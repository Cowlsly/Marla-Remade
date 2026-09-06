#!/usr/bin/env python3
"""gemma4_tokenizer.py - Gemma 4's `tokenizer.json` as the `SPM1` table `post::sentencepiece` reads.

# It is BPE, and that is the whole reason this is short

Gemma 4's tokenizer is a **BPE** model with 262144 pieces and 514906 merges, which is exactly the
algorithm `post::sentencepiece` already implements: greedy pairwise merging, highest score first,
leftmost on a tie. There is no Viterbi and no unigram lattice. So this script is a format
translation and not a new tokenizer.

# Scores are merge ranks, inverted

`SPM1` stores one score per *piece* and the Rust merges the pair with the highest score.
`tokenizer.json` stores an ordered `merges` list where **earlier means merges first**. So a
piece's score is `len(merges) - rank`, which turns "first in the list" into "largest number".

A piece produced by no merge - the base characters and the 256 byte pieces - keeps a score of 0.
It never participates as a merge *result*, so its score is never read by the merge loop; only its
id is, at the end.

# Byte fallback

The vocabulary holds all 256 `<0xNN>` pieces and `byte_fallback` is true, so any byte round-trips
and no text is ever lost to `<unk>`. `Table::parse_with` detects them by name; this script only
has to check they are all present, because a partial set would be worse than none.

# The specials are not fairseq's

`<pad>` 0, `<eos>` 1, `<bos>` 2, `<unk>` 3 - note `eos` **before** `bos`, the reverse of fairseq.
`post::sentencepiece::GEMMA` states the same, and `Table::parse_with` asserts it.

Run:

    python3 scripts/ml/gemma4_tokenizer.py \\
        --tokenizer tokenizer.json -o assets/gemma4_tokenizer.spm1
"""

from __future__ import annotations

import argparse
import json
import struct

MAGIC = b"SPM1"

# What `config.json`'s `vocab_size` says, and what `nets::gemma4::VOCAB` hardcodes.
VOCAB_SIZE = 262_144

# `post::sentencepiece::GEMMA`'s `names`, in id order from 0. Asserted rather than assumed: a
# table whose specials are elsewhere decodes every sequence off by one and looks almost right.
SPECIALS = ["<pad>", "<eos>", "<bos>", "<unk>"]


def build(tokenizer_path):
    """`[(piece, score)]` in id order, from a HuggingFace `tokenizer.json`."""
    with open(tokenizer_path, encoding="utf-8") as f:
        spec = json.load(f)
    model = spec["model"]
    if model.get("type") != "BPE":
        raise SystemExit(
            f"the tokenizer is {model.get('type')}, not BPE. `post::sentencepiece` merges "
            "pairwise by rank; a unigram model needs a Viterbi decode this does not have."
        )
    if not model.get("byte_fallback"):
        raise SystemExit("byte_fallback is off, so some bytes would have no spelling at all")

    vocab = model["vocab"]
    if len(vocab) != VOCAB_SIZE:
        raise SystemExit(f"{len(vocab)} pieces against a vocab_size of {VOCAB_SIZE}")

    merges = model["merges"]
    # `merges` is either ["a b", ...] or [["a", "b"], ...] depending on the version that wrote it.
    ranks = {}
    for rank, merge in enumerate(merges):
        pair = merge.split(" ", 1) if isinstance(merge, str) else list(merge)
        if len(pair) != 2:
            raise SystemExit(f"merge {rank} is {merge!r}, which is not a pair")
        # Earlier in the list merges first, so it must score higher.
        ranks.setdefault(pair[0] + pair[1], len(merges) - rank)

    table = [None] * VOCAB_SIZE
    for piece, index in vocab.items():
        if not 0 <= index < VOCAB_SIZE:
            raise SystemExit(f"{piece!r} has id {index}, outside the vocabulary")
        if table[index] is not None:
            raise SystemExit(f"id {index} is claimed by {table[index][0]!r} and {piece!r}")
        table[index] = (piece, ranks.get(piece, 0))
    missing = [i for i, entry in enumerate(table) if entry is None]
    if missing:
        raise SystemExit(f"{len(missing)} ids have no piece, the first being {missing[0]}")

    for index, name in enumerate(SPECIALS):
        if table[index][0] != name:
            raise SystemExit(f"id {index} is {table[index][0]!r}, not {name}")

    byte_pieces = [f"<0x{b:02X}>" for b in range(256)]
    held = {piece for piece, _ in table}
    absent = [p for p in byte_pieces if p not in held]
    if absent:
        raise SystemExit(f"{len(absent)} byte pieces are missing, the first being {absent[0]}")

    return table, len(ranks)


def pack(vocab):
    """The `SPM1` blob for `[(piece, score)]` in id order. Identical to `nllb_tokenizer.py`'s."""
    out = bytearray(MAGIC)
    out += struct.pack("<I", len(vocab))
    for piece, score in vocab:
        encoded = piece.encode("utf-8")
        if len(encoded) > 0xFFFF:
            raise SystemExit(f"a piece of {len(encoded)} bytes does not fit a u16 length")
        out += struct.pack("<iH", int(score), len(encoded))
        out += encoded
    return bytes(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tokenizer", required=True, help="tokenizer.json")
    parser.add_argument("-o", "--out", required=True)
    args = parser.parse_args()

    table, merged = build(args.tokenizer)
    blob = pack(table)
    with open(args.out, "wb") as f:
        f.write(blob)
    print(f"{len(table)} pieces, {merged} of them merge results")
    print(f"wrote {args.out}  {len(blob) / 1e6:.2f} MB")


if __name__ == "__main__":
    raise SystemExit(main())
