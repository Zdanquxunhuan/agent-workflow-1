package com.yu.agent.workflow.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "jdbc")
public class JdbcCommerceRepository implements CommerceRepository {
    private static final Set<String> REFUNDABLE_ORDER_STATUSES =
            Set.of("PAID", "ALLOCATED", "SHIPPED", "DELIVERED", "PARTIALLY_REFUNDED");
    private static final Set<String> REFUNDABLE_PAYMENT_STATUSES =
            Set.of("SUCCEEDED", "PARTIALLY_REFUNDED");
    private static final Set<String> ADDRESS_CHANGE_STATUSES =
            Set.of("CREATED", "PAID", "ALLOCATED");
    private static final Set<String> SHIPPABLE_ORDER_STATUSES =
            Set.of("PAID", "ALLOCATED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCommerceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String backend() {
        return "mysql";
    }

    @Override
    public boolean ready() {
        Integer value = jdbc.queryForObject("select 1", Integer.class);
        return value != null && value == 1;
    }

    @Override
    public OrderDetail order(String orderId) {
        String id = normalize(orderId);
        List<OrderDetail> rows = jdbc.query("""
                select o.id, o.customer_id, c.name as customer_name, o.status, o.currency,
                       o.total_amount, o.paid_amount, o.refunded_amount, o.shipping_address
                from orders o
                join customers c on c.id = o.customer_id
                where o.id = ?
                """, (rs, rowNum) -> {
            BigDecimal paid = money(rs.getBigDecimal("paid_amount"));
            BigDecimal refunded = money(rs.getBigDecimal("refunded_amount"));
            return new OrderDetail(rs.getString("id"), rs.getString("customer_id"),
                    rs.getString("customer_name"), rs.getString("status"), rs.getString("currency"),
                    money(rs.getBigDecimal("total_amount")), paid, refunded, BigDecimal.ZERO,
                    rs.getString("shipping_address"), List.of());
        }, id);
        if (rows.isEmpty()) throw BusinessException.of("NOT_FOUND", "Order does not exist: " + id);

        OrderDetail base = rows.get(0);
        BigDecimal refundable = base.paidAmount()
                .subtract(base.refundedAmount())
                .subtract(pendingRefundAmount(id))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        List<OrderLine> items = jdbc.query("""
                select sku, title, quantity, unit_price
                from order_items where order_id = ? order by id asc
                """, (rs, rowNum) -> new OrderLine(rs.getString("sku"), rs.getString("title"),
                rs.getInt("quantity"), money(rs.getBigDecimal("unit_price"))), id);
        return new OrderDetail(base.orderId(), base.customerId(), base.customerName(), base.status(),
                base.currency(), base.totalAmount(), base.paidAmount(), base.refundedAmount(),
                refundable, base.shippingAddress(), items);
    }

    @Override
    public Optional<PaymentDetail> paymentByOrder(String orderId) {
        List<PaymentDetail> rows = jdbc.query("""
                select id, order_id, channel, status, amount, refunded_amount, gateway_txn_id, paid_at
                from payments where order_id = ?
                """, (rs, rowNum) -> new PaymentDetail(rs.getString("id"), rs.getString("order_id"),
                rs.getString("channel"), rs.getString("status"), money(rs.getBigDecimal("amount")),
                money(rs.getBigDecimal("refunded_amount")), rs.getString("gateway_txn_id"),
                instant(rs.getTimestamp("paid_at"))), normalize(orderId));
        return rows.stream().findFirst();
    }

    @Override
    public List<RefundRecord> refunds(String orderId) {
        return jdbc.query("""
                select id, order_id, amount, status, reason, gateway_refund_id, idempotency_key,
                       requested_by, requested_at, completed_at
                from refunds where order_id = ? order by requested_at desc
                """, (rs, rowNum) -> new RefundRecord(rs.getString("id"), rs.getString("order_id"),
                money(rs.getBigDecimal("amount")), rs.getString("status"), rs.getString("reason"),
                rs.getString("gateway_refund_id"), rs.getString("idempotency_key"),
                rs.getString("requested_by"), instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("completed_at")), false), normalize(orderId));
    }

    @Override
    public Optional<ShipmentDetail> shipmentByOrder(String orderId) {
        List<ShipmentDetail> rows = jdbc.query("""
                select id, order_id, carrier, tracking_no, status, shipped_at, delivered_at
                from shipments where order_id = ?
                """, (rs, rowNum) -> new ShipmentDetail(rs.getString("id"), rs.getString("order_id"),
                rs.getString("carrier"), rs.getString("tracking_no"), rs.getString("status"),
                instant(rs.getTimestamp("shipped_at")), instant(rs.getTimestamp("delivered_at")),
                List.of(), false), normalize(orderId));
        return rows.stream().findFirst().map(this::withEvents);
    }

    @Override
    public Optional<TicketDetail> ticket(String ticketId) {
        List<TicketDetail> rows = jdbc.query("""
                select id, order_id, customer_id, status, priority, subject
                from tickets where id = ?
                """, (rs, rowNum) -> new TicketDetail(rs.getString("id"), rs.getString("order_id"),
                rs.getString("customer_id"), rs.getString("status"), rs.getString("priority"),
                rs.getString("subject")), normalize(ticketId));
        return rows.stream().findFirst();
    }

    // Business rejections are expected outcomes that the task engine records as a
    // FAILED step inside the approval transaction, so they must not mark the shared
    // transaction rollback-only. All checks below run before any insert/update.
    @Override
    @Transactional(transactionManager = "workflowTransactionManager", noRollbackFor = BusinessException.class)
    public RefundRecord createRefund(String orderId, BigDecimal amount, String reason,
                                     String actor, String taskId, String idempotencyKey) {
        RefundRecord existing = refundByIdempotencyKey(idempotencyKey);
        if (existing != null) return replayed(existing);

        String id = normalize(orderId);
        OrderRow order = lockOrder(id);
        if (!REFUNDABLE_ORDER_STATUSES.contains(order.status())) {
            throw BusinessException.of("REFUND_NOT_ALLOWED",
                    "Order " + id + " is not refundable in status " + order.status());
        }
        PaymentRow payment = lockPayment(id);
        if (payment == null || !REFUNDABLE_PAYMENT_STATUSES.contains(payment.status())) {
            throw BusinessException.of("REFUND_NOT_ALLOWED", "Order " + id + " has no refundable payment");
        }
        BigDecimal available = order.paidAmount()
                .subtract(order.refundedAmount())
                .subtract(pendingRefundAmount(id))
                .max(BigDecimal.ZERO);
        if (amount.compareTo(available) > 0) {
            throw BusinessException.of("INVALID_ARGUMENT",
                    "Refund amount " + amount + " exceeds refundable amount " + available.setScale(2, RoundingMode.HALF_UP));
        }

        Instant now = Instant.now();
        String refundId = "REF-" + UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into refunds(id, order_id, payment_id, amount, status, reason, idempotency_key,
                                        gateway_refund_id, requested_by, task_id, requested_at, completed_at)
                    values (?, ?, ?, ?, 'PENDING', ?, ?, null, ?, ?, ?, null)
                    """, refundId, id, payment.id(), amount, reason, idempotencyKey, actor, taskId,
                    Timestamp.from(now));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("refundId", refundId);
            payload.put("orderId", id);
            payload.put("paymentId", payment.id());
            payload.put("amount", amount);
            insertCommand("PAYMENT", "REFUND", refundId, payload, idempotencyKey + ":cmd", now, taskId);
        } catch (DuplicateKeyException ex) {
            // A concurrent retry won the unique idempotency key; return its record.
            RefundRecord concurrent = refundByIdempotencyKey(idempotencyKey);
            if (concurrent != null) return replayed(concurrent);
            throw ex;
        }
        return new RefundRecord(refundId, id, amount, "PENDING", reason, null,
                idempotencyKey, actor, now, null, false);
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager", noRollbackFor = BusinessException.class)
    public AddressChange updateAddress(String orderId, String newAddress,
                                       String actor, String taskId, String idempotencyKey) {
        AddressChange existing = addressChangeByIdempotencyKey(idempotencyKey);
        if (existing != null) return new AddressChange(existing.orderId(), existing.oldAddress(),
                existing.newAddress(), existing.status(), existing.idempotencyKey(),
                existing.integrationCommandId(), true);

        String id = normalize(orderId);
        OrderRow order = lockOrder(id);
        if (!ADDRESS_CHANGE_STATUSES.contains(order.status())) {
            throw BusinessException.of("ADDRESS_CHANGE_NOT_ALLOWED",
                    "Address can only be changed before shipment; current status is " + order.status());
        }
        Instant now = Instant.now();
        String commandId;
        try {
            jdbc.update("""
                    update orders set shipping_address = ?, version = version + 1, updated_at = ?
                    where id = ?
                    """, newAddress, Timestamp.from(now), id);
            jdbc.update("""
                    insert into address_change_log(order_id, old_address, new_address, changed_by, task_id,
                                                   idempotency_key, changed_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, id, order.shippingAddress(), newAddress, actor, taskId, idempotencyKey,
                    Timestamp.from(now));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", id);
            payload.put("oldAddress", order.shippingAddress());
            payload.put("newAddress", newAddress);
            commandId = insertCommand("LOGISTICS", "UPDATE_ADDRESS", id, payload, idempotencyKey + ":cmd", now, taskId);
        } catch (DuplicateKeyException ex) {
            AddressChange concurrent = addressChangeByIdempotencyKey(idempotencyKey);
            if (concurrent != null) return new AddressChange(concurrent.orderId(), concurrent.oldAddress(),
                    concurrent.newAddress(), concurrent.status(), concurrent.idempotencyKey(),
                    concurrent.integrationCommandId(), true);
            throw ex;
        }
        return new AddressChange(id, order.shippingAddress(), newAddress, "APPLIED",
                idempotencyKey, commandId, false);
    }

    @Override
    @Transactional(transactionManager = "workflowTransactionManager", noRollbackFor = BusinessException.class)
    public ShipmentDetail createShipment(String orderId, String carrier,
                                         String actor, String taskId, String idempotencyKey) {
        ShipmentDetail existing = shipmentByIdempotencyKey(idempotencyKey);
        if (existing != null) return withReplayed(existing, true);

        String id = normalize(orderId);
        OrderRow order = lockOrder(id);
        if (!SHIPPABLE_ORDER_STATUSES.contains(order.status())) {
            throw BusinessException.of("INVALID_STATE",
                    "Order " + id + " cannot be shipped in status " + order.status());
        }
        Integer shipmentCount = jdbc.queryForObject(
                "select count(*) from shipments where order_id = ?", Integer.class, id);
        if (shipmentCount != null && shipmentCount > 0) {
            throw BusinessException.of("CONFLICT", "Shipment already exists for order " + id);
        }

        Instant now = Instant.now();
        String shipmentId = "SHP-" + UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into shipments(id, order_id, carrier, tracking_no, status, idempotency_key,
                                          created_at, updated_at, shipped_at, delivered_at)
                    values (?, ?, ?, null, 'CREATED', ?, ?, ?, null, null)
                    """, shipmentId, id, carrier, idempotencyKey, Timestamp.from(now), Timestamp.from(now));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("shipmentId", shipmentId);
            payload.put("orderId", id);
            payload.put("carrier", carrier);
            insertCommand("LOGISTICS", "CREATE_SHIPMENT", shipmentId, payload, idempotencyKey + ":cmd", now, taskId);
        } catch (DuplicateKeyException ex) {
            ShipmentDetail concurrent = shipmentByIdempotencyKey(idempotencyKey);
            if (concurrent != null) return withReplayed(concurrent, true);
            throw BusinessException.of("CONFLICT", "Shipment already exists for order " + id);
        }
        return new ShipmentDetail(shipmentId, id, carrier, null, "CREATED", null, null, List.of(), false);
    }

    private OrderRow lockOrder(String orderId) {
        List<OrderRow> rows = jdbc.query("""
                select id, status, paid_amount, refunded_amount, shipping_address
                from orders where id = ? for update
                """, (rs, rowNum) -> new OrderRow(rs.getString("id"), rs.getString("status"),
                money(rs.getBigDecimal("paid_amount")), money(rs.getBigDecimal("refunded_amount")),
                rs.getString("shipping_address")), orderId);
        if (rows.isEmpty()) throw BusinessException.of("NOT_FOUND", "Order does not exist: " + orderId);
        return rows.get(0);
    }

    private PaymentRow lockPayment(String orderId) {
        List<PaymentRow> rows = jdbc.query("""
                select id, status, amount, refunded_amount from payments where order_id = ? for update
                """, (rs, rowNum) -> new PaymentRow(rs.getString("id"), rs.getString("status"),
                money(rs.getBigDecimal("amount")), money(rs.getBigDecimal("refunded_amount"))), orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal pendingRefundAmount(String orderId) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from refunds
                where order_id = ? and status in ('PENDING', 'UNKNOWN')
                """, BigDecimal.class, orderId);
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private RefundRecord refundByIdempotencyKey(String key) {
        List<RefundRecord> rows = jdbc.query("""
                select id, order_id, amount, status, reason, gateway_refund_id, idempotency_key,
                       requested_by, requested_at, completed_at
                from refunds where idempotency_key = ?
                """, (rs, rowNum) -> new RefundRecord(rs.getString("id"), rs.getString("order_id"),
                money(rs.getBigDecimal("amount")), rs.getString("status"), rs.getString("reason"),
                rs.getString("gateway_refund_id"), rs.getString("idempotency_key"),
                rs.getString("requested_by"), instant(rs.getTimestamp("requested_at")),
                instant(rs.getTimestamp("completed_at")), false), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AddressChange addressChangeByIdempotencyKey(String key) {
        List<AddressChange> rows = jdbc.query("""
                select order_id, old_address, new_address, idempotency_key
                from address_change_log where idempotency_key = ?
                """, (rs, rowNum) -> new AddressChange(rs.getString("order_id"),
                rs.getString("old_address"), rs.getString("new_address"), "APPLIED",
                rs.getString("idempotency_key"), null, false), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ShipmentDetail shipmentByIdempotencyKey(String key) {
        List<ShipmentDetail> rows = jdbc.query("""
                select id, order_id, carrier, tracking_no, status, shipped_at, delivered_at
                from shipments where idempotency_key = ?
                """, (rs, rowNum) -> new ShipmentDetail(rs.getString("id"), rs.getString("order_id"),
                rs.getString("carrier"), rs.getString("tracking_no"), rs.getString("status"),
                instant(rs.getTimestamp("shipped_at")), instant(rs.getTimestamp("delivered_at")),
                List.of(), false), key);
        return rows.isEmpty() ? null : withEvents(rows.get(0));
    }

    private ShipmentDetail withEvents(ShipmentDetail shipment) {
        List<ShipmentEvent> events = jdbc.query("""
                select status, location, description, occurred_at
                from shipment_events where shipment_id = ? order by occurred_at asc, id asc
                """, (rs, rowNum) -> new ShipmentEvent(rs.getString("status"), rs.getString("location"),
                rs.getString("description"), instant(rs.getTimestamp("occurred_at"))), shipment.shipmentId());
        return new ShipmentDetail(shipment.shipmentId(), shipment.orderId(), shipment.carrier(),
                shipment.trackingNo(), shipment.status(), shipment.shippedAt(), shipment.deliveredAt(),
                events, shipment.replayed());
    }

    private String insertCommand(String channel, String commandType, String aggregateId,
                                 Map<String, Object> payload, String idempotencyKey, Instant now, String taskId) {
        String commandId = "CMD-" + UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into integration_commands(id, channel, command_type, aggregate_id, payload_json,
                                                     status, attempts, idempotency_key, external_ref, last_error,
                                                     next_attempt_at, locked_by, locked_until, created_at, updated_at, task_id)
                    values (?, ?, ?, ?, ?, 'PENDING', 0, ?, null, null, ?, null, null, ?, ?, ?)
                    """, commandId, channel, commandType, aggregateId,
                    objectMapper.writeValueAsString(payload), idempotencyKey,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), taskId);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize integration command", ex);
        }
        return commandId;
    }

    private RefundRecord replayed(RefundRecord record) {
        return new RefundRecord(record.refundId(), record.orderId(), record.amount(), record.status(),
                record.reason(), record.gatewayRefundId(), record.idempotencyKey(), record.requestedBy(),
                record.requestedAt(), record.completedAt(), true);
    }

    private ShipmentDetail withReplayed(ShipmentDetail shipment, boolean replayed) {
        return new ShipmentDetail(shipment.shipmentId(), shipment.orderId(), shipment.carrier(),
                shipment.trackingNo(), shipment.status(), shipment.shippedAt(), shipment.deliveredAt(),
                shipment.events(), replayed);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record OrderRow(String id, String status, BigDecimal paidAmount,
                            BigDecimal refundedAmount, String shippingAddress) {
    }

    private record PaymentRow(String id, String status, BigDecimal amount, BigDecimal refundedAmount) {
    }
}
