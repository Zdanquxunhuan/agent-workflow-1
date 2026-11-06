package com.yu.agent.workflow.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.dialogue.enabled", havingValue = "true")
public class DialogueSettings {
    public final int maxReplans, maxClarifications, waitSeconds, lifetimeSeconds;

    public DialogueSettings(Environment env, RuntimeSettings runtime) {
        maxReplans = limit(env, "max-replans", 2, 1, 2);
        maxClarifications = limit(env, "max-clarifications", 3, 1, 3);
        waitSeconds = limit(env, "wait-seconds", 900, 60, 1800);
        lifetimeSeconds = limit(env, "lifetime-seconds", 1800, 60, 3600);
        if (lifetimeSeconds < waitSeconds || lifetimeSeconds < runtime.seconds)
            throw new IllegalStateException("Dialogue lifetime must cover wait and execution limits");
    }

    private int limit(Environment env, String key, int fallback, int min, int max) {
        int value = env.getProperty("agent.dialogue." + key, Integer.class, fallback);
        if (value < min || value > max) throw new IllegalStateException("Invalid dialogue limit: " + key);
        return value;
    }
}
