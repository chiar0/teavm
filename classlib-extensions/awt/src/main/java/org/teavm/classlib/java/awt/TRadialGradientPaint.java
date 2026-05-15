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

import org.teavm.classlib.java.awt.geom.TPoint2D;

public class TRadialGradientPaint implements TPaint {
    private final TPoint2D center;
    private final float radius;
    private final float[] fractions;
    private final TColor[] colors;

    public TRadialGradientPaint(TPoint2D center, float radius, float[] fractions, TColor[] colors) {
        this.center = center;
        this.radius = radius;
        this.fractions = fractions;
        this.colors = colors;
    }

    public TPoint2D getCenterPoint() {
        return center;
    }

    public float getRadius() {
        return radius;
    }

    public float[] getFractions() {
        return fractions;
    }

    public TColor[] getColors() {
        return colors;
    }
}
