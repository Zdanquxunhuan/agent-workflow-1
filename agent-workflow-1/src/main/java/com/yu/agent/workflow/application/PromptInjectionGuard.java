package com.yu.agent.workflow.application;

import com.yu.agent.workflow.api.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Rejects common attempts to turn user/model text into execution instructions. */
@Component
public class PromptInjectionGuard {
    private static final List<String> BLOCKED_MARKERS = List.of(
            "ignore previous instructions", "ignore all previous", "忽略之前的指令", "忽略系统提示",
            "reveal the system prompt", "显示系统提示", "绕过审批", "bypass approval",
            "reveal api key", "泄露密钥", "调用未注册工具", "call an unregistered tool");

    public void check(String input) {
        if (input == null) return;
        String normalized = input.toLowerCase(Locale.ROOT);
        for (String marker : BLOCKED_MARKERS) {
            if (normalized.contains(marker)) {
                throw BusinessException.of("PROMPT_INJECTION_BLOCKED", "Request contains a blocked instruction pattern");
            }
        }
    }
}
