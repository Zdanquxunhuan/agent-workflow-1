package com.yu.agent.workflow.domain;

import java.util.Set;

/** A local, versioned knowledge source with an explicit ACL. */
public record KnowledgeDocument(String id, String title, String content, String citation, Set<UserRole> allowedRoles) {
    public KnowledgeDocument {
        allowedRoles = Set.copyOf(allowedRoles);
    }
}
