/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich.quadmesh;

/**
 * Interface to define a criterionMet for a shape. Ported from infimg's
 * {@code quadmesh} package — see {@link ImageSource}'s doc for why.
 *
 * @param <V> MatchType
 */
public interface ShapeCriterion<V> {

    /**
     * @param im Image to examine.
     * @param s Shape to examine.
     * @return Not null if the criterion is met (the region is uniform
     * enough to become a leaf); null to keep splitting.
     */
    V criterionMet(ImageSource im, java.awt.Shape s);
}
