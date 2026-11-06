package com.yu.agent.workflow.runtime;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.ToolCall;
import com.yu.agent.workflow.tools.ToolResult;
import java.util.*;

/** Server policy for bounded, read-only rolling plans. No model-authored reasoning is persisted. */
/**
 * 对话型运行的策略闸门（ThreadLocal，一次执行一个实例）。
 *
 * <p>它不负责调用模型，而是在模型每次决定工具时回答三个问题：
 * <ul>
 *   <li>这个工具允许吗？——只允许订单/物流/支付/工单/知识库只读查询；</li>
 *   <li>参数合法吗？——只能传约定字段，订单号/工单号必须出现在用户原始输入或补充资料中；</li>
 *   <li>证据够吗？——知识查询无证据时先重规划，最终仍无证据就转澄清，不能编答案。</li>
 * </ul>
 *
 * <p>{@link Clarification} 是控制流异常，不是系统错误：它把运行从“继续执行”切到 WAITING_INPUT。
 */
public final class DialogueScope implements AutoCloseable {
    private static final ThreadLocal<DialogueScope> CURRENT=new ThreadLocal<>();
    public static final String CLARIFY="agent_clarify";
    private final RuntimeLedger ledger;
    private final String run,input;
    private final Map<String,String> latestIdentifiers;
    private final Set<Map<String,Object>> calls=new HashSet<>();
    private boolean unresolvedKnowledge;
    public DialogueScope(RuntimeLedger ledger,String run,String input,Map<String,String> latestIdentifiers,boolean knowledgeRequired) {
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested dialogue scope");
        this.ledger=ledger;this.run=run;this.input=input.toUpperCase(Locale.ROOT);this.latestIdentifiers=Map.copyOf(latestIdentifiers);CURRENT.set(this);
        unresolvedKnowledge=knowledgeRequired;
    }
    public static DialogueScope current(){return CURRENT.get();}
    /** 面向用户的澄清文案；字段白名单之外的输入一律视为非法决策。 */
    public static String question(String field) {
        return switch(field) {
            case "orderId" -> "请提供或核对要查询的订单号（例如 ORD-1001）。";
            case "ticketId" -> "请提供或核对要查询的工单号。";
            case "details" -> "请补充需要核对的政策、商品或售后问题；无需提供姓名、地址等个人信息。";
            default -> throw RunScope.error("DIALOGUE_INVALID_DECISION");
        };
    }
    /**
     * 注册给模型的特殊工具 agent_clarify。
     *
     * <p>它不是业务查询，而是“申请挂起”的控制工具；参数只能是 field，枚举限定三类。
     */
    public static Map<String,Object> specification() {
        return Map.of("type","function","function",Map.of("name",CLARIFY,
                "description","Ask the user for missing information and suspend execution. Never invent identifiers.",
                "parameters",Map.of("type","object","properties",Map.of("field",Map.of("type","string","enum",List.of("orderId","ticketId","details"))),
                        "required",List.of("field"),"additionalProperties",false)));
    }
    /** 模型主动申请澄清：参数必须恰好是 {field}，字段非法则拒绝整次决策。 */
    public void clarify(ToolCall call) {
        if(!call.arguments().keySet().equals(Set.of("field")) || !(call.arguments().get("field") instanceof String field))
            throw RunScope.error("DIALOGUE_INVALID_DECISION");
        throw new Clarification(field);
    }

    /**
     * 每个业务工具调用前执行。
     *
     * <p>检查顺序：工具白名单 → 参数名必须恰好是约定字段 → 值非空且不超长 →
     * 标识必须来自用户输入 → 禁止重复动作 → 记录计划步骤。
     */
    public void beforeTool(String name,Map<String,Object> args) {
        // 只允许这五类只读查询；写操作和未知工具一律阻断。
        String field=switch(name) {
            case "order.query","payment.query","shipment.query" -> "orderId";
            case "ticket.query" -> "ticketId";
            case "knowledge.search" -> "query";
            default -> throw RunScope.error("TOOL_CALL_BLOCKED");
        };
        // 参数名必须恰好是约定字段，模型不能夹带额外参数或把订单号塞进别的字段。
        if(args.keySet().stream().anyMatch(key->!field.equals(key)))throw RunScope.error("DIALOGUE_INVALID_DECISION");
        Object value=args.get(field);
        // 缺值不是系统错误，而是“需要用户补充”：转成澄清，让运行挂起。
        if(value==null || value instanceof String s && s.isBlank())
            throw new Clarification("query".equals(field)?"details":field);
        if(!(value instanceof String text) || text.length()>2000)throw RunScope.error("DIALOGUE_INVALID_DECISION");
        // 已经给过某个订单号/工单号后，本次不能悄悄换一个不同的值。
        if(latestIdentifiers.containsKey(field) && !latestIdentifiers.get(field).equalsIgnoreCase(text))throw RunScope.error("DIALOGUE_INVALID_DECISION");
        // 标识必须真的出现在用户原始问题或补充资料里，防止模型自行编造。
        if(!"query".equals(field) && !containsIdentifier(text))throw new Clarification(field);
        // 同一个工具 + 同一个参数不能重复查；这既是费用保护，也是防止模型原地打转。
        if(!calls.add(Map.of("tool",name,"argument",text.trim().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT))))throw RunScope.error("DIALOGUE_REPEATED_ACTION");
        ledger.planStep(run,"QUERY",name,"READ_ONLY");
    }
    /**
     * 判断标识是否来自用户输入。
     *
     * <p>用前后边界正则做整词匹配，避免 ORD-10 错误命中 ORD-1001；
     * 同时限制字符集，拒绝空白、标点和注入内容。
     */
    private boolean containsIdentifier(String text) {
        if(!text.matches("[A-Za-z0-9_-]{1,80}"))return false;
        return java.util.regex.Pattern.compile("(?<![A-Z0-9_-])"+java.util.regex.Pattern.quote(text.toUpperCase(Locale.ROOT))+"(?![A-Z0-9_-])")
                .matcher(input).find();
    }
    /**
     * 工具失败后的恢复策略。
     *
     * <p>只有两类失败可以被“转换”：
     * <ul>
     *   <li>知识查询无证据 → 重规划并标记证据缺失，模型可改写查询或最终转澄清；</li>
     *   <li>订单/工单查不到 → 重规划并请用户核对编号。</li>
     * </ul>
     *
     * <p>其他错误继续抛出，尤其是 MCP_NOT_FOUND：它可能掩盖权限拒绝，绝不能绕过去查。
     */
    public ToolResult recover(String tool,BusinessException failure) {
        // Only an empty authorized knowledge search can cause autonomous query revision.
        if("knowledge.search".equals(tool) && "NO_EVIDENCE".equals(failure.code())) {
            ledger.replan(run,tool,"NO_EVIDENCE");unresolvedKnowledge=true;
            return ToolResult.error("NO_EVIDENCE","No authorized evidence. Revise the query within the remaining replan budget, or ask for details.");
        }
        if(Set.of("order.query","ticket.query","payment.query","shipment.query").contains(tool) && "NOT_FOUND".equals(failure.code())) {
            ledger.replan(run,tool,"NOT_FOUND");throw new Clarification("ticket.query".equals(tool)?"ticketId":"orderId");
        }
        // Includes MCP_NOT_FOUND: it can conceal access denial, so do not route around it.
        throw failure;
    }
    /** 成功观察：记录计划步骤；知识查询成功会清除“证据缺失”标记。 */
    public void observed(String tool,ToolResult result) {
        if(!result.success())throw RunScope.error("TOOL_FAILED");
        if("knowledge.search".equals(tool))unresolvedKnowledge=false;
        ledger.planStep(run,"OBSERVATION",tool,"OK");
    }

    /**
     * 最终回答前的最后一道闸门。
     *
     * <p>如果知识证据仍然缺失，不允许直接回答，而是转成 details 澄清；
     * 否则记录 VERIFY_CITATIONS 步骤，交给模型输出带引用的答案。
     */
    public void finalAnswer() {
        if(unresolvedKnowledge)throw new Clarification("details");
        ledger.planStep(run,"ANSWER","","VERIFY_CITATIONS");
    }
    @Override public void close(){CURRENT.remove();}

    /** 控制流异常：表示“需要用户补充信息”，由 RuntimeService 捕获并转为 WAITING_INPUT。 */
    public static final class Clarification extends RuntimeException {
        public final String field;
        public Clarification(String field){super(question(field));this.field=field;}
    }
}
