/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * A wordless Cylon/KITT-style scanner bar meant to sit at the trailing end
 * of the app's {@link javax.swing.JMenuBar}: idle and blank whenever
 * nothing is running, animating a bouncing highlight — a bright lead LED
 * with a dimming trail behind it, direction-aware, like the genuine
 * article — only while at least one background action ({@link RegionView}'s
 * {@code SwingWorker}s) is in flight. Deliberately not a busy mouse cursor
 * or a modal progress dialog — see the "no spinning wait cursors"
 * convention this app already follows elsewhere. {@link #enter()}/
 * {@link #exit()} must be paired by every caller, success or failure alike.
 */
final class BusyIndicator extends JComponent {

    private static final int TICK_MS = 90;
    private static final int LED_COUNT = 12;
    private static final int TRAIL_LENGTH = 4;

    private final int width;
    private final int ledSize;
    private final int gap;
    private int busyCount;
    private int leadIndex;
    private int direction = 1;
    private final Timer timer;

    /**
     * @param height the height to render at, in pixels — pass a sibling
     * top-level {@link javax.swing.JMenu}'s own
     * {@link JComponent#getPreferredSize()} height so this scales with
     * whatever font/DPI the menu bar itself is already using, rather than
     * a fixed pixel constant that reads as a sliver on a 4K display.
     */
    BusyIndicator(int height) {
        ledSize = Math.max(3, height / 3);
        gap = Math.max(1, ledSize / 3);
        width = LED_COUNT * ledSize + (LED_COUNT - 1) * gap;
        setPreferredSize(new Dimension(width, height));
        timer = new Timer(TICK_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                advance();
            }
        });
    }

    private void advance() {
        leadIndex += direction;
        if (leadIndex >= LED_COUNT - 1) {
            leadIndex = LED_COUNT - 1;
            direction = -1;
        } else if (leadIndex <= 0) {
            leadIndex = 0;
            direction = 1;
        }
        repaint();
    }

    /** Marks one more background action as running; starts the animation if it wasn't already. */
    void enter() {
        busyCount++;
        if (1 == busyCount) {
            timer.start();
        }
    }

    /** Marks one background action as finished; stops the animation once nothing else is running. */
    void exit() {
        busyCount--;
        if (0 == busyCount) {
            timer.stop();
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (0 == busyCount) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g;
        int y = (getHeight() - ledSize) / 2;
        for (int i = 0; i < LED_COUNT; i++) {
            // The trail sits behind the lead relative to travel direction: moving right
            // (direction > 0), the trail is to the lead's left, and vice versa — same
            // as the genuine KITT scanner, not a symmetric glow on both sides.
            int distanceBehind = direction > 0 ? leadIndex - i : i - leadIndex;
            double brightness;
            if (0 == distanceBehind) {
                brightness = 1.0;
            } else if (distanceBehind > 0 && distanceBehind <= TRAIL_LENGTH) {
                brightness = 1.0 - (double) distanceBehind / (TRAIL_LENGTH + 1);
            } else {
                continue;
            }
            int red = (int) Math.round(60 + brightness * 195);
            g2.setColor(new Color(red, 0, 0));
            int x = i * (ledSize + gap);
            g2.fillRoundRect(x, y, ledSize, ledSize, ledSize / 2, ledSize / 2);
        }
    }
}
