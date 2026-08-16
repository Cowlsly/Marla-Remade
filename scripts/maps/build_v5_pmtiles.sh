#!/bin/bash
set -euo pipefail

# build_v5_pmtiles.sh — build the custom v5.pmtiles for the maps app.
#
# v5.pmtiles = Protomaps base schema (unchanged, style.json-compatible)
#            + safety     (baked road-furniture: cameras/ALPR/stops/signals)
#            + maxspeed   (posted speed limits for the P5b MaxspeedSource)
#            + admin_country / admin_region / admin_city  (borders; replaces .fgb)
#
# This single file REPLACES both:
#   * https://data.vayunmathur.com/v4.pmtiles      (base tiles)
#   * maps/src/main/assets/admin0.fgb + admin1.fgb (border masks)
#
# Pipeline (each step is a standalone script in this dir):
#   1. build_base_layers.sh   -> base.pmtiles           (planetiler OR reuse v4)
#   2. build_safety_layer.sh  -> safety.pmtiles         (osmium + tippecanoe)
#   3. build_maxspeed_layer.sh-> maxspeed.pmtiles       (osmium + tippecanoe)
#   4. build_admin_layers.sh  -> admin_*.pmtiles        (Natural Earth/OSM + tippecanoe)
#   5. tile-join              -> v5.pmtiles             (merge all layers)
#
# Full planet build is the user's infra step (large + long). Prove correctness
# first with a metro dry run:
#   ./build_v5_pmtiles.sh --pbf norcal.osm.pbf --bbox -122.6,37.2,-121.7,37.9 \
#       --base-mode reuse --out v5-sf.pmtiles
#
# Planet build:
#   ./build_v5_pmtiles.sh --pbf planet.osm.pbf --base-mode build \
#       --base-jar protomaps-basemap-HEAD-with-deps.jar --out v5.pmtiles
#
# Options:
#   --pbf FILE        OSM extract for safety + city layers (required unless --skip-*)
#   --out FILE        final output (default v5.pmtiles)
#   --workdir DIR     scratch dir for intermediates (default ./v5_work)
#   --bbox BOX        metro bbox "minlon,minlat,maxlon,maxlat" (dry runs)
#   --base-mode M     build|reuse (passed to build_base_layers.sh; default reuse)
#   --base-jar FILE   protomaps basemap jar (base-mode build)
#   --base-area A     planetiler area name/path (base-mode build; default planet)
#   --skip-base       don't (re)build base; expects <workdir>/base.pmtiles present
#   --skip-safety     omit safety layer
#   --skip-maxspeed   omit maxspeed layer
#   --skip-admin      omit admin layers
#   --keep-work       keep intermediates
#
# Tools: tile-join + tippecanoe, osmium, ogr2ogr (GDAL), python3, and either
#        java+planetiler (base build) or curl/pmtiles (base reuse). See README.md.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="v5.pmtiles"
WORK="./v5_work"
BBOX=""
BASE_MODE="reuse"
BASE_JAR=""
BASE_AREA="planet"
SKIP_BASE=0
SKIP_SAFETY=0
SKIP_MAXSPEED=0
SKIP_ADMIN=0
KEEP_WORK=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --workdir) WORK="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --base-mode) BASE_MODE="$2"; shift 2 ;;
        --base-jar) BASE_JAR="$2"; shift 2 ;;
        --base-area) BASE_AREA="$2"; shift 2 ;;
        --skip-base) SKIP_BASE=1; shift ;;
        --skip-safety) SKIP_SAFETY=1; shift ;;
        --skip-maxspeed) SKIP_MAXSPEED=1; shift ;;
        --skip-admin) SKIP_ADMIN=1; shift ;;
        --keep-work) KEEP_WORK=1; shift ;;
        -h|--help) sed -n '4,60p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

command -v tile-join >/dev/null || { echo "ERROR: tile-join (tippecanoe pkg) not installed" >&2; exit 1; }
mkdir -p "$WORK"

INPUTS=()

# --- 1. base ---
BASE="$WORK/base.pmtiles"
if [[ "$SKIP_BASE" == "1" ]]; then
    [[ -f "$BASE" ]] || { echo "ERROR: --skip-base but $BASE missing" >&2; exit 1; }
    echo "[v5] using existing base $BASE"
else
    BASE_ARGS=(--mode "$BASE_MODE" --out "$BASE")
    [[ -n "$BBOX" ]] && BASE_ARGS+=(--bbox "$BBOX")
    [[ "$BASE_MODE" == "build" ]] && BASE_ARGS+=(--jar "$BASE_JAR" --area "$BASE_AREA")
    "$HERE/build_base_layers.sh" "${BASE_ARGS[@]}"
fi
INPUTS+=("$BASE")

# --- 2. safety ---
if [[ "$SKIP_SAFETY" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for safety layer (or --skip-safety)" >&2; exit 1; }
    SAFETY_ARGS=(--pbf "$PBF" --out "$WORK/safety.pmtiles")
    [[ -n "$BBOX" ]] && SAFETY_ARGS+=(--bbox "$BBOX")
    "$HERE/build_safety_layer.sh" "${SAFETY_ARGS[@]}"
    INPUTS+=("$WORK/safety.pmtiles")
fi

# --- 3. maxspeed ---
if [[ "$SKIP_MAXSPEED" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for maxspeed layer (or --skip-maxspeed)" >&2; exit 1; }
    MS_ARGS=(--pbf "$PBF" --out "$WORK/maxspeed.pmtiles")
    [[ -n "$BBOX" ]] && MS_ARGS+=(--bbox "$BBOX")
    "$HERE/build_maxspeed_layer.sh" "${MS_ARGS[@]}"
    INPUTS+=("$WORK/maxspeed.pmtiles")
fi

# --- 4. admin ---
if [[ "$SKIP_ADMIN" == "0" ]]; then
    ADMIN_ARGS=(--outdir "$WORK/admin")
    [[ -n "$PBF" ]] && ADMIN_ARGS+=(--pbf "$PBF")
    [[ -n "$BBOX" ]] && ADMIN_ARGS+=(--bbox "$BBOX")
    [[ -z "$PBF" ]] && ADMIN_ARGS+=(--no-city)
    "$HERE/build_admin_layers.sh" "${ADMIN_ARGS[@]}"
    for l in admin_country admin_region admin_city; do
        [[ -f "$WORK/admin/$l.pmtiles" ]] && INPUTS+=("$WORK/admin/$l.pmtiles")
    done
fi

# --- 5. merge ---
echo "[v5] merging ${#INPUTS[@]} source(s) -> $OUT"
printf '  + %s\n' "${INPUTS[@]}"
# --no-tile-size-limit: don't drop features when combining dense base + overlays.
tile-join --force --no-tile-size-limit -o "$OUT" "${INPUTS[@]}"

SIZE="$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT" 2>/dev/null || echo '?')"
echo "[v5] done: $OUT (${SIZE} bytes)"
echo ""
echo "Layers now in $OUT:"
echo "  base : earth landcover landuse water roads buildings boundaries pois places"
echo "  new  : safety maxspeed admin_country admin_region admin_city"
echo ""
echo "Publish (bump key to v5.pmtiles):"
echo "  R2_KEY=v5.pmtiles ./scripts/maps/vendor_pmtiles.sh --local $OUT --source $OUT"
echo "Then P13 updates style.json url -> pmtiles://https://data.vayunmathur.com/v5.pmtiles"

[[ "$KEEP_WORK" == "1" ]] || echo "(intermediates kept in $WORK; pass --keep-work to silence)"
