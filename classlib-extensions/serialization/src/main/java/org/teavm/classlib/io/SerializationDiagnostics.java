package org.teavm.classlib.io;

import java.util.*;

public final class SerializationDiagnostics {

    private SerializationDiagnostics() {}

    // -----------------------------------------------------------------------
    // Kinds
    // -----------------------------------------------------------------------

    public enum Kind {
        setFieldFail,
        incompatible,
        classLoadFail,
        allocateFail,
        zeroFieldClass,
        containerPutFail,
        drainFrames,
        truncations,
        manifestMiss,
        schemaDrift,
        structuralAllocFail
    }

    // -----------------------------------------------------------------------
    // Context record
    // -----------------------------------------------------------------------

    public static class Ctx {
        public final String className;
        public final String fieldName;
        public final String declaredType;
        public final String runtimeType;
        public final int position;
        public final String error;

        public Ctx(String className, String fieldName, String declaredType,
                   String runtimeType, int position, String error) {
            this.className = className;
            this.fieldName = fieldName;
            this.declaredType = declaredType;
            this.runtimeType = runtimeType;
            this.position = position;
            this.error = error;
        }
    }

    // -----------------------------------------------------------------------
    // Strict mode
    // -----------------------------------------------------------------------

    private static boolean strict = false;

    public static void setStrict(boolean v) {
        strict = v;
    }

    public static boolean isStrict() {
        return strict;
    }

    // -----------------------------------------------------------------------
    // Warnings (always emitted, not gated by isDebug)
    // -----------------------------------------------------------------------

    public static void warn(String category, String message) {
        System.err.println("[" + category + "] " + message);
    }

    public static void warn(String category, String message, Throwable t) {
        System.err.println("[" + category + "] " + message + " \u2014 " + t.getClass().getName() + ": " + t.getMessage());
    }

    // -----------------------------------------------------------------------
    // Debug mode (cached system property)
    // -----------------------------------------------------------------------

    private static Boolean debugCache = null;

    public static boolean isDebug() {
        if (debugCache == null) {
            try { debugCache = Boolean.getBoolean("teavm.classlib.serdiag.debug"); }
            catch (Throwable t) { debugCache = false; }
        }
        return debugCache;
    }

    public static void setDebug(boolean v) {
        debugCache = v;
    }

    // -----------------------------------------------------------------------
    // Counters + samples
    // -----------------------------------------------------------------------

    private static final int MAX_SAMPLES = 5000;
    private static final Kind[] KINDS = Kind.values();
    private static final int N = KINDS.length;

    @SuppressWarnings("unchecked")
    private static final List<Ctx>[] samples = new ArrayList[N];
    private static final int[] counters = new int[N];

    static {
        for (int i = 0; i < N; i++) {
            samples[i] = new ArrayList<Ctx>();
        }
    }

    // -----------------------------------------------------------------------
    // Record (core)
    // -----------------------------------------------------------------------

    public static void record(Kind kind, Ctx ctx) {
        int idx = kind.ordinal();
        counters[idx]++;
        if (samples[idx].size() < MAX_SAMPLES) {
            samples[idx].add(ctx);
        }
        if (strict) {
            throw new RuntimeException(
                "SerializationDiagnostics strict: " + kind + " " + ctx.className
                + "." + ctx.fieldName + " \u2014 " + ctx.error);
        }
    }

    // -----------------------------------------------------------------------
    // Convenience overloads
    // -----------------------------------------------------------------------

    public static void record(Kind kind, String className) {
        record(kind, new Ctx(className, null, null, null, 0, null));
    }

    public static void record(Kind kind, String className, String error) {
        record(kind, new Ctx(className, null, null, null, 0, error));
    }

    public static void record(Kind kind, String className, String fieldName,
                              String declaredType, String runtimeType) {
        record(kind, new Ctx(className, fieldName, declaredType, runtimeType, 0, null));
    }

    public static void record(Kind kind, String className, String fieldName,
                              String declaredType, String runtimeType,
                              int position, String error) {
        record(kind, new Ctx(className, fieldName, declaredType, runtimeType, position, error));
    }

    // -----------------------------------------------------------------------
    // JSON export
    // -----------------------------------------------------------------------

    public static String getDiagnosticsJSON() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < N; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(KINDS[i].name()).append('"');
            sb.append(':');
            sb.append("{\"count\":").append(counters[i]);
            sb.append(",\"samples\":[");
            List<Ctx> list = samples[i];
            for (int j = 0; j < list.size(); j++) {
                if (j > 0) sb.append(',');
                appendCtx(sb, list.get(j));
            }
            sb.append("]}");
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendCtx(StringBuilder sb, Ctx c) {
        sb.append("{\"className\":");
        appendStr(sb, c.className);
        sb.append(",\"fieldName\":");
        appendStr(sb, c.fieldName);
        sb.append(",\"declaredType\":");
        appendStr(sb, c.declaredType);
        sb.append(",\"runtimeType\":");
        appendStr(sb, c.runtimeType);
        sb.append(",\"position\":").append(c.position);
        sb.append(",\"error\":");
        appendStr(sb, c.error);
        sb.append('}');
    }

    private static void appendStr(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
        } else {
            sb.append('"');
            sb.append(s.replace("\\", "\\\\").replace("\"", "\\\""));
            sb.append('"');
        }
    }

    // -----------------------------------------------------------------------
    // Reset
    // -----------------------------------------------------------------------

    public static void reset() {
        for (int i = 0; i < N; i++) {
            counters[i] = 0;
            samples[i].clear();
        }
    }
}
