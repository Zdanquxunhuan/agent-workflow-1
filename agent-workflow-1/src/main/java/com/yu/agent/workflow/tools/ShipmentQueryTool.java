package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class ShipmentQueryTool extends AbstractTool {
    public String name() { return "shipment.query"; }
    public boolean readOnly() { return true; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN); }
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId");
        String id = requiredText(arguments, "orderId").toUpperCase();
        if (!Set.of("ORD-1001", "ORD-1002").contains(id)) return ToolResult.error("NOT_FOUND", "Shipment does not exist: " + id);
        return ToolResult.ok("Shipment found", Map.of("orderId", id, "carrier", "Demo Express", "trackingNo", "DEMO-" + id.substring(4), "status", "IN_TRANSIT"));
    }
}
