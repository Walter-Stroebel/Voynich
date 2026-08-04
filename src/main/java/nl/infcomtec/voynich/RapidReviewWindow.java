/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * A general-purpose, whole-catalog "look and click" review pass: every
 * {@link CatalogEntry} with at least one file still on disk, shown one at a
 * time in random order, sized to two thirds of the display it opens on
 * ({@link GraphicsEnvironment}-driven, so it lands sensibly on whichever
 * monitor its owner is showing on — see CLAUDE.md's multi-monitor
 * guidance). For each image the reviewer can accept it (click the image,
 * press Enter, or the Accept button — invokes the pluggable
 * {@link RapidReviewAction}), skip it (Space/Right arrow/Skip button —
 * moves on without side effects), or abort the whole pass (Escape/Abort
 * button — closes immediately, no confirmation, since nothing about
 * aborting is destructive).
 * <p>
 * This class only drives the loop, the sizing, the random order, and
 * prefetching the next image off the EDT so clicking through doesn't wait
 * on disk/decode — what "accept" actually does is entirely up to the
 * {@link RapidReviewAction} passed in. The same window is meant to be
 * reused for any future single-glance judgment over the catalog by
 * supplying a different action, not rebuilt per task.
 * <p>
 * Separately from the pluggable accept judgment, every review also has a
 * free-text note field (type, Enter or the Note button — adds the text to
 * {@link CatalogEntry#tags} via {@link Catalog#addTag} and moves on). This
 * is deliberately not part of {@link RapidReviewAction}: a narrow, honest
 * accept contract ("only click the unambiguous ones") only stays usable if
 * there's somewhere to put the ones that don't cleanly fit it — a stain
 * that looks like a wash, a page worth a second look later, anything the
 * reviewer doesn't want to just lose by skipping.
 */
public class RapidReviewWindow extends JFrame {

    private final Catalog catalog;
    private final RapidReviewAction action;
    private final List<CatalogEntry> queue;
    private final Dimension imageBox;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private final JLabel imageLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel(" ");
    private final JTextField noteField = new JTextField();

    private int index = -1;
    private CatalogEntry current;
    private boolean currentLoaded;
    private CompletableFuture<BufferedImage> prefetched;

    /**
     * @param catalog source of entries to review
     * @param action what accepting an image means
     * @param owner window whose screen this should size/center against;
     * {@code null} uses the platform default screen
     * @throws IOException if listing the catalog fails
     */
    public RapidReviewWindow(Catalog catalog, RapidReviewAction action, Window owner) throws IOException {
        super("Rapid review — " + action.label());
        this.catalog = catalog;
        this.action = action;

        queue = new ArrayList<>();
        for (CatalogEntry entry : catalog.listAll()) {
            if (null != pickExistingFile(entry)) {
                queue.add(entry);
            }
        }
        Collections.shuffle(queue);

        GraphicsDevice device = null != owner ? owner.getGraphicsConfiguration().getDevice()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        Rectangle screen = device.getDefaultConfiguration().getBounds();
        int w = screen.width * 2 / 3;
        int h = screen.height * 2 / 3;
        imageBox = new Dimension(w - 40, h - 110);

        setLayout(new BorderLayout());
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(statusLabel, BorderLayout.NORTH);
        add(imageLabel, BorderLayout.CENTER);

        JButton accept = new JButton("Accept: " + action.label() + "  (Enter / click image)");
        JButton skip = new JButton("Skip  (Space)");
        JButton abort = new JButton("Abort  (Esc)");
        accept.addActionListener(e -> doAccept());
        skip.addActionListener(e -> doSkip());
        abort.addActionListener(e -> doAbort());
        JPanel buttonRow = new JPanel();
        buttonRow.add(accept);
        buttonRow.add(skip);
        buttonRow.add(abort);

        JButton noteButton = new JButton("Note & move on");
        noteField.addActionListener(e -> doNote());
        noteButton.addActionListener(e -> doNote());
        JPanel noteRow = new JPanel(new BorderLayout(6, 0));
        noteRow.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        noteRow.add(new JLabel("Note:"), BorderLayout.WEST);
        noteRow.add(noteField, BorderLayout.CENTER);
        noteRow.add(noteButton, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttonRow, BorderLayout.NORTH);
        south.add(noteRow, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        imageLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent evt) {
                doAccept();
            }
        });
        bindKey(KeyEvent.VK_ENTER, "accept", this::doAccept);
        // Unlike Enter/Right, Space isn't one of JTextField's own key
        // bindings (it only inserts via the generic typed-character
        // action), so without this guard it would also fire this
        // window-level shortcut while typing a note.
        bindKey(KeyEvent.VK_SPACE, "skip", () -> {
            if (!noteField.isFocusOwner()) {
                doSkip();
            }
        });
        bindKey(KeyEvent.VK_RIGHT, "skip", this::doSkip);
        bindKey(KeyEvent.VK_ESCAPE, "abort", this::doAbort);

        setBounds(screen.x + (screen.width - w) / 2, screen.y + (screen.height - h) / 2, w, h);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                doAbort();
            }
        });

        advance();
    }

    private void bindKey(int keyCode, String name, Runnable handler) {
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.run();
            }
        });
    }

    private void doAccept() {
        if (null == current || !currentLoaded) {
            return;
        }
        try {
            action.onAccept(current);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not record judgment for " + current.filename
                    + ":\n" + ex.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        advance();
    }

    private void doSkip() {
        advance();
    }

    /**
     * Adds the note field's text as a tag on the current entry (a no-op if
     * it's blank — same as {@link #doSkip()} in that case) and moves on.
     * Unlike {@link #doAccept()}, this doesn't require {@link #currentLoaded}
     * — a note is exactly the right place to record "this file wouldn't
     * load" too.
     */
    private void doNote() {
        String text = noteField.getText().trim();
        if (!text.isEmpty() && null != current) {
            try {
                catalog.addTag(current.filename, text);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not save note for " + current.filename
                        + ":\n" + ex.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        advance();
    }

    private void doAbort() {
        loader.shutdownNow();
        dispose();
    }

    private void advance() {
        index++;
        if (index >= queue.size()) {
            JOptionPane.showMessageDialog(this, "Reviewed all " + queue.size() + " images.",
                    "Review complete", JOptionPane.INFORMATION_MESSAGE);
            doAbort();
            return;
        }
        current = queue.get(index);
        currentLoaded = false;
        noteField.setText("");
        statusLabel.setText(String.format("%d / %d — %s", index + 1, queue.size(), current.filename));
        imageLabel.setIcon(null);
        imageLabel.setText("Loading…");

        CompletableFuture<BufferedImage> future = null != prefetched ? prefetched : loadAsync(current);
        CatalogEntry forEntry = current;
        future.whenComplete((img, ex) -> SwingUtilities.invokeLater(() -> onImageReady(forEntry, img)));

        prefetched = index + 1 < queue.size() ? loadAsync(queue.get(index + 1)) : null;
    }

    private void onImageReady(CatalogEntry forEntry, BufferedImage img) {
        if (forEntry != current) {
            return;
        }
        if (null == img) {
            imageLabel.setText("Could not load " + forEntry.filename + " — press Skip");
            currentLoaded = false;
        } else {
            imageLabel.setText(null);
            imageLabel.setIcon(new ImageIcon(img));
            currentLoaded = true;
        }
    }

    private CompletableFuture<BufferedImage> loadAsync(CatalogEntry entry) {
        return CompletableFuture.supplyAsync(() -> loadScaled(entry), loader);
    }

    private BufferedImage loadScaled(CatalogEntry entry) {
        File file = pickExistingFile(entry);
        if (null == file) {
            return null;
        }
        try {
            BufferedImage full = ImageIO.read(file);
            return null == full ? null : scaleToFit(full, imageBox.width, imageBox.height);
        } catch (IOException ex) {
            return null;
        }
    }

    private static File pickExistingFile(CatalogEntry entry) {
        for (CatalogEntry.Location loc : entry.locations) {
            File f = new File(loc.path);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private static BufferedImage scaleToFit(BufferedImage src, int maxW, int maxH) {
        double scale = Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
