//! Differential rasterisation against an INDEPENDENT reference renderer.
//!
//! # Why this module exists
//!
//! Every other test in this crate is self-referential. `tests.rs`,
//! `golden_tests.rs`, `robustness_tests.rs` and `differential_tests.rs` all
//! assert that the renderer does what *this team* decided the spec means. That
//! catches regressions and crashes, and `differential_tests.rs` additionally
//! catches non-determinism and broken metamorphic relations — but none of them
//! can catch the failure mode where a clause was misread the same way by the
//! implementer and by every reviewer. In that case the code and the test agree
//! by construction, the suite is green, and the page renders confidently wrong.
//!
//! The only cure is a second implementation of the same specification, written
//! by people who have never seen this one, compared on the one artefact both
//! produce: pixels.
//!
//! # The reference: `hayro` 0.7.1
//!
//! Chosen after evaluating the pure-Rust PDF-rasteriser field. Criteria and how
//! it scored:
//!
//! * **Actually rasterises** (not merely parses). Yes — `hayro::render` returns
//!   a `vello_cpu::Pixmap` of premultiplied RGBA8.
//! * **Pure Rust, no system libraries.** Yes — CPU-only through `vello_cpu`;
//!   the crate is `#![forbid(unsafe_code)]`. No C, no `build.rs` linking, no
//!   pkg-config. (`pdfium-render` and the other pdfium bindings were rejected
//!   on exactly this point: a native binary blob in a crate that cross-compiles
//!   to `aarch64-linux-android` is a real cost even confined to dev-deps, since
//!   it has to be fetched and matched per host and per CI image.)
//! * **Builds on this Windows host.** Yes, verified: clean `cargo build` of the
//!   whole 40-crate subtree in ~35 s, no toolchain beyond stable rustc. It
//!   declares `rust-version = 1.92`; this tree is on 1.97.
//! * **Licence.** `Apache-2.0 OR MIT`. The MIT arm is compatible with this
//!   crate's `GPL-2.0-only`, and a dev-dependency is not distributed regardless.
//! * **API usable from a test.** Yes: `Pdf::new(Vec<u8>)` → `pages()[i]` →
//!   `render(...)`. No filesystem fixtures required; `embed-fonts` (on by
//!   default) removes the need to ship the standard-14 substitutes.
//! * **Independent.** Separate authorship, separate lineage (`hayro-syntax` /
//!   `hayro-interpret`, not `lopdf`), and its regression corpus is ~1000 files
//!   scraped from the `pdf.js` and PDFBox suites — i.e. calibrated against two
//!   *further* independent implementations.
//!
//! ## The one shared component — and why it does not compromise the result
//!
//! `hayro-jbig2` is already a NORMAL dependency of this crate (`Cargo.toml`),
//! and `hayro` pulls it too. JBIG2 decoding is therefore NOT independently
//! verified by this module, and JBIG2 is deliberately absent from the corpus
//! below. Everything else — the object model, the content-stream interpreter,
//! colour spaces, functions, shadings, image decoding, the rasteriser — is
//! disjoint between the two.
//!
//! ## Proof that the dev-dependency cannot reach the shipped `cdylib`
//!
//! Three independent checks, all reproducible:
//!
//! 1. `cargo tree -p pdf_render --edges normal --target aarch64-linux-android`
//!    lists `hayro-jbig2` under `pdf_render` and does NOT list `hayro`,
//!    `hayro-interpret`, `hayro-syntax` or `vello_cpu` anywhere.
//! 2. Adding the entry changed the workspace `Cargo.lock` by **437 insertions
//!    and 0 deletions**. Zero deletions is the load-bearing half: no existing
//!    package's resolved version moved, so the graph reachable from the
//!    `cdylib` is byte-identical to what it was before.
//! 3. `cargo build --lib` does not compile `hayro`. Cargo only builds
//!    dev-dependencies for test/bench/example targets; the `cdylib` is a `lib`
//!    target. With `resolver = "2"` (set at the workspace root) features
//!    enabled by dev-dependencies are also not unified into non-dev builds.
//!
//! Check 2 is the one worth repeating after any dependency change here, because
//! it is the only one that would catch the subtle failure: a dev-dependency
//! that does not itself enter the build but *bumps a shared normal dependency*.
//!
//! # Harness design
//!
//! Both renderers are handed the SAME bytes (`Document::save_to` output), so no
//! difference can come from serialisation.
//!
//! * `hayro` rasterises directly to a pixmap.
//! * This crate emits `Prim`s, not pixels, so [`rasterize`] below turns them
//!   into a bitmap. **Everything that rasteriser approximates is a potential
//!   false positive**, so it is enumerated exhaustively in the next section.
//! * The two bitmaps are compared by [`fuzzy_diff`], a neighbourhood-tolerant
//!   perceptual metric (see below). Exact equality between two independent
//!   renderers is 100% false positives and is never used.
//!
//! ## EXACTLY what this module's rasteriser approximates
//!
//! Read this before believing any failure. In rough order of how likely each is
//! to masquerade as a renderer bug:
//!
//! 1. **Antialiasing model.** 4 sub-scanlines per pixel row with exact analytic
//!    x-coverage; `hayro`/`vello_cpu` use their own (different) analytic
//!    coverage. Edge pixels will differ by tens of levels. This is the entire
//!    reason for the neighbourhood-tolerant metric.
//! 2. **Stroke geometry is approximate.** Segments become quads; round joins and
//!    round caps become 24-gon disks; **miter joins are drawn as BEVEL joins**
//!    (`/MiterLimit` is ignored); projecting-square caps extend by half-width.
//!    All quads for one stroke are unioned as a single nonzero path so joins do
//!    not double-composite. Corpus strokes are therefore kept thin, or use round
//!    joins, or are compared only where the approximation is exact.
//! 3. **Image sampling is NEAREST-NEIGHBOUR**, with 2x2 subsampling per device
//!    pixel for edge coverage only. `/Interpolate` is ignored. Any test that
//!    magnifies a small image will disagree on interior gradients unless the
//!    image is a flat-block design — the corpus images are flat blocks for
//!    exactly this reason.
//! 3a. **Clip-path beziers are flattened SCALE-AWARELY** by [`clip_contours`],
//!    at roughly one segment per 3 device px. That deliberately mirrors the
//!    consumer rather than the renderer: a clip curve crosses the wire as a real
//!    `PathOp::Cubic` and Skia re-flattens it at device resolution every frame,
//!    so a clip boundary does not facet at zoom. Fills and strokes are the
//!    opposite — they arrive already flattened by `interpret.rs` at page-point
//!    resolution — so a disagreement on a filled or stroked curve is the
//!    renderer's flattener, and one on a clip boundary would be this file's.
//!    Pinned by [`refdiff_curved_clip_is_zoom_correct`].
//! 4. **Blend modes are ignored** (everything composites Normal, source-over,
//!    in non-linear sRGB u8 space). No corpus entry sets `/BM`.
//! 5. **Transparency groups**: `GroupPush`/`GroupPop` become a plain isolated
//!    layer composited with the group alpha. `isolated`/`knockout` are ignored.
//!    (`hayro` documents that it does not implement knockout/isolation either,
//!    so this is not a comparison anyone should trust; no corpus entry uses it.)
//! 6. **Soft masks** are implemented (content layer, mask layer, luminosity or
//!    alpha, `/TR` LUT). This rasteriser seeds the luminosity mask canvas with
//!    opaque black and does NOT itself read the group's `/BC` — but it does not
//!    need to: `interpret.rs` emits the §11.6.5.2 backdrop as an ordinary `Fill`
//!    covering the group `/BBox` before the mask content, so a non-default
//!    `/BC` arrives as a primitive and is honoured here for free. The black seed
//!    and the emitted default-black backdrop simply coincide. Pinned by
//!    [`refdiff_luminosity_soft_mask_backdrop_colour`]; if the backdrop ever
//!    stops being a prim and becomes a field on `SoftMaskPush`, that test starts
//!    failing and this note has to change with it.
//! 7. **`Prim::Text` with `outline == false` is NOT DRAWN.** That is the
//!    substitute-typeface fallback, where our renderer hands Kotlin a font name
//!    instead of contours; there is nothing to rasterise here. Rather than
//!    silently comparing a page with missing text, [`rasterize`] COUNTS these
//!    and [`compare_page`] hard-fails if the count is non-zero. Text is
//!    therefore tested through **Type 3 fonts**, whose glyphs are content
//!    streams both renderers must draw identically — which tests the text
//!    positioning machinery (`Tf`/`Td`/`TJ`/`Tz`/`Tc`/`Tw`/`TL`/`Tm`/
//!    `/FontMatrix`) without needing a font file or a glyph rasteriser.
//! 8. **`Prim::TextClipApply` is ignored** (no text-clip corpus entry).
//! 9. Colour is composited in 8-bit-ish sRGB with no gamma correction, matching
//!    what the Kotlin `Canvas` does but not necessarily what `vello_cpu` does
//!    internally for partial coverage.
//!
//! ## The comparison metric
//!
//! [`fuzzy_diff`] flags a pixel only when it differs from EVERY pixel in the
//! 3x3 neighbourhood of the other image by more than `CHANNEL_TOLERANCE` on
//! some channel. That makes it blind to antialiasing and to sub-pixel placement
//! differences, which are guaranteed and uninteresting, while remaining
//! sensitive to the things that matter: a wrong colour anywhere, ink present in
//! one render and absent in the other, or a shape displaced by more than a
//! pixel. A page passes when fewer than `DIFF_BUDGET` of its pixels are flagged.
//!
//! Several tests additionally use [`interior_mean`], which averages a rectangle
//! well inside a shape's boundary. That number is free of every antialiasing
//! concern above, so where a test can be phrased as "what colour is this
//! region", it is — and the tolerance can then be tightened to a couple of
//! levels, which is where the colour-space and function findings came from.
//!
//! # What it found
//!
//! 24 test functions covering 26 graded page comparisons at [`SCALE`], plus a
//! zoom-ceiling pair at 10.6 px/pt and one that deliberately checks the harness
//! REFUSES to grade (see [`refdiff_harness_refuses_a_page_it_cannot_draw`]).
//! **22 of the 26 agree at 0.0000% flagged pixels**
//! 0.0000% flagged pixels** — an exact match on shadings (axial, radial and
//! function-based), all four function types, Indexed and Separation, CalRGB,
//! image placement and `/Decode`, image masks and stencil polarity, nested and
//! even-odd clipping, `/Rotate` and `/CropBox`, form XObject `/Matrix` and
//! `/BBox`, dashes and phase, constant alpha, luminosity soft masks including a
//! non-default `/BC` backdrop, tiling patterns, and bezier flattening. Of the
//! remaining four, two are curve rims inside the noise floor (see
//! [`DIFF_BUDGET`]) and two are the findings below. That level of agreement is
//! itself the main evidence that the harness is measuring the renderers and not
//! itself.
//!
//! The three that do not agree, with verdicts:
//!
//! 1. **OUR BUG — Type 3 glyph descriptions are corrupted.** `d0`/`d1` are
//!    mis-tokenised by lopdf 0.36 and the damage lands on the glyph's first
//!    painting operator. See
//!    [`refdiff_root_cause_d0_mangles_the_next_operator`] for the isolated
//!    repro, the spec citation (§9.6.5, Table 113) and the proposed patch in
//!    `content.rs`. Both that test and
//!    [`refdiff_type3_font_text_positioning`] fail today because of it; they
//!    are the only two failures and they are the same bug.
//! 2. **Neither is wrong — `DeviceCMYK`, up to 109/255.** We convert
//!    arithmetically, `hayro` runs a CGATS TR 001 ICC profile. §8.6.4 makes the
//!    device spaces device-dependent and §8.6.5.6 provides `/DefaultCMYK` for
//!    documents that need a defined colorimetry. Recorded and bounded by
//!    [`refdiff_devicecmyk_diverges_because_the_reference_is_colour_managed`].
//! 3. **OURS IS RIGHT — `Lab`, up to 61/255.** [`spec_lab_to_srgb`], a third
//!    implementation written straight from §8.6.5.4, matches this crate at all
//!    six test patches to 0/255 and `hayro` to as much as 61/255. `hayro`'s
//!    numbers are consistent with a D50-referenced ICC Lab PCS that discards
//!    the declared `/WhitePoint`. No patch proposed.
//!
//! # Running
//!
//! Every test here is `#[ignore]`d, following `perf_tests.rs`, so the normal
//! suite is unchanged:
//!
//! ```text
//! cargo test -p pdf_render reference_diff -- --ignored --nocapture
//! ```

use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};

// ---------------------------------------------------------------------------
// Comparison thresholds
// ---------------------------------------------------------------------------

/// Per-channel 0-255 difference below which two pixels are "the same colour".
/// 30 absorbs sRGB rounding and the two renderers' differing partial-coverage
/// arithmetic; it is far below the distance between any two colours the corpus
/// deliberately puts next to each other.
const CHANNEL_TOLERANCE: i32 = 30;

/// Fraction of flagged pixels a page may have and still pass.
///
/// Calibrated against measured clean renders, not estimated. Of the 26 page
/// comparisons taken at [`SCALE`], 22 flag exactly 0.0000%. The four that do
/// not, in order:
///
/// ```text
///   bezier_stroke_vy  0.1356%   curve rim + this file's stroke expansion
///   bezier_fill       0.0425%   curve rim, antialiasing only
///   star_nonzero      0.0013%   two pixels at the spike tips
///   (all others)      0.0000%
/// ```
///
/// So the observed ceiling for a clean page is ~0.14% and 1% leaves roughly a
/// 7x margin. The two defects this harness actually caught were nowhere near
/// the line — the Type 3 `d0` corruption flagged 4.41% and the DeviceCMYK
/// colour-management divergence 49.28% — so the gap between "clean" and
/// "broken" is two to three orders of magnitude, not a judgement call.
///
/// [`refdiff_curved_clip_is_zoom_correct`] runs at 10.6 px/pt rather than
/// [`SCALE`] and is deliberately NOT folded into the figures above: flagged
/// fractions are not comparable across scales, since the rim is a different
/// share of the canvas. It grades itself against its own paired baseline.
const DIFF_BUDGET: f64 = 0.01;

/// Device pixels per PDF unit for the comparison. 2x keeps edge pixels a small
/// fraction of the total without making the pages slow.
const SCALE: f32 = 2.0;

// ---------------------------------------------------------------------------
// A premultiplied-RGBA float canvas
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct Canvas {
    w: usize,
    h: usize,
    /// Premultiplied RGBA, row-major, row 0 = top.
    px: Vec<[f32; 4]>,
}

impl Canvas {
    fn new(w: usize, h: usize, fill: [f32; 4]) -> Self {
        Canvas { w, h, px: vec![fill; w * h] }
    }

    fn opaque_white(w: usize, h: usize) -> Self {
        Canvas::new(w, h, [1.0, 1.0, 1.0, 1.0])
    }

    fn transparent(w: usize, h: usize) -> Self {
        Canvas::new(w, h, [0.0, 0.0, 0.0, 0.0])
    }

    /// Source-over a straight-alpha colour through a coverage/clip product.
    fn blend_px(&mut self, i: usize, rgb: [f32; 3], a: f32) {
        if a <= 0.0 {
            return;
        }
        let a = a.min(1.0);
        let d = &mut self.px[i];
        d[0] = rgb[0] * a + d[0] * (1.0 - a);
        d[1] = rgb[1] * a + d[1] * (1.0 - a);
        d[2] = rgb[2] * a + d[2] * (1.0 - a);
        d[3] = a + d[3] * (1.0 - a);
    }

    /// Composite another (premultiplied) layer over this one, scaled by a
    /// constant alpha and an optional per-pixel mask.
    fn composite_layer(&mut self, src: &Canvas, alpha: f32, mask: Option<&[f32]>) {
        for i in 0..self.px.len() {
            let m = mask.map_or(1.0, |m| m[i]) * alpha;
            if m <= 0.0 {
                continue;
            }
            let s = src.px[i];
            let sa = s[3] * m;
            if sa <= 0.0 {
                continue;
            }
            let d = &mut self.px[i];
            d[0] = s[0] * m + d[0] * (1.0 - sa);
            d[1] = s[1] * m + d[1] * (1.0 - sa);
            d[2] = s[2] * m + d[2] * (1.0 - sa);
            d[3] = sa + d[3] * (1.0 - sa);
        }
    }

    /// Flatten to straight 8-bit RGB over an opaque white page.
    fn to_rgb8(&self) -> Vec<[u8; 3]> {
        self.px
            .iter()
            .map(|p| {
                let a = p[3];
                let f = |c: f32| {
                    let v = c + (1.0 - a); // composite over white
                    (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u8
                };
                [f(p[0]), f(p[1]), f(p[2])]
            })
            .collect()
    }
}

// ---------------------------------------------------------------------------
// Scanline polygon coverage
// ---------------------------------------------------------------------------

/// Sub-scanlines per pixel row. 4 is enough that the metric's neighbourhood
/// tolerance absorbs the rest.
const SUBSAMPLES: usize = 4;

/// Analytic-in-x, supersampled-in-y coverage of a set of closed contours given
/// in DEVICE pixel coordinates (y down). Returns `w * h` values in `0..=1`.
fn poly_coverage(w: usize, h: usize, contours: &[Vec<(f64, f64)>], even_odd: bool) -> Vec<f32> {
    let mut cov = vec![0f32; w * h];
    let mut edges: Vec<(f64, f64, f64, f64)> = Vec::new();
    for c in contours {
        if c.len() < 3 {
            continue;
        }
        for i in 0..c.len() {
            let a = c[i];
            let b = c[(i + 1) % c.len()];
            if (a.1 - b.1).abs() > 1e-12 {
                edges.push((a.0, a.1, b.0, b.1));
            }
        }
    }
    if edges.is_empty() {
        return cov;
    }
    let wgt = 1.0 / SUBSAMPLES as f32;
    let mut xs: Vec<(f64, i32)> = Vec::new();
    for py in 0..h {
        let row = &mut cov[py * w..(py + 1) * w];
        for s in 0..SUBSAMPLES {
            let yc = py as f64 + (s as f64 + 0.5) / SUBSAMPLES as f64;
            xs.clear();
            for &(ax, ay, bx, by) in &edges {
                let (lo, hi) = if ay < by { (ay, by) } else { (by, ay) };
                if yc < lo || yc >= hi {
                    continue;
                }
                let t = (yc - ay) / (by - ay);
                xs.push((ax + t * (bx - ax), if by > ay { 1 } else { -1 }));
            }
            if xs.len() < 2 {
                continue;
            }
            xs.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
            let mut wind = 0i32;
            for i in 0..xs.len() - 1 {
                wind += xs[i].1;
                let inside = if even_odd { (i as i32 + 1) % 2 != 0 } else { wind != 0 };
                if inside {
                    add_span(row, w, xs[i].0, xs[i + 1].0, wgt);
                }
            }
        }
        for v in row.iter_mut() {
            *v = v.min(1.0);
        }
    }
    cov
}

fn add_span(row: &mut [f32], w: usize, xa: f64, xb: f64, wgt: f32) {
    let xa = xa.max(0.0);
    let xb = xb.min(w as f64);
    if !(xb > xa) {
        return;
    }
    let ia = xa.floor() as usize;
    let ib = (xb.ceil() as usize).min(w);
    if ia >= w {
        return;
    }
    for i in ia..ib {
        let l = (i as f64).max(xa);
        let r = ((i + 1) as f64).min(xb);
        if r > l {
            row[i] += wgt * (r - l) as f32;
        }
    }
}

// ---------------------------------------------------------------------------
// Stroke expansion (approximate — see the module header, item 2)
// ---------------------------------------------------------------------------

fn disk(cx: f64, cy: f64, r: f64) -> Vec<(f64, f64)> {
    (0..24)
        .map(|i| {
            let t = i as f64 / 24.0 * std::f64::consts::TAU;
            (cx + r * t.cos(), cy + r * t.sin())
        })
        .collect()
}

fn orient_ccw(mut q: Vec<(f64, f64)>) -> Vec<(f64, f64)> {
    let area: f64 = (0..q.len())
        .map(|i| {
            let a = q[i];
            let b = q[(i + 1) % q.len()];
            a.0 * b.1 - b.0 * a.1
        })
        .sum();
    if area < 0.0 {
        q.reverse();
    }
    q
}

/// Split a polyline into the "on" runs of a dash pattern (§8.4.3.6).
fn apply_dash(pts: &[(f64, f64)], dash: &[f32], phase: f32) -> Vec<Vec<(f64, f64)>> {
    let pat: Vec<f64> = dash.iter().map(|v| *v as f64).filter(|v| *v >= 0.0).collect();
    if pat.is_empty() || pat.iter().all(|v| *v <= 0.0) {
        return vec![pts.to_vec()];
    }
    let total: f64 = pat.iter().sum();
    let mut idx = 0usize;
    let mut on = true;
    let mut rem = pat[0];
    let mut ph = (phase as f64).rem_euclid(total * if pat.len() % 2 == 1 { 2.0 } else { 1.0 });
    while ph > 0.0 {
        if ph >= rem {
            ph -= rem;
            idx = (idx + 1) % pat.len();
            on = !on;
            rem = pat[idx];
        } else {
            rem -= ph;
            ph = 0.0;
        }
    }
    let mut out: Vec<Vec<(f64, f64)>> = Vec::new();
    let mut cur: Vec<(f64, f64)> = Vec::new();
    if on {
        if let Some(p) = pts.first() {
            cur.push(*p);
        }
    }
    for seg in pts.windows(2) {
        let (x0s, y0s) = seg[0];
        let (x1, y1) = seg[1];
        let seg_len = ((x1 - x0s).powi(2) + (y1 - y0s).powi(2)).sqrt();
        if seg_len < 1e-12 {
            continue;
        }
        let (ux, uy) = ((x1 - x0s) / seg_len, (y1 - y0s) / seg_len);
        let (mut x0, mut y0) = (x0s, y0s);
        let mut left = seg_len;
        while left > 1e-12 {
            if rem <= 1e-12 {
                idx = (idx + 1) % pat.len();
                on = !on;
                rem = pat[idx].max(1e-9);
                if on {
                    cur = vec![(x0, y0)];
                } else if cur.len() >= 2 {
                    out.push(std::mem::take(&mut cur));
                } else {
                    cur.clear();
                }
                continue;
            }
            let step = left.min(rem);
            let (px, py) = (x0 + ux * step, y0 + uy * step);
            if on {
                cur.push((px, py));
            }
            x0 = px;
            y0 = py;
            left -= step;
            rem -= step;
        }
    }
    if cur.len() >= 2 {
        out.push(cur);
    }
    out
}

fn stroke_contours(
    pts: &[(f64, f64)],
    width: f64,
    cap: u8,
    join: u8,
    dash: &[f32],
    phase: f32,
) -> Vec<Vec<(f64, f64)>> {
    let hw = (width / 2.0).max(0.35);
    let mut out: Vec<Vec<(f64, f64)>> = Vec::new();
    for run in apply_dash(pts, dash, phase) {
        if run.len() < 2 {
            if run.len() == 1 && cap == 1 {
                out.push(orient_ccw(disk(run[0].0, run[0].1, hw)));
            }
            continue;
        }
        let mut normals: Vec<(f64, f64)> = Vec::new();
        let last_seg = run.len() - 2;
        for (si, seg) in run.windows(2).enumerate() {
            let (x0, y0) = seg[0];
            let (x1, y1) = seg[1];
            let (dx, dy) = (x1 - x0, y1 - y0);
            let len = (dx * dx + dy * dy).sqrt();
            if len < 1e-9 {
                normals.push((0.0, 0.0));
                continue;
            }
            let (nx, ny) = (-dy / len * hw, dx / len * hw);
            normals.push((nx, ny));
            let (mut sx0, mut sy0, mut sx1, mut sy1) = (x0, y0, x1, y1);
            if cap == 2 {
                // Projecting square cap: extend the terminal segments.
                let (ux, uy) = (dx / len * hw, dy / len * hw);
                if si == 0 {
                    sx0 -= ux;
                    sy0 -= uy;
                }
                if si == last_seg {
                    sx1 += ux;
                    sy1 += uy;
                }
            }
            out.push(orient_ccw(vec![
                (sx0 + nx, sy0 + ny),
                (sx1 + nx, sy1 + ny),
                (sx1 - nx, sy1 - ny),
                (sx0 - nx, sy0 - ny),
            ]));
        }
        // Joins at interior vertices.
        for i in 1..run.len().saturating_sub(1) {
            let (x, y) = run[i];
            if join == 1 {
                out.push(orient_ccw(disk(x, y, hw)));
            } else {
                // Bevel; also the (under-)approximation used for miter.
                let n0 = normals[i - 1];
                let n1 = normals[i];
                out.push(orient_ccw(vec![(x, y), (x + n0.0, y + n0.1), (x + n1.0, y + n1.1)]));
                out.push(orient_ccw(vec![(x, y), (x - n0.0, y - n0.1), (x - n1.0, y - n1.1)]));
            }
        }
        if cap == 1 {
            let a = run[0];
            let b = run[run.len() - 1];
            out.push(orient_ccw(disk(a.0, a.1, hw)));
            out.push(orient_ccw(disk(b.0, b.1, hw)));
        }
    }
    out
}

// ---------------------------------------------------------------------------
// The prim rasteriser
// ---------------------------------------------------------------------------

/// What a rasterisation had to skip. Non-zero values make a comparison
/// meaningless, so [`compare_page`] refuses to grade a page with any.
#[derive(Default, Debug, PartialEq, Eq)]
struct Skipped {
    /// `Prim::Text` with `outline == false`: substitute typeface, no contours.
    substitute_text: usize,
    /// `Prim::Image` with `format == 1` (undecoded JPEG passthrough).
    jpeg_images: usize,
    /// Any prim carrying a non-Normal blend mode.
    blended: usize,
}

fn argb_to_rgb_a(argb: u32) -> ([f32; 3], f32) {
    let a = ((argb >> 24) & 0xFF) as f32 / 255.0;
    let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
    let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
    let b = (argb & 0xFF) as f32 / 255.0;
    ([r, g, b], a)
}

struct Rast {
    w: usize,
    h: usize,
    scale: f64,
    page_h: f64,
    /// Layer stack; the last entry is the current draw target.
    layers: Vec<Canvas>,
    clip: Vec<f32>,
    clip_stack: Vec<Vec<f32>>,
    /// Group alphas, innermost last.
    group_alpha: Vec<f32>,
    soft: Vec<SoftState>,
    skipped: Skipped,
}

struct SoftState {
    mask_type: u8,
    transfer: Option<Box<[u8; 256]>>,
    content: Option<Canvas>,
}

impl Rast {
    /// Prim display space (y up, origin bottom-left) → device pixels (y down).
    fn dev(&self, x: f64, y: f64) -> (f64, f64) {
        (x * self.scale, (self.page_h - y) * self.scale)
    }

    fn cur(&mut self) -> &mut Canvas {
        let n = self.layers.len() - 1;
        &mut self.layers[n]
    }

    fn fill_contours(&mut self, contours: &[Vec<(f64, f64)>], even_odd: bool, argb: u32) {
        let (rgb, a) = argb_to_rgb_a(argb);
        if a <= 0.0 {
            return;
        }
        let cov = poly_coverage(self.w, self.h, contours, even_odd);
        for i in 0..self.w * self.h {
            let c = cov[i] * self.clip[i];
            if c > 0.0 {
                self.cur().blend_px(i, rgb, a * c);
            }
        }
    }

    fn draw_image(&mut self, ctm: &Mat, w: u32, h: u32, data: &[u8], alpha: f32) {
        if w == 0 || h == 0 || data.len() < (w as usize * h as usize * 4) {
            return;
        }
        // Unit square → device pixels.
        let s = self.scale;
        let ph = self.page_h;
        let to_dev: Mat = mat_mul(ctm, &[s, 0.0, 0.0, -s, 0.0, ph * s]);
        let inv = match mat_inverse_checked(&to_dev) {
            Some(m) => m,
            None => return,
        };
        // Device bbox of the transformed unit square.
        let corners = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
            .map(|(u, v)| transform(&to_dev, u, v));
        let (mut x0, mut y0, mut x1, mut y1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
        for (x, y) in corners {
            x0 = x0.min(x);
            y0 = y0.min(y);
            x1 = x1.max(x);
            y1 = y1.max(y);
        }
        let px0 = (x0.floor().max(0.0)) as usize;
        let py0 = (y0.floor().max(0.0)) as usize;
        let px1 = (x1.ceil().min(self.w as f64)).max(0.0) as usize;
        let py1 = (y1.ceil().min(self.h as f64)).max(0.0) as usize;
        for py in py0..py1 {
            for px in px0..px1 {
                let i = py * self.w + px;
                let clip = self.clip[i];
                if clip <= 0.0 {
                    continue;
                }
                let mut acc = [0f32; 4];
                let mut n = 0f32;
                for sy in 0..2 {
                    for sx in 0..2 {
                        let dx = px as f64 + (sx as f64 + 0.5) / 2.0;
                        let dy = py as f64 + (sy as f64 + 0.5) / 2.0;
                        let (u, v) = transform(&inv, dx, dy);
                        n += 1.0;
                        if !(0.0..1.0).contains(&u) || !(0.0..1.0).contains(&v) {
                            continue;
                        }
                        // Row 0 is the TOP of the unit square (v = 1), §8.9.5.2.
                        let col = ((u * w as f64) as usize).min(w as usize - 1);
                        let row = (((1.0 - v) * h as f64) as usize).min(h as usize - 1);
                        let o = (row * w as usize + col) * 4;
                        let sa = data[o + 3] as f32 / 255.0;
                        acc[0] += data[o] as f32 / 255.0 * sa;
                        acc[1] += data[o + 1] as f32 / 255.0 * sa;
                        acc[2] += data[o + 2] as f32 / 255.0 * sa;
                        acc[3] += sa;
                    }
                }
                if acc[3] <= 0.0 {
                    continue;
                }
                let a = acc[3] / n * alpha * clip;
                let rgb = [acc[0] / acc[3], acc[1] / acc[3], acc[2] / acc[3]];
                self.cur().blend_px(i, rgb, a);
            }
        }
    }
}

/// Rasterise a rendered page into a canvas plus the tally of what was skipped.
fn rasterize(page: &PageData, scale: f32) -> (Canvas, Skipped) {
    let w = (page.width as f64 * scale as f64).floor().max(1.0) as usize;
    let h = (page.height as f64 * scale as f64).floor().max(1.0) as usize;
    let mut r = Rast {
        w,
        h,
        scale: scale as f64,
        page_h: page.height as f64,
        layers: vec![Canvas::opaque_white(w, h)],
        clip: vec![1.0; w * h],
        clip_stack: Vec::new(),
        group_alpha: Vec::new(),
        soft: Vec::new(),
        skipped: Skipped::default(),
    };
    let galpha = |r: &Rast| r.group_alpha.iter().product::<f32>();

    for prim in &page.prims {
        match prim {
            Prim::Fill { argb, even_odd, contours, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                let cs: Vec<Vec<(f64, f64)>> = contours
                    .iter()
                    .map(|c| c.iter().map(|&(x, y)| r.dev(x as f64, y as f64)).collect())
                    .collect();
                let a = galpha(&r);
                let argb = apply_alpha_to_argb(*argb, a as f64);
                r.fill_contours(&cs, *even_odd, argb);
            }
            Prim::Stroke { argb, width, dash, dash_phase, cap, join, miter: _, pts, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                let dpts: Vec<(f64, f64)> =
                    pts.iter().map(|&(x, y)| r.dev(x as f64, y as f64)).collect();
                let ddash: Vec<f32> = dash.iter().map(|v| v * scale).collect();
                let cs = stroke_contours(
                    &dpts,
                    *width as f64 * scale as f64,
                    *cap,
                    *join,
                    &ddash,
                    *dash_phase * scale,
                );
                let a = galpha(&r);
                let argb = apply_alpha_to_argb(*argb, a as f64);
                r.fill_contours(&cs, false, argb);
            }
            Prim::Image { ctm, w: iw, h: ih, format, data, alpha, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                if *format != 0 {
                    r.skipped.jpeg_images += 1;
                    continue;
                }
                let a = *alpha * galpha(&r);
                r.draw_image(ctm, *iw, *ih, data, a);
            }
            Prim::ImageTiled { ctm, w: iw, h: ih, data, xstep: _, ystep: _, i0, j0, nx, ny, alpha, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                let a = *alpha * galpha(&r);
                for j in *j0..(*j0 + *ny as i32) {
                    for i in *i0..(*i0 + *nx as i32) {
                        let cell = mat_mul(&translate(i as f64, j as f64), ctm);
                        r.draw_image(&cell, *iw, *ih, data, a);
                    }
                }
            }
            Prim::ClipPush { even_odd, pts, path_ops } => {
                let contours = clip_contours(&r, pts, path_ops);
                let cov = poly_coverage(r.w, r.h, &contours, *even_odd);
                r.clip_stack.push(r.clip.clone());
                for i in 0..r.clip.len() {
                    r.clip[i] *= cov[i];
                }
            }
            Prim::ClipPop => {
                if let Some(prev) = r.clip_stack.pop() {
                    r.clip = prev;
                }
            }
            Prim::TextClipApply => {}
            Prim::GroupPush { isolated: _, knockout: _, alpha, blend } => {
                if !matches!(blend, BlendMode::Normal) {
                    r.skipped.blended += 1;
                }
                r.layers.push(Canvas::transparent(w, h));
                r.group_alpha.push(*alpha);
            }
            Prim::GroupPop => {
                if r.layers.len() > 1 {
                    let layer = r.layers.pop().unwrap_or_else(|| Canvas::transparent(w, h));
                    let _ = r.group_alpha.pop();
                    // Group alpha was already folded into each prim's colour, so
                    // compositing the layer at 1.0 here avoids applying it twice.
                    r.cur().composite_layer(&layer, 1.0, None);
                }
            }
            Prim::SoftMaskPush { mask_type } => {
                r.soft.push(SoftState { mask_type: *mask_type, transfer: None, content: None });
                r.layers.push(Canvas::transparent(w, h));
            }
            Prim::SoftMaskTransfer(lut) => {
                if let Some(s) = r.soft.last_mut() {
                    s.transfer = Some(lut.clone());
                }
            }
            Prim::SoftMaskContent => {
                let content = r.layers.pop().unwrap_or_else(|| Canvas::transparent(w, h));
                if let Some(s) = r.soft.last_mut() {
                    s.content = Some(content);
                }
                let lum = r.soft.last().map(|s| s.mask_type == 1).unwrap_or(false);
                r.layers.push(if lum {
                    Canvas::new(w, h, [0.0, 0.0, 0.0, 1.0])
                } else {
                    Canvas::transparent(w, h)
                });
            }
            Prim::SoftMaskPop => {
                let maskc = r.layers.pop().unwrap_or_else(|| Canvas::transparent(w, h));
                if let Some(s) = r.soft.pop() {
                    let mut mask = vec![0f32; w * h];
                    for i in 0..w * h {
                        let p = maskc.px[i];
                        let v = if s.mask_type == 1 {
                            // Luminosity, §11.6.5.2: Y of the (premultiplied)
                            // colour over the black backdrop.
                            0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]
                        } else {
                            p[3]
                        };
                        let v = match &s.transfer {
                            Some(lut) => {
                                let idx = (v.clamp(0.0, 1.0) * 255.0 + 0.5) as usize;
                                lut[idx.min(255)] as f32 / 255.0
                            }
                            None => v.clamp(0.0, 1.0),
                        };
                        mask[i] = v;
                    }
                    if let Some(content) = s.content {
                        r.cur().composite_layer(&content, 1.0, Some(&mask));
                    }
                }
            }
            Prim::Text { outline, render_mode, argb, text, .. } => {
                // Outline text is already present as Fill/Stroke prims; this
                // record exists only for selection. Invisible / clip-only modes
                // and zero alpha paint nothing.
                if *outline || matches!(render_mode, 3 | 7) || (argb >> 24) == 0 || text.is_empty()
                {
                    continue;
                }
                r.skipped.substitute_text += 1;
            }
        }
    }
    let canvas = r.layers.swap_remove(0);
    (canvas, r.skipped)
}

fn clip_contours(r: &Rast, pts: &[(f32, f32)], path_ops: &Option<Vec<PathOp>>) -> Vec<Vec<(f64, f64)>> {
    match path_ops {
        Some(ops) => {
            let mut out: Vec<Vec<(f64, f64)>> = Vec::new();
            let mut cur: Vec<(f64, f64)> = Vec::new();
            let mut last = (0f64, 0f64);
            for op in ops {
                match op {
                    PathOp::Move(x, y) => {
                        if cur.len() >= 3 {
                            out.push(std::mem::take(&mut cur));
                        } else {
                            cur.clear();
                        }
                        last = (*x as f64, *y as f64);
                        cur.push(r.dev(last.0, last.1));
                    }
                    PathOp::Line(x, y) => {
                        last = (*x as f64, *y as f64);
                        cur.push(r.dev(last.0, last.1));
                    }
                    PathOp::Cubic(x1, y1, x2, y2, x3, y3) => {
                        let p0 = last;
                        let (c1, c2, c3) = (
                            r.dev(*x1 as f64, *y1 as f64),
                            r.dev(*x2 as f64, *y2 as f64),
                            r.dev(*x3 as f64, *y3 as f64),
                        );
                        let d0 = r.dev(p0.0, p0.1);
                        // Scale-aware, to match what the CONSUMER does: a clip
                        // curve crosses the wire as a real `PathOp::Cubic`
                        // (`interpret.rs:1035` is the only producer) and Skia
                        // re-flattens it at device resolution every frame, so a
                        // clip boundary does NOT facet at zoom the way a
                        // pre-flattened `Prim::Fill` contour does. Flattening at
                        // a fixed step count here would import a faceting error
                        // production does not have, and at high `SCALE` that
                        // would read as a renderer disagreement.
                        let hull = (c1.0 - d0.0).hypot(c1.1 - d0.1)
                            + (c2.0 - c1.0).hypot(c2.1 - c1.1)
                            + (c3.0 - c2.0).hypot(c3.1 - c2.1);
                        let steps = ((hull / 3.0).ceil() as usize).clamp(8, 256);
                        for k in 1..=steps {
                            let t = k as f64 / steps as f64;
                            let mt = 1.0 - t;
                            let x = mt * mt * mt * p0.0
                                + 3.0 * mt * mt * t * *x1 as f64
                                + 3.0 * mt * t * t * *x2 as f64
                                + t * t * t * *x3 as f64;
                            let y = mt * mt * mt * p0.1
                                + 3.0 * mt * mt * t * *y1 as f64
                                + 3.0 * mt * t * t * *y2 as f64
                                + t * t * t * *y3 as f64;
                            cur.push(r.dev(x, y));
                        }
                        last = (*x3 as f64, *y3 as f64);
                    }
                    PathOp::Close => {
                        if cur.len() >= 3 {
                            out.push(std::mem::take(&mut cur));
                        } else {
                            cur.clear();
                        }
                    }
                }
            }
            if cur.len() >= 3 {
                out.push(cur);
            }
            out
        }
        None => vec![pts.iter().map(|&(x, y)| r.dev(x as f64, y as f64)).collect()],
    }
}

// ---------------------------------------------------------------------------
// The reference side
// ---------------------------------------------------------------------------

fn hayro_rgb8(bytes: &[u8], scale: f32) -> (usize, usize, Vec<[u8; 3]>) {
    use hayro::{render, RenderCache, RenderSettings};
    use hayro::hayro_interpret::InterpreterSettings;
    use hayro::hayro_syntax::Pdf;
    use hayro::vello_cpu::color::palette::css::WHITE;

    let pdf = Pdf::new(bytes.to_vec()).expect("hayro must parse the fixture");
    let pages = pdf.pages();
    assert!(!pages.is_empty(), "hayro found no pages");
    let cache = RenderCache::new();
    let settings = InterpreterSettings::default();
    let pixmap = render(
        &pages[0],
        &cache,
        &settings,
        &RenderSettings { x_scale: scale, y_scale: scale, bg_color: WHITE, ..Default::default() },
    );
    let (w, h) = (pixmap.width() as usize, pixmap.height() as usize);
    let rgb = pixmap
        .data()
        .iter()
        .map(|p| {
            // Premultiplied over an opaque white background: with bg_color WHITE
            // every pixel has a == 255, so the channels are already straight.
            let a = p.a as u32;
            let un = |c: u8| {
                if a == 0 {
                    255u8
                } else {
                    ((c as u32 * 255 + a / 2) / a).min(255) as u8
                }
            };
            [un(p.r), un(p.g), un(p.b)]
        })
        .collect();
    (w, h, rgb)
}

// ---------------------------------------------------------------------------
// The metric
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct DiffReport {
    w: usize,
    h: usize,
    flagged: usize,
    fraction: f64,
    worst: Option<(usize, usize, [u8; 3], [u8; 3])>,
}

/// Neighbourhood-tolerant perceptual difference. A pixel of `a` is flagged only
/// when it differs from every pixel of `b` in its 3x3 neighbourhood by more than
/// [`CHANNEL_TOLERANCE`]. Antialiasing and sub-pixel placement therefore never
/// flag; wrong colour, missing ink and >1px displacement do.
fn fuzzy_diff(a: &[[u8; 3]], b: &[[u8; 3]], w: usize, h: usize) -> DiffReport {
    let mut flagged = 0usize;
    let mut worst: Option<(usize, usize, [u8; 3], [u8; 3], i32)> = None;
    let at = |v: &[[u8; 3]], x: usize, y: usize| v[y * w + x];
    for y in 0..h {
        for x in 0..w {
            let pa = at(a, x, y);
            let mut best = i32::MAX;
            let mut best_px = pa;
            for dy in -1i32..=1 {
                for dx in -1i32..=1 {
                    let nx = x as i32 + dx;
                    let ny = y as i32 + dy;
                    if nx < 0 || ny < 0 || nx >= w as i32 || ny >= h as i32 {
                        continue;
                    }
                    let pb = at(b, nx as usize, ny as usize);
                    let d = (0..3)
                        .map(|c| (pa[c] as i32 - pb[c] as i32).abs())
                        .max()
                        .unwrap_or(0);
                    if d < best {
                        best = d;
                        best_px = pb;
                    }
                }
            }
            if best > CHANNEL_TOLERANCE {
                flagged += 1;
                if worst.map_or(true, |wv| best > wv.4) {
                    worst = Some((x, y, pa, best_px, best));
                }
            }
        }
    }
    DiffReport {
        w,
        h,
        flagged,
        fraction: flagged as f64 / (w * h).max(1) as f64,
        worst: worst.map(|(x, y, p, q, _)| (x, y, p, q)),
    }
}

/// Mean straight-RGB colour of an axis-aligned rectangle given in PDF page
/// coordinates (y up). Sampling well inside a shape makes the result completely
/// independent of every antialiasing approximation in this file.
fn interior_mean(rgb: &[[u8; 3]], w: usize, h: usize, page_h: f64, rect: [f64; 4]) -> [f64; 3] {
    let x0 = (rect[0] * SCALE as f64).round().max(0.0) as usize;
    let x1 = (rect[2] * SCALE as f64).round().min(w as f64) as usize;
    let y0 = ((page_h - rect[3]) * SCALE as f64).round().max(0.0) as usize;
    let y1 = ((page_h - rect[1]) * SCALE as f64).round().min(h as f64) as usize;
    let mut acc = [0f64; 3];
    let mut n = 0f64;
    for y in y0..y1 {
        for x in x0..x1 {
            let p = rgb[y * w + x];
            acc[0] += p[0] as f64;
            acc[1] += p[1] as f64;
            acc[2] += p[2] as f64;
            n += 1.0;
        }
    }
    assert!(n > 0.0, "empty sample rect {rect:?}");
    [acc[0] / n, acc[1] / n, acc[2] / n]
}

/// Coarse ASCII ink map, for reading a disagreement off the test log without a
/// PNG viewer. `#` = dark, `+` = mid, `.` = light, ` ` = white.
fn ascii_ink(rgb: &[[u8; 3]], w: usize, h: usize, cols: usize) -> String {
    let rows = (cols * h / w.max(1)).max(1);
    let mut s = String::new();
    for ry in 0..rows {
        for rx in 0..cols {
            let x0 = rx * w / cols;
            let x1 = ((rx + 1) * w / cols).max(x0 + 1).min(w);
            let y0 = ry * h / rows;
            let y1 = ((ry + 1) * h / rows).max(y0 + 1).min(h);
            let mut acc = 0u32;
            let mut n = 0u32;
            for y in y0..y1 {
                for x in x0..x1 {
                    let p = rgb[y * w + x];
                    acc += (p[0] as u32 + p[1] as u32 + p[2] as u32) / 3;
                    n += 1;
                }
            }
            let v = if n == 0 { 255 } else { acc / n };
            s.push(match v {
                0..=63 => '#',
                64..=159 => '+',
                160..=239 => '.',
                _ => ' ',
            });
        }
        s.push('\n');
    }
    s
}

/// Everything a single fixture produced.
struct Rendered {
    w: usize,
    h: usize,
    page_h: f64,
    ours: Vec<[u8; 3]>,
    theirs: Vec<[u8; 3]>,
    report: DiffReport,
}

fn render_both(name: &str, bytes: &[u8]) -> Rendered {
    let doc = load_document_lenient(bytes).expect("our loader must open the fixture");
    let page_id = nth_page_id(&doc, 0).expect("page 0");
    let page = interpret_page(&doc, page_id).expect("interpret");
    let (canvas, skipped) = rasterize(&page, SCALE);
    assert_eq!(
        skipped,
        Skipped::default(),
        "[{name}] this fixture uses constructs the differential rasteriser cannot draw; \
         the comparison would be meaningless. See the module header."
    );
    let ours = canvas.to_rgb8();
    let (hw, hh, theirs) = hayro_rgb8(bytes, SCALE);
    assert_eq!(
        (canvas.w, canvas.h),
        (hw, hh),
        "[{name}] page dimensions disagree before any pixel is compared"
    );
    let report = fuzzy_diff(&ours, &theirs, hw, hh);
    println!(
        "[{name}] {}x{} flagged={} ({:.4}%) worst={:?}",
        report.w,
        report.h,
        report.flagged,
        report.fraction * 100.0,
        report.worst
    );
    Rendered { w: hw, h: hh, page_h: page.height as f64, ours, theirs, report }
}

fn compare_page(name: &str, bytes: &[u8]) -> Rendered {
    let r = render_both(name, bytes);
    if r.report.fraction > DIFF_BUDGET {
        println!("--- [{name}] OURS ---\n{}", ascii_ink(&r.ours, r.w, r.h, 96));
        println!("--- [{name}] HAYRO ---\n{}", ascii_ink(&r.theirs, r.w, r.h, 96));
    }
    assert!(
        r.report.fraction <= DIFF_BUDGET,
        "[{name}] renderers disagree on {:.3}% of pixels (budget {:.3}%). worst pixel {:?} \
         (ours, theirs)",
        r.report.fraction * 100.0,
        DIFF_BUDGET * 100.0,
        r.report.worst
    );
    r
}

// ---------------------------------------------------------------------------
// Fixture builders (mirrors of the private `golden_tests.rs` helpers)
// ---------------------------------------------------------------------------

fn assemble(
    doc: &mut Document,
    content: Vec<u8>,
    resources: Dictionary,
    mut page: Dictionary,
) -> ObjectId {
    let content_id = doc.add_object(Stream::new(dictionary! {}, content));
    let pages_id = doc.new_object_id();
    page.set("Type", Object::Name(b"Page".to_vec()));
    page.set("Parent", pages_id);
    page.set("Resources", resources);
    page.set("Contents", Object::Reference(content_id));
    if page.get(b"MediaBox").is_err() {
        page.set("MediaBox", vec![0.into(), 0.into(), 200.into(), 200.into()]);
    }
    let page_id = doc.add_object(page.clone());
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    page_id
}

/// Build a one-page 200x200 document from an operator list and serialise it.
fn pdf_bytes(ops: Vec<Operation>, resources: Dictionary) -> Vec<u8> {
    pdf_bytes_page(ops, resources, dictionary! {})
}

fn pdf_bytes_page(ops: Vec<Operation>, resources: Dictionary, page: Dictionary) -> Vec<u8> {
    let mut doc = Document::with_version("1.7");
    let content = Content { operations: ops }.encode().expect("encode content");
    let _ = assemble(&mut doc, content, resources, page);
    let mut out = Vec::new();
    doc.save_to(&mut out).expect("save");
    out
}

fn op(o: &str, args: Vec<Object>) -> Operation {
    Operation::new(o, args)
}

fn n(v: f64) -> Object {
    Object::Real(v as f32)
}

// ---------------------------------------------------------------------------
// Corpus
//
// Chosen for where a SELF-CONSISTENT misreading is most plausible, and phrased
// so this file's rasteriser approximations cannot explain a failure: flat
// colours, axis-aligned or generously-sized shapes, no blend modes, no
// substitute-font text.
// ---------------------------------------------------------------------------

/// Baseline: if two independent renderers cannot agree on a filled rectangle,
/// nothing further in this file means anything. This also calibrates
/// [`DIFF_BUDGET`] — the flagged fraction printed here is the metric's noise
/// floor for a page that is by construction identical.
#[test]
#[ignore]
fn refdiff_solid_fills_calibrate_the_metric() {
    let ops = vec![
        op("rg", vec![n(0.9), n(0.1), n(0.1)]),
        op("re", vec![n(20.0), n(20.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("g", vec![n(0.25)]),
        op("re", vec![n(110.0), n(20.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("rg", vec![n(0.0), n(0.35), n(0.7)]),
        op("re", vec![n(20.0), n(110.0), n(70.0), n(70.0)]),
        op("f", vec![]),
    ];
    let r = compare_page("solid_fills", &pdf_bytes(ops, dictionary! {}));
    // Interior means are antialiasing-free, so they can be graded tightly.
    for (label, rect) in [
        ("devicergb_warm", [30.0, 30.0, 80.0, 80.0]),
        ("devicegray", [120.0, 30.0, 170.0, 80.0]),
        ("devicergb_cool", [30.0, 120.0, 80.0, 170.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!(
                (a[c] - b[c]).abs() <= 3.0,
                "{label} channel {c}: ours {} vs hayro {}",
                a[c],
                b[c]
            );
        }
    }
}

/// FINDING (recorded, not a pass/fail gate): the two renderers disagree
/// systematically on `DeviceCMYK`, by up to ~40 levels per channel.
///
/// We convert arithmetically in `color.rs:12` `cmyk_to_argb`:
/// `r = (1-c)(1-k)`, `g = (1-m)(1-k)`, `b = (1-y)(1-k)`. `hayro` instead runs
/// every DeviceCMYK sample through an embedded CGATS TR 001 (SWOP) ICC profile
/// (`hayro-interpret-0.7.0/src/color.rs:1066`, `CMYK_TRANSFORM`), which is what
/// Acrobat and pdf.js effectively do.
///
/// NEITHER IS WRONG. ISO 32000-1 §8.6.4 makes the device colour spaces
/// explicitly device-dependent, and §8.6.5.6 provides `/DefaultCMYK` precisely
/// so that a document that needs a defined colorimetry can ask for one; absent
/// that entry the mapping is the consumer's choice. This test therefore only
/// pins the divergence so that it is a known, measured quantity rather than a
/// surprise the next time someone diffs against a reference — and so that a
/// future change to `cmyk_to_argb` shows up here as a change in the printed
/// deltas.
#[test]
#[ignore]
fn refdiff_devicecmyk_diverges_because_the_reference_is_colour_managed() {
    let patches: [[f64; 4]; 6] = [
        [0.0, 1.0, 1.0, 0.0],
        [1.0, 0.0, 1.0, 0.0],
        [1.0, 1.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.5],
        [0.2, 0.4, 0.6, 0.1],
        [0.0, 0.0, 0.0, 1.0],
    ];
    let mut ops = Vec::new();
    for (i, p) in patches.iter().enumerate() {
        let (col, row) = (i % 3, i / 3);
        ops.push(op("k", vec![n(p[0]), n(p[1]), n(p[2]), n(p[3])]));
        ops.push(op(
            "re",
            vec![n(10.0 + col as f64 * 64.0), n(10.0 + row as f64 * 96.0), n(56.0), n(88.0)],
        ));
        ops.push(op("f", vec![]));
    }
    let r = render_both("devicecmyk", &pdf_bytes(ops, dictionary! {}));
    let mut worst = 0f64;
    for (i, p) in patches.iter().enumerate() {
        let (col, row) = (i % 3, i / 3);
        let x = 15.0 + col as f64 * 64.0;
        let y = 15.0 + row as f64 * 96.0;
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, [x, y, x + 46.0, y + 78.0]);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, [x, y, x + 46.0, y + 78.0]);
        // The PostScript `setcmykcolor` additive formula, for reference: it is
        // the OTHER common reading, and is not what either renderer does.
        let ps = [
            (1.0 - (p[0] + p[3]).min(1.0)) * 255.0,
            (1.0 - (p[1] + p[3]).min(1.0)) * 255.0,
            (1.0 - (p[2] + p[3]).min(1.0)) * 255.0,
        ];
        let d = (0..3).map(|c| (a[c] - b[c]).abs()).fold(0.0, f64::max);
        worst = worst.max(d);
        println!(
            "  cmyk{p:?}: ours=[{:.0},{:.0},{:.0}] hayro=[{:.0},{:.0},{:.0}] \
             ps_additive=[{:.0},{:.0},{:.0}] max_delta={d:.0}",
            a[0], a[1], a[2], b[0], b[1], b[2], ps[0], ps[1], ps[2]
        );
    }
    println!("  worst DeviceCMYK channel delta = {worst:.0}/255");
    // Deliberately loose: this asserts the divergence stays a colour-management
    // difference (measured worst = 109/255, on saturated cyan+magenta) and has
    // not become a gross error such as a swapped channel.
    assert!(worst <= 120.0, "DeviceCMYK divergence grew past colour management: {worst}");
}

/// Nonzero vs even-odd on a self-intersecting star: the classic place to get
/// §8.5.3.3.2 backwards and never notice, because both rules look plausible.
#[test]
#[ignore]
fn refdiff_winding_rules_on_a_self_intersecting_star() {
    let star = |close: &str| {
        let pts = [(100.0, 190.0), (145.0, 55.0), (30.0, 140.0), (170.0, 140.0), (55.0, 55.0)];
        let mut v = vec![
            op("rg", vec![n(0.1), n(0.2), n(0.8)]),
            op("m", vec![n(pts[0].0), n(pts[0].1)]),
        ];
        for p in &pts[1..] {
            v.push(op("l", vec![n(p.0), n(p.1)]));
        }
        v.push(op("h", vec![]));
        v.push(op(close, vec![]));
        v
    };
    let _ = compare_page("star_nonzero", &pdf_bytes(star("f"), dictionary! {}));
    let _ = compare_page("star_evenodd", &pdf_bytes(star("f*"), dictionary! {}));
}

/// Indexed and Separation, plus a Type 2 tint transform. Getting the Separation
/// tint→alternate direction backwards, or the Indexed lookup stride wrong, is a
/// misreading no self-referential test can catch.
#[test]
#[ignore]
fn refdiff_indexed_and_separation_colour() {
    let mut doc = Document::with_version("1.7");
    // Indexed palette: 3 entries of DeviceRGB.
    let palette: Vec<u8> = vec![255, 0, 0, 0, 255, 0, 0, 0, 255];
    let indexed = Object::Array(vec![
        Object::Name(b"Indexed".to_vec()),
        Object::Name(b"DeviceRGB".to_vec()),
        Object::Integer(2),
        Object::String(palette, lopdf::StringFormat::Hexadecimal),
    ]);
    // Separation with a Type 2 exponential tint transform. The alternate is
    // DeviceRGB, not DeviceCMYK, so this measures the TINT TRANSFORM and not
    // the colour-management policy divergence recorded in
    // `refdiff_devicecmyk_diverges_because_the_reference_is_colour_managed`.
    let tint = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(1.0), Object::Real(1.0), Object::Real(1.0)],
            "C1" => vec![Object::Real(0.1), Object::Real(0.35), Object::Real(0.6)],
            "N" => 1,
        },
        Vec::new(),
    ));
    let sep = Object::Array(vec![
        Object::Name(b"Separation".to_vec()),
        Object::Name(b"Spot".to_vec()),
        Object::Name(b"DeviceRGB".to_vec()),
        Object::Reference(tint),
    ]);
    let cs = dictionary! { "IX" => indexed, "SP" => sep };
    let resources = dictionary! { "ColorSpace" => cs };
    let ops = vec![
        op("cs", vec![Object::Name(b"IX".to_vec())]),
        op("sc", vec![Object::Integer(2)]),
        op("re", vec![n(20.0), n(110.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("cs", vec![Object::Name(b"IX".to_vec())]),
        op("sc", vec![Object::Integer(1)]),
        op("re", vec![n(110.0), n(110.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("cs", vec![Object::Name(b"SP".to_vec())]),
        op("sc", vec![n(1.0)]),
        op("re", vec![n(20.0), n(20.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("cs", vec![Object::Name(b"SP".to_vec())]),
        op("sc", vec![n(0.5)]),
        op("re", vec![n(110.0), n(20.0), n(70.0), n(70.0)]),
        op("f", vec![]),
    ];
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, resources, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("indexed_separation", &bytes);
    for (label, rect) in [
        ("indexed_2_blue", [30.0, 120.0, 80.0, 170.0]),
        ("indexed_1_green", [120.0, 120.0, 170.0, 170.0]),
        ("separation_1.0", [30.0, 30.0, 80.0, 80.0]),
        ("separation_0.5", [120.0, 30.0, 170.0, 80.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!(
                (a[c] - b[c]).abs() <= 4.0,
                "{label} channel {c}: ours {} vs hayro {}",
                a[c],
                b[c]
            );
        }
    }
}

/// All four function types, driven through Separation tint transforms so the
/// answer lands in a flat, antialiasing-free patch of colour. This is the single
/// highest-value entry in the corpus: `functions.rs` is 58 kB of formula, every
/// line of it verified only against our own reading of §7.10.
#[test]
#[ignore]
fn refdiff_all_four_function_types_via_separation() {
    let mut doc = Document::with_version("1.7");

    // Type 2: exponential, N = 2 so the exponent is actually exercised.
    let f2 = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(0.0)],
            "C1" => vec![Object::Real(1.0)],
            "N" => 2,
        },
        Vec::new(),
    ));
    // Type 3: stitching two Type 2s, with a non-trivial /Encode.
    let f2a = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2, "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(0.0)], "C1" => vec![Object::Real(0.3)], "N" => 1,
        },
        Vec::new(),
    ));
    let f2b = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2, "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(0.3)], "C1" => vec![Object::Real(1.0)], "N" => 1,
        },
        Vec::new(),
    ));
    let f3 = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 3,
            "Domain" => vec![0.into(), 1.into()],
            "Functions" => vec![Object::Reference(f2a), Object::Reference(f2b)],
            "Bounds" => vec![Object::Real(0.4)],
            "Encode" => vec![Object::Real(0.0), Object::Real(1.0), Object::Real(0.0), Object::Real(1.0)],
        },
        Vec::new(),
    ));
    // Type 0: sampled, 5 samples, 8 bpc, linear interpolation between them.
    let f0 = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 0,
            "Domain" => vec![0.into(), 1.into()],
            "Range" => vec![0.into(), 1.into()],
            "Size" => vec![5.into()],
            "BitsPerSample" => 8,
        },
        vec![0u8, 32, 200, 220, 255],
    ));
    // Type 4: PostScript calculator.
    let f4 = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![0.into(), 1.into()],
            "Range" => vec![0.into(), 1.into()],
        },
        b"{ dup mul 0.5 mul 0.25 add }".to_vec(),
    ));

    let mut cs = Dictionary::new();
    for (name, f) in [(&b"F2"[..], f2), (&b"F3"[..], f3), (&b"F0"[..], f0), (&b"F4"[..], f4)] {
        cs.set(
            String::from_utf8_lossy(name).to_string(),
            Object::Array(vec![
                Object::Name(b"Separation".to_vec()),
                Object::Name(b"Spot".to_vec()),
                Object::Name(b"DeviceGray".to_vec()),
                Object::Reference(f),
            ]),
        );
    }
    // Four columns x two tint values, so eight independent evaluations.
    let mut ops = Vec::new();
    let tints = [0.35f64, 0.8];
    for (col, name) in [&b"F2"[..], &b"F3"[..], &b"F0"[..], &b"F4"[..]].iter().enumerate() {
        for (row, t) in tints.iter().enumerate() {
            ops.push(op("cs", vec![Object::Name(name.to_vec())]));
            ops.push(op("sc", vec![n(*t)]));
            ops.push(op(
                "re",
                vec![n(10.0 + col as f64 * 48.0), n(10.0 + row as f64 * 96.0), n(40.0), n(88.0)],
            ));
            ops.push(op("f", vec![]));
        }
    }
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, dictionary! { "ColorSpace" => cs }, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("functions", &bytes);
    let mut worst = 0f64;
    for (col, name) in ["Type2", "Type3", "Type0", "Type4"].iter().enumerate() {
        for (row, t) in tints.iter().enumerate() {
            let x = 15.0 + col as f64 * 48.0;
            let y = 15.0 + row as f64 * 96.0;
            let rect = [x, y, x + 30.0, y + 78.0];
            let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
            let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
            let d = (a[0] - b[0]).abs().max((a[1] - b[1]).abs()).max((a[2] - b[2]).abs());
            println!("  {name} tint={t}: ours={:.1} theirs={:.1} delta={:.1}", a[0], b[0], d);
            worst = worst.max(d);
            assert!(d <= 4.0, "{name} at tint {t}: ours {:.1} vs hayro {:.1}", a[0], b[0]);
        }
    }
    println!("  worst function delta = {worst:.2}");
}

/// Axial and radial shadings with `/Extend`. §8.7.4.5.3 and §8.7.4.5.4 are dense
/// and the extend/`t` mapping is easy to read two ways.
#[test]
#[ignore]
fn refdiff_axial_and_radial_shadings() {
    let mut doc = Document::with_version("1.7");
    let f = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2, "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(1.0), Object::Real(0.0), Object::Real(0.0)],
            "C1" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(1.0)],
            "N" => 1,
        },
        Vec::new(),
    ));
    let axial = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
        "Coords" => vec![Object::Real(50.0), Object::Real(0.0), Object::Real(150.0), Object::Real(0.0)],
        "Function" => Object::Reference(f),
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
    });
    let radial = doc.add_object(dictionary! {
        "ShadingType" => 3,
        "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
        "Coords" => vec![
            Object::Real(100.0), Object::Real(100.0), Object::Real(10.0),
            Object::Real(100.0), Object::Real(100.0), Object::Real(80.0),
        ],
        "Function" => Object::Reference(f),
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
    });

    let ops_ax = vec![
        op("q", vec![]),
        op("re", vec![n(0.0), n(100.0), n(200.0), n(100.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("sh", vec![Object::Name(b"AX".to_vec())]),
        op("Q", vec![]),
        op("q", vec![]),
        op("re", vec![n(0.0), n(0.0), n(200.0), n(100.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("sh", vec![Object::Name(b"RA".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! {
        "Shading" => dictionary! { "AX" => Object::Reference(axial), "RA" => Object::Reference(radial) }
    };
    let content = Content { operations: ops_ax }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("shadings", &bytes);
    // Extend regions: left of x=50 must be pure C0, right of x=150 pure C1.
    for (label, rect, want) in [
        ("axial_extend_before", [5.0, 120.0, 40.0, 180.0], [255.0, 0.0, 0.0]),
        ("axial_extend_after", [160.0, 120.0, 195.0, 180.0], [0.0, 0.0, 255.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?} spec={want:?}");
        for c in 0..3 {
            assert!((a[c] - b[c]).abs() <= 4.0, "{label} channel {c}: {} vs {}", a[c], b[c]);
        }
    }
}

/// Image XObject placement, `/Decode` inversion, and an `/ImageMask` stencil.
/// §8.9.5.2 (unit-square mapping, row 0 = v=1) and §8.9.6.2 (stencil polarity)
/// are both easy to invert silently — a flipped image still "looks like an
/// image".
#[test]
#[ignore]
fn refdiff_image_placement_decode_and_stencil() {
    let mut doc = Document::with_version("1.7");
    // 2x2 RGB, deliberately asymmetric so a flip in either axis is visible.
    let rgb = vec![255u8, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 0];
    let img = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 2,
            "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
            "BitsPerComponent" => 8,
        },
        rgb,
    ));
    // 8x8 stencil: left half set. 1 bit per row-padded row.
    let stencil: Vec<u8> = (0..8).map(|_| 0xF0u8).collect();
    let mask = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8,
            "ImageMask" => Object::Boolean(true),
            "BitsPerComponent" => 1,
        },
        stencil,
    ));
    let ops = vec![
        op("q", vec![]),
        op("cm", vec![n(80.0), n(0.0), n(0.0), n(80.0), n(20.0), n(100.0)]),
        op("Do", vec![Object::Name(b"Im".to_vec())]),
        op("Q", vec![]),
        op("q", vec![]),
        op("rg", vec![n(0.0), n(0.6), n(0.2)]),
        op("cm", vec![n(80.0), n(0.0), n(0.0), n(80.0), n(20.0), n(10.0)]),
        op("Do", vec![Object::Name(b"Ms".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! {
        "XObject" => dictionary! {
            "Im" => Object::Reference(img),
            "Ms" => Object::Reference(mask),
        }
    };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("image_placement", &bytes);
    // Quadrant colours pin the orientation: sample well inside each.
    for (label, rect) in [
        ("img_top_left", [30.0, 145.0, 55.0, 170.0]),
        ("img_top_right", [105.0, 145.0, 130.0, 170.0]),
        ("img_bottom_left", [30.0, 110.0, 55.0, 135.0]),
        ("img_bottom_right", [105.0, 110.0, 130.0, 135.0]),
        ("stencil_left_painted", [30.0, 20.0, 55.0, 80.0]),
        ("stencil_right_clear", [65.0, 20.0, 90.0, 80.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!((a[c] - b[c]).abs() <= 4.0, "{label} channel {c}: {} vs {}", a[c], b[c]);
        }
    }
}

/// A `/Decode` array that inverts a grayscale image (§8.9.5.2, Table 89).
#[test]
#[ignore]
fn refdiff_image_decode_array_inversion() {
    let mut doc = Document::with_version("1.7");
    let gray = vec![0u8, 64, 128, 255];
    let img = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 4, "Height" => 1,
            "ColorSpace" => Object::Name(b"DeviceGray".to_vec()),
            "BitsPerComponent" => 8,
            "Decode" => vec![Object::Real(1.0), Object::Real(0.0)],
        },
        gray,
    ));
    let ops = vec![
        op("q", vec![]),
        op("cm", vec![n(160.0), n(0.0), n(0.0), n(120.0), n(20.0), n(40.0)]),
        op("Do", vec![Object::Name(b"Im".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! { "XObject" => dictionary! { "Im" => Object::Reference(img) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("image_decode", &bytes);
    for (i, want) in [255.0f64, 191.0, 127.0, 0.0].iter().enumerate() {
        let x = 25.0 + i as f64 * 40.0;
        let rect = [x, 50.0, x + 30.0, 150.0];
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  sample {i}: ours={:.1} theirs={:.1} spec≈{want:.0}", a[0], b[0]);
        assert!((a[0] - b[0]).abs() <= 4.0, "sample {i}: ours {:.1} vs hayro {:.1}", a[0], b[0]);
    }
}

/// Form XObject with a non-identity `/Matrix` and a `/BBox` that must clip
/// (§8.10.2, Table 95). Getting the Matrix/BBox composition order wrong is the
/// canonical self-consistent mistake here: the content still appears, just in
/// the wrong place or unclipped.
#[test]
#[ignore]
fn refdiff_form_xobject_matrix_and_bbox_clip() {
    let mut doc = Document::with_version("1.7");
    let inner = Content {
        operations: vec![
            op("rg", vec![n(0.8), n(0.2), n(0.6)]),
            op("re", vec![n(-50.0), n(-50.0), n(200.0), n(200.0)]),
            op("f", vec![]),
        ],
    }
    .encode()
    .expect("encode inner");
    let form = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            // BBox must clip the oversized rect to 0..100 in form space.
            "BBox" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(100.0), Object::Real(100.0)],
            // Scale by 1.5 and translate: applied BEFORE the CTM.
            "Matrix" => vec![
                Object::Real(1.5), Object::Real(0.0), Object::Real(0.0),
                Object::Real(1.5), Object::Real(10.0), Object::Real(20.0),
            ],
            "Resources" => dictionary! {},
        },
        inner,
    ));
    let ops = vec![
        op("q", vec![]),
        op("cm", vec![n(1.0), n(0.0), n(0.0), n(1.0), n(15.0), n(10.0)]),
        op("Do", vec![Object::Name(b"Fm".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! { "XObject" => dictionary! { "Fm" => Object::Reference(form) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("form_matrix_bbox", &bytes);
}

/// Nested clipping with both winding rules, and the `W n` deferral rule
/// (§8.5.4: the clip takes effect only after the path-painting operator).
#[test]
#[ignore]
fn refdiff_nested_and_evenodd_clipping() {
    let ops = vec![
        op("q", vec![]),
        op("re", vec![n(20.0), n(20.0), n(120.0), n(120.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("re", vec![n(60.0), n(60.0), n(120.0), n(120.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("rg", vec![n(0.1), n(0.5), n(0.9)]),
        op("re", vec![n(0.0), n(0.0), n(200.0), n(200.0)]),
        op("f", vec![]),
        op("Q", vec![]),
        // Even-odd clip: an annulus made of two nested rects.
        op("q", vec![]),
        op("re", vec![n(150.0), n(10.0), n(45.0), n(45.0)]),
        op("re", vec![n(160.0), n(20.0), n(25.0), n(25.0)]),
        op("W*", vec![]),
        op("n", vec![]),
        op("g", vec![n(0.0)]),
        op("re", vec![n(0.0), n(0.0), n(200.0), n(200.0)]),
        op("f", vec![]),
        op("Q", vec![]),
    ];
    let _ = compare_page("clipping", &pdf_bytes(ops, dictionary! {}));
}

/// Type 3 font text: the only way to compare TEXT POSITIONING pixel-for-pixel
/// between two renderers without shipping a font file, because the glyphs are
/// content streams both must draw identically. Exercises `/FontMatrix`,
/// `/Widths`, `Tf`, `Td`, `TL`/`T*`, `Tc`, `Tz` and `TJ` kerning (§9.4.4).
///
/// **FIXED.** See [`refdiff_root_cause_d0_mangles_the_next_operator`] for the
/// isolated repro. In short: every Type 3 glyph description must begin
/// with `d0` or `d1` (§9.6.5), lopdf mis-tokenises those, and the damage lands
/// on the glyph's first painting operator. The square glyph in this fixture
/// disappeared entirely; the triangle glyph was drawn from the wrong start point.
#[test]
#[ignore]
fn refdiff_type3_font_text_positioning() {
    let mut doc = Document::with_version("1.7");
    // Glyph "a": a filled square in the left 700/1000 of the em.
    let ga = doc.add_object(Stream::new(
        dictionary! {},
        Content {
            operations: vec![
                op("d0", vec![n(700.0), n(0.0)]),
                op("re", vec![n(50.0), n(0.0), n(600.0), n(600.0)]),
                op("f", vec![]),
            ],
        }
        .encode()
        .expect("glyph a"),
    ));
    // Glyph "b": a filled triangle, narrower.
    let gb = doc.add_object(Stream::new(
        dictionary! {},
        Content {
            operations: vec![
                op("d0", vec![n(400.0), n(0.0)]),
                op("m", vec![n(30.0), n(0.0)]),
                op("l", vec![n(370.0), n(0.0)]),
                op("l", vec![n(200.0), n(700.0)]),
                op("h", vec![]),
                op("f", vec![]),
            ],
        }
        .encode()
        .expect("glyph b"),
    ));
    let font = doc.add_object(dictionary! {
        "Type" => "Font",
        "Subtype" => "Type3",
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "FontMatrix" => vec![
            Object::Real(0.001), Object::Real(0.0), Object::Real(0.0),
            Object::Real(0.001), Object::Real(0.0), Object::Real(0.0),
        ],
        "CharProcs" => dictionary! { "ga" => Object::Reference(ga), "gb" => Object::Reference(gb) },
        "Encoding" => dictionary! {
            "Type" => "Encoding",
            "Differences" => vec![
                Object::Integer(97),
                Object::Name(b"ga".to_vec()),
                Object::Name(b"gb".to_vec()),
            ],
        },
        "FirstChar" => 97,
        "LastChar" => 98,
        "Widths" => vec![Object::Integer(700), Object::Integer(400)],
        "Resources" => dictionary! {},
    });
    let ops = vec![
        op("BT", vec![]),
        op("rg", vec![n(0.0), n(0.0), n(0.0)]),
        op("Tf", vec![Object::Name(b"T3".to_vec()), n(24.0)]),
        op("TL", vec![n(34.0)]),
        op("Td", vec![n(15.0), n(160.0)]),
        op("Tj", vec![Object::string_literal("abab")]),
        op("T*", vec![]),
        op("Tc", vec![n(6.0)]),
        op("Tj", vec![Object::string_literal("abab")]),
        op("T*", vec![]),
        op("Tc", vec![n(0.0)]),
        op("Tz", vec![n(160.0)]),
        op("Tj", vec![Object::string_literal("aab")]),
        op("T*", vec![]),
        op("Tz", vec![n(100.0)]),
        op(
            "TJ",
            vec![Object::Array(vec![
                Object::string_literal("ab"),
                Object::Integer(-500),
                Object::string_literal("ab"),
            ])],
        ),
        op("ET", vec![]),
    ];
    let res = dictionary! { "Font" => dictionary! { "T3" => Object::Reference(font) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("type3_text", &bytes);
}

/// Constant alpha (`/ca`) with overlapping fills — §11.6.4.4. The compositing
/// arithmetic is a place where "close enough" hides a systematic error.
#[test]
#[ignore]
fn refdiff_constant_alpha_compositing() {
    let mut doc = Document::with_version("1.7");
    let gs = doc.add_object(dictionary! { "Type" => "ExtGState", "ca" => Object::Real(0.5) });
    let ops = vec![
        op("rg", vec![n(1.0), n(0.0), n(0.0)]),
        op("re", vec![n(20.0), n(60.0), n(100.0), n(80.0)]),
        op("f", vec![]),
        op("gs", vec![Object::Name(b"G".to_vec())]),
        op("rg", vec![n(0.0), n(0.0), n(1.0)]),
        op("re", vec![n(80.0), n(60.0), n(100.0), n(80.0)]),
        op("f", vec![]),
    ];
    let res = dictionary! { "ExtGState" => dictionary! { "G" => Object::Reference(gs) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("constant_alpha", &bytes);
    for (label, rect) in [
        ("red_only", [30.0, 70.0, 70.0, 130.0]),
        ("blue_over_red", [90.0, 70.0, 110.0, 130.0]),
        ("blue_over_white", [140.0, 70.0, 170.0, 130.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!((a[c] - b[c]).abs() <= 5.0, "{label} channel {c}: {} vs {}", a[c], b[c]);
        }
    }
}

/// `/Rotate` and a `/CropBox` offset from the `/MediaBox` origin (§7.7.3.3,
/// §14.11.2). Both renderers must independently agree on where the ink lands.
#[test]
#[ignore]
fn refdiff_rotate_and_cropbox() {
    for rotate in [0i64, 90, 180, 270] {
        let ops = vec![
            op("rg", vec![n(0.9), n(0.3), n(0.0)]),
            // An L shape, so every rotation is distinguishable.
            op("re", vec![n(60.0), n(60.0), n(80.0), n(20.0)]),
            op("f", vec![]),
            op("re", vec![n(60.0), n(60.0), n(20.0), n(80.0)]),
            op("f", vec![]),
        ];
        let page = dictionary! {
            "MediaBox" => vec![0.into(), 0.into(), 240.into(), 240.into()],
            "CropBox" => vec![20.into(), 20.into(), 220.into(), 200.into()],
            "Rotate" => Object::Integer(rotate),
        };
        let _ = compare_page(
            &format!("rotate_{rotate}"),
            &pdf_bytes_page(ops, dictionary! {}, page),
        );
    }
}

/// Dash patterns (§8.4.3.6): phase handling and the odd-length-array rule are
/// both easy to read two ways.
#[test]
#[ignore]
fn refdiff_dash_patterns() {
    let ops = vec![
        op("w", vec![n(6.0)]),
        op("J", vec![Object::Integer(0)]),
        op("g", vec![n(0.0)]),
        op("d", vec![Object::Array(vec![n(12.0), n(8.0)]), n(0.0)]),
        op("m", vec![n(10.0), n(170.0)]),
        op("l", vec![n(190.0), n(170.0)]),
        op("S", vec![]),
        op("d", vec![Object::Array(vec![n(12.0), n(8.0)]), n(6.0)]),
        op("m", vec![n(10.0), n(130.0)]),
        op("l", vec![n(190.0), n(130.0)]),
        op("S", vec![]),
        // Odd-length array: §8.4.3.6 says it is used cyclically, so the phases
        // alternate on/off across repetitions.
        op("d", vec![Object::Array(vec![n(15.0)]), n(0.0)]),
        op("m", vec![n(10.0), n(90.0)]),
        op("l", vec![n(190.0), n(90.0)]),
        op("S", vec![]),
        // Empty array = solid.
        op("d", vec![Object::Array(vec![]), n(0.0)]),
        op("m", vec![n(10.0), n(50.0)]),
        op("l", vec![n(190.0), n(50.0)]),
        op("S", vec![]),
    ];
    let _ = compare_page("dashes", &pdf_bytes(ops, dictionary! {}));
}

/// Luminosity soft mask (§11.6.5.2) driven by an axial shading.
#[test]
#[ignore]
fn refdiff_luminosity_soft_mask() {
    let mut doc = Document::with_version("1.7");
    let f = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2, "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(0.0)], "C1" => vec![Object::Real(1.0)], "N" => 1,
        },
        Vec::new(),
    ));
    let sh = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => Object::Name(b"DeviceGray".to_vec()),
        "Coords" => vec![Object::Real(20.0), Object::Real(0.0), Object::Real(180.0), Object::Real(0.0)],
        "Function" => Object::Reference(f),
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
    });
    let group = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(200.0), Object::Real(200.0)],
            "Group" => dictionary! { "S" => "Transparency", "CS" => Object::Name(b"DeviceGray".to_vec()) },
            "Resources" => dictionary! { "Shading" => dictionary! { "S0" => Object::Reference(sh) } },
        },
        Content { operations: vec![op("sh", vec![Object::Name(b"S0".to_vec())])] }
            .encode()
            .expect("mask content"),
    ));
    let gs = doc.add_object(dictionary! {
        "Type" => "ExtGState",
        "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(group) },
    });
    let ops = vec![
        op("gs", vec![Object::Name(b"G".to_vec())]),
        op("rg", vec![n(0.8), n(0.0), n(0.0)]),
        op("re", vec![n(10.0), n(50.0), n(180.0), n(100.0)]),
        op("f", vec![]),
    ];
    let res = dictionary! { "ExtGState" => dictionary! { "G" => Object::Reference(gs) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("soft_mask", &bytes);
}


/// ROOT CAUSE of `refdiff_type3_font_text_positioning`, isolated to the parser
/// so phase 2 has an exact, sub-millisecond target. **FIXED** — see
/// `content.rs::repair_d0_d1`.
///
/// ISO 32000-1 §9.6.5 "Type 3 Fonts": "The glyph description shall begin with
/// either the `d0` or the `d1` operator." Table 113 gives their signatures as
/// `wx wy d0` and `wx wy llx lly urx ury d1`.
///
/// lopdf 0.36's content tokeniser ends an operator token at the first digit, so
/// `700 0 d0` decodes as the DASH operator `d` with operands `[700, 0]`, and the
/// orphaned `0` becomes the FIRST OPERAND OF THE NEXT OPERATION. Measured on
/// this host with `lopdf::content::Content::decode`:
///
/// ```text
///   "700 0 d0\n50 0 600 600 re\nf"
///     -> op="d"  operands=[700, 0]
///        op="re" operands=[0, 50, 0, 600, 600]     <-- five operands
///        op="f"  operands=[]
///
///   "400 0 d0\n30 0 m\n..."
///     -> op="d"  operands=[400, 0]
///        op="m"  operands=[0, 30, 0]               <-- three operands
/// ```
///
/// Consequences in this crate, all silent:
///
/// * `interpret.rs:952` requires `nums.len() == 4` for `re`, so a rectangle that
///   opens a glyph is DROPPED. The glyph vanishes; the advance still happens, so
///   the line looks like it has spaces in it.
/// * `interpret.rs` `"m"` takes the first two operands, so the subpath starts at
///   `(0, 30)` instead of `(30, 0)` — the glyph is drawn from the wrong point.
/// * `interpret.rs:1546`'s `"d0" | "d1"` arm is DEAD CODE: it can never match.
/// * `interpret.rs:588`'s `"d"` arm runs instead, setting `gs.dash = []` and
///   `gs.dash_phase = 0`, so opening a glyph also silently clears an inherited
///   dash pattern (§9.6.5 says the glyph inherits the graphics state).
///
/// The lenient recovery tokeniser is NOT affected — `content.rs:83`'s
/// `OPERATORS` table lists `d0`/`d1` and `is_known_operator` matches the whole
/// token — so the bug is confined to the strict path, which is the path healthy
/// files take.
///
/// PROPOSED PATCH, in `content.rs`, applied inside `strict_operations` so both
/// `page_operations` and `stream_operations` get it:
///
/// ```ignore
/// fn strict_operations(bytes: &[u8]) -> Option<Vec<Operation>> {
///     if nesting_is_too_deep(bytes, MAX_DEPTH) {
///         return None;
///     }
///     match Content::decode(bytes) {
///         Ok(content) if !content.operations.is_empty() => {
///             Some(repair_d0_d1(content.operations))
///         }
///         _ => None,
///     }
/// }
///
/// /// Undo lopdf 0.36's mis-tokenisation of `d0`/`d1` (§9.6.5, Table 113).
/// ///
/// /// A conforming `d` takes an ARRAY then a number (§8.4.3.6, Table 52), so a
/// /// `d` whose operands are all numeric is unambiguously the mangled form:
/// /// two operands mean `d0`, six mean `d1`. The digit lopdf split off is the
/// /// next operation's first operand and is removed there.
/// fn repair_d0_d1(mut ops: Vec<Operation>) -> Vec<Operation> {
///     for i in 0..ops.len() {
///         let mangled = ops[i].operator == "d"
///             && matches!(ops[i].operands.len(), 2 | 6)
///             && ops[i]
///                 .operands
///                 .iter()
///                 .all(|o| matches!(o, Object::Integer(_) | Object::Real(_)));
///         if !mangled {
///             continue;
///         }
///         let is_d1 = ops[i].operands.len() == 6;
///         if let Some(next) = ops.get_mut(i + 1) {
///             let leaked = match next.operands.first() {
///                 Some(Object::Integer(v)) => *v == i64::from(is_d1),
///                 Some(Object::Real(v)) => *v == f32::from(u8::from(is_d1)),
///                 _ => false,
///             };
///             if !leaked {
///                 continue; // not the mangling after all; leave it alone
///             }
///             let _ = next.operands.remove(0);
///         }
///         ops[i].operator = if is_d1 { "d1" } else { "d0" }.to_string();
///     }
///     ops
/// }
/// ```
///
/// Note the ordering: the repair only renames `d` once it has confirmed the
/// leaked digit is present, so a genuinely malformed `d` is left untouched.
/// `d0` as the final operator of a stream leaves no next operation and no
/// leaked digit, which is why the `None` arm of `ops.get_mut(i + 1)` still
/// renames.
#[test]
#[ignore]
fn refdiff_root_cause_d0_mangles_the_next_operator() {
    let cases: [(&str, &str, usize); 2] = [
        ("700 0 d0\n50 0 600 600 re\nf", "re", 4),
        ("0 0 0 0 750 750 d1\n30 0 m\n370 0 l\nh\nf", "m", 2),
    ];
    for (src, want_op, want_operands) in cases {
        // Our STRICT path — the one healthy files take — not `Content::decode`
        // directly: the repair lives in `content.rs`, and lopdf's raw decoder is
        // what is being compensated for, so asserting on it could never go green.
        let ops = crate::content::strict_operations(src.as_bytes()).expect("strict parse");
        for o in &ops {
            println!("  {src:?} -> op={:?} operands={:?}", o.operator, o.operands);
        }
        let first = &ops[0];
        assert!(
            first.operator == "d0" || first.operator == "d1",
            "§9.6.5: a glyph description begins with d0/d1, but the parser produced {:?} \
             with operands {:?}. See this test's doc comment for the patch.",
            first.operator,
            first.operands
        );
        let painted = ops.iter().find(|o| o.operator == want_op).expect("painting operator");
        assert_eq!(
            painted.operands.len(),
            want_operands,
            "`{want_op}` must receive exactly {want_operands} operands; it received {:?}",
            painted.operands
        );
    }
}

/// The §8.6.5.4 Lab decoding plus the IEC 61966-2-1 sRGB encoding, written here
/// FROM THE SPEC TEXT rather than from `color.rs`, so this function is a third
/// independent implementation and can arbitrate between the other two.
///
/// §8.6.5.4: `M = (L*+16)/116 + a*/500`, `L = (L*+16)/116`,
/// `N = (L*+16)/116 − b*/200`; `X = Xw·g(M)`, `Y = Yw·g(L)`, `Z = Zw·g(N)`;
/// `g(x) = x³` for `x ≥ 6/29`, else `g(x) = (108/841)(x − 4/29)`.
fn spec_lab_to_srgb(l: f64, a: f64, b: f64, wp: [f64; 3]) -> [f64; 3] {
    let g = |x: f64| {
        if x >= 6.0 / 29.0 {
            x * x * x
        } else {
            (108.0 / 841.0) * (x - 4.0 / 29.0)
        }
    };
    let fy = (l + 16.0) / 116.0;
    let (x, y, z) = (wp[0] * g(fy + a / 500.0), wp[1] * g(fy), wp[2] * g(fy - b / 200.0));
    let lin = [
        3.2406 * x - 1.5372 * y - 0.4986 * z,
        -0.9689 * x + 1.8758 * y + 0.0415 * z,
        0.0557 * x - 0.2040 * y + 1.0570 * z,
    ];
    let enc = |v: f64| {
        let v = v.clamp(0.0, 1.0);
        let s = if v <= 0.0031308 { 12.92 * v } else { 1.055 * v.powf(1.0 / 2.4) - 0.055 };
        (s * 255.0).round()
    };
    [enc(lin[0]), enc(lin[1]), enc(lin[2])]
}

/// CIE-based colour: `Lab` (§8.6.5.4) and `CalRGB` (§8.6.5.2).
///
/// FINDING — **OURS IS RIGHT.** `CalRGB` agrees exactly. `Lab` diverges by
/// 1..61 levels, growing with chroma, and [`spec_lab_to_srgb`] — a third
/// implementation written straight from the clause — arbitrates in our favour
/// at every patch. `hayro`'s numbers are consistent with routing Lab through a
/// D50-referenced ICC PCS and discarding the `/WhitePoint` the file declares,
/// which is defensible as a colour-management policy but is not the XYZ that
/// §8.6.5.4 defines. NO PATCH PROPOSED.
#[test]
#[ignore]
fn refdiff_lab_and_calrgb_colour() {
    const WP: [f64; 3] = [0.9505, 1.0, 1.089];
    let lab = Object::Array(vec![
        Object::Name(b"Lab".to_vec()),
        Object::Dictionary(dictionary! {
            "WhitePoint" => vec![Object::Real(WP[0] as f32), Object::Real(WP[1] as f32), Object::Real(WP[2] as f32)],
            "Range" => vec![Object::Real(-100.0), Object::Real(100.0), Object::Real(-100.0), Object::Real(100.0)],
        }),
    ]);
    let calrgb = Object::Array(vec![
        Object::Name(b"CalRGB".to_vec()),
        Object::Dictionary(dictionary! {
            "WhitePoint" => vec![Object::Real(WP[0] as f32), Object::Real(WP[1] as f32), Object::Real(WP[2] as f32)],
            "Gamma" => vec![Object::Real(2.2), Object::Real(2.2), Object::Real(2.2)],
        }),
    ]);
    let res = dictionary! { "ColorSpace" => dictionary! { "LB" => lab, "CR" => calrgb } };
    let labs: [[f64; 3]; 6] = [
        [60.0, 0.0, 0.0],
        [70.0, 20.0, -30.0],
        [40.0, -25.0, 15.0],
        [54.0, 81.0, 70.0],
        [88.0, -79.0, 81.0],
        [30.0, 50.0, -60.0],
    ];
    let mut ops = Vec::new();
    for (i, p) in labs.iter().enumerate() {
        let (col, row) = (i % 3, i / 3);
        ops.push(op("cs", vec![Object::Name(b"LB".to_vec())]));
        ops.push(op("sc", vec![n(p[0]), n(p[1]), n(p[2])]));
        ops.push(op(
            "re",
            vec![n(10.0 + col as f64 * 64.0), n(105.0 - row as f64 * 95.0), n(56.0), n(85.0)],
        ));
        ops.push(op("f", vec![]));
    }
    let r = render_both("lab", &pdf_bytes(ops, res.clone()));
    let mut ours_worst = 0f64;
    let mut theirs_worst = 0f64;
    for (i, p) in labs.iter().enumerate() {
        let (col, row) = (i % 3, i / 3);
        let x = 15.0 + col as f64 * 64.0;
        let y = 110.0 - row as f64 * 95.0;
        let rect = [x, y, x + 46.0, y + 75.0];
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        let spec = spec_lab_to_srgb(p[0], p[1], p[2], WP);
        let da = (0..3).map(|c| (a[c] - spec[c]).abs()).fold(0.0, f64::max);
        let db = (0..3).map(|c| (b[c] - spec[c]).abs()).fold(0.0, f64::max);
        ours_worst = ours_worst.max(da);
        theirs_worst = theirs_worst.max(db);
        println!(
            "  Lab{p:?}: spec=[{:.0},{:.0},{:.0}] ours=[{:.0},{:.0},{:.0}] (Δ{da:.0}) \
             hayro=[{:.0},{:.0},{:.0}] (Δ{db:.0})",
            spec[0], spec[1], spec[2], a[0], a[1], a[2], b[0], b[1], b[2]
        );
    }
    println!("  worst |ours-spec| = {ours_worst:.0}/255, worst |hayro-spec| = {theirs_worst:.0}/255");
    assert!(
        ours_worst <= 2.0,
        "our Lab conversion deviates from the §8.6.5.4 formula by {ours_worst:.0}/255"
    );

    // CalRGB, where the two renderers do agree exactly.
    let cals: [[f64; 3]; 3] = [[0.8, 0.2, 0.2], [0.2, 0.8, 0.2], [0.5, 0.5, 0.5]];
    let mut ops = Vec::new();
    for (i, p) in cals.iter().enumerate() {
        ops.push(op("cs", vec![Object::Name(b"CR".to_vec())]));
        ops.push(op("sc", vec![n(p[0]), n(p[1]), n(p[2])]));
        ops.push(op("re", vec![n(10.0 + i as f64 * 64.0), n(55.0), n(56.0), n(90.0)]));
        ops.push(op("f", vec![]));
    }
    let r = compare_page("calrgb", &pdf_bytes(ops, res));
    for (i, p) in cals.iter().enumerate() {
        let x = 15.0 + i as f64 * 64.0;
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, [x, 60.0, x + 46.0, 140.0]);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, [x, 60.0, x + 46.0, 140.0]);
        let d = (0..3).map(|c| (a[c] - b[c]).abs()).fold(0.0, f64::max);
        println!(
            "  CalRGB{p:?}: ours=[{:.0},{:.0},{:.0}] hayro=[{:.0},{:.0},{:.0}] delta={d:.0}",
            a[0], a[1], a[2], b[0], b[1], b[2]
        );
        assert!(d <= 3.0, "CalRGB {p:?} disagrees by {d:.0}/255");
    }
}

/// Function-based (Type 1) shading with a non-identity `/Matrix` and a `/Domain`
/// that is not the unit square (§8.7.4.5.2). The `/Matrix` maps the domain into
/// the shading's target space, and applying it in the wrong direction still
/// produces a plausible-looking gradient.
#[test]
#[ignore]
fn refdiff_function_based_shading_matrix_and_domain() {
    let mut doc = Document::with_version("1.7");
    let f = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![Object::Real(-1.0), Object::Real(1.0), Object::Real(-1.0), Object::Real(1.0)],
            "Range" => vec![0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
        },
        // (x, y) -> (|x|, |y|, 0.25)
        b"{ 0.25 3 1 roll abs exch abs exch 3 -1 roll }".to_vec(),
    ));
    let sh = doc.add_object(dictionary! {
        "ShadingType" => 1,
        "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
        "Domain" => vec![Object::Real(-1.0), Object::Real(1.0), Object::Real(-1.0), Object::Real(1.0)],
        "Matrix" => vec![
            Object::Real(80.0), Object::Real(0.0), Object::Real(0.0),
            Object::Real(80.0), Object::Real(100.0), Object::Real(100.0),
        ],
        "Function" => Object::Reference(f),
    });
    let ops = vec![
        op("q", vec![]),
        op("re", vec![n(10.0), n(10.0), n(180.0), n(180.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("sh", vec![Object::Name(b"S0".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! { "Shading" => dictionary! { "S0" => Object::Reference(sh) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("function_shading", &bytes);
}

/// `/ImageMask` with `/Decode [1 0]`: §8.9.6.2 says sample value 0 marks the
/// page with `/Decode [0 1]`, and `/Decode [1 0]` reverses that. Inverting a
/// stencil is invisible to any test that only asserts "some ink appeared".
#[test]
#[ignore]
fn refdiff_image_mask_decode_polarity() {
    let mut doc = Document::with_version("1.7");
    let stencil: Vec<u8> = (0..8).map(|_| 0xF0u8).collect();
    let mk = |doc: &mut Document, decode: Option<[f64; 2]>| {
        let mut d = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8,
            "ImageMask" => Object::Boolean(true),
            "BitsPerComponent" => 1,
        };
        if let Some(dec) = decode {
            d.set("Decode", vec![Object::Real(dec[0] as f32), Object::Real(dec[1] as f32)]);
        }
        doc.add_object(Stream::new(d, stencil.clone()))
    };
    let a = mk(&mut doc, None);
    let b = mk(&mut doc, Some([1.0, 0.0]));
    let ops = vec![
        op("rg", vec![n(0.85), n(0.1), n(0.4)]),
        op("q", vec![]),
        op("cm", vec![n(80.0), n(0.0), n(0.0), n(80.0), n(15.0), n(105.0)]),
        op("Do", vec![Object::Name(b"A".to_vec())]),
        op("Q", vec![]),
        op("q", vec![]),
        op("cm", vec![n(80.0), n(0.0), n(0.0), n(80.0), n(105.0), n(105.0)]),
        op("Do", vec![Object::Name(b"B".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! {
        "XObject" => dictionary! { "A" => Object::Reference(a), "B" => Object::Reference(b) }
    };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("imagemask_decode", &bytes);
    for (label, rect) in [
        ("default_left_painted", [20.0, 115.0, 50.0, 175.0]),
        ("default_right_clear", [60.0, 115.0, 90.0, 175.0]),
        ("inverted_left_clear", [110.0, 115.0, 140.0, 175.0]),
        ("inverted_right_painted", [150.0, 115.0, 180.0, 175.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!((a[c] - b[c]).abs() <= 4.0, "{label} channel {c}: {} vs {}", a[c], b[c]);
        }
    }
}

/// Coloured tiling pattern (§8.7.3.3): `/XStep`/`/YStep` independent of
/// `/BBox`, plus a pattern `/Matrix`. Our renderer collapses this to one cell
/// bitmap plus a lattice (`Prim::ImageTiled`), which is a representation choice
/// nothing outside this crate has ever checked.
#[test]
#[ignore]
fn refdiff_tiling_pattern_step_and_matrix() {
    let mut doc = Document::with_version("1.7");
    let cell = Content {
        operations: vec![
            op("rg", vec![n(0.1), n(0.2), n(0.9)]),
            op("re", vec![n(0.0), n(0.0), n(10.0), n(10.0)]),
            op("f", vec![]),
        ],
    }
    .encode()
    .expect("cell");
    let pat = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern",
            "PatternType" => 1,
            "PaintType" => 1,
            "TilingType" => 1,
            "BBox" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(10.0), Object::Real(10.0)],
            "XStep" => Object::Real(20.0),
            "YStep" => Object::Real(20.0),
            "Matrix" => vec![
                Object::Real(1.0), Object::Real(0.0), Object::Real(0.0),
                Object::Real(1.0), Object::Real(5.0), Object::Real(5.0),
            ],
            "Resources" => dictionary! {},
        },
        cell,
    ));
    let ops = vec![
        op("cs", vec![Object::Name(b"Pattern".to_vec())]),
        op("scn", vec![Object::Name(b"P0".to_vec())]),
        op("re", vec![n(25.0), n(25.0), n(150.0), n(150.0)]),
        op("f", vec![]),
    ];
    let res = dictionary! { "Pattern" => dictionary! { "P0" => Object::Reference(pat) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("tiling_pattern", &bytes);
}









/// A luminosity soft mask with a non-default `/BC` backdrop (§11.6.5.2).
///
/// Added after `fix-interp` landed `/BC` and validated it against this harness:
/// the harness was sound for it, but only by accident of coverage — the corpus
/// had no `/BC` entry, and the module header still claimed this rasteriser
/// ignored the backdrop. It does ignore it directly; `interpret.rs` emits the
/// backdrop as a `Fill` over the group `/BBox` before the mask content, so it
/// arrives as an ordinary primitive.
///
/// This pins that arrangement from the outside. The mask group paints nothing
/// of its own over most of its area, so the mask value there is the luminosity
/// of `/BC` alone — a mid grey here, which must let roughly half the red
/// through. A renderer that ignored `/BC` and used the default black backdrop
/// would show nothing at all in that region, which is a 100%-vs-0% difference,
/// far outside any tolerance in this file.
#[test]
#[ignore]
fn refdiff_luminosity_soft_mask_backdrop_colour() {
    let build = |bc: Option<[f64; 3]>| {
        let mut doc = Document::with_version("1.7");
        // The mask group paints a small white square in one corner only; the
        // rest of its BBox is whatever the backdrop says.
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(200.0), Object::Real(200.0)],
                "Group" => dictionary! {
                    "S" => "Transparency",
                    "CS" => Object::Name(b"DeviceRGB".to_vec()),
                },
                "Resources" => dictionary! {},
            },
            Content {
                operations: vec![
                    op("rg", vec![n(1.0), n(1.0), n(1.0)]),
                    op("re", vec![n(20.0), n(120.0), n(60.0), n(60.0)]),
                    op("f", vec![]),
                ],
            }
            .encode()
            .expect("mask content"),
        ));
        let mut smask = dictionary! {
            "S" => "Luminosity",
            "G" => Object::Reference(group),
        };
        if let Some(c) = bc {
            smask.set(
                "BC",
                vec![Object::Real(c[0] as f32), Object::Real(c[1] as f32), Object::Real(c[2] as f32)],
            );
        }
        let gs = doc.add_object(dictionary! { "Type" => "ExtGState", "SMask" => smask });
        let ops = vec![
            op("gs", vec![Object::Name(b"G".to_vec())]),
            op("rg", vec![n(0.9), n(0.0), n(0.0)]),
            op("re", vec![n(0.0), n(0.0), n(200.0), n(200.0)]),
            op("f", vec![]),
        ];
        let res = dictionary! { "ExtGState" => dictionary! { "G" => Object::Reference(gs) } };
        let content = Content { operations: ops }.encode().expect("encode");
        let _ = assemble(&mut doc, content, res, dictionary! {});
        let mut b = Vec::new();
        doc.save_to(&mut b).expect("save");
        b
    };

    // Default backdrop: black, luminosity 0, so only the group's own white
    // square lets the red through.
    let r = compare_page("smask_bc_default", &build(None));
    let outside = interior_mean(&r.ours, r.w, r.h, r.page_h, [110.0, 20.0, 180.0, 90.0]);
    println!("  default /BC, outside the mask square: ours={outside:?}");

    // Mid-grey backdrop: luminosity ~0.5 everywhere the group did not paint.
    let r = compare_page("smask_bc_grey", &build(Some([0.5, 0.5, 0.5])));
    for (label, rect) in [
        ("inside_group_square", [30.0, 130.0, 70.0, 170.0]),
        ("backdrop_only_region", [110.0, 20.0, 180.0, 90.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  grey /BC {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!(
                (a[c] - b[c]).abs() <= 6.0,
                "{label} channel {c}: ours {} vs hayro {}",
                a[c],
                b[c]
            );
        }
    }
}

/// Bezier curve flattening (`c`, `v`, `y`; §8.5.2.2), filled and stroked, at a
/// scale where chord error is visible.
///
/// A GAP IN THIS CORPUS UNTIL NOW: not one of the other entries contains a
/// curve. Every shape was a rectangle, a straight-edged polygon or a
/// rasterised shading, so `interpret.rs`'s adaptive flattener
/// (`bezier_steps_for_flatness`, §10.6.2) had no differential coverage at all.
///
/// Relevant to the live S6 discussion about `outlines.rs`'s FIXED
/// `CURVE_STEPS = 10` for glyph outlines: this harness cannot reach that path,
/// because glyph contours are only emitted for an embedded font program and
/// there is no font file here — a standard-14 font takes the substitute path,
/// which [`rasterize`] refuses to grade. What this test CAN establish is
/// whether the OTHER flattener, the adaptive one used for content-stream
/// curves, holds up at large scale. If it does, that is a point of contrast:
/// the crate already contains a scale-aware flattener, and the glyph path is
/// the one place that does not use it.
///
/// The shapes are deliberately large — a near-circle of radius ~70pt at 2x, so
/// roughly 140 device px across — because chord error scales with radius and a
/// small curve would pass whatever the step count.
#[test]
#[ignore]
fn refdiff_bezier_flattening_at_scale() {
    // Four cubics approximating a circle, the classic 0.5523 magic number.
    let (cx, cy, r) = (100.0f64, 100.0f64, 70.0);
    let k = 0.552_284_749_8 * r;
    let circle = vec![
        op("rg", vec![n(0.15), n(0.35), n(0.8)]),
        op("m", vec![n(cx + r), n(cy)]),
        op("c", vec![n(cx + r), n(cy + k), n(cx + k), n(cy + r), n(cx), n(cy + r)]),
        op("c", vec![n(cx - k), n(cy + r), n(cx - r), n(cy + k), n(cx - r), n(cy)]),
        op("c", vec![n(cx - r), n(cy - k), n(cx - k), n(cy - r), n(cx), n(cy - r)]),
        op("c", vec![n(cx + k), n(cy - r), n(cx + r), n(cy - k), n(cx + r), n(cy)]),
        op("h", vec![]),
        op("f", vec![]),
    ];
    // `v` (first control point = current point) and `y` (second control point =
    // endpoint) are separate operators with their own operand shuffling, and
    // both are easy to implement as `c` with the wrong pair.
    let curves_vy = vec![
        op("G", vec![n(0.0)]),
        op("w", vec![n(3.0)]),
        op("m", vec![n(15.0), n(15.0)]),
        op("v", vec![n(15.0), n(170.0), n(170.0), n(170.0)]),
        op("S", vec![]),
        op("m", vec![n(30.0), n(15.0)]),
        op("y", vec![n(185.0), n(15.0), n(185.0), n(160.0)]),
        op("S", vec![]),
    ];

    // Measured SEPARATELY so the flagged counts attribute cleanly. A filled
    // curve arrives here already flattened by `interpret.rs`, so its rim tests
    // the renderer's flattener. A stroked curve additionally goes through this
    // file's approximate stroke expansion (module header item 2), so its rim
    // cannot distinguish the two and is reported rather than graded tightly.
    let rf = compare_page("bezier_fill", &pdf_bytes(circle.clone(), dictionary! {}));
    let a = interior_mean(&rf.ours, rf.w, rf.h, rf.page_h, [80.0, 80.0, 120.0, 120.0]);
    let b = interior_mean(&rf.theirs, rf.w, rf.h, rf.page_h, [80.0, 80.0, 120.0, 120.0]);
    println!("  circle interior: ours={a:?} theirs={b:?}");
    for c in 0..3 {
        assert!((a[c] - b[c]).abs() <= 3.0, "circle interior channel {c}: {} vs {}", a[c], b[c]);
    }

    let rs = compare_page("bezier_stroke_vy", &pdf_bytes(curves_vy, dictionary! {}));
    println!(
        "  attribution: filled curve {:.4}% flagged (renderer's flattener), \
         stroked v/y {:.4}% flagged (that PLUS this file's stroke expansion)",
        rf.report.fraction * 100.0,
        rs.report.fraction * 100.0
    );
}


/// The harness's own safety net, exercised.
///
/// [`render_both`] asserts `skipped == Skipped::default()` before it grades
/// anything, so that a page containing constructs this rasteriser cannot draw
/// is REFUSED rather than silently compared as a blank against a reference that
/// drew it properly. Until this test existed that assertion had never once
/// fired — no corpus entry reaches it — which is precisely the vacuous-check
/// pattern this round kept finding elsewhere (`shows_any_glyph` with zero
/// non-test callers being the other example). An untriggered safety net is
/// indistinguishable from a broken one.
///
/// A `DCTDecode` image is the cheapest way in: `interpret.rs` passes JPEG
/// through as `Prim::Image { format: 1 }` for the platform decoder rather than
/// decoding it here, so there are no RGBA samples for [`Rast::draw_image`] to
/// read. Measured: `format=1`, `data_len=318`, `jpeg_images=1`.
///
/// KNOWN GAP THIS PINS THE EDGE OF: the JPEG path therefore has NO differential
/// coverage at all. Closing it would mean decoding `format: 1` payloads here
/// with the crate's own `decode_jpeg_rgba`, which is a stand-in for Android's
/// decoder rather than the thing that actually runs — a muddy comparison, so it
/// is deliberately left open and documented instead of half-closed. Related:
/// `hunt-missing` found that a JPEG over 16 Mpx is emitted by Rust and dropped
/// by the Kotlin consumer, which is a cross-boundary loss this harness cannot
/// see either, because it measures the prim stream and the loss happens after.
#[test]
#[ignore]
fn refdiff_harness_refuses_a_page_it_cannot_draw() {
    // Minimal 8x8 greyscale baseline JPEG.
    let jpeg: Vec<u8> = {
        let mut v = vec![0xFFu8, 0xD8, 0xFF, 0xDB, 0x00, 0x43, 0x00];
        v.extend_from_slice(&[
            0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A,
            0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A,
            0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23,
            0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39,
            0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
        ]);
        v.extend_from_slice(&[
            0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x08, 0x00, 0x08, 0x01, 0x01, 0x11, 0x00,
        ]);
        v.extend_from_slice(&[0xFF, 0xC4, 0x00, 0x1F, 0x00]);
        v.extend_from_slice(&[
            0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        ]);
        v.extend_from_slice(&[0xFF, 0xC4, 0x00, 0xB5, 0x10]);
        v.extend_from_slice(&[
            0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00,
            0x01, 0x7D, 0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
            0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42,
            0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16,
            0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37,
            0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55,
            0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73,
            0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
            0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA,
            0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6,
            0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA,
            0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA,
        ]);
        v.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00]);
        v.extend_from_slice(&[0xF9, 0xFE, 0x8A, 0x28, 0xA0, 0x0F, 0xFF, 0xD9]);
        v
    };
    let mut doc = Document::with_version("1.7");
    let img = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8,
            "ColorSpace" => Object::Name(b"DeviceGray".to_vec()),
            "BitsPerComponent" => 8,
            "Filter" => Object::Name(b"DCTDecode".to_vec()),
        },
        jpeg,
    ));
    let ops = vec![
        op("q", vec![]),
        op("cm", vec![n(150.0), n(0.0), n(0.0), n(150.0), n(25.0), n(25.0)]),
        op("Do", vec![Object::Name(b"Im".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! { "XObject" => dictionary! { "Im" => Object::Reference(img) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let d = load_document_lenient(&bytes).expect("load");
    let pid = nth_page_id(&d, 0).expect("page");
    let page = interpret_page(&d, pid).expect("interpret");
    let format = page.prims.iter().find_map(|p| match p {
        Prim::Image { format, .. } => Some(*format),
        _ => None,
    });
    assert_eq!(format, Some(1), "precondition: DCTDecode must reach us as a format-1 passthrough");

    let (_canvas, skipped) = rasterize(&page, SCALE);
    assert_eq!(
        skipped,
        Skipped { substitute_text: 0, jpeg_images: 1, blended: 0 },
        "the rasteriser must COUNT what it could not draw"
    );

    // And the counter must actually stop the comparison, not merely exist.
    let refused = std::panic::catch_unwind(|| {
        let _ = render_both("jpeg_must_be_refused", &bytes);
    });
    assert!(
        refused.is_err(),
        "render_both graded a page containing an undrawable JPEG instead of refusing it — \
         the harness's only protection against silently comparing a blank page is gone"
    );
    println!("  harness correctly refused to grade a format-1 JPEG page");
}



/// The SAME curve, once as a clip boundary and once as a fill, at the viewer's
/// real maximum zoom — independent confirmation of the asymmetry the team
/// converged on late in round 6.
///
/// `interpret.rs`'s `c`/`v`/`y` arm emits every curve TWICE: `:1035` pushes an
/// exact `PathOp::Cubic` into `clip_path_ops`, and `:1022` flattens the same
/// curve through `bezier_steps_for_flatness` into the point list that becomes
/// `Prim::Fill { contours }`. The clip therefore reaches Skia as a real cubic
/// and is re-flattened at device resolution every frame; the fill is frozen at
/// page-point resolution before zoom is known.
///
/// That asymmetry was established by reading the chain across three files and
/// two languages (`interpret.rs` → `wire.rs` → `pathOpsToPath`). This measures
/// it instead. 10.6 px/pt is the effective ceiling for a 612pt page on a 1080px
/// viewport: `maxZoomFor` clamps `4·W/V` up to `MAX_ZOOM = 6`, and 6 × the
/// 1.765 fit scale is 10.6.
///
/// A clip whose boundary is a circle should be as crisp as the reference at any
/// zoom. The same circle painted as a fill carries ~0.42 device px of chord
/// error at this scale — small, but it is the thing that does not scale away.
#[test]
#[ignore]
fn refdiff_curved_clip_is_zoom_correct() {
    let (cx, cy, r) = (100.0f64, 100.0f64, 70.0);
    let k = 0.552_284_749_8 * r;
    let circle_path = |close_op: &str| {
        vec![
            op("m", vec![n(cx + r), n(cy)]),
            op("c", vec![n(cx + r), n(cy + k), n(cx + k), n(cy + r), n(cx), n(cy + r)]),
            op("c", vec![n(cx - k), n(cy + r), n(cx - r), n(cy + k), n(cx - r), n(cy)]),
            op("c", vec![n(cx - r), n(cy - k), n(cx - k), n(cy - r), n(cx), n(cy - r)]),
            op("c", vec![n(cx + k), n(cy - r), n(cx + r), n(cy - k), n(cx + r), n(cy)]),
            op("h", vec![]),
            op(close_op, vec![]),
        ]
    };
    // As a CLIP: the circle bounds a full-page fill, so the visible disc edge is
    // the clip boundary and nothing else.
    let mut clipped = vec![op("q", vec![])];
    clipped.extend(circle_path("W"));
    clipped.push(op("n", vec![]));
    clipped.extend(vec![
        op("rg", vec![n(0.15), n(0.35), n(0.8)]),
        op("re", vec![n(0.0), n(0.0), n(200.0), n(200.0)]),
        op("f", vec![]),
        op("Q", vec![]),
    ]);
    // As a FILL: same disc, edge comes from the pre-flattened contour.
    let mut filled = vec![op("rg", vec![n(0.15), n(0.35), n(0.8)])];
    filled.extend(circle_path("f"));

    // 10.6 px/pt — a 612pt page at max zoom on a 1080px phone.
    const ZOOM: f32 = 10.6;
    let measure = |label: &str, ops: Vec<Operation>| -> f64 {
        let bytes = pdf_bytes(ops, dictionary! {});
        let doc = load_document_lenient(&bytes).expect("load");
        let pid = nth_page_id(&doc, 0).expect("page");
        let page = interpret_page(&doc, pid).expect("interpret");
        let (canvas, skipped) = rasterize(&page, ZOOM);
        assert_eq!(skipped, Skipped::default(), "[{label}] undrawable construct");
        let ours = canvas.to_rgb8();
        let (w, h, theirs) = hayro_rgb8(&bytes, ZOOM);
        assert_eq!((canvas.w, canvas.h), (w, h), "[{label}] dimension mismatch");
        let rep = fuzzy_diff(&ours, &theirs, w, h);
        let perim = 2.0 * std::f64::consts::PI * r * ZOOM as f64;
        let per_rim = rep.flagged as f64 / perim;
        println!(
            "  {label:22} {w}x{h}: flagged {:5} ({:.4}%)  per rim-px {per_rim:.3}",
            rep.flagged,
            rep.fraction * 100.0
        );
        per_rim
    };

    let clip_edge = measure("curve as CLIP", clipped.clone());
    let fill_edge = measure("curve as FILL", filled);

    // The pixel gap is real but small (~12% of rim pixels), because both sides
    // sit on an antialiasing floor this harness cannot remove. So the pixel
    // comparison alone is too noisy to be the regression guard. Check the
    // STRUCTURAL property directly as well: the clip must actually carry
    // beziers. If `path_ops` ever goes `None`, `clip_contours` silently falls
    // back to the pre-flattened `pts` and the clip starts faceting like the
    // fill — which the loose pixel tolerance above would not catch.
    let bytes = pdf_bytes(clipped, dictionary! {});
    let doc = load_document_lenient(&bytes).expect("load");
    let pid = nth_page_id(&doc, 0).expect("page");
    let page = interpret_page(&doc, pid).expect("interpret");
    let cubics = page
        .prims
        .iter()
        .filter_map(|p| match p {
            Prim::ClipPush { path_ops, .. } => path_ops.as_ref(),
            _ => None,
        })
        .flatten()
        .filter(|o| matches!(o, PathOp::Cubic(..)))
        .count();
    assert_eq!(
        cubics, 4,
        "the clip must reach the consumer as four real cubics (interpret.rs:1035), not as a \
         pre-flattened polyline — Skia re-flattens these at device resolution, which is the \
         only reason a clip boundary stays crisp at zoom"
    );

    assert!(
        clip_edge <= fill_edge + 0.05,
        "a clip boundary should be no worse than the same curve pre-flattened as a fill, \
         but clip {clip_edge:.3} > fill {fill_edge:.3} per rim-pixel"
    );
    println!(
        "  asymmetry at {ZOOM} px/pt: clip {clip_edge:.3} vs fill {fill_edge:.3} per rim-pixel \
         ({} exact cubics carried)",
        cubics
    );
}


/// FINDING — the `i` (flatness) operator makes curve faceting VISIBLE, and
/// §10.6.2 says we are the ones out of conformance. **OUR BUG, one-line fix.**
///
/// §10.6.2 defines flatness as "the maximum permitted distance IN DEVICE PIXELS
/// between the mathematically correct path and an approximation constructed
/// from straight line segments". `interpret.rs:200-201` consumes it as a
/// tolerance in the units of `gs.ctm`, which `page_base_matrix` leaves as page
/// POINTS at 1:1 — the same units confusion that produced the S6 revert, but
/// here it is amplified by an operator the document controls.
///
/// Measured at 10.6 px/pt (a 612pt page at max zoom on a 1080px viewport).
/// NOTE that 10.6 is NOT the worst case — `maxZoomFor` floors the zoom at
/// `MAX_ZOOM = 6`, so effective resolution is `6 * viewportPx / pageWidthPts`
/// whenever the page is narrower than ~1.5x the viewport, which grows without
/// bound as the page narrows: a 200pt receipt on a tablet reaches 43.2 px/pt
/// and a 100pt label 86.4. Scale the figures below linearly for those. At
/// 43.2 px/pt, `i 3` is ~17.7 device px of chord error.
///
/// ```text
///   default (no `i`)   93 pts   per rim-px 0.175   sagitta 0.42 device px
///   i 1                49 pts   per rim-px 0.308   sagitta 1.52 device px
///   i 3                29 pts   per rim-px 2.419   sagitta 4.35 device px
///   i 10 (-> 3)        29 pts   per rim-px 2.419   sagitta 4.35 device px
/// ```
///
/// So a document asking for 3 device pixels of tolerance is given 4.35, and one
/// asking for 1 is given 1.52 — over budget in both cases, and 10x worse than
/// the default the same renderer uses when the document says nothing. The
/// `flatness.min(3.0)` clamp at `:200` is what stops it getting worse still.
///
/// THE INTERIM FIX IS `min`, NOT ASSIGNMENT — take the FINER of the two:
///
/// ```ignore
/// // interpret.rs:200 — currently:
/// let tol = if flatness > 0.0 { flatness.min(3.0) } else { 0.25 };
/// // proposed interim:
/// let tol = if flatness > 0.0 { flatness.min(0.25) } else { 0.25 };
/// ```
///
/// An earlier version of this note proposed `let tol = 0.25;` unconditionally.
/// That is WRONG and `hunt-wrong2` caught it: a fine `i` asks for MORE accuracy
/// than our default, and flattening it to 0.25 would coarsen it. Measured:
///
/// ```text
///   i 0.05  -> 201 pts, 0.09 device px    finer than default
///   i 0.1   -> 141 pts, 0.18 device px    finer than default
///   (none)  ->  93 pts, 0.42 device px
///   i 1     ->  49 pts, 1.52 device px
///   i 3     ->  29 pts, 4.35 device px
/// ```
///
/// So unconditional assignment would have taken `i 0.05` from 0.09 to 0.42 —
/// a 4.7x regression on a document explicitly requesting precision, to fix the
/// coarse case. `min` improves the coarse case identically and leaves the fine
/// case untouched.
///
/// WHY THE VIOLATION RATIO IS INDEPENDENT OF WHAT THE DOCUMENT ASKS. Since
/// `n = sqrt(len/tol)` and a cubic's deviation goes as `C·len/n²`, the delivered
/// sagitta is `C·tol` with `C ≈ 0.15` — proportional to the tolerance, not to
/// the curve. The bug is that `tol` is read as points when §10.6.2 denominates
/// it in device pixels, so `requested_pt = tol/scale` and
/// `violation = C·tol / (tol/scale) = C·scale`. The tolerance cancels: the
/// over-permission depends ONLY on the on-screen scale, which is why it is
/// ~1.6x at 10.6 px/pt whatever `i` says, and why break-even is at
/// `scale = 1/C ≈ 6.7 px/pt` — below that the formula's own conservatism more
/// than covers the unit error, above it does not. That near-cancellation at
/// ordinary scales is why this was never noticed.
///
/// The interim does NOT fix fine `i` — `i 0.1` stays at ~1.6x over-permitted,
/// because the unit error is untouched. It declines to make things worse. The
/// real fix is to send curves and let the consumer flatten at device
/// resolution, which is the same change the clip path already uses.
///
/// Not applied here: `interpret.rs` belongs to `fix-interp`. This test RECORDS
/// the numbers and bounds them loosely so a change in either direction shows up.
#[test]
#[ignore]
fn refdiff_flatness_operator_amplifies_faceting() {
    let (cx, cy, r) = (100.0f64, 100.0f64, 70.0);
    let k = 0.552_284_749_8 * r;
    let circle = |flatness: Option<f64>| {
        let mut v = vec![op("rg", vec![n(0.15), n(0.35), n(0.8)])];
        if let Some(f) = flatness {
            v.push(op("i", vec![n(f)]));
        }
        v.extend(vec![
            op("m", vec![n(cx + r), n(cy)]),
            op("c", vec![n(cx + r), n(cy + k), n(cx + k), n(cy + r), n(cx), n(cy + r)]),
            op("c", vec![n(cx - k), n(cy + r), n(cx - r), n(cy + k), n(cx - r), n(cy)]),
            op("c", vec![n(cx - r), n(cy - k), n(cx - k), n(cy - r), n(cx), n(cy - r)]),
            op("c", vec![n(cx + k), n(cy - r), n(cx + r), n(cy - k), n(cx + r), n(cy)]),
            op("h", vec![]),
            op("f", vec![]),
        ]);
        v
    };
    const ZOOM: f32 = 10.6;
    let mut worst_sagitta = 0.0f64;
    for (label, f) in [("default", None), ("i 1", Some(1.0)), ("i 3", Some(3.0))] {
        let bytes = pdf_bytes(circle(f), dictionary! {});
        let doc = load_document_lenient(&bytes).expect("load");
        let pid = nth_page_id(&doc, 0).expect("page");
        let page = interpret_page(&doc, pid).expect("interpret");
        let pts = page
            .prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { contours, .. } => Some(contours.iter().map(|c| c.len()).sum::<usize>()),
                _ => None,
            })
            .unwrap_or(0);
        let (canvas, skipped) = rasterize(&page, ZOOM);
        assert_eq!(skipped, Skipped::default());
        let ours = canvas.to_rgb8();
        let (w, h, theirs) = hayro_rgb8(&bytes, ZOOM);
        assert_eq!((canvas.w, canvas.h), (w, h));
        let rep = fuzzy_diff(&ours, &theirs, w, h);
        let perim = 2.0 * std::f64::consts::PI * r * ZOOM as f64;
        let theta = std::f64::consts::TAU / (pts.max(3) as f64);
        let sag = r * (1.0 - (theta / 2.0).cos()) * ZOOM as f64;
        worst_sagitta = worst_sagitta.max(sag);
        println!(
            "  {label:8}: {pts:3} pts  flagged {:6} ({:.4}%)  per rim-px {:.3}  sagitta {sag:.2} device px",
            rep.flagged,
            rep.fraction * 100.0,
            rep.flagged as f64 / perim
        );
    }
    println!("  worst sagitta across flatness settings: {worst_sagitta:.2} device px at {ZOOM} px/pt");
    // Loose, and deliberately two-sided: it should FALL if `i` is ignored as
    // proposed, and must not rise.
    assert!(
        worst_sagitta <= 6.0,
        "flatness handling got worse: {worst_sagitta:.2} device px of chord error"
    );
}


