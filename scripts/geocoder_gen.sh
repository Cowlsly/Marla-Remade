#!/usr/bin/env bash
# Build and run the offline geocoder database generator (scripts/geocoder_gen.cpp).
#
# Pipeline:
#   planet-latest.osm.pbf --osmium--> addr.geojsonseq --geocoder_gen--> geocoder.geodb
#
# The generator self-verifies its output (reverse + forward round-trip) before finishing.
# Nothing here is committed except this script and geocoder_gen.cpp; simdjson and the build
# artifacts live in a scratch dir. Intended to run under WSL/Linux on the build machine.
#
# Usage:
#   scripts/geocoder_gen.sh                 # full run (extract if needed, build, generate)
#   PBF=... GEOJSON=... OUT=... scripts/geocoder_gen.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRATCH="${SCRATCH:-$HOME/geocoder-scratch}"
BUILD="$SCRATCH/build"
PBF="${PBF:-$SCRATCH/planet-latest.osm.pbf}"
GEOJSON="${GEOJSON:-$SCRATCH/addr.geojsonseq}"
OUT="${OUT:-$SCRATCH/geocoder.geodb}"
# Pinned to commit SHAs rather than tags: a tag can be repointed upstream, so it is not a content
# pin. To bump, resolve the new tag with `git ls-remote <repo> 'refs/tags/<tag>^{}'` and update the
# SHA and its version comment together. The `^{}` matters: it peels annotated tags (zstd's are) to
# the commit, whereas the bare ref returns the tag object, which is not a valid content ref here.
SIMDJSON_REV="${SIMDJSON_REV:-b4242d3b4ffb97854b035175be077aab712a2d46}" # v3.11.0
ZSTD_REV="${ZSTD_REV:-63779c798237346c2b245c546c40b72a5a5913fe}"         # v1.5.5

mkdir -p "$BUILD"

echo "==> Toolchain check (no sudo; libzstd is linked from the existing runtime .so)"
command -v g++ >/dev/null || { echo "g++ not found. Install: sudo apt-get install -y g++" >&2; exit 1; }
ZSTD_SO="$(ldconfig -p | awk '/libzstd\.so/{print $NF; exit}')"
[ -n "$ZSTD_SO" ] || { echo "libzstd runtime not found (libzstd.so*). Install: sudo apt-get install -y libzstd1" >&2; exit 1; }
echo "    using libzstd: $ZSTD_SO"

echo "==> Headers: simdjson ($SIMDJSON_REV) + zstd.h ($ZSTD_REV)"
if [ ! -f "$BUILD/simdjson.cpp" ] || [ ! -f "$BUILD/simdjson.h" ]; then
  base="https://raw.githubusercontent.com/simdjson/simdjson/$SIMDJSON_REV/singleheader"
  curl -fL --ipv4 -o "$BUILD/simdjson.h" "$base/simdjson.h"
  curl -fL --ipv4 -o "$BUILD/simdjson.cpp" "$base/simdjson.cpp"
fi
if [ ! -f "$BUILD/zstd.h" ]; then
  curl -fL --ipv4 -o "$BUILD/zstd.h" \
    "https://raw.githubusercontent.com/facebook/zstd/$ZSTD_REV/lib/zstd.h"
fi

echo "==> Compiling geocoder_gen"
g++ -O2 -fopenmp -std=c++17 -I"$BUILD" \
    "$HERE/geocoder_gen.cpp" "$BUILD/simdjson.cpp" \
    "$ZSTD_SO" -o "$BUILD/geocoder_gen"

if [ ! -f "$GEOJSON" ]; then
  echo "==> Extracting addresses from $PBF (osmium; multi-hour)"
  command -v osmium >/dev/null || { echo "osmium not found. Install: sudo apt-get install -y osmium-tool" >&2; exit 1; }
  [ -f "$PBF" ] || { echo "planet PBF not found: $PBF" >&2; exit 1; }
  osmium tags-filter "$PBF" \
      n/addr:housenumber w/addr:housenumber r/addr:housenumber \
      -o "$SCRATCH/filtered.osm.pbf" --overwrite
  osmium export "$SCRATCH/filtered.osm.pbf" -f geojsonseq -o "$GEOJSON" --overwrite
fi

echo "==> Generating $OUT from $GEOJSON"
"$BUILD/geocoder_gen" generate "$GEOJSON" "$OUT"

echo "==> Result"
ls -lh "$OUT"
sha256sum "$OUT"
