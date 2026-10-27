package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class TicketQueryTool extends AbstractTool {
    public String name() { return "ticket.query"; }
    public boolean readOnly() { return true; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN); }
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "ticketId");
        String id = requiredText(arguments, "ticketId").toUpperCase();
        if (!"TKT-2001".equals(id)) return ToolResult.error("NOT_FOUND", "Ticket does not exist: " + id);
        return ToolResult.ok("Ticket found", Map.of("ticketId", id, "status", "OPEN", "priority", "NORMAL"));
    }
}
