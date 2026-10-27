package com.yu.agent.workflow.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.PlanStep;
import com.yu.agent.workflow.domain.TaskPlan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional DeepSeek planner. The model can propose a plan, but it never receives
 * a Java tool reference and never executes a side effect. AgentApplicationService
 * validates the returned names, arguments, role and approval requirement again.
 */
@Component
@ConditionalOnProperty(name = "agent.deepseek.enabled", havingValue = "true")
public class DeepSeekTaskPlanner implements TaskPlanner {
    private static final String SYSTEM_PROMPT = """
            You are a constrained enterprise after-sales workflow planner.
            Return JSON only. The JSON must have exactly one top-level field named steps.
            Each step has toolName, arguments, and requiresApproval.
            Only use these tool names: order.query, shipment.query, ticket.query,
            knowledge.search, refund.create, order.address.update.
            refund.create and order.address.update always require approval.
            Never invent a tool, user id, permission, or business result.
            If required information is missing, return {"steps":[]}.
            Example JSON: {"steps":[{"toolName":"order.query","arguments":{"orderId":"ORD-1001"},"requiresApproval":false}]}
            """;

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final RetryPolicy retryPolicy = new RetryPolicy(2, Duration.ofMillis(200));

    public DeepSeekTaskPlanner(RestClient.Builder builder, ObjectMapper objectMapper,
                               @org.springframework.beans.factory.annotation.Value("${agent.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                               @org.springframework.beans.factory.annotation.Value("${agent.deepseek.api-key:}") String apiKey,
                               @org.springframework.beans.factory.annotation.Value("${agent.deepseek.model:deepseek-flash}") String model,
                               @org.springframework.beans.factory.annotation.Value("${agent.deepseek.timeout-seconds:30}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank() || "replace-me".equalsIgnoreCase(apiKey.trim())) {
            throw BusinessException.of("MODEL_NOT_CONFIGURED", "DEEPSEEK_API_KEY is required when DeepSeek is enabled");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        this.client = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @Override
    public TaskPlan plan(String request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", request)));
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("temperature", 0.0);
        payload.put("max_tokens", 1200);
        payload.put("stream", false);

        final String response = requestWithBoundedRetry(payload);
        return parsePlan(response);
    }

    private String requestWithBoundedRetry(Map<String, Object> payload) {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.post().uri("/chat/completions").body(payload).retrieve().body(String.class);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status != 429 && status < 500) {
                    throw BusinessException.of("MODEL_UNAVAILABLE", "DeepSeek rejected the request");
                }
                if (!retryPolicy.shouldRetry(RetryPolicy.FailureClass.TRANSIENT, attempt)) {
                    throw BusinessException.of("MODEL_UNAVAILABLE", "DeepSeek request failed");
                }
                sleepBeforeRetry(attempt);
            } catch (RestClientException ex) {
                if (!retryPolicy.shouldRetry(RetryPolicy.FailureClass.TRANSIENT, attempt)) {
                    throw BusinessException.of("MODEL_UNAVAILABLE", "DeepSeek request failed");
                }
                sleepBeforeRetry(attempt);
            }
        }
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryPolicy.delayFor(attempt).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw BusinessException.of("MODEL_UNAVAILABLE", "DeepSeek request was interrupted");
        }
    }

    TaskPlan parsePlan(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText(null);
            if (content == null || content.isBlank()) {
                throw BusinessException.of("MODEL_INVALID_RESPONSE", "DeepSeek returned no plan content");
            }
            JsonNode plan = objectMapper.readTree(stripMarkdownFence(content));
            JsonNode stepsNode = plan.get("steps");
            if (stepsNode == null || !stepsNode.isArray()) {
                throw BusinessException.of("MODEL_INVALID_RESPONSE", "DeepSeek plan must contain a steps array");
            }
            List<PlanStep> steps = new ArrayList<>();
            int sequence = 1;
            for (JsonNode step : stepsNode) {
                String toolName = step.path("toolName").asText(null);
                JsonNode argsNode = step.get("arguments");
                if (toolName == null || toolName.isBlank() || argsNode == null || !argsNode.isObject()) {
                    throw BusinessException.of("MODEL_INVALID_RESPONSE", "Every plan step needs toolName and object arguments");
                }
                Map<String, Object> args = objectMapper.convertValue(argsNode, new TypeReference<>() {});
                boolean requiresApproval = step.path("requiresApproval").asBoolean(false);
                steps.add(new PlanStep(sequence++, toolName, args, requiresApproval));
            }
            return new TaskPlan(steps);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.of("MODEL_INVALID_RESPONSE", "DeepSeek returned invalid JSON");
        }
    }

    private String stripMarkdownFence(String content) {
        String value = content.trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int firstLineEnd = value.indexOf('\n');
            return firstLineEnd >= 0 ? value.substring(firstLineEnd + 1, value.length() - 3).trim() : value;
        }
        return value;
    }
}
