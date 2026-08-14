/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Paints a list of already-decoded images into one composite grid image —
 * the shared compositor behind both "Two-Page View" (two full-resolution
 * pages, forced to a 2-column layout) and "Thumbnail Matrix" (N cached
 * 256×256 thumbnails, auto square-ish layout) in {@link Voynich}. Knows
 * nothing about where a cell's image came from (disk vs. thumbnail cache) —
 * that's entirely the caller's business, kept in {@link RegionView} and
 * {@link OverviewPanel} respectively.
 */
final class ImageGrid {

    private ImageGrid() {
    }

    /**
     * @return the composite's pixel dimensions for {@code count} cells of
     * {@code cellSize} arranged {@code columns} wide (rows implied by
     * {@code count}) — lets a caller (Thumbnail Matrix's screen-fit check)
     * know the size before actually painting anything.
     */
    static Dimension dimensions(int count, int columns, Dimension cellSize) {
        int rows = (int) Math.ceil((double) count / columns);
        return new Dimension(columns * cellSize.width, rows * cellSize.height);
    }

    /**
     * @return {@code Math.ceil(Math.sqrt(count))}, clamped to at least 1 —
     * a simple square-ish column count for a caller that doesn't want to
     * force a specific layout (Thumbnail Matrix). Two-Page View instead
     * passes a fixed {@code columns=2} to {@link #paint} directly.
     */
    static int squareColumns(int count) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(count)));
    }

    /**
     * Paints {@code images} left-to-right, top-to-bottom into a fresh grid
     * of {@code columns} columns, each cell exactly {@code cellSize},
     * scaled down (never up) to fit its cell while preserving aspect ratio
     * and centered within it. A {@code null} entry in {@code images} (e.g.
     * a thumbnail that hasn't finished loading) paints as a blank cell
     * rather than failing the whole composite.
     */
    static BufferedImage paint(List<BufferedImage> images, int columns, Dimension cellSize) {
        Dimension size = dimensions(images.size(), columns, cellSize);
        BufferedImage grid = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = grid.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, size.width, size.height);
        for (int i = 0; i < images.size(); i++) {
            BufferedImage cell = images.get(i);
            if (null == cell) {
                continue;
            }
            int col = i % columns;
            int row = i / columns;
            int cellX = col * cellSize.width;
            int cellY = row * cellSize.height;
            double scale = Math.min(1.0, Math.min(
                    (double) cellSize.width / cell.getWidth(),
                    (double) cellSize.height / cell.getHeight()));
            int drawW = (int) Math.round(cell.getWidth() * scale);
            int drawH = (int) Math.round(cell.getHeight() * scale);
            int drawX = cellX + (cellSize.width - drawW) / 2;
            int drawY = cellY + (cellSize.height - drawH) / 2;
            g.drawImage(cell, drawX, drawY, drawW, drawH, null);
        }
        g.dispose();
        return grid;
    }
}
