/*
 *  Copyright 2023 Alexey Andreev.
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
"use strict";

let $rt_enumConstants = cls => {
    let meta = cls[$rt_meta];
    if (meta.resolvedEnumConstants === null) {
        let result = meta.enumConstants();
        meta.resolvedEnumConstants = result !== null ? result : [];
    }
    return meta.resolvedEnumConstants;
};

let $rt_isInstance = (obj, cls) => obj instanceof $rt_objcls() && !!obj.constructor[$rt_meta]
    && $rt_isAssignable(obj.constructor, cls);
let $rt_isAssignable = (from, to) => {
    if (from === to) {
        return true;
    }
    let map = from[$rt_meta].assignableCache;
    if (map === null) {
        map = new Map();
        from[$rt_meta].assignableCache = map;
    }
    let cachedResult = map.get(to);
    if (typeof cachedResult !== 'undefined') {
        return cachedResult;
    }
    if (to[$rt_meta].itemType !== null) {
        let result = from[$rt_meta].itemType !== null
                && $rt_isAssignable(from[$rt_meta].itemType, to[$rt_meta].itemType);
        map.set(to, result);
        return result;
    }
    let parent = from[$rt_meta].parent;
    if (parent !== null && parent !== from) {
        if ($rt_isAssignable(parent, to)) {
            map.set(to, true);
            return true;
        }
    }
    let superinterfaces = from[$rt_meta].superinterfaces;
    for (let i = 0; i < superinterfaces.length; i = (i + 1) | 0) {
        if ($rt_isAssignable(superinterfaces[i], to)) {
            map.set(to, true);
            return true;
        }
    }
    map.set(to, false);
    return false;
}
let $rt_castToInterface = (obj, cls) => {
    if (obj !== null && !$rt_isInstance(obj, cls)) {
        $rt_throwCCE();
    }
    return obj;
}
let $rt_castToClass = (obj, cls) => {
    if (obj !== null && !(obj instanceof cls)) {
        $rt_throwCCE();
    }
    return obj;
}
let $rt_instanceOfOrNull = (obj, cls) => obj === null || obj instanceof cls;

let $rt_callDefaultConstructor = (cls, obj) => {
    let constructor = cls[$rt_meta].constructor;
    if (constructor === null) {
        return false;
    }
    constructor(obj);
    return true;
}

let $rt_rawAlloc = classInfo => {
    // classInfo should be the JS constructor function for the class.
    // The TeaVM JS backend represents ClassInfo objects as constructor functions.
    // Raw allocation: create object with correct prototype without calling constructor.
    if (typeof classInfo === "function" && classInfo.prototype) {
        return Object.create(classInfo.prototype);
    }
    // Fallback: try $rt_cls to get the actual constructor
    try {
        let cls = classInfo && classInfo.classObject ? classInfo.classObject() : null;
        if (typeof cls === "function" && cls.prototype) {
            return Object.create(cls.prototype);
        }
    } catch (e) { /* ignore */ }
    return null;
}

let $rt_getFieldValue = (field, obj) => {
    if (typeof field.reader !== "function") return null;
    try {
        let typeMeta = field.type[$rt_meta];
        if (!typeMeta) return null;
        return typeMeta.valueToObject(field.reader(obj));
    } catch (e) { return null; }
}
let $rt_setFieldValue = (field, obj, value) => {
    if (typeof field.writer !== "function") {
        throw new Error("No writer for field: " + (field.name || "unknown"));
    }
    try {
        let typeMeta = field.type[$rt_meta];
        if (!typeMeta) {
            throw new Error("No type metadata for field: " + (field.name || "unknown"));
        }
        field.writer(obj, typeMeta.objectToValue(value));
    } catch (e) {
        throw e;
    }
}
let $rt_callMethod = (method, instance, args) => {
    let argsToPass = [];
    let isStatic = (method.modifiers & 8) !== 0;
    let isCalledDirectly = method.calledDirectly;
    if (isCalledDirectly) {
        argsToPass.push(instance);
    }
    for (let i = 0; i < args.data.length; ++i) {
        let paramMeta = method.parameterTypes[i][$rt_meta];
        if (!paramMeta) {
            throw new Error("No parameter type metadata for method: " + (method.name || "unknown") + " param " + i);
        }
        argsToPass.push(paramMeta.objectToValue(args.data[i]));
    }
    let caller = isStatic || isCalledDirectly ? method.caller : method.caller(instance);
    let result = caller.apply(instance, argsToPass);
    let returnMeta = method.returnType[$rt_meta];
    if (!returnMeta) {
        throw new Error("No return type metadata for method: " + (method.name || "unknown"));
    }
    return returnMeta.valueToObject(result);
}