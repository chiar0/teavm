/*
 *  Copyright 2015 Alexey Andreev.
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
package org.teavm.classlib.java.awt;

import java.util.HashMap;
import java.util.Map;

public class TRenderingHints {

    public static final Key KEY_ANTIALIASING = new Key("Antialiasing");
    public static final Key KEY_RENDERING = new Key("Rendering");
    public static final Key KEY_STROKE_CONTROL = new Key("StrokeControl");
    public static final Key KEY_TEXT_ANTIALIASING = new Key("TextAntialiasing");
    public static final Key KEY_FRACTIONALMETRICS = new Key("FractionalMetrics");
    public static final Key KEY_INTERPOLATION = new Key("Interpolation");
    public static final Key KEY_ALPHA_INTERPOLATION = new Key("AlphaInterpolation");
    public static final Key KEY_COLOR_RENDERING = new Key("ColorRendering");
    public static final Key KEY_DITHERING = new Key("Dithering");

    public static final Object VALUE_ANTIALIAS_ON = "AntialiasOn";
    public static final Object VALUE_ANTIALIAS_OFF = "AntialiasOff";
    public static final Object VALUE_ANTIALIAS_DEFAULT = "AntialiasDefault";
    public static final Object VALUE_RENDER_QUALITY = "RenderQuality";
    public static final Object VALUE_RENDER_SPEED = "RenderSpeed";
    public static final Object VALUE_RENDER_DEFAULT = "RenderDefault";
    public static final Object VALUE_STROKE_PURE = "StrokePure";
    public static final Object VALUE_STROKE_NORMALIZE = "StrokeNormalize";
    public static final Object VALUE_STROKE_DEFAULT = "StrokeDefault";
    public static final Object VALUE_TEXT_ANTIALIAS_ON = "TextAntialiasOn";
    public static final Object VALUE_TEXT_ANTIALIAS_OFF = "TextAntialiasOff";
    public static final Object VALUE_TEXT_ANTIALIAS_DEFAULT = "TextAntialiasDefault";
    public static final Object VALUE_INTERPOLATION_NEAREST_NEIGHBOR = "InterpolationNearest";
    public static final Object VALUE_INTERPOLATION_BILINEAR = "InterpolationBilinear";
    public static final Object VALUE_INTERPOLATION_BICUBIC = "InterpolationBicubic";

    public static final Object VALUE_COLOR_RENDER_QUALITY = "ColorRenderQuality";
    public static final Object VALUE_COLOR_RENDER_SPEED = "ColorRenderSpeed";
    public static final Object VALUE_COLOR_RENDER_DEFAULT = "ColorRenderDefault";
    public static final Object VALUE_ALPHA_INTERPOLATION_QUALITY = "AlphaInterpolationQuality";
    public static final Object VALUE_ALPHA_INTERPOLATION_SPEED = "AlphaInterpolationSpeed";
    public static final Object VALUE_ALPHA_INTERPOLATION_DEFAULT = "AlphaInterpolationDefault";
    public static final Object VALUE_DITHER_ENABLE = "DitherEnable";
    public static final Object VALUE_DITHER_DISABLE = "DitherDisable";
    public static final Object VALUE_DITHER_DEFAULT = "DitherDefault";

    private final Map<Key, Object> map = new HashMap<>();

    public TRenderingHints(Map<Key, ?> init) {
        if (init != null) map.putAll(init);
    }

    public TRenderingHints(Key key, Object value) {
        map.put(key, value);
    }

    public Object get(Object key) {
        return map.get(key);
    }

    public Object put(Object key, Object value) {
        if (key instanceof Key) {
            return map.put((Key) key, value);
        }
        return null;
    }

    public void add(TRenderingHints hints) {
        map.putAll(hints.map);
    }

    public static class Key {
        private final String name;
        Key(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }
}
