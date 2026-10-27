package com.yu.agent.workflow.domain;

import java.time.Instant;
import java.util.List;

public record AgentTaskView(
        String id,
        String userId,
        UserRole role,
        String request,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        TaskStatus status,
        TaskPlan plan,
        List<AgentStep> steps,
        ApprovalRequest approval,
        String resultMessage,
        int sideEffectCount) {
}
