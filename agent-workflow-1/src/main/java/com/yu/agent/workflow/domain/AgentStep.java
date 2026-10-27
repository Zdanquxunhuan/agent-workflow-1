package com.yu.agent.workflow.domain;

import java.time.Instant;
import java.util.Map;

public record AgentStep(
        String stepId,
        int sequence,
        String toolName,
        Map<String, Object> arguments,
        String status,
        String resultCode,
        String resultMessage,
        Instant startedAt,
        Instant finishedAt) {
    public AgentStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
