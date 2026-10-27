package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Safe local sink for development; production should replace it with SIEM/Kafka. */
@Component
@ConditionalOnMissingBean(AuditEventSink.class)
public class LoggingAuditEventSink implements AuditEventSink {
    private static final Logger log = LoggerFactory.getLogger(LoggingAuditEventSink.class);

    @Override
    public void publish(AuditEvent event) {
        log.info("audit-outbox-published eventId={} taskId={} type={}", event.eventId(), event.taskId(), event.type());
    }
}
