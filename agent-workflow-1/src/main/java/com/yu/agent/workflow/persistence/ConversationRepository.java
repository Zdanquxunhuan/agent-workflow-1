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

/**
 * 会话持久化。
 *
 * <p>整条会话以 JSON 快照保存，revision 是乐观锁版本：save 必须携带 previous，
 * 只有数据库中的 revision 仍等于 previous.revision 时更新才会成功。
 *
 * <p>这样两个实例或两个标签页同时写时，只有一个能提交，另一个收到 CONFLICT；
 * 迟到的模型答案也不会覆盖用户已经做出的取消/删除。
 */
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
    /**
     * 读取会话；已删除、已过期、租户不匹配都统一返回 NOT_FOUND，
     * 不暴露“存在但不属于你”这种信息。
     */
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
    /**
     * CAS 保存：先做本地一致性检查，再用 {@code WHERE revision=?} 做跨实例乐观锁。
     *
     * <p>更新行数为 0 说明数据库里的会话已经被别人改过（或过期），调用方应重新加载后重试。
     */
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
    /** 只返回本人拥有或作为参与者被邀请的会话；SQL 粗筛后再在内存中做一次精确校验。 */
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
