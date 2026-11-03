package com.yu.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.application.HttpRagKnowledgeSearchClient;
import com.yu.agent.workflow.domain.UserRole;
import com.yu.agent.workflow.tools.ToolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class HttpRagKnowledgeSearchClientTests {
    private HttpServer server;
    private HttpRagKnowledgeSearchClient client;
    private final AtomicInteger calls = new AtomicInteger();
    private int searchStatus = 200;
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private String searchBody = "{\"hits\":[{\"document_id\":\"" + ID + "\",\"document_version_id\":\"" + ID
            + "\",\"chunk_id\":\"" + ID + "\",\"title\":\"Policy\",\"text\":\"Supervisor approval required\","
            + "\"distance\":0.1,\"citation\":{\"url\":\"https://untrusted.example/leak\"}}]}";
    private final ToolContext context = new ToolContext("alice", UserRole.CUSTOMER_SERVICE, "rag-stateless", "trace", "demo");

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/knowledge-bases", exchange -> {
            calls.incrementAndGet();
            byte[] body = ("{\"items\":[{\"id\":\"" + ID + "\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/search", exchange -> {
            calls.incrementAndGet();
            byte[] body = searchBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(searchStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new HttpRagKnowledgeSearchClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "rag-api", 2);
        authenticate("alice", List.of("rag-api"));
    }

    private void authenticate(String subject, List<String> audiences) {
        Jwt jwt = Jwt.withTokenValue("verified-test-token").header("alg", "RS256")
                .subject(subject).audience(audiences).claim("tenant_id", "demo")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void cleanup() { server.stop(0); SecurityContextHolder.clearContext(); }

    @Test
    void preservesVersionedEvidenceAndBuildsCitationAgainstConfiguredOrigin() {
        var hits = client.search("refund", context, 3);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).documentVersionId()).isEqualTo(ID);
        assertThat(hits.get(0).citation()).startsWith("http://127.0.0.1:").endsWith("/api/v1/citations/" + ID);
        assertThat(hits.get(0).snippet()).contains("Supervisor");
    }

    @Test
    void usesV2RankingScoreWithoutTreatingLexicalNullDistanceAsPerfectSimilarity() {
        searchBody = searchBody.replace("\"distance\":0.1", "\"distance\":null,\"score\":0.032,\"score_type\":\"rrf\"");
        assertThat(client.search("q", context, 3).get(0).score()).isEqualTo(0.032);
    }

    @Test
    void missingOrMismatchedIdentityNeverCallsRemoteService() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> client.search("q", context, 3)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("verified tenant identity");
        authenticate("bob", List.of("rag-api"));
        assertThatThrownBy(() -> client.search("q", context, 3)).isInstanceOf(BusinessException.class);
        authenticate("alice", List.of("workflow-agent"));
        assertThatThrownBy(() -> client.search("q", context, 3)).isInstanceOf(BusinessException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void legacyTaskLedgerCannotPersistTenantEvidence() {
        assertThatThrownBy(() -> client.search("q", new ToolContext("alice", UserRole.CUSTOMER_SERVICE,
                "unscoped-task", "trace"), 3)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo("RAG_TASK_TENANCY_REQUIRED"));
        assertThat(calls).hasValue(0);
    }

    @Test
    void outageAndDeniedAccessAreNotConvertedToEmptyEvidence() {
        searchStatus = 503;
        assertThatThrownBy(() -> client.search("q", context, 3)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo("RAG_UNAVAILABLE"));
        searchStatus = 403;
        assertThatThrownBy(() -> client.search("q", context, 3)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo("RAG_ACCESS_DENIED"));
    }

    @Test
    void emptyEvidenceAndInvalidSchemaRemainDistinct() {
        searchBody = "{\"hits\":[]}";
        assertThat(client.search("q", context, 3)).isEmpty();
        searchBody = "{\"unexpected\":true}";
        assertThatThrownBy(() -> client.search("q", context, 3)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.code()).isEqualTo("RAG_UNAVAILABLE"));
    }
}
