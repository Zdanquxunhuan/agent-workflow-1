package com.yu.agent.workflow.commerce;

import java.math.BigDecimal;
import java.util.List;

public record OrderDetail(
        String orderId,
        String customerId,
        String customerName,
        String status,
        String currency,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal refundedAmount,
        BigDecimal refundableAmount,
        String shippingAddress,
        List<OrderLine> items) {
    public OrderDetail {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
