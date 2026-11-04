package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.mcp.*;
import com.yu.agent.workflow.persistence.TaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConditionalOnProperty(name="agent.mcp.shipment.enabled", havingValue="true")
public class McpShipmentQueryTool extends AbstractTool {
    private final McpShipmentClient client;
    private final McpEvidenceGuard evidence;
    private final McpCallJournal journal;
    private final TaskRepository tasks;
    public McpShipmentQueryTool(McpShipmentClient client,McpEvidenceGuard evidence,McpCallJournal journal,TaskRepository tasks) {
        this.client=client;this.evidence=evidence;this.journal=journal;this.tasks=tasks;
    }
    public String name() {return "shipment.query";}
    public boolean readOnly() {return true;}
    public Set<UserRole> allowedRoles() {return Set.of(UserRole.CUSTOMER_SERVICE,UserRole.SUPERVISOR,UserRole.ADMIN);}
    public ToolResult execute(Map<String,Object> arguments,ToolContext context) {
        rejectUnknown(arguments,"orderId");
        String order=requiredText(arguments,"orderId").toUpperCase(Locale.ROOT);
        if(!order.matches("ORD-[A-Z0-9-]{1,48}")) throw BusinessException.of("INVALID_ARGUMENT","Invalid orderId");
        if(!context.tenantId().equals(tasks.require(context.taskId()).tenantId()))
            throw BusinessException.of("MCP_ACCESS_DENIED","Task belongs to a different tenant");
        String callId=UUID.randomUUID().toString();long started=System.nanoTime();
        journal.start(callId,context);
        String code="MCP_INTERNAL_ERROR";
        try {
            Map<String,Object> data=new LinkedHashMap<>(client.track(order,context,callId));
            long revision=((Number)data.remove("grantRevision")).longValue();
            evidence.record(context,order,revision,(String)data.remove("ledgerId"));
            evidence.validateTask(context);
            data.put("source","mcp:shipment-service/shipment_track");data.put("mcpCallId",callId);
            data.put("protocolVersion",McpSettings.PROTOCOL);
            code="OK";
            return ToolResult.ok("Shipment found",data);
        } catch(BusinessException ex) {code=ex.code();throw ex;}
        finally {journal.finish(callId,code,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));}
    }
}
