package com.yu.agent.workflow.api;

import com.yu.agent.workflow.application.ConversationService;
import com.yu.agent.workflow.domain.Conversation;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.yu.agent.workflow.runtime.RuntimeService> runtime;
    private final ConversationService service;
    private final JwtUserResolver users;
    public ConversationController(ConversationService service, JwtUserResolver users) { this.service = service; this.users = users; }
    @PostMapping public ApiResponse<Conversation> create(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(service.create(users.resolve(jwt)), UUID.randomUUID().toString());
    }
    @GetMapping public ApiResponse<List<ConversationService.Listing>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(service.list(users.resolve(jwt)), UUID.randomUUID().toString());
    }
    @GetMapping("/{id}") public ApiResponse<Conversation> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String trace = UUID.randomUUID().toString();
        return ApiResponse.ok(service.get(id, users.resolve(jwt), trace), trace);
    }
    @PostMapping("/{id}/messages") public ApiResponse<ConversationService.Reply> send(@PathVariable String id,
            @Valid @RequestBody Message request, @RequestHeader("Idempotency-Key") String key, @AuthenticationPrincipal Jwt jwt) {
        String trace = UUID.randomUUID().toString();
        if(runtime!=null && runtime.getIfAvailable()!=null) {
            var user=users.resolve(jwt);
            return ApiResponse.ok(runtime.getObject().synchronous(new com.yu.agent.workflow.runtime.RuntimeService.Request(request.message(),id,request.revision()),key,user,
                    runId->service.send(id,request.revision(),key,request.message(),user,runId)),trace);
        }
        return ApiResponse.ok(service.send(id, request.revision(), key, request.message(), users.resolve(jwt), trace), trace);
    }
    @PutMapping("/{id}/participants") public ApiResponse<Conversation> participants(@PathVariable String id,
            @Valid @RequestBody Participants request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(service.participants(id, request.revision(), request.subjects(), users.resolve(jwt)), UUID.randomUUID().toString());
    }
    @PostMapping("/{id}/cancel") public ApiResponse<Void> cancel(@PathVariable String id,
            @Valid @RequestBody Revision request, @AuthenticationPrincipal Jwt jwt) {
        service.cancel(id, request.revision(), users.resolve(jwt));
        return ApiResponse.ok(null, UUID.randomUUID().toString());
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id,
            @RequestParam long revision, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, revision, users.resolve(jwt)); return ApiResponse.ok(null, UUID.randomUUID().toString());
    }
    public record Message(@PositiveOrZero long revision, @NotBlank @Size(max=2000) String message) {}
    public record Revision(@PositiveOrZero long revision) {}
    public record Participants(@PositiveOrZero long revision, @NotNull @Size(max=10) Set<String> subjects) {}
}
