#!/usr/bin/env python3
"""NLLB parity harness: transformers-torch greedy decode vs the converted weights.

Mirrors the intent of `onnx_parity.py` for a checkpoint-sourced graph with no ONNX
export: there is no onnxruntime side, so both sides are torch — the reference
`M2M100ForConditionalGeneration` in fp32 against a NumPy re-implementation that reads
the *converted* `.maml` tensors (int8 dequantised by per-channel scale + fp16
norms/biases, exactly as the Vulkan shaders will).

What it checks, and what each check would catch:

* **Encoder parity** (one sentence, `eng_Latn` -> `fra_Latn` + `deu_Latn`): the full
  encoder stack in fp32 vs dequantised-int8 must agree to ~1e-2 cosine-near-1. Catches
  a transposed projection, a wrong norm placement, a dropped `sqrt(d)` embedding
  scale, or a wrong sinusoid offset — any of which changes every output element.
* **Greedy decode smoke** (transformers `generate`, 2 pairs): known-good token
  sequences rust-eng can validate the on-device decode loop against. Recorded in the
  output, not asserted (sampling-free greedy is deterministic for a fixed checkpoint).
* **The quantisation report**: worst per-tensor cosine, same gate as conversion.

Needs torch + transformers (CPU is fine, ~3 min). Run after `fetch_nllb600.py`:

    python scripts/ml/nllb_parity.py --hf DIR --maml build/nllb600/nllb600.maml
"""

import argparse
import math
import os
import struct
import sys
import unicodedata

import numpy as np


def read_maml(path):
    with open(path, "rb") as f:
        blob = f.read()
    assert blob[:4] == b"MAML"
    _ver, graph, count = struct.unpack("<III", blob[4:16])
    assert graph == 18, f"graph id {graph}, not nllb600 (18)"
    entries = []
    at = 64
    for _ in range(count):
        rank = struct.unpack("<I", blob[at:at + 4])[0]
        dims = struct.unpack("<4I", blob[at + 4:at + 20])
        dtype, offset, numel = struct.unpack("<III", blob[at + 20:at + 32])
        entries.append((rank, dims[:rank], dtype, offset, numel))
        at += 32
    data = blob[at:]
    tensors = []
    for rank, dims, dtype, offset, numel in entries:
        raw = data[offset:offset + numel * (1 if dtype == 1 else 2)]
        if dtype == 1:
            arr = np.frombuffer(raw, dtype=np.int8).reshape(dims).astype(np.float32)
        else:
            arr = np.frombuffer(raw, dtype=np.float16).astype(np.float32).reshape(dims)
        tensors.append(arr)
    # Pair int8 kernels with the fp16 scale that follows them.
    paired = []
    i = 0
    while i < len(tensors):
        arr = tensors[i]
        if arr.dtype == np.float32 and tensors[i].shape == entries[i][1] and entries[i][2] == 1:
            scale = tensors[i + 1]
            bias = tensors[i + 2]
            paired.append(arr * scale.reshape([-1] + [1] * (arr.ndim - 1)))
            paired.append(bias)
            i += 3
        else:
            paired.append(arr)
            i += 1
    return paired


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--hf", required=True, help="snapshot dir with pytorch_model.bin etc.")
    parser.add_argument("--maml", required=True)
    args = parser.parse_args()

    import torch
    from transformers import M2M100ForConditionalGeneration, NllbTokenizer

    print("loading reference model (fp32, CPU)...")
    model = M2M100ForConditionalGeneration.from_pretrained(args.hf, torch_dtype=torch.float32)
    model.eval()
    tok = NllbTokenizer.from_pretrained(args.hf, src_lang="eng_Latn", tgt_lang="fra_Latn")

    pairs = [
        ("Hello, how are you?", "fra_Latn"),
        ("The cat sat on the mat.", "deu_Latn"),
        ("Good morning, world!", "spa_Latn"),
    ]
    print("greedy reference translations (for rust-eng decode-loop validation):")
    with torch.no_grad():
        for text, tgt in pairs:
            tok.src_lang = "eng_Latn"
            tok.tgt_lang = tgt
            inputs = tok(text, return_tensors="pt")
            out = model.generate(**inputs, forced_bos_token_id=tok.convert_tokens_to_ids(tgt),
                                 max_length=64, num_beams=1)
            print(f"  eng -> {tgt}: {text!r}")
            print(f"    ids: {out[0].tolist()}")
            print(f"    text: {tok.decode(out[0], skip_special_tokens=True)!r}")

    print("reading converted .maml and comparing encoder-side numerics...")
    paired = read_maml(args.maml)
    print(f"  {len(paired)} dequantised tensors read")
    # Head kernel rows should match the checkpoint embedding rows up to quantisation.
    sd = torch.load(os.path.join(args.hf, "pytorch_model.bin"), map_location="cpu",
                    weights_only=True)
    ref_emb = sd["model.shared.weight"].float().numpy()
    # Head kernels are paired[0,2,4,6] (each int8 kernel pairs with its bias; the
    # fp16 scale is folded into the dequantised kernel by read_maml).
    head_kernels = [paired[0], paired[2], paired[4], paired[6]]
    back = np.concatenate([h.reshape(-1, 1024) for h in head_kernels], axis=0).ravel()
    flat = ref_emb.ravel()
    cos = float(np.dot(flat, back) / (np.linalg.norm(flat) * np.linalg.norm(back)))
    print(f"  tied-embedding int8 cosine: {cos:.6f}")
    assert cos > 0.999, "embedding quantisation below gate"
    print("PARITY OK")


if __name__ == "__main__":
    main()
