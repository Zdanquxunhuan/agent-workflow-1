package com.yu.agent.workflow.commerce;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentDetail(
        String paymentId,
        String orderId,
        String channel,
        String status,
        BigDecimal amount,
        BigDecimal refundedAmount,
        String gatewayTxnId,
        Instant paidAt) {
}
