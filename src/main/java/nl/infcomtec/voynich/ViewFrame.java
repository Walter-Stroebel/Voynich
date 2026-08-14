/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JComponent;
import javax.swing.JFrame;

/**
 * Opens a named, independent {@link JFrame} around one visualization
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
 * Deliberately a plain, ownerless {@link JFrame}, not a {@code Dialog} owned
 * by the main app window: windows don't own windows here. An AWT owner only
 * hands the window manager a {@code WM_TRANSIENT_FOR} hint, which the window
 * manager is then free to act on by re-stacking this window relative to its
 * "owner" — that re-stacking behavior, not the absence of it, is what
 * caused this window to misbehave. Every window this app opens is a peer;
 * if one gets buried under another, that's what alt-tab/the taskbar is for,
 * same as for any other pair of unrelated application windows on the
 * desktop.
 * </p>
 */
final class ViewFrame {

    private ViewFrame() {
    }

    /**
     * @param name stable key into {@link Config#viewBounds}; also the frame
     * title
     * @param nearWindow used only to pick a default {@link GraphicsDevice}
     * (and, when unresizable, a location) the first time this view opens
     * with no usable saved bounds — not an AWT owner, and this window's
     * lifetime isn't tied to it. May be {@code null}, which falls back to
     * the platform's default screen.
     * @param content the visualization to show
     * @param resizable whether the user may resize the window; when
     * {@code false} only its position is remembered, since {@code content}'s
     * preferred size already determines a deterministic size on every open
     * @param maximizeInitially when there's no usable saved size yet, open
     * maximized ({@link JFrame#MAXIMIZED_BOTH}) instead of packing to
     * {@code content}'s preferred size. Ignored once a saved size exists; a
     * resize the user makes afterward (including un-maximizing) is
     * remembered like any other view.
     */
    static void open(String name, Window nearWindow, JComponent content, boolean resizable, boolean maximizeInitially) {
        JFrame view = new JFrame(name);
        view.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        view.setResizable(resizable);
        view.add(content);

        Config.Bounds saved = Voynich.config.viewBounds.get(name);
        boolean savedUsable = null != saved && onAnyScreen(saved);
        if (resizable && savedUsable) {
            view.setBounds(saved.x, saved.y, saved.width, saved.height);
        } else if (resizable && maximizeInitially) {
            view.setBounds(usableBounds(defaultDevice(nearWindow)));
            view.setExtendedState(JFrame.MAXIMIZED_BOTH);
        } else {
            view.pack();
            if (savedUsable) {
                view.setLocation(saved.x, saved.y);
            } else if (null != nearWindow) {
                view.setLocationRelativeTo(nearWindow);
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

    static GraphicsDevice defaultDevice(Window nearWindow) {
        return null != nearWindow ? nearWindow.getGraphicsConfiguration().getDevice()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    }

    /**
     * @return {@code device}'s bounds minus screen insets (taskbars, panels)
     * — the same area a native maximize would fill. Package-visible so the
     * Thumbnail Matrix fit check ({@link Voynich}) can reuse it without
     * duplicating the insets math.
     */
    static Rectangle usableBounds(GraphicsDevice device) {
        Rectangle screen = device.getDefaultConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(device.getDefaultConfiguration());
        return new Rectangle(screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom);
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

    private static void persist(String name, JFrame view) {
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
