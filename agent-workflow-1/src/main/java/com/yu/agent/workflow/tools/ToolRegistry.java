package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(Collection<AgentTool> registeredTools) {
        registeredTools.forEach(tool -> {
            if (tools.put(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate tool name: " + tool.name());
            }
        });
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw BusinessException.of("UNKNOWN_TOOL", "Tool is not registered: " + name);
        }
        return tool;
    }

    public ToolResult execute(String name, Map<String, Object> arguments, ToolContext context) {
        AgentTool tool = require(name);
        UserRole role = context.role();
        if (!tool.allowedRoles().contains(role) && role != UserRole.ADMIN) {
            throw BusinessException.of("FORBIDDEN", "Role is not allowed to execute tool: " + name);
        }
        return tool.execute(arguments, context);
    }

    public boolean contains(String name) { return tools.containsKey(name); }
    public Collection<AgentTool> all() { return tools.values(); }
    public Collection<AgentTool> readOnlyTools() { return tools.values().stream().filter(AgentTool::readOnly).toList(); }
}
