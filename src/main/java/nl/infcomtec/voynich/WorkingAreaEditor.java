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
 * Opens a {@link WorkingAreaCanvas} in its own {@link ViewFrame}-hosted
 * window, spawned by a button in {@link CatalogEntryEditor} the same way as
 * "Color Frequency"/"ΔE Heatmap" — except this one is interactive rather
 * than a passive visualization, so it carries its own Clear/Commit/Cancel
 * controls rather than being pure display.
 * <p>
 * {@link CatalogEntry#workingArea} is deliberately never auto-detected: the
 * boundary between "this page" and "the pages stacked underneath it" isn't
 * a colour/lightness distinction (same material), gradient/shadow-based
 * region growing breaks on hard folds (which are never true boundaries, no
 * matter how strong their shadow line looks), and building a robust
 * detector anyway would still leave a human fixing its outliers — at which
 * point the human may as well have just traced it, which takes them a few
 * seconds per page.
 * </p>
 */
final class WorkingAreaEditor {

    private WorkingAreaEditor() {
    }

    /**
     * @param nearWindow passed straight through to {@link ViewFrame#open} —
     * not an AWT owner, just which screen to maximize onto
     * @param catalog where Commit writes to
     * @param entry the entry being traced; its {@link CatalogEntry#width}/
     * {@link CatalogEntry#height} are not touched, only {@code workingArea}
     * @param image {@code entry}'s already-decoded full-resolution image
     * (the caller already has it loaded for display; no reason to decode it
     * a second time here)
     * @param onCommitted called right after a successful Commit writes
     * {@code entry.workingArea} to the catalog — lets the caller (which
     * handed us its own live reference to {@code entry}, not a copy) refresh
     * anything it derived from the old value, e.g. a cached mask overlay.
     * May be {@code null}.
     */
    static void open(Window nearWindow, Catalog catalog, CatalogEntry entry, BufferedImage image,
            Runnable onCommitted) {
        WorkingAreaCanvas canvas = new WorkingAreaCanvas(image, entry.workingArea);

        JLabel status = new JLabel(
                "Click to trace the working-area boundary (right-click undoes the last point);"
                + " click near the first point to close it.");
        JButton clear = new JButton("Clear");
        JButton commit = new JButton("Commit");
        JButton cancel = new JButton("Cancel");
        commit.setEnabled(!entry.workingArea.isEmpty());

        canvas.setStateListener(commit::setEnabled);
        clear.addActionListener(e -> canvas.clear());
        cancel.addActionListener(e -> SwingUtilities.getWindowAncestor(canvas).dispose());
        commit.addActionListener(e -> {
            entry.workingArea = canvas.resultVertices();
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

        ViewFrame.open("Working Area", nearWindow, panel, true, true);
    }
}
