#!/bin/bash
set -euo pipefail

# build_world_transit.sh — build the single global offline transit index
# (`world.transit`) by resolving the Transitous registry against
# transitland-atlas, downloading every static GTFS feed, and merging them into one
# TRX2 pack via `scripts/maps/gtfs_ingest`.
#
# This mirrors the "single global graph" decision for roads: the app downloads ONE
# transit pack on first open (see MainActivity's InitialDownloadChecker) and
# `OfflineRouter` just iterates `*.transit` — no per-zone logic.
#
# WHAT CHANGED, AND WHY IT MATTERS. This script used to scrape `url` fields out of
# the registry. Most US state files reference feeds by `transitland-atlas-id` with
# no URL at all, so 38 of California's 49 sources were silently dropped. Its feed
# names were also positional (`us_ca_0`, `us_ca_1`, …), which meant a two-field
# manifest, which meant **no MOTIS stop ids** — and without those, live delays
# cannot be matched to a stop. `gtfs_ingest`'s `resolve_feeds` now does the atlas
# resolution `build_ca_transit.ps1` pioneered, and emits the three-field manifest
# `manifest.rs::parse_feed_spec` reads.
#
# THIS IS A HEAVY DATA JOB and a large step up from what it replaces: hundreds of
# feeds and many GB of downloads, against the 18.9 MB pack the `.url`-only scrape
# produced. **`--region` and `--max-feeds` are how you stage it** — California
# first, which is the known-good case, then a few regions, then the world. Run it
# in WSL / Linux with plenty of disk.
#
# THE DIVISION OF LABOUR: this script owns network I/O — the two registry
# tarballs, and every feed zip — and `resolve_feeds` owns the resolution. That is
# what keeps `gtfs_ingest` dependency-free, and it is why downloads are cached
# here rather than there.
#
# Downloads are CONTENT-ADDRESSED: a feed's zip is stored under the SHA-256 of its
# URL, so a re-run with a different `--region` or `--max-feeds` re-uses everything
# it already has instead of re-fetching by feed name. Feed names are not stable
# across registry updates; URLs are.
#
# Requirements: bash, curl, unzip, cargo, tar, and sha256sum (or shasum).
#
# Usage:
#   ./build_world_transit.sh [options]
#
# Options:
#   --work DIR       Scratch dir for the registries + downloads + unzipped GTFS
#                    (default: ./world_transit_work). Reused/resumable.
#   --out DIR        Output dir for world.transit (+ .json)  (default: --work).
#   --region GLOB    Only resolve registry files matching this glob under feeds/
#                    (e.g. 'us-ca', 'us-*', 'de-*'). Default '*' (the whole world).
#                    USE THIS to stage the build.
#   --max-feeds N    Cap the number of feeds (0 = no cap, default 0). Applied to a
#                    name-sorted list, so the cap takes a stable prefix.
#   --manifest FILE  Skip the registries entirely; build straight from a manifest
#                    of `name=dir[=motis_prefix]` lines (already-unzipped feeds).
#   --jobs N         Parallel downloads (default 6).
#   --pack-name NAME Output pack name (default 'world' -> world.transit).
#   --resolve-only   Resolve and report the plan, then stop. Downloads nothing.
#   --refresh        Re-fetch the registry tarballs even if they are cached.
#   --publish        After building, upload the pack via publish_r2.sh.
#   --dry-run        Print what would happen; download/build nothing.
#   -h|--help        Show this help.
#
# Examples:
#   # California only, the known-good staging step:
#   ./build_world_transit.sh --region 'us-ca' --out /tmp/catransit
#
#   # See what the whole world would resolve to, without fetching a byte:
#   ./build_world_transit.sh --resolve-only
#
#   # Whole world, then publish:
#   export R2_ENDPOINT=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=...
#   ./build_world_transit.sh --work /data/transit --publish

HERE="$(cd "$(dirname "$0")" && pwd)"
INGEST_DIR="$HERE/gtfs_ingest"

WORK="./world_transit_work"
OUT=""
REGION="*"
MAX_FEEDS=0
MANIFEST=""
JOBS=6
PACK_NAME="world"
RESOLVE_ONLY=0
REFRESH=0
PUBLISH=0
DRY_RUN=0

TRANSITOUS_TARBALL="https://codeload.github.com/public-transport/transitous/tar.gz/refs/heads/main"
ATLAS_TARBALL="https://codeload.github.com/transitland/transitland-atlas/tar.gz/refs/heads/main"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --work) WORK="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --region) REGION="$2"; shift 2 ;;
        --max-feeds) MAX_FEEDS="$2"; shift 2 ;;
        --manifest) MANIFEST="$2"; shift 2 ;;
        --jobs) JOBS="$2"; shift 2 ;;
        --pack-name) PACK_NAME="$2"; shift 2 ;;
        --resolve-only) RESOLVE_ONLY=1; shift ;;
        --refresh) REFRESH=1; shift ;;
        --publish) PUBLISH=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '4,71p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done
OUT="${OUT:-$WORK}"

need() { command -v "$1" >/dev/null || { echo "ERROR: '$1' is required but not installed" >&2; exit 1; }; }
need curl
need unzip
need cargo
need tar

sha256_of_string() {
    if command -v sha256sum >/dev/null; then
        printf '%s' "$1" | sha256sum | cut -d' ' -f1
    elif command -v shasum >/dev/null; then
        printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1
    else
        echo "ERROR: need sha256sum or shasum for the download cache" >&2
        exit 1
    fi
}
export -f sha256_of_string

mkdir -p "$WORK" "$OUT"
GTFS_ROOT="$WORK/gtfs"          # unzipped feeds: $GTFS_ROOT/<feed_name>/
CACHE="$WORK/cache"             # content-addressed zips: $CACHE/<sha256 of url>.zip
mkdir -p "$GTFS_ROOT" "$CACHE"
BUILD_MANIFEST="$WORK/feeds.manifest"
PLAN="$WORK/plan.tsv"

# --- 1. Build the ingest tools (release) ---
echo "=== building gtfs_ingest (release) ==="
if [[ "$DRY_RUN" == "0" ]]; then
    ( cd "$INGEST_DIR" && cargo build --release )
fi
INGEST_BIN="$INGEST_DIR/target/release/gtfs_ingest"
RESOLVE_BIN="$INGEST_DIR/target/release/resolve_feeds"

# --- 2. Resolve the plan, unless a manifest was supplied ---
if [[ -n "$MANIFEST" ]]; then
    echo "=== using supplied manifest $MANIFEST (skipping the registries) ==="
    cp "$MANIFEST" "$BUILD_MANIFEST"
else
    # Fetch a registry tarball and extract it. Network I/O belongs here, not in
    # resolve_feeds, which is what keeps gtfs_ingest dependency-free.
    get_registry() {
        local url="$1" dir="$2" label="$3"
        if [[ -d "$dir/feeds" && "$REFRESH" == "0" ]]; then
            echo "=== reusing $label at $dir ==="
            return
        fi
        echo "=== fetching $label ==="
        if [[ "$DRY_RUN" == "1" ]]; then
            echo "[dry-run] curl -fsSL $url | tar -xz -C $dir --strip-components=1"
            return
        fi
        rm -rf "$dir"
        mkdir -p "$dir"
        curl -fsSL "$url" | tar -xz -C "$dir" --strip-components=1
        [[ -d "$dir/feeds" ]] || { echo "ERROR: $label has no feeds/ dir after extraction" >&2; exit 1; }
    }
    REG_DIR="$WORK/transitous"
    ATLAS_DIR="$WORK/transitland-atlas"
    get_registry "$TRANSITOUS_TARBALL" "$REG_DIR" "Transitous registry"
    get_registry "$ATLAS_TARBALL" "$ATLAS_DIR" "transitland-atlas"

    echo "=== resolving feeds (region: $REGION, max: $MAX_FEEDS) ==="
    RESOLVE_ARGS=(--registry "$REG_DIR" --atlas "$ATLAS_DIR" --out "$PLAN"
                  --region "$REGION" --report)
    [[ "$MAX_FEEDS" -gt 0 ]] && RESOLVE_ARGS+=(--max-feeds "$MAX_FEEDS")
    if [[ "$DRY_RUN" == "1" ]]; then
        echo "[dry-run] $RESOLVE_BIN ${RESOLVE_ARGS[*]}"
        echo "Done (dry run)."
        exit 0
    fi
    "$RESOLVE_BIN" "${RESOLVE_ARGS[@]}"

    if [[ "$RESOLVE_ONLY" == "1" ]]; then
        echo "=== --resolve-only: the plan is in $PLAN, nothing downloaded ==="
        exit 0
    fi

    # --- 3. Download + unzip each feed (parallel, resumable) ---
    echo "=== downloading + unzipping feeds (jobs: $JOBS) ==="
    fetch_one() {
        local name="$1" url="$2"
        local dir="$GTFS_ROOT/$name"
        # Already unzipped: nothing to do, however the zip got here.
        if [[ -f "$dir/stops.txt" ]]; then return 0; fi
        # Content-addressed on the URL, not the feed name: names shift when the
        # registry is updated, URLs do not, so this cache survives a re-resolve.
        local zip="$CACHE/$(sha256_of_string "$url").zip"
        if [[ ! -f "$zip" ]]; then
            if ! curl -fsSL --retry 3 -o "$zip.partial" "$url"; then
                echo "  WARN: download failed: $name ($url)" >&2
                rm -f "$zip.partial"
                return 0
            fi
            # Guard against HTML error pages / auth walls that are not a real zip.
            if [[ "$(head -c 2 "$zip.partial" 2>/dev/null)" != "PK" ]]; then
                echo "  WARN: not a GTFS zip, probably an auth wall (skipped): $name ($url)" >&2
                rm -f "$zip.partial"
                return 0
            fi
            # Renamed only once complete, so an interrupted run never leaves a
            # truncated zip that looks cached.
            mv "$zip.partial" "$zip"
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
        if [[ ! -f "$dir/stops.txt" ]]; then
            echo "  WARN: no stops.txt in the zip: $name" >&2; rm -rf "$dir"
        fi
    }
    export -f fetch_one
    export GTFS_ROOT CACHE

    # `name<TAB>url<TAB>motis_prefix`; the prefix is not needed to download.
    awk -F'\t' 'NF>=2 {print $1"\t"$2}' "$PLAN" \
        | xargs -P "$JOBS" -I{} bash -c 'line="{}"; name="${line%%$'"'"'\t'"'"'*}"; url="${line#*$'"'"'\t'"'"'}"; fetch_one "$name" "$url"'

    # --- 4. Build the three-field manifest from the plan, not from disk ---
    # From the plan, so the MOTIS prefix survives -- it exists nowhere on disk --
    # and so a stale feed left over from an earlier --region is not picked up.
    : > "$BUILD_MANIFEST"
    while IFS=$'\t' read -r name url prefix; do
        [[ -n "$name" ]] || continue
        dir="$GTFS_ROOT/$name"
        [[ -f "$dir/stops.txt" ]] || continue
        printf '%s=%s=%s\n' "$name" "$dir" "$prefix" >> "$BUILD_MANIFEST"
    done < "$PLAN"
fi

nfeeds="$(wc -l < "$BUILD_MANIFEST" | tr -d ' ')"
planned="$( [[ -f "$PLAN" ]] && wc -l < "$PLAN" | tr -d ' ' || echo "$nfeeds" )"
echo "=== $nfeeds of $planned feed(s) usable, listed in $BUILD_MANIFEST ==="
if [[ "$nfeeds" -eq 0 ]]; then
    echo "ERROR: no usable feeds — nothing to build" >&2
    exit 1
fi
# A MOTIS prefix is what lets a live delay be matched to a stop, so a manifest
# without them builds a pack that silently cannot do that.
withprefix="$(awk -F'=' 'NF>=3 && $3 != "" {n++} END {print n+0}' "$BUILD_MANIFEST")"
if [[ "$withprefix" -lt "$nfeeds" ]]; then
    echo "WARNING: $((nfeeds - withprefix)) feed(s) have no MOTIS prefix; their stops" >&2
    echo "         cannot be matched to live delays." >&2
fi

# --- 5. Merge into a single pack ---
echo "=== running gtfs_ingest merge -> $OUT/$PACK_NAME.transit ==="
"$INGEST_BIN" "$OUT" "$PACK_NAME" --manifest "$BUILD_MANIFEST"
echo "--- size + section breakdown ($OUT/$PACK_NAME.transit.json) ---"
cat "$OUT/$PACK_NAME.transit.json"

# --- 6. Publish (optional) ---
if [[ "$PUBLISH" == "1" ]]; then
    echo "=== publishing $PACK_NAME.transit to R2 ==="
    "$HERE/publish_r2.sh" "$OUT/$PACK_NAME.transit" --key "$PACK_NAME.transit"
fi

echo "Done."
