package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.persistence.EventRepository;
import com.yu.agent.workflow.persistence.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crash recovery is deliberately conservative: an unfinished task is marked
 * recovery-required and diagnosed. The service never blindly replays a
 * possibly-mutating tool after a process restart.
 *
 * <p>Recovery only claims tasks whose last update is older than the stale
 * threshold. A second instance can start while the first is executing a task,
 * and that live task must not be marked as interrupted.
 */
@Component
public class RecoveryService {
    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);
    private static final List<TaskStatus> UNFINISHED = List.of(
            TaskStatus.CREATED, TaskStatus.PLANNING, TaskStatus.RUNNING);

    private final TaskRepository tasks;
    private final TaskAuditWriter auditWriter;
    private final long staleSeconds;

    public RecoveryService(TaskRepository tasks, EventRepository events) {
        this(tasks, events, new TaskAuditWriter(tasks, events), 120);
    }

    @Autowired
    public RecoveryService(TaskRepository tasks, EventRepository events, TaskAuditWriter auditWriter,
                           @Value("${agent.workflow.recovery.stale-seconds:120}") long staleSeconds) {
        this.tasks = tasks;
        this.auditWriter = auditWriter;
        this.staleSeconds = Math.max(1, staleSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(transactionManager = "workflowTransactionManager")
    public void inspectInterruptedTasks() {
        Instant cutoff = Instant.now().minusSeconds(staleSeconds);
        for (TaskStatus status : UNFINISHED) {
            for (AgentTask task : tasks.findByStatus(status)) {
                if (task.status() != status || task.updatedAt().isAfter(cutoff)) continue;
                Instant previousUpdatedAt = task.updatedAt();
                task.resultMessage("Execution interrupted; manual recovery required");
                task.status(TaskStatus.RECOVERY_REQUIRED);
                auditWriter.write(task, new AuditEvent(UUID.randomUUID().toString(), task.id(), UUID.randomUUID().toString(),
                        "RECOVERY_REQUIRED", "system", null, "RECORDED",
                        "Task was " + status + " without a recent update and was not replayed automatically",
                        Map.of("previousStatus", status.name()), Instant.now()));
                log.warn("Recovery marked stale task taskId={} previousStatus={} updatedAt={}",
                        task.id(), status, previousUpdatedAt);
            }
        }
    }
}
