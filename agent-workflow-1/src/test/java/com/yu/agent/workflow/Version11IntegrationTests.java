package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.runtime.*;
import com.yu.agent.workflow.security.AuthenticatedUser;
import com.yu.agent.workflow.domain.UserRole;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.http.MediaType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Opt-in, dedicated disposable MySQL only. Real Spring wiring/JWT/HTTP SDK, no paid model. */
@EnabledIfEnvironmentVariable(named="V11_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:13371/v11_test\\?.+")
@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Version11IntegrationTests {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final String CHUNK="11111111-1111-4111-8111-111111111111";
    static final String POLICY="已发货订单退款需要主管审批。";
    static final ConcurrentLinkedQueue<Map<String,Object>> RESPONSES=new ConcurrentLinkedQueue<>();
    static final AtomicInteger CALLS=new AtomicInteger();
    static final AtomicBoolean REVOKED=new AtomicBoolean();
    static final List<Map<String,Object>> EVIDENCE=new CopyOnWriteArrayList<>();
    static volatile CountDownLatch modelEntered,releaseModel;
    static final HttpServer STUB=server();
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuntimeService service;
    @Autowired RuntimeSettings settings;
    @Autowired DialogueSettings dialogueSettings;
    @Autowired com.yu.agent.workflow.commerce.CommerceOutbox commerceOutbox;
    @Autowired com.yu.agent.workflow.commerce.CommerceGateway commerceGateway;
    @MockitoSpyBean RuntimeLedger ledger;
    String owner,token;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String url=System.getenv("V11_MYSQL_URL");
        if(url==null || !url.startsWith("jdbc:mysql://127.0.0.1:13371/v11_test?"))throw new IllegalStateException("Dedicated V11 fixture required");
        Map<String,String> values=new LinkedHashMap<>(Map.of(
                "agent.workflow.persistence","jdbc","mysql.url",url,"mysql.user","root","mysql.password","v11_isolated_test_only",
                "agent.deepseek.enabled","true","agent.deepseek.gateway","spring-ai","agent.deepseek.base-url",base(),
                "agent.deepseek.api-key","local-stub-only","agent.deepseek.model","v11-stub","agent.deepseek.allowed-tenants","legacy"));
        values.putAll(Map.of("agent.runtime.enabled","true","agent.runtime.allow-local-http","true","agent.runtime.request-budget","1",
                "agent.runtime.daily-budget","10","agent.runtime.primary.input-per-million","1","agent.runtime.primary.output-per-million","1",
                "agent.runtime.primary.price-version","fixture-1","agent.dialogue.enabled","true","agent.commerce.seed-demo-data","true","agent.commerce.dispatcher.enabled","false"));
        values.putAll(Map.of("agent.auth.mode","local","agent.auth.dev-token-enabled","true","agent.auth.audience","rag-api",
                "agent.knowledge.backend","http","agent.knowledge.http.base-url",base(),"agent.knowledge.task-enabled","true",
                "agent.memory.enabled","true","agent.memory.auto-extract","false","agent.memory.index-url",base(),"agent.memory.index-secret","v11-separate-memory-index-test-secret-12345"));
        values.put("agent.auth.local-secret","v11-local-jwt-signing-test-secret-123456789");
        values.put("agent.runtime.backup.enabled","false");values.put("agent.mcp.shipment.enabled","false");
        values.put("mysql.pool.max-size","20");values.put("logging.level.root","WARN");
        values.forEach((key,value)->r.add(key,()->value));
    }
    static String base(){return "http://127.0.0.1:"+STUB.getAddress().getPort();}
    static HttpServer server() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",e->{
                JSON.readTree(e.getRequestBody());CALLS.incrementAndGet();
                var entered=modelEntered;var release=releaseModel;
                if(entered!=null && release!=null){entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture barrier timeout");}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
                var message=RESPONSES.poll();
                if(message==null){reply(e,500,Map.of("error","Unexpected extra model call"));return;}
                reply(e,200,Map.of("id","fixture","model","v11-stub","choices",List.of(Map.of("index",0,"finish_reason",message.containsKey("tool_calls")?"tool_calls":"stop","message",message)),
                        "usage",Map.of("prompt_tokens",100,"completion_tokens",20,"total_tokens",120)));
            });
            server.createContext("/api/v1/knowledge-bases",e->reply(e,200,Map.of("items",List.of(Map.of("id",CHUNK)))));
            server.createContext("/api/v1/search",e->{
                var query=JSON.readTree(e.getRequestBody()).path("query").asText();
                reply(e,200,Map.of("hits",query.equals("empty")?List.of():List.of(Map.of("document_id",CHUNK,"document_version_id",CHUNK,"chunk_id",CHUNK,"title","Synthetic policy","text",POLICY))));
            });
            server.createContext("/api/v1/citations/",e->reply(e,REVOKED.get()?403:200,REVOKED.get()?Map.of("error","revoked"):
                    Map.of("is_current",true,"version_id",CHUNK,"text",POLICY,"content_hash","fixture-hash","content_revision",1)));
            server.createContext("/internal/",e->reply(e,200,Map.of("hits",List.of())));
            server.setExecutor(Executors.newCachedThreadPool(r->{var t=new Thread(r,"v11-local-http");t.setDaemon(true);return t;}));
            server.start();return server;
        }catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    static void reply(HttpExchange e,int code,Object value) throws java.io.IOException {
        byte[] bytes=JSON.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");
        e.sendResponseHeaders(code,bytes.length);e.getResponseBody().write(bytes);e.close();
    }
    static void tool(String name,Map<String,Object> args) throws Exception {
        RESPONSES.add(Map.of("role","assistant","tool_calls",List.of(Map.of("id","call-"+UUID.randomUUID(),"type","function",
                "function",Map.of("name",name,"arguments",JSON.writeValueAsString(args))))));
    }
    static void answer() throws Exception {RESPONSES.add(Map.of("role","assistant","content",JSON.writeValueAsString(Map.of("statements",List.of(Map.of("text","需要主管审批","sourceId",CHUNK,"quote",POLICY))))));}
    @BeforeEach void setup() throws Exception {
        await(()->((ThreadPoolExecutor)ReflectionTestUtils.getField(service,"workers")).getActiveCount()==0);
        RESPONSES.clear();CALLS.set(0);REVOKED.set(false);modelEntered=null;releaseModel=null;reset(ledger);
        owner="v11-"+UUID.randomUUID();token=token(owner,"legacy","CUSTOMER_SERVICE");
    }
    @AfterEach void cleanup() {if(releaseModel!=null)releaseModel.countDown();}
    @AfterAll static void report() throws Exception {
        STUB.stop(0);Files.createDirectories(Path.of("target/v11"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/v11/scenarios-"+System.currentTimeMillis()+".json").toFile(),EVIDENCE);
    }
    String token(String subject,String tenant,String role) {
        var claims=JwtClaimsSet.builder().issuer("agent-workflow-local").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .audience(List.of("rag-api")).claim("tenant_id",tenant).claim("roles",List.of(role)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    JsonNode request(MockHttpServletRequestBuilder request,Object body,String key,int status) throws Exception {return request(request,body,key,status,token);}
    JsonNode request(MockHttpServletRequestBuilder request,Object body,String key,int status,String jwt) throws Exception {
        request.header("Authorization","Bearer "+jwt);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body));
        if(key!=null)request.header("Idempotency-Key",key);
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return JSON.readTree(response.getContentAsByteArray());
    }
    String start(String message) throws Exception {return request(post("/api/v1/agent/dialogues"),Map.of("message",message),UUID.randomUUID().toString(),202).path("data").path("id").asText();}
    JsonNode state(String id) throws Exception {return request(get("/api/v1/agent/runs/"+id),null,null,200).path("data");}
    JsonNode stopped(String id,String expected) throws Exception {
        await(()->!Set.of("QUEUED","RUNNING").contains(jdbc.queryForObject("SELECT status FROM agent_runs WHERE id=?",String.class,id)));
        var value=state(id);assertThat(value.path("status").asText()).as(value.toString()).isEqualTo(expected);return value;
    }
    static void await(BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("Timed out waiting for local fixture");Thread.sleep(25);}
    }
    void input(String id,long revision,String key,int status) throws Exception {request(post("/api/v1/agent/dialogues/"+id+"/inputs"),Map.of("revision",revision,"field","orderId","value","ORD-1001"),key,status);}
    void record(String name,String id,long started) throws Exception {
        var data=new LinkedHashMap<String,Object>();data.put("scenario",name);data.put("elapsedMs",(System.nanoTime()-started)/1_000_000);
        data.put("run",JSON.convertValue(state(id),Map.class));data.put("stubModelCalls",CALLS.get());data.put("realProviderCalls",0);
        data.put("attempts",jdbc.queryForList("SELECT usage_source,input_tokens,output_tokens,accounted_micros,status FROM agent_model_attempts WHERE run_id=? ORDER BY started_at",id));
        EVIDENCE.add(data);
    }

    @Test @Order(1) void normalQueryUsesRealSdkJwtDatabaseAndVerifiedCitations() throws Exception {
        long started=System.nanoTime();tool("order_query",Map.of("orderId","ORD-1001"));tool("knowledge_search",Map.of("query","refund"));answer();
        String id=start("ORD-1001 能退款吗");var done=stopped(id,"SUCCEEDED");
        assertThat(done.path("attempt_count").asInt()).isEqualTo(3);assertThat(done.path("tool_count").asInt()).isEqualTo(2);
        assertThat(done.path("accounted_tokens").asInt()).isEqualTo(360);assertThat(done.path("accounted_micros").asInt()).isEqualTo(360);
        assertThat(request(get("/api/v1/agent/runs/"+id+"/result"),null,null,200).toString()).contains(POLICY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND type='SQL'",Integer.class)).isEqualTo(12);
        assertThat(request(get("/api/v1/agent/runs/"+id),null,null,404,token("other","legacy","ADMIN")).path("errorCode").asText()).isEqualTo("NOT_FOUND");
        record("normal_query",id,started);
    }

    @Test @Order(2) void clarificationResumesSameRunAndTaskWithoutResettingBudget() throws Exception {
        long started=System.nanoTime();tool("agent_clarify",Map.of("field","orderId"));String id=start("能退款吗");
        var waiting=stopped(id,"WAITING_INPUT");String task=waiting.path("task_id").asText();
        assertThat(waiting.path("attempt_count").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,task)).isEqualTo("WAITING_INPUT");
        assertThat(waiting.path("reserved_tokens").asInt()).isZero();
        tool("order_query",Map.of("orderId","ORD-1001"));tool("knowledge_search",Map.of("query","refund"));answer();
        token=token(owner,"legacy","CUSTOMER_SERVICE");String key=UUID.randomUUID().toString();input(id,0,key,202);
        var done=stopped(id,"SUCCEEDED");input(id,0,key,202);input(id,0,UUID.randomUUID().toString(),409);
        assertThat(done.path("task_id").asText()).isEqualTo(task);assertThat(done.path("attempt_count").asInt()).isEqualTo(4);
        assertThat(done.path("accounted_tokens").asInt()).isEqualTo(480);assertThat(CALLS.get()).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT sequence_no FROM task_steps WHERE task_id=? ORDER BY sequence_no",Integer.class,task)).containsExactly(1,2);
        record("clarification_resume",id,started);
    }

    @Test @Order(3) void emptyKnowledgeAllowsOneRevisionButRepeatedActionStops() throws Exception {
        long started=System.nanoTime();tool("knowledge_search",Map.of("query","empty"));tool("knowledge_search",Map.of("query","refund"));answer();
        String id=start("退款政策");var done=stopped(id,"SUCCEEDED");assertThat(done.path("replan_count").asInt()).isEqualTo(1);
        assertThat(request(get("/api/v1/agent/dialogues/"+id+"/plans"),null,null,200).toString()).contains("NO_EVIDENCE","parentVersion");
        record("bounded_replan",id,started);
        await(()->((ThreadPoolExecutor)ReflectionTestUtils.getField(service,"workers")).getActiveCount()==0);
        tool("knowledge_search",Map.of("query","empty"));tool("knowledge_search",Map.of("query"," EMPTY "));
        String repeated=start("退款政策二");assertThat(stopped(repeated,"FAILED").path("error_code").asText()).isEqualTo("DIALOGUE_REPEATED_ACTION");
    }

    @Test @Order(4) void sourceRevocationStopsResumeBeforeAnotherModelCall() throws Exception {
        long started=System.nanoTime();tool("knowledge_search",Map.of("query","refund"));tool("agent_clarify",Map.of("field","orderId"));
        String id=start("查政策后请核对我的订单");stopped(id,"WAITING_INPUT");REVOKED.set(true);
        input(id,0,UUID.randomUUID().toString(),202);var failed=stopped(id,"FAILED");
        assertThat(failed.path("error_code").asText()).isEqualTo("RAG_ACCESS_DENIED");assertThat(CALLS.get()).isEqualTo(2);
        request(get("/api/v1/agent/runs/"+id+"/result"),null,null,409);record("source_revocation",id,started);
    }

    @Test @Order(5) void budgetReservationAndUnknownSettlementAreDurableAndBounded() throws Exception {
        long started=System.nanoTime();tool("agent_clarify",Map.of("field","orderId"));String id=start("预算边界");stopped(id,"WAITING_INPUT");
        jdbc.update("UPDATE agent_runs SET max_attempts=attempt_count WHERE id=?",id);
        input(id,0,UUID.randomUUID().toString(),202);assertThat(stopped(id,"FAILED").path("error_code").asText()).isEqualTo("RUN_MODEL_LIMIT");assertThat(CALLS.get()).isEqualTo(1);
        record("budget_exhaustion",id,started);
        var user=new AuthenticatedUser(owner,UserRole.CUSTOMER_SERVICE,Set.of("CUSTOMER_SERVICE"),"legacy");
        String unknown=ledger.openDialogue(user,UUID.randomUUID().toString(),"unknown",System.currentTimeMillis()+60000,dialogueSettings).id();
        assertThat(ledger.claim(unknown)).isTrue();var reservation=ledger.reserve(unknown,settings.primary,100,"default",false);
        ledger.settle(reservation,Map.of(),"MODEL_TIMEOUT");ledger.settle(reservation,Map.of(),"MODEL_TIMEOUT");
        var charged=ledger.describe(unknown,user);assertThat(((Number)charged.get("accounted_micros")).longValue()).isEqualTo(reservation.cost());
        assertThat(((Number)charged.get("reserved_micros")).longValue()).isZero();ledger.finish(unknown,"MODEL_TIMEOUT");
    }

    @Test @Order(6) void concurrentResumeAndCancelCannotBeOverwrittenByOldPausedWorker() throws Exception {
        long started=System.nanoTime();CountDownLatch paused=new CountDownLatch(1),releaseOld=new CountDownLatch(1);
        doAnswer(inv->{inv.callRealMethod();paused.countDown();if(!releaseOld.await(10,TimeUnit.SECONDS))throw new AssertionError("pause barrier timeout");return null;})
                .when(ledger).pause(anyString(),anyString(),any(Runnable.class));
        var pool=Executors.newFixedThreadPool(2);
        try {
            tool("agent_clarify",Map.of("field","orderId"));String id=start("并发恢复");assertThat(paused.await(10,TimeUnit.SECONDS)).isTrue();
            stopped(id,"WAITING_INPUT");modelEntered=new CountDownLatch(1);releaseModel=new CountDownLatch(1);
            tool("knowledge_search",Map.of("query","refund"));
            CountDownLatch go=new CountDownLatch(1);
            Callable<Integer> resume=()->{go.await();return mvc.perform(post("/api/v1/agent/dialogues/"+id+"/inputs").header("Authorization","Bearer "+token)
                    .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"revision\":0,\"field\":\"orderId\",\"value\":\"ORD-1001\"}")).andReturn().getResponse().getStatus();};
            var a=pool.submit(resume);var b=pool.submit(resume);go.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,409);
            assertThat(modelEntered.await(10,TimeUnit.SECONDS)).isTrue();releaseOld.countDown();
            await(()->((ThreadPoolExecutor)ReflectionTestUtils.getField(service,"workers")).getActiveCount()==1);
            assertThat(state(id).path("status").asText()).isEqualTo("RUNNING");
            request(post("/api/v1/agent/runs/"+id+"/cancel"),null,null,200);releaseModel.countDown();
            assertThat(stopped(id,"CANCELLED").path("error_code").asText()).isEqualTo("RUN_CANCELLED");
            request(get("/api/v1/agent/runs/"+id+"/result"),null,null,409);assertThat(CALLS.get()).isEqualTo(2);record("resume_cancel_race",id,started);
        } finally {releaseOld.countDown();if(releaseModel!=null)releaseModel.countDown();pool.shutdownNow();}
    }

    @Test @Order(7) void conversationUsesConfirmedPreferenceAndForgettingRevokesHistory() throws Exception {
        var memory=request(post("/api/v1/memories"),Map.of("slot","response_style","value","concise","days",1),UUID.randomUUID().toString(),200).path("data");
        String mid=memory.path("id").asText();request(post("/api/v1/memories/"+mid+"/confirm"),Map.of("revision",1),null,200);
        var conversation=request(post("/api/v1/conversations"),null,null,200).path("data");String cid=conversation.path("id").asText();
        tool("knowledge_search",Map.of("query","refund"));answer();
        var reply=request(post("/api/v1/conversations/"+cid+"/messages"),Map.of("revision",0,"message","退款政策"),UUID.randomUUID().toString(),200);
        assertThat(reply.toString()).contains(POLICY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_memory_uses WHERE memory_id=?",Integer.class,mid)).isEqualTo(1);
        request(delete("/api/v1/memories/"+mid+"?revision=2"),null,null,200);
        assertThat(request(get("/api/v1/conversations/"+cid),null,null,409).path("errorCode").asText()).isEqualTo("MEMORY_CHANGED");
    }

    @Test @Order(8) void mcpGrantRevocationInvalidatesStoredAnswerWithoutCallingModel() throws Exception {
        tool("knowledge_search",Map.of("query","refund"));answer();String id=start("授权来源回放");var done=stopped(id,"SUCCEEDED");String task=done.path("task_id").asText();
        jdbc.update("INSERT INTO mcp_order_grants(tenant_id,subject_id,order_id,enabled,expires_at) VALUES ('legacy',?,'ORD-1001',true,UNIX_TIMESTAMP()+300)",owner);
        jdbc.update("INSERT INTO mcp_task_sources(task_id,order_id,tenant_id,subject_id,grant_revision) VALUES (?,'ORD-1001','legacy',?,1)",task,owner);
        request(get("/api/v1/agent/runs/"+id+"/result"),null,null,200);
        jdbc.update("UPDATE mcp_order_grants SET enabled=false WHERE tenant_id='legacy' AND subject_id=? AND order_id='ORD-1001'",owner);
        assertThat(request(get("/api/v1/agent/runs/"+id+"/result"),null,null,409).path("errorCode").asText()).isEqualTo("MCP_EVIDENCE_CHANGED");
        assertThat(CALLS.get()).isEqualTo(2);
    }

    @Test @Order(9) void refundRequiresIndependentApprovalAndConfirmsOnlyOnceInSandbox() throws Exception {
        long started=System.nanoTime();
        RESPONSES.add(Map.of("role","assistant","content",JSON.writeValueAsString(Map.of("steps",List.of(
                Map.of("toolName","refund.create","arguments",Map.of("orderId","ORD-1001","amount",20),"requiresApproval",true))))));
        // Give the requester supervisor authority too: self-approval must still be denied.
        token=token(owner,"legacy","SUPERVISOR");String key=UUID.randomUUID().toString();
        var body=Map.of("request","为订单 ORD-1001 退款 20 元");
        var created=request(post("/api/v1/tasks"),body,key,201).path("data");String task=created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        request(post("/api/v1/tasks/"+task+"/approve"),Map.of("reason","self"),null,403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE task_id=?",Integer.class,task)).isZero();
        String manager=token("manager-"+owner,"legacy","SUPERVISOR");
        var accepted=request(post("/api/v1/tasks/"+task+"/approve"),Map.of("reason","synthetic sample"),null,200,manager).path("data");
        assertThat(accepted.path("status").asText()).isEqualTo("WAITING_EXTERNAL");
        var dispatcher=new com.yu.agent.workflow.commerce.CommerceCommandDispatcher(commerceOutbox,commerceGateway,1,30,10,5);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(request(get("/api/v1/tasks/"+task),null,null,200).path("data").path("status").asText()).isEqualTo("SUCCEEDED");
        request(post("/api/v1/tasks/"+task+"/approve"),Map.of("reason","retry"),null,200,manager);
        assertThat(request(post("/api/v1/tasks"),body,key,201).path("data").path("id").asText()).isEqualTo(task);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE task_id=? AND status='SUCCEEDED'",Integer.class,task)).isEqualTo(1);
        assertThat(CALLS.get()).isEqualTo(1);
        EVIDENCE.add(Map.of("scenario","sandbox_refund","taskId",task,"elapsedMs",(System.nanoTime()-started)/1_000_000,
                "stubPlannerCalls",CALLS.get(),"realProviderCalls",0,"successfulRefundRows",1,"selfApproval","denied"));
    }
}
