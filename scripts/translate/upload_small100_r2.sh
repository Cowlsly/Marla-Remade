#!/usr/bin/env bash
# Uploads the SMaLL-100 bundle to R2 bucket `maps`, served at
# https://data.vayunmathur.com/models/small100/ (or subpath).
#
# Two files, ~320 MB: small100.maml (318.3 MiB, int8, graph id 15) and tokenizer.bin (1.7 MB).
# Build both with `python scripts/ml/fetch_small100.py`, which writes them to build/small100/ and
# prints the SHA-256 that Small100Model.kt pins.
#
# This replaced a seven-file ncnn bundle of 1.14 GB. Nothing here converts anything any more: the
# fp16-vs-int4 quantisation notes that used to live in this header described `ncnnllm`, whose int4
# multi-head attention crashed with a tagged-memory fault on the target device, and the conversion
# now happens in scripts/ml/maml_convert.py from the fp32 checkpoint.
#
# Credentials are read from the environment (never hardcoded). Run from a trusted shell:
#
#   export AWS_ACCESS_KEY_ID=...        # R2 access key id
#   export AWS_SECRET_ACCESS_KEY=...    # R2 secret
#   export R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com
#   ./scripts/translate/upload_small100_r2.sh build/small100 [--prefix small100/]
set -euo pipefail
BUNDLE=""
PREFIX="small100/"
FILES=(small100.maml tokenizer.bin)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="$2"; shift 2;;
    --prefix=*) PREFIX="${1#--prefix=}"; shift;;
    -h|--help)
      echo "usage: upload_small100_r2.sh <bundle_dir> [--prefix small100/]"
      echo "  default prefix small100/; use another for a side-by-side rollout"
      exit 0;;
    *) BUNDLE="$1"; shift;;
  esac
done
if [[ -z "$BUNDLE" ]]; then
  echo "usage: upload_small100_r2.sh <bundle_dir> [--prefix small100/]" >&2
  exit 1
fi
: "${AWS_ACCESS_KEY_ID:?set AWS_ACCESS_KEY_ID}"
: "${AWS_SECRET_ACCESS_KEY:?set AWS_SECRET_ACCESS_KEY}"
: "${R2_ENDPOINT:?set R2_ENDPOINT}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}"
# Validate. Exactly these two: an extra file left over from an older bundle would be synced up and
# then downloaded by every client for nothing.
for f in "${FILES[@]}"; do
  if [[ ! -f "$BUNDLE/$f" ]]; then
    echo "missing $BUNDLE/$f - run: python scripts/ml/fetch_small100.py" >&2
    exit 1
  fi
done
echo "=== Local bundle $BUNDLE (upload to s3://maps/models/$PREFIX) ==="
ls -lh "$BUNDLE"
# Normalize prefix to end with /
[[ "$PREFIX" != */ ]] && PREFIX="$PREFIX/"
aws s3 sync "$BUNDLE" "s3://maps/models/$PREFIX" \
  --endpoint-url "$R2_ENDPOINT" --checksum-algorithm CRC32
echo "--- uploaded objects s3://maps/models/$PREFIX ---"
aws s3 ls "s3://maps/models/$PREFIX" --endpoint-url "$R2_ENDPOINT"
echo ""
echo "Small100Model.kt FILES must pin these:"
for f in "${FILES[@]}"; do
  printf '  %s: %s\n' "$f" "$(shasum -a 256 "$BUNDLE/$f" | cut -d' ' -f1)"
done
echo ""
echo "The seven ncnn objects this replaced are removed from an installed app by"
echo "Small100Model.deleteRetired; delete them from the bucket by hand once clients have rolled."
