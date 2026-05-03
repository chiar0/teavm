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

import java.util.ArrayList;
import java.util.List;
import org.teavm.classlib.java.awt.TShape;

public class TPath2D implements TShape {

    public static final int WIND_EVEN_ODD = 0;
    public static final int WIND_NON_ZERO = 1;

    public static final int SEG_MOVETO = 0;
    public static final int SEG_LINETO = 1;
    public static final int SEG_QUADTO = 2;
    public static final int SEG_CUBICTO = 3;
    public static final int SEG_CLOSE = 4;

    public final List<int[]> segments = new ArrayList<>();
    public final List<double[]> coords = new ArrayList<>();
    public int windingRule;
    public double lastX;
    public double lastY;
    public double moveX;
    public double moveY;
    public boolean needsMove;

    protected TPath2D() {
        this(WIND_NON_ZERO);
    }

    protected TPath2D(int rule) {
        this.windingRule = rule;
    }

    public void moveTo(double x, double y) {
        segments.add(new int[]{SEG_MOVETO});
        coords.add(new double[]{x, y});
        lastX = x;
        lastY = y;
        moveX = x;
        moveY = y;
        needsMove = false;
    }

    public void lineTo(double x, double y) {
        if (needsMove) moveTo(lastX, lastY);
        segments.add(new int[]{SEG_LINETO});
        coords.add(new double[]{x, y});
        lastX = x;
        lastY = y;
    }

    public void quadTo(double x1, double y1, double x2, double y2) {
        if (needsMove) moveTo(lastX, lastY);
        segments.add(new int[]{SEG_QUADTO});
        coords.add(new double[]{x1, y1, x2, y2});
        lastX = x2;
        lastY = y2;
    }

    public void curveTo(double x1, double y1, double x2, double y2, double x3, double y3) {
        if (needsMove) moveTo(lastX, lastY);
        segments.add(new int[]{SEG_CUBICTO});
        coords.add(new double[]{x1, y1, x2, y2, x3, y3});
        lastX = x3;
        lastY = y3;
    }

    public void closePath() {
        segments.add(new int[]{SEG_CLOSE});
        coords.add(new double[]{});
        lastX = moveX;
        lastY = moveY;
        needsMove = true;
    }

    public void reset() {
        segments.clear();
        coords.clear();
        lastX = 0;
        lastY = 0;
        moveX = 0;
        moveY = 0;
        needsMove = false;
    }

    public TPoint2D getCurrentPoint() {
        return new TPoint2D.Double(lastX, lastY);
    }

    public void append(TShape s, boolean connect) {
        if (s instanceof TPath2D) {
            TPath2D src = (TPath2D) s;
            if (connect && !needsMove) {
                if (src.segments.size() > 0) {
                    double[] first = src.coords.get(0);
                    lineTo(first[0], first[1]);
                }
            }
            for (int i = 0; i < src.segments.size(); i++) {
                int type = src.segments.get(i)[0];
                double[] c = src.coords.get(i);
                switch (type) {
                    case SEG_MOVETO:
                        moveTo(c[0], c[1]);
                        break;
                    case SEG_LINETO:
                        lineTo(c[0], c[1]);
                        break;
                    case SEG_QUADTO:
                        quadTo(c[0], c[1], c[2], c[3]);
                        break;
                    case SEG_CUBICTO:
                        curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                        break;
                    case SEG_CLOSE:
                        closePath();
                        break;
                }
            }
        } else {
            // Fallback: lineTo shape center
            if (connect && !needsMove) {
                lineTo(lastX, lastY);
            }
            TRectangle2D b = s.getBounds2D();
            if (b != null && !b.isEmpty()) {
                lineTo(b.getCenterX(), b.getCenterY());
            }
        }
    }

    @Override
    public TRectangle2D getBounds2D() {
        if (segments.isEmpty()) return new TRectangle2D.Double();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] c : coords) {
            for (int i = 0; i < c.length; i += 2) {
                if (i + 1 < c.length) {
                    minX = Math.min(minX, c[i]);
                    minY = Math.min(minY, c[i + 1]);
                    maxX = Math.max(maxX, c[i]);
                    maxY = Math.max(maxY, c[i + 1]);
                }
            }
        }
        if (minX == Double.MAX_VALUE) return new TRectangle2D.Double();
        return new TRectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    public int getWindingRule() {
        return windingRule;
    }

    public void setWindingRule(int rule) {
        this.windingRule = rule;
    }
}
