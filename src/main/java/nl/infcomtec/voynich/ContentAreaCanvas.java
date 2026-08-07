/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Interactive tracing surface for one {@link CatalogEntry}'s
 * {@link CatalogEntry.Region#polygon}, over its full-resolution image.
 * A human clicks a sequence of vertices around the actual content — text,
 * illustration, wash — deliberately tight, not the physical page, closing
 * the path by clicking near its start. See {@link ContentAreaEditor} for why
 * this is a human-traced tool rather than an auto-detector.
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
 * <p>
 * Two loupes (a plain 4x and a contrast-boosted 4x) track the cursor,
 * showing native image pixels regardless of how far the whole page is
 * scaled down to fit the screen — the fix for boundaries that run right to
 * the image edge, where the on-screen scale alone makes exact placement
 * guesswork, and for content dim enough to almost miss (a margin so faint
 * it nearly got traced as blank vellum). The pair always anchors to
 * whichever screen corner is diagonally opposite the cursor's current
 * quadrant, so they never sit under the point you're about to click. Like
 * the rubber-band line, per-pixel cursor tracking avoids a full-component
 * repaint: the loupes are painted directly via {@code getGraphics()} on
 * every mouse move (cheap — a fixed small box, not the whole image), and
 * only when the cursor crosses into a different quadrant do two small
 * {@link #repaint(Rectangle)} calls (old corner, new corner) run instead,
 * each clipped so Java2D still only rasterizes that small box rather than
 * the underlying multi-thousand-pixel image.
 * </p>
 */
final class ContentAreaCanvas extends JComponent {

    private static final int CLOSE_RADIUS_PX = 10;
    private static final int HANDLE_RADIUS_PX = 6;
    private static final int HANDLE_GRAB_PX = 10;
    private static final Color BOUNDARY_COLOR = Color.RED;
    private static final Color HANDLE_COLOR = Color.YELLOW;
    private static final BasicStroke BOUNDARY_STROKE = new BasicStroke(2f);
    private static final BasicStroke RUBBER_BAND_STROKE = new BasicStroke(2f);

    private static final int LOUPE_SIZE = 220;
    private static final double LOUPE_ZOOM = 4.0;
    private static final int LOUPE_SOURCE_PX = (int) Math.round(LOUPE_SIZE / LOUPE_ZOOM);
    private static final int LOUPE_MARGIN = 14;
    private static final int LOUPE_GAP = 10;
    private static final int LOUPE_HYSTERESIS_PX = 60;
    private static final float LOUPE_CONTRAST = 2.3f;
    private static final Color LOUPE_BORDER = Color.WHITE;
    private static final Font LOUPE_LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    private final BufferedImage image;
    private final List<Point> vertices = new ArrayList<>();
    private boolean closed;
    private int dragIndex = -1;
    private Point rubberBandFrom;
    private Point rubberBandTo;
    private TraceStateListener stateListener;

    private Point lastPanelPoint;
    private boolean loupeAnchorLeft;
    private boolean loupeAnchorTop;
    private boolean loupeAnchorKnown;

    /**
     * @param image the entry's full-resolution image to trace over
     * @param initial the region's existing {@link CatalogEntry.Region#polygon},
     * pre-loaded (already closed) for review/adjustment rather than starting
     * from scratch; empty for a fresh trace
     */
    ContentAreaCanvas(BufferedImage image, List<CatalogEntry.Vertex> initial) {
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

            @Override
            public void mouseExited(MouseEvent e) {
                if (loupeAnchorKnown) {
                    repaint(loupeBounds(loupeAnchorLeft, loupeAnchorTop));
                }
                lastPanelPoint = null;
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (!closed && !vertices.isEmpty()) {
                    updateRubberBand(e.getPoint());
                }
                updateLoupes(e.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (closed && dragIndex >= 0) {
                    vertices.set(dragIndex, panelToImage(e.getPoint()));
                    lastPanelPoint = e.getPoint();
                    computeLoupeAnchor(e.getPoint());
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
    void setStateListener(TraceStateListener listener) {
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
            stateListener.onTraceStateChanged(closed);
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

    /**
     * Updates the tracked cursor position and repaints the loupe pair.
     * Ordinary mouse moves paint directly (cheap, no full-component
     * invalidation); crossing into a different quadrant instead triggers
     * two small clipped {@link #repaint(Rectangle)} calls — one to restore
     * the old corner's plain image, one for the new corner's loupes — so
     * neither the underlying (potentially huge) image nor the independent
     * XOR-drawn rubber-band state gets disturbed.
     */
    private void updateLoupes(Point panelPt) {
        Rectangle oldBounds = loupeAnchorKnown ? loupeBounds(loupeAnchorLeft, loupeAnchorTop) : null;
        lastPanelPoint = panelPt;
        boolean changed = computeLoupeAnchor(panelPt);
        if (changed) {
            if (null != oldBounds) {
                repaint(oldBounds);
            }
            repaint(loupeBounds(loupeAnchorLeft, loupeAnchorTop));
        } else {
            Graphics2D g2 = (Graphics2D) getGraphics();
            if (null != g2) {
                paintLoupes(g2);
                g2.dispose();
            }
        }
    }

    /**
     * @return true if the anchor corner (which quadrant of the canvas the
     * cursor is in, mirrored) changed since the last call. Flips use a
     * {@link #LOUPE_HYSTERESIS_PX} dead band straddling each center axis
     * rather than the bare midpoint — right at 0/90/180/270 degrees from
     * center, cursor jitter of a couple pixels would otherwise cross the
     * midpoint back and forth and make the loupes jump corner to corner.
     */
    private boolean computeLoupeAnchor(Point panelPt) {
        double centerX = getWidth() / 2.0;
        double centerY = getHeight() / 2.0;
        boolean newLeft;
        boolean newTop;
        if (!loupeAnchorKnown) {
            newLeft = panelPt.x >= centerX;
            newTop = panelPt.y >= centerY;
        } else {
            newLeft = loupeAnchorLeft
                    ? panelPt.x >= centerX - LOUPE_HYSTERESIS_PX
                    : panelPt.x > centerX + LOUPE_HYSTERESIS_PX;
            newTop = loupeAnchorTop
                    ? panelPt.y >= centerY - LOUPE_HYSTERESIS_PX
                    : panelPt.y > centerY + LOUPE_HYSTERESIS_PX;
        }
        boolean changed = !loupeAnchorKnown || newLeft != loupeAnchorLeft || newTop != loupeAnchorTop;
        loupeAnchorLeft = newLeft;
        loupeAnchorTop = newTop;
        loupeAnchorKnown = true;
        return changed;
    }

    private Point loupeOrigin(boolean anchorLeft, boolean anchorTop) {
        int x = anchorLeft ? LOUPE_MARGIN : getWidth() - LOUPE_MARGIN - LOUPE_SIZE;
        int y = anchorTop ? LOUPE_MARGIN : getHeight() - LOUPE_MARGIN - (2 * LOUPE_SIZE + LOUPE_GAP);
        return new Point(x, y);
    }

    private Rectangle loupeBounds(boolean anchorLeft, boolean anchorTop) {
        Point o = loupeOrigin(anchorLeft, anchorTop);
        return new Rectangle(o.x - 2, o.y - 2, LOUPE_SIZE + 4, 2 * LOUPE_SIZE + LOUPE_GAP + 4);
    }

    private void paintLoupes(Graphics2D g2) {
        if (null == lastPanelPoint) {
            return;
        }
        Point imgPt = panelToImage(lastPanelPoint);
        int half = LOUPE_SOURCE_PX / 2;
        int srcX = imgPt.x - half;
        int srcY = imgPt.y - half;
        BufferedImage crop = extractLoupeCrop(srcX, srcY);
        BufferedImage contrastCrop = applyContrast(crop);
        Point o = loupeOrigin(loupeAnchorLeft, loupeAnchorTop);
        paintLoupeBox(g2, crop, o.x, o.y, "4x", srcX, srcY);
        paintLoupeBox(g2, contrastCrop, o.x, o.y + LOUPE_SIZE + LOUPE_GAP, "4x, contrast boosted", srcX, srcY);
    }

    /**
     * Crops a fixed {@link #LOUPE_SOURCE_PX} square of native image pixels
     * with top-left corner at {@code (srcX, srcY)}, padded with neutral
     * gray where the crop falls outside the image bounds — the common case
     * right at a page edge, exactly where the loupe matters most.
     */
    private BufferedImage extractLoupeCrop(int srcX, int srcY) {
        BufferedImage crop = new BufferedImage(LOUPE_SOURCE_PX, LOUPE_SOURCE_PX, BufferedImage.TYPE_INT_RGB);
        Graphics2D cg = crop.createGraphics();
        cg.setColor(Color.GRAY);
        cg.fillRect(0, 0, LOUPE_SOURCE_PX, LOUPE_SOURCE_PX);
        int clipX1 = Math.max(0, srcX);
        int clipY1 = Math.max(0, srcY);
        int clipX2 = Math.min(image.getWidth(), srcX + LOUPE_SOURCE_PX);
        int clipY2 = Math.min(image.getHeight(), srcY + LOUPE_SOURCE_PX);
        if (clipX2 > clipX1 && clipY2 > clipY1) {
            int destX = clipX1 - srcX;
            int destY = clipY1 - srcY;
            cg.drawImage(image, destX, destY, destX + (clipX2 - clipX1), destY + (clipY2 - clipY1),
                    clipX1, clipY1, clipX2, clipY2, null);
        }
        cg.dispose();
        return crop;
    }

    /**
     * Contrast stretch centered on mid-gray (128 maps to itself), so the
     * crop's gray out-of-bounds padding stays neutral while real content —
     * including ink dim enough to nearly be missed — pushes toward the
     * extremes.
     */
    private BufferedImage applyContrast(BufferedImage src) {
        RescaleOp op = new RescaleOp(LOUPE_CONTRAST, 128f * (1f - LOUPE_CONTRAST), null);
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        op.filter(src, dst);
        return dst;
    }

    /**
     * @param srcX @param srcY the crop's source origin in image coordinates
     * (matches what {@link #extractLoupeCrop} was given), used to place
     * markers for any already-placed vertex that falls within this crop
     */
    private void paintLoupeBox(Graphics2D g2, BufferedImage crop, int x, int y, String label, int srcX, int srcY) {
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(crop, x, y, LOUPE_SIZE, LOUPE_SIZE, null);
        g2.setColor(LOUPE_BORDER);
        g2.setStroke(BOUNDARY_STROKE);
        g2.drawRect(x, y, LOUPE_SIZE, LOUPE_SIZE);

        double boxScale = (double) LOUPE_SIZE / LOUPE_SOURCE_PX;
        for (Point v : vertices) {
            if (v.x >= srcX && v.x < srcX + LOUPE_SOURCE_PX && v.y >= srcY && v.y < srcY + LOUPE_SOURCE_PX) {
                int vx = x + (int) Math.round((v.x - srcX) * boxScale);
                int vy = y + (int) Math.round((v.y - srcY) * boxScale);
                g2.setColor(HANDLE_COLOR);
                g2.fillOval(vx - 4, vy - 4, 8, 8);
                g2.setColor(Color.BLACK);
                g2.drawOval(vx - 4, vy - 4, 8, 8);
            }
        }

        // Full-box, XOR-mode crosshair: a solid color gets lost against
        // busy manuscript/vellum content, XOR against whatever's beneath
        // it stays visible regardless.
        int cx = x + LOUPE_SIZE / 2;
        int cy = y + LOUPE_SIZE / 2;
        g2.setXORMode(Color.WHITE);
        g2.setColor(Color.BLACK);
        g2.drawLine(x, cy, x + LOUPE_SIZE, cy);
        g2.drawLine(cx, y, cx, y + LOUPE_SIZE);
        g2.setPaintMode();

        g2.setFont(LOUPE_LABEL_FONT);
        g2.setColor(Color.BLACK);
        g2.fillRect(x + 1, y + 1, g2.getFontMetrics().stringWidth(label) + 6, 14);
        g2.setColor(Color.WHITE);
        g2.drawString(label, x + 4, y + 11);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(image, offX(), offY(), dispW(), dispH(), null);
        paintLoupes(g2);

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
