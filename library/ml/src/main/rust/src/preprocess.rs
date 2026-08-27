//! Bitmap to fp16 NCHW, on the CPU.
//!
//! # Why this is not a shader
//!
//! Both networks take at most 320x320, and both callers already downscale to a 512-pixel
//! long side before they get here, so this is a few hundred thousand bilinear taps —
//! well under a millisecond, and less than the cost of the extra upload, dispatch and
//! barrier a preprocessing shader would need. It also keeps the input path testable on
//! the host, which is where the sign of a normalisation or a transposed channel gets
//! caught.
//!
//! This replaces ncnn's `from_android_bitmap_resize` + `substract_mean_normalize`, which
//! lived inside the out-of-repo AAR and so was never reviewable.
//!
//! # The half-pixel convention
//!
//! Bilinear sampling here uses `src = (dst + 0.5) * scale - 0.5`, matching ONNX
//! `half_pixel` and PyTorch's `align_corners=False`. The resize shader uses the same
//! formula, so the input resize and the 38 in-graph resizes agree — which matters
//! because U^2-Netp's decoder repeatedly upsamples and adds.

use crate::nets::Shape;

/// The per-channel affine each network wants applied after scaling to `0..1`.
///
/// Both are `(value / 255 - mean) / std`; they differ only in whether the mean and std
/// are the identity.
#[derive(Clone, Copy, Debug)]
pub struct Normalise {
    /// Subtracted per channel, in RGB order.
    pub mean: [f32; 3],
    /// Divided per channel, in RGB order.
    pub std: [f32; 3],
}

/// Scale to `0..1` and nothing else.
///
/// What MediaPipe Selfie Segmentation wants: its processor sets `do_normalize: false`
/// with `rescale_factor: 1/255`.
pub const RESCALE_ONLY: Normalise = Normalise { mean: [0.0; 3], std: [1.0; 3] };

/// ImageNet statistics, which is what U^2-Netp was trained with and what the ncnn path
/// this replaces already used.
pub const IMAGENET: Normalise =
    Normalise { mean: [0.485, 0.456, 0.406], std: [0.229, 0.224, 0.225] };

/// Resize `pixels` to `shape` and write it as planar fp16.
///
/// `pixels` is `ARGB_8888` as `Bitmap.getPixels` produces it — `0xAARRGGBB` per entry,
/// row-major, `width` wide. Alpha is ignored: neither network has a fourth input
/// channel, and both callers hand over opaque camera or gallery frames.
///
/// The resize is a straight scale to `shape`, deliberately **not** the aspect-preserving
/// letterbox the U^2-Netp processor config describes. The ncnn path this replaces
/// stretched, so letterboxing would change existing behaviour in `:photos` rather than
/// preserve it.
pub fn to_planar_f16(
    pixels: &[i32],
    width: u32,
    height: u32,
    shape: Shape,
    norm: &Normalise,
    dst: &mut [u16],
) -> Result<(), String> {
    if shape.c != 3 {
        return Err(format!("preprocessing writes 3 channels, not {}", shape.c));
    }
    if width == 0 || height == 0 {
        return Err("a zero-sized bitmap".into());
    }
    let expected = (width as usize)
        .checked_mul(height as usize)
        .ok_or("bitmap dimensions overflow")?;
    if pixels.len() != expected {
        return Err(format!("{} pixels for a {width}x{height} bitmap", pixels.len()));
    }
    if dst.len() != shape.len() as usize {
        return Err(format!("{} output elements for {shape:?}", dst.len()));
    }

    let plane = (shape.h * shape.w) as usize;
    let x_scale = width as f32 / shape.w as f32;
    let y_scale = height as f32 / shape.h as f32;

    for out_y in 0..shape.h {
        let (y0, y1, wy) = sample(out_y, y_scale, height);
        for out_x in 0..shape.w {
            let (x0, x1, wx) = sample(out_x, x_scale, width);

            let p00 = pixel(pixels, width, x0, y0);
            let p10 = pixel(pixels, width, x1, y0);
            let p01 = pixel(pixels, width, x0, y1);
            let p11 = pixel(pixels, width, x1, y1);

            let at = (out_y * shape.w + out_x) as usize;
            for channel in 0..3usize {
                // Interpolating the 0..255 values and normalising once is both cheaper
                // and closer to the reference than normalising four taps first.
                let top = lerp(p00[channel], p10[channel], wx);
                let bottom = lerp(p01[channel], p11[channel], wx);
                let value = lerp_f32(top, bottom, wy) / 255.0;
                let normalised = (value - norm.mean[channel]) / norm.std[channel];
                let slot = dst
                    .get_mut(channel * plane + at)
                    .ok_or("preprocessing wrote past the end of its output")?;
                *slot = f32_to_f16(normalised);
            }
        }
    }
    Ok(())
}

/// The two source indices bracketing `out` and the weight of the second, under the
/// `half_pixel` convention. Coordinates outside the source clamp to the edge.
fn sample(out: u32, scale: f32, extent: u32) -> (u32, u32, f32) {
    let source = (out as f32 + 0.5) * scale - 0.5;
    let clamped = source.max(0.0);
    let low = clamped.floor();
    let weight = clamped - low;
    let low = (low as u32).min(extent - 1);
    let high = (low + 1).min(extent - 1);
    (low, high, weight)
}

fn pixel(pixels: &[i32], width: u32, x: u32, y: u32) -> [f32; 3] {
    let packed = pixels.get((y * width + x) as usize).copied().unwrap_or(0) as u32;
    [
        ((packed >> 16) & 0xff) as f32,
        ((packed >> 8) & 0xff) as f32,
        (packed & 0xff) as f32,
    ]
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

fn lerp_f32(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// fp32 to fp16, round-to-nearest-even, with subnormals.
///
/// Hand-written because Rust's `f16` is not stable on the toolchain this repo pins
/// (`rust-toolchain.toml`), and because a `half` dependency would be a whole crate for
/// two functions. Weights are converted by numpy in `scripts/ml/vkml_convert.py`; this
/// is only for the input, whose values sit in roughly `-2.2..2.2` after normalisation.
pub fn f32_to_f16(value: f32) -> u16 {
    let bits = value.to_bits();
    let sign = ((bits >> 16) & 0x8000) as u16;
    let exponent = ((bits >> 23) & 0xff) as i32;
    let mantissa = bits & 0x007f_ffff;

    if exponent == 0xff {
        // Infinity, or a NaN kept as a NaN rather than collapsed to infinity.
        return sign | 0x7c00 | if mantissa != 0 { 0x0200 } else { 0 };
    }
    let unbiased = exponent - 127;
    if unbiased > 15 {
        return sign | 0x7c00;
    }
    if unbiased < -24 {
        // Below half of the smallest subnormal, so it rounds to zero.
        return sign;
    }
    if unbiased < -14 {
        // Subnormal: shift the implicit leading 1 into the mantissa.
        let shift = (-unbiased - 14) as u32;
        let full = mantissa | 0x0080_0000;
        let shifted = full >> (13 + shift);
        let round = round_bit(full, 13 + shift);
        return sign | (shifted + round) as u16;
    }
    let half_exponent = ((unbiased + 15) as u32) << 10;
    let half_mantissa = mantissa >> 13;
    let round = round_bit(mantissa, 13);
    // A mantissa that rounds up past 0x3ff carries into the exponent, which the plain
    // addition handles because the fields are adjacent.
    sign | (half_exponent + half_mantissa + round) as u16
}

/// 1 when the bits being discarded round the kept value up, under round-to-nearest-even.
fn round_bit(bits: u32, shift: u32) -> u32 {
    if shift >= 32 {
        return 0;
    }
    let dropped = bits & ((1u32 << shift) - 1);
    let halfway = 1u32 << (shift - 1);
    if dropped > halfway || (dropped == halfway && (bits >> shift) & 1 == 1) {
        1
    } else {
        0
    }
}

/// fp16 to fp32, for reading a mask back off the device.
pub fn f16_to_f32(half: u16) -> f32 {
    let sign = ((half as u32) & 0x8000) << 16;
    let exponent = ((half >> 10) & 0x1f) as i32;
    let mantissa = ((half as u32) & 0x03ff) << 13;

    if exponent == 0 {
        if mantissa == 0 {
            return f32::from_bits(sign);
        }
        // Subnormal: renormalise by shifting until the leading bit clears the mantissa.
        let mut shifted = mantissa;
        let mut unbiased = -14;
        while shifted & 0x0080_0000 == 0 {
            shifted <<= 1;
            unbiased -= 1;
        }
        let biased = ((unbiased + 127) as u32) << 23;
        return f32::from_bits(sign | biased | (shifted & 0x007f_ffff));
    }
    if exponent == 0x1f {
        return f32::from_bits(sign | 0x7f80_0000 | mantissa);
    }
    let biased = ((exponent - 15 + 127) as u32) << 23;
    f32::from_bits(sign | biased | mantissa)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argb(r: u8, g: u8, b: u8) -> i32 {
        (0xffu32 << 24 | (r as u32) << 16 | (g as u32) << 8 | b as u32) as i32
    }

    #[test]
    fn a_solid_colour_survives_the_rescale_unchanged() {
        let pixels = vec![argb(255, 128, 0); 4];
        let shape = Shape::new(3, 2, 2);
        let mut out = vec![0u16; shape.len() as usize];
        to_planar_f16(&pixels, 2, 2, shape, &RESCALE_ONLY, &mut out).expect("preprocesses");
        // Planar, so the first four entries are all red, the next four all green.
        for i in 0..4 {
            assert!((f16_to_f32(out[i]) - 1.0).abs() < 1e-3, "red {i}");
            assert!((f16_to_f32(out[4 + i]) - 128.0 / 255.0).abs() < 1e-3, "green {i}");
            assert_eq!(f16_to_f32(out[8 + i]), 0.0, "blue {i}");
        }
    }

    #[test]
    fn imagenet_normalisation_matches_a_hand_computation() {
        let pixels = vec![argb(0, 255, 128)];
        let shape = Shape::new(3, 1, 1);
        let mut out = vec![0u16; 3];
        to_planar_f16(&pixels, 1, 1, shape, &IMAGENET, &mut out).expect("preprocesses");
        let expected = [
            (0.0 - 0.485) / 0.229,
            (1.0 - 0.456) / 0.224,
            (128.0 / 255.0 - 0.406) / 0.225,
        ];
        for (i, want) in expected.iter().enumerate() {
            let got = f16_to_f32(out[i]);
            assert!((got - want).abs() < 2e-3, "channel {i}: {got} vs {want}");
        }
    }

    #[test]
    fn channels_are_planar_and_in_rgb_order() {
        // A transposed or BGR input is the single most likely preprocessing bug and
        // produces a plausible-looking mask, so it is pinned with distinct values.
        let pixels = vec![argb(10, 20, 30)];
        let shape = Shape::new(3, 1, 1);
        let mut out = vec![0u16; 3];
        to_planar_f16(&pixels, 1, 1, shape, &RESCALE_ONLY, &mut out).expect("preprocesses");
        let got: Vec<f32> = out.iter().map(|&h| f16_to_f32(h) * 255.0).collect();
        assert!((got[0] - 10.0).abs() < 0.5, "{got:?}");
        assert!((got[1] - 20.0).abs() < 0.5, "{got:?}");
        assert!((got[2] - 30.0).abs() < 0.5, "{got:?}");
    }

    #[test]
    fn a_two_pixel_upscale_interpolates_at_half_pixel_centres() {
        // Source 0 and 255 across two columns, upscaled to four. With half-pixel
        // centres the samples land at source x = -0.25, 0.25, 0.75, 1.25, which clamp
        // and interpolate to 0, 63.75, 191.25, 255.
        let pixels = vec![argb(0, 0, 0), argb(255, 255, 255)];
        let shape = Shape::new(3, 1, 4);
        let mut out = vec![0u16; shape.len() as usize];
        to_planar_f16(&pixels, 2, 1, shape, &RESCALE_ONLY, &mut out).expect("preprocesses");
        let got: Vec<f32> = (0..4).map(|i| f16_to_f32(out[i]) * 255.0).collect();
        let want = [0.0, 63.75, 191.25, 255.0];
        for (i, w) in want.iter().enumerate() {
            assert!((got[i] - w).abs() < 0.6, "column {i}: {got:?} vs {want:?}");
        }
    }

    #[test]
    fn a_downscale_averages_rather_than_picking_a_corner() {
        // 2x2 of 0, 255, 255, 0 down to 1x1. Half-pixel puts the sample at the centre,
        // so all four taps weigh equally and the answer is the mean.
        let pixels = vec![argb(0, 0, 0), argb(255, 255, 255), argb(255, 255, 255), argb(0, 0, 0)];
        let shape = Shape::new(3, 1, 1);
        let mut out = vec![0u16; 3];
        to_planar_f16(&pixels, 2, 2, shape, &RESCALE_ONLY, &mut out).expect("preprocesses");
        let got = f16_to_f32(out[0]) * 255.0;
        assert!((got - 127.5).abs() < 0.6, "{got}");
    }

    #[test]
    fn half_conversion_round_trips_the_values_that_matter() {
        for value in [0.0f32, 1.0, -1.0, 0.5, -0.5, 2.2, -2.2, 1.0 / 255.0, 65504.0] {
            let back = f16_to_f32(f32_to_f16(value));
            let tolerance = value.abs() * 1e-3 + 1e-7;
            assert!((back - value).abs() <= tolerance, "{value} became {back}");
        }
    }

    #[test]
    fn half_conversion_rounds_to_nearest_even() {
        // 1 + 2^-11 sits exactly halfway between 1.0 and the next fp16, so it must go
        // to the even one, which is 1.0. Truncation would also give 1.0, so the
        // companion case below is what distinguishes them.
        assert_eq!(f32_to_f16(1.0 + 2f32.powi(-11)), f32_to_f16(1.0));
        // 1 + 3 * 2^-11 is halfway between the first and second fp16 above 1.0, and the
        // even neighbour is the second. Truncation would give the first.
        assert_eq!(f16_to_f32(f32_to_f16(1.0 + 3.0 * 2f32.powi(-11))), 1.0 + 2f32.powi(-9));
    }

    #[test]
    fn half_conversion_saturates_and_keeps_nan() {
        assert_eq!(f16_to_f32(f32_to_f16(1e30)), f32::INFINITY);
        assert_eq!(f16_to_f32(f32_to_f16(-1e30)), f32::NEG_INFINITY);
        assert!(f16_to_f32(f32_to_f16(f32::NAN)).is_nan());
        // Underflow keeps its sign, so a mask never gains a positive value from one.
        assert!(f16_to_f32(f32_to_f16(-1e-30)).is_sign_negative());
    }

    #[test]
    fn a_mismatched_output_length_is_refused() {
        let pixels = vec![argb(0, 0, 0)];
        let mut out = vec![0u16; 2];
        let error = to_planar_f16(&pixels, 1, 1, Shape::new(3, 1, 1), &RESCALE_ONLY, &mut out)
            .expect_err("wrong length");
        assert!(error.contains("2 output elements"), "{error}");
    }
}
