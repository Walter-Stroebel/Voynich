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
 * inlined as base64 in its JSON) per filename. {@link FileCatalog} is the
 * only backend — see {@link #open(Config)}.
 */
public interface Catalog {

    /**
     * Upserts {@code entry} and its thumbnail, keyed by
     * {@link CatalogEntry#filename}.
     *
     * @param entry the record to store
     * @param thumbnail the thumbnail to store alongside it; may be
     * {@code null} if there isn't one yet
     * @throws IOException if the write fails
     */
    void save(CatalogEntry entry, BufferedImage thumbnail) throws IOException;

    /**
     * @param filename the catalog key
     * @return the stored entry, or {@code null} if none exists
     * @throws IOException if the read fails
     */
    CatalogEntry loadEntry(String filename) throws IOException;

    /**
     * @param filename the catalog key
     * @return the stored thumbnail, or {@code null} if none exists
     * @throws IOException if the read fails
     */
    BufferedImage loadThumbnail(String filename) throws IOException;

    /**
     * @return every {@link CatalogEntry} currently stored, in
     * backend-defined order
     * @throws IOException if the read fails
     */
    List<CatalogEntry> listAll() throws IOException;

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
     * Records that {@code filename} was seen at {@code file}'s path, merging
     * into any existing entry rather than replacing it — the mechanism that
     * makes a NAS copy and a local copy of the same file collapse into one
     * {@link CatalogEntry} with two {@link CatalogEntry.Location} rather than
     * two competing entries. Backend-agnostic: implemented once here on top
     * of {@link #loadEntry} and {@link #save} rather than in {@link FileCatalog}
     * itself.
     *
     * @param filename the catalog key
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
    default CatalogEntry recordSighting(String filename, File file, int width, int height,
            int uniqueColors, int thumbnailUniqueColors, BufferedImage thumbnail) throws IOException {
        CatalogEntry entry = loadEntry(filename);
        if (null == entry) {
            entry = new CatalogEntry();
            entry.filename = filename;
        }
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
     * Adds {@code tag} to {@code filename}'s {@link CatalogEntry#tags} if not
     * already present, leaving everything else about the entry (including
     * its stored thumbnail) untouched. No-op if the tag is already there.
     *
     * @param filename the catalog key; must already have an entry
     * @param tag the free-text note to add
     * @throws IOException if the underlying read/write fails
     * @throws IllegalArgumentException if no entry exists for {@code filename}
     */
    default void addTag(String filename, String tag) throws IOException {
        CatalogEntry entry = loadEntry(filename);
        if (null == entry) {
            throw new IllegalArgumentException("No catalog entry for " + filename);
        }
        if (!entry.tags.contains(tag)) {
            entry.tags.add(tag);
            save(entry, loadThumbnail(filename));
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
