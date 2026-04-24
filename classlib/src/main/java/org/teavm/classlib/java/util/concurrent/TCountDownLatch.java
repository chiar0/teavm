package org.teavm.classlib.java.util.concurrent;

/**
 * TeaVM shim for java.util.concurrent.CountDownLatch.
 * <p>
 * In JavaScript, there's no true threading, so a CountDownLatch's
 * countdown and await operations are effectively no-ops.
 * <p>
 * This implementation provides minimal compatibility for AI code that
 * uses CountDownLatch for synchronizing parallel playouts.
 */
public class TCountDownLatch {

    private int count;

    /**
     * Constructs a CountDownLatch initialized with the given count.
     *
     * @param count the number of times {@link #countDown} must be invoked
     *              before threads can pass through {@link #await}
     */
    public TCountDownLatch(int count) {
        this.count = count;
    }

    /**
     * Causes the current thread to wait until the latch has counted down to zero.
     * In the browser, this returns immediately since there's no concurrent
     * execution to wait for.
     */
    public void await() {
        // No-op in single-threaded JavaScript
        // The "waiting" concept doesn't apply when there's no parallelism
    }

    /**
     * Causes the current thread to wait until the latch has counted down to zero,
     * or the specified waiting time elapses.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return true if the count reached zero, false if timeout elapsed first
     */
    public boolean await(long timeout, java.util.concurrent.TimeUnit unit) {
        // In single-threaded JavaScript, we return true immediately
        // as if the latch had been counted down
        return true;
    }

    /**
     * Decrements the count of the latch, releasing all waiting threads if
     * the count reaches zero.
     */
    public void countDown() {
        if (count > 0) {
            count--;
        }
    }

    /**
     * Returns the current count.
     *
     * @return the current count
     */
    public long getCount() {
        return count;
    }
}
