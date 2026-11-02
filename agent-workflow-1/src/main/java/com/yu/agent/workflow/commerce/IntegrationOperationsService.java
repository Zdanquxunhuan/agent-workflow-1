package com.yu.agent.workflow.commerce;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.AgentApplicationService;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnExpression("'${agent.workflow.persistence:memory}' == 'jdbc' and '${agent.commerce.dispatcher.enabled:true}' == 'true'")
public class IntegrationOperationsService {
    private final JdbcTemplate jdbc;
    private final JdbcCommerceOutbox outbox;
    private final CommerceCommandDispatcher dispatcher;
    private final AgentApplicationService tasks;

    public IntegrationOperationsService(JdbcTemplate jdbc, JdbcCommerceOutbox outbox,
                                         CommerceCommandDispatcher dispatcher, AgentApplicationService tasks) {
        this.jdbc=jdbc; this.outbox=outbox; this.dispatcher=dispatcher; this.tasks=tasks;
    }

    public List<Map<String,Object>> commands(String taskId, String actor, UserRole role) {
        tasks.get(taskId, actor, role);
        return jdbc.queryForList("select id,task_id,command_type,aggregate_id,idempotency_key,status,attempts,external_ref,last_error,next_attempt_at,created_at,updated_at from integration_commands where task_id=? order by created_at", taskId);
    }

    public Map<String,Object> command(String commandId, String actor, UserRole role) {
        List<Map<String,Object>> rows = jdbc.queryForList("select id,task_id,command_type,aggregate_id,idempotency_key,status,attempts,external_ref,last_error,next_attempt_at,created_at,updated_at from integration_commands where id=?", commandId);
        if (rows.isEmpty()) throw BusinessException.of("NOT_FOUND", "Integration command not found");
        Map<String,Object> result = new LinkedHashMap<>(rows.get(0));
        if (result.get("task_id") == null) requireOperator(role);
        else tasks.get(result.get("task_id").toString(), actor, role);
        result.put("events", jdbc.queryForList("select actor,action,message,occurred_at from integration_events where command_id=? order by occurred_at,id", commandId));
        result.put("reviews", jdbc.queryForList("select * from integration_reviews where command_id=?", commandId));
        return result;
    }

    public List<Map<String,Object>> reviews(String status, UserRole role) {
        requireOperator(role);
        if (!List.of("OPEN", "RESOLVED").contains(status)) throw BusinessException.of("INVALID_ARGUMENT", "Review status must be OPEN or RESOLVED");
        return jdbc.queryForList("""
                select r.*,c.task_id,c.status as command_status,c.aggregate_id,c.attempts,c.external_ref
                from integration_reviews r join integration_commands c on c.id=r.command_id
                where r.status=? order by r.created_at desc limit 200
                """, status);
    }

    /** No encompassing DB transaction: the provider GET runs between short claim/settlement transactions. */
    public Map<String,Object> recheck(String reviewId, String actor, UserRole role, String reason) {
        requireOperator(role);
        Map<String,Object> review = review(reviewId);
        if (!"OPEN".equals(review.get("status"))) throw BusinessException.of("INVALID_STATE", "Review is already resolved");
        String commandId = review.get("command_id").toString();
        String owner = "manual-" + UUID.randomUUID();
        CommerceCommand command = outbox.claimForReview(commandId, owner, Duration.ofSeconds(30));
        outbox.event(commandId, actor, "MANUAL_RECHECK", reason);
        dispatcher.process(command, owner, true);
        return command(commandId, actor, role);
    }

    @Transactional(transactionManager="workflowTransactionManager")
    public Map<String,Object> acknowledgeFailure(String reviewId, String actor, UserRole role, String reason) {
        requireOperator(role);
        Map<String,Object> review = review(reviewId);
        String commandId = review.get("command_id").toString();
        String status = jdbc.queryForObject("select status from integration_commands where id=? for update", String.class, commandId);
        if (!"FAILED".equals(status)) throw BusinessException.of("INVALID_STATE", "Only confirmed FAILED commands can be acknowledged; uncertain outcomes require provider evidence");
        int changed = jdbc.update("update integration_reviews set status='RESOLVED',resolution=?,resolved_by=?,resolved_at=? where id=? and status='OPEN'",
                reason, actor, Timestamp.from(Instant.now()), reviewId);
        if (changed > 0) outbox.event(commandId, actor, "FAILURE_ACKNOWLEDGED", reason);
        return command(commandId, actor, role);
    }

    private Map<String,Object> review(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("select * from integration_reviews where id=?", id);
        if (rows.isEmpty()) throw BusinessException.of("NOT_FOUND", "Integration review not found");
        return rows.get(0);
    }
    private void requireOperator(UserRole role) {
        if (role != UserRole.SUPERVISOR && role != UserRole.ADMIN) throw BusinessException.of("FORBIDDEN", "Supervisor or admin required for integration operations");
    }
}
