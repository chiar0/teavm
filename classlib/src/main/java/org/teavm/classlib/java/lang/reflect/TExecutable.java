/*
 *  Copyright 2025 Alexey Andreev.
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
package org.teavm.classlib.java.lang.reflect;

import org.teavm.classlib.java.lang.TClass;
import org.teavm.classlib.java.lang.TIllegalArgumentException;
import org.teavm.classlib.java.lang.annotation.TAnnotation;
import org.teavm.runtime.reflect.AnnotationInfoUtil;
import org.teavm.runtime.reflect.ClassInfo;
import org.teavm.runtime.reflect.ClassInfoUtil;
import org.teavm.runtime.reflect.MethodInfo;
import org.teavm.runtime.reflect.ModifiersInfo;

public abstract class TExecutable extends TAccessibleObject implements TMember, TGenericDeclaration {
    TClass<?> declaringClass;
    MethodInfo methodInfo;
    private TClass<?>[] parameterTypes;
    private TType[] genericParameterTypes;
    private TAnnotation[] declaredAnnotations;
    private TTypeVariable<?>[] typeParameters;

    TExecutable(TClass<?> declaringClass, MethodInfo methodInfo) {
        this.declaringClass = declaringClass;
        this.methodInfo = methodInfo;
    }

    @Override
    public TClass<?> getDeclaringClass() {
        return declaringClass;
    }

    @Override
    public int getModifiers() {
        return methodInfo.modifiers() & ModifiersInfo.JVM_FLAGS_MASK;
    }

    public TClass<?>[] getParameterTypes() {
        resolveParameterTypes();
        return parameterTypes.clone();
    }

    private void resolveParameterTypes() {
        if (parameterTypes == null) {
            parameterTypes = new TClass<?>[methodInfo.parameterCount()];
            for (var i = 0; i < parameterTypes.length; ++i) {
                parameterTypes[i] = (TClass<?>) (Object) ClassInfoUtil.resolve(methodInfo.parameterType(i))
                        .classObject();
            }
        }
    }

    private TClass<?> parameterType(int index) {
        return parameterTypes[index];
    }

    public int getParameterCount() {
        return methodInfo.parameterCount();
    }

    public TType[] getGenericParameterTypes() {
        if (genericParameterTypes == null) {
            var reflection = methodInfo.reflection();
            if (reflection == null) {
                genericParameterTypes = new TType[0];
            } else {
                genericParameterTypes = new TType[parameterTypes.length];
                var count = reflection.genericParameterTypeCount();
                for (var i = 0; i < count; ++i) {
                    var paramTypeInfo = reflection.genericParameterType(i);
                    genericParameterTypes[i] = paramTypeInfo != null
                            ? TGenericTypeFactory.create(this, paramTypeInfo)
                            : (TClass<?>) (Object) ClassInfoUtil.resolve(methodInfo.parameterType(i)).classObject();
                }
                for (var i = count; i < genericParameterTypes.length; ++i) {
                    genericParameterTypes[i] = (TClass<?>) (Object) ClassInfoUtil.resolve(
                            methodInfo.parameterType(i)).classObject();
                }
            }
        }
        return genericParameterTypes.clone();
    }

    @Override
    public boolean isSynthetic() {
        return (methodInfo.modifiers() & ModifiersInfo.SYNTHETIC) != 0;
    }

    public boolean isVarArgs() {
        return (methodInfo.modifiers() & ModifiersInfo.VARARGS) != 0;
    }

    @Override
    public TAnnotation[] getDeclaredAnnotations() {
        if (declaredAnnotations == null) {
            var reflection = methodInfo.reflection();
            if (reflection == null) {
                declaredAnnotations = new TAnnotation[0];
            } else {
                declaredAnnotations = new TAnnotation[reflection.annotationCount()];
                for (var i = 0; i < declaredAnnotations.length; ++i) {
                    declaredAnnotations[i] = (TAnnotation) AnnotationInfoUtil.createAnnotation(
                            reflection.annotation(i));
                }
            }
        }
        return declaredAnnotations.clone();
    }

    @Override
    public TTypeVariable<?>[] getTypeParameters() {
        if (typeParameters == null) {
            var reflection = methodInfo.reflection();
            if (reflection == null) {
                typeParameters = new TTypeVariable<?>[0];
            } else {
                typeParameters = new TTypeVariable<?>[reflection.typeParameterCount()];
                for (var i = 0; i < typeParameters.length; ++i) {
                    typeParameters[i] = new TTypeVariableImpl(this, reflection.typeParameter(i));
                }
            }
        }
        return typeParameters.clone();
    }

    public TParameter[] getParameters() {
        resolveParameterTypes();
        var params = new TParameter[parameterTypes.length];
        for (var i = 0; i < params.length; ++i) {
            String name = "arg" + i;
            if (methodInfo != null) {
                var nameInfo = methodInfo.parameterName(i);
                if (nameInfo != null) {
                    name = nameInfo.toString();
                }
            }
            params[i] = new TParameter(this, i, name, parameterTypes[i], 0);
        }
        return params;
    }

    public TAnnotation[][] getParameterAnnotations() {
        resolveParameterTypes();
        var reflection = methodInfo.reflection();
        if (reflection == null) {
            var result = new TAnnotation[parameterTypes.length][];
            for (var i = 0; i < result.length; ++i) {
                result[i] = new TAnnotation[0];
            }
            return result;
        }
        var result = new TAnnotation[parameterTypes.length][];
        for (var i = 0; i < result.length; ++i) {
            var count = reflection.parameterAnnotationCount(i);
            result[i] = new TAnnotation[count];
            for (var j = 0; j < count; ++j) {
                result[i][j] = (TAnnotation) AnnotationInfoUtil.createAnnotation(
                        reflection.parameterAnnotation(i, j));
            }
        }
        return result;
    }

    void validateArgs(Object[] args) {
        resolveParameterTypes();
        for (int i = 0; i < args.length; ++i) {
            var parameterType = parameterType(i);
            if (parameterType.getClassInfo().primitiveKind() == ClassInfo.PrimitiveKind.NOT) {
                if (args[i] != null && !parameterType.isInstance(args[i])) {
                    throw new TIllegalArgumentException();
                }
            } else {
                if (args[i] == null) {
                    throw new TIllegalArgumentException();
                }
            }
        }
    }
}
