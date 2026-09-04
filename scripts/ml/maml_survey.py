#!/usr/bin/env python3
"""What is already int8 in the shipped `.maml` assets, and what quantising the rest would cost.

A survey, not a converter. It reads each file's tensor table, reports how many bytes sit in
int8 kernels against how many are still fp16, and then simulates per-output-channel int8 on
every fp16 tensor that could take it — so a decision about quantising anything further starts
from the file rather than from a guess about it.

The simulation reads fp16 and quantises *that*, where a real conversion would read the fp32
export. That is very slightly pessimistic and needs no re-fetch of anything.

**These are upper bounds, and two of them were measured wrong.** Two corrections found by
running the real converter against the fp32 ONNX:

* **It ignores what the runtime refuses.** A convolution feeding a `PRelu` cannot be int8 (the
  slope wants the push offset the scale occupies), nor can a `ConvTranspose`, nor a padded
  convolution in an edge-padded graph. This file cannot see any of that — it sees weights.
  For `mobilefacenet`, a PReLU architecture, that is the difference between the 3.2 MiB
  predicted here and the 0.76 MiB actually available: only 15 of its 49 convolutions qualify.
* **It reads fp16, not fp32.** The shipped weights have already been rounded once, so
  quantising them is measurably kinder than quantising the export. `u2netp`'s worst tensor
  reads 0.99929 here and 0.998891 from the fp32 ONNX — which is the wrong side of the gate.

So treat a green row as "worth measuring properly", never as "worth shipping". The honest
number for any graph comes from running `maml_convert.py` with it in `INT8_GRAPHS`.

    python scripts/ml/maml_survey.py
"""

import argparse
import glob
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import maml_convert  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# `weights.rs`: MAGIC, then version/graph/count, a 64-byte header, then 32-byte table entries.
HEADER_BYTES = 64
ENTRY_BYTES = 32
DTYPE_I8 = 1

# Every bundled asset, by the directory each app keeps it in.
PATTERNS = (
    "camera/src/main/assets/*.maml",
    "photos/src/main/assets/*.maml",
    "photos/src/main/assets/clip/*.maml",
    "library/ocr/src/main/assets/*.maml",
    "speech/src/main/assets/supertonic/*.maml",
    "speech/src/main/assets/whisper-base/*.maml",
    "games/chess/src/main/assets/*.maml",
)


def quantisable(dims, numel):
    """Whether a tensor is the kind a converter would quantise.

    Rank 2 or more and an output axis worth having a scale per: convolution kernels and
    embedding tables. Norms, biases and per-channel scales are rank 1 and stay fp16 in every
    graph here — they are a rounding error of the file and a scale has nowhere to live.

    Deliberately **no size floor**. An earlier version of this skipped tensors under 4096
    elements on the grounds that their saving is negligible, and it therefore reported
    `supertonic_ttl` as passing comfortably — when the whole reason that graph is fp16 is one
    small tensor that quantises at 0.99212. The saving is negligible; the veto is not.
    """
    return len(dims) >= 2 and dims[0] > 1


# Below this a tensor is counted in the fidelity check but not in the reported saving, because
# one byte per weight of a 1 KiB tensor is not why anyone would do this work.
WORTH_SAVING = 4096


def survey(path):
    with open(path, "rb") as handle:
        blob = handle.read()
    if blob[:4] != b"MAML":
        raise SystemExit(f"{path} is not a .maml")
    _version, graph, count = struct.unpack("<III", blob[4:16])

    at = HEADER_BYTES
    entries = []
    for _ in range(count):
        rank = struct.unpack("<I", blob[at:at + 4])[0]
        dims = struct.unpack("<4I", blob[at + 4:at + 20])[:rank]
        dtype, offset, numel = struct.unpack("<III", blob[at + 20:at + 32])
        entries.append((dims, dtype, offset, numel))
        at += ENTRY_BYTES
    data = blob[at:]

    int8_tensors = int8_bytes = fp16_tensors = fp16_bytes = 0
    # What an int8 pass over the remaining fp16 would save, and what it would cost in fidelity.
    savable = 0
    worst = (1.0, None)
    failing = []
    largest = []
    for dims, dtype, offset, numel in entries:
        if dtype == DTYPE_I8:
            int8_tensors += 1
            int8_bytes += numel
            largest.append((numel, "int8", dims))
            continue
        fp16_tensors += 1
        fp16_bytes += numel * 2
        largest.append((numel * 2, "fp16", dims))
        if not quantisable(dims, numel):
            continue
        raw = data[offset:offset + numel * 2]
        weight = np.frombuffer(raw, dtype=np.float16).astype(np.float32).reshape(dims)
        kernel, scale = maml_convert.quantise_per_channel(weight)
        back = kernel.astype(np.float32).reshape(dims[0], -1) * scale[:, None]
        flat = weight.reshape(dims[0], -1)
        cosine = float(
            np.dot(flat.ravel(), back.ravel())
            / max(np.linalg.norm(flat) * np.linalg.norm(back), 1e-30)
        )
        if cosine < worst[0]:
            worst = (cosine, list(dims))
        if cosine < maml_convert.MIN_INT8_COSINE:
            failing.append((cosine, list(dims)))
        # One byte instead of two, plus an fp16 scale per output channel.
        if numel >= WORTH_SAVING:
            savable += numel * 2 - (numel + dims[0] * 2)

    largest.sort(reverse=True)
    return {
        "graph": graph,
        "size": len(blob),
        "int8_tensors": int8_tensors,
        "int8_bytes": int8_bytes,
        "fp16_tensors": fp16_tensors,
        "fp16_bytes": fp16_bytes,
        "savable": savable,
        "worst": worst,
        "failing": failing,
        "largest": largest[:3],
    }


def weights_in(path):
    """Every weight tensor in `path` as float, whatever it is stored as.

    An int8 kernel is returned multiplied by the fp16 per-output-channel scale that follows it,
    so a graph that is already quantised can be screened at a lower precision on the same
    footing as one that is not. That reconstruction is what the shaders do, so it is the right
    starting point — it is one quantisation removed from the fp32 export, which makes this
    slightly pessimistic for the already-int8 graphs and is fine for a screen.

    Yields `(dims, values, stored)` and skips the scale tensors it has consumed.
    """
    with open(path, "rb") as handle:
        blob = handle.read()
    if blob[:4] != b"MAML":
        raise SystemExit(f"{path} is not a .maml")
    _version, _graph, count = struct.unpack("<III", blob[4:16])

    at = HEADER_BYTES
    entries = []
    for _ in range(count):
        rank = struct.unpack("<I", blob[at:at + 4])[0]
        dims = struct.unpack("<4I", blob[at + 4:at + 20])[:rank]
        dtype, offset, numel = struct.unpack("<III", blob[at + 20:at + 32])
        entries.append((dims, dtype, offset, numel))
        at += ENTRY_BYTES
    data = blob[at:]

    def read(index):
        dims, dtype, offset, numel = entries[index]
        if dtype == DTYPE_I8:
            return np.frombuffer(data[offset:offset + numel], dtype=np.int8).astype(np.float32)
        return np.frombuffer(data[offset:offset + numel * 2], dtype=np.float16).astype(np.float32)

    skip = set()
    for index, (dims, dtype, _offset, numel) in enumerate(entries):
        if index in skip:
            continue
        values = read(index)
        if dtype == DTYPE_I8:
            # The scale is the tensor after the kernel, one entry per output channel.
            following = entries[index + 1] if index + 1 < len(entries) else None
            if following is None or following[3] != dims[0]:
                raise SystemExit(f"{path}: int8 tensor {index} is not followed by its scale")
            scale = read(index + 1)
            values = (values.reshape(dims[0], -1) * scale[:, None]).ravel()
            skip.add(index + 1)
        yield dims, values.reshape(dims), "int8" if dtype == DTYPE_I8 else "fp16"


def screen(path, bits, group):
    """`(before, after, worst, failures)` for re-quantising everything in `path` at `bits`."""
    before = os.path.getsize(path)
    packed = 0
    other = 0
    worst = (1.0, None)
    failures = 0
    considered = 0
    for dims, values, _stored in weights_in(path):
        if not quantisable(dims, values.size):
            other += values.size * 2
            continue
        rows = values.reshape(dims[0], -1)
        width = rows.shape[1]
        span = width if not group else min(group, width)
        rebuilt = np.empty_like(rows)
        spans = 0
        limit = float(2 ** (bits - 1) - 1)
        for start in range(0, width, span):
            block = rows[:, start:start + span]
            absmax = np.abs(block).max(axis=1)
            scale = np.where(absmax > 0, absmax / limit, 1.0).astype(np.float32)
            codes = np.clip(
                np.floor(np.abs(block) / scale[:, None] + 0.5) * np.sign(block), -limit, limit
            )
            scale = scale.astype(np.float16).astype(np.float32)
            rebuilt[:, start:start + span] = codes * scale[:, None]
            spans += 1
        cosine = float(
            np.dot(rows.ravel(), rebuilt.ravel())
            / max(np.linalg.norm(rows) * np.linalg.norm(rebuilt), 1e-30)
        )
        considered += 1
        if cosine < worst[0]:
            worst = (cosine, list(dims))
        if cosine < maml_convert.MIN_INT8_COSINE:
            failures += 1
        packed += int(np.ceil(values.size * bits / 8.0)) + dims[0] * spans * 2
    return before, packed + other, worst, failures, considered


def run_screen(paths, bits, group):
    grouping = "per output channel" if not group else f"per {group} weights"
    print(f"int{bits}, {grouping}, screened against each file's own weights\n")
    header = f"{'asset':34s} {'now':>7s} {'int%d' % bits:>7s} {'saved':>7s} {'worst cos':>10s} {'fails':>6s}"
    print(header)
    print("-" * len(header))
    total_before = total_after = 0
    for path in paths:
        before, after, worst, failures, considered = screen(path, bits, group)
        total_before += before
        total_after += after
        flag = "" if failures == 0 else f"{failures}/{considered}"
        print(
            f"{os.path.basename(path):34s} {before / (1 << 20):7.1f} {after / (1 << 20):7.1f} "
            f"{(before - after) / (1 << 20):7.1f} {worst[0]:10.5f} {flag:>6s}"
        )
    print()
    print(
        f"total {total_before / (1 << 20):.1f} -> {total_after / (1 << 20):.1f} MiB, "
        f"saving {(total_before - total_after) / (1 << 20):.1f} MiB"
    )
    print(f"'fails' counts tensors under the {maml_convert.MIN_INT8_COSINE} gate.")
    print()
    print(
        "Cosine is a screen, not a verdict. Maia's int4 passed nothing like this bar and still\n"
        "changed 1 move in 9; a graph that looks fine here still needs a task-level check."
    )
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--bits",
        type=int,
        default=0,
        choices=[0, 2, 3, 4, 5, 6],
        help="screen re-quantising everything at this width instead of reporting int8 headroom",
    )
    parser.add_argument(
        "--group",
        type=int,
        default=0,
        help="weights per scale within an output row; 0 means one scale for the whole row",
    )
    args = parser.parse_args()

    paths = []
    for pattern in PATTERNS:
        paths.extend(sorted(glob.glob(os.path.join(ROOT, pattern))))
    if not paths:
        raise SystemExit("no .maml assets found; run the fetch scripts first")

    if args.bits:
        return run_screen(paths, args.bits, args.group)

    header = (
        f"{'asset':34s} {'MiB':>6s} {'after':>6s} {'int8 t':>6s} {'fp16 t':>6s} "
        f"{'fp16 MiB':>9s} {'worst cos':>12s}"
    )
    print(header)
    print("-" * len(header))
    total_fp16 = 0
    total_size = 0
    total_savable = 0
    rows = []
    for path in paths:
        found = survey(path)
        total_fp16 += found["fp16_bytes"]
        total_size += found["size"]
        total_savable += found["savable"]
        rows.append((found["savable"], os.path.basename(path), found))
        cosine, _dims = found["worst"]
        gate = maml_convert.MIN_INT8_COSINE
        if cosine == 1.0:
            verdict = "n/a"
        else:
            verdict = f"{cosine:.5f}" + ("" if cosine >= gate else " FAIL")
        print(
            f"{os.path.basename(path):34s} {found['size'] / (1 << 20):6.1f} "
            f"{(found['size'] - found['savable']) / (1 << 20):6.1f} "
            f"{found['int8_tensors']:6d} {found['fp16_tensors']:6d} "
            f"{found['fp16_bytes'] / (1 << 20):9.1f} {verdict:>12s}"
        )

    print()
    for _savable, name, found in sorted(rows, reverse=True):
        if not found["failing"]:
            continue
        print(f"{name}: {len(found['failing'])} tensor(s) under the gate")
        for cosine, dims in sorted(found["failing"])[:4]:
            print(f"    {cosine:.5f}  {dims}")

    print()
    print(
        f"{total_size / (1 << 20):.1f} MiB bundled, of which {total_fp16 / (1 << 20):.1f} MiB "
        f"is fp16 ({100.0 * total_fp16 / total_size:.0f}%)"
    )
    print(
        f"quantising every eligible fp16 tensor: "
        f"{total_size / (1 << 20):.1f} -> {(total_size - total_savable) / (1 << 20):.1f} MiB, "
        f"saving {total_savable / (1 << 20):.1f} MiB"
    )
    # The aggregate understates the per-model effect, because most of the bundle is already
    # int8: a graph that has not been quantised yet does roughly halve, and a graph that has
    # been cannot move. Reporting both stops the total from reading as "not worth it".
    untouched = [(n, f) for _s, n, f in rows if f["int8_tensors"] == 0]
    if untouched:
        before = sum(f["size"] for _n, f in untouched)
        after = sum(f["size"] - f["savable"] for _n, f in untouched)
        print(
            f"of which the {len(untouched)} graphs with no int8 at all: "
            f"{before / (1 << 20):.1f} -> {after / (1 << 20):.1f} MiB "
            f"({100.0 * (before - after) / before:.0f}% off)"
        )
    print(f"'FAIL' marks a file holding a tensor under the {maml_convert.MIN_INT8_COSINE} gate.")
    print()
    print("where the remaining saving is:")
    rows.sort(reverse=True)
    for savable, name, found in rows[:5]:
        if savable < (1 << 20):
            continue
        print(f"  {name}  ({savable / (1 << 20):.1f} MiB)")
        for size, kind, dims in found["largest"]:
            if kind != "fp16" or size < (1 << 18):
                continue
            print(f"    {size / (1 << 20):7.2f} MiB  {list(dims)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
