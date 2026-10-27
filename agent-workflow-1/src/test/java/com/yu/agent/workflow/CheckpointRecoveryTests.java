package com.yu.agent.workflow;

import com.yu.agent.workflow.application.AgentApplicationService;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.persistence.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CheckpointRecoveryTests {
    @Autowired AgentApplicationService service;
    @Autowired TaskRepository tasks;

    @Test
    void recoveredReadOnlyTaskCanResumeWithoutReplayingAWrite() {
        var created = service.create("checkpoint-user", UserRole.CUSTOMER_SERVICE,
                "查询订单 ORD-1001", "checkpoint-1", "checkpoint-trace");
        var task = tasks.require(created.id());
        task.status(TaskStatus.RECOVERY_REQUIRED);
        task.resultMessage("Execution interrupted; manual recovery required");
        tasks.save(task);

        var resumed = service.resume(created.id(), "supervisor", UserRole.SUPERVISOR, "resume-trace");
        assertThat(resumed.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(resumed.sideEffectCount()).isZero();
        assertThat(service.events(created.id(), "supervisor", UserRole.SUPERVISOR))
                .anyMatch(event -> event.type().equals("RECOVERY_STARTED"));
    }
}
