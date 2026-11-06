package com.yu.agent.workflow.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 会话聚合（不可变快照）。
 *
 * <p>每次修改都生成一个新的 Conversation，revision+1；仓库用旧 revision 做 CAS，
 * 因此并发的模型结果、取消、删除不会互相覆盖，迟到的模型答案也写不回已经变化的会话。
 *
 * <p>注意：Conversation 只保存“用户看得到的问答”，不保存模型内部工具消息；
 * 事实性内容必须在每一轮重新查询，历史仅用于理解指代。
 */
public record Conversation(String id, String tenantId, String owner, Set<String> participants,
                           long revision, Instant expiresAt, boolean deleted, List<Turn> turns,
                           Summary summary) {
    public Conversation { participants = Set.copyOf(participants); turns = List.copyOf(turns); }

    /**
     * 一轮问答。
     *
     * <p>{@code status=RUNNING} 表示这一轮已经占位但模型尚未返回；
     * 它同时起到“会话忙”标记的作用，防止同一会话并发执行两轮。
     */
    public record Turn(String key, String message, String taskId, String status, String answer,
                       int rounds, List<String> tools, String errorCode) {
        public Turn { tools = List.copyOf(tools); }
    }

    /**
     * 对较早轮次的抽取式摘要。
     *
     * <p>只保留话题线索（例如最近提到的订单号），绝不缓存业务事实；
     * {@code throughTurn} 是摘要覆盖到的轮次水位，用于构建历史窗口。
     */
    public record Summary(long version, int throughTurn, String text, List<String> sourceTasks) {
        public Summary { sourceTasks = List.copyOf(sourceTasks); }
    }

    /** 生成下一版快照；revision 只在这里递增，仓库用旧版本做 CAS。 */
    public Conversation next(Set<String> readers, List<Turn> messages, Summary compacted, boolean removed) {
        return new Conversation(id, tenantId, owner, readers, revision + 1, expiresAt, removed, messages, compacted);
    }
}
