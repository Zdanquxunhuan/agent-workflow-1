package com.yu.agent.workflow;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityAuthenticationTests {
    @Autowired MockMvc mvc;

    @Test
    void forgedRoleHeaderIsIgnored() throws Exception {
        String created = mvc.perform(post("/api/v1/tasks")
                        .with(user("alice", "CUSTOMER_SERVICE"))
                        .header("X-User-Id", "attacker")
                        .header("X-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"为订单 ORD-1001 退款 20 元\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value("alice"))
                .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        String taskId = JsonPath.read(created, "$.data.id");

        mvc.perform(post("/api/v1/tasks/" + taskId + "/approve")
                        .with(user("alice", "CUSTOMER_SERVICE"))
                        .header("X-User-Id", "attacker")
                        .header("X-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"forged admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        mvc.perform(get("/api/v1/tasks/" + taskId).with(user("alice", "CUSTOMER_SERVICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.sideEffectCount").value(0));
    }

    @Test
    void requesterCannotApproveOwnTaskEvenWithSupervisorRole() throws Exception {
        String created = mvc.perform(post("/api/v1/tasks")
                        .with(user("supervisor-alice", "SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"为订单 ORD-1001 退款 20 元\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String taskId = JsonPath.read(created, "$.data.id");

        mvc.perform(post("/api/v1/tasks/" + taskId + "/approve")
                        .with(user("supervisor-alice", "SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"self approval\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(containsString("cannot approve their own task")));

        mvc.perform(get("/api/v1/tasks/" + taskId).with(user("supervisor-alice", "SUPERVISOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.sideEffectCount").value(0));
    }

    @Test
    void supervisorCanApproveAnotherUsersTask() throws Exception {
        String created = mvc.perform(post("/api/v1/tasks")
                        .with(user("alice", "CUSTOMER_SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"为订单 ORD-1001 退款 20 元\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String taskId = JsonPath.read(created, "$.data.id");

        mvc.perform(post("/api/v1/tasks/" + taskId + "/approve")
                        .with(user("bob", "SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"four-eyes approval\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.approval.decidedBy").value("bob"))
                .andExpect(jsonPath("$.data.sideEffectCount").value(1));
    }

    @Test
    void tokenWithoutKnownRoleIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/tasks")
                        .with(jwt().jwt(jwt -> jwt.subject("mallory").claim("roles", List.of("UNKNOWN")))
                                .authorities(List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"查询订单 ORD-1001\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(containsString("no role recognized")));
    }

    private static RequestPostProcessor user(String subject, String role) {
        return jwt()
                .jwt(jwt -> jwt.subject(subject).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
