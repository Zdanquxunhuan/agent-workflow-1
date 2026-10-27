package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.persistence.AuditOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Claims pending audit events with a lease and retries failed publication.
 * Disabled by default until a real sink is configured.
 */
@Component
@ConditionalOnProperty(name = "agent.audit.outbox.publisher.enabled", havingValue = "true")
public class AuditOutboxPublisher {
    private final AuditOutboxRepository outbox;
    private final AuditEventSink sink;
    private final int batchSize;
    private final Duration lease;
    private final Duration retryDelay;
    private final String owner = "audit-publisher-" + UUID.randomUUID();

    public AuditOutboxPublisher(AuditOutboxRepository outbox, AuditEventSink sink) {
        this(outbox, sink, 50, 30, 10);
    }

    @Autowired
    public AuditOutboxPublisher(AuditOutboxRepository outbox, AuditEventSink sink,
                                @Value("${agent.audit.outbox.publisher.batch-size:50}") int batchSize,
                                @Value("${agent.audit.outbox.publisher.lease-seconds:30}") long leaseSeconds,
                                @Value("${agent.audit.outbox.publisher.retry-delay-seconds:10}") long retryDelaySeconds) {
        this.outbox = outbox;
        this.sink = sink;
        this.batchSize = Math.max(1, batchSize);
        this.lease = Duration.ofSeconds(Math.max(1, leaseSeconds));
        this.retryDelay = Duration.ofSeconds(Math.max(1, retryDelaySeconds));
    }

    @Scheduled(fixedDelayString = "${agent.audit.outbox.publisher.fixed-delay-ms:5000}")
    public void scheduledPublish() {
        publishOnce();
    }

    public int publishOnce() {
        List<AuditEvent> claimed = outbox.claimPending(owner, batchSize, Instant.now(), lease);
        int published = 0;
        for (AuditEvent event : claimed) {
            try {
                sink.publish(event);
                outbox.markPublished(event.eventId(), owner, Instant.now());
                published++;
            } catch (RuntimeException ex) {
                outbox.markFailed(event.eventId(), owner, Instant.now().plus(retryDelay), safeMessage(ex));
            }
        }
        return published;
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
