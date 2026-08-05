/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent settings, serialized to/from {@code ~/.infVoy.json} via {@link JSON}.
 */
public class Config {

    /**
     * Base directory containing the PNG scans to browse.
     */
    public String scanPath;

    /**
     * MySQL connection details for the catalog store, provisioned by the
     * repo's {@code docker-compose.yml} (same credentials go in both places
     * — nothing reads the compose file itself, they're just kept in sync by
     * hand). Leave this {@code null}, or leave any of {@link Db#host},
     * {@link Db#database}, {@link Db#user} blank, to fall back to the
     * file-based catalog under {@code ~/.voynich-catalog}; see
     * {@link Catalog#open(Config)}.
     */
    public Db db;

    /**
     * Last on-screen bounds of named tool windows (visualization popups and
     * the like), keyed by a stable per-window-type name such as
     * "Color Frequency" — not per image, since the same visualization is
     * reopened for many entries and should keep landing wherever the user
     * last parked it. See {@link ViewFrame}.
     */
    public Map<String, Bounds> viewBounds = new HashMap<>();

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

    /**
     * MySQL connection parameters. See {@link #db}.
     */
    public static class Db {

        public String host = "localhost";
        /**
         * No default on purpose — this project never binds MySQL to its
         * standard port (see {@code docker-compose.yml}), so defaulting
         * this to 3306 would silently point an incomplete config at the
         * one port it's guaranteed not to be on. Leaving it unset (0)
         * fails loudly instead.
         */
        public int port;
        public String database;
        public String user;
        public String password;
    }
}
