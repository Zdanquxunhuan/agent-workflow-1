package com.yu.agent.workflow.domain;

import com.yu.agent.workflow.api.BusinessException;

import java.util.Map;
import java.util.Set;

/** Single source of truth for legal task transitions. */
public final class TaskStateMachine {
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(TaskStatus.CREATED, Set.of(TaskStatus.PLANNING, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.PLANNING, Set.of(TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL, TaskStatus.FAILED, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.WAITING_APPROVAL, Set.of(TaskStatus.RUNNING, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.RUNNING, Set.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.RECOVERY_REQUIRED, TaskStatus.WAITING_EXTERNAL)),
            Map.entry(TaskStatus.WAITING_EXTERNAL, Set.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.UNKNOWN_SIDE_EFFECT)),
            Map.entry(TaskStatus.UNKNOWN_SIDE_EFFECT, Set.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.WAITING_EXTERNAL)),
            Map.entry(TaskStatus.RECOVERY_REQUIRED, Set.of(TaskStatus.RUNNING, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.SUCCEEDED, Set.of()),
            Map.entry(TaskStatus.FAILED, Set.of()),
            Map.entry(TaskStatus.CANCELLED, Set.of()));

    private TaskStateMachine() {}

    public static void requireAllowed(TaskStatus from, TaskStatus to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw BusinessException.of("INVALID_STATE", "Illegal transition " + from + " -> " + to);
        }
    }

    public static Set<TaskStatus> allowedFrom(TaskStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }
}
