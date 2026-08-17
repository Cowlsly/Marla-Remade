#!/bin/bash
set -euo pipefail

# build_transit_lines_layer.sh — bake the `transit_lines` layer (OSM rail/transit)
#
# Produces a line-geometry PMTiles layer of OSM transit lines so the app's
# transit-line highlight (P22) can render/highlight them via queryRenderedFeatures
# on v5.pmtiles — no runtime Overpass query.
#
# Source layer produced:  transit_lines
#   geometry : LineString / MultiLineString
#   attrs    : kind, name?, ref?, colour?, osm_id
#   kind ∈ { rail, subway, light_rail, tram, monorail, train }
#
# Two OSM inputs are combined:
#   * railway WAYS       railway ∈ {rail,subway,light_rail,tram,monorail,narrow_gauge}
#                        (exported by osmium; kind derived from `railway`)
#   * route RELATIONS    route ∈ {subway,tram,light_rail,train,monorail}
#                        (assembled by ogr2ogr's OSM `multilinestrings` layer, which
#                        carries the relation `colour`; kind derived from `route`)
# ogr2ogr is optional: if it is missing the relation colour is skipped and the
# layer is built from railway ways only (a WARNING is printed).
#
# Usage:
#   ./build_transit_lines_layer.sh --pbf planet.osm.pbf --out transit_lines.pmtiles
#   ./build_transit_lines_layer.sh --pbf planet.osm.pbf --bbox -122.6,37.2,-121.7,37.9 --out transit_lines.pmtiles
#
# Options:
#   --pbf FILE     Input OSM .pbf (required)
#   --out FILE     Output .pmtiles (default: transit_lines.pmtiles)
#   --bbox BOX     Optional "minlon,minlat,maxlon,maxlat" metro extract (dry runs)
#   --minzoom N    tippecanoe minzoom (default 9)
#   --maxzoom N    tippecanoe maxzoom (default 16)
#   --keep-tmp     Don't delete intermediate files
#
# Tools required: osmium (osmium-tool), tippecanoe, python3; ogr2ogr (GDAL) optional

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="transit_lines.pmtiles"
BBOX=""
MINZOOM=9
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
        -h|--help) sed -n '4,36p' "$0" | sed 's/^# \?//'; exit 0 ;;
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
    echo "[transit_lines] extracting metro bbox $BBOX"
    osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
    SRC="$TMP/metro.osm.pbf"
fi

echo "[transit_lines] filtering railway ways + route relations"
# Keep the railway ways we bake AND route relations (with their referenced
# members so both way and relation geometries assemble).
osmium tags-filter --overwrite "$SRC" \
    w/railway=rail,subway,light_rail,tram,monorail,narrow_gauge \
    r/route=subway,tram,light_rail,train,monorail \
    -o "$TMP/transit_raw.osm.pbf"

echo "[transit_lines] exporting railway ways (osmium)"
osmium export -f geojsonseq --overwrite "$TMP/transit_raw.osm.pbf" \
    > "$TMP/ways.geojsonseq"

# Route relations carry the line `colour`; osmium export does not emit linear
# relations as geometry, so use GDAL's OSM `multilinestrings` layer for those.
: > "$TMP/relations.geojsonseq"
if command -v ogr2ogr >/dev/null; then
    echo "[transit_lines] exporting route relations (ogr2ogr multilinestrings)"
    ogr2ogr -f GeoJSONSeq "$TMP/relations.geojsonseq" \
        "$TMP/transit_raw.osm.pbf" multilinestrings 2>/dev/null \
        || echo "[transit_lines] WARNING: ogr2ogr relation export failed; railway ways only"
else
    echo "[transit_lines] WARNING: ogr2ogr (GDAL) not found; route-relation colour omitted (railway ways only)"
fi

echo "[transit_lines] normalizing kinds + colour"
cat "$TMP/ways.geojsonseq" "$TMP/relations.geojsonseq" \
    | python3 "$HERE/normalize_transit_lines.py" \
    > "$TMP/transit_lines.geojsonseq"

FEATURES="$(wc -l < "$TMP/transit_lines.geojsonseq" | tr -d ' ')"
echo "[transit_lines] $FEATURES normalized feature(s)"
[[ "$FEATURES" -gt 0 ]] || echo "[transit_lines] WARNING: 0 features — check bbox/extract"

echo "[transit_lines] tiling -> $OUT (z$MINZOOM-$MAXZOOM)"
tippecanoe --force \
    -o "$OUT" \
    -l transit_lines \
    --minimum-zoom="$MINZOOM" \
    --maximum-zoom="$MAXZOOM" \
    --drop-densest-as-needed \
    --extend-zooms-if-still-dropping \
    "$TMP/transit_lines.geojsonseq"

echo "[transit_lines] done: $OUT"
