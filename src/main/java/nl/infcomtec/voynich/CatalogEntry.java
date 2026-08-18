/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.util.ArrayList;
import java.util.List;

/**
 * One logical image's catalog record. Stored under the permanent
 * {@link #id} — the catalog's only identity for a page. There is
 * deliberately no stored display filename: every cataloged file's name is
 * always a live lookup into {@code data/scan-naming.tsv} via
 * {@link ScanRenamer} (see {@link ScanRenamer#displayName}), never data
 * this class caches itself — the physical file is, and routinely is,
 * renamed on disk (see {@code RenameTaskWindow}), and a cached name would
 * just go stale. A file that can't be resolved to an id via that table
 * (see {@link ScanRenamer#idForName}) is not something this catalog deals
 * with at all — see {@code ScanTaskWindow.resolveId}. The same file
 * routinely exists at more than one path too (e.g. a NAS copy plus a local
 * NVMe copy kept for read speed) — those are the same catalog entry with
 * two {@link #locations}, not two entries.
 * <p>
 * Serialized as-is through {@link JSON} as a standalone {@code <id>.json}
 * sidecar file by {@link FileCatalog}, thumbnail included (see
 * {@link #thumbnailPng}).
 */
public class CatalogEntry {

    /**
     * The catalog's own permanent identity for this page — one of the
     * bundled {@code data/scan-naming.tsv}'s {@code Id} values (1–213,
     * dense, no gaps), assigned once and never changed even if the file's
     * on-disk name (see {@link #filename}) is later renamed to a different
     * scheme. Not shown to the user anywhere; purely internal storage/
     * lookup key — see {@link Catalog#loadEntry(int)}. Zero for an entry
     * that predates this field (see {@code CLAUDE.md}'s "Catalog
     * persistence" section for the migration that assigns real ids to
     * pre-existing sidecars).
     */
    public int id;

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
        /**
         * Rotation to apply, in radians, so that this region's traced
         * content (e.g. a ring figure whose outline is a wonky wedge, not a
         * box) ends up upright. Independent of the polygon's vertex order
         * or shape — can't be inferred from either, so it's carried
         * explicitly. Zero for a region that is already upright (the
         * default for every region traced before this field existed).
         */
        public double angle = 0.0;
        /**
         * Index into the owning entry's {@link CatalogEntry#regions} of this
         * region's parent, or {@code -1} for a top-level region. Lets a
         * diagram with many small sub-figures (e.g. a ring of wedge-shaped
         * figures around a circle diagram) be traced as children of the
         * diagram's own region — the diagram region's bounding box then
         * doubles as the zoom viewport {@link ContentAreaEditor} opens for
         * tracing each child, instead of tracing at whole-page scale. Never
         * points at index 0 (the synthetic whole page) or forms a cycle;
         * only one level deep is ever needed here, but nothing enforces
         * that — it's just how far this manuscript's diagrams actually
         * nest.
         */
        public int parentIndex = -1;
    }
}
