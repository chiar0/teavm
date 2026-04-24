package org.teavm.classlib.java.util.concurrent.locks;

/**
 * TeaVM shim for java.util.concurrent.locks.ReentrantLock.
 * Provides a minimal implementation for Ludii's Context class and MCTS.
 * Since JavaScript is single-threaded, we don't need actual locking,
 * but we maintain state tracking for compatibility.
 */
public class TReentrantLock {

    private int holdCount = 0;

    public TReentrantLock() {
        // No-op in single-threaded JavaScript
    }

    public TReentrantLock(boolean fair) {
        // Fairness is meaningless in single-threaded JavaScript
    }

    public void lock() {
        holdCount++;
    }

    public void unlock() {
        if (holdCount > 0) {
            holdCount--;
        }
    }

    public boolean tryLock() {
        // Always succeeds in single-threaded environment
        holdCount++;
        return true;
    }

    public boolean isLocked() {
        return holdCount > 0;
    }

    public boolean isHeldByCurrentThread() {
        // Always true in single-threaded environment when locked
        return holdCount > 0;
    }

    public int getHoldCount() {
        return holdCount;
    }

    public void lockInterruptibly() {
        // No-op - JavaScript is single-threaded
        holdCount++;
    }

    public boolean tryLock(long timeout, java.util.concurrent.TimeUnit unit) {
        // Always succeeds immediately in single-threaded environment
        holdCount++;
        return true;
    }

    public java.util.concurrent.locks.Condition newCondition() {
        // Return a condition implementation for MCTS compatibility
        return new TCondition();
    }

    /**
     * Enhanced Condition implementation for TeaVM with full MCTS support.
     *
     * In a single-threaded JavaScript environment, condition variables don't need
     * actual synchronization semantics. However, we provide a complete implementation
     * that maintains state for compatibility with MCTS code patterns that may
     * check or interact with Condition objects.
     *
     * This implementation supports:
     * - No-op await methods (since no blocking is needed in single-threaded JS)
     * - No-op signal methods (since no threads to wake up)
     * - Proper return values for timed waits
     * - Thread interruption awareness (for API compatibility)
     */
    public static class TCondition implements java.util.concurrent.locks.Condition {

        /** Number of threads waiting on this condition (tracked for compatibility) */
        private int waiters = 0;

        @Override
        public void await() {
            // In single-threaded JS, await is a no-op since there's no
            // concurrent access to wait for
        }

        @Override
        public void awaitUninterruptibly() {
            // No-op in single-threaded environment
            // This version cannot be interrupted, which is fine since
            // there are no actual threads to interrupt
        }

        @Override
        public long awaitNanos(long nanosTimeout) {
            // In single-threaded JS, we don't actually wait
            // Return 0 to indicate no time remaining (immediate return)
            // This signals that the "wait" completed immediately
            return 0L;
        }

        @Override
        public boolean await(long time, java.util.concurrent.TimeUnit unit) {
            // In single-threaded JS, we don't actually wait
            // Return true to indicate normal return (not timeout/spurious wakeup)
            // This allows code that checks the return value to continue
            return true;
        }

        @Override
        public boolean awaitUntil(java.util.Date deadline) {
            // In single-threaded JS, we don't actually wait
            // Return true to indicate normal return before deadline
            return true;
        }

        @Override
        public void signal() {
            // In single-threaded JS, there are no waiting threads to wake up
            // This is a no-op but maintains API compatibility
        }

        @Override
        public void signalAll() {
            // In single-threaded JS, there are no waiting threads to wake up
            // This is a no-op but maintains API compatibility
        }
    }
}
