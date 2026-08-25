#!/bin/bash
set -euo pipefail

# build_maxspeed_layer.sh — bake the `maxspeed` layer (P5b) into a .pmtiles
#
# SUPERSEDED BY build_roads_layer.sh, and no longer called by any pipeline. The
# `roads` layer carries this layer's geometry and its raw `maxspeed` string beside
# the class, lane and width attributes the app needs to draw the road, so keeping a
# second copy of every road in the world to hold one property is not worth it.
#
# Kept only so the retired layer can still be built and diffed against `roads`.
# Delete this, normalize_maxspeed.py and osm_ingest/src/maxspeed.rs once that
# comparison is done — the same policy README.md's Caveat §8 applies to the other
# superseded normalisers.
#
# Extracts posted speed limits from OSM ways and tiles them into a single-layer
# PMTiles file, so the app's MaxspeedSource reads them from the basemap instead of
# querying anything at runtime.
#
# Source layer produced:  maxspeed  (attr: maxspeed, highway?, name?, osm_id)
#
# THE VALUE IS A RAW STRING. `maxspeed` is the OSM tag verbatim -- "25 mph",
# "100 km/h", "50", "none", "walk", "signals" -- because OsmMaxspeed.kt parses it
# itself and the distinctions matter: `none` on an autobahn is not an absent limit,
# and `walk` is not a number. Do NOT put it through osm_ingest's
# `tags::parse_maxspeed`, which is the routing graph's parser and normalises to a
# u8 of km/h.
#
# TWO ENGINES. `--engine rust` (the default) is cargo-only: `osm_extract` reads the
# .osm.pbf directly and `tile_lines` tiles it, so this layer needs neither osmium,
# nor tippecanoe, nor python3, and runs on Windows. `--engine legacy` is the
# original `osmium | normalize_maxspeed.py | tippecanoe` chain, kept so the two can
# be diffed against each other with `test/diff_geojsonseq.py` and
# `test/diff_pmtiles.py`.
#
# Usage:
#   ./build_maxspeed_layer.sh --pbf planet.osm.pbf --out maxspeed.pmtiles
#   ./build_maxspeed_layer.sh --pbf planet.osm.pbf --bbox -122.6,37.2,-121.7,37.9 --out maxspeed.pmtiles
#
# Options:
#   --pbf FILE     Input OSM .pbf (required)
#   --out FILE     Output .pmtiles (default: maxspeed.pmtiles)
#   --bbox BOX     Optional "minlon,minlat,maxlon,maxlat" metro extract (dry runs)
#   --minzoom N    tiler minzoom (default 12)
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
OUT="maxspeed.pmtiles"
BBOX=""
MINZOOM=12
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
        -h|--help) sed -n '4,52p' "$0" | sed 's/^# \?//'; exit 0 ;;
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

GEOJSON="${GEOJSON_OUT:-$TMP/maxspeed.geojsonseq}"
[[ -z "$GEOJSON_OUT" ]] || mkdir -p "$(dirname "$GEOJSON_OUT")"

if [[ "$ENGINE" == "rust" ]]; then
    echo "[maxspeed] extracting speed-limit ways -> $GEOJSON (osm_extract)"
    EXTRACT_ARGS=("$PBF" --layer maxspeed --out "$GEOJSON")
    [[ -n "$BBOX" ]] && EXTRACT_ARGS+=(--bbox "$BBOX")
    cargo run --release --quiet --manifest-path "$HERE/osm_ingest/Cargo.toml" \
        --bin osm_extract -- "${EXTRACT_ARGS[@]}"
else
    SRC="$PBF"
    if [[ -n "$BBOX" ]]; then
        echo "[maxspeed] extracting metro bbox $BBOX"
        osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
        SRC="$TMP/metro.osm.pbf"
    fi

    echo "[maxspeed] filtering ways with a posted limit"
    osmium tags-filter --overwrite "$SRC" \
        w/maxspeed w/maxspeed:forward w/maxspeed:backward \
        -o "$TMP/maxspeed_raw.osm.pbf"

    echo "[maxspeed] exporting to GeoJSON + normalizing"
    osmium export -f geojsonseq --overwrite "$TMP/maxspeed_raw.osm.pbf" \
        | python3 "$HERE/normalize_maxspeed.py" \
        > "$GEOJSON"
fi

FEATURES="$(wc -l < "$GEOJSON" | tr -d ' ')"
echo "[maxspeed] $FEATURES feature(s)"
[[ "$FEATURES" -gt 0 ]] || echo "[maxspeed] WARNING: 0 features — check bbox/extract"

echo "[maxspeed] tiling -> $OUT (z$MINZOOM-$MAXZOOM, engine: $ENGINE)"
if [[ "$ENGINE" == "rust" ]]; then
    cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
        --bin tile_lines -- \
        --geojson "$GEOJSON" \
        --out "$OUT" \
        --layer maxspeed \
        --minzoom "$MINZOOM" \
        --maxzoom "$MAXZOOM"
else
    tippecanoe --force \
        -o "$OUT" \
        -l maxspeed \
        --minimum-zoom="$MINZOOM" \
        --maximum-zoom="$MAXZOOM" \
        --drop-densest-as-needed \
        --extend-zooms-if-still-dropping \
        "$GEOJSON"
fi

echo "[maxspeed] done: $OUT"
