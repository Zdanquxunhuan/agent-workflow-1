package com.yu.agent.workflow.mcp;

import com.yu.agent.workflow.tools.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Kept active even after MCP is disabled: persisted answers must not outlive their source grants. */
@Component
@ConditionalOnProperty(name="agent.workflow.persistence", havingValue="jdbc")
public class McpEvidenceGuard {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    public McpEvidenceGuard(DataSource dataSource) {
        this.dataSource=dataSource; this.jdbc=new JdbcTemplate(dataSource); this.jdbc.setQueryTimeout(2);
    }
    @Transactional(transactionManager="workflowTransactionManager")
    public void record(ToolContext context, String order, long revision, String ledgerId) {
        if(!ledgerId.equals(jdbc.queryForObject("SELECT ledger_id FROM mcp_ledger_binding WHERE id=1",String.class)))
            throw McpHttp.failure("MCP_CONTRACT_MISMATCH");
        var tenants=jdbc.queryForList("SELECT tenant_id FROM tasks WHERE id=?",String.class,context.taskId());
        if(tenants.size()!=1 || !context.tenantId().equals(tenants.get(0))) throw McpHttp.failure("MCP_IDENTITY_REQUIRED");
        freshGrant(context.tenantId(), context.userId(), context.userId(), order, revision);
        jdbc.update("INSERT IGNORE INTO mcp_task_sources(task_id,order_id,tenant_id,subject_id,grant_revision) VALUES (?,?,?,?,?)",
                context.taskId(),order,context.tenantId(),context.userId(),revision);
    }
    public void validateTask(ToolContext context) {
        var sources=jdbc.queryForList("SELECT tenant_id,subject_id,order_id,grant_revision FROM mcp_task_sources WHERE task_id=?",context.taskId());
        for(var source:sources) {
            if(!context.tenantId().equals(source.get("tenant_id"))) throw McpHttp.failure("MCP_EVIDENCE_CHANGED");
            freshGrant(context.tenantId(), source.get("subject_id").toString(), context.userId(),
                    source.get("order_id").toString(), ((Number)source.get("grant_revision")).longValue());
        }
    }
    private void freshGrant(String tenant, String original, String reader, String order, long revision) {
        // An independent, autocommit connection avoids a surrounding MySQL REPEATABLE READ snapshot.
        // Source edges above remain in the caller transaction, so newly inserted edges are also visible.
        try(Connection connection=dataSource.getConnection()) {
            connection.setAutoCommit(true); connection.setReadOnly(true);
            try(var statement=connection.prepareStatement("SELECT 1 FROM mcp_order_grants origin JOIN mcp_order_grants reader "
                    +"ON reader.tenant_id=origin.tenant_id AND reader.order_id=origin.order_id "
                    +"WHERE origin.tenant_id=? AND origin.subject_id=? AND origin.order_id=? AND origin.revision=? "
                    +"AND origin.enabled=true AND origin.expires_at>UNIX_TIMESTAMP() "
                    +"AND reader.subject_id=? AND reader.enabled=true AND reader.expires_at>UNIX_TIMESTAMP()")) {
                statement.setQueryTimeout(2); statement.setString(1,tenant); statement.setString(2,original);
                statement.setString(3,order); statement.setLong(4,revision); statement.setString(5,reader);
                try(var result=statement.executeQuery()) {if(!result.next()) throw McpHttp.failure("MCP_EVIDENCE_CHANGED");}
            }
        } catch(SQLException ex) {throw McpHttp.failure("MCP_UNAVAILABLE");}
    }
}
