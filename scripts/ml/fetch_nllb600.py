#!/usr/bin/env python3
"""Fetch NLLB-200-distilled-600M's checkpoint and build the files `:translate` downloads.

Mirrors `fetch_small100.py`: pin the HF revision, download + digest-check every input,
assert `config.json` against transcribed architecture constants, assert the four tied
embedding copies byte-identical, build `tokenizer.bin` + `nllb_langs.json` via
`nllb_tokenizer.py`, convert `pytorch_model.bin` (via a local `.safetensors` repack —
`maml_convert.open_checkpoint` reads safetensors) to `nllb600.maml` via
`maml_convert.py --graph nllb600`.

It is not folded into `fetch_and_convert.sh` because these are **runtime downloads**,
not bundled assets: the output goes to a build directory and then to the mirror.

# The decode protocol (contract with rust-eng's `nets::nllb600`, read from transformers)

* **Source side:** NLLB's `NllbTokenizer.set_src_lang_special_tokens` (non-legacy) uses
  `prefix=[src_lang]` + `suffix=[eos]` — i.e. the **source-language** token LEADS the
  source sequence: `[src_lang, pieces..., eos]`. (SMaLL-100/M2M-100 put the *target*
  token on the source side; NLLB puts the *source* token there. Easy to get backwards.)
* **Target side:** the decoder's first input is the **target-language** token as
  forced BOS (`forced_bos_token_id = tgt_lang_id`, `decoder_start_token_id` unused at
  inference): decode step 0 input is `[tgt_lang]`, then autoregressive.
* **Positions:** fairseq sinusoid, first real token at position 2
  (`cumsum(mask)*mask + padding_idx`, padding_idx 1 — `M2M100SinusoidalPositionalEmbedding`
  with `offset=2`). Same math as small100; the Rust reuses it with NLLB's constants.
* **FFN activation:** ReLU (`activation_function: relu` in config.json).
* **Embeddings:** `scale_embedding: true` — host gather multiplies by `sqrt(1024)`.
* **Specials:** BOS 0 (`<s>`), PAD 1, EOS 2 (`</s>`), UNK 3. Lang tokens 256001..256202,
  `<mask>` 256203 (never emitted), 256204..256205 padding.

# The pins, and what each one catches

* **File SHA-256** below. Catches an upstream re-export at the same revision, or a
  truncated download.
* **The parameter inventory** `maml_convert.check_checkpoint` asserts (509 names:
  `model.shared` + 12 enc + 12 dec layers + 2 final norms). Catches a transposed or
  resized checkpoint; `fc1 [4096,1024]` / `fc2 [1024,4096]` is the asymmetric pair.
* **The layer table digest** `maml_convert.EXPECTED_DIGEST["nllb600"]` pins (722 tensors).
* **`config.json`**, fetched and checked but not converted — the ARCHITECTURE constants
  below are transcribed from it and `nets::nllb600` hardcodes them.

Usage:

    python scripts/ml/fetch_nllb600.py                 # verify digests, build the files
    python scripts/ml/fetch_nllb600.py --work DIR      # keep the checkpoint somewhere specific
    python scripts/ml/fetch_nllb600.py --print-digests # after an intentional upstream bump
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile

REPO = "facebook/nllb-200-distilled-600M"

# Pinned commit. Re-pin with --print-digests after an intentional bump (and only then).
REVISION = "f8d333a098d19b4fd9a8b18f94170487ad3f821d"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# Not an assets directory: these files are fetched at run time from
# `data.vayunmathur.com/models/nllb600/`, so the build only has to produce them for upload.
OUT = os.path.join(ROOT, "build", "nllb600")

CHECKPOINT = "pytorch_model.bin"
SPM = "sentencepiece.bpe.model"
TOKENIZER = "tokenizer.json"
CONFIG = "config.json"
GENERATION_CONFIG = "generation_config.json"
SPECIAL_TOKENS = "special_tokens_map.json"

# SHA-256 of every file this reads, at the pinned revision. Fill in with --print-digests
# on the first run (placeholder zeros fail loudly until then); the checkpoint's are the
# 2.46 GB bin plus the small files.
DIGESTS = {
    CHECKPOINT: "c266c2cfd19758b6d09c1fc31ecdf1e485509035f6b51dfe84f1ada83eefcc42",
    SPM: "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a",
    TOKENIZER: "e316b82de11d0f951f370943b3c438311629547285129b0b81dadabd01bca665",
    CONFIG: "f9b4081d3d108e9d06532ea8d92b932c04ed3a15ff2f87e6982f8e14db51fbc5",
    GENERATION_CONFIG: "0bb604cdb8392649176935a447ce994128f170a6908c135a17d0e3dfb8113cb2",
    SPECIAL_TOKENS: "992bd4ed610d644d6823081937bcc91bb8878dd556cea4ae5327f2480361330e",
}

# Transcribed from the pinned `config.json`, and asserted against it below rather
# than trusted. `nets::nllb600` is written against these.
ARCHITECTURE = {
    "d_model": 1024,
    "encoder_layers": 12,
    "decoder_layers": 12,
    "encoder_attention_heads": 16,
    "decoder_attention_heads": 16,
    "encoder_ffn_dim": 4096,
    "decoder_ffn_dim": 4096,
    "vocab_size": 256_206,
    "max_position_embeddings": 1024,
    "pad_token_id": 1,
    "bos_token_id": 0,
    "eos_token_id": 2,
    "decoder_start_token_id": 2,
    "scale_embedding": True,
    "activation_function": "relu",
    "model_type": "m2m_100",
}

# The four names the tied embedding appears under. Asserted byte-identical before
# conversion; only model.shared.weight is emitted.
TIED_COPIES = [
    "model.shared.weight",
    "model.encoder.embed_tokens.weight",
    "model.decoder.embed_tokens.weight",
    "lm_head.weight",
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


def check_architecture(path):
    """Fail if the checkpoint's shape is not the one `nets::nllb600` is written against."""
    held = json.load(open(path, encoding="utf-8"))
    wrong = {k: (held.get(k), v) for k, v in ARCHITECTURE.items() if held.get(k) != v}
    if wrong:
        lines = "\n".join(f"  {k}: {got!r} against {want!r}" for k, (got, want) in wrong.items())
        raise SystemExit(
            f"{path} is not the architecture the runtime hardcodes:\n{lines}\n"
            "nets::nllb600 has these baked in, so re-read it before re-pinning."
        )
    print(f"config.json matches {len(ARCHITECTURE)} hardcoded architecture constants")


def check_tied(checkpoint_path):
    """Fail unless the four tied copies are byte-identical (so emitting one is exact)."""
    import torch

    sd = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    first = sd[TIED_COPIES[0]]
    for name in TIED_COPIES[1:]:
        other = sd[name]
        if other.shape != first.shape or not bool(torch.equal(first, other)):
            diff = float((first.float() - other.float()).abs().max())
            raise SystemExit(
                f"{name} differs from {TIED_COPIES[0]} (max abs diff {diff}): "
                "the embedding is untied, so emitting one copy is wrong."
            )
    print(f"tied embedding identical under {len(TIED_COPIES)} names {list(first.shape)}")


def to_safetensors(checkpoint_path, out_path):
    """Repack the `.bin` as `.safetensors` (same bytes, indexed) for `open_checkpoint`."""
    import torch
    from safetensors.torch import save_file

    sd = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    # The four tied copies share storage (which is itself the proof they are tied);
    # clone so safetensors does not refuse shared memory, and drop the three extras
    # so the repack holds exactly the 509-tensor architecture `check_checkpoint` wants.
    extras = [n for n in TIED_COPIES[1:] if n in sd]
    for name in extras:
        del sd[name]
    sd = {k: v.clone() for k, v in sd.items()}
    save_file(sd, out_path)
    print(f"  (dropped tied extras: {extras})")
    print(f"repacked {len(sd)} tensors -> {os.path.basename(out_path)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", help="where to keep the checkpoint (default: a temp directory)")
    parser.add_argument("-o", "--out", default=OUT, help="where to write the files")
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

    if args.local:
        local = args.local
    else:
        work = args.work or os.path.join(tempfile.gettempdir(), "nllb600")
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
    check_tied(os.path.join(local, CHECKPOINT))

    os.makedirs(args.out, exist_ok=True)
    convert(
        "nllb_tokenizer.py",
        "--spm", os.path.join(local, SPM),
        "--tokenizer", os.path.join(local, TOKENIZER),
        "-o", os.path.join(args.out, "tokenizer.bin"),
        "--langs-out", os.path.join(args.out, "nllb_langs.json"),
    )
    safetensors_path = os.path.join(args.out, "_nllb600.safetensors")
    to_safetensors(os.path.join(local, CHECKPOINT), safetensors_path)
    convert(
        "maml_convert.py",
        safetensors_path,
        "--graph", "nllb600",
        "-o", os.path.join(args.out, "nllb600.maml"),
        "--print-digest",
    )
    os.remove(safetensors_path)

    print(f"\n{args.out}")
    for name in sorted(os.listdir(args.out)):
        path = os.path.join(args.out, name)
        if os.path.isfile(path):
            size = os.path.getsize(path)
            print(f"  {name}: {size} bytes ({size / (1 << 20):.1f} MiB) {sha256(path)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
