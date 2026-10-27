package com.yu.agent.workflow.persistence;

import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.TaskStatus;

import java.util.List;

public interface TaskRepository {
    AgentTask save(AgentTask task);
    AgentTask require(String id);
    AgentTask byIdempotency(String userId, String key);
    void bindIdempotency(AgentTask task);
    List<AgentTask> findByStatus(TaskStatus status);
}
