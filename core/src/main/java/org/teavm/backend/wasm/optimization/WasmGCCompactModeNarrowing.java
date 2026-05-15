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
import org.teavm.backend.wasm.model.WasmBlockType;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmModule;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.expression.WasmBlock;
import org.teavm.backend.wasm.model.expression.WasmBranch;
import org.teavm.backend.wasm.model.expression.WasmBreak;
import org.teavm.backend.wasm.model.expression.WasmCast;
import org.teavm.backend.wasm.model.expression.WasmConditional;
import org.teavm.backend.wasm.model.expression.WasmDefaultExpressionVisitor;
import org.teavm.backend.wasm.model.expression.WasmExpression;
import org.teavm.backend.wasm.model.expression.WasmGetLocal;
import org.teavm.backend.wasm.model.expression.WasmReturn;
import org.teavm.backend.wasm.model.expression.WasmTry;

/**
 * In compact mode, local 0 (this) is typed as anyref. When a block body ends
 * with [local.get 0, br target] where the br has no explicit result, the
 * anyref on the stack flows to the target block which expects a specific
 * struct type. V8 rejects this. This pass inserts ref.cast narrowing at
 * those points by looking at the break's TARGET block type.
 */
public class WasmGCCompactModeNarrowing extends WasmDefaultExpressionVisitor {
    private WasmFunction currentFunction;

    public static void apply(WasmModule module) {
        var visitor = new WasmGCCompactModeNarrowing();
        for (var function : module.functions) {
            if (function.getBody() == null || function.getBody().isEmpty()) {
                continue;
            }
            var paramTypes = function.getType().getParameterTypes();
            if (paramTypes.isEmpty()) {
                continue;
            }
            var firstParam = paramTypes.get(0);
            if (!(firstParam instanceof WasmType.SpecialReference)) {
                continue;
            }
            visitor.currentFunction = function;
            for (var part : function.getBody()) {
                part.acceptVisitor(visitor);
            }
        }
    }

    @Override
    public void visit(WasmBlock expression) {
        super.visit(expression);
        narrowBodyIfNeeded(expression.getBody());
    }

    @Override
    public void visit(WasmConditional expression) {
        super.visit(expression);
        narrowBodyIfNeeded(expression.getThenBlock().getBody());
        narrowBodyIfNeeded(expression.getElseBlock().getBody());
    }

    @Override
    public void visit(WasmTry expression) {
        super.visit(expression);
        narrowBodyIfNeeded(expression.getBody());
        for (var catchClause : expression.getCatches()) {
            narrowBodyIfNeeded(catchClause.getBody());
        }
    }

    private void narrowBodyIfNeeded(List<WasmExpression> body) {
        if (body.size() < 2) {
            return;
        }
        var last = body.get(body.size() - 1);

        WasmType.Reference targetRef = null;
        if (last instanceof WasmBreak) {
            targetRef = getBlockOutputType(((WasmBreak) last).getTarget());
        } else if (last instanceof WasmBranch) {
            targetRef = getBlockOutputType(((WasmBranch) last).getTarget());
        } else if (last instanceof WasmReturn) {
            var returnTypes = currentFunction.getType().getReturnTypes();
            if (returnTypes.size() == 1 && returnTypes.get(0) instanceof WasmType.CompositeReference) {
                targetRef = (WasmType.Reference) returnTypes.get(0);
            }
        } else {
            return;
        }
        if (targetRef == null) {
            return;
        }

        var valueExpr = body.get(body.size() - 2);
        if (!(valueExpr instanceof WasmGetLocal)) {
            return;
        }
        var getLocal = (WasmGetLocal) valueExpr;
        if (!isWideRef(getLocal.getLocal().getType())) {
            return;
        }

        var cast = new WasmCast(valueExpr, targetRef);
        body.set(body.size() - 2, cast);
    }

    private WasmType.Reference getBlockOutputType(WasmBlock block) {
        var blockType = block.getType();
        if (blockType == null) {
            return null;
        }
        WasmType outputType = null;
        if (blockType instanceof WasmBlockType.Value) {
            outputType = ((WasmBlockType.Value) blockType).type;
        } else if (blockType instanceof WasmBlockType.Function) {
            var outputs = blockType.getOutputTypes();
            if (outputs.size() == 1) {
                outputType = outputs.get(0);
            }
        }
        if (outputType instanceof WasmType.CompositeReference) {
            return (WasmType.Reference) outputType;
        }
        return null;
    }

    private boolean isWideRef(WasmType type) {
        if (!(type instanceof WasmType.SpecialReference)) {
            return false;
        }
        var kind = ((WasmType.SpecialReference) type).kind;
        return kind == WasmType.SpecialReferenceKind.ANY
                || kind == WasmType.SpecialReferenceKind.EQ
                || kind == WasmType.SpecialReferenceKind.STRUCT
                || kind == WasmType.SpecialReferenceKind.ARRAY;
    }
}
