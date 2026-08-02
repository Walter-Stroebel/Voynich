# CLAUDE.md
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Run
All commands run from the repo root (`/home/walter/github/Voynich/`).
```bash
# Build fat jar
mvn package
# Run (fat jar)
java -jar target/Voynich-1.0-jar-with-dependencies.jar [optional-config-file]
# Run via Maven
mvn exec:java
```
There are no tests yet.

## Shell Tooling
This machine always has an up-to-date `locate` database (`updatedb` runs
regularly). Default to `locate <pattern>` for filesystem search instead of
`find`. Only use `find` when `locate` genuinely can't do the job — filtering
by mtime/size/permissions, or a path created since the last `updatedb` run.

## Configuration
On first launch the app writes a template config to stderr and exits with code 2 if `~/.infVoy.json` is missing or `scanPath` is unset. Create the file manually:
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
| `Voynich` | Entry point. Loads config, validates `scanPath`, builds the main `JFrame`. |
| `Config` | Plain POJO serialized to/from `~/.infVoy.json` via `JSON`. Add fields here for new persistent settings. |
| `JSON` | Thin Jackson wrapper. Two `ObjectMapper` instances: `mapper` (pretty/indented) and `liner` (single-line). Always use these rather than creating a new `ObjectMapper`. |
| `EzAction` | `AbstractAction` subclass with optional fluent color/font hints. Call `applyTo(component)` after constructing the button to apply styling. |
| `Catalog` | Persistence contract for the image catalog: one `CatalogEntry` + one thumbnail per filename. `Catalog.open(Config)` picks the backend. |
| `CatalogEntry` | JSON-serializable catalog record, keyed by filename (not path) — see "Catalog persistence" below. |
| `MySqlCatalog` | `Catalog` backed by one MySQL table: `JSON` column for the entry, `MEDIUMBLOB` for the thumbnail. Plain JDBC, no ORM. |
| `FileCatalog` | `Catalog` backed by `<filename>.json` + `<filename>.png` sidecar files under a catalog directory. The fallback when no DB is configured. |

### Catalog persistence
`Catalog.open(config)` picks `MySqlCatalog` when `Config.db` (host/database/user)
is populated, else `FileCatalog` rooted at `~/.voynich-catalog`. Both store the
identical `CatalogEntry` shape — MySQL as a native `JSON` column, files as a
pretty-printed `.json` sidecar — so neither is a second-class citizen.

`CatalogEntry` is keyed by **filename, not path**: the same file often exists
at more than one path (e.g. a NAS copy plus a local NVMe copy kept for read
speed), and those must collapse into one entry with two
`CatalogEntry.Location` entries, not two competing catalog rows. Use
`Catalog.recordSighting` (a default method on `Catalog`, implemented once on
top of `loadEntry`/`save`) to record or update a sighting — don't build entries
by hand and call `save` directly unless you're deliberately overwriting.

MySQL runs via the repo's `docker-compose.yml`; copy `.env.example` to `.env`
(gitignored) and fill in real credentials before `docker compose up -d`. The
same credentials then go in `~/.infVoy.json`'s `db` object — nothing reads
`.env` or the compose file at runtime, the two are just kept in sync by hand.
Leaving `db` unset (or any of `host`/`database`/`user` blank) uses
`FileCatalog` instead; a populated `db` that fails to connect throws rather
than silently falling back, since that means something is actually
misconfigured.

`MYSQL_USER` is deliberately granted `ALL PRIVILEGES ON *.*`, not scoped to
just `MYSQL_DATABASE`. This instance exists solely to serve this one app —
there is no other tenant on it to protect from this user, so a scoped grant
buys no real isolation, only friction (admin/test work constantly needing a
root detour). Don't "fix" this back to a scoped grant out of habit; it would
be reintroducing theater, not closing a hole. Credentials for `docker exec
... mysql`/`mysqldump` on the host running the container come from a mounted
MySQL option file (`MYSQL_CNF_HOST_PATH` in `.env`, chmod 600), not `-p` on
the command line — that one's about keeping the value out of shell
history/session logs, not access control between local processes, which
don't have a boundary here either. See `scripts/mysql-backup.sh`.

### Colour analysis pipeline
Understanding this requires reading `EnhancedColor`, `FloatColor`, `YUV`, and `ColorBase` together — no single file tells the whole story.

- `EnhancedColor` (extends `java.awt.Color`) is the central colour-math class: RGB↔CIELAB↔XYZ↔YUV↔HSB conversions, ΔE distance, blending, gamut checks. Most colour operations ultimately call into its static `getCIELAB`/`fromCIELAB`/`getXYZ` methods, which are pure math (no caching) and relatively expensive (several `Math.pow` calls per pixel).
- `FloatColor` is a separate, lighter float[]-based RGBA representation used for spectrum generation (`spectrum`, `binSpectrum`) and premultiplied-alpha blending math. Converts to `EnhancedColor` via `getColor()`.
- `YUV` is a simple Y/U/V value type with its own distance/compare, independent of the CIELAB path.
- `ColorBase` exists purely to make `EnhancedColor`'s CIELAB math affordable at per-pixel/per-image volume. It keeps a two-level cache (per-instance + static cross-instance) of RGB↔CIELAB conversions keyed by `TriElm`, a top-level `short[3]` triple type reusable outside `ColorBase`. `ColorBase.TriLabColor` (nested — its constructor is intrinsically tied to `ColorBase`'s cache internals) is the cache's value type.
- `ColorImage` (top-level, composes a `ColorBase`) is the actual entry point for image analysis: reads a file, runs every pixel through the cache, and builds a `TriLabColor`-indexed colour inventory (`labIndex`) for nearest-neighbour/merge work. `TriElm`/`TriLabColor` deliberately have no `equals`/`hashCode` — they're only ever used as `TreeMap` keys via `compareTo`; do not put them in a `HashMap`/`HashSet` without adding those first.

## Java Style — Non-Negotiable

### What Java Is
Java is a mature, complete, high-performance language on a JIT JVM at roughly 2x C performance. It is not a slow legacy system. Maturity is a feature. Stability is a feature. Write it with confidence in what it is.

### Language Idiom
Prefer explicit, named, Object-contract-respecting Java. Java's object model is built on explicit construction, named types, and the `Object` contract (`equals`, `hashCode`, `toString`). Write to that model.

Prefer explicit iteration and named classes over anonymous dispatch. When the reader sees `->` they must resolve a functional interface in their head — work the IDE was doing, now transferred to the human reader permanently. Same lines of code, less readable, degraded stack traces.

Streams beyond a trivial filter-and-collect chain carry the same cost: a pipeline that looked clever becomes an archaeology problem six months later.

Records automate the `Object` contract rather than fulfilling it — two ways to express a class with no principle distinguishing when to use which. Fulfill the contract explicitly.

None of this is a prohibition — the IDE saves the typing either way, so brevity is not the argument. Readability and debuggability are.

### Threading
Normal hardware has 2–16 cores. Single-threaded Java is a special case requiring justification. Design with `ExecutorService`, `SwingWorker`, or structured concurrency (JDK 21+) from the start.

### Multi-Monitor
Users have 0 to N monitors. Reason about `GraphicsEnvironment` and `GraphicsDevice`. Window placement and screen-awareness are first-class concerns, not afterthoughts.

### UI
Swing is the UI toolkit. Complete, stable, in the JDK, forty years of production evidence. Do not reach for JavaFX — it was never finished, the WebView is a frozen WebKit fossil, and its trajectory is driven by Oracle's attention span.

### Frameworks
Spring is not Java. Spring replaces explicit object construction with annotation magic requiring the full framework runtime. Java has constructors, factories, and composition — use them. A container is an explicit architectural decision, not a default.

### Dependencies
Reach for the jar ecosystem when the problem has genuine complexity that warrants it. Not to solve trivial problems the language handles natively. Every dependency is a transitive closure of decisions you didn't make, vulnerabilities you didn't audit, upgrade cycles you now own. That cost must justify itself.

### Javadoc
Readers (human or LLM) are expected to read and understand the code — Javadoc is not a substitute for that. Document what can't be recovered by reading: a class's role/lifecycle, a public static field's purpose and who owns mutating it, non-obvious persistence or contracts. Don't document getters/setters or anything whose purpose is already stated by its name plus its immediate surrounding context (fluent builder methods, a class doc that already covers a field's intent). No handholding, no guessing "what could this be for" — document the border, not both sides of it.
