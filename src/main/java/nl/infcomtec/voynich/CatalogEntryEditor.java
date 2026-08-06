/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

/**
 * A non-modal view of one or more {@link CatalogEntry} records, one at a
 * time: the entry's actual image in {@link BorderLayout#CENTER} (via
 * {@link ImageDisplay}, since the image is the information the JSON is
 * annotating), an editable raw-JSON view and a plain tags box docked
 * {@link BorderLayout#EAST}. Two ways to use it:
 * <ul>
 * <li>{@link #edit} — a single entry, opened by {@link OverviewPanel} on a
 * thumbnail click. The button saves and closes.</li>
 * <li>{@link #review} — a shuffled queue of every entry with a readable
 * file, e.g. the toolbar's "Wash Review". Clicking the image appends a tag
 * built from {@link RapidReviewAction#tagTemplate()} and the click point to
 * the (already visible, already editable) tags box — nothing is written to
 * the catalog yet, so a bad click is corrected by deleting that line, same
 * as any other text edit. The button saves the current entry and loads the
 * next one from the queue instead of closing.</li>
 * </ul>
 * Both modes share the same save-time guards and the same window, so
 * there's exactly one implementation of each, not two copies drifting
 * apart.
 * <p>
 * This is the app's own database, owned by the person running it, so the
 * only validation on Save is against honest mistakes, not hostile input: the
 * JSON must parse (via {@link JSON}), {@link CatalogEntry#filename} must be
 * unchanged (it's the catalog key — editing it here would silently
 * create/orphan a row instead of renaming anything), and
 * {@link CatalogEntry#locations} must not have gone from non-empty to empty
 * (the easiest field to accidentally delete a line of and lose sighting
 * history for).
 * <p>
 * {@link CatalogEntry#tags} gets its own plain one-tag-per-line box instead
 * of living in the JSON blob — it's the field expected to be hand-edited the
 * most, and JSON array syntax (quoting, commas) is real friction for what's
 * just a short list of short strings. It's stripped out of the blob entirely
 * rather than shown in both places, so there's never two open editors
 * disagreeing about the same field.
 * <p>
 * {@link CatalogEntry#contentArea} gets the same treatment for a different
 * reason: it's edited by {@code ContentAreaEditor}, a separate window over
 * the same {@link #entry}, which can save it to the catalog while this
 * dialog is still open — but the JSON text box was rendered once at load
 * time and has no way to know that happened. So it's stripped from the blob
 * (nothing useful to hand-edit in a list of vertex coordinates anyway), and
 * {@link #save} always writes {@link #entry}'s live value rather than
 * whatever the stale blob text says, so a later Save here can never clobber
 * a trace that {@code ContentAreaEditor} already committed.
 */
final class CatalogEntryEditor {

    private static final int EAST_WIDTH = 440;

    private final Catalog catalog;
    private final List<CatalogEntry> queue;
    private final RapidReviewAction action;
    private final Consumer<CatalogEntry> onSaved;

    private final JDialog dialog;
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel imageLabel = new JLabel("", SwingConstants.CENTER);
    private final JTextArea jsonText = new JTextArea();
    private final JTextArea tagsText = new JTextArea();
    private final JButton freqButton = new JButton("Color Frequency");
    private final JButton heatButton = new JButton("ΔE Heatmap");
    private final JButton areaButton = new JButton("Content Area");
    private final JToggleButton maskButton = new JToggleButton("Show Mask");
    private final int imageMaxW;
    private final int imageMaxH;

    private int index = -1;
    private CatalogEntry entry;
    private Point lastLabelPoint;
    private BufferedImage fullImage;
    private Icon plainIcon;
    private Icon maskedIcon;

    /**
     * @param owner window the dialog is sized/centered against; may be
     * {@code null}, which falls back to the platform default screen
     * @param catalog where Save writes to
     * @param queue entries to show, in order; must be non-empty
     * @param action {@code null} for a single-entry edit (Save closes the
     * dialog); a review judgment for a batch pass (Save advances to the
     * next entry instead, and clicking the image stages a tag)
     * @param onSaved called with each saved entry; may be {@code null}
     */
    private CatalogEntryEditor(Window owner, Catalog catalog, List<CatalogEntry> queue,
            RapidReviewAction action, Consumer<CatalogEntry> onSaved) {
        this.catalog = catalog;
        this.queue = queue;
        this.action = action;
        this.onSaved = onSaved;

        String title = null != action ? action.label() + " review" : queue.get(0).filename;
        dialog = new JDialog(owner, title, JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());

        GraphicsDevice device = null != owner ? owner.getGraphicsConfiguration().getDevice()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        Rectangle screen = device.getDefaultConfiguration().getBounds();
        int dialogW = screen.width * 2 / 3;
        int dialogH = screen.height * 2 / 3;
        imageMaxW = dialogW - EAST_WIDTH - 40;
        imageMaxH = dialogH - 80;

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        dialog.add(statusLabel, BorderLayout.NORTH);
        dialog.add(imageLabel, BorderLayout.CENTER);

        jsonText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane jsonScroll = new JScrollPane(jsonText);
        jsonScroll.setPreferredSize(new Dimension(EAST_WIDTH, dialogH - 160));

        tagsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane tagsScroll = new JScrollPane(tagsText);
        tagsScroll.setBorder(BorderFactory.createTitledBorder("Tags (one per line)"));
        tagsScroll.setPreferredSize(new Dimension(EAST_WIDTH, 100));

        JButton primary = new JButton(null != action ? "Done (Enter)" : "Save (Enter)");
        JButton cancel = new JButton("Cancel (Esc)");
        primary.addActionListener(e -> save());
        cancel.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel();
        buttons.add(primary);
        buttons.add(cancel);

        freqButton.addActionListener(e -> openColorVisualization(freqButton, "Color Frequency",
                ci -> new JScrollPane(new FrequencyBarChart(ci))));
        heatButton.addActionListener(e -> openColorVisualization(heatButton, "ΔE Heatmap", DeltaEHeatmap::new));
        areaButton.addActionListener(e -> ContentAreaEditor.open(dialog.getOwner(), catalog, entry, fullImage,
                this::onContentAreaCommitted));
        maskButton.addActionListener(e -> {
            if (maskButton.isSelected()) {
                if (null != maskedIcon) {
                    imageLabel.setIcon(maskedIcon);
                } else {
                    buildMaskedIcon();
                }
            } else {
                imageLabel.setIcon(plainIcon);
            }
        });
        JPanel vizButtons = new JPanel();
        vizButtons.add(freqButton);
        vizButtons.add(heatButton);
        vizButtons.add(areaButton);
        vizButtons.add(maskButton);

        JPanel south = new JPanel(new BorderLayout());
        south.add(vizButtons, BorderLayout.NORTH);
        south.add(tagsScroll, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel east = new JPanel(new BorderLayout());
        east.add(jsonScroll, BorderLayout.CENTER);
        east.add(south, BorderLayout.SOUTH);
        dialog.add(east, BorderLayout.EAST);

        if (null != action) {
            imageLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent evt) {
                    lastLabelPoint = evt.getPoint();
                    addTemplatedTag();
                }
            });
            imageLabel.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent evt) {
                    lastLabelPoint = evt.getPoint();
                }
            });
        }

        // JTextArea binds Enter/Esc itself at WHEN_FOCUSED (insert-break /
        // nothing), which takes priority over these WHEN_IN_FOCUSED_WINDOW
        // bindings while either text box has focus — so Enter still inserts
        // a newline while editing, and only triggers Save/Done otherwise.
        bindKey(KeyEvent.VK_ENTER, "save", this::save);
        bindKey(KeyEvent.VK_ESCAPE, "cancel", dialog::dispose);

        dialog.setSize(dialogW, dialogH);
        dialog.setLocationRelativeTo(owner);

        advance();
        dialog.setVisible(true);
    }

    private void bindKey(int keyCode, String name, Runnable handler) {
        JComponent root = dialog.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.run();
            }
        });
    }

    /**
     * Appends a tag built from {@link RapidReviewAction#tagTemplate()} and
     * the last known pointer position (translated to the entry's original
     * image pixel coordinates, plus the colour sampled there) to the tags
     * box. Purely a text edit — nothing is written to the catalog until
     * {@link #save()}, so a mis-click is corrected by deleting the line, not
     * by undoing a database write.
     */
    private void addTemplatedTag() {
        Icon icon = imageLabel.getIcon();
        if (null == icon) {
            return;
        }
        Point imgPoint = ImageDisplay.toImageCoordinates(entry, icon.getIconWidth(), icon.getIconHeight(),
                imageLabel.getWidth(), imageLabel.getHeight(), lastLabelPoint);
        String tag = action.tagTemplate().replace("$X", String.valueOf(imgPoint.x))
                .replace("$Y", String.valueOf(imgPoint.y))
                .replace("$RGB", sampleRGB(imgPoint))
                .replace("$LAB", sampleLAB(imgPoint));
        String existing = tagsText.getText().stripTrailing();
        String updated = existing.isEmpty() ? tag : existing + "\n" + tag;
        tagsText.setText(updated);
        tagsText.setCaretPosition(updated.length());
    }

    /**
     * @return {@code "r,g,b"} sampled from {@link #fullImage} at {@code p},
     * or {@code "?,?,?"} if there is no full-resolution image to sample (a
     * decode failure that still left the scaled preview showing)
     */
    private String sampleRGB(Point p) {
        Color c = pixelAt(p);
        return null == c ? "?,?,?" : c.getRed() + "," + c.getGreen() + "," + c.getBlue();
    }

    /**
     * @return {@code "L,a,b"} (CIELAB, one decimal place) sampled from
     * {@link #fullImage} at {@code p}, or {@code "?,?,?"} if there is no
     * full-resolution image to sample
     */
    private String sampleLAB(Point p) {
        Color c = pixelAt(p);
        if (null == c) {
            return "?,?,?";
        }
        double[] lab = EnhancedColor.getCIELAB(c);
        return String.format("%.1f,%.1f,%.1f", lab[0], lab[1], lab[2]);
    }

    /**
     * Re-reads {@link #entry}'s file off the EDT into a fresh
     * {@link ColorImage} (a full per-pixel CIELab pass — can take a while on
     * the largest foldout scans), then hands it to {@code panelFactory} and
     * opens the result via {@link ViewFrame#open}. A no-op if the entry has
     * no readable file.
     */
    private void openColorVisualization(JButton source, String viewName, Function<ColorImage, JComponent> panelFactory) {
        File file = ImageDisplay.pickExistingFile(entry);
        if (null == file) {
            return;
        }
        source.setEnabled(false);
        new SwingWorker<ColorImage, Void>() {
            @Override
            protected ColorImage doInBackground() throws IOException {
                return new ColorImage(file);
            }

            @Override
            protected void done() {
                source.setEnabled(null != fullImage);
                try {
                    ViewFrame.open(viewName, dialog.getOwner(), panelFactory.apply(get()), true, false);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Could not analyse image:\n" + ex.getMessage(),
                            "Analysis failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Called by {@code ContentAreaEditor} right after it commits a trace for
     * {@link #entry} to the catalog. The mask overlay was built from
     * whatever {@link CatalogEntry#contentArea} was when it was last built —
     * possibly nothing, possibly a now-outdated trace — so it's discarded
     * rather than trusted, same reasoning as the JSON blob staleness fix
     * above: a background window mutated {@link #entry} and this dialog has
     * no other way to find out.
     */
    private void onContentAreaCommitted() {
        maskedIcon = null;
        maskButton.setEnabled(null != fullImage && entry.contentArea.size() >= 3);
        if (maskButton.isSelected()) {
            maskButton.setSelected(false);
            imageLabel.setIcon(plainIcon);
        }
    }

    /**
     * Rasterizes {@link CatalogEntry#contentArea} into a {@link BitSet2D}
     * (see its {@code createFromPolygon}) and darkens every pixel outside
     * it, off-EDT since this is a full-resolution pass over what can be an
     * 8000px-wide scan. Caches the result in {@link #maskedIcon} so toggling
     * back on doesn't recompute.
     */
    private void buildMaskedIcon() {
        CatalogEntry forEntry = entry;
        BufferedImage source = fullImage;
        maskButton.setEnabled(false);
        new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() {
                List<Point> vertices = new ArrayList<>();
                for (CatalogEntry.Vertex v : forEntry.contentArea) {
                    vertices.add(new Point(v.x, v.y));
                }
                BufferedImage out = BitSet2D.darkenOutside(source, vertices);
                return new ImageIcon(ImageDisplay.scaleToFit(out, imageMaxW, imageMaxH));
            }

            @Override
            protected void done() {
                if (forEntry != entry) {
                    return; // moved on to a different entry while this was computing
                }
                maskButton.setEnabled(null != fullImage);
                try {
                    maskedIcon = get();
                    if (maskButton.isSelected()) {
                        imageLabel.setIcon(maskedIcon);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Could not build mask overlay:\n" + ex.getMessage(),
                            "Mask failed", JOptionPane.ERROR_MESSAGE);
                    maskButton.setSelected(false);
                }
            }
        }.execute();
    }

    private Color pixelAt(Point p) {
        if (null == fullImage) {
            return null;
        }
        int x = Math.max(0, Math.min(fullImage.getWidth() - 1, p.x));
        int y = Math.max(0, Math.min(fullImage.getHeight() - 1, p.y));
        return new Color(fullImage.getRGB(x, y));
    }

    /**
     * Validates and writes the current text boxes for {@link #entry}, then
     * either closes the dialog (single-edit mode) or loads the next queued
     * entry (review mode). Does nothing on validation failure — the dialog
     * stays open on the same entry so the mistake can be fixed.
     */
    private void save() {
        CatalogEntry parsed;
        try {
            parsed = JSON.getMapper().readValue(jsonText.getText(), CatalogEntry.class);
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
        // entry.contentArea can be mutated and saved directly by
        // ContentAreaEditor while this dialog is open, but jsonText was
        // rendered once at load time and never refreshed — so it can't be
        // trusted as the source of truth for this field, same as tags.
        parsed.contentArea = entry.contentArea;
        try {
            catalog.save(parsed, catalog.loadThumbnail(entry.filename));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Save failed:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (null != onSaved) {
            onSaved.accept(parsed);
        }
        advance();
    }

    /**
     * Moves to the next entry in {@link #queue}, or closes the dialog once
     * it's exhausted. Also the initial load, called once from the
     * constructor.
     */
    private void advance() {
        index++;
        if (index >= queue.size()) {
            if (null != action) {
                JOptionPane.showMessageDialog(dialog, "Reviewed all " + queue.size() + " images.",
                        "Review complete", JOptionPane.INFORMATION_MESSAGE);
            }
            dialog.dispose();
            return;
        }
        entry = queue.get(index);
        lastLabelPoint = null;

        ObjectNode blob = JSON.getMapper().valueToTree(entry);
        blob.remove("tags");
        blob.remove("contentArea");
        String blobText;
        try {
            blobText = JSON.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(blob);
        } catch (JsonProcessingException ex) {
            // Can't actually happen: blob was just built from a real object, not parsed input.
            throw new IllegalStateException(ex);
        }
        jsonText.setText(blobText);
        jsonText.setCaretPosition(0);
        tagsText.setText(String.join("\n", entry.tags));
        tagsText.setCaretPosition(0);

        fullImage = ImageDisplay.loadFull(entry);
        maskedIcon = null;
        if (null == fullImage) {
            plainIcon = null;
            imageLabel.setIcon(null);
            imageLabel.setText("No readable file for " + entry.filename);
        } else {
            imageLabel.setText(null);
            plainIcon = new ImageIcon(ImageDisplay.scaleToFit(fullImage, imageMaxW, imageMaxH));
            imageLabel.setIcon(plainIcon);
        }
        freqButton.setEnabled(null != fullImage);
        heatButton.setEnabled(null != fullImage);
        areaButton.setEnabled(null != fullImage);
        maskButton.setSelected(false);
        maskButton.setEnabled(null != fullImage && entry.contentArea.size() >= 3);

        statusLabel.setText(null != action
                ? String.format("%d / %d — %s — click the image to add a \"%s\" tag",
                        index + 1, queue.size(), entry.filename, action.label())
                : entry.filename);
    }

    /**
     * Opens a single entry for viewing/editing. Save writes it and closes
     * the dialog.
     *
     * @param owner window the dialog is sized/centered against; may be
     * {@code null}
     * @param catalog where Save writes to
     * @param entry the entry to view/edit; must already be in the catalog
     * @param onSaved called with the saved entry after a successful Save;
     * never called on Cancel
     */
    static void edit(Window owner, Catalog catalog, CatalogEntry entry, Consumer<CatalogEntry> onSaved) {
        new CatalogEntryEditor(owner, catalog, List.of(entry), null, onSaved);
    }

    /**
     * Opens a shuffled, whole-catalog review pass: every {@link CatalogEntry}
     * with at least one file still on disk, one at a time. Clicking the
     * image stages a tag (via {@code action}) in the visible tags box; Done
     * saves the current entry (whatever's currently in the JSON/tags boxes,
     * staged tag or not) and loads the next one. Cancel/Esc stops the pass
     * without saving whatever's currently displayed.
     *
     * @param owner window the dialog is sized/centered against; may be
     * {@code null}
     * @param catalog source of entries to review, and where Done writes to
     * @param action what clicking the image records
     * @param onSaved called with each saved entry; may be {@code null}
     * @throws IOException if listing the catalog fails
     */
    static void review(Window owner, Catalog catalog, RapidReviewAction action, Consumer<CatalogEntry> onSaved)
            throws IOException {
        List<CatalogEntry> queue = new ArrayList<>();
        for (CatalogEntry candidate : catalog.listAll()) {
            if (null != ImageDisplay.pickExistingFile(candidate)) {
                queue.add(candidate);
            }
        }
        if (queue.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "No catalog entries with a readable file.",
                    "Nothing to review", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Collections.shuffle(queue);
        new CatalogEntryEditor(owner, catalog, queue, action, onSaved);
    }
}
