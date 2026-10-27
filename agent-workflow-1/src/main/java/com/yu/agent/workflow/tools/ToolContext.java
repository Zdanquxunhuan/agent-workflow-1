package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;

public record ToolContext(String userId, UserRole role, String taskId, String traceId) {
}
