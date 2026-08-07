/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * File-backed {@link Catalog}: each {@link CatalogEntry} is a standalone,
 * human-readable {@code <filename>.json} sidecar under one catalog
 * directory, with its thumbnail inlined into that same JSON as
 * {@link CatalogEntry#thumbnailPng} — no separate BLOB store or sidecar
 * image file to keep in sync with the entry.
 * <p>
 * A catalog directory created before 2026-08-06 may still have leftover
 * {@code <filename>.png} sidecars from before thumbnails moved inline;
 * {@link #loadEntry} migrates one in transparently on first read (loading it
 * into {@link CatalogEntry#thumbnailPng} and deleting the sidecar) so old
 * catalogs don't need a separate one-off migration pass.
 * </p>
 */
public class FileCatalog implements Catalog {

    private final File dir;
    /**
     * Sibling of {@link #dir}, not nested inside it — so {@link #checkpoint()}
     * zipping {@link #dir}'s contents never recurses into its own checkpoints.
     * Each checkpoint is one {@code <epoch-millis>.zip} file, named by the
     * timestamp it was taken at.
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
        if (null != thumbnail) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "png", out);
            entry.thumbnailPng = out.toByteArray();
        }
        File jsonFile = new File(dir, entry.filename + ".json");
        Files.writeString(jsonFile.toPath(), JSON.writeValueAsPretty(entry));
    }

    @Override
    public CatalogEntry loadEntry(String filename) throws IOException {
        File jsonFile = new File(dir, filename + ".json");
        if (!jsonFile.exists()) {
            return null;
        }
        CatalogEntry entry = JSON.readValue(null, jsonFile, CatalogEntry.class);
        if (null == entry.thumbnailPng) {
            File legacyPng = new File(dir, filename + ".png");
            if (legacyPng.exists()) {
                entry.thumbnailPng = Files.readAllBytes(legacyPng.toPath());
                Files.writeString(jsonFile.toPath(), JSON.writeValueAsPretty(entry));
                Files.delete(legacyPng.toPath());
            }
        }
        return entry;
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
        CatalogEntry entry = loadEntry(filename);
        if (null == entry || null == entry.thumbnailPng) {
            return null;
        }
        return ImageIO.read(new ByteArrayInputStream(entry.thumbnailPng));
    }

    @Override
    public void checkpoint() throws IOException {
        if (!checkpointsDir.exists() && !checkpointsDir.mkdirs()) {
            throw new IOException("Could not create checkpoints directory " + checkpointsDir);
        }
        File target = new File(checkpointsDir, System.currentTimeMillis() + ".zip");
        File[] files = dir.listFiles();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target)))) {
            if (null != files) {
                for (File f : files) {
                    zip.putNextEntry(new ZipEntry(f.getName()));
                    Files.copy(f.toPath(), zip);
                    zip.closeEntry();
                }
            }
        }
    }

    @Override
    public List<Catalog.CheckpointInfo> listCheckpoints() throws IOException {
        List<Catalog.CheckpointInfo> result = new ArrayList<>();
        File[] files = checkpointsDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.endsWith(".zip");
            }
        });
        if (null != files) {
            for (File f : files) {
                String base = f.getName().substring(0, f.getName().length() - 4);
                try {
                    result.add(new Catalog.CheckpointInfo(Long.parseLong(base), f.length()));
                } catch (NumberFormatException ignored) {
                    // not one of ours; skip it
                }
            }
        }
        result.sort(new Comparator<Catalog.CheckpointInfo>() {
            @Override
            public int compare(Catalog.CheckpointInfo a, Catalog.CheckpointInfo b) {
                return Long.compare(b.timestampMillis, a.timestampMillis);
            }
        });
        return result;
    }

    @Override
    public void restoreCheckpoint(long timestampMillis) throws IOException {
        File source = new File(checkpointsDir, timestampMillis + ".zip");
        if (!source.exists()) {
            throw new IllegalStateException("No checkpoint at " + timestampMillis);
        }
        File[] current = dir.listFiles();
        if (null != current) {
            for (File f : current) {
                Files.delete(f.toPath());
            }
        }
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(source)))) {
            ZipEntry entry;
            while (null != (entry = zip.getNextEntry())) {
                Files.copy(zip, new File(dir, entry.getName()).toPath());
                zip.closeEntry();
            }
        }
    }

    @Override
    public void deleteCheckpoint(long timestampMillis) throws IOException {
        File target = new File(checkpointsDir, timestampMillis + ".zip");
        if (!target.exists()) {
            throw new IOException("No checkpoint at " + timestampMillis);
        }
        Files.delete(target.toPath());
    }

    @Override
    public Catalog.StorageInfo liveStorageInfo() throws IOException {
        File[] files = dir.listFiles();
        int entryCount = 0;
        long totalBytes = 0;
        if (null != files) {
            for (File f : files) {
                if (f.getName().endsWith(".json")) {
                    entryCount++;
                }
                totalBytes += f.length();
            }
        }
        return new Catalog.StorageInfo(dir.getAbsolutePath(), entryCount, totalBytes);
    }
}
