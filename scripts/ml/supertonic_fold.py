#!/usr/bin/env python3
"""Supertonic 3's vocoder ONNX to `.maml`, folding its three per-channel affines away.

`nets::supertonic_vocoder` is a hardcoded forward pass of convolutions, layer norms, GELUs and
residual adds — and nothing else. The export has three per-channel affines that are not any of
those, and rather than adding an op for each, all three fold into a convolution:

    input   Mul by [24] then Add by [24]      -> folds into `embed`
    block   Mul by a [512] layer scale        -> folds into that block's `pwconv2`
    tail    BatchNormalization over [512]     -> folds into `head/layer1`

# Why the fold is legal here and not in ppocr_fold.py

`conv(a x + b)` is `conv_{aW}(x) + sum(W b)` at an interior position, and the constant term is a
per-output-channel bias. At a *border* it depends on what the padding supplies. With zero padding
the pad contributes `0` rather than `b`, the fold is wrong at the edge, and
`ppocr_fold.fold_affine_into_convs` refuses padded convolutions for exactly that reason — it was
a real bug in a shipped asset.

Every convolution in this vocoder is preceded by an ONNX `Pad` with **`mode=edge`**. A replicated
border supplies `a x_border + b`, which is what the fold assumes, so it holds everywhere. The
`pwconv2` case is unconditional anyway: a 1x1 has no border.

# Two smaller shape fixes

* `head/act` is a `PRelu` with a single shared slope. The runtime's `Act::PRelu` reads one slope
  per channel, so the scalar is widened to 2048 copies. 4 KB, and it keeps the shader branchless.
* `head/layer2` has no bias in the export. A zero one is synthesised, as `maml_convert` already
  does for `vits_dec`.

Usage:
    ./scripts/ml/supertonic_fold.py vocoder.onnx --graph supertonic_voc -o vocoder.maml
"""

import argparse
import hashlib
import os
import sys

import numpy as np
import onnx
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maml_convert  # noqa: E402  (needs the path above)

# The vocoder's stack width, and the widening inside each block.
CHANNELS = 512
INNER = CHANNELS * 4


def constants(graph):
    """Every initializer and `Constant` node output, by name."""
    held = {i.name: numpy_helper.to_array(i) for i in graph.initializer}
    for node in graph.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    held[node.output[0]] = numpy_helper.to_array(attribute.t)
    return held


def edge_padded(graph):
    """Whether every `Pad` in the graph replicates its border.

    Checked rather than assumed: the folds below are only valid for `edge`, and a future export
    that switched to `constant` would otherwise fold silently and wrongly.
    """
    modes = set()
    for node in graph.node:
        if node.op_type != "Pad":
            continue
        mode = "constant"
        for attribute in node.attribute:
            if attribute.name == "mode":
                mode = attribute.s.decode()
        modes.add(mode)
    return modes == {"edge"}


def fold_into(weight, bias, scale, shift):
    """`conv(scale * x + shift)` as a convolution on `x`.

    `weight` is `[out, in, k]`, `scale` and `shift` are per **input** channel. The scale rides on
    the weight; the shift becomes a constant per output channel, which is where the `k` sum
    comes from.
    """
    scaled = weight * scale.reshape(1, -1, 1)
    added = (weight * shift.reshape(1, -1, 1)).sum(axis=(1, 2))
    return scaled, bias + added


def fold_output(weight, bias, scale, shift):
    """`scale * conv(x) + shift` as a convolution, for a per-**output**-channel affine."""
    return weight * scale.reshape(-1, 1, 1), bias * scale + shift


def batchnorm_affine(node, held, epsilon=1e-5):
    """A `BatchNormalization`'s inference-time per-channel scale and shift."""
    gamma, beta, mean, variance = (held[name] for name in node.input[1:5])
    for attribute in node.attribute:
        if attribute.name == "epsilon":
            epsilon = attribute.f
    scale = gamma / np.sqrt(variance + epsilon)
    return scale, beta - mean * scale


def collect(graph):
    """The vocoder's layers and tensors, folded, in the order the Rust reads them."""
    held = constants(graph)
    if not edge_padded(graph):
        raise SystemExit(
            "this export does not pad every convolution with mode=edge, so the affine folds "
            "below are not valid; see the module docstring"
        )

    # The input affine, which precedes `embed`.
    input_scale = input_shift = None
    for node in graph.node:
        if node.op_type in ("Mul", "Add"):
            sized = [i for i in node.input if i in held and held[i].size == 24]
            if not sized:
                continue
            value = np.asarray(held[sized[0]], dtype=np.float64).reshape(-1)
            if node.op_type == "Mul" and input_scale is None:
                input_scale = value
            elif node.op_type == "Add" and input_shift is None:
                input_shift = value
    if input_scale is None or input_shift is None:
        raise SystemExit("no [24] input affine found ahead of embed")

    # And a scalar divisor at the very head - `normalizer.scale` in tts.json. Folded into the
    # per-channel scale rather than left for the runtime: dividing by it is multiplying the
    # affine''s slope, and the shift is applied after the division so it is untouched.
    divisor = None
    for node in graph.node:
        if node.op_type == "Div":
            scalars = [i for i in node.input if i in held and np.size(held[i]) == 1]
            if scalars:
                divisor = float(np.asarray(held[scalars[0]]).reshape(-1)[0])
                break
    if divisor is None or divisor == 0.0:
        raise SystemExit("no scalar Div found at the head of the graph")
    input_scale = input_scale / divisor

    # Each block's layer scale, and the tail's BatchNormalization.
    layer_scales = []
    for node in graph.node:
        if node.op_type == "Mul" and "convnext" in node.name:
            sized = [i for i in node.input if i in held and held[i].size == CHANNELS]
            if sized:
                layer_scales.append(np.asarray(held[sized[0]], dtype=np.float64).reshape(-1))
    norm = next((n for n in graph.node if n.op_type == "BatchNormalization"), None)
    if norm is None:
        raise SystemExit("no BatchNormalization found ahead of head/layer1")
    tail_scale, tail_shift = batchnorm_affine(norm, held)

    layers, tensors = [], []
    scales = iter(layer_scales)

    def add(op, name, weight, bias, key):
        first = len(tensors)
        tensors.append(np.ascontiguousarray(weight, dtype=np.float32))
        tensors.append(np.ascontiguousarray(bias, dtype=np.float32))
        layers.append(maml_convert.Layer(len(layers), op, name, key, first, 2))

    for node in graph.node:
        if node.op_type == "LayerNormalization":
            # Each block's norm sits between its depthwise and its `pwconv1`, so walking the
            # graph in order puts its pair in exactly the right place in the table.
            gamma = np.asarray(held[node.input[1]], dtype=np.float64)
            beta = np.asarray(held[node.input[2]], dtype=np.float64)
            add(
                "LayerNorm",
                node.name,
                gamma,
                beta,
                f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}",
            )
            continue
        if node.op_type != "Conv":
            continue
        weight = np.asarray(held[node.input[1]], dtype=np.float64)
        if len(node.input) > 2:
            bias = np.asarray(held[node.input[2]], dtype=np.float64)
        else:
            # `head/layer2` has none; the Rust reads a pair for every layer.
            bias = np.zeros(weight.shape[0], dtype=np.float64)
        note = ""

        if "embed" in node.name:
            weight, bias = fold_into(weight, bias, input_scale, input_shift)
            note = " folded=input_affine"
        elif "pwconv2" in node.name:
            weight, bias = fold_output(weight, bias, next(scales), 0.0)
            note = " folded=layer_scale"
        elif "head/layer1" in node.name:
            weight, bias = fold_into(weight, bias, tail_scale, tail_shift)
            note = " folded=final_norm"
        elif len(node.input) == 2:
            note = " b0=synthesised"

        lifted, _ = maml_convert.lift_to_2d(weight, node)
        group = 1
        for attribute in node.attribute:
            if attribute.name == "group":
                group = attribute.i
        key = (
            f"Conv w={list(lifted.shape)} b={list(bias.shape)} g={group}"
            f" pad=edge{note}"
        )
        add("Conv", node.name, lifted, bias, key)

        # The PReLU sits between the two head convolutions, and its slope is one tensor.
        if "head/layer1" in node.name:
            prelu = next(n for n in graph.node if n.op_type == "PRelu")
            slope = np.asarray(held[prelu.input[1]], dtype=np.float64).reshape(-1)
            if slope.size != 1:
                raise SystemExit(f"{prelu.name}: a {slope.size}-element slope, expected one")
            widened = np.full((INNER, 1, 1), float(slope[0]), dtype=np.float32)
            first = len(tensors)
            tensors.append(widened)
            layers.append(
                maml_convert.Layer(
                    len(layers),
                    "PRelu",
                    prelu.name,
                    f"PRelu s={list(widened.shape)} shared={float(slope[0]):.6f}",
                    first,
                    1,
                )
            )

    remaining = list(scales)
    if remaining:
        raise SystemExit(f"{len(remaining)} layer scales were never folded into a pwconv2")
    return layers, tensors


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("onnx")
    parser.add_argument("--graph", required=True, choices=sorted(maml_convert.GRAPHS))
    parser.add_argument("-o", "--out")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    raw = open(args.onnx, "rb").read()
    onnx_sha256 = hashlib.sha256(raw).digest()
    model = onnx.load(args.onnx)
    layers, tensors = collect(model.graph)

    if args.print_layers:
        for layer in layers:
            print(f"{layer.index:4d} t{layer.first_tensor:<4d} {layer.key()}  # {layer.name}")
    digest = maml_convert.layer_table_digest(layers)
    if args.print_digest:
        print(f'    "{args.graph}": "{digest}",')
    pinned = maml_convert.EXPECTED_DIGEST.get(args.graph, "")
    if digest != pinned and not args.print_digest:
        raise SystemExit(
            f"layer table digest {digest}\n           pinned  {pinned}\n"
            "The folded layer table changed, so the hardcoded forward pass no longer\n"
            "matches this export. Re-read it, update the Rust, then re-pin with\n"
            "--print-digest."
        )

    blob, count = maml_convert.build(
        layers, tensors, maml_convert.GRAPHS[args.graph], onnx_sha256
    )
    if args.out:
        with open(args.out, "wb") as f:
            f.write(blob)
    print(
        f"{args.graph}: {count} folded layers, {len(tensors)} tensors, "
        f"{len(blob)} bytes ({len(raw)} fp32 in)"
    )


if __name__ == "__main__":
    main()
