package com.yu.agent.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.tools.ToolResult;
import java.util.*;

/** Checks source membership and exact excerpts; does not claim semantic entailment. */
public final class GroundedAnswer {
    private record Evidence(String text, String citation, boolean knowledge) {}
    private final Map<String, Evidence> sources = new LinkedHashMap<>();
    private final ObjectMapper json;
    public GroundedAnswer(ObjectMapper json) { this.json = json; }
    public void add(String callId, String tool, ToolResult result) {
        if (!result.success()) throw BusinessException.of("TOOL_FAILED", "Read-only tool failed");
        if ("knowledge.search".equals(tool)) {
            for (var hit : json.valueToTree(result.data()).path("hits")) {
                String id = hit.path("chunkId").asText();
                if (id.isBlank()) throw BusinessException.of("ANSWER_INVALID_CITATION", "Versioned knowledge evidence required");
                sources.put(id, new Evidence(hit.path("snippet").asText(), hit.path("citation").asText(), true));
            }
        } else sources.put(callId, new Evidence(json.valueToTree(result.data()).toString(), "tool:" + callId, false));
    }
    public String render(String content) {
        if (sources.isEmpty()) throw BusinessException.of("NO_EVIDENCE", "No tool evidence available");
        try {
            var root = json.readTree(content);
            if (root.size() != 1 || !root.path("statements").isArray() || root.path("statements").isEmpty()
                    || root.path("statements").size() > 12) throw new IllegalArgumentException();
            List<String> rendered = new ArrayList<>();
            boolean citesKnowledge = false;
            for (var statement : root.path("statements")) {
                String text = statement.path("text").asText();
                String quote = statement.path("quote").asText();
                String id = statement.path("sourceId").asText();
                Evidence evidence = sources.get(id);
                if (statement.size() != 3 || text.isBlank() || text.length() > 2000 || quote.isBlank()
                        || evidence == null || !evidence.text().contains(quote)) throw new IllegalArgumentException();
                citesKnowledge |= evidence.knowledge();
                rendered.add(text + " [" + id + "]\n原文：" + quote + "\n来源：" + evidence.citation());
            }
            if (sources.values().stream().anyMatch(Evidence::knowledge) && !citesKnowledge) throw new IllegalArgumentException();
            return String.join("\n\n", rendered);
        } catch (Exception ex) {
            throw BusinessException.of("ANSWER_INVALID_CITATION", "Answer has invalid or unsupported source excerpts");
        }
    }
    public List<Map<String, String>> promptSources() {
        return sources.entrySet().stream().map(e -> Map.of("sourceId", e.getKey(),
                "text", e.getValue().text(), "kind", e.getValue().knowledge() ? "policy" : "business")).toList();
    }
}
