package org.teavm.classlib.java.util.concurrent;

/**
 * TeaVM shim for java.util.concurrent.ThreadFactory.
 * <p>
 * In the browser there are no threads, so {@link #newThread} returns null.
 * Ludii's DaemonThreadFactory implements this interface; TeaVM needs the
 * type in the classlib so the JS bundle declares the corresponding variable.
 */
public interface TThreadFactory {
    Thread newThread(Runnable r);
}
