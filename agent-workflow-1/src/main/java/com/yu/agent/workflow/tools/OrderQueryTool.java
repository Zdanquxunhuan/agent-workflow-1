package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class OrderQueryTool extends AbstractTool {
    private static final Map<String, Map<String, Object>> ORDERS = Map.of(
            "ORD-1001", Map.of("orderId", "ORD-1001", "status", "SHIPPED", "amount", 199.0, "customer", "demo-customer"),
            "ORD-1002", Map.of("orderId", "ORD-1002", "status", "DELIVERED", "amount", 59.0, "customer", "demo-customer"));

    public String name() { return "order.query"; }
    public boolean readOnly() { return true; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN); }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId");
        String id = requiredText(arguments, "orderId").toUpperCase();
        Map<String, Object> order = ORDERS.get(id);
        return order == null ? ToolResult.error("NOT_FOUND", "Order does not exist: " + id) : ToolResult.ok("Order found", order);
    }
}
