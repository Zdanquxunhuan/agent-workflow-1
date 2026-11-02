package com.yu.agent.workflow.api;

import com.yu.agent.workflow.commerce.IntegrationOperationsService;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations")
@ConditionalOnExpression("'${agent.workflow.persistence:memory}' == 'jdbc' and '${agent.commerce.dispatcher.enabled:true}' == 'true'")
public class IntegrationOperationsController {
    private final IntegrationOperationsService operations;
    private final JwtUserResolver users;
    public IntegrationOperationsController(IntegrationOperationsService operations, JwtUserResolver users) { this.operations=operations; this.users=users; }

    @GetMapping("/commands")
    public ApiResponse<List<Map<String,Object>>> commands(@RequestParam String taskId, @AuthenticationPrincipal Jwt jwt) {
        var user = users.resolve(jwt);
        return ApiResponse.ok(operations.commands(taskId,user.userId(),user.role()), UUID.randomUUID().toString());
    }
    @GetMapping("/commands/{id}")
    public ApiResponse<Map<String,Object>> command(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        var user = users.resolve(jwt);
        return ApiResponse.ok(operations.command(id,user.userId(),user.role()), UUID.randomUUID().toString());
    }
    @GetMapping("/reviews")
    public ApiResponse<List<Map<String,Object>>> reviews(@RequestParam(defaultValue="OPEN") String status, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(operations.reviews(status,users.resolve(jwt).role()), UUID.randomUUID().toString());
    }
    @PostMapping("/reviews/{id}/recheck")
    public ApiResponse<Map<String,Object>> recheck(@PathVariable String id, @Valid @RequestBody OperationRequest request, @AuthenticationPrincipal Jwt jwt) {
        var user = users.resolve(jwt);
        return ApiResponse.ok(operations.recheck(id,user.userId(),user.role(),request.reason()), UUID.randomUUID().toString());
    }
    @PostMapping("/reviews/{id}/acknowledge-failure")
    public ApiResponse<Map<String,Object>> acknowledge(@PathVariable String id, @Valid @RequestBody OperationRequest request, @AuthenticationPrincipal Jwt jwt) {
        var user = users.resolve(jwt);
        return ApiResponse.ok(operations.acknowledgeFailure(id,user.userId(),user.role(),request.reason()), UUID.randomUUID().toString());
    }
    public record OperationRequest(@NotBlank @Size(min=5,max=500) String reason) {}
}
