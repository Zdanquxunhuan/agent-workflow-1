package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.persistence.EventRepository;
import com.yu.agent.workflow.persistence.TaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Crash recovery is deliberately conservative: an unfinished task is marked
 * recovery-required and diagnosed. The service never blindly replays a
 * possibly-mutating tool after a process restart.
 */
@Component
public class RecoveryService {
    private final TaskRepository tasks;
    private final TaskAuditWriter auditWriter;

    public RecoveryService(TaskRepository tasks, EventRepository events) {
        this(tasks, events, new TaskAuditWriter(tasks, events));
    }

    @Autowired
    public RecoveryService(TaskRepository tasks, EventRepository events, TaskAuditWriter auditWriter) {
        this.tasks = tasks;
        this.auditWriter = auditWriter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void inspectInterruptedTasks() {
        for (AgentTask task : tasks.findByStatus(TaskStatus.RUNNING)) {
            task.resultMessage("Execution interrupted; manual recovery required");
            task.status(TaskStatus.RECOVERY_REQUIRED);
            auditWriter.write(task, new AuditEvent(UUID.randomUUID().toString(), task.id(), UUID.randomUUID().toString(),
                    "RECOVERY_REQUIRED", "system", null, "RECORDED",
                    "Task was RUNNING at startup and was not replayed automatically", Map.of(), Instant.now()));
        }
    }
}
