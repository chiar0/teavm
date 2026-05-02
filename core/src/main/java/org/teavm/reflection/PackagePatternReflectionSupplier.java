/*
 *  Copyright 2026 Alexey Andreev.
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
package org.teavm.reflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.ValueType;

/**
 * A {@link ReflectionSupplier} that exposes fields and methods for all
 * classes whose binary name starts with one of the configured package prefixes.
 *
 * <p>Uses the JVM classloader at build time to resolve real class metadata,
 * ensuring all parameter types, return types, and field types are properly
 * resolved for the dependency graph.</p>
 */
public class PackagePatternReflectionSupplier implements ReflectionSupplier {
    private final List<String> packagePrefixes;

    public PackagePatternReflectionSupplier(List<String> packagePrefixes) {
        this.packagePrefixes = normalize(packagePrefixes);
    }

    private static List<String> normalize(List<String> prefixes) {
        if (prefixes == null) {
            return Collections.emptyList();
        }
        var result = new ArrayList<String>(prefixes.size());
        for (var prefix : prefixes) {
            if (prefix.endsWith(".*")) {
                prefix = prefix.substring(0, prefix.length() - 2);
            }
            if (!prefix.isEmpty()) {
                result.add(prefix);
            }
        }
        return result;
    }

    private boolean matches(String className) {
        for (var prefix : packagePrefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static ValueType classToValueType(Class<?> cls) {
        if (cls == int.class) return ValueType.INTEGER;
        if (cls == long.class) return ValueType.LONG;
        if (cls == float.class) return ValueType.FLOAT;
        if (cls == double.class) return ValueType.DOUBLE;
        if (cls == boolean.class) return ValueType.BOOLEAN;
        if (cls == byte.class) return ValueType.BYTE;
        if (cls == short.class) return ValueType.SHORT;
        if (cls == char.class) return ValueType.CHARACTER;
        if (cls == void.class) return ValueType.VOID;
        if (cls.isArray()) return ValueType.arrayOf(classToValueType(cls.getComponentType()));
        return ValueType.object(cls.getName());
    }

    private static ValueType[] makeSignature(Class<?>[] paramTypes, Class<?> returnType) {
        var sig = new ValueType[paramTypes.length + 1];
        for (int i = 0; i < paramTypes.length; i++) {
            sig[i] = classToValueType(paramTypes[i]);
        }
        sig[paramTypes.length] = classToValueType(returnType);
        return sig;
    }

    @Override
    public boolean isClassFoundByName(ReflectionContext context, String name) {
        return matches(name);
    }

    @Override
    public Collection<String> getAccessibleFields(ReflectionContext context, String className) {
        if (!matches(className)) {
            return Collections.emptyList();
        }
        try {
            Class<?> cls = context.getClassLoader().loadClass(className);
            var fields = new ArrayList<String>();
            for (Field f : cls.getDeclaredFields()) {
                if (cls.isEnum() || !Modifier.isStatic(f.getModifiers())) {
                    fields.add(f.getName());
                }
            }
            return fields;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return Collections.emptyList();
        } catch (LinkageError e) {
            System.err.println("[PackagePatternReflectionSupplier] Cannot load " + className + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public Collection<MethodDescriptor> getAccessibleMethods(ReflectionContext context,
            String className) {
        if (!matches(className)) {
            return Collections.emptyList();
        }
        try {
            Class<?> cls = context.getClassLoader().loadClass(className);
            var methods = new ArrayList<MethodDescriptor>();
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                methods.add(new MethodDescriptor("<init>",
                        makeSignature(ctor.getParameterTypes(), void.class)));
            }
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                methods.add(new MethodDescriptor(m.getName(),
                        makeSignature(m.getParameterTypes(), m.getReturnType())));
            }
            return methods;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return Collections.emptyList();
        } catch (LinkageError e) {
            System.err.println("[PackagePatternReflectionSupplier] Cannot load " + className + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
