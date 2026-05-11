/*
 *  TeaVM classlib shim for java.net.http.WebSocket.
 *
 *  Uses @JSBody for native WebSocket access — works in both
 *  browsers (native WebSocket) and Node.js (ws package fallback).
 *
 *  The host application does NOT need to inject globalThis.WebSocket;
 *  this shim detects the environment and falls back to require('ws').
 */

package org.teavm.classlib.java.net.http;

import java.net.URI;

import org.teavm.classlib.java.util.concurrent.TCompletableFuture;
import org.teavm.classlib.java.util.concurrent.TCompletionStage;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public class TWebSocket {
    public static final int NORMAL_CLOSURE = 1000;

    private final JSObject nativeWs;
    private final Listener listener;

    private TWebSocket(JSObject nativeWs, Listener listener) {
        this.nativeWs = nativeWs;
        this.listener = listener;
    }

    // ── Event callback (@JSFunctor — single method) ──────────────────────

    @JSFunctor
    private interface WsCallback extends JSObject {
        void event(String type, String data, int code, String reason);
    }

    // ── Factory ──────────────────────────────────────────────────────────

    private static TCompletableFuture<TWebSocket> createAndConnect(URI uri, Listener listener) {
        final TCompletableFuture<TWebSocket> future = new TCompletableFuture<>();
        final TWebSocket[] ref = new TWebSocket[1];

        final WsCallback cb = (type, data, code, reason) -> {
            final TWebSocket ws = ref[0];
            if (ws == null) return;
            switch (type) {
                case "open":
                    listener.onOpen(ws);
                    future.complete(ws);
                    break;
                case "message":
                    listener.onText(ws, data, true);
                    break;
                case "close":
                    listener.onClose(ws, code, reason);
                    break;
                case "error":
                    listener.onError(ws, new RuntimeException("WebSocket error"));
                    future.completeExceptionally(new RuntimeException("WebSocket connection failed"));
                    break;
                default:
                    break;
            }
        };

        final JSObject nws = createNative(uri.toString(), cb);
        if (nws == null) {
            future.completeExceptionally(new RuntimeException("No WebSocket implementation available"));
            return future;
        }
        final TWebSocket tws = new TWebSocket(nws, listener);
        ref[0] = tws;

        return future;
    }

    /**
     * Creates a native WebSocket.
     * Browser: uses global WebSocket constructor.
     * Node.js 22+: uses built-in globalThis.WebSocket.
     * Node.js <22: falls back to require('ws').
     */
    @JSBody(params = {"url", "cb"}, script = ""
        + "var WS = null;"
        + "if (typeof WebSocket === 'function') {"
        + "  WS = WebSocket;"
        + "} else if (typeof globalThis !== 'undefined' && typeof globalThis.WebSocket === 'function') {"
        + "  WS = globalThis.WebSocket;"
        + "} else if (typeof require === 'function') {"
        + "  try { WS = require('ws'); } catch(e) {}"
        + "}"
        + "if (!WS) return null;"
        + "var ws = new WS(url);"
        + "ws.onopen = function() { cb('open', '', 0, ''); };"
        + "ws.onmessage = function(evt) {"
        + "  var d = (evt && evt.data != null) ? String(evt.data) : '';"
        + "  cb('message', d, 0, '');"
        + "};"
        + "ws.onclose = function(evt) {"
        + "  var c, r;"
        + "  if (evt && typeof evt === 'object' && 'code' in evt) {"
        + "    c = evt.code || 1000; r = evt.reason || '';"
        + "  } else {"
        + "    c = arguments.length > 0 ? arguments[0] : 1000;"
        + "    r = arguments.length > 1 ? arguments[1] : '';"
        + "  }"
        + "  cb('close', '', c, r);"
        + "};"
        + "ws.onerror = function() { cb('error', '', 0, ''); };"
        + "return ws;")
    private static native JSObject createNative(String url, WsCallback cb);

    // ── Instance methods ──────────────────────────────────────────────────

    public TCompletableFuture<TWebSocket> sendText(CharSequence data, boolean last) {
        nativeSend(nativeWs, data.toString());
        return TCompletableFuture.completedFuture(this);
    }

    public TCompletableFuture<TWebSocket> sendClose(int code, String reason) {
        nativeClose(nativeWs, code, reason);
        return TCompletableFuture.completedFuture(this);
    }

    public void request(long n) {
        // No-op — backpressure handled by the native WebSocket
    }

    @JSBody(params = {"ws", "data"}, script = "if (ws && typeof ws.send === 'function') { try { ws.send(data); } catch(e) {} }")
    private static native void nativeSend(JSObject ws, String data);

    @JSBody(params = {"ws", "code", "reason"}, script = "if (ws && typeof ws.close === 'function') ws.close(code, reason);")
    private static native void nativeClose(JSObject ws, int code, String reason);

    // ── Builder ──────────────────────────────────────────────────────────

    public interface Builder {
        TCompletableFuture<TWebSocket> buildAsync(URI uri, Listener listener);
    }

    // ── Listener ─────────────────────────────────────────────────────────

    public interface Listener {
        void onOpen(TWebSocket ws);
        TCompletionStage<?> onText(TWebSocket ws, CharSequence data, boolean last);
        TCompletionStage<?> onClose(TWebSocket ws, int statusCode, String reason);
        void onError(TWebSocket ws, Throwable error);
    }

    // ── Builder implementation ───────────────────────────────────────────

    static class BuilderImpl implements Builder {
        @Override
        public TCompletableFuture<TWebSocket> buildAsync(URI uri, Listener listener) {
            return createAndConnect(uri, listener);
        }
    }
}
