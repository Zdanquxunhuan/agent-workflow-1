package com.yu.agent.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.api.BusinessException;
import com.yu.agent.workflow.tools.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Forwards only a verified, RAG-audience token. Background execution without delegation fails closed. */
@Component
@ConditionalOnProperty(name = "agent.knowledge.backend", havingValue = "http")
public class HttpRagKnowledgeSearchClient implements KnowledgeSearchClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI base;
    private final String audience;
    private final Duration timeout;

    public HttpRagKnowledgeSearchClient(ObjectMapper mapper,
            @Value("${agent.knowledge.http.base-url:}") String baseUrl,
            @Value("${agent.knowledge.http.audience:rag-api}") String audience,
            @Value("${agent.knowledge.http.timeout-seconds:15}") long timeoutSeconds) {
        this.mapper = mapper;
        this.base = URI.create(baseUrl.replaceAll("/+$", "") + "/");
        if (base.getHost() == null || !List.of("http", "https").contains(base.getScheme())
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("RAG base URL must be a configured HTTP(S) origin");
        }
        this.audience = audience;
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(timeoutSeconds, 120)));
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public List<Hit> search(String query, ToolContext context, int limit) {
        if (!"rag-stateless".equals(context.taskId())) {
            throw BusinessException.of("RAG_TASK_TENANCY_REQUIRED",
                    "Use the stateless knowledge endpoint until the task ledger supports tenant isolation");
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated()
                || !context.userId().equals(auth.getToken().getSubject())
                || !auth.getToken().getAudience().contains(audience)
                || auth.getToken().getClaimAsString("tenant_id") == null
                || auth.getToken().getClaimAsString("tenant_id").isBlank()) {
            throw BusinessException.of("RAG_IDENTITY_REQUIRED",
                    "Knowledge search requires a verified tenant identity with the RAG audience");
        }
        String token = auth.getToken().getTokenValue();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            JsonNode libraries = call("api/v1/knowledge-bases", null, token, deadline);
            if (!libraries.path("items").isArray()) throw new IllegalArgumentException("Invalid KB response");
            List<String> kbIds = new ArrayList<>();
            if (libraries.path("items").size() > 5)
                throw BusinessException.of("RAG_KB_SCOPE_REQUIRED", "Use the RAG search API to select up to five knowledge bases");
            for (JsonNode kb : libraries.path("items")) {
                kbIds.add(UUID.fromString(kb.path("id").asText()).toString());
                if (kbIds.size() == 5) break;
            }
            if (kbIds.isEmpty()) return List.of();
            JsonNode result = call("api/v1/search", Map.of("query", query, "knowledge_base_ids", kbIds,
                    "top_k", Math.max(1, Math.min(limit, 20))), token, deadline);
            if (!result.path("hits").isArray()) throw new IllegalArgumentException("Invalid search response");
            List<Hit> hits = new ArrayList<>();
            for (JsonNode hit : result.path("hits")) {
                String documentId = UUID.fromString(hit.path("document_id").asText()).toString();
                String versionId = UUID.fromString(hit.path("document_version_id").asText()).toString();
                String chunkId = UUID.fromString(hit.path("chunk_id").asText()).toString();
                String snippet = hit.path("text").asText();
                if (snippet.isBlank() || snippet.length() > 16000) throw new IllegalArgumentException("Invalid evidence");
                // Never trust a model/server-provided external citation URL.
                hits.add(new Hit(documentId, hit.path("title").asText(), snippet,
                        base.resolve("api/v1/citations/" + chunkId).toString(),
                        hit.path("score").isNumber() ? hit.path("score").asDouble()
                                : (hit.path("distance").isNumber() ? 1 - hit.path("distance").asDouble() : 0),
                        versionId, chunkId));
                if (hits.size() == limit) break;
            }
            return List.copyOf(hits);
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw BusinessException.of("RAG_UNAVAILABLE", "Knowledge search was interrupted");
        } catch (Exception ex) {
            throw BusinessException.of("RAG_UNAVAILABLE", "Knowledge search failed or returned an invalid response");
        }
    }

    private JsonNode call(String path, Object body, String token, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw BusinessException.of("RAG_UNAVAILABLE", "Knowledge search deadline exceeded");
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofNanos(remaining)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var pending = client.sendAsync(request.build(), ignored -> new LimitedBodySubscriber());
        try {
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404)
                throw BusinessException.of("RAG_ACCESS_DENIED", "Knowledge access was denied");
            if (response.statusCode() == 429)
                throw BusinessException.of("RAG_RATE_LIMITED", "Knowledge service rate limit reached");
            if (response.statusCode() != 200)
                throw BusinessException.of("RAG_UNAVAILABLE", "Knowledge service is unavailable");
            return mapper.readTree(response.body());
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    /** Bounds both memory and the complete response duration, including a stalled response body. */
    private static class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 1_048_576) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalArgumentException("RAG response too large"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

