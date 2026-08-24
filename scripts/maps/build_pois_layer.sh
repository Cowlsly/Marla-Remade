#!/bin/bash
set -euo pipefail

# build_pois_layer.sh — bake our OWN OSM POI layer (`ma_pois`) + two side files.
#
# So that POI placement/name/type come from OpenStreetMap (baked at build time),
# not a runtime Google viewport scrape. Google is then hit only for rich details
# when the user taps a POI. This deliberately does NOT reuse the Protomaps base
# `pois` layer (the app suppresses that one).
#
# Produces six mutually-consistent outputs from a single `poi_extract` pass
# (same POI set, same order, same coordinates):
#
#   * <out>.pmtiles   source-layer `ma_pois` (Point) - attrs: name, type, osm_id
#   * poi_names.bin   deduped NUL-terminated UTF-8 name table (each unique name
#                     once); a "name start index" is the byte offset of a name
#   * poi_index.bin   flat 14-byte records (little-endian):
#                       int32 lat_e7, int32 lon_e7, uint32 name_off, uint16 type
#                     sorted by Morton (Z-order) key of (lat,lon)
#   * poi_attrs.bin   attribute sidecar keyed by poi_index.bin RECORD ORDINAL:
#                     opening_hours, phone, website, addr:*, cuisine, wheelchair
#   * poi_spatial.bin sparse CSR lat/lon grid over record ordinals, so a bbox
#                     query is cell-local instead of a Morton-span walk
#   * poi_name_index.bin  one (record, word) entry per word of every name,
#                     sorted by word, so name search is a binary search
#
# The last two are OPTIONAL for the app - it falls back to the Morton walk and
# the name-pool scan - but they are what keeps a planet-sized index usable.
#
# See scripts/maps/osm_ingest (src/poi_build.rs, src/poi_attrs.rs) for the full
# TYPE MAP + the exact on-disk layouts, and the README (`ma_pois` schema + file
# formats) for the app-side contract.
#
# Usage:
#   ./build_pois_layer.sh --pbf planet.osm.pbf --out ma_pois.pmtiles
#   ./build_pois_layer.sh --pbf planet.osm.pbf --bbox -122.6,37.2,-121.7,37.9 \
#       --out ma_pois.pmtiles --names-out poi_names.bin --index-out poi_index.bin
#
# Options:
#   --pbf FILE        Input OSM .pbf (required)
#   --out FILE        Output .pmtiles (default: ma_pois.pmtiles)
#   --names-out FILE  Output name table (default: poi_names.bin)
#   --index-out FILE  Output record index (default: poi_index.bin)
#   --attrs-out FILE  Output attribute sidecar (default: poi_attrs.bin beside
#                     --index-out)
#   --spatial-out FILE     Output spatial grid (default: poi_spatial.bin beside
#                     --index-out)
#   --name-index-out FILE  Output word index (default: poi_name_index.bin beside
#                     --index-out)
#   --bbox BOX        Optional "minlon,minlat,maxlon,maxlat" metro extract (dry runs)
#   --minzoom N       tiler minzoom (default 12)
#   --maxzoom N       tiler maxzoom (default 16)
#   --engine E        rust|legacy tiler (default rust). `rust` is tile_build's
#                     tile_points, so no tippecanoe install is needed; `legacy`
#                     is tippecanoe. The GeoJSON is Rust-produced either way, so
#                     this layer never needed tippecanoe in the first place.
#   --keep-tmp        Don't delete intermediate files
#
# Tools required: cargo. Additionally osmium (osmium-tool) only for --bbox, and
# tippecanoe only for --engine legacy. The three side files can also be built on
# their own, on any platform, with:
#   cargo run --release --manifest-path osm_ingest/Cargo.toml --bin poi_extract -- ...

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="ma_pois.pmtiles"
NAMES_OUT="poi_names.bin"
INDEX_OUT="poi_index.bin"
ATTRS_OUT=""
SPATIAL_OUT=""
NAME_INDEX_OUT=""
BBOX=""
MINZOOM=12
MAXZOOM=16
ENGINE="rust"
KEEP_TMP=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --names-out) NAMES_OUT="$2"; shift 2 ;;
        --index-out) INDEX_OUT="$2"; shift 2 ;;
        --attrs-out) ATTRS_OUT="$2"; shift 2 ;;
        --spatial-out) SPATIAL_OUT="$2"; shift 2 ;;
        --name-index-out) NAME_INDEX_OUT="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --minzoom) MINZOOM="$2"; shift 2 ;;
        --maxzoom) MAXZOOM="$2"; shift 2 ;;
        --engine) ENGINE="$2"; shift 2 ;;
        --keep-tmp) KEEP_TMP=1; shift ;;
        -h|--help) sed -n '4,51p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

# Beside the index by default, because that is where the app looks for it and
# because the two are only meaningful as a pair.
[[ -n "$ATTRS_OUT" ]] || ATTRS_OUT="$(dirname "$INDEX_OUT")/poi_attrs.bin"
[[ -n "$SPATIAL_OUT" ]] || SPATIAL_OUT="$(dirname "$INDEX_OUT")/poi_spatial.bin"
[[ -n "$NAME_INDEX_OUT" ]] || NAME_INDEX_OUT="$(dirname "$INDEX_OUT")/poi_name_index.bin"

[[ -n "$PBF" ]] || { echo "ERROR: --pbf required" >&2; exit 1; }
[[ -f "$PBF" ]] || { echo "ERROR: pbf not found: $PBF" >&2; exit 1; }
case "$ENGINE" in
    rust|legacy) : ;;
    *) echo "ERROR: --engine must be rust|legacy (got '$ENGINE')" >&2; exit 1 ;;
esac
command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }
# Only demanded where actually used, so the default rust path runs on a box with
# neither osmium nor tippecanoe installed.
if [[ -n "$BBOX" ]]; then
    command -v osmium >/dev/null || { echo "ERROR: --bbox needs osmium-tool" >&2; exit 1; }
fi
if [[ "$ENGINE" == "legacy" ]]; then
    command -v tippecanoe >/dev/null || { echo "ERROR: --engine legacy needs tippecanoe" >&2; exit 1; }
fi

TMP="$(mktemp -d)"
cleanup() { [[ "$KEEP_TMP" == "1" ]] || rm -rf "$TMP"; }
trap cleanup EXIT

SRC="$PBF"
if [[ -n "$BBOX" ]]; then
    echo "[ma_pois] extracting metro bbox $BBOX"
    osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
    SRC="$TMP/metro.osm.pbf"
fi

echo "[ma_pois] extracting POIs -> geojsonseq + $NAMES_OUT + $INDEX_OUT + $ATTRS_OUT + $SPATIAL_OUT + $NAME_INDEX_OUT"
cargo run --release --manifest-path "$HERE/osm_ingest/Cargo.toml" --bin poi_extract -- \
    "$SRC" \
    --geojson "$TMP/ma_pois.geojsonseq" \
    --names "$NAMES_OUT" \
    --index "$INDEX_OUT" \
    --attrs "$ATTRS_OUT" \
    --spatial "$SPATIAL_OUT" \
    --name-index "$NAME_INDEX_OUT"

FEATURES="$(wc -l < "$TMP/ma_pois.geojsonseq" | tr -d ' ')"
echo "[ma_pois] $FEATURES POI feature(s)"
[[ "$FEATURES" -gt 0 ]] || echo "[ma_pois] WARNING: 0 features — check bbox/extract"

echo "[ma_pois] tiling -> $OUT (z$MINZOOM-$MAXZOOM, engine: $ENGINE)"
if [[ "$ENGINE" == "rust" ]]; then
    cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
        --bin tile_points -- \
        --geojson "$TMP/ma_pois.geojsonseq" \
        --out "$OUT" \
        --layer ma_pois \
        --minzoom "$MINZOOM" \
        --maxzoom "$MAXZOOM"
else
    tippecanoe --force \
        -o "$OUT" \
        -l ma_pois \
        --minimum-zoom="$MINZOOM" \
        --maximum-zoom="$MAXZOOM" \
        --drop-densest-as-needed \
        --extend-zooms-if-still-dropping \
        "$TMP/ma_pois.geojsonseq"
fi

echo "[ma_pois] done: $OUT + $NAMES_OUT + $INDEX_OUT"
