/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

/**
 * Opens a modal view of a {@link CatalogEntry}: its actual image
 * ({@link BorderLayout#CENTER}, via {@link ImageDisplay}, since the image is
 * the information the JSON is annotating) with an editable raw-JSON view
 * docked to the side ({@link BorderLayout#EAST}) — the catalog's per-file
 * "notepad" ({@link CatalogEntry#tags}, {@link CatalogEntry#torrentJpg},
 * etc.) has no dedicated UI, and the whole entry is small enough that
 * hand-editing its JSON directly is simpler than building forms for each
 * field as they get added. Shared by {@link OverviewPanel} (click a
 * thumbnail) and {@link RapidReviewWindow} (its own view/edit button) so
 * there's exactly one dialog and one set of Save-time guards, not two
 * copies drifting apart.
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
 */
final class CatalogEntryEditor {

    private static final int EAST_WIDTH = 440;

    private CatalogEntryEditor() {
    }

    /**
     * @param owner window the dialog is sized/centered against; may be
     * {@code null}, which falls back to the platform default screen
     * @param catalog where Save writes to
     * @param entry the entry to view/edit; must already be in the catalog
     * @param onSaved called with the saved entry after a successful Save;
     * never called on Cancel
     */
    static void edit(Window owner, Catalog catalog, CatalogEntry entry, Consumer<CatalogEntry> onSaved) {
        JDialog dialog = new JDialog(owner, entry.filename, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout());

        GraphicsDevice device = null != owner ? owner.getGraphicsConfiguration().getDevice()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        Rectangle screen = device.getDefaultConfiguration().getBounds();
        int dialogW = screen.width * 2 / 3;
        int dialogH = screen.height * 2 / 3;

        JLabel imageLabel = new JLabel("", SwingConstants.CENTER);
        BufferedImage scaled = ImageDisplay.loadScaled(entry, dialogW - EAST_WIDTH - 40, dialogH - 40);
        if (null == scaled) {
            imageLabel.setText("No readable file for " + entry.filename);
        } else {
            imageLabel.setIcon(new ImageIcon(scaled));
        }
        dialog.add(imageLabel, BorderLayout.CENTER);

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
        scroll.setPreferredSize(new Dimension(EAST_WIDTH, dialogH - 160));

        JTextArea tagsText = new JTextArea(String.join("\n", entry.tags));
        tagsText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane tagsScroll = new JScrollPane(tagsText);
        tagsScroll.setBorder(BorderFactory.createTitledBorder("Tags (one per line)"));
        tagsScroll.setPreferredSize(new Dimension(EAST_WIDTH, 100));

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
            onSaved.accept(parsed);
            dialog.dispose();
        });
        cancel.addActionListener(evt -> dialog.dispose());
        JPanel buttons = new JPanel();
        buttons.add(save);
        buttons.add(cancel);

        JPanel south = new JPanel(new BorderLayout());
        south.add(tagsScroll, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel east = new JPanel(new BorderLayout());
        east.add(scroll, BorderLayout.CENTER);
        east.add(south, BorderLayout.SOUTH);
        dialog.add(east, BorderLayout.EAST);

        dialog.setSize(dialogW, dialogH);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
