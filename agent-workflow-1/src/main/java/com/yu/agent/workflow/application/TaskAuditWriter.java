package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.persistence.AuditOutboxRepository;
import com.yu.agent.workflow.persistence.EventRepository;
import com.yu.agent.workflow.persistence.InMemoryAuditOutboxRepository;
import com.yu.agent.workflow.persistence.TaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the current task snapshot, audit event, and outbox hand-off in one
 * resource transaction. The memory profile uses the configured no-op
 * transaction manager; JDBC callers share the MySQL transaction boundary.
 */
@Component
public class TaskAuditWriter {
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.yu.agent.workflow.memory.MemoryService> memory;
    private final TaskRepository tasks;
    private final EventRepository events;
    private final AuditOutboxRepository outbox;

    public TaskAuditWriter(TaskRepository tasks, EventRepository events) {
        this(tasks, events, new InMemoryAuditOutboxRepository());
    }

    @Autowired
    public TaskAuditWriter(TaskRepository tasks, EventRepository events, AuditOutboxRepository outbox) {
        this.tasks = tasks;
        this.events = events;
        this.outbox = outbox;
    }

    @Transactional(transactionManager = "workflowTransactionManager")
    public void write(AgentTask task, AuditEvent event) {
        if (!task.id().equals(event.taskId())) throw new IllegalArgumentException("Audit task mismatch");
        var metadata = new java.util.LinkedHashMap<String, Object>(event.metadata());
        metadata.put("tenantId", task.tenantId());
        metadata.put("entrypoint", task.entrypoint());
        event = new AuditEvent(event.eventId(), event.taskId(), event.traceId(), event.type(), event.actor(),
                event.toolName(), event.status(), event.message(), metadata, event.occurredAt());
        tasks.save(task);
        events.append(event);
        outbox.append(event);
        if ("TASK_SUCCEEDED".equals(event.type()) && memory != null) {
            var service = memory.getIfAvailable();
            if (service != null) service.onTaskSucceeded(task);
        }
    }
}
