package com.yu.agent.workflow;

import com.yu.agent.workflow.domain.AgentTaskView;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.application.AgentApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentWorkflow1ApplicationTests {
    @Autowired AgentApplicationService service;

    @Test
    void readOnlyOrderWorkflowSucceedsAndIsAudited() {
        AgentTaskView task = service.create("alice", UserRole.CUSTOMER_SERVICE, "查询订单 ORD-1001", "read-1", "trace-1");
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.steps()).hasSize(1);
        assertThat(task.steps().get(0).toolName()).isEqualTo("order.query");
        assertThat(service.events(task.id(), "alice", UserRole.CUSTOMER_SERVICE)).isNotEmpty();
    }

    @Test
    void highRiskWorkflowCannotExecuteBeforeApprovalAndApprovalIsIdempotent() {
        AgentTaskView pending = service.create("alice", UserRole.CUSTOMER_SERVICE, "退款 ORD-1001 金额 20", "refund-1", "trace-2");
        assertThat(pending.status()).isEqualTo(TaskStatus.WAITING_APPROVAL);
        assertThat(pending.sideEffectCount()).isZero();
        AgentTaskView approved = service.approve(pending.id(), "manager", UserRole.SUPERVISOR, "ok", "trace-3");
        AgentTaskView repeated = service.approve(pending.id(), "manager", UserRole.SUPERVISOR, "second click", "trace-4");
        assertThat(approved.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(approved.sideEffectCount()).isEqualTo(1);
        assertThat(repeated.sideEffectCount()).isEqualTo(1);
    }

    @Test
    void idempotencyReturnsTheOriginalTask() {
        AgentTaskView first = service.create("alice", UserRole.CUSTOMER_SERVICE, "查询订单 ORD-1001", "same-key", "trace-5");
        AgentTaskView second = service.create("alice", UserRole.CUSTOMER_SERVICE, "查询订单 ORD-1002", "same-key", "trace-6");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.request()).isEqualTo(first.request());
    }
}
