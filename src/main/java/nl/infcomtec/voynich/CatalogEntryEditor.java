/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
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
 * {@link CatalogEntry#regions} gets the same treatment for a different
 * reason: it's edited by {@code RegionManagerDialog}, a separate window over
 * the same {@link #entry}, which can save it to the catalog while this
 * dialog is still open — but the JSON text box was rendered once at load
 * time and has no way to know that happened. So it's stripped from the blob
 * (nothing useful to hand-edit in a list of vertex coordinates anyway), and
 * {@link #save} always writes {@link #entry}'s live value rather than
 * whatever the stale blob text says, so a later Save here can never clobber
 * a trace that {@code RegionManagerDialog} already committed.
 */
final class CatalogEntryEditor {

    private static final int EAST_WIDTH = 440;
    private static final BasicStroke HOVER_STROKE = new BasicStroke(3f);

    private final Catalog catalog;
    private final List<CatalogEntry> queue;
    private final RapidReviewAction action;
    private final EntrySavedListener onSaved;

    private final JDialog dialog;
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel imageLabel = new JLabel("", SwingConstants.CENTER);
    private final JTextArea jsonText = new JTextArea();
    private final JTextArea tagsText = new JTextArea();
    private final JComboBox<String> regionSelector = new JComboBox<>();
    private final JButton viewButton = new JButton("View ▾");
    private final JPopupMenu viewMenu = new JPopupMenu();
    private final JMenuItem freqItem = new JMenuItem(new EzAction("Color Frequency") {
        @Override
        public void actionPerformed(ActionEvent e) {
            openColorVisualization(viewButton, "Color Frequency", new ColorVisualizationFactory() {
                @Override
                public JComponent createPanel(ColorImage ci) {
                    return new JScrollPane(new FrequencyBarChart(ci));
                }
            });
        }
    }.withTooltip("Ranked colour-frequency chart for the selected region (or the whole page)"));
    private final JMenuItem heatItem = new JMenuItem(new EzAction("ΔE Heatmap") {
        @Override
        public void actionPerformed(ActionEvent e) {
            openColorVisualization(viewButton, "ΔE Heatmap", new ColorVisualizationFactory() {
                @Override
                public JComponent createPanel(ColorImage ci) {
                    return new DeltaEHeatmap(ci);
                }
            });
        }
    }.withTooltip("Spatial colour-distance-from-average map for the selected region (or the whole page)"));
    private final JMenuItem infimgItem = new JMenuItem(new EzAction("Open in infimg") {
        @Override
        public void actionPerformed(ActionEvent e) {
            openInInfimg();
        }
    }.withTooltip("Save the selected region (or the whole page) to /tmp and open it full-resolution in infimg"));
    private final JMenuItem visionItem = new JMenuItem(new EzAction("Ask Vision…") {
        @Override
        public void actionPerformed(ActionEvent e) {
            askVision();
        }
    }.withTooltip("Ask the local vision model a free-text question about the selected region (or the whole page)"));
    private final JButton areaButton = new JButton(new EzAction("Regions…") {
        @Override
        public void actionPerformed(ActionEvent e) {
            RegionManagerDialog.open(dialog.getOwner(), catalog, entry, fullImage, new Runnable() {
                @Override
                public void run() {
                    onRegionsChanged();
                }
            });
        }
    }.withTooltip("Add, trace, rename, reorder, or delete this entry's traced regions"));
    private final int imageMaxW;
    private final int imageMaxH;

    private int index = -1;
    private CatalogEntry entry;
    private Point lastLabelPoint;
    private BufferedImage fullImage;
    private Icon plainIcon;
    private int hoveredRegionIndex = -1;

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
            RapidReviewAction action, EntrySavedListener onSaved) {
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

        JButton primary = new JButton(new EzAction(null != action ? "Done (Enter)" : "Save (Enter)") {
            @Override
            public void actionPerformed(ActionEvent e) {
                save();
            }
        }.withTooltip(null != action ? "Save this entry and load the next one in the review queue"
                : "Save this entry and close"));
        JButton cancel = new JButton(new EzAction("Cancel (Esc)") {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        }.withTooltip("Close without saving"));
        JPanel buttons = new JPanel();
        buttons.add(primary);
        buttons.add(cancel);

        regionSelector.setToolTipText("Which region the displayed image and View menu apply to");
        regionSelector.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateDisplayedRegion();
            }
        });
        viewMenu.add(freqItem);
        viewMenu.add(heatItem);
        viewMenu.add(infimgItem);
        viewMenu.add(visionItem);
        viewButton.setToolTipText("View the selected region (or whole page): colour analysis or infimg");
        viewButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                viewMenu.show(viewButton, 0, viewButton.getHeight());
            }
        });
        JPanel vizButtons = new JPanel();
        vizButtons.add(new JLabel("Region:"));
        vizButtons.add(regionSelector);
        vizButtons.add(viewButton);
        vizButtons.add(areaButton);

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

        imageLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent evt) {
                clearHoveredRegion();
            }
        });
        imageLabel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent evt) {
                updateHoveredRegion(evt.getPoint());
            }
        });

        // JTextArea binds Enter/Esc itself at WHEN_FOCUSED (insert-break /
        // nothing), which takes priority over these WHEN_IN_FOCUSED_WINDOW
        // bindings while either text box has focus — so Enter still inserts
        // a newline while editing, and only triggers Save/Done otherwise.
        bindKey(KeyEvent.VK_ENTER, "save", new Runnable() {
            @Override
            public void run() {
                save();
            }
        });
        bindKey(KeyEvent.VK_ESCAPE, "cancel", new Runnable() {
            @Override
            public void run() {
                dialog.dispose();
            }
        });

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
     * Same "whole page or {@link #regionSelector}'s current region" crop as
     * {@link #openInInfimg}, but instead of opening the result locally, ships
     * it to the {@code predator} vision pipeline via {@link VisionClient} —
     * prompts for a free-text question ({@code JOptionPane}, since a
     * one-shot text prompt doesn't warrant a whole non-modal window of its
     * own the way a real tool window would), uploads and asks off the EDT,
     * then shows the answer in a read-only, scrollable, selectable text area
     * (Ctrl+A/Ctrl+C work natively on a non-editable {@code JTextArea}) plus
     * an explicit "Copy to Clipboard" button — answers routinely run several
     * markdown-formatted paragraphs, too long for a plain
     * {@code JOptionPane} message and needing more than a screenshot to get
     * out of the dialog. Separately offers to stage just the question (not
     * the full answer — a multi-paragraph answer is the wrong shape for the
     * tags box's one-line-per-note convention) as a short "asked vision:
     * ..." tag, so the fact that a question was asked is discoverable later
     * even though the full answer itself isn't kept. Deliberately just one
     * free-text entry point, no canned prompts — "a hammer, not a scalpel,"
     * Walter's call.
     */
    private void askVision() {
        if (null == fullImage) {
            return;
        }
        String question = JOptionPane.showInputDialog(dialog,
                "Question for the vision model:", "Ask Vision", JOptionPane.PLAIN_MESSAGE);
        if (null == question || question.isBlank()) {
            return;
        }
        int regionIndex = regionSelector.getSelectedIndex();
        CatalogEntry.Region region = regionIndex > 0 ? entry.regions.get(regionIndex) : null;
        BufferedImage source = fullImage;
        visionItem.setEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws IOException, InterruptedException {
                BufferedImage raster = source;
                if (null != region) {
                    List<Point> vertices = new ArrayList<>(region.polygon.size());
                    for (CatalogEntry.Vertex v : region.polygon) {
                        vertices.add(new Point(v.x, v.y));
                    }
                    raster = BitSet2D.cropToPolygon(source, vertices);
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                ImageIO.write(raster, "png", buf);
                VisionClient vision = new VisionClient(Voynich.config);
                String fileId = vision.uploadImageDownscaled(buf.toByteArray());
                return vision.askAboutImage(fileId, "png", question);
            }

            @Override
            protected void done() {
                visionItem.setEnabled(true);
                try {
                    showAnswer(question, get());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Vision request failed:\n" + ex.getMessage(),
                            "Ask Vision failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Shows a vision-model answer in a small non-modal dialog: a read-only,
     * word-wrapped {@link JTextArea} (Ctrl+A/Ctrl+C already work on a
     * non-editable text area without any extra wiring) inside a
     * {@link JScrollPane} so a long multi-paragraph answer scrolls instead
     * of stretching the dialog off-screen, plus an explicit
     * "Copy to Clipboard" button (a screenshot was the only way to get an
     * answer out before this). {@code question} is prefixed into the
     * displayed/copied text (not just implied by context) since a
     * copy-pasted answer is often shared or filed away separately from this
     * dialog, where the question wouldn't otherwise be visible. Offers to
     * stage just the {@code question} —
     * not the full {@code answer}, which is routinely several
     * markdown-formatted paragraphs and the wrong shape for the tags box's
     * one-line-per-note convention — as a short "asked vision: ..." tag.
     */
    private void showAnswer(String question, String answer) {
        String display = "Q: " + question + "\n\n" + answer;
        JTextArea area = new JTextArea(display);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(600, 400));

        JButton copyButton = new JButton(new EzAction("Copy to Clipboard") {
            @Override
            public void actionPerformed(ActionEvent e) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(display), null);
            }
        }.withTooltip("Copy the question and answer text to the system clipboard"));
        JPanel south = new JPanel();
        south.add(copyButton);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(dialog, panel, "Vision answer", JOptionPane.PLAIN_MESSAGE);

        int choice = JOptionPane.showConfirmDialog(dialog,
                "Stage a tag noting this question was asked?\n(\"asked vision: " + question + "\")",
                "Add tag?", JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (JOptionPane.YES_OPTION == choice) {
            String tag = "asked vision: " + question;
            String existing = tagsText.getText().stripTrailing();
            tagsText.setText(existing.isEmpty() ? tag : existing + "\n" + tag);
        }
    }

    /**
     * Re-reads {@link #entry}'s file off the EDT into a fresh
     * {@link ColorImage} (a full per-pixel CIELab pass — can take a while on
     * the largest foldout scans) — or, if {@link #regionSelector} has a
     * region other than "Whole page" selected, crops to just that region's
     * bounding box first (via {@link BitSet2D#cropAndMaskPolygon}) and
     * analyses only the polygon's real pixels, excluding the crop's
     * blacked-out corners from the colour inventory entirely rather than
     * just hiding them visually — then hands the result to
     * {@code panelFactory} and opens it via {@link ViewFrame#open}. A no-op
     * if the entry has no readable file.
     */
    private void openColorVisualization(JButton source, String viewName, ColorVisualizationFactory panelFactory) {
        File file = ImageDisplay.pickExistingFile(entry);
        if (null == file) {
            return;
        }
        int regionIndex = regionSelector.getSelectedIndex();
        CatalogEntry.Region region = regionIndex > 0 ? entry.regions.get(regionIndex) : null;
        source.setEnabled(false);
        new SwingWorker<ColorImage, Void>() {
            @Override
            protected ColorImage doInBackground() throws IOException {
                if (null == region) {
                    return new ColorImage(file);
                }
                BufferedImage full = ImageIO.read(file);
                List<Point> vertices = new ArrayList<>(region.polygon.size());
                for (CatalogEntry.Vertex v : region.polygon) {
                    vertices.add(new Point(v.x, v.y));
                }
                BitSet2D.Crop crop = BitSet2D.cropAndMaskPolygon(full, vertices);
                return new ColorImage(crop.image, entry.filename + " [" + region.kind + "]", crop.mask);
            }

            @Override
            protected void done() {
                source.setEnabled(null != fullImage);
                try {
                    String frameTitle = null == region ? viewName : viewName + " — " + region.kind;
                    ViewFrame.open(frameTitle, dialog.getOwner(), panelFactory.createPanel(get()), true, false);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Could not analyse image:\n" + ex.getMessage(),
                            "Analysis failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Same "whole page or {@link #regionSelector}'s current region" reading
     * {@link #openColorVisualization} uses, but skips the CIELab decode
     * entirely — this just wants pixels on screen, not a colour inventory —
     * and hands the raw crop (via {@link BitSet2D#cropToPolygon}, black
     * outside the polygon, same as {@code RegionViewer}'s "Save to /tmp &amp;
     * View") straight to {@link Voynich#launchImageView}, same
     * no-file-chooser convenience that button already offers one drill-down
     * level deeper.
     */
    private void openInInfimg() {
        int regionIndex = regionSelector.getSelectedIndex();
        CatalogEntry.Region region = regionIndex > 0 ? entry.regions.get(regionIndex) : null;
        if (null == fullImage) {
            return;
        }
        BufferedImage source = fullImage;
        String kindSuffix = null == region ? "" : "." + region.kind;
        infimgItem.setEnabled(false);
        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws IOException {
                BufferedImage raster = source;
                if (null != region) {
                    List<Point> vertices = new ArrayList<>(region.polygon.size());
                    for (CatalogEntry.Vertex v : region.polygon) {
                        vertices.add(new Point(v.x, v.y));
                    }
                    raster = BitSet2D.cropToPolygon(source, vertices);
                }
                File target = new File(System.getProperty("java.io.tmpdir"),
                        entry.filename + kindSuffix + "." + System.currentTimeMillis() + ".png");
                ImageIO.write(raster, "png", target);
                return target;
            }

            @Override
            protected void done() {
                infimgItem.setEnabled(true);
                try {
                    Voynich.launchImageView(get());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Could not open in infimg:\n" + ex.getMessage(),
                            "Open failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Rebuilds {@link #regionSelector}'s items from {@link #entry}'s
     * current {@link CatalogEntry#regions}: {@code "Whole page"} at index 0
     * (matching {@link #openColorVisualization}'s "no region selected"
     * reading of index 0), then one entry per region from index 1 on
     * (index 0, the synthetic whole-page region, is never listed here
     * either — selecting "Whole page" already means the same thing).
     *
     * @param preserveSelection {@code true} to keep the current selection by
     * label where possible (so {@link #onRegionsChanged}'s rebuild of the
     * <em>same</em> entry's list doesn't silently snap back to "Whole page"
     * just because a rename changed a label); {@code false} to always reset
     * to "Whole page" — {@link #advance}'s case, where the previous
     * selection belonged to a different entry entirely and carrying it over
     * would be a coincidence, not a preserved choice.
     */
    private void refreshRegionSelector(boolean preserveSelection) {
        Object previouslySelected = preserveSelection ? regionSelector.getSelectedItem() : null;
        regionSelector.removeAllItems();
        regionSelector.addItem("Whole page");
        for (int i = 1; i < entry.regions.size(); i++) {
            regionSelector.addItem("#" + i + ": " + entry.regions.get(i).kind);
        }
        regionSelector.setSelectedItem(null != previouslySelected ? previouslySelected : "Whole page");
    }

    /**
     * Called by {@code RegionManagerDialog} right after any action (Add,
     * Trace, Rename, Up, Down, Delete) writes {@link #entry}'s regions to
     * the catalog. Rebuilds {@link #regionSelector} (whatever it's showing
     * may now be stale — a background window mutated {@link #entry} and
     * this dialog has no other way to find out) and, since that rebuild can
     * itself change the selection, re-derives the displayed image from
     * whatever ends up selected.
     */
    private void onRegionsChanged() {
        hoveredRegionIndex = -1;
        refreshRegionSelector(true);
        updateDisplayedRegion();
    }

    /**
     * Sets {@link #imageLabel}'s icon from {@link #regionSelector}'s current
     * selection: {@link #plainIcon} for "Whole page" (index 0), or — for any
     * other region — a fresh darken-outside-the-polygon overlay (via
     * {@link BitSet2D#darkenOutside}), built off-EDT since this is a
     * full-resolution pass over what can be an 8000px-wide scan. Replaces
     * the old separate "Show Mask" toggle: which region is being looked at
     * and which region analysis runs over are the same question, so one
     * selector answers both instead of two independent controls that could
     * disagree.
     */
    private void updateDisplayedRegion() {
        hoveredRegionIndex = -1;
        if (null == fullImage) {
            return;
        }
        int regionIndex = regionSelector.getSelectedIndex();
        if (regionIndex <= 0) {
            imageLabel.setIcon(plainIcon);
            return;
        }
        CatalogEntry forEntry = entry;
        CatalogEntry.Region region = entry.regions.get(regionIndex);
        BufferedImage source = fullImage;
        new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() {
                List<Point> vertices = new ArrayList<>();
                for (CatalogEntry.Vertex v : region.polygon) {
                    vertices.add(new Point(v.x, v.y));
                }
                BufferedImage out = BitSet2D.darkenOutside(source, vertices);
                return new ImageIcon(ImageDisplay.scaleToFit(out, imageMaxW, imageMaxH));
            }

            @Override
            protected void done() {
                if (forEntry != entry || regionSelector.getSelectedIndex() != regionIndex) {
                    return; // moved on to a different entry/selection while this was computing
                }
                try {
                    imageLabel.setIcon(get());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Could not build region overlay:\n" + ex.getMessage(),
                            "Overlay failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Cheap "where did I leave off" hover feedback on the whole-page view:
     * on every mouse move, tests the cursor against every traced region's
     * polygon and, when the answer changes, XOR-draws the old outline away
     * and the new one in directly on {@link #imageLabel} — same
     * draw-twice-to-erase trick {@link ContentAreaCanvas} uses for its
     * rubber-band guide and hover highlight, so this doesn't cost a repaint
     * of the (potentially huge) scaled icon either. A no-op while a
     * region-scoped crop is selected, since {@link #imageLabel}'s icon is
     * then a crop with different coordinates than {@link CatalogEntry.Region}
     * polygons (which are always in full-page coordinates) would map to.
     */
    private void updateHoveredRegion(Point labelPoint) {
        if (regionSelector.getSelectedIndex() > 0) {
            return;
        }
        Icon icon = imageLabel.getIcon();
        if (null == icon || null == entry) {
            return;
        }
        Point imgPoint = ImageDisplay.toImageCoordinates(entry, icon.getIconWidth(), icon.getIconHeight(),
                imageLabel.getWidth(), imageLabel.getHeight(), labelPoint);
        int newHover = findRegionAt(imgPoint);
        if (newHover == hoveredRegionIndex) {
            return;
        }
        if (hoveredRegionIndex >= 0) {
            drawRegionOutlineXor(hoveredRegionIndex);
        }
        hoveredRegionIndex = newHover;
        if (hoveredRegionIndex >= 0) {
            drawRegionOutlineXor(hoveredRegionIndex);
        }
    }

    private void clearHoveredRegion() {
        if (hoveredRegionIndex >= 0) {
            drawRegionOutlineXor(hoveredRegionIndex);
            hoveredRegionIndex = -1;
        }
    }

    /**
     * @return the index into {@link CatalogEntry#regions} (index 0, the
     * synthetic whole-page region, never matches) of the topmost — last in
     * list order, so a later Add wins over an older overlapping one —
     * region containing {@code imagePoint}, or {@code -1} if none does
     */
    private int findRegionAt(Point imagePoint) {
        for (int i = entry.regions.size() - 1; i >= 1; i--) {
            if (containsPoint(entry.regions.get(i).polygon, imagePoint)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Standard even-odd ray-casting point-in-polygon test — cheap enough to
     * run against every region on every mouse move given these polygons
     * only ever run to a few dozen vertices.
     */
    private static boolean containsPoint(List<CatalogEntry.Vertex> polygon, Point p) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            CatalogEntry.Vertex vi = polygon.get(i);
            CatalogEntry.Vertex vj = polygon.get(j);
            if ((vi.y > p.y) != (vj.y > p.y)
                    && p.x < (vj.x - vi.x) * (double) (p.y - vi.y) / (vj.y - vi.y) + vi.x) {
                inside = !inside;
            }
        }
        return inside;
    }

    private void drawRegionOutlineXor(int regionIndex) {
        Icon icon = imageLabel.getIcon();
        if (null == icon) {
            return;
        }
        Graphics2D g2 = (Graphics2D) imageLabel.getGraphics();
        if (null == g2) {
            return;
        }
        int iconW = icon.getIconWidth();
        int iconH = icon.getIconHeight();
        int offX = (imageLabel.getWidth() - iconW) / 2;
        int offY = (imageLabel.getHeight() - iconH) / 2;
        double scaleX = iconW / (double) entry.width;
        double scaleY = iconH / (double) entry.height;
        List<CatalogEntry.Vertex> polygon = entry.regions.get(regionIndex).polygon;
        g2.setXORMode(Color.WHITE);
        g2.setColor(Color.CYAN);
        g2.setStroke(HOVER_STROKE);
        CatalogEntry.Vertex last = polygon.get(polygon.size() - 1);
        int prevX = offX + (int) Math.round(last.x * scaleX);
        int prevY = offY + (int) Math.round(last.y * scaleY);
        for (CatalogEntry.Vertex v : polygon) {
            int curX = offX + (int) Math.round(v.x * scaleX);
            int curY = offY + (int) Math.round(v.y * scaleY);
            g2.drawLine(prevX, prevY, curX, curY);
            prevX = curX;
            prevY = curY;
        }
        g2.setPaintMode();
        g2.dispose();
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
        // entry.regions can be mutated and saved directly by
        // ContentAreaEditor while this dialog is open, but jsonText was
        // rendered once at load time and never refreshed — so it can't be
        // trusted as the source of truth for this field, same as tags.
        parsed.regions = entry.regions;
        try {
            catalog.save(parsed, catalog.loadThumbnail(entry.filename));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Save failed:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (null != onSaved) {
            onSaved.onEntrySaved(parsed);
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
        hoveredRegionIndex = -1;

        ObjectNode blob = JSON.getMapper().valueToTree(entry);
        blob.remove("tags");
        blob.remove("regions");
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
        if (null == fullImage) {
            plainIcon = null;
            imageLabel.setIcon(null);
            imageLabel.setText("No readable file for " + entry.filename);
        } else {
            imageLabel.setText(null);
            plainIcon = new ImageIcon(ImageDisplay.scaleToFit(fullImage, imageMaxW, imageMaxH));
            imageLabel.setIcon(plainIcon);
        }
        refreshRegionSelector(false);
        viewButton.setEnabled(null != fullImage);
        areaButton.setEnabled(null != fullImage);

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
    static void edit(Window owner, Catalog catalog, CatalogEntry entry, EntrySavedListener onSaved) {
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
    static void review(Window owner, Catalog catalog, RapidReviewAction action, EntrySavedListener onSaved)
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
