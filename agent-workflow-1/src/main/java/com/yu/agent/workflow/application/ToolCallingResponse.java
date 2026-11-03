package com.yu.agent.workflow.application;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public record ToolCallingResponse(String content, List<ToolCall> toolCalls, Map<String, Object> assistantMessage,
                                  Map<String, Object> metadata) {
    public ToolCallingResponse(String content, List<ToolCall> calls, Map<String, Object> message) {
        this(content, calls, message, Map.of());
    }
    public ToolCallingResponse {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        assistantMessage = assistantMessage == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(assistantMessage));
    }
}
