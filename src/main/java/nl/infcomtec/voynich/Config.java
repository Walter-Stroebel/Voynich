/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent settings, serialized to/from {@code ~/.infVoy/config.json} via {@link JSON}.
 */
public class Config {

    /**
     * Base directory containing the PNG scans to browse.
     */
    public String scanPath;

    /**
     * Last on-screen bounds of named tool windows (visualization popups and
     * the like), keyed by a stable per-window-type name such as
     * "Color Frequency" — not per image, since the same visualization is
     * reopened for many entries and should keep landing wherever the user
     * last parked it. See {@link ViewFrame}.
     */
    public Map<String, Bounds> viewBounds = new HashMap<>();

    /**
     * {@link OverviewPanel}'s last-chosen sort field, by
     * {@code SortKey.name()} — {@code null} until {@link OverviewPanel#sort()}
     * is used for the first time, in which case the grid keeps its default
     * (catalog/insertion) order on the next run too.
     */
    public String sortKey;
    /**
     * {@link OverviewPanel}'s last-chosen sort direction, paired with
     * {@link #sortKey}.
     */
    public boolean sortDescending;

    /**
     * A plain {@code x/y/width/height} rectangle, kept separate from
     * {@link java.awt.Rectangle} so it serializes as four ints and nothing
     * else.
     */
    public static class Bounds {

        public int x;
        public int y;
        public int width;
        public int height;
    }
}
