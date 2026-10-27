package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationDatasetTests {
    @Test
    void evaluationSetHasTwentyFiveStableCasesAndSecurityCoverage() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/evaluation/tasks.json")) {
            JsonNode cases = new ObjectMapper().readTree(input);
            Set<String> ids = new HashSet<>();
            Set<String> security = new HashSet<>();
            cases.forEach(item -> {
                ids.add(item.path("id").asText());
                security.add(item.path("security").asText());
            });
            assertThat(cases).hasSize(25);
            assertThat(ids).hasSize(25);
            assertThat(security).contains("approval", "prompt_injection", "validation", "parameter");
        }
    }
}
