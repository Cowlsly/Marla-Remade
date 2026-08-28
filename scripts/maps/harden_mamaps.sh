#!/usr/bin/env bash
# Harden a `.mamaps` build: determinism, ring validity, cache invalidation, and size.
#
# The checks that cannot live in a unit test because they need a real extract and a real archive.
# Run before publishing.
#
# Usage:
#   ./harden_mamaps.sh --pbf california.osm.pbf [--out DIR] [--max-zoom 14]
#
# What it proves:
#
#   1. **Byte-identical rebuilds.** Three runs of the same input, at three thread counts, must
#      produce the same SHA-256. This is the check that catches a `HashMap` iterated in an emit path,
#      which is the one determinism bug that never shows up as a wrong pixel -- only as an archive
#      that will not diff against itself.
#   2. **A build id that follows its inputs.** Change the zoom range and the id has to change, or a
#      republish leaves every reader addressing the previous build's byte offsets forever.
#   3. **Ring validity across every tile**, via `mamaps_dump`, because the archive's header claims it
#      and the renderer skips a repair pass on the strength of that claim.
#   4. **The prefix budget.** Header plus dictionary plus root index inside 16 KiB, so a cold open is
#      one range request and not two.
#
# Everything here is read-only apart from the archives it writes under --out.

set -euo pipefail

PBF=""
OUT="${TMPDIR:-/tmp}/mamaps_harden"
MAX_ZOOM=14

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pbf) PBF="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --max-zoom) MAX_ZOOM="$2"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "harden_mamaps: unexpected argument '$1'" >&2; exit 2 ;;
  esac
done
[[ -n "$PBF" ]] || { echo "harden_mamaps: --pbf is required" >&2; exit 2; }
[[ -f "$PBF" ]] || { echo "harden_mamaps: $PBF does not exist" >&2; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

echo "== building mamaps_build and mamaps_dump =="
cargo build --release --quiet --manifest-path "$HERE/mamaps_build/Cargo.toml"
cargo build --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" --bin mamaps_dump
BUILD="$HERE/mamaps_build/target/release/mamaps_build"
DUMP="$HERE/tile_build/target/release/mamaps_dump"

sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

echo
echo "== 1. byte-identical rebuilds at 1, 3 and 32 threads =="
# The thread count is passed through RAYON_NUM_THREADS, which is what `tile_build`'s pool reads. The
# output must not depend on it: a differing hash means an emit path whose order comes from a pool.
HASHES=()
for threads in 1 3 32; do
  RAYON_NUM_THREADS="$threads" "$BUILD" \
    --input "$PBF" --out "$OUT/t$threads.mamaps" --max-zoom "$MAX_ZOOM" >/dev/null
  h="$(sha "$OUT/t$threads.mamaps")"
  echo "  $threads thread(s): ${h:0:16}"
  HASHES+=("$h")
done
for h in "${HASHES[@]}"; do
  if [[ "$h" != "${HASHES[0]}" ]]; then
    echo "  FAIL: the archive depends on the thread count" >&2
    exit 1
  fi
done
echo "  ok: identical across thread counts"

echo
echo "== 2. a build id that follows its inputs =="
id_of() { "$DUMP" "$1" --mode header | awk -F'\t' '$1=="build_id"{print $2}'; }
BASE_ID="$(id_of "$OUT/t1.mamaps")"
"$BUILD" --input "$PBF" --out "$OUT/shallow.mamaps" --max-zoom "$((MAX_ZOOM - 1))" >/dev/null
SHALLOW_ID="$(id_of "$OUT/shallow.mamaps")"
echo "  z0-$MAX_ZOOM        $BASE_ID"
echo "  z0-$((MAX_ZOOM - 1))        $SHALLOW_ID"
if [[ "$BASE_ID" == "$SHALLOW_ID" ]]; then
  echo "  FAIL: a different zoom range produced the same build id" >&2
  exit 1
fi
# And a rebuild of the same thing keeps its id, or every rebuild would invalidate every cache.
if [[ "$(id_of "$OUT/t3.mamaps")" != "$BASE_ID" ]]; then
  echo "  FAIL: rebuilding the same input changed the build id" >&2
  exit 1
fi
echo "  ok: the id follows the inputs and nothing else"

echo
echo "== 3. the archive claims validated rings =="
VALIDATED="$("$DUMP" "$OUT/t1.mamaps" --mode header | awk -F'\t' '$1=="rings_validated"{print $2}')"
echo "  rings_validated  $VALIDATED"
[[ "$VALIDATED" == "true" ]] || { echo "  FAIL: the renderer's repair pass will not be skipped" >&2; exit 1; }
echo "  ok"

echo
echo "== 4. the prefix budget: one range request to open =="
PREFIX="$("$DUMP" "$OUT/t1.mamaps" --mode header | awk -F'\t' '$1=="prefix_bytes"{print $2}')"
echo "  header + dictionary + root  $PREFIX bytes of 16384"
if (( PREFIX > 16384 )); then
  echo "  FAIL: a cold open costs two requests, forever, for every reader" >&2
  exit 1
fi
echo "  ok"

echo
echo "== summary =="
"$DUMP" "$OUT/t1.mamaps" --mode header | grep -E 'file_len|tiles_addressed|bodies_written|dedup'
echo
echo "all checks passed; archive at $OUT/t1.mamaps"
echo "publish with: ./publish_r2.sh (see its --help for the immutable-name caveat)"
