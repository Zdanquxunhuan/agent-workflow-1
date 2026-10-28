package com.yu.agent.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AgentChatAuditTests {
    @Autowired MockMvc mvc;

    @Test
    void chatCreatesTaskAndCompleteAuditTimeline() throws Exception {
        String response = mvc.perform(post("/api/v1/agent/chat")
                        .with(user("chat-audit-user", "CUSTOMER_SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "chat-audit-trace")
                        .content("{\"message\":\"查询订单 ORD-1001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").isNotEmpty())
                .andExpect(jsonPath("$.data.rounds").value(1))
                .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(response, "$.data.taskId");

        mvc.perform(get("/api/v1/tasks/" + taskId)
                        .with(user("chat-audit-user", "CUSTOMER_SERVICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.resultMessage").value("外部模型未启用；当前请求未执行多轮模型 Tool Calling。"));

        mvc.perform(get("/api/v1/tasks/" + taskId + "/events")
                        .with(user("chat-audit-user", "CUSTOMER_SERVICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].type", hasItem("CHAT_STARTED")))
                .andExpect(jsonPath("$.data[*].type", hasItem("MODEL_ROUND")))
                .andExpect(jsonPath("$.data[*].type", hasItem("CHAT_COMPLETED")))
                .andExpect(jsonPath("$.data[*].type", hasItem("TASK_SUCCEEDED")));
    }

    @Test
    void chatIdempotencyKeyReplaysTheAuditedTask() throws Exception {
        String first = mvc.perform(post("/api/v1/agent/chat")
                        .with(user("chat-idempotency-user", "CUSTOMER_SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "chat-audit-key")
                        .content("{\"message\":\"查询订单 ORD-1001\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstTaskId = com.jayway.jsonpath.JsonPath.read(first, "$.data.taskId");

        String second = mvc.perform(post("/api/v1/agent/chat")
                        .with(user("chat-idempotency-user", "CUSTOMER_SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "chat-audit-key")
                        .content("{\"message\":\"查询订单 ORD-1002\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondTaskId = com.jayway.jsonpath.JsonPath.read(second, "$.data.taskId");

        org.assertj.core.api.Assertions.assertThat(secondTaskId).isEqualTo(firstTaskId);
    }

    private static RequestPostProcessor user(String subject, String role) {
        return jwt()
                .jwt(jwt -> jwt.subject(subject).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
