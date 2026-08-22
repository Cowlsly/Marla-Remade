#!/usr/bin/env bash
#
# Refresh the bundled Whisper-tiny int8 ONNX assets in speech/src/main/assets/whisper-tiny/.
#
# Unlike the Piper voices, these are NOT downloaded at runtime — they ship inside the APK and
# are read straight out of the asset manager by WhisperOnnxEngine, so this script is a
# build-host tool for updating a vendored dependency, not a mirror-staging step.
#
# Upstream is HuggingFace onnx-community/whisper-tiny, which is an ONNX Runtime *dynamic*
# int8 quantization of openai/whisper-tiny. We consume it as-is: onnx2ncnn cannot read
# DynamicQuantizeLinear/MatMulInteger/ConvInteger, so there is no ncnn conversion path.
#
# The four files below are the whole runtime surface:
#   encoder_model_int8.onnx         audio -> 1500x384 hidden states
#   decoder_model_merged_int8.onnx  KV-cached greedy decode (use_cache_branch switches)
#   vocab.json                      id -> token, for byte-level decode (no merges: decode only)
#   generation_config.json          lang_to_id, suppress_tokens, special ids
#
# merges.txt and preprocessor_config.json are deliberately NOT vendored: we never BPE-encode
# (prompts are pure special tokens) and the feature-extractor constants are compile-time
# values in WhisperFeatures.kt, asserted against upstream by --check below.
#
#   ./scripts/speech/fetch_whisper_onnx.sh           # verify pinned hashes only
#   ./scripts/speech/fetch_whisper_onnx.sh --update  # re-download and rewrite hashes
#
set -euo pipefail

REPO="onnx-community/whisper-tiny"
REVISION="main"
BASE="https://huggingface.co/${REPO}/resolve/${REVISION}"
DEST="speech/src/main/assets/whisper-tiny"

# path-at-upstream  sha256
FILES=(
  "onnx/encoder_model_int8.onnx        03ff3c99ce804f79a42afd6212c9492eb75e55625926de66f8fc192e9567d336"
  "onnx/decoder_model_merged_int8.onnx 25e807a962b6349356d0ea5d0dfe530b7e5bf0e2a484aeca0359d03143faddd3"
  "vocab.json                          50d6a919f0a0601d56a04eb583c780d18553aa388254ba3158eb6a00f13e2c1a"
  "generation_config.json              f5c67e5a4f7102f8cb4d058bc95da276bbc19eeec997267c3bb0f25ef68facd1"
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
[ "${1:-}" = "--update" ] && update=1

mkdir -p "$DEST"
fail=0

for entry in "${FILES[@]}"; do
  # shellcheck disable=SC2086
  set -- $entry
  remote="$1"
  want="$2"
  local_name="$(basename "$remote")"
  out="${DEST}/${local_name}"

  if [ "$update" = 1 ] || [ ! -f "$out" ]; then
    echo "Downloading ${remote}..."
    curl -fL --retry 3 "${BASE}/${remote}" -o "${out}.tmp"
    mv "${out}.tmp" "$out"
  fi

  got="$(sha256 "$out")"
  if [ "$got" = "$want" ]; then
    printf '  ok      %-34s %s\n' "$local_name" "$got"
  elif [ "$update" = 1 ]; then
    printf '  CHANGED %-34s\n            was %s\n            now %s\n' "$local_name" "$want" "$got"
    echo "            ^ update the pin for ${local_name} in this script"
    fail=1
  else
    printf '  MISMATCH %-33s\n            want %s\n            got  %s\n' "$local_name" "$want" "$got"
    fail=1
  fi
done

# The feature extractor is reimplemented in Kotlin; if upstream ever retunes these we want a
# loud failure rather than a silently wrong mel (which yields fluent nonsense, not an error).
echo
echo "Checking preprocessor_config.json against WhisperFeatures.kt constants..."
pre="$(curl -fsSL "${BASE}/preprocessor_config.json")"
for kv in '"feature_size": 80' '"hop_length": 160' '"n_fft": 400' '"sampling_rate": 16000' '"n_samples": 480000' '"nb_max_frames": 3000'; do
  if printf '%s' "$pre" | tr -d ' \n' | grep -q "$(printf '%s' "$kv" | tr -d ' ')"; then
    echo "  ok      $kv"
  else
    echo "  MISMATCH expected $kv upstream — WhisperFeatures.kt must be updated" >&2
    fail=1
  fi
done

echo
if [ "$fail" != 0 ]; then
  echo "FAILED — see above." >&2
  exit 1
fi
du -sh "$DEST"
echo "All ${#FILES[@]} assets verified."
