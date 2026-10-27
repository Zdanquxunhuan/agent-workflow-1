package com.yu.agent.workflow.domain;

import com.yu.agent.workflow.api.BusinessException;

import java.util.Map;
import java.util.Set;

/** Single source of truth for legal task transitions. */
public final class TaskStateMachine {
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.of(
            TaskStatus.CREATED, Set.of(TaskStatus.PLANNING, TaskStatus.CANCELLED),
            TaskStatus.PLANNING, Set.of(TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL, TaskStatus.FAILED, TaskStatus.CANCELLED),
            TaskStatus.WAITING_APPROVAL, Set.of(TaskStatus.RUNNING, TaskStatus.CANCELLED),
            TaskStatus.RUNNING, Set.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.RECOVERY_REQUIRED),
            TaskStatus.RECOVERY_REQUIRED, Set.of(TaskStatus.RUNNING, TaskStatus.CANCELLED),
            TaskStatus.SUCCEEDED, Set.of(),
            TaskStatus.FAILED, Set.of(),
            TaskStatus.CANCELLED, Set.of());

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
