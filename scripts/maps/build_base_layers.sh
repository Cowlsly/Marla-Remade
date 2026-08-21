#!/bin/bash
set -euo pipefail

# build_base_layers.sh — produce the Protomaps-schema base map as .pmtiles
#
# The app's style.json (maps/src/main/assets/style.json) expects the Protomaps
# basemaps schema, i.e. these source-layers:
#   earth, landcover, landuse, water, roads, buildings, boundaries, pois, places
# We MUST keep those exact names so the style keeps working unchanged.
#
# Two supported paths:
#
#   (A) build  — regenerate from OSM with Planetiler + the official Protomaps
#                basemaps profile (produces the exact schema above). This is the
#                clean, reproducible path. Requires Java 21 + the protomaps
#                basemaps jar (built once from github.com/protomaps/basemaps).
#
#   (B) reuse  — download the upstream prebuilt Protomaps v4.pmtiles and use it
#                verbatim as the base. Fast; identical schema. Use when you only
#                want to refresh the safety/admin overlays on top of a known base.
#
# PLANETILER IS THE ONE DOCUMENTED JAVA EXCEPTION in this pipeline, and it is not
# going to be ported: it is a large Java program implementing the Protomaps schema,
# and reimplementing it would mean owning that schema rather than consuming it. So
# `--mode build` requires `java` and fails loudly when it is absent, and
# `--mode reuse` is the default precisely because it needs no JVM.
#
# `--mode reuse` IS NOW CARGO-ONLY apart from curl. A `--bbox` extract used to shell
# out to `go-pmtiles`; it now uses tile_build's own `pmtiles_extract`. The go tool is
# still accepted with `--extractor go-pmtiles` for comparison, but nothing needs it.
#
# Usage:
#   ./build_base_layers.sh --mode build --area planet --out base.pmtiles \
#       --jar protomaps-basemap-HEAD-with-deps.jar
#   ./build_base_layers.sh --mode build --area california --out base.pmtiles --jar ...
#   ./build_base_layers.sh --mode reuse --out base.pmtiles
#   ./build_base_layers.sh --mode reuse --bbox -122.6,37.2,-121.7,37.9 --out base.pmtiles
#
# Options:
#   --mode build|reuse     (default: reuse)
#   --out FILE             output base .pmtiles (default base.pmtiles)
#   --area NAME|FILE       Planetiler --area (a geofabrik name like "california",
#                          "planet", or a path to a .osm.pbf). build mode only.
#   --jar FILE             Protomaps basemap planetiler jar. build mode only.
#   --source URL           upstream base for reuse mode
#                          (default https://data.vayunmathur.com/v4.pmtiles — the
#                          app's own hosted base; demo-bucket.protomaps.com is dead/404)
#   --bbox BOX             optional metro bbox for a small build/extract dry run
#   --extractor E          rust|go-pmtiles for a --bbox reuse extract (default rust).
#                          `rust` downloads the archive and subsets it locally, so it
#                          needs the disk and RAM for the whole thing; `go-pmtiles`
#                          range-requests only the tiles it wants, which is the only
#                          practical way to clip the 137 GB planet base.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="reuse"
OUT="base.pmtiles"
AREA="planet"
JAR=""
SOURCE_URL="https://data.vayunmathur.com/v4.pmtiles"
BBOX=""
EXTRACTOR="rust"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode) MODE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --area) AREA="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --source) SOURCE_URL="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --extractor) EXTRACTOR="$2"; shift 2 ;;
        -h|--help) sed -n '4,53p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

case "$EXTRACTOR" in
    rust|go-pmtiles) : ;;
    *) echo "ERROR: --extractor must be rust|go-pmtiles (got '$EXTRACTOR')" >&2; exit 1 ;;
esac

case "$MODE" in
  build)
    # The one documented Java dependency in this pipeline. Loud, not silent: a
    # missing JVM must not quietly become a reuse.
    command -v java >/dev/null || {
        echo "ERROR: java (21+) is required for --mode build." >&2
        echo "  Planetiler is the one part of this pipeline that is NOT ported to" >&2
        echo "  Rust and is not going to be -- it implements the Protomaps schema," >&2
        echo "  and reimplementing it would mean owning that schema." >&2
        echo "  Use --mode reuse (the default), which needs no JVM." >&2
        exit 1
    }
    [[ -n "$JAR" && -f "$JAR" ]] || {
        echo "ERROR: --jar protomaps basemap jar required for build mode." >&2
        echo "  Build it once:" >&2
        echo "    git clone https://github.com/protomaps/basemaps && cd basemaps/tiles" >&2
        echo "    mvn -q clean package" >&2
        echo "    # -> target/protomaps-basemap-HEAD-with-deps.jar" >&2
        exit 1
    }
    ARGS=(--force --download --output="$OUT")
    # --area accepts a geofabrik name or a local .osm.pbf path.
    if [[ -f "$AREA" ]]; then ARGS+=(--osm-path="$AREA"); else ARGS+=(--area="$AREA"); fi
    [[ -n "$BBOX" ]] && ARGS+=(--bounds="$BBOX")
    echo "[base] planetiler build: area=$AREA -> $OUT"
    java -Xmx${JAVA_XMX:-8g} -jar "$JAR" "${ARGS[@]}"
    ;;
  reuse)
    if [[ -z "$BBOX" ]]; then
        echo "[base] downloading upstream base $SOURCE_URL -> $OUT (137 GB for planet!)"
        curl -fL --retry 3 -o "$OUT" "$SOURCE_URL"
    elif [[ "$EXTRACTOR" == "go-pmtiles" ]]; then
        command -v pmtiles >/dev/null || { echo "ERROR: --extractor go-pmtiles needs the pmtiles CLI" >&2; exit 1; }
        echo "[base] reuse+extract (go-pmtiles, range requests) $SOURCE_URL bbox=$BBOX -> $OUT"
        pmtiles extract "$SOURCE_URL" "$OUT" --bbox="$BBOX"
    else
        command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }
        # Our own extractor works on a LOCAL archive, so the whole thing has to come
        # down first. That is fine for a metro-sized base and hopeless for the 137 GB
        # planet -- say so rather than letting someone discover it after an hour.
        SRC="$SOURCE_URL"
        if [[ "$SOURCE_URL" == http://* || "$SOURCE_URL" == https://* ]]; then
            SRC="$OUT.source"
            if [[ -f "$SRC" ]]; then
                echo "[base] reusing already-downloaded $SRC"
            else
                echo "[base] downloading $SOURCE_URL -> $SRC (the whole archive; see" >&2
                echo "       --extractor go-pmtiles if that is the 137 GB planet base)" >&2
                curl -fL --retry 3 -o "$SRC.partial" "$SOURCE_URL"
                mv "$SRC.partial" "$SRC"
            fi
        fi
        echo "[base] reuse+extract (pmtiles_extract) bbox=$BBOX -> $OUT"
        cargo run --release --quiet --manifest-path "$HERE/tile_build/Cargo.toml" \
            --bin pmtiles_extract -- "$SRC" --out "$OUT" --bbox "$BBOX"
    fi
    ;;
  *) echo "ERROR: --mode must be build or reuse" >&2; exit 1 ;;
esac

echo "[base] done: $OUT"
