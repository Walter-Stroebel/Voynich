/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

/**
 * Lists every one of {@code entry}'s {@link CatalogEntry#regions} (excluding
 * the synthetic whole-page region at index 0, never user-editable) with
 * Trace/Rename/Up/Down/Delete actions per row, plus an Add button — the
 * management UI {@link ContentAreaEditor} alone couldn't provide, since a
 * one-shot polygon-tracing window has nowhere to show what's already been
 * traced. Opened by {@link CatalogEntryEditor}'s "Regions…" button. Modeless,
 * matching {@code Voynich}'s "windows don't own windows" convention; list
 * layout mirrors {@link StorageDialog} (plain {@code GridBagLayout}, no
 * {@code JTable}, since row count is always small).
 * <p>
 * Every action here (Trace's Commit, Add, Rename, Up, Down, Delete) saves to
 * the catalog immediately — there's no batched "Done" button and so no
 * unsaved-state to track on top of {@link CatalogEntryEditor}'s own JSON-blob
 * staleness guard for this same field.
 * <p>
 * {@link CatalogEntry#regions}' index convention is deliberately left alone
 * here, not hidden behind a flag: {@code regions.get(1)}, whatever occupies
 * it, is always <em>the</em> main content area
 * ({@link CatalogEntry#mainRegion()}). That makes two of these actions worth
 * calling out:
 * <ul>
 * <li><b>Add</b> always appends at the end — it can never change what's
 * main.</li>
 * <li><b>Up</b>/<b>Down</b> swap adjacent rows one step at a time (never
 * drag-and-drop) — so promoting a region to main is always the direct,
 * visible result of clicking "Up" enough times, never an accidental side
 * effect of deleting something else.</li>
 * <li><b>Delete</b> on the main row is allowed, but gets its own warning
 * wording: the next remaining region (if any) becomes the new main as a
 * direct, named consequence, not a silent side effect discovered later.</li>
 * </ul>
 */
final class RegionManagerDialog {

    private final JDialog dialog;
    private final Catalog catalog;
    private final CatalogEntry entry;
    private final BufferedImage image;
    private final Runnable onChanged;
    private final JPanel rowsPanel = new JPanel(new GridBagLayout());

    private RegionManagerDialog(Window owner, Catalog catalog, CatalogEntry entry, BufferedImage image,
            Runnable onChanged) {
        this.catalog = catalog;
        this.entry = entry;
        this.image = image;
        this.onChanged = onChanged;

        dialog = new JDialog(owner, "Regions — " + entry.filename, JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());

        rowsPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        dialog.add(new JScrollPane(rowsPanel), BorderLayout.CENTER);

        JButton addButton = new JButton(new EzAction("Add Region") {
            @Override
            public void actionPerformed(ActionEvent e) {
                addRegion();
            }
        }.withTooltip("Trace a brand new region — kind and author are asked for first"));
        JButton closeButton = new JButton(new EzAction("Close") {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        }.withTooltip("Close this window (regions are already saved, nothing pending)"));
        JPanel buttons = new JPanel();
        buttons.add(addButton);
        buttons.add(closeButton);
        dialog.add(buttons, BorderLayout.SOUTH);

        refresh();

        dialog.setSize(560, 420);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Opens the region manager for {@code entry}, owned by {@code owner}.
     *
     * @param owner window the dialog is sized/centered against
     * @param catalog where every action writes to
     * @param entry the entry whose regions are being managed
     * @param image {@code entry}'s already-decoded full-resolution image,
     * passed straight through to {@link ContentAreaEditor}
     * @param onChanged called after any action that changes {@code
     * entry.regions} — lets the caller (which handed us its own live
     * reference to {@code entry}, not a copy) refresh anything it derived
     * from the old value, e.g. a cached mask overlay
     */
    static void open(Window owner, Catalog catalog, CatalogEntry entry, BufferedImage image, Runnable onChanged) {
        new RegionManagerDialog(owner, catalog, entry, image, onChanged);
    }

    private void refresh() {
        rowsPanel.removeAll();

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new java.awt.Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridy = 0;
        addHeaderCell("#", 0, c);
        addHeaderCell("Kind", 1, c);
        addHeaderCell("Author", 2, c);

        int row = 1;
        for (int i = 1; i < entry.regions.size(); i++) {
            CatalogEntry.Region region = entry.regions.get(i);
            int index = i;

            c.gridy = row;
            c.gridx = 0;
            rowsPanel.add(new JLabel(String.valueOf(i)), c);
            c.gridx = 1;
            rowsPanel.add(new JLabel(region.kind.isEmpty() ? "(no kind)" : region.kind), c);
            c.gridx = 2;
            rowsPanel.add(new JLabel(region.author.isEmpty() ? "(unspecified)" : region.author), c);

            JPanel actions = new JPanel();
            JButton view = new JButton(new EzAction("View") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    viewRegion(region);
                }
            }.withTooltip("Show just this region, cropped and scaled to fit the screen"));
            JButton trace = new JButton(new EzAction("Trace") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    traceRegion(region);
                }
            }.withTooltip("Re-trace this region's polygon"));
            JButton rename = new JButton(new EzAction("Rename") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    renameRegion(region);
                }
            }.withTooltip("Change this region's kind/author without re-tracing its polygon"));
            JButton up = new JButton(new EzAction("Up") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    moveRegion(index, -1);
                }
            }.withTooltip("Swap with the region above — row #1 is always the main content area"));
            JButton down = new JButton(new EzAction("Down") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    moveRegion(index, 1);
                }
            }.withTooltip("Swap with the region below — row #1 is always the main content area"));
            JButton delete = new JButton(new EzAction("Delete") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    deleteRegion(index);
                }
            }.withTooltip("Permanently remove this region"));
            view.setEnabled(region.polygon.size() >= 3);
            up.setEnabled(index > 1);
            down.setEnabled(index < entry.regions.size() - 1);
            actions.add(view);
            actions.add(trace);
            actions.add(rename);
            actions.add(up);
            actions.add(down);
            actions.add(delete);
            c.gridx = 3;
            rowsPanel.add(actions, c);
            row++;
        }
        if (row == 1) {
            c.gridy = row;
            c.gridx = 0;
            c.gridwidth = 4;
            rowsPanel.add(new JLabel("No regions traced yet."), c);
            c.gridwidth = 1;
            row++;
        }

        // Absorbs leftover vertical space so rows stay top-aligned instead
        // of spreading out across the scroll pane.
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row;
        filler.weighty = 1;
        rowsPanel.add(new JLabel(), filler);

        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    private void addHeaderCell(String text, int column, GridBagConstraints template) {
        JLabel header = new JLabel(text);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        GridBagConstraints c = (GridBagConstraints) template.clone();
        c.gridx = column;
        rowsPanel.add(header, c);
    }

    /**
     * Prompts for a {@code kind} (editable combo, pre-filled from every
     * distinct kind already used across the catalog) and {@code author},
     * then opens {@link ContentAreaEditor} for a brand new region — nothing
     * is appended to {@link CatalogEntry#regions} unless that window's
     * Commit actually runs.
     */
    private void addRegion() {
        JComboBox<String> kindBox = new JComboBox<>(distinctKinds());
        kindBox.setEditable(true);
        JTextField authorField = new JTextField();
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        panel.add(new JLabel("Kind:"));
        panel.add(kindBox);
        panel.add(new JLabel("Author (blank = unspecified):"));
        panel.add(authorField);
        int choice = JOptionPane.showConfirmDialog(dialog, panel, "New region",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        Object kindItem = kindBox.getSelectedItem();
        String kind = null == kindItem ? "" : kindItem.toString().trim();
        String author = authorField.getText().trim();
        ContentAreaEditor.open(dialog, catalog, entry, image, null, kind, author, new Runnable() {
            @Override
            public void run() {
                changed();
            }
        });
    }

    /**
     * Crops {@link #image} to {@code region}'s bounding box (blacking out
     * everything inside that box but outside the polygon, via
     * {@link BitSet2D#cropToPolygon}), auto-scales the result to fit the
     * current screen — up for a small trace like a faint imprint mark, down
     * for one close to full-page — and shows it in a plain, non-interactive
     * {@link ViewFrame} window. Without this, a small region traced via the
     * "Show Mask" toggle's full-page view is easy to miss entirely: this is
     * the actual "look at what I traced" affordance.
     */
    private void viewRegion(CatalogEntry.Region region) {
        List<Point> vertices = new ArrayList<>(region.polygon.size());
        for (CatalogEntry.Vertex v : region.polygon) {
            vertices.add(new Point(v.x, v.y));
        }
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                BufferedImage cropped = BitSet2D.cropToPolygon(image, vertices);
                GraphicsDevice device = null != dialog.getGraphicsConfiguration()
                        ? dialog.getGraphicsConfiguration().getDevice()
                        : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                Rectangle screen = device.getDefaultConfiguration().getBounds();
                return ImageDisplay.scaleToFit(cropped, screen.width * 2 / 3, screen.height * 2 / 3);
            }

            @Override
            protected void done() {
                try {
                    JLabel label = new JLabel(new ImageIcon(get()));
                    ViewFrame.open("Region View", dialog, new JScrollPane(label), true, false);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Could not build region view:\n" + ex.getMessage(),
                            "View failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void traceRegion(CatalogEntry.Region region) {
        ContentAreaEditor.open(dialog, catalog, entry, image, region, null, null, new Runnable() {
            @Override
            public void run() {
                changed();
            }
        });
    }

    private void renameRegion(CatalogEntry.Region region) {
        JComboBox<String> kindBox = new JComboBox<>(distinctKinds());
        kindBox.setEditable(true);
        kindBox.setSelectedItem(region.kind);
        JTextField authorField = new JTextField(region.author);
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        panel.add(new JLabel("Kind:"));
        panel.add(kindBox);
        panel.add(new JLabel("Author (blank = unspecified):"));
        panel.add(authorField);
        int choice = JOptionPane.showConfirmDialog(dialog, panel, "Rename region",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        Object kindItem = kindBox.getSelectedItem();
        region.kind = null == kindItem ? "" : kindItem.toString().trim();
        region.author = authorField.getText().trim();
        save();
        changed();
    }

    private void moveRegion(int index, int delta) {
        int other = index + delta;
        if (other < 1 || other >= entry.regions.size()) {
            return;
        }
        Collections.swap(entry.regions, index, other);
        save();
        changed();
    }

    private void deleteRegion(int index) {
        CatalogEntry.Region region = entry.regions.get(index);
        String warning = 1 == index
                ? "This is the main content area. Deleting it makes region #2"
                + " (if any) the new main content area. Delete \"" + region.kind + "\"?"
                : "Permanently delete \"" + region.kind + "\"?";
        int choice = JOptionPane.showConfirmDialog(dialog, warning, "Delete region",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        entry.regions.remove(index);
        save();
        changed();
    }

    private void changed() {
        refresh();
        if (null != onChanged) {
            onChanged.run();
        }
    }

    private void save() {
        try {
            catalog.save(entry, catalog.loadThumbnail(entry.filename));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Save failed:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * @return every distinct {@link CatalogEntry.Region#kind} used anywhere
     * in the catalog (excluding the synthetic {@code "page"} kind, which is
     * never user-picked), sorted, for the Add/Rename kind combos. On a
     * catalog read failure, falls back to just {@code "content"} rather
     * than blocking the dialog — the combo stays editable either way.
     */
    private String[] distinctKinds() {
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
            // Fall back to just "content"; the combo is still editable.
        }
        kinds.add("content");
        return kinds.toArray(new String[0]);
    }
}
