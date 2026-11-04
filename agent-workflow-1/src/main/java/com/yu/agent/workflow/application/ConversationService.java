package com.yu.agent.workflow.application;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.Conversation;
import com.yu.agent.workflow.persistence.ConversationRepository;
import com.yu.agent.workflow.security.AuthenticatedUser;
import com.yu.agent.workflow.security.TenantIdentity;
import com.yu.agent.workflow.tools.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class ConversationService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.yu.agent.workflow.runtime.RuntimeLedger> runtimeLedger;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.yu.agent.workflow.memory.MemoryService> memoryProvider;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.yu.agent.workflow.memory.MemoryProvenanceGuard> memoryGuard;
    private final ConversationRepository repository;
    private final ChatAuditService audit;
    private final DeepSeekToolCallingService model;
    private final KnowledgeSearchClient knowledge;
    @Value("${agent.conversation.context-budget:6000}") private int contextBudget = 6000;
    @Value("${agent.conversation.retention-days:7}") private int retentionDays = 7;
    public ConversationService(ConversationRepository repository, ChatAuditService audit,
                               DeepSeekToolCallingService model, KnowledgeSearchClient knowledge) {
        this.repository = repository; this.audit = audit; this.model = model; this.knowledge = knowledge;
    }
    public Conversation create(AuthenticatedUser user) {
        TenantIdentity.require(user.tenantId());
        var value = new Conversation(UUID.randomUUID().toString(), user.tenantId(), user.userId(), Set.of(), 0,
                Instant.now().plusSeconds(Math.max(1, Math.min(30, retentionDays)) * 86400L), false, List.of(),
                new Conversation.Summary(0, 0, "", List.of()));
        repository.create(value); return value;
    }
    public record Listing(String id, String owner, long revision, Instant expiresAt) {}
    public List<Listing> list(AuthenticatedUser user) {
        TenantIdentity.require(user.tenantId());
        return repository.list(user.tenantId(), user.userId()).stream()
                .map(c -> new Listing(c.id(), c.owner(), c.revision(), c.expiresAt())).toList();
    }
    private Conversation authorized(String id, AuthenticatedUser user, boolean write) {
        TenantIdentity.require(user.tenantId());
        var c = repository.require(id, user.tenantId());
        if (!c.owner().equals(user.userId()) && (write || !c.participants().contains(user.userId())))
            throw BusinessException.of("NOT_FOUND", "Conversation does not exist");
        return c;
    }
    public void authorizeRuntime(String id,AuthenticatedUser user){authorized(id,user,true);}
    private void validateSources(Collection<String> tasks, AuthenticatedUser user, String trace) {
        for (String task : new LinkedHashSet<>(tasks)) {
            knowledge.revalidateTask(new ToolContext(user.userId(), user.role(), task, trace, user.tenantId()));
            if (memoryGuard != null && memoryGuard.getIfAvailable() != null)
                memoryGuard.getObject().validateTask(new ToolContext(user.userId(),user.role(),task,trace,user.tenantId()));
        }
    }
    public Conversation get(String id, AuthenticatedUser user, String trace) {
        var c = authorized(id, user, false);
        validateSources(c.turns().stream().filter(t -> t.taskId() != null).map(Conversation.Turn::taskId).toList(), user, trace);
        assertUnchanged(c, user);
        return c;
    }
    public Conversation participants(String id, long revision, Set<String> readers, AuthenticatedUser user) {
        var c = authorized(id, user, true); requireRevision(c, revision); requireIdle(c);
        if (readers == null || readers.size() > 10 || readers.stream().anyMatch(s -> s == null || !s.matches("[A-Za-z0-9._@-]{1,64}")))
            throw BusinessException.of("INVALID_ARGUMENT", "At most 10 valid participant subjects are allowed");
        var next = c.next(readers, c.turns(), c.summary(), false); repository.save(c, next);
        // Do not return transcript without evidence reauthorization from this administrative operation.
        return new Conversation(next.id(), next.tenantId(), next.owner(), next.participants(), next.revision(),
                next.expiresAt(), false, List.of(), new Conversation.Summary(0,0,"",List.of()));
    }
    public void delete(String id, long revision, AuthenticatedUser user) {
        var c = authorized(id, user, true); requireRevision(c, revision);
        repository.save(c, c.next(Set.of(), List.of(), new Conversation.Summary(0,0,"",List.of()), true));
    }
    public void cancel(String id, long revision, AuthenticatedUser user) {
        var c = authorized(id, user, true); requireRevision(c, revision);
        var turns = new ArrayList<>(c.turns());
        if (turns.isEmpty() || !"RUNNING".equals(turns.get(turns.size()-1).status()))
            throw BusinessException.of("CONFLICT", "No turn is running");
        var t = turns.get(turns.size()-1);
        turns.set(turns.size()-1, new Conversation.Turn(t.key(), t.message(), t.taskId(), "FAILED", null, 0, List.of(), "CANCELLED"));
        repository.save(c, c.next(c.participants(), turns, c.summary(), false));
        if(runtimeLedger!=null)runtimeLedger.ifAvailable(ledger->ledger.cancelConversation(id,t.key(),user));
    }
    public record Reply(String conversationId, long revision, Conversation.Turn turn, int estimatedTokens, int omittedTurns) {}
    public Reply send(String id, long revision, String key, String message, AuthenticatedUser user, String trace) {
        com.yu.agent.workflow.runtime.RunScope.checkpoint();
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,80}") || message == null || message.isBlank() || message.length() > 2000)
            throw BusinessException.of("INVALID_ARGUMENT", "A valid idempotency key and message (1-2000 characters) are required");
        var c = authorized(id, user, true);
        for (var turn : c.turns()) if (turn.key().equals(key)) {
            if (!turn.message().equals(message) || !"COMPLETED".equals(turn.status()))
                throw BusinessException.of("IDEMPOTENCY_CONFLICT", "Key is already used by another, running or failed turn");
            validateSources(List.of(turn.taskId()), user, trace); assertUnchanged(c, user);
            return new Reply(id, c.revision(), turn, 0, 0);
        }
        requireRevision(c, revision); requireIdle(c);
        if (c.turns().size() >= 100) throw BusinessException.of("CONFLICT", "Conversation limit reached; create a new conversation");
        var memory = memoryProvider == null ? null : memoryProvider.getIfAvailable();
        com.yu.agent.workflow.runtime.RunScope.progress("context_loading",Map.of());
        if (memory != null) memory.requireModelTenant(user);
        var recalled = memory == null ? new com.yu.agent.workflow.memory.MemoryService.Selection(List.of(),List.of(),"") : memory.recall(user,message);
        int totalBudget = Math.max(512, Math.min(12000, contextBudget));
        // Preserve the current user message and room for a compact historical reference before adding optional memories.
        var selection = memory == null ? recalled : memory.fit(recalled,totalBudget
                - message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 32 - (c.turns().isEmpty()?0:256));
        String memoryPrompt = memory == null ? "" : memory.prompt(selection);
        int memoryBytes = memoryPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + (memoryPrompt.isEmpty()?0:32);
        var window = ConversationContext.build(c, message, totalBudget - memoryBytes);
        List<Map<String,Object>> contextMessages = new ArrayList<>();
        if (!memoryPrompt.isEmpty()) contextMessages.add(Map.of("role","user","content",memoryPrompt));
        contextMessages.addAll(window.messages());
        validateSources(window.sourceTasks(), user, trace);
        var turns = new ArrayList<>(c.turns());
        turns.add(new Conversation.Turn(key, message, null, "RUNNING", null, 0, List.of(), null));
        var reserved = c.next(c.participants(), turns, window.summary(), false);
        repository.save(c, reserved); // Cross-instance CAS BEFORE any model request.
        ChatAuditService.ChatSession session = null;
        Conversation working = reserved;
        try {
            session = audit.start(user.userId(), user.role(), message, "conv-" + UUID.randomUUID(), trace);
            turns.set(turns.size()-1, new Conversation.Turn(key, message, session.taskId(), "RUNNING", null, 0, List.of(), null));
            var attached = reserved.next(reserved.participants(), turns, window.summary(), false);
            repository.save(reserved, attached);
            working = attached;
            var taskContext = new ToolContext(user.userId(), user.role(), session.taskId(), trace, user.tenantId());
            knowledge.inheritTaskEvidence(taskContext, window.sourceTasks());
            if (memory != null) {
                memory.validate(selection,user);
                memory.bind(session.taskId(),selection,window.sourceTasks());
                audit.memoryContext(session,Map.of("decisions",selection.decisions(),"degraded",selection.degraded(),
                        "memoryVersion","memory-v7","estimatedBytes",memoryBytes));
            } else if (memoryGuard != null && memoryGuard.getIfAvailable() != null) {
                memoryGuard.getObject().inherit(session.taskId(),window.sourceTasks());
            }
            final Conversation fence = working;
            Runnable check = () -> {
                assertUnchanged(fence, user); validateSources(window.sourceTasks(), user, trace);
                if (memory != null) memory.validate(selection,user);
            };
            var result = model.chat(user.userId(), user.role(), message, trace, session.taskId(), audit.listener(session), contextMessages, check);
            check.run();
            var done = new Conversation.Turn(key, message, session.taskId(), "COMPLETED", result.answer(), result.rounds(), result.executedTools(), null);
            turns.set(turns.size()-1, done);
            var finished = working.next(working.participants(), turns, window.summary(), false);
            var completedSession=session;
            var previous=working;
            return com.yu.agent.workflow.runtime.RunScope.publish(()->{
                audit.complete(completedSession, result);
                repository.save(previous, finished);
                return new Reply(id, finished.revision(), done, window.estimatedTokens()+memoryBytes, window.omittedTurns());
            });
        } catch (RuntimeException ex) {
            if (session != null) try { audit.fail(session, ex); } catch (RuntimeException failure) { ex.addSuppressed(failure); }
            turns.set(turns.size()-1, new Conversation.Turn(key, message, session == null ? null : session.taskId(), "FAILED", null, 0, List.of(),
                    ex instanceof BusinessException b ? b.code() : "INTERNAL_ERROR"));
            try { repository.save(working, working.next(working.participants(), turns, window.summary(), false)); }
            catch (RuntimeException failure) { ex.addSuppressed(failure); } // Never overwrite cancellation/deletion or a newer turn.
            throw ex;
        }
    }
    private void assertUnchanged(Conversation c, AuthenticatedUser user) { requireRevision(authorized(c.id(), user, false), c.revision()); }
    /** Internal recovery only: persisted run identity and turn key fence a crash-abandoned placeholder. */
    public void abandonRuntime(String id,String tenant,String owner,String key,String taskId,String code) {
        Conversation c;
        try{c=repository.require(id,tenant);}catch(BusinessException ex){if("NOT_FOUND".equals(ex.code()))return;throw ex;}
        if(!c.owner().equals(owner))throw BusinessException.of("FORBIDDEN","Runtime owner mismatch");
        var turns=new ArrayList<>(c.turns());
        for(int i=0;i<turns.size();i++) {
            var t=turns.get(i);
            if(t.key().equals(key) && "RUNNING".equals(t.status()) && (t.taskId()==null || Objects.equals(t.taskId(),taskId))) {
                turns.set(i,new Conversation.Turn(t.key(),t.message(),t.taskId(),"FAILED",null,0,List.of(),code));
                repository.save(c,c.next(c.participants(),turns,c.summary(),false));return;
            }
        }
    }
    private void requireRevision(Conversation c, long expected) {
        if (c.revision() != expected) throw BusinessException.of("CONFLICT", "Conversation revision changed; reload");
    }
    private void requireIdle(Conversation c) {
        if (c.turns().stream().anyMatch(t -> "RUNNING".equals(t.status()))) throw BusinessException.of("CONFLICT", "A turn is running; reload or cancel it");
    }
}
