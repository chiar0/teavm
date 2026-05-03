/*
 *  Copyright 2026 contributor.
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
package org.teavm.jso.core;

import org.teavm.jso.JSIndexer;
import org.teavm.jso.JSObject;

/**
 * A generic string-keyed property map view of any JS object.
 * Cast any {@link JSObject} to this interface to read named properties
 * via bracket notation ({@code obj[key]}).
 */
public interface JSPropertyMap extends JSObject {
    @JSIndexer
    JSObject get(String key);
}
