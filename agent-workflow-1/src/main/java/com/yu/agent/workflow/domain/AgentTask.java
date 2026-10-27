package com.yu.agent.workflow.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class AgentTask {
    private final String id;
    private final String userId;
    private final UserRole role;
    private final String request;
    private final String idempotencyKey;
    private final Instant createdAt;
    private Instant updatedAt;
    private TaskStatus status;
    private TaskPlan plan;
    private final List<AgentStep> steps = new ArrayList<>();
    private ApprovalRequest approval;
    private String resultMessage;
    private int sideEffectCount;

    public AgentTask(String id, String userId, UserRole role, String request, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.role = role;
        this.request = request;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = TaskStatus.CREATED;
    }

    public static AgentTask restore(AgentTaskView view) {
        AgentTask task = new AgentTask(view.id(), view.userId(), view.role(), view.request(), view.idempotencyKey(), view.createdAt());
        task.updatedAt = view.updatedAt();
        task.status = view.status();
        task.plan = view.plan();
        task.steps.addAll(view.steps());
        task.approval = view.approval();
        task.resultMessage = view.resultMessage();
        task.sideEffectCount = view.sideEffectCount();
        return task;
    }

    public synchronized void status(TaskStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public synchronized void plan(TaskPlan plan) { this.plan = plan; this.updatedAt = Instant.now(); }
    public synchronized void approval(ApprovalRequest approval) { this.approval = approval; this.updatedAt = Instant.now(); }
    public synchronized void resultMessage(String resultMessage) { this.resultMessage = resultMessage; this.updatedAt = Instant.now(); }
    public synchronized void addStep(AgentStep step) { this.steps.add(step); this.updatedAt = Instant.now(); }
    public synchronized void incrementSideEffect() { this.sideEffectCount++; this.updatedAt = Instant.now(); }

    public synchronized AgentTaskView view() {
        return new AgentTaskView(id, userId, role, request, idempotencyKey, createdAt, updatedAt, status,
                plan, List.copyOf(steps), approval, resultMessage, sideEffectCount);
    }

    public String id() { return id; }
    public String userId() { return userId; }
    public UserRole role() { return role; }
    public String request() { return request; }
    public String idempotencyKey() { return idempotencyKey; }
    public synchronized TaskStatus status() { return status; }
    public synchronized TaskPlan plan() { return plan; }
    public synchronized ApprovalRequest approval() { return approval; }
}
