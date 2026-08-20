/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich.quadmesh;

/**
 * Common surface {@link Quad}/{@link ShapeCriterion} need from an image.
 * Deliberately Lab-native (not returning an {@code EnhancedColor}) so a
 * criterion never has to reconvert RGB-&gt;Lab itself. Ported from infimg
 * v1.8's {@code nl.infcomtec.infimg.quadmesh} package (file-copy
 * convention, same as MITSA/jacksonwrap/advswing — infimg isn't
 * Maven-published, and this project's own rule is to never edit infimg's
 * source directly, only copy from it).
 */
public interface ImageSource {

    int getWidth();

    int getHeight();

    /**
     * @param lab out-param, filled with L*, a*, b* (unscaled) — same
     * calling convention as {@code EnhancedColor.getCIELAB(int argb, double[] lab)},
     * so the caller can reuse one array across a whole scan instead of
     * allocating per pixel.
     */
    void getLab(int x, int y, double[] lab);
}
