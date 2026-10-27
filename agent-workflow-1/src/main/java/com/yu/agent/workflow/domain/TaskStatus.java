package com.yu.agent.workflow.domain;

public enum TaskStatus {
    CREATED,
    PLANNING,
    RUNNING,
    WAITING_APPROVAL,
    RECOVERY_REQUIRED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
