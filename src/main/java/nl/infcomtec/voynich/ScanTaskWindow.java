/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Window;
import java.io.File;
import java.io.FilenameFilter;

/**
 * Walks {@link Config#scanPath}, decodes each PNG via {@link ColorImage},
 * and records it in the {@link Catalog} — the concrete {@link TaskWindow}
 * behind the toolbar's Scan button. Progress is reported per file; the
 * {@link OverviewPanel} is updated live as each one is cataloged, so results
 * appear while the scan is still running rather than only at the end.
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
                return name.endsWith(".png");
            }
        });
        if (null == files) {
            publishLine("Could not list " + config.scanPath);
            return;
        }
        publishLine("Found " + files.length + " PNG files.");
        int skipped = 0;
        for (int i = 0; i < files.length; i++) {
            if (isCancelRequested()) {
                publishLine("Cancelled after " + i + " of " + files.length + " files.");
                return;
            }
            File file = files[i];
            try {
                CatalogEntry existing = catalog.loadEntry(file.getName());
                if (unchanged(existing, file)) {
                    overview.addOrUpdate(existing);
                    publishLine(file.getName() + ": unchanged, skipped.");
                    skipped++;
                } else {
                    ColorImage ci = new ColorImage(file);
                    CatalogEntry entry = catalog.recordSighting(
                            file.getName(), file, ci.w, ci.h, ci.labIndex.size(), ci.thumbnail);
                    overview.addOrUpdate(entry, ci.thumbnail);
                    publishLine(file.getName() + ": " + ci.w + "x" + ci.h + ", "
                            + ci.labIndex.size() + " colors.");
                }
            } catch (Exception ex) {
                publishLine(file.getName() + ": FAILED - " + ex.getMessage());
            }
            setProgressPercent((int) ((i + 1) * 100L / files.length));
        }
        publishLine("Scan complete: " + files.length + " files, " + skipped + " unchanged.");
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
