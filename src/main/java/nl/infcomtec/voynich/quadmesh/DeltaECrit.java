/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich.quadmesh;

import java.awt.Rectangle;
import java.awt.Shape;

/**
 * This criterion is met when every pixel in the shape is within
 * {@link #limit} CIE76 ΔE of the first pixel encountered. Reference is the
 * first sample only (not a mean) — cheap, single-pass, and the tree still
 * converges to uniform leaves regardless of which pixel a split happens to
 * start from. Ported from infimg's {@code quadmesh} package — see
 * {@link ImageSource}'s doc for why.
 *
 * <p>
 * CIE76 ΔE = sqrt((ΔL*)² + (Δa*)² + (Δb*)²) on unscaled L*a*b* values.
 * 0 = identical; ~2.3 = a just-noticeable difference under D65.
 */
public class DeltaECrit implements ShapeCriterion<double[]> {

    /**
     * Max CIE76 ΔE from the reference pixel before the region is split.
     */
    public final double limit;

    /**
     * @param limit max CIE76 ΔE from the first pixel before splitting.
     */
    public DeltaECrit(double limit) {
        this.limit = limit;
    }

    @Override
    public double[] criterionMet(ImageSource im, Shape s) {
        Rectangle r = s.getBounds();
        double[] ref = null;
        double[] cur = new double[3];
        for (int x = r.x; x < r.x + r.width; x++) {
            for (int y = r.y; y < r.y + r.height; y++) {
                if (s.contains(x, y)) {
                    im.getLab(x, y, cur);
                    if (ref == null) {
                        ref = cur.clone();
                    } else {
                        double dl = ref[0] - cur[0];
                        double da = ref[1] - cur[1];
                        double db = ref[2] - cur[2];
                        double deltaE = Math.sqrt(dl * dl + da * da + db * db);
                        if (deltaE > limit) {
                            return null;
                        }
                    }
                }
            }
        }
        return ref;
    }
}
