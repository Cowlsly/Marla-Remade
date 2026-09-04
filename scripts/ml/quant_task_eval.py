#!/usr/bin/env python3
"""What a quantisation costs each model *at its output*, rather than at its weights.

`maml_convert.Fidelity` gates on `cosine(fp32 weight, int8 * scale)` per tensor. That is a
screen, and this session established how weak a one: Maia's int4 passed a 0.99 bar at group 16
and still changed one move in eleven. A cosine says the weights were reproduced; it says
nothing about whether the model still does its job, and the relationship between the two is
steeply nonlinear and different for every architecture.

So this runs the real graph twice on the same inputs — once as exported, once with every
eligible convolution requantised — and reports the divergence in units the app cares about:

* **embedding** (`mobilefacenet`): cosine between the two embeddings. This is the number a face
  match thresholds on, so a displacement of 0.01 is directly comparable to the ~0.3 threshold
  ArcFace-style recognition uses.
* **mask** (`u2netp`, `selfie`): IoU of the thresholded masks, plus the worst per-pixel delta.
  A saliency mask that moves a few pixels at the edge is invisible; one that loses a region is
  not, and IoU is what tells them apart.
* **dense** (everything else): correlation and worst deviation relative to the output range,
  which is what `onnx_parity.py` reports.

Weights are requantised in place with `maml_convert.quantise_per_channel` (or the grouped
variant), which is the arithmetic the shaders perform, so this measures the shipped scheme and
not an idealisation of it.

    python scripts/ml/quant_task_eval.py --onnx build/onnx/u2netp.onnx --graph u2netp --bits 8
    python scripts/ml/quant_task_eval.py --onnx build/onnx/mobilefacenet.onnx \\
        --graph mobilefacenet --bits 4 --group 32
"""

import argparse
import glob
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import maml_convert  # noqa: E402

# How to judge each graph's output. See the module docstring.
METRICS = {
    "mobilefacenet": "embedding",
    "u2netp": "mask",
    "selfie": "mask",
    "scrfd": "dense",
    "ppocr_det": "mask",
    "ppocr_rec": "dense",
}

# The input each graph wants, as `[channels, height, width]`.
SHAPES = {
    "mobilefacenet": (3, 112, 112),
    "u2netp": (3, 320, 320),
    "selfie": (3, 256, 256),
    "scrfd": (3, 640, 640),
    "ppocr_det": (3, 640, 640),
    "ppocr_rec": (3, 48, 320),
}

# `(mean, std, bgr)` per graph, transcribed from `library/ml/.../preprocess.rs`. Feeding a
# model the wrong normalisation puts it off its training distribution, which shows up as
# quantisation sensitivity that is really just a preprocessing bug.
NORMALISE = {
    "mobilefacenet": ((0.5, 0.5, 0.5), (0.5, 0.5, 0.5), False),
    "u2netp": ((0.485, 0.456, 0.406), (0.229, 0.224, 0.225), False),
    "selfie": ((0.0, 0.0, 0.0), (1.0, 1.0, 1.0), False),
    "scrfd": ((0.5, 0.5, 0.5), (128 / 255, 128 / 255, 128 / 255), False),
    "ppocr_det": ((0.485, 0.456, 0.406), (0.229, 0.224, 0.225), True),
    "ppocr_rec": ((0.5, 0.5, 0.5), (0.5, 0.5, 0.5), True),
}

# Real photographs already in the tree, from `:camera`'s Rust tests. Natural images matter
# here: on random noise a saliency mask is empty and a detector fires nowhere, which makes IoU
# and box metrics degenerate rather than merely noisy. See the `--images` flag.
PHOTOS = "camera/src/main/rust/testdata"


def photo_feeds(graph, count):
    """`count` real photographs, resized and normalised the way the app feeds this graph."""
    from PIL import Image

    channels, height, width = SHAPES[graph]
    mean, std, bgr = NORMALISE[graph]
    found = sorted(glob.glob(os.path.join(ROOT, PHOTOS, "*.jpg")))
    if not found:
        raise SystemExit(f"no photographs in {PHOTOS}")
    feeds = []
    for path in found[:count]:
        image = Image.open(path).convert("RGB").resize((width, height), Image.BILINEAR)
        pixels = np.asarray(image, dtype=np.float32) / 255.0
        if bgr:
            pixels = pixels[:, :, ::-1]
        pixels = (pixels - np.array(mean, np.float32)) / np.array(std, np.float32)
        feeds.append(
            np.ascontiguousarray(pixels.transpose(2, 0, 1)[None], dtype=np.float32)
        )
    return feeds


def requantise(weight, bits, group):
    """`weight` through a round trip of the scheme the shaders read."""
    if bits == 8 and not group:
        kernel, scale = maml_convert.quantise_per_channel(weight)
        return (kernel.astype(np.float32).reshape(weight.shape[0], -1) * scale[:, None]).reshape(
            weight.shape
        )
    limit = float(2 ** (bits - 1) - 1)
    rows = np.ascontiguousarray(weight, np.float32).reshape(weight.shape[0], -1)
    width = rows.shape[1]
    span = width if not group else min(group, width)
    out = np.empty_like(rows)
    for start in range(0, width, span):
        block = rows[:, start:start + span]
        absmax = np.abs(block).max(axis=1)
        scale = np.where(absmax > 0, absmax / limit, 1.0).astype(np.float32)
        codes = np.clip(
            np.floor(np.abs(block) / scale[:, None] + 0.5) * np.sign(block), -limit, limit
        )
        out[:, start:start + span] = codes * scale.astype(np.float16).astype(np.float32)[:, None]
    return out.reshape(weight.shape)


def quantised_model(path, bits, group):
    """A copy of the ONNX at `path` with every eligible convolution requantised."""
    import onnx
    from onnx import numpy_helper

    model = onnx.load(path)
    inits = {i.name: i for i in model.graph.initializer}
    consumers = {}
    for node in model.graph.node:
        for name in node.input:
            consumers.setdefault(name, []).append(node)

    touched = 0
    worst = 1.0
    for node in model.graph.node:
        if not maml_convert.int8_eligible(node, consumers):
            continue
        name = node.input[1]
        if name not in inits:
            continue
        weight = numpy_helper.to_array(inits[name]).astype(np.float32)
        if weight.ndim < 2:
            continue
        low = requantise(weight, bits, group)
        flat, back = weight.ravel(), low.ravel()
        cosine = float(
            np.dot(flat, back) / max(np.linalg.norm(flat) * np.linalg.norm(back), 1e-30)
        )
        worst = min(worst, cosine)
        inits[name].CopyFrom(numpy_helper.from_array(low, name))
        touched += 1
    return model, touched, worst


def run(model, feeds):
    import onnxruntime as ort

    session = ort.InferenceSession(
        model.SerializeToString(), providers=["CPUExecutionProvider"]
    )
    name = session.get_inputs()[0].name
    return [session.run(None, {name: f})[0] for f in feeds]


def report(kind, reference, low):
    """The divergence between two runs, in the units `kind` calls for."""
    if kind == "embedding":
        worst = 1.0
        for a, b in zip(reference, low):
            a, b = a.ravel(), b.ravel()
            worst = min(
                worst,
                float(np.dot(a, b) / max(np.linalg.norm(a) * np.linalg.norm(b), 1e-30)),
            )
        print(f"  embedding cosine against fp32: worst {worst:.6f}")
        print(
            "  a face match thresholds near 0.3, so displacement here is the whole budget: "
            f"{1 - worst:.6f} used"
        )
        return worst
    if kind == "mask":
        worst_iou = 1.0
        worst_delta = 0.0
        degenerate = 0
        for a, b in zip(reference, low):
            pa, pb = a.ravel() > 0.5, b.ravel() > 0.5
            # A mask that is essentially all-background or all-foreground makes IoU a coin
            # flip: an empty reference against a handful of stray positives scores 0, and two
            # near-empty masks score 1, neither of which says anything about the model. Random
            # inputs produce exactly that, so it is counted and reported rather than averaged in.
            coverage = pa.mean()
            if coverage < 0.001 or coverage > 0.999:
                degenerate += 1
                continue
            union = np.logical_or(pa, pb).sum()
            iou = 1.0 if union == 0 else float(np.logical_and(pa, pb).sum() / union)
            worst_iou = min(worst_iou, iou)
            worst_delta = max(worst_delta, float(np.abs(a - b).max()))
        if degenerate:
            # IoU is unusable, but the raw saliency map still is: correlation and mean error
            # over the probabilities say how far the map moved without depending on a
            # threshold that happens to sit in an empty region.
            flat_a = np.concatenate([a.ravel() for a in reference]).astype(np.float64)
            flat_b = np.concatenate([b.ravel() for b in low]).astype(np.float64)
            correlation = float(np.corrcoef(flat_a, flat_b)[0, 1])
            mae = float(np.abs(flat_a - flat_b).mean())
            print(
                f"  mask IoU: unusable — {degenerate}/{len(reference)} reference masks are "
                "degenerate (no clear subject in these photos)"
            )
            print(
                f"  saliency map instead: correlation {correlation:.6f}   "
                f"mean abs error {mae:.5f}   worst pixel "
                f"{max(float(np.abs(a - b).max()) for a, b in zip(reference, low)):.4f}"
            )
            return correlation
        print(f"  mask IoU at 0.5: worst {worst_iou:.6f}   worst pixel delta {worst_delta:.4f}")
        return worst_iou
    worst_corr = 1.0
    worst_rel = 0.0
    for a, b in zip(reference, low):
        a, b = a.ravel().astype(np.float64), b.ravel().astype(np.float64)
        spread = a.max() - a.min()
        worst_corr = min(worst_corr, float(np.corrcoef(a, b)[0, 1]))
        worst_rel = max(worst_rel, float(np.abs(a - b).max() / max(spread, 1e-9)))
    print(f"  output correlation: worst {worst_corr:.6f}   worst deviation {worst_rel * 100:.3f}%")
    return worst_corr


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--onnx", required=True)
    parser.add_argument("--graph", required=True, choices=sorted(SHAPES))
    parser.add_argument("--bits", type=int, default=8, choices=[4, 5, 6, 8])
    parser.add_argument("--group", type=int, default=0)
    parser.add_argument("--inputs", type=int, default=8)
    parser.add_argument("--seed", type=int, default=5)
    parser.add_argument(
        "--noise",
        action="store_true",
        help="use random inputs instead of the real photographs in camera's testdata",
    )
    args = parser.parse_args()

    try:
        import onnx  # noqa: F401
        import onnxruntime  # noqa: F401
    except ImportError as missing:
        raise SystemExit(f"needs onnx and onnxruntime: {missing}")

    if args.noise:
        # Random inputs. Sound for a *relative* comparison of two weightings of the same graph
        # — both sides see identical bytes — but a saliency mask or a detection map is
        # degenerate on noise, so the mask metric will refuse to report. Kept for the dense
        # graphs and for reproducing the earlier measurements.
        rng = np.random.default_rng(args.seed)
        channels, height, width = SHAPES[args.graph]
        feeds = [
            rng.standard_normal((1, channels, height, width)).astype(np.float32)
            for _ in range(args.inputs)
        ]
        source = "random noise"
    else:
        feeds = photo_feeds(args.graph, args.inputs)
        source = f"{len(feeds)} real photographs from {PHOTOS}"

    import onnx

    reference_model = onnx.load(args.onnx)
    low_model, touched, worst_weight = quantised_model(args.onnx, args.bits, args.group)
    grouping = "per output channel" if not args.group else f"per {args.group} weights"
    print(
        f"{args.graph}: int{args.bits}, {grouping}, {touched} convolutions requantised, "
        f"worst weight cosine {worst_weight:.6f}"
    )
    print(f"  input: {source}")

    reference = run(reference_model, feeds)
    low = run(low_model, feeds)
    report(METRICS[args.graph], reference, low)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
