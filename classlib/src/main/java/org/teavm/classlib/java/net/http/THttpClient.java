/*
 *  TeaVM classlib shim for java.net.http.HttpClient.
 *
 *  Uses fetch() for HTTP — native in browsers and Node.js 18+.
 *  Falls back to XMLHttpRequest in older environments.
 *  No external npm polyfills needed.
 *
 *  The send() method uses @Async/AsyncCallback to yield to the
 *  event loop while the request is in-flight.
 */

package org.teavm.classlib.java.net.http;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public class THttpClient {
    private Duration connectTimeout;
    private Duration requestTimeout;

    private THttpClient() {}

    public static Builder newBuilder() {
        return new Builder();
    }

    // ── HTTP send (synchronous-style, @Async for TeaVM) ───────────────────

    public THttpResponse<String> send(THttpRequest request, THttpResponse.BodyHandler<String> handler)
            throws IOException, InterruptedException {
        return doSend(request, handler);
    }

    @Async
    private native THttpResponse<String> doSend(THttpRequest request, THttpResponse.BodyHandler<String> handler);

    private void doSend(THttpRequest request, THttpResponse.BodyHandler<String> handler,
                        AsyncCallback<THttpResponse<String>> callback) {
        final String method = request.method();
        final String url = request.uri().toString();
        final String body = request.bodyContent();

        String headerJson = null;
        if (request.headers() != null && !request.headers().isEmpty()) {
            final StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (final Map.Entry<String, String> entry : request.headers().entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                  .append(escapeJson(entry.getValue())).append('"');
                first = false;
            }
            sb.append('}');
            headerJson = sb.toString();
        }

        // Compute timeout in milliseconds: use the shorter of connectTimeout / requestTimeout
        int timeoutMs = 0;
        if (connectTimeout != null) {
            timeoutMs = (int) Math.min(connectTimeout.toMillis(), Integer.MAX_VALUE);
        }
        if (requestTimeout != null) {
            final int reqMs = (int) Math.min(requestTimeout.toMillis(), Integer.MAX_VALUE);
            if (timeoutMs == 0 || reqMs < timeoutMs) {
                timeoutMs = reqMs;
            }
        }

        fetchRequest(method, url, body, headerJson, timeoutMs, (status, responseText) -> {
            callback.complete(new THttpResponse<>(status, handler.handle(status, responseText)));
        });
    }

    @JSFunctor
    private interface FetchCallback extends JSObject {
        void onComplete(int status, String responseText);
    }

    /**
     * Uses fetch() — available in all modern browsers and Node.js 18+.
     * Falls back to XMLHttpRequest if fetch() is not available.
     * Supports optional timeout via AbortController (fetch) or setTimeout (XHR).
     */
    @JSBody(params = {"method", "url", "body", "headersJson", "timeoutMs", "callback"}, script = ""
        + "var opts = {method: method};"
        + "if (body) opts.body = body;"
        + "if (headersJson) {"
        + "  try { opts.headers = JSON.parse(headersJson); } catch(e) {}"
        + "}"
        + "if (typeof fetch === 'function') {"
        + "  var controller = typeof AbortController === 'function' ? new AbortController() : null;"
        + "  if (controller) opts.signal = controller.signal;"
        + "  if (timeoutMs > 0 && controller) {"
        + "    setTimeout(function() { if (controller) controller.abort(); }, timeoutMs);"
        + "  }"
        + "  fetch(url, opts)"
        + "    .then(function(r) {"
        + "      return r.text().then(function(t) { return {s: r.status, b: t}; });"
        + "    })"
        + "    .then(function(result) { callback(result.s, result.b); })"
        + "    .catch(function(e) { callback(0, ''); });"
        + "} else if (typeof XMLHttpRequest === 'function' || typeof XMLHttpRequest === 'object') {"
        + "  var xhr = new XMLHttpRequest();"
        + "  xhr.open(method, url);"
        + "  if (timeoutMs > 0) xhr.timeout = timeoutMs;"
        + "  if (headersJson) {"
        + "    try {"
        + "      var h = JSON.parse(headersJson);"
        + "      for (var k in h) xhr.setRequestHeader(k, h[k]);"
        + "    } catch(e) {}"
        + "  }"
        + "  xhr.onreadystatechange = function() {"
        + "    if (xhr.readyState !== 4) return;"
        + "    callback(xhr.status, xhr.responseText || '');"
        + "  };"
        + "  xhr.ontimeout = function() { callback(0, ''); };"
        + "  xhr.send(body || null);"
        + "} else {"
        + "  callback(0, '');"
        + "}")
    private static native void fetchRequest(String method, String url, String body,
                                            String headersJson, int timeoutMs, FetchCallback callback);

    private static String escapeJson(final String s) {
        if (s == null) return "";
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else sb.append(c);
        }
        return sb.toString();
    }

    // ── WebSocket builder ─────────────────────────────────────────────────

    public TWebSocket.Builder newWebSocketBuilder() {
        return new TWebSocket.BuilderImpl();
    }

    public void close() {
        // no-op
    }

    // ── Builder ───────────────────────────────────────────────────────────

    public static class Builder {
        private Duration connectTimeout;
        private Duration requestTimeout;

        public Builder connectTimeout(Duration duration) {
            this.connectTimeout = duration;
            return this;
        }

        public THttpClient build() {
            final THttpClient client = new THttpClient();
            client.connectTimeout = this.connectTimeout;
            client.requestTimeout = this.requestTimeout;
            return client;
        }
    }
}
