package com.yu.agent.workflow.commerce;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundRecord(
        String refundId,
        String orderId,
        BigDecimal amount,
        String status,
        String reason,
        String gatewayRefundId,
        String idempotencyKey,
        String requestedBy,
        Instant requestedAt,
        Instant completedAt,
        boolean replayed) {
}
