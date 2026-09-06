"""Extract Google's music-detection gate and generate its Rust test fixtures.

The gate is `music_detector.sound_model` subgraph 3: the always-on, 8,200-parameter
classifier that answers "is this music?" once per 10 ms hop. It is a different network
from `sound_model_2`, the 172,576-parameter fingerprinter that `nnfp_reference.py`
covers, and the two are not interchangeable.

Two jobs, both driven off the same parse so they cannot disagree:

1. Write `library/ml/src/main/rust/src/gate.bin` - the ordered weight blob that
   `gate.rs` embeds. Every quantization scale in `gate.rs` is asserted against the
   flatbuffer here, so a wrong constant fails this script rather than silently
   producing wrong scores.

2. Print a Rust `#[cfg(test)]` fixture module to stdout. The reference forward pass
   below is written from `detector/ARCHITECTURE.md` section 3 in numpy float64 and
   shares no code with the Rust; pasting its output into `gate.rs` pins the port
   against an independently-written implementation, which is the same arrangement
   `nnfp_reference.py` has with `microfrontend.rs`.

Usage:

    python scripts/ml/gate_reference.py <path to music_detector.sound_model>

Add `--no-write` to print the fixtures without touching `gate.bin`.
"""

import pathlib
import sys

import numpy as np
from tflite.Model import Model

GATE_SUBGRAPH = 3

# Quantization, from ARCHITECTURE.md section 3.3. Asserted against the model below, so
# this table is a check on the flatbuffer and on `gate.rs`, not a second source of truth.
INPUT_SCALE = 0.000625  # i16 log-mel, == 1/1600, FilterBankLog's output_scale
FEATURE_SCALE = 0.05882353  # i8 after QUANTIZE
FEATURE_ZERO = -128
LOGIT_SCALE = 0.078431375  # i8 FC2 output
LOGIT_ZERO = -1
SCORE_SCALE = 0.0006103702  # i16 handed to the latching classifier
SCORE_ZERO = 16383

# Per-block (depthwise out, pointwise out) activation scales and zero points, in op order.
BLOCK_QUANT = [
    ((0.09264124, 0), (0.041850865, 0)),
    ((0.0057760403, 0), (0.031403266, -1)),
    ((0.00901962, 0), (0.029606337, 0)),
    ((0.0073377984, 0), (0.024098465, 0)),
    ((0.011312435, 0), (0.025132196, 0)),
    ((0.010261553, 0), (0.020659184, 0)),
]
FC1_QUANT = (0.026163183, -1)

CHANNELS = 32
TAPS = 4
BLOCKS = 6
HEAD_FRAMES = 5  # the last circular buffer is 5 deep, not 4
HIDDEN = 8


def load(path):
    """Return the gate subgraph and the model it belongs to."""
    raw = pathlib.Path(path).read_bytes()
    offset = 0 if raw[4:8] == b"TFL3" else raw.find(b"TFL3") - 4
    model = Model.GetRootAsModel(raw[offset:], 0)
    if model.SubgraphsLength() <= GATE_SUBGRAPH:
        raise SystemExit(f"{path} has {model.SubgraphsLength()} subgraphs, not a sound_model")
    return model, model.Subgraphs(GATE_SUBGRAPH)


def constant(model, graph, index, dtype):
    tensor = graph.Tensors(index)
    data = model.Buffers(tensor.Buffer()).DataAsNumpy()
    if isinstance(data, int) or data.size == 0:
        raise SystemExit(f"t{index} is not a constant")
    return np.frombuffer(data.tobytes(), dtype=dtype).astype(np.int64)


def quant(graph, index):
    q = graph.Tensors(index).Quantization()
    scale = float(q.ScaleAsNumpy()[0])
    zero = int(q.ZeroPointAsNumpy()[0]) if q.ZeroPointLength() else 0
    return scale, zero


def close(got, want, what):
    """ARCHITECTURE.md prints scales to ~8 digits; the model stores full float32."""
    if abs(got - want) > 1e-6 * max(1.0, abs(want)):
        raise SystemExit(f"{what}: model says {got!r}, the table says {want!r}")


def parse(model, graph):
    """Pull the twelve convolutions and two dense layers out in execution order.

    Read structurally - operand 1 of each op is its weights and operand 2 its bias -
    rather than by the tensor indices in ARCHITECTURE.md, so a re-export that renumbers
    tensors still works and a change of topology fails loudly.
    """
    codes = []
    for i in range(model.OperatorCodesLength()):
        code = model.OperatorCodes(i)
        custom = code.CustomCode()
        codes.append(custom.decode() if custom else code.BuiltinCode())

    layers = []
    for i in range(graph.OperatorsLength()):
        op = graph.Operators(i)
        kind = codes[op.OpcodeIndex()]
        if kind not in (3, 4, 9):  # CONV_2D, DEPTHWISE_CONV_2D, FULLY_CONNECTED
            continue
        weights, bias = op.Inputs(1), op.Inputs(2)
        layers.append((kind, weights, bias, op.Outputs(0)))

    expected = [4, 3] * BLOCKS + [9, 9]
    if [k for k, _, _, _ in layers] != expected:
        raise SystemExit(f"unexpected op sequence {[k for k, _, _, _ in layers]}")
    return layers


def extract(model, graph, layers):
    """Weights and biases in blob order, with every scale checked."""
    close(quant(graph, graph.Inputs(0))[0], INPUT_SCALE, "input scale")
    tensors = []

    for block in range(BLOCKS):
        depthwise, pointwise = layers[block * 2], layers[block * 2 + 1]
        (dw_scale, dw_zero), (pw_scale, pw_zero) = BLOCK_QUANT[block]
        close(quant(graph, depthwise[3])[0], dw_scale, f"block {block} depthwise scale")
        close(quant(graph, depthwise[3])[1], dw_zero, f"block {block} depthwise zero point")
        close(quant(graph, pointwise[3])[0], pw_scale, f"block {block} pointwise scale")
        close(quant(graph, pointwise[3])[1], pw_zero, f"block {block} pointwise zero point")
        for kind, weights, bias, _ in (depthwise, pointwise):
            shape = [
                graph.Tensors(weights).Shape(j)
                for j in range(graph.Tensors(weights).ShapeLength())
            ]
            want = [1, TAPS, 1, CHANNELS] if kind == 4 else [CHANNELS, 1, 1, CHANNELS]
            if shape != want:
                raise SystemExit(f"block {block} kernel is {shape}, not {want}")
            tensors.append(("i8", constant(model, graph, weights, np.int8)))
            tensors.append(("i32", constant(model, graph, bias, np.int32)))

    fc1, fc2 = layers[-2], layers[-1]
    close(quant(graph, fc1[3])[0], FC1_QUANT[0], "fc1 scale")
    close(quant(graph, fc1[3])[1], FC1_QUANT[1], "fc1 zero point")
    close(quant(graph, fc2[3])[0], LOGIT_SCALE, "logit scale")
    close(quant(graph, fc2[3])[1], LOGIT_ZERO, "logit zero point")
    for _, weights, bias, _ in (fc1, fc2):
        tensors.append(("i8", constant(model, graph, weights, np.int8)))
        tensors.append(("i32", constant(model, graph, bias, np.int32)))

    return tensors


def weight_scales(graph, layers):
    return [quant(graph, weights)[0] for _, weights, _, _ in layers]


def blob(tensors):
    out = bytearray()
    for dtype, values in tensors:
        if dtype == "i8":
            out += values.astype(np.int8).tobytes()
        else:
            out += values.astype(np.int32).tobytes()
    return bytes(out)


# --- reference forward pass -------------------------------------------------------
# float64 throughout, written from ARCHITECTURE.md section 3.1. The accumulators are
# exact integers; only the requantization multiply is floating point, which is what
# TFLite's own reference kernels do.


def requantize(acc, multiplier, zero, relu):
    """Integer accumulator to int8, TFLite's affine requantization.

    Rounds half away from zero, which is what TFLite's `MultiplyByQuantizedMultiplier`
    does and what Rust's `f64::round` does, so the two sides agree on ties.
    """
    x = np.asarray(acc, dtype=np.float64) * multiplier
    q = np.sign(x) * np.floor(np.abs(x) + 0.5) + zero
    return np.clip(q, zero if relu else -128, 127).astype(np.int64)


class Reference:
    def __init__(self, tensors, scales, activations):
        self.scales = scales
        self.activations = activations
        self.w = []
        for i in range(BLOCKS):
            dw = tensors[i * 4 + 0][1].reshape(TAPS, CHANNELS)
            dwb = tensors[i * 4 + 1][1]
            pw = tensors[i * 4 + 2][1].reshape(CHANNELS, CHANNELS)
            pwb = tensors[i * 4 + 3][1]
            self.w.append((dw, dwb, pw, pwb))
        self.fc1 = tensors[24][1].reshape(HIDDEN, HEAD_FRAMES * CHANNELS)
        self.fc1b = tensors[25][1]
        self.fc2 = tensors[26][1].reshape(1, HIDDEN)
        self.fc2b = tensors[27][1]
        self.reset()

    def reset(self):
        # TFLM memsets a CIRCULAR_BUFFER's variable tensor to raw zero, not to the
        # tensor's zero point, so a cold buffer decodes to `-zp * scale` rather than to
        # silence. ARCHITECTURE.md section 3.2 records this as verified. It only shows
        # up during the 23-frame warm-up, which `Gate` refuses to report.
        self.buffers = [np.zeros((TAPS, CHANNELS), dtype=np.int64) for _ in range(BLOCKS)]
        self.buffers.append(np.zeros((HEAD_FRAMES, CHANNELS), dtype=np.int64))

    @staticmethod
    def push(buffer, frame):
        buffer[:-1] = buffer[1:]
        buffer[-1] = frame
        return buffer

    def score(self, logmel16):
        """One i16 log-mel frame in, one i16 latching-classifier score out."""
        feature = np.asarray(logmel16, dtype=np.float64) * INPUT_SCALE / FEATURE_SCALE
        feature = np.sign(feature) * np.floor(np.abs(feature) + 0.5) + FEATURE_ZERO
        feature = np.clip(feature, -128, 127).astype(np.int64)
        self.push(self.buffers[0], feature)

        in_scale, in_zero = FEATURE_SCALE, FEATURE_ZERO
        for block in range(BLOCKS):
            dw, dwb, pw, pwb = self.w[block]
            (dw_scale, dw_zero) = self.activations[block * 2]
            (pw_scale, pw_zero) = self.activations[block * 2 + 1]
            buffer = self.buffers[block]
            acc = ((buffer - in_zero) * dw).sum(axis=0) + dwb
            multiplier = in_scale * self.scales[block * 2] / dw_scale
            mid = requantize(acc, multiplier, dw_zero, relu=False)

            acc = (pw * (mid - dw_zero)).sum(axis=1) + pwb
            multiplier = dw_scale * self.scales[block * 2 + 1] / pw_scale
            out = requantize(acc, multiplier, pw_zero, relu=True)

            self.push(self.buffers[block + 1], out)
            in_scale, in_zero = pw_scale, pw_zero

        fc1_scale, fc1_zero = self.activations[12]
        logit_scale, logit_zero = self.activations[13]
        flat = self.buffers[-1].reshape(-1)
        acc = (self.fc1 * (flat - in_zero)).sum(axis=1) + self.fc1b
        multiplier = in_scale * self.scales[12] / fc1_scale
        hidden = requantize(acc, multiplier, fc1_zero, relu=True)

        acc = (self.fc2 * (hidden - fc1_zero)).sum(axis=1) + self.fc2b
        multiplier = fc1_scale * self.scales[13] / logit_scale
        logit8 = requantize(acc, multiplier, logit_zero, relu=False)

        real = (logit8[0] - logit_zero) * logit_scale
        q = real / SCORE_SCALE
        q = int(np.sign(q) * np.floor(abs(q) + 0.5)) + SCORE_ZERO
        return int(np.clip(q, -32768, 32767))


def synthetic(frames):
    """Deterministic i16 log-mel frames, integer-only so both sides agree bit for bit.

    Not real audio - a mel ridge that sweeps upward, plus LCG hiss, in `ln * 1600` units.
    `gate.rs`'s test module reimplements this exactly, so only the resulting scores need
    to be carried across as a fixture. Generating features rather than PCM keeps this
    independent of the front end, which `microfrontend.rs` already pins separately.
    """
    state = 0x2545F491
    out = []
    for t in range(frames):
        row = []
        for c in range(CHANNELS):
            state = (state * 1103515245 + 12345) & 0x7FFFFFFF
            peak = (t * 3 + 7) % CHANNELS
            ridge = max(0, 12 - 3 * abs(c - peak))
            row.append(int((ridge * 1600) + (state >> 20) % 1600))
        out.append(row)
    return out


def rust_fixture(reference, frames):
    reference.reset()
    scores = [reference.score(frame) for frame in frames]

    lines = []
    for i in range(0, len(scores), 12):
        lines.append("        " + " ".join(f"{v}," for v in scores[i : i + 12]))
    body = "\n".join(lines)
    return f"""    /// The i16 score `synthetic()`'s first {len(scores)} frames produce, from the float64
    /// reference in `scripts/ml/gate_reference.py`. Bit-exact rather than approximate:
    /// the accumulators are integers and both sides round halves away from zero.
    #[rustfmt::skip]
    const PROBE_SCORES: [i16; {len(scores)}] = [
{body}
    ];
"""


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        raise SystemExit(__doc__)
    model, graph = load(args[0])
    layers = parse(model, graph)
    tensors = extract(model, graph, layers)
    scales = weight_scales(graph, layers)

    payload = blob(tensors)
    i8 = sum(v.size for d, v in tensors if d == "i8")
    i32 = sum(v.size for d, v in tensors if d == "i32")
    if (i8, i32) != (8200, 393):
        raise SystemExit(f"extracted {i8} weights and {i32} biases, expected 8200 and 393")

    if "--no-write" not in sys.argv:
        out = (
            pathlib.Path(__file__).resolve().parents[2]
            / "library/ml/src/main/rust/src/gate.bin"
        )
        out.write_bytes(payload)
        print(f"// wrote {out} ({len(payload)} bytes)", file=sys.stderr)

    activations = [quant(graph, out) for _, _, _, out in layers]
    print("// Generated by scripts/ml/gate_reference.py. Do not edit by hand.")
    print("    /// Weight scales, in blob order: dw/pw for each of the six blocks, then FC1, FC2.")
    print("    const WEIGHT_SCALES: [f32; 14] = [")
    print("        " + ", ".join(f"{s!r}" for s in scales))
    print("    ];")
    print()
    print("    /// Output scale and zero point of each of those fourteen layers.")
    print("    const OUTPUT_QUANT: [(f32, i32); 14] = [")
    for scale, zero in activations:
        print(f"        ({scale!r}, {zero}),")
    print("    ];")
    print()
    print(rust_fixture(Reference(tensors, scales, activations), synthetic(40)))


if __name__ == "__main__":
    main()
