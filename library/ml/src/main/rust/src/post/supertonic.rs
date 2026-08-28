//! The host half of Supertonic 3's sampler: the timestep embedding, the rotary angles, and the
//! guidance combination that turns two forward passes into one Euler step.
//!
//! # Why any of this is on the host
//!
//! [`crate::nets::supertonic_sampler`] is a plan of convolutions, attentions and layer norms.
//! Four things in the export are none of those, and all four are cheap:
//!
//! * The **timestep conditioning** is a sinusoidal embedding of `current_step / total_step`, a
//!   two-layer MLP with a Mish between, and four `Linear`s to 512 numbers. 2,048 values from two
//!   scalars, against a net that spends 64M multiply-adds per latent frame — so `Sin`, `Cos`,
//!   `Softplus` and `Tanh` shaders would exist for nothing.
//! * The **rotary angles** are `(position / length) * theta`. Nothing learned, and they change
//!   with the sequence lengths rather than with the weights.
//! * The **unconditional branch's inputs** are two learned tokens, one broadcast over the text
//!   and one standing in for the voice.
//! * The **guidance combination and the Euler step**, four multiply-adds per latent value.
//!
//! Each of those tensors is declared to [`crate::nets::Builder::host_tensor`] so a genuinely
//! unread weight is still an error.
//!
//! # The step is two passes
//!
//! The export tiles its batch to two and runs the whole network twice, once conditioned on the
//! real text and voice and once on the unconditional tokens, then takes
//! `4 * conditional - 3 * unconditional`. This runtime has no batch axis, so [`step`] takes the
//! two velocities the caller already ran. That is a genuine doubling of the sampler's cost.

use crate::nets::supertonic_sampler as net;
use crate::preprocess::f16_to_f32;
use crate::weights::Weights;

/// The frequency multiplier the export applies before the sinusoids: `t * 1000 * frequency`.
const TIME_SCALE: f32 = 1000.0;

/// One tensor of the `.maml` as `f32`, in the file's order.
fn tensor(weights: &Weights, index: usize, dims: &[u32]) -> Result<Vec<f32>, String> {
    let found = weights.shaped(index, dims)?;
    let bytes = weights.data();
    let start = found.offset as usize;
    let end = start + (found.len as usize) * 2;
    let raw = bytes
        .get(start..end)
        .ok_or_else(|| format!("tensor {index} runs past the file"))?;
    Ok(raw
        .chunks_exact(2)
        .map(|c| f16_to_f32(u16::from_le_bytes([c[0], c[1]])))
        .collect())
}

/// `out[o] = sum_i weight[o][i] * x[i] + bias[o]`, over a row-major `[out, in]` weight.
fn linear(weight: &[f32], bias: &[f32], x: &[f32]) -> Vec<f32> {
    let inputs = x.len();
    bias.iter()
        .enumerate()
        .map(|(o, &b)| {
            let row = &weight[o * inputs..(o + 1) * inputs];
            row.iter().zip(x).map(|(&w, &v)| w * v).sum::<f32>() + b
        })
        .collect()
}

/// `x * tanh(softplus(x))`, the export's `mlp.1`.
///
/// `softplus` is written as `ln(1 + e^x)` only for negative `x`: for large positive `x` the
/// exponential overflows while the function is within an fp32 epsilon of `x` itself.
fn mish(x: f32) -> f32 {
    let softplus = if x > 20.0 { x } else { x.exp().ln_1p() };
    x * softplus.tanh()
}

/// The four per-block timestep shifts, `[4 * 512]`, for step `current` of `total`.
///
/// The plan reads them as one `[2048, 1, 1]` input and [`crate::nets::Builder::slice_channels`]
/// hands each main block its own 512.
pub fn time_shifts(weights: &Weights, current: u32, total: u32) -> Result<Vec<f32>, String> {
    if total == 0 {
        return Err("a sampler step out of no steps".into());
    }
    let frequencies = tensor(weights, net::HOST_FREQUENCIES, &[net::FREQUENCIES])?;
    let progress = current as f32 / total as f32;

    // Sines then cosines, which is the order of the export's `Concat`.
    let angles: Vec<f32> = frequencies.iter().map(|&f| progress * TIME_SCALE * f).collect();
    let embedding: Vec<f32> = angles
        .iter()
        .map(|a| a.sin())
        .chain(angles.iter().map(|a| a.cos()))
        .collect();

    let in_weight = tensor(weights, net::HOST_MLP_IN, &[net::TIME_INNER, net::TIME])?;
    let in_bias = tensor(weights, net::HOST_MLP_IN + 1, &[net::TIME_INNER])?;
    let hidden: Vec<f32> = linear(&in_weight, &in_bias, &embedding).into_iter().map(mish).collect();

    let out_weight = tensor(weights, net::HOST_MLP_OUT, &[net::TIME, net::TIME_INNER])?;
    let out_bias = tensor(weights, net::HOST_MLP_OUT + 1, &[net::TIME])?;
    let time = linear(&out_weight, &out_bias, &hidden);

    let mut shifts = Vec::with_capacity(net::MAIN_BLOCKS * net::CHANNELS as usize);
    for block in 0..net::MAIN_BLOCKS {
        let index = net::HOST_TIME_LINEARS + block * 2;
        let weight = tensor(weights, index, &[net::CHANNELS, net::TIME])?;
        let bias = tensor(weights, index + 1, &[net::CHANNELS])?;
        shifts.extend(linear(&weight, &bias, &time));
    }
    Ok(shifts)
}

/// The rotary angle table for a sequence of `positions`, as the `[64, 1, positions]` the plan
/// wants: 32 channels of cosine then 32 of sine.
///
/// The angle is `(position / positions) * theta` — normalised by the sequence's **own** length,
/// which is what lets a latent frame and a text character at the same fraction of the way through
/// meet at the same angle. It also means the query table and the key table are different tensors
/// even though they share `theta`.
pub fn rotary_angles(weights: &Weights, positions: u32) -> Result<Vec<f32>, String> {
    if positions == 0 {
        return Err("a rotary table over no positions".into());
    }
    let theta = tensor(weights, net::HOST_THETA, &[net::FREQUENCIES])?;
    let width = positions as usize;
    let mut table = vec![0.0f32; 2 * net::FREQUENCIES as usize * width];
    for (frequency, &turn) in theta.iter().enumerate() {
        for position in 0..width {
            let angle = position as f32 / positions as f32 * turn;
            table[frequency * width + position] = angle.cos();
            table[(net::FREQUENCIES as usize + frequency) * width + position] = angle.sin();
        }
    }
    Ok(table)
}

/// The unconditional branch's text input: `text_special_token` at every one of `chars` positions.
pub fn unconditional_text(weights: &Weights, chars: u32) -> Result<Vec<f32>, String> {
    if chars == 0 {
        return Err("an unconditional text of no characters".into());
    }
    let token = tensor(weights, net::HOST_TEXT_TOKEN, &[net::TEXT])?;
    let mut out = Vec::with_capacity(token.len() * chars as usize);
    for value in token {
        out.extend(std::iter::repeat_n(value, chars as usize));
    }
    Ok(out)
}

/// The unconditional branch's style values, `[256, 1, 50]` and already transposed in the file.
pub fn unconditional_style(weights: &Weights) -> Result<Vec<f32>, String> {
    tensor(weights, net::HOST_STYLE_TOKEN, &[net::STYLE, net::STYLE_TOKENS])
}

/// The folded style keys for one guidance branch, `[4 * 256, 1, 50]`.
///
/// `tanh(W_key . style_key + b_key)`, all constant, so the converter evaluates it. The four style
/// attentions share one `style_key` but each has its own `W_key`, so this is four 256-channel
/// blocks stacked; the plan slices its own out. The two branches have different `style_key`s,
/// which is the only structural difference between them — everything else is an input, so one
/// plan serves both.
pub fn style_keys(weights: &Weights, conditional: bool) -> Result<Vec<f32>, String> {
    let index = if conditional {
        net::HOST_KEYS_CONDITIONAL
    } else {
        net::HOST_KEYS_UNCONDITIONAL
    };
    tensor(
        weights,
        index,
        &[net::STYLE * net::MAIN_BLOCKS as u32, net::STYLE_TOKENS],
    )
}

/// One Euler step: combine the two guidance branches and advance the latent.
///
/// `denoised = latent + (GUIDANCE * conditional - (GUIDANCE - 1) * unconditional) / total`, which
/// is what the export's last five nodes do once its batch is split back in two.
pub fn step(
    latent: &[f32],
    conditional: &[f32],
    unconditional: &[f32],
    total: u32,
) -> Result<Vec<f32>, String> {
    if total == 0 {
        return Err("a sampler step out of no steps".into());
    }
    if conditional.len() != latent.len() || unconditional.len() != latent.len() {
        return Err(format!(
            "a step over {} latent values against {} conditional and {} unconditional",
            latent.len(),
            conditional.len(),
            unconditional.len()
        ));
    }
    let scale = 1.0 / total as f32;
    Ok(latent
        .iter()
        .zip(conditional)
        .zip(unconditional)
        .map(|((&x, &c), &u)| {
            let velocity = net::GUIDANCE * c - (net::GUIDANCE - 1.0) * u;
            x + velocity * scale
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mish_matches_its_definition_and_survives_a_large_input() {
        // `x * tanh(softplus(x))`. At 0 it is 0, at 1 it is 0.86509836, and at 100 it is 100 —
        // the last only because `softplus` is not computed as `ln(1 + e^100)`, which is inf.
        assert!((mish(0.0)).abs() < 1e-6);
        assert!((mish(1.0) - 0.865_098_4).abs() < 1e-5);
        assert!((mish(100.0) - 100.0).abs() < 1e-3);
        assert!(mish(100.0).is_finite());
        // And it is not ReLU: a small negative input is negative, not zero.
        assert!(mish(-1.0) < -0.3 && mish(-1.0) > -0.31);
    }

    #[test]
    fn a_linear_reads_its_weight_row_major() {
        // `[2, 3]` over three inputs. A column-major read would give (14, 32) here, which is
        // the same magnitude and the wrong answer.
        let weight = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
        let got = linear(&weight, &[10.0, 20.0], &[1.0, 1.0, 1.0]);
        assert_eq!(got, vec![16.0, 35.0]);
    }

    #[test]
    fn the_euler_step_is_guidance_four_over_the_step_count() {
        // `x + (4c - 3u) / total`. With c = u the guidance cancels to plain c, which is the
        // check that the two coefficients differ by exactly one.
        let got = step(&[0.0, 1.0], &[2.0, 2.0], &[2.0, 2.0], 4).expect("steps");
        assert_eq!(got, vec![0.5, 1.5]);
        // And with them apart, the conditional is extrapolated away from the unconditional.
        let got = step(&[0.0], &[1.0], &[0.0], 1).expect("steps");
        assert_eq!(got, vec![4.0]);
    }

    #[test]
    fn the_euler_step_refuses_mismatched_branches() {
        let error = step(&[0.0, 0.0], &[0.0], &[0.0, 0.0], 1).expect_err("a short branch");
        assert!(error.contains("conditional"), "{error}");
        let error = step(&[0.0], &[0.0], &[0.0], 0).expect_err("no steps");
        assert!(error.contains("no steps"), "{error}");
    }
}
