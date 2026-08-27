//! The rotated crop that turns a detected region into a recogniser input.
//!
//! Ported from PaddleOCR's `get_rotate_crop_image` (`ppocrv5.cpp:369-402`), which is the
//! step between the two nets: detection gives an oriented rectangle over the source image,
//! and recognition wants an upright `48 x W` strip of it.
//!
//! # Why it is an affine map and not a perspective one
//!
//! The reference takes four arbitrary points and builds a perspective transform, because
//! upstream PaddleOCR's `DBPostProcess` returns polygon contours whose four corners are not
//! necessarily a rectangle. [`super::dbnet`] returns a [`Rotated`] — a genuine rectangle
//! from rotating calipers — so the transform is a rotation, a scale and a translation. That
//! is exact rather than an approximation of the reference: a perspective transform fitted to
//! four corners of a rectangle *is* this affine map.
//!
//! # Sampling
//!
//! Inverse mapping: walk the output, project each pixel back into the source, and sample
//! bilinearly. The forward direction would leave holes wherever the crop is upscaled.
//!
//! Bilinear rather than the reference's `INTER_LINEAR` default — the same thing — and the
//! same convention [`crate::preprocess`] uses, so a crop and a whole-image resize cannot
//! disagree about where a pixel centre is.

use super::dbnet::{Region, Rotated};

/// The four corners of `rect`, ordered so that `0 -> 1` runs along the text and `0 -> 3`
/// spans its height.
///
/// `Rotated` keeps its short axis in `width` and its long axis in `height`, so for a
/// horizontal run the text direction is the *long* axis. This returns them in that order
/// regardless of which field they came from, which is what makes the caller's crop always
/// `long x short` and never sideways.
///
/// The corner arithmetic is `cv::RotatedRect::points`, which is what the reference's
/// `minAreaRect` output feeds.
pub fn corners(rect: &Rotated) -> [(f32, f32); 4] {
    let radians = rect.angle.to_radians();
    let (sin, cos) = (radians.sin(), radians.cos());
    // Half-extents along the rectangle's own axes: `along` is the long axis (the reading
    // direction) and `across` is the short one (the glyph height).
    let (along, across) = (rect.height * 0.5, rect.width * 0.5);
    // The long axis points at `angle + 90` degrees, because `angle` describes `width`.
    let (along_x, along_y) = (-sin, cos);
    let (across_x, across_y) = (cos, sin);
    let corner = |a: f32, b: f32| {
        (
            rect.cx + along_x * a + across_x * b,
            rect.cy + along_y * a + across_y * b,
        )
    };
    [
        corner(-along, -across),
        corner(along, -across),
        corner(along, across),
        corner(-along, across),
    ]
}

/// The crop width a region wants at `height` tall, padded up to `multiple` and clamped.
///
/// Recognition runs at a fixed `48 x max_width` so that one compiled plan serves every
/// line; a shorter line is padded and a longer one is squeezed. Rounding up to `multiple`
/// is not cosmetic — `nets::ppocr_rec` refuses a width that is not a multiple of 8, because
/// three stride-2 stages act on it.
pub fn crop_width(region: &Region, height: u32, multiple: u32, max_width: u32) -> u32 {
    let short = region.rect.width.max(1.0);
    let long = region.rect.height.max(1.0);
    let scaled = (height as f32 * long / short).round().max(1.0);
    let wanted = (scaled as u32).min(max_width).max(multiple);
    wanted.div_ceil(multiple.max(1)) * multiple.max(1)
}

/// Sample the quad `corners` out of `pixels` into an `out_w` x `out_h` `ARGB_8888` buffer.
///
/// The output is the same shape [`crate::preprocess`] takes, so a crop feeds recognition
/// through exactly the path a whole bitmap does.
///
/// Coordinates outside the source clamp to its edge rather than going black: a detected box
/// is enlarged by 1.95x before it gets here, so it routinely hangs off the image, and a
/// black border would be read as a stroke.
pub fn warp(
    pixels: &[i32],
    width: u32,
    height: u32,
    corners: &[(f32, f32); 4],
    out_w: u32,
    out_h: u32,
) -> Result<Vec<i32>, String> {
    if width == 0 || height == 0 {
        return Err("a zero-sized source".into());
    }
    if out_w == 0 || out_h == 0 {
        return Err("a zero-sized crop".into());
    }
    let expected = (width as usize)
        .checked_mul(height as usize)
        .ok_or("source dimensions overflow")?;
    if pixels.len() != expected {
        return Err(format!("{} pixels for a {width}x{height} source", pixels.len()));
    }

    let (origin, along, across) = basis(corners);
    let mut out = vec![0i32; (out_w as usize) * (out_h as usize)];
    for y in 0..out_h {
        // Pixel centres, so a 1:1 crop samples the source's own centres.
        let v = (y as f32 + 0.5) / out_h as f32;
        for x in 0..out_w {
            let u = (x as f32 + 0.5) / out_w as f32;
            let sx = origin.0 + along.0 * u + across.0 * v;
            let sy = origin.1 + along.1 * u + across.1 * v;
            let slot = out
                .get_mut((y * out_w + x) as usize)
                .ok_or("the crop wrote past its own end")?;
            *slot = bilinear(pixels, width, height, sx, sy);
        }
    }
    Ok(out)
}

/// `(corner 0, the 0 -> 1 edge, the 0 -> 3 edge)`, which is the crop's coordinate frame.
fn basis(corners: &[(f32, f32); 4]) -> ((f32, f32), (f32, f32), (f32, f32)) {
    let p = corners;
    (
        p[0],
        (p[1].0 - p[0].0, p[1].1 - p[0].1),
        (p[3].0 - p[0].0, p[3].1 - p[0].1),
    )
}

/// One bilinear `ARGB_8888` sample, with edge clamping. Alpha comes back opaque: the crop
/// feeds a net that has no fourth channel, and a transparent pixel would otherwise read as
/// whatever the source happened to leave under it.
fn bilinear(pixels: &[i32], width: u32, height: u32, x: f32, y: f32) -> i32 {
    let clamp = |value: f32, extent: u32| value.max(0.0).min(extent as f32 - 1.0);
    let (x, y) = (clamp(x, width), clamp(y, height));
    let (x0, y0) = (x.floor(), y.floor());
    let (wx, wy) = (x - x0, y - y0);
    let x0 = x0 as u32;
    let y0 = y0 as u32;
    let x1 = (x0 + 1).min(width - 1);
    let y1 = (y0 + 1).min(height - 1);

    let at = |px: u32, py: u32| -> u32 {
        pixels.get((py * width + px) as usize).copied().unwrap_or(0) as u32
    };
    let (p00, p10, p01, p11) = (at(x0, y0), at(x1, y0), at(x0, y1), at(x1, y1));
    let channel = |shift: u32| -> u32 {
        let take = |packed: u32| ((packed >> shift) & 0xff) as f32;
        let top = take(p00) + (take(p10) - take(p00)) * wx;
        let bottom = take(p01) + (take(p11) - take(p01)) * wx;
        (top + (bottom - top) * wy).round().clamp(0.0, 255.0) as u32
    };
    ((0xffu32 << 24) | (channel(16) << 16) | (channel(8) << 8) | channel(0)) as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rect(cx: f32, cy: f32, short: f32, long: f32, angle: f32) -> Rotated {
        Rotated { cx, cy, width: short, height: long, angle }
    }

    fn region(rect: Rotated, vertical: bool) -> Region {
        Region { rect, score: 1.0, vertical }
    }

    fn argb(r: u32, g: u32, b: u32) -> i32 {
        ((0xffu32 << 24) | (r << 16) | (g << 8) | b) as i32
    }

    fn close(got: (f32, f32), want: (f32, f32)) {
        assert!(
            (got.0 - want.0).abs() < 1e-3 && (got.1 - want.1).abs() < 1e-3,
            "{got:?} vs {want:?}"
        );
    }

    #[test]
    fn an_upright_run_gives_its_long_axis_as_the_zero_to_one_edge() {
        // A 40-wide, 10-tall run centred at the origin. `Rotated` stores the short axis in
        // `width`, so a caller that took `width` as the crop's width would get a 10x40
        // sideways strip and recognise nothing.
        let got = corners(&rect(0.0, 0.0, 10.0, 40.0, -90.0));
        // At -90 degrees the long axis lies along +x.
        close(got[0], (-20.0, 5.0));
        close(got[1], (20.0, 5.0));
        close(got[2], (20.0, -5.0));
        close(got[3], (-20.0, -5.0));
        // The reading edge is the long one either way round.
        let along = ((got[1].0 - got[0].0).powi(2) + (got[1].1 - got[0].1).powi(2)).sqrt();
        let across = ((got[3].0 - got[0].0).powi(2) + (got[3].1 - got[0].1).powi(2)).sqrt();
        assert!((along - 40.0).abs() < 1e-3, "{along}");
        assert!((across - 10.0).abs() < 1e-3, "{across}");
    }

    #[test]
    fn the_corner_order_is_a_rectangle_and_not_a_bowtie() {
        // Corner 1 and corner 3 must be adjacent to corner 0, not diagonal from it. A
        // transposition here still produces four points and a plausible crop width, and
        // warps the text into an X.
        let got = corners(&rect(100.0, 50.0, 12.0, 60.0, -30.0));
        let diagonal = |a: (f32, f32), b: (f32, f32)| {
            ((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2)).sqrt()
        };
        let expected = (60.0f32 * 60.0 + 12.0 * 12.0).sqrt();
        assert!((diagonal(got[0], got[2]) - expected).abs() < 1e-2);
        assert!((diagonal(got[1], got[3]) - expected).abs() < 1e-2);
        // And the centre is the mean of the four.
        let cx: f32 = got.iter().map(|p| p.0).sum::<f32>() / 4.0;
        let cy: f32 = got.iter().map(|p| p.1).sum::<f32>() / 4.0;
        close((cx, cy), (100.0, 50.0));
    }

    #[test]
    fn a_crop_width_scales_the_long_axis_to_the_recognisers_height() {
        // A run four times as long as it is tall, at height 48, wants 192 — rounded up to
        // the multiple of 8 the recogniser insists on.
        let r = region(rect(0.0, 0.0, 10.0, 40.0, -90.0), false);
        assert_eq!(crop_width(&r, 48, 8, 320), 192);
        // A very long line clamps rather than compiling a wider plan.
        let long = region(rect(0.0, 0.0, 10.0, 4000.0, -90.0), false);
        assert_eq!(crop_width(&long, 48, 8, 320), 320);
        // A stubby one still gets a whole multiple, never zero.
        let stub = region(rect(0.0, 0.0, 40.0, 41.0, -90.0), false);
        assert_eq!(crop_width(&stub, 48, 8, 320) % 8, 0);
        assert!(crop_width(&stub, 48, 8, 320) >= 8);
    }

    #[test]
    fn an_axis_aligned_crop_reproduces_the_pixels_it_covers() {
        // A 4x2 source, cropped 1:1 over its whole extent. Sampling at pixel centres means
        // this must come back unchanged rather than shifted half a pixel.
        let pixels = vec![
            argb(10, 0, 0), argb(20, 0, 0), argb(30, 0, 0), argb(40, 0, 0), //
            argb(50, 0, 0), argb(60, 0, 0), argb(70, 0, 0), argb(80, 0, 0),
        ];
        let quad = [(-0.5, -0.5), (3.5, -0.5), (3.5, 1.5), (-0.5, 1.5)];
        let got = warp(&pixels, 4, 2, &quad, 4, 2).expect("warps");
        let reds: Vec<u32> = got.iter().map(|&p| ((p as u32) >> 16) & 0xff).collect();
        assert_eq!(reds, vec![10, 20, 30, 40, 50, 60, 70, 80]);
    }

    #[test]
    fn a_rotated_crop_reads_along_its_own_axis() {
        // A 2x4 source holding a column that reads 10, 20, 30, 40 downwards. Cropped with
        // the reading edge pointing *down*, it must come out as a 4-wide row in that order
        // — which is the whole job for a vertical run.
        let pixels = vec![
            argb(10, 0, 0), argb(10, 0, 0), //
            argb(20, 0, 0), argb(20, 0, 0), //
            argb(30, 0, 0), argb(30, 0, 0), //
            argb(40, 0, 0), argb(40, 0, 0),
        ];
        let quad = [(-0.5, -0.5), (-0.5, 3.5), (1.5, 3.5), (1.5, -0.5)];
        let got = warp(&pixels, 2, 4, &quad, 4, 2).expect("warps");
        let reds: Vec<u32> = got.iter().map(|&p| ((p as u32) >> 16) & 0xff).collect();
        assert_eq!(&reds[0..4], &[10, 20, 30, 40]);
        assert_eq!(&reds[4..8], &[10, 20, 30, 40]);
    }

    #[test]
    fn a_crop_that_hangs_off_the_image_clamps_rather_than_going_black() {
        // Detection enlarges every box by 1.95x, so a box at the edge routinely reaches
        // outside. A black border would be read as a stroke and invent characters.
        let pixels = vec![argb(200, 200, 200); 4];
        let quad = [(-10.0, -10.0), (12.0, -10.0), (12.0, 12.0), (-10.0, 12.0)];
        let got = warp(&pixels, 2, 2, &quad, 8, 8).expect("warps");
        for (i, &p) in got.iter().enumerate() {
            let red = ((p as u32) >> 16) & 0xff;
            assert_eq!(red, 200, "pixel {i} sampled outside the image as {red}");
        }
    }

    #[test]
    fn the_alpha_of_a_crop_is_always_opaque() {
        // The crop feeds a net with three input channels; `preprocess` drops alpha, so a
        // transparent source pixel must not leave the colour channels premultiplied.
        let pixels = vec![0x0000_0000u32 as i32; 4];
        let quad = [(-0.5, -0.5), (1.5, -0.5), (1.5, 1.5), (-0.5, 1.5)];
        let got = warp(&pixels, 2, 2, &quad, 2, 2).expect("warps");
        for &p in &got {
            assert_eq!(((p as u32) >> 24) & 0xff, 0xff, "{p:#010x}");
        }
    }

    #[test]
    fn a_zero_sized_crop_is_refused() {
        let pixels = vec![argb(0, 0, 0); 4];
        let quad = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)];
        assert!(warp(&pixels, 2, 2, &quad, 0, 8).is_err());
        assert!(warp(&pixels, 2, 2, &quad, 8, 0).is_err());
        assert!(warp(&pixels, 0, 2, &quad, 8, 8).is_err());
    }

    #[test]
    fn a_mismatched_pixel_count_is_refused_rather_than_read_short() {
        let quad = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)];
        let error = warp(&[0i32; 3], 2, 2, &quad, 4, 4).expect_err("three pixels");
        assert!(error.contains("3 pixels"), "{error}");
    }
}
