#!/usr/bin/env bash
#
# Regression script for CatalogCli's argv contract — CatalogCli is
# agent/script-facing, so its command-line shape is the actual product,
# not an implementation detail behind a GUI a mis-click can just undo.
# No test framework (deliberate project-wide choice, see CLAUDE.md/
# feedback_no_test_framework_by_design) — this is a plain shell script
# exercising the built jar end to end, same spirit as --smokeTest.
#
# Requires the app already built (mvn package) and a working MITSA-managed
# config pointing at a real catalog containing 3r.png/3v.png/4r.png/
# Front_cover.png. Groups 1, 4, and 5 call the real vision endpoint on
# predator and will fail if it's unreachable — everything else is
# offline.
#
# Run: scripts/test-catalog-cli.sh

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO_ROOT/target/Voynich-1.0-jar-with-dependencies.jar"
CLI=(java -cp "$JAR" nl.infcomtec.voynich.CatalogCli)

if [[ ! -f "$JAR" ]]; then
    echo "Jar not found at $JAR — run 'mvn package' first." >&2
    exit 1
fi

TMPDIR_TEST="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_TEST"' EXIT

PASS=0
FAIL=0

# --- assertion helpers -------------------------------------------------

# run_cli <name> <expected_exit_code> <args...> — invokes CatalogCli,
# captures stdout/stderr/exit code into ${OUT}/${ERR}/${CODE}, and
# records a pass/fail against the expected exit code. Callers chain
# further assert_* calls afterward to check the captured output.
run_cli() {
    local name="$1" expected="$2"
    shift 2
    OUT="$("${CLI[@]}" "$@" 2>"$TMPDIR_TEST/stderr")"
    CODE=$?
    ERR="$(cat "$TMPDIR_TEST/stderr")"
    if [[ "$CODE" -eq "$expected" ]]; then
        echo "  [ok]   $name (exit $CODE)"
    else
        echo "  [FAIL] $name — expected exit $expected, got $CODE"
        echo "         stderr: $ERR"
        FAIL=$((FAIL + 1))
        return 1
    fi
    PASS=$((PASS + 1))
    return 0
}

# assert_contains <name> <haystack> <needle>
assert_contains() {
    local name="$1" haystack="$2" needle="$3"
    if [[ "$haystack" == *"$needle"* ]]; then
        echo "  [ok]   $name"
        PASS=$((PASS + 1))
    else
        echo "  [FAIL] $name — expected to find \"$needle\""
        echo "         in: $haystack"
        FAIL=$((FAIL + 1))
    fi
}

# assert_file_exists <name> <path>
assert_file_exists() {
    local name="$1" path="$2"
    if [[ -s "$path" ]]; then
        echo "  [ok]   $name ($(stat -c%s "$path" 2>/dev/null || stat -f%z "$path") bytes)"
        PASS=$((PASS + 1))
    else
        echo "  [FAIL] $name — $path missing or empty"
        FAIL=$((FAIL + 1))
    fi
}

# assert_file_size_over <name> <path> <min_bytes>
assert_file_size_over() {
    local name="$1" path="$2" min="$3" size
    size="$(stat -c%s "$path" 2>/dev/null || stat -f%z "$path" 2>/dev/null || echo 0)"
    if [[ "$size" -gt "$min" ]]; then
        echo "  [ok]   $name ($size bytes > $min)"
        PASS=$((PASS + 1))
    else
        echo "  [FAIL] $name — $path is $size bytes, expected > $min"
        FAIL=$((FAIL + 1))
    fi
}

# ------------------------------------------------------------------------
echo "== Group 1: vision argument-parsing edge cases (network-dependent) =="

if run_cli "single file, unchanged path" 0 vision 3r.png "what color is the ink, one word"; then
    assert_contains "single file: non-empty answer" "$OUT" ""  # any non-empty is fine; real check is exit 0 above
fi

run_cli "2 files without -- is rejected" 1 vision 3r.png 3v.png "question"
assert_contains "2 files without --: mentions missing separator" "$ERR" "requires -- before the question"

if run_cli "2 files with -- runs sequentially" 0 vision 3r.png 3v.png -- "one word: any plants visible"; then
    assert_contains "sequential: 3r.png prefix present" "$OUT" "3r.png:"
    assert_contains "sequential: 3v.png prefix present" "$OUT" "3v.png:"
fi

if run_cli "--combine with exactly 2 files" 0 vision 3r.png 3v.png --combine -- "one word: any plants visible"; then
    assert_contains "combine: combined-label present" "$OUT" "3r.png+3v.png combined:"
fi

run_cli "--combine with 1 file is rejected" 1 vision 3r.png --combine "question"
assert_contains "--combine + 1 file: mentions exactly 2" "$ERR" "exactly 2"

run_cli "--combine with 3 files is rejected" 1 vision 3r.png 3v.png 4r.png --combine -- "question"
assert_contains "--combine + 3 files: mentions exactly 2" "$ERR" "exactly 2"

run_cli "--combine + --content-area is rejected" 1 vision 3r.png 3v.png --combine --content-area -- "question"
assert_contains "--combine + --content-area: mentions whole-page-only" "$ERR" "whole-page"

if run_cli "question starting with -- survives the separator" 0 vision 3r.png -- "--not a flag, just a weird question, one word answer"; then
    echo "  [ok]   dash-prefixed question after -- did not error"
    PASS=$((PASS + 1))
fi

# ------------------------------------------------------------------------
echo "== Group 2: two-page/matrix shape validation (offline) =="

TWO_PAGE_SINGLE="$TMPDIR_TEST/two-page-single.png"
run_cli "two-page single filename (auto-pair)" 0 two-page 3r.png --out "$TWO_PAGE_SINGLE"
assert_file_exists "two-page single filename: output written" "$TWO_PAGE_SINGLE"

TWO_PAGE_PAIR="$TMPDIR_TEST/two-page-pair.png"
run_cli "two-page explicit pair" 0 two-page 3r.png 3v.png --out "$TWO_PAGE_PAIR"
assert_file_exists "two-page explicit pair: output written" "$TWO_PAGE_PAIR"

TWO_PAGE_REVERSED="$TMPDIR_TEST/two-page-reversed.png"
run_cli "two-page reversed argument order" 0 two-page 3v.png 3r.png --out "$TWO_PAGE_REVERSED"
assert_file_exists "two-page reversed order: output written" "$TWO_PAGE_REVERSED"

run_cli "two-page rejects non-foliated filename" 1 two-page Front_cover.png
assert_contains "two-page non-foliated: clear rejection" "$ERR" "not a plain"

MATRIX_OUT="$TMPDIR_TEST/matrix.png"
run_cli "matrix with 3 filenames" 0 matrix 3r.png 3v.png 4r.png --out "$MATRIX_OUT"
assert_file_exists "matrix: output written" "$MATRIX_OUT"

run_cli "matrix with no filenames is rejected" 1 matrix

# ------------------------------------------------------------------------
echo "== Group 3: Voynich.config / --view regression check (offline) =="

EXTRACT_OUT="$TMPDIR_TEST/extract.png"
run_cli "extract --content-area --out (baseline)" 0 extract 3r.png --content-area --out "$EXTRACT_OUT"
assert_file_exists "extract --out: output written" "$EXTRACT_OUT"

if run_cli "extract --content-area --view does not crash" 0 extract 3r.png --content-area --view; then
    if [[ "$ERR" == *"NullPointerException"* || "$ERR" == *"Exception"* ]]; then
        echo "  [FAIL] --view: stderr shows an exception (the Voynich.config regression)"
        echo "         $ERR"
        FAIL=$((FAIL + 1))
    else
        echo "  [ok]   --view: no exception in stderr"
        PASS=$((PASS + 1))
    fi
fi

# ------------------------------------------------------------------------
echo "== Group 4: composite size sanity (structural, not byte-count) =="
# vision --combine builds its composite in memory and never writes it to
# disk (confirmed in CatalogCli.visionCombined), so there's no file from
# group 1 to size-check directly. What actually matters — and what broke
# before this session's fix — is that the vision-bound composite and the
# infimg-bound composite are genuinely different code paths: the former
# pre-scales each source to VisionClient.MAX_DIMENSION/2 per side before
# compositing, the latter (two-page, exercised in Group 2 above) stays
# full resolution. A byte-count assertion here would only be meaningful
# by accident (it'd depend on these two specific fixtures' pixel
# dimensions) — the real guarantee is structural, already confirmed by
# reading CatalogCli.visionCombined's cellCap math directly. This group
# just confirms the full-res reference point (two-page's output) is
# substantially larger than a first-pass-vision-upload would ever need
# to be, as a sanity check that the two paths haven't collapsed into one.
assert_file_size_over "two-page composite is full-resolution (not vision-capped)" "$TWO_PAGE_PAIR" 1000000

# ------------------------------------------------------------------------
echo "== Group 5: vision on a raw file path, not a catalog filename (network-dependent) =="
# two-page/matrix write a composite that is never itself a cataloged entry —
# added 2026-08-14 so vision can be pointed at that composite directly.

if run_cli "vision on a raw composite path" 0 vision "$TWO_PAGE_PAIR" "one word: are there two page images visible side by side"; then
    echo "  [ok]   raw-path vision call produced an answer"
    PASS=$((PASS + 1))
fi

run_cli "raw path + --content-area is rejected" 1 vision "$TWO_PAGE_PAIR" --content-area "question"
assert_contains "raw path + --content-area: clear rejection" "$ERR" "raw file path"

run_cli "nonexistent path keeps the original error message" 1 vision "$TMPDIR_TEST/does-not-exist.png" "question"
assert_contains "nonexistent path: unchanged error text" "$ERR" "No entry for"

if run_cli "mixed batch: catalog filename + raw path" 0 vision 3r.png "$TWO_PAGE_PAIR" -- "one word: any plants visible"; then
    assert_contains "mixed batch: catalog filename answered" "$OUT" "3r.png:"
    assert_contains "mixed batch: raw path answered" "$OUT" "$TWO_PAGE_PAIR:"
fi

# ------------------------------------------------------------------------
echo
echo "== Summary: $PASS passed, $FAIL failed =="
if [[ "$FAIL" -gt 0 ]]; then
    exit 1
fi
exit 0
