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

import java.util.function.Supplier;
import org.teavm.classlib.java.awt.TGraphics;
import org.teavm.classlib.java.awt.TGraphics2D;
import org.teavm.classlib.java.awt.TImage;

public class TBufferedImage extends org.teavm.classlib.java.awt.TImage implements TRenderedImage {

    public static final int TYPE_INT_RGB = 1;
    public static final int TYPE_INT_ARGB = 2;
    public static final int TYPE_BYTE_GRAY = 10;

    private static Supplier<TGraphics2D> graphicsFactory;

    private final int width;
    private final int height;
    private final int imageType;
    private Object backingObject;
    private int[] pixels;

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

    public Object getBackingObject() {
        return backingObject;
    }

    public void setBackingObject(Object obj) {
        this.backingObject = obj;
    }

    public static void setGraphicsFactory(Supplier<TGraphics2D> factory) {
        graphicsFactory = factory;
    }

    public TGraphics2D createGraphics() {
        if (graphicsFactory != null) {
            return graphicsFactory.get();
        }
        return new TGraphics2D();
    }

    public TGraphics getGraphics() {
        return createGraphics();
    }

    private void ensurePixels() {
        if (pixels == null) {
            pixels = new int[width * height];
            int fill = (imageType == TYPE_INT_ARGB) ? 0 : 0xFFFFFFFF;
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = fill;
            }
        }
    }

    public int getRGB(int x, int y) {
        ensurePixels();
        return pixels[y * width + x];
    }

    public void setRGB(int x, int y, int rgb) {
        ensurePixels();
        pixels[y * width + x] = rgb;
    }

    public int[] getRGB(int startX, int startY, int w, int h, int[] rgbArray, int offset, int scansize) {
        ensurePixels();
        if (rgbArray == null) {
            rgbArray = new int[offset + scansize * (h - 1) + w];
        }
        for (int y = 0; y < h; y++) {
            int srcPos = (startY + y) * width + startX;
            int dstPos = offset + scansize * y;
            System.arraycopy(pixels, srcPos, rgbArray, dstPos, w);
        }
        return rgbArray;
    }

    public void setRGB(int startX, int startY, int w, int h, int[] rgbArray, int offset, int scansize) {
        ensurePixels();
        for (int y = 0; y < h; y++) {
            int dstPos = (startY + y) * width + startX;
            int srcPos = offset + scansize * y;
            System.arraycopy(rgbArray, srcPos, pixels, dstPos, w);
        }
    }

    @Override
    public TImage getScaledInstance(int scaledWidth, int scaledHeight, int hints) {
        TBufferedImage scaled = new TBufferedImage(scaledWidth, scaledHeight, imageType);
        if (backingObject != null) {
            scaled.backingObject = backingObject;
        }
        // Don't copy pixels — scaling happens at Canvas2D drawImage time
        return scaled;
    }
}
