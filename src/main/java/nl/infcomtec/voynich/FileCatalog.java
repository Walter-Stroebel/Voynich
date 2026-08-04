/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * File-backed {@link Catalog}: each {@link CatalogEntry} is a standalone,
 * human-readable {@code <filename>.json} sidecar, with its thumbnail as a
 * sibling {@code <filename>.png}, both under one catalog directory (not
 * colocated with the source image — a given filename can have more than one
 * {@link CatalogEntry.Location}, so there's no single "next to the image" to
 * put them).
 * <p>
 * This is the fallback used by {@link Catalog#open(Config)} when
 * {@link Config#db} isn't configured; same {@link CatalogEntry} shape as
 * {@link MySqlCatalog}, just two files instead of one row.
 * </p>
 */
public class FileCatalog implements Catalog {

    private final File dir;
    /**
     * Sibling of {@link #dir}, not nested inside it — so {@link #checkpoint()}
     * copying {@link #dir}'s contents never recurses into its own checkpoints.
     * Each checkpoint is one subdirectory named by the epoch-millis timestamp
     * it was taken at.
     */
    private final File checkpointsDir;

    /**
     * @param dir the catalog directory; created if missing
     * @throws IOException if {@code dir} doesn't exist and can't be created
     */
    public FileCatalog(File dir) throws IOException {
        this.dir = dir;
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create catalog directory " + dir);
        }
        this.checkpointsDir = new File(dir.getParentFile(), dir.getName() + "-checkpoints");
    }

    @Override
    public void save(CatalogEntry entry, BufferedImage thumbnail) throws IOException {
        File jsonFile = new File(dir, entry.filename + ".json");
        Files.writeString(jsonFile.toPath(), JSON.writeValueAsPretty(entry));
        if (null != thumbnail) {
            ImageIO.write(thumbnail, "png", new File(dir, entry.filename + ".png"));
        }
    }

    @Override
    public CatalogEntry loadEntry(String filename) throws IOException {
        File jsonFile = new File(dir, filename + ".json");
        if (!jsonFile.exists()) {
            return null;
        }
        return JSON.readValue(null, jsonFile, CatalogEntry.class);
    }

    @Override
    public List<CatalogEntry> listAll() throws IOException {
        File[] jsonFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.endsWith(".json");
            }
        });
        List<CatalogEntry> all = new ArrayList<>();
        if (null != jsonFiles) {
            for (File jsonFile : jsonFiles) {
                all.add(JSON.readValue(null, jsonFile, CatalogEntry.class));
            }
        }
        return all;
    }

    @Override
    public BufferedImage loadThumbnail(String filename) throws IOException {
        File pngFile = new File(dir, filename + ".png");
        if (!pngFile.exists()) {
            return null;
        }
        return ImageIO.read(pngFile);
    }

    @Override
    public void checkpoint() throws IOException {
        File target = new File(checkpointsDir, Long.toString(System.currentTimeMillis()));
        if (!target.mkdirs()) {
            throw new IOException("Could not create checkpoint directory " + target);
        }
        File[] files = dir.listFiles();
        if (null != files) {
            for (File f : files) {
                Files.copy(f.toPath(), new File(target, f.getName()).toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    @Override
    public void restoreLatestCheckpoint() throws IOException {
        File[] checkpoints = checkpointsDir.listFiles(File::isDirectory);
        if (null == checkpoints || 0 == checkpoints.length) {
            throw new IllegalStateException("No checkpoint to restore");
        }
        File latest = null;
        long latestMillis = -1;
        for (File candidate : checkpoints) {
            try {
                long millis = Long.parseLong(candidate.getName());
                if (millis > latestMillis) {
                    latestMillis = millis;
                    latest = candidate;
                }
            } catch (NumberFormatException ignored) {
                // not one of ours; skip it
            }
        }
        if (null == latest) {
            throw new IllegalStateException("No checkpoint to restore");
        }
        File[] current = dir.listFiles();
        if (null != current) {
            for (File f : current) {
                Files.delete(f.toPath());
            }
        }
        File[] saved = latest.listFiles();
        if (null != saved) {
            for (File f : saved) {
                Files.copy(f.toPath(), new File(dir, f.getName()).toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
}
