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

public class TColor {

    public static final int RGB = 1;

    private final int value;

    public TColor(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public TColor(int r, int g, int b, int a) {
        value = ((a & 0xFF) << 24)
                | ((r & 0xFF) << 16)
                | ((g & 0xFF) << 8)
                | ((b & 0xFF) << 0);
    }

    public TColor(int rgb) {
        value = 0xFF000000 | rgb;
    }

    private TColor(int rgba, boolean hasalpha) {
        value = hasalpha ? rgba : (0xFF000000 | rgba);
    }

    public TColor(float r, float g, float b) {
        this(r, g, b, 1f);
    }

    public TColor(float r, float g, float b, float a) {
        this(Math.round(r * 255f), Math.round(g * 255f),
            Math.round(b * 255f), Math.round(a * 255f));
    }

    public int getRGB() {
        return value;
    }

    public int getRed() {
        return (value >> 16) & 0xFF;
    }

    public int getGreen() {
        return (value >> 8) & 0xFF;
    }

    public int getBlue() {
        return (value >> 0) & 0xFF;
    }

    public int getAlpha() {
        return (value >> 24) & 0xFF;
    }

    public static TColor decode(String nm) {
        String cleaned = nm;
        if (nm.startsWith("#")) {
            cleaned = nm.substring(1);
        } else if (nm.startsWith("0x") || nm.startsWith("0X")) {
            cleaned = nm.substring(2);
        }
        int color = Integer.parseInt(cleaned, 16);
        if (cleaned.length() == 6) {
            return new TColor(color);
        } else if (cleaned.length() == 8) {
            return new TColor(color, true);
        } else {
            return new TColor(color);
        }
    }

    public static final TColor white = new TColor(255, 255, 255);
    public static final TColor WHITE = white;
    public static final TColor black = new TColor(0, 0, 0);
    public static final TColor BLACK = black;
    public static final TColor red = new TColor(255, 0, 0);
    public static final TColor RED = red;
    public static final TColor green = new TColor(0, 255, 0);
    public static final TColor GREEN = green;
    public static final TColor blue = new TColor(0, 0, 255);
    public static final TColor BLUE = blue;
    public static final TColor yellow = new TColor(255, 255, 0);
    public static final TColor YELLOW = yellow;
    public static final TColor cyan = new TColor(0, 255, 255);
    public static final TColor CYAN = cyan;
    public static final TColor magenta = new TColor(255, 0, 255);
    public static final TColor MAGENTA = magenta;
    public static final TColor gray = new TColor(128, 128, 128);
    public static final TColor GRAY = gray;
    public static final TColor darkGray = new TColor(64, 64, 64);
    public static final TColor DARK_GRAY = darkGray;
    public static final TColor lightGray = new TColor(192, 192, 192);
    public static final TColor LIGHT_GRAY = lightGray;
    public static final TColor orange = new TColor(255, 200, 0);
    public static final TColor ORANGE = orange;
    public static final TColor pink = new TColor(255, 175, 175);
    public static final TColor PINK = pink;
}
