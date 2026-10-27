package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;
import java.util.Set;
import java.util.Map;

public interface AgentTool {
    String name();
    boolean readOnly();
    Set<UserRole> allowedRoles();
    ToolResult execute(Map<String, Object> arguments, ToolContext context);
}
