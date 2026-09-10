//! Extrudes a building footprint into a 3D mesh: walls plus a roof cap.
//!
//! WS-A (OSM Simple 3D Buildings). Render-time only — the archive carries no triangles by
//! design, only per-feature [`BuildingAttrs`](tilecodec::mamaps::body::BuildingAttrs) in a side
//! table (heights, a roof-shape enum, colours). This module turns one footprint plus its attrs
//! into the vertex/index pair the building pipeline draws.
//!
//! # The 3D building vertex
//!
//! Seven floats — unlike the position-only fill vertex — because buildings are the one layer
//! that needs per-vertex depth, a shading normal, and its own colour:
//!
//! | offset | floats | meaning                                                        |
//! |--------|--------|----------------------------------------------------------------|
//! | 0..12  | 3      | tile-local `(u, v, height)`; `u,v` in 0..1, `height` tile-norm  |
//! | 12..24 | 3      | face normal in the same tile-local space, unit length          |
//! | 24..28 | 1      | ARGB colour packed as `R8G8B8A8_UNORM` bytes `[r, g, b, a]`     |
//!
//! `height` is **tile-normalised** — a fraction of the tile's ground width, the same unit `u`/`v`
//! are — so the mesh is zoom-independent like every other tile mesh. The building vertex shader
//! multiplies it by the tile's world-px span (a per-frame push scalar) to recover the real world-px
//! height the WS0 perspective matrix expects in its `z` input. At pitch 0 that matrix ignores `z`
//! for `x`/`y`, so a building drawn from directly overhead collapses to its footprint — the flat
//! map the app has always shown.
//!
//! # Roof shapes
//!
//! A flat roof (the common case, and the fallback for any shape this build does not model) is the
//! footprint itself, tessellated flat at the apex — so a courtyard building keeps the hole its
//! walls surround. The five pitched shapes (gabled, hipped, pyramidal, skillion, dome) are built
//! over the footprint's **oriented bounding box**, aligned to the roof direction: exact for the
//! rectangular buildings that dominate OSM, and a clean approximation for the rest, where the walls
//! still follow the true footprint.
//!
//! # Flat per-face shading
//!
//! Each triangle carries its own face normal on all three vertices (walls and roof faces are not
//! shared, so the buffer is deliberately un-indexed-sharing). The fragment shader lights that
//! constant normal against one fixed direction, so every face reads as a single flat tone with no
//! shadows — the S3DB look, and cheap.

use super::fill;
use tilecodec::mamaps::body::{
    ROOF_DOME, ROOF_GABLED, ROOF_HIPPED, ROOF_ORIENT_ACROSS, ROOF_PYRAMIDAL, ROOF_SKILLION,
};

/// Floats per building vertex: `x, y, z, nx, ny, nz, colour`.
pub const FLOATS_PER_VERTEX: usize = 7;

/// The grid resolution a dome is tessellated at, per axis of the oriented box. Eight keeps the
/// dome round without drowning a dense z16 tile in triangles.
const DOME_STEPS: usize = 8;

/// Pack an `0xAARRGGBB` colour into the `R8G8B8A8_UNORM` byte order the vertex attribute reads:
/// the four little-endian bytes of the returned `u32` are `[r, g, b, a]`, so an `f32::from_bits`
/// round-trips it into the vertex buffer unchanged (the bytes are never read as a float).
pub fn pack_argb(argb: u32) -> u32 {
    let a = (argb >> 24) & 0xFF;
    let r = (argb >> 16) & 0xFF;
    let g = (argb >> 8) & 0xFF;
    let b = argb & 0xFF;
    r | (g << 8) | (b << 16) | (a << 24)
}

/// Extrude one footprint into walls plus a roof cap, appending to `out_v`/`out_i`.
///
/// `rings` are the footprint's rings as the decoder returns them — exterior first, then holes,
/// each closed — in tile integer coordinates over `extent`. `validated` is the archive's
/// rings-normalised flag, forwarded to the fill tessellator that builds a flat roof cap.
///
/// Heights are tile-normalised (see the module docs): `base` is where the walls start
/// (`min_height`), `wall_top` where they meet the eaves (`height - roof_height`), and `apex` the
/// roof's own top (`height`). A flat roof has `wall_top == apex`.
///
/// `roof_dir_rad` is the roof direction in radians and `roof_orientation` selects which axis the
/// ridge runs along. An unrecognised `roof_shape` falls back to flat, never panics.
#[allow(clippy::too_many_arguments)]
pub fn extrude(
    rings: &[Vec<(i32, i32)>],
    extent: u32,
    validated: bool,
    base: f32,
    wall_top: f32,
    apex: f32,
    roof_shape: u8,
    roof_dir_rad: f32,
    roof_orientation: u8,
    wall_colour: u32,
    roof_colour: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    // Open, tile-local rings (0..1), the closing duplicate dropped, for the walls and the roof
    // orientation box. Degenerate rings are skipped so a stray part cannot emit a wall.
    let scale = 1.0 / extent as f32;
    let local: Vec<Vec<(f32, f32)>> = rings
        .iter()
        .map(|ring| {
            let mut n = ring.len();
            while n >= 2 && ring[0] == ring[n - 1] {
                n -= 1;
            }
            ring[..n].iter().map(|&(x, y)| (x as f32 * scale, y as f32 * scale)).collect()
        })
        .filter(|r: &Vec<(f32, f32)>| r.len() >= 3)
        .collect();
    if local.is_empty() {
        return;
    }

    let wall_top = wall_top.max(base);
    let apex = apex.max(wall_top);
    let wall_rgba = pack_argb(wall_colour);
    let roof_rgba = pack_argb(roof_colour);

    emit_walls(&local, base, wall_top, wall_rgba, out_v, out_i);
    emit_roof(rings, extent, validated, &local[0], wall_top, apex, roof_shape, roof_dir_rad, roof_orientation, roof_rgba, out_v, out_i);
}

/// A wall quad per footprint edge, from `base` to `wall_top`, with an outward-facing normal.
fn emit_walls(
    local: &[Vec<(f32, f32)>],
    base: f32,
    wall_top: f32,
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    if wall_top <= base {
        return; // A floating part flush with its base has no wall to draw.
    }
    for ring in local {
        let centroid = ring_centroid(ring);
        let n = ring.len();
        for i in 0..n {
            let (x0, y0) = ring[i];
            let (x1, y1) = ring[(i + 1) % n];
            let (ex, ey) = (x1 - x0, y1 - y0);
            if ex == 0.0 && ey == 0.0 {
                continue;
            }
            // The edge's two horizontal normals are (ey, -ex) and (-ey, ex). Pick the one that
            // points away from the ring centroid so faces are lit as if from outside — cull is off
            // in the pipeline, so this only decides shading, never visibility.
            let (mx, my) = ((x0 + x1) * 0.5, (y0 + y1) * 0.5);
            let mut nx = ey;
            let mut ny = -ex;
            if nx * (mx - centroid.0) + ny * (my - centroid.1) < 0.0 {
                nx = -nx;
                ny = -ny;
            }
            let inv = 1.0 / (nx * nx + ny * ny).sqrt();
            let normal = [nx * inv, ny * inv, 0.0];

            let base0 = [x0, y0, base];
            let base1 = [x1, y1, base];
            let top1 = [x1, y1, wall_top];
            let top0 = [x0, y0, wall_top];
            push_tri(out_v, out_i, base0, base1, top1, normal, rgba);
            push_tri(out_v, out_i, base0, top1, top0, normal, rgba);
        }
    }
}

/// The roof cap: a flat lid for a flat/unknown shape (the true footprint, holes and all), or a
/// pitched surface over the footprint's oriented box for the five modelled shapes.
#[allow(clippy::too_many_arguments)]
fn emit_roof(
    rings: &[Vec<(i32, i32)>],
    extent: u32,
    validated: bool,
    exterior: &[(f32, f32)],
    wall_top: f32,
    apex: f32,
    roof_shape: u8,
    roof_dir_rad: f32,
    roof_orientation: u8,
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    let rise = apex - wall_top;
    let pitched =
        matches!(roof_shape, ROOF_GABLED | ROOF_HIPPED | ROOF_PYRAMIDAL | ROOF_SKILLION | ROOF_DOME);
    if !pitched || rise <= 0.0 {
        flat_cap(rings, extent, validated, apex, rgba, out_v, out_i);
        return;
    }

    let obb = OrientedBox::of(exterior, roof_dir_rad, roof_orientation);
    // The roof's own centroid, at mid-roof height, used to orient every face outward.
    let (ccx, ccy) = obb.point(0.5, 0.5);
    let centre = [ccx, ccy, (wall_top + apex) * 0.5];
    let e = wall_top;
    let a = apex;
    // `(cu, av)` in the oriented box (`cu` across the slopes, `av` along the ridge) to a 3D point.
    let p = |cu: f32, av: f32, z: f32| -> [f32; 3] {
        let (x, y) = obb.point(cu, av);
        [x, y, z]
    };

    match roof_shape {
        ROOF_SKILLION => {
            // A mono-pitch slope: the `cu == 0` edge stays at the eaves, the `cu == 1` edge rises.
            roof_quad(p(0.0, 0.0, e), p(1.0, 0.0, a), p(1.0, 1.0, a), p(0.0, 1.0, e), centre, rgba, out_v, out_i);
        }
        ROOF_GABLED => {
            let (ridge0, ridge1) = (p(0.5, 0.0, a), p(0.5, 1.0, a));
            // Two slopes down to the long edges.
            roof_quad(p(0.0, 0.0, e), ridge0, ridge1, p(0.0, 1.0, e), centre, rgba, out_v, out_i);
            roof_quad(ridge0, p(1.0, 0.0, e), p(1.0, 1.0, e), ridge1, centre, rgba, out_v, out_i);
            // The two vertical gable ends.
            roof_tri(p(0.0, 0.0, e), p(1.0, 0.0, e), ridge0, centre, rgba, out_v, out_i);
            roof_tri(p(0.0, 1.0, e), ridge1, p(1.0, 1.0, e), centre, rgba, out_v, out_i);
        }
        ROOF_HIPPED => {
            // The ridge is inset from both ends; a square building hips to a point (a pyramid).
            let t = 0.5 * (obb.cross_span / obb.along_span).min(1.0);
            let (ridge0, ridge1) = (p(0.5, t, a), p(0.5, 1.0 - t, a));
            let (e00, e10) = (p(0.0, 0.0, e), p(1.0, 0.0, e));
            let (e01, e11) = (p(0.0, 1.0, e), p(1.0, 1.0, e));
            // Two long slopes.
            roof_quad(e00, e01, ridge1, ridge0, centre, rgba, out_v, out_i);
            roof_quad(e10, ridge0, ridge1, e11, centre, rgba, out_v, out_i);
            // Two hip-end triangles.
            roof_tri(e00, ridge0, e10, centre, rgba, out_v, out_i);
            roof_tri(e01, e11, ridge1, centre, rgba, out_v, out_i);
        }
        ROOF_PYRAMIDAL => {
            let top = p(0.5, 0.5, a);
            let (c00, c10) = (p(0.0, 0.0, e), p(1.0, 0.0, e));
            let (c11, c01) = (p(1.0, 1.0, e), p(0.0, 1.0, e));
            roof_tri(c00, c10, top, centre, rgba, out_v, out_i);
            roof_tri(c10, c11, top, centre, rgba, out_v, out_i);
            roof_tri(c11, c01, top, centre, rgba, out_v, out_i);
            roof_tri(c01, c00, top, centre, rgba, out_v, out_i);
        }
        ROOF_DOME => {
            let n = DOME_STEPS as f32;
            let dome = |cu: f32, av: f32| -> [f32; 3] {
                let r2 = (2.0 * cu - 1.0).powi(2) + (2.0 * av - 1.0).powi(2);
                p(cu, av, e + rise * (1.0 - r2).max(0.0).sqrt())
            };
            for i in 0..DOME_STEPS {
                for j in 0..DOME_STEPS {
                    let (cu0, cu1) = (i as f32 / n, (i + 1) as f32 / n);
                    let (av0, av1) = (j as f32 / n, (j + 1) as f32 / n);
                    roof_quad(dome(cu0, av0), dome(cu1, av0), dome(cu1, av1), dome(cu0, av1), centre, rgba, out_v, out_i);
                }
            }
        }
        _ => unreachable!("non-pitched shapes take the flat cap above"),
    }
}

/// A flat roof lid: the footprint tessellated at the apex, every face pointing straight up.
fn flat_cap(
    rings: &[Vec<(i32, i32)>],
    extent: u32,
    validated: bool,
    apex: f32,
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    let mut cap_xy: Vec<f32> = Vec::new();
    let mut cap_idx: Vec<u32> = Vec::new();
    fill::tessellate(rings, extent, validated, &mut cap_xy, &mut cap_idx);
    for tri in cap_idx.chunks_exact(3) {
        let mut pts = [[0.0f32; 3]; 3];
        for (corner, &vi) in tri.iter().enumerate() {
            let at = vi as usize * fill::FLOATS_PER_VERTEX;
            pts[corner] = [cap_xy[at], cap_xy[at + 1], apex];
        }
        push_tri(out_v, out_i, pts[0], pts[1], pts[2], [0.0, 0.0, 1.0], rgba);
    }
}

/// An oriented bounding box of a ring, aligned to the roof direction, in which the pitched roofs
/// are laid out. `point` maps `(cu, av)` in `0..=1` (across the slopes, along the ridge) to a
/// tile-local footprint point.
struct OrientedBox {
    cross: (f32, f32),
    along: (f32, f32),
    cross_min: f32,
    cross_span: f32,
    along_min: f32,
    along_span: f32,
}

impl OrientedBox {
    fn of(ring: &[(f32, f32)], roof_dir_rad: f32, orientation: u8) -> OrientedBox {
        // The ridge runs along the roof direction by default; `across` swaps the axes so the ridge
        // runs perpendicular instead. `cross` is the axis the slopes descend along.
        let (rs, rc) = roof_dir_rad.sin_cos();
        let ridge = (rc, rs);
        let perp = (-rs, rc);
        let (along, cross) =
            if orientation == ROOF_ORIENT_ACROSS { (perp, ridge) } else { (ridge, perp) };
        let mut cross_min = f32::MAX;
        let mut cross_max = f32::MIN;
        let mut along_min = f32::MAX;
        let mut along_max = f32::MIN;
        for &(x, y) in ring {
            let c = x * cross.0 + y * cross.1;
            let al = x * along.0 + y * along.1;
            cross_min = cross_min.min(c);
            cross_max = cross_max.max(c);
            along_min = along_min.min(al);
            along_max = along_max.max(al);
        }
        // A zero span would divide by zero; a hair of span keeps a needle-thin footprint finite.
        let cross_span = (cross_max - cross_min).max(f32::EPSILON);
        let along_span = (along_max - along_min).max(f32::EPSILON);
        OrientedBox { cross, along, cross_min, cross_span, along_min, along_span }
    }

    /// The tile-local footprint point at oriented-box coordinates `(cu, av)`, each in `0..=1`.
    fn point(&self, cu: f32, av: f32) -> (f32, f32) {
        let c = self.cross_min + cu * self.cross_span;
        let al = self.along_min + av * self.along_span;
        (self.cross.0 * c + self.along.0 * al, self.cross.1 * c + self.along.1 * al)
    }
}

/// The centroid of a ring's vertices (the vertex average, enough to point wall normals outward
/// for the convex-ish footprints buildings are).
fn ring_centroid(ring: &[(f32, f32)]) -> (f32, f32) {
    let mut sx = 0.0;
    let mut sy = 0.0;
    for &(x, y) in ring {
        sx += x;
        sy += y;
    }
    let inv = 1.0 / ring.len() as f32;
    (sx * inv, sy * inv)
}

/// The unit normal of the triangle `a, b, c`, or straight up for a degenerate (zero-area)
/// triangle so a sliver never emits a NaN normal.
fn face_normal(a: [f32; 3], b: [f32; 3], c: [f32; 3]) -> [f32; 3] {
    let u = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    let v = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    let n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
    let len = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
    if len <= f32::EPSILON {
        return [0.0, 0.0, 1.0];
    }
    [n[0] / len, n[1] / len, n[2] / len]
}

/// Orient a roof face normal to face outward: up for a sloped face, away from the roof centre for
/// a near-vertical one (a gable or hip end), so shading never lights a roof from inside.
fn orient(mut normal: [f32; 3], face: [f32; 3], centre: [f32; 3]) -> [f32; 3] {
    if normal[2].abs() > 0.1 {
        if normal[2] < 0.0 {
            normal = [-normal[0], -normal[1], -normal[2]];
        }
    } else {
        let d = normal[0] * (face[0] - centre[0])
            + normal[1] * (face[1] - centre[1])
            + normal[2] * (face[2] - centre[2]);
        if d < 0.0 {
            normal = [-normal[0], -normal[1], -normal[2]];
        }
    }
    normal
}

/// One roof triangle, its normal oriented outward from the roof centre.
#[allow(clippy::too_many_arguments)]
fn roof_tri(
    a: [f32; 3],
    b: [f32; 3],
    c: [f32; 3],
    centre: [f32; 3],
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    let face = [(a[0] + b[0] + c[0]) / 3.0, (a[1] + b[1] + c[1]) / 3.0, (a[2] + b[2] + c[2]) / 3.0];
    let normal = orient(face_normal(a, b, c), face, centre);
    push_tri(out_v, out_i, a, b, c, normal, rgba);
}

/// One roof quad `a, b, c, d` as two triangles sharing the quad's normal.
#[allow(clippy::too_many_arguments)]
fn roof_quad(
    a: [f32; 3],
    b: [f32; 3],
    c: [f32; 3],
    d: [f32; 3],
    centre: [f32; 3],
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    roof_tri(a, b, c, centre, rgba, out_v, out_i);
    roof_tri(a, c, d, centre, rgba, out_v, out_i);
}

/// Append one triangle: three vertices sharing `normal` and `rgba`, three fresh indices. Vertices
/// are not shared between triangles, which is what makes the shading flat per face.
fn push_tri(
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
    a: [f32; 3],
    b: [f32; 3],
    c: [f32; 3],
    normal: [f32; 3],
    rgba: u32,
) {
    let base = (out_v.len() / FLOATS_PER_VERTEX) as u32;
    for pt in [a, b, c] {
        out_v.extend_from_slice(&[pt[0], pt[1], pt[2], normal[0], normal[1], normal[2], f32::from_bits(rgba)]);
    }
    out_i.extend_from_slice(&[base, base + 1, base + 2]);
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::body::{ROOF_FLAT, ROOF_ORIENT_ALONG};

    /// A closed unit square footprint over an extent of 100, so tile-local coordinates are the
    /// integer coordinate / 100.
    fn unit_square() -> Vec<Vec<(i32, i32)>> {
        vec![vec![(0, 0), (100, 0), (100, 100), (0, 100), (0, 0)]]
    }

    /// Read back one vertex as `(position, normal, packed-rgba-bits)`.
    fn vertex(v: &[f32], i: usize) -> ([f32; 3], [f32; 3], u32) {
        let at = i * FLOATS_PER_VERTEX;
        ([v[at], v[at + 1], v[at + 2]], [v[at + 3], v[at + 4], v[at + 5]], v[at + 6].to_bits())
    }

    fn extrude_square(shape: u8, base: f32, wall_top: f32, apex: f32) -> (Vec<f32>, Vec<u32>) {
        let mut v = Vec::new();
        let mut i = Vec::new();
        extrude(
            &unit_square(),
            100,
            false,
            base,
            wall_top,
            apex,
            shape,
            0.0,
            ROOF_ORIENT_ALONG,
            0xFF_00_80_C0,
            0xFF_C0_40_20,
            &mut v,
            &mut i,
        );
        (v, i)
    }

    /// The raw f32 vertex buffer read as bits — so two meshes can be compared for equality without
    /// a NaN colour pattern making a vertex differ from itself.
    fn bits(v: &[f32]) -> Vec<u32> {
        v.iter().map(|f| f.to_bits()).collect()
    }

    #[test]
    fn colour_packs_to_rgba_byte_order() {
        // 0xAARRGGBB in, little-endian [r, g, b, a] out, so R8G8B8A8_UNORM reads it upright.
        let packed = pack_argb(0xFF_11_22_33);
        assert_eq!(packed & 0xFF, 0x11, "byte 0 is red");
        assert_eq!((packed >> 8) & 0xFF, 0x22, "byte 1 is green");
        assert_eq!((packed >> 16) & 0xFF, 0x33, "byte 2 is blue");
        assert_eq!((packed >> 24) & 0xFF, 0xFF, "byte 3 is alpha");
    }

    #[test]
    fn a_box_with_height_emits_walls_and_a_roof() {
        // A gabled box: four walls plus a roof cap, so there is geometry both at the base and at
        // the apex, and the index buffer is whole triangles.
        let (v, i) = extrude_square(ROOF_GABLED, 0.0, 0.2, 0.35);
        assert!(!i.is_empty(), "a building with height must produce triangles");
        assert_eq!(i.len() % 3, 0, "indices come in threes");
        assert_eq!(v.len() % FLOATS_PER_VERTEX, 0, "vertices are whole");

        let count = v.len() / FLOATS_PER_VERTEX;
        let mut saw_base = false;
        let mut saw_apex = false;
        for k in 0..count {
            let (p, _, _) = vertex(&v, k);
            saw_base |= (p[2] - 0.0).abs() < 1e-6;
            saw_apex |= (p[2] - 0.35).abs() < 1e-6;
        }
        assert!(saw_base, "walls must start at the base height");
        assert!(saw_apex, "the gabled ridge must reach the apex height");
    }

    #[test]
    fn every_vertex_carries_the_right_colour() {
        // Walls take the building colour, the roof takes the roof colour; every vertex is one or
        // the other and nothing is left uncoloured.
        let (v, _) = extrude_square(ROOF_GABLED, 0.0, 0.2, 0.35);
        let wall = pack_argb(0xFF_00_80_C0);
        let roof = pack_argb(0xFF_C0_40_20);
        let count = v.len() / FLOATS_PER_VERTEX;
        assert!(count > 0);
        for k in 0..count {
            let (_, _, argb) = vertex(&v, k);
            assert!(argb == wall || argb == roof, "vertex {k} colour {argb:#010x} is neither wall nor roof");
        }
    }

    #[test]
    fn an_unknown_roof_shape_falls_back_to_flat() {
        // The format never stores an out-of-range shape, but the renderer must still not guess:
        // shape 200 has to extrude exactly as a flat roof does. Compared by bits because the
        // packed colour is a float NaN pattern that would never equal itself.
        let (unknown, ui) = extrude_square(200, 0.0, 0.3, 0.3);
        let (flat, fi) = extrude_square(ROOF_FLAT, 0.0, 0.3, 0.3);
        assert_eq!(bits(&unknown), bits(&flat), "an unknown shape must render as flat");
        assert_eq!(ui, fi);
    }

    #[test]
    fn a_flat_roof_caps_level_at_the_apex() {
        // With wall_top == apex the roof is a flat lid; every roof vertex sits at the apex and its
        // normal points straight up.
        let (v, _) = extrude_square(ROOF_FLAT, 0.0, 0.25, 0.25);
        let count = v.len() / FLOATS_PER_VERTEX;
        let mut roof_vertices = 0;
        for k in 0..count {
            let (p, n, _) = vertex(&v, k);
            if n[2] > 0.9 {
                roof_vertices += 1;
                assert!((p[2] - 0.25).abs() < 1e-6, "a flat roof vertex must sit at the apex");
                assert!(n[0].abs() < 1e-6 && n[1].abs() < 1e-6, "a flat roof normal is straight up");
            }
        }
        assert!(roof_vertices >= 3, "the flat cap must tessellate to at least one triangle");
    }

    #[test]
    fn the_footprint_survives_overhead_projection() {
        // From directly overhead the height column drops out, so the mesh must read as its
        // footprint: every vertex's (x, y) stays within the unit square.
        let (v, _) = extrude_square(ROOF_PYRAMIDAL, 0.0, 0.2, 0.6);
        let count = v.len() / FLOATS_PER_VERTEX;
        assert!(count > 0);
        let mut peak = 0.0f32;
        let mut peak_xy = (0.0, 0.0);
        for k in 0..count {
            let (p, _, _) = vertex(&v, k);
            assert!((-1e-4..=1.0 + 1e-4).contains(&p[0]), "x {} leaves the footprint", p[0]);
            assert!((-1e-4..=1.0 + 1e-4).contains(&p[1]), "y {} leaves the footprint", p[1]);
            if p[2] > peak {
                peak = p[2];
                peak_xy = (p[0], p[1]);
            }
        }
        assert!((peak - 0.6).abs() < 1e-4, "the apex reaches the full height");
        assert!((peak_xy.0 - 0.5).abs() < 0.05 && (peak_xy.1 - 0.5).abs() < 0.05, "the apex is over the centre");
    }

    #[test]
    fn a_floating_part_flush_with_its_base_draws_no_walls() {
        // base == wall_top: a roof-only part contributes only its cap, never a zero-height wall.
        let (v, _) = extrude_square(ROOF_FLAT, 0.3, 0.3, 0.3);
        let count = v.len() / FLOATS_PER_VERTEX;
        for k in 0..count {
            let (_, n, _) = vertex(&v, k);
            assert!(n[2] > 0.9, "with no wall every triangle is roof-facing");
        }
    }

    #[test]
    fn a_skillion_roof_slopes_from_one_edge_to_the_other() {
        // A mono-pitch roof: one edge stays at the eaves, the opposite edge rises to the apex.
        let (v, _) = extrude_square(ROOF_SKILLION, 0.0, 0.2, 0.5);
        let count = v.len() / FLOATS_PER_VERTEX;
        let mut low = f32::MAX;
        let mut high = f32::MIN;
        for k in 0..count {
            let (p, n, _) = vertex(&v, k);
            if n[2] > 0.1 {
                low = low.min(p[2]);
                high = high.max(p[2]);
            }
        }
        assert!((low - 0.2).abs() < 1e-4, "the low edge stays at the eaves, got {low}");
        assert!((high - 0.5).abs() < 1e-4, "the high edge reaches the apex, got {high}");
    }

    #[test]
    fn empty_rings_emit_nothing() {
        let mut v = Vec::new();
        let mut i = Vec::new();
        extrude(&[], 100, false, 0.0, 0.2, 0.4, ROOF_FLAT, 0.0, ROOF_ORIENT_ALONG, 0xFFFFFFFF, 0xFFFFFFFF, &mut v, &mut i);
        assert!(v.is_empty() && i.is_empty());
    }
}
