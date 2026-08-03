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
     * One sighting of {@link CatalogEntry#filename} at a specific path, with
     * enough to detect staleness ({@link #size}/{@link #mtime}) without
     * re-decoding the image.
     */
    public static class Location {

        public String path;
        public long size;
        public long mtime;
    }
}
