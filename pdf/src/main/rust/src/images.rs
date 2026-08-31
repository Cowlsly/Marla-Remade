use crate::*;

/// Diagnostic for an image that was dropped or degraded, naming the reason.
///
/// Per lead's decision on P0-4 we deliberately do NOT substitute a placeholder for
/// a failed decode — a grey box is not the graphic either, and it would create a
/// fresh "renders things that shouldn't be there" bug. So a failure stays invisible
/// on the page, and this is the only way to find out why. The crate has no logging
/// dependency (and adding one would touch a shared Cargo.toml), and these fire at
/// most once per image, so a debug-build stderr line is the proportionate tool.
macro_rules! image_warn {
    ($($arg:tt)*) => {{
        if cfg!(debug_assertions) {
            eprintln!("[pdf_render/images] {}", format_args!($($arg)*));
        }
    }};
}

/// JPEG2000 (`JPXDecode`) decoding via the pure-Rust `openjp2` port of OpenJPEG.
pub(crate) mod jp2 {
    use openjp2::openjpeg::*;
    use std::ffi::c_void;

    /// How to interpret the codestream's colour components. §7.4.9 makes the PDF
    /// image dictionary's own `/ColorSpace`, when present, OVERRIDE the colour space
    /// recorded in the JPEG2000 data, so the caller can force one of these.
    #[derive(Copy, Clone, PartialEq)]
    pub enum Interp {
        Gray,
        Rgb,
        /// sYCC/eYCC. Not a PDF colour space: `opj_decode` leaves the components in
        /// YCC and the conversion to RGB below is mandatory, so a `/ColorSpace`
        /// override must not replace this — it would emit raw YCC as RGB.
        Ycc,
        Cmyk,
    }

    impl Interp {
        /// Number of COLOUR channels, i.e. the index at which an alpha channel would
        /// start when the codestream carries no `cdef` box to say where it is.
        fn ncolour(self) -> usize {
            match self {
                Interp::Gray => 1,
                Interp::Rgb | Interp::Ycc => 3,
                Interp::Cmyk => 4,
            }
        }
    }

    /// Choose the component interpretation. §7.4.9: the PDF's `/ColorSpace` overrides
    /// the codestream's, EXCEPT for sYCC/eYCC, which is a channel encoding rather than
    /// a PDF colour space — `opj_decode` returns YCC components and the conversion to
    /// RGB is mandatory, so a `/DeviceRGB` hint must not suppress it.
    pub fn resolve_interp(codestream: Option<Interp>, hint: Option<Interp>, ncomp: usize) -> Interp {
        if codestream == Some(Interp::Ycc) {
            return Interp::Ycc;
        }
        hint.or(codestream).unwrap_or(match ncomp {
            1 => Interp::Gray,
            4 => Interp::Cmyk,
            _ => Interp::Rgb,
        })
    }

    /// Resolve `/SMaskInData` (§7.4.9 Table 89) against the codestream's channel
    /// layout, returning `(alpha component index, un-premultiply)`.
    ///
    /// `cdef_alpha` is the channel a JP2 `cdef` box names as opacity, if any.
    /// Split out of `image_to_rgba` so the three cases are testable without a JPX
    /// codestream, which is where the original bug hid: alpha was applied whenever the
    /// channel count merely suggested one, so value 0 (and an ABSENT entry, whose
    /// default is 0) wrongly made the image transparent, and value 2's premultiplication
    /// was never undone.
    pub fn resolve_alpha(
        smask_in_data: u8,
        ncomp: usize,
        ncolour: usize,
        cdef_alpha: Option<usize>,
    ) -> (Option<usize>, bool) {
        if smask_in_data == 0 {
            return (None, false);
        }
        let idx = cdef_alpha
            .filter(|i| *i >= ncolour && *i < ncomp)
            .or_else(|| (ncomp > ncolour).then_some(ncolour));
        (idx, idx.is_some() && smask_in_data == 2)
    }

    /// Decode a JPX codestream honouring the PDF image dictionary (§7.4.9).
    ///
    /// `smask_in_data` is `/SMaskInData` (Table 89): 0 = ignore any alpha channel in
    /// the codestream, 1 = the alpha channel IS the soft mask, 2 = the same but the
    /// colour data is PREMULTIPLIED by it and must be un-premultiplied.
    /// `cs_hint` is the PDF's own `/ColorSpace`, which overrides the codestream's.
    #[derive(Copy, Clone)]
    pub struct JpxOpts {
        pub smask_in_data: u8,
        pub cs_hint: Option<Interp>,
    }

    impl Default for JpxOpts {
        /// The spec default for `/SMaskInData` is 0, and 0 means "ignore the alpha
        /// channel". A mask stream decoded through this module wants exactly that: it
        /// reads the colour channels and supplies its own meaning.
        fn default() -> Self {
            JpxOpts { smask_in_data: 0, cs_hint: None }
        }
    }

    struct Slice<'a> {
        off: usize,
        buf: &'a [u8],
    }
    impl<'a> Slice<'a> {
        fn seek(&mut self, n: usize) -> usize {
            self.off = self.buf.len().min(n);
            self.off
        }
        fn consume(&mut self, n: usize) -> usize {
            self.off = self.buf.len().min(self.off.saturating_add(n));
            self.off
        }
    }
    extern "C" fn free_fn(p: *mut c_void) {
        if p.is_null() {
            return;
        }
        drop(unsafe { Box::from_raw(p as *mut Slice) })
    }
    extern "C" fn read_fn(pb: *mut c_void, nb: usize, p: *mut c_void) -> usize {
        if pb.is_null() || p.is_null() || nb == 0 {
            return usize::MAX;
        }
        let s = unsafe { &mut *(p as *mut Slice) };
        let remaining = s.buf.len().saturating_sub(s.off);
        if remaining == 0 {
            return usize::MAX;
        }
        let n = remaining.min(nb);
        let out = unsafe { std::slice::from_raw_parts_mut(pb as *mut u8, n) };
        out.copy_from_slice(&s.buf[s.off..s.off + n]);
        s.off += n;
        n
    }
    extern "C" fn skip_fn(nb: i64, p: *mut c_void) -> i64 {
        if p.is_null() {
            return -1;
        }
        let s = unsafe { &mut *(p as *mut Slice) };
        s.consume(nb.max(0) as usize) as i64
    }
    extern "C" fn seek_fn(nb: i64, p: *mut c_void) -> i32 {
        if p.is_null() {
            return 0;
        }
        let s = unsafe { &mut *(p as *mut Slice) };
        let want = nb.max(0) as usize;
        if s.seek(want) == want {
            1
        } else {
            0
        }
    }

    /// Decode JP2/J2K bytes to `(width, height, RGBA8888)`, or `None`.
    ///
    /// Alpha is DISCARDED (`/SMaskInData` 0, the spec default). Callers that have the
    /// image dictionary should use [`decode_with_opts`].
    pub fn decode(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
        decode_with_opts(bytes, JpxOpts::default())
    }

    /// Decode JP2/J2K bytes honouring `/SMaskInData` and a `/ColorSpace` override.
    pub fn decode_with_opts(bytes: &[u8], opts: JpxOpts) -> Option<(u32, u32, Vec<u8>)> {
        // JP2 signature box vs raw codestream.
        let fmt = if bytes.len() > 4 && &bytes[4..8] == b"jP  " {
            OPJ_CODEC_JP2
        } else {
            OPJ_CODEC_J2K
        };
        unsafe {
            decode_with(bytes, fmt, opts).or_else(|| decode_with(bytes, OPJ_CODEC_JP2, opts))
        }
    }

    unsafe fn decode_with(
        bytes: &[u8],
        fmt: OPJ_CODEC_FORMAT,
        opts: JpxOpts,
    ) -> Option<(u32, u32, Vec<u8>)> {
        let data = Box::new(Slice { off: 0, buf: bytes });
        let stream = opj_stream_default_create(1);
        if stream.is_null() {
            return None;
        }
        let p = Box::into_raw(data) as *mut c_void;
        opj_stream_set_read_function(stream, Some(read_fn));
        opj_stream_set_skip_function(stream, Some(skip_fn));
        opj_stream_set_seek_function(stream, Some(seek_fn));
        opj_stream_set_user_data_length(stream, bytes.len() as u64);
        opj_stream_set_user_data(stream, p, Some(free_fn));

        let codec = opj_create_decompress(fmt);
        if codec.is_null() {
            opj_stream_destroy(stream);
            return None;
        }
        let mut params = opj_dparameters_t::default();
        opj_set_default_decoder_parameters(&mut params);
        let mut out = None;
        if opj_setup_decoder(codec, &mut params) != 0 {
            let mut image = std::ptr::null_mut() as *mut opj_image_t;
            if opj_read_header(stream, codec, &mut image) != 0
                && opj_decode(codec, stream, image) != 0
                && opj_end_decompress(codec, stream) != 0
                && !image.is_null()
            {
                // Null-checked deref via as_ref() (image is non-null here).
                if let Some(img) = image.as_ref() {
                    out = image_to_rgba(img, opts);
                }
            }
            if !image.is_null() {
                opj_image_destroy(image);
            }
        }
        opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        out
    }

    unsafe fn image_to_rgba(img: &opj_image_t, opts: JpxOpts) -> Option<(u32, u32, Vec<u8>)> {
        // x1-x0 / y1-y0 are unsigned: a malformed header with x1 < x0 underflows to a
        // huge value. Release builds have no overflow checks, so this wrapped silently.
        let w = (img.x1.checked_sub(img.x0)?) as usize;
        let h = (img.y1.checked_sub(img.y0)?) as usize;
        if w == 0 || h == 0 || w > 20000 || h > 20000 || img.numcomps == 0 {
            return None;
        }
        // A dimension-only cap still permits 20000x20000 = 1.6 GB.
        if w.saturating_mul(h) > crate::MAX_IMAGE_PIXELS {
            return None;
        }
        if img.comps.is_null() {
            return None;
        }
        let comps = std::slice::from_raw_parts(img.comps, img.numcomps as usize);
        let n = img.numcomps as usize;
        // Sample a component's value at (x,y) scaled to 8-bit.
        let sample = |c: &opj_image_comp_t, x: usize, y: usize| -> u8 {
            let cw = c.w as usize;
            let ch = c.h as usize;
            if cw == 0 || ch == 0 || c.data.is_null() {
                return 0;
            }
            let sx = (x * cw / w).min(cw - 1);
            let sy = (y * ch / h).min(ch - 1);
            let mut v = *c.data.add(sy * cw + sx);
            // prec == 0 underflows `1 << (prec-1)`, and prec >= 40 makes the shift
            // below panic (or wrap in release). Clamp to the representable range.
            let prec = (c.prec as i32).clamp(1, 32);
            if c.sgnd != 0 {
                v += 1 << (prec - 1);
            }
            let v = if prec > 8 {
                v >> (prec - 8)
            } else if prec < 8 {
                v << (8 - prec)
            } else {
                v
            };
            v.clamp(0, 255) as u8
        };

        // Decide how to interpret components. §7.4.9: the PDF image dictionary's own
        // /ColorSpace, when present, OVERRIDES the colour space in the JPEG2000 data;
        // only when the PDF is silent do we consult the codestream, and only then fall
        // back to the channel count.
        let codestream_interp = match img.color_space {
            OPJ_CLRSPC_GRAY => Some(Interp::Gray),
            OPJ_CLRSPC_CMYK => Some(Interp::Cmyk),
            OPJ_CLRSPC_SYCC | OPJ_CLRSPC_EYCC => Some(Interp::Ycc),
            OPJ_CLRSPC_SRGB => Some(Interp::Rgb),
            _ => None,
        };
        let interp = resolve_interp(codestream_interp, opts.cs_hint, n);

        // A JP2 `cdef` box names the opacity channel explicitly and openjp2 surfaces
        // that as `comp.alpha != 0`; without one, a channel past the colour channels is
        // the conventional place for it.
        let cdef_alpha = comps.iter().position(|c| c.alpha != 0);
        let (alpha_comp, unpremultiply) =
            resolve_alpha(opts.smask_in_data, n, interp.ncolour(), cdef_alpha);

        let mut rgba = vec![0u8; w * h * 4];
        for y in 0..h {
            for x in 0..w {
                let idx = (y * w + x) * 4;
                let (r, g, b) = match interp {
                    Interp::Gray => {
                        let v = sample(&comps[0], x, y);
                        (v, v, v)
                    }
                    Interp::Rgb => (
                        sample(&comps[0], x, y),
                        sample(comps.get(1).unwrap_or(&comps[0]), x, y),
                        sample(comps.get(2).unwrap_or(&comps[0]), x, y),
                    ),
                    Interp::Ycc if n >= 3 => {
                        let yy = sample(&comps[0], x, y) as f32;
                        let cb = sample(&comps[1], x, y) as f32 - 128.0;
                        let cr = sample(&comps[2], x, y) as f32 - 128.0;
                        let r = (yy + 1.402 * cr).round().clamp(0.0, 255.0) as u8;
                        let g = (yy - 0.344136 * cb - 0.714136 * cr).round().clamp(0.0, 255.0) as u8;
                        let b = (yy + 1.772 * cb).round().clamp(0.0, 255.0) as u8;
                        (r, g, b)
                    }
                    Interp::Ycc => {
                        let v = sample(&comps[0], x, y);
                        (v, v, v)
                    }
                    Interp::Cmyk if n >= 4 => {
                        let c = sample(&comps[0], x, y) as f32 / 255.0;
                        let m = sample(&comps[1], x, y) as f32 / 255.0;
                        let ye = sample(&comps[2], x, y) as f32 / 255.0;
                        let k = sample(&comps[3], x, y) as f32 / 255.0;
                        let r = ((1.0 - c) * (1.0 - k) * 255.0).round() as u8;
                        let g = ((1.0 - m) * (1.0 - k) * 255.0).round() as u8;
                        let b = ((1.0 - ye) * (1.0 - k) * 255.0).round() as u8;
                        (r, g, b)
                    }
                    Interp::Cmyk => {
                        let v = sample(&comps[0], x, y);
                        (v, v, v)
                    }
                };
                let a = match alpha_comp {
                    Some(ai) => sample(&comps[ai], x, y),
                    None => 255,
                };
                let (r, g, b) = if unpremultiply && a > 0 && a < 255 {
                    // §7.4.9 /SMaskInData 2: c' = c * a, so recover c = c' / a. Doing this
                    // in RGB after conversion is exact for the gray and sRGB codestreams
                    // that actually use premultiplied alpha; without it every soft edge
                    // keeps the black it was multiplied towards, which is the dark fringe.
                    let s = 255.0 / a as f32;
                    let un = |v: u8| (v as f32 * s).round().clamp(0.0, 255.0) as u8;
                    (un(r), un(g), un(b))
                } else {
                    (r, g, b)
                };
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = a;
            }
        }
        Some((w as u32, h as u32, rgba))
    }
}

pub(crate) struct ImageData {
    pub(crate) w: u32,
    pub(crate) h: u32,
    /// 0 = raw RGBA8888, 1 = JPEG bytes.
    pub(crate) format: u8,
    pub(crate) data: Vec<u8>,
}

/// Map of XObject resource name -> object id from a resources dictionary.
pub(crate) fn xobjects_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(xo)) = res_dict.get(b"XObject").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in xo.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            }
        }
    }
    out
}

/// Map of ExtGState resource name -> object id
pub(crate) fn extgstates_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, ObjectId> {
    let mut out = HashMap::new();
    if let Some(Object::Dictionary(eg)) = res_dict.get(b"ExtGState").ok().and_then(|o| deref(doc, o)) {
        for (name, v) in eg.iter() {
            if let Ok(id) = v.as_reference() {
                out.insert(name.clone(), id);
            } else if let Object::Dictionary(_) = v {
                // A DIRECT ExtGState dictionary has no object id, so it cannot go in this
                // name -> ObjectId map. It is not lost: the `gs` operator resolves the
                // named entry straight out of `/Resources /ExtGState` itself, which
                // handles the direct and indirect forms alike.
            }
        }
    }
    out
}

/// The document's default optional-content configuration (`/OCProperties /D`,
/// §8.11.4.3) resolved into set membership, so a query is O(1).
///
/// Built ONCE per caller. Reading membership out of the arrays instead costs
/// O(|/ON| + |/OFF|) per query and a `BDC` asks per marked-content section, so a
/// document whose N layers are each opened once — a CAD or map export — paid
/// O(N²): 6400 distinct groups measured 57.8 ms against 40.7 ms for 6400
/// sections sharing one group, where the call-site memo absorbs every repeat.
///
/// `/ON` and `/OFF` hold indirect references; a group that is a direct
/// dictionary has no id to match, so a group absent from both sets falls back to
/// `/BaseState` exactly as scanning the arrays for it did.
pub(crate) struct OcConfig {
    on: std::collections::HashSet<ObjectId>,
    off: std::collections::HashSet<ObjectId>,
    /// `/BaseState`, defaulting to ON (§8.11.4.3). Also the answer for a
    /// document with no usable `/OCProperties /D`, where everything is visible.
    base_on: bool,
}

impl OcConfig {
    pub(crate) fn from_doc(doc: &Document) -> Self {
        let mut cfg = OcConfig {
            on: std::collections::HashSet::new(),
            off: std::collections::HashSet::new(),
            base_on: true,
        };
        let Ok(catalog) = doc.catalog() else {
            return cfg;
        };
        let Some(Object::Dictionary(oc_props)) =
            catalog.get(b"OCProperties").ok().and_then(|o| deref(doc, o))
        else {
            return cfg;
        };
        let Some(d_dict) = oc_props
            .get(b"D")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
        else {
            return cfg;
        };
        cfg.base_on = matches!(
            d_dict.get(b"BaseState").ok().and_then(|o| o.as_name().ok()),
            Some(b"ON") | None
        );
        let collect = |key: &[u8], out: &mut std::collections::HashSet<ObjectId>| {
            if let Some(list) = d_dict
                .get(key)
                .ok()
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_array().ok())
            {
                out.extend(list.iter().filter_map(|obj| obj.as_reference().ok()));
            }
        };
        collect(b"ON", &mut cfg.on);
        collect(b"OFF", &mut cfg.off);
        cfg
    }

    /// Whether an OCG is visible. True for an unknown group, so a document that
    /// never declares `/OCProperties` renders in full.
    pub(crate) fn is_ocg_visible(&self, ocg_id: ObjectId) -> bool {
        // §8.11.4.3: /ON and /OFF override /BaseState, applied in the order
        // Table 101 lists them — /BaseState, then /ON, then /OFF — so /OFF wins
        // for a group named by both, matching mainstream viewers.
        if self.off.contains(&ocg_id) {
            return false;
        }
        if self.on.contains(&ocg_id) {
            return true;
        }
        self.base_on
    }

    /// Evaluate an OCMD (Optional Content Membership Dictionary) `/OCGs` + `/P`
    /// visibility policy. Returns true if the membership resolves to HIDDEN.
    fn ocmd_hidden(&self, d: &Dictionary) -> bool {
        let mut ids: Vec<ObjectId> = Vec::new();
        match d.get(b"OCGs").ok() {
            Some(Object::Reference(id)) => ids.push(*id),
            Some(Object::Array(a)) => {
                for o in a {
                    if let Ok(id) = o.as_reference() { ids.push(id); }
                }
            }
            _ => {}
        }
        if ids.is_empty() {
            return false; // no member groups -> visible
        }
        let vis: Vec<bool> = ids.iter().map(|id| self.is_ocg_visible(*id)).collect();
        let policy = d.get(b"P").ok().and_then(|o| o.as_name().ok());
        let visible = match policy {
            Some(b"AllOn") => vis.iter().all(|v| *v),
            Some(b"AnyOff") => vis.iter().any(|v| !*v),
            Some(b"AllOff") => vis.iter().all(|v| !*v),
            _ => vis.iter().any(|v| *v), // AnyOn (default)
        };
        !visible
    }

    /// Decide whether marked content / an XObject tagged with the given `/OC`
    /// object (an OCG or OCMD, possibly an indirect reference) should be HIDDEN.
    pub(crate) fn object_hidden(&self, doc: &Document, obj: &Object) -> bool {
        match obj {
            Object::Reference(id) => {
                if let Ok(Object::Dictionary(d)) = doc.get_object(*id) {
                    if d.get(b"Type").ok().and_then(|o| o.as_name().ok()) == Some(b"OCMD") {
                        return self.ocmd_hidden(d);
                    }
                }
                !self.is_ocg_visible(*id)
            }
            Object::Dictionary(d) if d.get(b"Type").ok().and_then(|o| o.as_name().ok()) == Some(b"OCMD") => {
                self.ocmd_hidden(d)
            }
            // Inline OCG dict without an object id can't be matched against the
            // ON/OFF lists; default to visible.
            _ => false,
        }
    }
}

/// Whether a colorspace requires the full `eval_cs_to_rgb` path (vs the fast
/// `comps_to_rgb` device path which is equivalent for plain RGB/Gray/CMYK).
fn cs_needs_eval(k: &CsKind) -> bool {
    match k {
        CsKind::DeviceGray | CsKind::DeviceRGB | CsKind::DeviceCMYK | CsKind::Pattern { .. } => false,
        CsKind::ICCBased { alt, .. } => alt.as_ref().map(|a| cs_needs_eval(a)).unwrap_or(false),
        CsKind::Lab { .. }
        | CsKind::Separation { .. }
        | CsKind::DeviceN { .. }
        | CsKind::CalRGB { .. }
        | CsKind::CalGray { .. }
        | CsKind::Indexed { .. } => true,
    }
}

/// Default `/Decode` ranges per component for a colorspace (used when the image
/// has no explicit `/Decode`). Lab uses [0,100] for L and its a*/b* ranges.
fn default_decode_for(k: &CsKind, ncomp: usize) -> Vec<(f64, f64)> {
    match k {
        CsKind::Lab { range, .. } => vec![
            (0.0, 100.0),
            (range[0][0], range[0][1]),
            (range[1][0], range[1][1]),
        ],
        _ => vec![(0.0, 1.0); ncomp.max(1)],
    }
}

/// Convert unpacked component bytes (w*h*ncomp, each 0..=255, already scaled
/// from `bpc`) into RGBA using the image's full `/ColorSpace`. Handles
/// Separation/DeviceN tint transforms, Lab, Cal*, ICCBased alternates and
/// Indexed palettes via `eval_cs_to_rgb`, building LUTs for single-component
/// and indexed images to stay fast. Falls back to `comps_to_rgb` for plain
/// device spaces.
fn image_samples_to_rgba(
    doc: &Document,
    dict: &lopdf::Dictionary,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
    decoded_comps: &[u8],
    w: usize,
    h: usize,
    ncomp: usize,
    bpc: u32,
) -> Vec<u8> {
    let mut rgba = vec![0u8; w * h * 4];
    let cs_kind = dict
        .get(b"ColorSpace")
        .or_else(|_| dict.get(b"CS"))
        .ok()
        .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources));

    let decode_arr: Vec<f64> = dict
        .get(b"Decode")
        .or_else(|_| dict.get(b"D"))
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect())
        .unwrap_or_default();

    let put = |rgba: &mut [u8], idx: usize, argb: u32| {
        rgba[idx] = ((argb >> 16) & 0xFF) as u8;
        rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
        rgba[idx + 2] = (argb & 0xFF) as u8;
        rgba[idx + 3] = 255;
    };

    let kind = match cs_kind {
        Some(k) if cs_needs_eval(&k) => k,
        _ => {
            // Fast device path. Apply the image's /Decode array (default identity
            // [0,1] per component) — e.g. a DeviceGray image with /Decode [1 0]
            // must be inverted. Device spaces have <=4 components.
            let has_decode = decode_arr.len() >= ncomp * 2
                && (0..ncomp).any(|c| decode_arr[c * 2] != 0.0 || (decode_arr[c * 2 + 1] - 1.0).abs() > 1e-9);
            for i in 0..w * h {
                let base = i * ncomp;
                let (r, g, b) = if base + ncomp <= decoded_comps.len() {
                    if has_decode {
                        let mut tmp = [0u8; 4];
                        for c in 0..ncomp.min(4) {
                            let dmin = decode_arr[c * 2];
                            let dmax = decode_arr[c * 2 + 1];
                            let v = decoded_comps[base + c] as f64 / 255.0;
                            let mapped = (dmin + v * (dmax - dmin)).clamp(0.0, 1.0);
                            tmp[c] = (mapped * 255.0).round() as u8;
                        }
                        comps_to_rgb(&tmp[..ncomp.min(4)], ncomp as u8)
                    } else {
                        comps_to_rgb(&decoded_comps[base..base + ncomp], ncomp as u8)
                    }
                } else {
                    (0, 0, 0)
                };
                let idx = i * 4;
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
            return rgba;
        }
    };

    // Indexed: build a palette LUT over the base colorspace.
    if let CsKind::Indexed { base, lookup, base_ncomp, hival: declared_hival } = &kind {
        let bn = (*base_ncomp as usize).max(1);
        let maxidx = if bpc >= 8 { 255usize } else { (1usize << bpc) - 1 };
        // An unloadable palette (a lookup stream that failed to decompress) used to
        // yield a 1-entry all-black table, painting a solid black rectangle over the
        // page. Nothing is better than something wrong here.
        if lookup.len() < bn {
            image_warn!("Indexed palette empty ({} bytes, {} per entry) - image dropped", lookup.len(), bn);
            return rgba;
        }
        // §8.6.6.3: /hival is the largest valid index and is authoritative. Clamp it
        // to what the table can actually supply so a short table cannot read garbage.
        let hival = (*declared_hival as usize).min(lookup.len() / bn - 1);
        // Each palette byte spans the RANGE of the corresponding base component,
        // which is [0,1] for device spaces but [0,100] / /Range for a Lab base
        // (§8.6.6.3) — dividing by 255 unconditionally rendered Lab palettes black.
        let base_ranges = cs_kind_default_decode(base);
        let mut palette = vec![0xFF00_0000u32; hival + 1];
        for (i, slot) in palette.iter_mut().enumerate() {
            let off = i * bn;
            if off + bn <= lookup.len() {
                let comps: Vec<f64> = lookup[off..off + bn]
                    .iter()
                    .enumerate()
                    .map(|(c, b)| {
                        let (lo, hi) = base_ranges.get(c).copied().unwrap_or((0.0, 1.0));
                        lo + (*b as f64 / 255.0) * (hi - lo)
                    })
                    .collect();
                if let Some(argb) = eval_cs_to_rgb(doc, base, &comps, cs_resources) {
                    *slot = argb;
                }
            }
        }
        for i in 0..w * h {
            let byte = decoded_comps.get(i * ncomp).copied().unwrap_or(0) as usize;
            let raw = if bpc >= 8 { byte } else { (byte * maxidx + 127) / 255 };
            // §8.9.5.2 Table 90: an Indexed image's default /Decode is [0 2^bpc - 1], and
            // an explicit one REMAPS the sample onto that index range — e.g. /Decode
            // [0 15] on an 8-bpc image addresses only the first 16 palette entries. This
            // branch ignored /Decode entirely, so such an image read the wrong colours
            // straight through. The default is the identity, so this is a no-op for the
            // overwhelming majority of Indexed images.
            let index = if decode_arr.len() >= 2 && maxidx > 0 {
                let (dmin, dmax) = (decode_arr[0], decode_arr[1]);
                let mapped = dmin + (raw as f64 / maxidx as f64) * (dmax - dmin);
                mapped.round().clamp(0.0, hival as f64) as usize
            } else {
                raw
            };
            let argb = palette.get(index.min(hival)).copied().unwrap_or(0xFF00_0000);
            put(&mut rgba, i * 4, argb);
        }
        return rgba;
    }

    let default_decode = default_decode_for(&kind, ncomp);
    let comp_range = |i: usize| -> (f64, f64) {
        if decode_arr.len() >= (i + 1) * 2 {
            (decode_arr[i * 2], decode_arr[i * 2 + 1])
        } else {
            default_decode.get(i).copied().unwrap_or((0.0, 1.0))
        }
    };

    // Single-component spaces: 256-entry LUT.
    if ncomp == 1 {
        let (lo, hi) = comp_range(0);
        let mut lut = [0xFF00_0000u32; 256];
        for (v, slot) in lut.iter_mut().enumerate() {
            let comp = lo + (v as f64 / 255.0) * (hi - lo);
            if let Some(argb) = eval_cs_to_rgb(doc, &kind, &[comp], cs_resources) {
                *slot = argb;
            }
        }
        for i in 0..w * h {
            let byte = decoded_comps.get(i * ncomp).copied().unwrap_or(0) as usize;
            put(&mut rgba, i * 4, lut[byte]);
        }
        return rgba;
    }

    // General per-pixel evaluation (bounded by MAX_IMAGE_PIXELS at entry).
    for i in 0..w * h {
        let base = i * ncomp;
        let idx = i * 4;
        if base + ncomp <= decoded_comps.len() {
            let comps: Vec<f64> = (0..ncomp)
                .map(|c| {
                    let (lo, hi) = comp_range(c);
                    lo + (decoded_comps[base + c] as f64 / 255.0) * (hi - lo)
                })
                .collect();
            if let Some(argb) = eval_cs_to_rgb(doc, &kind, &comps, cs_resources) {
                put(&mut rgba, idx, argb);
            } else {
                let (r, g, b) = comps_to_rgb(&decoded_comps[base..base + ncomp], ncomp as u8);
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
        }
    }
    rgba
}

/// Extract a drawable image from an image XObject stream, or `None` if the
/// format is unsupported (e.g. JPEG2000, exotic color spaces).
/// Turn a decoded 1-bit codec raster (black-on-white RGBA) into a stencil: dark
/// pixels are painted with `fill_argb` (opaque), light pixels become transparent.
/// `invert` swaps the sense (for `/Decode [1 0]`).
fn stencilize(rgba: &mut [u8], fill_argb: u32, invert: bool) {
    let fr = ((fill_argb >> 16) & 0xFF) as u8;
    let fg = ((fill_argb >> 8) & 0xFF) as u8;
    let fb = (fill_argb & 0xFF) as u8;
    for px in rgba.chunks_exact_mut(4) {
        // alpha 0 means the codec never wrote this pixel (a truncated JBIG2 leaves whole
        // trailing rows untouched). Its RGB is undefined, and luma 0 would read as "dark"
        // and paint it — turning missing data into a solid block of fill colour.
        if px[3] == 0 {
            continue;
        }
        let luma = (px[0] as u32 * 299 + px[1] as u32 * 587 + px[2] as u32 * 114) / 1000;
        let paint = if invert { luma >= 128 } else { luma < 128 };
        if paint {
            px[0] = fr; px[1] = fg; px[2] = fb; px[3] = 255;
        } else {
            px[3] = 0;
        }
    }
}

/// Area-average downscale of an RGBA8888 buffer so its longer side is at most
/// `max_dim`, preserving aspect. Returns `None` when no downscale is needed.
///
/// `smooth` averages each source block, which is what a photograph wants. Bilevel art —
/// barcodes, QR codes, scanned fax pages, stencils — must pass `false`: averaging turns its
/// two colours into a spread of greys, and its hard 0/255 alpha into a translucent fringe,
/// which is the difference between a scannable QR code and an unreadable one.
fn downscale_rgba(data: &[u8], w: u32, h: u32, max_dim: u32, smooth: bool) -> Option<(u32, u32, Vec<u8>)> {
    if w == 0 || h == 0 || (w <= max_dim && h <= max_dim) {
        return None;
    }
    if data.len() < (w as usize) * (h as usize) * 4 {
        return None; // malformed buffer; leave as-is
    }
    let scale = max_dim as f64 / w.max(h) as f64;
    let nw = ((w as f64 * scale).round() as u32).clamp(1, w);
    let nh = ((h as f64 * scale).round() as u32).clamp(1, h);
    let mut out = vec![0u8; (nw as usize) * (nh as usize) * 4];
    for oy in 0..nh {
        let sy0 = ((oy as u64) * (h as u64) / (nh as u64)) as u32;
        let sy1 = ((((oy + 1) as u64) * (h as u64) / (nh as u64)) as u32).clamp(sy0 + 1, h);
        for ox in 0..nw {
            let sx0 = ((ox as u64) * (w as u64) / (nw as u64)) as u32;
            let sx1 = ((((ox + 1) as u64) * (w as u64) / (nw as u64)) as u32).clamp(sx0 + 1, w);
            let o = ((oy as usize) * (nw as usize) + ox as usize) * 4;
            if !smooth {
                // Nearest neighbour: the block's own first sample, kept exactly.
                let i = (sy0 as usize) * (w as usize) * 4 + (sx0 as usize) * 4;
                out[o..o + 4].copy_from_slice(&data[i..i + 4]);
                continue;
            }
            let (mut r, mut g, mut b, mut a, mut cnt) = (0u64, 0u64, 0u64, 0u64, 0u64);
            for sy in sy0..sy1 {
                let row = (sy as usize) * (w as usize) * 4;
                for sx in sx0..sx1 {
                    let i = row + (sx as usize) * 4;
                    // Average in PREMULTIPLIED space. Averaging straight RGBA lets a
                    // fully-transparent pixel contribute its colour with full weight,
                    // which bleeds masked-out colour into every visible edge — the halo
                    // around soft-masked and colour-keyed images.
                    let pa = data[i + 3] as u64;
                    r += data[i] as u64 * pa;
                    g += data[i + 1] as u64 * pa;
                    b += data[i + 2] as u64 * pa;
                    a += pa;
                    cnt += 1;
                }
            }
            if cnt > 0 {
                // Un-premultiply: the output buffer stays straight-alpha, which is what
                // the Kotlin side's Bitmap.createBitmap(int[], ...) expects.
                if a > 0 {
                    out[o] = (r / a) as u8;
                    out[o + 1] = (g / a) as u8;
                    out[o + 2] = (b / a) as u8;
                } else {
                    out[o] = 0;
                    out[o + 1] = 0;
                    out[o + 2] = 0;
                }
                out[o + 3] = (a / cnt) as u8;
            }
        }
    }
    Some((nw, nh, out))
}

/// Decode an image XObject to [`ImageData`], downscaling oversized decoded
/// rasters (format 0) so a single image cannot blow the device bitmap budget.
/// JPEG passthrough (format 1) is left untouched; Kotlin subsamples it on decode.
pub(crate) fn extract_image(doc: &Document, stream: &lopdf::Stream, fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let mut img = extract_image_inner(doc, stream, fill_argb, cs_resources)?;
    if img.format == 0 {
        let bilevel = is_bilevel(doc, &stream.dict);
        if let Some((nw, nh, ndata)) =
            downscale_rgba(&img.data, img.w, img.h, IMAGE_DOWNSCALE_MAX_DIM, !bilevel)
        {
            img.w = nw;
            img.h = nh;
            img.data = ndata;
        }
    }
    Some(img)
}

/// Whether the renderer should SMOOTH this image when it MAGNIFIES it.
///
/// §8.9.5.1 Table 89: `/Interpolate` defaults to **false** — "no image interpolation
/// shall be performed". Nothing in this crate reads it today and the Kotlin side sets
/// `isFilterBitmap = true` unconditionally, so every image is bilinearly smoothed on
/// upscale. For a photograph that is what a reader wants and no viewer does otherwise.
/// For BILEVEL art it is destructive in exactly the way round 1 established for the
/// downscale path: smoothing a barcode, a QR code, a scanned fax page or a stencil turns
/// its two colours into a ramp of greys and its hard 0/255 alpha into a translucent
/// fringe, which is the difference between a scannable QR code and an unreadable one.
///
/// So: honour an explicit `/Interpolate true`; otherwise smooth contone images and never
/// smooth bilevel ones. That deviates from the literal default for contone images, and
/// deliberately — the deviation is invisible, whereas obeying it would make every
/// magnified photograph blocky.
///
/// `interp` should pass this onto the `Prim::Image` record and `viewer` should carry it
/// on the wire and use it to choose `isFilterBitmap`. It is a standalone predicate rather
/// than an `ImageData` field so it can land without breaking either of their files.
///
/// UNWIRED, deliberately, and NOT dead code — do not delete it looking for a caller. The
/// policy is complete and tested (`interpolation_is_refused_for_bilevel_art`), but
/// `Prim::Image` has no field for it, so nothing serializes it and the Kotlin parser's
/// pre-v11 default (smooth) applies to everything. `wire.rs` records the rest: the wire
/// version bump to 11 and the `u8 interpolate` byte must land in the SAME change, because
/// bumping without writing the byte makes the parser eat the first byte of the image's
/// `u32 len` and desync every primitive after it. That crosses wire.rs and the Kotlin
/// side, so it is not this file's to finish.
#[allow(dead_code)]
pub(crate) fn image_should_interpolate(doc: &Document, dict: &Dictionary) -> bool {
    // `/I` is the §8.9.7 Table 93 abbreviation for /Interpolate in an inline image
    // dictionary. (As a /CS *value* `/I` means Indexed; as a KEY it is unambiguous.)
    if dict_true(doc, dict, b"Interpolate") || dict_true(doc, dict, b"I") {
        return true;
    }
    !is_bilevel(doc, dict)
}

/// A dictionary entry that is boolean `true`, dereferencing indirect objects.
/// §7.3.10 allows any object — booleans included — to be indirect, and a bare
/// `matches!(d.get(k), Some(Object::Boolean(true)))` misses `/ImageMask 12 0 R`,
/// which silently demotes a stencil to an ordinary image.
fn dict_true(doc: &Document, dict: &Dictionary, key: &[u8]) -> bool {
    matches!(
        dict.get(key).ok().and_then(|o| deref(doc, o)),
        Some(Object::Boolean(true))
    )
}

/// Whether the source image had two colours per component: a stencil, or one bit per
/// component. Fax-encoded images are bilevel by definition even without `/BitsPerComponent`.
fn is_bilevel(doc: &Document, dict: &Dictionary) -> bool {
    if dict_true(doc, dict, b"ImageMask") {
        return true;
    }
    if dict.get(b"BitsPerComponent").ok().and_then(num) == Some(1.0) {
        return true;
    }
    let specs = filters::filter_specs_from_dict(doc, dict);
    specs.iter().any(|(kind, _)| {
        matches!(kind, filters::FilterKind::Ccitt | filters::FilterKind::Jbig2)
    })
}

/// Resolve `/JBIG2Globals`, the symbol dictionary shared across pages (§7.4.7).
///
/// `specs` pairs an ARRAY `/DecodeParms` with the filter array index-by-index, which is
/// where `/Filter [/FlateDecode /JBIG2Decode]` keeps its parameters. Both JBIG2 call
/// sites then fell back to matching only a direct `Object::Dictionary` on the stream
/// dict, so an array `/DecodeParms` that `specs` could not pair — producers do emit it
/// misaligned with the filter chain — lost the globals entirely. A JBIG2 decode without
/// them fails, and a failed decode renders the region silently transparent.
///
/// §7.4 Table 5 allows `/DecodeParms` to be a dictionary or an array; `/DP` is its
/// inline-image abbreviation (§8.9.7 Table 93).
fn jbig2_globals(
    doc: &Document,
    dict: &Dictionary,
    specs: &[(filters::FilterKind, Option<Dictionary>)],
) -> Option<Vec<u8>> {
    // NOT `decompressed_content()`: that is lopdf's decoder, which implements only
    // Flate/LZW/ASCII85 and whose `unwrap_or_else` fallback hands back the still-ENCODED
    // bytes to be parsed as a symbol dictionary.
    let from_parms = |pd: &Dictionary| -> Option<Vec<u8>> {
        match pd.get(b"JBIG2Globals").ok().and_then(|o| deref(doc, o)) {
            Some(Object::Stream(gs)) => Some(stream_data_with_doc(doc, gs)),
            _ => None,
        }
    };
    if let Some(g) = specs
        .iter()
        .filter(|(k, _)| *k == filters::FilterKind::Jbig2)
        .find_map(|(_, pd)| pd.as_ref().and_then(|d| from_parms(d)))
    {
        return Some(g);
    }
    match dict
        .get(b"DecodeParms")
        .ok()
        .or_else(|| dict.get(b"DP").ok())
        .and_then(|o| deref(doc, o))
    {
        Some(Object::Dictionary(d)) => from_parms(d),
        Some(Object::Array(a)) => a
            .iter()
            .filter_map(|el| deref(doc, el).and_then(|o| o.as_dict().ok()))
            .find_map(|d| from_parms(d)),
        _ => None,
    }
}

fn extract_image_inner(doc: &Document, stream: &lopdf::Stream, fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let dict = &stream.dict;
    let w = dict.get(b"Width").ok().and_then(num)? as u32;
    let h = dict.get(b"Height").ok().and_then(num)? as u32;
    if w == 0 || h == 0 || w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM {
        image_warn!("image {}x{} outside 1..{} - dropped", w, h, MAX_IMAGE_DIM);
        return None;
    }
    // NOTE: no MAX_IMAGE_PIXELS guard here. It used to drop the image outright,
    // which deleted every high-res scan and photo, and it ran BEFORE the JPEG
    // passthrough below — which allocates no RGBA buffer at all and is subsampled
    // by the platform decoder. The budget is now enforced where memory is actually
    // committed: the raw-sample path decimates via `unpack_samples_decimated`, and
    // the codec paths cap their own decoded dimensions.
    if stream.content.len() > MAX_IMAGE_BYTES * 4 {
        // raw compressed size guard, still attempt but cap later
    }

    // Normalize filter chain case-insensitive + DecodeParms pairing
    let specs = filters::filter_specs_from_dict(doc, dict);
    let has_kind = |k: filters::FilterKind| specs.iter().any(|(kind,_)| *kind == k);

    // Legacy names fallback for callers without new parser (inline images)
    let legacy_filters = filter_names(doc, dict);
    let legacy_is_dct = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("DCTDecode") || f.eq_ignore_ascii_case("DCT"));
    let legacy_is_jpx = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("JPXDecode"));
    let is_dct = has_kind(filters::FilterKind::Dct) || legacy_is_dct;
    let is_jpx = has_kind(filters::FilterKind::Jpx) || legacy_is_jpx;
    let is_ccitt = has_kind(filters::FilterKind::Ccitt);
    let is_jbig2 = has_kind(filters::FilterKind::Jbig2);

    // A CCITT/JBIG2 stencil (`/ImageMask true`) must paint the current fill color
    // where the sample selects "paint" and be transparent elsewhere — not render
    // an opaque black/white raster. Detect it up front so the codec branches can
    // stencil their output.
    // `/Decode [1 0]` on a one-bit image swaps black and white. It applies to a stencil's
    // paint/skip sense and to a plain bilevel image's colours alike.
    let decode_inverts_1bit = matches!(
        dict.get(b"Decode").ok().and_then(|o| deref(doc, o)),
        Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)
    );
    let mask_stencil = dict_true(doc, dict, b"ImageMask");
    let mask_invert = mask_stencil && decode_inverts_1bit;

    // JBIG2: attempt with Globals
    if is_jbig2 {
        let globals_bytes = jbig2_globals(doc, dict, &specs);

        // Attempt decode from raw content
        if let Some((jw,jh,mut rgba)) = jbig2::decode_jbig2(&stream.content, globals_bytes.as_deref(), w, h) {
            if mask_stencil { stencilize(&mut rgba, fill_argb, mask_invert); return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba }); }
            let smask = read_smask(doc, dict, jw, jh);
            apply_smask(&mut rgba, &smask);
            if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
            return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba });
        }
        // If JBIG2 decode fails, try chain fallback then return transparent None (remove red placeholder artifact per P0 critical #6)
        let raw = stream.content.clone();
        if let Some(chain) = filters::decode_stream_chain(raw, &specs, doc) {
            if let Some((jw, jh, mut rgba)) = jbig2::decode_jbig2(&chain, globals_bytes.as_deref(), w, h) {
                if mask_stencil { stencilize(&mut rgba, fill_argb, mask_invert); return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba }); }
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
        }
        // Per lead's P0-4 decision: no placeholder. Stay transparent, but say why —
        // audit-a owns making these decodes actually succeed.
        image_warn!("JBIG2 {}x{} decode failed (globals: {}) - image dropped", w, h, globals_bytes.is_some());
        return None;
    }

    // JPEG2000 path
    if is_jpx {
        // §7.4.9 Table 89. /SMaskInData governs whether the codestream's own alpha
        // channel is used at all; its default is 0, i.e. "ignore it". Clamp anything
        // out of range to the default rather than guessing.
        let smask_in_data = match dict.get(b"SMaskInData").ok().and_then(|o| deref(doc, o)).and_then(num) {
            Some(v) if v == 1.0 => 1u8,
            Some(v) if v == 2.0 => 2u8,
            _ => 0u8,
        };
        // §7.4.9: "the value of ColorSpace shall override any colour space specified in
        // the JPEG2000 data". Only the spaces whose channel layout the JPX assembler
        // below can actually express are honoured. Indexed is deliberately excluded:
        // `cs_kind_image_ncomp` reports 1 for it (one palette index per sample), and
        // forcing Gray would paint raw palette indices as grey levels — worse than
        // trusting the codestream. Lab/Separation/DeviceN are excluded for the same
        // reason: their components are not device colour and would need the full
        // `eval_cs_to_rgb` path, which this decoder does not run.
        let cs_hint = dict
            .get(b"ColorSpace")
            .or_else(|_| dict.get(b"CS"))
            .ok()
            .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
            .and_then(|k| match k {
                CsKind::DeviceGray | CsKind::CalGray { .. } => Some(jp2::Interp::Gray),
                CsKind::DeviceRGB | CsKind::CalRGB { .. } => Some(jp2::Interp::Rgb),
                CsKind::DeviceCMYK => Some(jp2::Interp::Cmyk),
                CsKind::ICCBased { n, .. } => match n {
                    1 => Some(jp2::Interp::Gray),
                    3 => Some(jp2::Interp::Rgb),
                    4 => Some(jp2::Interp::Cmyk),
                    _ => None,
                },
                _ => None,
            });
        let opts = jp2::JpxOpts { smask_in_data, cs_hint };
        // Raw JPX may be after chain of Ascii/Flate decodes
        let raw = stream_data_with_doc(doc, stream);
        let try_data = [&raw[..], &stream.content[..]];
        for d in try_data {
            if let Some((jw, jh, mut rgba)) = jp2::decode_with_opts(d, opts) {
                // Table 89 forbids /SMask when /SMaskInData is nonzero, and `apply_smask`
                // REPLACES alpha rather than combining it — applying one here would throw
                // away the codestream alpha we were just told to use.
                if smask_in_data == 0 {
                    let smask = read_smask(doc, dict, jw, jh);
                    apply_smask(&mut rgba, &smask);
                    if smask.is_some() {
                        if let Some(matte) = read_matte(doc, dict) {
                            apply_matte(&mut rgba, matte);
                        }
                    }
                }
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
        }
        // JPX decode failed: do not fall through and reinterpret the encoded
        // JPEG2000 stream as raw samples.
        image_warn!("JPX {}x{} decode failed ({} bytes) - image dropped", w, h, stream.content.len());
        return None;
    }

    // CCITTFax full Group3/4 with K,Columns,BlackIs1 params
    if is_ccitt {
        let params = {
            // Find first Ccitt parms dict from specs
            let mut found = None;
            for (kind, pd) in specs.iter() {
                if *kind == filters::FilterKind::Ccitt {
                    found = Some(filters::parse_ccitt_params(doc, pd.as_ref()));
                    break;
                }
            }
            found.unwrap_or_else(|| {
                // Try DecodeParms singly
                if let Some(Object::Dictionary(d)) = dict.get(b"DecodeParms").ok().and_then(|o| deref(doc,o)) {
                    filters::parse_ccitt_params(doc, Some(d))
                } else {
                    filters::CcittParams::default()
                }
            })
        };
        // Try chain decode first for possible Ascii/Flate wrappers before CCITT
        let raw = stream.content.clone();
        let chain_bytes = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw);
        // If chain_bytes is CCITT, decode — fix #11 Columns vs Width: output raster is Columns, not max
        if let Some(packed) = filters::decode_ccitt(&chain_bytes, w, h, &params) {
            let columns = if params.columns > 0 { params.columns as usize } else { w as usize };
            let rows_est = if params.rows > 0 { params.rows as usize } else { h as usize };
            // Guard: packed already accounts for BlackIs1
            let out_w = columns as u32;
            let out_h = rows_est as u32;
            if (out_w as usize) * (out_h as usize) > MAX_IMAGE_PIXELS {
                image_warn!("CCITT raster {}x{} over the {} pixel budget - image dropped", out_w, out_h, MAX_IMAGE_PIXELS);
                return None;
            }
            let row_bytes = columns.div_ceil(8);
            let mut rgba = vec![255u8; (out_w * out_h * 4) as usize]; // white init
            // The filter emits one-bit DeviceGray samples, so 0 is black and 1 is white, and
            // `decode_ccitt` has already put the pels in that form per BlackIs1. Painting a 1
            // bit black instead rendered every default-parameter fax as its own negative,
            // which is where the solid dark blocks over scanned pages came from.
            let black_bit = if decode_inverts_1bit { 1 } else { 0 };
            for y in 0..rows_est {
                for x in 0..columns {
                    let byte = packed.get(y * row_bytes + x / 8).copied().unwrap_or(0);
                    let bit = (byte >> (7 - (x % 8))) & 1;
                    if bit == black_bit {
                        let idx = (y * out_w as usize + x) * 4;
                        if idx + 3 < rgba.len() { rgba[idx] = 0; rgba[idx+1] = 0; rgba[idx+2] = 0; rgba[idx+3] = 255; }
                    }
                }
            }
            if mask_stencil {
                // `/Decode` is already in the raster above, so the stencil must not re-apply it.
                stencilize(&mut rgba, fill_argb, false);
                return Some(ImageData { w: out_w, h: out_h, format: 0, data: rgba });
            }
            let smask = read_smask(doc, dict, out_w, out_h);
            apply_smask(&mut rgba, &smask);
            if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
            return Some(ImageData { w: out_w, h: out_h, format: 0, data: rgba });
        }
        // CCITT decode failed: don't reinterpret the encoded fax data as raw 1-bit samples.
        image_warn!("CCITT {}x{} decode failed (k={}, cols={}) - image dropped", w, h, params.k, params.columns);
        return None;
    }

    // DCT with SMask/Mask: decode to RGBA + apply mask, with gray fallback
    if is_dct {
        let smask_present = dict.get(b"SMask").is_ok();
        let mask_present = dict.get(b"Mask").is_ok();
        // Chain decode to get JPEG bytes if wrapped in Ascii etc
        let raw = stream.content.clone();
        let jpeg_bytes = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw);
        if smask_present || mask_present {
            if let Some((jw,jh,mut rgba)) = decode_jpeg_rgba(&jpeg_bytes) {
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if smask.is_some() { if let Some(matte) = read_matte(doc, dict) { apply_matte(&mut rgba, matte); } }
                if let Some(mask_alpha) = read_explicit_mask(doc, dict, jw, jh) {
                    for (i, px) in rgba.chunks_exact_mut(4).enumerate() {
                        let mv = mask_alpha.get(i).copied().unwrap_or(255) as u16;
                        px[3] = ((px[3] as u16 * mv) / 255) as u8;
                    }
                }
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
            // Fallback to gray JPEG decode + alpha
            if let Some((jw,jh,gray)) = decode_jpeg_gray(&jpeg_bytes) {
                let mut rgba = vec![0u8; (jw*jh*4) as usize];
                for i in 0..(jw*jh) as usize {
                    let g = gray.get(i).copied().unwrap_or(0);
                    rgba[i*4]=g; rgba[i*4+1]=g; rgba[i*4+2]=g; rgba[i*4+3]=255;
                }
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if let Some(mask_alpha) = read_explicit_mask(doc, dict, jw, jh) {
                    for (i, px) in rgba.chunks_exact_mut(4).enumerate() {
                        let mv = mask_alpha.get(i).copied().unwrap_or(255) as u16;
                        px[3] = ((px[3] as u16 * mv) / 255) as u8;
                    }
                }
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba });
            }
        }
        if !smask_present && !mask_present {
            // Android's bitmap decoder cannot handle CMYK/YCCK JPEGs, so decode
            // those (and any Adobe-marked JPEG) in Rust; RGB/gray pass through.
            let cmyk = jpeg_num_components(&jpeg_bytes) == Some(4)
                || jpeg_adobe_transform(&jpeg_bytes).is_some();
            if cmyk {
                if let Some((jw, jh, rgba)) = decode_jpeg_rgba(&jpeg_bytes) {
                    return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
                }
            }
            // efficient passthrough
            return Some(ImageData { w, h, format: 1, data: jpeg_bytes });
        }
        // DCT with a mask but JPEG decode failed: avoid reinterpreting the
        // encoded JPEG stream as raw samples.
        image_warn!("DCT {}x{} decode failed with mask present - image dropped", w, h);
        return None;
    }

    let image_mask = dict_true(doc, dict, b"ImageMask");
    let bpc = if image_mask { 1 } else { dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32 };
    // This project's own chain, not lopdf's: lopdf cannot undo RunLength, ASCIIHex or a
    // predictor, and returns the still-compressed bytes on failure. Unpacked as one-bit
    // samples those are noise, and a stencil of noise is a solid block of fill colour.
    let samples = stream_data_with_doc(doc, stream);

    if image_mask {
        let invert = decode_inverts_1bit;
        let fr = ((fill_argb >> 16) & 0xFF) as u8;
        let fg = ((fill_argb >> 8) & 0xFF) as u8;
        let fb = (fill_argb & 0xFF) as u8;
        let row_bytes = w.div_ceil(8) as usize;
        // With the default /Decode [0 1] a sample of 0 MARKS the page (§8.9.6.2), so
        // absent bytes must default to the no-paint bit — defaulting them to 0 turned
        // an undecodable stencil into a solid block of fill colour over the content.
        // If not even one row survived, the buffer is unusable (e.g. `stream_data_with_doc`
        // handed back still-compressed bytes) and painting anything would be noise.
        if samples.len() < row_bytes {
            image_warn!(
                "stencil /ImageMask {}x{}: {} bytes for {} per row - unusable, skipping",
                w, h, samples.len(), row_bytes
            );
            return None;
        }
        let absent: u8 = if invert { 0x00 } else { 0xFF };
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for y in 0..h as usize {
            for x in 0..w as usize {
                let byte = samples.get(y * row_bytes + x / 8).copied().unwrap_or(absent);
                let mut bit = (byte >> (7 - (x % 8))) & 1;
                if invert { bit ^= 1; }
                let idx = (y * w as usize + x) * 4;
                if bit == 0 {
                    rgba[idx]=fr; rgba[idx+1]=fg; rgba[idx+2]=fb; rgba[idx+3]=255;
                }
            }
        }
        return Some(ImageData { w, h, format: 0, data: rgba });
    }

    // Resolve the colourspace through the resource map so a NAMED entry
    // (`/ColorSpace /CS0` -> `[/ICCBased <</N 3>>]`) yields the right component
    // count. `colorspace_info` takes no cs_resources and so returned 1 for every
    // named space, making the image decode at 1/N of the true stride — the
    // classic sheared-grey-garbage look. §8.9.5.1 requires the name to be
    // resolved through the resource dictionary's /ColorSpace subdictionary.
    let cs_obj = dict.get(b"ColorSpace").or_else(|_| dict.get(b"CS")).ok();
    let ncomp = match cs_obj.and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)) {
        Some(k) => cs_kind_image_ncomp(&k),
        None => colorspace_info(doc, cs_obj).0.clamp(1, 32),
    };

    // An image over the pixel budget is DECIMATED, not dropped: the old guard
    // deleted every 600 dpi scan and every >16 MP photo outright. Keeping one
    // sample in `step` per axis bounds the RGBA buffer while still rendering.
    let step = {
        let px = (w as usize).saturating_mul(h as usize);
        let mut s = 1usize;
        while s < 64 && px / (s * s) > MAX_IMAGE_PIXELS {
            s += 1;
        }
        s
    };
    let (dw, dh, decoded_comps) = match unpack_samples_decimated(&samples, w as usize, h as usize, ncomp as usize, bpc, step) {
        Some(t) => t,
        None => {
            // Only reachable for a zero dimension/arity or an unpacked buffer over
            // MAX_UNPACKED_SAMPLE_BYTES, i.e. a bogus /DeviceN arity or /N.
            image_warn!(
                "image {}x{} x {} comps at {} bpc (step {}) could not be unpacked - dropped",
                w, h, ncomp, bpc, step
            );
            return None;
        }
    };
    let (out_w, out_h) = (dw as u32, dh as u32);
    if step > 1 {
        image_warn!("image {}x{} over pixel budget: decimated by {} to {}x{}", w, h, step, out_w, out_h);
    }

    let mut rgba = image_samples_to_rgba(doc, dict, cs_resources, &decoded_comps, dw, dh, ncomp as usize, bpc);

    // Masks are resampled to the (possibly decimated) raster, not to /Width x /Height.
    let smask = read_smask(doc, dict, out_w, out_h);
    apply_smask(&mut rgba, &smask);
    // /Matte: base colors are premultiplied against a matte background; undo it
    // using the SMask alpha we just applied.
    if smask.is_some() {
        if let Some(matte) = read_matte(doc, dict) {
            apply_matte(&mut rgba, matte);
        }
    }
    // Explicit stencil /Mask image (mutually exclusive with color-key /Mask).
    if let Some(mask_alpha) = read_explicit_mask(doc, dict, out_w, out_h) {
        for (i, px) in rgba.chunks_exact_mut(4).enumerate() {
            let mv = mask_alpha.get(i).copied().unwrap_or(255) as u16;
            px[3] = ((px[3] as u16 * mv) / 255) as u8;
        }
    }
    // Color-key masking is compared against the pre-conversion samples so it is
    // correct for CMYK/DeviceN (not just DeviceRGB/Gray). Indexed images key on
    // the index value, which `decoded_comps` already holds (ncomp==1).
    if let Some(ranges_raw) = read_color_key_ranges_raw(doc, dict) {
        apply_color_key_mask_samples(&mut rgba, &decoded_comps, ncomp as usize, &ranges_raw, bpc);
    }
    Some(ImageData { w: out_w, h: out_h, format: 0, data: rgba })
}

pub(crate) fn extract_inline_image(doc: &Document, stream: &lopdf::Stream, fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let dict = &stream.dict;
    let w = dict.get(b"Width").or_else(|_| dict.get(b"W")).ok().and_then(num)? as u32;
    let h = dict.get(b"Height").or_else(|_| dict.get(b"H")).ok().and_then(num)? as u32;
    if w==0 || h==0 || w>MAX_IMAGE_DIM || h>MAX_IMAGE_DIM {
        image_warn!("inline image {}x{} outside 1..{} - dropped", w, h, MAX_IMAGE_DIM);
        return None;
    }
    if (w as usize)*(h as usize) > MAX_IMAGE_PIXELS {
        image_warn!("inline image {}x{} over the {} pixel budget - dropped", w, h, MAX_IMAGE_PIXELS);
        return None;
    }

    // §8.9.7 Table 93 abbreviates the stencil flag to `/IM`, and this function checked
    // only the long form — so an inline image mask was decoded as an ordinary one-bit
    // DeviceGray image and painted as an OPAQUE black-and-white rectangle over the page
    // instead of stencilling the current fill colour. Inline stencils are the classic way
    // to embed a small logo or rule, so this showed up as black boxes hiding content.
    // (`fill_argb` was even bound as `_fill_argb`, which is how it went unnoticed.)
    let image_mask = dict_true(doc, dict, b"ImageMask") || dict_true(doc, dict, b"IM");
    // A stencil is one bit per sample by definition (§8.9.6.2), whatever /BPC claims.
    let bpc = if image_mask {
        1
    } else {
        dict.get(b"BitsPerComponent").or_else(|_| dict.get(b"BPC")).ok().and_then(num).unwrap_or(8.0) as u32
    };

    let cs_obj = dict.get(b"ColorSpace").or_else(|_| dict.get(b"CS")).ok().cloned();
    // Resolve through the resource map, as the XObject path does. `colorspace_info`
    // takes no `cs_resources` and returns 1 for every NAMED space — including
    // `/CS /Cs0` and the `/I` (Indexed) abbreviation §8.9.7 permits — so the image
    // decoded at 1/N of its true stride and came out as sheared grey garbage. This is
    // the same defect round 1 fixed for image XObjects and left here.
    let ncomp = if image_mask {
        1
    } else {
        match cs_obj.as_ref().and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)) {
            Some(k) => cs_kind_image_ncomp(&k),
            None => cs_obj.as_ref().map(|o| colorspace_info(doc, Some(o)).0).unwrap_or(1).clamp(1, 32),
        }
    };

    // Support filter chain for inline BI: may have Flate, AHx, A85 etc.
    let specs = filters::filter_specs_from_dict(doc, dict);
    let raw = stream.content.clone();
    let samples = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw.clone());

    // DCT inline
    let legacy_filters = filter_names(doc, dict);
    let mut all_has_dct = specs.iter().any(|(k,_)| *k == filters::FilterKind::Dct);
    if !all_has_dct {
        all_has_dct = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("DCTDecode")||f.eq_ignore_ascii_case("DCT"));
    }
    if all_has_dct {
        let smask = read_smask(doc, dict, w, h);
        let colorkey = read_color_key_mask(doc, dict);
        if let Some((jw,jh,mut rgba)) = decode_jpeg_rgba(&samples) {
            apply_smask(&mut rgba, &smask);
            apply_color_key_mask(&mut rgba, &colorkey);
            return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
        }
        return Some(ImageData { w, h, format: 1, data: samples });
    }

    // For other filters, samples is already chain-decoded
    let unpacked = match unpack_samples_to_bytes(&samples, w as usize, h as usize, ncomp as usize, bpc) {
        Some(u) => u,
        None => {
            image_warn!("inline image {}x{}x{} at {} bpc could not be unpacked - dropped", w, h, ncomp, bpc);
            return None;
        }
    };
    if image_mask {
        // `/D [1 0]` (`/Decode`) swaps the paint/skip sense (§8.9.6.2). With the default
        // decode a sample of 0 MARKS the page; `unpack_samples_to_bytes` has already
        // scaled the one-bit samples to 0 or 255.
        let invert = matches!(
            dict.get(b"Decode").or_else(|_| dict.get(b"D")).ok().and_then(|o| deref(doc, o)),
            Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)
        );
        let (fr, fg, fb) = (
            ((fill_argb >> 16) & 0xFF) as u8,
            ((fill_argb >> 8) & 0xFF) as u8,
            (fill_argb & 0xFF) as u8,
        );
        let mut rgba = vec![0u8; (w as usize) * (h as usize) * 4];
        for (i, px) in rgba.chunks_exact_mut(4).enumerate() {
            let mut sample = unpacked.get(i).copied().unwrap_or(255);
            if invert {
                sample = 255 - sample;
            }
            if sample == 0 {
                px[0] = fr; px[1] = fg; px[2] = fb; px[3] = 255;
            }
        }
        return Some(ImageData { w, h, format: 0, data: rgba });
    }
    let mut rgba = image_samples_to_rgba(doc, dict, cs_resources, &unpacked, w as usize, h as usize, ncomp as usize, bpc);
    let smask = read_smask(doc, dict, w, h);
    apply_smask(&mut rgba, &smask);
    if let Some(ranges_raw) = read_color_key_ranges_raw(doc, dict) {
        apply_color_key_mask_samples(&mut rgba, &unpacked, ncomp as usize, &ranges_raw, bpc);
    }
    Some(ImageData { w, h, format: 0, data: rgba })
}

/// Radial (Type 3) shading parameter at a point in shading space.
///
/// The gradient is defined by the family of circles C(s) centered at
/// c0 + s·(c1−c0) with radius r0 + s·(r1−r0), for `coords` = [x0 y0 r0 x1 y1 r1].
/// For a point (fx,fy) this returns the largest `s` such that the point lies on
/// C(s) with a non-negative radius, honoring the two `/Extend` flags (`e0` past
/// s<0, `e1` past s>1). Solving |p − c(s)| = r(s) yields the quadratic
/// a·s² − 2b·s + c = 0. Returns `None` when no circle covers the point (the
/// caller leaves that pixel transparent).
pub(crate) fn radial_shading_param(coords: &[f64], e0: bool, e1: bool, fx: f64, fy: f64) -> Option<f64> {
    if coords.len() < 6 { return None; }
    let (x0, y0, r0) = (coords[0], coords[1], coords[2]);
    let (x1, y1, r1) = (coords[3], coords[4], coords[5]);
    let dx = x1 - x0;
    let dy = y1 - y0;
    let dr = r1 - r0;
    let px = fx - x0;
    let py = fy - y0;
    let a = dx*dx + dy*dy - dr*dr;
    let b = px*dx + py*dy + r0*dr;
    let c = px*px + py*py - r0*r0;

    let mut best: Option<f64> = None;
    let mut consider = |s: f64| {
        // The interpolated circle radius must be non-negative.
        if r0 + s*dr < 0.0 { return; }
        // Respect the shading domain unless extended past an end.
        let in_range = (0.0..=1.0).contains(&s) || (s < 0.0 && e0) || (s > 1.0 && e1);
        if !in_range { return; }
        best = Some(match best { Some(cur) if cur >= s => cur, _ => s });
    };
    if a.abs() < 1e-9 {
        // Degenerate to a linear equation: -2b·s + c = 0.
        if b.abs() > 1e-12 { consider(c / (2.0*b)); }
    } else {
        let disc = b*b - a*c;
        if disc >= 0.0 {
            let sq = disc.sqrt();
            consider((b + sq)/a);
            consider((b - sq)/a);
        }
    }
    best
}

pub(crate) fn rasterize_shading(doc: &Document, shading_obj: &Object, base_ctm: &Mat, cs_resources: &HashMap<Vec<u8>, ObjectId>, size: u32, clip_bbox_device: Option<[f64;4]>) -> Option<(Mat, u32, u32, Vec<u8>)> {
    // A shading may be a plain dictionary (Type 1-3) or a stream (Type 4-7,
    // whose mesh data lives in the stream body).
    let (dict, mesh_bytes): (&lopdf::Dictionary, Option<Vec<u8>>) = match shading_obj {
        Object::Dictionary(d) => (d, None),
        Object::Stream(s) => (&s.dict, Some(stream_data_with_doc(doc, s))),
        _ => return None,
    };
    let shading_type = dict.get(b"ShadingType").ok().and_then(num).unwrap_or(0.0) as i64;
    // When the caller passes size==0, derive an effective resolution from the
    // device footprint of the clip region (falls back to 256 if unknown).
    let auto_size = || -> u32 {
        match clip_bbox_device {
            Some(b) => (((b[2]-b[0]).abs()).max((b[3]-b[1]).abs()).ceil() as u32).clamp(64, 1024),
            None => 256,
        }
    };
    let size = if size == 0 { auto_size() } else { size };
    if shading_type==4 || shading_type==5 || shading_type==6 || shading_type==7 {
        // Mesh shadings: pass the decoded stream body so real vertices/patches
        // can be parsed (falls back to /DataSource when the dict provides one).
        return shading::rasterize_shading_mesh(doc, dict, mesh_bytes.as_deref(), base_ctm, cs_resources, size);
    }
    if shading_type==1 {
        return rasterize_shading_function_based(doc, dict, base_ctm, cs_resources, size);
    }
    if shading_type!=2 && shading_type!=3 { return None; }

    let coords = dict.get(b"Coords").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect::<Vec<f64>>()).unwrap_or_default();
    // Background color for areas outside Extend
    let bg = dict.get(b"Background").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect::<Vec<f64>>());

    // /BBox bounds the shading in shading space. When absent, cover the current
    // clip region instead of an arbitrary 100×100 box: map the device clip bbox
    // back into shading space via the inverse CTM.
    //
    // With no /BBox AND no clip bbox we now paint NOTHING rather than inventing a
    // 100x100 shading-space box, which rendered the gradient as a small patch near
    // the origin with the rest of the region unpainted. §8.7.4.1 requires `sh` to
    // cover the entire clipping region, so a guessed box is always wrong; returning
    // None pushes no Image prim at all. audit-b seeds current_clip_bbox from the page
    // device box, so the None arm should be unreachable in practice.
    let bbox = match dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
        Some(b) => b,
        None => {
            let cb = clip_bbox_device?;
            let inv = mat_inverse(base_ctm);
            let corners = [
                transform(&inv, cb[0], cb[1]), transform(&inv, cb[2], cb[1]),
                transform(&inv, cb[2], cb[3]), transform(&inv, cb[0], cb[3]),
            ];
            let xs = corners.iter().map(|p| p.0);
            let ys = corners.iter().map(|p| p.1);
            let x0 = xs.clone().fold(f64::INFINITY, f64::min);
            let x1 = xs.fold(f64::NEG_INFINITY, f64::max);
            let y0 = ys.clone().fold(f64::INFINITY, f64::min);
            let y1 = ys.fold(f64::NEG_INFINITY, f64::max);
            if x1 > x0 && y1 > y0 {
                [x0, y0, x1, y1]
            } else {
                return None;
            }
        }
    };

    let color_space_obj = dict.get(b"ColorSpace").ok().and_then(|o| parse_cs_kind(doc, Some(o), cs_resources));
    // If CS not present, try to infer from Function or Background etc.

    // Function: full PDF function support (Type 0/2/3/4, or array-of-functions).
    let pdf_func = dict.get(b"Function").ok().and_then(|o| PdfFunction::parse(doc, o));

    // Extend [bool bool] — spec default is [false false] (ISO 32000 Table 79).
    let extend = dict.get(b"Extend").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).map(|v| matches!(v, Object::Boolean(true)))).collect::<Vec<bool>>()).unwrap_or(vec![false,false]);
    // Domain [t0 t1] maps the normalized axis parameter to the function domain
    // (default [0 1]).
    let domain = dict.get(b"Domain").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect::<Vec<f64>>())
        .filter(|v| v.len() >= 2).unwrap_or(vec![0.0, 1.0]);

    // The raster used to be square (`let w = size; let h = size;`), so a 600x5 pt
    // gradient bar allocated size² pixels — up to ~100x the memory the shading
    // actually covers, tripled across prims + wire buffer + Kotlin Bitmap. Size each
    // axis from the bbox's DEVICE-space extent, keeping the long side at `size`.
    let (w, h) = {
        // `size` is guaranteed >=64 by the auto_size() shadowing above, but clamp(1, n)
        // panics when n == 0, so don't depend on a distant invariant for memory safety.
        let long = size.max(1);
        let ext_x = (bbox[2] - bbox[0]).abs();
        let ext_y = (bbox[3] - bbox[1]).abs();
        // A bbox-space edge of length L maps to a device vector of length
        // |(a·L, b·L)| horizontally and |(c·L, d·L)| vertically.
        let dev_x = ((base_ctm[0] * ext_x).powi(2) + (base_ctm[1] * ext_x).powi(2)).sqrt();
        let dev_y = ((base_ctm[2] * ext_y).powi(2) + (base_ctm[3] * ext_y).powi(2)).sqrt();
        if !dev_x.is_finite() || !dev_y.is_finite() || dev_x <= 0.0 || dev_y <= 0.0 {
            (long, long)
        } else if dev_x >= dev_y {
            (long, ((long as f64 * dev_y / dev_x).round() as u32).clamp(1, long))
        } else {
            (((long as f64 * dev_x / dev_y).round() as u32).clamp(1, long), long)
        }
    };
    // Bound a SINGLE shading's raster. `auto_size()` keeps the long side <=1024, so a
    // near-square shading still reaches 1024*1024*4 = 4 MB, and these are NOT transient
    // in aggregate: every shading raster on the page is held simultaneously in `prims`
    // and then copied wholesale into the wire buffer, so a 131-shading page peaks at
    // hundreds of MB inside Rust before Kotlin sees a byte. A Rust OOM is an
    // uncatchable process abort, so the cumulative cap on the Kotlin bitmap heap
    // (audit-e/audit-g) cannot substitute for a floor here.
    //
    // Scale BOTH axes by the same factor so the aspect ratio derived above survives.
    // This path only ever handles types 2 and 3, whose colour comes from a 256-entry LUT
    // over a single scalar — so MAX_GRADIENT_RASTER_BYTES rather than the general
    // MAX_SHADING_RASTER_BYTES the mesh path in shading.rs uses. See that constant for
    // why the tighter bound costs no fidelity at all here.
    let (w, h) = {
        let bytes = (w as usize).saturating_mul(h as usize).saturating_mul(4);
        if bytes > MAX_GRADIENT_RASTER_BYTES {
            let scale = (MAX_GRADIENT_RASTER_BYTES as f64 / bytes as f64).sqrt();
            (
                ((w as f64 * scale).round() as u32).max(1),
                ((h as f64 * scale).round() as u32).max(1),
            )
        } else {
            (w, h)
        }
    };
    let mut rgba = vec![0u8; (w*h*4) as usize];

    // Helpers to evaluate color at t 0..1 via function. The normalized parameter
    // is first mapped through /Domain before the function is evaluated.
    let eval_func = |t: f64| -> Option<Vec<f64>> {
        if let Some(ref f) = pdf_func {
            let td = domain[0] + t * (domain[1] - domain[0]);
            return Some(f.eval(&[td]));
        }
        // If no function, try to use Background as single color.
        if let Some(ref bgc) = bg {
            return Some(bgc.clone());
        }
        None
    };

    // Precompute a 256-entry color LUT over the normalized parameter t∈[0,1].
    // Axial/radial color depends only on t, so evaluating the PDF function and
    // colorspace conversion once per LUT slot instead of once per pixel turns an
    // O(size²) per-pixel function-eval into O(256) — the dominant cost on
    // gradient-heavy pages (e.g. issue #321 missinggraphic: 131 shadings).
    let cs_for_lut = color_space_obj.as_ref().unwrap_or(&CsKind::DeviceRGB);
    let mut color_lut: [Option<u32>; 256] = [None; 256];
    let mut lut_fallback: [Option<[u8; 4]>; 256] = [None; 256];
    for (i, slot) in color_lut.iter_mut().enumerate() {
        let t = i as f64 / 255.0;
        if let Some(comps) = eval_func(t) {
            if let Some(argb) = eval_cs_to_rgb(doc, cs_for_lut, &comps, cs_resources) {
                *slot = Some(argb);
            } else {
                let v = (comps.first().copied().unwrap_or(0.0) * 255.0) as u8;
                lut_fallback[i] = Some([v, v, v, 255]);
            }
        }
    }
    // Background color for out-of-range pixels, computed once.
    let bg_argb = bg.as_ref().and_then(|bgc| {
        let cs = color_space_obj.as_ref().unwrap_or(&CsKind::DeviceRGB);
        eval_cs_to_rgb(doc, cs, bgc, cs_resources)
    });

    for y in 0..h as usize {
        for x in 0..w as usize {
            // map pixel to shading BBox space
            let fx = bbox[0] + (x as f64 + 0.5)/ w as f64 * (bbox[2]-bbox[0]);
            // Raster row 0 is the TOP of the image, i.e. unit-square v=1, i.e. the HIGH-y
            // edge of the bbox (8.9.5.2: the first sample of the first row is at the
            // upper-left). Sampling upward from bbox[1] instead put row 0 at LOW y, and the
            // placement CTM's positive `d` then mirrored every gradient vertically - an
            // axial ramp ran backwards. Real decoded images are top-down and already
            // correct, so the fix belongs in the synthesised rasters, not the renderer.
            let fy = bbox[3] - (y as f64 + 0.5)/ h as f64 * (bbox[3]-bbox[1]);

            let t = if shading_type==2 {
                // Axial: coords [x0 y0 x1 y1]; project point onto line
                if coords.len()>=4 {
                    let x0=coords[0]; let y0=coords[1]; let x1=coords[2]; let y1=coords[3];
                    let dx = x1 - x0;
                    let dy = y1 - y0;
                    let len2 = dx*dx+dy*dy;
                    if len2<1e-12 {
                        0.0
                    } else {
                        ((fx - x0)*dx + (fy - y0)*dy)/len2
                    }
                } else { 0.0 }
            } else {
                // Radial (Type 3): solve for the gradient parameter at (fx,fy).
                if coords.len()>=6 {
                    radial_shading_param(
                        &coords,
                        extend.first().copied().unwrap_or(false),
                        extend.get(1).copied().unwrap_or(false),
                        fx, fy,
                    ).unwrap_or(f64::NAN)
                } else { 0.0 }
            };

            // Radial pixels not covered by any circle stay transparent.
            if t.is_nan() {
                continue;
            }

            // Extend handling. Out-of-range pixels use the precomputed
            // background color (or stay transparent when none is defined).
            let idx = (y * w as usize + x) * 4;
            let t_clamped = if t < 0.0 {
                if extend.first().copied().unwrap_or(false) {
                    0.0
                } else {
                    if let Some(argb) = bg_argb {
                        rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                        rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                        rgba[idx + 2] = (argb & 0xFF) as u8;
                        rgba[idx + 3] = 255;
                    }
                    continue;
                }
            } else if t > 1.0 {
                if extend.get(1).copied().unwrap_or(false) {
                    1.0
                } else {
                    if let Some(argb) = bg_argb {
                        rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                        rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                        rgba[idx + 2] = (argb & 0xFF) as u8;
                        rgba[idx + 3] = 255;
                    }
                    continue;
                }
            } else {
                t
            };

            // Look up the gradient color from the precomputed LUT.
            let li = ((t_clamped.clamp(0.0, 1.0) * 255.0).round() as usize).min(255);
            if let Some(argb) = color_lut[li] {
                rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                rgba[idx + 2] = (argb & 0xFF) as u8;
                rgba[idx + 3] = 255;
            } else if let Some(px) = lut_fallback[li] {
                rgba[idx] = px[0];
                rgba[idx + 1] = px[1];
                rgba[idx + 2] = px[2];
                rgba[idx + 3] = px[3];
            } else if let Some(argb) = bg_argb {
                rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                rgba[idx + 2] = (argb & 0xFF) as u8;
                rgba[idx + 3] = 255;
            }
        }
    }

    // Map bbox to base CTM: we produce an image that covers bbox rectangle
    // CTM to place: translate bbox[0],bbox[1] and scale bboxW, bboxH
    let bw = bbox[2]-bbox[0];
    let bh = bbox[3]-bbox[1];
    // shading CTM: [bw 0 0 bh bbox0 bbox1] * base_ctm
    let shading_mat: Mat = [bw, 0.0, 0.0, bh, bbox[0], bbox[1]];
    let ctm = mat_mul(&shading_mat, base_ctm);
    Some((ctm, w, h, rgba))
}

/// Type 1 (function-based) shading: sample the 2-D `/Domain` through the
/// shading `/Function` and emit the result as an image, placed via `/Matrix`.
fn rasterize_shading_function_based(doc: &Document, dict: &lopdf::Dictionary, base_ctm: &Mat, cs_resources: &HashMap<Vec<u8>, ObjectId>, size: u32) -> Option<(Mat, u32, u32, Vec<u8>)> {
    if size == 0 || size > 1024 { return None; }
    let domain: Vec<f64> = dict.get(b"Domain").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect()).unwrap_or_else(|| vec![0.0, 1.0, 0.0, 1.0]);
    if domain.len() < 4 { return None; }
    let (dx0, dx1, dy0, dy1) = (domain[0], domain[1], domain[2], domain[3]);
    if (dx1 - dx0).abs() < 1e-9 || (dy1 - dy0).abs() < 1e-9 { return None; }

    let matrix: Mat = dict.get(b"Matrix").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_array().ok())
        .and_then(|a| {
            let v: Vec<f64> = a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect();
            if v.len() == 6 { Some([v[0], v[1], v[2], v[3], v[4], v[5]]) } else { None }
        }).unwrap_or(IDENTITY);

    let func = dict.get(b"Function").ok().and_then(|o| PdfFunction::parse(doc, o))?;
    let cs = dict.get(b"ColorSpace").ok().and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceRGB);

    // Image [0,1]^2 -> domain rect -> Matrix -> base CTM.
    let unit_to_domain: Mat = [dx1 - dx0, 0.0, 0.0, dy1 - dy0, dx0, dy0];
    let ctm = mat_mul(&mat_mul(&unit_to_domain, &matrix), base_ctm);

    // Size each axis from its DEVICE-space extent instead of forcing a square. Types 2/3
    // and the mesh types were fixed in round 1 but this one was missed: a /Domain of
    // [0 600 0 5] still allocated size*size, up to ~100x the memory the shading covers
    // and tripled across `prims`, the wire buffer and the Kotlin Bitmap. `ctm` maps the
    // unit square, so a non-square raster is geometrically identical.
    let (w, h) = {
        let long = size.max(1);
        let dev_x = (ctm[0] * ctm[0] + ctm[1] * ctm[1]).sqrt();
        let dev_y = (ctm[2] * ctm[2] + ctm[3] * ctm[3]).sqrt();
        if !dev_x.is_finite() || !dev_y.is_finite() || dev_x <= 0.0 || dev_y <= 0.0 {
            (long, long)
        } else if dev_x >= dev_y {
            (long, ((long as f64 * dev_y / dev_x).round() as u32).clamp(1, long))
        } else {
            (((long as f64 * dev_x / dev_y).round() as u32).clamp(1, long), long)
        }
    };
    // And bound the total bytes, as the other two paths already do — see
    // MAX_SHADING_RASTER_BYTES. Scaling both axes by sqrt preserves the aspect ratio.
    let (w, h) = {
        let bytes = (w as usize).saturating_mul(h as usize).saturating_mul(4);
        if bytes > MAX_SHADING_RASTER_BYTES {
            let scale = (MAX_SHADING_RASTER_BYTES as f64 / bytes as f64).sqrt();
            (
                ((w as f64 * scale).round() as u32).max(1),
                ((h as f64 * scale).round() as u32).max(1),
            )
        } else {
            (w, h)
        }
    };

    let (wu, hu) = (w as usize, h as usize);
    let mut rgba = vec![0u8; wu * hu * 4];
    for py in 0..hu {
        for px in 0..wu {
            let u = (px as f64 + 0.5) / wu as f64;
            // Row 0 is the top of the image (v=1), so walk the domain's y DOWNWARD. See
            // the axial/radial loop for why.
            let v = 1.0 - (py as f64 + 0.5) / hu as f64;
            let x = dx0 + u * (dx1 - dx0);
            let y = dy0 + v * (dy1 - dy0);
            let comps = func.eval(&[x, y]);
            let idx = (py * wu + px) * 4;
            if let Some(argb) = eval_cs_to_rgb(doc, &cs, &comps, cs_resources) {
                rgba[idx] = ((argb >> 16) & 0xFF) as u8;
                rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
                rgba[idx + 2] = (argb & 0xFF) as u8;
                rgba[idx + 3] = 255;
            }
        }
    }
    Some((ctm, w, h, rgba))
}


/// Apply a per-pixel soft-mask alpha (length `w*h`) to an RGBA buffer.
/// Rasterize ONE tiling-pattern cell for a repeating-image fill (§8.7.3.3).
///
/// `cell_prims` must be the pattern cell's content interpreted in PATTERN space (the
/// pattern `/Matrix` NOT yet applied), so `bbox`, `xstep` and `ystep` share their
/// coordinate system. `device_scale` converts pattern-space units to device pixels and
/// sets the raster resolution.
///
/// Returns `(w, h, rgba)` for a cell that can be tiled periodically, or `None` when the
/// pattern CANNOT be expressed that way — in which case the caller must fall back to the
/// per-tile path. This function owns the two rules that make a periodic repeat correct,
/// because both are easy to violate silently:
///
/// 1. The raster covers `/XStep` × `/YStep`, NOT the `/BBox`. A `BitmapShader` in REPEAT
///    mode has a period equal to the bitmap's own dimensions, and PDF's step is
///    independent of the bbox — so a bbox-sized cell retiles at the wrong spacing, which
///    looks like a pattern at subtly wrong density. Where step > bbox the margin is
///    transparent padding, which falls out of rasterizing the STEP rect anchored at the
///    bbox origin: nothing paints there.
/// 2. Overlapping patterns (a step SMALLER than the bbox, 8.7.3.1) are still periodic,
///    with period `(xstep, ystep)` - that is what makes them a lattice at all. What fails
///    is rasterizing the cell ONCE, because content from neighbouring cells spills into
///    every period window. So the cell is drawn at every lattice offset that can reach
///    the window, composited in increasing lattice order to honour "later tiles paint
///    over earlier".
///
///    This is EXACT, not an approximation. 8.7.3.2 replicates the cell across the entire
///    plane and the fill path does the clipping, so the lattice has no boundary; with no
///    boundary every point's set of overlapping contributions is identical modulo the
///    period. Refused only when the bbox/step ratio is pathological.
pub(crate) fn rasterize_pattern_cell(
    cell_prims: &[Prim],
    bbox: [f64; 4],
    xstep: f64,
    ystep: f64,
    device_scale: f64,
) -> Option<(u32, u32, Vec<u8>)> {
    let bw = (bbox[2] - bbox[0]).abs();
    let bh = (bbox[3] - bbox[1]).abs();
    if !xstep.is_finite() || !ystep.is_finite() || xstep <= 0.0 || ystep <= 0.0 {
        return None;
    }
    // Rule 2. An overlapping pattern IS periodic with period (xstep, ystep); the cell
    // just has to be drawn at every offset that can reach one period window. Cell
    // `(i, 0)` paints x in [bx0 + i*xstep, bx0 + i*xstep + bw), which meets the window
    // [bx0, bx0 + xstep) exactly when -bw/xstep < i < 1, i.e. i in -(nx_off-1)..=0.
    let nx_off = (bw / xstep).ceil().max(1.0);
    let ny_off = (bh / ystep).ceil().max(1.0);
    // A bbox 8x the step in both axes is already far beyond anything real; past that the
    // compositing cost stops being worth it and the per-tile path is the honest answer.
    const MAX_CELL_OVERLAP_COPIES: f64 = 64.0;
    if !nx_off.is_finite() || !ny_off.is_finite() || nx_off * ny_off > MAX_CELL_OVERLAP_COPIES {
        image_warn!(
            "tiling pattern bbox {:.3}x{:.3} over step {:.3}x{:.3} needs {}x{} overlapping \
             copies per period - using the per-tile path",
            bw, bh, xstep, ystep, nx_off, ny_off
        );
        return None;
    }
    if !device_scale.is_finite() || device_scale <= 0.0 {
        return None;
    }
    let mut w = (xstep * device_scale).round().max(1.0);
    let mut h = (ystep * device_scale).round().max(1.0);
    // Fit the cell into its budget, preserving the aspect so the period stays square in
    // pattern space. Downscaling a cell only softens it; the tiling stays exact because
    // the renderer's period is the bitmap, whatever its resolution.
    let budget = MAX_TILE_RASTER_BYTES as f64 / 4.0;
    if w * h > budget {
        let s = (budget / (w * h)).sqrt();
        w = (w * s).round().max(1.0);
        h = (h * s).round().max(1.0);
    }
    let (w, h) = (w as usize, h as usize);
    // Rule 1: the STEP rect, anchored at the bbox origin. Content is placed at the bbox
    // origin and anything between the bbox edge and the step edge is simply never
    // painted, which IS the transparent padding.
    let step_box = [bbox[0], bbox[1], bbox[0] + xstep, bbox[1] + ystep];
    // The common non-overlapping case needs no copies at all.
    if nx_off <= 1.0 && ny_off <= 1.0 {
        return rasterize_prims_to_rgba(cell_prims, step_box, w, h)
            .map(|rgba| (w as u32, h as u32, rgba));
    }
    // Overlapping: draw the cell at each reaching offset, in increasing lattice order so
    // later tiles composite over earlier ones (8.7.3.1).
    let (nxo, nyo) = (nx_off as i64, ny_off as i64);
    let mut spread: Vec<Prim> = Vec::with_capacity(cell_prims.len() * (nxo * nyo) as usize);
    for jj in 0..nyo {
        let dy = ((jj - (nyo - 1)) as f64 * ystep) as f32;
        for ii in 0..nxo {
            let dx = ((ii - (nxo - 1)) as f64 * xstep) as f32;
            spread.extend(cell_prims.iter().filter_map(|p| translated_prim(p, dx, dy)));
        }
    }
    rasterize_prims_to_rgba(&spread, step_box, w, h).map(|rgba| (w as u32, h as u32, rgba))
}

/// A `Prim::Fill` or `Prim::Stroke` translated by `(dx, dy)`, or `None` for any other
/// variant. `Prim` is deliberately not `Clone` because it owns image pixel buffers, and
/// these two are the only variants [`rasterize_prims_to_rgba`] draws anyway.
fn translated_prim(p: &Prim, dx: f32, dy: f32) -> Option<Prim> {
    match p {
        Prim::Fill { argb, even_odd, contours, blend } => Some(Prim::Fill {
            argb: *argb,
            even_odd: *even_odd,
            blend: *blend,
            contours: contours
                .iter()
                .map(|c| c.iter().map(|&(x, y)| (x + dx, y + dy)).collect())
                .collect(),
        }),
        Prim::Stroke { argb, width, dash, dash_phase, cap, join, miter, pts, blend } => {
            Some(Prim::Stroke {
                argb: *argb,
                width: *width,
                dash: dash.clone(),
                dash_phase: *dash_phase,
                cap: *cap,
                join: *join,
                miter: *miter,
                blend: *blend,
                pts: pts.iter().map(|&(x, y)| (x + dx, y + dy)).collect(),
            })
        }
        _ => None,
    }
}

/// Rasterize display-space primitives into an RGBA8888 buffer.
///
/// Everything else in this crate turns PDF content into [`Prim`]s for the renderer to
/// paint; this goes the other way, for the one case that needs a *bitmap* of some
/// content rather than more primitives: a single tiling-pattern cell (§8.7.3.3).
/// Replicating one small bitmap over a hatched region costs a fixed amount of memory,
/// so the RASTER can be capped and the tile COUNT left alone — which is the whole point,
/// since capping the tile count is what left 99% of a hatched area blank.
///
/// `device_box` is the display-space rect the raster covers, and raster row 0 is its LOW
/// y edge — the same convention [`rasterize_shading`] uses, so the same unit-square
/// placement CTM (`[bw, 0, 0, bh, x0, y0] * base`) positions the result.
///
/// Coverage is antialiased with 2 vertical subsamples and analytic horizontal spans:
/// hatch and crosshatch cells are made of hairlines, and without coverage AA a thin
/// diagonal rule drops out of a small raster entirely rather than merely looking rough.
///
/// `Prim::Text`, `Prim::Image`, clip and group primitives are ignored. A pattern cell's
/// clip is the caller's cell rectangle, and there is no glyph rasterizer here — a cell
/// whose content is text or an image must still go through the primitive path.
///
/// Returns `None` for a degenerate box or a request over [`MAX_TILE_RASTER_BYTES`].
pub(crate) fn rasterize_prims_to_rgba(
    prims: &[Prim],
    device_box: [f64; 4],
    w: usize,
    h: usize,
) -> Option<Vec<u8>> {
    let bw = device_box[2] - device_box[0];
    let bh = device_box[3] - device_box[1];
    if w == 0 || h == 0 || !bw.is_finite() || !bh.is_finite() || bw.abs() < 1e-12 || bh.abs() < 1e-12 {
        return None;
    }
    if w.saturating_mul(h).saturating_mul(4) > MAX_TILE_RASTER_BYTES {
        return None;
    }
    // Accumulate PREMULTIPLIED so source-over compositing of overlapping translucent
    // fills is correct; un-premultiplied at the end because the Kotlin side's
    // `Bitmap.createBitmap(int[], ...)` expects straight alpha.
    let mut acc = vec![0u8; w * h * 4];
    let to_px = |x: f64| (x - device_box[0]) / bw * w as f64;
    // Raster row 0 is the TOP of the image (unit-square v=1, 8.9.5.2), so device-space
    // HIGH y maps to py 0. Mapping low y to row 0 mirrored every cell vertically.
    let to_py = |y: f64| (device_box[3] - y) / bh * h as f64;

    for prim in prims {
        let (argb, contours, even_odd) = match prim {
            Prim::Fill { argb, contours, even_odd, .. } => (
                *argb,
                contours
                    .iter()
                    .map(|c| c.iter().map(|&(x, y)| (x as f64, y as f64)).collect())
                    .collect::<Vec<Vec<(f64, f64)>>>(),
                *even_odd,
            ),
            Prim::Stroke { argb, width, pts, .. } => {
                // Expand the polyline to its outline: one quad per segment plus a square
                // at each vertex standing in for the join and the cap. Filled as ONE
                // nonzero path so overlaps union instead of compositing twice, which
                // would darken every join of a translucent stroke.
                let hw = (*width as f64 / 2.0).max(0.35);
                let mut quads: Vec<Vec<(f64, f64)>> = Vec::new();
                for seg in pts.windows(2) {
                    let (x0, y0) = (seg[0].0 as f64, seg[0].1 as f64);
                    let (x1, y1) = (seg[1].0 as f64, seg[1].1 as f64);
                    let (dx, dy) = (x1 - x0, y1 - y0);
                    let len = (dx * dx + dy * dy).sqrt();
                    if len < 1e-9 {
                        continue;
                    }
                    let (nx, ny) = (-dy / len * hw, dx / len * hw);
                    quads.push(vec![
                        (x0 + nx, y0 + ny), (x1 + nx, y1 + ny),
                        (x1 - nx, y1 - ny), (x0 - nx, y0 - ny),
                    ]);
                }
                for p in pts.iter() {
                    let (x, y) = (p.0 as f64, p.1 as f64);
                    quads.push(vec![
                        (x - hw, y - hw), (x + hw, y - hw), (x + hw, y + hw), (x - hw, y + hw),
                    ]);
                }
                // Nonzero winding cancels where two contours wind oppositely, so give
                // every quad the same orientation before unioning them.
                for q in quads.iter_mut() {
                    let area: f64 = (0..q.len())
                        .map(|i| {
                            let a = q[i];
                            let b = q[(i + 1) % q.len()];
                            a.0 * b.1 - b.0 * a.1
                        })
                        .sum();
                    if area < 0.0 {
                        q.reverse();
                    }
                }
                (*argb, quads, false)
            }
            // No glyph rasterizer here, and a cell's clip is the caller's business.
            _ => continue,
        };
        let alpha = ((argb >> 24) & 0xFF) as f64 / 255.0;
        if alpha <= 0.0 {
            continue;
        }
        // Edges in raster coordinates.
        let mut edges: Vec<(f64, f64, f64, f64)> = Vec::new();
        for c in &contours {
            if c.len() < 3 {
                continue;
            }
            for i in 0..c.len() {
                let a = c[i];
                let b = c[(i + 1) % c.len()];
                let (ax, ay) = (to_px(a.0), to_py(a.1));
                let (bx, by) = (to_px(b.0), to_py(b.1));
                if (ay - by).abs() > 1e-12 {
                    edges.push((ax, ay, bx, by));
                }
            }
        }
        if edges.is_empty() {
            continue;
        }
        const SS: usize = 2;
        let mut cov = vec![0f32; w];
        let mut xs: Vec<(f64, i32)> = Vec::new();
        for py in 0..h {
            cov.iter_mut().for_each(|c| *c = 0.0);
            for s in 0..SS {
                let yc = py as f64 + (s as f64 + 0.5) / SS as f64;
                xs.clear();
                for &(ax, ay, bx, by) in &edges {
                    // Half-open in y so a vertex shared by two edges is counted once.
                    let (lo, hi, dir) = if ay < by { (ay, by, 1) } else { (by, ay, -1) };
                    if yc < lo || yc >= hi {
                        continue;
                    }
                    let t = (yc - ay) / (by - ay);
                    xs.push((ax + t * (bx - ax), dir));
                }
                if xs.len() < 2 {
                    continue;
                }
                xs.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
                let mut wind = 0i32;
                for i in 0..xs.len() - 1 {
                    wind += xs[i].1;
                    let inside = if even_odd { (i as i32 + 1) % 2 != 0 } else { wind != 0 };
                    if !inside {
                        continue;
                    }
                    // Analytic horizontal coverage for the span, so a hairline narrower
                    // than a pixel still contributes its true fraction.
                    let (xa, xb) = (xs[i].0.max(0.0), xs[i + 1].0.min(w as f64));
                    if xb <= xa {
                        continue;
                    }
                    let weight = 1.0 / SS as f32;
                    for px in xa.floor() as usize..(xb.ceil() as usize).min(w) {
                        let l = xa.max(px as f64);
                        let r = xb.min(px as f64 + 1.0);
                        if r > l {
                            cov[px] += (r - l) as f32 * weight;
                        }
                    }
                }
            }
            let (sr, sg, sb) = (
                ((argb >> 16) & 0xFF) as f64,
                ((argb >> 8) & 0xFF) as f64,
                (argb & 0xFF) as f64,
            );
            for px in 0..w {
                let a = alpha * (cov[px].clamp(0.0, 1.0) as f64);
                if a <= 0.0 {
                    continue;
                }
                let o = (py * w + px) * 4;
                let inv = 1.0 - a;
                for (ch, sc) in [sr, sg, sb].iter().enumerate() {
                    acc[o + ch] = (sc * a + acc[o + ch] as f64 * inv).round().clamp(0.0, 255.0) as u8;
                }
                acc[o + 3] = (a * 255.0 + acc[o + 3] as f64 * inv).round().clamp(0.0, 255.0) as u8;
            }
        }
    }

    for px in acc.chunks_exact_mut(4) {
        let a = px[3];
        if a == 0 || a == 255 {
            continue;
        }
        for ch in 0..3 {
            px[ch] = ((px[ch] as u32 * 255) / a as u32).min(255) as u8;
        }
    }
    Some(acc)
}

/// Apply a per-pixel soft-mask alpha (length `w*h`) to an RGBA buffer.
pub(crate) fn apply_smask(rgba: &mut [u8], smask: &Option<Vec<u8>>) {
    if let Some(alpha) = smask {
        let n = (rgba.len() / 4).min(alpha.len());
        for i in 0..n {
            rgba[i * 4 + 3] = alpha[i];
        }
    }
}

pub(crate) fn apply_color_key_mask(rgba: &mut [u8], mask_ranges: &Option<Vec<(u8,u8)>>) {
    let ranges = match mask_ranges {
        Some(r) => r,
        None => return,
    };
    if ranges.is_empty() { return; }
    let pixel_count = rgba.len() / 4;
    // ncomp inferred from ranges len: could be 1,3,4
    let ncomp = ranges.len();
    for i in 0..pixel_count {
        let base = i*4;
        let r = rgba[base];
        let g = rgba[base+1];
        let b = rgba[base+2];
        // For 1-comp, use r (gray)
        let transparent = match ncomp {
            1 => {
                let (mn,mx) = ranges[0];
                r >= mn && r <= mx
            }
            3 => {
                let (r0,r1)=ranges.first().copied().unwrap_or((0,0));
                let (g0,g1)=ranges.get(1).copied().unwrap_or((0,0));
                let (b0,b1)=ranges.get(2).copied().unwrap_or((0,0));
                r>=r0 && r<=r1 && g>=g0 && g<=g1 && b>=b0 && b<=b1
            }
            _ => {
                // 4+ components (CMYK/DeviceN). We only have post-conversion RGB here,
                // so there is no honest way to test a CMYK range — the old code compared
                // the RED channel against the CYAN range, which could make large regions
                // wrongly transparent. The sample-domain path
                // (`apply_color_key_mask_samples`) handles these correctly; skip here.
                false
            }
        };
        if transparent {
            rgba[base+3]=0;
        }
    }
}

pub(crate) fn read_color_key_mask(doc: &Document, dict: &lopdf::Dictionary) -> Option<Vec<(u8,u8)>> {
    let mask_obj = dict.get(b"Mask").ok().and_then(|o| deref(doc, o))?;
    let arr = match mask_obj {
        Object::Array(a) => a,
        _ => return None,
    };
    if arr.len()%2!=0 { return None; }
    if arr.len()>20 { return None; } // sanity: up to 10 components (DeviceN)
    // §8.9.6.4: the ranges are INTEGER source sample values in 0..2^bpc-1, never
    // 0..1. The old `if v > 1.0 { v } else { v * 255.0 }` heuristic turned a
    // legitimate 8-bpc `/Mask [0 1]` (mask near-black only) into (0,255), i.e.
    // every pixel transparent — the whole image vanished.
    let bpc = if dict_true(doc, dict, b"ImageMask") {
        1u32
    } else {
        dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32
    }
    .clamp(1, 16);
    let maxval = ((1u64 << bpc) - 1).max(1);
    let mut out = Vec::with_capacity(arr.len()/2);
    for i in 0..arr.len()/2 {
        let mn = num(&arr[i*2]).unwrap_or(0.0);
        let mx = num(&arr[i*2+1]).unwrap_or(0.0);
        let to_u8 = |v: f64| -> u8 {
            ((v.max(0.0).round() as u64).min(maxval) * 255 / maxval) as u8
        };
        let mn_u = to_u8(mn.min(mx));
        let mx_u = to_u8(mn.max(mx));
        out.push((mn_u, mx_u));
    }
    Some(out)
}

/// Read a color-key `/Mask` array as raw per-component sample-value ranges
/// (i.e. in the image's 0..2^bpc-1 units, one `(min,max)` per component). This
/// is the form needed to mask against pre-conversion samples for CMYK/DeviceN.
pub(crate) fn read_color_key_ranges_raw(doc: &Document, dict: &lopdf::Dictionary) -> Option<Vec<(u32,u32)>> {
    let mask_obj = dict.get(b"Mask").ok().and_then(|o| deref(doc, o))?;
    let arr = match mask_obj {
        Object::Array(a) => a,
        _ => return None,
    };
    if arr.is_empty() || arr.len()%2!=0 || arr.len()>20 { return None; }
    let mut out = Vec::with_capacity(arr.len()/2);
    for i in 0..arr.len()/2 {
        let mn = num(&arr[i*2]).unwrap_or(0.0).max(0.0);
        let mx = num(&arr[i*2+1]).unwrap_or(0.0).max(0.0);
        out.push((mn.min(mx) as u32, mn.max(mx) as u32));
    }
    Some(out)
}

/// Apply color-key masking against pre-conversion samples. `comps` is
/// `w*h*ncomp` bytes already scaled to 0..255 by [`unpack_samples_to_bytes`];
/// `ranges_raw` holds one `(min,max)` per component in 0..2^bpc-1 units. A pixel
/// whose every component falls inside its range becomes fully transparent. This
/// masks correctly for CMYK/DeviceN, unlike the RGB-only post-conversion path.
pub(crate) fn apply_color_key_mask_samples(
    rgba: &mut [u8],
    comps: &[u8],
    ncomp: usize,
    ranges_raw: &[(u32,u32)],
    bpc: u32,
) {
    if ncomp == 0 || ranges_raw.len() != ncomp { return; }
    let bpc = bpc.clamp(1, 16);
    let maxval = ((1u64 << bpc) - 1).max(1);
    // Map each raw range bound through the SAME transform `unpack_samples_decimated`
    // applied to the samples, so the comparison is exact. A generic `v * 255 / maxval`
    // agrees with the unpacker for 1/2/4/8 bpc but NOT for 12 or 16, where the unpacker
    // keeps the high bits (`raw >> 4`, `raw >> 8`) — there the two disagreed by up to a
    // whole level, so a colour-key range could fail to catch the value it names.
    let scaled: Vec<(u8,u8)> = ranges_raw.iter().map(|&(mn,mx)| {
        let s = |v: u32| -> u8 {
            let v = (v as u64).min(maxval);
            match bpc {
                12 => (v >> 4) as u8,
                16 => (v >> 8) as u8,
                _ => (v * 255 / maxval) as u8,
            }
        };
        (s(mn), s(mx))
    }).collect();
    let px = rgba.len() / 4;
    for i in 0..px {
        let mut inside = true;
        for (c, &(mn, mx)) in scaled.iter().enumerate() {
            let v = comps.get(i*ncomp + c).copied().unwrap_or(0);
            if v < mn || v > mx { inside = false; break; }
        }
        if inside { rgba[i*4+3] = 0; }
    }
}

pub(crate) fn decode_jpeg_rgba(data: &[u8]) -> Option<(u32,u32,Vec<u8>)> {
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(data));
    let pixels = decoder.decode().ok()?;
    let info = decoder.info()?;
    let w = info.width as u32;
    let h = info.height as u32;
    if w==0 || h==0 || w>20000 || h>20000 { return None; }
    // A dimension-only cap still allows 20000x20000 = 1.6 GB. The codestream's
    // dimensions can differ from the dict's /Width and /Height, so the caller's
    // guard does not cover this.
    if (w as usize).saturating_mul(h as usize) > MAX_IMAGE_PIXELS {
        image_warn!("JPEG codestream {}x{} exceeds pixel budget - refusing to decode", w, h);
        return None;
    }
    let rgba = match info.pixel_format {
        jpeg_decoder::PixelFormat::L8 => {
            // gray -> rgba
            let mut out = vec![0u8; (w*h*4) as usize];
            for i in 0..(w*h) as usize {
                let g = pixels.get(i).copied().unwrap_or(0);
                out[i*4]=g; out[i*4+1]=g; out[i*4+2]=g; out[i*4+3]=255;
            }
            out
        }
        jpeg_decoder::PixelFormat::RGB24 => {
            if pixels.len() < (w*h*3) as usize { return None; }
            let mut out = vec![0u8; (w*h*4) as usize];
            for i in 0..(w*h) as usize {
                out[i*4]=pixels[i*3];
                out[i*4+1]=pixels[i*3+1];
                out[i*4+2]=pixels[i*3+2];
                out[i*4+3]=255;
            }
            out
        }
        jpeg_decoder::PixelFormat::CMYK32 => {
            // jpeg-decoder has ALREADY un-inverted the Adobe convention before we see
            // these bytes: `color_convert_line_cmyk` emits 255-c/255-m/255-y/255-k, and
            // `color_convert_line_ycck` emits correct CMY but 255-k. So inverting all
            // four again produced a lurid negative for transform 0, and blew out blacks
            // for transform 2. Correct residual correction: invert CMY only for YCCK
            // (transform 2), and never invert K.
            if pixels.len() < (w*h*4) as usize { return None; }
            let invert_cmy = jpeg_adobe_transform(data) == Some(2);
            let mut out = vec![0u8; (w*h*4) as usize];
            for i in 0..(w*h) as usize {
                let (mut c, mut m, mut y) = (pixels[i*4], pixels[i*4+1], pixels[i*4+2]);
                let k = pixels[i*4+3];
                if invert_cmy { c = 255 - c; m = 255 - m; y = 255 - y; }
                let cf = c as f64 /255.0;
                let mf = m as f64 /255.0;
                let yf = y as f64 /255.0;
                let kf = k as f64 /255.0;
                let r = ((1.0 - cf)*(1.0 - kf)*255.0) as u8;
                let g = ((1.0 - mf)*(1.0 - kf)*255.0) as u8;
                let b = ((1.0 - yf)*(1.0 - kf)*255.0) as u8;
                out[i*4]=r; out[i*4+1]=g; out[i*4+2]=b; out[i*4+3]=255;
            }
            out
        }
        // 9..=16-bit precision (§7.4.8 permits 12-bit DCT). Previously fell into the
        // `_ =>` arm and the image vanished with no diagnostic.
        jpeg_decoder::PixelFormat::L16 => {
            if pixels.len() < (w*h*2) as usize { return None; }
            let mut out = vec![0u8; (w*h*4) as usize];
            for i in 0..(w*h) as usize {
                // Little-endian u16 pairs; the high byte is the 8-bit approximation.
                let g = pixels.get(i*2+1).copied().unwrap_or(0);
                out[i*4]=g; out[i*4+1]=g; out[i*4+2]=g; out[i*4+3]=255;
            }
            out
        }
        // All four PixelFormat variants are handled above; jpeg-decoder's enum has no
        // others, so an exhaustive match is preferable to a catch-all that drops images.
    };
    Some((w,h,rgba))
}

/// Read the component count from a JPEG's SOF marker (1 = gray, 3 = YCbCr/RGB,
/// 4 = CMYK/YCCK). Returns `None` if no SOF is found.
pub(crate) fn jpeg_num_components(data: &[u8]) -> Option<u8> {
    if data.len() < 2 || data[0] != 0xFF || data[1] != 0xD8 { return None; }
    let mut i = 2;
    while i + 1 < data.len() {
        if data[i] != 0xFF { i += 1; continue; }
        let marker = data[i + 1];
        if marker == 0xFF { i += 1; continue; }
        if marker == 0x01 || (0xD0..=0xD9).contains(&marker) { i += 2; continue; }
        if marker == 0xDA { break; }
        if i + 4 > data.len() { break; }
        let len = ((data[i + 2] as usize) << 8) | data[i + 3] as usize;
        if len < 2 { break; }
        let seg_end = i + 2 + len;
        if seg_end > data.len() { break; }
        // SOF0..SOF15, excluding DHT(C4), JPG(C8), DAC(CC).
        let is_sof = (0xC0..=0xCF).contains(&marker)
            && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        if is_sof {
            // payload: precision(1) height(2) width(2) ncomp(1)
            return data.get(i + 4 + 5).copied();
        }
        i = seg_end;
    }
    None
}

/// Scan JPEG markers for an APP14 "Adobe" segment, returning its transform flag
/// (0 = unknown/CMYK, 1 = YCbCr, 2 = YCCK) if present. Its mere presence signals
/// the Adobe inverted-CMYK convention. Returns `None` for non-Adobe JPEGs.
pub(crate) fn jpeg_adobe_transform(data: &[u8]) -> Option<u8> {
    if data.len() < 2 || data[0] != 0xFF || data[1] != 0xD8 { return None; }
    let mut i = 2;
    while i + 1 < data.len() {
        if data[i] != 0xFF { i += 1; continue; }
        let marker = data[i + 1];
        // Padding fill bytes.
        if marker == 0xFF { i += 1; continue; }
        // Standalone markers (no length): TEM, RSTn, SOI, EOI.
        if marker == 0x01 || (0xD0..=0xD9).contains(&marker) { i += 2; continue; }
        // Start of scan: entropy-coded data follows; stop looking.
        if marker == 0xDA { break; }
        if i + 4 > data.len() { break; }
        let len = ((data[i + 2] as usize) << 8) | data[i + 3] as usize;
        if len < 2 { break; }
        let seg_start = i + 4;
        let seg_end = i + 2 + len;
        if seg_end > data.len() { break; }
        if marker == 0xEE {
            let seg = &data[seg_start..seg_end];
            if seg.len() >= 12 && &seg[0..5] == b"Adobe" {
                return Some(seg[11]);
            }
        }
        i = seg_end;
    }
    None
}

pub(crate) fn decode_jpeg_gray(data: &[u8]) -> Option<(u32,u32,Vec<u8>)> {
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(data));
    let pixels = decoder.decode().ok()?;
    let info = decoder.info()?;
    let w = info.width as u32;
    let h = info.height as u32;
    if w==0 || h==0 || w>20000 || h>20000 { return None; }
    // A dimension-only cap still permits 20000x20000. The codestream's dimensions can
    // differ from the dict's /Width and /Height, so the caller's guard misses this.
    if (w as usize).saturating_mul(h as usize) > MAX_IMAGE_PIXELS {
        image_warn!("JPEG (gray) codestream {}x{} exceeds pixel budget - refusing to decode", w, h);
        return None;
    }
    let gray = match info.pixel_format {
        jpeg_decoder::PixelFormat::L8 => {
            if pixels.len() < (w*h) as usize { return None; }
            pixels[..(w*h) as usize].to_vec()
        }
        jpeg_decoder::PixelFormat::RGB24 => {
            if pixels.len() < (w*h*3) as usize { return None; }
            let mut out = vec![0u8; (w*h) as usize];
            for i in 0..(w*h) as usize {
                let r = pixels[i*3] as u16;
                let g = pixels[i*3+1] as u16;
                let b = pixels[i*3+2] as u16;
                out[i] = ((r*30 + g*59 + b*11)/100) as u8;
            }
            out
        }
        jpeg_decoder::PixelFormat::CMYK32 => {
            // A CMYK JPEG used as an /SMask previously fell into `_ =>`, and the caller's
            // RGBA fallback then took the R channel as luminance. Convert properly here.
            if pixels.len() < (w*h*4) as usize { return None; }
            let invert_cmy = jpeg_adobe_transform(data) == Some(2);
            let mut out = vec![0u8; (w*h) as usize];
            for i in 0..(w*h) as usize {
                let (mut c, mut m, mut y) = (pixels[i*4], pixels[i*4+1], pixels[i*4+2]);
                let k = pixels[i*4+3] as u16;
                if invert_cmy { c = 255 - c; m = 255 - m; y = 255 - y; }
                let kf = 255u16.saturating_sub(k);
                let r = (255u16 - c as u16) * kf / 255;
                let g = (255u16 - m as u16) * kf / 255;
                let b = (255u16 - y as u16) * kf / 255;
                out[i] = ((r*30 + g*59 + b*11)/100) as u8;
            }
            out
        }
        jpeg_decoder::PixelFormat::L16 => {
            if pixels.len() < (w*h*2) as usize { return None; }
            let mut out = vec![0u8; (w*h) as usize];
            for i in 0..(w*h) as usize {
                out[i] = pixels.get(i*2+1).copied().unwrap_or(0);
            }
            out
        }
    };
    Some((w,h,gray))
}

// Generic bit unpacker for BPC 2,4,12,16
/// Ceiling on the unpacked sample buffer (one byte per component). 16 MP of
/// 4-component data; guards against a bogus `/N` or `/DeviceN` arity turning
/// `w*h*ncomp` into a multi-gigabyte allocation.
pub(crate) const MAX_UNPACKED_SAMPLE_BYTES: usize = 64 * 1024 * 1024;

pub(crate) fn unpack_samples_to_bytes(samples: &[u8], w: usize, h: usize, ncomp: usize, bpc: u32) -> Option<Vec<u8>> {
    unpack_samples_decimated(samples, w, h, ncomp, bpc, 1).map(|(_, _, v)| v)
}

/// Unpack `bpc`-bit samples into one byte per component (0..=255), keeping every
/// `step`-th pixel and every `step`-th row. Returns `(out_w, out_h, bytes)` where
/// `bytes.len() == out_w * out_h * ncomp`.
///
/// A stream shorter than `/Width × /Height × ncomp` samples is NOT an error: §8.9.5.1
/// gives `/Width` and `/Height` authority over the sample count, so absent bytes read as
/// zero and the image still renders. Returning `None` here used to discard the whole
/// image for a single missing byte, which is common in the wild (a wrong `/Length`, a
/// truncated final scanline, or a Flate tail that failed to inflate).
///
/// The 0..=255 scaling per `bpc` is load-bearing and must not change: the Indexed
/// branch of [`image_samples_to_rgba`] inverts it to recover the palette index, and
/// [`apply_color_key_mask_samples`] scales `/Mask` ranges into the same domain.
pub(crate) fn unpack_samples_decimated(
    samples: &[u8],
    w: usize,
    h: usize,
    ncomp: usize,
    bpc: u32,
    step: usize,
) -> Option<(usize, usize, Vec<u8>)> {
    if w == 0 || h == 0 || ncomp == 0 {
        return None;
    }
    // An unsupported /BitsPerComponent is read as 8 rather than dropping the image.
    // §8.9.5.1 permits 1,2,4,8,16; 12 occurs in the wild and is handled too.
    let bpc = match bpc {
        1 | 2 | 4 | 8 | 12 | 16 => bpc,
        _ => 8,
    };
    let step = step.max(1);
    let ow = w.div_ceil(step);
    let oh = h.div_ceil(step);
    let out_len = ow.checked_mul(oh)?.checked_mul(ncomp)?;
    if out_len > MAX_UNPACKED_SAMPLE_BYTES {
        return None;
    }
    // Each scanline is padded to a byte boundary (§8.9.5.1).
    let row_bytes = w
        .checked_mul(ncomp)?
        .checked_mul(bpc as usize)?
        .div_ceil(8);
    let mut out = vec![0u8; out_len];
    for oy in 0..oh {
        let row_start = (oy * step) * row_bytes;
        for ox in 0..ow {
            let sx = ox * step;
            let obase = (oy * ow + ox) * ncomp;
            for c in 0..ncomp {
                let si = sx * ncomp + c;
                let raw: u32 = match bpc {
                    8 => samples.get(row_start + si).copied().unwrap_or(0) as u32,
                    // 16-bit: the high byte is the 8-bit approximation.
                    16 => samples.get(row_start + si * 2).copied().unwrap_or(0) as u32,
                    _ => {
                        let bit_off = si * bpc as usize;
                        let mut acc = 0u32;
                        for k in 0..bpc as usize {
                            let bp = bit_off + k;
                            let byte = samples.get(row_start + bp / 8).copied().unwrap_or(0);
                            acc = (acc << 1) | ((byte >> (7 - (bp % 8))) & 1) as u32;
                        }
                        acc
                    }
                };
                out[obase + c] = match bpc {
                    1 => {
                        if raw != 0 {
                            255
                        } else {
                            0
                        }
                    }
                    2 => (raw * 85) as u8,  // 255/3
                    4 => (raw * 17) as u8,  // 255/15
                    12 => (raw >> 4) as u8, // 4095 -> 255
                    _ => raw as u8,         // 8 and 16 (already the high byte)
                };
            }
        }
    }
    Some((ow, oh, out))
}

/// Bilinear sample of a `sw*sh` 1-channel (0..255) buffer at fractional (sx,sy).
fn bilinear_mask_sample(buf: &[u8], sw: usize, sh: usize, sx: f64, sy: f64) -> u8 {
    if sw == 0 || sh == 0 {
        return 0;
    }
    let x0 = sx.floor().clamp(0.0, (sw - 1) as f64) as usize;
    let y0 = sy.floor().clamp(0.0, (sh - 1) as f64) as usize;
    let x1 = (x0 + 1).min(sw - 1);
    let y1 = (y0 + 1).min(sh - 1);
    let fx = (sx - x0 as f64).clamp(0.0, 1.0);
    let fy = (sy - y0 as f64).clamp(0.0, 1.0);
    let s00 = buf.get(y0 * sw + x0).copied().unwrap_or(0) as f64;
    let s10 = buf.get(y0 * sw + x1).copied().unwrap_or(0) as f64;
    let s01 = buf.get(y1 * sw + x0).copied().unwrap_or(0) as f64;
    let s11 = buf.get(y1 * sw + x1).copied().unwrap_or(0) as f64;
    let top = s00 * (1.0 - fx) + s10 * fx;
    let bot = s01 * (1.0 - fx) + s11 * fx;
    (top * (1.0 - fy) + bot * fy).round().clamp(0.0, 255.0) as u8
}

/// Which convention a mask stream's samples follow. The two are OPPOSITE, and
/// sharing one mapping between them was inverting every 1-bit soft mask.
#[derive(Copy, Clone, PartialEq)]
pub(crate) enum MaskPolarity {
    /// `/SMask` (§11.6.5.3): the sample value IS the alpha, so with the default
    /// `/Decode [0 1]` a sample of 1 is fully OPAQUE.
    GrayLuminance,
    /// Explicit stencil `/Mask` (§8.9.6.4 with §8.9.6.2): a sample of 1 marks the
    /// area as MASKED OUT.
    StencilMaskedIf1,
}

/// Decode a mask stream into a gray 8-bit buffer of size sw*sh, attempting
/// compressed codecs (CCITT/JBIG2/DCT/JPX) via the same image pipeline.
/// Returns None only if the mask is truly undecodable.
///
/// The returned buffer is always in "alpha" terms — 255 keeps the base pixel,
/// 0 removes it — with `pol` selecting how a one-bit sample maps onto that.
/// Everything at >=2 bpc is a gray ramp and is polarity-independent.
fn decode_mask_stream_gray(
    doc: &Document,
    s: &lopdf::Stream,
    sw: usize,
    sh: usize,
    pol: MaskPolarity,
) -> Option<Vec<u8>> {
    // A decoded mask stream yields DeviceGray SAMPLES (0 = black, 255 = white) for
    // every codec below. Mapping a sample onto alpha is where the two conventions
    // diverge, and sharing one mapping between them is what inverted 1-bit soft masks:
    //   /SMask (§11.6.5.3): the sample IS the alpha, so white = opaque.
    //   stencil /Mask (§8.9.6.4 + §8.9.6.2): sample 1 (white) = masked out.
    let to_alpha = |sample: u8| -> u8 {
        match pol {
            MaskPolarity::GrayLuminance => sample,
            MaskPolarity::StencilMaskedIf1 => 255 - sample,
        }
    };
    // "Never masked", used where a codec produced no data. Correct in both
    // conventions because it bypasses `to_alpha`.
    const KEEP: u8 = 255;
    // A codec's own raster dimensions can differ from the mask dict's /Width x /Height
    // (a truncated codestream, or a dict that simply disagrees). Both callers resample
    // the returned buffer using `sw` as the ROW STRIDE, so handing back a differently
    // sized buffer shears the mask diagonally instead of scaling it. Fit it here.
    let fit = |buf: Vec<u8>, bw: usize, bh: usize| -> Vec<u8> {
        if bw == sw && bh == sh {
            return buf;
        }
        if bw == 0 || bh == 0 || sw == 0 || sh == 0 {
            return vec![KEEP; sw * sh];
        }
        let mut out = vec![KEEP; sw * sh];
        for y in 0..sh {
            let sy = (y * bh / sh).min(bh - 1);
            for x in 0..sw {
                let sx = (x * bw / sw).min(bw - 1);
                out[y * sw + x] = buf.get(sy * bw + sx).copied().unwrap_or(KEEP);
            }
        }
        out
    };
    // Try filter-aware decode chain first
    let specs = filters::filter_specs_from_dict(doc, &s.dict);
    let has_ccitt = specs.iter().any(|(k, _)| *k == filters::FilterKind::Ccitt);
    let has_jbig2 = specs.iter().any(|(k, _)| *k == filters::FilterKind::Jbig2);
    let has_dct = specs.iter().any(|(k, _)| *k == filters::FilterKind::Dct);
    let has_jpx = specs.iter().any(|(k, _)| *k == filters::FilterKind::Jpx);
    let sbpc = s.dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(1.0) as u32;

    if has_jbig2 {
        // Attempt JBIG2 path
        let raw = s.content.clone();
        let chain = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw.clone());
        // /JBIG2Globals holds the symbol dictionary shared across pages. Without it a
        // JBIG2 image decodes to nothing, and a failed JBIG2 mask renders as a silently
        // transparent region, so this is the single most common cause of total failure.
        // `jbig2_globals` is the same resolution the primary image path uses, so the two
        // cannot disagree about which shapes of /DecodeParms carry the globals.
        let globals = jbig2_globals(doc, &s.dict, &specs);
        if globals.is_none() {
            image_warn!("JBIG2 mask {}x{}: no /JBIG2Globals found - decode may fail", sw, sh);
        }
        if let Some((jw, jh, rgba)) = jbig2::decode_jbig2(&chain, globals.as_deref(), sw as u32, sh as u32)
            .or_else(|| jbig2::decode_jbig2(&s.content, globals.as_deref(), sw as u32, sh as u32))
        {
            // jbig2.rs emits DeviceGray samples (black = 0, white = 255) with alpha 255
            // only on pixels it actually decoded. alpha == 0 therefore means "never
            // decoded" (a truncated codestream leaves whole trailing rows untouched),
            // and those must not mask the base image at all.
            let mut gray = Vec::with_capacity((jw * jh) as usize);
            for chunk in rgba.chunks(4) {
                gray.push(if chunk.get(3).copied().unwrap_or(0) == 0 {
                    KEEP
                } else {
                    to_alpha(chunk[0])
                });
            }
            // Resample if jw*jh != sw*sh
            if jw as usize == sw && jh as usize == sh {
                return Some(gray);
            }
            // Nearest resample here for fallback then bilinear later maps final
            let mut out = vec![KEEP; sw * sh];
            for y in 0..sh {
                for x in 0..sw {
                    let sx = x * (jw as usize) / sw.max(1);
                    let sy = y * (jh as usize) / sh.max(1);
                    out[y * sw + x] = gray.get(sy * (jw as usize) + sx).copied().unwrap_or(KEEP);
                }
            }
            return Some(out);
        }
    }
    if has_ccitt {
        let params = filters::parse_ccitt_params(doc, specs.iter().find(|(k, _)| *k == filters::FilterKind::Ccitt).and_then(|(_, d)| d.as_ref()));
        let raw = s.content.clone();
        let chain = filters::decode_stream_chain(raw.clone(), &specs, doc).unwrap_or(raw);
        if let Some(packed) = filters::decode_ccitt(&chain, sw as u32, sh as u32, &params) {
            // `decode_ccitt` emits one-bit DeviceGray samples with /BlackIs1 already
            // applied, so bit 0 is BLACK and bit 1 is white (§7.4.6 Table 11, /BlackIs1
            // defaults to false) - the same convention the main CCITT image path uses.
            // This branch previously treated bit 1 as black, which inverted every
            // CCITT-encoded soft mask.
            let row_bytes = sw.div_ceil(8);
            let mut gray = vec![KEEP; sw * sh];
            for y in 0..sh {
                for x in 0..sw {
                    let byte = packed.get(y * row_bytes + x / 8).copied().unwrap_or(0xFF);
                    let bit = (byte >> (7 - (x % 8))) & 1;
                    gray[y * sw + x] = to_alpha(if bit == 1 { 255 } else { 0 });
                }
            }
            return Some(gray);
        }
    }
    if has_dct {
        // NOT `stream_data`: that is lopdf's decoder, which implements only
        // Flate/LZW/ASCII85 and returns Err for DCTDecode — and `stream_data` maps that
        // Err to an EMPTY Vec whenever a /Filter is present. So every DCT-compressed
        // /SMask decoded to nothing, fell through to the generic unpack path below,
        // read absent bytes as sample 0, and produced alpha 0 for every pixel: the base
        // image became completely invisible. A JPEG soft mask beside a JPEG image is the
        // commonest /SMask there is, so this hid whole photographs. `stream_data_with_doc`
        // passes image codecs through untouched and unwraps any Ascii/Flate wrapper.
        let raw = stream_data_with_doc(doc, s);
        if let Some((jw, jh, gray)) = decode_jpeg_gray(&raw) {
            let fitted = fit(gray, jw as usize, jh as usize);
            return Some(fitted.into_iter().map(to_alpha).collect());
        }
        if let Some((jw, jh, rgba)) = decode_jpeg_rgba(&raw) {
            let gray: Vec<u8> = rgba.chunks(4).map(|c| c[0]).collect();
            let fitted = fit(gray, jw as usize, jh as usize);
            return Some(fitted.into_iter().map(to_alpha).collect());
        }
        image_warn!("mask /SMask or /Mask: DCT {}x{} decode failed ({} bytes)", sw, sh, raw.len());
    }
    if has_jpx {
        // Same reasoning as the DCT branch: use the project's chain so an
        // Ascii85/Flate-wrapped JPX codestream is unwrapped first.
        let raw = stream_data_with_doc(doc, s);
        if let Some((jw, jh, rgba)) = jp2::decode(&raw) {
            let gray: Vec<u8> = rgba.chunks(4).map(|c| c[0]).collect();
            let fitted = fit(gray, jw as usize, jh as usize);
            return Some(fitted.into_iter().map(to_alpha).collect());
        }
        image_warn!("mask /SMask or /Mask: JPX {}x{} decode failed ({} bytes)", sw, sh, raw.len());
    }
    // Plain bit path (1-bit masks without compression). The raw bit IS the DeviceGray
    // sample: 1 = white. Mapping bit 1 to black here inverted every uncompressed
    // 1-bit /SMask, making the image opaque exactly where it should have been clear.
    let data = stream_data_with_doc(doc, s);
    // No bytes at all means no mask. Falling through with an empty buffer made the
    // unpacker read every sample as 0, which for a /SMask is alpha 0 — an undecodable
    // mask deleted the whole image. `None` here leaves the base image unmasked, which
    // is wrong in a way you can see and fix rather than wrong by omission.
    if data.is_empty() {
        image_warn!(
            "mask stream {}x{} (bpc {}) decoded to 0 bytes - leaving the image unmasked",
            sw, sh, sbpc
        );
        return None;
    }
    if sbpc == 1 {
        let row_bytes = sw.div_ceil(8);
        let mut gray = vec![KEEP; sw * sh];
        for y in 0..sh {
            for x in 0..sw {
                if y * row_bytes + x / 8 >= data.len() {
                    break;
                }
                let byte = data[y * row_bytes + x / 8];
                let bit = (byte >> (7 - (x % 8))) & 1;
                gray[y * sw + x] = to_alpha(if bit == 1 { 255 } else { 0 });
            }
        }
        return Some(gray);
    }
    // Generic low-BPC unpack path: >=2 bpc is a gray ramp, so the sample value maps
    // onto alpha through the same `to_alpha` as the one-bit paths.
    let unpacked = unpack_samples_to_bytes(&data, sw, sh, 1, sbpc).or_else(|| {
        let bpp = sbpc as usize;
        let row_bits = sw * bpp;
        let row_bytes = row_bits.div_ceil(8);
        let mut gray = vec![255u8; sw * sh];
        for y in 0..sh {
            let base = y * row_bytes;
            if base + row_bytes > data.len() {
                break;
            }
            for x in 0..sw {
                gray[y * sw + x] = data.get(base + x).copied().unwrap_or(255);
            }
        }
        Some(gray)
    })?;
    Some(unpacked.into_iter().map(to_alpha).collect())
}

/// Decode an explicit stencil `/Mask` image (an `ImageMask` XObject) into a
/// `w*h` 8-bit alpha buffer for the base image: mask sample 1 => masked
/// (alpha 0), 0 => painted (alpha 255), honoring the mask's `/Decode`. Scaled to
/// the base image's dimensions via bilinear filtering. No longer bails on
/// compressed codecs (CCITT/JBIG2/DCT/JPX) — those are decoded via
/// `decode_mask_stream_gray`.
pub(crate) fn read_explicit_mask(doc: &Document, dict: &lopdf::Dictionary, w: u32, h: u32) -> Option<Vec<u8>> {
    let m = dict.get(b"Mask").ok().and_then(|o| deref(doc, o))?;
    let s = match m {
        Object::Stream(s) => s,
        _ => return None,
    };
    if !dict_true(doc, &s.dict, b"ImageMask") {
        // §8.9.6.4 requires an explicit /Mask stream to BE an image mask. Without the
        // flag we cannot know the sample polarity, so the mask is skipped and the base
        // image is left unmasked — visible, but not silently so.
        image_warn!("/Mask stream is not an /ImageMask - mask ignored, image left unmasked");
        return None;
    }
    let sw = s.dict.get(b"Width").ok().and_then(num)? as usize;
    let sh = s.dict.get(b"Height").ok().and_then(num)? as usize;
    if sw == 0 || sh == 0 || sw > 20000 || sh > 20000 {
        image_warn!("/Mask stream {}x{} outside 1..20000 - mask ignored", sw, sh);
        return None;
    }
    let invert = matches!(
        s.dict.get(b"Decode").ok().and_then(|o| deref(doc, o)),
        Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)
    );
    let mut mask_alpha =
        decode_mask_stream_gray(doc, s, sw, sh, MaskPolarity::StencilMaskedIf1)?;
    // `/Decode [1 0]` reverses the stencil sense (§8.9.6.2). Apply it ONCE, here,
    // before any resampling: the resampled path used to have `invert` and non-invert
    // arms that were both the identity, so an inverted mask at any size other than
    // the base image's silently showed what should have been hidden.
    if invert {
        for v in mask_alpha.iter_mut() {
            *v = 255 - *v;
        }
    }
    let (w_us, h_us) = (w as usize, h as usize);
    if sw == w_us && sh == h_us {
        return Some(mask_alpha);
    }
    let mut alpha = vec![255u8; w_us * h_us];
    for y in 0..h_us {
        for x in 0..w_us {
            let sx = if w_us > 1 { x as f64 * (sw - 1) as f64 / (w_us - 1).max(1) as f64 } else { 0.0 };
            let sy = if h_us > 1 { y as f64 * (sh - 1) as f64 / (h_us - 1).max(1) as f64 } else { 0.0 };
            alpha[y * w_us + x] = bilinear_mask_sample(&mask_alpha, sw, sh, sx, sy);
        }
    }
    Some(alpha)
}

/// Read an SMask `/Matte` color as an RGB triple (0..1), if present. The matte
/// is given in the base image's colorspace; components are interpreted by arity
/// (gray/RGB/CMYK), which is sufficient for un-premultiplication.
pub(crate) fn read_matte(doc: &Document, dict: &lopdf::Dictionary) -> Option<[f64; 3]> {
    let sm = dict.get(b"SMask").ok().and_then(|o| deref(doc, o))?;
    // lopdf's `as_dict()` matches ONLY Object::Dictionary, never a Stream's dict, so
    // this returned None for every /SMask (which is always a stream) and /Matte was
    // dead code — `apply_matte` never ran.
    let smd = match &sm {
        Object::Stream(s) => &s.dict,
        Object::Dictionary(d) => d,
        _ => return None,
    };
    let arr = smd.get(b"Matte").ok().and_then(|o| deref(doc, o))?;
    let comps: Vec<f64> = arr.as_array().ok()?.iter().filter_map(num).collect();
    let rgb = match comps.len() {
        1 => [comps[0], comps[0], comps[0]],
        3 => [comps[0], comps[1], comps[2]],
        4 => {
            let (c, m, y, k) = (comps[0], comps[1], comps[2], comps[3]);
            [(1.0 - c) * (1.0 - k), (1.0 - m) * (1.0 - k), (1.0 - y) * (1.0 - k)]
        }
        _ => return None,
    };
    Some(rgb)
}

/// Un-premultiply an RGBA buffer whose colors were premultiplied against a
/// `/Matte` background, using the already-applied SMask alpha in `rgba`.
/// `c = matte + (c' - matte) / alpha`.
pub(crate) fn apply_matte(rgba: &mut [u8], matte: [f64; 3]) {
    let m = [matte[0] * 255.0, matte[1] * 255.0, matte[2] * 255.0];
    for px in rgba.chunks_exact_mut(4) {
        let a = px[3] as f64 / 255.0;
        if a <= 0.0 { continue; }
        for ch in 0..3 {
            let cp = px[ch] as f64;
            let un = m[ch] + (cp - m[ch]) / a;
            px[ch] = un.round().clamp(0.0, 255.0) as u8;
        }
    }
}

/// Decode an image's `/SMask` into w*h alpha via unified mask decoder (handles all filters)
pub(crate) fn read_smask(doc: &Document, dict: &lopdf::Dictionary, w: u32, h: u32) -> Option<Vec<u8>> {
    let sm = dict.get(b"SMask").ok().and_then(|o| deref(doc, o))?;
    let s = match sm {
        Object::Stream(s) => s,
        _ => return None,
    };
    let sw = s.dict.get(b"Width").ok().and_then(num).unwrap_or(w as f64) as usize;
    let sh = s.dict.get(b"Height").ok().and_then(num).unwrap_or(h as f64) as usize;
    if sw == 0 || sh == 0 || sw > 20000 || sh > 20000 {
        image_warn!("/SMask stream {}x{} outside 1..20000 - mask ignored", sw, sh);
        return None;
    }
    // P0 fix critical #1: previously DCT/JPX only; now uses unified decoder for all filters
    let mut gray = decode_mask_stream_gray(doc, s, sw, sh, MaskPolarity::GrayLuminance)?;
    // Honor the SMask's own /Decode [1 0], which inverts the alpha ramp.
    if matches!(s.dict.get(b"Decode").ok().and_then(|o| deref(doc, o)), Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)) {
        for v in gray.iter_mut() { *v = 255 - *v; }
    }
    // Bilinear resample sw*sh -> w*h
    let (w_us, h_us) = (w as usize, h as usize);
    if sw == w_us && sh == h_us { return Some(gray); }
    let mut alpha = vec![255u8; w_us * h_us];
    for y in 0..h_us {
        for x in 0..w_us {
            let sx = if w_us > 1 { x as f64 * (sw - 1) as f64 / (w_us - 1).max(1) as f64 } else { 0.0 };
            let sy = if h_us > 1 { y as f64 * (sh - 1) as f64 / (h_us - 1).max(1) as f64 } else { 0.0 };
            alpha[y * w_us + x] = bilinear_mask_sample(&gray, sw, sh, sx, sy);
        }
    }
    Some(alpha)
}

#[cfg(test)]
mod mask_tests {
    use super::*;

    // P0-1: a stream one byte short must still produce a full raster. Returning None
    // here discarded the entire image, which is the "image just isn't there" symptom.
    #[test]
    fn truncated_stream_still_unpacks() {
        // 4x2 RGB at 8bpc needs 24 bytes; supply 20.
        let short = vec![0x7Fu8; 20];
        let out = unpack_samples_to_bytes(&short, 4, 2, 3, 8).expect("must not drop the image");
        assert_eq!(out.len(), 4 * 2 * 3, "raster must be /Width x /Height sized");
        assert_eq!(out[0], 0x7F, "supplied bytes are preserved");
        assert_eq!(out[23], 0, "absent bytes read as zero");
    }

    // An unsupported /BitsPerComponent is read as 8 rather than dropping the image.
    #[test]
    fn unsupported_bpc_falls_back_to_eight() {
        let data = vec![0x11u8; 6];
        let out = unpack_samples_to_bytes(&data, 3, 2, 1, 7).expect("bpc 7 must not drop");
        assert_eq!(out.len(), 6);
    }

    // Scanline padding: 3 pixels of 1bpc occupy one padded byte per row, so row 1
    // starts at byte 1. Getting this wrong shears the image diagonally.
    #[test]
    fn rows_are_byte_padded() {
        // row0 = 1110_0000, row1 = 0000_0000
        let data = vec![0b1110_0000u8, 0b0000_0000];
        let out = unpack_samples_to_bytes(&data, 3, 2, 1, 1).unwrap();
        assert_eq!(&out[..3], &[255, 255, 255], "row 0 all set");
        assert_eq!(&out[3..], &[0, 0, 0], "row 1 reads from the padded byte boundary");
    }

    // P1-4: averaging straight RGBA lets transparent pixels bleed their colour into
    // visible neighbours. One opaque red among three transparent whites must stay red.
    #[test]
    fn premultiplied_downscale_does_not_bleed() {
        let mut data = vec![0u8; 2 * 2 * 4];
        data[0..4].copy_from_slice(&[255, 0, 0, 255]); // opaque red
        for i in 1..4 {
            data[i * 4..i * 4 + 4].copy_from_slice(&[255, 255, 255, 0]); // transparent white
        }
        let (w, h, out) = downscale_rgba(&data, 2, 2, 1, true).expect("must downscale");
        assert_eq!((w, h), (1, 1));
        assert_eq!(out[0], 255, "red channel preserved");
        assert_eq!(out[1], 0, "transparent white must not bleed into green");
        assert_eq!(out[2], 0, "transparent white must not bleed into blue");
        assert_eq!(out[3], 63, "alpha is the straight average of 255,0,0,0");
    }

    // A pixel the codec never wrote (alpha 0, undefined RGB) must not be painted with
    // the fill colour — that turned a truncated JBIG2 stencil into a solid block.
    #[test]
    fn stencilize_leaves_undecoded_pixels_alone() {
        let mut rgba = vec![
            0, 0, 0, 0, // never decoded: RGB undefined, alpha 0
            0, 0, 0, 255, // decoded black -> painted
            255, 255, 255, 255, // decoded white -> transparent
        ];
        stencilize(&mut rgba, 0xFF00_FF00, false);
        assert_eq!(rgba[3], 0, "undecoded pixel stays transparent");
        assert_eq!(&rgba[4..8], &[0, 255, 0, 255], "dark pixel takes the fill colour");
        assert_eq!(rgba[11], 0, "light pixel becomes transparent");
    }

    #[test]
    fn adobe_app14_transform_detected() {
        // SOI + APP14 "Adobe" (transform=2) + SOS.
        let data = vec![
            0xFF, 0xD8,
            0xFF, 0xEE, 0x00, 0x0E, // APP14, len=14
            b'A', b'd', b'o', b'b', b'e', 0x00, 0x64, 0x00, 0x00, 0x00, 0x00, 0x02,
            0xFF, 0xDA,
        ];
        assert_eq!(jpeg_adobe_transform(&data), Some(2));
        // A plain JPEG without the Adobe marker returns None.
        let plain = vec![0xFF, 0xD8, 0xFF, 0xDA];
        assert_eq!(jpeg_adobe_transform(&plain), None);
    }

    #[test]
    fn sof_component_count_read() {
        // SOI + SOF0 declaring 3 components, padded to the segment length.
        let mut data = vec![
            0xFF, 0xD8,
            0xFF, 0xC0, 0x00, 0x11, // SOF0, len=17
            0x08, 0x00, 0x01, 0x00, 0x01, 0x03, // prec, h, w, ncomp=3
        ];
        data.extend(std::iter::repeat_n(0u8, 9)); // 3 component specs
        assert_eq!(jpeg_num_components(&data), Some(3));
    }

    #[test]
    fn color_key_masks_cmyk_in_range() {
        // Two CMYK pixels: first inside the key range, second outside.
        let mut rgba = vec![10u8, 20, 30, 255,  40, 50, 60, 255];
        let comps = vec![5u8, 5, 5, 5,  200, 200, 200, 200];
        let ranges = [(0u32, 10), (0, 10), (0, 10), (0, 10)];
        apply_color_key_mask_samples(&mut rgba, &comps, 4, &ranges, 8);
        assert_eq!(rgba[3], 0, "in-range CMYK pixel becomes transparent");
        assert_eq!(rgba[7], 255, "out-of-range pixel stays opaque");
    }

    #[test]
    fn matte_un_premultiplies() {
        // Black matte: c = c' / alpha. c'=100, alpha=128/255 -> ~199.
        let mut rgba = vec![100u8, 100, 100, 128];
        apply_matte(&mut rgba, [0.0, 0.0, 0.0]);
        assert!((198..=201).contains(&rgba[0]), "un-premult ~199, got {}", rgba[0]);
        assert_eq!(rgba[3], 128, "alpha is preserved");
    }

    #[test]
    fn bilevel_downscale_keeps_two_colours() {
        // A 4x1 black/white checker halved. Averaging blends each pair to mid grey and is
        // what makes a downscaled QR code unreadable; nearest keeps the pixels it picks.
        let row = vec![
            0u8, 0, 0, 255,
            255, 255, 255, 255,
            0, 0, 0, 255,
            255, 255, 255, 255,
        ];
        let (w, _, smooth) = downscale_rgba(&row, 4, 1, 2, true).unwrap();
        assert_eq!(w, 2);
        assert!(smooth[0] > 100 && smooth[0] < 155, "averaged to grey, got {}", smooth[0]);

        let (_, _, nearest) = downscale_rgba(&row, 4, 1, 2, false).unwrap();
        assert_eq!(nearest[0], 0, "first block keeps its black sample");
        assert_eq!(nearest[4], 0, "second block keeps its black sample");
        assert_eq!(nearest[3], 255, "alpha is not blended either");
    }

    /// A 1-bit mask stream, uncompressed: bits 1,0,1,0,0,0,0,0.
    fn one_bit_mask_stream(image_mask: bool, decode_inverted: bool) -> Stream {
        let mut d = dictionary! {
            "Type" => "XObject",
            "Subtype" => "Image",
            "Width" => 8,
            "Height" => 1,
            "BitsPerComponent" => 1,
            "ColorSpace" => "DeviceGray",
        };
        if image_mask {
            d.set("ImageMask", true);
        }
        if decode_inverted {
            d.set("Decode", vec![1.into(), 0.into()]);
        }
        Stream::new(d, vec![0b1010_0000u8])
    }

    // §11.6.5.3 (/SMask) and §8.9.6.4 + §8.9.6.2 (explicit stencil /Mask) require
    // OPPOSITE polarities from the same 1-bit samples, and round 1's rework routed both
    // through one `decode_mask_stream_gray`. Pin the two directions against each other:
    // if a future change collapses them again, exactly one of these must fail.
    #[test]
    fn smask_and_stencil_mask_have_opposite_polarity() {
        let mut doc = Document::with_version("1.7");
        let sm = doc.add_object(one_bit_mask_stream(false, false));
        let sm_dict = dictionary! { "SMask" => Object::Reference(sm) };
        // /SMask: the sample IS the alpha, so bit 1 (white) is OPAQUE.
        assert_eq!(
            read_smask(&doc, &sm_dict, 8, 1).expect("smask decodes"),
            vec![255, 0, 255, 0, 0, 0, 0, 0],
            "/SMask bit 1 must be opaque"
        );

        let mk = doc.add_object(one_bit_mask_stream(true, false));
        let mk_dict = dictionary! { "Mask" => Object::Reference(mk) };
        // Stencil /Mask: sample 1 marks the area MASKED OUT, so bit 1 is alpha 0.
        assert_eq!(
            read_explicit_mask(&doc, &mk_dict, 8, 1).expect("mask decodes"),
            vec![0, 255, 0, 255, 255, 255, 255, 255],
            "stencil /Mask bit 1 must be masked out"
        );
    }

    // /Decode [1 0] reverses each convention exactly once (§8.9.6.2). Applying it twice
    // (or in the resample arm only) is the failure mode round 1 was chasing.
    #[test]
    fn decode_inverts_each_mask_convention_once() {
        let mut doc = Document::with_version("1.7");
        let sm = doc.add_object(one_bit_mask_stream(false, true));
        let sm_dict = dictionary! { "SMask" => Object::Reference(sm) };
        assert_eq!(
            read_smask(&doc, &sm_dict, 8, 1).expect("smask decodes"),
            vec![0, 255, 0, 255, 255, 255, 255, 255],
            "/SMask with /Decode [1 0] is the inverse of the default"
        );

        let mk = doc.add_object(one_bit_mask_stream(true, true));
        let mk_dict = dictionary! { "Mask" => Object::Reference(mk) };
        assert_eq!(
            read_explicit_mask(&doc, &mk_dict, 8, 1).expect("mask decodes"),
            vec![255, 0, 255, 0, 0, 0, 0, 0],
            "stencil /Mask with /Decode [1 0] is the inverse of the default"
        );
    }

    // /Decode must survive RESAMPLING, not just the same-size fast path: request the
    // mask at 4x1 (a decimated base raster) and the inverted sense must still hold.
    #[test]
    fn inverted_mask_survives_resampling() {
        let mut doc = Document::with_version("1.7");
        let mk = doc.add_object(one_bit_mask_stream(true, true));
        let mk_dict = dictionary! { "Mask" => Object::Reference(mk) };
        let a = read_explicit_mask(&doc, &mk_dict, 4, 1).expect("mask decodes");
        assert_eq!(a.len(), 4);
        assert_eq!(a[0], 255, "first sample keeps the inverted sense after resampling");
        assert_eq!(a[3], 0, "trailing zero bits stay masked-out under /Decode [1 0]");
    }

    // The bug this pins: `stream_data` is lopdf's decoder, which implements only
    // Flate/LZW/ASCII85 and returns Err for everything else — and maps that Err to an
    // EMPTY buffer whenever a /Filter is present. The unpacker then read absent bytes as
    // sample 0, which for a /SMask is alpha 0, so the base image vanished completely.
    // DCTDecode, RunLengthDecode and ASCIIHexDecode all hit that path; a DCT soft mask
    // beside a DCT image is the commonest /SMask in the wild.
    #[test]
    fn smask_with_filter_lopdf_cannot_decode_still_masks() {
        let mut doc = Document::with_version("1.7");
        let sm = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Image",
                "Width" => 2,
                "Height" => 2,
                "ColorSpace" => "DeviceGray",
                "BitsPerComponent" => 8,
                "Filter" => "ASCIIHexDecode",
            },
            b"00FF7F40>".to_vec(),
        ));
        let dict = dictionary! { "SMask" => Object::Reference(sm) };
        let alpha = read_smask(&doc, &dict, 2, 2).expect("mask must decode, not vanish");
        assert_eq!(
            alpha,
            vec![0x00, 0xFF, 0x7F, 0x40],
            "the /SMask sample value IS the alpha (§11.6.5.3)"
        );
    }

    // A mask stream that decodes to nothing must leave the base image UNMASKED rather
    // than fully transparent: the old fallthrough read every absent sample as 0.
    #[test]
    fn undecodable_mask_leaves_image_unmasked() {
        let mut doc = Document::with_version("1.7");
        let sm = doc.add_object(Stream::new(
            dictionary! {
                "Width" => 2,
                "Height" => 2,
                "BitsPerComponent" => 8,
                // Not a filter any decoder here implements, so the chain yields nothing.
                "Filter" => "NoSuchDecode",
            },
            vec![1u8, 2, 3, 4],
        ));
        let dict = dictionary! { "SMask" => Object::Reference(sm) };
        assert!(
            read_smask(&doc, &dict, 2, 2).is_none(),
            "no mask is better than an all-transparent mask"
        );
    }

    /// Build a `/SMask` whose /JBIG2Globals is reachable only via an ARRAY /DecodeParms
    /// paired index-by-index with `/Filter [/FlateDecode /JBIG2Decode]`.
    fn jbig2_mask_with_array_decodeparms(doc: &mut Document) -> Dictionary {
        let globals = doc.add_object(Stream::new(dictionary! {}, vec![0u8; 8]));
        let sm = doc.add_object(Stream::new(
            dictionary! {
                "Width" => 8,
                "Height" => 8,
                "BitsPerComponent" => 1,
                "Filter" => vec![Object::Name(b"FlateDecode".to_vec()), Object::Name(b"JBIG2Decode".to_vec())],
                "DecodeParms" => vec![
                    Object::Null,
                    Object::Dictionary(dictionary! { "JBIG2Globals" => Object::Reference(globals) }),
                ],
            },
            vec![0u8; 4],
        ));
        dictionary! { "SMask" => Object::Reference(sm) }
    }

    // The mask path resolved /JBIG2Globals by matching only a DIRECT `Object::Dictionary`
    // /DecodeParms on the stream dict, so `/Filter [/FlateDecode /JBIG2Decode]` with
    // `/DecodeParms [null <</JBIG2Globals 5 0 R>>]` — the common shape — never found it.
    // Without the shared symbol dictionary a JBIG2 mask decodes to nothing, and a failed
    // mask renders as a silently transparent region. `specs` pairs the array with the
    // filter chain index-by-index, which is how the main image path already does it.
    //
    // This asserts the LOOKUP, not a successful decode: the payload is not real JBIG2, so
    // what matters is that the globals are found and the failure is reported as "no mask"
    // rather than an all-transparent one.
    #[test]
    fn jbig2_mask_globals_are_found_through_array_decodeparms() {
        let mut doc = Document::with_version("1.7");
        let dict = jbig2_mask_with_array_decodeparms(&mut doc);
        // Must not come back as a fully-transparent mask, which would delete the image.
        if let Some(alpha) = read_smask(&doc, &dict, 8, 8) {
            assert!(
                alpha.iter().any(|v| *v != 0),
                "an undecodable JBIG2 mask must not mask the whole image away"
            );
        }
    }

    // §7.4.7: both JBIG2 paths must resolve /JBIG2Globals identically. `specs` pairs an
    // array /DecodeParms with the filter chain index-by-index, but producers also emit the
    // array MISALIGNED with the chain, and matching only a direct `Object::Dictionary` —
    // which both call sites did as their fallback — dropped the globals. A JBIG2 decode
    // without them fails, and a failed decode renders the region silently transparent.
    #[test]
    fn jbig2_globals_are_found_in_every_decodeparms_shape() {
        let mut doc = Document::with_version("1.7");
        let globals = doc.add_object(Object::Stream(Stream::new(
            lopdf::dictionary! {},
            b"GLOBALS".to_vec(),
        )));
        let parms = || lopdf::dictionary! { "JBIG2Globals" => Object::Reference(globals) };
        let expect = |d: Dictionary, why: &str| {
            let specs = filters::filter_specs_from_dict(&doc, &d);
            assert_eq!(
                super::jbig2_globals(&doc, &d, &specs).as_deref(),
                Some(&b"GLOBALS"[..]),
                "{why}"
            );
        };
        // Paired through the filter chain: a single filter, and an array whose second
        // element carries the parameters.
        expect(
            lopdf::dictionary! { "Filter" => Object::Name(b"JBIG2Decode".to_vec()), "DecodeParms" => parms() },
            "single /Filter with a dict /DecodeParms",
        );
        expect(
            lopdf::dictionary! {
                "Filter" => Object::Array(vec![Object::Name(b"FlateDecode".to_vec()), Object::Name(b"JBIG2Decode".to_vec())]),
                "DecodeParms" => Object::Array(vec![Object::Null, Object::Dictionary(parms())]),
            },
            "/Filter [/FlateDecode /JBIG2Decode] with an aligned array /DecodeParms",
        );
        // Misaligned: the globals sit at the index of the OTHER filter, so `specs` pairs
        // `None` with JBIG2 and only an array-scanning fallback finds them.
        expect(
            lopdf::dictionary! {
                "Filter" => Object::Array(vec![Object::Name(b"FlateDecode".to_vec()), Object::Name(b"JBIG2Decode".to_vec())]),
                "DecodeParms" => Object::Array(vec![Object::Dictionary(parms()), Object::Null]),
            },
            "an array /DecodeParms misaligned with the filter chain",
        );
    }

    // ---- Compressed-codec stencil paths -----------------------------------
    // The polarity tests above all drive the UNCOMPRESSED 1-bit path. The codec
    // branches reach `stencilize` by a different route and each has its own
    // inversion source, so they need their own pins. CCITT is the worst case: it
    // folds `/Decode` into `black_bit` while building the raster and then calls
    // `stencilize(.., false)` precisely so the same `/Decode` is not applied a
    // second time. Nothing caught a regression there before these.

    /// Group 4 (T.6) encode `rows` of `true` = black pels, as `/CCITTFaxDecode`
    /// with `/K -1` expects.
    fn g4_encode(rows: &[Vec<bool>], width: u32) -> Vec<u8> {
        let mut enc = fax::encoder::Encoder::new(fax::VecWriter::new());
        for row in rows {
            let pels = row
                .iter()
                .map(|&b| if b { fax::Color::Black } else { fax::Color::White });
            enc.encode_line(pels, width).expect("VecWriter is infallible");
        }
        enc.finish().expect("infallible").finish()
    }

    /// 8x2, left half black. Returned with the row pattern so a test can state
    /// the expectation in terms of ink rather than bits.
    fn half_black_g4() -> (Vec<u8>, usize) {
        let row: Vec<bool> = (0..8).map(|x| x < 4).collect();
        (g4_encode(&[row.clone(), row], 8), 8)
    }

    fn ccitt_stencil(decode_inverts: bool, black_is1: bool) -> ImageData {
        let doc = Document::with_version("1.7");
        let (data, w) = half_black_g4();
        let mut parms = dictionary! { "K" => -1, "Columns" => w as i64, "Rows" => 2 };
        if black_is1 {
            parms.set("BlackIs1", true);
        }
        let mut d = dictionary! {
            "Width" => w as i64, "Height" => 2, "BitsPerComponent" => 1,
            "ImageMask" => true,
            "Filter" => "CCITTFaxDecode",
            "DecodeParms" => parms,
        };
        if decode_inverts {
            d.set("Decode", vec![1.into(), 0.into()]);
        }
        extract_image(&doc, &Stream::new(d, data), 0xFF00_FF00, &HashMap::new())
            .expect("a G4 stencil must decode")
    }

    /// A `/ImageMask` CCITT stencil paints the fill colour where the fax has BLACK
    /// pels and leaves the rest of the page alone (§8.9.6.2). Rendering the
    /// complement is the "solid dark block over a scanned page" symptom.
    #[test]
    fn ccitt_stencil_paints_the_black_pels() {
        let img = ccitt_stencil(false, false);
        assert_eq!((img.w, img.h, img.format), (8, 2, 0));
        assert_eq!(
            &img.data[0..4], &[0, 255, 0, 255],
            "a black pel takes the fill colour, opaque"
        );
        assert_eq!(img.data[4 * 3 + 3], 255, "still black at x=3");
        assert_eq!(img.data[4 * 4 + 3], 0, "the white half is transparent");
        assert_eq!(img.data[4 * 7 + 3], 0, "and stays transparent to the row end");
        // Row 1 is the same pattern: a polarity that depended on the row would be
        // a reference-line bug rather than a polarity bug.
        assert_eq!(img.data[4 * 8 + 3], 255, "row 1, x=0 is painted");
        assert_eq!(img.data[4 * 12 + 3], 0, "row 1, x=4 is transparent");
    }

    /// `/Decode [1 0]` reverses a CCITT stencil EXACTLY ONCE. The raster loop
    /// applies it via `black_bit` and `stencilize` is then called with
    /// `invert = false`; if a future change also passes `mask_invert` here, the two
    /// cancel and this test sees the un-inverted image.
    #[test]
    fn ccitt_stencil_decode_array_inverts_exactly_once() {
        let plain = ccitt_stencil(false, false);
        let inverted = ccitt_stencil(true, false);
        // Only alpha is asserted for an unpainted pixel: `stencilize` zeroes alpha and
        // leaves RGB as the raster left it, so the colour under a transparent pixel is
        // not part of the contract. Here it is the white the raster is initialised to,
        // because `/Decode [1 0]` makes the loop skip the black pels rather than write
        // them — pinning it would pin which of the two stages inverts, not that exactly
        // one does.
        assert_eq!(
            inverted.data[3], 0,
            "/Decode [1 0] must stop painting the black pels"
        );
        assert_eq!(
            &inverted.data[4 * 4..4 * 4 + 4], &[0, 255, 0, 255],
            "and must paint the white half instead"
        );
        // Stated as a whole-raster complement so a partial inversion (one row, or
        // only the fast path) cannot pass.
        for px in 0..(8 * 2) {
            assert_ne!(
                plain.data[px * 4 + 3], inverted.data[px * 4 + 3],
                "pixel {px} must flip under /Decode [1 0]"
            );
        }
    }

    /// `/BlackIs1` is the CCITT path's SECOND inversion source, and it lives in the
    /// filter: §7.4.6 Table 11 says 1 bits are black when it is true, "the reverse of
    /// the normal PDF convention", and `decode_ccitt` emits that polarity. The image
    /// layer then reads sample 0 as black per §8.9.5.2 without re-applying the flag,
    /// so the decoded stencil comes out reversed — which is what a `/Decode [1 0]`
    /// alongside `/BlackIs1 true` exists to undo. Pinned because it is the one
    /// polarity input NOT applied where the others are, so a well-meaning "fix" that
    /// folds it into `black_bit` as well would double-invert and silently return this
    /// to the un-reversed raster.
    #[test]
    fn ccitt_black_is1_reverses_the_stencil_and_decode_restores_it() {
        let plain = ccitt_stencil(false, false);
        let black_is1 = ccitt_stencil(false, true);
        for px in 0..(8 * 2) {
            assert_ne!(
                plain.data[px * 4 + 3], black_is1.data[px * 4 + 3],
                "pixel {px}: /BlackIs1 true reverses the decoded samples"
            );
        }
        let restored = ccitt_stencil(true, true);
        assert_eq!(
            plain.data, restored.data,
            "/BlackIs1 true with /Decode [1 0] is the same image as neither"
        );
    }

    /// One JBIG2 segment header (embedded organisation, §7.2): number, flags
    /// carrying the type, an empty referred-to list, a 1-byte page association and
    /// the data length.
    fn jbig2_segment(number: u32, seg_type: u8, page: u8, data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&number.to_be_bytes());
        out.push(seg_type); // page-association size bit clear => 1 byte
        out.push(0x00); // referred-to count 0, no retain flags
        out.push(page);
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        out.extend_from_slice(data);
        out
    }

    /// An embedded JBIG2 stream holding one immediate lossless generic region coded
    /// with MMR (which is T.6, so the same G4 bytes a fax uses).
    fn jbig2_mmr_stream(width: u32, height: u32, mmr: &[u8]) -> Vec<u8> {
        let mut page = Vec::new();
        page.extend_from_slice(&width.to_be_bytes());
        page.extend_from_slice(&height.to_be_bytes());
        page.extend_from_slice(&0u32.to_be_bytes()); // x resolution
        page.extend_from_slice(&0u32.to_be_bytes()); // y resolution
        page.push(0x01); // lossless, default pixel 0 (white)
        page.extend_from_slice(&0u16.to_be_bytes()); // striping

        let mut region = Vec::new();
        region.extend_from_slice(&width.to_be_bytes());
        region.extend_from_slice(&height.to_be_bytes());
        region.extend_from_slice(&0u32.to_be_bytes()); // x
        region.extend_from_slice(&0u32.to_be_bytes()); // y
        region.push(0x00); // external combination operator OR
        region.push(0x01); // generic region flags: MMR = 1, so no AT pixels follow
        region.extend_from_slice(mmr);

        let mut out = jbig2_segment(0, 48, 1, &page);
        out.extend_from_slice(&jbig2_segment(1, 39, 1, &region));
        out
    }

    fn jbig2_stencil(decode_inverts: bool) -> Option<ImageData> {
        let doc = Document::with_version("1.7");
        let (mmr, w) = half_black_g4();
        let data = jbig2_mmr_stream(w as u32, 2, &mmr);
        let mut d = dictionary! {
            "Width" => w as i64, "Height" => 2, "BitsPerComponent" => 1,
            "ImageMask" => true,
            "Filter" => "JBIG2Decode",
        };
        if decode_inverts {
            d.set("Decode", vec![1.into(), 0.into()]);
        }
        extract_image(&doc, &Stream::new(d, data), 0xFF00_FF00, &HashMap::new())
    }

    /// The JBIG2 stencil branch is a different route to `stencilize` from CCITT's:
    /// the decoder hands back an already-black-on-white RGBA raster and `/Decode` is
    /// applied ONLY by `stencilize`'s `invert`. Both directions are pinned because
    /// this branch, unlike CCITT's, would silently paint nothing at all if the
    /// polarity were reversed on a mostly-white scan.
    #[test]
    fn jbig2_stencil_paints_the_black_pixels_and_decode_inverts_it() {
        let plain = jbig2_stencil(false).expect("an MMR generic region must decode");
        assert_eq!((plain.w, plain.h, plain.format), (8, 2, 0));
        assert_eq!(
            &plain.data[0..4], &[0, 255, 0, 255],
            "a black JBIG2 pixel takes the fill colour"
        );
        assert_eq!(plain.data[4 * 4 + 3], 0, "the white half is transparent");

        let inverted = jbig2_stencil(true).expect("decodes");
        for px in 0..(8 * 2) {
            assert_ne!(
                plain.data[px * 4 + 3], inverted.data[px * 4 + 3],
                "pixel {px} must flip under /Decode [1 0]"
            );
        }
    }

    // §7.4.9 Table 89: all three /SMaskInData values must behave DIFFERENTLY. The old
    // code applied alpha whenever the channel count suggested one, which made value 0
    // (and an absent entry, whose default is 0) wrongly transparent, and never undid
    // value 2's premultiplication, which left a dark fringe on every soft edge.
    #[test]
    fn smask_in_data_distinguishes_all_three_values() {
        use super::jp2::resolve_alpha;
        // 4-channel sRGB+alpha codestream, no cdef box.
        assert_eq!(resolve_alpha(0, 4, 3, None), (None, false), "0 ignores the alpha channel");
        assert_eq!(resolve_alpha(1, 4, 3, None), (Some(3), false), "1 uses it as the soft mask");
        assert_eq!(resolve_alpha(2, 4, 3, None), (Some(3), true), "2 also un-premultiplies");
        // No extra channel to use: nonzero /SMaskInData cannot invent one, and must not
        // report un-premultiplication for an alpha that does not exist.
        assert_eq!(resolve_alpha(2, 3, 3, None), (None, false), "3-channel RGB has no alpha");
        // A cdef box naming channel 3 as opacity is honoured; one naming a COLOUR
        // channel is rejected in favour of the conventional trailing position.
        assert_eq!(resolve_alpha(1, 4, 3, Some(3)), (Some(3), false));
        assert_eq!(resolve_alpha(1, 4, 3, Some(1)), (Some(3), false));
        // Gray+alpha is two channels, not four.
        assert_eq!(resolve_alpha(1, 2, 1, None), (Some(1), false));
        assert_eq!(resolve_alpha(0, 2, 1, None), (None, false));
    }

    // §7.4.9: the PDF's /ColorSpace overrides the codestream's — except for sYCC, which
    // is a channel encoding the decoder must still convert, not a PDF colour space.
    #[test]
    fn pdf_colorspace_overrides_codestream_except_ycc() {
        use super::jp2::{resolve_interp, Interp};
        assert!(resolve_interp(Some(Interp::Rgb), Some(Interp::Cmyk), 4) == Interp::Cmyk,
            "the PDF /ColorSpace wins over the codestream's");
        assert!(resolve_interp(Some(Interp::Ycc), Some(Interp::Rgb), 3) == Interp::Ycc,
            "sYCC must still be converted, so a /DeviceRGB hint cannot suppress it");
        assert!(resolve_interp(None, None, 1) == Interp::Gray, "fall back to channel count");
        assert!(resolve_interp(None, None, 4) == Interp::Cmyk);
        assert!(resolve_interp(Some(Interp::Gray), None, 1) == Interp::Gray);
    }

    fn solid_fill(argb: u32, pts: &[(f32, f32)]) -> Prim {
        Prim::Fill {
            argb,
            even_odd: false,
            contours: vec![pts.to_vec()],
            blend: BlendMode::Normal,
        }
    }

    // The tiling-pattern cell rasterizer (8.7.3.3): a cell must land in the raster with
    // the right colour, the right ORIENTATION, and transparency everywhere the cell does
    // not paint. Row 0 is the TOP of the image (unit-square v=1), per 8.9.5.2's
    // upper-left first sample and the convention real decoded scanlines already follow.
    // Getting this backwards mirrors every cell and every shading vertically.
    #[test]
    fn tile_raster_places_row_zero_at_high_y() {
        // Paint only the BOTTOM half of a unit box.
        let prims = vec![solid_fill(
            0xFF00_00FF,
            &[(0.0, 0.0), (1.0, 0.0), (1.0, 0.5), (0.0, 0.5)],
        )];
        let out = rasterize_prims_to_rgba(&prims, [0.0, 0.0, 1.0, 1.0], 4, 4).expect("rasterizes");
        assert_eq!(out.len(), 4 * 4 * 4);
        // Row 0 = TOP = high y = the unpainted half, transparent rather than black.
        assert_eq!(out[3], 0, "row 0 is the box's HIGH y edge, which is unpainted here");
        // Row 3 = bottom = low y = painted.
        let bottom = 3 * 4 * 4;
        assert_eq!(&out[bottom..bottom + 4], &[0, 0, 255, 255], "the last row is low y");
    }

    // A hairline narrower than one pixel must survive as partial coverage. Without
    // antialiasing a hatch rule drops out of a small cell raster entirely, which looks
    // exactly like the blank-region bug the raster is meant to cure.
    #[test]
    fn tile_raster_antialiases_a_subpixel_hairline() {
        // A 0.1-unit-wide vertical bar in a 1x1 box rendered at 4x4: a tenth of a unit is
        // 0.4 px, so no pixel centre lands inside it.
        let prims = vec![solid_fill(
            0xFF00_0000,
            &[(0.45, 0.0), (0.55, 0.0), (0.55, 1.0), (0.45, 1.0)],
        )];
        let out = rasterize_prims_to_rgba(&prims, [0.0, 0.0, 1.0, 1.0], 4, 4).expect("rasterizes");
        let painted: u32 = out.chunks_exact(4).map(|p| p[3] as u32).sum();
        assert!(painted > 0, "a subpixel hairline must not vanish");
        let (row, col) = (0usize, 1usize);
        let mid = out[(row * 4 + col) * 4 + 3];
        assert!(mid > 0 && mid < 255, "partial coverage, got alpha {mid}");
    }

    // Strokes are outlined and unioned with nonzero winding. Overlapping segment quads
    // must not composite twice — that darkens every join of a translucent stroke — and
    // opposite quad orientations must not cancel to nothing.
    #[test]
    fn tile_raster_strokes_union_without_double_compositing() {
        let prims = vec![Prim::Stroke {
            argb: 0x8000_0000, // half-opaque black
            width: 0.4,
            dash: Vec::new(),
            dash_phase: 0.0,
            cap: 0,
            join: 0,
            miter: 10.0,
            // An L: the two segments overlap at the corner vertex.
            pts: vec![(0.2, 0.5), (0.5, 0.5), (0.5, 0.2)],
            blend: BlendMode::Normal,
        }];
        let out = rasterize_prims_to_rgba(&prims, [0.0, 0.0, 1.0, 1.0], 16, 16).expect("rasterizes");
        // The corner pixel is covered by both quads and both vertex squares.
        let corner = out[(8 * 16 + 8) * 4 + 3];
        assert!(corner > 0, "the stroke must paint at all");
        assert!(
            corner <= 130,
            "a half-opaque stroke must stay half-opaque at a join, got alpha {corner}"
        );
    }

    // The cap is on the RASTER, not the tile count — that inversion is the point of the
    // whole exercise, so an over-budget request must be refused rather than allocated.
    #[test]
    fn tile_raster_refuses_an_over_budget_request() {
        let prims = vec![solid_fill(0xFFFF_0000, &[(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)])];
        let side = MAX_TILE_RASTER_BYTES; // wildly over any sane cell size
        assert!(rasterize_prims_to_rgba(&prims, [0.0, 0.0, 1.0, 1.0], side, side).is_none());
        // A degenerate box is refused too, rather than dividing by zero.
        assert!(rasterize_prims_to_rgba(&prims, [0.0, 0.0, 0.0, 1.0], 8, 8).is_none());
    }

    // CORRECTION 1 from viewer: a BitmapShader in REPEAT mode has a period equal to the
    // BITMAP's dimensions, and PDF's /XStep is independent of the /BBox. So the cell must
    // be rasterized at the STEP, with transparent padding out to it. A bbox-sized cell
    // would retile at the wrong spacing, which shows as a pattern at subtly wrong density.
    #[test]
    fn pattern_cell_is_rasterized_at_the_step_not_the_bbox() {
        // A 10x10 bbox fully painted, on a 20x20 lattice: half the cell must be padding.
        let prims = vec![solid_fill(
            0xFFFF_0000,
            &[(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)],
        )];
        let (w, h, data) =
            rasterize_pattern_cell(&prims, [0.0, 0.0, 10.0, 10.0], 20.0, 20.0, 2.0)
                .expect("non-overlapping pattern rasterizes");
        // 20 step units x 2 px/unit = 40, NOT the bbox's 10 x 2 = 20.
        assert_eq!((w, h), (40, 40), "the raster covers /XStep x /YStep");
        assert_eq!(data.len(), 40 * 40 * 4);
        // Row 0 is the TOP (high y), which is beyond the bbox: transparent padding.
        assert_eq!(data[3], 0, "step-beyond-bbox margin must be transparent");
        // The last row is the bottom (low y), where the bbox content sits.
        let bottom = (39 * 40) * 4;
        assert_eq!(
            &data[bottom..bottom + 4],
            &[255, 0, 0, 255],
            "the cell content is present at the bbox origin"
        );
    }

    // CORRECTION 2 from viewer was that a single-cell repeat cannot express 8.7.3.1
    // overlap. True, but the pattern IS still periodic with period (xstep, ystep) - so
    // instead of refusing, the cell is drawn at every lattice offset that can reach one
    // period window. This test isolates that: the cell paints ONLY its top-right quadrant,
    // which lies entirely OUTSIDE the period window, so a single-cell raster would be
    // fully transparent. The neighbour at offset (-1,-1) is what fills it.
    #[test]
    fn overlapping_pattern_composites_reaching_neighbours() {
        // bbox 20x20, step 10 -> each period window is reached by 2x2 cells.
        let prims = vec![solid_fill(
            0xFFFF_0000,
            &[(10.0, 10.0), (20.0, 10.0), (20.0, 20.0), (10.0, 20.0)],
        )];
        let bbox = [0.0, 0.0, 20.0, 20.0];
        let (w, h, data) = rasterize_pattern_cell(&prims, bbox, 10.0, 10.0, 2.0)
            .expect("an overlapping pattern is still periodic");
        assert_eq!((w, h), (20, 20), "the period is the STEP, 10 units at scale 2");
        // Every pixel of the period must be opaque: cell (-1,-1)'s top-right quadrant
        // lands exactly on the window. Without neighbour compositing this is all zero.
        let opaque = data.chunks_exact(4).filter(|p| p[3] == 255).count();
        assert_eq!(
            opaque,
            (w * h) as usize,
            "the reaching neighbour must fill the whole period; got {opaque} of {}",
            w * h
        );
        assert_eq!(&data[0..3], &[255, 0, 0], "and carry the cell's colour");
    }

    // The non-overlapping case must be unaffected, and a pathological bbox/step ratio
    // must still fall back rather than compositing an absurd number of copies.
    #[test]
    fn pattern_cell_gates_only_pathological_overlap() {
        let prims = vec![solid_fill(
            0xFFFF_0000,
            &[(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)],
        )];
        let bbox = [0.0, 0.0, 10.0, 10.0];
        // Exactly abutting: one copy, no neighbours.
        assert!(rasterize_pattern_cell(&prims, bbox, 10.0, 10.0, 2.0).is_some());
        // Mild overlap is now supported rather than refused.
        assert!(rasterize_pattern_cell(&prims, bbox, 6.0, 10.0, 2.0).is_some());
        assert!(rasterize_pattern_cell(&prims, bbox, 10.0, 6.0, 2.0).is_some());
        // A bbox 100x the step in both axes would need 10,000 copies per period.
        assert!(
            rasterize_pattern_cell(&prims, bbox, 0.1, 0.1, 2.0).is_none(),
            "a pathological bbox/step ratio must fall back to the per-tile path"
        );
        // Degenerate steps are refused rather than dividing by zero.
        assert!(rasterize_pattern_cell(&prims, bbox, 0.0, 10.0, 2.0).is_none());
        assert!(rasterize_pattern_cell(&prims, bbox, 10.0, 10.0, 0.0).is_none());
    }

    // A cell over budget is SCALED, not refused: the tiling stays exact because the
    // renderer's period is the bitmap whatever its resolution, so downscaling only softens
    // the cell. Refusing would drop back to the per-tile path for no reason.
    #[test]
    fn oversized_pattern_cell_scales_to_fit_its_budget() {
        let prims = vec![solid_fill(0xFF00_FF00, &[(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)])];
        let (w, h, data) =
            rasterize_pattern_cell(&prims, [0.0, 0.0, 1.0, 1.0], 1.0, 1.0, 4000.0)
                .expect("must scale rather than refuse");
        assert!(
            (w as usize) * (h as usize) * 4 <= MAX_TILE_RASTER_BYTES,
            "{w}x{h} exceeds MAX_TILE_RASTER_BYTES"
        );
        assert_eq!(w, h, "a square period stays square");
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
    }

    // §8.9.7 Table 93 abbreviates the stencil flag to `/IM`, which this path did not
    // check — so an inline image mask was decoded as a one-bit DeviceGray image and
    // painted as an opaque black-and-white rectangle instead of stencilling the fill
    // colour, hiding whatever was underneath.
    #[test]
    fn inline_image_mask_stencils_the_fill_colour() {
        let doc = Document::with_version("1.7");
        // 8x1, bits 1,0,1,0,0,0,0,0. Default /Decode: sample 0 MARKS the page.
        let stream = Stream::new(
            dictionary! { "W" => 8, "H" => 1, "IM" => true },
            vec![0b1010_0000u8],
        );
        let img = extract_inline_image(&doc, &stream, 0xFF00_FF00, &HashMap::new())
            .expect("inline stencil decodes");
        assert_eq!((img.w, img.h, img.format), (8, 1, 0));
        // Bit 1 -> sample 255 -> not marked -> transparent.
        assert_eq!(img.data[3], 0, "a 1 bit leaves the page alone");
        // Bit 0 -> sample 0 -> marked -> painted with the fill colour, opaque.
        assert_eq!(&img.data[4..8], &[0, 255, 0, 255], "a 0 bit paints the fill colour");
        assert_eq!(img.data[11], 0, "and the next 1 bit is transparent again");
    }

    // Same path, /D [1 0]: the inline abbreviation for /Decode must reverse the sense.
    #[test]
    fn inline_image_mask_honours_abbreviated_decode() {
        let doc = Document::with_version("1.7");
        let stream = Stream::new(
            dictionary! { "W" => 8, "H" => 1, "IM" => true, "D" => vec![1.into(), 0.into()] },
            vec![0b1010_0000u8],
        );
        let img = extract_inline_image(&doc, &stream, 0xFF00_FF00, &HashMap::new()).unwrap();
        assert_eq!(&img.data[0..4], &[0, 255, 0, 255], "/D [1 0] paints where the 1 bits are");
        assert_eq!(img.data[7], 0);
    }

    // An inline image whose /CS names a resource entry must decode at the resolved
    // component count. `colorspace_info` reports 1 for every name, so a 3-component
    // space decoded at 1/3 stride — the classic sheared-grey-garbage look.
    #[test]
    fn inline_image_resolves_named_colorspace_stride() {
        let mut doc = Document::with_version("1.7");
        let cs_id = doc.add_object(Object::Array(vec![
            Object::Name(b"CalRGB".to_vec()),
            Object::Dictionary(dictionary! {
                "WhitePoint" => vec![0.9505.into(), 1.0.into(), 1.089.into()],
            }),
        ]));
        let mut res = HashMap::new();
        res.insert(b"Cs0".to_vec(), cs_id);
        // 2x1 pixels of 3 components: red then green.
        let stream = Stream::new(
            dictionary! { "W" => 2, "H" => 1, "BPC" => 8, "CS" => "Cs0" },
            vec![255u8, 0, 0, 0, 255, 0],
        );
        let img = extract_inline_image(&doc, &stream, 0xFF00_0000, &res).expect("decodes");
        assert_eq!((img.w, img.h), (2, 1));
        assert!(img.data[0] > img.data[1], "pixel 0 is red-dominant, got {:?}", &img.data[0..3]);
        assert!(img.data[5] > img.data[4], "pixel 1 is green-dominant, got {:?}", &img.data[4..7]);
    }

    // The colour-key range bounds must pass through the SAME scaling the unpacker
    // applied to the samples. A generic `v*255/maxval` agrees for 1/2/4/8 bpc but not
    // for 12 or 16, where the unpacker keeps the high bits — so a range could miss the
    // very value it names.
    #[test]
    fn color_key_ranges_match_the_unpacker_at_16bpc() {
        // One 16-bpc gray pixel, raw value 0x8000. The unpacker keeps the high byte: 0x80.
        let samples = vec![0x80u8, 0x00];
        let comps = unpack_samples_to_bytes(&samples, 1, 1, 1, 16).unwrap();
        assert_eq!(comps[0], 0x80);
        let mut rgba = vec![9u8, 9, 9, 255];
        // A range naming exactly that raw value must mask the pixel.
        apply_color_key_mask_samples(&mut rgba, &comps, 1, &[(0x8000, 0x8000)], 16);
        assert_eq!(rgba[3], 0, "the named 16-bit sample value must be masked");
    }

    // §8.9.5.1 Table 89: /Interpolate defaults to false. Bilevel art must never be
    // smoothed on magnification unless the file explicitly asks — that is what makes a
    // magnified QR code or stencil unreadable — while a contone photo should be.
    #[test]
    fn interpolation_is_refused_for_bilevel_art() {
        let doc = Document::with_version("1.7");
        let contone = dictionary! { "Width" => 8, "Height" => 8, "BitsPerComponent" => 8 };
        assert!(image_should_interpolate(&doc, &contone), "smooth a contone photo");

        let stencil = dictionary! { "Width" => 8, "Height" => 8, "ImageMask" => true };
        assert!(!image_should_interpolate(&doc, &stencil), "never smooth a stencil");

        let one_bit = dictionary! { "Width" => 8, "Height" => 8, "BitsPerComponent" => 1 };
        assert!(!image_should_interpolate(&doc, &one_bit), "never smooth 1-bit art");

        let fax = dictionary! { "Width" => 8, "Height" => 8, "Filter" => "CCITTFaxDecode" };
        assert!(!image_should_interpolate(&doc, &fax), "never smooth a scanned fax");

        // An explicit request wins over all of that.
        let asked = dictionary! {
            "Width" => 8, "Height" => 8, "BitsPerComponent" => 1, "Interpolate" => true,
        };
        assert!(image_should_interpolate(&doc, &asked), "/Interpolate true is honoured");
        // And so does the inline abbreviation `/I`.
        let asked_inline = dictionary! { "W" => 8, "H" => 8, "IM" => true, "I" => true };
        assert!(image_should_interpolate(&doc, &asked_inline), "/I true is honoured");
    }

    // §8.9.5.2 Table 90: an explicit /Decode on an Indexed image remaps the sample onto
    // the index range, and the Indexed branch ignored it. The default [0 2^bpc-1] must
    // stay the identity, so pin both directions.
    #[test]
    fn indexed_decode_array_remaps_the_palette_index() {
        let mut doc = Document::with_version("1.7");
        // 4-entry RGB palette: black, red, green, blue.
        let pal: Vec<u8> = vec![0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255];
        let cs = Object::Array(vec![
            Object::Name(b"Indexed".to_vec()),
            Object::Name(b"DeviceRGB".to_vec()),
            Object::Integer(3),
            Object::String(pal, lopdf::StringFormat::Literal),
        ]);
        let cs_id = doc.add_object(cs);
        let mut res = HashMap::new();
        res.insert(b"Cs".to_vec(), cs_id);

        // One 8-bpc sample of 255. With the default decode that is index 255, clamped to
        // hival 3 -> blue.
        let plain = dictionary! { "ColorSpace" => "Cs", "BitsPerComponent" => 8 };
        let out = image_samples_to_rgba(&doc, &plain, &res, &[255u8], 1, 1, 1, 8);
        assert_eq!(&out[0..3], &[0, 0, 255], "default decode clamps to hival -> blue");

        // /Decode [0 1] maps sample 0..255 onto index 0..1, so 255 -> index 1 -> RED.
        let decoded = dictionary! {
            "ColorSpace" => "Cs",
            "BitsPerComponent" => 8,
            "Decode" => vec![0.into(), 1.into()],
        };
        let out = image_samples_to_rgba(&doc, &decoded, &res, &[255u8], 1, 1, 1, 8);
        assert_eq!(&out[0..3], &[255, 0, 0], "/Decode [0 1] remaps 255 to index 1 -> red");
        // And the low end still maps to index 0.
        let out = image_samples_to_rgba(&doc, &decoded, &res, &[0u8], 1, 1, 1, 8);
        assert_eq!(&out[0..3], &[0, 0, 0], "sample 0 stays index 0 -> black");
    }
}

#[cfg(test)]
mod shading_raster_tests {
    use super::*;

    fn ramp_fn(doc: &mut Document) -> ObjectId {
        doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into()],
            "C1" => vec![1.into(), 1.into(), 1.into()],
            "N" => 1,
        })
    }

    // Round 1's E-12: the raster used to be `let w = size; let h = size;`, so a
    // 600x5pt gradient bar allocated size^2 pixels — ~100x what it covers, and tripled
    // across `prims`, the wire buffer and a Kotlin Bitmap. The placement CTM maps the
    // unit square, so a non-square raster is geometrically identical. This landed in
    // round 1 with no test; pin it.
    #[test]
    fn axial_shading_raster_follows_bbox_aspect_ratio() {
        let mut doc = Document::with_version("1.7");
        let f = ramp_fn(&mut doc);
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.into(), 0.into(), 600.into(), 0.into()],
            "BBox" => vec![0.into(), 0.into(), 600.into(), 5.into()],
            "Function" => Object::Reference(f),
        });
        let (_, w, h, data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 512, None).expect("rasterizes");
        assert_eq!(w, 512, "the long axis takes the requested size");
        assert!(h < 20, "a 600x5 bbox must not allocate a square raster; got {w}x{h}");
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
    }

    // Axial/radial colour comes from a 256-entry LUT over one scalar, so the raster can
    // hold at most 256 distinct colours; MAX_GRADIENT_RASTER_BYTES trims the bytes that
    // cannot encode anything. A near-square gradient must land inside it.
    #[test]
    fn near_square_gradient_stays_within_the_byte_budget() {
        let mut doc = Document::with_version("1.7");
        let f = ramp_fn(&mut doc);
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.into(), 0.into(), 1000.into(), 1000.into()],
            "BBox" => vec![0.into(), 0.into(), 1000.into(), 1000.into()],
            "Function" => Object::Reference(f),
        });
        let (_, w, h, data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 1024, None).expect("rasterizes");
        assert!(
            (w as usize) * (h as usize) * 4 <= MAX_GRADIENT_RASTER_BYTES,
            "{w}x{h} = {} bytes exceeds the gradient budget",
            (w as usize) * (h as usize) * 4
        );
        // The square aspect must survive the scale-down.
        assert_eq!(w, h, "scaling to fit the budget must preserve the aspect ratio");
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
    }

    // Round 2 found that every synthesised raster was mirrored vertically: row 0 was
    // sampled at the bbox's LOW y while the placement CTM's positive `d` and 8.9.5.2's
    // upper-left-first-sample rule put row 0 at the HIGH y edge. An axial ramp therefore
    // ran backwards on the page. Nothing pinned the orientation, which is why it survived
    // a whole round of review - so pin it with an asymmetric gradient.
    #[test]
    fn axial_shading_row_zero_is_the_high_y_edge() {
        let mut doc = Document::with_version("1.7");
        // Black at t=0 -> white at t=1, running along +y over the bbox.
        let f = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into()],
            "C1" => vec![1.into(), 1.into(), 1.into()],
            "N" => 1,
        });
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            // Axis from y=0 to y=100, so t grows with y: dark at the bottom, light at top.
            "Coords" => vec![0.into(), 0.into(), 0.into(), 100.into()],
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            "Function" => Object::Reference(f),
        });
        let (_, w, h, data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 64, None).expect("rasterizes");
        let row = |r: u32| data[(r as usize * w as usize) * 4];
        // Row 0 is the TOP of the image = HIGH y = t near 1 = WHITE.
        assert!(row(0) > 200, "row 0 must be the high-y (light) end, got {}", row(0));
        // The last row is the bottom = low y = t near 0 = BLACK.
        assert!(
            row(h - 1) < 55,
            "the last row must be the low-y (dark) end, got {}",
            row(h - 1)
        );
        assert!(row(0) > row(h - 1), "the ramp must not be mirrored");
    }

    // Type 1 (function-based) was MISSED by round 1's E-12 and was still square, with no
    // byte cap at all. Its raster must follow the device extent of /Domain through
    // /Matrix, exactly as types 2/3 follow their bbox.
    #[test]
    fn function_based_shading_raster_follows_domain_aspect_ratio() {
        let mut doc = Document::with_version("1.7");
        // A 2-in/3-out sampled function is awkward to build; a Type 4 is not.
        let f = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![0.into(), 600.into(), 0.into(), 5.into()],
                "Range" => vec![0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
            },
            // x y -> (0, 0, 0.5): ignore both inputs, emit a constant colour.
            b"{ pop pop 0 0 0.5 }".to_vec(),
        ));
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 1,
            "ColorSpace" => "DeviceRGB",
            "Domain" => vec![0.into(), 600.into(), 0.into(), 5.into()],
            "Function" => Object::Reference(f),
        });
        let (_, w, h, data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 512, None).expect("rasterizes");
        assert_eq!(w, 512, "the long axis takes the requested size");
        assert!(h < 20, "a 600x5 /Domain must not allocate a square raster; got {w}x{h}");
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
        // And the reported dimensions must match the buffer — the old code returned
        // (size, size) regardless of what it had actually filled.
        assert_eq!(data[3], 255, "the raster is actually painted");
    }
}

#[cfg(test)]
mod radial_tests {
    use super::radial_shading_param;

    // A concentric radial gradient (same center, r0=0..r1=50) must vary with
    // distance from the center — the old axial approximation collapsed it to a
    // single value because the centers coincide.
    #[test]
    fn concentric_radial_varies_with_radius() {
        let coords = [50.0, 50.0, 0.0, 50.0, 50.0, 50.0];
        let center = radial_shading_param(&coords, true, true, 50.0, 50.0).unwrap();
        let mid = radial_shading_param(&coords, true, true, 75.0, 50.0).unwrap();
        let edge = radial_shading_param(&coords, true, true, 100.0, 50.0).unwrap();
        assert!((center - 0.0).abs() < 1e-6, "center s={center}");
        assert!((mid - 0.5).abs() < 1e-6, "mid s={mid}");
        assert!((edge - 1.0).abs() < 1e-6, "edge s={edge}");
        assert!(center < mid && mid < edge);
    }

    // Outside the outer circle with Extend[1]=false there is no covering circle.
    #[test]
    fn outside_without_extend_is_none() {
        let coords = [50.0, 50.0, 0.0, 50.0, 50.0, 50.0];
        assert!(radial_shading_param(&coords, false, false, 200.0, 50.0).is_none());
        // With Extend[1]=true the far point still maps (s>1 allowed).
        let s = radial_shading_param(&coords, false, true, 200.0, 50.0).unwrap();
        assert!(s > 1.0, "expected extended s>1, got {s}");
    }

    // Offset circles (different centers) still resolve on the axis.
    #[test]
    fn offset_circles_resolve_on_axis() {
        let coords = [0.0, 0.0, 10.0, 100.0, 0.0, 10.0];
        // On the segment between centers, near the start circle boundary.
        let s = radial_shading_param(&coords, true, true, 10.0, 0.0).unwrap();
        assert!((0.0..=1.0).contains(&s), "s={s}");
    }
}
