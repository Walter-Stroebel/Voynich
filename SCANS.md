# Scan sources and page naming — research notes

This document tracks what this project ran into while adding JPG scan
support: which Voynich manuscript scan sources exist, which format/quality
each one offers, and how page filenames differ between sources — the 2004
torrent release, this project's own working set, and
[Rene Zandbergen's voynich.nu](https://www.voynich.nu/), the most
established reference site for the manuscript.

**Scope decision: this app does not pick a single "correct" canonical
*display* naming convention.** Whether Rene's `f70v1`-style notation,
this project's own IIIF-derived naming, or Yale's raw manifest labels is
"right" is a question for the Voynich research community to settle among
themselves, not something this app has any business having an opinion
on — any of them can be the active `namingScheme` a user renames their
files to and works with.

That said, the catalog's internal *identity* is canonical, closing a real
gap this project ran into independently — no table ties the manuscript's
various naming schemes to one shared identity — that echoes, without
being identical to, gaps voynich.nu documents in its own transcription
and description pages. See [DATABASE.md](DATABASE.md) for the full design
and rationale.

## Why this matters

Different scan sources name their files differently:

- **The 2004 torrent release** (`/usb1/voynich_jpg/` in this project's
  own data, `001.jpg`–`213.jpg`) names files by raw sequential capture
  order — no relationship to folio numbers at all.
- **This project's own working PNG set** (`voynich_png/`) names files by
  folio (`1r.png`, `1v.png`, …), derived from Yale's IIIF manifest labels
  — see [`data/voynich-page-index.json`](src/main/resources/data/voynich-page-index.json)
  for the seq↔label↔filename mapping this project built.
- **Rene Zandbergen's own site** uses yet another notation for the
  manuscript's irregular pages — `f70v1`, `f102v1`, `f85v+f86r` — which,
  from what little was found so far, does not match this project's
  IIIF-label-derived naming (`70v_(part)_seq127.png`,
  `85v_and_86r_(foldout).png`) one-to-one. Not a problem this app is
  trying to solve (see the scope decision above) — just a fact worth
  knowing if a page number mentioned in Voynich research literature
  doesn't obviously match a filename in this project's own catalog.

Code that infers a page's recto/verso counterpart (`OverviewPanel.parseFolio`,
used by Two-Page View and folio-order sorting) currently only understands
the project's own `<digits><r|v>.<ext>` naming — it doesn't recognize a
raw torrent filename at all. Given the scope decision above, the fix is a
source-specific conversion step (e.g. rename a folder of torrent JPGs to
this project's own folio naming, using the mapping this project already
has), run once before scanning — not a runtime multi-convention lookup
inside `parseFolio` itself. See "What's left to build" below.

## What's confirmed (verified directly, not just claimed)

**There is no separate, better, or hidden scan set beyond the one 2004
Beinecke digitization.** Checked directly against Yale's live IIIF
manifest (`https://collections.library.yale.edu/manifests/2002046`) —
not just a search-engine summary, which turned out to have this wrong:

- Yale offers exactly two formats per page: **JPEG** (via IIIF Image API,
  `https://collections.library.yale.edu/iiif/2/<canvasId>/full/full/0/default.jpg`)
  and **TIFF** ("Full size original" rendering link,
  `https://collections.library.yale.edu/download/tiff/<canvasId>`) — both
  public, no login, no special access, no digging.
- **No PNG format exists anywhere on Yale's side.**
- Downloaded the front-cover TIFF live (`canvasId 1006074`, 2931×3865,
  34MB) and compared it pixel-for-pixel against this project's own
  `voynich_png/Front_cover.png`: **identical, mean absolute error 0.**
  Confirms this project's existing PNG working set already *is* Yale's
  authoritative TIFF, losslessly re-encoded.
- The 2004 torrent JPG for the same page differs from that same TIFF by
  only ~0.32% mean pixel error — textbook JPEG compression noise, not a
  different or lower-quality capture. All three sources (torrent JPG,
  Yale's live TIFF, this project's PNG) trace back to one underlying
  scan.
- No digitization-date metadata is present in Yale's manifest at all — an
  earlier search-engine result claiming a distinct "2014 PNG/JPEG
  digitization" could not be corroborated and appears to be simply wrong
  (possibly conflated with the Lazarus Project's 2014 multispectral
  imaging work, which is real but covers only 10 pages, not the whole
  manuscript — see `CLAUDE.md`'s Vision Pipeline notes for that separate
  thread).

**Bottom line for anyone getting scans**: any of the three sources (2004
torrent JPG, Yale's live TIFF, this project's own PNG set) is the same
underlying photography. Pick based on convenience/format, not on
believing one is a "better scan" than another.

## What's left to build

1. **Done.** File → "Rename to…" (`Voynich.renameScans`, backed by
   `ScanRenamer`/`RenameTaskWindow`) renames files under `scanPath` in
   place between any two naming schemes — columns of the bundled
   `data/scan-naming.tsv` lookup table (`Sequential`, `Yale`, `VoynichNu`
   so far, keyed by a permanent `Id` column that's never a rename target
   itself; add more columns to the TSV and they show up as new menu
   targets automatically, no code change needed). `Config.namingScheme`
   tracks which column the files currently match, updated after a
   successful rename. Real file extension is always preserved,
   independent of naming scheme. Pre-flight collision/blank-target
   detection refuses individual files rather than choking mid-batch.
2. **Done.** The catalog's identity is fully id-canonical — see
   [DATABASE.md](DATABASE.md) for the full design.
3. **Check the manuscript's own foliation for gaps/splits the renamer
   needs to not choke on**, independent of any naming-authority question:
   found while reading voynich.nu's gallery, split-page notation
   (`f67r1`/`f67r2`/`f67v2`/`f67v1`, `f68r1`/`f68r2`/`f68r3`) that doesn't
   correspond to anything currently in this project's own data — not yet
   checked whether these are present, correctly represented, or silently
   missing from the current 213-file PNG working set. Also: quire 2 skips
   `f12` entirely; quire 8 jumps from `f57r`–`f58v` straight to
   `f65r`–`f66v`. These are real gaps/irregularities in the manuscript's
   own historical foliation, not a scan-quality problem, and a renaming
   tool needs to handle them (or clearly skip them) rather than silently
   mis-map a page.
4. **If useful later**: a similar conversion utility for other source
   naming schemes as they come up (e.g. if a user's TIFF set from Yale's
   direct download uses `canvasId`-based filenames) — not needed yet,
   nothing currently blocks on it.

## References

- [DATABASE.md](DATABASE.md) — the id-canonical naming-scheme database
  this project built, and the naming-identity gap it addresses.
- [Rene Zandbergen's voynich.nu](https://www.voynich.nu/) — the Voynich
  research community's long-standing reference site; its own folio
  notation (`f70v1`, `f85v+f86r`, etc.) is where the split-page/foliation
  facts above were found.
- [Yale's Beinecke digital collection](https://collections.library.yale.edu/catalog/2002046) —
  the manuscript's official public scan viewer/download page.
- [Yale's IIIF manifest](https://collections.library.yale.edu/manifests/2002046) —
  the raw, machine-readable source this project's own
  `data/voynich-page-index.json` and the verification above were built
  from.
- `data/voynich-page-index.json` (`src/main/resources/data/`) — this
  project's own seq↔canvasId↔label↔filename table, built from the IIIF
  manifest above.
