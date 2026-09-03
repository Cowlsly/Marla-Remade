#!/usr/bin/env python3
"""NLLB-200's SentencePiece vocabulary to the flat table `post::sentencepiece` reads.

# It is BPE, and the "scores" are merge ranks

|`sentencepiece.bpe.model`'s `trainer_spec.model_type` is 2, BPE (asserted by `read_spm`
below). Its pieces carry scores 0, -1, -2, ... -255996,
the **negative merge rank**, not a log probability. Encoding is therefore greedy pairwise
merging by highest score, leftmost on a tie — exactly the loop `post::sentencepiece`
already implements for SMaLL-100. No Rust change needed; this was the hard blocker and
it is cleared.

# Inventory: 256,206 ids, and where each comes from

* `tokenizer.json`'s `model.vocab` decides ids: it is the fairseq order, 256,204 entries,
  ids 0..256203. The `.model` supplies merge ranks only.
* The `.model` holds 256,000 pieces. All 256,000 appear in `vocab` (`only_spm == 0`, so
  unlike small100 there are no scoreless-at-runtime pieces hiding behind `<unk>`).
* 204 `vocab` entries have no `.model` score: `<pad>` (id 1; SPM models never hold it),
  the 202 flores language codes (`ace_Arab` .. `zul_Latn`, ids 256001..256202) and
  `<mask>` (id 256203). They all get one below the lowest real rank, so a merge only
  ever reaches them when no genuine merge applies.
* Total: 256,204 `vocab` entries (ids 0..256203), but `config.json`'s `vocab_size` is
  256,206 and the checkpoint embedding is 256,206 rows. Ids 256204 and 256205 appear
  in NEITHER file: `madeupword`-style padding slots, same as small100's 8 (here 2).
  They are written as zero-length records so ids stay aligned with embedding rows.

# Normalisation happens in Kotlin

Same as small100: `normalizer_spec` is `nmt_nfkc` with a precompiled charsmap, so the
app calls `java.text.Normalizer.normalize(text, Form.NFKC)` first and
`post::sentencepiece` takes it from there (whitespace collapsing + metaspace prefix).

# The format

Identical SPM1 to `small100_tokenizer.py`:

    "SPM1"                     magic
    count      u32             vocab entries, so an id indexes directly
    count records, in id order:
        score  i32             the negative merge rank, exact as an integer
        length u16             the piece's UTF-8 length
        piece  length bytes

# The language map

`--langs-out` writes `nllb_langs.json`: the 202 flores codes in id order with their
token ids, for app-eng's 202-language picker. Each entry is
`{"flores": "eng_Latn", "id": 256130}`. The app joins its own UI codes (BCP-47 etc.)
to `flores`; the token id is what goes on the wire (source-lang prefix + forced-BOS
target — see `fetch_nllb600.py`'s docstring, shared with rust-eng).

Usage:
    ./scripts/ml/nllb_tokenizer.py --spm sentencepiece.bpe.model \
        --tokenizer tokenizer.json -o out/tokenizer.bin --langs-out out/nllb_langs.json
"""

import argparse
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

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

MAGIC = b"SPM1"

# fairseq's four, which the Rust checks rather than assumes.
SPECIALS = ["<s>", "<pad>", "</s>", "<unk>"]

# `config.json`'s vocab_size, and therefore the embedding width the converter reads.
VOCAB_SIZE = 256_206

# Ids in neither the .model nor tokenizer.json: padding so the table covers every
# embedding row. Same role as small100's 8 madeupwords.
TRAILING_PADS = VOCAB_SIZE - 256_204
LANG_LO, LANG_HI = 256_001, 256_202
MASK_ID = 256_203


def build(spm_path, tokenizer_path):
    """`[(piece, score)]` in id order, plus the lang-code inventory for `--langs-out`."""
    scores = read_spm(spm_path)
    held = json.load(open(tokenizer_path, encoding="utf-8"))
    vocab = held["model"]["vocab"]

    by_id = {}
    for piece, id_ in vocab.items():
        if id_ in by_id:
            raise SystemExit(f"{tokenizer_path}: id {id_} is both {by_id[id_]!r} and {piece!r}")
        by_id[id_] = piece
    if sorted(by_id) != list(range(len(by_id))):
        raise SystemExit(f"{tokenizer_path}: ids are not 0..{len(by_id) - 1}")

    names = [by_id[i] for i in range(4)]
    if names != SPECIALS:
        raise SystemExit(f"ids 0..3 are {names}, not fairseq's specials in fairseq's order")

    added = {a["content"]: a["id"] for a in held.get("added_tokens", [])}
    langs = sorted(
        ((code, id_) for code, id_ in added.items() if LANG_LO <= id_ <= LANG_HI),
        key=lambda pair: pair[1],
    )
    if len(langs) != 202 or [id_ for _, id_ in langs] != list(range(LANG_LO, LANG_HI + 1)):
        raise SystemExit(
            f"{tokenizer_path}: {len(langs)} language tokens in "
            f"{LANG_LO}..{LANG_HI}, not the contiguous 202 flores200 codes"
        )
    if added.get("<mask>") != MASK_ID:
        raise SystemExit(f"{tokenizer_path}: <mask> is {added.get('<mask>')}, not {MASK_ID}")

    # One below the lowest real rank, so these lose every merge they are in.
    floor = min(scores.values()) - 1
    shared = sum(1 for piece in by_id.values() if piece in scores)
    table = [(by_id[id_], scores.get(by_id[id_], floor)) for id_ in range(len(by_id))]
    table += [("", floor)] * TRAILING_PADS
    if len(table) != VOCAB_SIZE:
        raise SystemExit(f"{len(table)} ids against config.json's vocab_size {VOCAB_SIZE}")
    return table, shared, langs


def pack(vocab):
    """The `SPM1` blob for `[(piece, score)]` in id order."""
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
    parser.add_argument("--spm", required=True, help="sentencepiece.bpe.model")
    parser.add_argument("--tokenizer", required=True, help="tokenizer.json, the fairseq id order")
    parser.add_argument("-o", "--out", required=True)
    parser.add_argument("--langs-out", required=True, help="where to write nllb_langs.json")
    args = parser.parse_args()

    vocab, shared, langs = build(args.spm, args.tokenizer)
    blob, count, empty = pack(vocab)
    directory = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(directory, exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(blob)
    print(f"{os.path.basename(args.out)}: {count} pieces ({empty} empty), {len(blob)} bytes, "
          f"{shared} scores from the .model")

    mapping = [{"flores": code, "id": id_} for code, id_ in langs]
    with open(args.langs_out, "w", encoding="utf-8") as f:
        json.dump(mapping, f, indent=2)
        f.write("\n")
    print(f"{os.path.basename(args.langs_out)}: {len(mapping)} flores codes "
          f"{langs[0][1]}..{langs[-1][1]}")


if __name__ == "__main__":
    main()
