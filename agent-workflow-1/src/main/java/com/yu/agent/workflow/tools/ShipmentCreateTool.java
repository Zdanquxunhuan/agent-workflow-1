package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.commerce.CommerceRepository;
import com.yu.agent.workflow.commerce.ShipmentDetail;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class ShipmentCreateTool extends AbstractTool {
    private final CommerceRepository commerce;

    public ShipmentCreateTool(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    public String name() { return "shipment.create"; }
    public boolean readOnly() { return false; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.SUPERVISOR, UserRole.ADMIN); }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId", "carrier");
        String orderId = requiredText(arguments, "orderId").toUpperCase();
        String carrier = optionalText(arguments, "carrier", "SF Express");

        ShipmentDetail shipment = commerce.createShipment(orderId, carrier, context.userId(),
                context.taskId(), idempotencyKey(context, orderId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shipmentId", shipment.shipmentId());
        data.put("orderId", shipment.orderId());
        data.put("carrier", shipment.carrier());
        data.put("trackingNo", shipment.trackingNo() == null ? "" : shipment.trackingNo());
        data.put("status", shipment.status());
        data.put("replayed", shipment.replayed());
        data.put("verified", true);
        data.put("simulation", "memory".equals(commerce.backend()));
        data.put("backend", commerce.backend());
        return ToolResult.ok(shipment.replayed() ? "Shipment already exists (idempotent replay)"
                : "Shipment request accepted; carrier dispatch queued", data);
    }
}
