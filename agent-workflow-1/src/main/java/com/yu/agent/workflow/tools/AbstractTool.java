package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.api.BusinessException;
import java.util.Map;

abstract class AbstractTool implements AgentTool {
    protected String requiredText(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw BusinessException.of("INVALID_ARGUMENT", "Missing or blank argument: " + name);
        }
        if (text.length() > 200) {
            throw BusinessException.of("INVALID_ARGUMENT", "Argument is too long: " + name);
        }
        return text.trim();
    }

    protected void rejectUnknown(Map<String, Object> args, String... allowed) {
        for (String key : args.keySet()) {
            boolean valid = false;
            for (String item : allowed) valid |= item.equals(key);
            if (!valid) throw BusinessException.of("INVALID_ARGUMENT", "Unknown argument: " + key);
        }
    }
}
