package com.yu.agent.workflow.api;

import com.yu.agent.workflow.runtime.*;
import com.yu.agent.workflow.security.JwtUserResolver;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * 对话型运行入口。
 *
 * <p>它管理的是 agent_runs 里的“可暂停运行”，不是会话记录：
 * 模型在执行中缺少订单号、工单号或问题细节时，可以把运行置为 WAITING_INPUT；
 * 用户通过 {@code /{id}/inputs} 补充后，同一个 run 继续执行。
 *
 * <p>两个写接口都返回 202：请求只是“受理/恢复”，真正执行在 worker 线程。
 * 客户端应轮询 {@code GET /api/v1/agent/runs/{id}}，或订阅对应运行的事件流。
 *
 * <p>只有 {@code agent.dialogue.enabled=true} 时这个控制器才会注册；
 * 它还依赖 {@code agent.runtime.enabled=true} 提供的运行账本。
 */
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
    /** 新开一条对话型运行；同一个 Idempotency-Key 重试会返回同一条运行，不会重复执行。 */
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<?> start(@Valid @RequestBody RuntimeService.DialogueRequest body,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Jwt jwt) {
        return ok(runtime.submitDialogue(body,key,users.resolve(jwt)));
    }
    /**
     * 补充信息并恢复同一个运行。
     *
     * <p>{@code revision} 必须等于服务端当前的 dialogue_revision，{@code field} 必须等于当前的
     * clarification_field；旧页面或重复提交会被 CONFLICT / IDEMPOTENCY_CONFLICT 拦下。
     */
    @PostMapping("/{id}/inputs") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<?> input(@PathVariable String id,@Valid @RequestBody RuntimeService.DialogueInput body,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Jwt jwt) {
        return ok(runtime.resumeDialogue(id,body,key,users.resolve(jwt)));
    }
    /** 计划时间线：USER_REQUEST、MISSING_INFORMATION、NO_EVIDENCE、USER_INPUT 等决策记录。 */
    @GetMapping("/{id}/plans")
    public ApiResponse<?> plans(@PathVariable String id,@AuthenticationPrincipal Jwt jwt){return ok(ledger.plans(id,users.resolve(jwt)));}
}
