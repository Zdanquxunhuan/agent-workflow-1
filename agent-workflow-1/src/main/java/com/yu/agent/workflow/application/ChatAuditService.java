package com.yu.agent.workflow.application;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.AgentStep;
import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.AgentTaskView;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.TaskStateMachine;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.persistence.EventRepository;
import com.yu.agent.workflow.persistence.TaskRepository;
import com.yu.agent.workflow.tools.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gives the read-only chat endpoint the same task, step and audit timeline as
 * the normal workflow endpoint. The model still cannot write state directly;
 * this service only records server-observed events.
 */
@Service
public class ChatAuditService {
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.yu.agent.workflow.memory.MemoryProvenanceGuard> memoryGuard;
    private KnowledgeSearchClient knowledge;
    @org.springframework.beans.factory.annotation.Autowired
    public void knowledge(KnowledgeSearchClient knowledge) { this.knowledge = knowledge; }

    private final TaskRepository tasks;
    private final EventRepository events;
    private final TaskAuditWriter auditWriter;

    public ChatAuditService(TaskRepository tasks, EventRepository events) {
        this(tasks, events, new TaskAuditWriter(tasks, events));
    }

    @Autowired
    public ChatAuditService(TaskRepository tasks, EventRepository events, TaskAuditWriter auditWriter) {
        this.tasks = tasks;
        this.events = events;
        this.auditWriter = auditWriter;
    }

    @org.springframework.transaction.annotation.Transactional(transactionManager="workflowTransactionManager")
    public ChatSession start(String userId, UserRole role, String request,
                             String idempotencyKey, String traceId) {
        String normalizedKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : idempotencyKey.trim();
        // The repository unique constraint resolves concurrent retries; do not
        // pre-check here or two instances can both pass before either inserts.
        AgentTask candidate = new AgentTask(UUID.randomUUID().toString(), userId, role, request, normalizedKey, Instant.now(), com.yu.agent.workflow.security.TenantIdentity.current(), "chat");
        AgentTask task = tasks.create(candidate);
        com.yu.agent.workflow.runtime.RunScope.bindTask(task.id());
        if (task != candidate) {
            if (task.status() == TaskStatus.SUCCEEDED) {
                return new ChatSession(task.id(), task.userId(), task.role(), traceId, true, task.view());
            }
            throw BusinessException.of("IDEMPOTENCY_CONFLICT", "Chat request is already in progress or failed");
        }
        audit(task, traceId, "TASK_CREATED", userId, null, "Chat task created", Map.of("entrypoint", "agent.chat"));
        transition(task, TaskStatus.PLANNING, traceId, userId);
        audit(task, traceId, "CHAT_STARTED", userId, null, "Read-only chat started", Map.of());
        return new ChatSession(task.id(), userId, role, traceId, false, null);
    }

    public DeepSeekToolCallingService.ToolCallingAuditListener listener(ChatSession session) {
        return new Listener(session);
    }
    public void memoryContext(ChatSession session, Map<String,Object> metadata) {
        audit(tasks.require(session.taskId()),session.traceId(),"MEMORY_RECALL",session.userId(),null,"Memory selection recorded",metadata);
    }

    public void complete(ChatSession session, DeepSeekToolCallingService.ToolChatResult result) {
        AgentTask task = tasks.require(session.taskId());
        ensureRunning(task, session.traceId(), session.userId());
        task.resultMessage(result.answer());
        audit(task, session.traceId(), "CHAT_COMPLETED", session.userId(), null,
                "Read-only chat completed", Map.of("rounds", result.rounds(), "toolCount", result.executedTools().size()));
        transition(task, TaskStatus.SUCCEEDED, session.traceId(), session.userId());
        audit(task, session.traceId(), "TASK_SUCCEEDED", session.userId(), null,
                "Chat task completed", Map.of("entrypoint", "agent.chat"));
    }

    public void fail(ChatSession session, RuntimeException exception) {
        AgentTask task = tasks.require(session.taskId());
        if (!task.status().terminal()) {
            task.resultMessage(exception.getMessage() == null ? "Chat failed" : exception.getMessage());
            if (task.status() == TaskStatus.PLANNING || task.status() == TaskStatus.RUNNING) {
                transition(task, TaskStatus.FAILED, session.traceId(), session.userId());
            }
        }
        String code = exception instanceof BusinessException business ? business.code() : "INTERNAL_ERROR";
        audit(task, session.traceId(), "CHAT_FAILED", session.userId(), null,
                "Read-only chat failed", Map.of("code", code));
    }

    public DeepSeekToolCallingService.ToolChatResult replay(ChatSession session) {
        AgentTask task = tasks.require(session.taskId());
        com.yu.agent.workflow.security.TenantIdentity.require(task.tenantId());
        if (!task.userId().equals(session.userId())) throw BusinessException.of("FORBIDDEN", "Chat owner required");
        if (memoryGuard != null && memoryGuard.getIfAvailable() != null)
            memoryGuard.getObject().validateTask(new com.yu.agent.workflow.tools.ToolContext(session.userId(),session.role(),task.id(),session.traceId(),task.tenantId()));
        if (knowledge != null) knowledge.revalidateTask(new com.yu.agent.workflow.tools.ToolContext(session.userId(), session.role(), task.id(), session.traceId(), task.tenantId()));
        AgentTaskView view = task.view();
        if (view == null) view = tasks.require(session.taskId()).view();
        int rounds = (int) events.findByTask(session.taskId()).stream()
                .filter(event -> "MODEL_ROUND".equals(event.type())).count();
        List<String> executedTools = view.steps().stream().map(AgentStep::toolName).toList();
        return new DeepSeekToolCallingService.ToolChatResult(session.taskId(), view.resultMessage(), rounds, executedTools);
    }
    @org.springframework.transaction.annotation.Transactional(transactionManager="workflowTransactionManager")
    public void abandonRuntime(String taskId,String tenant,String owner,String trace,String code) {
        AgentTask task=tasks.require(taskId);
        if(!task.tenantId().equals(tenant) || !task.userId().equals(owner) || !"chat".equals(task.entrypoint()))throw BusinessException.of("FORBIDDEN","Runtime task owner mismatch");
        if(task.status()==TaskStatus.CREATED || task.status()==TaskStatus.RECOVERY_REQUIRED) {
            task.resultMessage("Runtime stopped before completion");transition(task,TaskStatus.CANCELLED,trace,owner);return;
        }
        if(!task.status().terminal())fail(new ChatSession(taskId,owner,task.role(),trace,false,null),BusinessException.of(code,"Runtime stopped before completion"));
    }

    private void ensureRunning(AgentTask task, String traceId, String actor) {
        if (task.status() == TaskStatus.PLANNING) transition(task, TaskStatus.RUNNING, traceId, actor);
    }

    private void transition(AgentTask task, TaskStatus next, String traceId, String actor) {
        TaskStatus current = task.status();
        TaskStateMachine.requireAllowed(current, next);
        task.status(next);
        audit(task, traceId, "STATE_CHANGED", actor, null, current + " -> " + next,
                Map.of("from", current.name(), "to", next.name()));
    }

    private void audit(AgentTask task, String traceId, String type, String actor,
                       String toolName, String message, Map<String, Object> metadata) {
        auditWriter.write(task, new AuditEvent(UUID.randomUUID().toString(), task.id(),
                traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId,
                type, actor, toolName, "RECORDED", message, metadata, Instant.now()));
    }

    public record ChatSession(String taskId, String userId, UserRole role, String traceId,
                              boolean reused, AgentTaskView existing) {}

    private final class Listener implements DeepSeekToolCallingService.ToolCallingAuditListener {
        private final ChatSession session;
        private final Map<String, Invocation> invocations = new HashMap<>();
        private int nextSequence = 1;

        private Listener(ChatSession session) {
            this.session = session;
        }

        @Override
        public void onModelRound(int round, List<com.yu.agent.workflow.application.ToolCall> toolCalls) {
            AgentTask task = tasks.require(session.taskId());
            ensureRunning(task, session.traceId(), session.userId());
            List<String> names = toolCalls.stream().map(com.yu.agent.workflow.application.ToolCall::name).toList();
            audit(task, session.traceId(), "MODEL_ROUND", session.userId(), null,
                    "Model round completed", Map.of("round", round, "toolCount", toolCalls.size(), "toolNames", names));
        }

        @Override
        public void onModelMetadata(int round, Map<String, Object> metadata) {
            if (metadata.isEmpty()) return;
            Map<String, Object> values = new HashMap<>(metadata);
            values.put("round", round);
            audit(tasks.require(session.taskId()), session.traceId(), "MODEL_USAGE", session.userId(), null,
                    "Model invocation metadata", values);
        }

        @Override
        public void onToolCallStarted(int round, com.yu.agent.workflow.application.ToolCall call,
                                      String internalName, Instant startedAt) {
            AgentTask task = tasks.require(session.taskId());
            Invocation invocation = new Invocation(UUID.randomUUID().toString(), nextSequence++, startedAt);
            invocations.put(call.id(), invocation);
            audit(task, session.traceId(), "TOOL_CALL_REQUESTED", session.userId(), internalName,
                    "Read-only tool call requested", Map.of("round", round, "sequence", invocation.sequence()));
        }

        @Override
        public void onToolCallCompleted(int round, com.yu.agent.workflow.application.ToolCall call,
                                        String internalName, Instant startedAt, Instant finishedAt, ToolResult result) {
            AgentTask task = tasks.require(session.taskId());
            Invocation invocation = invocations.remove(call.id());
            if (invocation == null) invocation = new Invocation(UUID.randomUUID().toString(), nextSequence++, startedAt);
            task.addStep(new AgentStep(invocation.stepId(), invocation.sequence(), internalName, call.arguments(),
                    result.success() ? "SUCCEEDED" : "FAILED", result.code(), result.message(),
                    invocation.startedAt(), finishedAt,
                    result.data().containsKey("mcpCallId") ? result.data() : Map.of()));
            audit(task, session.traceId(), "TOOL_EXECUTED", session.userId(), internalName, result.message(),
                    Map.of("round", round, "code", result.code(), "success", result.success()));
        }

        @Override
        public void onToolCallRejected(int round, com.yu.agent.workflow.application.ToolCall call,
                                       String internalName, Instant startedAt, BusinessException exception) {
            AgentTask task = tasks.require(session.taskId());
            Invocation invocation = invocations.remove(call.id());
            if (invocation == null) invocation = new Invocation(UUID.randomUUID().toString(), nextSequence++, startedAt);
            Instant finishedAt = Instant.now();
            task.addStep(new AgentStep(invocation.stepId(), invocation.sequence(), internalName, call.arguments(),
                    "REJECTED", exception.code(), exception.getMessage(), invocation.startedAt(), finishedAt));
            audit(task, session.traceId(), "TOOL_CALL_REJECTED", session.userId(), internalName,
                    exception.getMessage(), Map.of("round", round, "code", exception.code()));
        }
    }

    private record Invocation(String stepId, int sequence, Instant startedAt) {}
}
