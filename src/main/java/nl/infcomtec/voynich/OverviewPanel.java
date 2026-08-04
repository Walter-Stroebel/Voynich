/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * The app's main view: a scrollable grid of catalog thumbnails, one per
 * {@link CatalogEntry}. Starts empty on a fresh catalog — populated by
 * {@link #loadFromCatalog()} at startup with whatever's already cataloged,
 * and by {@link #addOrUpdate(CatalogEntry)} as a scan discovers or updates
 * entries live.
 */
public class OverviewPanel extends JPanel {

    private final Catalog catalog;
    private final DefaultListModel<CatalogEntry> model = new DefaultListModel<>();
    private final JList<CatalogEntry> list = new JList<>(model);
    private final Map<String, Icon> thumbnails = new HashMap<>();

    public OverviewPanel(Catalog catalog) {
        super(new BorderLayout());
        this.catalog = catalog;
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(-1);
        list.setCellRenderer(new EntryRenderer());
        // Every thumbnail is exactly THUMB_SIZE square, so a fixed cell size
        // is both correct and required: without it JList falls onto its
        // variable-cell-size layout path for HORIZONTAL_WRAP, which pads
        // every column out to the single widest cell in the whole model
        // (Swing's documented "slow and often surprising" mode) instead of
        // packing columns tightly against the viewport width.
        list.setFixedCellWidth(ColorImage.THUMB_SIZE + 12);
        list.setFixedCellHeight(ColorImage.THUMB_SIZE + 12 + 20);
        JScrollPane scroll = new JScrollPane(list);
        // Reserve the vertical scrollbar's width up front. Otherwise the
        // column count is computed against the full viewport width before
        // the scrollbar exists; once enough rows accumulate to actually
        // need it, the viewport narrows and a whole column of thumbnails
        // that fit a moment ago no longer does.
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        add(scroll, BorderLayout.CENTER);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent evt) {
                int idx = list.locationToIndex(evt.getPoint());
                if (idx < 0 || !list.getCellBounds(idx, idx).contains(evt.getPoint())) {
                    return;
                }
                showJsonEditor(model.getElementAt(idx));
            }
        });
    }

    /**
     * Opens a modal, editable raw-JSON view of {@code entry} — the catalog's
     * per-file "notepad" ({@link CatalogEntry#tags}, {@link CatalogEntry#torrentJpg},
     * etc.) has no dedicated UI yet, and the whole entry is small enough that
     * hand-editing its JSON directly is simpler than building forms for each
     * field as they get added. This is the app's own database, owned by the
     * person running it, so the only validation on Save is against honest
     * mistakes, not hostile input: the JSON must parse (via {@link JSON}),
     * {@link CatalogEntry#filename} must be unchanged (it's the catalog key —
     * editing it here would silently create/orphan a row instead of renaming
     * anything), and {@link CatalogEntry#locations} must not have gone from
     * non-empty to empty (the easiest field to accidentally delete a line of
     * and lose sighting history for).
     * <p>
     * {@link CatalogEntry#tags} gets its own plain one-tag-per-line box
     * instead of living in the JSON blob — it's the field expected to be
     * hand-edited the most, and JSON array syntax (quoting, commas) is real
     * friction for what's just a short list of short strings. It's stripped
     * out of the blob entirely rather than shown in both places, so there's
     * never two open editors disagreeing about the same field.
     *
     * @param entry the entry to view/edit; must already be in the catalog
     */
    private void showJsonEditor(CatalogEntry entry) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, entry.filename, JDialog.ModalityType.APPLICATION_MODAL);

        ObjectNode blob = JSON.getMapper().valueToTree(entry);
        blob.remove("tags");
        String blobText;
        try {
            blobText = JSON.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(blob);
        } catch (JsonProcessingException ex) {
            // Can't actually happen: blob was just built from a real object, not parsed input.
            throw new IllegalStateException(ex);
        }
        JTextArea text = new JTextArea(blobText);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(600, 460));
        dialog.add(scroll, BorderLayout.CENTER);

        JTextArea tagsText = new JTextArea(String.join("\n", entry.tags));
        tagsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane tagsScroll = new JScrollPane(tagsText);
        tagsScroll.setBorder(BorderFactory.createTitledBorder("Tags (one per line)"));
        tagsScroll.setPreferredSize(new Dimension(600, 80));

        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(evt -> {
            CatalogEntry parsed;
            try {
                parsed = JSON.getMapper().readValue(text.getText(), CatalogEntry.class);
            } catch (JsonProcessingException ex) {
                JOptionPane.showMessageDialog(dialog, "Not valid JSON:\n" + ex.getMessage(),
                        "Invalid JSON", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (null == parsed.filename || !parsed.filename.equals(entry.filename)) {
                JOptionPane.showMessageDialog(dialog,
                        "filename must stay \"" + entry.filename + "\" — it's the catalog key.",
                        "Required field changed", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!entry.locations.isEmpty() && parsed.locations.isEmpty()) {
                JOptionPane.showMessageDialog(dialog,
                        "locations went from " + entry.locations.size() + " entries to 0 — refusing to save.",
                        "Required field changed", JOptionPane.ERROR_MESSAGE);
                return;
            }
            List<String> newTags = new ArrayList<>();
            for (String line : tagsText.getText().split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    newTags.add(trimmed);
                }
            }
            parsed.tags = newTags;
            try {
                catalog.save(parsed, catalog.loadThumbnail(entry.filename));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog, "Save failed:\n" + ex.getMessage(),
                        "Save failed", JOptionPane.ERROR_MESSAGE);
                return;
            }
            addOrUpdate(parsed);
            dialog.dispose();
        });
        cancel.addActionListener(evt -> dialog.dispose());
        JPanel buttons = new JPanel();
        buttons.add(save);
        buttons.add(cancel);

        JPanel south = new JPanel(new BorderLayout());
        south.add(tagsScroll, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Prompts (via a {@link JOptionPane} button row, one ascending/descending
     * button pair per field) for a sort field and direction, then re-sorts
     * the grid in place. One click picks both and closes the dialog. Wired
     * to the app toolbar's Sort button in {@link Voynich#main}; must be
     * called from the EDT.
     */
    public void sort() {
        SortKey[] keys = SortKey.values();
        String[] labels = new String[keys.length * 2];
        Comparator<CatalogEntry>[] comparators = new Comparator[keys.length * 2];
        for (int i = 0; i < keys.length; i++) {
            labels[i * 2] = keys[i] + " ↑";
            comparators[i * 2] = keys[i];
            labels[i * 2 + 1] = keys[i] + " ↓";
            comparators[i * 2 + 1] = keys[i].reversed();
        }
        int choice = JOptionPane.showOptionDialog(this, "Sort by:", "Sort",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, labels, labels[0]);
        if (choice == JOptionPane.CLOSED_OPTION) {
            return;
        }
        Comparator<CatalogEntry> comparator = comparators[choice];
        List<CatalogEntry> entries = new ArrayList<>(model.size());
        for (int i = 0; i < model.size(); i++) {
            entries.add(model.get(i));
        }
        Collections.sort(entries, comparator);
        model.clear();
        for (CatalogEntry entry : entries) {
            model.addElement(entry);
        }
    }

    /**
     * The fields {@link #sort()} offers, in menu order. Each constant is
     * itself the {@link Comparator} for that field.
     */
    private enum SortKey implements Comparator<CatalogEntry> {
        FILENAME("Filename") {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                return a.filename.compareToIgnoreCase(b.filename);
            }
        },
        WIDTH("Width") {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                return Integer.compare(a.width, b.width);
            }
        },
        HEIGHT("Height") {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                return Integer.compare(a.height, b.height);
            }
        },
        THUMBNAIL_COLORS("Color") {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                return Integer.compare(a.thumbnailUniqueColors, b.thumbnailUniqueColors);
            }
        };

        private final String label;

        SortKey(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Populates the grid from every entry already in the catalog. Performs
     * catalog I/O (a full listing plus one thumbnail read per entry) —
     * call from a background thread, or before the frame is shown, never
     * from the EDT once the UI is live.
     *
     * @throws IOException if the catalog listing fails
     */
    public void loadFromCatalog() throws IOException {
        for (CatalogEntry entry : catalog.listAll()) {
            addOrUpdate(entry);
        }
    }

    /**
     * Adds {@code entry} to the grid, or updates it in place if already
     * present (matched by {@link CatalogEntry#filename}). Reads the
     * thumbnail from the catalog — same I/O caveat as
     * {@link #loadFromCatalog()}. The actual grid mutation is marshaled to
     * the EDT, so this is safe to call from a scan's background thread.
     *
     * @param entry the entry to show
     */
    public void addOrUpdate(final CatalogEntry entry) {
        BufferedImage thumb;
        try {
            thumb = catalog.loadThumbnail(entry.filename);
        } catch (IOException ex) {
            thumb = null;
        }
        addOrUpdate(entry, thumb);
    }

    /**
     * Same as {@link #addOrUpdate(CatalogEntry)}, but for a caller that
     * already has the thumbnail in hand (e.g. a scan that just decoded it)
     * and would rather not pay for a redundant catalog read.
     *
     * @param entry the entry to show
     * @param thumbnail the thumbnail to display, or {@code null} for none
     */
    public void addOrUpdate(final CatalogEntry entry, BufferedImage thumbnail) {
        final Icon icon = null != thumbnail ? new ImageIcon(thumbnail) : null;
        Runnable apply = new Runnable() {
            @Override
            public void run() {
                thumbnails.put(entry.filename, icon);
                int idx = indexOf(entry.filename);
                if (idx >= 0) {
                    model.set(idx, entry);
                } else {
                    model.addElement(entry);
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            apply.run();
        } else {
            SwingUtilities.invokeLater(apply);
        }
    }

    private int indexOf(String filename) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).filename.equals(filename)) {
                return i;
            }
        }
        return -1;
    }

    private final class EntryRenderer extends JLabel implements ListCellRenderer<CatalogEntry> {

        EntryRenderer() {
            setOpaque(true);
            setHorizontalTextPosition(SwingConstants.CENTER);
            setVerticalTextPosition(SwingConstants.BOTTOM);
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CatalogEntry> list, CatalogEntry entry,
                int index, boolean isSelected, boolean cellHasFocus) {
            setText(entry.filename);
            setIcon(thumbnails.get(entry.filename));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
