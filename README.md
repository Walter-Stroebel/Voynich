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
- A CIELab-thumbnail distance metric (`ColorImage.distanceTo`) — mean per-cell ΔE between two images' thumbnails, resolution-independent, the basis for "is this a copy of that" comparisons across a collection
- A catalog persistence layer (`Catalog`/`CatalogEntry`, with `MySqlCatalog` and `FileCatalog` backends) — one record + thumbnail per filename, keyed by filename rather than path so the same file at two locations (e.g. a NAS copy and a local NVMe copy) is one entry, not two. MySQL is optional (runs via `docker-compose.yml`) and falls back automatically to plain JSON+PNG sidecar files when unconfigured

**Implemented (app level):**
- `MySqlCatalog` smoke-tested against a real container — a live
  `recordSighting`/`loadEntry`/`loadThumbnail` round-trip, not just compiled
  code
- `Catalog` wired into `Voynich.main`: the toolbar's Scan action walks
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
  turning up). Clicking a thumbnail in `OverviewPanel` now opens a modal
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
  over a shuffled queue of every catalog entry — the toolbar's "Wash Review"
  action is one instance of it (`RapidReviewAction`), not a special case.
  Clicking the shown image stages a tag in an editable box; nothing is
  persisted until Done, so a whole pass is reviewable/correctable before any
  of it hits storage
- Manual checkpoint/undo for the whole catalog (`Catalog.checkpoint()`/
  `Catalog.restoreLatestCheckpoint()`) — a coarse, whole-catalog clone
  (a timestamped sibling directory for `FileCatalog`, a `CREATE TABLE ... AS
  SELECT` clone of the `images` table for `MySqlCatalog`), restorable via the
  toolbar's Checkpoint/Undo buttons or `CatalogCli checkpoint`/`restore`. No
  automatic pruning of old checkpoints — deliberate, left for hand cleanup

**Manuscript-specific, not part of the generic library:** `RingDiagramSegmenter`,
a first-pass tool for extracting individual upright figure crops from the
Voynich manuscript's circle/ring diagram pages. Unlike everything above, it
assumes manuscript content and isn't wired into the main GUI — currently
invoked standalone against a hardcoded scan file, not from a toolbar action.

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
3. **Deferred, nice-to-have:** primary/replica MySQL topology (fast NVMe
   primary, NAS replica) if a long-running catalog operation actually
   demands the durability. See "Why Docker + MySQL" below for why this is
   even possible at all. The replication mechanics themselves are built and
   live-tested (`replication/`, both master-slave and master-master) — what's
   still missing is any consumer of it: `Config` carries exactly one
   `db` endpoint, and `MySqlCatalog` has no notion of a second host to fail
   over to. `MySqlCatalog` does retry once through a dead/hiccuped
   connection (real hardware drops connections; that's normal, not a
   failover event), but a primary that's actually down still requires
   editing `db.host` by hand and restarting the app — this item is what
   would make that automatic.
4. **Minor housekeeping:** `mysql-connector-java:8.0.27` is the legacy
   artifact coordinate (`groupId: mysql`); the maintained one is
   `com.mysql:mysql-connector-j`. Still works, not urgent.
5. Editing operations (crop, exposure, white balance, etc.) — not scoped
   yet at all; this project has stayed cataloging/comparison so far, not
   editing.

## Why Docker + MySQL, not SQLite

The obvious default for a single-user desktop catalog is an embedded
file-based DB — SQLite, the same thing Lightroom's own `.lrcat` uses. That
was the first instinct here too, and it's wrong for this project: it's
precedent applied without checking whether the precedent's assumptions
still hold, i.e. cargo-culting.

The actual reasoning: anyone with the skill to run MySQL in a container
already has the answer to "which DB, where, why, how" — that's what running
your own container *is*. Pre-deciding those questions for them with an
embedded default takes away knobs a user at that level already knows how to
turn (placement, sizing, backup, networking) and replaces them with nothing
in return, since Docker removes the actual pain SQLite was invented to
avoid (installing and administering a long-running service by hand).

It also opens a topology SQLite can't: a primary container on fast NVMe for
working speed, and a replica on slower NAS storage purely for durability.
Worth having specifically because some catalog operations here — hashing,
comparing, or re-thumbnailing an entire collection — can run for hours,
days, or weeks; surviving a primary-disk failure mid-run is a real
"disk full at 2am, three days in" scenario, not a hypothetical. That's a
nice-to-have, not a requirement — `docker-compose.yml` and `.env.example` in
this repo are a minimal single-container example to adapt, not a prescribed
topology. `Catalog` doesn't care either way: it just needs one reachable
MySQL endpoint through `Config.db`, and falls back to plain files if none is
configured — see `CLAUDE.md`'s "Catalog persistence" section for the
mechanics.

## Test configurations

Docs and examples below use two placeholder LAN hosts instead of real
machine names — swap in whatever you actually have:

- `mach1` — `192.168.2.12`, the main work machine.
- `mach2` — `192.168.2.23`, a borrowed test machine: treat it as "don't
  break it" — everything on it lives in Docker, so the whole footprint is
  one `docker compose down -v` away from gone.

Config is always by IPv4 address, never hostname/mDNS or IPv6 — there's no
LAN-level problem here IPv6 solves.

Three configurations worth testing against, in order of how much is set up:

1. **File-only, no Docker/MySQL** (`mach1`) — no `db` block at all, so
   `Catalog.open` falls back to `FileCatalog` under `~/.voynich-catalog`.
   ```json
   { "scanPath": "/path/to/scans" }
   ```
2. **MySQL in Docker, non-default port** (`mach1`) — the common case for
   actual use: `docker compose up -d` on the same machine, then point the
   app at it by IP (see `docker-compose.yml`/`.env.example` for why never
   port 3306).
   ```json
   {
     "scanPath": "/path/to/scans",
     "db": { "host": "192.168.2.12", "port": 13306, "database": "voynich", "user": "voynich", "password": "..." }
   }
   ```
3. **Borrowed machine, as a MySQL replica** (`mach2`) — `mach2` runs its own
   MySQL-in-Docker, GTID-replicating from `mach1` (master-slave), optionally
   promoted to master-master. Nothing touches the host outside Docker, so
   "don't break it" is satisfied by containment rather than by installing
   nothing — see `replication/README.md` for the compose files, setup
   scripts, and a live-tested walkthrough (master-slave and master-master
   both verified working between the real `mach1`/`mach2`).
   ```json
   {
     "scanPath": "/path/to/scans",
     "db": { "host": "192.168.2.23", "port": 13307, "database": "voynich", "user": "voynich", "password": "..." }
   }
   ```
   The app just points at whichever node's IP:port — replication is a
   server-to-server concern the JVM client never sees.

## Build and run

See `CLAUDE.md` for build/run commands and the full class-by-class
architecture rundown — kept there as the actively maintained reference so
this file doesn't drift out of sync with it.
