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

import org.teavm.classlib.java.awt.geom.TRectangle2D;

public class TFontMetrics {

    private final TFont font;

    public TFontMetrics(TFont font) {
        this.font = font;
    }

    public TFont getFont() { return font; }

    public int getAscent() {
        return (int) (font.getSize() * 0.75);
    }

    public int getDescent() {
        return (int) (font.getSize() * 0.25);
    }

    public int getLeading() {
        return font.getSize() / 6;
    }

    public int getHeight() {
        return getAscent() + getDescent() + getLeading();
    }

    public int stringWidth(String str) {
        return (int) (str.length() * font.getSize() * 0.55);
    }

    public int charWidth(char ch) {
        return font.getSize() / 2;
    }

    public int getMaxAdvance() {
        return font.getSize();
    }

    public int charsWidth(char[] data, int off, int len) {
        return stringWidth(new String(data, off, len));
    }

    public TRectangle2D getStringBounds(String str, TGraphics g) {
        double w = str.length() * font.getSize() * 0.55;
        double h = font.getSize() * 1.2;
        return new TRectangle2D.Double(0, -getAscent(), w, h);
    }
}
