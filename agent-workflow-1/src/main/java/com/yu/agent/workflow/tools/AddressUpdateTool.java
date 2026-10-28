package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.commerce.AddressChange;
import com.yu.agent.workflow.commerce.CommerceRepository;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class AddressUpdateTool extends AbstractTool {
    private final CommerceRepository commerce;

    public AddressUpdateTool(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    public String name() { return "order.address.update"; }
    public boolean readOnly() { return false; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.SUPERVISOR, UserRole.ADMIN); }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "orderId", "newAddress");
        String orderId = requiredText(arguments, "orderId").toUpperCase();
        String newAddress = requiredText(arguments, "newAddress");

        AddressChange change = commerce.updateAddress(orderId, newAddress, context.userId(),
                context.taskId(), idempotencyKey(context, orderId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", change.orderId());
        data.put("oldAddress", change.oldAddress());
        data.put("newAddress", change.newAddress());
        data.put("status", change.status());
        data.put("integrationCommandId", change.integrationCommandId() == null ? "" : change.integrationCommandId());
        data.put("replayed", change.replayed());
        data.put("verified", true);
        data.put("simulation", "memory".equals(commerce.backend()));
        data.put("backend", commerce.backend());
        return ToolResult.ok(change.replayed() ? "Address change already applied (idempotent replay)"
                : "Address updated; logistics sync queued", data);
    }
}
