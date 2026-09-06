#!/usr/bin/env python3
"""Golden for Gemma 4's vision tower: onnxruntime's answer on a reproducible image.

`examples/check_gemma4_vision_parity.rs` reads what this writes. Nothing else in the port
compares the vision tower's *numbers* against the export they came from - the layout tests check
that 637 tensors are declared in the order the converter writes them, which a transposed read
satisfies perfectly.

# The image is generated, not loaded

Both sides build the same pixels from `synthetic_pixel` below, so the golden carries no image and
the two cannot drift. The pattern is a hash rather than a gradient because a linear ramp is
nearly rank-one, and a rank-one input hides exactly the errors this is looking for.

# The image is not square, deliberately

A patch has a row and a column, and they are used four separate ways: two learned position
tables, and two independent rotary blocks inside every head. On a square grid, swapping either
pair changes nothing that any shape check or any norm would notice. On a 33x18 grid it changes
everything. See `nets::gemma4_vision::COLUMN_POSITIONS`.

# The control is the point

The device runs int4 weights and the export runs fp16 ones, so the two cannot agree closely and
the interesting question is never "how close is it" but "is it as close as four bits allow". This
runs the reference twice - as exported, and with every linear round-tripped through the
converter's own `quantise_per_block` - and writes both. The second is the bar.

Without it the numbers are unreadable. The tower's output lands at cosine 0.95 against the exact
reference no matter what, because sixteen layers amplify four-bit noise; measured against the
int4 reference instead, a correct device sits far higher.

# What it writes

    layer_in_0 .. layer_in_15   four probe patches each, 768 wide
    tower_out                   the tower before pooling
    pooled_in                   what reaches the final norm
    image_features              the whole output, [soft_tokens, 1536]

each with a `_rows` list of which patches were probed and an `_int4` control. The per-layer
entries are four patches rather than the whole map because the whole map is 1.7 M floats a layer,
and four is enough to bisect: an error of composition moves every patch.

    python scripts/ml/gemma4_vision_parity.py vision_encoder_fp16.onnx \\
        -o vision_golden.json --width 1600 --height 900 --soft-tokens 70
"""
import argparse
import json
import math
import os
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from maml_convert import I4_BLOCK, quantise_per_block  # noqa: E402

PATCH = 16
POOL = 3
SIDE_MULTIPLE = PATCH * POOL
LAYERS = 16
D_MODEL = 768


def synthetic_pixel(y: int, x: int, channel: int) -> int:
    """A reproducible byte for pixel `(y, x)`, channel `(0, 1, 2)` = `(r, g, b)`.

    Must stay bit-identical to `synthetic_pixel` in `check_gemma4_vision_parity.rs`. Everything
    is masked to 32 bits so Python's unbounded integers behave like Rust's `u32`.
    """
    mask = 0xFFFFFFFF
    h = (y * 73_856_093) ^ (x * 19_349_663) ^ (channel * 83_492_791)
    h &= mask
    h ^= h >> 13
    h = (h * 1_274_126_177) & mask
    h ^= h >> 16
    return h & 0xFF


def aspect_preserving_size(height, width, max_patches):
    """`get_aspect_ratio_preserving_size` from `Gemma4ImageProcessor`, ported verbatim."""
    target_px = max_patches * PATCH**2
    factor = math.sqrt(target_px / (height * width))
    target_height = int(math.floor(factor * height / SIDE_MULTIPLE)) * SIDE_MULTIPLE
    target_width = int(math.floor(factor * width / SIDE_MULTIPLE)) * SIDE_MULTIPLE
    max_side = (max_patches // POOL**2) * SIDE_MULTIPLE
    if target_height == 0 and target_width == 0:
        raise SystemExit(f"{width}x{height} resizes to nothing")
    if target_height == 0:
        target_height = SIDE_MULTIPLE
        target_width = min(int(math.floor(width / height)) * SIDE_MULTIPLE, max_side)
    elif target_width == 0:
        target_width = SIDE_MULTIPLE
        target_height = min(int(math.floor(height / width)) * SIDE_MULTIPLE, max_side)
    return target_height, target_width


def build_inputs(rows, cols):
    """`pixel_values [1, T, 768]` in [0, 1] and `pixel_position_ids [1, T, 2]` as (column, row).

    The patchify is `convert_image_to_patches`: `(C, H, W)` reshaped to `(C, nph, 16, npw, 16)`
    then permuted `(1, 3, 2, 4, 0)`, so a patch's 768 values run y, then x, then channel.
    """
    height, width = rows * PATCH, cols * PATCH
    y = np.arange(height, dtype=np.int64)[:, None, None]
    x = np.arange(width, dtype=np.int64)[None, :, None]
    c = np.arange(3, dtype=np.int64)[None, None, :]
    mask = 0xFFFFFFFF
    h = ((y * 73_856_093) ^ (x * 19_349_663) ^ (c * 83_492_791)) & mask
    h ^= h >> 13
    h = (h * 1_274_126_177) & mask
    h ^= h >> 16
    image = (h & 0xFF).astype(np.float32) / 255.0  # (H, W, C)

    patches = image.reshape(rows, PATCH, cols, PATCH, 3).transpose(0, 2, 1, 3, 4)
    pixel_values = patches.reshape(rows * cols, PATCH * PATCH * 3).astype(np.float32)

    # meshgrid(arange(width), arange(height), indexing="xy") stacked last: (column, row).
    columns = np.tile(np.arange(cols, dtype=np.int64), rows)
    rows_ids = np.repeat(np.arange(rows, dtype=np.int64), cols)
    position_ids = np.stack([columns, rows_ids], axis=-1)
    return pixel_values[None, ...], position_ids[None, ...]


def numbered(node, prefix):
    """The index in `prefix`, `prefix_1`, ... - the same anchor `collect_gemma4_vision` uses."""
    name = (node.name or "").replace("_fused_rms_norm/node_mean", "node_mean")
    if not name.startswith(prefix):
        return None
    tail = name[len(prefix):]
    if tail == "":
        return 0
    return int(tail[1:]) if tail.startswith("_") and tail[1:].isdigit() else None


def trace_points(model):
    """`{label: tensor_name}`: the input to layers 0, 1 and 8, the tower's output, the pooled map.

    Layer `i`'s seven norms are `node_mean_{7i}` .. `node_mean_{7i+6}`, so the input to layer `i`
    is the input of `node_mean_{7i}` - the same numbering anchor `collect_gemma4_vision` relies
    on. `node_mean_{7 * LAYERS}` is the final norm, whose input is already **pooled**, so the
    tower's own output has to come from the masked fill that feeds the pooling instead.
    """
    norms, masked_fill = {}, None
    for node in model.graph.node:
        if node.op_type == "SimplifiedLayerNormalization":
            index = numbered(node, "node_mean")
            if index is not None:
                norms[index] = node.input[0]
        elif node.op_type == "Where" and (node.name or "").endswith("masked_fill"):
            masked_fill = node.input[2]
    if len(norms) != LAYERS * 7 + 1:
        raise SystemExit(
            f"{len(norms)} norms, not {LAYERS * 7 + 1} - the node numbering has changed"
        )
    if masked_fill is None:
        raise SystemExit("no masked_fill node - the pooling tail has changed")
    points = {f"layer_in_{i}": norms[7 * i] for i in range(LAYERS)}
    points["tower_out"] = masked_fill
    points["pooled_in"] = norms[7 * LAYERS]
    return points


def quantise_in_place(model):
    """Replace every linear's weight with its own int4 round-trip. Returns how many.

    This is the control, and it is the whole reason this script is worth running. The device has
    int4 weights and the export has fp16 ones, so a cosine of 0.95 at the output could be a
    composition error or could be four bits over sixteen layers - and the per-layer bisect cannot
    separate them, because both grow with depth.

    Running the reference a second time like this puts a number on what four bits alone costs. A
    device that lands on the same number is right; one that lands short of it is not.

    The two projections at the ends are **skipped**, because the converter leaves them at fp16 -
    see `collect_gemma4_vision.dense`. A control that quantised them would be a bar the device is
    not trying to meet, and the device would appear to beat it.
    """
    init = {i.name: i for i in model.graph.initializer}
    ends = {"node_linear", f"node_linear_{LAYERS * 7 + 1}"}
    touched = 0
    for node in model.graph.node:
        if node.op_type not in ("MatMul", "Gemm"):
            continue
        name = node.name or ""
        if not name.startswith("node_linear") or name in ends:
            continue
        for weight in node.input:
            if weight not in init:
                continue
            matrix = numpy_helper.to_array(init[weight]).astype(np.float32)
            if matrix.ndim != 2:
                continue
            # The converter quantises the `[out, in]` kernel, so the blocks run along the
            # contraction axis. A `Gemm` with `transB` already holds that; a `MatMul` does not.
            already = node.op_type == "Gemm"
            kernel = matrix if already else matrix.T
            codes, scale = quantise_per_block(kernel)
            flat = kernel.reshape(kernel.shape[0], -1)
            wide = np.repeat(scale, I4_BLOCK, axis=1)[:, : flat.shape[1]]
            back = (np.asarray(codes).astype(np.float32).reshape(flat.shape) * wide)
            back = back.reshape(kernel.shape)
            init[weight].CopyFrom(
                numpy_helper.from_array((back if already else back.T).astype(np.float16), weight)
            )
            touched += 1
    return touched


def collect(model, points, pixel_values, position_ids):
    """`{label: array}` for every trace point plus `image_features`, from one session."""
    existing = {o.name for o in model.graph.output}
    for tensor in points.values():
        if tensor not in existing:
            # Name only: onnxruntime infers the type, and asserting one here would just be a
            # second place to get fp16-versus-fp32 wrong.
            model.graph.output.add().name = tensor
            existing.add(tensor)
    session = ort.InferenceSession(
        model.SerializeToString(), providers=["CPUExecutionProvider"]
    )
    names = [o.name for o in session.get_outputs()]
    raw = dict(
        zip(names, session.run(names, {
            "pixel_values": pixel_values,
            "pixel_position_ids": position_ids,
        }))
    )
    out = {"image_features": np.asarray(raw["image_features"], dtype=np.float32)}
    for label, tensor in points.items():
        out[label] = np.asarray(raw[tensor], dtype=np.float32).reshape(-1, D_MODEL)
    return out


def cosine(a, b):
    a = np.asarray(a, dtype=np.float64).ravel()
    b = np.asarray(b, dtype=np.float64).ravel()
    return float(a @ b / max(np.linalg.norm(a) * np.linalg.norm(b), 1e-30))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("onnx", help="vision_encoder_fp16.onnx")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--width", type=int, default=1600)
    ap.add_argument("--height", type=int, default=900)
    ap.add_argument("--soft-tokens", type=int, default=70)
    ap.add_argument(
        "--no-control",
        action="store_true",
        help="skip the int4 reference, which is most of the runtime and all of the value",
    )
    args = ap.parse_args()

    max_patches = args.soft_tokens * POOL**2
    target_h, target_w = aspect_preserving_size(args.height, args.width, max_patches)
    rows, cols = target_h // PATCH, target_w // PATCH
    print(f"{args.width}x{args.height} -> {target_w}x{target_h} px = {cols}x{rows} patches")
    print(f"{rows * cols} patches, {(rows // POOL) * (cols // POOL)} soft tokens")
    if rows == cols:
        print("WARNING: a square grid cannot tell a row from a column", file=sys.stderr)

    pixel_values, position_ids = build_inputs(rows, cols)
    model = onnx.load(args.onnx)
    points = trace_points(model)
    print("tracing " + ", ".join(f"{k}={v}" for k, v in points.items()))

    print("running the reference as exported")
    exact = collect(model, points, pixel_values, position_ids)

    control = None
    if not args.no_control:
        touched = quantise_in_place(model)
        print(f"running it again with {touched} weights round-tripped through int4")
        control = collect(model, points, pixel_values, position_ids)

    golden = {
        "rows": rows,
        "cols": cols,
        "soft_tokens": (rows // POOL) * (cols // POOL),
        "image_features": exact["image_features"].reshape(-1).tolist(),
    }
    if control is not None:
        golden["image_features_int4"] = control["image_features"].reshape(-1).tolist()

    print()
    print(f"{'tensor':<14}{'rows':>8}{'rms':>12}{'int4 vs exact':>16}")
    for label in points:
        value = exact[label]
        probes = sorted({0, 1, min(7, value.shape[0] - 1), value.shape[0] - 1})
        golden[label] = value[probes].reshape(-1).tolist()
        golden[f"{label}_rows"] = probes
        note = ""
        if control is not None:
            golden[f"{label}_int4"] = control[label][probes].reshape(-1).tolist()
            note = f"{cosine(control[label], value):>16.6f}"
        print(f"{label:<14}{value.shape[0]:>8}{float(np.sqrt((value**2).mean())):>12.4f}{note}")
    if control is not None:
        bar = cosine(control["image_features"], exact["image_features"])
        print(f"{'image_features':<14}{exact['image_features'].shape[0]:>8}{'':>12}{bar:>16.6f}")
        print()
        print(f"four bits alone cost {1 - bar:.4f} of cosine at the output.")
        print("A device that lands here is right; one that lands short of it is not.")

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(golden, f)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
