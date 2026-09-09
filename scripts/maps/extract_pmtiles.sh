#!/bin/bash

# Configuration
# After self-host migration the source is R2 itself at data.vayunmathur.com.
# Original upstream: https://demo-bucket.protomaps.com/v4.pmtiles (137 GB)
SOURCE_URL="${SOURCE_URL:-https://data.vayunmathur.com/v4.pmtiles}"
OUTPUT_DIR="${OUTPUT_DIR:-./zones}"
mkdir -p "$OUTPUT_DIR"

# R2 Configuration — bucket is `maps` (custom domain data.vayunmathur.com)
# Env vars: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY / R2_ACCESS_KEY_ID,
# R2_SECRET_ACCESS_KEY + R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
R2_ENDPOINT="${R2_ENDPOINT:-https://<ACCOUNT_ID>.r2.cloudflarestorage.com}"
R2_BUCKET="${R2_BUCKET:-maps}"

# --- Automatic go-pmtiles Installation ---
# Installed project-local (under the gitignored tmp/) rather than /usr/local/bin, so the
# script never needs sudo and never writes to a system path.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS_DIR="${PMTILES_TOOLS_DIR:-$REPO_ROOT/tmp/tools}"
export PATH="$TOOLS_DIR:$PATH"

if ! command -v pmtiles &> /dev/null; then
    echo "pmtiles CLI not found. Installing to $TOOLS_DIR ..."

    # Detect OS and Architecture
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    ARCH=$(uname -m)
    case $ARCH in
        x86_64) ARCH="x86_64" ;;
        aarch64|arm64) ARCH="arm64" ;;
        *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
    esac

    # Pinned rather than resolved from the releases API, so the version can't move under
    # us. v1.31.2 -> commit a3e4951ea6a0477b784c27c1dcbfd9c130878c5a; confirmed via the
    # releases API to be the current latest release, not merely the newest tag.
    PMTILES_VERSION="${PMTILES_VERSION:-1.31.2}"

    # SHA-256 of the release archive, per platform.
    #
    # READ BEFORE TRUSTING THIS: upstream publishes NO checksums.txt for v1.31.2 — the
    # release contains only the archives. These digests were therefore computed from the
    # downloaded artifacts, not read from a protomaps-published manifest. So they prove
    # IMMUTABILITY (the bytes have not changed since they were measured, which catches a
    # later silent re-upload or a MITM) and NOT AUTHENTICITY (if the artifact was already
    # bad when measured, this pins the bad one). That is still worth having, but it is a
    # weaker guarantee than a pinned upstream digest and should not be described as one.
    #
    # Measured 2026-09-08 by download-and-hash of the release archives; the Linux x86_64
    # archive was fetched three times, byte-identical. Sizes recorded so a future reader
    # can sanity-check they're hashing the archive, not the extracted binary.
    # Override with PMTILES_SHA256=... to supply your own.
    if [ -z "${PMTILES_SHA256:-}" ]; then
        case "${OS}_${ARCH}" in
            linux_x86_64)   # go-pmtiles_1.31.2_Linux_x86_64.tar.gz, 17444324 B
                PMTILES_SHA256="3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f" ;;
            linux_arm64)    # go-pmtiles_1.31.2_Linux_arm64.tar.gz, 15779781 B
                PMTILES_SHA256="f8bd47e7ea866863489cad588fbaf2f31f42e5821f7a03f009b3769f05801cb1" ;;
            darwin_x86_64)  # go-pmtiles-1.31.2_Darwin_x86_64.zip, 18093572 B
                PMTILES_SHA256="1f0dc02eee6c58312dd6c509faee1b5c32f0596568af1bf51f1b034e7a88a65b" ;;
            darwin_arm64)   # go-pmtiles-1.31.2_Darwin_arm64.zip, 15963714 B
                PMTILES_SHA256="40528f7f616fcbf91207cd48c8fc023d213f6d86c0cbf1f748732803d1880f3d" ;;
            *)
                PMTILES_SHA256="" ;;
        esac
    fi

    # Upstream's asset naming is NOT uniform across platforms: Linux is
    # go-pmtiles_<ver>_Linux_<arch>.tar.gz (underscore, tarball) but macOS is
    # go-pmtiles-<ver>_Darwin_<arch>.zip — hyphen before the version, and a zip. Building
    # one pattern for both is why the macOS path 404'd. Casing is not a factor; GitHub
    # asset URLs are case-insensitive.
    case "$OS" in
        linux)  ASSET="go-pmtiles_${PMTILES_VERSION}_Linux_${ARCH}.tar.gz" ;;
        darwin) ASSET="go-pmtiles-${PMTILES_VERSION}_Darwin_${ARCH}.zip" ;;
        *)      echo "No automatic pmtiles install for OS '$OS'. Install it manually into $TOOLS_DIR."; exit 1 ;;
    esac

    DOWNLOAD_URL="https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/${ASSET}"

    mkdir -p "$TOOLS_DIR"
    ARCHIVE="$TOOLS_DIR/$ASSET"

    echo "Downloading pmtiles v${PMTILES_VERSION} for ${OS}_${ARCH}..."
    curl -fL "$DOWNLOAD_URL" -o "$ARCHIVE" || { echo "Error: download failed."; exit 1; }

    if [ -n "$PMTILES_SHA256" ]; then
        GOT=$(sha256sum "$ARCHIVE" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$ARCHIVE" | cut -d' ' -f1)
        if [ "$GOT" != "$PMTILES_SHA256" ]; then
            echo "Error: go-pmtiles checksum mismatch."
            echo "  want $PMTILES_SHA256"
            echo "  got  $GOT"
            rm -f "$ARCHIVE"
            exit 1
        fi
        echo "Checksum ok."
    else
        echo "Warning: no pinned SHA-256 for go-pmtiles ${OS}_${ARCH}; download is UNVERIFIED."
    fi

    case "$ASSET" in
        *.tar.gz)
            tar -xzf "$ARCHIVE" -C "$TOOLS_DIR" pmtiles ;;
        *.zip)
            command -v unzip >/dev/null 2>&1 || {
                echo "Error: 'unzip' is required to install pmtiles on $OS."; rm -f "$ARCHIVE"; exit 1; }
            unzip -o -q "$ARCHIVE" pmtiles -d "$TOOLS_DIR" ;;
    esac
    chmod +x "$TOOLS_DIR/pmtiles"
    rm -f "$ARCHIVE"
    echo "Installed to $TOOLS_DIR/pmtiles"
fi

# Check for other dependencies
if ! command -v aws &> /dev/null; then
    echo "Error: 'aws' CLI not found. Required for R2 syncing."
    exit 1
fi

if ! command -v bc &> /dev/null; then
    echo "Error: 'bc' (calculator) not found. Please install it (sudo apt install bc)."
    exit 1
fi

echo "Starting sequential extraction and upload of 64 zones..."

for i in {0..63}; do
    # 1. De-interleave Morton bits (Z-order curve)
    X=0
    [[ $((i & 1)) -ne 0 ]]  && X=$((X | 1))
    [[ $((i & 4)) -ne 0 ]]  && X=$((X | 2))
    [[ $((i & 16)) -ne 0 ]] && X=$((X | 4))

    Y=0
    [[ $((i & 2)) -ne 0 ]]  && Y=$((Y | 1))
    [[ $((i & 8)) -ne 0 ]]  && Y=$((Y | 2))
    [[ $((i & 32)) -ne 0 ]] && Y=$((Y | 4))

    # 2. Calculate Bounding Box
    LEFT=$(echo "-180 + ($X * 45)" | bc -l)
    RIGHT=$(echo "$LEFT + 45" | bc -l)
    BOTTOM=$(echo "-90 + ($Y * 22.5)" | bc -l)
    TOP=$(echo "$BOTTOM + 22.5" | bc -l)

    FILE_NAME="zone_$i.pmtiles"
    LOCAL_PATH="$OUTPUT_DIR/$FILE_NAME"

    echo "--------------------------------------------------------"
    echo "Processing Zone $i (Grid X:$X, Y:$Y)..."

    # 3. Perform Remote Extraction
    echo "Extracting $FILE_NAME..."
    pmtiles extract "$SOURCE_URL" "$LOCAL_PATH" \
        --bbox="$LEFT,$BOTTOM,$RIGHT,$TOP"

    if [ $? -eq 0 ]; then
        # 4. Upload to R2 immediately
        echo "Uploading $FILE_NAME to R2..."
        aws s3 cp "$LOCAL_PATH" "s3://$R2_BUCKET/$FILE_NAME" \
            --endpoint-url "$R2_ENDPOINT"

        if [ $? -eq 0 ]; then
            echo "Successfully uploaded $FILE_NAME. Deleting local copy..."
            # 5. Delete local file to save space
            rm "$LOCAL_PATH"
        else
            echo "ERROR: Failed to upload $FILE_NAME to R2. Local file kept for retry."
        fi
    else
        echo "WARNING: Failed to extract Zone $i"
    fi
done

echo "--------------------------------------------------------"
echo "Process finished."