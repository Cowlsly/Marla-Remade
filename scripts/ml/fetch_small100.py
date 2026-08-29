#!/usr/bin/env python3
"""Fetch SMaLL-100's int8 ONNX export and build the two files `:translate` downloads.

# Why this exists

SMaLL-100 shipped with the worst provenance of any model in this tree. The ncnn `.param`/`.bin`
pair on the mirror was produced by a chain of hand-run tools that no longer exists, and
`tokenizer.bin` could not be rebuilt at all: `scripts/ml/small100_tokenizer.py` wanted a
`tokenizer_data.json` that is neither in the repo nor anywhere upstream. So the SHA-256 pins in
`translate/.../Small100Model.kt` asserted that six blobs had not changed, without anyone being
able to say where they came from. This closes that, the same way `fetch_supertonic.py` did.

It is not folded into `fetch_and_convert.sh` because these are **runtime downloads**, not bundled
assets: the output goes to a build directory and then to the mirror, so there is nothing for that
script's in-repo digest table to compare against.

# The pins, and what each one catches

* **File SHA-256**, below. Catches an upstream re-export at the same revision, or a truncated
  download. `sentencepiece.bpe.model`'s digest is the one already pinned in `Small100Model.kt`,
  which is the only thread connecting the mirror's blobs to a named upstream repo.
* **The layer table digest** `maml_convert.EXPECTED_DIGEST` pins. Catches an export whose
  *structure* moved, which is what would make the hardcoded Rust forward pass in
  `nets::small100` read the right shapes holding the wrong numbers.
* **`config.json`**, which is fetched and checked but not converted, because the architecture
  constants below are transcribed from it and `nets::small100` hardcodes them.

None of the three checks the numbers. `scripts/ml/onnx_parity.py` does.

# The export is already int8

Upstream ran `onnxruntime.quantization.quantize_dynamic(weight_type=QInt8)`, so every `MatMul`
is a `MatMulInteger` and the 131M-parameter embedding `Gather` is int8 too. That is the whole
reason this port is worth doing: ncnn could not quantise the embedding, which is why the model on
the mirror is 1.14 GB. See `scripts/ml/maml_convert.py` for what the converter does with it.

Usage:

    python scripts/ml/fetch_small100.py                 # verify digests, build the two files
    python scripts/ml/fetch_small100.py --work DIR      # keep the ONNX somewhere specific
    python scripts/ml/fetch_small100.py --print-digests # after an intentional upstream bump
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile

REPO = "casawolice/small100-onnx"

# Pinned to a commit rather than to `main`, for the reason `fetch_and_convert.sh` gives: the
# digests below would catch a silent upstream change anyway, but a pinned revision tells you the
# upstream *revision* that moved rather than just that some bytes differ.
REVISION = "5c2c73ac70bee9c58f5a7ac5e84a36bee25db8ee"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# Not an assets directory: these two files are fetched at run time from
# `data.vayunmathur.com/models/small100/`, so the build only has to produce them for upload.
# `build/` is already gitignored.
OUT = os.path.join(ROOT, "build", "small100")

ENCODER = "onnx/encoder_model.onnx"
DECODER = "onnx/decoder_model_merged.onnx"

# The three files the tokenizer table is built from. `vocab.json` decides ids because it is the
# fairseq order; the `.bpe.model` supplies merge ranks; `lang_tokens.json` supplies the 100
# `__xx__` ids so they are read rather than transcribed.
SPM = "sentencepiece.bpe.model"
VOCAB = "vocab.json"
LANGS = "lang_tokens.json"
CONFIG = "config.json"

# SHA-256 of every file this reads, at REVISION. Not the whole repo: the sample code,
# `tokenizer.json` and the 3.7 MB `added_tokens.json` are not fetched, so pinning them would pin
# something never downloaded.
DIGESTS = {
    ENCODER: "a130f553106646e56c1908094074f354353c45d86b3e8d222b037784035e6dcd",
    DECODER: "f966ce4f1f484c2307dc007ef0beadc3a1651220c1ff9b33ea8dddbce468a4b0",
    SPM: "d8f7c76ed2a5e0822be39f0a4f95a55eb19c78f4593ce609e2edbc2aea4d380a",
    VOCAB: "b6e77e474aeea8f441363aca7614317c06381f3eacfe10fb9856d5081d1074cc",
    LANGS: "c638e65941c57577fff6321d5be72b2b25afc39fde7d6a48adecd2e44b504ddf",
    CONFIG: "4ba2f8f7c5ec286bdb2088ac3b8d0ca3b8b19eb4f107c7c3a44205ba15873bb2",
}

WANTED = list(DIGESTS)

# Transcribed from the pinned `config.json`, and asserted against it below rather than trusted.
# `Small100Model.kt` claimed "36 enc + 13 dec" layers, which were ncnn's fused counts and do not
# map onto anything here; `nets::small100` is written against these.
ARCHITECTURE = {
    "d_model": 1024,
    "encoder_layers": 12,
    "decoder_layers": 3,
    "encoder_attention_heads": 16,
    "decoder_attention_heads": 16,
    "encoder_ffn_dim": 4096,
    "decoder_ffn_dim": 4096,
    "vocab_size": 128_112,
    "max_position_embeddings": 1024,
    "pad_token_id": 1,
    "decoder_start_token_id": 2,
    "scale_embedding": True,
    "activation_function": "relu",
}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def convert(script, *args):
    """Run one of the sibling converters, letting its own output and exit code through.

    `check=True` matters: `maml_convert.py` exits non-zero when a layer table digest does not
    match, and that is the check that must stop an upload being built from the wrong export.
    """
    subprocess.run([sys.executable, os.path.join(HERE, script), *args], check=True, cwd=ROOT)


def check_architecture(path):
    """Fail if the checkpoint's shape is not the one `nets::small100` is written against."""
    held = json.load(open(path, encoding="utf-8"))
    wrong = {k: (held.get(k), v) for k, v in ARCHITECTURE.items() if held.get(k) != v}
    if wrong:
        lines = "\n".join(f"  {k}: {got!r} against {want!r}" for k, (got, want) in wrong.items())
        raise SystemExit(
            f"{path} is not the architecture the runtime hardcodes:\n{lines}\n"
            "nets::small100 has these baked in, so re-read it before re-pinning."
        )
    print(f"config.json matches {len(ARCHITECTURE)} hardcoded architecture constants")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", help="where to keep the ONNX (default: a temp directory)")
    parser.add_argument("-o", "--out", default=OUT, help="where to write the two files")
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

    work = args.work or os.path.join(tempfile.gettempdir(), "small100-onnx")
    local = snapshot_download(
        repo_id=REPO, revision=REVISION, allow_patterns=WANTED, local_dir=work
    )
    print(f"{REPO}@{REVISION[:12]} in {local}")

    if args.print_digests:
        for name in WANTED:
            print(f'    {name}: "{sha256(os.path.join(local, name))}",')
        return 0

    for name, expected in DIGESTS.items():
        got = sha256(os.path.join(local, name))
        if got != expected:
            raise SystemExit(
                f"{name}\n  sha256 {got}\n  pinned {expected}\n"
                "The upstream export changed. Re-read it, check the Rust forward pass still\n"
                "matches, then re-pin with --print-digests."
            )
    print(f"{len(DIGESTS)} upstream digests match")
    check_architecture(os.path.join(local, CONFIG))

    os.makedirs(args.out, exist_ok=True)
    tokenizer = os.path.join(args.out, "tokenizer.bin")
    convert(
        "small100_tokenizer.py",
        "--spm", os.path.join(local, SPM),
        "--vocab", os.path.join(local, VOCAB),
        "--langs", os.path.join(local, LANGS),
        "-o", tokenizer,
    )

    print(f"\n{args.out}")
    for name in sorted(os.listdir(args.out)):
        path = os.path.join(args.out, name)
        if os.path.isfile(path):
            size = os.path.getsize(path)
            print(f"  {name}: {size} bytes ({size / (1 << 20):.1f} MiB) {sha256(path)}")
    print(
        "\nThe digests above are what `Small100Model.FILES` pins. To check the table against"
        "\nthe real `sentencepiece`:\n  SMALL100_TOKENIZER="
        f"{tokenizer} cargo test -p modelrunner --lib -- real_vocabulary"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
