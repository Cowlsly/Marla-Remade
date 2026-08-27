#!/usr/bin/env python3
"""ONNX to `.vkml`, the weights container `:library:ml` reads.

`:library:ml` is not a graph interpreter: each network's forward pass is hardcoded
Rust in `library/ml/src/main/rust/src/nets/`. So a `.vkml` file carries **only
ordered tensors** — no operators, no topology, no names. The Rust net module
indexes the tensor table positionally.

That makes tensor *order* the entire contract between this script and the Rust,
and a reordering would be silently wrong rather than a load failure. So the order
is defined mechanically and pinned:

* Order is `Conv`/`ConvTranspose` nodes in ONNX topological order, weight then
  bias. Nothing else is emitted — every other initializer in these two graphs is
  shape-computation scaffolding that folds away at a fixed input size.
* `--check` hashes the whole ordered layer table (shapes *and* the attributes the
  Rust hardcodes: dilation, pads, strides, group) into one SHA-256 pinned below.
  Any upstream re-export that renames, reorders, retunes or re-pads a single layer
  changes that hash and fails, instead of producing a file that loads and infers
  nonsense.

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
from onnx import numpy_helper

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
}

# SHA-256 over the ordered layer table (see `layer_table_digest`). Regenerate with
# --print-digest after deliberately re-pointing at a new upstream export.
EXPECTED_DIGEST = {
    "selfie": "6db422ecfe0af8e37591eb14c195166b2c416649bf9063100bd5bb14eafaad3a",
    "u2netp": "f5199e05e4d4e93a6af601d04e3e0516bb68b9f292bb81d474704ea0b4e591d0",
}

# What each graph is exported at. Both nets are fixed-shape by construction; the
# u2netp export is dynamic-shape and its Shape/Gather/Slice/Cast scaffolding only
# folds away at exactly this size, so it is asserted rather than assumed.
GEOMETRY = {
    "selfie": {"input": "pixel_values", "output": "alphas", "size": 256},
    "u2netp": {"input": "input.1", "output": "1959", "size": 320},
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
}


class Layer:
    """One `Conv`/`ConvTranspose`, as the Rust forward pass needs to see it."""

    def __init__(self, index, node, weight, bias):
        attrs = {a.name: a for a in node.attribute}

        def ints(name, default):
            return list(attrs[name].ints) if name in attrs else list(default)

        self.index = index
        self.op = node.op_type
        self.name = node.output[0]
        self.weight_shape = list(weight.shape)
        self.bias_shape = list(bias.shape) if bias is not None else None
        self.kernel = ints("kernel_shape", weight.shape[2:])
        self.dilations = ints("dilations", (1, 1))
        # ONNX orders pads [top, left, bottom, right].
        self.pads = ints("pads", (0, 0, 0, 0))
        self.strides = ints("strides", (1, 1))
        self.group = attrs["group"].i if "group" in attrs else 1

    def key(self):
        """Everything the Rust hardcodes about this layer, as a stable string."""
        return (
            f"{self.op} w={self.weight_shape} b={self.bias_shape} "
            f"k={self.kernel} d={self.dilations} p={self.pads} "
            f"s={self.strides} g={self.group}"
        )


def collect_layers(graph):
    """Conv/ConvTranspose in topological order, with their weight and bias."""
    inits = {i.name: i for i in graph.initializer}
    layers = []
    tensors = []
    for node in graph.node:
        if node.op_type not in ("Conv", "ConvTranspose"):
            continue
        missing = [n for n in node.input[1:] if n not in inits]
        if missing:
            raise SystemExit(
                f"{node.output[0]}: weight/bias {missing} is not an initializer. "
                "A computed weight means this is not the frozen export we lower."
            )
        weight = numpy_helper.to_array(inits[node.input[1]])
        bias = numpy_helper.to_array(inits[node.input[2]]) if len(node.input) > 2 else None
        if bias is None:
            raise SystemExit(
                f"{node.output[0]}: no bias. The shaders always add one, so a "
                "bias-less layer would need a zero tensor synthesised here."
            )
        layers.append(Layer(len(layers), node, weight, bias))
        tensors.append(weight)
        tensors.append(bias)
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
    size = want["size"]

    names = [i.name for i in graph.input]
    if names != [want["input"]]:
        raise SystemExit(f"inputs are {names}, expected [{want['input']!r}]")
    dims = [d.dim_value or d.dim_param for d in graph.input[0].type.tensor_type.shape.dim]
    # Batch may be symbolic; the spatial dims may not be, because every folded
    # Resize target and pad below was resolved at this exact size.
    if list(dims[1:]) != [3, size, size]:
        raise SystemExit(f"input shape {dims}, expected [batch, 3, {size}, {size}]")

    outputs = [o.name for o in graph.output]
    if want["output"] not in outputs:
        raise SystemExit(f"outputs are {outputs}, expected {want['output']!r} among them")

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
    layers, tensors = collect_layers(model.graph)

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
            print(f"{layer.index:4d} {layer.key()}  # {layer.name}")

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
