//! VITS's stochastic duration predictor, on the CPU.
//!
//! # What it decides
//!
//! How many output frames each phoneme lasts. Everything downstream depends on it: the
//! alignment that expands the encoder's per-phoneme prior to per-frame, and through that the
//! length of the audio. It is the only part of speaking that is *sampled* rather than
//! computed — a different noise draw gives a different rhythm for the same words.
//!
//! # Why it is here and not a plan
//!
//! 555,159 of the voice's parameters, which is 4%, and it produces one number per phoneme.
//! Its two distinctive pieces are a bin search and a quadratic solve per position (see
//! [`super::spline`]) — data-dependent branching over tens of values, which is what a GPU is
//! worst at. The encoder, the flow and the vocoder are plans; this is a loop.
//!
//! # Shape of it
//!
//! `pre`, a three-layer depthwise-separable convolution stack and `proj` turn the encoder's
//! hidden state into a conditioning signal. Then eight flows run **in reverse** over two
//! channels of noise:
//!
//! ```text
//! flip, spline, flip, spline, flip, spline, flip, elementwise affine
//! ```
//!
//! and the first of the two output channels is the log-duration. The order is the export's
//! own: VITS reverses its flow list at inference and drops the second-to-last entry, which is
//! why there are three spline couplings in the graph and four in the checkpoint.

use crate::preprocess::f16_to_f32;
use crate::weights::Weights;

use super::spline::{self, Spline};

/// Channels the conditioning stack works in, VITS's `filter_channels`.
pub const CHANNELS: usize = 192;

/// Bins in each spline coupling. The projection emits `3 * BINS - 1` channels: widths,
/// heights, and the interior slopes, whose two ends are pinned by the linear tails.
pub const BINS: usize = 10;

/// Half-width of the interval the splines act on, VITS's `tail_bound`.
const TAIL_BOUND: f64 = 5.0;

/// Layers in each depthwise-separable stack.
const DEPTH: usize = 3;

/// Spline coupling layers, after the export's reversal drops one.
const COUPLINGS: usize = 3;

/// The epsilon in all 24 of the layer norms.
const EPSILON: f32 = 1e-5;

/// Tensors the predictor reads: 56 weighted nodes, each a pair.
///
/// `pre`, twelve for the conditioning stack, `proj`, then 28 per spline coupling.
pub const TENSORS: usize = 112;

/// The shift of the final elementwise affine, `dp.flows.0.m`.
///
/// Four scalars, so they are here rather than in the `.maml` — the same argument as
/// [`crate::nets::ppocr_det`]'s affines. Read from `en_GB-alan-low`.
const AFFINE_SHIFT: [f32; 2] = [0.55072945, 0.00070224184];

/// `exp(-logs)` of that affine, which the export has already folded into a constant. So the
/// reverse direction is a subtract and a multiply, with no exponential at runtime.
const AFFINE_INVERSE_SCALE: [f32; 2] = [1.9522907, 1.1635983];

/// One tensor of the `.maml`, decoded to `f32`.
fn tensor(weights: &Weights, index: usize, dims: &[u32]) -> Result<Vec<f32>, String> {
    let found = weights.shaped(index, dims)?;
    let bytes = weights.data();
    let start = found.offset as usize;
    let end = start + (found.len as usize) * 2;
    let raw = bytes
        .get(start..end)
        .ok_or_else(|| format!("tensor {index} runs past the data section"))?;
    Ok(raw.chunks_exact(2).map(|c| f16_to_f32(u16::from_le_bytes([c[0], c[1]]))).collect())
}

/// `erf`, to about 1.5e-7 — Abramowitz and Stegun 7.1.26.
///
/// Rust has no `erf`, and the export's GELU is the exact one rather than the tanh
/// approximation, so approximating the *activation* would be a different function. This
/// approximates `erf` itself instead, well below fp16's resolution.
pub(crate) fn erf(x: f32) -> f32 {
    const A: [f32; 5] = [0.254_829_6, -0.284_496_74, 1.421_413_7, -1.453_152, 1.061_405_4];
    const P: f32 = 0.327_591_1;
    let sign = if x < 0.0 { -1.0 } else { 1.0 };
    let x = x.abs();
    let t = 1.0 / (1.0 + P * x);
    let mut poly = 0.0;
    for coefficient in A.iter().rev() {
        poly = (poly + coefficient) * t;
    }
    sign * (1.0 - poly * (-x * x).exp())
}

/// The exact GELU, `0.5 x (1 + erf(x / sqrt(2)))`.
fn gelu(x: f32) -> f32 {
    0.5 * x * (1.0 + erf(x * std::f32::consts::FRAC_1_SQRT_2))
}

/// A `[channels, positions]` buffer, which is every intermediate here.
type Plane = Vec<f32>;

/// A depthwise convolution along the sequence, one `kernel`-wide filter per channel, padded
/// to hold the length.
fn depthwise(x: &Plane, positions: usize, weight: &[f32], bias: &[f32], dilation: usize) -> Plane {
    let kernel = weight.len() / CHANNELS;
    let pad = (kernel - 1) * dilation / 2;
    let mut out = vec![0.0f32; CHANNELS * positions];
    for channel in 0..CHANNELS {
        for position in 0..positions {
            let mut total = bias[channel];
            for tap in 0..kernel {
                let at = (position + tap * dilation) as isize - pad as isize;
                if at < 0 || at >= positions as isize {
                    continue;
                }
                total += weight[channel * kernel + tap] * x[channel * positions + at as usize];
            }
            out[channel * positions + position] = total;
        }
    }
    out
}

/// A 1x1 convolution, which is a matrix multiply per position.
fn pointwise(
    x: &Plane,
    inputs: usize,
    positions: usize,
    weight: &[f32],
    bias: &[f32],
) -> Plane {
    let outputs = bias.len();
    let mut out = vec![0.0f32; outputs * positions];
    for output in 0..outputs {
        for position in 0..positions {
            let mut total = bias[output];
            for input in 0..inputs {
                total += weight[output * inputs + input] * x[input * positions + position];
            }
            out[output * positions + position] = total;
        }
    }
    out
}

/// Normalise over channels, one position at a time.
fn layer_norm(x: &mut Plane, channels: usize, positions: usize, gamma: &[f32], beta: &[f32]) {
    for position in 0..positions {
        let mut mean = 0.0f64;
        for channel in 0..channels {
            mean += x[channel * positions + position] as f64;
        }
        mean /= channels as f64;
        let mut variance = 0.0f64;
        for channel in 0..channels {
            let centred = x[channel * positions + position] as f64 - mean;
            variance += centred * centred;
        }
        variance /= channels as f64;
        let inverse = 1.0 / (variance + EPSILON as f64).sqrt();
        for channel in 0..channels {
            let centred = x[channel * positions + position] as f64 - mean;
            x[channel * positions + position] =
                (centred * inverse) as f32 * gamma[channel] + beta[channel];
        }
    }
}

/// Three depthwise-separable residual layers, dilated 1, 3 and 9.
///
/// `base` is where this stack's 24 tensors start. Each layer is a separable convolution, a
/// norm, a GELU, a 1x1, a norm, a GELU and a residual add — the order VITS's `DDSConv` uses.
fn separable_stack(
    weights: &Weights,
    base: usize,
    x: &mut Plane,
    positions: usize,
) -> Result<(), String> {
    let channels = CHANNELS as u32;
    for layer in 0..DEPTH {
        let at = base + layer * 8;
        let dilation = 3usize.pow(layer as u32);
        let sep_w = tensor(weights, at, &[channels, 1, 1, 3])?;
        let sep_b = tensor(weights, at + 1, &[channels])?;
        let n1_g = tensor(weights, at + 2, &[channels])?;
        let n1_b = tensor(weights, at + 3, &[channels])?;
        let pw_w = tensor(weights, at + 4, &[channels, channels, 1, 1])?;
        let pw_b = tensor(weights, at + 5, &[channels])?;
        let n2_g = tensor(weights, at + 6, &[channels])?;
        let n2_b = tensor(weights, at + 7, &[channels])?;

        let mut y = depthwise(x, positions, &sep_w, &sep_b, dilation);
        layer_norm(&mut y, CHANNELS, positions, &n1_g, &n1_b);
        for value in &mut y {
            *value = gelu(*value);
        }
        let mut y = pointwise(&y, CHANNELS, positions, &pw_w, &pw_b);
        layer_norm(&mut y, CHANNELS, positions, &n2_g, &n2_b);
        for value in &mut y {
            *value = gelu(*value);
        }
        for (slot, added) in x.iter_mut().zip(&y) {
            *slot += added;
        }
    }
    Ok(())
}

/// The log-duration of each phoneme.
///
/// `hidden` is the encoder's pre-projection output, `[192, phonemes]`. `noise` is
/// `[2, phonemes]` of standard normal already scaled by the voice's `noise_w` — the caller
/// supplies it rather than this drawing it, so a test can pin the result and two runs of the
/// same sentence can deliberately differ.
pub fn log_durations(
    weights: &Weights,
    hidden: &[f32],
    phonemes: usize,
    noise: &[f32],
) -> Result<Vec<f32>, String> {
    if phonemes == 0 {
        return Err("a duration pass over no phonemes".into());
    }
    if hidden.len() != CHANNELS * phonemes {
        return Err(format!("{} hidden values for {phonemes} phonemes", hidden.len()));
    }
    if noise.len() != 2 * phonemes {
        return Err(format!("{} noise values for {phonemes} phonemes", noise.len()));
    }
    let channels = CHANNELS as u32;

    // The conditioning: pre, the separable stack, proj.
    let pre_w = tensor(weights, 0, &[channels, channels, 1, 1])?;
    let pre_b = tensor(weights, 1, &[channels])?;
    let mut conditioning = pointwise(&hidden.to_vec(), CHANNELS, phonemes, &pre_w, &pre_b);
    separable_stack(weights, 2, &mut conditioning, phonemes)?;
    let proj_w = tensor(weights, 26, &[channels, channels, 1, 1])?;
    let proj_b = tensor(weights, 27, &[channels])?;
    let conditioning = pointwise(&conditioning, CHANNELS, phonemes, &proj_w, &proj_b);

    // Two channels of noise through the reversed flows.
    let mut z = noise.to_vec();
    for coupling in 0..COUPLINGS {
        flip(&mut z, phonemes);
        let base = 28 + coupling * 28;
        couple(weights, base, &mut z, phonemes, &conditioning)?;
    }
    flip(&mut z, phonemes);
    // The elementwise affine, reversed: subtract the shift and scale by `exp(-logs)`.
    for channel in 0..2 {
        for position in 0..phonemes {
            let at = channel * phonemes + position;
            z[at] = (z[at] - AFFINE_SHIFT[channel]) * AFFINE_INVERSE_SCALE[channel];
        }
    }

    // The first channel is the log-duration; the second is the flow's discarded partner.
    Ok(z[0..phonemes].to_vec())
}

/// Swap the two channels, which is `Flip` over a two-channel tensor.
fn flip(z: &mut [f32], positions: usize) {
    for position in 0..positions {
        z.swap(position, positions + position);
    }
}

/// One spline coupling, reversed: the first channel conditions a transform of the second.
fn couple(
    weights: &Weights,
    base: usize,
    z: &mut [f32],
    positions: usize,
    conditioning: &Plane,
) -> Result<(), String> {
    let channels = CHANNELS as u32;
    let params = (3 * BINS - 1) as u32;

    // Widen the conditioning channel to the stack's width, add the encoder's signal, and
    // project down to the spline's parameters.
    let pre_w = tensor(weights, base, &[channels, 1, 1, 1])?;
    let pre_b = tensor(weights, base + 1, &[channels])?;
    let first = z[0..positions].to_vec();
    let mut h = pointwise(&first, 1, positions, &pre_w, &pre_b);
    for (slot, added) in h.iter_mut().zip(conditioning) {
        *slot += added;
    }
    separable_stack(weights, base + 2, &mut h, positions)?;
    let proj_w = tensor(weights, base + 26, &[params, channels, 1, 1])?;
    let proj_b = tensor(weights, base + 27, &[params])?;
    let predicted = pointwise(&h, CHANNELS, positions, &proj_w, &proj_b);

    // The widths and heights are scaled down by the stack's width; the slopes are not. That
    // asymmetry is VITS's, and dropping it would make every bin the same size.
    let scale = 1.0 / (CHANNELS as f32).sqrt();
    let mut widths = [0.0f32; BINS];
    let mut heights = [0.0f32; BINS];
    let mut slopes = [0.0f32; BINS - 1];
    for position in 0..positions {
        for bin in 0..BINS {
            widths[bin] = predicted[bin * positions + position] * scale;
            heights[bin] = predicted[(BINS + bin) * positions + position] * scale;
        }
        for knot in 0..BINS - 1 {
            slopes[knot] = predicted[(2 * BINS + knot) * positions + position];
        }
        let at = positions + position;
        z[at] = spline::inverse(
            z[at],
            &Spline {
                widths: &widths,
                heights: &heights,
                derivatives: &slopes,
                bound: TAIL_BOUND,
            },
        );
    }
    Ok(())
}

/// Whole frames per phoneme, from log-durations.
///
/// `length_scale` is the voice's speaking-rate control, 1.0 for its natural pace. Rounding up
/// and flooring at one is the export's own `ceil` and `clip`: a phoneme that rounded to zero
/// frames would vanish from the audio rather than be said quickly.
pub fn frames(log_durations: &[f32], length_scale: f32) -> Vec<u32> {
    log_durations
        .iter()
        .map(|&logw| (logw.exp() * length_scale).ceil().max(1.0) as u32)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn erf_matches_its_known_values() {
        // The approximation is A&S 7.1.26, good to 1.5e-7. Pinned at values with published
        // digits, because a wrong coefficient would still look like a sigmoid.
        for (x, want) in [
            (0.0f32, 0.0f32),
            (0.5, 0.5204999),
            (1.0, 0.8427008),
            (2.0, 0.9953223),
            (-1.0, -0.8427008),
            (3.0, 0.9999779),
        ] {
            assert!((erf(x) - want).abs() < 2e-6, "erf({x}) = {} not {want}", erf(x));
        }
    }

    #[test]
    fn gelu_matches_the_exact_erf_form() {
        // The tanh approximation differs by at most 4.7e-4, which is below fp16's 2.0e-3 step
        // there — so this is about matching the export rather than about the approximation being
        // unusable. See `nets::Act::Gelu`, which carries the same note for the shader.
        assert!((gelu(0.0) - 0.0).abs() < 1e-6);
        assert!((gelu(1.0) - 0.8413447).abs() < 1e-5);
        assert!((gelu(-1.0) + 0.15865526).abs() < 1e-5);
        // Large inputs pass through, large negatives vanish.
        assert!((gelu(6.0) - 6.0).abs() < 1e-4);
        assert!(gelu(-6.0).abs() < 1e-6);
    }

    #[test]
    fn flipping_two_channels_swaps_them() {
        let mut z = vec![1.0, 2.0, 3.0, 10.0, 20.0, 30.0];
        flip(&mut z, 3);
        assert_eq!(z, vec![10.0, 20.0, 30.0, 1.0, 2.0, 3.0]);
        flip(&mut z, 3);
        assert_eq!(z, vec![1.0, 2.0, 3.0, 10.0, 20.0, 30.0]);
    }

    #[test]
    fn frames_round_up_and_never_reach_zero() {
        // A phoneme of zero frames would disappear from the audio.
        let logw = vec![-10.0f32, 0.0, 0.7, 2.0];
        assert_eq!(frames(&logw, 1.0), vec![1, 1, 3, 8]);
        // The rate control scales before rounding, so a slow voice lengthens everything.
        assert_eq!(frames(&logw, 2.0), vec![1, 2, 5, 15]);
    }

    #[test]
    fn a_pointwise_convolution_is_a_matrix_multiply_per_position() {
        // Two inputs, three outputs, two positions. Written out by hand so the indexing of
        // `[out, in]` against `[in, position]` is pinned — transposing it would still produce
        // the right *shape*.
        let x = vec![1.0, 2.0, 10.0, 20.0];
        let weight = vec![1.0, 0.0, 0.0, 1.0, 1.0, 1.0];
        let bias = vec![0.0, 100.0, -1.0];
        let got = pointwise(&x, 2, 2, &weight, &bias);
        assert_eq!(got, vec![1.0, 2.0, 110.0, 120.0, 10.0, 21.0]);
    }

    #[test]
    fn a_zero_length_utterance_is_refused() {
        let weights = crate::weights::Weights::parse(&[], 0);
        assert!(weights.is_err());
    }
}
