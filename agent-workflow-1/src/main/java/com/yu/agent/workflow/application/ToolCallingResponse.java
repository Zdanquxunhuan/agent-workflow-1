package com.yu.agent.workflow.application;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public record ToolCallingResponse(String content, List<ToolCall> toolCalls, Map<String, Object> assistantMessage) {
    public ToolCallingResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        assistantMessage = assistantMessage == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(assistantMessage));
    }
}
