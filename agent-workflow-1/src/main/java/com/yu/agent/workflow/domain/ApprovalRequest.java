package com.yu.agent.workflow.domain;

import java.time.Instant;

public record ApprovalRequest(
        String approvalId,
        String taskId,
        String requestedBy,
        String decidedBy,
        String decision,
        String reason,
        Instant requestedAt,
        Instant decidedAt) {
}
