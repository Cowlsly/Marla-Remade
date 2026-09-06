//! 16 kHz PCM to log-mel frames, on the CPU.
//!
//! A port of Google's TFLM "microfrontend" op chain — `Framer`, `Window`, `FftAutoScale`,
//! `Rfft`, `Energy`, `FilterBank`, `FilterBankSquareRoot`, `FilterBankLog` — which is the
//! front end of the Now Playing music models in [`crate::nets::nnfp`]. It is written against
//! a [`Config`] rather than against that one model, because the same chain is what any
//! keyword spotter or pitch tracker here would want.
//!
//! # Why this is not a shader
//!
//! One frame is a 512-point FFT and 119 multiply-accumulates: a few thousand flops every
//! 10 ms. The dispatch and readback of a compute pass would cost more than the arithmetic, and
//! the host is where a sign error in a window or an off-by-one in a filter bank actually gets
//! caught. [`crate::preprocess`] is not a shader for the same reason.
//!
//! # Fixed point, and where this deliberately leaves it
//!
//! The device pipeline is integer end to end. `FftAutoScale` finds the left shift `s` that puts
//! the loudest sample just below i16 full scale, applies it so the i16 FFT keeps its precision,
//! and hands `s` to `FilterBankSquareRoot`, which shifts it back out. Those two cancel exactly:
//! energy is `2^(2s) |X|^2` because the transform is linear and energy quadratic, its square
//! root is `2^s |X|`, and `>> s` recovers `|X|`. So block floating point buys precision for an
//! i16 FFT and changes nothing else. Computing in f32 makes it unnecessary, and this module
//! drops it. Two more integer stages go the same way: the u64 integer square root becomes
//! [`f32::sqrt`], and Google's fixed-point `Log32` polynomial becomes [`f32::ln`].
//!
//! What does **not** cancel is the fixed-point transform's own `1 / fft_size` attenuation, which
//! a float implementation must apply explicitly. See [`Config::fft_gain`].
//!
//! All three departures **reduce** error rather than introduce it, and `output_scale = 1600` —
//! which is what ties this front end to the networks, whose input quantises at exactly
//! `1 / 1600` — is preserved, because these values are in natural-log units either way.
//!
//! Two quantisations are kept, because they are not rounding error but what the signal has
//! actually become by the time a network sees it:
//!
//! * the `>> 12` after windowing, which is real quantisation of the windowed samples;
//! * the 12-bit truncated filter-bank weights, where `weights[j] + unweights[j]` is 4095 and
//!   not 4096.
//!
//! **This is therefore not bit-exact with the DSP, and has never been diffed against it.** It
//! is the same pipeline computed more accurately. The tests below check it against an
//! independently written reference for the pipeline as specified, which is a different and
//! weaker claim.
//!
//! # The tables are computed, not shipped
//!
//! [`mel_breakpoints`] and [`hann_window`] regenerate the six constant tensors that the Now
//! Playing `.tflite` carries as literals. The tests assert they reproduce those tensors **bit
//! for bit**, which is both the correctness check and the reason a caller may ask for different
//! band limits and still get a table the same code path would have produced. The mel arithmetic
//! must be f32 throughout: in f64 several breakpoints land one least-significant bit away,
//! because the truncation to 12 bits happens to sit on a boundary.

/// How PCM becomes log-mel frames.
///
/// [`NOW_PLAYING`] is what the music models were trained with. The fields are public so a
/// different model can ask for a different band, rate or resolution.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Config {
    /// Samples per second of the incoming PCM.
    pub sample_rate: u32,
    /// Samples per analysis window.
    pub window_samples: usize,
    /// Samples between the start of one window and the start of the next.
    pub hop_samples: usize,
    /// FFT length. At least `window_samples`, a power of two; the window is zero padded to it.
    pub fft_size: usize,
    /// Mel channels out. The filter bank uses `channels + 1` bands to make them.
    pub channels: usize,
    /// Low edge of the mel band, in Hz.
    pub lower_band_limit: f32,
    /// High edge of the mel band, in Hz.
    pub upper_band_limit: f32,
    /// Fractional bits in the window table, so a tap of 1.0 is `1 << window_bits`.
    pub window_bits: u32,
    /// Multiplier on the transform output, before energy is taken.
    ///
    /// The device's `Rfft` attenuates by `1 / fft_size`; a plain f32 transform does not. The
    /// factor is not cosmetic. It is a *constant offset* of `ln(fft_size)` — 6.24 nats, 41.6% of
    /// the usable range — added uniformly to every channel, which pins every frame to
    /// [`Config::ceiling`]. Omitting it produces a pipeline that looks like it works until you
    /// notice every output is identical.
    ///
    /// **This is forced by the model, not inferred from the kernel.** `Rfft` is declared
    /// i16 to i16, and `FftAutoScale` has just normalised its input to near full i16 scale, so a
    /// unit-gain DC bin would be `512 * 32767` — a 512x overflow of the declared output type.
    /// The op cannot have unit gain. That the mechanism happens to be a fixed-point kissfft
    /// halving at each radix-2 stage is then a detail; the attenuation is structural.
    ///
    /// Two independent anchors agree. `FftAutoScale` would have nothing to protect if the
    /// transform did not shrink its input. And the classifier's input quantisation is exactly
    /// `15 / 255` at zero point -128, so its design range is precisely `[0, 15]` nats — content
    /// that belongs in 8..15 lands at 14.24..21.24 without this factor, which is the saturation
    /// that first exposed it.
    pub fft_gain: f32,
    /// Left shift applied inside the log, compensating precision lost in the square root.
    pub log_correction_bits: u32,
    /// Upper clamp on an output value, in natural-log units.
    ///
    /// Both networks quantise this frame to int8 at `scale = 0.05882353, zero_point = -128`,
    /// so anything above `255 * 0.05882353` saturates there. Reproducing the clamp keeps a loud
    /// input from behaving differently here than on the device. [`f32::INFINITY`] for none.
    pub ceiling: f32,
}

/// The front end the Now Playing music detector and fingerprinter were trained with.
///
/// Every value is recovered from `music_detector.sound_model`: 25 ms windows at a 10 ms hop,
/// 32 mel channels spanning 60–3800 Hz off a 512-point FFT.
pub const NOW_PLAYING: Config = Config {
    sample_rate: 16_000,
    window_samples: 400,
    hop_samples: 160,
    fft_size: 512,
    channels: 32,
    lower_band_limit: 60.0,
    upper_band_limit: 3800.0,
    window_bits: 12,
    fft_gain: 1.0 / 512.0,
    log_correction_bits: 3,
    ceiling: 15.0,
};

/// `1127 * ln(1 + hz / 700)`, in f32.
///
/// The precision is load bearing rather than incidental: the same formula evaluated in f64 and
/// rounded at the end moves several of the 119 filter-bank weights by one, because the
/// truncation to 12 bits sits on a boundary for them. The tests pin that.
fn mel(hz: f32) -> f32 {
    1127.0f32 * (hz / 700.0f32).ln_1p()
}

/// The triangular mel filter bank, as the integer tables the device holds.
///
/// `channels + 1` bands partition the live FFT bins. Band `b` contributes `weights[j]` to
/// channel `b - 1` and `unweights[j]` to channel `b`, so band 0's weighted half falls off the
/// bottom and is discarded — that is what turns 33 bands into 32 channels.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FilterBank {
    /// First FFT bin of each band, `channels + 1` entries.
    pub freq_starts: Vec<usize>,
    /// Bins in each band, `channels + 1` entries, summing to `weights.len()`.
    pub widths: Vec<usize>,
    /// Rising-edge weight per live bin, truncated to `window_bits` fractional bits.
    pub weights: Vec<u16>,
    /// Falling-edge weight per live bin. `weights[j] + unweights[j]` is one below
    /// `1 << window_bits`, because both halves truncate.
    pub unweights: Vec<u16>,
    /// First FFT bin the bank reads, `(int)(1.5 + lower_band_limit / hz_per_bin)`.
    pub start_index: usize,
    /// One past the last FFT bin the bank reads.
    pub end_index: usize,
}

/// The filter bank `config` describes, or why it cannot be built.
pub fn mel_breakpoints(config: &Config) -> Result<FilterBank, String> {
    if config.fft_size == 0 || config.channels == 0 {
        return Err("a filter bank needs a non-zero FFT size and channel count".to_string());
    }
    let hz_per_bin = config.sample_rate as f32 / config.fft_size as f32;
    let low = mel(config.lower_band_limit);
    let high = mel(config.upper_band_limit);
    if high <= low || !high.is_finite() {
        return Err(format!(
            "the band {}..{} Hz is empty in mel",
            config.lower_band_limit, config.upper_band_limit
        ));
    }
    let spacing = (high - low) / (config.channels as f32 + 1.0);
    // Centres for bands 0..=channels. The last lands exactly on `high` by construction.
    let centres: Vec<f32> = (0..=config.channels).map(|c| low + spacing * (c + 1) as f32).collect();

    let start_index = (1.5 + config.lower_band_limit / hz_per_bin) as usize;
    let bins = config.fft_size / 2 + 1;
    let mut end_index = start_index;
    while end_index < bins && mel(end_index as f32 * hz_per_bin) <= high {
        end_index += 1;
    }
    if end_index <= start_index {
        return Err(format!("no FFT bin falls in {}..{} Hz", config.lower_band_limit, high));
    }

    let one = (1u32 << config.window_bits) as f32;
    let mut widths = vec![0usize; config.channels + 1];
    let mut weights = Vec::with_capacity(end_index - start_index);
    let mut unweights = Vec::with_capacity(end_index - start_index);
    let mut band = 0usize;
    for bin in start_index..end_index {
        let here = mel(bin as f32 * hz_per_bin);
        while band < config.channels && here > centres.get(band).copied().unwrap_or(high) {
            band += 1;
        }
        let centre = centres.get(band).copied().unwrap_or(high);
        let rising = (centre - here) / spacing;
        weights.push((rising * one) as u16);
        unweights.push(((1.0 - rising) * one) as u16);
        if let Some(width) = widths.get_mut(band) {
            *width += 1;
        }
    }
    // Bands are contiguous from `start_index`, so a start is the running total of the widths
    // before it. Writing it that way rather than recording the first bin of each band keeps an
    // empty band's start meaningful instead of leaving it at zero.
    let mut next = start_index;
    let freq_starts = widths
        .iter()
        .map(|width| {
            let first = next;
            next += width;
            first
        })
        .collect();
    Ok(FilterBank { freq_starts, widths, weights, unweights, start_index, end_index })
}

/// The analysis window: a *periodic* Hann offset by half a sample, in `window_bits` fixed point.
///
/// `w[i] = floor((0.5 - 0.5 cos(2 pi (i + 0.5) / n)) * 2^bits + 0.5)`. The half-sample offset
/// and the round — rather than a truncate — are both load bearing: the tests pin the table
/// against the one the model ships, which none of the obvious near misses reproduce.
pub fn hann_window(config: &Config) -> Vec<i32> {
    let n = config.window_samples;
    let one = (1u32 << config.window_bits) as f64;
    (0..n)
        .map(|i| {
            let phase = std::f64::consts::TAU * (i as f64 + 0.5) / n as f64;
            ((0.5 - 0.5 * phase.cos()) * one + 0.5).floor() as i32
        })
        .collect()
}

/// A streaming log-mel front end: push PCM, get frames.
///
/// Holds the framer's partial window between calls, so a caller can hand it whatever the audio
/// source produced without aligning to a hop boundary. [`Frontend::reset`] clears that state.
/// Nothing allocates after construction.
pub struct Frontend {
    config: Config,
    window: Vec<i32>,
    bank: FilterBank,
    /// Samples not yet consumed by a frame.
    pending: Vec<i16>,
    spectrum: Vec<f32>,
    scratch: Vec<[f32; 2]>,
    twiddles: Vec<[f32; 2]>,
    reversed: Vec<u32>,
    rising: Vec<f32>,
    falling: Vec<f32>,
    frame: Vec<f32>,
}

impl Frontend {
    /// A front end for `config`, or why that configuration cannot be built.
    pub fn new(config: Config) -> Result<Frontend, String> {
        if config.hop_samples == 0 || config.window_samples == 0 {
            return Err("a front end needs a non-zero window and hop".to_string());
        }
        if config.fft_size < config.window_samples || !config.fft_size.is_power_of_two() {
            return Err(format!(
                "an FFT of {} cannot hold a {}-sample window, or is not a power of two",
                config.fft_size, config.window_samples
            ));
        }
        let bank = mel_breakpoints(&config)?;
        let bands = bank.widths.len();
        let window = hann_window(&config);
        let (twiddles, reversed) = fft_tables(config.fft_size);
        Ok(Frontend {
            spectrum: vec![0.0; config.fft_size / 2 + 1],
            scratch: vec![[0.0, 0.0]; config.fft_size],
            rising: vec![0.0; bands],
            falling: vec![0.0; bands],
            frame: vec![0.0; config.channels],
            pending: Vec::with_capacity(config.window_samples + config.hop_samples),
            window,
            bank,
            twiddles,
            reversed,
            config,
        })
    }

    /// Values in one frame, which is [`Config::channels`].
    pub fn channels(&self) -> usize {
        self.config.channels
    }

    /// Forget the partial window, so the next frame starts from silence.
    pub fn reset(&mut self) {
        self.pending.clear();
    }

    /// The power spectrum of the most recent frame, `fft_size / 2 + 1` bins.
    ///
    /// For a caller that wants the spectrum rather than the mel projection — pitch detection,
    /// say. Only the bins the filter bank reads are computed; the rest stay zero, because
    /// nothing in this pipeline looks at them.
    pub fn spectrum(&self) -> &[f32] {
        &self.spectrum
    }

    /// Append `samples`, emitting every frame that completes into `out`, in order.
    ///
    /// A frame is [`Config::channels`] values of `ln(magnitude)`, floored at zero and clamped to
    /// [`Config::ceiling`]. Returns how many frames were appended.
    pub fn process(&mut self, samples: &[i16], out: &mut Vec<f32>) -> usize {
        self.pending.extend_from_slice(samples);
        let mut produced = 0;
        while self.pending.len() >= self.config.window_samples {
            self.analyse();
            out.extend_from_slice(&self.frame);
            produced += 1;
            let hop = self.config.hop_samples.min(self.pending.len());
            self.pending.copy_within(hop.., 0);
            let keep = self.pending.len() - hop;
            self.pending.truncate(keep);
        }
        produced
    }

    /// Window, transform and project the leading `window_samples` of `pending` into `frame`.
    fn analyse(&mut self) {
        let shift = self.config.window_bits;
        for (slot, (sample, tap)) in
            self.scratch.iter_mut().zip(self.pending.iter().zip(self.window.iter()))
        {
            // The device's `Window` op verbatim: an arithmetic shift, so negatives floor.
            *slot = [((*sample as i32 * *tap) >> shift) as f32, 0.0];
        }
        for slot in self.scratch.iter_mut().skip(self.config.window_samples) {
            *slot = [0.0, 0.0];
        }
        fft(&mut self.scratch, &self.twiddles, &self.reversed);

        let (first_bin, last_bin) = (self.bank.start_index, self.bank.end_index);
        // Energy is quadratic, so the transform's gain is squared here rather than applied to
        // the coefficients — one multiply per bin instead of two.
        let gain = self.config.fft_gain * self.config.fft_gain;
        for (bin, slot) in self.spectrum.iter_mut().enumerate() {
            *slot = match self.scratch.get(bin) {
                Some([re, im]) if bin >= first_bin && bin < last_bin => (re * re + im * im) * gain,
                _ => 0.0,
            };
        }

        let mut at = 0usize;
        for (band, width) in self.bank.widths.iter().enumerate() {
            let first = self.bank.freq_starts.get(band).copied().unwrap_or(0);
            let mut up = 0.0f32;
            let mut down = 0.0f32;
            for step in 0..*width {
                let power = self.spectrum.get(first + step).copied().unwrap_or(0.0);
                up += self.bank.weights.get(at + step).copied().unwrap_or(0) as f32 * power;
                down += self.bank.unweights.get(at + step).copied().unwrap_or(0) as f32 * power;
            }
            if let Some(slot) = self.rising.get_mut(band) {
                *slot = up;
            }
            if let Some(slot) = self.falling.get_mut(band) {
                *slot = down;
            }
            at += width;
        }

        let correction = (1u32 << self.config.log_correction_bits) as f32;
        let ceiling = self.config.ceiling;
        for (channel, slot) in self.frame.iter_mut().enumerate() {
            // Channel `c` is band `c + 1`'s rising edge plus band `c`'s falling edge. That
            // discards band 0's rising half, which is the triangle below the first centre, and
            // is what makes `channels + 1` bands into `channels` outputs.
            let energy = self.rising.get(channel + 1).copied().unwrap_or(0.0)
                + self.falling.get(channel).copied().unwrap_or(0.0);
            // `FilterBankLog` floors at an argument of one, which in log units is zero — the
            // same place `ln` turns negative, so one clamp covers both ends.
            *slot = (correction * energy.sqrt()).ln().clamp(0.0, ceiling);
        }
    }
}

/// Twiddle factors `exp(-2 pi i k / n)` for `k < n / 2`, and the bit-reversal permutation.
///
/// `pub(crate)` because [`crate::logmel`] needs the same 512-point transform under a different
/// mel chain, and two Cooley-Tukeys in one crate is one too many.
pub(crate) fn fft_tables(n: usize) -> (Vec<[f32; 2]>, Vec<u32>) {
    let twiddles = (0..n / 2)
        .map(|k| {
            let angle = -std::f64::consts::TAU * k as f64 / n as f64;
            [angle.cos() as f32, angle.sin() as f32]
        })
        .collect();
    let bits = n.trailing_zeros();
    let reversed = (0..n).map(|i| (i as u32).reverse_bits() >> (32 - bits)).collect();
    (twiddles, reversed)
}

/// In-place iterative radix-2 Cooley-Tukey, decimation in time.
///
/// Nothing here is worth specialising: 512 points is 2,304 butterflies once per 10 ms of audio.
/// A real-input transform would halve that and is not worth the packing.
///
/// `pub(crate)` for [`crate::logmel`]. See [`fft_tables`].
pub(crate) fn fft(data: &mut [[f32; 2]], twiddles: &[[f32; 2]], reversed: &[u32]) {
    let n = data.len();
    for i in 0..n {
        let j = reversed.get(i).copied().unwrap_or(0) as usize;
        if j > i {
            data.swap(i, j);
        }
    }
    let mut span = 2usize;
    while span <= n {
        let half = span / 2;
        let stride = n / span;
        let mut base = 0usize;
        while base < n {
            for k in 0..half {
                let Some(&[wr, wi]) = twiddles.get(k * stride) else { continue };
                let Some(&[br, bi]) = data.get(base + k + half) else { continue };
                let Some(&[ar, ai]) = data.get(base + k) else { continue };
                let vr = br * wr - bi * wi;
                let vi = br * wi + bi * wr;
                if let Some(slot) = data.get_mut(base + k) {
                    *slot = [ar + vr, ai + vi];
                }
                if let Some(slot) = data.get_mut(base + k + half) {
                    *slot = [ar - vr, ai - vi];
                }
            }
            base += span;
        }
        span *= 2;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Generated by scripts/ml/nnfp_reference.py; see its docstring.
    const MODEL_WINDOW: [i32; 400] = [
        0, 1, 2, 3, 5, 8, 11, 14, 18, 23, 28, 33,
        39, 46, 53, 60, 68, 77, 86, 95, 105, 116, 127, 138,
        150, 162, 175, 188, 202, 216, 231, 246, 261, 277, 293, 310,
        327, 345, 363, 382, 401, 420, 440, 460, 480, 501, 522, 544,
        566, 589, 611, 634, 658, 682, 706, 730, 755, 780, 806, 831,
        857, 884, 910, 937, 964, 992, 1019, 1047, 1075, 1104, 1133, 1161,
        1191, 1220, 1249, 1279, 1309, 1339, 1369, 1400, 1430, 1461, 1492, 1523,
        1554, 1586, 1617, 1648, 1680, 1712, 1744, 1775, 1807, 1839, 1871, 1903,
        1935, 1968, 2000, 2032, 2064, 2096, 2128, 2161, 2193, 2225, 2257, 2289,
        2321, 2352, 2384, 2416, 2448, 2479, 2510, 2542, 2573, 2604, 2635, 2666,
        2696, 2727, 2757, 2787, 2817, 2847, 2876, 2905, 2935, 2963, 2992, 3021,
        3049, 3077, 3104, 3132, 3159, 3186, 3212, 3239, 3265, 3290, 3316, 3341,
        3366, 3390, 3414, 3438, 3462, 3485, 3507, 3530, 3552, 3574, 3595, 3616,
        3636, 3656, 3676, 3695, 3714, 3733, 3751, 3769, 3786, 3803, 3819, 3835,
        3850, 3865, 3880, 3894, 3908, 3921, 3934, 3946, 3958, 3969, 3980, 3991,
        4001, 4010, 4019, 4028, 4036, 4043, 4050, 4057, 4063, 4068, 4073, 4078,
        4082, 4085, 4088, 4091, 4093, 4094, 4095, 4096, 4096, 4095, 4094, 4093,
        4091, 4088, 4085, 4082, 4078, 4073, 4068, 4063, 4057, 4050, 4043, 4036,
        4028, 4019, 4010, 4001, 3991, 3980, 3969, 3958, 3946, 3934, 3921, 3908,
        3894, 3880, 3865, 3850, 3835, 3819, 3803, 3786, 3769, 3751, 3733, 3714,
        3695, 3676, 3656, 3636, 3616, 3595, 3574, 3552, 3530, 3507, 3485, 3462,
        3438, 3414, 3390, 3366, 3341, 3316, 3290, 3265, 3239, 3212, 3186, 3159,
        3132, 3104, 3077, 3049, 3021, 2992, 2963, 2935, 2905, 2876, 2847, 2817,
        2787, 2757, 2727, 2696, 2666, 2635, 2604, 2573, 2542, 2510, 2479, 2448,
        2416, 2384, 2352, 2321, 2289, 2257, 2225, 2193, 2161, 2128, 2096, 2064,
        2032, 2000, 1968, 1935, 1903, 1871, 1839, 1807, 1775, 1744, 1712, 1680,
        1648, 1617, 1586, 1554, 1523, 1492, 1461, 1430, 1400, 1369, 1339, 1309,
        1279, 1249, 1220, 1191, 1161, 1133, 1104, 1075, 1047, 1019, 992, 964,
        937, 910, 884, 857, 831, 806, 780, 755, 730, 706, 682, 658,
        634, 611, 589, 566, 544, 522, 501, 480, 460, 440, 420, 401,
        382, 363, 345, 327, 310, 293, 277, 261, 246, 231, 216, 202,
        188, 175, 162, 150, 138, 127, 116, 105, 95, 86, 77, 68,
        60, 53, 46, 39, 33, 28, 23, 18, 14, 11, 8, 5,
        3, 2, 1, 0,
    ];

    const MODEL_WIDTHS: [u16; 33] = [
        1, 1, 2, 1, 2, 2, 2, 2, 2, 2, 2, 3, 2, 3, 3, 3,
        3, 3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 6, 8,
        7,
    ];

    const MODEL_FREQ_STARTS: [u16; 33] = [
        3, 4, 5, 7, 8, 10, 12, 14, 16, 18, 20, 22, 25, 27, 30, 33,
        36, 39, 42, 46, 50, 54, 58, 62, 67, 72, 77, 82, 88, 94, 101, 107,
        115,
    ];

    const MODEL_UNWEIGHTS: [u16; 119] = [
        3302, 2140, 870, 3594, 2128, 575, 3034, 1321, 3631, 1777, 3955, 1976,
        4036, 1945, 3899, 1708, 3566, 1284, 3056, 691, 2384, 4040, 1564, 3151,
        609, 2132, 3626, 994, 2431, 3841, 1129, 2489, 3825, 1041, 2332, 3600,
        752, 1980, 3189, 282, 1453, 2606, 3742, 766, 1869, 2956, 4028, 989,
        2032, 3060, 4075, 980, 1968, 2944, 3907, 762, 1702, 2630, 3547, 357,
        1252, 2136, 3011, 3876, 635, 1480, 2316, 3143, 3961, 675, 1475, 2268,
        3052, 3829, 501, 1262, 2015, 2761, 3500, 135, 859, 1577, 2288, 2992,
        3690, 286, 971, 1650, 2324, 2991, 3652, 212, 862, 1507, 2146, 2780,
        3409, 4032, 554, 1168, 1776, 2380, 2979, 3573, 67, 652, 1232, 1809,
        2380, 2948, 3511, 4071, 530, 1081, 1628, 2171, 2711, 3246, 3778,
    ];

    const MODEL_WEIGHTS: [u16; 119] = [
        793, 1955, 3225, 501, 1967, 3520, 1061, 2774, 464, 2318, 140, 2119,
        59, 2150, 196, 2387, 529, 2811, 1039, 3404, 1711, 55, 2531, 944,
        3486, 1963, 469, 3101, 1664, 254, 2966, 1606, 270, 3054, 1763, 495,
        3343, 2115, 906, 3813, 2642, 1489, 353, 3329, 2226, 1139, 67, 3106,
        2063, 1035, 20, 3115, 2127, 1151, 188, 3333, 2393, 1465, 548, 3738,
        2843, 1959, 1084, 219, 3460, 2615, 1779, 952, 134, 3420, 2620, 1827,
        1043, 266, 3594, 2833, 2080, 1334, 595, 3960, 3236, 2518, 1807, 1103,
        405, 3809, 3124, 2445, 1771, 1104, 443, 3883, 3233, 2588, 1949, 1315,
        686, 63, 3541, 2927, 2319, 1715, 1116, 522, 4028, 3443, 2863, 2286,
        1715, 1147, 584, 24, 3565, 3014, 2467, 1924, 1384, 849, 317,
    ];

    const PROBE_FRAMES: [usize; 4] = [0, 1, 19, 39];
    const PROBE_LOG_MEL: [f32; 128] = [
        7.18161774, 7.37955594, 7.61032519, 7.44921308, 7.48374326, 9.49555759,
        11.5424359, 11.7046542, 9.82276901, 8.03501223, 7.53047145, 7.90233981,
        7.99683884, 7.4385179, 7.98894075, 8.30633025, 8.83349774, 10.6828328,
        10.1599948, 8.57639137, 8.02079926, 7.9518684, 8.15373332, 9.08686724,
        10.3715738, 9.09777509, 8.51311291, 8.51245852, 9.19846645, 10.086446,
        8.81702398, 8.47765944, 7.08221245, 7.89213906, 7.6227414, 7.54640009,
        7.40207052, 9.4965034, 11.5425661, 11.711765, 9.86768146, 7.85511495,
        7.72700583, 7.42780994, 7.06324804, 7.72029727, 8.41909339, 7.97282499,
        8.69977386, 10.7431168, 10.2558111, 7.74150255, 8.24210159, 8.20045459,
        8.5961293, 9.05891434, 10.3194791, 9.04083452, 8.34013706, 7.92185519,
        9.03233084, 10.1478033, 8.87693631, 8.66631753, 8.11772668, 7.88764563,
        7.55787315, 7.73268939, 7.57627846, 9.45679257, 11.530846, 11.7115067,
        9.86535083, 7.25441869, 7.32922098, 7.5483784, 8.45190646, 8.24958062,
        8.06509005, 8.06068231, 8.83414464, 10.7746654, 10.28436, 8.19054625,
        8.24616046, 8.60819825, 8.43584666, 9.02522813, 10.2432982, 9.00337361,
        8.06064297, 8.4133085, 9.08349741, 10.0722881, 8.50925708, 8.81470381,
        7.75420886, 7.58785263, 7.77240568, 7.93850746, 7.89886418, 9.50144794,
        11.5305868, 11.6832059, 9.76646398, 7.6421791, 7.05842435, 7.91109077,
        8.18258625, 8.04541793, 8.3534498, 8.01966836, 8.69126347, 10.7331839,
        10.2156687, 7.91517444, 7.996182, 8.03223468, 8.22614966, 9.13978872,
        10.3838735, 9.17924121, 8.74041469, 8.71177762, 9.23975998, 10.061276,
        8.2357872, 8.6366385,
    ];

    /// The signal `scripts/ml/nnfp_reference.py` fed its reference implementation.
    ///
    /// Integer only, so both sides produce bit-identical samples: a sine would go through
    /// `sin`, and a one-ulp difference between numpy's and Rust's would round a sample
    /// differently and make the whole comparison meaningless.
    fn synthetic(count: usize) -> Vec<i16> {
        let mut out = Vec::with_capacity(count);
        let mut seed: u32 = 12345;
        for n in 0..count {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            let noise = ((seed >> 16) & 0x1ff) as i32 - 256;
            let square = if (n / 18) % 2 == 0 { 900 } else { -900 };
            out.push((square + noise).clamp(-32768, 32767) as i16);
        }
        out
    }

    fn bank() -> FilterBank {
        mel_breakpoints(&NOW_PLAYING).expect("the shipped configuration builds a bank")
    }

    #[test]
    fn the_window_table_reproduces_the_shipped_tensor() {
        // `music_detector.sound_model` subgraph 2 tensor 1, verbatim. A periodic Hann without
        // the half-sample offset is wrong by up to 17, a symmetric Hann by 10, and truncating
        // instead of rounding by 1 on half the taps, so this pins all three choices at once.
        assert_eq!(hann_window(&NOW_PLAYING), MODEL_WINDOW.to_vec());
    }

    #[test]
    fn the_filter_bank_reproduces_the_shipped_tensors() {
        // Tensors 2, 4, 5 and 6 of the same subgraph. Reproducing 119 twelve-bit weights and
        // their complements exactly is a strong check on the mel formula, on the arithmetic
        // being f32 rather than f64, and on `floor` rather than `round` at the fixed-point step.
        let bank = bank();
        assert_eq!(bank.widths, MODEL_WIDTHS.iter().map(|w| *w as usize).collect::<Vec<_>>());
        assert_eq!(
            bank.freq_starts,
            MODEL_FREQ_STARTS.iter().map(|w| *w as usize).collect::<Vec<_>>()
        );
        assert_eq!(bank.weights, MODEL_WEIGHTS.to_vec());
        assert_eq!(bank.unweights, MODEL_UNWEIGHTS.to_vec());
        // Both derived rather than configured, and both agree with the `Energy` op's options.
        assert_eq!((bank.start_index, bank.end_index), (3, 122));
    }

    #[test]
    fn the_bands_partition_the_live_bins() {
        let bank = bank();
        assert_eq!(bank.widths.iter().sum::<usize>(), bank.weights.len());
        assert_eq!(bank.weights.len(), bank.end_index - bank.start_index);
        assert_eq!(bank.widths.len(), NOW_PLAYING.channels + 1);
        let last = bank.freq_starts.last().copied().unwrap_or(0);
        let width = bank.widths.last().copied().unwrap_or(0);
        assert_eq!(last + width, bank.end_index);
    }

    #[test]
    fn the_two_halves_of_each_weight_are_twelve_bit_complements() {
        // `weights[j] + unweights[j] == 4095`, not 4096: both halves truncate, so a least
        // significant bit is lost. Rounding either would break this and quietly change gain.
        let bank = bank();
        for (rising, falling) in bank.weights.iter().zip(bank.unweights.iter()) {
            assert_eq!(rising + falling, 4095);
        }
    }

    #[test]
    fn the_transform_matches_a_naive_discrete_fourier_transform() {
        // The radix-2 butterflies against the O(n^2) definition, which shares no code with
        // them. Catches a flipped twiddle sign or a bit-reversal off by one, either of which
        // still produces plausible-looking spectra.
        let n = 64;
        let (twiddles, reversed) = fft_tables(n);
        let input: Vec<f32> = (0..n).map(|i| ((i * 37 % 19) as f32) - 9.0).collect();
        let mut data: Vec<[f32; 2]> = input.iter().map(|v| [*v, 0.0]).collect();
        fft(&mut data, &twiddles, &reversed);
        for k in 0..n {
            let (mut re, mut im) = (0.0f64, 0.0f64);
            for (t, value) in input.iter().enumerate() {
                let angle = -std::f64::consts::TAU * (k * t) as f64 / n as f64;
                re += *value as f64 * angle.cos();
                im += *value as f64 * angle.sin();
            }
            let got = data.get(k).copied().unwrap_or([0.0, 0.0]);
            assert!((got[0] as f64 - re).abs() < 1e-3, "bin {k} real {} against {re}", got[0]);
            assert!((got[1] as f64 - im).abs() < 1e-3, "bin {k} imag {} against {im}", got[1]);
        }
    }

    #[test]
    fn the_pipeline_matches_an_independently_computed_reference() {
        // `scripts/ml/nnfp_reference.py` computes this pipeline from ARCHITECTURE.md's formulas
        // using numpy's FFT and f64 throughout, sharing no code with this module. What is left
        // in the residual is this module's f32 transform against that f64 one.
        let samples = NOW_PLAYING.window_samples + NOW_PLAYING.hop_samples * (40 - 1);
        let pcm = synthetic(samples);
        let mut frontend = Frontend::new(NOW_PLAYING).expect("the shipped configuration builds");
        let mut frames = Vec::new();
        assert_eq!(frontend.process(&pcm, &mut frames), 40);

        let channels = NOW_PLAYING.channels;
        let mut worst = 0.0f32;
        for (probe, frame) in PROBE_FRAMES.iter().enumerate() {
            for channel in 0..channels {
                let got = frames.get(frame * channels + channel).copied().unwrap_or(0.0);
                let want = PROBE_LOG_MEL.get(probe * channels + channel).copied().unwrap_or(0.0);
                worst = worst.max((got - want).abs());
            }
        }
        // Measured rather than guessed: the worst deviation is 9.5e-7, which is one ulp of f32
        // at this magnitude, so the f32 transform here is as close to the f64 reference as the
        // type allows. Tighten this if it ever comes in lower — a drift is a change to the
        // features every network downstream was trained on.
        assert!(worst < 2e-6, "worst log-mel deviation is {worst}");
    }

    #[test]
    fn the_reference_signal_exercises_the_range_rather_than_the_clamps() {
        // A comparison of two saturated clamps passes while measuring nothing, which is what
        // the first version of this fixture did.
        let low = PROBE_LOG_MEL.iter().copied().fold(f32::INFINITY, f32::min);
        let high = PROBE_LOG_MEL.iter().copied().fold(f32::NEG_INFINITY, f32::max);
        assert!(low > 0.0, "the reference floors at zero, so it is not measuring the log");
        assert!(high < NOW_PLAYING.ceiling, "the reference saturates at the ceiling");
        assert!(high - low > 3.0, "the reference spans only {} nats", high - low);
    }

    #[test]
    fn the_framer_waits_for_a_whole_window_then_emits_one_frame_per_hop() {
        let mut frontend = Frontend::new(NOW_PLAYING).expect("the shipped configuration builds");
        let mut frames = Vec::new();
        // 400 samples arriving 160 at a time: nothing until the third push, then one each.
        let hop = vec![0i16; NOW_PLAYING.hop_samples];
        assert_eq!(frontend.process(&hop, &mut frames), 0);
        assert_eq!(frontend.process(&hop, &mut frames), 0);
        assert_eq!(frontend.process(&hop, &mut frames), 1);
        assert_eq!(frontend.process(&hop, &mut frames), 1);
        assert_eq!(frames.len(), 2 * NOW_PLAYING.channels);
    }

    #[test]
    fn silence_floors_at_zero_rather_than_going_negative() {
        // `FilterBankLog` floors at an argument of one. Without the clamp this would be
        // negative infinity, and every convolution downstream would produce NaN.
        let mut frontend = Frontend::new(NOW_PLAYING).expect("the shipped configuration builds");
        let mut frames = Vec::new();
        assert_eq!(frontend.process(&vec![0i16; 4000], &mut frames), 23);
        assert!(frames.iter().all(|v| *v == 0.0), "silence is not all zero");
    }

    #[test]
    fn framing_is_independent_of_how_the_caller_chunks_the_audio() {
        // The pending buffer is the only state, so a caller reading 1024-sample blocks off a
        // microphone must get exactly what one whole-buffer call gives.
        let pcm = synthetic(6640);
        let mut whole = Frontend::new(NOW_PLAYING).expect("builds");
        let mut one = Vec::new();
        let _ = whole.process(&pcm, &mut one);

        let mut split = Frontend::new(NOW_PLAYING).expect("builds");
        let mut many = Vec::new();
        for chunk in pcm.chunks(1024) {
            let _ = split.process(chunk, &mut many);
        }
        assert_eq!(one, many);
    }

    #[test]
    fn resetting_discards_the_partial_window() {
        let mut frontend = Frontend::new(NOW_PLAYING).expect("builds");
        let mut frames = Vec::new();
        assert_eq!(frontend.process(&vec![1i16; 300], &mut frames), 0);
        frontend.reset();
        // Without the reset the leftover 300 would complete a window after only 100 more.
        assert_eq!(frontend.process(&vec![1i16; 300], &mut frames), 0);
        assert_eq!(frontend.process(&vec![1i16; 100], &mut frames), 1);
    }

    #[test]
    fn a_configuration_this_model_never_uses_still_builds() {
        // The point of taking a `Config` rather than hardcoding the constants. A pitch tracker
        // wants a longer window, a finer transform and a band that reaches the top octave.
        let tuner = Config {
            sample_rate: 44_100,
            window_samples: 2048,
            hop_samples: 512,
            fft_size: 4096,
            channels: 64,
            lower_band_limit: 50.0,
            upper_band_limit: 8000.0,
            ceiling: f32::INFINITY,
            ..NOW_PLAYING
        };
        let bank = mel_breakpoints(&tuner).expect("a 64-channel bank at 44.1 kHz");
        assert_eq!(bank.widths.len(), 65);
        assert_eq!(bank.widths.iter().sum::<usize>(), bank.weights.len());
        for (rising, falling) in bank.weights.iter().zip(bank.unweights.iter()) {
            // 4095 rather than 4096 whenever the weight is not exactly representable, which is
            // every one of the 119 under `NOW_PLAYING` but not at every band limit.
            assert!((4095..=4096).contains(&(rising + falling)));
        }
        let mut frontend = Frontend::new(tuner).expect("the tuner configuration builds");
        let mut frames = Vec::new();
        assert_eq!(frontend.process(&synthetic(2048 + 512), &mut frames), 2);
        assert_eq!(frames.len(), 2 * 64);
        assert_eq!(frontend.spectrum().len(), 4096 / 2 + 1);
    }

    #[test]
    fn a_window_the_transform_cannot_hold_is_refused() {
        let long = Config { window_samples: 1024, fft_size: 512, ..NOW_PLAYING };
        assert!(Frontend::new(long).is_err());
        let ragged = Config { fft_size: 500, ..NOW_PLAYING };
        assert!(Frontend::new(ragged).is_err());
    }

}
