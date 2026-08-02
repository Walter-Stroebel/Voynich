/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Panel;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * A decoded image paired with its colour analysis and load timing.
 * <p>
 * On construction the image file is read, each pixel is passed through
 * {@link ColorBase#add(int)}, and the raw ARGB value in {@link #pixels} is
 * replaced with the alpha-premultiplied absolute RGB returned by that call.
 * The owning {@link #cb} therefore reflects the complete colour inventory of
 * the image: {@code cb.cache.size()} yields the number of distinct colours
 * (after alpha pre-multiplication) and each {@link ColorBase.TriLabColor}
 * entry carries the per-colour pixel count.
 * </p>
 * <p>
 * Construction time is recorded internally as nanoseconds elapsed between the
 * first and last statement of the constructor; {@link #toString()} reports it
 * together with the image dimensions and colour count.
 * </p>
 */
public class ColorImage {

    /**
     * Fixed edge length, in pixels, of {@link #thumbnail} and
     * {@link #labThumbnail}. 256 follows the freedesktop.org thumbnail-spec
     * "large" tier, the same convention Linux desktop environments use for
     * their own thumbnail caches — chosen so this isn't a bespoke size, and
     * so a single 4K monitor comfortably tiles a contact sheet of them.
     */
    public static final int THUMB_SIZE = 256;

    /**
     * Dummy AWT component required by {@link MediaTracker}'s constructor; it
     * is never added to a container or shown. Shared across instances since
     * {@code MediaTracker} only uses it as an {@link java.awt.image.ImageObserver}
     * context, not mutable state.
     */
    private static final Panel TRACKER_COMPONENT = new Panel();

    /**
     * The colour base holding the per-image cache and pixel counts.
     */
    public ColorBase cb = new ColorBase();
    /**
     * Image width in pixels.
     */
    public int w;
    /**
     * Image height in pixels.
     */
    public int h;
    /**
     * Flat pixel array in row-major order, length {@code w * h}.
     * <p>
     * Each element is the alpha-premultiplied absolute RGB value returned by
     * {@link ColorBase#add(int)} for the corresponding source pixel. The
     * alpha channel is cleared; components occupy bits 23–16 (R), 15–8 (G),
     * and 7–0 (B).
     * </p>
     */
    public int[] pixels;
    /**
     * Wall-clock nanoseconds consumed by the constructor.
     */
    public long loadNanos;
    /**
     * CIELab-keyed index of all colours in this image.
     * <p>
     * Keyed by a {@link TriElm} with v0=L*×100, v1=a*×100, v2=b*×100,
     * allowing efficient L*-range pre-filtering via {@link TreeMap#subMap}
     * before computing full ΔE distances. Built once after the pixel scan;
     * use this as the basis for nearest-neighbour and merge operations.
     * </p>
     */
    public TreeMap<TriElm, ColorBase.TriLabColor> labIndex;

    /**
     * A {@link #THUMB_SIZE}×{@link #THUMB_SIZE} RGB thumbnail of the source
     * image, generated once at construction and kept in memory only (not
     * persisted to disk).
     * <p>
     * The source is scaled with {@link Image#getScaledInstance} to fit
     * within {@code THUMB_SIZE × THUMB_SIZE} preserving aspect ratio
     * ("contain", not "cover" — no cropping), then centred on a black
     * canvas. Black padding is deliberate, not arbitrary: it matches what
     * the physical scanbed already looks like outside the page edges in a
     * real scan, so the letterbox doesn't introduce synthetic colour data.
     * </p>
     */
    public BufferedImage thumbnail;

    /**
     * The CIELab conversion of every pixel in {@link #thumbnail}, flattened
     * row-major (index {@code y * THUMB_SIZE + x}).
     * <p>
     * Each entry comes from {@link ColorBase#resolve(Color)}, which only
     * touches the static, cross-instance {@link ColorBase#convert} cache —
     * it does not register into this image's own {@link #cb} inventory or
     * affect any {@link ColorBase.TriLabColor#count}. Thumbnail pixels are
     * interpolated blends produced by scaling, not real pixels the image
     * actually contains, so they belong in a separate structure from
     * {@link #labIndex}, which must stay a faithful colour inventory of the
     * source.
     * </p>
     */
    public ColorBase.TriLabColor[] labThumbnail;

    /**
     * Reads an image file, builds the colour inventory, and records the
     * elapsed construction time in {@link #loadNanos}.
     *
     * @param f the image file to read; any format supported by
     * {@link ImageIO} is accepted
     * @throws IOException if the file cannot be read or decoded
     */
    public ColorImage(File f) throws IOException {
        long t0 = System.nanoTime();
        BufferedImage img = ImageIO.read(f);
        w = img.getWidth();
        h = img.getHeight();
        pixels = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = cb.add(pixels[i]);
        }
        labIndex = new TreeMap<>();
        for (ColorBase.TriLabColor tc : cb.cache.values()) {
            TriElm key = new TriElm();
            key.v0 = tc.l;
            key.v1 = tc.a;
            key.v2 = tc.b;
            ColorBase.TriLabColor col = labIndex.get(key);
            if (null != col) {
                ColorBase.TriLabColor nw = new ColorBase.TriLabColor();
                nw.a = tc.a;
                nw.b = tc.b;
                nw.count = col.count + tc.count;
                nw.l = tc.l;
                nw.v0 = col.v0;
                nw.v1 = col.v1;
                nw.v2 = col.v2;
                labIndex.put(key, nw);
            } else {
                labIndex.put(key, tc);
            }
        }
        buildThumbnails(img, f);
        loadNanos = System.nanoTime() - t0;
    }

    /**
     * Scales {@code img} to fit within {@link #THUMB_SIZE}×{@link #THUMB_SIZE}
     * (preserving aspect ratio, black-padded) and populates {@link #thumbnail}
     * and {@link #labThumbnail}.
     * <p>
     * Called once from the constructor while the freshly decoded image is
     * still on hand — deliberately not from a lazily-invoked getter, so no
     * field ever needs to retain the full-resolution {@code BufferedImage}
     * just to derive a thumbnail from it later.
     * </p>
     *
     * @param img the freshly decoded source image
     * @param f the source file, used only for the exception message on
     * interruption
     * @throws IOException if thumbnail scaling is interrupted
     */
    private void buildThumbnails(BufferedImage img, File f) throws IOException {
        double scale = Math.min((double) THUMB_SIZE / w, (double) THUMB_SIZE / h);
        int scaledW = Math.max(1, (int) Math.round(w * scale));
        int scaledH = Math.max(1, (int) Math.round(h * scale));
        Image scaled = img.getScaledInstance(scaledW, scaledH, Image.SCALE_SMOOTH);
        MediaTracker tracker = new MediaTracker(TRACKER_COMPONENT);
        tracker.addImage(scaled, 0);
        try {
            tracker.waitForID(0);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while scaling thumbnail for " + f, ie);
        }
        BufferedImage thumb = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = thumb.createGraphics();
        // TYPE_INT_RGB pixels default to 0 (black), already our letterbox fill.
        int offX = (THUMB_SIZE - scaledW) / 2;
        int offY = (THUMB_SIZE - scaledH) / 2;
        g2.drawImage(scaled, offX, offY, null);
        g2.dispose();
        thumbnail = thumb;

        labThumbnail = new ColorBase.TriLabColor[THUMB_SIZE * THUMB_SIZE];
        int[] thumbPixels = thumb.getRGB(0, 0, THUMB_SIZE, THUMB_SIZE, null, 0, THUMB_SIZE);
        for (int i = 0; i < thumbPixels.length; i++) {
            labThumbnail[i] = ColorBase.resolve(new Color(thumbPixels[i]));
        }
    }

    /**
     * Mean per-cell CIELab ΔE (CIE76) between this image's and
     * {@code other}'s {@link #labThumbnail}.
     * <p>
     * Both thumbnails are already reduced to the same fixed
     * {@link #THUMB_SIZE}×{@link #THUMB_SIZE} grid, and each cell is already
     * an aggressive spatial mean of whatever source pixels
     * {@link Image#getScaledInstance} blended into it. That means cell
     * {@code i} in one thumbnail and cell {@code i} in the other already
     * refer to the same relative position on the page — no nearest-neighbour
     * matching or histogram weighting is needed, just a straight sum over
     * {@link ColorBase#deltaE(ColorBase.TriLabColor, ColorBase.TriLabColor)}.
     * </p>
     * <p>
     * 0 = identical thumbnails; ~2.3 = a just-noticeable per-cell difference
     * under D65 illuminant, same scale as {@link ColorBase#deltaE}.
     * </p>
     *
     * @param other the image to compare against
     * @return mean CIE76 ΔE across all {@link #THUMB_SIZE}×{@link #THUMB_SIZE}
     * cells
     */
    public double distanceTo(ColorImage other) {
        double tally = 0;
        for (int i = 0; i < labThumbnail.length; i++) {
            tally += ColorBase.deltaE(labThumbnail[i], other.labThumbnail[i]);
        }
        return tally / labThumbnail.length;
    }

    /**
     * Returns a human-readable summary of this image: dimensions, unique
     * colour count, total pixel count, and load time.
     * <p>
     * Example output:
     * </p>
     * <pre>1920x1080, 42318 unique colours, 2073600 pixels, 143 ms</pre>
     *
     * @return formatted summary string
     */
    @Override
    public String toString() {
        long ms = loadNanos / 1_000_000L;
        long us = (loadNanos % 1_000_000L) / 1_000L;
        return ms > 0
                ? String.format("%dx%d, %d unique colours, %d pixels, %d ms", w, h, cb.cache.size(), pixels.length, ms)
                : String.format("%dx%d, %d unique colours, %d pixels, %d µs", w, h, cb.cache.size(), pixels.length, us);
    }

    /**
     * Returns all colours in this image sorted by ascending pixel count
     * (rarest first). Callers wanting dominant colours first should call
     * {@link java.util.Collections#reverse(java.util.List)} on the result.
     * <p>
     * Colours with equal counts are ordered by their natural RGB ordering
     * (v0, v1, v2) for deterministic results.
     * </p>
     *
     * @return a new {@link List} of every {@link ColorBase.TriLabColor} in
     * {@link ColorBase#cache}, sorted by {@link ColorBase.TriLabColor#count}
     * ascending
     */
    public List<ColorBase.TriLabColor> sortedByFrequency() {
        List<ColorBase.TriLabColor> list = new ArrayList<>(cb.cache.values());
        list.sort(new Comparator<ColorBase.TriLabColor>() {
            @Override
            public int compare(ColorBase.TriLabColor o1, ColorBase.TriLabColor o2) {
                int cmp = Long.compare(o1.count, o2.count);
                if (0 == cmp) {
                    return o1.compareTo(o2);
                }
                return cmp;
            }
        });
        return list;
    }

}
