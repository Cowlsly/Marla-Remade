#!/usr/bin/env python3
"""Fetch Maia3-5M's checkpoint and build the `.maml` `:games:chess` bundles.

Mirrors `fetch_nllb600.py`'s `.pt` handling and `fetch_tinyclip.py`'s output path: pin the
HF revision, digest-check the download, assert the parameter inventory, repack the pickle
as `.safetensors` (`maml_convert.open_checkpoint` reads safetensors and there is no
`torch.load` in it), then convert to `maia3-5m.maml` in the app's assets directory.

Unlike NLLB this is a **bundled asset**, not a runtime download: 14 MiB is small enough to
ship, and the chess AI has to work offline. It replaces the 86 MB
`nn-71d6d32cb962.nnue` Stockfish read.

# The three pins, and what each one catches

* **The checkpoint SHA-256** below. Catches an upstream re-upload at the same revision, or
  a truncated download.
* **The parameter inventory** `maml_convert.check_checkpoint` asserts (148 names after the
  repack). Catches a transposed or resized checkpoint; `linear1 [512, 256]` against
  `linear2 [256, 512]` is the asymmetric pair.
* **The layer table digest** `maml_convert.EXPECTED_DIGEST["maia"]` pins (190 tensors).

# The shared attention-bias weight

`smolgen_shared_weight` is one parameter registered under nine names: itself, and
`transformer.layers.N.self_attn.smolgen_weight` for all eight blocks. `CustomTransformerEncoder`
assigns the same `nn.Parameter` into every block, so all nine entries alias one storage.

[`check_shared`] asserts that — byte-identical *and* the same `data_ptr` — and the repack
drops the eight extras, the way `fetch_nllb600.py` drops NLLB's three tied copies. If a
future checkpoint gives each block its own bias weight the drop would silently discard
seven real tensors, so it fails instead.

# Elo, and what it means for the app

Strength is a model **input** (`SelfElo` / `OppoElo`, 0..5000), not a search handicap, so
this one file covers every difficulty. See `games/chess/.../MaiaEngine.kt` for the mapping.

Usage:

    python scripts/ml/fetch_maia.py                  # verify digests, build the .maml
    python scripts/ml/fetch_maia.py --work DIR       # keep the checkpoint somewhere specific
    python scripts/ml/fetch_maia.py --print-digests  # after an intentional upstream bump
"""

import argparse
import hashlib
import os
import subprocess
import sys
import tempfile

REPO = "UofTCSSLab/Maia3-5M"

# Pinned commit. Re-pin with --print-digests after an intentional bump (and only then).
REVISION = "b6559de2398d7140b985f28fd2c19fb5e47ddabe"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# A bundled asset, beside the app's other assets. `noCompress += "maml"` in
# `games/chess/build.gradle.kts` keeps it page-aligned so `MaiaHandle` can open it as a
# file descriptor and read it in place rather than copying it to internal storage.
OUT = os.path.join(ROOT, "games", "chess", "src", "main", "assets")

CHECKPOINT = "maia3-5m.pt"

# SHA-256 of every file this reads, at the pinned revision.
DIGESTS = {
    CHECKPOINT: "ba14208b2992d85502f5fb501934abf6aaaeb355e9f3fdf90e326911f562524f",
}

# Transcribed from `maia3/model_registry.py`'s `BASE_SIZE_CONFIG` plus the `maia3-5m` row of
# `MODEL_SPECS`, and asserted against the checkpoint's shapes below rather than trusted.
# `nets::maia` is written against these.
ARCHITECTURE = {
    "dim_vit": 256,
    "head_hid_dim": 256,
    "num_heads": 8,
    "num_blocks": 8,
    "mlp_ratio": 2.0,
    "history": 8,
    "dim_emb": 128,
    "gab_gen_size": 64,
    "gab_intermediate_dim": 64,
    # `gab_per_square_dim == 0` is what makes the bias generator pool the squares with a
    # mean rather than running a per-square projection first — so there is no `sm1`, and
    # `nets::maia::attention_bias` starts with a `global_avg_pool`.
    "gab_per_square_dim": 0,
    "use_gab": True,
    "use_relative_bias": False,
    "use_absolute_pe": False,
    "use_rms_norm": True,
    "omit_qkv_biases": True,
    "include_time_info": False,
    "activation": "gelu",
}

# Total parameters after the repack drops the eight aliased copies: 7,327,236 in the
# checkpoint, minus 8 x 262,144.
PARAMETERS = 5_230_084

# The nine names the attention-bias weight appears under. Asserted to alias one storage
# before conversion; only `smolgen_shared_weight` survives the repack.
SHARED_COPIES = ["smolgen_shared_weight"] + [
    f"transformer.layers.{index}.self_attn.smolgen_weight" for index in range(8)
]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def convert(script, *args):
    """Run one of the sibling converters, letting its own output and exit code through."""
    subprocess.run([sys.executable, os.path.join(HERE, script), *args], check=True, cwd=ROOT)


def load(checkpoint_path):
    import torch

    return torch.load(checkpoint_path, map_location="cpu", weights_only=True)


def check_shared(state):
    """Fail unless all nine attention-bias names are one storage (so dropping eight is exact)."""
    import torch

    first = state[SHARED_COPIES[0]]
    for name in SHARED_COPIES[1:]:
        other = state[name]
        if other.shape != first.shape or not bool(torch.equal(first, other)):
            raise SystemExit(
                f"{name} differs from {SHARED_COPIES[0]}: the blocks no longer share one "
                "attention-bias weight, so dropping the per-block copies would lose seven "
                "real tensors. nets::maia assumes one shared weight."
            )
        if other.data_ptr() != first.data_ptr():
            raise SystemExit(
                f"{name} is equal to {SHARED_COPIES[0]} but is a separate tensor. Equal by "
                "coincidence is not shared; re-read the checkpoint before re-pinning."
            )
    print(f"attention-bias weight shared under {len(SHARED_COPIES)} names {list(first.shape)}")


def check_architecture(state):
    """Fail if the checkpoint's shapes are not the ones `nets::maia` hardcodes.

    There is no `config.json` in this repo — the architecture lives in `model_registry.py`
    on the Python side and in `nets::maia`'s constants on ours — so the shapes *are* the
    config, and this reads them back out of the checkpoint.
    """
    dim = ARCHITECTURE["dim_vit"]
    ffn = int(dim * ARCHITECTURE["mlp_ratio"])
    heads, gen = ARCHITECTURE["num_heads"], ARCHITECTURE["gab_gen_size"]
    want = {
        "smolgen_shared_weight": (64 * 64, gen),
        "elo_embedding_low.weight": (1, ARCHITECTURE["dim_emb"]),
        "token_projection.weight": (dim, 12 * ARCHITECTURE["history"] + 2 * ARCHITECTURE["dim_emb"]),
        "transformer.layers.0.self_attn.mha.in_proj_weight": (3 * dim, dim),
        "transformer.layers.0.self_attn.sm2.weight": (ARCHITECTURE["gab_intermediate_dim"], dim),
        "transformer.layers.0.self_attn.sm3.weight": (heads * gen, ARCHITECTURE["gab_intermediate_dim"]),
        "transformer.layers.0.linear1.weight": (ffn, dim),
        "transformer.layers.0.linear2.weight": (dim, ffn),
        "promo_bias_proj.weight": (4, ARCHITECTURE["head_hid_dim"]),
    }
    wrong = {
        name: (tuple(state[name].shape) if name in state else None, shape)
        for name, shape in want.items()
        if name not in state or tuple(state[name].shape) != shape
    }
    if wrong:
        lines = "\n".join(f"  {k}: {got} against {exp}" for k, (got, exp) in wrong.items())
        raise SystemExit(
            f"the checkpoint is not the architecture the runtime hardcodes:\n{lines}\n"
            "nets::maia has these baked in, so re-read it before re-pinning."
        )

    # `omit_qkv_biases` and `use_rms_norm` are absences, so they are checked as absences.
    for name in (
        "transformer.layers.0.self_attn.mha.in_proj_bias",
        "transformer.layers.0.self_attn.mha.out_proj.bias",
        "transformer.layers.0.norm1.bias",
    ):
        if name in state:
            raise SystemExit(
                f"{name} is present, so this checkpoint is not omit_qkv_biases=True with "
                "RMSNorm blocks. nets::maia synthesises zero biases and reads no beta."
            )

    total = sum(state[name].numel() for name in state if name not in SHARED_COPIES[1:])
    if total != PARAMETERS:
        raise SystemExit(f"{total} parameters after de-aliasing, not the pinned {PARAMETERS}")
    print(f"{len(want)} shapes and {total} parameters match the hardcoded architecture")


def to_safetensors(state, out_path):
    """Repack as `.safetensors` (same values, indexed) for `open_checkpoint`."""
    from safetensors.torch import save_file

    # The eight per-block copies share storage with the shared weight (which is itself the
    # proof they are shared), and safetensors refuses shared memory. Drop them, then clone
    # so nothing else in the file is a view.
    kept = {k: v.clone() for k, v in state.items() if k not in SHARED_COPIES[1:]}
    save_file(kept, out_path)
    print(f"  (dropped {len(SHARED_COPIES) - 1} aliased attention-bias copies)")
    print(f"repacked {len(kept)} tensors -> {os.path.basename(out_path)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", help="where to keep the checkpoint (default: a temp directory)")
    parser.add_argument("-o", "--out", default=OUT, help="where to write the .maml")
    parser.add_argument(
        "--print-digests",
        action="store_true",
        help="print the fetched files' SHA-256 instead of checking them, to re-pin after a bump",
    )
    parser.add_argument(
        "--local", help="use an already-downloaded snapshot dir instead of downloading"
    )
    args = parser.parse_args()

    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        raise SystemExit("needs huggingface_hub: python -m pip install huggingface_hub")
    try:
        import torch  # noqa: F401
    except ImportError:
        raise SystemExit("needs torch to read the .pt: python -m pip install torch")

    if args.local:
        local = args.local
    else:
        work = args.work or os.path.join(tempfile.gettempdir(), "maia3-5m")
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
                "The upstream checkpoint changed. Re-read it, check the Rust forward pass\n"
                "still matches, then re-pin with --print-digests."
            )
    print(f"{len(DIGESTS)} upstream digests match")

    state = load(os.path.join(local, CHECKPOINT))
    check_shared(state)
    check_architecture(state)

    os.makedirs(args.out, exist_ok=True)
    safetensors_path = os.path.join(args.out, "_maia3-5m.safetensors")
    to_safetensors(state, safetensors_path)
    convert(
        "maml_convert.py",
        safetensors_path,
        "--graph", "maia",
        "-o", os.path.join(args.out, "maia3-5m.maml"),
    )
    os.remove(safetensors_path)

    built = os.path.join(args.out, "maia3-5m.maml")
    size = os.path.getsize(built)
    print(f"\n{built}: {size} bytes ({size / (1 << 20):.1f} MiB) {sha256(built)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
