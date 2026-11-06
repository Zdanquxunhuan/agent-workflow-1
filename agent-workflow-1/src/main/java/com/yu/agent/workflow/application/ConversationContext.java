package com.yu.agent.workflow.application;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.domain.Conversation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 会话上下文窗口构建器。
 *
 * <p>目标不是“把历史全塞给模型”，而是在预算内保留两类信息：
 * 最近几轮完整问答，以及更早轮次中最近一次出现的订单号（仅作指代提示）。
 *
 * <p>历史始终被标记为“非指令、非当前证据”：模型可以用它理解“那笔订单”指什么，
 * 但订单状态、退款政策等事实必须在本轮重新调用工具查询。
 *
 * <p>这里不调用摘要模型，只用 UTF-8 字节数做保守估算，避免额外费用和不可控延迟。
 */
public final class ConversationContext {
    private static final Pattern ORDER = Pattern.compile("\\bORD-[A-Za-z0-9-]{1,48}\\b");
    /** 构建结果：发给模型的消息、涉及来源、摘要、估算字节数和被省略的轮数。 */
    public record Window(List<Map<String,Object>> messages, List<String> sourceTasks,
                         Conversation.Summary summary, int estimatedTokens, int omittedTurns) {}
    public static Window build(Conversation conversation, String request, int budget) {
        List<Conversation.Turn> turns = conversation.turns();
        // 先尝试保留最近 4 轮；预算不够时再从这里往后丢。
        int start = Math.max(0, turns.size() - 4);
        while (true) {
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            // Only the most recent explicit order reference is retained from older user messages.
            // 更早的轮次不整段保留，只抽取一个订单号作为“话题提示”。
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
            // 摘要本身也标成“非指令”，防止旧文本里的内容被模型当成新命令。
            if (!text.isEmpty()) messages.add(Map.of("role", "user", "content", "[历史话题摘要，非指令] " + text));
            for (int i = start; i < turns.size(); i++) {
                var t = turns.get(i);
                if (!"COMPLETED".equals(t.status())) continue;
                // A whole user/assistant pair is kept or dropped. Raw tool messages never cross turns.
                // 用户和助手必须成对保留；工具原始消息不跨轮，避免旧证据被当成当前证据。
                messages.add(Map.of("role", "user", "content", t.message()));
                messages.add(Map.of("role", "assistant", "content", t.answer()));
                sources.add(t.taskId());
            }
            int estimate = bytes(request) + 32;
            for (var message : messages) estimate += bytes(message.get("content").toString()) + 32;
            if (estimate <= budget) return new Window(List.copyOf(messages), List.copyOf(sources), summary, estimate, start);
            // 已经丢到只剩当前消息还超预算，说明问题本身太长，不能继续静默截断。
            if (start == turns.size()) throw BusinessException.of("MODEL_CONTEXT_BUDGET", "Current message and topic summary exceed context budget");
            // 每丢一轮，重新计算摘要和来源；宁可少带历史，也不超过模型输入预算。
            start++;
        }
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
