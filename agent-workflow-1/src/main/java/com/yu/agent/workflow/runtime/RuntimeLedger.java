package com.yu.agent.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Monetary values are estimated currency millionths, never an assertion of the provider's final invoice. */
@Service
@ConditionalOnProperty(name="agent.runtime.enabled",havingValue="true")
public class RuntimeLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final RuntimeSettings settings;
    private final String instance=UUID.randomUUID().toString();
    public RuntimeLedger(DataSource dataSource,@Qualifier("workflowTransactionManager") PlatformTransactionManager manager,
                         ObjectMapper json,RuntimeSettings settings) {
        jdbc=new JdbcTemplate(dataSource);jdbc.setQueryTimeout(3);
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(4);
        this.json=json;this.settings=settings;
    }
    public record Open(String id,boolean created){}
    public long now(){return jdbc.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class);}
    public Open open(AuthenticatedUser user,String key,String conversation,Object request,long identityExpires) {
        return open(user,key,conversation,request,identityExpires,null,null);
    }
    public Open openDialogue(AuthenticatedUser user,String key,String message,long identityExpires,DialogueSettings dialogue) {
        if(message==null || message.isBlank() || message.length()>2000)throw RunScope.error("INVALID_ARGUMENT");
        return open(user,key,null,Map.of("message",message),identityExpires,dialogue,message);
    }
    private Open open(AuthenticatedUser user,String key,String conversation,Object request,long identityExpires,DialogueSettings dialogue,String message) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{1,80}") || !user.userId().matches("[A-Za-z0-9._@-]{1,200}"))throw RunScope.error("INVALID_ARGUMENT");
        if(!settings.primary.tenants().contains(user.tenantId()))throw RunScope.error("FORBIDDEN");
        String entry=dialogue!=null?"dialogue":conversation==null?"chat":"conversation:"+conversation;
        String hash;
        try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));}
        catch(Exception ex){throw new IllegalArgumentException("Invalid runtime request");}
        String digest=hash;
        return tx.execute(status->{
            long time=now();long end=Math.min(time+settings.seconds*1000L,identityExpires);
            if(end<=time+1000)throw RunScope.error("RUN_IDENTITY_EXPIRED");
            java.sql.Date day=java.sql.Date.valueOf(java.time.Instant.ofEpochMilli(time).atOffset(java.time.ZoneOffset.UTC).toLocalDate());
            wallet(Map.of("tenant_id",user.tenantId(),"owner_id",user.userId(),"budget_day",day,"currency",settings.currency));
            var existing=jdbc.queryForList("SELECT id,request_hash FROM agent_runs WHERE tenant_id=? AND owner_id=? AND entrypoint=? AND request_key=? FOR UPDATE",user.tenantId(),user.userId(),entry,key);
            if(!existing.isEmpty()) {
                if(!digest.equals(existing.get(0).get("request_hash")))throw RunScope.error("IDEMPOTENCY_CONFLICT");
                return new Open(existing.get(0).get("id").toString(),false);
            }
            if(jdbc.queryForList("SELECT id FROM agent_runs WHERE tenant_id=? AND owner_id=? AND status IN ('QUEUED','RUNNING','WAITING_INPUT') AND deadline_at>? FOR UPDATE",
                    String.class,user.tenantId(),user.userId(),time).size()>=settings.maxActive)throw RunScope.error("RUN_BUSY");
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO agent_runs(id,tenant_id,owner_id,entrypoint,request_key,request_hash,conversation_id,instance_id,status,created_at,deadline_at,budget_day,currency,max_cost_micros,max_tokens,max_attempts,max_tools) "
                    +"VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",id,user.tenantId(),user.userId(),entry,key,digest,conversation,instance,"QUEUED",time,end,
                    day,settings.currency,settings.requestMicros,settings.maxTokens,settings.maxAttempts,settings.maxTools);
            var row=jdbc.queryForMap("SELECT * FROM agent_runs WHERE tenant_id=? AND owner_id=? AND entrypoint=? AND request_key=?",user.tenantId(),user.userId(),entry,key);
            if(!digest.equals(row.get("request_hash")))throw RunScope.error("IDEMPOTENCY_CONFLICT");
            boolean created=id.equals(row.get("id"));
            if(created) {
                if(dialogue!=null) {
                    jdbc.update("UPDATE agent_runs SET dialogue_enabled=true,max_replans=?,max_clarifications=?,chain_expires_at=?,execution_remaining_ms=?,segment_opened_at=?,wait_seconds=? WHERE id=?",
                            dialogue.maxReplans,dialogue.maxClarifications,time+dialogue.lifetimeSeconds*1000L,settings.seconds*1000L,time,dialogue.waitSeconds,id);
                    jdbc.update("INSERT INTO agent_dialogue_inputs(run_id,revision,request_key,field_name,input_text,created_at) VALUES (?,0,?,'message',?,?)",id,key,message,time);
                    eventInside(id,"plan_created",Map.of("version",1,"parentVersion",0,"reason","USER_REQUEST","next","DECIDE_READ_ONLY_OR_CLARIFY"),time);
                }
                eventInside(id,"queued",Map.of("runId",id),time);
            }
            return new Open(row.get("id").toString(),created);
        });
    }
    public Map<String,Object> owned(String id,AuthenticatedUser user) {
        var rows=jdbc.queryForList("SELECT * FROM agent_runs WHERE id=? AND tenant_id=? AND owner_id=?",id,user.tenantId(),user.userId());
        if(rows.isEmpty())throw RunScope.error("NOT_FOUND");return rows.get(0);
    }
    Map<String,Object> row(String id){return jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=?",id);}
    public Map<String,Object> describe(String id,AuthenticatedUser user) {
        var r=owned(id,user);Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("id","conversation_id","task_id","status","cancel_requested","error_code","created_at","started_at","ended_at","deadline_at",
                "currency","max_cost_micros","accounted_micros","reserved_micros","accounted_tokens","reserved_tokens","attempt_count","tool_count","fallback_used",
                "dialogue_enabled","dialogue_revision","plan_version","replan_count","max_replans","max_clarifications","clarification_field","chain_expires_at","execution_remaining_ms"))result.put(key,r.get(key));
        if("WAITING_INPUT".equals(r.get("status")))result.put("question",DialogueScope.question(r.get("clarification_field").toString()));
        result.put("costMeaning","configured-price estimate; unknown usage consumes reservation");return result;
    }
    public List<Map<String,Object>> list(AuthenticatedUser user,boolean active) {
        return jdbc.queryForList("SELECT id,conversation_id,task_id,status,cancel_requested,error_code,created_at,deadline_at,currency,accounted_micros,dialogue_enabled FROM agent_runs WHERE tenant_id=? AND owner_id=? "
                +(active?"AND status IN ('QUEUED','RUNNING','WAITING_INPUT') ":"")+"ORDER BY created_at DESC,id DESC LIMIT 50",user.tenantId(),user.userId());
    }
    public List<Map<String,Object>> attempts(String id,AuthenticatedUser user) {
        owned(id,user);
        return jdbc.queryForList("SELECT id,model_alias,model_name,route_reason,price_version,currency,status,code,usage_source,reserved_input,reserved_output,input_tokens,output_tokens,accounted_micros,started_at,ended_at FROM agent_model_attempts WHERE run_id=? ORDER BY started_at,id",id);
    }
    public boolean claim(String id) {
        return tx.execute(s->{
            long time=now();
            boolean ok=jdbc.update("UPDATE agent_runs SET status='RUNNING',started_at=COALESCE(started_at,?) WHERE id=? AND status='QUEUED' AND cancel_requested=false AND deadline_at>?",time,id,time)==1;
            if(ok)eventInside(id,"started",Map.of(),time);return ok;
        });
    }
    public void check(String id) {validate(row(id),now());}
    private void validate(Map<String,Object> r,long time) {
        if(flag(r,"cancel_requested") || "CANCELLED".equals(r.get("status")))throw RunScope.error("RUN_CANCELLED");
        if(number(r,"deadline_at")<=time)throw RunScope.error("RUN_DEADLINE");
        if(!"RUNNING".equals(r.get("status")))throw RunScope.error("RUN_NOT_ACTIVE");
        if(number(r,"accounted_micros")+number(r,"reserved_micros")>number(r,"max_cost_micros")
                || number(r,"accounted_tokens")+number(r,"reserved_tokens")>number(r,"max_tokens"))throw RunScope.error("RUN_BUDGET_EXCEEDED");
    }
    public void cancel(String id,AuthenticatedUser user) {
        owned(id,user);
        tx.executeWithoutResult(s->{
            var row=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);
            if(jdbc.update("UPDATE agent_runs SET cancel_requested=true WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING_INPUT') AND cancel_requested=false",id)==1)
                eventInside(id,"cancel_requested",Map.of(),now());
            if("WAITING_INPUT".equals(row.get("status"))) {
                jdbc.update("UPDATE agent_runs SET status='CANCELLED',error_code='RUN_CANCELLED',ended_at=? WHERE id=?",now(),id);
                eventInside(id,"terminal",Map.of("status","CANCELLED","code","RUN_CANCELLED"),now());
            }
        });
    }
    public void cancelConversation(String conversation,String key,AuthenticatedUser user) {
        for(String id:jdbc.queryForList("SELECT id FROM agent_runs WHERE conversation_id=? AND request_key=? AND tenant_id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')",
                String.class,conversation,key,user.tenantId(),user.userId()))cancel(id,user);
    }
    public void bindTask(String id,String task) {
        if(jdbc.update("UPDATE agent_runs r JOIN tasks t ON t.id=? AND t.tenant_id=r.tenant_id AND t.user_id=r.owner_id AND t.entrypoint='chat' SET r.task_id=? WHERE r.id=? AND r.status='RUNNING' AND (r.task_id IS NULL OR r.task_id=?)",task,task,id,task)!=1)
            throw RunScope.error("RUN_NOT_ACTIVE");
    }
    public boolean managesTask(String task) {
        return !jdbc.queryForList("SELECT id FROM agent_runs WHERE task_id=? LIMIT 1",String.class,task).isEmpty();
    }
    public void tool(String id,String name) {
        tx.executeWithoutResult(s->{
            var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);validate(r,now());
            if(number(r,"tool_count")>=number(r,"max_tools"))throw RunScope.error("RUN_TOOL_LIMIT");
            jdbc.update("UPDATE agent_runs SET tool_count=tool_count+1 WHERE id=?",id);eventInside(id,"tool_started",Map.of("tool",name),now());
        });
    }
    public record Reservation(String id,String runId,long input,long output,long cost,RuntimeSettings.Model model){}
    public Reservation reserve(String id,RuntimeSettings.Model model,long input,String reason,boolean fallback) {
        long output=model.maxOutput(),cost=model.cost(input,output),tokens=Math.addExact(input,output);
        return tx.execute(s->{
            var snapshot=row(id);var wallet=wallet(snapshot); // Lock order is ALWAYS wallet -> run -> attempt.
            var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);validate(r,now());
            if(!model.tenants().contains(r.get("tenant_id")))throw RunScope.error("FORBIDDEN");
            if(!settings.currency.equals(r.get("currency")))throw RunScope.error("RUN_CURRENCY_CHANGED");
            if(number(r,"attempt_count")>=number(r,"max_attempts"))throw RunScope.error("RUN_MODEL_LIMIT");
            if(fallback && flag(r,"fallback_used"))throw RunScope.error("RUN_FALLBACK_LIMIT");
            if(cost>number(r,"max_cost_micros")-number(r,"accounted_micros")-number(r,"reserved_micros")
                    || tokens>number(r,"max_tokens")-number(r,"accounted_tokens")-number(r,"reserved_tokens"))throw RunScope.error("RUN_BUDGET_EXCEEDED");
            if(cost>number(wallet,"limit_micros")-number(wallet,"accounted_micros")-number(wallet,"reserved_micros"))throw RunScope.error("DAILY_BUDGET_EXCEEDED");
            String attempt=UUID.randomUUID().toString();long time=now();
            jdbc.update("INSERT INTO agent_model_attempts(id,run_id,model_alias,model_name,route_reason,price_version,input_per_million,output_per_million,currency,status,usage_source,reserved_input,reserved_output,reserved_micros,started_at) VALUES (?,?,?,?,?,?,?,?,?,'STARTED','unknown',?,?,?,?)",
                    attempt,id,model.alias(),model.name(),reason,model.priceVersion(),model.inputPrice(),model.outputPrice(),r.get("currency"),input,output,cost,time);
            jdbc.update("UPDATE agent_runs SET attempt_count=attempt_count+1,reserved_tokens=reserved_tokens+?,reserved_micros=reserved_micros+?,fallback_used=(fallback_used OR ?) WHERE id=?",tokens,cost,fallback,id);
            walletDelta(r,0,cost);
            eventInside(id,"model_started",Map.of("attemptId",attempt,"modelAlias",model.alias(),"reason",reason),time);
            return new Reservation(attempt,id,input,output,cost,model);
        });
    }
    private Map<String,Object> wallet(Map<String,Object> r) {
        jdbc.update("INSERT INTO agent_runtime_wallets(tenant_id,owner_id,budget_day,currency,limit_micros) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE limit_micros=LEAST(limit_micros,VALUES(limit_micros))",
                r.get("tenant_id"),r.get("owner_id"),r.get("budget_day"),r.get("currency"),settings.dailyMicros);
        return jdbc.queryForMap("SELECT * FROM agent_runtime_wallets WHERE tenant_id=? AND owner_id=? AND budget_day=? AND currency=? FOR UPDATE",r.get("tenant_id"),r.get("owner_id"),r.get("budget_day"),r.get("currency"));
    }
    private void walletDelta(Map<String,Object> r,long accounted,long reserved) {
        jdbc.update("UPDATE agent_runtime_wallets SET accounted_micros=accounted_micros+?,reserved_micros=reserved_micros+? WHERE tenant_id=? AND owner_id=? AND budget_day=? AND currency=?",
                accounted,reserved,r.get("tenant_id"),r.get("owner_id"),r.get("budget_day"),r.get("currency"));
    }
    public void settle(Reservation reservation,Map<String,Object> metadata,String code) {
        tx.executeWithoutResult(s->{
            var r=row(reservation.runId());wallet(r);
            r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",reservation.runId());
            var a=jdbc.queryForMap("SELECT * FROM agent_model_attempts WHERE id=? FOR UPDATE",reservation.id());
            if(!"STARTED".equals(a.get("status")))return; // Late completion after crash reconciliation cannot double settle.
            Long input=usage(metadata,"inputTokens"),output=usage(metadata,"outputTokens");
            boolean known="provider".equals(metadata.get("usageSource")) && input!=null && output!=null;
            long accounted=known?reservation.model().cost(input,output):reservation.cost();
            long tokens=known?Math.addExact(input,output):reservation.input()+reservation.output();
            jdbc.update("UPDATE agent_model_attempts SET status=?,code=?,usage_source=?,input_tokens=?,output_tokens=?,accounted_micros=?,ended_at=? WHERE id=?",
                    "OK".equals(code)?"COMPLETED":"FAILED",code,known?"provider":"unknown",known?input:null,known?output:null,accounted,now(),reservation.id());
            jdbc.update("UPDATE agent_runs SET reserved_micros=reserved_micros-?,accounted_micros=accounted_micros+?,reserved_tokens=reserved_tokens-?,accounted_tokens=accounted_tokens+? WHERE id=?",
                    reservation.cost(),accounted,reservation.input()+reservation.output(),tokens,reservation.runId());
            walletDelta(r,accounted,-reservation.cost());
            eventInside(reservation.runId(),"model_finished",Map.of("attemptId",reservation.id(),"code",code,"usageSource",known?"provider":"unknown","accountedMicros",accounted),now());
        });
    }
    private Long usage(Map<String,Object> data,String key) {
        Object n=data.get(key);
        if(!(n instanceof Number number))return null;
        try {long value=new java.math.BigDecimal(number.toString()).longValueExact();return value>=0 && value<=10_000_000?value:null;}
        catch(ArithmeticException ex){return null;}
    }
    public boolean finish(String id,String code) {
        return tx.execute(s->{
            var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);long time=now();
            if(!Set.of("RUNNING","QUEUED","WAITING_INPUT").contains(r.get("status")))return false;
            String actual=flag(r,"cancel_requested")?"RUN_CANCELLED":number(r,"deadline_at")<=time?"RUN_DEADLINE":code;
            if("OK".equals(actual) && (number(r,"reserved_micros")!=0 || number(r,"reserved_tokens")!=0))actual="RUN_ACCOUNTING_INCOMPLETE";
            if("OK".equals(actual) && (number(r,"accounted_micros")>number(r,"max_cost_micros") || number(r,"accounted_tokens")>number(r,"max_tokens")))actual="RUN_BUDGET_EXCEEDED";
            String state="OK".equals(actual)?"SUCCEEDED":"RUN_CANCELLED".equals(actual)?"CANCELLED":"RUN_DEADLINE".equals(actual)?"EXPIRED":"RUN_INTERRUPTED".equals(actual)?"INTERRUPTED":"FAILED";
            jdbc.update("UPDATE agent_runs SET status=?,error_code=?,ended_at=? WHERE id=?",state,"OK".equals(actual)?null:actual,time,id);
            eventInside(id,"terminal",Map.of("status",state,"code",actual),time);
            return "SUCCEEDED".equals(state);
        });
    }
    public <T> T publish(String id,java.util.function.Supplier<T> action) {
        return tx.execute(s->{
            var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);long time=now();validate(r,time);
            if(number(r,"reserved_tokens")!=0 || number(r,"reserved_micros")!=0)throw RunScope.error("RUN_ACCOUNTING_INCOMPLETE");
            T result=action.get(); // Task success + conversation CAS join this transaction.
            if(now()>=number(r,"deadline_at"))throw RunScope.error("RUN_DEADLINE");
            jdbc.update("UPDATE agent_runs SET status='SUCCEEDED',ended_at=? WHERE id=?",now(),id);
            eventInside(id,"terminal",Map.of("status","SUCCEEDED","code","OK"),now());
            return result;
        });
    }
    public void event(String id,String type,Map<String,Object> data) {
        tx.executeWithoutResult(s->{
            var r=jdbc.queryForMap("SELECT status FROM agent_runs WHERE id=? FOR UPDATE",id);
            if("RUNNING".equals(r.get("status")))eventInside(id,type,data,now());
        });
    }
    private void eventInside(String id,String type,Map<String,Object> data,long time) {
        try {
            String payload=json.writeValueAsString(data);
            if(payload.getBytes(StandardCharsets.UTF_8).length>2048)throw new IllegalStateException("Progress payload too large");
            jdbc.update("INSERT INTO agent_run_events(run_id,event_type,payload_json,created_at) VALUES (?,?,?,?)",id,type,payload,time);
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Cannot encode progress");}
    }
    public List<Map<String,Object>> events(String id,long after) {
        return jdbc.queryForList("SELECT id,event_type,payload_json,created_at FROM agent_run_events WHERE run_id=? AND id>? ORDER BY id LIMIT 100",id,after);
    }
    public List<Map<String,Object>> cleanupCandidates() {
        return jdbc.queryForList("SELECT * FROM agent_runs WHERE status IN ('FAILED','CANCELLED','EXPIRED','INTERRUPTED') AND cleanup_done=false ORDER BY created_at LIMIT 50");
    }
    public void cleanupDone(String id){jdbc.update("UPDATE agent_runs SET cleanup_done=true WHERE id=?",id);}
    /** Never resume paid calls after a crash: close stale reservations conservatively as unknown usage. */
    public void reconcile() {
        for(String id:jdbc.queryForList("SELECT id FROM agent_runs WHERE deadline_at<? AND (status IN ('RUNNING','QUEUED','WAITING_INPUT') OR reserved_tokens>0) ORDER BY deadline_at LIMIT 50",String.class,now())) {
            tx.executeWithoutResult(s->{
                var r=row(id);wallet(r);r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);
                if(number(r,"deadline_at")>now())return;
                for(var a:jdbc.queryForList("SELECT * FROM agent_model_attempts WHERE run_id=? AND status='STARTED' FOR UPDATE",id)) {
                    long cost=number(a,"reserved_micros"),tokens=number(a,"reserved_input")+number(a,"reserved_output");
                    jdbc.update("UPDATE agent_model_attempts SET status='FAILED',code='RUN_INTERRUPTED',usage_source='unknown',accounted_micros=?,ended_at=? WHERE id=?",cost,now(),a.get("id"));
                    jdbc.update("UPDATE agent_runs SET reserved_micros=reserved_micros-?,accounted_micros=accounted_micros+?,reserved_tokens=reserved_tokens-?,accounted_tokens=accounted_tokens+? WHERE id=?",cost,cost,tokens,tokens,id);
                    walletDelta(r,cost,-cost);
                }
            });
            // Recheck after obtaining the lock: a waiting run might have resumed since the scan.
            tx.executeWithoutResult(s->{
                var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);
                if(number(r,"deadline_at")<=now() && Set.of("RUNNING","QUEUED","WAITING_INPUT").contains(r.get("status"))) {
                    String code=flag(r,"cancel_requested")?"RUN_CANCELLED":"RUN_DEADLINE";
                    String state=flag(r,"cancel_requested")?"CANCELLED":"EXPIRED";
                    jdbc.update("UPDATE agent_runs SET status=?,error_code=?,ended_at=? WHERE id=?",state,code,now(),id);
                    eventInside(id,"terminal",Map.of("status",state,"code",code),now());
                }
            });
        }
    }

    public void planStep(String id,String decision,String tool,String reason) {
        tx.executeWithoutResult(s->{
            var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);validate(r,now());
            if(!flag(r,"dialogue_enabled"))throw RunScope.error("DIALOGUE_REQUIRED");
            eventInside(id,"plan_step",Map.of("version",number(r,"plan_version"),"decision",decision,"tool",tool,"reason",reason),now());
        });
    }
    public void replan(String id,String tool,String reason) {
        tx.executeWithoutResult(s->{
            var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);validate(r,now());
            if(!flag(r,"dialogue_enabled") || number(r,"replan_count")>=number(r,"max_replans"))throw RunScope.error("DIALOGUE_REPLAN_LIMIT");
            long version=number(r,"plan_version");
            jdbc.update("UPDATE agent_runs SET replan_count=replan_count+1,plan_version=plan_version+1,knowledge_required=(knowledge_required OR ?) WHERE id=?","NO_EVIDENCE".equals(reason),id);
            eventInside(id,"plan_revised",Map.of("version",version+1,"parentVersion",version,"reason",reason,"failedTool",tool,
                    "next","NO_EVIDENCE".equals(reason)?"REVISE_QUERY_OR_CLARIFY":"CLARIFY_IDENTIFIER"),now());
        });
    }
    public void pause(String id,String field,Runnable suspendTask) {
        DialogueScope.question(field);
        tx.executeWithoutResult(s->{
            var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);long time=now();validate(r,time);
            if(!flag(r,"dialogue_enabled") || number(r,"dialogue_revision")>=number(r,"max_clarifications"))throw RunScope.error("DIALOGUE_CLARIFICATION_LIMIT");
            if(number(r,"reserved_tokens")!=0 || number(r,"reserved_micros")!=0)throw RunScope.error("RUN_ACCOUNTING_INCOMPLETE");
            suspendTask.run(); // Task WAITING_INPUT and run WAITING_INPUT commit atomically, no network here.
            time=now();validate(r,time);
            long remaining=number(r,"execution_remaining_ms")-Math.max(0,time-number(r,"segment_opened_at"));
            long waitEnd=Math.min(number(r,"chain_expires_at"),time+number(r,"wait_seconds")*1000L);
            if(remaining<=0 || waitEnd<=time)throw RunScope.error("RUN_DEADLINE");
            jdbc.update("UPDATE agent_runs SET status='WAITING_INPUT',clarification_field=?,deadline_at=?,execution_remaining_ms=? WHERE id=?",field,waitEnd,remaining,id);
            eventInside(id,"clarification_required",Map.of("version",number(r,"plan_version"),"revision",number(r,"dialogue_revision"),"decision","CLARIFY","reason","MISSING_INFORMATION","field",field),time);
        });
    }
    public boolean resumeDialogue(String id,long revision,String field,String value,String key,AuthenticatedUser user,long identityExpires) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{1,80}") || value==null || value.isBlank() || value.length()>2000)
            throw RunScope.error("INVALID_ARGUMENT");
        DialogueScope.question(field);
        if(!"details".equals(field) && !value.matches("[A-Za-z0-9_-]{1,80}"))throw RunScope.error("INVALID_ARGUMENT");
        owned(id,user);
        return tx.execute(s->{
            var r=jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=? FOR UPDATE",id);
            if(!flag(r,"dialogue_enabled"))throw RunScope.error("DIALOGUE_REQUIRED");
            var existing=jdbc.queryForList("SELECT * FROM agent_dialogue_inputs WHERE run_id=? AND request_key=?",id,key);
            if(!existing.isEmpty()) {
                var old=existing.get(0);
                if(number(old,"revision")!=revision+1 || !field.equals(old.get("field_name")) || !value.equals(old.get("input_text")))
                    throw RunScope.error("IDEMPOTENCY_CONFLICT");
                return false;
            }
            long time=now();
            if(!"WAITING_INPUT".equals(r.get("status")) || flag(r,"cancel_requested") || number(r,"dialogue_revision")!=revision
                    || !field.equals(r.get("clarification_field")))throw RunScope.error("CONFLICT");
            if(number(r,"deadline_at")<=time || number(r,"chain_expires_at")<=time)throw RunScope.error("RUN_DEADLINE");
            if(identityExpires<=time+1000)throw RunScope.error("RUN_IDENTITY_EXPIRED");
            if(!settings.primary.tenants().contains(user.tenantId()))throw RunScope.error("FORBIDDEN");
            if(!settings.currency.equals(r.get("currency")))throw RunScope.error("RUN_CURRENCY_CHANGED");
            long end=Math.min(Math.min(time+number(r,"execution_remaining_ms"),number(r,"chain_expires_at")),identityExpires);
            if(end<=time+1000)throw RunScope.error("RUN_DEADLINE");
            jdbc.update("INSERT INTO agent_dialogue_inputs(run_id,revision,request_key,field_name,input_text,created_at) VALUES (?,?,?,?,?,?)",id,revision+1,key,field,value,time);
            jdbc.update("UPDATE agent_runs SET status='QUEUED',dialogue_revision=dialogue_revision+1,plan_version=plan_version+1,clarification_field=NULL,deadline_at=?,segment_opened_at=?,instance_id=? WHERE id=?",end,time,instance,id);
            eventInside(id,"plan_revised",Map.of("version",number(r,"plan_version")+1,"parentVersion",number(r,"plan_version"),"reason","USER_INPUT","next","REAUTHORIZE_AND_QUERY_FRESH"),time);
            eventInside(id,"queued",Map.of("revision",revision+1),time);
            return true;
        });
    }
    public List<Map<String,Object>> dialogueInputs(String id,AuthenticatedUser user) {
        if(!flag(owned(id,user),"dialogue_enabled"))throw RunScope.error("DIALOGUE_REQUIRED");
        return jdbc.queryForList("SELECT revision,field_name,input_text FROM agent_dialogue_inputs WHERE run_id=? ORDER BY revision",id);
    }
    public List<Map<String,Object>> plans(String id,AuthenticatedUser user) {
        if(!flag(owned(id,user),"dialogue_enabled"))throw RunScope.error("DIALOGUE_REQUIRED");
        return jdbc.queryForList("SELECT id,event_type,payload_json,created_at FROM agent_run_events WHERE run_id=? AND event_type IN ('plan_created','plan_revised','plan_step','clarification_required','terminal') ORDER BY id LIMIT 200",id);
    }
    public static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    public static boolean flag(Map<String,Object> row,String key){Object value=row.get(key);return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0;}
}
