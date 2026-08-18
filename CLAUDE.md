# CLAUDE.md
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Memory
Project memory: `/home/walter/.claude/projects/-home-walter-github-Voynich/memory/`.
Check it before grepping the repo for "how do we already do X" questions
(mirror/sync commands, established scripts).

`~/bin/sync-legion.sh` (outside any repo — spans Voynich plus sibling
repos infimg and mcp-service-catalog) mirrors this memory directory to
predator alongside the repo, catalog, and checkpoints.

## Vision Pipeline (MCP)
A `predator-catalog` MCP server (user-scope, available in every Claude
Code session on this machine, any repo) exposes tools backed by a local
vision model (gemma-4-e4b, served via llama.cpp's `llama-server`) running
on predator. `look_at_image` answers a free-text question about an
uploaded image; `convert_image`/`identify_image` wrap ImageMagick. Upload
via `PUT http://predator:8765/files`, then call `look_at_image` with the
returned `file_id`. Known limits: vision-model output needs spot-checking
near a subjective category boundary (e.g. blue vs. green/teal pigment —
see `memory/project_vision_confabulation_finding.md`); resolution below
~1024px loses low-contrast/small-area detail (see
`memory/project_vision_resolution_floor_finding.md`); predator's real
upload limit is around 90+MB.

`VisionClient` gives the app itself the same access, not just Claude
Code's MCP tool: a plain `java.net.http.HttpClient` wrapper over the same
two wire calls — `PUT :8765/files` for upload, then `POST :8764/mcp`
(Streamable HTTP MCP transport, one JSON-RPC `tools/call` per request) to
invoke `look_at_image`. Image bytes go over the plain-HTTP upload, never
through MCP itself, which has no file-transfer semantics. Reachable from
`CatalogCli vision <filename> <question...> [--content-area |
--region-name <kind>]` and from the GUI's `CatalogEntryEditor` "View ▾" →
"Ask Vision…" — both print/show just the model's answer text, unwrapped
from the MCP response's double-JSON envelope (`result.content[0].text` is
itself a JSON string holding an OpenAI-style chat completion). See
`memory/project_vision_confabulation_finding.md` for known failure modes.

## Build and Run
All commands run from the repo root (`/home/walter/github/Voynich/`).
```bash
# Build fat jar
mvn package
# Run (fat jar)
java -jar target/Voynich-1.3.0-jar-with-dependencies.jar [optional-config-file]
# Run via Maven
mvn exec:java
# Smoke test: builds/shows the main JFrame, exits 0 once it's actually
# painted (not just constructed), instead of sitting open. Useful after a
# change to confirm the app still starts at all without a full manual run.
java -jar target/Voynich-1.3.0-jar-with-dependencies.jar --smokeTest
```
There are no tests yet, beyond the `--smokeTest` startup check above.

## Shell Tooling
This machine always has an up-to-date `locate` database (`updatedb` runs
regularly). Default to `locate <pattern>` for filesystem search instead of
`find`. Only use `find` when `locate` genuinely can't do the job — filtering
by mtime/size/permissions, or a path created since the last `updatedb` run.

## Configuration
On first launch the app creates `~/.infVoy/` if missing (fails fast if that path exists but isn't a directory), then writes a template config to stderr and exits with code 2 if `~/.infVoy/config.json` is missing or `scanPath` is unset. Create the file manually:
```json
{
  "scanPath": "/path/to/directory/of/png/scans"
}
```
A custom config path can be passed as the first CLI argument.

## Roadmap
In order — each step assumes the ones above it are done, not just that they
compile:
1. **Sort-by-similarity / duplicate report**, using `ColorImage.distanceTo`
   across the catalog — the original motivating feature, and the first item
   here nothing has actually been built against yet.
2. **Deferred, only if measured slow:** precomputed nearest-neighbour /
   duplicate-cluster caching, if an O(n²) `distanceTo` pass over a real
   collection turns out to actually be a bottleneck. Don't build this
   speculatively.
3. Editing operations (crop, exposure, white balance, etc.) — not scoped
   yet at all; this project has stayed cataloging/comparison so far, not
   editing.

## Architecture
Single Maven module, Java 17, Swing UI with **FlatDarculaLaf** dark theme.

| Class | Role |
|-------|------|
| `Voynich` | Entry point. Loads config, validates `scanPath`, builds the main `JFrame` with a `JMenuBar` in CUA order: **File** (Scan, Rename to…, Export… — a submenu, All/Selected/Marked, each prompting for an exporter name then a save dialog offering `.zip`/`.json` filters (zip default, one JSON entry deflated — this data compresses heavily), see `CatalogExporter` — Import… (`importEntries`: a file-open dialog accepting `.zip`/`.json`, `CatalogImporter.load`/`classify` against the local catalog, unresolvable records reported via a warning dialog rather than dropped silently, a `Catalog.checkpoint()` safety net taken before anything is written, then `ImportReviewDialog.open`) — Storage…, Exit). Before any of that, `main` loads `ScanRenamer.cached()` once and hard-stops (error dialog, exit 2) if `data/scan-naming.tsv` is malformed — the naming table is every entry's identity ground truth (see `CatalogEntry`'s row), so a bad table makes the whole app untrustworthy, not just the rename menu. **Edit** (Select All, Clear Selection — thin wrappers around `OverviewPanel.selectAllEntries()`/`clearEntrySelection()`), **View** (Sort, Filter, a `JCheckBoxMenuItem` for Content Area Only), **Review** (MarkUp…, an on-demand `JOptionPane` prompt for the tag template), **Selected** (Color Frequency/ΔE Heatmap/Ask Vision…/Open in infimg/Two-Page View/Thumbnail Matrix, all operating on `OverviewPanel.getSelectedEntries()` — see `OverviewPanel`'s row for the click/selection semantics that feed it). The Selected menu rebuilds each item's enabled state on `MenuListener.menuSelected`: Color Frequency/ΔE Heatmap for exactly 1 selected; Ask Vision/Open in infimg for 1+; Two-Page View only when `twoPagePair` resolves a valid pair; Thumbnail Matrix for 1+. A `BusyIndicator` docks at the menu bar's trailing end, sized from `fileMenu.getPreferredSize().height`, assigned to `RegionView.busy`. "Open in infimg" nags above 12 selected files. "Two-Page View" and "Thumbnail Matrix" compose a new image via `ImageGrid` and open it through infimg, not a new in-app `ViewFrame` viewer, so the result can be saved/discarded/clipboarded using infimg's own tools. Two-Page View composes verso left, recto right (the order an open book spread reads: verso is the back of the previous leaf; recto the front of the current one). "Ask Vision…" scales by selection count (see `RegionView`'s row): exactly 2 selected offers a Combined/Separate/Cancel choice (1 composite call, or 2 separate calls); the Combined path scales each source image to `VisionClient.MAX_DIMENSION / 2` per side before compositing. 3+ selected confirms once ("N images / N calls") then fires one call at a time via `RegionView.askVision`'s `onComplete` callback chain, never concurrently. `launchImageView` has two overloads, single-`File` and `launchImageView(List<File>)` — the latter appends every file's path to infimg's argv, since infimg treats all trailing positional arguments as a navigable file list for its own Prev/Next buttons. |
| `Config` | Plain POJO serialized to/from `~/.infVoy/config.json` via `JSON`. Add fields here for new persistent settings. `viewBounds` (a `Map<String, Config.Bounds>`) remembers named tool windows' last on-screen position/size — see `ViewFrame`. `infimgJar` is a launch *command*, not necessarily a jar path directly — point it at a wrapper script (e.g. `~/bin/infimg`) that pins its own jar version internally, so this config value survives infimg version bumps without an edit here each time; `Voynich.launchImageView` executes it directly (`ProcessBuilder`, no `java -jar` wrapping — the config value is a full command, ready to run as-is); a failed launch shows a `JOptionPane`, not just a log line. `visionHost`/`visionFilePort`/`visionMcpPort` configure `VisionClient`, defaulting to predator's real values. `namingScheme` (default `"Sequential"`) tracks which `scan-naming.tsv` column `scanPath`'s files currently match — see `ScanRenamer`. |
| `JSON` | Thin Jackson wrapper. Two `ObjectMapper` instances: `mapper` (pretty/indented) and `liner` (single-line). Always use these rather than creating a new `ObjectMapper`. |
| `EzAction` | `AbstractAction` subclass — the app-wide pattern for a clickable thing plus its full identity (label, behavior, tooltip, optional style hints) declared together at construction, rather than a bare `JButton` wired up separately via `addActionListener`/`setToolTipText`: one seam for any future app-wide change (e.g. a "pastel everything" pass) instead of N scattered call sites. `withTooltip(text)` sets the standard `Action` key `SHORT_DESCRIPTION`, which `JButton`/`JToggleButton`/`JMenuItem`'s `Action`-taking constructors already wire up to the component's hover tooltip automatically — no extra call needed. `withBackColor`/`withForeColor`/`withFont`, by contrast, are inert until the creator calls `applyTo(component)` explicitly (currently unused anywhere in the codebase). Used for every button/menu item across `Voynich`, `TaskWindow`, `CatalogEntryEditor`, `RegionManagerDialog`, `ContentAreaEditor`, and `StorageDialog` — a codebase-wide convention, not optional per-dialog styling. |
| `OverviewPanel` | Main content view: a `JList` grid of catalog thumbnails, `HORIZONTAL_WRAP`, `MULTIPLE_INTERVAL_SELECTION` selection mode. Click semantics follow file-manager convention: a single click selects/toggles/ranges via `JList`'s own built-in mouse handling — no custom selection code needed. A double click (`getClickCount() == 2`) opens `CatalogEntryEditor.edit` for the entry under the cursor, first collapsing the selection to just that entry if it wasn't already selected — see "Catalog persistence" below for what the editor does once open. `getSelectedEntries()`, `selectAllEntries()`/`clearEntrySelection()` (the menu bar's Edit menu), and `addSelectionListener` expose the grid's selection to `Voynich`'s "Selected" menu. Everything here is id-keyed, not filename-keyed — `thumbnails` is a `Map<Integer, BufferedImage>`, `findById(int)` and `thumbnailOf(CatalogEntry)` (exposing that same cache) back Two-Page View's r/v counterpart lookup and Thumbnail Matrix's composite respectively — both new selection-scoped features live in `Voynich`, not here. `displayNameOf(CatalogEntry)` (static) is the one canonical way anything in the app resolves a page's current display name — live via `ScanRenamer.displayName(entry.id, Config.namingScheme)`, never cached (see `CatalogEntry`'s row for why there's no stored filename to read instead). A nested `Folio` class (plain `number`/`side` fields) plus `static Folio parseFolio(CatalogEntry)` parses a strict recto/verso folio reference from the entry's bundled-TSV `Yale` column value (falling back to `displayNameOf` only if the id has no TSV row) — an irregular name like `100v_and_101r.png` (a multi-folio composite scan) or `Front_cover.png` (non-foliated) never has an inferable r/v counterpart; Two-Page View is unavailable for those pages by design. |
| `CatalogEntryEditor` | Non-modal editor over one or more `CatalogEntry` records: the entry's actual image (via `ImageDisplay`) alongside an editable raw-JSON view and a tags box. Two modes share the same window and save-time guards: `edit` (single entry, opened by `OverviewPanel`) and `review` (a shuffled whole-catalog pass driven by a `RapidReviewAction`, e.g. the "Review → MarkUp…" menu action — clicking the image stages a tag in the box; nothing persists until Done). Deliberately non-modal — this app doesn't decide how many entries or tool windows a user is allowed to have open at once; only `JOptionPane` confirm/error prompts stay modal, since those are synchronous answers a caller's control flow depends on, not parallel workspace windows. `advance()` (moves to the next queue entry, or the initial load) decodes the entry's full-resolution image off-EDT via a private `loadFullImage(CatalogEntry)`/`SwingWorker` — a blocked EDT can't repaint the menu bar's `BusyIndicator` either, so this must never run synchronously. The dialog shows immediately with a "Loading …" placeholder (`viewButton`/`areaButton` start disabled), reports through `RegionView.busy.enter()`/`exit()`, and guards a stale result — relevant for the MarkUp review queue, which can call `advance()` again quickly via Save before a slow load finishes — by discarding it if `entry` no longer matches the `target` captured when the load started. A "Region:" combo (rebuilt by `refreshRegionSelector` on every `advance()`/`onRegionsChanged`, resetting to "Whole page" on the former but preserving the current pick by label on the latter) picks "Whole page" or one of `entry.regions[1..]` and drives two things at once — deliberately one selector, not two independent controls that could disagree: `updateDisplayedRegion` swaps the inline image itself between the plain scaled view and a `BitSet2D.darkenOutside` overlay of whichever region is picked, built off-EDT; a "View ▾" button's popup menu ("Color Frequency"/"ΔE Heatmap"/"Open in infimg"/"Ask Vision…") acts on that same selection. All four items are thin wrappers delegating to the corresponding static `RegionView` method (`openColorVisualization`/`openInInfimg`/`askVision`) instead of each holding its own implementation — extracted so `OverviewPanel`'s "Selected" menu (see `Voynich`'s row) could reach the same region-scoped actions without needing an open editor; `showStandaloneAnswer(Window, String, String)` (package-visible static) is the shared answer-dialog display both this editor's own `askVision()` and `RegionView.askVisionOnImage` call, so there's one dialog implementation, not two. The View ▾ actions themselves still read `entry`/`regionSelector`/`fullImage` as before — only the shared crop/decode/upload logic moved out. "Regions…" stays its own button, deliberately not folded into the menu — it's region *management* (add/trace/rename/reorder/delete), not a view of the current selection, so mixing it in would blur that distinction rather than declutter it. |
| `RapidReviewAction` | Pluggable judgment for `CatalogEntryEditor.review`: just a label plus a tag template built from the click point. The dialog itself has no notion of what a "wash" or any other judgment means — only an implementation of this interface does. |
| `ImageDisplay` | Loads and scale-to-fits a `CatalogEntry`'s actual image file (not its stored thumbnail) into a Swing component, plus the inverse: mapping a click back to the original image's pixel coordinates. Used by `CatalogEntryEditor`. |
| `ViewFrame` | Opens a named, independent tool window (a plain, ownerless `JFrame` — windows don't own windows here; see the class doc for why) around one visualization `JComponent`, remembering that name's on-screen bounds in `Config.viewBounds` across restarts. Named per view *type* (e.g. "Color Frequency"), not per image, so reopening the same visualization for a different entry lands wherever it was last left. An optional `maximizeInitially` flag opens at the current screen's full usable size (`JFrame.MAXIMIZED_BOTH`) the first time a view is opened, before any saved size exists. `defaultDevice(Window)`/`usableBounds(GraphicsDevice)` (package-private, not `private`) resolve a `GraphicsDevice` and its screen bounds minus insets — reused as-is by `Voynich`'s Thumbnail Matrix fit-check rather than reimplemented, since both need exactly the same "what does maximize actually fill" math. |
| `RegionView` | Static, editor-free versions of the region-scoped view actions `CatalogEntryEditor`'s "View ▾" menu implements — `openColorVisualization`, `openInInfimg`, `askVision`/`askVisionOnImage` — lets `OverviewPanel`'s "Selected" menu (see `Voynich`'s row) reach the same actions directly from the thumbnail grid without an editor open. Each re-reads the target file off disk (via `ImageDisplay.pickExistingFile`) rather than depending on an already-decoded in-memory image, since none of these callers have one; `CatalogEntryEditor`'s own View ▾ items now delegate here too instead of duplicating the crop/decode/upload logic (see its row). A static `busy` field (a `BusyIndicator`, assigned once by `Voynich.main`) is `enter()`/`exit()`-wrapped around every `SwingWorker`'s background work here, so the menu bar's scanner-bar animates while any of these — or `CatalogEntryEditor.loadFullImage` — is running. `askVisionOnImage(Window, String, File, CatalogEntry.Region, String, Runnable)` takes a raw `File` and a `label` string instead of a `CatalogEntry`, so it also serves a composite image built from two selected entries that has no catalog entry of its own (`Voynich`'s Ask-Vision-on-a-pair "Combined" path); its `onComplete` callback (also on the `CatalogEntry`-taking `askVision` overload) lets a caller chain one vision call after another instead of firing several concurrently — `Voynich.askVisionSequentially` uses this to walk a selection set one call at a time, since predator's vision pipeline shouldn't see simultaneous requests and a single `busy` on/off state can't represent several overlapping calls anyway. |
| `ImageGrid` | Generic "paint N already-decoded images into one composite `BufferedImage`" compositor — the shared logic behind `Voynich`'s Two-Page View (forced 2-column layout, full-resolution sources) and Thumbnail Matrix (`squareColumns(count)`'s auto square-ish layout, cached 256×256 thumbnails as sources). `dimensions(count, columns, cellSize)` computes a composite's exact pixel size before anything is painted — lets Thumbnail Matrix's screen-fit nag (see `Voynich`'s row) check size first and skip building the image at all if the user cancels. `paint(...)` scales each cell down-only (never up) to fit, centers it, and leaves a `null` entry (e.g. a thumbnail that hasn't finished decoding) as a blank cell rather than failing the whole composite. Knows nothing about where a cell's image came from — that's entirely `RegionView`'s or `OverviewPanel`'s business. |
| `BusyIndicator` | A `JComponent` meant to dock at the trailing end of the app's `JMenuBar`: idle and blank whenever nothing is running, animating a 12-LED Cylon/KITT-style scanner bar — a bright lead LED with a 4-LED fading trail behind it, direction-aware (the trail always sits on the side the lead just came from, not a symmetric glow) — only while a `busyCount` kept by paired `enter()`/`exit()` calls is above zero. Deliberately not a busy mouse cursor or a modal progress dialog, matching this app's existing "no spinning wait cursors" convention. Takes its render height as a constructor parameter (a sibling top-level `JMenu`'s own `getPreferredSize().height`, from `Voynich.main`) rather than a hardcoded pixel constant, so every other dimension (width, LED size, trail spacing) scales off that one height and tracks the menu bar's actual font/DPI. Tick rate is 90ms, ≈1 second per full sweep. |
| `FrequencyBarChart` | Ranked colour-frequency swatch bars for one `ColorImage`. Colours are grouped into ~5-unit CIELab bins before ranking, not ranked by exact RGB value — these scans are photographed against a flat black backdrop, which repeats a handful of exact RGB values enormous numbers of times, while the paper (naturally grainy, despite covering far more of the page) splits its true colour across thousands of individually-small near-identical values; ranking by exact value buries the paper and ink entirely under backdrop noise. |
| `DeltaEHeatmap` | Per-cell CIE76 ΔE from a `ColorImage`'s pixel-count-weighted mean Lab colour, rendered over `ColorImage.labThumbnail`'s 256×256 grid as a blue (near the mean) to red (most different) heat map — spatially exposes ink, staining, or pigment anomalies that a frequency count alone can't show where. When `ColorImage.thumbnailMask` is non-null (a region-scoped crop), cells it has clear (the crop's masked-out corners/letterbox padding) render as plain black and are excluded from the max-ΔE scale entirely, not just from the reference mean — otherwise those cells' now-enormous distance from a mean correctly anchored to real content would dominate the scale and crush every real in-region variation down near "matches the mean". |
| `ContentAreaCanvas` | Interactive tracing surface for one `CatalogEntry.Region`'s polygon over its full-resolution image: click to place vertices tightly around the actual content — text, illustration, wash, not the physical page — click near the start to close, drag any handle to adjust afterward. Polygon-agnostic about *which* region it's tracing; that choice is made by its caller, `ContentAreaEditor`. The live segment to the cursor while tracing is drawn via `Graphics2D.setXORMode`, not a repaint — the same line drawn twice cancels out, so a mouse-move just erases-and-redraws that one segment directly instead of re-rendering the whole (often very large) image on every pixel of cursor travel. A tracking loupe pair (plain 4x + contrast-boosted 4x, anchored to whichever screen corner is diagonally opposite the cursor) shows native pixels regardless of on-screen scale — catches both imprecise edge placement on a heavily downscaled page and content dim enough to nearly miss. |
| `ContentAreaEditor` | Just the polygon editor now: wraps `ContentAreaCanvas` with Clear/Commit/Cancel controls and opens it via `ViewFrame` (`maximizeInitially=true` — every pixel of screen matters for precise tracing). Which `CatalogEntry.Region` it's tracing — a brand new one (`kind`/`author` already decided, only appended to `entry.regions` on Commit, never before, so a Cancel leaves nothing half-formed) or an existing one being re-traced — is entirely `RegionManagerDialog`'s call, made before this opens. See its class doc for why a region's polygon is human-traced rather than auto-detected: no fold — however severe — is ever a true boundary, since content routinely runs right through them, and judging how faint a mark can be before it still counts as content is a call a human makes better than a tuned threshold. Just the canvas wrapper — region management UI lives in `RegionManagerDialog`, see "Catalog persistence" below. |
| `RegionManagerDialog` | Lists `entry.regions` (index 0, the synthetic whole page, excluded — never user-editable) with View/Trace/Rename/Up/Down/Delete per row plus an Add button, opened by `CatalogEntryEditor`'s "Regions…" button. List layout mirrors `StorageDialog` (plain `GridBagLayout`, no `JTable`). "View" opens `RegionViewer` (see its own row) rather than a static crop — `CatalogEntryEditor`'s "Region:" selector overlays the whole page at page scale, which alone makes a small region (a faint imprint mark, say) easy to miss entirely. Every action saves to the catalog immediately — no batched "Done," so no unsaved-state to track on top of `CatalogEntryEditor`'s own JSON-blob staleness guard. Exists because `regions`' index convention (`regions.get(1)` is always the main content area, no boolean flag) turns fragile the moment a UI can delete or reorder rows, not just append: Add always appends at the end so it can never change what's main; Up/Down swap adjacent rows one step at a time (never drag-and-drop) so promoting a region to main is always the visible, direct result of a click, not a side effect of deleting something else; Delete on the main row gets its own warning naming the consequence (the next region, if any, becomes the new main) rather than the generic wording. |
| `RegionViewer` | Opens one already-traced `CatalogEntry.Region` as its own full-window "main view" — the drill-down step `RegionManagerDialog`'s "View" now leads to, replacing what used to be a small static, unrotated crop. The whole cropped-and-masked region (`BitSet2D.cropToPolygon`) fills the window; the mouse wheel rotates it live and saves `Region.angle` immediately, same every-action-saves-immediately convention as every other `RegionManagerDialog` action. Export…/Copy to Clipboard/Save to /tmp & View all act on the rotated raster as currently shown, not the unrotated crop. "Add Child" opens `ContentAreaEditor` zoomed to this region's own bounding box, with this region's index becoming the new region's `Region.parentIndex` — so a many-figure diagram can be drilled into (view region → add child → view that child → add its own child) without returning to `RegionManagerDialog`'s list for each step. Kind/author for a new child (or a Rename) is collected via `KindAuthorPrompt` (see its row), not a bare `JOptionPane`. |
| `KindAuthorPrompt` | Small modal `kind`/`author` prompt shared by `RegionManagerDialog`'s Add/Rename and `RegionViewer`'s Add Child. Exists because a `JOptionPane`-built version always handed initial keyboard focus to its default button rather than the message panel's first field, and — after extended live testing under this app's actual dialog stack (FlatDarculaLaf, launched from a nested action) — an editable `JComboBox` simply would not commit freshly typed text that wasn't already in its item list, no combination of `getSelectedItem()`/`getEditor().getItem()`/reading the editor's raw `JTextField` directly fixed it. The `kind` field is therefore a plain non-editable `JComboBox` of existing kinds (from `RegionManagerDialog.distinctKinds`) plus a separate "or new kind:" `JTextField` — non-blank always wins, since a bare `JTextField.getText()` has no commit-timing question to get wrong. |
| `ColorVisualizationFactory` | One-method interface (`createPanel(ColorImage)`) building the panel `CatalogEntryEditor`'s "Color Frequency"/"ΔE Heatmap" buttons open — one implementation per chart type (`FrequencyBarChart`, `DeltaEHeatmap`). Purpose-named replacement for a generic `Function<ColorImage, JComponent>` — see the Java Style section's stance on lambdas/method references for why a named functional interface, not a generic one, is the standing convention here. |
| `EntrySavedListener` | One-method interface (`onEntrySaved(CatalogEntry)`), notified after a `CatalogEntryEditor` Save/Done writes an entry to the catalog. Purpose-named replacement for a generic `Consumer<CatalogEntry>`, same rationale as `ColorVisualizationFactory`. |
| `BitSet2D` | A bit-per-pixel 2D mask (`BitSet` under the hood — real word-level range operations, not one call per pixel) plus utilities: flood fill (`oilSpill`), grow/shrink, invert, image conversion. `createFromPolygon` rasterizes a `List<Point>` (e.g. a decoded `CatalogEntry.mainRegion()` polygon) via a real scanline fill straight into the bits — deliberately not through `java.awt.Shape#contains`, which is the trap `createFromShape`/`copy`/`setOrClear` (kept, `@Deprecated`) fall into: recomputing the winding number from every edge on every pixel query is fine once, ruinous over millions. First consumer: `CatalogEntryEditor`'s region overlay (`updateDisplayedRegion`). `drawOutline(image, polygon, color, strokeWidth)` traces a polygon's boundary over a copy of `image` rather than dimming/masking anything (unlike `darkenOutside`, a different visual need) — for comparing a polygon against the real page without obscuring it; `ImportReviewDialog`'s only consumer so far. |
| `TaskWindow` | Abstract `JFrame` + `SwingWorker` wrapper for a background task: progress bar, log, Cancel button. One window per task-type, reused (not recreated) on repeat runs via a static registry. |
| `ScanTaskWindow` | `TaskWindow` that walks `config.scanPath`, decodes each image via `ColorImage`, and records it into the catalog with `Catalog.recordSighting`. |
| `ScanRenamer` | Loads the bundled `data/scan-naming.tsv` (tab-separated: `Id` (permanent `CatalogEntry.id`, excluded from `columns`) plus `Sequential`/`Yale`/`VoynichNu` columns so far, one row per manuscript page — see `SCANS.md`) as a plain 1:1 naming dictionary, no image knowledge involved. This table is the catalog's *only* source of identity (see `CatalogEntry`'s row): `load()` guarantees every column is complete for every row — a blank cell is filled with the zero-padded id itself (e.g. `"046"`) rather than left absent, so a scheme that never named covers/flyleaves (Rene's voynich.nu naming, say) doesn't force the TSV author to hand-fill every such row — and rejects the whole table (a hard `IOException`, naming the offending column/value) if any column has a duplicate non-blank value, since `idForName`/`idForFolio` resolve by first match and a silent duplicate would make that resolution wrong rather than failing loudly. Both `Voynich.main` and `CatalogCli.main` load this table eagerly at startup and hard-stop the whole app if it's malformed, not just disable the rename menu — id resolution is untrustworthy app-wide otherwise. `displayName(id, column)` is the one place any UI/CLI string gets a page's current name from (see `OverviewPanel.displayNameOf`), falling back to the bare id if `id`/`column` aren't found. `cached()` is the shared load-once/remember-failure static cache every caller uses rather than each hand-rolling its own. `idForFolio(number, side)` resolves a folio reference to its permanent id via the `Yale` column — the shared half of the recto/verso counterpart lookup `OverviewPanel`/`CatalogCli` each finish differently (in-memory grid vs. `Catalog.listAll()` scan). `idForName(filename)` resolves any known scheme's value back to an id — the only path `ScanTaskWindow.scanOne` uses to catalog a file at all; a file matching no row under any column is skipped and logged, never given an invented id (see "Catalog persistence" below). `plan(scanDir, fromColumn, toColumn)` matches files actually present in `scanDir` against `fromColumn` (by basename, extension-agnostic — a file's real extension can differ from whatever a naming column's own value carries) and works out each one's destination name under `toColumn`, always preserving the source file's real extension rather than adopting the target column's. Returns one `Plan` per matched file *before* touching the filesystem, so a caller can detect target-name collisions across the whole batch and refuse those individually (clear `skipReason`) without choking on the rest of a large batch. A file already correctly named under `toColumn` is a no-op skip, not a collision. `execute(plans, listener)` performs the non-skipped plans via plain `File.renameTo`, notifying a `PlanListener` per file. |
| `RenameTaskWindow` | `TaskWindow` behind File → "Rename to…" — runs one `ScanRenamer.execute` batch, logs each file's outcome, and on any successful rename calls `Catalog.renameEntry(id, oldFile, newFile)` to update the matching `Location.path` (a rename alone only touches the filesystem, not the catalog's own records of where the file lives), then updates `Config.namingScheme` to the new scheme. No re-Scan needed for the rename itself — the catalog's identity is `id`, not filename, so nothing about an entry's regions/tags/thumbnail needs reconciling just because its display name changed. |
| `Catalog` | Persistence contract for the image catalog: one `CatalogEntry` (thumbnail inlined as base64) per permanent id — the only identity this interface knows. `Catalog.open(Config)` opens the `FileCatalog` backend. `loadEntryByFilename(filename)` resolves a display name to an entry via `ScanRenamer.idForName` first, falling back to matching a `Location.path` basename (a catalogued file the TSV doesn't cover) — `null` if neither resolves, since this catalog doesn't hold opinions about files the naming table can't identify. `renameEntry(id, oldFile, newFile)` matches/updates the entry's `Location` by exact old path (there's no stored filename to match against instead). `addRegion(id, region)` — Import's only write path (see `ImportReviewDialog`) — always appends as a brand new region, never touching an existing one; if the incoming `region.kind` already matches one already on the entry, it's auto-suffixed (" (2)", " (3)", ...) until unique, since Walter ruled out any prompt/picker for this — Add should just always work. |
| `CatalogEntry` | JSON-serializable catalog record, keyed *only* by permanent `id` — no stored filename, no stored torrent-JPG cross-reference (both fields were removed; a page's name under any scheme is always a live `ScanRenamer.displayName(id, column)` lookup, never cached on the entry) — see "Catalog persistence" below. |
| `FileCatalog` | `Catalog` backed by a `<id>.json` sidecar file per entry under a catalog directory, thumbnail included inline as `CatalogEntry.thumbnailPng` (base64 via Jackson). The only backend. |
| `CatalogCli` | Command-line access to the catalog (`list`, with an optional case-insensitive/invertible text filter over an entry's whole JSON; `get`/`tag`/`save`; `alias <name>` resolves any known naming-scheme value or an on-disk filename to its permanent id and every other scheme's name for that page; `export <exporterName> --all \| --marked \| <filename> [<filename>...] -- <outFile>` writes a metadata-only JSON array — id, tags, regions, never image bytes or a display name — via `CatalogExporter`, filling any blank `Region.author` with `exporterName` for that export only; `--marked` means `regions.size() > 1` or non-empty `tags`; `checkpoint`/`restore`), through the same `Catalog.open(Config)` the GUI uses. Run via `java -cp target/Voynich-1.3.0-jar-with-dependencies.jar nl.infcomtec.voynich.CatalogCli <command>`, bypassing the fat jar's GUI `Main-Class`. `extract` pulls real decoded pixels: `--pixel x,y`/`--region x,y,w,h` (repeatable) go through `ColorImage`/`ColorBase` for rgb/lab/hex output, same colour math the GUI views use; `--content-area` skips that (no Lab decode needed for a raw crop) and instead writes a PNG cropped to `CatalogEntry.mainRegion()`'s bounding box, black outside the polygon (via `BitSet2D.cropToPolygon`, also used by `RegionManagerDialog`'s "View"; both just want the picture. `CatalogEntryEditor`'s region-scoped colour analysis instead uses `BitSet2D.cropAndMaskPolygon`, which returns the same cropped image plus a crop-local mask so the blacked-out corners can be excluded from analysis rather than just hidden). `vision <filename> [<filename>...] <question...> [--content-area \| --region-name <kind>] [--combine]` asks the local vision model a free-text question via `VisionClient` and prints the answer to stdout — whole page by default, or the same crop-to-polygon path `extract`'s region flags use (written to a temp PNG, uploaded, deleted after); no automatic downscaling, so a very large image can still hit the crash floor documented above. A single filename is unambiguous, no separator needed; two or more filenames require a literal `--` before the question, since both are otherwise-indistinguishable trailing positional args. Without `--combine`, multiple filenames fire one sequential `VisionClient` call per file, each answer printed prefixed with its filename; `--combine` (exactly 2 filenames, whole-page only, mirrors the GUI's Combined choice, incompatible with `--content-area`/`--region-name`) composes them into one side-by-side image via `ImageGrid` first — each source scaled to `VisionClient.MAX_DIMENSION / 2` per side before compositing, since relying on `VisionClient`'s own post-hoc downscale-after-decode to save an oversized upload only clamps after the full file has already been decoded — and asks once (`visionCombined` takes plain label strings rather than `CatalogEntry`, since a raw-path filename has no entry to read one from). Each filename is resolved as a catalog entry first; if that lookup fails, it falls back to a literal on-disk file path instead — the whole reason being that a `two-page`/`matrix` composite is never itself a cataloged entry, so without this there was no way to ask the vision model about one at all (`vision /tmp/spread.png "..."` works directly). A genuinely nonexistent path keeps the same "No entry for &lt;filename&gt;" error either way; `--content-area`/`--region-name` are rejected outright if any filename in the batch resolved as a raw path, since both only make sense against a real entry's traced regions. `two-page <filename> [<other-filename>] [--out path]` and `matrix <filename> [<filename>...] [--out path]` are the CLI equivalents of the GUI's Two-Page View/Thumbnail Matrix — full-resolution recto+verso composite (verso left, recto right; one filename infers its counterpart via `OverviewPanel.parseFolio` plus a direct `Catalog.loadEntry` lookup, erroring for a non-foliated or irregular filename) and cached-thumbnail grid composite (`ImageGrid.squareColumns`) respectively, via the same `ImageGrid` compositor the GUI uses. Neither has the GUI's interactive nags (infimg's >12-file confirm, the Thumbnail Matrix screen-fit warning) — a CLI invocation is already an explicit, scripted choice, consistent with every other `CatalogCli` command's no-confirm-prompt convention. `--out <path>` writes the composite there instead of opening it (same meaning as `extract`'s own `--out`); without `--out`, writes to a temp file and opens via `Voynich.launchImageView(File)`, same convenience `extract --view` offers. `CatalogCli.main()` assigns its loaded `Config` to the static `Voynich.config` field right after `Catalog.open`, since `launchImageView` reads that static directly. See `scripts/test-catalog-cli.sh` for the regression coverage of this command's argv contract. |
| `CatalogExporter` | Metadata-only export shared by File → Export… and `CatalogCli export` — never touches image bytes: each entry becomes an `Exported` record (`id`, `tags`, `regions`) with `thumbnailPng`/`locations`/`filename`/`torrentJpg` all dropped, since none of those mean anything outside this catalog's own storage or belong in a portable file at all (a display filename isn't even stable — see `CatalogEntry`'s row). `toExported(entry, exporterName)` fills any blank `Region.author` with `exporterName` for that export only, never written back to the source catalog. `marked(catalog)` (the "Marked" scope) returns every entry with `regions.size() > 1` or non-empty `tags` — real judgment data, not just an entry that happens to exist. `export(entries, exporterName, target)` writes plain pretty-printed JSON, or, if `target`'s name ends in `.zip` (case-insensitive), that same JSON deflated inside a one-entry zip (`java.util.zip`, same API `FileCatalog.checkpoint` already uses, no new dependency) — this data's repeated keys/whitespace/vertex coordinates compress heavily. An id-keyed export is the common ground `CatalogImporter` reads back in: two people's catalogs resolve the same id to the same physical page via the shared `data/scan-naming.tsv`, which a filename never guarantees across two different naming schemes. |
| `CatalogImporter` | Non-UI read/classify half of Import — `load(source)` parses a file `CatalogExporter.export` wrote, unzipping first if `source`'s name ends in `.zip` (same extension convention `export` itself uses to decide what to write, checked both directions so a file keeps meaning what its name says). `classify(catalog, records)` resolves each record's id against the local catalog into `Classified.resolvable`/`Classified.unresolvable` (the latter as human-readable reason strings, never a silent drop) — an id can be genuinely absent from the local `data/scan-naming.tsv` (the export came from a build with a newer/different table) or present in the table but never actually scanned locally yet; both are reported distinctly. Deliberately has no merge/write logic of its own — see `ImportReviewDialog` for where every write actually happens, and why. |
| `ImportReviewDialog` | The human review Import is built entirely around — "it involves a human," so this is GUI-only by design, no CLI equivalent. Non-modal (same "windows don't own windows" reasoning as `CatalogEntryEditor`), opened by `Voynich.importEntries` after a `Catalog.checkpoint()` safety net. Flattens every resolvable imported record's `regions[1..]` (index 0, the synthetic whole page, is never a review target — same convention `RegionManagerDialog` follows) into one review queue, one region at a time: the incoming region is drawn as a magenta outline over the real page image (`BitSet2D.drawOutline`, not a raw JSON/vertex diff — unusable at hand-traced-polygon granularity, per Walter's own explicit call) alongside stats (`Region.shoelaceArea()`, vertex count, author). Exactly two actions, no more: **Add** (`Catalog.addRegion` — always safe, appends as a brand new region, never touches anything existing) and **Ignore** (skip, no write) — a third "Replace" action was deliberately designed out: Walter ruled it out explicitly, since `RegionManagerDialog`'s existing Up/Down promote/demote already covers "make this the main content area" without Import needing its own overwrite semantics. Once the queue is exhausted, every imported record's `tags` (not just the ones that had regions) are offered once each as a plain checklist via `Catalog.addTag` — plain text has no vertex-noise problem, so no overlay treatment is needed there. Every successful write calls `OverviewPanel.addOrUpdate` with a freshly re-loaded entry (a real bug caught by Walter's own live testing: without this, a write lands correctly in storage but the already-open thumbnail grid/editor keeps showing stale pre-import state — same "who refreshes the grid after a write outside its own control" problem `RenameTaskWindow.overview.renameEntry` already solved for renames). |
| `VisionClient` | Plain `java.net.http.HttpClient` wrapper (no new dependency) around the `mcp-service-catalog` sibling project's vision pipeline on predator — see the "Vision Pipeline (MCP)" section above for the full wire-protocol rationale. `uploadImageDownscaled` (the normal entry point) decodes, downscales to `MAX_DIMENSION` (2048px on the longer axis, no-op if already smaller, never upscales) and re-encodes as PNG before calling `uploadFile` (`PUT :8765/files`) — "vision is not huge-capable" is a standing constraint of the underlying model/server (see the pixel-clamp/ubatch limits in the "Vision Pipeline (MCP)" section above), not a per-call decision, so every real caller downscales unconditionally rather than opting in; `uploadFile` itself stays available undownscaled for anything already known-small. Then `askAboutImage` (`POST :8764/mcp`, JSON-RPC `tools/call` naming `look_at_image`) unwraps the response's double-JSON envelope (an MCP `content[0].text` string that itself holds a stringified OpenAI-style chat completion) and strips a markdown code-fence wrapper (```` ```json ... ``` ````) the model applies inconsistently despite being asked for bare JSON, so callers just get clean answer text. Used by both `CatalogCli vision` and `CatalogEntryEditor`'s "Ask Vision…". |

### Catalog persistence
`Catalog.open(config)` opens `FileCatalog` rooted at `~/.infVoy/catalog`, one
pretty-printed `.json` sidecar per entry, thumbnail bytes inlined as base64
in that same file via `CatalogEntry.thumbnailPng` (a plain `byte[]`; Jackson
handles the base64 encoding). The MySQL backend (`MySqlCatalog`) that used to
be an alternative here was retired 2026-08-06 — its 213 entries were
exported into the file catalog and diffed for count/thumbnail/contentArea
parity before the MySQL code, `docker-compose.yml`/`docker-compose.nas.yml`/
`.env.example`, and `scripts/mysql-backup.sh` were deleted. `FileCatalog`
lazily migrates any leftover `<filename>.png` sidecar from before thumbnails
moved inline (see `CatalogEntry.thumbnailPng`) into the JSON on first read.

`replication/` is unrelated infrastructure exploration that predates the
MySQL retirement above and remains in the repo as generic,
`Catalog`-independent GTID MySQL replication work (master-slave and
master-master, live-tested between two real machines) — see
`replication/README.md` if that's ever useful for something else. Nothing
in `Catalog`/`Config` consumes it.

`CatalogEntry` is keyed **only by its permanent `id`** — no filename or
path is stored on the entry at all. The same file often exists at more
than one path too (e.g. a NAS copy plus a local NVMe copy kept for read
speed), and those collapse into one entry with two `CatalogEntry.Location`
entries, not two competing catalog rows. Use `Catalog.recordSighting` (a
default method on `Catalog`, implemented once on top of `loadEntry`/`save`)
to record or update a sighting — don't build entries by hand and call
`save` directly unless you're deliberately overwriting.

A cataloged file's *name* is never data this class stores — it's always a
live lookup, `ScanRenamer.displayName(entry.id, column)` (see
`OverviewPanel.displayNameOf`, the one canonical caller of it), resolved
fresh against `data/scan-naming.tsv` every time it's needed. This was a
deliberate design decision (2026-08-18, this same architectural pass): "the
catalog needs no names: the number (id) is canonical. We do not deal with
files that cannot be id-ed by the supplied CSV." A file `ScanTaskWindow`
can't resolve to an id via `ScanRenamer.idForName` is skipped and logged
during Scan, never given an invented id — the naming table is the single
place "how many pages exist, and what does each one's data mean" gets
decided, not something inferred from whatever happens to be sitting in
`scanPath`. This also makes an id the right anchor for exchanging data
between two people's catalogs (see `CatalogExporter`'s row): an id means
the same physical page in both, checked against the same shared table,
where a filename only means whatever naming scheme its owner's copy
happens to be using right now.

`CatalogEntry` also carries a free-form per-file "notepad" — `tags`
(short free-text notes; deliberately not a fixed set of categories, since
new kinds of note keep turning up). This needed no schema/migration work
to add, since `CatalogEntry` is stored as a single JSON blob either way —
that's the whole point of it being a JSON column/file rather than
normalized columns. Add a tag via `Catalog.addTag` (preserves the stored
thumbnail; no-ops on a duplicate) rather than loading, mutating, and
`save`-ing by hand. Both `OverviewPanel` (via `CatalogEntryEditor`, click a thumbnail) and
`CatalogCli` (`tag`/`save`) edit entries through this same notepad —
neither is a special case of the other.

`CatalogEntry.regions` (a `List<CatalogEntry.Region>`, empty until an entry's
`width`/`height` are known) holds every human-traced polygon on a scan,
addressed by list position rather than any flag: `regions.get(0)`, once
present, is always a synthesized rectangle spanning the whole image —
`CatalogEntry.ensureWholePageRegion()` inserts it the first time
`width`/`height` are set (called from `Catalog.recordSighting`), never
traced by a human. `regions.get(1)`, if present, is the traced content
area — a tight polygon around the scan's actual content (text,
illustration, wash), not the physical page: it deliberately excludes blank
vellum margins as well as photography backdrop, frayed edges, and the
other pages visible in the stack beneath it — and is what every mask/crop
consumer (`CatalogEntryEditor`'s "Region:" selector, `CatalogCli
--content-area`, `OverviewPanel`'s "Content Area Only" toggle) means by
`CatalogEntry.mainRegion()`. Anything from index 2 on is an "other area" — damage, a
second reviewer's opinion, whatever else turns up — distinguished only by
`Region.kind`/`Region.author`, both free text like `tags` (see above), not
an enum: `RegionManagerDialog`'s Add/Rename actions offer an editable combo
pre-filled with every distinct `kind` already used across the catalog, so a
label like "Arabic page number" gets typed once, ever. `Region.author`
blank means genuinely unattributed ("someone"), never implicitly the
primary user. `regions.size() <= 1` means no content area has been traced
yet. Region polygons are set via `ContentAreaEditor`, never auto-detected
(see its class doc); `RegionManagerDialog` is the list/add/rename/reorder/
delete management UI around it.

Two further per-region fields, both set from `RegionViewer` rather than
`ContentAreaEditor`: `Region.angle` (a live-rotatable view offset, mouse
wheel in `RegionViewer`, saved immediately on every wheel tick — the
traced polygon itself never changes, only how the cropped region is
displayed/exported/copied) and `Region.parentIndex` (set when a region is
traced via `RegionViewer`'s "Add Child" rather than `RegionManagerDialog`'s
top-level Add — the parent region's own index in `entry.regions`, letting
a large diagram be drilled into figure-by-figure: view region → Add Child →
view that child → Add its own child, and so on). A child region's own
`kind`/`author` is prompted for fresh each time via `KindAuthorPrompt` and
does **not** inherit the parent's `author`, even though the nesting implies
the same person likely traced both — see [Known limitations in
MANUAL.md](MANUAL.md#known-limitations) for the user-facing note on this.

`CatalogEntry` fields are Jackson-bound by JSON property name — a bare
Java field rename silently orphans every already-stored entry's data on
next read, since the JSON key just stops matching. Any future rename of
a persisted field needs a real data migration (checkpoint first, rewrite
every stored entry), not just a Java-side rename.

What counts as "content" within the main region is a signal-vs-noise call
the tracing user makes per page, not a fixed rule — e.g. Walter generally
excludes later-added modern arabic-numeral page numbers (added well after
the manuscript itself, not part of it), but that judgment is his, over his
copy of the scans, and isn't written down anywhere the software enforces.
When a call like that is worth remembering, it goes in `CatalogEntry.tags`
as free text (see above), not as a new structured field — same reasoning
as `tags` itself staying free-form rather than a fixed taxonomy. Because
this is a personal, per-collection judgment baked into `regions`/`tags`
rather than something re-derivable from the scans, sharing a catalog
between users needs an explicit import/export path rather than just
handing over the raw catalog directory — see `CatalogExporter`/
`CatalogImporter`/`ImportReviewDialog`'s rows above. Import is
deliberately conservative (Add/Ignore only, never a real diff/merge of
two `FileCatalog` directories) — see this same section's own discussion
of why `id` is canonical, above, for the reasoning that makes even this
conservative version possible at all.

`Catalog.checkpoint()`/`Catalog.restoreLatestCheckpoint()` give a manual,
whole-catalog undo: `checkpoint()` zips the entire current state into one
timestamped `<epoch-millis>.zip` (via `java.util.zip`, no extra dependency)
under `~/.infVoy/catalog-checkpoints`, cheaper on disk than a raw directory
copy now that each entry's thumbnail is inlined as base64;
`restoreLatestCheckpoint()` replaces the whole catalog with the newest such
zip, discarding anything written since — a full replace, not a merge, and
not a stack (always the single most recent checkpoint, never an older one).
Old checkpoints are never pruned automatically by default — `StorageDialog`
(opened via File → "Storage…", replacing the old opaque
Checkpoint/Undo pair 2026-08-07) makes them visible with take/restore/delete
actions per checkpoint, showing each one's ISO timestamp, age, and size.
`CatalogCli checkpoint`/`restore` remain the CLI equivalents.

Config, catalog, and checkpoints all moved under one `~/.infVoy/` base
directory 2026-08-07 (previously three separate home-dir dotfiles:
`~/.infVoy.json`, `~/.voynich-catalog`, `~/.voynich-catalog-checkpoints`).
`Voynich.baseDir` creates `~/.infVoy` on class load if missing and fails
fast if that path exists but isn't a directory; `Voynich.configFile` and
`Catalog.open` both derive from it, and `CatalogCli` reuses
`Voynich.configFile` rather than resolving its own copy. No migration code
was added — existing data was moved by hand.

### Colour analysis pipeline
Understanding this requires reading `EnhancedColor`, `FloatColor`, `YUV`, and `ColorBase` together — no single file tells the whole story.

- `EnhancedColor` (extends `java.awt.Color`) is the central colour-math class: RGB↔CIELAB↔XYZ↔YUV↔HSB conversions, ΔE distance, blending, gamut checks. Most colour operations ultimately call into its static `getCIELAB`/`fromCIELAB`/`getXYZ` methods, which are pure math (no caching) and relatively expensive (several `Math.pow` calls per pixel).
- `FloatColor` is a separate, lighter float[]-based RGBA representation used for spectrum generation (`spectrum`, `binSpectrum`) and premultiplied-alpha blending math. Converts to `EnhancedColor` via `getColor()`.
- `YUV` is a simple Y/U/V value type with its own distance/compare, independent of the CIELAB path.
- `ColorBase` exists purely to make `EnhancedColor`'s CIELAB math affordable at per-pixel/per-image volume. It keeps a two-level cache (per-instance + static cross-instance) of RGB↔CIELAB conversions keyed by `TriElm`, a top-level `short[3]` triple type reusable outside `ColorBase`. `ColorBase.TriLabColor` (nested — its constructor is intrinsically tied to `ColorBase`'s cache internals) is the cache's value type.
- `ColorImage` (top-level, composes a `ColorBase`) is the actual entry point for image analysis: reads a file, or (via `ColorImage(BufferedImage, String)`) analyses an already-decoded/cropped image directly, or (via `ColorImage(BufferedImage, String, BitSet2D)`) does the same but skips any pixel the mask has clear — the path `CatalogEntryEditor`'s region-scoped "Color Frequency"/"ΔE Heatmap" take, so a bounding-box crop's blacked-out corners (see `BitSet2D.cropAndMaskPolygon`) never pollute a frequency count or `DeltaEHeatmap`'s pixel-count-weighted reference mean the way they would if just painted black and counted like any other pixel; `thumbnail`/`labThumbnail` are unaffected either way, still showing the full crop (corners included) since that's the honest shape of the region, not a colour-statistics input. Every pixel scan runs each RGB value through the cache, building a `TriLabColor`-indexed colour inventory (`labIndex`) for nearest-neighbour/merge work. `TriElm`/`TriLabColor` deliberately have no `equals`/`hashCode` — they're only ever used as `TreeMap` keys via `compareTo`; do not put them in a `HashMap`/`HashSet` without adding those first.
- `TriLabColor.l`/`a`/`b` store CIELab L\*/a\*/b\* scaled ×100 (documented on the field, and correctly applied by `resolveFromLab`) — `ColorBase.deltaE` divides by 100 assuming that scale. `resolve(Color)` and the `TriLabColor(ColorBase, Color)` constructor used to skip the ×100 multiply, silently making every `deltaE`/`ColorImage.distanceTo` result ~100× too small (fixed 2026-08-05, caught building `DeltaEHeatmap`). If a colour-distance number ever looks implausibly tiny again, check this first.

## Java Style — Non-Negotiable

### What Java Is
Java is a mature, complete, high-performance language on a JIT JVM at roughly 2x C performance. It is not a slow legacy system. Maturity is a feature. Stability is a feature. Write it with confidence in what it is.

### Language Idiom
Prefer explicit, named, Object-contract-respecting Java. Java's object model is built on explicit construction, named types, and the `Object` contract (`equals`, `hashCode`, `toString`). Write to that model.

**No `->` and no `::`, anywhere, full stop — not even for a "trivial" one-liner.** Use an explicit anonymous (or named) class implementing the real functional interface instead: `new ActionListener() { public void actionPerformed(ActionEvent e) { ... } }`, not `e -> ...`. This was tightened from a softer "prefer explicit over anonymous dispatch, trivial cases OK" rule to a hard zero-exceptions one on 2026-08-07, after a drive-by pass found 8 lambdas and 7 method references that had survived under the "trivial" carve-out — the carve-out itself was the bug, since "is this one trivial enough" is exactly the judgment call that quietly erodes over time.

The concrete tell, found while fixing them: converting each one to its explicit form required *adding an import* — `Comparator`, `FilenameFilter`, `Consumer`, `ActionListener`, `ItemListener`. That import wasn't optional or newly-needed; it was always the real type being constructed. Lambda/method-reference syntax had just been hiding it — from the compiler's target-type inference, and therefore from the human (or AI) reader too, who has to already know the surrounding API by heart to know what `e -> ...` or `Class::method` even constructs. It's not a readability *style* preference at that point, it's information genuinely missing from the source.

The "saves typing" defense doesn't hold either: an IDE (or an AI assistant, now) writes the boilerplate either way, so the LOC saved were never a human's typing effort to begin with — only the reader's comprehension effort was traded away, permanently, for a few lines.

There's a second, LLM-specific version of the same cost, worth naming since an AI assistant is doing a lot of this codebase's editing: `Consumer.accept`, `Function.apply`, `Runnable.run`, `Predicate.test` are structurally near-identical one-method interfaces with *different* method names, and a lambda call site (`e -> ...`) gives no textual cue which one applies — the assistant has to already know, or go check, rather than read it off the line in front of it. `new ActionListener() { public void actionPerformed(ActionEvent e) { ... } }` puts the exact type and method to override in the tokens being generated from, not inferred from a signature possibly out of view. Fewer wrong-token guesses, same reason a human reader benefits.

Streams beyond a trivial filter-and-collect chain carry the same cost, for the same reason: a pipeline that looked clever becomes an archaeology problem six months later. Even a "trivial" filter should generally just be a plain loop — see the same 2026-08-07 pass, which replaced a one-line `.stream().filter(...).findFirst()` with a three-line loop for exactly this reason.

Records automate the `Object` contract rather than fulfilling it — two ways to express a class with no principle distinguishing when to use which. Fulfill the contract explicitly.

### Threading
Normal hardware has 2–16 cores. Single-threaded Java is a special case requiring justification. Design with `ExecutorService`, `SwingWorker`, or structured concurrency (JDK 21+) from the start.

### Multi-Monitor
Users have 0 to N monitors. Reason about `GraphicsEnvironment` and `GraphicsDevice`. Window placement and screen-awareness are first-class concerns, not afterthoughts.

### Time
Represent instants as a `long` epoch-millisecond (`System.currentTimeMillis()`), not `java.time.Instant`/`LocalDateTime`/`Duration`. The epoch-millis `long` has been stable since Java 1.3, sorts and diffs with plain arithmetic, and is what timestamped filenames already use throughout this codebase (e.g. checkpoint zips, see "Catalog persistence"). `java.time` is a large object graph — instants, zones, chronologies, formatter builders — for a problem a `long` and `String.format`'s `%t`/`%T` conversions (which accept a `long` millis argument directly, no wrapper object needed) already solve. Reach for `java.time` only for genuine calendar arithmetic (month/day-of-week boundaries, DST-aware scheduling) — a plain age/duration or a display timestamp doesn't need it.

### UI
Swing is the UI toolkit. Complete, stable, in the JDK, forty years of production evidence. Do not reach for JavaFX — it was never finished, the WebView is a frozen WebKit fossil, and its trajectory is driven by Oracle's attention span.

### Frameworks
Spring is not Java. Spring replaces explicit object construction with annotation magic requiring the full framework runtime. Java has constructors, factories, and composition — use them. A container is an explicit architectural decision, not a default.

### Dependencies
Reach for the jar ecosystem when the problem has genuine complexity that warrants it. Not to solve trivial problems the language handles natively. Every dependency is a transitive closure of decisions you didn't make, vulnerabilities you didn't audit, upgrade cycles you now own. That cost must justify itself.

### Javadoc
Readers (human or LLM) are expected to read and understand the code — Javadoc is not a substitute for that. Document what can't be recovered by reading: a class's role/lifecycle, a public static field's purpose and who owns mutating it, non-obvious persistence or contracts. Don't document getters/setters or anything whose purpose is already stated by its name plus its immediate surrounding context (fluent builder methods, a class doc that already covers a field's intent). No handholding, no guessing "what could this be for" — document the border, not both sides of it.
