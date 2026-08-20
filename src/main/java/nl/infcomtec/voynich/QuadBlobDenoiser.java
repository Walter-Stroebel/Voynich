/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.infcomtec.voynich.quadmesh.BufferedImageSource;
import nl.infcomtec.voynich.quadmesh.DeltaECrit;
import nl.infcomtec.voynich.quadmesh.Quad;
import nl.infcomtec.voynich.quadmesh.QuadProcessor;

/**
 * Content-preserving denoiser for photographed vellum scans, via anchor-gated
 * region growing over a tight ΔE-gated {@link Quad} tree — see
 * {@code memory/project_quadtree_blob_denoise_prototype.md} for the full
 * validation history (two failed approaches — a global flatten and plain
 * pairwise union-find merge — before this one) and why this needed a real
 * validated algorithm rather than a naive "shrink the image" denoise.
 *
 * <p>
 * Two-pass design: {@link #buildTightTree} first builds a tight-ΔE
 * (validated default 2.0) {@link Quad} tree — every leaf uniform within a
 * sub-JND tolerance, so leaf boundaries are trustworthy signal, not noise.
 * {@link #growBlobs} then merges adjacent leaves at a looser ΔE (validated
 * default 5.0) via flood-fill, but — unlike a plain pairwise merge — every
 * candidate leaf is tested directly against its blob's ONE fixed seed
 * anchor, never against whichever neighbor is currently admitting it. This
 * is what stops colour drift from transitively chaining across a real
 * boundary (e.g. through an antialiased ink-stroke edge) one small hop at a
 * time — the actual bug in the first (rejected) union-find version.
 *
 * <p>
 * Validated once (Voynich f17r, full page and a 600×500 crop) at
 * tight=2.0/merge=5.0: text glyphs, petal spikes, stem veins, a stain blob,
 * and a faint wash all survived pixel-level zoom comparison intact — only
 * vellum grain noise visibly calmed. Not yet validated across page types
 * (dense text vs. illustration vs. circular-diagram pages) — see
 * {@code CatalogCli}'s {@code denoise} command for exposing these
 * parameters instead of hardcoding them, so that generalization question
 * can be explored without a code change.
 */
public class QuadBlobDenoiser {

    /**
     * One leaf's info pulled out of the {@link Quad} tree for
     * adjacency/merge work — a flat list is friendlier to iterate/index
     * than re-walking the tree for every lookup.
     */
    private static class Leaf {

        Rectangle rect;
        double[] lab;
    }

    /**
     * Builds a tight-ΔE {@link Quad} tree over {@code image} — every leaf's
     * pixels are within {@code tightDeltaE} CIE76 ΔE of the leaf's first
     * pixel (see {@link DeltaECrit}).
     */
    public static Quad buildTightTree(BufferedImage image, double tightDeltaE) {
        return new Quad(new BufferedImageSource(image), new DeltaECrit(tightDeltaE));
    }

    /**
     * Flattens {@code tree} into a leaf list, alongside a {@code Quad}→index
     * map that {@link #growBlobs}'s point-location neighbor probes need.
     */
    private static List<Leaf> collectLeaves(Quad tree, final Map<Quad, Integer> indexOf) {
        final List<Leaf> leaves = new ArrayList<Leaf>();
        tree.traverse(new QuadProcessor() {
            @Override
            public boolean process(Quad q) {
                Leaf l = new Leaf();
                l.rect = q.rect;
                l.lab = (double[]) q.userObject;
                indexOf.put(q, leaves.size());
                leaves.add(l);
                return true;
            }
        });
        return leaves;
    }

    /**
     * Point-location: walks down from root, at each internal node picking
     * whichever child's rect contains ({@code x},{@code y}) — not indexed
     * by quadrant position, since {@link Quad}'s {@code nodes} can be
     * 2-wide (odd width/height edge splits) as well as 4-wide, so
     * quadrant-index arithmetic isn't safe. O(depth), not O(n) — the fix
     * for an earlier O(n²) all-pairs neighbor search that timed out at
     * 251K leaves on a full page.
     *
     * @return the leaf {@link Quad} containing ({@code x},{@code y}), or
     * null if outside the tree (image edges, where a probe just past the
     * last pixel has no neighbor).
     */
    private static Quad locate(Quad root, int x, int y) {
        Quad q = root;
        if (!q.rect.contains(x, y)) {
            return null;
        }
        while (q.nodes != null) {
            Quad next = null;
            for (Quad child : q.nodes) {
                if (child.rect.contains(x, y)) {
                    next = child;
                    break;
                }
            }
            if (null == next) {
                return null;
            }
            q = next;
        }
        return q;
    }

    private static Integer neighborAt(Quad root, Map<Quad, Integer> indexOf, int x, int y) {
        Quad neighbor = locate(root, x, y);
        return null == neighbor ? null : indexOf.get(neighbor);
    }

    private static double deltaE(double[] a, double[] b) {
        double dl = a[0] - b[0];
        double da = a[1] - b[1];
        double db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /**
     * Anchor-gated region growing over {@code tree}'s leaves. Every blob
     * has ONE fixed anchor — its seed leaf's mean Lab, set once and never
     * updated — and every candidate leaf, no matter how many hops from the
     * seed, is tested directly against that same anchor, never against
     * whichever neighbor happens to have admitted it. Same discipline
     * {@link DeltaECrit} already uses for a single leaf's pixels, run one
     * level up over leaves — this is what prevents colour drift from
     * chaining transitively across a real boundary.
     *
     * <p>
     * Genuine flood-fill/BFS, not a static tree-adjacency lookup — a
     * candidate's admission depends on which blob (and thus which anchor)
     * is currently growing into it, so membership can't be decided in one
     * pass over precomputed pairs. This gives up the O(n·depth) speedup a
     * precomputed adjacency table would allow, a deliberate tradeoff.
     *
     * @param mergeTolerance ΔE tolerance from a blob's anchor.
     * @return a fresh {@link BufferedImage}, each blob filled with the
     * pixel-count-weighted mean Lab of its member leaves.
     */
    public static BufferedImage denoise(BufferedImage image, double tightDeltaE, double mergeTolerance) {
        Quad tree = buildTightTree(image, tightDeltaE);
        Map<Quad, Integer> indexOf = new HashMap<Quad, Integer>();
        List<Leaf> leaves = collectLeaves(tree, indexOf);

        int[] blobId = new int[leaves.size()];
        Arrays.fill(blobId, -1);
        int nextBlob = 0;
        for (int seedId = 0; seedId < leaves.size(); seedId++) {
            if (blobId[seedId] != -1) {
                continue;
            }
            double[] anchor = leaves.get(seedId).lab;
            int blob = nextBlob++;
            blobId[seedId] = blob;
            ArrayDeque<Integer> frontier = new ArrayDeque<Integer>();
            frontier.add(seedId);
            while (!frontier.isEmpty()) {
                int curId = frontier.poll();
                Rectangle r = leaves.get(curId).rect;
                int midY = r.y + r.height / 2;
                int midX = r.x + r.width / 2;
                Integer[] candidates = {
                    neighborAt(tree, indexOf, r.x + r.width, midY),
                    neighborAt(tree, indexOf, r.x - 1, midY),
                    neighborAt(tree, indexOf, midX, r.y + r.height),
                    neighborAt(tree, indexOf, midX, r.y - 1)
                };
                for (Integer candId : candidates) {
                    if (null == candId || blobId[candId] != -1) {
                        continue;
                    }
                    if (deltaE(leaves.get(candId).lab, anchor) <= mergeTolerance) {
                        blobId[candId] = blob;
                        frontier.add(candId);
                    }
                }
            }
        }

        return renderBlobs(leaves, blobId, image.getWidth(), image.getHeight());
    }

    /**
     * Renders each blob filled with the pixel-count-weighted mean Lab of
     * its member leaves.
     */
    private static BufferedImage renderBlobs(List<Leaf> leaves, int[] labels, int width, int height) {
        Map<Integer, double[]> sumByBlob = new HashMap<Integer, double[]>();
        Map<Integer, Long> countByBlob = new HashMap<Integer, Long>();
        for (int i = 0; i < leaves.size(); i++) {
            Leaf l = leaves.get(i);
            long area = (long) l.rect.width * l.rect.height;
            double[] sum = sumByBlob.get(labels[i]);
            if (null == sum) {
                sum = new double[3];
                sumByBlob.put(labels[i], sum);
                countByBlob.put(labels[i], 0L);
            }
            sum[0] += l.lab[0] * area;
            sum[1] += l.lab[1] * area;
            sum[2] += l.lab[2] * area;
            countByBlob.put(labels[i], countByBlob.get(labels[i]) + area);
        }
        Map<Integer, Integer> rgbByBlob = new HashMap<Integer, Integer>();
        for (Map.Entry<Integer, double[]> e : sumByBlob.entrySet()) {
            long count = countByBlob.get(e.getKey());
            double[] mean = {e.getValue()[0] / count, e.getValue()[1] / count, e.getValue()[2] / count};
            rgbByBlob.put(e.getKey(), EnhancedColor.fromCIELAB(mean[0], mean[1], mean[2]).getRGB());
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < leaves.size(); i++) {
            Leaf l = leaves.get(i);
            int rgb = rgbByBlob.get(labels[i]);
            int x1 = l.rect.x + l.rect.width;
            int y1 = l.rect.y + l.rect.height;
            for (int y = l.rect.y; y < y1; y++) {
                for (int x = l.rect.x; x < x1; x++) {
                    out.setRGB(x, y, rgb);
                }
            }
        }
        return out;
    }
}
