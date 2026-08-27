//! Turns line geometry into triangles.
//!
//! # Width lives in the shader, not in these vertices
//!
//! A stroke's width is a screen measurement that changes continuously with zoom. If
//! it were baked in here, every zoom step would re-tessellate every road in every
//! visible tile, while panning, on the critical path. So a vertex carries the **unit
//! normal** of its join and a pair of multipliers, and the vertex shader offsets by
//!
//! ```text
//! position + normal * (offset_mul * gap_half + width_mul * half_width)
//! ```
//!
//! with the widths arriving as push constants. The geometry is then a function of the
//! tile alone.
//!
//! # Joins and caps
//!
//! `maps/src/main/assets/style.json` never sets `line-join` or `line-cap`, so the
//! spec defaults are what has to be right: **miter joins and butt caps**. Round joins
//! are visibly wrong at road junctions — a miter is what makes two segments of the
//! same road read as one line — and they are also the expensive ones, needing a fan
//! of extra vertices per join.
//!
//! A miter is clamped by [`MITER_LIMIT`]: as a turn approaches a hairpin the miter
//! length goes to infinity and an unclamped one throws a spike across the tile. Past
//! the limit the join degrades to a bevel, which is what the SVG and Canvas specs
//! prescribe.
//!
//! # `line-gap-width`
//!
//! 17 layers of the style use it for road casings: the stroke is drawn as two bands
//! standing off either side of the centreline, leaving the middle to the road fill
//! drawn over it. Both bands come out of one call, distinguished only by their
//! `offset_mul`, so a casing costs one draw rather than two.

/// Floats per vertex: `x, y, nx, ny, offset_mul, width_mul, distance`.
pub const FLOATS_PER_VERTEX: usize = 7;

/// How many half-widths a miter may extend before it degrades to a bevel. 2.0 is the
/// SVG/Canvas default and the value MapLibre uses.
pub const MITER_LIMIT: f32 = 2.0;

/// Stroke one line part.
///
/// `coords` is flat `[x0, y0, ...]` in tile coordinates; `extent` is the layer's
/// extent, so positions come out in 0..1 and the renderer needs no per-layer scale.
/// `gapped` produces a casing: two bands offset either side of the centreline instead
/// of one band centred on it. Indices are relative to the first vertex already in
/// `vertices`.
pub fn stroke(
    coords: &[i32],
    extent: u32,
    gapped: bool,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let points = dedupe(coords);
    let n = points.len() / 2;
    if n < 2 {
        return;
    }
    let scale = 1.0 / extent as f32;
    if gapped {
        band(&points, n, scale, 1.0, vertices, indices);
        band(&points, n, scale, -1.0, vertices, indices);
    } else {
        band(&points, n, scale, 0.0, vertices, indices);
    }
}

/// One band of quads along the line.
///
/// `offset_mul == 0` is a plain stroke straddling the centreline: its edges are at
/// `-half_width` and `+half_width`. A non-zero `offset_mul` puts the band entirely on
/// one side, from `gap_half` to `gap_half + width` — hence the `width_mul` of `0` and
/// `2 * offset_mul`, since `width` is `2 * half_width`.
fn band(
    points: &[i32],
    n: usize,
    scale: f32,
    offset_mul: f32,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let inner_width_mul = if offset_mul == 0.0 { -1.0 } else { 0.0 };
    let outer_width_mul = if offset_mul == 0.0 { 1.0 } else { 2.0 * offset_mul };

    let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
    let mut distance = 0.0f32;

    for i in 0..n {
        let x = points[i * 2];
        let y = points[i * 2 + 1];

        // Segment directions either side of this vertex; absent at the ends, where a
        // butt cap means the normal is just the one segment's.
        let before = if i > 0 { Some(direction(points, i - 1, i)) } else { None };
        let after = if i < n - 1 { Some(direction(points, i, i + 1)) } else { None };

        let (nx, ny) = match (before, after) {
            (None, Some(a)) => (-a.1, a.0),
            (Some(b), None) => (-b.1, b.0),
            (Some(b), Some(a)) => {
                // The miter normal: the bisector, lengthened by 1/cos(theta/2) so both
                // segments' edges meet exactly on it.
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
        };

        if i > 0 {
            distance += segment_length(points, i - 1, i) * scale;
        }

        let px = x as f32 * scale;
        let py = y as f32 * scale;
        vertices.extend_from_slice(&[px, py, nx, ny, offset_mul, inner_width_mul, distance]);
        vertices.extend_from_slice(&[px, py, nx, ny, offset_mul, outer_width_mul, distance]);
    }

    // Two triangles per segment, wound consistently so face culling could be switched
    // on later without the roads disappearing.
    for i in 0..(n - 1) {
        let a = base + (i as u32) * 2;
        indices.extend_from_slice(&[a, a + 1, a + 2, a + 1, a + 3, a + 2]);
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
/// — which would put a NaN in the normal and take the whole triangle strip off screen,
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
    fn offset_mul(v: &[f32], i: usize) -> f32 {
        at(v, i, 4)
    }
    fn width_mul(v: &[f32], i: usize) -> f32 {
        at(v, i, 5)
    }
    fn distance(v: &[f32], i: usize) -> f32 {
        at(v, i, 6)
    }
    fn normal_len(v: &[f32], i: usize) -> f32 {
        (normal_x(v, i).powi(2) + normal_y(v, i).powi(2)).sqrt()
    }

    #[test]
    fn a_straight_segment_becomes_one_quad() {
        let mut v = Vec::new();
        let mut idx = Vec::new();
        // Extent 100, so tile coordinates and 0..1 positions differ by a round 100.
        stroke(&[0, 0, 100, 0], 100, false, &mut v, &mut idx);

        assert_eq!(v.len() / FLOATS_PER_VERTEX, 4, "two points, two vertices each");
        assert_eq!(idx.len(), 6, "two triangles");
        // A segment heading +x has normal (0, 1): perpendicular, left-hand.
        for i in 0..4 {
            assert!((normal_x(&v, i) - 0.0).abs() < 1e-6);
            assert!((normal_y(&v, i) - 1.0).abs() < 1e-6);
        }
        assert!((position_x(&v, 0) - 0.0).abs() < 1e-6);
        assert!((position_x(&v, 2) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn a_plain_stroke_straddles_the_centreline() {
        let mut v = Vec::new();
        stroke(&[0, 0, 100, 0], 100, false, &mut v, &mut Vec::new());
        for i in 0..4 {
            assert_eq!(offset_mul(&v, i), 0.0);
        }
        assert_eq!(width_mul(&v, 0), -1.0);
        assert_eq!(width_mul(&v, 1), 1.0);
        assert_eq!(width_mul(&v, 2), -1.0);
        assert_eq!(width_mul(&v, 3), 1.0);
    }

    #[test]
    fn a_casing_is_two_bands_standing_off_either_side() {
        let mut v = Vec::new();
        let mut idx = Vec::new();
        stroke(&[0, 0, 100, 0], 100, true, &mut v, &mut idx);

        assert_eq!(v.len() / FLOATS_PER_VERTEX, 8, "two bands of two quads");
        assert_eq!(idx.len(), 12);
        // Band one is entirely on the +normal side: gap_half to gap_half + width, so
        // width_mul runs 0 to +2 — a band spans a full width, not a half width.
        for i in 0..4 {
            assert_eq!(offset_mul(&v, i), 1.0);
        }
        assert_eq!(width_mul(&v, 0), 0.0);
        assert_eq!(width_mul(&v, 1), 2.0);
        // Band two mirrors it.
        for i in 4..8 {
            assert_eq!(offset_mul(&v, i), -1.0);
        }
        assert_eq!(width_mul(&v, 4), 0.0);
        assert_eq!(width_mul(&v, 5), -2.0);
    }

    #[test]
    fn the_second_bands_indices_address_its_own_vertices() {
        // Getting this wrong stretches a triangle between the two bands and paints a
        // wedge across the tile.
        let mut v = Vec::new();
        let mut idx = Vec::new();
        stroke(&[0, 0, 100, 0], 100, true, &mut v, &mut idx);
        assert_eq!(idx.len(), 12, "six indices per band");
        assert!(idx[..6].iter().all(|&i| i < 4), "first band stays in its own vertices");
        assert!(idx[6..].iter().all(|&i| i >= 4), "second band must not reach into the first");
    }

    #[test]
    fn a_right_angle_join_gets_a_miter_normal() {
        // (0,0) -> (100,0) -> (100,100). The incoming normal is (0,1) and the outgoing
        // one is (-1,0); their bisector is (-1,1)/sqrt(2) and the miter length is
        // 1/cos(45°) = sqrt(2), so the normal comes out exactly (-1, 1).
        let mut v = Vec::new();
        stroke(&[0, 0, 100, 0, 100, 100], 100, false, &mut v, &mut Vec::new());
        assert_eq!(v.len() / FLOATS_PER_VERTEX, 6);
        assert!((normal_x(&v, 2) - -1.0).abs() < 1e-5, "join nx {}", normal_x(&v, 2));
        assert!((normal_y(&v, 2) - 1.0).abs() < 1e-5, "join ny {}", normal_y(&v, 2));
        assert!((normal_len(&v, 2) - 2f32.sqrt()).abs() < 1e-5);
        // The ends keep their own segment normal: a butt cap adds no geometry.
        assert!((normal_len(&v, 0) - 1.0).abs() < 1e-5);
        assert!((normal_len(&v, 4) - 1.0).abs() < 1e-5);
    }

    #[test]
    fn a_hairpin_miter_is_clamped_rather_than_throwing_a_spike() {
        let mut v = Vec::new();
        stroke(&[0, 0, 1000, 0, 0, 10], 4096, false, &mut v, &mut Vec::new());
        assert!((normal_len(&v, 2) - MITER_LIMIT).abs() < 1e-4, "got {}", normal_len(&v, 2));
    }

    #[test]
    fn a_perfect_reversal_does_not_produce_nan() {
        // An undefined bisector. A NaN normal takes the whole strip off screen, not
        // just that segment.
        let mut v = Vec::new();
        stroke(&[0, 0, 100, 0, 0, 0], 100, false, &mut v, &mut Vec::new());
        for (i, f) in v.iter().enumerate() {
            assert!(f.is_finite(), "float {i} is {f}");
        }
    }

    #[test]
    fn repeated_vertices_are_dropped() {
        let mut v = Vec::new();
        stroke(&[0, 0, 0, 0, 0, 0, 50, 0], 100, false, &mut v, &mut Vec::new());
        assert_eq!(v.len() / FLOATS_PER_VERTEX, 4, "three coincident points became one");
    }

    #[test]
    fn a_part_with_fewer_than_two_distinct_points_emits_nothing() {
        let mut v = Vec::new();
        let mut idx = Vec::new();
        stroke(&[], 100, false, &mut v, &mut idx);
        stroke(&[5, 5], 100, false, &mut v, &mut idx);
        stroke(&[5, 5, 5, 5], 100, false, &mut v, &mut idx);
        assert!(v.is_empty());
        assert!(idx.is_empty());
    }

    #[test]
    fn distance_accumulates_along_the_line_for_the_dash_pattern() {
        let mut v = Vec::new();
        stroke(&[0, 0, 300, 0, 300, 400], 100, false, &mut v, &mut Vec::new());
        assert!((distance(&v, 0) - 0.0).abs() < 1e-6);
        assert!((distance(&v, 2) - 3.0).abs() < 1e-6, "300 tile units at extent 100");
        assert!((distance(&v, 4) - 7.0).abs() < 1e-6, "plus 400 more");
    }
}
