#!/usr/bin/env bash
#
# Refresh the bundled `.vkml` weights that `:library:ml` runs, from the Apache-2.0
# ONNX exports they are converted from.
#
#   camera/src/main/assets/selfie_segmentation.vkml   MediaPipe Selfie Segmentation
#   photos/src/main/assets/u2netp.vkml                U^2-Net portable (saliency)
#
# Neither ONNX is vendored: it is 2x the size of the fp16 `.vkml` we actually ship
# and nothing reads it at run time. So this is the only record of where the weights
# came from, alongside the README.md next to each asset and the source ONNX SHA-256
# baked into each `.vkml` header.
#
# The conversion is deliberately not a graph import. `:library:ml` hardcodes each
# forward pass in Rust, so `.vkml` carries ordered tensors and nothing else, and
# vkml_convert.py pins a digest over the whole ordered layer table — an upstream
# re-export that reorders or re-pads one layer fails here rather than shipping a
# net that infers nonsense. See scripts/ml/vkml_convert.py.
#
#   ./scripts/ml/fetch_and_convert.sh            # verify pinned hashes, rebuild assets
#   ./scripts/ml/fetch_and_convert.sh --update   # re-download and rewrite hashes
#   ./scripts/ml/fetch_and_convert.sh --layers   # also dump the per-layer tables
#
set -euo pipefail

# repo-relative-dest  graph  sha256(onnx)  url
#
# Pinned to a commit rather than to `main`: the file SHA-256 below would catch a
# silent upstream change anyway, but a pinned revision means --update tells you the
# upstream *revision* that moved, not just that some bytes differ.
MODELS=(
  "camera/src/main/assets/selfie_segmentation.vkml selfie 3241ac4ad8aa35bdaf33946776db29f7c283a413aa0b0dacb9483594b4531aad https://huggingface.co/onnx-community/mediapipe_selfie_segmentation/resolve/be49485c8e027524be38591817fc5cd31bd9d00e/onnx/model.onnx"
  "photos/src/main/assets/u2netp.vkml             u2netp 309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8 https://huggingface.co/BritishWerewolf/U-2-Netp/resolve/7112208dbac3a3642496c8d54e2f0f9bb3dc1dc8/onnx/model.onnx"
)

if [ ! -f settings.gradle.kts ]; then
  echo "Run this from the repo root (where settings.gradle.kts is)." >&2
  exit 1
fi

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

update=0
layers=0
for arg in "$@"; do
  case "$arg" in
    --update) update=1 ;;
    --layers) layers=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

# The ONNX is a build-time input only, so it goes to a scratch dir rather than into
# the tree, and is re-fetched rather than committed.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

python="${PYTHON:-python3}"
if ! "$python" -c 'import onnx, numpy' >/dev/null 2>&1; then
  echo "Needs the 'onnx' and 'numpy' packages: $python -m pip install onnx numpy" >&2
  exit 1
fi

fail=0
for entry in "${MODELS[@]}"; do
  # shellcheck disable=SC2086
  set -- $entry
  dest="$1"
  graph="$2"
  want="$3"
  url="$4"
  onnx="${work}/${graph}.onnx"

  echo "Downloading ${graph} from ${url}..."
  curl -fL --retry 3 "$url" -o "$onnx"

  got="$(sha256 "$onnx")"
  if [ "$got" = "$want" ]; then
    printf '  ok       %-8s %s\n' "$graph" "$got"
  elif [ "$update" = 1 ]; then
    printf '  CHANGED  %-8s\n             was %s\n             now %s\n' "$graph" "$want" "$got"
    echo "             ^ update the pin for ${graph} in this script, then re-pin the"
    echo "               layer digest with vkml_convert.py --print-digest"
    fail=1
  else
    printf '  MISMATCH %-8s\n             want %s\n             got  %s\n' "$graph" "$want" "$got"
    echo "             Upstream moved. Re-run with --update to accept it." >&2
    fail=1
  fi

  extra=""
  [ "$layers" = 1 ] && extra="--print-layers"
  mkdir -p "$(dirname "$dest")"
  # shellcheck disable=SC2086
  "$python" scripts/ml/vkml_convert.py "$onnx" --graph "$graph" -o "$dest" $extra
done

echo
if [ "$fail" != 0 ]; then
  echo "FAILED — see above." >&2
  exit 1
fi
for entry in "${MODELS[@]}"; do
  # shellcheck disable=SC2086
  set -- $entry
  ls -l "$1" | awk '{ printf "  %10d  %s\n", $5, $NF }'
done
echo "All ${#MODELS[@]} models converted."
