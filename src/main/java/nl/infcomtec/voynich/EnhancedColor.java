/*
 * Copyright (c) 2014 by Walter Stroebel and InfComTec.
 * All rights reserved.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * Lots of color stuff.
 *
 * @author walter
 */
public class EnhancedColor extends Color implements Comparable {

    /**
     * Color.Black
     */
    public static final EnhancedColor ORIGIN = new EnhancedColor(Color.BLACK);
    private static int[] map16;
    private static int[] map8;
    protected static final Random random = new Random();

    public static final EnhancedColor NEVER_POST_IT = new EnhancedColor(255, 255, 153);

    /**
     * Clamps the RGB values and creates a color.
     *
     * @param r Red
     * @param g Green
     * @param b Blue
     * @return The color.
     */
    public static EnhancedColor clamped(long r, long g, long b) {
        return new EnhancedColor(clamp(r, 0, 255), clamp(g, 0, 255), clamp(b, 0, 255));
    }

    /**
     * Clamps the RGB values and creates a color.
     *
     * @param r Red
     * @param g Green
     * @param b Blue
     * @param a Alpha
     * @return The color.
     */
    public static int clamped(long r, long g, long b, long a) {
        return clamp(b, 0, 255) | (clamp(g, 0, 255) << 8) | (clamp(r, 0, 255) << 16) | (clamp(a, 0, 255) << 24);
    }

    /**
     * Compare red, then green and finally blue.
     *
     * @param other Other color.
     * @return Comparison.
     */
    public int compareRedGreen(Color other) {
        int c = Integer.compare(getRed(), other.getRed());
        if (0 == c) {
            c = Integer.compare(getGreen(), other.getGreen());
        }
        if (0 == c) {
            c = Integer.compare(getBlue(), other.getBlue());
        }
        return c;
    }

    /**
     * Compare green, then blue and finally red.
     *
     * @param other Other color.
     * @return Comparison.
     */
    public int compareGreenBlue(Color other) {
        int c = Integer.compare(getGreen(), other.getGreen());
        if (0 == c) {
            c = Integer.compare(getBlue(), other.getBlue());
        }
        if (0 == c) {
            c = Integer.compare(getRed(), other.getRed());
        }
        return c;
    }

    /**
     * Return the default grayscale value for a RGB color.
     *
     * @param c The colored pixel.
     * @return The default grayscale value.
     */
    public static int grayScale(Color c) {
        return Math.round(0.3f * c.getRed() + 0.59f * c.getGreen() + 0.11f * c.getBlue());
    }

    /**
     * Return the default grayscale value for a RGB color.
     *
     * @param c The colored pixel.
     * @return The default grayscale value.
     */
    public static EnhancedColor gray(Color c) {
        int v = clamp(Math.round(0.3f * c.getRed() + 0.59f * c.getGreen() + 0.11f * c.getBlue()), 0, 255);
        return new EnhancedColor(v, v, v);
    }

    /**
     * Calculates a color by mapping val onto min..max and stretching the result
     * over colors lighter then 0x808080.
     *
     * @param min Start of range.
     * @param max End of range.
     * @param val Value to map.
     * @return The color.
     */
    public static EnhancedColor map(int min, int max, int val) {
        if (min > max) {
            throw new RuntimeException("Min > Max");
        }
        double range = max - min;
        if (range <= 0) {
            range = 1;
        }
        double reach = 0x7F7F7F;
        double f = reach / range;
        double v = f * (val - min);
        int c = (int) Math.round(v);
        if (c > reach) {
            c = 0x7F7F7F;
        }
        c |= 0x808080;
        return new EnhancedColor(c);
    }

    public static int lightness(Color col) {
        return (col.getBlue() + col.getGreen() + col.getRed()) * col.getAlpha() / 255;
    }

    public static EnhancedColor fromHex(String hex) {
        return new EnhancedColor(Integer.parseInt(hex), true);
    }

    /**
     * As Hex string.
     *
     * @param c Color.
     * @return 08X.
     */
    public static String toHex(final Color c) {
        return String.format("%08X", c.getRGB());
    }

    /**
     * Return the difference between two colors as the distance between the two
     * points in RGB color space.
     *
     * @param c1 color1
     * @param c2 color2
     * @return The difference between two colors as the distance between the two
     * points in RGB color space.
     */
    public static double difference(Color c1, Color c2) {
        double r = c1.getRed() - c2.getRed();
        double g = c1.getGreen() - c2.getGreen();
        double b = c1.getBlue() - c2.getBlue();
        return Math.sqrt(r * r + g * g + b * b);
    }

    public static boolean equals(Color c1, Color c2) {
        return (c1.getRGB() & 0xFFFFFF) == (c2.getRGB() & 0xFFFFFF);
    }

    /**
     * Returns the closest color to the colors represented by only the upper 4
     * bits.
     *
     * The colors are truncated using a mask of 0xF0. Then the color is rounded
     * to the nearest color using the lower 4 bits.
     *
     * @param col Input color.
     * @return The closest 4 bit color.
     */
    public static EnhancedColor color16(Color col) {
        if (map16 == null) {
            map16 = new int[256];
            int d1 = 0;
            for (int c = 0; c < 256; c++) {
                map16[c] = ((c & 0xF0) + d1) & 0xF0;
                if ((c & 0xF) == 7) {
                    d1 = 0x10;
                }
                if ((c & 0xF) == 0xF) {
                    d1 = 0;
                }
            }
        }
        return new EnhancedColor(map16[col.getRed()], map16[col.getGreen()], map16[col.getBlue()]);
    }

    /**
     * Returns the closest color to the colors represented by only the upper 3
     * bits.
     *
     * The colors are truncated using a mask of 0xE0. Then the color is rounded
     * to the nearest color using the lower 5 bits.
     *
     * @param col Input color.
     * @return The closest 4 bit color.
     */
    public static EnhancedColor color8(Color col) {
        if (map8 == null) {
            map8 = new int[256];
            int d1 = 0;
            for (int c = 0; c < 256; c++) {
                map8[c] = ((c & 0xE0) + d1) & 0xE0;
                if ((c & 0x1F) == 0xF) {
                    d1 = 0x20;
                }
                if ((c & 0x1F) == 0x1F) {
                    d1 = 0;
                }
            }
        }
        return new EnhancedColor(map8[col.getRed()], map8[col.getGreen()], map8[col.getBlue()]);
    }

    /**
     * Clamp to min/max.
     *
     * @param v value to clamp.
     * @param min usually 0.
     * @param max usually 255.
     * @return clamped value.
     */
    public static int clamp(int v, int min, int max) {
        return Math.min(max, Math.max(min, v));
    }

    /**
     * Clamp to min/max.
     *
     * @param v value to clamp.
     * @param min usually 0.
     * @param max usually 1f.
     * @return clamped value.
     */
    public static float clamp(float v, float min, float max) {
        return Math.min(max, Math.max(min, v));
    }

    /**
     * CLamp to min/max.
     *
     * @param v value to clamp.
     * @param min usually 0.
     * @param max usually 255.
     * @return clamped value.
     */
    public static int clamp(long v, int min, int max) {
        return (int) Math.min(max, Math.max(min, v));
    }

    /**
     * Using K-means, reduce the [RGB,Color map].
     *
     * @param numColors Reduced number of colors.
     * @param maxIterations Limit.
     * @param pixels On input: RGB,Color and on output: orgRGB,reducedColor.
     */
    public static void kMeansColors(int numColors, int maxIterations, HashMap<Integer, EnhancedColor> pixels) {
        // Initialize centroids randomly
        List<EnhancedColor> centroids = new ArrayList<>(); // centroids for each cluster
        for (int i = 0; i < numColors; i++) {
            centroids.add(new EnhancedColor(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
        }

        // Run K-means algorithm
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            List<List<EnhancedColor>> clusters = new ArrayList<>();
            for (int i = 0; i < numColors; i++) {
                clusters.add(new ArrayList<>());
            }

            // Assign each data point to the closest centroid
            for (EnhancedColor dataPoint : pixels.values()) {
                int closestCentroidIndex = 0;
                double closestDistance = Double.MAX_VALUE;
                for (int i = 0; i < numColors; i++) {
                    double distance = dataPoint.difference(centroids.get(i));
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestCentroidIndex = i;
                    }
                }
                clusters.get(closestCentroidIndex).add(dataPoint);
            }

            // Update centroids to the mean color of the data points in each cluster
            for (int i = 0; i < numColors; i++) {
                if (!clusters.get(i).isEmpty()) {
                    int redSum = 0;
                    int greenSum = 0;
                    int blueSum = 0;
                    for (Color dataPoint : clusters.get(i)) {
                        redSum += dataPoint.getRed();
                        greenSum += dataPoint.getGreen();
                        blueSum += dataPoint.getBlue();
                    }
                    int clusterSize = clusters.get(i).size();
                    centroids.set(i, new EnhancedColor(redSum / clusterSize, greenSum / clusterSize, blueSum / clusterSize));
                }
            }
        }

        // Replace each pixel with the color of its closest centroid
        for (Integer pixel : pixels.keySet()) {
            EnhancedColor pixelColor = new EnhancedColor(pixel);
            int closestCentroidIndex = 0;
            double closestDistance = Double.MAX_VALUE;
            for (int j = 0; j < numColors; j++) {
                double distance = pixelColor.difference(centroids.get(j));
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestCentroidIndex = j;
                }
            }
            pixels.put(pixel, centroids.get(closestCentroidIndex));
        }
    }

    public static int HSBtoRGB(float[] hsb) {
        return HSBtoRGB(hsb[0], hsb[1], hsb[2]);
    }

    public static EnhancedColor fromHSB(float[] hsb) {
        return new EnhancedColor(HSBtoRGB(hsb[0], hsb[1], hsb[2]));
    }

    public static EnhancedColor random() {
        return new EnhancedColor(random.nextInt() | -16777216);
    }
    protected float[] hsbvals;

    /**
     * Clamps the RGB values and creates a color.
     *
     * @param r Red
     * @param g Green
     * @param b Blue
     */
    public EnhancedColor(long r, long g, long b) {
        super(clamp(r, 0, 255), clamp(g, 0, 255), clamp(b, 0, 255));
    }

    public EnhancedColor(Color c) {
        super(c.getRGB(), true);
    }

    public EnhancedColor(int r, int g, int b) {
        super(r, g, b);
    }

    public EnhancedColor(int r, int g, int b, int a) {
        super(r, g, b, a);
    }

    /**
     * Converts CIELAB values to an EnhancedColor object.
     *
     * @param L Luminance value.
     * @param a a* value from CIELAB.
     * @param b b* value from CIELAB.
     * @return EnhancedColor object with RGB values.
     */
    public static EnhancedColor fromCIELAB(double L, double a, double b) {
        double[] xyz = cielabToXYZ(L, a, b);
        int[] rgb = xyzToRGB(xyz[0], xyz[1], xyz[2]);
        return new EnhancedColor(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * Converts CIELAB to XYZ.
     *
     * @param L Luminance value.
     * @param a a* value from CIELAB.
     * @param b b* value from CIELAB.
     * @return Array of XYZ values.
     */
    private static double[] cielabToXYZ(double L, double a, double b) {
        double y = (L + 16) / 116;
        double x = a / 500 + y;
        double z = y - b / 200;

        x = 95.047 * ((x > 0.206897) ? Math.pow(x, 3) : (x - 16.0 / 116) / 7.787);
        y = 100.000 * ((y > 0.206897) ? Math.pow(y, 3) : (y - 16.0 / 116) / 7.787);
        z = 108.883 * ((z > 0.206897) ? Math.pow(z, 3) : (z - 16.0 / 116) / 7.787);

        return new double[]{x, y, z};
    }

    /**
     * Converts XYZ to RGB.
     *
     * @param x X component.
     * @param y Y component.
     * @param z Z component.
     * @return Array of RGB values.
     */
    private static int[] xyzToRGB(double x, double y, double z) {
        double r = (x * 3.2406 + y * -1.5372 + z * -0.4986) / 100;
        double g = (x * -0.9689 + y * 1.8758 + z * 0.0415) / 100;
        double b = (x * 0.0557 + y * -0.2040 + z * 1.0570) / 100;

        r = (r > 0.0031308) ? (1.055 * Math.pow(r, 1.0 / 2.4) - 0.055) : 12.92 * r;
        g = (g > 0.0031308) ? (1.055 * Math.pow(g, 1.0 / 2.4) - 0.055) : 12.92 * g;
        b = (b > 0.0031308) ? (1.055 * Math.pow(b, 1.0 / 2.4) - 0.055) : 12.92 * b;

        // Converting to 0-255 range after gamma correction
        int ri = (int) Math.round(Math.max(0, Math.min(255, r * 255)));
        int gi = (int) Math.round(Math.max(0, Math.min(255, g * 255)));
        int bi = (int) Math.round(Math.max(0, Math.min(255, b * 255)));

        return new int[]{ri, gi, bi};
    }

    public EnhancedColor(int rgb) {
        super(rgb);
    }

    public EnhancedColor(DataInput in) throws IOException {
        super(in.readInt());
    }

    public EnhancedColor(int rgba, boolean hasAlpha) {
        super(rgba, hasAlpha);
    }

    public EnhancedColor(float r, float g, float b) {
        super(r, g, b);
    }

    public EnhancedColor(float r, float g, float b, float a) {
        super(r, g, b, a);
    }

    public EnhancedColor(String hexColor) {
        super(fromHex(hexColor).getRGB());
    }

    public EnhancedColor(FloatColor ret) {
        super(ret.r, ret.g, ret.b);
    }

    public String longString() {
        getHSB();
        return String.format("%s %s H=%.3f,S=%.3f,B=%.3f", toHex(), YUV().toString(), hsbvals[0], hsbvals[1], hsbvals[2]);
    }

    public EnhancedColor getHueColor() {
        return new EnhancedColor(Color.HSBtoRGB(getHSB()[0], 0.5f, 0.5f));
    }

    /**
     * Convert to YUV.
     *
     * @return YUV.
     */
    public YUV YUV() {
        return new YUV(this);
    }

    /**
     * Calculates the XYZ values for the current color. This method is a
     * convenience wrapper that calls {@link #getXYZ(Color)} with this object's
     * color.
     *
     * @return an array of three double values representing XYZ color space: [X,
     * Y, Z]
     * @see #getXYZ(Color) for detailed explanation of the XYZ color space and
     * calculation details.
     */
    public double[] getXYZ() {
        return getXYZ(this);
    }

    /**
     * Calculates the XYZ values for the current color.
     *
     * The XYZ color space, which CIELAB is based on, has the following
     * theoretical ranges for its components:
     *
     * X: 0 to ~95.047, which corresponds to a reference white (usually D65
     * illuminant).
     *
     * Y: 0 to 100, representing the luminance of the color, where 100 is the
     * luminance of the reference white.
     *
     * Z: 0 to ~108.883, which also relates to a reference white.
     *
     * These ranges are based on the CIE 1931 color space and the D65
     * illuminant. The actual maximum values for X and Z can vary depending on
     * the illuminant and the colors involved. The values in the implementation
     * are normalized based on a D65 illuminant with Xn = 95.047, Yn = 100.000,
     * and Zn = 108.883.
     *
     * @param c standard ARGB (A is ignored).
     * @return
     */
    public static double[] getXYZ(Color c) {
        double[] xyz = new double[3];

        double r = c.getRed() / 255.0;
        double g = c.getGreen() / 255.0;
        double b = c.getBlue() / 255.0;

        r = (r > 0.04045) ? Math.pow((r + 0.055) / 1.055, 2.4) : r / 12.92;
        g = (g > 0.04045) ? Math.pow((g + 0.055) / 1.055, 2.4) : g / 12.92;
        b = (b > 0.04045) ? Math.pow((b + 0.055) / 1.055, 2.4) : b / 12.92;

        r *= 100.0;
        g *= 100.0;
        b *= 100.0;

        xyz[0] = r * 0.4124564 + g * 0.3575761 + b * 0.1804375;
        xyz[1] = r * 0.2126729 + g * 0.7151522 + b * 0.0721750;
        xyz[2] = r * 0.0193339 + g * 0.1191920 + b * 0.9503041;

        return xyz;
    }

    /**
     * @see #getXYZ(Color)
     * @param c standard ARGB (A is ignored).
     * @param xyz if at least 3 doubles will get set, else a new array will be
     * allocated.
     * @return an array of 3 doubles for x,y and z.
     */
    public static double[] getXYZ(int c, double[] xyz) {
        if (xyz == null || xyz.length < 3) {
            xyz = new double[3];
        }
        double r = ((c >> 16) & 0xFF) / 255.0;
        double g = ((c >> 8) & 0xFF) / 255.0;
        double b = (c & 0xFF) / 255.0;
        r = (r > 0.04045) ? Math.pow((r + 0.055) / 1.055, 2.4) : r / 12.92;
        g = (g > 0.04045) ? Math.pow((g + 0.055) / 1.055, 2.4) : g / 12.92;
        b = (b > 0.04045) ? Math.pow((b + 0.055) / 1.055, 2.4) : b / 12.92;
        r *= 100.0;
        g *= 100.0;
        b *= 100.0;
        xyz[0]
                = r * 0.4124564 + g * 0.3575761 + b * 0.1804375;
        xyz[1] = r * 0.2126729 + g * 0.7151522 + b * 0.0721750;
        xyz[2] = r * 0.0193339 + g * 0.1191920 + b * 0.9503041;
        return xyz;
    }

    /**
     * Gets the magnitude of the CIELAB value for this color in the range
     * 0..factor exclusive.
     *
     * @param factor Guarded for rounding errors.
     * @return The magnitude of the normalized CIELAB "vector" in the specified
     * range.
     */
    public int magnitudeCIE(int factor) {
        double[] cie = getCIELAB();
        double l = cie[0] / 100; // Normalize L* to 0-1.
        double a = cie[1] / 127; // Normalize a* to -1 to +1.
        double b = cie[2] / 127; // Normalize b* to -1 to +1.
        double mag = Math.min(Math.sqrt(l * l + a * a + b * b), 1.0); // Euclidean distance in normalized CIELAB space.
        int ret = (int) Math.round(mag * (factor - 1)); // Scale it to the range defined by factor.
        return ret >= factor ? factor - 1 : ret; // Clamp to 0 to factor-1.
    }

    /**
     * Calculates the CIELAB values for the current color. This method is a
     * convenience wrapper that calls {@link #getCIELAB(Color)} with this
     * object's color.
     *
     * @return an array of three double values representing the CIELAB color
     * space: [L, a, b]
     * @see #getCIELAB(Color) for detailed explanation of the CIELAB color space
     * and calculation details.
     */
    public double[] getCIELAB() {
        return getCIELAB(this);
    }

    /**
     * Calculates the CIELAB values for the current color.
     *
     * The CIELAB color space includes:
     *
     * L* axis (luminance) ranges from 0 to 100, where 0 is black and 100 is
     * white.
     *
     * a* axis (green–red) typically ranges from -128 to +127, where negative
     * values represent green and positive values represent red.
     *
     * b* axis (blue–yellow) also typically ranges from -128 to +127, with
     * negative values for blue and positive for yellow.
     *
     * @param c
     * @return an array of three double values representing the CIELAB color
     * space: [L, a, b]
     */
    public static double[] getCIELAB(Color c) {
        double[] cielab = new double[3];

        double[] xyz = getXYZ(c);

        double x = xyz[0] / 95.047;
        double y = xyz[1] / 100.000;
        double z = xyz[2] / 108.883;

        x = (x > 0.008856) ? Math.pow(x, 1.0 / 3.0) : (903.3 * x + 16) / 116;
        y = (y > 0.008856) ? Math.pow(y, 1.0 / 3.0) : (903.3 * y + 16) / 116;
        z = (z > 0.008856) ? Math.pow(z, 1.0 / 3.0) : (903.3 * z + 16) / 116;

        cielab[0] = Math.max(0, 116 * y - 16);
        cielab[1] = 500 * (x - y);
        cielab[2] = 200 * (y - z);

        return cielab;
    }

    public static double[] getCIELAB(int c, double[] cielab) {
        if (cielab == null || cielab.length < 3) {
            cielab = new double[3];
        }
        // xyz aliases cielab; holds XYZ until overwritten below.
        double[] xyz = getXYZ(c, cielab);

        double x = xyz[0] / 95.047;
        double y = xyz[1] / 100.000;
        double z = xyz[2] / 108.883;

        x = (x > 0.008856) ? Math.pow(x, 1.0 / 3.0) : (903.3 * x + 16) / 116;
        y = (y > 0.008856) ? Math.pow(y, 1.0 / 3.0) : (903.3 * y + 16) / 116;
        z = (z > 0.008856) ? Math.pow(z, 1.0 / 3.0) : (903.3 * z + 16) / 116;

        cielab[0] = Math.max(0, 116 * y - 16);
        cielab[1] = 500 * (x - y);
        cielab[2] = 200 * (y - z);

        return cielab;
    }

    /**
     * Convert to YUV.
     *
     * @return YUV.
     */
    public YUV getYUV() {
        return new YUV(this);
    }

    /**
     * Return the default grayscale value for a RGB color.
     *
     * @return The default grayscale value.
     */
    public int grayScale() {
        return Math.round(0.3f * getRed() + 0.59f * getGreen() + 0.11f * getBlue());
    }

    public void write(DataOutput out) throws IOException {
        out.writeInt(getRGB());
    }

    /**
     * Return the difference between two colors as the distance between the two
     * points in RGB color space.
     *
     * @param c Color to get the distance to;
     * @return The difference between two colors as the distance between the two
     * points in RGB color space.
     */
    public double difference(Color c) {
        double r = getRed() - c.getRed();
        double g = getGreen() - c.getGreen();
        double b = getBlue() - c.getBlue();
        double ret = Math.sqrt(r * r + g * g + b * b);
        // System.out.println("diff " + this + " " + c + " = " + ret);
        return ret;
    }

    /**
     * True if the color is opaque (no alpha).
     *
     * @return True if the color is opaque (no alpha).
     */
    public boolean isOpaque() {
        return getAlpha() == 255;
    }

    /**
     * True if the color is less then opaque.
     *
     * @return True if the color is less then opaque.
     */
    public boolean hasAlpha() {
        return getAlpha() < 255;
    }

    /**
     * True if RGBA equals zero, black fully transparent.
     *
     * @return getRGB()==0.
     */
    public boolean isZero() {
        return getRGB() == 0;
    }

    /**
     * The sum of R+G+B.
     *
     * @return (getBlue() + getGreen() + getRed()) times alpha(0..1) so 0..765
     * inclusive.
     */
    public int lightness() {
        int sum = getBlue() + getGreen() + getRed();
        sum *= getAlpha();
        sum /= 255;
        return sum;
    }

    @Override
    public String toString() {
        return String.format("(%08X)", getRGB());
    }

    public String toHex() {
        return toHex(this);
    }

    /**
     * Return this color + c divided by 2.
     *
     * @param c Color to blend with.
     * @return this color + c divided by 2.
     */
    public EnhancedColor blend(Color c) {
        return new EnhancedColor((getRed() + c.getRed()) / 2, (getGreen() + c.getGreen()) / 2, (getBlue() + c.getBlue()) / 2);
    }

    /**
     * Return this color + c for ratio f.
     *
     * @param c Color to blend with.
     * @param f ratio, 0 is all this, 1 is all c
     * @return this color + c divided by 2.
     */
    public EnhancedColor blend(Color c, float f) {
        float[] c0 = getRGBColorComponents(null);
        float[] c1 = c.getRGBColorComponents(null);
        float f1 = (1.0f - f);
        return new EnhancedColor(
                clamp(c0[0] * f1 + c1[0] * f, 0, 1f),
                clamp(c0[1] * f1 + c1[1] * f, 0, 1f),
                clamp(c0[2] * f1 + c1[2] * f, 0, 1f));
    }

    /**
     * Return a new color with max(rgba,c.rgba).
     *
     * @param c Color to blend with.
     * @return a new color with max(rgba,c.rgba).
     */
    public EnhancedColor mergeMax(Color c) {
        return new EnhancedColor(
                Math.max(getRed(), c.getRed()),
                Math.max(getGreen(), c.getGreen()),
                Math.max(getBlue(), c.getBlue()),
                Math.max(getAlpha(), c.getAlpha()));
    }

    /**
     * Return a new color with max(rgb+inc,255).
     *
     * @param inc value to add to all components clamped to 255 as max.
     * @return a new color with max(rgb+inc,255).
     */
    public EnhancedColor increment(int inc) {
        return new EnhancedColor(
                Math.min(getRed() + inc, 255),
                Math.min(getGreen() + inc, 255),
                Math.min(getBlue() + inc, 255),
                getAlpha());
    }

    /**
     * Return a new color with max(rgb-inc,0).
     *
     * @param inc value to add to all components clamped to 255 as max.
     * @return a new color with max(rgb-inc,0).
     */
    public EnhancedColor decrement(int inc) {
        return new EnhancedColor(
                Math.max(getRed() - inc, 0),
                Math.max(getGreen() - inc, 0),
                Math.max(getBlue() - inc, 0),
                getAlpha());
    }

    /**
     * Return a new color with max(rgb+1,255).
     *
     * @return a new color with max(rgb+1,255).
     */
    public EnhancedColor increment() {
        return increment(1);
    }

    /**
     * Return a new color with max(rgb-1,0).
     *
     * @return a new color with max(rgb-1,0).
     *
     */
    public EnhancedColor decrement() {
        return decrement(1);
    }

    /**
     * Return the brightest color.
     *
     * @param c Color to blend with.
     * @return The brightest color.
     */
    public EnhancedColor max(Color c) {
        if (ORIGIN.difference(c) > ORIGIN.difference(this)) {
            return new EnhancedColor(c);
        }
        return this;
    }

    /**
     * Linearly interpolates between two colors in CIELAB color space.
     *
     * Unlike RGB interpolation, CIELAB interpolation is perceptually uniform,
     * meaning equal steps in t produce equal perceived color differences.
     *
     * @param from Start color, t=0.
     * @param to End color, t=1.
     * @param t Interpolation factor. 0 returns from, 1 returns to, values
     * outside 0..1 extrapolate beyond the endpoints. Out-of-gamut results are
     * clamped by the XYZ to RGB conversion.
     * @return The interpolated color in RGB space.
     */
    public static EnhancedColor lerpCIELAB(final Color from, final Color to, float t) {
        double[] labA = getCIELAB(from);
        double[] labB = getCIELAB(to);
        return fromCIELAB(
                labA[0] + t * (labB[0] - labA[0]),
                labA[1] + t * (labB[1] - labA[1]),
                labA[2] + t * (labB[2] - labA[2]));
    }

    /**
     * Patent Troll head shot.
     *
     * @param c user picked color.
     * @return c or really near c while showing troll the middle finger.
     */
    public static EnhancedColor cleanColor(final Color c) {
        if (!isTooCloseToPostIt(c)) {
            return new EnhancedColor(c);
        }
        EnhancedColor a = lerpCIELAB(c, NEVER_POST_IT, -0.5f);
        EnhancedColor b = lerpCIELAB(c, NEVER_POST_IT, 1.5f);
        return deltaE(a, NEVER_POST_IT) > deltaE(b, NEVER_POST_IT) ? a : b;
    }

    /**
     * Return the merged color.
     *
     * @param c Colors to blend with.
     * @return The average color.
     */
    public EnhancedColor merge(Color... c) {
        int r = getRed();
        int g = getGreen();
        int b = getBlue();
        int n = 1;
        if (null != c) {
            for (Color a : c) {
                r += a.getRed();
                g += a.getGreen();
                b += a.getBlue();
                n++;
            }
        }
        return new EnhancedColor(r / n, g / n, b / n);
    }

    public boolean equalsRGB(Object obj) {
        if (obj instanceof EnhancedColor) {
            return (getRGB() & 0xFFFFFF) == (((Color) obj).getRGB() & 0xFFFFFF);
        }
        if (obj instanceof Color) {
            return (getRGB() & 0xFFFFFF) == (((Color) obj).getRGB() & 0xFFFFFF);
        }
        return super.equals(obj); //To change body of generated methods, choose Tools | Templates.
    }

    public boolean equalsRGB(Color obj) {
        if (null != obj) {
            return (getRGB() & 0xFFFFFF) == (((Color) obj).getRGB() & 0xFFFFFF);
        }
        return super.equals(obj); //To change body of generated methods, choose Tools | Templates.
    }

    /**
     * Returns the closest color to the colors represented by only the upper 4
     * bits.
     *
     * The colors are truncated using a mask of 0xF0. Then the color is rounded
     * to the nearest color using the lower 4 bits.
     *
     * @return The closest 4 bit color.
     */
    public EnhancedColor color16() {
        return color16(this);
    }

    /**
     * Returns the closest color to the colors represented by only the upper 3
     * bits.
     *
     * The colors are truncated using a mask of 0xE0. Then the color is rounded
     * to the nearest color using the lower 5 bits.
     *
     * @return The closest 3 bit color.
     */
    public EnhancedColor color8() {
        return color8(this);
    }

    /**
     * Clone this color with alpha set to the value.
     *
     * @param alpha
     * @return Same RGB and A=alpha.
     */
    public EnhancedColor setAlpha(int alpha) {
        return new EnhancedColor(getRed(), getGreen(), getBlue(), clamp(alpha, 0, 255));
    }

    /**
     * Subtract c2 from this color(clamped) ignoring alpha.
     *
     * @param c2 color to subtract.
     * @return new color.
     */
    public EnhancedColor subtract(EnhancedColor c2) {
        return new EnhancedColor(clamp(getRed() - c2.getRed(), 0, 255), clamp(getGreen() - c2.getGreen(), 0, 255), clamp(getBlue() - c2.getBlue(), 0, 255));
    }

    public EnhancedColor invert() {
        return new EnhancedColor(255 - getRed(), 255 - getGreen(), 255 - getBlue(), getAlpha());
    }

    public synchronized float[] getHSB() {
        if (null == hsbvals) {
            hsbvals = RGBtoHSB(getRed(), getGreen(), getBlue(), null);
        }
        return hsbvals;
    }

    public String getDescr() {
        StringBuilder sb = new StringBuilder();
        sb.append("UniColor{").append(System.lineSeparator());
        FloatColor fc = new FloatColor(this);
        sb.append(String.format("ARGB: 0x%s", toHex())).append(System.lineSeparator());
        sb.append(String.format("RGBA: %f, %f, %f, %f", fc.r, fc.g, fc.b, fc.a)).append(System.lineSeparator());
        sb.append(String.format("YUV : %f, %f, %f", YUV().Y / 255.0, YUV().U / 255.0, YUV().V / 255.0)).append(System.lineSeparator());
        sb.append(String.format("HSB : %f, %f, %f", getHSB()[0], getHSB()[1], getHSB()[2])).append(System.lineSeparator());
        sb.append('}');
        return sb.toString();
    }

    @Override
    public int compareTo(Object t) {
        if (t instanceof Color) {
            return YUV().compareTo((Color) t);
        }
        if (t instanceof YUV) {
            return YUV().compareTo((YUV) t);
        }
        if (t instanceof Integer) {
            return Integer.compare(getRGB(), (int) t);
        }
        throw new RuntimeException("Cannot compare " + getClass().getSimpleName() + " with " + t);
    }

    public EnhancedColor setGreen(int green) {
        return new EnhancedColor(getRed(), clamp(green, 0, 255), getBlue());
    }

    public EnhancedColor setBlue(int blue) {
        return new EnhancedColor(getRed(), getGreen(), clamp(blue, 0, 255));
    }

    public EnhancedColor setRed(int red) {
        return new EnhancedColor(clamp(red, 0, 255), getGreen(), getBlue());
    }

    /**
     * Length of the 3D vector(r,g,b).
     *
     * @return Length of the 3D vector(r,g,b).
     */
    public double magnitude() {
        return Math.sqrt(getRed() * getRed() + getGreen() * getGreen() + getBlue() * getBlue());
    }

    public static boolean isTooCloseToPostIt(Color c) {
        return deltaE(c, NEVER_POST_IT) < 10.0;
    }

    /**
     * Tests whether the given CIELAB coordinates fall within the sRGB gamut.
     * Converts to linear RGB without gamma encoding or clamping and checks that
     * all three channels are in [0..1].
     *
     * @param L Luminance, 0 (black) to 100 (white).
     * @param a Green-red opponent axis, typically -128..+127.
     * @param b Blue-yellow opponent axis, typically -128..+127.
     * @return true if the color is representable on an sRGB display.
     */
    public static boolean isInGamut(double L, double a, double b) {
        double[] xyz = cielabToXYZ(L, a, b);  // make package-visible
        double r = (xyz[0] * 3.2406 + xyz[1] * -1.5372 + xyz[2] * -0.4986) / 100;
        double g = (xyz[0] * -0.9689 + xyz[1] * 1.8758 + xyz[2] * 0.0415) / 100;
        double bl = (xyz[0] * 0.0557 + xyz[1] * -0.2040 + xyz[2] * 1.0570) / 100;
        return r >= 0 && g >= 0 && bl >= 0 && r <= 1 && g <= 1 && bl <= 1;
    }

    public static double deltaE(Color c1, Color c2) {
        double[] lab1 = getCIELAB(c1);
        double[] lab2 = getCIELAB(c2);
        double dL = lab1[0] - lab2[0];
        double da = lab1[1] - lab2[1];
        double db = lab1[2] - lab2[2];
        return Math.sqrt(dL * dL + da * da + db * db);
    }
}
