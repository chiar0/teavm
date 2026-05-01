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
package org.teavm.backend.wasm.generate.methods;

import java.util.List;
import org.teavm.backend.wasm.generate.classes.WasmGCClassInfoProvider;
import org.teavm.backend.wasm.model.WasmFunctionType;
import org.teavm.backend.wasm.model.WasmLocal;
import org.teavm.backend.wasm.model.WasmStructure;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.expression.WasmCallReference;
import org.teavm.backend.wasm.model.expression.WasmBlock;
import org.teavm.backend.wasm.model.expression.WasmCast;
import org.teavm.backend.wasm.model.expression.WasmCastBranch;
import org.teavm.backend.wasm.model.expression.WasmCastCondition;
import org.teavm.backend.wasm.model.expression.WasmDrop;
import org.teavm.backend.wasm.model.expression.WasmExpression;
import org.teavm.backend.wasm.model.expression.WasmGetLocal;
import org.teavm.backend.wasm.model.expression.WasmNullConstant;
import org.teavm.backend.wasm.model.expression.WasmPop;
import org.teavm.backend.wasm.model.expression.WasmStructGet;
import org.teavm.backend.wasm.model.expression.WasmUnreachable;
import org.teavm.backend.wasm.vtable.WasmGCVirtualTableProvider;
import org.teavm.model.MethodReference;

public class WasmGCVirtualCallGenerator {
    private WasmGCVirtualTableProvider virtualTables;
    private WasmGCClassInfoProvider classInfoProvider;

    public WasmGCVirtualCallGenerator(WasmGCVirtualTableProvider virtualTables,
            WasmGCClassInfoProvider classInfoProvider) {
        this.virtualTables = virtualTables;
        this.classInfoProvider = classInfoProvider;
    }

    public WasmExpression generate(MethodReference method, boolean suspending, WasmLocal instance,
            List<WasmExpression> arguments) {
        var vtable = virtualTables.lookup(method.getClassName());
        if (vtable == null) {
            if (method.getName().equals("clone") && method.parameterCount() == 0) {
                return generateCloneViaClassInfo(instance);
            }
            return new WasmUnreachable();
        }

        // For interface VTs, redirect to the non-interface ancestor's VT
        // so the ref.cast targets a VT struct that's a supertype of all concrete VTs
        var dispatchVT = vtable;
        if (vtable.isFakeInterfaceRepresentative()) {
            var ancestor = vtable.closestNonInterfaceAncestor();
            if (ancestor != null) {
                dispatchVT = ancestor;
            }
        }

        var entry = dispatchVT.entry(method.getDescriptor());
        if (entry == null) {
            return new WasmUnreachable();
        }

        var dispatchClassInfo = classInfoProvider.getClassInfo(dispatchVT.getClassName());
        var vtableStruct = dispatchClassInfo.getVirtualTableStructure();
        if (vtableStruct == null) {
            return new WasmUnreachable();
        }

        var objectClass = classInfoProvider.getClassInfo("java.lang.Object");

        WasmExpression classRef = new WasmStructGet(objectClass.getStructure(),
                new WasmGetLocal(instance), WasmGCClassInfoProvider.VT_FIELD_OFFSET);
        var index = WasmGCClassInfoProvider.VIRTUAL_METHOD_OFFSET + entry.getIndex();

        classRef = new WasmCast(classRef, vtableStruct.getNonNullReference());

        var functionRef = new WasmStructGet(vtableStruct, classRef, index);
        var functionTypeRef = (WasmType.CompositeReference) vtableStruct.getFields().get(index).getUnpackedType();
        var invoke = new WasmCallReference(functionRef, (WasmFunctionType) functionTypeRef.composite);
        invoke.setSuspensionPoint(suspending);
        WasmExpression instanceRef = new WasmGetLocal(instance);
        var instanceType = (WasmType.CompositeReference) instance.getType();
        var instanceStruct = (WasmStructure) instanceType.composite;
        var expectedInstanceClassStruct = dispatchClassInfo.getStructure();
        if (!expectedInstanceClassStruct.isSupertypeOf(instanceStruct)) {
            var check = new WasmBlock(false);
            var targetType = expectedInstanceClassStruct.getNonNullReference();
            check.setType(targetType.asBlock());
            check.getBody().add(new WasmCastBranch(WasmCastCondition.SUCCESS, instanceRef, instanceType,
                    targetType, check));
            check.getBody().add(new WasmDrop(new WasmPop(instanceType)));
            check.getBody().add(new WasmUnreachable());
            instanceRef = check;
        }

        invoke.getArguments().add(instanceRef);
        invoke.getArguments().addAll(arguments);
        return invoke;
    }

    private WasmExpression generateCloneViaClassInfo(WasmLocal instance) {
        var objectInfo = classInfoProvider.getClassInfo("java.lang.Object");
        var objectStruct = objectInfo.getStructure();
        var classInfoStruct = classInfoProvider.reflectionTypes().classInfo();

        var vt = new WasmStructGet(objectStruct, new WasmGetLocal(instance),
                WasmGCClassInfoProvider.VT_FIELD_OFFSET);
        var cls = new WasmStructGet(objectInfo.getVirtualTableStructure(), vt,
                WasmGCClassInfoProvider.CLASS_FIELD_OFFSET);
        var functionRef = new WasmStructGet(classInfoStruct.structure(), cls,
                classInfoStruct.cloneFunctionIndex());
        var call = new WasmCallReference(functionRef, classInfoStruct.cloneFunctionType());
        call.getArguments().add(new WasmGetLocal(instance));
        return call;
    }
}
