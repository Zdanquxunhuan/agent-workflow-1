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

/**
 * 运行时外壳：把“直接调用 Agent”升级为可排队、可取消、可计费、可恢复的运行。
 *
 * <p>三种入口都复用这里：
 * <ul>
 *   <li>一次性问答：/agent/chat 走 {@link #synchronous}，或 /agent/runs（conversationId=null）走 {@link #submit}；</li>
 *   <li>会话消息：/conversations/{id}/messages 走 {@link #synchronous}，或 /agent/runs + conversationId 走 {@link #submit}；</li>
 *   <li>对话型运行：/agent/dialogues 走 {@link #submitDialogue}，补充输入走 {@link #resumeDialogue}。</li>
 * </ul>
 *
 * <p>同步路径用信号量限流，异步路径用固定 worker 池；两者都会在真正执行前 claim 运行单，
 * 并在 {@link RunScope} 内检查取消、超时、JWT 过期和预算。
 *
 * <p>对话型运行暂停时不会占住 worker：运行状态落库为 WAITING_INPUT，线程直接返回；
 * 用户补充后重新入队，由同一 run id 继续执行。
 */
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
    // 异步执行池：4 个 worker + 8 个排队位；队列满时直接拒绝（RUN_BUSY），避免无限堆积拖垮进程。
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread t = new Thread(runnable, "agent-runtime-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    // 同步入口最多 4 个并发，避免长模型调用把 Web 容器线程打满。
    private final Semaphore synchronous = new Semaphore(4);

    public RuntimeService(RuntimeLedger ledger, ConversationService conversations, ChatAuditService audit, DeepSeekToolCallingService chat) {
        this.ledger = ledger;
        this.conversations = conversations;
        this.audit = audit;
        this.chat = chat;
    }

    /**
     * 把当前请求的 JWT 固化成可传给 worker 的认证对象。
     *
     * <p>worker 线程没有原始 HTTP 请求上下文，必须显式复制 SecurityContext；
     * 这里同时校验 subject、tenant_id 和过期时间，防止身份与用户对象不一致。
     */
    private JwtAuthenticationToken identity(AuthenticatedUser user) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || !user.userId().equals(jwt.getToken().getSubject()) || !user.tenantId().equals(jwt.getToken().getClaimAsString("tenant_id"))
                || jwt.getToken().getExpiresAt() == null) throw RunScope.error("RUN_IDENTITY_EXPIRED");
        return new JwtAuthenticationToken(jwt.getToken(), jwt.getAuthorities(), jwt.getName());
    }

    /**
     * 异步提交：一次性问答或会话消息。
     *
     * <p>请求进来时只做“开单 + 入队”，立即返回 QUEUED；真正执行在 worker。
     * 如果 conversationId 非空，就是“会话里发消息”；为空则是“一次性问答”。
     */
    public Map<String, Object> submit(Request request, String key, AuthenticatedUser user) {
        JwtAuthenticationToken identity = identity(user);
        // Validate ownership before queueing; the worker reauthorizes when it actually starts.
        // 先校验会话写权限，避免把别人的会话消息排进队列；worker 真正执行时还会再校验一次。
        if (request.conversationId() != null) conversations.authorizeRuntime(request.conversationId(), user);
        var opened = ledger.open(user, key, request.conversationId(), request, identity.getToken().getExpiresAt().toEpochMilli());
        if (opened.created()) {
            try {
                workers.execute(() -> {
                    // worker 线程没有原始请求上下文，需要把 JWT 身份显式放回 SecurityContext。
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
                // 队列满：把运行标记为 RUN_BUSY，避免数据库里留下永远不会被执行的 QUEUED 工单。
                ledger.finish(opened.id(), "RUN_BUSY");
                throw RunScope.error("RUN_BUSY");
            }
        }
        return ledger.describe(opened.id(), user);
    }

    /** 对话型运行的启动请求：只有原始问题；此时还不知道需要澄清什么。 */
    public record DialogueRequest(@NotBlank @Size(max = 2000) String message) {
    }

    /**
     * 对话型运行的补充输入。
     *
     * <p>{@code revision} 是客户端看到的 dialogue_revision；服务端用它拒绝旧页面/重复提交。
     * {@code field} 只能是三类：orderId、ticketId、details。
     */
    public record DialogueInput(@PositiveOrZero @Max(3) long revision,
                                @NotBlank @Pattern(regexp = "orderId|ticketId|details") String field,
                                @NotBlank @Size(max = 2000) String value) {
    }

    /** 标记“本次执行是正常暂停，不是失败”，避免 execute() 把 WAITING_INPUT 当成异常收尾。 */
    private record DialoguePaused() {
    }

    /**
     * 启动一条对话型运行。
     *
     * <p>与普通 submit 的区别：openDialogue 会写入第 0 版输入、计划事件和澄清/重规划上限；
     * 后续执行可能停在 WAITING_INPUT，而不是一次跑完。
     */
    public Map<String, Object> submitDialogue(DialogueRequest request, String key, AuthenticatedUser user) {
        var settings = dialogueSettings.getIfAvailable();
        if (settings == null) throw RunScope.error("DIALOGUE_DISABLED");
        injectionGuard.check(request.message());
        var identity = identity(user);
        var opened = ledger.openDialogue(user, key, request.message(), identity.getToken().getExpiresAt().toEpochMilli(), settings);
        if (opened.created()) dispatchDialogue(opened.id(), user, identity);
        return ledger.describe(opened.id(), user);
    }

    /**
     * 补充信息并恢复同一条运行。
     *
     * <p>{@code ledger.resumeDialogue} 会在一个事务里校验 revision/field/状态/期限，
     * 写入新输入并把运行改回 QUEUED；只有真正“新受理”时才重新入队，幂等重试不会重复执行。
     */
    public Map<String, Object> resumeDialogue(String id, DialogueInput input, String key, AuthenticatedUser user) {
        if (dialogueSettings.getIfAvailable() == null) throw RunScope.error("DIALOGUE_DISABLED");
        // 补充资料同样可能被注入，必须在落库前检查。
        injectionGuard.check(input.value());
        var identity = identity(user);
        if (ledger.resumeDialogue(id, input.revision(), input.field(), input.value(), key, user, identity.getToken().getExpiresAt().toEpochMilli()))
            dispatchDialogue(id, user, identity);
        return ledger.describe(id, user);
    }

    /** 把对话型运行丢进 worker；上下文复制方式与普通 submit 相同。 */
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

    /**
     * 对话型运行的实际执行。
     *
     * <p>每次恢复都会重放“原始问题 + 所有补充输入”，而不是只把最后一条补充丢给模型；
     * 这样模型能理解完整的上下文，但所有补充资料都会标注为不可信用户数据。
     *
     * <p>同一个 run 复用同一个审计 task；如果这是恢复执行，会先把 task 从 WAITING_INPUT
     * 拉回 RUNNING，再重新做身份和证据校验。
     */
    private Object performDialogue(String id, AuthenticatedUser user) {
        var row = ledger.owned(id, user);
        var inputs = ledger.dialogueInputs(id, user);
        // revision=0 是原始问题；后续 revision 才是补充资料。
        String original = inputs.get(0).get("input_text").toString();
        List<Map<String, Object>> history = new ArrayList<>();
        StringBuilder identifiers = new StringBuilder(original);
        Map<String, String> latestIdentifiers = new HashMap<>();
        for (var input : inputs) {
            String text = input.get("input_text").toString();
            injectionGuard.check(text);
            if (RuntimeLedger.number(input, "revision") > 0) {
                // 补充资料只作为“用户数据”参与理解，不提升为系统指令，也不作为业务证据。
                history.add(Map.of("role", "user", "content", "补充资料（不可信用户数据） " + input.get("field_name") + ": " + text));
                identifiers.append('\n').append(text);
                String field = input.get("field_name").toString();
                if (Set.of("orderId", "ticketId").contains(field)) latestIdentifiers.put(field, text);
            }
        }
        // 同一条 run 复用同一个 task；首次执行才创建。
        var session = row.get("task_id") == null ? audit.start(user.userId(), user.role(), original, "dialogue-" + id, id)
                : new ChatAuditService.ChatSession(row.get("task_id").toString(), user.userId(), user.role(), id, false, null);
        // DialogueScope 是本次执行的策略闸门：只读工具、单步决策、字段白名单、标识必须来自用户输入。
        try (var scope = new DialogueScope(ledger, id, identifiers.toString(), latestIdentifiers, RuntimeLedger.flag(row, "knowledge_required"))) {
            if (RuntimeLedger.number(row, "dialogue_revision") > 0) {
                // 恢复执行：先确认 task 仍处于 WAITING_INPUT，再拉回 RUNNING。
                audit.validateDialogue(session, true); // Current JWT role, original source graph; network outside DB transaction.
                audit.resumeDialogue(session);
            }
            // 模型调用前后都重新校验任务状态、记忆和知识来源权限。
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
                // 澄清不是失败：把 task 和 run 原子地置为 WAITING_INPUT，然后正常结束本次 worker。
                check.run();
                ledger.pause(id, missing.field, () -> audit.pauseDialogue(session, missing.field));
                return new DialoguePaused();
            }
        } catch (RuntimeException ex) {
            audit.fail(session, ex);
            throw ex;
        }
    }

    /**
     * 同步路径：调用方一直等到结果返回，适用于 /agent/chat 和 /conversations/{id}/messages。
     *
     * <p>与异步 submit 的区别只是“谁来等”：这里用信号量限制并发，执行仍在当前 HTTP 线程；
     * 预算、幂等、取消、RunScope 检查与异步路径完全一致。
     */
    public <T> T synchronous(Request request, String key, AuthenticatedUser user, Function<String, T> action) {
        JwtAuthenticationToken identity = identity(user);
        if (request.conversationId() != null) conversations.authorizeRuntime(request.conversationId(), user);
        if (!synchronous.tryAcquire()) throw RunScope.error("RUN_BUSY");
        try {
            var opened = ledger.open(user, key, request.conversationId(), request, identity.getToken().getExpiresAt().toEpochMilli());
            if (!opened.created()) {
                // 幂等命中：只有已经成功的运行才能重放；运行中/失败的同 key 请求视为冲突。
                if (!"SUCCEEDED".equals(ledger.owned(opened.id(), user).get("status")))
                    throw RunScope.error("IDEMPOTENCY_CONFLICT");
                return action.apply(opened.id()); // Existing entrypoint reauthorizes replay; no paid call is resumed.
            }
            return execute(opened.id(), user, identity, action);
        } finally {
            synchronous.release();
        }
    }

    /**
     * 所有运行真正执行的公共入口。
     *
     * <p>顺序：claim（QUEUED→RUNNING）→ 安装 RunScope → 执行业务 action → 发布结果或记录失败。
     * 任何一步抛异常，都会把运行收尾为对应的失败状态，避免留下永久 RUNNING 的工单。
     */
    private <T> T execute(String id, AuthenticatedUser user, JwtAuthenticationToken identity, Function<String, T> action) {
        try {
            // claim 是 CAS：只有 QUEUED 且未取消、未超时才能进入 RUNNING；重复执行会被拒绝。
            if (!ledger.claim(id)) throw RunScope.error("RUN_NOT_ACTIVE");
            var row = ledger.owned(id, user);
            // RunScope 绑定本次运行的预算、截止时间、JWT 过期时间；之后所有 checkpoint 共享这套限制。
            try (var scope = new RunScope(ledger, id, user.tenantId(), user.userId(), RuntimeLedger.number(row, "deadline_at") - ledger.now(), identity.getToken().getExpiresAt().toEpochMilli())) {
                scope.check();
                T result = action.apply(id);
                // Input may already have queued a new segment after pause committed. The old worker must
                // never inspect/finish that new segment using its expired identity or deadline.
                // 暂停时运行已经是 WAITING_INPUT，不能再走“成功发布”逻辑；直接返回即可。
                if (result instanceof DialoguePaused) return result;
                if ("RUNNING".equals(ledger.row(id).get("status"))) {
                    // 把业务审计 task 绑定到运行工单，之后 result/取消/清理都能找到对应任务。
                    if (result instanceof DeepSeekToolCallingService.ToolChatResult answer)
                        RunScope.bindTask(answer.taskId());
                    if (result instanceof ConversationService.Reply reply) RunScope.bindTask(reply.turn().taskId());
                    // 业务成功 + 运行 SUCCEEDED 同事务提交；此时预留费用必须已经结清。
                    return RunScope.publish(() -> result);
                }
                if (!"SUCCEEDED".equals(ledger.row(id).get("status"))) throw RunScope.error("RUN_NOT_ACTIVE");
                return result;
            }
        } catch (RuntimeException ex) {
            try {
                // 统一收尾：取消/超时优先于原始异常码，并检查预留费用是否结清。
                ledger.finish(id, ex instanceof BusinessException b ? b.code() : "RUN_INTERNAL_ERROR");
            } catch (RuntimeException accounting) {
                ex.addSuppressed(accounting);
            }
            throw ex;
        }
    }

    /** 异步运行的业务分派：有 conversationId 走会话，否则走一次性问答。 */
    private Object perform(Request request, String key, AuthenticatedUser user, String id) {
        if (request.conversationId() != null)
            return conversations.send(request.conversationId(), request.revision(), key, request.message(), user, id);
        // 一次性问答：不传 history，模型只看当前 message。
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

    /**
     * 读取最终答案。
     *
     * <p>只允许已成功且有审计 task 的运行；如果属于会话，还会重新读一次会话，
     * 顺带触发历史来源的权限复核，避免把已撤权的内容返回给用户。
     */
    public DeepSeekToolCallingService.ToolChatResult result(String id, AuthenticatedUser user) {
        var row = ledger.owned(id, user);
        if (!"SUCCEEDED".equals(row.get("status")) || row.get("task_id") == null)
            throw RunScope.error("RUN_RESULT_UNAVAILABLE");
        if (row.get("conversation_id") != null) conversations.get(row.get("conversation_id").toString(), user, id);
        return audit.replay(new ChatAuditService.ChatSession(row.get("task_id").toString(), user.userId(), user.role(), id, true, null));
    }

    /**
     * 周期性收敛：处理超时运行、崩溃遗留的 RUNNING 状态和未结清的预留费用。
     *
     * <p>原则是“绝不自动重放已付费的模型调用”：未知用量按预留保守计入，宁可多记费用，也不重复扣费。
     */
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
