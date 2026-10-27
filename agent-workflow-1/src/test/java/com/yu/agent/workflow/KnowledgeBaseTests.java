package com.yu.agent.workflow;

import com.yu.agent.workflow.application.KnowledgeBase;
import com.yu.agent.workflow.domain.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseTests {
    @Test
    void aclIsAppliedBeforeReturningRagHits() {
        KnowledgeBase base = new KnowledgeBase();
        var customerHits = base.search("高金额退款", UserRole.CUSTOMER_SERVICE, 10);
        var supervisorHits = base.search("高金额退款", UserRole.SUPERVISOR, 10);
        assertThat(customerHits).noneMatch(hit -> hit.documentId().equals("policy-internal-refund-v1"));
        assertThat(supervisorHits).anyMatch(hit -> hit.documentId().equals("policy-internal-refund-v1"));
    }

    @Test
    void policyQuestionFindsAuthorizedEvidenceWithoutLeakingDocuments() {
        var hits = new KnowledgeBase().search("退款政策", UserRole.CUSTOMER_SERVICE, 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(hit -> hit.citation().startsWith("policy://"));
    }
}
