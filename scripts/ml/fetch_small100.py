#!/usr/bin/env python3
"""Fetch SMaLL-100's checkpoint and build the two files `:translate` downloads.

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

# Two repos, and why the fp32 one

`alirezamsh/small100` is the model, MIT-licensed, and supplies the weights and both tokenizer
inputs. `casawolice/small100-onnx` supplies only `lang_tokens.json`, so the 100 `__xx__` ids are
read rather than transcribed a second time - `Small100Model.LANG_ORDER` already transcribes the
codes. Its `sentencepiece.bpe.model` and `vocab.json` are byte-identical to the fp32 repo's, which
is how the two are known to be the same checkpoint.

That repo also has an int8 ONNX export, and it is **not** used. It quantises per tensor rather
than per output channel, and it carries the tied 131M-parameter embedding three times, which is
why its deployment set is 609 MB against the 318 MiB this produces. See `CHECKPOINTS` in
`scripts/ml/maml_convert.py`.

# The pins, and what each one catches

* **File SHA-256**, below. Catches an upstream re-export at the same revision, or a truncated
  download. `sentencepiece.bpe.model`'s digest is the one already pinned in `Small100Model.kt`,
  which is the only thread connecting the mirror's blobs to a named upstream repo.
* **The parameter inventory** `maml_convert.check_checkpoint` asserts - every name and every
  shape. Catches a checkpoint whose *structure* moved, which is what would make the hardcoded
  Rust forward pass in `nets::small100` read the right shapes holding the wrong numbers. It is
  also what catches a transposed projection.
* **The layer table digest** `maml_convert.EXPECTED_DIGEST` pins, over the emitted order.
* **`config.json`**, which is fetched and checked but not converted, because the architecture
  constants below are transcribed from it and `nets::small100` hardcodes them.

The int8 fidelity gate in `maml_convert.collect_small100` is the only one of these that looks at
the numbers, and it only compares the quantisation against the weight it came from.
`scripts/ml/onnx_parity.py` is what compares the forward pass.

Usage:

    python scripts/ml/fetch_small100.py                 # verify digests, build the two files
    python scripts/ml/fetch_small100.py --work DIR      # keep the checkpoint somewhere specific
    python scripts/ml/fetch_small100.py --print-digests # after an intentional upstream bump
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile

REPO = "alirezamsh/small100"

# Pinned to a commit rather than to `main`, for the reason `fetch_and_convert.sh` gives: the
# digests below would catch a silent upstream change anyway, but a pinned revision tells you the
# upstream *revision* that moved rather than just that some bytes differ.
REVISION = "8ab680e26a596d2e3d2d2d17ae0f68df1037328c"

# Only `lang_tokens.json` comes from here. See the module docstring.
LANG_REPO = "casawolice/small100-onnx"
LANG_REVISION = "5c2c73ac70bee9c58f5a7ac5e84a36bee25db8ee"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# Not an assets directory: these two files are fetched at run time from
# `data.vayunmathur.com/models/small100/`, so the build only has to produce them for upload.
# `build/` is already gitignored.
OUT = os.path.join(ROOT, "build", "small100")

CHECKPOINT = "model.safetensors"

# The three files the tokenizer table is built from. `vocab.json` decides ids because it is the
# fairseq order; the `.bpe.model` supplies merge ranks; `lang_tokens.json` supplies the 100
# `__xx__` ids so they are read rather than transcribed.
SPM = "sentencepiece.bpe.model"
VOCAB = "vocab.json"
LANGS = "lang_tokens.json"
CONFIG = "config.json"

# SHA-256 of every file this reads, at the pinned revisions. Not the whole repo: the 1.86 GB
# `model.onnx` and the 1.33 GB `pytorch_model.bin` say the same thing as the checkpoint below, so
# pinning them would pin something never downloaded.
DIGESTS = {
    CHECKPOINT: "dd3b845a36ea4ed90437fd0b9b477e30c21f144d3658679fd5c945e3c96b0fbc",
    SPM: "d8f7c76ed2a5e0822be39f0a4f95a55eb19c78f4593ce609e2edbc2aea4d380a",
    VOCAB: "b6e77e474aeea8f441363aca7614317c06381f3eacfe10fb9856d5081d1074cc",
    CONFIG: "26fd3989cea6037d432c480f5181d05c088a613394d0361edc6605a6a1058715",
}
LANG_DIGESTS = {
    LANGS: "c638e65941c57577fff6321d5be72b2b25afc39fde7d6a48adecd2e44b504ddf",
}

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
    parser.add_argument("--work", help="where to keep the checkpoint (default: a temp directory)")
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

    work = args.work or os.path.join(tempfile.gettempdir(), "small100")
    local = snapshot_download(
        repo_id=REPO, revision=REVISION, allow_patterns=list(DIGESTS), local_dir=work
    )
    langs = snapshot_download(
        repo_id=LANG_REPO,
        revision=LANG_REVISION,
        allow_patterns=list(LANG_DIGESTS),
        local_dir=work + "-langs",
    )
    print(f"{REPO}@{REVISION[:12]} in {local}")
    print(f"{LANG_REPO}@{LANG_REVISION[:12]} in {langs}")

    found = [(local, DIGESTS), (langs, LANG_DIGESTS)]
    if args.print_digests:
        for root, pins in found:
            for name in pins:
                print(f'    {name}: "{sha256(os.path.join(root, name))}",')
        return 0

    for root, pins in found:
        for name, expected in pins.items():
            got = sha256(os.path.join(root, name))
            if got != expected:
                raise SystemExit(
                    f"{name}\n  sha256 {got}\n  pinned {expected}\n"
                    "The upstream export changed. Re-read it, check the Rust forward pass still\n"
                    "matches, then re-pin with --print-digests."
                )
    print(f"{len(DIGESTS) + len(LANG_DIGESTS)} upstream digests match")
    check_architecture(os.path.join(local, CONFIG))

    os.makedirs(args.out, exist_ok=True)
    tokenizer = os.path.join(args.out, "tokenizer.bin")
    convert(
        "small100_tokenizer.py",
        "--spm", os.path.join(local, SPM),
        "--vocab", os.path.join(local, VOCAB),
        "--langs", os.path.join(langs, LANGS),
        "-o", tokenizer,
    )
    convert(
        "maml_convert.py",
        os.path.join(local, CHECKPOINT),
        "--graph", "small100",
        "-o", os.path.join(args.out, "small100.maml"),
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
