#!/usr/bin/env python3
"""Check a `.maml` forward pass against onnxruntime, numerically.

Nothing else in this repo compares the *numbers* `:library:ml` produces against the export
they came from. The per-op fixtures in `nets::reference` check each op against hand
arithmetic, and the layer table digest checks that the shapes still line up, but neither
notices a weight that is subtly wrong. Both bugs this script found were exactly that shape:

* `ppocr_fold.py` folded a scalar affine into a **padded** convolution, which is only exact
  at interior pixels. That biased the border of every feature map in both PP-OCRv5 models
  and changed 5% of the shipped detection weights.
* A fused QKV projection's bias `Add` became unreachable once its slices were rewritten, so
  dead-code elimination removed it and all three projections got a zero bias. Every shape
  check, the tensor count and the digest passed.

So this exists, and is worth running whenever `ppocr_fold.py` or a net module changes.

# How to run it

    ./scripts/ml/fetch_and_convert.sh                       # produce the .maml first
    python scripts/ml/onnx_parity.py <graph> <inference.onnx> [--width N]

It writes a scratch directory, runs onnxruntime, then asks the Rust reference interpreter
for the same tensor and reports the difference. `onnxruntime` is an extra dependency:

    python -m pip install onnxruntime

# What "agreeing" means

`.maml` is fp16, so the two cannot agree exactly. The bar is that the difference is no
larger than fp16 weight quantisation alone accounts for, which this script measures by
running onnxruntime a second time with every weight rounded through fp16. A difference
materially above that line is a bug, not precision.
"""

import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np
import onnx
import onnxruntime
from onnx import numpy_helper

# The tensor to compare per graph, and why it rather than the network's output.
#
# Detection ends in a sigmoid over a text-probability map, which on a synthetic input is
# saturated to zero everywhere — comparing it would compare zeros. Its backbone output is
# where all fourteen of its surviving affines live, so that is the informative tensor.
PROBE = {
    "ppocr_rec": None,
    "ppocr_det": "p2o.pd_op.hardswish.23.0",
    "supertonic_voc": None,
    # The sentence encoder's output, before the CLS slice. The graph's own output is a single
    # scalar, and a correlation over one value is not a number - this is where all but 25k of
    # the parameters are, and it is `[64, T + 1]` so a structural error has nowhere to hide.
    "supertonic_dp": "/sentence_encoder/Add_output_0",
    # The graph's own output, which is the whole conditioning sequence.
    "supertonic_ttl": None,
    # `denoised_latent`, the graph's own output. That is a whole Euler step over both guidance
    # branches, so the runtime side runs the plan twice - which is the point worth checking.
    "supertonic_ve": None,
    # TinyCLIP is two towers in one graph, so it appears twice: the key selects which one, and the
    # runtime side branches on the same key.
    #
    # The probe is each projection's **raw output**, not the graph's `image_embeds`/`text_embeds` —
    # `CLIPModel` L2-normalises those before returning them, and comparing unit vectors would hide a
    # uniform scale error, which per-output-channel quantisation is exactly the kind of thing to
    # produce. `ClipEmbedder.l2Normalize` does the normalisation on the host either way.
    "tinyclip": "/visual_projection/MatMul_output_0",
    "tinyclip_text": "/text_projection/MatMul_output_0",
}

# Graphs whose input is a latent rather than an image, and how wide it is. One that is a
# module of a larger export also names its `first` node, and `onnx.utils.extract_model` cuts
# the subgraph there so it can be run at all; the cut promotes that node's input to a graph
# input, which is then fed random latents.
SUBGRAPH = {
    # A whole file, not a module: no `first`, so nothing is extracted. 144 latent channels.
    "supertonic_voc": {"channels": 144},
}

# TinyCLIP's fixed input side, and the two special tokens `ClipTokenizer` brackets a query with.
# 49,406 and 49,407 are the last two entries of the 49,408-word vocabulary, which is what makes the
# export's `ArgMax` over `input_ids` find the end-of-text position.
TINYCLIP_IMAGE = 224
TINYCLIP_SOT = 49_406
TINYCLIP_EOT = 49_407


def ramp_image(channels, height, width):
    """A deterministic `[1, c, h, w]` input, free of transcendentals so both sides read the same
    bytes."""
    count = channels * height * width
    ramp = ((np.arange(count, dtype=np.int64) * 37) % 255).astype(np.float32) / 255.0 - 0.5
    return ramp.reshape(1, channels, height, width)


def rounded_to_fp16(model):
    """`model` with every weight put through fp16, to measure quantisation on its own."""
    clone = onnx.ModelProto()
    clone.CopyFrom(model)
    for node in clone.graph.node:
        if node.op_type != "Constant":
            continue
        for attribute in node.attribute:
            if attribute.name != "value":
                continue
            value = numpy_helper.to_array(attribute.t)
            if value.dtype == np.float32 and value.size >= 8:
                attribute.t.CopyFrom(
                    numpy_helper.from_array(
                        value.astype(np.float16).astype(np.float32), attribute.t.name
                    )
                )
    for initializer in clone.graph.initializer:
        value = numpy_helper.to_array(initializer)
        if value.dtype == np.float32 and value.size >= 8:
            initializer.CopyFrom(
                numpy_helper.from_array(
                    value.astype(np.float16).astype(np.float32), initializer.name
                )
            )
    return clone


def final_logits(model):
    """The name of the tensor before a trailing `Softmax`, or the graph's own output.

    `nets::ppocr_rec` deliberately does not run the classifier's softmax, so the comparable
    tensor is its input.
    """
    outputs = {o.name for o in model.graph.output}
    for node in model.graph.node:
        if node.op_type == "Softmax" and node.output[0] in outputs:
            return node.input[0]
    return next(iter(outputs))


def run(model, probe, x, feed=None):
    session = onnxruntime.InferenceSession(
        model.SerializeToString(), providers=["CPUExecutionProvider"]
    )
    names = [o.name for o in session.get_outputs()]
    inputs = feed if feed is not None else {session.get_inputs()[0].name: x}
    outputs = session.run(None, inputs)
    return np.squeeze(np.asarray(outputs[names.index(probe)]))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("graph", choices=sorted(PROBE))
    parser.add_argument("onnx")
    parser.add_argument("--width", type=int, default=64)
    parser.add_argument("--maml", help="the .maml, for a graph that is a runtime download")
    parser.add_argument(
        "--probe",
        help="compare this tensor instead of the graph's usual one, for isolating a layer",
    )
    args = parser.parse_args()

    onnxruntime.set_default_logger_severity(3)
    model = onnx.load(args.onnx)
    work = tempfile.mkdtemp(prefix="maml_parity_")

    cut = SUBGRAPH.get(args.graph)
    # A cut without a `first` is a whole-graph model that merely needs a non-image input
    # shape - Supertonic ships its vocoder as its own file rather than as a module of a
    # larger one, so there is nothing to extract.
    if cut is not None and cut.get("first"):
        model = extract(args.onnx, model, cut, work)
    probe = args.probe or PROBE[args.graph] or final_logits(model)
    if probe not in {o.name for o in model.graph.output}:
        model.graph.output.append(
            onnx.helper.make_tensor_value_info(probe, onnx.TensorProto.FLOAT, None)
        )

    feed = None
    if cut is not None:
        # Random latents, deterministic. These nets are not classifiers, so there is no
        # "sensible" input; what matters is that both sides see the same one.
        rng = np.random.default_rng(20240827)
        x = rng.standard_normal((1, cut["channels"], args.width)).astype(np.float32)
        if cut.get("ones"):
            session_inputs = {i.name: i for i in model.graph.input}
            mask = session_inputs[cut["ones"]]
            rank = len(mask.type.tensor_type.shape.dim)
            shape = [1] * (rank - 1) + [args.width]
            feed = {
                model.graph.input[0].name: x,
                cut["ones"]: np.ones(shape, dtype=np.float32),
            }
        if cut.get("noise"):
            # Two channels of already-scaled noise, written out for the runtime to read after
            # the conditioning input.
            draw = rng.standard_normal((1, 2, args.width)).astype(np.float32) * 0.8
            feed[cut["noise"]] = draw
            draw.tofile(os.path.join(work, "noise.f32"))
    elif args.graph in ("supertonic_dp", "supertonic_ttl"):
        # Character ids and a voice's style. The ids go past 2048, where fp16 stops holding
        # every integer, so the runtime reads them as two lanes - which is the whole reason
        # `embed_lanes` exists.
        rng = np.random.default_rng(20240827)
        ids = rng.integers(0, 8322, size=(1, args.width), dtype=np.int64)
        if args.graph == "supertonic_dp":
            # `[1, 8, 16]`, which flattens row-major to 128.
            style = rng.standard_normal((1, 8, 16)).astype(np.float32)
            feed = {"text_ids": ids, "style_dp": style}
        else:
            # `[1, 50, 256]` position-major, which the runtime reads transposed. Writing the
            # transpose here is what the voice bundle will hold.
            style = rng.standard_normal((1, 50, 256)).astype(np.float32)
            feed = {"text_ids": ids, "style_ttl": style}
            style = np.ascontiguousarray(style.reshape(50, 256).T)
        feed["text_mask"] = np.ones((1, 1, args.width), dtype=np.float32)
        x = ids.astype(np.float32)
        style.tofile(os.path.join(work, "style.f32"))
    elif args.graph == "supertonic_ve":
        # A latent, a text conditioning and a voice. `--width` is the latent length; the text is
        # deliberately a different length, because the rotary angles normalise each sequence by
        # its own and a square case would hide a swap.
        rng = np.random.default_rng(20240827)
        chars = max(4, args.width // 2)
        latent = rng.standard_normal((1, 144, args.width)).astype(np.float32)
        text = rng.standard_normal((1, 256, chars)).astype(np.float32)
        style = rng.standard_normal((1, 50, 256)).astype(np.float32)
        feed = {
            "noisy_latent": latent,
            "text_emb": text,
            "style_ttl": style,
            "latent_mask": np.ones((1, 1, args.width), dtype=np.float32),
            "text_mask": np.ones((1, 1, chars), dtype=np.float32),
            "current_step": np.array([5.0], dtype=np.float32),
            "total_step": np.array([16.0], dtype=np.float32),
        }
        x = latent
        text.tofile(os.path.join(work, "text.f32"))
        # Channel-major, which is how the runtime holds a sequence.
        np.ascontiguousarray(style.reshape(50, 256).T).tofile(os.path.join(work, "style.f32"))
        os.environ["PARITY_CHARS"] = str(chars)
    elif args.graph in ("tinyclip", "tinyclip_text"):
        # The export is one graph with three inputs, and every call needs all of them even though
        # only one tower is read - which is exactly why `ClipEmbedder` passed a dummy for the other
        # side. So both cases below feed pixels *and* ids, and differ only in which is meaningful.
        #
        # `attention_mask` is all ones, as `ClipEmbedder` sends: CLIP's text tower is causal and
        # pools at the end-of-text position, so there is nothing for a padding mask to do.
        pixels = ramp_image(3, TINYCLIP_IMAGE, TINYCLIP_IMAGE)
        if args.graph == "tinyclip":
            # The shortest possible prompt, so the text side is cheap and unread.
            ids = np.array([[TINYCLIP_SOT, TINYCLIP_EOT]], dtype=np.int64)
            x = pixels
        else:
            # A short query, `<|startoftext|>` then pieces then `<|endoftext|>`. The ids stop at the
            # end-of-text token rather than padding to 77: the causal mask makes the pooled vector
            # identical either way, and the runtime relies on that to run `eot + 1` positions.
            rng = np.random.default_rng(20240827)
            pieces = rng.integers(0, TINYCLIP_SOT, size=args.width, dtype=np.int64)
            ids = np.concatenate(
                [[TINYCLIP_SOT], pieces, [TINYCLIP_EOT]]
            ).reshape(1, -1)
            # The runtime reads these from `input.f32` as f32, which is exact: every id is under
            # 49,408 and fp32 holds every integer to 2^24.
            x = ids.astype(np.float32)
        feed = {
            "pixel_values": pixels,
            "input_ids": ids,
            "attention_mask": np.ones_like(ids),
        }
    else:
        height = 48 if args.graph == "ppocr_rec" else args.width
        x = ramp_image(3, height, args.width)
    x.tofile(os.path.join(work, "input.f32"))

    reference = run(model, probe, x, feed)
    quantised = run(rounded_to_fp16(model), probe, x, feed)

    environment = dict(os.environ, PARITY_DIR=work, PARITY_GRAPH=args.graph)
    environment["PARITY_WIDTH"] = str(args.width)
    if args.maml:
        environment["PARITY_MAML"] = os.path.abspath(args.maml)
    subprocess.run(
        [
            "cargo", "test", "--release", "-p", "modelrunner", "--lib",
            "dump_reference_output", "--", "--ignored", "--nocapture",
        ],
        check=True,
        env=environment,
    )
    got = np.fromfile(os.path.join(work, "reference.f32"), dtype=np.float32)

    # The runtime writes `[c, h, w]`; reshape the ONNX tensor to match by element count.
    want = np.ascontiguousarray(reference).flatten()
    # Supertonic's sampler runs both classifier-free-guidance branches in one batch of two, so
    # an *internal* probe of it has twice the runtime's element count. The conditional branch is
    # the first half. The graph's own output is already combined, so this does not fire there.
    if want.size == got.size * 2 and args.graph == "supertonic_ve":
        want = want[: got.size]
        quantised = np.ascontiguousarray(quantised).flatten()[: got.size]
    if want.size != got.size:
        raise SystemExit(f"{probe}: onnx has {want.size} values, the runtime {got.size}")
    if args.graph == "ppocr_rec":
        # `[1, T, 838]` against the runtime's class-major `[838, T]`.
        want = np.ascontiguousarray(reference.T).flatten()

    def report(label, a, b):
        d = np.abs(a - b)
        print(
            f"  {label:28s} max {d.max():.6f}  mean {d.mean():.7f}  "
            f"corr {np.corrcoef(a, b)[0, 1]:.8f}"
        )

    bound = np.ascontiguousarray(
        quantised.T if args.graph == "ppocr_rec" else quantised
    ).flatten()
    # The tensor's own scale, because "max 0.008" means nothing on its own: the bar measures
    # fp16 *weights* against fp32 activations, and this runtime holds activations in fp16 too,
    # so a difference of order `eps * |tensor|` is the format rather than a bug.
    print(
        f"{args.graph} at {args.width} wide, tensor {probe}: "
        f"|values| max {np.abs(want).max():.4f}"
    )
    report("fp16 weights alone (the bar)", want, bound)
    report("the runtime", want, got)


def inline_constants(model):
    """Turn every `Constant` node into an initializer, in place.

    Cutting a module out of a larger export breaks whenever a node inside it reads a
    `Constant` produced outside it — the flow's four channel reversals each read three
    constants that belong to the encoder, so extracting the flow alone leaves them dangling.
    An initializer travels with any subgraph that references it, so inlining first makes the
    cut work wherever it is placed.
    """
    keep = []
    for node in model.graph.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    tensor = onnx.TensorProto()
                    tensor.CopyFrom(attribute.t)
                    tensor.name = node.output[0]
                    model.graph.initializer.append(tensor)
                    break
            else:
                keep.append(node)
        else:
            keep.append(node)
    del model.graph.node[:]
    model.graph.node.extend(keep)
    return model


def extract(path, model, cut, work):
    """Cut one module out of a larger export, so it can be run on its own.

    The module's input is whatever fed its first layer, which `onnx.utils.extract_model`
    promotes to a graph input.
    """
    first = next((n for n in model.graph.node if n.name == cut["first"]), None)
    if first is None:
        raise SystemExit(f"{cut['first']} is not in this export")
    prefix = cut["prefix"]
    outputs = [n.output[0] for n in model.graph.node if n.name.startswith(prefix)]
    # Inline first, then write the whole model back out: `extract_model` reads from a path.
    flattened = os.path.join(work, "flattened.onnx")
    onnx.save(inline_constants(onnx.load(path)), flattened)
    target = os.path.join(work, "subgraph.onnx")
    inputs = [first.input[0]]
    for extra in ("ones", "noise"):
        if cut.get(extra):
            inputs.append(cut[extra])
    wanted = [cut.get("output") or outputs[-1]]
    onnx.utils.extract_model(flattened, target, inputs, wanted)
    return onnx.load(target)


if __name__ == "__main__":
    main()
