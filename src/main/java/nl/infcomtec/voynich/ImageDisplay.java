/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Loading and scale-to-fit math for showing a {@link CatalogEntry}'s actual
 * image file (not its stored thumbnail) in a Swing component, plus the
 * inverse: mapping a click back from displayed coordinates to the original
 * image's pixel coordinates. Used by {@link CatalogEntryEditor} so there's
 * one implementation of "which of an entry's locations still exists on
 * disk," one implementation of the scaling math, and one implementation of
 * its inverse, not copies drifting apart.
 */
final class ImageDisplay {

    private ImageDisplay() {
    }

    /**
     * @param entry the entry to find a readable file for
     * @return the first of {@code entry}'s {@link CatalogEntry.Location}s
     * that still exists on disk, or {@code null} if none do
     */
    static File pickExistingFile(CatalogEntry entry) {
        for (CatalogEntry.Location loc : entry.locations) {
            File f = new File(loc.path);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /**
     * Reads {@code entry}'s image off disk (via {@link #pickExistingFile})
     * and scales it to fit within {@code maxW}x{@code maxH}, preserving
     * aspect ratio. Decodes at full resolution first, so this can be slow
     * and memory-heavy for the largest scans (some merged foldout pages
     * exceed 8000px wide) — call off the EDT when responsiveness matters.
     *
     * @return the scaled image, or {@code null} if no location is readable
     * or decoding fails
     */
    static BufferedImage loadScaled(CatalogEntry entry, int maxW, int maxH) {
        File file = pickExistingFile(entry);
        if (null == file) {
            return null;
        }
        try {
            BufferedImage full = ImageIO.read(file);
            return null == full ? null : scaleToFit(full, maxW, maxH);
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Maps a click at {@code labelPoint} — in the coordinate space of the
     * {@code JLabel} showing a {@code iconW}x{@code iconH} scaled icon
     * within a {@code labelW}x{@code labelH} label (centered, per
     * {@code JLabel}'s default alignment when the icon is smaller than the
     * label) — back to {@code entry}'s original image pixel coordinates.
     * Clamped to the icon's bounds, since a click can land in the
     * letterboxed margin around a non-square image.
     */
    static Point toImageCoordinates(CatalogEntry entry, int iconW, int iconH, int labelW, int labelH,
            Point labelPoint) {
        int offX = (labelW - iconW) / 2;
        int offY = (labelH - iconH) / 2;
        int ix = Math.max(0, Math.min(iconW - 1, labelPoint.x - offX));
        int iy = Math.max(0, Math.min(iconH - 1, labelPoint.y - offY));
        double scaleX = entry.width / (double) iconW;
        double scaleY = entry.height / (double) iconH;
        return new Point((int) Math.round(ix * scaleX), (int) Math.round(iy * scaleY));
    }

    /**
     * Scales {@code src} down (or up) to fit within {@code maxW}x{@code maxH}
     * while preserving aspect ratio.
     */
    static BufferedImage scaleToFit(BufferedImage src, int maxW, int maxH) {
        double scale = Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
