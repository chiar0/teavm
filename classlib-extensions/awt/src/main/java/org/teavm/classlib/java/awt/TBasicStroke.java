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

public class TBasicStroke implements TStroke {

    public static final int JOIN_MITER = 0;
    public static final int JOIN_ROUND = 1;
    public static final int JOIN_BEVEL = 2;

    public static final int CAP_BUTT = 0;
    public static final int CAP_ROUND = 1;
    public static final int CAP_SQUARE = 2;

    public float lineWidth;
    public int cap;
    public int join;
    public float miterlimit;
    public float[] dash;
    public float dashPhase;

    public TBasicStroke() {
        this(1.0f, CAP_SQUARE, JOIN_MITER, 10.0f, null, 0.0f);
    }

    public TBasicStroke(float width) {
        this(width, CAP_SQUARE, JOIN_MITER, 10.0f, null, 0.0f);
    }

    public TBasicStroke(float width, int cap, int join) {
        this(width, cap, join, 10.0f, null, 0.0f);
    }

    public TBasicStroke(float width, int cap, int join, float miterlimit) {
        this(width, cap, join, miterlimit, null, 0.0f);
    }

    public TBasicStroke(float width, int cap, int join, float miterlimit, float[] dash, float dashPhase) {
        this.lineWidth = width;
        this.cap = cap;
        this.join = join;
        this.miterlimit = miterlimit;
        this.dash = dash;
        this.dashPhase = dashPhase;
    }

    public float getLineWidth() {
        return lineWidth;
    }

    public int getEndCap() {
        return cap;
    }

    public int getLineJoin() {
        return join;
    }

    public float getMiterLimit() {
        return miterlimit;
    }

    public float[] getDashArray() {
        return dash;
    }

    public float getDashPhase() {
        return dashPhase;
    }

    @Override
    public TShape createStrokedShape(TShape p) {
        return p;
    }
}
