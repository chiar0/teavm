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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * TeaVM shim for java.io.ObjectOutputStream.
 * <p>
 * Implements custom serialization using a binary format that can be
 * deserialized by {@link TObjectInputStream} on any TeaVM backend (JS or Wasm GC).
 * Uses Java reflection to traverse object graphs including
 * all non-static, non-transient fields of each object.
 * <p>
 * Uses an iterative frame-based approach (mirroring {@link TObjectInputStream})
 * to avoid JS/Wasm call-stack overflow on deeply nested object graphs.
 * Container types (Object[], List, Map, custom objects) push a {@link WriteFrame}
 * onto a heap-allocated stack; the main loop processes frames iteratively.
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

    /** When true, transient fields are serialized alongside non-transient ones.
     *  TeaVM doesn't support custom readObject()/writeObject(), so transient
     *  data arrays in classes like ContainerState are lost unless we serialize
     *  them explicitly.  Set before game serialization, reset after. */
    private static boolean includeTransientFields;

    // ── Iterative frame stack ──────────────────────────────────────────────────

    /** Sentinel: advanceFrame returns this when a child value is null.
     *  (Returning null means "frame is fully consumed".) */
    private static final Object NULL_CHILD = new Object();

    private static final class WriteFrame {
        static final int WRITE_FIELDS = 1;
        static final int WRITE_ARRAY  = 2;
        static final int WRITE_LIST   = 3;
        static final int WRITE_MAP    = 4;
        static final int WRITE_SET    = 5;

        int type;
        Object container;        // The object whose children are being written
        Field[] fields;          // WRITE_FIELDS: ordered fields
        Object[] array;          // WRITE_ARRAY
        List<?> list;            // WRITE_LIST
        List<Object> mapKeys;    // WRITE_MAP
        List<Object> mapValues;  // WRITE_MAP
        int nextIndex;
        boolean waitingForValue; // WRITE_MAP: alternating key/value
    }

    private WriteFrame[] frameStack = new WriteFrame[256];
    private int frameTop;

    private WriteFrame pushFrame() {
        if (frameTop >= frameStack.length) {
            WriteFrame[] bigger = new WriteFrame[frameStack.length * 2];
            System.arraycopy(frameStack, 0, bigger, 0, frameStack.length);
            frameStack = bigger;
        }
        WriteFrame f = frameStack[frameTop];
        if (f == null) {
            f = new WriteFrame();
            frameStack[frameTop] = f;
        }
        frameTop++;
        return f;
    }

    // ── Type handler registry ──────────────────────────────────────────────────

    private final List<TypeHandler> typeHandlers = new ArrayList<>();

    public void registerTypeHandler(TypeHandler handler) {
        typeHandlers.add(handler);
    }

    // ── Listener ───────────────────────────────────────────────────────────────

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

    // ── Iterative main loop ────────────────────────────────────────────────────

    /**
     * Write an object to the stream using iterative frame-based traversal.
     * <p>
     * Container types (Object[], List, Map, custom objects) push a frame onto
     * the heap-allocated stack instead of recursing. The main loop processes
     * frames until the entire sub-graph rooted at {@code obj} is written.
     * <p>
     * TypeHandlers that call {@code writeObject(subObj)} from their
     * {@code write()} method trigger a nested iterative loop that completes
     * the sub-object before returning. The {@code baseFrame} marker ensures
     * each invocation only processes its own frames.
     */

    /** Byte position counter (for diagnostics). */
    private int bytePos;

    public final void writeObject(Object obj) throws IOException {
        int baseFrame = frameTop;
        if (!writeOneObject(obj)) {
            while (frameTop > baseFrame) {
                WriteFrame f = frameStack[frameTop - 1];
                Object nextChild = advanceFrame(f);
                if (nextChild == null) {
                    frameTop--;
                    continue;
                }
                if (nextChild == NULL_CHILD) {
                    writeOneObject(null);
                } else {
                    writeOneObject(nextChild);
                }
            }
        }
    }

    /**
     * Write a single object's header and body.
     * <p>
     * Leaf types (null, primitives, strings, enums, primitive arrays, BitSet)
     * are fully written and return {@code true}.
     * <p>
     * Container types (Object[], List, Map, custom objects) write their header
     * (type code, class name, element/field count) and push a frame for the
     * remaining children. Returns {@code false} to signal the caller should
     * run the iterative loop.
     *
     * @return {@code true} if the object was fully written (leaf);
     *         {@code false} if a frame was pushed (container, needs children)
     */
    private boolean writeOneObject(Object obj) throws IOException {
        // ── Null ──
        if (obj == null) {
            writeByte(TC_NULL);
            return true;
        }

        // ── Back-reference (identity-based) ──
        Integer refHandle = objectRefs.get(obj);
        if (refHandle != null) {
            writeByte(TC_REFERENCE);
            writeInt(refHandle);
            return true;
        }

        // ── Register handle before writing (handles cycles) ──
        int handle = nextHandle++;
        objectRefs.put(obj, handle);

        // ── TypeHandler ──
        for (TypeHandler handler : typeHandlers) {
            if (handler.canWrite(obj)) {
                writeByte(handler.typeCode());
                handler.write(obj, this);
                return true;
            }
        }

        Class<?> clazz = obj.getClass();
        String className = clazz.getName();

        // Diagnostic: record class name in ring buffer
        diagWriteCount++;
        int rIdx = (diagRingIdx++) % DIAG_RING_SIZE;
        diagRing[rIdx] = className;

        if (listener != null) {
            listener.onWriteObject(obj, frameTop);
        }

        // ── Leaf types: write fully and return true ──

        if (obj instanceof String) {
            String s = (String) obj;
            byte[] bytes = s.getBytes("UTF-8");
            if (bytes.length > 65535) {
                writeByte(TC_LONGSTRING);
                writeInt(bytes.length);
                out.write(bytes);
            } else {
                writeByte(TC_STRING);
                writeUTF(s);
            }
            return true;
        } else if (obj instanceof Integer) {
            writeByte(TC_INTEGER);
            writeInt((Integer) obj);
            return true;
        } else if (obj instanceof Long) {
            writeByte(TC_LONG);
            writeLong((Long) obj);
            return true;
        } else if (obj instanceof Double) {
            writeByte(TC_DOUBLE);
            writeDouble((Double) obj);
            return true;
        } else if (obj instanceof Float) {
            writeByte(TC_FLOAT);
            writeFloat((Float) obj);
            return true;
        } else if (obj instanceof Boolean) {
            writeByte(TC_BOOLEAN);
            writeBoolean((Boolean) obj);
            return true;
        } else if (obj instanceof Short) {
            writeByte(TC_SHORT);
            writeShort(((Short) obj).shortValue());
            return true;
        } else if (obj instanceof Character) {
            writeByte(TC_CHAR);
            writeChar(((Character) obj).charValue());
            return true;
        } else if (obj instanceof Byte) {
            writeByte(TC_BYTE);
            writeByte(((Byte) obj).byteValue());
            return true;
        } else if (obj instanceof Enum) {
            writeByte(TC_ENUM);
            writeUTF(canonicalEnumClassName((Enum<?>) obj, className));
            writeUTF(((Enum<?>) obj).name());
            return true;
        } else if (obj instanceof boolean[]) {
            writeByte(TC_ARRAY);
            writeUTF("[Z");
            boolean[] arr = (boolean[]) obj;
            writeInt(arr.length);
            for (boolean v : arr) {
                writeBoolean(v);
            }
            return true;
        } else if (obj instanceof byte[]) {
            writeByte(TC_ARRAY);
            writeUTF("[B");
            byte[] arr = (byte[]) obj;
            writeInt(arr.length);
            write(arr);
            return true;
        } else if (obj instanceof int[]) {
            writeByte(TC_ARRAY);
            writeUTF("[I");
            int[] arr = (int[]) obj;
            writeInt(arr.length);
            for (int v : arr) {
                writeInt(v);
            }
            return true;
        } else if (obj instanceof long[]) {
            writeByte(TC_ARRAY);
            writeUTF("[J");
            long[] arr = (long[]) obj;
            writeInt(arr.length);
            for (long v : arr) {
                writeLong(v);
            }
            return true;
        } else if (obj instanceof double[]) {
            writeByte(TC_ARRAY);
            writeUTF("[D");
            double[] arr = (double[]) obj;
            writeInt(arr.length);
            for (double v : arr) {
                writeDouble(v);
            }
            return true;
        } else if (obj instanceof float[]) {
            writeByte(TC_ARRAY);
            writeUTF("[F");
            float[] arr = (float[]) obj;
            writeInt(arr.length);
            for (float v : arr) {
                writeFloat(v);
            }
            return true;
        } else if (obj instanceof short[]) {
            writeByte(TC_ARRAY);
            writeUTF("[S");
            short[] arr = (short[]) obj;
            writeInt(arr.length);
            for (short v : arr) {
                writeShort(v);
            }
            return true;
        } else if (obj instanceof char[]) {
            writeByte(TC_ARRAY);
            writeUTF("[C");
            char[] arr = (char[]) obj;
            writeInt(arr.length);
            for (char v : arr) {
                writeChar(v);
            }
            return true;
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
            return true;
        }

        // ── Container types: write header, push frame, return false ──

        if (obj instanceof Object[]) {
            Object[] arr = (Object[]) obj;
            writeByte(TC_ARRAY);
            writeUTF(className);
            writeInt(arr.length);
            WriteFrame f = pushFrame();
            f.type = WriteFrame.WRITE_ARRAY;
            f.array = arr;
            f.list = null;
            f.mapKeys = null;
            f.mapValues = null;
            f.fields = null;
            f.container = obj;
            f.nextIndex = 0;
            f.waitingForValue = false;
            return false;
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            writeByte(TC_LIST);
            writeUTF(className);
            writeInt(list.size());
            WriteFrame f = pushFrame();
            f.type = WriteFrame.WRITE_LIST;
            f.list = list;
            f.array = null;
            f.mapKeys = null;
            f.mapValues = null;
            f.fields = null;
            f.container = obj;
            f.nextIndex = 0;
            f.waitingForValue = false;
            return false;
        } else if (obj instanceof java.util.Set) {
            java.util.Set<?> set = (java.util.Set<?>) obj;
            writeByte(TC_SET);
            writeUTF(className);
            writeInt(set.size());
            WriteFrame f = pushFrame();
            f.type = WriteFrame.WRITE_SET;
            f.list = new ArrayList<>(set);
            f.array = null;
            f.mapKeys = null;
            f.mapValues = null;
            f.fields = null;
            f.container = obj;
            f.nextIndex = 0;
            f.waitingForValue = false;
            return false;
        } else if (obj instanceof Iterable && !(obj instanceof Iterator)) {
            // Non-List Iterable (e.g. FastArrayList) — snapshot as ArrayList
            List<Object> snapshot = new ArrayList<>();
            for (Object item : (Iterable<?>) obj) {
                snapshot.add(item);
            }
            writeByte(TC_LIST);
            writeUTF(obj.getClass().getName());
            writeInt(snapshot.size());
            WriteFrame f = pushFrame();
            f.type = WriteFrame.WRITE_LIST;
            f.list = snapshot;
            f.array = null;
            f.mapKeys = null;
            f.mapValues = null;
            f.fields = null;
            f.container = obj;
            f.nextIndex = 0;
            f.waitingForValue = false;
            return false;
        } else if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            int size = map.size();
            writeByte(TC_MAP);
            writeUTF(className);
            writeInt(size);
            List<Object> keys = new ArrayList<>(size);
            List<Object> values = new ArrayList<>(size);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                keys.add(e.getKey());
                values.add(e.getValue());
            }
            WriteFrame f = pushFrame();
            f.type = WriteFrame.WRITE_MAP;
            f.mapKeys = keys;
            f.mapValues = values;
            f.array = null;
            f.list = null;
            f.fields = null;
            f.container = obj;
            f.nextIndex = 0;
            f.waitingForValue = false;
            return false;
        } else {
            // Generic object: write class name + field count, push frame for fields
            List<Field> fields = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            Class<?> c = clazz;
            while (c != null && c != Object.class) {
                for (Field fld : c.getDeclaredFields()) {
                    int mods = fld.getModifiers();
                    if (!Modifier.isStatic(mods)
                            && (includeTransientFields || !Modifier.isTransient(mods))) {
                        fld.setAccessible(true);
                        fields.add(fld);
                        names.add(fld.getName());
                        types.add(typeDescriptor(fld.getType()));
                    }
                }
                c = c.getSuperclass();
            }

            writeByte(TC_OBJECT);
            writeUTF(className);

            if (!schemaManifest.containsKey(className)) {
                schemaManifest.put(className, new FieldSchema(
                    className,
                    names.toArray(new String[0]),
                    types.toArray(new String[0])
                ));
            }

            writeShort(fields.size());

            if (fields.isEmpty()) {
                return true; // no fields — object fully written
            }

            WriteFrame f = pushFrame();
            f.type = WriteFrame.WRITE_FIELDS;
            f.container = obj;
            f.fields = fields.toArray(new Field[0]);
            f.array = null;
            f.list = null;
            f.mapKeys = null;
            f.mapValues = null;
            f.nextIndex = 0;
            f.waitingForValue = false;
            return false;
        }
    }

    /**
     * Advance a frame to the next child value to write.
     * <p>
     * For WRITE_FIELDS: writes the next field name to the stream and returns
     * the field value. If a field cannot be read (IllegalAccessException),
     * writes TC_NULL inline and continues to the next field.
     * <p>
     * For WRITE_ARRAY / WRITE_LIST: returns the next element.
     * <p>
     * For WRITE_MAP: alternates between key and value for each entry.
     *
     * @return the next child object to write, or {@code null} if the frame is done
     */
    private Object advanceFrame(WriteFrame f) throws IOException {
        switch (f.type) {
            case WriteFrame.WRITE_FIELDS: {
                while (f.nextIndex < f.fields.length) {
                    Field field = f.fields[f.nextIndex];
                    writeUTF(field.getName());
                    f.nextIndex++;
                    try {
                        Object val = field.get(f.container);
                        return val != null ? val : NULL_CHILD;
                    } catch (IllegalAccessException e) {
                        if (listener != null) {
                            listener.onError("write",
                                    f.container != null ? f.container.getClass().getName() : "null", e);
                        }
                        writeByte(TC_NULL);
                        // Continue to next field
                    } catch (Throwable e) {
                        String cn = (f.container != null) ? f.container.getClass().getName() : "null";
                        String msg = cn + "." + field.getName()
                                + " (" + field.getType().getName() + "): " + e.getMessage();
                        throw new IOException("Serialization failed at " + msg, e);
                    }
                }
                return null; // all fields consumed
            }
            case WriteFrame.WRITE_ARRAY: {
                if (f.nextIndex >= f.array.length) {
                    return null;
                }
                Object val = f.array[f.nextIndex++];
                return val != null ? val : NULL_CHILD;
            }
            case WriteFrame.WRITE_LIST:
            case WriteFrame.WRITE_SET: {
                if (f.nextIndex >= f.list.size()) {
                    return null;
                }
                Object val = f.list.get(f.nextIndex++);
                return val != null ? val : NULL_CHILD;
            }
            case WriteFrame.WRITE_MAP: {
                if (f.nextIndex >= f.mapKeys.size()) {
                    return null;
                }
                if (!f.waitingForValue) {
                    f.waitingForValue = true;
                    Object key = f.mapKeys.get(f.nextIndex);
                    return key != null ? key : NULL_CHILD;
                } else {
                    f.waitingForValue = false;
                    Object val = f.mapValues.get(f.nextIndex);
                    f.nextIndex++;
                    return val != null ? val : NULL_CHILD;
                }
            }
            default:
                return null;
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
        return serializeWithSchema(obj, null);
    }

    /**
     * Serialize an object with a schema manifest prepended, using the
     * provided setup callback to register type handlers before writing.
     * Output: magic(2) + version(2) + TC_SCHEMA + schema data + object data
     */
    public static byte[] serializeWithSchema(Object obj,
            java.util.function.Consumer<TObjectOutputStream> setup) throws IOException {
        // Pass 1: serialize to collect schema (with type handlers)
        java.io.ByteArrayOutputStream probe = new java.io.ByteArrayOutputStream();
        TObjectOutputStream probeOut = new TObjectOutputStream(probe);
        if (setup != null) setup.accept(probeOut);
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
        bytePos += 4;
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
        bytePos += 8;
    }

    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public void writeBoolean(boolean v) throws IOException {
        out.write(v ? 1 : 0);
        bytePos++;
    }

    public void writeByte(int v) throws IOException {
        out.write(v & 0xFF);
        bytePos++;
    }

    public void writeShort(int v) throws IOException {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
        bytePos += 2;
    }

    public void writeChar(int v) throws IOException {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
        bytePos += 2;
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
        bytePos += bytes.length;
    }

    public void write(byte[] b) throws IOException {
        out.write(b);
        bytePos += b.length;
    }

    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        bytePos += len;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        bytePos++;
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
     * always written automatically by the iterative frame loop.
     */
    public void defaultWriteObject() throws IOException {
        // No-op: field traversal is handled by the iterative frame loop
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
    static final byte TC_ENUM        = 0x7E;   // Enum (name follows)
    static final byte TC_LONGSTRING  = 0x62;   // String > 64KB
    // ── Special-cased library types (no $meta.fields in TeaVM) ──
    static final byte TC_SET               = 0x60;   // java.util.Set
    static final byte TC_BITSET            = 0x61;   // java.util.BitSet
    static final byte TC_SCHEMA            = 0x67;   // schema manifest block
}
