package com.yu.agent.workflow.commerce;

import java.time.Instant;

public record ShipmentEvent(String status, String location, String description, Instant occurredAt) {
}
