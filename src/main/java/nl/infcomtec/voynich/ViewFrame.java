/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JComponent;
import javax.swing.JDialog;

/**
 * Opens a named, non-modal {@link JDialog} around one visualization
 * {@link JComponent}, remembering that name's last on-screen bounds in
 * {@link Config#viewBounds} across restarts.
 * <p>
 * "Named" means per view <em>type</em> (e.g. "Color Frequency"), not per
 * image — opening the same visualization for a different
 * {@link CatalogEntry} reopens at the same place, which is the point: these
 * are tool windows a researcher parks on a second or third monitor and
 * reuses, not one-off popups.
 * </p>
 * <p>
 * Deliberately a {@link JDialog}, not a bare {@link javax.swing.JFrame}:
 * only {@code Dialog}/{@code Window} can declare an owner in AWT, and an
 * owner is what lets the window manager get a correct
 * {@code WM_TRANSIENT_FOR} hint for this window's place in the app's
 * stacking order — without it, window managers are left guessing, which
 * showed up as this window randomly dropping behind unrelated windows.
 * {@code owner} should be the long-lived main application window, not
 * whatever transient dialog happened to spawn this view (see
 * {@link CatalogEntryEditor}, which passes its own dialog's owner rather
 * than itself), so a view's lifetime isn't tied to that dialog closing.
 * </p>
 */
final class ViewFrame {

    private ViewFrame() {
    }

    /**
     * @param name stable key into {@link Config#viewBounds}; also the dialog
     * title
     * @param owner the long-lived window this visualization should be owned
     * by for correct window-manager stacking; may be {@code null}, which
     * still works (an unowned dialog), just without that stacking hint
     * @param content the visualization to show
     * @param resizable whether the user may resize the window; when
     * {@code false} only its position is remembered, since {@code content}'s
     * preferred size already determines a deterministic size on every open
     */
    static void open(String name, Window owner, JComponent content, boolean resizable) {
        JDialog view = new JDialog(owner, name, JDialog.ModalityType.MODELESS);
        view.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        view.setResizable(resizable);
        view.add(content);

        Config.Bounds saved = Voynich.config.viewBounds.get(name);
        boolean savedUsable = null != saved && onAnyScreen(saved);
        if (resizable && savedUsable) {
            view.setBounds(saved.x, saved.y, saved.width, saved.height);
        } else {
            view.pack();
            if (savedUsable) {
                view.setLocation(saved.x, saved.y);
            } else if (null != owner) {
                view.setLocationRelativeTo(owner);
            }
        }

        view.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                persist(name, view);
            }

            @Override
            public void componentResized(ComponentEvent e) {
                persist(name, view);
            }
        });

        view.setVisible(true);
    }

    /**
     * @return {@code true} if {@code b} intersects at least one currently
     * connected {@link GraphicsDevice}'s bounds — false after a monitor has
     * been unplugged or the display layout changed since the bounds were
     * saved, so a stale rectangle doesn't strand the window off-screen
     */
    private static boolean onAnyScreen(Config.Bounds b) {
        Rectangle r = new Rectangle(b.x, b.y, Math.max(1, b.width), Math.max(1, b.height));
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().intersects(r)) {
                return true;
            }
        }
        return false;
    }

    private static void persist(String name, JDialog view) {
        Rectangle b = view.getBounds();
        Config.Bounds bounds = new Config.Bounds();
        bounds.x = b.x;
        bounds.y = b.y;
        bounds.width = b.width;
        bounds.height = b.height;
        Voynich.config.viewBounds.put(name, bounds);
        Voynich.saveConfig();
    }
}
