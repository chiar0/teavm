package org.teavm.classlib.java.awt;

/**
 * TeaVM shim for java.awt.EventQueue.
 * TeaVM is single-threaded, so invokeLater() runs synchronously.
 */
public class TEventQueue {
    public static void invokeLater(Runnable runnable) {
        runnable.run();
    }

    public static void invokeAndWait(Runnable runnable) throws Exception {
        runnable.run();
    }
}
