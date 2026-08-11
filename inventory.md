# Inventory

Two things live in this file: a **function matrix** (every user-facing
function, where it's reachable, and exactly where it lives in the code —
the gap-detection tool, re-derive by re-auditing the code rather than
hand-editing when it goes stale) and a snapshot of non-code assets (data,
`/usb1`, infra, research findings) that aren't tracked anywhere else. See
`CLAUDE.md` for the actively-kept class rundown and `README.md` for
current state/roadmap.

## Function matrix

Audited 2026-08-09, rows added through 2026-08-10. "GUI"/"CLI" are checkmarked when that path exists;
"Location" is the class (and specific control) that implements it.

| # | Function | GUI | CLI | Location |
|---|---|---|---|---|
| 1 | Scan folder / catalog new+changed images | ✅ Scan button | ❌ | `Voynich` toolbar → `ScanTaskWindow` |
| 2 | Sort thumbnail grid | ✅ Sort button | ❌ | `OverviewPanel.sort()` |
| 3 | Filter/search entries (JSON substring, invert) | ✅ Filter button | ✅ `list [filter] [-v]` | `OverviewPanel.filter()` / `CatalogCli.list()` |
| 4 | Toggle "content area only" dim view | ✅ toggle button | ❌ | `Voynich` toolbar toggle → `OverviewPanel` |
| 5 | Rapid-review / MarkUp tagging pass | ✅ MarkUp button | ❌ | `Voynich` toolbar → `CatalogEntryEditor.review()` + `RapidReviewAction` |
| 6 | Open single entry editor | ✅ click thumbnail | ❌ | `OverviewPanel` → `CatalogEntryEditor.edit()` |
| 7 | View raw entry JSON | ✅ JSON box in editor | ✅ `get <filename>` | `CatalogEntryEditor` / `CatalogCli.get()` |
| 8 | Edit/replace raw entry JSON | ✅ JSON box Save | ✅ `save <filename> [jsonFile]` | `CatalogEntryEditor` / `CatalogCli.save()` |
| 9 | Add/edit free-text tags | ✅ Tags box | ✅ `tag <filename> <text>` | `CatalogEntryEditor` / `CatalogCli.tag()` |
| 10 | View Color Frequency chart | ✅ button in editor | ❌ | `CatalogEntryEditor` → `FrequencyBarChart` (via `ViewFrame`) |
| 11 | View ΔE Heatmap | ✅ button in editor | ❌ | `CatalogEntryEditor` → `DeltaEHeatmap` (via `ViewFrame`) |
| 12 | Extract single pixel color (rgb/lab/hex) | ❌ | ✅ `extract --pixel` | `CatalogCli.extract()` |
| 13 | Extract raw region pixel block | ❌ | ✅ `extract --region` | `CatalogCli.extract()` |
| 14 | Select which region is active (whole page / region N) | ✅ Region combo | ❌ | `CatalogEntryEditor.regionSelector` |
| 15 | Open Region Manager (list regions) | ✅ Regions… button | ❌ | `CatalogEntryEditor` → `RegionManagerDialog` |
| 16 | Add new top-level region | ✅ Add Region | ❌ | `RegionManagerDialog` → `ContentAreaEditor` |
| 17 | Add nested child region | ✅ Add Child | ❌ | `RegionManagerDialog` / `RegionViewer` → `ContentAreaEditor` |
| 18 | Trace/re-trace a region's polygon | ✅ Trace / canvas | ❌ (human-only by design) | `ContentAreaEditor` / `ContentAreaCanvas` |
| 19 | Rename region kind/author | ✅ Rename | ❌ | `RegionManagerDialog` → `KindAuthorPrompt` |
| 20 | Reorder regions (Up/Down, promote to "main") | ✅ Up/Down | ❌ | `RegionManagerDialog` |
| 21 | Delete a region | ✅ Delete | ❌ | `RegionManagerDialog` |
| 22 | View a region at scale | ✅ View → RegionViewer | ❌ | `RegionManagerDialog` → `RegionViewer` |
| 23 | Rotate a region's upright preview angle | ✅ mouse wheel | ❌ | `ContentAreaEditor`/`ContentAreaCanvas`, `RegionViewer` |
| 24 | Export region cropped+rotated to PNG file | ✅ Export… button | ✅ `extract --content-area`/`--region-name` | `RegionViewer.exportToFile()` (added 2026-08-09) / `CatalogCli.extract()`; shared raster bake via `BitSet2D.rotateUpright()` |
| 24b | Copy region cropped+rotated to system clipboard | ✅ Copy to Clipboard button | ❌ | `RegionViewer.copyToClipboard()` (added 2026-08-09) |
| 24c | Save region cropped+rotated to `/tmp` and open in a detached viewer | ✅ Save to /tmp & View button | ✅ `extract --content-area`/`--region-name --view` | `RegionViewer.saveToTmpAndView()` / `CatalogCli.extract()`, both via `Voynich.launchImageView()` (added 2026-08-10) |
| 25 | Take a whole-catalog checkpoint | ✅ Storage → Take Checkpoint Now | ✅ `checkpoint` | `StorageDialog` / `CatalogCli` |
| 26 | Restore latest checkpoint | ✅ Storage → Restore Selected | ✅ `restore` | `StorageDialog` / `CatalogCli` |
| 27 | Delete a checkpoint | ✅ Storage → Delete Selected | ❌ | `StorageDialog` |
| 28 | List/browse available checkpoints | ✅ Storage dialog list | ❌ | `StorageDialog` |
| 29 | Exit app | ✅ Exit button | n/a (process just ends) | `Voynich` toolbar |
| 30 | Smoke-test startup | ✅ verifies main JFrame builds+paints | ✅ `--smokeTest` flag (Voynich main, not CatalogCli) | `Voynich.main()` |
| 31 | Override config file path | ✅ n/a — positional arg to `Voynich` jar launch | ✅ `--config`/`-c <path>` (added 2026-08-09) | `Voynich.main()` / `CatalogCli.main()` |

**Confirmed intentional asymmetries** (not gaps, checked 2026-08-09):
- #10/11 (Color Frequency / ΔE Heatmap) have no CLI equivalent, even though
  `extract --pixel`/`--region` produce the same underlying Lab data — no
  scripting need identified yet for the aggregate view itself.
- #14–23 (region management, tracing) are GUI-only by design — tracing is
  pure human pixel judgment, never automatable.
- #27/28 (checkpoint delete/list) have no CLI equivalent — only take/restore
  latest are scriptable; enumerating/pruning checkpoints is a GUI-only task
  so far.
- A full audit (2026-08-09) of `CatalogEntry`/`Config` fields and every
  constructed `JButton`/`EzAction` in the source tree found no functionality
  that exists in code but has zero user-facing access path — everything
  either has a dedicated widget/command or is reachable through the raw-JSON
  box (`CatalogEntryEditor`) / `get`+`save` (`CatalogCli`).

## Data

- **210 PNG scans**, 3.8GB, at `/home/walter/voynich_png` (this machine's
  configured `scanPath`)
- `data/voynich-page-index.json` — Yale Beinecke IIIF manifest mapping
  torrent-numbered JPGs (001–213) to canonical folio labels
- `~/.infVoy/catalog` — the live catalog (213 entries); one
  `<filename>.json` per entry, thumbnail inlined as base64
- `~/.infVoy/catalog-checkpoints/` — manual checkpoints, one
  `<epoch-millis>.zip` each, never auto-pruned
- `src/main/resources/stolfi/` (gitignored — third-party sourced + one
  session's scratch analysis, not an app deliverable):
  - `LSI_ivtff_0d.txt` (1.7M) — Landini-Stolfi Interlinear transcription
    v1.6e6
  - `voynichese_data/` + `.zip` — 225 XML files, per-folio word bounding
    boxes
  - `voynich_labels.json` / `voynich_labels_spatial.json` — 988 unique
    labels across 51 folios, joining EVT transcription with voynichese.com
    coordinates, plus fitted-circle rotation geometry for circle-diagram
    folios
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
  provenance/calibration) — exists **only** on the NAS, not on predator's
  NVMe or in `voybak`. Completely unanalyzed.
- `voynich_mysql_backups/` — 5 gzipped `mysqldump` files, 49M; frozen as of
  2026-08-06 when `MySqlCatalog`/`scripts/mysql-backup.sh` were retired, not
  actively cleaned up

## Infrastructure

- `replication/` — GTID master-slave + master-master MySQL topology,
  live-tested mach1↔mach2; always `Catalog`-independent — see `README.md`'s
  "Why plain files, not a DB"
- predator also runs a nightly NAS backup (feeding the now-frozen
  `voynich_mysql_backups/` above) and hosts an unrelated local-LLM
  experiment (gemma-4-e4b via LM Studio)
- **`predator:~/github/Voynich/`** — full rsync mirror of this project
  directory (code + gitignored `stolfi/` research data), kept on
  predator's own NVMe. Deliberate second-machine, second-disk backup for a
  repo with no GitHub remote yet. Update it,
  plus memory/catalog/checkpoints, in one call via `scripts/sync-predator.sh`
  (gitignored, agent convenience). Freely usable over `ssh`/`scp`/`rsync`
  for read or write. The sibling `infimg` repo was mirrored here too for
  one day after extraction (2026-08-10) but dropped from the script the
  same day once it had its own GitHub remote and tagged releases — GitHub
  is that repo's backup now, so `predator:~/github/infimg/` no longer
  exists (deleted 2026-08-10, see `reference_predator_machine.md`).

## Documentation

- `README.md` — project framing ("Lightroom for generic image
  collections," Voynich as convenient dataset not subject), current-state/
  roadmap
- `CLAUDE.md` — build commands, architecture table, Java style rules
- `replication/README.md` — replication setup walkthrough

## Sibling project: infimg

The `~/Documents/imageview-project.md` idea (parked 2026-08-09) was built
2026-08-10 as `nl.infcomtec.voynich.ImageView` inside this repo first, then
extracted the same day into its own standalone repo,
[github.com/Walter-Stroebel/infimg](https://github.com/Walter-Stroebel/infimg)
(package `nl.infcomtec.infimg`, MIT-licensed, GitHub Actions build-on-push
plus tag-triggered release). No Voynich dependency in the extracted copy.
The forked `ImageView.java` copy inside this repo was removed the same
day — keeping two copies in sync was already a smell one day in.

**Now the standard "show the user an image" tool, not just a Voynich
utility.** Currently released at **v1.2.0**: fit-to-window on load,
mouse-wheel zoom, toolbar-toggle free-angle rotate, drag-pan, exact-view
Save/Copy (zoom/rotation/pan/crop baked in), Load/Paste from file or
clipboard, **10 remembered window-position slots** (`-0`..`-9`, launch
flag), a **Menu** button holding Look & Feel (system default or FlatLaf
Light/Dark/IntelliJ/Darcula), Lighter/Darker/More Contrast/Less Contrast
(one-click CIELAB L* nudges), and an optional ImageMagick-backed Metadata
viewer. Full changelog in the infimg repo's own `README.md`.

Voynich launches the standalone jar directly as a detached process
(`Voynich.launchImageView(File)`, path from `Config.infimgJar` — a
dev-machine setting, deliberately not defaulted), never sharing this app's
EDT. Wired into `RegionViewer`'s "Save to /tmp & View" button and
`CatalogCli extract --content-area`/`--region-name --view` (see
function-matrix row 24c). Outside the Voynich app itself, launch directly
via `~/bin/infimg <path>` — this is now the default way to show Walter any
image produced during a session (scratch probes, crops, diagrams), not
just an in-app feature.

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
- CIELAB a* channel genuinely separates verso bleed-through pigment (e.g.
  the faint star visible behind a leaf on a recto page) from plain vellum
  grain — confirmed real color science, not scan artifact. But automated
  isolation doesn't hold up past that: a "mark one sample, match by
  statistics" classifier also fires broadly on real ink-stroke/pigment-fill
  edges (antialiased blend pixels), which is structural to how the ink
  sits on the page, not a threshold-tuning problem. Investigated and
  deliberately closed 2026-08-11 (see
  `memory/project_vellum_noise_filter_attempt.md`) — human judgment stays
  the only reliable separator, consistent with tracing already being
  human-only by design.
