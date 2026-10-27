package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class RefundTool extends AbstractTool {
    public String name() { return "refund.create"; }
    public boolean readOnly() { return false; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.SUPERVISOR, UserRole.ADMIN); }
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId", "amount");
        String orderId = requiredText(arguments, "orderId").toUpperCase();
        Object amount = arguments.get("amount");
        if (!(amount instanceof Number number) || number.doubleValue() <= 0 || number.doubleValue() > 100000) {
            throw com.yu.agent.workflow.api.BusinessException.of("INVALID_ARGUMENT", "amount must be between 0 and 100000");
        }
        return ToolResult.ok("Simulated refund created", Map.of("orderId", orderId, "amount", number.doubleValue(), "simulation", true));
    }
}
