#!/bin/bash
set -euo pipefail

# build_admin_layers.sh — bake country/region/city admin borders into .pmtiles
#
# REPLACES the vendored FlatGeobuf assets maps/src/main/assets/admin0.fgb
# (countries, keyed by ISO_A2) and admin1.fgb (states/provinces, keyed by
# iso_3166_2), and adds a city layer. The normalized attributes keep those
# exact ISO keys so P13 can point CountryMap.kt at the PMTiles instead of .fgb
# with no attribute changes.
#
# Source layers produced (one .pmtiles each; merged later by build_v5_pmtiles.sh):
#   admin_country  admin_level=2  attr: name, name_en, ISO_A2, iso_a3
#   admin_region   admin_level=4  attr: name, name_en, iso_3166_2, country_iso
#   admin_city     admin_level=8  attr: name, name_en
#
# Data sources (documented choice, see README.md):
#   country/region -> Natural Earth 10m (matches .fgb schema, clean geometry)
#   city           -> OSM boundary=administrative admin_level=8 (NE has no cities)
#
# Usage:
#   ./build_admin_layers.sh --outdir out/ [--pbf planet.osm.pbf] [--ne-dir ne/] \
#                           [--bbox minlon,minlat,maxlon,maxlat] [--no-city]
#
# Tools required: ogr2ogr (GDAL), tippecanoe, python3; osmium + a --pbf for cities.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTDIR="."
PBF=""
NE_DIR="ne"
BBOX=""
WITH_CITY=1

NE_COUNTRIES_URL="https://naciscdn.org/naturalearth/10m/cultural/ne_10m_admin_0_countries.zip"
NE_REGIONS_URL="https://naciscdn.org/naturalearth/10m/cultural/ne_10m_admin_1_states_provinces.zip"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --outdir) OUTDIR="$2"; shift 2 ;;
        --pbf) PBF="$2"; shift 2 ;;
        --ne-dir) NE_DIR="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --no-city) WITH_CITY=0; shift ;;
        -h|--help) sed -n '4,32p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

command -v ogr2ogr    >/dev/null || { echo "ERROR: ogr2ogr (GDAL) not installed" >&2; exit 1; }
command -v tippecanoe >/dev/null || { echo "ERROR: tippecanoe not installed" >&2; exit 1; }
mkdir -p "$OUTDIR" "$NE_DIR"

# --- Fetch Natural Earth shapefiles if missing ---
fetch_ne() {
    local url="$1" zip="$2" shp="$3"
    if [[ -f "$NE_DIR/$shp" ]]; then return; fi
    echo "[admin] downloading $(basename "$url")"
    curl -fL --retry 3 -o "$NE_DIR/$zip" "$url"
    ( cd "$NE_DIR" && unzip -o "$zip" >/dev/null )
}
fetch_ne "$NE_COUNTRIES_URL" ne_admin0.zip ne_10m_admin_0_countries.shp
fetch_ne "$NE_REGIONS_URL"   ne_admin1.zip ne_10m_admin_1_states_provinces.shp

# ogr2ogr optional bbox clip: -spat minx miny maxx maxy
SPAT=()
if [[ -n "$BBOX" ]]; then
    IFS=',' read -r X0 Y0 X1 Y1 <<< "$BBOX"
    SPAT=(-spat "$X0" "$Y0" "$X1" "$Y1")
fi

build_layer() {  # level  layer_name  minzoom  maxzoom  input_geojson
    local level="$1" layer="$2" minz="$3" maxz="$4" geojson="$5"
    echo "[admin] tiling $layer (z$minz-$maxz)"
    # Admin polygons must stay whole enough to reassemble for the dimming mask,
    # so we disable size/feature limits and avoid aggressive dropping.
    tippecanoe --force \
        -o "$OUTDIR/$layer.pmtiles" \
        -l "$layer" \
        --minimum-zoom="$minz" \
        --maximum-zoom="$maxz" \
        --no-tile-size-limit \
        --no-feature-limit \
        --coalesce-densest-as-needed \
        --detect-shared-borders \
        --simplification=4 \
        "$geojson"
}

echo "[admin] country (Natural Earth admin_0)"
ogr2ogr -f GeoJSON "${SPAT[@]}" /vsistdout/ "$NE_DIR/ne_10m_admin_0_countries.shp" \
    | python3 "$HERE/normalize_admin.py" --level country > "$OUTDIR/admin_country.geojsonseq"
build_layer 2 admin_country 0 8 "$OUTDIR/admin_country.geojsonseq"

echo "[admin] region (Natural Earth admin_1)"
ogr2ogr -f GeoJSON "${SPAT[@]}" /vsistdout/ "$NE_DIR/ne_10m_admin_1_states_provinces.shp" \
    | python3 "$HERE/normalize_admin.py" --level region > "$OUTDIR/admin_region.geojsonseq"
build_layer 4 admin_region 3 9 "$OUTDIR/admin_region.geojsonseq"

if [[ "$WITH_CITY" == "1" ]]; then
    if [[ -z "$PBF" ]]; then
        echo "[admin] --no-city not set but no --pbf given; skipping city layer" >&2
    else
        command -v osmium >/dev/null || { echo "ERROR: osmium needed for city layer" >&2; exit 1; }
        [[ -f "$PBF" ]] || { echo "ERROR: pbf not found: $PBF" >&2; exit 1; }
        TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
        SRC="$PBF"
        if [[ -n "$BBOX" ]]; then
            osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
            SRC="$TMP/metro.osm.pbf"
        fi
        echo "[admin] city (OSM admin_level=8 boundaries)"
        # Keep boundary relations/ways; osmium export assembles the polygon areas
        # from closed ways + multipolygon/boundary relations automatically.
        osmium tags-filter --overwrite "$SRC" \
            r/boundary=administrative w/boundary=administrative \
            -o "$TMP/admin_raw.osm.pbf"
        osmium export -f geojsonseq --overwrite "$TMP/admin_raw.osm.pbf" \
            | python3 "$HERE/normalize_admin.py" --level city > "$OUTDIR/admin_city.geojsonseq"
        build_layer 8 admin_city 6 12 "$OUTDIR/admin_city.geojsonseq"
    fi
fi

echo "[admin] done -> $OUTDIR/admin_{country,region,city}.pmtiles"
