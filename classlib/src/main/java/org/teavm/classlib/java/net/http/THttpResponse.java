/*
 *  TeaVM classlib shim for java.net.http.HttpResponse.
 *  Simple value holder — no browser API needed.
 */

package org.teavm.classlib.java.net.http;

import java.util.function.Function;

public class THttpResponse<T> {
    private final int statusCode;
    private final T body;

    THttpResponse(int statusCode, T body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public T body() {
        return body;
    }

    // ── BodyHandlers ──────────────────────────────────────────────────────

    public static class BodyHandlers {
        public static BodyHandler<String> ofString() {
            return (statusCode, body) -> body;
        }
    }

    @FunctionalInterface
    public interface BodyHandler<T> {
        T handle(int statusCode, String body);
    }
}
