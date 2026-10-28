package com.yu.agent.workflow.commerce;

import com.yu.agent.workflow.api.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Offline/test implementation with the same demo dataset as the MySQL seed.
 * Writes apply immediately because there is no external gateway in this profile.
 */
@Component
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryCommerceRepository implements CommerceRepository {
    private static final Set<String> REFUNDABLE_ORDER_STATUSES =
            Set.of("PAID", "ALLOCATED", "SHIPPED", "DELIVERED", "PARTIALLY_REFUNDED");
    private static final Set<String> REFUNDABLE_PAYMENT_STATUSES =
            Set.of("SUCCEEDED", "PARTIALLY_REFUNDED");
    private static final Set<String> ADDRESS_CHANGE_STATUSES =
            Set.of("CREATED", "PAID", "ALLOCATED");
    private static final Set<String> SHIPPABLE_ORDER_STATUSES =
            Set.of("PAID", "ALLOCATED");

    private final Map<String, OrderDetail> orders = new LinkedHashMap<>();
    private final Map<String, PaymentDetail> payments = new LinkedHashMap<>();
    private final Map<String, List<RefundRecord>> refunds = new LinkedHashMap<>();
    private final Map<String, ShipmentDetail> shipments = new LinkedHashMap<>();
    private final Map<String, ShipmentDetail> shipmentsByIdempotency = new LinkedHashMap<>();
    private final Map<String, TicketDetail> tickets = new LinkedHashMap<>();
    private final Map<String, AddressChange> addressChanges = new LinkedHashMap<>();

    public InMemoryCommerceRepository() {
        seed();
    }

    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public synchronized OrderDetail order(String orderId) {
        OrderDetail order = orders.get(normalize(orderId));
        if (order == null) throw BusinessException.of("NOT_FOUND", "Order does not exist: " + normalize(orderId));
        return order;
    }

    @Override
    public synchronized Optional<PaymentDetail> paymentByOrder(String orderId) {
        return Optional.ofNullable(payments.get(normalize(orderId)));
    }

    @Override
    public synchronized List<RefundRecord> refunds(String orderId) {
        return List.copyOf(refunds.getOrDefault(normalize(orderId), List.of()));
    }

    @Override
    public synchronized Optional<ShipmentDetail> shipmentByOrder(String orderId) {
        return Optional.ofNullable(shipments.get(normalize(orderId)));
    }

    @Override
    public synchronized Optional<TicketDetail> ticket(String ticketId) {
        return Optional.ofNullable(tickets.get(normalize(ticketId)));
    }

    @Override
    public synchronized RefundRecord createRefund(String orderId, BigDecimal amount, String reason,
                                                  String actor, String taskId, String idempotencyKey) {
        RefundRecord existing = findRefund(idempotencyKey);
        if (existing != null) return replayed(existing);

        String id = normalize(orderId);
        OrderDetail order = order(id);
        if (!REFUNDABLE_ORDER_STATUSES.contains(order.status())) {
            throw BusinessException.of("REFUND_NOT_ALLOWED",
                    "Order " + id + " is not refundable in status " + order.status());
        }
        PaymentDetail payment = payments.get(id);
        if (payment == null || !REFUNDABLE_PAYMENT_STATUSES.contains(payment.status())) {
            throw BusinessException.of("REFUND_NOT_ALLOWED", "Order " + id + " has no refundable payment");
        }
        BigDecimal available = order.refundableAmount();
        if (amount.compareTo(available) > 0) {
            throw BusinessException.of("INVALID_ARGUMENT",
                    "Refund amount " + amount + " exceeds refundable amount " + available);
        }

        Instant now = Instant.now();
        BigDecimal newRefunded = payment.refundedAmount().add(amount);
        String paymentStatus = newRefunded.compareTo(payment.amount()) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
        payments.put(id, new PaymentDetail(payment.paymentId(), id, payment.channel(), paymentStatus,
                payment.amount(), newRefunded, payment.gatewayTxnId(), payment.paidAt()));

        String orderStatus = newRefunded.compareTo(order.paidAmount()) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
        replaceOrder(order, orderStatus, order.shippingAddress(), newRefunded, order.refundableAmount().subtract(amount));

        RefundRecord record = new RefundRecord("REF-" + UUID.randomUUID(), id, amount, "SUCCEEDED",
                reason, "MEM-REF-" + UUID.randomUUID(), idempotencyKey, actor, now, now, false);
        refunds.computeIfAbsent(id, key -> new ArrayList<>()).add(record);
        return record;
    }

    @Override
    public synchronized AddressChange updateAddress(String orderId, String newAddress,
                                                    String actor, String taskId, String idempotencyKey) {
        AddressChange existing = addressChanges.get(idempotencyKey);
        if (existing != null) {
            return new AddressChange(existing.orderId(), existing.oldAddress(), existing.newAddress(),
                    existing.status(), existing.idempotencyKey(), existing.integrationCommandId(), true);
        }
        String id = normalize(orderId);
        OrderDetail order = order(id);
        if (!ADDRESS_CHANGE_STATUSES.contains(order.status())) {
            throw BusinessException.of("ADDRESS_CHANGE_NOT_ALLOWED",
                    "Address can only be changed before shipment; current status is " + order.status());
        }
        replaceOrder(order, order.status(), newAddress, order.refundedAmount(), order.refundableAmount());
        AddressChange change = new AddressChange(id, order.shippingAddress(), newAddress, "APPLIED",
                idempotencyKey, "MEM-CMD-" + UUID.randomUUID(), false);
        addressChanges.put(idempotencyKey, change);
        return change;
    }

    @Override
    public synchronized ShipmentDetail createShipment(String orderId, String carrier,
                                                      String actor, String taskId, String idempotencyKey) {
        ShipmentDetail existing = shipmentsByIdempotency.get(idempotencyKey);
        if (existing != null) return withReplayed(existing, true);

        String id = normalize(orderId);
        OrderDetail order = order(id);
        if (!SHIPPABLE_ORDER_STATUSES.contains(order.status())) {
            throw BusinessException.of("INVALID_STATE",
                    "Order " + id + " cannot be shipped in status " + order.status());
        }
        if (shipments.containsKey(id)) {
            throw BusinessException.of("CONFLICT", "Shipment already exists for order " + id);
        }
        Instant now = Instant.now();
        String shipmentId = "SHP-" + UUID.randomUUID();
        ShipmentDetail shipment = new ShipmentDetail(shipmentId, id, carrier,
                "MEM" + Math.abs(shipmentId.hashCode()), "IN_TRANSIT", now, null,
                List.of(new ShipmentEvent("IN_TRANSIT", "发货仓", "物流单已创建并交接承运商", now)), false);
        shipments.put(id, shipment);
        shipmentsByIdempotency.put(idempotencyKey, shipment);
        replaceOrder(order, "SHIPPED", order.shippingAddress(), order.refundedAmount(), order.refundableAmount());
        return shipment;
    }

    private void replaceOrder(OrderDetail order, String status, String address,
                              BigDecimal refundedAmount, BigDecimal refundableAmount) {
        orders.put(order.orderId(), new OrderDetail(order.orderId(), order.customerId(), order.customerName(),
                status, order.currency(), order.totalAmount(), order.paidAmount(), refundedAmount,
                refundableAmount.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP), address, order.items()));
    }

    private RefundRecord findRefund(String idempotencyKey) {
        for (List<RefundRecord> records : refunds.values()) {
            for (RefundRecord record : records) {
                if (record.idempotencyKey().equals(idempotencyKey)) return record;
            }
        }
        return null;
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

    private void seed() {
        putOrder("ORD-1001", "CUST-001", "张伟", "SHIPPED", "199.00", "199.00", "0.00",
                "上海市浦东新区世纪大道 100 号 8 栋 1201",
                line("SKU-A-001", "无线降噪耳机", 1, "199.00"));
        putOrder("ORD-1002", "CUST-002", "李娜", "DELIVERED", "59.00", "59.00", "0.00",
                "杭州市西湖区文三路 88 号 3 单元 502",
                line("SKU-A-002", "快充数据线 2m", 1, "59.00"));
        putOrder("ORD-1003", "CUST-001", "张伟", "PAID", "328.00", "328.00", "0.00",
                "上海市徐汇区漕溪北路 18 号 6 楼",
                line("SKU-B-001", "机械键盘 87 键", 1, "199.00"),
                line("SKU-B-002", "人体工学腕托", 1, "129.00"));
        putOrder("ORD-1004", "CUST-003", "王强", "ALLOCATED", "89.90", "89.90", "0.00",
                "北京市朝阳区建国路 91 号 2 号楼 1703",
                line("SKU-C-001", "桌面收纳盒", 1, "89.90"));
        putOrder("ORD-1005", "CUST-004", "赵敏", "PARTIALLY_REFUNDED", "450.00", "450.00", "50.00",
                "广州市天河区体育西路 103 号 2201",
                line("SKU-D-001", "智能手表表带", 2, "150.00"),
                line("SKU-D-002", "屏幕保护膜", 1, "150.00"));
        putOrder("ORD-1006", "CUST-002", "李娜", "CANCELLED", "120.00", "0.00", "0.00",
                "杭州市滨江区江南大道 588 号 9 楼",
                line("SKU-E-001", "旅行背包 25L", 1, "120.00"));
        putOrder("ORD-1007", "CUST-003", "王强", "REFUNDED", "35.00", "35.00", "35.00",
                "北京市海淀区中关村大街 27 号 1105",
                line("SKU-F-001", "USB-C 扩展坞", 1, "35.00"));
        putOrder("ORD-1008", "CUST-004", "赵敏", "PAID", "1299.00", "1299.00", "0.00",
                "深圳市南山区科技园科苑路 15 号 5 栋",
                line("SKU-G-001", "轻薄笔记本 14 英寸", 1, "1299.00"));
        putOrder("ORD-1009", "CUST-001", "张伟", "CREATED", "66.00", "0.00", "0.00",
                "上海市静安区南京西路 1266 号 2802",
                line("SKU-H-001", "保温杯 500ml", 1, "66.00"));
        putOrder("ORD-1010", "CUST-002", "李娜", "SHIPPED", "240.00", "240.00", "0.00",
                "杭州市余杭区文一西路 969 号 4 号楼",
                line("SKU-I-001", "空气净化器滤芯", 2, "120.00"));

        seedPayment("PAY-1001", "ORD-1001", "ALIPAY", "SUCCEEDED", "199.00", "0.00");
        seedPayment("PAY-1002", "ORD-1002", "WECHAT", "SUCCEEDED", "59.00", "0.00");
        seedPayment("PAY-1003", "ORD-1003", "ALIPAY", "SUCCEEDED", "328.00", "0.00");
        seedPayment("PAY-1004", "ORD-1004", "WECHAT", "SUCCEEDED", "89.90", "0.00");
        seedPayment("PAY-1005", "ORD-1005", "CARD", "PARTIALLY_REFUNDED", "450.00", "50.00");
        seedPayment("PAY-1007", "ORD-1007", "WECHAT", "REFUNDED", "35.00", "35.00");
        seedPayment("PAY-1008", "ORD-1008", "ALIPAY", "SUCCEEDED", "1299.00", "0.00");
        seedPayment("PAY-1010", "ORD-1010", "ALIPAY", "SUCCEEDED", "240.00", "0.00");

        seedRefund("ORD-1005", "REF-5001", "50.00", "SUCCEEDED",
                "表带尺寸不合适，部分退款", "seed-refund-5001", "SANDBOX-REF-5001");
        seedRefund("ORD-1007", "REF-5002", "35.00", "SUCCEEDED",
                "商品未使用，整单退款", "seed-refund-5002", "SANDBOX-REF-5002");

        seedShipment("ORD-1001", "SHP-3001", "SF Express", "SF-SEED-1001", "IN_TRANSIT", "seed-shipment-3001");
        seedShipment("ORD-1002", "SHP-3002", "YTO Express", "YTO-SEED-1002", "DELIVERED", "seed-shipment-3002");
        seedShipment("ORD-1010", "SHP-3010", "JD Logistics", "JD-SEED-1010", "IN_TRANSIT", "seed-shipment-3010");

        tickets.put("TKT-2001", new TicketDetail("TKT-2001", "ORD-1001", "CUST-001",
                "OPEN", "NORMAL", "物流超过 48 小时未更新"));
        tickets.put("TKT-2002", new TicketDetail("TKT-2002", "ORD-1002", "CUST-002",
                "CLOSED", "HIGH", "退款到账时间咨询"));
    }

    private void putOrder(String id, String customerId, String customerName, String status,
                          String total, String paid, String refunded, String address, OrderLine... items) {
        BigDecimal paidAmount = new BigDecimal(paid);
        BigDecimal refundedAmount = new BigDecimal(refunded);
        orders.put(id, new OrderDetail(id, customerId, customerName, status, "CNY",
                new BigDecimal(total), paidAmount, refundedAmount,
                paidAmount.subtract(refundedAmount), address, List.of(items)));
    }

    private OrderLine line(String sku, String title, int quantity, String unitPrice) {
        return new OrderLine(sku, title, quantity, new BigDecimal(unitPrice));
    }

    private void seedPayment(String id, String orderId, String channel, String status,
                             String amount, String refunded) {
        payments.put(orderId, new PaymentDetail(id, orderId, channel, status,
                new BigDecimal(amount), new BigDecimal(refunded), "SEED-" + id, Instant.parse("2026-10-01T08:00:00Z")));
    }

    private void seedRefund(String orderId, String id, String amount, String status,
                            String reason, String key, String gatewayRefundId) {
        Instant now = Instant.parse("2026-09-02T02:00:00Z");
        refunds.computeIfAbsent(orderId, ignored -> new ArrayList<>())
                .add(new RefundRecord(id, orderId, new BigDecimal(amount), status, reason,
                        gatewayRefundId, key, "seed-supervisor", now, now, false));
    }

    private void seedShipment(String orderId, String id, String carrier, String trackingNo,
                              String status, String key) {
        Instant shipped = Instant.parse("2026-09-19T02:00:00Z");
        Instant delivered = "DELIVERED".equals(status) ? Instant.parse("2026-09-14T10:05:00Z") : null;
        shipments.put(orderId, new ShipmentDetail(id, orderId, carrier, trackingNo, status,
                shipped, delivered,
                List.of(new ShipmentEvent("CREATED", "发货仓", "仓库已出库", shipped)), false));
        shipmentsByIdempotency.put(key, shipments.get(orderId));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
