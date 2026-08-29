#!/usr/bin/env python3
"""Fetch whisper-base's checkpoint and build the `whisper_base.maml` `:speech` bundles.

# Why this exists

It replaces `scripts/speech/fetch_whisper_onnx.sh`, which vendored two int8 ONNX exports from
`onnx-community/whisper-base` and pinned their digests. Those exports are gone with ONNX Runtime;
this reads the fp32 checkpoint they were quantised from and quantises it here instead.

# The fp32 checkpoint, not the int8 export

`onnx-community/whisper-base` is an ONNX Runtime **dynamic** int8 quantisation, and it quantises per
*tensor*. That is the same coarseness that moved SMaLL-100 onto `alirezamsh/small100`'s checkpoint
for a factor-of-four resolution gain on its embedding — see `CHECKPOINTS` in
`scripts/ml/maml_convert.py`. `maml_convert.quantise_per_channel` gives every kernel here a
per-output-channel scale, and the worst measured per-tensor correlation against fp32 is 0.99979.

`openai/whisper-base` is **Apache-2.0**. It is a build-host dependency only: 290 MB downloaded here,
nothing at run time.

# The pins, and what each one catches

* **File SHA-256**, below. Catches an upstream re-export at the same revision, or a truncated
  download.
* **`config.json`**, checked against [`ARCHITECTURE`] rather than trusted. `nets::whisper` hardcodes
  6 encoder layers, 6 decoder layers, `d_model` 512, 8 heads and a 2048-wide feed-forward.
* **`preprocessor_config.json`**, checked against [`FEATURES`]. `WhisperFeatures.kt` hardcodes the
  mel front end, and a wrong `n_fft` or `hop_length` produces fluent, confident nonsense rather than
  an error. The old shell script asserted the same constants; this keeps that.
* **`generation_config.json`**, checked against [`DECODING`]. The special-token ids and the
  suppression lists are what `post::whisper` is written against, and `<|notimestamps|>` in particular
  changes the output to timestamped text if it moves or is dropped.
* **The parameter inventory** `maml_convert.check_checkpoint` asserts — every name and every shape.
  It is also what catches the two absences the port depends on: no `k_proj.bias` anywhere, and no
  `proj_out` because the head is tied.
* **The layer table digest** `maml_convert.EXPECTED_DIGEST` pins, over the emitted order.

`vocab.json` and `generation_config.json` are **checked, not rewritten**. Both already ship beside
the model, vendored from `onnx-community/whisper-base` by the script this replaces, and both are
byte-for-byte what the runtime reads: `WhisperTokenizer` decodes with the vocabulary directly and
`post::whisper` reads the ids and suppression lists out of the generation config. Copying upstream's
copies over them would rewrite 50,000 lines of identically-meaning JSON, so instead this proves the
two mirrors **agree** — same token for every id below 50,257, same value for every decoding field —
which is a stronger statement than a copy and leaves the shipped bytes alone.

Usage:

    python scripts/ml/fetch_whisper.py                 # verify digests, write the assets
    python scripts/ml/fetch_whisper.py --work DIR      # keep the 290 MB checkpoint somewhere
    python scripts/ml/fetch_whisper.py --print-digests # after an intentional upstream bump
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile

REPO = "openai/whisper-base"

# Pinned to a commit rather than to `main`, for the reason `fetch_and_convert.sh` gives: the digests
# below would catch a silent upstream change anyway, but a pinned revision tells you the upstream
# *revision* that moved rather than just that some bytes differ.
REVISION = "e37978b90ca9030d5170a5c07aadb050351a65bb"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# A bundled asset directory, unlike `fetch_small100.py`'s runtime downloads.
OUT = os.path.join(ROOT, "speech", "src", "main", "assets", "whisper-base")

CHECKPOINT = "model.safetensors"
CONFIG = "config.json"
PREPROCESSOR = "preprocessor_config.json"
GENERATION = "generation_config.json"
VOCAB = "vocab.json"

# SHA-256 of every file this reads, at the pinned revision. Not the whole repo: `pytorch_model.bin`,
# `flax_model.msgpack` and `tf_model.h5` say the same thing as the checkpoint, so pinning them would
# pin something never downloaded.
DIGESTS = {
    CHECKPOINT: "07cadb9f25677c8d50df603e66a98fbd842cce45047139baeb16e6219a1e807b",
    CONFIG: "a153c53883a6799b6f056b4a8d1a515c9926d03994682ba88a7616618d7da0c1",
    PREPROCESSOR: "9b5cd03a36fbb8a627c64d98a5b5b126ead95a77720723944487311f0110b666",
    GENERATION: "444b3f636d2fff89dd9ecf549e2a085b61f7ff0fa0246d4628bac6a3b8cc9ba4",
    VOCAB: "8f680bba319e01a653d2e8a5dbc17a9157179e0576e6ce74ce0c06356c6e24f9",
}

# Transcribed from the pinned `config.json`, and asserted against it below rather than trusted.
# `nets::whisper` is written against these; `maml_convert.CHECKPOINTS` restates the subset the tensor
# table depends on.
ARCHITECTURE = {
    "d_model": 512,
    "encoder_layers": 6,
    "decoder_layers": 6,
    "encoder_attention_heads": 8,
    "decoder_attention_heads": 8,
    "encoder_ffn_dim": 2048,
    "decoder_ffn_dim": 2048,
    "vocab_size": 51_865,
    "max_source_positions": 1500,
    "max_target_positions": 448,
    "num_mel_bins": 80,
    "activation_function": "gelu",
    # No `sqrt(d_model)` on the embedding, unlike M2M-100. Asserted because the converter relies on
    # it: a checkpoint with `scale_embedding: true` would need the scale folded into the table.
    "scale_embedding": False,
}

# From the pinned `preprocessor_config.json`, and the constants `WhisperFeatures.kt` hardcodes. The
# old `fetch_whisper_onnx.sh` asserted these; a wrong mel is the one error in this pipeline that
# produces confident nonsense rather than a failure, so the check survives the port.
FEATURES = {
    "feature_size": 80,
    "n_fft": 400,
    "hop_length": 160,
    "sampling_rate": 16000,
    "n_samples": 480_000,
    "nb_max_frames": 3000,
    "chunk_length": 30,
}

# From the pinned `generation_config.json`, and the ids `post::whisper` is written against.
#
# `no_timestamps_token_id` is the one to watch: it is part of the step-0 prompt, and dropping it
# changes the output from plain text to `<|0.00|>`-style timestamped text. That is the same shape of
# hazard as SMaLL-100's "the language token goes on the source side".
DECODING = {
    "decoder_start_token_id": 50258,
    "eos_token_id": 50257,
    "pad_token_id": 50257,
    "no_timestamps_token_id": 50363,
    "max_length": 448,
    "is_multilingual": True,
}

# `task_to_id`, `lang_to_id` and the two suppression lists, checked by size and by spot value rather
# than transcribed in full: 99 languages and 88 suppressed ids would be a list checked against
# itself, but a list that changed *length* is a different model.
DECODING_SIZES = {
    "lang_to_id": 99,
    "suppress_tokens": 88,
    "begin_suppress_tokens": 2,
}

# Ids at or above this are special or timestamp tokens with no text form, and are not in
# `vocab.json`. The same constant as `WhisperTokenizer.FIRST_SPECIAL_ID`.
FIRST_SPECIAL_ID = 50_257


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def convert(script, *args):
    """Run one of the sibling converters, letting its own output and exit code through.

    `check=True` matters: `maml_convert.py` exits non-zero when a layer table digest does not match,
    and that is the check that must stop an asset being written from the wrong checkpoint.
    """
    subprocess.run([sys.executable, os.path.join(HERE, script), *args], check=True, cwd=ROOT)


def check_json(path, expected, label):
    """Fail if `path` does not hold every key of `expected` with that value."""
    held = json.load(open(path, encoding="utf-8"))
    wrong = {k: (held.get(k), v) for k, v in expected.items() if held.get(k) != v}
    if wrong:
        lines = "\n".join(f"  {k}: {got!r} against {want!r}" for k, (got, want) in wrong.items())
        raise SystemExit(
            f"{path} is not the {label} the runtime hardcodes:\n{lines}\n"
            "Re-read it and check the Rust and Kotlin still match before re-pinning."
        )
    print(f"{os.path.basename(path)} matches {len(expected)} hardcoded {label} constants")
    return held


def check_decoding(path):
    held = check_json(path, DECODING, "decoding")
    wrong = {}
    for key, count in DECODING_SIZES.items():
        got = len(held.get(key) or [])
        if got != count:
            wrong[key] = (got, count)
    # `transcribe` rather than `translate`: this is a recogniser, and the two ids are adjacent, so
    # reading one for the other is a one-digit mistake that produces English for every language.
    tasks = held.get("task_to_id") or {}
    if tasks.get("transcribe") != 50359:
        wrong["task_to_id.transcribe"] = (tasks.get("transcribe"), 50359)
    if wrong:
        lines = "\n".join(f"  {k}: {got!r} against {want!r}" for k, (got, want) in wrong.items())
        raise SystemExit(f"{path}'s decoding tables moved:\n{lines}")
    print(f"generation_config.json has {DECODING_SIZES['lang_to_id']} languages and "
          f"{DECODING_SIZES['suppress_tokens']} suppressed ids")
    return held


def check_bundled_vocabulary(upstream, bundled):
    """Fail unless the shipped `vocab.json` is the same token table as `upstream`'s.

    Not a digest comparison: the two mirrors serialise the same map differently — one key per line
    against compact — so the bytes differ and the meaning does not. What `WhisperTokenizer` reads is
    the token for each id below [`FIRST_SPECIAL_ID`], so that is what is compared.

    This is the only provenance the bundled table has ever had. The script this replaces pinned the
    digest of `onnx-community/whisper-base`'s copy, which said the bytes had not changed without
    saying they were right.
    """
    if not os.path.isfile(bundled):
        raise SystemExit(f"{bundled} is missing; it ships beside the model and is not rebuilt here")
    want = json.load(open(upstream, encoding="utf-8"))
    got = json.load(open(bundled, encoding="utf-8"))

    def table(mapping):
        out = [None] * FIRST_SPECIAL_ID
        for token, index in mapping.items():
            if 0 <= index < FIRST_SPECIAL_ID:
                out[index] = token
        return out

    a, b = table(want), table(got)
    differing = [index for index in range(FIRST_SPECIAL_ID) if a[index] != b[index]]
    missing = [index for index in range(FIRST_SPECIAL_ID) if b[index] is None]
    if differing or missing:
        raise SystemExit(
            f"{bundled} is not {REPO}'s token table:\n"
            f"  {len(differing)} ids differ, first {differing[:6]}\n"
            f"  {len(missing)} ids absent, first {missing[:6]}\n"
            "A wrong token table produces wrong text rather than an error."
        )
    print(f"the bundled vocab.json agrees with {REPO} on all {FIRST_SPECIAL_ID} text ids")


def check_bundled_generation(upstream, bundled):
    """Fail unless the shipped `generation_config.json` decodes the same way as `upstream`'s.

    Every field `post::whisper` reads, plus the three tables, compared by value. The bundled copy also
    carries a `trust_remote_code` key upstream's does not, which nothing reads and which is therefore
    not compared.
    """
    if not os.path.isfile(bundled):
        raise SystemExit(f"{bundled} is missing; it ships beside the model and is not rebuilt here")
    want = json.load(open(upstream, encoding="utf-8"))
    got = json.load(open(bundled, encoding="utf-8"))
    keys = list(DECODING) + list(DECODING_SIZES) + ["task_to_id"]
    wrong = {k: (got.get(k), want.get(k)) for k in keys if got.get(k) != want.get(k)}
    if wrong:
        lines = "\n".join(f"  {k}: bundled {g!r} against upstream {w!r}" for k, (g, w) in wrong.items())
        raise SystemExit(f"{bundled} is not {REPO}'s generation config:\n{lines}")
    print(f"the bundled generation_config.json agrees with {REPO} on all {len(keys)} decoding fields")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", help="where to keep the checkpoint (default: a temp directory)")
    parser.add_argument("-o", "--out", default=OUT, help="where to write the assets")
    parser.add_argument(
        "--print-digests",
        action="store_true",
        help="print the fetched files' SHA-256 instead of checking them, to re-pin after a bump",
    )
    args = parser.parse_args()

    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        raise SystemExit("needs huggingface_hub: python -m pip install huggingface_hub")

    work = args.work or os.path.join(tempfile.gettempdir(), "whisper")
    local = snapshot_download(
        repo_id=REPO, revision=REVISION, allow_patterns=list(DIGESTS), local_dir=work
    )
    print(f"{REPO}@{REVISION[:12]} in {local}")

    if args.print_digests:
        for name in DIGESTS:
            print(f'    {name}: "{sha256(os.path.join(local, name))}",')
        return 0

    for name, expected in DIGESTS.items():
        got = sha256(os.path.join(local, name))
        if got != expected:
            raise SystemExit(
                f"{name}\n  sha256 {got}\n  pinned {expected}\n"
                "The upstream checkpoint changed. Re-read it, check the Rust forward pass still\n"
                "matches, then re-pin with --print-digests."
            )
    print(f"{len(DIGESTS)} upstream digests match")
    check_json(os.path.join(local, CONFIG), ARCHITECTURE, "architecture")
    check_json(os.path.join(local, PREPROCESSOR), FEATURES, "mel front end")
    check_decoding(os.path.join(local, GENERATION))

    os.makedirs(args.out, exist_ok=True)
    graph = os.path.join(args.out, "whisper_base.maml")
    convert("maml_convert.py", os.path.join(local, CHECKPOINT), "--graph", "whisper", "-o", graph)
    # Checked rather than rewritten. See the module docstring.
    check_bundled_vocabulary(os.path.join(local, VOCAB), os.path.join(args.out, VOCAB))
    check_bundled_generation(
        os.path.join(local, GENERATION), os.path.join(args.out, GENERATION)
    )

    print(f"\n{args.out}")
    for name in sorted(os.listdir(args.out)):
        path = os.path.join(args.out, name)
        if os.path.isfile(path):
            size = os.path.getsize(path)
            print(f"  {name}: {size} bytes ({size / (1 << 20):.1f} MiB) {sha256(path)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
