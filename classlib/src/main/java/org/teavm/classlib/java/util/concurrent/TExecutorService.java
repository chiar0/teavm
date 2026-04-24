package org.teavm.classlib.java.util.concurrent;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * TeaVM shim for java.util.concurrent.ExecutorService.
 * <p>
 * In JavaScript, there's no true threading, so ExecutorService operations
 * are either no-ops or run synchronously on the main thread.
 * <p>
 * This implementation provides minimal compatibility for AI code that uses
 * ExecutorService for parallel playouts - in the browser, these run
 * synchronously instead.
 */
public interface TExecutorService extends TExecutor {

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * In the browser, this is a no-op since we don't have actual threads.
     */
    void shutdown();

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks.
     * In the browser, this is a no-op.
     *
     * @return list of tasks that never commenced execution
     */
    List<Runnable> shutdownNow();

    /**
     * Returns true if this executor has been shut down.
     *
     * @return true if shutdown, false otherwise
     */
    boolean isShutdown();

    /**
     * Returns true if all tasks have completed following shut down.
     *
     * @return true if terminated
     */
    boolean isTerminated();

    /**
     * Blocks until all tasks have completed execution after a shutdown request.
     * In the browser, this returns immediately since tasks run synchronously.
     *
     * @return true if terminated
     */
    boolean awaitTermination(long timeout, TimeUnit unit);

    /**
     * Submits a value-returning task for execution and returns a Future.
     * In the browser, the task runs synchronously.
     *
     * @param task the task to submit
     * @return a Future representing pending completion of the task
     */
    <T> Future<T> submit(Callable<T> task);

    /**
     * Submits a Runnable task for execution.
     * In the browser, the task runs synchronously.
     *
     * @param task the task to submit
     * @return a Future representing pending completion
     */
    Future<?> submit(Runnable task);

    /**
     * Submits a Runnable task for execution and returns a Future.
     * In the browser, the task runs synchronously.
     *
     * @param task the task to submit
     * @param result the result to return
     * @return a Future representing pending completion
     */
    <T> Future<T> submit(Runnable task, T result);
}
