/*
 *  Copyright 2016 Alexey Andreev.
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
package org.teavm.classlib;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.ValueType;

public interface ReflectionSupplier {
    default Collection<String> getAccessibleFields(ReflectionContext context, String className) {
        return Collections.emptyList();
    }

    default Collection<MethodDescriptor> getAccessibleMethods(ReflectionContext context, String className) {
        return Collections.emptyList();
    }

    default Collection<MethodDescriptor> getProactivelyAccessibleMethods(
            ReflectionContext context, String className) {
        Collection<MethodDescriptor> all = getAccessibleMethods(context, className);
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        List<MethodDescriptor> ctors = new ArrayList<>();
        for (MethodDescriptor m : all) {
            if (m.getName().equals("<init>")) {
                ctors.add(m);
            }
        }
        return ctors;
    }

    /**
     * Returns the types from method signatures that should be linked into the
     * dependency graph when method reflection is activated for the given class.
     * This controls which types are transitively compiled when
     * {@code getDeclaredMethods()} or {@code getMethods()} is called on the class.
     *
     * <p>Returning an empty collection prevents type linking entirely — method
     * metadata (names, descriptors, annotations) is still generated, but the
     * types referenced in signatures won't be added to the dependency graph.
     * This is useful when callers only inspect method signatures without
     * invoking methods or instantiating parameter/return types.
     *
     * <p>The default implementation returns all distinct types from the accessible
     * methods' return types and parameter types.
     *
     * @param context reflection context
     * @param className the class being reflectively queried for methods
     * @return types to link into the dependency graph, or empty to skip linking
     */
    default Collection<ValueType> getMethodSignatureTypesToLink(
            ReflectionContext context, String className) {
        Collection<MethodDescriptor> methods = getAccessibleMethods(context, className);
        if (methods == null || methods.isEmpty()) {
            return Collections.emptyList();
        }
        Set<ValueType> types = new LinkedHashSet<>();
        for (MethodDescriptor m : methods) {
            types.add(m.getResultType());
            Collections.addAll(types, m.getParameterTypes());
        }
        return types;
    }

    @Deprecated
    default Collection<String> getClassesFoundByName(ReflectionContext context) {
        return Collections.emptyList();
    }

    default boolean isClassFoundByName(ReflectionContext context, String name) {
        return false;
    }
}
