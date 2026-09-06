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
import struct
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


def test_int4_round_trip_is_within_half_a_block_code():
    """As the int8 round trip, but the bound is that block's scale, not that row's.

    A quantiser that used one scale per row - the int8 behaviour - would still produce codes in
    range and a file of exactly the right size, and would blow this bound on any row whose blocks
    differ in magnitude. So the fixture makes them differ by 100x on purpose.
    """
    rng = np.random.RandomState(11)
    rows, taps = 6, maml_convert.I4_BLOCK * 3 + 7
    kernel = rng.randn(rows, taps).astype(np.float32)
    # Every third block is a hundred times louder, which one row-wide scale cannot serve.
    for block in range(0, taps, maml_convert.I4_BLOCK * 3):
        kernel[:, block : block + maml_convert.I4_BLOCK] *= 100.0
    codes, scale = maml_convert.quantise_per_block(kernel)
    blocks = scale.shape[1]
    check("the scale table is (rows, blocks)", scale.shape == (rows, blocks))
    check("blocks cover the taps", blocks == -(-taps // maml_convert.I4_BLOCK))
    worst = 0.0
    for r in range(rows):
        for t in range(taps):
            block = t // maml_convert.I4_BLOCK
            rebuilt = float(codes[r, t]) * float(scale[r, block])
            worst = max(worst, abs(rebuilt - float(kernel[r, t])) / float(scale[r, block]))
    check(f"every weight is within half a code of its block ({worst:.4f})", worst <= 0.5001)


def test_int4_codes_stay_inside_the_signed_nibble():
    """-8..7 is what `int4_at`'s `bitfieldExtract` sign-extends to, and 7 is the divisor.

    A quantiser dividing by 8 would put `+absmax` on code 8, which reads back as -8: the largest
    positive weight in the tensor becomes the largest negative one.
    """
    kernel = np.array([[-4.0, 4.0, 0.0, 1.0]], dtype=np.float32)
    codes, scale = maml_convert.quantise_per_block(kernel)
    check("no code exceeds the nibble", int(codes.min()) >= -8 and int(codes.max()) <= 7)
    check("the extreme lands on 7", int(np.abs(codes).max()) == 7)
    check("zero stays zero", int(codes[0, 2]) == 0)
    check("the scale is absmax/7", abs(float(scale[0, 0]) - np.float16(4.0 / 7.0)) < 1e-6)


def test_int4_packing_is_low_nibble_first_and_rounds_odd_lengths_up():
    """The one place the converter and `weights.rs` can disagree without either looking wrong.

    Five elements are two and a half bytes. Writing two would produce a file that parses - the
    reader's bounds check would pass - whose last element read as whatever followed it.
    """
    packed = maml_convert.pack_int4(np.array([-8, -1, 0, 1, 7], dtype=np.int8))
    check("five elements occupy three bytes", len(packed) == 3)
    check("low nibble first, sign kept", packed[0] == 0xF8)
    check("second pair", packed[1] == 0x10)
    check("the odd tail pads its high nibble", packed[2] == 0x07)
    even = maml_convert.pack_int4(np.zeros(64, dtype=np.int8))
    check("an even length is exactly half", len(even) == 32)
    try:
        maml_convert.pack_int4(np.array([8], dtype=np.int8))
        check("a code past the nibble is refused", False)
    except SystemExit:
        check("a code past the nibble is refused", True)


def test_an_int4_tensor_reaches_the_file_as_dtype_two():
    """End to end through `build`, since `Int4` is what tells it the dtype."""
    codes, scale = maml_convert.quantise_per_block(
        np.random.RandomState(3).randn(4, 70).astype(np.float32)
    )
    blob, _ = maml_convert.build(
        ["one"],
        [maml_convert.Int4(codes), scale.astype(np.float32), np.zeros(4, np.float32)],
        maml_convert.GRAPHS["supertonic_ve"],
        bytes(32),
    )
    # Tensor 0's dtype word sits at offset 20 of its 32-byte entry, after rank and four dims.
    entry = maml_convert.HEADER_BYTES
    dtype = struct.unpack_from("<I", blob, entry + 20)[0]
    length = struct.unpack_from("<I", blob, entry + 28)[0]
    check("the table says int4", dtype == maml_convert.DTYPE_I4)
    check("the length is elements, not bytes", length == 4 * 70)


def test_a_block_whose_scale_underflows_fp16_is_not_nan():
    """The failure mode: silent NaN codes and an undefined cast, not an error.

    `absmax / 7` for a block of very small weights rounds to **zero** in fp16. The division that
    follows is then `0 / 0` for every zero in the block, `numpy` produces NaN, and
    `NaN.astype(int8)` is undefined behaviour - so the block reaches the file as arbitrary codes
    with nothing to indicate anything went wrong.

    Found by a `RuntimeWarning` while converting the vision tower, which means real weights hit
    it. Both quantisers are guarded on the same terms.
    """
    tiny = np.zeros((2, maml_convert.I4_BLOCK * 2), dtype=np.float32)
    tiny[0, 0] = 1e-8
    tiny[1, maml_convert.I4_BLOCK] = 3e-9
    codes, scale = maml_convert.quantise_per_block(tiny)
    check("no code is NaN", not np.isnan(codes.astype(np.float32)).any())
    check("no code is infinite", np.isfinite(codes.astype(np.float32)).all())
    check("every scale is positive", bool((scale > 0).all()))
    check("codes stay inside the nibble", int(np.abs(codes).max()) <= 7)

    # And the int8 path, on the same terms.
    row = np.zeros((1, 8), dtype=np.float32)
    row[0, 0] = 1e-9
    codes8, scale8 = maml_convert.quantise_per_channel(row)
    check("int8 codes are finite", np.isfinite(codes8.astype(np.float32)).all())
    check("int8 scale is positive", bool((scale8 > 0).all()))


def main() -> int:
    test_round_trip_is_within_half_a_code()
    test_the_scale_is_per_output_channel_not_per_input()
    test_the_extremes_land_on_the_symmetric_range()
    test_zero_reconstructs_as_zero()
    test_the_scale_survives_fp16()
    test_a_non_finite_kernel_is_refused()
    test_a_quantised_conv_reaches_the_file_as_int8()
    test_a_shape_the_shaders_cannot_serve_is_refused()
    test_int4_round_trip_is_within_half_a_block_code()
    test_int4_codes_stay_inside_the_signed_nibble()
    test_int4_packing_is_low_nibble_first_and_rounds_odd_lengths_up()
    test_an_int4_tensor_reaches_the_file_as_dtype_two()
    test_a_block_whose_scale_underflows_fp16_is_not_nan()
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
