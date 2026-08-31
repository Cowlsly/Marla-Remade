use crate::*;

pub(crate) fn rgb_to_argb(r: f64, g: f64, b: f64) -> u32 {
    let c = |v: f64| (v.clamp(0.0, 1.0) * 255.0).round() as u32;
    0xFF00_0000 | (c(r) << 16) | (c(g) << 8) | c(b)
}

pub(crate) fn gray_to_argb(v: f64) -> u32 {
    rgb_to_argb(v, v, v)
}

pub(crate) fn cmyk_to_argb(c: f64, m: f64, y: f64, k: f64) -> u32 {
    let r = (1.0 - c) * (1.0 - k);
    let g = (1.0 - m) * (1.0 - k);
    let b = (1.0 - y) * (1.0 - k);
    rgb_to_argb(r, g, b)
}

// ---------------------------------------------------------------------------
// Content-stream interpreter
// ---------------------------------------------------------------------------

#[derive(Clone, Default)]
pub(crate) enum CsKind {
    #[default]
    DeviceGray,
    DeviceRGB,
    DeviceCMYK,
    Lab { white: [f64;3], range: [[f64;2];2] },
    Separation { name: Vec<u8>, alt: Box<CsKind>, tint_fn: Option<PdfFunction> },
    DeviceN { names: Vec<Vec<u8>>, alt: Box<CsKind>, tint_fn: Option<PdfFunction> },
    Pattern { base: Option<Box<CsKind>> },
    Indexed { base: Box<CsKind>, lookup: Vec<u8>, base_ncomp: u8, hival: u16 },
    ICCBased { n: u8, alt: Option<Box<CsKind>> },
    CalRGB { white: [f64;3], gamma: [f64;3], matrix: [[f64;3];3] },
    /// CIE-based grey (§8.6.5.2). `/BlackPoint` is parsed by nobody and ignored: the
    /// conversion below adapts from `/WhitePoint` only, so a non-default black point
    /// shifts the darkest tones slightly. Recorded here rather than kept as an
    /// always-`None` field that reads as if the support were half-written.
    CalGray { white: [f64;3], gamma: f64 },
}

pub(crate) fn colorspaces_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(cs)) = res_dict.get(b"ColorSpace").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in cs.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

pub(crate) fn shadings_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(sh)) = res_dict.get(b"Shading").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in sh.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

/// Map `/Pattern` resource names to their object ids (mirrors
/// [`shadings_from_resources`]). Both tiling (PatternType 1) and shading
/// (PatternType 2) patterns are indirect objects.
pub(crate) fn patterns_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(pat)) = res_dict.get(b"Pattern").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in pat.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

// Parse a colorspace object (Name or Array) into CsKind, using resources map for named entries
/// Depth ceiling for colour-space nesting. §8.6 nests only a handful of levels deep in
/// practice (Indexed over ICCBased over an /Alternate, say), so this only ever stops a
/// cycle. `[/Indexed 5 0 R 255 <00FF00>]` stored AS object 5 makes the base resolve back
/// to the same array, and the plain per-name self-reference guard cannot see it because
/// the cycle runs through the array element rather than a name. That recursed until the
/// stack overflowed, which unlike a panic cannot be caught and takes the process with it.
const MAX_CS_DEPTH: u32 = 16;

pub(crate) fn parse_cs_kind(doc: &Document, cs_obj: Option<&Object>, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<CsKind> {
    parse_cs_kind_at(doc, cs_obj, cs_resources, 0)
}

fn parse_cs_kind_at(doc: &Document, cs_obj: Option<&Object>, cs_resources: &HashMap<Vec<u8>, ObjectId>, depth: u32) -> Option<CsKind> {
    if depth >= MAX_CS_DEPTH {
        return None;
    }
    let obj = cs_obj?;
    // If Name, check if it's a resource reference
    if let Object::Name(name) = obj {
        // A named colorspace from the resource dictionary. The resolved object is
        // normally an Array (e.g. [/ICCBased ...], [/Separation ...]); it may also
        // be an indirect reference to one. (The previous `get_dictionary` check
        // rejected arrays outright, silently dropping the colorspace to gray.)
        if let Some(&id) = cs_resources.get(name) {
            if let Ok(resolved) = doc.get_object(id) {
                match resolved {
                    Object::Array(arr) => return parse_cs_array_at(doc, arr, cs_resources, depth + 1),
                    // Guard against a name that resolves to itself.
                    Object::Name(n2) if n2 != name => {
                        return parse_cs_kind_at(doc, Some(resolved), cs_resources, depth + 1);
                    }
                    _ => {}
                }
            }
        }
        // Builtin names
        return match name.as_slice() {
            b"DeviceRGB" | b"RGB" => Some(CsKind::DeviceRGB),
            b"DeviceCMYK" | b"CMYK" => Some(CsKind::DeviceCMYK),
            b"DeviceGray" | b"Gray" | b"G" => Some(CsKind::DeviceGray),
            b"Pattern" => Some(CsKind::Pattern { base: None }),
            _ => None,
        }
    }
    if let Object::Array(arr) = obj {
        return parse_cs_array_at(doc, arr, cs_resources, depth + 1);
    }
    // If Reference, deref
    if let Some(deref_obj) = deref(doc, obj) {
        return parse_cs_kind_at(doc, Some(deref_obj), cs_resources, depth + 1);
    }
    None
}

/// Resolve a colorspace operand for the `cs`/`CS` operators. Named entries in
/// the page `/Resources /ColorSpace` dict are honored whether they are stored as
/// a direct array (e.g. `/Cs0 [/ICCBased 5 0 R]`) or an indirect reference — the
/// pre-built id map only captures the reference form, so a direct array would
/// otherwise fall through to the DeviceGray default and render colors as gray.
pub(crate) fn parse_named_cs(
    doc: &Document,
    cs_obj: &Object,
    resources: Option<&lopdf::Dictionary>,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
) -> Option<CsKind> {
    if let Object::Name(name) = cs_obj {
        // Device builtins take precedence and never live in the resource dict.
        match name.as_slice() {
            b"DeviceRGB" | b"RGB" => return Some(CsKind::DeviceRGB),
            b"DeviceCMYK" | b"CMYK" => return Some(CsKind::DeviceCMYK),
            b"DeviceGray" | b"Gray" | b"G" => return Some(CsKind::DeviceGray),
            b"Pattern" => return Some(CsKind::Pattern { base: None }),
            _ => {}
        }
        if let Some(res) = resources {
            if let Some(Object::Dictionary(csd)) = res.get(b"ColorSpace").ok().and_then(|o| deref(doc, o)) {
                if let Ok(entry) = csd.get(name) {
                    if let Some(k) = parse_cs_kind(doc, Some(entry), cs_resources) {
                        return Some(k);
                    }
                }
            }
        }
    }
    parse_cs_kind(doc, Some(cs_obj), cs_resources)
}

fn parse_cs_array_at(doc: &Document, arr: &[Object], cs_resources: &HashMap<Vec<u8>, ObjectId>, depth: u32) -> Option<CsKind> {
    if depth >= MAX_CS_DEPTH {
        return None;
    }
    // Every nested colour space below goes through `parse_cs_kind_at` so the depth keeps
    // accumulating across the array/kind boundary; a cycle alternates between the two.
    let parse_cs_kind = |doc: &Document, o: Option<&Object>, r: &HashMap<Vec<u8>, ObjectId>| {
        parse_cs_kind_at(doc, o, r, depth + 1)
    };
    // arr head is name
    let head = arr.first().and_then(|o| o.as_name().ok()).unwrap_or(b"");
    match head {
        b"DeviceRGB" | b"RGB" => Some(CsKind::DeviceRGB),
        b"DeviceCMYK" | b"CMYK" => Some(CsKind::DeviceCMYK),
        b"DeviceGray" | b"G" | b"Gray" => Some(CsKind::DeviceGray),
        b"Pattern" => Some(CsKind::Pattern {
            // [ /Pattern baseColorSpace ] for uncolored (PaintType 2) patterns.
            base: arr.get(1).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).map(Box::new),
        }),
        b"CalRGB" => {
            // [ /CalRGB dict ]
            let dict = arr.get(1).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok());
            if let Some(d) = dict {
                let white = read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let gamma = read_gamma_rgb(d).unwrap_or([1.0,1.0,1.0]);
                let matrix = read_matrix_cal(d).unwrap_or([[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]);
                Some(CsKind::CalRGB { white, gamma, matrix })
            } else {
                Some(CsKind::DeviceRGB)
            }
        }
        b"CalGray" => {
            let dict = arr.get(1).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok());
            if let Some(d) = dict {
                let white = read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let gamma = d.get(b"Gamma").ok().and_then(num).filter(|g| g.is_finite()).unwrap_or(1.0);
                Some(CsKind::CalGray { white, gamma })
            } else {
                Some(CsKind::DeviceGray)
            }
        }
        b"Lab" => {
            // [ /Lab dict ]
            let dict = arr.get(1).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok());
            if let Some(d) = dict {
                let white = read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let range = read_lab_range(d).unwrap_or([[ -100.0, 100.0],[ -100.0, 100.0]]);
                Some(CsKind::Lab { white, range })
            } else {
                Some(CsKind::Lab { white: [0.9505,1.0,1.0890], range: [[ -100.0,100.0],[ -100.0,100.0]] })
            }
        }
        b"ICCBased" => {
            let dict_obj = arr.get(1).and_then(|o| deref(doc, o));
            // alt colorspace in dict /Alternate
            let alt = match dict_obj {
                Some(Object::Stream(s)) => s
                    .dict
                    .get(b"Alternate")
                    .ok()
                    .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
                    .map(Box::new),
                Some(Object::Dictionary(d)) => d
                    .get(b"Alternate")
                    .ok()
                    .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
                    .map(Box::new),
                _ => None,
            };
            // /N is required (§8.6.5.5) and must be 1, 3 or 4. When it is missing or
            // bogus, infer from /Alternate rather than defaulting to 1: a wrong
            // component count shifts every sample in an image and yields the
            // distinctive diagonal-rainbow garbage. Accept a bare dictionary too,
            // which `colorspace_info` already tolerated.
            let declared = match dict_obj {
                Some(Object::Stream(s)) => s.dict.get(b"N").ok().and_then(num),
                Some(Object::Dictionary(d)) => d.get(b"N").ok().and_then(num),
                _ => None,
            };
            let n = match declared {
                Some(v) if matches!(v as u8, 1 | 3 | 4) => v as u8,
                _ => alt.as_ref().map(|a| cs_kind_ncomp(a)).unwrap_or(3),
            };
            Some(CsKind::ICCBased { n: n.max(1), alt })
        }
        b"Indexed" | b"I" => {
            // [ /Indexed base hival lookup ]
            let base = arr.get(1).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceRGB);
            let base_n = cs_kind_ncomp(&base);
            let hival = arr.get(2).and_then(|o| deref(doc, o)).as_ref().and_then(|o| o.as_i64().ok())
                .unwrap_or(255).clamp(0, 65535) as u16;
            let lookup = match arr.get(3).and_then(|o| deref(doc, o)) {
                Some(Object::String(s,_)) => s.clone(),
                // NOT `decompressed_content()`. lopdf 0.36 implements only
                // Flate/LZW/ASCII85, so RunLength or ASCIIHex hits the `Err` arm and the
                // old fallback handed back the still-ENCODED bytes AS THE PALETTE. Worse,
                // it reads /DecodeParms with `as_dict()`, so an INDIRECT or ARRAY
                // /DecodeParms makes it return `Ok` with the PREDICTOR NEVER APPLIED —
                // a success carrying garbage, which no `unwrap_or_else` can catch.
                // Either way §8.6.6.3's palette is nonsense and every colour in the
                // image is wrong. `stream_data_with_doc` runs our own chain.
                Some(Object::Stream(s)) => stream_data_with_doc(doc, s),
                _ => Vec::new(),
            };
            Some(CsKind::Indexed { base: Box::new(base), lookup, base_ncomp: base_n, hival })
        }
        b"Separation" => {
            // [ /Separation name alt tintTransform ]
            let name = arr.get(1).and_then(|o| o.as_name().ok()).unwrap_or(b"").to_vec();
            let alt = arr.get(2).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceGray);
            let tint_fn = arr.get(3).and_then(|o| PdfFunction::parse(doc, o));
            Some(CsKind::Separation { name, alt: Box::new(alt), tint_fn })
        }
        b"DeviceN" => {
            let names = arr.get(1).and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok()).map(|a| a.iter().filter_map(|obj| obj.as_name().ok().map(|n| n.to_vec())).collect()).unwrap_or_default();
            let alt = arr.get(2).and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceGray);
            let tint_fn = arr.get(3).and_then(|o| PdfFunction::parse(doc, o));
            Some(CsKind::DeviceN { names, alt: Box::new(alt), tint_fn })
        }
        _ => None,
    }
}

pub(crate) fn cs_kind_ncomp(kind: &CsKind) -> u8 {
    match kind {
        CsKind::DeviceGray => 1,
        CsKind::DeviceRGB => 3,
        CsKind::DeviceCMYK => 4,
        CsKind::Lab { .. } => 3,
        CsKind::CalRGB { .. } => 3,
        CsKind::CalGray { .. } => 1,
        CsKind::ICCBased { n, .. } => *n,
        CsKind::Indexed { base_ncomp, .. } => *base_ncomp,
        CsKind::Separation { .. } => 1,
        CsKind::DeviceN { names, .. } => names.len() as u8,
        CsKind::Pattern { .. } => 0,
    }
}

/// Component count for an IMAGE sample in this colour space. Identical to
/// [`cs_kind_ncomp`] except for Indexed, where one image sample is a single
/// palette index (§8.6.6.3), not `base_ncomp` colour components.
///
/// Deliberately separate from [`cs_kind_ncomp`], which reports the BASE arity for
/// Indexed because that is what the palette decode needs. Clamped to 1..=32 so a bogus
/// `/N` or a 255-name `/DeviceN` cannot turn `w*h*ncomp` into a huge allocation; Pattern
/// (0 components, illegal for an image per §8.9.5.1) recovers as 1.
pub(crate) fn cs_kind_image_ncomp(kind: &CsKind) -> u8 {
    let n = match kind {
        CsKind::Indexed { .. } => 1,
        other => cs_kind_ncomp(other),
    };
    n.clamp(1, 32)
}

/// Initial color value when a color space is selected via `cs`/`CS` (PDF 8.6.8):
/// black for device/CIE/ICC spaces, index 0 for Indexed, full tint (all 1.0) for
/// Separation/DeviceN. Returns `None` for Pattern (color unchanged).
pub(crate) fn cs_initial_color(doc: &Document, kind: &CsKind, resources: &HashMap<Vec<u8>, ObjectId>) -> Option<u32> {
    let comps: Vec<f64> = match kind {
        CsKind::Separation { .. } => vec![1.0],
        CsKind::DeviceN { names, .. } => vec![1.0; names.len().max(1)],
        CsKind::Indexed { .. } => vec![0.0],
        CsKind::Pattern { .. } => return None,
        _ => vec![0.0; cs_kind_ncomp(kind).max(1) as usize],
    };
    eval_cs_to_rgb(doc, kind, &comps, resources)
}

/// Default per-component value range for a color space, used to decode Indexed
/// palette bytes and image samples. All spaces use [0,1] except Lab, whose L is
/// [0,100] and a*/b* follow the space's /Range.
pub(crate) fn cs_kind_default_decode(kind: &CsKind) -> Vec<(f64, f64)> {
    match kind {
        CsKind::Lab { range, .. } => vec![
            (0.0, 100.0),
            (range[0][0], range[0][1]),
            (range[1][0], range[1][1]),
        ],
        _ => vec![(0.0, 1.0); cs_kind_ncomp(kind) as usize],
    }
}

/// The Cal*/Lab dictionary readers below all feed `.unwrap_or(<spec default>)` at their
/// call sites, so rejecting a malformed entry makes it mean exactly what an ABSENT one
/// means. That matters for NON-FINITE values specifically: a real like 1e40 overflows
/// the f32 `Object::Real` holds, and an infinite /WhitePoint or /Matrix propagates
/// through `adapt_to_d65` (inf/inf) into NaN, which `rgb_to_argb` saturates to 0 — a
/// CalRGB image rendering as a solid black rectangle. Falling back to the default
/// renders the picture instead.
fn all_finite(v: &[f64]) -> bool {
    v.iter().all(|x| x.is_finite())
}

pub(crate) fn read_white_point(dict: &lopdf::Dictionary) -> Option<[f64;3]> {
    let arr = dict.get(b"WhitePoint").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=3 {
        let wp = [num(&arr[0])?, num(&arr[1])?, num(&arr[2])?];
        // A zero or negative Y also breaks the adaptation; §8.6.5.2 requires X and Z
        // positive and Y exactly 1.
        if all_finite(&wp) && wp[0] > 0.0 && wp[1] > 0.0 && wp[2] > 0.0 {
            Some(wp)
        } else {
            None
        }
    } else { None }
}

pub(crate) fn read_gamma_rgb(dict: &lopdf::Dictionary) -> Option<[f64;3]> {
    let arr = dict.get(b"Gamma").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=3 {
        let g = [num(&arr[0])?, num(&arr[1])?, num(&arr[2])?];
        if all_finite(&g) { Some(g) } else { None }
    } else { None }
}

pub(crate) fn read_matrix_cal(dict: &lopdf::Dictionary) -> Option<[[f64;3];3]> {
    let arr = dict.get(b"Matrix").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=9 {
        // PDF stores the CalRGB Matrix column-major as
        // [XA YA ZA  XB YB ZB  XC YC ZC], defining
        //   X = XA·A + XB·B + XC·C, Y = YA·A + …, Z = ZA·A + …
        // eval_cs_to_rgb multiplies rows against (A,B,C), so store it as rows
        // [[XA XB XC],[YA YB YC],[ZA ZB ZC]] (i.e. transposed from the array
        // order). Storing it in raw array order transposes the transform and
        // turns e.g. CalRGB white into cyan (issue #321 colorrenderexample).
        let m = [
            [num(&arr[0])?, num(&arr[3])?, num(&arr[6])?],
            [num(&arr[1])?, num(&arr[4])?, num(&arr[7])?],
            [num(&arr[2])?, num(&arr[5])?, num(&arr[8])?],
        ];
        if m.iter().all(|row| all_finite(row)) { Some(m) } else { None }
    } else { None }
}

pub(crate) fn read_lab_range(dict: &lopdf::Dictionary) -> Option<[[f64;2];2]> {
    let arr = dict.get(b"Range").ok().and_then(|o| o.as_array().ok())?;
    if arr.len()>=4 {
        let r = [num(&arr[0])?, num(&arr[1])?, num(&arr[2])?, num(&arr[3])?];
        // /Range also seeds the default /Decode for a Lab image (§8.9.5.2 Table 90),
        // so a non-finite bound would scale every sample to NaN.
        if all_finite(&r) { Some([[r[0], r[1]],[r[2], r[3]]]) } else { None }
    } else { None }
}

/// Bradford chromatic adaptation of an XYZ triple from `src_white` to the D65
/// white used by the sRGB matrix. Used for CalRGB/CalGray with non-D65 whites.
fn adapt_to_d65(x: f64, y: f64, z: f64, src_white: [f64; 3]) -> (f64, f64, f64) {
    const B: [[f64; 3]; 3] = [
        [0.8951, 0.2664, -0.1614],
        [-0.7502, 1.7135, 0.0367],
        [0.0389, -0.0685, 1.0296],
    ];
    const BINV: [[f64; 3]; 3] = [
        [0.9869929, -0.1470543, 0.1599627],
        [0.4323053, 0.5183603, 0.0492912],
        [-0.0085287, 0.0400428, 0.9684867],
    ];
    let mul = |m: &[[f64; 3]; 3], v: [f64; 3]| {
        [
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
        ]
    };
    let d65 = [0.95047, 1.0, 1.08883];
    let s = mul(&B, src_white);
    let d = mul(&B, d65);
    let lms = mul(&B, [x, y, z]);
    let scaled = [
        lms[0] * d[0] / s[0].abs().max(1e-9),
        lms[1] * d[1] / s[1].abs().max(1e-9),
        lms[2] * d[2] / s[2].abs().max(1e-9),
    ];
    let out = mul(&BINV, scaled);
    (out[0], out[1], out[2])
}

pub(crate) fn eval_cs_to_rgb(doc: &Document, kind: &CsKind, comps: &[f64], cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<u32> {
    match kind {
        CsKind::DeviceGray => {
            let v = comps.first().copied().unwrap_or(0.0);
            Some(gray_to_argb(v))
        }
        CsKind::DeviceRGB => {
            if comps.len()>=3 {
                Some(rgb_to_argb(comps[0], comps[1], comps[2]))
            } else { None }
        }
        CsKind::DeviceCMYK => {
            if comps.len()>=4 {
                Some(cmyk_to_argb(comps[0], comps[1], comps[2], comps[3]))
            } else { None }
        }
        CsKind::Lab { white, range, .. } => {
            // PDF spec 8.6.5.4 Lab -> XYZ -> (D50->D65 adapt via Bradford) -> sRGB
            let l = comps.first().copied().unwrap_or(0.0).clamp(0.0,100.0);
            // §8.6.5.4 Table 66: /Range bounds a* and b*, and "values outside the
            // range shall be adjusted to the nearest valid value". The image path
            // already arrives in range via /Decode, but `sc`/`scn` operands and a
            // tint transform whose own /Range is absent do NOT — and an unbounded
            // a*/b* drives `fx`/`fz` far outside the cube-root branch and produces a
            // saturated primary instead of the nearest in-gamut colour.
            let clamp_pair = |v: f64, r: [f64; 2]| v.max(r[0].min(r[1])).min(r[0].max(r[1]));
            let a = clamp_pair(comps.get(1).copied().unwrap_or(0.0), range[0]);
            let b = clamp_pair(comps.get(2).copied().unwrap_or(0.0), range[1]);
            let fy = (l + 16.0)/116.0;
            let fx = a / 500.0 + fy;
            let fz = fy - b / 200.0;
            let eps = 0.008856;
            let kappa = 903.3;
            let fx3 = fx.powi(3);
            let fz3 = fz.powi(3);
            let fy3 = fy.powi(3);
            let xr = if fx3 > eps { fx3 } else { (fx - 16.0/116.0)/7.787 };
            let yr = if l > kappa*eps { fy3 } else { l/kappa };
            let zr = if fz3 > eps { fz3 } else { (fz - 16.0/116.0)/7.787 };
            let wx = white[0];
            let wy = white[1];
            let wz = white[2];
            let mut x = xr * wx;
            let mut y = yr * wy;
            let mut z = zr * wz;
            // Bradford D50->D65 adaptation (approximate)
            // Src WP approx D50 [0.96422,1.0,0.82521] is already `white` per spec; dest D65 0.95047,1.0,1.08883
            // Using fixed Bradford matrices for XYZ D50->D65 to improve sRGB fidelity.
            const BRAD: [[f64;3];3] = [
                [ 0.8951,  0.2664, -0.1614],
                [-0.7502,  1.7135,  0.0367],
                [ 0.0389, -0.0685,  1.0296],
            ];
            const BRAD_INV: [[f64;3];3] = [
                [ 0.9869929, -0.1470543,  0.1599627],
                [ 0.4323053,  0.5183603,  0.0492912],
                [-0.0085287,  0.0400428,  0.9684867],
            ];
            // CIE reference white point of the DESTINATION. The Lab source white is the
            // space's own /WhitePoint (`white`) per §8.6.5.4, not a fixed D50, which is
            // why only the D65 trio is needed here.
            const LMS_D65_X: f64 = 0.95047;
            const LMS_D65_Y: f64 = 1.0;
            const LMS_D65_Z: f64 = 1.08883;
            // LMS = BRAD * XYZ
            let lms_src = [
                BRAD[0][0]*x + BRAD[0][1]*y + BRAD[0][2]*z,
                BRAD[1][0]*x + BRAD[1][1]*y + BRAD[1][2]*z,
                BRAD[2][0]*x + BRAD[2][1]*y + BRAD[2][2]*z,
            ];
            // White in LMS
            let src_wp_lms = [
                BRAD[0][0]*wx + BRAD[0][1]*wy + BRAD[0][2]*wz,
                BRAD[1][0]*wx + BRAD[1][1]*wy + BRAD[1][2]*wz,
                BRAD[2][0]*wx + BRAD[2][1]*wy + BRAD[2][2]*wz,
            ];
            let dst_wp_lms = [
                BRAD[0][0]*LMS_D65_X + BRAD[0][1]*LMS_D65_Y + BRAD[0][2]*LMS_D65_Z,
                BRAD[1][0]*LMS_D65_X + BRAD[1][1]*LMS_D65_Y + BRAD[1][2]*LMS_D65_Z,
                BRAD[2][0]*LMS_D65_X + BRAD[2][1]*LMS_D65_Y + BRAD[2][2]*LMS_D65_Z,
            ];
            let scale = [
                if src_wp_lms[0].abs() > 1e-9 { dst_wp_lms[0]/src_wp_lms[0] } else { 1.0 },
                if src_wp_lms[1].abs() > 1e-9 { dst_wp_lms[1]/src_wp_lms[1] } else { 1.0 },
                if src_wp_lms[2].abs() > 1e-9 { dst_wp_lms[2]/src_wp_lms[2] } else { 1.0 },
            ];
            let lms_ad = [lms_src[0]*scale[0], lms_src[1]*scale[1], lms_src[2]*scale[2]];
            x = BRAD_INV[0][0]*lms_ad[0] + BRAD_INV[0][1]*lms_ad[1] + BRAD_INV[0][2]*lms_ad[2];
            y = BRAD_INV[1][0]*lms_ad[0] + BRAD_INV[1][1]*lms_ad[1] + BRAD_INV[1][2]*lms_ad[2];
            z = BRAD_INV[2][0]*lms_ad[0] + BRAD_INV[2][1]*lms_ad[1] + BRAD_INV[2][2]*lms_ad[2];
            // XYZ D65 -> linear sRGB
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma = |u: f64| -> f64 {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma(r_lin), gamma(g_lin), gamma(b_lin)))
        }
        CsKind::CalRGB { white, gamma, matrix } => {
            // CalRGB: A^GammaR, B^GammaG, C^GammaB -> XYZ via Matrix, then adapt
            // from the space's /WhitePoint to D65 before XYZ -> sRGB (PDF 8.6.5.3).
            let a = comps.first().copied().unwrap_or(0.0).clamp(0.0,1.0).powf(gamma[0].clamp(0.1,10.0));
            let b = comps.get(1).copied().unwrap_or(0.0).clamp(0.0,1.0).powf(gamma[1].clamp(0.1,10.0));
            let c = comps.get(2).copied().unwrap_or(0.0).clamp(0.0,1.0).powf(gamma[2].clamp(0.1,10.0));
            let x = matrix[0][0]*a + matrix[0][1]*b + matrix[0][2]*c;
            let y = matrix[1][0]*a + matrix[1][1]*b + matrix[1][2]*c;
            let z = matrix[2][0]*a + matrix[2][1]*b + matrix[2][2]*c;
            let (x, y, z) = adapt_to_d65(x, y, z, *white);
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma_corr = |u: f64| -> f64 {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma_corr(r_lin), gamma_corr(g_lin), gamma_corr(b_lin)))
        }
        CsKind::CalGray { gamma, white, .. } => {
            let g = comps.first().copied().unwrap_or(0.0).clamp(0.0,1.0);
            let a = g.powf(gamma.clamp(0.1,10.0));
            // Scale the whitepoint by the gray value, then adapt to D65.
            let (x, y, z) = adapt_to_d65(white[0]*a, white[1]*a, white[2]*a, *white);
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma_corr = |u: f64| {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma_corr(r_lin), gamma_corr(g_lin), gamma_corr(b_lin)))
        }
        CsKind::ICCBased { n, alt } => {
            // Use alt if present — already handles Separation/DeviceN alt may be device (fast path)
            if let Some(alt_kind) = alt {
                if let Some(rgb) = eval_cs_to_rgb(doc, alt_kind, comps, cs_resources) {
                    return Some(rgb);
                }
            }
            // P0 fix critical #3: ICCBased had no ICC handling, silent fallback. Use alt or component-count fallback but warn.
            // Ideally parse ICC profile, but use RGB/Gray/CMYK by N with alpha-preserved alt lookup.
            match n {
                1 => {
                    let v = comps.first().copied().unwrap_or(0.0);
                    Some(gray_to_argb(v))
                }
                3 => {
                    if comps.len() >= 3 { Some(rgb_to_argb(comps[0], comps[1], comps[2])) } else { None }
                }
                4 => {
                    if comps.len() >= 4 { Some(cmyk_to_argb(comps[0], comps[1], comps[2], comps[3])) } else { None }
                }
                _ => None,
            }
        }
        CsKind::Indexed { base, lookup, base_ncomp, hival } => {
            let idx = (comps.first().copied().unwrap_or(0.0) as usize).clamp(0, *hival as usize);
            let off = idx * *base_ncomp as usize;
            if off + *base_ncomp as usize <= lookup.len() {
                let slice = &lookup[off..off+*base_ncomp as usize];
                // Each lookup byte 0..255 maps to the RANGE of the corresponding base
                // component (PDF 8.6.6.3): [0,1] for device spaces, but [0,100]/Range
                // for a Lab base — dividing by 255 unconditionally would darken Lab.
                let ranges = cs_kind_default_decode(base);
                let comps_f: Vec<f64> = slice.iter().enumerate().map(|(i, b)| {
                    let (lo, hi) = ranges.get(i).copied().unwrap_or((0.0, 1.0));
                    lo + (*b as f64 / 255.0) * (hi - lo)
                }).collect();
                eval_cs_to_rgb(doc, base, &comps_f, cs_resources)
            } else {
                None
            }
        }
        CsKind::Separation { name, alt, tint_fn } => {
            // The special colorant /None produces no marks (fully transparent).
            if name == b"None" {
                return Some(0x0000_0000);
            }
            let t = comps.first().copied().unwrap_or(1.0).clamp(0.0, 1.0);
            if let Some(tf) = tint_fn {
                let alt_comps = tf.eval(&[t]);
                // The tint transform must yield one value per component of the
                // alternate space (§8.6.6.4). A short return means a broken function;
                // passing it through would silently paint the wrong colour, so fall
                // back instead. (audit-e owns making well-formed Type 2/3 functions
                // return the full /Range arity; this is the backstop, not the fix.)
                if alt_comps.len() >= cs_kind_ncomp(alt).max(1) as usize {
                    if let Some(rgb) = eval_cs_to_rgb(doc, alt, &alt_comps, cs_resources) {
                        return Some(rgb);
                    }
                }
            }
            // No usable tint transform. Tint is SUBTRACTIVE per §8.6.6.4: 0 means no
            // colorant and 1 means maximum, so it must DARKEN. The old code fed `t`
            // straight into the alternate space, and for a DeviceGray/CalGray alternate
            // (which is also what a missing /Alternate defaults to) that inverted the
            // ramp — maximum ink rendered as WHITE, making spot-colour artwork
            // invisible on a white page. The correctly-polarised fallback below already
            // existed but was unreachable, because DeviceGray always returned Some.
            Some(gray_to_argb(1.0 - t))
        }
        CsKind::DeviceN { names, alt, tint_fn } => {
            // If every colorant is /None the region produces no marks.
            if !names.is_empty() && names.iter().all(|n| n == b"None") {
                return Some(0x0000_0000);
            }
            if let Some(tf) = tint_fn {
                // Evaluate the tint transform over all N input components.
                let alt_comps = tf.eval(comps);
                // Same arity check the Separation arm applies (§8.6.6.5 defers to
                // §8.6.6.4 for the tint transform): the function shall yield one value
                // per component of the alternate space. Without this the DeviceN arm
                // returned whatever `eval_cs_to_rgb` made of a short tuple — `None` for
                // an RGB/CMYK alternate, which leaves the PREVIOUS fill colour in place,
                // and plain black for a Gray alternate. Falling through to the
                // subtractive ramp below at least keeps the ink polarity right.
                if alt_comps.len() >= cs_kind_ncomp(alt).max(1) as usize {
                    if let Some(rgb) = eval_cs_to_rgb(doc, alt, &alt_comps, cs_resources) {
                        return Some(rgb);
                    }
                }
            }
            // No usable tint transform: treat each colorant as an independent
            // subtractive ink so all components contribute (0 tint = white, full
            // tint = darker).
            let light: f64 = comps.iter().map(|c| 1.0 - c.clamp(0.0, 1.0)).product();
            Some(gray_to_argb(light))
        }
        CsKind::Pattern { .. } => {
            // Pattern color handling: SCN may include base color, we already evaluated base if comps present
            // For pattern-only, we have no color - return None to keep current
            None
        }
    }
}




/// Resolve the page's (inherited) `/Resources` as an owned dictionary.
pub(crate) fn resources_dict(doc: &Document, page_id: ObjectId) -> Option<lopdf::Dictionary> {
    inherited(doc, page_id, b"Resources")
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned()
}

/// Collect the `/Filter` names of a stream (single name or array).
pub(crate) fn filter_names(doc: &Document, dict: &lopdf::Dictionary) -> Vec<String> {
    match dict.get(b"Filter").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Name(n)) => vec![String::from_utf8_lossy(n).into_owned()],
        Some(Object::Array(a)) => a
            .iter()
            .filter_map(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned())
            .collect(),
        _ => Vec::new(),
    }
}

/// Number of color components for a colorspace object, plus an optional Indexed
/// palette `(base_components, lookup_bytes)`.
/// Number of color components for a colorspace object, plus an optional Indexed
/// palette `(base_components, lookup_bytes)`. Now also handles Lab, Pattern etc returning fallback.
pub(crate) fn colorspace_info(
    doc: &Document,
    cs: Option<&Object>,
) -> (u8, Option<(u8, Vec<u8>)>) {
    colorspace_info_at(doc, cs, 0)
}

/// `MAX_CS_DEPTH` applies here for exactly the reason given at its definition, which
/// this second, parallel implementation of the same walk did not inherit:
/// `[/Indexed 5 0 R 255 <00FF00>]` stored AS object 5 makes the `Indexed` arm below
/// recurse on a reference that resolves back to the same array, forever. A Rust stack
/// overflow is not a panic and cannot be caught, so it takes the process with it.
fn colorspace_info_at(
    doc: &Document,
    cs: Option<&Object>,
    depth: u32,
) -> (u8, Option<(u8, Vec<u8>)>) {
    if depth >= MAX_CS_DEPTH {
        return (1, None);
    }
    let cs = match cs.and_then(|o| deref(doc, o)) {
        Some(o) => o,
        None => return (1, None),
    };
    match cs {
        Object::Name(n) => match n.as_slice() {
            b"DeviceRGB" | b"RGB" => (3, None),
            b"DeviceCMYK" | b"CMYK" => (4, None),
            b"CalRGB" => (3, None),
            b"Lab" => (3, None),
            _ => (1, None), // DeviceGray / CalGray / fallback
        },
        Object::Array(a) => {
            let head = a.first().and_then(|o| o.as_name().ok()).unwrap_or(b"");
            match head {
                b"ICCBased" => {
                    let n = a
                        .get(1)
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| match o {
                            Object::Stream(s) => s.dict.get(b"N").ok().and_then(num),
                            Object::Dictionary(d) => d.get(b"N").ok().and_then(num),
                            _ => None,
                        })
                        .unwrap_or(1.0) as u8;
                    (n.max(1), None)
                }
                b"Indexed" | b"I" => {
                    let (base_n, _) = colorspace_info_at(doc, a.get(1), depth + 1);
                    let lookup = match a.get(3).and_then(|o| deref(doc, o)) {
                        Some(Object::String(s, _)) => s.clone(),
                        // Same reasoning as the `parse_cs_array` Indexed arm above: an
                        // indirect or array /DecodeParms makes lopdf's decoder return Ok
                        // with the predictor unapplied, so the palette is silently garbage.
                        Some(Object::Stream(s)) => stream_data_with_doc(doc, s),
                        _ => Vec::new(),
                    };
                    (1, Some((base_n, lookup)))
                }
                b"CalRGB" => (3, None),
                b"CalGray" => (1, None),
                b"Lab" => (3, None),
                b"DeviceN" => {
                    let n = a
                        .get(1)
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| o.as_array().ok())
                        .map(|arr| arr.len() as u8)
                        .unwrap_or(1);
                    (n.max(1), None)
                }
                b"Separation" => (1, None),
                b"Pattern" => (0, None),
                _ => (1, None),
            }
        }
        _ => (0, None),
    }
}


pub(crate) fn comps_to_rgb(comps: &[u8], n: u8) -> (u8, u8, u8) {
    match n {
        3 => (comps[0], comps[1], comps[2]),
        4 => {
            let c = comps[0] as f64 / 255.0;
            let m = comps[1] as f64 / 255.0;
            let y = comps[2] as f64 / 255.0;
            let k = comps[3] as f64 / 255.0;
            let r = (1.0 - c) * (1.0 - k);
            let g = (1.0 - m) * (1.0 - k);
            let b = (1.0 - y) * (1.0 - k);
            ((r * 255.0).round().clamp(0.0,255.0) as u8, (g * 255.0).round().clamp(0.0,255.0) as u8, (b * 255.0).round().clamp(0.0,255.0) as u8)
        }
        _ => {
            // includes 1 and also maybe DeviceN fallback
            if comps.is_empty() { (0,0,0) } else { (comps[0], comps[0], comps[0]) }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn separation_uses_tint_transform() {
        // A Separation colorspace over DeviceRGB whose tint transform is a
        // Type 2 exponential mapping t -> (t, 0, 0): full tint => pure red.
        let doc = Document::with_version("1.7");
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceRGB),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                range: Vec::new(),
                c0: vec![0.0, 0.0, 0.0],
                c1: vec![1.0, 0.0, 0.0],
                n: 1.0,
            }),
        };
        let res = HashMap::new();
        let argb = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).unwrap();
        assert_eq!(argb & 0x00FF_FFFF, 0x00FF_0000, "full tint should be red");
        let half = eval_cs_to_rgb(&doc, &cs, &[0.5], &res).unwrap();
        let r = (half >> 16) & 0xFF;
        assert!(r > 100 && r < 160, "half tint red channel ~128, got {r}");
    }

    // A Separation with no tint transform must DARKEN as tint rises (§8.6.6.4: 0 is
    // no colorant, 1 is maximum). Feeding the tint straight into a DeviceGray
    // alternate inverted it, so maximum ink rendered WHITE and spot-colour artwork
    // was invisible on a white page.
    #[test]
    fn separation_without_tint_darkens_with_ink() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceGray),
            tint_fn: None,
        };
        let res = HashMap::new();
        let full = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).unwrap() & 0xFF;
        let none = eval_cs_to_rgb(&doc, &cs, &[0.0], &res).unwrap() & 0xFF;
        assert_eq!(full, 0, "maximum ink must be dark, not white");
        assert_eq!(none, 255, "zero tint leaves the page white");
    }

    // A tint transform that returns fewer components than the alternate space needs
    // is broken; it must fall back to the subtractive grey ramp rather than paint a
    // wrong colour or an inverted one.
    #[test]
    fn separation_short_tint_output_falls_back() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            // DeviceCMYK needs 4 components; this Type 2 yields only 1.
            alt: Box::new(CsKind::DeviceCMYK),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                range: Vec::new(),
                c0: vec![0.0],
                c1: vec![1.0],
                n: 1.0,
            }),
        };
        let res = HashMap::new();
        let full = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).unwrap() & 0xFF;
        assert_eq!(full, 0, "short tint output must still darken");
    }

    // §8.6.5.5: ICCBased /N is required, but when it is missing the alternate space's
    // component count is a far better guess than defaulting to 1 (gray) — a wrong
    // count shifts every sample in an image.
    #[test]
    fn iccbased_without_n_infers_from_alternate() {
        use lopdf::{dictionary, Object, Stream};
        let mut doc = Document::with_version("1.7");
        let icc_id = doc.add_object(Stream::new(
            dictionary! { "Alternate" => Object::Name(b"DeviceRGB".to_vec()) },
            vec![0u8; 4],
        ));
        let arr = vec![Object::Name(b"ICCBased".to_vec()), Object::Reference(icc_id)];
        let empty = HashMap::new();
        let kind = parse_cs_array_at(&doc, &arr, &empty, 0).expect("iccbased");
        assert_eq!(cs_kind_ncomp(&kind), 3, "must infer 3 from /Alternate, not default to 1");
    }

    // An Indexed image sample is ONE palette index (§8.6.6.3), not base_ncomp colour
    // components. cs_kind_ncomp still reports base_ncomp, which is what the palette
    // decode below needs — mesh shadings special-case Indexed themselves.
    #[test]
    fn indexed_image_ncomp_is_one() {
        let cs = CsKind::Indexed {
            base: Box::new(CsKind::DeviceRGB),
            lookup: vec![0, 0, 0, 255, 255, 255],
            base_ncomp: 3,
            hival: 1,
        };
        assert_eq!(cs_kind_image_ncomp(&cs), 1, "one index per image sample");
        assert_eq!(cs_kind_ncomp(&cs), 3, "the palette decode still needs the base arity");
    }

    // A named ICCBased colorspace stored as an INDIRECT array in the resource id
    // map must resolve to RGB — not collapse to DeviceGray (the old
    // get_dictionary check rejected arrays, turning colors gray).
    #[test]
    fn named_iccbased_array_resolves_to_rgb_not_gray() {
        use lopdf::{dictionary, Object, Stream};
        let mut doc = Document::with_version("1.7");
        let icc_id = doc.add_object(Stream::new(dictionary! { "N" => 3 }, vec![0u8; 4]));
        let cs_id = doc.add_object(Object::Array(vec![
            Object::Name(b"ICCBased".to_vec()),
            Object::Reference(icc_id),
        ]));
        let mut res = HashMap::new();
        res.insert(b"CS0".to_vec(), cs_id);
        let kind = parse_cs_kind(&doc, Some(&Object::Name(b"CS0".to_vec())), &res)
            .expect("named ICCBased should resolve");
        let argb = eval_cs_to_rgb(&doc, &kind, &[0.0, 1.0, 0.0], &res).unwrap();
        assert_eq!(argb & 0x00FF_FFFF, 0x0000_FF00, "ICCBased/RGB green must stay green");
    }

    // A named colorspace stored as a DIRECT array (not an indirect reference) is
    // not in the pre-built id map, so it must be resolved via the raw resource
    // ColorSpace dict by parse_named_cs.
    #[test]
    fn direct_array_colorspace_resolves_via_resources() {
        use lopdf::{dictionary, Object, Stream};
        let mut doc = Document::with_version("1.7");
        let icc_id = doc.add_object(Stream::new(dictionary! { "N" => 3 }, vec![0u8; 4]));
        let resources = dictionary! {
            "ColorSpace" => dictionary! {
                "CS0" => Object::Array(vec![
                    Object::Name(b"ICCBased".to_vec()),
                    Object::Reference(icc_id),
                ])
            }
        };
        let empty = HashMap::new();
        let kind = parse_named_cs(&doc, &Object::Name(b"CS0".to_vec()), Some(&resources), &empty)
            .expect("direct-array colorspace should resolve");
        let argb = eval_cs_to_rgb(&doc, &kind, &[1.0, 0.0, 0.0], &empty).unwrap();
        assert_eq!(argb & 0x00FF_FFFF, 0x00FF_0000, "direct-array ICCBased red must stay red");
    }

    // Indexed index must clamp to hival, not a hard-coded 255. With hival=1 an
    // out-of-range index (5) should clamp to entry 1, not read past the table.
    #[test]
    fn indexed_clamps_to_hival() {
        let doc = Document::with_version("1.7");
        // Two-entry palette over DeviceRGB: [black, white].
        let cs = CsKind::Indexed {
            base: Box::new(CsKind::DeviceRGB),
            lookup: vec![0, 0, 0, 255, 255, 255],
            base_ncomp: 3,
            hival: 1,
        };
        let res = HashMap::new();
        let over = eval_cs_to_rgb(&doc, &cs, &[5.0], &res).unwrap();
        assert_eq!(over & 0x00FF_FFFF, 0x00FF_FFFF, "index 5 clamps to hival=1 (white)");
    }

    // DeviceN without a tint transform must consider all components, not just the
    // first: two fully-inked colorants should be darker than one.
    #[test]
    fn devicen_without_tint_uses_all_components() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::DeviceN {
            names: vec![b"A".to_vec(), b"B".to_vec()],
            alt: Box::new(CsKind::DeviceGray),
            tint_fn: None,
        };
        let res = HashMap::new();
        let one = eval_cs_to_rgb(&doc, &cs, &[0.5, 0.0], &res).unwrap() & 0xFF;
        let both = eval_cs_to_rgb(&doc, &cs, &[0.5, 0.5], &res).unwrap() & 0xFF;
        assert!(both < one, "two inks ({both}) must be darker than one ({one})");
    }

    // An uncolored Pattern colorspace [/Pattern base] must record its base space
    // so SCN operands resolve in it rather than by arity guessing.
    #[test]
    fn pattern_records_base_colorspace() {
        let doc = Document::with_version("1.7");
        let arr = vec![
            Object::Name(b"Pattern".to_vec()),
            Object::Name(b"DeviceCMYK".to_vec()),
        ];
        let empty = HashMap::new();
        let kind = parse_cs_array_at(&doc, &arr, &empty, 0).expect("pattern cs");
        match kind {
            CsKind::Pattern { base: Some(b) } => {
                assert!(matches!(*b, CsKind::DeviceCMYK), "base must be DeviceCMYK");
            }
            _ => panic!("expected Pattern with base colorspace"),
        }
    }

    // §8.6.6.3: an Indexed palette may be a STREAM, and the palette must actually be
    // decoded. lopdf 0.36's `decompressed_content` implements only Flate/LZW/ASCII85, and
    // the old `unwrap_or_else(|_| s.content.clone())` fallback handed back the still-
    // ENCODED bytes as the palette — so every colour in the image was wrong.
    #[test]
    fn indexed_palette_stream_with_an_unsupported_filter_still_decodes() {
        let mut doc = Document::with_version("1.7");
        // Palette: black, red, green, blue as ASCIIHex.
        let lookup = doc.add_object(Stream::new(
            dictionary! { "Filter" => "ASCIIHexDecode" },
            b"000000FF0000 00FF00 0000FF>".to_vec(),
        ));
        let arr = vec![
            Object::Name(b"Indexed".to_vec()),
            Object::Name(b"DeviceRGB".to_vec()),
            Object::Integer(3),
            Object::Reference(lookup),
        ];
        let empty = HashMap::new();
        let kind = parse_cs_array_at(&doc, &arr, &empty, 0).expect("indexed cs");
        match kind {
            CsKind::Indexed { lookup, base_ncomp, .. } => {
                assert_eq!(base_ncomp, 3);
                assert_eq!(
                    lookup,
                    vec![0, 0, 0, 0xFF, 0, 0, 0, 0xFF, 0, 0, 0, 0xFF],
                    "the palette must be DECODED, not the raw ASCIIHex bytes"
                );
            }
            _ => panic!("expected Indexed"),
        }
    }

    // The same stream through `colorspace_info`, which has its own copy of the palette
    // read and had the same defect.
    #[test]
    fn colorspace_info_indexed_palette_stream_also_decodes() {
        let mut doc = Document::with_version("1.7");
        let lookup = doc.add_object(Stream::new(
            dictionary! { "Filter" => "ASCIIHexDecode" },
            b"0000FF>".to_vec(),
        ));
        let cs = Object::Array(vec![
            Object::Name(b"Indexed".to_vec()),
            Object::Name(b"DeviceRGB".to_vec()),
            Object::Integer(0),
            Object::Reference(lookup),
        ]);
        let (ncomp, indexed) = colorspace_info(&doc, Some(&cs));
        assert_eq!(ncomp, 1, "an Indexed image sample is one palette index");
        let (base_n, palette) = indexed.expect("indexed info");
        assert_eq!(base_n, 3);
        assert_eq!(palette, vec![0x00, 0x00, 0xFF], "palette must be decoded");
    }

    // §8.6.6.5 defers to §8.6.6.4 for the tint transform, which "shall produce one
    // value for each component of the alternate space". The Separation arm has checked
    // that since a previous round; the DeviceN arm did not, so a broken transform fed
    // `eval_cs_to_rgb` a short tuple. Over an RGB/CMYK alternate that returns None,
    // which every caller reads as "leave the current colour alone" — the fill silently
    // keeps whatever colour was set before, which is not a colour anyone chose.
    #[test]
    fn devicen_with_a_short_tint_output_falls_back_instead_of_vanishing() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::DeviceN {
            names: vec![b"A".to_vec(), b"B".to_vec()],
            // DeviceCMYK needs 4 components; this Type 2 yields only 1.
            alt: Box::new(CsKind::DeviceCMYK),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                range: Vec::new(),
                c0: vec![0.0],
                c1: vec![1.0],
                n: 1.0,
            }),
        };
        let res = HashMap::new();
        let full = eval_cs_to_rgb(&doc, &cs, &[1.0, 1.0], &res)
            .expect("a broken tint transform must still yield a colour");
        assert_eq!(full & 0xFF, 0, "full ink darkens");
        let none = eval_cs_to_rgb(&doc, &cs, &[0.0, 0.0], &res).expect("resolves");
        assert_eq!(none & 0xFF, 255, "no ink leaves the page white");

        // A well-formed transform is untouched: 2 inks -> DeviceGray, t -> 1-t.
        let ok = CsKind::DeviceN {
            names: vec![b"A".to_vec(), b"B".to_vec()],
            alt: Box::new(CsKind::DeviceGray),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                range: Vec::new(),
                c0: vec![1.0],
                c1: vec![0.0],
                n: 1.0,
            }),
        };
        assert_eq!(eval_cs_to_rgb(&doc, &ok, &[0.0, 0.0], &res).unwrap() & 0xFF, 255);
        assert_eq!(eval_cs_to_rgb(&doc, &ok, &[1.0, 1.0], &res).unwrap() & 0xFF, 0);
    }

    // §8.6.5.4 Table 66: /Range bounds a* and b*, and a value outside it "shall be
    // adjusted to the nearest valid value". The image path arrives in range via
    // /Decode, but `sc`/`scn` operands and a tint transform with no /Range of its own
    // do not — and an unbounded a*/b* drives fx/fz far outside the cube-root branch,
    // producing a saturated primary instead of the nearest in-gamut colour.
    #[test]
    fn lab_clamps_a_and_b_to_the_spaces_range() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::Lab {
            white: [0.9505, 1.0, 1.0890],
            range: [[-20.0, 20.0], [-20.0, 20.0]],
        };
        let res = HashMap::new();
        let at_edge = eval_cs_to_rgb(&doc, &cs, &[50.0, 20.0, 0.0], &res).unwrap();
        let beyond = eval_cs_to_rgb(&doc, &cs, &[50.0, 90.0, 0.0], &res).unwrap();
        assert_eq!(at_edge, beyond, "a* past /Range clamps to the edge of /Range");
        let below = eval_cs_to_rgb(&doc, &cs, &[50.0, -90.0, 0.0], &res).unwrap();
        assert_eq!(
            below,
            eval_cs_to_rgb(&doc, &cs, &[50.0, -20.0, 0.0], &res).unwrap()
        );
        // The clamp is not a blanket flattening: inside /Range the colour still varies.
        let inside = eval_cs_to_rgb(&doc, &cs, &[50.0, 0.0, 0.0], &res).unwrap();
        assert_ne!(inside, at_edge, "in-range a* is untouched");
        // And the DEFAULT range (-100..100) leaves ordinary Lab values alone.
        let wide = CsKind::Lab {
            white: [0.9505, 1.0, 1.0890],
            range: [[-100.0, 100.0], [-100.0, 100.0]],
        };
        assert_ne!(
            eval_cs_to_rgb(&doc, &wide, &[50.0, 60.0, 0.0], &res).unwrap(),
            eval_cs_to_rgb(&doc, &wide, &[50.0, 20.0, 0.0], &res).unwrap()
        );
    }

    // `parse_cs_array_at`'s MAX_CS_DEPTH comment describes the exact cycle
    // `[/Indexed 5 0 R 255 <..>]`-stored-as-object-5 creates. `colorspace_info` is a
    // second, parallel walk of the same object graph and did NOT have the guard, so the
    // same file that was safe through one entry point overflowed the stack through the
    // other. A stack overflow is not catchable, so the process dies.
    #[test]
    fn colorspace_info_survives_a_self_referential_indexed_base() {
        let mut doc = Document::with_version("1.7");
        let id = doc.new_object_id();
        doc.set_object(
            id,
            Object::Array(vec![
                Object::Name(b"Indexed".to_vec()),
                Object::Reference(id),
                Object::Integer(255),
                Object::String(vec![0, 0, 0], lopdf::StringFormat::Literal),
            ]),
        );
        let (n, indexed) = colorspace_info(&doc, Some(&Object::Reference(id)));
        assert_eq!(n, 1, "an Indexed image sample is still one index");
        assert!(indexed.is_some());
        // And the other entry point stays consistent.
        assert!(parse_cs_kind(&doc, Some(&Object::Reference(id)), &HashMap::new()).is_some());
    }

    // A malformed Cal*/Lab entry must mean exactly what an ABSENT one means — the spec
    // default — not propagate into the conversion. A real like 1e40 overflows the f32
    // `Object::Real` holds, and an infinite /WhitePoint reaches `adapt_to_d65` where
    // inf/inf is NaN; `rgb_to_argb` saturates NaN to 0, so a CalRGB image rendered as a
    // solid BLACK rectangle. (Same class as the non-finite path operands and shading
    // matrices found this round, on the colour side.)
    #[test]
    fn non_finite_cal_entries_fall_back_to_the_spec_defaults() {
        use lopdf::dictionary;
        let inf = Object::Real(f32::INFINITY);
        let bad_wp = dictionary! {
            "WhitePoint" => vec![inf.clone(), Object::Real(1.0), Object::Real(1.089)]
        };
        assert!(read_white_point(&bad_wp).is_none(), "non-finite /WhitePoint is refused");
        // A zero component breaks the adaptation just as badly.
        let zero_wp = dictionary! {
            "WhitePoint" => vec![Object::Real(0.0), Object::Real(1.0), Object::Real(1.089)]
        };
        assert!(read_white_point(&zero_wp).is_none(), "a zero /WhitePoint component is refused");
        let good_wp = dictionary! {
            "WhitePoint" => vec![Object::Real(0.9505), Object::Real(1.0), Object::Real(1.089)]
        };
        assert!(read_white_point(&good_wp).is_some(), "a sane /WhitePoint still reads");

        assert!(read_gamma_rgb(&dictionary! {
            "Gamma" => vec![inf.clone(), Object::Real(1.0), Object::Real(1.0)]
        }).is_none());
        assert!(read_lab_range(&dictionary! {
            "Range" => vec![Object::Real(-100.0), inf.clone(), Object::Real(-100.0), Object::Real(100.0)]
        }).is_none());
        let mut m: Vec<Object> = (0..9).map(|_| Object::Real(1.0)).collect();
        m[4] = inf;
        assert!(read_matrix_cal(&dictionary! { "Matrix" => m }).is_none());

        // End to end: a CalRGB space whose /WhitePoint overflowed must still render a
        // recognisable colour rather than collapsing to black.
        let doc = Document::with_version("1.7");
        let arr = vec![
            Object::Name(b"CalRGB".to_vec()),
            Object::Dictionary(dictionary! {
                "WhitePoint" => vec![Object::Real(f32::INFINITY), Object::Real(1.0), Object::Real(1.089)]
            }),
        ];
        let empty = HashMap::new();
        let kind = parse_cs_array_at(&doc, &arr, &empty, 0).expect("calrgb");
        let white = eval_cs_to_rgb(&doc, &kind, &[1.0, 1.0, 1.0], &empty).unwrap();
        assert!(
            (white & 0xFF) > 200,
            "CalRGB white must stay light, got {:#010X} - a NaN whitepoint renders black",
            white
        );
    }
}
