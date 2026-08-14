# CLAUDE.md
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Memory
At the start of a new session, actively recall your memories for this project
(`/home/walter/.claude/projects/-home-walter-github-Voynich/memory/`) rather
than waiting for a memory to happen to become relevant — a saved memory
doesn't surface itself. This matters especially for the "how do we already
do X" class of question (e.g. mirror/sync commands, established scripts):
check memory before falling back to grepping the repo.

`~/bin/sync-predator.sh` (lives outside any repo — spans multiple repos,
agent convenience not a deliverable of any one of them) already mirrors
this same memory directory to predator alongside the repo, catalog, and
checkpoints — one script call, several rsyncs, covering Voynich plus its
sibling repos (infimg, mcp-service-catalog). Don't treat memory landing on
predator as a surprise or a special case needing separate handling; it's
already covered.

## Vision Pipeline (MCP)
A `predator-catalog` MCP server (user-scope, not repo-local — available in
every Claude Code session on this machine, any repo) exposes tools backed
by a local vision model (gemma-4-e4b, served via llama.cpp's llama-server
directly as of 2026-08-14 — Ollama and the LM Studio wrapper were both
removed from predator that day) running on predator.
`look_at_image` answers a free-text question about an uploaded image;
`convert_image`/`identify_image` wrap ImageMagick. Upload via `PUT
http://predator:8765/files` (or the sibling repo's own upload path), then
call `look_at_image` with the returned `file_id`. Full corpus scans (213
scans, damage counting, 99.1% success) and targeted questions (e.g.
finding pages with a given visual feature) both work — see
`memory/project_voynich_vision_stress_test.md` and
`memory/project_vision_confabulation_finding.md`. Two things to know
before trusting an answer: (1) vision-model output is a first-pass draft,
not ground truth — spot-check, don't take a single answer as fact,
especially near a subjective category boundary (e.g. blue vs. green/teal
pigment); (2) resolution matters for subtle detail — a full-resolution
scan can catch low-contrast/small-area color signal that gets lost if
downscaled to ~1024px or below (see
`memory/project_vision_resolution_floor_finding.md`), so don't reach for
a defensive resize unless the actual file size requires it (predator's
real limit is around 90+MB, not a low bar).

**The app itself also has direct vision access now** (added 2026-08-14,
not just a Claude Code MCP tool anymore): `VisionClient` is a small plain
`java.net.http.HttpClient` wrapper that talks to the same predator
pipeline over its two real wire protocols — `PUT :8765/files` for upload,
then `POST :8764/mcp` (Streamable HTTP MCP transport, one JSON-RPC
`tools/call` message per request) to invoke `look_at_image` — no MCP
client library involved, since two plain HTTP calls don't need one. MCP
stays the *tool-definition contract* only (so the sibling
`mcp-service-catalog` project can add more tools later and this client
gets them for free); image bytes never travel through it, going over the
plain-HTTP upload first instead — the same "big data" workaround Claude
Code's own MCP client needs, since MCP itself never defined file-transfer
semantics. Reachable from `CatalogCli vision <filename> <question...>
[--content-area | --region-name <kind>]` and from the GUI's
`CatalogEntryEditor` "View ▾" → "Ask Vision…" (region-aware, same selector
as Color Frequency/ΔE Heatmap/Open in infimg; free-text question only, no
canned prompts — "a hammer, not a scalpel," Walter's call). Both print/show
just the model's answer text, unwrapped from the MCP response's
double-JSON envelope (`result.content[0].text` is itself a JSON string
holding an OpenAI-style chat completion). See
`memory/project_vision_gui_cli_tool.md` for the design rationale and
`memory/project_vision_confabulation_finding.md` for live confabulation
examples surfaced through this exact GUI feature.

## Build and Run
All commands run from the repo root (`/home/walter/github/Voynich/`).
```bash
# Build fat jar
mvn package
# Run (fat jar)
java -jar target/Voynich-1.0-jar-with-dependencies.jar [optional-config-file]
# Run via Maven
mvn exec:java
# Smoke test: builds/shows the main JFrame, exits 0 once it's actually
# painted (not just constructed), instead of sitting open. Useful after a
# change to confirm the app still starts at all without a full manual run.
java -jar target/Voynich-1.0-jar-with-dependencies.jar --smokeTest
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

## Architecture
Single Maven module, Java 17, Swing UI with **FlatDarculaLaf** dark theme.

| Class | Role |
|-------|------|
| `Voynich` | Entry point. Loads config, validates `scanPath`, builds the main `JFrame`. The window uses a `JMenuBar`, not a `JToolBar` — replaced 2026-08-14 once a usage audit found most toolbar buttons (Scan, MarkUp, Storage, Exit) were each used at most a handful of times per session while only Sort/Filter/Content-Area-Only saw real repeat use, making even those not worth permanent screen space either; a `JToolBar` with effectively one live button is just a worse `JMenuBar`. Menus follow CUA convention (File first, then Edit, View, Review, Selected) since "creatures of habit" was the explicit design call, not the single-`[Menu]`-button pattern `infimg` uses (that shape earns its keep there because of genuinely many high-frequency actions — Prev/Next, Zoom/Rotate, copy/paste — which isn't the case here): **File** (Scan, Storage…, Exit), **Edit** (Select All, Clear Selection — thin wrappers around `OverviewPanel.selectAllEntries()`/`clearEntrySelection()`), **View** (Sort, Filter, a `JCheckBoxMenuItem` for Content Area Only), **Review** (MarkUp… — now an on-demand `JOptionPane` prompt for the tag template instead of an always-visible toolbar `JTextField`, since the field wasn't used often enough to justify permanent space either), **Selected** (Color Frequency/ΔE Heatmap/Ask Vision…/Open in infimg/Two-Page View/Thumbnail Matrix, all operating on `OverviewPanel.getSelectedEntries()` — see `OverviewPanel`'s row for the click/selection semantics that feed it). The Selected menu rebuilds each item's enabled state on `MenuListener.menuSelected` (Color Frequency/ΔE Heatmap only for exactly 1 selected; Ask Vision/Open in infimg for 1+; Two-Page View only when `twoPagePair` resolves a valid pair; Thumbnail Matrix for 1+) rather than tracking selection changes live, since the menu is always closed while a selection changes anyway. A `BusyIndicator` sits docked at the menu bar's trailing end (`menuBar.add(Box.createHorizontalGlue())` then the component), sized from `fileMenu.getPreferredSize().height` and assigned to `RegionView.busy` once, right after the menu bar is built. "Open in infimg" nags (`>12` selected) before opening that many files at once, added once Select All made an accidental mass-open easy; "Two-Page View" and "Thumbnail Matrix" compose a new image via `ImageGrid` and open *that* through infimg rather than a new in-app `ViewFrame` viewer — Walter's explicit call, so the result can be saved/discarded/clipboarded using infimg's own tools instead of a bespoke viewer. Two-Page View composes verso *left*, recto *right* — the order an open book spread actually reads (verso is the back of the previous leaf, sitting on the left; recto is the front of the current leaf, on the right) — fixed 2026-08-14 after an initial version had the two sides backwards, caught by Walter spotting it visually on a real page pair. "Ask Vision…" scales similarly by selection count (see `RegionView`'s row) since a vision call is the one genuinely expensive action in the app: for exactly 2 selected, a Combined/Separate/Cancel choice offers sending one composite (1 call) or asking about each page separately (2 calls) — a real choice, not a silently-inferred N=2 special case, per Walter's explicit "too much magic" pushback on a first draft that combined automatically; the Combined path scales each source image down to `VisionClient.MAX_DIMENSION / 2` per side *before* compositing (fixed 2026-08-14 — an earlier version composited at full native resolution, producing a ~50MB/21-megapixel PNG that the vision model failed on with a confabulated "I can't see an image" answer instead of a clean error; the fix is to never rely on `VisionClient`'s own post-hoc downscale-after-decode to save an oversized upload, since that only clamps after the full file has already been decoded). For 3+ selected, `askVisionSequentially` confirms once ("N images / N calls") then fires one call at a time (never concurrently) via `RegionView.askVision`'s `onComplete` callback chain. `launchImageView` now has two overloads: the original single-`File` form and `launchImageView(List<File>)`, which appends every file's path to infimg's argv — infimg already treats all trailing positional arguments as a navigable file list for its own Prev/Next buttons, so no infimg-side change was needed to support opening a multi-page composite or a raw multi-file selection as one browsable session. |
| `Config` | Plain POJO serialized to/from `~/.infVoy/config.json` via `JSON`. Add fields here for new persistent settings. `viewBounds` (a `Map<String, Config.Bounds>`) remembers named tool windows' last on-screen position/size — see `ViewFrame`. `infimgJar` is a launch *command*, not necessarily a jar path directly — point it at a wrapper script (e.g. `~/bin/infimg`) that pins its own jar version internally, so this config value survives infimg version bumps without an edit here each time; `Voynich.launchImageView` executes it directly (`ProcessBuilder`, no hardcoded `java -jar` wrapping — fixed 2026-08-14, previously always wrapped the value in `java -jar`, which silently broke once the configured jar version was renamed/superseded, `pb.start()`'s `IOException` only ever logged, never shown to the user). `visionHost`/`visionFilePort`/`visionMcpPort` (added 2026-08-14) configure `VisionClient`, defaulting to predator's real values. |
| `JSON` | Thin Jackson wrapper. Two `ObjectMapper` instances: `mapper` (pretty/indented) and `liner` (single-line). Always use these rather than creating a new `ObjectMapper`. |
| `EzAction` | `AbstractAction` subclass — the app-wide pattern for a clickable thing plus its full identity (label, behavior, tooltip, optional style hints) declared together at construction, rather than a bare `JButton` wired up separately via `addActionListener`/`setToolTipText`: one seam for any future app-wide change (e.g. a "pastel everything" pass) instead of N scattered call sites. `withTooltip(text)` sets the standard `Action` key `SHORT_DESCRIPTION`, which `JButton`/`JToggleButton`/`JMenuItem`'s `Action`-taking constructors already wire up to the component's hover tooltip automatically — no extra call needed. `withBackColor`/`withForeColor`/`withFont`, by contrast, are inert until the creator calls `applyTo(component)` explicitly (currently unused anywhere in the codebase). Used for every button/menu item across `Voynich` (menu bar as of 2026-08-14, toolbar before that — the same `EzAction`s just moved container), `TaskWindow`, `CatalogEntryEditor`, `RegionManagerDialog`, `ContentAreaEditor`, and `StorageDialog` as of 2026-08-07 (a "drive-by" UI pass found the four latter dialogs had drifted to plain `JButton` + `addActionListener`, each written independently without checking for this convention). |
| `OverviewPanel` | Main content view: a `JList` grid of catalog thumbnails, `HORIZONTAL_WRAP`, `MULTIPLE_INTERVAL_SELECTION` selection mode. Click semantics follow file-manager convention (rewritten 2026-08-14, replacing an earlier version where every click — single or double — opened the editor, with a same-click double-click guard just papering over the resulting double-open): a single click does nothing beyond what `JList`'s own built-in mouse handling already did before this listener even fires — no custom selection code needed, since letting `JList` do its job was the actual fix, not extending the old click-swallowing logic. A double click (`getClickCount() == 2`) opens `CatalogEntryEditor.edit` for the entry under the cursor, first collapsing the selection to just that entry if it wasn't already selected — see "Catalog persistence" below for what the editor does once open. `getSelectedEntries()`, `selectAllEntries()`/`clearEntrySelection()` (the menu bar's Edit menu), and `addSelectionListener` expose the grid's selection to `Voynich`'s "Selected" menu. `findByFilename(String)` (linear scan, same cost as the pre-existing private `indexOf`, not worth a `Map` for its one caller) and `thumbnailOf(CatalogEntry)` (exposes the already-populated `thumbnails` cache, keyed by filename) back Two-Page View's r/v counterpart lookup and Thumbnail Matrix's composite respectively — both new selection-scoped features live in `Voynich`, not here. A nested `Folio` class (plain `number`/`side` fields, no record, per this project's Java style) plus `static Folio parseFolio(String filename)` (`^(\d+)([rv])\.png$`, anchored and requiring the exact shape) parses a strict recto/verso folio reference — deliberately stricter than the pre-existing `SortKey.pageNumberOf` (which only needs the leading digits and tolerates any suffix for sorting), so an irregular filename like `100v_and_101r.png` (a multi-folio composite scan) or `Front_cover.png` (non-foliated) never gets treated as having an inferable r/v counterpart; Two-Page View being unavailable for those pages is the intended outcome, confirmed explicitly rather than a gap. |
| `CatalogEntryEditor` | Non-modal editor over one or more `CatalogEntry` records: the entry's actual image (via `ImageDisplay`) alongside an editable raw-JSON view and a tags box. Two modes share the same window and save-time guards: `edit` (single entry, opened by `OverviewPanel`) and `review` (a shuffled whole-catalog pass driven by a `RapidReviewAction`, e.g. the "Review → MarkUp…" menu action — clicking the image stages a tag in the box; nothing persists until Done). Deliberately non-modal — this app doesn't decide how many entries or tool windows a user is allowed to have open at once; only `JOptionPane` confirm/error prompts stay modal, since those are synchronous answers a caller's control flow depends on, not parallel workspace windows. `advance()` (moves to the next queue entry, or the initial load) decodes the entry's full-resolution image off-EDT via a private `loadFullImage(CatalogEntry)`/`SwingWorker` (fixed 2026-08-14 — previously called `ImageDisplay.loadFull(entry)` synchronously, before the dialog even became visible, which froze the *entire app* on every double-click open of a large scan, not just this dialog, since a blocked EDT can't repaint the menu bar's `BusyIndicator` either): the dialog now shows immediately with a "Loading …" placeholder (`viewButton`/`areaButton` start disabled), reports through `RegionView.busy.enter()`/`exit()`, and guards a stale result — relevant for the MarkUp review queue, which can call `advance()` again quickly via Save before a slow load finishes — by discarding it if `entry` no longer matches the `target` captured when the load started. A "Region:" combo (rebuilt by `refreshRegionSelector` on every `advance()`/`onRegionsChanged`, resetting to "Whole page" on the former but preserving the current pick by label on the latter) picks "Whole page" or one of `entry.regions[1..]` and drives two things at once — deliberately one selector, not two independent controls that could disagree: `updateDisplayedRegion` swaps the inline image itself (no separate window; replaced a former separate "Show Mask" toggle) between the plain scaled view and a `BitSet2D.darkenOutside` overlay of whichever region is picked, built off-EDT; a "View ▾" button's popup menu ("Color Frequency"/"ΔE Heatmap"/"Open in infimg"/"Ask Vision…" — consolidated 2026-08-10 from three separate buttons, once a fourth action made the row too cluttered; a fifth, "Ask Vision…", was added 2026-08-14 the same way) acts on that same selection. As of 2026-08-14 all four items are thin wrappers delegating to the corresponding static `RegionView` method (`openColorVisualization`/`openInInfimg`/`askVision`) instead of each holding its own implementation — extracted so `OverviewPanel`'s "Selected" menu (see `Voynich`'s row) could reach the same region-scoped actions without needing an open editor; `showStandaloneAnswer(Window, String, String)` (package-visible static) is the shared answer-dialog display both this editor's own `askVision()` and `RegionView.askVisionOnImage` call, so there's one dialog implementation, not two. The View ▾ actions themselves still read `entry`/`regionSelector`/`fullImage` as before — only the shared crop/decode/upload logic moved out. "Regions…" stays its own button, deliberately not folded into the menu — it's region *management* (add/trace/rename/reorder/delete), not a view of the current selection, so mixing it in would blur that distinction rather than declutter it. |
| `RapidReviewAction` | Pluggable judgment for `CatalogEntryEditor.review`: just a label plus a tag template built from the click point. The dialog itself has no notion of what a "wash" or any other judgment means — only an implementation of this interface does. |
| `ImageDisplay` | Loads and scale-to-fits a `CatalogEntry`'s actual image file (not its stored thumbnail) into a Swing component, plus the inverse: mapping a click back to the original image's pixel coordinates. Used by `CatalogEntryEditor`. |
| `ViewFrame` | Opens a named, independent tool window (a plain, ownerless `JFrame` — windows don't own windows here; see the class doc for why) around one visualization `JComponent`, remembering that name's on-screen bounds in `Config.viewBounds` across restarts. Named per view *type* (e.g. "Color Frequency"), not per image, so reopening the same visualization for a different entry lands wherever it was last left. An optional `maximizeInitially` flag opens at the current screen's full usable size (`JFrame.MAXIMIZED_BOTH`) the first time a view is opened, before any saved size exists. `defaultDevice(Window)`/`usableBounds(GraphicsDevice)` (package-private, not `private`, as of 2026-08-14) resolve a `GraphicsDevice` and its screen bounds minus insets — reused as-is by `Voynich`'s Thumbnail Matrix fit-check rather than reimplemented, since both need exactly the same "what does maximize actually fill" math. |
| `RegionView` | Static, editor-free versions of the region-scoped view actions `CatalogEntryEditor`'s "View ▾" menu implements — `openColorVisualization`, `openInInfimg`, `askVision`/`askVisionOnImage` — added 2026-08-14 so `OverviewPanel`'s "Selected" menu (see `Voynich`'s row) can reach the same actions directly from the thumbnail grid without an editor open. Each re-reads the target file off disk (via `ImageDisplay.pickExistingFile`) rather than depending on an already-decoded in-memory image, since none of these callers have one; `CatalogEntryEditor`'s own View ▾ items now delegate here too instead of duplicating the crop/decode/upload logic (see its row). A static `busy` field (a `BusyIndicator`, assigned once by `Voynich.main`) is `enter()`/`exit()`-wrapped around every `SwingWorker`'s background work here, so the menu bar's scanner-bar animates while any of these — or `CatalogEntryEditor.loadFullImage` — is running. `askVisionOnImage(Window, String, File, CatalogEntry.Region, String, Runnable)` takes a raw `File` and a `label` string instead of a `CatalogEntry`, so it also serves a composite image built from two selected entries that has no catalog entry of its own (`Voynich`'s Ask-Vision-on-a-pair "Combined" path); its `onComplete` callback (also on the `CatalogEntry`-taking `askVision` overload) lets a caller chain one vision call after another instead of firing several concurrently — `Voynich.askVisionSequentially` uses this to walk a selection set one call at a time, since predator's vision pipeline shouldn't see simultaneous requests and a single `busy` on/off state can't represent several overlapping calls anyway. |
| `ImageGrid` | Generic "paint N already-decoded images into one composite `BufferedImage`" compositor, added 2026-08-14 — the shared logic behind `Voynich`'s Two-Page View (forced 2-column layout, full-resolution sources) and Thumbnail Matrix (`squareColumns(count)`'s auto square-ish layout, cached 256×256 thumbnails as sources). `dimensions(count, columns, cellSize)` computes a composite's exact pixel size before anything is painted — lets Thumbnail Matrix's screen-fit nag (see `Voynich`'s row) check size first and skip building the image at all if the user cancels. `paint(...)` scales each cell down-only (never up) to fit, centers it, and leaves a `null` entry (e.g. a thumbnail that hasn't finished decoding) as a blank cell rather than failing the whole composite. Knows nothing about where a cell's image came from — that's entirely `RegionView`'s or `OverviewPanel`'s business. |
| `BusyIndicator` | A `JComponent` meant to dock at the trailing end of the app's `JMenuBar` (added 2026-08-14): idle and blank whenever nothing is running, animating a 12-LED Cylon/KITT-style scanner bar — a bright lead LED with a 4-LED fading trail behind it, direction-aware (the trail always sits on the side the lead just came from, not a symmetric glow) — only while a `busyCount` kept by paired `enter()`/`exit()` calls is above zero. Deliberately not a busy mouse cursor or a modal progress dialog, matching this app's existing "no spinning wait cursors" convention. Takes its render height as a constructor parameter (a sibling top-level `JMenu`'s own `getPreferredSize().height`, from `Voynich.main`) rather than a hardcoded pixel constant — the original version hardcoded `1` px tall and was effectively invisible on any screen, caught once Walter's 4K monitor made it obvious; every other dimension (width, LED size, trail spacing) now scales off that one height so the whole thing tracks the menu bar's actual font/DPI. Tick rate (90ms, ≈1 second per full sweep) was also tuned down from an initial faster value after a live look showed it reading as too frantic for a background-status indicator. |
| `FrequencyBarChart` | Ranked colour-frequency swatch bars for one `ColorImage`. Colours are grouped into ~5-unit CIELab bins before ranking, not ranked by exact RGB value — these scans are photographed against a flat black backdrop, which repeats a handful of exact RGB values enormous numbers of times, while the paper (naturally grainy, despite covering far more of the page) splits its true colour across thousands of individually-small near-identical values; ranking by exact value buries the paper and ink entirely under backdrop noise. |
| `DeltaEHeatmap` | Per-cell CIE76 ΔE from a `ColorImage`'s pixel-count-weighted mean Lab colour, rendered over `ColorImage.labThumbnail`'s 256×256 grid as a blue (near the mean) to red (most different) heat map — spatially exposes ink, staining, or pigment anomalies that a frequency count alone can't show where. When `ColorImage.thumbnailMask` is non-null (a region-scoped crop), cells it has clear (the crop's masked-out corners/letterbox padding) render as plain black and are excluded from the max-ΔE scale entirely, not just from the reference mean — otherwise those cells' now-enormous distance from a mean correctly anchored to real content would dominate the scale and crush every real in-region variation down near "matches the mean". |
| `ContentAreaCanvas` | Interactive tracing surface for one `CatalogEntry.Region`'s polygon over its full-resolution image: click to place vertices tightly around the actual content — text, illustration, wash, not the physical page — click near the start to close, drag any handle to adjust afterward. Polygon-agnostic about *which* region it's tracing; that choice is made by its caller, `ContentAreaEditor`. The live segment to the cursor while tracing is drawn via `Graphics2D.setXORMode`, not a repaint — the same line drawn twice cancels out, so a mouse-move just erases-and-redraws that one segment directly instead of re-rendering the whole (often very large) image on every pixel of cursor travel. A tracking loupe pair (plain 4x + contrast-boosted 4x, anchored to whichever screen corner is diagonally opposite the cursor) shows native pixels regardless of on-screen scale — catches both imprecise edge placement on a heavily downscaled page and content dim enough to nearly miss. |
| `ContentAreaEditor` | Just the polygon editor now: wraps `ContentAreaCanvas` with Clear/Commit/Cancel controls and opens it via `ViewFrame` (`maximizeInitially=true` — every pixel of screen matters for precise tracing). Which `CatalogEntry.Region` it's tracing — a brand new one (`kind`/`author` already decided, only appended to `entry.regions` on Commit, never before, so a Cancel leaves nothing half-formed) or an existing one being re-traced — is entirely `RegionManagerDialog`'s call, made before this opens. See its class doc for why a region's polygon is human-traced rather than auto-detected: no fold — however severe — is ever a true boundary, since content routinely runs right through them, and judging how faint a mark can be before it still counts as content is a call a human makes better than a tuned threshold. Renamed from `WorkingAreaEditor`/`workingArea` 2026-08-05, generalized from a single `contentArea` polygon to the `regions` list 2026-08-07, shrunk to just the canvas wrapper (management UI moved out to `RegionManagerDialog`) 2026-08-07 — see "Catalog persistence" below. |
| `RegionManagerDialog` | Lists `entry.regions` (index 0, the synthetic whole page, excluded — never user-editable) with View/Trace/Rename/Up/Down/Delete per row plus an Add button, opened by `CatalogEntryEditor`'s "Regions…" button. List layout mirrors `StorageDialog` (plain `GridBagLayout`, no `JTable`). "View" (via `BitSet2D.cropToPolygon` then `ImageDisplay.scaleToFit` to ~2/3 of the current screen) is the only way to actually see what a trace covers at a sane scale — `CatalogEntryEditor`'s "Region:" selector overlays the whole page at page scale, which alone makes a small region (a faint imprint mark, say) easy to miss entirely. Every action saves to the catalog immediately — no batched "Done," so no unsaved-state to track on top of `CatalogEntryEditor`'s own JSON-blob staleness guard. Exists because `regions`' index convention (`regions.get(1)` is always the main content area, no boolean flag) turns fragile the moment a UI can delete or reorder rows, not just append: Add always appends at the end so it can never change what's main; Up/Down swap adjacent rows one step at a time (never drag-and-drop) so promoting a region to main is always the visible, direct result of a click, not a side effect of deleting something else; Delete on the main row gets its own warning naming the consequence (the next region, if any, becomes the new main) rather than the generic wording. |
| `BitSet2D` | A bit-per-pixel 2D mask (`BitSet` under the hood — real word-level range operations, not one call per pixel) plus utilities: flood fill (`oilSpill`), grow/shrink, invert, image conversion. `createFromPolygon` rasterizes a `List<Point>` (e.g. a decoded `CatalogEntry.mainRegion()` polygon) via a real scanline fill straight into the bits — deliberately not through `java.awt.Shape#contains`, which is the trap `createFromShape`/`copy`/`setOrClear` (kept, `@Deprecated`) fall into: recomputing the winding number from every edge on every pixel query is fine once, ruinous over millions. First consumer: `CatalogEntryEditor`'s region overlay (`updateDisplayedRegion`). |
| `TaskWindow` | Abstract `JFrame` + `SwingWorker` wrapper for a background task: progress bar, log, Cancel button. One window per task-type, reused (not recreated) on repeat runs via a static registry. |
| `ScanTaskWindow` | `TaskWindow` that walks `config.scanPath`, decodes each image via `ColorImage`, and records it into the catalog with `Catalog.recordSighting`. |
| `Catalog` | Persistence contract for the image catalog: one `CatalogEntry` (thumbnail inlined as base64) per filename. `Catalog.open(Config)` opens the `FileCatalog` backend. |
| `CatalogEntry` | JSON-serializable catalog record, keyed by filename (not path) — see "Catalog persistence" below. |
| `FileCatalog` | `Catalog` backed by a `<filename>.json` sidecar file per entry under a catalog directory, thumbnail included inline as `CatalogEntry.thumbnailPng` (base64 via Jackson). The only backend. |
| `CatalogCli` | Command-line access to the catalog (`list`, with an optional case-insensitive/invertible text filter over an entry's whole JSON; `get`/`tag`/`save`; `checkpoint`/`restore`), through the same `Catalog.open(Config)` the GUI uses. Run via `java -cp target/Voynich-1.0-jar-with-dependencies.jar nl.infcomtec.voynich.CatalogCli <command>`, bypassing the fat jar's GUI `Main-Class`. `extract` pulls real decoded pixels: `--pixel x,y`/`--region x,y,w,h` (repeatable) go through `ColorImage`/`ColorBase` for rgb/lab/hex output, same colour math the GUI views use; `--content-area` skips that (no Lab decode needed for a raw crop) and instead writes a PNG cropped to `CatalogEntry.mainRegion()`'s bounding box, black outside the polygon (via `BitSet2D.cropToPolygon`, also used by `RegionManagerDialog`'s "View"; both just want the picture. `CatalogEntryEditor`'s region-scoped colour analysis instead uses `BitSet2D.cropAndMaskPolygon`, which returns the same cropped image plus a crop-local mask so the blacked-out corners can be excluded from analysis rather than just hidden). `vision <filename> [<filename>...] <question...> [--content-area \| --region-name <kind>] [--combine]` (single-file form added 2026-08-14, multi-file/`--combine` added the same day once the GUI's "Selected → Ask Vision…" gained multi-page support) asks the local vision model a free-text question via `VisionClient` and prints the answer to stdout — whole page by default, or the same crop-to-polygon path `extract`'s region flags use (written to a temp PNG, uploaded, deleted after); no automatic downscaling, so a very large image can still hit the crash floor documented above. A single filename behaves exactly as before (unambiguous, no separator needed); two or more filenames require a literal `--` before the question, since both are otherwise-indistinguishable trailing positional args (the single-file case detects the common "forgot the `--`" mistake — the word right after the lone filename itself naming a real catalog entry — and fails loudly rather than silently folding a second filename into the question text). Without `--combine`, multiple filenames fire one sequential `VisionClient` call per file, each answer printed prefixed with its filename; `--combine` (exactly 2 filenames, whole-page only, mirrors the GUI's Combined choice) composes them into one side-by-side image via `ImageGrid` first — each source scaled to `VisionClient.MAX_DIMENSION / 2` per side before compositing, the same pre-composite downscale that fixed a real GUI bug (an uncapped composite produced a ~50MB/21-megapixel PNG the model failed on with a confabulated "I can't see an image" answer instead of a clean error) — and asks once. `two-page <filename> [<other-filename>] [--out path]` and `matrix <filename> [<filename>...] [--out path]` (both added 2026-08-14) are the CLI equivalents of the GUI's Two-Page View/Thumbnail Matrix — full-resolution recto+verso composite (verso left, recto right; one filename infers its counterpart via `OverviewPanel.parseFolio` plus a direct `Catalog.loadEntry` lookup, erroring for a non-foliated or irregular filename) and cached-thumbnail grid composite (`ImageGrid.squareColumns`) respectively, via the same `ImageGrid` compositor the GUI uses. Neither has the GUI's interactive nags (infimg's >12-file confirm, the Thumbnail Matrix screen-fit warning) — a CLI invocation is already an explicit, scripted choice, consistent with every other `CatalogCli` command's existing no-confirm-prompt convention. `--out <path>` writes the composite there instead of opening it (same meaning as `extract`'s own `--out` — the caller wants the file, not a live viewer); without `--out`, writes to a temp file and opens via `Voynich.launchImageView(File)`, same convenience `extract --view` offers — which only works correctly as of this same change: `CatalogCli.main()` loads its own local `Config` but never used to assign it to the static `Voynich.config` field `launchImageView` actually reads, so `extract --view` had been silently relying on whatever that static happened to be (usually `null`) rather than the config `CatalogCli` itself loaded; fixed by assigning `Voynich.config = cfg` right after `Catalog.open`. |
| `VisionClient` | Plain `java.net.http.HttpClient` wrapper (no new dependency) around the `mcp-service-catalog` sibling project's vision pipeline on predator — see the "Vision Pipeline (MCP)" section above for the full wire-protocol rationale. `uploadImageDownscaled` (the normal entry point, added 2026-08-14) decodes, downscales to `MAX_DIMENSION` (2048px on the longer axis, no-op if already smaller, never upscales) and re-encodes as PNG before calling `uploadFile` (`PUT :8765/files`) — "vision is not huge-capable" is a standing constraint of the underlying model/server (see the pixel-clamp/ubatch limits in the "Vision Pipeline (MCP)" section above), not a per-call decision, so every real caller downscales unconditionally rather than opting in; `uploadFile` itself stays available undownscaled for anything already known-small. Then `askAboutImage` (`POST :8764/mcp`, JSON-RPC `tools/call` naming `look_at_image`) unwraps the response's double-JSON envelope (an MCP `content[0].text` string that itself holds a stringified OpenAI-style chat completion) and strips a markdown code-fence wrapper (```` ```json ... ``` ````) the model applies inconsistently despite being asked for bare JSON — added 2026-08-14 after a live anomaly-triage pilot found it broke naive JSON parsing of the CLI's stdout — so callers just get clean answer text. Used by both `CatalogCli vision` and `CatalogEntryEditor`'s "Ask Vision…". |

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

Renamed from the singular `contentArea` field to this `regions` list
2026-08-07, itself once renamed from `workingArea` 2026-08-05 (which the
class doc originally defined as the full physical page). Both renames
needed real handling, unlike a fresh field addition: Jackson binds by JSON
property name, so a bare Java field rename would have silently orphaned
every already-traced entry's polygon on next read. Each time, the existing
entries were migrated in place (checkpoint first, then each entry's stored
JSON rewritten — by hand for the 5-entry `workingArea`→`contentArea` move,
by a small one-off script for the 213-entry `contentArea`→`regions` move
since every entry needed touching by then — vertex counts diffed against a
backup to confirm nothing was lost) rather than kept under a `@JsonAlias`
shim — the old contract was never actually followed to the letter in
practice, so there was no split "some entries mean the old thing" case to
preserve, just a label catching up to what the data already was.

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
between users will eventually need an explicit import/export path (e.g.
diffing/merging two `FileCatalog` directories) rather than just handing
over the raw catalog directory — not built yet, no consumer of it exists
yet either.

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
