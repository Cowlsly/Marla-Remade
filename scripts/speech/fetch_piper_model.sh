#!/usr/bin/env bash
#
# Stage an offline Piper (VITS) voice, in .maml format, as a single zip for the model
# mirror. The :speech app downloads it at runtime — it is NOT bundled in the APK — from
#   https://data.vayunmathur.com/models/piper/<archive>.zip
# e.g. voice3.zip (en) or de-low.zip, fr-low.zip … and extracts it (see PiperVoiceRegistry).
#
# The zip must contain the voice's CONTENTS at its root:
#   <voice>_enc_p.maml               text encoder            (graph 8)
#   <voice>_dp.maml                  duration predictor      (graph 10, read on the CPU)
#   <voice>_flow.maml                normalising flow        (graph 9)
#   <voice>_dec.maml                 HiFi-GAN vocoder        (graph 7)
#   <lang>-word_id.bin               grapheme-to-phoneme dictionary (en-word_id.bin, de-word_id.bin)
#   config.json                      sample rate + inference scales
#
# TTS runs on :library:ml, our own Vulkan compute runtime, so there is no .onnx on device, no
# tokens.txt and no espeak-ng-data/. espeak-ng is still needed HERE, to build the dictionary.
#
# Multi-speaker voices are NOT supported: they need an `emb_g` embedding network that is not
# converted, and every voice in the registry is single-speaker.
#
# ---------------------------------------------------------------------------
# Converting a voice to the four .maml files (once per voice)
# ---------------------------------------------------------------------------
# Unlike the ncnn path this needs no checkpoint, no patch and no pnnx. Piper publishes ONNX per
# voice and all four modules come out of that one file, because the export keeps its Torch
# module paths and maml_convert.py slices by them (see MODULES in that script):
#
#   V=en_GB-alan-low
#   B=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/low
#   curl -fLO $B/$V.onnx && curl -fLO $B/$V.onnx.json
#   python3 scripts/ml/maml_convert.py $V.onnx --graph vits_enc  -o ${V}_enc_p.maml
#   python3 scripts/ml/maml_convert.py $V.onnx --graph vits_dp   -o ${V}_dp.maml
#   python3 scripts/ml/maml_convert.py $V.onnx --graph vits_flow -o ${V}_flow.maml
#   python3 scripts/ml/maml_convert.py $V.onnx --graph vits_dec  -o ${V}_dec.maml
#   cp $V.onnx.json config.json
#
# The four together are about 31 MB against the 63 MB fp32 ONNX.
#
# Each graph has a pinned layer-table digest. A voice whose export differs structurally fails
# the digest rather than converting into something the Rust misreads - if that happens the Rust
# forward pass needs updating, not the pin.
#
# Then point this script at the directory holding the .maml files and config.json.
#
# ---------------------------------------------------------------------------
#   ./scripts/speech/fetch_piper_model.sh <converted-dir> [word-list] [lang] [espeak-voice] [quality] [archive-name]
#
#   <converted-dir>  dir with the four *.maml files and config.json (required)
#   [word-list]      file with one word per line (optional, auto-fetched for en)
#   [lang]           ISO-639-1 code, e.g. en, de, fr (default: en)
#   [espeak-voice]   espeak-ng voice for dict, e.g. en-us, de, fr (default: lang)
#   [quality]        low or medium (default: medium for en, low for others)
#   [archive-name]   output zip name, e.g. de-low.zip (default: voice3.zip for en, <lang>-low.zip otherwise)
#
#   The script respects WORDLIST env for generation: if no word-list and lang!=en,
#   it tries scripts/speech/generate_wordlist.py using `wordfreq` top 100k.
# ---------------------------------------------------------------------------
set -euo pipefail

SRC="${1:-}"
WORDS="${2:-}"
LANG_CODE="${3:-en}"
ESPEAK_VOICE="${4:-}"
QUALITY="${5:-}"
ARCHIVE_NAME="${6:-}"

if [ ! -f settings.gradle.kts ]; then
  echo "Run this from the repo root (where settings.gradle.kts is)." >&2
  exit 1
fi
if [ -z "$SRC" ] || [ ! -d "$SRC" ]; then
  echo "Usage: $0 <dir with *.maml and config.json> [word-list] [lang] [espeak-voice] [quality] [archive]" >&2
  echo "  e.g. $0 /tmp/de_DE-thorsten-medium /tmp/de-words.txt de de low de-low.zip" >&2
  exit 1
fi

# Defaults based on language — now high quality 22 kHz for all langs
if [ -z "$ESPEAK_VOICE" ]; then
  case "$LANG_CODE" in
    en) ESPEAK_VOICE="en-us" ;;
    *)  ESPEAK_VOICE="$LANG_CODE" ;;
  esac
fi
if [ -z "$QUALITY" ]; then
  QUALITY="high"
fi
if [ -z "$ARCHIVE_NAME" ]; then
  ARCHIVE_NAME="${LANG_CODE}-${QUALITY}.zip"
fi

DICT_FILE="${LANG_CODE}-word_id.bin"

command -v zip >/dev/null || { echo "'zip' is required." >&2; exit 1; }
command -v espeak-ng >/dev/null || {
  echo "'espeak-ng' is required to build the phoneme dictionary (build-time only;" >&2
  echo "it is NOT shipped on-device). brew install espeak-ng / apt install espeak-ng" >&2
  exit 1
}

DEST="dist/speech-mirror/piper"
ABS_DEST="$(pwd)/$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Identify the voice from the encoder, the same way Vits and PiperModel do.
ENC="$(find "$SRC" -maxdepth 1 -name '*_enc_p.maml' -print -quit)"
if [ -z "$ENC" ]; then
  echo "No *_enc_p.maml in $SRC - did maml_convert.py run?" >&2
  exit 1
fi
VOICE="$(basename "$ENC" _enc_p.maml)"
echo "Voice: $VOICE  lang=$LANG_CODE  espeak=$ESPEAK_VOICE  quality=$QUALITY  dict=$DICT_FILE  archive=$ARCHIVE_NAME"

if [ ! -f "$SRC/config.json" ]; then
  echo "config.json missing from $SRC (needed for the sample rate and phoneme_id_map)." >&2
  exit 1
fi

# Four files, one per module, and all four are required. There is no _emb_g: a multi-speaker
# voice needs a speaker-embedding network that is not converted, and PiperEngine refuses one.
for net in _enc_p _dp _flow _dec; do
  f="$SRC/${VOICE}${net}.maml"
  [ -f "$f" ] || { echo "Missing $f" >&2; exit 1; }
  cp "$f" "$TMP/"
done
cp "$SRC/config.json" "$TMP/"

# Phoneme dictionary. Without a word list:
#  - en: fetch CMUdict and strip it to bare words (Python robust parsing — old shell pipeline
#        broke on macOS with "illegal byte sequence" → 33k words missing HELLO).
#  - other: try generate_wordlist.py via wordfreq top 100k.
if [ -z "$WORDS" ]; then
  if [ "$LANG_CODE" = "en" ]; then
    echo "Fetching CMUdict for the word list..."
    curl -fL https://raw.githubusercontent.com/Alexir/CMUdict/master/cmudict-0.7b \
      -o "$TMP/cmudict.txt"
    python3 <<PY
import re
from pathlib import Path
pattern = re.compile(r"^[A-Za-z'-]+$")
seen=set()
words=[]
src=Path("$TMP/cmudict.txt")
for line in src.read_text(encoding='utf-8', errors='ignore').splitlines():
    if line.startswith(';;;'): continue
    line=line.strip()
    if not line: continue
    w=line.split()[0] if line.split() else ''
    w=re.sub(r'\(\d+\)$','',w)
    if not w: continue
    if any(c.isspace() for c in w): continue
    if not pattern.match(w): continue
    low=w.lower()
    if low in seen: continue
    seen.add(low)
    words.append(w)
Path("$TMP/words.txt").write_text("\n".join(words), encoding='utf-8')
print(f"CMUdict: {len(words)} words")
PY
    WORDS="$TMP/words.txt"
  else
    # Try wordfreq-based generation
    GEN_SCRIPT="$(pwd)/scripts/speech/generate_wordlist.py"
    if [ -f "$GEN_SCRIPT" ]; then
      echo "No word list supplied for $LANG_CODE — trying generate_wordlist.py (wordfreq top 100k)..."
      if python3 "$GEN_SCRIPT" --lang "$LANG_CODE" --top 100000 --out "$TMP/${LANG_CODE}-words.txt"; then
        WORDS="$TMP/${LANG_CODE}-words.txt"
      else
        echo "generate_wordlist.py failed (need 'pip install wordfreq'). Supply a word list file." >&2
        exit 1
      fi
    else
      echo "No word list for lang $LANG_CODE and no generate_wordlist.py. Provide a word file." >&2
      exit 1
    fi
  fi
fi
echo "Word list: $WORDS ($(wc -l < "$WORDS" | tr -d ' ') lines)"

SCRIPT_DIR="$(pwd)/scripts/speech"
"$SCRIPT_DIR/generate_piper_dict.py" \
  --config "$TMP/config.json" \
  --words "$WORDS" \
  --voice "$ESPEAK_VOICE" \
  --out "$TMP/$DICT_FILE"

# For non-EN voices also ensure en-word_id.bin exists for backward compat with
# ncnn-android <1.7.1 which required it. With 1.7.1 any *-word_id.bin works, so
# this extra file is harmless and makes the zip usable on both.
if [ "$LANG_CODE" != "en" ]; then
  # If we have an EN dict cached nearby (dist) or can reuse CMUdict? For simplicity,
  # if /tmp already has en words, reuse, else note.
  # The app-side PiperVoiceRegistry.installIfNeeded also copies en dict if missing,
  # so shipping only lang dict is okay. But include en as fallback for 1.7.0 testing.
  if [ -f "$TMP/en-word_id.bin" ]; then
    echo "en-word_id.bin already present (keeping)"
  else
    # Try to reuse if en words exist in cache? Skip — let app copy.
    echo "Note: only $DICT_FILE in zip, no en-word_id.bin. App will copy en dict on-device for 1.7.0 compat if en installed."
  fi
fi

mkdir -p "${ABS_DEST}"

# Zip the CONTENTS (not the wrapping folder) so entries sit at the zip root.
OUT_ZIP="${ABS_DEST}/${ARCHIVE_NAME}"
rm -f "$OUT_ZIP"
( cd "$TMP" && rm -f cmudict.txt words.txt "${LANG_CODE}-words.txt" && zip -r -q -X "$OUT_ZIP" . )

echo
echo "Staged ${DEST}/${ARCHIVE_NAME}"
du -sh "$OUT_ZIP"
shasum -a 256 "$OUT_ZIP"

# For English keep legacy aliases voice.zip + voice2.zip + voice3.zip identical
if [ "$LANG_CODE" = "en" ]; then
  cp "$OUT_ZIP" "${ABS_DEST}/voice.zip"
  cp "$OUT_ZIP" "${ABS_DEST}/voice2.zip"
  # If ARCHIVE_NAME is not voice3.zip, also copy to voice3.zip for compat
  if [ "$ARCHIVE_NAME" != "voice3.zip" ]; then
    cp "$OUT_ZIP" "${ABS_DEST}/voice3.zip"
  fi
  echo "Also staged ${DEST}/voice.zip + voice2.zip + ${DEST}/voice3.zip (same content)"
  du -sh "${ABS_DEST}/voice.zip" "${ABS_DEST}/voice3.zip"
fi

echo
echo "Upload ${ARCHIVE_NAME} to: https://data.vayunmathur.com/models/piper/${ARCHIVE_NAME}"
echo "SHA-256 must match PiperVoiceRegistry pin for lang=$LANG_CODE."
if [ "$LANG_CODE" = "en" ]; then
  echo "And voice.zip / voice3.zip for compat."
fi
