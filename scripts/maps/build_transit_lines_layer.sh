#!/bin/bash
set -euo pipefail

# build_transit_lines_layer.sh — bake the `transit_lines` layer (P22) into a .pmtiles
#
# Extracts OSM rail/subway/tram/light_rail/monorail geometry and tiles it into a
# single-layer PMTiles file, so the app's transit-line highlight reads it from the
# basemap with no runtime Overpass query.
#
# Source layer produced:  transit_lines  (attr: kind, name?, ref?, colour?, osm_id)
#   kind ∈ { rail, subway, light_rail, tram, monorail, train }
#
# TWO SOURCES, and each can only produce some of the kinds. Railway WAYS give
# rail/subway/light_rail/tram/monorail (narrow_gauge folds into rail); route
# RELATIONS give subway/tram/light_rail/train/monorail. So `rail` is only reachable
# from a way and `train` only from a relation, and `route=bus` is in neither map --
# this layer is the rail-like modes the highlight styles. `railway` wins over
# `route`: a way carrying both is track, and the route is a service over it.
#
# TWO ENGINES. `--engine rust` (the default) is cargo-only: `osm_extract` reads
# ways AND relations straight out of the .osm.pbf and `tile_lines` tiles them.
# `--engine legacy` is the original chain, which needed osmium for the ways,
# GDAL's `ogr2ogr … multilinestrings` for the relations, and
# normalize_transit_lines.py to unpack the `other_tags` HSTORE that GDAL folded the
# relation's tags into. Reading relations directly makes all of that moot -- the
# GDAL dependency and the HSTORE parsing both disappear.
#
# ONE SCHEMA CHANGE. A relation's `osm_id` is now `relation/9001`; the GDAL path
# emitted a bare `9001` while ways came through as `way/5001`. That disagreement
# was an artefact of the toolchain, so the rust engine uses `<kind>/<id>`
# throughout. `test/diff_geojsonseq.py` will report it on relation features.
#
# Relation geometry is an unordered MultiLineString of the member ways, deliberately
# unstitched: branches, loops and split platforms mean a route's ways often do not
# form one continuous path, and the layer's contract allows the multi- form.
#
# Usage:
#   ./build_transit_lines_layer.sh --pbf planet.osm.pbf --out transit_lines.pmtiles
#   ./build_transit_lines_layer.sh --pbf planet.osm.pbf --bbox -122.6,37.2,-121.7,37.9 \
#       --out transit_lines.pmtiles
#
# Options:
#   --pbf FILE     Input OSM .pbf (required)
#   --out FILE     Output .pmtiles (default: transit_lines.pmtiles)
#   --bbox BOX     Optional "minlon,minlat,maxlon,maxlat" metro extract (dry runs)
#   --minzoom N    tiler minzoom (default 8)
#   --maxzoom N    tiler maxzoom (default 16)
#   --engine E     rust|legacy (default rust)
#   --geojson-out F  also keep the intermediate geojsonseq here (for the
#                    differential harness, which needs both engines' output)
#   --keep-tmp     Don't delete intermediate files
#
# Tools required: cargo. `--engine legacy` additionally needs osmium, tippecanoe
# and python3, plus ogr2ogr (GDAL) for the route-relation half -- without it that
# engine silently produces railway ways only.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="transit_lines.pmtiles"
BBOX=""
MINZOOM=8
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
        -h|--help) sed -n '4,55p' "$0" | sed 's/^# \?//'; exit 0 ;;
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

GEOJSON="${GEOJSON_OUT:-$TMP/transit_lines.geojsonseq}"
[[ -z "$GEOJSON_OUT" ]] || mkdir -p "$(dirname "$GEOJSON_OUT")"

if [[ "$ENGINE" == "rust" ]]; then
    echo "[transit_lines] extracting railway ways + route relations -> $GEOJSON (osm_extract)"
    # Three passes over the PBF in one process: relations decide which ways matter,
    # ways decide which node coordinates matter, nodes supply them.
    EXTRACT_ARGS=("$PBF" --layer transit_lines --out "$GEOJSON")
    [[ -n "$BBOX" ]] && EXTRACT_ARGS+=(--bbox "$BBOX")
    cargo run --release --quiet --manifest-path "$HERE/osm_ingest/Cargo.toml" \
        --bin osm_extract -- "${EXTRACT_ARGS[@]}"
else
    SRC="$PBF"
    if [[ -n "$BBOX" ]]; then
        echo "[transit_lines] extracting metro bbox $BBOX"
        osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
        SRC="$TMP/metro.osm.pbf"
    fi

    echo "[transit_lines] filtering railway ways + route relations"
    osmium tags-filter --overwrite "$SRC" \
        w/railway=rail,subway,light_rail,tram,monorail,narrow_gauge \
        r/route=subway,tram,light_rail,train,monorail \
        -o "$TMP/transit_raw.osm.pbf"

    echo "[transit_lines] exporting ways to GeoJSON"
    osmium export -f geojsonseq --overwrite "$TMP/transit_raw.osm.pbf" > "$TMP/ways.geojsonseq"

    # Relations need GDAL: osmium export cannot assemble relation geometry.
    : > "$TMP/relations.geojsonseq"
    if command -v ogr2ogr >/dev/null; then
        ogr2ogr -f GeoJSONSeq "$TMP/relations.geojsonseq" \
            "$TMP/transit_raw.osm.pbf" multilinestrings 2>/dev/null \
            || echo "[transit_lines] WARNING: ogr2ogr relation export failed; railway ways only"
    else
        echo "[transit_lines] WARNING: ogr2ogr (GDAL) not found; route-relation colour omitted (railway ways only)"
    fi

    echo "[transit_lines] normalizing kinds"
    cat "$TMP/ways.geojsonseq" "$TMP/relations.geojsonseq" \
        | python3 "$HERE/normalize_transit_lines.py" \
        > "$GEOJSON"
fi

FEATURES="$(wc -l < "$GEOJSON" | tr -d ' ')"
echo "[transit_lines] $FEATURES feature(s)"
[[ "$FEATURES" -gt 0 ]] || echo "[transit_lines] WARNING: 0 features — check bbox/extract"

echo "[transit_lines] tiling -> $OUT (z$MINZOOM-$MAXZOOM, engine: $ENGINE)"
if [[ "$ENGINE" == "rust" ]]; then
    cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
        --bin tile_lines -- \
        --geojson "$GEOJSON" \
        --out "$OUT" \
        --layer transit_lines \
        --minzoom "$MINZOOM" \
        --maxzoom "$MAXZOOM"
else
    tippecanoe --force \
        -o "$OUT" \
        -l transit_lines \
        --minimum-zoom="$MINZOOM" \
        --maximum-zoom="$MAXZOOM" \
        --drop-densest-as-needed \
        --extend-zooms-if-still-dropping \
        "$GEOJSON"
fi

echo "[transit_lines] done: $OUT"
