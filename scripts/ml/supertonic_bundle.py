#!/usr/bin/env python3
"""Supertonic 3's non-weight bundle files: the codepoint table and the voice styles.

`supertonic_fold.py` produces the four `.maml` plans. Two more things travel with them and are
neither weights nor plans:

    onnx/unicode_indexer.json  -> unicode_indexer.bin   65,536 int16, one per BMP codepoint
    voice_styles/<name>.json   -> style_<name>.bin      style_ttl then style_dp, as fp16

# Why the codepoint table is not a `.maml`

A `.maml` holds fp16 or int8 *tensors*, and this is a lookup table of signed indices: `-1` where
the model has no token, 0..8320 where it has one. 65,536 `int16` is 128 KB against the JSON's
278 KB, and the Rust reads it as a borrowed `&[u8]` with no parsing at all. See
`post::supertonic::to_ids`, and note that the model expects **NFD** text - precomposed accents are
unmapped while combining marks are first-class tokens.

# Why the styles are not weights either

`style_ttl` and `style_dp` are *inputs*, one pair per voice, so they cannot live in a plan's
weights buffer. `style_ttl` is `[1, 50, 256]` in the JSON and this runtime is channel-major, so it
is written **transposed** to `[256, 50]`; `style_dp` is `[1, 8, 16]` and flattens row-major to 128,
which is the order the export's own `Reshape` produces.

Usage:
    ./scripts/ml/supertonic_bundle.py --indexer onnx/unicode_indexer.json -o out/
    ./scripts/ml/supertonic_bundle.py --styles voice_styles/ -o out/
"""

import argparse
import json
import os

import numpy as np

# Every code unit of the Basic Multilingual Plane. Astral-plane characters are outside the table
# and dropped by the reader rather than being mapped to -1 here.
ENTRIES = 65_536

# `n_style` and `style_key_dim` of the text-to-latent style token layer, and `n_style * style_dim`
# of the duration predictor's.
STYLE_TOKENS = 50
STYLE_WIDTH = 256
DURATION_STYLE = 128


def indexer(path, out):
    """`unicode_indexer.json` to a flat `int16` table."""
    table = json.load(open(path, encoding="utf-8"))
    if len(table) != ENTRIES:
        raise SystemExit(f"{path}: {len(table)} entries, not {ENTRIES}")
    values = np.asarray(table, dtype=np.int64)
    if values.min() < -1 or values.max() > 32_767:
        raise SystemExit(f"{path}: indices {values.min()}..{values.max()} do not fit int16")
    mapped = int((values >= 0).sum())
    target = os.path.join(out, "unicode_indexer.bin")
    values.astype("<i2").tofile(target)
    print(f"  unicode_indexer.bin  {mapped} mapped, max index {values.max()}, {ENTRIES * 2} bytes")
    return target


def style(path, out):
    """One `voice_styles/<name>.json` to `style_<name>.bin`, transposed and fp16."""
    name = os.path.splitext(os.path.basename(path))[0]
    held = json.load(open(path, encoding="utf-8"))

    def read(key, dims):
        entry = held[key]
        values = np.asarray(entry["data"], dtype=np.float32)
        if list(entry["dims"]) != dims:
            raise SystemExit(f"{path}: {key} is {entry['dims']}, not {dims}")
        return values.reshape(dims)

    ttl = read("style_ttl", [1, STYLE_TOKENS, STYLE_WIDTH]).reshape(STYLE_TOKENS, STYLE_WIDTH)
    dp = read("style_dp", [1, 8, 16]).reshape(-1)
    if dp.size != DURATION_STYLE:
        raise SystemExit(f"{path}: style_dp flattens to {dp.size}, not {DURATION_STYLE}")

    # Channel-major for `style_ttl`; `style_dp` is already in the order the export's `Reshape`
    # produces, which is what the duration predictor's head concatenates.
    blob = np.concatenate(
        [np.ascontiguousarray(ttl.T).reshape(-1).astype(np.float16), dp.astype(np.float16)]
    )
    target = os.path.join(out, f"style_{name}.bin")
    blob.tofile(target)
    print(f"  style_{name}.bin  {blob.size} fp16, {blob.nbytes} bytes")
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--indexer", help="onnx/unicode_indexer.json")
    parser.add_argument("--styles", help="the voice_styles directory, or one .json in it")
    parser.add_argument("-o", "--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    if not args.indexer and not args.styles:
        raise SystemExit("nothing to do: pass --indexer, --styles or both")
    if args.indexer:
        indexer(args.indexer, args.out)
    if args.styles:
        if os.path.isdir(args.styles):
            names = sorted(
                os.path.join(args.styles, f)
                for f in os.listdir(args.styles)
                if f.endswith(".json")
            )
            if not names:
                raise SystemExit(f"{args.styles}: no .json voice styles")
        else:
            names = [args.styles]
        for name in names:
            style(name, args.out)


if __name__ == "__main__":
    main()
