package com.yu.agent.workflow.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Immutable snapshot. The repository revision fences every mutation, including late model results. */
public record Conversation(String id, String tenantId, String owner, Set<String> participants,
                           long revision, Instant expiresAt, boolean deleted, List<Turn> turns,
                           Summary summary) {
    public Conversation { participants = Set.copyOf(participants); turns = List.copyOf(turns); }
    public record Turn(String key, String message, String taskId, String status, String answer,
                       int rounds, List<String> tools, String errorCode) {
        public Turn { tools = List.copyOf(tools); }
    }
    /** Extractive topic hints only, never cached business facts. Watermark counts transcript turns. */
    public record Summary(long version, int throughTurn, String text, List<String> sourceTasks) {
        public Summary { sourceTasks = List.copyOf(sourceTasks); }
    }
    public Conversation next(Set<String> readers, List<Turn> messages, Summary compacted, boolean removed) {
        return new Conversation(id, tenantId, owner, readers, revision + 1, expiresAt, removed, messages, compacted);
    }
}
