/*
 */
package nl.infcomtec.voynich;

import java.awt.Color;

/**
 * Luma, Cr, Cb.
 *
 * @author walter
 */
public class YUV {

    /**
     * Luma.
     */
    public double Y;
    /**
     * Red deviation.
     *
     */
    public double U;
    /**
     * Blue deviation.
     */
    public double V;

    /**
     * RGB to YUV.
     *
     * @param c Color.
     */
    public YUV(Color c) {
        Y = 0.257 * c.getRed() + 0.504 * c.getGreen() + 0.098 * c.getBlue() + 16;
        U = -0.148 * c.getRed() - 0.291 * c.getGreen() + 0.439 * c.getBlue() + 128;
        V = 0.439 * c.getRed() - 0.368 * c.getGreen() - 0.071 * c.getBlue() + 128;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 97 * hash + (int) (Double.doubleToLongBits(this.Y) ^ (Double.doubleToLongBits(this.Y) >>> 32));
        hash = 97 * hash + (int) (Double.doubleToLongBits(this.U) ^ (Double.doubleToLongBits(this.U) >>> 32));
        hash = 97 * hash + (int) (Double.doubleToLongBits(this.V) ^ (Double.doubleToLongBits(this.V) >>> 32));
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final YUV other = (YUV) obj;
        if (Double.doubleToLongBits(this.Y) != Double.doubleToLongBits(other.Y)) {
            return false;
        }
        if (Double.doubleToLongBits(this.U) != Double.doubleToLongBits(other.U)) {
            return false;
        }
        return Double.doubleToLongBits(this.V) == Double.doubleToLongBits(other.V);
    }

    /**
     * Clone.
     *
     * @param c Color.
     */
    public YUV(YUV c) {
        Y = c.Y;
        U = c.U;
        V = c.V;
    }

    /**
     * Get the length of the vector (Y,U,V).
     *
     * Note that U and V are remapped to -127,128.
     *
     * @return The length of the vector (Y,U,V).
     */
    public double getLen() {
        return Math.sqrt(Y * Y + (U - 127) * (U - 127) + (V - 127) * (V - 127));
    }

    /**
     * Get the distance between this and the other (Y,U,V).
     *
     * Note that the YUV components do not need to be normalized, as long as
     * they have the same magnitude.
     *
     * @return The length of the vector (Y,U,V).
     */
    public double getDistance(YUV other) {
        double y = Y - other.Y;
        double u = U - other.U;
        double v = V - other.V;
        return Math.sqrt(y * y + u * u + v * v);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Y=").append(String.format("%.3f", Y));
        sb.append(",U=").append(String.format("%.3f", U));
        sb.append(",V=").append(String.format("%.3f", V));
        return sb.toString();
    }

    /**
     * From values.
     *
     * @param y Luma.
     * @param u Red deviation.
     * @param v Blue deviation.
     */
    public YUV(double y, double u, double v) {
        Y = y;
        U = u;
        V = v;
    }

    /**
     * From values.
     *
     * @param y Luma.
     * @param u Red deviation.
     * @param v Blue deviation.
     * @return YUV.
     */
    public static YUV comps(double y, double u, double v) {
        return new YUV(y, u, v);
    }

    /**
     * YUV to RGB.
     *
     * @return Color.
     */
    public EnhancedColor from() {
        double y = Y - 16;
        double u = U - 128;
        double v = V - 128;
        double R = 1.164 * y + 1.596 * v;
        double G = 1.164 * y - 0.392 * u - 0.813 * v;
        double B = 1.164 * y + 2.017 * u;
        return EnhancedColor.clamped(Math.round(R), Math.round(G), Math.round(B));
    }

    public int getRGB() {
        double y = Y - 16;
        double u = U - 128;
        double v = V - 128;
        double R = 1.164 * y + 1.596 * v;
        double G = 1.164 * y - 0.392 * u - 0.813 * v;
        double B = 1.164 * y + 2.017 * u;
        return EnhancedColor.clamped(Math.round(R), Math.round(G), Math.round(B), 255L);
    }

    /**
     * First compares Y, then U, then V.
     *
     * @param c Will be converted to YUV.
     * @return First compares Y, then U, then V.
     */
    public int compareTo(Color c) {
        return compareTo(new YUV(c));
    }

    /**
     * First compares most significant component(s).
     *
     * @param yuv YUV other.
     * @return Comparison.
     */
    public int compareTo(YUV yuv) {
        int c = Double.compare(Y, yuv.Y);
        if (0 == c) {
            double u = U;
            double v = V;
            double uv1 = Math.sqrt(u * u + v * v);
            u = yuv.U;
            v = yuv.V;
            double uv2 = Math.sqrt(u * u + v * v);
            return Double.compare(uv1, uv2);
        }
        return c;
    }
}
