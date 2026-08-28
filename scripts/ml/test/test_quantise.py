#!/usr/bin/env python3
"""test_quantise.py - unit test for `maml_convert.quantise_per_channel`.

The int8 quantiser is the one part of the converter with no oracle beside it. Every other
transform is checked end to end by `scripts/ml/onnx_parity.py`, which needs the ONNX export and
onnxruntime; this needs neither, and the properties below are the ones a wrong quantiser breaks
while still producing a `.maml` of exactly the right size.

What each check is defending against, since none of them is arbitrary:

* **Round trip.** An absmax quantiser's error is bounded by half a code, `scale / 2`. A quantiser
  that divided by the wrong row's scale reconstructs plausible numbers that are wrong by a factor,
  and nothing about the file's shape reveals it.
* **The axis.** `library/ml`'s shaders index the scale by *output* channel, so a quantiser that
  reduced over axis 0 instead of the rest gives a tensor of the right length holding the wrong
  per-channel ranges. A kernel whose rows differ in scale by 1000x is what separates the two.
* **The range.** int8 is -128..127 and the scale is `absmax / 127`, so the extreme weight must
  land on ±127 exactly. Using 128 puts it out of range on one side.
* **Zero.** The quantisation is symmetric with no zero point, so an exactly-zero weight must
  reconstruct as exactly zero - `conv_int8.comp` has nowhere to put an offset.

Run:

    python3 scripts/ml/test/test_quantise.py

Exit code 0 = all assertions passed.
"""
from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import maml_convert  # noqa: E402  (needs the path above)
import supertonic_fold  # noqa: E402  (needs the path above)

passed = 0
failed = 0


def check(what: str, ok: bool) -> None:
    global passed, failed
    if ok:
        passed += 1
        print(f"  ok    {what}")
    else:
        failed += 1
        print(f"  FAIL  {what}")


def test_round_trip_is_within_half_a_code() -> None:
    print("round trip:")
    rng = np.random.default_rng(11)
    kernel = rng.standard_normal((17, 23, 1, 1)).astype(np.float32)
    quantised, scale = maml_convert.quantise_per_channel(kernel)

    check("the kernel keeps its shape", quantised.shape == kernel.shape)
    check("the payload is int8", quantised.dtype == np.int8)
    check("one scale per output channel", scale.shape == (17,))

    rows = kernel.reshape(17, -1)
    back = quantised.astype(np.float32).reshape(17, -1) * scale[:, None]
    # Half a code, plus the fp16 rounding of the scale itself, which multiplies the whole row.
    allowance = scale[:, None] * 0.5 + np.abs(back) * 2.0 ** -11
    check("every weight is within half a code", bool((np.abs(rows - back) <= allowance).all()))


def test_the_scale_is_per_output_channel_not_per_input() -> None:
    print("the reduction axis:")
    # Row `i` spans ±(i + 1), so the per-output-channel scales must differ by 20x across the
    # kernel. A quantiser that reduced over the output axis instead would give 23 identical-ish
    # scales, and only 20 of them, so the shape check alone nearly catches it - hence the
    # non-square shape.
    kernel = np.zeros((20, 23, 1, 1), dtype=np.float32)
    for row in range(20):
        kernel[row, :, 0, 0] = np.linspace(-(row + 1.0), row + 1.0, 23)
    quantised, scale = maml_convert.quantise_per_channel(kernel)

    check("there are as many scales as output channels", scale.shape == (20,))
    check("the scales rise with the rows", bool((np.diff(scale) > 0).all()))
    # Each row's own extreme must reach the top code, which is what per-channel buys: under one
    # tensor-wide scale row 0 would be quantised against row 19's range and lose 20x resolution.
    reached = [int(np.abs(quantised[row]).max()) for row in range(20)]
    check("every row reaches the top code", reached == [127] * 20)


def test_the_extremes_land_on_the_symmetric_range() -> None:
    print("the range:")
    kernel = np.array([[-4.0, 4.0, 0.0, 1.0]], dtype=np.float32).reshape(1, 4, 1, 1)
    quantised, scale = maml_convert.quantise_per_channel(kernel)
    flat = quantised.reshape(-1).tolist()

    check("the negative extreme is -127", flat[0] == -127)
    check("the positive extreme is +127", flat[1] == 127)
    check("nothing exceeds int8's range", all(-128 <= c <= 127 for c in flat))
    check("the scale is absmax / 127", abs(float(scale[0]) - 4.0 / 127.0) < 1e-4)


def test_zero_reconstructs_as_zero() -> None:
    print("zero:")
    kernel = np.array([[-2.0, 0.0, 2.0]], dtype=np.float32).reshape(1, 3, 1, 1)
    quantised, _ = maml_convert.quantise_per_channel(kernel)
    check("an exactly-zero weight quantises to code 0", int(quantised.reshape(-1)[1]) == 0)

    # An all-zero output channel has an absmax of 0, so its scale would be a division by zero.
    # It is given 1.0, which reproduces the row exactly for the same reason any scale would.
    kernel = np.zeros((2, 5, 1, 1), dtype=np.float32)
    kernel[1] = 3.0
    quantised, scale = maml_convert.quantise_per_channel(kernel)
    check("an all-zero channel has a finite scale", bool(np.isfinite(scale).all()))
    check("an all-zero channel is all zeros", bool((quantised[0] == 0).all()))
    check("its neighbour is unaffected", int(np.abs(quantised[1]).max()) == 127)


def test_the_scale_survives_fp16() -> None:
    print("fp16 storage:")
    # The scale is stored as fp16 beside the weights, so a scale the file cannot hold would make
    # the device compute something the converter never saw. Returning it already rounded is what
    # keeps the two agreeing.
    rng = np.random.default_rng(5)
    kernel = (rng.standard_normal((9, 11, 1, 1)) * 1e-3).astype(np.float32)
    _, scale = maml_convert.quantise_per_channel(kernel)
    check(
        "the scale is exactly representable in fp16",
        bool((scale.astype(np.float16).astype(np.float32) == scale).all()),
    )


def test_a_non_finite_kernel_is_refused() -> None:
    print("refusals:")
    kernel = np.array([[1.0, np.inf]], dtype=np.float32).reshape(1, 2, 1, 1)
    try:
        maml_convert.quantise_per_channel(kernel)
        check("an infinite weight is refused", False)
    except SystemExit:
        check("an infinite weight is refused", True)


def test_a_quantised_conv_reaches_the_file_as_int8() -> None:
    print("the table entry:")
    # The trap `Table.add` exists to avoid: it coerces every tensor to fp32, and a quantised kernel
    # that went through that path would be written as fp16 - the file would be *larger* than the
    # unquantised one, load without complaint, and infer nonsense, because `weights.rs` reads an
    # int8 entry with a 1-byte stride and an fp16 one with 2.
    channels, inputs = 6, 8
    held = {
        "block.pwconv2.weight": np.linspace(-1.0, 1.0, channels * inputs)
        .reshape(channels, inputs)
        .astype(np.float32),
        "block.pwconv2.bias": np.arange(channels, dtype=np.float32),
    }
    table = supertonic_fold.Table(held)
    table.conv("block.pwconv2", channels, int8=True)

    check("one layer, three tensors", len(table.layers) == 1 and len(table.tensors) == 3)
    check("the kernel stayed int8", table.tensors[0].dtype == np.int8)
    check("the scale is one per output channel", table.tensors[1].shape == (channels,))
    check("the bias follows the scale", table.tensors[2].shape == (channels,))
    check("the key records the dtype", "dtype=int8" in table.layers[0].key())

    blob, _ = maml_convert.build(table.layers, table.tensors, 12, b"\0" * 32)
    entry = maml_convert.HEADER_BYTES
    dtype = int.from_bytes(blob[entry + 20:entry + 24], "little")
    length = int.from_bytes(blob[entry + 28:entry + 32], "little")
    check("the table entry says int8", dtype == maml_convert.DTYPE_I8)
    check("it declares every tap", length == channels * inputs)

    # Three tensors at 16-byte alignment: 48 int8 bytes padded to 48, then two 6-element fp16
    # tensors of 12 bytes padded to 16 each. An fp16 kernel would have made the first 96.
    data_length = int.from_bytes(blob[52:56], "little")
    check("the kernel occupies one byte a tap", data_length == 48 + 16 + 12)


def test_a_shape_the_shaders_cannot_serve_is_refused() -> None:
    print("ineligible convolutions:")
    # Neither int8 shader edge-pads or indexes a grouped kernel, so a call site asking for one is a
    # mistake that must not reach the `.maml`: the tensor indices after it would already have
    # shifted by one, and the Rust would read the right shapes holding the wrong numbers.
    held = {
        "block.dwconv.weight": np.ones((4, 1, 1, 5), dtype=np.float32),
        "block.dwconv.bias": np.zeros(4, dtype=np.float32),
    }
    for what, kwargs in [
        ("a depthwise kernel", {"group": 4, "kernel": (1, 5)}),
        ("a padded kernel", {"kernel": (1, 5), "pads": (0, 2, 0, 2)}),
    ]:
        try:
            supertonic_fold.Table(held).conv("block.dwconv", 4, int8=True, **kwargs)
            check(f"{what} is refused", False)
        except SystemExit:
            check(f"{what} is refused", True)


def main() -> int:
    test_round_trip_is_within_half_a_code()
    test_the_scale_is_per_output_channel_not_per_input()
    test_the_extremes_land_on_the_symmetric_range()
    test_zero_reconstructs_as_zero()
    test_the_scale_survives_fp16()
    test_a_non_finite_kernel_is_refused()
    test_a_quantised_conv_reaches_the_file_as_int8()
    test_a_shape_the_shaders_cannot_serve_is_refused()
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
