/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Point;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

/**
 * Region-scoped view actions (Color Frequency, ΔE Heatmap, Open in infimg,
 * Ask Vision…) usable both from an open {@link CatalogEntryEditor} (its
 * "View ▾" menu) and directly from {@link OverviewPanel}'s thumbnail grid
 * via the "Selected" menu, with no editor open — see {@link Voynich}.
 * Each action re-reads the entry's file off disk rather than depending on
 * any already-decoded in-memory image, so it needs nothing beyond an entry,
 * an optional region, and a window to own its dialogs.
 */
final class RegionView {

    private RegionView() {
    }

    /**
     * Set once by {@link Voynich#main} right after the menu bar is built.
     * Every {@code SwingWorker} below reports through it so the menu bar's
     * scanner-bar indicator animates while any of them is running — see
     * {@link BusyIndicator}.
     */
    static BusyIndicator busy;

    /**
     * {@code region.polygon} as a plain {@link Point} list, the shape
     * {@link BitSet2D}'s crop methods take. {@code region} must be
     * non-null.
     */
    static List<Point> vertices(CatalogEntry.Region region) {
        List<Point> vertices = new ArrayList<>(region.polygon.size());
        for (CatalogEntry.Vertex v : region.polygon) {
            vertices.add(new Point(v.x, v.y));
        }
        return vertices;
    }

    /**
     * {@code full} cropped to {@code region} (black outside the polygon,
     * via {@link BitSet2D#cropToPolygon}), or {@code full} unchanged if
     * {@code region} is {@code null}.
     */
    static BufferedImage crop(BufferedImage full, CatalogEntry.Region region) {
        return null == region ? full : BitSet2D.cropToPolygon(full, vertices(region));
    }

    /**
     * Re-reads {@code entry}'s file off the EDT, crops to {@code region} (or
     * the whole page if {@code null}), and hands the result to
     * {@link Voynich#launchImageView(File)} via a temp PNG — the same path
     * {@link CatalogEntryEditor#openInInfimg()} uses, made callable without
     * an open editor.
     */
    static void openInInfimg(Window owner, CatalogEntry entry, CatalogEntry.Region region) {
        File file = ImageDisplay.pickExistingFile(entry);
        if (null == file) {
            return;
        }
        String kindSuffix = null == region ? "" : "." + region.kind;
        busy.enter();
        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws IOException {
                BufferedImage full = ImageIO.read(file);
                BufferedImage raster = crop(full, region);
                File target = new File(System.getProperty("java.io.tmpdir"),
                        entry.filename + kindSuffix + "." + System.currentTimeMillis() + ".png");
                ImageIO.write(raster, "png", target);
                return target;
            }

            @Override
            protected void done() {
                busy.exit();
                try {
                    Voynich.launchImageView(get());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(owner, "Could not open in infimg:\n" + ex.getMessage(),
                            "Open failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Re-reads {@code entry}'s file off the EDT, crops to {@code region} (or
     * the whole page if {@code null}), and delegates to
     * {@link #askVisionOnImage} — the single-entry, single-call case (also
     * used as the last step of {@link Voynich}'s N≥2 selection-set path,
     * once per entry).
     */
    static void askVision(Window owner, CatalogEntry entry, CatalogEntry.Region region, String question) {
        askVision(owner, entry, region, question, null);
    }

    /**
     * Same as {@link #askVision(Window, CatalogEntry, CatalogEntry.Region,
     * String)}, plus an {@code onComplete} callback — see
     * {@link #askVisionOnImage}'s doc for why a caller would want one.
     */
    static void askVision(Window owner, CatalogEntry entry, CatalogEntry.Region region, String question,
            Runnable onComplete) {
        File file = ImageDisplay.pickExistingFile(entry);
        if (null == file) {
            if (null != onComplete) {
                onComplete.run();
            }
            return;
        }
        askVisionOnImage(owner, entry.filename, file, region, question, onComplete);
    }

    /**
     * Crops {@code file}'s decoded image to {@code region} (or the whole
     * page if {@code null}) off the EDT, asks the local vision model
     * {@code question} about it, and shows the answer via
     * {@link CatalogEntryEditor#showStandaloneAnswer(Window, String, String)}
     * — same path {@link CatalogEntryEditor#askVision()} uses, made callable
     * without an open editor, and without requiring a {@link CatalogEntry}
     * (used for a composite image built from two selected entries, which has
     * no {@code CatalogEntry} of its own — see {@link Voynich}'s N=2
     * "combined" Ask Vision path). {@code label} identifies the image in the
     * answer dialog (a filename, or a description like "65v+65r combined").
     * {@code onComplete}, if non-null, runs after the answer dialog is
     * dismissed (success or failure alike) — lets a caller
     * ({@link Voynich}'s N≥2 selection-set path) chain one call after
     * another instead of firing several concurrently, since predator's
     * vision pipeline shouldn't be hit with simultaneous requests and a
     * single {@link #busy} on/off state can't meaningfully represent
     * several overlapping calls anyway.
     */
    static void askVisionOnImage(Window owner, String label, File file, CatalogEntry.Region region,
            String question, Runnable onComplete) {
        busy.enter();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws IOException, InterruptedException {
                BufferedImage full = ImageIO.read(file);
                BufferedImage raster = crop(full, region);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                ImageIO.write(raster, "png", buf);
                VisionClient vision = new VisionClient(Voynich.config);
                String fileId = vision.uploadImageDownscaled(buf.toByteArray());
                return vision.askAboutImage(fileId, "png", question);
            }

            @Override
            protected void done() {
                busy.exit();
                try {
                    CatalogEntryEditor.showStandaloneAnswer(owner, label + ": " + question, get());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(owner, "Vision request failed:\n" + ex.getMessage(),
                            "Ask Vision failed", JOptionPane.ERROR_MESSAGE);
                }
                if (null != onComplete) {
                    onComplete.run();
                }
            }
        }.execute();
    }

    /**
     * Re-reads {@code entry}'s file off the EDT, crops to {@code region} (or
     * the whole page if {@code null}), decodes it into a {@link ColorImage},
     * and opens {@code panelFactory}'s view of it via {@link ViewFrame} —
     * same path {@link CatalogEntryEditor#openColorVisualization} uses, made
     * callable without an open editor.
     */
    static void openColorVisualization(Window owner, CatalogEntry entry, CatalogEntry.Region region,
            String viewName, ColorVisualizationFactory panelFactory) {
        File file = ImageDisplay.pickExistingFile(entry);
        if (null == file) {
            return;
        }
        busy.enter();
        new SwingWorker<ColorImage, Void>() {
            @Override
            protected ColorImage doInBackground() throws IOException {
                if (null == region) {
                    return new ColorImage(file);
                }
                BufferedImage full = ImageIO.read(file);
                BitSet2D.Crop crop = BitSet2D.cropAndMaskPolygon(full, vertices(region));
                return new ColorImage(crop.image, entry.filename + " [" + region.kind + "]", crop.mask);
            }

            @Override
            protected void done() {
                busy.exit();
                try {
                    String frameTitle = null == region ? viewName : viewName + " — " + region.kind;
                    JComponent panel = panelFactory.createPanel(get());
                    ViewFrame.open(frameTitle, owner, panel, true, false);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(owner, "Could not analyse image:\n" + ex.getMessage(),
                            "Analysis failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
