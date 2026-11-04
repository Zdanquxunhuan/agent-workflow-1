package com.yu.agent.workflow.api;

import com.yu.agent.workflow.memory.*;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/memories")
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
public class MemoryController {
    private final MemoryService service;private final JwtUserResolver users;
    public MemoryController(MemoryService service,JwtUserResolver users){this.service=service;this.users=users;}
    private <T> ApiResponse<T> ok(T value){return ApiResponse.ok(value,UUID.randomUUID().toString());}
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="") String after){
        if(after.length()>36)throw BusinessException.of("INVALID_ARGUMENT","Invalid cursor");
        return ok(service.list(users.resolve(jwt),after));
    }
    @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return ok(service.get(id,users.resolve(jwt)));}
    @PostMapping public ApiResponse<?> create(@Valid @RequestBody Preference request,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Jwt jwt){
        return ok(service.createPreference(users.resolve(jwt),request.slot(),request.value(),request.days(),key));
    }
    @PostMapping("/{id}/confirm") public ApiResponse<?> confirm(@PathVariable String id,@Valid @RequestBody Revision request,@AuthenticationPrincipal Jwt jwt){
        return ok(service.change(id,request.revision(),"confirm",null,0,users.resolve(jwt)));
    }
    @PutMapping("/{id}") public ApiResponse<?> edit(@PathVariable String id,@Valid @RequestBody Edit request,@AuthenticationPrincipal Jwt jwt){
        return ok(service.change(id,request.revision(),"edit",request.content(),request.days(),users.resolve(jwt)));
    }
    @DeleteMapping("/{id}") public ApiResponse<?> delete(@PathVariable String id,@RequestParam long revision,@AuthenticationPrincipal Jwt jwt){
        return ok(service.change(id,revision,"delete",null,0,users.resolve(jwt)));
    }
    @PostMapping("/extractions") public ApiResponse<?> extract(@Valid @RequestBody Extraction request,@AuthenticationPrincipal Jwt jwt){
        return ok(Map.of("jobId",service.extract(request.taskId(),users.resolve(jwt))));
    }
    @PostMapping("/recall") public ApiResponse<?> recall(@Valid @RequestBody Recall request,@AuthenticationPrincipal Jwt jwt){return ok(service.recall(users.resolve(jwt),request.query()));}
    @GetMapping("/jobs") public ApiResponse<?> jobs(@AuthenticationPrincipal Jwt jwt){return ok(service.jobs(users.resolve(jwt)));}
    @PostMapping("/jobs/{id}/retry") public ApiResponse<?> retry(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){service.retry(id,users.resolve(jwt));return ok(Map.of("queued",true));}
    @GetMapping("/{id}/events") public ApiResponse<?> events(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return ok(service.events(id,users.resolve(jwt)));}
    @PostMapping("/{id}/reindex") public ApiResponse<?> reindex(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){service.reindex(id,users.resolve(jwt));return ok(Map.of("queued",true));}
    @PostMapping("/{id}/reauthorize") public ApiResponse<?> reauthorize(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){service.reauthorize(id,users.resolve(jwt));return ok(Map.of("renewed",true));}
    @GetMapping("/settings") public ApiResponse<?> settings(@AuthenticationPrincipal Jwt jwt){return ok(service.settings(users.resolve(jwt)));}
    @PutMapping("/settings") public ApiResponse<?> settings(@Valid @RequestBody Settings request,@AuthenticationPrincipal Jwt jwt){service.settings(users.resolve(jwt),request.automaticExtraction());return ok(service.settings(users.resolve(jwt)));}
    public record Preference(@NotBlank String slot,@NotBlank String value,@Min(1) @Max(365) int days){}
    public record Revision(@Min(1) long revision){}
    public record Edit(@Min(1) long revision,@NotBlank @Size(max=1000) String content,@Min(1) @Max(365) int days){}
    public record Extraction(@NotBlank @Size(max=64) String taskId){}
    public record Recall(@NotBlank @Size(max=2000) String query){}
    public record Settings(@NotNull Boolean automaticExtraction){}
}
