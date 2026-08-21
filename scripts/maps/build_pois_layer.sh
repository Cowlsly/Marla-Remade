#!/bin/bash
set -euo pipefail

# build_pois_layer.sh — bake our OWN OSM POI layer (`ma_pois`) + two side files.
#
# So that POI placement/name/type come from OpenStreetMap (baked at build time),
# not a runtime Google viewport scrape. Google is then hit only for rich details
# when the user taps a POI. This deliberately does NOT reuse the Protomaps base
# `pois` layer (the app suppresses that one).
#
# Produces three mutually-consistent outputs from a single `poi_extract` pass
# (same POI set, same coordinates):
#
#   * <out>.pmtiles   source-layer `ma_pois` (Point) — attrs: name, type, osm_id
#   * poi_names.bin   deduped NUL-terminated UTF-8 name table (each unique name
#                     once); a "name start index" is the byte offset of a name
#   * poi_index.bin   flat 14-byte records (little-endian):
#                       int32 lat_e7, int32 lon_e7, uint32 name_off, uint16 type
#                     sorted by Morton (Z-order) key of (lat,lon)
#
# See scripts/maps/osm_ingest (src/poi_build.rs) for the full TYPE MAP + the exact
# on-disk layouts, and the README (`ma_pois` schema + file formats) for the
# app-side contract.
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
#   --bbox BOX        Optional "minlon,minlat,maxlon,maxlat" metro extract (dry runs)
#   --minzoom N       tippecanoe minzoom (default 12)
#   --maxzoom N       tippecanoe maxzoom (default 16)
#   --keep-tmp        Don't delete intermediate files
#
# Tools required: cargo, osmium (osmium-tool), tippecanoe. The two side files can
# also be built on their own, on any platform, with:
#   cargo run --release --manifest-path osm_ingest/Cargo.toml --bin poi_extract -- ...
# only the .pmtiles step needs tippecanoe/osmium.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="ma_pois.pmtiles"
NAMES_OUT="poi_names.bin"
INDEX_OUT="poi_index.bin"
BBOX=""
MINZOOM=12
MAXZOOM=16
KEEP_TMP=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --names-out) NAMES_OUT="$2"; shift 2 ;;
        --index-out) INDEX_OUT="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --minzoom) MINZOOM="$2"; shift 2 ;;
        --maxzoom) MAXZOOM="$2"; shift 2 ;;
        --keep-tmp) KEEP_TMP=1; shift ;;
        -h|--help) sed -n '4,45p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

[[ -n "$PBF" ]] || { echo "ERROR: --pbf required" >&2; exit 1; }
[[ -f "$PBF" ]] || { echo "ERROR: pbf not found: $PBF" >&2; exit 1; }
command -v cargo      >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }
command -v osmium     >/dev/null || { echo "ERROR: osmium-tool not installed" >&2; exit 1; }
command -v tippecanoe >/dev/null || { echo "ERROR: tippecanoe not installed" >&2; exit 1; }

TMP="$(mktemp -d)"
cleanup() { [[ "$KEEP_TMP" == "1" ]] || rm -rf "$TMP"; }
trap cleanup EXIT

SRC="$PBF"
if [[ -n "$BBOX" ]]; then
    echo "[ma_pois] extracting metro bbox $BBOX"
    osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
    SRC="$TMP/metro.osm.pbf"
fi

echo "[ma_pois] extracting POIs -> geojsonseq + $NAMES_OUT + $INDEX_OUT"
cargo run --release --manifest-path "$HERE/osm_ingest/Cargo.toml" --bin poi_extract -- \
    "$SRC" \
    --geojson "$TMP/ma_pois.geojsonseq" \
    --names "$NAMES_OUT" \
    --index "$INDEX_OUT"

FEATURES="$(wc -l < "$TMP/ma_pois.geojsonseq" | tr -d ' ')"
echo "[ma_pois] $FEATURES POI feature(s)"
[[ "$FEATURES" -gt 0 ]] || echo "[ma_pois] WARNING: 0 features — check bbox/extract"

echo "[ma_pois] tiling -> $OUT (z$MINZOOM-$MAXZOOM)"
tippecanoe --force \
    -o "$OUT" \
    -l ma_pois \
    --minimum-zoom="$MINZOOM" \
    --maximum-zoom="$MAXZOOM" \
    --drop-densest-as-needed \
    --extend-zooms-if-still-dropping \
    "$TMP/ma_pois.geojsonseq"

echo "[ma_pois] done: $OUT + $NAMES_OUT + $INDEX_OUT"
