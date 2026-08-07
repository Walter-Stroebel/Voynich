/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * A small modal {@code kind}/{@code author} prompt — every {@code Region}
 * kind/author entry point ({@link RegionManagerDialog}'s Add/Rename,
 * {@link RegionViewer}'s Add Child) used to build this out of
 * {@link javax.swing.JOptionPane#showConfirmDialog}, which always hands
 * initial keyboard focus to its default button rather than the message
 * panel's first field. That's now moot: after extended live testing, an
 * editable {@link JComboBox} under this app's actual dialog stack
 * (FlatDarculaLaf, launched from a nested action rather than a bare test
 * frame) simply would not commit freshly typed text that wasn't already in
 * its item list — no combination of reading {@code getSelectedItem()},
 * {@code getEditor().getItem()}, or the editor's raw {@code JTextField}
 * directly, nor removing the dialog's default button, fixed it, and a
 * minimal isolated reproduction never diverged from a plain non-editable
 * combo's behavior either. Rather than keep chasing an editable combo's
 * commit semantics, the {@code kind} field is a plain non-editable
 * {@link JComboBox} of existing kinds plus a separate "or new kind:"
 * {@link JTextField} — non-blank always wins. A JTextField's own
 * {@code getText()} has no commit-timing question to get wrong.
 */
final class KindAuthorPrompt {

    private KindAuthorPrompt() {
    }

    /**
     * @param owner window this prompt is centered on
     * @param title dialog title
     * @param kinds every distinct kind already used across the catalog,
     * offered by the non-editable combo — see {@link RegionManagerDialog#distinctKinds}
     * @param initialKind pre-selected kind, or {@code ""} for none
     * @param initialAuthor pre-filled author, or {@code ""} for none
     * @return the entered kind/author, or {@code null} if cancelled
     */
    static Result prompt(Window owner, String title, String[] kinds, String initialKind, String initialAuthor) {
        JDialog dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JComboBox<String> kindBox = new JComboBox<>(kinds);
        if (!initialKind.isEmpty()) {
            kindBox.setSelectedItem(initialKind);
        }
        JTextField newKindField = new JTextField(24);
        JTextField authorField = new JTextField(initialAuthor, 24);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        c.gridy = 0;
        fields.add(new JLabel("Kind:"), c);
        c.gridy = 1;
        fields.add(kindBox, c);
        c.gridy = 2;
        fields.add(new JLabel("...or a new kind not listed above:"), c);
        c.gridy = 3;
        fields.add(newKindField, c);
        c.gridy = 4;
        fields.add(new JLabel("Author (blank = unspecified):"), c);
        c.gridy = 5;
        fields.add(authorField, c);

        final Result[] outcome = new Result[1];
        JButton ok = new JButton(new EzAction("OK") {
            @Override
            public void actionPerformed(ActionEvent e) {
                String typed = newKindField.getText().trim();
                Object selected = kindBox.getSelectedItem();
                String kind = !typed.isEmpty() ? typed : null == selected ? "" : selected.toString();
                outcome[0] = new Result(kind, authorField.getText().trim());
                dialog.dispose();
            }
        });
        JButton cancel = new JButton(new EzAction("Cancel") {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });
        JPanel buttons = new JPanel();
        buttons.add(ok);
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(fields, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);

        dialog.pack();
        // pack() alone has left the button row clipped below the visible
        // area in practice — belt-and-suspenders a minimum size so OK/Cancel
        // are always reachable without a manual resize.
        dialog.setMinimumSize(new java.awt.Dimension(360, dialog.getPreferredSize().height + 20));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return outcome[0];
    }

    /**
     * A confirmed {@link #prompt}'s result.
     */
    static final class Result {

        final String kind;
        final String author;

        Result(String kind, String author) {
            this.kind = kind;
            this.author = author;
        }
    }
}
