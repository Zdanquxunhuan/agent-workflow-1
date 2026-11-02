package com.yu.agent.workflow.api;

import com.yu.agent.workflow.security.JwtUserResolver;
import com.yu.agent.workflow.tools.ToolContext;
import com.yu.agent.workflow.tools.ToolRegistry;
import com.yu.agent.workflow.tools.ToolResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

/** Stateless RAG bridge. Avoids storing tenant evidence in the legacy unscoped task ledger. */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final ToolRegistry registry;
    private final JwtUserResolver users;

    public KnowledgeController(ToolRegistry registry, JwtUserResolver users) {
        this.registry = registry;
        this.users = users;
    }

    @PostMapping("/search")
    public ApiResponse<ToolResult> search(@Valid @RequestBody SearchRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        var user = users.resolve(jwt);
        String traceId = UUID.randomUUID().toString();
        var result = registry.execute("knowledge.search", Map.of("query", request.query()),
                new ToolContext(user.userId(), user.role(), "rag-stateless", traceId));
        return ApiResponse.ok(result, traceId);
    }

    public record SearchRequest(@NotBlank @Size(max = 2000) String query) {}
}
