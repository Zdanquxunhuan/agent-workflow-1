package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.DeepSeekToolCallingService;
import com.yu.agent.workflow.application.PromptInjectionGuard;
import com.yu.agent.workflow.application.ToolCall;
import com.yu.agent.workflow.application.ToolCallingGateway;
import com.yu.agent.workflow.application.ToolCallingResponse;
import com.yu.agent.workflow.application.KnowledgeBase;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.tools.AddressUpdateTool;
import com.yu.agent.workflow.tools.KnowledgeSearchTool;
import com.yu.agent.workflow.tools.OrderQueryTool;
import com.yu.agent.workflow.tools.RefundTool;
import com.yu.agent.workflow.tools.ShipmentQueryTool;
import com.yu.agent.workflow.tools.TicketQueryTool;
import com.yu.agent.workflow.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallingServiceTests {
    @Test
    void executesMultipleReadOnlyRoundsThenReturnsFinalAnswer() {
        ToolRegistry registry = registry();
        Queue<ToolCallingResponse> responses = new ArrayDeque<>();
        responses.add(responseWithCall(new ToolCall("call-1", "order.query", Map.of("orderId", "ORD-1001"))));
        responses.add(new ToolCallingResponse("订单 ORD-1001 当前状态为 SHIPPED。", List.of(), Map.of("role", "assistant", "content", "订单 ORD-1001 当前状态为 SHIPPED。")));
        DeepSeekToolCallingService service = service(registry, responses);

        var result = service.chat("alice", UserRole.CUSTOMER_SERVICE, "查询订单 ORD-1001", "trace");

        assertThat(result.answer()).contains("SHIPPED");
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.executedTools()).containsExactly("order.query");
    }

    @Test
    void modelCannotUseTheReadOnlyChatPathToCallAWriteTool() {
        ToolRegistry registry = registry();
        Queue<ToolCallingResponse> responses = new ArrayDeque<>();
        responses.add(responseWithCall(new ToolCall("call-1", "refund.create", Map.of("orderId", "ORD-1001", "amount", 20))));
        DeepSeekToolCallingService service = service(registry, responses);

        assertThatThrownBy(() -> service.chat("alice", UserRole.CUSTOMER_SERVICE, "退款", "trace"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("read-only");
    }

    private DeepSeekToolCallingService service(ToolRegistry registry, Queue<ToolCallingResponse> responses) {
        ToolCallingGateway gateway = (messages, tools) -> responses.remove();
        return new DeepSeekToolCallingService(gateway, registry, new PromptInjectionGuard(), new ObjectMapper());
    }

    private ToolCallingResponse responseWithCall(ToolCall call) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(Map.of("id", call.id(), "type", "function",
                "function", Map.of("name", call.name(), "arguments", "{}"))));
        return new ToolCallingResponse(null, List.of(call), assistant);
    }

    private ToolRegistry registry() {
        return new ToolRegistry(List.of(new OrderQueryTool(), new ShipmentQueryTool(), new TicketQueryTool(),
                new KnowledgeSearchTool(new KnowledgeBase()), new RefundTool(), new AddressUpdateTool()));
    }
}
