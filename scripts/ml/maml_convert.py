#!/usr/bin/env python3
"""ONNX to `.maml`, the weights container `:library:ml` reads.

`:library:ml` is not a graph interpreter: each network's forward pass is hardcoded
Rust in `library/ml/src/main/rust/src/nets/`. So a `.maml` file carries **only
ordered tensors** — no operators, no topology, no names. The Rust net module
indexes the tensor table positionally.

That makes tensor *order* the entire contract between this script and the Rust,
and a reordering would be silently wrong rather than a load failure. So the order
is defined mechanically and pinned:

* Order is the weighted nodes in ONNX **topological order** — `Conv`/`ConvTranspose`
  weight then bias, `PRelu` slope, `Gemm` weight then bias. Nothing else is emitted:
  every other initializer in these graphs is shape-computation scaffolding that folds
  away at a fixed input size.

  Topological rather than initializer order matters for the nets with `PRelu`: the Rust
  reads a layer as `weight = i, bias = i + 1, slope = i + 2`, which only lines up if a
  slope is emitted immediately after the convolution it follows.
* `--check` hashes the whole ordered layer table (shapes *and* the attributes the
  Rust hardcodes: dilation, pads, strides, group) into one SHA-256 pinned below.
  Any upstream re-export that renames, reorders, retunes or re-pads a single layer
  changes that hash and fails, instead of producing a file that loads and infers
  nonsense.

Two nodes are rewritten rather than emitted verbatim, because doing it here means the
runtime needs no shader for either:

* A **`Gemm`** over a flattened NCHW map is a convolution whose kernel covers the whole
  spatial extent. MobileFaceNet's `[512, 3136]` becomes `[512, 64, 7, 7]` and the
  existing `conv.comp` computes it, so there is no `InnerProduct` pipeline.
* A trailing **`BatchNormalization`** is folded into the `Gemm` before it. At inference a
  batch norm is a per-channel affine, so `W' = W * gamma / sqrt(var + eps)` and
  `b' = (b - mean) * gamma / sqrt(var + eps) + beta` is exact, not an approximation.

Weights are stored fp16 with fp32 accumulation in-shader (see `SPEC` below for the
byte layout). ONNX tensor layout is preserved verbatim — `[M, C/group, kH, kW]` for
`Conv`, `[C, M/group, kH, kW]` for `ConvTranspose` — so nothing here has to agree
with the shaders about a transpose.

    ./scripts/ml/maml_convert.py --check model.onnx --graph u2netp
    ./scripts/ml/maml_convert.py model.onnx --graph u2netp -o out.maml
    ./scripts/ml/maml_convert.py model.onnx --graph u2netp --print-layers
"""

import argparse
import hashlib
import math
import struct
import sys

import numpy as np
import onnx
from onnx import numpy_helper, shape_inference

# --- .maml ---------------------------------------------------------------------
#
# One contiguous blob, so the whole file becomes one VkBuffer and one staging
# upload. Little-endian throughout; every device we target is.
#
#   header, 64 bytes
#     0   4   magic b"MAML"
#     4   4   u32 format version
#     8   4   u32 graph id — the runtime rejects a file built for another net
#    12   4   u32 tensor count
#    16  32   source ONNX SHA-256, so a shipped asset traces to its upstream
#    48   4   u32 offset of the data section
#    52   4   u32 length of the data section
#    56   8   reserved, zero
#
#   tensor table, 32 bytes per entry, immediately after the header
#     0   4   u32 rank, 1..4
#     4  16   u32[4] dims, unused trailing entries zero
#    20   4   u32 dtype, 0 = fp16
#    24   4   u32 byte offset within the data section
#    28   4   u32 element count
#
#   data, fp16, each tensor padded to ALIGNMENT
MAGIC = b"MAML"
FORMAT_VERSION = 1
HEADER_BYTES = 64
TENSOR_ENTRY_BYTES = 32
# Tensor payload types. The container carries the type per tensor, so fp16 and int8 can sit in
# one file and a reader knows the stride without being told.
DTYPE_F16 = 0

# Signed 8-bit, symmetric, with a per-output-channel scale in the fp16 tensor that follows it.
#
# Only for a network where the weights dominate the download: SMaLL-100 is 330 million
# parameters, 660 MB at fp16 against 330 MB here, and the ncnn build of the same model shipped
# 1.14 GB. Everything else stays fp16, where the saving would not pay for the loss.
DTYPE_I8 = 1
# 16 bytes is the largest scalar/vector alignment the shaders index with, and a
# multiple of 2, so an aligned tensor offset is also an aligned fp16 index.
ALIGNMENT = 16

SPEC = __doc__

# Graph ids. Shared with `library/ml/src/main/rust/src/weights.rs`; changing one
# without the other is what the id exists to catch.
GRAPHS = {
    "selfie": 1,
    "u2netp": 2,
    "scrfd": 3,
    "mobilefacenet": 4,
    # Folded ahead of time by `scripts/ml/ppocr_fold.py`, not by this script.
    "ppocr_det": 5,
    "ppocr_rec": 6,
    "vits_dec": 7,
    "vits_enc": 8,
    "vits_flow": 9,
    "vits_dp": 10,
    "supertonic_voc": 11,
    "supertonic_dp": 12,
    "supertonic_ttl": 13,
    "supertonic_ve": 14,
}

# SHA-256 over the ordered layer table (see `layer_table_digest`). Regenerate with
# --print-digest after deliberately re-pointing at a new upstream export.
EXPECTED_DIGEST = {
    "selfie": "6db422ecfe0af8e37591eb14c195166b2c416649bf9063100bd5bb14eafaad3a",
    "u2netp": "f5199e05e4d4e93a6af601d04e3e0516bb68b9f292bb81d474704ea0b4e591d0",
    "scrfd": "d2ce2880a432af03b0ef594029d3ab03d86de54bd0a9f10ea2c8e412309b0f46",
    "mobilefacenet": "40987839aec03378958581deba29ce9fd9dd1ae9aa88d0c7ad04a885a2e2122c",
    "ppocr_det": "51b0c9ed871526fa270b4bfa53b393c056db21c58193849fb83d44768f69ef9b",
    "ppocr_rec": "8c16a8702aa7a1e7b4d94787d089616ca4bc3d8aaeebb0b0159eabc56af3ba08",
    "vits_dec": "ac3fa714b9c0074e7fb7fa1d5f5b65e30bf558df0817b014897cd56b6e850020",
    "vits_enc": "93ee24f70b490334d11b383f043e81284a3e781bc263597830c50f8df14db2c1",
    "vits_flow": "38cecda81bcfd4eb98782395905ddbe4637aa7f55fecb2de996d09fc530e2e6d",
    "vits_dp": "d02a6e28700823af95cbeeeb2b40d77e531f90086599deb3f840ce9a2a57a494",
    "supertonic_voc": "64e3a3437fa8f889fb679a5e08553ba7392c05a32cd844a0d2340698372a0071",
    "supertonic_dp": "a294dac3e0fa1acf03d5a483b96782190a626c6adbc517969592ec6a888e15cd",
    "supertonic_ttl": "92d0fa8ae73d4a8a223c77fb4230e390df580744014a3fcf3211ab72a988c020",
    "supertonic_ve": "d76f1fb13eaa9f5a16fed1f97a491d25f75325a36f00535fa3a11a7b16527951",
}

# Graphs that are one **module** of a larger export, keyed by the node-name prefix that
# selects it.
#
# Piper's VITS is a single 2755-node ONNX and it cannot be one plan: its duration predictor
# samples noise and builds a monotonic alignment out of `NonZero`, `ScatterND` and
# `GatherND`, and the length of everything after it depends on the durations it predicted.
# But the export keeps its Torch module paths, so the graph partitions along exactly the
# lines the model was written in - `enc_p`, `dp`, `flow`, `dec` - and the three static ones
# convert independently. See `nets::vits_dec`.
#
# A module-scoped graph has no GEOMETRY entry: its input is another module's output rather
# than a graph input, so there is nothing at the boundary to check a shape against. What is
# checked instead is the op inventory within the module, which is the same guard by another
# route.
MODULES = {
    "vits_dec": "/dec/",
    "vits_enc": "/enc_p/",
    "vits_flow": "/flow/",
    "vits_dp": "/dp/",
}

# `outputs` are the graph outputs the Rust forward pass corresponds to; the export may
# declare more (U^2-Netp declares all seven of its side outputs and we read one).
GEOMETRY = {
    "selfie": {
        "input": "pixel_values",
        "outputs": ["alphas"],
        "shape": [3, 256, 256],
    },
    "u2netp": {
        "input": "input.1",
        "outputs": ["1959"],
        "shape": [3, 320, 320],
    },
    # Nine: score, box and keypoint maps at strides 8, 16 and 32. The Rust reads the
    # convolution outputs feeding these rather than the reshaped tensors themselves,
    # because a Transpose/Reshape is a relabelling that NCHW indexing does for free.
    "scrfd": {
        "input": "input.1",
        "outputs": [
            "443", "468", "493",
            "446", "471", "496",
            "449", "474", "499",
        ],
        "shape": [3, None, None],
    },
    "mobilefacenet": {
        "input": "input.1",
        "outputs": ["516"],
        "shape": [3, 112, 112],
    },
}

# Op-type counts, as a coarse first check that the graph is the one we lowered by
# hand. Ops absent from the list must be absent from the graph.
EXPECTED_OPS = {
    "selfie": {
        "Conv": 54, "ConvTranspose": 1, "Add": 14, "Mul": 10, "Concat": 3,
        "ReduceMean": 10, "Relu": 22, "HardSwish": 11, "Sigmoid": 11,
        "Resize": 3, "Shape": 3, "Slice": 3,
    },
    "u2netp": {
        "Conv": 119, "MaxPool": 33, "Resize": 38, "Concat": 127, "Add": 11,
        "Relu": 112, "Sigmoid": 7, "Shape": 114, "Gather": 76, "Unsqueeze": 76,
        "Slice": 38, "Cast": 38, "Constant": 266,
    },
    "scrfd": {
        "Conv": 60, "Relu": 41, "Sigmoid": 3, "Add": 4, "Resize": 2, "Concat": 2,
        "Reshape": 9, "Transpose": 9, "Shape": 6, "Gather": 4, "Unsqueeze": 4,
        "Slice": 2,
    },
    "mobilefacenet": {
        "Conv": 49, "PRelu": 34, "Add": 12, "BatchNormalization": 1, "Flatten": 1,
        "Gemm": 1,
    },
    # Counted within `/dec/` only; see `MODULES`. The `Div`s are the three that average each
    # stage's residual blocks, which `nets::vits_dec` lowers to `Kind::Affine` at 1/3.
    "vits_dec": {
        "Conv": 20, "ConvTranspose": 3, "LeakyRelu": 16, "Add": 24, "Div": 3, "Tanh": 1,
    },
    # Counted within `/enc_p/` only. Most of these are not arithmetic: 48 Pad, 96 Reshape,
    # 42 Slice and 130 Unsqueeze are the export building a [heads, T, 2T-1] relative score
    # map and skewing it back, which `nets::vits_enc` does as nine taps instead. The six
    # `Where` are the padding mask, which is bit-identically the identity for one unpadded
    # utterance and so is not transcribed either.
    "vits_enc": {
        "Add": 60, "Cast": 1, "Concat": 66, "Conv": 37, "Div": 18, "Equal": 1,
        "Gather": 61, "Less": 1, "MatMul": 24, "Mul": 72, "Pad": 48, "Pow": 12, "Range": 1,
        "ReduceMean": 24, "Relu": 6, "Reshape": 96, "Shape": 25, "Slice": 42, "Softmax": 6,
        "Split": 1, "Sqrt": 12, "Sub": 42, "Transpose": 73, "Unsqueeze": 130, "Where": 6,
    },
    # Counted within `/flow/` only. The 16 gated activations are NOT here: their Tanh and
    # Sigmoid nodes carry auto-generated names with no module path, so a prefix cannot see
    # them. `ConstantOfShape`/`Neg`/`Exp` are the mean-only coupling''s absent log-scale,
    # which is `exp(-0)`; the 32 `Mul`s are the padding mask and the identity multiply by it.
    "vits_flow": {
        "Add": 28, "Concat": 4, "ConstantOfShape": 24, "Conv": 40, "Exp": 4, "Mul": 32,
        "Neg": 4, "Shape": 24, "Slice": 28, "Split": 4, "Sub": 4,
    },
    # Counted within `/dp/` only. This module is never compiled into a plan — `post::duration`
    # reads its 112 tensors on the host — so the inventory is here purely as a guard that the
    # export has not changed shape under us. The interesting entries are Softmax 6, CumSum 6,
    # GatherElements 21 and Sqrt 27: the bin search and quadratic solve of three rational
    # quadratic spline couplings, and Erf 24 for the exact GELU in four separable stacks.
    "vits_dp": {
        "Add": 141, "And": 3, "Cast": 6, "Concat": 55, "ConstantOfShape": 22, "Conv": 32,
        "CumSum": 6, "Div": 60, "Equal": 24, "Erf": 24, "Expand": 90, "Gather": 101,
        "GatherElements": 21, "GatherND": 15, "GreaterOrEqual": 6, "LessOrEqual": 3,
        "Mul": 140, "Neg": 6, "NonZero": 12, "Not": 3, "Pad": 9, "Pow": 27,
        "RandomNormalLike": 1, "Range": 36, "ReduceMean": 48, "ReduceSum": 3,
        "Reshape": 66, "ScatterND": 30, "Shape": 94, "Slice": 70, "Softmax": 6,
        "Softplus": 3, "Split": 4, "Sqrt": 27, "Squeeze": 12, "Sub": 67, "Transpose": 63,
        "Unsqueeze": 95, "Where": 24,
    },
}


class Layer:
    """One weighted node, as the Rust forward pass needs to see it."""

    def __init__(self, index, op, name, key, first_tensor, tensor_count):
        self.index = index
        self.op = op
        self.name = name
        self._key = key
        # Where this layer's tensors start in the `.maml` table, and how many it has.
        # The Rust indexes by tensor, and with `PRelu` in the mix that is no longer
        # `2 * layer` — so --print-layers reports it rather than leaving a transcriber
        # to count.
        self.first_tensor = first_tensor
        self.tensor_count = tensor_count

    def key(self):
        """Everything the Rust hardcodes about this layer, as a stable string."""
        return self._key


def lift_to_2d(weight, node):
    """A 1-D convolution's rank-3 weight as the rank-4 one this runtime indexes.

    A tensor here is `[c, h, w]` with no rank-3 case, so a 1-D convolution is a 2-D one
    whose kernel is `1 x k` — which is exactly what `nets::vits_dec` transcribes. Inserting
    the height axis is a reshape of the same bytes in the same order, and doing it here means
    the Rust reads `[out, in, 1, k]` like every other kernel rather than carrying a special
    case for audio.

    Returns `(weight, spatial)`, where `spatial` is the kernel/dilation/pad/stride form the
    digest key should record: also lifted to two dimensions, so a reader comparing the key
    against the Rust sees the same `(1, k)` the Rust wrote.
    """
    attrs = {a.name: a for a in node.attribute}

    def ints(name, default):
        return list(attrs[name].ints) if name in attrs else list(default)

    if weight.ndim != 3:
        return weight, None
    lifted = weight.reshape(weight.shape[0], weight.shape[1], 1, weight.shape[2])
    # ONNX orders pads as all the begins then all the ends, so a 1-D `[before, after]`
    # becomes `[0, before, 0, after]` rather than being padded on the right.
    pads = ints("pads", (0, 0))
    return lifted, {
        "kernel_shape": [1] + ints("kernel_shape", [weight.shape[2]]),
        "dilations": [1] + ints("dilations", [1]),
        "strides": [1] + ints("strides", [1]),
        "pads": [0, pads[0], 0, pads[1]],
    }


def convolution_key(node, weight, bias, spatial=None):
    """The digest key for a `Conv`/`ConvTranspose`.

    Byte-for-byte the string the first version of this script produced, so the pinned
    selfie and u2netp digests survive every later addition here.

    `spatial` overrides the node's own attributes, for a 1-D convolution that
    [`lift_to_2d`] has turned into a `1 x k` one.
    """
    attrs = {a.name: a for a in node.attribute}

    def ints(name, default):
        if spatial is not None and name in spatial:
            return list(spatial[name])
        return list(attrs[name].ints) if name in attrs else list(default)

    return (
        f"{node.op_type} w={list(weight.shape)} "
        f"b={list(bias.shape) if bias is not None else None} "
        f"k={ints('kernel_shape', weight.shape[2:])} "
        f"d={ints('dilations', (1, 1))} p={ints('pads', (0, 0, 0, 0))} "
        f"s={ints('strides', (1, 1))} "
        f"g={attrs['group'].i if 'group' in attrs else 1}"
    )


def inferred_shapes(model):
    """Every tensor's shape, for the fixed-shape graphs. Empty where inference fails."""
    try:
        inferred = shape_inference.infer_shapes(model)
    except Exception:
        return {}
    everything = list(inferred.graph.value_info) + list(inferred.graph.input)
    return {
        vi.name: [d.dim_value for d in vi.type.tensor_type.shape.dim]
        for vi in everything
    }


def flatten_source_shape(node, graph, shapes):
    """`[c, h, w]` of the map a `Gemm`'s flattened input came from.

    This is what lets a `Gemm` be emitted as a convolution kernel. It is read from the
    graph rather than assumed, because getting it wrong reshapes the fully-connected
    weight into the wrong kernel and produces a plausible-looking embedding.
    """
    producer = next((n for n in graph.node if node.input[0] in n.output), None)
    if producer is None or producer.op_type != "Flatten":
        raise SystemExit(
            f"{node.output[0]}: a Gemm whose input is not a Flatten. Only a Gemm over a "
            "flattened feature map can be emitted as a convolution."
        )
    shape = shapes.get(producer.input[0])
    if not shape or len(shape) != 4 or 0 in shape[1:]:
        raise SystemExit(
            f"{node.output[0]}: cannot infer the shape feeding {producer.output[0]}, "
            f"got {shape}. A Gemm can only be reshaped where the map's size is static."
        )
    return list(shape[1:])


def fold_batch_norm(node, inits, weight, bias):
    """Fold a `BatchNormalization` into the weight and bias of the layer before it.

    At inference a batch norm is `gamma * (x - mean) / sqrt(var + eps) + beta`, a
    per-output-channel affine, so pushing it into the preceding layer is exact. Doing it
    here rather than in a shader is why the runtime has no batch-norm pipeline.
    """
    attrs = {a.name: a for a in node.attribute}
    epsilon = attrs["epsilon"].f if "epsilon" in attrs else 1e-5
    missing = [n for n in node.input[1:] if n not in inits]
    if missing:
        raise SystemExit(f"{node.output[0]}: batch norm parameter {missing} is computed")
    gamma = numpy_helper.to_array(inits[node.input[1]]).astype(np.float64)
    beta = numpy_helper.to_array(inits[node.input[2]]).astype(np.float64)
    mean = numpy_helper.to_array(inits[node.input[3]]).astype(np.float64)
    var = numpy_helper.to_array(inits[node.input[4]]).astype(np.float64)
    scale = gamma / np.sqrt(var + epsilon)
    # fp64 for the fold itself: the division by sqrt(var) can be large, and this is the
    # one place the file's values are computed rather than copied.
    folded_weight = weight.astype(np.float64) * scale.reshape([-1] + [1] * (weight.ndim - 1))
    folded_bias = (bias.astype(np.float64) - mean) * scale + beta
    return folded_weight, folded_bias


def collect_layers(model, prefix=None):
    """The weighted nodes in topological order, with the tensors each contributes.

    `prefix` restricts the walk to one Torch module; see [`MODULES`].
    """
    graph = model.graph
    inits = {i.name: i for i in graph.initializer}
    # A module-scoped export keeps its weights in `Constant` nodes rather than the
    # initializer list, the way PP-OCRv5's recognition export does.
    constants = {}
    for node in graph.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    constants[node.output[0]] = attribute.t
    shapes = inferred_shapes(model)

    def held(name):
        return name in inits or name in constants

    def array(name):
        return numpy_helper.to_array(inits[name] if name in inits else constants[name])

    # Which node's output each BatchNormalization consumes, so the layer producing it
    # can absorb it.
    batch_norms = {n.input[0]: n for n in graph.node if n.op_type == "BatchNormalization"}
    consumers = {}
    for node in graph.node:
        for name in node.input:
            consumers.setdefault(name, []).append(node)
    folded = set()

    layers = []
    tensors = []
    for node in graph.node:
        if prefix is not None and not node.name.startswith(prefix):
            continue
        first_tensor = len(tensors)
        if node.op_type in ("Conv", "ConvTranspose"):
            missing = [n for n in node.input[1:] if not held(n)]
            if missing:
                raise SystemExit(
                    f"{node.output[0]}: weight/bias {missing} is not a constant. "
                    "A computed weight means this is not the frozen export we lower."
                )
            weight = array(node.input[1])
            weight, spatial = lift_to_2d(weight, node)
            bias = array(node.input[2]) if len(node.input) > 2 else None
            if bias is None:
                # The shaders always add a bias, so a layer without one gets a zero. Exact,
                # and recorded in the key so the digest notices if a layer that used to have
                # a bias stops having one — which would otherwise look like the same table.
                channels = weight.shape[1] if node.op_type == "ConvTranspose" else weight.shape[0]
                bias = np.zeros(channels, dtype=np.float32)
                key = convolution_key(node, weight, bias, spatial) + " b0=synthesised"
            else:
                key = convolution_key(node, weight, bias, spatial)
            tensors.append(weight)
            tensors.append(bias)
        elif node.op_type == "PRelu":
            if not held(node.input[1]):
                raise SystemExit(f"{node.output[0]}: a computed PRelu slope")
            slope = array(node.input[1])
            key = f"PRelu s={list(slope.shape)}"
            tensors.append(slope)
        elif node.op_type == "Gemm":
            attrs = {a.name: a for a in node.attribute}
            alpha = attrs["alpha"].f if "alpha" in attrs else 1.0
            beta = attrs["beta"].f if "beta" in attrs else 1.0
            trans_a = attrs["transA"].i if "transA" in attrs else 0
            trans_b = attrs["transB"].i if "transB" in attrs else 0
            if (alpha, beta, trans_a, trans_b) != (1.0, 1.0, 0, 1):
                raise SystemExit(
                    f"{node.output[0]}: Gemm alpha={alpha} beta={beta} transA={trans_a} "
                    f"transB={trans_b}; only the plain `x @ W.T + b` form is lowered."
                )
            missing = [n for n in node.input[1:] if not held(n)]
            if missing:
                raise SystemExit(f"{node.output[0]}: Gemm operand {missing} is computed")
            weight = array(node.input[1])
            bias = array(node.input[2])
            batch_norm = batch_norms.get(node.output[0])
            if batch_norm is not None:
                weight, bias = fold_batch_norm(batch_norm, inits, weight, bias)
                folded.add(batch_norm.output[0])
            spatial = flatten_source_shape(node, graph, shapes)
            expected = int(np.prod(spatial))
            if weight.shape[1] != expected:
                raise SystemExit(
                    f"{node.output[0]}: Gemm weight is {list(weight.shape)} but its input "
                    f"flattens {spatial} = {expected} values"
                )
            weight = weight.reshape([weight.shape[0]] + spatial)
            key = (
                f"Gemm w={list(weight.shape)} b={list(bias.shape)} "
                f"bn={batch_norm is not None}"
            )
            tensors.append(weight)
            tensors.append(bias)
        elif node.op_type == "Gather" and held(node.input[0]) and array(node.input[0]).ndim == 2:
            # An embedding table. VITS multiplies the looked-up row by `sqrt(d_model)`, and
            # that scale belongs here rather than in a shader: it is one op fewer and one
            # rounding fewer, since `round(table * sqrt(d))` is closer than
            # `round(table) * sqrt(d)`.
            table = array(node.input[0]).astype(np.float64)
            table = table * math.sqrt(table.shape[1])
            key = f"Embed t={list(table.shape)} scaled=sqrt({table.shape[1]})"
            tensors.append(table)
        elif node.op_type == "Pad" and held(node.input[0]):
            # A relative position table, which the export pads out to `2T-1` entries and
            # then skews. `nets::vits_enc` reads the nine unpadded entries directly, so the
            # leading batch axis is dropped and the padding never happens.
            table = np.squeeze(array(node.input[0]))
            if table.ndim != 2 or table.shape[0] % 2 == 0:
                raise SystemExit(
                    f"{node.output[0]}: a padded constant of {list(np.shape(table))}; a "
                    "relative table is [2 * window + 1, head_dim]"
                )
            key = f"Relative t={list(table.shape)}"
            tensors.append(table)
        elif node.op_type == "Mul" and layer_norm_scale(node, consumers, held, array):
            # A layer norm's gamma and the beta on the `Add` after it. The export decomposes
            # the rest of the norm into ReduceMean/Sub/Pow/Sqrt/Div, none of which carries a
            # weight, so this pair is all there is to collect.
            gamma = next(array(n) for n in node.input if held(n))
            follow = consumers[node.output[0]][0]
            beta = next(array(n) for n in follow.input if held(n))
            key = f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}"
            tensors.append(gamma)
            tensors.append(beta)
        elif node.op_type == "BatchNormalization":
            # Folded above, when the layer it follows was emitted. One that was not is a
            # batch norm somewhere in the middle of the graph, which nothing here can
            # absorb and no shader implements.
            if node.output[0] not in folded:
                raise SystemExit(
                    f"{node.output[0]}: a BatchNormalization that does not follow a Gemm. "
                    "Only a trailing batch norm can be folded away."
                )
            continue
        else:
            continue
        layers.append(
            Layer(
                len(layers),
                node.op_type,
                node.output[0],
                key,
                first_tensor,
                len(tensors) - first_tensor,
            )
        )
    return layers, tensors


def layer_norm_scale(node, consumers, held, array):
    """Whether `node` is the `Mul(x, gamma)` of a decomposed layer norm.

    Recognised by structure rather than by name: a `Mul` against a rank-1 constant whose one
    consumer is an `Add` against a rank-1 constant of the same length. In Piper's encoder that
    matches exactly the twelve norms and nothing else — the mask multiplies and the embedding
    scale are against scalars or computed tensors.
    """
    scales = [n for n in node.input if held(n) and array(n).ndim == 1]
    if len(scales) != 1:
        return False
    follow = consumers.get(node.output[0], [])
    if len(follow) != 1 or follow[0].op_type != "Add":
        return False
    shifts = [
        n
        for n in follow[0].input
        if held(n) and array(n).ndim == 1 and array(n).size == array(scales[0]).size
    ]
    return len(shifts) == 1


def layer_table_digest(layers):
    h = hashlib.sha256()
    for layer in layers:
        h.update(layer.key().encode())
        h.update(b"\n")
    return h.hexdigest()


def check_graph(model, graph_id_name):
    graph = model.graph
    prefix = MODULES.get(graph_id_name)
    nodes = [n for n in graph.node if prefix is None or n.name.startswith(prefix)]

    if prefix is None:
        want = GEOMETRY[graph_id_name]
        names = [i.name for i in graph.input]
        if names != [want["input"]]:
            raise SystemExit(f"inputs are {names}, expected [{want['input']!r}]")
        dims = [
            d.dim_value or d.dim_param for d in graph.input[0].type.tensor_type.shape.dim
        ]
        # Batch may be symbolic. A spatial dim may be too, but only where the expected
        # shape says so: for the fixed-size nets every folded Resize target and pad below
        # was resolved at exactly this size, so a dynamic one would silently mislower.
        got = list(dims[1:])
        expect = want["shape"]
        mismatched = len(got) != len(expect) or any(
            e is not None and g != e for g, e in zip(got, expect)
        )
        if mismatched:
            raise SystemExit(f"input shape {dims}, expected [batch] + {expect}")

        outputs = [o.name for o in graph.output]
        absent = [o for o in want["outputs"] if o not in outputs]
        if absent:
            raise SystemExit(f"outputs are {outputs}, expected {absent} among them")
    elif not nodes:
        raise SystemExit(
            f"no nodes are named {prefix!r}. A module-scoped graph relies on the export "
            "keeping its Torch module paths, which this one has not."
        )

    counts = {}
    for node in nodes:
        counts[node.op_type] = counts.get(node.op_type, 0) + 1
    expected = EXPECTED_OPS[graph_id_name]
    if counts != expected:
        only_got = {k: v for k, v in counts.items() if expected.get(k) != v}
        only_want = {k: v for k, v in expected.items() if counts.get(k) != v}
        raise SystemExit(f"op counts differ: got {only_got}, expected {only_want}")


def quantised_linear(node, held, array, following_bias):
    """An int8 `MatMulInteger` as a `1 x 1` convolution, plus its scale and bias.

    Returns `(tensors, key)` or `None` if this is not one of the shapes we lower.

    # The transpose that would otherwise be silent

    ONNX `MatMul` computes `x @ W`, so its weight is **`[in, out]`**. This runtime indexes a
    kernel as `[out, in, kh, kw]`. SMaLL-100's attention projections are all 1024 x 1024, so a
    missed transpose would produce a tensor of exactly the right shape holding exactly the
    wrong numbers, and the parameter-count check every other net leans on would pass. `fc1` is
    `[1024, 4096]`, which is the one that would fail loudly - so the transpose is asserted
    against a non-square layer in the tests rather than trusted here.

    # What the export carries, and what is dropped

    Read from `casawolice/small100-onnx` rather than assumed:

    * The weight zero point is a **per-column vector and every entry is 0**, so the quantisation
      is symmetric and a value is `int8 * scale`. A non-zero one is refused rather than ignored.
    * The weight scale is **per output column** - a `[1024, 4096]` weight carries a `[4096]`
      scale. It still multiplies the finished accumulator rather than every tap, so
      `conv_int8.comp` costs the same as the fp16 path; it just indexes the scale by output
      channel. A scalar is accepted too, broadcast to one per column, so a per-tensor quantiser
      also converts.
    * The *activation* scale and zero point come from a `DynamicQuantizeLinear` at runtime.
      Both are dropped: activations stay fp16 here, which is simpler than reproducing dynamic
      quantisation and strictly more accurate, and costs nothing because the weights are what
      take the space.
    """
    if node.op_type != "MatMulInteger" or len(node.input) < 4:
        return None
    weight_name, zero_name = node.input[1], node.input[3]
    if not held(weight_name) or not held(zero_name):
        return None
    weight = array(weight_name)
    if weight.dtype != np.int8 or weight.ndim != 2:
        return None
    zero = np.array(array(zero_name)).flatten()
    if np.any(zero != 0):
        raise SystemExit(
            f"{node.output[0]}: weight zero point {zero[zero != 0][:4].tolist()} is not 0, so "
            "the quantisation is not symmetric and `conv_int8.comp` would be wrong"
        )
    scale_name = weight_name.removesuffix("_quantized") + "_scale"
    if not held(scale_name):
        raise SystemExit(f"{node.output[0]}: no {scale_name} beside its weight")
    scale = np.array(array(scale_name)).flatten()
    inputs, outputs = weight.shape
    if scale.size == 1:
        # A per-tensor quantiser. Broadcast rather than special-case it, so the `.maml` layout is
        # the same either way and the shader has one path.
        scale = np.repeat(scale, outputs)
    if scale.size != outputs:
        raise SystemExit(
            f"{node.output[0]}: a {scale.size}-element scale for {outputs} output columns; "
            "`conv_int8.comp` reads one per output channel"
        )
    # `[in, out]` to `[out, in, 1, 1]`.
    kernel = np.ascontiguousarray(weight.T).reshape(outputs, inputs, 1, 1)
    bias = following_bias(node, outputs)
    key = (
        f"MatMulInteger w={list(kernel.shape)} scale={[outputs]} b={[outputs]} "
        f"zp=0 dtype=int8"
    )
    return [kernel, scale.astype(np.float32), bias], key


def quantise_per_channel(kernel):
    """An fp32 kernel as `(int8, scale)`, symmetric and absmax, one scale per output channel.

    The counterpart of [`quantised_linear`], which only ever *validated* an export that had
    already been quantised by onnxruntime. Supertonic's exports are fp32, so quantising them is
    something this script has to do rather than read off.

    # Symmetric, with no zero point

    `conv_int8.comp` and `conv_point_int8.comp` compute `scale[o] * sum(int8_w * in)`: there is
    nowhere for a zero point to go, and adding one would cost a second pass over the taps to
    subtract `zp * sum(in)`. So the scale is `absmax / 127` and 0 maps to 0, which is also what
    onnxruntime's dynamic quantiser was measured to emit for SMaLL-100 — every entry of every
    weight zero point in that export is 0.

    127 rather than 128: the range is made symmetric by giving up the one extra negative code, so
    that `-absmax` and `+absmax` both round to a representable value. Using 128 would let a
    weight at exactly `+absmax` quantise to 128, which does not exist in int8.

    # Per output channel, not per tensor

    A row of the kernel is one output channel, and the scale multiplies that row's finished
    accumulator, so a scale per row costs nothing over a scalar — see `Builder::conv_int8`. It
    buys a great deal: a layer whose channels differ in range by 100x loses most of its
    resolution on the quiet ones under a single tensor-wide scale.

    # An all-zero channel

    Its absmax is 0, and `0 / 127` is a scale of 0, which is *correct* — every weight in the row
    is 0, so any scale reproduces it — but it is also a division by zero when computing the
    codes. Those rows are given a scale of 1 instead, which is exact for the same reason and is
    finite.
    """
    kernel = np.ascontiguousarray(kernel, dtype=np.float32)
    if kernel.ndim < 1:
        raise SystemExit(f"rank {kernel.ndim} kernel; quantisation needs an output axis")
    if not np.isfinite(kernel).all():
        raise SystemExit("a kernel holding a non-finite value cannot be quantised")
    rows = kernel.reshape(kernel.shape[0], -1)
    absmax = np.abs(rows).max(axis=1)
    scale = np.where(absmax > 0, absmax / 127.0, 1.0).astype(np.float32)
    # Round half away from zero, as `np.round` does not: it rounds half to even, which biases a
    # symmetric weight distribution towards the even codes.
    codes = np.floor(np.abs(rows) / scale[:, None] + 0.5) * np.sign(rows)
    quantised = np.clip(codes, -127, 127).astype(np.int8).reshape(kernel.shape)
    # The scale is stored as fp16 beside the weights, so the reconstruction the shader performs
    # uses the *rounded* scale. Rounding it here means the error reported below is the error the
    # device will have, not an optimistic one.
    scale = scale.astype(np.float16).astype(np.float32)
    return quantised, scale


def build(layers, tensors, graph_id, onnx_sha256):
    """Serialise the tensor table and the data section.

    A tensor is fp16 unless it arrives as `int8`, in which case its bytes go through
    unchanged and the entry records [`DTYPE_I8`]. Its scale is a separate fp16 tensor that the
    caller has already placed after it.
    """
    table = bytearray()
    data = bytearray()
    for tensor in tensors:
        if tensor.ndim < 1 or tensor.ndim > 4:
            raise SystemExit(f"rank {tensor.ndim} tensor; the table holds 1..4")
        while len(data) % ALIGNMENT:
            data.append(0)
        offset = len(data)
        if tensor.dtype == np.int8:
            # Already quantised, so nothing to round: the bytes are the payload.
            dtype = DTYPE_I8
            data.extend(tensor.tobytes())
        else:
            dtype = DTYPE_F16
            half = np.ascontiguousarray(tensor, dtype=np.float32).astype(np.float16)
            if not np.isfinite(half).all():
                raise SystemExit(
                    f"a weight of shape {list(tensor.shape)} overflows fp16. "
                    "Storing this net at half precision needs per-tensor scaling."
                )
            data.extend(half.tobytes())
        dims = list(tensor.shape) + [0] * (4 - tensor.ndim)
        table.extend(struct.pack("<I", tensor.ndim))
        table.extend(struct.pack("<4I", *dims))
        table.extend(struct.pack("<III", dtype, offset, tensor.size))
    assert len(table) == len(tensors) * TENSOR_ENTRY_BYTES

    data_offset = HEADER_BYTES + len(table)
    header = bytearray()
    header.extend(MAGIC)
    header.extend(struct.pack("<III", FORMAT_VERSION, graph_id, len(tensors)))
    header.extend(onnx_sha256)
    header.extend(struct.pack("<II", data_offset, len(data)))
    header.extend(b"\0" * 8)
    assert len(header) == HEADER_BYTES

    return bytes(header) + bytes(table) + bytes(data), len(layers)


def main():
    parser = argparse.ArgumentParser(description=SPEC.splitlines()[0])
    parser.add_argument("onnx")
    parser.add_argument("--graph", required=True, choices=sorted(GRAPHS))
    parser.add_argument("-o", "--out", help="where to write the .maml")
    parser.add_argument("--check", action="store_true", help="validate only")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    with open(args.onnx, "rb") as f:
        raw = f.read()
    onnx_sha256 = hashlib.sha256(raw).digest()

    model = onnx.load(args.onnx)
    check_graph(model, args.graph)
    layers, tensors = collect_layers(model, MODULES.get(args.graph))

    digest = layer_table_digest(layers)
    if args.print_digest:
        print(f'    "{args.graph}": "{digest}",')
    pinned = EXPECTED_DIGEST[args.graph]
    if digest != pinned:
        message = (
            f"layer table digest {digest}\n"
            f"           pinned  {pinned}\n"
            "The ordered Conv/ConvTranspose table changed, so the hardcoded forward\n"
            f"pass in library/ml/src/main/rust/src/nets/{args.graph}.rs no longer\n"
            "matches this export. Re-read it with --print-layers, update the Rust,\n"
            "then re-pin with --print-digest."
        )
        if not args.print_digest:
            raise SystemExit(message)
        print(message, file=sys.stderr)

    if args.print_layers:
        for layer in layers:
            print(f"{layer.index:4d} t{layer.first_tensor:<4d} {layer.key()}  # {layer.name}")

    blob, count = build(layers, tensors, GRAPHS[args.graph], onnx_sha256)
    if args.out:
        with open(args.out, "wb") as f:
            f.write(blob)
    print(
        f"{args.graph}: {count} layers, {len(tensors)} tensors, "
        f"{len(blob)} bytes ({len(raw)} fp32 in)"
    )


if __name__ == "__main__":
    main()
