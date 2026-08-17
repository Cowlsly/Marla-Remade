#!/bin/bash
set -euo pipefail

# publish_r2.sh — upload built PMTiles (e.g. v5.pmtiles) to Cloudflare R2.
#
# SECURITY: this script reads ALL credentials/config from ENVIRONMENT VARIABLES.
# No secrets are stored in the repo. Never paste keys on the command line (they
# leak into shell history) — `export` them instead, ideally from a secret store.
#
# Required env:
#   R2_ENDPOINT           S3 API endpoint, e.g. https://<ACCOUNT_ID>.r2.cloudflarestorage.com
#   R2_ACCESS_KEY_ID      R2 access key id
#   R2_SECRET_ACCESS_KEY  R2 secret access key
# Optional env:
#   R2_BUCKET             Default: maps   (served at data.vayunmathur.com)
#
# Usage:
#   export R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
#   export R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=...
#   ./publish_r2.sh v5.pmtiles                 # -> s3://$R2_BUCKET/v5.pmtiles
#   ./publish_r2.sh v5.pmtiles --key v5.pmtiles
#   ./publish_r2.sh v5.pmtiles maxspeed.pmtiles  # multiple files (aux/siblings)
#
# Options:
#   --key NAME       Remote object key for the FIRST file (default: its basename).
#                    Ignored when uploading multiple files (each keeps its name).
#   --tool T         Upload tool: auto|rclone|aws (default: auto — prefer rclone).
#   --content-type C MIME type (default: application/octet-stream — fine for pmtiles).
#   --dry-run        Print what would happen; upload nothing.
#   -h|--help        Show this help.
#
# Note: v5.pmtiles BAKES the maxspeed layer in, so normally you only publish the
# single v5.pmtiles. The multi-file form exists for aux/sibling artifacts if you
# ever build them separately.

FILES=()
KEY=""
TOOL="auto"
CONTENT_TYPE="application/octet-stream"
CACHE_CONTROL="public, max-age=31536000, immutable"
DRY_RUN=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --key) KEY="$2"; shift 2 ;;
        --tool) TOOL="$2"; shift 2 ;;
        --content-type) CONTENT_TYPE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '4,40p' "$0" | sed 's/^# \?//'; exit 0 ;;
        -*) echo "Unknown arg: $1" >&2; exit 1 ;;
        *) FILES+=("$1"); shift ;;
    esac
done

# Default to the canonical build output if no file was given.
if [[ ${#FILES[@]} -eq 0 ]]; then
    FILES=("v5.pmtiles")
fi

# --- Validate required environment (fail clearly; list everything missing) ---
MISSING=()
[[ -n "${R2_ENDPOINT:-}" ]]          || MISSING+=("R2_ENDPOINT")
[[ -n "${R2_ACCESS_KEY_ID:-}" ]]     || MISSING+=("R2_ACCESS_KEY_ID")
[[ -n "${R2_SECRET_ACCESS_KEY:-}" ]] || MISSING+=("R2_SECRET_ACCESS_KEY")
if [[ ${#MISSING[@]} -gt 0 ]]; then
    echo "ERROR: missing required environment variable(s): ${MISSING[*]}" >&2
    echo "       export them before running (do NOT pass secrets as CLI args):" >&2
    echo "         export R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com" >&2
    echo "         export R2_ACCESS_KEY_ID=...  R2_SECRET_ACCESS_KEY=..." >&2
    exit 1
fi

R2_BUCKET="${R2_BUCKET:-maps}"

if [[ "$R2_ENDPOINT" == *"<ACCOUNT_ID>"* || "$R2_ENDPOINT" == *"<"* ]]; then
    echo "ERROR: R2_ENDPOINT still contains a placeholder: $R2_ENDPOINT" >&2
    echo "       Set the real endpoint for your account." >&2
    exit 1
fi

# --- Verify local files exist ---
for f in "${FILES[@]}"; do
    if [[ ! -f "$f" ]]; then
        echo "ERROR: file not found: $f" >&2
        echo "       Build it first, e.g. ./build_v5_pmtiles.sh --pbf ... --out v5.pmtiles" >&2
        exit 1
    fi
done

# --- Select upload tool ---
resolve_tool() {
    case "$TOOL" in
        rclone) command -v rclone >/dev/null || { echo "ERROR: rclone requested but not installed" >&2; exit 1; }; echo rclone ;;
        aws)    command -v aws    >/dev/null || { echo "ERROR: aws requested but not installed" >&2; exit 1; }; echo aws ;;
        auto)
            if command -v rclone >/dev/null; then echo rclone
            elif command -v aws >/dev/null; then echo aws
            else
                echo "ERROR: neither rclone nor aws CLI found. Install one:" >&2
                echo "  rclone:  https://rclone.org/install/" >&2
                echo "  aws-cli: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html" >&2
                exit 1
            fi
            ;;
        *) echo "ERROR: --tool must be auto|rclone|aws" >&2; exit 1 ;;
    esac
}
UPLOADER="$(resolve_tool)"

upload_rclone() {  # src  key
    local src="$1" key="$2"
    rclone copyto "$src" ":s3:$R2_BUCKET/$key" \
        --s3-provider Cloudflare \
        --s3-endpoint "$R2_ENDPOINT" \
        --s3-access-key-id "$R2_ACCESS_KEY_ID" \
        --s3-secret-access-key "$R2_SECRET_ACCESS_KEY" \
        --s3-region auto \
        --s3-upload-cutoff 100M \
        --s3-chunk-size 100M \
        --s3-no-check-bucket \
        --header-upload "Content-Type: $CONTENT_TYPE" \
        --header-upload "Cache-Control: $CACHE_CONTROL" \
        --progress
}

upload_aws() {  # src  key
    local src="$1" key="$2"
    # Scope creds to this invocation; aws reads them from the environment.
    AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
    AWS_REGION="auto" \
    aws s3 cp "$src" "s3://$R2_BUCKET/$key" \
        --endpoint-url "$R2_ENDPOINT" \
        --content-type "$CONTENT_TYPE" \
        --cache-control "$CACHE_CONTROL" \
        --only-show-errors
}

echo "=== publish_r2.sh ==="
echo "R2_ENDPOINT = $R2_ENDPOINT"
echo "R2_BUCKET   = $R2_BUCKET"
echo "tool        = $UPLOADER"
echo "files       = ${FILES[*]}"

first=1
for src in "${FILES[@]}"; do
    if [[ ${#FILES[@]} -eq 1 && -n "$KEY" ]]; then
        key="$KEY"
    elif [[ $first -eq 1 && -n "$KEY" ]]; then
        key="$KEY"
    else
        key="$(basename "$src")"
    fi
    first=0

    echo "--- uploading $src -> s3://$R2_BUCKET/$key (Content-Type: $CONTENT_TYPE) ---"
    if [[ "$DRY_RUN" == "1" ]]; then
        echo "[dry-run] would upload via $UPLOADER"
        continue
    fi
    if [[ "$UPLOADER" == "rclone" ]]; then
        upload_rclone "$src" "$key"
    else
        upload_aws "$src" "$key"
    fi
    echo "uploaded: https://data.vayunmathur.com/$key"
done

echo ""
echo "Done. The app loads: pmtiles://https://data.vayunmathur.com/v5.pmtiles"
echo "(P13 points style.json + MaxspeedSource.PMTILES_URL at the v5 file.)"
