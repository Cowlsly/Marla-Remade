use crate::*;

/// Field type + name inherited through the widget's `/Parent` chain.
pub(crate) fn field_attr<'a>(doc: &'a Document, mut id: ObjectId, key: &[u8]) -> Option<&'a Object> {
    for _ in 0..16 {
        let dict = doc.get_dictionary(id).ok()?;
        if let Ok(v) = dict.get(key) {
            return Some(v);
        }
        id = dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok())?;
    }
    None
}

/// A numeric field key resolved as §12.7.3.1 requires for the entries it marks
/// "Optional; inheritable": the widget's own value if it has one, otherwise the
/// nearest ancestor's up the `/Parent` chain.
fn inherited_num(doc: &Document, id: ObjectId, key: &[u8]) -> Option<f64> {
    field_attr(doc, id, key)
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(num)
}

/// The terminal field a widget belongs to: the nearest dictionary at or above it
/// carrying `/FT` (§12.7.3.1). `/V`, `/I`, `/Ff`, `/Q` and `/MaxLen` are keys of
/// THAT field, so writing them on the clicked widget (when the widget is a
/// separate `/Kids` entry) leaves every sibling widget stale, and writing them on
/// a grouping ancestor above the terminal field puts them out of reach. Bounded
/// so a malformed cyclic `/Parent` chain terminates; falls back to the widget
/// itself when nothing in the chain declares `/FT`.
pub(crate) fn terminal_field_id(doc: &Document, id: ObjectId) -> ObjectId {
    let mut cur = id;
    for _ in 0..16 {
        let dict = match doc.get_dictionary(cur) {
            Ok(d) => d,
            Err(_) => break,
        };
        if dict.get(b"FT").is_ok() {
            return cur;
        }
        match dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok()) {
            Some(p) if p != cur => cur = p,
            _ => break,
        }
    }
    id
}

/// The interactive form dictionary (`/Root /AcroForm`).
fn acroform(doc: &Document) -> Option<&Dictionary> {
    doc.catalog()
        .ok()?
        .get(b"AcroForm")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
}

/// A variable-text field's `/Q` quadding (0 left, 1 centred, 2 right): the
/// field's own, inheritable up `/Parent` (§12.7.4.3 Table 222), else the
/// AcroForm document-wide default (§12.7.2 Table 218).
fn field_quadding(doc: &Document, id: ObjectId) -> i64 {
    inherited_num(doc, id, b"Q")
        .or_else(|| {
            acroform(doc)
                .and_then(|af| af.get(b"Q").ok())
                .and_then(|o| deref(doc, o).or(Some(o)))
                .and_then(num)
        })
        .unwrap_or(0.0) as i64
}

/// The pieces of a `/DA` default appearance string that a generated field
/// appearance needs (§12.7.3.3: "a sequence of valid page-content graphics or
/// text state operators ... defining such properties as the field's text size
/// and colour").
pub(crate) struct DefaultAppearance {
    /// Resource name given to `Tf`, without the leading slash. `None` when the
    /// string names no font or names one we refuse to trust.
    pub font: Option<Vec<u8>>,
    /// Size given to `Tf`. ZERO is not "invisible": §12.7.4.3 makes it mean
    /// AUTO-SIZE to fit the field, so callers must substitute their own size.
    pub size: f64,
    /// Fill colour from `g` / `rg` / `k`, defaulting to black as the previous
    /// hardcoded `0 0 0 rg` did.
    pub argb: u32,
}

impl Default for DefaultAppearance {
    fn default() -> Self {
        DefaultAppearance { font: None, size: 0.0, argb: 0xFF00_0000 }
    }
}

/// Whether a `/DA` font name is safe to interpolate into a generated content
/// stream. The name is written straight into `/<name> <size> Tf`, so anything
/// outside the regular-character set could close the operand and inject
/// operators (§7.3.5 restricts names to regular characters anyway).
fn safe_resource_name(n: &[u8]) -> bool {
    !n.is_empty()
        && n.len() <= 127
        && n.iter().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'.' | b'+' | b'-'))
}

/// Parse the font name, size and fill colour out of a `/DA` string.
///
/// Only the fill-colour operators are read: a generated field appearance paints
/// glyphs in text render mode 0, which uses the fill colour, so `G`/`RG`/`K`
/// would have no effect. Later operators win, matching content-stream semantics.
pub(crate) fn parse_da(da: &[u8]) -> DefaultAppearance {
    let s = String::from_utf8_lossy(da);
    let toks: Vec<&str> = s.split_whitespace().collect();
    let mut out = DefaultAppearance::default();
    let n = |t: &str| t.parse::<f64>().ok().filter(|v| v.is_finite());
    for (i, t) in toks.iter().enumerate() {
        match *t {
            "Tf" if i >= 2 => {
                if let Some(v) = n(toks[i - 1]) {
                    // Clamped rather than rejected: a negative size is malformed,
                    // and an absurd one would only produce a huge clipped glyph.
                    out.size = v.clamp(0.0, 1000.0);
                }
                out.font = toks[i - 2]
                    .strip_prefix('/')
                    .map(|f| f.as_bytes())
                    .filter(|f| safe_resource_name(f))
                    .map(|f| f.to_vec());
            }
            "g" if i >= 1 => {
                if let Some(v) = n(toks[i - 1]) {
                    out.argb = gray_to_argb(v.clamp(0.0, 1.0));
                }
            }
            "rg" if i >= 3 => {
                if let (Some(r), Some(g), Some(b)) = (n(toks[i - 3]), n(toks[i - 2]), n(toks[i - 1])) {
                    out.argb = rgb_to_argb(r.clamp(0.0, 1.0), g.clamp(0.0, 1.0), b.clamp(0.0, 1.0));
                }
            }
            "k" if i >= 4 => {
                if let (Some(c), Some(m), Some(y), Some(k)) =
                    (n(toks[i - 4]), n(toks[i - 3]), n(toks[i - 2]), n(toks[i - 1]))
                {
                    out.argb = cmyk_to_argb(
                        c.clamp(0.0, 1.0), m.clamp(0.0, 1.0), y.clamp(0.0, 1.0), k.clamp(0.0, 1.0),
                    );
                }
            }
            _ => {}
        }
    }
    out
}

/// A widget's effective `/DA`: the field's own (inheritable through `/Parent`),
/// else the document-wide AcroForm default (§12.7.2 Table 218).
pub(crate) fn field_da(doc: &Document, id: ObjectId) -> DefaultAppearance {
    let own = field_attr(doc, id, b"DA")
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(|o| o.as_str().ok());
    if let Some(s) = own {
        return parse_da(s);
    }
    acroform(doc)
        .and_then(|af| af.get(b"DA").ok())
        .and_then(|o| deref(doc, o).or(Some(o)))
        .and_then(|o| o.as_str().ok())
        .map(parse_da)
        .unwrap_or_default()
}

/// Resources for a generated field appearance, plus the resource name to write
/// into `Tf`. Resolves a `/DA` font name against the AcroForm `/DR /Font`
/// dictionary (§12.7.3.3) and falls back to the Helvetica substitute otherwise.
///
/// A font is adopted only when the generated content can actually address it.
/// The value is written as a plain literal string, so a composite (`Type0`) font
/// — addressed by multi-byte CIDs — would render it as garbage, and a font with
/// a `/Differences` or symbolic encoding would remap the bytes to unrelated
/// glyphs. Both are strictly worse than the substitute, so in those cases the
/// substitute is kept and only the `/DA` size and colour are honoured.
fn da_font_resources(doc: &Document, name: Option<&[u8]>) -> (Dictionary, Vec<u8>) {
    let fallback = (helvetica_resources(), b"F1".to_vec());
    let name = match name {
        Some(n) if safe_resource_name(n) => n,
        _ => return fallback,
    };
    let entry = acroform(doc)
        .and_then(|af| af.get(b"DR").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|dr| dr.get(b"Font").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|fonts| fonts.get(name).ok());
    let entry = match entry {
        Some(e) => e,
        None => return fallback,
    };
    let font = match deref(doc, entry).and_then(|o| o.as_dict().ok()) {
        Some(d) => d,
        None => return fallback,
    };
    let simple = matches!(
        font.get(b"Subtype").ok().and_then(|o| o.as_name().ok()),
        Some(b"Type1") | Some(b"TrueType") | Some(b"MMType1")
    );
    if !simple || !latin_text_encoding(doc, font) {
        return fallback;
    }
    let mut res = helvetica_resources();
    if let Ok(Object::Dictionary(fonts)) = res.get_mut(b"Font") {
        fonts.set(name.to_vec(), entry.clone());
    }
    (res, name.to_vec())
}

/// Whether a simple font's `/Encoding` maps single Latin-1-ish bytes to the
/// glyphs they name, so a literal string written from `decode_pdf_text` output
/// lands on the right glyphs. Absent (the font's built-in encoding) counts,
/// since for the standard text faces that is StandardEncoding.
fn latin_text_encoding(doc: &Document, font: &Dictionary) -> bool {
    const OK: [&[u8]; 3] = [b"WinAnsiEncoding", b"MacRomanEncoding", b"StandardEncoding"];
    let enc = match font.get(b"Encoding").ok().and_then(|o| deref(doc, o).or(Some(o))) {
        None => return true,
        Some(e) => e,
    };
    match enc {
        Object::Name(n) => OK.contains(&n.as_slice()),
        Object::Dictionary(d) => {
            d.get(b"Differences").is_err()
                && d.get(b"BaseEncoding")
                    .ok()
                    .and_then(|o| o.as_name().ok())
                    .map(|n| OK.contains(&n))
                    .unwrap_or(true)
        }
        _ => false,
    }
}

/// Resolve a GoTo destination to a 0-based page index, or -1.
pub(crate) fn resolve_dest_page(doc: &Document, d: &Object, page_of: &HashMap<ObjectId, i32>) -> i32 {
    let d = deref(doc, d).unwrap_or(d);
    if let Object::Array(a) = d {
        if let Some(first) = a.first() {
            if let Ok(id) = first.as_reference() {
                return *page_of.get(&id).unwrap_or(&-1);
            }
        }
    }
    -1
}

/// Serialize link annotations for a page: rect (displayed space), destination
/// page (-1 if none), and URI (empty if none).
pub(crate) fn list_links(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);
    let mut page_of: HashMap<ObjectId, i32> = HashMap::new();
    for (n, id) in doc.get_pages() {
        page_of.insert(id, (n as i32) - 1);
    }
    let mut records: Vec<([f64; 4], i32, String)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            let dict = match a.as_reference().ok().and_then(|id| doc.get_dictionary(id).ok()) {
                Some(d) => d,
                None => continue,
            };
            if dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok()) != Some(b"Link".as_ref()) {
                continue;
            }
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (x0, y0) = transform(&base, n[0], n[1]);
                    let (x1, y1) = transform(&base, n[2], n[3]);
                    normalize_rect([x0, y0, x1, y1])
                }
                None => continue,
            };
            let mut dest_page = -1i32;
            let mut uri = String::new();
            if let Some(action) = dict.get(b"A").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()) {
                let s = action.get(b"S").ok().and_then(|o| o.as_name().ok());
                if s == Some(b"URI".as_ref()) {
                    if let Ok(u) = action.get(b"URI").and_then(|o| o.as_str()) {
                        uri = String::from_utf8_lossy(u).into_owned();
                    }
                } else if s == Some(b"GoTo".as_ref()) {
                    if let Ok(d) = action.get(b"D") {
                        // §12.6.4.2: a GoTo action's /D "shall be" a name, a byte
                        // string or an array — the first two being named
                        // destinations resolved through /Dests or the /Names
                        // name tree (§12.3.2.3). Accepting only the array form
                        // left dest_page at -1, and the record filter below then
                        // dropped the link entirely, so every named-destination
                        // link in the document was untappable.
                        dest_page = resolve_dest(doc, deref(doc, d).unwrap_or(d), &page_of);
                    }
                } else if s == Some(b"GoToR".as_ref()) {
                    if let Ok(d) = action.get(b"D") {
                        dest_page = resolve_dest_page(doc, d, &page_of);
                    }
                    if dest_page < 0 {
                        if let Some(f) = action
                            .get(b"F")
                            .ok()
                            .and_then(|o| deref(doc, o).or(Some(o)))
                            .and_then(|o| o.as_str().ok())
                        {
                            uri = String::from_utf8_lossy(f).into_owned();
                        }
                    }
                } else if s == Some(b"Launch".as_ref()) {
                    if let Some(f_obj) = action
                        .get(b"F")
                        .ok()
                        .and_then(|o| deref(doc, o).or(Some(o)))
                        .cloned()
                    {
                        let f_str = f_obj.as_str().ok().or_else(|| {
                            f_obj
                                .as_dict()
                                .ok()
                                .and_then(|d| d.get(b"F").ok())
                                .and_then(|o| o.as_str().ok())
                        });
                        if let Some(f) = f_str {
                            uri = String::from_utf8_lossy(f).into_owned();
                        }
                    } else if let Some(win_f) = action
                        .get(b"Win")
                        .ok()
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| o.as_dict().ok())
                        .and_then(|d| d.get(b"F").ok())
                        .and_then(|o| o.as_str().ok())
                    {
                        uri = String::from_utf8_lossy(win_f).into_owned();
                    }
                } else if s == Some(b"Named".as_ref()) {
                    // Named action (e.g. /N /GoToPage) — not directly resolvable
                }
            } else if let Ok(d) = dict.get(b"Dest") {
                dest_page = resolve_dest_page(doc, d, &page_of);
                // If Dest is Named via string, attempt name tree lookup
                if dest_page < 0 {
                    if let Some(obj) = deref(doc, d).or(Some(d)) {
                        dest_page = resolve_dest(doc, obj, &page_of);
                    }
                }
            }
            if dest_page >= 0 || !uri.is_empty() {
                records.push((rect, dest_page, uri));
            }
        }
    }
    let mut buf = Vec::new();
    buf.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for (rect, dest, uri) in records {
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        buf.extend_from_slice(&dest.to_le_bytes());
        let b = uri.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

pub(crate) fn list_form_fields(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);

    // (widgetId, typeCode, rect, name, value, checked)
    let mut fields: Vec<(i64, u8, [f64; 4], String, String, u8)> = Vec::new();
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
            let is_widget = dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok())
                == Some(b"Widget".as_ref());
            // Follow Parent T to locate AcroForm field type - handle nested field attrs
            let ft = field_attr(doc, id, b"FT").and_then(|o| o.as_name().ok());
            if !is_widget || ft.is_none() {
                continue;
            }
            let ft = ft.unwrap();
            // P0 fix #8: Sig distinct type 4, not generic 3
            let type_code = match ft {
                b"Tx" => 0u8,
                b"Btn" => 1u8,
                b"Ch" => 2u8,
                b"Sig" => 4u8,
                _ => 3u8,
            };
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (dx0, dy0) = transform(&base, n[0], n[1]);
                    let (dx1, dy1) = transform(&base, n[2], n[3]);
                    normalize_rect([dx0, dy0, dx1, dy1])
                }
                None => continue,
            };
            let name = field_attr(doc, id, b"T")
                .and_then(|o| o.as_str().ok())
                .map(decode_pdf_text)
                .unwrap_or_default();
            // P0 fix #10: Choice multi-select V can be array
            let value = field_attr(doc, id, b"V")
                .map(|o| match o {
                    Object::String(s, _) => decode_pdf_text(s),
                    Object::Name(n) => String::from_utf8_lossy(n).into_owned(),
                    Object::Array(arr) => {
                        // Multi-select array of strings
                        arr.iter().filter_map(|x| match x {
                            Object::String(s, _) => Some(decode_pdf_text(s)),
                            Object::Name(n) => Some(String::from_utf8_lossy(n).into_owned()),
                            _ => None,
                        }).collect::<Vec<_>>().join(",")
                    }
                    _ => String::new(),
                })
                .unwrap_or_default();
            let checked = if type_code == 1 {
                // §12.5.5: /AS names which of /AP /N's states this WIDGET paints,
                // so it is the per-widget truth. /V is the FIELD's value, shared
                // by every kid of a radio group — using it as a fallback for a
                // widget that has /AS = /Off reported every button in the group as
                // selected. Only fall back to /V when the widget has no /AS.
                match dict.get(b"AS").ok().and_then(|o| o.as_name().ok()) {
                    Some(s) => (s != b"Off") as u8,
                    None => (!value.is_empty() && value != "Off") as u8,
                }
            } else {
                0
            };
            fields.push((encode_id(id), type_code, rect, name, value, checked));
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(fields.len() as u32).to_le_bytes());
    for (id, tc, rect, name, value, checked) in fields {
        buf.extend_from_slice(&id.to_le_bytes());
        buf.push(tc);
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        for s in [&name, &value] {
            let b = s.as_bytes();
            let len = b.len().min(u16::MAX as usize);
            buf.extend_from_slice(&(len as u16).to_le_bytes());
            buf.extend_from_slice(&b[..len]);
        }
        buf.push(checked);
    }
    Some(buf)
}

/// Set the AcroForm `/NeedAppearances` flag so conformant viewers regenerate
/// field appearances after a value change.
pub(crate) fn set_need_appearances(doc: &mut Document) {
    let acro_id = doc
        .catalog()
        .ok()
        .and_then(|c| c.get(b"AcroForm").ok())
        .and_then(|o| o.as_reference().ok());
    if let Some(id) = acro_id {
        if let Ok(af) = doc.get_dictionary_mut(id) {
            af.set("NeedAppearances", Object::Boolean(true));
        }
    }
}

/// Build the content stream for a text field's `/N` appearance, honoring
/// alignment (`/Q`: 0 left, 1 center, 2 right), multiline (line-wrapped) and
/// comb (one glyph per `/MaxLen` cell) fields, and the font, size and colour the
/// caller resolved from `/DA`. Widths are approximated with Helvetica's ~0.5em
/// average since exact metrics aren't needed for a legible generated appearance.
#[allow(clippy::too_many_arguments)]
fn build_text_appearance(
    value: &str,
    w: f64,
    h: f64,
    size: f64,
    font: &[u8],
    argb: u32,
    quadding: i64,
    multiline: bool,
    comb: bool,
    max_len: usize,
) -> Vec<u8> {
    let char_w = size * 0.5;
    let (r, g, b) = argb_rgb(argb);
    let mut body = String::new();
    body.push_str(&format!(
        "q {r:.3} {g:.3} {b:.3} rg BT /{} {size:.3} Tf ",
        String::from_utf8_lossy(font)
    ));

    if comb && !multiline && max_len > 0 {
        // One glyph per cell, centered in each cell. §12.7.4.3 Table 226 bit 25
        // makes Comb "meaningful only if the MaxLen entry is present ... and if
        // the Multiline, Password, and FileSelect flags are clear", so a field
        // carrying both flags wraps rather than chopping the value into MaxLen
        // one-character cells on a single row.
        //
        // The vertical offset has to be repeated on every `Tm`: `Tm` REPLACES
        // the text matrix rather than concatenating (§9.4.2), so setting the
        // baseline once up front and then issuing per-cell `Tm`s dropped every
        // glyph to y=0, sitting the row on the bottom edge of the box with its
        // descenders clipped.
        let cell_w = w / max_len as f64;
        let base_y = (h - size) / 2.0;
        for (i, ch) in value.chars().take(max_len).enumerate() {
            let cx = i as f64 * cell_w + (cell_w - char_w) / 2.0;
            body.push_str(&format!(
                "1 0 0 1 {cx:.2} {base_y:.2} Tm ({}) Tj ",
                escape_pdf_literal(&ch.to_string())
            ));
        }
    } else if multiline {
        // Split on explicit newlines and greedily wrap to the box width. CRLF is
        // normalized first: splitting on both characters turns each "\r\n" into
        // an extra empty line, double-spacing the whole field.
        let value = value.replace("\r\n", "\n");
        let leading = size * 1.15;
        let max_chars = ((w - 4.0) / char_w).floor().max(1.0) as usize;
        let mut lines: Vec<String> = Vec::new();
        for raw in value.split(['\n', '\r']) {
            if raw.is_empty() {
                lines.push(String::new());
                continue;
            }
            let mut cur = String::new();
            for word in raw.split(' ') {
                if cur.is_empty() {
                    cur = word.to_string();
                } else if cur.chars().count() + 1 + word.chars().count() <= max_chars {
                    cur.push(' ');
                    cur.push_str(word);
                } else {
                    lines.push(std::mem::take(&mut cur));
                    cur = word.to_string();
                }
            }
            lines.push(cur);
        }
        let mut y = h - size - 2.0;
        for line in lines {
            let x = aligned_x(&line, w, char_w, quadding);
            body.push_str(&format!(
                "1 0 0 1 {x:.2} {y:.2} Tm ({}) Tj ",
                escape_pdf_literal(&line)
            ));
            y -= leading;
            if y < -size {
                break;
            }
        }
    } else {
        let x = aligned_x(value, w, char_w, quadding);
        let y = (h - size) / 2.0;
        body.push_str(&format!(
            "1 0 0 1 {x:.2} {y:.2} Tm ({}) Tj ",
            escape_pdf_literal(value)
        ));
    }
    body.push_str("ET Q");
    body.into_bytes()
}

/// Horizontal text origin for a line given the box width, approximate glyph
/// width and `/Q` alignment.
fn aligned_x(line: &str, w: f64, char_w: f64, quadding: i64) -> f64 {
    let text_w = line.chars().count() as f64 * char_w;
    match quadding {
        1 => ((w - text_w) / 2.0).max(2.0),      // centered
        2 => (w - text_w - 2.0).max(2.0),        // right
        _ => 2.0,                                 // left
    }
}

/// The font size for a generated field appearance: the `/DA` size, or — when
/// that is ZERO, which §12.7.4.3 defines as auto-size-to-fit and NOT as
/// invisible — a size derived from the field height.
fn field_font_size(da_size: f64, h: f64) -> f64 {
    if da_size > 0.0 {
        da_size
    } else {
        (h - 4.0).clamp(6.0, 14.0)
    }
}

pub(crate) fn set_text_field(handle: i64, widget_id: i64, value: &str) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    // /V is a field key, so it belongs on the terminal field; setting it on the
    // clicked widget leaves every other widget of the field stale.
    let root_id = terminal_field_id(doc, id);
    // §12.7.3.1 Table 220 marks /Ff and §12.7.4.3 Table 222 marks /Q and /MaxLen
    // "Optional; inheritable", and §12.7.2 Table 218 makes the AcroForm /Q the
    // document-wide default. Reading them off one dictionary missed both routes:
    // a field whose /Ff sits on a grouping ancestor lost its Multiline flag and
    // rendered a wrapped value as one clipped line, and a form aligned solely by
    // the AcroForm /Q rendered every field flush left.
    let flags = inherited_num(doc, id, b"Ff").unwrap_or(0.0) as u32;
    let multiline = flags & (1 << 12) != 0; // Ff bit 13
    let comb = flags & (1 << 24) != 0; // Ff bit 25
    let quadding = field_quadding(doc, id);
    let max_len = inherited_num(doc, id, b"MaxLen").unwrap_or(0.0) as usize;
    // §12.7.3.3: /DA carries the field's font, size and colour. Resolved once for
    // the field, since /DA is a field key shared by all its widgets.
    let da = field_da(doc, id);
    let (da_res, da_font) = da_font_resources(doc, da.font.as_deref());

    // A field may have several /Kids widgets, all displaying the same value
    // (§12.7.3.1), so regenerate an appearance for EACH from its own /Rect —
    // updating only the clicked widget leaves the field stale everywhere else it
    // appears. Rects are collected first so the mutable borrow for
    // make_appearance does not overlap the reads.
    let widget_ids: Vec<ObjectId> = doc
        .get_dictionary(root_id)
        .ok()
        .and_then(|d| d.get(b"Kids").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .map(|kids| kids.iter().filter_map(|k| k.as_reference().ok()).collect::<Vec<_>>())
        .filter(|v: &Vec<ObjectId>| !v.is_empty())
        .unwrap_or_else(|| vec![id]);
    let widget_rects: Vec<(ObjectId, [f64; 4])> = widget_ids
        .iter()
        .filter_map(|wid| {
            doc.get_dictionary(*wid)
                .ok()
                .and_then(|d| d.get(b"Rect").ok())
                .and_then(|o| read_rect(doc, o))
                .map(|r| (*wid, normalize_rect(r)))
        })
        .collect();
    let mut aps: Vec<(ObjectId, ObjectId)> = Vec::with_capacity(widget_rects.len());
    for (wid, r) in widget_rects {
        // Field text must read upright, so lay the appearance out in the display
        // orientation of the page the widget sits on (§12.5.2 /P). Widgets of one
        // field can be on pages with different /Rotate, hence the per-widget
        // lookup; absent /P we assume no rotation, matching previous behaviour.
        let rot = doc
            .get_dictionary(wid)
            .ok()
            .and_then(|d| d.get(b"P").ok())
            .and_then(|o| o.as_reference().ok())
            .map(|pid| page_rotation(doc, pid))
            .unwrap_or(0);
        let (w, h, apm) = display_orientation(rot, r[2] - r[0], r[3] - r[1]);
        let size = field_font_size(da.size, h);
        let content = build_text_appearance(value, w, h, size, &da_font, da.argb, quadding, multiline, comb, max_len);
        aps.push((wid, make_appearance_oriented(doc, w, h, content, da_res.clone(), apm)));
    }

    // /V is a field attribute, so it belongs on the root; the widgets carry only
    // the regenerated appearance.
    let set_root = if let Ok(dict) = doc.get_dictionary_mut(root_id) {
        dict.set("V", Object::string_literal(value));
        true
    } else { false };
    let mut set_widget = false;
    for (wid, ap_id) in aps {
        if let Ok(dict) = doc.get_dictionary_mut(wid) {
            let mut ap = Dictionary::new();
            ap.set("N", Object::Reference(ap_id));
            dict.set("AP", Object::Dictionary(ap));
            set_widget = true;
        }
    }
    if !set_root && !set_widget { return false; }
    set_need_appearances(doc);
    true
}

pub(crate) fn set_checkbox(handle: i64, widget_id: i64, on: bool) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    // Determine the "on" state name from the widget's /AP /N sub-dictionary.
    let on_state = doc
        .get_dictionary(id)
        .ok()
        .and_then(|d| d.get(b"AP").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|ap| ap.get(b"N").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|states| {
            states
                .iter()
                .map(|(k, _)| k.clone())
                .find(|k| k.as_slice() != b"Off")
        })
        .unwrap_or_else(|| b"Yes".to_vec());

    // Radio buttons: the widget belongs to a terminal field with several kid
    // widgets that must be mutually exclusive. Setting one on clears the others
    // and records the chosen export value on the field's /V.
    let field_id = terminal_field_id(doc, id);
    let sibling_ids: Vec<ObjectId> = if field_id == id {
        Vec::new()
    } else {
        doc.get_dictionary(field_id)
            .ok()
            .and_then(|pd| pd.get(b"Kids").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_array().ok())
            .map(|kids| kids.iter().filter_map(|k| k.as_reference().ok()).collect())
            .unwrap_or_default()
    };

    if sibling_ids.len() > 1 {
        // Radio group: set each kid's /AS, and the field's /V.
        for kid in &sibling_ids {
            let state = if *kid == id && on { on_state.clone() } else { b"Off".to_vec() };
            if let Ok(kd) = doc.get_dictionary_mut(*kid) {
                kd.set("AS", Object::Name(state));
            }
        }
        if let Ok(pd) = doc.get_dictionary_mut(field_id) {
            let v = if on { on_state } else { b"Off".to_vec() };
            pd.set("V", Object::Name(v));
        }
        return true;
    }

    // Single widget. /AS selects which of /AP /N's states paints (§12.5.5) and
    // lives on the widget; /V is the field's value. The two are the same
    // dictionary for a merged field+widget, and different when the widget is a
    // lone /Kids entry — where the old code wrote /V onto the widget, leaving the
    // field itself unset and the checkbox reading as unchecked on reload.
    let state = if on { on_state } else { b"Off".to_vec() };
    let mut updated = false;
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("AS", Object::Name(state.clone()));
        updated = true;
    }
    if let Ok(dict) = doc.get_dictionary_mut(field_id) {
        dict.set("V", Object::Name(state));
        updated = true;
    }
    updated
}

/// Set a Choice (`/Ch`) field's value: records `/V` and the matching `/Opt` index
/// in `/I` on the field, and builds a single-line appearance showing the
/// selection on the widget.
///
/// `value` is what the user saw, i.e. the DISPLAY string. §12.7.4.4 makes an
/// `/Opt` entry either a plain string (display == export) or a `[export display]`
/// pair, and `/V` must hold the EXPORT value; writing the display string there
/// submits the wrong data and stops matching `/Opt` on reload.
pub(crate) fn set_choice_field(handle: i64, widget_id: i64, value: &str) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    let root_id = terminal_field_id(doc, id);
    let rect = doc
        .get_dictionary(id)
        .ok()
        .and_then(|d| d.get(b"Rect").ok())
        .and_then(|o| read_rect(doc, o))
        .map(normalize_rect);
    // Find the option matching `value` by display OR export string, and take its
    // export value for /V.
    let mut opt_index = None;
    let mut export = value.to_string();
    if let Some(opts) = field_attr(doc, id, b"Opt")
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
    {
        let text = |o: &Object| match deref(doc, o).unwrap_or(o) {
            Object::String(s, _) => decode_pdf_text(s),
            _ => String::new(),
        };
        for (i, o) in opts.iter().enumerate() {
            let (exp, disp) = match deref(doc, o).unwrap_or(o) {
                Object::Array(pair) if pair.len() >= 2 => (text(&pair[0]), text(&pair[1])),
                other => {
                    let s = text(other);
                    (s.clone(), s)
                }
            };
            if disp == value || exp == value {
                opt_index = Some(i);
                export = exp;
                break;
            }
        }
    }

    let da = field_da(doc, id);
    let (da_res, da_font) = da_font_resources(doc, da.font.as_deref());
    // §12.7.4.4 makes a choice field a variable-text field, so /Q applies to it
    // exactly as it does to a text field.
    let quadding = field_quadding(doc, id);
    let ap_id = rect.map(|r| {
        // Same display-orientation handling as set_text_field (§12.5.2 /P).
        let rot = doc
            .get_dictionary(id)
            .ok()
            .and_then(|d| d.get(b"P").ok())
            .and_then(|o| o.as_reference().ok())
            .map(|pid| page_rotation(doc, pid))
            .unwrap_or(0);
        let (w, h, apm) = display_orientation(rot, r[2] - r[0], r[3] - r[1]);
        let size = field_font_size(da.size, h);
        // The widget shows the display string, not the export value.
        let content = build_text_appearance(value, w, h, size, &da_font, da.argb, quadding, false, false, 0);
        make_appearance_oriented(doc, w, h, content, da_res, apm)
    });

    // /V and /I are field keys (§12.7.3.1) and belong on the root; only /AP is
    // per-widget.
    if let Ok(dict) = doc.get_dictionary_mut(root_id) {
        dict.set("V", Object::string_literal(export));
        match opt_index {
            Some(i) => { dict.set("I", Object::Array(vec![Object::Integer(i as i64)])); }
            None => { dict.remove(b"I"); }
        }
    } else {
        return false;
    }
    if let Some(ap_id) = ap_id {
        if let Ok(dict) = doc.get_dictionary_mut(id) {
            let mut ap = Dictionary::new();
            ap.set("N", Object::Reference(ap_id));
            dict.set("AP", Object::Dictionary(ap));
        }
    }
    set_need_appearances(doc);
    true
}

/// Concatenate the visible text of every page, one blank line between pages.
fn all_pages_text(doc: &Document) -> String {
    let mut out = String::new();
    for (_num, page_id) in doc.get_pages() {
        if let Ok(pd) = interpret_page(doc, page_id) {
            let mut last_y = f32::NAN;
            for p in &pd.prims {
                if let Prim::Text { text, y, .. } = p {
                    if !last_y.is_nan() && (last_y - *y).abs() > 2.0 {
                        out.push('\n');
                    }
                    out.push_str(text);
                    last_y = *y;
                }
            }
        }
        out.push_str("\n\n");
    }
    out
}

/// Extract the document's visible text (from rendered text primitives), one
/// blank line between pages.
pub(crate) fn document_text(handle: i64) -> Option<String> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    // Same hazard as `docedit::render_page`: `interpret_page` recurses for form
    // XObjects, tiling patterns, soft-mask groups and Type 3 glyphs, and those caps
    // bound the DEPTH but not the frame SIZE, so the real headroom is whatever the
    // calling thread happens to have — a JNI thread on Android. A guard-page fault
    // is not an unwind, so the JNI `catch_unwind` cannot turn it into a failed
    // extraction; it kills the process. The stack has to be pinned here rather than
    // at the boundary because `JNIEnv` is not `Send`. A panic is re-raised with its
    // payload so that boundary still sees it, and a spawn failure falls back to the
    // calling thread.
    Some(std::thread::scope(|s| {
        match std::thread::Builder::new()
            .name("pdf-text".to_owned())
            .stack_size(crate::docedit::RENDER_STACK_BYTES)
            .spawn_scoped(s, || all_pages_text(doc))
        {
            Ok(h) => match h.join() {
                Ok(r) => r,
                Err(payload) => std::panic::resume_unwind(payload),
            },
            Err(_) => all_pages_text(doc),
        }
    }))
}

// ---------------------------------------------------------------------------
// Document outline (bookmarks)
// ---------------------------------------------------------------------------

/// Resolve a destination (array, or named) to a 0-based page index, or -1.
pub(crate) fn resolve_dest(doc: &Document, dest: &Object, page_index: &HashMap<ObjectId, i32>) -> i32 {
    let arr = match dest {
        Object::Array(a) => Some(a.clone()),
        Object::Name(n) => named_dest(doc, n),
        Object::String(s, _) => named_dest(doc, s),
        _ => None,
    };
    if let Some(a) = arr {
        if let Some(first) = a.first() {
            if let Ok(id) = first.as_reference() {
                return page_index.get(&id).copied().unwrap_or(-1);
            }
        }
    }
    -1
}

/// Look up a named destination's explicit dest array via `/Dests` and the
/// `/Names` name tree.
pub(crate) fn named_dest(doc: &Document, name: &[u8]) -> Option<Vec<Object>> {
    let catalog = doc.catalog().ok()?;
    // Old-style /Dests dictionary.
    if let Some(Object::Dictionary(dests)) = catalog.get(b"Dests").ok().and_then(|o| deref(doc, o)) {
        if let Ok(v) = dests.get(name) {
            return dest_array(doc, v);
        }
    }
    // /Names /Dests name tree.
    let root = catalog
        .get(b"Names")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"Dests").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())?;
    let mut visited = std::collections::HashSet::new();
    search_name_tree(doc, root, name, &mut visited)
}

pub(crate) fn dest_array(doc: &Document, obj: &Object) -> Option<Vec<Object>> {
    match deref(doc, obj)? {
        Object::Array(a) => Some(a.clone()),
        Object::Dictionary(d) => d.get(b"D").ok().and_then(|o| dest_array(doc, o)),
        _ => None,
    }
}

pub(crate) fn search_name_tree(
    doc: &Document,
    node: &lopdf::Dictionary,
    name: &[u8],
    visited: &mut std::collections::HashSet<ObjectId>,
) -> Option<Vec<Object>> {
    search_name_tree_at(doc, node, name, visited, 0)
}

/// `visited` alone bounds the number of nodes but not the DEPTH: a name tree
/// that is one long chain of single-kid nodes recurses once per node, so a
/// malformed file could exhaust the (small) JNI thread stack. §7.9.6 name trees
/// are balanced, so a real one is never deep.
fn search_name_tree_at(
    doc: &Document,
    node: &lopdf::Dictionary,
    name: &[u8],
    visited: &mut std::collections::HashSet<ObjectId>,
    depth: u32,
) -> Option<Vec<Object>> {
    if depth > 64 {
        return None;
    }
    if let Some(Object::Array(names)) = node.get(b"Names").ok().and_then(|o| deref(doc, o)) {
        let mut i = 0;
        while i + 1 < names.len() {
            if names[i].as_str().ok() == Some(name) {
                return dest_array(doc, &names[i + 1]);
            }
            i += 2;
        }
    }
    if let Some(Object::Array(kids)) = node.get(b"Kids").ok().and_then(|o| deref(doc, o)) {
        for kid in kids {
            if let Ok(id) = kid.as_reference() {
                if !visited.insert(id) {
                    continue;
                }
                if let Ok(child) = doc.get_dictionary(id) {
                    if let Some(r) = search_name_tree_at(doc, child, name, visited, depth + 1) {
                        return Some(r);
                    }
                }
            }
        }
    }
    None
}

/// Walk the outline linked-list/tree collecting `(level, pageIndex, title)`.
///
/// `visited` makes a circular `/Next` or `/First` terminate, and `out.len()` caps
/// the total. `level` additionally caps the RECURSION DEPTH: `visited` bounds the
/// node count but not the nesting, so a chain of thousands of single-child
/// entries would recurse once per entry and could exhaust a JNI thread's stack.
pub(crate) fn walk_outline(
    doc: &Document,
    start: Option<ObjectId>,
    level: u16,
    page_index: &HashMap<ObjectId, i32>,
    visited: &mut std::collections::HashSet<ObjectId>,
    out: &mut Vec<(u16, i32, String)>,
) {
    if level > 64 {
        return;
    }
    let mut cur = start;
    while let Some(id) = cur {
        if !visited.insert(id) || out.len() > 5000 {
            break;
        }
        let dict = match doc.get_dictionary(id) {
            Ok(d) => d,
            Err(_) => break,
        };
        let title = dict
            .get(b"Title")
            .ok()
            .and_then(|o| o.as_str().ok())
            .map(decode_pdf_text)
            .unwrap_or_default();
        let page = dict
            .get(b"Dest")
            .ok()
            .and_then(|o| deref(doc, o))
            .map(|d| resolve_dest(doc, d, page_index))
            .or_else(|| {
                dict.get(b"A")
                    .ok()
                    .and_then(|o| deref(doc, o))
                    .and_then(|o| o.as_dict().ok())
                    .and_then(|a| a.get(b"D").ok())
                    .and_then(|o| deref(doc, o))
                    .map(|d| resolve_dest(doc, d, page_index))
            })
            .unwrap_or(-1);
        out.push((level, page, title));

        if let Some(first) = dict.get(b"First").ok().and_then(|o| o.as_reference().ok()) {
            walk_outline(doc, Some(first), level + 1, page_index, visited, out);
        }
        cur = dict.get(b"Next").ok().and_then(|o| o.as_reference().ok());
    }
}

/// Serialized document outline: u32 count, then per entry
/// `u16 level, i32 pageIndex, u16 titleLen, [utf8]`.
pub(crate) fn list_outline(handle: i64) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    let outlines_id = doc
        .catalog()
        .ok()
        .and_then(|c| c.get(b"Outlines").ok())
        .and_then(|o| o.as_reference().ok())?;
    let pages = doc.get_pages();
    let mut page_index = HashMap::new();
    for (i, (_, id)) in pages.iter().enumerate() {
        page_index.insert(*id, i as i32);
    }
    let first = doc
        .get_dictionary(outlines_id)
        .ok()
        .and_then(|d| d.get(b"First").ok())
        .and_then(|o| o.as_reference().ok());
    let mut items = Vec::new();
    let mut visited = std::collections::HashSet::new();
    walk_outline(doc, first, 0, &page_index, &mut visited, &mut items);

    let mut buf = Vec::new();
    buf.extend_from_slice(&(items.len() as u32).to_le_bytes());
    for (level, page, title) in items {
        buf.extend_from_slice(&level.to_le_bytes());
        buf.extend_from_slice(&page.to_le_bytes());
        let b = title.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

#[cfg(test)]
mod da_tests {
    use super::{build_text_appearance, field_font_size};
    use crate::*;

    fn body(da: &[u8], h: f64) -> String {
        let d = parse_da(da);
        let font = d.font.clone().unwrap_or_else(|| b"F1".to_vec());
        let size = field_font_size(d.size, h);
        String::from_utf8(build_text_appearance(
            "Ab", 100.0, h, size, &font, d.argb, 0, false, false, 0,
        ))
        .expect("utf8")
    }

    /// §12.7.4.3: a font size of ZERO in `/DA` means AUTO-SIZE to fit, NOT zero.
    /// Reading the size straight out of `/DA` renders every such field as
    /// INVISIBLE text, which is strictly worse than ignoring `/DA` altogether, so
    /// size 0 must fall through to the height-derived size.
    #[test]
    fn zero_da_size_means_auto_not_invisible() {
        assert_eq!(parse_da(b"/Helv 0 Tf 0 g").size, 0.0, "0 is reported verbatim");
        assert_eq!(field_font_size(0.0, 24.0), 14.0);
        assert_eq!(field_font_size(0.0, 4.0), 6.0, "clamped up, never zero");
        assert_eq!(field_font_size(0.0, 200.0), 14.0, "clamped down");
        assert_eq!(field_font_size(9.5, 24.0), 9.5, "an explicit size wins");
        for h in [1.0, 4.0, 12.0, 24.0, 1000.0] {
            assert!(field_font_size(0.0, h) >= 6.0, "auto size collapsed at h={h}");
        }
        let c = body(b"/Helv 0 Tf 0 g", 24.0);
        assert!(c.contains("/Helv 14.000 Tf"), "auto-sized appearance was: {c}");
    }

    /// §12.7.3.3: `/DA` defines the field's font, size and colour. The generator
    /// hardcoded `0 0 0 rg /F1`, so a filled field came out in the wrong font,
    /// size and colour compared with Acrobat.
    #[test]
    fn da_font_size_and_colour_reach_the_content_stream() {
        let c = body(b"/Helv 11 Tf 1 0 0 rg", 24.0);
        assert!(c.contains("1.000 0.000 0.000 rg"), "colour: {c}");
        assert!(c.contains("/Helv 11.000 Tf"), "font and size: {c}");
        assert_eq!(parse_da(b"0.5 g").argb, gray_to_argb(0.5));
        assert_eq!(parse_da(b"0 0 1 0 k").argb, cmyk_to_argb(0.0, 0.0, 1.0, 0.0));
        assert_eq!(parse_da(b"1 0 0 rg 0 g").argb, gray_to_argb(0.0), "later wins");
        // No colour operator keeps the black the generator used to hardcode.
        assert_eq!(parse_da(b"/Helv 12 Tf").argb, 0xFF00_0000);
    }

    /// The font name is interpolated straight into `/<name> <size> Tf`, so a name
    /// carrying delimiters could close the operand and inject operators. §7.3.5
    /// restricts names to regular characters, so anything else falls back to the
    /// substitute.
    #[test]
    fn da_font_name_cannot_inject_operators() {
        for bad in [&b"/F1) Tj 0 0 1 rg ( 12 Tf"[..], &b"/(evil 12 Tf"[..], &b"/ 12 Tf"[..]] {
            assert!(
                parse_da(bad).font.is_none(),
                "accepted unsafe name from {:?}",
                String::from_utf8_lossy(bad)
            );
        }
        assert_eq!(parse_da(b"/Helv-Bold.1+2 12 Tf").font.as_deref(), Some(&b"Helv-Bold.1+2"[..]));
        // A malformed size must not become a negative or non-finite Tf operand.
        assert_eq!(parse_da(b"/Helv -5 Tf").size, 0.0, "negative falls to the auto path");
        assert_eq!(parse_da(b"/Helv nope Tf").size, 0.0);
    }

    /// A comb field lays out one `Tm` per cell, and `Tm` REPLACES the text matrix
    /// (§9.4.2) rather than concatenating it — so the vertical offset must be
    /// repeated on every one. Setting it once up front dropped the whole row to
    /// y=0, sitting the characters on the bottom edge with descenders clipped.
    #[test]
    fn comb_cells_keep_their_vertical_offset() {
        let c = String::from_utf8(build_text_appearance(
            "AB", 100.0, 20.0, 12.0, b"F1", 0xFF00_0000, 0, false, true, 5,
        ))
        .expect("utf8");
        // (20 - 12) / 2 == 4 for every cell.
        assert_eq!(c.matches("4.00 Tm").count(), 2, "both cells centered: {c}");
        assert!(!c.contains(" 0.00 Tm"), "a cell fell to the bottom edge: {c}");
    }

    /// Splitting on both `\r` and `\n` turns each CRLF into an extra empty line,
    /// double-spacing a multiline field.
    #[test]
    fn multiline_crlf_does_not_double_space() {
        let c = String::from_utf8(build_text_appearance(
            "one\r\ntwo", 200.0, 60.0, 10.0, b"F1", 0xFF00_0000, 0, true, false, 0,
        ))
        .expect("utf8");
        assert_eq!(c.matches("Tj").count(), 2, "expected exactly two lines: {c}");
    }

    /// §12.6.4.2: a GoTo action's `/D` "shall be" a name, a byte string or an
    /// array — the first two naming a destination resolved through `/Dests` or
    /// the `/Names` name tree (§12.3.2.3). Accepting only the array form left
    /// `dest_page` at -1, and `list_links` drops any record with neither a page
    /// nor a URI, so every named-destination link was silently untappable.
    #[test]
    fn a_goto_link_to_a_named_destination_resolves() {
        for (name, dest) in [
            ("array", Object::Array(vec![])),           // placeholder, replaced below
            ("name", name_obj("Chapter2")),
            ("string", Object::string_literal("Chapter2")),
        ] {
            let mut doc = Document::with_version("1.7");
            let pages_id = doc.new_object_id();
            let p0 = doc.add_object(dictionary! {
                "Type" => name_obj("Page"), "Parent" => Object::Reference(pages_id),
                "MediaBox" => rect_obj([0.0, 0.0, 612.0, 792.0]),
            });
            let p1 = doc.add_object(dictionary! {
                "Type" => name_obj("Page"), "Parent" => Object::Reference(pages_id),
                "MediaBox" => rect_obj([0.0, 0.0, 612.0, 792.0]),
            });
            let target = Object::Array(vec![Object::Reference(p1), name_obj("Fit")]);
            let dest = if name == "array" { target.clone() } else { dest };
            let action = doc.add_object(dictionary! { "S" => name_obj("GoTo"), "D" => dest });
            let link = doc.add_object(dictionary! {
                "Type" => name_obj("Annot"), "Subtype" => name_obj("Link"),
                "Rect" => rect_obj([10.0, 10.0, 100.0, 30.0]),
                "A" => Object::Reference(action),
            });
            if let Ok(Object::Dictionary(d)) = doc.get_object_mut(p0) {
                d.set("Annots", Object::Array(vec![Object::Reference(link)]));
            }
            doc.objects.insert(
                pages_id,
                Object::Dictionary(dictionary! {
                    "Type" => name_obj("Pages"),
                    "Kids" => Object::Array(vec![Object::Reference(p0), Object::Reference(p1)]),
                    "Count" => 2,
                }),
            );
            let names = doc.add_object(dictionary! {
                "Dests" => dictionary! {
                    "Names" => Object::Array(vec![Object::string_literal("Chapter2"), target]),
                },
            });
            let cat = doc.add_object(dictionary! {
                "Type" => name_obj("Catalog"),
                "Pages" => Object::Reference(pages_id),
                "Names" => Object::Reference(names),
            });
            doc.trailer.set("Root", cat);
            let handle = next_handle();
            registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);

            let buf = list_links(handle, 0).expect("links");
            let count = u32::from_le_bytes(buf[0..4].try_into().expect("count"));
            assert_eq!(count, 1, "/D as {name}: the link was dropped entirely");
            let dest_page = i32::from_le_bytes(buf[20..24].try_into().expect("dest"));
            assert_eq!(dest_page, 1, "/D as {name}: resolved to the wrong page");
            close_document(handle);
        }
    }

    /// A document with one AcroForm `/DR /Font /Fx` entry.
    fn doc_with_dr_font(font: Dictionary) -> Document {
        let mut doc = Document::with_version("1.7");
        let fid = doc.add_object(font);
        let acro = doc.add_object(dictionary! {
            "DR" => dictionary! { "Font" => dictionary! { "Fx" => fid } },
        });
        let pages = doc.add_object(dictionary! { "Type" => "Pages", "Kids" => Object::Array(vec![]), "Count" => 0 });
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages, "AcroForm" => acro });
        doc.trailer.set("Root", cat);
        doc
    }

    /// §12.7.3.3 resolves the `/DA` font name against `/DR`. It is only safe to
    /// adopt when the generated content — a plain literal string — can address it:
    /// a composite (Type0) font takes multi-byte CIDs and a `/Differences` or
    /// symbolic encoding remaps the bytes, both of which render the value as
    /// garbage. Those must keep the Helvetica substitute, which is legible.
    #[test]
    fn only_safely_addressable_dr_fonts_are_adopted() {
        let adopted = |font: Dictionary| {
            let doc = doc_with_dr_font(font);
            super::da_font_resources(&doc, Some(b"Fx")).1 == b"Fx".to_vec()
        };
        assert!(adopted(dictionary! { "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Times-Roman" }));
        assert!(adopted(
            dictionary! { "Type" => "Font", "Subtype" => "TrueType", "Encoding" => "WinAnsiEncoding" }
        ));
        assert!(
            !adopted(dictionary! { "Type" => "Font", "Subtype" => "Type0", "Encoding" => "Identity-H" }),
            "a composite font would render the value as multi-byte garbage"
        );
        assert!(
            !adopted(dictionary! {
                "Type" => "Font", "Subtype" => "Type1",
                "Encoding" => dictionary! { "Differences" => Object::Array(vec![]) },
            }),
            "a /Differences encoding remaps the bytes to unrelated glyphs"
        );
        assert!(
            !adopted(dictionary! { "Type" => "Font", "Subtype" => "Type1", "Encoding" => "Identity-H" }),
            "an unrecognised encoding is not assumed to be Latin text"
        );

        // A name that /DR does not define, and no name at all, both fall back.
        let doc = doc_with_dr_font(dictionary! { "Type" => "Font", "Subtype" => "Type1" });
        assert_eq!(super::da_font_resources(&doc, Some(b"Nope")).1, b"F1".to_vec());
        assert_eq!(super::da_font_resources(&doc, None).1, b"F1".to_vec());
        // When adopted, the font must actually be in the appearance's resources,
        // or the Tf name would not resolve and nothing would paint.
        let (res, name) = super::da_font_resources(&doc, Some(b"Fx"));
        let fonts = res.get(b"Font").and_then(|o| o.as_dict()).expect("/Font");
        assert!(fonts.get(&name).is_ok(), "adopted font missing from resources");
        assert!(fonts.get(b"F1").is_ok(), "substitute must stay available");
    }

    /// §12.7.3.1 Table 220 marks `/Ff`, and §12.7.4.3 Table 222 marks `/Q` and
    /// `/MaxLen`, "Optional; inheritable"; §12.7.2 Table 218 makes the AcroForm
    /// `/Q` the document-wide default. A generator that reads them off a single
    /// dictionary renders a wrapped value as one clipped line and ignores the
    /// form's alignment entirely.
    #[test]
    fn inheritable_field_attributes_reach_the_generated_appearance() {
        // widget -> terminal field (/FT, /Ff, /MaxLen) -> group -> AcroForm /Q.
        let mut doc = Document::with_version("1.7");
        let group_id = doc.new_object_id();
        let field_id = doc.new_object_id();
        let widget = doc.add_object(dictionary! {
            "Type" => name_obj("Annot"), "Subtype" => name_obj("Widget"),
            "Rect" => rect_obj([0.0, 0.0, 200.0, 60.0]),
            "Parent" => Object::Reference(field_id),
        });
        doc.objects.insert(
            field_id,
            Object::Dictionary(dictionary! {
                "FT" => name_obj("Tx"),
                "Ff" => 1 << 12, // Multiline
                "Parent" => Object::Reference(group_id),
                "Kids" => Object::Array(vec![Object::Reference(widget)]),
            }),
        );
        doc.objects.insert(
            group_id,
            Object::Dictionary(dictionary! { "Kids" => Object::Array(vec![Object::Reference(field_id)]) }),
        );
        let acro = doc.add_object(dictionary! {
            "Q" => 2, // right-aligned, document-wide
            "Fields" => Object::Array(vec![Object::Reference(group_id)]),
        });
        let pages = doc.add_object(dictionary! { "Type" => "Pages", "Kids" => Object::Array(vec![]), "Count" => 0 });
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages, "AcroForm" => acro });
        doc.trailer.set("Root", cat);

        assert_eq!(super::inherited_num(&doc, widget, b"Ff"), Some(4096.0), "/Ff up /Parent");

        let handle = next_handle();
        registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);
        assert!(set_text_field(handle, encode_id(widget), "alpha beta gamma delta epsilon zeta"));

        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("doc");
        let ap = doc
            .get_dictionary(widget)
            .and_then(|d| d.get(b"AP"))
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .and_then(|ap| ap.get(b"N").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_stream().ok())
            .expect("/AP /N");
        let c = String::from_utf8_lossy(&ap.content).into_owned();
        assert!(c.matches("Tj").count() > 1, "inherited Multiline never wrapped: {c}");
        // Right-aligned from the AcroForm /Q, so no line starts at the left inset.
        assert!(!c.contains("1 0 0 1 2.00 "), "AcroForm /Q 2 ignored, drew flush left: {c}");
        drop(reg);
        close_document(handle);
    }

    /// §12.7.4.3 Table 226 bit 25: Comb is "meaningful only if the MaxLen entry
    /// is present ... and if the Multiline, Password, and FileSelect flags are
    /// clear". Applied regardless, it chops a multiline value into `MaxLen`
    /// one-character cells on a single row.
    #[test]
    fn comb_is_ignored_when_the_field_is_also_multiline() {
        let combed = build_text_appearance("AB", 100.0, 20.0, 12.0, b"F1", 0, 0, false, true, 5);
        let wrapped = build_text_appearance("AB", 100.0, 20.0, 12.0, b"F1", 0, 0, true, true, 5);
        assert_eq!(String::from_utf8_lossy(&combed).matches("Tj").count(), 2, "comb: one Tj per cell");
        assert_eq!(
            String::from_utf8_lossy(&wrapped).matches("Tj").count(),
            1,
            "multiline + comb must lay out as one line, not per-character cells"
        );
    }
}

// ---------------------------------------------------------------------------
// Full-text search
// ---------------------------------------------------------------------------
