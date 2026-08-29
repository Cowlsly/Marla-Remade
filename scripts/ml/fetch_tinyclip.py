#!/usr/bin/env python3
"""Fetch TinyCLIP's fp32 ONNX export and build the `tinyclip.maml` `:photos` bundles.

# Why this exists

TinyCLIP was the **only bundled model in this tree with no pinned upstream SHA-256 and no fetch
script**. `scripts/ml/fetch_and_convert.sh` lists six models and CLIP was not among them;
`photos/src/main/assets/clip/README.md` did not exist, and `photos/src/main/assets/README.md`
punted to `SUPPLY_CHAIN_RISKS.md`, which named an upstream repo in prose and nothing else. Every
other model here is pinned by revision *and* file digest. This closes that.

# The fp32 export, not the int8 one the app shipped

The asset being replaced is `onnx/model_int8.onnx` from this same repo, and it is **not** what is
converted. Read rather than assumed, its 80 `MatMulInteger` weights are per-output-channel with
zero zero-points — but three tensors are not:

* `vision_model.embeddings.patch_embedding.weight_scale` is a **scalar**, so one scale covers a
  kernel whose per-channel absmax spans 0.0018 to 0.117.
* `text_model.embeddings.token_embedding` is **uint8 with a zero point of 226**, and
  `text_model.embeddings.position_embedding` **uint8 with 220**. `conv_int8.comp` computes
  `scale[o] * sum(int8_w * in)` and has nowhere to put a zero point, which is the same objection
  `CHECKPOINTS` in `maml_convert.py` records against SMaLL-100's int8 export.

Requantising those per channel would add a second rounding to resolution that is already gone. So
this reads `onnx/model.onnx`, which is fp32, and `maml_convert.quantise_per_channel` gives all
three a per-output-channel scale and no zero point. The result is 22.6 MiB against the shipped
24.3 MB, and the measured worst per-tensor correlation is 0.99986.

`onnx/model.onnx` is a **build-host** dependency only: 94 MB downloaded here, nothing at run time.

# The pins, and what each one catches

* **File SHA-256**, below. Catches an upstream re-export at the same revision, or a truncated
  download.
* **`config.json`**, checked against [`ARCHITECTURE`] rather than trusted. `nets::tinyclip`
  hardcodes 10 vision layers, 3 text layers, width 256, 4 heads and a 1024-wide feed-forward.
* **The named-parameter inventory** `maml_convert.check_architecture` asserts — every name and
  every shape, including the `[in, out]` orientation of all 80 projections, which is what catches a
  transposed read. That mistake is invisible in the shape for the 76 square ones.
* **The op counts** `maml_convert.EXPECTED_OPS` pins, which only an ONNX graph can give.
* **The layer table digest** `maml_convert.EXPECTED_DIGEST` pins, over the emitted order.

The int8 fidelity gate in `maml_convert.Fidelity` is the only one of these that looks at the
numbers, and it only compares the quantisation against the weight it came from.
`scripts/ml/onnx_parity.py` is what compares the forward pass.

Usage:

    python scripts/ml/fetch_tinyclip.py                 # verify digests, write the asset
    python scripts/ml/fetch_tinyclip.py --work DIR      # keep the 94 MB export somewhere specific
    python scripts/ml/fetch_tinyclip.py --print-digests # after an intentional upstream bump
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile

REPO = "onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX"

# Pinned to a commit rather than to `main`, for the reason `fetch_and_convert.sh` gives: the
# digests below would catch a silent upstream change anyway, but a pinned revision tells you the
# upstream *revision* that moved rather than just that some bytes differ.
#
# MIT, and an ONNX export of `wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M`, which is also MIT.
REVISION = "9463a9c508a344c837ffefe9d724f3827bf2dc79"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# A bundled asset, unlike `fetch_small100.py`'s runtime downloads, so this writes straight into the
# APK's assets directory beside the BPE table the tokenizer reads.
OUT = os.path.join(ROOT, "photos", "src", "main", "assets", "clip")

MODEL = "onnx/model.onnx"
CONFIG = "config.json"
PREPROCESSOR = "preprocessor_config.json"

# SHA-256 of every file this reads, at the pinned revision. Not the whole repo: the seven other
# quantisations of the same graph say the same thing as the fp32 one, so pinning them would pin
# something never downloaded.
DIGESTS = {
    MODEL: "31d28cb07209533d10fc4fef73ac324ce17de6741a2372e7e1531a4ac8fdaeb2",
    CONFIG: "0ca46b868f12305e959a1cfa2b8085e7bffd521f68769ec3bf2999986b55bec3",
    PREPROCESSOR: "5df7e578c37e907a431daf47fd592fc49fa50d23ed4c41285a0a34a58a9d2e06",
}

# Transcribed from the pinned `config.json`, and asserted against it below rather than trusted.
# `nets::tinyclip` is written against these; `maml_convert.ARCHITECTURES` restates the subset the
# tensor table depends on.
ARCHITECTURE = {
    "projection_dim": 512,
    "vision": {
        "hidden_size": 256,
        "intermediate_size": 1024,
        "num_attention_heads": 4,
        "num_hidden_layers": 10,
        "patch_size": 16,
        "hidden_act": "gelu",
    },
    "text": {
        "hidden_size": 256,
        "intermediate_size": 1024,
        "num_attention_heads": 4,
        "num_hidden_layers": 3,
        "hidden_act": "gelu",
    },
}

# From the pinned `preprocessor_config.json`, and the numbers `ClipEmbedder.preprocess` hardcodes.
# Asserted here so a re-pin that changed the normalisation could not pass silently: a wrong mean or
# a wrong crop size produces embeddings that are plausible and wrong, and nothing downstream checks
# them.
PREPROCESSING = {
    "size": 224,
    "crop_size": 224,
    "image_mean": [0.48145466, 0.4578275, 0.40821073],
    "image_std": [0.26862954, 0.26130258, 0.27577711],
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
    match, and that is the check that must stop an asset being written from the wrong export.
    """
    subprocess.run([sys.executable, os.path.join(HERE, script), *args], check=True, cwd=ROOT)


def check_architecture(path):
    """Fail if the export's config is not the architecture `nets::tinyclip` is written against."""
    held = json.load(open(path, encoding="utf-8"))
    wrong = {}
    if held.get("projection_dim") != ARCHITECTURE["projection_dim"]:
        wrong["projection_dim"] = (held.get("projection_dim"), ARCHITECTURE["projection_dim"])
    for tower in ("vision", "text"):
        config = held.get(f"{tower}_config", {})
        for key, want in ARCHITECTURE[tower].items():
            if config.get(key) != want:
                wrong[f"{tower}_config.{key}"] = (config.get(key), want)
    if wrong:
        lines = "\n".join(f"  {k}: {got!r} against {want!r}" for k, (got, want) in wrong.items())
        raise SystemExit(
            f"{path} is not the architecture the runtime hardcodes:\n{lines}\n"
            "nets::tinyclip has these baked in, so re-read it before re-pinning."
        )
    print("config.json matches the hardcoded architecture of both towers")


def check_preprocessing(path):
    """Fail if the export's preprocessing is not what `ClipEmbedder.preprocess` implements."""
    held = json.load(open(path, encoding="utf-8"))
    got = {
        # Both are `{"shortest_edge": 224}` / `{"height": 224, "width": 224}` dicts upstream, so
        # the values are pulled out rather than compared as dicts.
        "size": (held.get("size") or {}).get("shortest_edge"),
        "crop_size": (held.get("crop_size") or {}).get("height"),
        "image_mean": held.get("image_mean"),
        "image_std": held.get("image_std"),
    }
    close = all(
        abs(a - b) < 1e-6
        for key in ("image_mean", "image_std")
        for a, b in zip(got[key] or [], PREPROCESSING[key])
    )
    sized = got["size"] == PREPROCESSING["size"] and got["crop_size"] == PREPROCESSING["crop_size"]
    if not (close and sized):
        raise SystemExit(
            f"{path} is not the preprocessing ClipEmbedder implements:\n"
            f"  got  {got}\n  want {PREPROCESSING}\n"
            "A wrong mean or crop produces embeddings that are plausible and wrong."
        )
    print("preprocessor_config.json matches ClipEmbedder.preprocess")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", help="where to keep the export (default: a temp directory)")
    parser.add_argument("-o", "--out", default=OUT, help="where to write tinyclip.maml")
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

    work = args.work or os.path.join(tempfile.gettempdir(), "tinyclip")
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
                "The upstream export changed. Re-read it, check the Rust forward pass still\n"
                "matches, then re-pin with --print-digests."
            )
    print(f"{len(DIGESTS)} upstream digests match")
    check_architecture(os.path.join(local, CONFIG))
    check_preprocessing(os.path.join(local, PREPROCESSOR))

    os.makedirs(args.out, exist_ok=True)
    asset = os.path.join(args.out, "tinyclip.maml")
    convert("maml_convert.py", os.path.join(local, MODEL), "--graph", "tinyclip", "-o", asset)

    size = os.path.getsize(asset)
    print(f"\n{asset}: {size} bytes ({size / (1 << 20):.1f} MiB)")
    print(f"  sha256 {sha256(asset)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
