/*
 * Copyright (c) 2017 by Walter Stroebel and InfComTec.
 * All rights reserved.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * A BitSet but in 2D.
 * <p>
 * The Sobel/edge-detect factory methods from the original (see
 * {@code nl.wers.library.images.BitSet2D} in the CodeLibrary project) were
 * dropped here — they depend on {@code BaseImage}/{@code PixelOperation},
 * a heavier image-processing wrapper not worth importing into this
 * repo for methods nothing here calls.
 *
 * @author walter
 */
public class BitSet2D extends BitSet implements Cloneable, Iterable<Point> {

    /**
     * Rasterizes a simple polygon directly into a new {@code BitSet2D},
     * without ever calling {@link Shape#contains}: that recomputes the
     * winding number from every edge on every call, which is fine once but
     * ruinous over millions of pixels (see {@link #createFromShape}, which
     * still pays that cost — this is the fast alternative for exactly that
     * case). Standard scanline fill instead: per row, find where each edge
     * crosses that row's centerline, sort the crossings, and bit-set the
     * paired-up spans between them (even-odd rule) using {@link BitSet}'s
     * native {@code set(fromIndex, toIndex)} range-set, not a per-pixel
     * loop.
     *
     * @param vertices polygon vertices in order; need not be explicitly
     * closed — the edge from the last vertex back to the first is implied.
     * Fewer than 3 vertices yields an empty {@code BitSet2D}.
     * @param width the resulting {@code BitSet2D}'s width
     * @param height the resulting {@code BitSet2D}'s height
     */
    public static BitSet2D createFromPolygon(List<Point> vertices, int width, int height) {
        BitSet2D ret = new BitSet2D(width, height);
        int n = vertices.size();
        if (n < 3) {
            return ret;
        }
        List<Double> crossings = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            double yc = y + 0.5;
            crossings.clear();
            for (int i = 0; i < n; i++) {
                Point a = vertices.get(i);
                Point b = vertices.get((i + 1) % n);
                if (a.y == b.y) {
                    continue;
                }
                if ((yc >= a.y && yc < b.y) || (yc >= b.y && yc < a.y)) {
                    double t = (yc - a.y) / (double) (b.y - a.y);
                    crossings.add(a.x + t * (b.x - a.x));
                }
            }
            Collections.sort(crossings);
            for (int i = 0; i + 1 < crossings.size(); i += 2) {
                int xStart = Math.max(0, (int) Math.ceil(crossings.get(i) - 0.5));
                int xEnd = Math.min(width - 1, (int) Math.floor(crossings.get(i + 1) - 0.5));
                if (xStart <= xEnd) {
                    ret.set(y * width + xStart, y * width + xEnd + 1);
                }
            }
        }
        return ret;
    }

    /**
     * @deprecated calls {@link Shape#contains} per pixel in {@code s}'s
     * bounding box (via {@link #copy}) — fine for a small/simple shape, but
     * see {@link #createFromPolygon} for anything the size of a full scan.
     */
    @Deprecated
    public static BitSet2D createFromShape(Shape s) {
        Rectangle r = s.getBounds();
        r.width += r.x;
        r.height += r.y;
        BitSet2D ret = new BitSet2D(r.width, r.height);
        ret.copy(s);
        return ret;
    }

    public Dimension getSize() {
        return new Dimension(width, height);
    }

    private final int height;
    private final int width;

    /**
     * Create a two dimensional BitSet.
     *
     * @param width X dimension.
     * @param height Y dimension.
     */
    public BitSet2D(final int width, final int height) {
        super(width * height);
        this.width = width;
        this.height = height;
    }

    /**
     * User-supplied continuation test for {@link #oilSpill}: called at each
     * frontier pixel to decide whether the spill keeps spreading through it.
     */
    public interface BitSetConditions {

        boolean set(int x, int y);
    }

    /**
     * Simulates an oil spill, starting at x,y.
     *
     * @param x Origin x.
     * @param y Origin y.
     * @param c User supplied conditions when to continue spreading and when to
     * stop.
     */
    public void oilSpill(int x, int y, BitSetConditions c) {
        BitSet2D test = new BitSet2D(width, height);
        test.set2D(x, y);
        oilSpill(test, c);
    }

    private void oilSpill(BitSet2D test, BitSetConditions c) {
        BitSet2D tested = new BitSet2D(width, height);
        BitSet2D ntest;
        do {
            ntest = new BitSet2D(width, height);
            System.out.println("Need to test " + test.cardinality());
            for (Point p : test) {
                tested.set2D(p.x, p.y);
                if (c.set(p.x, p.y)) {
                    set2D(p.x, p.y);
                    if (p.x > 0) {
                        if (!tested.get2D(p.x - 1, p.y)) {
                            ntest.set2D(p.x - 1, p.y);
                        }
                    }
                    if (p.x < width - 1) {
                        if (!tested.get2D(p.x + 1, p.y)) {
                            ntest.set2D(p.x + 1, p.y);
                        }
                    }
                    if (p.y > 0) {
                        if (!tested.get2D(p.x, p.y - 1)) {
                            ntest.set2D(p.x, p.y - 1);
                        }
                    }
                    if (p.y < height - 1) {
                        if (!tested.get2D(p.x, p.y + 1)) {
                            ntest.set2D(p.x, p.y + 1);
                        }
                    }
                }
            }
            test.clear();
            test.or(ntest);
        } while (!test.isEmpty());
    }

    public BitSet2D grow(final int byN) {
        int n = Math.max(byN, 0);
        BitSet2D ret = this;
        while (n > 0) {
            ret = ret.grow();
            n--;
        }
        return ret;
    }

    public BitSet2D grow() {
        BitSet2D ret = new BitSet2D(width, height);
        for (Point p : this) {
            ret.set2D3(p.x, p.y);
        }
        return ret;
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public synchronized Object clone() {
        BitSet2D ret = new BitSet2D(width, height);
        ret.or(this);
        return ret;
    }

    /**
     * Create an inverted (complemented) BitSet2D.
     *
     * @return A BitSet2D with all bits flipped from this BitSet2D.
     */
    public synchronized BitSet2D invert() {
        BitSet2D ret = new BitSet2D(width, height);
        ret.or(this);
        ret.flip(0, width * height);
        return ret;
    }

    /**
     * Reset the bit
     *
     * @param p X and Y.
     */
    public void clear(final Point p) {
        clear(p.y * width + p.x);
    }

    /**
     * Reset the bit
     *
     * @param x X coordinate.
     * @param y Y coordinate.
     */
    public void clear2D(final int x, final int y) {
        clear(y * width + x);
    }

    /**
     * Flip the bit
     *
     * @param x X coordinate.
     * @param y Y coordinate.
     */
    public void flip2D(final int x, final int y) {
        flip(y * width + x);
    }

    /**
     * Translate index to X and Y
     *
     * @param idx Index.
     * @return X and Y.
     */
    public Point fromIndex(final int idx) {
        return new Point(idx % width, idx / width);
    }

    public boolean get(final Point p) {
        return get(p.y * width + p.x);
    }

    public boolean getSet(int x, int y) {
        return get(y * width + x);
    }

    public boolean get2D(final int x, final int y) {
        return get(y * width + x);
    }

    /**
     * Fairly unusual method: if either x or y is out of bounds, wrap to the
     * other side.
     *
     * @param x Interpreted as (x+width)%width.
     * @param y Interpreted as (y+height)%height.
     * @return Bit at that position.
     */
    public boolean wrap2D(final int x, final int y) {
        int wx = (x + width) % width;
        int wy = (y + height) % height;
        return get(wy * width + wx);
    }

    /**
     * Fairly unusual method: if either x or y is out of bounds, wrap to the
     * other side.
     *
     * @param x Interpreted as (x+width)%width.
     * @param y Interpreted as (y+height)%height.
     * @param bit Set bit if true, clear if false.
     */
    public void wrap2D(final int x, final int y, final boolean bit) {
        int wx = (x + width) % width;
        int wy = (y + height) % height;
        if (bit) {
            set2D(wx, wy);
        } else {
            clear2D(wx, wy);
        }
    }

    /**
     * @return the height
     */
    public int getHeight() {
        return height;
    }

    /**
     * @return the width
     */
    public int getWidth() {
        return width;
    }

    /**
     * @return new Rectangle(width, height)
     */
    public Rectangle getBounds() {
        return new Rectangle(width, height);
    }

    /**
     * Scans the BitSet2D for smallest and largest set(x,y), returning the
     * resulting Rectangle.
     * <p>
     * Note that this method might be relatively costly.</p>
     *
     * @return new Rectangle(firstIndex, lastIndex)
     */
    public synchronized Rectangle getRealBounds() {
        if (isEmpty()) {
            return new Rectangle();
        }
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = 0;
        int y1 = 0;
        for (Point p : this) {
            x0 = Math.min(x0, p.x);
            x1 = Math.max(x1, p.x);
            y0 = Math.min(y0, p.y);
            y1 = Math.max(y1, p.y);
        }
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /**
     * Calculate index for X and Y.
     *
     * @param x X.
     * @param y Y.
     * @return Index.
     */
    public int index(final int x, final int y) {
        return y * width + x;
    }

    /**
     * Calculate index for X and Y.
     *
     * @param p Coordinates.
     * @return Index.
     */
    public int index(final Point p) {
        return p.y * width + p.x;
    }

    public void set(final Point p) {
        set(p.y * width + p.x);
    }

    /**
     * Set (add/remove) all points contained in the shape.
     *
     * <p>
     * Note that if the Shape is larger than this BitSet2D in any dimension, it
     * may contain some bits that will not be copied.</p>
     *
     * @param shape Shape with points.
     */
    public void set(final Shape shape) {
        setOrClear(shape, true);
    }

    /**
     * Set (add/remove) all points contained in the shape.
     *
     * <p>
     * Note that if the Shape is larger than this BitSet2D in any dimension, it
     * may contain some bits that will not be copied.</p>
     *
     * @param shape Shape with points.
     * @param set set if true, clear if false
     */
    public synchronized void setOrClear(final Shape shape, boolean set) {
        Rectangle b = shape.getBounds();
        for (int x = b.x; x < b.x + b.width; x++) {
            for (int y = b.y; y < b.y + b.height; y++) {
                if (shape.contains(x, y)) {
                    if (set) {
                        set2D(x, y);
                    } else {
                        clear2D(x, y);
                    }
                }
            }
        }
    }

    /**
     * Set and reset all points contained in the area.
     *
     * <p>
     * Note that if the Shape is larger than this BitSet2D in any dimension, it
     * may contain some bits that will not be copied.</p>
     *
     * @param shape Area with points.
     */
    public synchronized void copy(final Shape shape) {
        Rectangle b = shape.getBounds();
        for (int x = b.x; x < b.x + b.width; x++) {
            for (int y = b.y; y < b.y + b.height; y++) {
                if (shape.contains(x, y)) {
                    set2D(x, y);
                } else {
                    clear2D(x, y);
                }
            }
        }
    }

    /**
     * Set the bit, growing the set if needed.
     * <p>
     * Note that this <b>will</b> work for negative values of x and y,
     * effectively translating the current BitSet2D.</p>
     *
     * @param x X, may be &lt;0
     * @param y Y, may be &lt;0
     * @return Same or new set with the bit set.
     */
    public synchronized BitSet2D add2D(final int x, final int y) {
        BitSet2D ret;
        int sx = x;
        int sy = y;
        int nw = width;
        int nh = height;
        if (sx < 0) {
            nw += -sx;
            sx = 0;
        }
        if (sy < 0) {
            nh += -sy;
            sy = 0;
        }
        if (sx >= nw) {
            nw = sx + 1;
        }
        if (sy >= nh) {
            nh = sy + 1;
        }
        if (nw != width || nh != height) {
            ret = new BitSet2D(nw, nh);
            for (Point p : this) {
                ret.set2D(p.x, p.y);
            }
        } else {
            ret = this;
        }
        ret.set2D(sx, sy);
        return ret;
    }

    private int clampX(int x) {
        if (x < 0) {
            return 0;
        }
        if (x > width - 1) {
            return width - 1;
        }
        return x;
    }

    private int clampY(int y) {
        if (y < 0) {
            return 0;
        }
        if (y > height - 1) {
            return height - 1;
        }
        return y;
    }

    /**
     * Get number of neighbors inclusive.
     *
     * @param x X, wrapped.
     * @param y Y, wrapped.
     * @return 9 if all bits x-1,y-1 to x+1,y+1 are set.
     */
    public int get2D3(final int x, final int y) {
        int ret = 0;
        for (int wx = x - 1; wx < x + 2; wx++) {
            for (int wy = y - 1; wy < y + 2; wy++) {
                if (wrap2D(wx, wy)) {
                    ret++;
                }
            }
        }
        return ret;
    }

    /**
     * Set bit and neighbors.
     *
     * @param x X, clamped.
     * @param y Y, clamped.
     */
    public void set2D3(final int x, final int y) {
        int x1 = clampX(x - 1);
        int x2 = clampX(x + 1);
        int y1 = clampY(y - 1);
        int y2 = clampY(y + 1);
        for (int sx = x1; sx <= x2; sx++) {
            for (int sy = y1; sy <= y2; sy++) {
                set2D(sx, sy);
            }
        }
    }

    public void set2D(final int x, final int y) {
        set(y * width + x);
    }

    /**
     * Create a binary image with set points as WHITE and reset points as BLACK.
     * <p>
     * Note that this method is <b>not</b> optimized, using it on huge BitSet2D
     * instances will be costly.</p>
     *
     * @return A binary image.
     */
    public synchronized BufferedImage toImage() {
        BufferedImage ret = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        final int W = Color.WHITE.getRGB();
        for (Point p : this) {
            ret.setRGB(p.x, p.y, W);
        }
        return ret;
    }

    /**
     * Draw 1x1 rectangles in color c using gr.
     * <p>
     * Note that this method is <b>not</b> optimized, using it on huge BitSet2D
     * instances will be costly.</p>
     * <p>
     * Note that gr current color is not preserved.</p>
     *
     * @param gr Graphics instance to draw with.
     * @param c color use for fillRect(1,1).
     */
    public synchronized void draw(Graphics gr, Color c) {
        gr.setColor(c);
        for (Point p : this) {
            gr.fillRect(p.x, p.y, 1, 1);
        }
    }

    /**
     * Draw 1x1 rectangles in color obtained from img using gr.
     * <p>
     * Note that this method is <b>not</b> optimized, using it on huge BitSet2D
     * instances will be costly.</p>
     * <p>
     * Note that gr current color is not preserved.</p>
     *
     * @param gr Graphics instance to draw with.
     * @param img Image to use as source. Must have the same or larger
     * dimensions.
     */
    public synchronized void draw(Graphics gr, BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (Point p : this) {
            if (p.x < w && p.y < h) {
                gr.setColor(new Color(img.getRGB(p.x, p.y)));
                gr.fillRect(p.x, p.y, 1, 1);
            }
        }
    }

    /**
     * Set the alpha of all pixels to ifSet that have a bit set and set the
     * alpha of all pixels to ifClear that have a cleared bit.
     * <p>
     * Note that this method is <b>not</b> optimized, using it on huge BitSet2D
     * instances (or images) will be costly.</p>
     *
     * @param source Image to process.
     * @param ifSet Alpha value between 0 and 255 if the corresponding bit is
     * set.
     * @param ifClear Alpha value between 0 and 255 if the corresponding bit is
     * cleared.
     * @return An image with alpha.
     */
    public synchronized BufferedImage toAlpha(BufferedImage source, final int ifSet, final int ifClear) {
        final int s = ifSet << 24;
        final int c = ifClear << 24;
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage ret = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height && y < h; y++) {
            for (int x = 0; x < width && x < w; x++) {
                if (get2D(x, y)) {
                    ret.setRGB(x, y, (source.getRGB(x, y) & 0xFFFFFF) | s);
                } else {
                    ret.setRGB(x, y, (source.getRGB(x, y) & 0xFFFFFF) | c);
                }
            }
        }
        return ret;
    }

    /**
     * @deprecated use getRealBounds.
     * @return see getRealBounds.
     */
    @Deprecated
    public Rectangle getOutline() {
        return getRealBounds();
    }

    /**
     * Could create huge lists of newly created Point objects. Does allow to
     * alter a BitSet2D in place.
     *
     * @return List of all set bits as Point objects.
     */
    public LinkedList<Point> setToPoints() {
        LinkedList<Point> ret = new LinkedList<>();
        for (Point p : this) {
            ret.add(p);
        }
        return ret;
    }

    @Override
    public Iterator<Point> iterator() {
        return new Iterator<Point>() {
            private int idx = nextSetBit(0);

            @Override
            public boolean hasNext() {
                return idx >= 0;
            }

            @Override
            public Point next() {
                Point ret = new Point(idx % width, idx / width);
                idx = nextSetBit(idx + 1);
                return ret;
            }
        };
    }

    /**
     * Rasterizes {@code polygon} via {@link #createFromPolygon} at
     * {@code image}'s own size and returns a copy of {@code image} with
     * every pixel outside it dimmed to a quarter brightness — the shared
     * "everything but the traced content" visualization behind both
     * {@code CatalogEntryEditor}'s "Show Mask" toggle (full-resolution scans)
     * and {@code OverviewPanel}'s content-area toggle (thumbnails). Cheap
     * enough at either size to call fresh on every use rather than caching:
     * a scanline fill plus one pass over the pixels, no per-pixel
     * {@link Shape#contains} cost.
     *
     * @param image the image to dim outside {@code polygon}; not mutated
     * @param polygon vertices in {@code image}'s own pixel coordinates —
     * fewer than 3 dims the whole image (an empty/untraced polygon means
     * "nothing confirmed as content yet", not "confirmed empty")
     * @return a new, dimmed-outside-the-polygon copy of {@code image}
     */
    public static BufferedImage darkenOutside(BufferedImage image, List<Point> polygon) {
        int w = image.getWidth();
        int h = image.getHeight();
        BitSet2D mask = createFromPolygon(polygon, w, h);
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                if (!mask.get2D(x, y)) {
                    pixels[rowOffset + x] = darken(pixels[rowOffset + x]);
                }
            }
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

    /**
     * @return {@code rgb} at a quarter brightness, for dimming everything a
     * traced content-area polygon excludes
     */
    private static int darken(int rgb) {
        int r = ((rgb >> 16) & 0xFF) / 4;
        int g = ((rgb >> 8) & 0xFF) / 4;
        int b = (rgb & 0xFF) / 4;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Crops {@code full} to {@code polygon}'s bounding box, then blacks out
     * every pixel inside that box but outside the polygon itself — blank
     * vellum, backdrop, frayed edge, other pages in the stack, whatever the
     * trace excluded. Shared by {@code CatalogCli --content-area} and
     * {@code RegionManagerDialog}'s "View" action, both of which only need
     * the picture, not which pixels are real content — for that (e.g.
     * {@code CatalogEntryEditor}'s region-scoped "Color Frequency"/"ΔE
     * Heatmap", which must exclude the blacked-out corners from analysis
     * rather than just visually hide them) use {@link #cropAndMaskPolygon}
     * instead. Uses {@link #createFromPolygon} for the mask, never
     * {@code Shape.contains()} — see the class doc.
     *
     * @param full the source image
     * @param polygon vertices in {@code full}'s own pixel coordinates; must
     * have at least 3 vertices
     * @return a new, smaller {@code BufferedImage} — just the polygon's
     * bounding box, masked
     */
    public static BufferedImage cropToPolygon(BufferedImage full, List<Point> polygon) {
        return cropAndMaskPolygon(full, polygon).image;
    }

    /**
     * Same crop-to-bounding-box-and-black-out-outside-the-polygon as
     * {@link #cropToPolygon}, but also returns the polygon mask in the
     * crop's own local coordinates (not {@code full}'s) — so a caller can
     * tell "real content" from "blacked-out corner" pixel-for-pixel without
     * re-deriving the bounding box or re-running {@link #createFromPolygon}
     * itself. First consumer: {@code ColorImage}'s masked constructor, used
     * by {@code CatalogEntryEditor}'s region-scoped colour analysis so a
     * masked-out corner never pollutes a frequency count or a ΔE reference
     * mean the way it would if it were just painted black and analysed like
     * any other pixel.
     *
     * @param full the source image
     * @param polygon vertices in {@code full}'s own pixel coordinates; must
     * have at least 3 vertices
     * @return the cropped, masked image plus a same-size {@link BitSet2D}
     * that's set wherever that image's pixel is real (inside the polygon)
     */
    public static Crop cropAndMaskPolygon(BufferedImage full, List<Point> polygon) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Point v : polygon) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
        }
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(full.getWidth() - 1, maxX);
        maxY = Math.min(full.getHeight() - 1, maxY);
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;

        BitSet2D fullMask = createFromPolygon(polygon, full.getWidth(), full.getHeight());
        BitSet2D localMask = new BitSet2D(w, h);
        int[] pixels = full.getRGB(minX, minY, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                if (fullMask.get2D(minX + x, minY + y)) {
                    localMask.set2D(x, y);
                } else {
                    pixels[rowOffset + x] = 0xFF000000;
                }
            }
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return new Crop(out, localMask);
    }

    /**
     * The result of {@link #cropAndMaskPolygon}: a cropped image plus a
     * same-size mask distinguishing real content pixels from the
     * blacked-out corners the bounding-box crop necessarily includes.
     */
    public static final class Crop {

        public final BufferedImage image;
        public final BitSet2D mask;

        private Crop(BufferedImage image, BitSet2D mask) {
            this.image = image;
            this.mask = mask;
        }
    }
}
