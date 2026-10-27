package com.yu.agent.workflow.api;

import com.yu.agent.workflow.application.DeepSeekToolCallingService;
import com.yu.agent.workflow.application.ChatAuditService;
import com.yu.agent.workflow.domain.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentChatController {
    private final DeepSeekToolCallingService service;
    private final ChatAuditService audit;

    public AgentChatController(DeepSeekToolCallingService service, ChatAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/chat")
    public ApiResponse<DeepSeekToolCallingService.ToolChatResult> chat(@Valid @RequestBody ChatRequest request,
                                                                        @RequestHeader("X-User-Id") String userId,
                                                                        @RequestHeader(value = "X-Role", defaultValue = "CUSTOMER_SERVICE") String role,
                                                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String actualTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        UserRole actualRole = UserRole.parse(role);
        ChatAuditService.ChatSession session = audit.start(userId, actualRole, request.message(), idempotencyKey, actualTraceId);
        if (session.reused()) {
            return ApiResponse.ok(audit.replay(session), actualTraceId);
        }
        try {
            DeepSeekToolCallingService.ToolChatResult result = service.chat(
                    userId, actualRole, request.message(), actualTraceId, session.taskId(), audit.listener(session));
            audit.complete(session, result);
            return ApiResponse.ok(result, actualTraceId);
        } catch (RuntimeException ex) {
            audit.fail(session, ex);
            throw ex;
        }
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}
}
