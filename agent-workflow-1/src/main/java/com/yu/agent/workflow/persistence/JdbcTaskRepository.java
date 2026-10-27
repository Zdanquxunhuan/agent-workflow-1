package com.yu.agent.workflow.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.AgentStep;
import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.AgentTaskView;
import com.yu.agent.workflow.domain.ApprovalRequest;
import com.yu.agent.workflow.domain.PlanStep;
import com.yu.agent.workflow.domain.TaskPlan;
import com.yu.agent.workflow.domain.TaskStatus;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "jdbc")
public class JdbcTaskRepository implements TaskRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcTaskRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AgentTask save(AgentTask task) {
        AgentTaskView view = task.view();
        jdbc.update("""
                insert into tasks(id, user_id, role, request, status, idempotency_key, created_at, updated_at, result_message, side_effect_count, plan_json, approval_json)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                on conflict (id) do update set user_id=excluded.user_id, role=excluded.role, request=excluded.request,
                  status=excluded.status, idempotency_key=excluded.idempotency_key, updated_at=excluded.updated_at,
                  result_message=excluded.result_message, side_effect_count=excluded.side_effect_count,
                  plan_json=excluded.plan_json, approval_json=excluded.approval_json
                """, view.id(), view.userId(), view.role().name(), view.request(), view.status().name(), view.idempotencyKey(),
                Timestamp.from(view.createdAt()), Timestamp.from(view.updatedAt()), view.resultMessage(), view.sideEffectCount(),
                json(view.plan()), json(view.approval()));
        jdbc.update("delete from task_steps where task_id = ?", view.id());
        for (AgentStep step : view.steps()) {
            jdbc.update("""
                    insert into task_steps(id, task_id, sequence_no, tool_name, arguments_json, status, result_code, result_message, started_at, finished_at)
                    values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    """, step.stepId(), view.id(), step.sequence(), step.toolName(), json(step.arguments()), step.status(),
                    step.resultCode(), step.resultMessage(), Timestamp.from(step.startedAt()),
                    step.finishedAt() == null ? null : Timestamp.from(step.finishedAt()));
        }
        return task;
    }

    @Override
    public AgentTask require(String id) {
        List<AgentTaskView> found = jdbc.query("""
                select id, user_id, role, request, status, idempotency_key, created_at, updated_at, result_message, side_effect_count, plan_json, approval_json
                from tasks where id = ?
                for update
                """, (rs, rowNum) -> {
            String taskId = rs.getString("id");
            return new AgentTaskView(taskId, rs.getString("user_id"), UserRole.valueOf(rs.getString("role")),
                    rs.getString("request"), rs.getString("idempotency_key"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(), TaskStatus.valueOf(rs.getString("status")),
                    read(rs.getString("plan_json"), TaskPlan.class),
                    loadSteps(taskId), read(rs.getString("approval_json"), ApprovalRequest.class),
                    rs.getString("result_message"), rs.getInt("side_effect_count"));
        }, id);
        if (found.isEmpty()) throw BusinessException.of("TASK_NOT_FOUND", "Task does not exist: " + id);
        return AgentTask.restore(found.get(0));
    }

    @Override
    public AgentTask byIdempotency(String userId, String key) {
        if (key == null || key.isBlank()) return null;
        List<String> ids = jdbc.query("select id from tasks where user_id = ? and idempotency_key = ?",
                (rs, rowNum) -> rs.getString(1), userId, key);
        return ids.isEmpty() ? null : require(ids.get(0));
    }

    @Override
    public void bindIdempotency(AgentTask task) {
        // The unique (user_id, idempotency_key) constraint is the binding.
    }

    @Override
    public List<AgentTask> findByStatus(TaskStatus status) {
        return jdbc.query("select id from tasks where status = ? order by created_at asc",
                (rs, rowNum) -> require(rs.getString(1)), status.name());
    }

    private List<AgentStep> loadSteps(String taskId) {
        return jdbc.query("""
                select id, sequence_no, tool_name, arguments_json, status, result_code, result_message, started_at, finished_at
                from task_steps where task_id = ? order by sequence_no asc
                """, (rs, rowNum) -> new AgentStep(rs.getString("id"), rs.getInt("sequence_no"), rs.getString("tool_name"),
                readMap(rs.getString("arguments_json")), rs.getString("status"), rs.getString("result_code"),
                rs.getString("result_message"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant()), taskId);
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not serialize task state", ex); }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readValue(value, type); }
        catch (Exception ex) { throw new IllegalStateException("Could not deserialize task state", ex); }
    }

    private Map<String, Object> readMap(String value) {
        try {
            JavaType type = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
            return objectMapper.readValue(value, type);
        } catch (Exception ex) { return Map.of(); }
    }
}
