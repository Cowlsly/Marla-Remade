#!/bin/bash
set -euo pipefail

# build_all.sh — ONE command that builds every runtime artifact the maps app
# downloads, from an .osm.pbf plus a GTFS source.
#
# Replaces driving three disjoint pipelines by hand (run_generator.sh for the
# graph, build_v5_pmtiles.sh for the tiles, build_world_transit.sh for transit),
# and closes the gap where poi_names.bin/poi_index.bin had to be hand-copied
# into the graph directory before the upload would pick them up.
#
# THE 13 ARTIFACTS, all landing in --out-dir:
#   graph    metadata.bin road_names.bin nodes.bin edges.bin lanes.bin
#            intermediate.bin
#   pois     poi_names.bin poi_index.bin poi_attrs.bin poi_spatial.bin poi_name_index.bin
#   transit  world.transit
#   tiles    v5-overlay.pmtiles               (name from --out)
# plus manifest.txt listing every one with its size and SHA-256. The first twelve
# are what MainActivity's InitialDownloadChecker fetches; the thirteenth is streamed.
#
# Stages run in order graph -> pois -> transit -> tiles. Each writes a stamp file
# under <work>/stamps on success, so a re-run skips what already finished;
# --force ignores the stamps.
#
# THE DIVISION OF LABOUR: this script and its build_all.ps1 twin own network I/O
# and process orchestration, nothing else. Every bytes-to-bytes transformation
# belongs in the Rust crates (osm_ingest, gtfs_ingest, tile_build) — that is why
# they carry no HTTP stack and why the downloads live out here.
#
# Usage:
#   ./build_all.sh --pbf california-latest.osm.pbf --gtfs-manifest feeds.manifest
#   ./build_all.sh --geofabrik north-america/us/california --gtfs-region 'us-ca'
#   ./build_all.sh --pbf norcal.osm.pbf --bbox -122.6,37.2,-121.7,37.9 --dry-run
#
# Options:
#   Inputs
#     --pbf FILE          OSM extract for the graph, POI and tile layers
#     --geofabrik REGION  fetch the PBF instead, e.g. 'north-america/us/california'
#                         ('-latest.osm.pbf' is appended). Cached in <work>.
#     --gtfs-manifest F   feeds.manifest ('name=dir[=motis_prefix]' per line) for
#                         world.transit and the transit_stops tile layer
#     --gtfs-region GLOB  build that manifest from Transitous' published gtfs
#                         directory instead, e.g. 'us-ca' (see build_world_transit.sh)
#     --gtfs-mirror DIR   where that mirror lives (default <work>/transit/transitous).
#                         ~10 GB for the whole world, so on WSL put it on the LINUX
#                         filesystem even when --out-dir is under /mnt/c.
#     --gtfs-rate LIMIT   wget --limit-rate for the mirror (default 30m)
#     --bbox BOX          "minlon,minlat,maxlon,maxlat". Clipped ONCE up front and
#                         reused by every stage, so the graph honours it too.
#                         Needs osmium.
#   Outputs
#     --out-dir DIR       where the 13 artifacts land (default ./build_all_out)
#     --out FILE          the tile archive (default <out-dir>/v5-overlay.pmtiles,
#                         or <out-dir>/v5.pmtiles with --with-base). Its basename
#                         becomes the published key, so match whatever the app
#                         points at.
#     --work DIR          scratch + stamps (default <out-dir>/work)
#     --with-base         merge the Protomaps base INTO the tile archive, instead
#                         of the default overlay-only output. Only sane for a
#                         metro-sized extract: tile_join holds every input in
#                         memory, and a planet base is ~127 GB of a 137 GB result.
#                         The app streams the base from its own published archive
#                         (MapTileCache.BASEMAP_PMTILES_URL), so the overlay-only
#                         default is the shippable shape.
#     --base-archive URL  published archive to reuse for the base layers
#                         (--with-base only; default build_base_layers.sh's own)
#     --admin-reuse SRC   carry admin_country and admin_region forward from an
#                         existing archive (a local .pmtiles or a URL) instead of
#                         rebuilding them from Natural Earth. Those two are the only
#                         layers that still need ogr2ogr and python3, so with this
#                         and --base-mode reuse the whole tile build is cargo-only
#                         apart from curl.
#     --base-mode M       build|reuse (--with-base only; default reuse; build needs
#                         java 21 + --base-jar)
#     --base-jar FILE     protomaps basemap jar (--base-mode build only)
#     --base-area A       planetiler area (--base-mode build only; default planet)
#   Stage control
#     --skip-graph  --skip-pois  --skip-transit  --skip-tiles
#     --only STAGE        run exactly one of graph|pois|transit|tiles
#     --force             ignore stamps and redo every requested stage
#     --dry-run           print what each stage would run; change nothing
#   Graph build-time memory (forwarded to road_graph; none change the on-disk
#   contract, and --rounds does not change the output at all)
#     --within-way-chains confine chains to one OSM way, which removes the segment
#                         array and the incidence index entirely. REQUIRED at
#                         planet scale: without it the reference path peaked at
#                         61.85 GiB on Europe alone, and a planet is 2.65x that.
#                         Costs ~9% more nodes and ~7% more edges.
#     --rounds N          write the pack in N source-partitioned rounds instead of
#                         one, so the write buffer is edges/N x 48 B rather than
#                         all of it. Planet wants 3.
#     --spill-dir DIR     put chains.pts somewhere other than --out-dir. It is the
#                         only file the build reads RANDOMLY, so it wants cheap
#                         seeks -- keep it off a network or /mnt/c mount even when
#                         the output lives there.
#                         See osm_ingest/README.md 'Build-time memory'.
#   Engines, per layer, so a ported layer rolls back with a flag
#     --engine-base E     rust|legacy    (default legacy)
#     --engine-safety E   rust|legacy    (default rust: osm_extract + tile_points,
#                                         no osmium/tippecanoe/python3)
#     --engine-transit-lines E           (default rust: ways AND route relations
#                                         read straight from the PBF, no GDAL)
#     --engine-admin E    rust|legacy    (default legacy: admin_country and
#                                         admin_region come from Natural Earth,
#                                         which OSM cannot supply)
#     --engine-admin-city E              (default rust: osm_extract's own ring
#                                         assembler + tile_polygons)
#     --engine-pois E     rust|legacy    (default rust: tile_points, no tippecanoe)
#   Publishing
#     --publish           upload every built artifact via publish_r2.sh
#     --publish-dry-run   with --publish, pass --dry-run through to publish_r2.sh
#   -h|--help             this help
#
# Tools: cargo always. The legacy engines additionally need osmium, tippecanoe,
# python3 and (for admin) ogr2ogr; --base-mode build needs java 21; --bbox needs
# osmium. See the "Caveats" section of README.md for exactly which layers are
# cargo-only today.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PBF=""
GEOFABRIK=""
GTFS_MANIFEST=""
GTFS_REGION=""
BBOX=""
OUT_DIR="./build_all_out"
OUT=""
WORK=""
BASE_ARCHIVE=""
ADMIN_REUSE=""
BASE_MODE="reuse"
BASE_JAR=""
BASE_AREA="planet"
WITH_BASE=0
WITHIN_WAY_CHAINS=0
ROUNDS=""
SPILL_DIR=""
GTFS_MIRROR=""
GTFS_RATE=""
SKIP_GRAPH=0
SKIP_POIS=0
SKIP_TRANSIT=0
SKIP_TILES=0
ONLY=""
FORCE=0
DRY_RUN=0
PUBLISH=0
PUBLISH_DRY_RUN=0
ENGINE_BASE="legacy"
ENGINE_SAFETY="rust"
ENGINE_TRANSIT_LINES="rust"
ENGINE_ADMIN="legacy"
ENGINE_ADMIN_CITY="rust"
ENGINE_POIS="rust"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --geofabrik) GEOFABRIK="$2"; shift 2 ;;
        --gtfs-manifest) GTFS_MANIFEST="$2"; shift 2 ;;
        --gtfs-region) GTFS_REGION="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --out-dir) OUT_DIR="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --base-archive) BASE_ARCHIVE="$2"; shift 2 ;;
        --with-base) WITH_BASE=1; shift ;;
        --within-way-chains) WITHIN_WAY_CHAINS=1; shift ;;
        --rounds) ROUNDS="$2"; shift 2 ;;
        --spill-dir) SPILL_DIR="$2"; shift 2 ;;
        --gtfs-mirror) GTFS_MIRROR="$2"; shift 2 ;;
        --gtfs-rate) GTFS_RATE="$2"; shift 2 ;;
        --admin-reuse) ADMIN_REUSE="$2"; shift 2 ;;
        --base-mode) BASE_MODE="$2"; shift 2 ;;
        --base-jar) BASE_JAR="$2"; shift 2 ;;
        --base-area) BASE_AREA="$2"; shift 2 ;;
        --skip-graph) SKIP_GRAPH=1; shift ;;
        --skip-pois) SKIP_POIS=1; shift ;;
        --skip-transit) SKIP_TRANSIT=1; shift ;;
        --skip-tiles) SKIP_TILES=1; shift ;;
        --only) ONLY="$2"; shift 2 ;;
        --force) FORCE=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        --publish) PUBLISH=1; shift ;;
        --publish-dry-run) PUBLISH_DRY_RUN=1; shift ;;
        --engine-base) ENGINE_BASE="$2"; shift 2 ;;
        --engine-safety) ENGINE_SAFETY="$2"; shift 2 ;;
        --engine-transit-lines) ENGINE_TRANSIT_LINES="$2"; shift 2 ;;
        --engine-admin) ENGINE_ADMIN="$2"; shift 2 ;;
        --engine-admin-city) ENGINE_ADMIN_CITY="$2"; shift 2 ;;
        --engine-pois) ENGINE_POIS="$2"; shift 2 ;;
        -h|--help) sed -n '4,118p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

case "$ONLY" in
    ""|graph|pois|transit|tiles) : ;;
    *) echo "ERROR: --only must be graph|pois|transit|tiles (got '$ONLY')" >&2; exit 1 ;;
esac

# A `rust` engine is only offered once that layer has actually been ported.
# Refusing it loudly beats silently running the legacy path and reporting success.
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
engine_check base           "$ENGINE_BASE"           0
engine_check safety         "$ENGINE_SAFETY"         1
engine_check transit-lines  "$ENGINE_TRANSIT_LINES"  1
engine_check admin          "$ENGINE_ADMIN"          0
engine_check admin-city     "$ENGINE_ADMIN_CITY"     1
engine_check pois           "$ENGINE_POIS"           1

command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
WORK="${WORK:-$OUT_DIR/work}"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
# Named after what it contains. The published v5.pmtiles has a base baked in, so
# an overlay-only archive must not silently reuse that name.
if [[ "$WITH_BASE" == "1" ]]; then
    OUT="${OUT:-$OUT_DIR/v5.pmtiles}"
else
    OUT="${OUT:-$OUT_DIR/v5-overlay.pmtiles}"
fi
STAMPS="$WORK/stamps"
mkdir -p "$STAMPS"

run() {
    if [[ "$DRY_RUN" == "1" ]]; then
        printf '[dry-run]'
        printf ' %q' "$@"
        printf '\n'
        return 0
    fi
    "$@"
}

stage_done() { [[ "$FORCE" == "0" && -f "$STAMPS/$1.done" ]]; }
mark_done() { [[ "$DRY_RUN" == "1" ]] || : > "$STAMPS/$1.done"; }

want_stage() {
    if [[ -n "$ONLY" ]]; then
        [[ "$ONLY" == "$1" ]]
        return
    fi
    local var="SKIP_${1^^}"
    [[ "${!var}" == "0" ]]
}

require_pbf() {
    [[ -n "$PBF" ]] || { echo "ERROR: --pbf or --geofabrik required for the $1 stage (or --skip-$1)" >&2; exit 1; }
    [[ -f "$PBF" || "$DRY_RUN" == "1" ]] || { echo "ERROR: pbf not found: $PBF" >&2; exit 1; }
}

# --- input: fetch the PBF if asked ---
# Network I/O lives here rather than in osm_ingest, which is why that crate has
# no HTTP dependency.
if [[ -n "$GEOFABRIK" ]]; then
    [[ -z "$PBF" ]] || { echo "ERROR: pass --pbf or --geofabrik, not both" >&2; exit 1; }
    PBF="$WORK/$(basename "$GEOFABRIK")-latest.osm.pbf"
    if [[ -f "$PBF" ]]; then
        echo "[all] reusing cached $PBF"
    else
        command -v curl >/dev/null || { echo "ERROR: --geofabrik needs curl" >&2; exit 1; }
        echo "[all] fetching https://download.geofabrik.de/$GEOFABRIK-latest.osm.pbf"
        # Download to .partial so an interrupted fetch is never mistaken for a
        # complete cached extract on the next run.
        run curl -fL --retry 3 -o "$PBF.partial" \
            "https://download.geofabrik.de/$GEOFABRIK-latest.osm.pbf"
        run mv "$PBF.partial" "$PBF"
    fi
fi

# --- input: clip once, share everywhere ---
# The per-layer scripts each do their own `osmium extract -b` when handed --bbox.
# Clipping once here instead means one extract rather than six, and it is the
# only way the graph stage can honour a bbox at all (road_graph has no --bbox).
if [[ -n "$BBOX" ]]; then
    require_pbf graph
    command -v osmium >/dev/null || { echo "ERROR: --bbox needs osmium-tool" >&2; exit 1; }
    CLIPPED="$WORK/metro.osm.pbf"
    if [[ -f "$CLIPPED" && "$FORCE" == "0" ]]; then
        echo "[all] reusing bbox extract $CLIPPED"
    else
        echo "[all] clipping to $BBOX (once, shared by every stage)"
        run osmium extract --overwrite -b "$BBOX" "$PBF" -o "$CLIPPED"
    fi
    PBF="$CLIPPED"
fi

POIS_TILE="$WORK/ma_pois.pmtiles"
# build_world_transit.sh always lands its resolved manifest here, including when
# it was handed one via --manifest, so the tiles stage has a single place to look.
TRANSIT_WORK="$WORK/transit"
EFFECTIVE_GTFS_MANIFEST="$TRANSIT_WORK/feeds.manifest"

# --- stage: graph (6 files) ---
if want_stage graph; then
    if stage_done graph; then
        echo "=== graph: stamp present, skipping (--force to redo) ==="
    else
        require_pbf graph
        echo "=== graph -> $OUT_DIR (metadata/road_names/nodes/edges/lanes/intermediate.bin) ==="
        GRAPH_ARGS=("$PBF" --out "$OUT_DIR")
        [[ "$WITHIN_WAY_CHAINS" == "1" ]] && GRAPH_ARGS+=(--within-way-chains)
        [[ -n "$ROUNDS" ]] && GRAPH_ARGS+=(--rounds "$ROUNDS")
        [[ -n "$SPILL_DIR" ]] && GRAPH_ARGS+=(--spill-dir "$SPILL_DIR")
        # Gated deliberately: road_graph truncates its outputs as it writes, so a
        # failure here must not fall through to the manifest or the publish.
        run cargo run --release --quiet --manifest-path "$HERE/osm_ingest/Cargo.toml" \
            --bin road_graph -- "${GRAPH_ARGS[@]}"
        mark_done graph
    fi
fi

# --- stage: pois (5 side files + the ma_pois tile layer) ---
# One poi_extract pass produces the geojson the tiler reads AND all five side
# files, so they cannot disagree. The side files go straight to --out-dir; that
# is the manual copy step run_generator.sh used to ask for.
if want_stage pois; then
    if stage_done pois; then
        echo "=== pois: stamp present, skipping (--force to redo) ==="
    else
        require_pbf pois
        echo "=== pois -> $OUT_DIR/poi_{names,index,attrs,spatial,name_index}.bin + $POIS_TILE ==="
        run "$HERE/build_pois_layer.sh" \
            --pbf "$PBF" \
            --out "$POIS_TILE" \
            --names-out "$OUT_DIR/poi_names.bin" \
            --index-out "$OUT_DIR/poi_index.bin" \
            --attrs-out "$OUT_DIR/poi_attrs.bin" \
            --spatial-out "$OUT_DIR/poi_spatial.bin" \
            --name-index-out "$OUT_DIR/poi_name_index.bin" \
            --engine "$ENGINE_POIS"
        mark_done pois
    fi
fi

# --- stage: transit (world.transit) ---
if want_stage transit; then
    if stage_done transit; then
        echo "=== transit: stamp present, skipping (--force to redo) ==="
    else
        if [[ -z "$GTFS_MANIFEST" && -z "$GTFS_REGION" ]]; then
            echo "ERROR: transit stage needs --gtfs-manifest or --gtfs-region (or --skip-transit)" >&2
            exit 1
        fi
        [[ -z "$GTFS_MANIFEST" || -z "$GTFS_REGION" ]] || {
            echo "ERROR: pass --gtfs-manifest or --gtfs-region, not both" >&2; exit 1; }
        echo "=== transit -> $OUT_DIR/world.transit ==="
        WT_ARGS=(--work "$TRANSIT_WORK" --out "$OUT_DIR")
        [[ -n "$GTFS_MIRROR" ]] && WT_ARGS+=(--mirror-dir "$GTFS_MIRROR")
        [[ -n "$GTFS_RATE" ]] && WT_ARGS+=(--rate "$GTFS_RATE")
        if [[ -n "$GTFS_MANIFEST" ]]; then
            WT_ARGS+=(--manifest "$GTFS_MANIFEST")
        else
            WT_ARGS+=(--region "$GTFS_REGION")
        fi
        [[ "$DRY_RUN" == "1" ]] && WT_ARGS+=(--dry-run)
        "$HERE/build_world_transit.sh" "${WT_ARGS[@]}"
        mark_done transit
    fi
fi

# --- stage: tiles ($OUT) ---
if want_stage tiles; then
    if stage_done tiles; then
        echo "=== tiles: stamp present, skipping (--force to redo) ==="
    else
        require_pbf tiles
        echo "=== tiles -> $OUT ==="
        V5_ARGS=(--pbf "$PBF" --out "$OUT" --workdir "$WORK/v5")
        # Overlay-only unless asked otherwise. The base options only mean anything
        # when there IS a base, and build_v5_pmtiles.sh rejects them alongside
        # --no-base rather than ignoring them, so they are passed only here.
        if [[ "$WITH_BASE" == "1" ]]; then
            V5_ARGS+=(--base-mode "$BASE_MODE")
            [[ -n "$BASE_ARCHIVE" ]] && V5_ARGS+=(--base-source "$BASE_ARCHIVE")
            [[ "$BASE_MODE" == "build" ]] && V5_ARGS+=(--base-jar "$BASE_JAR" --base-area "$BASE_AREA")
            V5_ARGS+=(--engine-base "$ENGINE_BASE")
        else
            V5_ARGS+=(--no-base)
        fi
        # The pois stage already built the layer and the side files; --skip-pois
        # plus --extra-layer folds that archive into the merge without a second
        # poi_extract pass.
        if [[ -f "$POIS_TILE" || "$DRY_RUN" == "1" ]]; then
            V5_ARGS+=(--skip-pois --extra-layer "$POIS_TILE")
        fi
        [[ -n "$ADMIN_REUSE" ]] && V5_ARGS+=(--admin-reuse "$ADMIN_REUSE")
        if [[ -f "$EFFECTIVE_GTFS_MANIFEST" ]]; then
            V5_ARGS+=(--gtfs-manifest "$EFFECTIVE_GTFS_MANIFEST")
        elif [[ -n "$GTFS_MANIFEST" ]]; then
            V5_ARGS+=(--gtfs-manifest "$GTFS_MANIFEST")
        fi
        V5_ARGS+=(--engine-safety "$ENGINE_SAFETY"
                  --engine-transit-lines "$ENGINE_TRANSIT_LINES"
                  --engine-admin "$ENGINE_ADMIN"
                  --engine-admin-city "$ENGINE_ADMIN_CITY")
        [[ "$DRY_RUN" == "1" ]] && V5_ARGS+=(--dry-run)
        "$HERE/build_v5_pmtiles.sh" "${V5_ARGS[@]}"
        mark_done tiles
    fi
fi

# --- manifest.txt: name, size, SHA-256 for all 12 ---
ARTIFACTS=(metadata.bin road_names.bin nodes.bin edges.bin lanes.bin intermediate.bin
           poi_names.bin poi_index.bin poi_attrs.bin poi_spatial.bin poi_name_index.bin
           world.transit)

sha256_of() {
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        echo "no-sha256-tool"
    fi
}
size_of() { stat -c%s "$1" 2>/dev/null || stat -f%z "$1" 2>/dev/null || echo 0; }

artifact_path() {
    if [[ "$1" == "$(basename "$OUT")" ]]; then echo "$OUT"; else echo "$OUT_DIR/$1"; fi
}

if [[ "$DRY_RUN" == "1" ]]; then
    echo ""
    echo "[dry-run] would write $OUT_DIR/manifest.txt over these $(( ${#ARTIFACTS[@]} + 1 )) artifacts:"
    printf '  %s\n' "${ARTIFACTS[@]}" "$(basename "$OUT")"
else
    MANIFEST_FILE="$OUT_DIR/manifest.txt"
    : > "$MANIFEST_FILE"
    PRESENT=()
    MISSING=()
    for a in "${ARTIFACTS[@]}" "$(basename "$OUT")"; do
        p="$(artifact_path "$a")"
        if [[ -f "$p" ]]; then
            printf '%-16s %14s  %s\n' "$a" "$(size_of "$p")" "$(sha256_of "$p")" >> "$MANIFEST_FILE"
            PRESENT+=("$p")
        else
            printf '%-16s %14s  %s\n' "$a" "-" "MISSING" >> "$MANIFEST_FILE"
            MISSING+=("$a")
        fi
    done
    echo ""
    echo "=== $OUT_DIR/manifest.txt (${#PRESENT[@]}/$(( ${#ARTIFACTS[@]} + 1 )) present) ==="
    cat "$MANIFEST_FILE"
    if [[ ${#MISSING[@]} -gt 0 ]]; then
        # Not fatal: --skip-*/--only runs are expected to leave holes. Naming them
        # is what stops a partial run from looking like a complete one.
        echo ""
        echo "[all] NOT built this run: ${MISSING[*]}" >&2
    fi
fi

# --- publish (optional) ---
if [[ "$PUBLISH" == "1" ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
        echo "[dry-run] would publish every present artifact via publish_r2.sh"
    elif [[ ${#PRESENT[@]} -eq 0 ]]; then
        echo "ERROR: --publish but nothing was built" >&2
        exit 1
    else
        echo ""
        echo "=== publishing ${#PRESENT[@]} artifact(s) to R2 ==="
        PUB_ARGS=("${PRESENT[@]}")
        [[ "$PUBLISH_DRY_RUN" == "1" ]] && PUB_ARGS+=(--dry-run)
        # publish_r2.sh keys each file by basename, which is what the app's URLs
        # expect; --out's basename is therefore the tile key.
        "$HERE/publish_r2.sh" "${PUB_ARGS[@]}"
    fi
else
    echo ""
    echo "Publish (creds from env — see README 'Publishing to R2'):"
    echo "  export R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com"
    echo "  export R2_ACCESS_KEY_ID=...  R2_SECRET_ACCESS_KEY=..."
    echo "  $0 <same args> --publish"
fi
