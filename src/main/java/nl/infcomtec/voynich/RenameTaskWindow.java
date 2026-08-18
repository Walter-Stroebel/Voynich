/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Window;
import java.io.File;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * Runs a {@link ScanRenamer} batch over {@link Config#scanPath} — the
 * concrete {@link TaskWindow} behind the File menu's "Rename to…" action.
 * Collision/blank-target detection already happened in the caller (see
 * {@link Voynich}) via {@link ScanRenamer#plan}; this window just executes
 * the resulting plan and logs each file's outcome, then updates
 * {@link Config#namingScheme} to the new scheme on success. The catalog
 * itself is unaffected by a rename — it's still keyed by the old filenames
 * until a Scan reconciles it — so {@code onDone} is where the caller
 * re-triggers Scan to make {@link OverviewPanel} reflect the new names.
 */
public class RenameTaskWindow extends TaskWindow {

    public static final String TASK_TYPE = "rename";

    private final Config config;
    private final List<ScanRenamer.Plan> plans;
    private final String toColumn;
    private final Runnable onDone;

    public RenameTaskWindow(Config config, List<ScanRenamer.Plan> plans, String toColumn, Window owner, Runnable onDone) {
        super(TASK_TYPE, "Renaming " + config.scanPath + " to " + toColumn, owner);
        this.config = config;
        this.plans = plans;
        this.toColumn = toColumn;
        this.onDone = onDone;
    }

    @Override
    protected void runTask() throws Exception {
        publishLine("Renaming " + plans.size() + " file(s) to \"" + toColumn + "\" naming.");
        final int[] done = {0};
        int renamed = ScanRenamer.execute(plans, new ScanRenamer.PlanListener() {
            @Override
            public void onPlanHandled(ScanRenamer.Plan plan, boolean ok) {
                File src = plan.source;
                if (null != plan.skipReason) {
                    publishLine(src.getName() + ": skipped - " + plan.skipReason);
                } else if (ok) {
                    publishLine(src.getName() + " -> " + plan.dest.getName());
                } else {
                    publishLine(src.getName() + ": FAILED to rename to " + plan.dest.getName());
                }
                done[0]++;
                setProgressPercent(done[0] * 100 / Math.max(1, plans.size()));
            }
        });
        publishLine("Done: " + renamed + " renamed, " + (plans.size() - renamed) + " skipped/failed.");
        if (renamed > 0) {
            config.namingScheme = toColumn;
            Voynich.saveConfig();
            publishLine("Config namingScheme updated to \"" + toColumn + "\".");
            SwingUtilities.invokeLater(onDone);
        }
    }
}
