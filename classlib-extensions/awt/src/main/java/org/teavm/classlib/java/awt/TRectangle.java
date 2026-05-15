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

public class TRectangle implements TShape, Cloneable {
    public int x;
    public int y;
    public int width;
    public int height;

    public TRectangle() {
    }

    public TRectangle(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean contains(int px, int py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    public boolean contains(TRectangle r) {
        return r.x >= x && r.y >= y
            && r.x + r.width <= x + width
            && r.y + r.height <= y + height;
    }

    public boolean intersects(TRectangle r) {
        return r.x + r.width > x && r.y + r.height > y
            && r.x < x + width && r.y < y + height;
    }

    public void add(int px, int py) {
        int x1 = Math.min(x, px);
        int y1 = Math.min(y, py);
        int x2 = Math.max(x + width, px);
        int y2 = Math.max(y + height, py);
        x = x1;
        y = y1;
        width = x2 - x1;
        height = y2 - y1;
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.width = w;
        this.height = h;
    }

    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    @Override
    public TRectangle2D getBounds2D() {
        return new TRectangle2D.Double(x, y, width, height);
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    @Override
    public String toString() {
        return "Rectangle[" + x + ", " + y + ", " + width + ", " + height + "]";
    }
}
