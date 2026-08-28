#!/usr/bin/env python3
"""SMaLL-100's SentencePiece vocabulary to the flat table `post::sentencepiece` reads.

# It is BPE, and the "scores" are merge ranks

The file is named `sentencepiece.bpe.model` and its `trainer_spec.model_type` is 2, BPE. Its
pieces carry a `score` of 0, -1, -2, ... which is the **negative merge rank**, not a log
probability - `,` scores -119149 because it is a very late merge, which looks like a bug until you
know the convention. Encoding is therefore greedy pairwise merging by highest score, leftmost on a
tie, and not the Viterbi a Unigram model would need.

`tokenizer_data.json` from the reference export is enough on its own: its `vocab` is 128,112
`[piece, score]` pairs in **fairseq** id order (which differs from the .model's), and its scores
are byte-identical to the .model's for all 127,610 shared pieces. So no protobuf is parsed here or
anywhere else.

# Normalisation happens in Kotlin

The model's `normalizer_spec` is `nmt_nfkc` with a 237 KB `precompiled_charsmap`. Reproducing that
in Rust would mean carrying Unicode tables in the APK, so the app calls
`java.text.Normalizer.normalize(text, Form.NFKC)` first and `post::sentencepiece` takes it from
there - the same split `post::supertonic::to_ids` uses for NFD. Checked against the real
`sentencepiece` on 20 samples across Latin, Cyrillic, Arabic, Devanagari, Han, Kana, Hangul,
fullwidth forms and emoji: NFKC plus whitespace collapsing agrees on all of them.

# The format

    "SPM1"                     magic
    count      u32             vocab entries, so an id indexes directly
    count records, in id order:
        score  i32             the negative merge rank, exact as an integer
        length u16             the piece's UTF-8 length
        piece  length bytes

Eight of SMaLL-100's entries are empty (`madeupword` padding) and are written as zero-length
records rather than skipped, so ids stay aligned.

Usage:
    ./scripts/ml/small100_tokenizer.py tokenizer_data.json -o out/tokenizer.bin
"""

import argparse
import json
import os
import struct

MAGIC = b"SPM1"

# fairseq's four, which the Rust checks rather than assumes.
SPECIALS = {"bos": 0, "pad": 1, "eos": 2, "unk": 3}
METASPACE = "\u2581"


def build(path):
    held = json.load(open(path, encoding="utf-8"))
    if held.get("metaspace") != METASPACE:
        raise SystemExit(f"{path}: metaspace is {held.get('metaspace')!r}, not U+2581")
    if held.get("specials") != SPECIALS:
        raise SystemExit(f"{path}: specials are {held.get('specials')}, not {SPECIALS}")
    vocab = held["vocab"]
    if len(vocab) != held["vocab_size"]:
        raise SystemExit(f"{path}: {len(vocab)} entries against vocab_size {held['vocab_size']}")

    out = bytearray(MAGIC)
    out += struct.pack("<I", len(vocab))
    empty = 0
    for piece, score in vocab:
        encoded = piece.encode("utf-8")
        if len(encoded) > 0xFFFF:
            raise SystemExit(f"a piece of {len(encoded)} bytes does not fit a u16 length")
        if score != int(score):
            raise SystemExit(f"{piece!r} scores {score}, which is not a merge rank")
        empty += not encoded
        out += struct.pack("<iH", int(score), len(encoded))
        out += encoded
    return bytes(out), len(vocab), empty


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("tokenizer_data")
    parser.add_argument("-o", "--out", required=True)
    args = parser.parse_args()

    blob, count, empty = build(args.tokenizer_data)
    directory = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(directory, exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(blob)
    print(f"{os.path.basename(args.out)}: {count} pieces ({empty} empty), {len(blob)} bytes")


if __name__ == "__main__":
    main()
