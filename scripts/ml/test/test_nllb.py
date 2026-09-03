#!/usr/bin/env python3
"""test_nllb.py - unit test for `maml_convert`'s NLLB checkpoint path.

Mirrors `test_small100.py`: `collect_nllb` is the one converter arm with no ONNX graph
behind it, so `check_checkpoint` is the whole guard and `collect_nllb`'s emission
*order* is the whole contract with `nets::nllb600`. Both are checked here against a
toy model of the same shape rather than the real 2.46 GB checkpoint, so this runs in
a second and needs no download.

What each check is defending against (same four as small100, plus NLLB's own):

* **The transpose.** A `torch.nn.Linear` weight is `[out, in]` and a 1x1 kernel is
  `[out, in, 1, 1]`, so a projection is a reshape. `fc1` and `fc2` are the asymmetric
  pair that makes it loud.
* **The order.** `nets::nllb600` walks the tensor table positionally. A layer emitted
  out of order loads cleanly and infers nonsense.
* **The tied head appearing once.** Emitting the embedding twice would put 250 MiB
  back into the download.
* **The int8 triple.** `Builder::conv_int8` reads kernel, then per-output-channel
  scale, then bias.
* **The uneven head split.** 256,206 classes do not divide into 4 equal ranges;
  `head_rows` must sum to vocab and tile the classes without gap or overlap.

Run:
    python3 scripts/ml/test/test_nllb.py

Exit code 0 = all assertions passed.
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import maml_convert  # noqa: E402  (needs the path above)

TOY = {
    "d_model": 8,
    "encoder_layers": 2,
    "decoder_layers": 2,
    "ffn": 16,
    "vocab": 10,
    "head_splits": 4,
    "head_rows": [3, 3, 2, 2],
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
    for name, shape in maml_convert.nllb_inventory(spec).items():
        if transpose_projections and name.endswith(".weight") and len(shape) == 2:
            shape = list(reversed(shape))
        held[name] = rng.standard_normal(shape).astype(np.float32)
    return held.__getitem__, {name: list(v.shape) for name, v in held.items()}


def test_the_inventory_is_the_real_architecture() -> None:
    print("inventory:")
    want = maml_convert.nllb_inventory(maml_convert.CHECKPOINTS["nllb600"])
    # 1 shared + 2 final norms + 12 enc * 16 names + 12 dec * 26 names:
    # per encoder layer 2 norms*2 + 4 projs*2 + 2 + 2 = 16 names; per decoder layer
    # 16 + cross norm*2 + 4 projs*2 = 26. The checkpoint holds 512 tensors (509 + the
    # 3 extra tied copies); 1,402,138,624 params total.
    check("509 parameters", len(want) == 509)
    check("the embedding is [vocab, d_model]", want["model.shared.weight"] == [256_206, 1024])
    check("fc1 is [ffn, d_model]", want["model.encoder.layers.0.fc1.weight"] == [4096, 1024])
    check("fc2 is [d_model, ffn]", want["model.encoder.layers.0.fc2.weight"] == [1024, 4096])
    check(
        "the decoder has cross-attention",
        "model.decoder.layers.11.encoder_attn.q_proj.weight" in want,
    )
    check(
        "the encoder has none",
        "model.encoder.layers.0.encoder_attn.q_proj.weight" not in want,
    )
    check(
        "the other three tied copies are absent",
        "lm_head.weight" not in want
        and "model.encoder.embed_tokens.weight" not in want
        and "model.decoder.embed_tokens.weight" not in want,
    )
    check(
        "k_proj carries a bias (unlike whisper)",
        "model.decoder.layers.0.self_attn.k_proj.bias" in want,
    )
    total = sum(int(np.prod(shape)) for shape in want.values())
    # model.shared 256206*1024 + norms + 24 layers of (attn norms + 4/8 projs + ffn).
    # Per encoder layer params: norms 3*2*1024 + projs 4*2*(1024*1024+1024)... computed below.
    print(f"  info  inventory parameters: {total}")


def test_a_transposed_checkpoint_is_refused() -> None:
    print("the transpose:")
    real = maml_convert.nllb_inventory(maml_convert.CHECKPOINTS["nllb600"])
    flipped = {
        name: (list(reversed(shape)) if name.endswith(".weight") and len(shape) == 2 else shape)
        for name, shape in real.items()
    }
    try:
        maml_convert.check_checkpoint(flipped, "nllb600")
        caught = ""
    except SystemExit as exit:
        caught = str(exit)
    check("a transposed checkpoint is refused", bool(caught))
    check("and fc1 is named in the message", "fc1" in caught)
    maml_convert.check_checkpoint(real, "nllb600")
    check("the real inventory is accepted", True)


def test_the_table_order_is_the_contract() -> None:
    print("order:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_nllb(get, TOY)
    ops = [layer.op for layer in layers]

    heads = [i for i, op in enumerate(ops) if op == "Head"]
    check("all four head splits come first", heads == [0, 1, 2, 3])

    encoder = ops[4:4 + 8]
    check(
        "an encoder layer is norm, four projections, norm, fc1, fc2",
        encoder == ["LayerNorm", "Linear8", "Linear8", "Linear8", "Linear8",
                    "LayerNorm", "Linear8", "Linear8"],
    )
    per_encoder = 8
    at = 4 + TOY["encoder_layers"] * per_encoder
    check("the encoder's final norm follows its layers", ops[at] == "LayerNorm")
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
        names[4] == "model.encoder.layers.0.self_attn_layer_norm",
    )
    check("a head split names its class range", names[0].endswith("[0:3]"))
    check("the last split ends at vocab", names[3].endswith(f"[8:{TOY['vocab']}]"))

    at_tensor = 0
    contiguous = True
    for layer in layers:
        contiguous = contiguous and layer.first_tensor == at_tensor
        at_tensor += layer.tensor_count
    check("layer tensor ranges tile the table", contiguous and at_tensor == len(tensors))

    # 4 head layers of 3 + 2*8 enc + 1 + 2*13 dec + 1 layers.
    want_layers = 4 + TOY["encoder_layers"] * 8 + 1 + TOY["decoder_layers"] * 13 + 1
    check(f"{want_layers} layers", len(layers) == want_layers)
    want_tensors = 4 * 3 + TOY["encoder_layers"] * 22 + 2 + TOY["decoder_layers"] * 36 + 2
    check(f"{want_tensors} tensors", len(tensors) == want_tensors)


def test_an_int8_layer_is_kernel_then_scale_then_bias() -> None:
    print("the int8 triple:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_nllb(get, TOY)

    for index, rows in enumerate(TOY["head_rows"]):
        head = layers[index]
        kernel, scale, bias = tensors[head.first_tensor: head.first_tensor + 3]
        check(f"split {index} kernel [{rows}, {TOY['d_model']}, 1, 1] int8",
              kernel.dtype == np.int8 and list(kernel.shape) == [rows, TOY["d_model"], 1, 1])
        check(f"split {index} one scale per class",
              scale.shape == (rows,) and scale.dtype != np.int8)
        check(f"split {index} tied bias is zero",
              bias.shape == (rows,) and not bias.any())

    ffn = next(layer for layer in layers if layer.name.endswith("fc1"))
    kernel, scale, bias = tensors[ffn.first_tensor: ffn.first_tensor + 3]
    check(
        f"fc1's kernel is [{TOY['ffn']}, {TOY['d_model']}, 1, 1]",
        list(kernel.shape) == [TOY["ffn"], TOY["d_model"], 1, 1],
    )
    check("with one scale per output channel", scale.shape == (TOY["ffn"],))
    check("and the checkpoint's own bias", bias.shape == (TOY["ffn"],) and bias.any())


def test_the_digest_covers_the_order() -> None:
    print("the digest:")
    get, _ = toy_checkpoint(TOY)
    layers, _ = maml_convert.collect_nllb(get, TOY)
    before = maml_convert.layer_table_digest(layers)
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
