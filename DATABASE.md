# There is a database

Rene Zandbergen's [voynich.nu](https://www.voynich.nu/) — the research
community's long-standing reference site — documents a real, related gap
in its own transcription page: "There is no place where they are all
collected together," referring to the various transliteration files
different researchers have produced over the years, scattered across
locations in incompatible formats with no central collection point. Its
description page separately notes that even the Beinecke library itself
"does not propose a method for identifying individual pages (or panels)
that are part of the foldouts," leaving the foldout-panel identification
question to a de facto convention voynich.nu adopts on its own initiative.

Neither is exactly "there is no database for page identity" — that
specific framing is this project's own characterization, not a quote —
but both point at the same underlying gap this project ran into
independently while building a catalog: no single place ties the
manuscript's competing naming schemes (the 2004 torrent release's raw
sequential numbering, Yale's own IIIF-manifest labels, Rene's own
`f70v1`-style notation for the manuscript's irregular pages) to one
shared identity, with no guarantee any two of them even agree on how many
pages there are.

This project built that table. It is not a big table — 213 rows, a
handful of columns — but it is the thing that was missing, and once it
exists, a real amount of software becomes possible that wasn't before:
identity-stable cataloging, safe renaming between naming schemes,
metadata export that survives being handed to someone with a completely
different copy of the scans. This document is the write-up of that
database and the design decisions behind it, kept separate from
`SCANS.md` (which is about where the scan *pixels* came from — a related
but distinct, and comparatively anecdotal, question) and from `CLAUDE.md`
(which documents the codebase for a developer, not the underlying
identity problem for a reader who cares about the manuscript itself).

## The problem

Every page of the manuscript has been named more than once, by more than
one party, for more than one reason:

- The **2004 torrent release** numbers files by raw capture sequence
  (`001.jpg`–`213.jpg`) — no relationship to folio numbers at all.
- **Yale's own digitization** (the authoritative source; see `SCANS.md`
  for the pixel-level verification that every other copy in circulation
  traces back to this one) labels pages via its IIIF manifest, close to
  but not identical to standard foliation (`1r.png`, `1v.png`, …,
  `100v_and_101r.png` for composite/foldout scans).
- **Rene's own site** uses yet another notation for the manuscript's
  irregular pages (`f70v1`, `f102v1`, `f85v+f86r`), which does not map
  one-to-one onto Yale's own IIIF-derived naming.

None of these schemes is "wrong" — each was built for a real purpose by
a real party. The actual gap is that nothing says which page under one
scheme is the same physical page as which page under another. Without
that, two researchers citing the "same" page by different names have no
way to mechanically confirm they mean the same thing, and no piece of
software can reliably answer "is this the file I already have, just
renamed" versus "is this actually a different page."

## The database

`data/scan-naming.tsv`, bundled with this project (`src/main/resources/data/`),
is that table: one row per physical page, currently 213 rows, an `Id`
column (a permanent integer, 1–213, dense, arbitrary and internal — it
carries no meaning of its own beyond "this row") plus one column per
naming scheme (`Sequential`, `Yale`, `VoynichNu` so far). Every other
column's value for a given row is that scheme's name for the same
physical page the `Id` identifies.

**The `Id` column, not any naming scheme's own values, is this project's
canonical identity for a page.** `CatalogEntry` (the in-app record for
one cataloged scan) stores nothing but that id — no display filename, no
cross-reference to any particular naming scheme — because a stored name
is a cache that goes stale the moment a file is renamed or a second
naming scheme enters the picture, and a cache that can silently disagree
with the source of truth is worse than no cache at all. Every name shown
anywhere in the app (a thumbnail's label, a CLI listing, an exported
filename suggestion) is resolved fresh from the table, every time, via
`ScanRenamer.displayName(id, scheme)` — never read back from a stored
field. Renaming a whole scan folder from one scheme to another
(`Voynich`'s File → "Rename to…") is therefore just a filesystem
operation plus one lookup per file; the catalog itself never needs to
"know about" a rename, because it never cached the old name to begin
with.

**"We do not deal with files that cannot be id-ed by the table."** A file
scanned into the catalog that doesn't match any column's value for any
row is skipped and logged, not silently admitted under a freshly
invented identity. This is deliberate, not an oversight: an id minted
outside the table would have no home in any naming scheme, defeating the
table's entire purpose the moment it happened. If a genuinely new page
needs to enter the catalog — an errata sheet, a newly digitized flyleaf —
the table itself is amended first (add a row), and only then does the
file resolve.

## Table integrity

A table like this is only as trustworthy as its own internal
consistency, and a hand-edited TSV is exactly the kind of place a typo
creeps in unnoticed. Two checks run on every load
(`ScanRenamer.load()`), both hard failures — the app refuses to start
rather than run against a table it can't fully trust:

- **No duplicate value within a column.** If two rows both claimed the
  same name under the same scheme, name→id resolution would become
  ambiguous — silently returning whichever row happened to be scanned
  first, rather than failing loudly. The loader rejects this outright,
  naming the offending column and value.
- **No genuinely blank cell.** A naming scheme that never named a
  particular page (Rene's site, for instance, has no occasion to name a
  plain cover or flyleaf) doesn't need that cell hand-filled with a
  placeholder — the loader fills it automatically with the page's own
  zero-padded id (e.g. `"046"`), so every column is complete by
  construction, and a new column can be added with only the rows that
  scheme actually names.

## Extending the table

Adding support for a naming scheme this project doesn't yet know about —
someone's own coding system, another researcher's site, a future
digitization's own labels — is exactly as hard as adding a column to a
spreadsheet: fill in the names that scheme has, leave the rest blank, and
the two checks above either accept it or say precisely why not. No code
change is required; every naming-scheme-aware feature in the app (Rename
to…, the Sort menu's "Name (current scheme)" mode, CLI display names)
picks up a new column automatically.

## What this unlocks

- **Identity-stable cataloging.** A page's catalog record — its traced
  content regions, tags, notes — survives a rename between naming
  schemes untouched, because nothing about it was ever keyed by name.
- **Safe batch renaming** between any two schemes in the table
  (`Voynich`'s File → "Rename to…"), with pre-flight collision detection
  so a bad table or an unexpected filename collision refuses individual
  files rather than corrupting a batch partway through.
- **Metadata export that means something to a stranger.** `CatalogCli
  export` / the GUI's File → Export… write out one page's traced regions
  and tags keyed by `id` alone — never a filename, which would only be
  meaningful to whoever has an identically-named copy of the scans.
  Anyone else with their own copy of the same 213 pages and this same
  `data/scan-naming.tsv` can resolve that id back to whatever name they
  actually use. This is also the reason import (accepting someone else's
  exported metadata back into a catalog) is viable at all: two catalogs
  only need to agree on the table, never on a filename.

## What this does not solve

The table can still be *wrong* — a row could point at the wrong physical
page, a transcription error from whoever built it. The duplicate-value
and complete-column checks catch internal inconsistency, not correctness
against the actual manuscript; that remains a human judgment call, same
as any other transcription work. Nor does this settle which naming
scheme is "correct" for the manuscript at large — see `SCANS.md`'s own
scope decision — the table is deliberately naming-scheme-agnostic; it
only insists that whichever names exist, they resolve to one shared,
checkable identity underneath.
