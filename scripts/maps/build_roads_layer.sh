#!/bin/bash
set -euo pipefail

# build_roads_layer.sh — bake the `roads` layer into a .pmtiles
#
# Extracts road geometry from OSM ways with the attributes the app needs to draw
# and label roads itself — class, speed, lanes, turn lanes, width and the
# bridge/tunnel/layer draw-order flags — and tiles it into a single-layer PMTiles
# file.
#
# Source layer produced:  roads
#   class                 number 1-15, `osm_ingest::tags::get_hw_id`'s road type.
#                         A `*_link` ramp carries its PARENT's class -- get_hw_id has
#                         no id for one and cannot be given one, since those numbers
#                         are an on-disk contract with the routing graph.
#   maxspeed?             the OSM tag verbatim ("25 mph", "none", "DE:urban")
#   maxspeed_kmh?         the same limit as a number, ABSENT when there isn't one
#   lanes?                number
#   turn_lanes_forward?   `|`-separated decimal LANE_* masks, one per lane
#   turn_lanes_backward?  the same, for the reverse direction
#   oneway?               1 when `oneway=yes`
#   width?                metres
#   bridge? tunnel?       1 when set
#   layer?                signed, absent at ground level
#   osm_id                "way/123"
#
# THIS LAYER RETIRES `maxspeed`. That layer carried one property and a copy of
# every road's geometry to hang it on; this one carries the same geometry once and
# answers the rendering questions too. The app now draws roads from this layer
# (`RoadsLayer.kt`) above z11 and reads the posted limit out of the same features
# (`queryPostedLimit`); `MaxspeedOverlay.kt` is gone.
#
# SPEED IS CARRIED TWICE, ON PURPOSE. `maxspeed` is the raw tag and `maxspeed_kmh`
# is the parsed km/h number. The number is what a style expression can compare
# against without parsing a string per feature; the raw value is the only thing
# that survives `maxspeed=DE:urban` (the parser answers 0 for every implicit
# country scheme) and the only record of whether a limit was authored in mph, which
# decides the unit the speed badge renders in. `maxspeed_kmh` is omitted rather
# than zeroed when there is no numeric limit, so `none` never reads as 0 km/h.
#
# z11-16, NOT z6-16. Base roads keep z0-10 and this layer takes over above them, so
# there is a clean handover rather than a gap. Replacing base roads outright needs
# per-feature min-zoom gating, which does not exist: without it a z6 all-classes planet
# roads layer is neither small nor correct. See scripts/maps/README.md.
#
# The tiler runs with --stream. `pyramid::build_archive` holds the whole input, a second
# projected copy of every geometry and a whole zoom's tile map, all proportional to the
# input BYTES -- and this layer is every road on the planet at one zoom deeper than
# maxspeed, whose archive is already 8.3 GB. --stream spills to disk instead, so peak
# memory tracks the tile COUNT. The output is byte-identical either way.
#
# Usage:
#   ./build_roads_layer.sh --pbf planet.osm.pbf --out roads.pmtiles
#   ./build_roads_layer.sh --pbf planet.osm.pbf --bbox -122.6,37.2,-121.7,37.9 --out roads.pmtiles
#
# Options:
#   --pbf FILE     Input OSM .pbf (required)
#   --out FILE     Output .pmtiles (default: roads.pmtiles)
#   --bbox BOX     Optional "minlon,minlat,maxlon,maxlat" metro extract (dry runs)
#   --minzoom N    tiler minzoom (default 11)
#   --maxzoom N    tiler maxzoom (default 16)
#   --spill-dir D  put the tiler's spill somewhere other than beside --out. It is
#                  read once per zoom and can reach tens of GB, so keep it off a
#                  network or /mnt/c mount even when the output lives there.
#   --geojson-out F  also keep the intermediate geojsonseq here
#   --keep-tmp     Don't delete intermediate files
#
# Tools required: cargo. Nothing else — there is no legacy engine, because there
# was never a `normalize_roads.py` to be faithful to.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="roads.pmtiles"
BBOX=""
MINZOOM=11
MAXZOOM=16
GEOJSON_OUT=""
SPILL_DIR=""
KEEP_TMP=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --minzoom) MINZOOM="$2"; shift 2 ;;
        --maxzoom) MAXZOOM="$2"; shift 2 ;;
        --spill-dir) SPILL_DIR="$2"; shift 2 ;;
        --geojson-out) GEOJSON_OUT="$2"; shift 2 ;;
        --keep-tmp) KEEP_TMP=1; shift ;;
        -h|--help) sed -n '4,65p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

[[ -n "$PBF" ]] || { echo "ERROR: --pbf required" >&2; exit 1; }
[[ -f "$PBF" ]] || { echo "ERROR: pbf not found: $PBF" >&2; exit 1; }
(( MINZOOM <= MAXZOOM )) || { echo "ERROR: --minzoom above --maxzoom" >&2; exit 1; }
command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }

TMP="$(mktemp -d)"
cleanup() { [[ "$KEEP_TMP" == "1" ]] || rm -rf "$TMP"; }
trap cleanup EXIT

GEOJSON="${GEOJSON_OUT:-$TMP/roads.geojsonseq}"
[[ -z "$GEOJSON_OUT" ]] || mkdir -p "$(dirname "$GEOJSON_OUT")"

echo "[roads] extracting road ways -> $GEOJSON (osm_extract)"
EXTRACT_ARGS=("$PBF" --layer roads --out "$GEOJSON")
[[ -n "$BBOX" ]] && EXTRACT_ARGS+=(--bbox "$BBOX")
cargo run --release --quiet --manifest-path "$HERE/osm_ingest/Cargo.toml" \
    --bin osm_extract -- "${EXTRACT_ARGS[@]}"

FEATURES="$(wc -l < "$GEOJSON" | tr -d ' ')"
echo "[roads] $FEATURES feature(s)"
[[ "$FEATURES" -gt 0 ]] || echo "[roads] WARNING: 0 features — check bbox/extract"

echo "[roads] tiling -> $OUT (z$MINZOOM-$MAXZOOM, streaming)"
# Beside the output unless told otherwise, matching what tile_lines defaults to.
TILE_SPILL="${SPILL_DIR:-$OUT.spill}"
mkdir -p "$TILE_SPILL"
cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
    --bin tile_lines -- \
    --geojson "$GEOJSON" \
    --out "$OUT" \
    --layer roads \
    --minzoom "$MINZOOM" \
    --maxzoom "$MAXZOOM" \
    --stream \
    --spill-dir "$TILE_SPILL"
echo "[roads] done: $OUT"
