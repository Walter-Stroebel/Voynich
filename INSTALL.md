# Installing the Voynich Cataloging Tool — from zero

This is for someone who has never installed a Java program before and may
not have a terminal open right now. If you already have Java 17 and know
your way around a terminal, skip ahead to [step 2](#2-install-the-app-itself)
— you don't need to build anything from source, a ready-to-run download
exists.

Only one thing needs installing before anything else: **Java 17**. Then
you install the app itself via [MITSA](https://github.com/Walter-Stroebel/mitsa)
(a small cross-platform launcher/updater — no manual jar downloads or
per-OS scripts to babysit), plus the scan images and the companion
viewer, and you're running.

*(Maven/git and building from source are only needed if you want to
modify the code yourself — that path is covered separately, at the end of
this document, and isn't part of the normal path to just running the
app.)*

---

## 1. Install Java 17

You need a **JDK** (Java Development Kit — includes the compiler, not just
the runtime), specifically **version 17**, an LTS (Long-Term Support)
release with years of support life left. Don't grab "whatever's newest"
even though a newer JDK would probably also run this app fine — this
project is only ever built and tested against 17, so a newer major
version is untested territory, not a verified one.

**The confusing part, up front:** searching "download Java" mostly leads
to Oracle's site, which pushes its own commercial JDK build and a login
wall. You don't need that one and don't need an Oracle account. Oracle
owns the Java trademark and language spec, but OpenJDK is the real,
free, fully-compatible open-source implementation, and several
organizations build and distribute it with no licensing catches. Use one
of those instead.

**Recommended: [Eclipse Temurin](https://adoptium.net/)** — pick your OS,
download the **JDK 17 (LTS)** installer, run it.

**Watch out:** Temurin's own site defaults to whatever its newest release
is (25 at the time of writing), not 17 — both the homepage's "Download
Temurin" button and the bare releases page land there. On the
[releases page](https://adoptium.net/temurin/releases/), open the
**Version** dropdown yourself and pick **"JDK 17 - LTS"** before
downloading; don't just take whatever's offered by default.

- **Windows:** download the `.msi`, run it, accept defaults (make sure
  "Set JAVA_HOME" and "Add to PATH" are checked in the installer options —
  they usually are by default).
- **Mac:** download the `.pkg`, run it. Or, if you have
  [Homebrew](https://brew.sh/): `brew install temurin@17`.
- **Linux:** your distro's package manager almost certainly has it —
  e.g. Debian/Ubuntu: `sudo apt install openjdk-17-jdk`; Fedora:
  `sudo dnf install java-17-openjdk-devel`. Otherwise use the Temurin
  installer for your distro from the link above, making sure to pick the
  17 build there too.

**Verify it worked** — open a terminal (Windows: search "Command Prompt"
or "PowerShell"; Mac: "Terminal" app; Linux: your terminal emulator) and
run:

```bash
java -version
```

You want to see `17` in the output, e.g. `openjdk version "17.0.x"` — if
it shows some other major version, you likely have a different JDK
installed or on PATH ahead of Temurin 17; sort that out before
continuing rather than assuming it'll be fine. If you get "command not
found" instead, the installer didn't add Java to your PATH — reinstall
and make sure that option is checked, or search "add Java to PATH
\<your OS\>".

## 2. Install the app itself

Voynich is installed and run through [MITSA](https://github.com/Walter-Stroebel/mitsa).
MITSA handles fetching the right release jar, keeping it updated, and
giving you a plain `voynich` command — no manual jar downloads, no
version numbers to track by hand.

1. Install MITSA itself: follow its own
   [INSTALL.md](https://github.com/Walter-Stroebel/mitsa/blob/main/INSTALL.md)
   (needs the same Java 17 you just installed in step 1 — MITSA doesn't
   bundle its own JVM).
2. Register Voynich:

   ```bash
   mitsa add voynich Walter-Stroebel Voynich 'Voynich-.*-jar-with-dependencies\.jar'
   ```

   This registers the app and fetches the latest release jar. From then
   on, `mitsa run voynich` launches it, and `mitsa update voynich` pulls
   a newer release when one's out — no re-downloading or re-registering.

## 3. Get the scans themselves

This repo doesn't include the manuscript images — you need your own
local folder of them. The easy path works fine; the alternative below is
only worth the extra effort for pixel-level colour work.

**The easy way — just use JPG.** The
[Beinecke digital collection](https://collections.library.yale.edu/catalog/2002046)'s
own "download all" option gives you JPG files from the library's 2004
scan of the whole manuscript, and this app reads them directly — no
conversion needed. In practice these are quite good: minimal compression,
easily good enough for browsing, tracing regions, and everyday work. The
one thing to know is that JPG compression can occasionally introduce a
small artifact right at the pixel level, which matters if you're doing
close quantitative colour analysis (the ΔE Heatmap especially) rather
than just looking at the pages.

**If you want to avoid that** — for careful pixel-level colour work —
Yale's own collections site offers lossless **TIFF** directly, no digging
needed: their public IIIF manifest links a full-resolution TIFF download
for every page, right on the same domain as the JPG viewer (a URL like
`https://collections.library.yale.edu/download/tiff/<canvasId>`, no login
or special access). Verified: same dimensions and same underlying scan as
the JPG, just without the lossy compression (a direct pixel comparison
showed only the expected small JPEG-noise difference, nothing structural
or a different photograph). There's no separate, higher-quality "newer"
scan set beyond this — the TIFF and JPG both trace back to the same one
digitization. Yale doesn't publish PNG directly, so if you want PNG
specifically, convert from the TIFF (or the JPG) yourself — either way
[ImageMagick](https://imagemagick.org/) handles it in one line:

```bash
# from inside your folder of .jpg or .tif files
mkdir png
magick mogrify -format png -path png *.jpg
# or, for TIFFs:
magick mogrify -format png -path png *.tif
```

(Older ImageMagick installs use `convert`/`mogrify` as the command
name directly, without the `magick` prefix — if `magick` isn't found,
try `mogrify -format png -path png *.jpg` instead.)

Whichever format you end up with — JPG straight from Yale, or a
converted PNG/TIFF folder — point `scanPath` (next step) at that
folder.

Scan only catalogs files it can resolve to a page via the bundled
`data/scan-naming.tsv` naming table — any of its known schemes' names
works (Sequential, Yale, VoynichNu). A file whose name matches none of
them is skipped, not catalogued under some improvised identity — so if
your files use a naming scheme this table doesn't yet cover, either add a
column for it (see [MANUAL.md](MANUAL.md#the-main-window)'s Rename to…
coverage, or just extend `data/scan-naming.tsv` directly) or use **File →
Rename to…** to rename the whole folder in place to a scheme it already
knows.

## 4. Get infimg (needed for viewing full-size images)

Several core menu actions — **Open in infimg**, **Two-Page View**,
**Thumbnail Matrix**, and viewing an exported region — hand their result
to a separate companion app, [infimg](https://github.com/Walter-Stroebel/infimg),
rather than opening it in a window of their own. Without it, those menu
items just silently do nothing when clicked. Treat this as a required
step, not an optional extra — especially on Linux, where there's no
platform image viewer this app falls back to instead.

infimg is installed the same MITSA way as Voynich itself — if you've
already installed MITSA in step 2, this is one more registration, no
wrapper scripts to write by hand:

```bash
mitsa add infimg Walter-Stroebel infimg 'infimg-.*-jar-with-dependencies\.jar'
```

This fetches the latest infimg release and writes a `~/bin/infimg`
command (Linux/macOS) whose whole job is to hand off to `mitsa run
infimg` — that path is what you'll point `"infimgJar"` at in the config
step next. `mitsa update infimg` pulls a newer release later; nothing
about this app's own config needs to change when that happens.

## 5. Set up a config file

The app needs to know where your scanned images live, and where to find
the infimg command MITSA just installed. Create a folder called
`.infVoy` in your home directory (on Windows that's usually
`C:\Users\<you>\.infVoy`; on Mac/Linux it's `~/.infVoy`), and inside it a
file named `config.json` containing:

```json
{
  "scanPath": "/path/to/your/folder/of/scans",
  "infimgJar": "/home/you/bin/infimg"
}
```

Replace `scanPath` with wherever your converted PNG scans actually are,
and `infimgJar` with the path MITSA wrote for infimg in step 4 —
`~/bin/infimg` on Linux/Mac (on Windows, the `.bat` shim under
`%APPDATA%\mitsa`, per MITSA's own docs).

(If you skip this step, the app will create the `.infVoy` folder itself
on first run and tell you exactly this, then exit — it won't guess.)

## 6. Run it

```bash
mitsa run voynich
```

(Or just `voynich`, if MITSA's shim directory is on your PATH — same
convenience as any other installed command, no version number or jar
path to remember.)

The main window should open. From here, hand off to
[MANUAL.md](MANUAL.md#the-main-window) for how to actually use the app —
the first thing you'll want is **File → Scan** to populate the catalog
from your scan folder.

---

### If something goes wrong

- **`java: command not found` / `'java' is not recognized`** — Java isn't
  on your PATH. Reinstall and check the PATH option, or search "add to
  PATH" for your OS.
- **`mitsa: command not found`** — MITSA's own shim isn't on your PATH;
  see its [INSTALL.md](https://github.com/Walter-Stroebel/mitsa/blob/main/INSTALL.md)'s
  own troubleshooting.
- **Open in infimg / Two-Page View / Thumbnail Matrix do nothing when
  clicked** — `infimgJar` is missing or wrong in `config.json`. Re-check
  step 4/5 — it should point at the `infimg` command MITSA installed.
- **Everything above worked but some other menu item silently does
  nothing** — see [MANUAL.md's Known
  Limitations](MANUAL.md#known-limitations).

---

## Building from source instead

Only relevant if you want to modify the code yourself, or need a version
newer than the latest tagged release. You'll additionally need **Maven**
and **git**:

- **Maven** — Windows: download the binary zip from
  [maven.apache.org/download.cgi](https://maven.apache.org/download.cgi),
  unzip it somewhere permanent, add its `bin` folder to your PATH. Mac:
  `brew install maven`. Linux: `sudo apt install maven` /
  `sudo dnf install maven`. Verify with `mvn -version`.
- **git** — Windows: [git-scm.com/download/win](https://git-scm.com/download/win).
  Mac: `brew install git` (or just run `git --version`, which often
  triggers an install prompt on a fresh Mac). Linux: `sudo apt install git` /
  `sudo dnf install git`. Verify with `git --version`.

Then, instead of step 2 above:

```bash
git clone https://github.com/Walter-Stroebel/Voynich.git
cd Voynich
mvn package
java -jar target/Voynich-*-jar-with-dependencies.jar
```

(The `*` picks up whatever version `pom.xml` currently declares — no
edit needed here when that number changes. On Windows, `cmd`/PowerShell
don't expand `*` the same way; check `target/` after the build and use
the exact filename instead.)

`mvn package` downloads the project's dependencies (needs an internet
connection, first time only) and compiles everything into one runnable
jar under `target/`. Look for `BUILD SUCCESS` near the end of the output.
If it fails mentioning a Java version, you likely have an older JDK also
installed and it's the one being used — confirm `java -version` says 17+,
and set `JAVA_HOME` explicitly to your Temurin 17 install if you have
more than one JDK.
