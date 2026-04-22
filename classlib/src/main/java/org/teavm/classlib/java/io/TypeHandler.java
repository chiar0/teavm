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
 * Plugin interface for serializing/deserializing custom types
 * that are not part of the standard Java serialization format.
 * Register via {@link TObjectOutputStream#registerTypeHandler}
 * and {@link TObjectInputStream#registerTypeHandler}.
 */
public interface TypeHandler {
    /**
     * Type code for this handler. Must be a byte value not used by
     * the standard format (TC_NULL through TC_ENUM, TC_BITSET, TC_SCHEMA).
     * Recommended range: 0x62-0x7F.
     */
    int typeCode();

    /**
     * Returns true if this handler can serialize the given object.
     */
    boolean canWrite(Object obj);

    /**
     * Write the object to the stream. The implementation should call
     * out.writeXxx() methods to emit the object's data.
     * The type code byte is already written by the dispatcher.
     */
    void write(Object obj, TObjectOutputStream out) throws java.io.IOException;

    /**
     * Read an object from the stream. The type code byte has already been
     * consumed by the dispatcher. Returns the reconstructed object.
     */
    Object read(int typeCode, TObjectInputStream in) throws java.io.IOException;
}
