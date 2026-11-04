package com.yu.agent.workflow.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.persistence.TaskRepository;
import com.yu.agent.workflow.security.TenantIdentity;
import com.yu.agent.workflow.tools.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local-development delegation broker; production token exchange is deliberately fail-closed. */
@Service
public class RagTaskAccess {
    public record Grant(String tenant, String subject, List<String> kbIds, long expiresAt, boolean revoked) {}
    public record Source(String chunkId, String versionId, String hash, long revision) {}
    private final TaskRepository tasks;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Map<String, List<Source>> sources = new ConcurrentHashMap<>();
    private final Map<String, List<String>> dependencies = new ConcurrentHashMap<>();
    @Value("${agent.knowledge.task-enabled:false}") private boolean enabled;
    @Value("${agent.auth.mode:local}") private String authMode = "local";
    @Value("${agent.knowledge.delegation.local-secret:}") private String secret = "";
    @Value("${agent.knowledge.delegation.issuer:rag-local}") private String issuer = "rag-local";
    @Value("${agent.knowledge.http.audience:rag-api}") private String audience = "rag-api";

    public RagTaskAccess(TaskRepository tasks, ObjectMapper json, ObjectProvider<JdbcTemplate> jdbc) {
        this.tasks = tasks; this.json = json; this.jdbc = jdbc.getIfAvailable();
    }

    public String token(ToolContext context) {
        if (!enabled) throw BusinessException.of("RAG_TASK_TENANCY_REQUIRED", "Task knowledge integration is disabled");
        var task = tasks.require(context.taskId());
        if (!task.tenantId().equals(context.tenantId())) denied();
        Grant grant = grant(task.id());
        if (grant != null && (grant.revoked() || grant.expiresAt() <= Instant.now().getEpochSecond())) denied();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken auth && auth.isAuthenticated()
                && task.userId().equals(auth.getToken().getSubject())
                && task.tenantId().equals(auth.getToken().getClaimAsString("tenant_id"))
                && auth.getToken().getAudience().contains(audience)
                && auth.getToken().getExpiresAt() != null && auth.getToken().getExpiresAt().isAfter(Instant.now()))
            return auth.getToken().getTokenValue();
        if (grant == null || !grant.tenant().equals(task.tenantId()) || !grant.subject().equals(task.userId())
                || !"local".equals(authMode) || secret.length() < 32) denied();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(grant.subject()).audience(List.of(audience))
                .issuedAt(now).expiresAt(now.plusSeconds(60)).claim("tenant_id", grant.tenant())
                .claim("delegated_task", task.id()).build();
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /** Caller already authorized the task/conversation; RAG must evaluate the current reader's ACL. */
    public void validateRead(ToolContext context) {
        if (!enabled) throw BusinessException.of("RAG_TASK_TENANCY_REQUIRED", "Task knowledge integration is disabled");
        var task = tasks.require(context.taskId());
        if (!task.tenantId().equals(context.tenantId())) denied();
        Grant grant = grant(task.id());
        if (grant != null && (grant.revoked() || grant.expiresAt() <= Instant.now().getEpochSecond())) denied();
    }

    public synchronized List<String> scope(ToolContext context, List<String> accessible) {
        Grant existing = grant(context.taskId());
        if (existing != null) {
            if (existing.revoked() || existing.expiresAt() <= Instant.now().getEpochSecond()) denied();
            return existing.kbIds().stream().filter(accessible::contains).toList();
        }
        var task = tasks.require(context.taskId());
        Grant created = new Grant(task.tenantId(), task.userId(), List.copyOf(accessible),
                Instant.now().plusSeconds(3600).getEpochSecond(), false);
        if (jdbc == null) grants.putIfAbsent(task.id(), created);
        else jdbc.update("INSERT IGNORE INTO task_knowledge_grants(task_id,grant_json) VALUES (?,?)", task.id(), encode(created));
        // A concurrent request may have frozen a narrower scope.
        return grant(task.id()).kbIds().stream().filter(accessible::contains).toList();
    }

    public Grant grant(String taskId) {
        if (jdbc == null) return grants.get(taskId);
        var rows = jdbc.queryForList("SELECT grant_json FROM task_knowledge_grants WHERE task_id=?", String.class, taskId);
        return rows.isEmpty() ? null : decode(rows.get(0), Grant.class);
    }
    public void renew(ToolContext context, List<String> accessible) {
        if (!enabled) denied();
        var task=tasks.require(context.taskId());
        if(!task.tenantId().equals(context.tenantId()) || !task.userId().equals(context.userId())) denied();
        Grant old=grant(task.id());
        if(old==null || old.revoked() || !accessible.containsAll(old.kbIds())) denied();
        Grant next=new Grant(old.tenant(),old.subject(),old.kbIds(),Instant.now().plusSeconds(3600).getEpochSecond(),false);
        boolean changed=jdbc==null ? grants.replace(task.id(),old,next)
                : jdbc.update("UPDATE task_knowledge_grants SET grant_json=? WHERE task_id=? AND grant_json=CAST(? AS JSON)",encode(next),task.id(),encode(old))==1;
        if(!changed) throw BusinessException.of("CONFLICT","Knowledge grant changed during renewal");
    }

    public void revoke(String taskId, String actor) {
        var task = tasks.require(taskId);
        TenantIdentity.require(task.tenantId());
        if (!task.userId().equals(actor)) denied();
        Grant old = grant(taskId);
        Grant revoked = new Grant(task.tenantId(), task.userId(), old == null ? List.of() : old.kbIds(), 0, true);
        if (jdbc == null) grants.put(taskId, revoked);
        else jdbc.update("INSERT INTO task_knowledge_grants(task_id,grant_json) VALUES (?,?) ON DUPLICATE KEY UPDATE grant_json=VALUES(grant_json)", taskId, encode(revoked));
    }

    public synchronized void remember(String taskId, List<Source> added) {
        if (jdbc == null) {
            List<Source> all = new ArrayList<>(sources.getOrDefault(taskId, List.of()));
            all.addAll(added); sources.put(taskId, List.copyOf(new LinkedHashSet<>(all)));
        } else for (Source source : added) {
            jdbc.update("INSERT IGNORE INTO task_knowledge_sources(task_id,chunk_id,version_id,content_hash,content_revision) VALUES (?,?,?,?,?)",
                    taskId, source.chunkId(), source.versionId(), source.hash(), source.revision());
        }
    }

    public List<Source> sources(String taskId) {
        if (jdbc == null) return sources.getOrDefault(taskId, List.of());
        return jdbc.query("SELECT * FROM task_knowledge_sources WHERE task_id=?", (r,n) ->
                new Source(r.getString("chunk_id"), r.getString("version_id"), r.getString("content_hash"), r.getLong("content_revision")), taskId);
    }
    public void inherit(ToolContext context, List<String> sourceTasks) {
        var target = tasks.require(context.taskId());
        if (!target.tenantId().equals(context.tenantId()) || !target.userId().equals(context.userId())) denied();
        if (sourceTasks.size() > 100) denied();
        for (String id : new LinkedHashSet<>(sourceTasks)) {
            var source = tasks.require(id);
            if (id.equals(target.id()) || !source.tenantId().equals(target.tenantId())
                    || !source.userId().equals(target.userId())) denied();
            if (jdbc != null) jdbc.update("INSERT IGNORE INTO task_knowledge_dependencies(task_id,source_task_id) VALUES (?,?)", target.id(), id);
        }
        if (jdbc == null) dependencies.put(target.id(), List.copyOf(new LinkedHashSet<>(sourceTasks)));
    }
    public List<String> dependencies(String taskId) {
        if (jdbc == null) return dependencies.getOrDefault(taskId, List.of());
        return jdbc.queryForList("SELECT source_task_id FROM task_knowledge_dependencies WHERE task_id=?", String.class, taskId);
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private <T> T decode(String value, Class<T> type) { try { return json.readValue(value, type); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private void denied() { throw BusinessException.of("RAG_IDENTITY_REQUIRED", "Valid task knowledge delegation is required"); }
}
