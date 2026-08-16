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
# Usage:
#   ./build_base_layers.sh --mode build --area planet --out base.pmtiles \
#       --jar protomaps-basemap-HEAD-with-deps.jar
#   ./build_base_layers.sh --mode build --area california --out base.pmtiles --jar ...
#   ./build_base_layers.sh --mode reuse --out base.pmtiles
#
# Options:
#   --mode build|reuse     (default: reuse)
#   --out FILE             output base .pmtiles (default base.pmtiles)
#   --area NAME|FILE       Planetiler --area (a geofabrik name like "california",
#                          "planet", or a path to a .osm.pbf). build mode only.
#   --jar FILE             Protomaps basemap planetiler jar. build mode only.
#   --source URL          upstream base for reuse mode
#                          (default https://demo-bucket.protomaps.com/v4.pmtiles)
#   --bbox BOX             optional metro bbox for a small build/extract dry run

MODE="reuse"
OUT="base.pmtiles"
AREA="planet"
JAR=""
SOURCE_URL="https://demo-bucket.protomaps.com/v4.pmtiles"
BBOX=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode) MODE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --area) AREA="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --source) SOURCE_URL="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        -h|--help) sed -n '4,40p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

case "$MODE" in
  build)
    command -v java >/dev/null || { echo "ERROR: java (21+) required for build mode" >&2; exit 1; }
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
    command -v pmtiles >/dev/null || echo "[base] note: install go-pmtiles for --bbox reuse extracts"
    if [[ -n "$BBOX" ]]; then
        command -v pmtiles >/dev/null || { echo "ERROR: pmtiles CLI required for --bbox reuse" >&2; exit 1; }
        echo "[base] reuse+extract $SOURCE_URL bbox=$BBOX -> $OUT"
        pmtiles extract "$SOURCE_URL" "$OUT" --bbox="$BBOX"
    else
        echo "[base] downloading upstream base $SOURCE_URL -> $OUT (137 GB for planet!)"
        curl -fL --retry 3 -o "$OUT" "$SOURCE_URL"
    fi
    ;;
  *) echo "ERROR: --mode must be build or reuse" >&2; exit 1 ;;
esac

echo "[base] done: $OUT"
