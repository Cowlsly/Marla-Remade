#!/bin/bash
set -euo pipefail

# build_maxspeed_layer.sh — bake the `maxspeed` layer (posted speed limits)
#
# Produces a line-geometry PMTiles layer of OSM ways that carry a maxspeed tag,
# so the app's posted-speed-limit feature (P5b MaxspeedSource) can read it via
# queryRenderedFeatures from v5.pmtiles instead of a separate host.
#
# Source layer produced:  maxspeed   (attr: maxspeed, highway?, name?, osm_id)
#   maxspeed = raw OSM value, e.g. "35 mph", "50", "50 km/h" (parsed app-side)
#
# Usage:
#   ./build_maxspeed_layer.sh --pbf planet.osm.pbf --out maxspeed.pmtiles
#   ./build_maxspeed_layer.sh --pbf planet.osm.pbf --bbox -122.6,37.2,-121.7,37.9 --out maxspeed.pmtiles
#
# Options:
#   --pbf FILE     Input OSM .pbf (required)
#   --out FILE     Output .pmtiles (default: maxspeed.pmtiles)
#   --bbox BOX     Optional "minlon,minlat,maxlon,maxlat" metro extract (dry runs)
#   --minzoom N    tippecanoe minzoom (default 12 — where roads appear)
#   --maxzoom N    tippecanoe maxzoom (default 16)
#   --keep-tmp     Don't delete intermediate files
#
# Tools required: osmium (osmium-tool), tippecanoe, python3

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="maxspeed.pmtiles"
BBOX=""
MINZOOM=12
MAXZOOM=16
KEEP_TMP=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --minzoom) MINZOOM="$2"; shift 2 ;;
        --maxzoom) MAXZOOM="$2"; shift 2 ;;
        --keep-tmp) KEEP_TMP=1; shift ;;
        -h|--help) sed -n '4,30p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

[[ -n "$PBF" ]] || { echo "ERROR: --pbf required" >&2; exit 1; }
[[ -f "$PBF" ]] || { echo "ERROR: pbf not found: $PBF" >&2; exit 1; }
command -v osmium     >/dev/null || { echo "ERROR: osmium-tool not installed" >&2; exit 1; }
command -v tippecanoe >/dev/null || { echo "ERROR: tippecanoe not installed" >&2; exit 1; }

TMP="$(mktemp -d)"
cleanup() { [[ "$KEEP_TMP" == "1" ]] || rm -rf "$TMP"; }
trap cleanup EXIT

SRC="$PBF"
if [[ -n "$BBOX" ]]; then
    echo "[maxspeed] extracting metro bbox $BBOX"
    osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
    SRC="$TMP/metro.osm.pbf"
fi

echo "[maxspeed] filtering ways with a maxspeed tag"
osmium tags-filter --overwrite "$SRC" \
    w/maxspeed w/maxspeed:forward w/maxspeed:backward \
    -o "$TMP/maxspeed_raw.osm.pbf"

echo "[maxspeed] exporting to GeoJSON + normalizing"
osmium export -f geojsonseq --overwrite "$TMP/maxspeed_raw.osm.pbf" \
    | python3 "$HERE/normalize_maxspeed.py" \
    > "$TMP/maxspeed.geojsonseq"

FEATURES="$(wc -l < "$TMP/maxspeed.geojsonseq" | tr -d ' ')"
echo "[maxspeed] $FEATURES normalized feature(s)"
[[ "$FEATURES" -gt 0 ]] || echo "[maxspeed] WARNING: 0 features — check bbox/extract"

echo "[maxspeed] tiling -> $OUT (z$MINZOOM-$MAXZOOM)"
tippecanoe --force \
    -o "$OUT" \
    -l maxspeed \
    --minimum-zoom="$MINZOOM" \
    --maximum-zoom="$MAXZOOM" \
    --drop-densest-as-needed \
    --extend-zooms-if-still-dropping \
    "$TMP/maxspeed.geojsonseq"

echo "[maxspeed] done: $OUT"
