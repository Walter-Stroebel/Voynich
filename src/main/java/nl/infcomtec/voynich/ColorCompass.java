package nl.infcomtec.voynich;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import javax.swing.*;

/**
 * Interactive polar map of a colour space, centered on a pickable colour.
 * Three interchangeable spaces are supported (CIELAB, YUV, RGB); distance
 * from the center encodes reach along six axes, two per native channel
 * (channel+/channel-). Left-click samples a colour into the history strip,
 * right-click re-centers the map on the clicked colour, and — if the
 * compass was opened on two colours — a small ring/dot marks the second
 * colour's position relative to the current center. Points that fall
 * outside the sRGB gamut render as solid black rather than a dimmed colour:
 * there is nothing displayable there.
 */
public class ColorCompass extends JFrame {

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
                    ColorCompass.super.setVisible(true);
                }
            });
        }
    }

    /**
     * Opens centered on neutral gray (L*50, a*0, b*0).
     */
    public ColorCompass() {
        this(new EnhancedColor(128, 128, 128), null);
    }

    /**
     * Opens centered on the given colour.
     *
     * @param initial colour to center the compass on.
     */
    public ColorCompass(Color initial) {
        this(initial, null);
    }

    /**
     * Opens centered on {@code initial}, with {@code second} marked on the
     * disc. The ΔE/reach slider is auto-scaled so {@code second} lands at
     * 2/3 of the disc radius, on whichever axis direction its delta from
     * {@code initial} leans toward.
     *
     * @param initial colour to center the compass on.
     * @param second colour to mark, or {@code null} for none.
     */
    public ColorCompass(Color initial, Color second) {
        super("Colour Compass");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);

        CompassPanel compass = new CompassPanel(500, initial);
        InfoPanel info = new InfoPanel(compass);
        compass.setInfoPanel(info);
        if (second != null) {
            compass.setSecondColor(second);
        }

        JPanel root = new JPanel(new BorderLayout(16, 0));
        root.setBackground(new Color(0x1a1a1a));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(compass, BorderLayout.WEST);
        root.add(info, BorderLayout.CENTER);

        setContentPane(root);
        pack();
        setLocationByPlatform(true);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static final int BLACK_ARGB = 0xff000000;

    private static int argbOpaque(double r, double g, double b) {
        int ri = (int) clamp(Math.round(r), 0, 255);
        int gi = (int) clamp(Math.round(g), 0, 255);
        int bi = (int) clamp(Math.round(b), 0, 255);
        return 0xff000000 | (ri << 16) | (gi << 8) | bi;
    }

    /**
     * Lab→XYZ→linear-sRGB, kept local and unclamped rather than reusing
     * {@link EnhancedColor#fromCIELAB}: that method deliberately clips each
     * channel to a displayable value, which loses exactly the information
     * this tool needs to tell in-gamut from out-of-gamut.
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
     * Same idea as {@link #GAMUT_EPSILON}, scaled for the 0..255 range that
     * the YUV and RGB conversions (simple linear algebra, far less
     * round-trip error than Lab's cube roots) work in.
     */
    private static final double GAMUT_EPSILON_255 = 0.05;

    private static boolean inGamut255(double[] rgb) {
        for (double c : rgb) {
            if (c < -GAMUT_EPSILON_255 || c > 255 + GAMUT_EPSILON_255) {
                return false;
            }
        }
        return true;
    }

    /**
     * YUV→RGB, kept local and unclamped for the same reason as
     * {@link #rawLinearRGB}: {@link YUV#from()} clamps.
     *
     * @return [r, g, b] in the 0..255 scale, unclamped.
     */
    private static double[] rawRgbFromYUV(double Y, double U, double V) {
        double y = Y - 16, u = U - 128, v = V - 128;
        double r = 1.164 * y + 1.596 * v;
        double g = 1.164 * y - 0.392 * u - 0.813 * v;
        double b = 1.164 * y + 2.017 * u;
        return new double[]{r, g, b};
    }

    // ── Axis definitions ─────────────────────────────────────────────────────
    // Each row: {angle in degrees, w1, w2, w3} — the direction in the active
    // Space's native channels reached when the mouse is at that angle from
    // the center. Shared across all three spaces: only the channels' meaning
    // (and their labels/colours, see Space) changes between modes.
    static final double[][] AXES = {
        {90, 1, 0, 0},
        {270, -1, 0, 0},
        {30, 0, 1, 0},
        {210, 0, -1, 0},
        {330, 0, 0, 1},
        {150, 0, 0, -1},};

    /**
     * Finds the on-disc angle that best represents an arbitrary native-space
     * delta. Most deltas activate two adjacent axes (a delta with all three
     * channels nonzero has no exact position on the disc, since the disc's
     * generative mapping only ever blends two of the six rays); this
     * resolves that by summing each axis's positive contribution as a 2D
     * vector and taking the resultant direction.
     */
    private static double angleForDelta(double[] delta) {
        double vx = 0, vy = 0;
        for (double[] axis : AXES) {
            double activation = Math.max(0, delta[0] * axis[1] + delta[1] * axis[2] + delta[2] * axis[3]);
            double rad = Math.toRadians(axis[0]);
            vx += activation * Math.cos(rad);
            vy += activation * Math.sin(rad);
        }
        if (vx == 0 && vy == 0) {
            return 0;
        }
        double deg = Math.toDegrees(Math.atan2(vy, vx));
        return deg < 0 ? deg + 360 : deg;
    }

    /**
     * A colour space the compass can display: conversion to/from
     * {@link Color}, gamut test, and the axis vocabulary (labels/colours)
     * for that space's three native channels.
     */
    enum Space {

        LAB("ΔE reach") {
            @Override
            double[] fromColor(Color c) {
                return EnhancedColor.getCIELAB(c);
            }

            @Override
            double[] clampDomain(double[] v) {
                return new double[]{clamp(v[0], 0, 100), clamp(v[1], -128, 128), clamp(v[2], -128, 128)};
            }

            @Override
            boolean inGamut(double[] v) {
                return ColorCompass.inGamut(rawLinearRGB(v[0], v[1], v[2]));
            }

            @Override
            int toArgb(double[] v) {
                double[] linear = rawLinearRGB(v[0], v[1], v[2]);
                if (!ColorCompass.inGamut(linear)) {
                    return BLACK_ARGB;
                }
                return argbOpaque(gammaEncode(linear[0]) * 255, gammaEncode(linear[1]) * 255, gammaEncode(linear[2]) * 255);
            }

            @Override
            Color toDisplayColor(double[] v) {
                return EnhancedColor.fromCIELAB(v[0], v[1], v[2]);
            }

            @Override
            String format(double[] v) {
                return String.format("L* %.1f  a* %.1f  b* %.1f", v[0], v[1], v[2]);
            }

            @Override
            String[] axisLabels() {
                return new String[]{"L+", "L−", "a+", "a−", "b+", "b−"};
            }

            @Override
            Color[] axisColors() {
                return new Color[]{
                    new Color(0xff, 0xff, 0xff), new Color(0x44, 0x44, 0x44),
                    new Color(0xff, 0x44, 0x66), new Color(0x44, 0xcc, 0x44),
                    new Color(0xff, 0xdd, 0x00), new Color(0x44, 0x88, 0xff)
                };
            }

            @Override
            String[] axisLegend() {
                return new String[]{"↑ L+ lighter", "↓ L− darker", "↗ a+ red",
                    "↙ a− green", "↘ b+ yellow", "↖ b− blue"};
            }
        },
        YUV("YUV reach") {
            @Override
            double[] fromColor(Color c) {
                YUV yuv = new YUV(c);
                return new double[]{yuv.Y, yuv.U, yuv.V};
            }

            @Override
            double[] clampDomain(double[] v) {
                return new double[]{clamp(v[0], 0, 255), clamp(v[1], 0, 255), clamp(v[2], 0, 255)};
            }

            @Override
            boolean inGamut(double[] v) {
                return inGamut255(rawRgbFromYUV(v[0], v[1], v[2]));
            }

            @Override
            int toArgb(double[] v) {
                double[] rgb = rawRgbFromYUV(v[0], v[1], v[2]);
                if (!inGamut255(rgb)) {
                    return BLACK_ARGB;
                }
                return argbOpaque(rgb[0], rgb[1], rgb[2]);
            }

            @Override
            Color toDisplayColor(double[] v) {
                return new YUV(v[0], v[1], v[2]).from();
            }

            @Override
            String format(double[] v) {
                return String.format("Y %.1f  U %.1f  V %.1f", v[0], v[1], v[2]);
            }

            @Override
            String[] axisLabels() {
                return new String[]{"Y+", "Y−", "U+", "U−", "V+", "V−"};
            }

            @Override
            Color[] axisColors() {
                return new Color[]{
                    new Color(0xff, 0xff, 0xff), new Color(0x44, 0x44, 0x44),
                    new Color(0xff, 0x44, 0x44), new Color(0x44, 0xdd, 0xdd),
                    new Color(0x44, 0x66, 0xff), new Color(0xdd, 0xdd, 0x44)
                };
            }

            @Override
            String[] axisLegend() {
                return new String[]{"↑ Y+ brighter", "↓ Y− darker", "↗ U+ red dev.",
                    "↙ U− cyan dev.", "↘ V+ blue dev.", "↖ V− yellow dev."};
            }
        },
        RGB("RGB reach") {
            @Override
            double[] fromColor(Color c) {
                return new double[]{c.getRed(), c.getGreen(), c.getBlue()};
            }

            @Override
            double[] clampDomain(double[] v) {
                return new double[]{clamp(v[0], 0, 255), clamp(v[1], 0, 255), clamp(v[2], 0, 255)};
            }

            @Override
            boolean inGamut(double[] v) {
                return inGamut255(v);
            }

            @Override
            int toArgb(double[] v) {
                if (!inGamut255(v)) {
                    return BLACK_ARGB;
                }
                return argbOpaque(v[0], v[1], v[2]);
            }

            @Override
            Color toDisplayColor(double[] v) {
                return EnhancedColor.clamped(Math.round(v[0]), Math.round(v[1]), Math.round(v[2]));
            }

            @Override
            String format(double[] v) {
                return String.format("R %.0f  G %.0f  B %.0f", v[0], v[1], v[2]);
            }

            @Override
            String[] axisLabels() {
                return new String[]{"R+", "R−", "G+", "G−", "B+", "B−"};
            }

            @Override
            Color[] axisColors() {
                return new Color[]{
                    new Color(0xff, 0x44, 0x44), new Color(0x44, 0xdd, 0xdd),
                    new Color(0x44, 0xdd, 0x44), new Color(0xdd, 0x44, 0xdd),
                    new Color(0x44, 0x66, 0xff), new Color(0xdd, 0xdd, 0x44)
                };
            }

            @Override
            String[] axisLegend() {
                return new String[]{"↑ R+ more red", "↓ R− less red (cyan)", "↗ G+ more green",
                    "↙ G− less green (magenta)", "↘ B+ more blue", "↖ B− less blue (yellow)"};
            }
        };

        final String reachLabel;

        Space(String reachLabel) {
            this.reachLabel = reachLabel;
        }

        /**
         * @return this space's native [ch1, ch2, ch3] for a real colour.
         */
        abstract double[] fromColor(Color c);

        /**
         * @return v clamped to this space's legal per-channel numeric range
         * (not a gamut test — just keeps values in a sane domain).
         */
        abstract double[] clampDomain(double[] v);

        /**
         * @return whether v converts to a displayable sRGB colour.
         */
        abstract boolean inGamut(double[] v);

        /**
         * @return opaque ARGB for v, or solid black if out of gamut.
         */
        abstract int toArgb(double[] v);

        /**
         * @return the nearest displayable colour to v (clamped, no gamut
         * signalling — for swatches/history, not the disc raster).
         */
        abstract Color toDisplayColor(double[] v);

        /**
         * @return v formatted as this space's coordinate readout text.
         */
        abstract String format(double[] v);

        abstract String[] axisLabels();

        abstract Color[] axisColors();

        abstract String[] axisLegend();
    }

    /**
     * Renders the polar disc for the active {@link Space} and turns mouse
     * position/clicks into native coordinates for the linked
     * {@link InfoPanel}.
     */
    static class CompassPanel extends JPanel {

        final int SIZE;
        final int CX, CY, R;
        Space mode = Space.LAB;
        double[] center;
        Color secondColor;
        int deReach = 30;
        double[] hover = null;
        InfoPanel info;
        BufferedImage compassImage;

        CompassPanel(int size, Color initialColor) {
            SIZE = size;
            CX = CY = SIZE / 2;
            R = SIZE / 2 - 2;
            center = mode.fromColor(initialColor);
            setPreferredSize(new Dimension(SIZE, SIZE));
            setBackground(new Color(0x1a1a1a));
            rebuildImage();

            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    hover = pixelToNative(e.getX(), e.getY());
                    if (hover != null && info != null) {
                        info.update(hover, false);
                    }
                    repaint();
                }
            });
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    double[] v = pixelToNative(e.getX(), e.getY());
                    if (v == null) {
                        return;
                    }
                    if (SwingUtilities.isRightMouseButton(e)) {
                        center = v;
                        rebuildImage();
                        if (info != null) {
                            info.update(v, false);
                        }
                    } else {
                        if (info != null) {
                            info.pick(v);
                        }
                    }
                    repaint();
                }
            });
        }

        void setInfoPanel(InfoPanel p) {
            this.info = p;
        }

        void setMode(Space newMode) {
            Color asColor = mode.toDisplayColor(center);
            mode = newMode;
            center = newMode.fromColor(asColor);
            rebuildImage();
        }

        /**
         * Marks {@code c} on the disc and scales the reach so it sits at
         * exactly 2/3 of the radius, on whichever axis direction its delta
         * from the current center leans toward.
         */
        void setSecondColor(Color c) {
            secondColor = c;
            double dist = norm(subtract(mode.fromColor(c), center));
            int needed = (int) Math.max(5, Math.ceil(dist * 1.5));
            if (info != null) {
                info.snapReachTo(needed);
            } else {
                deReach = needed;
                rebuildImage();
            }
            repaint();
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
                    if (Math.sqrt(dx * dx + dy * dy) > R) {
                        raster[py * SIZE + px] = 0x00000000;
                        continue;
                    }
                    raster[py * SIZE + px] = mode.toArgb(discToNative(dx, dy));
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

        /**
         * Native coordinate reached at pixel offset (dx,dy) from the
         * center, shared by the raster fill and mouse hit-testing.
         */
        double[] discToNative(double dx, double dy) {
            double dist = Math.sqrt(dx * dx + dy * dy);
            double reach = (dist / R) * deReach;
            double angle = Math.toDegrees(Math.atan2(-dy, dx));
            if (angle < 0) {
                angle += 360;
            }
            double[] w = axisWeights(angle);
            double[] v = center.clone();
            for (int i = 0; i < AXES.length; i++) {
                v[0] += reach * AXES[i][1] * w[i];
                v[1] += reach * AXES[i][2] * w[i];
                v[2] += reach * AXES[i][3] * w[i];
            }
            return mode.clampDomain(v);
        }

        double[] pixelToNative(int px, int py) {
            double dx = px - CX, dy = py - CY;
            if (Math.sqrt(dx * dx + dy * dy) > R) {
                return null;
            }
            return discToNative(dx, dy);
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
            String[] labels = mode.axisLabels();
            Color[] colors = mode.axisColors();
            for (int i = 0; i < AXES.length; i++) {
                double rad = Math.toRadians(AXES[i][0]);
                int ex = (int) (CX + Math.cos(rad) * R);
                int ey = (int) (CY - Math.sin(rad) * R);
                g2.setColor(new Color(colors[i].getRed(), colors[i].getGreen(), colors[i].getBlue(), 50));
                g2.drawLine(CX, CY, ex, ey);
                // label
                int lx = (int) (CX + Math.cos(rad) * (R - 20));
                int ly = (int) (CY - Math.sin(rad) * (R - 20));
                g2.setColor(new Color(colors[i].getRed(), colors[i].getGreen(), colors[i].getBlue(), 160));
                g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                g2.drawString(labels[i], lx - 8, ly + 4);
            }

            // border
            g2.setColor(new Color(0x333333));
            g2.setStroke(new BasicStroke(2));
            g2.drawOval(CX - R, CY - R, R * 2, R * 2);

            // second-colour marker
            if (secondColor != null) {
                double[] delta = subtract(mode.fromColor(secondColor), center);
                double frac = Math.min(1.0, norm(delta) / deReach);
                double rad = Math.toRadians(angleForDelta(delta));
                int mx = (int) (CX + Math.cos(rad) * R * frac);
                int my = (int) (CY - Math.sin(rad) * R * frac);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2));
                g2.drawOval(mx - 7, my - 7, 14, 14);
                g2.setColor(secondColor);
                g2.fillOval(mx - 4, my - 4, 8, 8);
            }

            // center dot
            g2.setColor(Color.WHITE);
            g2.fillOval(CX - 5, CY - 5, 10, 10);
            g2.setColor(mode.toDisplayColor(center));
            g2.fillOval(CX - 3, CY - 3, 6, 6);
        }
    }

    /**
     * Mode buttons, swatch, coordinate readout and pick history alongside
     * the compass disc.
     */
    static class InfoPanel extends JPanel {

        final CompassPanel compass;
        final JPanel swatch = new JPanel();
        final JLabel lblCoord = new JLabel();
        final JLabel lblHex = new JLabel("#808080");
        final JLabel lblReachVal = new JLabel();
        final JSlider slider = new JSlider(5, 80, 30);
        final JPanel axisLegendPanel = new JPanel();
        final JPanel history = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        final ArrayList<Color> picks = new ArrayList<>();

        InfoPanel(CompassPanel compass) {
            this.compass = compass;
            setBackground(new Color(0x1a1a1a));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

            JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            modeRow.setBackground(new Color(0x1a1a1a));
            ButtonGroup group = new ButtonGroup();
            for (Space s : Space.values()) {
                JToggleButton btn = new JToggleButton(s.name());
                btn.setSelected(s == compass.mode);
                btn.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                btn.addActionListener(e -> {
                    compass.setMode(s);
                    refreshForMode();
                    compass.repaint();
                });
                group.add(btn);
                modeRow.add(btn);
            }

            swatch.setPreferredSize(new Dimension(220, 70));
            swatch.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
            swatch.setBackground(new Color(0x808080));
            swatch.setBorder(BorderFactory.createLineBorder(new Color(0x333333)));

            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
            lblCoord.setForeground(new Color(0xaaaaaa));
            lblCoord.setFont(mono);
            lblHex.setForeground(Color.WHITE);
            lblHex.setFont(mono);
            lblReachVal.setForeground(new Color(0xaaaaaa));
            lblReachVal.setFont(mono);

            slider.setBackground(new Color(0x1a1a1a));
            slider.setForeground(new Color(0x888888));

            JLabel lblReach = small("REACH");
            JLabel lblHist = small("HISTORY (click=center)");
            JLabel lblHint = small("LEFT CLICK=pick  RIGHT CLICK=set center");

            history.setBackground(new Color(0x1a1a1a));
            history.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

            axisLegendPanel.setLayout(new BoxLayout(axisLegendPanel, BoxLayout.Y_AXIS));
            axisLegendPanel.setBackground(new Color(0x1a1a1a));

            slider.addChangeListener(e -> {
                compass.setDeReach(slider.getValue());
                lblReachVal.setText(compass.mode.reachLabel + ": " + slider.getValue());
            });

            add(modeRow);
            add(Box.createVerticalStrut(8));
            add(swatch);
            add(Box.createVerticalStrut(8));
            add(lblCoord);
            add(lblHex);
            add(lblReachVal);
            add(Box.createVerticalStrut(4));
            add(lblReach);
            add(slider);
            add(Box.createVerticalStrut(8));
            add(axisLegendPanel);
            add(Box.createVerticalStrut(8));
            add(lblHist);
            add(history);
            add(Box.createVerticalGlue());
            add(lblHint);

            refreshForMode();
        }

        JLabel small(String t) {
            JLabel l = new JLabel(t);
            l.setForeground(new Color(0x555555));
            l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            return l;
        }

        /**
         * Re-reads the active mode's axis vocabulary/labels and re-derives
         * the current readouts from it; called after {@link Space}
         * switches.
         */
        void refreshForMode() {
            axisLegendPanel.removeAll();
            String[] desc = compass.mode.axisLegend();
            Color[] colors = compass.mode.axisColors();
            for (int i = 0; i < desc.length; i++) {
                JLabel l = new JLabel(desc[i]);
                l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                l.setForeground(colors[i]);
                axisLegendPanel.add(l);
            }
            axisLegendPanel.revalidate();
            axisLegendPanel.repaint();
            lblReachVal.setText(compass.mode.reachLabel + ": " + slider.getValue());
            update(compass.center, false);
            rebuildHistory();
        }

        /**
         * Extends the slider's range if needed, then sets it to {@code
         * value} — used to snap the reach so a second colour lands at 2/3
         * radius.
         */
        void snapReachTo(int value) {
            if (value > slider.getMaximum()) {
                slider.setMaximum(value);
            }
            slider.setValue(value);
        }

        void update(double[] v, boolean picked) {
            Color rgb = compass.mode.toDisplayColor(v);
            swatch.setBackground(rgb);
            lblCoord.setText(compass.mode.format(v));
            lblHex.setText(String.format("#%02x%02x%02x", rgb.getRed(), rgb.getGreen(), rgb.getBlue()));
        }

        void pick(double[] v) {
            if (!compass.mode.inGamut(v)) {
                return; // out of gamut, skip
            }
            update(v, true);
            picks.add(0, compass.mode.toDisplayColor(v));
            if (picks.size() > 20) {
                picks.remove(picks.size() - 1);
            }
            rebuildHistory();
        }

        void rebuildHistory() {
            history.removeAll();
            for (Color c : picks) {
                JPanel chip = new JPanel();
                chip.setPreferredSize(new Dimension(28, 28));
                chip.setBackground(c);
                chip.setBorder(BorderFactory.createLineBorder(new Color(0x333333)));
                chip.setToolTipText(String.format("%s  #%02x%02x%02x  [click=center]",
                        compass.mode.format(compass.mode.fromColor(c)), c.getRed(), c.getGreen(), c.getBlue()));
                chip.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        compass.center = compass.mode.fromColor(c);
                        compass.rebuildImage();
                        compass.repaint();
                        update(compass.center, false);
                    }
                });
                history.add(chip);
            }
            history.revalidate();
            history.repaint();
        }
    }
}
