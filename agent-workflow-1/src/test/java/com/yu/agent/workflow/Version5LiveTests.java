package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.yu.agent.workflow.application.*;
import com.yu.agent.workflow.commerce.InMemoryCommerceRepository;
import com.yu.agent.workflow.domain.*;
import com.yu.agent.workflow.persistence.InMemoryTaskRepository;
import com.yu.agent.workflow.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="V5_LIVE",matches="true")
class Version5LiveTests {
    @Test void oneSyntheticOrderAndPolicyThroughRealModelAndRag() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var tasks=new InMemoryTaskRepository();
        tasks.create(new AgentTask("v5-live","admin",UserRole.CUSTOMER_SERVICE,"refund","key",Instant.now(),"demo","chat"));
        var access=new RagTaskAccess(tasks,json,new DefaultListableBeanFactory().getBeanProvider(JdbcTemplate.class));
        ReflectionTestUtils.setField(access,"enabled",true);
        var rag=new HttpRagKnowledgeSearchClient(json,"http://127.0.0.1:18091","rag-api",10); rag.taskAccess(access);
        byte[] secret="rag-local-development-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
        Instant now=Instant.now();
        var encoder=new NimbusJwtEncoder(new ImmutableSecret<>(secret));
        String raw=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("rag-local").subject("admin").audience(List.of("rag-api"))
                        .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("tenant_id","demo").build())).getTokenValue();
        Jwt jwt=Jwt.withTokenValue(raw).header("alg","HS256").subject("admin").audience(List.of("rag-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("tenant_id","demo").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,List.of()));
        // Fail before spending provider budget if the temporary fixture has expired.
        assertThat(rag.search("退款",new ToolContext("admin",UserRole.CUSTOMER_SERVICE,"v5-live","preflight","demo"),3)).hasSize(1);
        var gateway=new SpringAiToolCallingGateway(RestClient.builder(),json,"https://api.deepseek.com",
                System.getenv("DEEPSEEK_API_KEY"),"deepseek-flash",25,1200);
        var registry=new ToolRegistry(List.of(new OrderQueryTool(new InMemoryCommerceRepository()),new KnowledgeSearchTool(rag)));
        ReflectionTestUtils.setField(registry,"businessTenant","demo");
        List<Map<String,Object>> calls = new ArrayList<>();
        int[] attempts = {0};
        int callLimit = Math.min(3, Integer.parseInt(System.getenv().getOrDefault("V5_LIVE_MAX_CALLS", "3")));
        ToolCallingGateway recorded = (messages, specs) -> {
            if (++attempts[0] > callLimit) throw new IllegalStateException("Live test model-call budget exhausted");
            var response = gateway.complete(messages, specs);
            Map<String,Object> call = new LinkedHashMap<>(response.metadata());
            call.put("toolNames", response.toolCalls().stream().map(ToolCall::name).toList());
            call.put("content", Objects.toString(response.content(), ""));
            calls.add(call);
            try { Files.writeString(Path.of("target/v5-live-debug.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(calls)); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
            return response;
        };
        var service=new DeepSeekToolCallingService(recorded,registry,new PromptInjectionGuard(),json);
        service.knowledge(rag);
        ReflectionTestUtils.setField(service,"gatewayMode","spring-ai");
        ReflectionTestUtils.setField(service,"allowedTenants","demo");
        ReflectionTestUtils.setField(service,"deadlineSeconds",60L);
        List<Map<String,Object>> usage=new ArrayList<>();
        try {
            var result=service.chat("admin",UserRole.CUSTOMER_SERVICE,"查询订单 ORD-1001 的当前状态，并检索退款政策说明已发货订单退款需要谁审批。","v5-live-trace","v5-live",
                    new DeepSeekToolCallingService.ToolCallingAuditListener() {
                        @Override public void onModelMetadata(int round,Map<String,Object> metadata) { usage.add(metadata); }
                    });
            assertThat(result.executedTools()).contains("order.query","knowledge.search");
            assertThat(result.answer()).contains("主管审批","/api/v1/citations/");
            Files.createDirectories(Path.of("docs/agent/reports"));
            Files.writeString(Path.of("docs/agent/reports/v5-live-2026-11-03.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("scope","one synthetic order and policy; real DeepSeek and RAG API/PG/S3; lexical retrieval; embedding double; in-memory Java task store",
                            "result",result,"modelCalls",usage,"verifiedAt",Instant.now().toString())));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
