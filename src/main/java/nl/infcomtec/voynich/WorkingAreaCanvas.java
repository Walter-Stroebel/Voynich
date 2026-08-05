/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Interactive tracing surface for one {@link CatalogEntry}'s
 * {@link CatalogEntry#workingArea} polygon, over its full-resolution image.
 * A human clicks a sequence of vertices around the manuscript page's actual
 * boundary — never the photography backdrop, never a fold, and routing
 * around whatever "chasm" of frayed edge/other-page-stack the corner shows —
 * closing the path by clicking near its start. See
 * {@link WorkingAreaEditor} for why this is a human-traced tool rather than
 * an auto-detector.
 * <p>
 * While tracing, the live segment from the last placed vertex to the cursor
 * is drawn with {@link Graphics2D#setXORMode}, not a full repaint: the same
 * line drawn twice cancels out, so a mouse-move updates the guide by erasing
 * the previous position and drawing the new one directly, without
 * re-rendering the (potentially very large) underlying image on every
 * pixel of cursor travel. Once closed, each vertex gets a small draggable
 * handle for a review/adjustment pass — that part uses ordinary repaints,
 * since handle drags are infrequent compared to live cursor tracking.
 * </p>
 */
final class WorkingAreaCanvas extends JComponent {

    private static final int CLOSE_RADIUS_PX = 10;
    private static final int HANDLE_RADIUS_PX = 6;
    private static final int HANDLE_GRAB_PX = 10;
    private static final Color BOUNDARY_COLOR = Color.RED;
    private static final Color HANDLE_COLOR = Color.YELLOW;
    private static final BasicStroke BOUNDARY_STROKE = new BasicStroke(2f);
    private static final BasicStroke RUBBER_BAND_STROKE = new BasicStroke(2f);

    private final BufferedImage image;
    private final List<Point> vertices = new ArrayList<>();
    private boolean closed;
    private int dragIndex = -1;
    private Point rubberBandFrom;
    private Point rubberBandTo;
    private Consumer<Boolean> stateListener;

    /**
     * @param image the entry's full-resolution image to trace over
     * @param initial the entry's existing {@link CatalogEntry#workingArea},
     * pre-loaded (already closed) for review/adjustment rather than starting
     * from scratch; empty for a fresh trace
     */
    WorkingAreaCanvas(BufferedImage image, List<CatalogEntry.Vertex> initial) {
        this.image = image;
        for (CatalogEntry.Vertex v : initial) {
            vertices.add(new Point(v.x, v.y));
        }
        closed = vertices.size() >= 3;
        setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (closed) {
                    dragIndex = handleAt(e.getPoint());
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    undoLastVertex();
                } else {
                    handleTraceClick(e.getPoint());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragIndex = -1;
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (!closed && !vertices.isEmpty()) {
                    updateRubberBand(e.getPoint());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (closed && dragIndex >= 0) {
                    vertices.set(dragIndex, panelToImage(e.getPoint()));
                    repaint();
                }
            }
        });
    }

    /**
     * Discards the current trace (or the loaded polygon) and starts over.
     */
    void clear() {
        vertices.clear();
        closed = false;
        rubberBandFrom = null;
        rubberBandTo = null;
        dragIndex = -1;
        repaint();
        fireStateChanged();
    }

    /**
     * @param listener called with {@code true} once the polygon is closed
     * (and again with {@code false} on {@link #clear}) — drives the host
     * window's Commit button enablement
     */
    void setStateListener(Consumer<Boolean> listener) {
        this.stateListener = listener;
    }

    /**
     * @return the closed polygon's vertices in image pixel coordinates, or
     * an empty list if not yet closed
     */
    List<CatalogEntry.Vertex> resultVertices() {
        if (!closed) {
            return List.of();
        }
        List<CatalogEntry.Vertex> out = new ArrayList<>(vertices.size());
        for (Point p : vertices) {
            out.add(new CatalogEntry.Vertex(p.x, p.y));
        }
        return out;
    }

    /**
     * Right-click: removes the most recently placed vertex, so one bad
     * click mid-trace doesn't cost the whole polygon traced so far (that's
     * what {@link #clear} is for). A no-op with nothing placed yet.
     */
    private void undoLastVertex() {
        if (vertices.isEmpty()) {
            return;
        }
        eraseRubberBand();
        vertices.remove(vertices.size() - 1);
        rubberBandFrom = vertices.isEmpty() ? null : imageToPanel(vertices.get(vertices.size() - 1));
        repaint();
    }

    private void handleTraceClick(Point panelPt) {
        eraseRubberBand();
        if (vertices.size() >= 3 && imageToPanel(vertices.get(0)).distance(panelPt) <= CLOSE_RADIUS_PX) {
            closed = true;
            repaint();
            fireStateChanged();
            return;
        }
        vertices.add(panelToImage(panelPt));
        rubberBandFrom = imageToPanel(vertices.get(vertices.size() - 1));
        repaint();
    }

    private int handleAt(Point panelPt) {
        for (int i = 0; i < vertices.size(); i++) {
            if (imageToPanel(vertices.get(i)).distance(panelPt) <= HANDLE_GRAB_PX) {
                return i;
            }
        }
        return -1;
    }

    private void updateRubberBand(Point newTo) {
        if (null != rubberBandTo) {
            drawXorLine(rubberBandFrom, rubberBandTo);
        }
        drawXorLine(rubberBandFrom, newTo);
        rubberBandTo = newTo;
    }

    private void eraseRubberBand() {
        if (null != rubberBandFrom && null != rubberBandTo) {
            drawXorLine(rubberBandFrom, rubberBandTo);
        }
        rubberBandTo = null;
    }

    private void drawXorLine(Point a, Point b) {
        Graphics2D g2 = (Graphics2D) getGraphics();
        if (null == g2) {
            return;
        }
        g2.setXORMode(Color.WHITE);
        g2.setColor(Color.BLACK);
        g2.setStroke(RUBBER_BAND_STROKE);
        g2.drawLine(a.x, a.y, b.x, b.y);
        g2.setPaintMode();
        g2.dispose();
    }

    private void fireStateChanged() {
        if (null != stateListener) {
            stateListener.accept(closed);
        }
    }

    private double scale() {
        return Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
    }

    private int dispW() {
        return Math.max(1, (int) Math.round(image.getWidth() * scale()));
    }

    private int dispH() {
        return Math.max(1, (int) Math.round(image.getHeight() * scale()));
    }

    private int offX() {
        return (getWidth() - dispW()) / 2;
    }

    private int offY() {
        return (getHeight() - dispH()) / 2;
    }

    private Point imageToPanel(Point p) {
        double s = scale();
        return new Point(offX() + (int) Math.round(p.x * s), offY() + (int) Math.round(p.y * s));
    }

    private Point panelToImage(Point p) {
        double s = scale();
        int ix = (int) Math.round((p.x - offX()) / s);
        int iy = (int) Math.round((p.y - offY()) / s);
        ix = Math.max(0, Math.min(image.getWidth() - 1, ix));
        iy = Math.max(0, Math.min(image.getHeight() - 1, iy));
        return new Point(ix, iy);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(image, offX(), offY(), dispW(), dispH(), null);

        if (vertices.isEmpty()) {
            return;
        }
        g2.setColor(BOUNDARY_COLOR);
        g2.setStroke(BOUNDARY_STROKE);
        Point prev = imageToPanel(vertices.get(0));
        for (int i = 1; i < vertices.size(); i++) {
            Point cur = imageToPanel(vertices.get(i));
            g2.drawLine(prev.x, prev.y, cur.x, cur.y);
            prev = cur;
        }
        if (closed) {
            Point first = imageToPanel(vertices.get(0));
            g2.drawLine(prev.x, prev.y, first.x, first.y);
            g2.setColor(HANDLE_COLOR);
            for (Point v : vertices) {
                Point p = imageToPanel(v);
                g2.fillOval(p.x - HANDLE_RADIUS_PX, p.y - HANDLE_RADIUS_PX, HANDLE_RADIUS_PX * 2, HANDLE_RADIUS_PX * 2);
                g2.setColor(Color.BLACK);
                g2.drawOval(p.x - HANDLE_RADIUS_PX, p.y - HANDLE_RADIUS_PX, HANDLE_RADIUS_PX * 2, HANDLE_RADIUS_PX * 2);
                g2.setColor(HANDLE_COLOR);
            }
        }
    }
}
