package com.yu.agent.workflow.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Remote reads and durable local refund intents; no external HTTP inside write transactions. */
@Repository
@Primary
@ConditionalOnProperty(name="agent.commerce.gateway", havingValue="http")
public class HttpCommerceRepository implements CommerceRepository {
    private final HttpBusinessClient client;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean writesEnabled;

    public HttpCommerceRepository(HttpBusinessClient client, JdbcTemplate jdbc, ObjectMapper mapper,
                                   @Value("${agent.commerce.http.writes-enabled:false}") boolean writesEnabled) {
        this.client = client; this.jdbc = jdbc; this.mapper = mapper;
        this.writesEnabled = writesEnabled;
    }
    public String backend() { return "http-business"; }
    public boolean ready() { return Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class)) && client.ready(); }
    public OrderDetail order(String id) { return client.order(id); }
    public Optional<PaymentDetail> paymentByOrder(String id) { return client.payment(id); }
    public Optional<ShipmentDetail> shipmentByOrder(String id) { return client.shipment(id); }
    public Optional<TicketDetail> ticket(String id) { throw BusinessException.of("UNSUPPORTED_OPERATION", "External ticket integration is not enabled"); }

    public List<RefundRecord> refunds(String orderId) { return find("where order_id=? order by requested_at", orderId); }

    @Transactional(transactionManager="workflowTransactionManager", noRollbackFor=BusinessException.class)
    public RefundRecord createRefund(String orderId, BigDecimal amount, String reason, String actor, String taskId, String key) {
        if (!writesEnabled) throw BusinessException.of("FORBIDDEN", "External writes are disabled by the operator");
        List<RefundRecord> existing = find("where idempotency_key=?", key);
        if (!existing.isEmpty()) {
            RefundRecord old = existing.get(0);
            if (!old.orderId().equals(orderId) || old.amount().compareTo(amount) != 0) throw BusinessException.of("IDEMPOTENCY_CONFLICT", "Refund key has different parameters");
            return new RefundRecord(old.refundId(), old.orderId(), old.amount(), old.status(), old.reason(), old.gatewayRefundId(), key, old.requestedBy(), old.requestedAt(), old.completedAt(), true);
        }
        Instant now = Instant.now();
        String refundId = "XREF-" + UUID.randomUUID();
        jdbc.update("""
                insert into external_refund_requests(id,order_id,amount,status,reason,idempotency_key,requested_by,task_id,requested_at)
                values (?,?,?,'PENDING',?,?,?,?,?)
                """, refundId, orderId, amount, reason, key, actor, taskId, Timestamp.from(now));
        try {
            jdbc.update("""
                    insert into integration_commands(id,channel,command_type,aggregate_id,payload_json,status,attempts,idempotency_key,next_attempt_at,created_at,updated_at,task_id)
                    values (?,'BUSINESS_HTTP','REFUND',?,?,'PENDING',0,?,?,?,?,?)
                    """, "CMD-" + UUID.randomUUID(), refundId,
                    mapper.writeValueAsString(Map.of("refundId", refundId, "orderId", orderId, "amount", amount)),
                    key, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), taskId);
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize refund intent", ex); }
        return new RefundRecord(refundId, orderId, amount, "PENDING", reason, null, key, actor, now, null, false);
    }

    public AddressChange updateAddress(String orderId, String newAddress, String actor, String taskId, String key) {
        throw BusinessException.of("UNSUPPORTED_OPERATION", "HTTP address writes are disabled; only refunds are integrated");
    }
    public ShipmentDetail createShipment(String orderId, String carrier, String actor, String taskId, String key) {
        throw BusinessException.of("UNSUPPORTED_OPERATION", "HTTP carrier writes are disabled; only refunds are integrated");
    }
    private List<RefundRecord> find(String suffix, String value) {
        return jdbc.query("select * from external_refund_requests " + suffix, (rs,n) -> new RefundRecord(
                rs.getString("id"), rs.getString("order_id"), rs.getBigDecimal("amount"), rs.getString("status"), rs.getString("reason"),
                rs.getString("external_ref"), rs.getString("idempotency_key"), rs.getString("requested_by"), rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(), false), value);
    }
}
