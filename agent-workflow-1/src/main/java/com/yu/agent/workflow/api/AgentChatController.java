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
            String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey;
            var result = runtime.getObject().synchronous(new com.yu.agent.workflow.runtime.RuntimeService.Request(request.message(), null, 0), key, user,
                    runId -> executeChat(request, user, "run-" + runId, runId));
            return ApiResponse.ok(result, actualTraceId);
        }
        return ApiResponse.ok(executeChat(request, user, idempotencyKey, actualTraceId), actualTraceId);
    }

    private DeepSeekToolCallingService.ToolChatResult executeChat(ChatRequest request, AuthenticatedUser user, String idempotencyKey, String actualTraceId) {
        ChatAuditService.ChatSession session = audit.start(user.userId(), user.role(), request.message(), idempotencyKey, actualTraceId);
        if (session.reused()) {
            return audit.replay(session);
        }
        try {
            DeepSeekToolCallingService.ToolChatResult result = service.chat(
                    user.userId(), user.role(), request.message(), actualTraceId, session.taskId(), audit.listener(session));
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

    @GetMapping("/runtime")
    public ApiResponse<java.util.Map<String, Object>> runtime() {
        return ApiResponse.ok(java.util.Map.of("enabled", runtime != null && runtime.getIfAvailable() != null,
                "streamMode", "progress-only", "disconnectPolicy", "observe-or-reconnect; explicit cancel stops execution"), UUID.randomUUID().toString());
    }
}
