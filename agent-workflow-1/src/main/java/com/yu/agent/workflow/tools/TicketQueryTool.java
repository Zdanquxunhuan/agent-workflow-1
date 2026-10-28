package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.commerce.CommerceRepository;
import com.yu.agent.workflow.commerce.TicketDetail;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class TicketQueryTool extends AbstractTool {
    private final CommerceRepository commerce;

    public TicketQueryTool(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    public String name() { return "ticket.query"; }
    public boolean readOnly() { return true; }
    public Set<UserRole> allowedRoles() {
        return Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN);
    }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "ticketId");
        String id = requiredText(arguments, "ticketId").toUpperCase();
        TicketDetail ticket = commerce.ticket(id)
                .orElseThrow(() -> BusinessException.of("NOT_FOUND", "Ticket does not exist: " + id));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.ticketId());
        data.put("orderId", ticket.orderId() == null ? "" : ticket.orderId());
        data.put("customerId", ticket.customerId());
        data.put("status", ticket.status());
        data.put("priority", ticket.priority());
        data.put("subject", ticket.subject());
        data.put("source", commerce.backend());
        return ToolResult.ok("Ticket found", data);
    }
}
