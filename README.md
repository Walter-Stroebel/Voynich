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

## Roadmap

In order — each step assumes the ones above it are done, not just that they
compile:

1. **Smoke-test `MySqlCatalog` against a real container.** It compiles but
   has never touched an actual MySQL instance — `docker compose up -d`, fill
   in a real `.env`, run one `recordSighting` round-trip. De-risk this before
   building anything on top of it.
2. **Wire `Catalog` into `Voynich.main`.** Currently nothing opens one or
   records a sighting — the whole persistence layer is unused library code
   until the scan loop calls it. This is what turns it from a library into
   an actual pipeline.
3. **Catalog/browse UI** — a grid view over `thumbnail`, the first real use
   of any of this from a human's perspective instead of just a compiler's.
4. **Sort-by-similarity / duplicate report**, using `ColorImage.distanceTo`
   across the catalog — the original motivating feature.
5. **Deferred, only if measured slow:** precomputed nearest-neighbour /
   duplicate-cluster caching, if an O(n²) `distanceTo` pass over a real
   collection turns out to actually be a bottleneck. Don't build this
   speculatively.
6. **Deferred, nice-to-have:** primary/replica MySQL topology (fast NVMe
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
7. **Minor housekeeping:** `mysql-connector-java:8.0.27` is the legacy
   artifact coordinate (`groupId: mysql`); the maintained one is
   `com.mysql:mysql-connector-j`. Still works, not urgent.
8. Editing operations (crop, exposure, white balance, etc.) — not scoped
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
