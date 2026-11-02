package com.yu.agent.workflow.commerce;

import com.yu.agent.workflow.application.TaskAuditWriter;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.domain.TaskStateMachine;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.persistence.TaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Called within settlement transactions: command, local receipt, task and audit commit together. */
@Component
@ConditionalOnProperty(name="agent.workflow.persistence", havingValue="jdbc")
public class IntegrationTaskTracker {
    private final JdbcTemplate jdbc;
    private final TaskRepository tasks;
    private final TaskAuditWriter audit;
    public IntegrationTaskTracker(JdbcTemplate jdbc, TaskRepository tasks, TaskAuditWriter audit) {
        this.jdbc=jdbc; this.tasks=tasks; this.audit=audit;
    }
    public void refresh(CommerceCommand command) {
        List<String> taskIds = jdbc.query("select task_id from integration_commands where id=? and task_id is not null", (rs,n)->rs.getString(1), command.commandId());
        if (taskIds.isEmpty()) return;
        var task = tasks.require(taskIds.get(0));
        if (task.status() != TaskStatus.WAITING_EXTERNAL && task.status() != TaskStatus.UNKNOWN_SIDE_EFFECT) return;
        List<String> states = jdbc.query("select status from integration_commands where task_id=?", (rs,n)->rs.getString(1), task.id());
        boolean uncertain = states.stream().anyMatch(s->List.of("UNKNOWN", "MANUAL_REVIEW").contains(s));
        boolean pending = states.stream().anyMatch(s->!List.of("ACKNOWLEDGED", "FAILED").contains(s));
        TaskStatus next = uncertain ? TaskStatus.UNKNOWN_SIDE_EFFECT : pending ? TaskStatus.WAITING_EXTERNAL
                : states.contains("FAILED") ? TaskStatus.FAILED : TaskStatus.SUCCEEDED;
        if (next == task.status()) return;
        TaskStateMachine.requireAllowed(task.status(), next);
        String previous = task.status().name();
        task.status(next);
        task.resultMessage(switch(next) {
            case SUCCEEDED -> "External business confirmed completion";
            case FAILED -> "External business confirmed failure; see integration review";
            case UNKNOWN_SIDE_EFFECT -> "External result requires reconciliation or manual review";
            default -> "External business confirmation pending";
        });
        audit.write(task, new AuditEvent(UUID.randomUUID().toString(), task.id(), UUID.randomUUID().toString(),
                "EXTERNAL_OUTCOME_UPDATED", "integration-worker", "refund.create", "RECORDED", task.view().resultMessage(),
                Map.of("commandId", command.commandId(), "from", previous, "to", next.name()), Instant.now()));
    }
}
