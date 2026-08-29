use crate::*;
use std::sync::Arc;

/// Highest CID a composite font may use (PDF 32000-1 9.7.4.3). Ranges read from
/// untrusted `/W`, `/W2` and CMap data are clamped to this so a corrupt or
/// hostile upper bound cannot drive a multi-billion-iteration loop.
pub(crate) const MAX_CID: u32 = 0xFFFF;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub(crate) struct FontStyle {
    pub(crate) bold: bool,
    pub(crate) italic: bool,
}

#[derive(Clone)]
pub(crate) struct FontInfo {
    /// Type0 (Identity-H) fonts use 2-byte codes; simple fonts use 1 byte.
    pub(crate) two_byte: bool,
    pub(crate) wmode: u8,
    /// CID -> `(w1_y, v_x)` from `/W2`: the vertical displacement and the x
    /// component of the position vector, in text-space units (glyph units/1000).
    pub(crate) vertical_metrics: HashMap<u32, (f64, f64)>,
    /// `(v_y, w1_y)` from `/DW2`, text-space units. Spec default `[880 -1000]`.
    pub(crate) default_vertical: (f64, f64),
    pub(crate) cid_to_gid: Option<HashMap<u32, u16>>,
    /// `code -> unicode string` from the font's `/ToUnicode` CMap, if any.
    /// Shared, not owned: see [`FontCacheScope`].
    pub(crate) to_unicode: Option<Arc<HashMap<u32, String>>>,
    /// `code -> unicode char` from the simple-font encoding (base + Differences),
    /// used when `/ToUnicode` is absent or lacks the code.
    pub(crate) encoding: HashMap<u32, char>,
    /// `code -> unicode char` recovered from an embedded TrueType `cmap`, for
    /// re-encoded subset fonts without `/ToUnicode`. Preferred over `encoding`.
    pub(crate) cmap_uni: HashMap<u32, char>,
    /// Type0 `/Encoding` CMap mapping character codes -> CIDs (non-Identity CJK
    /// encodings). `None` for Identity-H/V (code == CID) and simple fonts.
    pub(crate) cmap: Option<cmap::EncodingCMap>,
    /// `code (or CID) -> glyph width` in text-space units (glyph units / 1000).
    pub(crate) widths: HashMap<u32, f64>,
    /// Fallback width (glyph units / 1000) for codes absent from `widths`.
    pub(crate) default_width: f64,
    /// Type 3 font data (glyph CharProc content streams), if this is a Type 3 font.
    pub(crate) t3: Option<Type3Font>,
    /// Synthetic font style recovered from BaseFont name + FontDescriptor.
    pub(crate) style: FontStyle,
    /// Generic font family for substitute shaping on the Kotlin side, recovered
    /// from the BaseFont name + FontDescriptor `/Flags`: 0 = sans-serif,
    /// 1 = serif, 2 = monospace.
    pub(crate) family: u8,
    /// Descriptive base font name for fallback shaping (optional).
    pub(crate) base_font: String,
    /// Embedded font program (TrueType/CFF/Type1) for rendering the PDF's real
    /// glyph outlines. `None` falls back to system-font substitution. Shared
    /// rather than owned because it holds the whole decompressed program: see
    /// [`FontCacheScope`].
    pub(crate) glyph_program: Option<Arc<crate::outlines::GlyphProgram>>,
    /// `code -> glyph name` from the PDF `/Encoding` `/Differences`, used to look
    /// up outlines by name in Type1 / CFF programs.
    pub(crate) glyph_names: HashMap<u32, String>,
}

/// Type 3 font: glyphs are content streams drawn in glyph space, mapped to text
/// space by `font_matrix`.
#[derive(Clone)]
pub(crate) struct Type3Font {
    pub(crate) font_matrix: Mat,
    /// Character code -> CharProc stream object id (via `/Encoding` Differences).
    pub(crate) char_procs: HashMap<u32, ObjectId>,
    pub(crate) resources: Option<Dictionary>,
}

impl FontInfo {
    /// Invoke `f(code, is_single_byte_space)` for each character code in the
    /// string, honoring this font's code width (1 or 2 bytes).
    pub(crate) fn for_each_code(&self, bytes: &[u8], mut f: impl FnMut(u32, bool)) {
        if self.two_byte {
            match &self.cmap {
                // Non-Identity CMap: segment bytes by the codespace (variable
                // length) and yield the raw character code. Width/glyph lookups
                // map code -> CID via `to_cid`.
                Some(cm) => {
                    let mut i = 0;
                    while i < bytes.len() {
                        let n = cm.code_len(bytes[i]).clamp(1, 4).min(bytes.len() - i);
                        let mut c = 0u32;
                        for k in 0..n { c = (c << 8) | bytes[i + k] as u32; }
                        // Tw applies only to a single-byte code 32.
                        f(c, n == 1 && c == 32);
                        i += n;
                    }
                }
                // Identity-H/V: fixed 2-byte codes, code == CID.
                None => {
                    let mut i = 0;
                    while i + 1 < bytes.len() {
                        let code = ((bytes[i] as u32) << 8) | bytes[i + 1] as u32;
                        // Word spacing (Tw) never applies to 2-byte codes (PDF 9.3.3).
                        f(code, false);
                        i += 2;
                    }
                }
            }
        } else {
            for &b in bytes {
                let code = b as u32;
                // PDF 9.3.3: Tw applies to the single-byte code 32 and to nothing
                // else. Notably NOT to NBSP, which must not stretch under
                // justification, nor to other codes the encoding maps to a space.
                f(code, code == 32);
            }
        }
    }

    /// Map a raw character code to a CID via the `/Encoding` CMap (identity when
    /// there is no CMap, i.e. Identity-H/V or simple fonts).
    pub(crate) fn to_cid(&self, code: u32) -> u32 {
        match &self.cmap {
            Some(cm) => cm.to_cid(code),
            None => code,
        }
    }

    /// Width of `code` in text-space units (glyph units / 1000). Widths (`/W`)
    /// are keyed by CID, so the code is mapped through the CMap first.
    pub(crate) fn width(&self, code: u32) -> f64 {
        let cid = self.to_cid(code);
        self.widths.get(&cid).copied().unwrap_or(self.default_width)
    }

    pub(crate) fn push_code(&self, code: u32, out: &mut String) {
        if let Some(map) = &self.to_unicode {
            if let Some(s) = map.get(&code) {
                out.push_str(s);
                return;
            }
        }
        // Prefer the declared encoding (WinAnsi / Differences) so standard
        // punctuation is correct; fall back to the embedded cmap for symbolic
        // re-encoded subset fonts whose encoding doesn't cover the code.
        if let Some(c) = self.encoding.get(&code) {
            out.push(*c);
            return;
        }
        if let Some(c) = self.cmap_uni.get(&code) {
            out.push(*c);
            return;
        }
        // Last resort: Latin-1 for single-byte codes; best-effort otherwise.
        if let Some(c) = char::from_u32(code) {
            out.push(c);
        }
    }
}

thread_local! {
    /// Populated only inside a [`FontCacheScope`]. `None` means "no cache", which
    /// is the safe default: an entry cannot go stale if none is stored.
    static FONT_CACHE: std::cell::RefCell<Option<HashMap<(usize, ObjectId), FontInfo>>> =
        std::cell::RefCell::new(None);
}

/// Enables the [`FontInfo`] cache for the lifetime of the guard.
///
/// [`font_info`] decompresses and parses the entire embedded font program, and
/// [`fonts_from_resources`] runs on *every* `interpret_content` call — so without
/// a cache a font shared by N pages is parsed N times to render and another N
/// times to build the search index.
///
/// The key is the font dictionary's object id *paired with the identity of the
/// `Document` it came from*, and it is a *complete* key only while that document
/// is not mutated. That is precisely why the cache is scoped to one top-level
/// operation and dropped at the end instead of living in a global: a `docedit`
/// mutation between operations cannot be served a stale entry, because between
/// operations there is no cache at all. The document component means a scope that
/// happens to span two documents cannot collide on a shared object id. Fonts
/// written as direct (non-indirect) dictionaries have no object id and are never
/// cached.
///
/// Nesting is safe — only the outermost guard installs and tears down the cache —
/// so callers may create one without knowing whether an outer one exists.
pub(crate) struct FontCacheScope {
    outermost: bool,
}

impl FontCacheScope {
    pub(crate) fn new() -> Self {
        let outermost = FONT_CACHE.with(|c| {
            let mut c = c.borrow_mut();
            if c.is_none() {
                *c = Some(HashMap::new());
                true
            } else {
                false
            }
        });
        FontCacheScope { outermost }
    }
}

impl Drop for FontCacheScope {
    fn drop(&mut self) {
        if self.outermost {
            FONT_CACHE.with(|c| *c.borrow_mut() = None);
        }
    }
}

/// Build a `font resource name -> FontInfo` map from a resources dictionary.
pub(crate) fn fonts_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, FontInfo> {
    let mut fonts = HashMap::new();
    let font_dict = match res_dict.get(b"Font").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => d,
        _ => return fonts,
    };
    for (name, font_ref) in font_dict.iter() {
        // Only an indirect font can be cached: a direct dictionary has no
        // identity to key on. The document address distinguishes equal object ids
        // coming from different documents; it is stable because the document
        // outlives the scope.
        let key = match font_ref {
            Object::Reference(id) => Some((doc as *const Document as usize, *id)),
            _ => None,
        };
        if let Some(hit) = key.and_then(cached_font) {
            fonts.insert(name.clone(), hit);
            continue;
        }
        if let Some(Object::Dictionary(fd)) = deref(doc, font_ref) {
            let fi = font_info(doc, fd);
            if let Some(key) = key {
                cache_font(key, &fi);
            }
            fonts.insert(name.clone(), fi);
        }
    }
    fonts
}

fn cached_font(key: (usize, ObjectId)) -> Option<FontInfo> {
    FONT_CACHE.with(|c| c.borrow().as_ref()?.get(&key).cloned())
}

fn cache_font(key: (usize, ObjectId), fi: &FontInfo) {
    FONT_CACHE.with(|c| {
        if let Some(map) = c.borrow_mut().as_mut() {
            map.insert(key, fi.clone());
        }
    });
}

pub(crate) fn font_info(doc: &Document, font: &lopdf::Dictionary) -> FontInfo {
    let subtype = font.get(b"Subtype").ok().and_then(|o| o.as_name().ok());
    let two_byte = matches!(subtype, Some(b"Type0"));
    let is_type3 = subtype == Some(b"Type3");
    let to_unicode = font
        .get(b"ToUnicode")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| match o {
            Object::Stream(s) => Some(stream_data(s)),
            _ => None,
        })
        .map(|data| cmap::parse(&data));

    // Type0 /Encoding CMap: Identity-H/V need no code->CID map (code == CID); a
    // named predefined CMap can't be embedded here (best-effort identity), but an
    // embedded CMap stream is parsed for real code->CID mapping and WMode.
    let mut encoding_cmap: Option<cmap::EncodingCMap> = None;
    let mut cmap_wmode: u8 = 0;
    if two_byte {
        match font.get(b"Encoding").ok().and_then(|o| deref(doc, o)) {
            Some(Object::Name(n)) => {
                let name = String::from_utf8_lossy(n);
                if name.ends_with("-V") { cmap_wmode = 1; }
                // A predefined CMap cannot be embedded, so its code->CID table is
                // unavailable and CID lookups stay identity. For the mixed-width
                // families we can still install the codespace ranges, which is what
                // determines how many bytes each code consumes -- without them a
                // 1-byte ASCII code inside a Shift-JIS/GBK/Big5/UHC string is read
                // as half of a 2-byte code and the whole rest of the string
                // desynchronizes. Identity-H/V and the Uni*-UCS2-* families are
                // pure 2-byte and need no CMap at all.
                if let Some(codespace) = cmap::predefined_codespace(&name) {
                    encoding_cmap = Some(cmap::EncodingCMap {
                        codespace,
                        wmode: cmap_wmode,
                        ..Default::default()
                    });
                }
            }
            Some(Object::Stream(s)) => {
                let cm = cmap::parse_encoding_cmap(&stream_data(s));
                cmap_wmode = cm.wmode;
                encoding_cmap = Some(cm);
            }
            _ => {}
        }
    }

    // WMode: 0 horizontal (default), 1 vertical. Detect from Type0 font dict and descendant.
    let wmode: u8 = font.get(b"WMode").ok().and_then(|o| deref(doc, o)).and_then(num).map(|v| if v >= 1.0 { 1 } else { 0 }).unwrap_or(0) as u8;
    let desc_wmode: u8 = font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::Array(a) => a.first(), _ => None }).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()).and_then(|d| d.get(b"WMode").ok()).and_then(|o| deref(doc, o)).and_then(num).map(|v| if v >= 1.0 { 1 } else { 0 }).unwrap_or(wmode);
    let effective_wmode = desc_wmode.max(wmode).max(cmap_wmode);

    // CIDToGIDMap
    let cid_to_gid: Option<HashMap<u32, u16>> = {
        font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::Array(a) => a.first(), _ => None }).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()).and_then(|df| {
            match df.get(b"CIDToGIDMap").ok().and_then(|o| deref(doc, o)).or_else(|| df.get(b"CIDToGIDMap").ok()) {
                Some(Object::Stream(s)) => {
                    let data = stream_data(s);
                    let mut map = HashMap::new();
                    for (i, chunk) in data.chunks(2).enumerate() {
                        if chunk.len() < 2 { break; }
                        let gid = ((chunk[0] as u16) << 8) | chunk[1] as u16;
                        // Zeros are deliberately NOT inserted. Do not "fix" this:
                        // outlines.rs treats an explicit map as authoritative and
                        // returns .notdef for a miss, so an absent entry and a
                        // present 0 already mean the same thing (PDF 9.7.4.2). If
                        // zeros were inserted here while that lookup fell back to
                        // identity, a CID mapped to .notdef would instead select the
                        // glyph at index == cid; if they were inserted and the
                        // lookup drew GID 0, every such glyph would render as a
                        // visible hollow .notdef box.
                        if gid != 0 {
                            map.insert(i as u32, gid);
                        }
                    }
                    // A zero-length or undecodable stream must not yield an empty
                    // map: `Some` means "an explicit mapping exists", and callers
                    // treat it as authoritative. Returning `Some(empty)` would send
                    // every CID of the font to .notdef.
                    if map.is_empty() { None } else { Some(map) }
                }
                Some(Object::Name(n)) if n == b"Identity" => None, // identity = no remap
                _ => None,
            }
        })
    };

    // Type 3 glyph data (parsed before widths so the FontMatrix scale is known).
    let t3 = if is_type3 {
        type3::parse_type3_font(doc, font).map(|info| {
            let mut char_procs = HashMap::new();
            for (code, name) in info.encoding.iter() {
                if let Some(id) = info.char_procs.get(name) {
                    char_procs.insert(*code as u32, *id);
                }
            }
            Type3Font { font_matrix: info.font_matrix, char_procs, resources: info.resources }
        })
    } else {
        None
    };

    let (mut widths, default_width) = if two_byte {
        cid_widths(doc, font)
    } else if is_type3 {
        let fm_scale = t3.as_ref().map(|t| t.font_matrix[0]).unwrap_or(0.001);
        type3_widths(doc, font, fm_scale)
    } else {
        simple_widths(doc, font)
    };

    // Vertical metrics /W2 + /DW2 for WMode 1 (PDF 9.7.4.3), consumed by the
    // vertical branch of `show_string`.
    let vert_desc = font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::Array(a) => a.first(), _ => None }).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()).cloned();
    let (vert_map, default_vert): (HashMap<u32, (f64, f64)>, (f64, f64)) = {
        let mut vm = HashMap::new();
        // /DW2 is [v_y, w1_y]; the spec default is [880 -1000].
        let mut dw2 = (0.880, -1.0);
        if let Some(ref df) = vert_desc {
            if let Some(Object::Array(arr)) = df.get(b"DW2").ok().and_then(|o| deref(doc, o)) {
                let v: Vec<f64> = arr.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).collect();
                if v.len() >= 2 {
                    dw2 = (v[0] / 1000.0, v[1] / 1000.0);
                }
            }
            // /W2 has two forms:
            //   c [w1y v1x v1y  w1y v1x v1y ...]   consecutive CIDs from c
            //   cFirst cLast w1y v1x v1y           one entry for the whole range
            // Per-CID v_y is not retained (the map holds `(w1_y, v_x)` and v_y
            // comes from /DW2); a CID-specific v_y is vanishingly rare and the
            // struct shape is shared with fixtures outside this module.
            if let Some(Object::Array(w2)) = df.get(b"W2").ok().and_then(|o| deref(doc, o)) {
                let mut i = 0;
                while i < w2.len() {
                    let c0 = match w2.get(i).and_then(|o| deref(doc, o)).and_then(num) {
                        Some(v) => v.max(0.0) as u32,
                        None => break,
                    };
                    match w2.get(i + 1).and_then(|o| deref(doc, o)) {
                        Some(Object::Array(list)) => {
                            let vals: Vec<f64> =
                                list.iter().filter_map(|o| deref(doc, o).and_then(num)).collect();
                            for (j, t) in vals.chunks(3).enumerate() {
                                if t.len() < 3 { break; }
                                vm.insert(c0 + j as u32, (t[0] / 1000.0, t[1] / 1000.0));
                            }
                            i += 2;
                        }
                        _ => {
                            let c1 = w2.get(i + 1).and_then(|o| deref(doc, o)).and_then(num);
                            let w1y = w2.get(i + 2).and_then(|o| deref(doc, o)).and_then(num);
                            let v1x = w2.get(i + 3).and_then(|o| deref(doc, o)).and_then(num);
                            if let (Some(c1), Some(w1y), Some(v1x)) = (c1, w1y, v1x) {
                                let c1 = (c1.max(0.0) as u32).min(MAX_CID);
                                for cid in c0..=c1 {
                                    vm.insert(cid, (w1y / 1000.0, v1x / 1000.0));
                                }
                            }
                            i += 5;
                        }
                    }
                }
            }
        }
        (vm, dw2)
    };

    let (encoding, builtin_first) = if two_byte {
        (HashMap::new(), false)
    } else {
        encoding::build(doc, font)
    };
    let cmap_uni = if two_byte || is_type3 {
        HashMap::new()
    } else {
        let mut m = ttf_code_map(doc, font);
        // Fall back to the embedded font program's built-in encoding for
        // symbolic/subset fonts that lack /ToUnicode and a TrueType cmap.
        if m.is_empty() {
            let t1 = type1_builtin_encoding(doc, font);
            if !t1.is_empty() {
                m = t1;
            } else {
                let cff = cff_builtin_encoding(doc, font);
                if !cff.is_empty() {
                    m = cff;
                }
            }
        }
        m
    };

    // PDF 9.6.6.1 resolution order for a symbolic font with no /Encoding and no
    // /BaseEncoding: the font program's BUILT-IN encoding outranks any implicit
    // base encoding. `encoding::build` left the base empty in that case, so
    // `encoding` currently holds only /Differences (which always wins) and the
    // built-in map in `cmap_uni` is consulted next by `push_code`. WinAnsi is
    // backfilled only for codes neither covers, so a font whose symbolic flag is
    // set spuriously and whose program yields no built-in encoding still decodes.
    let mut encoding = encoding;
    if builtin_first {
        for (code, ch) in encoding::win_ansi() {
            if !encoding.contains_key(&code) && !cmap_uni.contains_key(&code) {
                encoding.insert(code, ch);
            }
        }
    }

    // --- Font style detection for bold/italic synthesis ---
    let base_font_name = font.get(b"BaseFont").ok().and_then(|o| o.as_name().ok())
        .map(|n| String::from_utf8_lossy(n).to_string())
        .or_else(|| {
            // Try descendant for Type0
            font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o))
                .and_then(|o| match o { Object::Array(a) => a.first(), _ => None })
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_dict().ok())
                .and_then(|d| d.get(b"BaseFont").ok())
                .and_then(|o| o.as_name().ok())
                .map(|n| String::from_utf8_lossy(n).to_string())
        })
        .unwrap_or_default();

    let fd = font.get(b"FontDescriptor").ok().and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok()).cloned()
        .or_else(|| {
            // For Type0, try descendant's FontDescriptor
            font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o))
                .and_then(|o| match o { Object::Array(a) => a.first(), _ => None })
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_dict().ok())
                .and_then(|d| d.get(b"FontDescriptor").ok())
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_dict().ok())
                .cloned()
        });

    let mut bold = false;
    let mut italic = false;
    let lower = base_font_name.to_lowercase();
    if lower.contains("bold") || lower.contains("black") || lower.contains("heavy") {
        bold = true;
    }
    if lower.contains("italic") || lower.contains("oblique") || lower.contains("slanted") {
        italic = true;
    }
    if let Some(ref desc) = fd {
        if let Some(flags) = desc.get(b"Flags").ok().and_then(|o| deref(doc, o)).and_then(num) {
            let f = flags as i64;
            // Bit 18 (1<<18 = 262144) = Italic per PDF spec 9.8.2
            if f & (1<<18) != 0 || f & 64 != 0 { italic = true; } // 64 is common non-spec but some generators
            // There is no bold flag but some files use bit 6? Actually force bold is 18? We'll rely on StemV/Weight
        }
        if let Some(angle) = desc.get(b"ItalicAngle").ok().and_then(|o| deref(doc, o)).and_then(num) {
            if angle.abs() > 0.5 { italic = true; }
        }
        if let Some(weight) = desc.get(b"FontWeight").ok().and_then(|o| deref(doc, o)).and_then(num) {
            if weight >= 600.0 { bold = true; }
        } else if let Some(name) = desc.get(b"FontWeight").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_name().ok()) {
            if String::from_utf8_lossy(name).to_lowercase().contains("bold") { bold = true; }
        }
        if let Some(stemv) = desc.get(b"StemV").ok().and_then(|o| deref(doc, o)).and_then(num) {
            if stemv.abs() > 140.0 { bold = true; }
        }
        if desc.get(b"FontName").ok().and_then(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).to_lowercase().contains("bold")).unwrap_or(false) { bold = true; }
    }

    // --- Embedded glyph outline program (real font rendering) ---
    // Type 3 fonts draw via CharProc streams, not outline programs.
    let glyph_program = if is_type3 {
        None
    } else {
        crate::outlines::build_glyph_program(doc, fd.as_ref())
    };
    let glyph_names = if two_byte || is_type3 {
        HashMap::new()
    } else {
        crate::outlines::encoding_differences(doc, font)
    };

    // --- Standard-14 metrics fallback ---
    // A non-embedded standard font (Helvetica/Times/Courier/Symbol/ZapfDingbats)
    // may omit /Widths; use the Core-14 AFM widths (keyed by glyph name) so text
    // is spaced correctly instead of at a flat 0.5 em. Resolve code -> glyph name
    // via /Differences, falling back to StandardEncoding.
    if !two_byte && !is_type3 && widths.is_empty() {
        if let Some(afm) = crate::afm::standard_14_widths(&base_font_name) {
            for code in 0u32..=255 {
                let name = glyph_names.get(&code).cloned().or_else(|| {
                    crate::type1::STANDARD_ENCODING.iter()
                        .find(|(c, _)| *c as u32 == code)
                        .map(|(_, n)| (*n).to_string())
                });
                if let Some(name) = name {
                    if let Some(w) = afm.get(&name) {
                        widths.insert(code, *w);
                    }
                }
            }
        }
    }

    // --- Generic family detection for substitute shaping (0 sans, 1 serif, 2 mono) ---
    // The embedded base font is not rendered directly; Kotlin picks a matching
    // system typeface, so we only need the broad family. BaseFont names (including
    // subset prefixes like `BCFRDE+Times-Roman`) give the strongest signal; the
    // FontDescriptor `/Flags` (PDF 9.8.2, Table 121: bit 1 FixedPitch, bit 2 Serif)
    // is authoritative when the name is generic.
    let is_mono_name = lower.contains("courier") || lower.contains("mono") || lower.contains("consol");
    let is_sans_name = lower.contains("arial") || lower.contains("helvetica")
        || lower.contains("verdana") || lower.contains("tahoma") || lower.contains("calibri")
        || lower.contains("segoe") || lower.contains("sans");
    let is_serif_name = !is_sans_name && (lower.contains("times") || lower.contains("georgia")
        || lower.contains("garamond") || lower.contains("minion") || lower.contains("palatino")
        || lower.contains("cambria") || lower.contains("antiqua") || lower.contains("serif")
        || lower.contains("roman"));
    let mut family: u8 = if is_mono_name { 2 } else if is_serif_name { 1 } else { 0 };
    if let Some(ref desc) = fd {
        if let Some(flags) = desc.get(b"Flags").ok().and_then(|o| deref(doc, o)).and_then(num) {
            let f = flags as i64;
            if f & 1 != 0 {
                family = 2; // FixedPitch -> monospace
            } else if f & 2 != 0 && !is_sans_name && !is_mono_name {
                family = 1; // Serif
            }
        }
    }

    FontInfo {
        two_byte,
        wmode: effective_wmode,
        vertical_metrics: vert_map,
        default_vertical: default_vert,
        cid_to_gid,
        to_unicode: to_unicode.map(Arc::new),
        encoding,
        cmap_uni,
        cmap: encoding_cmap,
        widths,
        default_width,
        t3,
        style: FontStyle { bold, italic },
        family,
        base_font: base_font_name,
        glyph_program: glyph_program.map(Arc::new),
        glyph_names,
    }
}

/// Widths for a Type 3 font: `/Widths` values are in glyph space and are scaled
/// to text space by the FontMatrix x-scale (rather than the /1000 used for
/// simple fonts).
fn type3_widths(doc: &Document, font: &lopdf::Dictionary, fm_scale: f64) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    let first_char = font.get(b"FirstChar").ok().and_then(|o| deref(doc, o)).and_then(num).unwrap_or(0.0) as u32;
    if let Some(Object::Array(arr)) = font.get(b"Widths").ok().and_then(|o| deref(doc, o)) {
        for (i, w) in arr.iter().enumerate() {
            if let Some(w) = deref(doc, w).and_then(num) {
                widths.insert(first_char + i as u32, w * fm_scale);
            }
        }
    }
    (widths, 0.0)
}

/// Widths for a simple (1-byte) font from `/Widths` + `/FirstChar`, with the
/// `/FontDescriptor /MissingWidth` fallback. Values are glyph units / 1000.
pub(crate) fn simple_widths(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    // `/FirstChar` may be an indirect reference; `num` does not dereference, so
    // without the `deref` the whole width table silently shifts by FirstChar.
    let first_char = font
        .get(b"FirstChar")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(0.0) as u32;
    if let Some(Object::Array(arr)) = font.get(b"Widths").ok().and_then(|o| deref(doc, o)) {
        for (i, w) in arr.iter().enumerate() {
            if let Some(w) = deref(doc, w).and_then(num) {
                widths.insert(first_char + i as u32, w / 1000.0);
            }
        }
    }
    let missing = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"MissingWidth").ok())
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(0.0)
        / 1000.0;
    // Simple fonts without a /Widths array (e.g. the standard 14) get a
    // reasonable default so advances are non-degenerate.
    let default_width = if widths.is_empty() { 0.5 } else { missing };
    (widths, default_width)
}

/// Widths for a Type0/CID font from the descendant font's `/W` array + `/DW`.
/// The map is keyed by CID (== 2-byte code for Identity-H). Units glyph/1000.
pub(crate) fn cid_widths(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    let mut default_width = 1.0; // /DW default is 1000 glyph units.

    let descendant = font
        .get(b"DescendantFonts")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| match o {
            Object::Array(a) => a.first(),
            _ => None,
        })
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok());

    let df = match descendant {
        Some(d) => d,
        None => return (widths, default_width),
    };

    if let Some(dw) = df.get(b"DW").ok().and_then(|o| deref(doc, o)).and_then(num) {
        default_width = dw / 1000.0;
    }

    // /W: [ c [w1 w2 ...]  cFirst cLast w  ... ]
    if let Some(Object::Array(w)) = df.get(b"W").ok().and_then(|o| deref(doc, o)) {
        let mut i = 0;
        while i < w.len() {
            let c = match deref(doc, &w[i]).and_then(num) {
                Some(v) => v as u32,
                None => break,
            };
            match w.get(i + 1).and_then(|o| deref(doc, o)) {
                Some(Object::Array(list)) => {
                    for (j, item) in list.iter().enumerate() {
                        if let Some(v) = deref(doc, item).and_then(num) {
                            widths.insert(c + j as u32, v / 1000.0);
                        }
                    }
                    i += 2;
                }
                _ => {
                    let c_last = w.get(i + 1).and_then(|o| deref(doc, o)).and_then(num);
                    let width = w.get(i + 2).and_then(|o| deref(doc, o)).and_then(num);
                    if let (Some(c_last), Some(width)) = (c_last, width) {
                        // CIDs are bounded at 65535 (PDF 9.7.4.3), so a corrupt or
                        // hostile `cLast` cannot drive a multi-billion-iteration loop.
                        let c_last = (c_last.max(0.0) as u32).min(MAX_CID);
                        for cid in c..=c_last {
                            widths.insert(cid, width / 1000.0);
                        }
                    }
                    i += 3;
                }
            }
        }
    }
    (widths, default_width)
}

/// Build a `code -> unicode char` map from an embedded simple TrueType font's
/// `/FontFile2` cmap, used to recover text from re-encoded subset fonts that
/// lack a `/ToUnicode` map. Empty if unavailable.
pub(crate) fn ttf_code_map(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
    let ff = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"FontFile2").ok())
        .and_then(|o| deref(doc, o));
    match ff {
        Some(Object::Stream(s)) => ttf::code_to_unicode(&stream_data(s)),
        _ => HashMap::new(),
    }
}

/// Recover a Type 1 (`/FontFile`) font's built-in `/Encoding` array by scanning
/// the clear-text (pre-`eexec`) portion for `dup <code> /<name> put` entries and
/// mapping glyph names to Unicode. Empty if the font has no `/FontFile`.
pub(crate) fn type1_builtin_encoding(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
    let ff = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"FontFile").ok())
        .and_then(|o| deref(doc, o));
    let stream = match ff {
        Some(Object::Stream(s)) => s,
        _ => return HashMap::new(),
    };
    let data = stream_data(stream);
    // Only the clear-text segment (`/Length1` bytes, or up to `eexec`) holds the
    // /Encoding array in ASCII.
    let len1 = stream
        .dict
        .get(b"Length1")
        .ok()
        .and_then(num)
        .map(|v| v as usize)
        .unwrap_or(data.len());
    let end = len1.min(data.len());
    let text = &data[..end];
    parse_type1_encoding_text(text)
}

/// Scan `dup <code> /<name> put` records from a Type 1 clear-text segment.
fn parse_type1_encoding_text(bytes: &[u8]) -> HashMap<u32, char> {
    let mut map = HashMap::new();
    let s = String::from_utf8_lossy(bytes);
    for line in s.split(['\n', '\r']) {
        // Tokens: dup <code> /<name> put
        let mut it = line.split_whitespace();
        loop {
            match it.next() {
                Some("dup") => {}
                Some(_) => continue,
                None => break,
            }
            let code = match it.next().and_then(|t| t.parse::<u32>().ok()) {
                Some(c) => c,
                None => break,
            };
            let name_tok = match it.next() {
                Some(t) if t.starts_with('/') => &t[1..],
                _ => break,
            };
            if it.next() == Some("put") {
                if let Some(c) = encoding::glyph_to_char(name_tok) {
                    map.insert(code, c);
                }
            }
            break;
        }
    }
    map
}

/// Recover a CFF (`/FontFile3`) font's built-in encoding as `code -> Unicode`
/// via its Encoding + charset. Empty on parse failure or for CIDFont CFF (which
/// is code-mapped through a CMap, not the CFF encoding).
pub(crate) fn cff_builtin_encoding(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
    let ff = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"FontFile3").ok())
        .and_then(|o| deref(doc, o));
    let stream = match ff {
        Some(Object::Stream(s)) => s,
        _ => return HashMap::new(),
    };
    cff::builtin_encoding(&stream_data(stream))
}

/// Minimal TrueType `cmap` parser: recovers a character-code → Unicode map by
/// composing a code→glyph subtable (Mac 1,0 or Symbol 3,0) with the reverse of
/// a Unicode subtable (3,1 / 0,3 / 3,10). All reads are bounds-checked so
/// malformed font data can never panic.
pub(crate) mod ttf {
    use std::collections::HashMap;

    fn u16b(b: &[u8], o: usize) -> u16 {
        ((*b.get(o).unwrap_or(&0) as u16) << 8) | *b.get(o + 1).unwrap_or(&0) as u16
    }
    fn u32b(b: &[u8], o: usize) -> u32 {
        ((u16b(b, o) as u32) << 16) | u16b(b, o + 2) as u32
    }

    /// Group count for the range-based cmap subtable formats, clamped to the
    /// groups that actually fit in `b`. Out-of-bounds reads return 0 rather than
    /// failing, so an unclamped count from corrupt data would otherwise spin for
    /// billions of iterations appending junk entries.
    fn group_count(b: &[u8], count_off: usize, groups_off: usize, group_size: usize) -> usize {
        let declared = u32b(b, count_off) as usize;
        let fits = b.len().saturating_sub(groups_off) / group_size;
        declared.min(fits)
    }

    fn table_offset(b: &[u8], tag: &[u8; 4]) -> Option<usize> {
        let num = u16b(b, 4) as usize;
        for i in 0..num {
            let rec = 12 + i * 16;
            if b.get(rec..rec + 4)? == tag {
                return Some(u32b(b, rec + 8) as usize);
            }
        }
        None
    }

    /// Upper bound on the pairs one `cmap` subtable may yield. Formats 8, 12 and
    /// 13 are group lists where each group expands to a code RANGE, so the
    /// existing per-group clamps (group count x 65536 codes each) still multiply
    /// out to billions of entries for a subtable that is only a few KB on disk.
    /// A Unicode-complete cmap needs ~0x110000 pairs, so this cannot truncate a
    /// legitimate font.
    const MAX_CMAP_PAIRS: usize = 0x20_0000;

    /// Parse a subtable at `off` into (code, glyphId) pairs.
    fn parse_subtable(b: &[u8], off: usize) -> Vec<(u32, u16)> {
        let mut out = Vec::new();
        let fmt = u16b(b, off);
        match fmt {
            0 => {
                // Byte encoding: 256 single-byte glyph ids.
                for c in 0..256u32 {
                    let g = *b.get(off + 6 + c as usize).unwrap_or(&0) as u16;
                    if g != 0 {
                        out.push((c, g));
                    }
                }
            }
            2 => {
                // Format 2 (CJK high-byte): sparse subHeaders + maps.
                // Structure: [format,u16][length,u16][lang,u16][subHeaderKeys 256×u16][subHeaders][glyphIndexArray]
                // Each subHeaderKey is idx*8 of subHeader, or 0 if single-byte. SubHeader: firstCode,reserved,entryCount,delta (i16),rangeOffset.
                // Bounds-check heavily — exotic.
                if b.len() < off + 6 || off + 6 > b.len() {
                    return out;
                }
                let sub_keys_off = off + 6;
                if sub_keys_off + 512 > b.len() {
                    return out;
                }
                // Pre-calc max subHeader idx from keys
                let mut max_key = 0usize;
                for k in 0..256 {
                    let v = u16b(b, sub_keys_off + k * 2) as usize;
                    if v / 8 > max_key {
                        max_key = v / 8;
                    }
                }
                let sub_header_off = sub_keys_off + 512;
                // GlyphIndexArray follows subHeaders: need to estimate
                let ghi_off = sub_header_off + (max_key + 1) * 8;
                if ghi_off > b.len() {
                    return out;
                }
                for sbyte in 0u32..256 {
                    let key_raw = u16b(b, sub_keys_off + sbyte as usize * 2) as usize;
                    let sh_idx = key_raw / 8;
                    if sh_idx == 0 {
                        // Single-byte code maps via one entry
                        let sh_off = sub_header_off + sh_idx * 8;
                        if sh_off + 8 > b.len() {
                            continue;
                        }
                        let first = u16b(b, sh_off) as u32;
                        // Only attempt when high byte matches etc — best-effort
                        // For format2, single-byte glyphs: range 0x00..0xFF
                        if sbyte == first {
                            let range_off = u16b(b, sh_off + 6) as usize;
                            let glyph: u16 = if range_off == 0 {
                                let delta = u16b(b, sh_off + 4) as i16;
                                (sbyte as i16 + delta) as u16
                            } else {
                                let addr = ghi_off + range_off;
                                u16b(b, addr)
                            };
                            if glyph != 0 {
                                out.push((sbyte, glyph));
                            }
                        }
                    }
                }
                // Two-byte sequence handling simplified: high byte groups
                for hi in 0u32..256 {
                    let key_raw = u16b(b, sub_keys_off + hi as usize * 2) as usize;
                    let sh_idx = key_raw / 8;
                    if sh_idx == 0 {
                        continue;
                    }
                    let sh_off = sub_header_off + sh_idx * 8;
                    if sh_off + 8 > b.len() {
                        continue;
                    }
                    let first_code = u16b(b, sh_off) as u32;
                    let entry_count = u16b(b, sh_off + 2) as u32;
                    let delta = u16b(b, sh_off + 4) as i16;
                    let range_off = u16b(b, sh_off + 6) as usize;
                    for low in 0u32..entry_count.min(256) {
                        let code = (hi << 8) | (first_code + low);
                        let gid = if range_off == 0 {
                            ((first_code + low) as i16 + delta) as u16
                        } else {
                            let addr = sub_header_off + sh_idx * 8 + 6 + range_off + (low as usize * 2);
                            u16b(b, addr)
                        };
                        if gid != 0 {
                            out.push((code, gid));
                        }
                    }
                }
            }
            6 => {
                let first = u16b(b, off + 6) as u32;
                let count = u16b(b, off + 8) as usize;
                for i in 0..count {
                    let g = u16b(b, off + 10 + i * 2);
                    if g != 0 {
                        out.push((first + i as u32, g));
                    }
                }
            }
            4 => {
                let segx2 = u16b(b, off + 6) as usize;
                let seg = segx2 / 2;
                let end_o = off + 14;
                let start_o = end_o + segx2 + 2;
                let delta_o = start_o + segx2;
                let range_o = delta_o + segx2;
                for i in 0..seg {
                    let end = u16b(b, end_o + i * 2);
                    let start = u16b(b, start_o + i * 2);
                    let delta = u16b(b, delta_o + i * 2);
                    let range = u16b(b, range_o + i * 2);
                    if start > end {
                        continue;
                    }
                    for c in start..=end {
                        if c == 0xFFFF {
                            break;
                        }
                        let gid = if range == 0 {
                            c.wrapping_add(delta)
                        } else {
                            let addr = range_o + i * 2 + range as usize + 2 * (c - start) as usize;
                            let g = u16b(b, addr);
                            if g == 0 {
                                0
                            } else {
                                g.wrapping_add(delta)
                            }
                        };
                        if gid != 0 {
                            out.push((c as u32, gid));
                        }
                    }
                }
            }
            8 => {
                // Format 8: mixed 16/32 coverage. Guarded best-effort.
                // [format 8][reserved][length u32][lang u32][is32 array 8192 bytes][nGroups u32][groups...] groups are [start,end,gid]
                if b.len() < off + 12 {
                    return out;
                }
                let length = u32b(b, off + 2) as usize;
                if off + length > b.len() || length < 8200 {
                    return out;
                }
                // After is32 bitmap (8192 bytes) at off+12, nGroups at off+8204
                let ngroups_off = off + 12 + 8192;
                if ngroups_off + 4 > b.len() {
                    return out;
                }
                let ngroups = u32b(b, ngroups_off) as usize;
                let groups_off = ngroups_off + 4;
                for g in 0..ngroups.min(100_000) {
                    let go = groups_off + g * 12;
                    if go + 12 > b.len() || out.len() >= MAX_CMAP_PAIRS {
                        break;
                    }
                    let sc = u32b(b, go);
                    let ec = u32b(b, go + 4);
                    let sg = u32b(b, go + 8) as u16;
                    if sc > ec || ec - sc > 65535 || sg == 0 {
                        continue;
                    }
                    for c in sc..=ec {
                        out.push((c, (sg as u32 + (c - sc)) as u16));
                    }
                }
            }
            10 => {
                // Trimmed array (like format 6 but 32-bit code space).
                let first = u32b(b, off + 12);
                let count = u32b(b, off + 16) as usize;
                for i in 0..count.min(0x20000) {
                    let g = u16b(b, off + 20 + i * 2);
                    if g != 0 {
                        out.push((first + i as u32, g));
                    }
                }
            }
            12 => {
                let ngroups = group_count(b, off + 12, off + 16, 12);
                for i in 0..ngroups {
                    if out.len() >= MAX_CMAP_PAIRS {
                        break;
                    }
                    let g = off + 16 + i * 12;
                    let sc = u32b(b, g);
                    let ec = u32b(b, g + 4);
                    let sg = u32b(b, g + 8);
                    if sc > ec || ec - sc > 65535 {
                        continue;
                    }
                    for c in sc..=ec {
                        out.push((c, (sg + (c - sc)) as u16));
                    }
                }
            }
            13 => {
                // Many-to-one range mappings: every code in a group maps to the
                // same glyph (used for e.g. "last resort" fonts).
                let ngroups = group_count(b, off + 12, off + 16, 12);
                for i in 0..ngroups {
                    if out.len() >= MAX_CMAP_PAIRS {
                        break;
                    }
                    let g = off + 16 + i * 12;
                    let sc = u32b(b, g);
                    let ec = u32b(b, g + 4);
                    let gid = u32b(b, g + 8) as u16;
                    if sc > ec || ec - sc > 65535 || gid == 0 {
                        continue;
                    }
                    for c in sc..=ec {
                        out.push((c, gid));
                    }
                }
            }
            14 => {
                // Format 14: variation selectors — produces no direct code->gid mapping
                // for basic text extraction; skip but parse best-effort: if present,
                // treat first 3 tables? For extraction we ignore selectors and only
                // map base unicode via defaultUVS -> uVS. The cmap recovery composes
                // code->glyph and gid->uni anyway; variation tables provide alt uni for
                // <base, selector>. We produce base uni mapping ignoring selector for now.
                // Parse top [format 2byte][length 4][numVarSelectorRecords 4]
                if b.len() < off + 10 {
                    return out;
                }
                let num_recs = u32b(b, off + 6) as usize;
                // Each record: varSelector 3 byte, defaultUVS off 4, nonDefault off 4.
                // If defaultUVS non-zero, it contains ranges mapping base unicode -> selector maps to default glyph.
                // This logic is complex, for robustness we only handle defaultUVS path to map base uni to default glyph
                for i in 0..num_recs.min(1000) {
                    let rec_off = off + 10 + i * 11;
                    if rec_off + 11 > b.len() {
                        break;
                    }
                    let default_off = u32b(b, rec_off + 3) as usize;
                    if default_off != 0 {
                        let base_rec = off + default_off;
                        if base_rec + 4 > b.len() {
                            continue;
                        }
                        let num_ranges = u32b(b, base_rec) as usize;
                        for r in 0..num_ranges.min(10_000) {
                            let ro = base_rec + 4 + r * 4;
                            if ro + 4 > b.len() {
                                break;
                            }
                            let start = (b[ro] as u32) << 16 | u16b(b, ro + 1) as u32;
                            let addl = b[ro + 3] as u32;
                            for u in start..=start + addl {
                                out.push((u, 0)); // marker, will be filtered via uni mapping fallback?
                            }
                        }
                    }
                }
                // No gid mapping for format 14; fallback to other subtable
            }
            _ => {}
        }
        out
    }

    /// `gid -> unicode` recovered from the `post` table's glyph names via the
    /// Adobe Glyph List. Only used when the font has no Unicode `cmap` subtable.
    /// Parsing is delegated to `ttf-parser` (the `glyph-names` feature) rather
    /// than hand-rolling another untrusted-binary reader.
    fn gid_names_to_unicode(b: &[u8]) -> HashMap<u16, u32> {
        let mut m = HashMap::new();
        let face = match ttf_parser::Face::parse(b, 0) {
            Ok(f) => f,
            Err(_) => return m,
        };
        for gid in 0..face.number_of_glyphs() {
            if let Some(name) = face.glyph_name(ttf_parser::GlyphId(gid)) {
                if let Some(c) = super::encoding::glyph_to_char(name) {
                    m.insert(gid, c as u32);
                }
            }
        }
        m
    }

    pub fn code_to_unicode(b: &[u8]) -> HashMap<u32, char> {
        let mut result = HashMap::new();
        let cmap = match table_offset(b, b"cmap") {
            Some(o) => o,
            None => return result,
        };
        let n = u16b(b, cmap + 2) as usize;

        let mut uni_sub: Option<usize> = None;
        let mut mac_sub: Option<usize> = None;
        let mut sym_sub: Option<usize> = None;
        for i in 0..n {
            let r = cmap + 4 + i * 8;
            let pid = u16b(b, r);
            let eid = u16b(b, r + 2);
            let so = cmap + u32b(b, r + 4) as usize;
            match (pid, eid) {
                (3, 1) | (0, 3) | (3, 10) | (0, 4) => uni_sub = Some(so),
                (1, 0) => mac_sub = Some(so),
                (3, 0) => sym_sub = Some(so),
                _ => {}
            }
        }

        // glyph -> unicode (from the Unicode subtable).
        let gid_to_uni: HashMap<u16, u32> = match uni_sub {
            Some(o) => {
                let mut m = HashMap::new();
                for (uni, gid) in parse_subtable(b, o) {
                    m.entry(gid).or_insert(uni);
                }
                m
            }
            // A symbolic font may carry only a (3,0) Symbol and/or (1,0)
            // Macintosh subtable and no Unicode subtable at all. Bailing here left
            // the whole map empty, so such a font contributed nothing to selection
            // or search even though its glyph names say exactly what the glyphs
            // are. Recover `gid -> unicode` from the `post` table's glyph names
            // through the Adobe Glyph List: names are an authoritative Unicode
            // source, unlike guessing Unicode from the raw character code, which
            // is what would actually pollute the text index.
            None => gid_names_to_unicode(b),
        };
        if gid_to_uni.is_empty() {
            return result;
        }

        // code -> glyph (from Symbol and/or Mac subtables), then -> unicode.
        // PDF 9.6.6.4: a symbolic TrueType font is looked up through the (3,0)
        // Microsoft Symbol subtable in preference to (1,0) Macintosh, and the
        // presence of a (3,0) table is itself the strongest symbolic signal we
        // have here. `or_insert` makes the first source win, so (3,0) leads.
        for sub in [sym_sub, mac_sub].into_iter().flatten() {
            for (code, gid) in parse_subtable(b, sub) {
                if let Some(&uni) = gid_to_uni.get(&gid) {
                    if let Some(c) = char::from_u32(uni) {
                        result.entry(code).or_insert(c);
                        // Symbol (3,0) codes are often mapped at 0xF000+code.
                        if code >= 0xF000 {
                            result.entry(code - 0xF000).or_insert(c);
                        }
                    }
                }
            }
        }
        result
    }

    #[cfg(test)]
    mod tests {
        use super::parse_subtable;

        fn be16(v: u16) -> [u8; 2] { v.to_be_bytes() }
        fn be32(v: u32) -> [u8; 4] { v.to_be_bytes() }

        #[test]
        fn format13_maps_range_to_single_glyph() {
            let mut b = Vec::new();
            b.extend_from_slice(&be16(13));      // format
            b.extend_from_slice(&be16(0));       // reserved
            b.extend_from_slice(&be32(0));       // length
            b.extend_from_slice(&be32(0));       // language
            b.extend_from_slice(&be32(1));       // nGroups
            b.extend_from_slice(&be32(0x41));    // startChar
            b.extend_from_slice(&be32(0x43));    // endChar
            b.extend_from_slice(&be32(5));       // glyphID
            let pairs = parse_subtable(&b, 0);
            assert!(pairs.contains(&(0x41, 5)));
            assert!(pairs.contains(&(0x42, 5)));
            assert!(pairs.contains(&(0x43, 5)));
        }

        #[test]
        fn format10_trimmed_array() {
            let mut b = Vec::new();
            b.extend_from_slice(&be16(10));      // format
            b.extend_from_slice(&be16(0));       // reserved
            b.extend_from_slice(&be32(0));       // length
            b.extend_from_slice(&be32(0));       // language
            b.extend_from_slice(&be32(0x41));    // startCharCode
            b.extend_from_slice(&be32(2));       // numChars
            b.extend_from_slice(&be16(7));       // glyph for 0x41
            b.extend_from_slice(&be16(8));       // glyph for 0x42
            let pairs = parse_subtable(&b, 0);
            assert!(pairs.contains(&(0x41, 7)));
            assert!(pairs.contains(&(0x42, 8)));
        }
    }
}

// ---------------------------------------------------------------------------
// Simple-font encodings (base encoding + /Differences)
// ---------------------------------------------------------------------------

pub(crate) mod encoding {
    use super::{deref, num, Object};
    use lopdf::Document;
    use std::collections::HashMap;

    /// Build a `code -> unicode char` map for a simple font: start from the base
    /// encoding (WinAnsi / MacRoman / Standard, or Symbol / ZapfDingbats for
    /// those base fonts), then apply any `/Encoding /Differences`.
    ///
    /// Returns `(map, builtin_first)`. `builtin_first` is true when the font is
    /// symbolic AND declares neither `/Encoding` nor `/BaseEncoding`: PDF 9.6.6.1
    /// gives the font program's built-in encoding priority there, so the base is
    /// left empty and the caller layers the built-in map underneath /Differences.
    pub fn build(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, char>, bool) {
        let base_font = font
            .get(b"BaseFont")
            .ok()
            .and_then(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned())
            .unwrap_or_default();

        let enc_obj = font.get(b"Encoding").ok().and_then(|o| deref(doc, o));
        let mut builtin_first = false;
        let base_name = match &enc_obj {
            Some(Object::Name(n)) => Some(String::from_utf8_lossy(n).into_owned()),
            Some(Object::Dictionary(d)) => d
                .get(b"BaseEncoding")
                .ok()
                .and_then(|o| o.as_name().ok())
                .map(|n| String::from_utf8_lossy(n).into_owned()),
            _ => None,
        };

        let mut map = if base_font.contains("Symbol") {
            symbol_table()
        } else if base_font.contains("ZapfDingbats") || base_font.contains("Dingbats") {
            zapf_table()
        } else if base_name.is_none() && is_symbolic(doc, font) {
            // Built-in encoding takes priority (PDF 9.6.6.1); the caller layers it
            // in. Only /Differences belongs in this map.
            builtin_first = true;
            HashMap::new()
        } else {
            match base_name.as_deref() {
                Some("WinAnsiEncoding") => win_ansi(),
                Some("MacRomanEncoding") => crate::glyphlist::mac_roman(),
                Some("StandardEncoding") => standard(),
                Some("Symbol") => symbol_table(),
                Some("ZapfDingbats") => zapf_table(),
                // Default base encoding for most simple fonts is Standard, but
                // WinAnsi is the safest superset for modern PDFs.
                _ => win_ansi(),
            }
        };

        // Apply /Differences: [ code /name /name code /name ... ].
        if let Some(Object::Dictionary(d)) = &enc_obj {
            if let Some(Object::Array(diffs)) = d.get(b"Differences").ok().and_then(|o| deref(doc, o))
            {
                let mut code = 0u32;
                for item in diffs {
                    match item {
                        Object::Integer(_) | Object::Real(_) => {
                            // Clamp negatives to 0 to match outlines.rs's
                            // /Differences parser, which does `n.max(0)`.
                            code = num(item).unwrap_or(0.0).max(0.0) as u32;
                        }
                        Object::Name(name) => {
                            if let Some(c) = glyph_to_char(&String::from_utf8_lossy(name)) {
                                map.insert(code, c);
                            }
                            code += 1;
                        }
                        _ => {}
                    }
                }
            }
        }
        (map, builtin_first)
    }

    /// FontDescriptor `/Flags` bit 3 (value 4) = Symbolic (PDF 9.8.2, Table 121).
    fn is_symbolic(doc: &Document, font: &lopdf::Dictionary) -> bool {
        font.get(b"FontDescriptor")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .and_then(|d| d.get(b"Flags").ok())
            .and_then(|o| deref(doc, o))
            .and_then(num)
            .map(|f| (f as i64) & 4 != 0)
            .unwrap_or(false)
    }

    /// Resolve an Adobe glyph name to a Unicode scalar. Handles `uniXXXX`,
    /// `uXXXXXX`, the Adobe Glyph List (standard Latin/Greek/symbol names),
    /// single-character names, and named digits/letters.
    pub fn glyph_to_char(name: &str) -> Option<char> {
        // Strip a font-specific suffix like "name.sc" / "name.alt".
        let base = name.split('.').next().unwrap_or(name);
        if let Some(hex) = base.strip_prefix("uni") {
            if hex.len() >= 4 {
                if let Ok(cp) = u32::from_str_radix(&hex[..4], 16) {
                    return char::from_u32(cp);
                }
            }
        }
        if base.starts_with('u') && base.len() >= 5 && base.len() <= 7 {
            if let Ok(cp) = u32::from_str_radix(&base[1..], 16) {
                if let Some(c) = char::from_u32(cp) {
                    return Some(c);
                }
            }
        }
        // Adobe Glyph List (standard names).
        if let Some(c) = crate::glyphlist::agl(base) {
            return Some(c);
        }
        if let Some(c) = curated(base) {
            return Some(c);
        }
        // Single-character glyph name (e.g. "A", "a", "1").
        let mut chars = base.chars();
        if let (Some(c), None) = (chars.next(), chars.clone().next()) {
            return Some(c);
        }
        None
    }

    fn curated(name: &str) -> Option<char> {
        let c = match name {
            "space" | "nbspace" => ' ',
            "bullet" => '\u{2022}',
            "periodcentered" => '\u{00B7}',
            "endash" => '\u{2013}',
            "emdash" => '\u{2014}',
            "hyphen" | "sfthyphen" => '-',
            "quoteleft" => '\u{2018}',
            "quoteright" => '\u{2019}',
            "quotedblleft" => '\u{201C}',
            "quotedblright" => '\u{201D}',
            "quotesingle" => '\'',
            "quotedbl" => '"',
            "comma" => ',',
            "period" => '.',
            "colon" => ':',
            "semicolon" => ';',
            "slash" => '/',
            "backslash" => '\\',
            "asterisk" => '*',
            "ampersand" => '&',
            "at" => '@',
            "numbersign" => '#',
            "percent" => '%',
            "dollar" => '$',
            "cent" => '\u{00A2}',
            "sterling" => '\u{00A3}',
            "euro" => '\u{20AC}',
            "yen" => '\u{00A5}',
            "trademark" => '\u{2122}',
            "registered" => '\u{00AE}',
            "copyright" => '\u{00A9}',
            "degree" => '\u{00B0}',
            "plusminus" => '\u{00B1}',
            "multiply" => '\u{00D7}',
            "divide" => '\u{00F7}',
            "ellipsis" => '\u{2026}',
            "dagger" => '\u{2020}',
            "daggerdbl" => '\u{2021}',
            "paragraph" => '\u{00B6}',
            "section" => '\u{00A7}',
            "fi" => '\u{FB01}',
            "fl" => '\u{FB02}',
            "exclam" => '!',
            "question" => '?',
            "parenleft" => '(',
            "parenright" => ')',
            "bracketleft" => '[',
            "bracketright" => ']',
            "braceleft" => '{',
            "braceright" => '}',
            "less" => '<',
            "greater" => '>',
            "equal" => '=',
            "plus" => '+',
            "minus" => '\u{2212}',
            "underscore" => '_',
            "hyphenminus" => '-',
            "arrowright" => '\u{2192}',
            "arrowleft" => '\u{2190}',
            "arrowup" => '\u{2191}',
            "arrowdown" => '\u{2193}',
            "zero" => '0',
            "one" => '1',
            "two" => '2',
            "three" => '3',
            "four" => '4',
            "five" => '5',
            "six" => '6',
            "seven" => '7',
            "eight" => '8',
            "nine" => '9',
            _ => return None,
        };
        Some(c)
    }

    /// WinAnsiEncoding (CP1252): Latin-1 with the 0x80–0x9F range remapped.
    pub fn win_ansi() -> HashMap<u32, char> {
        let mut m = latin1();
        let overrides: [(u32, u32); 27] = [
            (0x80, 0x20AC),
            (0x82, 0x201A),
            (0x83, 0x0192),
            (0x84, 0x201E),
            (0x85, 0x2026),
            (0x86, 0x2020),
            (0x87, 0x2021),
            (0x88, 0x02C6),
            (0x89, 0x2030),
            (0x8A, 0x0160),
            (0x8B, 0x2039),
            (0x8C, 0x0152),
            (0x8E, 0x017D),
            (0x91, 0x2018),
            (0x92, 0x2019),
            (0x93, 0x201C),
            (0x94, 0x201D),
            (0x95, 0x2022),
            (0x96, 0x2013),
            (0x97, 0x2014),
            (0x98, 0x02DC),
            (0x99, 0x2122),
            (0x9A, 0x0161),
            (0x9B, 0x203A),
            (0x9C, 0x0153),
            (0x9E, 0x017E),
            (0x9F, 0x0178),
        ];
        for (code, cp) in overrides {
            if let Some(c) = char::from_u32(cp) {
                m.insert(code, c);
            }
        }
        m
    }

    /// Adobe StandardEncoding: matches Latin-1 for the core ASCII letters/digits
    /// but differs across punctuation (0x27 quoteright, 0x60 quoteleft) and the
    /// whole 0x80–0xFF range, so it is built from the real name table rather than
    /// aliased to Latin-1.
    fn standard() -> HashMap<u32, char> {
        let mut m = HashMap::new();
        for (code, name) in crate::type1::STANDARD_ENCODING {
            if let Some(c) = glyph_to_char(name) {
                m.insert(*code as u32, c);
            }
        }
        m
    }

    /// Codes 0x20–0xFF mapped as Latin-1 (identity to Unicode).
    fn latin1() -> HashMap<u32, char> {
        let mut m = HashMap::new();
        for code in 0x20u32..=0xFF {
            if let Some(c) = char::from_u32(code) {
                m.insert(code, c);
            }
        }
        m
    }

    /// The full Adobe Symbol-font encoding (Greek + math operators).
    fn symbol_table() -> HashMap<u32, char> {
        crate::glyphlist::symbol()
    }

    /// The ZapfDingbats encoding (dingbats/ornaments).
    fn zapf_table() -> HashMap<u32, char> {
        crate::glyphlist::zapf()
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn winansi_maps_bullet_and_dashes() {
            let m = win_ansi();
            assert_eq!(m.get(&0x95), Some(&'\u{2022}'));
            assert_eq!(m.get(&0x96), Some(&'\u{2013}'));
            assert_eq!(m.get(&0x97), Some(&'\u{2014}'));
            assert_eq!(m.get(&0x41), Some(&'A'));
        }

        #[test]
        fn glyph_names_resolve() {
            assert_eq!(glyph_to_char("bullet"), Some('\u{2022}'));
            assert_eq!(glyph_to_char("uni20AC"), Some('\u{20AC}'));
            assert_eq!(glyph_to_char("A"), Some('A'));
            assert_eq!(glyph_to_char("emdash"), Some('\u{2014}'));
            assert_eq!(glyph_to_char("Aacute"), Some('\u{00C1}'));
        }
    }
}

#[cfg(test)]
mod encrypt_tests {
    use super::*;

    fn build_doc_bytes(title: &[u8]) -> Vec<u8> {
        let mut doc = Document::with_version("1.7");
        let info = doc.add_object(dictionary! {
            "Title" => Object::String(title.to_vec(), lopdf::StringFormat::Literal),
        });
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages",
            "Kids" => vec![page_id.into()],
            "Count" => 1,
        }));
        let catalog = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog);
        doc.trailer.set("Info", info);
        let mut out = Vec::new();
        doc.save_to(&mut out).unwrap();
        out
    }

    fn roundtrip(algo: crate::EncryptAlgo) {
        let title = b"SecretTitle123";
        let plain = build_doc_bytes(title);
        let pw = b"hunter2";
        let enc = crate::encrypt_doc_bytes(&plain, pw, pw, algo).expect("encrypt");
        // Wrong/empty password should not authenticate.
        let mut doc0 = Document::load_mem(&enc).unwrap();
        assert!(doc0.trailer.get(b"Encrypt").is_ok(), "should be encrypted");
        assert_ne!(crate::decrypt_in_place(&mut doc0, b""), crate::DecryptStatus::Ok);
        // Correct password decrypts and recovers the /Title string.
        let mut doc = Document::load_mem(&enc).unwrap();
        assert_eq!(crate::decrypt_in_place(&mut doc, pw), crate::DecryptStatus::Ok);
        let info_ref = doc.trailer.get(b"Info").unwrap().as_reference().unwrap();
        let info = doc.get_dictionary(info_ref).unwrap();
        let got = info.get(b"Title").unwrap().as_str().unwrap();
        assert_eq!(got, &title[..], "title should round-trip through {:?}", algo as u8);
    }

    #[test]
    fn rc4_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Rc4_128);
    }

    #[test]
    fn aes128_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Aes128);
    }

    #[test]
    fn aes256_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Aes256);
    }
}

#[cfg(test)]
mod type1_tests {
    use super::*;

    #[test]
    fn type1_encoding_scan() {
        let text = b"/Encoding 256 array\n0 1 255 {1 index exch /.notdef put} for\ndup 65 /A put\ndup 97 /a put\ndup 233 /eacute put\nreadonly def";
        let m = parse_type1_encoding_text(text);
        assert_eq!(m.get(&65), Some(&'A'));
        assert_eq!(m.get(&97), Some(&'a'));
        assert_eq!(m.get(&233), Some(&'\u{00E9}'));
    }

    #[test]
    fn rksj_codespace_segments_mixed_width_codes() {
        // 90ms-RKSJ-H interleaves 1-byte and 2-byte codes. Decoding as fixed
        // 2-byte codes would pair 'A' with the kanji lead byte and desynchronize
        // the rest of the string.
        let cs = cmap::predefined_codespace("90ms-RKSJ-H").expect("RKSJ recognized");
        let cm = cmap::EncodingCMap { codespace: cs, ..Default::default() };
        assert_eq!(cm.code_len(b'A'), 1, "ASCII is single-byte");
        assert_eq!(cm.code_len(0x82), 2, "kanji lead byte is double-byte");
        assert_eq!(cm.code_len(0xB0), 1, "half-width katakana is single-byte");
        assert_eq!(cm.code_len(0xE0), 2, "second kanji lead range is double-byte");
    }

    #[test]
    fn ucs2_cmaps_are_not_given_a_codespace() {
        // Pure 2-byte families already decode correctly via the Identity path.
        assert!(cmap::predefined_codespace("UniJIS-UCS2-H").is_none());
        assert!(cmap::predefined_codespace("UniGB-UCS2-H").is_none());
        assert!(cmap::predefined_codespace("UniKS-UCS2-H").is_none());
        assert!(cmap::predefined_codespace("Identity-H").is_none());
        assert!(cmap::predefined_codespace("Identity-V").is_none());
        // The ISO-2022 families are <2121>-<7E7E>, i.e. pure 2-byte.
        assert!(cmap::predefined_codespace("Add-H").is_none());
        assert!(cmap::predefined_codespace("Ext-V").is_none());
    }

    #[test]
    fn mixed_width_cmap_families_are_recognized() {
        // Each of these has 1-byte ranges alongside its 2-byte ranges, so a fixed
        // 2-byte decode desynchronizes the byte stream (PDF 9.7.6.2).
        let len = |name: &str, b: u8| {
            let cs = cmap::predefined_codespace(name).unwrap_or_else(|| panic!("{name} recognized"));
            cmap::EncodingCMap { codespace: cs, ..Default::default() }.code_len(b)
        };
        // Big5, GBK, EUC-CN, UHC, EUC-KR: ASCII single-byte, lead byte double.
        for (name, lead) in [
            ("ETen-B5-H", 0xA1u8),
            ("B5pc-H", 0xA1),
            ("GBK-EUC-H", 0x81),
            ("GB-EUC-H", 0xA1),
            ("GBpc-EUC-V", 0xA1),
            ("KSCms-UHC-H", 0x81),
            ("KSC-EUC-H", 0x81),
            ("KSCpc-EUC-H", 0x81),
        ] {
            assert_eq!(len(name, b'A'), 1, "{name}: ASCII must be single-byte");
            assert_eq!(len(name, lead), 2, "{name}: lead byte must be double-byte");
        }
        // Japanese EUC: 1-byte ASCII, 2-byte for both the 0x8E single-shift form
        // and the standard 0xA1.. plane.
        assert_eq!(len("EUC-H", b'A'), 1);
        assert_eq!(len("EUC-H", 0x8E), 2);
        assert_eq!(len("EUC-H", 0xA1), 2);
    }

    #[test]
    fn utf8_and_utf16_cmaps_segment_by_lead_byte() {
        // UniJIS-UTF8-H etc. are 1-4 bytes; a fixed 2-byte read desynchronizes on
        // the first ASCII character.
        let cs = cmap::predefined_codespace("UniJIS-UTF8-H").expect("UTF8 recognized");
        let cm = cmap::EncodingCMap { codespace: cs, ..Default::default() };
        assert_eq!(cm.code_len(b'A'), 1);
        assert_eq!(cm.code_len(0xC3), 2);
        assert_eq!(cm.code_len(0xE3), 3);
        assert_eq!(cm.code_len(0xF0), 4);
        // UTF-16 is 2 bytes except surrogate pairs, which are 4.
        let cs = cmap::predefined_codespace("UniGB-UTF16-H").expect("UTF16 recognized");
        let cm = cmap::EncodingCMap { codespace: cs, ..Default::default() };
        assert_eq!(cm.code_len(0x00), 2);
        assert_eq!(cm.code_len(0xD8), 4, "high surrogate starts a 4-byte code");
        assert_eq!(cm.code_len(0xE0), 2);
    }

    #[test]
    fn for_each_code_resegments_a_mixed_width_string() {
        // End-to-end: the whole point of the codespace is that a 1-byte code in
        // the middle of a CJK string does not shift every following code by one
        // byte. "A" + 2-byte kanji + "B" must yield exactly three codes.
        let cs = cmap::predefined_codespace("90ms-RKSJ-H").unwrap();
        let fi = FontInfo {
            two_byte: true,
            cmap: Some(cmap::EncodingCMap { codespace: cs, ..Default::default() }),
            ..simple_font_with_encoding()
        };
        let mut got = Vec::new();
        fi.for_each_code(&[0x41, 0x82, 0xA0, 0x42], |c, sp| got.push((c, sp)));
        assert_eq!(got, vec![(0x41, false), (0x82A0, false), (0x42, false)]);
        // A single-byte code 32 still reports as a word-spacing space, and a
        // 2-byte code whose value happens to be 0x20 does not (PDF 9.3.3).
        let mut spaces = Vec::new();
        fi.for_each_code(&[0x20, 0x82, 0x20], |c, sp| spaces.push((c, sp)));
        assert_eq!(spaces, vec![(0x20, true), (0x8220, false)]);
    }

    #[test]
    fn w_range_form_cannot_allocate_unbounded_widths() {
        // A hostile `cLast` must not drive a multi-billion-iteration insert loop.
        let mut doc = Document::new();
        let desc = doc.add_object(lopdf::dictionary! {
            "Type" => "Font",
            "Subtype" => "CIDFontType2",
            "W" => vec![0.into(), 4_000_000_000u32.into(), 500.into()],
        });
        let font = lopdf::dictionary! {
            "Type" => "Font",
            "Subtype" => "Type0",
            "DescendantFonts" => vec![desc.into()],
        };
        let (widths, _) = cid_widths(&doc, &font);
        assert!(widths.len() <= MAX_CID as usize + 1);
        assert_eq!(widths.get(&0), Some(&0.5));
        assert_eq!(widths.get(&MAX_CID), Some(&0.5));
    }

    #[test]
    fn bfrange_cannot_allocate_unbounded_strings() {
        // A 4-byte `hi` in a bfrange must be clamped, not expanded to 4 billion
        // entries.
        let map = cmap::parse(b"1 beginbfrange\n<00000000> <FFFFFFFF> <0041>\nendbfrange");
        assert!(map.len() <= MAX_CID as usize + 1);
        assert_eq!(map.get(&0).map(String::as_str), Some("A"));
    }

    #[test]
    fn tw_applies_only_to_single_byte_code_32() {
        // PDF 9.3.3: Tw applies to code 32 and nothing else -- notably not NBSP,
        // which must not stretch under justification.
        let fi = simple_font_with_encoding();
        let mut seen = Vec::new();
        fi.for_each_code(&[32, 0xA0, b'A'], |code, is_space| seen.push((code, is_space)));
        assert_eq!(seen, vec![(32, true), (0xA0, false), (65, false)]);
    }

    #[test]
    fn font_cache_shares_parsed_fonts_only_inside_a_scope() {
        // The cache exists because `fonts_from_resources` runs per page and
        // re-parses each font's embedded program every time. Assert on Arc
        // identity rather than on equality: equal contents would also hold if the
        // font had been re-parsed, which is exactly the bug being fixed.
        let mut doc = Document::with_version("1.7");
        let tu = doc.add_object(Stream::new(
            dictionary! {},
            b"1 beginbfchar\n<41> <0041>\nendbfchar".to_vec(),
        ));
        let font = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "TrueType",
            "BaseFont" => "Helvetica",
            "ToUnicode" => tu,
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => font } };

        // No scope active: the default must stay uncached, so nothing can ever go
        // stale in a mutated document.
        let a = fonts_from_resources(&doc, &res);
        let b = fonts_from_resources(&doc, &res);
        let (au, bu) = (
            a[b"F1".as_ref()].to_unicode.as_ref().unwrap(),
            b[b"F1".as_ref()].to_unicode.as_ref().unwrap(),
        );
        assert_eq!(au.get(&0x41).map(String::as_str), Some("A"), "parse still works");
        assert!(!Arc::ptr_eq(au, bu), "must not cache without an active scope");

        // Inside a scope the second lookup reuses the first parse.
        let _scope = FontCacheScope::new();
        let c = fonts_from_resources(&doc, &res);
        let d = fonts_from_resources(&doc, &res);
        assert!(
            Arc::ptr_eq(
                c[b"F1".as_ref()].to_unicode.as_ref().unwrap(),
                d[b"F1".as_ref()].to_unicode.as_ref().unwrap()
            ),
            "font should be parsed once per scope"
        );
    }

    fn simple_font_with_encoding() -> FontInfo {
        FontInfo {
            two_byte: false,
            wmode: 0,
            vertical_metrics: HashMap::new(),
            default_vertical: (0.880, -1.0),
            cid_to_gid: None,
            to_unicode: None,
            encoding: encoding::win_ansi(),
            cmap_uni: HashMap::new(),
            cmap: None,
            widths: HashMap::new(),
            default_width: 0.5,
            t3: None,
            style: FontStyle::default(),
            family: 0,
            base_font: String::new(),
            glyph_program: None,
            glyph_names: HashMap::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// ToUnicode CMap parsing
// ---------------------------------------------------------------------------

pub(crate) mod cmap {
    use std::collections::HashMap;

    enum Token {
        Hex(Vec<u8>),
        ArrayOpen,
        ArrayClose,
        Keyword(String),
    }

    /// Parse a `/ToUnicode` CMap stream into a `code -> string` map, handling
    /// `beginbfchar`/`endbfchar` and `beginbfrange`/`endbfrange`.
    pub fn parse(data: &[u8]) -> HashMap<u32, String> {
        let tokens = tokenize(data);
        let mut map = HashMap::new();
        let mut i = 0;
        while i < tokens.len() {
            match &tokens[i] {
                Token::Keyword(k) if k == "beginbfchar" => {
                    i += 1;
                    while i < tokens.len() {
                        if let Token::Keyword(e) = &tokens[i] {
                            if e == "endbfchar" {
                                break;
                            }
                        }
                        if let (Token::Hex(src), Some(Token::Hex(dst))) =
                            (&tokens[i], tokens.get(i + 1))
                        {
                            map.insert(code(src), utf16be(dst));
                            i += 2;
                        } else {
                            i += 1;
                        }
                    }
                    i += 1; // skip endbfchar
                }
                Token::Keyword(k) if k == "beginbfrange" => {
                    i += 1;
                    while i < tokens.len() {
                        if let Token::Keyword(e) = &tokens[i] {
                            if e == "endbfrange" {
                                break;
                            }
                        }
                        match (tokens.get(i), tokens.get(i + 1), tokens.get(i + 2)) {
                            (Some(Token::Hex(lo)), Some(Token::Hex(hi)), Some(Token::Hex(dst))) => {
                                let (lo, hi) = (code(lo), code(hi));
                                // A single bfrange cannot sanely span more than the
                                // 16-bit code space; clamp so a corrupt 4-byte `hi`
                                // cannot allocate billions of strings.
                                let hi = hi.min(lo.saturating_add(super::MAX_CID));
                                let base = utf16be_units(dst);
                                for (n, c) in (lo..=hi).enumerate() {
                                    map.insert(c, units_to_string_incremented(&base, n as u32));
                                }
                                i += 3;
                            }
                            (Some(Token::Hex(lo)), Some(Token::Hex(_hi)), Some(Token::ArrayOpen)) => {
                                let lo = code(lo);
                                i += 3; // skip lo, hi, '['
                                let mut n = 0u32;
                                while i < tokens.len() {
                                    match &tokens[i] {
                                        Token::ArrayClose => {
                                            i += 1;
                                            break;
                                        }
                                        Token::Hex(dst) => {
                                            map.insert(lo + n, utf16be(dst));
                                            n += 1;
                                            i += 1;
                                        }
                                        _ => i += 1,
                                    }
                                }
                            }
                            _ => i += 1,
                        }
                    }
                    i += 1; // skip endbfrange
                }
                _ => i += 1,
            }
        }
        map
    }

    fn tokenize(data: &[u8]) -> Vec<Token> {
        let mut tokens = Vec::new();
        let mut i = 0;
        while i < data.len() {
            let b = data[i];
            match b {
                b'<' => {
                    let mut hex = String::new();
                    i += 1;
                    while i < data.len() && data[i] != b'>' {
                        if !data[i].is_ascii_whitespace() {
                            hex.push(data[i] as char);
                        }
                        i += 1;
                    }
                    i += 1; // consume '>'
                    tokens.push(Token::Hex(hex_to_bytes(&hex)));
                }
                b'[' => {
                    tokens.push(Token::ArrayOpen);
                    i += 1;
                }
                b']' => {
                    tokens.push(Token::ArrayClose);
                    i += 1;
                }
                _ if b.is_ascii_alphabetic() => {
                    let mut kw = String::new();
                    while i < data.len()
                        && (data[i].is_ascii_alphanumeric() || data[i] == b'*')
                    {
                        kw.push(data[i] as char);
                        i += 1;
                    }
                    tokens.push(Token::Keyword(kw));
                }
                _ => i += 1,
            }
        }
        tokens
    }

    fn hex_to_bytes(hex: &str) -> Vec<u8> {
        let mut h = hex.to_string();
        if h.len() % 2 == 1 {
            h.push('0');
        }
        (0..h.len())
            .step_by(2)
            .filter_map(|i| u8::from_str_radix(&h[i..i + 2], 16).ok())
            .collect()
    }

    fn code(bytes: &[u8]) -> u32 {
        let mut c = 0u32;
        for &b in bytes {
            c = (c << 8) | b as u32;
        }
        c
    }

    /// Codespace ranges for the predefined mixed-width CMap families (PDF 9.7.5.2,
    /// Table 118). These interleave 1-byte and 2-byte codes, so decoding them as
    /// fixed 2-byte codes desynchronizes the byte stream for the rest of the
    /// string. The CID mapping itself still needs the real compiled table, but
    /// getting the segmentation right makes `/ToUnicode` (which is keyed by CODE)
    /// resolve correctly, which is what most such files rely on.
    ///
    /// Returning `None` means "fixed 2-byte", which is right for Identity-H/V,
    /// the `Uni*-UCS2-*` families, and the pure-2-byte ISO-2022 families
    /// (`H`, `V`, `Add-H`, `Ext-H`, whose codespace is <2121>-<7E7E>).
    pub fn predefined_codespace(name: &str) -> Option<Vec<(u32, u32, u8)>> {
        // UTF-8 (UniJIS-UTF8-H, UniGB-UTF8-H, UniCNS-UTF8-H, UniKS-UTF8-H): 1-4
        // bytes, so a fixed 2-byte read desynchronizes on the very first ASCII
        // character. Checked before the region families because the names
        // overlap (e.g. "UniGB-UTF8-H" also contains "GB").
        if name.contains("UTF8") {
            return Some(vec![
                (0x00, 0x7F, 1),
                (0xC080, 0xDFBF, 2),
                (0xE08080, 0xEFBFBF, 3),
                (0xF0808080, 0xF7BFBFBF, 4),
            ]);
        }
        // UTF-16: 2 bytes, except surrogate pairs which are 4.
        if name.contains("UTF16") {
            return Some(vec![
                (0x0000, 0xD7FF, 2),
                (0xD800DC00, 0xDBFFDFFF, 4),
                (0xE000, 0xFFFF, 2),
            ]);
        }
        // Shift-JIS: 90ms-RKSJ-H, 90msp-RKSJ-V, 90pv-RKSJ-H, Add-RKSJ-H, Ext-RKSJ-H
        if name.contains("RKSJ") {
            return Some(vec![
                (0x00, 0x80, 1),
                (0x8140, 0x9FFC, 2),
                (0xA0, 0xDF, 1),
                (0xE040, 0xFCFC, 2),
            ]);
        }
        // Japanese EUC: EUC-H, EUC-V. The 0x8E single-shift form is a 2-byte code.
        if name.starts_with("EUC-") {
            return Some(vec![(0x00, 0x80, 1), (0x8EA0, 0x8EFE, 2), (0xA1A1, 0xFEFE, 2)]);
        }
        // GBK: GBK-EUC-H, GBKp-EUC-H, GBK2K-H
        //
        // GBK2K-H (GB18030) additionally has a 4-byte plane whose codes are
        // distinguished from 2-byte codes only by the SECOND byte (0x30-0x39),
        // which `code_len` cannot see — it dispatches on the first byte alone.
        // Those codes therefore still mis-segment. Left as-is deliberately: the
        // 1- and 2-byte planes cover essentially all real GBK2K content, and
        // widening `code_len` to a multi-byte lookahead would change
        // `for_each_code` for every font to fix a rare case.
        if name.contains("GBK") {
            return Some(vec![(0x00, 0x80, 1), (0x8140, 0xFEFE, 2)]);
        }
        // EUC-CN: GB-EUC-H, GBpc-EUC-H (checked after GBK, whose names also
        // contain "GB").
        if name.contains("GB") && name.contains("EUC") {
            return Some(vec![(0x00, 0x80, 1), (0xA1A1, 0xFEFE, 2)]);
        }
        // Big5: ETen-B5-H, ETenms-B5-H, B5pc-H, HKscs-B5-H
        if name.contains("-B5") || name.starts_with("B5") {
            return Some(vec![(0x00, 0x80, 1), (0xA140, 0xFEFE, 2)]);
        }
        // Korean UHC / EUC-KR: KSCms-UHC-H, KSCms-UHC-HW-V, KSC-EUC-H, KSCpc-EUC-H
        if name.contains("UHC") || name.contains("KSC") {
            return Some(vec![(0x00, 0x80, 1), (0x8141, 0xFEFE, 2)]);
        }
        None
    }

    /// A Type0 `/Encoding` CMap: variable-length codespace ranges plus code->CID
    /// mappings (from `begincidrange`/`begincidchar`), and the writing mode.
    #[derive(Default, Clone)]
    pub struct EncodingCMap {
        /// (lo, hi, byte_len) codespace ranges.
        pub codespace: Vec<(u32, u32, u8)>,
        pub single: HashMap<u32, u32>,
        /// (lo, hi, cid_of_lo) contiguous ranges.
        pub ranges: Vec<(u32, u32, u32)>,
        pub wmode: u8,
    }

    impl EncodingCMap {
        /// Map a character code to a CID (identity fallback if unmapped).
        pub fn to_cid(&self, code: u32) -> u32 {
            if let Some(c) = self.single.get(&code) {
                return *c;
            }
            for &(lo, hi, c0) in &self.ranges {
                if code >= lo && code <= hi {
                    return c0 + (code - lo);
                }
            }
            code
        }

        /// Byte length of the code beginning with `first_byte`, using the
        /// codespace ranges (defaults to 2 bytes, the Identity case).
        pub fn code_len(&self, first_byte: u8) -> usize {
            for &(lo, hi, n) in &self.codespace {
                let shift = (n.saturating_sub(1)) * 8;
                let flo = (lo >> shift) & 0xFF;
                let fhi = (hi >> shift) & 0xFF;
                if (first_byte as u32) >= flo && (first_byte as u32) <= fhi {
                    return n as usize;
                }
            }
            if self.codespace.is_empty() { 2 } else { self.codespace[0].2 as usize }
        }
    }

    /// Parse a Type0 `/Encoding` CMap stream. Handles `codespacerange`,
    /// `cidrange`, `cidchar`, and `/WMode`. Numeric CID operands are decimal.
    pub fn parse_encoding_cmap(data: &[u8]) -> EncodingCMap {
        let mut cm = EncodingCMap::default();
        // Lightweight token scan: hex strings <..>, decimal integers, keywords.
        #[derive(PartialEq)]
        enum T { Hex(Vec<u8>), Int(u32), Kw(String) }
        let mut toks: Vec<T> = Vec::new();
        let mut i = 0;
        while i < data.len() {
            let b = data[i];
            if b == b'<' {
                let mut hex = String::new();
                i += 1;
                while i < data.len() && data[i] != b'>' {
                    if !data[i].is_ascii_whitespace() { hex.push(data[i] as char); }
                    i += 1;
                }
                i += 1;
                toks.push(T::Hex(hex_to_bytes(&hex)));
            } else if b.is_ascii_digit() {
                let s = i;
                while i < data.len() && data[i].is_ascii_digit() { i += 1; }
                let n: u32 = std::str::from_utf8(&data[s..i]).ok().and_then(|x| x.parse().ok()).unwrap_or(0);
                toks.push(T::Int(n));
            } else if b.is_ascii_alphabetic() || b == b'/' {
                let s = i;
                i += 1;
                // '-' is part of predefined CMap names (`/90ms-RKSJ-H usecmap`), so
                // it must not terminate the token.
                while i < data.len() && (data[i].is_ascii_alphanumeric() || data[i] == b'/' || data[i] == b'.' || data[i] == b'-') { i += 1; }
                toks.push(T::Kw(String::from_utf8_lossy(&data[s..i]).into_owned()));
            } else {
                i += 1;
            }
        }
        let byte_len = |bytes: &[u8]| -> u8 { bytes.len().clamp(1, 4) as u8 };
        let mut j = 0;
        while j < toks.len() {
            match &toks[j] {
                // `/SomeCMap usecmap` inherits the referenced CMap. Only a
                // predefined name can be inherited here (an embedded one would have
                // to be reachable through the stream's own /UseCMap, which lopdf
                // does not hand us), and only its codespace ranges are recoverable,
                // so inherit those and let this stream's own ranges override.
                T::Kw(k) if k == "usecmap" => {
                    if let Some(T::Kw(name)) = j.checked_sub(1).and_then(|p| toks.get(p)) {
                        let base = name.trim_start_matches('/');
                        if let Some(cs) = predefined_codespace(base) {
                            for r in cs {
                                if !cm.codespace.contains(&r) {
                                    cm.codespace.push(r);
                                }
                            }
                        }
                        if base.ends_with("-V") {
                            cm.wmode = 1;
                        }
                    }
                    j += 1;
                }
                T::Kw(k) if k == "/WMode" => {
                    if let Some(T::Int(w)) = toks.get(j + 1) { cm.wmode = if *w >= 1 { 1 } else { 0 }; }
                    j += 1;
                }
                T::Kw(k) if k == "begincodespacerange" => {
                    j += 1;
                    while j + 1 < toks.len() {
                        if let (T::Hex(lo), T::Hex(hi)) = (&toks[j], &toks[j + 1]) {
                            cm.codespace.push((code(lo), code(hi), byte_len(lo)));
                            j += 2;
                        } else { break; }
                    }
                }
                T::Kw(k) if k == "begincidrange" => {
                    j += 1;
                    while j + 2 < toks.len() {
                        match (&toks[j], &toks[j + 1], &toks[j + 2]) {
                            (T::Hex(lo), T::Hex(hi), T::Int(cid)) => {
                                cm.ranges.push((code(lo), code(hi), *cid));
                                j += 3;
                            }
                            _ => break,
                        }
                    }
                }
                T::Kw(k) if k == "begincidchar" => {
                    j += 1;
                    while j + 1 < toks.len() {
                        match (&toks[j], &toks[j + 1]) {
                            (T::Hex(c), T::Int(cid)) => {
                                cm.single.insert(code(c), *cid);
                                j += 2;
                            }
                            _ => break,
                        }
                    }
                }
                _ => { j += 1; }
            }
        }
        cm
    }

    fn utf16be_units(bytes: &[u8]) -> Vec<u16> {
        bytes
            .chunks(2)
            .map(|c| {
                let hi = c[0] as u16;
                let lo = *c.get(1).unwrap_or(&0) as u16;
                (hi << 8) | lo
            })
            .collect()
    }

    fn utf16be(bytes: &[u8]) -> String {
        String::from_utf16_lossy(&utf16be_units(bytes))
    }

    /// Increment the last UTF-16 code unit by `n` (per PDF bfrange semantics)
    /// and decode the result.
    fn units_to_string_incremented(units: &[u16], n: u32) -> String {
        let mut u = units.to_vec();
        if let Some(last) = u.last_mut() {
            *last = last.wrapping_add(n as u16);
        }
        String::from_utf16_lossy(&u)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn parses_bfchar_single_byte() {
            let cmap = b"2 beginbfchar\n<41> <0041>\n<42> <0042>\nendbfchar";
            let map = parse(cmap);
            assert_eq!(map.get(&0x41).map(String::as_str), Some("A"));
            assert_eq!(map.get(&0x42).map(String::as_str), Some("B"));
        }

        #[test]
        fn parses_bfchar_two_byte() {
            let cmap = b"1 beginbfchar\n<0003> <0048>\nendbfchar";
            let map = parse(cmap);
            assert_eq!(map.get(&0x0003).map(String::as_str), Some("H"));
        }

        #[test]
        fn parses_bfrange_incrementing() {
            let cmap = b"1 beginbfrange\n<0041> <0043> <0061>\nendbfrange";
            let map = parse(cmap);
            assert_eq!(map.get(&0x41).map(String::as_str), Some("a"));
            assert_eq!(map.get(&0x42).map(String::as_str), Some("b"));
            assert_eq!(map.get(&0x43).map(String::as_str), Some("c"));
        }

        #[test]
        fn parses_bfrange_after_preamble() {
            // A realistic ToUnicode with a dict/codespace preamble before the
            // bfrange block (regression for a token double-increment bug).
            let cmap = b"/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n<< /Registry (TTX+0) /Ordering (T1) /Supplement 0 >> def\n1 begincodespacerange\n<0000><FFFF>\nendcodespacerange\n2 beginbfrange\n<0033><0033><0050>\n<0055><0055><0072>\nendbfrange\nendcmap";
            let map = parse(cmap);
            assert_eq!(map.get(&0x33).map(String::as_str), Some("P"));
            assert_eq!(map.get(&0x55).map(String::as_str), Some("r"));
        }
    }
}

// ---------------------------------------------------------------------------
// Wire serialization
// ---------------------------------------------------------------------------
