package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.AgentApplicationService;
import com.yu.agent.workflow.domain.AgentTaskView;
import com.yu.agent.workflow.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes every fixed evaluation case against the real application service. */
@SpringBootTest
class EvaluationBatchTests {
    @Autowired AgentApplicationService service;

    @Test
    void everyEvaluationCaseMatchesItsExpectedStatusAndFirstTool() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/evaluation/tasks.json")) {
            JsonNode cases = new ObjectMapper().readTree(input);
            int passed = 0;
            for (JsonNode item : cases) {
                String id = item.path("id").asText();
                String expectedStatus = item.path("expectedStatus").asText();
                String expectedTool = item.path("expectedTool").asText();
                try {
                    AgentTaskView task = service.create("eval-" + id, UserRole.CUSTOMER_SERVICE,
                            item.path("request").asText(), "eval-key-" + id, "eval-trace-" + id);
                    assertThat(task.status().name()).as(id).isEqualTo(expectedStatus);
                    String actualTool = task.plan() == null || task.plan().steps().isEmpty()
                            ? "none" : task.plan().steps().get(0).toolName();
                    assertThat(actualTool).as(id).isEqualTo(expectedTool);
                } catch (BusinessException ex) {
                    assertThat(expectedStatus).as(id).isEqualTo("ERROR");
                }
                passed++;
            }
            assertThat(passed).isEqualTo(25);
        }
    }
}
