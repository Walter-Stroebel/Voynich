/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renames the files under {@link Config#scanPath} in place from one naming
 * scheme to another, using the bundled {@code data/scan-naming.tsv} lookup
 * table as a plain 1:1 dictionary — no scan-format knowledge, no image
 * decoding, just a filename swap. The table's first row is the header: each
 * column is one naming scheme (e.g. {@code torrent_jpg}, {@code yale_label},
 * {@code project_png}, {@code rene_voynich_nu}), and each subsequent row is
 * one physical page's name under every scheme that has one — a cell is
 * blank where that scheme has no established name for an irregular/
 * non-foliated page (see {@code SCANS.md}).
 * <p>
 * A rename always preserves the file's real on-disk extension rather than
 * adopting whatever extension (if any) happens to appear in the target
 * column's literal text — {@link OverviewPanel#parseFolio} already treats
 * png/jpg/jpeg interchangeably, so "which naming scheme" and "which image
 * format" are independent questions and this class only ever answers the
 * first one.
 * </p>
 */
public class ScanRenamer {

    private static final String RESOURCE_PATH = "/data/scan-naming.tsv";

    /**
     * One row of the lookup table: {@code names.get(column)} is that row's
     * filename basename (no extension) under {@code column}'s scheme, or
     * {@code null} if that scheme has no name for this page.
     */
    public static final class Row {

        public final Map<String, String> names;

        Row(Map<String, String> names) {
            this.names = names;
        }
    }

    public final List<String> columns;
    public final List<Row> rows;

    private ScanRenamer(List<String> columns, List<Row> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    /**
     * Loads the bundled lookup table.
     *
     * @throws IOException if the resource is missing or malformed
     */
    public static ScanRenamer load() throws IOException {
        try (InputStream in = ScanRenamer.class.getResourceAsStream(RESOURCE_PATH)) {
            if (null == in) {
                throw new IOException("Missing bundled resource " + RESOURCE_PATH);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String headerLine = reader.readLine();
            if (null == headerLine) {
                throw new IOException(RESOURCE_PATH + " is empty");
            }
            List<String> columns = new ArrayList<>();
            for (String col : headerLine.split("\t", -1)) {
                columns.add(col);
            }
            List<Row> rows = new ArrayList<>();
            String line;
            while (null != (line = reader.readLine())) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                Map<String, String> names = new HashMap<>();
                for (int i = 0; i < columns.size() && i < cells.length; i++) {
                    String value = cells[i];
                    if (!value.isEmpty()) {
                        names.put(columns.get(i), value);
                    }
                }
                rows.add(new Row(names));
            }
            return new ScanRenamer(columns, rows);
        }
    }

    /**
     * A planned single-file rename, or the reason one isn't happening.
     */
    public static final class Plan {

        public final File source;
        public final File dest;
        public final String skipReason;

        private Plan(File source, File dest, String skipReason) {
            this.source = source;
            this.dest = dest;
            this.skipReason = skipReason;
        }
    }

    /**
     * Works out what {@link #execute(List)} would do, without touching the
     * filesystem — lets a caller detect target-name collisions across the
     * whole batch and refuse to run at all rather than renaming most of a
     * directory and choking partway through. A collision is itself reported
     * as a per-file skip reason on every row involved, not silently dropped.
     *
     * @param scanDir directory currently named under {@code fromColumn}
     * @param fromColumn current naming scheme (a header from {@link #columns})
     * @param toColumn target naming scheme
     * @return one {@link Plan} per file actually present in {@code scanDir}
     * that matches a {@code fromColumn} name in the table
     */
    public List<Plan> plan(File scanDir, String fromColumn, String toColumn) {
        List<Plan> plans = new ArrayList<>();
        Map<String, String> destByBasename = new HashMap<>();
        Set<String> collidedBasenames = new HashSet<>();

        List<Object[]> matched = new ArrayList<>(); // {File source, String targetBasename}
        File[] files = scanDir.listFiles();
        if (null == files) {
            return plans;
        }
        // Keyed by basename (extension stripped) rather than literal
        // filename: a file's real on-disk extension can differ from
        // whatever extension (if any) the naming table's column value
        // happens to carry — see stripExtension's doc.
        Map<String, File> filesByBasename = new HashMap<>();
        for (File f : files) {
            filesByBasename.put(stripExtension(f.getName()), f);
        }

        for (Row row : rows) {
            String fromName = row.names.get(fromColumn);
            if (null == fromName) {
                continue;
            }
            File source = filesByBasename.get(stripExtension(fromName));
            if (null == source) {
                continue;
            }
            String toValue = row.names.get(toColumn);
            if (null == toValue) {
                plans.add(new Plan(source, null, "no " + toColumn + " name for this page"));
                continue;
            }
            String toBasename = stripExtension(toValue);
            String ext = extensionOf(source.getName());
            String destName = toBasename + ext;
            if (destName.equals(source.getName())) {
                // Already correctly named under toColumn (basename matches,
                // extension unaffected) — nothing to do, not a collision.
                plans.add(new Plan(source, null, "already named \"" + destName + "\""));
                continue;
            }
            String prior = destByBasename.put(destName, fromName);
            if (null != prior) {
                collidedBasenames.add(destName);
            }
            matched.add(new Object[]{source, destName});
        }

        for (Object[] m : matched) {
            File source = (File) m[0];
            String destName = (String) m[1];
            if (collidedBasenames.contains(destName)) {
                plans.add(new Plan(source, null, "target name \"" + destName + "\" is shared by more than one source page"));
                continue;
            }
            File dest = new File(scanDir, destName);
            if (dest.exists()) {
                plans.add(new Plan(source, null, "target \"" + destName + "\" already exists"));
                continue;
            }
            plans.add(new Plan(source, dest, null));
        }
        return plans;
    }

    /**
     * Callback for {@link #execute}, notified once per {@link Plan} as it's
     * handled — either skipped (per its {@code skipReason}) or attempted.
     */
    public interface PlanListener {

        /**
         * @param plan the plan just handled
         * @param renamed {@code true} if the rename actually happened;
         * {@code false} for a skip or a failed {@code renameTo}
         */
        void onPlanHandled(Plan plan, boolean renamed);
    }

    /**
     * Performs every non-skipped {@link Plan} from {@link #plan}, renaming
     * files in place. Callers should have already surfaced skip reasons to
     * the user via {@link #plan} — this just does the filesystem work.
     *
     * @return number of files actually renamed
     */
    public static int execute(List<Plan> plans, PlanListener listener) {
        int renamed = 0;
        for (Plan p : plans) {
            if (null != p.skipReason) {
                listener.onPlanHandled(p, false);
                continue;
            }
            boolean ok = p.source.renameTo(p.dest);
            if (ok) {
                renamed++;
            }
            listener.onPlanHandled(p, ok);
        }
        return renamed;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }

    /**
     * Strips a trailing known scannable-image extension (see
     * {@link ScanTaskWindow#SCANNABLE_EXTENSIONS}) from a naming-column
     * value, if it has one — only {@code project_png} column values do
     * today ({@code "1r.png"}); other columns (e.g. {@code torrent_jpg}'s
     * own values are the source name itself, not a target) or plain
     * extension-free labels (e.g. {@code rene_voynich_nu}'s {@code "f1r"})
     * pass through unchanged. Deliberately doesn't strip any trailing dot
     * segment — a label that happens to contain a period is not this app's
     * business to mangle.
     */
    private static String stripExtension(String value) {
        for (String ext : ScanTaskWindow.SCANNABLE_EXTENSIONS) {
            if (value.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
                return value.substring(0, value.length() - ext.length());
            }
        }
        return value;
    }
}
