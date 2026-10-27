package com.yu.agent.workflow.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
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

@Component
@ConditionalOnProperty(name = "agent.deepseek.enabled", havingValue = "true")
public class DeepSeekToolCallingGateway implements ToolCallingGateway {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final RetryPolicy retryPolicy = new RetryPolicy(2, Duration.ofMillis(200));

    public DeepSeekToolCallingGateway(RestClient.Builder builder, ObjectMapper objectMapper,
                                      @Value("${agent.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                                      @Value("${agent.deepseek.api-key:}") String apiKey,
                                      @Value("${agent.deepseek.model:deepseek-flash}") String model,
                                      @Value("${agent.deepseek.timeout-seconds:30}") long timeoutSeconds) {
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
    public ToolCallingResponse complete(List<Map<String, Object>> messages, List<Map<String, Object>> toolSpecifications) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("tools", toolSpecifications);
        payload.put("tool_choice", "auto");
        payload.put("temperature", 0.0);
        payload.put("stream", false);
        return parse(requestWithBoundedRetry(payload));
    }

    private String requestWithBoundedRetry(Map<String, Object> payload) {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.post().uri("/chat/completions").body(payload).retrieve().body(String.class);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status != 429 && status < 500) {
                    throw BusinessException.of("MODEL_UNAVAILABLE", "DeepSeek rejected the tool-calling request");
                }
                RetryPolicy.FailureClass failureClass = status == 429
                        ? RetryPolicy.FailureClass.RATE_LIMITED
                        : RetryPolicy.FailureClass.TRANSIENT;
                if (!retryPolicy.shouldRetry(failureClass, attempt)) {
                    throw BusinessException.of("MODEL_UNAVAILABLE", "DeepSeek tool-calling request failed");
                }
                sleepBeforeRetry(attempt);
            } catch (RestClientException ex) {
                if (!retryPolicy.shouldRetry(RetryPolicy.FailureClass.TRANSIENT, attempt)) {
                    throw BusinessException.of("MODEL_UNAVAILABLE", "DeepSeek tool-calling request failed");
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
            throw BusinessException.of("MODEL_UNAVAILABLE", "DeepSeek tool-calling request was interrupted");
        }
    }

    private ToolCallingResponse parse(String body) {
        try {
            JsonNode message = objectMapper.readTree(body).at("/choices/0/message");
            String content = message.path("content").isNull() ? null : message.path("content").asText(null);
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                String id = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                String argumentJson = call.path("function").path("arguments").asText("{}");
                Map<String, Object> arguments = objectMapper.readValue(argumentJson, new TypeReference<>() {});
                calls.add(new ToolCall(id, name, arguments));
            }
            Map<String, Object> assistant = objectMapper.convertValue(message, new TypeReference<>() {});
            assistant = new LinkedHashMap<>(assistant);
            assistant.put("role", "assistant");
            return new ToolCallingResponse(content, calls, assistant);
        } catch (Exception ex) {
            throw BusinessException.of("MODEL_INVALID_RESPONSE", "DeepSeek returned an invalid tool-calling response");
        }
    }
}
