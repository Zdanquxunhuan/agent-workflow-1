package com.yu.agent.workflow.api;

import com.yu.agent.workflow.runtime.*;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent/dialogues")
@ConditionalOnProperty(name="agent.dialogue.enabled",havingValue="true")
public class DialogueController {
    private final RuntimeService runtime;
    private final RuntimeLedger ledger;
    private final JwtUserResolver users;
    public DialogueController(RuntimeService runtime,RuntimeLedger ledger,JwtUserResolver users) {
        this.runtime=runtime;this.ledger=ledger;this.users=users;
    }
    private ApiResponse<?> ok(Object value){return ApiResponse.ok(value,UUID.randomUUID().toString());}
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<?> start(@Valid @RequestBody RuntimeService.DialogueRequest body,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Jwt jwt) {
        return ok(runtime.submitDialogue(body,key,users.resolve(jwt)));
    }
    @PostMapping("/{id}/inputs") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<?> input(@PathVariable String id,@Valid @RequestBody RuntimeService.DialogueInput body,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Jwt jwt) {
        return ok(runtime.resumeDialogue(id,body,key,users.resolve(jwt)));
    }
    @GetMapping("/{id}/plans")
    public ApiResponse<?> plans(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return ok(ledger.plans(id,users.resolve(jwt)));}
}
