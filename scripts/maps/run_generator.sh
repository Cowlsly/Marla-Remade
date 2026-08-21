#!/bin/bash
set -o pipefail

# --- CONFIGURATION ---
# Load environment variables from .env file if it exists
if [ -f .env ]; then
    echo "Loading configuration from .env..."
    set -a
    source .env
    set +a
fi

R2_ENDPOINT="${R2_ENDPOINT:-https://<ACCOUNT_ID>.r2.cloudflarestorage.com}"
BUCKET_NAME="${BUCKET_NAME:-maps}"
ACCESS_KEY="${R2_ACCESS_KEY:-ACCESS_KEY_HERE}"
SECRET_KEY="${R2_SECRET_KEY:-SECRET_KEY_HERE}"
DEFAULT_PBF="california-latest.osm.pbf"
DATA_DIR="map_data"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INGEST_DIR="$HERE/osm_ingest"

# Helper to run commands with sudo only if available
run_cmd() {
    if command -v sudo >/dev/null 2>&1; then
        sudo "$@"
    else
        "$@"
    fi
}

# Use provided argument or default to california-latest.osm.pbf
INPUT_PBF="${1:-$DEFAULT_PBF}"

# --- 1. SYSTEM DEPENDENCIES ---
# The graph builder is Rust (scripts/maps/osm_ingest) and reads .osm.pbf
# natively, so there is no libosmium/g++/zlib toolchain to install any more --
# only cargo, and the AWS CLI for the upload. On Windows use build_graph.ps1
# instead; it runs the same builder without needing bash at all.
echo "[1/5] Checking build tools..."
command -v cargo >/dev/null || { echo "ERROR: cargo not installed (https://rustup.rs)" >&2; exit 1; }

# --- 2. AWS CLI INSTALLATION ---
if ! command -v aws &> /dev/null; then
    echo "[2/5] Installing AWS CLI v2..."
    curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
    unzip -q awscliv2.zip
    run_cmd ./aws/install
    rm -rf aws awscliv2.zip
else
    echo "[2/5] AWS CLI is already installed."
fi

# --- 3. DATA DOWNLOAD ---
if [ ! -f "$INPUT_PBF" ]; then
    echo "[3/5] $INPUT_PBF not found. Downloading California extract..."
    curl -OL "https://download.geofabrik.de/north-america/us/california-latest.osm.pbf"
else
    echo "[3/5] $INPUT_PBF already exists. Skipping download."
fi

# --- 4. CLOUDFLARE R2 CONFIGURATION ---
echo "[4/5] Configuring credentials for R2..."
aws configure set aws_access_key_id "$ACCESS_KEY" --profile r2
aws configure set aws_secret_access_key "$SECRET_KEY" --profile r2
aws configure set region auto --profile r2

# --- 5. GRAPH BUILD ---
# Must be gated: on failure the output files have already been truncated by the
# builder, and syncing those to R2 would publish a partial graph over the live one.
echo "[5/5] Building the routing graph..."
if ! cargo run --release --manifest-path "$INGEST_DIR/Cargo.toml" --bin road_graph -- \
        "$INPUT_PBF" --out "$DATA_DIR"; then
    echo "ERROR: road_graph failed; not uploading anything." >&2
    exit 1
fi

# --- 6. AUTOMATED UPLOAD ---
echo "Uploading files to Cloudflare R2..."

# 6a. Upload the SINGLE GLOBAL routing graph (no per-zone artifacts).
# These are exactly the files maps/src/main/rust/src/graph.rs mmaps from one
# directory. The offline TILE packs (zone_*.pmtiles) and the P11 transit index
# (world.transit) are published separately (extract_pmtiles.sh / gtfs_ingest)
# and are intentionally NOT part of this graph upload.
#
# poi_names.bin / poi_index.bin are the P27 POI side files emitted by
# build_v5_pmtiles.sh (build_pois_layer.sh) beside v5.pmtiles. Copy them into
# "$DATA_DIR" before this sync (or point --workdir/--out there) so the app can
# fetch them from the same data.vayunmathur.com host as the graph.
echo "Syncing single global graph to R2..."

aws s3 sync "$DATA_DIR" "s3://$BUCKET_NAME/" \
    --endpoint-url "$R2_ENDPOINT" \
    --profile r2 \
    --content-type "application/octet-stream" \
    --exclude "*" \
    --include "metadata.bin" \
    --include "road_names.bin" \
    --include "nodes.bin" \
    --include "edges.bin" \
    --include "lanes.bin" \
    --include "poi_names.bin" \
    --include "poi_index.bin"

echo "---------------------------------------------------------"
echo "Pipeline Finished Successfully."
echo "---------------------------------------------------------"