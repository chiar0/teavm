package org.teavm.classlib.java.awt;

public class TAlphaComposite implements TComposite {

    public static final int SRC_OVER = 3;

    private final int rule;
    private final float alpha;

    private TAlphaComposite(int rule, float alpha) {
        this.rule = rule;
        this.alpha = alpha;
    }

    public static TAlphaComposite getInstance(int rule) {
        return new TAlphaComposite(rule, 1.0f);
    }

    public static TAlphaComposite getInstance(int rule, float alpha) {
        return new TAlphaComposite(rule, alpha);
    }

    public static TAlphaComposite SrcOver(float alpha) {
        return new TAlphaComposite(SRC_OVER, alpha);
    }

    public int getRule() {
        return rule;
    }

    public float getAlpha() {
        return alpha;
    }
}
