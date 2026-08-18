/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Metadata-only export: never touches image bytes, on the reasoning laid
 * out in {@code CLAUDE.md}'s "Catalog persistence" section — the traced
 * regions/tags are Walter's own judgment calls over his copy of the scans,
 * not anything re-derivable from the images, so they're what's worth
 * handing to another researcher, not the pixels themselves (which anyone
 * can already get from the original scan set).
 * <p>
 * Writes one JSON array of {@link Exported} records — a trimmed
 * {@link CatalogEntry}, {@link CatalogEntry#thumbnailPng}/
 * {@link CatalogEntry#locations} dropped since neither means anything
 * outside this catalog's own storage.
 */
public final class CatalogExporter {

    private CatalogExporter() {
    }

    /**
     * @param catalog to filter/read entries from
     * @return every entry where {@link CatalogEntry#regions} has more than
     * just the synthetic whole page, or {@link CatalogEntry#tags} carries
     * real data — i.e. everything export's "Marked" scope means by "has
     * real data"
     */
    public static List<CatalogEntry> marked(Catalog catalog) throws IOException {
        List<CatalogEntry> result = new ArrayList<>();
        for (CatalogEntry entry : catalog.listAll()) {
            if (isMarked(entry)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static boolean isMarked(CatalogEntry entry) {
        if (entry.regions.size() > 1) {
            return true;
        }
        return !entry.tags.isEmpty();
    }

    /**
     * One entry's exported form: everything in {@link CatalogEntry} except
     * {@link CatalogEntry#thumbnailPng}/{@link CatalogEntry#locations} (both
     * meaningless outside this catalog's own on-disk storage) and
     * {@link CatalogEntry#torrentJpg}/{@link CatalogEntry#filename} (naming-
     * scheme aliases belonging to {@code data/scan-naming.tsv}, not per-
     * entry tag/region judgment data — exporting them here would duplicate
     * that table's job, and {@code filename} isn't even stable, changing on
     * rename). {@link CatalogEntry#id} is the permanent key and travels
     * instead — resolvable back to any naming scheme's name via {@code
     * CatalogCli alias}.
     */
    public static final class Exported {

        public int id;
        public List<String> tags = new ArrayList<>();
        public List<CatalogEntry.Region> regions = new ArrayList<>();
    }

    /**
     * @param entry source
     * @param exporterName filled into any {@link CatalogEntry.Region#author}
     * that is blank in the source entry — attribution for this export only,
     * never written back to the catalog itself
     * @return the trimmed, attributed record
     */
    public static Exported toExported(CatalogEntry entry, String exporterName) {
        Exported out = new Exported();
        out.id = entry.id;
        out.tags = new ArrayList<>(entry.tags);
        for (CatalogEntry.Region region : entry.regions) {
            CatalogEntry.Region copy = new CatalogEntry.Region();
            copy.kind = region.kind;
            copy.author = (null == region.author || region.author.isEmpty()) ? exporterName : region.author;
            copy.polygon = new ArrayList<>(region.polygon);
            copy.angle = region.angle;
            copy.parentIndex = region.parentIndex;
            out.regions.add(copy);
        }
        return out;
    }

    /**
     * The single JSON entry's name inside a zip export — a fixed name
     * rather than deriving one from {@code target}, since the JSON content
     * itself (not the outer zip's filename) is what {@link
     * CatalogImporter#load} actually parses; the entry name is never shown
     * to a user.
     */
    private static final String ZIP_ENTRY_NAME = "export.json";

    /**
     * Writes {@code entries} to {@code target} as one JSON array of
     * {@link Exported} records, each attributed via {@link #toExported} —
     * either pretty-printed JSON directly, or, if {@code target}'s name
     * ends in {@code .zip} (case-insensitive), that same JSON deflated
     * inside a one-entry zip archive. This data compresses heavily (a
     * hand-traced polygon's repeated {@code "x"}/{@code "y"} keys and
     * indentation whitespace are exactly what deflate is good at), and
     * this project already has zip-writing precedent in {@code
     * FileCatalog.checkpoint} — same {@code java.util.zip}, no new
     * dependency.
     */
    public static void export(List<CatalogEntry> entries, String exporterName, File target) throws IOException {
        List<Exported> out = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            out.add(toExported(entry, exporterName));
        }
        byte[] json = JSON.getMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(out);
        if (target.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target)))) {
                zip.putNextEntry(new ZipEntry(ZIP_ENTRY_NAME));
                zip.write(json);
                zip.closeEntry();
            }
        } else {
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(json);
            }
        }
    }
}
