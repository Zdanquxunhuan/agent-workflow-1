package com.yu.agent.workflow.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** Protocol adapter only: the application owns the tool loop and authorization. */
@Component
@ConditionalOnProperty(name = "agent.deepseek.enabled", havingValue = "true")
@ConditionalOnExpression("'${agent.deepseek.gateway:legacy}' == 'spring-ai'")
public class SpringAiToolCallingGateway implements ToolCallingGateway {
    private final ChatModel model;
    private final ObjectMapper json;

    @Autowired
    public SpringAiToolCallingGateway(RestClient.Builder builder, ObjectMapper json,
            @Value("${agent.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${agent.deepseek.api-key:}") String apiKey,
            @Value("${agent.deepseek.model:deepseek-flash}") String modelName,
            @Value("${agent.deepseek.timeout-seconds:30}") long seconds,
            @Value("${agent.deepseek.max-output-tokens:1200}") int maxTokens) {
        if (apiKey == null || apiKey.isBlank() || "replace-me".equalsIgnoreCase(apiKey.trim()))
            throw BusinessException.of("MODEL_NOT_CONFIGURED", "Model API key is required");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, seconds))).build());
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, seconds)));
        var api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey)
                .completionsPath("/chat/completions")
                .restClientBuilder(builder.requestFactory(factory)).build();
        this.model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).temperature(0.0)
                        .responseFormat(org.springframework.ai.openai.api.ResponseFormat.builder()
                                .type(org.springframework.ai.openai.api.ResponseFormat.Type.JSON_OBJECT).build())
                        .extraBody("api.deepseek.com".equals(java.net.URI.create(baseUrl).getHost())
                                ? Map.of("thinking", Map.of("type", "disabled")) : Map.of())
                        .maxTokens(Math.max(1, maxTokens)).internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        this.json = json;
    }

    public SpringAiToolCallingGateway(ChatModel model, ObjectMapper json) {
        this.model = model;
        this.json = json;
    }

    @Override
    public ToolCallingResponse complete(List<Map<String, Object>> messages, List<Map<String, Object>> specifications) {
        long started = System.nanoTime();
        var options = OpenAiChatOptions.builder().internalToolExecutionEnabled(false)
                .tools(specifications.stream().map(s -> json.convertValue(s, OpenAiApi.FunctionTool.class)).toList())
                .build();
        org.springframework.ai.chat.model.ChatResponse response;
        try {
            response = model.call(new Prompt(convertMessages(messages), options));
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Never expose provider bodies, prompts or credentials in API errors.
            throw BusinessException.of("MODEL_UNAVAILABLE", "Spring AI model call failed");
        }
        try {
            if (response == null || response.getResult() == null) throw new IllegalArgumentException();
            if (response.hasFinishReasons(Set.of("length", "content_filter"))) throw new IllegalArgumentException();
            AssistantMessage output = response.getResult().getOutput();
            List<ToolCall> calls = new ArrayList<>();
            List<Map<String, Object>> wireCalls = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var call : output.getToolCalls()) {
                if (call.id() == null || call.id().isBlank() || !ids.add(call.id())
                        || call.name() == null || call.name().isBlank()) throw new IllegalArgumentException();
                Map<String, Object> arguments = json.readValue(call.arguments(), new TypeReference<>() {});
                if (arguments == null) throw new IllegalArgumentException();
                calls.add(new ToolCall(call.id(), call.name(), arguments));
                wireCalls.add(Map.of("id", call.id(), "type", "function",
                        "function", Map.of("name", call.name(), "arguments", call.arguments())));
            }
            String content = output.getText();
            if (calls.isEmpty() && (content == null || content.isBlank())) throw new IllegalArgumentException();
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", content);
            if (!wireCalls.isEmpty()) assistant.put("tool_calls", wireCalls);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("gateway", "spring-ai");
            metadata.put("model", Objects.toString(response.getMetadata().getModel(), "unknown"));
            metadata.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            metadata.put("promptVersion", "agent-readonly-v5");
            metadata.put("toolSchemaVersion", "readonly-v1");
            var usage = response.getMetadata().getUsage();
            boolean known = usage != null && usage.getNativeUsage() != null
                    && usage.getPromptTokens() != null && usage.getCompletionTokens() != null
                    && usage.getPromptTokens() >= 0 && usage.getCompletionTokens() >= 0;
            metadata.put("usageSource", known ? "provider" : "unknown");
            if (known) {
                metadata.put("inputTokens", usage.getPromptTokens());
                metadata.put("outputTokens", usage.getCompletionTokens());
            }
            return new ToolCallingResponse(content, calls, assistant, metadata);
        } catch (Exception ex) {
            throw BusinessException.of("MODEL_INVALID_RESPONSE", "Model returned an invalid tool response");
        }
    }

    private List<Message> convertMessages(List<Map<String, Object>> messages) {
        List<Message> result = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (var message : messages) {
            String text = Objects.toString(message.get("content"), "");
            switch (Objects.toString(message.get("role"))) {
                case "system" -> result.add(new SystemMessage(text));
                case "user" -> result.add(new UserMessage(text));
                case "assistant" -> {
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                    for (var call : json.valueToTree(message).path("tool_calls")) {
                        String id = call.path("id").asText();
                        String name = call.path("function").path("name").asText();
                        names.put(id, name);
                        calls.add(new AssistantMessage.ToolCall(id, "function", name,
                                call.path("function").path("arguments").asText()));
                    }
                    result.add(AssistantMessage.builder().content(text).toolCalls(calls).build());
                }
                case "tool" -> {
                    String id = Objects.toString(message.get("tool_call_id"), "");
                    if (!names.containsKey(id)) throw BusinessException.of("MODEL_INVALID_MESSAGES", "Unpaired tool result");
                    result.add(ToolResponseMessage.builder().responses(List.of(
                            new ToolResponseMessage.ToolResponse(id, names.get(id), text))).build());
                }
                default -> throw BusinessException.of("MODEL_INVALID_MESSAGES", "Unsupported message role");
            }
        }
        return result;
    }
}
