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
package org.teavm.classlib.java.awt.geom;

public abstract class TRectangle2D implements Cloneable {

    public abstract double getX();
    public abstract double getY();
    public abstract double getWidth();
    public abstract double getHeight();
    public abstract boolean isEmpty();
    public abstract void setRect(double x, double y, double w, double h);

    public void setRect(TRectangle2D r) {
        setRect(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    public double getMinX() {
        return getX();
    }

    public double getMinY() {
        return getY();
    }

    public double getMaxX() {
        return getX() + getWidth();
    }

    public double getMaxY() {
        return getY() + getHeight();
    }

    public double getCenterX() {
        return getX() + getWidth() / 2.0;
    }

    public double getCenterY() {
        return getY() + getHeight() / 2.0;
    }

    public boolean contains(double x, double y) {
        double x0 = getX();
        double y0 = getY();
        return x >= x0 && y >= y0 && x < x0 + getWidth() && y < y0 + getHeight();
    }

    public boolean contains(double x, double y, double w, double h) {
        if (isEmpty() || w <= 0 || h <= 0) {
            return false;
        }
        return contains(x, y) && contains(x + w, y + h);
    }

    public boolean intersects(double x, double y, double w, double h) {
        if (isEmpty() || w <= 0 || h <= 0) {
            return false;
        }
        double x0 = getX();
        double y0 = getY();
        return x + w > x0 && y + h > y0 && x < x0 + getWidth() && y < y0 + getHeight();
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
    public int hashCode() {
        long bits = java.lang.Double.doubleToLongBits(getX());
        bits += java.lang.Double.doubleToLongBits(getY()) * 37;
        bits += java.lang.Double.doubleToLongBits(getWidth()) * 43;
        bits += java.lang.Double.doubleToLongBits(getHeight()) * 47;
        return (int) bits ^ (int) (bits >> 32);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof TRectangle2D) {
            TRectangle2D r = (TRectangle2D) obj;
            return getX() == r.getX() && getY() == r.getY()
                && getWidth() == r.getWidth() && getHeight() == r.getHeight();
        }
        return false;
    }

    public static class Double extends TRectangle2D {
        public double x;
        public double y;
        public double width;
        public double height;

        public Double() {
            this.x = 0;
            this.y = 0;
            this.width = 0;
            this.height = 0;
        }

        public Double(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
        }

        @Override
        public double getX() {
            return x;
        }

        @Override
        public double getY() {
            return y;
        }

        @Override
        public double getWidth() {
            return width;
        }

        @Override
        public double getHeight() {
            return height;
        }

        @Override
        public boolean isEmpty() {
            return width <= 0 || height <= 0;
        }

        @Override
        public void setRect(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
        }

        @Override
        public String toString() {
            return "Rectangle2D.Double[" + x + ", " + y + ", " + width + ", " + height + "]";
        }
    }

    public static class Float extends TRectangle2D {
        public float x;
        public float y;
        public float width;
        public float height;

        public Float() {
            this.x = 0;
            this.y = 0;
            this.width = 0;
            this.height = 0;
        }

        public Float(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
        }

        @Override
        public double getX() {
            return (double) x;
        }

        @Override
        public double getY() {
            return (double) y;
        }

        @Override
        public double getWidth() {
            return (double) width;
        }

        @Override
        public double getHeight() {
            return (double) height;
        }

        @Override
        public boolean isEmpty() {
            return width <= 0 || height <= 0;
        }

        @Override
        public void setRect(double x, double y, double w, double h) {
            this.x = (float) x;
            this.y = (float) y;
            this.width = (float) w;
            this.height = (float) h;
        }

        public void setRect(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
        }

        @Override
        public String toString() {
            return "Rectangle2D.Float[" + x + ", " + y + ", " + width + ", " + height + "]";
        }
    }
}
