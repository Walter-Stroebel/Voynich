/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Segments individual figures out of one of the manuscript's circular ring
 * diagrams (never "zodiac" — see project notes): locates the diagram's outer
 * boundary circle directly in a full-resolution scan, profiles ink density
 * by radius from that center to find the figure-bearing ring bands, isolates
 * each figure as a connected ink blob within a band, then crops and rotates
 * each one so it stands upright (its outward radial direction points to the
 * top of the crop).
 *
 * <p>
 * This deliberately does not use the voynichese.com viewport coordinates
 * from {@code voynich_labels_spatial.json} — that coordinate system doesn't
 * map onto scan pixels by a simple scale factor (different aspect ratio).
 * All geometry here comes from the scan's own ink pattern instead.
 */
public class RingDiagramSegmenter {

    private final BufferedImage scan;
    private final int inkThreshold;

    public RingDiagramSegmenter(BufferedImage scan) {
        this.scan = scan;
        this.inkThreshold = computeInkThreshold();
    }

    public int getInkThreshold() {
        return inkThreshold;
    }

    /**
     * A circle in scan pixel coordinates.
     */
    public static class Circle {

        public final double cx, cy, r;

        public Circle(double cx, double cy, double r) {
            this.cx = cx;
            this.cy = cy;
            this.r = r;
        }

        @Override
        public String toString() {
            return String.format("Circle(cx=%.1f, cy=%.1f, r=%.1f)", cx, cy, r);
        }
    }

    /**
     * A contiguous range of radii where ink density stays above threshold —
     * candidate for "this is where a ring of figures sits", as opposed to a
     * thin thresholded strip which is more likely a boundary/baseline line.
     */
    public static class RadialBand {

        public final int rMin, rMax;

        public RadialBand(int rMin, int rMax) {
            this.rMin = rMin;
            this.rMax = rMax;
        }

        public int width() {
            return rMax - rMin;
        }

        @Override
        public String toString() {
            return String.format("Band[%d-%d, w=%d]", rMin, rMax, width());
        }
    }

    /**
     * One figure, found as a connected ink blob within a radial band.
     */
    public static class FigureBlob {

        public final int minX, minY, maxX, maxY;
        public final double centroidX, centroidY;
        public final int pixelCount;

        public FigureBlob(int minX, int minY, int maxX, int maxY, double centroidX, double centroidY, int pixelCount) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.centroidX = centroidX;
            this.centroidY = centroidY;
            this.pixelCount = pixelCount;
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }

        @Override
        public String toString() {
            return String.format("FigureBlob(centroid=%.0f,%.0f size=%dx%d px=%d)",
                    centroidX, centroidY, width(), height(), pixelCount);
        }
    }

    /**
     * Ink is defined relative to this scan's own background tone, not a
     * fixed value — vellum tone and staining vary too much page to page for
     * a fixed cutoff. Background is estimated as a high percentile over a
     * broad sparse sample of the whole image (excluding a border margin
     * that risks catching the scan's black photo-background rather than
     * vellum) — even a busy diagram page is majority blank vellum, so the
     * bulk of luminance values cluster there regardless of location.
     */
    private int computeInkThreshold() {
        int border = Math.min(scan.getWidth(), scan.getHeight()) / 15;
        List<Integer> samples = new ArrayList<>();
        for (int y = border; y < scan.getHeight() - border; y += 7) {
            for (int x = border; x < scan.getWidth() - border; x += 7) {
                int rgb = scan.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                samples.add((int) (0.299 * r + 0.587 * g + 0.114 * b));
            }
        }
        samples.sort(null);
        int background = samples.isEmpty() ? 200 : samples.get((int) (samples.size() * 0.7));
        return (int) (background * 0.72);
    }

    /**
     * Below this, a pixel is the scan's black photo-background outside the
     * vellum's ragged edge (measured ~8-10), not hand-drawn ink (measured
     * well above this even for dark strokes) — without this floor, larger
     * candidate circles were scoring higher purely by grazing the page
     * border rather than tracing the diagram.
     */
    private static final int BLACK_BACKGROUND_CEILING = 35;

    private boolean isInk(int x, int y) {
        if (x < 0 || y < 0 || x >= scan.getWidth() || y >= scan.getHeight()) {
            return false;
        }
        int rgb = scan.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int luminance = (int) (0.299 * r + 0.587 * g + 0.114 * b);
        return luminance < inkThreshold && luminance > BLACK_BACKGROUND_CEILING;
    }

    /**
     * Fraction of points sampled around a candidate circle's circumference
     * that land on ink (checked over a few pixels of radial tolerance, since
     * hand-drawn lines wobble).
     */
    public double circleScore(double cx, double cy, double r) {
        int samples = 720;
        int hits = 0;
        for (int i = 0; i < samples; i++) {
            double theta = 2 * Math.PI * i / samples;
            boolean found = false;
            for (int dr = -3; dr <= 3 && !found; dr++) {
                int x = (int) Math.round(cx + (r + dr) * Math.cos(theta));
                int y = (int) Math.round(cy + (r + dr) * Math.sin(theta));
                if (isInk(x, y)) {
                    found = true;
                }
            }
            if (found) {
                hits++;
            }
        }
        return (double) hits / samples;
    }

    /**
     * Locates the diagram's outer boundary circle with a bounded, coarse-to-
     * fine exhaustive grid search around a seed center/radius, maximizing
     * {@link #circleScore}. Deliberately not greedy hill-climbing — the
     * outer boundary here is a ring of separate cursive words with real
     * gaps between them, not a solid line, so the score landscape is noisy
     * enough that a greedy walk was observed wandering away from a visually
     * near-perfect seed toward a worse, merely-locally-better point.
     */
    public Circle calibrateBoundary(double seedCx, double seedCy, double seedR) {
        double cx = seedCx, cy = seedCy, r = seedR;
        double centerRange = seedR * 0.08, radiusRange = seedR * 0.15;
        double[] centerSteps = {centerRange / 5, centerRange / 20, centerRange / 80};
        double[] radiusSteps = {radiusRange / 5, radiusRange / 20, radiusRange / 80};
        for (int pass = 0; pass < centerSteps.length; pass++) {
            double cStep = centerSteps[pass], rStep = radiusSteps[pass];
            double bestCx = cx, bestCy = cy, bestR = r;
            double best = -1;
            for (double tx = cx - centerRange; tx <= cx + centerRange; tx += cStep) {
                for (double ty = cy - centerRange; ty <= cy + centerRange; ty += cStep) {
                    for (double tr = r - radiusRange; tr <= r + radiusRange; tr += rStep) {
                        double score = circleScore(tx, ty, tr);
                        if (score > best) {
                            best = score;
                            bestCx = tx;
                            bestCy = ty;
                            bestR = tr;
                        }
                    }
                }
            }
            cx = bestCx;
            cy = bestCy;
            r = bestR;
            centerRange = cStep;
            radiusRange = rStep;
        }
        return new Circle(cx, cy, r);
    }

    /**
     * Ink density (0-1) sampled around each radius from 0 to maxRadius,
     * centered on the given circle's center.
     */
    public double[] radialProfile(Circle center, int maxRadius) {
        double[] profile = new double[maxRadius + 1];
        int samples = 360;
        for (int r = 0; r <= maxRadius; r++) {
            int hits = 0;
            for (int i = 0; i < samples; i++) {
                double theta = 2 * Math.PI * i / samples;
                int x = (int) Math.round(center.cx + r * Math.cos(theta));
                int y = (int) Math.round(center.cy + r * Math.sin(theta));
                if (isInk(x, y)) {
                    hits++;
                }
            }
            profile[r] = (double) hits / samples;
        }
        return profile;
    }

    /**
     * Finds contiguous radius ranges where density exceeds threshold, merges
     * runs separated by a small gap (noise tolerance), and keeps only runs
     * wide enough to plausibly be a ring of figure bodies rather than a thin
     * drawn line.
     */
    public List<RadialBand> findFigureBands(double[] profile, double densityThreshold, int minBandWidth, int mergeGap) {
        List<int[]> raw = new ArrayList<>();
        int start = -1;
        for (int r = 0; r < profile.length; r++) {
            boolean above = profile[r] > densityThreshold;
            if (above && start < 0) {
                start = r;
            } else if (!above && start >= 0) {
                raw.add(new int[]{start, r - 1});
                start = -1;
            }
        }
        if (start >= 0) {
            raw.add(new int[]{start, profile.length - 1});
        }
        List<int[]> merged = new ArrayList<>();
        for (int[] run : raw) {
            if (!merged.isEmpty() && run[0] - merged.get(merged.size() - 1)[1] <= mergeGap) {
                merged.get(merged.size() - 1)[1] = run[1];
            } else {
                merged.add(run);
            }
        }
        List<RadialBand> bands = new ArrayList<>();
        for (int[] run : merged) {
            if (run[1] - run[0] >= minBandWidth) {
                bands.add(new RadialBand(run[0], run[1]));
            }
        }
        return bands;
    }

    /**
     * Expands a mask outward by radius r (Chebyshev distance) so nearby but
     * not-quite-touching ink strokes of the same hand-drawn figure become
     * one connected region — these are thin pen-outline drawings, not
     * filled shapes, so a figure's own strokes (barrel outline, body,
     * raised arm) are frequently a few pixels apart from each other.
     */
    private boolean[] dilate(boolean[] mask, int w, int h, int r) {
        boolean[] horiz = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = false;
                for (int dx = -r; dx <= r && !v; dx++) {
                    int nx = x + dx;
                    if (nx >= 0 && nx < w && mask[y * w + nx]) v = true;
                }
                horiz[y * w + x] = v;
            }
        }
        boolean[] out = new boolean[w * h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                boolean v = false;
                for (int dy = -r; dy <= r && !v; dy++) {
                    int ny = y + dy;
                    if (ny >= 0 && ny < h && horiz[ny * w + x]) v = true;
                }
                out[y * w + x] = v;
            }
        }
        return out;
    }

    /**
     * Connected-component search for ink blobs whose polar distance from
     * center falls within [rMin, rMax]. Connectivity is computed on a
     * dilated copy of the ink mask (see {@link #dilate}) so a figure's
     * separate pen strokes merge into one component, but each blob's
     * centroid/pixel count is measured from the original, undilated ink
     * only. 8-connected flood fill, iterative (stack-based) to avoid
     * recursion depth on large blobs.
     */
    public List<FigureBlob> findBlobsInBand(Circle center, double rMin, double rMax, int minPixels, int maxDimension, int dilateRadius) {
        int x0 = Math.max(0, (int) Math.floor(center.cx - rMax));
        int x1 = Math.min(scan.getWidth() - 1, (int) Math.ceil(center.cx + rMax));
        int y0 = Math.max(0, (int) Math.floor(center.cy - rMax));
        int y1 = Math.min(scan.getHeight() - 1, (int) Math.ceil(center.cy + rMax));
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        boolean[] original = new boolean[w * h];
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double dist = Math.hypot(x - center.cx, y - center.cy);
                if (dist >= rMin && dist <= rMax && isInk(x, y)) {
                    original[(y - y0) * w + (x - x0)] = true;
                }
            }
        }
        boolean[] mask = dilate(original, w, h, dilateRadius);
        boolean[] visited = new boolean[w * h];
        List<FigureBlob> blobs = new ArrayList<>();
        Deque<int[]> stack = new ArrayDeque<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int idx = (y - y0) * w + (x - x0);
                if (!mask[idx] || visited[idx]) {
                    continue;
                }
                long sumX = 0, sumY = 0;
                int count = 0;
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                visited[idx] = true;
                stack.push(new int[]{x, y});
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    int px = p[0], py = p[1];
                    if (original[(py - y0) * w + (px - x0)]) {
                        sumX += px;
                        sumY += py;
                        count++;
                        if (px < minX) minX = px;
                        if (px > maxX) maxX = px;
                        if (py < minY) minY = py;
                        if (py > maxY) maxY = py;
                    }
                    for (int ddy = -1; ddy <= 1; ddy++) {
                        for (int ddx = -1; ddx <= 1; ddx++) {
                            if (ddx == 0 && ddy == 0) continue;
                            int nx = px + ddx, ny = py + ddy;
                            if (nx < x0 || nx > x1 || ny < y0 || ny > y1) continue;
                            int nidx = (ny - y0) * w + (nx - x0);
                            if (mask[nidx] && !visited[nidx]) {
                                visited[nidx] = true;
                                stack.push(new int[]{nx, ny});
                            }
                        }
                    }
                }
                if (count >= minPixels && maxX - minX <= maxDimension && maxY - minY <= maxDimension) {
                    blobs.add(new FigureBlob(minX, minY, maxX, maxY,
                            (double) sumX / count, (double) sumY / count, count));
                }
            }
        }
        return blobs;
    }

    /**
     * Crops a window around a figure blob's centroid, rotated so the
     * direction from the diagram's center to that centroid points straight
     * up — i.e. assumes the figure stands radially (head outward), which
     * matches every ring diagram inspected so far.
     */
    public BufferedImage cropUpright(Circle center, FigureBlob blob, int outSize) {
        double dx = blob.centroidX - center.cx;
        double dy = blob.centroidY - center.cy;
        double angleFromUpDeg = Math.toDegrees(Math.atan2(dx, -dy));

        int patchSize = (int) Math.ceil(outSize * 1.5);
        int px0 = (int) Math.round(blob.centroidX - patchSize / 2.0);
        int py0 = (int) Math.round(blob.centroidY - patchSize / 2.0);
        px0 = Math.max(0, Math.min(px0, scan.getWidth() - patchSize));
        py0 = Math.max(0, Math.min(py0, scan.getHeight() - patchSize));
        BufferedImage patch = scan.getSubimage(px0, py0, patchSize, patchSize);

        BufferedImage rotated = new BufferedImage(patchSize, patchSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, patchSize, patchSize);
        AffineTransform xform = new AffineTransform();
        xform.translate(patchSize / 2.0, patchSize / 2.0);
        xform.rotate(-Math.toRadians(angleFromUpDeg));
        xform.translate(-patchSize / 2.0, -patchSize / 2.0);
        g.drawImage(patch, xform, null);
        g.dispose();

        int cropX = (patchSize - outSize) / 2;
        int cropY = (patchSize - outSize) / 2;
        return rotated.getSubimage(cropX, cropY, outSize, outSize);
    }

    /**
     * Finds blobs in every given band and writes each one's upright crop to
     * outDir, in parallel (capped at 8 concurrent tasks per Walter's steer —
     * one page's worth of crops doesn't need more than that).
     */
    public void segment(Circle boundary, List<RadialBand> bands, File outDir, int outSize, int minPixels, int maxDimension, int dilateRadius) throws InterruptedException {
        outDir.mkdirs();
        List<FigureBlob> allBlobs = new ArrayList<>();
        for (RadialBand band : bands) {
            List<FigureBlob> blobs = findBlobsInBand(boundary, band.rMin, band.rMax, minPixels, maxDimension, dilateRadius);
            System.out.println(band + " -> " + blobs.size() + " blobs");
            allBlobs.addAll(blobs);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, Math.max(1, allBlobs.size())));
        int i = 0;
        for (FigureBlob blob : allBlobs) {
            final int idx = i++;
            pool.submit(() -> {
                try {
                    BufferedImage crop = cropUpright(boundary, blob, outSize);
                    File out = new File(outDir, String.format("figure_%02d_%04.0f_%04.0f.png", idx, blob.centroidX, blob.centroidY));
                    ImageIO.write(crop, "png", out);
                    System.out.println("wrote " + out.getName() + " from " + blob);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.MINUTES);
    }

    public static void main(String[] args) throws Exception {
        File configFile = new File(System.getProperty("user.home"), ".infVoy.json");
        Config cfg = JSON.readValue(null, configFile, Config.class);
        File scanFile = new File(cfg.scanPath, "70v_(part).png");
        BufferedImage scan = ImageIO.read(scanFile);
        System.out.println("loaded " + scanFile + " " + scan.getWidth() + "x" + scan.getHeight());

        RingDiagramSegmenter seg = new RingDiagramSegmenter(scan);
        System.out.println("ink threshold: " + seg.getInkThreshold());

        double seedCx = scan.getWidth() * 0.515;
        double seedCy = scan.getHeight() * 0.425;
        double seedR = scan.getWidth() * 0.40;
        System.out.println("seed score: " + seg.circleScore(seedCx, seedCy, seedR));

        // Automatic boundary calibration (circleScore-maximizing search) was
        // abandoned: the left ~20-25% of every scan is page-binding shadow
        // texture that overlaps the diagram's own left edge, genuinely dark
        // enough to read as ink, and pulls any score-maximizing search
        // toward it. The seed above was placed by direct visual inspection
        // and confirmed a close match — using it as-is rather than letting
        // an optimizer "improve" it into the binding shadow.
        Circle boundary = new Circle(seedCx, seedCy, seedR);
        System.out.println("using seed boundary as-is: " + boundary + " score=" + seg.circleScore(boundary.cx, boundary.cy, boundary.r));

        // Radial ink-density profiling (see findFigureBands) turned out not
        // to distinguish figure bands from text/gap bands at all: figures
        // here are thin pen-outline drawings, not filled shapes, so their
        // average ink density is comparably low to the surrounding caption
        // text - there's no density peak to threshold on. These two bands
        // were instead read directly off a labeled ruler strip cropped
        // straight up from center (ff70v2 specifically) - see project notes.
        List<RadialBand> bands = new ArrayList<>();
        bands.add(new RadialBand(380, 600));
        bands.add(new RadialBand(760, 1100));
        System.out.println("bands (measured, not detected): " + bands);

        File outDir = new File("src/main/resources/stolfi/segments/70v2");
        seg.segment(boundary, bands, outDir, 420, 800, 550, 12);
        System.out.println("done");
    }
}
