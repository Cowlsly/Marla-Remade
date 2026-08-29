#!/usr/bin/env python3
"""SMaLL-100's SentencePiece vocabulary to the flat table `post::sentencepiece` reads.

# It is BPE, and the "scores" are merge ranks

The file is named `sentencepiece.bpe.model` and its `trainer_spec.model_type` is 2, BPE. Its
pieces carry a `score` of 0, -1, -2, ... which is the **negative merge rank**, not a log
probability - `,` scores -119149 because it is a very late merge, which looks like a bug until you
know the convention. Encoding is therefore greedy pairwise merging by highest score, leftmost on a
tie, and not the Viterbi a Unigram model would need.

# Two inputs, because only one of them can be fetched

`tokenizer_data.json` from the reference export is enough on its own: its `vocab` is 128,112
`[piece, score]` pairs in **fairseq** id order and its scores are the .model's. But that file is
not in the repo and is not among the pinned upstream downloads, so a fresh checkout cannot
reproduce the table from it. `--spm` rebuilds the same table from the three files that *are*
pinned - `sentencepiece.bpe.model`, `vocab.json` and `lang_tokens.json` - and is the path
`fetch_small100.py` drives.

The two sources are not interchangeable views of one list, because the .model and fairseq
disagree about both order and inventory:

* **Order.** The .model is `<unk> <s> </s>` then merges; fairseq is `<s> <pad> </s> <unk>` then
  merges. `vocab.json` *is* the fairseq order, so it decides ids and the .model only supplies
  scores.
* **Inventory.** 127,610 pieces are shared. 394 are in `vocab.json` only (`<pad>`, runs of NUL,
  a few hundred CJK characters) and 390 are in the .model only. The 390 cannot be represented -
  the table is indexed by id and those have none - and the real tokenizer maps them to `<unk>`
  anyway, since it segments with the .model and then looks the piece up in `vocab.json`.

# Scores the .model does not supply

502 of the 128,112 entries have no .model score: the 394 above, the 100 `__xx__` language tokens
and the 8 `madeupword` slots. They all get one below the lowest real rank, so a merge only ever
reaches them when no genuine merge applies. Giving them 0 instead - the *highest* rank, since
these are negative - would make `__en__` the first merge in any text containing it.

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
    ./scripts/ml/small100_tokenizer.py --spm sentencepiece.bpe.model \
        --vocab vocab.json --langs lang_tokens.json -o out/tokenizer.bin
"""

import argparse
import json
import os
import struct

MAGIC = b"SPM1"

# fairseq's four, which the Rust checks rather than assumes.
SPECIALS = {"bos": 0, "pad": 1, "eos": 2, "unk": 3}
METASPACE = "\u2581"

# `SMALL100Tokenizer.vocab_size` is `len(vocab.json) + 100 languages + num_madeup_words`, and the
# embedding the converter reads is this wide. A mismatch means the checkpoint moved.
VOCAB_SIZE = 128_112
MADEUP_WORDS = 8

# `trainer_spec.model_type`, from sentencepiece's ModelType enum. Encoding is greedy pairwise
# merging only because this is BPE; a Unigram model would need Viterbi over the same table.
MODEL_TYPE_BPE = 2


def read_varint(blob, at):
    """One protobuf varint, returning the value and the offset past it."""
    value = 0
    shift = 0
    while True:
        if at >= len(blob):
            raise SystemExit("a varint runs off the end of the model")
        byte = blob[at]
        at += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, at
        shift += 7


def fields(blob):
    """Every top-level protobuf field as `(number, wire_type, payload)`.

    Hand-decoded rather than generated, because the alternative is depending on the
    `sentencepiece` wheel to read three fields out of a file we already have. Only the two wire
    types this model uses are handled; anything else is a format change worth stopping on.
    """
    at = 0
    while at < len(blob):
        tag, at = read_varint(blob, at)
        number, wire = tag >> 3, tag & 7
        if wire == 2:
            length, at = read_varint(blob, at)
            yield number, wire, blob[at : at + length]
            at += length
        elif wire == 5:
            yield number, wire, blob[at : at + 4]
            at += 4
        elif wire == 0:
            value, at = read_varint(blob, at)
            yield number, wire, value
        else:
            raise SystemExit(f"unhandled protobuf wire type {wire} in the model")


def read_spm(path):
    """`piece -> merge rank` from a `sentencepiece.bpe.model`, asserting it is the BPE we expect.

    `ModelProto` numbers `pieces` 1, `trainer_spec` 2 and `normalizer_spec` 3; inside a piece,
    `piece` is 1 and `score` is 2 as a fixed32 float. Scores are whole numbers because they are
    ranks, and that is asserted rather than rounded - a fractional score would mean the file is
    Unigram after all, and the Rust merge loop would be the wrong algorithm.
    """
    blob = open(path, "rb").read()
    scores = {}
    checked_type = False
    for number, _, payload in fields(blob):
        if number == 1:
            piece, score = None, 0.0
            for inner, wire, value in fields(payload):
                if inner == 1:
                    piece = value.decode("utf-8")
                elif inner == 2 and wire == 5:
                    score = struct.unpack("<f", value)[0]
            if piece is None:
                raise SystemExit(f"{path}: a piece with no `piece` field")
            if score != int(score):
                raise SystemExit(f"{path}: {piece!r} scores {score}, which is not a merge rank")
            scores.setdefault(piece, int(score))
        elif number == 2:
            for inner, _, value in fields(payload):
                if inner == 3:
                    checked_type = True
                    if value != MODEL_TYPE_BPE:
                        raise SystemExit(
                            f"{path}: trainer_spec.model_type is {value}, not BPE"
                            f" ({MODEL_TYPE_BPE}); the merge loop in post::sentencepiece only"
                            " applies to BPE"
                        )
        elif number == 3:
            for inner, _, value in fields(payload):
                if inner == 1 and value.decode("utf-8") != "nmt_nfkc":
                    raise SystemExit(
                        f"{path}: normalizer is {value.decode('utf-8')!r}, not nmt_nfkc;"
                        " the Kotlin caller normalises with Form.NFKC"
                    )
    if not checked_type:
        raise SystemExit(f"{path}: no trainer_spec.model_type, so this may not be BPE")
    return scores


def build_from_spm(spm_path, vocab_path, langs_path):
    """The table from the three pinned upstream files. See the module docstring."""
    scores = read_spm(spm_path)
    encoder = json.load(open(vocab_path, encoding="utf-8"))
    langs = json.load(open(langs_path, encoding="utf-8"))["lang_to_id"]

    by_id = {}
    for piece, id_ in encoder.items():
        if id_ in by_id:
            raise SystemExit(f"{vocab_path}: id {id_} is both {by_id[id_]!r} and {piece!r}")
        by_id[id_] = piece
    if sorted(by_id) != list(range(len(by_id))):
        raise SystemExit(f"{vocab_path}: ids are not 0..{len(by_id) - 1}")
    base = len(by_id)
    for code, id_ in sorted(langs.items(), key=lambda pair: pair[1]):
        if id_ != len(by_id):
            raise SystemExit(f"{langs_path}: {code} is {id_}, not {len(by_id)}")
        by_id[id_] = f"__{code}__"
    for id_ in range(len(by_id), len(by_id) + MADEUP_WORDS):
        by_id[id_] = ""
    if len(by_id) != VOCAB_SIZE:
        raise SystemExit(
            f"{len(by_id)} ids from {base} vocab + {len(langs)} languages + {MADEUP_WORDS}"
            f" madeupwords, against config.json's vocab_size {VOCAB_SIZE}"
        )

    # One below the lowest real rank, so these lose every merge they are in.
    floor = min(scores.values()) - 1
    shared = sum(1 for piece in by_id.values() if piece in scores)
    vocab = [(by_id[id_], scores.get(by_id[id_], floor)) for id_ in range(len(by_id))]
    return vocab, shared


def build_from_json(path):
    """The table from `tokenizer_data.json`, which a fresh checkout cannot fetch."""
    held = json.load(open(path, encoding="utf-8"))
    if held.get("metaspace") != METASPACE:
        raise SystemExit(f"{path}: metaspace is {held.get('metaspace')!r}, not U+2581")
    if held.get("specials") != SPECIALS:
        raise SystemExit(f"{path}: specials are {held.get('specials')}, not {SPECIALS}")
    vocab = held["vocab"]
    if len(vocab) != held["vocab_size"]:
        raise SystemExit(f"{path}: {len(vocab)} entries against vocab_size {held['vocab_size']}")
    return [(piece, score) for piece, score in vocab]


def pack(vocab):
    """The `SPM1` blob for `[(piece, score)]` in id order."""
    names = [piece for piece, _ in vocab[:4]]
    if names != ["<s>", "<pad>", "</s>", "<unk>"]:
        raise SystemExit(f"ids 0..3 are {names}, not fairseq's specials in fairseq's order")
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
    parser.add_argument("tokenizer_data", nargs="?")
    parser.add_argument("--spm", help="sentencepiece.bpe.model, in place of tokenizer_data")
    parser.add_argument("--vocab", help="vocab.json, the fairseq id order; required with --spm")
    parser.add_argument("--langs", help="lang_tokens.json; required with --spm")
    parser.add_argument("-o", "--out", required=True)
    args = parser.parse_args()

    if args.spm:
        if not (args.vocab and args.langs):
            parser.error("--spm needs --vocab and --langs")
        vocab, shared = build_from_spm(args.spm, args.vocab, args.langs)
        note = f", {shared} scores from the .model"
    elif args.tokenizer_data:
        vocab = build_from_json(args.tokenizer_data)
        note = ""
    else:
        parser.error("pass either tokenizer_data or --spm")

    blob, count, empty = pack(vocab)
    directory = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(directory, exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(blob)
    print(f"{os.path.basename(args.out)}: {count} pieces ({empty} empty), {len(blob)} bytes{note}")


if __name__ == "__main__":
    main()
