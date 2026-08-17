# Installing the Voynich Cataloging Tool — from zero

This is for someone who has never built a Java project before and may not
have a terminal open right now. If you already have Java 17, Maven, and
git installed and know how to use them, skip this and go straight to
[MANUAL.md](MANUAL.md#install--first-run).

Three things need installing before anything else: **Java 17**, **Maven**,
and **git**. Then you clone the repo, build it, and run it.

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

## 2. Install Maven

Maven is the build tool this project uses — it downloads the project's
dependencies and compiles/packages the app.

- **Windows:** download the binary zip from
  [maven.apache.org/download.cgi](https://maven.apache.org/download.cgi),
  unzip it somewhere permanent (e.g. `C:\Program Files\Maven`), then add
  its `bin` folder to your PATH (search "edit environment variables
  Windows" if you haven't done this before).
- **Mac:** `brew install maven` if you have Homebrew, otherwise the
  manual zip method above.
- **Linux:** `sudo apt install maven` (Debian/Ubuntu) or
  `sudo dnf install maven` (Fedora).

**Verify:**

```bash
mvn -version
```

Should print a Maven version and, underneath it, confirm it's using the
Java 17 you just installed.

## 3. Install git

Needed to download ("clone") the project's source code.

- **Windows:** [git-scm.com/download/win](https://git-scm.com/download/win),
  run the installer, accept defaults.
- **Mac:** `brew install git`, or just run `git --version` in a terminal —
  on a fresh Mac this alone often triggers an install prompt.
- **Linux:** `sudo apt install git` / `sudo dnf install git`.

**Verify:**

```bash
git --version
```

## 4. Get the source code

This project doesn't have a public repository URL yet — you'll have been
given the code directly (a zip, a folder copy, a private repo link).
If it's a git repository someone shared with you:

```bash
git clone <the-url-you-were-given>
cd Voynich
```

If you just have a folder/zip, unzip it and `cd` into it in your
terminal.

## 5. Build it

Still inside the `Voynich` folder:

```bash
mvn package
```

This downloads the project's dependencies (needs an internet connection,
first time only) and compiles everything into one runnable jar file. It
can take a minute or two the first time. You'll see a lot of scrolling
output; look for `BUILD SUCCESS` near the end.

## 6. Get the scans themselves

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

## 7. Build infimg (needed for viewing full-size images)

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

(If you'd rather build it yourself from source instead — e.g. to test an
unreleased change — `git clone https://github.com/Walter-Stroebel/infimg.git`
then `mvn package` inside it works the same way `mvn package` did for this
repo, producing the same jar under `target/`.)

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

## 8. Set up a config file

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
and `infimgJar` with the wrapper script's path from step 7 (on Windows,
the `.bat` file's path).

(If you skip this step, the app will create the `.infVoy` folder itself
on first run and tell you exactly this, then exit — it won't guess.)

## 9. Run it

```bash
java -jar target/Voynich-1.0-jar-with-dependencies.jar
```

The main window should open. From here, hand off to
[MANUAL.md](MANUAL.md#the-main-window) for how to actually use the app —
the first thing you'll want is **File → Scan** to populate the catalog
from your scan folder.

---

### If something goes wrong

- **`java: command not found` / `'java' is not recognized`** — Java isn't
  on your PATH. Reinstall and check the PATH option, or search "add to
  PATH" for your OS.
- **`mvn` not found** — same issue, for Maven.
- **`BUILD FAILURE` mentioning a Java version** — you likely have an
  older Java also installed and it's the one being used. Run
  `java -version` and confirm it says 17+; if you have multiple JDKs
  installed, you may need to set `JAVA_HOME` explicitly to the Temurin
  17 install path.
- **Open in infimg / Two-Page View / Thumbnail Matrix do nothing when
  clicked** — `infimgJar` is missing or wrong in `config.json`, or the
  wrapper script isn't executable (`chmod +x` on Linux/Mac). Re-check
  step 7.
- **Everything above worked but some other menu item silently does
  nothing** — see [MANUAL.md's Known
  Limitations](MANUAL.md#known-limitations).
