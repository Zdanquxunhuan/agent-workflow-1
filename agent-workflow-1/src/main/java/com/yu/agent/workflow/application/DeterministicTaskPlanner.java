package com.yu.agent.workflow.application;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.PlanStep;
import com.yu.agent.workflow.domain.TaskPlan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "agent.deepseek.enabled", havingValue = "false", matchIfMissing = true)
public class DeterministicTaskPlanner implements TaskPlanner {
    private static final Pattern ORDER = Pattern.compile("(?i)(ORD-\\d{4})");
    private static final Pattern TICKET = Pattern.compile("(?i)(TKT-\\d{4})");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:金额|amount|退款)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)");

    @Override
    public TaskPlan plan(String request) {
        String normalized = request.toLowerCase(Locale.ROOT);
        List<PlanStep> steps = new ArrayList<>();
        Matcher order = ORDER.matcher(request);
        Matcher ticket = TICKET.matcher(request);
        String orderId = order.find() ? order.group(1).toUpperCase(Locale.ROOT) : null;
        String ticketId = ticket.find() ? ticket.group(1).toUpperCase(Locale.ROOT) : null;

        // Policy questions can contain the word “refund”, but they are read-only
        // knowledge requests unless an order id and refund amount are present.
        if (containsAny(normalized, "政策", "规则", "policy", "知识")
                && (!containsAny(normalized, "amount", "金额") || orderId == null)) {
            steps.add(new PlanStep(1, "knowledge.search", Map.of("query", request), false));
        } else if (containsAny(normalized, "退款", "refund")) {
            require(orderId, "Refund requests must include an order id such as ORD-1001");
            Matcher amount = AMOUNT.matcher(request);
            if (!amount.find()) throw BusinessException.of("INVALID_ARGUMENT", "Refund requests must include an amount");
            steps.add(new PlanStep(1, "refund.create", Map.of("orderId", orderId, "amount", Double.parseDouble(amount.group(1))), true));
        } else if (containsAny(normalized, "地址", "address")) {
            require(orderId, "Address changes must include an order id such as ORD-1001");
            String address = extractAddress(request);
            steps.add(new PlanStep(1, "order.address.update", Map.of("orderId", orderId, "newAddress", address), true));
        } else if (containsAny(normalized, "物流", "配送", "shipment", "tracking")) {
            require(orderId, "Shipment requests must include an order id such as ORD-1001");
            steps.add(new PlanStep(1, "shipment.query", Map.of("orderId", orderId), false));
        } else if (containsAny(normalized, "工单", "ticket")) {
            require(ticketId, "Ticket requests must include a ticket id such as TKT-2001");
            steps.add(new PlanStep(1, "ticket.query", Map.of("ticketId", ticketId), false));
        } else if (containsAny(normalized, "政策", "规则", "policy", "知识")) {
            steps.add(new PlanStep(1, "knowledge.search", Map.of("query", request), false));
        } else if (containsAny(normalized, "订单", "order")) {
            require(orderId, "Order requests must include an order id such as ORD-1001");
            steps.add(new PlanStep(1, "order.query", Map.of("orderId", orderId), false));
        } else {
            throw BusinessException.of("UNSUPPORTED_REQUEST", "Request does not map to a registered workflow");
        }
        return new TaskPlan(steps);
    }

    private String extractAddress(String request) {
        int separator = Math.max(request.indexOf(':'), request.indexOf('：'));
        if (separator >= 0 && separator + 1 < request.length()) {
            String value = request.substring(separator + 1).trim();
            if (!value.isBlank()) return value;
        }
        return "模拟新地址";
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static void require(String value, String message) {
        if (value == null) throw BusinessException.of("INVALID_ARGUMENT", message);
    }
}
