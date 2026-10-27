package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.AgentStep;
import com.yu.agent.workflow.domain.AgentTaskView;
import org.springframework.stereotype.Component;

import java.util.List;

/** Builds a bounded, task-local context. It never reads another task's state. */
@Component
public class ContextBuilder {
    private static final int MAX_REQUEST_CHARS = 2000;
    private static final int MAX_STEPS = 8;

    public TaskContextSnapshot build(AgentTaskView task) {
        String request = task.request() == null ? "" : task.request();
        if (request.length() > MAX_REQUEST_CHARS) request = request.substring(0, MAX_REQUEST_CHARS);
        List<AgentStep> recentSteps = task.steps().size() <= MAX_STEPS
                ? List.copyOf(task.steps())
                : List.copyOf(task.steps().subList(task.steps().size() - MAX_STEPS, task.steps().size()));
        return new TaskContextSnapshot(task.id(), request, task.status().name(), recentSteps);
    }

    public record TaskContextSnapshot(String taskId, String request, String status, List<AgentStep> recentSteps) {
        public TaskContextSnapshot {
            recentSteps = List.copyOf(recentSteps);
        }
    }
}
