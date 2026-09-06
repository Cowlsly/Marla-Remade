#!/usr/bin/env python3
"""Golden for `library/ml/src/main/rust/src/logmel.rs`: what the real Gemma 4 extractor says.

Drives `Gemma4AudioFeatureExtractor._extract_spectrogram` — the shipped one, from
site-packages, not a reimplementation — over three reproducible signals and prints a Rust test
module. The constants are pasted into `logmel.rs` rather than read at test time so that
`cargo test` needs no fixture file, which is the arrangement `scripts/ml/nnfp_reference.py`
already uses for `microfrontend.rs`.

    python scripts/ml/gemma4_audio_parity.py

# The signals are generated on both sides, not carried

Same idea as `gemma4_vision_parity.py`: `tone` and `noise_burst` below are duplicated verbatim
in `logmel.rs`'s tests, so the golden holds only the *output*. Both are quantised to i16 before
scaling, so the two sides agree bit for bit rather than to within whatever `sin` does.

The pure tone is the load-bearing case. A 440 Hz tone lands on mel channel 24 and on *linear*
bin 7, and the two are so far apart that any disagreement between the window, the transform and
the filter bank about what a frequency is shows up as a peak in the wrong place rather than as a
small residual. The noise burst is broadband and half silence, so it covers the mel floor and
the frames that straddle the onset. Silence needs no golden: every value is `ln(1e-3)`.

# Importing transformers on this machine

`import transformers` fails a huggingface-hub version check (needs >=1.5.0, found 1.4.1), so
the package `__init__` is bypassed: `transformers` is registered as a bare module object with a
`__path__` and a `__version__`, and the feature extractor's file is loaded straight off disk.
Everything the extractor actually touches — `audio_utils`, `feature_extraction_utils` — then
imports normally. Nothing about the numbers is stubbed, so this is the reference itself.
"""
import importlib.metadata
import importlib.util
import math
import os
import sys
import types

import numpy as np

TRANSFORMERS = os.path.join(
    os.path.expanduser("~"), "AppData", "Roaming", "Python", "Python312",
    "site-packages", "transformers",
)
SAMPLE_RATE = 16000
MELS = 128
HOP = 160
# `__call__`'s own defaults, and the units are the trap: `pad_to_multiple_of` counts SAMPLES,
# not mel frames. Read as frames it gives 3072 and a downstream T of 768 instead of 750.
MAX_SAMPLES = 480_000
PAD_MULTIPLE = 128


def load_extractor():
    """The shipped `Gemma4AudioFeatureExtractor`, with the package `__init__` stepped around."""
    root = os.environ.get("TRANSFORMERS_DIR", TRANSFORMERS)
    if not os.path.isdir(root):
        raise SystemExit(f"no transformers at {root}; set TRANSFORMERS_DIR")
    for name, path in [
        ("transformers", root),
        ("transformers.models", os.path.join(root, "models")),
        ("transformers.models.gemma4", os.path.join(root, "models", "gemma4")),
    ]:
        if name not in sys.modules:
            module = types.ModuleType(name)
            module.__path__ = [path]
            module.__package__ = name
            sys.modules[name] = module
    sys.modules["transformers"].__version__ = importlib.metadata.version("transformers")
    target = os.path.join(root, "models", "gemma4", "feature_extraction_gemma4.py")
    spec = importlib.util.spec_from_file_location(
        "transformers.models.gemma4.feature_extraction_gemma4", target
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module.Gemma4AudioFeatureExtractor()


def tone(count, hz, amplitude):
    """`amplitude * sin(2 pi hz t)` at 16 kHz, quantised to i16 and scaled to `-1..1`.

    `floor(x + 0.5)` rather than `round`, because numpy rounds halves to even and Rust rounds
    them away from zero. Must stay identical to `tone` in `logmel.rs`.
    """
    out = np.zeros(count, dtype=np.float32)
    for i in range(count):
        value = amplitude * math.sin(math.tau * hz * i / SAMPLE_RATE)
        out[i] = max(-32768, min(32767, math.floor(value + 0.5))) / 32768.0
    return out


def noise_burst(count):
    """Full-scale hashed noise over the middle half, silence either side.

    Integer only, so both sides produce identical samples. Must stay identical to `noise_burst`
    in `logmel.rs`.
    """
    mask = 0xFFFFFFFF
    out = np.zeros(count, dtype=np.float32)
    for i in range(count):
        if not count // 4 <= i < 3 * count // 4:
            continue
        h = (i * 73_856_093) & mask
        h ^= h >> 13
        h = (h * 1_274_126_177) & mask
        h ^= h >> 16
        out[i] = ((h & 0xFFFF) - 32768) / 32768.0
    return out


def spectrogram(extractor, waveform):
    """`_extract_spectrogram` on one whole waveform, `[frames, 128]` in f64."""
    mask = np.ones(len(waveform), dtype=np.int64)
    mel, _ = extractor._extract_spectrogram(waveform.astype(np.float32), mask)
    return mel


def rust_floats(name, values, per_line=6):
    body = ""
    for start in range(0, len(values), per_line):
        # Nine significant digits: f32 carries about seven, so the printed value is not what
        # limits the comparison.
        row = ", ".join(f"{v:.9}" for v in values[start : start + per_line])
        body += f"        {row},\n"
    return f"    const {name}: [f32; {len(values)}] = [\n{body}    ];\n"


def mask_rows(extractor, lengths):
    """`(real, padded, total, valid)` per length, from the real `__call__` end to end.

    This is the caller's side of the contract rather than the front end's: `__call__` truncates
    at `MAX_SAMPLES`, zero-pads up to a multiple of `PAD_MULTIPLE` SAMPLES, and returns a mask
    marking which mel rows came entirely from real audio. `logmel.rs` does none of that, so the
    point of measuring it here is to pin the arithmetic the caller needs:

        total = frame_count(padded)   valid = frame_count(min(real, MAX_SAMPLES))

    Note the waveform is passed as a LIST. Handing `__call__` a bare 1-D array trips a squeeze
    on `_extract_spectrogram`'s batch axis in this version and raises for every length but 1.
    """
    rows = []
    for real in lengths:
        out = extractor([np.zeros(real, dtype=np.float32)])
        total = np.asarray(out["input_features"][0]).shape[0]
        valid = int(np.asarray(out["input_features_mask"][0]).sum())
        padded = min(MAX_SAMPLES, -(-real // PAD_MULTIPLE) * PAD_MULTIPLE)
        rows.append((real, padded, total, valid))
    return rows


def probes(name, frames):
    """Which frames to carry, chosen per case so that every one of them measures something.

    The tone is steady, so frame 0 (the only one the left pad reaches into) and the last frame
    are the only ones that differ from the middle. The burst is not: it runs from sample 1250 to
    3750, so frame 0 is silence, frame 8 straddles the onset, 15 is inside it and 24 straddles
    the end. Probing 0, 1, mid and last there would have put three of the four in silence, which
    is a comparison of two floors.
    """
    rows = {"TONE": [0, 1, frames // 2, frames - 1], "NOISE": [0, 8, 15, 24]}[name]
    if max(rows) >= frames:
        raise SystemExit(f"{name}: probe {max(rows)} past the {frames} frames it has")
    return rows


def main():
    extractor = load_extractor()
    if (extractor.frame_length, extractor.hop_length, extractor.fft_length) != (320, 160, 512):
        raise SystemExit("the reference defaults have moved; re-derive the spec")
    print(
        f"# transformers {sys.modules['transformers'].__version__}: frame"
        f" {extractor.frame_length}, hop {extractor.hop_length}, fft {extractor.fft_length},"
        f" filters {extractor.mel_filters.shape}",
        file=sys.stderr,
    )

    # Every count comes from the reference actually running, not from the formula. The
    # 321-vs-320 unfold is exactly the kind of thing that is right in the formula and wrong in
    # the code, or the other way round.
    #
    # 480000 is the reference's own `max_length`, 30 s, and it is here because the whole audio
    # track's shapes rest on it: 480000 samples -> 2999 mel frames -> T = 750 after the two
    # stride-2 convolutions of the subsample projection. It costs a second to run and it is the
    # one length a wrong answer would propagate furthest from.
    lengths = [0, 160, 161, 320, 321, 480, 481, 1000, 4000, 5000, 8000, 16000, 480000]
    counts = [
        spectrogram(extractor, np.zeros(length, dtype=np.float32)).shape[0] for length in lengths
    ]
    print(f"# frames from {lengths}: {counts}", file=sys.stderr)

    mask_table = mask_rows(
        extractor, [1, 160, 161, 500, 1000, 4000, 5000, 20000, 479999, 480000, 480001]
    )
    for real, padded, total, valid in mask_table:
        print(f"# {real} real -> {padded} padded -> {total} rows, {valid} valid", file=sys.stderr)

    cases = [
        ("TONE", tone(8000, 440.0, 8192.0)),
        ("NOISE", noise_burst(5000)),
    ]
    blocks = []
    for name, waveform in cases:
        mel = spectrogram(extractor, waveform)
        rows = probes(name, mel.shape[0])
        print(
            f"# {name}: {len(waveform)} samples -> {mel.shape[0]} frames,"
            f" probing {rows}, range {mel.min():.4f}..{mel.max():.4f}",
            file=sys.stderr,
        )
        blocks.append(f"    const REF_{name}_ROWS: [usize; {len(rows)}] = {rows};")
        blocks.append(rust_floats(f"REF_{name}", [v for r in rows for v in mel[r]]))

    # The argmax of a pure tone's first full frame, which is the one number that says the whole
    # chain agrees about what a frequency is.
    peaks = {}
    for hz in (440.0, 3000.0):
        mel = spectrogram(extractor, tone(8000, hz, 8192.0))
        peaks[hz] = int(np.argmax(mel[10]))
        linear = int(round(hz / 8000.0 * (MELS - 1)))
        print(f"# {hz:.0f} Hz peaks at mel channel {peaks[hz]} (linear would be {linear})",
              file=sys.stderr)

    # The bank on its own, so a disagreement can be pinned on it or ruled out without the FFT.
    sums = extractor.mel_filters.sum(axis=0)
    print(f"# filter columns: {int((sums > 0).sum())} of {MELS} non-empty", file=sys.stderr)

    print("    // Generated by scripts/ml/gemma4_audio_parity.py; see its docstring.")
    print(
        "    const REF_FRAME_LENGTHS: [usize; "
        f"{len(lengths)}] = [\n        {', '.join(str(v) for v in lengths)},\n    ];"
    )
    print(f"    const REF_FRAME_COUNTS: [usize; {len(counts)}] = {counts};")
    body = "".join(f"        ({r}, {p}, {t}, {v}),\n" for r, p, t, v in mask_table)
    print(
        f"    const REF_MASK_ROWS: [(usize, usize, usize, usize); {len(mask_table)}] = [\n{body}    ];"
    )
    print(rust_floats("REF_FILTER_SUMS", [float(v) for v in sums], 6))
    print(f"    const REF_TONE_PEAKS: [(f32, usize); 2] = [(440.0, {peaks[440.0]}),"
          f" (3000.0, {peaks[3000.0]})];")
    for block in blocks:
        print(block)


if __name__ == "__main__":
    main()
