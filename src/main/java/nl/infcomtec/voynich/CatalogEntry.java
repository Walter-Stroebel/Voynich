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
 * Serialized as-is through {@link JSON} as a standalone
 * {@code <filename>.json} sidecar file by {@link FileCatalog}, thumbnail
 * included (see {@link #thumbnailPng}).
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
     * Every region on this scan, in a fixed positional convention rather
     * than any flag: {@code regions.get(0)}, once {@link #width}/
     * {@link #height} are known, is always the whole-page rectangle —
     * synthesized by {@link #ensureWholePageRegion()}, never traced by a
     * human. {@code regions.get(1)}, if present, is <em>the</em> content
     * area every existing mask/crop consumer (the "Show Mask" toggle,
     * {@code CatalogCli --content-area}) treats as the boundary of the
     * scan's actual content — text, illustration, wash — not the physical
     * page; it deliberately excludes blank vellum margins as well as
     * photography backdrop, frayed edges, and the other pages visible in
     * the stack beneath it. Never auto-detected: no fold — however severe —
     * is ever a true boundary, since content routinely runs right through
     * them. Anything from index 2 on is an "other area" — damage, a second
     * reviewer's opinion, whatever else turns up — distinguished only by
     * {@link Region#kind}/{@link Region#author}, both free text.
     * {@code regions.size() <= 1} means no content area has been traced yet
     * (only the synthetic whole page, or nothing at all for an entry that
     * predates {@link #width}/{@link #height} being known, or that isn't
     * actually a manuscript page). Renamed from the singular {@code
     * contentArea} field 2026-08-07 — see CLAUDE.md's "Catalog persistence"
     * section for that migration.
     */
    public List<Region> regions = new ArrayList<>();

    /**
     * Inserts the synthetic whole-page {@link Region} at {@code regions[0]}
     * if it's not already there. A no-op once {@code regions} is non-empty
     * (whatever's at index 0, real or synthetic, stays), and while
     * {@link #width}/{@link #height} are still both zero — some catalog
     * entries never become real pages (a colour chart, a cover shot) and
     * simply never get one. Called after {@link #width}/{@link #height}
     * are set, from {@link Catalog#recordSighting}.
     */
    public void ensureWholePageRegion() {
        if (!regions.isEmpty() || width <= 0 || height <= 0) {
            return;
        }
        Region page = new Region();
        page.kind = "page";
        page.polygon.add(new Vertex(0, 0));
        page.polygon.add(new Vertex(width, 0));
        page.polygon.add(new Vertex(width, height));
        page.polygon.add(new Vertex(0, height));
        regions.add(page);
    }

    /**
     * @return {@code regions.get(1)} — the traced main content area — or
     * {@code null} if none has been traced yet
     */
    public Region mainRegion() {
        return regions.size() >= 2 ? regions.get(1) : null;
    }
    /**
     * The stored thumbnail's raw PNG bytes, inline in this same JSON
     * blob/column rather than a separate file or BLOB column — Jackson
     * serializes {@code byte[]} as base64. {@code null} if no thumbnail has
     * been stored yet.
     */
    public byte[] thumbnailPng;

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
     * One point of a {@link Region} polygon, in this image's own pixel
     * coordinates.
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

    /**
     * One human-traced polygon plus who drew it and what it's marking. Both
     * {@link #kind} and {@link #author} are free text, not an enum — same
     * "new kinds keep turning up" reasoning as {@link CatalogEntry#tags}.
     * {@link #author} blank means genuinely unattributed ("someone"), never
     * implicitly the primary user.
     */
    public static class Region {

        /**
         * Free-form label, e.g. {@code "content"}, {@code "damage"},
         * {@code "Arabic page number"}, {@code "Tim's opinion"}. The
         * tracing UI offers an editable combo box pre-filled with every
         * distinct value already used across the catalog, so a given label
         * is typed at most once ever.
         */
        public String kind = "";
        /**
         * Who traced this region, or blank if unspecified. Never defaulted
         * to the primary user — blank means "someone," not "me."
         */
        public String author = "";
        public List<Vertex> polygon = new ArrayList<>();
    }
}
