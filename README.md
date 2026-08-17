# Voynich

> **Disclaimer:** AI was used extensively as grunt labor for text processing
> in this project. No unsupervised AI code should be present.

![Main window: thumbnail overview grid, folio order](docs/screenshots/overview_folio_order_readme.png)

*What the main window looks like once populated — not what you'll see on
first launch. On a fresh install the catalog starts empty; run File → Scan
against your own scan folder first. See [Install & first
run](MANUAL.md#install--first-run).*

A general-purpose toolkit for analysing and browsing large collections of
images — decode at scale, generate consistent thumbnails, catalog by visual
similarity, find duplicate/near-duplicate copies across a collection. The
Voynich manuscript scans are the working dataset, not the subject: a large,
freely available, high-resolution public-domain image collection with no
licensing complications, a convenient stand-in for "some arbitrary massive
public source of images." Nothing in the codebase assumes manuscript
content.

Think Lightroom, but for a research corpus instead of a photo library:
import and decode a directory of images at scale, catalog and search by
visual similarity, trace and annotate regions of interest by hand, and run
quantitative colour analysis over a page or a region.

## Getting started

- **New to Java/Maven, or don't have the scans yet?** → [INSTALL.md](INSTALL.md)
  (also covers building the required companion viewer,
  [infimg](https://github.com/Walter-Stroebel/infimg) — several core menu
  actions depend on it, especially on Linux where there's no fallback)
- **Already set up, just want to build and run?** → [MANUAL.md](MANUAL.md#install--first-run)
- **Using the app** — cataloging, region tracing, colour analysis, vision
  queries, multi-page views, CLI — → [MANUAL.md](MANUAL.md)
- **Architecture, class-by-class rundown, build details** → [CLAUDE.md](CLAUDE.md)

## Current state

Early. Cataloging, thumbnailing, per-image colour analysis, region tracing,
and local vision-model queries are implemented and in daily use. Similarity
search / duplicate detection across the whole collection — the original
motivating feature — hasn't been built yet; see [CLAUDE.md](CLAUDE.md) for
the full state and roadmap.

## Why plain files, not a DB

The catalog started on MySQL, on the reasoning that anyone running their
own container already has the "which DB, where, why, how" answers a
single-user desktop tool shouldn't pre-decide for them. Retired
2026-08-06 once inlined base64 thumbnails and single-zip checkpoints made
a second stateful service stop paying for itself — see
[CLAUDE.md](CLAUDE.md) for the mechanics. `replication/` is unrelated,
predates this decision, and remains as generic MySQL replication work
unconnected to the catalog.
