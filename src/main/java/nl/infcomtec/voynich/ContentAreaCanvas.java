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
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
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
 * Three loupes (a plain 4x, a contrast-boosted 4x, and a Sobel
 * edge-magnitude 4x) track the cursor, showing native image pixels
 * regardless of how far the whole page is scaled down to fit the screen
 * — the fix for boundaries that run right to the image edge, where the
 * on-screen scale alone makes exact placement guesswork, and for content
 * dim enough to almost miss (a margin so faint it nearly got traced as
 * blank vellum). The Sobel view turns a faint or gradual ink/vellum
 * transition into a bright line at the actual edge — the standard
 * gradient-magnitude kernel pair (Gx/Gy, combined as
 * {@code sqrt(Gx^2 + Gy^2)}), run on luminance. The trio always anchors to
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
 * <p>
 * An optional {@code viewport} (see the constructor) restricts display and
 * tracing to a sub-rectangle of the image — a parent region's bounding box,
 * typically — so a small figure nested inside a larger traced diagram (e.g.
 * one wedge of a many-figure circle diagram) can be traced at usable scale
 * instead of whole-page scale. Placed vertices are still recorded in the
 * full image's coordinates regardless of viewport. Once a polygon is
 * closed, the mouse wheel rotates a live preview of the traced crop itself
 * (top-center of the canvas) — seeing the actual figure spin upright is
 * what sets {@link CatalogEntry.Region#angle}, not an abstract indicator,
 * since a wonky wedge has no edge that reliably means "up" to look at.
 * </p>
 * <p>
 * While tracing (or re-tracing) one region, every other already-traced
 * region on the page is otherwise invisible here — useful when a crowded
 * area (e.g. a ring of many small figures) makes it easy to lose track of
 * which neighbor is which. Cheap hover feedback bridges that: the cursor is
 * tested against each {@link #siblingPolygons} entry on every move, and
 * whichever one it's currently inside gets XOR-outlined, the same
 * draw-twice-to-erase trick as the rubber-band guide — see
 * {@link #updateHoverHighlight}.
 * </p>
 */
final class ContentAreaCanvas extends JComponent {

    private static final int CLOSE_RADIUS_PX = 10;
    private static final int HANDLE_RADIUS_PX = 6;
    private static final int HANDLE_GRAB_PX = 10;
    private static final Color BOUNDARY_COLOR = Color.RED;
    private static final Color HANDLE_COLOR = Color.YELLOW;
    private static final Color HOVER_COLOR = Color.CYAN;
    private static final BasicStroke BOUNDARY_STROKE = new BasicStroke(2f);
    private static final BasicStroke RUBBER_BAND_STROKE = new BasicStroke(2f);
    private static final BasicStroke HOVER_STROKE = new BasicStroke(2f);

    private static final int LOUPE_SIZE = 220;
    private static final double LOUPE_ZOOM = 4.0;
    private static final int LOUPE_SOURCE_PX = (int) Math.round(LOUPE_SIZE / LOUPE_ZOOM);
    private static final int LOUPE_MARGIN = 14;
    private static final int LOUPE_GAP = 10;
    private static final int LOUPE_HYSTERESIS_PX = 60;
    private static final float LOUPE_CONTRAST = 2.3f;
    private static final Color LOUPE_BORDER = Color.WHITE;
    private static final Font LOUPE_LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final double ANGLE_STEP_RADIANS = Math.toRadians(1);
    private static final int ROTATION_PREVIEW_SIZE = 220;
    private static final Color ROTATION_PREVIEW_BG = Color.DARK_GRAY;

    private final BufferedImage image;
    private final BufferedImage displayImage;
    private final int originX;
    private final int originY;
    private final List<Point> vertices = new ArrayList<>();
    private boolean closed;
    private int dragIndex = -1;
    private Point rubberBandFrom;
    private Point rubberBandTo;
    private TraceStateListener stateListener;
    private double angle;
    private BufferedImage rotationPreviewSource;
    private boolean rotationPreviewDirty = true;

    private Point lastPanelPoint;
    private boolean loupeAnchorLeft;
    private boolean loupeAnchorTop;
    private boolean loupeAnchorKnown;

    private final List<List<Point>> siblingPolygons;
    private int hoveredSiblingIndex = -1;

    /**
     * @param image the entry's full-resolution image to trace over
     * @param initial the region's existing {@link CatalogEntry.Region#polygon},
     * pre-loaded (already closed) for review/adjustment rather than starting
     * from scratch; empty for a fresh trace
     * @param viewport when non-null, restricts both display and tracing to
     * this sub-rectangle of {@code image} (typically a parent region's
     * bounding box) so a small child figure can be traced at usable scale
     * instead of whole-page scale; {@code null} traces the full image, as
     * before this parameter existed. Placed vertices are still recorded in
     * {@code image}'s full coordinates regardless.
     * @param initialAngle the region's existing {@link CatalogEntry.Region#angle},
     * pre-loaded for review; {@code 0} for a fresh trace
     * @param siblingPolygons every other already-traced region's polygon (in
     * {@code image}'s full coordinates, not this trace's own), purely for
     * hover feedback — see {@link #updateHoverHighlight}; {@code null} or
     * empty for none
     */
    ContentAreaCanvas(BufferedImage image, List<CatalogEntry.Vertex> initial, Rectangle viewport,
            double initialAngle, List<List<Point>> siblingPolygons) {
        this.image = image;
        this.siblingPolygons = null == siblingPolygons ? List.of() : siblingPolygons;
        if (null == viewport) {
            this.displayImage = image;
            this.originX = 0;
            this.originY = 0;
        } else {
            this.displayImage = image.getSubimage(viewport.x, viewport.y, viewport.width, viewport.height);
            this.originX = viewport.x;
            this.originY = viewport.y;
        }
        for (CatalogEntry.Vertex v : initial) {
            vertices.add(new Point(v.x, v.y));
        }
        closed = vertices.size() >= 3;
        angle = initialAngle;
        setPreferredSize(new Dimension(displayImage.getWidth(), displayImage.getHeight()));

        addMouseWheelListener(new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (!closed) {
                    return;
                }
                angle += e.getWheelRotation() * ANGLE_STEP_RADIANS;
                repaint();
            }
        });
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
                if (hoveredSiblingIndex >= 0) {
                    drawSiblingOutlineXor(hoveredSiblingIndex);
                    hoveredSiblingIndex = -1;
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (!closed && !vertices.isEmpty()) {
                    updateRubberBand(e.getPoint());
                }
                updateLoupes(e.getPoint());
                updateHoverHighlight(e.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (closed && dragIndex >= 0) {
                    vertices.set(dragIndex, panelToImage(e.getPoint()));
                    rotationPreviewDirty = true;
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
            rotationPreviewDirty = true;
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

    /**
     * @return the closed polygon's rotation, in radians, as adjusted by the
     * mouse wheel — see {@link CatalogEntry.Region#angle}
     */
    double angleResult() {
        return angle;
    }

    private double scale() {
        return Math.min((double) getWidth() / displayImage.getWidth(), (double) getHeight() / displayImage.getHeight());
    }

    private int dispW() {
        return Math.max(1, (int) Math.round(displayImage.getWidth() * scale()));
    }

    private int dispH() {
        return Math.max(1, (int) Math.round(displayImage.getHeight() * scale()));
    }

    private int offX() {
        return (getWidth() - dispW()) / 2;
    }

    private int offY() {
        return (getHeight() - dispH()) / 2;
    }

    private Point imageToPanel(Point p) {
        double s = scale();
        return new Point(offX() + (int) Math.round((p.x - originX) * s), offY() + (int) Math.round((p.y - originY) * s));
    }

    private Point panelToImage(Point p) {
        double s = scale();
        int ix = (int) Math.round((p.x - offX()) / s) + originX;
        int iy = (int) Math.round((p.y - offY()) / s) + originY;
        ix = Math.max(originX, Math.min(originX + displayImage.getWidth() - 1, ix));
        iy = Math.max(originY, Math.min(originY + displayImage.getHeight() - 1, iy));
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

    /**
     * The loupe grid is a fixed 2x2 square (plain, contrast, Sobel, and one
     * reserved-empty cell) rather than a taller stack — it was a stack
     * originally, but a third loupe made that an L-shape eating unclaimed
     * vertical space for no reason; a square uses less of it and already has
     * room for a fourth loupe should one ever be worth adding.
     */
    private Point loupeOrigin(boolean anchorLeft, boolean anchorTop) {
        int side = 2 * LOUPE_SIZE + LOUPE_GAP;
        int x = anchorLeft ? LOUPE_MARGIN : getWidth() - LOUPE_MARGIN - side;
        int y = anchorTop ? LOUPE_MARGIN : getHeight() - LOUPE_MARGIN - side;
        return new Point(x, y);
    }

    private Rectangle loupeBounds(boolean anchorLeft, boolean anchorTop) {
        Point o = loupeOrigin(anchorLeft, anchorTop);
        int side = 2 * LOUPE_SIZE + LOUPE_GAP;
        return new Rectangle(o.x - 2, o.y - 2, side + 4, side + 4);
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
        BufferedImage edgeCrop = applySobel(crop);
        Point o = loupeOrigin(loupeAnchorLeft, loupeAnchorTop);
        paintLoupeBox(g2, crop, o.x, o.y, "4x", srcX, srcY);
        paintLoupeBox(g2, contrastCrop, o.x + LOUPE_SIZE + LOUPE_GAP, o.y, "4x, contrast boosted", srcX, srcY);
        paintLoupeBox(g2, edgeCrop, o.x, o.y + LOUPE_SIZE + LOUPE_GAP, "4x, Sobel edges", srcX, srcY);
    }

    /**
     * Cheap hover feedback for already-traced neighbors while tracing a new
     * region: on every mouse move, tests the cursor's image-space point
     * against each {@link #siblingPolygons} entry and, when the "which
     * region is under the cursor" answer changes, XOR-draws the old
     * outline away and the new one in — the same erase-by-redrawing-the-
     * identical-lines trick as the rubber-band guide, so this doesn't cost
     * a repaint of the (potentially huge) underlying image either. Purely
     * visual — doesn't affect tracing, selection, or what Commit saves.
     */
    private void updateHoverHighlight(Point panelPt) {
        int newHover = findSiblingAt(panelToImage(panelPt));
        if (newHover == hoveredSiblingIndex) {
            return;
        }
        if (hoveredSiblingIndex >= 0) {
            drawSiblingOutlineXor(hoveredSiblingIndex);
        }
        hoveredSiblingIndex = newHover;
        if (hoveredSiblingIndex >= 0) {
            drawSiblingOutlineXor(hoveredSiblingIndex);
        }
    }

    /**
     * @return the index into {@link #siblingPolygons} of the topmost (last
     * in list order, so a later Add wins over an older overlapping one)
     * polygon containing {@code imagePt}, or {@code -1} if none does
     */
    private int findSiblingAt(Point imagePt) {
        for (int i = siblingPolygons.size() - 1; i >= 0; i--) {
            if (containsPoint(siblingPolygons.get(i), imagePt)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Standard even-odd ray-casting point-in-polygon test — cheap enough to
     * run against every sibling on every mouse move given these polygons
     * only ever run to a few dozen vertices.
     */
    private boolean containsPoint(List<Point> polygon, Point p) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point pi = polygon.get(i);
            Point pj = polygon.get(j);
            if ((pi.y > p.y) != (pj.y > p.y)
                    && p.x < (pj.x - pi.x) * (double) (p.y - pi.y) / (pj.y - pi.y) + pi.x) {
                inside = !inside;
            }
        }
        return inside;
    }

    private void drawSiblingOutlineXor(int siblingIndex) {
        Graphics2D g2 = (Graphics2D) getGraphics();
        if (null == g2) {
            return;
        }
        List<Point> polygon = siblingPolygons.get(siblingIndex);
        g2.setXORMode(Color.WHITE);
        g2.setColor(HOVER_COLOR);
        g2.setStroke(HOVER_STROKE);
        Point prev = imageToPanel(polygon.get(polygon.size() - 1));
        for (Point v : polygon) {
            Point cur = imageToPanel(v);
            g2.drawLine(prev.x, prev.y, cur.x, cur.y);
            prev = cur;
        }
        g2.setPaintMode();
        g2.dispose();
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

    private static final int SOBEL_EDGE_THRESHOLD = 60;

    /**
     * Sobel gradient-magnitude edge map: the standard 3x3 Gx/Gy kernel pair
     * run on luminance, combined as {@code sqrt(Gx^2 + Gy^2)}. Luminance is
     * box-blurred first and the magnitude is thresholded to black-below-cutoff
     * before display — at native pixel resolution, unblurred vellum grain
     * produces gradients everywhere, burying real ink/vellum boundaries under
     * what reads as noise rather than lines; the blur+threshold turns this
     * into what it's meant to be, a mask of the actual edges, near-black
     * except right at a real transition. Border pixels (no full 3x3
     * neighborhood available) are left black.
     */
    private BufferedImage applySobel(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[][] gray = new int[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[x][y] = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            }
        }
        int[][] blurred = boxBlur3(gray, w, h);
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 1; x < w - 1; x++) {
            for (int y = 1; y < h - 1; y++) {
                int gx = -blurred[x - 1][y - 1] - 2 * blurred[x - 1][y] - blurred[x - 1][y + 1]
                        + blurred[x + 1][y - 1] + 2 * blurred[x + 1][y] + blurred[x + 1][y + 1];
                int gy = -blurred[x - 1][y - 1] - 2 * blurred[x][y - 1] - blurred[x + 1][y - 1]
                        + blurred[x - 1][y + 1] + 2 * blurred[x][y + 1] + blurred[x + 1][y + 1];
                int mag = Math.min(255, (int) Math.round(Math.sqrt((double) gx * gx + (double) gy * gy)));
                int shown = mag <= SOBEL_EDGE_THRESHOLD ? 0
                        : Math.min(255, (mag - SOBEL_EDGE_THRESHOLD) * 255 / (255 - SOBEL_EDGE_THRESHOLD));
                int rgb = (shown << 16) | (shown << 8) | shown;
                dst.setRGB(x, y, rgb);
            }
        }
        return dst;
    }

    /**
     * Simple 3x3 box blur (edge pixels reuse the nearest in-bounds value
     * rather than skipping), run before {@link #applySobel}'s gradient pass
     * to average out per-pixel vellum grain noise ahead of, not instead of,
     * real ink/vellum edges.
     */
    private int[][] boxBlur3(int[][] gray, int w, int h) {
        int[][] out = new int[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int sum = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = Math.max(0, Math.min(w - 1, x + dx));
                        int ny = Math.max(0, Math.min(h - 1, y + dy));
                        sum += gray[nx][ny];
                    }
                }
                out[x][y] = sum / 9;
            }
        }
        return out;
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
        g2.drawImage(displayImage, offX(), offY(), dispW(), dispH(), null);
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
            paintRotationPreview(g2);
        }
    }

    /**
     * Shows the traced polygon's own pixels — cropped to its bounding box
     * and masked outside the polygon via {@link BitSet2D#cropToPolygon} —
     * rotating live as the mouse wheel adjusts {@link #angle}, in a fixed
     * box in the canvas's top-center. This is what actually sets
     * {@link CatalogEntry.Region#angle}: seeing the figure itself turn
     * upright, not an abstract arrow next to it, which told the eye nothing
     * about whether a wonky wedge (no edge reliably means "up") was
     * correctly oriented.
     */
    private void paintRotationPreview(Graphics2D g2) {
        if (rotationPreviewDirty || null == rotationPreviewSource) {
            rotationPreviewSource = BitSet2D.cropToPolygon(image, vertices);
            rotationPreviewDirty = false;
        }
        int boxX = (getWidth() - ROTATION_PREVIEW_SIZE) / 2;
        int boxY = LOUPE_MARGIN;
        g2.setColor(ROTATION_PREVIEW_BG);
        g2.fillRect(boxX, boxY, ROTATION_PREVIEW_SIZE, ROTATION_PREVIEW_SIZE);
        g2.setColor(LOUPE_BORDER);
        g2.setStroke(BOUNDARY_STROKE);
        g2.drawRect(boxX, boxY, ROTATION_PREVIEW_SIZE, ROTATION_PREVIEW_SIZE);

        double srcW = rotationPreviewSource.getWidth();
        double srcH = rotationPreviewSource.getHeight();
        double cos = Math.abs(Math.cos(angle));
        double sin = Math.abs(Math.sin(angle));
        double rotatedW = srcW * cos + srcH * sin;
        double rotatedH = srcW * sin + srcH * cos;
        double fitScale = 0.85 * Math.min(
                ROTATION_PREVIEW_SIZE / rotatedW,
                ROTATION_PREVIEW_SIZE / rotatedH);
        Graphics2D pg = (Graphics2D) g2.create(boxX, boxY, ROTATION_PREVIEW_SIZE, ROTATION_PREVIEW_SIZE);
        pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        pg.translate(ROTATION_PREVIEW_SIZE / 2.0, ROTATION_PREVIEW_SIZE / 2.0);
        pg.rotate(angle);
        pg.scale(fitScale, fitScale);
        pg.translate(-rotationPreviewSource.getWidth() / 2.0, -rotationPreviewSource.getHeight() / 2.0);
        pg.drawImage(rotationPreviewSource, 0, 0, null);
        pg.dispose();

        g2.setFont(LOUPE_LABEL_FONT);
        g2.setColor(Color.BLACK);
        String label = "wheel rotates";
        g2.fillRect(boxX + 1, boxY + 1, g2.getFontMetrics().stringWidth(label) + 6, 14);
        g2.setColor(Color.WHITE);
        g2.drawString(label, boxX + 4, boxY + 11);
    }
}
