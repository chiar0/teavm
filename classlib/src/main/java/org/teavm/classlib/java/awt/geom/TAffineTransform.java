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

public class TAffineTransform {
    public double m00;
    public double m10;
    public double m01;
    public double m11;
    public double m02;
    public double m12;

    public TAffineTransform() {
        m00 = 1;
        m11 = 1;
    }

    public static TAffineTransform getScaleInstance(double sx, double sy) {
        TAffineTransform t = new TAffineTransform();
        t.m00 = sx;
        t.m11 = sy;
        return t;
    }

    public static TAffineTransform getTranslateInstance(double tx, double ty) {
        TAffineTransform t = new TAffineTransform();
        t.m02 = tx;
        t.m12 = ty;
        return t;
    }

    public static TAffineTransform getRotateInstance(double theta) {
        TAffineTransform t = new TAffineTransform();
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        t.m00 = c;
        t.m10 = s;
        t.m01 = -s;
        t.m11 = c;
        return t;
    }

    public void concatenate(TAffineTransform other) {
        double t00 = m00 * other.m00 + m01 * other.m10;
        double t01 = m00 * other.m01 + m01 * other.m11;
        double t02 = m00 * other.m02 + m01 * other.m12 + m02;
        double t10 = m10 * other.m00 + m11 * other.m10;
        double t11 = m10 * other.m01 + m11 * other.m11;
        double t12 = m10 * other.m02 + m11 * other.m12 + m12;
        m00 = t00;
        m01 = t01;
        m02 = t02;
        m10 = t10;
        m11 = t11;
        m12 = t12;
    }

    public void rotate(double theta) {
        concatenate(getRotateInstance(theta));
    }

    public void translate(double tx, double ty) {
        concatenate(getTranslateInstance(tx, ty));
    }

    public void scale(double sx, double sy) {
        concatenate(getScaleInstance(sx, sy));
    }

    public void setToIdentity() {
        m00 = 1;
        m10 = 0;
        m01 = 0;
        m11 = 1;
        m02 = 0;
        m12 = 0;
    }

    public void setToTranslation(double tx, double ty) {
        setToIdentity();
        m02 = tx;
        m12 = ty;
    }

    public void setToScale(double sx, double sy) {
        setToIdentity();
        m00 = sx;
        m11 = sy;
    }

    public void setToRotation(double theta) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        m00 = c;
        m10 = s;
        m01 = -s;
        m11 = c;
        m02 = 0;
        m12 = 0;
    }

    public TPoint2D transform(TPoint2D src, TPoint2D dst) {
        double x = src.getX();
        double y = src.getY();
        double nx = m00 * x + m01 * y + m02;
        double ny = m10 * x + m11 * y + m12;
        if (dst == null) {
            dst = new TPoint2D.Double(nx, ny);
        } else {
            dst.setLocation(nx, ny);
        }
        return dst;
    }

    public void preConcatenate(TAffineTransform other) {
        double t00 = other.m00 * m00 + other.m01 * m10;
        double t01 = other.m00 * m01 + other.m01 * m11;
        double t02 = other.m00 * m02 + other.m01 * m12 + other.m02;
        double t10 = other.m10 * m00 + other.m11 * m10;
        double t11 = other.m10 * m01 + other.m11 * m11;
        double t12 = other.m10 * m02 + other.m11 * m12 + other.m12;
        m00 = t00;
        m01 = t01;
        m02 = t02;
        m10 = t10;
        m11 = t11;
        m12 = t12;
    }

    public void shear(double shx, double shy) {
        TAffineTransform s = new TAffineTransform();
        s.m01 = shx;
        s.m10 = shy;
        concatenate(s);
    }

    public boolean isIdentity() {
        return m00 == 1 && m10 == 0 && m01 == 0 && m11 == 1 && m02 == 0 && m12 == 0;
    }

    @Override
    public Object clone() {
        TAffineTransform t = new TAffineTransform();
        t.m00 = m00;
        t.m10 = m10;
        t.m01 = m01;
        t.m11 = m11;
        t.m02 = m02;
        t.m12 = m12;
        return t;
    }

    public double getTranslateX() { return m02; }
    public double getTranslateY() { return m12; }
    public double getScaleX() { return m00; }
    public double getScaleY() { return m11; }
    public double getShearX() { return m01; }
    public double getShearY() { return m10; }
}
