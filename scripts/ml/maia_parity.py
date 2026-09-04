#!/usr/bin/env python3
"""Maia3 parity harness: the PyTorch checkpoint vs the converted `.maml` through the Rust.

`onnx_parity.py` does not apply — there is no ONNX export of Maia3 at all — so this follows
`nllb_parity.py`'s precedent of a checkpoint-only harness, but goes further: rather than
comparing a NumPy re-implementation, it drives `reference.rs::dump_reference_output`, so the
thing under test is the **actual Rust plan** running the **actual converted weights**.

Each fixture position goes through both sides and the 4352 move logits are compared:

* the reference `MAIA3Model` in fp32, built from `maia3-5m.pt` with the architecture in
  `maia3/model_registry.py`, encoded by `maia3/dataset.py::tokenize_board`;
* the Rust CPU interpreter running `nets::maia`'s plan over the `.maml`, fed the 12 planes
  this script encodes independently.

# Why this phase is the one that matters

Every remaining risk in this model is a silent one. Square indexing, the mirror-and-swap for
black, the move vocabulary's ordering, the eight-fold attention-bias replication and the
post-norm block order all fail into logits that are finite, plausibly distributed and
completely wrong — there is no shape, no digest and no fidelity gate that catches any of
them. A wrong board encoding still plays legal chess, just badly.

So the comparison is on the assembled 4352-vector, not on an intermediate, and the pass bar
is agreement on the **argmax move** as well as the numbers: a correlation of 0.999 with a
different best move is a failure, and it is the failure mode a subtly wrong encoding
produces.

# The bar

fp16 rounding plus int8 weight quantisation. `scripts/ml/maia_quant_eval.py` is what
established that int8 is safe here at all — it measures move agreement over hundreds of
positions, where this measures the whole logit vector over a handful of chosen ones. The two
answer different questions and both are needed: that one says the quantisation does not change
the model, this one says the runtime computes the same thing the checkpoint does.

What is asserted is the correlation, the relative max deviation against the logit range, and
the argmax over the legal moves.

Needs torch, python-chess and the checkpoint. Run after `fetch_maia.py`:

    python scripts/ml/maia_parity.py \
        --checkpoint ~/.cache/huggingface/.../maia3-5m.pt \
        --maml games/chess/src/main/assets/maia3-5m.maml
"""

import argparse
import json
import math
import os
import subprocess
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FIXTURE = os.path.join(HERE, "maia_parity_fixture.json")

# `maia3/model_registry.py`: BASE_SIZE_CONFIG plus the maia3-5m row of MODEL_SPECS.
CONFIG = {
    "history": 8,
    "use_padding": True,
    "include_time_info": False,
    "dim_emb": 128,
    "num_blocks": 8,
    "mlp_ratio": 2.0,
    "dropout": 0.0,
    "use_gab": True,
    "use_relative_bias": False,
    "use_absolute_pe": False,
    "use_rms_norm": True,
    "omit_qkv_biases": True,
    "activation": "gelu",
    "dim_vit": 256,
    "head_hid_dim": 256,
    "num_heads": 8,
    "gab_gen_size": 64,
    "gab_per_square_dim": 0,
    "gab_intermediate_dim": 64,
}

# `maia3/dataset.py::PIECE_MAP`, as plane indices: white P,N,B,R,Q,K then black P,N,B,R,Q,K.
PIECE_ORDER = "PNBRQK"

# The bound
#
# Set from measurement against the shipped int8 file, not chosen. The weights are int8 with a
# per-channel scale and the Rust interpreter rounds every intermediate store through fp16, so
# the gap against fp32 is dominated by the quantisation rather than by any one operation.
#
# Measured over the fixture: correlation 0.99978 to 0.99995, deviation 0.5% to 1.4% of the
# logit range. The endgames sit at the loose end of both because their logit range is the
# narrowest — a fixed absolute error is a larger fraction of a 5.7-wide spread than of a
# 14.8-wide one — not because anything is worse about them.
#
# These were 0.9999 and 1% when the file was fp16, where the deviation was 0.12% to 0.25%.
# What is asserted alongside them is the argmax over *legal* moves, and that is the assertion
# that actually protects the model: a bound loose enough to admit a real encoding bug would
# still be caught by the move disagreeing.
MIN_CORRELATION = 0.9997

# Max absolute deviation, as a fraction of the reference vector's range.
MAX_RELATIVE_DEVIATION = 0.02


class Spec:
    """The dotted-attribute config object `MAIA3Model` expects."""

    def __init__(self, held):
        self.__dict__.update(held)


def encode_planes(board):
    """The 12 board planes as `[12, 64]` plane-major, mirrored and colour-swapped for black.

    Independent of `maia3.dataset.tokenize_board` on purpose: it is a re-derivation from the
    same rules, so agreement means two readings of the encoding agree rather than one
    reading agreeing with itself. It is also the reference for the Kotlin encoder, which
    cannot import python-chess.
    """
    import chess

    if board.turn == chess.BLACK:
        board = board.mirror()
    planes = np.zeros((12, 64), dtype=np.float32)
    for square in chess.SQUARES:
        piece = board.piece_at(square)
        if piece is None:
            continue
        plane = PIECE_ORDER.index(piece.symbol().upper())
        if piece.color == chess.BLACK:
            plane += 6
        planes[plane][square] = 1.0
    return planes


def reference_logits(model, planes, self_elo, oppo_elo):
    """The checkpoint's own 4352 move logits for a board, in fp32."""
    import torch

    history = CONFIG["history"]
    # `[64, 12]` square-major, repeated across the eight plies. `get_historical_tokens` pads
    # a short history by repeating the earliest position, and with `use_uci_history=False`
    # the deque holds only the current one — so all eight plies are this board.
    squares = torch.from_numpy(planes.T.copy())
    tokens = squares.repeat(1, history)
    # `forward` slices to `12 * history`, so the trailing clk_ponder column is not needed —
    # but `get_historical_tokens` appends one, so include it and let the slice drop it.
    tokens = torch.cat([tokens, torch.zeros(64, 1)], dim=1).unsqueeze(0)
    with torch.no_grad():
        move, _value, _ponder = model(
            tokens,
            torch.tensor([float(self_elo)]),
            torch.tensor([float(oppo_elo)]),
        )
    return move[0].numpy().astype(np.float32)


def move_name(index):
    """The vocabulary entry at `index`, as `maia3/utils.py::get_all_possible_moves` names it."""
    if index < 4096:
        return f"{square_name(index // 64)}{square_name(index % 64)}"
    at = index - 4096
    from_file, rest = divmod(at, 32)
    to_file, piece = divmod(rest, 4)
    return f"{'abcdefgh'[from_file]}7{'abcdefgh'[to_file]}8{'qrbn'[piece]}"


def square_name(square):
    return f"{'abcdefgh'[square % 8]}{square // 8 + 1}"


def legal_mask(board):
    """The vocabulary indices of `board`'s legal moves, mirrored for black as the model sees them.

    `maia3/dataset.py::get_legal_moves_mask`. This is the mask the app applies before it
    samples, so it is the mask the comparison has to apply too: the raw 4352-vector scores
    every from-to pair on the board, including the ~4000 that are not moves at all, and their
    logits are unconstrained. Comparing an unmasked argmax reports a disagreement over two
    illegal moves as a failure.
    """
    import chess

    vocabulary = {move_name(index): index for index in range(4352)}
    indices = []
    for move in board.legal_moves:
        uci = move.uci()
        if board.turn == chess.BLACK:
            uci = mirror_uci(uci)
        at = vocabulary.get(uci)
        if at is not None:
            indices.append(at)
    return np.array(sorted(indices), dtype=np.int64)


def mirror_uci(uci):
    """`maia3/utils.py::mirror_move`: flip both squares' ranks, keep the promotion piece."""
    flip = lambda square: f"{square[0]}{9 - int(square[1])}"  # noqa: E731
    return flip(uci[:2]) + flip(uci[2:4]) + uci[4:]


def build_reference_binary():
    """Compile the Rust test binary once and return its path.

    One `cargo` invocation for the whole fixture rather than one per position. That is eight
    times less process startup, and it also means a tree that is mid-edit somewhere else only
    has to be buildable once, at the start, instead of staying buildable for the length of the
    run.
    """
    import json

    crate = os.path.join(ROOT, "library", "ml", "src", "main", "rust")
    finished = subprocess.run(
        [
            "cargo", "test", "--release", "-p", "modelrunner", "--lib",
            "--no-run", "--message-format=json",
        ],
        check=True,
        cwd=crate,
        capture_output=True,
        text=True,
    )
    for line in finished.stdout.splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("reason") == "compiler-artifact" and message.get("executable"):
            if message.get("target", {}).get("name") == "modelrunner":
                return message["executable"]
    raise SystemExit("cargo did not report a test binary for modelrunner")


def run_reference(binary, work, maml):
    """Drive `dump_reference_output` for whatever is currently in `work/input.f32`.

    The output file is deleted first and its absence is fatal afterwards. That is not
    belt-and-braces: the test is `#[ignore]`d and filtered by name, so a filter that matches
    nothing exits 0 and leaves the *previous* position's output in place — and comparing every
    position against one stale logit vector produces plausible-looking per-position
    disagreements, because the legal-move mask still differs. It cost an afternoon once.
    """
    written = os.path.join(work, "reference.f32")
    if os.path.exists(written):
        os.remove(written)
    environment = dict(
        os.environ,
        PARITY_DIR=work,
        PARITY_GRAPH="maia",
        PARITY_WIDTH="64",
        PARITY_MAML=os.path.abspath(maml),
    )
    subprocess.run(
        [binary, "dump_reference_output", "--ignored", "--nocapture"],
        check=True,
        env=environment,
    )
    if not os.path.exists(written):
        raise SystemExit(
            f"{binary} ran but wrote no reference.f32 — the test filter matched nothing, "
            "or the maia arm of dump_reference_output returned early."
        )
    return np.fromfile(written, dtype=np.float32)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--checkpoint", required=True, help="maia3-5m.pt")
    parser.add_argument("--maml", required=True, help="the converted maia3-5m.maml")
    parser.add_argument("--fixture", default=FIXTURE)
    parser.add_argument("--work", help="keep the intermediates somewhere specific")
    args = parser.parse_args()

    try:
        import chess  # noqa: F401
        import torch
    except ImportError as missing:
        raise SystemExit(f"needs torch and python-chess: {missing}")

    sys.path.insert(0, os.path.join(ROOT, "build", "maia", "src"))
    try:
        from maia3_models import MAIA3Model
    except ImportError:
        raise SystemExit(
            "needs CSSLab/maia3's models.py at build/maia/src/maia3_models.py.\n"
            "Fetch it with:\n"
            "  curl -o build/maia/src/maia3_models.py \\\n"
            "    https://raw.githubusercontent.com/CSSLab/maia3/main/maia3/models.py"
        )

    print("loading the reference model (fp32, CPU)...")
    model = MAIA3Model(Spec(CONFIG))
    state = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
    # The checkpoint was saved from a build that called the attention bias "smolgen"; the
    # module on `main` calls the same parameter `gab`. Renaming rather than loading loosely,
    # so a genuinely missing tensor still fails.
    renamed = {
        name.replace("smolgen_shared_weight", "gab_shared_weight").replace(
            "self_attn.smolgen_weight", "self_attn.gab_weight"
        ): value
        for name, value in state.items()
    }
    model.load_state_dict(renamed, strict=True)
    model.eval()

    fixture = json.load(open(args.fixture, encoding="utf-8"))["positions"]
    # Absolute, because the Rust binary is run from elsewhere.
    work = os.path.abspath(args.work or tempfile.mkdtemp(prefix="maia_parity_"))
    os.makedirs(work, exist_ok=True)
    binary = build_reference_binary()
    print(f"{len(fixture)} positions, intermediates in {work}\n")

    results = []
    for position in fixture:
        import chess

        board = chess.Board(position["fen"])
        planes = encode_planes(board)
        want = reference_logits(model, planes, position["self_elo"], position["oppo_elo"])

        # The Rust side reads the 12 planes then the two elos, and does the history repeat
        # and the elo blend itself — so both are inside the comparison rather than beside it.
        feed = np.concatenate(
            [planes.ravel(), np.array([position["self_elo"], position["oppo_elo"]], np.float32)]
        ).astype(np.float32)
        feed.tofile(os.path.join(work, "input.f32"))

        got = run_reference(binary, work, args.maml)
        if got.size != want.size:
            raise SystemExit(f"{position['name']}: torch has {want.size}, the runtime {got.size}")

        deviation = float(np.abs(want - got).max())
        spread = float(want.max() - want.min())
        relative = deviation / spread
        correlation = float(np.corrcoef(want, got)[0, 1])

        # Masked to the legal moves, which is what the app picks from. The unmasked argmax
        # is meaningless: 4000-odd of the 4352 entries are not moves in this position and
        # their logits are unconstrained, so two runs can disagree about the best illegal
        # move while agreeing on every real one.
        legal = legal_mask(board)
        best_want = int(legal[want[legal].argmax()])
        best_got = int(legal[got[legal].argmax()])
        # How much room the top legal move has. A disagreement under the fp16 deviation is a
        # near-tie, not an encoding bug, and this is what tells the two apart.
        ranked = np.sort(want[legal])[::-1]
        margin = float(ranked[0] - ranked[1]) if ranked.size > 1 else math.inf
        results.append((position, correlation, relative, best_want, best_got, margin, deviation))

        print(f"{position['name']}:")
        print(f"  {position['why']}")
        print(
            f"  corr {correlation:.8f}  max dev {deviation:.5f} "
            f"({relative * 100:.3f}% of a {spread:.2f} range)"
        )
        print(
            f"  best of {legal.size} legal: torch {move_name(best_want)} ({best_want}), "
            f"runtime {move_name(best_got)} ({best_got}), margin {margin:.4f}\n"
        )

    # The black-to-move mirror is checked as a relation between two positions rather than
    # against a number: startpos and startpos-black are the same board from the mover's own
    # side, so they must encode identically and so must produce identical logits.
    named = {p["name"]: rest for p, *rest in results}
    if "startpos" in named and "startpos-black" in named:
        same = named["startpos"][2] == named["startpos-black"][2]
        print(f"the mirror: startpos and startpos-black agree on the best move: {same}")
        if not same:
            raise SystemExit(
                "startpos and startpos-black chose different moves. They are the same "
                "position from the mover's side, so the mirror-and-swap is wrong."
            )

    # The elo input has to reach the model, which the two ends of the range prove.
    if "endgame-elo-floor" in named and "endgame-elo-ceiling" in named:
        differ = named["endgame-elo-floor"][2] != named["endgame-elo-ceiling"][2]
        print(f"the elo blend: floor and ceiling differ on the same board: {differ}")

    failures = []
    for position, correlation, relative, best_want, best_got, margin, deviation in results:
        if correlation < MIN_CORRELATION:
            failures.append(f"{position['name']}: correlation {correlation:.8f}")
        if relative > MAX_RELATIVE_DEVIATION:
            failures.append(f"{position['name']}: max deviation {relative * 100:.3f}% of range")
        if best_want != best_got:
            # A tie inside this position's own deviation is not a disagreement about the
            # model, so it is reported and allowed. A clear preference that flipped is an
            # encoding bug, and the margin is what tells the two apart.
            note = (
                f"{position['name']}: torch plays {move_name(best_want)}, "
                f"the runtime {move_name(best_got)} (margin {margin:.4f} against a "
                f"{deviation:.4f} deviation)"
            )
            if margin > 2.0 * deviation:
                failures.append(note)
            else:
                print(f"  tie inside the runtime's own deviation: {note}")
    if failures:
        raise SystemExit("\nPARITY FAILED\n  " + "\n  ".join(failures))
    print(f"\nPARITY OK over {len(results)} positions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
