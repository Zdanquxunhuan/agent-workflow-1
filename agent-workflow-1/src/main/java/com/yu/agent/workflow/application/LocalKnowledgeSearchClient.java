package com.yu.agent.workflow.application;

import com.yu.agent.workflow.tools.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "agent.knowledge.backend", havingValue = "local", matchIfMissing = true)
public class LocalKnowledgeSearchClient implements KnowledgeSearchClient {
    private final KnowledgeBase knowledgeBase;

    public LocalKnowledgeSearchClient(KnowledgeBase knowledgeBase) { this.knowledgeBase = knowledgeBase; }

    public List<Hit> search(String query, ToolContext context, int limit) {
        return knowledgeBase.search(query, context.role(), limit).stream()
                .map(hit -> new Hit(hit.documentId(), hit.title(), hit.snippet(), hit.citation(),
                        hit.score(), "local-demo", hit.documentId())).toList();
    }
}

