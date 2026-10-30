package com.yu.business;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BusinessLedger {
    private final JdbcTemplate jdbc;
    private final long settlementDelay;

    public BusinessLedger(JdbcTemplate jdbc, @Value("${business.settlement-delay-seconds:3}") long delay) {
        this.jdbc = jdbc;
        this.settlementDelay = Math.max(1, delay);
    }

    public Map<String, Object> order(String id) {
        Map<String, Object> row = one("select * from orders where id = ?", id);
        BigDecimal paid = (BigDecimal) row.get("paid_amount");
        BigDecimal refunded = (BigDecimal) row.get("refunded_amount");
        BigDecimal reserved = jdbc.queryForObject("select coalesce(sum(amount),0) from refunds where order_id=? and status='PENDING'", BigDecimal.class, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", id); result.put("customerId", row.get("customer_id"));
        result.put("customerName", row.get("customer_name")); result.put("status", row.get("status"));
        result.put("currency", row.get("currency")); result.put("totalAmount", row.get("total_amount"));
        result.put("paidAmount", paid); result.put("refundedAmount", refunded);
        result.put("refundableAmount", paid.subtract(refunded).subtract(reserved).max(BigDecimal.ZERO));
        result.put("shippingAddress", row.get("shipping_address"));
        result.put("items", jdbc.query("select * from order_items where order_id=?", (rs, n) -> Map.of(
                "sku", rs.getString("sku"), "title", rs.getString("title"), "quantity", rs.getInt("quantity"),
                "unitPrice", rs.getBigDecimal("unit_price")), id));
        result.put("synthetic", true); result.put("source", "local-business-mysql");
        return result;
    }

    public Map<String, Object> payment(String orderId) {
        List<Map<String,Object>> rows = jdbc.query("select * from payments where order_id=?", (rs,n) -> Map.<String,Object>of(
                "paymentId", rs.getString("id"), "orderId", orderId, "channel", "LOCAL_LEDGER",
                "status", rs.getString("status"), "amount", rs.getBigDecimal("amount"), "refundedAmount", rs.getBigDecimal("refunded_amount"),
                "gatewayTxnId", rs.getString("gateway_txn_id"), "paidAt", rs.getTimestamp("paid_at").toInstant()), orderId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business payment not found");
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> submit(String key, String orderId, BigDecimal amount) {
        Map<String, Object> order = one("select * from orders where id=? for update", orderId);
        List<Map<String, Object>> prior = jdbc.queryForList("select * from refunds where idempotency_key=? for update", key);
        if (!prior.isEmpty()) {
            Map<String, Object> old = prior.get(0);
            if (!orderId.equals(old.get("order_id")) || amount.compareTo((BigDecimal) old.get("amount")) != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key belongs to a different refund");
            }
            return refundResponse(old, false);
        }
        BigDecimal reserved = jdbc.queryForObject("select coalesce(sum(amount),0) from refunds where order_id=? and status='PENDING'", BigDecimal.class, orderId);
        BigDecimal available = ((BigDecimal) order.get("paid_amount")).subtract((BigDecimal) order.get("refunded_amount")).subtract(reserved);
        if (amount.compareTo(available) > 0 || amount.signum() <= 0 || "CREATED".equals(order.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund exceeds available paid amount or order is unpaid");
        }
        Instant now = Instant.now();
        jdbc.update("""
                insert into refunds(id,idempotency_key,order_id,amount,status,behavior,settle_after,created_at)
                values (?,?,?,?,'PENDING',?,?,?)
                """, "BREF-" + UUID.randomUUID(), key, orderId, amount, order.get("refund_behavior"),
                Timestamp.from(now.plusSeconds(settlementDelay)), Timestamp.from(now));
        return refundResponse(one("select * from refunds where idempotency_key=?", key), true);
    }

    public Map<String, Object> refund(String key) {
        return refundResponse(one("select * from refunds where idempotency_key=?", key), false);
    }

    @Scheduled(fixedDelayString = "${business.settlement-interval-ms:500}")
    @Transactional
    public void settleDue() {
        List<String> keys = jdbc.query("select idempotency_key from refunds where status='PENDING' and behavior <> 'HOLD' and settle_after <= ? order by created_at limit 20",
                (rs, n) -> rs.getString(1), Timestamp.from(Instant.now()));
        for (String key : keys) {
            Map<String, Object> row = one("select * from refunds where idempotency_key=?", key);
            settle(key, "DECLINE".equals(row.get("behavior")) ? "FAILED" : "SUCCEEDED", "Automatic synthetic ledger settlement");
        }
    }

    @Transactional
    public Map<String, Object> settle(String key, String status, String reason) {
        if (!List.of("SUCCEEDED", "FAILED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected SUCCEEDED or FAILED");
        Map<String, Object> snapshot = one("select * from refunds where idempotency_key=?", key);
        String orderId = (String) snapshot.get("order_id");
        one("select * from orders where id=? for update", orderId);
        Map<String, Object> refund = one("select * from refunds where idempotency_key=? for update", key);
        if (!"PENDING".equals(refund.get("status"))) {
            if (!status.equals(refund.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Final ledger outcome cannot be changed");
            return refundResponse(refund, false);
        }
        BigDecimal amount = (BigDecimal) refund.get("amount");
        if ("SUCCEEDED".equals(status)) {
            jdbc.update("update orders set refunded_amount=refunded_amount+?,status=if(refunded_amount>=paid_amount,'REFUNDED','PARTIALLY_REFUNDED'),updated_at=? where id=?",
                    amount, Timestamp.from(Instant.now()), orderId);
            jdbc.update("update payments set refunded_amount=refunded_amount+?,status=if(refunded_amount>=amount,'REFUNDED','PARTIALLY_REFUNDED') where order_id=?", amount, orderId);
        }
        jdbc.update("update refunds set status=?,completed_at=? where id=?", status, Timestamp.from(Instant.now()), refund.get("id"));
        jdbc.update("insert into operation_audit(refund_id,action,reason,occurred_at) values (?,?,?,?)",
                refund.get("id"), status, reason, Timestamp.from(Instant.now()));
        return refund(key);
    }

    private Map<String, Object> refundResponse(Map<String, Object> row, boolean newlyCreated) {
        return Map.of("externalRef", row.get("id"), "idempotencyKey", row.get("idempotency_key"),
                "orderId", row.get("order_id"), "amount", row.get("amount"), "status", row.get("status"),
                "message", "Synthetic local business ledger", "behavior", row.get("behavior"), "newlyCreated", newlyCreated);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business record not found");
        return rows.get(0);
    }
}
