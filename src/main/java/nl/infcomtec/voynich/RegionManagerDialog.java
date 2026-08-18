/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.TreeSet;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

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

        dialog = new JDialog(owner, "Regions — " + OverviewPanel.displayNameOf(entry), JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());

        rowsPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        dialog.add(new JScrollPane(rowsPanel), BorderLayout.CENTER);

        JButton addButton = new JButton(new EzAction("Add Region") {
            @Override
            public void actionPerformed(ActionEvent e) {
                addRegion(-1);
            }
        }.withTooltip("Trace a brand new top-level region — kind and author are asked for first"));
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

        // pack() rather than a guessed constant: the row width depends on
        // the longest kind label plus however many action buttons a row
        // has, both of which grow over time (more kinds get typed, "(child
        // of #N)" adds length) — a fixed size clips them again sooner or
        // later.
        dialog.pack();
        java.awt.Dimension packed = dialog.getSize();
        dialog.setSize(Math.max(600, packed.width), Math.max(420, packed.height));
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
            String kindLabel = region.kind.isEmpty() ? "(no kind)" : region.kind;
            if (region.parentIndex >= 0) {
                kindLabel += " (child of #" + region.parentIndex + ")";
            }
            rowsPanel.add(new JLabel(kindLabel), c);
            c.gridx = 2;
            rowsPanel.add(new JLabel(region.author.isEmpty() ? "(unspecified)" : region.author), c);

            JPanel actions = new JPanel();
            JButton view = new JButton(new EzAction("View") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    RegionViewer.open(dialog, catalog, entry, image, index, region, new Runnable() {
                        @Override
                        public void run() {
                            changed();
                        }
                    });
                }
            }.withTooltip("Open this region as its own rotatable main view — mouse wheel"
                    + " rotates it, with its own Add Child button"));
            JButton trace = new JButton(new EzAction("Trace") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    traceRegion(region);
                }
            }.withTooltip("Re-trace this region's polygon"));
            JButton addChild = new JButton(new EzAction("Add Child") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    addRegion(index);
                }
            }.withTooltip("Trace a new region nested inside this one, zoomed to its bounding box —"
                    + " for a small figure inside a larger traced diagram"));
            addChild.setEnabled(region.polygon.size() >= 3);
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
            actions.add(addChild);
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
     *
     * @param parentIndex the new region's {@link CatalogEntry.Region#parentIndex};
     * {@code -1} for top-level (the toolbar's "Add Region"), or a row's own
     * index when called from that row's "Add Child" — in the latter case
     * tracing is zoomed to the parent's bounding box (see
     * {@link #boundingBox}) instead of the whole page, since a child is
     * typically a small figure nested inside a larger already-traced
     * diagram.
     */
    private void addRegion(int parentIndex) {
        KindAuthorPrompt.Result result = KindAuthorPrompt.prompt(dialog,
                parentIndex < 0 ? "New region" : "New child region", distinctKinds(catalog), "", "");
        if (null == result) {
            return;
        }
        Rectangle viewport = parentIndex < 0 ? null : boundingBox(entry.regions.get(parentIndex));
        ContentAreaEditor.open(dialog, catalog, entry, image, null, result.kind, result.author, parentIndex,
                viewport, new Runnable() {
            @Override
            public void run() {
                changed();
            }
        });
    }

    /**
     * @return {@code region}'s polygon bounding box in image pixel
     * coordinates, clamped to {@link #image}'s bounds — used as the zoom
     * viewport for tracing a child region (see {@link #addRegion}/
     * {@link #traceRegion}), and only ever called with a region that
     * already has a polygon (a top-level Add has no parent, so no bounding
     * box is needed there)
     */
    private Rectangle boundingBox(CatalogEntry.Region region) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (CatalogEntry.Vertex v : region.polygon) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
        }
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(image.getWidth(), maxX);
        maxY = Math.min(image.getHeight(), maxY);
        return new Rectangle(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    private void traceRegion(CatalogEntry.Region region) {
        Rectangle viewport = region.parentIndex < 0 ? null : boundingBox(entry.regions.get(region.parentIndex));
        ContentAreaEditor.open(dialog, catalog, entry, image, region, null, null, region.parentIndex, viewport,
                new Runnable() {
            @Override
            public void run() {
                changed();
            }
        });
    }

    private void renameRegion(CatalogEntry.Region region) {
        KindAuthorPrompt.Result result = KindAuthorPrompt.prompt(dialog, "Rename region", distinctKinds(catalog),
                region.kind, region.author);
        if (null == result) {
            return;
        }
        region.kind = result.kind;
        region.author = result.author;
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
            catalog.save(entry, catalog.loadThumbnail(entry.id));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Save failed:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * @return every distinct {@link CatalogEntry.Region#kind} used anywhere
     * in the catalog (excluding the synthetic {@code "page"} kind, which is
     * never user-picked), sorted, for {@link KindAuthorPrompt}'s combo —
     * shared with {@link RegionViewer}'s own "Add Child" prompt, since a
     * kind typed once from either place should show up for the other, not
     * two independently-drifting lists. On a catalog read failure, falls
     * back to just {@code "content"} rather than blocking the dialog — the
     * combo stays editable either way.
     */
    static String[] distinctKinds(Catalog catalog) {
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
