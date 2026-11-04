package com.yu.agent.workflow.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
public class MemoryWorker implements org.springframework.beans.factory.DisposableBean {
    private static final Logger log=LoggerFactory.getLogger(MemoryWorker.class);
    private final MemoryStore store;private final MemoryService memory;private final MemoryIndexClient index;
    private final java.util.concurrent.ExecutorService executor=new java.util.concurrent.ThreadPoolExecutor(1,1,0,
            java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),r -> {
                Thread t=new Thread(r,"memory-worker");t.setDaemon(true);return t;
            });
    private final java.util.concurrent.atomic.AtomicBoolean draining=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean expiring=new java.util.concurrent.atomic.AtomicBoolean();
    public MemoryWorker(MemoryStore store,MemoryService memory,MemoryIndexClient index){this.store=store;this.memory=memory;this.index=index;}
    @Scheduled(fixedDelayString="${agent.memory.worker-delay-ms:5000}")
    public void scheduleDrain(){submit(draining,this::drain);}
    @Scheduled(fixedDelayString="${agent.memory.expiry-delay-ms:60000}")
    public void scheduleExpiry(){submit(expiring,this::expire);}
    private void submit(java.util.concurrent.atomic.AtomicBoolean busy,Runnable work){
        if(!busy.compareAndSet(false,true))return;
        try{executor.execute(() -> {try{work.run();}catch(RuntimeException ex){log.warn("Memory worker cycle failed type={}",ex.getClass().getSimpleName());}finally{busy.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException ex){busy.set(false);}
    }
    @Override public void destroy(){executor.shutdownNow();}
    public void drain(){
        store.reapAbandoned();
        for(int i=0;i<5;i++){
            var job=store.claim();if(job==null)return;
            try{
                if("EXTRACT".equals(job.kind()))memory.processExtraction(job);
                else {var m=store.find(job.target());if(m!=null)index.synchronize(m);}
                store.finish(job,true,null);
            }catch(RuntimeException ex){
                String code=ex instanceof com.yu.agent.workflow.api.BusinessException b?b.code():"MEMORY_JOB_FAILED";
                store.finish(job,false,code);log.warn("Memory job failed id={} kind={} attempt={} code={}",job.id(),job.kind(),job.attempts(),code);
            }
        }
    }
    public void expire(){
        for(var snapshot:store.expired())store.transaction(() -> {
            store.lockOwner(snapshot.tenantId(),snapshot.ownerId());var m=store.find(snapshot.id());
            if(m!=null && m.expiresAt()<=MemoryStore.now() && java.util.Set.of("ACTIVE","CANDIDATE").contains(m.state()))
                store.update(m,new MemoryRecord(m.id(),m.tenantId(),m.ownerId(),m.kind(),m.slot(),"",m.sourceTaskId(),m.revision()+1,
                        "EXPIRED",m.expiresAt(),m.createdAt(),MemoryStore.now()),"EXPIRED");
            return null;
        });
    }
}
