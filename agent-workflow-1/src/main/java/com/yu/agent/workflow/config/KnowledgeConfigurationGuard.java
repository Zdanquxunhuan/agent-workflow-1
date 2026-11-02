package com.yu.agent.workflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class KnowledgeConfigurationGuard {
    public KnowledgeConfigurationGuard(@Value("${agent.knowledge.backend:local}") String backend,
            @Value("${agent.knowledge.http.base-url:}") String baseUrl, Environment environment) {
        if (!Set.of("local", "http").contains(backend))
            throw new IllegalStateException("agent.knowledge.backend must be local or http");
        if (environment.acceptsProfiles(Profiles.of("prod")) && (!backend.equals("http") || !baseUrl.startsWith("https://")))
            throw new IllegalStateException("Production knowledge search requires http backend and HTTPS RAG URL");
    }
}

