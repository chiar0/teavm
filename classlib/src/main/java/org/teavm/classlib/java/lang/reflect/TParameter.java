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
package org.teavm.classlib.java.lang.reflect;

import org.teavm.classlib.java.lang.TClass;
import org.teavm.classlib.java.lang.annotation.TAnnotation;

public class TParameter extends TAccessibleObject {
    private final TExecutable executable;
    private final int index;
    private final String name;
    private final TClass<?> type;
    private final int modifiers;

    TParameter(TExecutable executable, int index, String name, TClass<?> type, int modifiers) {
        this.executable = executable;
        this.index = index;
        this.name = name;
        this.type = type;
        this.modifiers = modifiers;
    }

    public String getName() {
        return name;
    }

    public TClass<?> getType() {
        return type;
    }

    public TExecutable getDeclaringExecutable() {
        return executable;
    }

    public int getModifiers() {
        return modifiers;
    }

    public boolean isNamePresent() {
        return !name.startsWith("arg");
    }

    @Override
    public TAnnotation[] getDeclaredAnnotations() {
        var paramAnnotations = executable.getParameterAnnotations();
        if (index < paramAnnotations.length) {
            return paramAnnotations[index];
        }
        return new TAnnotation[0];
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append(type != null ? type.getName() : "null");
        sb.append(' ');
        sb.append(name);
        return sb.toString();
    }
}
