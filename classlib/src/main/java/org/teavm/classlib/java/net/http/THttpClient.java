/*
 *  TeaVM classlib shim for java.net.http.HttpClient.
 *  Uses XHR (XMLHttpRequest) via TeaVM's JSO wrapper for HTTP requests,
 *  and returns a TWebSocket.Builder for WebSocket connections.
 *
 *  The send() method uses @Async/AsyncCallback to yield to the browser
 *  event loop while the XHR request is in-flight — same pattern as
 *  TXHRURLConnection.performRequest().
 */

package org.teavm.classlib.java.net.http;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.ajax.XMLHttpRequest;

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
        final XMLHttpRequest xhr = new XMLHttpRequest();

        xhr.open(request.method(), request.uri().toString());

        // Set headers
        if (request.headers() != null) {
            for (final Map.Entry<String, String> entry : request.headers().entrySet()) {
                xhr.setRequestHeader(entry.getKey(), entry.getValue());
            }
        }

        xhr.setOnReadyStateChange(() -> {
            if (xhr.getReadyState() != XMLHttpRequest.DONE) return;

            final int status = xhr.getStatus();
            final String body = xhr.getResponseText();
            callback.complete(new THttpResponse<>(status, handler.handle(status, body)));
        });

        // Send with body (POST) or without (GET)
        final String body = request.bodyContent();
        if (body != null) {
            xhr.send(body);
        } else {
            xhr.send();
        }
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
