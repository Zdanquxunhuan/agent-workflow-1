package com.yu.agent.workflow.persistence;

import com.yu.agent.workflow.domain.AuditEvent;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

/** Durable hand-off between task transactions and an external audit publisher. */
public interface AuditOutboxRepository {
    void append(AuditEvent event);

    List<AuditEvent> pending(int limit);

    void markPublished(String eventId, Instant publishedAt);

    List<AuditEvent> claimPending(String owner, int limit, Instant now, Duration lease);

    void markPublished(String eventId, String owner, Instant publishedAt);

    void markFailed(String eventId, String owner, Instant nextAttemptAt, String error);
}
