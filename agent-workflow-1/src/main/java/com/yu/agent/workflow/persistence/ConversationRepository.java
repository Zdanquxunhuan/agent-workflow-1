package com.yu.agent.workflow.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.Conversation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class ConversationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, Conversation> memory = new ConcurrentHashMap<>();
    public ConversationRepository(ObjectProvider<JdbcTemplate> provider, ObjectMapper json) {
        this.jdbc = provider.getIfAvailable(); this.json = json;
    }
    public void create(Conversation value) {
        if (jdbc == null) memory.put(value.id(), value);
        else jdbc.update("INSERT INTO conversations(id,tenant_id,owner_id,revision,expires_at,state_json) VALUES (?,?,?,?,?,?)",
                value.id(), value.tenantId(), value.owner(), value.revision(), value.expiresAt().getEpochSecond(), encode(value));
    }
    public Conversation require(String id, String tenant) {
        Conversation value;
        if (jdbc == null) value = memory.get(id);
        else {
            var rows = jdbc.queryForList("SELECT state_json FROM conversations WHERE id=? AND tenant_id=?", String.class, id, tenant);
            value = rows.isEmpty() ? null : decode(rows.get(0));
        }
        if (value == null || !value.tenantId().equals(tenant) || value.deleted() || !value.expiresAt().isAfter(Instant.now()))
            throw BusinessException.of("NOT_FOUND", "Conversation does not exist or has expired");
        return value;
    }
    public void save(Conversation previous, Conversation next) {
        if (!previous.expiresAt().isAfter(Instant.now())) throw BusinessException.of("NOT_FOUND", "Conversation expired");
        if (next.revision() != previous.revision() + 1 || !previous.id().equals(next.id())
                || !previous.owner().equals(next.owner()) || !previous.tenantId().equals(next.tenantId()))
            throw new IllegalArgumentException("Invalid conversation revision");
        boolean changed = jdbc == null ? memory.replace(previous.id(), previous, next)
                : jdbc.update("UPDATE conversations SET revision=?,state_json=? WHERE id=? AND tenant_id=? AND revision=? AND expires_at>?",
                    next.revision(), encode(next), previous.id(), previous.tenantId(), previous.revision(), Instant.now().getEpochSecond()) == 1;
        if (!changed) throw BusinessException.of("CONFLICT", "Conversation changed; reload before retrying");
    }
    public List<Conversation> list(String tenant, String actor) {
        List<Conversation> values = jdbc == null ? List.copyOf(memory.values()) : jdbc.queryForList(
                "SELECT state_json FROM conversations WHERE tenant_id=? AND expires_at>? AND JSON_EXTRACT(state_json,'$.deleted')=false AND (owner_id=? OR JSON_CONTAINS(state_json,JSON_QUOTE(?),'$.participants')) ORDER BY expires_at DESC LIMIT 50",
                String.class, tenant, Instant.now().getEpochSecond(), actor, actor).stream().map(this::decode).toList();
        return values.stream().filter(v -> v.tenantId().equals(tenant) && !v.deleted() && v.expiresAt().isAfter(Instant.now())
                && (v.owner().equals(actor) || v.participants().contains(actor))).limit(50).toList();
    }
    private String encode(Conversation value) {
        try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("Conversation serialization failed", ex); }
    }
    /** Transcript retention does not erase the separate business/audit records. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${agent.conversation.cleanup-ms:60000}")
    public void purgeExpired() {
        long now = Instant.now().getEpochSecond();
        if (jdbc == null) memory.entrySet().removeIf(e -> e.getValue().expiresAt().getEpochSecond() <= now);
        else jdbc.update("DELETE FROM conversations WHERE expires_at<=? LIMIT 100", now);
    }
    private Conversation decode(String value) {
        try { return json.readValue(value, Conversation.class); } catch (Exception ex) { throw new IllegalStateException("Conversation storage is invalid", ex); }
    }
}
