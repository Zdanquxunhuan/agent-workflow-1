package com.yu.agent.workflow.runtime;

import com.yu.agent.workflow.api.BusinessException;
import java.util.Map;
import java.util.concurrent.*;

/** Explicitly installed on an authorized worker; never inheritable and never stores credentials in the database. */
public final class RunScope implements AutoCloseable {
    private static final ThreadLocal<RunScope> CURRENT=new ThreadLocal<>();
    final RuntimeLedger ledger;
    public final String id,tenant,owner;
    private final long deadline,identityExpires;
    private long nextPoll;
    private Runnable evidenceCheck=()->{};
    public RunScope(RuntimeLedger ledger,String id,String tenant,String owner,long remainingMillis,long identityExpires) {
        if(CURRENT.get()!=null)throw new IllegalStateException("Nested runtime scope");
        this.ledger=ledger;this.id=id;this.tenant=tenant;this.owner=owner;
        this.deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,remainingMillis));this.identityExpires=identityExpires;
        CURRENT.set(this);
    }
    public static RunScope current(){return CURRENT.get();}
    public static void checkpoint(){if(current()!=null)current().check();}
    public static long capDeadline(long local){checkpoint();return current()==null?local:Math.min(local,current().deadline);}
    public void check() {
        if(Thread.currentThread().isInterrupted())throw error("RUN_CANCELLED");
        if(System.currentTimeMillis()>=identityExpires)throw error("RUN_IDENTITY_EXPIRED");
        long now=System.nanoTime();
        if(now>=deadline)throw error("RUN_DEADLINE");
        if(now>=nextPoll){ledger.check(id);nextPoll=now+TimeUnit.MILLISECONDS.toNanos(200);}
    }
    public void event(String type,Map<String,Object> data){check();ledger.event(id,type,data);}
    public static void progress(String type,Map<String,Object> data){if(current()!=null)current().event(type,data);}
    public static void evidenceCheck(Runnable check){if(current()!=null)current().evidenceCheck=check;}
    public void reauthorize(){check();evidenceCheck.run();check();}
    public static void bindTask(String task){if(current()!=null){current().check();current().ledger.bindTask(current().id,task);}}
    public static void tool(String name){if(current()!=null){current().check();current().ledger.tool(current().id,name);}}
    public static <T> T publish(java.util.function.Supplier<T> action) {
        if(current()==null)return action.get();current().check();return current().ledger.publish(current().id,action);
    }
    public static <T> T await(CompletableFuture<T> future,long localDeadline) throws InterruptedException,ExecutionException,TimeoutException {
        try {
            long end=capDeadline(localDeadline);
            while(true) {
                checkpoint();long remaining=end-System.nanoTime();
                if(remaining<=0){checkpoint();throw new TimeoutException();}
                try {return future.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(200)),TimeUnit.NANOSECONDS);}
                catch(TimeoutException ignored){/* Re-check persisted cancellation and shared budget. */}
            }
        } finally {if(!future.isDone())future.cancel(true);}
    }
    public static BusinessException error(String code){return BusinessException.of(code,"Agent execution stopped ("+code+")");}
    @Override public void close(){CURRENT.remove();}
}
