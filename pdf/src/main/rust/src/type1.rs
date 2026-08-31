//! Minimal Type 1 (`/FontFile`) font parser: eexec decryption, `/CharStrings`
//! and `/Subrs` extraction, and a Type 1 charstring interpreter that produces
//! flattened glyph outlines in 1000-unit em space.
//!
//! Scope: the operators real Type 1 fonts use for glyph outlines — hsbw/sbw,
//! move/line/curve, closepath, callsubr/return, div, seac (accent composition),
//! callothersubr/pop (flex + hint replacement), endchar. Hint operators
//! (hstem/vstem/…) are parsed and ignored. Malformed input yields `None` or a
//! partial outline rather than panicking.

use crate::outlines::ContourBuilder;
use std::collections::HashMap;

pub(crate) struct Type1Font {
    /// Glyph name -> flattened contours in 1000-unit em space.
    pub(crate) glyphs: HashMap<String, Vec<Vec<(f64, f64)>>>,
    /// Built-in `/Encoding`: char code -> glyph name.
    pub(crate) encoding: HashMap<u32, String>,
    /// `/FontMatrix` (defaults to 0.001 scale) mapping glyph space -> text space.
    pub(crate) font_matrix: [f64; 6],
}

/// Type 1 / eexec decryption (Adobe Type 1 Font Format §7). Decrypts `cipher`
/// with the running key seeded at `r`, then drops the first `skip` plaintext
/// bytes (4 for eexec, `lenIV` for charstrings).
fn decrypt(cipher: &[u8], r0: u16, skip: usize) -> Vec<u8> {
    let c1: u16 = 52845;
    let c2: u16 = 22719;
    let mut r = r0;
    let mut out = Vec::with_capacity(cipher.len());
    for &c in cipher {
        let p = c ^ (r >> 8) as u8;
        r = (c as u16).wrapping_add(r).wrapping_mul(c1).wrapping_add(c2);
        out.push(p);
    }
    if skip >= out.len() {
        Vec::new()
    } else {
        out.split_off(skip)
    }
}

/// Locate the `eexec` keyword and return the encrypted section as raw bytes,
/// decoding it from ASCII-hex when the font stores that section as hex.
fn extract_eexec(data: &[u8]) -> Option<Vec<u8>> {
    let pos = find(data, b"eexec")?;
    let mut i = pos + 5;
    // Skip whitespace after `eexec`.
    while i < data.len() && matches!(data[i], b' ' | b'\r' | b'\n' | b'\t') {
        i += 1;
    }
    let section = &data[i..];
    // Hex-encoded if the first bytes are all hex digits (and there's whitespace,
    // which binary sections almost never begin with 4 hex + space patterns).
    let is_hex = section.iter().take(4).all(|b| b.is_ascii_hexdigit());
    if is_hex {
        let mut bytes = Vec::new();
        let mut hi: Option<u8> = None;
        for &b in section {
            let v = match b {
                b'0'..=b'9' => b - b'0',
                b'a'..=b'f' => b - b'a' + 10,
                b'A'..=b'F' => b - b'A' + 10,
                b' ' | b'\r' | b'\n' | b'\t' => continue,
                _ => break,
            };
            match hi.take() {
                None => hi = Some(v),
                Some(h) => bytes.push((h << 4) | v),
            }
        }
        Some(decrypt(&bytes, 55665, 4))
    } else {
        Some(decrypt(section, 55665, 4))
    }
}

fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return None;
    }
    hay.windows(needle.len()).position(|w| w == needle)
}

fn find_from(hay: &[u8], needle: &[u8], start: usize) -> Option<usize> {
    if start >= hay.len() {
        return None;
    }
    find(&hay[start..], needle).map(|p| p + start)
}

/// Find a whitespace-delimited PostScript token. Searching for a bare `end`
/// substring would match inside a glyph name such as `/endash`.
fn find_token(hay: &[u8], tok: &[u8], start: usize) -> Option<usize> {
    let mut at = start;
    while let Some(p) = find_from(hay, tok, at) {
        let before_ok = p == 0 || hay[p - 1].is_ascii_whitespace();
        let after = p + tok.len();
        let after_ok = after >= hay.len() || hay[after].is_ascii_whitespace();
        if before_ok && after_ok {
            return Some(p);
        }
        at = p + 1;
    }
    None
}

/// Parse `n` decimal integer tokens starting at/after `pos`, returning them with
/// the index just past the last one consumed.
fn read_int(data: &[u8], mut i: usize) -> Option<(i64, usize)> {
    while i < data.len() && data[i].is_ascii_whitespace() {
        i += 1;
    }
    let start = i;
    if i < data.len() && (data[i] == b'-' || data[i] == b'+') {
        i += 1;
    }
    while i < data.len() && data[i].is_ascii_digit() {
        i += 1;
    }
    if i == start {
        return None;
    }
    std::str::from_utf8(&data[start..i]).ok()?.parse::<i64>().ok().map(|v| (v, i))
}

/// Parse the cleartext `/Encoding` array (`dup <code> /<name> put`) into
/// code -> name. Recognizes the `StandardEncoding` shorthand.
fn parse_encoding(cleartext: &[u8]) -> HashMap<u32, String> {
    let mut enc = HashMap::new();
    if let Some(p) = find(cleartext, b"/Encoding") {
        let region = &cleartext[p..(p + 200).min(cleartext.len())];
        if find(region, b"StandardEncoding").is_some() {
            for (code, name) in STANDARD_ENCODING {
                enc.insert(*code as u32, (*name).to_string());
            }
            return enc;
        }
    }
    // Scan `dup <code> /<name> put` entries.
    let mut search = 0usize;
    while let Some(dp) = find_from(cleartext, b"dup ", search) {
        search = dp + 4;
        let (code, mut j) = match read_int(cleartext, dp + 4) {
            Some(v) => v,
            None => continue,
        };
        while j < cleartext.len() && cleartext[j].is_ascii_whitespace() {
            j += 1;
        }
        if j >= cleartext.len() || cleartext[j] != b'/' {
            continue;
        }
        j += 1;
        let ns = j;
        while j < cleartext.len() && !cleartext[j].is_ascii_whitespace() {
            j += 1;
        }
        if let Ok(name) = std::str::from_utf8(&cleartext[ns..j]) {
            if (0..256).contains(&code) {
                enc.insert(code as u32, name.to_string());
            }
        }
    }
    enc
}

fn parse_font_matrix(cleartext: &[u8]) -> [f64; 6] {
    let default = [0.001, 0.0, 0.0, 0.001, 0.0, 0.0];
    let p = match find(cleartext, b"/FontMatrix") {
        Some(p) => p,
        None => return default,
    };
    let lb = match find_from(cleartext, b"[", p) {
        Some(p) => p,
        None => return default,
    };
    let rb = match find_from(cleartext, b"]", lb) {
        Some(p) => p,
        None => return default,
    };
    let vals: Vec<f64> = std::str::from_utf8(&cleartext[lb + 1..rb])
        .unwrap_or("")
        .split_whitespace()
        .filter_map(|s| s.parse::<f64>().ok())
        .collect();
    if vals.len() == 6 {
        [vals[0], vals[1], vals[2], vals[3], vals[4], vals[5]]
    } else {
        default
    }
}

/// Read the `RD`/`-|` binary-data operator: after an integer length and the RD
/// token comes exactly one space and then `len` raw bytes. Returns the bytes and
/// the index just past them.
fn read_rd_binary(data: &[u8], len_pos: usize) -> Option<(Vec<u8>, usize)> {
    let (len, mut i) = read_int(data, len_pos)?;
    if len < 0 {
        return None;
    }
    // Skip whitespace, then the RD or -| token, then exactly one space.
    while i < data.len() && data[i].is_ascii_whitespace() {
        i += 1;
    }
    // token is `RD` or `-|`
    if i + 2 > data.len() {
        return None;
    }
    let tok = &data[i..i + 2];
    if tok != b"RD" && tok != b"-|" {
        return None;
    }
    i += 2;
    // exactly one binary-preceding space
    if i >= data.len() {
        return None;
    }
    i += 1;
    let end = i + len as usize;
    if end > data.len() {
        return None;
    }
    Some((data[i..end].to_vec(), end))
}

/// Extract `lenIV`, the `/Subrs` array, and the `/CharStrings` dictionary from
/// the decrypted eexec section, decrypting each charstring in place.
fn parse_private(dec: &[u8]) -> (Vec<Vec<u8>>, HashMap<String, Vec<u8>>) {
    let len_iv = find(dec, b"/lenIV")
        .and_then(|p| read_int(dec, p + 6))
        .map(|(v, _)| v.max(0) as usize)
        .unwrap_or(4);

    // --- Subrs: `dup <i> <len> RD <bytes> NP` ---
    // Both the declared count and each `dup` index come straight from the file and
    // size a heap allocation. A Type 1 font's Subrs array is at most a few
    // thousand entries; without a cap, `/Subrs 2000000000 array` (or one oversized
    // `dup` index) asks for tens of gigabytes before a single charstring is read.
    const MAX_SUBRS: usize = 65536;
    let mut subrs: Vec<Vec<u8>> = Vec::new();
    if let Some(sp) = find(dec, b"/Subrs") {
        if let Some((count, _)) = read_int(dec, sp + 6) {
            subrs = vec![Vec::new(); (count.max(0) as usize).min(MAX_SUBRS)];
        }
        let mut i = sp;
        let mut guard = 0;
        while let Some(dp) = find_from(dec, b"dup ", i) {
            i = dp + 4;
            guard += 1;
            if guard > 100_000 {
                break;
            }
            let (idx, j) = match read_int(dec, dp + 4) {
                Some(v) => v,
                None => continue,
            };
            let (bytes, next) = match read_rd_binary(dec, j) {
                Some(v) => v,
                None => continue,
            };
            i = next;
            if idx >= 0 && (idx as usize) < MAX_SUBRS {
                if (idx as usize) >= subrs.len() {
                    subrs.resize(idx as usize + 1, Vec::new());
                }
                subrs[idx as usize] = decrypt(&bytes, 4330, len_iv);
            }
            // Stop once we hit CharStrings.
            if let Some(cs) = find(dec, b"/CharStrings") {
                if next > cs {
                    break;
                }
            }
        }
    }

    // --- CharStrings: `/<name> <len> RD <bytes> ND` ---
    let mut glyphs: HashMap<String, Vec<u8>> = HashMap::new();
    if let Some(cp) = find(dec, b"/CharStrings") {
        // Advance past the `begin` that opens the dict.
        let mut i = find_from(dec, b"begin", cp).map(|p| p + 5).unwrap_or(cp + 12);
        let mut guard = 0;
        while i < dec.len() {
            guard += 1;
            if guard > 500_000 {
                break;
            }
            // Find next `/name`.
            let slash = match find_from(dec, b"/", i) {
                Some(p) => p,
                None => break,
            };
            let ns = slash + 1;
            let mut je = ns;
            while je < dec.len() && !dec[je].is_ascii_whitespace() {
                je += 1;
            }
            let name = match std::str::from_utf8(&dec[ns..je]) {
                Ok(n) => n.to_string(),
                Err(_) => {
                    i = je;
                    continue;
                }
            };
            match read_rd_binary(dec, je) {
                Some((bytes, next)) => {
                    glyphs.insert(name, decrypt(&bytes, 4330, len_iv));
                    i = next;
                }
                None => {
                    i = je;
                    // `end` closes the dict.
                    if let Some(ep) = find_token(dec, b"end", cp) {
                        if slash > ep {
                            break;
                        }
                    }
                }
            }
        }
    }

    (subrs, glyphs)
}

/// Interpret a decrypted Type 1 charstring into `out`, following `subrs` for
/// callsubr and `glyphs`/`encoding` for seac accent composition.
fn run_charstring(
    cs: &[u8],
    subrs: &[Vec<u8>],
    glyphs: &HashMap<String, Vec<u8>>,
    out: &mut ContourBuilder,
) {
    let mut st = Interp {
        stack: Vec::new(),
        ps_stack: Vec::new(),
        x: 0.0,
        y: 0.0,
        sbx: 0.0,
        flex_pts: Vec::new(),
        in_flex: false,
        subrs,
        glyphs,
        depth: 0,
    };
    st.exec(cs, out);
}

struct Interp<'a> {
    stack: Vec<f64>,
    ps_stack: Vec<f64>,
    x: f64,
    y: f64,
    sbx: f64,
    flex_pts: Vec<(f64, f64)>,
    in_flex: bool,
    subrs: &'a [Vec<u8>],
    glyphs: &'a HashMap<String, Vec<u8>>,
    depth: u32,
}

impl<'a> Interp<'a> {
    /// Returns true if an endchar/seac terminated the whole glyph.
    fn exec(&mut self, cs: &[u8], out: &mut ContourBuilder) -> bool {
        if self.depth > 30 {
            return true;
        }
        self.depth += 1;
        let mut i = 0;
        while i < cs.len() {
            let b = cs[i];
            i += 1;
            if b >= 32 {
                // Number operand.
                let v = if b <= 246 {
                    (b as i32 - 139) as f64
                } else if b <= 250 {
                    let w = *cs.get(i).unwrap_or(&0) as i32;
                    i += 1;
                    ((b as i32 - 247) * 256 + w + 108) as f64
                } else if b <= 254 {
                    let w = *cs.get(i).unwrap_or(&0) as i32;
                    i += 1;
                    (-(b as i32 - 251) * 256 - w - 108) as f64
                } else {
                    // 255: 32-bit signed integer.
                    let mut n: i32 = 0;
                    for _ in 0..4 {
                        n = (n << 8) | (*cs.get(i).unwrap_or(&0) as i32);
                        i += 1;
                    }
                    n as f64
                };
                self.stack.push(v);
                continue;
            }
            match b {
                13 => {
                    // hsbw: sbx wx
                    if self.stack.len() >= 2 {
                        self.sbx = self.stack[0];
                        self.x = self.stack[0];
                        self.y = 0.0;
                    }
                    self.stack.clear();
                }
                9 => {
                    // closepath
                    out.close();
                    self.stack.clear();
                }
                21 => {
                    // rmoveto
                    let n = self.stack.len();
                    if n >= 2 {
                        self.x += self.stack[n - 2];
                        self.y += self.stack[n - 1];
                    }
                    self.moveto(out);
                    self.stack.clear();
                }
                22 => {
                    // hmoveto
                    if let Some(&dx) = self.stack.last() {
                        self.x += dx;
                    }
                    self.moveto(out);
                    self.stack.clear();
                }
                4 => {
                    // vmoveto
                    if let Some(&dy) = self.stack.last() {
                        self.y += dy;
                    }
                    self.moveto(out);
                    self.stack.clear();
                }
                5 => {
                    // rlineto
                    let n = self.stack.len();
                    if n >= 2 {
                        self.x += self.stack[n - 2];
                        self.y += self.stack[n - 1];
                        out.line_to(self.x, self.y);
                    }
                    self.stack.clear();
                }
                6 => {
                    // hlineto
                    if let Some(&dx) = self.stack.last() {
                        self.x += dx;
                        out.line_to(self.x, self.y);
                    }
                    self.stack.clear();
                }
                7 => {
                    // vlineto
                    if let Some(&dy) = self.stack.last() {
                        self.y += dy;
                        out.line_to(self.x, self.y);
                    }
                    self.stack.clear();
                }
                8 => {
                    // rrcurveto: dx1 dy1 dx2 dy2 dx3 dy3
                    if self.stack.len() >= 6 {
                        let s = &self.stack[self.stack.len() - 6..];
                        self.curve(out, s[0], s[1], s[2], s[3], s[4], s[5]);
                    }
                    self.stack.clear();
                }
                30 => {
                    // vhcurveto: dy1 dx2 dy2 dx3
                    if self.stack.len() >= 4 {
                        let s = &self.stack[self.stack.len() - 4..];
                        self.curve(out, 0.0, s[0], s[1], s[2], s[3], 0.0);
                    }
                    self.stack.clear();
                }
                31 => {
                    // hvcurveto: dx1 dx2 dy2 dy3
                    if self.stack.len() >= 4 {
                        let s = &self.stack[self.stack.len() - 4..];
                        self.curve(out, s[0], 0.0, s[1], s[2], 0.0, s[3]);
                    }
                    self.stack.clear();
                }
                1 | 3 => {
                    // hstem / vstem: ignore
                    self.stack.clear();
                }
                10 => {
                    // callsubr
                    if let Some(idx) = self.stack.pop() {
                        let idx = idx as i64;
                        if idx >= 0 && (idx as usize) < self.subrs.len() {
                            let sub = self.subrs[idx as usize].clone();
                            if self.exec(&sub, out) {
                                self.depth -= 1;
                                return true;
                            }
                        }
                    }
                }
                11 => {
                    // return
                    self.depth -= 1;
                    return false;
                }
                14 => {
                    // endchar
                    out.close();
                    self.depth -= 1;
                    return true;
                }
                12 => {
                    // escape
                    let b2 = *cs.get(i).unwrap_or(&0);
                    i += 1;
                    match b2 {
                        0..=2 => {
                            // dotsection / vstem3 / hstem3: ignore
                            self.stack.clear();
                        }
                        6 => {
                            // seac: asb adx ady bchar achar
                            if self.stack.len() >= 5 {
                                let s = self.stack.clone();
                                let n = s.len();
                                self.seac(out, s[n - 5], s[n - 4], s[n - 3], s[n - 2] as i32, s[n - 1] as i32);
                            }
                            self.stack.clear();
                            self.depth -= 1;
                            return true;
                        }
                        7 => {
                            // sbw
                            if self.stack.len() >= 4 {
                                self.sbx = self.stack[0];
                                self.x = self.stack[0];
                                self.y = self.stack[1];
                            }
                            self.stack.clear();
                        }
                        12 => {
                            // div
                            let n = self.stack.len();
                            if n >= 2 {
                                let a = self.stack[n - 2];
                                let bb = self.stack[n - 1];
                                self.stack.truncate(n - 2);
                                self.stack.push(if bb != 0.0 { a / bb } else { 0.0 });
                            }
                        }
                        16 => {
                            // callothersubr
                            self.callothersubr(out);
                        }
                        17 => {
                            // pop: PS stack -> operand stack
                            let v = self.ps_stack.pop().unwrap_or(0.0);
                            self.stack.push(v);
                        }
                        33 => {
                            // setcurrentpoint
                            if self.stack.len() >= 2 {
                                self.x = self.stack[0];
                                self.y = self.stack[1];
                            }
                            self.stack.clear();
                        }
                        _ => {
                            self.stack.clear();
                        }
                    }
                }
                _ => {
                    self.stack.clear();
                }
            }
        }
        self.depth -= 1;
        false
    }

    fn moveto(&mut self, out: &mut ContourBuilder) {
        if self.in_flex {
            self.flex_pts.push((self.x, self.y));
        } else {
            out.move_to(self.x, self.y);
        }
    }

    fn curve(&mut self, out: &mut ContourBuilder, dx1: f64, dy1: f64, dx2: f64, dy2: f64, dx3: f64, dy3: f64) {
        let x1 = self.x + dx1;
        let y1 = self.y + dy1;
        let x2 = x1 + dx2;
        let y2 = y1 + dy2;
        let x3 = x2 + dx3;
        let y3 = y2 + dy3;
        out.curve_to(x1, y1, x2, y2, x3, y3);
        self.x = x3;
        self.y = y3;
    }

    /// Handle `callothersubr`: flex (0/1/2) and hint replacement (3). Others push
    /// their args back for subsequent `pop`s.
    fn callothersubr(&mut self, out: &mut ContourBuilder) {
        let othersubr = self.stack.pop().unwrap_or(-1.0) as i64;
        let nargs = self.stack.pop().unwrap_or(0.0) as i64;
        let nargs = nargs.max(0) as usize;
        let mut args = Vec::with_capacity(nargs);
        for _ in 0..nargs {
            args.push(self.stack.pop().unwrap_or(0.0));
        }
        args.reverse();
        match othersubr {
            1 => {
                // Start flex: begin collecting the 7 reference points.
                self.in_flex = true;
                self.flex_pts.clear();
            }
            2 => {
                // Collect flex point (the preceding rmoveto pushed it).
            }
            0 => {
                // End flex: emit two curves from the 7 collected points (point 0 is
                // the reference point; 1-3 and 4-6 are the two cubic segments). The
                // collected points are absolute glyph-space coordinates.
                self.in_flex = false;
                if self.flex_pts.len() >= 7 {
                    let p = self.flex_pts.clone();
                    out.curve_to(p[1].0, p[1].1, p[2].0, p[2].1, p[3].0, p[3].1);
                    out.curve_to(p[4].0, p[4].1, p[5].0, p[5].1, p[6].0, p[6].1);
                    self.x = p[6].0;
                    self.y = p[6].1;
                }
                // Return the end point for the following `pop pop setcurrentpoint`.
                self.ps_stack.push(self.y);
                self.ps_stack.push(self.x);
                self.flex_pts.clear();
            }
            3 => {
                // Hint replacement: return subr# 3 for the following `pop; callsubr`.
                self.ps_stack.push(3.0);
            }
            _ => {
                // Unknown: make args available to following pops in reverse order.
                for a in args.into_iter().rev() {
                    self.ps_stack.push(a);
                }
            }
        }
    }

    /// seac: compose an accented glyph from base `bchar` + accent `achar`, both
    /// referenced by StandardEncoding code.
    fn seac(&mut self, out: &mut ContourBuilder, asb: f64, adx: f64, ady: f64, bchar: i32, achar: i32) {
        let bname = std_name(bchar);
        let aname = std_name(achar);
        if let Some(name) = bname {
            if let Some(cs) = self.glyphs.get(name).cloned() {
                let mut sub = Interp {
                    stack: Vec::new(),
                    ps_stack: Vec::new(),
                    x: 0.0,
                    y: 0.0,
                    sbx: 0.0,
                    flex_pts: Vec::new(),
                    in_flex: false,
                    subrs: self.subrs,
                    glyphs: self.glyphs,
                    depth: self.depth,
                };
                sub.exec(&cs, out);
            }
        }
        if let Some(name) = aname {
            if let Some(cs) = self.glyphs.get(name).cloned() {
                // The accent's own `hsbw` resets the current point to its side
                // bearing, so seeding x/y here cannot place it. Interpret the
                // accent at its natural origin, then translate the finished
                // contours by `sbx + adx - asb` / `ady`: per TN #5015 `asb` is the
                // accent's own side bearing, which its `hsbw` re-applies, so it
                // must be subtracted out.
                let dx = self.sbx + adx - asb;
                let mut acc = ContourBuilder::new();
                let mut sub = Interp {
                    stack: Vec::new(),
                    ps_stack: Vec::new(),
                    x: 0.0,
                    y: 0.0,
                    sbx: 0.0,
                    flex_pts: Vec::new(),
                    in_flex: false,
                    subrs: self.subrs,
                    glyphs: self.glyphs,
                    depth: self.depth,
                };
                sub.exec(&cs, &mut acc);
                for c in acc.finish() {
                    out.add_contour(c.into_iter().map(|(px, py)| (px + dx, py + ady)).collect());
                }
            }
        }
    }
}

fn std_name(code: i32) -> Option<&'static str> {
    if !(0..=255).contains(&code) {
        return None;
    }
    STANDARD_ENCODING.iter().find(|(c, _)| *c as i32 == code).map(|(_, n)| *n)
}

pub(crate) fn parse(data: &[u8]) -> Option<Type1Font> {
    // The cleartext portion precedes `eexec`.
    let clear_end = find(data, b"eexec").unwrap_or(data.len());
    let cleartext = &data[..clear_end];
    let encoding = parse_encoding(cleartext);
    let font_matrix = parse_font_matrix(cleartext);

    let dec = extract_eexec(data)?;
    let (subrs, raw_glyphs) = parse_private(&dec);
    if raw_glyphs.is_empty() {
        return None;
    }

    let mut glyphs = HashMap::with_capacity(raw_glyphs.len());
    for (name, cs) in &raw_glyphs {
        let mut cb = ContourBuilder::new();
        run_charstring(cs, &subrs, &raw_glyphs, &mut cb);
        glyphs.insert(name.clone(), cb.finish());
    }

    Some(Type1Font { glyphs, encoding, font_matrix })
}

/// Adobe StandardEncoding: code -> glyph name (used for the `StandardEncoding`
/// shorthand and seac accent composition). Covers the printable Latin range.
pub(crate) static STANDARD_ENCODING: &[(u8, &str)] = &[
    (32, "space"), (33, "exclam"), (34, "quotedbl"), (35, "numbersign"), (36, "dollar"),
    (37, "percent"), (38, "ampersand"), (39, "quoteright"), (40, "parenleft"), (41, "parenright"),
    (42, "asterisk"), (43, "plus"), (44, "comma"), (45, "hyphen"), (46, "period"),
    (47, "slash"), (48, "zero"), (49, "one"), (50, "two"), (51, "three"), (52, "four"),
    (53, "five"), (54, "six"), (55, "seven"), (56, "eight"), (57, "nine"), (58, "colon"),
    (59, "semicolon"), (60, "less"), (61, "equal"), (62, "greater"), (63, "question"),
    (64, "at"), (65, "A"), (66, "B"), (67, "C"), (68, "D"), (69, "E"), (70, "F"), (71, "G"),
    (72, "H"), (73, "I"), (74, "J"), (75, "K"), (76, "L"), (77, "M"), (78, "N"), (79, "O"),
    (80, "P"), (81, "Q"), (82, "R"), (83, "S"), (84, "T"), (85, "U"), (86, "V"), (87, "W"),
    (88, "X"), (89, "Y"), (90, "Z"), (91, "bracketleft"), (92, "backslash"), (93, "bracketright"),
    (94, "asciicircum"), (95, "underscore"), (96, "quoteleft"), (97, "a"), (98, "b"), (99, "c"),
    (100, "d"), (101, "e"), (102, "f"), (103, "g"), (104, "h"), (105, "i"), (106, "j"),
    (107, "k"), (108, "l"), (109, "m"), (110, "n"), (111, "o"), (112, "p"), (113, "q"),
    (114, "r"), (115, "s"), (116, "t"), (117, "u"), (118, "v"), (119, "w"), (120, "x"),
    (121, "y"), (122, "z"), (123, "braceleft"), (124, "bar"), (125, "braceright"), (126, "asciitilde"),
    (161, "exclamdown"), (162, "cent"), (163, "sterling"), (164, "fraction"), (165, "yen"),
    (166, "florin"), (167, "section"), (168, "currency"), (169, "quotesingle"), (170, "quotedblleft"),
    (171, "guillemotleft"), (172, "guilsinglleft"), (173, "guilsinglright"), (174, "fi"), (175, "fl"),
    (177, "endash"), (178, "dagger"), (179, "daggerdbl"), (180, "periodcentered"), (182, "paragraph"),
    (183, "bullet"), (184, "quotesinglbase"), (185, "quotedblbase"), (186, "quotedblright"),
    (187, "guillemotright"), (188, "ellipsis"), (189, "perthousand"), (191, "questiondown"),
    (193, "grave"), (194, "acute"), (195, "circumflex"), (196, "tilde"), (197, "macron"),
    (198, "breve"), (199, "dotaccent"), (200, "dieresis"), (202, "ring"), (203, "cedilla"),
    (205, "hungarumlaut"), (206, "ogonek"), (207, "caron"), (208, "emdash"), (225, "AE"),
    (227, "ordfeminine"), (232, "Lslash"), (233, "Oslash"), (234, "OE"), (235, "ordmasculine"),
    (241, "ae"), (245, "dotlessi"), (248, "lslash"), (249, "oslash"), (250, "oe"), (251, "germandbls"),
];

#[cfg(test)]
mod tests {
    use super::*;

    /// Type 1 charstring number encoding (Adobe Type 1 Font Format 6.2).
    fn n(v: i32) -> Vec<u8> {
        if (-107..=107).contains(&v) {
            vec![(v + 139) as u8]
        } else if (108..=1131).contains(&v) {
            let d = v - 108;
            vec![(247 + d / 256) as u8, (d % 256) as u8]
        } else if (-1131..=-108).contains(&v) {
            let d = -v - 108;
            vec![(251 + d / 256) as u8, (d % 256) as u8]
        } else {
            let b = v.to_be_bytes();
            vec![255, b[0], b[1], b[2], b[3]]
        }
    }

    #[test]
    fn subrs_array_size_is_not_taken_from_the_file() {
        // `/Subrs <n> array` and each `dup <i>` index size a heap allocation
        // straight from untrusted bytes. Unclamped, this asks for ~48 GB of empty
        // Vecs before a single charstring is read.
        let dec = b"/lenIV 0 def\n/Subrs 2000000000 array\ndup 0 3 RD abc NP\ndup 1999999999 3 RD def NP\nND\n/CharStrings 1 dict dup begin\n/A 3 RD xyz ND\nend";
        let (subrs, glyphs) = parse_private(dec);
        assert!(subrs.len() <= 65536, "subr table sized from the file: {}", subrs.len());
        assert_eq!(subrs[0].len(), 3, "the in-range subr is still stored");
        assert!(glyphs.contains_key("A"), "CharStrings still parse after the cap");
    }

    #[test]
    fn flex_emits_one_contour_and_leaves_the_current_point_correct() {
        // OtherSubrs 1/2/0 flex (Adobe Type 1 Font Format 8.3). The seven
        // rmovetos are reference points, NOT contour starts, and OtherSubr 0
        // returns the end point for the trailing `pop pop setcurrentpoint`.
        // Getting either wrong restarts the contour seven times and corrupts the
        // current point, damaging every segment drawn after the flex.
        let mut cs: Vec<u8> = Vec::new();
        cs.extend(n(0));
        cs.extend(n(500));
        cs.push(13); // hsbw
        cs.extend(n(0));
        cs.extend(n(0));
        cs.push(21); // rmoveto -> contour starts at (0, 0)
        cs.extend(n(0));
        cs.extend(n(1));
        cs.extend([12, 16]); // 0 1 callothersubr -> begin flex
        for (dx, dy) in [(50, 50), (10, 10), (10, 10), (10, -10), (10, -10), (10, 10), (10, 10)] {
            cs.extend(n(dx));
            cs.extend(n(dy));
            cs.push(21); // rmoveto -> reference point
            cs.extend(n(0));
            cs.extend(n(2));
            cs.extend([12, 16]); // 0 2 callothersubr -> collect
        }
        cs.extend(n(50)); // flex depth
        cs.extend(n(110)); // end x
        cs.extend(n(70)); // end y
        cs.extend(n(3));
        cs.extend(n(0));
        cs.extend([12, 16]); // 3 0 callothersubr -> end flex
        cs.extend([12, 17, 12, 17, 12, 33]); // pop pop setcurrentpoint
        cs.extend(n(10));
        cs.extend(n(0));
        cs.push(5); // rlineto -> (120, 70), proves the pen survived the flex
        cs.push(14); // endchar

        let mut cb = ContourBuilder::new();
        run_charstring(&cs, &[], &HashMap::new(), &mut cb);
        let contours = cb.finish();

        assert_eq!(contours.len(), 1, "flex must not start new contours");
        let c = &contours[0];
        assert_eq!(c.first().copied(), Some((0.0, 0.0)));
        // `endchar` closes the contour, and `ContourBuilder` represents closure as
        // a duplicated first point (the convention `interpret.rs`'s `h` uses), so
        // the pen's final position is the point BEFORE that closing point.
        assert_eq!(c.last().copied(), Some((0.0, 0.0)), "contour is explicitly closed");
        let pen = c[c.len() - 2];
        assert!(
            (pen.0 - 120.0).abs() < 1e-6 && (pen.1 - 70.0).abs() < 1e-6,
            "trailing rlineto ended at {pen:?}, expected (120, 70)"
        );
        // The join between the two cubics is the flex midpoint, reference point 3.
        assert!(
            c.iter().any(|&(x, y)| (x - 80.0).abs() < 1e-6 && (y - 60.0).abs() < 1e-6),
            "first flex curve must end at (80, 60)"
        );
    }
}
