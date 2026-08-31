use crate::*;

/// Recursion depth allowed for transparency groups and soft masks. Kept in step
/// with the form-XObject limit in the `Do` arm: gating these at
/// [`MAX_PATTERN_RECURSION`] (4) while forms recurse to 10 silently dropped the
/// mask and painted the form unmasked.
pub(crate) const MAX_GROUP_DEPTH: u32 = 10;

/// Hard ceiling on saved graphics states. §8.4.2 puts no limit on `q` nesting, and
/// declining to save on overflow let colour/CTM changes leak past the matching
/// `Q`, misrendering everything after it. This is deliberately far above any real
/// document so the lossy path is unreachable in practice.
pub(crate) const MAX_GRAPHICS_STACK_HARD: usize = MAX_GRAPHICS_STACK * 16;

pub(crate) fn bezier_steps_for_flatness(hull: [(f64, f64); 4], flatness: f64) -> usize {
    // §10.6.2 defines flatness as a tolerance in DEVICE space, so the segment
    // count has to scale with the curve's device-space size. A fixed count made
    // large curves visibly faceted, and `i` could collapse a curve to one line.
    let mut len = 0.0;
    for w in hull.windows(2) {
        len += (w[1].0 - w[0].0).hypot(w[1].1 - w[0].1);
    }
    if !len.is_finite() || len <= 0.0 {
        return 1;
    }
    let tol = if flatness > 0.0 { flatness.min(3.0) } else { 0.25 };
    ((len / tol).sqrt().ceil() as usize).clamp(4, 64)
}

pub(crate) fn shoelace_area(pts: &[(f64,f64)]) -> f64 {
    if pts.len() < 3 { return 0.0; }
    let mut area = 0.0;
    for i in 0..pts.len() {
        let j = (i+1) % pts.len();
        area += pts[i].0 * pts[j].1 - pts[j].0 * pts[i].1;
    }
    area * 0.5
}

/// Parse ExtGState dash `D`: Spec §8.4.3.6 canonical is [[dashArray] phase] nested. Flat [a b c] lenient where last=phase only for len>=3 (critical fix: pure [3 3] must NOT become [3] phase 3).
pub(crate) fn parse_dash_d_array(doc: &Document, arr: &[Object]) -> (Vec<f64>, f64) {
    // Derived from the shared cap, not a local literal: Kotlin's wire decoder
    // rejects a page outright when the dash array exceeds its own bound, so the
    // parser must not allow a longer one.
    const MAX_DASH: usize = MAX_DASH_LEN;
    if arr.is_empty() {
        return (Vec::new(), 0.0);
    }
    // Canonical nested [[dashArray] phase]
    if arr.len() == 2 {
        let first_is_arr = matches!(arr[0], Object::Array(_)) || matches!(deref(doc, &arr[0]), Some(Object::Array(_)));
        if first_is_arr {
            let inner = match &arr[0] {
                Object::Array(a) => a.clone(),
                _ => deref(doc, &arr[0]).and_then(|o| o.as_array().ok()).cloned().unwrap_or_default(),
            };
            let phase = deref(doc, &arr[1]).and_then(num).or_else(|| num(&arr[1])).unwrap_or(0.0);
            let dashes: Vec<f64> = inner.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).filter(|v| *v >= 0.0).take(MAX_DASH).collect();
            return (dashes, phase);
        }
    }
    // Check for any nested arrays
    let has_nested = arr.iter().any(|o| matches!(o, Object::Array(_)) || matches!(deref(doc, o), Some(Object::Array(_))));
    let nums: Vec<f64> = arr.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).collect();
    if has_nested {
        let mut dashes = Vec::new();
        for o in arr {
            match o {
                Object::Array(inner) => {
                    dashes.extend(inner.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(|v| *v >= 0.0));
                }
                _ => {
                    if let Some(Object::Array(inner)) = deref(doc, o) {
                        dashes.extend(inner.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(|v| *v >= 0.0));
                    }
                }
            }
        }
        if dashes.is_empty() {
            dashes = nums;
        }
        return (dashes.into_iter().filter(|v| *v >= 0.0).take(MAX_DASH).collect(), 0.0);
    }
    if nums.is_empty() {
        return (Vec::new(), 0.0);
    }
    // Lenient flat [dashes..., phase] only for len>=3 to avoid [3 3] bug
    if nums.len() >= 3 {
        let phase = nums.last().copied().unwrap_or(0.0);
        let dashes: Vec<f64> = nums[..nums.len()-1].iter().copied().filter(|v| *v >= 0.0).take(MAX_DASH).collect();
        (dashes, phase)
    } else {
        (nums.into_iter().filter(|v| *v >= 0.0).take(MAX_DASH).collect(), 0.0)
    }
}


pub(crate) fn parse_dash_extgstate(doc: &Document, obj: &Object) -> (Vec<f64>, f64) {
    match deref(doc, obj).unwrap_or(obj) {
        Object::Array(arr) => parse_dash_d_array(doc, arr),
        _ => (Vec::new(), 0.0),
    }
}

pub(crate) fn interpret_page(doc: &Document, page_id: ObjectId) -> Result<PageData, String> {
    let (width, height) = page_display_size(doc, page_id);
    let base = page_base_matrix(doc, page_id);

    // `fonts_from_resources` runs on EVERY `interpret_content` call — the page,
    // every form XObject it reaches, every tiling-pattern cell and every
    // annotation appearance stream — and re-parses the whole embedded font
    // program each time. One scope per page collapses that to once per font.
    let _font_cache = crate::FontCacheScope::new();

    // §7.7.3.3: /Contents is optional, and a tokenizer failure must not lose the
    // whole page. `page_operations` returns lopdf's strict parse unchanged when it
    // succeeds and only re-tokenizes leniently when it fails, which is the
    // all-or-nothing inline-image case (§8.9.7) that used to blank a whole page.
    let (ops, recovered) = crate::content::page_operations(doc, page_id);
    if recovered && cfg!(debug_assertions) {
        eprintln!(
            "[pdf_render/interpret] page {page_id:?}: strict content parse failed, \
             recovered {} operations leniently",
            ops.len()
        );
    }
    let res = resources_dict(doc, page_id);

    let mut prims = Vec::new();
    let init = GraphicsState { ctm: base, ..Default::default() };
    // §8.7.4.1: with no clipping path, `sh` paints across the whole page, so seed
    // the clip extent with the page box. Prims are emitted in page space, so that
    // box is simply [0, 0, width, height].
    interpret_content_seeded(
        doc,
        &ops,
        res.as_ref(),
        init,
        &mut prims,
        0,
        false,
        Some([0.0, 0.0, width as f64, height as f64]),
    );
    render_annotations(doc, page_id, &base, &mut prims);

    Ok(PageData {
        width,
        height,
        prims,
    })
}

/// Interpret a content stream (`ops`) against a `resources` dictionary into
/// drawing primitives, starting from `init` graphics state. Reused for page
/// content, form XObjects (`Do`), and annotation appearance streams. `depth`
/// bounds recursion through nested form XObjects.
pub(crate) fn interpret_content(
    doc: &Document,
    ops: &[lopdf::content::Operation],
    resources: Option<&lopdf::Dictionary>,
    init: GraphicsState,
    prims: &mut Vec<Prim>,
    depth: u32,
    text_only: bool,
) {
    interpret_content_seeded(doc, ops, resources, init, prims, depth, text_only, None);
}

/// As [`interpret_content`], but seeds the initial device-space clip extent.
/// Only the page-level caller has a meaningful starting clip region (the page
/// box); nested streams start with none.
#[allow(clippy::too_many_arguments)]
pub(crate) fn interpret_content_seeded(
    doc: &Document,
    ops: &[lopdf::content::Operation],
    resources: Option<&lopdf::Dictionary>,
    init: GraphicsState,
    prims: &mut Vec<Prim>,
    depth: u32,
    text_only: bool,
    init_clip_bbox: Option<[f64; 4]>,
) {
    // `mut` for the ExtGState `/Font` arm (§8.4.5 Table 58), which references a font
    // dictionary directly instead of through a resource name and so has to register it.
    let mut fonts = resources
        .map(|r| fonts_from_resources(doc, r))
        .unwrap_or_default();
    let xobjects = resources
        .map(|r| xobjects_from_resources(doc, r))
        .unwrap_or_default();
    let extgstates = resources
        .map(|r| extgstates_from_resources(doc, r))
        .unwrap_or_default();
    let colorspaces = resources
        .map(|r| colorspaces_from_resources(doc, r))
        .unwrap_or_default();
    let shadings = resources
        .map(|r| shadings_from_resources(doc, r))
        .unwrap_or_default();
    let patterns = resources
        .map(|r| patterns_from_resources(doc, r))
        .unwrap_or_default();

    let mut gs = init;
    // Pattern matrices are relative to the coordinate system in effect when this
    // content stream begins (the page default CTM, or the form's CTM).
    let pattern_base_ctm = gs.ctm;
    #[derive(Clone)]
    struct SavedState {
        gs: GraphicsState,
        clip_depth: usize,
        group_depth: usize,
        clip_bbox: Option<[f64; 4]>,
    }
    let mut stack: Vec<SavedState> = Vec::new();
    let mut q_overflow: usize = 0;

    struct PendingClip {
        even_odd: bool,
        polys: Vec<Vec<(f64,f64)>>,
        path_ops: Vec<PathOp>,
    }

    // single ClipPush per W op preserving holes via full path_ops (fix high #7).
    // Also intersects the new clip's device-space bbox into `clip_bbox` so that
    // later `sh` operators know the current clip region even after `W n` commits.
    #[inline]
    fn emit_one_clip(prims: &mut Vec<Prim>, pc: PendingClip, clip_depth: &mut usize, clip_bbox: &mut Option<[f64;4]>, text_only: bool, oc_hidden: bool) {
        if text_only || oc_hidden { return; }
        if *clip_depth >= MAX_CLIP_DEPTH { return; }
        if pc.polys.is_empty() && pc.path_ops.is_empty() { return; }
        // Intersect the accumulated clip bbox with this clip's device bbox.
        let mut nx0 = f64::INFINITY; let mut ny0 = f64::INFINITY;
        let mut nx1 = f64::NEG_INFINITY; let mut ny1 = f64::NEG_INFINITY;
        for poly in &pc.polys {
            for &(x, y) in poly {
                nx0 = nx0.min(x); ny0 = ny0.min(y);
                nx1 = nx1.max(x); ny1 = ny1.max(y);
            }
        }
        if nx1 > nx0 && ny1 > ny0 {
            *clip_bbox = Some(match *clip_bbox {
                Some(cur) => [cur[0].max(nx0), cur[1].max(ny0), cur[2].min(nx1), cur[3].min(ny1)],
                None => [nx0, ny0, nx1, ny1],
            });
        }
        let pts: Vec<(f32,f32)> = pc.polys.first().map(|p| p.iter().map(|&(x,y)| (x as f32, y as f32)).collect()).unwrap_or_default();
        let po = if pc.path_ops.is_empty() { None } else { Some(pc.path_ops) };
        prims.push(Prim::ClipPush { even_odd: pc.even_odd, pts, path_ops: po });
        *clip_depth += 1;
    }

    let mut text_matrix = IDENTITY;
    let mut line_matrix = IDENTITY;

    let mut subpaths: Vec<Vec<(f64, f64)>> = Vec::new();
    let mut cur_user: (f64, f64) = (0.0, 0.0);
    let mut start_user: (f64, f64) = (0.0, 0.0);
    // Marked-content stack, one entry per BMC/BDC: true means content in this
    // frame is suppressed by optional content. §14.6 requires 1:1 nesting, so a
    // frame is pushed for EVERY BMC/BDC even when visibility is unchanged.
    let mut oc_stack: Vec<bool> = Vec::new();
    // BMC/BDC pushes dropped at MAX_OC_STACK, so EMC can discard the matching
    // pop instead of popping a frame it does not own (which un-hid content).
    let mut oc_overflow: usize = 0;
    let mut group_depth: usize = 0;
    let mut pending_clip: Option<PendingClip> = None;
    // §11.6.5.1: the soft mask is a graphics-state parameter, not a per-operator
    // one. Tracks the last bracket emitted so a run of paints under the same mask
    // expands the mask group once instead of once per painting operator.
    let mut mask_bracket: Option<MaskBracket> = None;
    // Memo for optional-content answers within this stream. Distinct from
    // `oc_config` below: that holds the /ON and /OFF membership sets, while this
    // memoizes the work AROUND a lookup — resolving a /Properties resource NAME to
    // its OCG, and evaluating an OCMD's /OCGs + /P policy — neither of which the
    // membership sets cover. A layer is opened and closed many times per page, and
    // within one content stream the /Properties resource is fixed, so each distinct
    // property list resolves to the same answer every time.
    //
    // Deliberately NOT shared with nested streams, even though a nested form
    // starts with an empty memo and pays the re-read again. `OcKey::Named` is a
    // /Properties resource NAME, and resource dictionaries are per-stream: `/P1`
    // in a form's /Properties may name a different OCG than the page's `/P1`, so
    // a shared memo would answer the wrong question. Only `OcKey::Ref` is
    // stream-independent, and splitting the memo to share half of it buys a
    // dictionary lookup on a path that is already off the hot loop.
    #[derive(PartialEq, Eq, Hash)]
    enum OcKey {
        Named(Vec<u8>),
        Ref(ObjectId),
    }
    let mut oc_cache: HashMap<OcKey, bool> = HashMap::new();
    // The `/ON` and `/OFF` membership sets, built AT MOST ONCE per content stream and
    // only if an optional-content lookup actually happens.
    //
    // Held rather than resolved per call because `OcConfig::from_doc` builds both
    // HashSets from the catalog, which is O(N) in the document's OCG count. There used
    // to be a free `oc_object_hidden(doc, obj)` wrapper in images.rs that called it
    // internally, and these call sites were on it: that silently threw away a measured
    // 8.8x speedup (`bench`: 111 ms -> 12.6 ms over 1600 distinct OCGs) by relocating
    // the same O(N) work out of four `clone()`s and into two set builds — identical
    // asymptotics, no win. That wrapper has since been DELETED precisely so the trap
    // cannot be re-entered; `OcConfig::object_hidden` is now the only way in, and it
    // requires you to have hoisted the config first.
    //
    // Lazy rather than eager because the overwhelming majority of content streams
    // contain no optional content at all, and this runs for every form XObject, every
    // tiling-pattern cell replay and every soft-mask group — building it unconditionally
    // would turn a measured win into a per-stream tax. `oc_cache` above still earns its
    // keep: it memoizes the /Properties NAME resolution and the OCMD `/OCGs` + `/P`
    // evaluation, neither of which the membership sets cover.
    let mut oc_config: Option<OcConfig> = None;
    macro_rules! oc_hidden {
        ($obj:expr) => {{
            let cfg = oc_config.get_or_insert_with(|| OcConfig::from_doc(doc));
            cfg.object_hidden(doc, $obj)
        }};
    }
    // Device-space bbox of the accumulated (committed) clip region, tracked so the
    // `sh` operator can fill the current clip even after `W n` clears pending_clip.
    let mut current_clip_bbox: Option<[f64; 4]> = init_clip_bbox;
    let mut clip_depth: usize = 0;
    let mut clip_path_ops: Vec<PathOp> = Vec::new(); // current clip path ops before W
    // Whether the current text object (BT..ET) used a clip render mode (Tr 4-7).
    let mut text_clip_used = false;

    let dev = |gs: &GraphicsState, x: f64, y: f64| transform(&gs.ctm, x, y);

    for op in ops.iter().take(MAX_CONTENT_OPS) {
        let o = &op.operands;
        match op.operator.as_str() {
            "q" => {
                if stack.len() < MAX_GRAPHICS_STACK_HARD {
                    stack.push(SavedState { gs: gs.clone(), clip_depth, group_depth, clip_bbox: current_clip_bbox });
                } else {
                    q_overflow += 1;
                }
            }
            "Q" => {
                if q_overflow > 0 {
                    q_overflow -= 1;
                } else if let Some(saved) = stack.pop() {
                    while clip_depth > saved.clip_depth {
                        if !text_only { prims.push(Prim::ClipPop); }
                        clip_depth = clip_depth.saturating_sub(1);
                    }
                    while group_depth > saved.group_depth {
                        if !text_only { prims.push(Prim::GroupPop); }
                        group_depth = group_depth.saturating_sub(1);
                    }
                    current_clip_bbox = saved.clip_bbox;
                    gs = saved.gs;
                    // A pending `W` belongs to the path being built inside this
                    // q/Q pair; it must not survive to clip later content.
                    pending_clip = None;
                }
            }
            "cm" => {
                if let Some(m) = read_matrix(o) {
                    // A non-finite CTM poisons every coordinate derived from it,
                    // which makes whole regions of the page silently disappear.
                    let next = mat_mul(&m, &gs.ctm);
                    if next.iter().all(|v| v.is_finite()) {
                        gs.ctm = next;
                    }
                }
            }
            "w" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.line_width = v; }
            }
            "J" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.line_cap = (v as i64).clamp(0,2) as u8; }
            }
            "j" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.line_join = (v as i64).clamp(0,2) as u8; }
            }
            "M" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.miter_limit = v; }
            }
            "i" => {
                if let Some(v) = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))) { gs.flatness = v.clamp(0.0, 100.0); }
            }
            "d" => {
                let dash_obj = o.first().and_then(|x| deref(doc, x).or(Some(x)));
                let mut dashes = if let Some(Object::Array(arr)) = dash_obj {
                    arr.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).filter(|v| *v >= 0.0).take(MAX_DASH_LEN).collect()
                } else { Vec::new() };
                if dashes.len() % 2 == 1 && !dashes.is_empty() { let cl = dashes.clone(); dashes.extend(cl); if dashes.len() > MAX_DASH_LEN { dashes.truncate(MAX_DASH_LEN); } }
                gs.dash = dashes;
                gs.dash_phase = o.get(1).and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x))).unwrap_or(0.0);
            }
            "gs" => {
                if let Some(Object::Name(name)) = o.first() {
                    let inline_dict = resources.and_then(|r| {
                        r.get(b"ExtGState").ok()
                            .and_then(|o| deref(doc, o))
                            .and_then(|o| o.as_dict().ok())
                            .and_then(|d| d.get(name).ok())
                            .and_then(|o| deref(doc, o))
                            .and_then(|o| o.as_dict().ok())
                    });
                    // Helper to apply a dict to gs
                    let apply_dict = |dict: &lopdf::Dictionary, gs: &mut GraphicsState, doc: &Document| {
                        // ISO 32000: /CA is the stroking alpha, /ca is the nonstroking (fill) alpha.
                        if let Some(v) = dict.get(b"CA").ok().and_then(num) {
                            gs.alpha_stroke = v.clamp(0.0,1.0);
                        }
                        if let Some(v) = dict.get(b"ca").ok().and_then(num) {
                            gs.alpha_fill = v.clamp(0.0,1.0);
                        }
                        if let Some(v) = dict.get(b"LW").ok().and_then(num) {
                            gs.line_width = v;
                        }
                        if let Some(v) = dict.get(b"LC").ok().and_then(num) {
                            gs.line_cap = (v as i64).clamp(0,2) as u8;
                        }
                        if let Some(v) = dict.get(b"LJ").ok().and_then(num) {
                            gs.line_join = (v as i64).clamp(0,2) as u8;
                        }
                        if let Some(v) = dict.get(b"ML").ok().and_then(num) {
                            gs.miter_limit = v;
                        }
                        // §8.4.5 Table 58 `/FL` is the flatness tolerance, i.e. the
                        // graphics-state form of the `i` operator (§10.6.2), and it is
                        // what `bezier_steps_for_flatness` consumes. It was the one
                        // Table 58 key with existing plumbing and no parser, so a
                        // document that set flatness via `gs` instead of `i` got the
                        // default tolerance and visibly faceted large curves. The clamp
                        // is the `i` arm's; the operand is read WITHOUT `deref`, matching
                        // every other Table 58 scalar here rather than the `i` arm.
                        if let Some(v) = dict.get(b"FL").ok().and_then(num) {
                            gs.flatness = v.clamp(0.0, 100.0);
                        }
                        if let Some(d_obj) = dict.get(b"D").ok().and_then(|obj| deref(doc, obj).or(Some(obj))) {
                            // P1 fix: /D [] 0 must reset to solid (was previously ignored)
                            let (dashes, phase) = parse_dash_extgstate(doc, d_obj);
                            gs.dash = dashes;
                            gs.dash_phase = phase;
                        }
                        if let Some(bm_obj) = dict.get(b"BM").ok().and_then(|obj| deref(doc, obj).or(Some(obj))) {
                            if let Ok(n) = bm_obj.as_name() {
                                gs.blend_mode = BlendMode::from_name(n);
                            } else if let Ok(arr) = bm_obj.as_array() {
                                // BM can be array, take first that is not Normal
                                for el in arr {
                                    if let Ok(n) = el.as_name() {
                                        let bm = BlendMode::from_name(n);
                                        if bm != BlendMode::Normal {
                                            gs.blend_mode = bm;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        // ---------------------------------------------------------
                        // Table 58 keys that are DELIBERATELY not parsed. Recorded
                        // here so the next audit can tell a decision from an
                        // oversight, and because for several of them a partial
                        // simulation is measurably WORSE than ignoring them — the
                        // lesson of the overprint approximation that had to be
                        // removed, where `white MULTIPLY dst == dst` turned white
                        // knockout rectangles into no-ops and let covered content
                        // reappear.
                        //
                        // /OP /op /OPM — §8.6.7 scopes overprint to devices with
                        //   separable colorants. An additive RGB compositor has none,
                        //   so there is nothing to record and no consumer for it.
                        // /TR /TR2 — §10.4. A transfer function maps DEVICE colour
                        //   components after conversion into the device colour space,
                        //   which is Clause 10 "Rendering", i.e. device-dependent
                        //   calibration for a specific press. It is not the same
                        //   parameter as the soft-mask /TR of §11.6.5.2, which is a
                        //   mask-SHAPE function, is device-independent, and IS
                        //   implemented (see the /SMask arm and `read_transfer_lut`).
                        //   Honouring this one would also have to be all-or-nothing:
                        //   fill/stroke/text colours are computed here and could be
                        //   remapped, but a DCTDecode image is passed through to the
                        //   renderer as JPEG bytes and cannot be, so an inverting /TR
                        //   would invert the vector layer and leave the photographs
                        //   alone. A uniformly wrong page beats a half-inverted one.
                        // /HT — §10.5 halftones. A halftone screen exists to render
                        //   continuous tone on a bilevel device; the output here is
                        //   8-bit-per-channel antialiased RGB, which represents the
                        //   requested tone directly. Applying a screen could only
                        //   throw tonal resolution away.
                        // /BG /BG2 /UCR /UCR2 — §10.3 black generation and undercolour
                        //   removal, defined only for the DeviceGray -> DeviceCMYK
                        //   conversion. Nothing here converts to CMYK.
                        // /RI — §8.6.5.8 rendering intent. Selects a gamut-mapping
                        //   policy for an ICC transform; colour here goes through each
                        //   space's defining formulae rather than an ICC engine, so
                        //   there is no transform for it to parameterise. The `ri`
                        //   operator is a documented no-op for the same reason.
                        // /SM — §10.6.3 smoothness tolerance: a shading-quality hint,
                        //   with the raster resolution already derived from the device
                        //   footprint of the clip region.
                        // /SA — §10.6 automatic stroke adjustment: quantises stroke
                        //   edges to the pixel grid for crispness at low resolution.
                        //   That is the renderer's own scan conversion, not something
                        //   expressible in the primitive stream.
                        // /TK — §9.3.8 text knockout, and /AIS — §11.6.4.3 alpha-is-
                        //   shape. Both change how overlapping marks composite INSIDE
                        //   one text object / group. The primitive stream composites
                        //   marks in order against the running result, and neither
                        //   alternative is expressible with Canvas layers (the same
                        //   reason /I and /K on a transparency group are approximated
                        //   as isolated non-knockout). Faking either would change the
                        //   common case to fix the rare one.
                        // ---------------------------------------------------------
                        // Soft mask: /SMask /None clears; dict may have /G as Ref OR direct Stream (P0 fix)
                        if let Ok(sm_raw) = dict.get(b"SMask") {
                            if let Ok(n) = sm_raw.as_name() {
                                if n == b"None" { gs.soft_mask = None; }
                            } else if let Some(sm) = deref(doc, sm_raw).or(Some(sm_raw)) {
                                if let Ok(smdict) = sm.as_dict() {
                                    let mask_type = match smdict.get(b"S").ok().and_then(|o| o.as_name().ok()) {
                                        Some(b"Luminosity") => 1u8,
                                        _ => 0u8,
                                    };
                                    let backdrop = smdict.get(b"BC").ok()
                                        .and_then(|o| deref(doc, o))
                                        .and_then(|o| o.as_array().ok())
                                        .map(|a| a.iter().filter_map(num).collect::<Vec<f64>>())
                                        .filter(|v| !v.is_empty());
                                    // /G can be Ref or direct Stream/Dict — handle both
                                    let gid_opt = smdict.get(b"G").ok().and_then(|g| {
                                        if let Object::Reference(id) = g { Some(*id) }
                                        else if let Some(Object::Reference(id)) = deref(doc, g) {
                                            match deref(doc, g) { Some(Object::Reference(_)) => Some(*id), _ => g.as_reference().ok() }
                                        } else { g.as_reference().ok() }
                                    });
                                    if let Some(gid) = gid_opt {
                                        // §11.6.5.2: the mask value passes through /TR
                                        // before use. `read_transfer_lut` returns None for
                                        // /Identity and for anything within one 8-bit step
                                        // of it, so the common case costs nothing.
                                        // (imaging owns functions.rs/graphics_state.rs;
                                        // this line only populates the new field.)
                                        let tr = smdict
                                            .get(b"TR")
                                            .ok()
                                            .and_then(|o| functions::read_transfer_lut(doc, o));
                                        gs.soft_mask = Some(SoftMask { group_id: gid, mask_type, ctm: gs.ctm, backdrop, tr });
                                    }
                                }
                            }
                        }
                    };
                    let chosen: Option<lopdf::Dictionary> = if let Some(&id) = extgstates.get(name) {
                        // Cloned rather than borrowed: `apply_dict` needs `&mut gs`
                        // while `doc` is still borrowed by the dictionary.
                        doc.get_dictionary(id).ok().cloned()
                    } else {
                        inline_dict.cloned()
                    };
                    if let Some(dict_clone) = chosen {
                        apply_dict(&dict_clone, &mut gs, doc);
                        // §8.4.5 Table 58 `/Font` is `[font size]`, where `font` is an
                        // INDIRECT REFERENCE to a font dictionary rather than a
                        // resource name — it is the graphics-state equivalent of `Tf`
                        // and is saved and restored by q/Q like the rest of the text
                        // state (§9.3.1). Unparsed, `gs.font_key` stayed empty and
                        // `show_string` fell through to its no-metrics branch: the run
                        // was emitted as ONE primitive at the origin with a guessed
                        // 0.5-em-per-byte advance and the bytes read as Latin-1, so the
                        // text appeared but at the wrong place, spacing and encoding.
                        //
                        // The font is registered under a key derived from its object id
                        // rather than a resource name, because it deliberately has no
                        // name: `/Font` exists precisely to reference a font that the
                        // resource dictionary need not list.
                        //
                        // Two known limits of that, neither a regression on the
                        // no-metrics branch this replaces. The registration bypasses
                        // fonts.rs's `FontCacheScope` (its cache helpers are private to
                        // that module), so a `/Font` inside a stream that is REPLAYED —
                        // a tiling-pattern cell — re-parses the font program per
                        // replay. And a nested stream rebuilds `fonts` from its own
                        // resources, which cannot contain this key, so a form XObject
                        // that shows text under an INHERITED `/Font` selection still
                        // falls through to the no-metrics branch — exactly as it
                        // already does for an inherited `Tf` resource name.
                        if let Some(farr) = dict_clone
                            .get(b"Font")
                            .ok()
                            .and_then(|o| deref(doc, o))
                            .and_then(|o| o.as_array().ok())
                        {
                            if let Some(fid) = farr.first().and_then(|o| o.as_reference().ok()) {
                                let key = format!("\u{0}gsfont{}_{}", fid.0, fid.1).into_bytes();
                                if !fonts.contains_key(&key) {
                                    if let Ok(Object::Dictionary(fd)) = doc.get_object(fid) {
                                        let fd = fd.clone();
                                        fonts.insert(key.clone(), font_info(doc, &fd));
                                    }
                                }
                                // Only adopt it once there really are metrics behind
                                // the key: a dangling reference must leave whatever
                                // `Tf` last selected alone rather than blanking it.
                                if fonts.contains_key(&key) {
                                    gs.font_key = key;
                                    if let Some(sz) = farr.get(1).and_then(|o| deref(doc, o).and_then(num).or_else(|| num(o))) {
                                        gs.font_size = sz;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "W" => {
                // P0 fix: emit as single ClipPush preserving holes via path_ops (was per-poly loop)
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                pending_clip = Some(PendingClip { even_odd: false, polys: subpaths.clone(), path_ops: clip_path_ops.clone() });
            }
            "W*" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                pending_clip = Some(PendingClip { even_odd: true, polys: subpaths.clone(), path_ops: clip_path_ops.clone() });
            }
            "m" => {
                let xn = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                let yn = o.get(1).and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                if let (Some(x), Some(y)) = (xn, yn) {
                    cur_user = (x, y);
                    start_user = (x, y);
                    let (dx, dy) = dev(&gs, x, y);
                    if subpaths.len() < MAX_SUBPATHS {
                        subpaths.push(vec![(dx, dy)]);
                        clip_path_ops.push(PathOp::Move(dx as f32, dy as f32));
                    }
                }
            }
            "l" => {
                let xn = o.first().and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                let yn = o.get(1).and_then(|x| deref(doc, x).and_then(num).or_else(|| num(x)));
                if let (Some(x), Some(y)) = (xn, yn) {
                    // §8.5.2.1: `l` with no current point is an error. Fabricating a
                    // subpath here desynchronised `subpaths` from `clip_path_ops`,
                    // and an unmatched Line makes Android's Path start at (0,0).
                    if subpaths.is_empty() { continue; }
                    cur_user = (x, y);
                    let (dx, dy) = dev(&gs, x, y);
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push((dx, dy));
                    }
                    clip_path_ops.push(PathOp::Line(dx as f32, dy as f32));
                }
            }
            "c" | "v" | "y" => {
                let nums: Vec<f64> = o.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).collect();
                let (p1, p2, p3) = match op.operator.as_str() {
                    "c" if nums.len() == 6 => (
                        (nums[0], nums[1]),
                        (nums[2], nums[3]),
                        (nums[4], nums[5]),
                    ),
                    "v" if nums.len() == 4 => {
                        (cur_user, (nums[0], nums[1]), (nums[2], nums[3]))
                    }
                    "y" if nums.len() == 4 => {
                        ((nums[0], nums[1]), (nums[2], nums[3]), (nums[2], nums[3]))
                    }
                    _ => continue,
                };
                let p0 = cur_user;
                // Same rule as `l`: a curve with no current point is a no-op.
                if subpaths.is_empty() { continue; }
                let (d0x, d0y) = dev(&gs, p0.0, p0.1);
                let (c1x, c1y) = dev(&gs, p1.0, p1.1);
                let (c2x, c2y) = dev(&gs, p2.0, p2.1);
                let (c3x, c3y) = dev(&gs, p3.0, p3.1);
                let bez_steps = bezier_steps_for_flatness(
                    [(d0x, d0y), (c1x, c1y), (c2x, c2y), (c3x, c3y)],
                    gs.flatness,
                );
                for step in 1..=bez_steps {
                    let t = step as f64 / bez_steps as f64;
                    let (bx, by) = cubic_bezier(p0, p1, p2, p3, t);
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, bx, by));
                    }
                }
                cur_user = p3;
                // Record the exact cubic (device space) for bezier-retentive clips.
                clip_path_ops.push(PathOp::Cubic(c1x as f32, c1y as f32, c2x as f32, c2y as f32, c3x as f32, c3y as f32));
            }
            "re" => {
                let nums: Vec<f64> = o.iter().filter_map(|x| deref(doc, x).and_then(num).or_else(|| num(x))).collect();
                if nums.len() == 4 {
                    let (x, y, w, h) = (nums[0], nums[1], nums[2], nums[3]);
                    let rect = vec![
                        dev(&gs, x, y),
                        dev(&gs, x + w, y),
                        dev(&gs, x + w, y + h),
                        dev(&gs, x, y + h),
                        dev(&gs, x, y),
                    ];
                    if subpaths.len() >= MAX_SUBPATHS { continue; }
                    subpaths.push(rect);
                    let (mx, my) = dev(&gs, x, y);
                    let (x1, y1d) = dev(&gs, x + w, y);
                    let (x2, y2d) = dev(&gs, x + w, y + h);
                    let (x3, y3d) = dev(&gs, x, y + h);
                    clip_path_ops.push(PathOp::Move(mx as f32, my as f32));
                    clip_path_ops.push(PathOp::Line(x1 as f32, y1d as f32));
                    clip_path_ops.push(PathOp::Line(x2 as f32, y2d as f32));
                    clip_path_ops.push(PathOp::Line(x3 as f32, y3d as f32));
                    clip_path_ops.push(PathOp::Close);
                    cur_user = (x, y);
                    start_user = (x, y);
                }
            }
            "h" => {
                // P0 fix: avoid duplicate close point causing zero-length segment
                if let Some(sp) = subpaths.last_mut() {
                    let (sx, sy) = dev(&gs, start_user.0, start_user.1);
                    let skip_dup = sp.last().map(|&(px, py)| (px-sx).abs() < 1e-6 && (py-sy).abs() < 1e-6).unwrap_or(false);
                    if !skip_dup { sp.push((sx, sy)); }
                }
                clip_path_ops.push(PathOp::Close);
                cur_user = start_user;
            }
            "S" | "s" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                if op.operator == "s" {
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, start_user.0, start_user.1));
                    }
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.stroke_pattern {
                        paint_pattern_stroke(doc, pid, &subpaths, &gs, &pattern_base_ctm, prims, depth, clip_depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_stroke(prims, &subpaths, &gs);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket);
                    }
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "f" | "F" | "f*" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.fill_pattern {
                        paint_pattern_fill(doc, pid, &subpaths, op.operator == "f*", &pattern_base_ctm, gs.fill, gs.alpha_fill as f32, gs.blend_mode, prims, depth, clip_depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_fill(prims, &subpaths, gs.fill, op.operator == "f*", gs.alpha_fill, gs.blend_mode);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket);
                    }
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "B" | "B*" | "b" | "b*" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                if op.operator.starts_with('b') {
                    if let Some(sp) = subpaths.last_mut() {
                        sp.push(dev(&gs, start_user.0, start_user.1));
                    }
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    let sm_start = prims.len();
                    if let Some(pid) = gs.fill_pattern {
                        paint_pattern_fill(doc, pid, &subpaths, op.operator.ends_with('*'), &pattern_base_ctm, gs.fill, gs.alpha_fill as f32, gs.blend_mode, prims, depth, clip_depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_fill(prims, &subpaths, gs.fill, op.operator.ends_with('*'), gs.alpha_fill, gs.blend_mode);
                    }
                    if let Some(pid) = gs.stroke_pattern {
                        paint_pattern_stroke(doc, pid, &subpaths, &gs, &pattern_base_ctm, prims, depth, clip_depth);
                    } else if prims.len() < MAX_PRIMITIVES {
                        emit_stroke(prims, &subpaths, &gs);
                    }
                    if let Some(m) = gs.soft_mask.clone() {
                        wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket);
                    }
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "n" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                subpaths.clear(); clip_path_ops.clear();
            }
            "BI" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                    if let Some(Object::Stream(stream)) = o.first() {
                        if let Some(img) = extract_inline_image(doc, stream, gs.fill, &colorspaces) {
                            let sm_start = prims.len();
                            if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: gs.alpha_fill as f32, blend: gs.blend_mode }); }
                            if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket); }
                        }
                    }
                }
            }
            "Do" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                if let Some(Object::Name(name)) = o.first() {
                    if let Some(&id) = xobjects.get(name) {
                        if let Ok(Object::Stream(stream)) = doc.get_object(id) {
                            // Skip the whole XObject if its optional-content group is OFF.
                            if stream.dict.get(b"OC").ok().map(|oc| oc_hidden!(oc)).unwrap_or(false) {
                                continue;
                            }
                            let subtype = stream
                                .dict
                                .get(b"Subtype")
                                .ok()
                                .and_then(|o| o.as_name().ok());
                            if subtype == Some(b"Image") {
                                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                                    if let Some(img) = extract_image(doc, stream, gs.fill, &colorspaces) {
                                        let sm_start = prims.len();
                                        if prims.len() < MAX_PRIMITIVES { prims.push(Prim::Image { ctm: gs.ctm, w: img.w, h: img.h, format: img.format, data: img.data, alpha: gs.alpha_fill as f32, blend: gs.blend_mode }); }
                                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket); }
                                    }
                                }
                            } else if subtype == Some(b"Form")
                                && depth < 10
                                // §8.11.2: content in a disabled optional-content group
                                // shall not be drawn. The Image branch above is gated on
                                // this; the form recursion was NOT, and `oc_stack` is a
                                // local that no nested stream inherits, so
                                // `/OC1 BDC /Fm0 Do EMC` with OC1 OFF painted the form's
                                // ENTIRE contents. Worse, it painted them UNCLIPPED,
                                // because the `/BBox` ClipPush and the `GroupPush` below
                                // are both gated on this same flag while the recursion
                                // that needed them was not.
                                //
                                // `text_only` deliberately still descends. That caller is
                                // `search::build_index`, and the policy set in the `Tj`
                                // arm is that an OC-hidden layer is hidden, not absent:
                                // its text has to stay searchable. Suppressing ink and
                                // keeping the index is exactly what mode 3 achieves there.
                                && !(oc_stack.last().copied().unwrap_or(false) && !text_only)
                            {
                                let form_matrix = stream
                                    .dict
                                    .get(b"Matrix")
                                    .ok()
                                    .and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o)))
                                    .unwrap_or(IDENTITY);
                                let form_res = stream
                                    .dict
                                    .get(b"Resources")
                                    .ok()
                                    .and_then(|o| deref(doc, o))
                                    .and_then(|o| o.as_dict().ok())
                                    .cloned();
                                // Transparency group detection per Phase 4: /Group << /S /Transparency /I bool /K bool >>
                                let (is_transparency_group, isolated, knockout) = {
                                    if let Some(Object::Dictionary(gdict)) = stream.dict.get(b"Group").ok().and_then(|o| deref(doc,o).or(Some(o))).cloned() {
                                        let s = gdict.get(b"S").ok().and_then(|o| o.as_name().ok());
                                        if s == Some(b"Transparency") {
                                            let i = gdict.get(b"I").ok().and_then(|o| match o { Object::Boolean(b) => Some(*b), _=> None }).unwrap_or(false);
                                            let k = gdict.get(b"K").ok().and_then(|o| match o { Object::Boolean(b) => Some(*b), _=> None }).unwrap_or(false);
                                            (true, i, k)
                                        } else { (false,false,false) }
                                    } else { (false,false,false) }
                                };
                                // ExtGState soft mask active at this Do: bracket
                                // the form as the masked content and emit the /G
                                // group as the mask.
                                let active_smask = gs.soft_mask.clone();
                                let use_smask = active_smask.is_some()
                                    && !text_only
                                    && !oc_stack.last().copied().unwrap_or(false)
                                    && prims.len() < crate::MAX_PRIMITIVES
                                    && depth < MAX_GROUP_DEPTH;
                                let sm_start = prims.len();
                                // The group push is NOT mutually exclusive with the
                                // soft-mask bracket. Making it so silently disabled the
                                // §11.6.6 alpha reset below, which is gated on
                                // `pushed_group`: a form carrying BOTH `/ca` < 1 and an
                                // `/SMask` then applied `ca` to every element inside
                                // instead of once to the composited group, over-darkening
                                // wherever that content overlaps itself.
                                //
                                // Both fit because `sm_start` is captured ABOVE the push,
                                // so `wrap_with_soft_mask` inserts `SoftMaskPush` outside
                                // the group: the group composites (applying `ca` once)
                                // inside the masked layer, then the mask applies to that
                                // result, which is the §11.6.5.1 order.
                                let should_emit_group = is_transparency_group && !text_only && !oc_stack.last().copied().unwrap_or(false) && depth < MAX_GROUP_DEPTH;
                                let pushed_group = should_emit_group
                                    && prims.len() < crate::MAX_PRIMITIVES
                                    && group_depth < 32;
                                if pushed_group {
                                    // The nonstroking constant alpha (ca) applies to the
                                    // group as a whole when it is painted; NOT ca*CA.
                                    prims.push(Prim::GroupPush { isolated, knockout, alpha: gs.alpha_fill as f32, blend: gs.blend_mode });
                                    group_depth+=1;
                                }
                                // Form content shall be clipped to /BBox (transformed by
                                // /Matrix), per PDF 8.10.1, so it can't bleed past its box.
                                let form_ctm = mat_mul(&form_matrix, &gs.ctm);
                                // §8.7.4.1 requires `sh` to cover the ENTIRE current
                                // clipping region, so `rasterize_shading` refuses to guess:
                                // handed no clip extent, a shading with no `/BBox` of its own
                                // paints NOTHING at all. Only the page-level caller seeded
                                // that extent, and every nested stream reaches the
                                // interpreter through `interpret_content`, which passes
                                // `None` — so `/Sh sh` inside a form XObject silently
                                // vanished, which is one of the few ways a fully-implemented
                                // operator can still render nothing.
                                //
                                // §8.10.1 clips a form's content to `/BBox` transformed by
                                // `/Matrix`, so that box — intersected with the clip already
                                // in force — IS the region a nested `sh` has to fill. It is
                                // the same quantity the `ClipPush` below is built from.
                                let form_clip_bbox = {
                                    let own = stream
                                        .dict
                                        .get(b"BBox")
                                        .ok()
                                        .and_then(|o| read_rect(doc, o))
                                        .and_then(|bb| {
                                            quad_device_bbox(&[
                                                transform(&form_ctm, bb[0], bb[1]),
                                                transform(&form_ctm, bb[2], bb[1]),
                                                transform(&form_ctm, bb[2], bb[3]),
                                                transform(&form_ctm, bb[0], bb[3]),
                                            ])
                                        });
                                    // No `/BBox` (malformed, §8.10.2 makes it required) means
                                    // no extra bound, not an empty one: inherit the caller's.
                                    match (own, current_clip_bbox) {
                                        (Some(b), Some(cur)) => Some([
                                            b[0].max(cur[0]),
                                            b[1].max(cur[1]),
                                            b[2].min(cur[2]),
                                            b[3].min(cur[3]),
                                        ]),
                                        (Some(b), None) => Some(b),
                                        (None, cur) => cur,
                                    }
                                    // The intersection itself can come out empty.
                                    .filter(|b| b[2] > b[0] && b[3] > b[1])
                                };
                                let mut bbox_clipped = false;
                                if !text_only && !oc_stack.last().copied().unwrap_or(false) {
                                    if let Some(bb) = stream.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
                                        if prims.len() < MAX_PRIMITIVES {
                                            let c = [
                                                transform(&form_ctm, bb[0], bb[1]),
                                                transform(&form_ctm, bb[2], bb[1]),
                                                transform(&form_ctm, bb[2], bb[3]),
                                                transform(&form_ctm, bb[0], bb[3]),
                                            ];
                                            let pts: Vec<(f32, f32)> = c.iter().map(|&(x, y)| (x as f32, y as f32)).collect();
                                            let po = vec![
                                                PathOp::Move(c[0].0 as f32, c[0].1 as f32),
                                                PathOp::Line(c[1].0 as f32, c[1].1 as f32),
                                                PathOp::Line(c[2].0 as f32, c[2].1 as f32),
                                                PathOp::Line(c[3].0 as f32, c[3].1 as f32),
                                                PathOp::Close,
                                            ];
                                            prims.push(Prim::ClipPush { even_odd: false, pts, path_ops: Some(po) });
                                            bbox_clipped = true;
                                        }
                                    }
                                }
                                // §8.9.7: lopdf wraps inline-image parsing in nom
                                // `cut(...)`, so ONE inline image it cannot handle
                                // failed this whole form and blanked it, exactly as it
                                // used to blank a whole page. `stream_operations`
                                // returns lopdf's result untouched when it succeeds, so
                                // a form that renders today is unaffected.
                                let sub_ops = crate::content::stream_operations(doc, stream);
                                if !sub_ops.is_empty() {
                                        let mut sub_gs = gs.clone();
                                        sub_gs.ctm = form_ctm;
                                        // A soft mask applies once, to this form as a
                                        // whole. Only clear it when the wrap actually
                                        // happened; otherwise the mask must stay in the
                                        // state and be applied per element inside, since
                                        // §11.6.5.1 makes it inherited state that cannot
                                        // silently vanish.
                                        if use_smask { sub_gs.soft_mask = None; }
                                        // Per PDF 11.6.6: on entering a transparency group the
                                        // alpha constants reset to 1.0 and blend to Normal — they
                                        // are applied when the group's result is composited (via
                                        // GroupPush), not again to each element inside. Without
                                        // this, ca is double-applied and low-alpha groups vanish.
                                        //
                                        // Deliberately still gated on `pushed_group`, so when
                                        // `MAX_PRIMITIVES` or the group-depth cap demotes the push
                                        // the alpha keeps being applied per element. There is no
                                        // composite to apply it to once in that case, and resetting
                                        // anyway would drop `ca` entirely and paint the form fully
                                        // opaque — wrong for all content, where per-element is
                                        // wrong only where content overlaps itself.
                                        if pushed_group {
                                            sub_gs.alpha_fill = 1.0;
                                            sub_gs.alpha_stroke = 1.0;
                                            sub_gs.blend_mode = BlendMode::Normal;
                                        }
                                        let res_ref = form_res.as_ref().or(resources);
                                        interpret_content_seeded(
                                            doc,
                                            &sub_ops,
                                            res_ref,
                                            sub_gs,
                                            prims,
                                            depth + 1,
                                            text_only,
                                            form_clip_bbox,
                                        );
                                }
                                if bbox_clipped {
                                    // Always balance the ClipPush, even if the prim cap
                                    // was hit inside the form, to keep the clip stack sane.
                                    prims.push(Prim::ClipPop);
                                }
                                // §8.10.1: `Do` on a form behaves as `q … Q`, so the
                                // group must be composited here rather than being left
                                // open until the next `Q` (or end of stream), which let
                                // its alpha and blend mode leak onto later content.
                                if pushed_group {
                                    prims.push(Prim::GroupPop);
                                    group_depth -= 1;
                                }
                                // Bracket the whole form as the masked content, then
                                // append the mask group (rendered at the mask's set-time CTM).
                                if use_smask {
                                    if let Some(mask) = active_smask {
                                        wrap_with_soft_mask(prims, sm_start, doc, resources, &mask, depth, &mut mask_bracket);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "rg" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 3 {
                    gs.fill = rgb_to_argb(n[0], n[1], n[2]);
                    gs.non_stroke_cs = CsKind::DeviceRGB;
                    gs.fill_pattern = None;
                }
            }
            "RG" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 3 {
                    gs.stroke = rgb_to_argb(n[0], n[1], n[2]);
                    gs.stroke_cs = CsKind::DeviceRGB;
                    gs.stroke_pattern = None;
                }
            }
            "g" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.fill = gray_to_argb(v);
                    gs.non_stroke_cs = CsKind::DeviceGray;
                    gs.fill_pattern = None;
                }
            }
            "G" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.stroke = gray_to_argb(v);
                    gs.stroke_cs = CsKind::DeviceGray;
                    gs.stroke_pattern = None;
                }
            }
            "k" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 4 {
                    gs.fill = cmyk_to_argb(n[0], n[1], n[2], n[3]);
                    gs.non_stroke_cs = CsKind::DeviceCMYK;
                    gs.fill_pattern = None;
                }
            }
            "K" => {
                let n: Vec<f64> = o.iter().filter_map(num).collect();
                if n.len() == 4 {
                    gs.stroke = cmyk_to_argb(n[0], n[1], n[2], n[3]);
                    gs.stroke_cs = CsKind::DeviceCMYK;
                    gs.stroke_pattern = None;
                }
            }
            "CS" => {
                if let Some(cs_name) = o.first() {
                    if let Some(kind) = parse_named_cs(doc, cs_name, resources, &colorspaces) {
                        // Selecting a color space resets the current color to its
                        // initial value (PDF 8.6.8).
                        if let Some(c) = cs_initial_color(doc, &kind, &colorspaces) { gs.stroke = c; }
                        gs.stroke_cs = kind;
                    }
                    gs.stroke_pattern = None;
                }
            }
            "cs" => {
                if let Some(cs_name) = o.first() {
                    if let Some(kind) = parse_named_cs(doc, cs_name, resources, &colorspaces) {
                        if let Some(c) = cs_initial_color(doc, &kind, &colorspaces) { gs.fill = c; }
                        gs.non_stroke_cs = kind;
                    }
                    gs.fill_pattern = None;
                }
            }
            "SC" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if let Some(rgb) = eval_cs_to_rgb(doc, &gs.stroke_cs, &comps, &colorspaces) {
                    gs.stroke = rgb;
                }
            }
            "sc" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if let Some(rgb) = eval_cs_to_rgb(doc, &gs.non_stroke_cs, &comps, &colorspaces) {
                    gs.fill = rgb;
                }
            }
            "SCN" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if matches!(gs.stroke_cs, CsKind::Pattern { .. }) {
                    gs.stroke_pattern = o.last().and_then(|obj| obj.as_name().ok()).and_then(|pn| patterns.get(pn).copied());
                    if !comps.is_empty() {
                        gs.stroke = uncolored_pattern_argb(doc, &gs.stroke_cs, &comps, &colorspaces);
                    }
                } else if !comps.is_empty() {
                    if let Some(rgb) = eval_cs_to_rgb(doc, &gs.stroke_cs, &comps, &colorspaces) {
                        gs.stroke = rgb;
                    }
                }
            }
            "scn" => {
                let comps: Vec<f64> = o.iter().filter_map(num).collect();
                if matches!(gs.non_stroke_cs, CsKind::Pattern { .. }) {
                    gs.fill_pattern = o.last().and_then(|obj| obj.as_name().ok()).and_then(|pn| patterns.get(pn).copied());
                    if !comps.is_empty() {
                        gs.fill = uncolored_pattern_argb(doc, &gs.non_stroke_cs, &comps, &colorspaces);
                    }
                } else if !comps.is_empty() {
                    if let Some(rgb) = eval_cs_to_rgb(doc, &gs.non_stroke_cs, &comps, &colorspaces) {
                        gs.fill = rgb;
                    }
                }
            }
            "sh" => {
                // Capture the clip extent (device space) that bounds this shading
                // so it can be rasterized at device resolution and, when the
                // shading has no /BBox, cover the whole clip.
                let clip_bbox_device: Option<[f64;4]> = pending_clip.as_ref().map(|pc| {
                    let mut x0 = f64::INFINITY; let mut y0 = f64::INFINITY;
                    let mut x1 = f64::NEG_INFINITY; let mut y1 = f64::NEG_INFINITY;
                    for poly in pc.polys.iter() {
                        for &(x,y) in poly.iter() {
                            x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y);
                        }
                    }
                    [x0, y0, x1, y1]
                }).filter(|b| b[2] > b[0] && b[3] > b[1])
                // Fall back to the already-committed clip region (the common
                // `re W n /Sh sh` case, where pending_clip is None by now).
                .or(current_clip_bbox);
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                if !text_only {
                    if let Some(Object::Name(name)) = o.first() {
                        if let Some(&id) = shadings.get(name) {
                            if let Ok(obj) = doc.get_object(id) {
                                if let Some((ctm,w,h,data)) = rasterize_shading(doc, obj, &gs.ctm, &colorspaces, 0, clip_bbox_device) {
                                    if prims.len() < MAX_PRIMITIVES && !oc_stack.last().copied().unwrap_or(false) {
                                        let sm_start = prims.len();
                                        prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: gs.alpha_fill as f32, blend: gs.blend_mode });
                                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket); }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "BMC" => {
                let hidden = oc_stack.last().copied().unwrap_or(false);
                if oc_stack.len() < MAX_OC_STACK { oc_stack.push(hidden); } else { oc_overflow += 1; }
            }
            "BDC" => {
                // Optional content: `/OC <props> BDC`, where <props> is the OCG/OCMD
                // itself (an inline dict or a name resolved via /Properties). The
                // property list IS the group — there is no nested /OC key.
                let mut should_hide = false;
                let tag = o.first().and_then(|t| t.as_name().ok());
                if tag == Some(b"OC") {
                    if let Some(prop_obj) = o.get(1) {
                        match prop_obj {
                            Object::Name(n) => {
                                // Resolve via the /Properties resource, keeping the
                                // indirect reference so ON/OFF lists can match it.
                                if let Some(&cached) = oc_cache.get(&OcKey::Named(n.clone())) {
                                    should_hide = cached;
                                } else if let Some(res_dict) = resources {
                                    if let Some(prop_dict) = res_dict.get(b"Properties").ok().and_then(|ob| deref(doc, ob)).and_then(|ob| ob.as_dict().ok()) {
                                        if let Ok(oc_ref) = prop_dict.get(n) {
                                            should_hide = oc_hidden!(oc_ref);
                                            oc_cache.insert(OcKey::Named(n.clone()), should_hide);
                                        }
                                    }
                                }
                            }
                            Object::Reference(id) => {
                                should_hide = match oc_cache.get(&OcKey::Ref(*id)) {
                                    Some(&cached) => cached,
                                    None => {
                                        let v = oc_hidden!(prop_obj);
                                        oc_cache.insert(OcKey::Ref(*id), v);
                                        v
                                    }
                                };
                            }
                            other => {
                                should_hide = oc_hidden!(other);
                            }
                        }
                    }
                }
                // Hiding is inherited: a visible OCG nested inside a hidden
                // region stays hidden (§8.11.4.5).
                let hidden = oc_stack.last().copied().unwrap_or(false) || should_hide;
                if oc_stack.len() < MAX_OC_STACK { oc_stack.push(hidden); } else { oc_overflow += 1; }
            }
            "MP" | "DP" => {
                // Marked-content point operators: no matching EMC, so they must not
                // affect the marked-content / optional-content stack.
            }
            "EMC" => {
                // Exactly one frame per EMC (§14.6). Unmatched EMCs are ignored.
                if oc_overflow > 0 { oc_overflow -= 1; } else { oc_stack.pop(); }
            }
            "d0" | "d1" => {
                // Type3 glyph width+bbox: record if inside Type3 context (in draw.rs)
                // Here at top-level content stream, these are explicit no-ops per spec
                // outside charproc, but we honor d1/d0 as no-op without advancing pen.
            }
            "BT" => {
                if let Some(pc) = pending_clip.take() {
                    emit_one_clip(prims, pc, &mut clip_depth, &mut current_clip_bbox, text_only, oc_stack.last().copied().unwrap_or(false));
                }
                text_matrix = IDENTITY;
                line_matrix = IDENTITY;
                text_clip_used = false;
            }
            "ET" => {
                // If the text object added glyphs to the clip (Tr 4-7), apply it.
                if text_clip_used && !text_only && !oc_stack.last().copied().unwrap_or(false) && prims.len() < MAX_PRIMITIVES {
                    prims.push(Prim::TextClipApply);
                    clip_depth += 1;
                }
                text_clip_used = false;
            }
            "Tf" => {
                if let Some(Object::Name(name)) = o.first() {
                    gs.font_key = name.clone();
                }
                if let Some(sz) = o.get(1).and_then(num) {
                    gs.font_size = sz;
                }
            }
            "TL" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.leading = v;
                }
            }
            "Tc" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.char_spacing = v;
                }
            }
            "Tw" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.word_spacing = v;
                }
            }
            "Tz" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.h_scale = v / 100.0;
                }
            }
            "Ts" => {
                if let Some(v) = o.first().and_then(num) {
                    gs.rise = v;
                }
            }
            "Tr" => {
                if let Some(v) = o.first().and_then(num) {
                    // §9.3.6 defines modes 0..7 only.
                    gs.render_mode = (v as i64).clamp(0, 7);
                }
            }
            "Td" => {
                if let (Some(tx), Some(ty)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    line_matrix = mat_mul(&translate(tx, ty), &line_matrix);
                    text_matrix = line_matrix;
                }
            }
            "TD" => {
                if let (Some(tx), Some(ty)) = (o.first().and_then(num), o.get(1).and_then(num)) {
                    gs.leading = -ty;
                    line_matrix = mat_mul(&translate(tx, ty), &line_matrix);
                    text_matrix = line_matrix;
                }
            }
            "Tm" => {
                if let Some(m) = read_matrix(o) {
                    line_matrix = m;
                    text_matrix = m;
                }
            }
            "T*" => {
                line_matrix = mat_mul(&translate(0.0, -gs.leading), &line_matrix);
                text_matrix = line_matrix;
            }
            "Tj" => {
                if let Some(Object::String(bytes, _)) = o.first() {
                    let sm_start = prims.len();
                    if gs.render_mode >= 4 { text_clip_used = true; }
                    // §8.11.2: content in a disabled optional-content group shall
                    // not be drawn — text as much as paths. Render mode 3 is
                    // precisely "neither fill nor stroke" (§9.3.6), so borrow it for
                    // the duration of the show rather than discarding the glyphs:
                    // the layer is hidden, not absent, and `search::build_index`
                    // runs this same code, so dropping them would silently remove
                    // the text from the search index too.
                    let oc_hidden = oc_stack.last().copied().unwrap_or(false);
                    let shown_mode = gs.render_mode;
                    if oc_hidden { gs.render_mode = 3; }
                    let adv = show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                    gs.render_mode = shown_mode;
                    if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                        text_matrix = mat_mul(&translate(0.0, adv), &text_matrix);
                    } else {
                        text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    }
                    // Soft-mask must also cover invisible-clip modes 4-6, not only 0-2.
                    if !oc_hidden && matches!(gs.render_mode, 0|1|2|4|5|6) {
                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket); }
                    }
                }
            }
            "'" => {
                line_matrix = mat_mul(&translate(0.0, -gs.leading), &line_matrix);
                text_matrix = line_matrix;
                if let Some(Object::String(bytes, _)) = o.first() {
                    // P0 fix #24: soft-mask must apply to ' operator
                    let sm_start = prims.len();
                    if gs.render_mode >= 4 { text_clip_used = true; }
                    let oc_hidden = oc_stack.last().copied().unwrap_or(false);
                    let shown_mode = gs.render_mode;
                    if oc_hidden { gs.render_mode = 3; }
                    let adv = show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                    gs.render_mode = shown_mode;
                    if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                        text_matrix = mat_mul(&translate(0.0, adv), &text_matrix);
                    } else {
                        text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    }
                    if !oc_hidden && matches!(gs.render_mode, 0|1|2|4|5|6) {
                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket); }
                    }
                }
            }
            "\"" => {
                if let Some(aw) = o.first().and_then(num) { gs.word_spacing = aw; }
                if let Some(ac) = o.get(1).and_then(num) { gs.char_spacing = ac; }
                line_matrix = mat_mul(&translate(0.0, -gs.leading), &line_matrix);
                text_matrix = line_matrix;
                if let Some(Object::String(bytes, _)) = o.get(2) {
                    // P0 fix #24: soft-mask must apply to " operator
                    let sm_start = prims.len();
                    if gs.render_mode >= 4 { text_clip_used = true; }
                    let oc_hidden = oc_stack.last().copied().unwrap_or(false);
                    let shown_mode = gs.render_mode;
                    if oc_hidden { gs.render_mode = 3; }
                    let adv = show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                    gs.render_mode = shown_mode;
                    if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                        text_matrix = mat_mul(&translate(0.0, adv), &text_matrix);
                    } else {
                        text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    }
                    if !oc_hidden && matches!(gs.render_mode, 0|1|2|4|5|6) {
                        if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket); }
                    }
                }
            }
            "TJ" => {
                let sm_start = prims.len();
                if gs.render_mode >= 4 { text_clip_used = true; }
                let oc_hidden = oc_stack.last().copied().unwrap_or(false);
                let shown_mode = gs.render_mode;
                if oc_hidden { gs.render_mode = 3; }
                if let Some(Object::Array(arr)) = o.first() {
                    for el in arr {
                        match el {
                            Object::String(bytes, _) => {
                                let adv = show_string(doc, prims, &gs, &fonts, &text_matrix, bytes, depth);
                                if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                        text_matrix = mat_mul(&translate(0.0, adv), &text_matrix);
                    } else {
                        text_matrix = mat_mul(&translate(adv, 0.0), &text_matrix);
                    }
                            }
                            Object::Integer(_) | Object::Real(_) => {
                                let n = num(el).unwrap_or(0.0);
                                // TJ adjustment applies along the writing axis.
                                if fonts.get(&gs.font_key).map(|f| f.wmode == 1).unwrap_or(false) {
                                    let ty = -n / 1000.0 * gs.font_size;
                                    text_matrix = mat_mul(&translate(0.0, ty), &text_matrix);
                                } else {
                                    let tx = -n / 1000.0 * gs.font_size * gs.h_scale;
                                    text_matrix = mat_mul(&translate(tx, 0.0), &text_matrix);
                                }
                            }
                            _ => {}
                        }
                    }
                }
                if oc_hidden { gs.render_mode = shown_mode; }
                if !oc_hidden && matches!(gs.render_mode, 0|1|2|4|5|6) {
                    if let Some(m) = gs.soft_mask.clone() { wrap_with_soft_mask(prims, sm_start, doc, resources, &m, depth, &mut mask_bracket); }
                }
            }
            // Explicit no-ops (documented): rendering intent, and compatibility
            // sections have no effect on our flat-primitive output.
            "ri" | "BX" | "EX" | "EI" => {}
            _ => {}
        }
    }
    while group_depth > 0 { if !text_only { prims.push(Prim::GroupPop); } group_depth-=1; }
    while clip_depth > 0 {
        if !text_only {
            prims.push(Prim::ClipPop);
        }
        clip_depth -= 1;
    }
}

pub(crate) fn read_matrix(operands: &[Object]) -> Option<Mat> {
    let n: Vec<f64> = operands.iter().filter_map(num).collect();
    if n.len() == 6 {
        Some([n[0], n[1], n[2], n[3], n[4], n[5]])
    } else {
        None
    }
}

/// Resolve the ARGB base color for an uncolored (`/PaintType 2`) pattern's
/// operands. When the Pattern colorspace declares an underlying base space
/// (`[/Pattern base]`), the operands are interpreted in that space; otherwise
/// they are approximated as Gray/RGB/CMYK by arity.
pub(crate) fn uncolored_pattern_argb(
    doc: &Document,
    cs: &CsKind,
    comps: &[f64],
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
) -> u32 {
    if let CsKind::Pattern { base: Some(base) } = cs {
        if let Some(rgb) = eval_cs_to_rgb(doc, base, comps, cs_resources) {
            return rgb;
        }
    }
    match comps.len() {
        1 => gray_to_argb(comps[0]),
        3 => rgb_to_argb(comps[0], comps[1], comps[2]),
        4 => cmyk_to_argb(comps[0], comps[1], comps[2], comps[3]),
        _ => 0xFF00_0000,
    }
}

/// Paint a pattern fill within the region described by `polys` (device space).
/// Handles PatternType 2 (shading) and PatternType 1 (tiling), bounded by
/// [`MAX_PATTERN_RECURSION`] and a per-pattern tile cap.
/// Build stroke-outline quadrilaterals (device space) for a set of polyline
/// subpaths, offsetting each segment by `hw` (half the device line width) on
/// both sides, plus a small square at every vertex so joints/caps don't leave
/// gaps. Each quad is painted independently so the segments union correctly.
fn stroke_outline_quads(subpaths: &[Vec<(f64, f64)>], hw: f64) -> Vec<Vec<(f64, f64)>> {
    let mut quads: Vec<Vec<(f64, f64)>> = Vec::new();
    for sp in subpaths {
        if sp.len() < 2 { continue; }
        for w in sp.windows(2) {
            let (x0, y0) = w[0];
            let (x1, y1) = w[1];
            let dx = x1 - x0;
            let dy = y1 - y0;
            let len = (dx*dx + dy*dy).sqrt();
            if len < 1e-9 { continue; }
            let nx = -dy / len * hw;
            let ny = dx / len * hw;
            quads.push(vec![
                (x0 + nx, y0 + ny),
                (x1 + nx, y1 + ny),
                (x1 - nx, y1 - ny),
                (x0 - nx, y0 - ny),
            ]);
        }
        for &(x, y) in sp.iter() {
            quads.push(vec![
                (x - hw, y - hw),
                (x + hw, y - hw),
                (x + hw, y + hw),
                (x - hw, y + hw),
            ]);
        }
    }
    quads
}

/// Identity of a soft mask, used to decide whether an already-emitted bracket
/// can absorb another painting operation.
///
/// §11.6.5.2 renders the mask group with the CTM in effect when `gs` set the
/// mask, so the CTM is part of the identity: the same group under two different
/// CTMs is two different masks. `/BC` changes the rendered group and `/TR` the
/// push record, so both are included.
#[derive(PartialEq)]
pub(crate) struct MaskKey {
    group_id: ObjectId,
    mask_type: u8,
    ctm: [u64; 6],
    backdrop: Option<Vec<u64>>,
    tr: Option<[u8; 256]>,
}

impl MaskKey {
    fn of(mask: &SoftMask) -> Self {
        let mut ctm = [0u64; 6];
        for (dst, src) in ctm.iter_mut().zip(mask.ctm.iter()) {
            *dst = src.to_bits();
        }
        MaskKey {
            group_id: mask.group_id,
            mask_type: mask.mask_type,
            ctm,
            backdrop: mask
                .backdrop
                .as_ref()
                .map(|b| b.iter().map(|v| v.to_bits()).collect()),
            tr: mask.tr,
        }
    }
}

/// The soft-mask bracket most recently emitted into `prims`, so a following
/// painting operation under the same mask can extend it.
pub(crate) struct MaskBracket {
    key: MaskKey,
    /// Index of the `SoftMaskPush`.
    push: usize,
    /// Index of the `SoftMaskContent` separator.
    content: usize,
    /// `prims.len()` when the bracket was closed. Coalescing is only sound while
    /// the bracket is still the tail of `prims`.
    end: usize,
}

/// Render an ExtGState soft-mask group into `prims` as the mask content of a
/// SoftMaskPush/Content/Pop bracket. The group is placed at the CTM captured
/// when the mask was set. For a luminosity mask with a `/BC` backdrop, a
/// backdrop-colored rectangle is painted first so uncovered areas take the
/// backdrop luminance (default black otherwise).
pub(crate) fn render_soft_mask_group(
    doc: &Document,
    resources: Option<&lopdf::Dictionary>,
    mask: &SoftMask,
    prims: &mut Vec<Prim>,
    depth: u32,
) {
    if depth >= MAX_PATTERN_RECURSION || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let mstream = match doc.get_object(mask.group_id) {
        Ok(Object::Stream(s)) => s.clone(),
        _ => return,
    };
    let mmatrix = mstream.dict.get(b"Matrix").ok().and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o))).unwrap_or(IDENTITY);
    let group_ctm = mat_mul(&mmatrix, &mask.ctm);
    let mres = mstream.dict.get(b"Resources").ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();
    // /BC backdrop rectangle for luminosity masks.
    if mask.mask_type == 1 {
        if let Some(bc) = &mask.backdrop {
            if let Some(rect) = mstream.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
                let cs = mstream.dict.get(b"Group").ok().and_then(|o| deref(doc, o))
                    .and_then(|o| o.as_dict().ok())
                    .and_then(|gd| gd.get(b"CS").ok().and_then(|o| parse_cs_kind(doc, Some(o), &HashMap::new())));
                let argb = cs.as_ref()
                    .and_then(|k| eval_cs_to_rgb(doc, k, bc, &HashMap::new()))
                    .unwrap_or_else(|| match bc.len() {
                        1 => gray_to_argb(bc[0]),
                        3 => rgb_to_argb(bc[0], bc[1], bc[2]),
                        4 => cmyk_to_argb(bc[0], bc[1], bc[2], bc[3]),
                        _ => 0xFF00_0000,
                    });
                let corners = [
                    transform(&group_ctm, rect[0], rect[1]),
                    transform(&group_ctm, rect[2], rect[1]),
                    transform(&group_ctm, rect[2], rect[3]),
                    transform(&group_ctm, rect[0], rect[3]),
                ];
                let poly: Vec<(f64, f64)> = corners.to_vec();
                emit_fill(prims, std::slice::from_ref(&poly), argb, false, 1.0, BlendMode::Normal);
            }
        }
    }
    let msub_ops = crate::content::stream_operations(doc, &mstream);
    if !msub_ops.is_empty() {
        let mgs = GraphicsState {
            ctm: group_ctm,
            soft_mask: None,
            blend_mode: BlendMode::Normal,
            alpha_fill: 1.0,
            alpha_stroke: 1.0,
            ..Default::default()
        };
        let mres_ref = mres.as_ref().or(resources);
        // Same §8.7.4.1 hazard as the `Do` arm: a mask group is a form XObject
        // (§11.6.5.2), so its `/BBox` is the clip its content is drawn under, and a
        // `sh` inside it paints nothing at all without that extent. A mask that
        // paints nothing is uniformly black, which for a luminosity mask hides
        // ALL of the masked content rather than merely mis-toning it.
        let mask_clip_bbox = mstream
            .dict
            .get(b"BBox")
            .ok()
            .and_then(|o| read_rect(doc, o))
            .and_then(|bb| {
                quad_device_bbox(&[
                    transform(&group_ctm, bb[0], bb[1]),
                    transform(&group_ctm, bb[2], bb[1]),
                    transform(&group_ctm, bb[2], bb[3]),
                    transform(&group_ctm, bb[0], bb[3]),
                ])
            });
        interpret_content_seeded(
            doc,
            &msub_ops,
            mres_ref,
            mgs,
            prims,
            depth + 1,
            false,
            mask_clip_bbox,
        );
    }
}

/// Bracket the primitives appended since `start` with the given soft mask so
/// they are drawn only where the mask is opaque/luminous. No-op if nothing was
/// emitted. Reuses the SoftMaskPush/Content/Pop wire prims.
///
/// §11.6.5.1 makes the soft mask a graphics-state PARAMETER: one mask covers
/// every operation painted while it is set. So when the bracket in `bracket` is
/// still the tail of `prims` and carries the same mask, this operation's
/// primitives are moved inside it instead of opening a second bracket. Opening
/// one per operation re-interprets the mask's whole content stream every time,
/// which on a page with one gradient mask over hundreds of shapes expands the
/// mask hundreds of times and pushes the page past [`MAX_PRIMITIVES`], dropping
/// real content.
///
/// The merge composites the coalesced operations against each other inside the
/// masked layer before the mask is applied, rather than masking each one
/// separately against the backdrop. Those agree exactly for a fully opaque or
/// fully transparent mask and for non-overlapping content — which is what a run
/// of shapes under one mask is in practice — and differ only in the alpha
/// arithmetic where partially-masked content overlaps itself.
pub(crate) fn wrap_with_soft_mask(
    prims: &mut Vec<Prim>,
    start: usize,
    doc: &Document,
    resources: Option<&lopdf::Dictionary>,
    mask: &SoftMask,
    depth: u32,
    bracket: &mut Option<MaskBracket>,
) {
    if start >= prims.len() || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let key = MaskKey::of(mask);
    // Structural re-validation rather than invalidating on every other push: the
    // recorded indices must still name the bracket's own prims, and the bracket
    // must end exactly where this operation began.
    if let Some(b) = bracket.as_mut() {
        if b.key == key
            && b.end == start
            && b.push < b.content
            && b.content < b.end
            && matches!(prims.get(b.push), Some(Prim::SoftMaskPush { .. }))
            && matches!(prims.get(b.content), Some(Prim::SoftMaskContent))
            && matches!(prims.get(b.end - 1), Some(Prim::SoftMaskPop))
        {
            let added: Vec<Prim> = prims.drain(start..).collect();
            let n = added.len();
            prims.splice(b.content..b.content, added);
            b.content += n;
            b.end += n;
            return;
        }
    }
    prims.insert(start, Prim::SoftMaskPush { mask_type: mask.mask_type });
    // §11.6.5.2: the mask value is passed through `/TR` before use. `model.rs`
    // specifies this immediately after the push, and it is `None` for `/Identity`.
    if let Some(lut) = mask.tr {
        prims.insert(start + 1, Prim::SoftMaskTransfer(Box::new(lut)));
    }
    let content = prims.len();
    prims.push(Prim::SoftMaskContent);
    render_soft_mask_group(doc, resources, mask, prims, depth);
    prims.push(Prim::SoftMaskPop);
    *bracket = Some(MaskBracket { key, push: start, content, end: prims.len() });
}

/// Axis-aligned device-space bbox of a transformed `/BBox` quad, or `None` when the
/// quad is degenerate or non-finite.
///
/// §8.7.4.1 makes `sh` fill the whole current clip, and `rasterize_shading` paints
/// nothing rather than guess an extent, so every nested content stream has to hand
/// down the box its content is clipped to. Shared by the three that have one: a form
/// XObject's `/BBox` (§8.10.1), a soft-mask group's (§11.6.5.2), a tiling-pattern
/// cell's (§8.7.3.1) and an annotation appearance stream's (§12.5.5).
pub(crate) fn quad_device_bbox(c: &[(f64, f64); 4]) -> Option<[f64; 4]> {
    let xs = c.iter().map(|p| p.0);
    let ys = c.iter().map(|p| p.1);
    let b = [
        xs.clone().fold(f64::INFINITY, f64::min),
        ys.clone().fold(f64::INFINITY, f64::min),
        xs.fold(f64::NEG_INFINITY, f64::max),
        ys.fold(f64::NEG_INFINITY, f64::max),
    ];
    if b.iter().all(|v| v.is_finite()) && b[2] > b[0] && b[3] > b[1] {
        Some(b)
    } else {
        None
    }
}

/// Bounding box (device space) of a set of polygons, or `None` if empty.
fn polys_device_bbox(polys: &[Vec<(f64, f64)>]) -> Option<[f64;4]> {
    let mut x0 = f64::INFINITY; let mut y0 = f64::INFINITY;
    let mut x1 = f64::NEG_INFINITY; let mut y1 = f64::NEG_INFINITY;
    for poly in polys {
        for &(x,y) in poly.iter() {
            x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y);
        }
    }
    if x1 > x0 && y1 > y0 { Some([x0, y0, x1, y1]) } else { None }
}

/// the segments (matching how a real stroke covers the path).
pub(crate) fn paint_pattern_stroke(
    doc: &Document,
    pattern_id: ObjectId,
    subpaths: &[Vec<(f64, f64)>],
    gs: &GraphicsState,
    pattern_base_ctm: &Mat,
    prims: &mut Vec<Prim>,
    depth: u32,
    clip_depth: usize,
) {
    // These clips are pushed and popped inside this function, so they never
    // unbalance the stream — but they DO consume renderer clip levels, so they
    // have to respect the same ceiling as `emit_one_clip`.
    if clip_depth >= MAX_CLIP_DEPTH {
        return;
    }
    if depth >= MAX_PATTERN_RECURSION || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let obj = match doc.get_object(pattern_id) {
        Ok(o) => o,
        Err(_) => return,
    };
    let dict = match obj {
        Object::Dictionary(d) => d,
        Object::Stream(s) => &s.dict,
        _ => return,
    };
    let ptype = dict.get(b"PatternType").ok().and_then(num).unwrap_or(0.0) as i64;
    let matrix = dict.get(b"Matrix").ok().and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o))).unwrap_or(IDENTITY);
    let pmat = mat_mul(&matrix, pattern_base_ctm);

    // Half stroke width in device space (CTM average axis scale).
    let ctm = &gs.ctm;
    let sx = (ctm[0]*ctm[0] + ctm[1]*ctm[1]).sqrt();
    let sy = (ctm[2]*ctm[2] + ctm[3]*ctm[3]).sqrt();
    let scale = (sx + sy) / 2.0;
    // P0 fix medium #23: don't enlarge hairlines via min 0.35 – keep true width, Kotlin handles 1 device px hairline
    let hw = (gs.line_width * scale) / 2.0;

    // Build ONE clip covering every stroke quad, then paint the pattern ONCE.
    // Rasterizing the shading per quad allocated a full bbox-sized image for each
    // of the ~2N quads of an N-point path (up to ~4 MB each), i.e. multi-GB on any
    // gradient-stroked curve.
    const MAX_STROKE_QUADS: usize = 4096;
    let mut quads = stroke_outline_quads(subpaths, hw);
    if quads.len() > MAX_STROKE_QUADS {
        quads.truncate(MAX_STROKE_QUADS);
    }
    let stroke_bbox = polys_device_bbox(subpaths);
    let mut path_ops: Vec<PathOp> = Vec::new();
    for quad in &quads {
        if quad.len() < 3 || shoelace_area(quad).abs() < 1e-3 { continue; }
        // The quads deliberately overlap (segment bodies plus vertex squares).
        // Normalize each to positive winding so they UNION under the nonzero rule
        // rather than cancelling each other into holes.
        let mut q: Vec<(f64, f64)> = quad.clone();
        if shoelace_area(&q) < 0.0 {
            q.reverse();
        }
        path_ops.push(PathOp::Move(q[0].0 as f32, q[0].1 as f32));
        for &(x, y) in &q[1..] {
            path_ops.push(PathOp::Line(x as f32, y as f32));
        }
        path_ops.push(PathOp::Close);
    }
    if path_ops.is_empty() || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let pts: Vec<(f32, f32)> = quads
        .first()
        .map(|q| q.iter().map(|&(x, y)| (x as f32, y as f32)).collect())
        .unwrap_or_default();
    prims.push(Prim::ClipPush { even_odd: false, pts, path_ops: Some(path_ops) });
    if ptype == 2 {
        if let Some(shobj) = dict.get(b"Shading").ok().and_then(|o| deref(doc, o)) {
            if let Some((ctm, w, h, data)) = rasterize_shading(doc, shobj, &pmat, &HashMap::new(), 0, stroke_bbox) {
                if prims.len() < MAX_PRIMITIVES {
                    prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: gs.alpha_stroke as f32, blend: gs.blend_mode });
                }
            }
        }
    } else if ptype == 1 {
        paint_tiling_pattern(doc, obj, dict, &pmat, gs.stroke, &quads, prims, depth, gs.alpha_stroke as f32, gs.blend_mode);
    }
    prims.push(Prim::ClipPop);
}

pub(crate) fn paint_pattern_fill(
    doc: &Document,
    pattern_id: ObjectId,
    polys: &[Vec<(f64, f64)>],
    even_odd: bool,
    pattern_base_ctm: &Mat,
    base_argb: u32,
    alpha_fill: f32,
    blend: BlendMode,
    prims: &mut Vec<Prim>,
    depth: u32,
    clip_depth: usize,
) {
    if clip_depth >= MAX_CLIP_DEPTH {
        return;
    }
    if depth >= MAX_PATTERN_RECURSION || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let obj = match doc.get_object(pattern_id) {
        Ok(o) => o,
        Err(_) => return,
    };
    let dict = match obj {
        Object::Dictionary(d) => d,
        Object::Stream(s) => &s.dict,
        _ => return,
    };
    let ptype = dict.get(b"PatternType").ok().and_then(num).unwrap_or(0.0) as i64;
    let matrix = dict.get(b"Matrix").ok().and_then(|o| read_matrix_obj(deref(doc, o).unwrap_or(o))).unwrap_or(IDENTITY);
    let pmat = mat_mul(&matrix, pattern_base_ctm);

    // ONE clip for the whole fill region.
    // per contour made a path with disjoint subpaths paint nothing at all, and
    // more than 63 contours tripped the renderer's clip-depth guard and discarded
    // the rest of the page. path_ops carries every contour so holes survive.
    let mut path_ops: Vec<PathOp> = Vec::new();
    let mut first: Option<&Vec<(f64, f64)>> = None;
    for poly in polys {
        if poly.len() < 3 {
            continue;
        }
        if first.is_none() {
            first = Some(poly);
        }
        path_ops.push(PathOp::Move(poly[0].0 as f32, poly[0].1 as f32));
        for &(x, y) in &poly[1..] {
            path_ops.push(PathOp::Line(x as f32, y as f32));
        }
        path_ops.push(PathOp::Close);
    }
    if path_ops.is_empty() || prims.len() >= MAX_PRIMITIVES {
        return;
    }
    let pts: Vec<(f32, f32)> = first
        .map(|p| p.iter().map(|&(x, y)| (x as f32, y as f32)).collect())
        .unwrap_or_default();
    prims.push(Prim::ClipPush { even_odd, pts, path_ops: Some(path_ops) });

    if ptype == 2 {
        if let Some(shobj) = dict.get(b"Shading").ok().and_then(|o| deref(doc, o)) {
            let fill_bbox = polys_device_bbox(polys);
            if let Some((ctm, w, h, data)) = rasterize_shading(doc, shobj, &pmat, &HashMap::new(), 0, fill_bbox) {
                if prims.len() < MAX_PRIMITIVES {
                    prims.push(Prim::Image { ctm, w, h, format: 0, data, alpha: alpha_fill, blend });
                }
            }
        }
    } else if ptype == 1 {
        paint_tiling_pattern(doc, obj, dict, &pmat, base_argb, polys, prims, depth, alpha_fill, blend);
    }

    prims.push(Prim::ClipPop);
}

fn paint_tiling_pattern(
    doc: &Document,
    obj: &Object,
    dict: &lopdf::Dictionary,
    pmat: &Mat,
    base_argb: u32,
    polys: &[Vec<(f64, f64)>],
    prims: &mut Vec<Prim>,
    depth: u32,
    alpha: f32,
    blend: BlendMode,
) {
    let stream = match obj {
        Object::Stream(s) => s,
        _ => return,
    };
    let paint_type = dict.get(b"PaintType").ok().and_then(num).unwrap_or(1.0) as i64;
    let bbox = dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).unwrap_or([0.0, 0.0, 1.0, 1.0]);
    let xstep = dict.get(b"XStep").ok().and_then(num).unwrap_or(bbox[2] - bbox[0]);
    let ystep = dict.get(b"YStep").ok().and_then(num).unwrap_or(bbox[3] - bbox[1]);
    // The tile lattice spacing is a magnitude; a negative /XStep or /YStep made
    // i0 > i1 so the loop body never ran and the pattern painted nothing.
    let xstep = xstep.abs();
    let ystep = ystep.abs();
    // §8.7.4.1: `sh` fills the whole current clip, and `rasterize_shading` paints NOTHING
    // for a shading with no `/BBox` of its own when handed no clip extent. §8.7.3.1 clips a
    // cell to the pattern `/BBox`, so that box is the extent for every path below that
    // interprets the cell. Two of them are the malformed-pattern fallbacks, which paint the
    // cell once at `pmat`; the third (the periodic-raster path) works in cell space and
    // needs the box unmapped, computed separately at its own site.
    let cell_bbox_device = quad_device_bbox(&[
        transform(pmat, bbox[0], bbox[1]),
        transform(pmat, bbox[2], bbox[1]),
        transform(pmat, bbox[2], bbox[3]),
        transform(pmat, bbox[0], bbox[3]),
    ]);
    // Zero-step pattern is malformed — show bbox once instead of blanking
    if xstep.abs() < 1e-6 || ystep.abs() < 1e-6 {
        let res = dict
            .get(b"Resources")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .cloned();
        // Same all-or-nothing hazard as a page or a form: one inline image lopdf
        // rejects would otherwise blank the whole cell.
        let cell_ops = crate::content::stream_operations(doc, stream);
        if !cell_ops.is_empty() {
            let mut tile_gs = GraphicsState { ctm: *pmat, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
            if paint_type == 2 { tile_gs.fill = base_argb; tile_gs.stroke = base_argb; }
            interpret_content_seeded(doc, &cell_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_bbox_device);
        }
        return;
    }
    let res = dict
        .get(b"Resources")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned();
    let content_ops = crate::content::stream_operations(doc, stream);
    if content_ops.is_empty() {
        return;
    }

    // Device-space bounding box of the fill region.
    let (mut minx, mut miny, mut maxx, mut maxy) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for poly in polys {
        for &(x, y) in poly {
            minx = minx.min(x);
            miny = miny.min(y);
            maxx = maxx.max(x);
            maxy = maxy.max(y);
        }
    }
    if !minx.is_finite() {
        return;
    }

    // Map that box into pattern space to bound the tile index range.
    let inv = mat_inverse(pmat);
    // Singular pattern matrix — degrade to single tile instead of blank.
    if (inv[0]*inv[3] - inv[1]*inv[2]).abs() < 1e-12 {
        let mut tile_gs = GraphicsState { ctm: *pmat, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
        if paint_type == 2 { tile_gs.fill = base_argb; tile_gs.stroke = base_argb; }
        interpret_content_seeded(doc, &content_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_bbox_device);
        return;
    }
    let (mut pminx, mut pminy, mut pmaxx, mut pmaxy) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for (x, y) in [(minx, miny), (maxx, miny), (minx, maxy), (maxx, maxy)] {
        let (px, py) = transform(&inv, x, y);
        pminx = pminx.min(px);
        pminy = pminy.min(py);
        pmaxx = pmaxx.max(px);
        pmaxy = pmaxy.max(py);
    }
    let i0 = ((pminx - bbox[2]) / xstep).floor() as i64;
    let i1 = ((pmaxx - bbox[0]) / xstep).ceil() as i64;
    let j0 = ((pminy - bbox[3]) / ystep).floor() as i64;
    let j1 = ((pmaxy - bbox[1]) / ystep).ceil() as i64;
    let total_i = (i1 - i0 + 1).max(0);
    let total_j = (j1 - j0 + 1).max(0);

    // §8.7.3.3 requires the cell replicated across the WHOLE region. Rasterizing
    // the cell ONCE and emitting a periodic bitmap makes the tile count
    // irrelevant, which is the only way to satisfy that: the per-tile path below
    // has to cap the count, and any cap leaves part of a large hatched region
    // blank.
    //
    // Restricted to cells made only of fills and strokes, because
    // `rasterize_prims_to_rgba` has no glyph rasterizer and ignores images, clips
    // and groups — a cell containing any of those would silently lose it, so it
    // keeps the per-tile path. `rasterize_pattern_cell` owns the other gates (the
    // period being the step and not the bbox, and bounding the copies-per-period
    // needed to honour §8.7.3.1 overlap).
    let mut cell_prims: Vec<Prim> = Vec::new();
    let mut cell_gs = GraphicsState {
        ctm: IDENTITY,
        // §11.6.7 treats the pattern as a transparency group: alpha and blend ride
        // on the composited result, not on each element inside the cell.
        alpha_fill: 1.0,
        alpha_stroke: 1.0,
        ..GraphicsState::default()
    };
    if paint_type == 2 { cell_gs.fill = base_argb; cell_gs.stroke = base_argb; }
    // This cell is interpreted at IDENTITY, so its "device" space IS pattern space and the
    // extent is the `/BBox` unmapped. Seeding it is not cosmetic: it decides the GATE below.
    // Unseeded, a cell whose only content is `sh` produced no Image prim, `cell_prims` came
    // out all-Fill/Stroke, the periodic-raster path was taken, and the gradient was silently
    // dropped from every tile. Seeded, the Image appears, the gate correctly rejects it, and
    // the per-tile path (also seeded) paints it.
    let cell_space_bbox = quad_device_bbox(&[
        (bbox[0], bbox[1]),
        (bbox[2], bbox[1]),
        (bbox[2], bbox[3]),
        (bbox[0], bbox[3]),
    ]);
    interpret_content_seeded(doc, &content_ops, res.as_ref(), cell_gs, &mut cell_prims, depth + 1, false, cell_space_bbox);
    if !cell_prims.is_empty()
        && cell_prims.iter().all(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. }))
        && prims.len() < MAX_PRIMITIVES
    {
        // Pattern-space -> device scale, so the cell is rasterized at display
        // resolution instead of an arbitrary fixed size.
        let sx = (pmat[0] * pmat[0] + pmat[1] * pmat[1]).sqrt();
        let sy = (pmat[2] * pmat[2] + pmat[3] * pmat[3]).sqrt();
        if let Some((cw, ch, data)) =
            rasterize_pattern_cell(&cell_prims, bbox, xstep, ystep, sx.max(sy))
        {
            // The unit square maps onto ONE CELL — one period of the lattice —
            // anchored at the bbox origin, matching `rasterize_pattern_cell`'s
            // step rect.
            let cell_mat: Mat = [xstep, 0.0, 0.0, ystep, bbox[0], bbox[1]];
            // For a PERIODIC bitmap the extent is counted in whole periods
            // relative to the region: cell `i` spans pattern x in
            // [bbox[0] + i*xstep, bbox[0] + (i+1)*xstep). That differs from the
            // per-tile loop's `i0`/`i1`, which are in bbox-overlap terms and
            // deliberately start a cell early — here that would report an extent
            // a period wider than the region on every side.
            let ti0 = ((pminx - bbox[0]) / xstep).floor();
            let tj0 = ((pminy - bbox[1]) / ystep).floor();
            let tnx = (((pmaxx - bbox[0]) / xstep).ceil() - ti0).max(1.0);
            let tny = (((pmaxy - bbox[1]) / ystep).ceil() - tj0).max(1.0);
            prims.push(Prim::ImageTiled {
                ctm: mat_mul(&cell_mat, pmat),
                w: cw,
                h: ch,
                data,
                xstep: xstep as f32,
                ystep: ystep as f32,
                i0: ti0.clamp(i32::MIN as f64, i32::MAX as f64) as i32,
                j0: tj0.clamp(i32::MIN as f64, i32::MAX as f64) as i32,
                nx: tnx.clamp(1.0, u32::MAX as f64) as u32,
                ny: tny.clamp(1.0, u32::MAX as f64) as u32,
                alpha,
                blend,
            });
            return;
        }
    }

    // Per-tile fallback. Reached when the cell contains text, an image or its own
    // clipping (see the gate above), and when `rasterize_pattern_cell` declines:
    // a degenerate step, or a `/BBox` so much larger than the step that honouring
    // §8.7.3.1 overlap would need more than its copies-per-period budget. Plain
    // overlap is NOT a fallback case — that path composites the cell at each
    // reaching lattice offset and stays periodic.
    //
    // Each cell is REPLAYED as primitives here, so the count has to be capped.
    const MAX_TILES: i64 = 20_000;
    // §8.7.3.3 requires the cell replicated across the WHOLE region, so when the
    // lattice exceeds the budget, thin it out UNIFORMLY. Taking a dense square
    // patch anchored at one corner instead — which is what this used to do — left
    // the rest of the region empty, and a 2pt lattice over a 400x400 region is
    // 40,401 tiles, so it covered barely half. A lower-density cell over the whole
    // region still reads as the texture that was asked for; a correct patch beside
    // a blank area reads as missing content.
    //
    // f64 for the product: `i1`/`j1` come from a division by a step that may be
    // tiny, so `total_i * total_j` can overflow `i64` and panic in a debug build.
    let need = total_i as f64 * total_j as f64;
    let stride = if need > MAX_TILES as f64 {
        ((need / MAX_TILES as f64).sqrt().ceil() as i64).max(1)
    } else {
        1
    };
    let mut count = 0i64;
    'outer: for j in (j0..=j1).step_by(stride as usize) {
        for i in (i0..=i1).step_by(stride as usize) {
            if count >= MAX_TILES || prims.len() >= MAX_PRIMITIVES {
                break 'outer;
            }
            count += 1;
            let translate: Mat = [1.0, 0.0, 0.0, 1.0, i as f64 * xstep, j as f64 * ystep];
            let tile_ctm = mat_mul(&translate, pmat);
            // Clip each cell to the pattern /BBox (PDF 8.7.3.1) so content that
            // overflows the cell — or a cell smaller than XStep/YStep — cannot
            // bleed into neighboring cells.
            let bc = [
                transform(&tile_ctm, bbox[0], bbox[1]),
                transform(&tile_ctm, bbox[2], bbox[1]),
                transform(&tile_ctm, bbox[2], bbox[3]),
                transform(&tile_ctm, bbox[0], bbox[3]),
            ];
            let cell_pts: Vec<(f32, f32)> = bc.iter().map(|&(x, y)| (x as f32, y as f32)).collect();
            let cell_po = vec![
                PathOp::Move(bc[0].0 as f32, bc[0].1 as f32),
                PathOp::Line(bc[1].0 as f32, bc[1].1 as f32),
                PathOp::Line(bc[2].0 as f32, bc[2].1 as f32),
                PathOp::Line(bc[3].0 as f32, bc[3].1 as f32),
                PathOp::Close,
            ];
            prims.push(Prim::ClipPush { even_odd: false, pts: cell_pts, path_ops: Some(cell_po) });
            let mut tile_gs = GraphicsState { ctm: tile_ctm, alpha_fill: alpha as f64, alpha_stroke: alpha as f64, blend_mode: blend, ..GraphicsState::default() };
            if paint_type == 2 {
                tile_gs.fill = base_argb;
                tile_gs.stroke = base_argb;
            }
            // §8.7.3.1 clips the cell to the pattern `/BBox`, which is therefore the
            // clip extent a `sh` inside the cell must fill (§8.7.4.1). Without it
            // `rasterize_shading` declines and a gradient-filled hatch cell paints
            // nothing — the `bc` corners just used for the ClipPush are that extent.
            let cell_clip = quad_device_bbox(&bc);
            interpret_content_seeded(doc, &content_ops, res.as_ref(), tile_gs, prims, depth + 1, false, cell_clip);
            prims.push(Prim::ClipPop);
        }
    }
}

/// Read a 6-element matrix from an array object.
pub(crate) fn read_matrix_obj(obj: &Object) -> Option<Mat> {
    match obj {
        Object::Array(a) => read_matrix(a),
        _ => None,
    }
}

/// Read a 4-number array (e.g. `/Rect`, `/BBox`) resolving references.
pub(crate) fn read_rect(doc: &Document, obj: &Object) -> Option<[f64; 4]> {
    let arr = deref(doc, obj)?.as_array().ok()?;
    if arr.len() != 4 {
        return None;
    }
    let mut out = [0.0; 4];
    for (i, v) in arr.iter().enumerate() {
        out[i] = deref(doc, v).and_then(num)?;
    }
    Some(out)
}

#[cfg(test)]
mod coverage_gap_tests {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    /// §8.7.4.1: `sh` shall fill the ENTIRE current clipping region. Because a
    /// shading with no `/BBox` has no extent of its own, `images::rasterize_shading`
    /// deliberately refuses to guess one and returns `None` — painting nothing —
    /// when the caller supplies no clip extent either. Only the page-level caller
    /// ever did; every nested stream went through `interpret_content`, which passes
    /// `None`. So `/Sh sh` inside a form XObject emitted no primitive at all, which
    /// is a fully-implemented operator rendering nothing. §8.10.1 makes the form's
    /// `/BBox` the clip its content is drawn under, so that box is the extent.
    #[test]
    fn sh_inside_a_form_xobject_paints_without_a_shading_bbox() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![1.0.into(), 0.0.into(), 0.0.into()],
            "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
            "N" => 1,
        });
        // Deliberately NO /BBox on the shading: the whole point of the test.
        let shading = doc.add_object(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.into(), 0.into(), 100.into(), 0.into()],
            "Function" => Object::Reference(func),
        });
        let form = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
                "Resources" => dictionary! {
                    "Shading" => dictionary! { "Sh" => Object::Reference(shading) },
                },
            },
            b"/Sh sh".to_vec(),
        ));
        let res = dictionary! {
            "XObject" => dictionary! { "Fm" => Object::Reference(form) },
        };
        let ops = vec![Operation::new("Do", vec![Object::Name(b"Fm".to_vec())])];
        let mut prims = Vec::new();
        // Mirrors `interpret_page`: the page box is the starting clip extent.
        interpret_content_seeded(
            &doc,
            &ops,
            Some(&res),
            GraphicsState::default(),
            &mut prims,
            0,
            false,
            Some([0.0, 0.0, 200.0, 200.0]),
        );
        let images = prims.iter().filter(|p| matches!(p, Prim::Image { .. })).count();
        assert_eq!(images, 1, "the gradient must be painted, not silently dropped");
        // A 1x1 raster would technically satisfy the count while showing nothing, so
        // check the shading was actually rasterized over the form's box.
        for p in &prims {
            if let Prim::Image { w, h, data, .. } = p {
                assert!(*w > 1 && *h > 1, "raster collapsed to {w}x{h}");
                assert_eq!(data.len(), (*w as usize) * (*h as usize) * 4);
                assert!(data.chunks(4).any(|px| px[3] > 0), "raster is fully transparent");
            }
        }
    }

    /// Same hazard one level deeper: a luminosity soft mask whose group paints
    /// nothing is uniformly black, which hides ALL of the masked content rather
    /// than merely mis-toning it. §11.6.5.2 makes the mask group a form XObject,
    /// so its `/BBox` is the extent for a `sh` inside it.
    #[test]
    fn sh_inside_a_soft_mask_group_paints_without_a_shading_bbox() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.0.into()],
            "C1" => vec![1.0.into()],
            "N" => 1,
        });
        let shading = doc.add_object(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceGray",
            "Coords" => vec![0.into(), 0.into(), 100.into(), 0.into()],
            "Function" => Object::Reference(func),
        });
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
                "Group" => dictionary! { "S" => "Transparency" },
                "Resources" => dictionary! {
                    "Shading" => dictionary! { "Sh" => Object::Reference(shading) },
                },
            },
            b"/Sh sh".to_vec(),
        ));
        let mask = SoftMask {
            group_id: group,
            mask_type: 1,
            ctm: IDENTITY,
            backdrop: None,
            tr: None,
        };
        let mut prims = Vec::new();
        render_soft_mask_group(&doc, None, &mask, &mut prims, 0);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Image { .. })),
            "an all-black mask hides everything it should reveal"
        );
    }

    /// §8.11.2: content in a disabled optional-content group shall not be drawn.
    /// The `Do` arm gated the Image branch, the `/BBox` ClipPush and the
    /// `GroupPush` on the marked-content hidden flag but NOT the form recursion,
    /// and `oc_stack` is a local that no nested stream inherits — so a hidden
    /// form painted its entire contents, and painted them UNCLIPPED, because the
    /// clip that would have bounded them was suppressed by the very flag the
    /// recursion ignored. Reported by `residuals`' cross-round interaction review.
    #[test]
    fn a_form_xobject_in_a_disabled_oc_group_paints_nothing() {
        // `text_only` = false is the render path; true is `search::build_index`,
        // which must still descend so a hidden layer's text stays searchable.
        let run = |text_only: bool| -> (usize, usize) {
            let mut doc = Document::with_version("1.7");
            let ocg = doc.add_object(dictionary! {
                "Type" => "OCG",
                "Name" => Object::string_literal("hidden layer"),
            });
            // The OCG is OFF in the default configuration.
            let catalog = doc.add_object(dictionary! {
                "Type" => "Catalog",
                "OCProperties" => dictionary! {
                    "OCGs" => vec![Object::Reference(ocg)],
                    "D" => dictionary! { "OFF" => vec![Object::Reference(ocg)] },
                },
            });
            doc.trailer.set("Root", Object::Reference(catalog));
            let font = doc.add_object(dictionary! {
                "Type" => "Font",
                "Subtype" => "Type1",
                "BaseFont" => "Helvetica",
                "FirstChar" => 65,
                "LastChar" => 66,
                "Widths" => vec![1000.into(), 1000.into()],
            });
            let form = doc.add_object(Stream::new(
                dictionary! {
                    "Type" => "XObject",
                    "Subtype" => "Form",
                    "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
                    "Resources" => dictionary! {
                        "Font" => dictionary! { "F1" => Object::Reference(font) },
                    },
                },
                b"0 0 50 50 re f BT /F1 12 Tf (AB) Tj ET".to_vec(),
            ));
            let res = dictionary! {
                "XObject" => dictionary! { "Fm" => Object::Reference(form) },
                "Properties" => dictionary! { "P1" => Object::Reference(ocg) },
            };
            let ops = vec![
                Operation::new(
                    "BDC",
                    vec![Object::Name(b"OC".to_vec()), Object::Name(b"P1".to_vec())],
                ),
                Operation::new("Do", vec![Object::Name(b"Fm".to_vec())]),
                Operation::new("EMC", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content(
                &doc,
                &ops,
                Some(&res),
                GraphicsState::default(),
                &mut prims,
                0,
                text_only,
            );
            (
                prims.iter().filter(|p| matches!(p, Prim::Fill { .. })).count(),
                prims.iter().filter(|p| matches!(p, Prim::Text { .. })).count(),
            )
        };

        let (fills, _) = run(false);
        assert_eq!(fills, 0, "a hidden layer's form must not paint");

        // Sanity check that the fixture would paint at all when the layer is ON,
        // so a zero above cannot come from a broken fixture.
        let mut doc = Document::with_version("1.7");
        let form = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            },
            b"0 0 50 50 re f".to_vec(),
        ));
        let res = dictionary! {
            "XObject" => dictionary! { "Fm" => Object::Reference(form) },
        };
        let ops = vec![Operation::new("Do", vec![Object::Name(b"Fm".to_vec())])];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
            "fixture must paint when the layer is not hidden"
        );

        // The search-index path still descends: hidden is not absent (§8.11.2 bars
        // DRAWING, not indexing), matching the `Tj` arm's render-mode-3 policy.
        let (_, texts) = run(true);
        assert!(texts > 0, "a hidden layer's text must stay searchable");
    }

    /// §11.6.6: on entering a transparency group the alpha constants reset to 1.0,
    /// because `/ca` applies ONCE to the group's composited result. That reset is
    /// gated on `pushed_group`, so making the group push mutually exclusive with
    /// the soft-mask bracket silently disabled it — precisely the failure the
    /// comment above it warns about. A form with BOTH `/ca` < 1 and an `/SMask`
    /// then applied `ca` to every element inside, over-darkening wherever that
    /// content overlaps itself. Reported by `residuals`' interaction review.
    #[test]
    fn group_alpha_resets_even_when_a_soft_mask_is_active() {
        let mut doc = Document::with_version("1.7");
        let mask_group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "Group" => dictionary! { "S" => "Transparency", "CS" => "DeviceGray" },
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            },
            b"1 g 0 0 100 100 re f".to_vec(),
        ));
        let egs = doc.add_object(dictionary! {
            "Type" => "ExtGState",
            "ca" => 0.5,
            "SMask" => dictionary! {
                "S" => "Luminosity",
                "G" => Object::Reference(mask_group),
            },
        });
        // Two OVERLAPPING fills: per-element and per-group alpha agree everywhere
        // else, so overlap is the only shape that can witness the bug.
        let form = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "Group" => dictionary! { "S" => "Transparency" },
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            },
            b"0 0 60 60 re f 40 40 60 60 re f".to_vec(),
        ));
        let res = dictionary! {
            "ExtGState" => dictionary! { "GS" => Object::Reference(egs) },
            "XObject" => dictionary! { "Fm" => Object::Reference(form) },
        };
        let ops = vec![
            Operation::new("gs", vec![Object::Name(b"GS".to_vec())]),
            Operation::new("Do", vec![Object::Name(b"Fm".to_vec())]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);

        let mask = prims
            .iter()
            .position(|p| matches!(p, Prim::SoftMaskPush { .. }))
            .expect("the soft mask must bracket the form");
        let push = prims
            .iter()
            .position(|p| matches!(p, Prim::GroupPush { .. }))
            .expect("a transparency group must still be pushed when a soft mask is active");
        // §11.6.5.1 order: the group composites first, inside the masked layer.
        assert!(mask < push, "SoftMaskPush at {mask} must precede GroupPush at {push}");
        let Prim::GroupPush { alpha, .. } = &prims[push] else { unreachable!() };
        assert!((*alpha - 0.5).abs() < 1e-6, "group carries alpha {alpha}, expected /ca 0.5");

        // ...so `ca` must NOT also be baked into the elements inside the group.
        // Alpha rides in the top byte of `argb`.
        let group_end = prims[push..]
            .iter()
            .position(|p| matches!(p, Prim::GroupPop))
            .map(|i| push + i)
            .expect("the group must be popped");
        let inner: Vec<u8> = prims[push..group_end]
            .iter()
            .filter_map(|p| match p {
                Prim::Fill { argb, .. } => Some((argb >> 24) as u8),
                _ => None,
            })
            .collect();
        assert_eq!(inner.len(), 2, "both fills must reach the group");
        for a in inner {
            assert_eq!(a, 0xFF, "element alpha {a:#x} — /ca was applied twice");
        }
    }

    /// (§10.6.2 flatness tolerance), and `bezier_steps_for_flatness` already
    /// consumes it — it was the one Table 58 key with plumbing but no parser, so a
    /// document that set flatness through `gs` got the default tolerance.
    #[test]
    fn extgstate_fl_changes_curve_flattening() {
        let flatten = |fl: Option<f64>| -> usize {
            let mut doc = Document::with_version("1.7");
            let gs_id = doc.add_object(match fl {
                Some(v) => dictionary! { "FL" => v },
                None => dictionary! {},
            });
            let res = dictionary! {
                "ExtGState" => dictionary! { "GS1" => Object::Reference(gs_id) },
            };
            let ops = vec![
                Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
                Operation::new("m", vec![0.into(), 0.into()]),
                Operation::new(
                    "c",
                    vec![0.into(), 800.into(), 800.into(), 800.into(), 800.into(), 0.into()],
                ),
                Operation::new("S", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
            prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Stroke { pts, .. } => Some(pts.len()),
                    _ => None,
                })
                .max()
                .unwrap_or(0)
        };
        let fine = flatten(None);
        let coarse = flatten(Some(3.0));
        assert!(fine > 1, "the curve must be flattened at all");
        assert!(
            coarse < fine,
            "a coarser /FL must flatten to fewer segments, got {coarse} vs {fine}"
        );
    }

    /// §8.4.5 Table 58 `/Font` is `[font size]` with `font` an indirect reference to
    /// a font dictionary — the graphics-state equivalent of `Tf`, referencing a font
    /// the resource dictionary need not name. Unparsed, `gs.font_key` stayed empty
    /// and `show_string` took its no-metrics branch: the whole run collapsed to ONE
    /// primitive at the origin with a guessed advance, instead of one per glyph
    /// placed from `/Widths`.
    #[test]
    fn extgstate_font_selects_a_font_without_a_resource_name() {
        let mut doc = Document::with_version("1.7");
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "Type1",
            "BaseFont" => "Helvetica",
            "FirstChar" => 65,
            "LastChar" => 66,
            "Widths" => vec![1000.into(), 1000.into()],
        });
        let gs_id = doc.add_object(dictionary! {
            "Font" => vec![Object::Reference(font_id), 12.into()],
        });
        // No /Font entry in the resources at all: `/Font` is precisely for this.
        let res = dictionary! {
            "ExtGState" => dictionary! { "GS1" => Object::Reference(gs_id) },
        };
        let ops = vec![
            Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
            Operation::new("BT", vec![]),
            Operation::new("Tj", vec![Object::string_literal("AB")]),
            Operation::new("ET", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        let texts: Vec<&Prim> = prims.iter().filter(|p| matches!(p, Prim::Text { .. })).collect();
        assert_eq!(texts.len(), 2, "one primitive per glyph, placed from /Widths");
        // /Widths 1000 at size 12 is a 12-unit advance, not the 0.5-em-per-byte guess.
        for t in &texts {
            if let Prim::Text { size, advance, .. } = t {
                assert!((*size - 12.0).abs() < 1e-3, "size comes from /Font, got {size}");
                assert!((*advance - 12.0).abs() < 0.5, "advance from /Widths, got {advance}");
            }
        }
        // A dangling /Font reference must leave the previous selection alone rather
        // than blanking it, so the text does not disappear on a malformed file.
        let bad_gs = doc.add_object(dictionary! {
            "Font" => vec![Object::Reference((9999, 0)), 12.into()],
        });
        let res2 = dictionary! {
            "Font" => dictionary! { "F1" => Object::Reference(font_id) },
            "ExtGState" => dictionary! { "GS2" => Object::Reference(bad_gs) },
        };
        let ops2 = vec![
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            Operation::new("gs", vec![Object::Name(b"GS2".to_vec())]),
            Operation::new("BT", vec![]),
            Operation::new("Tj", vec![Object::string_literal("AB")]),
            Operation::new("ET", vec![]),
        ];
        let mut prims2 = Vec::new();
        interpret_content(&doc, &ops2, Some(&res2), GraphicsState::default(), &mut prims2, 0, false);
        assert_eq!(
            prims2.iter().filter(|p| matches!(p, Prim::Text { .. })).count(),
            2,
            "a dangling /Font must not discard the font Tf already selected"
        );
    }
}

#[cfg(test)]
mod stroke_pattern_tests {
    use super::stroke_outline_quads;

    // A single horizontal segment yields one segment quad plus two vertex
    // squares, all offset by the half width.
    #[test]
    fn horizontal_segment_quad_offsets_by_half_width() {
        let sp = vec![vec![(0.0, 0.0), (10.0, 0.0)]];
        let quads = stroke_outline_quads(&sp, 2.0);
        // 1 segment quad + 2 vertex squares.
        assert_eq!(quads.len(), 3);
        let seg = &quads[0];
        assert_eq!(seg.len(), 4);
        // Normal to a horizontal segment is vertical: y offset = +/-hw.
        assert!(seg.iter().any(|&(_, y)| (y - 2.0).abs() < 1e-9));
        assert!(seg.iter().any(|&(_, y)| (y + 2.0).abs() < 1e-9));
    }

    // Zero-length segments are skipped (no NaN normals), but the vertex square
    // still covers the point.
    #[test]
    fn degenerate_segment_is_skipped() {
        let sp = vec![vec![(5.0, 5.0), (5.0, 5.0)]];
        let quads = stroke_outline_quads(&sp, 1.0);
        // No segment quad, just the two coincident vertex squares.
        assert_eq!(quads.len(), 2);
    }
}
