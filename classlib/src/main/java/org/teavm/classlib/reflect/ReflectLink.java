package org.teavm.classlib.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.teavm.classlib.java.lang.TClass;

/**
 * Cross-backend reflection bridge used by the ObjectInputStream shim.
 * <p>
 * Centralises the few operations that behave differently between the TeaVM
 * JavaScript target and the WebAssembly GC target:
 * <ul>
 *   <li>Raw instance allocation (no constructor) — three-strategy fallback
 *       chain that always yields a live object when any strategy succeeds.</li>
 *   <li>Field lookup across the superclass chain with per-class caching.</li>
 *   <li>Safe field writes — value coercion for primitive arrays that arrive
 *       as aliased types on Wasm GC, suppression of {@code ref.cast} traps.</li>
 *   <li>Typed object-array allocation so array element writes don't trap.</li>
 *   <li>ChunkSet {@code wordsInUse} repair (Ludii-specific) and enum lookup.</li>
 * </ul>
 */
public final class ReflectLink {

    private ReflectLink() {}

    // ═══════════════════════════════════════════════════════════════════════
    //  Diagnostic sink (decoupled from SerDiag)
    // ═══════════════════════════════════════════════════════════════════════

    public interface DiagSink {
        void record(String kind, String className, String fieldName, String expectedType, String actualType);
        void record(String kind, String className, String fieldName, String actualType);
        boolean isDebug();
    }

    private static DiagSink diag = null;

    public static void setDiagSink(DiagSink sink) { diag = sink; }

    /**
     * Get the {@code ClassInfo} for a Class object via the back-pointer field
     * on the Wasm GC {@code java.lang.Class} struct (populated by
     * {@code TClass.createClass()} during struct construction).
     */
    private static org.teavm.runtime.reflect.ClassInfo getClassInfo(Class<?> clazz) {
        try {
            return ((TClass<?>) (Object) clazz).getClassInfo();
        } catch (Throwable t) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Instance allocation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Result of an allocation attempt.  Callers that can recover from a
     * failure read {@code obj}; callers that want to surface a rich error
     * (e.g. the {@code IOException} path in {@code TObjectInputStream}) read
     * {@code reason} and {@code strategyErrors}.
     */
    public static final class AllocResult {
        public final Object obj;
        /** Human-readable short reason when obj is null; null when obj is non-null. */
        public final String reason;
        /** Per-strategy error messages (always 3 entries, nulls where no attempt was made). */
        public final String[] strategyErrors;

        AllocResult(Object obj, String reason, String[] strategyErrors) {
            this.obj = obj;
            this.reason = reason;
            this.strategyErrors = strategyErrors;
        }
    }

    /**
     * Allocate a live instance of {@code clazz} for deserialization.  Unlike
     * the legacy {@link #allocate(Class)}, this variant retains the error
     * message from each attempted strategy so diagnostics can show exactly
     * why a class could not be instantiated.
     * <p>
       * Constructor-first strategy order:
     * <ol>
     *   <li>Declared no-arg constructor via reflection — works even when
     *       the constructor is private.</li>
     *   <li>Raw allocation via {@code ClassInfo.newInstance()} —
     *       runs the no-arg constructor.</li>
     *   <li>Raw allocation + field init via
     *       {@code ClassInfo.newInstance()} + {@code initializeNewInstance()}
     *       — skips {@code <clinit>} for classes with constructor-clinit issues.</li>
     * </ol>
     */
    public static AllocResult allocateWithReason(Class<?> clazz) {
        String[] errs = new String[3];

        // Strategy 1: Declared no-arg constructor via reflection.
        try {
            java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object obj = ctor.newInstance();
            if (obj != null) return new AllocResult(obj, null, errs);
            errs[0] = "Constructor.newInstance() returned null";
        } catch (Throwable t) {
            errs[0] = "declared-ctor threw " + t.getClass().getName()
                + ": " + t.getMessage();
        }

        // Strategy 2: ClassInfo.newInstance — runs no-arg constructor + field init.
        try {
            org.teavm.runtime.reflect.ClassInfo ci = getClassInfo(clazz);
            if (ci != null) {
                Object obj = ci.newInstance();
                if (obj != null) return new AllocResult(obj, null, errs);
                errs[1] = "ClassInfo.newInstance() returned null";
            } else {
                errs[1] = "no ClassInfo in runtime registry";
            }
        } catch (Throwable t) {
            errs[1] = "ClassInfo.newInstance threw " + t.getClass().getName()
                + ": " + t.getMessage();
        }

        // Strategy 3: ClassInfo.newInstance + initializeNewInstance, no clinit.
        try {
            org.teavm.runtime.reflect.ClassInfo ci = getClassInfo(clazz);
            if (ci != null) {
                Object obj = ci.newInstance();
                if (obj != null) {
                    ci.initializeNewInstance(obj);
                    return new AllocResult(obj, null, errs);
                }
                errs[2] = "newInstance (no-clinit) returned null";
            } else {
                errs[2] = "no ClassInfo for no-clinit path";
            }
        } catch (Throwable t) {
            errs[2] = "no-clinit alloc threw " + t.getClass().getName()
                + ": " + t.getMessage();
        }

        // Compose a single-line reason so log grepping stays simple.
        StringBuilder sb = new StringBuilder();
        sb.append("all 3 allocation strategies failed");
        for (int i = 0; i < errs.length; i++) {
            sb.append("; s").append(i + 1).append("=").append(errs[i]);
        }
        return new AllocResult(null, sb.toString(), errs);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Typed array allocation
    // ═══════════════════════════════════════════════════════════════════════

    private static final Map<String, Class<?>> ARRAY_ELEMENT_CACHE = new HashMap<>();
    private static final Set<String>  ARRAY_ELEMENT_MISSING = new HashSet<>();

    /**
     * Build an object array matching the JVM descriptor {@code className}
     * (e.g. {@code [Lfoo.Bar;}, {@code [[Ljava.util.List;}).
     * Falls back to {@code Object[]} if the element class cannot be resolved.
     * Delegates descriptor parsing to {@link #classFromDescriptor(String)}.
     */
    public static Object[] allocateObjectArray(String className, int length) {
        if (ARRAY_ELEMENT_MISSING.contains(className)) return new Object[length];
        Class<?> resolvedElementType = ARRAY_ELEMENT_CACHE.get(className);
        if (resolvedElementType == null) {
            try {
                Class<?> arrayType = classFromDescriptor(className);
                if (arrayType == null || !arrayType.isArray()) {
                    ARRAY_ELEMENT_MISSING.add(className);
                    return new Object[length];
                }
                resolvedElementType = arrayType.getComponentType();
                ARRAY_ELEMENT_CACHE.put(className, resolvedElementType);
            } catch (Throwable t) {
                ARRAY_ELEMENT_MISSING.add(className);
                return new Object[length];
            }
        }
        try {
            return (Object[]) java.lang.reflect.Array.newInstance(resolvedElementType, length);
        } catch (Throwable t) {
            return new Object[length];
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Array set safe
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Store {@code value} at {@code index}. ArrayStoreException is now
     * catchable in the Wasm GC backend.
     */
    public static void arraySetSafe(Object[] arr, int index, Object value) {
        try {
            arr[index] = value;
        } catch (Throwable ignored) {
            // ArrayStoreException is now catchable (Wasm GC fix)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Field lookup and write
    // ═══════════════════════════════════════════════════════════════════════

    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new IdentityHashMap<>();

    /** All fields per name including shadowed (parent) fields. */
    private static final Map<Class<?>, Map<String, Field[]>> ALL_FIELDS_CACHE = new IdentityHashMap<>();

    /** Look up an instance field (subclass wins on shadowed names). */
    private static Field findField(Class<?> cls, String fieldName) {
        return lookupField(cls, fieldName);
    }

    private static Field lookupField(Class<?> cls, String fieldName) {
        Map<String, Field> map = FIELD_CACHE.get(cls);
        if (map == null) {
            buildFieldCaches(cls);
            map = FIELD_CACHE.get(cls);
        }
        return map.get(fieldName);
    }

    private static void buildFieldCaches(Class<?> cls) {
        if (FIELD_CACHE.containsKey(cls)) return;

        Map<String, Field> primaryMap = new HashMap<>();
        Map<String, java.util.ArrayList<Field>> tempListMap = new HashMap<>();

        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getName();
                try { f.setAccessible(true); } catch (Throwable ignored) {}
                if (!primaryMap.containsKey(n)) {
                    primaryMap.put(n, f);
                }
                tempListMap.computeIfAbsent(n, k -> new java.util.ArrayList<>()).add(f);
            }
            c = c.getSuperclass();
        }

        FIELD_CACHE.put(cls, primaryMap);

        Map<String, Field[]> allMap = new HashMap<>();
        for (Map.Entry<String, java.util.ArrayList<Field>> e : tempListMap.entrySet()) {
            allMap.put(e.getKey(), e.getValue().toArray(new Field[0]));
        }
        ALL_FIELDS_CACHE.put(cls, allMap);
    }

    /**
     * Find a field with the given name whose declared type matches
     * {@code knownType}, searching through all fields including shadowed
     * parent fields. Returns null if no match.
     */
    private static Field findFieldByType(Class<?> cls, String fieldName, Class<?> knownType) {
        buildFieldCaches(cls);
        Map<String, Field[]> allMap = ALL_FIELDS_CACHE.get(cls);
        if (allMap == null) return null;
        Field[] candidates = allMap.get(fieldName);
        if (candidates == null) return null;
        for (Field f : candidates) {
            try {
                if (f.getType() == knownType) return f;
            } catch (Throwable ignored) {}
        }
        for (Field f : candidates) {
            try {
                if (f.getType().getName().equals(knownType.getName())) return f;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Find a field with the given name whose declared type is assignable
     * from the value's type, searching shadowed parent fields.
     */
    private static Field findCompatibleField(Class<?> cls, String fieldName, Object value) {
        if (value == null) return null;
        buildFieldCaches(cls);
        Map<String, Field[]> allMap = ALL_FIELDS_CACHE.get(cls);
        if (allMap == null) return null;
        Field[] candidates = allMap.get(fieldName);
        if (candidates == null || candidates.length <= 1) return null;
        for (Field f : candidates) {
            try {
                Class<?> ft = f.getType();
                if (ft.isInstance(value)) return f;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Summary of the cached fields visible for {@code cls}.  Used to enrich
     * "field not found" diagnostics — when reflection returns an incomplete
     * set, the error sample now shows which names ARE visible, so a missing
     * name can be recognised at a glance instead of requiring a rebuild with
     * extra printouts.
     */
    private static String visibleFieldsSummary(Class<?> cls) {
        Map<String, Field> map = FIELD_CACHE.get(cls);
        if (map == null) {
            // Force population so the diagnostic is complete.
            lookupField(cls, "");
            map = FIELD_CACHE.get(cls);
        }
        if (map == null || map.isEmpty()) return "<none>";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String k : map.keySet()) {
            if (n++ > 0) sb.append(',');
            sb.append(k);
            if (n >= 24) { sb.append(",…"); break; }
        }
        return sb.toString();
    }

    /** Track how many ChunkSet/HashedChunkSet diagnostics we've printed (limit noise). */
    private static int chunkSetDiagCount = 0;
    private static final int CHUNKSET_DIAG_MAX = 500;

    /**
     * Write {@code value} into {@code obj.fieldName}, using the schema type
     * descriptor when available to determine the field type even when
     * {@code getDeclaredFields()} returns empty for non-reflectable classes.
     * Falls back to the classic {@link #setField(Object, String, Object)}
     * when {@code schemaTypeDescriptor} is null.
     */
    public static void setField(Object obj, String fieldName, Object value,
                                String schemaTypeDescriptor) {
        if (schemaTypeDescriptor != null) {
            Class<?> schemaType = classFromDescriptor(schemaTypeDescriptor);
            if (schemaType != null) {
                setFieldWithKnownType(obj, fieldName, value, schemaType);
                return;
            }
        }
        setField(obj, fieldName, value);
    }

    /**
     * Write {@code value} into {@code obj.fieldName}, coercing primitive
     * wrappers and aliased primitive arrays where needed. Any trap from
     * {@code Field.set} is swallowed — a missing/mismatched field is better
     * skipped than fatal, since the rest of the graph can still be useful.
     */
    public static void setField(Object obj, String fieldName, Object value) {
        String clsName = obj.getClass().getName();
        Field f = lookupField(obj.getClass(), fieldName);

        // If the primary field's type is incompatible with the value,
        // check shadowed parent fields (e.g. Seq.moves vs Moves.moves).
        if (f != null && value != null) {
            try {
                Class<?> ft = f.getType();
                if (!ft.isPrimitive() && !ft.isInstance(value)
                    && !ft.isArray()) {
                    Field shadowed = findCompatibleField(obj.getClass(), fieldName, value);
                    if (shadowed != null) f = shadowed;
                }
            } catch (Throwable ignored) {}
        }

        if (f == null) {
            // Enrich the "field not found" diagnostic with
            //   - declaredType: the value's class so readers can eyeball type
            //     mismatches vs the class that genuinely owns a field of this name
            //   - runtimeType: summary of the reflection-visible fields on the
            //     target class (so it's obvious the field is absent, not just mislaid)
            //   - error: the literal marker "no-such-field" — the kind encodes
            //     the reason but we keep the error column machine-readable
            String runtimeSummary = "known=[" + visibleFieldsSummary(obj.getClass()) + "]";
            String declared = value != null ? value.getClass().getName() : "null";
            if (diag != null) diag.record("setFieldFail", clsName, fieldName,
                declared, runtimeSummary);
            return;
        }
        boolean isFinal = Modifier.isFinal(f.getModifiers());
        if (isFinal && diag != null && diag.isDebug()) {
            diag.record("setFieldFail", clsName, fieldName, "final", null);
        }

        // Targeted diagnostics for ChunkSet fields
        if (diag != null && diag.isDebug() && chunkSetDiagCount < CHUNKSET_DIAG_MAX) {
            if ("main.collections.ChunkSet".equals(clsName)) {
                chunkSetDiagCount++;
                if ("words".equals(fieldName) && value != null) {
                    try {
                        int len = java.lang.reflect.Array.getLength(value);
                        diag.record("setFieldFail", clsName, fieldName,
                            "long[]", value.getClass().getName());
                    } catch (Throwable t) {
                        diag.record("setFieldFail", clsName, fieldName,
                            "long[]", value.getClass().getName());
                    }
                } else if ("chunkSize".equals(fieldName)) {
                    diag.record("setFieldFail", clsName, fieldName,
                        String.valueOf(value), null);
                } else if ("chunkMask".equals(fieldName)) {
                    diag.record("setFieldFail", clsName, fieldName,
                        String.valueOf(value), null);
                }
            }
            if (clsName.contains("HashedChunkSet") && "internalState".equals(fieldName)) {
                chunkSetDiagCount++;
                diag.record("setFieldFail", clsName, fieldName,
                    (value == null) ? "null" : value.getClass().getName(), null);
            }
        }

        Object converted = null;
        try {
            Class<?> ft = f.getType();
            converted = coerce(ft, value);
            if (ft.isArray() && converted != null && converted.getClass().getComponentType() == null) {
                try {
                    converted = tryConvertListToArray(converted, ft.getComponentType());
                } catch (NoSuchMethodException notListLike) {
                    // not a list-like value — proceed with Field.set()
                } catch (Throwable listErr) {
                    if (diag != null) diag.record("incompatible", obj.getClass().getName(),
                        fieldName, ft.getName(), converted.getClass().getName());
                    return;
                }
            }
            try {
                f.set(obj, converted);
            } catch (ClassCastException e) {
                Object coerced = coerce(ft, converted);
                try {
                    f.set(obj, coerced);
                } catch (ClassCastException e2) {
                    if (diag != null) diag.record("setFieldFail", obj.getClass().getName(),
                        fieldName, ft.getName(),
                        converted != null ? converted.getClass().getName() : "null");
                }
            }

        } catch (Throwable t) {
            if (diag != null) diag.record("setFieldFail", obj.getClass().getName(),
                fieldName,
                converted != null ? converted.getClass().getName() : "null",
                t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * Same as {@link #setField} but uses {@code knownType} as the declared
     * field type instead of querying {@code Field.getType()}.  This bypasses
     * the broken {@code getDeclaredFields()} path on non-reflectable TeaVM
     * classes — the Field object is still required for {@code Field.set()},
     * but the schema type provides correct coercion and compatibility checks
     * even when reflection metadata is unavailable.
     */
    private static void setFieldWithKnownType(Object obj, String fieldName,
                                               Object value, Class<?> knownType) {
        String clsName = obj.getClass().getName();
        Field f = lookupField(obj.getClass(), fieldName);

        // If the primary field's type doesn't match the schema type,
        // check shadowed parent fields (e.g. Seq.moves vs Moves.moves).
        if (f != null && f.getType() != knownType) {
            try {
                if (!f.getType().getName().equals(knownType.getName())) {
                    Field shadowed = findFieldByType(obj.getClass(), fieldName, knownType);
                    if (shadowed != null) f = shadowed;
                }
            } catch (Throwable ignored) {}
        }

        if (f == null) {
            String runtimeSummary = "known=[" + visibleFieldsSummary(obj.getClass()) + "]";
            if (diag != null) diag.record("setFieldFail", clsName, fieldName,
                knownType.getName(), runtimeSummary);
            return;
        }
        boolean isFinal = Modifier.isFinal(f.getModifiers());
        if (isFinal && diag != null && diag.isDebug()) {
            diag.record("setFieldFail", clsName, fieldName, "final", null);
        }

        Object converted = null;
        try {
            converted = coerce(knownType, value);
            if (knownType.isArray() && converted != null && converted.getClass().getComponentType() == null) {
                try {
                    converted = tryConvertListToArray(converted, knownType.getComponentType());
                } catch (NoSuchMethodException notListLike) {
                    // not a list-like value — proceed with Field.set()
                } catch (Throwable listErr) {
                    if (diag != null) diag.record("incompatible", clsName,
                        fieldName, knownType.getName(), converted.getClass().getName());
                    return;
                }
            }
            try {
                f.set(obj, converted);
            } catch (ClassCastException e) {
                Object coerced = coerce(knownType, converted);
                try {
                    f.set(obj, coerced);
                } catch (ClassCastException e2) {
                    if (diag != null) diag.record("setFieldFail", clsName,
                        fieldName, knownType.getName(),
                        converted != null ? converted.getClass().getName() : "null");
                }
            }

        } catch (Throwable t) {
            if (diag != null) diag.record("setFieldFail", clsName,
                fieldName, knownType.getName(),
                value != null ? value.getClass().getName() : "null");
        }
    }

    /**
     * Resolve a JVM field descriptor (JLS 4.3) to its {@code Class<?>}.
     * Handles primitives, object types ({@code Lpkg/Cls;}), and
     * multi-dimensional arrays ({@code [[I}, {@code [Lpkg/Cls;}).
     * Returns {@code null} for unresolvable descriptors.
     */
    public static Class<?> classFromDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return null;
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
                return java.lang.reflect.Array.newInstance(componentType, 0).getClass();
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

    private static Object coerce(Class<?> ft, Object value) {
        if (value == null) return null;

        if (ft == int.class) {
            if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
            if (value instanceof Boolean) return Integer.valueOf(((Boolean) value) ? 1 : 0);
        } else if (ft == long.class) {
            if (value instanceof Number) return Long.valueOf(((Number) value).longValue());
        } else if (ft == double.class) {
            if (value instanceof Number) return Double.valueOf(((Number) value).doubleValue());
        } else if (ft == float.class) {
            if (value instanceof Number) return Float.valueOf(((Number) value).floatValue());
        } else if (ft == boolean.class) {
            if (value instanceof Boolean) return value;
            if (value instanceof Number) return Boolean.valueOf(((Number) value).intValue() != 0);
        } else if (ft == short.class) {
            if (value instanceof Number) return Short.valueOf(((Number) value).shortValue());
        } else if (ft == byte.class) {
            if (value instanceof Number) return Byte.valueOf(((Number) value).byteValue());
        } else if (ft == char.class) {
            if (value instanceof Character) return value;
            if (value instanceof Number) return Character.valueOf((char) ((Number) value).intValue());
        }
        return value;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Ludii-specific post-repair — ChunkSet.wordsInUse (field cache)
    // ═══════════════════════════════════════════════════════════════════════

    private static Field chunkSetWordsField;
    private static Field chunkSetWordsInUseField;
    private static boolean chunkSetFieldsResolved;

    /**
     * Recomputes the {@code wordsInUse} counter on a freshly-deserialized
     * ChunkSet. Ludii relies on this counter for correctness, but it is
     * derived state that we don't serialise directly. Field references are
     * resolved once and cached.  This method is safe to call when
     * ChunkSet is not on the classpath — it will simply return.
     */
    public static void repairChunkSetWordsInUse(Object obj) {
        try {
            Class<?> chunkSetClass = Class.forName("main.collections.ChunkSet");
            if (!chunkSetClass.isInstance(obj)) return;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // ChunkSet not on classpath — skip
            return;
        }
        try {
            if (!chunkSetFieldsResolved) {
                chunkSetFieldsResolved = true;
                Class<?> c = obj.getClass();
                while (c != null && c != Object.class) {
                    for (Field f : c.getDeclaredFields()) {
                        if ("words".equals(f.getName())) chunkSetWordsField = f;
                        else if ("wordsInUse".equals(f.getName())) chunkSetWordsInUseField = f;
                    }
                    if (chunkSetWordsField != null && chunkSetWordsInUseField != null) break;
                    c = c.getSuperclass();
                }
                if (chunkSetWordsField != null) {
                    try { chunkSetWordsField.setAccessible(true); } catch (Throwable ignored) {}
                }
                if (chunkSetWordsInUseField != null) {
                    try { chunkSetWordsInUseField.setAccessible(true); } catch (Throwable ignored) {}
                }
            }
            if (chunkSetWordsField == null || chunkSetWordsInUseField == null) {
                if (diag != null) diag.record("setFieldFail",
                    obj.getClass().getName(), "wordsInUse",
                    "long[]", "repair field-lookup-failed: words=" + (chunkSetWordsField != null)
                    + " wordsInUse=" + (chunkSetWordsInUseField != null));
                return;
            }
            Object words = chunkSetWordsField.get(obj);
            if (words instanceof long[]) {
                long[] data = (long[]) words;
                int n = data.length;
                while (n > 0 && data[n - 1] == 0L) n--;
                chunkSetWordsInUseField.set(obj, Integer.valueOf(n));
            } else if (words instanceof int[]) {
                int[] data = (int[]) words;
                int n = data.length;
                while (n > 0 && data[n - 1] == 0) n--;
                chunkSetWordsInUseField.set(obj, Integer.valueOf(n));
            }
        } catch (Throwable t) {
            if (diag != null) diag.record("setFieldFail",
                obj.getClass().getName(), "wordsInUse",
                "long[]", "repair-threw: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static Object tryConvertListToArray(Object converted, Class<?> arrayComponentType)
            throws ReflectiveOperationException {
        java.lang.reflect.Method sizeMethod = converted.getClass().getMethod("size");
        java.lang.reflect.Method getMethod = converted.getClass().getMethod("get", int.class);
        int listSize = ((Number) sizeMethod.invoke(converted)).intValue();
        Object newArray = java.lang.reflect.Array.newInstance(arrayComponentType, listSize);
        for (int i = 0; i < listSize; i++) {
            java.lang.reflect.Array.set(newArray, i, getMethod.invoke(converted, i));
        }
        return newArray;
    }
}
