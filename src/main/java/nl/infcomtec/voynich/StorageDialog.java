/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.Window;
import java.io.IOException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

/**
 * Replaces the old opaque Checkpoint/Undo toolbar buttons with a visible
 * view of what's actually on disk: the live catalog's size, and every
 * checkpoint's age and size, with take/restore/delete actions. Modeless,
 * matching {@link CatalogEntryEditor}'s and {@link ViewFrame}'s
 * independent-window convention — see {@code Voynich}'s "Windows don't own
 * windows" reasoning.
 */
final class StorageDialog {

    private final JDialog dialog;
    private final Catalog catalog;
    private final Runnable onRestored;
    private final JLabel liveLabel = new JLabel(" ", SwingConstants.CENTER);
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Taken (ISO local time)", "Age (~what you'd lose)", "Size"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JButton restoreButton = new JButton("Restore Selected");
    private final JButton deleteButton = new JButton("Delete Selected");
    private List<Catalog.CheckpointInfo> checkpoints = List.of();

    private StorageDialog(Window owner, Catalog catalog, Runnable onRestored) {
        this.catalog = catalog;
        this.onRestored = onRestored;

        dialog = new JDialog(owner, "Storage", JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());

        liveLabel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        dialog.add(liveLabel, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getSelectionModel().addListSelectionListener(e -> updateButtonState());
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);

        JButton takeButton = new JButton("Take Checkpoint Now");
        JButton closeButton = new JButton("Close");
        takeButton.addActionListener(e -> takeCheckpoint());
        restoreButton.addActionListener(e -> restoreSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        closeButton.addActionListener(e -> dialog.dispose());
        updateButtonState();
        JPanel buttons = new JPanel();
        buttons.add(takeButton);
        buttons.add(restoreButton);
        buttons.add(deleteButton);
        buttons.add(closeButton);
        dialog.add(buttons, BorderLayout.SOUTH);

        refresh();

        dialog.setSize(640, 420);
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
        model.setRowCount(0);
        long now = System.currentTimeMillis();
        for (Catalog.CheckpointInfo cp : checkpoints) {
            model.addRow(new Object[]{
                formatTimestamp(cp.timestampMillis),
                formatAge(now - cp.timestampMillis),
                formatBytes(cp.sizeBytes)
            });
        }
        table.clearSelection();
        updateButtonState();
    }

    private void updateButtonState() {
        boolean hasSelection = table.getSelectedRow() >= 0;
        restoreButton.setEnabled(hasSelection);
        deleteButton.setEnabled(hasSelection);
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
        int row = table.getSelectedRow();
        if (row < 0 || row >= checkpoints.size()) {
            JOptionPane.showMessageDialog(dialog, "Select a checkpoint first.",
                    "Nothing selected", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return checkpoints.get(row);
    }

    private static String formatTimestamp(long epochMillis) {
        return String.format("%tFT%<tT", epochMillis);
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
