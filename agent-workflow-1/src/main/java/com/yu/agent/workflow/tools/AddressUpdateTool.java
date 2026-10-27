package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class AddressUpdateTool extends AbstractTool {
    public String name() { return "order.address.update"; }
    public boolean readOnly() { return false; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.SUPERVISOR, UserRole.ADMIN); }
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId", "newAddress");
        String orderId = requiredText(arguments, "orderId").toUpperCase();
        String newAddress = requiredText(arguments, "newAddress");
        return ToolResult.ok("Simulated address updated", Map.of("orderId", orderId, "newAddress", newAddress, "simulation", true));
    }
}
