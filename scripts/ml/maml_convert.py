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

One graph does not come from an ONNX export at all. SMaLL-100 is read from its PyTorch
`.safetensors` and quantised to int8 here, because the only ONNX exports that exist for it are
already int8 and quantised per *tensor*; see [`CHECKPOINTS`] for why that is not usable.

    ./scripts/ml/maml_convert.py --check model.onnx --graph u2netp
    ./scripts/ml/maml_convert.py model.onnx --graph u2netp -o out.maml
    ./scripts/ml/maml_convert.py model.onnx --graph u2netp --print-layers
    ./scripts/ml/maml_convert.py model.safetensors --graph small100 -o out.maml
"""

import argparse
import hashlib
import math
import os
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
#    16  32   source SHA-256 of the ONNX or checkpoint, so a shipped asset traces upstream
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

# The floor for `cosine(fp32 weight, int8 * scale)` per tensor. Supertonic's text encoder was
# reverted to fp16 at 0.99212 while its sampler shipped at 0.99997, so this is set where a layer
# that quantises this badly is worth looking at rather than shipping.
MIN_INT8_COSINE = 0.999

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
    # 7..10 were Piper's four graphs — vocoder, encoder, flow and duration predictor —
    # deleted when Supertonic replaced them. The numbers are **not** reused: an id identifies
    # a forward pass, and a `.maml` built for the old vocoder must be rejected rather than
    # loaded as whatever took its slot.
    "supertonic_voc": 11,
    "supertonic_dp": 12,
    "supertonic_ttl": 13,
    "supertonic_ve": 14,
    # Read from a PyTorch checkpoint rather than an ONNX graph; see [`CHECKPOINTS`].
    "small100": 15,
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
    "supertonic_voc": "5282083898554b1fae28de946bfc1a82a11e2acddf3911877f27b1b115c583aa",
    "supertonic_dp": "d9a548e1f1ed908a3b0ad27ab543dd7521858f9110b426edb1ed64ba1f1a13e4",
    "supertonic_ttl": "92d0fa8ae73d4a8a223c77fb4230e390df580744014a3fcf3211ab72a988c020",
    "supertonic_ve": "48ab5789e8839507ff93e099dc0f61940f25da9ba6201651784f6e68598199e2",
    "small100": "c9f05f2fb686aa53137ca06b521baf6d7a00f32cfb4ea42bd14a5c3e7cf6c086",
}

# Graphs that are one **module** of a larger export, keyed by the node-name prefix that
# selects it.
#
# Empty at present: every graph here is exported as its own file. The mechanism stays because
# an export that packs several forward passes into one graph is not convertible as one plan,
# and a Torch export keeps its module paths - so it partitions along the lines the model was
# written in, and each static module converts independently.
#
# A module-scoped graph has no GEOMETRY entry: its input is another module's output rather
# than a graph input, so there is nothing at the boundary to check a shape against. What is
# checked instead is the op inventory within the module, which is the same guard by another
# route.
MODULES = {}

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
}

# Graphs read from a **PyTorch checkpoint** rather than an ONNX graph, keyed by graph id name.
#
# Every other net here converts from a frozen ONNX export, and for good reason: the graph records
# the shapes and the op inventory, so `check_graph` can refuse an export whose structure moved
# without anyone transcribing anything. A checkpoint has neither. It is used anyway for SMaLL-100,
# because the only ONNX exports that exist for it are already int8:
#
# * `casawolice/small100-onnx` quantises **per tensor**, not per output channel. Its embedding's
#   row absmax spans 0.258 to 1.740, so one scale for the whole table costs a factor of four in
#   resolution against the per-row scale `quantise_per_channel` produces, and up to 6.7x on the
#   quietest rows. That resolution cannot be recovered by requantising: it is already gone.
# * It carries the 131M-parameter embedding **three times** - `embed_tokens.weight` in the
#   encoder, `model.shared.weight` in the decoder and a separately quantised `[1024, 128112]`
#   `lm_head` - which is why that deployment set is 609 MB rather than 333 MB. In the checkpoint
#   the weight is tied and appears exactly once, so the tied head costs nothing.
# * Its two embedding copies are **uint8 with a zero point of 159**. `conv_point_int8.comp`
#   computes `scale[o] * sum(int8_w * in)` and has nowhere to put a zero point.
#
# What is checked instead is the **exact parameter inventory** - every name and every shape,
# derived from the constants below rather than listed - which is a stricter guard than the op
# counts an ONNX graph gets, because it fails on a renamed or resized parameter as well as on a
# missing one. The layer table digest still covers the order.
CHECKPOINTS = {
    "small100": {
        "d_model": 1024,
        "encoder_layers": 12,
        "decoder_layers": 3,
        "ffn": 4096,
        "vocab": 128_112,
        # The tied embedding is 125.1 MiB of int8, and `maxStorageBufferRange`'s guaranteed
        # minimum is 128 MiB. Emitting it as two tensors over disjoint class ranges keeps every
        # binding comfortably under that floor and gives `nets::small100` its two logits ops for
        # free; 128,112 is 2 x 64,056, so the halves are equal and no range is padded.
        "head_splits": 2,
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
    whose kernel is `1 x k` — which is what every convolution in `nets::supertonic_vocoder`
    reads. Inserting the height axis is a reshape of the same bytes in the same order, and
    doing it here means the Rust reads `[out, in, 1, k]` like every other kernel rather than
    carrying a special case for audio.

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
            # An embedding table. An export that multiplies the looked-up row by
            # `sqrt(d_model)` has that scale folded in here rather than left to a shader: it
            # is one op fewer and one rounding fewer, since `round(table * sqrt(d))` is
            # closer than `round(table) * sqrt(d)`.
            table = array(node.input[0]).astype(np.float64)
            table = table * math.sqrt(table.shape[1])
            key = f"Embed t={list(table.shape)} scaled=sqrt({table.shape[1]})"
            tensors.append(table)
        elif node.op_type == "Pad" and held(node.input[0]):
            # A relative position table, which the export pads out to `2T-1` entries and
            # then skews. `nets::supertonic_text` and `nets::supertonic_duration` read the
            # nine unpadded entries directly, so the leading batch axis is dropped and the
            # padding never happens.
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
    consumer is an `Add` against a rank-1 constant of the same length. In the exports this reads
    that matches the layer norms and nothing else - mask multiplies and embedding scales are
    against scalars or computed tensors.
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


def open_checkpoint(path):
    """`(name -> ndarray, {name: shape})` for a `.safetensors` file, read one tensor at a time.

    Lazily, because the checkpoint is 1.33 GB of fp32 and every tensor is quantised to int8 or
    rounded to fp16 the moment it is read - so holding the whole thing costs 1.3 GB of resident
    host memory for nothing.
    """
    try:
        from safetensors import safe_open
    except ImportError:
        raise SystemExit("needs safetensors: python -m pip install safetensors")
    handle = safe_open(path, framework="np")
    shapes = {name: list(handle.get_slice(name).get_shape()) for name in handle.keys()}
    return handle.get_tensor, shapes


def small100_inventory(spec):
    """`{name: shape}` the checkpoint must hold exactly, derived from [`CHECKPOINTS`].

    Derived rather than transcribed: a list of 275 names would be checked against itself.
    """
    d, ffn = spec["d_model"], spec["ffn"]
    want = {"model.shared.weight": [spec["vocab"], d]}
    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        want[f"model.{side}.layer_norm.weight"] = [d]
        want[f"model.{side}.layer_norm.bias"] = [d]
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            attentions = ["self_attn"] + (["encoder_attn"] if side == "decoder" else [])
            for attention in attentions:
                want[f"{at}.{attention}_layer_norm.weight"] = [d]
                want[f"{at}.{attention}_layer_norm.bias"] = [d]
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    want[f"{at}.{attention}.{projection}.weight"] = [d, d]
                    want[f"{at}.{attention}.{projection}.bias"] = [d]
            want[f"{at}.final_layer_norm.weight"] = [d]
            want[f"{at}.final_layer_norm.bias"] = [d]
            want[f"{at}.fc1.weight"] = [ffn, d]
            want[f"{at}.fc1.bias"] = [ffn]
            want[f"{at}.fc2.weight"] = [d, ffn]
            want[f"{at}.fc2.bias"] = [d]
    return want


def check_checkpoint(shapes, graph_id_name):
    """Refuse a checkpoint that is not the architecture the Rust forward pass hardcodes.

    # The transpose that would otherwise be silent

    A `torch.nn.Linear`'s weight is **`[out, in]`**, and this runtime indexes a kernel as
    `[out, in, kh, kw]`, so a projection needs a reshape and no transpose. ONNX `MatMul` is the
    other way round - `x @ W` with a `[in, out]` weight - and every attention projection here is
    square 1024 x 1024, so reading one convention as the other yields a tensor of exactly the
    right shape holding exactly the wrong numbers, and a parameter count that still adds up.

    `fc1` is `[4096, 1024]` and `fc2` is `[1024, 4096]`, so the shape check below is what makes
    that mistake loud: a transposed checkpoint fails on those four numbers before anything is
    written. That is the whole reason this asserts shapes rather than just names.
    """
    want = small100_inventory(CHECKPOINTS[graph_id_name])
    missing = sorted(set(want) - set(shapes))
    extra = sorted(set(shapes) - set(want))
    shared = sorted(set(want) & set(shapes))
    wrong = {n: (shapes[n], want[n]) for n in shared if shapes[n] != want[n]}
    if missing or extra or wrong:
        lines = []
        if missing:
            lines.append(f"  absent: {missing[:6]}{' ...' if len(missing) > 6 else ''}")
        if extra:
            lines.append(f"  unexpected: {extra[:6]}{' ...' if len(extra) > 6 else ''}")
        for name, (got, expect) in list(wrong.items())[:6]:
            lines.append(f"  {name}: {got} against {expect}")
        raise SystemExit(
            f"{graph_id_name}: the checkpoint is not the architecture nets/{graph_id_name}.rs\n"
            "hardcodes. A wrong shape here is a wrong forward pass, not a load failure:\n"
            + "\n".join(lines)
        )


def collect_small100(get, spec):
    """SMaLL-100's checkpoint as the ordered tensor table `nets::small100` indexes.

    Order is the contract, and it is:

        the tied embedding, as `head_splits` disjoint class ranges
        each encoder layer, in forward order
        the encoder's final layer norm
        each decoder layer, in forward order
        the decoder's final layer norm

    The **tied embedding comes first and appears once**, because it has two roles and neither
    wants a second copy. `nets::small100` gathers row `t` of it on the host to build the encoder
    and decoder inputs - the rows are contiguous, and each carries its own scale, which is exactly
    what a per-output-channel quantiser produces - and binds the same tensors as the kernel of the
    logits projection. Splitting it by class range is what keeps that binding under
    `maxStorageBufferRange`; see [`CHECKPOINTS`].

    # What is folded, and what is not

    * `sqrt(d_model)` is **not** folded into the embedding here, unlike the fp16 `Gather` arm in
      [`collect_layers`]. The host gather applies it while it is still fp32, which is one rounding
      fewer than scaling an int8 code by its scale and then by `sqrt(1024)`.
    * The attention query scale is **not** folded into `q_proj`. M2M-100 scales the query by
      `head_dim ** -0.5` and `head_dim` is 64, which is the `1 / sqrt(head_dim)` `attn_scores`
      already applies. Folding it would apply it twice.
    * Sinusoidal positions are not in the checkpoint and are not emitted. They are static, so
      `nets::small100` computes them and adds them to the gathered rows in fp32, which also
      removes the `pos_weights.f32.bin` the ncnn build had to download.
    """
    layers = []
    tensors = []
    # Worst per-tensor cosine between the fp32 weight and `int8 * scale`, which is the numbers
    # the device will read. This is the only check that the quantisation itself is usable; the
    # shape assertions in [`check_checkpoint`] are what catch a transpose, since a cosine is
    # computed against whatever was read and a transposed read agrees with itself perfectly.
    fidelity = []

    def quantise(name, weight):
        kernel, scale = quantise_per_channel(weight)
        back = kernel.astype(np.float32).reshape(weight.shape[0], -1) * scale[:, None]
        flat = weight.reshape(weight.shape[0], -1)
        cosine = float(
            np.dot(flat.ravel(), back.ravel())
            / max(np.linalg.norm(flat) * np.linalg.norm(back), 1e-30)
        )
        fidelity.append((cosine, name))
        return kernel, scale

    def emit(op, name, key, added):
        layers.append(Layer(len(layers), op, name, key, len(tensors) - added, added))

    def linear(name):
        """An int8 kernel as `[out, in, 1, 1]`, its per-output-channel scale, and its bias."""
        weight = get(f"{name}.weight")
        bias = get(f"{name}.bias")
        outputs, inputs = weight.shape
        kernel, scale = quantise(name, weight.reshape(outputs, inputs, 1, 1))
        tensors.extend([kernel, scale, bias])
        emit(
            "Linear8",
            name,
            f"Linear8 w={list(kernel.shape)} scale={[outputs]} b={list(bias.shape)} "
            "zp=0 dtype=int8",
            3,
        )

    def layer_norm(name):
        gamma = get(f"{name}.weight")
        beta = get(f"{name}.bias")
        tensors.extend([gamma, beta])
        emit("LayerNorm", name, f"LayerNorm g={list(gamma.shape)} b={list(beta.shape)}", 2)

    embedding = get("model.shared.weight")
    splits = spec["head_splits"]
    classes, width = embedding.shape
    if classes % splits:
        raise SystemExit(f"{classes} classes do not divide into {splits} equal ranges")
    step = classes // splits
    for part in range(splits):
        lo = part * step
        rows = embedding[lo : lo + step]
        name = f"model.shared.weight[{lo}:{lo + step}]"
        kernel, scale = quantise(name, rows.reshape(step, width, 1, 1))
        # `Builder::conv_int8` reads a bias after the scale, and a tied head has none. Zeros are
        # exact, and recording it in the key means a checkpoint that grows one is not mistaken for
        # this table.
        tensors.extend([kernel, scale, np.zeros(step, dtype=np.float32)])
        emit(
            "Head",
            name,
            f"Head w={list(kernel.shape)} scale={[step]} b={[step]} "
            f"classes={lo}..{lo + step} zp=0 dtype=int8 b0=synthesised",
            3,
        )

    for side, count in (("encoder", spec["encoder_layers"]), ("decoder", spec["decoder_layers"])):
        for index in range(count):
            at = f"model.{side}.layers.{index}"
            # Pre-norm, as `M2M100EncoderLayer` and `M2M100DecoderLayer` are: the norm comes
            # before the sublayer it feeds and the residual skips both. Emitting in forward order
            # is what lets the Rust walk the table without an index table.
            layer_norm(f"{at}.self_attn_layer_norm")
            for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                linear(f"{at}.self_attn.{projection}")
            if side == "decoder":
                layer_norm(f"{at}.encoder_attn_layer_norm")
                for projection in ("q_proj", "k_proj", "v_proj", "out_proj"):
                    linear(f"{at}.encoder_attn.{projection}")
            layer_norm(f"{at}.final_layer_norm")
            linear(f"{at}.fc1")
            linear(f"{at}.fc2")
        layer_norm(f"model.{side}.layer_norm")

    fidelity.sort()
    for cosine, name in fidelity[:3]:
        if cosine < MIN_INT8_COSINE:
            raise SystemExit(
                f"{name}: int8 * scale correlates {cosine:.6f} with the fp32 weight, under"
                f" {MIN_INT8_COSINE}.\nQuantising this layer costs too much; exclude it and store"
                " it fp16 instead."
            )
    worst = ", ".join(f"{name} {cosine:.6f}" for cosine, name in fidelity[:3])
    print(f"int8 fidelity over {len(fidelity)} tensors, worst three: {worst}")
    return layers, tensors


def quantise_per_channel(kernel):
    """An fp32 kernel as `(int8, scale)`, symmetric and absmax, one scale per output channel.

    Every int8 tensor in this tree goes through here. Nothing reads a pre-quantised export:
    Supertonic's are fp32, and SMaLL-100's only int8 export quantises per tensor, which throws
    away the resolution this recovers - see [`CHECKPOINTS`].

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
    parser.add_argument("model", help="an ONNX export, or a .safetensors for a CHECKPOINTS graph")
    parser.add_argument("--graph", required=True, choices=sorted(GRAPHS))
    parser.add_argument("-o", "--out", help="where to write the .maml")
    parser.add_argument("--check", action="store_true", help="validate only")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    source_sha256 = hashlib.sha256()
    with open(args.model, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            source_sha256.update(block)
    source_size = os.path.getsize(args.model)

    if args.graph in CHECKPOINTS:
        get, shapes = open_checkpoint(args.model)
        check_checkpoint(shapes, args.graph)
        layers, tensors = collect_small100(get, CHECKPOINTS[args.graph])
    else:
        model = onnx.load(args.model)
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

    blob, count = build(layers, tensors, GRAPHS[args.graph], source_sha256.digest())
    if args.out:
        with open(args.out, "wb") as f:
            f.write(blob)
    print(
        f"{args.graph}: {count} layers, {len(tensors)} tensors, "
        f"{len(blob)} bytes ({source_size} fp32 in)"
    )


if __name__ == "__main__":
    main()
