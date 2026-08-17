# Installing the Voynich Cataloging Tool — from zero

This is for someone who has never installed a Java program before and may
not have a terminal open right now. If you already have Java 17 and know
your way around a terminal, skip ahead to [step 2](#2-download-the-app-itself)
— you don't need to build anything from source, a ready-to-run download
exists.

Only one thing needs installing before anything else: **Java 17**. Then
you download the app itself (already built, no compiling needed), the
scan images, and the companion viewer, and you're running.

*(Maven/git and building from source are only needed if you want to
modify the code yourself — that path is covered separately, at the end of
this document, and isn't part of the normal path to just running the
app.)*

---

## 1. Install Java 17

You need a **JDK** (Java Development Kit — includes the compiler, not just
the runtime), version 17 or newer.

**The confusing part, up front:** searching "download Java" mostly leads
to Oracle's site, which pushes its own commercial JDK build and a login
wall. You don't need that one and don't need an Oracle account. Oracle
owns the Java trademark and language spec, but OpenJDK is the real,
free, fully-compatible open-source implementation, and several
organizations build and distribute it with no licensing catches. Use one
of those instead.

**Recommended: [Eclipse Temurin](https://adoptium.net/)** — pick your OS,
download the **JDK 17 (LTS)** installer, run it.

- **Windows:** download the `.msi`, run it, accept defaults (make sure
  "Set JAVA_HOME" and "Add to PATH" are checked in the installer options —
  they usually are by default).
- **Mac:** download the `.pkg`, run it. Or, if you have
  [Homebrew](https://brew.sh/): `brew install temurin@17`.
- **Linux:** your distro's package manager almost certainly has it —
  e.g. Debian/Ubuntu: `sudo apt install openjdk-17-jdk`; Fedora:
  `sudo dnf install java-17-openjdk-devel`. Otherwise use the Temurin
  installer for your distro from the link above.

**Verify it worked** — open a terminal (Windows: search "Command Prompt"
or "PowerShell"; Mac: "Terminal" app; Linux: your terminal emulator) and
run:

```bash
java -version
```

You want to see `17` (or higher) in the output, e.g.
`openjdk version "17.0.x"`. If you get "command not found", the installer
didn't add Java to your PATH — reinstall and make sure that option is
checked, or search "add Java to PATH \<your OS\>".

## 2. Download the app itself

The app is published as a single ready-to-run file (a "jar") — no
building or compiling needed:

1. Go to
   [github.com/Walter-Stroebel/Voynich/releases/latest](https://github.com/Walter-Stroebel/Voynich/releases/latest)
2. Download the one `.jar` file attached to that release (named something
   like `Voynich-1.0-jar-with-dependencies.jar`).
3. Put it somewhere permanent — e.g. a folder called `Voynich` in your
   Documents, or wherever you keep this kind of thing. You'll run it
   directly from there.

## 3. Get the scans themselves

This repo doesn't include the manuscript images — you need your own
local folder of them, and there's a wrinkle: the Beinecke Library's
public distribution is JPG. This app expects **PNG**.

- The default, easiest download from the
  [Beinecke digital collection](https://collections.library.yale.edu/catalog/2002046)
  is JPG — fine to grab as a starting point, but not what `scanPath`
  should point at.
- Higher-quality **TIFF** originals exist and are obtainable, but not
  through the ordinary download button — expect to dig (archived
  torrent releases, direct requests to the library, or similar) to get
  them. TIFF is the best source to convert from if you can get it; JPG
  works too, just with an extra generation of compression already baked
  in.
- Either way, **convert to PNG** before pointing the app at the folder.
  [ImageMagick](https://imagemagick.org/) is the standard tool for this;
  once installed, a one-liner converts a whole directory:

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

Point `scanPath` (next step) at that `png` folder, not the original
JPG/TIFF folder.

## 4. Get infimg (needed for viewing full-size images)

Several core menu actions — **Open in infimg**, **Two-Page View**,
**Thumbnail Matrix**, and viewing an exported region — hand their result
to a separate companion app, [infimg](https://github.com/Walter-Stroebel/infimg),
rather than opening it in a window of their own. Without it, those menu
items just silently do nothing when clicked. Treat this as a required
step, not an optional extra — especially on Linux, where there's no
platform image viewer this app falls back to instead.

infimg has its own public repository with tagged releases — download the
latest one rather than building from source, so you get exactly what was
tested and released, not whatever happens to be on `main` at clone time:

1. Go to
   [github.com/Walter-Stroebel/infimg/releases/latest](https://github.com/Walter-Stroebel/infimg/releases/latest)
2. Download the one `.jar` file attached to that release (named something
   like `infimg-1.5-jar-with-dependencies.jar` — the exact number changes
   with each release, so don't hardcode a version anywhere below).
3. Put it somewhere permanent, e.g. `~/bin/infimg-1.5-jar-with-dependencies.jar`
   (or wherever you keep such things).

Then write a tiny wrapper script so this app can launch it without you
having to update its config every time you download a newer release. On
Linux/Mac, create e.g. `~/bin/infimg` (make sure `~/bin` is on your PATH,
or just pick any folder and use its full path in the config step below)
containing:

```bash
#!/bin/sh
exec java -jar /full/path/to/infimg-*-jar-with-dependencies.jar "$@"
```

(The `*` glob is deliberate — it matches whatever version-numbered
filename you actually downloaded, so dropping in a newer release later is
just replacing the jar file, no edits needed here.)

...then `chmod +x ~/bin/infimg`. On Windows, a `.bat` file with the
equivalent `java -jar ...` line works the same way.

Wrapping it in a script rather than pointing straight at the jar is
deliberate — it means a future infimg version bump doesn't require
touching this app's config again, just replacing the jar the wrapper
points at.
You'll point `"infimgJar"` at this wrapper script's path in the config
step next.

## 5. Set up a config file

The app needs to know where your scanned images live, and where to find
the infimg wrapper script from the previous step. Create a folder called
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
and `infimgJar` with the wrapper script's path from step 4 (on Windows,
the `.bat` file's path).

(If you skip this step, the app will create the `.infVoy` folder itself
on first run and tell you exactly this, then exit — it won't guess.)

## 6. Run it

```bash
java -jar /full/path/to/Voynich-1.0-jar-with-dependencies.jar
```

(Use the actual path where you put the jar in step 2. On Windows you can
also usually just double-click the jar file, though running it from a
terminal like this makes any error message easier to see if something
goes wrong.)

The main window should open. From here, hand off to
[MANUAL.md](MANUAL.md#the-main-window) for how to actually use the app —
the first thing you'll want is **File → Scan** to populate the catalog
from your scan folder.

---

### If something goes wrong

- **`java: command not found` / `'java' is not recognized`** — Java isn't
  on your PATH. Reinstall and check the PATH option, or search "add to
  PATH" for your OS.
- **Open in infimg / Two-Page View / Thumbnail Matrix do nothing when
  clicked** — `infimgJar` is missing or wrong in `config.json`, or the
  wrapper script isn't executable (`chmod +x` on Linux/Mac). Re-check
  step 4.
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
java -jar target/Voynich-1.0-jar-with-dependencies.jar
```

`mvn package` downloads the project's dependencies (needs an internet
connection, first time only) and compiles everything into one runnable
jar under `target/`. Look for `BUILD SUCCESS` near the end of the output.
If it fails mentioning a Java version, you likely have an older JDK also
installed and it's the one being used — confirm `java -version` says 17+,
and set `JAVA_HOME` explicitly to your Temurin 17 install if you have
more than one JDK.
