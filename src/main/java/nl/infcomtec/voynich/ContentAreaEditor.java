/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Opens a {@link ContentAreaCanvas} in its own {@link ViewFrame}-hosted
 * window, spawned by a button in {@link CatalogEntryEditor} the same way as
 * "Color Frequency"/"ΔE Heatmap" — except this one is interactive rather
 * than a passive visualization, so it carries its own Clear/Commit/Cancel
 * controls rather than being pure display.
 * <p>
 * A small chooser ({@link #pickRegion}) runs first: pick an existing
 * {@link CatalogEntry#regions} entry (excluding the synthetic whole-page
 * region at index 0) to re-trace, or start a new one with a {@code kind}
 * (an editable combo pre-filled from every distinct kind already used
 * across the catalog — so "Arabic page number" gets typed once, ever) and
 * an {@code author} (blank means genuinely unattributed, never defaulted to
 * whoever's running the app).
 * <p>
 * {@link CatalogEntry.Region#polygon} is deliberately never auto-detected —
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
     * @param catalog where Commit writes to, and where the {@code kind}
     * combo's suggestions come from
     * @param entry the entry being traced; its {@link CatalogEntry#width}/
     * {@link CatalogEntry#height} are not touched, only {@code regions}
     * @param image {@code entry}'s already-decoded full-resolution image
     * (the caller already has it loaded for display; no reason to decode it
     * a second time here)
     * @param onCommitted called right after a successful Commit writes
     * {@code entry.regions} to the catalog — lets the caller (which handed
     * us its own live reference to {@code entry}, not a copy) refresh
     * anything it derived from the old value, e.g. a cached mask overlay.
     * May be {@code null}.
     */
    static void open(Window nearWindow, Catalog catalog, CatalogEntry entry, BufferedImage image,
            Runnable onCommitted) {
        PickedRegion picked = pickRegion(nearWindow, catalog, entry);
        if (null == picked) {
            return;
        }
        List<CatalogEntry.Vertex> initial = null != picked.existing ? picked.existing.polygon : List.of();
        ContentAreaCanvas canvas = new ContentAreaCanvas(image, initial);

        JLabel status = new JLabel(
                "Click to trace \"" + picked.kind + "\" (right-click undoes the last point);"
                + " click near the first point to close it.");
        JButton clear = new JButton("Clear");
        JButton commit = new JButton("Commit");
        JButton cancel = new JButton("Cancel");
        commit.setEnabled(!initial.isEmpty());

        canvas.setStateListener(commit::setEnabled);
        clear.addActionListener(e -> canvas.clear());
        cancel.addActionListener(e -> SwingUtilities.getWindowAncestor(canvas).dispose());
        commit.addActionListener(e -> {
            CatalogEntry.Region region = null != picked.existing ? picked.existing : new CatalogEntry.Region();
            region.kind = picked.kind;
            region.author = picked.author;
            region.polygon = canvas.resultVertices();
            if (null == picked.existing) {
                entry.ensureWholePageRegion();
                entry.regions.add(region);
            }
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

    /**
     * One already-traced region (any index &gt;= 1 of {@code entry.regions})
     * plus its own index, for the "re-trace" combo — kept together since the
     * combo displays a label built from both but {@link #open} needs the
     * live {@link CatalogEntry.Region} object back to mutate in place.
     */
    private static final class PickedRegion {

        CatalogEntry.Region existing;
        String kind;
        String author;
    }

    /**
     * Prompts for which region to (re-)trace: an existing one (from
     * {@code entry.regions}, index 1 on — index 0 is always the synthetic
     * whole page, never user-editable) or a new one, with an editable
     * {@code kind} combo pre-filled from every distinct kind already used
     * anywhere in the catalog, and a free-text {@code author} field
     * defaulting to blank.
     *
     * @return the chosen region plus its kind/author, or {@code null} if
     * the user cancelled
     */
    private static PickedRegion pickRegion(Window nearWindow, Catalog catalog, CatalogEntry entry) {
        List<CatalogEntry.Region> existingRegions = new ArrayList<>();
        List<String> existingLabels = new ArrayList<>();
        existingLabels.add("New region");
        for (int i = 1; i < entry.regions.size(); i++) {
            CatalogEntry.Region r = entry.regions.get(i);
            existingRegions.add(r);
            String label = "#" + i + ": " + (r.kind.isEmpty() ? "(no kind)" : r.kind)
                    + (r.author.isEmpty() ? "" : " (" + r.author + ")");
            existingLabels.add(label);
        }

        JComboBox<String> existingBox = new JComboBox<>(existingLabels.toArray(new String[0]));
        JComboBox<String> kindBox = new JComboBox<>(distinctKinds(catalog));
        kindBox.setEditable(true);
        JTextField authorField = new JTextField();

        existingBox.addActionListener(e -> {
            int idx = existingBox.getSelectedIndex();
            if (idx <= 0) {
                kindBox.setSelectedItem("");
                authorField.setText("");
            } else {
                CatalogEntry.Region r = existingRegions.get(idx - 1);
                kindBox.setSelectedItem(r.kind);
                authorField.setText(r.author);
            }
        });

        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        panel.add(new JLabel("Region:"));
        panel.add(existingBox);
        panel.add(new JLabel("Kind:"));
        panel.add(kindBox);
        panel.add(new JLabel("Author (blank = unspecified):"));
        panel.add(authorField);

        int choice = JOptionPane.showConfirmDialog(nearWindow, panel, "Pick region",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }

        PickedRegion picked = new PickedRegion();
        int idx = existingBox.getSelectedIndex();
        picked.existing = idx <= 0 ? null : existingRegions.get(idx - 1);
        Object kindItem = kindBox.getSelectedItem();
        picked.kind = null == kindItem ? "" : kindItem.toString().trim();
        picked.author = authorField.getText().trim();
        return picked;
    }

    /**
     * @return every distinct {@link CatalogEntry.Region#kind} used anywhere
     * in the catalog (excluding the synthetic {@code "page"} kind, which is
     * never user-picked), sorted, for {@link #pickRegion}'s editable combo.
     * On a catalog read failure, falls back to an empty list rather than
     * blocking the dialog — the combo stays editable either way.
     */
    private static String[] distinctKinds(Catalog catalog) {
        TreeSet<String> kinds = new TreeSet<>();
        try {
            for (CatalogEntry candidate : catalog.listAll()) {
                for (int i = 1; i < candidate.regions.size(); i++) {
                    String kind = candidate.regions.get(i).kind;
                    if (null != kind && !kind.isEmpty()) {
                        kinds.add(kind);
                    }
                }
            }
        } catch (IOException ex) {
            // Fall back to an empty suggestion list; the combo is still editable.
        }
        kinds.add("content");
        return kinds.toArray(new String[0]);
    }
}
