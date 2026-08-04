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
            case "list":
                list(catalog, args.length > 1 ? args[1] : null);
                break;
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

    private static void list(Catalog catalog, String filter) throws IOException {
        for (CatalogEntry entry : catalog.listAll()) {
            if (null == filter || entry.filename.contains(filter)) {
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
        System.err.println("  list [filter]              list filenames (optionally containing 'filter')");
        System.err.println("  get <filename>              print the entry's JSON");
        System.err.println("  tag <filename> <text...>    add a tag/note (no-op if already present)");
        System.err.println("  save <filename> [jsonFile]  replace the entry (reads stdin if jsonFile omitted)");
    }
}
