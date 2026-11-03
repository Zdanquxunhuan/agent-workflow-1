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
        int sideEffectCount,
        String tenantId,
        String entrypoint) {
    public AgentTaskView(String id, String userId, UserRole role, String request, String idempotencyKey,
                         Instant createdAt, Instant updatedAt, TaskStatus status, TaskPlan plan,
                         List<AgentStep> steps, ApprovalRequest approval, String resultMessage, int sideEffectCount) {
        this(id, userId, role, request, idempotencyKey, createdAt, updatedAt, status, plan, steps,
                approval, resultMessage, sideEffectCount, "legacy", "tasks");
    }
}
