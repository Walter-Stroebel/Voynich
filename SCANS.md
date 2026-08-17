# Scan sources and page naming — research notes

This document tracks an open question this project ran into while adding
JPG scan support: which Voynich manuscript scan sources exist, which
format/quality each one offers, and — the harder, still-unresolved part —
what page-naming convention this app should use so that a page's filename
means the same thing to this project, to the wider Voynich research
community, and to [Rene Zandbergen's voynich.nu](https://www.voynich.nu/),
the most established reference site for the manuscript.

This is genuinely unfinished. Sections below are marked accordingly.
Nothing here should be treated as settled until the "Naming convention"
section below says so.

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
  `85v_and_86r_(foldout).png`) one-to-one. See "Open questions" below.

Code that infers a page's recto/verso counterpart (`OverviewPanel.parseFolio`,
used by Two-Page View and folio-order sorting) currently only understands
the project's own `<digits><r|v>.<ext>` naming — it doesn't recognize a
raw torrent filename or Rene's shorthand at all. Fixing that properly
means picking one canonical convention first, not bolting on lookups
ad hoc — see "Open questions" below for why that got paused mid-build.

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

## Open questions — NOT yet resolved

1. **What naming convention should this app actually use?** Three
   candidates exist and none has been confirmed as *the* answer:
   - This project's own IIIF-label-derived naming (already in
     `data/voynich-page-index.json`) — mechanically derived, consistent,
     but not verified against what the research community actually uses.
   - Rene Zandbergen's voynich.nu notation (`f70v1`, `f85v+f86r`, etc.) —
     likely the more "standard" one among researchers, since voynich.nu
     is the field's long-running reference site, but the exact rules
     (and a full table, if one exists) haven't been located yet. A search
     result claimed "René Zandbergen has a table of folio numbers with
     the corresponding Beinecke catalog image number" but the actual
     page/URL wasn't found in the research done so far — check
     [voynich.nu](https://www.voynich.nu/) directly, starting from its
     [folios page](https://www.voynich.nu/folios.html) (a thumbnail
     gallery, not the table) and other pages linked from its front page.
   - Yale's own raw IIIF manifest labels, unprocessed — probably not a
     good final choice (inconsistent punctuation/wording,
     `"70v (part)"` vs `"85v and 86r (foldout)"`), but the ground truth
     everything else is derived from.
2. **Does the manuscript's own foliation have gaps/splits this needs to
   handle regardless of naming convention?** Found while reading
   voynich.nu's gallery: split-page notation (`f67r1`/`f67r2`/`f67v2`/
   `f67v1`, `f68r1`/`f68r2`/`f68r3`) that doesn't correspond to anything
   currently in this project's data — not yet checked whether these are
   present, correctly represented, or silently missing from the current
   213-file PNG working set. Also: quire 2 skips `f12` entirely; quire 8
   jumps from `f57r`–`f58v` straight to `f65r`–`f66v`. These are real
   gaps in the manuscript's own historical foliation, not a scan-quality
   problem — any naming/mapping tool needs to not choke on them.
3. **Once 1–2 are settled**: build the actual mapping from every source
   (2004 torrent JPG's sequential filenames, Yale's canvasId-keyed
   TIFF/JPEG, this project's own PNG set) to whichever convention wins.
4. **Then, and only then**: fix `OverviewPanel.parseFolio` to recognize
   pages scanned from a non-canonically-named source (like the raw
   torrent JPGs) — a design was started and paused mid-build (an embedded
   `data/voynich-page-index.json` lookup inside `parseFolio`) once it
   became clear the naming convention itself wasn't settled yet. A
   simpler shape was also proposed — a one-time rename/convert utility
   (tentatively `CatalogCli yalenames <dir>`) that renames a folder of
   torrent JPGs to canonical names before scanning, so `parseFolio` itself
   never needs to change — but which shape is right depends on question 1
   being answered first.

**Until these are resolved: don't recommend a "torrent JPG rename" tool
to anyone, and don't reference this naming scheme externally** (e.g. when
first reaching out to Rene Zandbergen) — a half-right naming convention
handed to the field's own reference-site maintainer would be actively
confusing, not helpful.

## References

- [Rene Zandbergen's voynich.nu](https://www.voynich.nu/) — the Voynich
  research community's long-standing reference site; likely holds the
  actual canonical naming answer, not yet fully located.
- [Yale's Beinecke digital collection](https://collections.library.yale.edu/catalog/2002046) —
  the manuscript's official public scan viewer/download page.
- [Yale's IIIF manifest](https://collections.library.yale.edu/manifests/2002046) —
  the raw, machine-readable source this project's own
  `data/voynich-page-index.json` and the verification above were built
  from.
- `data/voynich-page-index.json` (`src/main/resources/data/`) — this
  project's own seq↔canvasId↔label↔filename table, built from the IIIF
  manifest above.
