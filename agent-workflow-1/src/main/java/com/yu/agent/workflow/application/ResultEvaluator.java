package com.yu.agent.workflow.application;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.tools.AgentTool;
import com.yu.agent.workflow.tools.ToolResult;
import org.springframework.stereotype.Component;

/** Keeps HTTP/tool success separate from business completion. */
@Component
public class ResultEvaluator {
    public void verify(AgentTool tool, ToolResult result) {
        if (!result.success()) return;
        boolean verifiable = Boolean.TRUE.equals(result.data().get("verified"))
                || Boolean.TRUE.equals(result.data().get("simulation"));
        if (!tool.readOnly() && !verifiable) {
            throw BusinessException.of("RESULT_NOT_VERIFIED", "Write tool did not return a verifiable business result");
        }
    }
}
