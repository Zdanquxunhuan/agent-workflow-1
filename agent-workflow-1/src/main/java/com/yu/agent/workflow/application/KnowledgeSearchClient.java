package com.yu.agent.workflow.application;

import com.yu.agent.workflow.tools.ToolContext;
import java.util.List;

public interface KnowledgeSearchClient {
    List<Hit> search(String query, ToolContext context, int limit);
    default void revalidateTask(ToolContext context) {}
    default void prepareTask(ToolContext context) {}

    record Hit(String documentId, String title, String snippet, String citation, double score,
               String documentVersionId, String chunkId) {}
}

