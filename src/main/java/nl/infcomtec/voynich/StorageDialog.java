/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

/**
 * Replaces the old opaque Checkpoint/Undo toolbar buttons with a visible
 * view of what's actually on disk: the live catalog's size, and every
 * checkpoint's age and size, with take/restore/delete actions. Modeless,
 * matching {@link CatalogEntryEditor}'s and {@link ViewFrame}'s
 * independent-window convention — see {@code Voynich}'s "Windows don't own
 * windows" reasoning. A three-column, few-row list with no sorting/editing
 * doesn't need {@code JTable}; one {@code JRadioButton} per row (grouped for
 * single-selection) plus a plain {@code GridBagLayout} grid is simpler and
 * just as clear.
 */
final class StorageDialog {

    private final JDialog dialog;
    private final Catalog catalog;
    private final Runnable onRestored;
    private final JLabel liveLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JPanel checkpointsPanel = new JPanel(new GridBagLayout());
    private ButtonGroup selectionGroup = new ButtonGroup();
    private final JButton restoreButton = new JButton(new EzAction("Restore Selected") {
        @Override
        public void actionPerformed(ActionEvent e) {
            restoreSelected();
        }
    }.withTooltip("Discard everything since the selected checkpoint, replacing the live catalog with it"));
    private final JButton deleteButton = new JButton(new EzAction("Delete Selected") {
        @Override
        public void actionPerformed(ActionEvent e) {
            deleteSelected();
        }
    }.withTooltip("Permanently delete the selected checkpoint (the live catalog is untouched)"));
    private final List<JRadioButton> rowSelectors = new ArrayList<>();
    private List<Catalog.CheckpointInfo> checkpoints = List.of();

    private StorageDialog(Window owner, Catalog catalog, Runnable onRestored) {
        this.catalog = catalog;
        this.onRestored = onRestored;

        dialog = new JDialog(owner, "Storage", JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());

        liveLabel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        dialog.add(liveLabel, BorderLayout.NORTH);

        checkpointsPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        dialog.add(new JScrollPane(checkpointsPanel), BorderLayout.CENTER);

        JButton takeButton = new JButton(new EzAction("Take Checkpoint Now") {
            @Override
            public void actionPerformed(ActionEvent e) {
                takeCheckpoint();
            }
        }.withTooltip("Zip the entire current catalog into a new timestamped checkpoint"));
        JButton closeButton = new JButton(new EzAction("Close") {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        }.withTooltip("Close this window"));
        JPanel buttons = new JPanel();
        buttons.add(takeButton);
        buttons.add(restoreButton);
        buttons.add(deleteButton);
        buttons.add(closeButton);
        dialog.add(buttons, BorderLayout.SOUTH);

        refresh();

        dialog.setSize(480, 420);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Opens the storage dialog, owned by {@code owner}.
     *
     * @param owner window the dialog is sized/centered against
     * @param catalog the catalog to inspect and manage
     * @param onRestored called after a successful restore, so the caller can
     * reload its view (e.g. {@code OverviewPanel.loadFromCatalog})
     */
    static void open(Window owner, Catalog catalog, Runnable onRestored) {
        new StorageDialog(owner, catalog, onRestored);
    }

    private void refresh() {
        try {
            Catalog.StorageInfo live = catalog.liveStorageInfo();
            liveLabel.setText(String.format("<html>Live catalog: <b>%d</b> entries, <b>%s</b>, at %s</html>",
                    live.entryCount, formatBytes(live.totalBytes), live.location));
            checkpoints = catalog.listCheckpoints();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Could not read storage: " + ex.getMessage(),
                    "Storage read failed", JOptionPane.ERROR_MESSAGE);
            checkpoints = List.of();
        }
        rebuildCheckpointsPanel();
        updateButtonState();
    }

    private void rebuildCheckpointsPanel() {
        checkpointsPanel.removeAll();
        // A fresh group each rebuild, rather than clearSelection() on the
        // old one, since ButtonGroup never forgets a registered button —
        // reusing it would accumulate every past checkpoint's radio button.
        selectionGroup = new ButtonGroup();
        rowSelectors.clear();

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new java.awt.Insets(2, 4, 2, 8);
        c.anchor = GridBagConstraints.WEST;

        c.gridy = 0;
        addHeaderCell("", 0, c);
        addHeaderCell("Taken", 1, c);
        addHeaderCell("Age (~data at risk)", 2, c);
        addHeaderCell("Size", 3, c);

        long now = System.currentTimeMillis();
        int row = 1;
        for (Catalog.CheckpointInfo cp : checkpoints) {
            JRadioButton selector = new JRadioButton();
            selector.addItemListener(new ItemListener() {
                @Override
                public void itemStateChanged(ItemEvent e) {
                    updateButtonState();
                }
            });
            selectionGroup.add(selector);
            rowSelectors.add(selector);

            c.gridy = row;
            c.gridx = 0;
            checkpointsPanel.add(selector, c);
            c.gridx = 1;
            checkpointsPanel.add(new JLabel(formatTimestamp(cp.timestampMillis)), c);
            c.gridx = 2;
            checkpointsPanel.add(new JLabel(formatAge(now - cp.timestampMillis)), c);
            c.gridx = 3;
            checkpointsPanel.add(new JLabel(formatBytes(cp.sizeBytes)), c);
            row++;
        }

        // Absorbs leftover vertical space so rows stay top-aligned instead
        // of spreading out across the scroll pane.
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row;
        filler.weighty = 1;
        checkpointsPanel.add(new JLabel(), filler);

        checkpointsPanel.revalidate();
        checkpointsPanel.repaint();
    }

    private void addHeaderCell(String text, int column, GridBagConstraints template) {
        JLabel header = new JLabel(text);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        GridBagConstraints c = (GridBagConstraints) template.clone();
        c.gridx = column;
        checkpointsPanel.add(header, c);
    }

    private void updateButtonState() {
        boolean hasSelection = selectedIndex() >= 0;
        restoreButton.setEnabled(hasSelection);
        deleteButton.setEnabled(hasSelection);
    }

    private int selectedIndex() {
        for (int i = 0; i < rowSelectors.size(); i++) {
            if (rowSelectors.get(i).isSelected()) {
                return i;
            }
        }
        return -1;
    }

    private void takeCheckpoint() {
        try {
            catalog.checkpoint();
            refresh();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Could not checkpoint: " + ex.getMessage(),
                    "Checkpoint failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restoreSelected() {
        Catalog.CheckpointInfo selected = selectedCheckpoint();
        if (null == selected) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(dialog,
                "Discard everything since " + formatTimestamp(selected.timestampMillis)
                + "?", "Restore checkpoint", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            catalog.restoreCheckpoint(selected.timestampMillis);
            refresh();
            if (null != onRestored) {
                onRestored.run();
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Could not restore checkpoint: " + ex.getMessage(),
                    "Restore failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelected() {
        Catalog.CheckpointInfo selected = selectedCheckpoint();
        if (null == selected) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(dialog,
                "Permanently delete the checkpoint from " + formatTimestamp(selected.timestampMillis)
                + "?", "Delete checkpoint", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            catalog.deleteCheckpoint(selected.timestampMillis);
            refresh();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Could not delete checkpoint: " + ex.getMessage(),
                    "Delete failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Catalog.CheckpointInfo selectedCheckpoint() {
        int index = selectedIndex();
        if (index < 0 || index >= checkpoints.size()) {
            JOptionPane.showMessageDialog(dialog, "Select a checkpoint first.",
                    "Nothing selected", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return checkpoints.get(index);
    }

    private static String formatTimestamp(long epochMillis) {
        return String.format("%tF %<tT", epochMillis);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private static String formatAge(long ageMillis) {
        long minutes = ageMillis / 60_000;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return days + "d " + (hours % 24) + "h ago";
        }
        if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m ago";
        }
        if (minutes > 0) {
            return minutes + "m ago";
        }
        return "just now";
    }
}
