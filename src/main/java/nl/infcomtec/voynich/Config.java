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
     * Base directory containing the scans to browse — see
     * {@link ScanTaskWindow#SCANNABLE_EXTENSIONS} for which file
     * extensions are recognized.
     */
    public String scanPath;

    /**
     * Which naming scheme the files under {@link #scanPath} currently use —
     * a column header from the bundled {@code data/scan-naming.tsv} lookup
     * table (see {@link ScanRenamer}), e.g. {@code "torrent_jpg"} or
     * {@code "project_png"}. Defaults to {@code "torrent_jpg"} since that's
     * the only naming a freshly-downloaded scan set is likely to start in;
     * updated by {@link ScanRenamer} after a successful rename so a later
     * rename knows the true current state rather than guessing from file
     * extensions (which don't change on a rename — see the class doc).
     */
    public String namingScheme = "Sequential";

    /**
     * Command to launch the standalone infimg viewer (see
     * {@code github.com/Walter-Stroebel/infimg}), invoked directly by
     * {@link Voynich#launchImageView} (not wrapped in {@code java -jar} —
     * point this at a launcher script, e.g. {@code ~/bin/infimg}, that does
     * its own {@code java -jar <versioned-jar>} internally, so this config
     * value survives infimg version bumps without needing an edit here each
     * time). A bare jar path also still works, as long as it's directly
     * executable (unusual for a plain {@code .jar}) — the launcher-script
     * indirection is the intended usage. Not shipped with a default: a
     * dev-machine launcher path only makes sense on the machine that has
     * one, so {@link Voynich#launchImageView} logs a warning rather than
     * guessing when this is unset — this "install location" question is
     * intended to be settled properly in the user manual's install section,
     * not baked in here.
     */
    public String infimgJar;

    /**
     * Host running the {@code mcp-service-catalog} vision pipeline (see
     * {@link VisionClient}) — defaults to "predator", the only machine that
     * currently runs it. Overridable for anyone pointing this at a different
     * box.
     */
    public String visionHost = "predator";

    /**
     * Port for the vision pipeline's file upload service (plain
     * {@code PUT}), paired with {@link #visionMcpPort}.
     */
    public int visionFilePort = 8765;

    /**
     * Port for the vision pipeline's Streamable HTTP MCP transport
     * ({@code POST /mcp}, JSON-RPC).
     */
    public int visionMcpPort = 8764;

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
