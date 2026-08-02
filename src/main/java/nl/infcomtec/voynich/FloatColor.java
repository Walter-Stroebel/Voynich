/*
 *  Copyright (c) 2018 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;

/**
 * Bunch of Color methods using the float[] form of a Color.
 *
 * @author walter
 */
public class FloatColor implements Comparable<FloatColor> {

    public final float r, g, b, a;
    public final boolean clamped;

    public static final FloatColor BLACK = new FloatColor(Color.BLACK);
    public static final FloatColor BLUE = new FloatColor(Color.BLUE);
    public static final FloatColor YELLOW = new FloatColor(Color.YELLOW);
    public static final FloatColor CYAN = new FloatColor(Color.CYAN);
    public static final FloatColor MAGENTA = new FloatColor(Color.MAGENTA);
    public static final FloatColor DARK_GRAY = new FloatColor(Color.DARK_GRAY);
    public static final FloatColor GRAY = new FloatColor(Color.GRAY);
    public static final FloatColor GREEN = new FloatColor(Color.GREEN);
    public static final FloatColor LIGHT_GRAY = new FloatColor(Color.LIGHT_GRAY);
    public static final FloatColor ORANGE = new FloatColor(Color.ORANGE);
    public static final FloatColor PINK = new FloatColor(Color.PINK);
    public static final FloatColor RED = new FloatColor(Color.RED);
    public static final FloatColor WHITE = new FloatColor(Color.WHITE);

    public FloatColor(float r, float g, float b) {
        this.r = clamp(r);
        this.g = clamp(g);
        this.b = clamp(b);
        this.a = 1;
        this.clamped = true;
    }

    public FloatColor(FloatColor fc) {
        this(fc.r,fc.g,fc.b,fc.a);
    }

    public FloatColor(float r, float g, float b, float a) {
        this.r = clamp(r);
        this.g = clamp(g);
        this.b = clamp(b);
        this.a = clamp(a);
        this.clamped = true;
    }

    public FloatColor(float r, float g, float b, float a, boolean clamped) {
        this.clamped = clamped;
        if (clamped) {
            this.r = clamp(r);
            this.g = clamp(g);
            this.b = clamp(b);
            this.a = clamp(a);
        } else {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }
    }

    public FloatColor(Color c) {
        float[] f = c.getComponents(null);
        r = f[0];
        g = f[1];
        b = f[2];
        a = f[3];
        clamped = true;
    }

    /**
     * Returns an array of colors.
     *
     * @param binStep Number of significant bits (1 through 8, step is 128 to
     * 1).
     * @return An RGB sorted array of colors.
     */
    public static FloatColor[] binSpectrum(byte binStep) {
        int step = EnhancedColor.clamp(1 << (binStep - 1), 1, 128);
        int len = (256 / step) * (256 / step) * (256 / step);
        FloatColor[] ret = new FloatColor[len];
        int i = 0;
        double inc = step / 255.0;
        for (double r = 0; r < 1; r += inc) {
            for (double g = 0; g < 1; g += inc) {
                for (double b = 0; b < 1; b += inc) {
                    ret[i++] = new FloatColor((float) r, (float) g, (float) b);
                }
            }
        }
        return ret;
    }

    /**
     * Returns an array of colors.
     *
     * @param inc Step for each color.
     * @return An RGB sorted array of colors.
     */
    public static FloatColor[] spectrum(double inc) {
        int n = (int) (1.0 / inc) + 1;
        int len = n * n * n;
        if (len > 256 * 256 * 256) {
            throw new RuntimeException("Unrealistic number of colors " + len + " (>8 bit) in spectrum.");
        }
        FloatColor[] ret = new FloatColor[len];
        int i = 0;
        for (double r = 0; r < 1; r += inc) {
            for (double g = 0; g < 1; g += inc) {
                for (double b = 0; b < 1; b += inc) {
                    ret[i++] = new FloatColor((float) r, (float) g, (float) b);
                }
            }
        }
        int rLen = len - 1;
        while (null == ret[rLen]) {
            rLen--;
        }
        rLen++;
        if (rLen != len) {
            FloatColor[] ret2 = new FloatColor[rLen];
            System.arraycopy(ret, 0, ret2, 0, rLen);
            return ret2;
        }
        return ret;
    }

    /**
     * Returns an array of colors.
     *
     * @param nR number of red tints.
     * @param nG number of green tints.
     * @param nB number of blue tints.
     * @return An RGB sorted array of colors.
     */
    public static FloatColor[] spectrum(int nR, int nG, int nB) {
        int len = nR * nG * nB + 1;
        FloatColor[] ret = new FloatColor[len];
        int i = 1;
        ret[0] = new FloatColor(0, 0, 0);
        double rI = 1.0 / (nR);
        double gI = 1.0 / (nG);
        double bI = 1.0 / (nB);
        for (double r = rI; r <= 1; r += rI) {
            for (double g = gI; g <= 1; g += gI) {
                for (double b = bI; b <= 1; b += bI) {
                    System.out.println(i + " " + r + " " + g + " " + b);
                    ret[i++] = new FloatColor((float) r, (float) g, (float) b);
                }
            }
        }
        return ret;
    }

    public FloatColor rgbDiff(FloatColor fc) {
        return new FloatColor(
                Math.abs(r * a - fc.r * fc.a),
                Math.abs(g * a - fc.g * fc.a),
                Math.abs(b * a - fc.b * fc.a),
                1, true);
    }

    @Override
    public String toString() {
        return "FloatColor{" + "r=" + r + ", g=" + g + ", b=" + b + ", a=" + a + ", clamped=" + clamped + '}';
    }

    /**
     * The amount of "light" represented by this color.
     * <p>
     * This should be like the amount of energy consumed by an (ideal) RGB LED
     * set to this color. Alpha is considered to be a limiting factor here: an
     * alpha of 0 would be black (darkness, zero).</p>
     *
     * @return 0 for black, 1 for white;
     */
    public float light() {
        return (r * a + g * a + b * a) / 3;
    }

    public EnhancedColor getColor() {
        return new EnhancedColor(r, g, b, a);
    }

    public float distance(FloatColor other) {
        double r2 = (r * a - other.r * other.a);
        r2 *= r2;
        double g2 = (g * a - other.g * other.a);
        g2 *= g2;
        double b2 = (b * a - other.b * other.a);
        b2 *= b2;
        return Double.valueOf(Math.sqrt(r2 + g2 + b2)).floatValue();
    }

    public FloatColor blend(FloatColor other) {
        float _a = a + other.a;
        if (0 == _a) {
            return BLACK;
        }
        float _r = (r * a + other.r * other.a) / _a;
        float _g = (g * a + other.g * other.a) / _a;
        float _b = (b * a + other.b * other.a) / _a;
        return new FloatColor(_r, _g, _b, clamp(_a));
    }

    /**
     * Convert to full alpha.
     *
     * @return new FloatColor(r * a, g * a, b * a)
     */
    public FloatColor preMult() {
        return new FloatColor(r * a, g * a, b * a);
    }

    /**
     * Returns the "darkest" of two blended colors.
     *
     * @param other
     * @return preMult least.
     */
    public FloatColor min(FloatColor other) {
        FloatColor c1 = preMult();
        FloatColor c2 = other.preMult();
        return new FloatColor(Math.min(c1.r, c2.r), Math.min(c1.g, c2.g), Math.min(c1.b, c2.b), 1, clamped && other.clamped);
    }

    /**
     * Returns the "brightest" of two blended colors.
     *
     * @param other
     * @return preMult most.
     */
    public FloatColor max(FloatColor other) {
        FloatColor c1 = preMult();
        FloatColor c2 = other.preMult();
        return new FloatColor(Math.max(c1.r, c2.r), Math.max(c1.g, c2.g), Math.max(c1.b, c2.b), 1, clamped && other.clamped);
    }

    private float clamp(float r) {
        if (Float.isFinite(r)) {
            if (r < 0) {
                return 0;
            }
            if (r > 1) {
                return 1;
            }
            return r;
        }
        return 0;
    }

    @Override
    public int compareTo(FloatColor o) {
        int c = Float.compare(r * a, o.r * o.a);
        if (0 == c) {
            c = Float.compare(g * a, o.g * o.a);
        }
        if (0 == c) {
            c = Float.compare(b * a, o.b * o.a);
        }
        return c;
    }
}
