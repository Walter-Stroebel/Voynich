/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Opens one already-traced {@link CatalogEntry.Region} as its own full-window
 * "main view" — the drill-down step {@link RegionManagerDialog}'s "View"
 * button now leads to, replacing what used to be a small static, unrotated
 * crop. The whole cropped-and-masked region fills the window (via
 * {@link BitSet2D#cropToPolygon}) and the mouse wheel rotates it live,
 * exactly like {@link ContentAreaCanvas}'s in-trace rotation preview, except
 * here the rotated crop <em>is</em> the whole window rather than a small
 * corner box, since this window's only job is showing this one region.
 * Rotating saves {@link CatalogEntry.Region#angle} immediately, same
 * every-action-saves-immediately convention as every other
 * {@code RegionManagerDialog} action.
 * <p>
 * "Add Child" opens {@link ContentAreaEditor} zoomed to this region's own
 * bounding box, with this region as the new region's
 * {@link CatalogEntry.Region#parentIndex} — so a many-figure diagram can be
 * drilled into (view region → add child → view that child → add its own
 * child, and so on) without returning to {@link RegionManagerDialog}'s list
 * for each step.
 */
final class RegionViewer {

    private RegionViewer() {
    }

    /** Remembered across "Export…" calls within one run, not persisted to {@link Config}. */
    private static File lastExportDir;

    /**
     * @param owner window the new viewer is opened near
     * @param catalog where a rotation change or a newly-traced child saves to
     * @param entry the owning entry; {@code region} must be one of its
     * {@link CatalogEntry#regions}
     * @param image {@code entry}'s already-decoded full-resolution image
     * @param regionIndex {@code region}'s index within {@code entry.regions} —
     * becomes the new child's {@link CatalogEntry.Region#parentIndex}
     * @param region the region to view/rotate
     * @param onChanged called after a rotation save or a child commit, so the
     * caller's own list/state can refresh
     */
    static void open(Window owner, Catalog catalog, CatalogEntry entry, BufferedImage image, int regionIndex,
            CatalogEntry.Region region, Runnable onChanged) {
        List<Point> polygon = new ArrayList<>(region.polygon.size());
        for (CatalogEntry.Vertex v : region.polygon) {
            polygon.add(new Point(v.x, v.y));
        }
        BufferedImage crop = BitSet2D.cropToPolygon(image, polygon);

        RotatableCropPanel canvas = new RotatableCropPanel(crop, region.angle);
        canvas.addMouseWheelListener(new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                canvas.rotate(e.getWheelRotation() * Math.toRadians(1));
                region.angle = canvas.angle();
                try {
                    catalog.save(entry, catalog.loadThumbnail(entry.filename));
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(canvas, "Save failed:\n" + ex.getMessage(),
                            "Save failed", JOptionPane.ERROR_MESSAGE);
                }
                if (null != onChanged) {
                    onChanged.run();
                }
            }
        });

        JLabel status = new JLabel("Mouse wheel rotates \"" + region.kind + "\" — saved immediately.");
        JButton addChild = new JButton(new EzAction("Add Child") {
            @Override
            public void actionPerformed(ActionEvent e) {
                KindAuthorPrompt.Result result = KindAuthorPrompt.prompt(owner, "New child region",
                        RegionManagerDialog.distinctKinds(catalog), region.kind, region.author);
                if (null == result) {
                    return;
                }
                java.awt.Rectangle viewport = boundingBox(region.polygon);
                ContentAreaEditor.open(owner, catalog, entry, image, null, result.kind, result.author, regionIndex,
                        viewport, onChanged);
            }
        }.withTooltip("Trace a new region nested inside this one, zoomed to its bounding box —"
                + " kind/author asked for first"));
        JButton export = new JButton(new EzAction("Export…") {
            @Override
            public void actionPerformed(ActionEvent e) {
                exportToFile(owner, entry, region, canvas.rotatedRaster());
            }
        }.withTooltip("Save this region, rotated as currently shown, to a PNG file"));
        JButton copy = new JButton(new EzAction("Copy to Clipboard") {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyToClipboard(owner, canvas.rotatedRaster());
            }
        }.withTooltip("Copy this region, rotated as currently shown, to the system clipboard"));
        JButton viewTmp = new JButton(new EzAction("Save to /tmp & View") {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveToTmpAndView(owner, entry, region, canvas.rotatedRaster());
            }
        }.withTooltip("Save this region, rotated as currently shown, to /tmp and open it in ImageView —"
                + " no file chooser needed"));
        JPanel buttons = new JPanel();
        buttons.add(export);
        buttons.add(copy);
        buttons.add(viewTmp);
        buttons.add(addChild);
        JPanel south = new JPanel(new BorderLayout());
        south.add(status, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(canvas, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        ViewFrame.open("Region View", owner, panel, true, true);
    }

    /**
     * Saves {@code raster} (already rotation-baked by
     * {@link RotatableCropPanel#rotatedRaster()}) as a PNG the user picks a
     * path for — the GUI equivalent of {@code CatalogCli extract
     * --content-area}/{@code --region-name}'s {@code --out}, which until now
     * was the only way to get a rotated region out as an actual file.
     */
    private static void exportToFile(Window owner, CatalogEntry entry, CatalogEntry.Region region,
            BufferedImage raster) {
        JFileChooser chooser = new JFileChooser(lastExportDir);
        chooser.setFileFilter(new FileNameExtensionFilter("PNG image", "png"));
        chooser.setSelectedFile(new File(lastExportDir, entry.filename + "." + region.kind + ".png"));
        if (JFileChooser.APPROVE_OPTION != chooser.showSaveDialog(owner)) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase().endsWith(".png")) {
            target = new File(target.getParentFile(), target.getName() + ".png");
        }
        lastExportDir = target.getParentFile();
        try {
            ImageIO.write(raster, "png", target);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(owner, "Export failed:\n" + ex.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Writes {@code raster} straight to a timestamped file under
     * {@code /tmp} and opens it in a detached {@link ImageView} process
     * (see {@link Voynich#launchImageView}) — for a quick look without
     * subjecting the user to a {@link JFileChooser} dialog over their real
     * filesystem, and without sharing this app's own EDT with whatever
     * pile of viewer windows accumulates over a session.
     */
    private static void saveToTmpAndView(Window owner, CatalogEntry entry, CatalogEntry.Region region,
            BufferedImage raster) {
        File target = new File(System.getProperty("java.io.tmpdir"),
                entry.filename + "." + region.kind + "." + System.currentTimeMillis() + ".png");
        try {
            ImageIO.write(raster, "png", target);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(owner, "Save failed:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Voynich.launchImageView(target);
    }

    /**
     * Puts {@code raster} on the system clipboard as an image — for pasting
     * straight into another application (a note, an email, a chat) without
     * the round trip through a saved file that {@link #exportToFile} needs.
     */
    private static void copyToClipboard(Window owner, BufferedImage raster) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new ImageTransferable(raster), null);
    }

    /** Minimal {@link Transferable} wrapping a single {@link BufferedImage} for clipboard image copy. */
    private static final class ImageTransferable implements Transferable {

        private final BufferedImage image;

        ImageTransferable(BufferedImage image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }

    private static java.awt.Rectangle boundingBox(List<CatalogEntry.Vertex> polygon) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (CatalogEntry.Vertex v : polygon) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
        }
        return new java.awt.Rectangle(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    /**
     * Fills its whole area with {@code crop}, rotated about its own center —
     * the "main view" itself is the rotated image, not a small preview next
     * to something else.
     */
    private static final class RotatableCropPanel extends JComponent {

        private final BufferedImage crop;
        private double angle;

        RotatableCropPanel(BufferedImage crop, double initialAngle) {
            this.crop = crop;
            this.angle = initialAngle;
            setPreferredSize(new java.awt.Dimension(crop.getWidth(), crop.getHeight()));
        }

        void rotate(double delta) {
            angle += delta;
            repaint();
        }

        double angle() {
            return angle;
        }

        /** Bakes the currently-shown rotation into a real raster, black outside — for Export/Copy. */
        BufferedImage rotatedRaster() {
            return BitSet2D.rotateUpright(crop, angle);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double fitScale = 0.92 * Math.min((double) getWidth() / crop.getWidth(), (double) getHeight() / crop.getHeight());
            g2.translate(getWidth() / 2.0, getHeight() / 2.0);
            g2.rotate(angle);
            g2.scale(fitScale, fitScale);
            g2.translate(-crop.getWidth() / 2.0, -crop.getHeight() / 2.0);
            g2.drawImage(crop, 0, 0, null);
        }
    }
}
