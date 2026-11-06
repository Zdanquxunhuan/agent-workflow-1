package com.yu.agent.workflow.runtime;

import com.yu.agent.workflow.api.BusinessException;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Thread-scoped execution guard for exactly one runtime run.
 *
 * <p>Installed explicitly by the authorized worker after the run is claimed, and removed on close. A pooled
 * worker thread may have served a previous request, so the scope is never inherited and never assumed to be
 * present. Credentials stay in memory only: a restarted process cannot silently resume a paid call.
 *
 * <p>The scope answers one question at explicit boundaries: may this run continue? Long-running steps (model
 * calls, tool execution, RAG HTTP, the final commit) call {@link #checkpoint()}, so a persisted cancellation,
 * expired identity, exhausted deadline or budget overrun interrupts the work at the next boundary instead of
 * after it finishes.
 */
/**
 * 单次运行的线程级守卫。
 *
 * <p>三种入口最终都会在 RuntimeService.execute 中安装一个 RunScope；
 * 之后模型循环、工具调用、会话保存、答案发布都通过 {@link #checkpoint()} 共享同一套
 * 取消/超时/JWT 过期/预算检查。
 *
 * <p>ThreadLocal 保证一个执行线程只属于一个运行；嵌套安装会被拒绝，
 * 否则内层运行会遮蔽外层的身份和截止时间。
 */
public final class RunScope implements AutoCloseable {
    /**
     * One scope per thread; nested installation is rejected because it would hide the outer run's identity.
     */
    private static final ThreadLocal<RunScope> CURRENT = new ThreadLocal<>();
    final RuntimeLedger ledger;
    public final String id, tenant, owner;
    /**
     * Absolute {@link System#nanoTime()} deadline, already capped by the owning JWT's expiry.
     */
    private final long deadline;
    /**
     * Wall-clock epoch millis after which the worker's JWT must no longer be trusted.
     */
    private final long identityExpires;
    /**
     * Throttles ledger reads to once per 200ms; in-memory checks still run on every call.
     */
    private long nextPoll;
    /**
     * Revalidates evidence and authorization right before an answer is produced; no-op by default.
     */
    private Runnable evidenceCheck = () -> {
    };

    public RunScope(RuntimeLedger ledger, String id, String tenant, String owner, long remainingMillis, long identityExpires) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested runtime scope");
        this.ledger = ledger;
        this.id = id;
        this.tenant = tenant;
        this.owner = owner;
        this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, remainingMillis));
        this.identityExpires = identityExpires;
        CURRENT.set(this);
    }

    public static RunScope current() {
        return CURRENT.get();
    }

    /**
     * Static no-op outside a runtime scope, so legacy entrypoints can share the same call sites unguarded.
     */
    public static void checkpoint() {
        if (current() != null) current().check();
    }

    public static long capDeadline(long local) {
        checkpoint();
        return current() == null ? local : Math.min(local, current().deadline);
    }

    /**
     * Cheap in-memory checks on every call, plus a throttled persisted check that sees cancellations and budget
     * changes made by other instances. Throws as soon as the run is no longer allowed to continue.
     */
    public void check() {
        if (Thread.currentThread().isInterrupted()) throw error("RUN_CANCELLED");
        if (System.currentTimeMillis() >= identityExpires) throw error("RUN_IDENTITY_EXPIRED");
        long now = System.nanoTime();
        if (now >= deadline) throw error("RUN_DEADLINE");
        if (now >= nextPoll) {
            ledger.check(id);
            nextPoll = now + TimeUnit.MILLISECONDS.toNanos(200);
        }
    }

    public void event(String type, Map<String, Object> data) {
        check();
        ledger.event(id, type, data);
    }

    public static void progress(String type, Map<String, Object> data) {
        if (current() != null) current().event(type, data);
    }

    public static void evidenceCheck(Runnable check) {
        if (current() != null) current().evidenceCheck = check;
    }

    /**
     * Re-runs the registered evidence check before publishing an answer, so stale permissions cannot leak it.
     */
    public void reauthorize() {
        check();
        evidenceCheck.run();
        check();
    }

    public static void bindTask(String task) {
        if (current() != null) {
            current().check();
            current().ledger.bindTask(current().id, task);
        }
    }

    /**
     * Counts a tool call against the run limit; {@code ToolRegistry} also uses {@link #current()} to force read-only.
     */
    public static void tool(String name) {
        if (current() != null) {
            current().check();
            current().ledger.tool(current().id, name);
        }
    }

    /**
     * Commits business success and the SUCCEEDED run state in one transaction after reservations are settled.
     */
    public static <T> T publish(java.util.function.Supplier<T> action) {
        if (current() == null) return action.get();
        current().check();
        return current().ledger.publish(current().id, action);
    }

    /**
     * Bounds any downstream wait by the run deadline and keeps polling cancellation while waiting.
     */
    public static <T> T await(CompletableFuture<T> future, long localDeadline) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            long end = capDeadline(localDeadline);
            while (true) {
                checkpoint();
                long remaining = end - System.nanoTime();
                if (remaining <= 0) {
                    checkpoint();
                    throw new TimeoutException();
                }
                try {
                    return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {/* Re-check persisted cancellation and shared budget. */}
            }
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    public static BusinessException error(String code) {
        return BusinessException.of(code, "Agent execution stopped (" + code + ")");
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
