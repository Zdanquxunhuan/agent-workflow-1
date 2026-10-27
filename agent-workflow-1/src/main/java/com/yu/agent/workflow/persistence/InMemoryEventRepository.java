package com.yu.agent.workflow.persistence;

import com.yu.agent.workflow.domain.AuditEvent;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@ConditionalOnProperty(name = "agent.workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventRepository implements EventRepository {
    private final Map<String, List<AuditEvent>> events = new ConcurrentHashMap<>();
    public void append(AuditEvent event) { events.computeIfAbsent(event.taskId(), ignored -> new CopyOnWriteArrayList<>()).add(event); }
    public List<AuditEvent> findByTask(String taskId) {
        return events.getOrDefault(taskId, List.of()).stream().sorted(Comparator.comparing(AuditEvent::occurredAt)).toList();
    }
}
