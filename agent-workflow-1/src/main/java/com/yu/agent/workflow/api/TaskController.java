package com.yu.agent.workflow.api;

import com.yu.agent.workflow.application.AgentApplicationService;
import com.yu.agent.workflow.domain.AgentTaskView;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.security.AuthenticatedUser;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final AgentApplicationService service;
    private final JwtUserResolver users;

    public TaskController(AgentApplicationService service, JwtUserResolver users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentTaskView> create(@Valid @RequestBody CreateTaskRequest request,
                                              @AuthenticationPrincipal Jwt jwt,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                              @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.create(user.userId(), user.role(), request.request(), idempotencyKey, actualTraceId), actualTraceId);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AgentTaskView> get(@PathVariable String taskId, @AuthenticationPrincipal Jwt jwt,
                                          @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        return ApiResponse.ok(service.get(taskId, user.userId(), user.role()), traceIdOrNew(traceId));
    }

    @GetMapping("/{taskId}/events")
    public ApiResponse<List<AuditEvent>> events(@PathVariable String taskId, @AuthenticationPrincipal Jwt jwt,
                                                @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        return ApiResponse.ok(service.events(taskId, user.userId(), user.role()), traceIdOrNew(traceId));
    }

    @PostMapping("/{taskId}/approve")
    public ApiResponse<AgentTaskView> approve(@PathVariable String taskId, @Valid @RequestBody(required = false) DecisionRequest request,
                                              @AuthenticationPrincipal Jwt jwt,
                                              @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.approve(taskId, user.userId(), user.role(), request == null ? null : request.reason(), actualTraceId), actualTraceId);
    }

    @PostMapping("/{taskId}/reject")
    public ApiResponse<AgentTaskView> reject(@PathVariable String taskId, @Valid @RequestBody(required = false) DecisionRequest request,
                                             @AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.reject(taskId, user.userId(), user.role(), request == null ? null : request.reason(), actualTraceId), actualTraceId);
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<AgentTaskView> cancel(@PathVariable String taskId, @AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.cancel(taskId, user.userId(), user.role(), actualTraceId), actualTraceId);
    }

    @PostMapping("/{taskId}/resume")
    public ApiResponse<AgentTaskView> resume(@PathVariable String taskId, @AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthenticatedUser user = users.resolve(jwt);
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.resume(taskId, user.userId(), user.role(), actualTraceId), actualTraceId);
    }

    private String traceIdOrNew(String traceId) { return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId; }

    public record CreateTaskRequest(@NotBlank @Size(max = 2000) String request) {}
    public record DecisionRequest(@Size(max = 500) String reason) {}
}
