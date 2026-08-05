# Inventory

A snapshot of what exists in this project, taken 2026-08-04. This is a
point-in-time listing, not a maintained architecture doc — see `CLAUDE.md`
for the actively-kept class rundown and `README.md` for current
state/roadmap. Re-generate rather than hand-edit when it goes stale.

## Code (`src/main/java/nl/infcomtec/voynich/`, 24 classes)

- **App shell**: `Voynich` (entry point/JFrame), `Config`/`JSON` (settings,
  Jackson wrappers), `EzAction` (styled Swing actions)
- **Catalog layer**: `Catalog` (contract + `recordSighting`/`checkpoint`/
  `restoreLatestCheckpoint`), `CatalogEntry`, `MySqlCatalog`, `FileCatalog` —
  filename-keyed, MySQL or JSON+PNG sidecars; manual whole-catalog
  checkpoint/undo on both backends (2026-08-04)
- **UI**: `OverviewPanel` (thumbnail grid), `TaskWindow`/`ScanTaskWindow`
  (background-task progress pattern), `CatalogEntryEditor` (non-modal
  per-entry edit and shuffled whole-catalog review, sharing one
  window/save path), `RapidReviewAction` (pluggable review judgment, e.g.
  toolbar's "MarkUp"), `ImageDisplay` (scale-to-fit + click-to-pixel
  mapping for an entry's actual image), `ViewFrame` (named tool window
  with remembered bounds), `FrequencyBarChart`/`DeltaEHeatmap` (per-entry
  colour visualizations, opened via `ViewFrame`)
- **Colour pipeline**: `EnhancedColor` (RGB↔CIELAB↔XYZ↔YUV↔HSB, ΔE),
  `FloatColor`, `YUV`, `ColorBase` (two-level RGB→LAB cache), `ColorImage`
  (per-image colour inventory), `TriElm`

Note (2026-08-05): `RingDiagramSegmenter` was deleted — a failed first-pass
experiment at extracting upright figure crops from circle-diagram pages,
superseded by the page-level `Circular diagram` tagging pass via MarkUp.

Note (2026-08-05, later same day): added `ViewFrame`, `FrequencyBarChart`,
`DeltaEHeatmap` (see CLAUDE.md for what each does); fixed a `ColorBase`
scaling bug that made `deltaE`/`distanceTo` results ~100x too small;
`CatalogEntryEditor` and the new viz windows are now deliberately
non-modal. Otherwise this inventory still reflects 2026-08-04.

Note (2026-08-05, still later): added `CatalogEntry.workingArea`/`Vertex`
(human-traced page-boundary polygon, excluding backdrop/frayed edge/other
pages in the stack — never auto-detected), `WorkingAreaCanvas`/
`WorkingAreaEditor` (the tracing tool, spawned from `CatalogEntryEditor`'s
new "Working Area" button). Also converted `ViewFrame` from an
owner-of-the-main-frame `JDialog` to a plain, ownerless `JFrame` — windows
don't own windows in this app — and added its `maximizeInitially` option
(real `JFrame.MAXIMIZED_BOTH`, used by `WorkingAreaEditor`). 26 classes now.

Note (2026-08-05, still later still): added `BitSet2D` — Walter's own
bit-per-pixel 2D mask class, ported in from his personal CodeLibrary and
adapted here (dropped `BaseImage`-dependent Sobel/edge-detect factories not
worth importing; fixed a real pre-existing iterator bug where `hasNext()`
used `idx > 0` instead of `idx >= 0`, silently returning zero points
whenever pixel (0,0) was set; added `createFromPolygon`, a scanline fill
straight from vertices that avoids `Shape.contains()` entirely). This is
the intended storage/query layer for turning a `workingArea` polygon into
fast per-pixel ROI membership. 27 classes now.

Note (2026-08-05, still later again): wired up `BitSet2D`'s first consumer —
`CatalogEntryEditor` gained a "Show Mask" toggle button that darkens
everything `workingArea` excludes on the inline image (built off-EDT via
`BitSet2D.createFromPolygon`, cached per entry, invalidated if
`WorkingAreaEditor` commits a new trace while this dialog is still open).

Note (2026-08-05, one more time): `CatalogCli extract` gained
`--working-area` — crops to `CatalogEntry.workingArea`'s bounding box and
writes a PNG (stdout or `--out`), black outside the polygon. Second
`BitSet2D.createFromPolygon` consumer. Verified end-to-end against 1r.png.

Note (2026-08-05, yet again): added a cursor-tracking loupe pair (plain 4x
+ contrast-boosted 4x, opposite-corner anchored, hysteresis around the
center axes) to `WorkingAreaCanvas`, live-tested and confirmed working.
Then: `workingArea` renamed to `contentArea` everywhere (field, both
tracing classes → `ContentAreaCanvas`/`ContentAreaEditor`, CLI flag
`--content-area`, button label "Content Area") — the documented "full
physical page" contract was never actually followed in practice (tight
content-hugging boxes felt natural, exhaustively including blank vellum
didn't), so the name was changed to match the real, human-incentive-aligned
behavior instead of asking the behavior to match the name. The 5 entries
already traced at rename time were migrated in place (checkpoint, then
each entry's stored JSON key rewritten via `CatalogCli save`, vertex
counts diffed against a pre-rename backup to confirm nothing was lost) —
see CLAUDE.md's Catalog persistence section for the full rationale.

Dependencies: FlatLaf 3.3, mysql-connector-java 8.0.27 (legacy artifact
coordinate — `com.mysql:mysql-connector-j` is the maintained one, noted as
minor housekeeping in the README roadmap), Jackson 2.18.2. No test
framework yet.

## Data

- **210 PNG scans**, 3.8GB, at `/home/walter/voynich_png` (this machine's
  configured `scanPath`)
- `data/voynich-page-index.json` — Yale Beinecke IIIF manifest mapping
  torrent-numbered JPGs (001–213) to canonical folio labels
- `~/.voynich-catalog` (FileCatalog fallback dir) — empty on this machine;
  the live catalog is MySQL on predator (192.168.2.23:13306)
- `src/main/resources/stolfi/` (gitignored — third-party sourced + one
  session's scratch analysis, not an app deliverable):
  - `LSI_ivtff_0d.txt` (1.7M) — Landini-Stolfi Interlinear transcription
    v1.6e6
  - `voynichese_data/` + `.zip` — 225 XML files, per-folio word bounding
    boxes
  - `voynich_labels.json` / `voynich_labels_spatial.json` — 988 unique
    labels across 51 folios, joining EVT transcription with voynichese.com
    coordinates (a join that didn't exist anywhere before this project did
    it), plus fitted-circle rotation geometry for zodiac-section folios
  - `circle_diagram_census.md` — manual per-file diagram count (~28-30
    diagrams; automated Hough-circle detection was tried and abandoned)
  - `segments/70v2/` — extracted figure crops (1 folio so far)
  - `read_this_first.txt` — session notes: pigment transfer findings,
    multispectral data status

## `/usb1` (NAS mount — present identically on this machine and on predator)

Raw source material and backups, outside the git repo and outside the
app's own catalog. Six `voynich*` directories:

- `voynich_png/` — 210 files, 3.8G — the working set (`scanPath`)
- `voynich_tiff/` — 210 files, 6.9G — original TIFF scans the PNGs were
  converted from
- `voynich_tor/` — 429 files, 2.7G — the original torrent download, as-is
  (each image paired with a `*_thumb.jpg`), including a lot of non-page
  filler files
- `voynich_jpg/` — 213 files, 2.1G — `voynich_tor/` with the filler
  stripped down to just the 213 real pages (`001.jpg`–`213.jpg`); this is
  the set `data/voynich-page-index.json` maps to folio labels
- `voynich.spec/Voynich_001r/` — 38 files, 3.6G — multispectral capture of
  f1r only, 37 wavelength-band TIFFs (`MB365UV` through `MB940IR`, plus
  filter variants) from the Lazarus Project 2014 scan, per-file ~100MB. A
  large volume of data of questionable value/quality (unverified
  provenance/calibration) — not worth the storage cost of a second copy,
  so this exists **only** on the NAS, not on predator's NVMe or in
  `voybak`. See "Research findings" below — completely unanalyzed as of
  this writing.
- `voynich_mysql_backups/` — 5 gzipped `mysqldump` files, 49M, nightly
  cron output from `scripts/mysql-backup.sh`, 14-day retention

## Infrastructure

- `docker-compose.yml` + `.env.example` — single MySQL 8 container
  (`voynich-mysql`, port 13306, non-default on purpose), option-file
  credentials
- `docker-compose.nas.yml` — NAS-side variant
- `replication/` — GTID master-slave + master-master MySQL topology,
  live-tested mach1↔mach2, not yet consumed by `Config`/`MySqlCatalog`
  (roadmap item)
- `scripts/mysql-backup.sh` — backup script, restore-tested
- **Live services**: `voynich-mysql` running on predator
  (192.168.2.23:13306), actively used by this app; predator also runs a
  nightly NAS backup and hosts an unrelated local-LLM experiment
  (gemma-4-e4b via LM Studio)
- **`predator:/home/walter/voybak/Voynich/`** — a full rsync mirror of
  this project directory (code + gitignored `stolfi/` research data), kept
  on predator's own NVMe. Deliberate second-machine, second-disk backup —
  predator and this machine are both commercial-grade hardware sharing one
  hot office, so a single-machine failure is a real risk being backed up
  against, not a hypothetical. Update with `rsync -av --exclude='target/'
  /home/walter/github/Voynich/ predator:/home/walter/voybak/Voynich/`.
  Freely usable over `ssh`/`scp`/`rsync` for read or write.

## Documentation

- `README.md` — project framing ("Lightroom for generic image
  collections," Voynich as convenient dataset not subject), current-state/
  roadmap, three tested configs
- `CLAUDE.md` — build commands, architecture table, Java style rules
- `replication/README.md` — replication setup walkthrough

## Research findings (not yet in code)

- f1r is a colour-transfer ghost of f1v (copper-green pigment, chroma
  ratio ≈0.24)
- f8r/f17r green pigment identity match (McCrone-consistent)
- Unresolved anomaly: f17r azurite not degrading toward green as expected
- "Demi-duplication": ring-figures that look like copy-paste repeats at a
  glance are often individually distinct on closer inspection (hairstyle,
  cheek color) — load-bearing for not treating figures as interchangeable
  in segmentation work
- Multispectral data (`/usb1/voynich.spec/`, above) exists for f1r only
  and is unanalyzed — a candidate second encoding layer (color as
  structured information, not just decoration) orthogonal to the
  label/figure work, not yet started
