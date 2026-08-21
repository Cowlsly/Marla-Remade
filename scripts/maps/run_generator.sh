#!/bin/bash
set -euo pipefail

# run_generator.sh — DEPRECATED, kept as a thin wrapper over build_all.sh.
#
# What this used to do: build the routing graph (5 files), then `aws s3 sync` 7
# keys. The other two keys were poi_names.bin and poi_index.bin, which it never
# produced -- you had to copy them into map_data/ by hand first. Because the sync
# used `--exclude "*"` plus explicit `--include`s, forgetting that step uploaded
# 5 of 7 keys and still printed "Pipeline Finished Successfully".
#
# build_all.sh runs poi_extract itself, so those two files are always there. It
# also builds world.transit and the tile archive, which this script never did.
#
# The old CLI still works:
#   ./run_generator.sh [california-latest.osm.pbf]
# and forwards to:
#   ./build_all.sh --pbf <that> --out-dir map_data \
#       --skip-transit --skip-tiles --publish
# i.e. the same 7 keys as before, minus the manual copy.
#
# TWO BEHAVIOUR CHANGES worth knowing:
#   * Credentials. publish_r2.sh reads R2_ENDPOINT / R2_ACCESS_KEY_ID /
#     R2_SECRET_ACCESS_KEY. This script read R2_ACCESS_KEY / R2_SECRET_KEY, so
#     those two are bridged below for compatibility.
#   * No AWS CLI bootstrap. publish_r2.sh uses rclone or aws, whichever is
#     present, and tells you how to install one if neither is.
#
# For anything new, call build_all.sh directly -- it has --dry-run, per-stage
# skips, stamps, and a manifest.txt with sizes and SHA-256s.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '4,31p' "$0" | sed 's/^# \?//'
    exit 0
fi

# Load environment variables from .env file if it exists
if [[ -f .env ]]; then
    echo "Loading configuration from .env..."
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
fi

# Bridge the old credential names onto the ones publish_r2.sh reads.
export R2_ACCESS_KEY_ID="${R2_ACCESS_KEY_ID:-${R2_ACCESS_KEY:-}}"
export R2_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:-${R2_SECRET_KEY:-}}"
export R2_BUCKET="${R2_BUCKET:-${BUCKET_NAME:-maps}}"

INPUT_PBF="${1:-california-latest.osm.pbf}"

echo "=========================================================" >&2
echo "run_generator.sh is DEPRECATED. It now just calls:" >&2
echo "  build_all.sh --pbf $INPUT_PBF --out-dir map_data \\" >&2
echo "      --skip-transit --skip-tiles --publish" >&2
echo "Use build_all.sh directly for the full 9-artifact build." >&2
echo "=========================================================" >&2

ARGS=(--out-dir map_data --skip-transit --skip-tiles --publish)
if [[ -f "$INPUT_PBF" ]]; then
    ARGS=(--pbf "$INPUT_PBF" "${ARGS[@]}")
elif [[ "$INPUT_PBF" == "california-latest.osm.pbf" ]]; then
    # Only auto-download the one extract this script always defaulted to; any
    # other missing path is a typo, and fetching California for it would build a
    # graph for the wrong region.
    ARGS=(--geofabrik north-america/us/california "${ARGS[@]}")
else
    echo "ERROR: pbf not found: $INPUT_PBF" >&2
    exit 1
fi

exec "$HERE/build_all.sh" "${ARGS[@]}"
