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
 * decoding, just a filename swap. The table's first column, {@code Id}, is
 * the page's permanent {@link CatalogEntry#id} (see its doc — the app's own
 * stable internal handle, never shown to the user) and is deliberately
 * excluded from {@link #columns}, since it's not a display naming scheme
 * itself, just the row key every scheme's value is looked up against. Every
 * other column (e.g. {@code Sequential}, {@code Yale}, {@code Rene}) is one
 * naming scheme; a cell is blank where that scheme has no established name
 * for an irregular/non-foliated page (see {@code SCANS.md}).
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
     * One row of the lookup table: {@link #id} is this page's permanent
     * {@link CatalogEntry#id}; {@code names.get(column)} is that row's
     * filename basename (no extension) under {@code column}'s scheme, or
     * {@code null} if that scheme has no name for this page.
     */
    public static final class Row {

        public final int id;
        public final Map<String, String> names;

        Row(int id, Map<String, String> names) {
            this.id = id;
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
            String[] headerCells = headerLine.split("\t", -1);
            if (headerCells.length == 0 || !"Id".equals(headerCells[0])) {
                throw new IOException(RESOURCE_PATH + "'s first column must be \"Id\"");
            }
            List<String> columns = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            seen.add("Id");
            for (int i = 1; i < headerCells.length; i++) {
                String col = headerCells[i];
                if (col.isEmpty()) {
                    throw new IOException(RESOURCE_PATH + " has an empty column name in its header row");
                }
                if (!seen.add(col)) {
                    throw new IOException(RESOURCE_PATH + " has a duplicate column name in its header row: \"" + col + "\"");
                }
                columns.add(col);
            }
            List<Row> rows = new ArrayList<>();
            Set<Integer> seenIds = new HashSet<>();
            String line;
            while (null != (line = reader.readLine())) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells.length == 0) {
                    throw new IOException(RESOURCE_PATH + " has a row with no Id column");
                }
                int id;
                try {
                    id = Integer.parseInt(cells[0]);
                } catch (NumberFormatException ex) {
                    throw new IOException(RESOURCE_PATH + " has a non-numeric Id: \"" + cells[0] + "\"");
                }
                if (!seenIds.add(id)) {
                    throw new IOException(RESOURCE_PATH + " has a duplicate Id: " + id);
                }
                Map<String, String> names = new HashMap<>();
                for (int i = 0; i < columns.size() && i + 1 < cells.length; i++) {
                    String value = cells[i + 1];
                    if (!value.isEmpty()) {
                        names.put(columns.get(i), value);
                    }
                }
                rows.add(new Row(id, names));
            }
            return new ScanRenamer(columns, rows);
        }
    }

    /**
     * @param id a page's permanent {@link CatalogEntry#id}
     * @return that page's {@link Row}, or {@code null} if {@code id} isn't
     * in the table (shouldn't happen for a real catalog entry, but a
     * caller displaying names shouldn't crash if it does)
     */
    public Row rowFor(int id) {
        for (Row row : rows) {
            if (row.id == id) {
                return row;
            }
        }
        return null;
    }

    /**
     * Resolves a display filename to its permanent {@link CatalogEntry#id}
     * by matching its basename (extension stripped) against every known
     * naming column's values — used by {@link ScanTaskWindow} so a freshly
     * scanned file, whatever scheme its current name follows, gets filed
     * under the right stable id rather than a fresh one.
     *
     * @param filename the file's current display name (e.g. {@code
     * "1r.png"} or {@code "001.jpg"})
     * @return the matching id, or {@code null} if {@code filename} doesn't
     * match any row under any column — e.g. a file outside the known
     * 213-page manuscript
     */
    public Integer idForName(String filename) {
        String basename = stripExtension(filename);
        for (Row row : rows) {
            for (String value : row.names.values()) {
                if (stripExtension(value).equals(basename)) {
                    return row.id;
                }
            }
        }
        return null;
    }

    /**
     * Counts, for each column, how many files in {@code scanDir} have a
     * basename matching that column's value for some row — i.e. how well
     * {@code scanDir}'s actual current filenames fit each known naming
     * scheme. Used to catch {@link Config#namingScheme} being wrong (e.g.
     * its hardcoded {@code "torrent_jpg"} default, when a given
     * {@code scanPath} was actually always named some other way) rather
     * than trusting it blindly — see {@link Voynich#renameScans}.
     *
     * @return column name to match count, highest first is not guaranteed;
     * a caller should compare against {@code files.length} for confidence
     */
    public Map<String, Integer> detectScheme(File scanDir) {
        Map<String, Integer> counts = new HashMap<>();
        File[] files = scanDir.listFiles();
        if (null == files) {
            return counts;
        }
        Set<String> basenames = new HashSet<>();
        for (File f : files) {
            basenames.add(stripExtension(f.getName()));
        }
        for (String column : columns) {
            int count = 0;
            for (Row row : rows) {
                String value = row.names.get(column);
                if (null != value && basenames.contains(stripExtension(value))) {
                    count++;
                }
            }
            counts.put(column, count);
        }
        return counts;
    }

    /**
     * A planned single-file rename, or the reason one isn't happening.
     */
    public static final class Plan {

        public final int id;
        public final File source;
        public final File dest;
        public final String skipReason;

        private Plan(int id, File source, File dest, String skipReason) {
            this.id = id;
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

        final class Matched {

            final int id;
            final File source;
            final String destName;

            Matched(int id, File source, String destName) {
                this.id = id;
                this.source = source;
                this.destName = destName;
            }
        }
        List<Matched> matched = new ArrayList<>();
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
                plans.add(new Plan(row.id, source, null, "no " + toColumn + " name for this page"));
                continue;
            }
            String toBasename = stripExtension(toValue);
            String ext = extensionOf(source.getName());
            String destName = toBasename + ext;
            if (destName.equals(source.getName())) {
                // Already correctly named under toColumn (basename matches,
                // extension unaffected) — nothing to do, not a collision.
                plans.add(new Plan(row.id, source, null, "already named \"" + destName + "\""));
                continue;
            }
            String prior = destByBasename.put(destName, fromName);
            if (null != prior) {
                collidedBasenames.add(destName);
            }
            matched.add(new Matched(row.id, source, destName));
        }

        for (Matched m : matched) {
            if (collidedBasenames.contains(m.destName)) {
                plans.add(new Plan(m.id, m.source, null, "target name \"" + m.destName + "\" is shared by more than one source page"));
                continue;
            }
            File dest = new File(scanDir, m.destName);
            if (dest.exists()) {
                plans.add(new Plan(m.id, m.source, null, "target \"" + m.destName + "\" already exists"));
                continue;
            }
            plans.add(new Plan(m.id, m.source, dest, null));
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
