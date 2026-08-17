/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Window;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks {@link Config#scanPath}, decodes each scan image via
 * {@link ColorImage}, and records it in the {@link Catalog} — the concrete
 * {@link TaskWindow} behind the toolbar's Scan button. Files are processed
 * in parallel across every available core (see {@link #runTask()});
 * progress is reported per file as each one finishes, and the
 * {@link OverviewPanel} is updated live, so results appear while the scan
 * is still running rather than only at the end.
 * <p>
 * A file whose catalog entry already has a {@link CatalogEntry.Location}
 * matching this path with the same {@code size}/{@code mtime} is skipped
 * without re-decoding — those two fields exist on {@code Location}
 * specifically for this. Anything new, changed, or missing from the catalog
 * still goes through the full decode.
 * </p>
 */
public class ScanTaskWindow extends TaskWindow {

    public static final String TASK_TYPE = "scan";

    /**
     * Filename extensions {@link #runTask()} accepts as scannable images —
     * PNG (the format this project's own catalog was originally built on)
     * plus JPG/JPEG (Yale Beinecke's easy, default public download; PNG/TIFF
     * take real digging to obtain — see {@code INSTALL.md}). Compared
     * lowercased, so this also covers `.PNG`/`.JPG`/`.JPEG`. Shared with
     * {@link OverviewPanel#parseFolio}, which needs the identical extension
     * set to recognize a folio filename regardless of which of these formats
     * it was scanned in — one canonical list, not two independently
     * maintained ones.
     */
    static final String[] SCANNABLE_EXTENSIONS = {".png", ".jpg", ".jpeg"};

    /**
     * @return {@code true} if {@code name}, lowercased, ends with one of
     * {@link #SCANNABLE_EXTENSIONS}
     */
    static boolean isScannable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : SCANNABLE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private final Config config;
    private final Catalog catalog;
    private final OverviewPanel overview;

    public ScanTaskWindow(Config config, Catalog catalog, OverviewPanel overview, Window owner) {
        super(TASK_TYPE, "Scanning " + config.scanPath, owner);
        this.config = config;
        this.catalog = catalog;
        this.overview = overview;
    }

    @Override
    protected void runTask() throws Exception {
        File[] files = new File(config.scanPath).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return isScannable(name);
            }
        });
        if (null == files) {
            publishLine("Could not list " + config.scanPath);
            return;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        publishLine("Found " + files.length + " image files, scanning with " + cores + " threads.");

        AtomicInteger completed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(cores);
        for (final File file : files) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    scanOne(file, files.length, completed, skipped);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);

        if (isCancelRequested()) {
            publishLine("Cancelled after " + completed.get() + " of " + files.length + " files.");
        } else {
            publishLine("Scan complete: " + files.length + " files, " + skipped.get() + " unchanged.");
        }
    }

    /**
     * Catalogs one file — the unit of work submitted per-file to the thread
     * pool in {@link #runTask()}. Checked in, not just fired off blind: skips
     * entirely if a cancel has already landed by the time this task actually
     * starts (queued-but-not-yet-run tasks from a large batch bail out cheaply
     * this way rather than each doing a full decode after the user cancelled).
     *
     * @param file the file to catalog
     * @param total total file count, for the progress percentage
     * @param completed shared counter, incremented once this file is done
     * @param skipped shared counter, incremented if this file was unchanged
     */
    private void scanOne(File file, int total, AtomicInteger completed, AtomicInteger skipped) {
        if (isCancelRequested()) {
            return;
        }
        try {
            CatalogEntry existing = catalog.loadEntry(file.getName());
            if (unchanged(existing, file)) {
                overview.addOrUpdate(existing);
                publishLine(file.getName() + ": unchanged, skipped.");
                skipped.incrementAndGet();
            } else {
                ColorImage ci = new ColorImage(file);
                CatalogEntry entry = catalog.recordSighting(
                        file.getName(), file, ci.w, ci.h, ci.labIndex.size(),
                        ci.thumbnailUniqueColors, ci.thumbnail);
                overview.addOrUpdate(entry, ci.thumbnail);
                publishLine(file.getName() + ": " + ci.w + "x" + ci.h + ", "
                        + ci.labIndex.size() + " colors.");
            }
        } catch (Exception ex) {
            publishLine(file.getName() + ": FAILED - " + ex.getMessage());
        }
        setProgressPercent((int) (completed.incrementAndGet() * 100L / total));
    }

    /**
     * @param entry the file's existing catalog entry, or {@code null} if
     * never seen before
     * @param file the file on disk being considered
     * @return {@code true} if {@code entry} already has a {@link
     * CatalogEntry.Location} for this exact path with matching
     * {@code size}/{@code mtime}, meaning the file can be trusted unchanged
     * since it was last decoded
     */
    private static boolean unchanged(CatalogEntry entry, File file) {
        if (null == entry) {
            return false;
        }
        String path = file.getAbsolutePath();
        for (CatalogEntry.Location loc : entry.locations) {
            if (loc.path.equals(path)) {
                return loc.size == file.length() && loc.mtime == file.lastModified();
            }
        }
        return false;
    }
}
