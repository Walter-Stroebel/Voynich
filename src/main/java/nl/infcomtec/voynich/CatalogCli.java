/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Command-line access to whichever {@link Catalog} backend {@code ~/.infVoy.json}
 * selects (MySQL or {@link FileCatalog}) — the tool this project kept
 * reinventing as a throwaway one-shot {@code main} class every time an entry
 * needed reading or a tag needed adding. Not a Swing app; run via:
 * <pre>
 * java -cp target/Voynich-1.0-jar-with-dependencies.jar nl.infcomtec.voynich.CatalogCli &lt;command&gt; [args]
 * </pre>
 * (the {@code -cp} plus explicit class name bypasses the fat jar's GUI
 * {@code Main-Class}, so no packaging changes were needed for this).
 */
public class CatalogCli {

    public static void main(String[] args) throws IOException {
        if (0 == args.length) {
            usage();
            return;
        }
        File configFile = new File(System.getProperty("user.home"), ".infVoy.json");
        Config cfg = JSON.getMapper().readValue(configFile, Config.class);
        Catalog catalog = Catalog.open(cfg);

        String command = args[0];
        switch (command) {
            case "list": {
                List<String> rest = List.of(args).subList(1, args.length);
                boolean invert = rest.contains("-v") || rest.contains("--invert");
                String filter = rest.stream().filter(a -> !a.equals("-v") && !a.equals("--invert"))
                        .findFirst().orElse(null);
                list(catalog, filter, invert);
                break;
            }
            case "get":
                requireArgs(args, 2, "get <filename>");
                get(catalog, args[1]);
                break;
            case "tag":
                requireArgs(args, 3, "tag <filename> <text...>");
                tag(catalog, args[1], String.join(" ", List.of(args).subList(2, args.length)));
                break;
            case "save":
                requireArgs(args, 2, "save <filename> [jsonFile, else stdin]");
                save(catalog, args[1], args.length > 2 ? args[2] : null);
                break;
            case "checkpoint":
                catalog.checkpoint();
                System.out.println("checkpointed");
                break;
            case "restore":
                try {
                    catalog.restoreLatestCheckpoint();
                    System.out.println("restored latest checkpoint");
                } catch (IllegalStateException ex) {
                    System.err.println(ex.getMessage());
                    System.exit(1);
                }
                break;
            default:
                usage();
        }
    }

    private static void requireArgs(String[] args, int min, String usage) {
        if (args.length < min) {
            System.err.println("Usage: " + usage);
            System.exit(1);
        }
    }

    /**
     * Matches {@code filter} the same way {@code OverviewPanel.filter()}
     * does: case-insensitive substring search over the entry's whole JSON
     * representation, not just the filename — so it also catches hits in
     * tags, torrentJpg, locations, etc. {@code invert} flips the match, same
     * as {@code OverviewPanel.filter()}'s checkbox — e.g. to find entries
     * still missing a given tag.
     */
    private static void list(Catalog catalog, String filter, boolean invert) throws IOException {
        String needle = null == filter ? null : filter.toLowerCase();
        for (CatalogEntry entry : catalog.listAll()) {
            boolean matches = null == needle || JSON.writeValueAsString(entry).toLowerCase().contains(needle);
            if (null == needle || matches != invert) {
                System.out.println(entry.filename + "\t" + entry.width + "x" + entry.height
                        + "\ttags=" + entry.tags);
            }
        }
    }

    private static void get(Catalog catalog, String filename) throws IOException {
        CatalogEntry entry = catalog.loadEntry(filename);
        if (null == entry) {
            System.err.println("No entry for " + filename);
            System.exit(1);
        }
        System.out.println(JSON.writeValueAsPretty(entry));
    }

    private static void tag(Catalog catalog, String filename, String text) throws IOException {
        if (null == catalog.loadEntry(filename)) {
            System.err.println("No entry for " + filename);
            System.exit(1);
        }
        catalog.addTag(filename, text);
        System.out.println("tagged: " + filename + " -> " + catalog.loadEntry(filename).tags);
    }

    /**
     * Same two sanity checks as {@code OverviewPanel.showJsonEditor}'s Save
     * button: this is the app's own database with no external attacker, so
     * only honest mistakes are guarded against — the JSON must parse,
     * {@link CatalogEntry#filename} must match {@code filename} (it's the
     * catalog key), and {@link CatalogEntry#locations} must not have been
     * emptied out.
     */
    private static void save(Catalog catalog, String filename, String jsonFile) throws IOException {
        CatalogEntry existing = catalog.loadEntry(filename);
        if (null == existing) {
            System.err.println("No entry for " + filename + " — CatalogCli only edits existing entries.");
            System.exit(1);
        }
        String json = null == jsonFile
                ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(new File(jsonFile).toPath());
        CatalogEntry parsed;
        try {
            parsed = JSON.getMapper().readValue(json, CatalogEntry.class);
        } catch (IOException ex) {
            System.err.println("Not valid JSON: " + ex.getMessage());
            System.exit(1);
            return;
        }
        if (null == parsed.filename || !parsed.filename.equals(filename)) {
            System.err.println("filename must stay \"" + filename + "\" — it's the catalog key.");
            System.exit(1);
        }
        if (!existing.locations.isEmpty() && parsed.locations.isEmpty()) {
            System.err.println("locations went from " + existing.locations.size() + " entries to 0 — refusing to save.");
            System.exit(1);
        }
        catalog.save(parsed, catalog.loadThumbnail(filename));
        System.out.println("saved: " + filename);
    }

    private static void usage() {
        System.err.println("Usage: CatalogCli <command> [args]");
        System.err.println("  list [-v|--invert] [filter]  list filenames (optionally whose JSON contains/lacks 'filter', case-insensitive)");
        System.err.println("  get <filename>              print the entry's JSON");
        System.err.println("  tag <filename> <text...>    add a tag/note (no-op if already present)");
        System.err.println("  save <filename> [jsonFile]  replace the entry (reads stdin if jsonFile omitted)");
        System.err.println("  checkpoint                  clone the whole catalog's current state");
        System.err.println("  restore                     discard everything since the last checkpoint");
    }
}
