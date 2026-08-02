/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

/**
 * An ordered triple of {@code short} values used as a composite map key.
 * <p>
 * The natural ordering is lexicographic on (v0, v1, v2), which satisfies the
 * contract required by both {@link java.util.TreeMap} (the per-instance
 * cache) and {@link java.util.concurrent.ConcurrentSkipListMap} (the shared
 * static caches in {@link ColorBase}). The meaning of v0/v1/v2 is defined by
 * the caller — {@link ColorBase} uses this both as an RGB triple (v0=R,
 * v1=G, v2=B) and, via {@link ColorBase.TriLabColor}, as a scaled CIELab
 * triple (v0=L*x100, v1=a*x100, v2=b*x100).
 * </p>
 * <p>
 * {@code equals} and {@code hashCode} are intentionally not overridden —
 * only ordered maps that rely exclusively on {@link #compareTo} are used. Do
 * not place {@code TriElm} instances into any hash-based collection
 * ({@code HashMap}, {@code HashSet}) without first adding proper
 * {@code equals}/{@code hashCode} implementations.
 * </p>
 */
public class TriElm implements Comparable<TriElm> {

    /**
     * First component of the triple; meaning depends on usage context (see
     * class doc).
     */
    public short v0;
    /**
     * Second component of the triple; meaning depends on usage context (see
     * class doc).
     */
    public short v1;
    /**
     * Third component of the triple; meaning depends on usage context (see
     * class doc).
     */
    public short v2;

    /**
     * Lexicographic comparison on (v0, v1, v2).
     *
     * @param o the other {@code TriElm} to compare against
     * @return negative, zero, or positive as this triple is less than, equal
     * to, or greater than {@code o}
     */
    @Override
    public int compareTo(TriElm o) {
        int d = Short.compare(v0, o.v0);
        if (0 == d) {
            d = Short.compare(v1, o.v1);
        }
        if (0 == d) {
            d = Short.compare(v2, o.v2);
        }
        return d;
    }
}
