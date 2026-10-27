package com.yu.agent.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.tools.ToolContext;
import com.yu.agent.workflow.tools.ToolRegistry;
import com.yu.agent.workflow.tools.ToolResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeepSeekToolCallingService {
    private static final int MAX_ROUNDS = 4;
    private final ToolCallingGateway gateway;
    private final ToolRegistry registry;
    private final PromptInjectionGuard guard;
    private final ObjectMapper objectMapper;

    public DeepSeekToolCallingService(ToolCallingGateway gateway, ToolRegistry registry,
                                      PromptInjectionGuard guard, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.registry = registry;
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    public ToolChatResult chat(String userId, UserRole role, String request, String traceId) {
        return chat(userId, role, request, traceId, null, ToolCallingAuditListener.NOOP);
    }

    public ToolChatResult chat(String userId, UserRole role, String request, String traceId,
                               String taskId, ToolCallingAuditListener auditListener) {
        guard.check(request);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是售后查询助手。只使用提供的只读工具，完成工具调用后再回答；不要执行退款、改地址或其他写操作。"));
        messages.add(Map.of("role", "user", "content", request));
        List<Map<String, Object>> specs = readOnlySpecifications();
        List<String> executed = new ArrayList<>();
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            ToolCallingResponse response = gateway.complete(messages, specs);
            auditListener.onModelRound(round, response.toolCalls());
            if (response.toolCalls().isEmpty()) {
                return new ToolChatResult(taskId, response.content() == null ? "模型没有返回最终答复" : response.content(), round, executed);
            }
            messages.add(response.assistantMessage());
            for (ToolCall call : response.toolCalls()) {
                String internalName = internalToolName(call.name());
                Instant startedAt = Instant.now();
                if (!registry.contains(internalName) || !registry.require(internalName).readOnly()) {
                    BusinessException blocked = BusinessException.of("TOOL_CALL_BLOCKED", "Only registered read-only tools may be called");
                    auditListener.onToolCallRejected(round, call, internalName, startedAt, blocked);
                    throw blocked;
                }
                auditListener.onToolCallStarted(round, call, internalName, startedAt);
                ToolResult result;
                try {
                    result = registry.execute(internalName, call.arguments(), new ToolContext(userId, role,
                            taskId == null ? "chat" : taskId, traceId));
                } catch (BusinessException ex) {
                    auditListener.onToolCallRejected(round, call, internalName, startedAt, ex);
                    throw ex;
                }
                auditListener.onToolCallCompleted(round, call, internalName, startedAt, Instant.now(), result);
                executed.add(internalName);
                messages.add(toolMessage(call, result));
            }
        }
        throw BusinessException.of("TOOL_CALL_LIMIT", "Tool-calling round limit exceeded");
    }

    private List<Map<String, Object>> readOnlySpecifications() {
        List<Map<String, Object>> specifications = new ArrayList<>();
        for (var tool : registry.readOnlyTools()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            if (tool.name().equals("order.query") || tool.name().equals("shipment.query")) properties.put("orderId", Map.of("type", "string"));
            if (tool.name().equals("ticket.query")) properties.put("ticketId", Map.of("type", "string"));
            if (tool.name().equals("knowledge.search")) properties.put("query", Map.of("type", "string"));
            Map<String, Object> function = new LinkedHashMap<>();
            // DeepSeek/OpenAI function names cannot contain dots; keep the
            // server's dotted registry name behind a reversible wire name.
            function.put("name", externalToolName(tool.name()));
            function.put("description", "Read-only simulated tool: " + tool.name());
            function.put("parameters", Map.of("type", "object", "properties", properties, "additionalProperties", false));
            specifications.add(Map.of("type", "function", "function", function));
        }
        return specifications;
    }

    private String externalToolName(String internalName) { return internalName.replace('.', '_'); }

    private String internalToolName(String externalName) {
        if (registry.contains(externalName)) return externalName;
        return externalName.replace('_', '.');
    }

    private Map<String, Object> toolMessage(ToolCall call, ToolResult result) {
        try {
            return Map.of("role", "tool", "tool_call_id", call.id(), "content", objectMapper.writeValueAsString(result));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialize tool result", ex);
        }
    }

    public record ToolChatResult(String taskId, String answer, int rounds, List<String> executedTools) {
        public ToolChatResult(String answer, int rounds, List<String> executedTools) {
            this(null, answer, rounds, executedTools);
        }

        public ToolChatResult {
            executedTools = List.copyOf(executedTools);
        }
    }

    public interface ToolCallingAuditListener {
        ToolCallingAuditListener NOOP = new ToolCallingAuditListener() {};

        default void onModelRound(int round, List<ToolCall> toolCalls) {}

        default void onToolCallStarted(int round, ToolCall call, String internalName, Instant startedAt) {}

        default void onToolCallCompleted(int round, ToolCall call, String internalName,
                                         Instant startedAt, Instant finishedAt, ToolResult result) {}

        default void onToolCallRejected(int round, ToolCall call, String internalName,
                                        Instant startedAt, BusinessException exception) {}
    }
}
