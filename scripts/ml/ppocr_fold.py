#!/usr/bin/env python3
"""Fold a PP-OCRv5 `paddle2onnx` export into `.vkml`, ahead of time.

PP-OCRv5's official ONNX (Apache-2.0, `PaddlePaddle/PP-OCRv5_*_onnx`) is PP-HGNetV2 as
`paddle2onnx` spells it, which is not a form `:library:ml` can lower. Every convolution
arrives as a chain:

    Conv (no bias)
      -> Add(bias, shape [1, C, 1, 1])
      -> Mul(scalar)  -> Add(scalar)            a "learnable affine block"
      -> HardSigmoid(alpha=1/6) -> Mul(self)    a HardSwish, spelled out
      -> Mul(scalar)  -> Add(scalar)            another affine block

with 192 `Identity` nodes threaded through it. In the detection export that is 62
convolutions, 117 `Add`s, 86 `Mul`s and 34 `HardSigmoid`s, of which only about a dozen
`Add`/`Mul` pairs are real ops \u2014 the feature-pyramid residuals and the squeeze-excite
gates. Everything else is constant.

# Why this is a separate script

`vkml_convert.py`'s contract is that a `.vkml` is the ONNX's tensors *verbatim*, so the
Rust can index them positionally and nothing has to agree about a rewrite. Teaching it to
constant-fold a graph would make that contract "verbatim, except for these seven
rewrites", and the rewrites would then run on every model it converts.

So the fold happens here instead, once, ahead of time. The output is a `.vkml` whose
tensors are the folded weights and biases, and the input is the official export with its
SHA-256 recorded in the header exactly as before. Provenance is unchanged; only the place
the arithmetic happens moves.

# What is folded, and why it is exact

At inference every one of these is an affine map on a convolution's output, and an affine
map composed with a convolution is a convolution:

* `Add(x, bias)` becomes the bias the shaders already add.
* `Mul(x, s)` for a scalar or per-channel `s` scales the weight and the bias.
* `Add(x, t)` adds to the bias.
* `BatchNormalization` is `gamma * (x - mean) / sqrt(var + eps) + beta`, so it is the
  same shape of thing.
* `HardSigmoid(alpha=1/6, beta=0.5)` followed by `Mul` against its own input is the
  definition of `HardSwish`, which the runtime fuses into the convolution's store.

None of that is an approximation. The one thing deliberately **not** folded is an affine
block that sits *after* an activation: pushing it through the next convolution needs
`b' += t * sum(W)` over each output row, which is only valid when the tensor feeds
convolutions and nothing else. Rather than depend on that, those are emitted as an
`Affine` op the runtime runs as an elementwise pass. There are a couple of dozen, and
detection runs once per OCR request rather than per frame.

    ./scripts/ml/ppocr_fold.py det.onnx --graph ppocr_det -o out.vkml
    ./scripts/ml/ppocr_fold.py det.onnx --graph ppocr_det --print-graph
"""

import argparse
import hashlib
import os
import sys

import numpy as np
import onnx
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vkml_convert  # noqa: E402  (needs the path above)

# ONNX `HardSigmoid` defaults to alpha 0.2; Paddle's hard-swish uses 1/6. Both appear in
# these graphs, so the value is read per node and checked rather than assumed.
HARDSWISH_ALPHA = 1.0 / 6.0
TOLERANCE = 1e-4


class Op:
    """One op in the normalised graph, as the Rust forward pass will see it."""

    def __init__(self, kind, inputs, output, **extra):
        self.kind = kind
        self.inputs = inputs
        self.output = output
        self.extra = extra

    def __str__(self):
        detail = " ".join(f"{k}={v}" for k, v in self.extra.items())
        return f"{self.kind:14s} {self.inputs} -> {self.output}  {detail}"


def resolve(name, alias):
    """Follow an `Identity` alias chain to the tensor that actually produced it."""
    seen = set()
    while name in alias and name not in seen:
        seen.add(name)
        name = alias[name]
    return name


def normalise(model):
    """Strip `Identity`, fold the constant affine chains, and return the ops and tensors.

    Returns `(ops, layers, tensors)`: the normalised op list for transcription, the
    `Layer` records the digest is taken over, and the fp16 tensors in `.vkml` order.
    """
    graph = model.graph
    inits = {i.name: numpy_helper.to_array(i) for i in graph.initializer}

    # Rewire Identity without mutating the nodes: build an alias map and read every
    # input through it.
    alias = {}
    for node in graph.node:
        if node.op_type == "Identity":
            alias[node.output[0]] = node.input[0]
    nodes = [n for n in graph.node if n.op_type != "Identity"]
    inputs_of = {id(n): [resolve(i, alias) for i in n.input] for n in nodes}

    producer = {}
    consumers = {}
    for node in nodes:
        for name in inputs_of[id(node)]:
            consumers.setdefault(name, []).append(node)
        for name in node.output:
            producer[name] = node

    def constant(node, index):
        """The constant operand of a binary node, or `(None, None)`."""
        names = inputs_of[id(node)]
        for position, name in enumerate(names):
            if name in inits:
                other = names[1 - position] if len(names) == 2 else None
                if index is None or position == index:
                    return inits[name], other
        return None, None

    consumed = set()
    ops = []
    layers = []
    tensors = []

    def emit_conv(node, weight, bias, act, act_alpha, output):
        first = len(tensors)
        tensors.append(weight.astype(np.float32))
        tensors.append(bias.astype(np.float32))
        attrs = {a.name: a for a in node.attribute}

        def ints(name, default):
            return list(attrs[name].ints) if name in attrs else list(default)

        key = (
            f"{node.op_type} w={list(weight.shape)} b={list(bias.shape)} "
            f"k={ints('kernel_shape', weight.shape[2:])} "
            f"d={ints('dilations', (1, 1))} p={ints('pads', (0, 0, 0, 0))} "
            f"s={ints('strides', (1, 1))} "
            f"g={attrs['group'].i if 'group' in attrs else 1} act={act}"
        )
        layers.append(
            vkml_convert.Layer(len(layers), node.op_type, output, key, first, 2)
        )
        ops.append(
            Op(
                node.op_type,
                [inputs_of[id(node)][0]],
                output,
                w=list(weight.shape),
                k=ints("kernel_shape", weight.shape[2:]),
                s=ints("strides", (1, 1)),
                p=ints("pads", (0, 0, 0, 0)),
                g=attrs["group"].i if "group" in attrs else 1,
                act=act,
                t=first,
            )
        )

    for node in nodes:
        if id(node) in consumed:
            continue
        if node.op_type in ("Conv", "ConvTranspose"):
            names = inputs_of[id(node)]
            weight = np.array(inits[names[1]], dtype=np.float64)
            channels = weight.shape[1] if node.op_type == "ConvTranspose" else weight.shape[0]
            bias = (
                np.array(inits[names[2]], dtype=np.float64).flatten()
                if len(names) > 2
                else np.zeros(channels, dtype=np.float64)
            )
            act, act_alpha = "None", None
            cursor = node.output[0]

            # Absorb everything constant that follows, stopping at the activation: an
            # affine applied *after* one cannot move before it.
            while True:
                following = consumers.get(cursor, [])
                if act != "None":
                    break
                # `x * HardSigmoid(x)` is not a single-consumer chain: the tensor feeds
                # both the HardSigmoid and the Mul that combines them. Matching it before
                # the branch test below is the difference between fusing 24 HardSwishes
                # and emitting 48 extra ops for them.
                gate = next((n for n in following if n.op_type in ("HardSigmoid", "Sigmoid")), None)
                if len(following) == 2 and gate is not None:
                    other = following[0] if following[1] is gate else following[1]
                    after = consumers.get(gate.output[0], [])
                    if (
                        other.op_type == "Mul"
                        and len(after) == 1
                        and after[0] is other
                        and gate.output[0] in inputs_of[id(other)]
                        and cursor in inputs_of[id(other)]
                    ):
                        if gate.op_type == "Sigmoid":
                            act = "Swish"
                        else:
                            attrs = {a.name: a.f for a in gate.attribute}
                            alpha = attrs.get("alpha", 0.2)
                            beta = attrs.get("beta", 0.5)
                            if (
                                abs(alpha - HARDSWISH_ALPHA) > TOLERANCE
                                or abs(beta - 0.5) > TOLERANCE
                            ):
                                raise SystemExit(
                                    f"{gate.output[0]}: x * HardSigmoid(x) with "
                                    f"alpha={alpha} beta={beta}; the runtime's HardSwish "
                                    "is alpha=1/6 beta=0.5"
                                )
                            act = "HardSwish"
                        consumed.add(id(gate))
                        consumed.add(id(other))
                        cursor = other.output[0]
                        continue
                if len(following) != 1:
                    break
                step = following[0]
                if step.op_type == "Add":
                    value, _ = constant(step, None)
                    if value is None:
                        break
                    bias = bias + np.array(value, dtype=np.float64).flatten()
                elif step.op_type == "Mul":
                    value, _ = constant(step, None)
                    if value is None:
                        break
                    scale = np.array(value, dtype=np.float64).flatten()
                    if scale.size == 1:
                        weight = weight * scale[0]
                        bias = bias * scale[0]
                    elif scale.size == channels:
                        shape = [1] * weight.ndim
                        axis = 1 if node.op_type == "ConvTranspose" else 0
                        shape[axis] = channels
                        weight = weight * scale.reshape(shape)
                        bias = bias * scale
                    else:
                        break
                elif step.op_type == "BatchNormalization":
                    weight, bias = fold_batch_norm(step, inits, weight, bias, node.op_type)
                elif step.op_type == "Relu":
                    act = "Relu"
                elif step.op_type == "Sigmoid":
                    act = "Sigmoid"
                elif step.op_type == "HardSigmoid":
                    # A HardSigmoid with a single consumer is a standalone gate — the
                    # excite half of a squeeze-excite block, whose Mul takes a different
                    # tensor. The self-multiplying kind was matched above.
                    #
                    # Its alpha and beta fold into the convolution:
                    # `clamp(alpha * (Wx + b) + beta, 0, 1)` is `clamp(W'x + b', 0, 1)`.
                    # Both 0.2 and 1/6 appear in this graph, so an activation carrying the
                    # two parameters would have to put them in the push block; this way the
                    # runtime needs one parameterless clamp.
                    attrs = {a.name: a.f for a in step.attribute}
                    alpha = attrs.get("alpha", 0.2)
                    beta = attrs.get("beta", 0.5)
                    weight = weight * alpha
                    bias = bias * alpha + beta
                    act = "Clip01"
                else:
                    break
                consumed.add(id(step))
                cursor = step.output[0]

            emit_conv(node, weight, bias, act, act_alpha, cursor)
        elif node.op_type in ("Add", "Mul"):
            value, other = constant(node, None)
            if value is not None:
                # A leftover constant affine: it follows an activation, so it could not be
                # folded above. One elementwise pass rather than a weight rewrite whose
                # validity depends on what the tensor feeds.
                scalar = np.array(value, dtype=np.float64).flatten()
                if scalar.size != 1:
                    raise SystemExit(
                        f"{node.output[0]}: a leftover {node.op_type} against a "
                        f"{list(np.array(value).shape)} constant, which is not a scalar affine"
                    )
                scale = float(scalar[0]) if node.op_type == "Mul" else 1.0
                shift = float(scalar[0]) if node.op_type == "Add" else 0.0
                ops.append(Op("Affine", [other], node.output[0], scale=scale, shift=shift))
            else:
                ops.append(Op(node.op_type, inputs_of[id(node)], node.output[0]))
        else:
            ops.append(Op(node.op_type, inputs_of[id(node)], node.output[0]))

    return merge_affines(ops, consumers), layers, tensors


def merge_affines(ops, consumers):
    """Collapse a `Mul(scalar)` immediately followed by an `Add(scalar)` into one op.

    `paddle2onnx` spells a learnable affine block as two nodes, so folding leaves them as
    two `Affine`s in a row. One op is one pass over the data instead of two.
    """
    by_input = {}
    for index, op in enumerate(ops):
        for name in op.inputs:
            by_input.setdefault(name, []).append(index)

    merged = []
    absorbed = set()
    for index, op in enumerate(ops):
        if index in absorbed:
            continue
        if op.kind == "Affine":
            followers = by_input.get(op.output, [])
            if len(followers) == 1:
                nxt = ops[followers[0]]
                # Only when the second is the other half: a scale then a shift.
                if (
                    nxt.kind == "Affine"
                    and len(consumers.get(op.output, [])) <= 1
                    and (op.extra["shift"] == 0.0 or nxt.extra["scale"] == 1.0)
                ):
                    op = Op(
                        "Affine",
                        op.inputs,
                        nxt.output,
                        # `(x * a + s) * a2 + s2`, with one of the two halves the identity.
                        scale=op.extra["scale"] * nxt.extra["scale"],
                        shift=op.extra["shift"] * nxt.extra["scale"] + nxt.extra["shift"],
                    )
                    absorbed.add(followers[0])
        merged.append(op)
    return merged


def fold_batch_norm(node, inits, weight, bias, op_type):
    """`gamma * (x - mean) / sqrt(var + eps) + beta`, pushed into a weight and bias."""
    attrs = {a.name: a.f for a in node.attribute}
    epsilon = attrs.get("epsilon", 1e-5)
    gamma, beta, mean, var = (
        np.array(inits[n], dtype=np.float64) for n in node.input[1:5]
    )
    scale = gamma / np.sqrt(var + epsilon)
    shape = [1] * weight.ndim
    shape[1 if op_type == "ConvTranspose" else 0] = scale.size
    return weight * scale.reshape(shape), (bias - mean) * scale + beta


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("onnx")
    parser.add_argument("--graph", required=True, choices=sorted(vkml_convert.GRAPHS))
    parser.add_argument("-o", "--out", help="where to write the .vkml")
    parser.add_argument("--print-graph", action="store_true", help="dump the folded ops")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    with open(args.onnx, "rb") as f:
        raw = f.read()
    onnx_sha256 = hashlib.sha256(raw).digest()

    model = onnx.load(args.onnx)
    ops, layers, tensors = normalise(model)

    if args.print_graph:
        for index, op in enumerate(ops):
            print(f"{index:4d} {op}")
    if args.print_layers:
        for layer in layers:
            print(f"{layer.index:4d} t{layer.first_tensor:<4d} {layer.key()}  # {layer.name}")

    digest = vkml_convert.layer_table_digest(layers)
    if args.print_digest:
        print(f'    "{args.graph}": "{digest}",')
    pinned = vkml_convert.EXPECTED_DIGEST.get(args.graph, "")
    if digest != pinned and not args.print_digest:
        raise SystemExit(
            f"layer table digest {digest}\n           pinned  {pinned}\n"
            "The folded layer table changed, so the hardcoded forward pass no longer\n"
            "matches this export. Re-read it with --print-graph, update the Rust, then\n"
            "re-pin with --print-digest."
        )

    blob, count = vkml_convert.build(
        layers, tensors, vkml_convert.GRAPHS[args.graph], onnx_sha256
    )
    if args.out:
        with open(args.out, "wb") as f:
            f.write(blob)
    kinds = {}
    for op in ops:
        kinds[op.kind] = kinds.get(op.kind, 0) + 1
    print(
        f"{args.graph}: {count} folded layers, {len(tensors)} tensors, "
        f"{len(blob)} bytes ({len(raw)} fp32 in)"
    )
    print(f"  normalised ops: {kinds}")


if __name__ == "__main__":
    main()
