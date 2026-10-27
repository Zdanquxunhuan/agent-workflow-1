package com.yu.agent.workflow.application;

import java.time.Duration;

/** A bounded retry policy. Permission, validation, business rejection, and
 * unknown-side-effect failures are intentionally never retryable. */
public final class RetryPolicy {
    public enum FailureClass { TRANSIENT, RATE_LIMITED, VALIDATION, PERMISSION, BUSINESS, UNKNOWN_SIDE_EFFECT }

    private final int maxAttempts;
    private final Duration backoff;

    public RetryPolicy(int maxAttempts, Duration backoff) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.backoff = backoff == null ? Duration.ZERO : backoff;
    }

    public boolean shouldRetry(FailureClass failureClass, int attempt) {
        return attempt < maxAttempts && (failureClass == FailureClass.TRANSIENT || failureClass == FailureClass.RATE_LIMITED);
    }

    public Duration delayFor(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 10);
        return backoff.multipliedBy(multiplier);
    }

    public int maxAttempts() { return maxAttempts; }
}
