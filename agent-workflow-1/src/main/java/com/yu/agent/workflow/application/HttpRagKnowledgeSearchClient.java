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
    private RagTaskAccess taskAccess;
    @org.springframework.beans.factory.annotation.Autowired
    public void taskAccess(RagTaskAccess taskAccess) { this.taskAccess = taskAccess; }

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
    public void prepareTask(ToolContext context) {
        if (taskAccess == null) throw BusinessException.of("RAG_TASK_TENANCY_REQUIRED", "Task knowledge integration is disabled");
        String token = taskAccess.token(context);
        try {
            var result = call("api/v1/knowledge-bases", null, token, System.nanoTime() + timeout.toNanos());
            if (!result.path("items").isArray() || result.path("items").size() > 5)
                throw BusinessException.of("RAG_KB_SCOPE_REQUIRED", "Select at most five accessible knowledge bases");
            List<String> ids = new ArrayList<>();
            for (var kb : result.path("items")) ids.add(UUID.fromString(kb.path("id").asText()).toString());
            taskAccess.scope(context, ids);
        } catch (BusinessException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw BusinessException.of("RAG_UNAVAILABLE", "Delegation setup interrupted"); }
        catch (Exception ex) { throw BusinessException.of("RAG_UNAVAILABLE", "Could not establish knowledge scope"); }
    }

    @Override
    public void renewTaskAccess(ToolContext context) {
        if(taskAccess==null) throw BusinessException.of("RAG_TASK_TENANCY_REQUIRED","Task knowledge integration is disabled");
        String token=requestToken(context);
        try {
            long deadline=System.nanoTime()+timeout.toNanos();
            var libraries=call("api/v1/knowledge-bases",null,token,deadline);
            if(!libraries.path("items").isArray())throw new IllegalArgumentException();
            List<String> ids=new ArrayList<>();
            for(var kb:libraries.path("items"))ids.add(UUID.fromString(kb.path("id").asText()).toString());
            for(var source:taskAccess.sources(context.taskId())) {
                var current=call("api/v1/citations/"+source.chunkId(),null,token,deadline);
                if(!current.path("is_current").asBoolean() || !source.versionId().equals(current.path("version_id").asText())
                        || !source.hash().equals(current.path("content_hash").asText())
                        || !current.path("content_revision").isIntegralNumber() || source.revision()!=current.path("content_revision").asLong())
                    throw BusinessException.of("RAG_EVIDENCE_CHANGED","Renewal requires unchanged readable source evidence");
            }
            taskAccess.renew(context,ids);
        }catch(BusinessException ex){throw ex;}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw BusinessException.of("RAG_UNAVAILABLE","Renewal interrupted");}
        catch(Exception ex){throw BusinessException.of("RAG_UNAVAILABLE","Could not renew knowledge access");}
    }

    @Override
    public List<Hit> search(String query, ToolContext context, int limit) {
        boolean stateless = "rag-stateless".equals(context.taskId());
        if (!stateless && taskAccess == null) {
            throw BusinessException.of("RAG_TASK_TENANCY_REQUIRED",
                    "Use the stateless knowledge endpoint until the task ledger supports tenant isolation");
        }
        String token = stateless ? requestToken(context) : taskAccess.token(context);
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            JsonNode libraries = call("api/v1/knowledge-bases", null, token, deadline);
            if (!libraries.path("items").isArray()) throw new IllegalArgumentException("Invalid KB response");
            List<String> kbIds = new ArrayList<>();
            if (libraries.path("items").size() > 5)
                throw BusinessException.of("RAG_KB_SCOPE_REQUIRED", "Select at most five accessible knowledge bases");
            for (JsonNode kb : libraries.path("items")) kbIds.add(UUID.fromString(kb.path("id").asText()).toString());
            if (!stateless) kbIds = taskAccess.scope(context, kbIds);
            if (kbIds.isEmpty()) return List.of();
            JsonNode result = call("api/v1/search", Map.of("query", query, "knowledge_base_ids", kbIds,
                    "top_k", Math.max(1, Math.min(limit, 20))), token, deadline);
            if (!result.path("hits").isArray()) throw new IllegalArgumentException("Invalid search response");
            List<Hit> hits = new ArrayList<>();
            List<RagTaskAccess.Source> sources = new ArrayList<>();
            for (JsonNode hit : result.path("hits")) {
                String documentId = UUID.fromString(hit.path("document_id").asText()).toString();
                String versionId = UUID.fromString(hit.path("document_version_id").asText()).toString();
                String chunkId = UUID.fromString(hit.path("chunk_id").asText()).toString();
                String snippet = hit.path("text").asText();
                if (snippet.isBlank() || snippet.length() > 16000) throw new IllegalArgumentException("Invalid evidence");
                if (!stateless) {
                    JsonNode current = call("api/v1/citations/" + chunkId, null, token, deadline);
                    if (!current.path("is_current").asBoolean() || !versionId.equals(current.path("version_id").asText())
                            || !snippet.equals(current.path("text").asText()) || !current.path("content_revision").isIntegralNumber())
                        throw BusinessException.of("RAG_EVIDENCE_CHANGED", "Knowledge evidence changed");
                    sources.add(new RagTaskAccess.Source(chunkId, versionId, current.path("content_hash").asText(),
                            current.path("content_revision").asLong()));
                }
                hits.add(new Hit(documentId, hit.path("title").asText(), snippet,
                        base.resolve("api/v1/citations/" + chunkId).toString(), (hit.path("score").isNumber() ? hit.path("score").asDouble() : 1 - hit.path("distance").asDouble()), versionId, chunkId));
                if (hits.size() == limit) break;
            }
            if (!stateless) taskAccess.remember(context.taskId(), sources);
            return List.copyOf(hits);
        } catch (BusinessException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw BusinessException.of("RAG_UNAVAILABLE", "Knowledge search interrupted"); }
        catch (Exception ex) { throw BusinessException.of("RAG_UNAVAILABLE", "Knowledge search failed"); }
    }

    private String requestToken(ToolContext context) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated()
                || !context.userId().equals(auth.getToken().getSubject())
                || !auth.getToken().getAudience().contains(audience)
                || auth.getToken().getClaimAsString("tenant_id") == null
                || auth.getToken().getClaimAsString("tenant_id").isBlank()
                || !context.tenantId().equals(auth.getToken().getClaimAsString("tenant_id"))) {
            throw BusinessException.of("RAG_IDENTITY_REQUIRED",
                    "Knowledge search requires a verified tenant identity with the RAG audience");
        }
        return auth.getToken().getTokenValue();
    }

    @Override
    public void revalidateTask(ToolContext context) {
        revalidateTask(context, new java.util.HashSet<>(), System.nanoTime() + timeout.toNanos());
    }

    @Override
    public void inheritTaskEvidence(ToolContext context, List<String> sourceTasks) {
        if (taskAccess == null && !sourceTasks.isEmpty())
            throw BusinessException.of("RAG_TASK_TENANCY_REQUIRED", "Persistent knowledge dependencies are required");
        if (taskAccess != null) taskAccess.inherit(context, sourceTasks);
    }

    private void revalidateTask(ToolContext context, java.util.Set<String> visited, long deadline) {
        if (!visited.add(context.taskId())) return;
        if (visited.size() > 100) throw BusinessException.of("MODEL_CONTEXT_BUDGET", "Knowledge dependency limit exceeded");
        if (taskAccess != null) for (String source : taskAccess.dependencies(context.taskId()))
            revalidateTask(new ToolContext(context.userId(), context.role(), source, context.traceId(), context.tenantId()), visited, deadline);
        if (taskAccess == null || taskAccess.sources(context.taskId()).isEmpty()) return;
        taskAccess.validateRead(context);
        String token = SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken
                ? requestToken(context) : taskAccess.token(context);
        try {
            for (var source : taskAccess.sources(context.taskId())) {
                JsonNode current = call("api/v1/citations/" + source.chunkId(), null, token, deadline);
                if (!current.path("is_current").asBoolean() || !source.versionId().equals(current.path("version_id").asText())
                        || !source.hash().equals(current.path("content_hash").asText())
                        || !current.path("content_revision").isIntegralNumber()
                        || source.revision() != current.path("content_revision").asLong())
                    throw BusinessException.of("RAG_EVIDENCE_CHANGED", "Knowledge evidence changed; submit a new request");
            }
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
        deadline=com.yu.agent.workflow.runtime.RunScope.capDeadline(deadline);
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
            HttpResponse<byte[]> response = com.yu.agent.workflow.runtime.RunScope.await(pending,deadline);
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

