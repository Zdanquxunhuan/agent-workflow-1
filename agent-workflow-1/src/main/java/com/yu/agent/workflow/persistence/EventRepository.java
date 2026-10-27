package com.yu.agent.workflow.persistence;

import com.yu.agent.workflow.domain.AuditEvent;

import java.util.List;

public interface EventRepository {
    void append(AuditEvent event);
    List<AuditEvent> findByTask(String taskId);
}
