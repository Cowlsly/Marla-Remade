use crate::*;

/// The matrix mapping an appearance stream's form space to page space, fitting
/// the (Matrix-transformed) `/BBox` into the annotation `/Rect` (PDF 12.5.5).
pub(crate) fn appearance_matrix(rect: [f64; 4], bbox: [f64; 4], matrix: Mat) -> Mat {
    let corners = [
        (bbox[0], bbox[1]),
        (bbox[2], bbox[1]),
        (bbox[2], bbox[3]),
        (bbox[0], bbox[3]),
    ];
    let mut tx0 = f64::INFINITY;
    let mut ty0 = f64::INFINITY;
    let mut tx1 = f64::NEG_INFINITY;
    let mut ty1 = f64::NEG_INFINITY;
    for (x, y) in corners {
        let (px, py) = transform(&matrix, x, y);
        tx0 = tx0.min(px);
        ty0 = ty0.min(py);
        tx1 = tx1.max(px);
        ty1 = ty1.max(py);
    }
    let rx0 = rect[0].min(rect[2]);
    let ry0 = rect[1].min(rect[3]);
    let rx1 = rect[0].max(rect[2]);
    let ry1 = rect[1].max(rect[3]);
    let bw = tx1 - tx0;
    let bh = ty1 - ty0;
    let sx = if bw.abs() > 1e-6 { (rx1 - rx0) / bw } else { 1.0 };
    let sy = if bh.abs() > 1e-6 { (ry1 - ry0) / bh } else { 1.0 };
    let fit = [sx, 0.0, 0.0, sy, rx0 - sx * tx0, ry0 - sy * ty0];
    mat_mul(&matrix, &fit)
}

/// Whether an annotation should be painted on screen. Shared by the renderer and
/// by `flatten_document`, which must not bake in anything invisible.
///
/// Per §12.5.3 Table 165 the `/F` flags are tested by VALUE: Hidden is bit
/// position 2 (value 2), NoView is bit position 6 (value 32). Also honors
/// optional content (§8.11.2) and skips `/Popup`, whose appearance is shown only
/// via its parent's open state (§12.5.6.14).
pub(crate) fn annot_visible_on_screen(doc: &Document, dict: &lopdf::Dictionary) -> bool {
    let flags = dict
        .get(b"F")
        .ok()
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
        .unwrap_or(0.0) as i64;
    if flags & 0b10 != 0 || flags & 0b10_0000 != 0 {
        return false;
    }
    if dict.get(b"OC").ok().map(|oc| crate::oc_object_hidden(doc, oc)).unwrap_or(false) {
        return false;
    }
    dict.get(b"Subtype")
        .ok()
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(|o| o.as_name().ok())
        != Some(b"Popup")
}

/// Render each visible page annotation's normal appearance (`/AP /N`) into
/// primitives, mapping the appearance BBox into the annotation Rect, then
/// through `base` (page rotation / origin) into displayed space.
pub(crate) fn render_annotations(doc: &Document, page_id: ObjectId, base: &Mat, prims: &mut Vec<Prim>) {
    let annots = match doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        Some(Object::Array(a)) => a.clone(),
        _ => return,
    };

    for a in &annots {
        let dict = match deref(doc, a).and_then(|o| o.as_dict().ok()) {
            Some(d) => d,
            None => continue,
        };
        if !annot_visible_on_screen(doc, dict) {
            continue;
        }
        render_annotation(doc, dict, base, prims);
    }
}

pub(crate) fn render_annotation(doc: &Document, dict: &lopdf::Dictionary, base: &Mat, prims: &mut Vec<Prim>) {
    let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
        Some(r) => r,
        None => return,
    };

    // Resolve the normal appearance: /AP /N is either a stream or a subdictionary
    // of appearance states selected by /AS. When absent, synthesize a basic
    // appearance for common markup/shape annotation types.
    let ap = match dict.get(b"AP").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => d,
        _ => {
            synthesize_annotation_appearance(doc, dict, rect, base, prims);
            return;
        }
    };
    // Fix P0 early return without fallback — if /AP present but N malformed, synthesize fallback
    let normal = match ap.get(b"N").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Stream(s)) => s,
        Some(Object::Dictionary(states)) => {
            let as_name = dict.get(b"AS").ok().and_then(|o| o.as_name().ok());
            let picked = match as_name {
                // /AS present: it selects the state. If it names a state that is
                // absent, fall back to "Off" (a button's default) and otherwise
                // draw nothing — NOT an arbitrary entry, which is
                // nondeterministic and would render an unchecked box as checked.
                Some(n) => states.get(n).ok().or_else(|| states.get(b"Off").ok()),
                // /AS absent is malformed (§12.5.5, Table 168 requires it when /N
                // is a subdictionary). Prefer "Off"; failing that, a single entry
                // is unambiguous, so use the real appearance rather than
                // synthesizing a crude one over the top of it.
                None => states.get(b"Off").ok().or_else(|| {
                    if states.len() == 1 {
                        states.iter().next().map(|(_, v)| v)
                    } else {
                        None
                    }
                }),
            };
            match picked.and_then(|o| deref(doc, o)) {
                Some(Object::Stream(s)) => s,
                _ => {
                    synthesize_annotation_appearance(doc, dict, rect, base, prims);
                    return;
                }
            }
        }
        _ => {
            synthesize_annotation_appearance(doc, dict, rect, base, prims);
            return;
        }
    };

    let bbox_raw = normal
        .dict
        .get(b"BBox")
        .ok()
        .and_then(|o| read_rect(doc, o));
    let bbox = bbox_raw.unwrap_or([0.0, 0.0, 1.0, 1.0]);
    let matrix = normal
        .dict
        .get(b"Matrix")
        .ok()
        .and_then(read_matrix_obj)
        .unwrap_or(IDENTITY);
    let res = normal
        .dict
        .get(b"Resources")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();

    let ops = crate::content::stream_operations(doc, normal);
    if ops.is_empty() {
        return;
    }

    let ctm = mat_mul(&appearance_matrix(rect, bbox, matrix), base);

    // §8.10.2: a form XObject's /BBox "shall be used to clip" its contents, and
    // §12.5.5 defines an appearance stream as a form XObject. Without this an
    // appearance paints outside its own Rect. Clip to the /Matrix-transformed
    // BBox QUAD rather than the axis-aligned box §12.5.5 derives for the /Rect
    // fit — the latter is too loose and lets a rotated appearance spill. The
    // same `ctm` is reused for the clip and the content, so the form's /Matrix
    // (already folded into it by appearance_matrix) cannot be applied twice.
    // A degenerate BBox is skipped: a collapsed quad would swallow the whole
    // annotation, turning a spill into a vanish.
    let clip = bbox_raw
        .map(normalize_rect)
        .filter(|b| b[2] - b[0] > 0.0 && b[3] - b[1] > 0.0);
    if let Some(b) = clip {
        let pts: Vec<(f32, f32)> = [(b[0], b[1]), (b[2], b[1]), (b[2], b[3]), (b[0], b[3])]
            .iter()
            .map(|&(x, y)| {
                let (dx, dy) = transform(&ctm, x, y);
                (dx as f32, dy as f32)
            })
            .collect();
        let mut path_ops = vec![PathOp::Move(pts[0].0, pts[0].1)];
        path_ops.extend(pts[1..].iter().map(|&(x, y)| PathOp::Line(x, y)));
        path_ops.push(PathOp::Close);
        prims.push(Prim::ClipPush { even_odd: false, pts, path_ops: Some(path_ops) });
    }

    let gs = GraphicsState {
        ctm,
        ..Default::default()
    };
    let start = prims.len();
    interpret_content(doc, &ops, res.as_ref(), gs, prims, 1, false);

    // Honor the annotation's constant opacity (/CA) over its rendered prims.
    let ca = dict.get(b"CA").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(num).unwrap_or(1.0);
    if ca < 1.0 {
        for p in prims[start..].iter_mut() {
            scale_prim_alpha(p, ca);
        }
    }
    // Unconditional pop (mirrors the `Do` arm): interpret_content always returns
    // with a balanced clip depth, so this keeps the canvas clip stack balanced
    // even if the content hit the primitive cap.
    if clip.is_some() {
        prims.push(Prim::ClipPop);
    }
}

/// Read an annotation color array (`/C`, `/IC`) as ARGB, or `None` when the
/// array is empty (meaning "no color" / transparent) or absent.
///
/// The array is dereferenced: §7.3.10 lets any object be indirect, and an
/// indirect `/C` previously read as "no colour", silently substituting the
/// default instead of the author's.
fn markup_color(doc: &Document, dict: &lopdf::Dictionary, key: &[u8]) -> Option<u32> {
    let arr = dict
        .get(key)
        .ok()
        .and_then(|o| deref(doc, o).or(Some(o)))?
        .as_array()
        .ok()?;
    let c: Vec<f64> = arr.iter().filter_map(num).collect();
    match c.len() {
        1 => Some(gray_to_argb(c[0])),
        3 => Some(rgb_to_argb(c[0], c[1], c[2])),
        4 => Some(cmyk_to_argb(c[0], c[1], c[2], c[3])),
        _ => None,
    }
}

/// Border width from `/BS /W` (preferred) or the legacy `/Border` array [_,_,W].
fn annot_border_width(doc: &Document, dict: &lopdf::Dictionary) -> f64 {
    if let Some(w) = dict.get(b"BS").ok().and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|bs| bs.get(b"W").ok()).and_then(num) {
        return w;
    }
    if let Some(Object::Array(b)) = dict.get(b"Border").ok().and_then(|o| deref(doc, o)) {
        if let Some(w) = b.get(2).and_then(num) { return w; }
    }
    1.0
}

/// Border dash array from ExGState `/D` or `/BS /D` or `/Border` dash [3rd?].
fn annot_border_dash(doc: &Document, dict: &lopdf::Dictionary) -> Vec<f64> {
    const MAX_D: usize = 32;
    if let Some(b) = dict.get(b"BS").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()) {
        if let Some(db) = b.get(b"D").ok().and_then(|o| deref(doc, o).or(Some(o))) {
            let (d, _phase) = parse_dash_extgstate(doc, db);
            if !d.is_empty() {
                return d.into_iter().take(MAX_D).collect();
            }
        }
    }
    if let Some(Object::Array(b)) = dict.get(b"Border").ok().and_then(|o| deref(doc, o)) {
        if b.len() >= 4 {
            if let Some(Object::Array(dash_arr)) = b.get(3) {
                return dash_arr.iter().filter_map(num).filter(|v| *v >= 0.0).take(MAX_D).collect();
            }
        }
    }
    Vec::new()
}

/// Synthesize a basic appearance for annotation types that lack an `/AP` stream.
///
/// Only shapes the file actually specifies are drawn: Square/Circle from `/Rect`,
/// Line from `/L` (with `/LE` endings), Ink from `/InkList`, Polygon/PolyLine
/// from `/Vertices`, the text markup types from `/QuadPoints`, Caret as an
/// insertion wedge and Stamp as its `/Name` wording in a box.
///
/// Everything else — Widget, Link, FileAttachment, Sound, Movie, Screen, and any
/// of the above missing its defining geometry — draws NOTHING. A crude wrong
/// shape is worse than an absent one: a bare `/Rect` outline is
/// indistinguishable from a Square annotation and asserts a geometry the file
/// never gave. In particular Link must never draw chrome of its own (§12.5.6.5
/// leaves the border to `/Border`, honoured only inside a real `/AP`).
pub(crate) fn synthesize_annotation_appearance(
    doc: &Document,
    dict: &lopdf::Dictionary,
    rect: [f64; 4],
    base: &Mat,
    prims: &mut Vec<Prim>,
) {
    let subtype = match dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok()) {
        Some(s) => s.to_vec(),
        None => return,
    };
    let ca = dict
        .get(b"CA")
        .ok()
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
        .unwrap_or(1.0)
        .clamp(0.0, 1.0);
    let stroke = markup_color(doc, dict, b"C");
    let fill = markup_color(doc, dict, b"IC");
    let bw = annot_border_width(doc, dict);
    let dev = |x: f64, y: f64| -> (f64, f64) { transform(base, x, y) };
    // Device half-width for strokes (approx via base scale).
    let scale = ((base[0]*base[0]+base[1]*base[1]).sqrt() + (base[2]*base[2]+base[3]*base[3]).sqrt()) / 2.0;

    let gs = GraphicsState {
        ctm: *base,
        line_width: bw.max(0.5),
        alpha_fill: ca,
        alpha_stroke: ca,
        ..Default::default()
    };

    // QuadPoints (text markup): 8 numbers per quad.
    let quads: Vec<[(f64,f64);4]> = dict.get(b"QuadPoints").ok()
        .and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| {
            let v: Vec<f64> = a.iter().filter_map(num).collect();
            v.chunks_exact(8).map(|q| [(q[0],q[1]),(q[2],q[3]),(q[4],q[5]),(q[6],q[7])]).collect()
        }).unwrap_or_default();

    match subtype.as_slice() {
        b"Square" => {
            let poly = vec![dev(rect[0],rect[1]), dev(rect[2],rect[1]), dev(rect[2],rect[3]), dev(rect[0],rect[3])];
            if let Some(f) = fill { emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal); }
            if let Some(s) = stroke {
                let mut ring = poly.clone(); ring.push(poly[0]);
                let mut sgs = gs.clone(); sgs.stroke = s;
                let d = annot_border_dash(doc, dict);
                if !d.is_empty() { sgs.dash = d; }
                emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
            }
        }
        b"Circle" => {
            // Approximate the inscribed ellipse with a polygon.
            let (cx, cy) = ((rect[0]+rect[2])/2.0, (rect[1]+rect[3])/2.0);
            let (rx, ry) = ((rect[2]-rect[0]).abs()/2.0, (rect[3]-rect[1]).abs()/2.0);
            let poly: Vec<(f64,f64)> = (0..48).map(|i| {
                let t = i as f64 / 48.0 * std::f64::consts::TAU;
                dev(cx + rx*t.cos(), cy + ry*t.sin())
            }).collect();
            if let Some(f) = fill { emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal); }
            if let Some(s) = stroke {
                let mut ring = poly.clone(); ring.push(poly[0]);
                let mut sgs = gs.clone(); sgs.stroke = s;
                let d = annot_border_dash(doc, dict);
                if !d.is_empty() { sgs.dash = d; }
                emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
            }
        }
        b"Line" => {
            let l_arr: Option<Vec<f64>> = dict.get(b"L").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok()).map(|a| a.iter().filter_map(num).collect());
            if let Some(n) = l_arr {
                if n.len() >= 4 {
                    let (p0, p1) = ((n[0], n[1]), (n[2], n[3]));
                    let seg = vec![dev(p0.0, p0.1), dev(p1.0, p1.1)];
                    let mut sgs = gs.clone(); sgs.stroke = stroke.unwrap_or(0xFF00_0000);
                    let d = annot_border_dash(doc, dict);
                    if !d.is_empty() { sgs.dash = d; }
                    emit_stroke(prims, std::slice::from_ref(&seg), &sgs);
                    // §12.5.6.7: /LE is [startStyle endStyle] and each ending
                    // points OUTWARD along the line, so the start ending's
                    // direction is p1 -> p0 and the end ending's is p0 -> p1.
                    let (dx, dy) = (p1.0 - p0.0, p1.1 - p0.1);
                    let len = (dx * dx + dy * dy).sqrt();
                    if len > 1e-6 {
                        let u = (dx / len, dy / len);
                        let le: Vec<Vec<u8>> = dict
                            .get(b"LE")
                            .ok()
                            .and_then(|o| deref(doc, o))
                            .and_then(|o| o.as_array().ok())
                            .map(|a| a.iter().filter_map(|o| o.as_name().ok().map(|n| n.to_vec())).collect())
                            .unwrap_or_default();
                        let le_size = (bw.max(1.0) * 4.0).clamp(4.0, 24.0);
                        if let Some(s) = le.first() {
                            emit_line_ending(prims, base, p0, (-u.0, -u.1), s, le_size, &sgs, fill, ca);
                        }
                        if let Some(s) = le.get(1) {
                            emit_line_ending(prims, base, p1, u, s, le_size, &sgs, fill, ca);
                        }
                    }
                }
            }
        }
        b"Ink" => {
            if let Some(Object::Array(paths)) = dict.get(b"InkList").ok().and_then(|o| deref(doc, o)) {
                let mut sgs = gs.clone(); sgs.stroke = stroke.unwrap_or(0xFF00_0000);
                let d = annot_border_dash(doc, dict);
                if !d.is_empty() { sgs.dash = d; }
                for p in paths {
                    let n: Vec<f64> = p.as_array().map(|a| a.iter().filter_map(num).collect()).unwrap_or_default();
                    let pts: Vec<(f64,f64)> = n.chunks_exact(2).map(|c| dev(c[0], c[1])).collect();
                    if pts.len() >= 2 { emit_stroke(prims, std::slice::from_ref(&pts), &sgs); }
                }
            }
        }
        b"Highlight" => {
            let color = stroke.unwrap_or(0xFFFF_FF00); // default yellow
            if quads.is_empty() {
                let poly = vec![dev(rect[0],rect[1]), dev(rect[2],rect[1]), dev(rect[2],rect[3]), dev(rect[0],rect[3])];
                emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(color, ca), false, 1.0, BlendMode::Multiply);
            } else {
                // §12.5.6.10 orders the vertices UL, UR, LL, LR — a "Z", not a
                // ring — so traversing them in file order self-intersects into a
                // bow-tie. Reorder to UL, UR, LR, LL. Kept as a quad rather than
                // its bbox so rotated/skewed text quads stay correct.
                //
                // Over the primitive cap the loop simply stops. The previous
                // fallback swapped to axis-aligned bbox rects instead, which both
                // emitted the same number of primitives it was trying to avoid
                // AND drew the wrong shape for rotated text.
                for q in &quads {
                    if prims.len() >= MAX_PRIMITIVES {
                        break;
                    }
                    let poly: Vec<(f64,f64)> = [q[0], q[1], q[3], q[2]].iter().map(|&(x,y)| dev(x,y)).collect();
                    emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(color, ca), false, 1.0, BlendMode::Multiply);
                }
            }
        }
        b"Underline" | b"StrikeOut" | b"Squiggly" => {
            let color = stroke.unwrap_or(0xFF00_0000);
            let mut sgs = gs.clone(); sgs.stroke = color;
            let d = annot_border_dash(doc, dict);
            if !d.is_empty() { sgs.dash = d; }
            // §12.5.6.10 quad order is UL, UR, LL, LR. Work along the quad's own
            // edges in PAGE space and map each point through `base`, instead of
            // taking the device-space bbox: the bbox is axis-aligned, so on a
            // rotated page (or over rotated text) it drew a horizontal rule
            // across the glyphs rather than a rule following the baseline.
            if subtype != b"Squiggly" {
                sgs.line_width = (bw.max(1.0)) / scale.max(1e-6); // ~1px device
            }
            // Fraction of the quad height at which the rule sits, measured from
            // the bottom edge towards the top.
            let t = if subtype == b"StrikeOut" { 0.5 } else { 0.10 };
            for q in &quads {
                if prims.len() >= MAX_PRIMITIVES {
                    break;
                }
                let (ul, ur, ll, lr) = (q[0], q[1], q[2], q[3]);
                // Baseline-parallel start/end, lifted off the bottom edge.
                let a = (ll.0 + (ul.0 - ll.0) * t, ll.1 + (ul.1 - ll.1) * t);
                let b = (lr.0 + (ur.0 - lr.0) * t, lr.1 + (ur.1 - lr.1) * t);
                if subtype == b"Squiggly" {
                    // Zig-zag between the rule line and a line 8% of the quad
                    // height above it, so the wave follows the text direction.
                    let up = ((ul.0 - ll.0) * 0.08, (ul.1 - ll.1) * 0.08);
                    let mut zig: Vec<(f64, f64)> = Vec::with_capacity(9);
                    for i in 0..=8 {
                        let f = i as f64 / 8.0;
                        let (x, y) = (a.0 + (b.0 - a.0) * f, a.1 + (b.1 - a.1) * f);
                        let (x, y) = if i % 2 == 0 { (x, y) } else { (x + up.0, y + up.1) };
                        zig.push(dev(x, y));
                    }
                    emit_stroke(prims, std::slice::from_ref(&zig), &sgs);
                } else {
                    let seg = vec![dev(a.0, a.1), dev(b.0, b.1)];
                    emit_stroke(prims, std::slice::from_ref(&seg), &sgs);
                }
            }
        }
        b"Polygon" | b"PolyLine" => {
            // §12.5.6.9: the shape IS /Vertices. Without it there is no shape, so
            // draw nothing rather than the /Rect outline the old code fell back
            // to — a rectangle is indistinguishable from a Square annotation and
            // claims a geometry the file never gave.
            if let Some(Object::Array(verts)) = dict.get(b"Vertices").ok().and_then(|o| deref(doc, o)) {
                let n: Vec<f64> = verts.iter().filter_map(num).collect();
                let pts: Vec<(f64,f64)> = n.chunks_exact(2).map(|c| dev(c[0], c[1])).collect();
                if pts.len() >= 2 {
                    let closed = subtype == b"Polygon";
                    // /IC is the interior colour and only a closed Polygon has an
                    // interior; on a PolyLine it colours the line endings, so it
                    // must not become the stroke colour (§12.5.6.9).
                    if closed {
                        if let Some(f) = fill {
                            emit_fill(prims, std::slice::from_ref(&pts), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal);
                        }
                    }
                    if let Some(s) = stroke {
                        let mut ring = pts.clone();
                        if closed { ring.push(pts[0]); }
                        let mut sgs = gs.clone(); sgs.stroke = s;
                        let d = annot_border_dash(doc, dict);
                        if !d.is_empty() { sgs.dash = d; }
                        emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
                    }
                }
            }
        }
        b"Caret" => {
            // §12.5.6.11: a caret marks a text insertion point. Synthesize the
            // upward wedge, which says "inserted here"; the old /Rect outline was
            // indistinguishable from a Square annotation.
            let r = normalize_rect(rect);
            if r[2] - r[0] > 0.0 && r[3] - r[1] > 0.0 {
                let tri = vec![dev(r[0], r[1]), dev((r[0] + r[2]) / 2.0, r[3]), dev(r[2], r[1])];
                let col = stroke.unwrap_or(0xFF00_0000);
                emit_fill(prims, std::slice::from_ref(&tri), apply_alpha_to_argb(col, ca), false, 1.0, BlendMode::Normal);
            }
        }
        b"Stamp" => {
            // §12.5.6.12: /Name selects a standard stamp whose artwork we do not
            // ship. Drawing the stamp's own wording inside its border conveys
            // what the stamp says. With no /Name there is nothing defensible to
            // draw, so draw nothing — the old /Rect outline read as a Square.
            let label = dict
                .get(b"Name")
                .ok()
                .and_then(|o| deref(doc, o).or(Some(o)))
                .and_then(|o| o.as_name().ok())
                .map(stamp_label)
                .unwrap_or_default();
            let r = normalize_rect(rect);
            let (rw, rh) = (r[2] - r[0], r[3] - r[1]);
            if !label.is_empty() && rw > 0.0 && rh > 0.0 {
                let col = stroke.unwrap_or(0xFFFF_0000); // Acrobat's stamps are red
                let poly = vec![dev(r[0], r[1]), dev(r[2], r[1]), dev(r[2], r[3]), dev(r[0], r[3])];
                let mut ring = poly.clone(); ring.push(poly[0]);
                let mut sgs = gs.clone(); sgs.stroke = col;
                emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
                // `emit_annot_text` advances 0.5em per character, so size the
                // text to fit the box on both axes.
                let n = label.chars().count().max(1) as f64;
                let size = (rh * 0.55).min(rw * 0.85 / (0.5 * n)).max(1.0);
                let (px, py) = dev(
                    r[0] + (rw - n * size * 0.5).max(0.0) / 2.0,
                    r[1] + (rh - size) / 2.0,
                );
                emit_annot_text(prims, px as f32, py as f32, (size * scale) as f32, apply_alpha_to_argb(col, ca), &label);
            }
        }
        b"FreeText" => {
            // Border/background box plus the /Contents text (no /AP fallback).
            let poly = vec![dev(rect[0],rect[1]), dev(rect[2],rect[1]), dev(rect[2],rect[3]), dev(rect[0],rect[3])];
            if let Some(f) = fill { emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal); }
            if let Some(s) = stroke {
                let mut ring = poly.clone(); ring.push(poly[0]);
                let mut sgs = gs.clone(); sgs.stroke = s;
                emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
            }
            let text = dict.get(b"Contents").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::String(b,_) => Some(decode_pdf_text(b)), _ => None }).unwrap_or_default();
            if !text.is_empty() {
                let size = 12.0_f64;
                let dsize = (size * scale) as f32;
                let mut y = rect[3] - size; // top-down in page space
                for line in text.split(['\n', '\r']).filter(|l| !l.is_empty()) {
                    if prims.len() >= MAX_PRIMITIVES || y < rect[1] { break; }
                    let (px, py) = dev(rect[0] + 2.0, y);
                    emit_annot_text(prims, px as f32, py as f32, dsize, 0xFF00_0000, line);
                    y -= size * 1.2;
                }
            }
        }
        b"Text" => {
            // Sticky-note icon: a small filled square marker at the annotation rect.
            let x0 = rect[0]; let y1 = rect[3];
            let s = 18.0_f64.min((rect[2]-rect[0]).abs().max(12.0));
            let poly = vec![dev(x0, y1 - s), dev(x0 + s, y1 - s), dev(x0 + s, y1), dev(x0, y1)];
            let col = stroke.or(fill).unwrap_or(0xFFFF_E000); // note yellow
            emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(col, ca), false, 1.0, BlendMode::Normal);
            let mut ring = poly.clone(); ring.push(poly[0]);
            let mut sgs = gs.clone(); sgs.stroke = 0xFF00_0000;
            emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
        }
        b"Redact" => {
            // §12.5.6.24: a redaction is not applied until apply_redactions runs,
            // so before that the annotation only MARKS the region. Outline it in
            // /C (default red) and fill only if /IC is present — a wash over the
            // region would obscure the very text the user needs to read.
            let poly = vec![dev(rect[0],rect[1]), dev(rect[2],rect[1]), dev(rect[2],rect[3]), dev(rect[0],rect[3])];
            if let Some(f) = fill {
                emit_fill(prims, std::slice::from_ref(&poly), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal);
            }
            let mut ring = poly.clone(); ring.push(poly[0]);
            let mut sgs = gs.clone();
            sgs.stroke = stroke.unwrap_or(0xFFFF_0000);
            let d = annot_border_dash(doc, dict);
            if !d.is_empty() { sgs.dash = d; }
            emit_stroke(prims, std::slice::from_ref(&ring), &sgs);
        }
        _ => {}
    }
}

/// Emit a single line of substitute-font text at a device-space baseline (used
/// for synthesized FreeText appearances).
fn emit_annot_text(prims: &mut Vec<Prim>, x: f32, y: f32, size: f32, argb: u32, text: &str) {
    prims.push(Prim::Text {
        x, y, size, argb,
        text: text.to_string(),
        stroke_argb: None,
        stroke_width: None,
        advance: size * 0.5,
        render_mode: 0,
        blend: BlendMode::Normal,
        is_bold: false,
        is_italic: false,
        font_family: 0,
        outline: false,
        h_scale: 1.0,
    });
}

/// Paint one `/LE` line ending (§12.5.6.7 Table 176) at page-space point `tip`,
/// where `dir` is the unit vector pointing along the line TOWARDS `tip` so that
/// arrowheads point outward. `size` is the ending's page-space extent and
/// `interior` is `/IC`, which §12.5.6.7 defines as the fill for line endings.
/// `/None` and any unrecognised name draw nothing.
#[allow(clippy::too_many_arguments)]
fn emit_line_ending(
    prims: &mut Vec<Prim>,
    base: &Mat,
    tip: (f64, f64),
    dir: (f64, f64),
    style: &[u8],
    size: f64,
    sgs: &GraphicsState,
    interior: Option<u32>,
    ca: f64,
) {
    let n = (-dir.1, dir.0);
    let mut closed: Option<Vec<(f64, f64)>> = None;
    let mut open: Vec<(f64, f64)> = Vec::new();
    match style {
        b"OpenArrow" | b"ROpenArrow" | b"ClosedArrow" | b"RClosedArrow" => {
            // Reversed forms point back along the line instead of outward.
            let s = if style == b"ROpenArrow" || style == b"RClosedArrow" { -size } else { size };
            let hw = size * 0.577; // 30-degree half-angle
            let a = (tip.0 - dir.0 * s + n.0 * hw, tip.1 - dir.1 * s + n.1 * hw);
            let b = (tip.0 - dir.0 * s - n.0 * hw, tip.1 - dir.1 * s - n.1 * hw);
            if style == b"OpenArrow" || style == b"ROpenArrow" {
                open = vec![a, tip, b];
            } else {
                closed = Some(vec![tip, a, b]);
            }
        }
        b"Square" => {
            let h = size * 0.5;
            closed = Some(vec![
                (tip.0 - dir.0 * h - n.0 * h, tip.1 - dir.1 * h - n.1 * h),
                (tip.0 + dir.0 * h - n.0 * h, tip.1 + dir.1 * h - n.1 * h),
                (tip.0 + dir.0 * h + n.0 * h, tip.1 + dir.1 * h + n.1 * h),
                (tip.0 - dir.0 * h + n.0 * h, tip.1 - dir.1 * h + n.1 * h),
            ]);
        }
        b"Diamond" => {
            let h = size * 0.6;
            closed = Some(vec![
                (tip.0 - dir.0 * h, tip.1 - dir.1 * h),
                (tip.0 - n.0 * h, tip.1 - n.1 * h),
                (tip.0 + dir.0 * h, tip.1 + dir.1 * h),
                (tip.0 + n.0 * h, tip.1 + n.1 * h),
            ]);
        }
        b"Circle" => {
            let r = size * 0.5;
            closed = Some(
                (0..24)
                    .map(|i| {
                        let t = i as f64 / 24.0 * std::f64::consts::TAU;
                        (tip.0 + r * t.cos(), tip.1 + r * t.sin())
                    })
                    .collect(),
            );
        }
        b"Butt" => {
            let h = size * 0.5;
            open = vec![
                (tip.0 - n.0 * h, tip.1 - n.1 * h),
                (tip.0 + n.0 * h, tip.1 + n.1 * h),
            ];
        }
        b"Slash" => {
            // A short line at 60 degrees counter-clockwise from the line itself.
            let (c, s) = (std::f64::consts::FRAC_PI_3.cos(), std::f64::consts::FRAC_PI_3.sin());
            let u = (dir.0 * c - dir.1 * s, dir.0 * s + dir.1 * c);
            let h = size * 0.5;
            open = vec![
                (tip.0 - u.0 * h, tip.1 - u.1 * h),
                (tip.0 + u.0 * h, tip.1 + u.1 * h),
            ];
        }
        _ => return,
    }
    // The line's dash pattern applies to the line, not to its endings.
    let mut g = sgs.clone();
    g.dash = Vec::new();
    if let Some(shape) = closed {
        let pts: Vec<(f64, f64)> = shape.iter().map(|&(x, y)| transform(base, x, y)).collect();
        if let Some(f) = interior {
            emit_fill(prims, std::slice::from_ref(&pts), apply_alpha_to_argb(f, ca), false, 1.0, BlendMode::Normal);
        }
        let mut ring = pts.clone();
        ring.push(pts[0]);
        emit_stroke(prims, std::slice::from_ref(&ring), &g);
    }
    if !open.is_empty() {
        let pts: Vec<(f64, f64)> = open.iter().map(|&(x, y)| transform(base, x, y)).collect();
        emit_stroke(prims, std::slice::from_ref(&pts), &g);
    }
}

/// Split a standard stamp `/Name` (§12.5.6.12 Table 181 names them in CamelCase,
/// e.g. `ForPublicRelease`) into the upper-case wording the stamp displays.
fn stamp_label(name: &[u8]) -> String {
    let raw = String::from_utf8_lossy(name);
    let mut out = String::with_capacity(raw.len() + 4);
    let mut prev_lower = false;
    for c in raw.chars() {
        if c.is_ascii_uppercase() && prev_lower {
            out.push(' ');
        }
        prev_lower = c.is_ascii_lowercase() || c.is_ascii_digit();
        out.push(c.to_ascii_uppercase());
    }
    out
}

// ---------------------------------------------------------------------------
// Editing: annotations, form filling, and save (lopdf write-back)
// ---------------------------------------------------------------------------
//
// The "safe" viewer edits via an overlay model written back through lopdf: new
// content is added as annotations (with generated appearance streams so other
// viewers render them) or as AcroForm field values. Existing body-text glyph
// runs are not editable in this architecture.

/// Encode a lopdf `ObjectId` (num, gen) into a single `i64` handle for Kotlin.
pub(crate) fn encode_id(id: ObjectId) -> i64 {
    ((id.0 as i64) << 16) | (id.1 as i64)
}

pub(crate) fn decode_id(v: i64) -> ObjectId {
    (((v >> 16) & 0xFFFF_FFFF) as u32, (v & 0xFFFF) as u16)
}

pub(crate) fn nth_page_id(doc: &Document, index: i32) -> Option<ObjectId> {
    doc.get_pages().get(&((index as u32) + 1)).copied()
}

pub(crate) fn name_obj(s: &str) -> Object {
    Object::Name(s.as_bytes().to_vec())
}

pub(crate) fn rect_obj(r: [f64; 4]) -> Object {
    Object::Array(vec![r[0].into(), r[1].into(), r[2].into(), r[3].into()])
}

pub(crate) fn argb_rgb(argb: u32) -> (f64, f64, f64) {
    (
        ((argb >> 16) & 0xFF) as f64 / 255.0,
        ((argb >> 8) & 0xFF) as f64 / 255.0,
        (argb & 0xFF) as f64 / 255.0,
    )
}

pub(crate) fn normalize_rect(r: [f64; 4]) -> [f64; 4] {
    [
        r[0].min(r[2]),
        r[1].min(r[3]),
        r[0].max(r[2]),
        r[1].max(r[3]),
    ]
}

/// Escape a string for PDF literal — full escapes per spec §7.3.4.2: \n \r \t \b \f ( ) \
/// Previous only escaped ( ) \ causing invalid streams for FreeText with newlines.
pub(crate) fn escape_pdf_literal(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '(' => out.push_str("\\("),
            ')' => out.push_str("\\)"),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{0008}' => out.push_str("\\b"),
            '\u{000C}' => out.push_str("\\f"),
            _ => out.push(c),
        }
    }
    out
}

/// Decode PDF text string: handles BE BOM FE FF and LE BOM FF FE, else Latin-1/PDFDoc approximation.
/// Fix: previously only BE, not LE.
pub(crate) fn decode_pdf_text(bytes: &[u8]) -> String {
    if bytes.len() >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF {
        let units: Vec<u16> = bytes[2..].chunks(2).map(|c| ((c[0] as u16) << 8) | *c.get(1).unwrap_or(&0) as u16).collect();
        String::from_utf16_lossy(&units)
    } else if bytes.len() >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE {
        let units: Vec<u16> = bytes[2..].chunks(2).map(|c| (c[0] as u16) | ((*c.get(1).unwrap_or(&0) as u16) << 8)).collect();
        String::from_utf16_lossy(&units)
    } else {
        // Not a UTF-16 string, so treat the bytes as Latin-1. PDFDocEncoding
        // (§7.9.2.2) differs from Latin-1 only in 0x18-0x1F and 0x80-0x9F, so
        // this is an approximation: a true PDFDocEncoding table would map those
        // ranges, and WinAnsiEncoding would map 0x80-0x9F differently again.
        bytes.iter().map(|&b| b as char).collect()
    }
}

/// Resources with Helvetica /F1 plus WinAnsiEncoding for non-ASCII, per P0 #7
pub(crate) fn helvetica_resources() -> Dictionary {
    let mut font = Dictionary::new();
    font.set("Type", name_obj("Font"));
    font.set("Subtype", name_obj("Type1"));
    font.set("BaseFont", name_obj("Helvetica"));
    font.set("Encoding", name_obj("WinAnsiEncoding"));
    let mut fonts = Dictionary::new();
    fonts.set("F1", Object::Dictionary(font));
    let mut res = Dictionary::new();
    res.set("Font", Object::Dictionary(fonts));
    res
}

/// Display-orientation size of a raw-page rect, plus the appearance `/Matrix`
/// that maps a form drawn in that orientation back into raw page space.
///
/// Text-bearing appearances (FreeText, callouts, underlines, field values) must
/// be laid out the way the reader sees them, but `/Rect` is in raw page space, so
/// on a rotated page the two orientations differ and content comes out sideways.
/// `appearance_matrix` (§12.5.5) already supplies translation and scale by
/// fitting the transformed BBox onto `/Rect`, so `/Matrix` only has to carry the
/// rotation — it is the inverse of the page base matrix's linear part.
///
/// Purely geometric appearances (Square/Circle/Ink/Polygon/Highlight) are defined
/// by their own coordinates and correctly rotate with the page, so they must NOT
/// use this. At `/Rotate 0` this is a strict no-op.
pub(crate) fn display_orientation(rotation: i64, w: f64, h: f64) -> (f64, f64, Mat) {
    match rotation {
        90 => (h, w, [0.0, 1.0, -1.0, 0.0, 0.0, 0.0]),
        180 => (w, h, [-1.0, 0.0, 0.0, -1.0, 0.0, 0.0]),
        270 => (h, w, [0.0, -1.0, 1.0, 0.0, 0.0, 0.0]),
        _ => (w, h, IDENTITY),
    }
}

/// `display_orientation` for the page at `page_index`.
pub(crate) fn page_display_orientation(doc: &Document, page_index: i32, w: f64, h: f64) -> (f64, f64, Mat) {
    let rot = nth_page_id(doc, page_index)
        .map(|pid| page_rotation(doc, pid))
        .unwrap_or(0);
    display_orientation(rot, w, h)
}

/// Build a Form XObject appearance stream with the given BBox size, content and
/// resources, returning its object id.
pub(crate) fn make_appearance(doc: &mut Document, w: f64, h: f64, content: Vec<u8>, res: Dictionary) -> ObjectId {
    make_appearance_oriented(doc, w, h, content, res, IDENTITY)
}

/// `make_appearance` plus a `/Matrix`, for appearances that must stay upright on
/// a rotated page (see `display_orientation`).
pub(crate) fn make_appearance_oriented(
    doc: &mut Document,
    w: f64,
    h: f64,
    content: Vec<u8>,
    res: Dictionary,
    matrix: Mat,
) -> ObjectId {
    let mut d = Dictionary::new();
    d.set("Type", name_obj("XObject"));
    d.set("Subtype", name_obj("Form"));
    d.set("FormType", 1);
    d.set(
        "BBox",
        Object::Array(vec![0.into(), 0.into(), w.into(), h.into()]),
    );
    if matrix != IDENTITY {
        d.set(
            "Matrix",
            Object::Array(matrix.iter().map(|v| Object::Real(*v as f32)).collect()),
        );
    }
    d.set("Resources", Object::Dictionary(res));
    doc.add_object(Stream::new(d, content))
}

/// Append an annotation reference to a page's `/Annots` array (creating it if
/// needed), handling both inline and indirect arrays.
pub(crate) fn append_annot(doc: &mut Document, page_id: ObjectId, annot_id: ObjectId) {
    let indirect = match doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Annots").ok()) {
        Some(Object::Reference(id)) => Some(*id),
        _ => None,
    };
    if let Some(arr_id) = indirect {
        if let Ok(Object::Array(a)) = doc.get_object_mut(arr_id) {
            a.push(Object::Reference(annot_id));
        }
        return;
    }
    if let Ok(page) = doc.get_dictionary_mut(page_id) {
        match page.get_mut(b"Annots") {
            Ok(Object::Array(a)) => a.push(Object::Reference(annot_id)),
            _ => page.set("Annots", Object::Array(vec![Object::Reference(annot_id)])),
        }
    }
}

/// Attach `Rect` + `AP /N` to an annotation dict.
pub(crate) fn set_appearance(annot: &mut Dictionary, rect: [f64; 4], ap_id: ObjectId) {
    annot.set("Rect", rect_obj(rect));
    let mut ap = Dictionary::new();
    ap.set("N", Object::Reference(ap_id));
    annot.set("AP", Object::Dictionary(ap));
}

pub(crate) fn add_annotation_object(doc: &mut Document, page_index: i32, annot: Dictionary) -> Option<i64> {
    let page_id = nth_page_id(doc, page_index)?;
    let annot_id = doc.add_object(annot);
    append_annot(doc, page_id, annot_id);
    Some(encode_id(annot_id))
}

/// Content stream drawing a (possibly multi-line) text block in a `w`×`h` box.
pub(crate) fn free_text_content(w: f64, h: f64, text: &str, argb: u32, size: f64) -> Vec<u8> {
    let (r, g, b) = argb_rgb(argb);
    let leading = size * 1.2;
    let mut c = format!(
        "q {r:.3} {g:.3} {b:.3} rg BT /F1 {size} Tf {leading} TL {x} {y} Td",
        x = 2.0,
        y = h - size,
    );
    let _ = w;
    for line in text.split('\n') {
        c.push_str(&format!(" ({}) Tj T*", escape_pdf_literal(line)));
    }
    c.push_str(" ET Q");
    c.into_bytes()
}

pub(crate) fn add_free_text(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    size: f64,
    text: &str,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    // Text must read upright regardless of /Rotate.
    let (w, h, apm) = page_display_orientation(doc, page_index, r[2] - r[0], r[3] - r[1]);
    let content = free_text_content(w, h, text, argb, size);
    let ap_id = make_appearance_oriented(doc, w, h, content, helvetica_resources(), apm);
    let (cr, cg, cb) = argb_rgb(argb);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("FreeText"));
    annot.set("Contents", Object::string_literal(text));
    annot.set(
        "DA",
        Object::string_literal(format!("{cr:.3} {cg:.3} {cb:.3} rg /F1 {size} Tf")),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

pub(crate) fn add_highlight(handle: i64, page_index: i32, rect: [f64; 4], argb: u32) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    // Multiply-blended translucent fill so underlying text shows through.
    let content = format!(
        "q /GS1 gs {cr:.3} {cg:.3} {cb:.3} rg 0 0 {w} {h} re f Q"
    )
    .into_bytes();
    let mut gs = Dictionary::new();
    gs.set("Type", name_obj("ExtGState"));
    gs.set("ca", Object::Real(0.4));
    gs.set("BM", name_obj("Multiply"));
    let mut gss = Dictionary::new();
    gss.set("GS1", Object::Dictionary(gs));
    let mut res = Dictionary::new();
    res.set("ExtGState", Object::Dictionary(gss));
    let ap_id = make_appearance(doc, w, h, content, res);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Highlight"));
    annot.set(
        "QuadPoints",
        Object::Array(vec![
            r[0].into(), r[3].into(), r[2].into(), r[3].into(),
            r[0].into(), r[1].into(), r[2].into(), r[1].into(),
        ]),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a text-markup annotation over `rect`. kind: 0 Underline, 1 StrikeOut, 2 Squiggly.
pub(crate) fn add_text_markup(handle: i64, page_index: i32, rect: [f64; 4], argb: u32, kind: i32) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    // The underline/strikeout position is relative to the text's reading
    // orientation, so it must be laid out in display orientation.
    let (w, h, apm) = page_display_orientation(doc, page_index, r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = (h * 0.06).clamp(0.8, 3.0);
    let content = match kind {
        1 => {
            let y = h / 2.0;
            format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {y:.2} m {w:.2} {y:.2} l S Q")
        }
        2 => {
            let base = h * 0.12;
            let amp = (h * 0.08).clamp(1.0, 4.0);
            let step = (amp * 2.0).max(3.0);
            let mut c = format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {base:.2} m ");
            let mut x = 0.0;
            let mut up = true;
            while x < w {
                let nx = (x + step).min(w);
                let y = if up { base + amp } else { base };
                c.push_str(&format!("{nx:.2} {y:.2} l "));
                x = nx;
                up = !up;
            }
            c.push_str("S Q");
            c
        }
        _ => {
            let y = h * 0.10;
            format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG 0 {y:.2} m {w:.2} {y:.2} l S Q")
        }
    }
    .into_bytes();
    let ap_id = make_appearance_oriented(doc, w, h, content, Dictionary::new(), apm);
    let subtype = match kind {
        1 => "StrikeOut",
        2 => "Squiggly",
        _ => "Underline",
    };
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj(subtype));
    annot.set(
        "QuadPoints",
        Object::Array(vec![
            r[0].into(), r[3].into(), r[2].into(), r[3].into(),
            r[0].into(), r[1].into(), r[2].into(), r[1].into(),
        ]),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a sticky-note (Text) annotation at editor point (x,y) with `text`.
pub(crate) fn add_note(handle: i64, page_index: i32, x: f64, y: f64, argb: u32, text: &str) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let binv = page_base_inverse(doc, page_index);
    let (px, py) = transform(&binv, x, y);
    let s = 20.0;
    let r = normalize_rect([px, py - s, px + s, py]);
    let (cr, cg, cb) = argb_rgb(argb);
    let content = format!(
        "q {cr:.3} {cg:.3} {cb:.3} rg 1 1 {w:.1} {h:.1} re f 1 1 1 rg 4 5 12 2 re f 4 9 12 2 re f 4 13 8 2 re f Q",
        w = s - 2.0,
        h = s - 2.0,
    )
    .into_bytes();
    // The icon's text bars read left-to-right, so like the other text-bearing
    // appearances it is laid out in display orientation and rotated back by
    // /Matrix; the rect is square, so only the content orientation changes.
    let rot = nth_page_id(doc, page_index)
        .map(|pid| page_rotation(doc, pid))
        .unwrap_or(0);
    let (_, _, apm) = display_orientation(rot, s, s);
    let ap_id = make_appearance_oriented(doc, s, s, content, Dictionary::new(), apm);
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Text"));
    annot.set("Name", name_obj("Note"));
    annot.set("Contents", Object::string_literal(text));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a FreeText callout: a leader line from anchor (ax,ay) to a text box near
/// (bx,by), all in editor coordinates.
///
/// The whole callout — leader, box and text — is laid out in DISPLAY space and
/// carried back into raw page space by the appearance `/Matrix`, the same
/// mechanism `add_free_text` uses (see `display_orientation`). Laying it out in
/// raw page space instead, as this did, put the text and the box sideways
/// relative to the visible content on a `/Rotate 90` or `270` page, and mirrored
/// the leader's knee on `180`.
pub(crate) fn add_callout(
    handle: i64,
    page_index: i32,
    ax: f64,
    ay: f64,
    bx: f64,
    by: f64,
    argb: u32,
    size: f64,
    text: &str,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let bw = 160.0;
    let bh = (size * 1.6).max(24.0);
    // Editor coordinates are already display space, so the box is built there.
    let (box_x0, box_y1) = (bx, by);
    let box_y0 = by - bh;
    let box_x1 = bx + bw;
    let minx = ax.min(box_x0);
    let miny = ay.min(box_y0);
    let maxx = ax.max(box_x1);
    let maxy = ay.max(box_y1);
    let (w, h) = (maxx - minx, maxy - miny);
    // /Rect is in raw page space; the display-space bounding box maps to it under
    // the page base matrix, which for every /Rotate is axis-aligned.
    let r = page_rect(doc, page_index, [minx, miny, maxx, maxy]);
    let rot = nth_page_id(doc, page_index)
        .map(|pid| page_rotation(doc, pid))
        .unwrap_or(0);
    let (_, _, apm) = display_orientation(rot, w, h);
    let (cr, cg, cb) = argb_rgb(argb);
    let lax = ax - minx;
    let lay = ay - miny;
    let lx0 = box_x0 - minx;
    let ly0 = box_y0 - miny;
    let lx1 = box_x1 - minx;
    let ly1 = box_y1 - miny;
    let knee_y = (ly0 + ly1) / 2.0;
    let mut c = format!(
        "q 1 w {cr:.3} {cg:.3} {cb:.3} RG {lax:.2} {lay:.2} m {lx0:.2} {knee_y:.2} l S "
    );
    c.push_str(&format!(
        "{lx0:.2} {ly0:.2} {bw2:.2} {bh2:.2} re S ",
        bw2 = lx1 - lx0,
        bh2 = ly1 - ly0,
    ));
    c.push_str(&format!(
        "{cr:.3} {cg:.3} {cb:.3} rg BT /F1 {size} Tf {tx:.2} {ty:.2} Td ({t}) Tj ET Q",
        tx = lx0 + 4.0,
        ty = ly1 - size - 2.0,
        t = escape_pdf_literal(text),
    ));
    let ap_id = make_appearance_oriented(doc, w, h, c.into_bytes(), helvetica_resources(), apm);
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("FreeText"));
    annot.set("IT", name_obj("FreeTextCallout"));
    annot.set("Contents", Object::string_literal(text));
    annot.set(
        "DA",
        Object::string_literal(format!("{cr:.3} {cg:.3} {cb:.3} rg /F1 {size} Tf")),
    );
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a redaction annotation: an opaque black filled rectangle marked so that
/// `apply_redactions` can permanently remove the content beneath it.
pub(crate) fn add_redaction(handle: i64, page_index: i32, rect: [f64; 4]) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let content = format!("q 0 0 0 rg 0 0 {w} {h} re f Q").into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());
    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Square"));
    annot.set("IC", Object::Array(vec![0.into(), 0.into(), 0.into()]));
    annot.set("PdfRedact", Object::Boolean(true));
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(0.0));
    annot.set("BS", Object::Dictionary(bs));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

pub(crate) fn add_square(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    line_width: f64,
    fill: bool,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);
    let content = if fill {
        format!("q {cr:.3} {cg:.3} {cb:.3} rg 0 0 {w} {h} re f Q")
    } else {
        format!(
            "q {lw} w {cr:.3} {cg:.3} {cb:.3} RG {x} {y} {rw} {rh} re S Q",
            x = lw / 2.0,
            y = lw / 2.0,
            rw = w - lw,
            rh = h - lw,
        )
    }
    .into_bytes();
    let ap_id = make_appearance(doc, w, h, content, Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Square"));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_shape_border(&mut annot, argb, lw, fill);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Add a Circle (ellipse) annotation inscribed in [rect], stroked or filled.
pub(crate) fn add_circle(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    argb: u32,
    line_width: f64,
    fill: bool,
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    let (w, h) = (r[2] - r[0], r[3] - r[1]);
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);

    // Ellipse inscribed in the BBox
    // approximated by four cubic Bézier arcs.
    let inset = if fill { 0.0 } else { lw / 2.0 };
    let cx = w / 2.0;
    let cy = h / 2.0;
    let rx = (w / 2.0 - inset).max(0.0);
    let ry = (h / 2.0 - inset).max(0.0);
    let k = 0.552_284_75_f64; // 4/3 * (sqrt(2) - 1)
    let ox = rx * k;
    let oy = ry * k;

    let mut c = String::from("q ");
    if fill {
        c.push_str(&format!("{cr:.3} {cg:.3} {cb:.3} rg "));
    } else {
        c.push_str(&format!("{lw} w {cr:.3} {cg:.3} {cb:.3} RG "));
    }
    c.push_str(&format!("{:.2} {:.2} m ", cx + rx, cy));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry,
    ));
    c.push_str(&format!(
        "{:.2} {:.2} {:.2} {:.2} {:.2} {:.2} c ",
        cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy,
    ));
    c.push_str(if fill { "f Q" } else { "S Q" });
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Circle"));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    set_shape_border(&mut annot, argb, lw, fill);
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// Set annotation constant opacity (`/CA`, `/ca`) from the alpha byte of `argb`.
pub(crate) fn set_alpha(annot: &mut Dictionary, argb: u32) {
    let a = ((argb >> 24) & 0xFF) as f64 / 255.0;
    if a < 1.0 {
        annot.set("CA", Object::Real(a as f32));
        annot.set("ca", Object::Real(a as f32));
    }
}

/// Set `/BS` (border) and, for filled shapes, `/IC` (interior color) on a
/// Square/Circle annotation. Filled shapes carry a zero-width border.
pub(crate) fn set_shape_border(annot: &mut Dictionary, argb: u32, line_width: f64, fill: bool) {
    let (cr, cg, cb) = argb_rgb(argb);
    let mut bs = Dictionary::new();
    if fill {
        annot.set("IC", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
        bs.set("W", Object::Real(0.0));
    } else {
        bs.set("W", Object::Real(line_width as f32));
    }
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(annot, argb);
}

/// Add a Polygon (when `closed`) or PolyLine (open) annotation from flat
/// page-space x,y `points`. Closed polygons may be filled; open polylines are
/// always stroked. Used for triangles, stars, arrows, lines, polylines and
/// flattened Bézier curves.
pub(crate) fn add_poly(
    handle: i64,
    page_index: i32,
    points: &[f32],
    argb: u32,
    line_width: f64,
    fill: bool,
    closed: bool,
) -> Option<i64> {
    if points.len() < 4 {
        return None;
    }
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let converted = page_points(doc, page_index, points);
    let points = converted.as_slice();
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);
    let do_fill = fill && closed;

    let mut minx = f64::INFINITY;
    let mut miny = f64::INFINITY;
    let mut maxx = f64::NEG_INFINITY;
    let mut maxy = f64::NEG_INFINITY;
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = (points[i] as f64, points[i + 1] as f64);
        minx = minx.min(x);
        miny = miny.min(y);
        maxx = maxx.max(x);
        maxy = maxy.max(y);
        i += 2;
    }
    let pad = lw + 2.0;
    let rect = [minx - pad, miny - pad, maxx + pad, maxy + pad];
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);

    let mut c = String::from("q ");
    if do_fill {
        c.push_str(&format!("{cr:.3} {cg:.3} {cb:.3} rg "));
    } else {
        c.push_str(&format!("{lw} w {cr:.3} {cg:.3} {cb:.3} RG "));
    }
    let mut verts = Vec::new();
    let mut j = 0;
    let mut first = true;
    while j + 1 < points.len() {
        let px = points[j] as f64;
        let py = points[j + 1] as f64;
        verts.push(px.into());
        verts.push(py.into());
        let (lx, ly) = (px - rect[0], py - rect[1]);
        if first {
            c.push_str(&format!("{lx:.2} {ly:.2} m "));
            first = false;
        } else {
            c.push_str(&format!("{lx:.2} {ly:.2} l "));
        }
        j += 2;
    }
    if closed {
        c.push_str(if do_fill { "h f Q" } else { "h S Q" });
    } else {
        c.push_str("S Q");
    }
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj(if closed { "Polygon" } else { "PolyLine" }));
    annot.set("Vertices", Object::Array(verts));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    if do_fill {
        annot.set("IC", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    }
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(if do_fill { 0.0 } else { lw as f32 }));
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, normalize_rect(rect), ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// `points`: flat page-space x,y pairs of a single ink stroke.
pub(crate) fn add_ink(
    handle: i64,
    page_index: i32,
    argb: u32,
    line_width: f64,
    points: &[f32],
) -> Option<i64> {
    if points.len() < 4 {
        return None;
    }
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let converted = page_points(doc, page_index, points);
    let points = converted.as_slice();
    let (cr, cg, cb) = argb_rgb(argb);
    let lw = line_width.max(0.5);

    let mut minx = f64::INFINITY;
    let mut miny = f64::INFINITY;
    let mut maxx = f64::NEG_INFINITY;
    let mut maxy = f64::NEG_INFINITY;
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = (points[i] as f64, points[i + 1] as f64);
        minx = minx.min(x);
        miny = miny.min(y);
        maxx = maxx.max(x);
        maxy = maxy.max(y);
        i += 2;
    }
    let pad = lw + 2.0;
    let rect = [minx - pad, miny - pad, maxx + pad, maxy + pad];
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);

    // Appearance content in BBox space (origin at rect min).
    let mut c = format!("q {lw} w {cr:.3} {cg:.3} {cb:.3} RG ");
    let mut ink = Vec::new();
    let mut j = 0;
    let mut first = true;
    while j + 1 < points.len() {
        let px = points[j] as f64;
        let py = points[j + 1] as f64;
        ink.push(px.into());
        ink.push(py.into());
        let (lx, ly) = (px - rect[0], py - rect[1]);
        if first {
            c.push_str(&format!("{lx:.2} {ly:.2} m "));
            first = false;
        } else {
            c.push_str(&format!("{lx:.2} {ly:.2} l "));
        }
        j += 2;
    }
    c.push_str("S Q");
    let ap_id = make_appearance(doc, w, h, c.into_bytes(), Dictionary::new());

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Ink"));
    annot.set("InkList", Object::Array(vec![Object::Array(ink)]));
    annot.set("C", Object::Array(vec![cr.into(), cg.into(), cb.into()]));
    let mut bs = Dictionary::new();
    bs.set("W", Object::Real(lw as f32));
    annot.set("BS", Object::Dictionary(bs));
    set_alpha(&mut annot, argb);
    set_appearance(&mut annot, rect, ap_id);
    add_annotation_object(doc, page_index, annot)
}

/// `jpeg`: raw JPEG bytes for a Stamp annotation image.
pub(crate) fn add_stamp(
    handle: i64,
    page_index: i32,
    rect: [f64; 4],
    img_w: u32,
    img_h: u32,
    jpeg: &[u8],
) -> Option<i64> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let r = page_rect(doc, page_index, rect);
    // A stamp image must appear upright to the reader, not rotated with the page.
    let (w, h, apm) = page_display_orientation(doc, page_index, r[2] - r[0], r[3] - r[1]);

    let mut img_dict = Dictionary::new();
    img_dict.set("Type", name_obj("XObject"));
    img_dict.set("Subtype", name_obj("Image"));
    img_dict.set("Width", Object::Integer(img_w as i64));
    img_dict.set("Height", Object::Integer(img_h as i64));
    img_dict.set("BitsPerComponent", Object::Integer(8));
    img_dict.set("ColorSpace", name_obj("DeviceRGB"));
    img_dict.set("Filter", name_obj("DCTDecode"));
    let img_id = doc.add_object(Stream::new(img_dict, jpeg.to_vec()));

    let mut xobj = Dictionary::new();
    xobj.set("Im0", Object::Reference(img_id));
    let mut res = Dictionary::new();
    res.set("XObject", Object::Dictionary(xobj));
    let content = format!("q {w} 0 0 {h} 0 0 cm /Im0 Do Q").into_bytes();
    let ap_id = make_appearance_oriented(doc, w, h, content, res, apm);

    let mut annot = Dictionary::new();
    annot.set("Type", name_obj("Annot"));
    annot.set("Subtype", name_obj("Stamp"));
    set_appearance(&mut annot, r, ap_id);
    add_annotation_object(doc, page_index, annot)
}

pub(crate) fn update_annotation_rect(handle: i64, page_index: i32, annot_id: i64, rect: [f64; 4]) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let pr = page_rect(doc, page_index, rect);
    let id = decode_id(annot_id);
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("Rect", rect_obj(pr));
        true
    } else {
        false
    }
}

pub(crate) fn update_free_text(handle: i64, annot_id: i64, text: &str) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    // Read existing rect / color / size.
    let (rect, argb, size) = {
        let dict = match doc.get_dictionary(id) {
            Ok(d) => d,
            Err(_) => return false,
        };
        let rect = dict
            .get(b"Rect")
            .ok()
            .and_then(|o| read_rect(doc, o))
            .map(normalize_rect)
            .unwrap_or([0.0, 0.0, 100.0, 20.0]);
        let argb = dict
            .get(b"C")
            .ok()
            .and_then(|o| o.as_array().ok())
            .filter(|a| a.len() == 3)
            .map(|a| {
                let r = a[0].as_float().unwrap_or(0.0);
                let g = a[1].as_float().unwrap_or(0.0);
                let b = a[2].as_float().unwrap_or(0.0);
                rgb_to_argb(r as f64, g as f64, b as f64)
            })
            .unwrap_or(0xFF00_0000);
        let size = dict
            .get(b"DA")
            .ok()
            .and_then(|o| o.as_str().ok())
            .and_then(parse_da_size)
            .unwrap_or(12.0);
        (rect, argb, size)
    };
    let (w, h) = (rect[2] - rect[0], rect[3] - rect[1]);
    let content = free_text_content(w, h, text, argb, size);
    let ap_id = make_appearance(doc, w, h, content, helvetica_resources());
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("Contents", Object::string_literal(text));
        let mut ap = Dictionary::new();
        ap.set("N", Object::Reference(ap_id));
        dict.set("AP", Object::Dictionary(ap));
        true
    } else {
        false
    }
}

/// Extract the font size preceding `Tf` in a `/DA` string.
pub(crate) fn parse_da_size(da: &[u8]) -> Option<f64> {
    let s = String::from_utf8_lossy(da);
    let toks: Vec<&str> = s.split_whitespace().collect();
    let tf = toks.iter().position(|t| *t == "Tf")?;
    if tf == 0 {
        return None;
    }
    toks[tf - 1].parse::<f64>().ok()
}

/// Remove an annotation reference from a page's `/Annots` (inline or indirect).
/// Returns whether a reference was actually removed. Does NOT delete the object.
pub(crate) fn remove_annot_ref(doc: &mut Document, page_id: ObjectId, id: ObjectId) -> bool {
    let indirect = match doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Annots").ok()) {
        Some(Object::Reference(aid)) => Some(*aid),
        _ => None,
    };
    if let Some(arr_id) = indirect {
        if let Ok(Object::Array(a)) = doc.get_object_mut(arr_id) {
            let before = a.len();
            a.retain(|o| o.as_reference().ok() != Some(id));
            return before != a.len();
        }
        return false;
    }
    if let Ok(page) = doc.get_dictionary_mut(page_id) {
        if let Ok(Object::Array(a)) = page.get_mut(b"Annots") {
            let before = a.len();
            a.retain(|o| o.as_reference().ok() != Some(id));
            return before != a.len();
        }
    }
    false
}

pub(crate) fn delete_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    let removed = remove_annot_ref(doc, page_id, id);
    doc.objects.remove(&id);
    removed
}

/// Detach an annotation (remove its page reference) but keep the object, so it
/// can be re-attached for undo/redo.
pub(crate) fn detach_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    remove_annot_ref(doc, page_id, id)
}

/// Re-attach a previously detached annotation to its page.
pub(crate) fn reattach_annotation(handle: i64, page_index: i32, annot_id: i64) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(annot_id);
    if !doc.objects.contains_key(&id) {
        return false;
    }
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return false,
    };
    append_annot(doc, page_id, id);
    true
}

/// Offset alternating x,y numbers of a flat array in place by (dx, dy).
pub(crate) fn offset_flat(arr: &mut [Object], dx: f64, dy: f64) {
    for (i, o) in arr.iter_mut().enumerate() {
        if let Some(n) = num(o) {
            let d = if i % 2 == 0 { dx } else { dy };
            *o = Object::Real((n + d) as f32);
        }
    }
}

/// Duplicate an annotation, shifting its geometry by (dx, dy) page-space units.
/// The copy shares the (immutable) appearance stream. Returns the new id, or 0.
pub(crate) fn duplicate_annotation(handle: i64, page_index: i32, annot_id: i64, dx: f64, dy: f64) -> i64 {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return 0,
    };
    let id = decode_id(annot_id);
    let mut dict = match doc.get_dictionary(id) {
        Ok(d) => d.clone(),
        Err(_) => return 0,
    };
    for key in [b"Rect".as_ref(), b"Vertices", b"QuadPoints", b"L"] {
        if let Ok(Object::Array(a)) = dict.get(key) {
            let mut a2 = a.clone();
            offset_flat(&mut a2, dx, dy);
            dict.set(key.to_vec(), Object::Array(a2));
        }
    }
    if let Ok(Object::Array(lists)) = dict.get(b"InkList") {
        let mut out = Vec::with_capacity(lists.len());
        for l in lists {
            if let Object::Array(pts) = l {
                let mut p2 = pts.clone();
                offset_flat(&mut p2, dx, dy);
                out.push(Object::Array(p2));
            } else {
                out.push(l.clone());
            }
        }
        dict.set("InkList", Object::Array(out));
    }
    let new_id = doc.add_object(dict);
    let page_id = match nth_page_id(doc, page_index) {
        Some(p) => p,
        None => return 0,
    };
    append_annot(doc, page_id, new_id);
    encode_id(new_id)
}

// --- Serialized listing for the UI ---------------------------------------

pub(crate) fn subtype_code(subtype: &[u8]) -> u8 {
    match subtype {
        b"FreeText" => 1,
        b"Highlight" => 2,
        b"Square" => 3,
        b"Ink" => 4,
        b"Stamp" => 5,
        b"Widget" => 6,
        b"Text" => 7,
        b"Line" => 8,
        b"Circle" => 9,
        b"Polygon" => 10,
        b"PolyLine" => 11,
        b"Underline" => 12,
        b"StrikeOut" => 13,
        b"Squiggly" => 14,
        b"Link" => 15,
        b"Popup" => 16,
        b"FileAttachment" => 17,
        b"Sound" => 18,
        b"Movie" => 19,
        b"Screen" => 20,
        b"Caret" => 21,
        b"Redact" => 22,
        b"Watermark" => 23,
        b"PrinterMark" => 24,
        b"TrapNet" => 25,
        b"3D" => 26,
        _ => 0,
    }
}

pub(crate) fn annot_color(doc: &Document, dict: &Dictionary) -> u32 {
    dict.get(b"C")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .filter(|a| a.len() == 3)
        .map(|a| {
            rgb_to_argb(
                a[0].as_float().unwrap_or(0.0) as f64,
                a[1].as_float().unwrap_or(0.0) as f64,
                a[2].as_float().unwrap_or(0.0) as f64,
            )
        })
        .unwrap_or(0xFF00_0000)
}

pub(crate) fn list_annotations(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);

    let mut records: Vec<(i64, u8, [f64; 4], u32, String)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            let id = match a.as_reference() {
                Ok(id) => id,
                Err(_) => continue,
            };
            let dict = match doc.get_dictionary(id) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let subtype = dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok());
            let code = subtype.map(subtype_code).unwrap_or(0);
            // Report rects in displayed space so the editor's hit-testing and
            // selection boxes line up with the (rotation-baked) render.
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (dx0, dy0) = transform(&base, n[0], n[1]);
                    let (dx1, dy1) = transform(&base, n[2], n[3]);
                    normalize_rect([dx0, dy0, dx1, dy1])
                }
                None => continue,
            };
            let color = annot_color(doc, dict);
            let contents = dict
                .get(b"Contents")
                .ok()
                .and_then(|o| o.as_str().ok())
                .map(decode_pdf_text)
                .unwrap_or_default();
            records.push((encode_id(id), code, rect, color, contents));
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for (id, code, rect, color, contents) in records {
        buf.extend_from_slice(&id.to_le_bytes());
        buf.push(code);
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        buf.extend_from_slice(&color.to_le_bytes());
        let b = contents.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

#[cfg(test)]
mod synthesis_tests {
    use super::stamp_label;
    use crate::*;

    fn annot(subtype: &str) -> Dictionary {
        let mut d = Dictionary::new();
        d.set("Type", name_obj("Annot"));
        d.set("Subtype", name_obj(subtype));
        d
    }

    /// Synthesize with an identity base matrix so device space == page space.
    fn synth(dict: &Dictionary, rect: [f64; 4]) -> Vec<Prim> {
        let doc = Document::with_version("1.7");
        let mut prims = Vec::new();
        synthesize_annotation_appearance(&doc, dict, rect, &IDENTITY, &mut prims);
        prims
    }

    /// `Prim` has no `Debug`, so failures report the primitive kinds instead.
    fn kinds(prims: &[Prim]) -> Vec<&'static str> {
        prims
            .iter()
            .map(|p| match p {
                Prim::Text { .. } => "Text",
                Prim::Fill { .. } => "Fill",
                Prim::Stroke { .. } => "Stroke",
                _ => "other",
            })
            .collect()
    }

    fn only_stroke(prims: &[Prim]) -> Vec<(f32, f32)> {
        let mut found = None;
        for p in prims {
            if let Prim::Stroke { pts, .. } = p {
                assert!(found.is_none(), "expected exactly one stroke, got {:?}", kinds(prims));
                found = Some(pts.clone());
            }
        }
        found.expect("no stroke emitted")
    }

    /// §12.5.6.10 quad order is UL, UR, LL, LR. The rule must be built from the
    /// quad's own edges: taking the DEVICE-SPACE BBOX instead collapses a rotated
    /// quad to an axis-aligned box, so an underline under vertical text was drawn
    /// as a short horizontal stroke across the glyphs instead of a long vertical
    /// one alongside them. Same class of bug as the round-1 Highlight bow-tie.
    #[test]
    fn text_markup_rules_follow_the_quad_not_its_bbox() {
        // A quad whose reading direction (UL -> UR) runs along +Y: vertical text.
        let quad = [0.0, 0.0, 0.0, 100.0, 20.0, 0.0, 20.0, 100.0];
        for subtype in ["Underline", "StrikeOut"] {
            let mut d = annot(subtype);
            d.set("QuadPoints", Object::Array(quad.iter().map(|v| (*v).into()).collect()));
            let pts = only_stroke(&synth(&d, [0.0, 0.0, 20.0, 100.0]));
            assert_eq!(pts.len(), 2, "{subtype}");
            let (dx, dy) = ((pts[1].0 - pts[0].0).abs(), (pts[1].1 - pts[0].1).abs());
            assert!(dx < 0.01, "{subtype}: rule is not parallel to the text (dx={dx})");
            assert!((dy - 100.0).abs() < 0.01, "{subtype}: rule spans {dy}, not the quad's 100");
        }
        // StrikeOut bisects the quad; Underline sits near the bottom edge, which
        // for this quad is the x=20 side.
        let mut d = annot("StrikeOut");
        d.set("QuadPoints", Object::Array(quad.iter().map(|v| (*v).into()).collect()));
        assert!((only_stroke(&synth(&d, [0.0, 0.0, 20.0, 100.0]))[0].0 - 10.0).abs() < 0.01);
        let mut d = annot("Underline");
        d.set("QuadPoints", Object::Array(quad.iter().map(|v| (*v).into()).collect()));
        assert!((only_stroke(&synth(&d, [0.0, 0.0, 20.0, 100.0]))[0].0 - 18.0).abs() < 0.01);
    }

    /// A crude wrong shape is worse than an absent one: these subtypes used to
    /// fall back to stroking the `/Rect`, which is indistinguishable from a Square
    /// annotation and asserts a geometry the file never supplied.
    #[test]
    fn subtypes_without_their_defining_geometry_draw_nothing() {
        let rect = [0.0, 0.0, 60.0, 40.0];
        for subtype in ["Polygon", "PolyLine", "FileAttachment", "Sound", "Movie", "Screen", "Link", "Widget"] {
            let mut d = annot(subtype);
            d.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
            let prims = synth(&d, rect);
            assert!(prims.is_empty(), "{subtype} drew {:?}", kinds(&prims));
        }
        // A Stamp with no /Name has no wording to show, so it draws nothing too.
        assert!(synth(&annot("Stamp"), rect).is_empty());
    }

    /// A Caret is an insertion mark (§12.5.6.11) and a Stamp says something
    /// (§12.5.6.12) — both get a synthesis that carries their meaning.
    #[test]
    fn caret_and_named_stamp_synthesize_something_meaningful() {
        let rect = [0.0, 0.0, 60.0, 40.0];
        let caret = synth(&annot("Caret"), rect);
        assert!(
            matches!(caret.as_slice(), [Prim::Fill { contours, .. }] if contours[0].len() == 3),
            "caret should be a filled triangle, got {:?}",
            kinds(&caret)
        );

        let mut d = annot("Stamp");
        d.set("Name", name_obj("ForPublicRelease"));
        let prims = synth(&d, rect);
        let text: Vec<&String> = prims
            .iter()
            .filter_map(|p| if let Prim::Text { text, .. } = p { Some(text) } else { None })
            .collect();
        assert_eq!(text, vec!["FOR PUBLIC RELEASE"], "got {:?}", kinds(&prims));
    }

    /// §12.5.5 fits the /Matrix-transformed /BBox onto /Rect. The rotated-page
    /// appearances (`add_free_text`, `add_callout`, `add_note`, `add_stamp`,
    /// generated field appearances) rely on that fit coming out as a pure
    /// translation: they author a `dw`x`dh` box in DISPLAY orientation and let
    /// `/Matrix` rotate it back, so if the transformed BBox did not match the raw
    /// `/Rect` the content would be squashed instead of rotated.
    #[test]
    fn oriented_appearance_fits_its_rect_without_scaling() {
        let (dw, dh) = (160.0_f64, 40.0_f64);
        for rot in [0i64, 90, 180, 270] {
            let (_, _, apm) = display_orientation(rot, dw, dh);
            // The raw /Rect a caller stores: display dims swap for quarter turns.
            let rect = match rot {
                90 | 270 => [10.0, 20.0, 10.0 + dh, 20.0 + dw],
                _ => [10.0, 20.0, 10.0 + dw, 20.0 + dh],
            };
            let m = appearance_matrix(rect, [0.0, 0.0, dw, dh], apm);
            let sx = (m[0] * m[0] + m[1] * m[1]).sqrt();
            let sy = (m[2] * m[2] + m[3] * m[3]).sqrt();
            assert!(
                (sx - 1.0).abs() < 1e-9 && (sy - 1.0).abs() < 1e-9,
                "rot={rot}: appearance scaled by ({sx},{sy}) instead of only rotated"
            );
            for (x, y) in [(0.0, 0.0), (dw, 0.0), (dw, dh), (0.0, dh)] {
                let (px, py) = transform(&m, x, y);
                assert!(
                    px >= rect[0] - 1e-6 && px <= rect[2] + 1e-6
                        && py >= rect[1] - 1e-6 && py <= rect[3] + 1e-6,
                    "rot={rot}: BBox corner ({px},{py}) fell outside {rect:?}"
                );
            }
        }
        // /Rotate 0 must be a strict no-op.
        assert_eq!(display_orientation(0, dw, dh), (dw, dh, IDENTITY));
    }

    #[test]
    fn stamp_names_split_into_words() {
        assert_eq!(stamp_label(b"Approved"), "APPROVED");
        assert_eq!(stamp_label(b"NotForPublicRelease"), "NOT FOR PUBLIC RELEASE");
        assert_eq!(stamp_label(b"TopSecret"), "TOP SECRET");
        assert_eq!(stamp_label(b""), "");
    }

    /// §12.5.6.7: `/LE` line endings. `/None` and unrecognised names must add
    /// nothing to the bare segment; an arrow adds exactly one more subpath.
    #[test]
    fn line_endings_are_painted_and_unknown_ones_are_ignored() {
        let strokes = |le: Option<Vec<&str>>| -> usize {
            let mut d = annot("Line");
            d.set("L", Object::Array(vec![0.into(), 0.into(), 100.into(), 0.into()]));
            if let Some(le) = le {
                d.set("LE", Object::Array(le.iter().map(|s| name_obj(s)).collect()));
            }
            synth(&d, [0.0, -10.0, 100.0, 10.0])
                .iter()
                .filter(|p| matches!(p, Prim::Stroke { .. }))
                .count()
        };
        assert_eq!(strokes(None), 1, "no /LE: just the segment");
        assert_eq!(strokes(Some(vec!["None", "None"])), 1, "/None draws nothing");
        assert_eq!(strokes(Some(vec!["Wat", "Nope"])), 1, "unknown names draw nothing");
        assert_eq!(strokes(Some(vec!["None", "OpenArrow"])), 2, "one arrowhead");
        assert_eq!(strokes(Some(vec!["ClosedArrow", "ClosedArrow"])), 3, "two heads");
    }

    /// §7.3.10 lets any object be indirect. An indirect `/C` used to read as "no
    /// colour", silently substituting the default for the author's.
    #[test]
    fn an_indirect_colour_is_dereferenced() {
        let mut doc = Document::with_version("1.7");
        let cid = doc.add_object(Object::Array(vec![1.into(), 0.into(), 0.into()]));
        let mut d = annot("Underline");
        d.set("C", Object::Reference(cid));
        d.set("QuadPoints", Object::Array(vec![0.into(), 10.into(), 100.into(), 10.into(), 0.into(), 0.into(), 100.into(), 0.into()]));
        let mut prims = Vec::new();
        synthesize_annotation_appearance(&doc, &d, [0.0, 0.0, 100.0, 10.0], &IDENTITY, &mut prims);
        let argb = prims
            .iter()
            .find_map(|p| if let Prim::Stroke { argb, .. } = p { Some(*argb) } else { None })
            .expect("no stroke");
        assert_eq!(argb, 0xFFFF_0000, "indirect /C ignored, fell back to black");
    }
}
