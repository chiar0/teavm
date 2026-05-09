/*
 *  TeaVM classlib shim for java.util.concurrent.CompletableFuture.
 *  Provides minimal CompletableFuture for TeaVM's single-threaded runtime.
 *
 *  TeaVM is single-threaded and event-driven, so:
 *  - runAsync() executes synchronously (no thread pool)
 *  - get() never actually blocks (returns immediately)
 *  - The future chain is a simple callback pipeline
 *
 *  Only the operations used by DatabaseFunctionsWebLudii are implemented:
 *  - completedFuture(value)
 *  - runAsync(Runnable)
 *  - get() / get(timeout, unit)
 *  - thenApply(fn)
 *  - exceptionally(fn)
 */

package org.teavm.classlib.java.util.concurrent;

import java.util.function.Function;

public class TCompletableFuture<T> implements TCompletionStage<T> {
    private T value;
    private Throwable exception;
    private boolean complete;

    public TCompletableFuture() {
    }

    private TCompletableFuture(T value) {
        this.value = value;
        this.complete = true;
    }

    // ── Static factories ──────────────────────────────────────────────────

    public static <U> TCompletableFuture<U> completedFuture(U value) {
        return new TCompletableFuture<>(value);
    }

    public static TCompletableFuture<Void> runAsync(Runnable runnable) {
        final TCompletableFuture<Void> future = new TCompletableFuture<>();
        try {
            runnable.run();
            future.complete((Void) null);
        } catch (final Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    // ── Instance methods ──────────────────────────────────────────────────

    public boolean complete(T value) {
        if (this.complete) return false;
        this.value = value;
        this.complete = true;
        return true;
    }

    public boolean completeExceptionally(Throwable ex) {
        if (this.complete) return false;
        this.exception = ex;
        this.complete = true;
        return true;
    }

    public T get() throws TExecutionException, InterruptedException {
        if (exception != null) throw new TExecutionException(exception);
        return value;
    }

    public T get(long timeout, TTimeUnit unit)
            throws TExecutionException, InterruptedException {
        return get();
    }

    public T join() {
        if (exception != null) {
            if (exception instanceof RuntimeException) throw (RuntimeException) exception;
            throw new RuntimeException(exception);
        }
        return value;
    }

    public boolean isDone() {
        return complete;
    }

    public boolean isCompletedExceptionally() {
        return complete && exception != null;
    }

    // ── TCompletionStage implementation ───────────────────────────────────

    @Override
    public <U> TCompletionStage<U> thenApply(Function<? super T, ? extends U> fn) {
        final TCompletableFuture<U> result = new TCompletableFuture<>();
        if (exception != null) {
            result.completeExceptionally(exception);
        } else {
            try {
                result.complete(fn.apply(value));
            } catch (final Throwable e) {
                result.completeExceptionally(e);
            }
        }
        return result;
    }

    @Override
    public TCompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn) {
        if (exception != null) {
            final TCompletableFuture<T> result = new TCompletableFuture<>();
            try {
                result.complete(fn.apply(exception));
            } catch (final Throwable e) {
                result.completeExceptionally(e);
            }
            return result;
        }
        return this;
    }
}
