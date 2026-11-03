package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.*;
import com.yu.agent.workflow.domain.*;
import com.yu.agent.workflow.persistence.*;
import com.yu.agent.workflow.tools.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class Version5Tests {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    @AfterEach void cleanup() { if (server != null) server.stop(0); SecurityContextHolder.clearContext(); }

    @Test void realSdkPreservesToolRoundTripAndReportsUsageWithoutExecutingToolsItself() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
            int round = calls.incrementAndGet();
            assertThat(request.path("tools").get(0).path("function").path("name").asText()).isEqualTo("order_query");
            if (round == 2) assertThat(request.path("messages").get(2).path("tool_call_id").asText()).isEqualTo("call1");
            String message = round == 1 ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"order_query\",\"arguments\":\"{\\\"orderId\\\":\\\"ORD-1001\\\"}\"}}]}"
                    : "{\"role\":\"assistant\",\"content\":\"SHIPPED\"}";
            reply(exchange, 200, "{\"id\":\"r\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"" + (round == 1 ? "tool_calls" : "stop") + "\",\"message\":" + message + "}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}");
        });
        server.start();
        var gateway = gateway();
        List<Map<String,Object>> specs = List.of(Map.of("type", "function", "function", Map.of("name", "order_query", "description", "read order", "parameters", Map.of("type","object","properties",Map.of("orderId",Map.of("type","string"))))));
        List<Map<String,Object>> messages = new ArrayList<>(List.of(Map.of("role","user","content","order?")));
        var first = gateway.complete(messages, specs);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(first.toolCalls().get(0).arguments()).containsEntry("orderId","ORD-1001");
        messages.add(first.assistantMessage());
        messages.add(Map.of("role","tool","tool_call_id","call1","content","SHIPPED"));
        var second = gateway.complete(messages, specs);
        assertThat(second.content()).isEqualTo("SHIPPED");
        assertThat(second.metadata()).containsEntry("usageSource","provider").containsEntry("inputTokens",20);
    }

    @Test void providerFailureIsBoundedAndDoesNotLeakItsBody() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions", e -> { calls.incrementAndGet(); reply(e,503,"private-provider-details"); });
        server.start();
        assertThatThrownBy(() -> gateway().complete(List.of(Map.of("role","user","content","hello")),List.of()))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("private-provider-details");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void tenantAndEntrypointArePartOfIdempotencyAndRequestMismatchIsRejected() {
        var tasks = new InMemoryTaskRepository();
        var a = task("a","tenant-a","tasks");
        assertThat(tasks.create(a)).isSameAs(a);
        assertThat(tasks.create(task("retry","tenant-a","tasks"))).isSameAs(a);
        assertThat(tasks.create(task("b","tenant-b","tasks")).id()).isEqualTo("b");
        assertThat(tasks.create(task("chat","tenant-a","chat")).id()).isEqualTo("chat");
        assertThatThrownBy(() -> tasks.create(new AgentTask("bad","alice",UserRole.CUSTOMER_SERVICE,"different","key",Instant.now(),"tenant-a","tasks")))
                .isInstanceOf(BusinessException.class);
        authenticate("tenant-b","alice");
        var service = new AgentApplicationService(tasks,new InMemoryEventRepository(),new TaskAuditWriter(tasks,new InMemoryEventRepository()),
                request -> new TaskPlan(List.of()),new ToolRegistry(List.of()),new PromptInjectionGuard(),new ResultEvaluator(),8,30);
        assertThatThrownBy(() -> service.get("a","alice",UserRole.ADMIN)).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.code()).isEqualTo("TASK_NOT_FOUND"));
        assertThatThrownBy(() -> service.approve("a","manager",UserRole.ADMIN,"ok","trace")).isInstanceOf(BusinessException.class);
    }

    @Test void sourcesAreRecheckedAfterGenerationAndDelegationCanBeRevoked() throws Exception {
        var tasks = new InMemoryTaskRepository(); tasks.create(task("a","demo","chat"));
        var access = new RagTaskAccess(tasks,json,new DefaultListableBeanFactory().getBeanProvider(JdbcTemplate.class));
        ReflectionTestUtils.setField(access,"enabled",true);
        ReflectionTestUtils.setField(access,"secret","test-only-local-signing-secret-32-bytes");
        AtomicInteger revision = new AtomicInteger(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/knowledge-bases",e -> reply(e,200,"{\"items\":[{\"id\":\""+ID+"\"}]}"));
        server.createContext("/api/v1/search",e -> reply(e,200,"{\"hits\":[{\"document_id\":\""+ID+"\",\"document_version_id\":\""+ID+"\",\"chunk_id\":\""+ID+"\",\"text\":\"Supervisor approval required\"}]}"));
        server.createContext("/api/v1/citations/",e -> reply(e,200,"{\"is_current\":true,\"version_id\":\""+ID+"\",\"text\":\"Supervisor approval required\",\"content_hash\":\"hash\",\"content_revision\":"+revision.get()+"}"));
        server.start();
        var client = new HttpRagKnowledgeSearchClient(json,url(),"rag-api",2); client.taskAccess(access);
        var context = new ToolContext("alice",UserRole.CUSTOMER_SERVICE,"a","trace","demo");
        authenticate("demo","alice");
        assertThat(client.search("refund",context,3)).hasSize(1);
        client.revalidateTask(context);
        SecurityContextHolder.clearContext(); // A resumed task has no original request JWT.
        assertThat(access.token(context)).contains(".");
        client.revalidateTask(context);
        revision.incrementAndGet();
        assertThatThrownBy(() -> client.revalidateTask(context)).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.code()).isEqualTo("RAG_EVIDENCE_CHANGED"));
        authenticate("demo","alice"); access.revoke("a","alice");
        assertThatThrownBy(() -> client.revalidateTask(context)).isInstanceOf(BusinessException.class);
    }

    @Test void groundedAnswerRejectsInventedOrMissingQuotes() {
        GroundedAnswer answer = new GroundedAnswer(json);
        answer.add("call1","knowledge.search",ToolResult.ok("ok",Map.of("hits",List.of(new KnowledgeSearchClient.Hit(ID,"Policy","Supervisor approval required","/citation",1,ID,ID)))));
        String valid = "{\"statements\":[{\"text\":\"Needs approval\",\"sourceId\":\""+ID+"\",\"quote\":\"Supervisor approval\"}]}";
        assertThat(answer.render(valid)).contains("/citation");
        assertThatThrownBy(() -> answer.render(valid.replace("Supervisor approval","No approval"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> answer.render(valid.replace(ID,"invented"))).isInstanceOf(BusinessException.class);
    }

    @Test void strictChatUsesExplicitSourcesAndReplayRechecksAuthorization() {
        var tasks = new InMemoryTaskRepository();
        var audit = new ChatAuditService(tasks, new InMemoryEventRepository());
        KnowledgeSearchClient knowledge = org.mockito.Mockito.mock(KnowledgeSearchClient.class);
        org.mockito.Mockito.when(knowledge.search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new KnowledgeSearchClient.Hit(ID,"Policy","Supervisor approval required","/citation",1,ID,ID)));
        AtomicInteger rounds = new AtomicInteger();
        ToolCallingGateway gateway = (messages, specs) -> {
            if (rounds.incrementAndGet() == 1) return new ToolCallingResponse(null,
                    List.of(new ToolCall("k1","knowledge_search",Map.of("query","refund"))),Map.of("role","assistant"));
            assertThat(messages.get(messages.size()-1).get("content").toString()).contains("allowedSources", ID);
            return new ToolCallingResponse("{\"statements\":[{\"text\":\"Needs approval\",\"sourceId\":\""+ID+"\",\"quote\":\"Supervisor approval\"}]}",List.of(),Map.of());
        };
        var service = new DeepSeekToolCallingService(gateway,new ToolRegistry(List.of(new KnowledgeSearchTool(knowledge))),new PromptInjectionGuard(),json);
        service.knowledge(knowledge); audit.knowledge(knowledge);
        ReflectionTestUtils.setField(service,"gatewayMode","spring-ai");
        ReflectionTestUtils.setField(service,"allowedTenants","legacy");
        var session=audit.start("alice",UserRole.CUSTOMER_SERVICE,"refund","key","trace");
        var result=service.chat("alice",UserRole.CUSTOMER_SERVICE,"refund","trace",session.taskId(),audit.listener(session));
        audit.complete(session,result);
        assertThat(result.answer()).contains("/citation");
        org.mockito.Mockito.doThrow(BusinessException.of("RAG_ACCESS_DENIED","revoked")).when(knowledge).revalidateTask(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> audit.replay(session)).isInstanceOf(BusinessException.class);
    }

    private SpringAiToolCallingGateway gateway() { return new SpringAiToolCallingGateway(RestClient.builder(),json,url(),"test-key","test-model",2,200); }
    private String url() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    private AgentTask task(String id,String tenant,String entrypoint) { return new AgentTask(id,"alice",UserRole.CUSTOMER_SERVICE,"query","key",Instant.now(),tenant,entrypoint); }
    static void authenticate(String tenant,String subject) {
        Jwt jwt=Jwt.withTokenValue("test-verified-token").header("alg","HS256").subject(subject).audience(List.of("rag-api"))
                .claim("tenant_id",tenant).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,List.of()));
    }
    private void reply(com.sun.net.httpserver.HttpExchange e,int status,String text) throws java.io.IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type","application/json");
        e.sendResponseHeaders(status,bytes.length); e.getResponseBody().write(bytes); e.close();
    }
}
