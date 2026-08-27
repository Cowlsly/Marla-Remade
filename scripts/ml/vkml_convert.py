#!/usr/bin/env python3
"""ONNX to `.vkml`, the weights container `:library:ml` reads.

`:library:ml` is not a graph interpreter: each network's forward pass is hardcoded
Rust in `library/ml/src/main/rust/src/nets/`. So a `.vkml` file carries **only
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

    ./scripts/ml/vkml_convert.py --check model.onnx --graph u2netp
    ./scripts/ml/vkml_convert.py model.onnx --graph u2netp -o out.vkml
    ./scripts/ml/vkml_convert.py model.onnx --graph u2netp --print-layers
"""

import argparse
import hashlib
import struct
import sys

import numpy as np
import onnx
from onnx import numpy_helper, shape_inference

# --- .vkml ---------------------------------------------------------------------
#
# One contiguous blob, so the whole file becomes one VkBuffer and one staging
# upload. Little-endian throughout; every device we target is.
#
#   header, 64 bytes
#     0   4   magic b"VKML"
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
MAGIC = b"VKML"
FORMAT_VERSION = 1
HEADER_BYTES = 64
TENSOR_ENTRY_BYTES = 32
DTYPE_F16 = 0
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
    "vits_dec": "",
}

# What each graph is exported at, as `[channels, height, width]` after the batch
# dimension. `None` is a dimension the export leaves symbolic, which only SCRFD does:
# it runs at 640 on the long side and whatever the short side pads up to, so baking a
# size in would bake in one photo's aspect ratio.
#
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


class Layer:
    """One weighted node, as the Rust forward pass needs to see it."""

    def __init__(self, index, op, name, key, first_tensor, tensor_count):
        self.index = index
        self.op = op
        self.name = name
        self._key = key
        # Where this layer's tensors start in the `.vkml` table, and how many it has.
        # The Rust indexes by tensor, and with `PRelu` in the mix that is no longer
        # `2 * layer` — so --print-layers reports it rather than leaving a transcriber
        # to count.
        self.first_tensor = first_tensor
        self.tensor_count = tensor_count

    def key(self):
        """Everything the Rust hardcodes about this layer, as a stable string."""
        return self._key


def convolution_key(node, weight, bias):
    """The digest key for a `Conv`/`ConvTranspose`.

    Byte-for-byte the string the first version of this script produced, so the pinned
    selfie and u2netp digests survive every later addition here.
    """
    attrs = {a.name: a for a in node.attribute}

    def ints(name, default):
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


def collect_layers(model):
    """The weighted nodes in topological order, with the tensors each contributes."""
    graph = model.graph
    inits = {i.name: i for i in graph.initializer}
    shapes = inferred_shapes(model)
    array = lambda name: numpy_helper.to_array(inits[name])  # noqa: E731

    # Which node's output each BatchNormalization consumes, so the layer producing it
    # can absorb it.
    batch_norms = {n.input[0]: n for n in graph.node if n.op_type == "BatchNormalization"}
    folded = set()

    layers = []
    tensors = []
    for node in graph.node:
        first_tensor = len(tensors)
        if node.op_type in ("Conv", "ConvTranspose"):
            missing = [n for n in node.input[1:] if n not in inits]
            if missing:
                raise SystemExit(
                    f"{node.output[0]}: weight/bias {missing} is not an initializer. "
                    "A computed weight means this is not the frozen export we lower."
                )
            weight = array(node.input[1])
            bias = array(node.input[2]) if len(node.input) > 2 else None
            if bias is None:
                raise SystemExit(
                    f"{node.output[0]}: no bias. The shaders always add one, so a "
                    "bias-less layer would need a zero tensor synthesised here."
                )
            key = convolution_key(node, weight, bias)
            tensors.append(weight)
            tensors.append(bias)
        elif node.op_type == "PRelu":
            if node.input[1] not in inits:
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
            missing = [n for n in node.input[1:] if n not in inits]
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


def layer_table_digest(layers):
    h = hashlib.sha256()
    for layer in layers:
        h.update(layer.key().encode())
        h.update(b"\n")
    return h.hexdigest()


def check_graph(model, graph_id_name):
    graph = model.graph
    want = GEOMETRY[graph_id_name]

    names = [i.name for i in graph.input]
    if names != [want["input"]]:
        raise SystemExit(f"inputs are {names}, expected [{want['input']!r}]")
    dims = [d.dim_value or d.dim_param for d in graph.input[0].type.tensor_type.shape.dim]
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

    counts = {}
    for node in graph.node:
        counts[node.op_type] = counts.get(node.op_type, 0) + 1
    expected = EXPECTED_OPS[graph_id_name]
    if counts != expected:
        only_got = {k: v for k, v in counts.items() if expected.get(k) != v}
        only_want = {k: v for k, v in expected.items() if counts.get(k) != v}
        raise SystemExit(f"op counts differ: got {only_got}, expected {only_want}")


def build(layers, tensors, graph_id, onnx_sha256):
    """Serialise the tensor table and the fp16 data section."""
    table = bytearray()
    data = bytearray()
    for tensor in tensors:
        if tensor.ndim < 1 or tensor.ndim > 4:
            raise SystemExit(f"rank {tensor.ndim} tensor; the table holds 1..4")
        while len(data) % ALIGNMENT:
            data.append(0)
        offset = len(data)
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
        table.extend(struct.pack("<III", DTYPE_F16, offset, tensor.size))
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
    parser.add_argument("-o", "--out", help="where to write the .vkml")
    parser.add_argument("--check", action="store_true", help="validate only")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    with open(args.onnx, "rb") as f:
        raw = f.read()
    onnx_sha256 = hashlib.sha256(raw).digest()

    model = onnx.load(args.onnx)
    check_graph(model, args.graph)
    layers, tensors = collect_layers(model)

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
