package com.yu.agent.workflow.api;

import com.yu.agent.workflow.application.DeepSeekToolCallingService;
import com.yu.agent.workflow.application.ChatAuditService;
import com.yu.agent.workflow.security.AuthenticatedUser;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 一次性问答入口。
 *
 * <p>一次请求就是一次独立运行：不创建、不读取 conversation，也不会把上一轮回答带进提示词。
 * 因此“查询订单 ORD-1001”和紧接着的“那运费呢”是两次互不相干的请求；后者必须自己再提供订单号。
 *
 * <p>开启 {@code agent.runtime.enabled} 时，请求先经过 {@link com.yu.agent.workflow.runtime.RuntimeService}：
 * 获得运行工单、幂等键、预算、截止时间和取消能力；未开启时直接调用审计和模型服务，
 * 仍然保留任务审计，但没有运行账本。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentChatController {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.yu.agent.workflow.runtime.RuntimeService> runtime;
    private final DeepSeekToolCallingService service;
    private final ChatAuditService audit;
    private final JwtUserResolver users;

    public AgentChatController(DeepSeekToolCallingService service, ChatAuditService audit,
                               JwtUserResolver users) {
        this.service = service;
        this.audit = audit;
        this.users = users;
    }

    @PostMapping("/chat")
    public ApiResponse<DeepSeekToolCallingService.ToolChatResult> chat(@Valid @RequestBody ChatRequest request,
                                                                       @AuthenticationPrincipal Jwt jwt,
                                                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                                       @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        String actualTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        if (runtime != null && runtime.getIfAvailable() != null) {
            // 未传幂等键时生成一个；客户端重试应复用同一个 key，避免重复计费。
            String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey;
            // conversationId=null 是“一次性”的关键：运行入口记为 chat，不关联任何会话，
            // 执行时也不会构建历史上下文。
            var result = runtime.getObject().synchronous(new com.yu.agent.workflow.runtime.RuntimeService.Request(request.message(), null, 0), key, user,
                    runId -> executeChat(request, user, "run-" + runId, runId));
            return ApiResponse.ok(result, actualTraceId);
        }
        // 运行时未开启：没有运行账本，但仍有 ChatAuditService 的任务级幂等和审计。
        return ApiResponse.ok(executeChat(request, user, idempotencyKey, actualTraceId), actualTraceId);
    }

    /**
     * 一次性问答的核心执行：先建审计任务，再调用模型工具循环，最后把“业务成功”和“运行成功”一起提交。
     *
     * <p>这里没有 history 参数，所以模型每次只能看到当前这一条 message。
     */
    private DeepSeekToolCallingService.ToolChatResult executeChat(ChatRequest request, AuthenticatedUser user, String idempotencyKey, String actualTraceId) {
        ChatAuditService.ChatSession session = audit.start(user.userId(), user.role(), request.message(), idempotencyKey, actualTraceId);
        // reused=true 表示同一个幂等键已经完成过：直接重放已保存的答案，不再产生模型费用。
        if (session.reused()) {
            return audit.replay(session);
        }
        try {
            // 不传 history，等价于给模型一个“只有本轮问题”的空上下文。
            DeepSeekToolCallingService.ToolChatResult result = service.chat(
                    user.userId(), user.role(), request.message(), actualTraceId, session.taskId(), audit.listener(session));
            // publish 会把任务完成、答案落库和运行状态 SUCCEEDED 放在同一个事务里。
            return com.yu.agent.workflow.runtime.RunScope.publish(() -> {
                audit.complete(session, result);
                return result;
            });
        } catch (RuntimeException ex) {
            audit.fail(session, ex);
            throw ex;
        }
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {
    }

    /**
     * 能力探测：前端据此决定走“运行时异步提交 + 进度订阅”还是“同步直达”。
     *
     * <p>进度流只负责状态提示，最终答案仍从 result 接口读取，避免把未校验的文本当成业务结果。
     */
    @GetMapping("/runtime")
    public ApiResponse<java.util.Map<String, Object>> runtime() {
        return ApiResponse.ok(java.util.Map.of("enabled", runtime != null && runtime.getIfAvailable() != null,
                "streamMode", "progress-only", "disconnectPolicy", "observe-or-reconnect; explicit cancel stops execution"), UUID.randomUUID().toString());
    }
}
