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

public class TDimension {

    public int width;
    public int height;

    public TDimension() {
    }

    public TDimension(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public double getWidth() {
        return (double) width;
    }

    public double getHeight() {
        return (double) height;
    }

    public void setSize(int w, int h) {
        this.width = w;
        this.height = h;
    }

    @Override
    public String toString() {
        return "Dimension[" + width + "," + height + "]";
    }
}
