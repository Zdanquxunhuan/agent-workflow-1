package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.AuditEvent;

@FunctionalInterface
public interface AuditEventSink {
    void publish(AuditEvent event);
}
