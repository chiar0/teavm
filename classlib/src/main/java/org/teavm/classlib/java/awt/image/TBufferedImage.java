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
package org.teavm.classlib.java.awt.image;

import org.teavm.classlib.java.awt.TGraphics;
import org.teavm.classlib.java.awt.TGraphics2D;

public class TBufferedImage extends org.teavm.classlib.java.awt.TImage {

    public static final int TYPE_INT_RGB = 1;
    public static final int TYPE_INT_ARGB = 2;
    public static final int TYPE_BYTE_GRAY = 10;

    private final int width;
    private final int height;
    private final int imageType;

    public TBufferedImage(int width, int height, int imageType) {
        this.width = width;
        this.height = height;
        this.imageType = imageType;
    }

    @Override
    public int getWidth(Object observer) {
        return width;
    }

    @Override
    public int getHeight(Object observer) {
        return height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getType() {
        return imageType;
    }

    public TGraphics2D createGraphics() {
        return new TGraphics2D();
    }

    public TGraphics getGraphics() {
        return createGraphics();
    }
}
