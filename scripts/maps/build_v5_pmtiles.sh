#!/bin/bash
set -euo pipefail

# build_v5_pmtiles.sh — build the custom v5.pmtiles for the maps app.
#
# v5.pmtiles = Protomaps base schema (unchanged, style.json-compatible)
#            + safety     (baked road-furniture: cameras/ALPR/stops/signals)
#            + maxspeed   (posted speed limits for the P5b MaxspeedSource)
#            + transit_lines (OSM rail/subway/tram/… lines for the P22 highlight)
#            + ma_pois    (OUR baked OSM POI layer: placement/name/type from OSM)
#            + admin_country / admin_region / admin_city  (borders; replaces .fgb)
#
# The ma_pois step ALSO emits two compact side files next to --out
# (poi_names.bin + poi_index.bin) that the app mmaps for POI lookup — see
# build_pois_layer.sh / osm_ingest / README for the exact formats.
#
# This single file REPLACES both:
#   * https://data.vayunmathur.com/v4.pmtiles      (base tiles)
#   * maps/src/main/assets/admin0.fgb + admin1.fgb (border masks)
#
# Pipeline (each step is a standalone script in this dir):
#   1. build_base_layers.sh   -> base.pmtiles           (planetiler OR reuse v4)
#   2. build_safety_layer.sh  -> safety.pmtiles         (osmium + tippecanoe)
#   3. build_maxspeed_layer.sh-> maxspeed.pmtiles       (osmium + tippecanoe)
#   4. build_transit_lines_layer.sh -> transit_lines.pmtiles (osmium/ogr2ogr + tippecanoe)
#   5. build_pois_layer.sh    -> ma_pois.pmtiles + poi_names.bin + poi_index.bin
#   6. build_admin_layers.sh  -> admin_*.pmtiles        (Natural Earth/OSM + tippecanoe)
#   7. tile-join              -> v5.pmtiles             (merge all layers)
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
#   --skip-transit-lines omit transit_lines layer
#   --skip-pois       omit ma_pois layer + poi_names.bin/poi_index.bin side files
#   --skip-admin      omit admin layers
#   --publish         after a successful build, upload $OUT to R2 (publish_r2.sh;
#                     reads R2_ENDPOINT/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY from env)
#   --publish-key K   remote object key when publishing (default: basename of --out)
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
SKIP_TRANSIT_LINES=0
SKIP_POIS=0
SKIP_ADMIN=0
KEEP_WORK=0
PUBLISH=0
PUBLISH_KEY=""

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
        --skip-transit-lines) SKIP_TRANSIT_LINES=1; shift ;;
        --skip-pois) SKIP_POIS=1; shift ;;
        --skip-admin) SKIP_ADMIN=1; shift ;;
        --keep-work) KEEP_WORK=1; shift ;;
        --publish) PUBLISH=1; shift ;;
        --publish-key) PUBLISH_KEY="$2"; shift 2 ;;
        -h|--help) sed -n '4,52p' "$0" | sed 's/^# \?//'; exit 0 ;;
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

# --- 4. transit_lines ---
if [[ "$SKIP_TRANSIT_LINES" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for transit_lines layer (or --skip-transit-lines)" >&2; exit 1; }
    TL_ARGS=(--pbf "$PBF" --out "$WORK/transit_lines.pmtiles")
    [[ -n "$BBOX" ]] && TL_ARGS+=(--bbox "$BBOX")
    "$HERE/build_transit_lines_layer.sh" "${TL_ARGS[@]}"
    INPUTS+=("$WORK/transit_lines.pmtiles")
fi

# --- 5. ma_pois (our baked OSM POI layer + poi_names.bin/poi_index.bin) ---
if [[ "$SKIP_POIS" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for ma_pois layer (or --skip-pois)" >&2; exit 1; }
    OUTDIR="$(cd "$(dirname "$OUT")" && pwd)"
    POIS_ARGS=(--pbf "$PBF" --out "$WORK/ma_pois.pmtiles" \
        --names-out "$OUTDIR/poi_names.bin" --index-out "$OUTDIR/poi_index.bin")
    [[ -n "$BBOX" ]] && POIS_ARGS+=(--bbox "$BBOX")
    "$HERE/build_pois_layer.sh" "${POIS_ARGS[@]}"
    INPUTS+=("$WORK/ma_pois.pmtiles")
fi

# --- 6. admin ---
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

# --- 7. merge ---
echo "[v5] merging ${#INPUTS[@]} source(s) -> $OUT"
printf '  + %s\n' "${INPUTS[@]}"
# --no-tile-size-limit: don't drop features when combining dense base + overlays.
tile-join --force --no-tile-size-limit -o "$OUT" "${INPUTS[@]}"

SIZE="$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT" 2>/dev/null || echo '?')"
echo "[v5] done: $OUT (${SIZE} bytes)"
echo ""
echo "Layers now in $OUT:"
echo "  base : earth landcover landuse water roads buildings boundaries pois places"
echo "  new  : safety maxspeed transit_lines ma_pois admin_country admin_region admin_city"
echo ""
if [[ "$SKIP_POIS" == "0" ]]; then
    echo "POI side files (emitted beside $OUT for the app to mmap):"
    echo "  $OUTDIR/poi_names.bin   (deduped NUL-terminated UTF-8 name table)"
    echo "  $OUTDIR/poi_index.bin   (flat 14-byte records: lat_e7,lon_e7,name_off,type)"
    echo ""
fi

if [[ "$PUBLISH" == "1" ]]; then
    PUB_ARGS=("$OUT")
    [[ -n "$PUBLISH_KEY" ]] && PUB_ARGS+=(--key "$PUBLISH_KEY")
    echo "[v5] publishing to R2 (creds from env: R2_ENDPOINT/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY)"
    "$HERE/publish_r2.sh" "${PUB_ARGS[@]}"
else
    echo "Publish (creds from environment variables — see README 'Publishing to R2'):"
    echo "  export R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com"
    echo "  export R2_ACCESS_KEY_ID=...  R2_SECRET_ACCESS_KEY=..."
    echo "  ./scripts/maps/publish_r2.sh $OUT --key v5.pmtiles"
    echo "  # or re-run this build with --publish"
    echo "Then P13 updates style.json url -> pmtiles://https://data.vayunmathur.com/v5.pmtiles"
fi

[[ "$KEEP_WORK" == "1" ]] || echo "(intermediates kept in $WORK; pass --keep-work to silence)"
