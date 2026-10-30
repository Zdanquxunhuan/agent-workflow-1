package com.yu.agent.workflow.commerce;

public record CommerceCommand(
        String commandId,
        String channel,
        String commandType,
        String aggregateId,
        String payloadJson,
        String idempotencyKey,
        int attempts,
        String previousStatus) {
    public CommerceCommand(String commandId, String channel, String commandType, String aggregateId,
                           String payloadJson, String idempotencyKey, int attempts) {
        this(commandId, channel, commandType, aggregateId, payloadJson, idempotencyKey, attempts, "PENDING");
    }
}
