package com.yu.agent.workflow.tools;

import java.util.Map;

public record ToolResult(boolean success, String code, String message, Map<String, Object> data) {
    public ToolResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ToolResult ok(String message, Map<String, Object> data) {
        return new ToolResult(true, "OK", message, data);
    }

    public static ToolResult error(String code, String message) {
        return new ToolResult(false, code, message, Map.of());
    }
}
