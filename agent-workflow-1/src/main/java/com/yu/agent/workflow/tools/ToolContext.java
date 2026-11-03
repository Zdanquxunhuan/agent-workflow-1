package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;

public record ToolContext(String userId, UserRole role, String taskId, String traceId, String tenantId) {
    public ToolContext(String userId, UserRole role, String taskId, String traceId) {
        this(userId, role, taskId, traceId, com.yu.agent.workflow.security.TenantIdentity.current());
    }
}
