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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * TeaVM-compatible ObjectOutputStream producing standard Java serialization format.
 * Output is readable by {@code java.io.ObjectInputStream} on any JVM.
 * <p>
 * Uses an iterative frame-based approach to avoid JS/Wasm call-stack overflow
 * on deeply nested object graphs. Container types (Object[], generic objects)
 * push a {@link WriteFrame} onto a heap-allocated stack; the main loop
 * processes frames iteratively.
 * <p>
 * Custom types can be registered via {@link #registerTypeHandler(TypeHandler)}.
 * Diagnostic callbacks can be installed via {@link #setListener(TSerializationListener)}.
 */
public class TObjectOutputStream extends OutputStream implements TObjectOutput {

    // ── Standard Java serialization constants ──────────────────────────────────

    static final int  STREAM_MAGIC   = 0xACED;
    static final int  STREAM_VERSION = 0x0005;

    // Type codes (from Java Object Serialization Specification §6.4)
    static final byte TC_NULL          = (byte) 0x70;
    static final byte TC_REFERENCE     = (byte) 0x71;
    static final byte TC_CLASSDESC     = (byte) 0x72;
    static final byte TC_OBJECT        = (byte) 0x73;
    static final byte TC_STRING        = (byte) 0x74;
    static final byte TC_ARRAY         = (byte) 0x75;
    static final byte TC_CLASS         = (byte) 0x76;
    static final byte TC_BLOCKDATA     = (byte) 0x77;
    static final byte TC_ENDBLOCKDATA  = (byte) 0x78;
    static final byte TC_ENUM          = (byte) 0x7E;
    static final byte TC_BLOCKDATALONG = (byte) 0x7A;

    // Class descriptor flags
    static final byte SC_SERIALIZABLE    = 0x02;
    static final byte SC_WRITE_METHOD    = 0x01;
    static final byte SC_EXTERNALIZABLE  = 0x04;
    static final byte SC_BLOCK_DATA      = 0x08;
    static final byte SC_ENUM            = 0x10;

    // Primitive type codes for field descriptors
    static final byte BYTE_TYPE    = 'B';
    static final byte CHAR_TYPE    = 'C';
    static final byte DOUBLE_TYPE  = 'D';
    static final byte FLOAT_TYPE   = 'F';
    static final byte INT_TYPE     = 'I';
    static final byte LONG_TYPE    = 'J';
    static final byte SHORT_TYPE   = 'S';
    static final byte BOOLEAN_TYPE = 'Z';
    static final byte OBJECT_TYPE  = 'L';
    static final byte ARRAY_TYPE   = '[';

    // ── JDK class SUID lookup ──────────────────────────────────────────────────

    private static final HashMap<String, Long> JDK_SUIDS = new HashMap<>();
    static {
        JDK_SUIDS.put("java.util.ArrayList",            8683452581122892189L);
        JDK_SUIDS.put("java.util.AbstractList",         5331098787285072646L);
        JDK_SUIDS.put("java.util.HashMap",              362498820763181265L);
        JDK_SUIDS.put("java.util.EnumMap",              458661240069192865L);
        JDK_SUIDS.put("java.util.BitSet",               7997698588986878753L);
        JDK_SUIDS.put("java.lang.Integer",              1360826667806852920L);
        JDK_SUIDS.put("java.lang.Long",                 4290774380558885855L);
        JDK_SUIDS.put("java.lang.Double",               -9172774392245257468L);
        JDK_SUIDS.put("java.lang.Float",                -2671254064391917916L);
        JDK_SUIDS.put("java.lang.Boolean",              -3665804199014368530L);
        JDK_SUIDS.put("java.lang.Short",                7515723878860438694L);
        JDK_SUIDS.put("java.lang.Character",            3786198719317633806L);
        JDK_SUIDS.put("java.lang.Byte",                -7183690577395765713L);
        JDK_SUIDS.put("java.lang.Number",               -8742448824652078965L);
        JDK_SUIDS.put("java.lang.String",              -6849794470754667710L);
        JDK_SUIDS.put("java.lang.Class",               -5198453180811184097L);
        JDK_SUIDS.put("java.lang.Enum",                0L);
        // AWT shims
        JDK_SUIDS.put("java.awt.Color",                118526816881161077L);
        JDK_SUIDS.put("java.awt.geom.Point2D",         0L);
        JDK_SUIDS.put("java.awt.geom.Point2D$Double",  6150783262733311327L);
        // Ludii collections
        JDK_SUIDS.put("main.collections.FastArrayList",     1L);
        // Ludii Externalizable
        JDK_SUIDS.put("main.collections.ChunkSet",          1L);
        // Trove Externalizable
        JDK_SUIDS.put("gnu.trove.impl.hash.THash",     -1792948471915530295L);

        // Ludii enums that declare serialVersionUID = 1L
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private final OutputStream out;
    private int nextHandle = 0x7e0000;

    /** Object instance → wire handle (for TC_REFERENCE back-references). */
    private final IdentityHashMap<Object, Integer> objectRefs = new IdentityHashMap<>();

    /** Class descriptor → wire handle (for reusing TC_CLASSDESC via TC_REFERENCE). */
    private final HashMap<String, Integer> classDescHandles = new HashMap<>();

    /** String → wire handle (for reusing TC_STRING via TC_REFERENCE). */
    private final HashMap<String, Integer> stringHandles = new HashMap<>();

    /** Cached field metadata per class. */
    private final HashMap<String, CachedClassInfo> classInfoCache = new HashMap<>();

    /** Type handler registry. */
    private final List<TypeHandler> typeHandlers = new ArrayList<>();

    private TSerializationListener listener;
    private static boolean includeTransientFields;
    private static int lastTotalObjectCount;
    private static int lastHandleCount;
    private int bytePos;

    // Handle trace: records type tag for every handle assignment
    private static ArrayList<String> handleTrace = new ArrayList<>();
    private static boolean traceEnabled;

    // Diagnostic ring buffer
    private static int diagWriteCount;
    private static final int DIAG_RING_SIZE = 64;
    private static final String[] diagRing = new String[DIAG_RING_SIZE];
    private static int diagRingIdx;

    // ── Class field metadata ───────────────────────────────────────────────────

    /** Metadata for a single class in the hierarchy. */
    private static final class ClassFieldInfo {
        String className;
        long suid;
        byte flags; // SC_SERIALIZABLE, |SC_WRITE_METHOD, |SC_EXTERNALIZABLE, |SC_ENUM
        /** Primitive fields sorted alphabetically by name. */
        Field[] primFields;
        byte[] primTypes;
        /** Object fields sorted alphabetically by name. */
        Field[] objFields;
        String[] objFieldDescs; // JVM descriptor (e.g. "Ljava/lang/String;")
    }

    /** Cached metadata for a class and its Serializable superclasses. */
    private static final class CachedClassInfo {
        /** Hierarchy from most-super Serializable to most-derived. */
        ClassFieldInfo[] hierarchy;
    }

    // ── Iterative frame stack ──────────────────────────────────────────────────

    private static final Object NULL_CHILD = new Object();

    private static final class WriteFrame {
        static final int WRITE_FIELDS = 1;
        static final int WRITE_ARRAY  = 2;

        int type;
        Object container;
        ClassFieldInfo[] hierarchy; // WRITE_FIELDS: hierarchy array
        int classIdx;               // WRITE_FIELDS: current class in hierarchy
        int fieldIdx;               // WRITE_FIELDS: current field in current class
        Object[] array;             // WRITE_ARRAY
        int nextIndex;              // WRITE_ARRAY: next element
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

    // ── Constructor and public API ─────────────────────────────────────────────

    public TObjectOutputStream(OutputStream out) throws IOException {
        this.out = out;
        writeShort(STREAM_MAGIC);
        writeShort(STREAM_VERSION);
    }

    public void registerTypeHandler(TypeHandler handler) {
        typeHandlers.add(handler);
    }

    public void setListener(TSerializationListener listener) {
        this.listener = listener;
    }

    public static void setIncludeTransientFields(boolean value) {
        includeTransientFields = value;
    }

    public static int getLastTotalObjectCount() {
        return lastTotalObjectCount;
    }

    public static int getLastHandleCount() {
        return lastHandleCount;
    }

    public static void enableHandleTrace(boolean enable) {
        traceEnabled = enable;
        if (enable) handleTrace = new ArrayList<>();
    }

    public static String getHandleTraceJSON() {
        if (handleTrace.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int len = handleTrace.size();
        int limit = Math.min(len, 50000);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(handleTrace.get(i)).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static void traceHandle(String tag) {
        if (traceEnabled) handleTrace.add(tag);
    }

    public static String dumpDiagRing() {
        StringBuilder sb = new StringBuilder();
        sb.append("diagWriteCount=").append(diagWriteCount).append(" lastClasses=");
        int count = Math.min(diagRingIdx, DIAG_RING_SIZE);
        int start = (diagRingIdx > DIAG_RING_SIZE) ? (diagRingIdx % DIAG_RING_SIZE) : 0;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % DIAG_RING_SIZE;
            if (i > 0) sb.append(',');
            sb.append(diagRing[idx]);
        }
        return sb.toString();
    }

    // ── Static serialize convenience methods ───────────────────────────────────

    public static byte[] serialize(Object obj) throws IOException {
        return serialize(obj, null);
    }

    public static byte[] serialize(Object obj,
            java.util.function.Consumer<TObjectOutputStream> setup) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        TObjectOutputStream oos = new TObjectOutputStream(baos);
        if (setup != null) setup.accept(oos);
        oos.writeObject(obj);
        oos.close();
        return baos.toByteArray();
    }

    // ── Main writeObject loop (iterative) ──────────────────────────────────────

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

    // ── Core: write one object to the stream ───────────────────────────────────

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

        // ── Grammar types: write null (prevent singleton corruption) ──
        if (isGrammarType(obj.getClass().getName())) {
            writeByte(TC_NULL);
            return true;
        }

        // ── Pre-register for circular reference detection (handle assigned later) ──
        objectRefs.put(obj, -1); // sentinel: "being written"

        Class<?> clazz = obj.getClass();
        String className = clazz.getName();

        // ── Externalizable (Trove, ChunkSet, etc.) — BEFORE type handlers ──
        // Externalizable objects must use standard format, not custom type codes,
        // so the reader can decode them without custom type handlers.
        if (obj instanceof java.io.Externalizable) {
            writeExternalizable((java.io.Externalizable) obj, className);
            return true;
        }

        // ── TypeHandler (for non-Externalizable custom types) ──
        for (TypeHandler handler : typeHandlers) {
            if (handler.canWrite(obj)) {
                writeByte(handler.typeCode());
                // Assign a wire handle (the reader registers one for every type handler result)
                assignObjectHandle(obj);
                handler.write(obj, this);
                return true;
            }
        }

        // Diagnostic
        diagWriteCount++;
        int rIdx = (diagRingIdx++) % DIAG_RING_SIZE;
        diagRing[rIdx] = className;
        if (listener != null) listener.onWriteObject(obj, frameTop);

        // ── String ──
        if (obj instanceof String) {
            writeStandardString((String) obj);
            // Handle already assigned by writeStandardString; update objectRefs
            Integer sh = stringHandles.get((String) obj);
            if (sh != null) objectRefs.put(obj, sh);
            return true;
        }

        // ── Class reference ──
        if (obj instanceof Class) {
            Class<?> cls = (Class<?>) obj;
            writeByte(TC_CLASS);
            writeClassDesc(cls.getName(), cls.isEnum(), (byte) (cls.isEnum() ? SC_ENUM : 0));
            assignObjectHandle(obj);
            return true;
        }

        // ── Enum ──
        if (obj instanceof Enum) {
            Enum<?> e = (Enum<?>) obj;
            String enumClassName = canonicalEnumClassName(e, className);
            writeByte(TC_ENUM);
            writeClassDesc(enumClassName, true, SC_ENUM | SC_SERIALIZABLE);
            assignObjectHandle(obj);
            writeNewString(e.name());
            return true;
        }

        // ── Boxed primitives ──
        if (obj instanceof Integer)  { writeBoxedInt((Integer) obj, obj); return true; }
        if (obj instanceof Long)     { writeBoxedLong((Long) obj, obj); return true; }
        if (obj instanceof Double)   { writeBoxedDouble((Double) obj, obj); return true; }
        if (obj instanceof Float)    { writeBoxedFloat((Float) obj, obj); return true; }
        if (obj instanceof Boolean)  { writeBoxedBoolean((Boolean) obj, obj); return true; }
        if (obj instanceof Short)    { writeBoxedShort((Short) obj, obj); return true; }
        if (obj instanceof Character){ writeBoxedCharacter((Character) obj, obj); return true; }
        if (obj instanceof Byte)     { writeBoxedByte((Byte) obj, obj); return true; }

        // ── Primitive arrays (write inline) ──
        if (obj instanceof int[])     { writePrimArray("[I", (int[]) obj); return true; }
        if (obj instanceof long[])    { writePrimArray("[J", (long[]) obj); return true; }
        if (obj instanceof double[])  { writePrimArray("[D", (double[]) obj); return true; }
        if (obj instanceof float[])   { writePrimArray("[F", (float[]) obj); return true; }
        if (obj instanceof boolean[]) { writePrimArray("[Z", (boolean[]) obj); return true; }
        if (obj instanceof byte[])    { writePrimArray("[B", (byte[]) obj); return true; }
        if (obj instanceof short[])   { writePrimArray("[S", (short[]) obj); return true; }
        if (obj instanceof char[])    { writePrimArray("[C", (char[]) obj); return true; }

        // ── JDK Collections: standard format ──
        if (obj instanceof ArrayList)    { writeArrayListStd((ArrayList<?>) obj); return true; }
        if (obj instanceof HashMap)      { writeHashMapStd((HashMap<?, ?>) obj); return true; }
        if (obj instanceof EnumMap)      {
            EnumMap<?, ?> em = (EnumMap<?, ?>) obj;
            Class<?> kt = getEnumMapKeyType(em);
            if (kt != null) {
                writeEnumMapStd(em);
            } else {
                System.out.println("[TOS] EnumMap with NULL keyType! size=" + em.size());
                writeHashMapStd(new HashMap<>(em));
            }
            return true;
        }
        if (obj instanceof BitSet)       { writeBitSetStd((BitSet) obj); return true; }

        // ── FastArrayList: standard SC_WRITE_METHOD format ──
        if (className != null && className.equals("main.collections.FastArrayList")) {
            writeFastArrayListStd(obj);
            return true;
        }

        // ── Object array (push frame for elements) ──
        if (obj instanceof Object[]) {
            Object[] arr = (Object[]) obj;
            writeByte(TC_ARRAY);
            writeClassDesc(className, false, 0);
            assignObjectHandle(obj);
            writeInt(arr.length);
            if (arr.length == 0) return true;
            WriteFrame f = pushFrame();
            f.type = WriteFrame.WRITE_ARRAY;
            f.array = arr;
            f.container = obj;
            f.nextIndex = 0;
            return false;
        }

        // ── List (non-ArrayList): snapshot as ArrayList and re-enter ──
        if (obj instanceof List) {
            // Remove sentinel pre-registration; snapshot gets its own handle via re-entry
            objectRefs.remove(obj);
            ArrayList<?> snapshot = (obj instanceof ArrayList)
                ? (ArrayList<?>) obj
                : new ArrayList<>((java.util.Collection<?>) obj);
            return writeOneObject(snapshot);
        }

        // ── Set: snapshot as ArrayList and re-enter ──
        if (obj instanceof java.util.Set) {
            objectRefs.remove(obj);
            ArrayList<Object> snapshot = new ArrayList<>((java.util.Collection<?>) obj);
            return writeOneObject(snapshot);
        }

        // ── Map (non-HashMap/EnumMap): snapshot as HashMap and re-enter ──
        if (obj instanceof Map) {
            objectRefs.remove(obj);
            HashMap<Object, Object> snapshot = new HashMap<>((Map<?, ?>) obj);
            return writeOneObject(snapshot);
        }

        // ── Generic object: TC_OBJECT + classDesc + push frame for fields ──
        CachedClassInfo info = getClassInfo(clazz, className);
        writeByte(TC_OBJECT);
        writeClassDescChain(info.hierarchy);

        // Assign object handle AFTER classDesc chain (matches standard handle order)
        assignObjectHandle(obj);

        // Count total fields across hierarchy
        int totalFields = 0;
        boolean hasOnlyPrimitives = true;
        for (ClassFieldInfo cfi : info.hierarchy) {
            totalFields += cfi.primFields.length + cfi.objFields.length;
            if (cfi.objFields.length > 0) hasOnlyPrimitives = false;
        }

        if (totalFields == 0) {
            // No fields — write SC_WRITE_METHOD block data end markers if needed
            writeBlockDataEnds(info.hierarchy);
            return true;
        }

        // Push frame to write field values
        WriteFrame f = pushFrame();
        f.type = WriteFrame.WRITE_FIELDS;
        f.container = obj;
        f.hierarchy = info.hierarchy;
        f.classIdx = 0;
        f.fieldIdx = 0;
        f.array = null;
        f.nextIndex = 0;
        return false;
    }

    // ── Frame advancement ──────────────────────────────────────────────────────

    private Object advanceFrame(WriteFrame f) throws IOException {
        switch (f.type) {
            case WriteFrame.WRITE_FIELDS:
                return advanceFieldsFrame(f);
            case WriteFrame.WRITE_ARRAY:
                return advanceArrayFrame(f);
            default:
                return null;
        }
    }

    /**
     * Advance through field values for a generic object.
     * Primitive fields are written inline as raw bytes.
     * Object fields are returned for nested writeObject processing.
     */
    private Object advanceFieldsFrame(WriteFrame f) throws IOException {
        while (f.classIdx < f.hierarchy.length) {
            ClassFieldInfo cfi = f.hierarchy[f.classIdx];

            // Write remaining primitive fields for current class
            while (f.fieldIdx < cfi.primFields.length) {
                Field field = cfi.primFields[f.fieldIdx];
                byte ptype = cfi.primTypes[f.fieldIdx];
                f.fieldIdx++;
                writePrimField(f.container, field, ptype);
            }

            // Switch to object fields
            int objIdx = f.fieldIdx - cfi.primFields.length;
            if (objIdx < cfi.objFields.length) {
                Field field = cfi.objFields[objIdx];
                f.fieldIdx++;
                try {
                    Object val = field.get(f.container);
                    if (val != null && isGrammarType(val.getClass().getName())) {
                        val = null;
                    }
                    return val != null ? val : NULL_CHILD;
                } catch (ReflectiveOperationException e) {
                    if (listener != null) {
                        listener.onError("write",
                                f.container != null ? f.container.getClass().getName() : "null", e);
                    }
                    return NULL_CHILD;
                }
            }

            // All fields for this class done
            // If SC_WRITE_METHOD, write TC_ENDBLOCKDATA
            if ((cfi.flags & SC_WRITE_METHOD) != 0) {
                writeByte(TC_ENDBLOCKDATA);
            }

            // Move to next class in hierarchy
            f.classIdx++;
            f.fieldIdx = 0;
        }
        return null; // all classes and fields done
    }

    private Object advanceArrayFrame(WriteFrame f) {
        if (f.nextIndex >= f.array.length) return null;
        Object val = f.array[f.nextIndex++];
        return val != null ? val : NULL_CHILD;
    }

    /** Assign a classDesc handle. */
    private int assignClassDescHandle(String className) {
        int handle = nextHandle++;
        Integer prev = classDescHandles.get(className);
        classDescHandles.put(className, handle);
        traceHandle("CD:" + className);
        // Trace: warn when a classDesc handle is overwritten
        if (traceEnabled && prev != null && className.contains("CompassDirection")) {
            System.out.println("[TOS-WARN] classDescHandle OVERWRITE for " + className
                + " old=" + prev + " new=" + handle + " idx=" + (handle - 0x7e0000));
        }
        return handle;
    }

    /** Assign the actual wire handle to an object (after classDesc has been written). */
    private void assignObjectHandle(Object obj) {
        int handle = nextHandle++;
        objectRefs.put(obj, handle);
        if (traceEnabled) {
            String tag = obj == null ? "null" : obj.getClass().getName();
            // Shorten common types
            if (tag.startsWith("java.util.")) tag = "ju:" + tag.substring(11);
            else if (tag.startsWith("java.lang.")) tag = "jl:" + tag.substring(10);
            else if (tag.startsWith("[")) tag = "arr:" + tag;
            traceHandle("OBJ:" + tag);
        }
    }
    private void writeBlockDataEnds(ClassFieldInfo[] hierarchy) throws IOException {
        for (ClassFieldInfo cfi : hierarchy) {
            if ((cfi.flags & SC_WRITE_METHOD) != 0) {
                writeByte(TC_ENDBLOCKDATA);
            }
        }
    }

    /** Write a primitive field value as raw bytes. */
    private void writePrimField(Object container, Field field, byte ptype) throws IOException {
        try {
            switch (ptype) {
                case INT_TYPE:     writeInt(field.getInt(container)); break;
                case LONG_TYPE:    writeLong(field.getLong(container)); break;
                case DOUBLE_TYPE:  writeDouble(field.getDouble(container)); break;
                case FLOAT_TYPE:   writeFloat(field.getFloat(container)); break;
                case BOOLEAN_TYPE: writeBoolean(field.getBoolean(container)); break;
                case BYTE_TYPE:    writeByte(field.getByte(container)); break;
                case SHORT_TYPE:   writeShort(field.getShort(container)); break;
                case CHAR_TYPE:    writeChar(field.getChar(container)); break;
            }
        } catch (ReflectiveOperationException e) {
            // Write zero for inaccessible primitive fields
            switch (ptype) {
                case LONG_TYPE: case DOUBLE_TYPE: writeLong(0); break;
                case FLOAT_TYPE: writeFloat(0); break;
                case INT_TYPE: case CHAR_TYPE: case SHORT_TYPE: writeInt(0); break;
                case BYTE_TYPE: writeByte(0); break;
                case BOOLEAN_TYPE: writeBoolean(false); break;
            }
        }
    }

    // ── Class descriptor writing ───────────────────────────────────────────────

    /**
     * Write a TC_CLASSDESC chain for the given class hierarchy.
     * The first element (most-derived) is written, its superClassDesc
     * references the next element, and the chain ends with TC_NULL.
     */
    private void writeClassDescChain(ClassFieldInfo[] hierarchy) throws IOException {
        // Write from most-derived to most-super, then TC_NULL.
        // hierarchy[0] is most-super, hierarchy[length-1] is most-derived.
        // Standard format: most-derived TC_CLASSDESC first, its superClassDesc points to parent.
        // If a classDesc was already written (exists in classDescHandles), emit TC_REFERENCE
        // instead of re-writing it. This prevents handle overwrites that corrupt back-references.
        for (int i = hierarchy.length - 1; i >= 0; i--) {
            ClassFieldInfo cfi = hierarchy[i];
            Integer existingHandle = classDescHandles.get(cfi.className);
            if (existingHandle != null) {
                // Already written — TC_REFERENCE replaces the entire remaining chain
                writeByte(TC_REFERENCE);
                writeInt(existingHandle);
                return;
            }
            writeByte(TC_CLASSDESC);
            writeDescString(cfi.className);
            writeLong(cfi.suid);
            int descHandle = assignClassDescHandle(cfi.className);
            writeByte(cfi.flags);

            // Field count
            int totalCount = cfi.primFields.length + cfi.objFields.length;
            writeShort(totalCount);

            // Primitive field descriptors (already sorted by name)
            for (int j = 0; j < cfi.primFields.length; j++) {
                writeByte(cfi.primTypes[j]);
                writeDescString(cfi.primFields[j].getName());
            }

            // Object field descriptors (already sorted by name)
            for (int j = 0; j < cfi.objFields.length; j++) {
                writeByte(OBJECT_TYPE);
                writeDescString(cfi.objFields[j].getName());
                writeFieldTypeName(cfi.objFieldDescs[j]);
            }

            // classAnnotation: empty → TC_ENDBLOCKDATA
            writeByte(TC_ENDBLOCKDATA);
        }
        // Super class of the most-super class: TC_NULL
        writeByte(TC_NULL);
    }

    /**
     * Write a single class descriptor (for enums, arrays, boxed types, etc.)
     * Handles handle dedup: if already written, emits TC_REFERENCE.
     */
    private void writeClassDesc(String className, boolean isEnum, int extraFlags) throws IOException {
        Integer existing = classDescHandles.get(className);
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
            // Trace: log when a classDesc TC_REFERENCE is written and what handle it points to
            if (traceEnabled && className.contains("CompassDirection")) {
                int idx = existing - 0x7e0000;
                String traceType = idx >= 0 && idx < handleTrace.size() ? handleTrace.get(idx) : "?";
                System.out.println("[TOS-REF] writeClassDesc TC_REF for " + className + " handle=" + existing + " idx=" + idx + " traceType=" + traceType);
            }
            return;
        }
        writeByte(TC_CLASSDESC);
        writeDescString(className);
        long suid = isEnum ? lookupEnumSUID(className) : lookupSUID(className);
        writeLong(suid);
        int descHandle = assignClassDescHandle(className);
        int flags = SC_SERIALIZABLE | extraFlags;
        writeByte(flags);
        // No fields for enums, simple types
        writeShort(0);
        // classAnnotation
        writeByte(TC_ENDBLOCKDATA);
        // superClassDesc
        if (isEnum && !className.equals("java.lang.Enum")) {
            // Enum classes extend java.lang.Enum
            writeClassDesc("java.lang.Enum", true, SC_ENUM | SC_SERIALIZABLE);
        } else if (isEnum) {
            // java.lang.Enum itself extends Object
            writeByte(TC_NULL);
        } else {
            writeSuperClassDesc(className);
        }
    }

    /** Write superclass descriptors for a simple class (enums, boxed types). */
    private void writeSuperClassDesc(String className) throws IOException {
        // Boxed number types → Number → Object
        if (className.startsWith("java.lang.") && !className.equals("java.lang.Number")
                && !className.equals("java.lang.String") && !className.equals("java.lang.Boolean")) {
            // Integer, Long, Double, Float, Short, Character, Byte → extends Number
            writeClassDesc("java.lang.Number", false, (byte) 0);
            return;
        }
        // Default: no serializable superclass
        writeByte(TC_NULL);
    }

    /** Write a standard TC_STRING with handle dedup (for string objects). */
    private void writeStandardString(String s) throws IOException {
        Integer existing = stringHandles.get(s);
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
            return;
        }
        int handle = nextHandle++;
        stringHandles.put(s, handle);
        traceHandle("S:" + s.substring(0, Math.min(20, s.length())));
        writeByte(TC_STRING);
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeShort(bytes.length);
        out.write(bytes);
        bytePos += 2 + bytes.length;
    }

    /**
     * Write a string with TC_STRING always (never TC_REFERENCE).
     * Used for enum constant names where some JVMs' readString()
     * in readEnum context may not handle TC_REFERENCE correctly.
     */
    private void writeNewString(String s) throws IOException {
        int handle = nextHandle++;
        // Do NOT register in stringHandles — enum names must never be TC_REFERENCE'd
        // because JVM's readString() in readEnum doesn't handle TC_REFERENCE
        traceHandle("NS:" + s.substring(0, Math.min(20, s.length())));
        writeByte(TC_STRING);
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeShort(bytes.length);
        out.write(bytes);
        bytePos += 2 + bytes.length;
    }

    /**
     * Write a raw UTF string for class descriptor fields (no TC_STRING prefix).
     * Standard Java serialization uses this format for className, fieldName,
     * and fieldTypeName inside TC_CLASSDESC blocks.
     */
    private void writeDescString(String s) throws IOException {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("String too long for descriptor: " + bytes.length);
        }
        writeShort(bytes.length);
        out.write(bytes);
        bytePos += 2 + bytes.length;
    }

    /**
     * Write a field type name using TC_STRING format (with handle assignment).
     * Standard Java serialization uses this for object/array field type names
     * inside TC_CLASSDESC blocks — NOT raw UTF.
     */
    private void writeFieldTypeName(String typeName) throws IOException {
        // Field type names use TC_STRING format (0x74 + handle + UTF data)
        Integer existing = stringHandles.get(typeName);
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
            return;
        }
        int handle = nextHandle++;
        stringHandles.put(typeName, handle);
        traceHandle("FTN:" + typeName);
        writeByte(TC_STRING);
        byte[] bytes = typeName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeShort(bytes.length);
        out.write(bytes);
        bytePos += 2 + bytes.length;
    }

    // ── Boxed primitive writers ────────────────────────────────────────────────

    private void writeBoxedInt(int val, Object obj) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.lang.Integer");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.lang.Integer");
            writeLong(1360826667806852920L);
            int descHandle = assignClassDescHandle("java.lang.Integer");
            writeByte(SC_SERIALIZABLE);
            writeShort(1);
            writeByte(INT_TYPE);
            writeDescString("value");
            writeByte(TC_ENDBLOCKDATA);
            writeClassDesc("java.lang.Number", false, 0);
        }
        assignObjectHandle(obj);
        writeInt(val);
    }

    private void writeBoxedLong(long val, Object obj) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.lang.Long");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.lang.Long");
            writeLong(4290774380558885855L);
            int descHandle = assignClassDescHandle("java.lang.Long");
            writeByte(SC_SERIALIZABLE);
            writeShort(1);
            writeByte(LONG_TYPE);
            writeDescString("value");
            writeByte(TC_ENDBLOCKDATA);
            writeClassDesc("java.lang.Number", false, 0);
        }
        assignObjectHandle(obj);
        writeLong(val);
    }

    private void writeBoxedDouble(double val, Object obj) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.lang.Double");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.lang.Double");
            writeLong(-9172774392245257468L);
            int descHandle = assignClassDescHandle("java.lang.Double");
            writeByte(SC_SERIALIZABLE);
            writeShort(1);
            writeByte(DOUBLE_TYPE);
            writeDescString("value");
            writeByte(TC_ENDBLOCKDATA);
            writeClassDesc("java.lang.Number", false, 0);
        }
        assignObjectHandle(obj);
        writeDouble(val);
    }

    private void writeBoxedFloat(float val, Object obj) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.lang.Float");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.lang.Float");
            writeLong(-2671254064391917916L);
            int descHandle = assignClassDescHandle("java.lang.Float");
            writeByte(SC_SERIALIZABLE);
            writeShort(1);
            writeByte(FLOAT_TYPE);
            writeDescString("value");
            writeByte(TC_ENDBLOCKDATA);
            writeClassDesc("java.lang.Number", false, 0);
        }
        assignObjectHandle(obj);
        writeFloat(val);
    }

    private void writeBoxedBoolean(boolean val, Object obj) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.lang.Boolean");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.lang.Boolean");
            writeLong(-3665804199014368530L);
            int descHandle = assignClassDescHandle("java.lang.Boolean");
            writeByte(SC_SERIALIZABLE);
            writeShort(1);
            writeByte(BOOLEAN_TYPE);
            writeDescString("value");
            writeByte(TC_ENDBLOCKDATA);
            writeByte(TC_NULL);
        }
        assignObjectHandle(obj);
        writeBoolean(val);
    }

    private void writeBoxedShort(short val, Object obj) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.lang.Short");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.lang.Short");
            writeLong(7515723878860438694L);
            int descHandle = assignClassDescHandle("java.lang.Short");
            writeByte(SC_SERIALIZABLE);
            writeShort(1);
            writeByte(SHORT_TYPE);
            writeDescString("value");
            writeByte(TC_ENDBLOCKDATA);
            writeClassDesc("java.lang.Number", false, 0);
        }
        assignObjectHandle(obj);
        writeShort(val);
    }

    private void writeBoxedCharacter(char val, Object obj) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.lang.Character");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.lang.Character");
            writeLong(3786198719317633806L);
            int descHandle = assignClassDescHandle("java.lang.Character");
            writeByte(SC_SERIALIZABLE);
            writeShort(1);
            writeByte(CHAR_TYPE);
            writeDescString("value");
            writeByte(TC_ENDBLOCKDATA);
            writeClassDesc("java.lang.Number", false, 0);
        }
        assignObjectHandle(obj);
        writeChar(val);
    }

    private void writeBoxedByte(byte val, Object obj) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.lang.Byte");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.lang.Byte");
            writeLong(-7183690577395765713L);
            int descHandle = assignClassDescHandle("java.lang.Byte");
            writeByte(SC_SERIALIZABLE);
            writeShort(1);
            writeByte(BYTE_TYPE);
            writeDescString("value");
            writeByte(TC_ENDBLOCKDATA);
            writeClassDesc("java.lang.Number", false, 0);
        }
        assignObjectHandle(obj);
        writeByte(val);
    }

    // ── Primitive array writers ────────────────────────────────────────────────

    private void writePrimArray(String arrayDesc, Object arr) throws IOException {
        writeByte(TC_ARRAY);
        writeClassDesc(arrayDesc, false, (byte) 0);
        assignObjectHandle(arr);
        if (arr instanceof int[]) {
            int[] a = (int[]) arr;
            writeInt(a.length);
            for (int v : a) writeInt(v);
        } else if (arr instanceof long[]) {
            long[] a = (long[]) arr;
            writeInt(a.length);
            for (long v : a) writeLong(v);
        } else if (arr instanceof double[]) {
            double[] a = (double[]) arr;
            writeInt(a.length);
            for (double v : a) writeDouble(v);
        } else if (arr instanceof float[]) {
            float[] a = (float[]) arr;
            writeInt(a.length);
            for (float v : a) writeFloat(v);
        } else if (arr instanceof boolean[]) {
            boolean[] a = (boolean[]) arr;
            writeInt(a.length);
            for (boolean v : a) writeBoolean(v);
        } else if (arr instanceof byte[]) {
            byte[] a = (byte[]) arr;
            writeInt(a.length);
            write(a);
        } else if (arr instanceof short[]) {
            short[] a = (short[]) arr;
            writeInt(a.length);
            for (short v : a) writeShort(v);
        } else if (arr instanceof char[]) {
            char[] a = (char[]) arr;
            writeInt(a.length);
            for (char v : a) writeChar(v);
        }
    }

    // ── JDK Collection standard writers ────────────────────────────────────────

    /**
     * Write java.util.ArrayList in standard format.
     * Standard: SC_SERIALIZABLE|SC_WRITE_METHOD, field "int size",
     * block data: writeInt(capacity) + writeObject(element) × size + TC_ENDBLOCKDATA.
     */
    private void writeArrayListStd(ArrayList<?> list) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.util.ArrayList");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.util.ArrayList");
            writeLong(8683452581122892189L);
            int descHandle = assignClassDescHandle("java.util.ArrayList");
            writeByte(SC_SERIALIZABLE | SC_WRITE_METHOD);
            writeShort(1);
            writeByte(INT_TYPE);
            writeDescString("size");
            writeByte(TC_ENDBLOCKDATA);
            writeByte(TC_NULL); // ArrayList extends AbstractList which is NOT Serializable
        }
        assignObjectHandle(list);
        // Field data for AbstractList: no serializable fields (modCount is transient)
        // Field data for ArrayList: int size
        writeInt(list.size());
        // objectAnnotation (SC_WRITE_METHOD):
        writeBlockDataInt(list.size()); // capacity (same as size for trimmed list)
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            writeObject(elem);
        }
        writeByte(TC_ENDBLOCKDATA);
    }

    /**
     * Write java.util.HashMap in standard format.
     * Standard: SC_SERIALIZABLE|SC_WRITE_METHOD, fields "float loadFactor", "int threshold",
     * block data: writeInt(buckets) + writeInt(size) + writeObject(key)+writeObject(value) × size.
     */
    private void writeHashMapStd(HashMap<?, ?> map) throws IOException {
        int size = map.size();
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.util.HashMap");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.util.HashMap");
            writeLong(362498820763181265L);
            int descHandle = assignClassDescHandle("java.util.HashMap");
            writeByte(SC_SERIALIZABLE | SC_WRITE_METHOD);
            writeShort(2);
            writeByte(FLOAT_TYPE);
            writeDescString("loadFactor");
            writeByte(INT_TYPE);
            writeDescString("threshold");
            writeByte(TC_ENDBLOCKDATA);
            writeByte(TC_NULL);
        }
        assignObjectHandle(map);
        // Field data: float loadFactor, int threshold
        writeFloat(0.75f); // loadFactor
        writeInt(size); // threshold (approximation, reader ignores it)
        // objectAnnotation (SC_WRITE_METHOD):
        writeBlockDataTwoInts(size, size); // buckets + mappings in one block
        for (Map.Entry<?, ?> e : map.entrySet()) {
            writeObject(e.getKey());
            writeObject(e.getValue());
        }
        writeByte(TC_ENDBLOCKDATA);
    }

    /**
     * Write java.util.EnumMap in standard format.
     * Standard: SC_SERIALIZABLE|SC_WRITE_METHOD, field "Class keyType",
     * block data: writeInt(size) + writeObject(key)+writeObject(value) × size.
     */
    private void writeEnumMapStd(EnumMap<?, ?> map) throws IOException {
        int size = map.size();
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.util.EnumMap");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.util.EnumMap");
            writeLong(458661240069192865L);
            int descHandle = assignClassDescHandle("java.util.EnumMap");
            writeByte(SC_SERIALIZABLE | SC_WRITE_METHOD);
            writeShort(1);
            writeByte(OBJECT_TYPE);
            writeDescString("keyType");
            writeFieldTypeName("Ljava/lang/Class;");
            writeByte(TC_ENDBLOCKDATA);
            writeByte(TC_NULL);
        }
        assignObjectHandle(map);
        // Field data: Class keyType — must use writeObject to register handle
        // (JVM's ObjectInputStream resolves through readObject0 which assigns handles)
        Class<?> keyType = getEnumMapKeyType(map);
        if (keyType != null) {
            writeObject(keyType);
        } else {
            writeByte(TC_NULL);
        }
        // objectAnnotation (SC_WRITE_METHOD):
        writeBlockDataInt(size);
        for (Map.Entry<?, ?> e : map.entrySet()) {
            writeObject(e.getKey());
            writeObject(e.getValue());
        }
        writeByte(TC_ENDBLOCKDATA);
    }

    /** Last known EnumMap keyType for empty-map fallback. */
    private static Class<?> lastEnumMapKeyType;

    /** Extract the key type from an EnumMap. */
    private static Class<?> getEnumMapKeyType(EnumMap<?, ?> map) {
        // Try reflection on the runtime class (works in both TeaVM and JVM)
        try {
            Field f = map.getClass().getDeclaredField("keyType");
            f.setAccessible(true);
            Class<?> kt = (Class<?>) f.get(map);
            if (kt != null) { lastEnumMapKeyType = kt; return kt; }
        } catch (Throwable t) {}
        // Fallback: reflection on the declared type
        try {
            Field f = EnumMap.class.getDeclaredField("keyType");
            f.setAccessible(true);
            Class<?> kt = (Class<?>) f.get(map);
            if (kt != null) { lastEnumMapKeyType = kt; return kt; }
        } catch (Throwable t) {}
        // Entry-set fallback: derive keyType from first entry
        try {
            for (Object e : map.entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) e;
                Object key = entry.getKey();
                if (key != null) {
                    Class<?> kt = key.getClass();
                    lastEnumMapKeyType = kt;
                    return kt;
                }
            }
        } catch (Throwable t) {}
        // Last resort: use the most recently seen EnumMap keyType
        if (lastEnumMapKeyType != null) return lastEnumMapKeyType;
        return null;
    }

    /**
     * Write java.util.BitSet in standard format.
     * Standard: SC_SERIALIZABLE, field "long[] bits". No SC_WRITE_METHOD.
     */
    private void writeBitSetStd(BitSet bs) throws IOException {
        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("java.util.BitSet");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("java.util.BitSet");
            writeLong(7997698588986878753L);
            int descHandle = assignClassDescHandle("java.util.BitSet");
            writeByte(SC_SERIALIZABLE | SC_WRITE_METHOD);
            writeShort(1);
            writeByte(ARRAY_TYPE);
            writeDescString("bits");
            writeFieldTypeName("[J");
            writeByte(TC_ENDBLOCKDATA);
            writeByte(TC_NULL);
        }
        assignObjectHandle(bs);
        // Field data: long[] bits
        long[] words = bs.toLongArray();
        writeObject(words);
        // SC_WRITE_METHOD: BitSet.writeObject ends with defaultWriteObject() then block data ends
        writeByte(TC_ENDBLOCKDATA);
    }

    /**
     * Write collections.FastArrayList in standard SC_WRITE_METHOD format.
     * Standard: SC_SERIALIZABLE|SC_WRITE_METHOD, field "int size",
     * block data: writeInt(size) + writeObject(element) × size + TC_ENDBLOCKDATA.
     */
    private void writeFastArrayListStd(Object obj) throws IOException {
        // Read size and data via reflection
        int size = 0;
        Object[] data = null;
        try {
            java.lang.reflect.Field sizeField = obj.getClass().getDeclaredField("size");
            sizeField.setAccessible(true);
            size = sizeField.getInt(obj);
            java.lang.reflect.Field dataField = obj.getClass().getDeclaredField("data");
            dataField.setAccessible(true);
            data = (Object[]) dataField.get(obj);
        } catch (Exception e) {
            // Fallback: try to get size from public size() method
            try {
                java.lang.reflect.Method sizeMethod = obj.getClass().getMethod("size");
                size = (Integer) sizeMethod.invoke(obj);
            } catch (Exception ignored) {}
        }

        writeByte(TC_OBJECT);
        Integer existing = classDescHandles.get("main.collections.FastArrayList");
        if (existing != null) {
            writeByte(TC_REFERENCE);
            writeInt(existing);
        } else {
            writeByte(TC_CLASSDESC);
            writeDescString("main.collections.FastArrayList");
            writeLong(1L);
            int descHandle = assignClassDescHandle("main.collections.FastArrayList");
            writeByte(SC_SERIALIZABLE | SC_WRITE_METHOD);
            writeShort(1);
            writeByte(INT_TYPE);
            writeDescString("size");
            writeByte(TC_ENDBLOCKDATA);
            writeByte(TC_NULL); // extends Object
        }
        assignObjectHandle(obj);
        // Field data: int size
        writeInt(size);
        // Block data (SC_WRITE_METHOD): writeInt(capacity) + elements
        writeBlockDataInt(size);
        for (int i = 0; i < size; i++) {
            Object elem = (data != null && i < data.length) ? data[i] : null;
            writeObject(elem);
        }
        writeByte(TC_ENDBLOCKDATA);
    }

    // ── Externalizable writer ────────────────────────────────────────────────

    /**
     * Write an Externalizable object in standard format.
     * Externalizable classes use SC_EXTERNALIZABLE|SC_BLOCK_DATA flags, 0 fields,
     * and their writeExternal output wrapped in block data.
     */
    private void writeExternalizable(java.io.Externalizable obj, String className) throws IOException {
        writeByte(TC_OBJECT);
        writeExternalizableClassDescChain(obj.getClass());
        assignObjectHandle(obj);
        // Buffer the writeExternal output using a simple ObjectOutput wrapper
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.ObjectOutput extOut = new SimpleObjectOutput(baos);
        obj.writeExternal(extOut);
        extOut.close();
        byte[] extData = baos.toByteArray();
        // Write as block data(s) + TC_ENDBLOCKDATA
        writeRawBlockData(extData);
        writeByte(TC_ENDBLOCKDATA);
    }

    /**
     * Write the classDesc chain for an Externalizable class hierarchy.
     * Each class in the chain gets SC_EXTERNALIZABLE|SC_BLOCK_DATA flags.
     */
    private void writeExternalizableClassDescChain(Class<?> clazz) throws IOException {
        // Collect hierarchy from most-derived to root Externalizable
        ArrayList<Class<?>> chain = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class && java.io.Externalizable.class.isAssignableFrom(c)) {
            chain.add(c);
            c = c.getSuperclass();
        }
        // Write from most-derived to most-super.
        // Each TC_CLASSDESC implicitly contains its superClassDesc (the next entry).
        // TC_REFERENCE replaces the ENTIRE remaining chain (the referenced classDesc
        // already has the full super chain built in), so we stop immediately.
        for (int i = 0; i < chain.size(); i++) {
            String name = chain.get(i).getName();
            Integer existing = classDescHandles.get(name);
            if (existing != null) {
                writeByte(TC_REFERENCE);
                writeInt(existing);
                return; // TC_REFERENCE includes the full super chain — nothing more to write
            }
            writeByte(TC_CLASSDESC);
            writeDescString(name);
            long suid = lookupSUID(name);
            writeLong(suid);
            int descHandle = assignClassDescHandle(name);
            writeByte(SC_EXTERNALIZABLE | SC_BLOCK_DATA); // 0x0C
            writeShort(0); // no fields
            writeByte(TC_ENDBLOCKDATA);
        }
        // Super of most-super Externalizable: TC_NULL
        writeByte(TC_NULL);
    }

    /** Write raw bytes as block data chunks (max 255 bytes per TC_BLOCKDATA). */
    private void writeRawBlockData(byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            int chunk = Math.min(data.length - off, 255);
            writeByte(TC_BLOCKDATA);
            writeByte(chunk);
            out.write(data, off, chunk);
            bytePos += 2 + chunk;
            off += chunk;
        }
    }

    /** Write an int inside block data context (TC_BLOCKDATA + 4 bytes). */
    private void writeBlockDataInt(int val) throws IOException {
        writeByte(TC_BLOCKDATA);
        writeByte(4);
        out.write((val >> 24) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write(val & 0xFF);
        bytePos += 6;
    }

    /** Write two ints in one TC_BLOCKDATA (8 bytes). */
    private void writeBlockDataTwoInts(int a, int b) throws IOException {
        writeByte(TC_BLOCKDATA);
        writeByte(8);
        out.write((a >> 24) & 0xFF);
        out.write((a >> 16) & 0xFF);
        out.write((a >> 8) & 0xFF);
        out.write(a & 0xFF);
        out.write((b >> 24) & 0xFF);
        out.write((b >> 16) & 0xFF);
        out.write((b >> 8) & 0xFF);
        out.write(b & 0xFF);
        bytePos += 10;
    }

    // ── Class info caching and field collection ────────────────────────────────

    private CachedClassInfo getClassInfo(Class<?> clazz, String className) {
        CachedClassInfo cached = classInfoCache.get(className);
        if (cached != null) return cached;

        // Collect full class hierarchy (most-super first), including non-Serializable
        // ancestors. Standard Java serialization stops at non-Serializable boundaries,
        // but Ludii's class hierarchy has Serializable classes extending non-Serializable
        // parents (e.g., Vertex extends TopologyElement) whose fields must be preserved.
        ArrayList<Class<?>> hierarchy = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            hierarchy.add(c);
            c = c.getSuperclass();
        }
        // Reverse to get most-super first
        ArrayList<Class<?>> sorted = new ArrayList<>(hierarchy);
        java.util.Collections.reverse(sorted);

        ArrayList<ClassFieldInfo> fieldInfos = new ArrayList<>();
        for (Class<?> cls : sorted) {
            fieldInfos.add(buildClassFieldInfo(cls));
        }

        cached = new CachedClassInfo();
        cached.hierarchy = fieldInfos.toArray(new ClassFieldInfo[0]);
        classInfoCache.put(className, cached);
        return cached;
    }

    private ClassFieldInfo buildClassFieldInfo(Class<?> clazz) {
        String className = clazz.getName();
        long suid = lookupSUID(className);
        byte flags = SC_SERIALIZABLE;

        // Detect Ludie custom writeObject classes
        if (isCustomWriteMethodClass(className)) {
            flags |= SC_WRITE_METHOD;
        }

        // Collect non-static, non-transient fields
        ArrayList<Field> primFieldsList = new ArrayList<>();
        ArrayList<Byte> primTypesList = new ArrayList<>();
        ArrayList<Field> objFieldsList = new ArrayList<>();
        ArrayList<String> objDescsList = new ArrayList<>();

        for (Field fld : clazz.getDeclaredFields()) {
            int mods = fld.getModifiers();
            if (Modifier.isStatic(mods)) continue;
            if (!includeTransientFields && Modifier.isTransient(mods)) continue;
            try {
                fld.setAccessible(true);
            } catch (Throwable t) {
                continue; // skip inaccessible (JVM module system)
            }

            Class<?> type = fld.getType();
            if (type.isPrimitive()) {
                primFieldsList.add(fld);
                primTypesList.add(primTypeCode(type));
            } else {
                objFieldsList.add(fld);
                objDescsList.add(typeDescriptor(type));
            }
        }

        // Sort primitives by field name
        sortFieldsByName(primFieldsList, primTypesList);
        // Sort objects by field name
        sortObjFieldsByName(objFieldsList, objDescsList);

        ClassFieldInfo cfi = new ClassFieldInfo();
        cfi.className = className;
        cfi.suid = suid;
        cfi.flags = flags;
        cfi.primFields = primFieldsList.toArray(new Field[0]);
        cfi.primTypes = new byte[primTypesList.size()];
        for (int i = 0; i < primTypesList.size(); i++) cfi.primTypes[i] = primTypesList.get(i);
        cfi.objFields = objFieldsList.toArray(new Field[0]);
        cfi.objFieldDescs = objDescsList.toArray(new String[0]);
        return cfi;
    }

    private static void sortFieldsByName(ArrayList<Field> fields, ArrayList<Byte> types) {
        // Simple insertion sort (small N)
        for (int i = 1; i < fields.size(); i++) {
            for (int j = i; j > 0 && fields.get(j).getName().compareTo(fields.get(j - 1).getName()) < 0; j--) {
                java.util.Collections.swap(fields, j, j - 1);
                java.util.Collections.swap(types, j, j - 1);
            }
        }
    }

    private static void sortObjFieldsByName(ArrayList<Field> fields, ArrayList<String> descs) {
        for (int i = 1; i < fields.size(); i++) {
            for (int j = i; j > 0 && fields.get(j).getName().compareTo(fields.get(j - 1).getName()) < 0; j--) {
                java.util.Collections.swap(fields, j, j - 1);
                java.util.Collections.swap(descs, j, j - 1);
            }
        }
    }

    private static byte primTypeCode(Class<?> type) {
        if (type == int.class)     return INT_TYPE;
        if (type == long.class)    return LONG_TYPE;
        if (type == double.class)  return DOUBLE_TYPE;
        if (type == float.class)   return FLOAT_TYPE;
        if (type == boolean.class) return BOOLEAN_TYPE;
        if (type == byte.class)    return BYTE_TYPE;
        if (type == short.class)   return SHORT_TYPE;
        if (type == char.class)    return CHAR_TYPE;
        return INT_TYPE; // fallback
    }

    private static String typeDescriptor(Class<?> c) {
        if (c == int.class)     return "I";
        if (c == long.class)    return "J";
        if (c == double.class)  return "D";
        if (c == float.class)   return "F";
        if (c == boolean.class) return "Z";
        if (c == byte.class)    return "B";
        if (c == short.class)   return "S";
        if (c == char.class)    return "C";
        if (c == void.class)    return "V";
        if (c.isArray())        return c.getName().replace('.', '/');
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static long lookupSUID(String className) {
        Long suid = JDK_SUIDS.get(className);
        if (suid != null) return suid;
        // Default: all Ludii classes use 1L
        return 1L;
    }

    /** Enum SUID lookup: JVM spec requires all enum descriptors to have SUID=0. */
    private static long lookupEnumSUID(String className) {
        return 0L;
    }

    /** Classes with custom writeObject that need SC_WRITE_METHOD flag. */
    private static boolean isCustomWriteMethodClass(String className) {
        return "other.state.container.BaseContainerState".equals(className)
            || "other.state.puzzle.BaseContainerStateDeductionPuzzles".equals(className)
            || "main.collections.FastArrayList".equals(className);
    }

    private static String canonicalEnumClassName(Enum<?> e, String rawName) {
        try {
            Class<?> dc = e.getDeclaringClass();
            if (dc != null) return dc.getName();
        } catch (Exception ex) {
            // fall through
        }
        int dollar = rawName.lastIndexOf('$');
        if (dollar >= 0) {
            String suffix = rawName.substring(dollar + 1);
            boolean allDigits = !suffix.isEmpty();
            for (int i = 0; i < suffix.length(); i++) {
                if (!Character.isDigit(suffix.charAt(i))) { allDigits = false; break; }
            }
            if (allDigits) return rawName.substring(0, dollar);
        }
        return rawName;
    }

    static boolean isGrammarType(String className) {
        return className.startsWith("main.grammar.")
            || className.startsWith("grammar.");
    }

    /**
     * Minimal ObjectOutput that writes raw bytes to a ByteArrayOutputStream.
     * Used to capture writeExternal output without ObjectOutputStream overhead.
     */
    private static final class SimpleObjectOutput extends java.io.DataOutputStream
            implements java.io.ObjectOutput {
        SimpleObjectOutput(java.io.OutputStream out) { super(out); }
        public void writeObject(Object obj) throws IOException {
            throw new UnsupportedOperationException("writeObject not supported in Externalizable buffer");
        }
        public void flush() throws IOException { out.flush(); }
        public void close() throws IOException { out.close(); }
    }

    // ── Primitive writers ──────────────────────────────────────────────────────

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
        for (int i = 0; i < len; i++) out.write(s.charAt(i) & 0xFF);
    }

    public void writeChars(String s) throws IOException {
        int len = s.length();
        for (int i = 0; i < len; i++) writeChar(s.charAt(i));
    }

    public void writeUTF(String s) throws IOException {
        if (s == null) { writeShort(-1); return; }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("String too long for UTF encoding: " + bytes.length);
        }
        writeShort(bytes.length);
        out.write(bytes);
        bytePos += bytes.length;
    }

    public void write(byte[] b) throws IOException { out.write(b); bytePos += b.length; }
    public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); bytePos += len; }

    @Override
    public void write(int b) throws IOException { out.write(b); bytePos++; }

    @Override
    public void flush() throws IOException { out.flush(); }

    @Override
    public void close() throws IOException {
        lastTotalObjectCount = objectRefs.size();
        lastHandleCount = nextHandle;
        out.close();
    }

    public void reset() throws IOException {
        objectRefs.clear();
        classDescHandles.clear();
        stringHandles.clear();
        nextHandle = 0x7e0000;
    }

    public void defaultWriteObject() throws IOException {
        // No-op: field traversal is handled by the iterative frame loop
    }
}
