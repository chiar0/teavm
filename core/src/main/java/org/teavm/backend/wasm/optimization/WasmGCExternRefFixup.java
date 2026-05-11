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
package org.teavm.backend.wasm.optimization;

import java.util.List;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmModule;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.expression.WasmCall;
import org.teavm.backend.wasm.model.expression.WasmDefaultExpressionVisitor;
import org.teavm.backend.wasm.model.expression.WasmExpression;
import org.teavm.backend.wasm.model.expression.WasmExternConversion;
import org.teavm.backend.wasm.model.expression.WasmExternConversionType;
import org.teavm.backend.wasm.render.WasmTypeInference;

/**
 * Post-processing pass that inserts extern.externalize/internalize conversions
 * at call sites where argument types don't match function parameter types.
 * This fixes V8 validation errors when intrinsics generate calls to imported
 * JS functions (which have externref parameters) with Java-typed arguments.
 */
public class WasmGCExternRefFixup extends WasmDefaultExpressionVisitor {
    private final WasmTypeInference typeInference = new WasmTypeInference();
    private int fixesApplied;

    public static int apply(WasmModule module) {
        var fixup = new WasmGCExternRefFixup();
        for (var function : module.functions) {
            if (function.getBody() != null) {
                fixup.processExpressions(function.getBody());
            }
        }
        return fixup.fixesApplied;
    }

    private void processExpressions(List<WasmExpression> expressions) {
        for (int i = 0; i < expressions.size(); i++) {
            expressions.get(i).acceptVisitor(this);
            // The visitor may have replaced the expression via the argument list
        }
    }

    @Override
    public void visit(WasmCall expression) {
        super.visit(expression);
        var function = expression.getFunction();
        if (function == null || function.getType() == null) {
            return;
        }
        var paramTypes = function.getType().getParameterTypes();
        var arguments = expression.getArguments();
        for (int i = 0; i < arguments.size() && i < paramTypes.size(); i++) {
            var expectedType = paramTypes.get(i);
            if (expectedType == WasmType.EXTERN || isExternRef(expectedType)) {
                var arg = arguments.get(i);
                arg.acceptVisitor(typeInference);
                var actualType = typeInference.getSingleResult();
                if (actualType instanceof WasmType.CompositeReference) {
                    arguments.set(i, new WasmExternConversion(WasmExternConversionType.ANY_TO_EXTERN, arg));
                    fixesApplied++;
                }
            }
        }
    }

    private boolean isExternRef(WasmType type) {
        if (type instanceof WasmType.SpecialReference) {
            return ((WasmType.SpecialReference) type).kind == WasmType.SpecialReferenceKind.EXTERN;
        }
        return false;
    }
}
