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
        if (!tool.readOnly() && !Boolean.TRUE.equals(result.data().get("simulation"))) {
            throw BusinessException.of("RESULT_NOT_VERIFIED", "Write tool did not return a verifiable simulation result");
        }
    }
}
