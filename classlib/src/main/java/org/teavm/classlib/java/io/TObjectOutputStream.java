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

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * TeaVM shim for java.io.ObjectOutputStream.
 * <p>
 * Implements custom serialization using a binary format that can be
 * deserialized by {@link TObjectInputStream} on any TeaVM backend (JS or Wasm GC).
 * Uses Java reflection to recursively traverse object graphs including
 * all non-static, non-transient fields of each object.
 * <p>
 * Custom types can be registered via {@link #registerTypeHandler(TypeHandler)}.
 * Diagnostic callbacks can be installed via {@link #setListener(TSerializationListener)}.
 */
public class TObjectOutputStream extends OutputStream implements TObjectOutput {

    private final OutputStream out;
    /** Identity-based reference tracking to handle circular references */
    private final IdentityHashMap<Object, Integer> objectRefs = new IdentityHashMap<>();
    private final Map<String, FieldSchema> schemaManifest = new HashMap<>();
    private int nextHandle = 0x7e0000;

    private static int lastTotalObjectCount;

    /** Depth guard: maximum recursive writeObject depth before truncation. */
    private static final int MAX_OBJECT_DEPTH = 512;
    private int writeDepth;

    /** When true, transient fields are serialized alongside non-transient ones.
     *  TeaVM doesn't support custom readObject()/writeObject(), so transient
     *  data arrays in classes like ContainerState are lost unless we serialize
     *  them explicitly.  Set before game serialization, reset after. */
    private static boolean includeTransientFields;

    // Type handler registry
    private final List<TypeHandler> typeHandlers = new ArrayList<>();

    public void registerTypeHandler(TypeHandler handler) {
        typeHandlers.add(handler);
    }

    // Listener
    private TSerializationListener listener;

    public void setListener(TSerializationListener listener) {
        this.listener = listener;
    }

    public static void setIncludeTransientFields(boolean value) {
        includeTransientFields = value;
    }

    /** Returns total objects registered by the most recent serializer instance. */
    public static int getLastTotalObjectCount() {
        return lastTotalObjectCount;
    }

    /** Diagnostic: dump the last N class names written before failure. */
    public static String dumpDiagRing() {
        StringBuilder sb = new StringBuilder();
        sb.append("diagWriteCount=").append(diagWriteCount).append(" lastClasses=");
        int count = Math.min(diagRingIdx, DIAG_RING_SIZE);
        int start = (diagRingIdx > DIAG_RING_SIZE) ? (diagRingIdx % DIAG_RING_SIZE) : 0;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % DIAG_RING_SIZE;
            if (i > 0) {
                sb.append(',');
            }
            sb.append(diagRing[idx]);
        }
        return sb.toString();
    }

    public TObjectOutputStream(OutputStream out) throws IOException {
        this(out, false);
    }

    private TObjectOutputStream(OutputStream out, boolean skipHeader) throws IOException {
        this.out = out;
        if (!skipHeader) {
            writeShort(STREAM_MAGIC);
            writeShort(STREAM_VERSION);
        }
    }

    /** Diagnostic: count of objects written, for tracking OOB location. */
    private static int diagWriteCount;
    /** Diagnostic: last N class names written before a failure. */
    private static final int DIAG_RING_SIZE = 64;
    private static final String[] diagRing = new String[DIAG_RING_SIZE];
    private static int diagRingIdx;

    public final void writeObject(Object obj) throws IOException {
        // Try registered type handlers first
        for (TypeHandler handler : typeHandlers) {
            if (handler.canWrite(obj)) {
                // Register before writing to maintain handle consistency
                // with the standard write path (handles back-references)
                Integer refHandle = objectRefs.get(obj);
                if (refHandle != null) {
                    writeByte(TC_REFERENCE);
                    writeInt(refHandle);
                    return;
                }
                int handle = nextHandle++;
                objectRefs.put(obj, handle);

                writeByte(handler.typeCode());
                handler.write(obj, this);
                return;
            }
        }

        if (obj == null) {
            writeByte(TC_NULL);
            return;
        }

        // Check for back-reference (identity-based)
        Integer refHandle = objectRefs.get(obj);
        if (refHandle != null) {
            writeByte(TC_REFERENCE);
            writeInt(refHandle);
            return;
        }

        // Register this object before serializing children (handles cycles)
        int handle = nextHandle++;
        objectRefs.put(obj, handle);

        Class<?> clazz = obj.getClass();
        String className = clazz.getName();

        // Diagnostic: record class name in ring buffer
        diagWriteCount++;
        int rIdx = (diagRingIdx++) % DIAG_RING_SIZE;
        diagRing[rIdx] = className;

        if (listener != null) {
            listener.onWriteObject(obj, writeDepth);
        }

        // Handle by type (most specific first)
        if (obj instanceof String) {
            writeByte(TC_STRING);
            writeUTF((String) obj);
        } else if (obj instanceof Integer) {
            writeByte(TC_INTEGER);
            writeInt((Integer) obj);
        } else if (obj instanceof Long) {
            writeByte(TC_LONG);
            writeLong((Long) obj);
        } else if (obj instanceof Double) {
            writeByte(TC_DOUBLE);
            writeDouble((Double) obj);
        } else if (obj instanceof Float) {
            writeByte(TC_FLOAT);
            writeFloat((Float) obj);
        } else if (obj instanceof Boolean) {
            writeByte(TC_BOOLEAN);
            writeBoolean((Boolean) obj);
        } else if (obj instanceof Short) {
            writeByte(TC_SHORT);
            writeShort(((Short) obj).shortValue());
        } else if (obj instanceof Character) {
            writeByte(TC_CHAR);
            writeChar(((Character) obj).charValue());
        } else if (obj instanceof Byte) {
            writeByte(TC_BYTE);
            writeByte(((Byte) obj).byteValue());
        } else if (obj instanceof Enum) {
            // Enums: write canonical declaring class name + ordinal for reliable reconstruction.
            // TeaVM 0.14 may create anonymous inner classes for individual enum constants
            // (e.g., AbsoluteDirection$1 for AbsoluteDirection.N).  We must write the
            // declaring-class name so the deserializer can resolve it via Class.forName().
            writeByte(TC_ENUM);
            writeUTF(canonicalEnumClassName((Enum<?>) obj, className));
            writeInt(((Enum<?>) obj).ordinal());
        } else if (obj instanceof boolean[]) {
            writeByte(TC_ARRAY);
            writeUTF("[Z");
            boolean[] arr = (boolean[]) obj;
            writeInt(arr.length);
            for (boolean v : arr) {
                writeBoolean(v);
            }
        } else if (obj instanceof byte[]) {
            writeByte(TC_ARRAY);
            writeUTF("[B");
            byte[] arr = (byte[]) obj;
            writeInt(arr.length);
            write(arr);
        } else if (obj instanceof int[]) {
            writeByte(TC_ARRAY);
            writeUTF("[I");
            int[] arr = (int[]) obj;
            writeInt(arr.length);
            for (int v : arr) {
                writeInt(v);
            }
        } else if (obj instanceof long[]) {
            writeByte(TC_ARRAY);
            writeUTF("[J");
            long[] arr = (long[]) obj;
            writeInt(arr.length);
            for (long v : arr) {
                writeLong(v);
            }
        } else if (obj instanceof double[]) {
            writeByte(TC_ARRAY);
            writeUTF("[D");
            double[] arr = (double[]) obj;
            writeInt(arr.length);
            for (double v : arr) {
                writeDouble(v);
            }
        } else if (obj instanceof float[]) {
            writeByte(TC_ARRAY);
            writeUTF("[F");
            float[] arr = (float[]) obj;
            writeInt(arr.length);
            for (float v : arr) {
                writeFloat(v);
            }
        } else if (obj instanceof short[]) {
            writeByte(TC_ARRAY);
            writeUTF("[S");
            short[] arr = (short[]) obj;
            writeInt(arr.length);
            for (short v : arr) {
                writeShort(v);
            }
        } else if (obj instanceof char[]) {
            writeByte(TC_ARRAY);
            writeUTF("[C");
            char[] arr = (char[]) obj;
            writeInt(arr.length);
            for (char v : arr) {
                writeChar(v);
            }
        } else if (obj instanceof Object[]) {
            writeByte(TC_ARRAY);
            writeUTF(className);
            Object[] arr = (Object[]) obj;
            writeInt(arr.length);
            for (Object o : arr) {
                writeObject(o);
            }
        } else if (obj instanceof BitSet) {
            writeByte(TC_BITSET);
            BitSet bs = (BitSet) obj;
            int card = bs.cardinality();
            writeInt(card);
            int bit = bs.nextSetBit(0);
            while (bit >= 0) {
                writeInt(bit);
                bit = bs.nextSetBit(bit + 1);
            }
        } else if (obj instanceof List) {
            writeByte(TC_LIST);
            writeUTF(className);
            List<?> list = (List<?>) obj;
            writeInt(list.size());
            for (Object o : list) {
                writeObject(o);
            }
        } else if (obj instanceof Map) {
            writeByte(TC_MAP);
            writeUTF(className);
            Map<?, ?> map = (Map<?, ?>) obj;
            writeInt(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                writeObject(e.getKey());
                writeObject(e.getValue());
            }
        } else {
            // Generic object: write class name then all fields via reflection
            writeByte(TC_OBJECT);
            writeUTF(className);
            writeObjectData(obj);
        }
    }

    protected void writeObjectData(Object obj) throws IOException {
        if (!schemaManifest.containsKey(obj.getClass().getName())) {
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    int mods = f.getModifiers();
                    if (!Modifier.isStatic(mods)
                            && (includeTransientFields || !Modifier.isTransient(mods))) {
                        names.add(f.getName());
                        types.add(typeDescriptor(f.getType()));
                    }
                }
                c = c.getSuperclass();
            }
            schemaManifest.put(obj.getClass().getName(), new FieldSchema(
                obj.getClass().getName(),
                names.toArray(new String[0]),
                types.toArray(new String[0])
            ));
        }

        writeDepth++;
        try {
            // Depth guard: truncate graphs deeper than MAX_OBJECT_DEPTH
            if (writeDepth > MAX_OBJECT_DEPTH) {
                writeShort(0);
                return;
            }

            List<Field> fields = new ArrayList<>();
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    int mods = f.getModifiers();
                    if (!Modifier.isStatic(mods)
                            && (includeTransientFields || !Modifier.isTransient(mods))) {
                        fields.add(f);
                    }
                }
                c = c.getSuperclass();
            }

            writeShort(fields.size());
            for (Field f : fields) {
                String fieldName = f.getName();
                String ftName = f.getType().getName();
                writeUTF(fieldName);
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    writeObject(val);
                } catch (IllegalAccessException e) {
                    if (listener != null) {
                        listener.onError("write",
                                obj != null ? obj.getClass().getName() : "null", e);
                    }
                    writeByte(TC_NULL);
                } catch (IOException e) {
                    throw e;
                } catch (Throwable e) {
                    // Catch Wasm GC traps (e.g. "array element access out of bounds")
                    // and rethrow with context about which class/field failed.
                    String className = (obj != null) ? obj.getClass().getName() : "null";
                    String msg = className + "." + fieldName + " (" + ftName + "): " + e.getMessage();
                    throw new IOException("Serialization failed at " + msg, e);
                }
            }
        } finally {
            writeDepth--;
        }
    }

    /**
     * Return the canonical enum class name for serialisation.
     * <p>
     * TeaVM 0.14 creates a separate JS constructor for each enum constant that
     * overrides behaviour (e.g., {@code AbsoluteDirection$1} for {@code N}).
     * {@code getDeclaringClass()} returns the real enum class, but some TeaVM
     * builds may not implement it correctly, so we also fall back to stripping
     * trailing {@code $<digits>} suffixes from the raw class name.
     */
    private static String canonicalEnumClassName(Enum<?> e, String rawName) {
        try {
            Class<?> dc = e.getDeclaringClass();
            if (dc != null) {
                return dc.getName();
            }
        } catch (Exception ex) {
            // fall through
        }
        int dollar = rawName.lastIndexOf('$');
        if (dollar >= 0) {
            String suffix = rawName.substring(dollar + 1);
            boolean allDigits = !suffix.isEmpty();
            for (int i = 0; i < suffix.length(); i++) {
                if (!Character.isDigit(suffix.charAt(i))) {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) {
                return rawName.substring(0, dollar);
            }
        }
        return rawName;
    }

    // ── Schema manifest ──────────────────────────────────────────────────────

    public void writeSchemaManifest() throws IOException {
        writeByte(TC_SCHEMA);
        writeShort(schemaManifest.size());
        for (FieldSchema fs : schemaManifest.values()) {
            writeUTF(fs.className);
            writeShort(fs.fieldNames.length);
            for (int i = 0; i < fs.fieldNames.length; i++) {
                writeUTF(fs.fieldNames[i]);
                writeUTF(fs.fieldTypeDescriptors[i]);
            }
        }
    }

    /**
     * Serialize an object with a schema manifest prepended.
     * Output: magic(2) + version(2) + TC_SCHEMA + schema data + object data
     */
    public static byte[] serializeWithSchema(Object obj) throws IOException {
        // Pass 1: serialize to collect schema
        java.io.ByteArrayOutputStream probe = new java.io.ByteArrayOutputStream();
        TObjectOutputStream probeOut = new TObjectOutputStream(probe);
        probeOut.writeObject(obj);
        probeOut.close();
        Map<String, FieldSchema> schema = new HashMap<>(probeOut.schemaManifest);
        byte[] objectBytes = probe.toByteArray();

        // Pass 2: write header + schema + object data (skip duplicate header)
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        TObjectOutputStream schemaWriter = new TObjectOutputStream(result, true);
        schemaWriter.writeShort(STREAM_MAGIC);
        schemaWriter.writeShort(STREAM_VERSION);
        schemaWriter.schemaManifest.putAll(schema);
        schemaWriter.writeSchemaManifest();
        schemaWriter.flush();
        // Append object data (skip its magic+version header)
        result.write(objectBytes, 4, objectBytes.length - 4);
        return result.toByteArray();
    }

    private static String typeDescriptor(Class<?> c) {
        if (c == int.class) {
            return "I";
        }
        if (c == long.class) {
            return "J";
        }
        if (c == double.class) {
            return "D";
        }
        if (c == float.class) {
            return "F";
        }
        if (c == boolean.class) {
            return "Z";
        }
        if (c == byte.class) {
            return "B";
        }
        if (c == short.class) {
            return "S";
        }
        if (c == char.class) {
            return "C";
        }
        if (c == void.class) {
            return "V";
        }
        if (c.isArray()) {
            return c.getName().replace('.', '/');
        }
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static final class FieldSchema {
        final String className;
        final String[] fieldNames;
        final String[] fieldTypeDescriptors;

        FieldSchema(String className, String[] fieldNames, String[] fieldTypeDescriptors) {
            this.className = className;
            this.fieldNames = fieldNames;
            this.fieldTypeDescriptors = fieldTypeDescriptors;
        }
    }

    // ── Primitive writes ──────────────────────────────────────────────────────

    public void writeInt(int v) throws IOException {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public void writeLong(long v) throws IOException {
        out.write((int) (v >> 56) & 0xFF);
        out.write((int) (v >> 48) & 0xFF);
        out.write((int) (v >> 40) & 0xFF);
        out.write((int) (v >> 32) & 0xFF);
        out.write((int) (v >> 24) & 0xFF);
        out.write((int) (v >> 16) & 0xFF);
        out.write((int) (v >> 8) & 0xFF);
        out.write((int) v & 0xFF);
    }

    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public void writeBoolean(boolean v) throws IOException {
        out.write(v ? 1 : 0);
    }

    public void writeByte(int v) throws IOException {
        out.write(v & 0xFF);
    }

    public void writeShort(int v) throws IOException {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public void writeChar(int v) throws IOException {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public void writeBytes(String s) throws IOException {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            out.write(s.charAt(i) & 0xFF);
        }
    }

    public void writeChars(String s) throws IOException {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            writeChar(s.charAt(i));
        }
    }

    public void writeUTF(String s) throws IOException {
        if (s == null) {
            writeShort(-1);
            return;
        }
        byte[] bytes = s.getBytes("UTF-8");
        if (bytes.length > 65535) {
            throw new IOException("String too long for UTF encoding: "
                    + bytes.length + " bytes (max 65535)");
        }
        writeShort(bytes.length);
        out.write(bytes);
    }

    public void write(byte[] b) throws IOException {
        out.write(b);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        lastTotalObjectCount = objectRefs.size();
        out.close();
    }

    /**
     * Reset the stream, clearing the internal object reference table so that
     * objects written subsequently are treated as new objects.
     */
    public void reset() throws IOException {
        objectRefs.clear();
        nextHandle = 0x7e0000;
    }

    /**
     * Write the default serializable fields of the current object.
     * In TeaVM's custom serialization this is a no-op because fields are
     * always written automatically by {@link #writeObjectData(Object)}.
     */
    public void defaultWriteObject() throws IOException {
        // No-op: field traversal is handled by writeObjectData()
    }

    // ── Type codes (ACED header matches standard Java, object/primitive codes are custom) ──

    static final int  STREAM_MAGIC   = 0xACED;
    static final int  STREAM_VERSION = 0x0005;

    static final byte TC_NULL        = 0x70;   // null reference
    static final byte TC_REFERENCE   = 0x71;   // back-reference
    static final byte TC_STRING      = 0x74;   // String
    static final byte TC_ARRAY       = 0x75;   // any array (className follows)
    static final byte TC_OBJECT      = 0x73;   // generic object (reflection)
    static final byte TC_INTEGER     = 0x4C;   // Integer / int
    static final byte TC_LONG        = 0x4D;   // Long / long
    static final byte TC_DOUBLE      = 0x4E;   // Double / double
    static final byte TC_FLOAT       = 0x4F;   // Float / float
    static final byte TC_BOOLEAN     = 0x5A;   // Boolean / boolean
    static final byte TC_LIST        = 0x5B;   // List (ArrayList etc.)
    static final byte TC_MAP         = 0x5C;   // Map (HashMap etc.)
    static final byte TC_SHORT       = 0x5D;   // Short / short
    static final byte TC_CHAR        = 0x5E;   // Character / char
    static final byte TC_BYTE        = 0x5F;   // Byte / byte
    static final byte TC_ENUM        = 0x60;   // Enum (ordinal follows)
    // ── Special-cased library types (no $meta.fields in TeaVM) ──
    static final byte TC_BITSET            = 0x61;   // java.util.BitSet
    static final byte TC_SCHEMA            = 0x67;   // schema manifest block
}
