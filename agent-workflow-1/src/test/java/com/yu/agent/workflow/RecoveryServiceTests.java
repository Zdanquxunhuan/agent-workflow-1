package com.yu.agent.workflow;

import com.yu.agent.workflow.application.RecoveryService;
import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.AgentTaskView;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.persistence.InMemoryEventRepository;
import com.yu.agent.workflow.persistence.InMemoryTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryServiceTests {
    @Test
    void interruptedRunningTaskIsNotReplayed() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        Instant staleAt = Instant.now().minusSeconds(600);
        AgentTask task = restored("task-recovery", TaskStatus.RUNNING, staleAt);
        tasks.save(task);

        new RecoveryService(tasks, events).inspectInterruptedTasks();

        assertThat(tasks.require(task.id()).status()).isEqualTo(TaskStatus.RECOVERY_REQUIRED);
        assertThat(tasks.require(task.id()).view().resultMessage()).contains("manual recovery");
        assertThat(events.findByTask(task.id())).singleElement().extracting("type").isEqualTo("RECOVERY_REQUIRED");
    }

    @Test
    void liveTaskOwnedByAnotherInstanceIsNotMarkedAsInterrupted() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        AgentTask live = restored("task-live", TaskStatus.RUNNING, Instant.now());
        AgentTask planning = restored("task-planning", TaskStatus.PLANNING, Instant.now().minusSeconds(600));
        tasks.save(live);
        tasks.save(planning);

        new RecoveryService(tasks, events).inspectInterruptedTasks();

        assertThat(tasks.require(live.id()).status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(events.findByTask(live.id())).isEmpty();
        assertThat(tasks.require(planning.id()).status()).isEqualTo(TaskStatus.RECOVERY_REQUIRED);
    }

    private static AgentTask restored(String id, TaskStatus status, Instant updatedAt) {
        return AgentTask.restore(new AgentTaskView(id, "alice", UserRole.CUSTOMER_SERVICE, "query", null,
                updatedAt.minusSeconds(60), updatedAt, status, null, List.of(), null, null, 0));
    }
}
