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
        ChatAuditService.ChatSession session = audit.start(user.userId(), user.role(), request.message(), idempotencyKey, actualTraceId);
        if (session.reused()) {
            return ApiResponse.ok(audit.replay(session), actualTraceId);
        }
        try {
            DeepSeekToolCallingService.ToolChatResult result = service.chat(
                    user.userId(), user.role(), request.message(), actualTraceId, session.taskId(), audit.listener(session));
            audit.complete(session, result);
            return ApiResponse.ok(result, actualTraceId);
        } catch (RuntimeException ex) {
            audit.fail(session, ex);
            throw ex;
        }
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}
}
