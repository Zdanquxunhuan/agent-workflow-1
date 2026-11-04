package com.yu.agent.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.agent.workflow.security.AuthenticatedUser;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate bounded observer pool: a slow SSE reader cannot occupy an agent execution worker. */
@Service
@ConditionalOnProperty(name="agent.runtime.enabled",havingValue="true")
public class RuntimeEvents {
    private final RuntimeLedger ledger;private final ObjectMapper json;
    private final ThreadPoolExecutor observers=new ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,new SynchronousQueue<>(),
            runnable->{Thread t=new Thread(runnable,"agent-progress-observer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public RuntimeEvents(RuntimeLedger ledger,ObjectMapper json){this.ledger=ledger;this.json=json;}
    public SseEmitter subscribe(String id,AuthenticatedUser user,long after,long tokenExpiry) {
        ledger.owned(id,user);if(after<0)throw RunScope.error("INVALID_ARGUMENT");
        SseEmitter emitter=new SseEmitter(65000L);AtomicBoolean closed=new AtomicBoolean();
        emitter.onCompletion(()->closed.set(true));emitter.onTimeout(()->closed.set(true));emitter.onError(ex->closed.set(true));
        try{observers.execute(()->{
            long cursor=after,lastHeartbeat=0,until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            try {
                while(!closed.get() && System.currentTimeMillis()<tokenExpiry && System.nanoTime()<until) {
                    var row=ledger.owned(id,user);
                    for(var event:ledger.events(id,cursor)) {
                        emitter.send(SseEmitter.event().id(event.get("id").toString()).name(event.get("event_type").toString())
                                .data(Map.of("at",event.get("created_at"),"data",json.readTree(event.get("payload_json").toString()))));
                        cursor=RuntimeLedger.number(event,"id");
                    }
                    if(!Set.of("RUNNING","QUEUED").contains(row.get("status"))) {
                        // Explicit snapshot also works when Last-Event-ID is beyond the last stored event.
                        emitter.send(SseEmitter.event().name("snapshot").data(Map.of("status",row.get("status"))));break;
                    }
                    if(System.nanoTime()-lastHeartbeat>TimeUnit.SECONDS.toNanos(5)) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));lastHeartbeat=System.nanoTime();
                    }
                    Thread.sleep(500);
                }
                emitter.complete();
            } catch(InterruptedException ex){Thread.currentThread().interrupt();emitter.complete();}
            catch(Exception ex){emitter.completeWithError(RunScope.error("RUN_STREAM_CLOSED"));}
            finally{closed.set(true);}
        });}catch(RejectedExecutionException ex){throw RunScope.error("RUN_BUSY");}
        return emitter;
    }
    @PreDestroy public void close(){observers.shutdownNow();}
}
