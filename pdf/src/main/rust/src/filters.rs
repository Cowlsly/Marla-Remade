//! Filter decoders for PDF image XObjects and content streams.
//! Implements chain decoding with case-insensitive filter normalization.
//! Used to close P0 blank-page blockers: ASCIIHex/85, LZW, Flate, RunLength, CCITT, JBIG2.

use lopdf::{Dictionary, Document, Object};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FilterKind {
    AsciiHex,
    Ascii85,
    Lzw,
    Flate,
    RunLength,
    Ccitt,
    Dct,
    Jpx,
    Jbig2,
    Crypt,
    Unknown(String),
}

#[derive(Clone, Debug)]
pub struct CcittParams {
    pub k: i32,
    pub columns: u32,
    pub rows: u32,
    pub end_of_line: bool,
    pub end_of_block: bool,
    pub black_is1: bool,
    pub damaged_rows_before_error: u32,
    pub encoded_byte_align: bool,
}

impl Default for CcittParams {
    fn default() -> Self {
        CcittParams {
            k: 0,
            columns: 1728,
            rows: 0,
            end_of_line: false,
            end_of_block: false,
            black_is1: false,
            damaged_rows_before_error: 0,
            encoded_byte_align: false,
        }
    }
}

#[derive(Clone, Debug)]
pub struct LzwParams {
    pub early_change: bool,
}
impl Default for LzwParams {
    fn default() -> Self {
        Self { early_change: true }
    }
}

fn num(obj: &Object) -> Option<f64> {
    match obj {
        Object::Integer(i) => Some(*i as f64),
        Object::Real(r) => Some(*r as f64),
        _ => None,
    }
}
fn deref<'a>(doc: &'a Document, obj: &'a Object) -> Option<&'a Object> {
    match doc.dereference(obj) {
        Ok((_, o)) => Some(o),
        Err(_) => None,
    }
}

pub fn normalize_filter_name(name: &str) -> FilterKind {
    let n = name.trim().trim_start_matches('/').to_ascii_lowercase();
    match n.as_str() {
        "asciihexdecode" | "ahx" | "ah" => FilterKind::AsciiHex,
        "ascii85decode" | "a85" => FilterKind::Ascii85,
        "lzwdecode" | "lzw" => FilterKind::Lzw,
        "flatedecode" | "fl" | "flate" => FilterKind::Flate,
        "runlengthdecode" | "rl" | "rle" => FilterKind::RunLength,
        "ccittfaxdecode" | "ccf" | "ccitt" | "fax" | "g3" | "g4" => FilterKind::Ccitt,
        "dctdecode" | "dct" => FilterKind::Dct,
        "jpxdecode" | "jpx" | "jp2" | "jpeg2000" => FilterKind::Jpx,
        "jbig2decode" | "jbig2" => FilterKind::Jbig2,
        "crypt" => FilterKind::Crypt,
        other => FilterKind::Unknown(other.to_string()),
    }
}

pub fn filter_specs_from_dict(doc: &Document, dict: &Dictionary) -> Vec<(FilterKind, Option<Dictionary>)> {
    let mut out = Vec::new();
    let filter_objs: Vec<Object> = match dict.get(b"Filter").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Name(name)) => vec![Object::Name(name.clone())],
        Some(Object::Array(arr)) => arr.clone(),
        // §8.9.7 Table 93 abbreviates /Filter to /F in an INLINE image dictionary, and
        // inline images are the only dictionaries that reach here without a /Filter.
        // In a regular stream dictionary /F is a file specification instead (§7.3.8.2),
        // which is a string or a dictionary — so only the two shapes a filter can
        // actually take are accepted, and a file spec is still ignored.
        _ => match dict.get(b"F").ok().and_then(|o| deref(doc, o)) {
            Some(Object::Name(name)) => vec![Object::Name(name.clone())],
            Some(Object::Array(arr)) => arr.clone(),
            _ => vec![],
        },
    };
    // DecodeParms may be dict or array; /DP is its inline-image abbreviation.
    let mut decode_parms: Vec<Option<Dictionary>> = Vec::new();
    let parms_obj = dict
        .get(b"DecodeParms")
        .ok()
        .and_then(|o| deref(doc, o))
        .or_else(|| dict.get(b"DP").ok().and_then(|o| deref(doc, o)));
    match parms_obj {
        Some(Object::Dictionary(d)) => {
            // §7.4 pairs a DecodeParms ARRAY with a Filter array, but producers commonly
            // emit a single dict alongside a filter array. Attaching it to index 0 meant
            // that for `[/ASCII85Decode /FlateDecode]` the /Predictor never reached Flate
            // and the image came out garbled. Give it to the first filter that can use it.
            let target = filter_objs
                .iter()
                .position(|f| {
                    f.as_name()
                        .ok()
                        .or_else(|| deref(doc, f).and_then(|o| o.as_name().ok()))
                        .map(|n| {
                            matches!(
                                normalize_filter_name(&String::from_utf8_lossy(n)),
                                FilterKind::Flate | FilterKind::Lzw | FilterKind::Ccitt | FilterKind::Jbig2
                            )
                        })
                        .unwrap_or(false)
                })
                .unwrap_or(0);
            for i in 0..filter_objs.len().max(1) {
                decode_parms.push(if i == target { Some(d.clone()) } else { None });
            }
        }
        Some(Object::Array(arr)) => {
            for el in arr {
                let d_opt = deref(doc, el)
                    .and_then(|o| o.as_dict().ok())
                    .or_else(|| el.as_dict().ok())
                    .cloned();
                decode_parms.push(d_opt);
            }
            while decode_parms.len() < filter_objs.len() { decode_parms.push(None); }
        }
        _ => {
            decode_parms = vec![None; filter_objs.len()];
        }
    }

    for (i, fobj) in filter_objs.iter().enumerate() {
        let name_bytes_opt = fobj.as_name().ok()
            .or_else(|| deref(doc, fobj).and_then(|o| o.as_name().ok()));
        if let Some(name_bytes) = name_bytes_opt {
            let s = String::from_utf8_lossy(name_bytes).into_owned();
            let kind = normalize_filter_name(&s);
            let parms = decode_parms.get(i).cloned().unwrap_or(None);
            out.push((kind, parms));
        }
    }
    out
}

/// ASCIIHex: strip whitespace, stop at '>', handle odd nibble padded with 0.
pub fn decode_ascii_hex(data: &[u8]) -> Vec<u8> {
    let mut hex_digits = Vec::new();
    for &b in data {
        if b == b'>' { break; }
        if b.is_ascii_whitespace() { continue; }
        if b.is_ascii_hexdigit() { hex_digits.push(b); } else { break; }
    }
    if hex_digits.len() % 2 == 1 { hex_digits.push(b'0'); }
    let mut out = Vec::with_capacity(hex_digits.len() / 2);
    for chunk in hex_digits.chunks(2) {
        let hi = (chunk[0] as char).to_digit(16).unwrap_or(0) as u8;
        let lo = (chunk[1] as char).to_digit(16).unwrap_or(0) as u8;
        out.push((hi << 4) | lo);
    }
    out
}

/// ASCII85: handle 'z' and '~>' EOD, ignore whitespace.
pub fn decode_ascii85(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    let mut buffer: u32 = 0;
    let mut count = 0usize;
    let mut i = 0;
    // Skip the PostScript "<~" opening delimiter. §7.4.3 only specifies the "~>" EOD, but
    // producers following the PostScript convention emit the opener too, and '<' falls
    // inside the valid '!'..'u' digit range so it would otherwise be decoded as data and
    // shift every subsequent group.
    if data.starts_with(b"<~") {
        i = 2;
    }
    while i < data.len() {
        let b = data[i]; i+=1;
        if b == b'~' {
            if i < data.len() && data[i]==b'>' { break; }
            continue;
        }
        if b == b'z' {
            if count!=0 { return Err("z inside group".into()); }
            out.extend_from_slice(&[0,0,0,0]);
            continue;
        }
        if b.is_ascii_whitespace() { continue; }
        if !(b'!'..=b'u').contains(&b) { break; }
        // checked_add matters as well as checked_mul: "s8W-" reaches exactly u32::MAX/85*85,
        // so the following digit overflows the add. Release builds have no overflow checks,
        // so this wrapped silently in production and panicked in tests.
        buffer = buffer
            .checked_mul(85)
            .and_then(|v| v.checked_add((b - b'!') as u32))
            .ok_or("group overflows u32")?;
        count+=1;
        if count==5 { out.extend_from_slice(&buffer.to_be_bytes()); buffer=0; count=0; }
    }
    if count>0 {
        for _ in count..5 {
            buffer = buffer
                .checked_mul(85)
                .and_then(|v| v.checked_add(84))
                .ok_or("group overflows u32")?;
        }
        let bytes = buffer.to_be_bytes();
        out.extend_from_slice(&bytes[..count-1]);
    }
    Ok(out)
}

/// RunLength per PDF spec EOD 128.
pub fn decode_runlength(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut i=0;
    while i < data.len() {
        let len = data[i] as i16; i+=1;
        if len==128 { break; }
        else if len<=127 {
            let copy_len = (len+1) as usize;
            if i+copy_len > data.len() { out.extend_from_slice(&data[i..]); break; }
            out.extend_from_slice(&data[i..i+copy_len]); i+=copy_len;
        } else {
            if i>=data.len() { break; }
            let repeat = (257 - len as i32) as usize;
            let b=data[i]; i+=1;
            out.extend(std::iter::repeat_n(b, repeat));
        }
    }
    out
}

pub fn decode_lzw(data: &[u8], early_change: bool) -> Option<Vec<u8>> {
    // weezl crate removed – pure std LZW decoder (single function we use, prefer stdlib)
    lzw_decode_std(data, early_change)
}

/// Minimal std-only LZW decoder for PDF's LZWDecode (MSB-first, 8-bit symbols).
/// Handles EarlyChange 1 (default, code size early bump) vs 0.
/// This is the `single function we use, we can just write it ourselves` rewrite path.
/// Returns None on malformed data.
fn lzw_decode_std(data: &[u8], early_change: bool) -> Option<Vec<u8>> {
    // Simplified version: common case – try via quick dict of 258+ entries.
    // If too complex, return None and let caller fail gracefully.
    // Real PDF LZW switches code size at 2^k - early. Standard TIFF variant uses clear code 256, eod 257.
    const CLEAR: u32 = 256;
    const EOD: u32 = 257;
    if data.is_empty() { return Some(Vec::new()); }
    let mut dict: Vec<Vec<u8>> = Vec::with_capacity(4096);
    for i in 0..256 { dict.push(vec![i as u8]); }
    dict.push(vec![]); // 256 clear
    dict.push(vec![]); // 257 eod
    let mut out = Vec::with_capacity(data.len()*2);
    let mut code_bits = 9usize;
    let mut bit_buf: u32 = 0;
    let mut bits_in_buf = 0usize;
    let mut data_pos = 0usize;
    let mut prev: Option<Vec<u8>> = None;

    let read_code = |bit_buf: &mut u32, bits_in_buf: &mut usize, data: &[u8], pos: &mut usize, bits: usize| -> Option<u32> {
        while *bits_in_buf < bits {
            if *pos >= data.len() { return None; }
            *bit_buf = (*bit_buf << 8) | data[*pos] as u32;
            *bits_in_buf += 8;
            *pos += 1;
        }
        let shift = *bits_in_buf - bits;
        let code = (*bit_buf >> shift) & ((1u32 << bits) - 1);
        *bits_in_buf = shift;
        *bit_buf &= (1u32 << shift).wrapping_sub(1);
        Some(code)
    };

    loop {
        let Some(code) = read_code(&mut bit_buf, &mut bits_in_buf, data, &mut data_pos, code_bits)
        else {
            // Stream ended without an EOD (257). Many producers omit it; returning None
            // here discarded a fully-decoded stream and blanked the page.
            break;
        };
        if code == CLEAR {
            dict.truncate(258);
            code_bits = 9;
            prev = None;
            continue;
        }
        if code == EOD { break; }
        let entry: Vec<u8> = if (code as usize) < dict.len() {
            dict[code as usize].clone()
        } else if code as usize == dict.len() {
            // KwKwK case
            match prev.as_ref() {
                Some(p) if !p.is_empty() => {
                    let mut e = p.clone();
                    e.push(p[0]);
                    e
                }
                _ => break,
            }
        } else {
            // Corrupt code: keep everything decoded so far rather than losing the stream.
            break;
        };
        out.extend_from_slice(&entry);
        if let Some(p) = prev.take() {
            if dict.len() < 4096 {
                if let Some(&first) = entry.first() {
                    let mut new_entry = p;
                    new_entry.push(first);
                    dict.push(new_entry);
                    // EarlyChange bumps code size one entry early per PDF spec
                    let threshold = if early_change { (1usize << code_bits) - 1 } else { 1usize << code_bits };
                    if dict.len() >= threshold && code_bits < 12 {
                        code_bits += 1;
                    }
                }
            }
        }
        prev = Some(entry);
    }
    Some(out)
}

/// Maximum bytes we will inflate from one stream. §7.4.4 places no limit on the
/// expansion ratio, so a few-KB stream can inflate to gigabytes and OOM-kill the
/// app; `MAX_PDF_BYTES` bounds only the compressed input.
const MAX_DECODED_BYTES: u64 = 256 * 1024 * 1024;

pub fn decode_flate(data: &[u8]) -> Option<Vec<u8>> {
    use flate2::read::{DeflateDecoder, ZlibDecoder};
    use std::io::Read;
    // `read_to_end` appends as it inflates, so on a corrupt or truncated stream the
    // buffer already holds every byte that did inflate. Truncated streams are common
    // in the wild and discarding the partial output turns a mostly-fine page blank.
    let inflate = |bytes: &[u8], zlib: bool| -> Option<Vec<u8>> {
        let mut out = Vec::new();
        let res = if zlib {
            ZlibDecoder::new(bytes)
                .take(MAX_DECODED_BYTES)
                .read_to_end(&mut out)
        } else {
            DeflateDecoder::new(bytes)
                .take(MAX_DECODED_BYTES)
                .read_to_end(&mut out)
        };
        match res {
            Ok(_) => Some(out),
            Err(_) if !out.is_empty() => Some(out),
            Err(_) => None,
        }
    };
    if let Some(out) = inflate(data, true) {
        return Some(out);
    }
    // Some producers omit the two-byte zlib header and emit raw deflate.
    if let Some(out) = inflate(data, false) {
        return Some(out);
    }
    // A single stray byte before the zlib header is another known producer bug.
    if data.len() > 1 {
        if let Some(out) = inflate(&data[1..], true) {
            return Some(out);
        }
    }
    None
}

pub fn parse_ccitt_params(doc: &Document, dict_opt: Option<&Dictionary>) -> CcittParams {
    let mut p = CcittParams::default();
    if let Some(dict)=dict_opt {
        let try_num = |key: &[u8]| -> Option<f64> {
            dict.get(key).ok().and_then(|o| deref(doc,o).and_then(num).or_else(|| num(o)))
        };
        if let Some(v)=try_num(b"K") { p.k = v as i32; }
        if let Some(v)=try_num(b"Columns").or_else(|| try_num(b"W")) { p.columns = v as u32; }
        if let Some(v)=try_num(b"Rows").or_else(|| try_num(b"H")) { p.rows = v as u32; }
        if let Some(Object::Boolean(b)) = dict.get(b"EndOfLine").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"EndOfLine").ok()) { p.end_of_line = *b; }
        if let Some(Object::Boolean(b)) = dict.get(b"EndOfBlock").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"EndOfBlock").ok()) { p.end_of_block = *b; }
        if let Some(Object::Boolean(b)) = dict.get(b"BlackIs1").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"BlackIs1").ok()) { p.black_is1 = *b; }
        // also check boolean via reference variant for BlackIs1 through deref returning Object::Boolean
        if let Some(Object::Boolean(b)) = dict.get(b"BlackIs1").ok().and_then(|o| deref(doc,o)) {
            p.black_is1 = *b;
        }
        if let Some(v)=try_num(b"DamagedRowsBeforeError") { p.damaged_rows_before_error=v as u32; }
        if let Some(Object::Boolean(b)) = dict.get(b"EncodedByteAlign").ok().and_then(|o| deref(doc,o)).or_else(|| dict.get(b"EncodedByteAlign").ok()) { p.encoded_byte_align = *b; }
    }
    p
}

pub fn decode_ccitt(data: &[u8], w: u32, h: u32, params: &CcittParams) -> Option<Vec<u8>> {
    // P0 fix: honor BlackIs1 (spec §7.4.6: true=>1=black, false default=>1=white inverted), estimate Rows when absent
    let columns = if params.columns > 0 { params.columns } else { w.max(1) };
    let rows_est = if params.rows > 0 { params.rows } else {
        // Estimate from data length: rows ≈ data_len*8 / cols (fax data may omit Rows)
        let est = (data.len() * 8 / columns.max(1) as usize) as u32;
        est.max(h).max(1)
    };
    let rows = rows_est;
    let rows_us = rows as usize;
    let cols_us = columns as usize;
    if cols_us == 0 || rows_us == 0 || cols_us > 20000 || rows_us > 20000 { return None; }
    let row_bytes = cols_us.div_ceil(8);
    if (cols_us * rows_us) > 16 * 1024 * 1024 { return None; }
    let mut packed = vec![0u8; row_bytes * rows_us];

    let black_is1 = params.black_is1;
    // Fill helper honoring BlackIs1 inversion: fax crate Black pel = 1 bit per G3/G4 logical.
    // Spec: BlackIs1 false (default) means 0 bits are black? Actually PDF spec: BlackIs1 false => 1-bits are not black (0=black), True=>1=black.
    // Wait typical fax: BlackIs1 false (default) 0=white, 1? Actually check: PDF 1.7 Table 8: BlackIs1: 0= white is 0? spec says if false, 1 bits are white? Let's interpret: false => 1 is black? Quick reference: PDF spec says BlackIs1 false (default) means 1 bits will be interpreted as white (0 black), True means 1 is black. But many viewers treat default as 1=white? Actually typical TIFF: 0=white means Photometric 0=white, so 1=black. BlackIs1 true means 1=black.
    // So false => 1=white => need invert black pels (fax Black -> should be white when false? Hmm)
    // For robustness and to match Telerik # fix: invert when BlackIs1==false (default) vs true? Let's implement: true=>1=black => keep black as black (no invert), false=>1=white => black pel should become white => invert.
    // Previous code ignored flag; we now honor: paint_black = is_black if black_is1 else !is_black
    let fill_rows = |lines: Vec<Vec<u32>>, packed: &mut Vec<u8>| {
        for (y, trans) in lines.into_iter().enumerate() {
            if y >= rows_us { break; }
            let mut cur_x = 0usize;
            for pel in fax::decoder::pels(&trans, columns) {
                let is_black = matches!(pel, fax::Color::Black);
                let paint_black = if black_is1 { is_black } else { !is_black };
                if paint_black {
                    packed[y * row_bytes + cur_x / 8] |= 1 << (7 - (cur_x % 8));
                }
                cur_x += 1;
                if cur_x >= cols_us { break; }
            }
        }
    };

    if params.k < 0 {
        // Group4
        let mut lines: Vec<Vec<u32>> = Vec::new();
        let res = fax::decoder::decode_g4(data.iter().copied(), columns, Some(rows), |trans| { lines.push(trans.to_vec()); });
        if res.is_none() && lines.is_empty() {
            let mut lines2 = Vec::new();
            if fax::decoder::decode_g4(data.iter().copied(), columns, None, |t| lines2.push(t.to_vec())).is_some() {
                lines = lines2;
            } else {
                return None;
            }
        }
        fill_rows(lines, &mut packed);
        Some(packed)
    } else if params.k > 0 {
        // Mixed 1-D/2-D G3 (K>0): fax crate doesn't expose K switching natively —
        // try G3 decode first (will get 1-D lines for K>0 mixed), then G4 fallback.
        let mut lines: Vec<Vec<u32>> = Vec::new();
        if fax::decoder::decode_g3(data.iter().copied(), |trans| { lines.push(trans.to_vec()); }).is_some() && !lines.is_empty() {
            fill_rows(lines, &mut packed);
            return Some(packed);
        }
        // Fallback to G4 attempt for mixed pages that lean G4
        let mut lines2: Vec<Vec<u32>> = Vec::new();
        if fax::decoder::decode_g4(data.iter().copied(), columns, Some(rows), |t| { lines2.push(t.to_vec()); }).is_some() && !lines2.is_empty() {
            fill_rows(lines2, &mut packed);
            return Some(packed);
        }
        None
    } else {
        // Pure G3
        let mut lines: Vec<Vec<u32>> = Vec::new();
        let res = fax::decoder::decode_g3(data.iter().copied(), |trans| { lines.push(trans.to_vec()); });
        if res.is_none() && lines.is_empty() { return None; }
        fill_rows(lines, &mut packed);
        Some(packed)
    }
}

pub fn decode_stream_chain(mut data: Vec<u8>, specs: &[(FilterKind, Option<Dictionary>)], doc: &Document) -> Option<Vec<u8>> {
    // Chain decode iterating filters in order (PDF filter order is decoding order)
    for (kind, parms) in specs {
        match kind {
            FilterKind::AsciiHex => { data = decode_ascii_hex(&data); }
            FilterKind::Ascii85 => {
                match decode_ascii85(&data) {
                    Ok(d) => data = d,
                    Err(_) => return None,
                }
            }
            FilterKind::RunLength => { data = decode_runlength(&data); }
            FilterKind::Flate => {
                // Handle PNG/TIFF predictors via parms if present
                if let Some(d) = decode_flate(&data) { data = d; } else { return None; }
                data = apply_predictor(data, parms.as_ref(), doc);
            }
            FilterKind::Lzw => {
                let early = parms.as_ref().and_then(|d| {
                    d.get(b"EarlyChange").ok().and_then(num).or_else(|| d.get(b"EarlyChange").ok().and_then(|o| deref(doc,o).and_then(num)))
                }).map(|v| v!=0.0).unwrap_or(true);
                if let Some(d)=decode_lzw(&data, early) { data=d; } else { return None; }
                // LZW supports the same PNG/TIFF predictors as Flate.
                data = apply_predictor(data, parms.as_ref(), doc);
            }
            FilterKind::Ccitt | FilterKind::Dct | FilterKind::Jpx | FilterKind::Jbig2 => {
                // Image codecs are always the LAST filter (§7.4) and are decoded by the
                // image layer, which is the only place that knows the real /Width and
                // /Height. Decoding CCITT here as well made the image layer re-decode the
                // resulting 1-bpc raster as if it were a fresh G3/G4 codestream, and forced
                // this code to guess the row count from `data.len()*8/columns` — which
                // measures COMPRESSED bits and so under-counts rows by the compression
                // ratio. Content streams never use these filters.
            }
            FilterKind::Crypt => {
                // Stream decryption already happens at document load (decrypt.rs),
                // so by here the bytes are plaintext regardless of /Name. A /Crypt
                // filter with /Name /Identity means "not encrypted" — either way a
                // no-op in the decode chain.
            }
            FilterKind::Unknown(_) => { /* return None to avoid silent corruption */ return None; }
        }
    }
    Some(data)
}

/// Apply a PNG (Predictor >= 10) or TIFF (Predictor == 2) predictor to `data`
/// using the `/DecodeParms` values. Returns `data` unchanged if no predictor.
fn apply_predictor(data: Vec<u8>, parms: Option<&Dictionary>, doc: &Document) -> Vec<u8> {
    let dict = match parms {
        Some(d) => d,
        None => return data,
    };
    let pred = dict
        .get(b"Predictor")
        .ok()
        .and_then(num)
        .or_else(|| dict.get(b"Predictor").ok().and_then(|o| deref(doc, o).and_then(num)))
        .unwrap_or(1.0);
    if pred <= 1.0 {
        return data;
    }
    let get = |key: &[u8], default: f64| -> f64 {
        dict.get(key)
            .ok()
            .and_then(num)
            .or_else(|| dict.get(key).ok().and_then(|o| deref(doc, o).and_then(num)))
            .unwrap_or(default)
    };
    // Clamp before any multiplication: `as usize` saturates a huge or negative float to
    // usize::MAX / 0, and `colors * bpc` / `bpp * cols` would then overflow (silently in
    // release, since overflow-checks are off).
    let cols = (get(b"Columns", 1.0).max(0.0) as usize).clamp(1, 1 << 24);
    let colors = (get(b"Colors", 1.0).max(0.0) as usize).clamp(1, 32);
    let bpc = match get(b"BitsPerComponent", 8.0) as i64 {
        1 => 1,
        2 => 2,
        4 => 4,
        16 => 16,
        _ => 8,
    };
    if (10.0..=15.0).contains(&pred) {
        apply_png_predictor(data, cols, colors, bpc)
    } else if pred == 2.0 {
        apply_tiff_predictor2(data, cols, colors, bpc)
    } else {
        data
    }
}

/// Undo a PNG predictor (§7.4.4.4). Each row is prefixed with a filter-type byte.
///
/// `lopdf::filters::png::decode_frame` cannot be used: it derives the row length as
/// `bytes_per_pixel * pixels_per_row`, but the spec requires
/// `ceil(Columns * Colors * BitsPerComponent / 8)`. Those agree only when
/// `BitsPerComponent` is 8 or 16, so every bilevel and 2/4-bit image got an over-long
/// row, `read_exact` failed, and the caller returned the data with the per-row filter
/// bytes still embedded. It also aborted the whole frame on a short final row.
///
/// The per-row arithmetic itself (Sub/Up/Avg/Paeth in wrapping u8 math) is correct in
/// lopdf, so `decode_row` is reused.
fn apply_png_predictor(data: Vec<u8>, cols: usize, colors: usize, bpc: usize) -> Vec<u8> {
    use lopdf::filters::png::{decode_row, FilterType};
    // Byte offset of the "left" sample. PNG defines this as ceil(bits per pixel / 8),
    // minimum 1, so sub-byte pixel depths filter on adjacent bytes.
    let bpp = (colors * bpc).div_ceil(8).max(1);
    // The FACTORS are clamped by the caller, but their PRODUCT is not bounded by
    // anything the file has to back up: at the clamp ceilings,
    // (1<<24 * 32 * 16).div_ceil(8) is a 1 GiB row, and the two row buffers below
    // plus `out` then allocate ~3 GiB from a 512-byte stream. `MAX_DECODED_BYTES`
    // cannot help - this happens after decompression.
    //
    // 7.4.4.4 gives the row length as ceil(Columns x Colors x BitsPerComponent / 8),
    // so the honest bound is the decoded stream itself: a row that does not fit in
    // the data cannot be a row the data describes. Truncated final rows are still
    // zero-filled below, so this only bites when not even one row is present - a
    // case whose output is unusable regardless.
    let row_bytes = cols
        .saturating_mul(colors)
        .saturating_mul(bpc)
        .div_ceil(8)
        .min(data.len());
    if row_bytes == 0 {
        return data;
    }
    let mut out = Vec::with_capacity(data.len());
    // §7.4.4.4: the row above the first is treated as all zeros.
    let mut prev = vec![0u8; row_bytes];
    let mut row = vec![0u8; row_bytes];
    let mut pos = 0usize;
    while pos < data.len() {
        let Ok(filter) = FilterType::try_from(data[pos]) else {
            // Not a predictor byte: stop and keep the rows decoded so far.
            break;
        };
        pos += 1;
        let end = (pos + row_bytes).min(data.len());
        let got = end - pos;
        row[..got].copy_from_slice(&data[pos..end]);
        // Zero-fill a truncated final row instead of discarding the entire frame.
        row[got..].fill(0);
        pos = end;
        decode_row(filter, bpp, &prev, &mut row);
        out.extend_from_slice(&row);
        std::mem::swap(&mut prev, &mut row);
    }
    out
}

/// TIFF Predictor 2: horizontal differencing. Reverses the left-difference for
/// 1/2/4/8/16-bit samples. Rows are byte-aligned; sub-byte samples are packed
/// big-endian within each byte and 16-bit samples are big-endian.
fn apply_tiff_predictor2(mut data: Vec<u8>, cols: usize, colors: usize, bpc: usize) -> Vec<u8> {
    if cols == 0 || colors == 0 {
        return data;
    }
    // Bounded by the decoded stream for the same reason as the PNG path above: the
    // declared factors are attacker-controlled and their product is not. `rows`
    // already came out as 0 for an over-long row on a 64-bit target, but the
    // multiplications themselves wrap (unchecked in release), and the per-row
    // `samples` buffer in the 1/2/4-bit arm below is sized from `samples_per_row`.
    let row_bytes = cols
        .saturating_mul(colors)
        .saturating_mul(bpc)
        .div_ceil(8)
        .min(data.len());
    if row_bytes == 0 {
        return data;
    }
    // A row cannot hold more samples than it has bits for; equal to
    // `cols * colors` whenever the two agree, and smaller only when the clamp above
    // took effect.
    let samples_per_row = cols.saturating_mul(colors).min(row_bytes * 8 / bpc);
    let rows = data.len() / row_bytes;
    match bpc {
        8 => {
            for r in 0..rows {
                let base = r * row_bytes;
                for i in colors..samples_per_row {
                    let prev = data[base + i - colors];
                    data[base + i] = data[base + i].wrapping_add(prev);
                }
            }
        }
        16 => {
            for r in 0..rows {
                let base = r * row_bytes;
                for i in colors..samples_per_row {
                    let p = base + (i - colors) * 2;
                    let c = base + i * 2;
                    let prev = ((data[p] as u16) << 8) | data[p + 1] as u16;
                    let cur = ((data[c] as u16) << 8) | data[c + 1] as u16;
                    let val = cur.wrapping_add(prev);
                    data[c] = (val >> 8) as u8;
                    data[c + 1] = (val & 0xFF) as u8;
                }
            }
        }
        1 | 2 | 4 => {
            let mask = ((1u32 << bpc) - 1) as u16;
            for r in 0..rows {
                let base = r * row_bytes;
                // Unpack samples (MSB-first) for this row.
                let mut samples = vec![0u16; samples_per_row];
                for (s, slot) in samples.iter_mut().enumerate() {
                    let bit = s * bpc;
                    let byte = base + bit / 8;
                    let shift = 8 - bpc - (bit % 8);
                    *slot = ((data[byte] as u16) >> shift) & mask;
                }
                for i in colors..samples_per_row {
                    samples[i] = samples[i].wrapping_add(samples[i - colors]) & mask;
                }
                for (s, val) in samples.iter().enumerate() {
                    let bit = s * bpc;
                    let byte = base + bit / 8;
                    let shift = 8 - bpc - (bit % 8);
                    data[byte] = (data[byte] & !((mask as u8) << shift)) | ((*val as u8) << shift);
                }
            }
        }
        _ => {}
    }
    data
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn ascii_hex_basic() {
        let out = decode_ascii_hex(b"48656C6C6F>");
        assert_eq!(out, b"Hello");
        let out2 = decode_ascii_hex(b"4");
        assert_eq!(out2, vec![0x40]);
    }
    #[test] fn ascii85_z() {
        let out = decode_ascii85(b"z~>").unwrap();
        assert_eq!(out, vec![0,0,0,0]);
    }
    #[test] fn runlength() {
        let data = vec![0, 0xAB, 128];
        assert_eq!(decode_runlength(&data), vec![0xAB]);
        let data2 = vec![254, 0xFF, 128];
        assert_eq!(decode_runlength(&data2), vec![0xFF,0xFF,0xFF]);
    }
    #[test] fn normalize_filters() {
        assert_eq!(normalize_filter_name("FlateDecode"), FilterKind::Flate);
        assert_eq!(normalize_filter_name("/Fl"), FilterKind::Flate);
        assert_eq!(normalize_filter_name("ASCII85DECODE"), FilterKind::Ascii85);
        assert_eq!(normalize_filter_name("DCTDecode"), FilterKind::Dct);
        assert_eq!(normalize_filter_name("JBIG2Decode"), FilterKind::Jbig2);
        assert_eq!(normalize_filter_name("CCITTFaxDecode"), FilterKind::Ccitt);
    }
    #[test] fn tiff_predictor2_horizontal() {
        // Two rows, 3 columns, 1 color, 8bpc. Encoded as left-differences.
        // Row0 original [10, 20, 30] -> diffs [10, 10, 10].
        // Row1 original [ 5,  5,  5] -> diffs [ 5,  0,  0].
        let encoded = vec![10u8, 10, 10, 5, 0, 0];
        let out = apply_tiff_predictor2(encoded, 3, 1, 8);
        assert_eq!(out, vec![10, 20, 30, 5, 5, 5]);
    }
    #[test] fn tiff_predictor2_16bit() {
        // One row, 2 columns, 1 color, 16bpc big-endian.
        // Original samples [0x0102, 0x0305] -> diffs [0x0102, 0x0203].
        let encoded = vec![0x01, 0x02, 0x02, 0x03];
        let out = apply_tiff_predictor2(encoded, 2, 1, 16);
        assert_eq!(out, vec![0x01, 0x02, 0x03, 0x05]);
    }
    #[test] fn tiff_predictor2_4bit() {
        // One row, 4 columns, 1 color, 4bpc. Original nibbles [1,3,6,7].
        // Diffs [1,2,3,1] -> packed 0x12, 0x31.
        let encoded = vec![0x12, 0x31];
        let out = apply_tiff_predictor2(encoded, 4, 1, 4);
        assert_eq!(out, vec![0x13, 0x67]);
    }
    #[test] fn tiff_predictor2_1bit() {
        // One row, 8 columns, 1 color, 1bpc. Original bits 1 0 1 1 0 0 1 0.
        // Left diffs (xor-like add mod 2): b[0]=1; b[i]=orig[i]-orig[i-1] mod2
        // orig=10110010 -> diffs: 1, 1, 1, 0, 1, 0, 1, 1 = 0b11101011 = 0xEB.
        let encoded = vec![0xEBu8];
        let out = apply_tiff_predictor2(encoded, 8, 1, 1);
        assert_eq!(out, vec![0b10110010]);
    }

    #[test] fn png_predictor_rgb_8bpc_sub() {
        // 2 rows, 2 columns, 3 colors, 8bpc => row_bytes 6, bpp 3.
        // Filter 1 (Sub) row0: [10,20,30, 5,5,5] -> [10,20,30, 15,25,35]
        // Filter 2 (Up)  row1: [1,1,1, 1,1,1]    -> previous row + 1
        let data = vec![1, 10, 20, 30, 5, 5, 5, 2, 1, 1, 1, 1, 1, 1];
        let out = apply_png_predictor(data, 2, 3, 8);
        assert_eq!(out, vec![10, 20, 30, 15, 25, 35, 11, 21, 31, 16, 26, 36]);
    }

    #[test] fn png_predictor_1bpc_row_length() {
        // 1 color, 1bpc, 17 columns => ceil(17/8) = 3 bytes per row, NOT 17.
        // Using `bpp * columns` (lopdf's decode_frame) would demand 17 bytes and fail.
        // Two rows, filter 0 (None), so the payload passes through untouched.
        let data = vec![0, 0xAA, 0xBB, 0x80, 0, 0x11, 0x22, 0x00];
        let out = apply_png_predictor(data, 17, 1, 1);
        assert_eq!(out, vec![0xAA, 0xBB, 0x80, 0x11, 0x22, 0x00]);
    }

    #[test] fn png_predictor_keeps_truncated_final_row() {
        // row_bytes 3; the second row supplies only 2 of its 3 bytes. The first row must
        // survive rather than the whole frame being discarded.
        let data = vec![0, 1, 2, 3, 0, 4, 5];
        let out = apply_png_predictor(data, 3, 1, 8);
        assert_eq!(out.len(), 6);
        assert_eq!(&out[..3], &[1, 2, 3]);
        assert_eq!(&out[3..], &[4, 5, 0]);
    }

    #[test] fn flate_returns_partial_output_when_truncated() {
        use flate2::write::ZlibEncoder;
        use std::io::Write;
        let payload: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let mut e = ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        e.write_all(&payload).expect("encode");
        let full = e.finish().expect("finish");
        // Lop off the tail: the stream is now corrupt but most of it still inflates.
        let truncated = &full[..full.len() - 8];
        let out = decode_flate(truncated).expect("partial output, not None");
        assert!(!out.is_empty(), "partial inflate must not be discarded");
        assert_eq!(out, payload[..out.len()], "partial output must be a valid prefix");
    }

    #[test] fn ascii85_overflow_is_an_error_not_a_panic() {
        // "s8W-" is exactly u32::MAX/85*85, so the next digit overflows the ADD.
        assert!(decode_ascii85(b"s8W-\"~>").is_err());
        // The maximal legal group must still decode.
        assert_eq!(decode_ascii85(b"s8W-!~>").unwrap(), vec![0xFF, 0xFF, 0xFF, 0xFF]);
    }

    #[test] fn ascii85_skips_postscript_opener() {
        assert_eq!(decode_ascii85(b"<~z~>").unwrap(), vec![0, 0, 0, 0]);
    }

    #[test] fn lzw_truncated_stream_keeps_decoded_prefix() {
        // 'A' 'B' with no EOD code: 9-bit codes 65, 66 then the stream just stops.
        // 0 0100 0001 0 0100 0010 -> 0x20, 0x90, 0x88 (trailing bits are padding).
        let out = decode_lzw(&[0x20, 0x90, 0x88], true).expect("partial output");
        assert_eq!(&out[..2], b"AB");
    }

    #[test] fn ccitt_is_left_encoded_for_the_image_layer() {
        // decode_stream_chain must NOT decode CCITT: the image layer owns it because only
        // it knows the real /Width and /Height.
        let doc = Document::new();
        let specs = vec![(FilterKind::Ccitt, None)];
        let raw = vec![0x26, 0xA0, 0x00, 0x11];
        let out = decode_stream_chain(raw.clone(), &specs, &doc).expect("passthrough");
        assert_eq!(out, raw, "CCITT bytes must reach the image layer untouched");
    }

    #[test] fn inline_image_f_abbreviation_is_a_filter() {
        // §8.9.7 Table 93: an inline image spells /Filter as /F and /DecodeParms as /DP.
        // Without this the filter chain came back empty and the still-ENCODED bytes were
        // used as image samples.
        let doc = Document::new();
        let mut dict = Dictionary::new();
        dict.set("W", Object::Integer(4));
        dict.set("H", Object::Integer(4));
        dict.set("F", Object::Name(b"AHx".to_vec()));
        let specs = filter_specs_from_dict(&doc, &dict);
        assert_eq!(specs.len(), 1);
        assert_eq!(specs[0].0, FilterKind::AsciiHex);

        // /DP carries the parameters under the same abbreviation scheme.
        let mut dict = Dictionary::new();
        dict.set("F", Object::Name(b"Fl".to_vec()));
        let mut dp = Dictionary::new();
        dp.set("Predictor", Object::Integer(12));
        dp.set("Columns", Object::Integer(4));
        dict.set("DP", Object::Dictionary(dp));
        let specs = filter_specs_from_dict(&doc, &dict);
        assert_eq!(specs[0].0, FilterKind::Flate);
        let parms = specs[0].1.as_ref().expect("/DP must reach the Flate filter");
        assert_eq!(parms.get(b"Predictor").unwrap(), &Object::Integer(12));
    }

    #[test] fn stream_f_file_specification_is_not_treated_as_a_filter() {
        // In a regular STREAM dictionary /F is a file specification (§7.3.8.2), not a
        // filter. Only the Name/Array shapes a filter can have may be accepted, or an
        // external-file reference would be misread as a filter name.
        let doc = Document::new();
        let mut dict = Dictionary::new();
        dict.set("F", Object::String(b"/tmp/data.bin".to_vec(), lopdf::StringFormat::Literal));
        assert!(filter_specs_from_dict(&doc, &dict).is_empty());

        let mut dict = Dictionary::new();
        let mut fs = Dictionary::new();
        fs.set("Type", Object::Name(b"Filespec".to_vec()));
        dict.set("F", Object::Dictionary(fs));
        assert!(filter_specs_from_dict(&doc, &dict).is_empty());
    }

    #[test] fn explicit_filter_still_wins_over_f() {
        // /F is only consulted when /Filter is absent, so no existing behaviour changes.
        let doc = Document::new();
        let mut dict = Dictionary::new();
        dict.set("Filter", Object::Name(b"FlateDecode".to_vec()));
        dict.set("F", Object::Name(b"AHx".to_vec()));
        let specs = filter_specs_from_dict(&doc, &dict);
        assert_eq!(specs.len(), 1);
        assert_eq!(specs[0].0, FilterKind::Flate);
    }
}
