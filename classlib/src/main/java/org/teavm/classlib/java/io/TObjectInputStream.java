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
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.TreeMap;

import org.teavm.classlib.io.SerializationDiagnostics;

/**
 * TeaVM shim for {@link java.io.ObjectInputStream}.
 * <p>
 * Deserializes objects from the binary format produced by
 * {@link TObjectOutputStream}. Uses an iterative work-stack (never the Java
 * call stack) so deeply-nested object graphs do not overflow.
 * <p>
 * Custom types beyond the standard Java serialization format can be handled
 * by registering {@link TypeHandler} implementations via
 * {@link #registerTypeHandler(TypeHandler)}. Monitoring and diagnostics are
 * available through {@link TSerializationListener} via
 * {@link #setListener(TSerializationListener)}.
 */
public class TObjectInputStream extends InputStream implements TObjectInput {

    private static final int HANDLE_BASE = 0x7e0000;

    private final InputStream in;
    /** Maps serialization handle offset to deserialized object (back-references). */
    private final ArrayList<Object> handleList = new ArrayList<>();

    private int totalObjects;
    private long startTimeMs;

    /** Chunked-deserialization support: max objects per chunk before yielding. */
    private int chunkLimit = Integer.MAX_VALUE;
    private int chunkCounter;
    /** Root object produced by readObject() once the work stack drains. */
    private Object rootObject;
    /** Current deserialization phase label, set by the orchestrator. */
    private String currentPhase = "drainRoot";
    public void setCurrentPhase(String phase) {
        this.currentPhase = phase;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    /** Number of times CHUNK_YIELD has been returned. */
    private int chunksEmitted;
    public int getChunksEmitted() {
        return chunksEmitted;
    }

    /**
     * Sentinel returned by {@link #readObject()} when the per-chunk object
     * budget is exhausted.  The caller should yield and call
     * {@code readObject()} again to resume -- the work stack retains all
     * intermediate state.
     */
    public static final Object CHUNK_YIELD = new Object();

    /** Running byte count, fed into diagnostic traces. */
    private int position;
    /** Initial stream size for drift detection. */
    private int initialStreamSize;
    /** Count of type handler invocations. */
    private int handlerCallCount;

    /** Cache Class.forName results to avoid repeated string lookups for the same class names. */
    private final java.util.HashMap<String, Class<?>> classForNameCache = new java.util.HashMap<>();

    /** Cache enum constants: className + "." + constName -> enum value. */
    private final java.util.HashMap<String, Object> enumConstantCache = new java.util.HashMap<>();

    /** Ring buffer of the last N type-code reads (diagnostic on I/O errors). */
    private static final int TRACE_SIZE = 32;
    private final int[] tracePositions = new int[TRACE_SIZE];
    private final int[] traceTypeCodes = new int[TRACE_SIZE];
    private final int[] traceFrameType = new int[TRACE_SIZE];
    private int traceIndex;
    private int traceCount;

    // ═════════════════════════════════════════════════════════════════════════
    //  Type handler registry, object allocator, and listener
    // ═════════════════════════════════════════════════════════════════════════

    /** Registered type handlers for custom type codes. */
    private final List<TypeHandler> typeHandlers = new ArrayList<>();

    /**
     * Register a type handler for a custom type code.  When a type code
     * is read that does not match any built-in type, each registered handler
     * is checked in registration order.
     */
    public void registerTypeHandler(TypeHandler handler) {
        typeHandlers.add(handler);
    }

    /**
     * Strategy interface for allocating objects during deserialization.
     * Targets with limited reflection support (e.g. WASM GC) can install
     * a custom allocator that uses runtime-specific allocation mechanisms
     * (raw ClassInfo allocation, multi-strategy fallbacks, etc.).
     */
    public interface ObjectAllocator {
        /**
         * Allocate a new instance of the given class.
         * Returns the allocated object, or null if allocation fails.
         */
        Object allocate(Class<?> clazz) throws Exception;
    }

    /** Custom object allocator; null uses the default reflection-based approach. */
    private ObjectAllocator objectAllocator;

    /**
     * Install a custom object allocator.  When set, {@link #allocateInstance}
     * delegates to this allocator first; if it returns null, the default
     * reflection-based allocation is attempted as a fallback.
     */
    public void setObjectAllocator(ObjectAllocator allocator) {
        this.objectAllocator = allocator;
    }

    /**
     * Strategy interface for setting fields on deserialized objects.
     * Targets where {@code Field.set()} has limitations (e.g. final fields
     * on WASM GC) can install a custom writer with richer coercion logic.
     */
    public interface FieldWriter {
        /**
         * Write {@code value} into field {@code fieldName} of {@code obj}.
         * The {@code schemaTypeDescriptor} may provide type information when
         * runtime reflection is incomplete.
         */
        void setField(Object obj, String fieldName, Object value,
                       String schemaTypeDescriptor) throws Exception;
    }

    /** Custom field writer; null uses the default reflection-based approach. */
    private FieldWriter fieldWriter;

    /**
     * Install a custom field writer.  When set, {@link #setFieldSafely}
     * delegates to this writer; if it throws, the default approach is
     * used as a fallback.
     */
    public void setFieldWriter(FieldWriter writer) {
        this.fieldWriter = writer;
    }

    /**
     * Strategy interface for allocating typed object arrays and setting
     * elements safely.  Targets with strict type checking (e.g. WASM GC)
     * can install a custom implementation that checks component-type
     * compatibility before writing to avoid fatal ref.cast traps.
     */
    public interface ArrayAllocator {
        /**
         * Allocate a typed object array for the given JVM descriptor.
         * Returns null to fall back to the default allocation.
         */
        Object[] allocate(String className, int length);

        /**
         * Store a value at the given index, performing any needed
         * type-compatibility checks.
         */
        void setSafe(Object[] arr, int index, Object value);
    }

    /** Custom array allocator; null uses the default reflection-based approach. */
    private ArrayAllocator arrayAllocator;

    /**
     * Install a custom array allocator.  When set, {@link #allocateObjectArray}
     * and {@link #arraySetSafe} delegate to it.
     */
    public void setArrayAllocator(ArrayAllocator allocator) {
        this.arrayAllocator = allocator;
    }

    /**
     * Callback invoked on each object after its fields have been deserialized.
     * Useful for per-object post-processing (e.g. fixing sentinel objects,
     * sweeping undefined values to null) without requiring a separate
     * full-graph walk after deserialization completes.
     */
    public interface PostDeserializeCallback {
        void onObjectDeserialized(Object obj);
    }

    private PostDeserializeCallback postDeserializeCallback;

    public void setPostDeserializeCallback(PostDeserializeCallback cb) {
        this.postDeserializeCallback = cb;
    }

    /** Listener for monitoring and diagnostics. */
    private TSerializationListener listener;

    /**
     * Set a listener to receive callbacks for read events, schema drift,
     * and errors during deserialization.
     */
    public void setListener(TSerializationListener listener) {
        this.listener = listener;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Constructor
    // ═════════════════════════════════════════════════════════════════════════

    public TObjectInputStream(InputStream in) throws IOException {
        this.in = in;
        try { initialStreamSize = in.available(); } catch (Throwable t) { initialStreamSize = -1; }
        int magic = readShort() & 0xFFFF;
        int version = readShort() & 0xFFFF;
        if (magic != TObjectOutputStream.STREAM_MAGIC) {
            throw new IOException("Invalid stream magic: 0x"
                    + Integer.toHexString(magic & 0xFFFF));
        }
        if (version != TObjectOutputStream.STREAM_VERSION) {
            throw new IOException("Unsupported stream version: " + version
                + " (expected " + TObjectOutputStream.STREAM_VERSION + ")");
        }
        startTimeMs = System.currentTimeMillis();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Chunked deserialization API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Set the maximum number of objects to drain per {@link #readObject()} call.
     * When the limit is hit, {@code readObject()} returns {@link #CHUNK_YIELD} to
     * signal the caller should yield and call again.
     */
    public void setChunkLimit(int n) {
        this.chunkLimit = n;
    }

    /** Returns {@code true} when the work stack is empty (deserialization complete). */
    public boolean isDone() {
        return frameTop == 0;
    }

    /**
     * Returns the root object once {@link #readObject()} has completed a full
     * drain cycle (i.e. {@code frameTop == 0}). Only valid after a call to
     * {@code readObject()} that returned non-null.
     */
    public Object getRoot() {
        return rootObject;
    }

    /** Diagnostic getters -- read-only, no side effects. */
    public int getTotalObjects() {
        return totalObjects;
    }

    public int getChunkCounter() {
        return chunkCounter;
    }

    public int getFrameTop() {
        return frameTop;
    }

    public int getStreamPosition() {
        return position;
    }

    public int getHandleListSize() {
        return handleList.size();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Work stack -- iterative deserialization
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * One container waiting for its children. Each time a child value arrives,
     * it is delivered to the top frame; when the frame's remaining counter
     * hits zero, the container itself becomes the value delivered upward.
     */
    private static final class Frame {
        static final int OBJECT_FIELDS = 1;
        static final int OBJECT_ARRAY  = 2;
        static final int LIST_ELEMENTS = 3;
        static final int MAP_ENTRIES   = 4;
        static final int SET_ELEMENTS  = 5;
        static final int DRAIN         = 8;

        int type;
        Object container;
        int remaining;

        // OBJECT_FIELDS / LIST_ELEMENTS:
        String className;
        String nextFieldName;
        ClassSchema schema;

        // MAP_ENTRIES:
        Object pendingKey;
        boolean waitingForValue;

        // OBJECT_ARRAY:
        int nextIndex;

        // DRAIN:
        int drainCount;
    }

    private Frame[] frameStack = new Frame[256];
    private int frameTop;

    private Frame pushFrame() {
        if (frameTop >= frameStack.length) {
            Frame[] bigger = new Frame[frameStack.length * 2];
            System.arraycopy(frameStack, 0, bigger, 0, frameStack.length);
            frameStack = bigger;
        }
        Frame f = frameStack[frameTop];
        if (f == null) {
            f = new Frame();
            frameStack[frameTop] = f;
        }
        frameTop++;
        return f;
    }

    /** Sentinel returned from {@link #readLeafOrStartContainer()} when a frame was pushed. */
    private static final Object NEEDS_CHILDREN = new Object();

    // ═════════════════════════════════════════════════════════════════════════
    //  readObject -- pure iteration, zero recursion
    // ═════════════════════════════════════════════════════════════════════════

    public final Object readObject() throws IOException, ClassNotFoundException {
        if (SerializationDiagnostics.isDebug()) {
            System.out.println("[TIS] readObject() entered, stream available=" + in.available());
        }
        while (true) {
            if (frameTop > 0 && chunkCounter > 0 && chunkCounter >= chunkLimit) {
                chunkCounter = 0;
                chunksEmitted++;
                return CHUNK_YIELD;
            }
            Object value = readLeafOrStartContainer();
            while (value != NEEDS_CHILDREN) {
                if (frameTop == 0) {
                    rootObject = value;
                    chunkCounter = 0;
                    return value;
                }
                try {
                    value = deliverToFrame(frameStack[frameTop - 1], value);
                } catch (IOException e) {
                    // Pop the failing frame so subsequent reads can recover
                    if (frameTop > 0) {
                        frameTop--;
                    }
                    throw e;
                }
            }
        }
    }

    /**
     * Read a complete object without delivering to parent frames.
     * Type handlers MUST use this instead of {@link #readObject()} for
     * nested reads, because {@code readObject()} delivers leaf values
     * to the top-of-stack frame — which belongs to the handler's parent
     * container, not the handler itself.
     */
    public Object readObjectUnframed() throws IOException, ClassNotFoundException {
        int floor = frameTop;
        while (true) {
            Object value = readLeafOrStartContainer();
            while (value != NEEDS_CHILDREN) {
                if (frameTop <= floor) {
                    return value;
                }
                value = deliverToFrame(frameStack[frameTop - 1], value);
            }
        }
    }

    /** Number of drift log lines emitted (capped to avoid flood). */
    private int driftLogs;
    private static final int MAX_DRIFT_LOGS = 20;

    private Object readLeafOrStartContainer() throws IOException, ClassNotFoundException {
        // Drift detection: compare tracked position to actual ByteArrayInputStream position
        if (initialStreamSize > 0 && driftLogs < MAX_DRIFT_LOGS
                && org.teavm.classlib.io.SerializationDiagnostics.isDebug()) {
            try {
                int actualConsumed = initialStreamSize - in.available();
                int drift = actualConsumed - position;
                if (drift != 0) {
                    System.out.println("[TIS-DRIFT] trackedPos=" + position
                        + " actualConsumed=" + actualConsumed + " drift=" + drift);
                    driftLogs++;
                }
            } catch (Throwable ignored) {}
        }
        int tc = readByte();
        if (SerializationDiagnostics.isDebug() && totalObjects < 3) {
            System.out.println("[TIS] readLeafOrStartContainer tc=0x" + Integer.toHexString(tc) + " total=" + totalObjects + " avail=" + in.available());
        }
        recordTrace(tc);
        chunkCounter++;  // Count every operation toward the chunk budget
        switch (tc) {
            case TC_NULL:
                return null;
            case TC_REFERENCE:
                return readReference();
            case TC_STRING: {
                String v = readUTF();
                register(v);
                return v;
            }
            case TC_LONGSTRING: {
                int len = readInt();
                byte[] bytes = new byte[len];
                readFully(bytes);
                String v = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                register(v);
                return v;
            }
            case TC_INTEGER: {
                Integer v = readInt();
                register(v);
                return v;
            }
            case TC_LONG: {
                Long v = readLong();
                register(v);
                return v;
            }
            case TC_DOUBLE: {
                Double v = readDouble();
                register(v);
                return v;
            }
            case TC_FLOAT: {
                Float v = readFloat();
                register(v);
                return v;
            }
            case TC_BOOLEAN: {
                Boolean v = readByte() != 0;
                register(v);
                return v;
            }
            case TC_SHORT: {
                Short v = (short) readShort();
                register(v);
                return v;
            }
            case TC_CHAR: {
                Character v = (char) readChar();
                register(v);
                return v;
            }
            case TC_BYTE: {
                Byte v = (byte) readByte();
                register(v);
                return v;
            }
            case TC_ENUM:
                return readEnum();
            case TC_ARRAY:
                return startArray();
            case TC_LIST:
                return startList();
            case TC_MAP:
                return startMap();
            case TC_SET:
                return startSet();
            case TC_OBJECT:
                return startCustomObject();
            case TC_BITSET:
                return readBitSet();
            case TC_SCHEMA:
                readSchemaInline();
                return readLeafOrStartContainer();
            default:
                break;
        }

        // Try registered type handlers for custom type codes
        for (TypeHandler handler : typeHandlers) {
            if (handler.typeCode() == tc) {
                handlerCallCount++;
                Object result = handler.read(tc, this);
                register(result);
                return result;
            }
        }

        throw new IOException(buildTraceMessage("BADTC:0x"
            + Integer.toHexString(tc & 0xFF) + " hc=" + handlerCallCount));
    }

    private Object deliverToFrame(Frame f, Object value) throws IOException {
        switch (f.type) {
            case Frame.OBJECT_FIELDS: {
                String typeDesc = null;
                if (f.schema != null) {
                    // Use position-based lookup to handle shadowed fields
                    // (e.g. Seq.moves + Moves.moves both named "moves").
                    int fieldIdx = f.schema.fieldNames.length - f.remaining;
                    if (fieldIdx >= 0 && fieldIdx < f.schema.fieldTypeDescriptors.length) {
                        typeDesc = f.schema.fieldTypeDescriptors[fieldIdx];
                    }
                }
                try {
                    setFieldSafely(f.container, f.nextFieldName, value, typeDesc);
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onError("setField", f.className, e);
                    }
                }
                f.remaining--;
                if (f.remaining > 0) {
                    f.nextFieldName = readUTF();
                    if (f.schema != null) {
                        int fieldIdx = f.schema.fieldNames.length - f.remaining;
                        if (fieldIdx >= 0 && fieldIdx < f.schema.fieldNames.length
                                && !f.nextFieldName.equals(f.schema.fieldNames[fieldIdx])) {
                            if (listener != null) {
                                listener.onSchemaDrift(f.className,
                                    "field[" + fieldIdx + "]: expected "
                                    + f.schema.fieldNames[fieldIdx]
                                    + ", got " + f.nextFieldName);
                            }
                        }
                    }
                    return NEEDS_CHILDREN;
                }
                frameTop--;
                if (postDeserializeCallback != null) {
                    postDeserializeCallback.onObjectDeserialized(f.container);
                }
                return resolveObject(f.container);
            }

            case Frame.OBJECT_ARRAY: {
                Object[] arr = (Object[]) f.container;
                arraySetSafe(arr, f.nextIndex++, value);
                f.remaining--;
                if (f.remaining > 0) {
                    return NEEDS_CHILDREN;
                }
                frameTop--;
                return arr;
            }

            case Frame.LIST_ELEMENTS: {
                // container is always ArrayList (created in startList)
                @SuppressWarnings("unchecked")
                ArrayList<Object> list = (ArrayList<Object>) f.container;
                try {
                    list.add(value);
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onError("listAdd",
                            value != null ? value.getClass().getName() : "null", e);
                    }
                }
                f.remaining--;
                if (f.remaining > 0) {
                    return NEEDS_CHILDREN;
                }
                frameTop--;
                Object reconstructed = reconstructIfNonArrayList(f.className, list);
                if (reconstructed != list) {
                    patchHandle(list, reconstructed);
                }
                return reconstructed;
            }

            case Frame.MAP_ENTRIES: {
                // container is always HashMap (created in startMap)
                @SuppressWarnings("unchecked")
                HashMap<Object, Object> map = (HashMap<Object, Object>) f.container;
                if (!f.waitingForValue) {
                    f.pendingKey = value;
                    f.waitingForValue = true;
                    return NEEDS_CHILDREN;
                }
                try {
                    map.put(f.pendingKey, value);
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onError("mapPut",
                            value != null ? value.getClass().getName() : "null", e);
                    }
                }
                f.waitingForValue = false;
                f.remaining--;
                if (f.remaining > 0) {
                    return NEEDS_CHILDREN;
                }
                frameTop--;
                Object reconstructed = reconstructIfNonHashMap(f.className, map);
                if (reconstructed != map) {
                    patchHandle(map, reconstructed);
                }
                return reconstructed;
            }

            case Frame.SET_ELEMENTS: {
                // container is always HashSet (created in startSet)
                @SuppressWarnings("unchecked")
                java.util.HashSet<Object> set = (java.util.HashSet<Object>) f.container;
                set.add(value);
                f.remaining--;
                if (f.remaining > 0) {
                    return NEEDS_CHILDREN;
                }
                frameTop--;
                Object reconstructed = reconstructIfNonHashSet(f.className, set);
                if (reconstructed != set) {
                    patchHandle(set, reconstructed);
                }
                return reconstructed;
            }

            case Frame.DRAIN: {
                if (listener != null) {
                    listener.onReadObject("drain", frameTop);
                }
                f.drainCount--;
                if (f.drainCount > 0) {
                    // Must consume the next field name from the stream to keep
                    // the cursor aligned.  The wire format alternates
                    // fieldName(UTF) + fieldValue, so after draining one
                    // value we need to read the next name before the next
                    // readObject() call delivers the subsequent value.
                    readUTF();
                    return NEEDS_CHILDREN;
                }
                frameTop--;
                return null;
            }

            default:
                throw new IOException("Unknown frame type: " + f.type);
        }
    }

    // ── Diagnostic trace ─────────────────────────────────────────────────────

    private void recordTrace(int typeCode) {
        int idx = traceIndex % TRACE_SIZE;
        tracePositions[idx] = position - 1;
        traceTypeCodes[idx] = typeCode;
        traceFrameType[idx] = (frameTop > 0) ? frameStack[frameTop - 1].type : -1;
        traceIndex++;
        if (traceCount < TRACE_SIZE) {
            traceCount++;
        }
    }

    private String buildTraceMessage(String error) {
        StringBuilder sb = new StringBuilder(error);
        sb.append(" at position ").append(position - 1);
        sb.append("\n--- last ").append(traceCount).append(" type code reads ---\n");
        int start = Math.max(0, traceIndex - TRACE_SIZE);
        for (int i = start; i < traceIndex; i++) {
            int idx = i % TRACE_SIZE;
            String hex = (traceTypeCodes[idx] >= 0)
                ? "0x" + Integer.toHexString(traceTypeCodes[idx])
                : "EOF(-1)";
            sb.append("  pos=").append(tracePositions[idx])
              .append(" tc=").append(hex)
              .append(" ft=").append(traceFrameType[idx])
              .append('\n');
        }
        return sb.toString();
    }

    // ── Reference tracking ───────────────────────────────────────────────────

    private void register(Object obj) {
        handleList.add(obj);
    }

    private void patchHandle(Object oldObj, Object newObj) {
        for (int i = handleList.size() - 1; i >= 0; i--) {
            if (handleList.get(i) == oldObj) {
                handleList.set(i, newObj);
            }
        }
    }

    private Object resolveObject(Object obj) {
        try {
            java.lang.reflect.Method m = obj.getClass().getDeclaredMethod("readResolve");
            m.setAccessible(true);
            Object resolved = m.invoke(obj);
            if (resolved != null && resolved != obj) {
                patchHandle(obj, resolved);
                return resolved;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {
        }
        return obj;
    }

    private Object readReference() throws IOException {
        int handle = readInt();
        int index = handle - HANDLE_BASE;
        if (index < 0 || index >= handleList.size()) {
            throw new IOException(buildTraceMessage("Invalid object handle: " + handle
                + " (index=" + index + ", registered=" + handleList.size() + ")"));
        }
        return handleList.get(index);
    }

    // ── Container starters ───────────────────────────────────────────────────

    private Object startArray() throws IOException, ClassNotFoundException {
        String className = readUTF();
        int length = readInt();
        if (length < 0 || length > 10_000_000) {
            throw new IOException("Array length out of range: " + length);
        }

        // Primitive arrays -- read inline, no frame needed.
        switch (className) {
            case "[B": {
                byte[] a = new byte[length];
                register(a);
                readFully(a);
                return a;
            }
            case "[I": {
                int[] a = new int[length];
                register(a);
                for (int i = 0; i < length; i++) {
                    a[i] = readInt();
                }
                return a;
            }
            case "[J": {
                long[] a = new long[length];
                register(a);
                for (int i = 0; i < length; i++) {
                    a[i] = readLong();
                }
                return a;
            }
            case "[Z": {
                boolean[] a = new boolean[length];
                register(a);
                for (int i = 0; i < length; i++) {
                    a[i] = readByte() != 0;
                }
                return a;
            }
            case "[D": {
                double[] a = new double[length];
                register(a);
                for (int i = 0; i < length; i++) {
                    a[i] = readDouble();
                }
                return a;
            }
            case "[F": {
                float[] a = new float[length];
                register(a);
                for (int i = 0; i < length; i++) {
                    a[i] = readFloat();
                }
                return a;
            }
            case "[S": {
                short[] a = new short[length];
                register(a);
                for (int i = 0; i < length; i++) {
                    a[i] = (short) readShort();
                }
                return a;
            }
            case "[C": {
                char[] a = new char[length];
                register(a);
                for (int i = 0; i < length; i++) {
                    a[i] = (char) readChar();
                }
                return a;
            }
            default:
                break;
        }

        // Object array -- create a typed array so the result is assignable to
        // the field's declared array type.
        Object[] arr = allocateObjectArray(className, length);
        register(arr);
        if (length == 0) {
            return arr;
        }
        Frame f = pushFrame();
        f.type = Frame.OBJECT_ARRAY;
        f.container = arr;
        f.remaining = length;
        f.nextIndex = 0;
        return NEEDS_CHILDREN;
    }

    private Object startList() throws IOException {
        String listClassName = readUTF();
        int size = readInt();
        if (size < 0 || size > 10_000_000) {
            throw new IOException("List size out of range: " + size);
        }
        ArrayList<Object> list = new ArrayList<>(size);
        register(list);
        if (size == 0) {
            Object reconstructed = reconstructIfNonArrayList(listClassName, list);
            if (reconstructed != list) {
                patchHandle(list, reconstructed);
            }
            return reconstructed;
        }
        Frame f = pushFrame();
        f.type = Frame.LIST_ELEMENTS;
        f.container = list;
        f.remaining = size;
        f.className = listClassName;
        return NEEDS_CHILDREN;
    }

    /**
     * After reading all elements into an ArrayList, try to reconstruct the
     * declared iterable type (e.g. FastArrayList) via reflection. Falls back
     * to the ArrayList if construction fails.
     */
    private static Object reconstructIfNonArrayList(String className, ArrayList<Object> elements) {
        if ("java.util.ArrayList".equals(className) || className.isEmpty()) {
            return elements;
        }
        if ("java.util.Collections$UnmodifiableList".equals(className)
                || "java.util.Collections$UnmodifiableRandomAccessList".equals(className)
                || className.startsWith("java.util.Collections$")) {
            // Unmodifiable wrappers are package-private inner classes — can't reconstruct.
            // Return the ArrayList as-is; callers will see a mutable list but data is preserved.
            return elements;
        }
        try {
            Class<?> declaredType = Class.forName(className);
            Object container = declaredType.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method addMethod = declaredType.getMethod("add", Object.class);
            for (Object item : elements) {
                addMethod.invoke(container, item);
            }
            return container;
        } catch (Throwable t) {
            return elements;
        }
    }

    private static Object reconstructIfNonHashMap(String className, HashMap<Object, Object> elements) {
        if ("java.util.HashMap".equals(className) || className.isEmpty()) {
            return elements;
        }
        if ("java.util.EnumMap".equals(className)) {
            try {
                if (!elements.isEmpty()) {
                    Object firstKey = null;
                    for (Object k : elements.keySet()) { firstKey = k; break; }
                    if (firstKey != null) {
                        @SuppressWarnings("unchecked")
                        EnumMap rawMap = new EnumMap(firstKey.getClass());
                        rawMap.putAll(elements);
                        return rawMap;
                    }
                }
                // Empty EnumMap — use the EnumMap(Map) constructor with an empty HashMap
                // to avoid needing the enum Class for the no-arg path.
                // Fall through to HashMap fallback for empty EnumMaps.
            } catch (Throwable t) {
                // EnumMap(Class) may fail if TGenericEnumSet.getConstants() returns null
                // on backends without full enum reflection. Fall through to HashMap.
            }
            return elements;
        }
        if ("java.util.LinkedHashMap".equals(className)) {
            LinkedHashMap<Object, Object> lhm = new LinkedHashMap<>(elements.size() * 2);
            lhm.putAll(elements);
            return lhm;
        }
        if ("java.util.TreeMap".equals(className)) {
            TreeMap<Object, Object> tm = new TreeMap<>();
            try {
                tm.putAll(elements);
                return tm;
            } catch (Throwable t) {
                return elements;
            }
        }
        if (className.startsWith("java.util.Collections$")) {
            return elements;
        }
        try {
            Class<?> declaredType = Class.forName(className);
            Object container = declaredType.getDeclaredConstructor().newInstance();
            if (container instanceof Map) {
                ((Map) container).putAll(elements);
            }
            return container;
        } catch (Throwable t) {
            return elements;
        }
    }

    private Object startMap() throws IOException {
        String mapClassName = readUTF();
        int size = readInt();
        if (size < 0 || size > 10_000_000) {
            throw new IOException("Map size out of range: " + size);
        }
        HashMap<Object, Object> map = new HashMap<>(size * 2);
        register(map);
        if (size == 0) {
            Object reconstructed = reconstructIfNonHashMap(mapClassName, map);
            if (reconstructed != map) {
                patchHandle(map, reconstructed);
            }
            return reconstructed;
        }
        Frame f = pushFrame();
        f.type = Frame.MAP_ENTRIES;
        f.container = map;
        f.remaining = size;
        f.className = mapClassName;
        f.waitingForValue = false;
        return NEEDS_CHILDREN;
    }

    private Object startSet() throws IOException {
        String setClassName = readUTF();
        int size = readInt();
        if (size < 0 || size > 10_000_000) {
            throw new IOException("Set size out of range: " + size);
        }
        java.util.HashSet<Object> set = new HashSet<>(size * 2);
        register(set);
        if (size == 0) {
            Object reconstructed = reconstructIfNonHashSet(setClassName, set);
            if (reconstructed != set) {
                patchHandle(set, reconstructed);
            }
            return reconstructed;
        }
        Frame f = pushFrame();
        f.type = Frame.SET_ELEMENTS;
        f.container = set;
        f.remaining = size;
        f.className = setClassName;
        return NEEDS_CHILDREN;
    }

    private static Object reconstructIfNonHashSet(String className, HashSet<Object> elements) {
        if ("java.util.HashSet".equals(className) || className.isEmpty()) {
            return elements;
        }
        if ("java.util.LinkedHashSet".equals(className)) {
            java.util.LinkedHashSet<Object> lhs = new java.util.LinkedHashSet<>(elements.size() * 2);
            lhs.addAll(elements);
            return lhs;
        }
        if ("java.util.TreeSet".equals(className)) {
            java.util.TreeSet<Object> ts = new java.util.TreeSet<>();
            // TreeSet requires Comparable elements — fall back to HashSet if not
            try {
                ts.addAll(elements);
                return ts;
            } catch (Throwable t) {
                return elements;
            }
        }
        if (className.startsWith("java.util.Collections$")) {
            // Unmodifiable wrappers — can't reconstruct, return as HashSet
            return elements;
        }
        try {
            Class<?> declaredType = Class.forName(className);
            Object container = declaredType.getDeclaredConstructor().newInstance();
            if (container instanceof java.util.Set) {
                ((java.util.Set<Object>) container).addAll(elements);
            }
            return container;
        } catch (Throwable t) {
            return elements;
        }
    }

    private Object startCustomObject() throws IOException, ClassNotFoundException {
        String className = readUTF();
        int fieldCount = readShort();

        // ── Singleton guard ────────────────────────────────────────────────
        // Classes like grammar.Grammar use a static singleton and must not
        // be re-instantiated.  Their constructors have side-effects that
        // corrupt global state.  Drain the field bytes and register null.
        if (isSingletonClassName(className)) {
            totalObjects++;
            register(null);
            if (fieldCount > 0) {
                readUTF(); // consume first field name
                Frame f = pushFrame();
                f.type = Frame.DRAIN;
                f.drainCount = fieldCount;
                return NEEDS_CHILDREN;
            }
            return null;
        }

        ClassSchema schema = schemaManifest.get(className);

        // ── Stage 1: Class.forName (cached) ──────────────────────────────
        if (SerializationDiagnostics.isDebug() && (totalObjects % 500) == 0) {
            System.out.println("[TIS] Allocating object #" + totalObjects + " class=" + className);
        }
        Class<?> clazz = classForNameCache.get(className);
        if (clazz == null && !classForNameCache.containsKey(className)) {
            if (SerializationDiagnostics.isDebug() && totalObjects < 5) System.out.println("[TIS] Class.forName(" + className + ")...");
            try {
                clazz = Class.forName(className);
                if (SerializationDiagnostics.isDebug() && totalObjects < 5) System.out.println("[TIS] Class.forName(" + className + ") done");
            } catch (Throwable e) {
                if (SerializationDiagnostics.isDebug() && totalObjects < 5) System.out.println("[TIS] Class.forName(" + className + ") FAILED: " + e.getMessage());
                if (listener != null) {
                    String reason = e.getClass().getName() + ": " + e.getMessage();
                    listener.onError("Class.forName", className,
                        new RuntimeException(reason));
                }
            }
            classForNameCache.put(className, clazz);
        }

        // ── Stage 2: Allocate ─────────────────────────────────────────────
        Object obj = null;
        if (clazz != null) {
            if (SerializationDiagnostics.isDebug() && totalObjects < 5) System.out.println("[TIS] allocateInstance(" + className + ")...");
            try {
                obj = allocateInstance(clazz);
                if (SerializationDiagnostics.isDebug() && totalObjects < 5) System.out.println("[TIS] allocateInstance(" + className + ") done, obj=" + (obj != null));
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError("allocate", className, e);
                }
            } catch (Throwable t) {
                if (listener != null) {
                    listener.onError("allocate", className,
                        new RuntimeException(t.getClass().getName() + ": " + t.getMessage()));
                }
            }
        }

        // ── Stage 3: Structural-failure policy ────────────────────────────
        // When we cannot materialise an object whose fields the writer
        // committed, DRAIN the field bytes + register(null) to keep the
        // stream valid.
        totalObjects++;
        if (obj == null) {
            register(null);
            if (fieldCount > 0) {
                // The wire format writes N fieldName(UTF)+value pairs.
                // readUTF() for the first field name has NOT been consumed
                // yet (unlike the obj != null path which reads it at line
                // ~794).  Read it here so the DRAIN handler's subsequent
                // readUTF() calls stay aligned.
                readUTF(); // consume firstName
                Frame f = pushFrame();
                f.type = Frame.DRAIN;
                f.drainCount = fieldCount;
                return NEEDS_CHILDREN;
            }
            return null;
        }

        register(obj);

        if (fieldCount == 0) {
            return resolveObject(obj);
        }

        String firstName = readUTF();
        Frame f = pushFrame();
        f.type = Frame.OBJECT_FIELDS;
        f.container = obj;
        f.remaining = fieldCount;
        f.className = className;
        f.schema = schema;
        f.nextFieldName = firstName;

        if (schema != null) {
            if (schema.fieldNames.length != fieldCount) {
                if (listener != null) {
                    listener.onSchemaDrift(className,
                        "fieldCount: expected " + schema.fieldNames.length
                        + ", got " + fieldCount);
                }
            }
            if (schema.fieldNames.length > 0
                    && !firstName.equals(schema.fieldNames[0])) {
                if (listener != null) {
                    listener.onSchemaDrift(className,
                        "field[0]: expected " + schema.fieldNames[0]
                        + ", got " + firstName);
                }
            }
        }

        if (listener != null) {
            listener.onReadObject(className, frameTop);
        }

        return NEEDS_CHILDREN;
    }

    // ── Enum reader ──────────────────────────────────────────────────────────

    private Object readEnum() throws IOException {
        String className = readUTF();
        String constName = readUTF();

        String cacheKey = className + "#" + constName;
        Object cached = enumConstantCache.get(cacheKey);
        if (cached != null) {
            register(cached);
            return cached;
        }

        Class<?> enumClass = null;
        try {
            enumClass = Class.forName(className);
        } catch (Throwable ignored) {
        }

        if (enumClass == null) {
            String name = className;
            while (enumClass == null) {
                int dollar = name.lastIndexOf('$');
                if (dollar < 0) {
                    break;
                }
                String suffix = name.substring(dollar + 1);
                boolean allDigits = !suffix.isEmpty();
                for (int i = 0; i < suffix.length(); i++) {
                    if (!Character.isDigit(suffix.charAt(i))) {
                        allDigits = false;
                        break;
                    }
                }
                if (!allDigits) {
                    break;
                }
                name = name.substring(0, dollar);
                try {
                    enumClass = Class.forName(name);
                } catch (Throwable ignored) {
                }
            }
        }

        if (enumClass != null && enumClass.isEnum()) {
            try {
                java.lang.reflect.Method valueOf = enumClass.getMethod("valueOf", Class.class, String.class);
                Object v = valueOf.invoke(null, enumClass, constName);
                enumConstantCache.put(cacheKey, v);
                register(v);
                return v;
            } catch (Throwable t) {
                try {
                    java.lang.reflect.Method valueOf = enumClass.getMethod("valueOf", String.class);
                    Object v = valueOf.invoke(null, constName);
                    enumConstantCache.put(cacheKey, v);
                    register(v);
                    return v;
                } catch (Throwable t2) {
                }
            }
        }
        register(null);
        return null;
    }

    // ── Special-cased library types ──────────────────────────────────────────

    private Object readBitSet() throws IOException {
        int card = readInt();
        if (card < 0 || card > 10_000_000) {
            throw new IOException("BitSet cardinality out of range: " + card);
        }
        BitSet bs = new BitSet();
        register(bs);
        for (int i = 0; i < card; i++) {
            bs.set(readInt());
        }
        return bs;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Reflection helpers (standard java.lang.reflect)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Allocate an instance of the given class. Tries the declared no-arg
     * constructor first, then falls back to {@code Class.newInstance()}.
     * Projects targeting TeaVM Wasm GC that need unsafe allocation can
     * provide their own workarounds externally via TypeHandler or
     * {@link ObjectAllocator}.
     */
    private Object allocateInstance(Class<?> clazz) throws Exception {
        if (clazz == null) {
            return null;
        }
        // Abstract classes cannot be instantiated.
        try {
            if ((clazz.getModifiers() & java.lang.reflect.Modifier.ABSTRACT) != 0) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        // Try custom allocator first (e.g. WASM GC raw allocation)
        if (objectAllocator != null) {
            Object obj = objectAllocator.allocate(clazz);
            if (obj != null) {
                return obj;
            }
        }
        try {
            java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Set a field value using standard Java reflection. If a custom
     * {@link FieldWriter} is installed, delegates to it first. If a schema
     * type descriptor is available, it is used for coercion; otherwise the
     * field's declared type is used.
     */
    private void setFieldSafely(Object obj, String fieldName, Object value,
                                String schemaTypeDescriptor) throws Exception {
        // Try custom field writer first (e.g. WASM GC final-field handling)
        if (fieldWriter != null) {
            try {
                fieldWriter.setField(obj, fieldName, value, schemaTypeDescriptor);
                return;
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError("setField", obj.getClass().getName(), e);
                }
                return;
            }
        }
        Field f = findField(obj.getClass(), fieldName);
        if (f == null) {
            return;
        }
        f.setAccessible(true);

        Class<?> targetType;
        if (schemaTypeDescriptor != null) {
            Class<?> schemaType = classFromDescriptor(schemaTypeDescriptor);
            targetType = (schemaType != null) ? schemaType : f.getType();
        } else {
            targetType = f.getType();
        }

        Object coerced = coerce(targetType, value);
        f.set(obj, coerced);
    }

    private final java.util.Map<String, Field> fieldLookupCache = new java.util.HashMap<>();

    /** Look up an instance field by name, searching the class hierarchy. */
    private Field findField(Class<?> cls, String fieldName) {
        String key = cls.getName() + "." + fieldName;
        Field cached = fieldLookupCache.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getName().equals(fieldName)
                            && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        fieldLookupCache.put(key, f);
                        return f;
                    }
                }
            } catch (Throwable ignored) {
                // getDeclaredFields may fail on some TeaVM targets
            }
            c = c.getSuperclass();
        }
        fieldLookupCache.put(key, null);
        return null;
    }

    /**
     * Resolve a JVM field descriptor (JLS 4.3) to its {@code Class<?>}.
     * Handles primitives, object types, and multi-dimensional arrays.
     * Returns {@code null} for unresolvable descriptors.
     */
    static Class<?> classFromDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return null;
        }
        switch (descriptor) {
            case "I": return int.class;
            case "J": return long.class;
            case "D": return double.class;
            case "F": return float.class;
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "S": return short.class;
            case "C": return char.class;
            case "V": return void.class;
            default: break;
        }
        if (descriptor.startsWith("[")) {
            String component = descriptor.substring(1);
            Class<?> componentType = classFromDescriptor(component);
            if (componentType != null) {
                return Array.newInstance(componentType, 0).getClass();
            }
            try {
                return Class.forName(descriptor.replace('/', '.'));
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            String className = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Coerce a value to match the target type. Handles primitive wrapper
     * conversions.
     */
    private static Object coerce(Class<?> ft, Object value) {
        if (value == null) {
            return null;
        }

        if (ft == int.class) {
            if (value instanceof Number) {
                return Integer.valueOf(((Number) value).intValue());
            }
            if (value instanceof Boolean) {
                return Integer.valueOf(((Boolean) value) ? 1 : 0);
            }
        } else if (ft == long.class) {
            if (value instanceof Number) {
                return Long.valueOf(((Number) value).longValue());
            }
        } else if (ft == double.class) {
            if (value instanceof Number) {
                return Double.valueOf(((Number) value).doubleValue());
            }
        } else if (ft == float.class) {
            if (value instanceof Number) {
                return Float.valueOf(((Number) value).floatValue());
            }
        } else if (ft == boolean.class) {
            if (value instanceof Boolean) {
                return value;
            }
            if (value instanceof Number) {
                return Boolean.valueOf(((Number) value).intValue() != 0);
            }
        } else if (ft == short.class) {
            if (value instanceof Number) {
                return Short.valueOf(((Number) value).shortValue());
            }
        } else if (ft == byte.class) {
            if (value instanceof Number) {
                return Byte.valueOf(((Number) value).byteValue());
            }
        } else if (ft == char.class) {
            if (value instanceof Character) {
                return value;
            }
            if (value instanceof Number) {
                return Character.valueOf((char) ((Number) value).intValue());
            }
        }
        return value;
    }

    /**
     * Allocate an object array matching the JVM descriptor className
     * (e.g. {@code [Lfoo.Bar;}, {@code [[Ljava.util.List;}).
     * Falls back to {@code Object[]} if the element class cannot be resolved.
     * When a custom {@link ArrayAllocator} is installed, delegates to it first.
     */
    private Object[] allocateObjectArray(String className, int length) {
        if (arrayAllocator != null) {
            try {
                Object[] arr = arrayAllocator.allocate(className, length);
                if (arr != null) {
                    return arr;
                }
            } catch (Throwable ignored) {
                // Fall through to default allocation
            }
        }
        try {
            int dims = 0;
            while (dims < className.length() && className.charAt(dims) == '[') {
                dims++;
            }
            if (dims == 0) {
                return new Object[length];
            }
            String inner = className.substring(dims);
            if (!inner.startsWith("L") || !inner.endsWith(";")) {
                return new Object[length];
            }
            Class<?> elementClass = Class.forName(inner.substring(1, inner.length() - 1));
            Class<?> resolvedType = elementClass;
            for (int i = 0; i < dims - 1; i++) {
                resolvedType = Array.newInstance(resolvedType, 0).getClass();
            }
            return (Object[]) Array.newInstance(resolvedType, length);
        } catch (Throwable t) {
            return new Object[length];
        }
    }

    /**
     * Store a value at the given index in an object array. On targets with
     * strict type checking, incompatibilities are silently dropped rather
     * than causing a fatal trap.  When a custom {@link ArrayAllocator} is
     * installed, delegates its {@link ArrayAllocator#setSafe} method.
     */
    private void arraySetSafe(Object[] arr, int index, Object value) {
        if (arrayAllocator != null) {
            try {
                arrayAllocator.setSafe(arr, index, value);
                return;
            } catch (Throwable ignored) {
                // Fall through to default
            }
        }
        try {
            arr[index] = value;
        } catch (Throwable ignored) {
            // Incompatible type -- drop silently to avoid fatal trap
        }
    }

    // ── Primitive reads ──────────────────────────────────────────────────────

    public int readInt() throws IOException {
        int c1 = in.read();
        int c2 = in.read();
        int c3 = in.read();
        int c4 = in.read();
        if ((c1 | c2 | c3 | c4) < 0) {
            int avail = 0;
            try { avail = in.available(); } catch (Throwable ignored) {}
            if (SerializationDiagnostics.isDebug()) {
                System.out.println("[TIS-EOF] readInt partial: c1=" + c1 + " c2=" + c2
                    + " c3=" + c3 + " c4=" + c4 + " trackedPos=" + position
                    + " in.available()=" + avail);
            }
            throw new IOException("Unexpected end of stream");
        }
        position += 4;
        return (c1 << 24) | (c2 << 16) | (c3 << 8) | c4;
    }

    public long readLong() throws IOException {
        long high = readInt() & 0xFFFFFFFFL;
        long low  = readInt() & 0xFFFFFFFFL;
        return (high << 32) | low;
    }

    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public boolean readBoolean() throws IOException {
        position++;
        return in.read() != 0;
    }

    public byte readByte() throws IOException {
        position++;
        int b = in.read();
        if (b < 0) {
            throw new IOException("Unexpected end of stream in readByte at position " + (position - 1));
        }
        return (byte) b;
    }

    public int readUnsignedByte() throws IOException {
        position++;
        return in.read() & 0xFF;
    }

    public short readShort() throws IOException {
        int c1 = in.read();
        int c2 = in.read();
        if ((c1 | c2) < 0) {
            throw new IOException("Unexpected end of stream");
        }
        position += 2;
        return (short) ((c1 << 8) | c2);
    }

    public int readUnsignedShort() throws IOException {
        int c1 = in.read();
        int c2 = in.read();
        if ((c1 | c2) < 0) {
            throw new IOException("Unexpected end of stream");
        }
        position += 2;
        return ((c1 << 8) | c2) & 0xFFFF;
    }

    public char readChar() throws IOException {
        return (char) readShort();
    }

    public String readUTF() throws IOException {
        int len = readUnsignedShort();
        if (len == 0xFFFF) {
            return null;
        }
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public void readFully(byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    public void readFully(byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int count = in.read(b, off + n, len - n);
            if (count < 0) {
                throw new IOException("Unexpected end of stream");
            }
            n += count;
        }
        position += len;
    }

    public int skipBytes(int n) throws IOException {
        int total = 0;
        while (total < n) {
            int skipped = (int) in.skip(n - total);
            if (skipped <= 0) {
                break;
            }
            total += skipped;
        }
        position += total;
        return total;
    }

    public String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            position++;
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                position++;
                int next = in.read();
                if (next >= 0 && next != '\n') {
                    position++;
                    sb.append('\r');
                }
                break;
            }
            sb.append((char) c);
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public int read() throws IOException {
        return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return in.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /**
     * Read the default serializable fields of the current object.
     * In TeaVM's custom serialization this is a no-op because fields are
     * always read automatically by the iterative work-stack engine.
     */
    public void defaultReadObject() throws IOException {
        // No-op: field traversal is handled by the iterative read loop
    }

    // ── Type codes (mirror TObjectOutputStream) ──────────────────────────────

    private static final byte TC_NULL      = TObjectOutputStream.TC_NULL;
    private static final byte TC_REFERENCE = TObjectOutputStream.TC_REFERENCE;
    private static final byte TC_STRING    = TObjectOutputStream.TC_STRING;
    private static final byte TC_ARRAY     = TObjectOutputStream.TC_ARRAY;
    private static final byte TC_OBJECT    = TObjectOutputStream.TC_OBJECT;
    private static final byte TC_INTEGER   = TObjectOutputStream.TC_INTEGER;
    private static final byte TC_LONG      = TObjectOutputStream.TC_LONG;
    private static final byte TC_DOUBLE    = TObjectOutputStream.TC_DOUBLE;
    private static final byte TC_FLOAT     = TObjectOutputStream.TC_FLOAT;
    private static final byte TC_BOOLEAN   = TObjectOutputStream.TC_BOOLEAN;
    private static final byte TC_LIST      = TObjectOutputStream.TC_LIST;
    private static final byte TC_MAP       = TObjectOutputStream.TC_MAP;
    private static final byte TC_SHORT     = TObjectOutputStream.TC_SHORT;
    private static final byte TC_CHAR      = TObjectOutputStream.TC_CHAR;
    private static final byte TC_BYTE      = TObjectOutputStream.TC_BYTE;
    private static final byte TC_ENUM      = TObjectOutputStream.TC_ENUM;
    private static final byte TC_BITSET    = TObjectOutputStream.TC_BITSET;
    private static final byte TC_SET       = TObjectOutputStream.TC_SET;
    private static final byte TC_SCHEMA    = TObjectOutputStream.TC_SCHEMA;
    private static final byte TC_LONGSTRING = 0x62;

    // ── Schema manifest ──────────────────────────────────────────────────

    private static final class ClassSchema {
        final String[] fieldNames;
        final String[] fieldTypeDescriptors;
        ClassSchema(String[] fieldNames, String[] fieldTypeDescriptors) {
            this.fieldNames = fieldNames;
            this.fieldTypeDescriptors = fieldTypeDescriptors;
        }
    }

    private final Map<String, ClassSchema> schemaManifest = new HashMap<>();

    private void readSchemaInline() throws IOException {
        int classCount = readShort();
        for (int i = 0; i < classCount; i++) {
            String className = readUTF();
            int fieldCount = readShort();
            String[] names = new String[fieldCount];
            String[] types = new String[fieldCount];
            for (int j = 0; j < fieldCount; j++) {
                names[j] = readUTF();
                types[j] = readUTF();
            }
            schemaManifest.put(className, new ClassSchema(names, types));
        }
    }

    /**
     * Returns {@code true} for class names whose live objects must not be
     * re-created during deserialization.  These classes maintain static
     * singletons whose constructors have global side-effects (e.g.
     * grammar.Grammar generates a full symbol table).  The deserializer
     * drains their field data without allocating a live object.
     */
    private static boolean isSingletonClassName(String className) {
        return "grammar.Grammar".equals(className);
    }
}
