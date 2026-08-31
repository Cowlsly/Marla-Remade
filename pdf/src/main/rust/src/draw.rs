use crate::*;

/// Everything on `Prim::Text` is derived from unvalidated file input (`Tf`, `Tz`,
/// `/Widths`, the CTM), and a non-finite value can survive even validated operands:
/// `f64 as f32` saturates to infinity above ~3.4e38. It cannot be left to the
/// consumer, because Kotlin's `coerceIn`/`coerceAtLeast` are comparisons and every
/// comparison against NaN is false — it passes straight through the clamp into
/// `Paint`, and the glyph silently disappears rather than being visibly wrong.
fn finite_or_zero(v: f32) -> f32 {
    if v.is_finite() {
        v
    } else {
        0.0
    }
}

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
    // §8.5.3.2: "If a subpath is degenerate (consists of a single-point closed
    // subpath or of two or more points at the same coordinates), S shall paint it
    // only if round line caps have been specified, producing a filled circle
    // centred at the single point. If butt or projecting square line caps have
    // been specified, S shall paint nothing." A single-point subpath (`x y m S`,
    // or `x y m h S` once `h` drops the duplicate) never reached the renderer at
    // all, so the dot idiom used for stippled leader lines and map symbols
    // vanished. The circle is emitted directly rather than left to the renderer's
    // stroker, because what the spec asks for here is a fill, not a stroke.
    let dot_radius = (width as f64 / 2.0).max(0.05);
    for sp in subpaths {
        // One primitive PER SUBPATH, so the caller's single pre-call cap check
        // cannot bound this loop: a path carrying MAX_SUBPATHS subpaths overshot
        // MAX_PRIMITIVES by up to that many. `emit_fill` has no equivalent problem
        // because it emits one Fill holding every contour. Reported by `r5-state`,
        // whose doc on the constant states it is enforced at every emitting push.
        if prims.len() >= MAX_PRIMITIVES {
            break;
        }
        if sp.is_empty() { continue; }
        let (fx, fy) = sp[0];
        if sp.iter().all(|&(x, y)| (x - fx).abs() < 1e-9 && (y - fy).abs() < 1e-9) {
            if gs.line_cap == 1 {
                const DOT_SEGMENTS: usize = 16;
                let circle: Vec<(f32, f32)> = (0..DOT_SEGMENTS)
                    .map(|i| {
                        let a = i as f64 / DOT_SEGMENTS as f64 * std::f64::consts::TAU;
                        ((fx + dot_radius * a.cos()) as f32, (fy + dot_radius * a.sin()) as f32)
                    })
                    .collect();
                prims.push(Prim::Fill { argb, even_odd: false, contours: vec![circle], blend });
            }
            continue;
        }
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

/// Show `bytes` with no ambient resource dictionary — see [`show_string_in`],
/// which is what the interpreter calls.
///
/// `#[cfg(test)]` because the only remaining callers are test modules (here,
/// `golden_tests.rs` and `tests.rs`). It is kept rather than folded into them so
/// those files, which belong to other owners, did not have to change when
/// §9.6.5's Type 3 resource fallback added the `ambient_resources` parameter.
#[cfg(test)]
pub(crate) fn show_string(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fonts: &HashMap<Vec<u8>, FontInfo>,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
) -> f64 {
    show_string_in(doc, prims, gs, fonts, text_matrix, bytes, depth, None)
}

/// Emit one text primitive per glyph, each positioned at its exact device-space
/// origin computed from the PDF glyph widths + text state. Drawing glyph-by-glyph
/// (rather than one run) keeps kerned/justified text aligned even though a
/// substitute system font renders the glyph shapes. Returns the total advance in
/// text space so the caller can step the text matrix.
///
/// `ambient_resources` is the resource dictionary in force at the showing
/// operator. Only Type 3 needs it: §9.6.5 makes a Type 3 font's own `/Resources`
/// optional and says the names "shall be looked up in the resource dictionary of
/// the page on which the font is used" when it is absent.
#[allow(clippy::too_many_arguments)]
pub(crate) fn show_string_in(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fonts: &HashMap<Vec<u8>, FontInfo>,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
    ambient_resources: Option<&lopdf::Dictionary>,
) -> f64 {
    let tfs = gs.font_size;
    let th = gs.h_scale;
    let trm = mat_mul(text_matrix, &gs.ctm);
    let y_scale = (trm[2] * trm[2] + trm[3] * trm[3]).sqrt();
    // Device-space horizontal scale, used to convert glyph advances (user space)
    // into the device advance carried on the wire for text selection/search.
    let x_scale = (trm[0] * trm[0] + trm[1] * trm[1]).sqrt();
    // Horizontal scale to put on the wire for the SUBSTITUTE face. Kotlin derives
    // the glyph's em from `size` (= Tfs·y_scale) and multiplies its width by
    // `h_scale`, so Th alone under-describes an anisotropic matrix: with
    // x_scale != y_scale the outline path draws the glyph x_scale/y_scale wider
    // than the substitute path does, and the two disagree about the SIZE of the
    // same glyph. Folding the ratio in is exactly 1.0 for every isotropic matrix
    // — including every pure rotation — so it changes nothing on ordinary pages.
    // This is the only part of the mismatch expressible in the v8 wire; the glyph
    // being axis-aligned under a rotated matrix needs a field that does not exist.
    //
    // Bounded here rather than at the consumer. A near-degenerate matrix makes the
    // ratio enormous, and `th` is `Tz/100` whose product can overflow; the check is
    // on the f32 that is actually serialized, because `f64 as f32` saturates to inf
    // above ~3.4e38 and a finite f64 would otherwise sail through.
    let aniso = if y_scale > 1e-9 { x_scale / y_scale } else { 1.0 };
    let wire_h_scale = th * if aniso.is_finite() { aniso.clamp(0.01, 100.0) } else { 1.0 };
    let wire_h_scale = {
        let v = wire_h_scale as f32;
        if v.is_finite() {
            v
        } else {
            1.0
        }
    };
    let size = (tfs * y_scale) as f32;
    // `Tf`'s operand and the CTM are file input, and `size` is their product, so a
    // non-finite value is reachable even with both validated — an f64 above the f32
    // range saturates to inf on the cast. NaN then survives Kotlin's
    // `coerceAtLeast` (a comparison) into `Paint.textSize` and the glyph vanishes.
    // Zero is the honest substitute: the consumer floors it to one pixel.
    let size = finite_or_zero(size);
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
            let run_advance = bytes.len() as f64 * 0.5 * tfs * th;
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
                        // The DEVICE advance of the whole run, which is what the
                        // wire contract says this field is and what the selection
                        // layer rescales a non-painted run to. `size` was one
                        // glyph's worth for a run of any length, so a mode-3 OCR
                        // run with no font resource had every selection rectangle
                        // piled onto its first character.
                        advance: finite_or_zero((run_advance * x_scale) as f32).max(size * 0.1),
                        render_mode: gs.render_mode as u8,
                        blend: gs.blend_mode,
                        is_bold: false,
                        is_italic: false,
                        font_family: 0,
                        outline: false,
                        h_scale: wire_h_scale,
                    });
                }
            }
            return run_advance;
        }
    };

    // Type 3 fonts: draw each glyph by interpreting its CharProc content stream.
    if let Some(t3) = &fi.t3 {
        return show_string_type3(doc, prims, gs, fi, t3, text_matrix, bytes, depth, ambient_resources);
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
            // Trise is part of §9.4.4's text-space parameter matrix, which does not
            // depend on the writing mode: it displaces the glyph in text-space y in
            // vertical writing exactly as it does in horizontal. Dropping it here put
            // super/subscripts in vertical CJK back on the baseline.
            (-vx * tfs * th, pen - vy * tfs + gs.rise, w1y * tfs - extra)
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
                // Same reasoning as `size`. The selection layer rescales a
                // non-painted run by `advance * scale / measured`, and its
                // `advance > 0f` guard PASSES infinity, so one overflowed glyph
                // would stretch every remaining glyph's rectangle in the run.
                let glyph_device_adv = finite_or_zero(glyph_device_adv);
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
                                h_scale: wire_h_scale,
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
                            h_scale: wire_h_scale,
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
                            h_scale: wire_h_scale,
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
                            h_scale: wire_h_scale,
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
#[allow(clippy::too_many_arguments)]
fn show_string_type3(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fi: &FontInfo,
    t3: &Type3Font,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
    ambient_resources: Option<&lopdf::Dictionary>,
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
    // See `show_string_in`: Th alone under-describes an anisotropic matrix to the
    // substitute face, is exactly right for every isotropic one, and is bounded on
    // the serialized f32 so neither a degenerate matrix nor an overflowing cast can
    // put a non-finite scale on the wire (NaN survives the consumer's clamp).
    let aniso = if y_scale > 1e-9 { x_scale / y_scale } else { 1.0 };
    let wire_h_scale = th * if aniso.is_finite() { aniso.clamp(0.01, 100.0) } else { 1.0 };
    let wire_h_scale = {
        let v = wire_h_scale as f32;
        if v.is_finite() {
            v
        } else {
            1.0
        }
    };
    let size = finite_or_zero((tfs * y_scale) as f32);
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
                            // §9.6.5: the Type 3 `/Resources` entry is optional, and
                            // "if any glyph descriptions refer to named resources but
                            // this dictionary is absent, the names shall be looked up
                            // in the resource dictionary of the page on which the font
                            // is used". Passing `None` instead made a CharProc that
                            // does `/Im0 Do`, `/GS0 gs` or `/Sh0 sh` against the page's
                            // resources draw nothing at all — common in TeX/dvips
                            // output, which routinely omits the font's own /Resources.
                            t3.resources.as_ref().or(ambient_resources),
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
                    advance: finite_or_zero((fi.width(code) * tfs * th * x_scale) as f32)
                        .max(size * 0.1),
                    stroke_argb: None,
                    stroke_width: None,
                    render_mode: gs.render_mode as u8,
                    blend: gs.blend_mode,
                    is_bold: false,
                    is_italic: false,
                    font_family: 0,
                    outline: false,
                    h_scale: wire_h_scale,
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
mod type3_resource_fallback_tests {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    /// §9.6.5: a Type 3 font's `/Resources` is optional, and "if any glyph
    /// descriptions refer to named resources but this dictionary is absent, the
    /// names shall be looked up in the resource dictionary of the page on which
    /// the font is used". `show_string_type3` passed `t3.resources` straight
    /// through, so with no `/Resources` on the font the CharProc's resource
    /// lookups all missed and the glyph drew NOTHING — not even a fallback shape.
    /// TeX/dvips bitmap-font output routinely omits the entry. Reported by
    /// `a-text2`.
    #[test]
    fn a_type3_charproc_falls_back_to_the_page_resources() {
        let mut doc = Document::with_version("1.7");
        // The CharProc paints through an ExtGState it can only reach via the page.
        let egs = doc.add_object(dictionary! { "ca" => 0.5 });
        let proc_id = doc.add_object(Stream::new(
            dictionary! {},
            b"/GSP gs 0 0 700 700 re f".to_vec(),
        ));
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type3",
            "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
            "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
            "CharProcs" => doc.add_object(dictionary! { "a" => proc_id }),
            "Encoding" => doc.add_object(dictionary! {
                "Type" => "Encoding", "Differences" => vec![65.into(), "a".into()],
            }),
            "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
            // Deliberately NO /Resources on the font: the whole point of the test.
        };
        let font_id = doc.add_object(font.clone());
        let res = dictionary! {
            "Font" => dictionary! { "F1" => Object::Reference(font_id) },
            "ExtGState" => dictionary! { "GSP" => Object::Reference(egs) },
        };
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 100.into()]),
            Operation::new("Tj", vec![Object::string_literal("A")]),
            Operation::new("ET", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);

        let alpha = prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { argb, .. } => Some((argb >> 24) as u8),
                _ => None,
            })
            .expect("the Type 3 glyph must paint");
        assert_eq!(
            alpha, 0x80,
            "the CharProc's /GSP must resolve through the page resources"
        );
    }
}

#[cfg(test)]
mod degenerate_stroke_tests {
    use crate::*;

    fn stroke(subpaths: &[Vec<(f64, f64)>], cap: u8) -> Vec<Prim> {
        let gs = GraphicsState { line_cap: cap, line_width: 4.0, ..Default::default() };
        let mut prims = Vec::new();
        emit_stroke(&mut prims, subpaths, &gs);
        prims
    }

    /// §8.5.3.2: "If a subpath is degenerate (consists of a single-point closed
    /// subpath or of two or more points at the same coordinates), `S` shall paint
    /// it only if round line caps have been specified, producing a filled circle
    /// centred at the single point. If butt or projecting square line caps have
    /// been specified, `S` shall paint nothing."
    ///
    /// A single-point subpath — what `x y m S` and `x y m h S` produce — was
    /// dropped before it reached the renderer, so the round-cap dot idiom used for
    /// stipple patterns, leader-line dots and map symbols painted nothing at all.
    #[test]
    fn a_degenerate_subpath_paints_a_dot_only_with_round_caps() {
        for sp in [vec![(5.0, 7.0)], vec![(5.0, 7.0), (5.0, 7.0)]] {
            let round = stroke(std::slice::from_ref(&sp), 1);
            let contours = round
                .iter()
                .find_map(|p| match p {
                    Prim::Fill { contours, .. } => Some(contours.clone()),
                    _ => None,
                })
                .unwrap_or_else(|| panic!("round caps must paint a dot for {sp:?}"));
            assert_eq!(contours.len(), 1);
            // Centred on the point, radius = half the (device) line width.
            for &(x, y) in &contours[0] {
                let r = ((x as f64 - 5.0).hypot(y as f64 - 7.0) - 2.0).abs();
                assert!(r < 1e-3, "dot vertex ({x}, {y}) is not on the r=2 circle");
            }

            for butt_or_square in [0u8, 2] {
                let out = stroke(std::slice::from_ref(&sp), butt_or_square);
                assert!(
                    out.is_empty(),
                    "cap {butt_or_square} must paint nothing for a degenerate \
                     subpath, got {} prims",
                    out.len()
                );
            }
        }
    }

    /// The dot path must not swallow real strokes: a subpath with distinct points
    /// still strokes, round caps or not.
    #[test]
    fn a_non_degenerate_subpath_still_strokes() {
        let sp = vec![vec![(0.0, 0.0), (10.0, 0.0)]];
        for cap in [0u8, 1, 2] {
            let out = stroke(&sp, cap);
            assert_eq!(
                out.iter().filter(|p| matches!(p, Prim::Stroke { .. })).count(),
                1,
                "cap {cap}"
            );
            assert!(!out.iter().any(|p| matches!(p, Prim::Fill { .. })));
        }
    }

    /// `emit_stroke` emits one primitive PER SUBPATH, and every caller checks the
    /// cap once BEFORE the call (`interpret.rs`: `else if prims.len() <
    /// MAX_PRIMITIVES { emit_stroke(…) }`), so the loop has to bound itself or a
    /// single `S` on a MAX_SUBPATHS-subpath path walks straight past the ceiling.
    /// `MAX_PRIMITIVES` is the process's memory guard against an uncatchable Rust
    /// OOM and its doc states it is enforced at every content-emitting push.
    #[test]
    fn emit_stroke_stops_at_the_primitive_cap() {
        let gs = GraphicsState { line_width: 1.0, ..Default::default() };
        let subpaths: Vec<Vec<(f64, f64)>> =
            (0..64).map(|i| vec![(i as f64, 0.0), (i as f64, 10.0)]).collect();
        let mut prims: Vec<Prim> = Vec::new();
        // Start just under the cap, as the caller's own check guarantees. Built by
        // `extend` rather than `resize` on purpose: `Vec::resize` needs `T: Clone`,
        // and `Prim` must not be cloneable-by-habit — `Image`/`ImageTiled` carry the
        // whole decoded payload. Nothing here should force a derive on model.rs.
        prims.extend((0..MAX_PRIMITIVES - 4).map(|_| Prim::ClipPop));
        emit_stroke(&mut prims, &subpaths, &gs);
        assert_eq!(
            prims.len(),
            MAX_PRIMITIVES,
            "the cap must bound the per-subpath loop, not just its entry"
        );
    }
}

#[cfg(test)]
mod blind_reaudit_r5_text_tests {
    use crate::outlines::GlyphProgram;
    use crate::type1::Type1Font;
    use crate::*;
    use std::sync::Arc;

    /// A 1000x1000 box on a 1000-unit em, reachable as glyph name "A" at code 65.
    fn embedded_box_font(wmode: u8) -> FontInfo {
        let t1 = Type1Font {
            glyphs: [(
                "A".to_string(),
                vec![vec![(0.0, 0.0), (1000.0, 0.0), (1000.0, 1000.0), (0.0, 1000.0), (0.0, 0.0)]],
            )]
            .into_iter()
            .collect(),
            encoding: [(65u32, "A".to_string())].into_iter().collect(),
            font_matrix: [0.001, 0.0, 0.0, 0.001, 0.0, 0.0],
        };
        FontInfo {
            two_byte: false,
            wmode,
            vertical_metrics: Arc::default(),
            default_vertical: (0.880, -1.0),
            cid_to_gid: None,
            to_unicode: None,
            encoding: Arc::new([(65u32, 'A')].into_iter().collect()),
            cmap_uni: Arc::default(),
            cmap: None,
            widths: Arc::new([(65u32, 0.5)].into_iter().collect()),
            default_width: 0.5,
            t3: None,
            style: FontStyle::default(),
            family: 0,
            base_font: String::new(),
            glyph_program: Some(Arc::new(GlyphProgram::Type1(t1))),
            glyph_names: Arc::new([(65u32, "A".to_string())].into_iter().collect()),
        }
    }

    fn show(fi: FontInfo, gs: GraphicsState) -> Vec<Prim> {
        let doc = Document::with_version("1.7");
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), fi);
        let mut prims = Vec::new();
        show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);
        prims
    }

    fn state(render_mode: i64) -> GraphicsState {
        GraphicsState {
            font_key: b"F1".to_vec(),
            font_size: 100.0,
            render_mode,
            ..Default::default()
        }
    }

    /// §9.3.6 Table 106: mode 3 is "Neither fill nor stroke text (invisible)" and
    /// mode 7 is "Add to path for clipping" — neither marks the page. Mode 3 is how
    /// every scanned document carries its OCR layer, so if it paints, the scan is
    /// overprinted with a second copy of its own text.
    ///
    /// Asserted against an EMBEDDED font, because that is the path that can paint:
    /// modes 0-2 emit real outlines as `Prim::Fill`/`Prim::Stroke`, which the Kotlin
    /// side has no render-mode guard for (it skips `Prim::Text` for rm 3/7, and skips
    /// `outline`-flagged Text entirely). Ink for mode 3 therefore has to be suppressed
    /// HERE or not at all.
    #[test]
    fn render_mode_3_and_7_emit_no_ink_even_with_an_embedded_program() {
        let ink = |p: &Prim| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. });

        // Precondition: this font really does paint in a painting mode.
        assert!(
            show(embedded_box_font(0), state(0)).iter().any(ink),
            "precondition: mode 0 must emit outline ink, or the test proves nothing"
        );

        for rm in [3i64, 7] {
            let prims = show(embedded_box_font(0), state(rm));
            assert!(
                !prims.iter().any(ink),
                "render mode {rm} must paint nothing, got {} ink prim(s)",
                prims.iter().filter(|p| ink(p)).count()
            );
            let texts: Vec<_> = prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Text { argb, render_mode, text, .. } => Some((*argb, *render_mode, text)),
                    _ => None,
                })
                .collect();
            assert_eq!(texts.len(), 1, "mode {rm} must still reach the text index");
            assert_eq!(texts[0].0, 0, "mode {rm} text prim must carry no colour");
            assert_eq!(texts[0].1, rm as u8);
        }
    }

    /// The glyph-space -> text-space scale is the font program's units-per-em, and it
    /// is applied exactly once: a 1000-unit box on a 1000-unit em at 100 Tf spans
    /// 100 user-space units. Applying it twice (or not at all) is invisible in a
    /// units-per-em-1000 font only if the second factor is 1, which is why this
    /// asserts the absolute extent rather than a ratio.
    #[test]
    fn an_embedded_outline_is_scaled_by_units_per_em_exactly_once() {
        let prims = show(embedded_box_font(0), state(0));
        let contours = prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { contours, .. } => Some(contours.clone()),
                _ => None,
            })
            .expect("mode 0 with an embedded program must emit outline fills");
        let xs: Vec<f32> = contours.iter().flatten().map(|p| p.0).collect();
        let ys: Vec<f32> = contours.iter().flatten().map(|p| p.1).collect();
        let max_x = xs.iter().cloned().fold(f32::MIN, f32::max);
        let max_y = ys.iter().cloned().fold(f32::MIN, f32::max);
        assert!((max_x - 100.0).abs() < 1e-3, "em box should span 100 user units, got {max_x}");
        assert!((max_y - 100.0).abs() < 1e-3, "em box should span 100 user units, got {max_y}");
    }

    /// Horizontal scaling (Tz) scales the glyph and its advance horizontally only
    /// (§9.4.4: Th multiplies the x column of the text-space parameter matrix).
    #[test]
    fn horizontal_scaling_widens_the_outline_without_stretching_it_vertically() {
        let gs = GraphicsState { h_scale: 2.0, ..state(0) };
        let prims = show(embedded_box_font(0), gs);
        let contours = prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { contours, .. } => Some(contours.clone()),
                _ => None,
            })
            .expect("outline fill");
        let max_x = contours.iter().flatten().map(|p| p.0).fold(f32::MIN, f32::max);
        let max_y = contours.iter().flatten().map(|p| p.1).fold(f32::MIN, f32::max);
        assert!((max_x - 200.0).abs() < 1e-3, "Tz 200 must double the width, got {max_x}");
        assert!((max_y - 100.0).abs() < 1e-3, "Tz must not touch the height, got {max_y}");
    }

    /// §9.4.4: Trise is a row of the text-space parameter matrix, which does not
    /// depend on the writing mode. The vertical branch built its placement point
    /// from the position vector alone and dropped Trise, so a superscript in
    /// vertical CJK sat on the baseline.
    #[test]
    fn text_rise_applies_in_vertical_writing_mode_too() {
        let origin = |rise: f64| {
            let gs = GraphicsState { rise, ..state(3) };
            show(embedded_box_font(1), gs)
                .into_iter()
                .find_map(|p| match p {
                    Prim::Text { y, .. } => Some(y),
                    _ => None,
                })
                .expect("a text prim per glyph")
        };
        assert!(
            (origin(20.0) - origin(0.0) - 20.0).abs() < 1e-3,
            "Trise must displace a vertical glyph by 20 user units"
        );
    }

    /// Substitute glyphs are sized by Kotlin as `size` (the em, taken from the
    /// matrix's Y scale) times `h_scale` (Tz). Under an ANISOTROPIC matrix those
    /// two do not describe the glyph the outline path draws: the outline is scaled
    /// by x_scale horizontally and y_scale vertically, so the substitute came out
    /// narrower or wider by exactly that ratio — the two paths disagreeing about
    /// the size of the same glyph. The ratio is the only part of the mismatch the
    /// current wire can carry (a rotation still needs a field that does not exist),
    /// and it must be exactly 1 for every isotropic matrix, which is nearly all of
    /// them — hence the second half of this test.
    #[test]
    fn the_substitute_face_is_told_the_matrixs_horizontal_scale() {
        let h_scale_for = |ctm: Mat, th: f64| {
            let mut fi = embedded_box_font(0);
            fi.glyph_program = None; // force the substitute path
            let gs = GraphicsState { ctm, h_scale: th, ..state(0) };
            show(fi, gs)
                .into_iter()
                .find_map(|p| match p {
                    Prim::Text { h_scale, size, .. } => Some((h_scale, size)),
                    _ => None,
                })
                .expect("substitute path emits a text prim")
        };

        // Isotropic: unchanged, whatever the zoom. This is the case that must not move.
        for s in [1.0, 3.0, 0.25] {
            let (hs, _) = h_scale_for([s, 0.0, 0.0, s, 0.0, 0.0], 1.0);
            assert!((hs - 1.0).abs() < 1e-5, "isotropic scale {s} must leave Tz alone, got {hs}");
        }
        // A pure rotation is isotropic too.
        let (a, b) = (0.6_f64, 0.8_f64); // cos/sin of a 53-degree rotation
        let (hs, _) = h_scale_for([a, b, -b, a, 0.0, 0.0], 1.0);
        assert!((hs - 1.0).abs() < 1e-5, "a pure rotation must leave Tz alone, got {hs}");

        // Anisotropic: x twice y. The em still comes from the Y scale, and the
        // horizontal stretch rides on h_scale.
        let (hs, size) = h_scale_for([2.0, 0.0, 0.0, 1.0, 0.0, 0.0], 1.0);
        assert!((hs - 2.0).abs() < 1e-5, "x_scale/y_scale = 2 must reach the wire, got {hs}");
        assert!((size - 100.0).abs() < 1e-3, "the em still follows the Y scale, got {size}");

        // …and it composes with a real Tz rather than replacing it.
        let (hs, _) = h_scale_for([2.0, 0.0, 0.0, 1.0, 0.0, 0.0], 0.5);
        assert!((hs - 1.0).abs() < 1e-5, "Tz 50% under a 2:1 matrix, got {hs}");

        // A near-degenerate matrix must not put a non-finite or absurd scale on the
        // wire. y_scale sits just above the divide-by-zero guard, so the raw ratio
        // is ~5e8; the producer bounds it rather than relying on the consumer.
        let (hs, _) = h_scale_for([1.0, 0.0, 0.0, 2e-9, 0.0, 0.0], 1.0);
        assert!(hs.is_finite() && (0.01..=100.0).contains(&hs), "unbounded scale {hs}");
        // Fully degenerate (below the guard) falls back to plain Tz.
        let (hs, _) = h_scale_for([1.0, 0.0, 0.0, 0.0, 0.0, 0.0], 0.75);
        assert!((hs - 0.75).abs() < 1e-5, "degenerate matrix must yield plain Tz, got {hs}");

        // A pathological Tz must not reach the wire either. NaN is the one that
        // matters: Kotlin's `coerceIn` is two comparisons, both false against NaN,
        // so it would sail through the consumer's clamp into `Paint.textScaleX` and
        // the glyph would silently disappear rather than be the wrong width.
        for bad_tz in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
            for ctm in [[1.0, 0.0, 0.0, 1.0, 0.0, 0.0], [2.0, 0.0, 0.0, 1.0, 0.0, 0.0]] {
                let (hs, _) = h_scale_for(ctm, bad_tz);
                assert!(hs.is_finite(), "Tz {bad_tz} put {hs} on the wire");
            }
        }
    }

    /// `Tf`'s operand and the CTM are file input and `size` is their product, so a
    /// non-finite value is reachable even with both validated upstream: `f64 as f32`
    /// saturates to infinity above ~3.4e38, and `inf * 0` from a zero-scale matrix
    /// is NaN. It matters because NaN is not merely a wrong number here — Kotlin
    /// floors the size with `coerceAtLeast`, a comparison that is false against NaN,
    /// so it reaches `Paint.textSize` and the glyph vanishes.
    #[test]
    fn a_pathological_font_size_cannot_put_nan_on_the_wire() {
        let sizes = |tfs: f64, ctm: Mat| {
            let mut fi = embedded_box_font(0);
            fi.glyph_program = None;
            let gs = GraphicsState { ctm, font_size: tfs, ..state(0) };
            show(fi, gs)
                .into_iter()
                .filter_map(|p| match p {
                    Prim::Text { size, advance, .. } => Some((size, advance)),
                    _ => None,
                })
                .collect::<Vec<_>>()
        };
        let zero_scale: Mat = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0];
        let identity: Mat = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];
        for (tfs, ctm) in [
            (f64::INFINITY, zero_scale), // inf * 0 = NaN, the reachable case
            (f64::INFINITY, identity),
            (f64::NAN, identity),
            (1e308, identity),
        ] {
            for (size, advance) in sizes(tfs, ctm) {
                assert!(size.is_finite(), "Tf {tfs} produced size {size}");
                assert!(advance.is_finite(), "Tf {tfs} produced advance {advance}");
            }
        }
    }

    /// The no-metrics fallback returns a run advance of `len * 0.5 * Tfs * Th` but
    /// used to put ONE glyph's `size` on the wire as the run's device advance. A
    /// non-painted run (mode 3 — the OCR layer of a scan with an unresolvable font
    /// resource) is aligned to that field by the selection layer, so every glyph's
    /// selection rectangle piled up on the first character.
    #[test]
    fn a_run_with_no_font_metrics_reports_the_whole_runs_advance() {
        let doc = Document::with_version("1.7");
        let fonts: HashMap<Vec<u8>, FontInfo> = HashMap::new();
        let gs = GraphicsState { font_key: b"F1".to_vec(), font_size: 10.0, ..Default::default() };
        let mut prims = Vec::new();
        let pen = show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"ABCD", 0);
        let advance = prims
            .iter()
            .find_map(|p| match p {
                Prim::Text { advance, .. } => Some(*advance),
                _ => None,
            })
            .expect("the run must still reach the text index");
        assert!((pen - 20.0).abs() < 1e-9, "4 codes at 0.5 em of 10 Tf");
        assert!(
            (advance - pen as f32).abs() < 1e-3,
            "the wire advance must span the whole run ({pen}), got {advance}"
        );
    }
}

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
