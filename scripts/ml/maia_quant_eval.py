#!/usr/bin/env python3
"""How far can Maia3-5M be quantised? Measured on move agreement, not on cosine.

Phase 8 of the Maia port, generalised to answer int4 as well as int8.

`maml_convert.Fidelity` gates each tensor at cosine 0.999 against its fp32 self, but a cosine
gate says nothing about whether the model still picks the same move, which is the only thing
this model is for. A weight tensor can correlate at 0.99999 and still flip the top of a 40-way
distribution whose top two moves are 0.02 apart, and the endgame positions in
`maia_parity_fixture.json` have margins exactly that small.

So this quantises the kernels `collect_maia` emits, dequantises them, loads them back into the
reference model, and compares **the move it plays** against fp32 over a few hundred real
positions.

# The schemes

`--bits 8` is what this runtime already implements: symmetric absmax, one scale per output
channel, no zero point, scale rounded to fp16. `maml_convert.quantise_per_channel` verbatim.

`--bits 4` is the same arithmetic at 7 levels instead of 127, and it is only interesting with
`--group`: a whole output row sharing one scale has to represent 352 weights with 15 codes,
which is not enough. Sub-row groups are what llama.cpp's Q4_0 does, and they cost one extra
fp16 scale per group. **Nothing in `:library:ml` can read either grouped or 4-bit weights
today** — this measures whether it would be worth building, not something that exists.

# What is not measured

Device fp16 rounding of the *activations*. Both sides here are fp32 activations, so this
isolates weight quantisation. `maia_parity.py` measures the rest, and it is the harness that
would have to be re-run against a real quantised `.maml` before shipping one.

Run:

    python scripts/ml/maia_quant_eval.py --checkpoint .../maia3-5m.pt --bits 8
    python scripts/ml/maia_quant_eval.py --checkpoint .../maia3-5m.pt --bits 4 --group 32
"""

import argparse
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import maml_convert  # noqa: E402
from maia_parity import CONFIG, Spec, encode_planes, legal_mask, move_name  # noqa: E402

# The parameter-name suffixes that become `1x1` convolution kernels in the `.maml`, and so the
# ones a quantising collector would touch. `in_proj_weight` is fused q/k/v and is handled
# separately: the file holds three kernels, so it gets three independent scale vectors.
KERNELS = (
    "token_projection.weight",
    "linear1.weight",
    "linear2.weight",
    "sm2.weight",
    "sm3.weight",
    "out_proj.weight",
    "proj_sq_from.weight",
    "proj_sq_to.weight",
    "promo_bias_proj.weight",
)


def quantise(weight, bits, group):
    """`weight` round-tripped through `bits`-bit symmetric absmax with `group`-wide scales.

    At `bits=8` and `group=0` this is `maml_convert.quantise_per_channel` reproduced: absmax
    over the whole output row, `absmax / 127`, round half away from zero, scale stored fp16.
    The reproduction is asserted against the real function in [`check_against_converter`], so
    the two cannot drift.

    A `group` splits each output row into spans that carry their own scale. That is the only
    thing that makes 4 bits usable — 15 codes across a 352-wide row throws away most of the
    resolution — and it is what the runtime would have to learn to read.
    """
    limit = float(2 ** (bits - 1) - 1)
    rows = np.ascontiguousarray(weight, np.float32).reshape(weight.shape[0], -1)
    width = rows.shape[1]
    span = width if group in (0, None) else min(group, width)

    out = np.empty_like(rows)
    scales = 0
    for start in range(0, width, span):
        block = rows[:, start:start + span]
        absmax = np.abs(block).max(axis=1)
        scale = np.where(absmax > 0, absmax / limit, 1.0).astype(np.float32)
        # Round half away from zero, as `np.round` does not: it rounds half to even, which
        # biases a symmetric weight distribution towards the even codes.
        codes = np.floor(np.abs(block) / scale[:, None] + 0.5) * np.sign(block)
        codes = np.clip(codes, -limit, limit)
        # The scale is stored fp16 beside the weights, so the reconstruction a shader performs
        # uses the *rounded* scale. Rounding it here means the error measured is the error the
        # device would have, not an optimistic one.
        scale = scale.astype(np.float16).astype(np.float32)
        out[:, start:start + span] = codes * scale[:, None]
        scales += rows.shape[0]
    return out.reshape(weight.shape), scales


def check_against_converter():
    """Fail unless `quantise(.., 8, 0)` is `maml_convert.quantise_per_channel` exactly.

    The whole comparison rests on this arm being the shipped quantiser rather than a plausible
    reimplementation of it, so it is checked rather than commented.
    """
    rng = np.random.default_rng(3)
    for shape in [(16, 32), (7, 5), (64, 1)]:
        weight = rng.standard_normal(shape).astype(np.float32)
        codes, scale = maml_convert.quantise_per_channel(weight)
        want = codes.astype(np.float32) * scale[:, None]
        got, _ = quantise(weight, 8, 0)
        if not np.allclose(want, got, atol=0, rtol=0):
            raise SystemExit(
                f"the 8-bit arm has drifted from maml_convert.quantise_per_channel at {shape}"
            )


def quantise_state(state, bits, group):
    """A copy of `state` with every convolution kernel round-tripped, and the scale count."""
    import torch

    out = {}
    scales = 0
    for name, value in state.items():
        array = value.numpy()
        if name.endswith("in_proj_weight"):
            # Split, quantise each third, rejoin: the file holds three kernels with three
            # independent scale vectors, so quantising the fused tensor would share a scale
            # across q, k and v and understate the resolution quantisation actually gets.
            third = array.shape[0] // 3
            parts = []
            for index in range(3):
                block, count = quantise(array[index * third:(index + 1) * third], bits, group)
                parts.append(block)
                scales += count
            out[name] = torch.from_numpy(np.concatenate(parts, axis=0))
        elif name.endswith("smolgen_shared_weight") or name.endswith("smolgen_weight"):
            # One kernel in the file, replicated eight times, so the eight aliases all get the
            # same quantisation rather than eight independent ones.
            block, count = quantise(array, bits, group)
            out[name] = torch.from_numpy(block)
            if name.endswith("smolgen_shared_weight"):
                # Counted once here and multiplied by the replication in `file_sizes`.
                scales += count
        elif any(name.endswith(suffix) for suffix in KERNELS):
            block, count = quantise(array, bits, group)
            out[name] = torch.from_numpy(block)
            scales += count
        else:
            out[name] = value
    return out, scales


def file_sizes(checkpoint, bits, group):
    """`(fp16_bytes, quantised_bytes, kernels)` for the `.maml` a converter would write.

    Measured off `collect_maia`'s own output rather than off the checkpoint, because the two
    differ: the attention-bias weight is one parameter in the checkpoint and eight replicated
    copies in the file, so it carries eight times its checkpoint weight in the saving. Deriving
    it from the checkpoint is how the estimate goes wrong.
    """
    import torch
    from safetensors.torch import save_file

    repack = os.path.join(ROOT, "build", "maia", "_quant_eval.safetensors")
    if not os.path.exists(repack):
        os.makedirs(os.path.dirname(repack), exist_ok=True)
        state = torch.load(checkpoint, map_location="cpu", weights_only=True)
        aliased = [n for n in state if n.endswith("self_attn.smolgen_weight")]
        save_file({k: v.clone() for k, v in state.items() if k not in aliased}, repack)

    get, _ = maml_convert.open_checkpoint(repack)
    layers, tensors = maml_convert.collect_maia(get, maml_convert.CHECKPOINTS["maia"])

    quantised_elems = 0
    fp16_elems = 0
    scale_elems = 0
    kernels = 0
    for layer in layers:
        for at in range(layer.tensor_count):
            array = np.asarray(tensors[layer.first_tensor + at])
            # A kernel is the rank-4 first tensor of a projection; everything else stays fp16.
            if layer.op in ("Linear", "Smolgen") and at == 0 and array.ndim == 4:
                quantised_elems += array.size
                width = array.size // array.shape[0]
                spans = 1 if group in (0, None) else -(-width // min(group, width))
                scale_elems += array.shape[0] * spans
                kernels += 1
            else:
                fp16_elems += array.size
    fp16_bytes = (quantised_elems + fp16_elems) * 2
    # 4-bit weights pack two to a byte; a scale is fp16 either way.
    per_weight = 1.0 if bits == 8 else bits / 8.0
    packed = int(np.ceil(quantised_elems * per_weight))
    return fp16_bytes, packed + (fp16_elems + scale_elems) * 2, kernels


def positions(count, seed):
    """`count` positions reached by random legal play, so they are real and varied.

    Random rather than fixture: eight hand-picked positions cannot measure an agreement rate,
    and what matters is how often quantisation changes the answer across the distribution of
    positions a game actually visits — including the many where the top two moves are close.
    """
    import chess

    rng = np.random.default_rng(seed)
    out = []
    while len(out) < count:
        board = chess.Board()
        # A whole game of random play, sampling along the way, so openings, middlegames and
        # endgames all appear in roughly the proportion a game spends in them.
        for _ in range(rng.integers(20, 120)):
            legal = list(board.legal_moves)
            if not legal:
                break
            board.push(legal[rng.integers(len(legal))])
            if board.is_game_over():
                break
            out.append(board.copy())
            if len(out) >= count:
                break
    return out[:count]


def best_legal(model, board, elo, torch):
    """`(index, margin)` for the model's top legal move, and its lead over the runner-up."""
    planes = encode_planes(board)
    squares = torch.from_numpy(planes.T.copy())
    tokens = torch.cat([squares.repeat(1, CONFIG["history"]), torch.zeros(64, 1)], dim=1)
    with torch.no_grad():
        move, _value, _ponder = model(
            tokens.unsqueeze(0), torch.tensor([float(elo)]), torch.tensor([float(elo)])
        )
    logits = move[0].numpy()
    legal = legal_mask(board)
    if legal.size == 0:
        return None, 0.0
    ranked = np.sort(logits[legal])[::-1]
    margin = float(ranked[0] - ranked[1]) if ranked.size > 1 else float("inf")
    return int(legal[logits[legal].argmax()]), margin


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--checkpoint", required=True, help="maia3-5m.pt")
    parser.add_argument("--bits", type=int, default=8, choices=[2, 3, 4, 5, 6, 8])
    parser.add_argument(
        "--group",
        type=int,
        default=0,
        help="weights per scale within an output row; 0 means one scale for the whole row",
    )
    parser.add_argument("--positions", type=int, default=400)
    parser.add_argument("--elo", type=int, default=1500)
    parser.add_argument("--seed", type=int, default=11)
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
        raise SystemExit("needs build/maia/src/maia3_models.py; see maia_parity.py's header")

    check_against_converter()

    state = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
    rename = lambda held: {  # noqa: E731
        n.replace("smolgen_shared_weight", "gab_shared_weight").replace(
            "self_attn.smolgen_weight", "self_attn.gab_weight"
        ): v
        for n, v in held.items()
    }

    reference = MAIA3Model(Spec(CONFIG))
    reference.load_state_dict(rename(state), strict=True)
    reference.eval()

    low_state, _ = quantise_state(state, args.bits, args.group)
    low = MAIA3Model(Spec(CONFIG))
    low.load_state_dict(rename(low_state), strict=True)
    low.eval()

    grouping = "per output channel" if not args.group else f"per {args.group} weights"
    fp16_bytes, packed_bytes, kernels = file_sizes(args.checkpoint, args.bits, args.group)
    print(f"int{args.bits}, {grouping}, over {kernels} kernels")
    print(
        f"file: {fp16_bytes / (1 << 20):.1f} MiB fp16 -> {packed_bytes / (1 << 20):.1f} MiB "
        f"({100 * (fp16_bytes - packed_bytes) / fp16_bytes:.0f}% saved)"
    )

    # The same gate conversion applies, so a scheme that cannot pass it is reported as such
    # rather than only being judged on moves.
    worst = 1.0
    worst_name = ""
    for name, value in state.items():
        if not (name.endswith(KERNELS) or name.endswith("in_proj_weight")):
            continue
        if name.endswith("self_attn.smolgen_weight"):
            continue
        flat = value.numpy().reshape(value.shape[0], -1).ravel()
        back = low_state[name].numpy().reshape(value.shape[0], -1).ravel()
        cosine = float(np.dot(flat, back) / max(np.linalg.norm(flat) * np.linalg.norm(back), 1e-30))
        if cosine < worst:
            worst, worst_name = cosine, name
    verdict = "passes" if worst >= maml_convert.MIN_INT8_COSINE else "FAILS"
    print(
        f"worst per-tensor cosine {worst:.6f} ({worst_name}) "
        f"\u2014 {verdict} the {maml_convert.MIN_INT8_COSINE} gate"
    )

    boards = positions(args.positions, args.seed)
    print(f"\n{len(boards)} positions from random legal play, at elo {args.elo}\n")

    agreed = 0
    disagreements = []
    for board in boards:
        want, margin = best_legal(reference, board, args.elo, torch)
        got, _ = best_legal(low, board, args.elo, torch)
        if want is None:
            continue
        if want == got:
            agreed += 1
        else:
            disagreements.append((margin, board.fen(), move_name(want), move_name(got)))

    rate = 100.0 * agreed / len(boards)
    print(f"move agreement: {agreed}/{len(boards)} = {rate:.1f}%")
    if disagreements:
        disagreements.sort()
        print("\nwhere they differ, closest margin first:")
        for margin, fen, want, got in disagreements[:8]:
            print(f"  margin {margin:.4f}  fp32 {want}  quantised {got}")
            print(f"    {fen}")
        # 0.1 is comfortably outside the deviation fp16 rounding alone produces, which
        # `maia_parity.py` measures at 0.011 to 0.018. A disagreement wider than that is the
        # quantisation changing the model's mind, not landing a coin flip the other way.
        wide = [d for d in disagreements if d[0] > 0.1]
        print(
            f"\n{len(disagreements)} disagreements, {len(wide)} of them on a margin over 0.1 "
            "(i.e. wider than fp16 rounding alone)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
