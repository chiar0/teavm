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

import java.util.Locale;
import java.util.Map;

import org.teavm.classlib.java.awt.font.TFontRenderContext;
import org.teavm.classlib.java.awt.geom.TRectangle2D;

public class TFont {

    public static final int PLAIN = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;

    private String name;
    private int style;
    private int size;

    public TFont(String name, int style, int size) {
        this.name = name != null ? name : "Default";
        this.style = style;
        this.size = size;
    }

    public TFont(Map<?, ?> attributes) {
        this.name = "Default";
        this.style = PLAIN;
        this.size = 12;
    }

    public String getName() {
        return name;
    }

    public String getFamily() {
        return name;
    }

    public String getFamily(Locale l) {
        return name;
    }

    public int getStyle() {
        return style;
    }

    public int getSize() {
        return size;
    }

    public boolean isPlain() {
        return style == PLAIN;
    }

    public boolean isBold() {
        return (style & BOLD) != 0;
    }

    public boolean isItalic() {
        return (style & ITALIC) != 0;
    }

    public TFont deriveFont(int style) {
        return new TFont(this.name, style, this.size);
    }

    public TFont deriveFont(float size) {
        return new TFont(this.name, this.style, (int) size);
    }

    public TRectangle2D getStringBounds(String str, TFontRenderContext frc) {
        double width = str.length() * size * 0.6;
        double height = size * 1.2;
        return new TRectangle2D.Double(0, -size * 0.8, width, height);
    }

    public String getFontName() {
        return name;
    }
}
