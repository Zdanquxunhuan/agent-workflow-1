package com.yu.agent.workflow.persistence;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.TaskStatus;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskRepository implements TaskRepository {
    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();

    public AgentTask save(AgentTask task) { tasks.put(task.id(), task); return task; }
    public AgentTask require(String id) { return Optional.ofNullable(tasks.get(id)).orElseThrow(() -> BusinessException.of("TASK_NOT_FOUND", "Task does not exist: " + id)); }
    public AgentTask byIdempotency(String userId, String key) {
        String id = idempotency.get(userId + "::" + key);
        return id == null ? null : tasks.get(id);
    }
    public void bindIdempotency(AgentTask task) { if (task.idempotencyKey() != null) idempotency.put(task.userId() + "::" + task.idempotencyKey(), task.id()); }
    public List<AgentTask> findByStatus(TaskStatus status) {
        return tasks.values().stream().filter(task -> task.status() == status).toList();
    }
}
