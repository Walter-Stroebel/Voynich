/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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
    private final Map<String, BufferedImage> thumbnails = new HashMap<>();
    /**
     * When {@code true}, {@link EntryRenderer} dims every thumbnail pixel
     * outside its entry's {@link CatalogEntry#mainRegion()} instead of
     * showing the plain thumbnail — see {@link #setContentAreaOnly(boolean)}.
     * Recomputed fresh on every repaint rather than cached: at thumbnail
     * resolution the mask-and-dim pass ({@link BitSet2D#darkenOutside}) is
     * microseconds, and {@code JList} only ever renders the handful of cells
     * actually visible, so there's nothing worth caching here.
     */
    private boolean contentAreaOnly;
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
                        model.getElementAt(idx), new EntrySavedListener() {
                            @Override
                            public void onEntrySaved(CatalogEntry saved) {
                                addOrUpdate(saved);
                            }
                        });
            }
        });
    }

    /**
     * Prompts (via a field combo box plus one "Descending" checkbox) for a
     * sort field and direction, then re-sorts the grid in place. Wired to
     * the app toolbar's Sort button in {@link Voynich#main}; must be called
     * from the EDT.
     */
    public void sort() {
        JComboBox<SortKey> fieldBox = new JComboBox<>(SortKey.values());
        JCheckBox descending = new JCheckBox("Descending");
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("Sort by:"), BorderLayout.NORTH);
        panel.add(fieldBox, BorderLayout.CENTER);
        panel.add(descending, BorderLayout.SOUTH);
        int choice = JOptionPane.showConfirmDialog(this, panel, "Sort",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        SortKey key = (SortKey) fieldBox.getSelectedItem();
        Comparator<CatalogEntry> comparator = descending.isSelected() ? key.reversed() : key;
        Collections.sort(allEntries, comparator);
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
     * Toggles whether the grid shows plain thumbnails or dims each one down
     * to just its traced {@link CatalogEntry#mainRegion()}. An entry with no
     * main region traced yet is left plain either way — this doubles as a
     * visual "not yet traced" indicator, not a claim that the whole
     * thumbnail is content. Wired to the app toolbar's toggle
     * button in {@link Voynich#main}; must be called from the EDT.
     *
     * @param on {@code true} to show content-area-only, {@code false} for
     * plain thumbnails
     */
    public void setContentAreaOnly(boolean on) {
        this.contentAreaOnly = on;
        list.repaint();
    }

    /**
     * Maps {@code entry}'s {@link CatalogEntry#mainRegion()} polygon (traced
     * in its full-resolution image's own pixel coordinates) into the thumbnail's
     * {@link ColorImage#THUMB_SIZE}×{@link ColorImage#THUMB_SIZE} coordinate
     * space: the same uniform scale-then-center-translate
     * {@link java.awt.geom.AffineTransform} that {@code ColorImage
     * .buildThumbnails} used to build the thumbnail image itself (aspect
     * preserved, no rotation/shear — a single scale factor for both axes
     * plus one offset for whichever axis got letterboxed).
     */
    private static List<Point> contentAreaInThumbnailSpace(CatalogEntry entry) {
        double scale = Math.min((double) ColorImage.THUMB_SIZE / entry.width, (double) ColorImage.THUMB_SIZE / entry.height);
        int scaledW = Math.max(1, (int) Math.round(entry.width * scale));
        int scaledH = Math.max(1, (int) Math.round(entry.height * scale));
        int offX = (ColorImage.THUMB_SIZE - scaledW) / 2;
        int offY = (ColorImage.THUMB_SIZE - scaledH) / 2;
        AffineTransform toThumbnail = new AffineTransform();
        toThumbnail.translate(offX, offY);
        toThumbnail.scale(scale, scale);
        List<CatalogEntry.Vertex> polygon = entry.mainRegion().polygon;
        List<Point> points = new ArrayList<>(polygon.size());
        for (CatalogEntry.Vertex v : polygon) {
            Point2D p = toThumbnail.transform(new Point2D.Double(v.x, v.y), null);
            points.add(new Point((int) Math.round(p.getX()), (int) Math.round(p.getY())));
        }
        return points;
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
        },
        PAGE_NUMBER("Page number") {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                int cmp = Integer.compare(pageNumberOf(a.filename), pageNumberOf(b.filename));
                return cmp != 0 ? cmp : a.filename.compareToIgnoreCase(b.filename);
            }
        },
        CONTENT_AREA_SIZE("Content area size") {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                return Double.compare(mainRegionArea(a), mainRegionArea(b));
            }
        };

        /**
         * Leading numeric folio number of {@code filename} (e.g. 3 for
         * "3r.png", 100 for "100v_and_101r.png"), or {@link Integer#MAX_VALUE}
         * if it doesn't start with {@code digit+[rv]} — numeric, not lexical,
         * so "3r" sorts before "20r" rather than after it.
         */
        private static int pageNumberOf(String filename) {
            Matcher m = PAGE_NUMBER_PATTERN.matcher(filename);
            if (!m.find()) {
                return Integer.MAX_VALUE;
            }
            return Integer.parseInt(m.group(1));
        }

        private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("^(\\d+)[rv]");

        /**
         * Shoelace-formula area of {@code entry}'s {@link CatalogEntry#mainRegion()}
         * polygon, or {@code -1} if no main region has been traced yet — sorts
         * untraced entries before every real (non-negative) area.
         */
        private static double mainRegionArea(CatalogEntry entry) {
            CatalogEntry.Region region = entry.mainRegion();
            if (null == region) {
                return -1;
            }
            List<CatalogEntry.Vertex> polygon = region.polygon;
            double sum = 0;
            for (int i = 0; i < polygon.size(); i++) {
                CatalogEntry.Vertex p1 = polygon.get(i);
                CatalogEntry.Vertex p2 = polygon.get((i + 1) % polygon.size());
                sum += (double) p1.x * p2.y - (double) p2.x * p1.y;
            }
            return Math.abs(sum) / 2.0;
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
        Runnable apply = new Runnable() {
            @Override
            public void run() {
                thumbnails.put(entry.filename, thumbnail);
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
            BufferedImage thumb = thumbnails.get(entry.filename);
            Icon icon = null;
            if (null != thumb) {
                if (contentAreaOnly && null != entry.mainRegion()) {
                    icon = new ImageIcon(BitSet2D.darkenOutside(thumb, contentAreaInThumbnailSpace(entry)));
                } else {
                    icon = new ImageIcon(thumb);
                }
            }
            setIcon(icon);
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
