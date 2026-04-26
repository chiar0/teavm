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

public abstract class TGraphics {

    protected TColor color;
    protected TFont font;

    public abstract TGraphics create();
    public abstract void dispose();

    public TColor getColor() { return color; }
    public void setColor(TColor c) { this.color = c; }

    public TFont getFont() { return font; }
    public void setFont(TFont f) { this.font = f; }

    public abstract void drawLine(int x1, int y1, int x2, int y2);
    public abstract void fillRect(int x, int y, int width, int height);
    public abstract void drawRect(int x, int y, int width, int height);
    public abstract void drawString(String str, int x, int y);
    public abstract void drawOval(int x, int y, int width, int height);
    public abstract void fillOval(int x, int y, int width, int height);
}
