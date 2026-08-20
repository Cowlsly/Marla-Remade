#!/bin/bash
set -euo pipefail

# build_world_transit.sh — build the single global offline transit index
# (`world.transit`) by MERGING every GTFS feed in the Transitous registry into
# one TRX2 pack via `scripts/maps/gtfs_ingest`, then (optionally) publish it to
# R2 (served at data.vayunmathur.com/world.transit).
#
# This mirrors the "single global graph" decision for roads: the app downloads
# ONE transit pack on first open (see MainActivity's InitialDownloadChecker) and
# `OfflineRouter` just iterates `*.transit` — no per-zone logic.
#
# This is a HEAVY data job (tens of GB downloaded, GBs of unzipped GTFS). Run it
# in WSL / Linux with plenty of disk. VALIDATE ON A SMALL SUBSET FIRST with
# `--region` (e.g. a few CA agencies) before attempting the whole world.
#
# Feeds come from the Transitous registry
# (github.com/public-transport/transitous, `feeds/*.json`), which points at each
# agency's official GTFS `.zip` — the same open data behind the P10 online boards.
#
# Requirements: bash, curl, unzip, cargo (to build gtfs_ingest). `jq` is used to
# parse feed JSON when present; otherwise a grep fallback extracts `.zip` URLs.
#
# Usage:
#   ./build_world_transit.sh [options]
#
# Options:
#   --work DIR       Scratch dir for the registry + downloads + unzipped GTFS
#                    (default: ./world_transit_work). Reused/resumable.
#   --out DIR        Output dir for world.transit (+ .json)  (default: --work).
#   --region GLOB    Only use registry files matching this glob under feeds/
#                    (e.g. 'us*', 'de*'). Default '*' (the whole world).
#                    USE THIS for the CA/subset validation run.
#   --limit N        Cap the number of feeds processed (0 = no cap, default 0).
#   --manifest FILE  Skip the registry entirely; build straight from a manifest
#                    of `feed_name=gtfs_dir` lines (already-unzipped feeds).
#   --jobs N         Parallel downloads (default 6).
#   --publish        After building, upload world.transit via publish_r2.sh.
#   --dry-run        Print what would happen; download/build nothing.
#   -h|--help        Show this help.
#
# Examples:
#   # CA-only validation subset (small), no publish:
#   ./build_world_transit.sh --region 'us*' --limit 8 --out /tmp/catransit
#
#   # Whole world, then publish:
#   export R2_ENDPOINT=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=...
#   ./build_world_transit.sh --work /data/transit --publish

HERE="$(cd "$(dirname "$0")" && pwd)"
INGEST_DIR="$HERE/gtfs_ingest"

WORK="./world_transit_work"
OUT=""
REGION="*"
LIMIT=0
MANIFEST=""
JOBS=6
PUBLISH=0
DRY_RUN=0
PACK_NAME="world"
REGISTRY_TARBALL="https://codeload.github.com/public-transport/transitous/tar.gz/refs/heads/main"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --work) WORK="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --region) REGION="$2"; shift 2 ;;
        --limit) LIMIT="$2"; shift 2 ;;
        --manifest) MANIFEST="$2"; shift 2 ;;
        --jobs) JOBS="$2"; shift 2 ;;
        --publish) PUBLISH=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '3,60p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done
OUT="${OUT:-$WORK}"

mkdir -p "$WORK" "$OUT"
GTFS_ROOT="$WORK/gtfs"          # unzipped feeds: $GTFS_ROOT/<feed_name>/
mkdir -p "$GTFS_ROOT"
BUILD_MANIFEST="$WORK/feeds.manifest"

need() { command -v "$1" >/dev/null || { echo "ERROR: '$1' is required but not installed" >&2; exit 1; }; }
need curl
need unzip
need cargo

# --- 1. Build the ingest tool (release) ---
echo "=== building gtfs_ingest (release) ==="
if [[ "$DRY_RUN" == "0" ]]; then
    ( cd "$INGEST_DIR" && cargo build --release )
fi
INGEST_BIN="$INGEST_DIR/target/release/gtfs_ingest"

# A feed name safe for filenames / the string pool (feed provenance on device).
sanitize() { echo "$1" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]\+/_/g; s/^_//; s/_$//'; }

# --- 2. Gather (feed_name  zip_url) pairs, unless a manifest was supplied ---
if [[ -n "$MANIFEST" ]]; then
    echo "=== using supplied manifest $MANIFEST (skipping registry) ==="
    cp "$MANIFEST" "$BUILD_MANIFEST"
else
    REG_DIR="$WORK/transitous"
    if [[ ! -d "$REG_DIR/feeds" ]]; then
        echo "=== fetching Transitous registry ==="
        if [[ "$DRY_RUN" == "0" ]]; then
            mkdir -p "$REG_DIR"
            curl -fsSL "$REGISTRY_TARBALL" | tar -xz -C "$REG_DIR" --strip-components=1
        fi
    else
        echo "=== reusing registry at $REG_DIR ==="
    fi

    echo "=== extracting GTFS zip URLs (region: $REGION) ==="
    # Pull every URL field from the matching feed JSONs and keep the GTFS zips.
    extract_urls() {
        local f="$1"
        if command -v jq >/dev/null; then
            jq -r '[.. | objects | .url? // empty] | .[]' "$f" 2>/dev/null || true
        else
            grep -oE '"url"[[:space:]]*:[[:space:]]*"[^"]+"' "$f" | sed -E 's/.*"url"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/'
        fi
    }

    : > "$WORK/urls.txt"
    shopt -s nullglob
    for f in "$REG_DIR"/feeds/$REGION.json; do
        base="$(basename "$f" .json)"
        i=0
        while IFS= read -r url; do
            [[ -z "$url" ]] && continue
            # Skip GTFS-realtime / non-static endpoints (these are .pb streams,
            # not schedule zips) — e.g. `...gtfs_rt?...&feed_type=trip`.
            case "$url" in
                *gtfs_rt*|*gtfsrt*|*realtime*|*feed_type=*|*.pb|*.pb\?*) continue ;;
            esac
            # Keep only static GTFS schedule feeds (zip, or a gtfs path).
            case "$url" in
                *.zip|*.zip\?*|*gtfs*|*GTFS*) : ;;
                *) continue ;;
            esac
            name="$(sanitize "${base}_${i}")"
            printf '%s\t%s\n' "$name" "$url" >> "$WORK/urls.txt"
            i=$((i+1))
        done < <(extract_urls "$f")
    done
    shopt -u nullglob

    total="$(wc -l < "$WORK/urls.txt" | tr -d ' ')"
    echo "found $total candidate GTFS feeds"
    if [[ "$LIMIT" -gt 0 ]]; then
        head -n "$LIMIT" "$WORK/urls.txt" > "$WORK/urls.trim.txt"
        mv "$WORK/urls.trim.txt" "$WORK/urls.txt"
        echo "limited to $LIMIT feeds"
    fi

    # --- 3. Download + unzip each feed (parallel, resumable) ---
    echo "=== downloading + unzipping feeds (jobs: $JOBS) ==="
    : > "$BUILD_MANIFEST"
    fetch_one() {
        local name="$1" url="$2"
        local zip="$WORK/zips/$name.zip"
        local dir="$GTFS_ROOT/$name"
        mkdir -p "$WORK/zips"
        if [[ -f "$dir/stops.txt" ]]; then return 0; fi
        if ! curl -fsSL --retry 3 -o "$zip" "$url"; then
            echo "  WARN: download failed: $name ($url)" >&2; return 0
        fi
        # Guard against HTML error pages / redirects that aren't a real zip.
        if [[ "$(head -c 2 "$zip" 2>/dev/null)" != "PK" ]]; then
            echo "  WARN: not a GTFS zip (skipped): $name ($url)" >&2; rm -f "$zip"; return 0
        fi
        mkdir -p "$dir"
        if ! unzip -oq "$zip" -d "$dir" 2>/dev/null; then
            echo "  WARN: unzip failed: $name" >&2; rm -rf "$dir"; return 0
        fi
        # Some feeds nest the txt files one dir deep; flatten if needed.
        if [[ ! -f "$dir/stops.txt" ]]; then
            local inner; inner="$(dirname "$(find "$dir" -name stops.txt | head -n1)" 2>/dev/null || true)"
            if [[ -n "$inner" && "$inner" != "$dir" ]]; then mv "$inner"/* "$dir"/ 2>/dev/null || true; fi
        fi
        rm -f "$zip"
    }
    export -f fetch_one sanitize
    export WORK GTFS_ROOT

    if [[ "$DRY_RUN" == "0" ]]; then
        # xargs -P for parallelism; each line is "name<TAB>url".
        awk -F'\t' '{print $1"\t"$2}' "$WORK/urls.txt" \
            | xargs -P "$JOBS" -I{} bash -c 'line="{}"; name="${line%%$'"'"'\t'"'"'*}"; url="${line#*$'"'"'\t'"'"'}"; fetch_one "$name" "$url"'
    fi

    # Build the ingest manifest from feeds that actually have stops.txt.
    : > "$BUILD_MANIFEST"
    for d in "$GTFS_ROOT"/*/; do
        [[ -f "${d}stops.txt" ]] || continue
        n="$(basename "$d")"
        printf '%s=%s\n' "$n" "${d%/}" >> "$BUILD_MANIFEST"
    done
fi

nfeeds="$(wc -l < "$BUILD_MANIFEST" | tr -d ' ')"
echo "=== $nfeeds feeds ready in $BUILD_MANIFEST ==="
if [[ "$nfeeds" -eq 0 ]]; then
    echo "ERROR: no usable feeds — nothing to build" >&2
    exit 1
fi

# --- 4. Merge into a single world.transit ---
echo "=== running gtfs_ingest merge -> $OUT/$PACK_NAME.transit ==="
if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] $INGEST_BIN $OUT $PACK_NAME --manifest $BUILD_MANIFEST"
else
    "$INGEST_BIN" "$OUT" "$PACK_NAME" --manifest "$BUILD_MANIFEST"
    echo "--- size + section breakdown ($OUT/$PACK_NAME.transit.json) ---"
    cat "$OUT/$PACK_NAME.transit.json"
fi

# --- 5. Publish (optional) ---
if [[ "$PUBLISH" == "1" ]]; then
    echo "=== publishing $PACK_NAME.transit to R2 ==="
    if [[ "$DRY_RUN" == "1" ]]; then
        echo "[dry-run] would run publish_r2.sh $OUT/$PACK_NAME.transit --key $PACK_NAME.transit"
    else
        "$HERE/publish_r2.sh" "$OUT/$PACK_NAME.transit" --key "$PACK_NAME.transit"
    fi
fi

echo "Done."
