package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.application.ChatAuditService;
import com.yu.agent.workflow.application.DeepSeekToolCallingService;
import com.yu.agent.workflow.application.ToolCall;
import com.yu.agent.workflow.application.ToolCallingGateway;
import com.yu.agent.workflow.application.ToolCallingResponse;
import com.yu.agent.workflow.commerce.InMemoryCommerceRepository;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.persistence.EventRepository;
import com.yu.agent.workflow.persistence.InMemoryEventRepository;
import com.yu.agent.workflow.persistence.InMemoryTaskRepository;
import com.yu.agent.workflow.persistence.TaskRepository;
import com.yu.agent.workflow.tools.OrderQueryTool;
import com.yu.agent.workflow.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAuditServiceTests {
    @Test
    void toolCallingRoundsAndResultsBecomeTaskStepsAndEvents() {
        TaskRepository tasks = new InMemoryTaskRepository();
        EventRepository events = new InMemoryEventRepository();
        ChatAuditService audit = new ChatAuditService(tasks, events);
        ToolRegistry registry = new ToolRegistry(List.of(new OrderQueryTool(new InMemoryCommerceRepository())));
        Queue<ToolCallingResponse> responses = new ArrayDeque<>();
        ToolCall call = new ToolCall("call-1", "order_query", Map.of("orderId", "ORD-1001"));
        responses.add(new ToolCallingResponse(null, List.of(call), assistantWithCall(call)));
        responses.add(new ToolCallingResponse("订单已发货", List.of(), Map.of("role", "assistant", "content", "订单已发货")));
        ToolCallingGateway gateway = (messages, specifications) -> responses.remove();
        DeepSeekToolCallingService service = new DeepSeekToolCallingService(
                gateway, registry, new com.yu.agent.workflow.application.PromptInjectionGuard(), new ObjectMapper());

        ChatAuditService.ChatSession session = audit.start(
                "alice", UserRole.CUSTOMER_SERVICE, "查询订单 ORD-1001", "chat-test-key", "chat-test-trace");
        var result = service.chat("alice", UserRole.CUSTOMER_SERVICE, "查询订单 ORD-1001", "chat-test-trace",
                session.taskId(), audit.listener(session));
        audit.complete(session, result);

        var task = tasks.require(session.taskId()).view();
        assertThat(result.taskId()).isEqualTo(session.taskId());
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.steps()).singleElement().satisfies(step -> {
            assertThat(step.toolName()).isEqualTo("order.query");
            assertThat(step.status()).isEqualTo("SUCCEEDED");
        });
        assertThat(events.findByTask(session.taskId()).stream().map(event -> event.type()))
                .contains("CHAT_STARTED", "MODEL_ROUND", "TOOL_CALL_REQUESTED", "TOOL_EXECUTED",
                        "CHAT_COMPLETED", "TASK_SUCCEEDED");
    }

    private Map<String, Object> assistantWithCall(ToolCall call) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(Map.of("id", call.id(), "type", "function",
                "function", Map.of("name", call.name(), "arguments", "{\"orderId\":\"ORD-1001\"}"))));
        return assistant;
    }
}
