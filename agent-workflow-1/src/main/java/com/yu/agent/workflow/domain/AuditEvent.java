package com.yu.agent.workflow.domain;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        String eventId,
        String taskId,
        String traceId,
        String type,
        String actor,
        String toolName,
        String status,
        String message,
        Map<String, Object> metadata,
        Instant occurredAt) {
    public AuditEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
