package com.yu.agent.workflow.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.domain.AuditEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "jdbc")
public class JdbcAuditOutboxRepository implements AuditOutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.update("""
                insert into audit_outbox(id, event_id, task_id, event_type, payload_json, created_at)
                values (?, ?, ?, ?, ?::jsonb, ?)
                on conflict (event_id) do nothing
                """, event.eventId(), event.eventId(), event.taskId(), event.type(), json(event),
                Timestamp.from(event.occurredAt()));
    }

    @Override
    public List<AuditEvent> pending(int limit) {
        return jdbc.query("""
                select payload_json from audit_outbox
                where published_at is null
                order by created_at asc
                limit ?
                """, (rs, rowNum) -> readEvent(rs.getString("payload_json")), Math.max(1, limit));
    }

    @Override
    public void markPublished(String eventId, Instant publishedAt) {
        markPublished(eventId, null, publishedAt);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(transactionManager = "workflowTransactionManager")
    public List<AuditEvent> claimPending(String owner, int limit, Instant now, Duration lease) {
        Instant actualNow = now == null ? Instant.now() : now;
        Instant leaseUntil = actualNow.plus(lease == null ? Duration.ZERO : lease);
        List<AuditEvent> claimed = jdbc.query("""
                select event_id, payload_json from audit_outbox
                where published_at is null
                  and (next_attempt_at is null or next_attempt_at <= ?)
                  and (locked_until is null or locked_until <= ?)
                order by created_at asc
                for update skip locked
                limit ?
                """, (rs, rowNum) -> {
            String eventId = rs.getString("event_id");
            jdbc.update("update audit_outbox set locked_by = ?, locked_until = ? where event_id = ?",
                    owner, Timestamp.from(leaseUntil), eventId);
            return readEvent(rs.getString("payload_json"));
        }, Timestamp.from(actualNow), Timestamp.from(actualNow), Math.max(1, limit));
        return claimed;
    }

    @Override
    public void markPublished(String eventId, String owner, Instant publishedAt) {
        if (owner == null) {
            jdbc.update("update audit_outbox set published_at = ?, locked_by = null, locked_until = null where event_id = ? and published_at is null",
                    Timestamp.from(publishedAt == null ? Instant.now() : publishedAt), eventId);
        } else {
            jdbc.update("update audit_outbox set published_at = ?, locked_by = null, locked_until = null where event_id = ? and locked_by = ? and published_at is null",
                    Timestamp.from(publishedAt == null ? Instant.now() : publishedAt), eventId, owner);
        }
    }

    @Override
    public void markFailed(String eventId, String owner, Instant nextAttemptAt, String error) {
        jdbc.update("""
                update audit_outbox
                   set attempts = attempts + 1,
                       next_attempt_at = ?,
                       last_error = ?,
                       locked_by = null,
                       locked_until = null
                 where event_id = ? and locked_by = ? and published_at is null
                """, Timestamp.from(nextAttemptAt == null ? Instant.now() : nextAttemptAt), truncate(error), eventId, owner);
    }

    private String json(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize audit outbox event", ex);
        }
    }

    private AuditEvent readEvent(String value) {
        try {
            return objectMapper.readValue(value, AuditEvent.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not deserialize audit outbox event", ex);
        }
    }

    private String truncate(String error) {
        if (error == null) return null;
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
