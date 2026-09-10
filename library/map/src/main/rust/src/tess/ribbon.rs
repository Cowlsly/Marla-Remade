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

/// Tessellate one carriageway part.
///
/// `coords` is flat `[x0, y0, ...]` in tile coordinates; `extent` is the layer's
/// extent, so positions come out in 0..1 and the renderer needs no per-layer scale.
/// Indices are relative to the first vertex already in `vertices`.
pub fn ribbon(coords: &[i32], extent: u32, vertices: &mut Vec<f32>, indices: &mut Vec<u32>) {
    let points = dedupe(coords);
    let n = points.len() / 2;
    if n < 2 {
        return;
    }
    let scale = 1.0 / extent as f32;

    let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
    let mut distance = 0.0f32;

    for i in 0..n {
        let (nx, ny) = join_normal(&points, n, i);

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
        vertices.extend_from_slice(&[px, py, nx, ny, -1.0, distance]);
        vertices.extend_from_slice(&[px, py, nx, ny, 1.0, distance]);
    }

    // Two triangles per segment, wound as the stroke path winds them so face culling
    // could be switched on later without the roads disappearing.
    for i in 0..(n - 1) {
        let a = base + (i as u32) * 2;
        indices.extend_from_slice(&[a, a + 1, a + 2, a + 1, a + 3, a + 2]);
    }
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
}
