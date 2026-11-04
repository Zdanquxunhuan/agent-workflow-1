package com.yu.agent.workflow.mcp;

import com.yu.agent.workflow.tools.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name="agent.mcp.shipment.enabled", havingValue="true")
public class McpCallJournal {
    private final JdbcTemplate jdbc;
    public McpCallJournal(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Transactional(transactionManager="workflowTransactionManager", propagation=Propagation.REQUIRES_NEW, timeout=3)
    public void start(String id, ToolContext context) {
        jdbc.update("INSERT INTO mcp_tool_calls(id,task_id,tenant_id,subject_id,code,created_at) VALUES (?,?,?,?,?,?)",
                id,context.taskId(),context.tenantId(),context.userId(),"STARTED",System.currentTimeMillis());
    }
    @Transactional(transactionManager="workflowTransactionManager", propagation=Propagation.REQUIRES_NEW, timeout=3)
    public void finish(String id,String code,long elapsed) {
        if(jdbc.update("UPDATE mcp_tool_calls SET code=?,elapsed_ms=? WHERE id=?",code,elapsed,id)!=1)
            throw McpHttp.failure("MCP_AUDIT_UNAVAILABLE");
    }
}
