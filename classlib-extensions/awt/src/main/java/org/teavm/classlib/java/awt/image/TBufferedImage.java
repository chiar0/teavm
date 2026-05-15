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
import org.teavm.jso.browser.Window;
import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.dom.html.HTMLCanvasElement;

public class TBufferedImage extends org.teavm.classlib.java.awt.TImage implements TRenderedImage {

    public static final int TYPE_INT_RGB = 1;
    public static final int TYPE_INT_ARGB = 2;
    public static final int TYPE_BYTE_GRAY = 10;

    private static Supplier<TGraphics2D> graphicsFactory;

    static {
        try {
            graphicsFactory = () -> {
                try {
                    HTMLCanvasElement canvas = (HTMLCanvasElement) Window.current()
                            .getDocument().createElement("canvas");
                    canvas.setWidth(1);
                    canvas.setHeight(1);
                    CanvasRenderingContext2D ctx = (CanvasRenderingContext2D) canvas.getContext("2d");
                    return new TGraphics2D() {
                        @Override
                        public void fillRect(int x, int y, int width, int height) {
                            ctx.fillRect(x, y, width, height);
                        }

                        @Override
                        public void drawRect(int x, int y, int width, int height) {
                            ctx.strokeRect(x, y, width, height);
                        }

                        @Override
                        public void drawLine(int x1, int y1, int x2, int y2) {
                            ctx.beginPath();
                            ctx.moveTo(x1, y1);
                            ctx.lineTo(x2, y2);
                            ctx.stroke();
                        }

                        @Override
                        public void drawString(String str, int x, int y) {
                            ctx.fillText(str, x, y);
                        }

                        @Override
                        public void drawString(String str, float x, float y) {
                            ctx.fillText(str, x, y);
                        }

                        @Override
                        public void drawOval(int x, int y, int width, int height) {
                            ctx.beginPath();
                            ctx.ellipse(x + width / 2.0, y + height / 2.0,
                                    width / 2.0, height / 2.0, 0, 0, Math.PI * 2);
                            ctx.stroke();
                        }

                        @Override
                        public void fillOval(int x, int y, int width, int height) {
                            ctx.beginPath();
                            ctx.ellipse(x + width / 2.0, y + height / 2.0,
                                    width / 2.0, height / 2.0, 0, 0, Math.PI * 2);
                            ctx.fill();
                        }

                        @Override
                        public void drawArc(int x, int y, int width, int height,
                                int startAngle, int arcAngle) {
                            ctx.beginPath();
                            ctx.ellipse(x + width / 2.0, y + height / 2.0,
                                    width / 2.0, height / 2.0, 0,
                                    Math.toRadians(startAngle),
                                    Math.toRadians(startAngle + arcAngle));
                            ctx.stroke();
                        }

                        @Override
                        public void fillArc(int x, int y, int width, int height,
                                int startAngle, int arcAngle) {
                            ctx.beginPath();
                            ctx.ellipse(x + width / 2.0, y + height / 2.0,
                                    width / 2.0, height / 2.0, 0,
                                    Math.toRadians(startAngle),
                                    Math.toRadians(startAngle + arcAngle));
                            ctx.fill();
                        }

                        @Override
                        public void drawRoundRect(int x, int y, int width, int height,
                                int arcWidth, int arcHeight) {
                            roundRectPath(ctx, x, y, width, height, arcWidth, arcHeight);
                            ctx.stroke();
                        }

                        @Override
                        public void fillRoundRect(int x, int y, int width, int height,
                                int arcWidth, int arcHeight) {
                            roundRectPath(ctx, x, y, width, height, arcWidth, arcHeight);
                            ctx.fill();
                        }

                        @Override
                        public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {
                            if (nPoints <= 0) return;
                            ctx.beginPath();
                            ctx.moveTo(xPoints[0], yPoints[0]);
                            for (int i = 1; i < nPoints; i++) ctx.lineTo(xPoints[i], yPoints[i]);
                            ctx.closePath();
                            ctx.stroke();
                        }

                        @Override
                        public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {
                            if (nPoints <= 0) return;
                            ctx.beginPath();
                            ctx.moveTo(xPoints[0], yPoints[0]);
                            for (int i = 1; i < nPoints; i++) ctx.lineTo(xPoints[i], yPoints[i]);
                            ctx.closePath();
                            ctx.fill();
                        }

                        @Override
                        public void drawImage(TImage img, int x, int y, Object observer) {
                            if (img instanceof TBufferedImage) {
                                Object backing = ((TBufferedImage) img).getBackingObject();
                                if (backing instanceof org.teavm.jso.canvas.CanvasImageSource) {
                                    ctx.drawImage((org.teavm.jso.canvas.CanvasImageSource) backing, x, y);
                                }
                            }
                        }

                        @Override
                        public void drawImage(TImage img, int x, int y, int w, int h, Object observer) {
                            if (img instanceof TBufferedImage) {
                                Object backing = ((TBufferedImage) img).getBackingObject();
                                if (backing instanceof org.teavm.jso.canvas.CanvasImageSource) {
                                    ctx.drawImage((org.teavm.jso.canvas.CanvasImageSource) backing, x, y, w, h);
                                }
                            }
                        }

                        @Override
                        public void drawImage(TImage img,
                                org.teavm.classlib.java.awt.geom.TAffineTransform xform, Object observer) {
                            if (img instanceof TBufferedImage) {
                                Object backing = ((TBufferedImage) img).getBackingObject();
                                if (backing instanceof org.teavm.jso.canvas.CanvasImageSource) {
                                    if (xform != null) {
                                        ctx.save();
                                        ctx.setTransform(xform.m00, xform.m10, xform.m01,
                                                xform.m11, xform.m02, xform.m12);
                                    }
                                    ctx.drawImage(
                                            (org.teavm.jso.canvas.CanvasImageSource) backing, 0, 0);
                                    if (xform != null) ctx.restore();
                                }
                            }
                        }
                    };
                } catch (Throwable t) {
                    return new TGraphics2D();
                }
            };
        } catch (Throwable ignored) {
            // Window.current() may not be available in non-browser environments
        }
    }

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

    /**
     * Helper for the default canvas-backed graphics factory.
     * Emits a rounded-rectangle path onto the given canvas context.
     */
    private static void roundRectPath(CanvasRenderingContext2D ctx,
            double x, double y, double w, double h, double aw, double ah) {
        double hp = Math.PI / 2;
        ctx.beginPath();
        ctx.moveTo(x + aw, y);
        ctx.lineTo(x + w - aw, y);
        ctx.ellipse(x + w - aw, y + ah, aw, ah, 0, -hp, 0);
        ctx.lineTo(x + w, y + h - ah);
        ctx.ellipse(x + w - aw, y + h - ah, aw, ah, 0, 0, hp);
        ctx.lineTo(x + aw, y + h);
        ctx.ellipse(x + aw, y + h - ah, aw, ah, 0, hp, Math.PI);
        ctx.lineTo(x, y + ah);
        ctx.ellipse(x + aw, y + ah, aw, ah, 0, Math.PI, Math.PI + hp);
        ctx.closePath();
    }
}
