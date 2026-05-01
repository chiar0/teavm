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
package org.teavm.backend.wasm.generators;

import org.teavm.backend.wasm.generate.classes.WasmGCClassInfoProvider;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmLocal;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.expression.WasmCallReference;
import org.teavm.backend.wasm.model.expression.WasmExpression;
import org.teavm.backend.wasm.model.expression.WasmGetLocal;
import org.teavm.backend.wasm.model.expression.WasmSetLocal;
import org.teavm.backend.wasm.model.expression.WasmStructGet;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;

public class SystemDoArrayCopyGenerator implements WasmGCCustomGenerator {
    @Override
    public void apply(MethodReference method, WasmFunction function, WasmGCCustomGeneratorContext context) {
        var objType = context.typeMapper().mapType(ValueType.object("java.lang.Object"));

        var srcLocal = new WasmLocal(objType, "src");
        var srcPosLocal = new WasmLocal(WasmType.INT32, "srcPos");
        var destLocal = new WasmLocal(objType, "dest");
        var destPosLocal = new WasmLocal(WasmType.INT32, "destPos");
        var lengthLocal = new WasmLocal(WasmType.INT32, "length");
        function.add(srcLocal);
        function.add(srcPosLocal);
        function.add(destLocal);
        function.add(destPosLocal);
        function.add(lengthLocal);

        var classInfoStruct = context.classInfoProvider().reflectionTypes().classInfo();
        var objInfo = context.classInfoProvider().getClassInfo(Object.class.getName());

        // Cache source ClassInfo in a local variable
        var classInfoLocal = new WasmLocal(classInfoStruct.structure().getReference(), "srcClassInfo");
        function.add(classInfoLocal);

        // src.VT (virtual table pointer from object header)
        var getSrc = new WasmGetLocal(srcLocal);
        var srcVT = new WasmStructGet(objInfo.getStructure(), getSrc,
                WasmGCClassInfoProvider.VT_FIELD_OFFSET);

        // src.VT.class (ClassInfo pointer from virtual table)
        var srcClass = new WasmStructGet(objInfo.getVirtualTableStructure(), srcVT,
                WasmGCClassInfoProvider.CLASS_FIELD_OFFSET);

        // Store ClassInfo in local for reuse
        function.getBody().add(new WasmSetLocal(classInfoLocal, srcClass));

        // classInfo.copyArray (function pointer for array copy)
        var copyFn = new WasmStructGet(classInfoStruct.structure(),
                new WasmGetLocal(classInfoLocal), classInfoStruct.copyArrayIndex());

        // call_ref copyArray(classInfo, src, srcPos, dest, destPos, length)
        var call = new WasmCallReference(copyFn, classInfoStruct.copyArrayFunctionType());
        call.getArguments().add(new WasmGetLocal(classInfoLocal));
        call.getArguments().add(new WasmGetLocal(srcLocal));
        call.getArguments().add(new WasmGetLocal(srcPosLocal));
        call.getArguments().add(new WasmGetLocal(destLocal));
        call.getArguments().add(new WasmGetLocal(destPosLocal));
        call.getArguments().add(new WasmGetLocal(lengthLocal));
        function.getBody().add(call);
    }
}
