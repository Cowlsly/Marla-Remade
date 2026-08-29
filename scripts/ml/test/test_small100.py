#!/usr/bin/env python3
"""test_small100.py - unit test for `maml_convert`'s SMaLL-100 checkpoint path.

`collect_small100` is the one converter arm with no ONNX graph behind it, so nothing about its
input records the architecture: `check_checkpoint` is the whole guard, and `collect_small100`'s
emission *order* is the whole contract with `nets::small100`. Both are checked here against a
toy model of the same shape rather than the real 1.33 GB checkpoint, so this runs in a second and
needs no download.

What each check is defending against:

* **The transpose.** A `torch.nn.Linear` weight is `[out, in]` and a 1x1 kernel is
  `[out, in, 1, 1]`, so a projection is a reshape. ONNX `MatMul` is `[in, out]`, and every
  attention projection here is square, so reading one convention as the other gives the right
  shape holding the wrong numbers. `fc1` and `fc2` are the asymmetric pair that makes it loud, and
  the test asserts a transposed checkpoint is *rejected* rather than trusting a comment.
* **The order.** `nets::small100` walks the tensor table positionally. A layer emitted out of
  order loads cleanly and infers nonsense.
* **The tied head appearing once.** Emitting the embedding twice would put 125 MiB back into the
  download, which is most of the reason for the port.
* **The int8 triple.** `Builder::conv_int8` reads kernel, then per-output-channel scale, then
  bias. A pair, or the scale after the bias, is a silent misread.

Run:
    python3 scripts/ml/test/test_small100.py

Exit code 0 = all assertions passed.
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import maml_convert  # noqa: E402  (needs the path above)

# The real architecture with every dimension shrunk, so the table has the same *structure* -
# two head halves, encoder layers, an encoder norm, decoder layers, a decoder norm - at a size
# that fits in memory. `head_splits` stays 2 because that is what the Rust reads.
TOY = {
    "d_model": 8,
    "encoder_layers": 2,
    "decoder_layers": 1,
    "ffn": 16,
    "vocab": 6,
    "head_splits": 2,
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


def toy_checkpoint(spec, transpose_projections: bool = False):
    """`(get, shapes)` for a checkpoint of `spec`'s shape, filled with distinguishable values."""
    rng = np.random.default_rng(7)
    held = {}
    for name, shape in maml_convert.small100_inventory(spec).items():
        if transpose_projections and name.endswith(".weight") and len(shape) == 2:
            shape = list(reversed(shape))
        held[name] = rng.standard_normal(shape).astype(np.float32)
    return held.__getitem__, {name: list(v.shape) for name, v in held.items()}


def test_the_inventory_is_the_real_architecture() -> None:
    print("inventory:")
    want = maml_convert.small100_inventory(maml_convert.CHECKPOINTS["small100"])
    # 275 is what `alirezamsh/small100` holds; a derived list that disagrees is derived wrong.
    check("275 parameters", len(want) == 275)
    check("the embedding is [vocab, d_model]", want["model.shared.weight"] == [128_112, 1024])
    # The pair a transpose would swap.
    check("fc1 is [ffn, d_model]", want["model.encoder.layers.0.fc1.weight"] == [4096, 1024])
    check("fc2 is [d_model, ffn]", want["model.encoder.layers.0.fc2.weight"] == [1024, 4096])
    check(
        "the decoder has cross-attention",
        "model.decoder.layers.0.encoder_attn.q_proj.weight" in want,
    )
    check(
        "the encoder has none",
        "model.encoder.layers.0.encoder_attn.q_proj.weight" not in want,
    )
    total = sum(int(np.prod(shape)) for shape in want.values())
    check(f"332,735,488 parameters, got {total}", total == 332_735_488)


def test_a_transposed_checkpoint_is_refused() -> None:
    print("the transpose:")
    _, shapes = toy_checkpoint(TOY)
    try:
        maml_convert.check_checkpoint(shapes, "small100")
        refused = False
    except SystemExit:
        refused = True
    # The toy is not the real architecture, so it must fail; the point is *which* checks fire.
    check("a toy checkpoint is refused against the real spec", refused)

    real = maml_convert.small100_inventory(maml_convert.CHECKPOINTS["small100"])
    flipped = {
        name: (list(reversed(shape)) if name.endswith(".weight") and len(shape) == 2 else shape)
        for name, shape in real.items()
    }
    try:
        maml_convert.check_checkpoint(flipped, "small100")
        caught = ""
    except SystemExit as exit:
        caught = str(exit)
    check("a transposed checkpoint is refused", bool(caught))
    check("and fc1 is named in the message", "fc1" in caught)
    maml_convert.check_checkpoint(real, "small100")
    check("the real inventory is accepted", True)


def test_the_table_order_is_the_contract() -> None:
    print("order:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_small100(get, TOY)
    ops = [layer.op for layer in layers]

    heads = [i for i, op in enumerate(ops) if op == "Head"]
    check("both head halves come first", heads == [0, 1])
    check("the embedding is emitted once, as two ranges", len(heads) == TOY["head_splits"])

    # One encoder layer: norm, q, k, v, out, norm, fc1, fc2.
    encoder = ops[2:2 + 8]
    check(
        "an encoder layer is norm, four projections, norm, fc1, fc2",
        encoder == ["LayerNorm", "Linear8", "Linear8", "Linear8", "Linear8",
                    "LayerNorm", "Linear8", "Linear8"],
    )
    per_encoder = 8
    at = 2 + TOY["encoder_layers"] * per_encoder
    check("the encoder's final norm follows its layers", ops[at] == "LayerNorm")
    # One decoder layer adds a cross-attention norm and four more projections.
    decoder = ops[at + 1: at + 1 + 13]
    check(
        "a decoder layer has two attentions",
        decoder == ["LayerNorm"] + ["Linear8"] * 4 + ["LayerNorm"] + ["Linear8"] * 4
        + ["LayerNorm", "Linear8", "Linear8"],
    )
    check("the decoder's final norm is last", ops[-1] == "LayerNorm")

    names = [layer.name for layer in layers]
    check(
        "the layers are named after the checkpoint",
        names[2] == "model.encoder.layers.0.self_attn_layer_norm",
    )
    check("a head half names its class range", names[0].endswith("[0:3]"))

    # Every layer's tensors are contiguous and in order, which is how the Rust indexes them.
    at = 0
    contiguous = True
    for layer in layers:
        contiguous = contiguous and layer.first_tensor == at
        at += layer.tensor_count
    check("layer tensor ranges tile the table", contiguous and at == len(tensors))


def test_an_int8_layer_is_kernel_then_scale_then_bias() -> None:
    print("the int8 triple:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_small100(get, TOY)

    head = layers[0]
    check("a head half emits three tensors", head.tensor_count == 3)
    kernel, scale, bias = tensors[head.first_tensor: head.first_tensor + 3]
    rows = TOY["vocab"] // TOY["head_splits"]
    check("the kernel is int8", kernel.dtype == np.int8)
    check(
        f"shaped [{rows}, {TOY['d_model']}, 1, 1]",
        list(kernel.shape) == [rows, TOY["d_model"], 1, 1],
    )
    check("one scale per class", scale.shape == (rows,) and scale.dtype != np.int8)
    check("a tied head's bias is zero", bias.shape == (rows,) and not bias.any())

    ffn = next(layer for layer in layers if layer.name.endswith("fc1"))
    kernel, scale, bias = tensors[ffn.first_tensor: ffn.first_tensor + 3]
    check(
        f"fc1's kernel is [{TOY['ffn']}, {TOY['d_model']}, 1, 1]",
        list(kernel.shape) == [TOY["ffn"], TOY["d_model"], 1, 1],
    )
    check("with one scale per output channel", scale.shape == (TOY["ffn"],))
    check("and the checkpoint's own bias", bias.shape == (TOY["ffn"],) and bias.any())

    norm = next(layer for layer in layers if layer.name.endswith("self_attn_layer_norm"))
    check("a layer norm emits two", norm.tensor_count == 2)
    gamma, beta = tensors[norm.first_tensor: norm.first_tensor + 2]
    check("both fp32, so `build` rounds them to fp16", gamma.dtype != np.int8)
    check("both [d_model]", gamma.shape == beta.shape == (TOY["d_model"],))


def test_the_digest_covers_the_order() -> None:
    print("the digest:")
    get, _ = toy_checkpoint(TOY)
    layers, _ = maml_convert.collect_small100(get, TOY)
    before = maml_convert.layer_table_digest(layers)
    # A key is what the Rust hardcodes about a layer, not its name, so the pair swapped here has
    # to differ in shape. Two `[d_model]` layer norms have the same key and are interchangeable in
    # the digest, which is correct: swapping them changes nothing the forward pass can observe.
    q_proj = next(i for i, layer in enumerate(layers) if layer.name.endswith("q_proj"))
    fc1 = next(i for i, layer in enumerate(layers) if layer.name.endswith("fc1"))
    swapped = list(layers)
    swapped[q_proj], swapped[fc1] = swapped[fc1], swapped[q_proj]
    check("a reordered pair changes it", maml_convert.layer_table_digest(swapped) != before)
    check("the same table does not", maml_convert.layer_table_digest(list(layers)) == before)


def main() -> int:
    test_the_inventory_is_the_real_architecture()
    test_a_transposed_checkpoint_is_refused()
    test_the_table_order_is_the_contract()
    test_an_int8_layer_is_kernel_then_scale_then_bias()
    test_the_digest_covers_the_order()
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
