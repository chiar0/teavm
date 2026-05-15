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
import java.util.List;

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

    /** Auto-detected: true when ReflectLink is on the classpath. */
    private static final boolean REFLECT_LINK_AVAILABLE;

    static {
        boolean available = false;
        try {
            Class.forName("org.teavm.classlib.reflect.ReflectLink");
            available = true;
        } catch (Throwable ignored) {}
        REFLECT_LINK_AVAILABLE = available;
    }

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

    /** Cache enum valueOf Method objects: className -> Method (avoids repeated getMethod reflection). */
    private final java.util.HashMap<String, java.lang.reflect.Method> enumValueOfCache = new java.util.HashMap<>();

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

        if (REFLECT_LINK_AVAILABLE) {
            autoInstallReflectLink();
            if (SerializationDiagnostics.isDebug()) {
                System.out.println("[TIS] ReflectLink available=" + REFLECT_LINK_AVAILABLE
                    + " allocator=" + (objectAllocator != null) + " fieldWriter=" + (fieldWriter != null));
            }
        } else {
            if (SerializationDiagnostics.isDebug()) {
                System.out.println("[TIS] ReflectLink NOT available");
            }
        }
    }

    private void autoInstallReflectLink() {
        try {
            // Direct invocation — both classes are in the classlib JAR.
            // Using direct calls instead of getMethod()/invoke() because TeaVM's
            // runtime reflection may not register static methods on classlib classes.
            objectAllocator = clazz -> {
                try {
                    org.teavm.classlib.reflect.ReflectLink.AllocResult result =
                        org.teavm.classlib.reflect.ReflectLink.allocateWithReason(clazz);
                    return result.obj;
                } catch (Throwable t) { return null; }
            };

            fieldWriter = (obj, fieldName, value, schemaTypeDescriptor) -> {
                org.teavm.classlib.reflect.ReflectLink.setField(obj, fieldName, value, schemaTypeDescriptor);
            };

            arrayAllocator = new ArrayAllocator() {
                @Override
                public Object[] allocate(String className, int length) {
                    try { return org.teavm.classlib.reflect.ReflectLink.allocateObjectArray(className, length); }
                    catch (Throwable t) { return null; }
                }

                @Override
                public void setSafe(Object[] arr, int index, Object value) {
                    try { org.teavm.classlib.reflect.ReflectLink.arraySetSafe(arr, index, value); }
                    catch (Throwable t) { arr[index] = value; }
                }
            };
        } catch (Throwable t) {
            if (SerializationDiagnostics.isDebug()) {
                System.out.println("[TIS] autoInstallReflectLink FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            }
        }
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
        static final int OBJECT_ARRAY  = 2;
        static final int STD_FIELDS    = 9; // Standard format: read fields in descriptor order

        int type;
        Object container;
        int remaining;

        // STD_FIELDS:
        String className;
        String nextFieldName;

        // STD_FIELDS: class descriptor hierarchy for field ordering
        ClassDescInfo[] fieldHierarchy;
        int totalFieldIndex; // current field index across all hierarchy classes

        // OBJECT_ARRAY:
        int nextIndex;
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

    /** Sentinel returned when TC_ENDBLOCKDATA is read at top level. */
    private static final Object END_BLOCK = new Object();

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
                    throw new IOException(formatException(e), e);
                } catch (Throwable t) {
                    // Catch JS runtime errors (TypeError, etc.) that TeaVM wraps
                    if (SerializationDiagnostics.isDebug()) {
                        System.out.println("[TIS-DELIVER] CRASH in deliverToFrame frameTop=" + frameTop
                            + " frameType=" + frameStack[Math.max(0, frameTop - 1)].type
                            + " className=" + frameStack[Math.max(0, frameTop - 1)].className
                            + " err=" + t.getClass().getName() + ": " + t.getMessage());
                    }
                    if (frameTop > 0) {
                        frameTop--;
                    }
                    throw new IOException("deliverToFrame failed: " + t.getMessage(), t);
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
        if (SerializationDiagnostics.isDebug() && (totalObjects < 5 || frameTop < 2)) {
            System.out.println("[TIS] readLeafOrStartContainer tc=0x" + Integer.toHexString(tc & 0xFF)
                + " total=" + totalObjects + " frameTop=" + frameTop + " pos=" + position
                + " handles=" + handleList.size());
        }
        recordTrace(tc);
        chunkCounter++;  // Count every operation toward the chunk budget
        switch (tc) {
            case TC_NULL:
                return null;
            case TC_REFERENCE:
                return readReference();
            case TC_STRING: {
                // TC_STRING: handle + UTF data
                String v = readUTF();
                register(v);
                return v;
            }
            case TC_ENUM:
                return readStdEnum();
            case TC_ARRAY:
                return readStdArray();
            case TC_OBJECT:
                return readStdObject();
            case TC_CLASS: {
                // TC_CLASS: classDesc + assign handle
                ClassDescInfo desc = readClassDesc();
                totalObjects++;
                Object classObj = null;
                if (desc != null && desc.className != null) {
                    try {
                        classObj = Class.forName(desc.className);
                    } catch (ClassNotFoundException ignored) {}
                }
                register(classObj);
                return classObj;
            }
            case TC_BLOCKDATA:
            case TC_BLOCKDATALONG: {
                // Skip block data (for SC_WRITE_METHOD/SC_EXTERNALIZABLE)
                skipBlockData(tc);
                return readLeafOrStartContainer();
            }
            case TC_ENDBLOCKDATA:
                // Should not be read at top level; signal to caller
                return END_BLOCK;
            default:
                break;
        }

        // Try registered type handlers for custom type codes
        for (TypeHandler handler : typeHandlers) {
            if (handler.typeCode() == tc) {
                handlerCallCount++;
                // Pre-register placeholder (matches writer's assignObjectHandle before handler.write)
                int handleIdx = handleList.size();
                logHandle(null);
                handleList.add(null);
                Object result = handler.read(tc, this);
                handleList.set(handleIdx, result);
                return result;
            }
        }

        IOException badTc = new IOException(buildTraceMessage("BADTC:0x"
            + Integer.toHexString(tc & 0xFF) + " hc=" + handlerCallCount));
        throw new IOException(formatException(badTc), badTc);
    }

    private Object deliverToFrame(Frame f, Object value) throws IOException, ClassNotFoundException {
        switch (f.type) {
            case Frame.STD_FIELDS: {
                // Standard format: deliver object field value, then continue
                if (SerializationDiagnostics.isDebug() && "game.Game".equals(f.className) && f.totalFieldIndex < 8) {
                    System.out.println("[TIS-FIELD] obj field[" + f.totalFieldIndex + "] "
                        + f.nextFieldName + " val=" + (value != null ? value.getClass().getName() : "null"));
                }
                try {
                    setFieldSafely(f.container, f.nextFieldName, value, null);
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onError("setField", f.className, e);
                    }
                }
                f.totalFieldIndex++;
                f.remaining--;
                // Continue processing remaining fields (may be more primitives)
                return processStdFieldsFrame();
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

            default:
                throw new IOException(formatException(
                    new IOException("Unknown frame type: " + f.type)));
        }
    }

    // ── Stack-trace formatting ─────────────────────────────────────────────

    /**
     * Format a throwable with its stack trace (up to 8 frames) and cause
     * chain into a single human-readable string.  Useful for WASM GC and
     * other runtimes where the native error message is opaque.
     */
    private static String formatException(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage());
        StackTraceElement[] trace = t.getStackTrace();
        if (trace != null) {
            int max = Math.min(trace.length, 8);
            for (int i = 0; i < max; i++) {
                sb.append("\n  at ").append(trace[i].toString());
            }
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            sb.append("\nCaused by: ").append(formatException(cause));
        }
        return sb.toString();
    }

    /**
     * Public static utility that callers (e.g. LudiiBridge) can use to
     * format any exception with stack-trace information for diagnostics.
     */
    public static String formatThrowable(Throwable t) {
        return formatException(t);
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

    private void logHandle(Object obj) {
        if (SerializationDiagnostics.isDebug()) {
            int idx = handleList.size();
            String type = obj == null ? "null" : obj.getClass().getName();
            if (obj instanceof ClassDescInfo) type = "CD:" + ((ClassDescInfo) obj).className;
            else if (obj instanceof String) type = "S:" + ((String) obj).substring(0, Math.min(20, ((String) obj).length()));
            System.out.println("[TIS-REG] handle=" + idx + " type=" + type + " pos=" + position + " totalObj=" + totalObjects);
        }
    }

    private void register(Object obj) {
        logHandle(obj);
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
            IOException badHandle = new IOException(buildTraceMessage("Invalid object handle: " + handle
                + " (index=" + index + ", registered=" + handleList.size() + ")"));
            throw new IOException(formatException(badHandle), badHandle);
        }
        return handleList.get(index);
    }

    // ── Container starters ───────────────────────────────────────────────────

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
                if (SerializationDiagnostics.isDebug()) {
                    System.out.println("[TIS-SETFIELD] fieldWriter FAILED: " + obj.getClass().getName()
                        + "." + fieldName + " = " + (value != null ? value.getClass().getName() : "null")
                        + " err=" + e.getMessage());
                }
                if (listener != null) {
                    listener.onError("setField", obj.getClass().getName(), e);
                }
                // Fall through to default reflection path
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

    // ── Type codes (standard Java Object Serialization) ──────────────────────

    private static final byte TC_NULL          = TObjectOutputStream.TC_NULL;
    private static final byte TC_REFERENCE     = TObjectOutputStream.TC_REFERENCE;
    private static final byte TC_CLASSDESC     = TObjectOutputStream.TC_CLASSDESC;
    private static final byte TC_OBJECT        = TObjectOutputStream.TC_OBJECT;
    private static final byte TC_STRING        = TObjectOutputStream.TC_STRING;
    private static final byte TC_ARRAY         = TObjectOutputStream.TC_ARRAY;
    private static final byte TC_CLASS         = TObjectOutputStream.TC_CLASS;
    private static final byte TC_BLOCKDATA     = TObjectOutputStream.TC_BLOCKDATA;
    private static final byte TC_ENDBLOCKDATA  = TObjectOutputStream.TC_ENDBLOCKDATA;
    private static final byte TC_ENUM          = TObjectOutputStream.TC_ENUM;
    private static final byte TC_BLOCKDATALONG = TObjectOutputStream.TC_BLOCKDATALONG;

    // Class descriptor flags (mirror TObjectOutputStream)
    private static final byte SC_SERIALIZABLE    = TObjectOutputStream.SC_SERIALIZABLE;
    private static final byte SC_WRITE_METHOD    = TObjectOutputStream.SC_WRITE_METHOD;
    private static final byte SC_EXTERNALIZABLE  = TObjectOutputStream.SC_EXTERNALIZABLE;
    private static final byte SC_BLOCK_DATA      = TObjectOutputStream.SC_BLOCK_DATA;
    private static final byte SC_ENUM            = TObjectOutputStream.SC_ENUM;

    // ── Class descriptor cache ──────────────────────────────────────────────

    /** Parsed class descriptor from TC_CLASSDESC. */
    private static final class ClassDescInfo {
        String className;
        long suid;
        byte flags;
        /** Field names in descriptor order (primitives first, then objects). */
        String[] fieldNames;
        /** Field type codes: B,C,D,F,I,J,S,Z for primitives; L,[ for objects. */
        byte[] fieldTypes;
        /** For object/array fields (L,[): the type descriptor string. */
        String[] fieldClassNames;
        /** Super class descriptor (null if superclass is TC_NULL). */
        ClassDescInfo superDesc;
    }

    /** Class descriptor handle → ClassDescInfo. */
    private final ArrayList<ClassDescInfo> descHandleList = new ArrayList<>();

    /** Read a class descriptor: TC_NULL, TC_REFERENCE, or TC_CLASSDESC. */
    private ClassDescInfo readClassDesc() throws IOException {
        int tc = readByte();
        if (tc == TC_NULL) return null;
        if (tc == TC_REFERENCE) {
            int handle = readInt();
            int idx = handle - HANDLE_BASE;
            if (idx >= 0 && idx < handleList.size()) {
                Object obj = handleList.get(idx);
                if (obj instanceof ClassDescInfo) return (ClassDescInfo) obj;
                // Diagnostic: what did we find instead?
                StringBuilder diag = new StringBuilder();
                diag.append("Bad class desc reference: 0x").append(Integer.toHexString(handle))
                    .append(" idx=").append(idx).append(" found ").append(obj.getClass().getName())
                    .append(" totalHandles=").append(handleList.size())
                    .append(" totalObjects=").append(totalObjects)
                    .append(" pos=").append(position);
                // Show nearby handles
                diag.append(" nearby=[");
                for (int j = Math.max(0, idx - 3); j <= Math.min(handleList.size() - 1, idx + 3); j++) {
                    if (j > Math.max(0, idx - 3)) diag.append(", ");
                    Object h = handleList.get(j);
                    diag.append(j).append("=");
                    if (h instanceof ClassDescInfo) diag.append("CD:").append(((ClassDescInfo)h).className);
                    else if (h instanceof String) diag.append("S:").append(((String)h).substring(0, Math.min(30, ((String)h).length())));
                    else if (h == null) diag.append("null");
                    else diag.append(h.getClass().getSimpleName());
                }
                diag.append("]");
                throw new IOException(diag.toString());
            }
            throw new IOException("Bad class desc reference: 0x" + Integer.toHexString(handle)
                + " idx=" + idx + " totalHandles=" + handleList.size());
        }
        if (tc == TC_CLASSDESC) {
            return readNewClassDesc();
        }
        throw new IOException("Expected class desc, got tc=0x" + Integer.toHexString(tc & 0xFF)
            + " at position " + (position - 1));
    }

    /** Read a fresh TC_CLASSDESC block. */
    private ClassDescInfo readNewClassDesc() throws IOException {
        ClassDescInfo desc = new ClassDescInfo();
        desc.className = readUTF();
        desc.suid = readLong();
        desc.flags = (byte) readByte();

        // Register handle BEFORE reading remaining content (matches writer order)
        logHandle(desc);
        handleList.add(desc);

        int fieldCount = readShort();

        desc.fieldNames = new String[fieldCount];
        desc.fieldTypes = new byte[fieldCount];
        desc.fieldClassNames = new String[fieldCount];

        for (int i = 0; i < fieldCount; i++) {
            desc.fieldTypes[i] = (byte) readByte();
            desc.fieldNames[i] = readUTF();
            byte tc = desc.fieldTypes[i];
            if (tc == 'L' || tc == '[') {
                // Object/array field: type name is TC_STRING or TC_REFERENCE
                desc.fieldClassNames[i] = readTypeString();
            }
        }

        // classAnnotation: skip until TC_ENDBLOCKDATA
        skipAnnotations();

        // superClassDesc
        desc.superDesc = readClassDesc();

        return desc;
    }

    /** Read a string that may be TC_STRING, TC_LONGSTRING, or TC_REFERENCE. */
    private String readTypeString() throws IOException {
        int tc = readByte();
        if (tc == TC_STRING) {
            String s = readUTF();
            logHandle(s);
            handleList.add(s); // register handle
            return s;
        }
        if (tc == TC_REFERENCE) {
            int handle = readInt();
            int idx = handle - HANDLE_BASE;
            if (idx >= 0 && idx < handleList.size()) {
                Object obj = handleList.get(idx);
                if (obj instanceof String) return (String) obj;
                if (SerializationDiagnostics.isDebug()) {
                    System.out.println("[TIS-BADREF] readTypeString got TC_REFERENCE handle=0x"
                        + Integer.toHexString(handle) + " idx=" + idx
                        + " but found " + obj.getClass().getName() + " not String"
                        + " totalHandles=" + handleList.size());
                }
            } else {
                if (SerializationDiagnostics.isDebug()) {
                    System.out.println("[TIS-BADREF] readTypeString got TC_REFERENCE handle=0x"
                        + Integer.toHexString(handle) + " idx=" + idx
                        + " but handleList.size()=" + handleList.size()
                        + " pos=" + position);
                }
            }
            throw new IOException("Bad string reference: 0x" + Integer.toHexString(handle));
        }
        throw new IOException("Expected string in field descriptor, got tc=0x"
            + Integer.toHexString(tc & 0xFF));
    }

    /** Skip annotation blocks (class annotations, object annotations). */
    private void skipAnnotations() throws IOException {
        while (true) {
            int tc = readByte();
            if (tc == TC_ENDBLOCKDATA) return;
            if (tc == TC_BLOCKDATA) {
                int len = readByte() & 0xFF;
                skipBytes(len);
            } else if (tc == TC_BLOCKDATALONG) {
                int len = readInt();
                skipBytes(len);
            } else {
                // It's an object annotation — read and discard
                // Push back the tc byte... we can't easily do that.
                // Instead, handle common cases
                throw new IOException("Unexpected tc=0x" + Integer.toHexString(tc & 0xFF)
                    + " in annotation at position " + (position - 1));
            }
        }
    }

    /** Skip block data and all contents until TC_ENDBLOCKDATA. */
    private void skipBlockData(int tc) throws IOException {
        if (tc == TC_BLOCKDATA) {
            int len = readByte() & 0xFF;
            skipBytes(len);
        } else if (tc == TC_BLOCKDATALONG) {
            int len = readInt();
            skipBytes(len);
        }
    }

    // ── Standard format object reader ────────────────────────────────────────

    /**
     * Read a standard TC_OBJECT: classDesc chain + field values + optional block data.
     */
    private Object readStdObject() throws IOException, ClassNotFoundException {
        ClassDescInfo desc = readClassDesc();
        if (desc == null) {
            throw new IOException("TC_OBJECT with null class desc at position " + (position - 1));
        }
        String pendingClassName = desc.className; // capture for error reporting

        // Check flags for special handling
        boolean isExternalizable = (desc.flags & SC_EXTERNALIZABLE) != 0;
        boolean isWriteMethod = (desc.flags & SC_WRITE_METHOD) != 0;

        // Build the full field list from class hierarchy (most-super first)
        ArrayList<ClassDescInfo> hierarchy = new ArrayList<>();
        ClassDescInfo d = desc;
        while (d != null) {
            hierarchy.add(d);
            d = d.superDesc;
        }
        java.util.Collections.reverse(hierarchy);

        String className = desc.className;

        if (SerializationDiagnostics.isDebug()) {
            int tf = 0;
            for (ClassDescInfo cdi : hierarchy) tf += cdi.fieldNames.length;
            System.out.println("[TIS-STD] readStdObject #" + totalObjects + " class=" + className
                + " hierarchy=" + hierarchy.size() + " totalFields=" + tf
                + " flags=0x" + Integer.toHexString(desc.flags & 0xFF)
                + " pos=" + position);
        }

        // ── Special handling for JDK collections ────────────────────────
        if ("java.util.ArrayList".equals(className)) {
            return readArrayListStd(hierarchy);
        }
        if ("java.util.HashMap".equals(className)) {
            return readHashMapStd(hierarchy);
        }
        if ("java.util.EnumMap".equals(className)) {
            return readEnumMapStd(hierarchy);
        }
        if ("java.util.BitSet".equals(className)) {
            return readBitSetStd(hierarchy);
        }

        // ── Singleton guard ────────────────────────────────────────────
        if (isSingletonClassName(className)) {
            totalObjects++;
            register(null);
            drainObjectFields(hierarchy, isWriteMethod || isExternalizable);
            return null;
        }

        // ── Stage 1: Class.forName (cached) ──────────────────────────
        Class<?> clazz = classForNameCache.get(className);
        if (clazz == null && !classForNameCache.containsKey(className)) {
            try {
                clazz = Class.forName(className);
            } catch (Throwable e) {
                if (listener != null) listener.onError("Class.forName", className,
                    new RuntimeException(e.getClass().getName() + ": " + e.getMessage()));
            }
            classForNameCache.put(className, clazz);
        }

        // ── Stage 2: Allocate ─────────────────────────────────────────
        Object obj = null;
        if (clazz != null) {
            try {
                obj = allocateInstance(clazz);
            } catch (Exception e) {
                if (SerializationDiagnostics.isDebug()) {
                    System.out.println("[TIS-ALLOC] allocateInstance threw for " + className + ": " + e.getMessage());
                }
                if (listener != null) listener.onError("allocate", className, e);
            } catch (Throwable t) {
                if (SerializationDiagnostics.isDebug()) {
                    System.out.println("[TIS-ALLOC] allocateInstance THREW for " + className + ": " + t.getClass().getName() + ": " + t.getMessage());
                }
                if (listener != null) listener.onError("allocate", className,
                    new RuntimeException(t.getClass().getName() + ": " + t.getMessage()));
            }
        }

        totalObjects++;

        // ── Stage 3: Structural failure — drain ──────────────────────
        if (obj == null) {
            if (SerializationDiagnostics.isDebug()) {
                System.out.println("[TIS-STD] ALLOC FAILED for " + className + " — draining " + hierarchy.size() + " classes");
            }
            register(null);
            drainObjectFields(hierarchy, isWriteMethod || isExternalizable);
            return null;
        }

        register(obj);

        if (isExternalizable) {
            readExternalData(obj, clazz);
            if (postDeserializeCallback != null) {
                postDeserializeCallback.onObjectDeserialized(obj);
            }
            return resolveObject(obj);
        }

        // Push STD_FIELDS frame that reads all fields in descriptor order
        Frame f = pushFrame();
        f.type = Frame.STD_FIELDS;
        f.container = obj;
        f.className = className;
        f.fieldHierarchy = hierarchy.toArray(new ClassDescInfo[0]);
        f.totalFieldIndex = 0;
        // remaining = total fields across hierarchy
        int totalFields = 0;
        for (ClassDescInfo cdi : hierarchy) totalFields += cdi.fieldNames.length;
        f.remaining = totalFields;

        if (totalFields == 0) {
            frameTop--;
            if (isWriteMethod) {
                readObjectAnnotation(obj, className);
            }
            if (postDeserializeCallback != null) {
                postDeserializeCallback.onObjectDeserialized(obj);
            }
            return resolveObject(obj);
        }

        if (SerializationDiagnostics.isDebug() && "game.Game".equals(className)) {
            System.out.println("[TIS-STD] About to call processStdFieldsFrame for Game"
                + " totalFields=" + totalFields + " obj=" + (obj != null) + " frameTop=" + frameTop);
        }

        // Process any leading primitive fields immediately
        try {
            return processStdFieldsFrame();
        } catch (Throwable t) {
            if (SerializationDiagnostics.isDebug()) {
                System.out.println("[TIS-STD] CRASH in readStdObject class=" + pendingClassName
                    + " totalObjects=" + totalObjects + " pos=" + position
                    + " err=" + t.getClass().getName() + ": " + t.getMessage());
            }
            throw t;
        }
    }

    /**
     * Process the STD_FIELDS frame at top of stack.
     * Reads primitive fields inline; returns NEEDS_CHILDREN when an object field is encountered.
     */
    private Object processStdFieldsFrame() throws IOException, ClassNotFoundException {
        Frame f = frameStack[frameTop - 1];
        ClassDescInfo[] hierarchy = f.fieldHierarchy;

        if (SerializationDiagnostics.isDebug() && "game.Game".equals(f.className) && f.totalFieldIndex == 0) {
            System.out.println("[TIS-STD] processStdFieldsFrame START for game.Game remaining=" + f.remaining
                + " hierarchy=" + hierarchy.length + " fieldCounts="
                + java.util.Arrays.toString(java.util.stream.IntStream.range(0, hierarchy.length)
                    .map(i -> hierarchy[i].fieldNames.length).toArray()));
        }

        while (f.remaining > 0) {
            // Find current field
            int fieldIdx = f.totalFieldIndex;
            int counted = 0;
            ClassDescInfo currentClass = null;
            int fieldInClass = 0;
            for (ClassDescInfo cdi : hierarchy) {
                if (counted + cdi.fieldNames.length > fieldIdx) {
                    currentClass = cdi;
                    fieldInClass = fieldIdx - counted;
                    break;
                }
                counted += cdi.fieldNames.length;
            }

            if (currentClass == null) break;

            byte ftc = currentClass.fieldTypes[fieldInClass];
            String fieldName = currentClass.fieldNames[fieldInClass];
            String typeDesc = (ftc == 'L' || ftc == '[') ? currentClass.fieldClassNames[fieldInClass] : String.valueOf((char) ftc);

            if (isPrimitiveTypeCode(ftc)) {
                // Read and set primitive immediately
                Object value = readPrimitiveValue(ftc);
                if (SerializationDiagnostics.isDebug() && "game.Game".equals(f.className) && f.totalFieldIndex < 8) {
                    System.out.println("[TIS-FIELD] prim field[" + f.totalFieldIndex + "] "
                        + currentClass.className + "." + fieldName + " type=" + (char)ftc
                        + " val=" + value);
                }
                try {
                    setFieldSafely(f.container, fieldName, value, typeDesc);
                } catch (Exception e) {
                    if (listener != null) listener.onError("setField", currentClass.className, e);
                }
                f.totalFieldIndex++;
                f.remaining--;
            } else {
                // Object field — store field name and return NEEDS_CHILDREN
                f.nextFieldName = fieldName;
                if (SerializationDiagnostics.isDebug() && "game.Game".equals(f.className) && f.totalFieldIndex < 8) {
                    System.out.println("[TIS-FIELD] obj field[" + f.totalFieldIndex + "] " + fieldName
                        + " type=" + typeDesc + " handles=" + handleList.size() + " pos=" + position);
                }
                return NEEDS_CHILDREN;
            }
        }

        // All fields done
        frameTop--;
        // Check for SC_WRITE_METHOD block data
        // (need to check flags of the most-derived class)
        byte topFlags = hierarchy[hierarchy.length - 1].flags;
        if ((topFlags & SC_WRITE_METHOD) != 0) {
            readObjectAnnotation(f.container, f.className);
        }
        if (postDeserializeCallback != null) {
            postDeserializeCallback.onObjectDeserialized(f.container);
        }
        return resolveObject(f.container);
    }

    /** Read a primitive value based on its type code. */
    private Object readPrimitiveValue(byte typeCode) throws IOException {
        switch (typeCode) {
            case 'B': return (byte) readByte();
            case 'C': return (char) readChar();
            case 'D': return readDouble();
            case 'F': return readFloat();
            case 'I': return readInt();
            case 'J': return readLong();
            case 'S': return (short) readShort();
            case 'Z': return readByte() != 0;
            default: throw new IOException("Unknown primitive type code: " + (char) typeCode);
        }
    }

    private boolean isPrimitiveTypeCode(byte tc) {
        return tc == 'B' || tc == 'C' || tc == 'D' || tc == 'F'
            || tc == 'I' || tc == 'J' || tc == 'S' || tc == 'Z';
    }

    /** Drain field values for an object that couldn't be allocated. */
    private void drainObjectFields(ArrayList<ClassDescInfo> hierarchy, boolean hasBlockData) throws IOException {
        for (ClassDescInfo cdi : hierarchy) {
            for (int i = 0; i < cdi.fieldNames.length; i++) {
                if (isPrimitiveTypeCode(cdi.fieldTypes[i])) {
                    readPrimitiveValue(cdi.fieldTypes[i]);
                } else {
                    // Read and discard the object reference
                    try {
                        readObjectUnframed();
                    } catch (Throwable t) { /* ignore */ }
                }
            }
        }
        if (hasBlockData) {
            // Skip block data until TC_ENDBLOCKDATA
            while (true) {
                int tc = readByte();
                if (tc == TC_ENDBLOCKDATA) break;
                if (tc == TC_BLOCKDATA) {
                    int len = readByte() & 0xFF;
                    skipBytes(len);
                } else if (tc == TC_BLOCKDATALONG) {
                    int len = readInt();
                    skipBytes(len);
                } else {
                    // It's an embedded object — skip it
                    try { readObjectUnframed(); } catch (Throwable t) { /* ignore */ }
                }
            }
        }
    }

    /** Read object annotation block data (for SC_WRITE_METHOD). */
    private void readObjectAnnotation(Object obj, String className) throws IOException {
        // Read block data + objects until TC_ENDBLOCKDATA
        // For Ludii custom writeObject classes, we need to handle specific formats
        while (true) {
            int tc = readByte();
            if (tc == TC_ENDBLOCKDATA) break;
            if (tc == TC_BLOCKDATA) {
                int len = readByte() & 0xFF;
                skipBytes(len);
            } else if (tc == TC_BLOCKDATALONG) {
                int len = readInt();
                skipBytes(len);
            } else {
                // Embedded object — try to read and apply based on class
                try { readObjectUnframed(); } catch (Throwable t) { /* ignore */ }
            }
        }
    }

    /** Read external data for Externalizable objects. */
    private void readExternalData(Object obj, Class<?> clazz) throws IOException {
        // For SC_EXTERNALIZABLE|SC_BLOCK_DATA: read block data until TC_ENDBLOCKDATA
        // Then call readExternal on the object
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        while (true) {
            int tc = readByte();
            if (tc == TC_ENDBLOCKDATA) break;
            if (tc == TC_BLOCKDATA) {
                int len = readByte() & 0xFF;
                for (int i = 0; i < len; i++) baos.write(readByte());
            } else if (tc == TC_BLOCKDATALONG) {
                int len = readInt();
                for (int i = 0; i < len; i++) baos.write(readByte());
            } else {
                throw new IOException("Unexpected tc=0x" + Integer.toHexString(tc & 0xFF)
                    + " in external data for " + clazz.getName());
            }
        }
        // Call readExternal with the buffered data
        if (obj instanceof java.io.Externalizable) {
            try {
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(baos.toByteArray());
                java.io.DataInputStream dis = new java.io.DataInputStream(bais);
                ((java.io.Externalizable) obj).readExternal(new SimpleObjectInput(dis));
            } catch (Throwable t) {
                if (listener != null) listener.onError("readExternal", clazz.getName(),
                    new RuntimeException(t.getClass().getName() + ": " + t.getMessage()));
            }
        }
    }

    // ── Standard format array reader ─────────────────────────────────────────

    private Object readStdArray() throws IOException, ClassNotFoundException {
        ClassDescInfo desc = readClassDesc();
        if (desc == null) {
            throw new IOException("TC_ARRAY with null class desc at position " + (position - 1));
        }
        String className = desc.className;
        int length = readInt();

        // Primitive arrays
        if (className.equals("[B")) {
            byte[] arr = new byte[length];
            readFully(arr);
            register(arr);
            return arr;
        } else if (className.equals("[I")) {
            int[] arr = new int[length];
            for (int i = 0; i < length; i++) arr[i] = readInt();
            register(arr);
            return arr;
        } else if (className.equals("[J")) {
            long[] arr = new long[length];
            for (int i = 0; i < length; i++) arr[i] = readLong();
            register(arr);
            return arr;
        } else if (className.equals("[D")) {
            double[] arr = new double[length];
            for (int i = 0; i < length; i++) arr[i] = readDouble();
            register(arr);
            return arr;
        } else if (className.equals("[F")) {
            float[] arr = new float[length];
            for (int i = 0; i < length; i++) arr[i] = readFloat();
            register(arr);
            return arr;
        } else if (className.equals("[Z")) {
            boolean[] arr = new boolean[length];
            for (int i = 0; i < length; i++) arr[i] = readByte() != 0;
            register(arr);
            return arr;
        } else if (className.equals("[S")) {
            short[] arr = new short[length];
            for (int i = 0; i < length; i++) arr[i] = (short) readShort();
            register(arr);
            return arr;
        } else if (className.equals("[C")) {
            char[] arr = new char[length];
            for (int i = 0; i < length; i++) arr[i] = (char) readChar();
            register(arr);
            return arr;
        }

        // Object array
        Object[] arr = allocateObjectArray(className, length);
        register(arr);
        if (length == 0) return arr;

        Frame f = pushFrame();
        f.type = Frame.OBJECT_ARRAY;
        f.container = arr;
        f.remaining = length;
        f.nextIndex = 0;
        return NEEDS_CHILDREN;
    }

    // ── Standard format collection readers ────────────────────────────────────

    private Object readArrayListStd(ArrayList<ClassDescInfo> hierarchy) throws IOException, ClassNotFoundException {
        // Read default field: int size
        // Find the size field in the ArrayList classDesc
        int size = 0;
        for (ClassDescInfo cdi : hierarchy) {
            for (int i = 0; i < cdi.fieldNames.length; i++) {
                if (cdi.fieldTypes[i] == 'I' && "size".equals(cdi.fieldNames[i])) {
                    size = readInt();
                }
            }
        }
        // Skip TC_BLOCKDATA(capacity)
        int tc = readByte();
        if (tc == TC_BLOCKDATA) {
            int len = readByte() & 0xFF;
            skipBytes(len);
        }
        // Create ArrayList and register
        ArrayList<Object> list = new ArrayList<>(size);
        totalObjects++;
        register(list);
        // Read elements
        for (int i = 0; i < size; i++) {
            Object elem = readObjectUnframed();
            list.add(elem);
        }
        // Read TC_ENDBLOCKDATA
        readByte(); // TC_ENDBLOCKDATA
        return list;
    }

    private Object readHashMapStd(ArrayList<ClassDescInfo> hierarchy) throws IOException, ClassNotFoundException {
        // Read default fields: float loadFactor, int threshold
        float loadFactor = 0.75f;
        int threshold = 0;
        for (ClassDescInfo cdi : hierarchy) {
            for (int i = 0; i < cdi.fieldNames.length; i++) {
                if (cdi.fieldTypes[i] == 'F' && "loadFactor".equals(cdi.fieldNames[i])) {
                    loadFactor = readFloat();
                } else if (cdi.fieldTypes[i] == 'I' && "threshold".equals(cdi.fieldNames[i])) {
                    threshold = readInt();
                }
            }
        }
        // Block data: capacity + mappings count
        int tc = readByte();
        int capacity = 0, mappings = 0;
        if (tc == TC_BLOCKDATA) {
            int len = readByte() & 0xFF;
            capacity = readInt();
            mappings = readInt();
            // Skip any remaining bytes in block
            if (len > 8) skipBytes(len - 8);
        }
        // Create HashMap and register
        HashMap<Object, Object> map = new HashMap<>(capacity, loadFactor);
        totalObjects++;
        register(map);
        // Read key-value pairs
        for (int i = 0; i < mappings; i++) {
            Object key = readObjectUnframed();
            Object value = readObjectUnframed();
            map.put(key, value);
        }
        // Read TC_ENDBLOCKDATA
        readByte();
        return map;
    }

    private Object readEnumMapStd(ArrayList<ClassDescInfo> hierarchy) throws IOException, ClassNotFoundException {
        // CRITICAL: The writer assigns the EnumMap object handle BEFORE writing
        // the keyType field value. We must register a placeholder handle at the
        // same position, then patch it after creating the EnumMap.
        int mapHandleIdx = handleList.size();
        totalObjects++;
        logHandle(null);
        handleList.add(null); // placeholder — patched below

        // Read default field: Class keyType
        Class<?> keyType = null;
        for (ClassDescInfo cdi : hierarchy) {
            for (int i = 0; i < cdi.fieldNames.length; i++) {
                if ("keyType".equals(cdi.fieldNames[i])) {
                    keyType = readClassField();
                }
            }
        }

        // Create EnumMap and patch handle
        EnumMap map;
        try {
            map = keyType != null ? new EnumMap(keyType) : null;
        } catch (Throwable t) {
            map = null; // defer creation
        }
        // If keyType was null or EnumMap creation failed, defer creation
        boolean deferredCreation = (map == null);

        if (!deferredCreation) {
            handleList.set(mapHandleIdx, map);
        }

        // Block data: size + key-value pairs
        int tc = readByte();
        int size = 0;
        if (tc == TC_BLOCKDATA) {
            int len = readByte() & 0xFF;
            size = readInt();
            if (len > 4) skipBytes(len - 4);
        }
        for (int i = 0; i < size; i++) {
            Object key = readObjectUnframed();
            Object value = readObjectUnframed();
            // Deferred creation: create EnumMap using first key's declaring class
            if (deferredCreation && map == null && key instanceof Enum) {
                try {
                    Class<?> kt = ((Enum<?>) key).getDeclaringClass();
                    map = new EnumMap(kt);
                } catch (Throwable t) {
                    // Fall back to HashMap
                }
                if (map != null) {
                    handleList.set(mapHandleIdx, map); // patch placeholder, no extra handle
                }
            }
            if (map != null) {
                try {
                    map.put(key, value);
                } catch (Throwable t) {
                    // Ignore put failures
                }
            }
        }
        // Handle empty EnumMap with null keyType — patch placeholder with HashMap
        if (deferredCreation && map == null) {
            Object fallback = new HashMap();
            handleList.set(mapHandleIdx, fallback); // patch placeholder, no extra handle
            // Read TC_ENDBLOCKDATA
            readByte();
            return fallback;
        }
        // Read TC_ENDBLOCKDATA
        readByte();
        return map;
    }

    private Object readBitSetStd(ArrayList<ClassDescInfo> hierarchy) throws IOException, ClassNotFoundException {
        // Pre-register BitSet handle (matches writer's assignObjectHandle before field values)
        int bsHandleIdx = handleList.size();
        totalObjects++;
        logHandle(null);
        handleList.add(null); // placeholder

        // Read default field: long[] bits
        long[] words = null;
        for (ClassDescInfo cdi : hierarchy) {
            for (int i = 0; i < cdi.fieldNames.length; i++) {
                if ("bits".equals(cdi.fieldNames[i])) {
                    words = (long[]) readObjectUnframed();
                }
            }
        }
        // Create BitSet and patch handle
        BitSet bs = new BitSet();
        if (words != null) {
            for (int i = 0; i < words.length; i++) {
                long w = words[i];
                for (int bit = 0; bit < 64; bit++) {
                    if ((w & (1L << bit)) != 0) {
                        bs.set(i * 64 + bit);
                    }
                }
            }
        }
        handleList.set(bsHandleIdx, bs);
        // SC_WRITE_METHOD: read TC_ENDBLOCKDATA
        readByte();
        return bs;
    }

    /** Read a Class field value (TC_CLASS + classDesc, TC_REFERENCE, or TC_NULL). */
    private Class<?> readClassField() throws IOException {
        int tc = readByte();
        if (tc == TC_NULL) return null;
        if (tc == TC_REFERENCE) {
            Object ref = readReference();
            if (ref instanceof Class) return (Class<?>) ref;
            return null;
        }
        if (tc == TC_CLASS) {
            ClassDescInfo desc = readClassDesc();
            if (desc == null) return null;
            try {
                Class<?> cls = Class.forName(desc.className);
                // Register handle to match writer's handle assignment for Class objects
                logHandle(cls);
                handleList.add(cls);
                return cls;
            } catch (ClassNotFoundException e) {
                // Register null handle to keep numbering aligned
                logHandle(null);
                handleList.add(null);
                return null;
            }
        }
        throw new IOException("Expected TC_CLASS, TC_REFERENCE, or TC_NULL, got 0x" + Integer.toHexString(tc & 0xFF));
    }

    // ── Standard format enum reader ──────────────────────────────────────────

    private Object readStdEnum() throws IOException {
        // TC_ENUM: classDesc (enum class) + TC_STRING (constant name)
        ClassDescInfo desc = readClassDesc();
        String className = desc != null ? desc.className : "unknown";

        // Pre-register enum handle BEFORE reading string (matches writer's
        // assignObjectHandle before writeStandardString)
        int enumHandleIdx = handleList.size();
        totalObjects++;
        logHandle(null);
        handleList.add(null); // placeholder

        // Read enum constant name (TC_STRING or TC_REFERENCE) — registers string handle
        String constName = readTypeString();

        // Resolve the enum constant and patch handle
        Object result = resolveEnumConstant(className, constName);
        handleList.set(enumHandleIdx, result);
        return result;
    }

    /** Resolve an enum constant from class name and constant name. */
    private Object resolveEnumConstant(String className, String constName) {
        String cacheKey = className + "." + constName;
        Object cached = enumConstantCache.get(cacheKey);
        if (cached != null) return cached;

        try {
            Class<?> clazz = classForNameCache.get(className);
            if (clazz == null) {
                clazz = Class.forName(className);
                classForNameCache.put(className, clazz);
            }
            java.lang.reflect.Method valueOf = enumValueOfCache.get(className);
            if (valueOf == null) {
                valueOf = clazz.getMethod("valueOf", String.class);
                enumValueOfCache.put(className, valueOf);
            }
            Object result = valueOf.invoke(null, constName);
            enumConstantCache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            if (listener != null) listener.onError("enum", className, e);
            return null;
        }
    }

    // ── Simple ObjectInput for readExternal ──────────────────────────────────

    private static final class SimpleObjectInput extends java.io.DataInputStream
            implements java.io.ObjectInput {
        SimpleObjectInput(java.io.InputStream in) { super(in); }
        public Object readObject() { throw new UnsupportedOperationException(); }
        public void close() {}
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
