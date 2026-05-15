/*
 *  TeaVM classlib shim for java.util.concurrent.CompletionStage.
 *  Provides minimal CompletionStage interface for TeaVM's single-threaded runtime.
 *
 *  Only the methods used by DatabaseFunctionsWebLudii are implemented.
 */

package org.teavm.classlib.java.util.concurrent;

import java.util.function.Function;

public interface TCompletionStage<T> {
    <U> TCompletionStage<U> thenApply(Function<? super T, ? extends U> fn);
    TCompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn);
}
