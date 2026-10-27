package com.yu.agent.workflow.persistence;

import com.yu.agent.workflow.domain.AuditEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAuditOutboxRepository implements AuditOutboxRepository {
    private final Map<String, Entry> events = new ConcurrentHashMap<>();

    @Override
    public void append(AuditEvent event) {
        events.putIfAbsent(event.eventId(), new Entry(event));
    }

    @Override
    public List<AuditEvent> pending(int limit) {
        int boundedLimit = Math.max(1, limit);
        return events.values().stream()
                .filter(entry -> entry.publishedAt == null)
                .map(entry -> entry.event)
                .sorted(Comparator.comparing(AuditEvent::occurredAt))
                .limit(boundedLimit)
                .toList();
    }

    @Override
    public void markPublished(String eventId, Instant published) {
        markPublished(eventId, null, published);
    }

    @Override
    public synchronized List<AuditEvent> claimPending(String owner, int limit, Instant now, Duration lease) {
        Instant actualNow = now == null ? Instant.now() : now;
        Instant lockedUntil = actualNow.plus(lease == null ? Duration.ZERO : lease);
        List<AuditEvent> claimed = new ArrayList<>();
        events.values().stream()
                .filter(entry -> entry.publishedAt == null)
                .filter(entry -> entry.nextAttemptAt == null || !entry.nextAttemptAt.isAfter(actualNow))
                .filter(entry -> entry.lockedUntil == null || !entry.lockedUntil.isAfter(actualNow))
                .sorted(Comparator.comparing(entry -> entry.event.occurredAt()))
                .limit(Math.max(1, limit))
                .forEach(entry -> {
                    entry.lockedBy = owner;
                    entry.lockedUntil = lockedUntil;
                    claimed.add(entry.event);
                });
        return List.copyOf(claimed);
    }

    @Override
    public synchronized void markPublished(String eventId, String owner, Instant published) {
        Entry entry = events.get(eventId);
        if (entry != null && (owner == null || owner.equals(entry.lockedBy))) {
            entry.publishedAt = published == null ? Instant.now() : published;
            entry.lockedBy = null;
            entry.lockedUntil = null;
        }
    }

    @Override
    public synchronized void markFailed(String eventId, String owner, Instant nextAttemptAt, String error) {
        Entry entry = events.get(eventId);
        if (entry != null && (owner == null || owner.equals(entry.lockedBy))) {
            entry.nextAttemptAt = nextAttemptAt;
            entry.lastError = error;
            entry.attempts++;
            entry.lockedBy = null;
            entry.lockedUntil = null;
        }
    }

    private static final class Entry {
        private final AuditEvent event;
        private Instant publishedAt;
        private Instant nextAttemptAt;
        private Instant lockedUntil;
        private String lockedBy;
        private String lastError;
        private int attempts;

        private Entry(AuditEvent event) { this.event = event; }
    }
}
