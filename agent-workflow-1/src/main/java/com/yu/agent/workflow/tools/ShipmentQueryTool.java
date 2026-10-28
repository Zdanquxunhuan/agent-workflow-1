package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.commerce.CommerceRepository;
import com.yu.agent.workflow.commerce.ShipmentDetail;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class ShipmentQueryTool extends AbstractTool {
    private final CommerceRepository commerce;

    public ShipmentQueryTool(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    public String name() { return "shipment.query"; }
    public boolean readOnly() { return true; }
    public Set<UserRole> allowedRoles() {
        return Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN);
    }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId");
        String id = requiredText(arguments, "orderId").toUpperCase();
        ShipmentDetail shipment = commerce.shipmentByOrder(id)
                .orElseThrow(() -> BusinessException.of("NOT_FOUND", "Shipment does not exist for order: " + id));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shipmentId", shipment.shipmentId());
        data.put("orderId", id);
        data.put("carrier", shipment.carrier());
        data.put("trackingNo", shipment.trackingNo() == null ? "" : shipment.trackingNo());
        data.put("status", shipment.status());
        data.put("shippedAt", shipment.shippedAt() == null ? "" : shipment.shippedAt().toString());
        data.put("deliveredAt", shipment.deliveredAt() == null ? "" : shipment.deliveredAt().toString());
        data.put("events", shipment.events());
        data.put("source", commerce.backend());
        return ToolResult.ok("Shipment found", data);
    }
}
