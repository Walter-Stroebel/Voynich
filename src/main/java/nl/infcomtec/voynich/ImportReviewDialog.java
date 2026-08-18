/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

/**
 * Non-modal, per-region review queue for an Import — same "this app
 * doesn't decide how many tool windows a user has open" reasoning as
 * {@link CatalogEntryEditor}'s own modelessness. Every incoming region
 * from every resolvable {@link CatalogExporter.Exported} record is
 * flattened into one queue (skipping each record's index 0, the synthetic
 * whole page, never a review target — same convention {@link
 * RegionManagerDialog} already follows); the reviewer sees it drawn as an
 * outline over the real page image (via {@link BitSet2D#drawOutline}, not
 * a raw JSON/vertex diff, which is unusable at hand-traced-polygon
 * granularity) alongside its stats, and picks Add or Ignore. Nothing is
 * written until Add is clicked — see {@link Catalog#addRegion} for the
 * write itself, including its own-kind-name-collision handling. Once
 * every region in the queue has been reviewed, every imported record's
 * {@link CatalogExporter.Exported#tags} — the full original list passed
 * to {@link #open}, not just the ones that had regions — are offered as a
 * plain checklist (no vertex-noise problem with tags, so no overlay
 * treatment needed there).
 */
final class ImportReviewDialog {

    private final JDialog dialog;
    private final Catalog catalog;
    private final OverviewPanel overview;
    private final List<CatalogExporter.Exported> allRecords;
    private final List<QueueItem> queue;
    private int index;

    private final JLabel imageLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel statsLabel = new JLabel(" ");
    private final JButton addButton = new JButton();
    private final JButton ignoreButton = new JButton();

    /**
     * One region still to review, tagged with which entry it belongs to.
     */
    private static final class QueueItem {

        final CatalogEntry entry;
        final CatalogEntry.Region region;

        QueueItem(CatalogEntry entry, CatalogEntry.Region region) {
            this.entry = entry;
            this.region = region;
        }
    }

    /**
     * Builds the review queue from every resolvable imported record and
     * opens the dialog — the entry point {@code Voynich.importEntries}
     * calls after loading/classifying the file and taking a safety
     * checkpoint.
     *
     * @param owner parent window for the non-modal dialog
     * @param catalog to load full entries/images from and write Add
     * actions to
     * @param overview the live thumbnail grid — refreshed via {@link
     * OverviewPanel#addOrUpdate(CatalogEntry)} after every successful
     * write, since {@link Catalog#addRegion}/{@link Catalog#addTag} write
     * straight to storage with no way to reach into this already-open
     * grid's separate in-memory model on their own (same reasoning as
     * {@code RenameTaskWindow}'s own {@code overview.renameEntry} call)
     * @param resolvable every imported record whose id resolved to a real
     * local entry (see {@link CatalogImporter.Classified#resolvable})
     */
    static void open(Window owner, Catalog catalog, OverviewPanel overview, List<CatalogExporter.Exported> resolvable) {
        List<QueueItem> queue = new ArrayList<>();
        try {
            for (CatalogExporter.Exported record : resolvable) {
                CatalogEntry entry = catalog.loadEntry(record.id);
                if (null == entry) {
                    continue;
                }
                for (int i = 1; i < record.regions.size(); i++) {
                    queue.add(new QueueItem(entry, record.regions.get(i)));
                }
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(owner, "Could not read catalog:\n" + ex.getMessage(),
                    "Import failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (queue.isEmpty()) {
            reviewTags(owner, catalog, overview, resolvable);
            return;
        }
        new ImportReviewDialog(owner, catalog, overview, resolvable, queue).advance();
    }

    private ImportReviewDialog(Window owner, Catalog catalog, OverviewPanel overview,
            List<CatalogExporter.Exported> allRecords, List<QueueItem> queue) {
        this.catalog = catalog;
        this.overview = overview;
        this.allRecords = allRecords;
        this.queue = queue;

        dialog = new JDialog(owner, "Import review", JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());

        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        dialog.add(new JScrollPane(imageLabel), BorderLayout.CENTER);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        side.add(statusLabel);
        side.add(Box.createVerticalStrut(8));
        side.add(statsLabel);
        side.add(Box.createVerticalStrut(16));

        addButton.setAction(new EzAction("Add") {
            @Override
            public void actionPerformed(ActionEvent e) {
                doAdd();
            }
        }.withTooltip("Append this as a brand new region — never touches any existing region"));
        ignoreButton.setAction(new EzAction("Ignore") {
            @Override
            public void actionPerformed(ActionEvent e) {
                advance();
            }
        }.withTooltip("Skip this region, nothing written"));
        side.add(addButton);
        side.add(Box.createVerticalStrut(4));
        side.add(ignoreButton);
        dialog.add(side, BorderLayout.EAST);

        dialog.setSize(1100, 900);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private void doAdd() {
        // advance() increments index right after reading the item it just
        // displayed, so index - 1 is always the currently-shown item here.
        QueueItem item = queue.get(index - 1);
        CatalogEntry updated;
        try {
            updated = catalog.addRegion(item.entry.id, item.region);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Could not add region:\n" + ex.getMessage(),
                    "Add failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        overview.addOrUpdate(updated);
        advance();
    }

    /**
     * Moves to the next queued region, or — once the queue is exhausted —
     * offers every imported record's tags as a checklist, then closes.
     * Loads the page image off the EDT, same freeze-avoidance reasoning as
     * {@code CatalogEntryEditor.loadFullImage}.
     */
    private void advance() {
        if (index >= queue.size()) {
            Window owner = dialog.getOwner();
            dialog.dispose();
            reviewTags(owner, catalog, overview, allRecords);
            return;
        }
        QueueItem item = queue.get(index);
        addButton.setEnabled(false);
        statusLabel.setText((index + 1) + " / " + queue.size() + " — "
                + OverviewPanel.displayNameOf(item.entry));
        statsLabel.setText(statsText(item.region));
        imageLabel.setText("Loading…");
        imageLabel.setIcon(null);
        index++;

        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                BufferedImage full = ImageDisplay.loadFull(item.entry);
                if (null == full) {
                    return null;
                }
                BufferedImage scaled = ImageDisplay.scaleToFit(full, 1000, 800);
                double scaleX = (double) scaled.getWidth() / full.getWidth();
                double scaleY = (double) scaled.getHeight() / full.getHeight();
                List<Point> scaledPolygon = new ArrayList<>();
                for (CatalogEntry.Vertex v : item.region.polygon) {
                    scaledPolygon.add(new Point((int) Math.round(v.x * scaleX), (int) Math.round(v.y * scaleY)));
                }
                return BitSet2D.drawOutline(scaled, scaledPolygon, Color.MAGENTA, 3f);
            }

            @Override
            protected void done() {
                BufferedImage result;
                try {
                    result = get();
                } catch (Exception ex) {
                    result = null;
                }
                if (null == result) {
                    imageLabel.setText("No readable file for " + OverviewPanel.displayNameOf(item.entry));
                } else {
                    imageLabel.setText(null);
                    imageLabel.setIcon(new ImageIcon(result));
                }
                addButton.setEnabled(true);
            }
        }.execute();
    }

    private static String statsText(CatalogEntry.Region region) {
        String author = (null == region.author || region.author.isEmpty()) ? "(unspecified)" : region.author;
        return "<html>kind: " + region.kind + "<br>author: " + author
                + "<br>vertices: " + region.polygon.size()
                + "<br>area: " + Math.round(region.shoelaceArea()) + " px&sup2;</html>";
    }

    /**
     * Offers every record's incoming tags (not already present locally) as
     * a plain checklist, one small confirm dialog per record with at least
     * one candidate tag — no vertex-overlay treatment needed since tags
     * are already plain, diffable text. Each checked tag is added via
     * {@link Catalog#addTag} directly (a no-op if already present, per its
     * existing contract).
     */
    private static void reviewTags(Window owner, Catalog catalog, OverviewPanel overview,
            List<CatalogExporter.Exported> records) {
        for (CatalogExporter.Exported record : records) {
            CatalogEntry entry;
            try {
                entry = catalog.loadEntry(record.id);
            } catch (IOException ex) {
                continue;
            }
            if (null == entry) {
                continue;
            }
            List<String> candidates = new ArrayList<>();
            for (String tag : record.tags) {
                if (!entry.tags.contains(tag)) {
                    candidates.add(tag);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.add(new JLabel("New tags for " + OverviewPanel.displayNameOf(entry) + ":"));
            List<JCheckBox> boxes = new ArrayList<>();
            for (String tag : candidates) {
                JCheckBox box = new JCheckBox(tag, true);
                boxes.add(box);
                panel.add(box);
            }
            int choice = JOptionPane.showConfirmDialog(owner, panel, "Import tags",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                continue;
            }
            boolean anyAdded = false;
            for (JCheckBox box : boxes) {
                if (box.isSelected()) {
                    try {
                        catalog.addTag(entry.id, box.getText());
                        anyAdded = true;
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(owner, "Could not add tag:\n" + ex.getMessage(),
                                "Add tag failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
            if (anyAdded) {
                try {
                    overview.addOrUpdate(catalog.loadEntry(entry.id));
                } catch (IOException ignored) {
                    // Best-effort refresh only; the write itself already
                    // succeeded and is safely on disk either way.
                }
            }
        }
    }
}
