/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich.quadmesh;

import java.awt.image.BufferedImage;
import nl.infcomtec.voynich.EnhancedColor;

/**
 * {@link ImageSource} backed directly by a {@link BufferedImage}, reusing
 * this project's {@link EnhancedColor#getCIELAB(int, double[])} rather than
 * building any Lab cache — see {@link ImageSource}'s doc for why. Ported
 * from infimg's {@code quadmesh} package.
 */
public class BufferedImageSource implements ImageSource {

    private final BufferedImage image;

    public BufferedImageSource(BufferedImage image) {
        this.image = image;
    }

    @Override
    public int getWidth() {
        return image.getWidth();
    }

    @Override
    public int getHeight() {
        return image.getHeight();
    }

    @Override
    public void getLab(int x, int y, double[] lab) {
        EnhancedColor.getCIELAB(image.getRGB(x, y), lab);
    }
}
