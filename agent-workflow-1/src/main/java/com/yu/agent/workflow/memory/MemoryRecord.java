package com.yu.agent.workflow.memory;

public record MemoryRecord(String id, String tenantId, String ownerId, String kind, String slot,
                           String content, String sourceTaskId, long revision, String state,
                           long expiresAt, long createdAt, long updatedAt) {
    public boolean active(long now) { return "ACTIVE".equals(state) && expiresAt > now; }
}
