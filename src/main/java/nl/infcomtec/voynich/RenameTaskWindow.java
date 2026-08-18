/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Window;
import java.io.File;
import java.util.List;

/**
 * Runs a {@link ScanRenamer} batch over {@link Config#scanPath} — the
 * concrete {@link TaskWindow} behind the File menu's "Rename to…" action.
 * Collision/blank-target detection already happened in the caller (see
 * {@link Voynich}) via {@link ScanRenamer#plan}; this window executes the
 * resulting plan, and for each file actually renamed on disk, re-keys its
 * catalog entry to match via {@link Catalog#renameEntry} and updates
 * {@link OverviewPanel} in place — same thumbnail/regions/tags, nothing
 * re-decoded. This is deliberately not a Scan: {@code Scan} re-decodes
 * every image from scratch, which is the wrong tool for what's actually
 * just "the catalog's index needs the same filenames the disk now has."
 * Finally updates {@link Config#namingScheme} to the new scheme on
 * success.
 */
public class RenameTaskWindow extends TaskWindow {

    public static final String TASK_TYPE = "rename";

    private final Config config;
    private final Catalog catalog;
    private final OverviewPanel overview;
    private final List<ScanRenamer.Plan> plans;
    private final String toColumn;

    public RenameTaskWindow(Config config, Catalog catalog, OverviewPanel overview,
            List<ScanRenamer.Plan> plans, String toColumn, Window owner) {
        super(TASK_TYPE, "Renaming " + config.scanPath + " to " + toColumn, owner);
        this.config = config;
        this.catalog = catalog;
        this.overview = overview;
        this.plans = plans;
        this.toColumn = toColumn;
    }

    @Override
    protected void runTask() throws Exception {
        publishLine("Renaming " + plans.size() + " file(s) to \"" + toColumn + "\" naming.");
        int renamed = 0;
        int catalogUpdated = 0;
        int done = 0;
        for (ScanRenamer.Plan plan : plans) {
            File src = plan.source;
            if (null != plan.skipReason) {
                publishLine(src.getName() + ": skipped - " + plan.skipReason);
            } else {
                String oldName = src.getName();
                boolean ok = src.renameTo(plan.dest);
                if (ok) {
                    renamed++;
                    publishLine(oldName + " -> " + plan.dest.getName());
                    CatalogEntry renamedEntry = catalog.renameEntry(plan.id, src, plan.dest);
                    if (null != renamedEntry) {
                        catalogUpdated++;
                        overview.renameEntry(renamedEntry);
                    }
                } else {
                    publishLine(oldName + ": FAILED to rename to " + plan.dest.getName());
                }
            }
            done++;
            setProgressPercent(done * 100 / Math.max(1, plans.size()));
        }
        publishLine("Done: " + renamed + " renamed, " + (plans.size() - renamed) + " skipped/failed, "
                + catalogUpdated + " catalog entries re-keyed.");
        if (renamed > 0) {
            config.namingScheme = toColumn;
            Voynich.saveConfig();
            publishLine("Config namingScheme updated to \"" + toColumn + "\".");
        }
    }
}
