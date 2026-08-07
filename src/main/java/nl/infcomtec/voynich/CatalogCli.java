/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
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
        Config cfg = JSON.getMapper().readValue(Voynich.configFile, Config.class);
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
            case "extract":
                requireArgs(args, 3, "extract <filename> --pixel x,y | --region x,y,w,h [--format rgb|lab|hex] "
                        + "| --content-area [--out path]");
                extract(catalog, args);
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
        CatalogEntry entry = catalog.loadEntry(filename);
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
                case "--format":
                    format = args[++i];
                    break;
                case "--out":
                    out = args[++i];
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(1);
                    return;
            }
        }
        int modes = (null != pixel ? 1 : 0) + (!regions.isEmpty() ? 1 : 0) + (contentArea ? 1 : 0);
        if (0 == modes) {
            System.err.println("Need --pixel x,y, --region x,y,w,h, or --content-area");
            System.exit(1);
            return;
        }
        if (modes > 1) {
            System.err.println("--pixel, --region, and --content-area are mutually exclusive");
            System.exit(1);
            return;
        }
        if (regions.size() > 1 && null == out) {
            System.err.println("Multiple --region needs --out (used as a prefix: <out>.0, <out>.1, ...)");
            System.exit(1);
            return;
        }

        if (contentArea) {
            extractContentArea(entry, imgFile, out);
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
     * traced {@link CatalogEntry#mainRegion()} bounding box and writes it as
     * a PNG — either to {@code out} or, if {@code null}, straight to
     * stdout. Doesn't go through {@link ColorImage} (no CIELab decode
     * needed for a raw crop), so it's cheaper than the {@code --pixel}/
     * {@code --region} modes above.
     */
    private static void extractContentArea(CatalogEntry entry, File imgFile, String out) throws IOException {
        CatalogEntry.Region main = entry.mainRegion();
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
        System.err.println("Usage: CatalogCli <command> [args]");
        System.err.println("  list [-v|--invert] [filter]  list filenames (optionally whose JSON contains/lacks 'filter', case-insensitive)");
        System.err.println("  get <filename>              print the entry's JSON");
        System.err.println("  tag <filename> <text...>    add a tag/note (no-op if already present)");
        System.err.println("  save <filename> [jsonFile]  replace the entry (reads stdin if jsonFile omitted)");
        System.err.println("  extract <filename> --pixel x,y | --region x,y,w,h [--region ...] [--format rgb|lab|hex]");
        System.err.println("                      | --content-area [--out path]");
        System.err.println("                              real decoded pixel colour via ColorImage/ColorBase;");
        System.err.println("                              --pixel prints to stdout, --region writes a binary blob");
        System.err.println("                              (stdout or --out) plus a JSON manifest on stderr; repeat");
        System.err.println("                              --region to pull several regions from one decode (needs --out,");
        System.err.println("                              used as a prefix: <out>.0, <out>.1, ...); --content-area writes");
        System.err.println("                              a PNG cropped to the traced main region's bounding");
        System.err.println("                              box, black outside the polygon (stdout or --out)");
        System.err.println("  checkpoint                  clone the whole catalog's current state");
        System.err.println("  restore                     discard everything since the last checkpoint");
    }
}
