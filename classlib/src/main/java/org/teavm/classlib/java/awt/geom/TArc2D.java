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

public abstract class TArc2D {

    public static final int OPEN = 0;
    public static final int CHORD = 1;
    public static final int PIE = 2;

    public static class Double extends TArc2D {
        public double x;
        public double y;
        public double width;
        public double height;
        public double start;
        public double extent;
        public int type;

        public Double() {
            this.type = OPEN;
        }

        public Double(int type) {
            this.type = type;
        }

        public Double(double x, double y, double w, double h, double start, double extent, int type) {
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
            this.start = start;
            this.extent = extent;
            this.type = type;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }

        public double getAngleStart() {
            return start;
        }

        public double getAngleExtent() {
            return extent;
        }

        public int getArcType() {
            return type;
        }
    }
}
