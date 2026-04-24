package org.teavm.classlib.java.util.concurrent;

/**
 * TeaVM shim for java.util.concurrent.Executors.
 * <p>
 * Factory and utility methods for Executor, ExecutorService,
 * and ThreadFactory classes.
 */
public class TExecutors {

    /**
     * Creates a thread pool that reuses a fixed number of threads.
     * In the browser, this returns a synchronous executor that runs
     * tasks immediately on the calling thread.
     *
     * @param nThreads the number of threads in the pool
     * @return the newly created thread pool
     */
    public static TExecutorService newFixedThreadPool(int nThreads) {
        return new TSynchronousExecutorService();
    }

    /**
     * Creates a thread pool that reuses a fixed number of threads.
     * In the browser, this returns a synchronous executor that runs
     * tasks immediately on the calling thread.
     *
     * @param nThreads the number of threads in the pool
     * @param threadFactory the factory to use when creating new threads
     * @return the newly created thread pool
     */
    public static TExecutorService newFixedThreadPool(int nThreads, java.util.concurrent.ThreadFactory threadFactory) {
        return new TSynchronousExecutorService();
    }

    /**
     * Synchronous executor service implementation for single-threaded JavaScript.
     * <p>
     * Tasks are executed immediately when submitted, on the calling thread.
     * This provides compatibility with AI code that expects parallel execution
     * but doesn't require true parallelism for correctness.
     */
    private static class TSynchronousExecutorService implements TExecutorService {
        private boolean shutdown = false;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return new java.util.ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true; // Already terminated since tasks run synchronously
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            if (shutdown) {
                throw new IllegalStateException("ExecutorService has been shutdown");
            }
            try {
                T result = task.call();
                return new TCompletedFuture<>(result);
            } catch (Exception e) {
                return new TCompletedFuture<>(e);
            }
        }

        @Override
        public java.util.concurrent.Future<?> submit(java.lang.Runnable task) {
            if (shutdown) {
                throw new IllegalStateException("ExecutorService has been shutdown");
            }
            task.run();
            return new TCompletedFuture<>(null);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.lang.Runnable task, T result) {
            if (shutdown) {
                throw new IllegalStateException("ExecutorService has been shutdown");
            }
            task.run();
            return new TCompletedFuture<>(result);
        }

        @Override
        public void execute(java.lang.Runnable command) {
            if (shutdown) {
                throw new IllegalStateException("ExecutorService has been shutdown");
            }
            command.run();
        }
    }

    /**
     * A Future that is already completed.
     * Used for synchronous execution where the task completes
     * before the Future is returned.
     */
    private static class TCompletedFuture<T> implements java.util.concurrent.Future<T> {
        private final T result;
        private final Exception exception;

        TCompletedFuture(T result) {
            this.result = result;
            this.exception = null;
        }

        TCompletedFuture(Exception exception) {
            this.result = null;
            this.exception = exception;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false; // Already completed
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() {
            if (exception != null) {
                throw new RuntimeException(exception);
            }
            return result;
        }

        @Override
        public T get(long timeout, java.util.concurrent.TimeUnit unit) {
            return get();
        }
    }
}
