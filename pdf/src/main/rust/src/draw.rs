use crate::*;

pub(crate) fn cubic_bezier(
    p0: (f64, f64),
    p1: (f64, f64),
    p2: (f64, f64),
    p3: (f64, f64),
    t: f64,
) -> (f64, f64) {
    let u = 1.0 - t;
    let w0 = u * u * u;
    let w1 = 3.0 * u * u * t;
    let w2 = 3.0 * u * t * t;
    let w3 = t * t * t;
    (
        w0 * p0.0 + w1 * p1.0 + w2 * p2.0 + w3 * p3.0,
        w0 * p0.1 + w1 * p1.1 + w2 * p2.1 + w3 * p3.1,
    )
}

// old emit_fill removed - replaced by alpha-aware version


pub(crate) fn emit_stroke(prims: &mut Vec<Prim>, subpaths: &[Vec<(f64, f64)>], gs: &GraphicsState) {
    // Device-space scale via CTM
    let ctm = &gs.ctm;
    let sx = (ctm[0] * ctm[0] + ctm[1] * ctm[1]).sqrt();
    let sy = (ctm[2] * ctm[2] + ctm[3] * ctm[3]).sqrt();
    let scale = (sx + sy) / 2.0;
    let width = (gs.line_width * scale) as f32;
    // Single-element dash is valid (odd -> duplicate) per PDF spec — fix #19
    let mut dash: Vec<f32> = gs.dash.iter().map(|d| (d * scale) as f32).filter(|d| *d >= 0.0).collect();
    if dash.len() == 1 && dash[0] > 0.0 {
        dash.push(dash[0]);
    } else if dash.len() % 2 == 1 && dash.len() > 1 {
        let cl = dash.clone();
        dash.extend(cl);
    }
    let dash = if dash.len() >= 2 && dash.iter().sum::<f32>() > 0.0 { dash } else { Vec::new() };
    let dash_phase = (gs.dash_phase * scale) as f32;
    let argb = apply_alpha_to_argb(gs.stroke, gs.alpha_stroke);
    // Overprint is a device-colorant control (PDF 8.6.7) and has no meaning on an
    // additive RGB compositor: there are no separations to leave unmarked. The
    // old Multiply approximation actively broke pages, since `white MULTIPLY dst
    // == dst` turned white knockout rectangles into no-ops and let content that
    // was meant to be covered show through.
    let blend = gs.blend_mode;
    for sp in subpaths {
        if sp.len() >= 2 {
            prims.push(Prim::Stroke {
                argb,
                width: width.max(0.1),
                dash: dash.clone(),
                dash_phase,
                cap: gs.line_cap,
                join: gs.line_join,
                miter: gs.miter_limit as f32,
                pts: sp.iter().map(|&(x, y)| (x as f32, y as f32)).collect(),
                blend,
            });
        }
    }
}

pub(crate) fn emit_fill(prims: &mut Vec<Prim>, subpaths: &[Vec<(f64, f64)>], argb: u32, even_odd: bool, alpha_fill: f64, blend: BlendMode) {
    let argb = apply_alpha_to_argb(argb, alpha_fill);
    // All subpaths of the path form ONE fill region so interior contours (glyph
    // counters / holes) are cut out by the winding rule, instead of being filled
    // in as separate solid polygons.
    let contours: Vec<Vec<(f32, f32)>> = subpaths
        .iter()
        .filter(|sp| sp.len() >= 3)
        .map(|sp| sp.iter().map(|&(x, y)| (x as f32, y as f32)).collect())
        .collect();
    if !contours.is_empty() {
        prims.push(Prim::Fill { argb, even_odd, contours, blend });
    }
}

/// Emit a text primitive for `bytes` at the current text matrix (unless the
/// render mode is invisible/clip-only) and return the horizontal advance in
/// user-space units so the caller can step the text matrix.
/// Emit one text primitive per glyph, each positioned at its exact device-space
/// origin computed from the PDF glyph widths + text state. Drawing glyph-by-glyph
/// (rather than one run) keeps kerned/justified text aligned even though a
/// substitute system font renders the glyph shapes. Returns the total advance in
/// text space so the caller can step the text matrix.
pub(crate) fn show_string(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fonts: &HashMap<Vec<u8>, FontInfo>,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
) -> f64 {
    let tfs = gs.font_size;
    let th = gs.h_scale;
    let trm = mat_mul(text_matrix, &gs.ctm);
    let y_scale = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    // Device-space horizontal scale, used to convert glyph advances (user space)
    // into the device advance carried on the wire for text selection/search.
    let x_scale = (trm[0] * trm[0] + trm[1] * trm[1]).sqrt();
    let size = (tfs * y_scale) as f32;
    // Modes 3 (invisible) and 7 (clip only) advance the pen but paint nothing.
    let drawable = gs.render_mode != 3 && gs.render_mode != 7;
    // Mode 3 is how a scan carries its OCR layer: nothing is painted, but the run
    // must still reach the text index or the document is unsearchable. `argb: 0`
    // here plus the `rm != 3` paint guard in SafePdfViewerScreen.kt keep it unseen.
    let invisible = gs.render_mode == 3;

    let fi = match fonts.get(&gs.font_key) {
        Some(fi) => fi,
        None => {
            // No font metrics: emit the run at the origin and estimate advance.
            if (drawable || invisible) && !bytes.is_empty() {
                let (x, y) = transform(&trm, 0.0, gs.rise);
                let text: String =
                    bytes.iter().filter_map(|&b| char::from_u32(b as u32)).collect();
                if !text.is_empty() {
                    prims.push(Prim::Text {
                        x: x as f32,
                        y: y as f32,
                        size,
                        argb: if invisible { 0 } else { apply_alpha_to_argb(gs.fill, gs.alpha_fill) },
                        text,
                        stroke_argb: None,
                        stroke_width: None,
                        advance: size,
                        render_mode: gs.render_mode as u8,
                        blend: gs.blend_mode,
                        is_bold: false,
                        is_italic: false,
                        font_family: 0,
                        outline: false,
                        h_scale: th as f32,
                    });
                }
            }
            return bytes.len() as f64 * 0.5 * tfs * th;
        }
    };

    // Type 3 fonts: draw each glyph by interpreting its CharProc content stream.
    if let Some(t3) = &fi.t3 {
        return show_string_type3(doc, prims, gs, fi, t3, text_matrix, bytes, depth);
    }

    let mut pen = 0.0_f64;
    // Fix high #12: device stroke width should use Trm scale (includes Tm·Tfs·Th), not just CTM
    let trm = mat_mul(text_matrix, &gs.ctm);
    let sx_trm = (trm[0] * trm[0] + trm[1] * trm[1]).sqrt();
    let sy_trm = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    let avg_trm_scale = (sx_trm + sy_trm) * 0.5;
    let device_stroke_w = (gs.line_width * avg_trm_scale) as f32;
    // Constant per-font attributes hoisted out of the per-glyph closure.
    let bold = fi.style.bold;
    let italic = fi.style.italic;
    let family = fi.family;
    let has_program = fi.glyph_program.is_some();
    // Vertical writing mode (WMode 1): glyphs advance down the page and are
    // positioned by the /W2 //DW2 position vector (PDF 9.4.4).
    let vertical = fi.wmode == 1;

    fi.for_each_code(bytes, |code, is_space| {
        let w0 = fi.width(code); // horizontal glyph width (em)
        let tx = w0 * tfs + gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
        let glyph_advance_user = tx * th; // accurate advance using /Widths /W
        // Placement point (text space) and pen advance depend on writing mode.
        // Vertical (PDF 9.4.4, 9.7.4.3): the glyph is offset by the position
        // vector v and the pen advances by w1_y, both from /W2 //DW2 rather than
        // assumed. Tc/Tw keep widening the gap as they do horizontally, which
        // deviates from the literal `ty = w1*Tfs + Tc + Tw` in 9.4.4 (where a
        // positive Tc would tighten negative-w1 vertical text) but matches the
        // behaviour every horizontal path here already has.
        let (place_x, place_y, advance) = if vertical {
            let cid = fi.to_cid(code);
            let (w1y, vx) = fi
                .vertical_metrics
                .get(&cid)
                .copied()
                .unwrap_or((fi.default_vertical.1, 0.5 * w0));
            let vy = fi.default_vertical.0;
            let extra = gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
            (-vx * tfs * th, pen - vy * tfs, w1y * tfs - extra)
        } else {
            (pen, gs.rise, glyph_advance_user)
        };
        // Render modes 0-7 (PDF 9.3.6, Table 106) all emit something: 0-2 ink,
        // 4-6 ink plus clip, 7 clip only, and 3 an invisible record that keeps the
        // text selectable and searchable. An out-of-range Tr emits nothing.
        if (0..=7).contains(&gs.render_mode) {
            let (x, y) = transform(&trm, place_x, place_y);
            let mut s = String::new();
            fi.push_code(code, &mut s);
            // `s` is the selection/search payload only. A glyph whose Unicode
            // cannot be recovered (surrogate-range CID, code > 0x10FFFF, or a
            // /ToUnicode entry mapping to an empty string) must still be PAINTED
            // from its embedded outline, so nothing below is gated on `s` except
            // the Prim::Text records that carry the text itself.
            {
                let fill_alpha = gs.alpha_fill;
                let stroke_alpha = gs.alpha_stroke;
                let has_fill = matches!(gs.render_mode, 0|2|4|6);
                let has_stroke = matches!(gs.render_mode, 1|2|5|6);
                let clip_only = gs.render_mode == 7;
                let invisible = gs.render_mode == 3;
                let rm = gs.render_mode as u8;
                let glyph_device_adv = if vertical {
                    (advance.abs() * y_scale) as f32
                } else {
                    (glyph_advance_user * x_scale) as f32
                };
                // Real embedded outline for pure paint modes (0/1/2). Clip modes
                // (4-7) keep the substitute-glyph path so Kotlin can build the clip.
                let outline = if has_program && matches!(gs.render_mode, 0..=2) {
                    crate::outlines::glyph_outline(fi, code)
                } else {
                    None
                };
                if let Some((contours, upm)) = outline {
                    // Glyph space (font units) -> device: (1/upm) · [Tfs·Th,0,0,Tfs] ·
                    // translate(pen, rise) · Tm · CTM. Mirrors the Type 3 pipeline.
                    let font_matrix: Mat = [1.0 / upm, 0.0, 0.0, 1.0 / upm, 0.0, 0.0];
                    let scale_m: Mat = [tfs * th, 0.0, 0.0, tfs, 0.0, 0.0];
                    let place = translate(place_x, place_y);
                    let m1 = mat_mul(&scale_m, &mat_mul(&place, &trm));
                    let glyph_ctm = mat_mul(&font_matrix, &m1);
                    let dev: Vec<Vec<(f32, f32)>> = contours
                        .iter()
                        .map(|c| {
                            c.iter()
                                .map(|&(gx, gy)| {
                                    let (dx, dy) = transform(&glyph_ctm, gx, gy);
                                    (dx as f32, dy as f32)
                                })
                                .collect()
                        })
                        .collect();
                    if prims.len() < MAX_PRIMITIVES {
                        if has_fill {
                            prims.push(Prim::Fill {
                                argb: apply_alpha_to_argb(gs.fill, fill_alpha),
                                even_odd: false,
                                contours: dev.clone(),
                                blend: gs.blend_mode,
                            });
                        }
                        if has_stroke {
                            let sargb = apply_alpha_to_argb(gs.stroke, stroke_alpha);
                            for c in &dev {
                                if c.len() >= 2 {
                                    prims.push(Prim::Stroke {
                                        argb: sargb,
                                        width: device_stroke_w.max(0.1),
                                        dash: Vec::new(),
                                        dash_phase: 0.0,
                                        cap: gs.line_cap,
                                        join: gs.line_join,
                                        miter: gs.miter_limit as f32,
                                        pts: c.clone(),
                                        blend: gs.blend_mode,
                                    });
                                }
                            }
                        }
                        // Non-painting Text carrying the glyph for selection/search.
                        // Skipped when no Unicode was recoverable — the ink above is
                        // already on the page either way.
                        if !s.is_empty() {
                            prims.push(Prim::Text {
                                x: x as f32,
                                y: y as f32,
                                size,
                                argb: apply_alpha_to_argb(gs.fill, fill_alpha),
                                text: s.clone(),
                                advance: glyph_device_adv.max(size * 0.1),
                                stroke_argb: None,
                                stroke_width: None,
                                render_mode: rm,
                                blend: gs.blend_mode,
                                is_bold: bold,
                                is_italic: italic,
                                font_family: family,
                                outline: true,
                                h_scale: th as f32,
                            });
                        }
                    }
                } else if !s.is_empty() && prims.len() < MAX_PRIMITIVES {
                    // Substitute-font path (no embedded program or glyph missing).
                    // Requires a string: there is no outline to fall back on, so a
                    // glyph with no recoverable Unicode simply cannot be drawn here.
                    if has_fill {
                        prims.push(Prim::Text {
                            x: x as f32,
                            y: y as f32,
                            size,
                            argb: apply_alpha_to_argb(gs.fill, fill_alpha),
                            text: s.clone(),
                            advance: glyph_device_adv.max(size * 0.1),
                            stroke_argb: if has_stroke { Some(apply_alpha_to_argb(gs.stroke, stroke_alpha)) } else { None },
                            stroke_width: if has_stroke { Some(device_stroke_w) } else { None },
                            render_mode: rm,
                            blend: gs.blend_mode,
                            is_bold: bold,
                            is_italic: italic,
                            font_family: family,
                            outline: false,
                            h_scale: th as f32,
                        });
                    } else if has_stroke {
                        prims.push(Prim::Text {
                            x: x as f32,
                            y: y as f32,
                            size,
                            argb: apply_alpha_to_argb(gs.stroke, stroke_alpha), // stroke-only: use stroke color as fill for visibility (Kotlin draws stroke)
                            text: s.clone(),
                            advance: glyph_device_adv.max(size * 0.1),
                            stroke_argb: Some(apply_alpha_to_argb(gs.stroke, stroke_alpha)),
                            stroke_width: Some(device_stroke_w),
                            render_mode: rm,
                            blend: gs.blend_mode,
                            is_bold: bold,
                            is_italic: italic,
                            font_family: family,
                            outline: false,
                            h_scale: th as f32,
                        });
                    } else if clip_only || invisible {
                        // Mode 7: no paint, but carry the glyph so Kotlin can add
                        // its outline to the clip at the text-clip-apply marker.
                        // Mode 3: paints nothing at all, but the glyph must still
                        // reach the text index -- a scanned page's OCR layer is
                        // drawn in mode 3, and dropping it is why such documents
                        // had no selectable or searchable text. Kotlin skips
                        // painting both modes.
                        prims.push(Prim::Text {
                            x: x as f32,
                            y: y as f32,
                            size,
                            argb: 0,
                            text: s.clone(),
                            advance: glyph_device_adv.max(size * 0.1),
                            stroke_argb: None,
                            stroke_width: None,
                            render_mode: rm,
                            blend: gs.blend_mode,
                            is_bold: bold,
                            is_italic: italic,
                            font_family: family,
                            outline: false,
                            h_scale: th as f32,
                        });
                    }
                }
            }
        }
        pen += advance;
    });
    // No `prims.truncate(MAX_PRIMITIVES)` here. MAX_PRIMITIVES bounds PAINT
    // primitives, and every push above is already gated on it, so a truncate can
    // only ever fire on primitives this call did not emit — cutting the tail off
    // a caller's ClipPush/ClipPop or SoftMaskPush/Pop bracket and leaving the
    // renderer's stack unbalanced for the rest of the page. Structural prims are
    // bounded transitively instead: each needs its own operator, and the operator
    // loop is capped at MAX_CONTENT_OPS with nesting capped at MAX_CLIP_DEPTH.
    pen
}

/// Clip and transparency-group brackets left open in `slice`, outermost first.
/// `true` = clip, `false` = group. Soft-mask prims are ignored: a soft mask is
/// not closable by appending a pop (see the per-glyph cap in `show_string_type3`).
fn open_brackets(slice: &[Prim]) -> Vec<bool> {
    let mut open: Vec<bool> = Vec::new();
    for p in slice {
        match p {
            Prim::ClipPush { .. } => open.push(true),
            Prim::GroupPush { .. } => open.push(false),
            Prim::ClipPop | Prim::GroupPop => {
                open.pop();
            }
            _ => {}
        }
    }
    open
}

/// Append the matching pops for `open`, innermost first.
fn push_closers(prims: &mut Vec<Prim>, mut open: Vec<bool>) {
    while let Some(is_clip) = open.pop() {
        prims.push(if is_clip { Prim::ClipPop } else { Prim::GroupPop });
    }
}

/// Render a Type 3 text run by interpreting each glyph's CharProc content stream
/// into the current graphics state. Returns the total text-space advance.
fn show_string_type3(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fi: &FontInfo,
    t3: &Type3Font,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
) -> f64 {
    let tfs = gs.font_size;
    let th = gs.h_scale;
    let drawable = gs.render_mode != 3 && gs.render_mode != 7;
    // Mode 3 in a Type 3 font is still an OCR layer: skip the CharProc (it would
    // paint ink) but emit the non-painting Text record so the glyph is selectable.
    let invisible = gs.render_mode == 3;
    let trm = mat_mul(text_matrix, &gs.ctm);
    let x_scale = (trm[0] * trm[0] + trm[1] * trm[1]).sqrt();
    let y_scale = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    let size = (tfs * y_scale) as f32;
    let mut pen = 0.0_f64;
    let mut glyphs = 0usize;

    fi.for_each_code(bytes, |code, is_space| {
        let advance = fi.width(code) * tfs + gs.char_spacing + if is_space { gs.word_spacing } else { 0.0 };
        let advance = advance * th;
        if drawable
            && glyphs < MAX_TYPE3_GLYPHS
            && depth < MAX_PATTERN_RECURSION
            && prims.len() < MAX_PRIMITIVES
        {
            if let Some(&proc_id) = t3.char_procs.get(&code) {
                if let Ok(Object::Stream(s)) = doc.get_object(proc_id) {
                    let glyph_ops = crate::content::stream_operations(doc, s);
                    if !glyph_ops.is_empty() {
                        // Glyph space -> device: FontMatrix · [Tfs·Th,0,0,Tfs,0,0]
                        // · translate(pen, rise) · Tm · CTM.
                        let scale_m: Mat = [tfs * th, 0.0, 0.0, tfs, 0.0, 0.0];
                        let place = translate(pen, gs.rise);
                        let m1 = mat_mul(&scale_m, &mat_mul(&place, &mat_mul(text_matrix, &gs.ctm)));
                        let glyph_ctm = mat_mul(&t3.font_matrix, &m1);
                        let mut glyph_gs = gs.clone();
                        glyph_gs.ctm = glyph_ctm;
                        let before = prims.len();
                        interpret_content(
                            doc,
                            &glyph_ops,
                            t3.resources.as_ref(),
                            glyph_gs,
                            prims,
                            depth + 1,
                            false,
                        );
                        // Bound the per-glyph primitive count without breaking the
                        // renderer's save/restore stack. A CharProc may wrap all its
                        // content in `q … W n … Q` or a form XObject, so the capped
                        // range can hold ClipPush/GroupPush with their pops beyond the
                        // cap: cutting blind severs them and everything after this
                        // glyph on the page stays clipped. Cutting back to the last
                        // balanced point is safe but drops the whole glyph whenever the
                        // content sits inside one bracket, which is the common shape.
                        // So cut AT the cap and append the closers the cut orphaned.
                        let cap = before + MAX_TYPE3_PRIMS_PER_GLYPH;
                        if prims.len() > cap {
                            // A soft-mask bracket is the one kind that cannot be
                            // closed after the fact: per `model.rs` its mask is what
                            // FOLLOWS `SoftMaskContent`, so appending a bare
                            // `SoftMaskPop` leaves an empty mask, which hides the very
                            // content the mask exists to reveal. Locate the outermost
                            // one still open at the cap.
                            let mut mask_depth = 0i32;
                            let mut mask_start: Option<usize> = None;
                            for (i, p) in prims[before..cap].iter().enumerate() {
                                match p {
                                    Prim::SoftMaskPush { .. } => {
                                        if mask_depth == 0 { mask_start = Some(before + i); }
                                        mask_depth += 1;
                                    }
                                    Prim::SoftMaskPop => {
                                        mask_depth -= 1;
                                        if mask_depth == 0 { mask_start = None; }
                                    }
                                    _ => {}
                                }
                            }
                            let mut cut = cap;
                            let mut closed = false;
                            if let Some(start) = mask_start {
                                // Locate this bracket's `SoftMaskContent` separator and
                                // its matching `SoftMaskPop`.
                                let mut d = 0i32;
                                let mut content_idx = None;
                                let mut pop_idx = None;
                                for (i, p) in prims[start..].iter().enumerate() {
                                    match p {
                                        Prim::SoftMaskPush { .. } => d += 1,
                                        Prim::SoftMaskContent => {
                                            if d == 1 && content_idx.is_none() {
                                                content_idx = Some(start + i);
                                            }
                                        }
                                        Prim::SoftMaskPop => {
                                            d -= 1;
                                            if d == 0 { pop_idx = Some(start + i); break; }
                                        }
                                        _ => {}
                                    }
                                }
                                match (content_idx, pop_idx) {
                                    // The cap falls in the MASKED CONTENT. Drop the
                                    // surplus content but move the mask back on, so the
                                    // bracket stays whole and keeps its real mask.
                                    // Cutting before the push instead would discard the
                                    // whole glyph, because consecutive paints under one
                                    // mask are coalesced into a SINGLE bracket whose
                                    // push sits at the very start.
                                    //
                                    // `ci >= cap` is load-bearing, not a case split:
                                    // draining a range that began BEFORE `cap` would
                                    // shift later indices down, and the `truncate(cap)`
                                    // below would then keep content that was originally
                                    // past the cap.
                                    (Some(ci), Some(pi)) if ci >= cap => {
                                        let tail: Vec<Prim> = prims.drain(ci..=pi).collect();
                                        prims.truncate(cap);
                                        // Nesting order matters. A bracket opened INSIDE
                                        // the masked content has to close before
                                        // `SoftMaskContent`; one opened outside closes
                                        // after `SoftMaskPop`. Emitting both at the end
                                        // would cross them, leaving the renderer to
                                        // restore across a saveLayer boundary.
                                        let inner = open_brackets(&prims[start..]);
                                        push_closers(prims, inner);
                                        prims.extend(tail);
                                        let outer = open_brackets(&prims[before..start]);
                                        push_closers(prims, outer);
                                        closed = true;
                                        cut = prims.len();
                                    }
                                    // The cap falls inside the MASK itself, which is not
                                    // separable: complete the bracket instead. Bounded
                                    // by the mask group's own primitive count.
                                    (Some(_), Some(pi)) => cut = pi + 1,
                                    // No closing pop at all: drop the masked run rather
                                    // than emit a bracket whose mask never arrives.
                                    _ => cut = start,
                                }
                            }
                            prims.truncate(cut);
                            if !closed {
                                let open = open_brackets(&prims[before..]);
                                push_closers(prims, open);
                            }
                        }
                        glyphs += 1;
                    }
                }
            }
        } else if invisible && prims.len() < MAX_PRIMITIVES {
            let mut s = String::new();
            fi.push_code(code, &mut s);
            if !s.is_empty() {
                let (x, y) = transform(&trm, pen, gs.rise);
                prims.push(Prim::Text {
                    x: x as f32,
                    y: y as f32,
                    size,
                    argb: 0,
                    text: s,
                    advance: ((fi.width(code) * tfs * th * x_scale) as f32).max(size * 0.1),
                    stroke_argb: None,
                    stroke_width: None,
                    render_mode: gs.render_mode as u8,
                    blend: gs.blend_mode,
                    is_bold: false,
                    is_italic: false,
                    font_family: 0,
                    outline: false,
                    h_scale: th as f32,
                });
            }
        }
        pen += advance;
    });
    pen
}

// ---------------------------------------------------------------------------
// Image XObjects
// ---------------------------------------------------------------------------

#[cfg(test)]
mod type3_cap_tests {
    use crate::*;
    use lopdf::content::{Content, Operation};
    use lopdf::{dictionary, Stream};

    /// §9.6.5 + §11.6.5.1: when the per-glyph primitive bound falls inside a
    /// soft-mask bracket, the glyph must keep painting AND keep its real mask.
    ///
    /// A soft mask is the one bracket that cannot be closed after the fact — per
    /// `model.rs` the mask is what follows `SoftMaskContent`, so appending a bare
    /// `SoftMaskPop` yields an EMPTY mask, which hides the content it exists to
    /// reveal. And cutting back to before the `SoftMaskPush` discards the whole
    /// glyph, because `wrap_with_soft_mask` coalesces consecutive paints under one
    /// mask into a SINGLE bracket whose push sits at the very first prim. Both
    /// failure modes are silent, which is why this is asserted rather than reasoned.
    #[test]
    fn per_glyph_cap_inside_a_soft_mask_keeps_the_glyph_and_the_mask() {
        let mut doc = Document::with_version("1.7");
        let mask_id = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 1000.into(), 1000.into()],
                "Group" => dictionary! { "S" => "Transparency" },
            },
            Content { operations: vec![
                Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
                Operation::new("re", vec![0.into(), 0.into(), 1000.into(), 1000.into()]),
                Operation::new("f", vec![]),
            ]}.encode().unwrap(),
        ));
        let gs_id = doc.add_object(dictionary! {
            "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(mask_id) },
        });
        // Well past the cap, so it lands in the masked content rather than the mask.
        let mut src = String::from("/GS1 gs\n");
        for i in 0..(MAX_TYPE3_PRIMS_PER_GLYPH * 3) {
            src.push_str(&format!("0 {} 10 10 re f\n", i % 600));
        }
        let proc_id = doc.add_object(Stream::new(dictionary! {}, src.into_bytes()));
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type3",
            "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
            "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
            "CharProcs" => doc.add_object(dictionary! { "a" => proc_id }),
            "Encoding" => doc.add_object(dictionary! {
                "Type" => "Encoding", "Differences" => vec![65.into(), "a".into()],
            }),
            "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
            "Resources" => dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
        };
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
        let gs = GraphicsState { font_key: b"F1".to_vec(), font_size: 100.0, ..Default::default() };
        let mut prims = Vec::new();
        // Two glyphs, so a mis-cut on the first corrupts the one after it.
        show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"AA", 0);

        let count = |f: fn(&Prim) -> bool| prims.iter().filter(|p| f(p)).count();
        let fills = count(|p| matches!(p, Prim::Fill { .. }));
        let pushes = count(|p| matches!(p, Prim::SoftMaskPush { .. }));
        let contents = count(|p| matches!(p, Prim::SoftMaskContent));
        println!(
            "type3 smask cap: {} prims, {fills} fills, {pushes} push / {contents} content \
             (MAX_TYPE3_PRIMS_PER_GLYPH={MAX_TYPE3_PRIMS_PER_GLYPH})",
            prims.len()
        );
        assert!(fills > 0, "the glyph must still paint after being capped");
        assert!(
            fills < MAX_TYPE3_PRIMS_PER_GLYPH * 3,
            "the per-glyph bound must actually bind, or this proves nothing"
        );
        assert_eq!(
            pushes, contents,
            "every SoftMaskPush must keep its SoftMaskContent — a bracket closed \
             without one has an empty mask and hides the content it should reveal"
        );
        let mut depth = 0i32;
        for (i, p) in prims.iter().enumerate() {
            match p {
                Prim::SoftMaskPush { .. } => depth += 1,
                Prim::SoftMaskPop => {
                    depth -= 1;
                    assert!(depth >= 0, "unmatched SoftMaskPop at prim {i}");
                }
                _ => {}
            }
        }
        assert_eq!(depth, 0, "{depth} soft-mask level(s) left open by the cap");
    }
}