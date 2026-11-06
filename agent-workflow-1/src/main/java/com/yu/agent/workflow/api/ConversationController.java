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

/**
 * 会话入口：管理 conversation（会话容器），并在其中追加 turn（一轮问答）。
 *
 * <p>会话本身只负责“保存聊天记录 + 控制版本”，真正的 Agent 执行在 {@link ConversationService}。
 * 每一轮 send 都会启动一次新的运行，而不是维持一个长期存活的模型会话；
 * “多轮”是应用层通过历史窗口拼出来的。
 *
 * <p>并发安全依赖两个东西：每个 turn 的 {@code Idempotency-Key} 防重复扣费，
 * 以及 conversation 的 {@code revision} 做 CAS 防两个客户端互相覆盖。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.yu.agent.workflow.runtime.RuntimeService> runtime;
    private final ConversationService service;
    private final JwtUserResolver users;
    public ConversationController(ConversationService service, JwtUserResolver users) { this.service = service; this.users = users; }
    /** 新建会话；保留期默认 7 天，过期后不能继续写入。 */
    @PostMapping public ApiResponse<Conversation> create(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(service.create(users.resolve(jwt)), UUID.randomUUID().toString());
    }
    /** 只列出当前用户自己的会话，不返回其他参与者的会话列表。 */
    @GetMapping public ApiResponse<List<ConversationService.Listing>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(service.list(users.resolve(jwt)), UUID.randomUUID().toString());
    }
    /** 读取会话；返回前会重新校验历史中引用过的知识/记忆来源是否仍有权限。 */
    @GetMapping("/{id}") public ApiResponse<Conversation> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String trace = UUID.randomUUID().toString();
        return ApiResponse.ok(service.get(id, users.resolve(jwt), trace), trace);
    }
    /**
     * 在会话中追加一轮消息。
     *
     * <p>{@code revision} 是客户端上次读到的会话版本，服务端用它做 CAS；
     * {@code Idempotency-Key} 是这一轮 turn 的幂等键，网络重试不会重复扣费。
     */
    @PostMapping("/{id}/messages") public ApiResponse<ConversationService.Reply> send(@PathVariable String id,
            @Valid @RequestBody Message request, @RequestHeader("Idempotency-Key") String key, @AuthenticationPrincipal Jwt jwt) {
        String trace = UUID.randomUUID().toString();
        if(runtime!=null && runtime.getIfAvailable()!=null) {
            var user=users.resolve(jwt);
            // 运行时开启：先开运行单，再执行 send；这样这一轮也具备预算、截止时间、取消能力。
            return ApiResponse.ok(runtime.getObject().synchronous(new com.yu.agent.workflow.runtime.RuntimeService.Request(request.message(),id,request.revision()),key,user,
                    runId->service.send(id,request.revision(),key,request.message(),user,runId)),trace);
        }
        // 运行时未开启：直接执行，仍保留 turn 级幂等和 revision CAS。
        return ApiResponse.ok(service.send(id, request.revision(), key, request.message(), users.resolve(jwt), trace), trace);
    }
    /** 修改参与者；只有 owner 能写，参与者只能读。 */
    @PutMapping("/{id}/participants") public ApiResponse<Conversation> participants(@PathVariable String id,
            @Valid @RequestBody Participants request, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(service.participants(id, request.revision(), request.subjects(), users.resolve(jwt)), UUID.randomUUID().toString());
    }
    /** 取消最后一轮仍在 RUNNING 的消息，并同步取消对应的运行单。 */
    @PostMapping("/{id}/cancel") public ApiResponse<Void> cancel(@PathVariable String id,
            @Valid @RequestBody Revision request, @AuthenticationPrincipal Jwt jwt) {
        service.cancel(id, request.revision(), users.resolve(jwt));
        return ApiResponse.ok(null, UUID.randomUUID().toString());
    }
    /** 逻辑删除会话；仍要求 revision 匹配，避免误删刚被其他客户端修改过的会话。 */
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id,
            @RequestParam long revision, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, revision, users.resolve(jwt)); return ApiResponse.ok(null, UUID.randomUUID().toString());
    }
    public record Message(@PositiveOrZero long revision, @NotBlank @Size(max=2000) String message) {}
    public record Revision(@PositiveOrZero long revision) {}
    public record Participants(@PositiveOrZero long revision, @NotNull @Size(max=10) Set<String> subjects) {}
}
