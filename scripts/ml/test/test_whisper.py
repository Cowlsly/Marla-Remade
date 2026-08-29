#!/usr/bin/env python3
"""test_whisper.py - unit test for `maml_convert`'s whisper-base checkpoint path.

The sibling of `test_small100.py`, and the same argument: `collect_whisper` reads a checkpoint, so
nothing about its input records the architecture. `check_checkpoint` against `whisper_inventory` is
the whole shape guard, and `collect_whisper`'s emission *order* is the whole contract with
`nets::whisper`. Both are checked against a toy model of the same shape rather than the real 290 MB
checkpoint, so this runs in a second and needs no download.

What each check is defending against, beyond what the SMaLL-100 test already covers:

* **The absent `k_proj.bias`.** No key projection in any of whisper's 18 attentions has one, and
  `Builder::conv_int8` always reads a bias after the scale. The converter synthesises zeros; this
  asserts that a checkpoint which *grows* one is refused rather than read as this table with the
  bias ignored, and that the synthesis is recorded in the digest key.
* **The conv stem's stride.** `conv2` is stride 2, which is what turns 3000 mel frames into 1500
  encoder positions. A wrong stride gives the right rank and the wrong length, and the encoder's
  output length is not checked against anything downstream.
* **The rank-3 lift.** Both convolutions are `[out, in, k]` in the checkpoint and `[out, in, 1, k]`
  in the file. That is a reshape of the same bytes, and it must not be a transpose.
* **The tied head appearing once, and not being split.** SMaLL-100's is emitted as two class ranges
  because 128,112 x 1024 is 125 MiB against a guaranteed 128 MiB binding; whisper's 51,865 x 512 is
  26.6 MB and must be one tensor, or `nets::whisper` reads the wrong index for every layer after it.
* **The position tables going opposite ways.** The encoder's is added to the conv stem's output on
  the device, so it is transposed to `[width, 1, positions]` and emitted as a `Constant`; the
  decoder's is added to a gathered embedding row on the *host*, so it is left `[positions, width]`.
  Swapping them is silent.

Run:

    python3 scripts/ml/test/test_whisper.py

Exit code 0 = all assertions passed.
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import maml_convert  # noqa: E402  (needs the path above)

# The real architecture with every dimension shrunk, so the table has the same *structure* — two
# convolutions, two position tables, an encoder stack, a tied head, a decoder stack, two trailing
# norms — at a size that fits in memory. `conv_kernel` stays 3 because that is what the Rust reads.
TOY = {
    "d_model": 8,
    "encoder_layers": 2,
    "decoder_layers": 1,
    "ffn": 16,
    "vocab": 6,
    "mels": 4,
    "source_positions": 10,
    "target_positions": 5,
    "conv_kernel": 3,
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


def toy_checkpoint(spec, transpose_projections: bool = False, key_bias: bool = False):
    """`(get, shapes)` for a checkpoint of `spec`'s shape, filled with distinguishable values."""
    rng = np.random.default_rng(13)
    held = {}
    for name, shape in maml_convert.whisper_inventory(spec).items():
        if transpose_projections and name.endswith(".weight") and len(shape) == 2:
            shape = list(reversed(shape))
        held[name] = rng.standard_normal(shape).astype(np.float32)
    if key_bias:
        # The absence this port depends on, deliberately filled in.
        for name in [n for n in list(held) if n.endswith("k_proj.weight")]:
            held[name.replace(".weight", ".bias")] = np.zeros(spec["d_model"], dtype=np.float32)
    return held.__getitem__, {name: list(v.shape) for name, v in held.items()}


def test_the_inventory_is_the_real_architecture() -> None:
    print("inventory:")
    want = maml_convert.whisper_inventory(maml_convert.CHECKPOINTS["whisper"])
    # 245 is what `openai/whisper-base` holds; a derived list that disagrees is derived wrong.
    check(f"245 parameters, got {len(want)}", len(want) == 245)
    check("the tied embedding is [vocab, d_model]", want["model.decoder.embed_tokens.weight"] == [51_865, 512])
    check("there is no proj_out, because the head is tied", "proj_out.weight" not in want)
    # The two convolutions, rank-3 in the checkpoint.
    check("conv1 is [d_model, mels, 3]", want["model.encoder.conv1.weight"] == [512, 80, 3])
    check("conv2 is [d_model, d_model, 3]", want["model.encoder.conv2.weight"] == [512, 512, 3])
    # The encoder's positions are a real frozen tensor, not computed sinusoids.
    check(
        "the encoder has a 1500-position table",
        want["model.encoder.embed_positions.weight"] == [1500, 512],
    )
    check(
        "and the decoder a learned 448-position one",
        want["model.decoder.embed_positions.weight"] == [448, 512],
    )
    # The pair a transpose would swap.
    check("fc1 is [ffn, d_model]", want["model.encoder.layers.0.fc1.weight"] == [2048, 512])
    check("fc2 is [d_model, ffn]", want["model.encoder.layers.0.fc2.weight"] == [512, 2048])
    # The absence, stated as an absence.
    keys = [n for n in want if n.endswith("k_proj.weight")]
    biases = [n for n in want if n.endswith("k_proj.bias")]
    check(f"18 key projections, got {len(keys)}", len(keys) == 18)
    check("and not one of them has a bias", not biases)
    check(
        "while q, v and out all do",
        all(
            f"model.encoder.layers.0.self_attn.{p}.bias" in want
            for p in ("q_proj", "v_proj", "out_proj")
        ),
    )
    check(
        "the decoder has cross-attention",
        "model.decoder.layers.0.encoder_attn.q_proj.weight" in want,
    )
    check(
        "the encoder has none",
        "model.encoder.layers.0.encoder_attn.q_proj.weight" not in want,
    )
    total = sum(int(np.prod(shape)) for shape in want.values())
    check(f"72,593,920 parameters, got {total}", total == 72_593_920)


def test_a_transposed_or_bias_grown_checkpoint_is_refused() -> None:
    print("the two refusals:")
    real = maml_convert.whisper_inventory(maml_convert.CHECKPOINTS["whisper"])
    maml_convert.check_checkpoint(real, "whisper")
    check("the real inventory is accepted", True)

    flipped = {
        name: (list(reversed(shape)) if name.endswith(".weight") and len(shape) == 2 else shape)
        for name, shape in real.items()
    }
    try:
        maml_convert.check_checkpoint(flipped, "whisper")
        caught = ""
    except SystemExit as exit:
        caught = str(exit)
    check("a transposed checkpoint is refused", bool(caught))
    check("and fc1 is named in the message", "fc1" in caught)

    # A checkpoint that grew the bias the converter synthesises. It must fail, not be read as this
    # table: the emitted bias would be zeros while the real one is not, which is silent.
    grown = dict(real)
    grown["model.encoder.layers.0.self_attn.k_proj.bias"] = [512]
    try:
        maml_convert.check_checkpoint(grown, "whisper")
        caught = ""
    except SystemExit as exit:
        caught = str(exit)
    check("a checkpoint that grew a k_proj bias is refused", bool(caught))
    check("and it is named as unexpected", "k_proj.bias" in caught)


def test_the_table_order_is_the_contract() -> None:
    print("order:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_whisper(get, TOY)
    ops = [layer.op for layer in layers]
    encoder_layer = ["LayerNorm"] + ["Linear8"] * 4 + ["LayerNorm", "Linear8", "Linear8"]
    decoder_layer = (
        ["LayerNorm"] + ["Linear8"] * 4
        + ["LayerNorm"] + ["Linear8"] * 4
        + ["LayerNorm", "Linear8", "Linear8"]
    )
    expected = (
        ["Conv8", "Conv8", "Constant"]
        + encoder_layer * TOY["encoder_layers"]
        + ["LayerNorm"]
        + ["Head", "Table"]
        + decoder_layer * TOY["decoder_layers"]
        + ["LayerNorm"]
    )
    check("the whole table is encoder then decoder, in forward order", ops == expected)
    names = [layer.name for layer in layers]
    check("conv1 comes first", names[0].endswith("conv1"))
    check("then conv2", names[1].endswith("conv2"))
    check("then the encoder positions", names[2].endswith("encoder.embed_positions"))
    check("the tied head opens the decoder", names.count("model.decoder.embed_tokens") == 1)
    check("and is emitted once, unsplit", ops.count("Head") == 1)
    check("the decoder's norm is last", names[-1] == "model.decoder.layer_norm")
    # The stacks are contiguous.
    encoder = [i for i, name in enumerate(names) if ".encoder." in name]
    decoder = [i for i, name in enumerate(names) if ".decoder." in name]
    check("no decoder layer sits inside the encoder", max(encoder) < min(decoder))
    # Every layer's tensors are contiguous and in order, which is how the Rust indexes them.
    at = 0
    contiguous = True
    for layer in layers:
        contiguous = contiguous and layer.first_tensor == at
        at += layer.tensor_count
    check("layer tensor ranges tile the table", contiguous and at == len(tensors))


def test_the_conv_stem_is_lifted_and_strided() -> None:
    print("the conv stem:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_whisper(get, TOY)

    conv1, conv2 = layers[0], layers[1]
    for layer, inputs, stride in ((conv1, TOY["mels"], 1), (conv2, TOY["d_model"], 2)):
        kernel, scale, bias = tensors[layer.first_tensor: layer.first_tensor + 3]
        check(
            f"{layer.name.rsplit('.', 1)[-1]} is [{TOY['d_model']}, {inputs}, 1, 3] int8",
            list(kernel.shape) == [TOY["d_model"], inputs, 1, TOY["conv_kernel"]]
            and kernel.dtype == np.int8,
        )
        check("with one scale per output channel", scale.shape == (TOY["d_model"],))
        check("and the checkpoint's own bias", bias.shape == (TOY["d_model"],) and bias.any())
        check(f"and stride {stride} in its key", f"s={[1, stride]}" in layer.key())
    # The lift is a reshape, not a transpose: element order is unchanged.
    original = get("model.encoder.conv1.weight")
    kernel, scale, _ = tensors[conv1.first_tensor: conv1.first_tensor + 3]
    back = kernel.astype(np.float32).reshape(TOY["d_model"], -1) * scale[:, None]
    check(
        "the lift preserves element order",
        np.allclose(back.reshape(original.shape), original, atol=0.05),
    )


def test_a_key_projection_gets_a_synthesised_zero_bias() -> None:
    print("the absent key bias:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_whisper(get, TOY)
    keys = [layer for layer in layers if layer.name.endswith("k_proj")]
    check(f"{TOY['encoder_layers'] + TOY['decoder_layers'] * 2} key projections", len(keys) == 4)
    for layer in keys:
        kernel, scale, bias = tensors[layer.first_tensor: layer.first_tensor + 3]
        check(f"{layer.name.split('.layers.')[-1]}: still an int8 triple", layer.tensor_count == 3)
        check("with a zero bias", bias.shape == (TOY["d_model"],) and not bias.any())
        check("recorded in the key", "b0=synthesised" in layer.key())
        check("and a real kernel", kernel.dtype == np.int8 and scale.any())
    # And no other projection has one, which is what says the synthesis is targeted.
    others = [
        layer
        for layer in layers
        if layer.op == "Linear8" and not layer.name.endswith("k_proj")
    ]
    check(
        "no other projection is synthesised",
        all("b0=synthesised" not in layer.key() for layer in others),
    )


def test_the_position_tables_go_opposite_ways() -> None:
    print("the head and the tables:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_whisper(get, TOY)

    head = next(layer for layer in layers if layer.op == "Head")
    kernel, scale, bias = tensors[head.first_tensor: head.first_tensor + 3]
    check(
        f"[{TOY['vocab']}, {TOY['d_model']}, 1, 1] int8",
        list(kernel.shape) == [TOY["vocab"], TOY["d_model"], 1, 1] and kernel.dtype == np.int8,
    )
    check("one scale per class", scale.shape == (TOY["vocab"],))
    check("a tied head's bias is zero", bias.shape == (TOY["vocab"],) and not bias.any())
    check("and the tie is recorded in the key", "tied=embed_tokens" in head.key())

    # The encoder's table is added to the conv stem's output, which is a device tensor, so it is
    # transposed to `[width, 1, positions]` and emitted as a `Constant`.
    encoder = next(layer for layer in layers if layer.op == "Constant")
    values = tensors[encoder.first_tensor]
    check("the encoder's table is a Constant", encoder.name.endswith("encoder.embed_positions"))
    check(
        f"transposed to [{TOY['d_model']}, 1, {TOY['source_positions']}]",
        list(values.shape) == [TOY["d_model"], 1, TOY["source_positions"]],
    )
    original = get("model.encoder.embed_positions.weight")
    check(
        "so channel c of position p is table[c][0][p]",
        np.allclose(values.reshape(TOY["d_model"], TOY["source_positions"]), original.T),
    )

    # The decoder's is added to a gathered embedding row on the host, so it is left alone.
    decoder = next(layer for layer in layers if layer.op == "Table")
    values = tensors[decoder.first_tensor]
    check("the decoder's table is a host Table", decoder.name.endswith("decoder.embed_positions"))
    check(
        f"left [{TOY['target_positions']}, {TOY['d_model']}]",
        list(values.shape) == [TOY["target_positions"], TOY["d_model"]],
    )
    check(
        "unchanged, because the host reads it",
        np.allclose(values, get("model.decoder.embed_positions.weight")),
    )


def test_the_digest_covers_the_order() -> None:
    print("the digest:")
    get, _ = toy_checkpoint(TOY)
    layers, _ = maml_convert.collect_whisper(get, TOY)
    before = maml_convert.layer_table_digest(layers)
    # A key is what the Rust hardcodes about a layer, not its name, so the pair swapped here has to
    # differ in shape. The two convolutions differ in their stride as well as their input width.
    swapped = list(layers)
    swapped[0], swapped[1] = swapped[1], swapped[0]
    check("swapping the two convolutions changes it", maml_convert.layer_table_digest(swapped) != before)
    q = next(i for i, layer in enumerate(layers) if layer.name.endswith("q_proj"))
    fc1 = next(i for i, layer in enumerate(layers) if layer.name.endswith("fc1"))
    swapped = list(layers)
    swapped[q], swapped[fc1] = swapped[fc1], swapped[q]
    check("and so does a reordered projection pair", maml_convert.layer_table_digest(swapped) != before)
    check("the same table does not", maml_convert.layer_table_digest(list(layers)) == before)
    # A key projection and a query projection are the same shape, so `b0=synthesised` is the only
    # thing distinguishing them in the digest — which is exactly why it is in the key.
    keys = "\n".join(layer.key() for layer in layers)
    check(
        "every key projection is marked synthesised",
        keys.count("b0=synthesised") == 4 + 1,  # four k_proj, plus the tied head
    )


def main() -> int:
    test_the_inventory_is_the_real_architecture()
    test_a_transposed_or_bias_grown_checkpoint_is_refused()
    test_the_table_order_is_the_contract()
    test_the_conv_stem_is_lifted_and_strided()
    test_a_key_projection_gets_a_synthesised_zero_bias()
    test_the_position_tables_go_opposite_ways()
    test_the_digest_covers_the_order()
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
