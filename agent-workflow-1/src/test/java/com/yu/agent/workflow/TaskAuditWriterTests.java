package com.yu.agent.workflow;

import com.yu.agent.workflow.application.TaskAuditWriter;
import com.yu.agent.workflow.domain.AgentTask;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.persistence.InMemoryAuditOutboxRepository;
import com.yu.agent.workflow.persistence.InMemoryEventRepository;
import com.yu.agent.workflow.persistence.InMemoryTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskAuditWriterTests {
    @Test
    void taskEventAndOutboxRecordAreWrittenTogether() {
        var tasks = new InMemoryTaskRepository();
        var events = new InMemoryEventRepository();
        var outbox = new InMemoryAuditOutboxRepository();
        var writer = new TaskAuditWriter(tasks, events, outbox);
        var task = new AgentTask("task-outbox", "alice", UserRole.CUSTOMER_SERVICE,
                "查询订单 ORD-1001", null, Instant.now());
        var event = new AuditEvent("event-outbox", task.id(), "trace-outbox", "TASK_CREATED",
                "alice", null, "RECORDED", "created", Map.of(), Instant.now());

        writer.write(task, event);

        assertThat(tasks.require(task.id()).view().id()).isEqualTo(task.id());
        assertThat(events.findByTask(task.id())).containsExactly(event);
        assertThat(outbox.pending(10)).containsExactly(event);
        outbox.markPublished(event.eventId(), Instant.now());
        assertThat(outbox.pending(10)).isEmpty();
    }
}
