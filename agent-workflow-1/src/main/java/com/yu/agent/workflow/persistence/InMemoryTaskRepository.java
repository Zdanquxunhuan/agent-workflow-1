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
    public synchronized AgentTask create(AgentTask task) {
        String key = idempotencyKey(task);
        if (key != null) {
            String existingId = idempotency.get(key);
            if (existingId != null) return tasks.get(existingId);
        }
        tasks.put(task.id(), task);
        if (key != null) idempotency.put(key, task.id());
        return task;
    }
    public AgentTask require(String id) { return Optional.ofNullable(tasks.get(id)).orElseThrow(() -> BusinessException.of("TASK_NOT_FOUND", "Task does not exist: " + id)); }
    public AgentTask byIdempotency(String userId, String key) {
        String id = idempotency.get(idempotencyKey(userId, key));
        return id == null ? null : tasks.get(id);
    }
    public List<AgentTask> findByStatus(TaskStatus status) {
        return tasks.values().stream().filter(task -> task.status() == status).toList();
    }

    private String idempotencyKey(AgentTask task) {
        return task.idempotencyKey() == null || task.idempotencyKey().isBlank()
                ? null : idempotencyKey(task.userId(), task.idempotencyKey());
    }

    private String idempotencyKey(String userId, String key) {
        return userId + "::" + key;
    }
}
