package com.yu.agent.workflow.runtime;

import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.ToolCall;
import com.yu.agent.workflow.tools.ToolResult;
import java.util.*;

/** Server policy for bounded, read-only rolling plans. No model-authored reasoning is persisted. */
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
    public static String question(String field) {
        return switch(field) {
            case "orderId" -> "请提供或核对要查询的订单号（例如 ORD-1001）。";
            case "ticketId" -> "请提供或核对要查询的工单号。";
            case "details" -> "请补充需要核对的政策、商品或售后问题；无需提供姓名、地址等个人信息。";
            default -> throw RunScope.error("DIALOGUE_INVALID_DECISION");
        };
    }
    public static Map<String,Object> specification() {
        return Map.of("type","function","function",Map.of("name",CLARIFY,
                "description","Ask the user for missing information and suspend execution. Never invent identifiers.",
                "parameters",Map.of("type","object","properties",Map.of("field",Map.of("type","string","enum",List.of("orderId","ticketId","details"))),
                        "required",List.of("field"),"additionalProperties",false)));
    }
    public void clarify(ToolCall call) {
        if(!call.arguments().keySet().equals(Set.of("field")) || !(call.arguments().get("field") instanceof String field))
            throw RunScope.error("DIALOGUE_INVALID_DECISION");
        throw new Clarification(field);
    }
    public void beforeTool(String name,Map<String,Object> args) {
        String field=switch(name) {
            case "order.query","payment.query","shipment.query" -> "orderId";
            case "ticket.query" -> "ticketId";
            case "knowledge.search" -> "query";
            default -> throw RunScope.error("TOOL_CALL_BLOCKED");
        };
        if(args.keySet().stream().anyMatch(key->!field.equals(key)))throw RunScope.error("DIALOGUE_INVALID_DECISION");
        Object value=args.get(field);
        if(value==null || value instanceof String s && s.isBlank())
            throw new Clarification("query".equals(field)?"details":field);
        if(!(value instanceof String text) || text.length()>2000)throw RunScope.error("DIALOGUE_INVALID_DECISION");
        if(latestIdentifiers.containsKey(field) && !latestIdentifiers.get(field).equalsIgnoreCase(text))throw RunScope.error("DIALOGUE_INVALID_DECISION");
        if(!"query".equals(field) && !containsIdentifier(text))throw new Clarification(field);
        if(!calls.add(Map.of("tool",name,"argument",text.trim().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT))))throw RunScope.error("DIALOGUE_REPEATED_ACTION");
        ledger.planStep(run,"QUERY",name,"READ_ONLY");
    }
    private boolean containsIdentifier(String text) {
        if(!text.matches("[A-Za-z0-9_-]{1,80}"))return false;
        return java.util.regex.Pattern.compile("(?<![A-Z0-9_-])"+java.util.regex.Pattern.quote(text.toUpperCase(Locale.ROOT))+"(?![A-Z0-9_-])")
                .matcher(input).find();
    }
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
    public void observed(String tool,ToolResult result) {
        if(!result.success())throw RunScope.error("TOOL_FAILED");
        if("knowledge.search".equals(tool))unresolvedKnowledge=false;
        ledger.planStep(run,"OBSERVATION",tool,"OK");
    }
    public void finalAnswer() {
        if(unresolvedKnowledge)throw new Clarification("details");
        ledger.planStep(run,"ANSWER","","VERIFY_CITATIONS");
    }
    @Override public void close(){CURRENT.remove();}
    public static final class Clarification extends RuntimeException {
        public final String field;
        public Clarification(String field){super(question(field));this.field=field;}
    }
}
