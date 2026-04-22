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
package org.teavm.backend.c.generate;

import org.teavm.model.ElementModifier;
import org.teavm.model.MethodReader;
import org.teavm.model.ValueType;
import org.teavm.model.analysis.ClassInitializerInfo;
import org.teavm.reflection.ReflectionDependencyListener;

class MethodReflectionCallerGenerator {
    private final GenerationContext context;
    private CodeWriter writer;
    private IncludeManager includes;
    private final ReflectionDependencyListener reflection;
    private final ClassInitializerInfo classInitInfo;

    MethodReflectionCallerGenerator(GenerationContext context, CodeWriter writer, IncludeManager includes,
            ReflectionDependencyListener reflection, ClassInitializerInfo classInitInfo) {
        this.context = context;
        this.writer = writer;
        this.includes = includes;
        this.reflection = reflection;
        this.classInitInfo = classInitInfo;
    }

    void setWriter(CodeWriter writer) {
        this.writer = writer;
    }

    void setIncludes(IncludeManager includes) {
        this.includes = includes;
    }

    void generateCaller(MethodReader method) {
        var names = context.getNames();
        var callerName = names.forMethod(method.getReference()) + "@caller";

        includes.includePath("reflection.h");
        includes.includePath("reflection_gen.h");
        writer.print("void* ").print(callerName).println("(void* instance, void* argsArray) {").indent();

        var isStatic = method.hasModifier(ElementModifier.STATIC);

        if (!isStatic && classInitInfo.isDynamicInitializer(method.getOwnerName())) {
            var initName = context.getNames().forClassInitializer(method.getOwnerName());
            writer.print(initName).println("();");
        }

        var paramTypes = method.getParameterTypes();
        for (var i = 0; i < paramTypes.length; ++i) {
            var pt = paramTypes[i];
            writer.print("void* arg").print(String.valueOf(i)).print(" = teavm_reflection_getItem(argsArray, ")
                    .print(String.valueOf(i)).println(");");
            if (pt instanceof ValueType.Primitive) {
                switch (((ValueType.Primitive) pt).getKind()) {
                    case BOOLEAN:
                        writer.print("int8_t val").print(String.valueOf(i)).print(" = (int8_t) ")
                                .print("TEAVM_REFLECTION_UNBOX_BOOLEAN(arg").print(String.valueOf(i)).println(");");
                        break;
                    case BYTE:
                        writer.print("int8_t val").print(String.valueOf(i)).print(" = ")
                                .print("TEAVM_REFLECTION_UNBOX_BYTE(arg").print(String.valueOf(i)).println(");");
                        break;
                    case SHORT:
                        writer.print("int16_t val").print(String.valueOf(i)).print(" = ")
                                .print("TEAVM_REFLECTION_UNBOX_SHORT(arg").print(String.valueOf(i)).println(");");
                        break;
                    case CHARACTER:
                        writer.print("uint16_t val").print(String.valueOf(i)).print(" = ")
                                .print("TEAVM_REFLECTION_UNBOX_CHAR(arg").print(String.valueOf(i)).println(");");
                        break;
                    case INTEGER:
                        writer.print("int32_t val").print(String.valueOf(i)).print(" = ")
                                .print("TEAVM_REFLECTION_UNBOX_INT(arg").print(String.valueOf(i)).println(");");
                        break;
                    case LONG:
                        writer.print("int64_t val").print(String.valueOf(i)).print(" = ")
                                .print("TEAVM_REFLECTION_UNBOX_LONG(arg").print(String.valueOf(i)).println(");");
                        break;
                    case FLOAT:
                        writer.print("float val").print(String.valueOf(i)).print(" = ")
                                .print("TEAVM_REFLECTION_UNBOX_FLOAT(arg").print(String.valueOf(i)).println(");");
                        break;
                    case DOUBLE:
                        writer.print("double val").print(String.valueOf(i)).print(" = ")
                                .print("TEAVM_REFLECTION_UNBOX_DOUBLE(arg").print(String.valueOf(i)).println(");");
                        break;
                }
            }
        }

        writer.print("void* result = ").print(names.forMethod(method.getReference())).print("(");
        if (!isStatic) {
            writer.print("instance");
        }
        for (var i = 0; i < paramTypes.length; ++i) {
            if (!isStatic || i > 0) {
                writer.print(", ");
            }
            var pt = paramTypes[i];
            if (pt instanceof ValueType.Primitive) {
                writer.print("val").print(String.valueOf(i));
            } else {
                writer.print("arg").print(String.valueOf(i));
            }
        }
        writer.println(");");

        var returnType = method.getResultType();
        if (returnType == ValueType.VOID) {
            writer.println("return NULL;");
        } else {
            var conv = getBoxingConv(returnType);
            writer.print("return teavm_reflection_box(").print(conv).print(", &result);");
            writer.println();
        }

        writer.outdent().println("}");
    }

    private String getBoxingConv(ValueType type) {
        if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN: return "TEAVM_PRIMITIVE_BOOLEAN";
                case BYTE: return "TEAVM_PRIMITIVE_BYTE";
                case SHORT: return "TEAVM_PRIMITIVE_SHORT";
                case CHARACTER: return "TEAVM_PRIMITIVE_CHAR";
                case INTEGER: return "TEAVM_PRIMITIVE_INT";
                case LONG: return "TEAVM_PRIMITIVE_LONG";
                case FLOAT: return "TEAVM_PRIMITIVE_FLOAT";
                case DOUBLE: return "TEAVM_PRIMITIVE_DOUBLE";
            }
        }
        return "TEAVM_PRIMITIVE_NONE";
    }

    boolean needsCaller(MethodReader method) {
        if (method.getName().equals("<clinit>")) {
            return false;
        }
        var className = method.getOwnerName();
        if (!reflection.getClassesWithReflectableMethods().contains(className)) {
            return false;
        }
        var accessibleMethods = reflection.getAccessibleMethods(className);
        if (accessibleMethods == null) {
            return false;
        }
        var desc = method.getReference().getDescriptor();
        return accessibleMethods.contains(desc);
    }
}
