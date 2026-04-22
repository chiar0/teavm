/*
 *  Copyright 2025 contributor.
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
package org.teavm.classlib.java.io;

/**
 * Stub for java.io.ObjectStreamField, required by classes compiled with
 * {@code serialPersistentFields} declarations.
 * TeaVM does not include ObjectStreamField in its classlib; this stub satisfies
 * the static-initialiser reference so TeaVM can compile the class without aborting.
 */
public class TObjectStreamField implements Comparable<TObjectStreamField> {

    private final String name;
    private final Class<?> type;

    public TObjectStreamField(String name, Class<?> type) {
        this.name = name;
        this.type = type;
    }

    public TObjectStreamField(String name, Class<?> type, boolean unshared) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public Class<?> getType() {
        return type;
    }

    public char getTypeCode() {
        if (type == null) {
            return 'L';
        }
        if (type == byte.class) {
            return 'B';
        }
        if (type == char.class) {
            return 'C';
        }
        if (type == double.class) {
            return 'D';
        }
        if (type == float.class) {
            return 'F';
        }
        if (type == int.class) {
            return 'I';
        }
        if (type == long.class) {
            return 'J';
        }
        if (type == short.class) {
            return 'S';
        }
        if (type == boolean.class) {
            return 'Z';
        }
        if (type.isArray()) {
            return '[';
        }
        return 'L';
    }

    public String getTypeString() {
        return null;
    }

    public int getOffset() {
        return 0;
    }

    public boolean isPrimitive() {
        return type != null && type.isPrimitive();
    }

    public boolean isUnshared() {
        return false;
    }

    @Override
    public int compareTo(TObjectStreamField other) {
        return this.name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return name + ":" + (type != null ? type.getName() : "?");
    }
}
