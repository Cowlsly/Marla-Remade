//! Mesh shading rasterizers for Type 4-7 shadings (free-form/lattice Gouraud,
//! Coons and tensor-product patch meshes). Types 1-3 (function-based, axial,
//! radial) live in `images.rs::rasterize_shading`.
//!
//! Each shading is decoded into an RGBA image plus a placement CTM. Colors are
//! evaluated through the shared colorspace machinery (`crate::eval_cs_to_rgb`,
//! which understands Separation/DeviceN/Lab/ICC via `crate::PdfFunction`), so
//! mesh colors are as faithful as flat fills.

use crate::*;

/// Big-endian bit reader over a packed mesh data stream (PDF 7.10.5: all values
/// are packed high-order-bit first with no inter-record padding).
struct BitReader<'a> {
    data: &'a [u8],
    bitpos: usize,
}

impl<'a> BitReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        BitReader { data, bitpos: 0 }
    }
    fn remaining_bits(&self) -> usize {
        (self.data.len() * 8).saturating_sub(self.bitpos)
    }
    /// Skip to the next byte boundary, discarding any padding bits. Free-form
    /// (Type 4) meshes pad each *vertex* and Coons/tensor (Type 6/7) meshes pad
    /// each *patch* to a whole number of bytes; without this every record after
    /// the first non-byte-aligned one decodes as garbage.
    fn align(&mut self) {
        self.bitpos = (self.bitpos + 7) & !7;
    }
    /// Read `bits` (<=64) as an unsigned integer, or `None` if exhausted.
    fn read(&mut self, bits: u32) -> Option<u64> {
        if bits == 0 {
            return Some(0);
        }
        if self.remaining_bits() < bits as usize {
            return None;
        }
        let mut v: u64 = 0;
        for _ in 0..bits {
            let byte = self.data[self.bitpos / 8];
            let bit = 7 - (self.bitpos % 8) as u32;
            v = (v << 1) | ((byte >> bit) & 1) as u64;
            self.bitpos += 1;
        }
        Some(v)
    }
}

fn max_for_bits(bits: u32) -> f64 {
    if bits >= 64 {
        u64::MAX as f64
    } else {
        ((1u64 << bits) - 1) as f64
    }
}

fn map_val(raw: u64, dmin: f64, dmax: f64, bits: u32) -> f64 {
    let m = max_for_bits(bits);
    if m == 0.0 {
        dmin
    } else {
        dmin + (raw as f64 / m) * (dmax - dmin)
    }
}

#[derive(Clone)]
struct Vertex {
    x: f64,
    y: f64,
    color: Vec<f64>,
}

/// Rasterize a Type 4-7 shading into an RGBA image + placement CTM.
/// `mesh_bytes` is the decoded shading-stream body (the mesh data source).
pub fn rasterize_shading_mesh(
    doc: &Document,
    dict: &Dictionary,
    mesh_bytes: Option<&[u8]>,
    base_ctm: &Mat,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
    size: u32,
) -> Option<(Mat, u32, u32, Vec<u8>)> {
    let shading_type = dict.get(b"ShadingType").ok().and_then(num).unwrap_or(0.0) as i64;
    if ![4, 5, 6, 7].contains(&shading_type) {
        return None;
    }
    if size == 0 || size > 1024 {
        return None;
    }

    let cs_kind = dict
        .get(b"ColorSpace")
        .ok()
        .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
        .unwrap_or(CsKind::DeviceRGB);
    // If the shading has a /Function, colors are 1-in scalars mapped through it.
    let func = dict.get(b"Function").ok().and_then(|o| PdfFunction::parse(doc, o));
    // Otherwise each vertex carries one value per component of the colour space —
    // except for Indexed, where §8.6.6.3 makes a colour value a SINGLE palette index
    // and `eval_cs_to_rgb`'s Indexed arm reads exactly one. `cs_kind_ncomp` reports
    // the BASE arity (3 for an RGB base), so reading that many values per vertex
    // consumed 3x the bits, desynchronising the packed stream from the second vertex
    // onward — every later vertex decodes from the middle of its predecessor and the
    // mesh becomes spectacular noise, on top of every colour resolving to index ~0.
    let ncomp = if func.is_some() {
        1
    } else if matches!(cs_kind, CsKind::Indexed { .. }) {
        1
    } else {
        cs_kind_ncomp(&cs_kind) as usize
    };
    if ncomp == 0 {
        return None;
    }

    let bps_coord = dict.get(b"BitsPerCoordinate").ok().and_then(num).unwrap_or(16.0) as u32;
    let bps_comp = dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32;
    let bps_flag = dict.get(b"BitsPerFlag").ok().and_then(num).unwrap_or(8.0) as u32;
    if bps_coord == 0 || bps_coord > 32 || bps_comp == 0 || bps_comp > 16 || bps_flag > 8 {
        return None;
    }

    let decode: Vec<f64> = dict
        .get(b"Decode")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect())
        .unwrap_or_default();
    if decode.len() < 4 {
        return None;
    }
    // Coordinate bounds from Decode: [xmin xmax ymin ymax ...].
    let (xmin, xmax, ymin, ymax) = (decode[0], decode[1], decode[2], decode[3]);
    if (xmax - xmin).abs() < 1e-9 || (ymax - ymin).abs() < 1e-9 {
        return None;
    }
    // Every vertex coordinate is `dmin + (raw/max)*(dmax-dmin)`, so an infinite
    // /Decode extent makes `0 * inf` = NaN for raw == 0. NaN then defeats
    // `fill_tri`'s barycentric rejection (every `< -1e-6` test is false for NaN),
    // the colour cast saturates to 0, and the shading paints an OPAQUE BLACK
    // rectangle over its whole area — covering page content that should show
    // through. Reject the shading instead: with no usable geometry the only
    // correct answer is to paint nothing, the same conclusion the empty-mesh
    // branch below reaches.
    if ![xmin, xmax, ymin, ymax].iter().all(|v| v.is_finite()) {
        return None;
    }
    let bounds = {
        let mut b = [xmin, ymin, xmax, ymax];
        // The shading shall be painted only inside /BBox (Table 78), which is
        // expressed in the shading's own target coordinate space. Intersecting it
        // with the /Decode extent means geometry outside the box lands outside the
        // raster and is never drawn, and the raster resolution is spent only on
        // the visible region. /BBox may be given unnormalised, so order it first.
        if let Some(bb) = dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
            let (bx0, bx1) = (bb[0].min(bb[2]), bb[0].max(bb[2]));
            let (by0, by1) = (bb[1].min(bb[3]), bb[1].max(bb[3]));
            b = [b[0].max(bx0), b[1].max(by0), b[2].min(bx1), b[3].min(by1)];
            if b[2] - b[0] < 1e-9 || b[3] - b[1] < 1e-9 {
                return None;
            }
        }
        b
    };

    // Prefer the stream body; fall back to a /DataSource stream/string.
    let owned_ds;
    let data: &[u8] = match mesh_bytes {
        Some(b) if !b.is_empty() => b,
        _ => {
            owned_ds = dict
                .get(b"DataSource")
                .ok()
                .and_then(|o| deref(doc, o))
                .and_then(|o| match o {
                    Object::Stream(s) => Some(stream_data_with_doc(doc, s)),
                    Object::String(bytes, _) => Some(bytes.clone()),
                    _ => None,
                })?;
            &owned_ds
        }
    };

    let color_of = |comps: &[f64]| -> u32 {
        let mapped;
        let use_comps: &[f64] = if let Some(f) = &func {
            mapped = f.eval(&[comps.first().copied().unwrap_or(0.0)]);
            &mapped
        } else {
            comps
        };
        eval_cs_to_rgb(doc, &cs_kind, use_comps, cs_resources).unwrap_or(0xFF80_8080)
    };

    // `/VerticesPerRow` is read before the raster is allocated so a degenerate value
    // still short-circuits without any allocation (§8.7.4.5.6 requires >= 2).
    let vpr = if shading_type == 5 {
        let v = dict.get(b"VerticesPerRow").ok().and_then(num).unwrap_or(0.0) as usize;
        if v < 2 {
            return None;
        }
        v
    } else {
        0
    };

    // Size the raster to the shading's aspect ratio instead of forcing a square.
    // A wide, shallow gradient band used to allocate size*size regardless (up to
    // 4 MB for a few-point-tall bar), and a page with many shadings multiplied that
    // waste through `prims`, the wire buffer and again as Kotlin bitmaps. The
    // placement CTM maps the unit square, so a non-square raster is geometrically
    // identical. Both extents are already known non-degenerate.
    let (w, h) = {
        let bw = bounds[2] - bounds[0];
        let bh = bounds[3] - bounds[1];
        let long = size as usize;
        let short = |r: f64| (((size as f64) * r).round() as usize).clamp(1, long);
        if bh <= bw { (long, short(bh / bw)) } else { (short(bw / bh), long) }
    };
    // Bound this single raster's bytes as well as its shape — see
    // MAX_SHADING_RASTER_BYTES for why per-page peak residency, not per-shading
    // size, is the binding constraint. Scaling both axes by sqrt keeps the aspect.
    let (w, h) = {
        let bytes = w.saturating_mul(h).saturating_mul(4);
        if bytes > MAX_SHADING_RASTER_BYTES {
            let s = (MAX_SHADING_RASTER_BYTES as f64 / bytes as f64).sqrt();
            (
                ((w as f64 * s).round() as usize).max(1),
                ((h as f64 * s).round() as usize).max(1),
            )
        } else {
            (w, h)
        }
    };
    let mut rgba = vec![0u8; w * h * 4];

    // Rasterize each triangle as it is decoded rather than materialising the whole
    // mesh first. The mesh types are the one place in this file where an allocation
    // is sized purely from file values AND amplified: MAX_SHADING_PATCHES is 8000 and
    // each Coons patch tessellates to 2*8*8 = 128 triangles, so 8000 patches * 29
    // bytes = 232 KB of stream (a couple of KB once Flate-compressed) expanded into a
    // MAX_SHADING_TRIANGLES-long `Vec<(Vertex, Vertex, Vertex)>` — ~120 bytes inline
    // plus three heap `Vec<f64>` colour tuples per element, so hundreds of MB and
    // millions of allocations. A Rust OOM is an uncatchable process abort, so this
    // cannot be recovered from downstream. Streaming holds one patch at a time and
    // paints in exactly the same order, so the raster is byte-identical.
    let mut painted = 0usize;
    {
        let mut emit = |v0: &Vertex, v1: &Vertex, v2: &Vertex| {
            let c0 = color_of(&v0.color);
            let c1 = color_of(&v1.color);
            let c2 = color_of(&v2.color);
            fill_tri(&mut rgba, w, h, &bounds, (v0, v1, v2), (c0, c1, c2));
            painted += 1;
        };
        match shading_type {
            4 => parse_type4(data, bps_flag, bps_coord, bps_comp, ncomp, &decode, &mut emit),
            5 => parse_type5(data, vpr, bps_coord, bps_comp, ncomp, &decode, &mut emit),
            6 | 7 => parse_type6_7(
                data, shading_type, bps_flag, bps_coord, bps_comp, ncomp, &decode, &mut emit,
            ),
            _ => {}
        }
    }

    if painted == 0 {
        // Nothing parseable. Do NOT fall back to flooding the area with
        // /Background: per Table 78 /Background fills only the portions that lie
        // OUTSIDE the shading's own extent and "shall be ignored by the sh
        // operator", so painting it over the whole Decode box turned a failed
        // mesh into an opaque rectangle covering the entire clip — hiding page
        // content. With no triangles we cannot know the shading's extent, so the
        // only correct answer is to paint nothing at all.
        return None;
    }

    let ctm = placement_ctm(&bounds, base_ctm);
    Some((ctm, w as u32, h as u32, rgba))
}

fn placement_ctm(bounds: &[f64; 4], base_ctm: &Mat) -> Mat {
    let bw = bounds[2] - bounds[0];
    let bh = bounds[3] - bounds[1];
    let shading_mat: Mat = [bw, 0.0, 0.0, bh, bounds[0], bounds[1]];
    mat_mul(&shading_mat, base_ctm)
}

/// Read one vertex (x, y, color components) from the bit stream.
fn read_vertex(
    br: &mut BitReader,
    bps_coord: u32,
    bps_comp: u32,
    ncomp: usize,
    decode: &[f64],
) -> Option<Vertex> {
    let rx = br.read(bps_coord)?;
    let ry = br.read(bps_coord)?;
    let x = map_val(rx, decode[0], decode[1], bps_coord);
    let y = map_val(ry, decode[2], decode[3], bps_coord);
    let mut color = Vec::with_capacity(ncomp);
    for c in 0..ncomp {
        let cmin = decode.get(4 + c * 2).copied().unwrap_or(0.0);
        let cmax = decode.get(4 + c * 2 + 1).copied().unwrap_or(1.0);
        let raw = br.read(bps_comp)?;
        color.push(map_val(raw, cmin, cmax, bps_comp));
    }
    Some(Vertex { x, y, color })
}

/// A sink for decoded mesh triangles. Triangles are handed to the caller one at a
/// time instead of being collected, so peak memory stays proportional to a single
/// patch rather than to `MAX_SHADING_TRIANGLES`.
type Emit<'a> = &'a mut dyn FnMut(&Vertex, &Vertex, &Vertex);

/// Type 4: free-form Gouraud-shaded triangle mesh (flag-driven strips/fans).
fn parse_type4(
    data: &[u8],
    bps_flag: u32,
    bps_coord: u32,
    bps_comp: u32,
    ncomp: usize,
    decode: &[f64],
    emit: Emit,
) {
    let mut br = BitReader::new(data);
    let mut emitted = 0usize;
    // `last` holds the most recently completed triangle so flag 1/2 continuation
    // vertices can form strips/fans; `pending` accumulates the three flag-0
    // vertices that begin a new independent triangle (PDF 8.7.4.5.5).
    let mut last: Option<(Vertex, Vertex, Vertex)> = None;
    let mut pending: Vec<Vertex> = Vec::new();
    let mut guard = 0usize;
    while br.remaining_bits() >= (bps_flag + 2 * bps_coord + ncomp as u32 * bps_comp) as usize {
        guard += 1;
        if guard > 2_000_000 || emitted >= MAX_SHADING_TRIANGLES { break; }
        let flag = br.read(bps_flag).unwrap_or(0);
        let v = match read_vertex(&mut br, bps_coord, bps_comp, ncomp, decode) { Some(v) => v, None => break };
        // Each vertex's data occupies a whole number of bytes; trailing padding
        // bits in the last byte are ignored (ISO 32000-1 8.7.4.5.5).
        br.align();
        match flag {
            0 => {
                // Start (or continue accumulating) a new independent triangle: its
                // three vertices all carry flag 0.
                pending.push(v);
                if pending.len() == 3 {
                    let t = (pending[0].clone(), pending[1].clone(), pending[2].clone());
                    emit(&t.0, &t.1, &t.2);
                    emitted += 1;
                    last = Some(t);
                    pending.clear();
                }
            }
            1 => {
                // Continuation: new triangle = (vb, vc, v) of the previous triangle.
                pending.clear();
                if let Some((_a, b, c)) = last.clone() {
                    let t = (b, c, v);
                    emit(&t.0, &t.1, &t.2);
                    emitted += 1;
                    last = Some(t);
                }
            }
            2 => {
                // Continuation: new triangle = (va, vc, v) of the previous triangle.
                pending.clear();
                if let Some((a, _b, c)) = last.clone() {
                    let t = (a, c, v);
                    emit(&t.0, &t.1, &t.2);
                    emitted += 1;
                    last = Some(t);
                }
            }
            _ => break,
        }
    }
}

/// Type 5: lattice-form Gouraud mesh (row-major, `VerticesPerRow`).
fn parse_type5(
    data: &[u8],
    vpr: usize,
    bps_coord: u32,
    bps_comp: u32,
    ncomp: usize,
    decode: &[f64],
    emit: Emit,
) {
    let mut br = BitReader::new(data);
    // Only two rows are ever live at once, so the whole lattice never has to be
    // resident even when the stream declares millions of vertices.
    let read_row = |br: &mut BitReader| -> Option<Vec<Vertex>> {
        let mut row = Vec::with_capacity(vpr.min(4096));
        for _ in 0..vpr {
            row.push(read_vertex(br, bps_coord, bps_comp, ncomp, decode)?);
        }
        Some(row)
    };
    let mut prev = match read_row(&mut br) {
        Some(r) => r,
        None => return,
    };
    let mut emitted = 0usize;
    while let Some(cur) = read_row(&mut br) {
        for c in 0..vpr - 1 {
            if emitted >= MAX_SHADING_TRIANGLES {
                return;
            }
            emit(&prev[c], &prev[c + 1], &cur[c]);
            emit(&cur[c], &prev[c + 1], &cur[c + 1]);
            emitted += 2;
        }
        prev = cur;
    }
}

fn bezier(p: [(f64, f64); 4], t: f64) -> (f64, f64) {
    let mt = 1.0 - t;
    let a = mt * mt * mt;
    let b = 3.0 * mt * mt * t;
    let c = 3.0 * mt * t * t;
    let d = t * t * t;
    (
        a * p[0].0 + b * p[1].0 + c * p[2].0 + d * p[3].0,
        a * p[0].1 + b * p[1].1 + c * p[2].1 + d * p[3].1,
    )
}

/// Type 6 (Coons) / Type 7 (tensor-product) patch mesh. Patches are subdivided
/// on a grid using the real boundary Bézier curves and bilinear corner-color
/// interpolation.
fn parse_type6_7(
    data: &[u8],
    shading_type: i64,
    bps_flag: u32,
    bps_coord: u32,
    bps_comp: u32,
    ncomp: usize,
    decode: &[f64],
    emit: Emit,
) {
    let n_pts = if shading_type == 6 { 12 } else { 16 };
    let mut br = BitReader::new(data);
    // Previous patch boundary (12 boundary points) + corner colors, for
    // edge-sharing when flag != 0.
    let mut prev_pts: Vec<(f64, f64)> = Vec::new();
    let mut prev_cols: Vec<Vec<f64>> = Vec::new();
    let mut emitted = 0usize;
    let mut patches = 0usize;

    let read_point = |br: &mut BitReader| -> Option<(f64, f64)> {
        let rx = br.read(bps_coord)?;
        let ry = br.read(bps_coord)?;
        Some((
            map_val(rx, decode[0], decode[1], bps_coord),
            map_val(ry, decode[2], decode[3], bps_coord),
        ))
    };
    let read_color = |br: &mut BitReader| -> Option<Vec<f64>> {
        let mut col = Vec::with_capacity(ncomp);
        for c in 0..ncomp {
            let cmin = decode.get(4 + c * 2).copied().unwrap_or(0.0);
            let cmax = decode.get(4 + c * 2 + 1).copied().unwrap_or(1.0);
            col.push(map_val(br.read(bps_comp)?, cmin, cmax, bps_comp));
        }
        Some(col)
    };

    loop {
        if patches >= MAX_SHADING_PATCHES || emitted >= MAX_SHADING_TRIANGLES {
            break;
        }
        if br.remaining_bits() < bps_flag as usize {
            break;
        }
        let flag = match br.read(bps_flag) {
            Some(f) => f,
            None => break,
        };

        // 12 boundary control points (p1..p12) and 4 corner colors for this patch.
        // Type 7 additionally has 4 interior control points (p13..p16).
        let mut pts: Vec<(f64, f64)> = Vec::with_capacity(12);
        let mut interior: Vec<(f64, f64)> = Vec::with_capacity(4);
        let mut cols: Vec<Vec<f64>> = Vec::with_capacity(4);

        if flag == 0 || prev_pts.len() < 12 || prev_cols.len() < 4 {
            // Full patch: read all points + 4 colors.
            let mut all = Vec::with_capacity(n_pts);
            let mut ok = true;
            for _ in 0..n_pts {
                match read_point(&mut br) {
                    Some(p) => all.push(p),
                    None => { ok = false; break; }
                }
            }
            if !ok || all.len() < 12 {
                break;
            }
            pts = all[0..12].to_vec(); // p1..p12 boundary points
            if shading_type == 7 && all.len() >= 16 {
                interior = all[12..16].to_vec(); // p13..p16 tensor interior
            }
            let mut cok = true;
            for _ in 0..4 {
                match read_color(&mut br) {
                    Some(c) => cols.push(c),
                    None => { cok = false; break; }
                }
            }
            if !cok {
                break;
            }
        } else {
            // Shared-edge patch: 8 new points (coons) / 12 (tensor) + 2 colors.
            // The shared edge (4 pts, 2 colors) comes from the previous patch,
            // selected by flag (1/2/3 => which previous edge).
            let (shared_pts, shared_c0, shared_c1) = shared_edge(&prev_pts, &prev_cols, flag);
            let new_pts_count = if shading_type == 6 { 8 } else { 12 };
            let mut new_pts = Vec::with_capacity(new_pts_count);
            let mut ok = true;
            for _ in 0..new_pts_count {
                match read_point(&mut br) {
                    Some(p) => new_pts.push(p),
                    None => { ok = false; break; }
                }
            }
            if !ok {
                break;
            }
            // Boundary = shared edge (4) + next 8 new boundary points; for tensor
            // the final 4 new points are the interior control points.
            if shading_type == 7 && new_pts.len() >= 12 {
                interior = new_pts[8..12].to_vec();
            }
            pts.extend_from_slice(&shared_pts);
            pts.extend(new_pts.into_iter().take(8));
            if pts.len() < 12 {
                break;
            }
            let c2;
            let c3;
            if let Some(c) = read_color(&mut br) { c2 = Some(c); } else { break; }
            if let Some(c) = read_color(&mut br) { c3 = Some(c); } else { break; }
            cols.push(shared_c0);
            cols.push(shared_c1);
            cols.push(c2.unwrap());
            cols.push(c3.unwrap());
        }

        // Each patch's data occupies a whole number of bytes; trailing padding
        // bits in the last byte are ignored (ISO 32000-1 8.7.4.5.7).
        br.align();

        // Corners of the boundary loop: p1=pts[0], p4=pts[3], p7=pts[6], p10=pts[9].
        let e_left = [pts[0], pts[1], pts[2], pts[3]]; // C00 -> C01
        let e_top = [pts[3], pts[4], pts[5], pts[6]]; // C01 -> C11
        let e_right = [pts[6], pts[7], pts[8], pts[9]]; // C11 -> C10
        let e_bottom = [pts[9], pts[10], pts[11], pts[0]]; // C10 -> C00
        let c00 = cols.first().cloned().unwrap_or_default();
        let c01 = cols.get(1).cloned().unwrap_or_default();
        let c11 = cols.get(2).cloned().unwrap_or_default();
        let c10 = cols.get(3).cloned().unwrap_or_default();

        // Subdivide the patch surface on an N×N grid. Coons (Type 6) uses the
        // bilinearly-blended boundary surface; tensor (Type 7) uses the full
        // bicubic Bézier defined by the 12 boundary + 4 interior control points.
        const N: usize = 8;
        // Build the 4×4 tensor control grid P[i][j] from the PDF point ordering.
        let tensor_grid: Option<[[(f64, f64); 4]; 4]> = if shading_type == 7 && interior.len() == 4 {
            let mut p = [[(0.0, 0.0); 4]; 4];
            p[0][0] = pts[0];  p[0][1] = pts[1];  p[0][2] = pts[2];  p[0][3] = pts[3];
            p[1][3] = pts[4];  p[2][3] = pts[5];  p[3][3] = pts[6];  p[3][2] = pts[7];
            p[3][1] = pts[8];  p[3][0] = pts[9];  p[2][0] = pts[10]; p[1][0] = pts[11];
            p[1][1] = interior[0]; p[1][2] = interior[1]; p[2][2] = interior[2]; p[2][1] = interior[3];
            Some(p)
        } else {
            None
        };
        let bern = |t: f64| -> [f64; 4] {
            let mt = 1.0 - t;
            [mt*mt*mt, 3.0*t*mt*mt, 3.0*t*t*mt, t*t*t]
        };
        let surf = |u: f64, v: f64| -> (f64, f64) {
            if let Some(p) = tensor_grid {
                let bu = bern(u);
                let bv = bern(v);
                let mut sx = 0.0;
                let mut sy = 0.0;
                for i in 0..4 {
                    for j in 0..4 {
                        let w = bu[i] * bv[j];
                        sx += w * p[i][j].0;
                        sy += w * p[i][j].1;
                    }
                }
                return (sx, sy);
            }
            let left = bezier(e_left, v);
            let right = {
                // u=1 edge from C10(v=0) to C11(v=1): reverse e_right (C11->C10)
                let rp = [e_right[3], e_right[2], e_right[1], e_right[0]];
                bezier(rp, v)
            };
            let bottom = {
                // v=0 edge from C00(u=0) to C10(u=1): reverse e_bottom (C10->C00)
                let bp = [e_bottom[3], e_bottom[2], e_bottom[1], e_bottom[0]];
                bezier(bp, u)
            };
            let top = bezier(e_top, u); // v=1 edge C01->C11
            let c00p = e_left[0];
            let c01p = e_left[3];
            let c11p = e_top[3];
            let c10p = e_bottom[0];
            let sx = (1.0 - u) * left.0 + u * right.0 + (1.0 - v) * bottom.0 + v * top.0
                - ((1.0 - u) * (1.0 - v) * c00p.0 + u * (1.0 - v) * c10p.0 + (1.0 - u) * v * c01p.0 + u * v * c11p.0);
            let sy = (1.0 - u) * left.1 + u * right.1 + (1.0 - v) * bottom.1 + v * top.1
                - ((1.0 - u) * (1.0 - v) * c00p.1 + u * (1.0 - v) * c10p.1 + (1.0 - u) * v * c01p.1 + u * v * c11p.1);
            (sx, sy)
        };
        let color_at = |u: f64, v: f64| -> Vec<f64> {
            let n = ncomp;
            (0..n)
                .map(|k| {
                    let a = c00.get(k).copied().unwrap_or(0.0);
                    let b = c10.get(k).copied().unwrap_or(0.0);
                    let c = c01.get(k).copied().unwrap_or(0.0);
                    let d = c11.get(k).copied().unwrap_or(0.0);
                    (1.0 - u) * (1.0 - v) * a + u * (1.0 - v) * b + (1.0 - u) * v * c + u * v * d
                })
                .collect()
        };
        let mut grid: Vec<Vec<Vertex>> = Vec::with_capacity(N + 1);
        for iv in 0..=N {
            let v = iv as f64 / N as f64;
            let mut row = Vec::with_capacity(N + 1);
            for iu in 0..=N {
                let u = iu as f64 / N as f64;
                let (x, y) = surf(u, v);
                row.push(Vertex { x, y, color: color_at(u, v) });
            }
            grid.push(row);
        }
        for iv in 0..N {
            for iu in 0..N {
                let a = &grid[iv][iu];
                let b = &grid[iv][iu + 1];
                let c = &grid[iv + 1][iu];
                let d = &grid[iv + 1][iu + 1];
                emit(a, b, c);
                emit(c, b, d);
                emitted += 2;
            }
        }

        prev_pts = pts;
        prev_cols = cols;
        patches += 1;
    }
}

/// Select the shared edge (4 control points + 2 corner colors) from the
/// previous patch for a flag-1/2/3 continuation, per ISO 32000 Table 85 (Coons)
/// / Table 86 (tensor). The new patch's first edge (p1..p4, colors c1,c2) is the
/// previous patch's edge in FORWARD order so the patches join without twisting:
///   flag 1 -> prev p4,p5,p6,p7 & colors c2,c3
///   flag 2 -> prev p7,p8,p9,p10 & colors c3,c4
///   flag 3 -> prev p10,p11,p12,p1 & colors c4,c1
fn shared_edge(prev_pts: &[(f64, f64)], prev_cols: &[Vec<f64>], flag: u64) -> ([(f64, f64); 4], Vec<f64>, Vec<f64>) {
    // Boundary points p1..p12 = index 0..11; corner colors c1..c4 = index 0..3.
    let (ia, ib, ic, id, ca, cb) = match flag {
        1 => (3usize, 4, 5, 6, 1usize, 2usize),
        2 => (6usize, 7, 8, 9, 2usize, 3usize),
        _ => (9usize, 10, 11, 0, 3usize, 0usize),
    };
    let g = |i: usize| prev_pts.get(i).copied().unwrap_or((0.0, 0.0));
    let col = |i: usize| prev_cols.get(i).cloned().unwrap_or_default();
    ([g(ia), g(ib), g(ic), g(id)], col(ca), col(cb))
}

/// Rasterize a single Gouraud triangle into the RGBA buffer over `bounds`.
fn fill_tri(
    rgba: &mut [u8],
    w: usize,
    h: usize,
    bounds: &[f64; 4],
    v: (&Vertex, &Vertex, &Vertex),
    colors: (u32, u32, u32),
) {
    let (v0, v1, v2) = v;
    let (c0, c1, c2) = colors;
    let (x0, y0) = (v0.x, v0.y);
    let (x1, y1) = (v1.x, v1.y);
    let (x2, y2) = (v2.x, v2.y);
    // A non-finite vertex is not merely unpaintable, it paints EVERYTHING: the
    // scanline bounds below collapse to the full raster (`NaN.max(0.0)` is 0 and
    // `NaN.min(w)` is w), and every barycentric rejection test `a < -1e-6` is false
    // for NaN, so the whole buffer is filled opaque. Bail before that.
    if ![x0, y0, x1, y1, x2, y2].iter().all(|c| c.is_finite()) {
        return;
    }
    let bw = bounds[2] - bounds[0];
    let bh = bounds[3] - bounds[1];
    let to_px = |x: f64| (x - bounds[0]) / bw * w as f64;
    // Raster row 0 is the TOP of the image (unit-square v=1, ISO 32000-1 8.9.5.2), so the
    // HIGH-y edge of `bounds` maps to py 0. Mapping bounds[1] to row 0 instead mirrored
    // every mesh shading vertically, because `placement_ctm`'s `d` is positive. Must stay
    // consistent with the `fy` inverse below.
    let to_py = |y: f64| (bounds[3] - y) / bh * h as f64;
    let min_x = x0.min(x1).min(x2);
    let max_x = x0.max(x1).max(x2);
    let min_y = y0.min(y1).min(y2);
    let max_y = y0.max(y1).max(y2);
    let px0 = to_px(min_x).floor().max(0.0) as i32;
    let px1 = to_px(max_x).ceil().min(w as f64) as i32;
    // `to_py` now DECREASES with y, so the triangle's max_y gives the smaller row index.
    // Swapping these is not cosmetic: with them the wrong way round py0 > py1 and the
    // scanline loop below never executes, so every mesh shading would render empty.
    let py0 = to_py(max_y).floor().max(0.0) as i32;
    let py1 = to_py(min_y).ceil().min(h as f64) as i32;
    let denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
    if denom.abs() < 1e-12 {
        return;
    }
    for py in py0..py1 {
        for px in px0..px1 {
            let fx = bounds[0] + (px as f64 + 0.5) / w as f64 * bw;
            let fy = bounds[3] - (py as f64 + 0.5) / h as f64 * bh;
            let a = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / denom;
            let b = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / denom;
            let c = 1.0 - a - b;
            if a < -1e-6 || b < -1e-6 || c < -1e-6 {
                continue;
            }
            // `.round()`, not truncation: `c` is formed as `1 - a - b`, so a+b+c can be
            // 0.9999999999999999 and a flat white triangle summed to 254.99999999999997,
            // which `as u8` truncated to 254. The clamp covers the -1e-6 slack above.
            let r = ((((c0 >> 16) & 0xFF) as f64 * a + ((c1 >> 16) & 0xFF) as f64 * b + ((c2 >> 16) & 0xFF) as f64 * c).round()).clamp(0.0, 255.0) as u8;
            let g = ((((c0 >> 8) & 0xFF) as f64 * a + ((c1 >> 8) & 0xFF) as f64 * b + ((c2 >> 8) & 0xFF) as f64 * c).round()).clamp(0.0, 255.0) as u8;
            let bl = (((c0 & 0xFF) as f64 * a + (c1 & 0xFF) as f64 * b + (c2 & 0xFF) as f64 * c).round()).clamp(0.0, 255.0) as u8;
            let idx = (py as usize * w + px as usize) * 4;
            rgba[idx] = r;
            rgba[idx + 1] = g;
            rgba[idx + 2] = bl;
            rgba[idx + 3] = 255;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Collect the triangles a streaming parser emits, for tests that want to make
    /// assertions about the decoded mesh rather than the raster.
    fn collect(f: impl FnOnce(Emit)) -> Vec<(Vertex, Vertex, Vertex)> {
        let mut out = Vec::new();
        let mut sink = |a: &Vertex, b: &Vertex, c: &Vertex| {
            out.push((a.clone(), b.clone(), c.clone()));
        };
        f(&mut sink);
        out
    }

    /// Build a minimal Type 4 free-form Gouraud triangle: one triangle with
    /// three distinct RGB corner colors, packed at 8bpp coords/comps.
    fn type4_one_triangle() -> Vec<u8> {
        // Decode: x 0..1, y 0..1, r/g/b 0..1. 8 bits each. flag=8 bits.
        // Vertex layout: flag(1) x(1) y(1) r(1) g(1) b(1) = 6 bytes.
        let verts: [(u8, u8, u8, [u8; 3]); 3] = [
            (0, 0, 0, [255, 0, 0]),
            (0, 255, 0, [0, 255, 0]),
            (0, 0, 255, [0, 0, 255]),
        ];
        let mut out = Vec::new();
        for (flag, x, y, c) in verts {
            out.push(flag);
            out.push(x);
            out.push(y);
            out.extend_from_slice(&c);
        }
        out
    }

    #[test]
    fn type4_produces_varied_pixels() {
        let data = type4_one_triangle();
        let decode = [0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0];
        let tris = collect(|e| parse_type4(&data, 8, 8, 8, 3, &decode, e));
        assert_eq!(tris.len(), 1);
        let bounds = [0.0, 0.0, 1.0, 1.0];
        let (w, h) = (32usize, 32usize);
        let mut rgba = vec![0u8; w * h * 4];
        let (v0, v1, v2) = &tris[0];
        fill_tri(
            &mut rgba,
            w,
            h,
            &bounds,
            (v0, v1, v2),
            (0xFFFF0000, 0xFF00FF00, 0xFF0000FF),
        );
        // Expect a mix of colors, not a single flat value.
        let mut seen = std::collections::HashSet::new();
        for px in rgba.chunks(4) {
            if px[3] == 255 {
                seen.insert((px[0], px[1], px[2]));
            }
        }
        assert!(seen.len() > 3, "gouraud triangle should have varied colors");
    }

    #[test]
    fn bit_reader_reads_big_endian() {
        let mut br = BitReader::new(&[0b1010_0000, 0b1100_0000]);
        assert_eq!(br.read(3), Some(0b101));
        assert_eq!(br.read(5), Some(0b00000));
        assert_eq!(br.read(2), Some(0b11));
    }

    // ISO 32000-1 8.7.4.5.5: each Type 4 vertex occupies a whole number of bytes
    // and trailing padding bits are ignored. Here flag(8) + 2*coord(8) + 1*comp(4)
    // = 28 bits, so every vertex is padded to 32. Without the per-vertex align the
    // second and third vertices decode from the padding and yield garbage
    // coordinates, so this pins the alignment behaviour rather than just the count.
    #[test]
    fn type4_pads_each_vertex_to_a_byte_boundary() {
        // Per vertex: [flag, x, y, colour<<4 | padding].
        let data: Vec<u8> = vec![
            0, 0, 0, 0x00, // v0 (0,0)   colour 0/15
            0, 255, 0, 0xF0, // v1 (255,0) colour 15/15
            0, 0, 255, 0x80, // v2 (0,255) colour 8/15
        ];
        // x 0..255, y 0..255, colour 0..1 — coords map through as identity.
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        let tris = collect(|e| parse_type4(&data, 8, 8, 4, 1, &decode, e));
        assert_eq!(tris.len(), 1, "three flag-0 vertices form exactly one triangle");
        let (v0, v1, v2) = &tris[0];
        assert_eq!((v0.x, v0.y), (0.0, 0.0));
        assert_eq!((v1.x, v1.y), (255.0, 0.0));
        assert_eq!((v2.x, v2.y), (0.0, 255.0));
        assert!((v1.color[0] - 1.0).abs() < 1e-9, "v1 colour is the full 4-bit range");
    }

    // A Type 7 tensor patch whose interior control points are displaced must
    // produce a different surface than the equivalent Type 6 Coons patch (which
    // has no interior points). This guards against silently dropping p13..p16.
    fn tensor_patch_bytes(interior: [(u8, u8); 4]) -> Vec<u8> {
        // 12 boundary points forming a [0,100] square, then 4 interior points.
        let boundary: [(u8, u8); 12] = [
            (0, 0), (0, 33), (0, 66), (0, 100),
            (33, 100), (66, 100), (100, 100),
            (100, 66), (100, 33), (100, 0),
            (66, 0), (33, 0),
        ];
        let mut out = vec![0u8]; // flag = 0 (full patch), 8-bit
        for (x, y) in boundary { out.push(x); out.push(y); }
        for (x, y) in interior { out.push(x); out.push(y); }
        out.extend_from_slice(&[0, 0, 0, 0]); // 4 single-component colors
        out
    }

    #[test]
    fn tensor_interior_changes_surface() {
        // Coord decode maps 0..255 -> 0..255 (identity); one color component.
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        // Flat/interpolated interior vs interior pulled hard to the corners.
        let flat = tensor_patch_bytes([(33, 33), (33, 66), (66, 66), (66, 33)]);
        let bulged = tensor_patch_bytes([(0, 0), (0, 100), (100, 100), (100, 0)]);
        let a = collect(|e| parse_type6_7(&flat, 7, 8, 8, 8, 1, &decode, e));
        let b = collect(|e| parse_type6_7(&bulged, 7, 8, 8, 8, 1, &decode, e));
        assert!(!a.is_empty() && !b.is_empty(), "both patches should tessellate");
        let verts = |tris: &[(Vertex, Vertex, Vertex)]| -> Vec<(i64, i64)> {
            tris.iter().flat_map(|(p, q, r)| {
                [p, q, r].map(|v| ((v.x * 100.0) as i64, (v.y * 100.0) as i64))
            }).collect()
        };
        assert_ne!(verts(&a), verts(&b), "displaced interior must alter geometry");
    }

    // ISO 32000-1 8.7.4.5.5, Table 80: an edge flag of 1 continues the previous
    // triangle with (vb, vc, new) and a flag of 2 with (va, vc, new). Swapping them
    // (or reusing the wrong pair) still produces the right NUMBER of triangles, so a
    // count assertion cannot see it — the mesh just folds back on itself.
    #[test]
    fn type4_edge_flags_pick_the_right_previous_vertices() {
        // Per vertex: flag(8) x(8) y(8) colour(8). Distinct coords identify vertices.
        let data: Vec<u8> = vec![
            0, 10, 11, 0, // va
            0, 20, 21, 0, // vb
            0, 30, 31, 0, // vc  -> triangle 0 = (va, vb, vc)
            1, 40, 41, 0, // flag 1 -> (vb, vc, vd)
            2, 50, 51, 0, // flag 2 -> (vb, vd, ve)  [va of the PREVIOUS triangle is vb]
        ];
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        let tris = collect(|e| parse_type4(&data, 8, 8, 8, 1, &decode, e));
        assert_eq!(tris.len(), 3);
        let xy = |v: &Vertex| (v.x as i32, v.y as i32);
        assert_eq!(
            (xy(&tris[0].0), xy(&tris[0].1), xy(&tris[0].2)),
            ((10, 11), (20, 21), (30, 31))
        );
        // flag 1: drop the previous triangle's FIRST vertex.
        assert_eq!(
            (xy(&tris[1].0), xy(&tris[1].1), xy(&tris[1].2)),
            ((20, 21), (30, 31), (40, 41))
        );
        // flag 2: drop the previous triangle's SECOND vertex.
        assert_eq!(
            (xy(&tris[2].0), xy(&tris[2].1), xy(&tris[2].2)),
            ((20, 21), (40, 41), (50, 51))
        );
    }

    // ISO 32000-1 8.7.4.5.7 Table 85: a flag-1 continuation patch inherits the
    // previous patch's p4..p7 as its own p1..p4 AND its c2,c3 as c1,c2. Getting the
    // colour half wrong is invisible in the geometry but rotates the gradient inside
    // every continued patch.
    #[test]
    fn coons_continuation_inherits_edge_and_corner_colours() {
        // Patch 1: flag 0, 12 boundary points, 4 one-component colours.
        let boundary: [(u8, u8); 12] = [
            (0, 0), (0, 33), (0, 66), (0, 100),
            (33, 100), (66, 100), (100, 100),
            (100, 66), (100, 33), (100, 0),
            (66, 0), (33, 0),
        ];
        let mut data = vec![0u8];
        for (x, y) in boundary { data.push(x); data.push(y); }
        data.extend_from_slice(&[10, 20, 30, 40]); // c1..c4
        // Patch 2: flag 1, 8 new boundary points, 2 new colours (c3, c4).
        data.push(1);
        for i in 0..8u8 { data.push(200 + i); data.push(200 + i); }
        data.extend_from_slice(&[50, 60]);

        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 255.0];
        let tris = collect(|e| parse_type6_7(&data, 6, 8, 8, 8, 1, &decode, e));
        assert!(!tris.is_empty());

        // The second patch's tessellation starts after the first's (2*8*8 triangles).
        let per_patch = 2 * 8 * 8;
        assert_eq!(tris.len(), 2 * per_patch, "two patches tessellated");

        // grid[0][0] of a patch is its C00 corner = p1 with colour c1. For patch 2
        // that inherits patch 1's p4 = (0,100) and patch 1's c2 = 20.
        let corner = &tris[per_patch].0;
        assert_eq!((corner.x as i32, corner.y as i32), (0, 100), "p1 = previous p4");
        assert!((corner.color[0] - 20.0).abs() < 1e-6, "c1 = previous c2");
    }

    // §8.7.4.5.6: a lattice mesh is row-major with `/VerticesPerRow` vertices per row,
    // and each cell splits into two triangles. This pins the triangulation and the row
    // pairing directly, since `parse_type5` now streams a row at a time rather than
    // materialising the whole lattice.
    #[test]
    fn type5_lattice_pairs_adjacent_rows() {
        // Vertex = x(8) y(8) colour(8). Two rows of two.
        let data: Vec<u8> = vec![
            0, 0, 0, 10, 0, 0, // row 0: (0,0) (10,0)
            0, 10, 0, 10, 10, 0, // row 1: (0,10) (10,10)
        ];
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        let tris = collect(|e| parse_type5(&data, 2, 8, 8, 1, &decode, e));
        assert_eq!(tris.len(), 2, "one lattice cell = two triangles");
        let xy = |v: &Vertex| (v.x as i32, v.y as i32);
        assert_eq!(
            (xy(&tris[0].0), xy(&tris[0].1), xy(&tris[0].2)),
            ((0, 0), (10, 0), (0, 10))
        );
        assert_eq!(
            (xy(&tris[1].0), xy(&tris[1].1), xy(&tris[1].2)),
            ((0, 10), (10, 0), (10, 10))
        );

        // A trailing PARTIAL row contributes nothing rather than pairing with garbage.
        let mut ragged = data.clone();
        ragged.extend_from_slice(&[0, 20, 0]);
        assert_eq!(collect(|e| parse_type5(&ragged, 2, 8, 8, 1, &decode, e)).len(), 2);
        // And a single row alone yields no triangles at all.
        assert!(collect(|e| parse_type5(&data[..6], 2, 8, 8, 1, &decode, e)).is_empty());
    }

    // §8.7.4.5.7 (Table 85) / §8.7.4.5.8: "All of the data for a patch shall occupy a
    // whole number of bytes; if the total number of bits required is not divisible by
    // 8, the last data byte for each patch is padded at the end with extra bits, which
    // shall be ignored." The existing continuation test uses 8-bit everything, so every
    // record is accidentally aligned and it cannot see a missing `align()`. Here a Coons
    // patch is 8 + 24*8 + 4*1 = 204 bits, so each patch carries 4 padding bits; without
    // the align the second patch's flag is read out of the padding and the whole rest of
    // the stream decodes from the wrong bit offset — the "spectacular noise" failure.
    #[test]
    fn coons_patches_are_padded_to_a_byte_boundary() {
        let boundary: [(u8, u8); 12] = [
            (0, 0), (0, 33), (0, 66), (0, 100),
            (33, 100), (66, 100), (100, 100),
            (100, 66), (100, 33), (100, 0),
            (66, 0), (33, 0),
        ];
        let mut data = vec![0u8]; // patch 1: flag 0
        for (x, y) in boundary { data.push(x); data.push(y); }
        data.push(0b1010_0000); // c1..c4 = 1,0,1,0 as single bits, then 4 pad bits
        data.push(1u8); // patch 2: flag 1 — must start on a byte boundary
        for i in 0..8u8 { data.push(200 + i); data.push(200 + i); }
        data.push(0b0100_0000); // 2 new colours = 0,1, then 6 pad bits
        assert_eq!(data.len(), 44);

        // 1-bit components, so /Decode maps raw 0 -> 0.0 and raw 1 -> 1.0.
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        let tris = collect(|e| parse_type6_7(&data, 6, 8, 8, 1, 1, &decode, e));
        let per_patch = 2 * 8 * 8;
        assert_eq!(tris.len(), 2 * per_patch, "both patches decode");

        // Patch 2 inherits patch 1's p4 = (0,100) and patch 1's c2 = 0 (Table 85).
        let corner = &tris[per_patch].0;
        assert_eq!((corner.x as i32, corner.y as i32), (0, 100), "p1 = previous p4");
        assert!(corner.color[0].abs() < 1e-9, "c1 = previous c2 = 0");
        // Patch 1's own C00 carries c1 = 1, so the colours are not all collapsing to 0.
        assert!((tris[0].0.color[0] - 1.0).abs() < 1e-9);
    }

    // §8.6.6.3: a colour value in an Indexed space is a SINGLE index, not `base_ncomp`
    // components. `cs_kind_ncomp` reports the base arity (3 over DeviceRGB), and using
    // that as the per-vertex component count made every vertex consume 3x the colour
    // bits, so the packed stream desynchronised after the first vertex.
    #[test]
    fn an_indexed_mesh_reads_one_index_per_vertex() {
        let mut doc = Document::with_version("1.7");
        let palette = doc.add_object(Stream::new(
            dictionary! {},
            vec![0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255],
        ));
        let dict = dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => Object::Array(vec![
                Object::Name(b"Indexed".to_vec()),
                Object::Name(b"DeviceRGB".to_vec()),
                Object::Integer(3),
                Object::Reference(palette),
            ]),
            "BitsPerCoordinate" => 8,
            "BitsPerComponent" => 8,
            "BitsPerFlag" => 8,
            // x 0..255, y 0..255, index 0..3.
            "Decode" => vec![0.into(), 255.into(), 0.into(), 255.into(), 0.into(), 3.into()],
        };
        // flag, x, y, index-byte. 85/255 * 3 == 1 -> palette entry 1 == red.
        let data: Vec<u8> = vec![
            0, 0, 0, 85,
            0, 255, 0, 85,
            0, 0, 255, 85,
        ];
        let (_ctm, w, h, rgba) =
            rasterize_shading_mesh(&doc, &dict, Some(&data), &IDENTITY, &HashMap::new(), 32)
                .expect("four bytes per vertex is exactly three vertices = one triangle");
        assert!(w > 0 && h > 0);
        let red = rgba
            .chunks_exact(4)
            .filter(|px| px[3] == 255)
            .collect::<Vec<_>>();
        assert!(!red.is_empty(), "the triangle painted something");
        assert!(
            red.iter().all(|px| px[0] == 255 && px[1] == 0 && px[2] == 0),
            "every covered pixel is palette entry 1 (red), not the 0xFF808080 fallback"
        );
    }

    // A /Decode extent of +/-infinity makes `dmin + (raw/max)*(dmax-dmin)` NaN for
    // raw == 0. NaN defeats every `< -1e-6` barycentric rejection in `fill_tri` and
    // the colour cast saturates to 0, so the shading used to paint an OPAQUE BLACK
    // rectangle over its whole area and hide the page content beneath it.
    #[test]
    fn non_finite_geometry_paints_nothing_rather_than_a_black_rectangle() {
        let inf_decode = [f64::NEG_INFINITY, f64::INFINITY, 0.0, 255.0, 0.0, 1.0];
        let data: Vec<u8> = vec![
            0, 0, 0, 0,
            0, 255, 0, 255,
            0, 0, 255, 128,
        ];
        let tris = collect(|e| parse_type4(&data, 8, 8, 8, 1, &inf_decode, e));
        assert_eq!(tris.len(), 1);
        assert!(tris[0].0.x.is_nan(), "the NaN this guards against is reachable");

        let (w, h) = (16usize, 16usize);
        let mut rgba = vec![0u8; w * h * 4];
        let (v0, v1, v2) = &tris[0];
        fill_tri(&mut rgba, w, h, &[0.0, 0.0, 1.0, 1.0], (v0, v1, v2), (0xFF00_0000, 0xFF00_0000, 0xFF00_0000));
        assert!(
            rgba.chunks(4).all(|px| px[3] == 0),
            "a triangle with non-finite vertices must leave every pixel transparent"
        );
    }

    // A flat white triangle must paint 255, not 254. `c` is computed as `1 - a - b`,
    // so a+b+c is 0.9999999999999999 for many interior pixels, the weighted sum is
    // 254.99999999999997, and truncating `as u8` darkened every flat mesh by 1/255 —
    // and every gradient by up to the same, systematically towards zero.
    #[test]
    fn flat_white_triangle_is_255_not_254() {
        let (w, h) = (32usize, 32usize);
        let mut rgba = vec![0u8; w * h * 4];
        let v = |x: f64, y: f64| Vertex { x, y, color: vec![1.0, 1.0, 1.0] };
        let (v0, v1, v2) = (v(0.05, 0.05), v(0.95, 0.05), v(0.5, 0.95));
        let white = 0xFFFF_FFFF;
        fill_tri(&mut rgba, w, h, &[0.0, 0.0, 1.0, 1.0], (&v0, &v1, &v2), (white, white, white));
        let painted: Vec<&[u8]> = rgba.chunks(4).filter(|px| px[3] == 255).collect();
        assert!(painted.len() > 100, "expected a filled triangle, got {} pixels", painted.len());
        assert!(
            painted.iter().all(|px| px[0] == 255 && px[1] == 255 && px[2] == 255),
            "a triangle white at all three corners must be white everywhere inside; \
             darkest pixel was {:?}",
            painted.iter().min_by_key(|px| px[0]).unwrap()
        );
    }

    // The same rounding, on a colour that is not a channel extreme, so the clamp
    // cannot be what rescues it: a flat (100,150,200) triangle truncated to
    // (99,149,199) on every interior pixel whose barycentric sum fell short of 1.
    #[test]
    fn flat_midtone_triangle_keeps_its_exact_colour() {
        let (w, h) = (32usize, 32usize);
        let mut rgba = vec![0u8; w * h * 4];
        let v = |x: f64, y: f64| Vertex { x, y, color: vec![0.4, 0.6, 0.8] };
        let (v0, v1, v2) = (v(0.05, 0.05), v(0.95, 0.05), v(0.5, 0.95));
        let c = 0xFF64_96C8; // 100, 150, 200
        fill_tri(&mut rgba, w, h, &[0.0, 0.0, 1.0, 1.0], (&v0, &v1, &v2), (c, c, c));
        let painted: Vec<&[u8]> = rgba.chunks(4).filter(|px| px[3] == 255).collect();
        assert!(!painted.is_empty(), "expected a filled triangle");
        assert!(
            painted.iter().all(|px| px[0] == 100 && px[1] == 150 && px[2] == 200),
            "flat triangle must keep its colour; saw {:?}",
            painted.iter().find(|px| px[0] != 100 || px[1] != 150 || px[2] != 200)
        );
    }

    // `rasterize_shading_mesh` returning None (rather than an all-transparent raster)
    // for a mesh it cannot parse is load-bearing beyond this file: images.rs now
    // applies a /Background post-pass over every alpha-0 pixel of whatever raster it
    // gets back, so an empty raster would be flooded edge to edge with the background
    // colour and become the opaque rectangle over page content that the no-triangles
    // branch exists to prevent. Table 78 scopes /Background to the area outside the
    // shading's bounds, and an unparseable mesh has no known bounds.
    #[test]
    fn unparseable_mesh_returns_none_rather_than_an_empty_raster() {
        let doc = Document::with_version("1.7");
        let dict = dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => "DeviceRGB",
            "BitsPerCoordinate" => 8,
            "BitsPerComponent" => 8,
            "BitsPerFlag" => 8,
            "Decode" => vec![0.into(), 255.into(), 0.into(), 255.into(),
                             0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
        };
        // Two vertices' worth of bytes: enough to decode, never enough for a triangle.
        let truncated = vec![0u8; 12];
        assert!(
            rasterize_shading_mesh(&doc, &dict, Some(&truncated), &IDENTITY, &HashMap::new(), 64)
                .is_none(),
            "a mesh that yields no triangles must produce no raster at all"
        );
        // And a non-finite /Decode extent, which makes every vertex NaN.
        let inf_dict = dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => "DeviceRGB",
            "BitsPerCoordinate" => 8,
            "BitsPerComponent" => 8,
            "BitsPerFlag" => 8,
            "Decode" => vec![Object::Real(f32::NEG_INFINITY), Object::Real(f32::INFINITY),
                             0.into(), 255.into(),
                             0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
        };
        let three_verts: Vec<u8> = vec![
            0, 0, 0, 255, 0, 0,
            0, 255, 0, 0, 255, 0,
            0, 0, 255, 0, 0, 255,
        ];
        assert!(
            rasterize_shading_mesh(&doc, &inf_dict, Some(&three_verts), &IDENTITY, &HashMap::new(), 64)
                .is_none(),
            "an infinite /Decode extent must be refused, not rasterized as NaN geometry"
        );
    }
}
