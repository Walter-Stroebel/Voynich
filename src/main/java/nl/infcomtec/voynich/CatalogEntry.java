/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.util.ArrayList;
import java.util.List;

/**
 * One logical image's catalog record, identified by {@link #filename} —
 * deliberately not by path. The same file routinely exists at more than one
 * path (e.g. a NAS copy plus a local NVMe copy kept for read speed); those
 * are the same catalog entry with two {@link #locations}, not two entries.
 * <p>
 * Serialized as-is through {@link JSON}: as a MySQL {@code JSON} column by
 * {@link MySqlCatalog}, or as a standalone {@code <filename>.json} sidecar
 * file by {@link FileCatalog}. Both backends store and load the identical
 * shape; see {@link Catalog#open(Config)} for which one a given run uses.
 */
public class CatalogEntry {

    /**
     * The catalog key. Matches the physical filename shared by every
     * {@link Location}, not any single one of their paths.
     */
    public String filename;
    /**
     * Every known place this file has been seen, most recently updated by
     * {@link Catalog#recordSighting}.
     */
    public List<Location> locations = new ArrayList<>();
    public int width;
    public int height;
    public int uniqueColors;
    /**
     * Distinct colour count of the {@link Catalog#loadThumbnail}-stored
     * thumbnail, not the source image. Smoothing during the downscale to
     * {@link ColorImage#THUMB_SIZE}×{@link ColorImage#THUMB_SIZE} erases most
     * of the fine antialiasing/scan noise that dominates {@link #uniqueColors},
     * so the two counts measure different things and neither substitutes for
     * the other.
     */
    public int thumbnailUniqueColors;
    /**
     * The filename this image is known by in the original 2004/torrent JPG
     * numbering (e.g. {@code "127.jpg"}), when known — see
     * {@code data/voynich-page-index.json} for the full cross-reference.
     * {@code null} if not yet established for this entry.
     */
    public String torrentJpg;
    /**
     * Free-text short notes about this page (e.g. {@code "circular diagram"},
     * {@code "foldout"}) — a per-file notepad, not a fixed set of categories.
     * New kinds of note keep turning up as the manuscript gets studied, so
     * this deliberately isn't an enum or a set of booleans. Added via
     * {@link Catalog#addTag}.
     */
    public List<String> tags = new ArrayList<>();
    /**
     * Human-traced boundary around this scan's actual content — text,
     * illustration, wash — in this image's own pixel coordinates. Not the
     * physical page: a tight bound on the "good stuff," deliberately
     * excluding blank vellum margins as well as photography backdrop,
     * frayed edges, and the other pages visible in the stack beneath it.
     * Never auto-detected: no fold — however severe — is ever a true
     * boundary, since content routinely runs right through them. Empty
     * until a human has traced it via {@code ContentAreaEditor}; a real
     * polygon always has at least 3 vertices.
     */
    public List<Vertex> contentArea = new ArrayList<>();

    /**
     * One sighting of {@link CatalogEntry#filename} at a specific path, with
     * enough to detect staleness ({@link #size}/{@link #mtime}) without
     * re-decoding the image.
     */
    public static class Location {

        public String path;
        public long size;
        public long mtime;
    }

    /**
     * One point of a {@link #contentArea} polygon, in this image's own
     * pixel coordinates.
     */
    public static class Vertex {

        public int x;
        public int y;

        public Vertex() {
        }

        public Vertex(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
