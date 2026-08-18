/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Command-line access to the {@link FileCatalog} — the tool this project
 * kept reinventing as a throwaway one-shot {@code main} class every time an
 * entry needed reading or a tag needed adding. Not a Swing app; run via:
 * <pre>
 * java -cp target/Voynich-1.0-jar-with-dependencies.jar nl.infcomtec.voynich.CatalogCli [--config path] &lt;command&gt; [args]
 * </pre>
 * (the {@code -cp} plus explicit class name bypasses the fat jar's GUI
 * {@code Main-Class}, so no packaging changes were needed for this). An
 * optional {@code --config}/{@code -c &lt;path&gt;}, found anywhere in the
 * argument list and stripped before command dispatch, overrides
 * {@link Voynich#configFile} (the default {@code ~/.infVoy/config.json}) —
 * for a user running more than one {@link Config#scanPath}/catalog pair
 * side by side, since the GUI's own config-file-as-first-arg override
 * (see {@code Voynich.main}) has no CatalogCli equivalent otherwise.
 */
public class CatalogCli {

    public static void main(String[] args) throws IOException {
        File configFile = Voynich.configFile;
        List<String> argList = new ArrayList<>(List.of(args));
        int configIdx = argList.indexOf("--config");
        if (configIdx < 0) {
            configIdx = argList.indexOf("-c");
        }
        if (configIdx >= 0) {
            if (configIdx + 1 >= argList.size()) {
                System.err.println("--config requires a path");
                System.exit(1);
                return;
            }
            configFile = new File(argList.get(configIdx + 1));
            argList.remove(configIdx + 1);
            argList.remove(configIdx);
        }
        args = argList.toArray(new String[0]);

        if (0 == args.length) {
            usage();
            return;
        }
        Config cfg = JSON.getMapper().readValue(configFile, Config.class);
        Catalog catalog = Catalog.open(cfg);
        // Voynich.launchImageView reads the static Voynich.config, not any config passed
        // to it directly — without this, --view (and the new two-page/matrix commands
        // below) silently used whatever Voynich.config happened to be (usually null)
        // instead of the config this process just loaded.
        Voynich.config = cfg;

        String command = args[0];
        switch (command) {
            case "list": {
                List<String> rest = List.of(args).subList(1, args.length);
                boolean invert = rest.contains("-v") || rest.contains("--invert");
                String filter = null;
                for (String a : rest) {
                    if (!a.equals("-v") && !a.equals("--invert")) {
                        filter = a;
                        break;
                    }
                }
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
            case "extract":
                requireArgs(args, 3, "extract <filename> --pixel x,y | --region x,y,w,h [--format rgb|lab|hex] "
                        + "| --content-area [--out path] | --region-name <kind> [--out path]");
                extract(catalog, args);
                break;
            case "vision":
                requireArgs(args, 3, "vision <filename> [<filename>...] <question...> "
                        + "[--content-area | --region-name <kind>] [--combine] "
                        + "(prefix the question with -- once more than one filename is given)");
                vision(cfg, catalog, args);
                break;
            case "two-page":
                requireArgs(args, 2, "two-page <filename> [<other-filename>] [--out <path>]");
                twoPage(catalog, args);
                break;
            case "matrix":
                requireArgs(args, 2, "matrix <filename> [<filename>...] [--out <path>]");
                matrix(catalog, args);
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
        CatalogEntry entry = catalog.loadEntryByFilename(filename);
        if (null == entry) {
            System.err.println("No entry for " + filename);
            System.exit(1);
        }
        System.out.println(JSON.writeValueAsPretty(entry));
    }

    private static void tag(Catalog catalog, String filename, String text) throws IOException {
        CatalogEntry entry = catalog.loadEntryByFilename(filename);
        if (null == entry) {
            System.err.println("No entry for " + filename);
            System.exit(1);
            return;
        }
        catalog.addTag(entry.id, text);
        System.out.println("tagged: " + filename + " -> " + catalog.loadEntry(entry.id).tags);
    }

    /**
     * Same two sanity checks as {@code OverviewPanel.showJsonEditor}'s Save
     * button: this is the app's own database with no external attacker, so
     * only honest mistakes are guarded against — the JSON must parse,
     * {@link CatalogEntry#id} must match the existing entry's (it's the
     * catalog key), and {@link CatalogEntry#locations} must not have been
     * emptied out.
     */
    private static void save(Catalog catalog, String filename, String jsonFile) throws IOException {
        CatalogEntry existing = catalog.loadEntryByFilename(filename);
        if (null == existing) {
            System.err.println("No entry for " + filename + " — CatalogCli only edits existing entries.");
            System.exit(1);
            return;
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
        if (parsed.id != existing.id) {
            System.err.println("id must stay " + existing.id + " — it's the catalog key.");
            System.exit(1);
        }
        if (!existing.locations.isEmpty() && parsed.locations.isEmpty()) {
            System.err.println("locations went from " + existing.locations.size() + " entries to 0 — refusing to save.");
            System.exit(1);
        }
        catalog.save(parsed, catalog.loadThumbnail(existing.id));
        System.out.println("saved: " + filename);
    }

    /**
     * Prints or exports real pixel colour, decoded and Lab-converted through
     * the same {@link ColorImage}/{@link ColorBase} path the GUI's colour
     * views use — not a reimplementation, so results are guaranteed
     * consistent with {@code FrequencyBarChart}/{@code DeltaEHeatmap}.
     * <p>
     * {@code --pixel x,y} prints one line to stdout in the requested format
     * ({@code rgb}|{@code lab}|{@code hex}, default all three). {@code
     * --region x,y,w,h} writes a binary blob — row-major, no header — to
     * {@code --out} if given, else stdout; a one-line JSON manifest
     * (dimensions, format, dtype, byte count) always goes to stderr, so
     * piping stdout straight into a numpy {@code fromfile} never sees it
     * mixed in with the data.
     * </p>
     * <p>
     * {@code --region} may be repeated to pull several regions from one
     * decode — {@link ColorImage}'s constructor (full-page decode plus the
     * colour-cache scan) dominates runtime for these manuscript-sized scans,
     * far more than extracting any one region does, so re-invoking the JVM
     * per region would mean paying that cost again for no reason. With more
     * than one {@code --region}, {@code --out} is required and used as a
     * prefix — region <i>n</i> (in the order given) is written to
     * {@code <out>.<n>} — and stderr gets one JSON object per line instead
     * of a single line.
     * </p>
     */
    private static void extract(Catalog catalog, String[] args) throws IOException {
        String filename = args[1];
        CatalogEntry entry = catalog.loadEntryByFilename(filename);
        if (null == entry) {
            System.err.println("No entry for " + filename);
            System.exit(1);
            return;
        }
        File imgFile = resolveExistingLocation(entry);
        if (null == imgFile) {
            System.err.println("No on-disk location found for " + filename);
            System.exit(1);
            return;
        }

        int[] pixel = null;
        List<int[]> regions = new ArrayList<>();
        String format = null;
        String out = null;
        boolean contentArea = false;
        String regionName = null;
        boolean view = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--pixel":
                    pixel = parseInts(args[++i], 2, "--pixel x,y");
                    break;
                case "--region":
                    regions.add(parseInts(args[++i], 4, "--region x,y,w,h"));
                    break;
                case "--content-area":
                    contentArea = true;
                    break;
                case "--region-name":
                    regionName = args[++i];
                    break;
                case "--format":
                    format = args[++i];
                    break;
                case "--out":
                    out = args[++i];
                    break;
                case "--view":
                    view = true;
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(1);
                    return;
            }
        }
        if (view && !contentArea && null == regionName) {
            System.err.println("--view only applies to --content-area or --region-name");
            System.exit(1);
            return;
        }
        int modes = (null != pixel ? 1 : 0) + (!regions.isEmpty() ? 1 : 0) + (contentArea ? 1 : 0)
                + (null != regionName ? 1 : 0);
        if (0 == modes) {
            System.err.println("Need --pixel x,y, --region x,y,w,h, --content-area, or --region-name <kind>");
            System.exit(1);
            return;
        }
        if (modes > 1) {
            System.err.println("--pixel, --region, --content-area, and --region-name are mutually exclusive");
            System.exit(1);
            return;
        }
        if (regions.size() > 1 && null == out) {
            System.err.println("Multiple --region needs --out (used as a prefix: <out>.0, <out>.1, ...)");
            System.exit(1);
            return;
        }

        if (contentArea) {
            extractContentArea(entry, entry.mainRegion(), imgFile, out, view);
            return;
        }
        if (null != regionName) {
            extractRegionByName(entry, imgFile, regionName, out, view);
            return;
        }

        ColorImage img = new ColorImage(imgFile);

        if (null != pixel) {
            int x = pixel[0], y = pixel[1];
            if (x < 0 || y < 0 || x >= img.w || y >= img.h) {
                System.err.println("Pixel (" + x + "," + y + ") outside " + img.w + "x" + img.h);
                System.exit(1);
                return;
            }
            printPixel(img.pixels[y * img.w + x], null == format ? List.of("rgb", "lab", "hex") : List.of(format));
            return;
        }

        if (null == format) {
            format = "lab";
        }
        for (int n = 0; n < regions.size(); n++) {
            int[] region = regions.get(n);
            int x0 = region[0], y0 = region[1], rw = region[2], rh = region[3];
            if (rw <= 0 || rh <= 0 || x0 < 0 || y0 < 0 || x0 + rw > img.w || y0 + rh > img.h) {
                System.err.println("Region " + n + " outside " + img.w + "x" + img.h);
                System.exit(1);
                return;
            }
            byte[] blob = buildBlob(img, x0, y0, rw, rh, format);
            String regionOut = regions.size() > 1 ? out + "." + n : out;
            if (null == regionOut) {
                System.out.write(blob);
                System.out.flush();
            } else {
                Files.write(new File(regionOut).toPath(), blob);
            }
            System.err.println(String.format(
                    "{\"filename\":\"%s\",\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d,"
                    + "\"format\":\"%s\",\"dtype\":\"%s\",\"order\":\"row-major\",\"bytes\":%d%s}",
                    filename, x0, y0, rw, rh, format, "rgb".equals(format) ? "uint8" : "float32", blob.length,
                    null == regionOut ? "" : ",\"path\":\"" + regionOut + "\""));
        }
    }

    /**
     * {@code --content-area}: crops {@code imgFile} to {@code entry}'s
     * traced {@link CatalogEntry#mainRegion()} bounding box, rotates it
     * upright by {@link CatalogEntry.Region#angle} (the same angle
     * {@code RegionViewer}'s mouse wheel sets and applies live, but only
     * ever bakes into the GUI's rendering — never into a saved file until
     * now), and writes the result as a PNG — either to {@code out} or, if
     * {@code null}, straight to stdout — unless {@code view} is set, in which
     * case {@code out} (generating a {@code /tmp} path if not given) is always
     * used as a real file and then opened in a detached infimg process via
     * {@link Voynich#launchImageView}, the CLI equivalent of
     * {@code RegionViewer}'s "Save to /tmp & View" button — the "show me
     * something" path for an agent driving this CLI without a GUI window of
     * its own to display the result in. Doesn't go through {@link ColorImage}
     * (no CIELab decode needed for a raw crop), so it's cheaper than the
     * {@code --pixel}/{@code --region} modes above.
     */
    private static void extractContentArea(CatalogEntry entry, CatalogEntry.Region main, File imgFile, String out,
            boolean view) throws IOException {
        if (null == main) {
            System.err.println("No content area traced yet for " + entry.filename);
            System.exit(1);
            return;
        }
        BufferedImage full = ImageIO.read(imgFile);
        List<Point> vertices = new ArrayList<>(main.polygon.size());
        for (CatalogEntry.Vertex v : main.polygon) {
            vertices.add(new Point(v.x, v.y));
        }
        BufferedImage cropped = BitSet2D.cropToPolygon(full, vertices);
        if (0.0 != main.angle) {
            cropped = BitSet2D.rotateUpright(cropped, main.angle);
        }

        if (view && null == out) {
            out = File.createTempFile(entry.filename + "." + main.kind + ".", ".png").getAbsolutePath();
        }
        if (null == out) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", buf);
            System.out.write(buf.toByteArray());
            System.out.flush();
        } else {
            ImageIO.write(cropped, "png", new File(out));
        }
        System.err.println(String.format(
                "{\"filename\":\"%s\",\"width\":%d,\"height\":%d,\"vertices\":%d%s}",
                entry.filename, cropped.getWidth(), cropped.getHeight(), main.polygon.size(),
                null == out ? "" : ",\"path\":\"" + out + "\""));
        if (view) {
            Voynich.launchImageView(new File(out));
        }
    }

    /**
     * {@code --region-name <kind>}: same crop-to-polygon path as
     * {@code --content-area}, but for every {@link CatalogEntry.Region}
     * matching {@link CatalogEntry.Region#kind} (case-insensitive exact
     * match) instead of the fixed {@code regions.get(1)} main area — e.g.
     * pulling every figure traced with the same label (a page can hold
     * several regions sharing one {@code kind}, as opposed to
     * {@link CatalogEntry#mainRegion()} which is always exactly
     * {@code regions.get(1)}). Index 0, the synthetic whole-page region, is
     * never matched — its {@code kind} is always {@code "page"}, which isn't
     * a name anyone traced. One match writes straight to {@code out} (or
     * stdout); more than one match requires {@code out} as a prefix, same
     * convention as multiple {@code --region}: {@code <out>.0},
     * {@code <out>.1}, ...
     */
    private static void extractRegionByName(CatalogEntry entry, File imgFile, String kind, String out,
            boolean view) throws IOException {
        List<CatalogEntry.Region> matches = new ArrayList<>();
        for (int i = 1; i < entry.regions.size(); i++) {
            CatalogEntry.Region r = entry.regions.get(i);
            if (r.kind.equalsIgnoreCase(kind)) {
                matches.add(r);
            }
        }
        if (matches.isEmpty()) {
            System.err.println("No region with kind \"" + kind + "\" for " + entry.filename);
            System.exit(1);
            return;
        }
        if (matches.size() > 1 && null == out && !view) {
            System.err.println("Multiple regions match kind \"" + kind + "\" (" + matches.size()
                    + ") — need --out as a prefix: <out>.0, <out>.1, ...");
            System.exit(1);
            return;
        }
        for (int n = 0; n < matches.size(); n++) {
            String matchOut = matches.size() > 1 && null != out ? out + "." + n : out;
            extractContentArea(entry, matches.get(n), imgFile, matchOut, view);
        }
    }

    /**
     * {@code vision <filename> [<filename>...] <question...> [--content-area |
     * --region-name <kind>] [--combine]}: uploads one or more pages (or, with
     * {@code --content-area}/{@code --region-name}, the same cropped-to-polygon
     * PNG {@code extract} would write for each — reusing
     * {@link #extractContentArea}/{@link #extractRegionByName}'s crop path via
     * a temp file rather than duplicating it) to the vision pipeline via
     * {@link VisionClient} and prints the model's free-text answer(s). Mirrors
     * {@code Voynich}'s "Selected → Ask Vision…" menu action, minus its
     * interactive confirms — a CLI invocation is already an explicit, scripted
     * choice, so there's nothing to confirm the way an accidental multi-select
     * click needs guarding against.
     * <p>
     * Single filename: unchanged from the original single-file form, question
     * words follow directly. Two or more filenames: the boundary between
     * filenames and the free-text question is ambiguous (both are trailing
     * positional args), so a literal {@code --} is required before the
     * question once more than one filename is given. {@code --combine}
     * (valid only with exactly 2 filenames, and incompatible with
     * {@code --content-area}/{@code --region-name} — that path is whole-page
     * only) composes both into one side-by-side image via {@link ImageGrid},
     * each source scaled to fit {@link VisionClient#MAX_DIMENSION}{@code / 2}
     * per side first — the same pre-composite downscale that fixed a real bug
     * in the GUI's equivalent path (an uncapped composite produced a
     * ~50MB/21-megapixel PNG the vision model failed on with a confabulated
     * "I can't see an image" answer instead of a clean error; never rely on
     * {@link VisionClient#uploadImageDownscaled}'s post-hoc resize to save an
     * already-oversized upload) — and asks once. Without {@code --combine},
     * multiple filenames fire one sequential call per file (never concurrent),
     * each answer printed prefixed with its filename.
     * <p>
     * Each filename is resolved as a catalog entry first; if that fails, it's
     * tried as a literal on-disk file path instead (added 2026-08-14 so a
     * {@code two-page}/{@code matrix} composite — never itself a cataloged
     * entry — can be asked about directly, e.g.
     * {@code vision /tmp/spread.png "..."}). A nonexistent path either way
     * still fails with the original "No entry for &lt;filename&gt;" message,
     * not a new one, so a script checking for that exact string isn't broken.
     * {@code --content-area}/{@code --region-name} only make sense against a
     * real {@link CatalogEntry}'s traced regions, so either is rejected if
     * any filename in the batch resolved as a raw path.
     * <p>
     * See {@code CLAUDE.md}'s "Vision Pipeline (MCP)" section for the model
     * this calls and its known limits (spot-check, don't trust blindly).
     */
    private static void vision(Config cfg, Catalog catalog, String[] args) throws IOException {
        int separatorIdx = -1;
        for (int j = 1; j < args.length; j++) {
            if ("--".equals(args[j])) {
                separatorIdx = j;
                break;
            }
        }

        // No -- given: exactly one filename at args[1], unchanged from the original
        // single-file form — everything after it (flags aside) is the question, no
        // ambiguity to resolve. A -- was given: everything before it (flags aside) is
        // one or more filenames, everything after it is the question, unconditionally
        // (even a single word after -- is the whole question, never re-parsed as a flag,
        // so a question that happens to start with "--" still works).
        List<String> filenames = new ArrayList<>();
        boolean contentArea = false;
        boolean combine = false;
        String regionName = null;
        List<String> questionParts = new ArrayList<>();
        if (separatorIdx < 0) {
            filenames.add(args[1]);
            // A forgotten -- before a second filename would otherwise silently fold that
            // filename into the question text — catch the common case (the very next word
            // itself names a real catalog entry) and fail loudly instead.
            if (args.length > 2 && args[2].endsWith(".png") && null != catalog.loadEntryByFilename(args[2])) {
                System.err.println("More than one filename requires -- before the question");
                System.exit(1);
                return;
            }
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--content-area":
                        contentArea = true;
                        break;
                    case "--region-name":
                        regionName = args[++i];
                        break;
                    case "--combine":
                        combine = true;
                        break;
                    default:
                        questionParts.add(args[i]);
                }
            }
        } else {
            for (int i = 1; i < separatorIdx; i++) {
                switch (args[i]) {
                    case "--content-area":
                        contentArea = true;
                        break;
                    case "--region-name":
                        regionName = args[++i];
                        break;
                    case "--combine":
                        combine = true;
                        break;
                    default:
                        filenames.add(args[i]);
                }
            }
            for (int i = separatorIdx + 1; i < args.length; i++) {
                questionParts.add(args[i]);
            }
        }
        if (filenames.isEmpty()) {
            System.err.println("Need at least one filename");
            System.exit(1);
            return;
        }
        if (contentArea && null != regionName) {
            System.err.println("--content-area and --region-name are mutually exclusive");
            System.exit(1);
            return;
        }
        if (combine && (contentArea || null != regionName)) {
            System.err.println("--combine is whole-page only, incompatible with --content-area/--region-name");
            System.exit(1);
            return;
        }
        if (combine && 2 != filenames.size()) {
            System.err.println("--combine requires exactly 2 filenames");
            System.exit(1);
            return;
        }
        if (questionParts.isEmpty()) {
            System.err.println("Need a question to ask");
            System.exit(1);
            return;
        }
        String question = String.join(" ", questionParts);

        // Each filename is either a real catalog entry (the common case) or, if that
        // lookup fails, a literal path to an already-on-disk PNG — e.g. a composite
        // two-page/matrix just wrote, which is never itself a cataloged entry. A
        // null placeholder in entries marks the latter; region flags (which only make
        // sense against a real CatalogEntry's traced regions) are rejected below for
        // any batch containing one.
        List<CatalogEntry> entries = new ArrayList<>();
        List<File> files = new ArrayList<>();
        boolean anyRawPath = false;
        for (String filename : filenames) {
            CatalogEntry entry = catalog.loadEntryByFilename(filename);
            File imgFile;
            if (null != entry) {
                imgFile = resolveExistingLocation(entry);
                if (null == imgFile) {
                    System.err.println("No on-disk location found for " + filename);
                    System.exit(1);
                    return;
                }
            } else {
                imgFile = new File(filename);
                if (!imgFile.exists()) {
                    System.err.println("No entry for " + filename);
                    System.exit(1);
                    return;
                }
                anyRawPath = true;
            }
            entries.add(entry);
            files.add(imgFile);
        }
        if (anyRawPath && (contentArea || null != regionName)) {
            System.err.println("--content-area/--region-name require a cataloged filename, not a raw file path");
            System.exit(1);
            return;
        }

        if (combine) {
            visionCombined(cfg, filenames.get(0), files.get(0), filenames.get(1), files.get(1), question);
            return;
        }
        for (int n = 0; n < entries.size(); n++) {
            String answer = askVisionOnFile(cfg, entries.get(n), files.get(n), contentArea, regionName, question);
            System.out.println(filenames.size() > 1 ? filenames.get(n) + ": " + answer : answer);
        }
    }

    private static String askVisionOnFile(Config cfg, CatalogEntry entry, File imgFile, boolean contentArea,
            String regionName, String question) throws IOException {
        File toUpload = imgFile;
        File tempCrop = null;
        if (contentArea || null != regionName) {
            CatalogEntry.Region region = contentArea ? entry.mainRegion() : firstRegionNamed(entry, regionName);
            if (null == region) {
                System.err.println("No matching region for " + entry.filename);
                System.exit(1);
                return null;
            }
            tempCrop = File.createTempFile(entry.filename + ".", ".png");
            extractContentArea(entry, region, imgFile, tempCrop.getAbsolutePath(), false);
            toUpload = tempCrop;
        }
        try {
            VisionClient vision = new VisionClient(cfg);
            byte[] bytes = Files.readAllBytes(toUpload.toPath());
            String fileId = vision.uploadImageDownscaled(bytes);
            return vision.askAboutImage(fileId, "png", question);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted");
            System.exit(1);
            return null;
        } finally {
            if (null != tempCrop) {
                tempCrop.delete();
            }
        }
    }

    private static void visionCombined(Config cfg, String labelA, File fileA, String labelB, File fileB,
            String question) throws IOException {
        int cellCap = VisionClient.MAX_DIMENSION / 2;
        BufferedImage imgA = ImageDisplay.scaleToFit(ImageIO.read(fileA), cellCap, cellCap);
        BufferedImage imgB = ImageDisplay.scaleToFit(ImageIO.read(fileB), cellCap, cellCap);
        int cellW = Math.max(imgA.getWidth(), imgB.getWidth());
        int cellH = Math.max(imgA.getHeight(), imgB.getHeight());
        BufferedImage composite = ImageGrid.paint(List.of(imgA, imgB), 2, new Dimension(cellW, cellH));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(composite, "png", buf);
        try {
            VisionClient vision = new VisionClient(cfg);
            String fileId = vision.uploadImageDownscaled(buf.toByteArray());
            String answer = vision.askAboutImage(fileId, "png", question);
            System.out.println(labelA + "+" + labelB + " combined: " + answer);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted");
            System.exit(1);
        }
    }

    /**
     * {@code two-page <filename> [<other-filename>] [--out <path>]}: composes
     * a folio's recto+verso pair side by side, full resolution — verso left,
     * recto right, the order an open book spread actually reads — and either
     * writes the composite to {@code --out} (caller wants the file, same as
     * {@code extract}'s existing {@code --out} meaning) or opens it in infimg
     * via {@link Voynich#launchImageView(File)} (no {@code --out}, same
     * "open it for me" convenience {@code extract --view} already offers).
     * Mirrors {@code Voynich}'s "Selected → Two-Page View" menu action. One
     * filename infers its r/v counterpart via {@link OverviewPanel#parseFolio}
     * plus a direct {@link Catalog#loadEntry} lookup (no in-memory
     * {@code OverviewPanel} to reuse here); two filenames must both parse and
     * are reordered verso-first regardless of the order given. Either shape
     * fails clearly for a non-foliated or irregular filename (a cover, a
     * multi-folio composite scan) — same strict shape-matching the GUI action
     * uses, not a nag, since there's no valid composite to build.
     */
    private static void twoPage(Catalog catalog, String[] args) throws IOException {
        String out = null;
        List<String> filenames = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if ("--out".equals(args[i])) {
                out = args[++i];
            } else {
                filenames.add(args[i]);
            }
        }
        if (filenames.isEmpty() || filenames.size() > 2) {
            System.err.println("Usage: two-page <filename> [<other-filename>] [--out <path>]");
            System.exit(1);
            return;
        }

        CatalogEntry versoEntry;
        CatalogEntry rectoEntry;
        if (2 == filenames.size()) {
            CatalogEntry entryA = catalog.loadEntryByFilename(filenames.get(0));
            CatalogEntry entryB = catalog.loadEntryByFilename(filenames.get(1));
            if (null == entryA || null == entryB) {
                System.err.println("No entry for " + (null == entryA ? filenames.get(0) : filenames.get(1)));
                System.exit(1);
                return;
            }
            OverviewPanel.Folio a = OverviewPanel.parseFolio(entryA);
            OverviewPanel.Folio b = OverviewPanel.parseFolio(entryB);
            if (null == a || null == b) {
                System.err.println("Both filenames must be plain <number><r|v> folio pages");
                System.exit(1);
                return;
            }
            boolean firstIsVerso = 'v' == a.side;
            versoEntry = firstIsVerso ? entryA : entryB;
            rectoEntry = firstIsVerso ? entryB : entryA;
        } else {
            String filename = filenames.get(0);
            CatalogEntry entry = catalog.loadEntryByFilename(filename);
            if (null == entry) {
                System.err.println("No entry for " + filename);
                System.exit(1);
                return;
            }
            OverviewPanel.Folio folio = OverviewPanel.parseFolio(entry);
            if (null == folio) {
                System.err.println(filename + " is not a plain <number><r|v> folio page");
                System.exit(1);
                return;
            }
            char otherSide = 'r' == folio.side ? 'v' : 'r';
            CatalogEntry other = findFolioCounterpart(catalog, folio.number, otherSide);
            if (null == other) {
                System.err.println("No counterpart " + folio.number + otherSide + " for " + filename + " in the catalog");
                System.exit(1);
                return;
            }
            versoEntry = 'v' == folio.side ? entry : other;
            rectoEntry = 'v' == folio.side ? other : entry;
        }

        File versoFile = resolveExistingLocation(versoEntry);
        File rectoFile = resolveExistingLocation(rectoEntry);
        if (null == versoFile || null == rectoFile) {
            System.err.println("No on-disk location found for " + versoEntry.filename + " or " + rectoEntry.filename);
            System.exit(1);
            return;
        }

        BufferedImage a = ImageIO.read(versoFile);
        BufferedImage b = ImageIO.read(rectoFile);
        int cellW = Math.max(a.getWidth(), b.getWidth());
        int cellH = Math.max(a.getHeight(), b.getHeight());
        BufferedImage composite = ImageGrid.paint(List.of(a, b), 2, new Dimension(cellW, cellH));

        if (null != out) {
            ImageIO.write(composite, "png", new File(out));
            System.out.println(out);
        } else {
            File target = File.createTempFile(versoEntry.filename + "+" + rectoEntry.filename + ".", ".png");
            ImageIO.write(composite, "png", target);
            Voynich.launchImageView(target);
        }
    }

    /**
     * {@code matrix <filename> [<filename>...] [--out <path>]}: composes
     * every given page's already-cataloged 256×256 thumbnail into one
     * square-ish grid image (via {@link ImageGrid#squareColumns}), and either
     * writes it to {@code --out} or opens it in infimg — same {@code --out}/
     * launch duality as {@link #twoPage}. Mirrors {@code Voynich}'s
     * "Selected → Thumbnail Matrix" menu action, minus its screen-fit nag — a
     * CLI invocation has no "current screen" to fit against, and per this
     * class's existing no-confirm-prompts convention, just builds what was
     * asked for.
     */
    private static void matrix(Catalog catalog, String[] args) throws IOException {
        String out = null;
        List<String> filenames = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if ("--out".equals(args[i])) {
                out = args[++i];
            } else {
                filenames.add(args[i]);
            }
        }
        if (filenames.isEmpty()) {
            System.err.println("Usage: matrix <filename> [<filename>...] [--out <path>]");
            System.exit(1);
            return;
        }

        List<BufferedImage> thumbnails = new ArrayList<>();
        for (String filename : filenames) {
            CatalogEntry entry = catalog.loadEntryByFilename(filename);
            if (null == entry) {
                System.err.println("No entry for " + filename);
                System.exit(1);
                return;
            }
            thumbnails.add(catalog.loadThumbnail(entry.id));
        }
        int columns = ImageGrid.squareColumns(filenames.size());
        BufferedImage composite = ImageGrid.paint(thumbnails, columns,
                new Dimension(ColorImage.THUMB_SIZE, ColorImage.THUMB_SIZE));

        if (null != out) {
            ImageIO.write(composite, "png", new File(out));
            System.out.println(out);
        } else {
            File target = File.createTempFile("matrix.", ".png");
            ImageIO.write(composite, "png", target);
            Voynich.launchImageView(target);
        }
    }

    private static CatalogEntry.Region firstRegionNamed(CatalogEntry entry, String kind) {
        for (int i = 1; i < entry.regions.size(); i++) {
            CatalogEntry.Region r = entry.regions.get(i);
            if (r.kind.equalsIgnoreCase(kind)) {
                return r;
            }
        }
        return null;
    }

    /**
     * @return the cataloged entry for folio {@code number}{@code side},
     * resolved via {@link ScanRenamer#idForFolio} then a plain catalog
     * scan for that id — same folio→id lookup {@code OverviewPanel}'s
     * counterpart uses, just resolved to a {@code CatalogEntry} differently
     * since this has no in-memory grid list to search, only
     * {@link Catalog#listAll}.
     */
    private static CatalogEntry findFolioCounterpart(Catalog catalog, int number, char side) throws IOException {
        Integer id = ScanRenamer.cached().idForFolio(number, side);
        if (null == id) {
            return null;
        }
        for (CatalogEntry entry : catalog.listAll()) {
            if (entry.id == id) {
                return entry;
            }
        }
        return null;
    }

    private static File resolveExistingLocation(CatalogEntry entry) {
        for (CatalogEntry.Location loc : entry.locations) {
            File f = new File(loc.path);
            if (f.exists()) {
                return f;
            }
        }
        return null;
    }

    private static int[] parseInts(String s, int count, String usageMsg) {
        String[] parts = s.split(",");
        if (parts.length != count) {
            System.err.println("Usage: " + usageMsg);
            System.exit(1);
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static void printPixel(int rgb, List<String> formats) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        for (String format : formats) {
            switch (format) {
                case "rgb":
                    System.out.println("rgb=" + r + "," + g + "," + b);
                    break;
                case "lab": {
                    ColorBase.TriLabColor lab = ColorBase.resolve(new Color(rgb));
                    System.out.printf("lab=%.2f,%.2f,%.2f%n", lab.l / 100.0, lab.a / 100.0, lab.b / 100.0);
                    break;
                }
                case "hex":
                    System.out.printf("hex=#%06x%n", rgb & 0xFFFFFF);
                    break;
                default:
                    System.err.println("Unknown format: " + format + " (expected rgb|lab|hex)");
                    System.exit(1);
            }
        }
    }

    /**
     * @param format {@code rgb} (3× uint8/pixel), {@code lab} (3× float32/pixel,
     * unscaled L*, a*, b*, little-endian), or {@code hex} ({@code #rrggbb} per
     * pixel, UTF-8 text — the one non-binary option, for spot-checking a small
     * region by eye rather than feeding a numpy array)
     */
    private static byte[] buildBlob(ColorImage img, int x0, int y0, int w, int h, String format) {
        switch (format) {
            case "rgb": {
                byte[] blob = new byte[w * h * 3];
                int i = 0;
                for (int y = y0; y < y0 + h; y++) {
                    for (int x = x0; x < x0 + w; x++) {
                        int rgb = img.pixels[y * img.w + x];
                        blob[i++] = (byte) ((rgb >> 16) & 0xFF);
                        blob[i++] = (byte) ((rgb >> 8) & 0xFF);
                        blob[i++] = (byte) (rgb & 0xFF);
                    }
                }
                return blob;
            }
            case "lab": {
                ByteBuffer buf = ByteBuffer.allocate(w * h * 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int y = y0; y < y0 + h; y++) {
                    for (int x = x0; x < x0 + w; x++) {
                        ColorBase.TriLabColor lab = ColorBase.resolve(new Color(img.pixels[y * img.w + x]));
                        buf.putFloat((float) (lab.l / 100.0));
                        buf.putFloat((float) (lab.a / 100.0));
                        buf.putFloat((float) (lab.b / 100.0));
                    }
                }
                return buf.array();
            }
            case "hex": {
                StringBuilder sb = new StringBuilder();
                for (int y = y0; y < y0 + h; y++) {
                    for (int x = x0; x < x0 + w; x++) {
                        sb.append(String.format("#%06x%n", img.pixels[y * img.w + x] & 0xFFFFFF));
                    }
                }
                return sb.toString().getBytes(StandardCharsets.UTF_8);
            }
            default:
                System.err.println("Unknown format: " + format + " (expected rgb|lab|hex)");
                System.exit(1);
                return new byte[0];
        }
    }

    private static void usage() {
        System.err.println("Usage: CatalogCli [--config|-c path] <command> [args]");
        System.err.println("  --config|-c path             use this config file instead of ~/.infVoy/config.json");
        System.err.println("  list [-v|--invert] [filter]  list filenames (optionally whose JSON contains/lacks 'filter', case-insensitive)");
        System.err.println("  get <filename>              print the entry's JSON");
        System.err.println("  tag <filename> <text...>    add a tag/note (no-op if already present)");
        System.err.println("  save <filename> [jsonFile]  replace the entry (reads stdin if jsonFile omitted)");
        System.err.println("  extract <filename> --pixel x,y | --region x,y,w,h [--region ...] [--format rgb|lab|hex]");
        System.err.println("                      | --content-area [--out path] [--view] | --region-name <kind> [--out path] [--view]");
        System.err.println("                              real decoded pixel colour via ColorImage/ColorBase;");
        System.err.println("                              --pixel prints to stdout, --region writes a binary blob");
        System.err.println("                              (stdout or --out) plus a JSON manifest on stderr; repeat");
        System.err.println("                              --region to pull several regions from one decode (needs --out,");
        System.err.println("                              used as a prefix: <out>.0, <out>.1, ...); --content-area writes");
        System.err.println("                              a PNG cropped to the traced main region's bounding");
        System.err.println("                              box, black outside the polygon (stdout or --out);");
        System.err.println("                              --region-name <kind> does the same for any traced region");
        System.err.println("                              matched by its kind label (case-insensitive), not just the main area;");
        System.err.println("                              --view (with --content-area or --region-name) skips the file entirely");
        System.err.println("                              and opens the PNG(s) straight in a detached infimg process —");
        System.err.println("                              writes to a /tmp file when --out isn't given");
        System.err.println("  vision <filename> [<filename>...] <question...> [--content-area | --region-name <kind>] [--combine]");
        System.err.println("                              ask the local vision model a free-text question about the");
        System.err.println("                              page (or a traced region), prints its answer to stdout;");
        System.err.println("                              more than one filename requires -- before the question;");
        System.err.println("                              without --combine, fires one sequential call per filename;");
        System.err.println("                              --combine (exactly 2 filenames, whole-page only) composes");
        System.err.println("                              them into one side-by-side image and asks once;");
        System.err.println("                              very large images (see CLAUDE.md) may fail without downscaling first");
        System.err.println("  two-page <filename> [<other-filename>] [--out path]");
        System.err.println("                              compose a folio's recto+verso pair side by side, full");
        System.err.println("                              resolution, verso left/recto right; one filename infers");
        System.err.println("                              the other side, if it's cataloged; opens in infimg, or");
        System.err.println("                              writes to --out instead of opening");
        System.err.println("  matrix <filename> [<filename>...] [--out path]");
        System.err.println("                              compose the given pages' cached thumbnails into one grid");
        System.err.println("                              image; opens in infimg, or writes to --out instead");
        System.err.println("  checkpoint                  clone the whole catalog's current state");
        System.err.println("  restore                     discard everything since the last checkpoint");
    }
}
