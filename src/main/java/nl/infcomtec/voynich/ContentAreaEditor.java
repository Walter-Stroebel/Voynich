/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Opens a {@link ContentAreaCanvas} in its own {@link ViewFrame}-hosted
 * window, spawned by a button in {@link CatalogEntryEditor} the same way as
 * "Color Frequency"/"ΔE Heatmap" — except this one is interactive rather
 * than a passive visualization, so it carries its own Clear/Commit/Cancel
 * controls rather than being pure display.
 * <p>
 * {@link CatalogEntry#contentArea} is deliberately never auto-detected —
 * not because ink-vs-blank-vellum is inherently undetectable (unlike the
 * page-vs-stacked-pages-beneath boundary this tool used to trace, which
 * really is same-material-same-lighting), but because a fold is never a
 * true boundary no matter how strong its shadow line looks, and because
 * judging how faint a mark has to be before it's still "content" (a wash
 * dim enough to nearly miss even under the tracing loupe) is exactly the
 * kind of call a human makes better and faster than tuning a threshold.
 * </p>
 */
final class ContentAreaEditor {

    private ContentAreaEditor() {
    }

    /**
     * @param nearWindow passed straight through to {@link ViewFrame#open} —
     * not an AWT owner, just which screen to maximize onto
     * @param catalog where Commit writes to
     * @param entry the entry being traced; its {@link CatalogEntry#width}/
     * {@link CatalogEntry#height} are not touched, only {@code contentArea}
     * @param image {@code entry}'s already-decoded full-resolution image
     * (the caller already has it loaded for display; no reason to decode it
     * a second time here)
     * @param onCommitted called right after a successful Commit writes
     * {@code entry.contentArea} to the catalog — lets the caller (which
     * handed us its own live reference to {@code entry}, not a copy) refresh
     * anything it derived from the old value, e.g. a cached mask overlay.
     * May be {@code null}.
     */
    static void open(Window nearWindow, Catalog catalog, CatalogEntry entry, BufferedImage image,
            Runnable onCommitted) {
        ContentAreaCanvas canvas = new ContentAreaCanvas(image, entry.contentArea);

        JLabel status = new JLabel(
                "Click to trace the content-area boundary (right-click undoes the last point);"
                + " click near the first point to close it.");
        JButton clear = new JButton("Clear");
        JButton commit = new JButton("Commit");
        JButton cancel = new JButton("Cancel");
        commit.setEnabled(!entry.contentArea.isEmpty());

        canvas.setStateListener(commit::setEnabled);
        clear.addActionListener(e -> canvas.clear());
        cancel.addActionListener(e -> SwingUtilities.getWindowAncestor(canvas).dispose());
        commit.addActionListener(e -> {
            entry.contentArea = canvas.resultVertices();
            try {
                catalog.save(entry, catalog.loadThumbnail(entry.filename));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(canvas, "Save failed:\n" + ex.getMessage(),
                        "Save failed", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (null != onCommitted) {
                onCommitted.run();
            }
            SwingUtilities.getWindowAncestor(canvas).dispose();
        });

        JPanel buttons = new JPanel();
        buttons.add(clear);
        buttons.add(commit);
        buttons.add(cancel);
        JPanel south = new JPanel(new BorderLayout());
        south.add(status, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(canvas, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        ViewFrame.open("Content Area", nearWindow, panel, true, true);
    }
}
