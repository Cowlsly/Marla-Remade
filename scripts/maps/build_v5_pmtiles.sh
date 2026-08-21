#!/bin/bash
set -euo pipefail

# build_v5_pmtiles.sh — build the custom v5.pmtiles for the maps app.
#
# v5.pmtiles = Protomaps base schema (unchanged, style.json-compatible)
#            + safety     (baked road-furniture: cameras/ALPR/stops/signals)
#            + maxspeed   (posted speed limits for the P5b MaxspeedSource)
#            + transit_lines (OSM rail/subway/tram/… lines for the P22 highlight)
#            + ma_pois    (OUR baked OSM POI layer: placement/name/type from OSM)
#            + transit_stops (GTFS stop pins + their MOTIS ids, replacing the
#                          removed /api/v1/map/stops per-viewport fetch)
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
#   7. build_transit_stops_layer.sh -> transit_stops.pmtiles (GTFS, cargo-only)
#   8. tile_join              -> v5.pmtiles             (merge all layers)
#
# Steps 7 and 8 are cargo-only (scripts/maps/tile_build), so they need neither
# tippecanoe nor osmium. The rest still do -- see README.md.
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
#   --gtfs-manifest F feeds.manifest for the transit_stops layer (`name=dir[=motis_prefix]`
#                     per line, as build_ca_transit.ps1 writes). Without it the
#                     transit_stops step is skipped -- this layer takes GTFS dirs,
#                     not --pbf.
#   --base-mode M     build|reuse (passed to build_base_layers.sh; default reuse)
#   --base-jar FILE   protomaps basemap jar (base-mode build)
#   --base-area A     planetiler area name/path (base-mode build; default planet)
#   --base-source URL published archive to reuse (base-mode reuse; default is
#                     build_base_layers.sh's own)
#   --extra-layer F   fold an already-built .pmtiles into the final merge
#                     (repeatable). Lets a caller that built a layer itself skip
#                     the matching step here instead of building it twice.
#   --engine-base E       rust|legacy per-layer engine. `safety`, `maxspeed`,
#   --engine-safety E     `transit_lines` and `admin-city` default to rust
#   --engine-maxspeed E   (cargo-only); the rest default to legacy, and asking for
#   --engine-transit-lines E  rust before a layer is ported is an error rather than
#   --engine-admin E          a silent no-op, so a rollback is always one flag.
#   --engine-admin-city E     `admin-country`/`admin-region` come from Natural
#                             Earth and will never have a rust engine.
#   --dry-run         print each step's command instead of running it
#   --skip-base       don't (re)build base; expects <workdir>/base.pmtiles present
#   --skip-safety     omit safety layer
#   --skip-maxspeed   omit maxspeed layer
#   --skip-transit-lines omit transit_lines layer
#   --skip-pois       omit ma_pois layer + poi_names.bin/poi_index.bin side files
#   --skip-admin      omit admin layers
#   --skip-transit-stops omit transit_stops layer
#   --publish         after a successful build, upload $OUT to R2 (publish_r2.sh;
#                     reads R2_ENDPOINT/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY from env)
#   --publish-key K   remote object key when publishing (default: basename of --out)
#   --keep-work       keep intermediates
#
# Tools: osmium, ogr2ogr (GDAL), python3, tippecanoe (base/safety/maxspeed/
#        transit_lines/pois/admin layers), and either java+planetiler (base build)
#        or curl/pmtiles (base reuse). The transit_stops layer and the final merge
#        need only cargo. See README.md.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT="v5.pmtiles"
WORK="./v5_work"
BBOX=""
BASE_MODE="reuse"
BASE_JAR=""
BASE_AREA="planet"
BASE_SOURCE=""
EXTRA_LAYERS=()
ENGINE_BASE="legacy"
ENGINE_SAFETY="rust"
ENGINE_MAXSPEED="rust"
ENGINE_TRANSIT_LINES="rust"
ENGINE_ADMIN="legacy"
ENGINE_ADMIN_CITY="rust"
DRY_RUN=0
SKIP_BASE=0
SKIP_SAFETY=0
SKIP_MAXSPEED=0
SKIP_TRANSIT_LINES=0
SKIP_POIS=0
SKIP_ADMIN=0
SKIP_TRANSIT_STOPS=0
GTFS_MANIFEST=""
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
        --base-source) BASE_SOURCE="$2"; shift 2 ;;
        --extra-layer) EXTRA_LAYERS+=("$2"); shift 2 ;;
        --engine-base) ENGINE_BASE="$2"; shift 2 ;;
        --engine-safety) ENGINE_SAFETY="$2"; shift 2 ;;
        --engine-maxspeed) ENGINE_MAXSPEED="$2"; shift 2 ;;
        --engine-transit-lines) ENGINE_TRANSIT_LINES="$2"; shift 2 ;;
        --engine-admin) ENGINE_ADMIN="$2"; shift 2 ;;
        --engine-admin-city) ENGINE_ADMIN_CITY="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --skip-base) SKIP_BASE=1; shift ;;
        --skip-safety) SKIP_SAFETY=1; shift ;;
        --skip-maxspeed) SKIP_MAXSPEED=1; shift ;;
        --skip-transit-lines) SKIP_TRANSIT_LINES=1; shift ;;
        --skip-pois) SKIP_POIS=1; shift ;;
        --skip-admin) SKIP_ADMIN=1; shift ;;
        --skip-transit-stops) SKIP_TRANSIT_STOPS=1; shift ;;
        --gtfs-manifest) GTFS_MANIFEST="$2"; shift 2 ;;
        --keep-work) KEEP_WORK=1; shift ;;
        --publish) PUBLISH=1; shift ;;
        --publish-key) PUBLISH_KEY="$2"; shift 2 ;;
        -h|--help) sed -n '4,85p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

# Every layer keeps a rust|legacy switch even before it has a rust path, so the
# superscript can pass one uniformly. Asking for an unported engine is an error
# rather than a silent fall-back to legacy.
engine_check() {
    local layer="$1" engine="$2" ported="$3"
    case "$engine" in
        legacy|rust) : ;;
        *) echo "ERROR: --engine-$layer must be rust|legacy (got '$engine')" >&2; exit 1 ;;
    esac
    if [[ "$engine" == "rust" && "$ported" != "1" ]]; then
        echo "ERROR: --engine-$layer rust is not implemented yet; use legacy" >&2
        exit 1
    fi
}
engine_check base          "$ENGINE_BASE"          0
engine_check safety        "$ENGINE_SAFETY"        1
engine_check maxspeed      "$ENGINE_MAXSPEED"      1
engine_check transit-lines "$ENGINE_TRANSIT_LINES" 1
engine_check admin         "$ENGINE_ADMIN"         0
engine_check admin-city    "$ENGINE_ADMIN_CITY"    1

run() {
    if [[ "$DRY_RUN" == "1" ]]; then
        printf '[dry-run]'
        printf ' %q' "$@"
        printf '\n'
        return 0
    fi
    "$@"
}

command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }
mkdir -p "$WORK"

# Hoisted out of the POIs branch: the summary block below reads it too, and
# leaving it branch-local meant `set -u` only spared us because both sites happen
# to share the same guard.
OUTDIR="$(cd "$(dirname "$OUT")" && pwd)"

INPUTS=()

# --- 1. base ---
BASE="$WORK/base.pmtiles"
if [[ "$SKIP_BASE" == "1" ]]; then
    [[ -f "$BASE" ]] || { echo "ERROR: --skip-base but $BASE missing" >&2; exit 1; }
    echo "[v5] using existing base $BASE"
else
    BASE_ARGS=(--mode "$BASE_MODE" --out "$BASE")
    [[ -n "$BBOX" ]] && BASE_ARGS+=(--bbox "$BBOX")
    [[ -n "$BASE_SOURCE" ]] && BASE_ARGS+=(--source "$BASE_SOURCE")
    [[ "$BASE_MODE" == "build" ]] && BASE_ARGS+=(--jar "$BASE_JAR" --area "$BASE_AREA")
    run "$HERE/build_base_layers.sh" "${BASE_ARGS[@]}"
fi
INPUTS+=("$BASE")

# --- 2. safety ---
if [[ "$SKIP_SAFETY" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for safety layer (or --skip-safety)" >&2; exit 1; }
    SAFETY_ARGS=(--pbf "$PBF" --out "$WORK/safety.pmtiles" --engine "$ENGINE_SAFETY")
    [[ -n "$BBOX" ]] && SAFETY_ARGS+=(--bbox "$BBOX")
    run "$HERE/build_safety_layer.sh" "${SAFETY_ARGS[@]}"
    INPUTS+=("$WORK/safety.pmtiles")
fi

# --- 3. maxspeed ---
if [[ "$SKIP_MAXSPEED" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for maxspeed layer (or --skip-maxspeed)" >&2; exit 1; }
    MS_ARGS=(--pbf "$PBF" --out "$WORK/maxspeed.pmtiles" --engine "$ENGINE_MAXSPEED")
    [[ -n "$BBOX" ]] && MS_ARGS+=(--bbox "$BBOX")
    run "$HERE/build_maxspeed_layer.sh" "${MS_ARGS[@]}"
    INPUTS+=("$WORK/maxspeed.pmtiles")
fi

# --- 4. transit_lines ---
if [[ "$SKIP_TRANSIT_LINES" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for transit_lines layer (or --skip-transit-lines)" >&2; exit 1; }
    TL_ARGS=(--pbf "$PBF" --out "$WORK/transit_lines.pmtiles" --engine "$ENGINE_TRANSIT_LINES")
    [[ -n "$BBOX" ]] && TL_ARGS+=(--bbox "$BBOX")
    run "$HERE/build_transit_lines_layer.sh" "${TL_ARGS[@]}"
    INPUTS+=("$WORK/transit_lines.pmtiles")
fi

# --- 5. ma_pois (our baked OSM POI layer + poi_names.bin/poi_index.bin) ---
if [[ "$SKIP_POIS" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for ma_pois layer (or --skip-pois)" >&2; exit 1; }
    POIS_ARGS=(--pbf "$PBF" --out "$WORK/ma_pois.pmtiles" \
        --names-out "$OUTDIR/poi_names.bin" --index-out "$OUTDIR/poi_index.bin")
    [[ -n "$BBOX" ]] && POIS_ARGS+=(--bbox "$BBOX")
    run "$HERE/build_pois_layer.sh" "${POIS_ARGS[@]}"
    INPUTS+=("$WORK/ma_pois.pmtiles")
fi

# --- 6. admin ---
if [[ "$SKIP_ADMIN" == "0" ]]; then
    ADMIN_ARGS=(--outdir "$WORK/admin" --engine-city "$ENGINE_ADMIN_CITY")
    [[ -n "$PBF" ]] && ADMIN_ARGS+=(--pbf "$PBF")
    [[ -n "$BBOX" ]] && ADMIN_ARGS+=(--bbox "$BBOX")
    [[ -z "$PBF" ]] && ADMIN_ARGS+=(--no-city)
    run "$HERE/build_admin_layers.sh" "${ADMIN_ARGS[@]}"
    for l in admin_country admin_region admin_city; do
        [[ -f "$WORK/admin/$l.pmtiles" ]] && INPUTS+=("$WORK/admin/$l.pmtiles")
    done
fi

# --- 7. transit_stops (GTFS stop pins; cargo-only, takes GTFS dirs not --pbf) ---
if [[ "$SKIP_TRANSIT_STOPS" == "0" ]]; then
    if [[ -z "$GTFS_MANIFEST" ]]; then
        # Not an error: this layer's input is a set of GTFS feeds, which a plain
        # --pbf build has no way to produce. Say so rather than failing a build
        # that never asked for stops.
        echo "[v5] no --gtfs-manifest given; skipping transit_stops layer" >&2
    else
        run "$HERE/build_transit_stops_layer.sh" \
            --manifest "$GTFS_MANIFEST" \
            --out "$WORK/transit_stops.pmtiles" \
            --workdir "$WORK/transit_stops"
        INPUTS+=("$WORK/transit_stops.pmtiles")
    fi
fi

# --- 7b. layers the caller already built ---
# Last in, so an --extra-layer wins a name collision with anything above it.
for f in ${EXTRA_LAYERS[@]+"${EXTRA_LAYERS[@]}"}; do
    if [[ -f "$f" || "$DRY_RUN" == "1" ]]; then
        INPUTS+=("$f")
    else
        echo "ERROR: --extra-layer not found: $f" >&2
        exit 1
    fi
done

# --- 8. merge ---
echo "[v5] merging ${#INPUTS[@]} source(s) -> $OUT"
printf '  + %s\n' "${INPUTS[@]}"
# Our own tile_join, not tippecanoe's: it unions each tile's layers and carries
# line/polygon geometry through untouched, and needs no tippecanoe install. Later
# inputs win a layer-name collision, so a rebuilt overlay replaces a stale copy.
run cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
    --bin tile_join -- --out "$OUT" "${INPUTS[@]}"

if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] stopping before the summary; nothing was built"
    exit 0
fi

SIZE="$(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT" 2>/dev/null || echo '?')"
echo "[v5] done: $OUT (${SIZE} bytes)"
echo ""
echo "Layers now in $OUT:"
echo "  base : earth landcover landuse water roads buildings boundaries pois places"
echo "  new  : safety maxspeed transit_lines ma_pois transit_stops"
echo "         admin_country admin_region admin_city"
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

# Intermediates are never deleted, by design -- they are what makes a re-run cheap.
# --keep-work only silences this reminder.
[[ "$KEEP_WORK" == "1" ]] || echo "(intermediates kept in $WORK; pass --keep-work to silence)"
