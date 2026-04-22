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

public class TPoint {

    public int x;
    public int y;

    public TPoint() {
        this.x = 0;
        this.y = 0;
    }

    public TPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public TPoint(TPoint p) {
        this.x = p.x;
        this.y = p.y;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public void setLocation(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setLocation(double x, double y) {
        this.x = (int) x;
        this.y = (int) y;
    }

    public void move(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void translate(int dx, int dy) {
        this.x += dx;
        this.y += dy;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TPoint) {
            TPoint p = (TPoint) obj;
            return x == p.x && y == p.y;
        }
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return x + y * 31;
    }

    @Override
    public String toString() {
        return "Point[" + x + ", " + y + "]";
    }
}
