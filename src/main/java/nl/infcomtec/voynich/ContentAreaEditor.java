/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Opens a {@link ContentAreaCanvas} in its own {@link ViewFrame}-hosted
 * window, spawned by {@link RegionManagerDialog}'s Add/Trace actions the
 * same way "Color Frequency"/"ΔE Heatmap" open from {@link CatalogEntryEditor}
 * — except this one is interactive rather than a passive visualization, so
 * it carries its own Clear/Commit/Cancel controls rather than being pure
 * display.
 * <p>
 * Purely a polygon editor: which {@link CatalogEntry.Region} it's tracing —
 * a brand new one (not yet in {@link CatalogEntry#regions}, {@code kind}/
 * {@code author} already decided) or an existing one being re-traced — is
 * entirely {@link RegionManagerDialog}'s call, made before this opens. A
 * fresh region is only appended to {@code entry.regions} on Commit, never
 * before — so a Cancel on a new trace leaves no half-formed region behind.
 * <p>
 * A polygon is deliberately never auto-detected — not because
 * ink-vs-blank-vellum is inherently undetectable (unlike the
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
     * {@link CatalogEntry#height} are not touched, only {@code regions}
     * @param image {@code entry}'s already-decoded full-resolution image
     * (the caller already has it loaded for display; no reason to decode it
     * a second time here)
     * @param existing the region to re-trace, pre-loading its current
     * polygon for review/adjustment; {@code null} to start a fresh trace
     * @param newKind {@code existing}'s {@link CatalogEntry.Region#kind} to
     * report in the status line if {@code existing} is non-null, otherwise
     * the kind a freshly-traced region will be created with — ignored
     * (existing's own value used instead) once {@code existing != null}
     * @param newAuthor same as {@code newKind} but for
     * {@link CatalogEntry.Region#author}
     * @param newParentIndex {@code existing}'s {@link CatalogEntry.Region#parentIndex}
     * to report if {@code existing} is non-null, otherwise the parent a
     * freshly-traced region will be created with; {@code -1} for top-level —
     * ignored (existing's own value used instead) once {@code existing != null}
     * @param viewport when non-null, restricts tracing to this sub-rectangle
     * of {@code image} (typically the parent region's bounding box) instead
     * of the whole page — see {@link ContentAreaCanvas}
     * @param onCommitted called right after a successful Commit writes
     * {@code entry.regions} to the catalog — lets the caller (which handed
     * us its own live reference to {@code entry}, not a copy) refresh
     * anything it derived from the old value, e.g. a cached mask overlay or
     * {@link RegionManagerDialog}'s row list.
     */
    static void open(Window nearWindow, Catalog catalog, CatalogEntry entry, BufferedImage image,
            CatalogEntry.Region existing, String newKind, String newAuthor, int newParentIndex,
            Rectangle viewport, Runnable onCommitted) {
        List<CatalogEntry.Vertex> initial = null != existing ? existing.polygon : List.of();
        String kindLabel = null != existing ? existing.kind : newKind;
        double initialAngle = null != existing ? existing.angle : 0.0;
        ContentAreaCanvas canvas = new ContentAreaCanvas(image, initial, viewport, initialAngle);

        JLabel status = new JLabel(
                "Click to trace \"" + kindLabel + "\" (right-click undoes the last point);"
                + " click near the first point to close it. Once closed, the mouse wheel"
                + " spins the preview at top-center — turn it to upright.");
        JButton clear = new JButton(new EzAction("Clear") {
            @Override
            public void actionPerformed(ActionEvent e) {
                canvas.clear();
            }
        }.withTooltip("Discard the current trace and start over"));
        JButton commit = new JButton(new EzAction("Commit") {
            @Override
            public void actionPerformed(ActionEvent e) {
                CatalogEntry.Region region = existing;
                if (null == region) {
                    region = new CatalogEntry.Region();
                    region.kind = newKind;
                    region.author = newAuthor;
                    region.parentIndex = newParentIndex;
                    entry.ensureWholePageRegion();
                    entry.regions.add(region);
                }
                region.polygon = canvas.resultVertices();
                region.angle = canvas.angleResult();
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
            }
        }.withTooltip("Save this polygon and close"));
        JButton cancel = new JButton(new EzAction("Cancel") {
            @Override
            public void actionPerformed(ActionEvent e) {
                SwingUtilities.getWindowAncestor(canvas).dispose();
            }
        }.withTooltip("Close without saving"));
        commit.setEnabled(!initial.isEmpty());

        canvas.setStateListener(new TraceStateListener() {
            @Override
            public void onTraceStateChanged(boolean closed) {
                commit.setEnabled(closed);
            }
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
