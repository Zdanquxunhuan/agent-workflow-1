package com.yu.agent.workflow.persistence;

import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.TaskStatus;

import java.util.List;

public interface TaskRepository {
    AgentTask save(AgentTask task);
    /** Atomically inserts a new task; returns the existing task when the idempotency key is already bound. */
    AgentTask create(AgentTask task);
    AgentTask require(String id);
    AgentTask byIdempotency(String userId, String key);
    List<AgentTask> findByStatus(TaskStatus status);
}
