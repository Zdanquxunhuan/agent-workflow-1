package com.yu.agent.workflow.api;

import com.yu.agent.workflow.application.RagTaskAccess;
import com.yu.agent.workflow.security.JwtUserResolver;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class KnowledgeDelegationController {
    private final RagTaskAccess access;
    private final JwtUserResolver users;
    public KnowledgeDelegationController(RagTaskAccess access, JwtUserResolver users) { this.access=access; this.users=users; }
    @DeleteMapping("/{id}/knowledge-grant")
    public ApiResponse<Void> revoke(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        access.revoke(id, users.resolve(jwt).userId());
        return ApiResponse.ok(null, java.util.UUID.randomUUID().toString());
    }
}
