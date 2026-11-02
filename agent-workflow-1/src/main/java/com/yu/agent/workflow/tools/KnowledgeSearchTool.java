package com.yu.agent.workflow.tools;

import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.application.KnowledgeBase;
import com.yu.agent.workflow.application.KnowledgeSearchClient;
import com.yu.agent.workflow.application.LocalKnowledgeSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import com.yu.agent.workflow.api.BusinessException;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class KnowledgeSearchTool extends AbstractTool {
    private final KnowledgeSearchClient client;

    @Autowired
    public KnowledgeSearchTool(KnowledgeSearchClient client) {
        this.client = client;
    }

    /** Keeps offline fixtures explicit; production wiring uses KnowledgeSearchClient. */
    public KnowledgeSearchTool(KnowledgeBase knowledgeBase) {
        this.client = new LocalKnowledgeSearchClient(knowledgeBase);
    }
    public String name() { return "knowledge.search"; }
    public boolean readOnly() { return true; }
    public Set<UserRole> allowedRoles() { return Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN); }
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        rejectUnknown(arguments, "query");
        String query = requiredText(arguments, "query");
        var hits = client.search(query, context, 3);
        if (hits.isEmpty()) throw BusinessException.of("NO_EVIDENCE", "No authorized knowledge evidence found");
        var first = hits.get(0);
        return ToolResult.ok("Knowledge search completed", Map.of(
                "query", query,
                "evidence", first.snippet(),
                "citation", first.citation(),
                "hits", hits));
    }
}
