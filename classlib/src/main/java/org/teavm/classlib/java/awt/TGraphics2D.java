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

import org.teavm.classlib.java.awt.font.TFontRenderContext;
import org.teavm.classlib.java.awt.geom.TAffineTransform;

public class TGraphics2D extends TGraphics {

    protected TStroke stroke;
    protected TShape clip;
    protected TAffineTransform transform = new TAffineTransform();
    protected TRenderingHints hints = new TRenderingHints((java.util.Map<TRenderingHints.Key, ?>) null);
    protected TColor background = new TColor(0, 0, 0, 0);
    protected Object paint;

    @Override
    public TGraphics create() {
        TGraphics2D g = new TGraphics2D();
        g.color = this.color;
        g.font = this.font;
        g.stroke = this.stroke;
        g.clip = this.clip;
        g.transform = new TAffineTransform();
        g.paint = this.paint;
        g.background = this.background;
        return g;
    }

    @Override
    public void dispose() {
    }

    public TStroke getStroke() { return stroke; }
    public void setStroke(TStroke s) { this.stroke = s; }

    public Object getPaint() { return paint; }
    public void setPaint(Object p) { this.paint = p; }

    public TColor getBackground() { return background; }
    public void setBackground(TColor c) { this.background = c; }

    public TAffineTransform getTransform() { return transform; }
    public void setTransform(TAffineTransform t) { this.transform = t; }

    public void transform(TAffineTransform t) { this.transform.concatenate(t); }
    public void translate(double tx, double ty) { this.transform.translate(tx, ty); }
    public void rotate(double theta) { this.transform.rotate(theta); }
    public void rotate(double theta, double x, double y) {
        translate(x, y);
        rotate(theta);
        translate(-x, -y);
    }
    public void scale(double sx, double sy) { this.transform.scale(sx, sy); }

    public TShape getClip() { return clip; }
    public void setClip(TShape s) { this.clip = s; }
    public void clip(TShape s) { this.clip = s; }

    public Object getRenderingHint(TRenderingHints.Key key) { return hints.get(key); }
    public void setRenderingHint(TRenderingHints.Key key, Object value) { hints.put(key, value); }
    public void addRenderingHints(TRenderingHints h) { hints.add(h); }
    public TRenderingHints getRenderingHints() { return hints; }

    public void draw(TShape s) {
    }

    public void fill(TShape s) {
        fillRect(0, 0, 0, 0);
    }

    public void drawString(String s, float x, float y) {
        drawString(s, (int) x, (int) y);
    }

    public void drawImage(TImage img, int x, int y, Object observer) {
    }

    public void drawImage(TImage img, int x, int y, int w, int h, Object observer) {
    }

    public void drawImage(TImage img, TAffineTransform xform, Object observer) {
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2) {
    }

    @Override
    public void fillRect(int x, int y, int width, int height) {
    }

    @Override
    public void drawRect(int x, int y, int width, int height) {
    }

    @Override
    public void drawString(String str, int x, int y) {
    }

    @Override
    public void drawOval(int x, int y, int width, int height) {
    }

    @Override
    public void fillOval(int x, int y, int width, int height) {
    }

    public void fill3DRect(int x, int y, int width, int height, boolean raised) {
    }

    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
    }

    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
    }

    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
    }

    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
    }

    public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {
    }

    public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {
    }

    public TFontRenderContext getFontRenderContext() {
        return new TFontRenderContext();
    }

    public TFontMetrics getFontMetrics() {
        return getFontMetrics(font);
    }

    public TFontMetrics getFontMetrics(TFont f) {
        return new TFontMetrics(f);
    }
}
