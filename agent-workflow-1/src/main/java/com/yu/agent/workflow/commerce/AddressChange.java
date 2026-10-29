package com.yu.agent.workflow.commerce;

public record AddressChange(
        String orderId,
        String oldAddress,
        String newAddress,
        String status,
        String idempotencyKey,
        String integrationCommandId,
        boolean replayed) {
}
