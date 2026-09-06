"""Reference values for `library/ml/src/main/rust/src/microfrontend.rs`'s tests.

Independently written: this computes the log-mel pipeline from the formulas in
`detector/ARCHITECTURE.md` using numpy's FFT, with no reference to the Rust. It prints a
Rust test module, which is pasted into `microfrontend.rs` rather than read at test time so
that `cargo test` needs no fixture file.

    python scripts/ml/nnfp_reference.py <path to music_detector.sound_model>
"""

import sys

import numpy as np
from tflite.Model import Model

F32 = np.float32
SAMPLE_RATE, FFT_SIZE, CHANNELS = 16000, 512, 32
WINDOW, HOP, WINDOW_BITS = 400, 160, 12
LOWER, UPPER, CORRECTION, CEILING = 60.0, 3800.0, 3, 15.0
# The fixed-point kissfft the device uses halves at each radix-2 stage, so its forward
# transform carries a 1/N. See `Config::fft_gain` in microfrontend.rs.
FFT_GAIN = 1.0 / FFT_SIZE
FRAMES = 40


def mel(hz):
    return F32(F32(1127.0) * F32(np.log1p(F32(F32(hz) / F32(700.0)))))


def hann():
    i = np.arange(WINDOW)
    return np.floor((0.5 - 0.5 * np.cos(2 * np.pi * (i + 0.5) / WINDOW)) * (1 << WINDOW_BITS) + 0.5)


def breakpoints():
    hz_per_bin = F32(F32(SAMPLE_RATE) / F32(FFT_SIZE))
    low, high = mel(LOWER), mel(UPPER)
    spacing = F32((high - low) / F32(CHANNELS + 1))
    centres = [F32(low + spacing * F32(c + 1)) for c in range(CHANNELS + 1)]
    start = int(1.5 + LOWER / float(hz_per_bin))
    end = start
    while mel(end * float(hz_per_bin)) <= high:
        end += 1
    widths = [0] * (CHANNELS + 1)
    weights, unweights = [], []
    band = 0
    for b in range(start, end):
        here = mel(b * float(hz_per_bin))
        while band < CHANNELS and here > centres[band]:
            band += 1
        rising = F32((centres[band] - here) / spacing)
        weights.append(int(F32(rising) * (1 << WINDOW_BITS)))
        unweights.append(int(F32(F32(1.0) - rising) * (1 << WINDOW_BITS)))
        widths[band] += 1
    starts, at = [], start
    for width in widths:
        starts.append(at)
        at += width
    return widths, starts, weights, unweights, start, end


def synthetic(count):
    """A deterministic i16 signal both sides can produce bit-identically.

    Integer only, on purpose: a sine would go through `sin`, and a one-ulp difference between
    numpy's and Rust's would round a sample differently and make the comparison meaningless.
    A square wave at 16000/36 Hz gives a strong fundamental and odd harmonics across the band,
    and the linear-congruential noise keeps every mel channel occupied. The amplitudes are
    chosen to land the log-mel values inside 0..CEILING rather than pinned to the clamp, so
    that the comparison actually tests the arithmetic.
    """
    out = np.zeros(count, dtype=np.int64)
    seed = 12345
    for n in range(count):
        seed = (seed * 1664525 + 1013904223) & 0xFFFFFFFF
        noise = ((seed >> 16) & 0x1FF) - 256
        square = 900 if (n // 18) % 2 == 0 else -900
        out[n] = max(-32768, min(32767, square + noise))
    return out.astype(np.int16)


def log_mel(pcm, window, widths, starts, weights, unweights, first, last):
    frames = []
    at = 0
    while at + WINDOW <= len(pcm):
        block = pcm[at : at + WINDOW].astype(np.int64)
        # `>>` on a negative in Rust is arithmetic, which is a floor. numpy's is too.
        windowed = (block * window.astype(np.int64)) >> WINDOW_BITS
        padded = np.zeros(FFT_SIZE)
        padded[:WINDOW] = windowed
        spectrum = np.fft.rfft(padded) * FFT_GAIN
        power = np.zeros(FFT_SIZE // 2 + 1)
        power[first:last] = np.abs(spectrum[first:last]) ** 2
        rising = np.zeros(len(widths))
        falling = np.zeros(len(widths))
        j = 0
        for band, width in enumerate(widths):
            bins = power[starts[band] : starts[band] + width]
            rising[band] = np.dot(np.array(weights[j : j + width], dtype=float), bins)
            falling[band] = np.dot(np.array(unweights[j : j + width], dtype=float), bins)
            j += width
        frame = []
        for c in range(CHANNELS):
            energy = rising[c + 1] + falling[c]
            value = np.log((1 << CORRECTION) * np.sqrt(energy)) if energy > 0 else -np.inf
            frame.append(float(np.clip(value, 0.0, CEILING)))
        frames.append(frame)
        at += HOP
    return frames


def model_tables(path):
    raw = open(path, "rb").read()
    offset = 0 if raw[4:8] == b"TFL3" else raw.find(b"TFL3") - 4
    model = Model.GetRootAsModel(raw[offset:], 0)
    graph = model.Subgraphs(2)
    out = {}
    for index in (1, 2, 3, 4, 5, 6):
        tensor = graph.Tensors(index)
        buffer = model.Buffers(tensor.Buffer())
        out[index] = np.frombuffer(buffer.DataAsNumpy().tobytes(), dtype=np.int16)
    return out


def rust_slice(name, kind, values, per_line=12):
    body = ""
    for start in range(0, len(values), per_line):
        row = ", ".join(str(int(v)) for v in values[start : start + per_line])
        body += f"        {row},\n"
    return f"    const {name}: [{kind}; {len(values)}] = [\n{body}    ];\n"


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    tables = model_tables(sys.argv[1])
    window = hann()
    widths, starts, weights, unweights, first, last = breakpoints()

    for label, mine, theirs in [
        ("window", window, tables[1]),
        ("widths", widths, tables[2]),
        ("weight_starts", np.cumsum([0] + widths[:-1]), tables[3]),
        ("freq_starts", starts, tables[4]),
        ("unweights", unweights, tables[5]),
        ("weights", weights, tables[6]),
    ]:
        same = np.array_equal(np.array(mine, dtype=np.int64), theirs.astype(np.int64))
        print(f"# {label:14s} reproduces the model exactly: {same}", file=sys.stderr)
        if not same:
            raise SystemExit(f"{label} does not match the shipped tensor")

    samples = WINDOW + HOP * (FRAMES - 1)
    pcm = synthetic(samples)
    frames = log_mel(pcm, window, widths, starts, weights, unweights, first, last)
    print(f"# {len(frames)} frames from {samples} samples", file=sys.stderr)

    probes = [0, 1, 19, FRAMES - 1]
    print("    // Generated by scripts/ml/nnfp_reference.py; see its docstring.")
    print(rust_slice("MODEL_WINDOW", "i32", tables[1]))
    print(rust_slice("MODEL_WIDTHS", "u16", tables[2], 16))
    print(rust_slice("MODEL_FREQ_STARTS", "u16", tables[4], 16))
    print(rust_slice("MODEL_UNWEIGHTS", "u16", tables[5], 12))
    print(rust_slice("MODEL_WEIGHTS", "u16", tables[6], 12))
    print(f"    const PROBE_FRAMES: [usize; {len(probes)}] = {list(probes)};".replace("[", "[").replace("]", "]"))
    flat = [v for p in probes for v in frames[p]]
    body = ""
    for start in range(0, len(flat), 6):
        # Nine significant digits: f32 carries about seven, so the printed value is not what
        # limits the comparison. At six the fixture's own rounding dominated the residual.
        row = ", ".join(f"{v:.9}" for v in flat[start : start + 6])
        body += f"        {row},\n"
    print(f"    const PROBE_LOG_MEL: [f32; {len(flat)}] = [\n{body}    ];")


if __name__ == "__main__":
    main()
