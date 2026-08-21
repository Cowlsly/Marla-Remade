#!/bin/bash
set -euo pipefail

# build_safety_layer.sh — bake the `safety` road-furniture layer into a .pmtiles
#
# Extracts speed cameras, ALPR/surveillance (DeFlock), stop signs and traffic
# signals from an OSM extract and tiles them into a single-layer PMTiles file.
# These are baked at build time so the app never needs a runtime Overpass query.
#
# Source layer produced:  safety   (attr: kind, name?, direction?, operator?, ref?,
#                                   surveillance_type?, osm_id)
#   kind ∈ { speed_camera, alpr, surveillance, stop_sign, traffic_signals }
#
# TWO ENGINES. `--engine rust` (the default) is cargo-only: osm_ingest's
# `osm_extract` reads the .osm.pbf directly and tile_build's `tile_points` tiles
# it, so this layer needs neither osmium, nor tippecanoe, nor python3, and runs on
# Windows. `--engine legacy` is the original
# `osmium tags-filter | osmium export | normalize_safety.py | tippecanoe` chain,
# kept so the two can be diffed against each other with
# `test/diff_geojsonseq.py` and `test/diff_pmtiles.py`.
#
# The Rust classifier mirrors normalize_safety.py exactly, including the two
# things easiest to get wrong: `enforcement=maxspeed` is checked BEFORE the
# surveillance branch, and the ALPR heuristic matches `surveillance:type` by
# substring but `camera:type` by exact equality. See osm_ingest/src/safety.rs.
#
# Usage:
#   ./build_safety_layer.sh --pbf planet.osm.pbf --out safety.pmtiles
#   ./build_safety_layer.sh --pbf planet.osm.pbf --bbox -122.6,37.2,-121.7,37.9 --out safety.pmtiles
#   ./build_safety_layer.sh --pbf planet.osm.pbf --engine legacy --out safety-legacy.pmtiles
#
# Options:
#   --pbf FILE     Input OSM .pbf (required)
#   --out FILE     Output .pmtiles (default: safety.pmtiles)
#   --bbox BOX     Optional "minlon,minlat,maxlon,maxlat" metro extract (dry runs)
#   --minzoom N    tiler minzoom (default 10)
#   --maxzoom N    tiler maxzoom (default 16)
#   --engine E     rust|legacy (default rust)
#   --geojson-out F  also keep the intermediate geojsonseq here (for the
#                    differential harness, which needs both engines' output)
#   --keep-tmp     Don't delete intermediate files
#
# Tools required: cargo. `--engine legacy` additionally needs osmium, tippecanoe
# and python3; `--engine rust --bbox` needs none of them (the clip is inline).

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="safety.pmtiles"
BBOX=""
MINZOOM=10
MAXZOOM=16
ENGINE="rust"
GEOJSON_OUT=""
KEEP_TMP=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --minzoom) MINZOOM="$2"; shift 2 ;;
        --maxzoom) MAXZOOM="$2"; shift 2 ;;
        --engine) ENGINE="$2"; shift 2 ;;
        --geojson-out) GEOJSON_OUT="$2"; shift 2 ;;
        --keep-tmp) KEEP_TMP=1; shift ;;
        -h|--help) sed -n '4,44p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

[[ -n "$PBF" ]] || { echo "ERROR: --pbf required" >&2; exit 1; }
[[ -f "$PBF" ]] || { echo "ERROR: pbf not found: $PBF" >&2; exit 1; }
case "$ENGINE" in
    rust|legacy) : ;;
    *) echo "ERROR: --engine must be rust|legacy (got '$ENGINE')" >&2; exit 1 ;;
esac
command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }
if [[ "$ENGINE" == "legacy" ]]; then
    command -v osmium     >/dev/null || { echo "ERROR: --engine legacy needs osmium-tool" >&2; exit 1; }
    command -v tippecanoe >/dev/null || { echo "ERROR: --engine legacy needs tippecanoe" >&2; exit 1; }
    command -v python3    >/dev/null || { echo "ERROR: --engine legacy needs python3" >&2; exit 1; }
fi

TMP="$(mktemp -d)"
cleanup() { [[ "$KEEP_TMP" == "1" ]] || rm -rf "$TMP"; }
trap cleanup EXIT

GEOJSON="${GEOJSON_OUT:-$TMP/safety.geojsonseq}"
[[ -z "$GEOJSON_OUT" ]] || mkdir -p "$(dirname "$GEOJSON_OUT")"

if [[ "$ENGINE" == "rust" ]]; then
    echo "[safety] extracting safety nodes -> $GEOJSON (osm_extract)"
    # One pass over the PBF, with the bbox applied inline: no tags-filter
    # intermediate, no GeoJSON round trip through python.
    EXTRACT_ARGS=("$PBF" --layer safety --out "$GEOJSON")
    [[ -n "$BBOX" ]] && EXTRACT_ARGS+=(--bbox "$BBOX")
    cargo run --release --quiet --manifest-path "$HERE/osm_ingest/Cargo.toml" \
        --bin osm_extract -- "${EXTRACT_ARGS[@]}"
else
    SRC="$PBF"
    if [[ -n "$BBOX" ]]; then
        echo "[safety] extracting metro bbox $BBOX"
        osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
        SRC="$TMP/metro.osm.pbf"
    fi

    echo "[safety] filtering safety/road-furniture nodes"
    # Keep only the node types we bake. `enforcement=maxspeed` catches speed-camera
    # nodes tagged via the enforcement relation scheme.
    osmium tags-filter --overwrite "$SRC" \
        n/highway=speed_camera,stop,traffic_signals \
        n/man_made=surveillance \
        n/enforcement=maxspeed \
        -o "$TMP/safety_raw.osm.pbf"

    echo "[safety] exporting to GeoJSON + normalizing kinds"
    osmium export -f geojsonseq --overwrite "$TMP/safety_raw.osm.pbf" \
        | python3 "$HERE/normalize_safety.py" \
        > "$GEOJSON"
fi

FEATURES="$(wc -l < "$GEOJSON" | tr -d ' ')"
echo "[safety] $FEATURES feature(s)"
[[ "$FEATURES" -gt 0 ]] || echo "[safety] WARNING: 0 features — check bbox/extract"

echo "[safety] tiling -> $OUT (z$MINZOOM-$MAXZOOM, engine: $ENGINE)"
if [[ "$ENGINE" == "rust" ]]; then
    cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
        --bin tile_points -- \
        --geojson "$GEOJSON" \
        --out "$OUT" \
        --layer safety \
        --minzoom "$MINZOOM" \
        --maxzoom "$MAXZOOM"
else
    tippecanoe --force \
        -o "$OUT" \
        -l safety \
        --minimum-zoom="$MINZOOM" \
        --maximum-zoom="$MAXZOOM" \
        --drop-densest-as-needed \
        --extend-zooms-if-still-dropping \
        "$GEOJSON"
fi

echo "[safety] done: $OUT"
