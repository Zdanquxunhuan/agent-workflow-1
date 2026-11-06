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

/**
 * 会话编排。
 *
 * <p>这里把“聊天记录”和“Agent 执行”分开：
 * <ul>
 *   <li>{@link Conversation}/{@link Conversation.Turn} 保存用户看得到的消息和答案；</li>
 *   <li>每一轮 {@link #send} 仍然创建独立的 AgentTask/Run，由工具调用内核执行；</li>
 *   <li>历史只通过 {@link ConversationContext} 构建成有预算的窗口，并不是长期模型会话。</li>
 * </ul>
 *
 * <p>并发与费用安全有三层：
 * <ul>
 *   <li>turn 的 Idempotency-Key：网络重试返回同一轮，不重复调用模型；</li>
 *   <li>conversation 的 revision CAS：两个客户端同时写时只有一个能提交；</li>
 *   <li>requireIdle：同一会话同一时刻只允许一个 RUNNING turn。</li>
 * </ul>
 */
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
    @Value("${agent.conversation.context-budget:6000}")
    private int contextBudget = 6000;
    @Value("${agent.conversation.retention-days:7}")
    private int retentionDays = 7;

    public ConversationService(ConversationRepository repository, ChatAuditService audit,
                               DeepSeekToolCallingService model, KnowledgeSearchClient knowledge) {
        this.repository = repository;
        this.audit = audit;
        this.model = model;
        this.knowledge = knowledge;
    }

    /** 新建空会话；保留期取配置值，并限制在 1~30 天之间。 */
    public Conversation create(AuthenticatedUser user) {
        TenantIdentity.require(user.tenantId());
        var value = new Conversation(UUID.randomUUID().toString(), user.tenantId(), user.userId(), Set.of(), 0,
                Instant.now().plusSeconds(Math.max(1, Math.min(30, retentionDays)) * 86400L), false, List.of(),
                new Conversation.Summary(0, 0, "", List.of()));
        repository.create(value);
        return value;
    }

    public record Listing(String id, String owner, long revision, Instant expiresAt) {
    }

    public List<Listing> list(AuthenticatedUser user) {
        TenantIdentity.require(user.tenantId());
        return repository.list(user.tenantId(), user.userId()).stream()
                .map(c -> new Listing(c.id(), c.owner(), c.revision(), c.expiresAt())).toList();
    }

    /**
     * 会话权限：owner 可读写；参与者只能读；其他人一律按“不存在”处理，避免泄露会话是否存在。
     *
     * <p>写操作还包括发消息、改参与者、取消和删除，因此都用 {@code write=true}。
     */
    private Conversation authorized(String id, AuthenticatedUser user, boolean write) {
        TenantIdentity.require(user.tenantId());
        var c = repository.require(id, user.tenantId());
        if (!c.owner().equals(user.userId()) && (write || !c.participants().contains(user.userId())))
            throw BusinessException.of("NOT_FOUND", "Conversation does not exist");
        return c;
    }

    public void authorizeRuntime(String id, AuthenticatedUser user) {
        authorized(id, user, true);
    }

    private void validateSources(Collection<String> tasks, AuthenticatedUser user, String trace) {
        for (String task : new LinkedHashSet<>(tasks)) {
            knowledge.revalidateTask(new ToolContext(user.userId(), user.role(), task, trace, user.tenantId()));
            if (memoryGuard != null && memoryGuard.getIfAvailable() != null)
                memoryGuard.getObject().validateTask(new ToolContext(user.userId(), user.role(), task, trace, user.tenantId()));
        }
    }

    /**
     * 读取会话前重新校验历史来源。
     *
     * <p>用户可能已经被移除知识库/记忆权限，但历史答案仍然存在；如果直接返回，就会泄露
     * 他当前已无权查看的内容。因此每次读会话都要重新走一遍来源授权。
     */
    public Conversation get(String id, AuthenticatedUser user, String trace) {
        var c = authorized(id, user, false);
        validateSources(c.turns().stream().filter(t -> t.taskId() != null).map(Conversation.Turn::taskId).toList(), user, trace);
        assertUnchanged(c, user);
        return c;
    }

    public Conversation participants(String id, long revision, Set<String> readers, AuthenticatedUser user) {
        var c = authorized(id, user, true);
        requireRevision(c, revision);
        requireIdle(c);
        if (readers == null || readers.size() > 10 || readers.stream().anyMatch(s -> s == null || !s.matches("[A-Za-z0-9._@-]{1,64}")))
            throw BusinessException.of("INVALID_ARGUMENT", "At most 10 valid participant subjects are allowed");
        var next = c.next(readers, c.turns(), c.summary(), false);
        repository.save(c, next);
        // Do not return transcript without evidence reauthorization from this administrative operation.
        return new Conversation(next.id(), next.tenantId(), next.owner(), next.participants(), next.revision(),
                next.expiresAt(), false, List.of(), new Conversation.Summary(0, 0, "", List.of()));
    }

    public void delete(String id, long revision, AuthenticatedUser user) {
        var c = authorized(id, user, true);
        requireRevision(c, revision);
        repository.save(c, c.next(Set.of(), List.of(), new Conversation.Summary(0, 0, "", List.of()), true));
    }

    public void cancel(String id, long revision, AuthenticatedUser user) {
        var c = authorized(id, user, true);
        requireRevision(c, revision);
        var turns = new ArrayList<>(c.turns());
        if (turns.isEmpty() || !"RUNNING".equals(turns.get(turns.size() - 1).status()))
            throw BusinessException.of("CONFLICT", "No turn is running");
        var t = turns.get(turns.size() - 1);
        turns.set(turns.size() - 1, new Conversation.Turn(t.key(), t.message(), t.taskId(), "FAILED", null, 0, List.of(), "CANCELLED"));
        repository.save(c, c.next(c.participants(), turns, c.summary(), false));
        if (runtimeLedger != null) runtimeLedger.ifAvailable(ledger -> ledger.cancelConversation(id, t.key(), user));
    }

    public record Reply(String conversationId, long revision, Conversation.Turn turn, int estimatedTokens,
                        int omittedTurns) {
    }

    /**
     * 追加一轮消息并同步执行。
     *
     * <p>顺序很重要：先做幂等检查，再校验 revision 和空闲状态，再构建有预算的上下文，
     * 然后在调用模型之前把 RUNNING 占位写入数据库，最后才允许产生模型费用。
     * 这样并发请求、进程崩溃和客户端重试都不会把同一轮执行两次。
     */
    public Reply send(String id, long revision, String key, String message, AuthenticatedUser user, String trace) {
        // 共享运行时的取消/超时/JWT/预算检查；没有 RunScope 时这是空操作。
        com.yu.agent.workflow.runtime.RunScope.checkpoint();
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,80}") || message == null || message.isBlank() || message.length() > 2000)
            throw BusinessException.of("INVALID_ARGUMENT", "A valid idempotency key and message (1-2000 characters) are required");
        // 发消息是写操作，只有 owner 能执行。
        var c = authorized(id, user, true);
        // 第一层幂等：同一个 key 在这一轮已经存在时，只有“同样消息 + 已完成”才能重放；
        // 否则说明 key 被复用到了不同内容或未完成的状态，必须拒绝。
        for (var turn : c.turns())
            if (turn.key().equals(key)) {
                if (!turn.message().equals(message) || !"COMPLETED".equals(turn.status()))
                    throw BusinessException.of("IDEMPOTENCY_CONFLICT", "Key is already used by another, running or failed turn");
                validateSources(List.of(turn.taskId()), user, trace);
                assertUnchanged(c, user);
                return new Reply(id, c.revision(), turn, 0, 0);
            }
        // 第二层并发控制：revision 必须等于客户端读到的版本；
        // 同时会话不能已有 RUNNING 轮次，避免两个 turn 交错写同一段历史。
        requireRevision(c, revision);
        requireIdle(c);
        if (c.turns().size() >= 100)
            throw BusinessException.of("CONFLICT", "Conversation limit reached; create a new conversation");
        // 长期记忆是可选的“补充资料”，必须先按预算裁剪，不能挤掉当前消息和历史窗口。
        var memory = memoryProvider == null ? null : memoryProvider.getIfAvailable();
        com.yu.agent.workflow.runtime.RunScope.progress("context_loading", Map.of());
        if (memory != null) memory.requireModelTenant(user);
        var recalled = memory == null ? new com.yu.agent.workflow.memory.MemoryService.Selection(List.of(), List.of(), "") : memory.recall(user, message);
        int totalBudget = Math.max(512, Math.min(12000, contextBudget));
        // Preserve the current user message and room for a compact historical reference before adding optional memories.
        var selection = memory == null ? recalled : memory.fit(recalled, totalBudget
                - message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 32 - (c.turns().isEmpty() ? 0 : 256));
        String memoryPrompt = memory == null ? "" : memory.prompt(selection);
        int memoryBytes = memoryPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + (memoryPrompt.isEmpty() ? 0 : 32);
        // 历史窗口只保留最近几轮 + 更早轮次的一条话题提示；不是把全部 transcript 塞给模型。
        var window = ConversationContext.build(c, message, totalBudget - memoryBytes);
        List<Map<String, Object>> contextMessages = new ArrayList<>();
        if (!memoryPrompt.isEmpty()) contextMessages.add(Map.of("role", "user", "content", memoryPrompt));
        contextMessages.addAll(window.messages());
        // 窗口里引用过的知识/记忆来源，在发给模型前必须重新确认当前用户仍有权限。
        validateSources(window.sourceTasks(), user, trace);
        var turns = new ArrayList<>(c.turns());
        // 先写 RUNNING 占位，再调用模型：这是跨实例的“占位锁”，也是崩溃恢复时识别半成品的依据。
        turns.add(new Conversation.Turn(key, message, null, "RUNNING", null, 0, List.of(), null));
        var reserved = c.next(c.participants(), turns, window.summary(), false);
        repository.save(c, reserved); // Cross-instance CAS BEFORE any model request.
        ChatAuditService.ChatSession session = null;
        Conversation working = reserved;
        try {
            // 每一轮仍然有自己的 AgentTask，会话只是把这些 task 的问答串起来展示。
            session = audit.start(user.userId(), user.role(), message, "conv-" + UUID.randomUUID(), trace);
            turns.set(turns.size() - 1, new Conversation.Turn(key, message, session.taskId(), "RUNNING", null, 0, List.of(), null));
            var attached = reserved.next(reserved.participants(), turns, window.summary(), false);
            repository.save(reserved, attached);
            working = attached;
            // 新任务继承历史轮次的证据授权，但只用于“可见性”，业务事实仍要在本轮重新查询。
            var taskContext = new ToolContext(user.userId(), user.role(), session.taskId(), trace, user.tenantId());
            knowledge.inheritTaskEvidence(taskContext, window.sourceTasks());
            if (memory != null) {
                memory.validate(selection, user);
                memory.bind(session.taskId(), selection, window.sourceTasks());
                audit.memoryContext(session, Map.of("decisions", selection.decisions(), "degraded", selection.degraded(),
                        "memoryVersion", "memory-v7", "estimatedBytes", memoryBytes));
            } else if (memoryGuard != null && memoryGuard.getIfAvailable() != null) {
                memoryGuard.getObject().inherit(session.taskId(), window.sourceTasks());
            }
            // 模型调用前后都要过同一道“栅栏”：
            // 1) 会话 revision 没被别人改过；2) 历史来源仍然有权限；3) 记忆仍然有效。
            // 这样即使模型跑到一半用户被撤销权限，答案也不会被发布。
            final Conversation fence = working;
            Runnable check = () -> {
                assertUnchanged(fence, user);
                validateSources(window.sourceTasks(), user, trace);
                if (memory != null) memory.validate(selection, user);
            };
            var result = model.chat(user.userId(), user.role(), message, trace, session.taskId(), audit.listener(session), contextMessages, check);
            check.run();
            var done = new Conversation.Turn(key, message, session.taskId(), "COMPLETED", result.answer(), result.rounds(), result.executedTools(), null);
            turns.set(turns.size() - 1, done);
            var finished = working.next(working.participants(), turns, window.summary(), false);
            var completedSession = session;
            var previous = working;
            // 答案、turn 状态、会话 revision 和运行 SUCCEEDED 在同一个事务里提交；
            // 任何一步失败都会回滚，不会出现“答案已展示但运行没记账”的中间态。
            return com.yu.agent.workflow.runtime.RunScope.publish(() -> {
                audit.complete(completedSession, result);
                repository.save(previous, finished);
                return new Reply(id, finished.revision(), done, window.estimatedTokens() + memoryBytes, window.omittedTurns());
            });
        } catch (RuntimeException ex) {
            // 失败也要落一个 FAILED turn；但保存时仍用 CAS，不能覆盖用户刚刚的取消或删除。
            if (session != null) try {
                audit.fail(session, ex);
            } catch (RuntimeException failure) {
                ex.addSuppressed(failure);
            }
            turns.set(turns.size() - 1, new Conversation.Turn(key, message, session == null ? null : session.taskId(), "FAILED", null, 0, List.of(),
                    ex instanceof BusinessException b ? b.code() : "INTERNAL_ERROR"));
            try {
                repository.save(working, working.next(working.participants(), turns, window.summary(), false));
            } catch (RuntimeException failure) {
                ex.addSuppressed(failure);
            } // Never overwrite cancellation/deletion or a newer turn.
            throw ex;
        }
    }

    /** 重新读库并确认 revision 没变；模型调用耗时较长，期间可能有取消、删除或其他写入。 */
    private void assertUnchanged(Conversation c, AuthenticatedUser user) {
        requireRevision(authorized(c.id(), user, false), c.revision());
    }

    /**
     * 仅用于崩溃恢复：把“运行已中断但 turn 还停在 RUNNING”的占位改成 FAILED。
     *
     * <p>必须同时匹配 run 的 request_key、taskId 和 owner，防止一个旧运行误伤用户后来重新提交的同名 turn。
     */
    public void abandonRuntime(String id, String tenant, String owner, String key, String taskId, String code) {
        Conversation c;
        try {
            c = repository.require(id, tenant);
        } catch (BusinessException ex) {
            if ("NOT_FOUND".equals(ex.code())) return;
            throw ex;
        }
        if (!c.owner().equals(owner)) throw BusinessException.of("FORBIDDEN", "Runtime owner mismatch");
        var turns = new ArrayList<>(c.turns());
        for (int i = 0; i < turns.size(); i++) {
            var t = turns.get(i);
            if (t.key().equals(key) && "RUNNING".equals(t.status()) && (t.taskId() == null || Objects.equals(t.taskId(), taskId))) {
                turns.set(i, new Conversation.Turn(t.key(), t.message(), t.taskId(), "FAILED", null, 0, List.of(), code));
                repository.save(c, c.next(c.participants(), turns, c.summary(), false));
                return;
            }
        }
    }

    /** 乐观锁校验：客户端必须基于最新版本提交，否则让它重新加载，避免覆盖别人的修改。 */
    private void requireRevision(Conversation c, long expected) {
        if (c.revision() != expected) throw BusinessException.of("CONFLICT", "Conversation revision changed; reload");
    }

    /** 同一会话一次只允许一轮在跑，否则两轮会交错写同一段历史，语义和顺序都无法保证。 */
    private void requireIdle(Conversation c) {
        if (c.turns().stream().anyMatch(t -> "RUNNING".equals(t.status())))
            throw BusinessException.of("CONFLICT", "A turn is running; reload or cancel it");
    }
}
