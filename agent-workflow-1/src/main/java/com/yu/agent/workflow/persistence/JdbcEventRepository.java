package com.yu.agent.workflow.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.domain.AuditEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "jdbc")
public class JdbcEventRepository implements EventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.update("""
                insert ignore into audit_events(id, task_id, trace_id, event_type, actor, tool_name, status, message, metadata_json, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.taskId(), event.traceId(), event.type(), event.actor(), event.toolName(),
                event.status(), event.message(), json(event.metadata()), Timestamp.from(event.occurredAt()));
    }

    @Override
    public List<AuditEvent> findByTask(String taskId) {
        return jdbc.query("""
                select id, task_id, trace_id, event_type, actor, tool_name, status, message, metadata_json, occurred_at
                from audit_events where task_id = ? order by occurred_at asc
                """, (rs, rowNum) -> new AuditEvent(rs.getString("id"), rs.getString("task_id"),
                        rs.getString("trace_id"), rs.getString("event_type"), rs.getString("actor"),
                        rs.getString("tool_name"), rs.getString("status"), rs.getString("message"),
                        readMap(rs.getString("metadata_json")), rs.getTimestamp("occurred_at").toInstant()), taskId);
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not serialize audit metadata", ex); }
    }

    private Map<String, Object> readMap(String value) {
        try { return objectMapper.readValue(value, objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
        catch (Exception ex) { return Map.of(); }
    }
}
