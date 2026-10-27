package com.yu.agent.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.TaskPlan;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekTaskPlannerTests {
    @Test
    void parsesTheStructuredPlanReturnedByDeepSeek() {
        DeepSeekTaskPlanner planner = new DeepSeekTaskPlanner(RestClient.builder(), new ObjectMapper(),
                "http://localhost", "test-key", "deepseek-flash", 1);
        TaskPlan plan = planner.parsePlan("{\"choices\":[{\"message\":{\"content\":\"{\\\"steps\\\":[{\\\"toolName\\\":\\\"order.query\\\",\\\"arguments\\\":{\\\"orderId\\\":\\\"ORD-1001\\\"},\\\"requiresApproval\\\":false}]}\"}}]}");
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).toolName()).isEqualTo("order.query");
        assertThat(plan.steps().get(0).arguments().get("orderId")).isEqualTo("ORD-1001");
    }

    @Test
    void rejectsAResponseWithoutSteps() {
        DeepSeekTaskPlanner planner = new DeepSeekTaskPlanner(RestClient.builder(), new ObjectMapper(),
                "http://localhost", "test-key", "deepseek-flash", 1);
        assertThatThrownBy(() -> planner.parsePlan("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("steps array");
    }
}
