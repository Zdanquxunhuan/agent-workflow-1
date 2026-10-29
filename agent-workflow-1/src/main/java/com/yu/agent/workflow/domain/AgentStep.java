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
        Instant finishedAt,
        Map<String, Object> resultData) {
    public AgentStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        resultData = resultData == null ? Map.of() : Map.copyOf(resultData);
    }

    public AgentStep(String stepId, int sequence, String toolName, Map<String, Object> arguments,
                     String status, String resultCode, String resultMessage, Instant startedAt, Instant finishedAt) {
        this(stepId, sequence, toolName, arguments, status, resultCode, resultMessage, startedAt, finishedAt, Map.of());
    }
}
