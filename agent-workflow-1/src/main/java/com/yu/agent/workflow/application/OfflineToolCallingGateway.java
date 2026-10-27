package com.yu.agent.workflow.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent.deepseek.enabled", havingValue = "false", matchIfMissing = true)
public class OfflineToolCallingGateway implements ToolCallingGateway {
    @Override
    public ToolCallingResponse complete(List<Map<String, Object>> messages, List<Map<String, Object>> toolSpecifications) {
        String answer = "外部模型未启用；当前请求未执行多轮模型 Tool Calling。";
        return new ToolCallingResponse(answer, List.of(), Map.of("role", "assistant", "content", answer));
    }
}
