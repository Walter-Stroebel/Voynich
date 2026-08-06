/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
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
    /**
     * Every known entry, in the app's current sort order — the ground
     * truth. {@link #model} is a filtered view onto this list, never the
     * other way around: {@link #sort()} and {@link #addOrUpdate} mutate
     * this list first, then call {@link #applyFilter()} to rebuild
     * {@link #model} from it.
     */
    private final List<CatalogEntry> allEntries = new ArrayList<>();
    private final DefaultListModel<CatalogEntry> model = new DefaultListModel<>();
    private final JList<CatalogEntry> list = new JList<>(model);
    private final Map<String, Icon> thumbnails = new HashMap<>();
    /**
     * Filenames currently passing {@link #filter()}'s search text, or
     * {@code null} when no filter is active (show everything). In-memory
     * only, never persisted to the catalog — the "selected thumbnails"
     * this exposes is expected to grow other uses (bulk tagging, etc.)
     * beyond just driving {@link #applyFilter()}.
     */
    private Set<String> selectedFilenames;

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
                CatalogEntryEditor.edit(SwingUtilities.getWindowAncestor(OverviewPanel.this), catalog,
                        model.getElementAt(idx), OverviewPanel.this::addOrUpdate);
            }
        });
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
        List<Comparator<CatalogEntry>> comparators = new ArrayList<>(keys.length * 2);
        for (SortKey key : keys) {
            comparators.add(key);
            comparators.add(key.reversed());
        }
        for (int i = 0; i < keys.length; i++) {
            labels[i * 2] = keys[i] + " ↑";
            labels[i * 2 + 1] = keys[i] + " ↓";
        }
        int choice = JOptionPane.showOptionDialog(this, "Sort by:", "Sort",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, labels, labels[0]);
        if (choice == JOptionPane.CLOSED_OPTION) {
            return;
        }
        Collections.sort(allEntries, comparators.get(choice));
        applyFilter();
    }

    /**
     * Prompts for free text and an "invert" checkbox, then narrows the grid
     * to entries whose JSON representation does (or, with the checkbox
     * checked, does not) contain it, case-insensitively. Blank text (or an
     * empty catalog match) clears the filter and shows everything again.
     * Wired to the app toolbar's Filter button in {@link Voynich#main}; must
     * be called from the EDT.
     */
    public void filter() {
        JTextField queryField = new JTextField(20);
        JCheckBox invert = new JCheckBox("Not matching (invert)");
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("Filter text (blank to clear):"), BorderLayout.NORTH);
        panel.add(queryField, BorderLayout.CENTER);
        panel.add(invert, BorderLayout.SOUTH);
        int choice = JOptionPane.showConfirmDialog(this, panel, "Filter",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        String query = queryField.getText().trim();
        if (query.isEmpty()) {
            selectedFilenames = null;
        } else {
            String needle = query.toLowerCase();
            boolean not = invert.isSelected();
            selectedFilenames = new LinkedHashSet<>();
            for (CatalogEntry entry : allEntries) {
                boolean matches = JSON.writeValueAsString(entry).toLowerCase().contains(needle);
                if (matches != not) {
                    selectedFilenames.add(entry.filename);
                }
            }
        }
        applyFilter();
    }

    /**
     * Rebuilds {@link #model} from {@link #allEntries}, keeping only
     * entries in {@link #selectedFilenames} ({@code null} meaning
     * "keep all"). Call after any change to either.
     */
    private void applyFilter() {
        model.clear();
        for (CatalogEntry entry : allEntries) {
            if (null == selectedFilenames || selectedFilenames.contains(entry.filename)) {
                model.addElement(entry);
            }
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
                    allEntries.set(idx, entry);
                } else {
                    allEntries.add(entry);
                }
                applyFilter();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            apply.run();
        } else {
            SwingUtilities.invokeLater(apply);
        }
    }

    private int indexOf(String filename) {
        for (int i = 0; i < allEntries.size(); i++) {
            if (allEntries.get(i).filename.equals(filename)) {
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
