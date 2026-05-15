/*
 *  Copyright 2024 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.classlib.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * General-purpose functional collection utilities for pre-Java-8 environments.
 * Provides common operations like filter, map, and flatten without requiring
 * the Stream API or {@code java.util.function} package.
 *
 * @author Alexey Andreev
 */
public final class CollectionUtils {
    private CollectionUtils() {
    }

    /**
     * Represents a predicate (boolean-valued function) of one argument.
     *
     * @param <T> the type of the input to the predicate
     */
    @FunctionalInterface
    public interface Predicate<T> {
        boolean test(T value);
    }

    /**
     * Represents a function that accepts one argument and produces a result.
     *
     * @param <T> the type of the input to the function
     * @param <R> the type of the result of the function
     */
    @FunctionalInterface
    public interface Function<T, R> {
        R apply(T value);
    }

    /**
     * Returns a new list containing only the elements that match the given predicate.
     *
     * @param <T>   the type of elements in the source collection
     * @param source the source collection to filter
     * @param pred the predicate to test each element against
     * @return a new list of elements that satisfy the predicate
     */
    public static <T> List<T> filter(Collection<T> source, Predicate<T> pred) {
        List<T> result = new ArrayList<>();
        for (T item : source) {
            if (pred.test(item)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Returns a new list by applying the given function to each element of the source.
     *
     * @param <T>   the type of elements in the source collection
     * @param <R>   the type of elements in the resulting list
     * @param source the source collection
     * @param fn    the function to apply to each element
     * @return a new list of transformed elements
     */
    public static <T, R> List<R> map(Collection<T> source, Function<T, R> fn) {
        List<R> result = new ArrayList<>(source.size());
        for (T item : source) {
            result.add(fn.apply(item));
        }
        return result;
    }

    /**
     * Flattens a collection of collections into a single flat list.
     *
     * @param <T>    the type of elements
     * @param nested the collection of collections to flatten
     * @return a new list containing all elements from all nested collections
     */
    public static <T> List<T> flatten(Collection<? extends Collection<T>> nested) {
        List<T> result = new ArrayList<>();
        for (Collection<T> inner : nested) {
            result.addAll(inner);
        }
        return result;
    }

    /**
     * Returns {@code true} if any element in the collection matches the given predicate.
     *
     * @param <T>   the type of elements
     * @param source the source collection
     * @param pred the predicate to test each element against
     * @return {@code true} if at least one element satisfies the predicate
     */
    public static <T> boolean any(Collection<T> source, Predicate<T> pred) {
        for (T item : source) {
            if (pred.test(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if all elements in the collection match the given predicate.
     *
     * @param <T>   the type of elements
     * @param source the source collection
     * @param pred the predicate to test each element against
     * @return {@code true} if every element satisfies the predicate
     */
    public static <T> boolean all(Collection<T> source, Predicate<T> pred) {
        for (T item : source) {
            if (!pred.test(item)) {
                return false;
            }
        }
        return true;
    }
}
