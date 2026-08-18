/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a {@link CatalogExporter.Exported} JSON array back in — the
 * non-UI half of Import (see {@code ImportReviewDialog} for the human
 * review this data is never written without). Deliberately has no bulk
 * merge of its own: this class only loads and classifies the file against
 * the local catalog; every actual write is a per-region {@link
 * Catalog#addRegion} call the reviewer explicitly triggers.
 */
public final class CatalogImporter {

    private CatalogImporter() {
    }

    /**
     * @param source a file previously written by {@link
     * CatalogExporter#export} — a {@code .zip} with one JSON entry if its
     * name ends in {@code .zip} (case-insensitive), plain JSON otherwise;
     * same extension convention {@link CatalogExporter#export} itself uses
     * to decide which to write, so a file keeps meaning what its name says
     * @return the parsed records, in file order
     * @throws IOException if the file can't be read or parsed, or a zip
     * source has no entries
     */
    public static List<CatalogExporter.Exported> load(File source) throws IOException {
        CatalogExporter.Exported[] array = source.getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? readZip(source)
                : JSON.getMapper().readValue(source, CatalogExporter.Exported[].class);
        List<CatalogExporter.Exported> result = new ArrayList<>();
        for (CatalogExporter.Exported entry : array) {
            result.add(entry);
        }
        return result;
    }

    /**
     * Reads the first entry of {@code source} (a zip written by {@link
     * CatalogExporter#export}, always exactly one JSON entry) and parses
     * it the same way plain JSON is parsed.
     */
    private static CatalogExporter.Exported[] readZip(File source) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(source)))) {
            ZipEntry entry = zip.getNextEntry();
            if (null == entry) {
                throw new IOException(source + " is a zip file with no entries");
            }
            return JSON.getMapper().readValue(zip, CatalogExporter.Exported[].class);
        }
    }

    /**
     * One imported record, classified against the local catalog — the
     * split {@link #resolvable}/{@link #unresolvable} lets a caller show
     * unresolvable entries plainly rather than silently dropping them
     * (see {@code Voynich.importEntries}).
     */
    public static final class Classified {

        /**
         * Every imported record whose {@link CatalogExporter.Exported#id}
         * resolves to a real local {@link CatalogEntry} — safe to hand to
         * the review UI.
         */
        public final List<CatalogExporter.Exported> resolvable = new ArrayList<>();
        /**
         * Every imported record whose id could not be resolved locally,
         * paired with a human-readable reason — either the id isn't in
         * this app's own bundled {@code data/scan-naming.tsv} at all (the
         * import came from a build with a newer/different table), or the
         * table knows the id but this catalog has never actually scanned
         * that page yet (nothing to add a region onto).
         */
        public final List<String> unresolvable = new ArrayList<>();
    }

    /**
     * @param catalog to resolve each imported id against
     * @param records as loaded by {@link #load}
     * @return the classification — see {@link Classified}
     * @throws IOException if the underlying catalog listing, or the
     * naming table load, fails
     */
    public static Classified classify(Catalog catalog, List<CatalogExporter.Exported> records) throws IOException {
        Classified out = new Classified();
        for (CatalogExporter.Exported record : records) {
            CatalogEntry entry = catalog.loadEntry(record.id);
            if (null != entry) {
                out.resolvable.add(record);
                continue;
            }
            ScanRenamer.Row row;
            try {
                row = ScanRenamer.cached().rowFor(record.id);
            } catch (IOException ex) {
                row = null;
            }
            String reason = null == row
                    ? "id " + record.id + ": not in this app's naming table (possibly a newer/different scan-naming.tsv)"
                    : "id " + record.id + ": known page, but never scanned into this catalog yet";
            out.unresolvable.add(reason);
        }
        return out;
    }
}
