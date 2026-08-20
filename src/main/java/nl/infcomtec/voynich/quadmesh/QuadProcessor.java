/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich.quadmesh;

/**
 * Apply a function to a quad's leaves. Ported from infimg's {@code quadmesh}
 * package — see {@link ImageSource}'s doc for why.
 */
public interface QuadProcessor {

    /**
     * Called for each quad leaf.
     *
     * @param q The leaf node.
     * @return True to continue processing, false to abort.
     */
    boolean process(Quad q);
}
