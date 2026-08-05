# CLAUDE.md
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Run
All commands run from the repo root (`/home/walter/github/Voynich/`).
```bash
# Build fat jar
mvn package
# Run (fat jar)
java -jar target/Voynich-1.0-jar-with-dependencies.jar [optional-config-file]
# Run via Maven
mvn exec:java
```
There are no tests yet.

## Shell Tooling
This machine always has an up-to-date `locate` database (`updatedb` runs
regularly). Default to `locate <pattern>` for filesystem search instead of
`find`. Only use `find` when `locate` genuinely can't do the job — filtering
by mtime/size/permissions, or a path created since the last `updatedb` run.

## Configuration
On first launch the app writes a template config to stderr and exits with code 2 if `~/.infVoy.json` is missing or `scanPath` is unset. Create the file manually:
```json
{
  "scanPath": "/path/to/directory/of/png/scans"
}
```
A custom config path can be passed as the first CLI argument.

## Architecture
Single Maven module, Java 17, Swing UI with **FlatDarculaLaf** dark theme.

| Class | Role |
|-------|------|
| `Voynich` | Entry point. Loads config, validates `scanPath`, builds the main `JFrame`. |
| `Config` | Plain POJO serialized to/from `~/.infVoy.json` via `JSON`. Add fields here for new persistent settings. `viewBounds` (a `Map<String, Config.Bounds>`) remembers named tool windows' last on-screen position/size — see `ViewFrame`. |
| `JSON` | Thin Jackson wrapper. Two `ObjectMapper` instances: `mapper` (pretty/indented) and `liner` (single-line). Always use these rather than creating a new `ObjectMapper`. |
| `EzAction` | `AbstractAction` subclass with optional fluent color/font hints. Call `applyTo(component)` after constructing the button to apply styling. |
| `OverviewPanel` | Main content view: a `JList` grid of catalog thumbnails, `HORIZONTAL_WRAP`. Clicking a thumbnail opens `CatalogEntryEditor.edit` for that entry — see "Catalog persistence" below. |
| `CatalogEntryEditor` | Non-modal editor over one or more `CatalogEntry` records: the entry's actual image (via `ImageDisplay`) alongside an editable raw-JSON view and a tags box. Two modes share the same window and save-time guards: `edit` (single entry, opened by `OverviewPanel`) and `review` (a shuffled whole-catalog pass driven by a `RapidReviewAction`, e.g. the toolbar's "MarkUp" — clicking the image stages a tag in the box; nothing persists until Done). Deliberately non-modal — this app doesn't decide how many entries or tool windows a user is allowed to have open at once; only `JOptionPane` confirm/error prompts stay modal, since those are synchronous answers a caller's control flow depends on, not parallel workspace windows. Three buttons ("Color Frequency", "ΔE Heatmap", "Content Area") open the entry's file (re-decoded off-EDT into a `ColorImage` for the first two) via `ViewFrame`. A fourth, "Show Mask", toggles the inline image itself (no separate window) between the plain scaled view and a `BitSet2D`-masked one (everything `CatalogEntry.contentArea` excludes, darkened) — built off-EDT and cached per entry, discarded whenever `ContentAreaEditor` commits a new trace out from under it. |
| `RapidReviewAction` | Pluggable judgment for `CatalogEntryEditor.review`: just a label plus a tag template built from the click point. The dialog itself has no notion of what a "wash" or any other judgment means — only an implementation of this interface does. |
| `ImageDisplay` | Loads and scale-to-fits a `CatalogEntry`'s actual image file (not its stored thumbnail) into a Swing component, plus the inverse: mapping a click back to the original image's pixel coordinates. Used by `CatalogEntryEditor`. |
| `ViewFrame` | Opens a named, independent tool window (a plain, ownerless `JFrame` — windows don't own windows here; see the class doc for why) around one visualization `JComponent`, remembering that name's on-screen bounds in `Config.viewBounds` across restarts. Named per view *type* (e.g. "Color Frequency"), not per image, so reopening the same visualization for a different entry lands wherever it was last left. An optional `maximizeInitially` flag opens at the current screen's full usable size (`JFrame.MAXIMIZED_BOTH`) the first time a view is opened, before any saved size exists. |
| `FrequencyBarChart` | Ranked colour-frequency swatch bars for one `ColorImage`. Colours are grouped into ~5-unit CIELab bins before ranking, not ranked by exact RGB value — these scans are photographed against a flat black backdrop, which repeats a handful of exact RGB values enormous numbers of times, while the paper (naturally grainy, despite covering far more of the page) splits its true colour across thousands of individually-small near-identical values; ranking by exact value buries the paper and ink entirely under backdrop noise. |
| `DeltaEHeatmap` | Per-cell CIE76 ΔE from a `ColorImage`'s pixel-count-weighted mean Lab colour, rendered over `ColorImage.labThumbnail`'s 256×256 grid as a blue (near the mean) to red (most different) heat map — spatially exposes ink, staining, or pigment anomalies that a frequency count alone can't show where. |
| `ContentAreaCanvas` | Interactive tracing surface for one entry's `CatalogEntry.contentArea` polygon over its full-resolution image: click to place vertices tightly around the actual content — text, illustration, wash, not the physical page — click near the start to close, drag any handle to adjust afterward. The live segment to the cursor while tracing is drawn via `Graphics2D.setXORMode`, not a repaint — the same line drawn twice cancels out, so a mouse-move just erases-and-redraws that one segment directly instead of re-rendering the whole (often very large) image on every pixel of cursor travel. A tracking loupe pair (plain 4x + contrast-boosted 4x, anchored to whichever screen corner is diagonally opposite the cursor) shows native pixels regardless of on-screen scale — catches both imprecise edge placement on a heavily downscaled page and content dim enough to nearly miss. |
| `ContentAreaEditor` | Wraps `ContentAreaCanvas` with Clear/Commit/Cancel controls and opens it via `ViewFrame` (`maximizeInitially=true` — every pixel of screen matters for precise tracing), spawned by `CatalogEntryEditor`'s "Content Area" button. See its class doc for why `contentArea` is human-traced rather than auto-detected: no fold — however severe — is ever a true boundary, since content routinely runs right through them, and judging how faint a mark can be before it still counts as content is a call a human makes better than a tuned threshold. Renamed from `WorkingAreaEditor`/`workingArea` 2026-08-05 — see "Catalog persistence" below for why. |
| `BitSet2D` | A bit-per-pixel 2D mask (`BitSet` under the hood — real word-level range operations, not one call per pixel) plus utilities: flood fill (`oilSpill`), grow/shrink, invert, image conversion. `createFromPolygon` rasterizes a `List<Point>` (e.g. a decoded `CatalogEntry.contentArea`) via a real scanline fill straight into the bits — deliberately not through `java.awt.Shape#contains`, which is the trap `createFromShape`/`copy`/`setOrClear` (kept, `@Deprecated`) fall into: recomputing the winding number from every edge on every pixel query is fine once, ruinous over millions. First consumer: `CatalogEntryEditor`'s "Show Mask" toggle. |
| `TaskWindow` | Abstract `JFrame` + `SwingWorker` wrapper for a background task: progress bar, log, Cancel button. One window per task-type, reused (not recreated) on repeat runs via a static registry. |
| `ScanTaskWindow` | `TaskWindow` that walks `config.scanPath`, decodes each image via `ColorImage`, and records it into the catalog with `Catalog.recordSighting`. |
| `Catalog` | Persistence contract for the image catalog: one `CatalogEntry` + one thumbnail per filename. `Catalog.open(Config)` picks the backend. |
| `CatalogEntry` | JSON-serializable catalog record, keyed by filename (not path) — see "Catalog persistence" below. |
| `MySqlCatalog` | `Catalog` backed by one MySQL table: `JSON` column for the entry, `MEDIUMBLOB` for the thumbnail. Plain JDBC, no ORM. |
| `FileCatalog` | `Catalog` backed by `<filename>.json` + `<filename>.png` sidecar files under a catalog directory. The fallback when no DB is configured. |
| `CatalogCli` | Command-line access to the catalog (`list`, with an optional case-insensitive/invertible text filter over an entry's whole JSON; `get`/`tag`/`save`; `checkpoint`/`restore`), through the same `Catalog.open(Config)` the GUI uses — works against either backend. Run via `java -cp target/Voynich-1.0-jar-with-dependencies.jar nl.infcomtec.voynich.CatalogCli <command>`, bypassing the fat jar's GUI `Main-Class`. `extract` pulls real decoded pixels: `--pixel x,y`/`--region x,y,w,h` (repeatable) go through `ColorImage`/`ColorBase` for rgb/lab/hex output, same colour math the GUI views use; `--content-area` skips that (no Lab decode needed for a raw crop) and instead writes a PNG cropped to `CatalogEntry.contentArea`'s bounding box, black outside the polygon (via `BitSet2D.createFromPolygon`). |

### Catalog persistence
`Catalog.open(config)` picks `MySqlCatalog` when `Config.db` (host/database/user)
is populated, else `FileCatalog` rooted at `~/.voynich-catalog`. Both store the
identical `CatalogEntry` shape — MySQL as a native `JSON` column, files as a
pretty-printed `.json` sidecar — so neither is a second-class citizen.

`CatalogEntry` is keyed by **filename, not path**: the same file often exists
at more than one path (e.g. a NAS copy plus a local NVMe copy kept for read
speed), and those must collapse into one entry with two
`CatalogEntry.Location` entries, not two competing catalog rows. Use
`Catalog.recordSighting` (a default method on `Catalog`, implemented once on
top of `loadEntry`/`save`) to record or update a sighting — don't build entries
by hand and call `save` directly unless you're deliberately overwriting.

`CatalogEntry` also carries a free-form per-file "notepad" — `torrentJpg`
(cross-reference to the original 2004/torrent JPG numbering) and `tags`
(short free-text notes; deliberately not a fixed set of categories, since
new kinds of note keep turning up). Both fields needed no schema/migration
work to add, since `CatalogEntry` is stored as a single JSON blob either
way — that's the whole point of it being a JSON column/file rather than
normalized columns. Add a tag via `Catalog.addTag` (preserves the stored
thumbnail; no-ops on a duplicate) rather than loading, mutating, and
`save`-ing by hand. Both `OverviewPanel` (via `CatalogEntryEditor`, click a thumbnail) and
`CatalogCli` (`tag`/`save`) edit entries through this same notepad —
neither is a special case of the other.

`CatalogEntry.contentArea` (a `List<CatalogEntry.Vertex>`, empty until traced)
is a tight polygon around the scan's actual content — text, illustration,
wash — not the physical page: it deliberately excludes blank vellum margins
as well as photography backdrop, frayed edges, and the other pages visible
in the stack beneath it. Set via `ContentAreaEditor`, never auto-detected
(see its class doc). Same "no migration needed" story as `torrentJpg`/`tags`
for adding the field — but note the field itself *was* renamed 2026-08-05
(from `workingArea`, which the class doc originally defined as the full
physical page). That rename needed real handling, unlike a fresh addition:
Jackson binds by JSON property name, so a bare Java field rename would have
silently orphaned every already-traced entry's polygon on next read. The 5
entries that existed at rename time were migrated in place (checkpoint
first, then each entry's stored JSON key rewritten from `workingArea` to
`contentArea` via `CatalogCli save`, vertex counts diffed against a backup
to confirm nothing was lost) rather than kept under a `@JsonAlias` shim —
the old contract was never actually followed to the letter in practice, so
there was no split "some entries mean the old thing" case to preserve, just
a label catching up to what the data already was.

`Catalog.checkpoint()`/`Catalog.restoreLatestCheckpoint()` give a manual,
whole-catalog undo: `checkpoint()` clones the entire current state under a
new timestamp (a sibling directory for `FileCatalog`, a
`CREATE TABLE ... AS SELECT` clone of `images` for `MySqlCatalog`);
`restoreLatestCheckpoint()` replaces the whole catalog with the newest such
clone, discarding anything written since — a full replace, not a merge, and
not a stack (always the single most recent checkpoint, never an older one).
Old checkpoints are never pruned automatically; that's deliberate, left for
hand cleanup rather than built speculatively. Wired to the toolbar's
Checkpoint/Undo buttons and `CatalogCli checkpoint`/`restore`.

MySQL runs via the repo's `docker-compose.yml`; copy `.env.example` to `.env`
(gitignored) and fill in real credentials before `docker compose up -d`. The
same credentials then go in `~/.infVoy.json`'s `db` object — nothing reads
`.env` or the compose file at runtime, the two are just kept in sync by hand.
Leaving `db` unset (or any of `host`/`database`/`user` blank) uses
`FileCatalog` instead; a populated `db` that fails to connect throws rather
than silently falling back, since that means something is actually
misconfigured.

`MYSQL_USER` is deliberately granted `ALL PRIVILEGES ON *.*`, not scoped to
just `MYSQL_DATABASE`. This instance exists solely to serve this one app —
there is no other tenant on it to protect from this user, so a scoped grant
buys no real isolation, only friction (admin/test work constantly needing a
root detour). Don't "fix" this back to a scoped grant out of habit; it would
be reintroducing theater, not closing a hole. Credentials for `docker exec
... mysql`/`mysqldump` on the host running the container come from a mounted
MySQL option file (`MYSQL_CNF_HOST_PATH` in `.env`, chmod 600), not `-p` on
the command line — that one's about keeping the value out of shell
history/session logs, not access control between local processes, which
don't have a boundary here either. See `scripts/mysql-backup.sh`.

### Colour analysis pipeline
Understanding this requires reading `EnhancedColor`, `FloatColor`, `YUV`, and `ColorBase` together — no single file tells the whole story.

- `EnhancedColor` (extends `java.awt.Color`) is the central colour-math class: RGB↔CIELAB↔XYZ↔YUV↔HSB conversions, ΔE distance, blending, gamut checks. Most colour operations ultimately call into its static `getCIELAB`/`fromCIELAB`/`getXYZ` methods, which are pure math (no caching) and relatively expensive (several `Math.pow` calls per pixel).
- `FloatColor` is a separate, lighter float[]-based RGBA representation used for spectrum generation (`spectrum`, `binSpectrum`) and premultiplied-alpha blending math. Converts to `EnhancedColor` via `getColor()`.
- `YUV` is a simple Y/U/V value type with its own distance/compare, independent of the CIELAB path.
- `ColorBase` exists purely to make `EnhancedColor`'s CIELAB math affordable at per-pixel/per-image volume. It keeps a two-level cache (per-instance + static cross-instance) of RGB↔CIELAB conversions keyed by `TriElm`, a top-level `short[3]` triple type reusable outside `ColorBase`. `ColorBase.TriLabColor` (nested — its constructor is intrinsically tied to `ColorBase`'s cache internals) is the cache's value type.
- `ColorImage` (top-level, composes a `ColorBase`) is the actual entry point for image analysis: reads a file, runs every pixel through the cache, and builds a `TriLabColor`-indexed colour inventory (`labIndex`) for nearest-neighbour/merge work. `TriElm`/`TriLabColor` deliberately have no `equals`/`hashCode` — they're only ever used as `TreeMap` keys via `compareTo`; do not put them in a `HashMap`/`HashSet` without adding those first.
- `TriLabColor.l`/`a`/`b` store CIELab L\*/a\*/b\* scaled ×100 (documented on the field, and correctly applied by `resolveFromLab`) — `ColorBase.deltaE` divides by 100 assuming that scale. `resolve(Color)` and the `TriLabColor(ColorBase, Color)` constructor used to skip the ×100 multiply, silently making every `deltaE`/`ColorImage.distanceTo` result ~100× too small (fixed 2026-08-05, caught building `DeltaEHeatmap`). If a colour-distance number ever looks implausibly tiny again, check this first.

## Java Style — Non-Negotiable

### What Java Is
Java is a mature, complete, high-performance language on a JIT JVM at roughly 2x C performance. It is not a slow legacy system. Maturity is a feature. Stability is a feature. Write it with confidence in what it is.

### Language Idiom
Prefer explicit, named, Object-contract-respecting Java. Java's object model is built on explicit construction, named types, and the `Object` contract (`equals`, `hashCode`, `toString`). Write to that model.

Prefer explicit iteration and named classes over anonymous dispatch. When the reader sees `->` they must resolve a functional interface in their head — work the IDE was doing, now transferred to the human reader permanently. Same lines of code, less readable, degraded stack traces.

Streams beyond a trivial filter-and-collect chain carry the same cost: a pipeline that looked clever becomes an archaeology problem six months later.

Records automate the `Object` contract rather than fulfilling it — two ways to express a class with no principle distinguishing when to use which. Fulfill the contract explicitly.

None of this is a prohibition — the IDE saves the typing either way, so brevity is not the argument. Readability and debuggability are.

### Threading
Normal hardware has 2–16 cores. Single-threaded Java is a special case requiring justification. Design with `ExecutorService`, `SwingWorker`, or structured concurrency (JDK 21+) from the start.

### Multi-Monitor
Users have 0 to N monitors. Reason about `GraphicsEnvironment` and `GraphicsDevice`. Window placement and screen-awareness are first-class concerns, not afterthoughts.

### UI
Swing is the UI toolkit. Complete, stable, in the JDK, forty years of production evidence. Do not reach for JavaFX — it was never finished, the WebView is a frozen WebKit fossil, and its trajectory is driven by Oracle's attention span.

### Frameworks
Spring is not Java. Spring replaces explicit object construction with annotation magic requiring the full framework runtime. Java has constructors, factories, and composition — use them. A container is an explicit architectural decision, not a default.

### Dependencies
Reach for the jar ecosystem when the problem has genuine complexity that warrants it. Not to solve trivial problems the language handles natively. Every dependency is a transitive closure of decisions you didn't make, vulnerabilities you didn't audit, upgrade cycles you now own. That cost must justify itself.

### Javadoc
Readers (human or LLM) are expected to read and understand the code — Javadoc is not a substitute for that. Document what can't be recovered by reading: a class's role/lifecycle, a public static field's purpose and who owns mutating it, non-obvious persistence or contracts. Don't document getters/setters or anything whose purpose is already stated by its name plus its immediate surrounding context (fluent builder methods, a class doc that already covers a field's intent). No handholding, no guessing "what could this be for" — document the border, not both sides of it.
