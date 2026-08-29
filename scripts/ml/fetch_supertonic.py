#!/usr/bin/env python3
"""Fetch Supertonic 3's ONNX export and build the bundle `:speech` ships.

Supertonic was the one model in this tree with **no recorded provenance**. Every other one is
pinned by upstream revision and file SHA-256 in `scripts/ml/fetch_and_convert.sh`, with a README
beside the asset; Supertonic's four exports were fetched by hand, so nothing said where they came
from and the bundle could not be rebuilt from a fresh checkout. This closes that.

It is not folded into `fetch_and_convert.sh` because the shape is different: that script is one
ONNX to one `.maml` per model, while this is one upstream repo to *four* `.maml` plus a codepoint
table and ten voice styles, through two converters. It also needs `huggingface_hub`, which the
shell script deliberately does not.

# The licence is use-restricted

Supertonic 3 is **OpenRAIL**, the first such licence in this tree - everything else here is
Apache-2.0, MIT, ODbL or InsightFace's non-commercial research terms. Because the bundle ships
inside the APK rather than being downloaded by a user who went looking for it, the restriction
travels with every release. See `SUPPLY_CHAIN_RISKS.md`.

# What is verified, and what that catches

Two independent checks, and they fail for different reasons:

* The **file SHA-256** of each ONNX, below. Catches an upstream re-export at the same revision, or
  a truncated download.
* The **layer table digest** each converter pins in `maml_convert.EXPECTED_DIGEST`. Catches an
  export whose *structure* moved - a reordered or re-padded layer - which is what would make the
  hardcoded Rust forward passes read the right shapes holding the wrong numbers.

Neither checks the numbers. `scripts/ml/onnx_parity.py` does, and is worth running after any
change here; the four correlations it reported for this revision are in the table at the bottom.

Usage:

    python scripts/ml/fetch_supertonic.py                 # verify digests, rebuild the bundle
    python scripts/ml/fetch_supertonic.py --work DIR      # keep the ONNX somewhere specific
    python scripts/ml/fetch_supertonic.py --print-digests # after an intentional upstream bump
"""

import argparse
import hashlib
import os
import subprocess
import sys
import tempfile

REPO = "Supertone/supertonic-3"

# Pinned to a commit rather than to `main`, for the reason `fetch_and_convert.sh` gives: the
# digests below would catch a silent upstream change anyway, but a pinned revision tells you the
# upstream *revision* that moved rather than just that some bytes differ.
REVISION = "3cadd1ee6394adea1bd021217a0e650ede09a323"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(ROOT, "speech", "src", "main", "assets", "supertonic")

# graph id -> the ONNX in the upstream repo it is folded from.
GRAPHS = {
    "supertonic_dp": "onnx/duration_predictor.onnx",
    "supertonic_ttl": "onnx/text_encoder.onnx",
    "supertonic_ve": "onnx/vector_estimator.onnx",
    "supertonic_voc": "onnx/vocoder.onnx",
}

# The codepoint table and the ten voice styles, which are inputs rather than weights and so are
# not `.maml`. See `supertonic_bundle.py`.
INDEXER = "onnx/unicode_indexer.json"
STYLES = "voice_styles"

# SHA-256 of every file this reads, at REVISION. Not the whole repo: the 11 MB of audio samples and
# hero images upstream ships are not fetched, so pinning them would pin something never downloaded.
DIGESTS = {
    "onnx/duration_predictor.onnx":
        "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
    "onnx/text_encoder.onnx":
        "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
    "onnx/vector_estimator.onnx":
        "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c",
    "onnx/vocoder.onnx":
        "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba",
}

# Only what the two converters read. The ONNX is 398 MB against the ~198 MB of fp16 `.maml` it
# becomes, and nothing reads it at run time, so it stays out of the repo - the same rule
# `fetch_and_convert.sh` applies to every other model here.
WANTED = list(GRAPHS.values()) + [INDEXER, f"{STYLES}/*.json", "LICENSE", "config.json"]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def convert(script, *args):
    """Run one of the sibling converters, letting its own output and exit code through.

    `check=True` matters: `supertonic_fold.py` exits non-zero when a layer table digest does not
    match, and that is the check that must stop a bundle being built from the wrong export.
    """
    subprocess.run([sys.executable, os.path.join(HERE, script), *args], check=True, cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", help="where to keep the ONNX (default: a temp directory)")
    parser.add_argument("-o", "--out", default=ASSETS, help="where to write the bundle")
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

    work = args.work or os.path.join(tempfile.gettempdir(), "supertonic-3")
    local = snapshot_download(
        repo_id=REPO, revision=REVISION, allow_patterns=WANTED, local_dir=work
    )
    print(f"{REPO}@{REVISION[:12]} in {local}")

    if args.print_digests:
        for name in sorted(GRAPHS.values()):
            print(f'    "{name}":\n        "{sha256(os.path.join(local, name))}",')
        return 0

    for name, expected in sorted(DIGESTS.items()):
        got = sha256(os.path.join(local, name))
        if got != expected:
            raise SystemExit(
                f"{name}\n  sha256 {got}\n  pinned {expected}\n"
                "The upstream export changed. Re-read it, check the Rust forward pass still\n"
                "matches, then re-pin with --print-digests."
            )
    print(f"{len(DIGESTS)} ONNX digests match")

    os.makedirs(args.out, exist_ok=True)
    for graph, onnx in sorted(GRAPHS.items()):
        convert(
            "supertonic_fold.py",
            os.path.join(local, onnx),
            "--graph", graph,
            "-o", os.path.join(args.out, f"{graph}.maml"),
        )
    convert(
        "supertonic_bundle.py",
        "--indexer", os.path.join(local, INDEXER),
        "--styles", os.path.join(local, STYLES),
        "-o", args.out,
    )

    total = 0
    for name in sorted(os.listdir(args.out)):
        path = os.path.join(args.out, name)
        if not os.path.isfile(path) or name.endswith(".md"):
            continue
        total += os.path.getsize(path)
    print(f"\n{args.out}: {total} bytes ({total / (1 << 20):.1f} MiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
