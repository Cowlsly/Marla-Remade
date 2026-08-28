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
    "vits_dec": None,
    # The prior's mean and log-variance. `enc_p` is reachable from the graph's own inputs, so
    # unlike the vocoder it needs no extraction — the duration predictor runs after it.
    "vits_enc": "/enc_p/proj/Conv_output_0",
}

# Graphs that are one module of a larger export, and have to be cut out of it before they can
# be run at all.
#
# The vocoder's input is the flow's output, so there is no way to reach it from the graph's
# own input without also running the duration predictor — which samples noise, so two runs
# would not even agree with each other. `onnx.utils.extract_model` cuts the subgraph at the
# vocoder's first convolution instead, and the input is random latents.
SUBGRAPH = {
    "vits_dec": {"first": "/dec/conv_pre/Conv", "channels": 192},
}


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
    args = parser.parse_args()

    onnxruntime.set_default_logger_severity(3)
    model = onnx.load(args.onnx)
    work = tempfile.mkdtemp(prefix="maml_parity_")

    cut = SUBGRAPH.get(args.graph)
    if cut is not None:
        model = extract(args.onnx, model, cut, work)
    probe = PROBE[args.graph] or final_logits(model)
    if probe not in {o.name for o in model.graph.output}:
        model.graph.output.append(
            onnx.helper.make_tensor_value_info(probe, onnx.TensorProto.FLOAT, None)
        )

    feed = None
    if cut is not None:
        # Random latents, deterministic. The vocoder is not a classifier, so there is no
        # "sensible" input; what matters is that both sides see the same one.
        rng = np.random.default_rng(20240827)
        x = rng.standard_normal((1, cut["channels"], args.width)).astype(np.float32)
    elif args.graph == "vits_enc":
        # Phoneme ids. int64 for onnxruntime, the same values as fp32 for the runtime, which
        # carries them in the arena — exact, since fp16 holds every integer to 2048.
        rng = np.random.default_rng(20240827)
        ids = rng.integers(0, 130, size=(1, args.width), dtype=np.int64)
        feed = {
            "input": ids,
            "input_lengths": np.array([args.width], dtype=np.int64),
            "scales": np.array([0.667, 1.0, 0.8], dtype=np.float32),
        }
        x = ids.astype(np.float32)
    else:
        height = 48 if args.graph == "ppocr_rec" else args.width
        # Deterministic and free of transcendentals, so both sides read identical bytes.
        count = 3 * height * args.width
        ramp = (
            (np.arange(count, dtype=np.int64) * 37) % 255
        ).astype(np.float32) / 255.0 - 0.5
        x = ramp.reshape(1, 3, height, args.width)
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
    print(f"{args.graph} at {args.width} wide, tensor {probe}:")
    report("fp16 weights alone (the bar)", want, bound)
    report("the runtime", want, got)


def extract(path, model, cut, work):
    """Cut one module out of a larger export, so it can be run on its own.

    The module's input is whatever fed its first layer, which `onnx.utils.extract_model`
    promotes to a graph input.
    """
    first = next((n for n in model.graph.node if n.name == cut["first"]), None)
    if first is None:
        raise SystemExit(f"{cut['first']} is not in this export")
    outputs = [
        o for o in (n.output[0] for n in model.graph.node if n.name.startswith("/dec/"))
    ]
    target = os.path.join(work, "subgraph.onnx")
    onnx.utils.extract_model(path, target, [first.input[0]], [outputs[-1]])
    return onnx.load(target)


if __name__ == "__main__":
    main()
