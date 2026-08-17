# Voynich

> **Disclaimer:** AI was used extensively as grunt labor for text processing
> in this project. No unsupervised AI code should be present.

A general-purpose toolkit for analysing and browsing large collections of
images — decode at scale, generate consistent thumbnails, catalog by visual
similarity, find duplicate/near-duplicate copies across a collection. The
Voynich manuscript scans are the working dataset, not the subject: a large,
freely available, high-resolution public-domain image collection with no
licensing complications, which is a convenient stand-in for "some arbitrary
massive public source of images." Nothing in the codebase assumes manuscript
content, and design decisions are made against that generic case, not
anything Voynich-specific. The name stuck because the dataset that motivated
the project did.

## Why "Lightroom" as the reference point

Photo-catalog tools solve a known set of problems: import and decode a
directory of images at scale, generate thumbnails consistently, catalog and
search by visual similarity, detect duplicate or near-duplicate copies
across a collection, and do all of that fast enough to work with tens of
thousands of files. Naming that reference point makes "have we done this
yet" a concrete, answerable question, rather than a moving target.

## Current state

This is early. Worth being precise about what exists versus what's still a
gap.

**Implemented (library level):**
- Config-driven scan directory loading
- Full CIELab colour pipeline (`EnhancedColor`/`FloatColor`/`YUV`) — RGB↔CIELab↔XYZ↔YUV↔HSB conversion, ΔE distance, blending, gamut checks
- Two-level RGB→CIELab cache (`ColorBase`): per-image plus a JVM-wide static cache, so a colour repeated across an entire collection is converted once
- Per-image colour inventory — every distinct colour in a decoded image with its pixel count (`ColorImage.cb`, `ColorImage.labIndex`)
- Fixed 256×256 thumbnail generated on load (aspect-preserved, black-letterboxed to match real scanbed edges), matching the freedesktop.org thumbnail-spec "large" size so a single 4K monitor can host a proper contact sheet
- A CIELab-thumbnail distance metric (`ColorImage.distanceTo`) — mean per-cell ΔE between two images' thumbnails, resolution-independent, the basis for "is this a copy of that" comparisons across a collection. A scaling bug in `ColorBase` made every `deltaE`/`distanceTo` result ~100× too small until fixed 2026-08-05 (nothing had consumed the numbers yet, so nothing else was silently affected)
- A catalog persistence layer (`Catalog`/`CatalogEntry`, `FileCatalog` backend) — one JSON record per filename, thumbnail inlined as base64, keyed by filename rather than path so the same file at two locations (e.g. a NAS copy and a local NVMe copy) is one entry, not two. A `MySqlCatalog` backend existed earlier in the project; retired 2026-08-06 once every entry had been exported to and verified against the file backend — see `CLAUDE.md`'s "Catalog persistence" section

**Implemented (app level):**
- `Catalog` wired into `Voynich.main`: the menu bar's File → Scan action walks
  `config.scanPath`, decodes each image, and records it via
  `Catalog.recordSighting`, with progress shown live in a `TaskWindow`
  (background `SwingWorker`, Cancel while running/Close when done, one window
  per task-type reused on repeat runs) — see `CLAUDE.md` for the class
  rundown
- Catalog/browse UI: `OverviewPanel`, a `JList` grid over stored thumbnails
  and filenames, populated from `Catalog.listAll()` at startup and updated
  live as a scan runs — the grid view the roadmap below used to list as a
  separate step
- Re-scans skip any file whose catalog entry already matches its on-disk
  `size`/`mtime`, so a repeat scan with nothing changed completes in seconds
  instead of re-decoding all 210+ images
- A free-form per-entry "notepad" — `CatalogEntry.torrentJpg` (a cross-reference
  to the original 2004/torrent JPG numbering, when known) and `CatalogEntry.tags`
  (short free-text notes like `"circular diagram"` or `"foldout"`;
  deliberately not a fixed set of categories, since new kinds of note keep
  turning up). Clicking a thumbnail in `OverviewPanel` now opens a non-modal
  editor instead of doing nothing — a raw-JSON view of the entry (everything
  except `tags`, which gets its own one-note-per-line box so adding a note
  doesn't mean hand-typing JSON array syntax), Jackson-validated on Save
  and guarded against the two easy ways to corrupt an entry by hand
  (changing `filename`, the catalog key, or emptying `locations`)
- `CatalogCli` (`list`, with an optional case-insensitive/`-v`-invertible text
  filter over an entry's whole JSON; `get`/`tag`/`save`; `checkpoint`/`restore`),
  a standalone command-line tool against the same `Catalog` the GUI uses — for
  scripted or one-off catalog reads/edits without opening the app; see its
  class doc for exact usage
- A case-insensitive JSON text filter (`OverviewPanel.filter()`, mirrored in
  `CatalogCli list`) — substring match over an entry's whole JSON, not just
  its filename, so it also catches hits in tags/`torrentJpg`/locations; an
  invert checkbox (GUI) or `-v`/`--invert` flag (CLI) flips it, e.g. to find
  entries still missing a given tag
- A general click/skip/note/abort review pass (`CatalogEntryEditor.review()`)
  over a shuffled queue of every catalog entry — the Review menu's
  "MarkUp…" action (an on-demand prompt for the tag template, rather than
  an always-visible field, since it's used rarely enough that permanent
  screen space wasn't worth it) is one instance of it (`RapidReviewAction`),
  not a special case. The template supports `$X`/`$Y`/`$RGB`/`$LAB`
  placeholders, filled in from the clicked pixel. Clicking the shown image
  stages a tag in an editable box; nothing is persisted until Done, so a
  whole pass is reviewable/correctable before any of it hits storage
- Manual checkpoint/restore for the whole catalog (`Catalog.checkpoint()`/
  `Catalog.restoreCheckpoint()`) — a coarse, whole-catalog clone into one
  timestamped zip (`java.util.zip`, no extra dependency), managed via
  File → "Storage…" (`StorageDialog`: live catalog size, each
  checkpoint's timestamp/age/size, take/restore/delete) or
  `CatalogCli checkpoint`/`restore`. No automatic pruning of old
  checkpoints — deliberate, left for hand cleanup (or the dialog's Delete)
- A content-area-only view toggle in `OverviewPanel` (the View menu's
  "Content Area Only" checkbox) — dims every thumbnail down to just its traced
  `CatalogEntry.contentArea`, mapped from the polygon's full-resolution
  coordinates into the 256×256 thumbnail's via the same scale-and-center
  `AffineTransform` used to build the thumbnail in the first place. An
  entry with no (or an incomplete) trace stays plain either way, so the
  toggle doubles as a visual "still needs tracing" checklist rather than
  implying an untraced page is confirmed empty
- Two per-entry colour visualizations in `CatalogEntryEditor`, opened as
  independent windows via `ViewFrame`: `FrequencyBarChart` (ranked swatch
  bars, colours grouped into perceptual CIELab bins rather than ranked by
  exact RGB, since a flat photo backdrop otherwise buries the paper/ink
  colours under scan noise) and `DeltaEHeatmap` (per-cell ΔE from the page's
  average colour, spatially exposing ink/staining/pigment anomalies a
  frequency count alone can't show *where*). `ViewFrame` remembers each
  named window's on-screen position/size in `Config.viewBounds` across
  restarts. No modality anywhere in this stack — the app doesn't limit how
  many entry editors or tool windows a user has open at once; `JOptionPane`
  confirm/error prompts are the one deliberate exception, since those are
  synchronous answers, not parallel windows
- A standalone image viewer, [infimg](https://github.com/Walter-Stroebel/infimg)
  (separate repo, no longer forked into this one — see `inventory.md`),
  launched as a detached process (`Voynich.launchImageView`, jar path from
  `Config.infimgJar`) — fit-to-window, mouse-wheel zoom/rotate, drag-pan,
  clipboard paste/copy, exact-view Save — reachable from `RegionViewer`'s
  "Save to /tmp & View" button, `CatalogCli extract --view`, and the menu
  bar's "Selected → Open in infimg" (single or multiple pages at once, via
  a `launchImageView(List<File>)` overload that hands infimg one
  browsable session instead of opening N separate windows; nags above 12
  selected pages, since Select All made an accidental mass-open easy).
  Deliberately its own process per window rather than another `JFrame` in
  this app's own EDT, since a user routinely ends up with dozens open side
  by side.
- File-manager-style thumbnail selection in `OverviewPanel` (single click
  toggles/ranges a selection, double click opens the editor) feeds a
  "Selected" menu with actions that operate on the whole selection at
  once, not just one entry: Color Frequency/ΔE Heatmap/Ask Vision for
  exactly one selected page (unchanged from the single-entry behavior),
  Open in infimg for any number, and two new compose-then-open-in-infimg
  actions — **Two-Page View** (an exact folio's recto+verso pair, side by
  side, verso left/recto right as an open book actually reads; enabled
  only for a genuine r/v pair or a single page whose counterpart exists in
  the catalog, never for non-foliated pages like covers) and **Thumbnail
  Matrix** (any number of selected pages' existing cached thumbnails
  composited into one grid, with a screen-fit warning before building an
  oversized one). Both hand their composite to infimg rather than opening
  a new in-app viewer, so it can be saved, discarded, or clipboarded using
  infimg's own tools. Ask Vision on more than one page confirms intent
  first (a real vision call is the one genuinely expensive action in this
  app) and, for exactly two pages, offers to send them as one combined
  image (capped well under the vision model's real size limits before
  compositing) instead of two separate calls. `CatalogCli` has full parity
  for the vision/composite side of this: `vision` now takes one or more
  filenames (a literal `--` separates filenames from the question once
  there's more than one) with the same single-call/`--combine`/sequential
  shapes as the GUI, and `two-page`/`matrix` build the same composites and
  either open them in infimg or write to `--out` — no CLI equivalent for
  raw multi-select/Open-in-infimg itself, since a shell one-liner already
  covers that scripted case.

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

## Why plain files, not a DB

The catalog started on MySQL (in Docker, with a matching GTID
master-slave/master-master replication setup in `replication/` for
durability during long-running operations) on the reasoning that anyone
running their own container already has the "which DB, where, why, how"
answers a single-user desktop tool shouldn't pre-decide for them. That
backend was retired 2026-08-06: once thumbnails moved inline into each
entry's JSON (base64, no separate BLOB/sidecar to keep in sync) and
whole-catalog checkpoints became a single zip instead of a directory/table
copy, the file backend covered everything the project actually needed, and
running a second stateful service just to browse a few hundred images
stopped paying for itself. All 213 entries were exported to and verified
against the file backend (counts, thumbnails, traced `contentArea`
polygons) before the MySQL code and its Docker/backup infra were deleted —
see `CLAUDE.md`'s "Catalog persistence" section for the mechanics that
replaced it.

`replication/` is unrelated infrastructure exploration that predates this
decision and remains in the repo as generic, `Catalog`-independent GTID
MySQL replication work (master-slave and master-master, live-tested between
two real machines) — see `replication/README.md` if that's ever useful for
something else. Nothing in `Catalog`/`Config` consumes it.

## Build and run

See `CLAUDE.md` for build/run commands and the full class-by-class
architecture rundown — kept there as the actively maintained reference so
this file doesn't drift out of sync with it.

## User manual

`MANUAL.md` is the full walkthrough for actually using the app —
installation, the main window, cataloging, region tracing, color
analysis, vision queries, multi-page views, and a CLI reference, with
real screenshots. Reads fine as plain Markdown, or render it to a
single self-contained HTML file (all images embedded, nothing else
needed) with:

```bash
pandoc MANUAL.md -o MANUAL.html --standalone --self-contained \
  --metadata title="Voynich Cataloging Tool — Manual" --variable=title:
```

The `--metadata title=` plus empty `--variable=title:` pair is
deliberate: pandoc's default HTML template renders a `title` metadata
value as an extra `<h1>` at the top of the body on top of the
document's own heading, so this combination gives the page a correct
browser-tab title without a duplicated heading underneath it. The
rendered `MANUAL.html` isn't checked into the repo — regenerate it on
demand from the command above whenever you want to hand the manual to
someone as one file.
