#!/bin/bash
set -euo pipefail

# build_world_transit.sh — build the single global offline transit index
# (`world.transit`) by mirroring Transitous' own published GTFS directory and
# merging every zip into one TRX2 pack via `scripts/maps/gtfs_ingest`.
#
# This mirrors the "single global graph" decision for roads: the app downloads ONE
# transit pack on first open (see MainActivity's InitialDownloadChecker) and
# `OfflineRouter` just iterates `*.transit` — no per-zone logic.
#
# ONE HOST, ONE MIRROR. Every feed comes from https://api.transitous.org/gtfs/,
# which is Transitous' own already-fetched, already-normalised output. Nothing here
# talks to an agency, a national access point, or a rehosting site.
#
# That replaced resolving the Transitous registry against transitland-atlas and then
# fetching each feed from its upstream URL. Those upstreams are third parties in
# every state of disrepair: 401s from WMATA and the Spanish NAP, 403s, 404s from
# dead rehosts, hostname-mismatched TLS certs, broken pipes. Every one of them was a
# warning to be read and ignored, and a feed silently missing from the pack.
# Transitous already solves that problem — including the API keys we do not have —
# so the fix is to take its output instead of repeating its work badly.
#
# THE FEED ID. A zip is `<region>_<Source>.gtfs.zip` and its MOTIS feed id is
# `<region>-<Source>` — the same string `resolve_feeds` used to compose, and the
# same one Transitous names its `scripts/*.lua` transforms after
# (`au-vic_Transport-Victoria-metro-trains.gtfs.zip` <-> `au-vic-Transport-Victoria-
# metro-trains.lua`). Only the FIRST underscore is a separator; the rest of the name
# keeps its own hyphens and its case. Without that id the pack carries no MOTIS stop
# ids and live delays cannot be matched to a stop.
#
# THIS IS A HEAVY DATA JOB: ~2250 feeds and ~10 GB mirrored. **`--region` is how you
# stage it** — California first, which is the known-good case, then the world. Run it
# in WSL / Linux with plenty of disk. The mirror is timestamp-based, so a re-run
# re-fetches only what changed upstream.
#
# Requirements: bash, wget, unzip, cargo, and sha256sum (or shasum).
#
# Usage:
#   ./build_world_transit.sh [options]
#
# Options:
#   --work DIR       Scratch dir for the mirror + unzipped GTFS
#                    (default: ./world_transit_work). Reused/resumable.
#   --out DIR        Output dir for world.transit (+ .json)  (default: --work).
#   --region GLOB    Only use feeds whose region (the part before the first `_`)
#                    matches this glob: 'us-ca', 'us-*', 'de-*'. Default '*' (the
#                    whole world). USE THIS to stage the build.
#   --max-feeds N    Cap the number of feeds (0 = no cap, default 0). Applied to a
#                    name-sorted list, so the cap takes a stable prefix.
#   --manifest FILE  Skip the mirror entirely; build straight from a manifest
#                    of `name=dir[=motis_prefix]` lines (already-unzipped feeds).
#   --mirror-dir DIR Where the mirror lives (default <work>/transitous). Point this
#                    at an existing mirror to build fully offline.
#   --skip-mirror    Use whatever is already in --mirror-dir; fetch nothing.
#   --rate LIMIT     wget --limit-rate (default 30m). Be kind to a volunteer host.
#   --pack-name NAME Output pack name (default 'world' -> world.transit).
#   --list-only      List the feeds that would be used, then stop.
#   --publish        After building, upload the pack via publish_r2.sh.
#   --dry-run        Print what would happen; download/build nothing.
#   -h|--help        Show this help.
#
# Examples:
#   # California only, the known-good staging step:
#   ./build_world_transit.sh --region 'us-ca' --out /tmp/catransit
#
#   # See which feeds the mirror offers, without building a pack:
#   ./build_world_transit.sh --list-only
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
# How many feeds to unzip at once.
#
# The unzip loop only; the wget mirror below stays strictly serial on purpose --
# see its own comment. Unzipping is CPU-bound (inflate) and touches only per-feed
# directories, so this is the one place in this script that fans out safely.
JOBS="${MAPS_JOBS:-4}"
MIRROR_DIR=""
SKIP_MIRROR=0
RATE="30m"
PACK_NAME="world"
LIST_ONLY=0
PUBLISH=0
DRY_RUN=0

# Transitous' published output: the zips it has already fetched and normalised,
# plus the `scripts/` transforms that name each feed's MOTIS id.
GTFS_INDEX_URL="https://api.transitous.org/gtfs/"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --work) WORK="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --region) REGION="$2"; shift 2 ;;
        --max-feeds) MAX_FEEDS="$2"; shift 2 ;;
        --manifest) MANIFEST="$2"; shift 2 ;;
        --jobs) JOBS="$2"; shift 2 ;;
        --mirror-dir) MIRROR_DIR="$2"; shift 2 ;;
        --skip-mirror) SKIP_MIRROR=1; shift ;;
        --rate) RATE="$2"; shift 2 ;;
        --pack-name) PACK_NAME="$2"; shift 2 ;;
        --list-only) LIST_ONLY=1; shift ;;
        --publish) PUBLISH=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '4,72p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done
OUT="${OUT:-$WORK}"
MIRROR_DIR="${MIRROR_DIR:-$WORK/transitous}"

need() { command -v "$1" >/dev/null || { echo "ERROR: '$1' is required but not installed" >&2; exit 1; }; }
need unzip
need cargo
# Only when this run will actually fetch: a --dry-run, a supplied manifest or an
# existing mirror all get by without it.
[[ "$SKIP_MIRROR" == "1" || "$DRY_RUN" == "1" || -n "$MANIFEST" ]] || need wget

mkdir -p "$WORK" "$OUT"
GTFS_ROOT="$WORK/gtfs"          # unzipped feeds: $GTFS_ROOT/<feed_name>/
mkdir -p "$GTFS_ROOT"
BUILD_MANIFEST="$WORK/feeds.manifest"

# Delete a failed feed's unzip directory, and refuse to delete anything else.
#
# Every recursive delete in this script goes through here. It exists because the
# flatten step below once computed a path of `.` and handed the repo root to a
# plain `rm -rf`: a guard that asserts the target is a real subdirectory of
# GTFS_ROOT turns that class of mistake into a refusal instead of data loss.
discard_feed_dir() {
    local target="$1" root
    root="$(cd "$GTFS_ROOT" 2>/dev/null && pwd -P)" || return 0
    [[ -n "$target" && -d "$target" ]] || return 0
    target="$(cd "$target" 2>/dev/null && pwd -P)" || return 0
    # Must be strictly inside GTFS_ROOT: not the root, not a parent, not elsewhere.
    if [[ "$target" == "$root"/?* ]]; then
        rm -rf "$target"
    else
        echo "  REFUSED to delete '$target': not inside $root" >&2
    fi
}

# `Get-SafeName`, matching gtfs_ingest's registry::safe_name: lowercase, and every
# run of non-alphanumerics becomes one `_`. Used for the unzip directory only.
safe_name() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/_/g; s/^_+//; s/_+$//'
}

# --- 1. Build the ingest tool (release) ---
echo "=== building gtfs_ingest (release) ==="
if [[ "$DRY_RUN" == "0" ]]; then
    ( cd "$INGEST_DIR" && cargo build --release )
fi
INGEST_BIN="$INGEST_DIR/target/release/gtfs_ingest"

# --- 2. Mirror Transitous' published GTFS directory ---
if [[ -n "$MANIFEST" ]]; then
    echo "=== using supplied manifest $MANIFEST (skipping the mirror) ==="
    cp "$MANIFEST" "$BUILD_MANIFEST"
else
    if [[ "$SKIP_MIRROR" == "1" ]]; then
        echo "=== --skip-mirror: using the existing mirror at $MIRROR_DIR ==="
    else
        echo "=== mirroring $GTFS_INDEX_URL -> $MIRROR_DIR (rate: $RATE) ==="
        mkdir -p "$MIRROR_DIR"
        # ONE host, ONE recursive fetch. --mirror is timestamp-based, so a re-run
        # only pulls what changed upstream. The `scripts/` transforms come along
        # because they name each feed's MOTIS id, which is worth having on disk
        # next to the zips it identifies.
        #
        # DO NOT fan this out. `--limit-rate` is per-process, so N parallel wgets
        # silently multiply the load on a volunteer host by N -- the rate limit
        # would still read 30m in the arguments while the host saw N x 30m. The
        # unzip loop below is where this script gets its concurrency; overlap the
        # download with CPU work instead of splitting the download.
        MIRROR_ARGS=(
            --limit-rate="$RATE" --mirror -l 2 --no-parent
            --cut-dirs=1 --no-host-directories
            --include-directories=gtfs,gtfs/scripts
            --accept '.zip' --accept '.lua' --accept 'config.yml'
            -e robots=off
            -P "$MIRROR_DIR"
            "$GTFS_INDEX_URL"
        )
        if [[ "$DRY_RUN" == "1" ]]; then
            echo "[dry-run] wget ${MIRROR_ARGS[*]}"
        else
            # wget exits 8 on a server error for any single file. With ~2250 files
            # one 404 must not fail the whole build, so the exit code is reported
            # and the feed count below is what actually gates the build.
            wget "${MIRROR_ARGS[@]}" || echo "  NOTE: wget exited $? (partial mirror is expected; see the feed count)" >&2
        fi
    fi

    # --- 3. Enumerate, filter, unzip ---
    mapfile -t ZIPS < <(find "$MIRROR_DIR" -name '*.gtfs.zip' -type f 2>/dev/null | sort)
    if [[ "$DRY_RUN" == "1" && "${#ZIPS[@]}" -eq 0 ]]; then
        echo "[dry-run] no mirror on disk yet; nothing to enumerate"
        echo "Done (dry run)."
        exit 0
    fi
    echo "=== ${#ZIPS[@]} zip(s) in the mirror; region filter: $REGION ==="

    : > "$BUILD_MANIFEST"

    # --- 3a. Select, serially, in sorted order -------------------------------
    #
    # Cheap (name parsing and a glob match, no I/O), and it has to be serial anyway
    # because `--max-feeds` contractually takes a stable prefix of the name-sorted
    # list. Unzipping is the expensive part and happens below.
    CAND_NAME=(); CAND_DIR=(); CAND_PREFIX=(); CAND_ZIP=()
    for zip in ${ZIPS[@]+"${ZIPS[@]}"}; do
        stem="$(basename "$zip" .gtfs.zip)"
        # `<region>_<Source>`: only the FIRST underscore separates them, and the
        # MOTIS id rejoins them with a hyphen. Everything after keeps its own
        # hyphens and its case -- `us-ca_SF-bayarea` -> `us-ca-SF-bayarea`.
        region="${stem%%_*}"
        source_name="${stem#*_}"
        [[ "$source_name" != "$stem" ]] || source_name=""
        # shellcheck disable=SC2254  # REGION is a glob on purpose
        case "$region" in
            $REGION) : ;;
            *) continue ;;
        esac
        if [[ -z "$source_name" ]]; then
            echo "  WARN: '$stem' has no <region>_<source> split; skipped" >&2
            continue
        fi
        CAND_NAME+=("$(safe_name "$stem")")
        CAND_PREFIX+=("$region-$source_name")
        CAND_ZIP+=("$zip")
        CAND_DIR+=("$GTFS_ROOT/$(safe_name "$stem")")
    done
    NCAND="${#CAND_NAME[@]}"

    if [[ "$LIST_ONLY" == "1" ]]; then
        used=0
        for ((i = 0; i < NCAND; i++)); do
            [[ "$MAX_FEEDS" -gt 0 && "$used" -ge "$MAX_FEEDS" ]] && break
            printf '%s\t%s\t%s\n' "${CAND_NAME[$i]}" "${CAND_PREFIX[$i]}" "${CAND_ZIP[$i]}"
            used=$((used + 1))
        done
        echo "=== --list-only: $used feed(s) would be used ==="
        exit 0
    fi

    # --- 3b. Unzip, in parallel, in waves ------------------------------------
    #
    # Embarrassingly parallel: each feed is its own zip and its own directory. Two
    # things constrain how:
    #
    #   * The manifest must stay in sorted order, so each feed writes its line to
    #     its OWN fragment file and the fragments are concatenated by index. A
    #     shared append would interleave and make the order completion order.
    #   * `--max-feeds` counts feeds that actually unzipped, not feeds attempted, so
    #     the work goes out in waves and the count is checked between them. Selecting
    #     N candidates up front instead would silently return fewer than N whenever a
    #     zip was corrupt.
    #
    # A feed that fails is a warning, not a failure: one bad zip out of hundreds must
    # not lose the build. Failure is signalled by an ABSENT fragment rather than an
    # exit code, so the reap loop needs no error plumbing.
    FRAG_DIR="$WORK/frag"
    rm -rf "$FRAG_DIR"
    mkdir -p "$FRAG_DIR"

    unzip_feed() {
        local i="$1"
        local zip="${CAND_ZIP[$i]}" dir="${CAND_DIR[$i]}"
        local name="${CAND_NAME[$i]}" prefix="${CAND_PREFIX[$i]}"
        local stem
        stem="$(basename "$zip" .gtfs.zip)"
        if [[ ! -f "$dir/stops.txt" ]]; then
            mkdir -p "$dir"
            if ! unzip -oq "$zip" -d "$dir" 2>/dev/null; then
                echo "  WARN: unzip failed: $stem" >&2
                discard_feed_dir "$dir"
                return 0
            fi
            # Some feeds nest the txt files one dir deep; flatten if needed.
            if [[ ! -f "$dir/stops.txt" ]]; then
                local found inner
                found="$(find "$dir" -name stops.txt 2>/dev/null | head -n1)"
                # Test the find RESULT, not the dirname of it. `dirname ""` is `.`,
                # so a zip with no stops.txt anywhere used to set inner="." and run
                # `mv ./* "$dir"/` -- moving the whole current working directory
                # into the feed dir, which the discard below then deleted.
                if [[ -n "$found" ]]; then
                    inner="$(dirname "$found")"
                    if [[ "$inner" != "$dir" ]]; then
                        mv "$inner"/* "$dir"/ 2>/dev/null || true
                    fi
                fi
            fi
            if [[ ! -f "$dir/stops.txt" ]]; then
                echo "  WARN: no stops.txt in the zip: $stem" >&2
                discard_feed_dir "$dir"
                return 0
            fi
        fi
        printf '%s=%s=%s\n' "$name" "$dir" "$prefix" > "$FRAG_DIR/$i.line"
        return 0
    }

    used=0
    next=0
    while [[ "$next" -lt "$NCAND" ]]; do
        [[ "$MAX_FEEDS" -gt 0 && "$used" -ge "$MAX_FEEDS" ]] && break
        wave_pids=(); wave_idx=()
        while [[ "${#wave_pids[@]}" -lt "$JOBS" && "$next" -lt "$NCAND" ]]; do
            unzip_feed "$next" &
            wave_pids+=("$!")
            wave_idx+=("$next")
            next=$((next + 1))
        done
        for p in ${wave_pids[@]+"${wave_pids[@]}"}; do
            wait "$p" || true
        done
        # In index order, so the manifest stays sorted however the wave finished.
        for i in ${wave_idx[@]+"${wave_idx[@]}"}; do
            [[ "$MAX_FEEDS" -gt 0 && "$used" -ge "$MAX_FEEDS" ]] && break
            if [[ -s "$FRAG_DIR/$i.line" ]]; then
                cat "$FRAG_DIR/$i.line" >> "$BUILD_MANIFEST"
                used=$((used + 1))
            fi
        done
        echo "  ...$used feed(s) unzipped of $NCAND candidate(s)"
    done
    rm -rf "$FRAG_DIR"
fi

nfeeds="$(wc -l < "$BUILD_MANIFEST" | tr -d ' ')"
echo "=== $nfeeds feed(s) usable, listed in $BUILD_MANIFEST ==="
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

# --- 4. Merge into a single pack ---
echo "=== running gtfs_ingest merge -> $OUT/$PACK_NAME.transit ==="
"$INGEST_BIN" "$OUT" "$PACK_NAME" --manifest "$BUILD_MANIFEST"
echo "--- size + section breakdown ($OUT/$PACK_NAME.transit.json) ---"
cat "$OUT/$PACK_NAME.transit.json"

# --- 5. Publish (optional) ---
if [[ "$PUBLISH" == "1" ]]; then
    echo "=== publishing $PACK_NAME.transit to R2 ==="
    "$HERE/publish_r2.sh" "$OUT/$PACK_NAME.transit" --key "$PACK_NAME.transit"
fi

echo "Done."
