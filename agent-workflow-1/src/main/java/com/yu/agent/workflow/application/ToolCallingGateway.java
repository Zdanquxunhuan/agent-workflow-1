package com.yu.agent.workflow.application;

import java.util.List;
import java.util.Map;

public interface ToolCallingGateway {
    ToolCallingResponse complete(List<Map<String, Object>> messages, List<Map<String, Object>> toolSpecifications);
}
