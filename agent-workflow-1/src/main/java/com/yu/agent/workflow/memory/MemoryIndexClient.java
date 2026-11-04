package com.yu.agent.workflow.memory;

import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.yu.agent.workflow.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;

@Component
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
public class MemoryIndexClient {
    private final URI base;
    private final ObjectMapper json;
    private final NimbusJwtEncoder encoder;
    private final double maxDistance;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public MemoryIndexClient(ObjectMapper json, Environment environment,
            @Value("${agent.memory.index-url:}") String url,@Value("${agent.memory.index-secret:}") String secret,
            @Value("${agent.workflow.persistence:memory}") String persistence,
            @Value("${agent.memory.max-distance:0.45}") double maxDistance,
            @Value("${agent.auth.local-secret:}") String userSecret) {
        this.maxDistance=maxDistance;
        this.json=json; base=URI.create(url.endsWith("/")?url:url+"/");
        boolean local = "http".equals(base.getScheme()) && Set.of("localhost","127.0.0.1","[::1]").contains(Objects.toString(base.getHost(),""));
        if(!"jdbc".equals(persistence) || secret.length()<32 || secret.equals(userSecret) || !Double.isFinite(maxDistance) || maxDistance<0 || maxDistance>2 || base.getHost()==null || base.getUserInfo()!=null
                || base.getQuery()!=null || base.getFragment()!=null || !("https".equals(base.getScheme()) || local)
                || (Arrays.asList(environment.getActiveProfiles()).contains("prod") && !"https".equals(base.getScheme())))
            throw new IllegalStateException("Memory requires JDBC, a separate signing secret and HTTPS index URL (loopback HTTP only outside prod)");
        encoder=new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
    }
    public record Hit(String id,long revision,double distance) {}
    public List<Hit> search(String tenant,String owner,String query) {
        var root=call(tenant,owner,"query",Map.of("query",query,"limit",8));
        if(!root.path("hits").isArray() || root.path("hits").size()>10) throw unavailable();
        List<Hit> hits=new ArrayList<>();
        for(var n:root.path("hits")) {
            String id=n.path("id").asText();
            try {UUID.fromString(id);}catch(IllegalArgumentException ex){throw unavailable();}
            if(!n.path("revision").isIntegralNumber() || n.path("revision").asLong()<1 || !n.path("distance").isNumber()) throw unavailable();
            double distance=n.path("distance").asDouble(); if(!Double.isFinite(distance)) throw unavailable();
            if(distance<=maxDistance) hits.add(new Hit(id,n.path("revision").asLong(),distance));
        }
        return List.copyOf(hits);
    }
    public void synchronize(MemoryRecord m) {
        // Keep a stable payload for each authority revision, even if it expires in flight.
        boolean deleted=!"ACTIVE".equals(m.state()) || !"TASK_SUMMARY".equals(m.kind());
        var root=call(m.tenantId(),m.ownerId(),"upsert",Map.of("id",m.id(),"revision",m.revision(),"expiresAt",m.expiresAt(),
                "deleted",deleted,"text",deleted?"":m.content()));
        if(!root.path("revision").isIntegralNumber() || root.path("revision").asLong()<m.revision()) throw unavailable();
    }
    private JsonNode call(String tenant,String owner,String operation,Object payload) {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            byte[] body=json.writeValueAsBytes(payload); Instant now=Instant.now();
            var claims=JwtClaimsSet.builder().issuer("workflow-memory").subject(owner).audience(List.of("memory-index"))
                    .issuedAt(now).expiresAt(now.plusSeconds(30)).claim("tenant_id",tenant).claim("operation",operation)
                    .claim("body_hash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))).build();
            String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
            var request=HttpRequest.newBuilder(base.resolve("internal/memory-index/"+operation)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type","application/json").header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending=http.sendAsync(request,ignored -> new LimitedBody());
            var response=pending.get(10,TimeUnit.SECONDS);
            if(response.statusCode()!=200) throw BusinessException.of(switch(response.statusCode()) {
                case 401,403 -> "MEMORY_INDEX_AUTH";
                case 409 -> "MEMORY_INDEX_CONFLICT";
                case 429 -> "MEMORY_INDEX_RATE_LIMIT";
                case 422 -> "MEMORY_INDEX_INPUT";
                default -> "MEMORY_INDEX_UNAVAILABLE";
            },"Memory index request failed");
            return json.readTree(response.body());
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch(BusinessException ex) { throw ex; }
        catch(Exception ex) { throw unavailable(); }
        finally { if(pending!=null && !pending.isDone()) pending.cancel(true); }
    }
    private BusinessException unavailable() { return BusinessException.of("MEMORY_INDEX_UNAVAILABLE","Memory semantic index unavailable"); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var b:buffers){
                if(bytes.size()+b.remaining()>65536){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Index body too large"));return;}
                byte[] value=new byte[b.remaining()];b.get(value);bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        public void onError(Throwable ex){result.completeExceptionally(ex);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
