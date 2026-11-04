package com.yu.agent.workflow.api;

import com.yu.agent.workflow.runtime.*;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;

@RestController
@RequestMapping("/api/v1/agent/runs")
@ConditionalOnProperty(name="agent.runtime.enabled",havingValue="true")
public class RuntimeController {
    private final RuntimeService service;private final RuntimeLedger ledger;private final RuntimeEvents events;private final JwtUserResolver users;
    public RuntimeController(RuntimeService service,RuntimeLedger ledger,RuntimeEvents events,JwtUserResolver users) {
        this.service=service;this.ledger=ledger;this.events=events;this.users=users;
    }
    private <T> ApiResponse<T> ok(T data){return ApiResponse.ok(data,UUID.randomUUID().toString());}
    @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="false") boolean active,@AuthenticationPrincipal Jwt jwt){return ok(ledger.list(users.resolve(jwt),active));}
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<?> submit(@Valid @RequestBody RuntimeService.Request request,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Jwt jwt){return ok(service.submit(request,key,users.resolve(jwt)));}
    @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return ok(ledger.describe(id,users.resolve(jwt)));}
    @GetMapping("/{id}/usage") public ApiResponse<?> usage(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return ok(ledger.attempts(id,users.resolve(jwt)));}
    @GetMapping("/{id}/result") public ApiResponse<?> result(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return ok(service.result(id,users.resolve(jwt)));}
    @PostMapping("/{id}/cancel") public ApiResponse<?> cancel(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){ledger.cancel(id,users.resolve(jwt));return ok(ledger.describe(id,users.resolve(jwt)));}
    @GetMapping(value="/{id}/events",produces="text/event-stream")
    public SseEmitter events(@PathVariable String id,@RequestHeader(value="Last-Event-ID",defaultValue="0") String after,
                             @AuthenticationPrincipal Jwt jwt,HttpServletResponse response) {
        long cursor;try{cursor=Long.parseLong(after);}catch(NumberFormatException ex){throw RunScope.error("INVALID_ARGUMENT");}
        if(jwt.getExpiresAt()==null)throw RunScope.error("RUN_IDENTITY_EXPIRED");
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
        return events.subscribe(id,users.resolve(jwt),cursor,jwt.getExpiresAt().toEpochMilli());
    }
}
