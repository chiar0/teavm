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
import org.teavm.backend.wasm.model.expression.WasmFloat32Constant;
import org.teavm.backend.wasm.model.expression.WasmFloat64Constant;
import org.teavm.backend.wasm.model.expression.WasmInt32Constant;
import org.teavm.backend.wasm.model.expression.WasmInt64Constant;
import org.teavm.backend.wasm.model.expression.WasmNullConstant;
import org.teavm.backend.wasm.model.expression.WasmUnreachable;

/**
 * Post-processing pass that fixes functions with empty bodies.
 * V8 rejects functions with non-void return types and empty bodies.
 * This can happen when TeaVM's code generation queue doesn't process
 * all entries (e.g., due to missing class info or generation errors).
 */
public class WasmGCEmptyBodyFixup {
    public static int apply(WasmModule module) {
        int fixesApplied = 0;
        for (var function : module.functions) {
            if (function.getBody() != null && function.getBody().isEmpty()) {
                var returnTypes = function.getType().getReturnTypes();
                if (!returnTypes.isEmpty()) {
                    if (returnTypes.size() == 1) {
                        function.getBody().add(defaultValue(returnTypes.get(0)));
                    } else {
                        // Multi-value return: use unreachable (can't produce multiple values)
                        function.getBody().add(new WasmUnreachable());
                    }
                    fixesApplied++;
                }
            }
        }
        return fixesApplied;
    }

    private static org.teavm.backend.wasm.model.expression.WasmExpression defaultValue(WasmType type) {
        if (type == WasmType.INT32) {
            return new WasmInt32Constant(0);
        } else if (type == WasmType.INT64) {
            return new WasmInt64Constant(0);
        } else if (type == WasmType.FLOAT32) {
            return new WasmFloat32Constant(0);
        } else if (type == WasmType.FLOAT64) {
            return new WasmFloat64Constant(0);
        } else if (type instanceof WasmType.Reference) {
            return new WasmNullConstant((WasmType.Reference) type);
        } else {
            return new WasmUnreachable();
        }
    }
}
