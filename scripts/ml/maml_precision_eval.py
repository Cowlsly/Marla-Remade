#!/usr/bin/env python3
"""What a lower precision does to whisper's and Supertonic's *outputs*, on the shipped weights.

`quant_task_eval.py` answers this for the ONNX-sourced graphs by running onnxruntime twice.
Whisper and Supertonic have no usable ONNX in the tree — whisper is a checkpoint and Supertonic
is heavily folded before conversion — so this takes the other route: rewrite the shipped
`.maml` with requantised weights and run **the Rust reference interpreter** over both.

That is a stronger measurement than the onnxruntime one in two ways: it is the exact forward
pass the device runs, and it is the exact weights that ship. It is slower, which is why the
other script exists.

# How a lower precision is simulated without a new dtype

There is no int4 or int6 in the `.maml` format and this does not add one. A tensor is
dequantised, rounded to the target width's grid, and then re-encoded in **its original dtype** —
an int8 slot stays int8, an fp16 slot stays fp16. The values carry the coarser grid; the
container does not change. So the layout, the tensor count and every offset are untouched, and
the Rust net loads it without knowing anything happened.

Re-encoding int4 values into an int8 slot is near-lossless: 15 levels inside the same absmax
are all representable in 255, so what is measured is the int4 rounding and not the round trip.

    python scripts/ml/maml_precision_eval.py --graph whisper --bits 6
"""

import argparse
import json
import os
import struct
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import maml_convert  # noqa: E402
import maml_survey  # noqa: E402

# The graphs `reference.rs::dump_reference_output` can drive, with the asset each reads and the
# input it expects. `PARITY_WIDTH` means different things per graph; these are the values
# `onnx_parity.py` uses.
GRAPHS = {
    "whisper": {
        "asset": "speech/src/main/assets/whisper-base/whisper_base.maml",
        "shape": (1, 80, 3000),
        "width": 3000,
        "what": "encoder hidden states over a 30s window",
    },
    "supertonic_voc": {
        "asset": "speech/src/main/assets/supertonic/supertonic_voc.maml",
        "shape": (144, 64),
        "width": 64,
        "what": "waveform samples",
    },
}


def read_maml(path):
    """`(header, entries, data)` for a `.maml`, with entries as mutable lists."""
    with open(path, "rb") as handle:
        blob = handle.read()
    if blob[:4] != b"MAML":
        raise SystemExit(f"{path} is not a .maml")
    count = struct.unpack("<I", blob[12:16])[0]
    at = maml_survey.HEADER_BYTES
    entries = []
    for _ in range(count):
        rank = struct.unpack("<I", blob[at:at + 4])[0]
        dims = list(struct.unpack("<4I", blob[at + 4:at + 20]))[:rank]
        dtype, offset, numel = struct.unpack("<III", blob[at + 20:at + 32])
        entries.append({"dims": dims, "dtype": dtype, "offset": offset, "numel": numel})
        at += maml_survey.ENTRY_BYTES
    return blob[:at], entries, bytearray(blob[at:])


def requantised_copy(path, out_path, bits):
    """Write `path` to `out_path` with every eligible weight rounded to `bits`, dtype preserved."""
    header, entries, data = read_maml(path)

    def load(entry):
        if entry["dtype"] == maml_survey.DTYPE_I8:
            raw = data[entry["offset"]:entry["offset"] + entry["numel"]]
            return np.frombuffer(bytes(raw), dtype=np.int8).astype(np.float32)
        raw = data[entry["offset"]:entry["offset"] + entry["numel"] * 2]
        return np.frombuffer(bytes(raw), dtype=np.float16).astype(np.float32)

    limit = float(2 ** (bits - 1) - 1)
    touched = 0
    worst = 1.0
    for index, entry in enumerate(entries):
        dims = entry["dims"]
        if not maml_survey.quantisable(dims, entry["numel"]):
            continue
        values = load(entry)
        if entry["dtype"] == maml_survey.DTYPE_I8:
            following = entries[index + 1]
            if following["numel"] != dims[0]:
                raise SystemExit(f"tensor {index} is int8 but is not followed by its scale")
            scale = load(following)
            values = (values.reshape(dims[0], -1) * scale[:, None]).ravel()
        rows = values.reshape(dims[0], -1)

        absmax = np.abs(rows).max(axis=1)
        grid = np.where(absmax > 0, absmax / limit, 1.0).astype(np.float32)
        codes = np.clip(
            np.floor(np.abs(rows) / grid[:, None] + 0.5) * np.sign(rows), -limit, limit
        )
        rounded = codes * grid.astype(np.float16).astype(np.float32)[:, None]
        cosine = float(
            np.dot(rows.ravel(), rounded.ravel())
            / max(np.linalg.norm(rows) * np.linalg.norm(rounded), 1e-30)
        )
        worst = min(worst, cosine)
        touched += 1

        # Back into the slot it came from, in the dtype it came in.
        if entry["dtype"] == maml_survey.DTYPE_I8:
            kernel, scale = maml_convert.quantise_per_channel(rounded.reshape(dims))
            data[entry["offset"]:entry["offset"] + entry["numel"]] = kernel.astype(
                np.int8
            ).tobytes()
            following = entries[index + 1]
            data[following["offset"]:following["offset"] + following["numel"] * 2] = (
                scale.astype(np.float16).tobytes()
            )
        else:
            data[entry["offset"]:entry["offset"] + entry["numel"] * 2] = (
                rounded.astype(np.float16).tobytes()
            )

    with open(out_path, "wb") as handle:
        handle.write(header)
        handle.write(data)
    return touched, worst


def build_reference_binary():
    crate = os.path.join(ROOT, "library", "ml", "src", "main", "rust")
    finished = subprocess.run(
        [
            "cargo", "test", "--release", "-p", "modelrunner", "--lib",
            "--no-run", "--message-format=json",
        ],
        check=True, cwd=crate, capture_output=True, text=True,
    )
    for line in finished.stdout.splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if (
            message.get("reason") == "compiler-artifact"
            and message.get("executable")
            and message.get("target", {}).get("name") == "modelrunner"
        ):
            return message["executable"]
    raise SystemExit("cargo did not report a test binary")


def run_reference(binary, work, graph, maml, width):
    written = os.path.join(work, "reference.f32")
    if os.path.exists(written):
        os.remove(written)
    subprocess.run(
        [binary, "dump_reference_output", "--ignored", "--nocapture"],
        check=True,
        env=dict(
            os.environ,
            PARITY_DIR=work,
            PARITY_GRAPH=graph,
            PARITY_WIDTH=str(width),
            PARITY_MAML=os.path.abspath(maml),
        ),
    )
    if not os.path.exists(written):
        raise SystemExit("the reference wrote nothing; the test filter matched nothing")
    return np.fromfile(written, dtype=np.float32)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--graph", required=True, choices=sorted(GRAPHS))
    parser.add_argument("--bits", type=int, required=True, choices=[4, 5, 6])
    parser.add_argument("--work", default=os.path.join(ROOT, "build", "precision"))
    args = parser.parse_args()

    spec = GRAPHS[args.graph]
    asset = os.path.join(ROOT, spec["asset"])
    work = os.path.abspath(args.work)
    os.makedirs(work, exist_ok=True)

    # A deterministic ramp, as `onnx_parity.py` uses: what matters is that both runs see
    # identical bytes, not that the input is realistic.
    total = int(np.prod(spec["shape"]))
    feed = (np.arange(total, dtype=np.float32) % 255.0) / 255.0 - 0.5
    feed.tofile(os.path.join(work, "input.f32"))

    low_path = os.path.join(work, f"{args.graph}_int{args.bits}.maml")
    touched, worst = requantised_copy(asset, low_path, args.bits)
    print(
        f"{args.graph}: int{args.bits}, {touched} tensors rounded, "
        f"worst weight cosine {worst:.6f}"
    )

    binary = build_reference_binary()
    reference = run_reference(binary, work, args.graph, asset, spec["width"])
    low = run_reference(binary, work, args.graph, low_path, spec["width"])
    if reference.size != low.size:
        raise SystemExit(f"{reference.size} against {low.size} values")

    spread = float(reference.max() - reference.min())
    correlation = float(np.corrcoef(reference, low)[0, 1])
    deviation = float(np.abs(reference - low).max())
    rms = float(np.sqrt(np.mean((reference - low) ** 2)))
    signal = float(np.sqrt(np.mean(reference**2)))
    snr = 20.0 * np.log10(signal / rms) if rms > 0 else float("inf")
    print(f"  {spec['what']}: {reference.size} values")
    print(f"  correlation {correlation:.6f}")
    print(f"  worst deviation {deviation:.5f} ({100 * deviation / spread:.2f}% of range)")
    print(f"  error SNR {snr:.1f} dB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
