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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import javax.imageio.ImageIO;

/**
 * Command-line access to the {@link FileCatalog} — the tool this project
 * kept reinventing as a throwaway one-shot {@code main} class every time an
 * entry needed reading or a tag needed adding. Not a Swing app; run via:
 * <pre>
 * java -cp target/Voynich-1.0-jar-with-dependencies.jar nl.infcomtec.voynich.CatalogCli [--identity name] [--config-file path] &lt;command&gt; [args]
 * </pre>
 * (the {@code -cp} plus explicit class name bypasses the fat jar's GUI
 * {@code Main-Class}, so no packaging changes were needed for this). An
 * optional {@code --identity name}, found anywhere in the argument list and
 * stripped before command dispatch, selects which MITSA appId (and
 * therefore {@link Voynich#baseDir} — separate catalog/checkpoints, not
 * just a different config file) this process runs against — see
 * {@link Voynich#DEFAULT_IDENTITY}'s doc for why identities never share a
 * catalog. An optional {@code --config-file}/{@code -c &lt;path&gt;}, also
 * stripped before dispatch, overrides {@link Voynich#configFile} directly
 * (within whichever identity's {@link Voynich#baseDir} was just resolved,
 * or the default identity if {@code --identity} wasn't given) — for a user
 * running more than one {@link Config#scanPath}/catalog pair side by side
 * without a full separate identity. Same flag names as {@code Voynich.main},
 * which uses the shared {@code nl.infcomtec.tools.GetOpt}; this hand-rolled
 * scan exists instead of GetOpt itself because GetOpt exits the process on
 * any unrecognized option, which would break passing the rest of the
 * argument list through to this class's own subcommand dispatch below.
 */
public class CatalogCli {

    public static void main(String[] args) throws IOException {
        List<String> argList = new ArrayList<>(List.of(args));
        int identityIdx = argList.indexOf("--identity");
        if (identityIdx >= 0) {
            if (identityIdx + 1 >= argList.size()) {
                System.err.println("--identity requires a name");
                System.exit(1);
                return;
            }
            Voynich.identity = argList.get(identityIdx + 1);
            Voynich.baseDir = nl.infcomtec.mitsa.MitsaPaths.appDataDir(Voynich.identity);
            Voynich.configFile = new File(Voynich.baseDir, "config.json");
            argList.remove(identityIdx + 1);
            argList.remove(identityIdx);
        }
        File configFile = Voynich.configFile;
        int configIdx = argList.indexOf("--config-file");
        if (configIdx < 0) {
            configIdx = argList.indexOf("-c");
        }
        if (configIdx >= 0) {
            if (configIdx + 1 >= argList.size()) {
                System.err.println("--config-file requires a path");
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
        // See Voynich.main's identical check: a malformed naming table makes
        // id resolution untrustworthy for every command here, not just the
        // ones that obviously touch it, so this is a hard stop up front.
        try {
            ScanRenamer.cached();
        } catch (IOException ex) {
            System.err.println("Cannot start: the bundled naming table (data/scan-naming.tsv) is malformed:");
            System.err.println(ex.getMessage());
            System.exit(2);
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
            case "alias":
                requireArgs(args, 2, "alias <name>");
                alias(catalog, args[1]);
                break;
            case "export":
                requireArgs(args, 3, "export <exporterName> --all | --marked | <filename> [<filename>...] -- <outFile>");
                export(catalog, args);
                break;
            case "denoise":
                requireArgs(args, 2, "denoise <outDir> [--tight N] [--merge N] [--threads N]");
                denoise(catalog, args);
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

    /**
     * {@code export <exporterName> --all | --marked | <filename>
     * [<filename>...] -- <outFile>} — CLI equivalent of the GUI's File →
     * Export… submenu (see {@code Voynich.exportEntries}), same metadata-
     * only contract as {@link CatalogExporter}. The trailing {@code --
     * <outFile>} mirrors {@code vision}'s own separator convention for a
     * variable-length filename list followed by one more positional
     * argument.
     */
    private static void export(Catalog catalog, String[] args) throws IOException {
        String exporterName = args[1];
        List<String> rest = List.of(args).subList(2, args.length);
        List<CatalogEntry> entries;
        List<String> remaining;
        if (rest.contains("--all")) {
            entries = catalog.listAll();
            remaining = new ArrayList<>(rest);
            remaining.remove("--all");
        } else if (rest.contains("--marked")) {
            entries = CatalogExporter.marked(catalog);
            remaining = new ArrayList<>(rest);
            remaining.remove("--marked");
        } else {
            int sep = rest.indexOf("--");
            if (sep < 0) {
                System.err.println("Usage: export <exporterName> --all | --marked | <filename> [<filename>...] -- <outFile>");
                System.exit(1);
                return;
            }
            entries = new ArrayList<>();
            for (String filename : rest.subList(0, sep)) {
                CatalogEntry entry = catalog.loadEntryByFilename(filename);
                if (null == entry) {
                    System.err.println("No entry for " + filename);
                    System.exit(1);
                    return;
                }
                entries.add(entry);
            }
            remaining = new ArrayList<>(rest.subList(sep, rest.size()));
        }
        remaining.remove("--");
        if (remaining.isEmpty()) {
            System.err.println("Missing output file");
            System.exit(1);
            return;
        }
        File out = new File(remaining.get(0));
        CatalogExporter.export(entries, exporterName, out);
        System.out.println("Exported " + entries.size() + " entries to " + out);
    }

    /**
     * {@code denoise <outDir> [--tight N] [--merge N] [--threads N]}: the
     * "clone-the-corpus" preprocessing tool from
     * {@code memory/project_quadtree_blob_denoise_prototype.md} — the FINAL
     * stage of the pipeline, downstream of content-area tracing, not a
     * substitute for it. For every {@code catalog} entry with a traced
     * {@link CatalogEntry#mainRegion()}, crops to that region's bounding box
     * (via {@link BitSet2D#cropToPolygon} — pixels outside the polygon but
     * inside the box are blacked out; everything not-content-area is real
     * scanning-process noise, not signal worth preserving, Walter's own
     * framing 2026-08-20) and only THEN runs {@link QuadBlobDenoiser} on
     * that crop — denoising the untraced backdrop/margin of a page would be
     * denoising noise, which makes no sense; masking first means the
     * algorithm never has to protect content it doesn't know exists.
     * Entries with no traced content area yet (see
     * {@code CatalogEntry#regions}'s "regions.size() &lt;= 1 means no
     * content area has been traced yet") are skipped, not denoised
     * whole-page — an untraced page hasn't finished the required human
     * judgment step yet, so there is no honest signal/noise boundary to
     * denoise against (skip, not guess, was Walter's explicit call).
     * Skipped/failed filenames are listed in the summary so gaps in the
     * clone are visible, not silent.
     *
     * <p>
     * Output PNGs land in {@code outDir} under the entry's current display
     * filename (same {@link OverviewPanel#displayNameOf} convention as
     * everything else in this CLI), sized to the content area — generally
     * much smaller than the original scan, by design — so {@code outDir}
     * can be pointed at directly as a second identity's {@code scanPath}
     * (see {@code Voynich}'s {@code --identity}/Switch Identity…): the
     * existing app becomes the original-vs-denoised comparison browser,
     * unmodified, no new {@code CatalogEntry} field or UI needed.
     * Deliberately writes files only, never touches any {@link Catalog} —
     * region/tag metadata for the denoised copies is a separate concern
     * (Import…, from the original identity's export) once a clean identity
     * has actually been scanned. Re-running against an {@code outDir} that
     * already has output is incremental by default: any entry whose display
     * filename already exists there is skipped (reported separately from
     * "no content area" skips), so marking more scans and re-running only
     * denoises what's new — {@code --force} reprocesses everything instead,
     * e.g. after a {@code --tight}/{@code --merge} change. {@code
     * denoise-run.json} is a JSON array, one provenance object appended per
     * run (not per image, since a whole run shares one recipe), so a
     * directory's full incremental history stays visible rather than being
     * overwritten by the latest run.
     *
     * <p>
     * Defaults (tight=2.0, merge=5.0) are the one validated parameter pair
     * so far (Voynich f17r, full page and crop) — exposed as flags rather
     * than hardcoded specifically so the open "does this generalize across
     * page types" question can be explored without a code change.
     * {@code --threads} defaults to every available core (Walter's explicit
     * call: this is a batch corpus job, not interactive UI work sharing the
     * machine with anything else) — deliberately local-only, not farmed out
     * to predator/victus, which would be over-the-top for this.
     *
     * <p>
     * Concurrency is a self-tuning ratchet, not a precomputed budget —
     * Walter's own call, 2026-08-20, after two prior designs (a
     * per-megapixel byte-cost estimate, both unsynchronized and then
     * synchronized) each still let a default-sized heap grind into
     * repeated Full GCs on the real corpus: "Literally run the FIRST image
     * (sort by size I'd suggest). If that's done and you have headroom,
     * start two. etc. it is an optimization, not a law." Entries are
     * processed smallest-content-area-first (so the ratchet earns evidence
     * fast, on cheap images, before ever risking a big one) via
     * {@link #runDenoiseQueue}: start at concurrency 1; after every
     * completion, if old-gen occupancy looks healthy (see
     * {@link #HEAP_HEALTHY_THRESHOLD}), allow one more concurrent slot,
     * up to {@code --threads}; if it looks unhealthy, stay at the current
     * level rather than grow. No per-image byte-cost math anywhere — the
     * actual observed GC behavior after each real completion is the only
     * signal, which is exactly why it doesn't need a tuned constant to get
     * right on a machine this dyad has never run it on (see this dyad's own
     * RAMpocalypse memory: RAM/GPU supply is genuinely squeezed, "buy more"
     * isn't a real fix to assume by default). A single image bigger than
     * the whole heap still runs (at concurrency 1, alone) rather than being
     * skipped outright.
     */

    /**
     * @return true if {@code outDir} already holds this entry's denoised
     * output under ANY naming scheme's basename for its id, not just the
     * currently active {@link Config#namingScheme} — files were previously
     * written under whatever scheme was active at the time, and Walter
     * renames scans between schemes on purpose ("human folly" is not a
     * reason to lose track of a file), so checking only the current
     * scheme's expected filename would miss existing output after a scheme
     * switch and silently re-denoise the whole corpus under the new names,
     * leaving stale duplicates from the old scheme behind. Compares by
     * basename (extension stripped), same convention as
     * {@link ScanRenamer#idForName}, since denoise always writes PNG
     * regardless of what extension a scheme's own naming table stores.
     */
    private static boolean alreadyDenoised(CatalogEntry entry, File outDir) throws IOException {
        ScanRenamer.Row row = ScanRenamer.cached().rowFor(entry.id);
        if (null == row) {
            return false;
        }
        File[] existing = outDir.listFiles();
        if (null == existing) {
            return false;
        }
        for (String name : row.names.values()) {
            if (name.isEmpty()) {
                continue;
            }
            String base = stripExtension(name);
            for (File f : existing) {
                if (stripExtension(f.getName()).equals(base)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static void denoise(Catalog catalog, String[] args) throws IOException {
        File outDir = new File(args[1]);
        double tight = 2.0;
        double merge = 5.0;
        int threads = Runtime.getRuntime().availableProcessors();
        boolean force = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--tight":
                    tight = Double.parseDouble(args[++i]);
                    break;
                case "--merge":
                    merge = Double.parseDouble(args[++i]);
                    break;
                case "--threads":
                    threads = Integer.parseInt(args[++i]);
                    break;
                case "--force":
                    force = true;
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(1);
                    return;
            }
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            System.err.println("Could not create " + outDir);
            System.exit(1);
            return;
        }

        List<CatalogEntry> all = catalog.listAll();
        List<CatalogEntry> withContentArea = new ArrayList<>();
        List<String> skippedNoContentArea = new ArrayList<>();
        List<String> skippedAlreadyPresent = new ArrayList<>();
        for (CatalogEntry entry : all) {
            if (null == entry.mainRegion()) {
                skippedNoContentArea.add(OverviewPanel.displayNameOf(entry));
                continue;
            }
            String displayName = OverviewPanel.displayNameOf(entry);
            if (!force && alreadyDenoised(entry, outDir)) {
                skippedAlreadyPresent.add(displayName);
                continue;
            }
            withContentArea.add(entry);
        }
        if (withContentArea.isEmpty()) {
            if (!skippedAlreadyPresent.isEmpty()) {
                System.out.println("Nothing to do: all " + skippedAlreadyPresent.size()
                        + " content-area entr" + (1 == skippedAlreadyPresent.size() ? "y" : "ies")
                        + " already have output in " + outDir + ". Use --force to redo them.");
                return;
            }
            System.err.println("No catalog entries have a traced content area yet — nothing to denoise.");
            System.exit(1);
            return;
        }
        // Smallest-content-area-first -- see denoise's own doc: the ratchet
        // needs cheap evidence fast, on small images, before it ever risks
        // growing concurrency into a huge one.
        Collections.sort(withContentArea, new Comparator<CatalogEntry>() {
            @Override
            public int compare(CatalogEntry a, CatalogEntry b) {
                return Double.compare(contentAreaMegapixels(a), contentAreaMegapixels(b));
            }
        });
        System.out.println("Denoising " + withContentArea.size() + " content-area crop(s) to " + outDir
                + " (tight=" + tight + " merge=" + merge + " maxThreads=" + threads
                + "); skipping " + skippedNoContentArea.size() + " entr"
                + (1 == skippedNoContentArea.size() ? "y" : "ies") + " with no traced content area"
                + (skippedAlreadyPresent.isEmpty() ? "." : ("; skipping " + skippedAlreadyPresent.size()
                        + " already present in " + outDir + " (--force to redo).")));

        List<String> failed = new ArrayList<>();
        int completedCount = runDenoiseQueue(withContentArea, outDir, tight, merge, threads, failed);

        LinkedHashMap<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("algorithm", "QuadBlobDenoiser (anchor-gated region growing), content-area cropped");
        provenance.put("tightDeltaE", tight);
        provenance.put("mergeDeltaE", merge);
        provenance.put("outDir", outDir.getAbsolutePath());
        provenance.put("imageCount", completedCount);
        provenance.put("failed", failed);
        provenance.put("skippedNoContentArea", skippedNoContentArea);
        provenance.put("skippedAlreadyPresent", skippedAlreadyPresent);
        provenance.put("force", force);
        provenance.put("runAtEpochMillis", System.currentTimeMillis());
        File provenanceFile = new File(outDir, "denoise-run.json");
        List<Object> runs = new ArrayList<>();
        if (provenanceFile.exists()) {
            try {
                Object existing = JSON.getMapper().readValue(provenanceFile, Object.class);
                if (existing instanceof List) {
                    runs.addAll((List<Object>) existing);
                } else {
                    runs.add(existing);
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read existing " + provenanceFile
                        + ", overwriting with this run's provenance only: " + e.getMessage());
            }
        }
        runs.add(provenance);
        JSON.getMapper().writerWithDefaultPrettyPrinter().writeValue(provenanceFile, runs);

        System.out.println("Done: " + completedCount + " written, " + failed.size() + " failed, "
                + skippedNoContentArea.size() + " skipped (no content area), "
                + skippedAlreadyPresent.size() + " skipped (already present). "
                + "Point a Voynich identity's scanPath at " + outDir + " to browse the result.");
    }

    /**
     * Heap occupancy (via {@link #heapOccupancyFraction}) below this
     * fraction of its max reads as "healthy" (ratchet grows by 1, up to
     * {@code --threads}); at or above reads as "unhealthy" (ratchet shrinks
     * by 1, floor 1) — symmetric in both directions, not grow-only. A
     * grow-only version (2026-08-20) held concurrency steady rather than
     * releasing it once several large images landed together near the end
     * of a real corpus run, so it stayed pinned at a too-high level through
     * sustained Full-GC thrash with no way to recover; shrinking gives the
     * next completion a real chance to bring the heap back to a level the
     * ratchet can safely re-grow from. No claim this exact number is
     * optimal — it only needs to be conservative enough to stop the ratchet
     * from growing into that same spiral.
     */
    private static final double HEAP_HEALTHY_THRESHOLD = 0.60;

    /**
     * The self-tuning concurrency ratchet — see {@link #denoise}'s own doc
     * for why this replaced two prior precomputed-budget designs, both of
     * which still let a default heap grind into repeated Full GCs on the
     * real corpus (2026-08-20). Runs {@code queue} (expected
     * smallest-content-area-first) through a single dispatcher loop on the
     * calling thread: keeps up to {@code allowedConcurrency} worker threads
     * (a plain {@link ExecutorService}, capped at {@code maxThreads}) busy
     * at once, starting at 1; after each completion, bumps
     * {@code allowedConcurrency} up by one — never above {@code maxThreads}
     * — only if old-gen occupancy currently looks healthy (see
     * {@link #HEAP_HEALTHY_THRESHOLD}); otherwise holds where it is.
     * "An optimization, not a law" (Walter, 2026-08-20) — concurrency is
     * never refused for an image being too large; the ratchet only controls
     * how many run at once, and an oversized image still runs alone at
     * concurrency 1 if that's as high as the ratchet ever grew.
     *
     * <p>
     * One thing IS refused, though — not a concurrency question at all:
     * {@link #tooLargeForHeap} is checked before dispatch, skipping (never
     * attempting) any single image whose estimated need alone exceeds what
     * {@link Runtime#maxMemory()} could provide even running completely
     * solo. This is the real final lesson of 2026-08-20's corpus run: after
     * fixing the concurrency bugs, 32 of 33 real content areas — including
     * two ~21-megapixel wide pages — succeeded cleanly on a default ~8GB
     * heap, but the corpus's one ~49-megapixel foldout still thrashed into
     * runaway Full GCs running entirely ALONE, with no other task competing
     * for memory. No amount of concurrency throttling helps a single task
     * that's bigger than the machine — "sometimes you just can't," Walter's
     * own words — and the only honest response to that is to recognize it
     * up front and skip with a clear, actionable message (see
     * {@link #tooLargeForHeap}'s own doc), not spend minutes discovering it
     * through thrash the way this exact image did before this check
     * existed.
     *
     * @return how many entries were denoised successfully; {@code failed}
     * (must be a plain, not-yet-populated list) is filled with the display
     * name of every entry that failed or was skipped as too large for this
     * heap — including any that threw an {@link OutOfMemoryError} despite
     * the pre-flight check (a conservative estimate can still be wrong; see
     * {@link #tooLargeForHeap}), caught via {@code catch (Throwable)}, not
     * {@code catch (Exception)}, since the first real corpus run
     * (2026-08-20) lost exactly one large entry silently to an
     * {@code OutOfMemoryError} an {@code Exception}-only catch let through
     * uncounted.
     */
    private static int runDenoiseQueue(List<CatalogEntry> queue, File outDir, double tight, double merge,
            int maxThreads, List<String> failed) {
        int total = queue.size();
        AtomicInteger nextIndex = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger running = new AtomicInteger(0);
        AtomicInteger allowedConcurrency = new AtomicInteger(1);
        List<String> threadSafeFailed = Collections.synchronizedList(failed);
        ExecutorService pool = Executors.newFixedThreadPool(maxThreads);

        // The dispatcher itself: single loop on the calling thread, deciding
        // when to hand the next queue entry to the pool. Deliberately not
        // "submit everything up front and let worker threads self-gate" (the
        // prior two designs) -- a single dispatcher is the only place that
        // needs to reason about allowedConcurrency at all, so there's no
        // check-then-act race to guard with a lock this time.
        while (true) {
            int idx = nextIndex.get();
            if (idx >= total) {
                break;
            }
            if (running.get() >= allowedConcurrency.get()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (!nextIndex.compareAndSet(idx, idx + 1)) {
                continue;
            }
            final CatalogEntry entry = queue.get(idx);
            final int myIndex = idx;
            double entryMp = contentAreaMegapixels(entry);
            if (tooLargeForHeap(entryMp)) {
                String displayName = OverviewPanel.displayNameOf(entry);
                System.err.println("[" + (myIndex + 1) + "/" + total + "] cannot handle " + displayName
                        + " (" + String.format("%.0f", entryMp) + " megapixel content area) — this heap's max is "
                        + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB; you might need to run with a"
                        + " larger -Xmx, or this task cannot be done with this hardware. Skipping.");
                threadSafeFailed.add(displayName);
                continue;
            }
            running.incrementAndGet();
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    String displayName = OverviewPanel.displayNameOf(entry);
                    long startMs = System.currentTimeMillis();
                    System.out.println("[" + (myIndex + 1) + "/" + total + "] starting " + displayName
                            + " (concurrency=" + allowedConcurrency.get() + ") ...");
                    System.out.flush();
                    boolean ok = false;
                    try {
                        File imgFile = resolveExistingLocation(entry);
                        if (null == imgFile) {
                            System.err.println("No on-disk location found for " + displayName);
                            return;
                        }
                        BufferedImage full = ImageIO.read(imgFile);
                        if (null == full) {
                            System.err.println("Could not decode " + displayName);
                            return;
                        }
                        CatalogEntry.Region main = entry.mainRegion();
                        List<Point> vertices = new ArrayList<>(main.polygon.size());
                        for (CatalogEntry.Vertex v : main.polygon) {
                            vertices.add(new Point(v.x, v.y));
                        }
                        BufferedImage cropped = BitSet2D.cropToPolygon(full, vertices);
                        BufferedImage denoised = QuadBlobDenoiser.denoise(cropped, tight, merge);
                        ImageIO.write(denoised, "png", new File(outDir, displayName));
                        ok = true;
                        int n = completed.incrementAndGet();
                        long elapsedMs = System.currentTimeMillis() - startMs;
                        System.out.println("[" + n + "/" + total + "] done " + displayName
                                + " (" + cropped.getWidth() + "x" + cropped.getHeight() + ", "
                                + (elapsedMs / 1000.0) + "s)");
                        System.out.flush();
                    } catch (Throwable ex) {
                        // Throwable, not Exception: an OutOfMemoryError on the largest
                        // content areas (e.g. a multi-page foldout, tens of megapixels)
                        // is an Error, not an Exception -- catching only Exception let
                        // exactly this happen silently once (2026-08-20): the task died
                        // with no stack trace, no "Failed on" line, and no entry in
                        // either completed or failed, so the run's own accounting didn't
                        // add up and the gap was invisible until counted by hand.
                        System.err.println("Failed on " + displayName + ": " + ex);
                    } finally {
                        if (!ok) {
                            threadSafeFailed.add(displayName);
                        }
                        running.decrementAndGet();
                        // Symmetric, not grow-only: an earlier version only ever held
                        // steady on an unhealthy reading, never gave concurrency back,
                        // so once several large images landed together (a real burst
                        // caught 2026-08-20, near the end of a corpus run) the ratchet
                        // stayed pinned at a too-high level through sustained Full-GC
                        // thrash with no way to recover. +-1 per completion (never
                        // more, even though several completions can race this update
                        // concurrently -- updateAndGet's CAS retry means each one still
                        // only ever applies a single +-1 step, they just don't compound
                        // within one instant) keeps this self-correcting in both
                        // directions instead of a one-way ratchet.
                        final boolean healthy = heapOccupancyFraction() < HEAP_HEALTHY_THRESHOLD;
                        allowedConcurrency.updateAndGet(new IntUnaryOperator() {
                            @Override
                            public int applyAsInt(int cur) {
                                return healthy ? Math.min(maxThreads, cur + 1) : Math.max(1, cur - 1);
                            }
                        });
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return completed.get();
    }

    /**
     * @return the JVM's current whole-heap occupancy as a fraction of its
     * configured max (0.0-1.0) — {@link Runtime#totalMemory()} (currently
     * committed heap) minus {@link Runtime#freeMemory()} (unused within
     * that committed portion), over {@link Runtime#maxMemory()} (the real
     * ceiling the heap can grow to). Deliberately just {@code Runtime} —
     * no {@code java.lang.management} pool-name matching for "old gen"
     * specifically; {@code Runtime} already reports everything this ratchet
     * needs, and pool names vary by collector/JDK vendor in a way plain
     * heap occupancy doesn't (Walter, 2026-08-20: "getRuntime() has all you
     * need, mind the cargo cult" — caught before the heavier API shipped).
     */
    private static double heapOccupancyFraction() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return (double) used / rt.maxMemory();
    }

    /**
     * Conservative per-megapixel byte cost for the pre-flight
     * {@link #tooLargeForHeap} check ONLY — deliberately not reused for
     * concurrency decisions (see {@link #runDenoiseQueue}'s doc for why a
     * live ratchet replaced per-megapixel budget math there). Calibrated
     * against the one real data point this dyad actually has, not a fresh
     * guess: on the real corpus (2026-08-20, default ~8GB max heap), two
     * ~21-megapixel content areas ({@code 68r.png}/{@code 68v.png})
     * succeeded cleanly running alone, while the corpus's one
     * ~49-megapixel foldout thrashed into runaway Full GCs running
     * completely alone too — no concurrency involved either time. That
     * puts the real per-image ceiling on this exact heap somewhere between
     * 21MP (fits) and 49MP (doesn't), i.e. this heap's ~8000MB max can hold
     * at most ~21-45 MP alone, or very roughly 180-380MB/MP; this constant
     * picks the low (more conservative) end of that observed range on
     * purpose — a false-positive skip costs nothing here (a human can still
     * force it with a bigger {@code -Xmx}), while a false-negative attempt
     * costs a repeat of the exact thrash this check exists to avoid.
     */
    private static final long HEAP_CEILING_BYTES_PER_MEGAPIXEL = 200L * 1024 * 1024;

    /**
     * @return {@code true} if a single image of {@code megapixels} content
     * area is estimated to need more memory than this JVM's
     * {@link Runtime#maxMemory()} could ever provide, even running
     * completely alone with no concurrent task competing for heap — the
     * "sometimes you just can't" case (Walter, 2026-08-20): no amount of
     * concurrency throttling helps a single task bigger than the machine,
     * so {@link #runDenoiseQueue} skips it outright with a clear message
     * (naming the current heap ceiling and suggesting a larger
     * {@code -Xmx}) rather than attempting it and discovering the same
     * thing through minutes of Full-GC thrash, the way the corpus's one
     * ~49-megapixel foldout entry did before this check existed.
     */
    private static boolean tooLargeForHeap(double megapixels) {
        long estimatedBytes = (long) (megapixels * HEAP_CEILING_BYTES_PER_MEGAPIXEL);
        return estimatedBytes > Runtime.getRuntime().maxMemory();
    }

    /**
     * @return {@code entry}'s traced {@link CatalogEntry#mainRegion()}
     * bounding-box area in megapixels, or {@code 0.0} if there's no traced
     * content area — the size {@link #denoise} actually processes (the
     * crop, not the full scan), still used to sort the queue
     * smallest-first (see {@link #runDenoiseQueue}'s doc for why).
     */
    private static double contentAreaMegapixels(CatalogEntry entry) {
        CatalogEntry.Region main = entry.mainRegion();
        if (null == main || main.polygon.isEmpty()) {
            return 0.0;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (CatalogEntry.Vertex v : main.polygon) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
        }
        return ((long) (maxX - minX) * (maxY - minY)) / 1_000_000.0;
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
                System.out.println(OverviewPanel.displayNameOf(entry) + "\t" + entry.width + "x" + entry.height
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
            System.err.println("No content area traced yet for " + OverviewPanel.displayNameOf(entry));
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
            out = File.createTempFile(OverviewPanel.displayNameOf(entry) + "." + main.kind + ".", ".png").getAbsolutePath();
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
                "{\"id\":%d,\"width\":%d,\"height\":%d,\"vertices\":%d%s}",
                entry.id, cropped.getWidth(), cropped.getHeight(), main.polygon.size(),
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
            System.err.println("No region with kind \"" + kind + "\" for " + OverviewPanel.displayNameOf(entry));
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
                System.err.println("No matching region for " + OverviewPanel.displayNameOf(entry));
                System.exit(1);
                return null;
            }
            tempCrop = File.createTempFile(OverviewPanel.displayNameOf(entry) + ".", ".png");
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
            System.err.println("No on-disk location found for " + OverviewPanel.displayNameOf(versoEntry)
                    + " or " + OverviewPanel.displayNameOf(rectoEntry));
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
            File target = File.createTempFile(
                    OverviewPanel.displayNameOf(versoEntry) + "+" + OverviewPanel.displayNameOf(rectoEntry) + ".", ".png");
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

    /**
     * {@code alias <name>}: resolves {@code name} — under any naming scheme,
     * or the current display filename, extension-agnostic — to its
     * permanent {@link CatalogEntry#id}, then prints every known scheme's
     * name for that id plus the live catalog filename and whether it's
     * actually cataloged. Exists purely to answer "which file is X in my
     * universe?" — the naming-scheme confusion this whole id migration
     * (see {@code CLAUDE.md}'s "Catalog persistence" section) was built to
     * fix in the first place, but the GUI's own answer to that question
     * ({@link CatalogEntryEditor}'s aliases label) needs the entry already
     * open; this is the same lookup from a bare name, no GUI needed.
     */
    private static void alias(Catalog catalog, String name) throws IOException {
        ScanRenamer renamer;
        try {
            renamer = ScanRenamer.cached();
        } catch (IOException ex) {
            System.err.println("Could not load scan-naming.tsv: " + ex.getMessage());
            System.exit(1);
            return;
        }
        Integer id = renamer.idForName(name);
        CatalogEntry entry = null;
        if (null == id) {
            // Not a known naming-column value — try it as a live catalog
            // filename directly, so "alias <current filename>" also works.
            entry = catalog.loadEntryByFilename(name);
            if (null != entry) {
                id = entry.id;
            }
        }
        if (null == id) {
            System.err.println("No known naming-scheme name or catalog filename matches \"" + name + "\"");
            System.exit(1);
            return;
        }
        if (null == entry) {
            for (CatalogEntry e : catalog.listAll()) {
                if (e.id == id) {
                    entry = e;
                    break;
                }
            }
        }
        System.out.println("id: " + id);
        ScanRenamer.Row row = renamer.rowFor(id);
        for (String column : renamer.columns) {
            String value = null == row ? null : row.names.get(column);
            System.out.println("  " + column + ": " + (null == value ? "(none)" : value));
        }
        System.out.println(null == entry ? "  catalog: not cataloged yet" : "  catalog: cataloged");
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
        System.err.println("Usage: CatalogCli [--config-file|-c path] <command> [args]");
        System.err.println("  --config-file|-c path         use this config file instead of the MITSA-managed default");
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
        System.err.println("  alias <name>                \"which file is this in my universe?\" — resolves name");
        System.err.println("                              (any naming scheme's value, or the current catalog");
        System.err.println("                              filename) to its permanent id and every other scheme's name");
        System.err.println("  export <exporterName> --all | --marked | <filename> [<filename>...] -- <outFile>");
        System.err.println("                              write metadata only (tags/regions, never image bytes) as one");
        System.err.println("                              JSON array; --marked = entries with a traced content area, other");
        System.err.println("                              regions, or tags/torrentJpg beyond the synthetic whole page;");
        System.err.println("                              exporterName fills any blank Region.author in the export only");
        System.err.println("  denoise <outDir> [--tight N] [--merge N] [--threads N] [--force]");
        System.err.println("                              FINAL-stage corpus-clone preprocessing: for every catalog entry");
        System.err.println("                              with a traced content area, crop to its bounding box (black");
        System.err.println("                              outside the polygon) then quadtree anchor-gated region-growing");
        System.err.println("                              denoise (see QuadBlobDenoiser) the crop; entries with no traced");
        System.err.println("                              content area yet are skipped, not denoised whole-page. Writes");
        System.err.println("                              results to outDir under each entry's display filename, appending");
        System.err.println("                              a run record to outDir's denoise-run.json provenance array;");
        System.err.println("                              point a second identity's scanPath at outDir to browse the result");
        System.err.println("                              (no catalog touched). Incremental by default: an entry whose");
        System.err.println("                              output already exists in outDir is skipped, so marking more scans");
        System.err.println("                              and re-running only denoises what's new; --force reprocesses");
        System.err.println("                              everything (e.g. after changing --tight/--merge). Each task waits");
        System.err.println("                              for live free-heap headroom (vs. its own crop's megapixels) before");
        System.err.println("                              starting, so large pages (e.g. foldouts) never run concurrently");
        System.err.println("                              enough to exhaust a modest heap");
        System.err.println("                              defaults: --tight 2.0 --merge 5.0 --threads <all cores>");
        System.err.println("  checkpoint                  clone the whole catalog's current state");
        System.err.println("  restore                     discard everything since the last checkpoint");
    }
}
