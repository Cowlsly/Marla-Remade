#!/bin/bash
set -euo pipefail
# build_transit_stops_layer.sh - bake GTFS stops into a transit_stops .pmtiles.
#
# Cargo-only: unlike the other layer scripts this needs neither osmium nor
# tippecanoe, so it also runs on the Windows dev box via its .ps1 twin. Two
# stages, both Rust:
#
#   1. gtfs_ingest --bin transit_stops  ->  transit_stops.geojsonseq
#   2. tile_build  --bin tile_points    ->  transit_stops.pmtiles
#
# Input is the SAME unzipped GTFS directories the offline routing pack is built
# from, addressed through the same feeds.manifest - so the stop pins on the map and
# the stops the on-device planner routes through come from one source of truth.
#
# Each feature carries `motis_id` (<Transitous prefix>_<gtfs stop_id>), `name` and
# `route_type`. The motis_id is what lets a tapped pin fetch live departures from
# MOTIS /stoptimes without the removed /api/v1/map/stops lookup. A feed whose
# Transitous source name the build does not know contributes stops with no
# motis_id; they still open an offline departure board, just no live delays.
#
# Usage:
#   ./build_transit_stops_layer.sh --manifest world_transit_work/feeds.manifest
#   ./build_transit_stops_layer.sh --manifest m.txt --out transit_stops.pmtiles \
#       --minzoom 10 --maxzoom 14
#
# Options:
#   --manifest FILE   feeds.manifest: `name=dir[=motis_prefix]` per line (required)
#   --out FILE        output .pmtiles (default transit_stops.pmtiles)
#   --workdir DIR     scratch dir for the geojsonseq (default ./transit_stops_work)
#   --minzoom N       default 10 (stops are denser than ma_pois, sparser than roads)
#   --maxzoom N       default 14
#   --keep-work       keep the intermediate geojsonseq
#
# Tools required: cargo. Nothing else.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST=""
OUT="transit_stops.pmtiles"
WORK="./transit_stops_work"
MINZOOM=10
MAXZOOM=14
KEEP_WORK=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --manifest) MANIFEST="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --workdir) WORK="$2"; shift 2 ;;
        --minzoom) MINZOOM="$2"; shift 2 ;;
        --maxzoom) MAXZOOM="$2"; shift 2 ;;
        --keep-work) KEEP_WORK=1; shift ;;
        -h|--help) sed -n '3,34p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

[[ -n "$MANIFEST" ]] || { echo "ERROR: --manifest required" >&2; exit 1; }
[[ -f "$MANIFEST" ]] || { echo "ERROR: manifest not found: $MANIFEST" >&2; exit 1; }
(( MINZOOM <= MAXZOOM )) || { echo "ERROR: --minzoom above --maxzoom" >&2; exit 1; }
command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }

mkdir -p "$WORK"
GEOJSON="$WORK/transit_stops.geojsonseq"

echo "[transit_stops] 1/2 GTFS -> geojsonseq"
cargo run --release --quiet --manifest-path "$HERE/gtfs_ingest/Cargo.toml" \
    --bin transit_stops -- --geojson "$GEOJSON" --manifest "$MANIFEST"

FEATURES="$(wc -l < "$GEOJSON" | tr -d ' ')"
echo "[transit_stops] $FEATURES stop feature(s)"
[[ "$FEATURES" -gt 0 ]] || { echo "ERROR: no stop features - check the manifest" >&2; exit 1; }

echo "[transit_stops] 2/2 geojsonseq -> $OUT (z$MINZOOM-$MAXZOOM)"
cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
    --bin tile_points -- --geojson "$GEOJSON" --out "$OUT" --layer transit_stops \
    --minzoom "$MINZOOM" --maxzoom "$MAXZOOM"

[[ "$KEEP_WORK" == "1" ]] || rm -f "$GEOJSON"

SIZE="$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT" 2>/dev/null || echo '?')"
echo ""
echo "[transit_stops] done: $OUT (${SIZE} bytes), layer 'transit_stops'"
