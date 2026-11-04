package com.yu.agent.workflow.mcp;

import com.yu.agent.workflow.api.BusinessException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** Deadline covers headers AND body; cancellation and a bounded subscriber prevent slow/large responses. */
final class McpHttp {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    HttpResponse<byte[]> post(URI uri, Map<String,String> headers, String body, long deadline) {
        deadline=com.yu.agent.workflow.runtime.RunScope.capDeadline(deadline);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw failure("MCP_TIMEOUT");
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        LimitedBody subscriber = new LimitedBody();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(builder.build(), ignored -> subscriber);
        try { return com.yu.agent.workflow.runtime.RunScope.await(pending,deadline); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw failure("MCP_CANCELLED"); }
        catch (TimeoutException ex) { throw failure("MCP_TIMEOUT"); }
        catch (ExecutionException ex) {
            if (subscriber.tooLarge) throw failure("MCP_RESPONSE_TOO_LARGE");
            if (ex.getCause() instanceof HttpTimeoutException) throw failure("MCP_TIMEOUT");
            if (ex.getCause() instanceof BusinessException business) throw business;
            throw failure("MCP_UNAVAILABLE");
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }
    static BusinessException failure(String code) { return BusinessException.of(code, "MCP shipment request could not be completed (" + code + ")"); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private volatile boolean tooLarge;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            result.whenComplete((ignored,error) -> { if (result.isCancelled()) value.cancel(); });
            value.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 65536-bytes.size()) {
                    tooLarge=true;
                    subscription.cancel(); result.completeExceptionally(failure("MCP_RESPONSE_TOO_LARGE")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
