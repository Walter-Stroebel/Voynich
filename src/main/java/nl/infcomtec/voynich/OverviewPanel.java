/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Component;
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
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
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
        add(new JScrollPane(list), BorderLayout.CENTER);
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
        COLOR_DENSITY("Colors / Megapixel") {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                return Double.compare(colorsPerMegapixel(a), colorsPerMegapixel(b));
            }
        };

        /**
         * {@link CatalogEntry#uniqueColors} scaled per megapixel of
         * {@link CatalogEntry#width}×{@link CatalogEntry#height}, so foldout
         * pages (scanned at 2x, 3x, or 3x2 the area of a normal page) don't
         * simply win on pixel count alone.
         */
        private static double colorsPerMegapixel(CatalogEntry e) {
            long pixels = (long) e.width * (long) e.height;
            return pixels == 0 ? 0.0 : e.uniqueColors * 1_000_000.0 / pixels;
        }

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
