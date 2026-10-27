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
 * transaction manager; JDBC callers share the PostgreSQL transaction boundary.
 */
@Component
public class TaskAuditWriter {
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
        tasks.save(task);
        events.append(event);
        outbox.append(event);
    }
}
