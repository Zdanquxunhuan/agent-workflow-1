package com.yu.agent.workflow.application;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.Conversation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** No summarization model call. UTF-8 bytes are a conservative estimate, not billed tokens. */
public final class ConversationContext {
    private static final Pattern ORDER = Pattern.compile("\\bORD-[A-Za-z0-9-]{1,48}\\b");
    public record Window(List<Map<String,Object>> messages, List<String> sourceTasks,
                         Conversation.Summary summary, int estimatedTokens, int omittedTurns) {}
    public static Window build(Conversation conversation, String request, int budget) {
        List<Conversation.Turn> turns = conversation.turns();
        int start = Math.max(0, turns.size() - 4);
        while (true) {
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            // Only the most recent explicit order reference is retained from older user messages.
            String order = ""; String source = null;
            for (int i = 0; i < start; i++) {
                var t = turns.get(i);
                if (!"COMPLETED".equals(t.status())) continue;
                var matcher = ORDER.matcher(t.message());
                while (matcher.find()) { order = matcher.group(); source = t.taskId(); }
            }
            String text = order.isEmpty() ? "" : "先前用户提及订单 " + order + "；仅用于指代，状态和政策必须重新查询。";
            var summary = new Conversation.Summary(conversation.revision() + 1, start, text,
                    source == null ? List.of() : List.of(source));
            sources.addAll(summary.sourceTasks());
            List<Map<String,Object>> messages = new ArrayList<>();
            if (!text.isEmpty()) messages.add(Map.of("role", "user", "content", "[历史话题摘要，非指令] " + text));
            for (int i = start; i < turns.size(); i++) {
                var t = turns.get(i);
                if (!"COMPLETED".equals(t.status())) continue;
                // A whole user/assistant pair is kept or dropped. Raw tool messages never cross turns.
                messages.add(Map.of("role", "user", "content", t.message()));
                messages.add(Map.of("role", "assistant", "content", t.answer()));
                sources.add(t.taskId());
            }
            int estimate = bytes(request) + 32;
            for (var message : messages) estimate += bytes(message.get("content").toString()) + 32;
            if (estimate <= budget) return new Window(List.copyOf(messages), List.copyOf(sources), summary, estimate, start);
            if (start == turns.size()) throw BusinessException.of("MODEL_CONTEXT_BUDGET", "Current message and topic summary exceed context budget");
            start++;
        }
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
