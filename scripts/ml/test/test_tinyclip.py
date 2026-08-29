#!/usr/bin/env python3
"""test_tinyclip.py - unit test for `maml_convert`'s TinyCLIP path.

`collect_tinyclip` is the one converter arm that reads an ONNX graph **by name** rather than walking
it topologically, and the reason is that TinyCLIP's export gives it no choice: two towers in one
graph, interleaved, with all 80 projections held in anonymous `onnx::MatMul_NNNN` initializers.
`check_architecture` is therefore the whole shape guard, and `collect_tinyclip`'s emission *order*
is the whole contract with `nets::tinyclip`.

Both are checked here against a toy graph of the same structure rather than the real 94 MB export,
so this runs in a second and needs no download.

What each check is defending against:

* **The transpose, in two directions at once.** A `MatMul` weight is `[in, out]` and a 1x1 kernel is
  `[out, in, 1, 1]`, so a projection is a transpose. But the `Conv` patch kernel is `[M, C, kH, kW]`
  already and must **not** be transposed. Getting either wrong yields a tensor of the right shape
  holding the wrong numbers; 76 of the 80 projections are square, so only `fc1`/`fc2` and the two
  512-wide heads make it visible in a shape at all.
* **The position tables, which go opposite ways.** The vision table is transposed to
  `[width, 1, positions]` because the device adds it to a channel-major sequence; the text table is
  left `[positions, width]` because the *host* reads it. Swapping them is silent.
* **The order.** `nets::tinyclip` walks the tensor table positionally. A layer emitted out of order
  loads cleanly and infers nonsense.
* **The int8 triple.** `Builder::conv_int8` reads kernel, then per-output-channel scale, then bias.
  The token embedding is the one **pair** — kernel and scale, no bias — because nothing convolves
  with it; the host gathers a row.

Run:

    python3 scripts/ml/test/test_tinyclip.py

Exit code 0 = all assertions passed.
"""

from __future__ import annotations

import os
import sys

import numpy as np
import onnx
from onnx import helper, numpy_helper

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import maml_convert  # noqa: E402  (needs the path above)

# The real architecture with every dimension shrunk, so the table has the same *structure* — a
# patch conv, a class token, two position tables, two towers of pre-norm layers and two projections
# — at a size that builds instantly. `heads` is unused by the converter and kept so the toy is a
# valid spec.
TOY = {
    "width": 4,
    "heads": 2,
    "ffn": 8,
    "vision_layers": 2,
    "text_layers": 1,
    "patch": 2,
    "grid": 3,
    "vocab": 5,
    "context": 6,
    "projection": 3,
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


def toy_export(spec, transpose_projections: bool = False):
    """An `onnx.ModelProto` of `spec`'s shape, filled with distinguishable values.

    Only the initializer list and the node names are populated: those are all
    `check_architecture` and `collect_tinyclip` read, and a topologically valid CLIP would be a
    hundred lines of scaffolding testing nothing.
    """
    rng = np.random.default_rng(11)
    parameters, projections = maml_convert.tinyclip_inventory(spec)
    initializers = [
        numpy_helper.from_array(rng.standard_normal(shape).astype(np.float32), name)
        for name, shape in parameters.items()
    ]
    nodes = []
    for index, (node_name, shape) in enumerate(sorted(projections.items())):
        if transpose_projections:
            shape = list(reversed(shape))
        weight = f"onnx::MatMul_{index}"
        initializers.append(
            numpy_helper.from_array(rng.standard_normal(shape).astype(np.float32), weight)
        )
        nodes.append(
            helper.make_node("MatMul", ["x", weight], [f"{node_name}_out"], name=node_name)
        )
    graph = helper.make_graph(nodes, "toy", [], [], initializer=initializers)
    return helper.make_model(graph)


def test_the_inventory_is_the_real_architecture() -> None:
    print("inventory:")
    spec = maml_convert.ARCHITECTURES["tinyclip"]
    parameters, projections = maml_convert.tinyclip_inventory(spec)
    # 10 vision layers and 3 text layers of 6 named tensors each, plus 15 outside the towers.
    check(f"141 named parameters, got {len(parameters)}", len(parameters) == 141)
    # 13 layers of 6 projections, plus the two towers' output heads.
    check(f"80 projections, got {len(projections)}", len(projections) == 80)
    check(
        "the patch kernel is [width, 3, patch, patch]",
        parameters["vision_model.embeddings.patch_embedding.weight"] == [256, 3, 16, 16],
    )
    check(
        "the token embedding is [vocab, width]",
        parameters["text_model.embeddings.token_embedding.weight"] == [49_408, 256],
    )
    check(
        "the vision positions are the folded [1, 197, width]",
        parameters[maml_convert.VISION_POSITIONS_FOLDED] == [1, 197, 256],
    )
    # The upstream typo, which is load-bearing: the vision tower normalises before the stack as
    # well as after it, and missing that layer is a plausible-looking embedding.
    check("pre_layrnorm is spelled the export's way", "vision_model.pre_layrnorm.weight" in parameters)
    # The asymmetric pair a transpose would swap.
    check(
        "fc1 is [width, ffn]",
        projections["/vision_model/encoder/layers.0/mlp/fc1/MatMul"] == [256, 1024],
    )
    check(
        "fc2 is [ffn, width]",
        projections["/vision_model/encoder/layers.0/mlp/fc2/MatMul"] == [1024, 256],
    )
    check(
        "the text tower has three layers and no fourth",
        "/text_model/encoder/layers.2/mlp/fc1/MatMul" in projections
        and "/text_model/encoder/layers.3/mlp/fc1/MatMul" not in projections,
    )
    total = sum(int(np.prod(shape)) for shape in parameters.values())
    total += sum(int(np.prod(shape)) for shape in projections.values())
    check(f"23,446,016 parameters, got {total}", total == 23_446_016)


def test_a_transposed_export_is_refused() -> None:
    print("the transpose:")
    spec = maml_convert.ARCHITECTURES["tinyclip"]
    flipped = toy_export(TOY, transpose_projections=True)
    try:
        maml_convert.check_architecture(flipped, "tinyclip", TOY)
        caught = ""
    except SystemExit as exit:
        caught = str(exit)
    check("a transposed export is refused", bool(caught))
    check("and fc1 is named in the message", "fc1" in caught)

    toy = toy_export(TOY)
    maml_convert.check_architecture(toy, "tinyclip", TOY)
    check("the toy export is accepted against its own spec", True)
    try:
        maml_convert.check_architecture(toy, "tinyclip", spec)
        refused = False
    except SystemExit:
        refused = True
    check("and refused against the real one", refused)


def test_the_table_order_is_the_contract() -> None:
    print("order:")
    layers, tensors = maml_convert.collect_tinyclip(toy_export(TOY), TOY)
    ops = [layer.op for layer in layers]
    layer_ops = ["LayerNorm"] + ["Linear8"] * 4 + ["LayerNorm", "Linear8", "Linear8"]
    expected = (
        ["Conv8", "Constant", "Constant", "LayerNorm"]
        + layer_ops * TOY["vision_layers"]
        + ["LayerNorm", "Linear8"]
        + ["Embed8", "Constant"]
        + layer_ops * TOY["text_layers"]
        + ["LayerNorm", "Linear8"]
    )
    check("the whole table is vision then text, in forward order", ops == expected)
    names = [layer.name for layer in layers]
    check("the patch kernel comes first", names[0].endswith("patch_embedding.weight"))
    check("then the class token", names[1].endswith("class_embedding"))
    check("then the vision positions", names[2] == maml_convert.VISION_POSITIONS_FOLDED)
    check("the visual projection ends the vision tower", "visual_projection" in names)
    check("the text projection is last", names[-1] == "text_projection")
    # The towers are contiguous, unlike the export, which interleaves them.
    vision = [i for i, name in enumerate(names) if name.startswith("vision")]
    text = [i for i, name in enumerate(names) if name.startswith("text")]
    check("no text layer sits inside the vision tower", max(vision) < min(text))
    # Every layer's tensors are contiguous and in order, which is how the Rust indexes them.
    at = 0
    contiguous = True
    for layer in layers:
        contiguous = contiguous and layer.first_tensor == at
        at += layer.tensor_count
    check("layer tensor ranges tile the table", contiguous and at == len(tensors))


def test_a_projection_is_transposed_and_the_conv_is_not() -> None:
    print("the two orientations:")
    export = toy_export(TOY)
    inits = {i.name: numpy_helper.to_array(i) for i in export.graph.initializer}
    nodes = {n.name: n for n in export.graph.node}
    layers, tensors = maml_convert.collect_tinyclip(export, TOY)

    fc1 = next(layer for layer in layers if layer.name.endswith("mlp.fc1"))
    kernel, scale, bias = tensors[fc1.first_tensor: fc1.first_tensor + 3]
    check("fc1's kernel is int8", kernel.dtype == np.int8)
    check(
        f"shaped [{TOY['ffn']}, {TOY['width']}, 1, 1]",
        list(kernel.shape) == [TOY["ffn"], TOY["width"], 1, 1],
    )
    check("with one scale per output channel", scale.shape == (TOY["ffn"],))
    check("and the export's own bias", bias.shape == (TOY["ffn"],) and bias.any())
    # The values, not just the shape: `kernel * scale` must reproduce the *transposed* weight.
    node = "/vision_model/encoder/layers.0/mlp/fc1/MatMul"
    held = next(x for x in nodes[node].input if x in inits)
    want = inits[held].T
    back = kernel.astype(np.float32).reshape(TOY["ffn"], TOY["width"]) * scale[:, None]
    check("and reproduces the transposed weight", np.allclose(back, want, atol=0.02))

    patch = layers[0]
    kernel, scale, bias = tensors[patch.first_tensor: patch.first_tensor + 3]
    original = inits["vision_model.embeddings.patch_embedding.weight"]
    check("the patch kernel keeps ONNX's [M, C, kH, kW]", kernel.shape == original.shape)
    back = kernel.astype(np.float32).reshape(TOY["width"], -1) * scale[:, None]
    check(
        "and is emitted verbatim, not transposed",
        np.allclose(back.reshape(original.shape), original, atol=0.05),
    )
    check("with a synthesised zero bias", bias.shape == (TOY["width"],) and not bias.any())


def test_the_position_tables_go_opposite_ways() -> None:
    print("the position tables:")
    export = toy_export(TOY)
    inits = {i.name: numpy_helper.to_array(i) for i in export.graph.initializer}
    layers, tensors = maml_convert.collect_tinyclip(export, TOY)
    positions = TOY["grid"] ** 2 + 1

    vision = next(layer for layer in layers if layer.name == maml_convert.VISION_POSITIONS_FOLDED)
    table = tensors[vision.first_tensor]
    check(
        f"the vision table is [width, 1, {positions}]",
        list(table.shape) == [TOY["width"], 1, positions],
    )
    original = np.squeeze(inits[maml_convert.VISION_POSITIONS_FOLDED])
    check(
        "transposed, so channel c of position p is table[c][0][p]",
        np.allclose(table.reshape(TOY["width"], positions), original.T),
    )

    text = next(
        layer for layer in layers if layer.name.endswith("position_embedding.weight")
    )
    table = tensors[text.first_tensor]
    check(
        f"the text table stays [{TOY['context']}, width]",
        list(table.shape) == [TOY["context"], TOY["width"]],
    )
    check(
        "unchanged, because the host reads it",
        np.allclose(table, inits["text_model.embeddings.position_embedding.weight"]),
    )

    embedding = next(layer for layer in layers if layer.op == "Embed8")
    check("the token embedding is a pair, not a triple", embedding.tensor_count == 2)
    kernel, scale = tensors[embedding.first_tensor: embedding.first_tensor + 2]
    check(
        f"[{TOY['vocab']}, width, 1, 1] int8",
        list(kernel.shape) == [TOY["vocab"], TOY["width"], 1, 1] and kernel.dtype == np.int8,
    )
    check("with one scale per row, which is per token", scale.shape == (TOY["vocab"],))


def test_the_digest_covers_the_order() -> None:
    print("the digest:")
    layers, _ = maml_convert.collect_tinyclip(toy_export(TOY), TOY)
    before = maml_convert.layer_table_digest(layers)
    # A key is what the Rust hardcodes about a layer, not its name, so the pair swapped here has to
    # differ in shape. Two `[width]` layer norms have the same key and are interchangeable in the
    # digest, which is correct: swapping them changes nothing the forward pass can observe.
    q_proj = next(i for i, layer in enumerate(layers) if layer.name.endswith("q_proj"))
    fc1 = next(i for i, layer in enumerate(layers) if layer.name.endswith("fc1"))
    swapped = list(layers)
    swapped[q_proj], swapped[fc1] = swapped[fc1], swapped[q_proj]
    check("a reordered pair changes it", maml_convert.layer_table_digest(swapped) != before)
    check("the same table does not", maml_convert.layer_table_digest(list(layers)) == before)
    # The synthesised biases are in the keys, so an export that grew one is not read as this table.
    keys = "\n".join(layer.key() for layer in layers)
    check("the three synthesised biases are recorded", keys.count("b0=synthesised") == 3)


def main() -> int:
    test_the_inventory_is_the_real_architecture()
    test_a_transposed_export_is_refused()
    test_the_table_order_is_the_contract()
    test_a_projection_is_transposed_and_the_conv_is_not()
    test_the_position_tables_go_opposite_ways()
    test_the_digest_covers_the_order()
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
