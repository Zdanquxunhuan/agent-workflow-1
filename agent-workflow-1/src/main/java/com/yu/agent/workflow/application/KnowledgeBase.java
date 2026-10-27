package com.yu.agent.workflow.application;

import com.yu.agent.workflow.domain.KnowledgeDocument;
import com.yu.agent.workflow.domain.UserRole;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small local RAG implementation for the simulated project. ACL filtering is
 * performed before ranking and snippet creation, so unauthorized documents
 * cannot leak through search results.
 */
@Component
public class KnowledgeBase {
    private final List<KnowledgeDocument> documents = List.of(
            new KnowledgeDocument("policy-after-sales-v1", "售后政策", "已发货订单退款需主管审批；地址修改仅在发货前允许。 Delivered order refund policy requires supervisor approval.", "policy://after-sales/v1",
                    Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN)),
            new KnowledgeDocument("policy-internal-refund-v1", "退款审核内部规则", "高金额退款需要主管复核并保留审批理由。", "policy://internal/refund/v1",
                    Set.of(UserRole.SUPERVISOR, UserRole.ADMIN)),
            new KnowledgeDocument("policy-delivery-v1", "配送说明", "物流查询只返回当前用户授权订单的必要运输信息。", "policy://delivery/v1",
                    Set.of(UserRole.CUSTOMER_SERVICE, UserRole.SUPERVISOR, UserRole.ADMIN)));

    public List<SearchHit> search(String query, UserRole role, int limit) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank()) return List.of();
        return documents.stream()
                .filter(document -> document.allowedRoles().contains(role) || role == UserRole.ADMIN)
                .map(document -> Map.entry(document, score(document, normalized)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<KnowledgeDocument, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(1, limit))
                .map(entry -> new SearchHit(entry.getKey().id(), entry.getKey().title(), snippet(entry.getKey(), normalized),
                        entry.getKey().citation(), entry.getValue()))
                .toList();
    }

    private int score(KnowledgeDocument document, String query) {
        String corpus = (document.title() + " " + document.content()).toLowerCase(Locale.ROOT);
        int score = corpus.contains(query) ? 3 : 0;
        for (String token : query.split("\\s+")) if (!token.isBlank() && corpus.contains(token)) score++;
        if (query.length() <= 16) {
            for (int i = 0; i < query.length(); i++) {
                String character = String.valueOf(query.charAt(i));
                if (!Character.isWhitespace(query.charAt(i)) && corpus.contains(character)) score++;
            }
        }
        return score;
    }

    private String snippet(KnowledgeDocument document, String query) {
        String content = document.content();
        int index = content.toLowerCase(Locale.ROOT).indexOf(query);
        if (index < 0) return content;
        int start = Math.max(0, index - 30);
        int end = Math.min(content.length(), index + query.length() + 80);
        return content.substring(start, end);
    }

    public record SearchHit(String documentId, String title, String snippet, String citation, int score) {}
}
