package com.yu.agent.workflow.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Leases pending payment/logistics commands and hands them to the configured
 * gateway outside database transactions. Settlement of the command, local
 * business state, task and audit commits together; expired leases use fenced updates.
 */
@Component
@ConditionalOnExpression("'${agent.workflow.persistence:memory}' == 'jdbc' and '${agent.commerce.dispatcher.enabled:true}' == 'true'")
public class CommerceCommandDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CommerceCommandDispatcher.class);

    private final CommerceOutbox outbox;
    private final CommerceGateway gateway;
    private final int batchSize;
    private final Duration lease;
    private final Duration retryDelay;
    private final int maxAttempts;
    private final String owner = "commerce-dispatcher-" + UUID.randomUUID();

    public CommerceCommandDispatcher(CommerceOutbox outbox, CommerceGateway gateway,
                                     @Value("${agent.commerce.dispatcher.batch-size:20}") int batchSize,
                                     @Value("${agent.commerce.dispatcher.lease-seconds:30}") long leaseSeconds,
                                     @Value("${agent.commerce.dispatcher.retry-delay-seconds:10}") long retryDelaySeconds,
                                     @Value("${agent.commerce.dispatcher.max-attempts:5}") int maxAttempts) {
        this.outbox = outbox;
        this.gateway = gateway;
        this.batchSize = Math.max(1, batchSize);
        this.lease = Duration.ofSeconds(Math.max(1, leaseSeconds));
        this.retryDelay = Duration.ofSeconds(Math.max(1, retryDelaySeconds));
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${agent.commerce.dispatcher.fixed-delay-ms:3000}")
    public void scheduledDispatch() {
        try {
            dispatchOnce();
        } catch (RuntimeException ex) {
            log.warn("commerce-dispatch failed: {}", safeMessage(ex));
        }
    }

    public int dispatchOnce() {
        int processed = 0;
        // Lease immediately before execution, rather than expiring a whole serial batch in advance.
        for (int i = 0; i < batchSize; i++) {
            List<CommerceCommand> commands = outbox.claim(owner, 1, lease);
            if (commands.isEmpty()) break;
            CommerceCommand command = commands.get(0);
            process(command, owner, false);
            processed++;
        }
        return processed;
    }

    /** queryOnly is used for manual reconciliation; it can never issue a new write. */
    public void process(CommerceCommand command, String leaseOwner, boolean queryOnly) {
            try {
                CommerceGateway.GatewayResult result;
                if (gateway.supportsStatusQuery()) {
                    result = gateway.query(command);
                    if ("NOT_FOUND".equals(result.status())) {
                        if (queryOnly || List.of("UNKNOWN", "AWAITING_CONFIRMATION", "MANUAL_REVIEW").contains(command.previousStatus())) {
                            outbox.markManualReview(command, leaseOwner, "Previously uncertain or accepted operation is absent from the ledger; provider investigation required");
                            return;
                        }
                        // The provider contract atomically enforces the same idempotency key.
                        result = gateway.execute(command);
                    }
                    if ("PENDING".equals(result.status())) {
                        if (command.attempts() >= maxAttempts) outbox.markManualReview(command, leaseOwner, "Confirmation budget exhausted; investigate external pending refund");
                        else outbox.awaitConfirmation(command, leaseOwner, result.externalRef());
                        return;
                    }
                    if ("FAILED".equals(result.status())) { outbox.markFailed(command, leaseOwner, result.message()); return; }
                    if (!"SUCCEEDED".equals(result.status())) throw new CommerceGateway.GatewayTimeoutException("Unrecognized external outcome");
                } else {
                    if (queryOnly) throw new CommerceGateway.GatewayTimeoutException("Gateway does not provide a status-query contract");
                    if ("PROCESSING".equals(command.previousStatus()) || "UNKNOWN".equals(command.previousStatus())) {
                        outbox.markManualReview(command, leaseOwner, "Expired write lease without a status-query contract; refusing blind replay");
                        return;
                    }
                    result = gateway.execute(command);
                }
                switch (command.commandType()) {
                    case "REFUND" -> outbox.completeRefund(command, leaseOwner, result.externalRef());
                    case "CREATE_SHIPMENT" -> outbox.completeShipment(command, leaseOwner,
                            result.trackingNo() == null || result.trackingNo().isBlank()
                                    ? "PENDING" : result.trackingNo());
                    case "UPDATE_ADDRESS" -> outbox.completeAddressChange(command, leaseOwner, result.externalRef());
                    default -> outbox.markFailed(command, leaseOwner, "Unknown command type: " + command.commandType());
                }
                log.info("commerce-command-ack commandId={} type={} attempt={}",
                        command.commandId(), command.commandType(), command.attempts());
            } catch (CommerceGateway.GatewayTimeoutException ex) {
                // The downstream may have applied the operation. Never replay blindly.
                if (command.attempts() >= maxAttempts || queryOnly) outbox.markManualReview(command, leaseOwner, safeMessage(ex));
                else outbox.markUnknown(command, leaseOwner, safeMessage(ex));
                log.warn("commerce-command-unknown commandId={} type={}", command.commandId(), command.commandType());
            } catch (RuntimeException ex) {
                if (gateway.supportsStatusQuery() || "PROCESSING".equals(command.previousStatus())) {
                    outbox.markManualReview(command, leaseOwner, "Unexpected failure; external outcome must be reconciled");
                } else if (command.attempts() >= maxAttempts) {
                    outbox.markFailed(command, leaseOwner, safeMessage(ex));
                    log.error("commerce-command-failed commandId={} type={}", command.commandId(), command.commandType());
                } else {
                    outbox.retryLater(command, leaseOwner, Instant.now().plus(retryDelay), safeMessage(ex));
                    log.warn("commerce-command-retry commandId={} attempt={}", command.commandId(), command.attempts());
                }
            }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
