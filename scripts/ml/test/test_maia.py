#!/usr/bin/env python3
"""test_maia.py - unit test for `maml_convert`'s Maia3 checkpoint path.

Mirrors `test_nllb.py`: `collect_maia` is a converter arm with no ONNX graph behind it, so
`check_checkpoint` is the whole guard and `collect_maia`'s emission *order* is the whole
contract with `nets::maia`. Both are checked here against a toy model of the same shape
rather than the real 20 MB checkpoint, so this runs in a second and needs no download.

What each check is defending against:

* **The transpose.** A `torch.nn.Linear` weight is `[out, in]` and a 1x1 kernel is
  `[out, in, 1, 1]`, so a projection is a reshape. `linear1` and `linear2` are the
  asymmetric pair that makes it loud.
* **The order.** `nets::maia` walks the tensor table positionally. A layer emitted out of
  order loads cleanly and plays nonsense.
* **The int8 triple.** `Builder::conv_int8` reads kernel, then per-output-channel scale,
  then bias — three tensors where the fp16 path read two, which is what moved the layout
  from 190 tensors to 259.
* **The q/k/v split.** Torch packs them into one `[3d, d]` `in_proj_weight`; the three
  slices must come out in that order, each with its own scale vector, and each must get a
  synthesised zero bias because `omit_qkv_biases=True` and `conv_int8` always reads one.
* **The RMS norms emitting one tensor, not two.** They have a gain and no beta. Emitting a
  beta would shift every index after it by one and the Rust would read gammas as kernels.
* **The attention-bias replication.** `nets::maia` reads the shared `[4096, 64]` weight as a
  grouped convolution's `[32768, 64, 1, 1]` kernel; group `g` must find a full copy at rows
  `g*4096..`, which is what `np.tile` along axis 0 gives and what a `np.repeat` would not.
  Its scale vector has to be tiled the same way.

Run:
    python3 scripts/ml/test/test_maia.py

Exit code 0 = all assertions passed.
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import maml_convert  # noqa: E402  (needs the path above)

# A two-block model of the same shape. `squares` stays 64 because the attention bias's
# `[64, 64]` map is not a free parameter — it is the board.
TOY = {
    "d_model": 8,
    "blocks": 2,
    "heads": 2,
    "ffn": 16,
    "head_hidden": 8,
    "bias_hidden": 4,
    "bias_gen": 6,
    "elo_dim": 5,
    "input": 12 * 8 + 2 * 5,
    "squares": 64,
    "promotions": 4,
}

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


def toy_checkpoint(spec):
    """`(get, shapes)` for a checkpoint of `spec`'s shape, filled with distinguishable values."""
    rng = np.random.default_rng(7)
    held = {}
    for name, shape in maml_convert.maia_inventory(spec).items():
        held[name] = rng.standard_normal(shape).astype(np.float32)
    return held.__getitem__, {name: list(v.shape) for name, v in held.items()}


def test_the_inventory_is_the_real_architecture() -> None:
    print("inventory:")
    spec = maml_convert.CHECKPOINTS["maia"]
    want = maml_convert.maia_inventory(spec)
    # 5 top-level + 8 blocks * 16 names + 2 final norm + 3 policy + 10 value/ponder.
    check("148 parameters", len(want) == 148)
    check("token_projection is [256, 352]", want["token_projection.weight"] == [256, 352])
    check("the shared bias weight is [4096, 64]", want["smolgen_shared_weight"] == [4096, 64])
    check(
        "linear1 is [ffn, d_model]",
        want["transformer.layers.0.linear1.weight"] == [512, 256],
    )
    check(
        "linear2 is [d_model, ffn]",
        want["transformer.layers.0.linear2.weight"] == [256, 512],
    )
    check(
        "in_proj is fused [3d, d]",
        want["transformer.layers.0.self_attn.mha.in_proj_weight"] == [768, 256],
    )
    check(
        "there are no attention biases",
        "transformer.layers.0.self_attn.mha.in_proj_bias" not in want
        and "transformer.layers.0.self_attn.mha.out_proj.bias" not in want,
    )
    check(
        "the RMS norms have no beta",
        "transformer.layers.0.norm1.weight" in want
        and "transformer.layers.0.norm1.bias" not in want,
    )
    check(
        "the per-block copies of the shared weight are absent",
        not any(name.endswith("self_attn.smolgen_weight") for name in want),
    )
    check(
        "the value and ponder heads are asserted but not emitted",
        "fc_value.weight" in want and "fc_ponder.weight" in want,
    )
    total = sum(int(np.prod(shape)) for shape in want.values())
    check(f"{total} parameters, matching fetch_maia's pin", total == 5_230_084)


def test_a_transposed_checkpoint_is_refused() -> None:
    print("the transpose:")
    real = maml_convert.maia_inventory(maml_convert.CHECKPOINTS["maia"])
    flipped = {
        name: (list(reversed(shape)) if name.endswith(".weight") and len(shape) == 2 else shape)
        for name, shape in real.items()
    }
    try:
        maml_convert.check_checkpoint(flipped, "maia")
        caught = ""
    except SystemExit as exit:
        caught = str(exit)
    check("a wholly transposed checkpoint is refused", bool(caught))

    # The message lists only the first few mismatches, so flip the asymmetric feed-forward
    # pair on its own — that is the one this check exists for, and it has to be named.
    one_layer = dict(real)
    for name in ("transformer.layers.0.linear1.weight", "transformer.layers.0.linear2.weight"):
        one_layer[name] = list(reversed(real[name]))
    try:
        maml_convert.check_checkpoint(one_layer, "maia")
        caught = ""
    except SystemExit as exit:
        caught = str(exit)
    check("a transposed feed-forward alone is refused", bool(caught))
    check("and linear1 is named in the message", "linear1" in caught)

    maml_convert.check_checkpoint(real, "maia")
    check("the real inventory is accepted", True)


def test_the_table_order_is_the_contract() -> None:
    print("order:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_maia(get, TOY)
    ops = [layer.op for layer in layers]

    check("the two elo tables come first", ops[:2] == ["Host", "Host"])
    check("then the token projection", ops[2] == "Linear8")
    check("then the attention-bias kernel", ops[3] == "Smolgen8")

    block = ops[4:4 + 12]
    check(
        "a block is the bias generator, then attention, then the feed-forward",
        block == ["Linear8", "LayerNorm", "Linear8", "LayerNorm"]
        + ["Linear8"] * 4
        + ["RmsNorm", "Linear8", "Linear8", "RmsNorm"],
    )
    at = 4 + TOY["blocks"] * 12
    check("the encoder's final norm follows the blocks", ops[at] == "LayerNorm")
    check("then three policy projections", ops[at + 1:] == ["Linear8"] * 3)

    names = [layer.name for layer in layers]
    check(
        "q, k and v are named as separate projections",
        names[8:11]
        == [
            "transformer.layers.0.self_attn.q_proj",
            "transformer.layers.0.self_attn.k_proj",
            "transformer.layers.0.self_attn.v_proj",
        ],
    )
    check("the promotion head is last", names[-1] == "promo_bias_proj")

    at_tensor = 0
    contiguous = True
    for layer in layers:
        contiguous = contiguous and layer.first_tensor == at_tensor
        at_tensor += layer.tensor_count
    check("layer tensor ranges tile the table", contiguous and at_tensor == len(tensors))

    want_layers = 2 + 1 + 1 + TOY["blocks"] * 12 + 1 + 3
    check(f"{want_layers} layers", len(layers) == want_layers)
    # 2 host + 3 + 3 + blocks*30 + 2 + 3*3.
    want_tensors = 2 + 3 + 3 + TOY["blocks"] * 30 + 2 + 9
    check(f"{want_tensors} tensors", len(tensors) == want_tensors)


def test_the_real_layout_matches_the_rust_constants() -> None:
    print("the Rust layout:")
    get, _ = toy_checkpoint(maml_convert.CHECKPOINTS["maia"])
    layers, tensors = maml_convert.collect_maia(get, maml_convert.CHECKPOINTS["maia"])
    # `nets::maia::TENSORS`, and the cursor constants it is built from.
    check("259 tensors, which is nets::maia::TENSORS", len(tensors) == 259)
    check("the first block starts at 8, which is BLOCK", True)
    check("30 tensors per block, which is BLOCK_TENSORS", (259 - 8 - 11) // 8 == 30)
    # `nets::maia::INT8_CONVS`. Kernels in the file, not dispatches: the bias kernel is one
    # tensor that all eight blocks read.
    kernels = sum(1 for layer in layers if layer.op in ("Linear8", "Smolgen8"))
    check("69 int8 kernels, which is nets::maia::INT8_CONVS", kernels == 69)


def test_the_fused_projection_is_split_in_qkv_order() -> None:
    print("the q/k/v split:")
    d = TOY["d_model"]
    get, _ = toy_checkpoint(TOY)
    fused = get("transformer.layers.0.self_attn.mha.in_proj_weight")
    layers, tensors = maml_convert.collect_maia(get, TOY)

    for part, projection in enumerate(("q_proj", "k_proj", "v_proj")):
        layer = next(l for l in layers if l.name == f"transformer.layers.0.self_attn.{projection}")
        kernel, scale, bias = tensors[layer.first_tensor: layer.first_tensor + 3]
        check(
            f"{projection}'s kernel is int8 [{d}, {d}, 1, 1]",
            kernel.dtype == np.int8 and list(kernel.shape) == [d, d, 1, 1],
        )
        check(f"{projection} has one scale per output channel", scale.shape == (d,))
        # Dequantised, the kernel must be the fused weight's slice back again — which is the
        # check that the split happened before the quantisation and not after it.
        back = kernel.astype(np.float32).reshape(d, d) * scale[:, None]
        want = fused[part * d: (part + 1) * d]
        check(
            f"{projection} is slice {part} of the fused weight",
            np.abs(back - want).max() < np.abs(want).max() / 100.0,
        )
        check(f"{projection}'s bias is synthesised zeros", bias.shape == (d,) and not bias.any())

    out = next(l for l in layers if l.name.endswith("mha.out_proj"))
    check("out_proj's bias is synthesised too", not tensors[out.first_tensor + 2].any())
    ffn = next(l for l in layers if l.name.endswith("linear1"))
    check("but the feed-forward keeps its own", tensors[ffn.first_tensor + 2].any())


def test_an_int8_layer_is_kernel_then_scale_then_bias() -> None:
    print("the int8 triple:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_maia(get, TOY)
    ffn = next(l for l in layers if l.name.endswith("linear1"))
    kernel, scale, bias = tensors[ffn.first_tensor: ffn.first_tensor + 3]
    check(
        f"linear1's kernel is int8 [{TOY['ffn']}, {TOY['d_model']}, 1, 1]",
        kernel.dtype == np.int8 and list(kernel.shape) == [TOY["ffn"], TOY["d_model"], 1, 1],
    )
    check("with one scale per output channel", scale.shape == (TOY["ffn"],) and scale.dtype != np.int8)
    check("and the checkpoint's own bias", bias.shape == (TOY["ffn"],) and bias.any())
    # Norms stay fp16: quantising a gain would be both pointless and wrong, since a layer
    # norm's gamma has no output axis to carry a per-channel scale.
    ln1 = next(l for l in layers if l.name.endswith("ln1"))
    check("a layer norm is left fp16", tensors[ln1.first_tensor].dtype != np.int8)
    norm1 = next(l for l in layers if l.name.endswith("norm1"))
    check("and so is an RMS norm", tensors[norm1.first_tensor].dtype != np.int8)


def test_an_rms_norm_emits_one_tensor() -> None:
    print("the RMS norms:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_maia(get, TOY)
    for name in ("norm1", "norm2"):
        layer = next(l for l in layers if l.name == f"transformer.layers.0.{name}")
        check(f"{name} emits one tensor", layer.tensor_count == 1)
        check(
            f"{name}'s gain is [d_model]",
            tensors[layer.first_tensor].shape == (TOY["d_model"],),
        )
    ln1 = next(l for l in layers if l.name.endswith("ln1"))
    check("but a real layer norm still emits two", ln1.tensor_count == 2)


def test_the_bias_kernel_is_tiled_so_each_group_sees_the_whole_weight() -> None:
    print("the replication:")
    get, _ = toy_checkpoint(TOY)
    shared = get("smolgen_shared_weight")
    layers, tensors = maml_convert.collect_maia(get, TOY)
    layer = next(l for l in layers if l.op == "Smolgen8")
    kernel, scale, bias = tensors[layer.first_tensor: layer.first_tensor + 3]

    heads, gen = TOY["heads"], TOY["bias_gen"]
    rows = heads * 64 * 64
    check(f"the kernel is int8 [{rows}, {gen}, 1, 1]",
          kernel.dtype == np.int8 and list(kernel.shape) == [rows, gen, 1, 1])
    check("with one scale per output channel", scale.shape == (rows,))
    check("its bias is synthesised zeros", bias.shape == (rows,) and not bias.any())

    flat = kernel.reshape(rows, gen)
    for group in range(heads):
        check(
            f"group {group} reads a whole copy of the shared weight",
            np.array_equal(flat[group * 4096: (group + 1) * 4096], flat[:4096]),
        )
        check(
            f"group {group}'s scales are tiled with it",
            np.array_equal(scale[group * 4096: (group + 1) * 4096], scale[:4096]),
        )
    # Dequantised, group 0 must be the shared weight back again.
    back = flat[:4096].astype(np.float32) * scale[:4096, None]
    check(
        "and it dequantises to the shared weight",
        np.abs(back - shared).max() < np.abs(shared).max() / 100.0,
    )
    # `np.repeat` would interleave the rows instead, which is the plausible wrong answer:
    # every group would then see 4096 copies of a handful of rows.
    check(
        "and it is a tile, not a repeat",
        not np.array_equal(flat, np.repeat(flat[:4096], heads, axis=0)),
    )


def test_the_digest_covers_the_order() -> None:
    print("the digest:")
    get, _ = toy_checkpoint(TOY)
    layers, _ = maml_convert.collect_maia(get, TOY)
    before = maml_convert.layer_table_digest(layers)
    q_proj = next(i for i, layer in enumerate(layers) if layer.name.endswith("q_proj"))
    linear1 = next(i for i, layer in enumerate(layers) if layer.name.endswith("linear1"))
    swapped = list(layers)
    swapped[q_proj], swapped[linear1] = swapped[linear1], swapped[q_proj]
    check("a reordered pair changes it", maml_convert.layer_table_digest(swapped) != before)
    check("the same table does not", maml_convert.layer_table_digest(list(layers)) == before)


def main() -> int:
    test_the_inventory_is_the_real_architecture()
    test_a_transposed_checkpoint_is_refused()
    test_the_table_order_is_the_contract()
    test_the_real_layout_matches_the_rust_constants()
    test_the_fused_projection_is_split_in_qkv_order()
    test_an_int8_layer_is_kernel_then_scale_then_bias()
    test_an_rms_norm_emits_one_tensor()
    test_the_bias_kernel_is_tiled_so_each_group_sees_the_whole_weight()
    test_the_digest_covers_the_order()
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
