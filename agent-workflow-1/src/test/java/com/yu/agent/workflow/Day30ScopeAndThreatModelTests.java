package com.yu.agent.workflow;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.AgentApplicationService;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Acceptance evidence for the Day 30 scope and threat-model boundary. */
@SpringBootTest
class Day30ScopeAndThreatModelTests {
    @Autowired ToolRegistry registry;
    @Autowired AgentApplicationService service;

    @Test
    void toolInventorySeparatesReadOnlyAndHighRiskActions() {
        assertThat(registry.require("order.query").readOnly()).isTrue();
        assertThat(registry.require("shipment.query").readOnly()).isTrue();
        assertThat(registry.require("ticket.query").readOnly()).isTrue();
        assertThat(registry.require("knowledge.search").readOnly()).isTrue();
        assertThat(registry.require("refund.create").readOnly()).isFalse();
        assertThat(registry.require("order.address.update").readOnly()).isFalse();
        assertThat(registry.require("refund.create").allowedRoles())
                .containsExactlyInAnyOrder(UserRole.SUPERVISOR, UserRole.ADMIN);
    }

    @Test
    void internalAgentRoleCannotObtainBusinessToolAuthority() {
        var rejected = service.create(
                "agent-bot", UserRole.AGENT, "查询订单 ORD-1001", "day30-agent-role", "day30-trace-agent");

        assertThat(rejected.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(rejected.steps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo("REJECTED");
            assertThat(step.resultCode()).isEqualTo("FORBIDDEN");
        });
    }

    @Test
    void highRiskRequestHasZeroSideEffectBeforeApproval() {
        var pending = service.create(
                "alice", UserRole.CUSTOMER_SERVICE, "退款 ORD-1001 金额 20", "day30-refund", "day30-trace-refund");

        assertThat(pending.status()).isEqualTo(TaskStatus.WAITING_APPROVAL);
        assertThat(pending.sideEffectCount()).isZero();
        assertThatThrownBy(() -> service.approve(
                pending.id(), "alice", UserRole.CUSTOMER_SERVICE, "客服尝试审批", "day30-trace-denied"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only a supervisor or admin");
        assertThat(pending.sideEffectCount()).isZero();
    }
}
