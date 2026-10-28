package com.yu.agent.workflow.commerce;

import java.time.Instant;
import java.util.List;

public record ShipmentDetail(
        String shipmentId,
        String orderId,
        String carrier,
        String trackingNo,
        String status,
        Instant shippedAt,
        Instant deliveredAt,
        List<ShipmentEvent> events,
        boolean replayed) {
    public ShipmentDetail {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
