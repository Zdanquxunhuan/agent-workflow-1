package com.yu.agent.workflow.application;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.AgentStep;
import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.AgentTaskView;
import com.yu.agent.workflow.domain.ApprovalRequest;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.domain.PlanStep;
import com.yu.agent.workflow.domain.TaskPlan;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.TaskStateMachine;
import com.yu.agent.workflow.domain.Checkpoint;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.persistence.EventRepository;
import com.yu.agent.workflow.persistence.TaskRepository;
import com.yu.agent.workflow.tools.ToolContext;
import com.yu.agent.workflow.tools.ToolRegistry;
import com.yu.agent.workflow.tools.ToolResult;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentApplicationService {
    private final TaskRepository tasks;
    private final EventRepository events;
    private final TaskAuditWriter auditWriter;
    private final TaskPlanner planner;
    private final ToolRegistry toolRegistry;
    private final PromptInjectionGuard promptInjectionGuard;
    private final ResultEvaluator resultEvaluator;
    private final int maxSteps;
    private final long deadlineSeconds;

    public AgentApplicationService(TaskRepository tasks, EventRepository events,
                                   TaskAuditWriter auditWriter,
                                   TaskPlanner planner, ToolRegistry toolRegistry,
                                   PromptInjectionGuard promptInjectionGuard, ResultEvaluator resultEvaluator,
                                   @Value("${agent.workflow.max-steps:8}") int maxSteps,
                                   @Value("${agent.workflow.deadline-seconds:30}") long deadlineSeconds) {
        this.tasks = tasks;
        this.events = events;
        this.auditWriter = auditWriter;
        this.planner = planner;
        this.toolRegistry = toolRegistry;
        this.promptInjectionGuard = promptInjectionGuard;
        this.resultEvaluator = resultEvaluator;
        this.maxSteps = Math.max(1, maxSteps);
        this.deadlineSeconds = Math.max(1, deadlineSeconds);
    }

    /**
     * Creates a task and runs only the server-owned orchestration path. A planner
     * response is data, not authority: it is normalized and checked before any
     * tool is allowed to execute.
     */
    public synchronized AgentTaskView create(String userId, UserRole role, String request, String idempotencyKey, String traceId) {
        if (userId == null || userId.isBlank()) throw BusinessException.of("INVALID_ARGUMENT", "X-User-Id is required");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            AgentTask previous = tasks.byIdempotency(userId, idempotencyKey);
            if (previous != null) return previous.view();
        }
        AgentTask task = new AgentTask(UUID.randomUUID().toString(), userId, role, request, idempotencyKey, Instant.now());
        tasks.save(task);
        tasks.bindIdempotency(task);
        audit(task, traceId, "TASK_CREATED", userId, null, "Task created", Map.of());
        transition(task, TaskStatus.PLANNING, traceId, userId);
        Instant planningStarted = Instant.now();
        audit(task, traceId, "PLANNING_STARTED", userId, null, "Plan generation started",
                Map.of("planner", planner.getClass().getSimpleName()));
        TaskPlan plan;
        try {
            promptInjectionGuard.check(request);
            plan = normalizeAndValidatePlan(planner.plan(request));
            audit(task, traceId, "PLANNING_COMPLETED", userId, null, "Plan generation completed", Map.of(
                    "planner", planner.getClass().getSimpleName(),
                    "stepCount", plan.steps().size(),
                    "durationMs", java.time.Duration.between(planningStarted, Instant.now()).toMillis()));
        } catch (BusinessException ex) {
            task.resultMessage(ex.getMessage());
            audit(task, traceId, "PLAN_REJECTED", userId, null, ex.getMessage(), Map.of("code", ex.code()));
            transition(task, TaskStatus.FAILED, traceId, userId);
            throw ex;
        }
        task.plan(plan);
        audit(task, traceId, "PLAN_ACCEPTED", userId, null, "Plan accepted", Map.of("stepCount", plan.steps().size()));
        if (plan.requiresApproval()) {
            transition(task, TaskStatus.WAITING_APPROVAL, traceId, userId);
            ApprovalRequest approval = new ApprovalRequest(UUID.randomUUID().toString(), task.id(), userId, null,
                    "PENDING", null, Instant.now(), null);
            task.approval(approval);
            audit(task, traceId, "APPROVAL_REQUESTED", userId, null, "High-risk action is waiting for approval", Map.of());
        } else {
            executePlan(task, traceId, userId, role, 1);
        }
        return task.view();
    }

    /**
     * Approval and the first write are one transaction in JDBC mode. The task
     * lock plus terminal-state check makes a repeated approval harmless.
     */
    @Transactional(transactionManager = "workflowTransactionManager")
    public AgentTaskView approve(String taskId, String approver, UserRole role, String reason, String traceId) {
        AgentTask task = tasks.require(taskId);
        synchronized (task) {
            if (role != UserRole.SUPERVISOR && role != UserRole.ADMIN) {
                throw BusinessException.of("FORBIDDEN", "Only a supervisor or admin can approve a task");
            }
            if (task.status() == TaskStatus.SUCCEEDED) return task.view();
            if (task.status() != TaskStatus.WAITING_APPROVAL) {
                throw BusinessException.of("INVALID_STATE", "Task is not waiting for approval");
            }
            ApprovalRequest current = task.approval();
            task.approval(new ApprovalRequest(current.approvalId(), current.taskId(), current.requestedBy(), approver,
                    "APPROVED", reason, current.requestedAt(), Instant.now()));
            audit(task, traceId, "APPROVAL_GRANTED", approver, null, "Approval granted", Map.of());
            executePlan(task, traceId, approver, role, 1);
            return task.view();
        }
    }

    @Transactional(transactionManager = "workflowTransactionManager")
    public AgentTaskView reject(String taskId, String approver, UserRole role, String reason, String traceId) {
        AgentTask task = tasks.require(taskId);
        synchronized (task) {
            if (role != UserRole.SUPERVISOR && role != UserRole.ADMIN) {
                throw BusinessException.of("FORBIDDEN", "Only a supervisor or admin can reject a task");
            }
            if (task.status() == TaskStatus.CANCELLED) return task.view();
            if (task.status() != TaskStatus.WAITING_APPROVAL) {
                throw BusinessException.of("INVALID_STATE", "Task is not waiting for approval");
            }
            ApprovalRequest current = task.approval();
            task.approval(new ApprovalRequest(current.approvalId(), current.taskId(), current.requestedBy(), approver,
                    "REJECTED", reason, current.requestedAt(), Instant.now()));
            task.resultMessage("Rejected by approver");
            transition(task, TaskStatus.CANCELLED, traceId, approver);
            audit(task, traceId, "APPROVAL_REJECTED", approver, null, "Approval rejected; no side effect executed", Map.of());
            return task.view();
        }
    }

    @Transactional(transactionManager = "workflowTransactionManager")
    public AgentTaskView cancel(String taskId, String actor, UserRole role, String traceId) {
        AgentTask task = tasks.require(taskId);
        if (!task.userId().equals(actor) && role != UserRole.ADMIN) throw BusinessException.of("FORBIDDEN", "Only the task owner can cancel it");
        synchronized (task) {
            if (task.status().terminal()) return task.view();
            transition(task, TaskStatus.CANCELLED, traceId, actor);
            task.resultMessage("Cancelled by user");
            audit(task, traceId, "TASK_CANCELLED", actor, null, "Task cancelled", Map.of());
            return task.view();
        }
    }

    /** Resume only the remaining read-only suffix of a checkpointed task. */
    @Transactional(transactionManager = "workflowTransactionManager")
    public AgentTaskView resume(String taskId, String actor, UserRole role, String traceId) {
        if (role != UserRole.SUPERVISOR && role != UserRole.ADMIN) {
            throw BusinessException.of("FORBIDDEN", "Only a supervisor or admin can resume a recovered task");
        }
        AgentTask task = tasks.require(taskId);
        synchronized (task) {
            if (task.status() != TaskStatus.RECOVERY_REQUIRED) {
                throw BusinessException.of("INVALID_STATE", "Task is not waiting for recovery");
            }
            AgentTaskView view = task.view();
            if (view.plan() == null || view.plan().steps().isEmpty()) {
                throw BusinessException.of("RECOVERY_UNSAFE", "Recovered task has no checkpointed plan");
            }
            int nextSequence = view.plan().steps().stream()
                    .filter(step -> view.steps().stream().noneMatch(done -> done.sequence() == step.sequence() && "SUCCEEDED".equals(done.status())))
                    .mapToInt(PlanStep::sequence).min().orElse(view.plan().steps().size() + 1);
            for (PlanStep step : view.plan().steps()) {
                if (step.sequence() >= nextSequence && !toolRegistry.require(step.toolName()).readOnly()) {
                    throw BusinessException.of("RECOVERY_UNSAFE", "Recovery will not replay an uncertain write tool");
                }
            }
            transition(task, TaskStatus.RUNNING, traceId, actor);
            audit(task, traceId, "RECOVERY_STARTED", actor, null, "Read-only checkpoint recovery started", Map.of("nextSequence", nextSequence));
            if (nextSequence > view.plan().steps().size()) {
                task.resultMessage("Workflow completed from checkpoint");
                transition(task, TaskStatus.SUCCEEDED, traceId, actor);
                audit(task, traceId, "TASK_SUCCEEDED", actor, null, "Workflow completed from checkpoint", Map.of());
            } else {
                executePlan(task, traceId, actor, role, nextSequence);
            }
            return task.view();
        }
    }

    public AgentTaskView get(String taskId, String actor, UserRole role) {
        AgentTask task = tasks.require(taskId);
        if (!task.userId().equals(actor) && role != UserRole.ADMIN && role != UserRole.SUPERVISOR) {
            throw BusinessException.of("FORBIDDEN", "Task is not visible to this user");
        }
        return task.view();
    }

    public List<AuditEvent> events(String taskId, String actor, UserRole role) {
        get(taskId, actor, role);
        return events.findByTask(taskId);
    }

    private void executePlan(AgentTask task, String traceId, String actor, UserRole executionRole, int startSequence) {
        // This is intentionally a bounded, explicit loop. The model cannot
        // continue execution by itself or bypass the deadline and step limit.
        if (task.status() != TaskStatus.RUNNING) transition(task, TaskStatus.RUNNING, traceId, actor);
        if (task.plan() == null || task.plan().steps().isEmpty()) {
            task.resultMessage("No executable steps");
            transition(task, TaskStatus.FAILED, traceId, actor);
            return;
        }
        if (task.plan().steps().size() > maxSteps) {
            task.resultMessage("Plan exceeds maximum step count");
            transition(task, TaskStatus.FAILED, traceId, actor);
            return;
        }
        Instant deadline = Instant.now().plusSeconds(deadlineSeconds);
        for (PlanStep step : task.plan().steps()) {
            if (step.sequence() < startSequence) continue;
            if (Instant.now().isAfter(deadline)) {
                task.resultMessage("Execution deadline exceeded");
                transition(task, TaskStatus.FAILED, traceId, actor);
                audit(task, traceId, "DEADLINE_EXCEEDED", actor, null, "Execution deadline exceeded", Map.of());
                return;
            }
            String stepId = UUID.randomUUID().toString();
            Instant started = Instant.now();
            ToolResult result;
            try {
                result = toolRegistry.execute(step.toolName(), step.arguments(), new ToolContext(actor,
                        executionRole, task.id(), traceId));
            } catch (BusinessException ex) {
                task.addStep(new AgentStep(stepId, step.sequence(), step.toolName(), step.arguments(), "REJECTED",
                        ex.code(), ex.getMessage(), started, Instant.now()));
                audit(task, traceId, "TOOL_REJECTED", actor, step.toolName(), ex.getMessage(), Map.of("code", ex.code()));
                task.resultMessage(ex.getMessage());
                transition(task, TaskStatus.FAILED, traceId, actor);
                return;
            }
            try {
                resultEvaluator.verify(toolRegistry.require(step.toolName()), result);
            } catch (BusinessException ex) {
                task.addStep(new AgentStep(stepId, step.sequence(), step.toolName(), step.arguments(), "FAILED",
                        ex.code(), ex.getMessage(), started, Instant.now()));
                task.resultMessage(ex.getMessage());
                audit(task, traceId, "RESULT_REJECTED", actor, step.toolName(), ex.getMessage(), Map.of("code", ex.code()));
                transition(task, TaskStatus.FAILED, traceId, actor);
                return;
            }
            task.addStep(new AgentStep(stepId, step.sequence(), step.toolName(), step.arguments(),
                    result.success() ? "SUCCEEDED" : "FAILED", result.code(), result.message(), started, Instant.now()));
            audit(task, traceId, "TOOL_EXECUTED", actor, step.toolName(), result.message(), Map.of("code", result.code()));
            if (!result.success()) {
                task.resultMessage(result.message());
                transition(task, TaskStatus.FAILED, traceId, actor);
                return;
            }
            if (!toolRegistry.require(step.toolName()).readOnly()) task.incrementSideEffect();
            Checkpoint checkpoint = new Checkpoint(task.id(), step.sequence() + 1, stepId, "READY", Instant.now());
            audit(task, traceId, "CHECKPOINT_SAVED", actor, step.toolName(), "Checkpoint saved", Map.of(
                    "nextSequence", checkpoint.nextSequence(), "lastStepId", checkpoint.lastStepId()));
        }
        task.resultMessage("Workflow completed");
        transition(task, TaskStatus.SUCCEEDED, traceId, actor);
        audit(task, traceId, "TASK_SUCCEEDED", actor, null, "Workflow completed", Map.of());
    }

    private TaskPlan normalizeAndValidatePlan(TaskPlan plan) {
        if (plan == null || plan.steps().isEmpty()) throw BusinessException.of("INVALID_PLAN", "Planner returned no steps");
        if (plan.steps().size() > maxSteps) throw BusinessException.of("INVALID_PLAN", "Plan has too many steps");
        List<PlanStep> normalized = new java.util.ArrayList<>();
        for (PlanStep step : plan.steps()) {
            if (!toolRegistry.contains(step.toolName())) throw BusinessException.of("UNKNOWN_TOOL", "Tool is not registered: " + step.toolName());
            var tool = toolRegistry.require(step.toolName());
            // Approval is a server-side property of the registered tool. The
            // model cannot turn off the gate by returning false in its JSON.
            normalized.add(new PlanStep(step.sequence(), step.toolName(), step.arguments(), !tool.readOnly()));
        }
        return new TaskPlan(normalized);
    }

    private void transition(AgentTask task, TaskStatus next, String traceId, String actor) {
        TaskStatus current = task.status();
        TaskStateMachine.requireAllowed(current, next);
        task.status(next);
        audit(task, traceId, "STATE_CHANGED", actor, null, current + " -> " + next, Map.of("from", current.name(), "to", next.name()));
    }

    private void audit(AgentTask task, String traceId, String type, String actor, String toolName, String message, Map<String, Object> metadata) {
        auditWriter.write(task, new AuditEvent(UUID.randomUUID().toString(), task.id(),
                traceId == null ? UUID.randomUUID().toString() : traceId,
                type, actor, toolName, "RECORDED", message, metadata, Instant.now()));
    }
}
