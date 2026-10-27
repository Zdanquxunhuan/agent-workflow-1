package com.yu.agent.workflow.domain;

import java.util.Map;

public record PlanStep(int sequence, String toolName, Map<String, Object> arguments, boolean requiresApproval) {
    public PlanStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
