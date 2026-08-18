/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Persistence for the image catalog: one {@link CatalogEntry} (thumbnail
 * inlined as base64 in its JSON) per {@link CatalogEntry#id} — the app's
 * permanent internal key, not the display {@link CatalogEntry#filename},
 * which can and does change under a rename (see {@link #renameEntry}).
 * {@link FileCatalog} is the only backend — see {@link #open(Config)}.
 */
public interface Catalog {

    /**
     * Upserts {@code entry} and its thumbnail, keyed by
     * {@link CatalogEntry#id}.
     *
     * @param entry the record to store
     * @param thumbnail the thumbnail to store alongside it; may be
     * {@code null} if there isn't one yet
     * @throws IOException if the write fails
     */
    void save(CatalogEntry entry, BufferedImage thumbnail) throws IOException;

    /**
     * @param id the catalog key
     * @return the stored entry, or {@code null} if none exists
     * @throws IOException if the read fails
     */
    CatalogEntry loadEntry(int id) throws IOException;

    /**
     * @param id the catalog key
     * @return the stored thumbnail, or {@code null} if none exists
     * @throws IOException if the read fails
     */
    BufferedImage loadThumbnail(int id) throws IOException;

    /**
     * @return every {@link CatalogEntry} currently stored, in
     * backend-defined order
     * @throws IOException if the read fails
     */
    List<CatalogEntry> listAll() throws IOException;

    /**
     * Permanently removes the entry stored under {@code id}, if any. No-op
     * if none exists.
     *
     * @param id the catalog key to remove
     * @throws IOException if the delete fails
     */
    void deleteEntry(int id) throws IOException;

    /**
     * Convenience lookup for callers that only have a display filename
     * (CLI args, a click in the UI) — a linear scan over {@link #listAll()}
     * matching {@link CatalogEntry#filename}, since filename is no longer
     * the storage key and has no dedicated index. Fine at this catalog's
     * scale (213 entries); not meant for a hot path.
     *
     * @param filename the entry's current display filename
     * @return the matching entry, or {@code null} if none has that filename
     * @throws IOException if the underlying listing fails
     */
    default CatalogEntry loadEntryByFilename(String filename) throws IOException {
        for (CatalogEntry entry : listAll()) {
            if (entry.filename.equals(filename)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * @return one higher than the largest {@link CatalogEntry#id} currently
     * stored (or {@code 1} for an empty catalog) — used to assign a fresh
     * id to a file that doesn't match any {@link ScanRenamer} naming
     * column (e.g. something outside the known 213-page manuscript dropped
     * into {@code scanPath}), so it still gets a stable identity rather
     * than being skipped or crashing the scan.
     * @throws IOException if the underlying listing fails
     */
    default int nextUnusedId() throws IOException {
        int max = 0;
        for (CatalogEntry entry : listAll()) {
            if (entry.id > max) {
                max = entry.id;
            }
        }
        return max + 1;
    }

    /**
     * Clones the entire catalog's current state under a new, timestamped
     * checkpoint. Coarse-grained (the whole catalog, not one entry) and
     * cheap — a directory copy or a {@code CREATE TABLE ... AS SELECT},
     * depending on backend. Checkpoints accumulate; nothing prunes them
     * automatically, by design — pruning is a separate, low-stakes concern
     * left for hand cleanup (see {@link #deleteCheckpoint}, driven by
     * {@code StorageDialog}).
     *
     * @throws IOException if the clone fails
     */
    void checkpoint() throws IOException;

    /**
     * Every checkpoint currently on disk, newest first — the data behind
     * {@code StorageDialog}'s "what's there, how big, how old" view.
     *
     * @throws IOException if the listing fails
     */
    List<CheckpointInfo> listCheckpoints() throws IOException;

    /**
     * Replaces the entire catalog's current state with the checkpoint taken
     * at {@code timestampMillis}. A full replace, not a merge: any entry
     * written since that checkpoint is discarded, including one written
     * after the checkpoint but never checkpointed itself.
     *
     * @param timestampMillis identifies the checkpoint, as returned by
     * {@link CheckpointInfo#timestampMillis}
     * @throws IOException if the restore fails
     * @throws IllegalStateException if no such checkpoint exists
     */
    void restoreCheckpoint(long timestampMillis) throws IOException;

    /**
     * Permanently removes one checkpoint. Does not touch the live catalog or
     * any other checkpoint.
     *
     * @param timestampMillis identifies the checkpoint, as returned by
     * {@link CheckpointInfo#timestampMillis}
     * @throws IOException if the delete fails or no such checkpoint exists
     */
    void deleteCheckpoint(long timestampMillis) throws IOException;

    /**
     * Replaces the entire catalog's current state with its most recent
     * {@link #checkpoint()}. Not a stack — this always targets the single
     * most recent checkpoint, never an older one. Convenience wrapper over
     * {@link #listCheckpoints()} and {@link #restoreCheckpoint}, built once
     * here so backends only need to implement the two lower-level operations.
     *
     * @throws IOException if the restore fails
     * @throws IllegalStateException if no checkpoint exists yet
     */
    default void restoreLatestCheckpoint() throws IOException {
        List<CheckpointInfo> checkpoints = listCheckpoints();
        if (checkpoints.isEmpty()) {
            throw new IllegalStateException("No checkpoint to restore");
        }
        restoreCheckpoint(checkpoints.get(0).timestampMillis);
    }

    /**
     * Size of one checkpoint on disk, as listed by {@link #listCheckpoints()}.
     */
    final class CheckpointInfo {

        public final long timestampMillis;
        public final long sizeBytes;

        public CheckpointInfo(long timestampMillis, long sizeBytes) {
            this.timestampMillis = timestampMillis;
            this.sizeBytes = sizeBytes;
        }
    }

    /**
     * Size of the live catalog itself (not a checkpoint), as shown at the
     * top of {@code StorageDialog}.
     */
    final class StorageInfo {

        public final String location;
        public final int entryCount;
        public final long totalBytes;

        public StorageInfo(String location, int entryCount, long totalBytes) {
            this.location = location;
            this.entryCount = entryCount;
            this.totalBytes = totalBytes;
        }
    }

    /**
     * @return size and location of the live catalog (not any checkpoint)
     * @throws IOException if the read fails
     */
    StorageInfo liveStorageInfo() throws IOException;

    /**
     * Records that {@code id}'s page was seen at {@code file}'s path (as
     * {@code filename}), merging into any existing entry rather than
     * replacing it — the mechanism that makes a NAS copy and a local copy
     * of the same file collapse into one {@link CatalogEntry} with two
     * {@link CatalogEntry.Location} rather than two competing entries.
     * Backend-agnostic: implemented once here on top of {@link #loadEntry}
     * and {@link #save} rather than in {@link FileCatalog} itself.
     *
     * @param id the catalog key — resolved by the caller (see
     * {@code ScanTaskWindow}) before this is called, since only the caller
     * knows how to turn a filename into a stable id (via
     * {@link ScanRenamer}, or an existing entry's own id if this file was
     * already catalogued under a different display name)
     * @param filename the file's current display name
     * @param file the file this sighting came from; its path, size and
     * mtime are recorded as (or update) one {@link CatalogEntry.Location}
     * @param width image width, as decoded
     * @param height image height, as decoded
     * @param uniqueColors distinct colour count, as decoded
     * @param thumbnailUniqueColors distinct colour count of the generated
     * thumbnail, as decoded
     * @param thumbnail the thumbnail to store alongside it; may be
     * {@code null} if there isn't one yet
     * @return the merged, saved entry
     * @throws IOException if the underlying read/write fails
     */
    default CatalogEntry recordSighting(int id, String filename, File file, int width, int height,
            int uniqueColors, int thumbnailUniqueColors, BufferedImage thumbnail) throws IOException {
        CatalogEntry entry = loadEntry(id);
        if (null == entry) {
            entry = new CatalogEntry();
            entry.id = id;
        }
        entry.filename = filename;
        entry.width = width;
        entry.height = height;
        entry.uniqueColors = uniqueColors;
        entry.thumbnailUniqueColors = thumbnailUniqueColors;
        entry.ensureWholePageRegion();
        String path = file.getAbsolutePath();
        CatalogEntry.Location match = null;
        for (CatalogEntry.Location loc : entry.locations) {
            if (loc.path.equals(path)) {
                match = loc;
                break;
            }
        }
        if (null == match) {
            match = new CatalogEntry.Location();
            match.path = path;
            entry.locations.add(match);
        }
        match.size = file.length();
        match.mtime = file.lastModified();
        save(entry, thumbnail);
        return entry;
    }

    /**
     * Renames an entry's {@link CatalogEntry#filename} and matching
     * {@link CatalogEntry.Location#path} in place — the entry's {@link
     * CatalogEntry#id} and everything else about it (thumbnail, regions,
     * tags) is untouched, since {@code id}, not filename, is the storage
     * key. Used by {@link ScanRenamer}-driven renames (see {@code
     * RenameTaskWindow}): a filesystem rename alone doesn't touch the
     * catalog, so this is the catalog-side half of that operation —
     * deliberately not a full re-Scan, which would needlessly re-decode
     * every renamed image from scratch.
     *
     * @param id the entry's permanent catalog key
     * @param newFilename the entry's new display filename
     * @param newFile the file's new on-disk location, so the matching
     * {@link CatalogEntry.Location#path} can be updated too — otherwise a
     * later Scan would see it as a fresh sighting under the old path,
     * mismatched against the new filename
     * @return the updated entry, or {@code null} if {@code id} had no entry
     * @throws IOException if the underlying read/write fails
     */
    default CatalogEntry renameEntry(int id, String newFilename, File newFile) throws IOException {
        CatalogEntry entry = loadEntry(id);
        if (null == entry) {
            return null;
        }
        String oldFilename = entry.filename;
        String newPath = newFile.getAbsolutePath();
        for (CatalogEntry.Location loc : entry.locations) {
            if (new File(loc.path).getName().equals(oldFilename)) {
                loc.path = newPath;
            }
        }
        entry.filename = newFilename;
        save(entry, loadThumbnail(id));
        return entry;
    }

    /**
     * Adds {@code tag} to {@code id}'s {@link CatalogEntry#tags} if not
     * already present, leaving everything else about the entry (including
     * its stored thumbnail) untouched. No-op if the tag is already there.
     *
     * @param id the catalog key; must already have an entry
     * @param tag the free-text note to add
     * @throws IOException if the underlying read/write fails
     * @throws IllegalArgumentException if no entry exists for {@code id}
     */
    default void addTag(int id, String tag) throws IOException {
        CatalogEntry entry = loadEntry(id);
        if (null == entry) {
            throw new IllegalArgumentException("No catalog entry for id " + id);
        }
        if (!entry.tags.contains(tag)) {
            entry.tags.add(tag);
            save(entry, loadThumbnail(id));
        }
    }

    /**
     * Opens the catalog: {@link FileCatalog} rooted at
     * {@code <Voynich.baseDir>/catalog}.
     *
     * @param config the loaded application config
     * @return an open {@code Catalog}
     * @throws IOException if the backend can't be opened
     */
    static Catalog open(Config config) throws IOException {
        return new FileCatalog(new File(Voynich.baseDir, "catalog"));
    }
}
