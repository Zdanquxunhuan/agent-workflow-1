package com.yu.agent.workflow.mcp;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Grant administration stays available when remote execution is switched off. */
@Service
@ConditionalOnProperty(name="agent.workflow.persistence",havingValue="jdbc")
public class McpGrantService {
    private final JdbcTemplate jdbc;
    private final String tenant;
    public McpGrantService(JdbcTemplate jdbc,@Value("${agent.commerce.tenant:legacy}") String tenant) {
        this.jdbc=jdbc;this.tenant=tenant;
    }
    public record Grant(String subjectId,String orderId,boolean enabled,long expiresAt,long revision) {}
    private void authorize(AuthenticatedUser actor,String subject,String order) {
        if(actor.role()!=UserRole.ADMIN || !tenant.equals(actor.tenantId())) throw BusinessException.of("FORBIDDEN","Business tenant administrator required");
        if(subject==null || !subject.matches("[A-Za-z0-9._@-]{1,200}") || order==null || !order.matches("ORD-[A-Z0-9-]{1,48}"))
            throw BusinessException.of("INVALID_ARGUMENT","Invalid grant identity");
    }
    public Grant get(AuthenticatedUser actor,String subject,String order) {
        authorize(actor,subject,order);
        var rows=jdbc.query("SELECT enabled,expires_at,revision FROM mcp_order_grants WHERE tenant_id=? AND subject_id=? AND order_id=?",
                (rs,n)->new Grant(subject,order,rs.getBoolean(1),rs.getLong(2),rs.getLong(3)),tenant,subject,order);
        if(rows.isEmpty())return new Grant(subject,order,false,0,0);
        return rows.get(0);
    }
    @Transactional(transactionManager="workflowTransactionManager",timeout=3)
    public Grant put(AuthenticatedUser actor,String subject,String order,boolean enabled,long expiresAt,long expectedRevision,String reason) {
        authorize(actor,subject,order);
        long now=jdbc.queryForObject("SELECT UNIX_TIMESTAMP()",Long.class);
        if(expectedRevision<0 || reason==null || reason.isBlank() || reason.length()>256 || expiresAt<0
                || enabled && (expiresAt<=now || expiresAt>now+30*86400L))
            throw BusinessException.of("INVALID_ARGUMENT","An enabled grant requires an expiry within 30 days and a reason");
        if(jdbc.queryForList("SELECT id FROM orders WHERE id=?",String.class,order).isEmpty())
            throw BusinessException.of("NOT_FOUND","Order does not exist");
        if(expectedRevision==0) {
            try {jdbc.update("INSERT INTO mcp_order_grants(tenant_id,subject_id,order_id,enabled,expires_at) VALUES (?,?,?,?,?)",tenant,subject,order,enabled,expiresAt);}
            catch(DuplicateKeyException ex) {throw BusinessException.of("CONFLICT","Read the current grant revision before updating");}
        } else if(jdbc.update("UPDATE mcp_order_grants SET enabled=?,expires_at=? WHERE tenant_id=? AND subject_id=? AND order_id=? AND revision=?",
                enabled,expiresAt,tenant,subject,order,expectedRevision)!=1)
            throw BusinessException.of("CONFLICT","Grant revision changed");
        Grant result=get(actor,subject,order);
        jdbc.update("INSERT INTO mcp_grant_events VALUES (?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),tenant,actor.userId(),subject,order,
                enabled,expiresAt,result.revision(),reason,now*1000);
        return result;
    }
}
