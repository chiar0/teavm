/*
 *  TeaVM classlib shim for java.net.http.WebSocket.
 *  Wraps org.teavm.jso.websocket.WebSocket (existing JSO browser wrapper).
 *
 *  Adapts the JSO WebSocket's event-driven API (onOpen/onMessage/onClose/onError)
 *  to java.net.http.WebSocket's Listener interface.
 *
 *  buildAsync() creates the JSO WebSocket and bridges events immediately.
 *  Since TeaVM is single-threaded, the returned CompletableFuture is already
 *  completed — events fire from the browser event loop later.
 */

package org.teavm.classlib.java.net.http;

import java.net.URI;

import org.teavm.classlib.java.util.concurrent.TCompletableFuture;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.MessageEvent;
import org.teavm.jso.websocket.CloseEvent;

public class TWebSocket {
    public static final int NORMAL_CLOSURE = 1000;

    private final org.teavm.jso.websocket.WebSocket jsoWs;
    private final Listener listener;

    private TWebSocket(org.teavm.jso.websocket.WebSocket jsoWs, Listener listener) {
        this.jsoWs = jsoWs;
        this.listener = listener;
    }

    // ── Factory (called from Builder.buildAsync) ──────────────────────────

    private static TCompletableFuture<TWebSocket> createAndConnect(URI uri, Listener listener) {
        final org.teavm.jso.websocket.WebSocket jsoWs =
                new org.teavm.jso.websocket.WebSocket(uri.toString());

        final TWebSocket[] ref = new TWebSocket[1];

        jsoWs.onOpen((EventListener<Event>) evt -> {
            if (ref[0] != null) {
                listener.onOpen(ref[0]);
            }
        });

        jsoWs.onMessage((EventListener<MessageEvent>) evt -> {
            if (ref[0] != null) {
                final String data = evt.getDataAsString();
                listener.onText(ref[0], data, true);
            }
        });

        jsoWs.onClose((EventListener<CloseEvent>) evt -> {
            if (ref[0] != null) {
                listener.onClose(ref[0], evt.getCode(), evt.getReason());
            }
        });

        jsoWs.onError((EventListener<Event>) evt -> {
            if (ref[0] != null) {
                listener.onError(ref[0], new RuntimeException("WebSocket error"));
            }
        });

        final TWebSocket ws = new TWebSocket(jsoWs, listener);
        ref[0] = ws;

        return TCompletableFuture.completedFuture(ws);
    }

    // ── Instance methods ──────────────────────────────────────────────────

    public TCompletableFuture<TWebSocket> sendText(CharSequence data, boolean last) {
        jsoWs.send(data.toString());
        return TCompletableFuture.completedFuture(this);
    }

    public TCompletableFuture<TWebSocket> sendClose(int code, String reason) {
        jsoWs.close(code, reason);
        return TCompletableFuture.completedFuture(this);
    }

    public void request(long n) {
        // No-op — browser WebSocket handles backpressure automatically
    }

    // ── Builder (nested in WebSocket, matching JDK structure) ─────────────

    public interface Builder {
        TCompletableFuture<TWebSocket> buildAsync(URI uri, Listener listener);
    }

    // ── Listener interface ────────────────────────────────────────────────

    public interface Listener {
        void onOpen(TWebSocket ws);
        org.teavm.classlib.java.util.concurrent.TCompletionStage<?> onText(
                TWebSocket ws, CharSequence data, boolean last);
        org.teavm.classlib.java.util.concurrent.TCompletionStage<?> onClose(
                TWebSocket ws, int code, String reason);
        void onError(TWebSocket ws, Throwable error);
    }

    // ── Builder implementation (package-private) ──────────────────────────

    static class BuilderImpl implements Builder {
        @Override
        public TCompletableFuture<TWebSocket> buildAsync(URI uri, Listener listener) {
            return createAndConnect(uri, listener);
        }
    }
}
