//! Turns a road centreline into the carriageway surface lane markings are painted on.
//!
//! # Why a ribbon is not just a wide stroke
//!
//! A stroke ([`super::stroke`]) only has to be a band of one colour, so a fragment
//! never needs to know where in that band it landed. A marking does: whether a
//! fragment is inside the centre line, inside a lane divider, or on bare tarmac is
//! entirely a question of how far across the road it is. So every vertex here carries
//! `t`, an across-road coordinate that is exactly `-1` on the left kerb and `+1` on the
//! right and interpolates linearly between them. Each marking in the style then reduces
//! to a comparison against `t`, and one carriageway mesh serves all of them.
//!
//! # Width still lives in the shader
//!
//! Normalising `t` is what makes that possible. A carriageway's half-width is a screen
//! measurement that changes continuously with zoom; baking it into these vertices would
//! re-tessellate every road in the resident set on every zoom step, while panning, on
//! the critical path. It stays a push constant — exactly as `Stroke::half_px` does for
//! the line path — and the vertex shader offsets by
//!
//! ```text
//! position + normal * t * half_width
//! ```
//!
//! Because `t` is a fraction of the road rather than a distance across it, a marking's
//! place on the carriageway is the same number at every zoom. The geometry is a
//! function of the tile alone.
//!
//! # A taper is a ratio, and a ratio is not a width
//!
//! Where a road's lane count changes, two abutting sections are two features with two
//! lane counts, so the renderer draws them as two meshes at two half-widths and the
//! carriageway steps. Removing that step means the width has to vary *along* the road,
//! which reads like the invariant above forbids it. It does not, and the distinction is
//! the whole of [`Taper`]: what the invariant rules out is a **screen measurement** in a
//! vertex, because that is what a zoom step changes. A dimensionless ratio — this
//! vertex carries a road four fifths as wide as the push constant says — is not a screen
//! measurement, and a zoom step does not touch it.
//!
//! It needs no new attribute either, because the normal is already not a unit vector:
//! [`join_normal`] lengthens it by the miter factor, and the vertex shader multiplies by
//! it without ever assuming a length. So a taper is that same normal scaled a second
//! time, and the vertex stays six floats, the push constant stays a push constant, and
//! `road_surface.vert` is untouched.
//!
//! Both sides of a transition scale toward a width derived from the *node* rather than
//! from either section (see [`crate::tile::taper`]), so they cannot arrive at it
//! disagreeing. The step is not detected and corrected; it is unrepresentable.
//!
//! What this does **not** move is the lane markings: `lanes` and the centre-line `t` are
//! still per-mesh push constants, so through a taper the shader squeezes the section's
//! own lane count into a narrowing road rather than wedging one lane out, and the centre
//! line converges on the narrow section's without exactly meeting it. Both would need a
//! seventh float — still dimensionless, still invariant-safe — and neither is the step
//! the eye actually catches.
//!
//! # Joins
//!
//! Miter joins and butt caps, computed and clamped exactly as the stroke path does, and
//! sharing its [`MITER_LIMIT`]. A carriageway, its casing and its markings are all
//! drawn from one centreline and have to agree along their edges, so they cannot
//! disagree about where a join is. Lengthening the normal through a miter is also what
//! holds `t = ±1` on the kerb around a bend — with a unit normal the road narrows into
//! the turn and every divider slides sideways with it.

use super::stroke::MITER_LIMIT;

/// Floats per vertex: `x, y, nx, ny, t, distance`.
pub const FLOATS_PER_VERTEX: usize = 6;

/// How much narrower the carriageway is at each end of a part than along its middle.
///
/// `start` and `end` are ratios in `(0, 1]` against this part's *own* width: `1.0` is
/// full width and `0.5` is half of it, because the section this end runs into carries
/// half the lanes. `run` is how far back from that end the ramp reaches, in the same
/// tile-local `0..1` units the emitted positions use.
///
/// A ratio and a ground length, never a pixel: see the module docs on why that is what
/// lets the width stay a push constant.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Taper {
    start: f32,
    end: f32,
    run: f32,
}

impl Taper {
    /// A part that abuts nothing of a different width — the whole of the junction
    /// connector path, and the overwhelming majority of roads.
    pub const NONE: Taper = Taper { start: 1.0, end: 1.0, run: 0.0 };

    /// Clamped on the way in, so a ratio outside `(0, 1]` — which would invert the road
    /// or collapse it to nothing — cannot reach the tessellator to be checked for later.
    ///
    /// A taper that ramps to full width at both ends *is* [`NONE`](Taper::NONE) and is
    /// returned as it, so "this part does not taper" has one spelling rather than one per
    /// run length it happened to be offered.
    pub fn new(start: f32, end: f32, run: f32) -> Taper {
        let (start, end) = (ratio(start), ratio(end));
        if start >= 1.0 && end >= 1.0 {
            return Taper::NONE;
        }
        Taper { start, end, run: if run > 0.0 { run } else { 0.0 } }
    }

    /// Whether this taper would change any vertex. A `run` of zero cannot ramp.
    fn is_flat(self) -> bool {
        self.run <= 0.0
    }
}

/// A width ratio, held strictly positive so a taper can never produce a zero-width or
/// inside-out carriageway. The floor is one 255th because the narrowest a real ratio can
/// be is one lane against the 255 a `u8` lane count admits.
fn ratio(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(1.0 / 255.0, 1.0)
    } else {
        1.0
    }
}

/// Tessellate one carriageway part at a uniform width.
///
/// `coords` is flat `[x0, y0, ...]` in tile coordinates; `extent` is the layer's
/// extent, so positions come out in 0..1 and the renderer needs no per-layer scale.
/// Indices are relative to the first vertex already in `vertices`.
pub fn ribbon(coords: &[i32], extent: u32, vertices: &mut Vec<f32>, indices: &mut Vec<u32>) {
    ribbon_tapered(coords, extent, Taper::NONE, vertices, indices);
}

/// As [`ribbon`], but ramping to a neighbouring section's width at either end.
///
/// The ramp is applied to the join normal, which the vertex shader already treats as a
/// length rather than a direction, so nothing about the vertex format or the push
/// constants changes. A vertex is inserted where each ramp begins: without one, the
/// ramp would run from whatever vertex the simplifier happened to leave, and a straight
/// half-kilometre of road would taper over its whole length.
pub fn ribbon_tapered(
    coords: &[i32],
    extent: u32,
    taper: Taper,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let mut points = dedupe(coords);
    if points.len() / 2 < 2 {
        return;
    }
    // `taper.run` is in the same 0..1 units as the output; the points are still in
    // extent units, so the ramp has to be measured in those.
    let widths = taper_widths(&mut points, taper, taper.run * extent as f32);
    let n = points.len() / 2;
    let scale = 1.0 / extent as f32;

    let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
    let mut distance = 0.0f32;

    for i in 0..n {
        let (nx, ny) = join_normal(&points, n, i);
        // Empty for an untapered part, which is nearly all of them, so the common path
        // pays one bounds check and multiplies by nothing.
        let width = widths.get(i).copied().unwrap_or(1.0);

        if i > 0 {
            // Scaled with the positions: the dash and marking-repeat patterns are
            // measured in pixels, and `line.vert` gets there by multiplying by the
            // tile's pixel span. Leaving this in extent units would make the pattern
            // period depend on the layer that happened to supply the geometry.
            distance += segment_length(&points, i - 1, i) * scale;
        }

        let px = points[i * 2] as f32 * scale;
        let py = points[i * 2 + 1] as f32 * scale;
        // Tile y grows downward, so the perpendicular `(-dy, dx)` points to the right
        // of travel — the `-1` vertex is the left kerb, as a driver would name it.
        vertices.extend_from_slice(&[px, py, nx * width, ny * width, -1.0, distance]);
        vertices.extend_from_slice(&[px, py, nx * width, ny * width, 1.0, distance]);
    }

    // Two triangles per segment, wound as the stroke path winds them so face culling
    // could be switched on later without the roads disappearing.
    for i in 0..(n - 1) {
        let a = base + (i as u32) * 2;
        indices.extend_from_slice(&[a, a + 1, a + 2, a + 1, a + 3, a + 2]);
    }
}

/// The width ratio at each point, inserting the vertices the ramps need as it goes.
///
/// Empty when there is no taper, which the caller reads as one everywhere rather than
/// paying a vector per part for the case that is nearly every part.
fn taper_widths(points: &mut Vec<i32>, taper: Taper, run: f32) -> Vec<f32> {
    if taper.is_flat() {
        return Vec::new();
    }
    let lengths = cumulative(points);
    let Some(&total) = lengths.last() else { return Vec::new() };
    // Half the part at most, so the two ramps meet in the middle rather than crossing
    // and inverting. A section shorter than two ramps tapers over all of it.
    let run = run.min(total * 0.5);
    if run <= 0.0 {
        return Vec::new();
    }

    let mut wanted: Vec<f32> = Vec::new();
    if taper.end < 1.0 {
        wanted.push(total - run);
    }
    if taper.start < 1.0 {
        wanted.push(run);
    }
    // Two ramps that meet share the vertex they meet on. Inserting one for each would
    // put two coincident points in the line, and a zero-length segment has no direction
    // — the NaN `dedupe` exists to prevent, reintroduced after it has already run.
    if wanted.len() == 2 && (wanted[0] - wanted[1]).abs() < MIN_SPLIT_GAP {
        wanted.pop();
    }
    // Furthest along first: its insertion cannot move the index the nearer one was
    // measured at, so both are computed against the original line and applied in turn.
    let splits: Vec<(usize, i32, i32)> =
        wanted.iter().filter_map(|&along| split_at(points, &lengths, along)).collect();
    for (at, x, y) in splits {
        points.splice(at * 2..at * 2, [x, y]);
    }

    // Re-measured, because rounding an inserted point to integer tile units moves it a
    // fraction off the line it was cut from and so changes the length it sits on.
    let lengths = cumulative(points);
    let total = lengths.last().copied().unwrap_or(0.0);
    lengths
        .iter()
        .map(|&along| ramp(along, run, taper.start).min(ramp(total - along, run, taper.end)))
        .collect()
}

/// How near an existing point a ramp's own vertex may be inserted, in tile units.
///
/// The inserted point is rounded to integer tile units, so nearer than this and rounding
/// could land it exactly on its neighbour, where [`dedupe`] has already run and would not
/// drop it again; nearer than a whole unit and [`direction`] would be normalising a
/// sub-unit segment, which the connector path relies on never happening.
const MIN_SPLIT_GAP: f32 = 1.5;

/// The ratio at a point `along` past the end that ramps to `at_end`: exactly `at_end` on
/// the end itself, exactly one from `run` onwards.
fn ramp(along: f32, run: f32, at_end: f32) -> f32 {
    let fraction = (along / run).clamp(0.0, 1.0);
    at_end + (1.0 - at_end) * fraction
}

/// Where a vertex has to be inserted to put one at arc length `at`, as
/// `(index it goes before, x, y)` — or nothing when a vertex is already close enough.
///
/// "Close enough" is [`MIN_SPLIT_GAP`].
fn split_at(points: &[i32], lengths: &[f32], at: f32) -> Option<(usize, i32, i32)> {
    let after = (1..lengths.len()).find(|&i| lengths[i] >= at)?;
    let before = after - 1;
    let span = lengths[after] - lengths[before];
    if span <= 0.0 || at - lengths[before] < MIN_SPLIT_GAP || lengths[after] - at < MIN_SPLIT_GAP {
        return None;
    }
    let fraction = (at - lengths[before]) / span;
    let x = points[before * 2] as f32
        + fraction * (points[after * 2] - points[before * 2]) as f32;
    let y = points[before * 2 + 1] as f32
        + fraction * (points[after * 2 + 1] - points[before * 2 + 1]) as f32;
    Some((after, x.round() as i32, y.round() as i32))
}

/// Arc length from the first point to each point, in tile units.
fn cumulative(points: &[i32]) -> Vec<f32> {
    let n = points.len() / 2;
    let mut out = Vec::with_capacity(n);
    let mut total = 0.0;
    out.push(0.0);
    for i in 1..n {
        total += segment_length(points, i - 1, i);
        out.push(total);
    }
    out
}

/// The normal at point `i`: unit at the ends, a clamped miter in between.
fn join_normal(points: &[i32], n: usize, i: usize) -> (f32, f32) {
    let before = if i > 0 { Some(direction(points, i - 1, i)) } else { None };
    let after = if i < n - 1 { Some(direction(points, i, i + 1)) } else { None };

    match (before, after) {
        (None, Some(a)) => (-a.1, a.0),
        (Some(b), None) => (-b.1, b.0),
        (Some(b), Some(a)) => {
            // The bisector, lengthened by 1/cos(theta/2) so both segments' kerbs meet
            // exactly on it.
            let (n1x, n1y) = (-b.1, b.0);
            let (n2x, n2y) = (-a.1, a.0);
            let mut mx = n1x + n2x;
            let mut my = n1y + n2y;
            let len = (mx * mx + my * my).sqrt();
            if len < 1e-6 {
                // A perfect reversal: the bisector is undefined, so fall back to the
                // incoming normal rather than emitting NaN.
                (n1x, n1y)
            } else {
                mx /= len;
                my /= len;
                let cos_half = mx * n1x + my * n1y;
                let miter = if cos_half > 1e-3 { 1.0 / cos_half } else { MITER_LIMIT };
                let clamped = if miter > MITER_LIMIT { MITER_LIMIT } else { miter };
                (mx * clamped, my * clamped)
            }
        }
        (None, None) => unreachable!("a part with fewer than two points was filtered out"),
    }
}

/// Unit direction from point `i` to point `j`.
fn direction(points: &[i32], i: usize, j: usize) -> (f32, f32) {
    let dx = (points[j * 2] - points[i * 2]) as f32;
    let dy = (points[j * 2 + 1] - points[i * 2 + 1]) as f32;
    let len = (dx * dx + dy * dy).sqrt();
    (dx / len, dy / len)
}

fn segment_length(points: &[i32], i: usize, j: usize) -> f32 {
    let dx = (points[j * 2] - points[i * 2]) as f32;
    let dy = (points[j * 2 + 1] - points[i * 2 + 1]) as f32;
    (dx * dx + dy * dy).sqrt()
}

/// Drop consecutive duplicate vertices.
///
/// Simplified tile geometry contains them, and a zero-length segment has no direction
/// — which would put a NaN in the normal and take the whole carriageway off screen,
/// not just that segment.
fn dedupe(coords: &[i32]) -> Vec<i32> {
    let mut out: Vec<i32> = Vec::with_capacity(coords.len());
    let mut i = 0;
    while i + 1 < coords.len() {
        let (x, y) = (coords[i], coords[i + 1]);
        let len = out.len();
        if len == 0 || out[len - 2] != x || out[len - 1] != y {
            out.push(x);
            out.push(y);
        }
        i += 2;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(v: &[f32], vertex: usize, field: usize) -> f32 {
        v[vertex * FLOATS_PER_VERTEX + field]
    }
    fn position_x(v: &[f32], i: usize) -> f32 {
        at(v, i, 0)
    }
    fn normal_x(v: &[f32], i: usize) -> f32 {
        at(v, i, 2)
    }
    fn normal_y(v: &[f32], i: usize) -> f32 {
        at(v, i, 3)
    }
    fn across(v: &[f32], i: usize) -> f32 {
        at(v, i, 4)
    }
    fn distance(v: &[f32], i: usize) -> f32 {
        at(v, i, 5)
    }
    fn normal_len(v: &[f32], i: usize) -> f32 {
        (normal_x(v, i).powi(2) + normal_y(v, i).powi(2)).sqrt()
    }

    /// What `line.vert` does with a vertex, so the invariants can be checked on the
    /// host: the whole point of keeping the width out of the buffer is that the shader
    /// is the only place the two are ever combined.
    fn shade(v: &[f32], i: usize, t: f32, half_width: f32) -> (f32, f32) {
        (
            at(v, i, 0) + normal_x(v, i) * t * half_width,
            at(v, i, 1) + normal_y(v, i) * t * half_width,
        )
    }

    #[test]
    fn a_straight_segment_becomes_one_quad() {
        let mut v = Vec::new();
        let mut idx = Vec::new();
        // Extent 100, so tile coordinates and 0..1 positions differ by a round 100.
        ribbon(&[0, 0, 100, 0], 100, &mut v, &mut idx);

        assert_eq!(v.len() / FLOATS_PER_VERTEX, 4, "two points, two vertices each");
        assert_eq!(idx.len(), 6, "two triangles");
        for i in 0..4 {
            assert!((normal_x(&v, i) - 0.0).abs() < 1e-6);
            assert!((normal_y(&v, i) - 1.0).abs() < 1e-6);
        }
        assert!((position_x(&v, 0) - 0.0).abs() < 1e-6);
        assert!((position_x(&v, 2) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn t_reaches_both_kerbs_and_never_leaves_the_carriageway() {
        // A marking is placed by comparing against `t`, so anything outside [-1, +1]
        // would paint kerb markings onto the verge.
        let mut v = Vec::new();
        ribbon(&[0, 0, 100, 0, 100, 100, 250, 40], 4096, &mut v, &mut Vec::new());
        let n = v.len() / FLOATS_PER_VERTEX;
        assert_eq!(n, 8);
        for i in 0..n {
            assert!((-1.0..=1.0).contains(&across(&v, i)), "t {} at {i}", across(&v, i));
        }
        for i in 0..(n / 2) {
            assert_eq!(across(&v, i * 2), -1.0, "left kerb is exactly -1");
            assert_eq!(across(&v, i * 2 + 1), 1.0, "right kerb is exactly +1");
        }
    }

    #[test]
    fn a_markings_place_on_the_road_survives_a_width_change() {
        // The regression this guards is the expensive one: if the half-width were baked
        // in, a zoom step would need a re-tessellation. The same buffer has to serve
        // every width, with the marking staying the same fraction across the road.
        let mut v = Vec::new();
        ribbon(&[0, 0, 100, 0], 100, &mut v, &mut Vec::new());
        let divider = -1.0 / 3.0;

        let fraction = |half_width: f32| {
            let (lx, ly) = shade(&v, 0, -1.0, half_width);
            let (rx, ry) = shade(&v, 1, 1.0, half_width);
            let (mx, my) = shade(&v, 0, divider, half_width);
            let span = ((rx - lx).powi(2) + (ry - ly).powi(2)).sqrt();
            ((mx - lx).powi(2) + (my - ly).powi(2)).sqrt() / span
        };

        let narrow = fraction(2.5);
        let wide = fraction(37.0);
        assert!((narrow - wide).abs() < 1e-6, "{narrow} vs {wide}");
        // And it is where `t` says it is: a third of the way in from the left kerb.
        assert!((narrow - (divider + 1.0) / 2.0).abs() < 1e-6);
    }

    #[test]
    fn a_right_angle_join_gets_a_miter_normal() {
        // (0,0) -> (100,0) -> (100,100). The incoming normal is (0,1) and the outgoing
        // one is (-1,0); their bisector is (-1,1)/sqrt(2) and the miter length is
        // 1/cos(45°) = sqrt(2), so the normal comes out exactly (-1, 1) and the kerb
        // stays on `t = ±1` through the turn.
        let mut v = Vec::new();
        ribbon(&[0, 0, 100, 0, 100, 100], 100, &mut v, &mut Vec::new());
        assert_eq!(v.len() / FLOATS_PER_VERTEX, 6);
        assert!((normal_x(&v, 2) - -1.0).abs() < 1e-5, "join nx {}", normal_x(&v, 2));
        assert!((normal_y(&v, 2) - 1.0).abs() < 1e-5, "join ny {}", normal_y(&v, 2));
        assert!((normal_len(&v, 2) - 2f32.sqrt()).abs() < 1e-5);
        assert!((normal_len(&v, 0) - 1.0).abs() < 1e-5);
        assert!((normal_len(&v, 4) - 1.0).abs() < 1e-5);
    }

    #[test]
    fn a_hairpin_miter_is_clamped_rather_than_throwing_a_spike() {
        let mut v = Vec::new();
        ribbon(&[0, 0, 1000, 0, 0, 10], 4096, &mut v, &mut Vec::new());
        assert!((normal_len(&v, 2) - MITER_LIMIT).abs() < 1e-4, "got {}", normal_len(&v, 2));
    }

    #[test]
    fn a_perfect_reversal_does_not_produce_nan() {
        let mut v = Vec::new();
        ribbon(&[0, 0, 100, 0, 0, 0], 100, &mut v, &mut Vec::new());
        for (i, f) in v.iter().enumerate() {
            assert!(f.is_finite(), "float {i} is {f}");
        }
    }

    #[test]
    fn points_one_tile_unit_apart_still_give_a_unit_normal() {
        // `dedupe` only drops *exactly* coincident points, so the shortest surviving
        // segment is one integer tile unit — never short enough for `direction` to
        // divide by something near zero. Junction connectors converging on a shared
        // arm endpoint lean on this. It stops holding the moment a caller feeds in
        // coordinates that are not integer tile units, or `dedupe` grows a tolerance.
        let mut v = Vec::new();
        ribbon(&[0, 0, 1, 0, 1, 1, 2, 1], 4096, &mut v, &mut Vec::new());
        for (i, f) in v.iter().enumerate() {
            assert!(f.is_finite(), "float {i} is {f}");
        }
        assert!((normal_len(&v, 0) - 1.0).abs() < 1e-5, "start {}", normal_len(&v, 0));
        let last = v.len() / FLOATS_PER_VERTEX - 1;
        assert!((normal_len(&v, last) - 1.0).abs() < 1e-5, "end {}", normal_len(&v, last));
    }

    #[test]
    fn repeated_vertices_are_dropped() {
        let mut v = Vec::new();
        ribbon(&[0, 0, 0, 0, 0, 0, 50, 0], 100, &mut v, &mut Vec::new());
        assert_eq!(v.len() / FLOATS_PER_VERTEX, 4, "three coincident points became one");
    }

    #[test]
    fn a_part_with_fewer_than_two_distinct_points_emits_nothing() {
        let mut v = Vec::new();
        let mut idx = Vec::new();
        ribbon(&[], 100, &mut v, &mut idx);
        ribbon(&[5, 5], 100, &mut v, &mut idx);
        ribbon(&[5, 5, 5, 5], 100, &mut v, &mut idx);
        assert!(v.is_empty());
        assert!(idx.is_empty());
    }

    #[test]
    fn distance_accumulates_along_the_line_in_tile_local_units() {
        // Mirrors the stroke path's assertion: `line.vert` scales this by the tile's
        // pixel span, so extent units here would stretch every marking pattern by the
        // extent.
        let mut v = Vec::new();
        ribbon(&[0, 0, 300, 0, 300, 400], 100, &mut v, &mut Vec::new());
        assert!((distance(&v, 0) - 0.0).abs() < 1e-6);
        assert!((distance(&v, 2) - 3.0).abs() < 1e-6, "300 tile units at extent 100");
        assert!((distance(&v, 4) - 7.0).abs() < 1e-6, "plus 400 more");
    }

    /// The kerb offset the vertex shader lands on, in tile-local units, for the push
    /// constant `half_width`. Everything below asserts on this rather than on the raw
    /// normal, because it is what actually reaches a pixel.
    fn kerb_offset(v: &[f32], i: usize, half_width: f32) -> f32 {
        let (x, y) = shade(v, i, 1.0, half_width);
        ((x - at(v, i, 0)).powi(2) + (y - at(v, i, 1)).powi(2)).sqrt()
    }

    #[test]
    fn no_taper_is_byte_identical_to_the_untapered_path() {
        // The overwhelming majority of parts, and the whole of the junction connector
        // path. A ratio of one must not perturb a single float.
        let line = [0, 0, 300, 0, 300, 400, 700, 400];
        let (mut plain_v, mut plain_i) = (Vec::new(), Vec::new());
        ribbon(&line, 4096, &mut plain_v, &mut plain_i);
        let (mut none_v, mut none_i) = (Vec::new(), Vec::new());
        ribbon_tapered(&line, 4096, Taper::NONE, &mut none_v, &mut none_i);
        assert_eq!(plain_v, none_v);
        assert_eq!(plain_i, none_i);

        // And a taper whose ratios are both one is the same thing however long its run.
        let (mut flat_v, mut flat_i) = (Vec::new(), Vec::new());
        ribbon_tapered(&line, 4096, Taper::new(1.0, 1.0, 0.05), &mut flat_v, &mut flat_i);
        assert_eq!(plain_v, flat_v);
        assert_eq!(plain_i, flat_i);
    }

    #[test]
    fn a_tapered_end_is_exactly_the_ratio_and_the_far_end_is_untouched() {
        // The property the whole design rests on: the narrow end is *exactly* the
        // neighbour's width, not nearly it, so two sections meeting there cannot leave
        // a step between them.
        let mut v = Vec::new();
        // 1000 units long at extent 1000, so tile-local length is 1.0 and a run of 0.2
        // is 200 units.
        ribbon_tapered(&[0, 0, 1000, 0], 1000, Taper::new(0.5, 1.0, 0.2), &mut v, &mut Vec::new());
        let last = v.len() / FLOATS_PER_VERTEX - 1;
        assert!((kerb_offset(&v, 0, 40.0) - 20.0).abs() < 1e-5, "start is half width");
        assert!((kerb_offset(&v, last, 40.0) - 40.0).abs() < 1e-5, "end is full width");
    }

    #[test]
    fn the_ramp_reaches_full_width_at_the_run_and_stays_there() {
        let mut v = Vec::new();
        ribbon_tapered(&[0, 0, 1000, 0], 1000, Taper::new(0.5, 1.0, 0.2), &mut v, &mut Vec::new());
        let n = v.len() / FLOATS_PER_VERTEX;
        assert_eq!(n, 6, "a vertex was inserted where the ramp ends");
        // The inserted point is at 200 units along, which is 0.2 in tile-local units.
        assert!((position_x(&v, 2) - 0.2).abs() < 1e-5, "at {}", position_x(&v, 2));
        assert!((kerb_offset(&v, 2, 40.0) - 40.0).abs() < 1e-5, "full width from the run on");
    }

    #[test]
    fn a_taper_narrows_monotonically_and_never_inverts_the_road() {
        let mut v = Vec::new();
        ribbon_tapered(
            &[0, 0, 250, 0, 500, 0, 750, 0, 1000, 0],
            1000,
            Taper::new(0.25, 0.5, 0.3),
            &mut v,
            &mut Vec::new(),
        );
        let n = v.len() / FLOATS_PER_VERTEX;
        let widths: Vec<f32> = (0..n).step_by(2).map(|i| kerb_offset(&v, i, 40.0)).collect();
        assert!(widths.iter().all(|w| *w > 0.0), "a zero width draws nothing: {widths:?}");
        assert!(widths.iter().all(|w| *w <= 40.0 + 1e-5), "never wider than the push: {widths:?}");
        assert!((widths[0] - 10.0).abs() < 1e-5, "start quarter width: {}", widths[0]);
        let end = widths.len() - 1;
        assert!((widths[end] - 20.0).abs() < 1e-5, "end half width: {}", widths[end]);
        // Rising away from the start, falling toward the end: no bulge in between.
        let peak = widths.iter().cloned().fold(f32::MIN, f32::max);
        assert!((peak - 40.0).abs() < 1e-5, "the middle reaches full width: {widths:?}");
    }

    #[test]
    fn a_taper_leaves_t_on_the_kerbs_so_the_markings_still_span_the_road() {
        // `road_surface.frag` places every marking against `t`. If a taper moved it the
        // dividers would drift off the asphalt exactly where the road is changing.
        let mut v = Vec::new();
        ribbon_tapered(
            &[0, 0, 400, 0, 400, 400],
            4096,
            Taper::new(0.4, 0.6, 0.02),
            &mut v,
            &mut Vec::new(),
        );
        let n = v.len() / FLOATS_PER_VERTEX;
        for i in 0..(n / 2) {
            assert_eq!(across(&v, i * 2), -1.0, "left kerb is exactly -1");
            assert_eq!(across(&v, i * 2 + 1), 1.0, "right kerb is exactly +1");
        }
    }

    #[test]
    fn a_tapered_marking_still_survives_a_width_change() {
        // The invariant the module exists to protect, re-asserted *through* a taper: a
        // ratio is not a screen measurement, so a zoom step still needs no
        // re-tessellation.
        let mut v = Vec::new();
        ribbon_tapered(&[0, 0, 1000, 0], 1000, Taper::new(0.5, 1.0, 0.2), &mut v, &mut Vec::new());
        let divider = -1.0 / 3.0;
        let fraction = |vertex: usize, half_width: f32| {
            let (lx, ly) = shade(&v, vertex, -1.0, half_width);
            let (rx, ry) = shade(&v, vertex + 1, 1.0, half_width);
            let (mx, my) = shade(&v, vertex, divider, half_width);
            let span = ((rx - lx).powi(2) + (ry - ly).powi(2)).sqrt();
            ((mx - lx).powi(2) + (my - ly).powi(2)).sqrt() / span
        };
        for vertex in [0, 2] {
            let narrow = fraction(vertex, 2.5);
            let wide = fraction(vertex, 37.0);
            assert!((narrow - wide).abs() < 1e-5, "vertex {vertex}: {narrow} vs {wide}");
            assert!((narrow - (divider + 1.0) / 2.0).abs() < 1e-5);
        }
    }

    #[test]
    fn two_ramps_longer_than_the_part_meet_instead_of_crossing() {
        // A section shorter than two taper runs — a slip lane between two changes. The
        // ends must still be exact and nothing in between may invert.
        let mut v = Vec::new();
        ribbon_tapered(&[0, 0, 100, 0], 1000, Taper::new(0.5, 0.25, 0.4), &mut v, &mut Vec::new());
        let n = v.len() / FLOATS_PER_VERTEX;
        let last = n - 1;
        assert!((kerb_offset(&v, 0, 40.0) - 20.0).abs() < 1e-5);
        assert!((kerb_offset(&v, last, 40.0) - 10.0).abs() < 1e-5);
        for i in 0..n {
            let w = kerb_offset(&v, i, 40.0);
            assert!(w > 0.0 && w <= 40.0 + 1e-5, "vertex {i} width {w}");
        }
    }

    #[test]
    fn a_ramp_that_lands_on_an_existing_vertex_inserts_nothing() {
        // The guard the connector path relies on: `dedupe` drops exact duplicates and
        // `direction` normalises segments of at least one tile unit, so a split point
        // within rounding distance of a vertex must not be inserted at all.
        let mut v = Vec::new();
        // A run of 0.2 at extent 1000 is 200 units, which is exactly the second point.
        ribbon_tapered(&[0, 0, 200, 0, 1000, 0], 1000, Taper::new(0.5, 1.0, 0.2), &mut v, &mut Vec::new());
        assert_eq!(v.len() / FLOATS_PER_VERTEX, 6, "three points, no fourth inserted");
        assert!((kerb_offset(&v, 2, 40.0) - 40.0).abs() < 1e-5, "and it is the full-width point");
    }

    #[test]
    fn every_float_of_a_tapered_ribbon_is_finite() {
        for taper in [
            Taper::new(0.5, 0.5, 0.25),
            Taper::new(1.0 / 255.0, 1.0, 0.5),
            Taper::new(0.5, 0.5, 10.0),
            Taper::new(f32::NAN, -3.0, f32::NEG_INFINITY),
        ] {
            for line in [
                &[0, 0, 100, 0][..],
                &[0, 0, 1, 0, 1, 1, 2, 1][..],
                &[0, 0, 1000, 0, 0, 10][..],
                &[0, 0, 100, 0, 0, 0][..],
            ] {
                let mut v = Vec::new();
                ribbon_tapered(line, 4096, taper, &mut v, &mut Vec::new());
                for (i, f) in v.iter().enumerate() {
                    assert!(f.is_finite(), "float {i} is {f} for {taper:?} on {line:?}");
                }
            }
        }
    }

    #[test]
    fn a_ratio_outside_the_unit_range_cannot_be_constructed() {
        // Clamped at the constructor rather than checked at the tessellator: a zero
        // ratio draws nothing and a negative one turns the road inside out, and neither
        // should be a state the caller can hand over to be caught later.
        assert_eq!(Taper::new(2.0, 1.5, 0.1), Taper::new(1.0, 1.0, 0.1));
        assert!(Taper::new(0.0, -1.0, 0.1).start > 0.0);
        assert!(Taper::new(0.0, -1.0, 0.1).end > 0.0);
        assert_eq!(Taper::new(f32::NAN, f32::INFINITY, 0.1), Taper::new(1.0, 1.0, 0.1));
        assert!(Taper::new(0.5, 0.5, -1.0).is_flat(), "a negative run cannot ramp");
        assert!(Taper::NONE.is_flat());
    }
}
