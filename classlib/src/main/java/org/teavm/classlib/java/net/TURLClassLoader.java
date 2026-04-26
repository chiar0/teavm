/*
 *  Copyright 2024 Alexey Andreev.
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
package org.teavm.classlib.java.net;

import java.net.URL;
import org.teavm.classlib.java.lang.TClassLoader;

/**
 * Stub implementation of {@link java.net.URLClassLoader}.
 * TeaVM compiles all classes AOT at build time — dynamic JAR loading
 * from URLs is not possible. Construction throws unconditionally.
 */
public class TURLClassLoader extends TClassLoader {
    public TURLClassLoader(URL[] urls) {
        super();
        throw new UnsupportedOperationException("URLClassLoader is not supported in TeaVM");
    }

    public TURLClassLoader(URL[] urls, TClassLoader parent) {
        super(parent);
        throw new UnsupportedOperationException("URLClassLoader is not supported in TeaVM");
    }
}
