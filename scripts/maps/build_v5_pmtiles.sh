#!/bin/bash
set -euo pipefail

# build_v5_pmtiles.sh — build the custom v5.pmtiles for the maps app.
#
# Two shapes come out of this script, and which one you want is a choice about
# size, not about content:
#
#   v5.pmtiles          base + overlays in one archive. ~137 GB at planet scale,
#                       essentially all of it base — the overlays add under 3%.
#   v5-overlay.pmtiles  overlays only (--no-base). ~2-7 GB at planet scale, and
#                       the app pairs it with the published base archive.
#
# Prefer --no-base for anything planet-sized. tile_join holds every input in RAM
# (see tile_build/src/bin/tile_join.rs), which a 127 GB base makes impossible;
# never joining the base removes that limit rather than engineering around it.
# The layer namespaces are disjoint by construction — build_base_layers.sh fixes
# the base names and every overlay name is additive — so the split needs no
# renaming and the app can mount both archives side by side.
#
# v5.pmtiles = Protomaps base schema (unchanged, style.json-compatible)
#            + safety     (baked road-furniture: cameras/ALPR/stops/signals)
#            + roads      (road geometry + class/speed/lanes/width, retiring the
#                          separate maxspeed layer)
#            + transit_lines (OSM rail/subway/tram/… lines for the P22 highlight)
#            + ma_pois    (OUR baked OSM POI layer: placement/name/type from OSM)
#            + transit_stops (GTFS stop pins + their MOTIS ids, replacing the
#                          removed /api/v1/map/stops per-viewport fetch)
#            + admin_country / admin_region / admin_city  (borders; replaces .fgb)
#
# The ma_pois step ALSO emits three compact side files next to --out
# (poi_names.bin + poi_index.bin + poi_attrs.bin) that the app mmaps for POI
# lookup — see build_pois_layer.sh / osm_ingest / README for the exact formats.
#
# This single file REPLACES both:
#   * https://data.vayunmathur.com/v4.pmtiles      (base tiles)
#   * maps/src/main/assets/admin0.fgb + admin1.fgb (border masks)
#
# Pipeline (each step is a standalone script in this dir):
#   1. build_base_layers.sh   -> base.pmtiles           (planetiler OR reuse v4)
#                                                      (skipped by --no-base)
#   2. build_safety_layer.sh  -> safety.pmtiles         (osmium + tippecanoe)
#   3. build_roads_layer.sh   -> roads.pmtiles          (cargo-only)
#   4. build_transit_lines_layer.sh -> transit_lines.pmtiles (osmium/ogr2ogr + tippecanoe)
#   5. build_pois_layer.sh    -> ma_pois.pmtiles + poi_{names,index,attrs}.bin
#   6. build_admin_layers.sh  -> admin_*.pmtiles        (Natural Earth/OSM + tippecanoe)
#   7. build_transit_stops_layer.sh -> transit_stops.pmtiles (GTFS, cargo-only)
#   8. tile_join              -> $OUT                   (merge all layers)
#
# Steps 7 and 8 are cargo-only (scripts/maps/tile_build), so they need neither
# tippecanoe nor osmium. The rest still do -- see README.md.
#
# Full planet build is the user's infra step (large + long). Prove correctness
# first with a metro dry run:
#   ./build_v5_pmtiles.sh --pbf norcal.osm.pbf --bbox -122.6,37.2,-121.7,37.9 \
#       --base-mode reuse --out v5-sf.pmtiles
#
# Planet build (overlays only -- the shippable shape):
#   ./build_v5_pmtiles.sh --pbf planet.osm.pbf --no-base --out v5-overlay.pmtiles
#
# Planet build including the base, if you have the RAM for a 137 GB join:
#   ./build_v5_pmtiles.sh --pbf planet.osm.pbf --base-mode build \
#       --base-jar protomaps-basemap-HEAD-with-deps.jar --out v5.pmtiles
#
# Options:
#   --pbf FILE        OSM extract for safety + city layers (required unless --skip-*)
#   --out FILE        final output (default v5.pmtiles, or v5-overlay.pmtiles
#                     under --no-base)
#   --no-base         build the overlays ONLY: skip step 1 and leave the base out
#                     of the merge entirely. The result is a standalone overlay
#                     archive the app mounts alongside the published base, and it
#                     is the only way a planet build fits in tile_join's memory.
#                     Mutually exclusive with --skip-base, and rejects the
#                     base-only options (--base-mode build, --base-jar,
#                     --base-source) rather than ignoring them.
#   --workdir DIR     scratch dir for intermediates (default ./v5_work)
#   --spill-dir DIR   where the roads tiler spills. It is tens of GB at planet scale
#                     and read once per zoom, so keep it off a network or /mnt/c
#                     mount even when the output lives there. Defaults to beside
#                     the roads archive.
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
#   --engine-base E       rust|legacy per-layer engine. `safety`,
#   --engine-safety E     `transit_lines` and `admin-city` default to rust
#   --engine-transit-lines E  (cargo-only); the rest default to legacy, and asking
#   --engine-admin E          for rust before a layer is ported is an error rather
#   --engine-admin-city E     than a silent no-op, so a rollback is always one flag.
#                             `admin-country`/`admin-region` come from Natural
#                             Earth and will never have a rust engine. `roads` has
#                             no switch: it never had a Python normaliser to be
#                             faithful to, so there is no legacy path to fall back
#                             to.
#   --admin-reuse SRC Carry admin_country and admin_region forward from an existing
#                     archive (a local .pmtiles or a URL) with pmtiles_extract,
#                     instead of rebuilding them from Natural Earth. Those two are
#                     the only layers that still need ogr2ogr and python3, so with
#                     this and --base-mode reuse the whole tile build is cargo-only
#                     apart from curl.
#   --dry-run         print each step's command instead of running it
#   --skip-base       don't (re)build base; expects <workdir>/base.pmtiles present
#   --skip-safety     omit safety layer
#   --skip-roads      omit roads layer
#   --skip-transit-lines omit transit_lines layer
#   --skip-pois       omit ma_pois layer + poi_{names,index,attrs}.bin side files
#   --skip-admin      omit admin layers
#   --skip-transit-stops omit transit_stops layer
#   --publish         after a successful build, upload $OUT to R2 (publish_r2.sh;
#                     reads R2_ENDPOINT/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY from env)
#   --publish-key K   remote object key when publishing (default: basename of --out)
#   --keep-work       keep intermediates
#
# Tools: osmium, ogr2ogr (GDAL), python3, tippecanoe (base/safety/
#        transit_lines/pois/admin layers), and either java+planetiler (base build)
#        or curl/pmtiles (base reuse). The roads and transit_stops layers and the
#        final merge need only cargo. See README.md.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PBF=""
OUT=""
WORK="./v5_work"
BBOX=""
BASE_MODE="reuse"
BASE_JAR=""
BASE_AREA="planet"
BASE_SOURCE=""
EXTRA_LAYERS=()
ENGINE_BASE="legacy"
ENGINE_SAFETY="rust"
ENGINE_TRANSIT_LINES="rust"
ENGINE_ADMIN="legacy"
ENGINE_ADMIN_CITY="rust"
ADMIN_REUSE=""
SPILL_DIR=""
DRY_RUN=0
NO_BASE=0
SKIP_BASE=0
SKIP_SAFETY=0
SKIP_ROADS=0
SKIP_TRANSIT_LINES=0
SKIP_POIS=0
SKIP_ADMIN=0
SKIP_TRANSIT_STOPS=0
GTFS_MANIFEST=""
KEEP_WORK=0
PUBLISH=0
PUBLISH_KEY=""
# How many layer builds run at once.
#
# Peak memory is roughly JOBS x the largest concurrent layer, and the largest is
# the roads extract at 5.00 GiB (California, measured). Three is the default
# because it uses the box without betting the build on it: six concurrent layers
# on a metro extract is comfortable, six on a planet extract is not, and a script
# that OOMs by default on a smaller machine is worse than one that is merely
# slower. Raise it when you know the machine.
JOBS=3

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --workdir) WORK="$2"; shift 2 ;;
        --spill-dir) SPILL_DIR="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --base-mode) BASE_MODE="$2"; shift 2 ;;
        --base-jar) BASE_JAR="$2"; shift 2 ;;
        --base-area) BASE_AREA="$2"; shift 2 ;;
        --base-source) BASE_SOURCE="$2"; shift 2 ;;
        --extra-layer) EXTRA_LAYERS+=("$2"); shift 2 ;;
        --engine-base) ENGINE_BASE="$2"; shift 2 ;;
        --engine-safety) ENGINE_SAFETY="$2"; shift 2 ;;
        --engine-transit-lines) ENGINE_TRANSIT_LINES="$2"; shift 2 ;;
        --engine-admin) ENGINE_ADMIN="$2"; shift 2 ;;
        --engine-admin-city) ENGINE_ADMIN_CITY="$2"; shift 2 ;;
        --admin-reuse) ADMIN_REUSE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --no-base) NO_BASE=1; shift ;;
        --skip-base) SKIP_BASE=1; shift ;;
        --skip-safety) SKIP_SAFETY=1; shift ;;
        --skip-roads) SKIP_ROADS=1; shift ;;
        --skip-transit-lines) SKIP_TRANSIT_LINES=1; shift ;;
        --skip-pois) SKIP_POIS=1; shift ;;
        --skip-admin) SKIP_ADMIN=1; shift ;;
        --skip-transit-stops) SKIP_TRANSIT_STOPS=1; shift ;;
        --gtfs-manifest) GTFS_MANIFEST="$2"; shift 2 ;;
        --jobs) JOBS="$2"; shift 2 ;;
        --keep-work) KEEP_WORK=1; shift ;;
        --publish) PUBLISH=1; shift ;;
        --publish-key) PUBLISH_KEY="$2"; shift 2 ;;
        -h|--help) sed -n '4,122p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

# --no-base is about the merge, --skip-base is about step 1 reusing an existing
# intermediate: one omits the base, the other insists on having built it. Asking
# for both is a contradiction, and guessing which was meant is worse than
# stopping.
if [[ "$NO_BASE" == "1" && "$SKIP_BASE" == "1" ]]; then
    echo "ERROR: --no-base and --skip-base are contradictory (omit the base vs. reuse a built one)" >&2
    exit 1
fi

# These only describe how to produce a base. Under --no-base there is no base to
# produce, so accepting them would silently do nothing -- which reads as a
# successful build of the wrong thing.
if [[ "$NO_BASE" == "1" ]]; then
    for pair in "--base-jar:$BASE_JAR" "--base-source:$BASE_SOURCE"; do
        if [[ -n "${pair#*:}" ]]; then
            echo "ERROR: ${pair%%:*} makes no sense with --no-base (no base is built)" >&2
            exit 1
        fi
    done
    if [[ "$BASE_MODE" == "build" ]]; then
        echo "ERROR: --base-mode build makes no sense with --no-base (no base is built)" >&2
        exit 1
    fi
fi

# Named after what it contains. v5.pmtiles is published with the base baked in
# and the app points at it today, so redefining that name mid-rollout would
# break any client that had already fetched the old bytes.
if [[ -z "$OUT" ]]; then
    if [[ "$NO_BASE" == "1" ]]; then OUT="v5-overlay.pmtiles"; else OUT="v5.pmtiles"; fi
fi

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

# --- the layer fan-out ------------------------------------------------------
#
# The layer builds are independent: each reads $PBF read-only, each mktemp -d's its
# own scratch, and each writes a distinct name under $WORK (admin gets its own
# subdirectory). So they can run at once -- but three things have to be right, and
# none of them are free.
#
# 1. `set -euo pipefail` does NOT propagate a failure out of a background job. A
#    layer that dies would otherwise reach tile_join and yield a short archive that
#    looks fine. `wait` per PID is the only way to see each exit code, so every job
#    is waited on individually and its status recorded.
# 2. Each job's output goes to its own log and is replayed afterwards. Six builds
#    interleaving progress bars on one terminal is unreadable, and the log is what
#    makes a failure diagnosable.
# 3. MAPS_THREADS is divided among the jobs. Without it each layer would size its
#    own pool from the whole box -- six layers claiming 32 threads each on 32 cores
#    is 192 threads, and finishes slower than running in sequence.
[[ "$JOBS" =~ ^[0-9]+$ && "$JOBS" -ge 1 ]] || {
    echo "ERROR: --jobs wants a positive count (got '$JOBS')" >&2; exit 1; }

# Total threads to share out. `nproc` is not on every box, so fall back rather
# than fail; MAPS_THREADS from the caller wins, since an operator who set it meant it.
TOTAL_THREADS="${MAPS_THREADS:-$(nproc 2>/dev/null || echo 4)}"
PER_JOB_THREADS=$(( TOTAL_THREADS / JOBS ))
[[ "$PER_JOB_THREADS" -ge 1 ]] || PER_JOB_THREADS=1

JOB_PIDS=()
JOB_NAMES=()
JOB_LOGS=()
LOG_DIR="$WORK/logs"

# Start a layer build, or run it in the foreground when --jobs 1.
launch() {
    local name="$1"; shift
    if [[ "$DRY_RUN" == "1" ]]; then
        printf '[dry-run]'
        printf ' %q' "$@"
        printf '\n'
        return 0
    fi
    if [[ "$JOBS" -le 1 ]]; then
        echo "[v5] $name"
        MAPS_THREADS="$PER_JOB_THREADS" "$@"
        return
    fi
    mkdir -p "$LOG_DIR"
    local log="$LOG_DIR/$name.log"
    echo "[v5] $name: started (MAPS_THREADS=$PER_JOB_THREADS, log $log)"
    MAPS_THREADS="$PER_JOB_THREADS" "$@" >"$log" 2>&1 &
    JOB_PIDS+=("$!")
    JOB_NAMES+=("$name")
    JOB_LOGS+=("$log")
    # Never more than JOBS in flight, so --jobs really is a memory bound and not
    # just a hint.
    [[ "${#JOB_PIDS[@]}" -lt "$JOBS" ]] || reap
}

# Wait for every outstanding job, replay its log, and fail if any failed.
reap() {
    [[ "${#JOB_PIDS[@]}" -gt 0 ]] || return 0
    local failed=()
    local i
    for i in "${!JOB_PIDS[@]}"; do
        if wait "${JOB_PIDS[$i]}"; then
            echo "===== ${JOB_NAMES[$i]}: ok ====="
            cat "${JOB_LOGS[$i]}"
        else
            echo "===== ${JOB_NAMES[$i]}: FAILED =====" >&2
            cat "${JOB_LOGS[$i]}" >&2
            failed+=("${JOB_NAMES[$i]}")
        fi
    done
    JOB_PIDS=()
    JOB_NAMES=()
    JOB_LOGS=()
    if [[ "${#failed[@]}" -gt 0 ]]; then
        echo "ERROR: layer build(s) failed: ${failed[*]}" >&2
        exit 1
    fi
}

command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }
mkdir -p "$WORK"

# Hoisted out of the POIs branch: the summary block below reads it too, and
# leaving it branch-local meant `set -u` only spared us because both sites happen
# to share the same guard.
OUTDIR="$(cd "$(dirname "$OUT")" && pwd)"

# The merge's argv order is a CONTRACT: a later input wins a layer-name collision,
# so `--extra-layer` must come last and the base must come first. Completion order
# is not argv order once the layers run concurrently, so the list is assembled in
# three fixed pieces rather than appended to as jobs finish. `INPUTS_ADMIN` is
# separate only because its entries are existence-checked, and those files do not
# exist until the admin job has been reaped.
INPUTS=()
INPUTS_ADMIN=()
INPUTS_POST=()

# --- 1. base ---
BASE="$WORK/base.pmtiles"
if [[ "$NO_BASE" == "1" ]]; then
    echo "[v5] --no-base: overlays only, pairing with the published base archive"
elif [[ "$SKIP_BASE" == "1" ]]; then
    [[ -f "$BASE" ]] || { echo "ERROR: --skip-base but $BASE missing" >&2; exit 1; }
    echo "[v5] using existing base $BASE"
    INPUTS+=("$BASE")
else
    BASE_ARGS=(--mode "$BASE_MODE" --out "$BASE")
    [[ -n "$BBOX" ]] && BASE_ARGS+=(--bbox "$BBOX")
    [[ -n "$BASE_SOURCE" ]] && BASE_ARGS+=(--source "$BASE_SOURCE")
    [[ "$BASE_MODE" == "build" ]] && BASE_ARGS+=(--jar "$BASE_JAR" --area "$BASE_AREA")
    launch base "$HERE/build_base_layers.sh" "${BASE_ARGS[@]}"
    INPUTS+=("$BASE")
fi

# --- 2. safety ---
if [[ "$SKIP_SAFETY" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for safety layer (or --skip-safety)" >&2; exit 1; }
    SAFETY_ARGS=(--pbf "$PBF" --out "$WORK/safety.pmtiles" --engine "$ENGINE_SAFETY")
    [[ -n "$BBOX" ]] && SAFETY_ARGS+=(--bbox "$BBOX")
    launch safety "$HERE/build_safety_layer.sh" "${SAFETY_ARGS[@]}"
    INPUTS+=("$WORK/safety.pmtiles")
fi

# --- 3. roads ---
if [[ "$SKIP_ROADS" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for roads layer (or --skip-roads)" >&2; exit 1; }
    ROADS_ARGS=(--pbf "$PBF" --out "$WORK/roads.pmtiles")
    [[ -n "$BBOX" ]] && ROADS_ARGS+=(--bbox "$BBOX")
    # The roads tiler streams, so it needs somewhere to spill. Forwarded rather than
    # left to default beside the output, because at planet scale the spill is tens of
    # GB and the operator is the only one who knows which mount can take it.
    [[ -n "$SPILL_DIR" ]] && ROADS_ARGS+=(--spill-dir "$SPILL_DIR/roads")
    launch roads "$HERE/build_roads_layer.sh" "${ROADS_ARGS[@]}"
    INPUTS+=("$WORK/roads.pmtiles")
fi

# --- 4. transit_lines ---
if [[ "$SKIP_TRANSIT_LINES" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for transit_lines layer (or --skip-transit-lines)" >&2; exit 1; }
    TL_ARGS=(--pbf "$PBF" --out "$WORK/transit_lines.pmtiles" --engine "$ENGINE_TRANSIT_LINES")
    [[ -n "$BBOX" ]] && TL_ARGS+=(--bbox "$BBOX")
    launch transit_lines "$HERE/build_transit_lines_layer.sh" "${TL_ARGS[@]}"
    INPUTS+=("$WORK/transit_lines.pmtiles")
fi

# --- 5. ma_pois (our baked OSM POI layer + poi_names.bin/poi_index.bin) ---
if [[ "$SKIP_POIS" == "0" ]]; then
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf required for ma_pois layer (or --skip-pois)" >&2; exit 1; }
    POIS_ARGS=(--pbf "$PBF" --out "$WORK/ma_pois.pmtiles" \
        --names-out "$OUTDIR/poi_names.bin" --index-out "$OUTDIR/poi_index.bin" \
        --attrs-out "$OUTDIR/poi_attrs.bin" \
        --spatial-out "$OUTDIR/poi_spatial.bin" \
        --name-index-out "$OUTDIR/poi_name_index.bin")
    [[ -n "$BBOX" ]] && POIS_ARGS+=(--bbox "$BBOX")
    launch ma_pois "$HERE/build_pois_layer.sh" "${POIS_ARGS[@]}"
    INPUTS+=("$WORK/ma_pois.pmtiles")
fi

# --- 6. admin ---
if [[ "$SKIP_ADMIN" == "0" ]]; then
    ADMIN_ARGS=(--outdir "$WORK/admin" --engine-city "$ENGINE_ADMIN_CITY")
    [[ -n "$PBF" ]] && ADMIN_ARGS+=(--pbf "$PBF")
    [[ -n "$BBOX" ]] && ADMIN_ARGS+=(--bbox "$BBOX")
    [[ -z "$PBF" ]] && ADMIN_ARGS+=(--no-city)
    # With --admin-reuse, the Natural Earth levels are lifted out of an existing
    # archive rather than rebuilt, so build_admin_layers.sh only has to do the one
    # level OSM can supply -- and therefore needs no ogr2ogr, tippecanoe or python3.
    [[ -n "$ADMIN_REUSE" ]] && ADMIN_ARGS+=(--only-city)
    launch admin "$HERE/build_admin_layers.sh" "${ADMIN_ARGS[@]}"
    # Deferred: these files do not exist until the admin job has been reaped, and
    # `INPUTS_ADMIN` keeps their argv position regardless of when that happens.
    ADMIN_WANTED=(admin_country admin_region admin_city)

    if [[ -n "$ADMIN_REUSE" ]]; then
        SRC="$ADMIN_REUSE"
        if [[ "$ADMIN_REUSE" == http://* || "$ADMIN_REUSE" == https://* ]]; then
            SRC="$WORK/admin/reuse_source.pmtiles"
            mkdir -p "$WORK/admin"
            if [[ -f "$SRC" ]]; then
                echo "[v5] reusing already-downloaded $SRC"
            else
                echo "[v5] downloading $ADMIN_REUSE for the admin lift"
                run curl -fL --retry 3 -o "$SRC.partial" "$ADMIN_REUSE"
                run mv "$SRC.partial" "$SRC"
            fi
        fi
        echo "[v5] lifting admin_country + admin_region out of $SRC"
        # One archive holding both, so the merge gains one input rather than two.
        run cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
            --bin pmtiles_extract -- "$SRC" \
            --out "$WORK/admin/admin_reused.pmtiles" \
            --layer admin_country --layer admin_region
        ADMIN_REUSED="$WORK/admin/admin_reused.pmtiles"
    fi
fi

# --- 7. transit_stops (GTFS stop pins; cargo-only, takes GTFS dirs not --pbf) ---
if [[ "$SKIP_TRANSIT_STOPS" == "0" ]]; then
    if [[ -z "$GTFS_MANIFEST" ]]; then
        # Not an error: this layer's input is a set of GTFS feeds, which a plain
        # --pbf build has no way to produce. Say so rather than failing a build
        # that never asked for stops.
        echo "[v5] no --gtfs-manifest given; skipping transit_stops layer" >&2
    else
        # --maxzoom explicitly, and equal to the archive's: the merge unions every
        # input's maxzoom into one source-level maxzoom, and MapLibre overzooms per
        # source rather than per layer. A stops layer tiled below the union has its
        # stops disappear above its own maxzoom, because the client then fetches real
        # tiles that carry no `transit_stops`.
        launch transit_stops "$HERE/build_transit_stops_layer.sh" \
            --manifest "$GTFS_MANIFEST" \
            --out "$WORK/transit_stops.pmtiles" \
            --workdir "$WORK/transit_stops" \
            --maxzoom 16
        INPUTS_POST+=("$WORK/transit_stops.pmtiles")
    fi
fi

# --- 7a. every layer job has to have finished before the merge reads its output ---
#
# This is the barrier the whole fan-out hangs on: `reap` waits on each PID
# individually and exits non-zero if any layer failed, because `set -e` would
# otherwise let a dead layer through to tile_join and produce a short archive that
# looks perfectly valid.
reap

for l in ${ADMIN_WANTED[@]+"${ADMIN_WANTED[@]}"}; do
    [[ -f "$WORK/admin/$l.pmtiles" ]] && INPUTS_ADMIN+=("$WORK/admin/$l.pmtiles")
done
[[ -n "${ADMIN_REUSED:-}" ]] && INPUTS_ADMIN+=("$ADMIN_REUSED")

INPUTS+=(${INPUTS_ADMIN[@]+"${INPUTS_ADMIN[@]}"} ${INPUTS_POST[@]+"${INPUTS_POST[@]}"})

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
if [[ "$NO_BASE" == "1" ]]; then
    echo "  (no base layers -- pair this with the published base archive)"
else
    echo "  base : earth landcover landuse water roads buildings boundaries pois places"
fi
echo "  new  : safety roads transit_lines ma_pois transit_stops"
echo "         admin_country admin_region admin_city"
echo ""
if [[ "$SKIP_POIS" == "0" ]]; then
    echo "POI side files (emitted beside $OUT for the app to mmap):"
    echo "  $OUTDIR/poi_names.bin   (deduped NUL-terminated UTF-8 name table)"
    echo "  $OUTDIR/poi_index.bin   (flat 14-byte records: lat_e7,lon_e7,name_off,type)"
    echo "  $OUTDIR/poi_attrs.bin   (attribute sidecar, keyed by poi_index record ordinal)"
    echo "  $OUTDIR/poi_spatial.bin (sparse CSR lat/lon grid over record ordinals)"
    echo "  $OUTDIR/poi_name_index.bin (one (record, word) entry per word, word-sorted)"
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
    echo "  ./scripts/maps/publish_r2.sh $OUT --key $(basename "$OUT")"
    echo "  # or re-run this build with --publish"
    if [[ "$NO_BASE" == "1" ]]; then
        echo "Then point MapTileCache.OVERLAY_PMTILES_URL at the published key."
    else
        echo "Then P13 updates style.json url -> pmtiles://https://data.vayunmathur.com/v5.pmtiles"
    fi
fi

# Intermediates are never deleted, by design -- they are what makes a re-run cheap.
# --keep-work only silences this reminder.
[[ "$KEEP_WORK" == "1" ]] || echo "(intermediates kept in $WORK; pass --keep-work to silence)"
