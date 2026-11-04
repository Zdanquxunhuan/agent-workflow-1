package com.yu.agent.workflow.runtime;

import org.springframework.http.*;
import org.springframework.http.client.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded synchronous facade for Spring AI; the actual exchange can be cancelled while waiting for the body. */
final class RuntimeModelHttp implements ClientHttpRequestFactory {
    private final URI endpoint;
    private final int seconds;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    RuntimeModelHttp(String base,int seconds){endpoint=URI.create(base+"/chat/completions");this.seconds=seconds;}
    @Override public ClientHttpRequest createRequest(URI uri,HttpMethod method) {
        if(!endpoint.equals(uri) || !HttpMethod.POST.equals(method))throw RunScope.error("MODEL_ENDPOINT_BLOCKED");
        return new ClientHttpRequest() {
            private final HttpHeaders headers=new HttpHeaders();
            private final Map<String,Object> attributes=new HashMap<>();
            private final ByteArrayOutputStream body=new ByteArrayOutputStream();
            public HttpMethod getMethod(){return method;}
            public URI getURI(){return uri;}
            public Map<String,Object> getAttributes(){return attributes;}
            public HttpHeaders getHeaders(){return headers;}
            public OutputStream getBody(){return body;}
            public ClientHttpResponse execute() throws IOException {
                if(RunScope.current()==null)throw RunScope.error("RUN_CONTEXT_REQUIRED");
                RunScope.checkpoint();
                if(body.size()>524288)throw RunScope.error("MODEL_CONTEXT_BUDGET");
                long deadline=RunScope.capDeadline(System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds));
                var request=HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.max(1,deadline-System.nanoTime())))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                headers.forEach((name,values)->{
                    if(!Set.of("content-length","host","connection","expect","upgrade").contains(name.toLowerCase(Locale.ROOT)))
                        values.forEach(value->request.header(name,value));
                });
                LimitedBody subscriber=new LimitedBody();
                var future=http.sendAsync(request.build(),ignored->subscriber);
                final HttpResponse<byte[]> response;
                try {response=RunScope.await(future,deadline);}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw RunScope.error("RUN_CANCELLED");}
                catch(TimeoutException ex){throw RunScope.error("MODEL_TIMEOUT");}
                catch(ExecutionException ex){
                    if(subscriber.tooLarge)throw RunScope.error("MODEL_INVALID_RESPONSE");
                    if(ex.getCause() instanceof java.net.http.HttpTimeoutException)throw RunScope.error("MODEL_TIMEOUT");
                    throw RunScope.error("MODEL_TRANSIENT");
                }
                int status=response.statusCode();
                if(status==429)throw RunScope.error("MODEL_RATE_LIMITED");
                if(status==408 || status>=500)throw RunScope.error("MODEL_TRANSIENT");
                if(status<200 || status>=300)throw RunScope.error("MODEL_REJECTED");
                if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim().equalsIgnoreCase("application/json"))
                    throw RunScope.error("MODEL_INVALID_RESPONSE");
                HttpHeaders responseHeaders=new HttpHeaders();response.headers().map().forEach(responseHeaders::put);
                return new ClientHttpResponse() {
                    private final InputStream input=new ByteArrayInputStream(response.body());
                    public HttpStatusCode getStatusCode(){return HttpStatusCode.valueOf(status);}
                    public String getStatusText(){return "";}
                    public HttpHeaders getHeaders(){return responseHeaders;}
                    public InputStream getBody(){return input;}
                    public void close(){try{input.close();}catch(IOException ignored){}}
                };
            }
        };
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final ByteArrayOutputStream data=new ByteArrayOutputStream();
        Flow.Subscription subscription;volatile boolean tooLarge;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var b:buffers){
                if(b.remaining()>1_048_576-data.size()){tooLarge=true;subscription.cancel();result.completeExceptionally(new IOException("Model response too large"));return;}
                byte[] bytes=new byte[b.remaining()];b.get(bytes);data.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable ex){result.completeExceptionally(ex);}
        public void onComplete(){result.complete(data.toByteArray());}
    }
}
