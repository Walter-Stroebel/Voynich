/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Persistence for the image catalog: one {@link CatalogEntry} plus one
 * thumbnail per filename. Two backends implement this identically —
 * {@link MySqlCatalog} (JSON + BLOB columns) and {@link FileCatalog} (JSON +
 * PNG sidecar files) — chosen by {@link #open(Config)} depending on whether
 * {@link Config#db} is populated.
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
     * Records that {@code filename} was seen at {@code file}'s path, merging
     * into any existing entry rather than replacing it — the mechanism that
     * makes a NAS copy and a local copy of the same file collapse into one
     * {@link CatalogEntry} with two {@link CatalogEntry.Location} rather than
     * two competing entries. Backend-agnostic: implemented once here on top
     * of {@link #loadEntry} and {@link #save} so {@link MySqlCatalog} and
     * {@link FileCatalog} don't each need their own merge logic.
     *
     * @param filename the catalog key
     * @param file the file this sighting came from; its path, size and
     * mtime are recorded as (or update) one {@link CatalogEntry.Location}
     * @param width image width, as decoded
     * @param height image height, as decoded
     * @param uniqueColors distinct colour count, as decoded
     * @param thumbnail the thumbnail to store alongside it; may be
     * {@code null} if there isn't one yet
     * @return the merged, saved entry
     * @throws IOException if the underlying read/write fails
     */
    default CatalogEntry recordSighting(String filename, File file, int width, int height,
            int uniqueColors, BufferedImage thumbnail) throws IOException {
        CatalogEntry entry = loadEntry(filename);
        if (null == entry) {
            entry = new CatalogEntry();
            entry.filename = filename;
        }
        entry.width = width;
        entry.height = height;
        entry.uniqueColors = uniqueColors;
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
     * Opens the catalog backend selected by {@code config}: {@link MySqlCatalog}
     * when {@link Config#db} and its {@code host}/{@code database}/{@code user}
     * are all populated, otherwise {@link FileCatalog} rooted at
     * {@code ~/.voynich-catalog}. A populated {@link Config#db} that fails to
     * connect throws rather than silently degrading to the file backend —
     * that failure means something is actually wrong, not that MySQL wasn't
     * configured.
     *
     * @param config the loaded application config
     * @return an open {@code Catalog}
     * @throws IOException if the selected backend can't be opened
     */
    static Catalog open(Config config) throws IOException {
        Config.Db db = config.db;
        if (null != db && notBlank(db.host) && notBlank(db.database) && notBlank(db.user)) {
            return new MySqlCatalog(db);
        }
        return new FileCatalog(new File(System.getProperty("user.home"), ".voynich-catalog"));
    }

    private static boolean notBlank(String s) {
        return null != s && !s.isBlank();
    }
}
