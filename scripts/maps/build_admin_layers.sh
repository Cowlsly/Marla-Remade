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
# THE CITY LAYER HAS TWO ENGINES. `--engine-city rust` (the default) is cargo-only:
# `osm_extract` reads the boundary relations out of the .osm.pbf and assembles their
# member ways into rings itself, then `tile_polygons` tiles them. `--engine-city
# legacy` is the original `osmium export | normalize_admin.py | tippecanoe` chain,
# where libosmium did the ring assembly.
#
# THE COUNTRY AND REGION LAYERS HAVE NO RUST ENGINE, and are not going to get one
# from OSM: they come from Natural Earth shapefiles, which OSM cannot supply. That
# is the third input the README's caveats section describes. They still need
# ogr2ogr, tippecanoe and python3.
#
# Usage:
#   ./build_admin_layers.sh --outdir out/ [--pbf planet.osm.pbf] [--ne-dir ne/] \
#                           [--bbox minlon,minlat,maxlon,maxlat] [--no-city] \
#                           [--engine-city rust|legacy] [--only-city]
#
# Options:
#   --outdir DIR       Where the .pmtiles land (default .)
#   --pbf FILE         OSM extract, required for the city layer
#   --ne-dir DIR       Natural Earth download cache (default ne)
#   --bbox BOX         "minlon,minlat,maxlon,maxlat"
#   --no-city          Omit the city layer
#   --only-city        Build ONLY the city layer, so a cargo-only box can do the
#                      one admin layer it is able to
#   --engine-city E    rust|legacy (default rust)
#
# Tools required: cargo. The country and region layers additionally need ogr2ogr
# (GDAL), tippecanoe and python3; `--engine-city legacy` needs osmium, tippecanoe
# and python3 as well.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTDIR="."
PBF=""
NE_DIR="ne"
BBOX=""
WITH_CITY=1
ONLY_CITY=0
ENGINE_CITY="rust"

NE_COUNTRIES_URL="https://naciscdn.org/naturalearth/10m/cultural/ne_10m_admin_0_countries.zip"
NE_REGIONS_URL="https://naciscdn.org/naturalearth/10m/cultural/ne_10m_admin_1_states_provinces.zip"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --outdir) OUTDIR="$2"; shift 2 ;;
        --pbf) PBF="$2"; shift 2 ;;
        --ne-dir) NE_DIR="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --no-city) WITH_CITY=0; shift ;;
        --only-city) ONLY_CITY=1; shift ;;
        --engine-city) ENGINE_CITY="$2"; shift 2 ;;
        -h|--help) sed -n '4,49p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

case "$ENGINE_CITY" in
    rust|legacy) : ;;
    *) echo "ERROR: --engine-city must be rust|legacy (got '$ENGINE_CITY')" >&2; exit 1 ;;
esac
command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }
if [[ "$ONLY_CITY" == "0" ]]; then
    command -v ogr2ogr    >/dev/null || { echo "ERROR: ogr2ogr (GDAL) needed for the country/region layers (or --only-city)" >&2; exit 1; }
    command -v tippecanoe >/dev/null || { echo "ERROR: tippecanoe needed for the country/region layers (or --only-city)" >&2; exit 1; }
    command -v python3    >/dev/null || { echo "ERROR: python3 needed for the country/region layers (or --only-city)" >&2; exit 1; }
fi
mkdir -p "$OUTDIR" "$NE_DIR"

# --- Fetch Natural Earth shapefiles if missing ---
fetch_ne() {
    local url="$1" zip="$2" shp="$3"
    if [[ -f "$NE_DIR/$shp" ]]; then return; fi
    echo "[admin] downloading $(basename "$url")"
    curl -fL --retry 3 -o "$NE_DIR/$zip" "$url"
    ( cd "$NE_DIR" && unzip -o "$zip" >/dev/null )
}
if [[ "$ONLY_CITY" == "0" ]]; then
    fetch_ne "$NE_COUNTRIES_URL" ne_admin0.zip ne_10m_admin_0_countries.shp
    fetch_ne "$NE_REGIONS_URL"   ne_admin1.zip ne_10m_admin_1_states_provinces.shp
fi

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

if [[ "$ONLY_CITY" == "0" ]]; then
    echo "[admin] country (Natural Earth admin_0)"
    ogr2ogr -f GeoJSON "${SPAT[@]}" /vsistdout/ "$NE_DIR/ne_10m_admin_0_countries.shp" \
        | python3 "$HERE/normalize_admin.py" --level country > "$OUTDIR/admin_country.geojsonseq"
    build_layer 2 admin_country 0 8 "$OUTDIR/admin_country.geojsonseq"

    echo "[admin] region (Natural Earth admin_1)"
    ogr2ogr -f GeoJSON "${SPAT[@]}" /vsistdout/ "$NE_DIR/ne_10m_admin_1_states_provinces.shp" \
        | python3 "$HERE/normalize_admin.py" --level region > "$OUTDIR/admin_region.geojsonseq"
    build_layer 4 admin_region 3 9 "$OUTDIR/admin_region.geojsonseq"
fi

if [[ "$WITH_CITY" == "1" ]]; then
    if [[ -z "$PBF" ]]; then
        echo "[admin] --no-city not set but no --pbf given; skipping city layer" >&2
    else
        [[ -f "$PBF" ]] || { echo "ERROR: pbf not found: $PBF" >&2; exit 1; }
        GEOJSON="$OUTDIR/admin_city.geojsonseq"
        if [[ "$ENGINE_CITY" == "rust" ]]; then
            echo "[admin] city (OSM admin_level=8 boundaries, osm_extract)"
            # Three passes over the PBF in one process, with our own ring assembler:
            # relations decide which ways matter, ways give ordered node refs, nodes
            # give coordinates. Rings that will not close are dropped and reported.
            EXTRACT_ARGS=("$PBF" --layer admin_city --out "$GEOJSON")
            [[ -n "$BBOX" ]] && EXTRACT_ARGS+=(--bbox "$BBOX")
            cargo run --release --quiet --manifest-path "$HERE/osm_ingest/Cargo.toml" \
                --bin osm_extract -- "${EXTRACT_ARGS[@]}"
            echo "[admin] tiling admin_city (z6-12, tile_polygons)"
            # No per-tile byte budget: an admin polygon must stay whole enough to
            # reassemble for the dimming mask, which is why the legacy path passed
            # --no-tile-size-limit --no-feature-limit.
            cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
                --bin tile_polygons -- \
                --geojson "$GEOJSON" \
                --out "$OUTDIR/admin_city.pmtiles" \
                --layer admin_city \
                --minzoom 6 --maxzoom 12 \
                --simplification 4 \
                --max-tile-bytes 100000000
        else
            command -v osmium     >/dev/null || { echo "ERROR: --engine-city legacy needs osmium" >&2; exit 1; }
            command -v tippecanoe >/dev/null || { echo "ERROR: --engine-city legacy needs tippecanoe" >&2; exit 1; }
            command -v python3    >/dev/null || { echo "ERROR: --engine-city legacy needs python3" >&2; exit 1; }
            TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
            SRC="$PBF"
            if [[ -n "$BBOX" ]]; then
                osmium extract --overwrite -b "$BBOX" "$PBF" -o "$TMP/metro.osm.pbf"
                SRC="$TMP/metro.osm.pbf"
            fi
            echo "[admin] city (OSM admin_level=8 boundaries, osmium)"
            # Keep boundary relations/ways; osmium export assembles the polygon areas
            # from closed ways + multipolygon/boundary relations automatically.
            osmium tags-filter --overwrite "$SRC" \
                r/boundary=administrative w/boundary=administrative \
                -o "$TMP/admin_raw.osm.pbf"
            osmium export -f geojsonseq --overwrite "$TMP/admin_raw.osm.pbf" \
                | python3 "$HERE/normalize_admin.py" --level city > "$GEOJSON"
            build_layer 8 admin_city 6 12 "$GEOJSON"
        fi
    fi
fi

echo "[admin] done -> $OUTDIR/admin_{country,region,city}.pmtiles"
