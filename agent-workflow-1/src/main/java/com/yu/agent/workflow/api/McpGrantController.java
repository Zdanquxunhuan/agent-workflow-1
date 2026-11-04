package com.yu.agent.workflow.api;

import com.yu.agent.workflow.mcp.McpGrantService;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mcp/shipment/grants")
@ConditionalOnProperty(name="agent.workflow.persistence",havingValue="jdbc")
public class McpGrantController {
    private final McpGrantService grants;
    private final JwtUserResolver users;
    public McpGrantController(McpGrantService grants,JwtUserResolver users) {this.grants=grants;this.users=users;}
    @GetMapping public ApiResponse<McpGrantService.Grant> get(@AuthenticationPrincipal Jwt jwt,@RequestParam String subjectId,@RequestParam String orderId) {
        return ApiResponse.ok(grants.get(users.resolve(jwt),subjectId,orderId),UUID.randomUUID().toString());
    }
    @PutMapping public ApiResponse<McpGrantService.Grant> put(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Update request) {
        return ApiResponse.ok(grants.put(users.resolve(jwt),request.subjectId(),request.orderId(),request.enabled(),request.expiresAt(),
                request.expectedRevision(),request.reason()),UUID.randomUUID().toString());
    }
    public record Update(@NotBlank @Size(max=200) String subjectId,@NotBlank @Size(max=52) String orderId,
                         @NotNull Boolean enabled,@NotNull @Min(0) Long expiresAt,@NotNull @Min(0) Long expectedRevision,
                         @NotBlank @Size(max=256) String reason) {}
}
