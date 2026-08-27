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

# Ops that only rearrange or relabel, and are therefore aliases in `.vkml`'s layout.
#
# This runtime's tensors are `[c, h, w]` with batch always 1, and `nets::ppocr_rec` keeps a
# transformer's sequence as `[d_model, 1, T]` precisely so that the export's reshapes and
# permutes are no-ops. Nine `Transpose`s, 52 `Reshape`s and nine `Squeeze`s in the
# recognition graph are exactly that: `[1, 120, 1, T]` to `[1, T, 120]` and back, and the
# five-dimensional shuffle that splits a fused QKV projection into heads.
#
# Aliasing them is a **claim**, not an observation: nothing here checks that a given
# `Transpose` is a no-op in the target layout. What checks it is that the forward pass in
# `nets::ppocr_rec` was written from a reading of this same graph, and that the layer table
# digest below pins every weight's shape. A permute that mattered would show up as a net
# that infers nonsense, so `--print-graph` is worth reading when this list changes.
SHAPE_ONLY = ("Reshape", "Transpose", "Squeeze", "Unsqueeze", "Flatten")

# The shape-computation subgraph that feeds those reshapes: `Shape` into `Slice` into
# `Concat`. Once the reshapes are aliased away nothing reads it, so it is dropped as dead
# code rather than recognised.
SHAPE_MACHINERY = ("Shape", "ConstantOfShape")


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
    # The recognition export puts its weights in `Constant` *nodes* rather than in the
    # initializer list — 330 of them — so those count as constants too. Detection uses
    # initializers, so both have to work.
    for node in graph.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    inits[node.output[0]] = numpy_helper.to_array(attribute.t)

    # Rewire Identity without mutating the nodes: build an alias map and read every
    # input through it. `Cast` is aliased too — paddle2onnx inserts one in front of every
    # transposed convolution's weight, and it is a no-op between two float types. So are
    # the pure shape ops; see `SHAPE_ONLY`.
    alias = {}
    for node in graph.node:
        if node.op_type == "Identity" or (
            node.op_type == "Cast" and node.input and node.input[0] in inits
        ):
            alias[node.output[0]] = node.input[0]
        elif node.op_type in SHAPE_ONLY and node.input:
            alias[node.output[0]] = node.input[0]
        elif node.op_type in ("Add", "Mul") and len(node.input) == 2:
            # `Add(x, 0)` and `Mul(x, 1)`. paddle2onnx emits three of these around the
            # sequence pool, where Paddle's `assign` became an unsqueeze, an add of zero
            # and a squeeze. Folding them as affines would work and would leave an
            # elementwise pass over 115,200 elements that does nothing.
            for position, name in enumerate(node.input):
                value = inits.get(name)
                if value is None:
                    continue
                flat = np.array(value, dtype=np.float64).flatten()
                identity = 0.0 if node.op_type == "Add" else 1.0
                if flat.size == 1 and abs(flat[0] - identity) < 1e-12:
                    alias[node.output[0]] = node.input[1 - position]
                    break

    # The fused QKV projection, and the three slices that split it. Resolved here rather
    # than during the walk because the alias map has to be complete before any input is
    # read through it; this adds the slice outputs to it.
    fused, keep = split_fused_qkv(graph, inits, alias)

    nodes = [
        n
        for n in graph.node
        if n.op_type not in ("Identity", "Constant")
        and n.op_type not in SHAPE_MACHINERY
        and not (n.output and n.output[0] in alias)
    ]
    # Whatever is left of the shape machinery reads a `Shape` that is now gone, so it is
    # dead. Reverse reachability from the graph's output is what removes it.
    nodes = live_nodes(nodes, graph, alias, fused, keep)
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

    def emit_linear(node, weight, bias, act, source, output, already_transposed=False):
        """A `MatMul` against a constant, as the 1x1 convolution the runtime lowers it to.

        ONNX spells `x[..., in] @ W[in, out]`, so the weight arrives transposed relative to
        a convolution's `[out, in, kh, kw]`. Transposing here rather than in the shader is
        what lets every projection in the transformer be an ordinary `Kind::Conv`.
        """
        kernel = weight if already_transposed else transposed(weight)
        first = len(tensors)
        tensors.append(kernel.astype(np.float32))
        tensors.append(bias.astype(np.float32))
        key = (
            f"Linear w={list(kernel.shape)} b={list(bias.shape)} "
            f"k=[1, 1] d=[1, 1] p=[0, 0, 0, 0] s=[1, 1] g=1 act={act}"
        )
        layers.append(vkml_convert.Layer(len(layers), "MatMul", output, key, first, 2))
        ops.append(
            Op("Linear", [source], output, w=list(kernel.shape), act=act, t=first)
        )

    def emit_layer_norm(gamma, beta, epsilon, source, output):
        """One `LayerNorm` layer from the nine nodes the export decomposes it into."""
        first = len(tensors)
        tensors.append(np.array(gamma, dtype=np.float32).flatten())
        tensors.append(np.array(beta, dtype=np.float32).flatten())
        key = f"LayerNorm c={gamma.size} eps={epsilon:.9g}"
        layers.append(vkml_convert.Layer(len(layers), "LayerNorm", output, key, first, 2))
        ops.append(Op("LayerNorm", [source], output, c=int(gamma.size), eps=epsilon, t=first))

    def absorb(cursor, weight, bias, channels, is_transpose):
        """Pull every constant that follows `cursor` into `weight` and `bias`.

        Stops at the activation, because an affine applied *after* one cannot move before
        it. Returns the updated weight and bias, the activation found, and the name of the
        last tensor absorbed — which becomes the layer's output.

        Shared by the convolutions and the `MatMul` linears: the recognition export spells
        a feed-forward layer as `MatMul -> Add(bias) -> Sigmoid -> Mul(self)`, which is the
        same chain a convolution gets, so recognising it twice would be two chances to
        recognise it differently.
        """
        act = "None"
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
                    axis = 1 if is_transpose else 0
                    shape[axis] = channels
                    weight = weight * scale.reshape(shape)
                    bias = bias * scale
                else:
                    break
            elif step.op_type == "BatchNormalization":
                weight, bias = fold_batch_norm(
                    step, inits, weight, bias, "ConvTranspose" if is_transpose else "Conv"
                )
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
        return weight, bias, act, cursor

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
            weight, bias, act, cursor = absorb(
                node.output[0], weight, bias, channels, node.op_type == "ConvTranspose"
            )
            emit_conv(node, weight, bias, act, None, cursor)
        elif node.op_type == "MatMul":
            names = inputs_of[id(node)]
            constants = [n for n in names if n in inits]
            if not constants:
                # Both operands are activations, so this is one of attention's two
                # products — the scores or the weighted sum. `nets::ppocr_rec` lowers
                # those to `AttnScores` and `AttnApply`, which read no weights.
                ops.append(Op("MatMul", names, node.output[0]))
                continue
            weight = np.array(inits[constants[0]], dtype=np.float64)
            if weight.ndim != 2:
                raise SystemExit(
                    f"{node.output[0]}: a MatMul against a {list(weight.shape)} constant"
                )
            source = next(n for n in names if n not in inits)
            outputs = weight.shape[1]
            bias, bias_node = following_bias(node, consumers, inputs_of, inits, outputs)
            if bias_node is not None:
                consumed.add(id(bias_node))
            cursor = bias_node.output[0] if bias_node is not None else node.output[0]
            parts = fused.get(node.output[0])
            if parts is not None:
                # A fused QKV projection: one `[d, 3d]` weight that the export slices into
                # query, key and value after a five-dimensional shuffle. Split here into
                # three ordinary linears, because `nets::ppocr_rec` projects them
                # separately and a channel slice of a constant is exact.
                width = outputs // 3
                for index, name in enumerate(parts):
                    emit_linear(
                        node,
                        weight[:, index * width : (index + 1) * width],
                        bias[index * width : (index + 1) * width],
                        "None",
                        source,
                        name,
                    )
                continue
            weight, bias, act, cursor = absorb(cursor, transposed(weight), bias, outputs, False)
            emit_linear(node, weight, bias, act, source, cursor, already_transposed=True)
        elif node.op_type == "ReduceMean":
            matched = match_layer_norm(node, consumers, inputs_of, inits)
            if matched is None:
                ops.append(Op(node.op_type, inputs_of[id(node)], node.output[0]))
                continue
            chain, gamma, beta, epsilon, source, output = matched
            for member in chain:
                consumed.add(id(member))
            emit_layer_norm(gamma, beta, epsilon, source, output)
        elif node.op_type == "Softmax":
            axis = next((a.i for a in node.attribute if a.name == "axis"), -1)
            if node.output[0] in {o.name for o in graph.output}:
                # The classifier's softmax over the 838 classes. Deliberately dropped:
                # `post::ctc::decode` takes the argmax, which a softmax cannot change, and
                # recovers the winner's probability from the raw logits directly. See the
                # module docs in `nets::ppocr_rec`.
                ops.append(Op("DroppedSoftmax", inputs_of[id(node)], node.output[0], axis=axis))
                continue
            ops.append(Op(node.op_type, inputs_of[id(node)], node.output[0], axis=axis))
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

    ops = merge_affines(ops, consumers)
    ops = fold_affine_into_convs(ops, tensors)
    return ops, layers, tensors


def transposed(weight):
    """`[in, out]` as a convolution kernel `[out, in, 1, 1]`."""
    return np.ascontiguousarray(weight.T).reshape(weight.shape[1], weight.shape[0], 1, 1)


def following_bias(node, consumers, inputs_of, inits, outputs):
    """The `Add(matmul, bias)` that follows a linear, as `(bias, node)`.

    Raises rather than defaulting to zeros. Every `MatMul` in this export carries its bias
    as a following `Add`, so failing to find one means the graph is not the shape this
    script thinks — and a silently zeroed bias is invisible: it survives every shape check,
    the layer table digest and the tensor count, and shows up only as lost accuracy.
    """
    following = consumers.get(node.output[0], [])
    if len(following) == 1 and following[0].op_type == "Add":
        for name in inputs_of[id(following[0])]:
            if name in inits:
                value = np.array(inits[name], dtype=np.float64).flatten()
                if value.size == outputs:
                    return value, following[0]
    raise SystemExit(
        f"{node.output[0]}: a MatMul with {outputs} outputs and no following Add against a "
        f"[{outputs}] constant, so its bias cannot be read"
    )


def split_fused_qkv(graph, inits, alias):
    """Find each fused QKV projection and alias its three slices to per-part names.

    PaddleOCR's attention projects query, key and value in one `[d, 3d]` `MatMul`, then
    reshapes to `[batch, T, 3, heads, head_dim]`, permutes and slices the leading axis.
    `nets::ppocr_rec` projects the three separately, so this records which slice is which
    and lets the walk emit three linears from the one weight.

    It also drops the `Mul(q, 1/sqrt(head_dim))` the export applies to the query, after
    checking the constant really is that: `Kind::AttnScores` derives the same scale from
    the head geometry, so leaving it here would apply it twice.

    Mutates `alias`, and returns `({matmul_output: [q_name, k_name, v_name]}, keep)`, where
    `keep` is the set of output names that must survive dead-code elimination.
    """
    def resolved(name):
        return resolve(name, alias)

    constants = dict(inits)
    consumers = {}
    for node in graph.node:
        for name in node.input:
            consumers.setdefault(resolved(name), []).append(node)

    fused = {}
    keep = set()
    for node in graph.node:
        if node.op_type != "MatMul":
            continue
        weights = [resolved(n) for n in node.input if resolved(n) in constants]
        if not weights:
            continue
        weight = np.array(constants[weights[0]])
        if weight.ndim != 2 or weight.shape[1] != weight.shape[0] * 3:
            continue

        width = weight.shape[0]
        names = [f"{node.output[0]}#{part}" for part in ("q", "k", "v")]
        # The slices read the bias `Add`'s output through the aliased reshape and permute.
        bias_node = next(
            (n for n in consumers.get(node.output[0], []) if n.op_type == "Add"), None
        )
        if bias_node is None:
            raise SystemExit(f"{node.output[0]}: a fused QKV projection with no bias")
        # Aliasing the three slices below orphans this `Add` — nothing in the graph reads
        # its output any more — so without pinning it here the dead-code pass removes it
        # and the walk reads a zero bias for all three projections.
        #
        # Pinned by output *name*, not by `id()`: protobuf's upb backend hands out a fresh
        # Python wrapper on each access to a repeated field, so an id taken here does not
        # match the one `normalise` sees. Names are unique — ONNX graphs are SSA.
        keep.add(bias_node.output[0])
        split_source = resolved(bias_node.output[0])
        found = {}
        for candidate in graph.node:
            if candidate.op_type != "Slice":
                continue
            if not candidate.input or resolved(candidate.input[0]) != split_source:
                continue
            starts = next((list(a.ints) for a in candidate.attribute if a.name == "starts"), [])
            if len(starts) != 1 or not 0 <= starts[0] < 3:
                continue
            found[starts[0]] = candidate
        if len(found) != 3:
            raise SystemExit(
                f"{node.output[0]}: a [{width}, {width * 3}] projection with "
                f"{len(found)} slices, expected three (query, key, value)"
            )
        for part, candidate in found.items():
            alias[candidate.output[0]] = names[part]
        fused[node.output[0]] = names

        # The query's scale. `heads` is not known here, so the check is that the constant
        # is `1/sqrt(width / heads)` for the integer head count that makes it so.
        scale_node = next(
            (
                n
                for n in graph.node
                if n.op_type == "Mul" and any(resolved(i) == names[0] for i in n.input)
            ),
            None,
        )
        if scale_node is None:
            raise SystemExit(f"{node.output[0]}: the query is not scaled at all")
        value = next(
            (np.array(constants[resolved(i)]).flatten() for i in scale_node.input
             if resolved(i) in constants),
            None,
        )
        if value is None or value.size != 1:
            raise SystemExit(f"{scale_node.output[0]}: the query's scale is not a scalar")
        head_dim = 1.0 / float(value[0]) ** 2
        if abs(head_dim - round(head_dim)) > 1e-3 or not width % max(round(head_dim), 1) == 0:
            raise SystemExit(
                f"{scale_node.output[0]}: a query scale of {value[0]} is not "
                f"1/sqrt(head_dim) for any head count dividing {width}"
            )
        alias[scale_node.output[0]] = names[0]
    return fused, keep


def live_nodes(nodes, graph, alias, fused, keep):
    """`nodes`, restricted to those that transitively feed a graph output, plus `keep`.

    Aliasing the reshapes away orphans the `Shape`/`Slice`/`Concat` subgraphs that computed
    their target shapes — 52 reshapes' worth. Reverse reachability removes them without
    this script having to recognise what each one was for.

    `fused` has to be threaded through: a fused QKV projection's three parts are named for
    slices that no longer exist, so without registering the `MatMul` as their producer the
    walk backwards stops dead there and takes both attentions and the two layer norms in
    front of them with it. `keep` carries the output names of nodes that are unreachable
    *by construction* but still hold data the walk needs — those projections' bias `Add`s.
    """
    producer = {}
    for node in nodes:
        for name in node.output:
            producer[name] = node
        for part in fused.get(node.output[0] if node.output else "", []):
            producer[part] = node
    live = set()
    queue = [resolve(o.name, alias) for o in graph.output]
    queue.extend(keep)
    while queue:
        name = queue.pop()
        node = producer.get(name)
        if node is None or id(node) in live:
            continue
        live.add(id(node))
        queue.extend(resolve(i, alias) for i in node.input)
    return [n for n in nodes if id(n) in live]


def match_layer_norm(first_mean, consumers, inputs_of, inits):
    """Match `ReduceMean/Sub/Pow/ReduceMean/Add/Sqrt/Div/Mul/Add` as one layer norm.

    The export decomposes each of the recogniser's five layer norms into nine nodes. Left
    alone that is 45 ops, most of them reductions over a dynamic axis. Returns
    `(chain, gamma, beta, epsilon, source, output)`, or `None` if this `ReduceMean` is
    something else.

    Matched forwards from the *first* `ReduceMean` rather than from the `Div`, because the
    walk reaches it first and would otherwise have emitted it as a standalone op.
    """
    def only(name, kind):
        following = consumers.get(name, [])
        if len(following) == 1 and following[0].op_type == kind:
            return following[0]
        return None

    def other_input(node, known):
        return next((n for n in inputs_of[id(node)] if n != known), None)

    source = next(iter(inputs_of[id(first_mean)]), None)
    if source is None:
        return None
    # `Sub(x, mean)` reads both the layer norm's input and this mean.
    centre = next(
        (
            n
            for n in consumers.get(first_mean.output[0], [])
            if n.op_type == "Sub" and source in inputs_of[id(n)]
        ),
        None,
    )
    if centre is None:
        return None
    power = next(
        (n for n in consumers.get(centre.output[0], []) if n.op_type == "Pow"), None
    )
    if power is None:
        return None
    exponent = next(
        (np.array(inits[n]).flatten() for n in inputs_of[id(power)] if n in inits), None
    )
    if exponent is None or exponent.size != 1 or abs(exponent[0] - 2.0) > 1e-9:
        return None
    variance = only(power.output[0], "ReduceMean")
    if variance is None:
        return None
    stabilised = only(variance.output[0], "Add")
    if stabilised is None:
        return None
    epsilon = next(
        (np.array(inits[n]).flatten() for n in inputs_of[id(stabilised)] if n in inits), None
    )
    if epsilon is None or epsilon.size != 1:
        return None
    root = only(stabilised.output[0], "Sqrt")
    if root is None:
        return None
    divide = next(
        (
            n
            for n in consumers.get(root.output[0], [])
            if n.op_type == "Div" and centre.output[0] in inputs_of[id(n)]
        ),
        None,
    )
    if divide is None:
        return None
    scale = only(divide.output[0], "Mul")
    if scale is None:
        return None
    gamma_name = other_input(scale, divide.output[0])
    if gamma_name not in inits:
        return None
    shift = only(scale.output[0], "Add")
    if shift is None:
        return None
    beta_name = other_input(shift, scale.output[0])
    if beta_name not in inits:
        return None

    gamma = np.array(inits[gamma_name]).flatten()
    beta = np.array(inits[beta_name]).flatten()
    if gamma.size != beta.size:
        return None
    chain = [first_mean, centre, power, variance, stabilised, root, divide, scale, shift]
    return chain, gamma, beta, float(epsilon[0]), source, shift.output[0]


def fold_affine_into_convs(ops, tensors):
    """Push each scalar `Affine` into the convolutions it feeds, where that is exact.

    `conv(a * x + t)[m]` is `a * conv(x)[m] + t * sum(W[m])`, so an affine on a
    convolution's *input* folds into its weight and bias just as one on its output does.
    The sum is over every weight axis but the output-channel one, which for a grouped or
    depthwise convolution is exactly the channels that output reads.

    # Only when the convolution is unpadded

    That identity holds at an interior pixel and **fails at a border one**. A padded
    convolution reads zero outside the input, not `t`, so the constant's true contribution
    there is `t * sum(W over the in-bounds taps)` — position-dependent, and smaller than
    `t * sum(W)` by whatever hangs over the edge. Folding anyway biases the whole border
    of every feature map, which is 27% of the pixels of a 12x16 map at a 3x3 kernel, and
    compounds through the backbone.

    There is no fix that keeps the fold: the correction varies per output position, so it
    is not a bias. Padding the input with `t` instead of zero would make the identity exact
    and is not what the graph says. So an affine feeding a padded convolution stays an op,
    which is what ncnn's conversion of these models does with all of them.

    Also only when the affine's result is not needed anywhere else, so every consumer has
    to be a convolution as well.
    """
    consumers = {}
    for op in ops:
        for name in op.inputs:
            consumers.setdefault(name, []).append(op)

    def foldable(op):
        if op.kind not in ("Conv", "ConvTranspose"):
            return False
        # `Linear` is a 1x1 with no padding by construction, but it is spelled as its own
        # kind and is deliberately not folded into: it would mean this function had to know
        # that a `MatMul`'s weight is stored transposed.
        return not any(op.extra.get("p", (0, 0, 0, 0)))

    dropped = set()
    for op in ops:
        if op.kind != "Affine":
            continue
        following = consumers.get(op.output, [])
        if not following or not all(foldable(f) for f in following):
            continue
        scale = op.extra["scale"]
        shift = op.extra["shift"]
        for conv in following:
            first = conv.extra["t"]
            weight = tensors[first]
            bias = tensors[first + 1]
            # The row sum comes from the *unscaled* weight, so compute it first.
            axes = (0, 2, 3) if conv.kind == "ConvTranspose" else (1, 2, 3)
            tensors[first + 1] = bias + shift * weight.sum(axis=axes)
            tensors[first] = weight * scale
            # The convolution now reads what fed the affine.
            conv.inputs = [op.inputs[0] if i == op.output else i for i in conv.inputs]
        dropped.add(id(op))
    return [op for op in ops if id(op) not in dropped]


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
