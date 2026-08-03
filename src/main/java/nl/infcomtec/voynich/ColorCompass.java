package nl.infcomtec.voynich;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import javax.swing.*;

/**
 * Interactive polar map of CIELAB colour space, centered on a pickable colour.
 * Distance from the center encodes ΔE (CIE76) reach along six axes (L+/L-,
 * a+/a-, b+/b-); left-click samples a colour into the history strip,
 * right-click re-centers the map on the clicked colour.
 */
public class CielabCompass extends JFrame {

    public void showFrame() {
        if (getWidth() < 500 || getHeight() < 500) {
            // avoid tiny window
            setSize(500, 500);
        }
        if (EventQueue.isDispatchThread()) {
            super.setVisible(true);
        } else {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    CielabCompass.super.setVisible(true);
                }
            });
        }
    }

    /**
     * Opens centered on neutral gray (L*50, a*0, b*0).
     */
    public CielabCompass() {
        this(new EnhancedColor(128, 128, 128));
    }

    /**
     * Opens centered on the CIELAB coordinates of the given colour.
     *
     * @param initial colour to center the compass on.
     */
    public CielabCompass(Color initial) {
        super("CIELAB ΔE Compass");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);

        double[] initialLab = EnhancedColor.getCIELAB(initial);
        CompassPanel compass = new CompassPanel(500, initialLab);
        InfoPanel info = new InfoPanel(compass);
        compass.setInfoPanel(info);

        JPanel root = new JPanel(new BorderLayout(16, 0));
        root.setBackground(new Color(0x1a1a1a));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(compass, BorderLayout.WEST);
        root.add(info, BorderLayout.CENTER);

        setContentPane(root);
        pack();
        setLocationByPlatform(true);
    }

    /**
     * Lab→XYZ→linear-sRGB, kept local and unclamped rather than reusing
     * {@link EnhancedColor#fromCIELAB}: that method deliberately clips each
     * channel to a displayable value, so every out-of-gamut coordinate in a
     * region collapses to whatever primary it clips to. This tool instead
     * wants out-of-gamut pixels to stay visually distinguishable — dimmed by
     * how far and in which direction they overshoot — which needs the raw
     * value before clamping.
     *
     * @return [r, g, bl] linear-light (pre-gamma), unclamped.
     */
    private static double[] rawLinearRGB(double L, double a, double b) {
        double y = (L + 16) / 116;
        double x = a / 500 + y;
        double z = y - b / 200;
        double X = 95.047 * ((x > 0.206897) ? x * x * x : (x - 16.0 / 116) / 7.787);
        double Y = 100.000 * ((y > 0.206897) ? y * y * y : (y - 16.0 / 116) / 7.787);
        double Z = 108.883 * ((z > 0.206897) ? z * z * z : (z - 16.0 / 116) / 7.787);
        double xn = X / 100, yn = Y / 100, zn = Z / 100;
        double r = 3.2406 * xn - 1.5372 * yn - 0.4986 * zn;
        double g = -0.9689 * xn + 1.8758 * yn + 0.0415 * zn;
        double bl = 0.0557 * xn - 0.2040 * yn + 1.0570 * zn;
        return new double[]{r, g, bl};
    }

    private static double gammaEncode(double linear) {
        return (linear <= 0.0031308) ? 12.92 * linear : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
    }

    /**
     * Slack applied when testing {@code rawLinearRGB} against the [0,1]
     * gamut boundary. The Lab→XYZ→RGB round trip (cube roots, powers, a 3x3
     * matrix) accumulates enough floating-point error that an exact sRGB
     * primary — e.g. pure magenta, sitting right on the gamut surface — can
     * come back a few 1e-6 outside [0,1] and be wrongly rejected as
     * unrepresentable without this tolerance.
     */
    private static final double GAMUT_EPSILON = 1e-4;

    private static boolean inGamut(double[] linearRgb) {
        for (double c : linearRgb) {
            if (c < -GAMUT_EPSILON || c > 1 + GAMUT_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /**
     * ARGB for a CIELAB coordinate. Out-of-gamut coordinates are dimmed and
     * made translucent rather than hard-clamped, so the disc still shows
     * where the true colour would lie.
     */
    static int labToArgb(double L, double a, double b, boolean dimOutOfGamut) {
        double[] linear = rawLinearRGB(L, a, b);
        boolean inGamut = inGamut(linear);
        double factor = (!inGamut && dimOutOfGamut) ? 0.3 : 1.0;
        int ri = (int) Math.min(255, Math.max(0, gammaEncode(linear[0]) * 255 * factor));
        int gi = (int) Math.min(255, Math.max(0, gammaEncode(linear[1]) * 255 * factor));
        int bi = (int) Math.min(255, Math.max(0, gammaEncode(linear[2]) * 255 * factor));
        int alpha = (!inGamut && dimOutOfGamut) ? 180 : 255;
        return (alpha << 24) | (ri << 16) | (gi << 8) | bi;
    }

    // ── Axis definitions ─────────────────────────────────────────────────────
    // Each row: {angle in degrees, dL, da, db} — the CIELAB direction reached
    // when the mouse is at that angle from the center.
    static final double[][] AXES = {
        {90, 1, 0, 0},
        {270, -1, 0, 0},
        {30, 0, 1, 0},
        {210, 0, -1, 0},
        {330, 0, 0, 1},
        {150, 0, 0, -1},};
    static final Color[] AXIS_COLORS = {
        new Color(0xff, 0xff, 0xff), new Color(0x44, 0x44, 0x44),
        new Color(0xff, 0x44, 0x66), new Color(0x44, 0xcc, 0x44),
        new Color(0xff, 0xdd, 0x00), new Color(0x44, 0x88, 0xff),};
    static final String[] AXIS_LABELS = {"L+", "L−", "a+", "a−", "b+", "b−"};

    /**
     * Renders the polar CIELAB disc and turns mouse position/clicks into
     * CIELAB coordinates for the linked {@link InfoPanel}.
     */
    static class CompassPanel extends JPanel {

        final int SIZE;
        final int CX, CY, R;
        double[] center;
        int deReach = 30;
        double[] hover = null;
        InfoPanel info;
        BufferedImage compassImage;

        CompassPanel(int size, double[] initialLab) {
            SIZE = size;
            CX = CY = SIZE / 2;
            R = SIZE / 2 - 2;
            center = initialLab.clone();
            setPreferredSize(new Dimension(SIZE, SIZE));
            setBackground(new Color(0x1a1a1a));
            rebuildImage();

            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    hover = pixelToLab(e.getX(), e.getY());
                    if (hover != null && info != null) {
                        info.update(hover, false);
                    }
                    repaint();
                }
            });
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    double[] lab = pixelToLab(e.getX(), e.getY());
                    if (lab == null) {
                        return;
                    }
                    if (SwingUtilities.isRightMouseButton(e)) {
                        center = lab;
                        rebuildImage();
                        if (info != null) {
                            info.update(lab, false);
                        }
                    } else {
                        if (info != null) {
                            info.pick(lab);
                        }
                    }
                    repaint();
                }
            });
        }

        void setInfoPanel(InfoPanel p) {
            this.info = p;
        }

        void setDeReach(int v) {
            deReach = v;
            rebuildImage();
            repaint();
        }

        void rebuildImage() {
            compassImage = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            int[] raster = new int[SIZE * SIZE];
            for (int py = 0; py < SIZE; py++) {
                for (int px = 0; px < SIZE; px++) {
                    double dx = px - CX, dy = py - CY;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist > R) {
                        raster[py * SIZE + px] = 0x00000000;
                        continue;
                    }
                    double t = dist / R;
                    double reach = t * deReach;
                    double angle = Math.toDegrees(Math.atan2(-dy, dx));
                    if (angle < 0) {
                        angle += 360;
                    }
                    double[] w = axisWeights(angle);
                    double L = center[0], a = center[1], b = center[2];
                    for (int i = 0; i < AXES.length; i++) {
                        L += reach * AXES[i][1] * w[i];
                        a += reach * AXES[i][2] * w[i];
                        b += reach * AXES[i][3] * w[i];
                    }
                    L = Math.max(0, Math.min(100, L));
                    a = Math.max(-128, Math.min(128, a));
                    b = Math.max(-128, Math.min(128, b));
                    raster[py * SIZE + px] = labToArgb(L, a, b, true);
                }
            }
            compassImage.setRGB(0, 0, SIZE, SIZE, raster, 0, SIZE);
        }

        double[] axisWeights(double angle) {
            double[] diffs = new double[AXES.length];
            double sum = 0;
            double min1 = Double.MAX_VALUE, min2 = Double.MAX_VALUE;
            for (int i = 0; i < AXES.length; i++) {
                double d = Math.abs(angle - AXES[i][0]);
                if (d > 180) {
                    d = 360 - d;
                }
                diffs[i] = d;
                if (d < min1) {
                    min2 = min1;
                    min1 = d;
                } else if (d < min2) {
                    min2 = d;
                }
            }
            // only two nearest axes get weight
            double[] w = new double[AXES.length];
            for (int i = 0; i < AXES.length; i++) {
                if (diffs[i] == min1 || diffs[i] == min2) {
                    w[i] = 1.0 / (diffs[i] + 1e-9);
                    sum += w[i];
                }
            }
            for (int i = 0; i < AXES.length; i++) {
                w[i] /= sum;
            }
            return w;
        }

        double[] pixelToLab(int px, int py) {
            double dx = px - CX, dy = py - CY;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist > R) {
                return null;
            }
            double t = dist / R;
            double reach = t * deReach;
            double angle = Math.toDegrees(Math.atan2(-dy, dx));
            if (angle < 0) {
                angle += 360;
            }
            double[] w = axisWeights(angle);
            double L = center[0], a = center[1], b = center[2];
            for (int i = 0; i < AXES.length; i++) {
                L += reach * AXES[i][1] * w[i];
                a += reach * AXES[i][2] * w[i];
                b += reach * AXES[i][3] * w[i];
            }
            return new double[]{
                Math.max(0, Math.min(100, L)),
                Math.max(-128, Math.min(128, a)),
                Math.max(-128, Math.min(128, b))
            };
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // clip to circle
            g2.setClip(new java.awt.geom.Ellipse2D.Double(CX - R, CY - R, R * 2, R * 2));
            g2.drawImage(compassImage, 0, 0, null);
            g2.setClip(null);

            // axis lines
            for (int i = 0; i < AXES.length; i++) {
                double rad = Math.toRadians(AXES[i][0]);
                int ex = (int) (CX + Math.cos(rad) * R);
                int ey = (int) (CY - Math.sin(rad) * R);
                g2.setColor(new Color(AXIS_COLORS[i].getRed(), AXIS_COLORS[i].getGreen(),
                        AXIS_COLORS[i].getBlue(), 50));
                g2.drawLine(CX, CY, ex, ey);
                // label
                int lx = (int) (CX + Math.cos(rad) * (R - 20));
                int ly = (int) (CY - Math.sin(rad) * (R - 20));
                g2.setColor(new Color(AXIS_COLORS[i].getRed(), AXIS_COLORS[i].getGreen(),
                        AXIS_COLORS[i].getBlue(), 160));
                g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                g2.drawString(AXIS_LABELS[i], lx - 8, ly + 4);
            }

            // border
            g2.setColor(new Color(0x333333));
            g2.setStroke(new BasicStroke(2));
            g2.drawOval(CX - R, CY - R, R * 2, R * 2);

            // center dot
            g2.setColor(Color.WHITE);
            g2.fillOval(CX - 5, CY - 5, 10, 10);
            EnhancedColor cRgb = EnhancedColor.fromCIELAB(center[0], center[1], center[2]);
            g2.setColor(cRgb);
            g2.fillOval(CX - 3, CY - 3, 6, 6);
        }
    }

    /**
     * Swatch, coordinate readout and pick history alongside the compass disc.
     */
    static class InfoPanel extends JPanel {

        final CompassPanel compass;
        final JPanel swatch = new JPanel();
        final JLabel lblLab = new JLabel("L* 50  a* 0  b* 0");
        final JLabel lblHex = new JLabel("#808080");
        final JLabel lblDE = new JLabel("ΔE reach: 30");
        final JSlider slider = new JSlider(5, 80, 30);
        final JPanel history = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        final ArrayList<double[]> picks = new ArrayList<>();

        InfoPanel(CompassPanel compass) {
            this.compass = compass;
            setBackground(new Color(0x1a1a1a));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

            swatch.setPreferredSize(new Dimension(220, 70));
            swatch.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
            swatch.setBackground(new Color(0x808080));
            swatch.setBorder(BorderFactory.createLineBorder(new Color(0x333333)));

            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
            lblLab.setForeground(new Color(0xaaaaaa));
            lblLab.setFont(mono);
            lblHex.setForeground(Color.WHITE);
            lblHex.setFont(mono);
            lblDE.setForeground(new Color(0xaaaaaa));
            lblDE.setFont(mono);

            slider.setBackground(new Color(0x1a1a1a));
            slider.setForeground(new Color(0x888888));

            JLabel lblReach = small("ΔE REACH");
            JLabel lblHist = small("HISTORY (click=center)");
            JLabel lblHint = small("LEFT CLICK=pick  RIGHT CLICK=set center");

            history.setBackground(new Color(0x1a1a1a));
            history.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

            slider.addChangeListener(e -> {
                compass.setDeReach(slider.getValue());
                lblDE.setText("ΔE reach: " + slider.getValue());
            });

            add(swatch);
            add(Box.createVerticalStrut(8));
            add(lblLab);
            add(lblHex);
            add(lblDE);
            add(Box.createVerticalStrut(4));
            add(lblReach);
            add(slider);
            add(Box.createVerticalStrut(8));
            add(axisLegend());
            add(Box.createVerticalStrut(8));
            add(lblHist);
            add(history);
            add(Box.createVerticalGlue());
            add(lblHint);

            update(compass.center, false);
        }

        JLabel small(String t) {
            JLabel l = new JLabel(t);
            l.setForeground(new Color(0x555555));
            l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            return l;
        }

        JPanel axisLegend() {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(new Color(0x1a1a1a));
            String[] desc = {"↑ L+ lighter", "↓ L− darker", "↗ a+ red", "↙ a− green", "↘ b+ yellow", "↖ b− blue"};
            for (int i = 0; i < desc.length; i++) {
                JLabel l = new JLabel(desc[i]);
                l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                l.setForeground(AXIS_COLORS[i]);
                p.add(l);
            }
            return p;
        }

        void update(double[] lab, boolean picked) {
            EnhancedColor rgb = EnhancedColor.fromCIELAB(lab[0], lab[1], lab[2]);
            swatch.setBackground(rgb);
            lblLab.setText(String.format("L* %.1f  a* %.1f  b* %.1f", lab[0], lab[1], lab[2]));
            lblHex.setText(String.format("#%02x%02x%02x", rgb.getRed(), rgb.getGreen(), rgb.getBlue()));
        }

        void pick(double[] lab) {
            if (!inGamut(rawLinearRGB(lab[0], lab[1], lab[2]))) {
                return; // out of gamut, skip
            }
            update(lab, true);
            picks.add(0, lab);
            if (picks.size() > 20) {
                picks.remove(picks.size() - 1);
            }
            rebuildHistory();
        }

        void rebuildHistory() {
            history.removeAll();
            for (int i = 0; i < picks.size(); i++) {
                double[] lab = picks.get(i);
                EnhancedColor rgb = EnhancedColor.fromCIELAB(lab[0], lab[1], lab[2]);
                JPanel chip = new JPanel();
                chip.setPreferredSize(new Dimension(28, 28));
                chip.setBackground(rgb);
                chip.setBorder(BorderFactory.createLineBorder(new Color(0x333333)));
                chip.setToolTipText(String.format("L*%.1f a*%.1f b*%.1f  #%02x%02x%02x  [click=center]",
                        lab[0], lab[1], lab[2], rgb.getRed(), rgb.getGreen(), rgb.getBlue()));
                final double[] labRef = lab;
                chip.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        compass.center = labRef.clone();
                        compass.rebuildImage();
                        compass.repaint();
                        update(labRef, false);
                    }
                });
                history.add(chip);
            }
            history.revalidate();
            history.repaint();
        }
    }
}
