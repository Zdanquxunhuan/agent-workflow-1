package com.yu.agent.workflow;

import com.yu.agent.workflow.application.AuditEventSink;
import com.yu.agent.workflow.application.AuditOutboxPublisher;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.persistence.InMemoryAuditOutboxRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuditOutboxPublisherTests {
    @Test
    void publisherClaimsAndMarksEvents() {
        var outbox = new InMemoryAuditOutboxRepository();
        var published = new AtomicInteger();
        AuditEvent event = event("event-publish");
        outbox.append(event);
        AuditEventSink sink = ignored -> published.incrementAndGet();
        var publisher = new AuditOutboxPublisher(outbox, sink);

        assertThat(publisher.publishOnce()).isEqualTo(1);
        assertThat(published).hasValue(1);
        assertThat(outbox.pending(10)).isEmpty();
    }

    @Test
    void failedPublicationIsLeftForRetry() {
        var outbox = new InMemoryAuditOutboxRepository();
        AuditEvent event = event("event-fail");
        outbox.append(event);
        var publisher = new AuditOutboxPublisher(outbox, ignored -> { throw new IllegalStateException("sink down"); });

        assertThat(publisher.publishOnce()).isZero();
        assertThat(outbox.pending(10)).containsExactly(event);
    }

    private AuditEvent event(String id) {
        return new AuditEvent(id, "task-outbox", "trace-outbox", "TASK_CREATED",
                "alice", null, "RECORDED", "created", Map.of(), Instant.now());
    }
}
