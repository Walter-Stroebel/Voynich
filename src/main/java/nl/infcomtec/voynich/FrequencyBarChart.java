/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import javax.swing.JPanel;

/**
 * Ranked colour-frequency swatch bars for one {@link ColorImage}: one row
 * per perceptual colour cluster, an actual-colour swatch plus a bar whose
 * length is proportional to {@code log10(pixel count)} — linear would leave
 * every ink colour invisible next to the dominant paper colour, which
 * typically outnumbers them by several orders of magnitude.
 * <p>
 * Ranked by <em>exact</em> RGB value, this would be dominated by whatever
 * region of the page is flattest: these scans are photographed against a
 * solid black backdrop, and a flat region repeats a handful of exact RGB
 * values enormous numbers of times, while the paper — even though it covers
 * far more of the page — is naturally grainy and splits its "true" colour
 * across thousands of near-identical-but-not-exact RGB values, each
 * individually small. Ranking by exact value buries the paper and ink
 * entirely under backdrop noise. Instead, colours are first grouped into
 * {@link #BIN_WIDTH}-wide CIELab cells (roughly 2&times; the CIE76
 * just-noticeable-difference threshold) and ranked by each cell's summed
 * pixel count, so "paper", "backdrop", and each ink colour collapse into one
 * row apiece regardless of how many distinct exact RGB values scan noise
 * split them into.
 * </p>
 * <p>
 * Capped to the {@link #MAX_ROWS} largest clusters.
 * </p>
 */
final class FrequencyBarChart extends JPanel {

    private static final int MAX_ROWS = 60;
    private static final int ROW_H = 16;
    private static final int SWATCH_W = 24;
    private static final int LABEL_W = 90;
    /**
     * CIELab bin width in the {@code short} L*, a*, b* fields' ×100 scale —
     * 500 here means 5.0 real Lab units per bin.
     */
    private static final int BIN_WIDTH = 500;

    /**
     * One perceptual colour cluster: a summed pixel count plus a
     * count-weighted running sum of RGB, so the swatch shown is the
     * cluster's average appearance rather than an arbitrary member of it.
     */
    private static final class Bin {

        long count;
        long sumR;
        long sumG;
        long sumB;

        Color averageColor() {
            return new Color((int) (sumR / count), (int) (sumG / count), (int) (sumB / count));
        }
    }

    private final List<Bin> rows;
    private final long maxCount;

    FrequencyBarChart(ColorImage image) {
        TreeMap<TriElm, Bin> bins = new TreeMap<>();
        for (ColorBase.TriLabColor c : image.cb.cache.values()) {
            TriElm key = new TriElm();
            key.v0 = (short) (c.l / BIN_WIDTH);
            key.v1 = (short) (c.a / BIN_WIDTH);
            key.v2 = (short) (c.b / BIN_WIDTH);
            Bin bin = bins.get(key);
            if (null == bin) {
                bin = new Bin();
                bins.put(key, bin);
            }
            bin.count += c.count;
            bin.sumR += (long) c.v0 * c.count;
            bin.sumG += (long) c.v1 * c.count;
            bin.sumB += (long) c.v2 * c.count;
        }
        List<Bin> sorted = new ArrayList<>(bins.values());
        sorted.sort(new Comparator<Bin>() {
            @Override
            public int compare(Bin a, Bin b) {
                return Long.compare(b.count, a.count);
            }
        });
        rows = sorted.size() > MAX_ROWS ? sorted.subList(0, MAX_ROWS) : sorted;
        long max = 1;
        for (Bin b : rows) {
            max = Math.max(max, b.count);
        }
        maxCount = max;
        setPreferredSize(new Dimension(520, ROW_H * rows.size() + 16));
        setBackground(Color.BLACK);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int barMaxW = Math.max(50, getWidth() - SWATCH_W - LABEL_W - 30);
        double logMax = Math.log10(maxCount + 1);
        int y = 8;
        for (Bin b : rows) {
            Color swatch = b.averageColor();
            g2.setColor(swatch);
            g2.fillRect(8, y, SWATCH_W, ROW_H - 2);
            g2.setColor(Color.GRAY);
            g2.drawRect(8, y, SWATCH_W, ROW_H - 2);

            double frac = logMax <= 0 ? 0 : Math.log10(b.count + 1) / logMax;
            int barW = Math.max(1, (int) Math.round(frac * barMaxW));
            g2.setColor(swatch);
            g2.fillRect(8 + SWATCH_W + 6, y, barW, ROW_H - 2);

            g2.setColor(Color.LIGHT_GRAY);
            g2.drawString(String.format("%,d px", b.count), 8 + SWATCH_W + 6 + barMaxW + 6, y + ROW_H - 4);
            y += ROW_H;
        }
    }
}
