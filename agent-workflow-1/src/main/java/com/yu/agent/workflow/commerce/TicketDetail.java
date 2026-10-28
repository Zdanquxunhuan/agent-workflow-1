package com.yu.agent.workflow.commerce;

public record TicketDetail(
        String ticketId,
        String orderId,
        String customerId,
        String status,
        String priority,
        String subject) {
}
