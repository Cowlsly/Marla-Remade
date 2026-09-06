#!/usr/bin/env python3
"""Golden for Gemma 4's audio encoder: onnxruntime's answer, with an int4 control.

`examples/check_gemma4_audio_parity.rs` reads what this writes. It is the audio counterpart of
`gemma4_vision_parity.py` and follows it closely; read that one first.

Not to be confused with `gemma4_audio_parity.py`, which is the FRONT END - waveform to log-mel.
This one starts where that one stops. It imports `load_extractor` from it rather than repeating
the importlib bypass, so there is one copy of the trick that gets transformers imported on a
machine where its package `__init__` fails a huggingface-hub version check.

# The control is the point, again

The device runs int4 weights and the export runs fp16 ones, so the two cannot agree closely and
the interesting question is never "how close is it" but "is it as close as four bits allow". On
the vision tower a completely correct encoder read 0.946 against the exact reference and 0.999
against the int4 control, and without the second number the first is unreadable - we nearly
concluded a correct tower was broken.

So this runs the reference twice, and the second run is the bar.

WHAT THE CONTROL QUANTISES IS NOT A GUESS. It is read out of `collect_gemma4_audio` in
`maml_convert.py`, which is the code that actually writes the `.maml`:

    int4  `projection()`  ten linears per layer, twelve layers, 120 total:
                          ffw1.up ffw1.down q k v post_proj lconv.gate lconv.exit
                          ffw2.up ffw2.down
    fp16  everything else the file holds. `maml_convert` writes any tensor that is not int4 or
                          int8 as `DTYPE_F16` (line 3413), so the norms, the clip bounds, the
                          query scales and the twelve relative tables are all fp16 in the `.maml`
                          even though the converter carries them as fp32 numpy on the way through.

That second row is why this INSISTS ON THE fp16 EXPORT. Loading `audio_encoder.onnx` instead of
`audio_encoder_fp16.onnx` would leave every non-int4 weight at fp32 while the device has it
fp16-rounded, and the control would then be a bar the device cannot meet for a reason that has
nothing to do with four bits. `require_fp16` refuses the fp32 export rather than quietly
measuring against it.

A control that quantised the relative tables, or the SSCP convs, or the input projection would
flatter the device against a bar it is not trying to meet. The count is asserted: exactly 120.

# The clips, and what the padding cases turned into

The brief originally asked for a clip with a padded tail, to exercise the -65504 fill and the
bounds guard. `audio-net` then measured that `nets::gemma4_audio` DOES NOT PAD - it feeds F real
frames with the mask all true, where the reference pads to a multiple and masks - and that the
two arms are bit-identical on the export across eight configurations. So there is no padded tail
on the device, the second SSCP mask does no work, and a padded-tail clip would measure a code
path that does not exist.

What survives is the START of the sequence, which is where the fill and the bounds guard actually
live: queries `q < 11` whose band reaches `k = q - 11 + j < 0`. Hence:

    short   T = 4    every query is on the ramp. The case that matters most.
    ragged  T = 29   T % 12 != 0, so the last chunk is partial
    multi   T = 63   several chunks and still not a multiple
    exact   T = 48   a clean multiple, as contrast. It is the WEAK case and is here to show
                     that the others are doing something it does not.

`no_pad_equivalence` additionally re-runs every residue-2 clip through the reference's own
padded-and-masked arm and asserts it matches the unpadded arm the device uses. That turns
`audio-net`'s measurement into a standing check rather than a fact in a chat message.

# The negative control

`break_relative` reverses the offset axis of the twelve relative-position tables and reports what
that does to the output cosine. A harness that has never been shown to fail is not evidence, and
the reversal is the error `maml_convert.py:2405-2411` warns about: shape-preserving, so no plan
builder catches it, and wrong at every position. If a deliberately broken graph still reads above
0.99 the harness says so and tells you not to trust a pass.

# What it measured, and why the control is not optional

Run against `audio_encoder_fp16.onnx`, four bits alone cost this much cosine AT THE OUTPUT:

    tiny    T = 1     0.009283
    short   T = 4     0.001913
    ragged  T = 25    0.016667
    multi   T = 56    0.037371
    exact   T = 48    0.041174

So the int4 penalty GROWS with clip length and reaches four percent on ordinary clips. Against
the exact reference a correct encoder therefore reads anywhere from 0.998 to 0.958 depending only
on how long the audio is, and there is no fixed floor anyone could set. That is the whole reason
for the control: measured against it the bar is clip-independent.

The negative control, on the longest clip, with the reversed tables then quantised exactly as the
device's are:

    against the exact reference   0.936792
    against the int4 control      0.967655   <- the number that decides
    a correct encoder would read  1.000000   against the same control

Read those two columns together. Against the exact reference a BROKEN encoder reads 0.9368 while
a CORRECT one reads 0.9588 on a comparable clip - a gap of two percent, and the two populations
very nearly touch. Against the control the broken one reads 0.9677 against a known 1.0. The
control does not widen the gap much; what it does is make the correct answer a CONSTANT instead
of a function of clip length, which is what lets a floor exist at all.

Be honest about the margin: 0.9677 is not far below a floor of 0.99, and a subtler bug than a
fully reversed table will sit closer still. The per-layer bisect, not the output cosine, is what
localises anything; the output figure is a verdict, not a diagnosis.

# The negative controls, and what they establish

`break_relative` and `swap_qk` perturb the graph deliberately, then quantise and fp16-round it
exactly as the device's weights are, so each is a like-for-like stand-in for a broken device.
Measured on the longest clip:

    perturbation                        vs exact  vs control   enters at
    all 12 relative tables reversed     0.936792    0.967655   layer_in_1
    layer 6's relative table reversed   0.953414    0.987361   layer_in_7
    q and k projections swapped         0.909556    0.911101   layer_in_1

Each names the layer AFTER the fault, which is the right answer in all three. That localisation
is exact, and it is exact only because the criterion is "any departure from 1.0" - see
[`DEPARTURE`], which also explains why that criterion does NOT transfer to the device.

The output column is the warning. A fault confined to one layer reads 0.987 at the output against
the control, while four bits alone cost 0.037 on the same clip - the bug is three times smaller
than the noise it hides in. Against the exact reference it reads 0.9534, BETTER than a correct
encoder scores on a comparable clip. So the output figure is a detector of gross errors only, and
the per-probe profile is what finds anything subtler.

    python scripts/ml/gemma4_audio_encoder_parity.py audio_encoder_fp16.onnx -o audio_golden.json
"""
import argparse
import json
import os
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gemma4_audio_parity import load_extractor  # noqa: E402
from maml_convert import I4_BLOCK, quantise_per_block  # noqa: E402

LAYERS = 12
D_MODEL = 1024
HEADS = 8
HEAD_DIM = 128
REL_OFFSETS = 13
OUT_DIM = 1536
NORMS_PER_LAYER = 9
CHUNK = 12
MAX_SAMPLES = 480_000
PAD_MULTIPLE = 128
INT4_PER_LAYER = 10
# `maml_convert.FP16_LIMIT`: fp16's largest finite value, which the converter substitutes for an
# absent clip bound rather than storing an infinity.
FP16_LIMIT = 65504.0

# Real sample counts, computed rather than picked. `check_clip` re-derives every property and
# fails if it stops holding.
#
# THREE OF THE FIVE SIT AT F % 4 == 2, which is not decoration. The reference's second SSCP mask
# does observable work only when the valid frame count is EVEN, and corrupts a valid soft token
# only when it is 2 (mod 4) - then exactly one, always the last. We pad nothing and mask nothing,
# so that residue class is the only place our shortcut and the reference could diverge. A golden
# without it tests nothing about the no-padding decision. `audio-net`'s first no-padding run
# sampled F in {37, 40, 52, 61, 99, 100}, residues {1, 0, 0, 1, 3, 0}, and missed the hazard
# entirely; my own first four clips were F in {16, 116, 252, 189}, residues {0, 0, 0, 1}, and
# missed it the same way.
CLIPS = [
    ("tiny", 385),      # F 2   T 1    F%4=2. One token: every band position but k=q is < 0.
    ("short", 2_704),   # F 16  T 4    T < 12, so every query is still on the ramp.
    ("ragged", 15_745),  # F 98  T 25   F%4=2, T%12=1.
    ("multi", 35_585),  # F 222 T 56   F%4=2, T%12=8, several chunks.
    ("exact", 30_248),  # F 189 T 48   clean multiple, no hazard. The weak contrast case.
]


# The ONNX initializer shape of each int4 slot, by its offset from `base`. From `audio-net`'s
# worked examples. Asserting these is much stronger than counting 120: a shift in the node
# numbering that preserved the count would still have to preserve this [1024,4096] /
# [4096,1024] / [1024,2048] / [1024,1024] pattern at every one of ten slots in all twelve layers.
SLOT_SHAPES = {
    0: (1024, 4096),   # ffw1.up
    1: (4096, 1024),   # ffw1.down
    2: (1024, 1024),   # q_proj
    3: (1024, 1024),   # k_proj
    4: (1024, 1024),   # v_proj
    6: (1024, 1024),   # post_proj
    7: (1024, 2048),   # lconv.gate
    8: (1024, 1024),   # lconv.exit
    9: (1024, 4096),   # ffw2.up
    10: (4096, 1024),  # ffw2.down
}


# How far from 1.0 counts as a departure, WHEN COMPARING TWO ONNXRUNTIME RUNS.
#
# READ THIS BEFORE REUSING IT AS A DEVICE THRESHOLD. IT IS NOT ONE.
#
# The negative-control profile is control-versus-perturbed-control: both sides are onnxruntime,
# same arithmetic, same order of operations, differing only by the perturbation. Under those
# conditions a correct build reads EXACTLY 1.000000 at every probe upstream of the fault, which
# is what makes "any departure at all" a usable criterion and what makes the localisation exact.
#
# The DEVICE is not that. It is Vulkan fp16 compute, a different implementation with a different
# summation order and an fp16 arena, so device-versus-control will sit below 1.0 at EVERY probe
# even when the device is perfectly correct, and that baseline will grow with depth. Applying
# 1e-6 to it would report "enters at layer_in_0" for a flawless device.
#
# So the device bar has to be MEASURED from a known-good run and cannot be guessed here. Until
# someone has that, the Rust side should print the profile and let its SHAPE speak - a correct
# device diverges smoothly and monotonically, a composition error adds a step on top of that
# trend - rather than render a verdict against a number nobody has justified.
DEPARTURE = 1e-6


def synthetic_pcm(count):
    """A reproducible waveform in -1..1, bit-identical to `synthetic_pcm` in the Rust example.

    Integer hash to i16 and then a divide by 32768, which is exact in binary, so the two sides
    agree to the bit and the golden carries no waveform. A hash rather than a tone because a
    single frequency excites almost none of the 128 mel channels, and a near-rank-one input hides
    exactly the composition errors this is looking for.
    """
    index = np.arange(count, dtype=np.uint64)
    mask = np.uint64(0xFFFFFFFF)
    h = (index * np.uint64(73_856_093)) & mask
    h ^= h >> np.uint64(13)
    h = (h * np.uint64(1_274_126_177)) & mask
    h ^= h >> np.uint64(16)
    return (((h & np.uint64(0xFFFF)).astype(np.int64) - 32768) / 32768.0).astype(np.float32)


def frame_count(n):
    """`logmel::frame_count`, the mel unfold at 321 rather than 320."""
    return (n + 160 - 321) // 160 + 1 if n + 160 >= 321 else 0


def subsample(n):
    return -(-n // 2)


def tokens(frames):
    """`gemma4_audio::tokens`: two stride-2 convolutions, so ceil(ceil(F/2)/2)."""
    return subsample(subsample(frames))


def check_clip(name, real):
    """The clip's shape, and a hard failure if it no longer exercises what it is here for.

    `frames` is what the DEVICE feeds: `frame_count(real)` real rows, no padding. The reference's
    padded row count is carried too, but only so `no_pad_equivalence` can build the other arm.
    """
    padded = min(MAX_SAMPLES, -(-real // PAD_MULTIPLE) * PAD_MULTIPLE)
    frames = frame_count(min(real, MAX_SAMPLES))
    facts = dict(
        name=name, real=real, padded=padded, frames=frames,
        frames_padded=frame_count(padded), tokens=tokens(frames), residue=frames % 4,
    )
    t = facts["tokens"]
    if name == "short":
        if t >= CHUNK:
            raise SystemExit(f"{name}: T={t} no longer sits inside one chunk")
    elif name == "tiny":
        if t != 1:
            raise SystemExit(f"{name}: T={t}, not the single-token case")
    elif name == "exact":
        if t % CHUNK:
            raise SystemExit(f"{name}: T={t} is not the clean multiple this case is for")
    elif t % CHUNK == 0:
        raise SystemExit(f"{name}: T={t} is a clean multiple, which is the weak case")
    if name == "multi" and t < 4 * CHUNK:
        raise SystemExit(f"{name}: T={t} is not several chunks")
    # The seam's predicted victim, from op-designer's and audio-tracer's independent derivations:
    # at F == 2 (mod 4) the smeared SSCP position reaches exactly one valid output, index
    # (F - 2) / 4, and that index is always the LAST valid token. Re-derived here rather than
    # trusted, because it is the token the golden checks specifically.
    if facts["residue"] == 2:
        victim = (frames - 2) // 4
        if victim != t - 1:
            raise SystemExit(
                f"{name}: the seam victim (F-2)/4 = {victim} is not the last token {t - 1}"
            )
        facts["seam_victim"] = victim
    return facts


def mel_for(extractor, facts):
    """`(device_mel, padded_mel, padded_mask)` for a clip, from the shipped extractor.

    `device_mel` is the first `frames` rows only, which is what `nets::gemma4_audio` is fed: real
    audio, no padding, mask all true. The padded pair is the reference's own arm, kept so the two
    can be compared rather than assumed equal.

    Passed as a LIST: handing `__call__` a bare 1-D array trips a squeeze on the batch axis and
    raises for every length but one.
    """
    wave = synthetic_pcm(facts["real"])[:MAX_SAMPLES]
    out = extractor([wave])
    padded_mel = np.asarray(out["input_features"][0], dtype=np.float32)
    padded_mask = np.asarray(out["input_features_mask"][0]).astype(bool)
    if padded_mel.shape[0] != facts["frames_padded"]:
        raise SystemExit(
            f"{facts['real']} samples gave {padded_mel.shape[0]} rows, not {facts['frames_padded']}"
        )
    if int(padded_mask.sum()) != facts["frames"]:
        raise SystemExit(
            f"the reference says {int(padded_mask.sum())} valid rows, frame_count says"
            f" {facts['frames']}"
        )
    return padded_mel[: facts["frames"]], padded_mel, padded_mask


def require_fp16(model, path):
    """Refuse the fp32 export, because the control models a file whose non-int4 tensors are fp16.

    `maml_convert` writes everything that is not int4 or int8 as `DTYPE_F16`, so the norms, clip
    bounds, query scales and relative tables are all fp16 on the device. The fp16 export already
    holds them at that precision, which is why the control does not need to round them itself -
    but only if this really is the fp16 export.
    """
    kinds = {}
    for tensor in model.graph.initializer:
        kinds[tensor.data_type] = kinds.get(tensor.data_type, 0) + 1
    half = kinds.get(onnx.TensorProto.FLOAT16, 0)
    full = kinds.get(onnx.TensorProto.FLOAT, 0)
    if full > half:
        raise SystemExit(
            f"{path} holds {full} fp32 initializers against {half} fp16: this looks like the fp32"
            " export. Use audio_encoder_fp16.onnx - the control assumes every non-int4 tensor is"
            " already at the precision the .maml stores it in."
        )
    return half, full


def int4_linears(model):
    """The initializer names `collect_gemma4_audio` sends through `projection()`, and only those.

    `node_linear` runs 0..134 with a hole at every `base + 5` - the twelve `relative_k_proj`,
    which the exporter folded away - and another at 133, which is the `Add` that applies
    `output_proj`'s bias. Index 0 is the input projection and is emitted at fp16, as are the
    output and embedder projections, so the int4 set is exactly `1 + 11*i + d` for
    `d in {0,1,2,3,4,6,7,8,9,10}`. See `maml_convert.collect_gemma4_audio`.
    """
    numbered = {}
    for node in model.graph.node:
        name = node.name or ""
        if node.op_type not in ("MatMul", "Gemm") or not name.startswith("node_linear"):
            continue
        tail = name[len("node_linear"):]
        if tail == "":
            numbered[0] = node
        elif tail.startswith("_") and tail[1:].isdigit():
            numbered[int(tail[1:])] = node
    wanted = []
    for layer in range(LAYERS):
        base = 1 + 11 * layer
        for step in sorted(SLOT_SHAPES):
            node = numbered.get(base + step)
            if node is None:
                raise SystemExit(f"node_linear_{base + step} is missing; the numbering moved")
            wanted.append((node, SLOT_SHAPES[step], f"layer {layer} slot +{step}"))
    if len(wanted) != LAYERS * INT4_PER_LAYER:
        raise SystemExit(f"{len(wanted)} int4 linears, not {LAYERS * INT4_PER_LAYER}")
    if 1 + 11 * (LAYERS - 1) + 5 in numbered:
        raise SystemExit("node_linear at base+5 exists; relative_k_proj was not folded")
    return wanted


def quantise_in_place(model):
    """Round-trip every int4 linear's weight through the converter's own quantiser. Returns how many.

    The converter transposes the `[in, out]` MatMul initializer to an `[out, in]` kernel before
    quantising, so the blocks run along the contraction axis. Doing it any other way here would
    measure a bar the device is not trying to meet.
    """
    inits = {i.name: i for i in model.graph.initializer}
    touched = 0
    for node, expect, label in int4_linears(model):
        for name in node.input:
            tensor = inits.get(name)
            if tensor is None:
                continue
            original = numpy_helper.to_array(tensor)
            matrix = original.astype(np.float32)
            if matrix.ndim != 2:
                continue
            if matrix.shape != expect:
                raise SystemExit(f"{label} is {matrix.shape}, not {expect}: the numbering moved")
            kernel = matrix.T
            codes, scale = quantise_per_block(kernel)
            # rows = out, taps = in, blocks = ceil(in / I4_BLOCK). Asserted because getting the
            # orientation backwards puts the blocks along the OUTPUT axis, which quantises fine,
            # produces the right shapes, and measures a bar the device is not trying to meet.
            blocks = -(-kernel.shape[1] // I4_BLOCK)
            if scale.shape != (kernel.shape[0], blocks):
                raise SystemExit(
                    f"{label}: scale is {scale.shape}, not {(kernel.shape[0], blocks)} -"
                    " the blocks are not running along the contraction axis"
                )
            flat = kernel.reshape(kernel.shape[0], -1)
            wide = np.repeat(scale, I4_BLOCK, axis=1)[:, : flat.shape[1]]
            back = (np.asarray(codes).astype(np.float32).reshape(flat.shape) * wide)
            back = back.reshape(kernel.shape).T
            # `original.dtype`, not `matrix.dtype`: `matrix` is the fp32 working copy, so using
            # its dtype writes fp32 into an fp16 graph and onnxruntime refuses the model with
            # "Type parameter (T) of Optype (MatMul) bound to different types".
            tensor.CopyFrom(numpy_helper.from_array(back.astype(original.dtype), name))
            touched += 1
    if touched != LAYERS * INT4_PER_LAYER:
        raise SystemExit(f"quantised {touched} weights, not {LAYERS * INT4_PER_LAYER}")
    return touched


def fp16_round(model):
    """Put every non-int4 initializer through fp16, as `maml_convert.build` does. Returns what moved.

    `audio-net`'s correction: the `.maml` has no fp32, so the 109 norm gains, the clip bounds,
    the query scales, the relative tables, the conv kernels and every synthesised bias are all
    fp16 on the device. A control that round-tripped only the int4 linears would leave those at
    whatever the export holds and be slightly optimistic.

    Rather than reason about whether the fp16 export already stores them at that precision, this
    does the rounding unconditionally and reports how many tensors it actually changed. Zero is
    the answer that says the export was already fp16; anything else is a gap the control would
    otherwise have missed. Doing it is cheaper than arguing about it and is correct either way.
    """
    skip = {name for node, _, _ in int4_linears(model) for name in node.input}
    moved, worst = 0, 0.0
    for tensor in model.graph.initializer:
        if tensor.name in skip or tensor.data_type != onnx.TensorProto.FLOAT:
            continue
        values = numpy_helper.to_array(tensor)
        if not np.issubdtype(values.dtype, np.floating):
            continue
        # Clamp before casting, because the converter SUBSTITUTES rather than overflows: an
        # absent clip bound becomes +-FP16_LIMIT (`maml_convert.py`'s `FP16_LIMIT = 65504.0`),
        # and `build()` raises rather than storing an infinity. Three tensors in this export hold
        # values past fp16's range - a blind `.astype(np.float16)` turns them into inf, which is
        # not what the device gets and poisons everything downstream of them.
        clamped = np.clip(values, -FP16_LIMIT, FP16_LIMIT)
        rounded = clamped.astype(np.float16).astype(values.dtype)
        gap = float(np.abs(rounded - values).max()) if values.size else 0.0
        if not np.isfinite(gap):
            raise SystemExit(f"{tensor.name} still overflows fp16 after clamping")
        if gap > 0.0:
            moved += 1
            worst = max(worst, gap)
            tensor.CopyFrom(numpy_helper.from_array(rounded, tensor.name))
    return moved, worst


def break_relative(model, only=None):
    """Reverse the offset axis of the relative tables. A deliberate, realistic, shape-preserving bug.

    The harness has to demonstrate that it can FAIL before its passing means anything - the
    standard `audio-net` set with their arm D, which deleted the mask multiplies and showed the
    comparison caught it. An assertion that has never fired is not evidence.

    This is the error the converter itself warns about at `maml_convert.py:2405-2411`: the table
    is `[heads, head_dim, offsets]` and column `offsets - 1` is displacement zero, so getting the
    column order backwards reads the wrong tap at every position while preserving every shape.
    Shape-preserving on purpose - a bug that changes a shape is caught by the plan builder and
    never reaches a cosine.

    `only` breaks a single layer instead of all twelve, which is the harder and more realistic
    case: most real composition errors are one wrong line in one place, and a localised bug is
    exactly what an output cosine averages away.
    """
    touched, seen = 0, 0
    inits = {i.name: i for i in model.graph.initializer}
    for node in model.graph.node:
        if node.op_type != "MatMul":
            continue
        for name in node.input:
            tensor = inits.get(name)
            if tensor is None or list(tensor.dims) != [HEADS, HEAD_DIM, REL_OFFSETS]:
                continue
            seen += 1
            if only is not None and seen - 1 != only:
                continue
            values = numpy_helper.to_array(tensor)
            tensor.CopyFrom(
                numpy_helper.from_array(np.ascontiguousarray(values[:, :, ::-1]), name)
            )
            touched += 1
    if seen != LAYERS:
        raise SystemExit(f"found {seen} relative tables, not {LAYERS}")
    if touched != (1 if only is not None else LAYERS):
        raise SystemExit(f"reversed {touched} tables, not what was asked")
    return touched


def swap_qk(model):
    """Exchange every layer's q and k projections. The other classic composition error.

    Shapes are identical - both [1024, 1024] - so nothing downstream notices, and the attention
    still produces a plausible distribution. Vision had four errors of exactly this family and
    none of them changed a shape.
    """
    inits = {i.name: i for i in model.graph.initializer}
    slots = int4_linears(model)
    touched = 0
    for layer in range(LAYERS):
        q = slots[layer * INT4_PER_LAYER + 2][0]
        k = slots[layer * INT4_PER_LAYER + 3][0]
        qw = [inits[n] for n in q.input if n in inits]
        kw = [inits[n] for n in k.input if n in inits]
        if len(qw) != 1 or len(kw) != 1:
            raise SystemExit(f"layer {layer}: q has {len(qw)} weights and k has {len(kw)}")
        a, b = numpy_helper.to_array(qw[0]).copy(), numpy_helper.to_array(kw[0]).copy()
        qw[0].CopyFrom(numpy_helper.from_array(b, qw[0].name))
        kw[0].CopyFrom(numpy_helper.from_array(a, kw[0].name))
        touched += 1
    return touched


# Each is applied to a fresh copy of the graph, then quantised and fp16-rounded exactly as the
# device's weights are, so the comparison is like for like. The point is not that each fails -
# it is HOW FAR each falls, because that is the harness's detection power stated as a number
# rather than assumed.
NEGATIVE_CONTROLS = [
    ("all 12 relative tables reversed", lambda m: break_relative(m)),
    ("layer 6's relative table reversed", lambda m: break_relative(m, only=6)),
    ("q and k projections swapped", swap_qk),
]


def trace_points(model):
    """`{label: tensor_name}` for the SSCP output and the input to each of the twelve layers.

    Anchored the way `collect_gemma4_audio` anchors, because that is the reading which has
    already been checked against this graph:

      * there are `9 * LAYERS + 1` SimplifiedLayerNormalization nodes, nine per layer plus the
        embedder's, and layer `i`'s input is the input of `node_mean_{9i}` - its ffw1 pre-norm;
      * `node_mean_{9 * LAYERS}` is the embedder's pre-projection norm, whose input is the
        encoder's output;
      * the bare `node_linear` with no numeric suffix is the input projection, so its non-weight
        input is what the SSCP produced and its output is what enters layer 0.

    The SSCP pair is the point of this function. It is where the seam-smearing mask lives and
    where three time axes differ by factors of two, so separating "the front end is wrong" from
    "a layer is wrong" is most of the value of a bisect here.
    """
    norms, projection, tail = {}, None, None
    for node in model.graph.node:
        if node.op_type == "SimplifiedLayerNormalization":
            name = (node.name or "").replace("_fused_rms_norm/node_mean", "node_mean")
            if name.startswith("node_mean"):
                suffix = name[len("node_mean"):]
                if suffix == "":
                    norms[0] = node.input[0]
                elif suffix.startswith("_") and suffix[1:].isdigit():
                    norms[int(suffix[1:])] = node.input[0]
        elif node.op_type == "MatMul" and node.name == "node_linear":
            projection = node
        elif node.name == "node_linear_133":
            tail = node
    if len(norms) != NORMS_PER_LAYER * LAYERS + 1:
        raise SystemExit(
            f"{len(norms)} norms, not {NORMS_PER_LAYER * LAYERS + 1} - the numbering has changed"
        )
    if projection is None:
        raise SystemExit("no bare node_linear - the input projection is not where it was")
    inits = {i.name for i in model.graph.initializer}
    activation = [i for i in projection.input if i not in inits]
    if len(activation) != 1:
        raise SystemExit(f"the input projection has {len(activation)} activation inputs, not one")
    points = {"sscp_out": activation[0], "layer0_in_projected": projection.output[0]}
    for layer in range(LAYERS):
        points[f"layer_in_{layer}"] = norms[NORMS_PER_LAYER * layer]
    # The tower's own output, 1024 wide, BEFORE output_proj. `encoder_out` below is the input to
    # the embedder's pre-projection norm and is therefore already 1536 wide and past that
    # projection, so without this pair a disagreement in the tail cannot be told from one in the
    # tower. `audio-net` asked whether I wanted it; I did not have it and did.
    #
    # output_proj is the one biased linear, so the exporter split it: node_linear_133 is the Add
    # and the weight belongs to the MatMul feeding it. Followed by edge rather than by name, as
    # `collect_gemma4_audio` does.
    if tail is not None and tail.op_type == "Add":
        produced = {out: n for n in model.graph.node for out in n.output}
        for name in tail.input:
            source = produced.get(name)
            if source is not None and source.op_type == "MatMul":
                upstream = [i for i in source.input if i not in inits]
                if len(upstream) == 1:
                    points["tower_out"] = upstream[0]
    if "tower_out" not in points:
        raise SystemExit("could not follow node_linear_133 back to the tower's output")
    points["encoder_out"] = norms[NORMS_PER_LAYER * LAYERS]
    return points


def graph_inputs(model):
    """`{name: (dims, elem_type)}` so the feed can be built without guessing shapes."""
    out = {}
    for value in model.graph.input:
        shape = value.type.tensor_type.shape
        dims = [d.dim_value if d.dim_value else d.dim_param for d in shape.dim]
        out[value.name] = (dims, value.type.tensor_type.elem_type)
    return out


def collect(model, points, feed):
    """`{label: array}` for every trace point plus the encoder's output, from one session."""
    existing = {o.name for o in model.graph.output}
    for tensor in points.values():
        if tensor not in existing:
            model.graph.output.add().name = tensor
            existing.add(tensor)
    session = ort.InferenceSession(
        model.SerializeToString(), providers=["CPUExecutionProvider"]
    )
    names = [o.name for o in session.get_outputs()]
    raw = dict(zip(names, session.run(names, feed)))
    out = {}
    for label, tensor in points.items():
        out[label] = np.asarray(raw[tensor], dtype=np.float32)
    for name in names:
        if name not in points.values():
            out.setdefault("audio_features", np.asarray(raw[name], dtype=np.float32))
    return out


def cosine(a, b):
    a = np.asarray(a, dtype=np.float64).ravel()
    b = np.asarray(b, dtype=np.float64).ravel()
    return float(a @ b / max(np.linalg.norm(a) * np.linalg.norm(b), 1e-30))


def probe_tokens(facts):
    """Token rows worth carrying: the start ramp, a chunk boundary, the middle, the last.

    0 and 1 are where `k = q - 11 + j` runs off the front of the sequence and an unguarded read
    walks into whatever precedes the K tensor in the arena. 11 is the last query on the ramp and
    12 the first fully-live one, so the pair straddles the boundary the bounds guard defends.
    There is no padded token to probe: the device does not pad.
    """
    total = facts["tokens"]
    wanted = {0, 1, CHUNK - 1, CHUNK, total // 2, total - 1}
    return sorted(t for t in wanted if 0 <= t < total)


def no_pad_equivalence(model, points, unpadded, padded_feed, facts):
    """`audio-net`'s claim, re-measured on the residue class where it could actually fail.

    They compared the padded-and-masked arm against the unpadded one and found them
    bit-identical, which is why the second SSCP mask was cancelled. Their first run sampled six
    frame counts and hit none at `F % 4 == 2`, which is the only residue where the mask does
    observable work; the re-run covered 50 such cases and still found zero difference.

    This re-runs it as a standing check rather than a fact in a chat message. `unpadded` is the
    already-computed device arm, so only the padded arm costs a forward pass.

    Returns `(worst, where, victim_gap)`. `victim_gap` is the difference at the token the seam is
    predicted to corrupt - the last valid one - reported separately because a maximum over 1536
    channels and every trace point is not evidence about a specific position.
    """
    padded = collect(onnx.load_from_string(model.SerializeToString()), points, padded_feed)
    worst, where, victim_gap = 0.0, "", 0.0
    victim = facts.get("seam_victim")
    for label, value in unpadded.items():
        other = padded.get(label)
        if other is None:
            continue
        a = value.reshape(-1, value.shape[-1])
        b = other.reshape(-1, other.shape[-1])[: a.shape[0]]
        if a.shape != b.shape:
            return float("inf"), f"{label} is {a.shape} against {b.shape}", float("inf")
        gap = float(np.abs(a - b).max()) if a.size else 0.0
        if gap > worst:
            worst, where = gap, label
        if victim is not None and victim < a.shape[0]:
            victim_gap = max(victim_gap, float(np.abs(a[victim] - b[victim]).max()))
    return worst, where, victim_gap


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("onnx", help="audio_encoder_fp16.onnx")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument(
        "--no-control",
        action="store_true",
        help="skip the int4 reference, which is most of the runtime and all of the value",
    )
    args = ap.parse_args()

    extractor = load_extractor()
    base = onnx.load(args.onnx)
    half, full = require_fp16(base, args.onnx)
    print(f"{args.onnx}: {half} fp16 initializers, {full} fp32")
    print("graph inputs: " + ", ".join(
        f"{n}{d}" for n, (d, _) in graph_inputs(base).items()
    ))
    points = trace_points(base)
    print(f"tracing {len(points)} points, sscp_out = {points['sscp_out']}")

    golden = {"clips": []}
    for name, real in CLIPS:
        facts = check_clip(name, real)
        device_mel, padded_mel, padded_mask = mel_for(extractor, facts)
        probes = probe_tokens(facts)
        print(
            f"\n{name}: {real} samples -> {facts['frames']} mel rows -> T {facts['tokens']}"
            f", probing {probes}"
        )

        feed = build_feed(base, device_mel, None)
        exact = collect(onnx.load(args.onnx), points, feed)
        entry = dict(facts)
        entry["probe_tokens"] = probes

        if facts["residue"] == 2:
            # Every clip in the hazard residue, not one of them: this is the only place the
            # no-padding shortcut could diverge from the reference, and it is the whole reason
            # those lengths were chosen. Costs one extra forward pass, since the device arm is
            # the `exact` run already computed.
            padded_feed = build_feed(base, padded_mel, padded_mask)
            gap, where, victim_gap = no_pad_equivalence(base, points, exact, padded_feed, facts)
            entry["no_pad_max_abs_diff"] = gap
            entry["no_pad_victim_diff"] = victim_gap
            verdict = "bit-identical" if gap == 0.0 else f"DIFFERS at {where}"
            print(
                f"  padded arm vs unpadded: max abs diff {gap:.9f} -> {verdict};"
                f" at the predicted victim token {facts['seam_victim']}: {victim_gap:.9f}"
            )

        control = None
        if not args.no_control:
            model = onnx.load(args.onnx)
            touched = quantise_in_place(model)
            moved, worst_round = fp16_round(model)
            print(
                f"  {touched} weights through int4; {moved} more tensors moved by fp16 rounding"
                f" (worst {worst_round:.6g})"
            )
            control = collect(model, points, feed)

        # The negative control, on the LONGEST clip because that is where discrimination is
        # hardest: four bits alone cost more cosine as the layers accumulate, so a bug hides
        # best in a long clip.
        #
        # MEASURED AGAINST THE CONTROL, NOT THE EXACT REFERENCE. That distinction is the whole
        # lesson of the vision tower. A broken graph reads 0.9565 against the exact reference and
        # int4 alone reads 0.9626 on this clip - the two OVERLAP, so "cosine against exact" has
        # no discriminating power at all here. The device is judged against the control, so the
        # question the negative control has to answer is what a BUG scores against the control.
        if name == "multi" and control is not None:
            print("  NEGATIVE CONTROLS, each quantised as the device is, on the longest clip:")
            print(
                f"    {'perturbation':<36}{'vs exact':>10}{'vs control':>12}"
                f"   where it enters"
            )
            golden["negative_controls"] = {}
            for label, mutate in NEGATIVE_CONTROLS:
                broken = onnx.load(args.onnx)
                mutate(broken)
                quantise_in_place(broken)
                fp16_round(broken)
                wrong = collect(broken, points, feed)
                out_exact = cosine(wrong["audio_features"], exact["audio_features"])
                out_control = cosine(wrong["audio_features"], control["audio_features"])
                # WHERE IT FIRST DEPARTS, not where it is worst. Error accumulates through the
                # stack, so the minimum cosine is always the LAST trace point and says nothing
                # about origin - my first version reported that and localised every bug to
                # `encoder_out`, including one confined to layer 6. The first point to fall
                # below the floor is the one that names the layer.
                # WHERE IT FIRST DEPARTS FROM 1.0, which is the only statistic that localises.
                # Two earlier versions of this line were wrong and both looked reasonable:
                #
                #   the WORST probe        always the last one, because error accumulates
                #   the BIGGEST STEP       also the last one, because the error GROWS as it goes
                #   first below 0.999      four layers late, because the departure starts at 6e-5
                #
                # A correct build reproduces the control exactly, so every probe upstream of the
                # fault reads 1.000000 and the first one that does not names the layer. Measured:
                # a fault in layer 6 first departs at layer_in_7, by 5.9e-5, with layer_in_0..6
                # all exactly 1.0.
                ordered = [p for p in points if p in wrong and p in control]
                per_point = [(p, cosine(wrong[p], control[p])) for p in ordered]
                entered = next((p for p, c in per_point if 1.0 - c > DEPARTURE), "nowhere")
                worst_point, worst_cosine = min(per_point, key=lambda pair: pair[1])
                golden["negative_controls"][label] = {
                    "vs_exact": out_exact,
                    "vs_control": out_control,
                    "enters_at": entered,
                    "worst_trace_cosine": worst_cosine,
                    "worst_trace_point": worst_point,
                    "profile": {p: c for p, c in per_point},
                }
                print(
                    f"    {label:<36}{out_exact:>10.6f}{out_control:>12.6f}"
                    f"   enters at {entered}"
                )
                if out_control > 0.99 and entered == "nowhere":
                    print(
                        f"    WARNING: '{label}' is invisible to BOTH the output and the bisect."
                        " This harness cannot detect it."
                    )
                elif out_control > 0.99:
                    print(
                        f"    NOTE: '{label}' reads {out_control:.6f} at the output, which is"
                        f" above a 0.99 floor. Only the bisect catches it, at {entered}."
                    )

        print(f"  {'tensor':<22}{'rows':>7}{'rms':>11}{'int4 vs exact':>16}")
        for label in list(points) + ["audio_features"]:
            value = exact.get(label)
            if value is None:
                continue
            flat = value.reshape(-1, value.shape[-1])
            rows = [p for p in probes if p < flat.shape[0]]
            entry[label] = flat[rows].reshape(-1).tolist()
            entry[f"{label}_rows"] = rows
            note = ""
            if control is not None and label in control:
                other = control[label].reshape(-1, control[label].shape[-1])
                entry[f"{label}_int4"] = other[rows].reshape(-1).tolist()
                note = f"{cosine(other, value):>16.6f}"
            print(f"  {label:<22}{flat.shape[0]:>7}{float(np.sqrt((value ** 2).mean())):>11.4f}{note}")
        if control is not None and "audio_features" in control:
            bar = cosine(control["audio_features"], exact["audio_features"])
            entry["output_int4_cosine"] = bar
            print(f"  four bits alone cost {1 - bar:.6f} of cosine at the OUTPUT.")
        golden["clips"].append(entry)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(golden, f)
    print(f"\nwrote {args.out}")


def build_feed(model, mel, mask):
    """The graph's inputs, filled from a mel block and its row mask.

    Built by inspecting `model.graph.input` rather than by hardcoding names, since a re-export
    renames freely. Pass `mask=None` for the device's arm, which is all-true over every row it
    supplies; pass the reference's mask with its padded mel for the other arm.
    """
    feed = {}
    for name, (dims, elem_type) in graph_inputs(model).items():
        rank = len(dims)
        if "mask" in name.lower():
            dtype = onnx.helper.tensor_dtype_to_np_dtype(elem_type)
            rows = np.ones(mel.shape[0], dtype=bool) if mask is None else mask[: mel.shape[0]]
            value = rows.astype(dtype)
            feed[name] = value.reshape((1,) + value.shape + (1,) * (rank - 2))
        else:
            feed[name] = mel.reshape((1,) + mel.shape).astype(np.float32)
    if not feed:
        raise SystemExit("the graph declares no inputs")
    return feed


if __name__ == "__main__":
    main()
