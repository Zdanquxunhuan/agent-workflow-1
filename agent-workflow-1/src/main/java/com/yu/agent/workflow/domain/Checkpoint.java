package com.yu.agent.workflow.domain;

import java.time.Instant;

public record Checkpoint(String taskId, int nextSequence, String lastStepId, String status, Instant capturedAt) {}
