package com.yu.agent.workflow;

import com.yu.agent.workflow.application.RecoveryService;
import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.persistence.InMemoryEventRepository;
import com.yu.agent.workflow.persistence.InMemoryTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryServiceTests {
    @Test
    void interruptedRunningTaskIsNotReplayed() {
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        AgentTask task = new AgentTask("task-recovery", "alice", UserRole.CUSTOMER_SERVICE, "query", null, Instant.now());
        task.status(TaskStatus.RUNNING);
        tasks.save(task);

        new RecoveryService(tasks, events).inspectInterruptedTasks();

        assertThat(tasks.require(task.id()).status()).isEqualTo(TaskStatus.RECOVERY_REQUIRED);
        assertThat(tasks.require(task.id()).view().resultMessage()).contains("manual recovery");
        assertThat(events.findByTask(task.id())).singleElement().extracting("type").isEqualTo("RECOVERY_REQUIRED");
    }
}
