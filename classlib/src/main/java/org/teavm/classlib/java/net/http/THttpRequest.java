/*
 *  TeaVM classlib shim for java.net.http.HttpRequest.
 *  Simple value holder — no browser API needed.
 *
 *  Matches JDK structure:
 *  - HttpRequest (value holder)
 *  - HttpRequest.Builder (fluent builder)
 *  - HttpRequest.BodyPublisher (body content wrapper)
 *  - HttpRequest.BodyPublishers (factory for BodyPublisher instances)
 */

package org.teavm.classlib.java.net.http;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class THttpRequest {
    private final URI uri;
    private final String method;
    private final String bodyContent;
    private final Map<String, String> headers;
    private final Duration timeout;

    private THttpRequest(Builder builder) {
        this.uri = builder.uri;
        this.method = builder.method;
        this.bodyContent = builder.bodyPublisher != null ? builder.bodyPublisher.content : null;
        this.headers = new HashMap<>(builder.headers);
        this.timeout = builder.timeout;
    }

    public URI uri() { return uri; }
    public String method() { return method; }
    public String bodyContent() { return bodyContent; }
    public Map<String, String> headers() { return headers; }
    public Duration timeout() { return timeout; }

    public static Builder newBuilder() {
        return new Builder();
    }

    // ── BodyPublisher ─────────────────────────────────────────────────────

    public static class BodyPublisher {
        private final String content;

        BodyPublisher(String content) { this.content = content; }

        public String content() { return content; }
    }

    // ── BodyPublishers (factory) ──────────────────────────────────────────

    public static class BodyPublishers {
        public static BodyPublisher ofString(String body) {
            return new BodyPublisher(body);
        }

        public static BodyPublisher noBody() {
            return new BodyPublisher(null);
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────

    public static class Builder {
        private URI uri;
        private String method = "GET";
        private BodyPublisher bodyPublisher;
        private final Map<String, String> headers = new HashMap<>();
        private Duration timeout;

        public Builder uri(URI uri) { this.uri = uri; return this; }

        public Builder GET() { this.method = "GET"; this.bodyPublisher = null; return this; }

        public Builder POST(BodyPublisher bodyPublisher) {
            this.method = "POST";
            this.bodyPublisher = bodyPublisher;
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public Builder timeout(Duration duration) { this.timeout = duration; return this; }

        public THttpRequest build() {
            return new THttpRequest(this);
        }
    }
}
