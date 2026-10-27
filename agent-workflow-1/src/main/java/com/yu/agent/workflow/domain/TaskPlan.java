package com.yu.agent.workflow.domain;

import java.util.List;

public record TaskPlan(List<PlanStep> steps) {
    public TaskPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean requiresApproval() {
        return steps.stream().anyMatch(PlanStep::requiresApproval);
    }
}
