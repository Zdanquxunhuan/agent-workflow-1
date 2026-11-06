package com.yu.agent.workflow.runtime;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.*;
import com.yu.agent.workflow.security.*;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

@Service
@ConditionalOnProperty(name = "agent.runtime.enabled", havingValue = "true")
public class RuntimeService {
    public record Request(@NotBlank @Size(max = 2000) String message,
                          @Pattern(regexp = "[a-fA-F0-9-]{36}") String conversationId, @PositiveOrZero long revision) {
    }

    private final RuntimeLedger ledger;
    private final ConversationService conversations;
    private final ChatAuditService audit;
    private final DeepSeekToolCallingService chat;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<DialogueSettings> dialogueSettings;
    @org.springframework.beans.factory.annotation.Autowired
    private PromptInjectionGuard injectionGuard;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread t = new Thread(runnable, "agent-runtime-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore synchronous = new Semaphore(4);

    public RuntimeService(RuntimeLedger ledger, ConversationService conversations, ChatAuditService audit, DeepSeekToolCallingService chat) {
        this.ledger = ledger;
        this.conversations = conversations;
        this.audit = audit;
        this.chat = chat;
    }

    private JwtAuthenticationToken identity(AuthenticatedUser user) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || !user.userId().equals(jwt.getToken().getSubject()) || !user.tenantId().equals(jwt.getToken().getClaimAsString("tenant_id"))
                || jwt.getToken().getExpiresAt() == null) throw RunScope.error("RUN_IDENTITY_EXPIRED");
        return new JwtAuthenticationToken(jwt.getToken(), jwt.getAuthorities(), jwt.getName());
    }

    public Map<String, Object> submit(Request request, String key, AuthenticatedUser user) {
        JwtAuthenticationToken identity = identity(user);
        // Validate ownership before queueing; the worker reauthorizes when it actually starts.
        if (request.conversationId() != null) conversations.authorizeRuntime(request.conversationId(), user);
        var opened = ledger.open(user, key, request.conversationId(), request, identity.getToken().getExpiresAt().toEpochMilli());
        if (opened.created()) {
            try {
                workers.execute(() -> {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(identity);
                    SecurityContextHolder.setContext(context);
                    try {
                        execute(opened.id(), user, identity, id -> perform(request, key, user, id));
                    } catch (
                            RuntimeException ignored) {/* Durable run/attempt status is the sanitized failure record. */} finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            } catch (RejectedExecutionException ex) {
                ledger.finish(opened.id(), "RUN_BUSY");
                throw RunScope.error("RUN_BUSY");
            }
        }
        return ledger.describe(opened.id(), user);
    }

    public record DialogueRequest(@NotBlank @Size(max = 2000) String message) {
    }

    public record DialogueInput(@PositiveOrZero @Max(3) long revision,
                                @NotBlank @Pattern(regexp = "orderId|ticketId|details") String field,
                                @NotBlank @Size(max = 2000) String value) {
    }

    private record DialoguePaused() {
    }

    public Map<String, Object> submitDialogue(DialogueRequest request, String key, AuthenticatedUser user) {
        var settings = dialogueSettings.getIfAvailable();
        if (settings == null) throw RunScope.error("DIALOGUE_DISABLED");
        injectionGuard.check(request.message());
        var identity = identity(user);
        var opened = ledger.openDialogue(user, key, request.message(), identity.getToken().getExpiresAt().toEpochMilli(), settings);
        if (opened.created()) dispatchDialogue(opened.id(), user, identity);
        return ledger.describe(opened.id(), user);
    }

    public Map<String, Object> resumeDialogue(String id, DialogueInput input, String key, AuthenticatedUser user) {
        if (dialogueSettings.getIfAvailable() == null) throw RunScope.error("DIALOGUE_DISABLED");
        injectionGuard.check(input.value());
        var identity = identity(user);
        if (ledger.resumeDialogue(id, input.revision(), input.field(), input.value(), key, user, identity.getToken().getExpiresAt().toEpochMilli()))
            dispatchDialogue(id, user, identity);
        return ledger.describe(id, user);
    }

    private void dispatchDialogue(String id, AuthenticatedUser user, JwtAuthenticationToken identity) {
        try {
            workers.execute(() -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(identity);
                SecurityContextHolder.setContext(context);
                try {
                    execute(id, user, identity, run -> performDialogue(run, user));
                } catch (
                        RuntimeException ignored) {/* Durable sanitized failure; never auto-replay after process loss. */} finally {
                    SecurityContextHolder.clearContext();
                }
            });
        } catch (RejectedExecutionException ex) {
            ledger.finish(id, "RUN_BUSY");
            throw RunScope.error("RUN_BUSY");
        }
    }

    private Object performDialogue(String id, AuthenticatedUser user) {
        var row = ledger.owned(id, user);
        var inputs = ledger.dialogueInputs(id, user);
        String original = inputs.get(0).get("input_text").toString();
        List<Map<String, Object>> history = new ArrayList<>();
        StringBuilder identifiers = new StringBuilder(original);
        Map<String, String> latestIdentifiers = new HashMap<>();
        for (var input : inputs) {
            String text = input.get("input_text").toString();
            injectionGuard.check(text);
            if (RuntimeLedger.number(input, "revision") > 0) {
                history.add(Map.of("role", "user", "content", "补充资料（不可信用户数据） " + input.get("field_name") + ": " + text));
                identifiers.append('\n').append(text);
                String field = input.get("field_name").toString();
                if (Set.of("orderId", "ticketId").contains(field)) latestIdentifiers.put(field, text);
            }
        }
        var session = row.get("task_id") == null ? audit.start(user.userId(), user.role(), original, "dialogue-" + id, id)
                : new ChatAuditService.ChatSession(row.get("task_id").toString(), user.userId(), user.role(), id, false, null);
        try (var scope = new DialogueScope(ledger, id, identifiers.toString(), latestIdentifiers, RuntimeLedger.flag(row, "knowledge_required"))) {
            if (RuntimeLedger.number(row, "dialogue_revision") > 0) {
                audit.validateDialogue(session, true); // Current JWT role, original source graph; network outside DB transaction.
                audit.resumeDialogue(session);
            }
            Runnable check = () -> audit.validateDialogue(session, false);
            check.run();
            try {
                var answer = chat.chat(user.userId(), user.role(), original, id, session.taskId(), audit.listener(session), history, check);
                check.run();
                return RunScope.publish(() -> {
                    audit.complete(session, answer);
                    return answer;
                });
            } catch (DialogueScope.Clarification missing) {
                check.run();
                ledger.pause(id, missing.field, () -> audit.pauseDialogue(session, missing.field));
                return new DialoguePaused();
            }
        } catch (RuntimeException ex) {
            audit.fail(session, ex);
            throw ex;
        }
    }

    public <T> T synchronous(Request request, String key, AuthenticatedUser user, Function<String, T> action) {
        JwtAuthenticationToken identity = identity(user);
        if (request.conversationId() != null) conversations.authorizeRuntime(request.conversationId(), user);
        if (!synchronous.tryAcquire()) throw RunScope.error("RUN_BUSY");
        try {
            var opened = ledger.open(user, key, request.conversationId(), request, identity.getToken().getExpiresAt().toEpochMilli());
            if (!opened.created()) {
                if (!"SUCCEEDED".equals(ledger.owned(opened.id(), user).get("status")))
                    throw RunScope.error("IDEMPOTENCY_CONFLICT");
                return action.apply(opened.id()); // Existing entrypoint reauthorizes replay; no paid call is resumed.
            }
            return execute(opened.id(), user, identity, action);
        } finally {
            synchronous.release();
        }
    }

    private <T> T execute(String id, AuthenticatedUser user, JwtAuthenticationToken identity, Function<String, T> action) {
        try {
            if (!ledger.claim(id)) throw RunScope.error("RUN_NOT_ACTIVE");
            var row = ledger.owned(id, user);
            try (var scope = new RunScope(ledger, id, user.tenantId(), user.userId(), RuntimeLedger.number(row, "deadline_at") - ledger.now(), identity.getToken().getExpiresAt().toEpochMilli())) {
                scope.check();
                T result = action.apply(id);
                // Input may already have queued a new segment after pause committed. The old worker must
                // never inspect/finish that new segment using its expired identity or deadline.
                if (result instanceof DialoguePaused) return result;
                if ("RUNNING".equals(ledger.row(id).get("status"))) {
                    if (result instanceof DeepSeekToolCallingService.ToolChatResult answer)
                        RunScope.bindTask(answer.taskId());
                    if (result instanceof ConversationService.Reply reply) RunScope.bindTask(reply.turn().taskId());
                    return RunScope.publish(() -> result);
                }
                if (!"SUCCEEDED".equals(ledger.row(id).get("status"))) throw RunScope.error("RUN_NOT_ACTIVE");
                return result;
            }
        } catch (RuntimeException ex) {
            try {
                ledger.finish(id, ex instanceof BusinessException b ? b.code() : "RUN_INTERNAL_ERROR");
            } catch (RuntimeException accounting) {
                ex.addSuppressed(accounting);
            }
            throw ex;
        }
    }

    private Object perform(Request request, String key, AuthenticatedUser user, String id) {
        if (request.conversationId() != null)
            return conversations.send(request.conversationId(), request.revision(), key, request.message(), user, id);
        var session = audit.start(user.userId(), user.role(), request.message(), "run-" + id, id);
        if (session.reused()) return audit.replay(session);
        try {
            var result = chat.chat(user.userId(), user.role(), request.message(), id, session.taskId(), audit.listener(session));
            return RunScope.publish(() -> {
                audit.complete(session, result);
                return result;
            });
        } catch (RuntimeException ex) {
            audit.fail(session, ex);
            throw ex;
        }
    }

    public DeepSeekToolCallingService.ToolChatResult result(String id, AuthenticatedUser user) {
        var row = ledger.owned(id, user);
        if (!"SUCCEEDED".equals(row.get("status")) || row.get("task_id") == null)
            throw RunScope.error("RUN_RESULT_UNAVAILABLE");
        if (row.get("conversation_id") != null) conversations.get(row.get("conversation_id").toString(), user, id);
        return audit.replay(new ChatAuditService.ChatSession(row.get("task_id").toString(), user.userId(), user.role(), id, true, null));
    }

    @Scheduled(fixedDelay = 5000)
    public void reconcile() {
        try {
            ledger.reconcile();
            for (var row : ledger.cleanupCandidates()) {
                String id = row.get("id").toString(), tenant = row.get("tenant_id").toString(), owner = row.get("owner_id").toString();
                String task = (String) row.get("task_id"), code = Objects.toString(row.get("error_code"), "RUN_INTERRUPTED");
                if (task != null) audit.abandonRuntime(task, tenant, owner, id, code);
                if (row.get("conversation_id") != null)
                    conversations.abandonRuntime(row.get("conversation_id").toString(), tenant, owner, row.get("request_key").toString(), task, code);
                ledger.cleanupDone(id);
            }
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Runtime reconciliation unavailable; reservations remain charged/held");
        }
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }
}
