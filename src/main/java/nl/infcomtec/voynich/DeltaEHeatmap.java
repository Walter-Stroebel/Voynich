/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/**
 * Per-cell CIE76 &Delta;E from an image's average colour, rendered over
 * {@link ColorImage#labThumbnail}'s fixed {@link ColorImage#THUMB_SIZE}
 * grid.
 * <p>
 * The reference is a pixel-count-weighted mean Lab over the whole page, not
 * the single most frequent exact RGB triple: many of these scans are
 * photographed against a flat black backdrop, and a solid-colour backdrop
 * repeats exactly far more often than the paper's naturally grainy texture
 * does, so "most frequent colour" tends to pick the backdrop, not the paper.
 * A weighted mean stays anchored to whichever colour actually covers most of
 * the page's area.
 * </p>
 * <p>
 * A frequency count (see {@link FrequencyBarChart}) says an outlier colour
 * exists; this says <em>where</em> — spatially exposing ink, staining, or a
 * pigment anomaly that a ranked list alone can't show.
 * </p>
 */
final class DeltaEHeatmap extends JPanel {

    private final BufferedImage heat;
    private final double maxDeltaE;

    DeltaEHeatmap(ColorImage image) {
        ColorBase.TriLabColor reference = weightedMeanLab(image);
        int size = ColorImage.THUMB_SIZE;
        double[] deltas = new double[size * size];
        double max = 0;
        for (int i = 0; i < deltas.length; i++) {
            deltas[i] = ColorBase.deltaE(image.labThumbnail[i], reference);
            max = Math.max(max, deltas[i]);
        }
        maxDeltaE = max;
        heat = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < deltas.length; i++) {
            double t = max <= 0 ? 0 : deltas[i] / max;
            heat.setRGB(i % size, i / size, heatColor(t).getRGB());
        }
        setPreferredSize(new Dimension(size * 2, size * 2 + 20));
        setBackground(Color.BLACK);
    }

    /**
     * @return a {@link ColorBase.TriLabColor} for the pixel-count-weighted
     * mean Lab of every distinct colour in {@code image}'s full-resolution
     * {@link ColorBase#cache} — resolved through
     * {@link ColorBase#lab2TriLabColor}, so it carries the same clamped,
     * cached short representation as every other {@link ColorBase.TriLabColor}
     * and is directly comparable via {@link ColorBase#deltaE}
     */
    private static ColorBase.TriLabColor weightedMeanLab(ColorImage image) {
        double sumL = 0;
        double sumA = 0;
        double sumB = 0;
        long total = 0;
        for (ColorBase.TriLabColor c : image.cb.cache.values()) {
            sumL += (c.l / 100.0) * c.count;
            sumA += (c.a / 100.0) * c.count;
            sumB += (c.b / 100.0) * c.count;
            total += c.count;
        }
        return ColorBase.lab2TriLabColor(sumL / total, sumA / total, sumB / total);
    }

    /**
     * @param t normalized ΔE in [0, 1]
     * @return blue (t=0, matches the reference) through cyan/green/yellow to
     * red (t=1, maximally different) — a plain hue sweep, legible without a
     * separate legend key since the two endpoints are unambiguous
     */
    private static Color heatColor(double t) {
        float clamped = (float) Math.max(0, Math.min(1, t));
        return Color.getHSBColor(0.66f * (1f - clamped), 1f, 1f);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int side = Math.max(1, Math.min(getWidth(), getHeight() - 20));
        int offX = (getWidth() - side) / 2;
        g2.drawImage(heat, offX, 0, side, side, null);
        g2.setColor(Color.LIGHT_GRAY);
        g2.drawString(String.format("blue = near the page's average colour, red = max ΔE (%.1f) from it", maxDeltaE),
                offX, side + 16);
    }
}
