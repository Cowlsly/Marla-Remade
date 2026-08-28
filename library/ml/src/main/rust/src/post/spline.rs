//! The inverse of a piecewise rational quadratic spline, with linear tails.
//!
//! Ported from VITS's `transforms.py` (`unconstrained_rational_quadratic_spline` and
//! `rational_quadratic_spline`, both at `inverse=True`), which is the transform inside its
//! stochastic duration predictor's coupling layers.
//!
//! # What it is
//!
//! A monotonic map from `[-bound, bound]` to itself, built from `bins` rational quadratic
//! segments whose widths, heights and end derivatives are predicted per position by a small
//! convolutional stack. Outside the interval it is the identity, which is what "linear tails"
//! means. Only the inverse direction is needed: the predictor is run backwards at inference,
//! turning noise into log-durations.
//!
//! # Why this is on the CPU
//!
//! The duration predictor holds 555,183 of the voice's parameters — 4% — and produces one
//! number per phoneme. Its arithmetic is a bin search, a softmax over ten bins and a
//! quadratic solve per position: data-dependent branching over tens of values, which is the
//! one shape of work a GPU makes slower. The rest of VITS is on the GPU; this is not.
//!
//! # Precision
//!
//! `f64` throughout, unlike everything else here. The solve divides by `-b - sqrt(b^2 - 4ac)`,
//! and near a bin boundary that denominator is the difference of two nearly equal numbers —
//! the one place in this runtime where fp32 cancellation would be visible in the output. A
//! duration is later rounded up to a whole frame, so an error of one part in a thousand can
//! move a phoneme's length by a frame and audibly change the rhythm.

/// The smallest fraction of the interval one bin may occupy, VITS's `DEFAULT_MIN_BIN_WIDTH`.
const MIN_BIN_WIDTH: f64 = 1e-3;

/// As [`MIN_BIN_WIDTH`], for heights.
const MIN_BIN_HEIGHT: f64 = 1e-3;

/// The smallest slope a knot may have, VITS's `DEFAULT_MIN_DERIVATIVE`. Keeps the spline
/// strictly increasing, and so invertible.
const MIN_DERIVATIVE: f64 = 1e-3;

/// The nudge VITS's `searchsorted` adds to the last boundary so an input exactly at `bound`
/// lands in the last bin rather than past it.
const EDGE: f64 = 1e-6;

/// One position's spline parameters, already separated but **not** normalised.
///
/// `widths` and `heights` are `bins` logits each; `derivatives` is `bins - 1`, because the two
/// ends are pinned to slope 1 by the linear tails. That is why the predictor emits
/// `3 * bins - 1` channels and not `3 * bins`.
pub struct Spline<'a> {
    /// Unnormalised bin widths, `bins` of them.
    pub widths: &'a [f32],
    /// Unnormalised bin heights, `bins` of them.
    pub heights: &'a [f32],
    /// Unnormalised interior knot slopes, `bins - 1` of them.
    pub derivatives: &'a [f32],
    /// Half-width of the interval the spline acts on. VITS uses 5.
    pub bound: f64,
}

/// `softmax(logits)`, scaled so each bin is at least `floor` of the interval and they sum to
/// one.
fn normalise(logits: &[f32], floor: f64) -> Vec<f64> {
    let bins = logits.len();
    let peak = logits.iter().fold(f32::NEG_INFINITY, |a, &b| a.max(b)) as f64;
    let exponentials: Vec<f64> = logits.iter().map(|&l| (l as f64 - peak).exp()).collect();
    let total: f64 = exponentials.iter().sum();
    // `floor * bins` of the interval is reserved and the softmax shares out the rest, so
    // every bin is positive however extreme a logit is.
    let room = 1.0 - floor * bins as f64;
    exponentials.iter().map(|e| floor + room * (e / total)).collect()
}

/// The cumulative boundaries of `sizes` mapped onto `[-bound, bound]`, `bins + 1` of them.
fn boundaries(sizes: &[f64], bound: f64) -> Vec<f64> {
    let mut out = Vec::with_capacity(sizes.len() + 1);
    out.push(-bound);
    let mut running = 0.0;
    for size in sizes {
        running += size;
        out.push(2.0 * bound * running - bound);
    }
    // The ends are set rather than accumulated: the sum is one only up to rounding, and a
    // last boundary a hair inside `bound` would leave a sliver the bin search cannot reach.
    let last = out.len() - 1;
    out[0] = -bound;
    out[last] = bound;
    out
}

/// Slopes at every knot, `bins + 1` of them.
///
/// The interior slopes are `min + softplus(logit)`. The two ends are exactly 1, which is what
/// makes the spline meet its linear tails without a kink — VITS achieves that by padding the
/// logits with `log(exp(1 - min) - 1)`, and this writes the 1 directly.
fn slopes(logits: &[f32]) -> Vec<f64> {
    let mut out = Vec::with_capacity(logits.len() + 2);
    out.push(1.0);
    for &logit in logits {
        // `ln(1 + exp(x))`, computed so a large `x` does not overflow before the log.
        let x = logit as f64;
        let softplus = if x > 30.0 { x } else { x.exp().ln_1p() };
        out.push(MIN_DERIVATIVE + softplus);
    }
    out.push(1.0);
    out
}

/// The index of the bin `value` falls in, given `bins + 1` ascending boundaries.
fn bin_of(boundaries: &[f64], value: f64) -> usize {
    let bins = boundaries.len() - 1;
    let mut found = 0;
    for index in 0..bins {
        let upper = if index == bins - 1 {
            boundaries[index + 1] + EDGE
        } else {
            boundaries[index + 1]
        };
        if value >= boundaries[index] && value < upper {
            found = index;
        }
    }
    found.min(bins - 1)
}

/// Map `input` back through the spline.
///
/// Outside `[-bound, bound]` this is the identity. Inside, it solves the rational quadratic
/// segment containing `input` for the abscissa that produced it.
pub fn inverse(input: f32, spline: &Spline) -> f32 {
    let bound = spline.bound;
    let value = input as f64;
    if value < -bound || value > bound {
        return input;
    }
    let bins = spline.widths.len();
    if bins == 0 || spline.heights.len() != bins || spline.derivatives.len() + 1 != bins {
        // A malformed spline is the caller's bug, and returning the input unchanged is the
        // one answer that cannot make a duration negative.
        return input;
    }

    let widths = normalise(spline.widths, MIN_BIN_WIDTH);
    let heights = normalise(spline.heights, MIN_BIN_HEIGHT);
    let cumulative_widths = boundaries(&widths, bound);
    let cumulative_heights = boundaries(&heights, bound);
    let knots = slopes(spline.derivatives);

    // Inverting, so the bin is found by *height*: the input is on the output axis.
    let bin = bin_of(&cumulative_heights, value);
    let width = cumulative_widths[bin + 1] - cumulative_widths[bin];
    let height = cumulative_heights[bin + 1] - cumulative_heights[bin];
    if width <= 0.0 || height <= 0.0 {
        return input;
    }
    let average = height / width;
    let start = knots[bin];
    let end = knots[bin + 1];
    let offset = value - cumulative_heights[bin];

    // The rational quadratic, rearranged into `a t^2 + b t + c = 0` for the fraction `t`
    // through the bin.
    let curvature = start + end - 2.0 * average;
    let a = offset * curvature + height * (average - start);
    let b = height * start - offset * curvature;
    let c = -average * offset;
    let discriminant = b * b - 4.0 * a * c;
    if discriminant < 0.0 {
        return input;
    }
    // `2c / (-b - sqrt(D))` rather than the textbook `(-b - sqrt(D)) / 2a`: the two agree
    // algebraically, and this form does not divide by `a`, which is zero wherever the
    // segment happens to be straight. VITS uses the same rearrangement.
    let denominator = -b - discriminant.sqrt();
    let fraction = if denominator == 0.0 { 0.0 } else { 2.0 * c / denominator };
    (fraction * width + cumulative_widths[bin]) as f32
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The logit that makes `MIN_DERIVATIVE + softplus(logit)` exactly 1, which is the slope
    /// VITS pins the two ends to. `log(exp(1 - min) - 1)`.
    fn unit_slope() -> f32 {
        ((1.0f64 - MIN_DERIVATIVE).exp() - 1.0).ln() as f32
    }

    /// The forward direction, for round-tripping. Test-only: inference never needs it, and
    /// having it lets every inverse be checked against the map it claims to invert.
    fn forward(input: f32, spline: &Spline) -> f32 {
        let bound = spline.bound;
        let value = input as f64;
        if value < -bound || value > bound {
            return input;
        }
        let widths = normalise(spline.widths, MIN_BIN_WIDTH);
        let heights = normalise(spline.heights, MIN_BIN_HEIGHT);
        let cumulative_widths = boundaries(&widths, bound);
        let cumulative_heights = boundaries(&heights, bound);
        let knots = slopes(spline.derivatives);

        // Forward, so the bin is found by *width*.
        let bin = bin_of(&cumulative_widths, value);
        let width = cumulative_widths[bin + 1] - cumulative_widths[bin];
        let height = cumulative_heights[bin + 1] - cumulative_heights[bin];
        let average = height / width;
        let start = knots[bin];
        let end = knots[bin + 1];
        let fraction = (value - cumulative_widths[bin]) / width;

        let numerator = height
            * (average * fraction * fraction + start * fraction * (1.0 - fraction));
        let denominator =
            average + (start + end - 2.0 * average) * fraction * (1.0 - fraction);
        (cumulative_heights[bin] + numerator / denominator) as f32
    }

    fn uniform(bins: usize) -> (Vec<f32>, Vec<f32>, Vec<f32>) {
        (vec![0.0; bins], vec![0.0; bins], vec![unit_slope(); bins - 1])
    }

    #[test]
    fn a_uniform_spline_with_unit_slopes_is_the_identity() {
        // Equal widths, equal heights and every slope 1 makes each segment a straight line of
        // gradient one, so the whole spline is the identity. This is the fixture that pins the
        // normalisation: a wrong floor, a missing `2 * bound` or an off-by-one in the
        // boundaries all break it.
        let (widths, heights, derivatives) = uniform(10);
        let spline = Spline {
            widths: &widths,
            heights: &heights,
            derivatives: &derivatives,
            bound: 5.0,
        };
        for step in -50..=50 {
            let x = step as f32 / 10.0;
            let got = inverse(x, &spline);
            assert!((got - x).abs() < 2e-3, "at {x}: {got}");
        }
    }

    #[test]
    fn outside_the_interval_it_is_exactly_the_identity() {
        // Linear tails. Not approximately: the input is returned unchanged, so a long
        // duration is not quantised by the spline at all.
        let (widths, heights, derivatives) = uniform(10);
        let spline = Spline {
            widths: &widths,
            heights: &heights,
            derivatives: &derivatives,
            bound: 5.0,
        };
        for x in [-1000.0f32, -5.001, 5.001, 1000.0] {
            assert_eq!(inverse(x, &spline), x, "at {x}");
        }
    }

    #[test]
    fn the_inverse_undoes_the_forward_on_a_lopsided_spline() {
        // Deliberately uneven: widths that grow, heights that shrink and slopes that vary, so
        // the bin search, the height normalisation and the quadratic solve are all exercised
        // on different values. A bug shared by both directions could cancel, but the two
        // search *different* axes and solve differently, so most cannot.
        let widths: Vec<f32> = (0..10).map(|i| i as f32 * 0.4 - 2.0).collect();
        let heights: Vec<f32> = (0..10).map(|i| 2.0 - i as f32 * 0.3).collect();
        let derivatives: Vec<f32> = (0..9).map(|i| (i as f32 - 4.0) * 0.5).collect();
        let spline = Spline {
            widths: &widths,
            heights: &heights,
            derivatives: &derivatives,
            bound: 5.0,
        };
        for step in -49..=49 {
            let x = step as f32 / 10.0;
            let there = forward(x, &spline);
            let back = inverse(there, &spline);
            assert!((back - x).abs() < 5e-3, "at {x}: forward {there}, back {back}");
        }
    }

    #[test]
    fn the_inverse_is_increasing() {
        // Monotonicity is what makes the transform invertible at all, and `MIN_DERIVATIVE` is
        // what guarantees it. A non-monotonic spline would map two noises to one duration and
        // the flow would not be a bijection.
        let widths: Vec<f32> = vec![3.0, -2.0, 0.5, 1.0, -3.0, 2.0, 0.0, -1.0, 4.0, -0.5];
        let heights: Vec<f32> = vec![-1.0, 2.0, 0.0, -3.0, 1.5, 0.5, -2.0, 3.0, -0.5, 1.0];
        let derivatives: Vec<f32> = vec![-5.0, 5.0, 0.0, -2.0, 2.0, -1.0, 1.0, 3.0, -3.0];
        let spline = Spline {
            widths: &widths,
            heights: &heights,
            derivatives: &derivatives,
            bound: 5.0,
        };
        let mut previous = f32::NEG_INFINITY;
        for step in -50..=50 {
            let got = inverse(step as f32 / 10.0, &spline);
            assert!(got > previous, "at {step}: {got} after {previous}");
            previous = got;
        }
    }

    #[test]
    fn the_ends_of_the_interval_map_to_the_ends() {
        // The spline is a bijection of `[-bound, bound]` onto itself, so its ends are fixed
        // whatever the parameters. If the last boundary were accumulated rather than set,
        // `+bound` would fall outside every bin.
        let widths: Vec<f32> = vec![5.0, -5.0, 1.0, 0.0, 2.0, -1.0, 3.0, -2.0, 0.5, 4.0];
        let heights: Vec<f32> = vec![-2.0, 4.0, 0.0, 1.0, -3.0, 2.0, -1.0, 5.0, 0.5, -4.0];
        let derivatives: Vec<f32> = vec![0.0; 9];
        let spline = Spline {
            widths: &widths,
            heights: &heights,
            derivatives: &derivatives,
            bound: 5.0,
        };
        assert!((inverse(-5.0, &spline) + 5.0).abs() < 1e-3, "{}", inverse(-5.0, &spline));
        assert!((inverse(5.0, &spline) - 5.0).abs() < 1e-3, "{}", inverse(5.0, &spline));
    }

    #[test]
    fn a_malformed_spline_returns_its_input_rather_than_a_wrong_duration() {
        // `derivatives` must be one shorter than `widths`. Anything else is a transcription
        // bug, and the identity is the only answer that cannot produce a negative duration.
        let widths = vec![0.0; 10];
        let heights = vec![0.0; 10];
        let derivatives = vec![0.0; 10];
        let spline = Spline {
            widths: &widths,
            heights: &heights,
            derivatives: &derivatives,
            bound: 5.0,
        };
        assert_eq!(inverse(1.25, &spline), 1.25);
    }
}
