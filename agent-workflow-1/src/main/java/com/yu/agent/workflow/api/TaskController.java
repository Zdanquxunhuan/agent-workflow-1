package com.yu.agent.workflow.api;

import com.yu.agent.workflow.application.AgentApplicationService;
import com.yu.agent.workflow.domain.AgentTaskView;
import com.yu.agent.workflow.domain.AuditEvent;
import com.yu.agent.workflow.domain.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final AgentApplicationService service;
    public TaskController(AgentApplicationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentTaskView> create(@Valid @RequestBody CreateTaskRequest request,
                                              @RequestHeader("X-User-Id") String userId,
                                              @RequestHeader(value = "X-Role", defaultValue = "CUSTOMER_SERVICE") String role,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                              @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.create(userId, UserRole.parse(role), request.request(), idempotencyKey, actualTraceId), actualTraceId);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AgentTaskView> get(@PathVariable String taskId, @RequestHeader("X-User-Id") String userId,
                                          @RequestHeader(value = "X-Role", defaultValue = "CUSTOMER_SERVICE") String role,
                                          @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(service.get(taskId, userId, UserRole.parse(role)), traceIdOrNew(traceId));
    }

    @GetMapping("/{taskId}/events")
    public ApiResponse<List<AuditEvent>> events(@PathVariable String taskId, @RequestHeader("X-User-Id") String userId,
                                                @RequestHeader(value = "X-Role", defaultValue = "CUSTOMER_SERVICE") String role,
                                                @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(service.events(taskId, userId, UserRole.parse(role)), traceIdOrNew(traceId));
    }

    @PostMapping("/{taskId}/approve")
    public ApiResponse<AgentTaskView> approve(@PathVariable String taskId, @Valid @RequestBody(required = false) DecisionRequest request,
                                              @RequestHeader("X-User-Id") String userId,
                                              @RequestHeader(value = "X-Role", defaultValue = "CUSTOMER_SERVICE") String role,
                                              @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.approve(taskId, userId, UserRole.parse(role), request == null ? null : request.reason(), actualTraceId), actualTraceId);
    }

    @PostMapping("/{taskId}/reject")
    public ApiResponse<AgentTaskView> reject(@PathVariable String taskId, @Valid @RequestBody(required = false) DecisionRequest request,
                                             @RequestHeader("X-User-Id") String userId,
                                             @RequestHeader(value = "X-Role", defaultValue = "CUSTOMER_SERVICE") String role,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.reject(taskId, userId, UserRole.parse(role), request == null ? null : request.reason(), actualTraceId), actualTraceId);
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<AgentTaskView> cancel(@PathVariable String taskId, @RequestHeader("X-User-Id") String userId,
                                             @RequestHeader(value = "X-Role", defaultValue = "CUSTOMER_SERVICE") String role,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.cancel(taskId, userId, UserRole.parse(role), actualTraceId), actualTraceId);
    }

    @PostMapping("/{taskId}/resume")
    public ApiResponse<AgentTaskView> resume(@PathVariable String taskId, @RequestHeader("X-User-Id") String userId,
                                             @RequestHeader(value = "X-Role", defaultValue = "CUSTOMER_SERVICE") String role,
                                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String actualTraceId = traceIdOrNew(traceId);
        return ApiResponse.ok(service.resume(taskId, userId, UserRole.parse(role), actualTraceId), actualTraceId);
    }

    private String traceIdOrNew(String traceId) { return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId; }

    public record CreateTaskRequest(@NotBlank @Size(max = 2000) String request) {}
    public record DecisionRequest(@Size(max = 500) String reason) {}
}
