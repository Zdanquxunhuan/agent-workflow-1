package com.yu.agent.workflow.commerce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "jdbc")
public class JdbcCommerceOutbox implements CommerceOutbox {
    private final JdbcTemplate jdbc;
    private final IntegrationTaskTracker tracker;
    private final long pollSeconds;

    public JdbcCommerceOutbox(JdbcTemplate jdbc, IntegrationTaskTracker tracker,
                               @Value("${agent.commerce.dispatcher.retry-delay-seconds:10}") long pollSeconds) {
        this.jdbc = jdbc;
        this.tracker = tracker;
        this.pollSeconds = Math.max(1, pollSeconds);
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager")
    public List<CommerceCommand> claim(String owner, int limit, Duration lease) {
        Instant now = Instant.now();
        Instant lockedUntil = now.plus(lease);
        List<CommerceCommand> claimed = jdbc.query("""
                select id, channel, command_type, aggregate_id, payload_json, idempotency_key, attempts, status
                from integration_commands
                where (status in ('PENDING','AWAITING_CONFIRMATION','UNKNOWN') or (status = 'PROCESSING' and locked_until <= ?))
                  and (next_attempt_at is null or next_attempt_at <= ?)
                  and (locked_until is null or locked_until <= ?)
                order by created_at asc
                limit ?
                for update skip locked
                """, (rs, rowNum) -> new CommerceCommand(rs.getString("id"), rs.getString("channel"),
                rs.getString("command_type"), rs.getString("aggregate_id"), rs.getString("payload_json"),
                rs.getString("idempotency_key"), rs.getInt("attempts") + 1, rs.getString("status")),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Math.max(1, limit));
        for (CommerceCommand command : claimed) {
            jdbc.update("""
                    update integration_commands
                       set status = 'PROCESSING', attempts = ?, locked_by = ?, locked_until = ?, updated_at = ?
                     where id = ?
                    """, command.attempts(), owner, Timestamp.from(lockedUntil),
                    Timestamp.from(now), command.commandId());
        }
        return claimed;
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager")
    public void completeRefund(CommerceCommand command, String owner, String gatewayRefundId) {
        Instant now = Instant.now();
        if (!acknowledgeCommand(command, owner, gatewayRefundId, now)) return;
        if ("BUSINESS_HTTP".equals(command.channel())) {
            jdbc.update("update external_refund_requests set status='SUCCEEDED',external_ref=?,completed_at=? where id=? and status in ('PENDING','UNKNOWN')",
                    gatewayRefundId, Timestamp.from(now), command.aggregateId());
            completed(command);
            return;
        }
        List<RefundRow> refunds = jdbc.query("""
                select id, order_id, payment_id, amount, status from refunds where id = ? for update
                """, (rs, rowNum) -> new RefundRow(rs.getString("id"), rs.getString("order_id"),
                rs.getString("payment_id"), rs.getBigDecimal("amount"), rs.getString("status")),
                command.aggregateId());
        if (refunds.isEmpty()) return;
        RefundRow refund = refunds.get(0);
        if (!List.of("PENDING", "UNKNOWN").contains(refund.status())) return;

        jdbc.update("update refunds set status = 'SUCCEEDED', gateway_refund_id = ?, completed_at = ? where id = ?",
                gatewayRefundId, Timestamp.from(now), refund.id());

        PaymentRow payment = first(jdbc.query("""
                select id, amount, refunded_amount from payments where id = ? for update
                """, (rs, rowNum) -> new PaymentRow(rs.getString("id"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("refunded_amount")), refund.paymentId()));
        if (payment != null) {
            BigDecimal refunded = payment.refundedAmount().add(refund.amount());
            String status = refunded.compareTo(payment.amount()) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
            jdbc.update("update payments set refunded_amount = ?, status = ?, updated_at = ? where id = ?",
                    refunded, status, Timestamp.from(now), payment.id());
        }

        OrderRow order = first(jdbc.query("""
                select id, paid_amount, refunded_amount from orders where id = ? for update
                """, (rs, rowNum) -> new OrderRow(rs.getString("id"), rs.getBigDecimal("paid_amount"),
                rs.getBigDecimal("refunded_amount")), refund.orderId()));
        if (order != null) {
            BigDecimal refunded = order.refundedAmount().add(refund.amount());
            String status = refunded.compareTo(order.paidAmount()) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
            jdbc.update("""
                    update orders set refunded_amount = ?, status = ?, version = version + 1, updated_at = ?
                    where id = ?
                    """, refunded, status, Timestamp.from(now), order.id());
        }
        completed(command);
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager")
    public void completeShipment(CommerceCommand command, String owner, String trackingNo) {
        Instant now = Instant.now();
        if (!acknowledgeCommand(command, owner, trackingNo, now)) return;
        ShipmentRow shipment = first(jdbc.query("""
                select id, order_id, status from shipments where id = ? for update
                """, (rs, rowNum) -> new ShipmentRow(rs.getString("id"), rs.getString("order_id"),
                rs.getString("status")), command.aggregateId()));
        if (shipment == null || !"CREATED".equals(shipment.status())) return;

        jdbc.update("""
                update shipments set status = 'IN_TRANSIT', tracking_no = ?, shipped_at = ?, updated_at = ?
                where id = ?
                """, trackingNo, Timestamp.from(now), Timestamp.from(now), shipment.id());
        jdbc.update("""
                insert into shipment_events(shipment_id, status, location, description, occurred_at)
                values (?, 'IN_TRANSIT', '发货仓', '物流单已创建并交接承运商', ?)
                """, shipment.id(), Timestamp.from(now));
        jdbc.update("""
                update orders set status = 'SHIPPED', version = version + 1, updated_at = ?
                where id = ? and status in ('PAID', 'ALLOCATED')
                """, Timestamp.from(now), shipment.orderId());
        completed(command);
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager")
    public void completeAddressChange(CommerceCommand command, String owner, String externalRef) {
        if (acknowledgeCommand(command, owner, externalRef, Instant.now())) completed(command);
    }

    @Override
    public void retryLater(CommerceCommand command, String owner, Instant nextAttemptAt, String error) {
        jdbc.update("""
                update integration_commands
                   set status = 'PENDING', next_attempt_at = ?, last_error = ?,
                       locked_by = null, locked_until = null, updated_at = ?
                 where id = ? and locked_by = ? and attempts = ? and status = 'PROCESSING'
                """, Timestamp.from(nextAttemptAt), truncate(error), Timestamp.from(Instant.now()),
                command.commandId(), owner, command.attempts());
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager")
    public void markFailed(CommerceCommand command, String owner, String error) {
        if (!terminateCommand(command, owner, "FAILED", error)) return;
        failAggregate(command, "FAILED");
        review(command, error);
        event(command.commandId(), "integration-worker", "FAILED", error);
        tracker.refresh(command);
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager")
    public void markUnknown(CommerceCommand command, String owner, String error) {
        if (!terminateCommand(command, owner, "UNKNOWN", error)) return;
        failAggregate(command, "UNKNOWN");
        review(command, error);
        event(command.commandId(), "integration-worker", "UNKNOWN", error);
        tracker.refresh(command);
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager")
    public void markManualReview(CommerceCommand command, String owner, String reason) {
        if (!terminateCommand(command, owner, "MANUAL_REVIEW", reason)) return;
        failAggregate(command, "UNKNOWN");
        review(command, reason);
        event(command.commandId(), "integration-worker", "MANUAL_REVIEW", reason);
        tracker.refresh(command);
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager")
    public void awaitConfirmation(CommerceCommand command, String owner, String externalRef) {
        int changed = jdbc.update("""
                update integration_commands set status='AWAITING_CONFIRMATION',external_ref=?,next_attempt_at=?,
                    locked_by=null,locked_until=null,updated_at=?
                where id=? and locked_by=? and attempts=? and status='PROCESSING'
                """, externalRef, Timestamp.from(Instant.now().plusSeconds(pollSeconds)), Timestamp.from(Instant.now()),
                command.commandId(), owner, command.attempts());
        if (changed == 0) return;
        if ("BUSINESS_HTTP".equals(command.channel())) {
            jdbc.update("update external_refund_requests set status='PENDING',external_ref=? where id=? and status in ('PENDING','UNKNOWN')",
                    externalRef, command.aggregateId());
        }
        event(command.commandId(), "integration-worker", "AWAITING_CONFIRMATION", "Business accepted; waiting for final ledger outcome");
        tracker.refresh(command);
    }

    /** Manual checks acquire the same fenced lease as scheduled checks. No HTTP in this transaction. */
    @Transactional(transactionManager = "workflowTransactionManager")
    public CommerceCommand claimForReview(String commandId, String owner, Duration lease) {
        List<CommerceCommand> rows = jdbc.query("""
                select id,channel,command_type,aggregate_id,payload_json,idempotency_key,attempts,status
                from integration_commands where id=? and status in ('UNKNOWN','MANUAL_REVIEW','AWAITING_CONFIRMATION') for update
                """, (rs,n)->new CommerceCommand(rs.getString("id"),rs.getString("channel"),rs.getString("command_type"),
                rs.getString("aggregate_id"),rs.getString("payload_json"),rs.getString("idempotency_key"),rs.getInt("attempts")+1,rs.getString("status")), commandId);
        if (rows.isEmpty()) throw com.yu.agent.workflow.api.BusinessException.of("INVALID_STATE", "Command is complete or currently being processed; refresh before rechecking");
        CommerceCommand command = rows.get(0);
        jdbc.update("update integration_commands set status='PROCESSING',attempts=?,locked_by=?,locked_until=?,updated_at=? where id=?",
                command.attempts(), owner, Timestamp.from(Instant.now().plus(lease)), Timestamp.from(Instant.now()), commandId);
        return command;
    }

    public void event(String commandId, String actor, String action, String message) {
        jdbc.update("insert into integration_events(id,command_id,actor,action,message,occurred_at) values (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), commandId, actor, action, truncate(message == null ? action : message), Timestamp.from(Instant.now()));
    }

    private void review(CommerceCommand command, String reason) {
        jdbc.update("""
                insert into integration_reviews(id,command_id,status,reason,created_at) values (?,?,'OPEN',?,?)
                on duplicate key update reason=values(reason)
                """, "REV-"+UUID.randomUUID(), command.commandId(), truncate(reason), Timestamp.from(Instant.now()));
    }

    private void completed(CommerceCommand command) {
        jdbc.update("update integration_reviews set status='RESOLVED',resolution='Confirmed by external ledger',resolved_by='integration-worker',resolved_at=? where command_id=? and status='OPEN'",
                Timestamp.from(Instant.now()), command.commandId());
        event(command.commandId(), "integration-worker", "ACKNOWLEDGED", "Business completion confirmed");
        tracker.refresh(command);
    }

    private boolean acknowledgeCommand(CommerceCommand command, String owner, String externalRef, Instant now) {
        return jdbc.update("""
                update integration_commands
                   set status = 'ACKNOWLEDGED', external_ref = ?, last_error = null,
                       locked_by = null, locked_until = null, updated_at = ?
                 where id = ? and locked_by = ? and attempts = ? and status = 'PROCESSING'
                """, externalRef, Timestamp.from(now), command.commandId(), owner, command.attempts()) > 0;
    }

    private boolean terminateCommand(CommerceCommand command, String owner, String status, String error) {
        return jdbc.update("""
                update integration_commands
                   set status = ?, last_error = ?, locked_by = null, locked_until = null, updated_at = ?, next_attempt_at = ?
                 where id = ? and locked_by = ? and attempts = ? and status = 'PROCESSING'
                """, status, truncate(error), Timestamp.from(Instant.now()), Timestamp.from(Instant.now().plusSeconds(pollSeconds)),
                command.commandId(), owner, command.attempts()) > 0;
    }

    private void failAggregate(CommerceCommand command, String status) {
        Instant now = Instant.now();
        if ("BUSINESS_HTTP".equals(command.channel())) {
            jdbc.update("update external_refund_requests set status=?,completed_at=? where id=? and status in ('PENDING','UNKNOWN')",
                    status, "FAILED".equals(status) ? Timestamp.from(now) : null, command.aggregateId());
            return;
        }
        switch (command.commandType()) {
            case "REFUND" -> jdbc.update(
                    "update refunds set status = ?, completed_at = ? where id = ? and status = 'PENDING'",
                    status, Timestamp.from(now), command.aggregateId());
            case "CREATE_SHIPMENT" -> jdbc.update(
                    "update shipments set status = ?, updated_at = ? where id = ? and status = 'CREATED'",
                    status, Timestamp.from(now), command.aggregateId());
            default -> {
                // Address changes are already applied locally; only the command is marked.
            }
        }
    }

    private String truncate(String error) {
        if (error == null) return null;
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    private static <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    private record RefundRow(String id, String orderId, String paymentId, BigDecimal amount, String status) {
    }

    private record PaymentRow(String id, BigDecimal amount, BigDecimal refundedAmount) {
    }

    private record OrderRow(String id, BigDecimal paidAmount, BigDecimal refundedAmount) {
    }

    private record ShipmentRow(String id, String orderId, String status) {
    }
}
