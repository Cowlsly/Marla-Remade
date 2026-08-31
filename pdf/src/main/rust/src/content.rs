//! Lenient fallback tokenizer for PDF content streams (§7.2, §7.3, §8.9.7).
//!
//! `lopdf`'s content parser wraps inline-image parsing in nom's `cut(...)`
//! (parser/mod.rs:555), so a failure inside it becomes a `Failure` rather than an
//! `Error`. `many0` propagates a `Failure`, which means ONE malformed or merely
//! unsupported inline image makes the ENTIRE content stream fail to decode and the
//! page render completely blank. lopdf hard-errors on:
//!
//! * any inline image with `/F` or `/Filter` (`Error::Unimplemented`),
//! * `/CS /G` — the §8.9.7 Table 93 abbreviation for `DeviceGray`, which is absent
//!   from lopdf's accepted name list,
//! * a missing `/BPC` or `/CS`, which is every `/IM true` stencil, since a stencil
//!   mask has no colour space and an implicit depth of 1.
//!
//! So this module re-tokenizes the stream by hand, accepting everything above and
//! recovering from outright garbage instead of aborting. It is used ONLY when
//! lopdf's strict parse fails (or yields nothing), so it cannot regress a file that
//! already renders.
//!
//! # The `BI`/`ID`/`EI` hazard
//!
//! Per §8.9.7 the `ID` operator is followed by exactly ONE whitespace byte and then
//! RAW BINARY image data. Scanning forward for the next `EI` is therefore wrong: the
//! byte pair `EI` occurs in binary pixel data constantly, and accepting a false match
//! resynchronizes the tokenizer into the middle of an image, turning the rest of the
//! stream into garbage operators — strictly worse than the blank page being fixed.
//!
//! Instead the expected data length is COMPUTED from `/W`, `/H`, `/BPC` and `/CS`
//! (or taken from `/L` / `/Length`, which PDF 2.0 added for exactly this reason),
//! exactly that many bytes are skipped, and the terminating `EI` is then verified.
//! Scanning is a last resort, reached only when the length genuinely cannot be
//! computed (a filtered image with no `/L`), and even then a candidate must be
//! whitespace-delimited AND followed by something that actually looks like a content
//! stream before it is accepted.

use crate::*;
use lopdf::content::Operation;
use lopdf::StringFormat;

// --- Bounds. Nothing here may allocate based on a length read from the file. ---

/// Cap on recovered operations. Bounds worst-case memory on a hostile stream.
///
/// Tied to the interpreter's own ceiling: `interpret_content` stops reading at
/// `MAX_CONTENT_OPS`, so tokenizing beyond that allocates operations nobody will
/// ever look at — on precisely the hostile input the cap exists to contain. Keeping
/// the two equal also stops them drifting into one being dead code.
const MAX_OPERATIONS: usize = MAX_CONTENT_OPS;
/// Cap on the pending operand stack. Operators consume a handful; the rest is junk.
const MAX_OPERANDS: usize = 4096;
/// Array/dictionary nesting cap (recursion bound).
const MAX_DEPTH: u32 = 32;
/// Cap on elements in one array and entries in one dictionary.
const MAX_ARRAY_ITEMS: usize = 200_000;
const MAX_DICT_ENTRIES: usize = 4096;
/// Cap on a single string or name token.
const MAX_STRING: usize = 16 * 1024 * 1024;
const MAX_NAME: usize = 4096;
/// How far past a candidate `EI` to look for proof that operators resume there.
const EI_LOOKAHEAD: usize = 64;

/// §7.2.3 Table 1: PDF white-space characters.
#[inline]
fn is_ws(b: u8) -> bool {
    matches!(b, b'\0' | b'\t' | b'\n' | b'\x0c' | b'\r' | b' ')
}

/// §7.2.3 Table 2: PDF delimiter characters.
#[inline]
fn is_delim(b: u8) -> bool {
    matches!(b, b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%')
}

/// A "regular" character: anything that is neither white space nor a delimiter.
/// Regular characters are what make up numbers, names, keywords and operators.
#[inline]
fn is_regular(b: u8) -> bool {
    !is_ws(b) && !is_delim(b)
}

/// Every content-stream operator in §A.1. Used to validate that a candidate `EI`
/// is followed by real operators rather than more pixel data.
const OPERATORS: &[&[u8]] = &[
    b"b", b"B", b"b*", b"B*", b"BDC", b"BI", b"BMC", b"BT", b"BX", b"c", b"cm", b"CS", b"cs", b"d",
    b"d0", b"d1", b"Do", b"DP", b"EI", b"EMC", b"ET", b"EX", b"f", b"F", b"f*", b"G", b"g", b"gs",
    b"h", b"i", b"ID", b"j", b"J", b"K", b"k", b"l", b"m", b"M", b"MP", b"n", b"q", b"Q", b"re",
    b"RG", b"rg", b"ri", b"s", b"S", b"SC", b"sc", b"SCN", b"scn", b"sh", b"T*", b"Tc", b"Td",
    b"TD", b"Tf", b"Tj", b"TJ", b"TL", b"Tm", b"Tr", b"Ts", b"Tw", b"Tz", b"v", b"w", b"W", b"W*",
    b"y", b"'", b"\"",
];

fn is_known_operator(tok: &[u8]) -> bool {
    OPERATORS.contains(&tok)
}

// ---------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------

/// Tokenize a content stream leniently. Never fails and never panics; returns
/// whatever could be recovered, which may be empty.
pub(crate) fn parse_operations_lenient(data: &[u8]) -> Vec<Operation> {
    Lexer { d: data, p: 0 }.run()
}

/// Whether array/dictionary nesting in `data` ever exceeds [`MAX_DEPTH`].
///
/// `lopdf::content::Content::decode` recurses per nesting level with no bound, so
/// `BT [[[[…(x)…]]]] TJ ET` overflows the stack — measured at N≈360 on an 8 MiB stack and
/// N≈48 on the 1 MiB an Android render thread gets. That is a guard-page fault, not an
/// unwind, so the `catch_unwind` at the JNI boundary cannot catch it and the whole process
/// dies. The lenient tokenizer already bounds itself at [`MAX_DEPTH`] and survives, but it
/// only runs when the strict parser *returns*.
///
/// So this is a pre-check, not a parser: it decides only whether the strict parser is safe
/// to call. It deliberately errs toward "too deep" — a false positive costs one pass of the
/// lenient tokenizer, which handles valid content identically, while a false negative is a
/// process kill. §7.3.4.2 literal strings (balanced unescaped parens, backslash escapes),
/// §7.3.4.3 hex strings and §7.2.4 comments are skipped so a `[` inside them cannot count.
fn nesting_is_too_deep(data: &[u8], max: u32) -> bool {
    let mut depth: u32 = 0;
    let mut i = 0usize;
    while i < data.len() {
        match data[i] {
            b'%' => {
                while i < data.len() && data[i] != b'\n' && data[i] != b'\r' {
                    i += 1;
                }
            }
            b'(' => {
                let mut nest = 1usize;
                i += 1;
                while i < data.len() && nest > 0 {
                    match data[i] {
                        b'\\' => i += 1,
                        b'(' => nest += 1,
                        b')' => nest -= 1,
                        _ => {}
                    }
                    i += 1;
                }
            }
            b'<' if data.get(i + 1) == Some(&b'<') => {
                depth += 1;
                if depth > max {
                    return true;
                }
                i += 2;
            }
            b'<' => {
                i += 1;
                while i < data.len() && data[i] != b'>' {
                    i += 1;
                }
                i += 1;
            }
            b'>' if data.get(i + 1) == Some(&b'>') => {
                depth = depth.saturating_sub(1);
                i += 2;
            }
            b'[' => {
                depth += 1;
                if depth > max {
                    return true;
                }
                i += 1;
            }
            b']' => {
                depth = depth.saturating_sub(1);
                i += 1;
            }
            _ => i += 1,
        }
    }
    false
}

/// Strict parse first, lenient tokenizer only if that fails, yields nothing, or is
/// unsafe to attempt at all.
fn strict_operations(bytes: &[u8]) -> Option<Vec<Operation>> {
    if nesting_is_too_deep(bytes, MAX_DEPTH) {
        return None;
    }
    match Content::decode(bytes) {
        Ok(content) if !content.operations.is_empty() => Some(content.operations),
        _ => None,
    }
}

/// Operations for a page's content, preferring lopdf's strict parser and falling
/// back to [`parse_operations_lenient`] when it fails or recovers nothing.
///
/// The `bool` is true when the fallback produced the result (for logging/tests).
pub(crate) fn page_operations(doc: &Document, page_id: ObjectId) -> (Vec<Operation>, bool) {
    // Decode with OUR filter chain, then parse strictly first so files that render
    // today are unaffected by the parse, falling back to the lenient tokenizer.
    //
    // NOT `get_and_decode_page_content`: that decodes through lopdf, whose ASCII85
    // decoder adds a base-85 5-tuple into a `u32` unchecked (lopdf/src/object.rs:777),
    // so a stream containing `uuuuu` panics with "attempt to add with overflow" in
    // debug and wraps silently in release. A panic here is fatal at the JNI boundary:
    // one malformed content stream kills the whole document. `page_content_bytes` is
    // also the more correct decode — it joins every /Contents stream with intervening
    // white space per §7.8.2, and yields nothing rather than ciphertext when a decoder
    // fails.
    let bytes = page_content_bytes(doc, page_id);
    if bytes.is_empty() {
        return (Vec::new(), false);
    }
    if let Some(ops) = strict_operations(&bytes) {
        return (ops, false);
    }
    // Either the parse failed (the inline-image `cut` case), it recovered nothing, or the
    // nesting was too deep to hand to lopdf at all.
    // All three render a blank page today, so the lenient path can only improve matters.
    (parse_operations_lenient(&bytes), true)
}

/// Operations for a NESTED content stream: a form XObject (`Do`), a soft-mask
/// group, a tiling-pattern cell or an annotation appearance stream.
///
/// Those streams reach `Content::decode` directly, and on failure the caller drops
/// the entire stream — so a single inline image inside a form XObject blanks that
/// whole form exactly as it blanks a whole page. Returns an empty vector only when
/// nothing at all could be recovered, so callers can use the result unconditionally.
pub(crate) fn stream_operations(doc: &Document, stream: &Stream) -> Vec<Operation> {
    operations_from_bytes(&stream_data_with_doc(doc, stream))
}

/// Strict parse first, lenient tokenizer only if that fails or yields nothing.
fn operations_from_bytes(bytes: &[u8]) -> Vec<Operation> {
    if let Some(ops) = strict_operations(bytes) {
        return ops;
    }
    parse_operations_lenient(bytes)
}

/// Concatenated, filter-decoded bytes of a page's `/Contents`.
///
/// Deliberately not `Document::get_page_content`: that writes the still-ENCODED
/// bytes when `decompressed_content` fails (lopdf document.rs:617), which would
/// hand compressed data to the tokenizer to be parsed as operators. Our
/// [`stream_data_with_doc`] handles the filter chain case-insensitively and yields
/// nothing rather than ciphertext when every decoder fails.
fn page_content_bytes(doc: &Document, page_id: ObjectId) -> Vec<u8> {
    let mut out = Vec::new();
    for id in doc.get_page_contents(page_id) {
        if let Ok(Object::Stream(s)) = doc.get_object(id) {
            out.extend_from_slice(&stream_data_with_doc(doc, s));
            // §7.8.2: the streams are concatenated with intervening white space so a
            // lexical token cannot span the boundary between two of them.
            out.push(b'\n');
        }
    }
    out
}

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

struct Lexer<'a> {
    d: &'a [u8],
    p: usize,
}

impl<'a> Lexer<'a> {
    #[inline]
    fn peek(&self) -> Option<u8> {
        self.d.get(self.p).copied()
    }

    /// Skip white space and `%` comments (§7.2.4).
    fn skip_ws(&mut self) {
        while let Some(b) = self.peek() {
            if is_ws(b) {
                self.p += 1;
            } else if b == b'%' {
                while let Some(c) = self.peek() {
                    if c == b'\n' || c == b'\r' {
                        break;
                    }
                    self.p += 1;
                }
            } else {
                break;
            }
        }
    }

    fn run(mut self) -> Vec<Operation> {
        let mut ops: Vec<Operation> = Vec::new();
        let mut stack: Vec<Object> = Vec::new();
        while self.p < self.d.len() && ops.len() < MAX_OPERATIONS {
            self.skip_ws();
            let Some(b) = self.peek() else { break };
            match b {
                // Operand starts.
                b'/' | b'(' | b'[' | b'<' => {
                    let before = self.p;
                    match self.object(0) {
                        Some(o) => {
                            if stack.len() < MAX_OPERANDS {
                                stack.push(o);
                            }
                        }
                        // Unparseable operand: step over one byte so we always make
                        // progress, then keep going.
                        None => self.p = (before + 1).max(self.p),
                    }
                }
                b'+' | b'-' | b'.' | b'0'..=b'9' => {
                    let o = self.number();
                    if stack.len() < MAX_OPERANDS {
                        stack.push(o);
                    }
                }
                // Stray closers and PostScript braces: not valid here, drop them.
                b']' | b'}' | b'{' | b')' => self.p += 1,
                b'>' => self.p += 1,
                _ => {
                    let tok = self.keyword();
                    if tok.is_empty() {
                        // Not a regular character (already handled above): skip it.
                        self.p += 1;
                        continue;
                    }
                    match tok.as_slice() {
                        b"true" => {
                            if stack.len() < MAX_OPERANDS {
                                stack.push(Object::Boolean(true));
                            }
                        }
                        b"false" => {
                            if stack.len() < MAX_OPERANDS {
                                stack.push(Object::Boolean(false));
                            }
                        }
                        b"null" => {
                            if stack.len() < MAX_OPERANDS {
                                stack.push(Object::Null);
                            }
                        }
                        b"BI" => {
                            // Pending operands cannot belong to BI; discard them.
                            stack.clear();
                            match self.inline_image() {
                                InlineResult::Image(stream) => ops.push(Operation {
                                    operator: "BI".to_string(),
                                    operands: vec![Object::Stream(stream)],
                                }),
                                // Lost sync inside binary data. Everything before the
                                // image is kept; going further would resynchronize into
                                // pixel data and emit garbage operators.
                                InlineResult::Abort => break,
                            }
                        }
                        // `ID`/`EI` outside a BI mean we are out of step; ignore them
                        // rather than treating them as drawing operators.
                        b"ID" | b"EI" => stack.clear(),
                        _ => {
                            let operands = std::mem::take(&mut stack);
                            ops.push(Operation {
                                operator: String::from_utf8_lossy(&tok).into_owned(),
                                operands,
                            });
                        }
                    }
                }
            }
        }
        ops
    }

    /// Read a run of regular characters (a number, keyword or operator token).
    fn keyword(&mut self) -> Vec<u8> {
        let s = self.p;
        while let Some(b) = self.peek() {
            if !is_regular(b) {
                break;
            }
            self.p += 1;
            if self.p - s >= MAX_NAME {
                break;
            }
        }
        self.d[s..self.p].to_vec()
    }

    /// Parse one operand. `depth` bounds array/dictionary nesting.
    fn object(&mut self, depth: u32) -> Option<Object> {
        self.skip_ws();
        let b = self.peek()?;
        match b {
            b'/' => Some(Object::Name(self.name())),
            b'(' => Some(Object::String(self.literal_string(), StringFormat::Literal)),
            b'[' => {
                if depth >= MAX_DEPTH {
                    return None;
                }
                Some(Object::Array(self.array(depth + 1)))
            }
            b'<' => {
                if self.d.get(self.p + 1) == Some(&b'<') {
                    if depth >= MAX_DEPTH {
                        return None;
                    }
                    Some(Object::Dictionary(self.dictionary(depth + 1)))
                } else {
                    Some(Object::String(self.hex_string(), StringFormat::Hexadecimal))
                }
            }
            b'+' | b'-' | b'.' | b'0'..=b'9' => Some(self.number()),
            _ if is_regular(b) => match self.keyword().as_slice() {
                b"true" => Some(Object::Boolean(true)),
                b"false" => Some(Object::Boolean(false)),
                b"null" => Some(Object::Null),
                // A bare keyword where a value was expected (e.g. an operator, or `R`
                // from a reference that cannot exist in a content stream).
                _ => None,
            },
            _ => None,
        }
    }

    /// §7.3.5 name object, including `#XX` hex escapes.
    fn name(&mut self) -> Vec<u8> {
        self.p += 1; // the '/'
        let mut out = Vec::new();
        while let Some(b) = self.peek() {
            if !is_regular(b) {
                break;
            }
            self.p += 1;
            if b == b'#' {
                let hi = self.peek().and_then(|c| (c as char).to_digit(16));
                let lo = self
                    .d
                    .get(self.p + 1)
                    .and_then(|c| (*c as char).to_digit(16));
                if let (Some(hi), Some(lo)) = (hi, lo) {
                    out.push(((hi << 4) | lo) as u8);
                    self.p += 2;
                    continue;
                }
                // Malformed escape: keep the '#' literally, as viewers do.
            }
            out.push(b);
            if out.len() >= MAX_NAME {
                break;
            }
        }
        out
    }

    /// §7.3.4.2 literal string: balanced parentheses plus backslash escapes.
    fn literal_string(&mut self) -> Vec<u8> {
        self.p += 1; // the '('
        let mut out = Vec::new();
        let mut depth = 1u32;
        while let Some(b) = self.peek() {
            self.p += 1;
            match b {
                b'\\' => {
                    let Some(e) = self.peek() else { break };
                    self.p += 1;
                    match e {
                        b'n' => out.push(b'\n'),
                        b'r' => out.push(b'\r'),
                        b't' => out.push(b'\t'),
                        b'b' => out.push(8),
                        b'f' => out.push(12),
                        b'(' => out.push(b'('),
                        b')' => out.push(b')'),
                        b'\\' => out.push(b'\\'),
                        // A backslash before an EOL is a line continuation: both the
                        // backslash and the EOL are dropped.
                        b'\r' => {
                            if self.peek() == Some(b'\n') {
                                self.p += 1;
                            }
                        }
                        b'\n' => {}
                        // \ddd octal, one to three digits.
                        b'0'..=b'7' => {
                            let mut v = (e - b'0') as u32;
                            for _ in 0..2 {
                                match self.peek() {
                                    Some(c @ b'0'..=b'7') => {
                                        v = v * 8 + (c - b'0') as u32;
                                        self.p += 1;
                                    }
                                    _ => break,
                                }
                            }
                            out.push((v & 0xFF) as u8);
                        }
                        // §7.3.4.2: a backslash before any other character is ignored
                        // and the character stands for itself.
                        other => out.push(other),
                    }
                }
                b'(' => {
                    depth += 1;
                    out.push(b'(');
                }
                b')' => {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                    out.push(b')');
                }
                // §7.3.4.2: an end-of-line inside a literal string means LF, whichever
                // of CR / LF / CRLF was actually written.
                b'\r' => {
                    if self.peek() == Some(b'\n') {
                        self.p += 1;
                    }
                    out.push(b'\n');
                }
                other => out.push(other),
            }
            if out.len() >= MAX_STRING {
                break;
            }
        }
        out
    }

    /// §7.3.4.3 hexadecimal string. A trailing odd digit is padded with `0`.
    fn hex_string(&mut self) -> Vec<u8> {
        self.p += 1; // the '<'
        let mut digits: Vec<u8> = Vec::new();
        while let Some(b) = self.peek() {
            self.p += 1;
            if b == b'>' {
                break;
            }
            if b.is_ascii_hexdigit() {
                digits.push(b);
            }
            if digits.len() >= MAX_STRING {
                break;
            }
        }
        if digits.len() % 2 == 1 {
            digits.push(b'0');
        }
        digits
            .chunks(2)
            .map(|c| {
                let hi = (c[0] as char).to_digit(16).unwrap_or(0) as u8;
                let lo = (c[1] as char).to_digit(16).unwrap_or(0) as u8;
                (hi << 4) | lo
            })
            .collect()
    }

    /// §7.3.6 array.
    fn array(&mut self, depth: u32) -> Vec<Object> {
        self.p += 1; // the '['
        let mut out = Vec::new();
        loop {
            self.skip_ws();
            match self.peek() {
                None => break,
                Some(b']') => {
                    self.p += 1;
                    break;
                }
                Some(_) => {
                    let before = self.p;
                    match self.object(depth) {
                        Some(o) => {
                            if out.len() < MAX_ARRAY_ITEMS {
                                out.push(o);
                            }
                        }
                        None => {
                            // Skip the offending token so the array still terminates.
                            if self.p == before {
                                self.p += 1;
                            }
                        }
                    }
                }
            }
        }
        out
    }

    /// §7.3.7 dictionary.
    fn dictionary(&mut self, depth: u32) -> Dictionary {
        self.p += 2; // the '<<'
        let mut dict = Dictionary::new();
        loop {
            self.skip_ws();
            match self.peek() {
                None => break,
                Some(b'>') => {
                    // Consume '>>' (or a lone '>' from a malformed stream).
                    self.p += 1;
                    if self.peek() == Some(b'>') {
                        self.p += 1;
                    }
                    break;
                }
                Some(b'/') => {
                    let key = self.name();
                    let before = self.p;
                    match self.object(depth) {
                        Some(v) => {
                            if dict.len() < MAX_DICT_ENTRIES {
                                dict.set(key, v);
                            }
                        }
                        None => {
                            if self.p == before {
                                self.p += 1;
                            }
                        }
                    }
                }
                // A non-name where a key belongs: drop it and resynchronize.
                Some(_) => {
                    let before = self.p;
                    if self.object(depth).is_none() && self.p == before {
                        self.p += 1;
                    }
                }
            }
        }
        dict
    }

    /// §7.3.2 / §7.3.3 numeric object, accepting the malformed forms real files
    /// contain: `.5`, `4.`, `+3`, `--5`, and stray extra decimal points.
    fn number(&mut self) -> Object {
        let tok = self.keyword();
        Object::from(parse_number(&tok))
    }

    // -- Inline images (§8.9.7) --------------------------------------------

    /// Parse `BI <dict> ID <binary> EI`, with `BI` already consumed.
    fn inline_image(&mut self) -> InlineResult {
        // 1. Key/value pairs up to the `ID` keyword. They are NOT wrapped in `<< >>`.
        let mut dict = Dictionary::new();
        loop {
            self.skip_ws();
            let Some(b) = self.peek() else {
                return InlineResult::Abort;
            };
            if b == b'/' {
                let key = self.name();
                let before = self.p;
                match self.object(0) {
                    Some(v) => {
                        if dict.len() < MAX_DICT_ENTRIES {
                            dict.set(key, v);
                        }
                    }
                    None => {
                        if self.p == before {
                            self.p += 1;
                        }
                    }
                }
                continue;
            }
            if is_regular(b) {
                let before = self.p;
                let tok = self.keyword();
                match tok.as_slice() {
                    b"ID" => break,
                    // `EI` with no `ID`: a degenerate but harmless empty image.
                    b"EI" => return InlineResult::Image(Stream::new(dict, Vec::new())),
                    b"true" | b"false" | b"null" => continue,
                    _ if tok.is_empty() => {
                        self.p = before + 1;
                        continue;
                    }
                    // Stray token in the dictionary: ignore and carry on.
                    _ => continue,
                }
            }
            // Any other byte (a stray delimiter): step over it.
            self.p += 1;
        }

        // 2. §8.9.7: exactly ONE whitespace byte separates `ID` from the data.
        if self.peek().is_some_and(is_ws) {
            self.p += 1;
        }
        let data_start = self.p;
        let remaining = self.d.len() - data_start;

        // 3. Prefer a COMPUTED length over scanning for `EI`.
        //
        // `extra` covers producers that write CRLF after `ID` even though the spec
        // allows a single byte: the second candidate treats the LF as a separator
        // rather than as the first pixel byte. Whichever candidate lands on a
        // verified `EI` wins, so this cannot silently shift the data.
        for len in inline_len_candidates(&dict, remaining) {
            for extra in [0usize, 1usize] {
                if extra == 1 && !self.d.get(data_start).copied().is_some_and(is_ws) {
                    continue;
                }
                let s = data_start + extra;
                let Some(end) = s.checked_add(len) else { continue };
                if end > self.d.len() {
                    continue;
                }
                if let Some(after) = ei_at(self.d, end) {
                    let data = self.d[s..end].to_vec();
                    self.p = after;
                    return InlineResult::Image(Stream::new(dict, data));
                }
            }
        }

        // 4. A computable length whose `EI` did not verify, or no computable length
        //    at all (a filtered image without `/L`). Scan — but validate.
        if let Some((data_end, after)) = scan_for_ei(self.d, data_start) {
            let data = self.d[data_start..data_end].to_vec();
            self.p = after;
            return InlineResult::Image(Stream::new(dict, data));
        }

        // 5. No credible `EI` anywhere. The stream is very likely truncated inside
        //    the image. Keep the data we have and stop: resuming here would mean
        //    tokenizing pixel data.
        let end = self.d.len().min(data_start.saturating_add(MAX_INLINE_DATA));
        let data = self.d[data_start.min(end)..end].to_vec();
        self.p = self.d.len();
        InlineResult::Image(Stream::new(dict, data))
    }
}

/// Cap on the bytes kept for one inline image when the stream is truncated.
const MAX_INLINE_DATA: usize = 64 * 1024 * 1024;

enum InlineResult {
    Image(Stream),
    Abort,
}

/// Parse a PDF numeric token leniently, returning an integer when the token has no
/// fractional part. Handles `.5`, `4.`, `+3`, `--5` and repeated decimal points.
fn parse_number(tok: &[u8]) -> NumTok {
    let mut neg = false;
    let mut i = 0;
    // Consume any run of signs. `--5` occurs in real files; Acrobat reads it as -5,
    // i.e. repeated minus signs do NOT toggle, so a single flag is correct here.
    while i < tok.len() && (tok[i] == b'+' || tok[i] == b'-') {
        if tok[i] == b'-' {
            neg = true;
        }
        i += 1;
    }
    let mut int_part: i64 = 0;
    let mut frac: f64 = 0.0;
    let mut scale = 0.1f64;
    let mut seen_dot = false;
    let mut overflow = false;
    let mut digits = 0usize;
    for &b in &tok[i..] {
        if b == b'.' {
            // A second '.' is malformed; treat the following digits as continuing
            // the fraction rather than discarding the whole token.
            seen_dot = true;
            continue;
        }
        if !b.is_ascii_digit() {
            // Trailing junk (e.g. `12abc`): keep what parsed.
            break;
        }
        digits += 1;
        let d = (b - b'0') as i64;
        if seen_dot {
            frac += d as f64 * scale;
            scale *= 0.1;
        } else {
            match int_part.checked_mul(10).and_then(|v| v.checked_add(d)) {
                Some(v) => int_part = v,
                None => overflow = true,
            }
        }
    }
    if digits == 0 {
        // A lone sign or dot. Zero is the harmless reading.
        return NumTok::Int(0);
    }
    if seen_dot || overflow {
        let v = int_part as f64 + frac;
        NumTok::Real(if neg { -v } else { v })
    } else {
        NumTok::Int(if neg { -int_part } else { int_part })
    }
}

enum NumTok {
    Int(i64),
    Real(f64),
}

impl From<NumTok> for Object {
    fn from(n: NumTok) -> Object {
        match n {
            NumTok::Int(i) => Object::Integer(i),
            NumTok::Real(r) => Object::Real(r as f32),
        }
    }
}

/// Byte offset just past a valid `EI` at or after `from` (skipping white space).
/// `None` when `from` is not a credible end-of-image position.
fn ei_at(d: &[u8], from: usize) -> Option<usize> {
    let mut i = from;
    // A truncated stream that simply ends where the data ends is acceptable.
    if i >= d.len() {
        return Some(d.len());
    }
    while i < d.len() && is_ws(d[i]) {
        i += 1;
    }
    if d.get(i) == Some(&b'E') && d.get(i + 1) == Some(&b'I') {
        let after = i + 2;
        // `EI` must be a complete token, not the start of `EIx`.
        if after == d.len() || !is_regular(d[after]) {
            return Some(after);
        }
    }
    None
}

/// Search for the `EI` that terminates inline-image data starting at `from`.
///
/// Returns `(end of image data, offset just past EI)`. A candidate must be
/// whitespace-delimited on both sides. The first pass additionally requires the
/// bytes after it to look like resumed content-stream operators, which is what
/// stops a chance `EI` inside pixel data from being accepted; only if no candidate
/// passes that test does a second pass take the first merely well-delimited one.
fn scan_for_ei(d: &[u8], from: usize) -> Option<(usize, usize)> {
    for strict in [true, false] {
        let mut i = from;
        while i + 1 < d.len() {
            if d[i] == b'E' && d[i + 1] == b'I' {
                let preceded = i > from && is_ws(d[i - 1]);
                let after = i + 2;
                let followed = after == d.len() || !is_regular(d[after]);
                if preceded && followed && (!strict || plausible_resume(&d[after..])) {
                    return Some((i - 1, after));
                }
            }
            i += 1;
        }
    }
    None
}

/// Whether `d` (the bytes right after a candidate `EI`) looks like a content stream
/// resuming, rather than more binary pixel data.
///
/// Accepts when a known operator turns up within [`EI_LOOKAHEAD`] bytes, or the
/// stream simply ends. Rejects as soon as a byte appears that could not occur in
/// content-stream syntax at this point, since binary data is dense in such bytes.
fn plausible_resume(d: &[u8]) -> bool {
    if d.is_empty() {
        return true;
    }
    let limit = d.len().min(EI_LOOKAHEAD);
    let mut i = 0;
    while i < limit {
        let b = d[i];
        if is_ws(b) {
            i += 1;
            continue;
        }
        // Content-stream syntax at this point is printable ASCII. A control byte or
        // a byte with the high bit set is the signature of pixel data.
        if !(0x20..=0x7e).contains(&b) {
            return false;
        }
        if b.is_ascii_alphabetic() || b == b'\'' || b == b'"' {
            let s = i;
            while i < d.len() && is_regular(d[i]) {
                i += 1;
            }
            // A keyword here is either an operator (good) or noise (bad).
            return is_known_operator(&d[s..i]);
        }
        // An operand or a delimiter: legitimate, but not yet proof. Keep looking for
        // the operator that must follow.
        i += 1;
    }
    // Ran out of look-ahead having seen only plausible bytes. Everything scanned was
    // printable ASCII, which binary pixel data essentially never is for 64 bytes.
    true
}

// ---------------------------------------------------------------------------
// Inline-image data length (§8.9.7)
// ---------------------------------------------------------------------------

fn abbr<'a>(dict: &'a Dictionary, short: &[u8], long: &[u8]) -> Option<&'a Object> {
    dict.get(short).or_else(|_| dict.get(long)).ok()
}

fn int_of(obj: Option<&Object>) -> Option<i64> {
    match obj? {
        Object::Integer(i) => Some(*i),
        Object::Real(r) => Some(*r as i64),
        _ => None,
    }
}

fn is_true(dict: &Dictionary, short: &[u8], long: &[u8]) -> bool {
    matches!(abbr(dict, short, long), Some(Object::Boolean(true)))
}

/// Candidate byte lengths for the raw data of an inline image, best first.
///
/// An empty result means the length is genuinely not computable and the caller must
/// fall back to scanning. Several candidates are returned when the component count
/// is ambiguous (a `/CS` naming a colour-space resource we cannot resolve here); the
/// caller keeps the one whose `EI` verifies, which is far safer than scanning.
fn inline_len_candidates(dict: &Dictionary, remaining: usize) -> Vec<usize> {
    // `/L` (and its `/Length` synonym) is authoritative and, since PDF 2.0, exists
    // precisely so that a consumer need not scan for `EI`.
    if let Some(l) = int_of(abbr(dict, b"L", b"Length")) {
        if l >= 0 && (l as u64) <= remaining as u64 {
            return vec![l as usize];
        }
    }
    // A filter makes the encoded length unknowable from the geometry.
    if abbr(dict, b"F", b"Filter").is_some() {
        return Vec::new();
    }
    let (Some(w), Some(h)) = (
        int_of(abbr(dict, b"W", b"Width")),
        int_of(abbr(dict, b"H", b"Height")),
    ) else {
        return Vec::new();
    };
    if w <= 0 || h <= 0 {
        return Vec::new();
    }
    let (w, h) = (w as u64, h as u64);

    // §8.9.6.2: a stencil mask has one bit per sample and no colour space at all,
    // which is exactly the case lopdf rejects for its missing /BPC and /CS.
    let stencil = is_true(dict, b"IM", b"ImageMask");
    let bpc = if stencil {
        1
    } else {
        match int_of(abbr(dict, b"BPC", b"BitsPerComponent")).unwrap_or(8) {
            v @ (1 | 2 | 4 | 8 | 16) => v as u64,
            _ => return Vec::new(),
        }
    };

    let ncomps: Vec<u64> = if stencil {
        vec![1]
    } else {
        colorspace_components(abbr(dict, b"CS", b"ColorSpace"))
    };

    let mut out = Vec::new();
    for n in ncomps {
        // ceil(W * ncomp * bpc / 8) per row, rows are byte-aligned (§8.9.5.1).
        let Some(bits) = w.checked_mul(n).and_then(|v| v.checked_mul(bpc)) else {
            continue;
        };
        let row = bits.div_ceil(8);
        let Some(total) = row.checked_mul(h) else { continue };
        if total <= remaining as u64 && total <= MAX_INLINE_DATA as u64 {
            out.push(total as usize);
        }
    }
    out
}

/// Candidate component counts for an inline image's `/CS`, best first.
///
/// §8.9.7 Table 93 abbreviates the device spaces as `/G`, `/RGB`, `/CMYK` and `/I`.
/// `/G` in particular is valid but missing from lopdf's accepted list, which is one
/// of the three ways a perfectly good inline image blanks a page today.
fn colorspace_components(cs: Option<&Object>) -> Vec<u64> {
    // No colour space at all on a non-stencil image is malformed (§8.9.7 requires one
    // unless /ImageMask is true), which is also where lopdf gives up entirely. Offer
    // the possible counts and let the `EI` check decide rather than guessing once.
    let Some(cs) = cs else { return vec![1, 3, 4] };
    match cs {
        Object::Name(n) => match n.as_slice() {
            b"DeviceGray" | b"G" | b"CalGray" => vec![1],
            b"DeviceRGB" | b"RGB" | b"CalRGB" => vec![3],
            b"DeviceCMYK" | b"CMYK" => vec![4],
            // Indexed is always one component per sample, whatever its base space.
            b"Indexed" | b"I" => vec![1],
            b"Lab" => vec![3],
            // A name referring to the page's /ColorSpace resources, which are not
            // available here. Offer the possible component counts in order of
            // real-world frequency; the caller keeps whichever lands on a verified
            // `EI`, which is far safer than scanning binary data for one.
            _ => vec![1, 3, 4],
        },
        Object::Array(a) => {
            let family = match a.first() {
                Some(Object::Name(n)) => n.as_slice(),
                _ => return vec![1, 3, 4],
            };
            match family {
                b"Indexed" | b"I" => vec![1],
                b"CalGray" => vec![1],
                b"CalRGB" | b"Lab" => vec![3],
                // /N lives in the ICC profile's stream dictionary, which an inline
                // image cannot carry, so the count stays ambiguous.
                b"ICCBased" => vec![3, 1, 4],
                // Arity is the length of the names array in element 1.
                b"DeviceN" => match a.get(1) {
                    Some(Object::Array(names)) if !names.is_empty() => vec![names.len() as u64],
                    _ => vec![1, 3, 4],
                },
                _ => vec![1, 3, 4],
            }
        }
        _ => vec![1, 3, 4],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn operators(ops: &[Operation]) -> Vec<String> {
        ops.iter().map(|o| o.operator.clone()).collect()
    }

    /// Raw data of every recovered inline image, in order.
    fn inline_data(ops: &[Operation]) -> Vec<Vec<u8>> {
        ops.iter()
            .filter(|o| o.operator == "BI")
            .filter_map(|o| match o.operands.first() {
                Some(Object::Stream(s)) => Some(s.content.clone()),
                _ => None,
            })
            .collect()
    }

    fn operands_of(ops: &[Operation], operator: &str) -> Vec<Object> {
        ops.iter()
            .find(|o| o.operator == operator)
            .map(|o| o.operands.clone())
            .unwrap_or_default()
    }

    #[test]
    fn empty_input_yields_nothing() {
        assert!(parse_operations_lenient(b"").is_empty());
        assert!(parse_operations_lenient(b"   \r\n\t ").is_empty());
    }

    #[test]
    fn tokenizes_an_ordinary_stream() {
        let ops = parse_operations_lenient(b"q 1 0 0 1 10 20 cm BT /F1 12 Tf (Hi) Tj ET Q");
        assert_eq!(
            operators(&ops),
            vec!["q", "cm", "BT", "Tf", "Tj", "ET", "Q"]
        );
        assert_eq!(
            operands_of(&ops, "Tj"),
            vec![Object::String(b"Hi".to_vec(), StringFormat::Literal)]
        );
    }

    #[test]
    fn malformed_number_forms() {
        // `.5`, `4.`, `+3` and `--5` all occur in real files; lopdf's `real`/`integer`
        // parsers reject some of them outright.
        let ops = parse_operations_lenient(b".5 4. +3 --5 -0.25 12abc m");
        let o = operands_of(&ops, "m");
        assert_eq!(o.len(), 6, "every numeric token must survive: {o:?}");
        assert_eq!(o[0], Object::Real(0.5));
        assert_eq!(o[1], Object::Real(4.0));
        assert_eq!(o[2], Object::Integer(3));
        assert_eq!(o[3], Object::Integer(-5), "`--5` reads as -5, as Acrobat does");
        assert_eq!(o[4], Object::Real(-0.25));
        assert_eq!(o[5], Object::Integer(12), "trailing junk is dropped");
    }

    #[test]
    fn literal_string_nesting_and_escapes() {
        let ops = parse_operations_lenient(b"(a(b)c) Tj");
        assert_eq!(
            operands_of(&ops, "Tj"),
            vec![Object::String(b"a(b)c".to_vec(), StringFormat::Literal)]
        );

        // \101 == 'A'; \) is a literal paren; a backslash before EOL is a continuation.
        let ops = parse_operations_lenient(b"(\\101\\)\\n\\\n end) Tj");
        let Object::String(s, _) = &operands_of(&ops, "Tj")[0] else {
            panic!("expected a string");
        };
        assert_eq!(s.as_slice(), b"A)\n end");

        // A bare CR inside a literal string means LF (7.3.4.2).
        let ops = parse_operations_lenient(b"(a\rb) Tj");
        let Object::String(s, _) = &operands_of(&ops, "Tj")[0] else {
            panic!("expected a string");
        };
        assert_eq!(s.as_slice(), b"a\nb");
    }

    #[test]
    fn hex_string_pads_odd_digit() {
        let ops = parse_operations_lenient(b"<48656C6C6F> Tj <4> Tj");
        let data: Vec<Vec<u8>> = ops
            .iter()
            .filter(|o| o.operator == "Tj")
            .filter_map(|o| match o.operands.first() {
                Some(Object::String(s, _)) => Some(s.clone()),
                _ => None,
            })
            .collect();
        assert_eq!(data[0], b"Hello".to_vec());
        assert_eq!(data[1], vec![0x40], "a trailing nibble is padded with 0");
    }

    #[test]
    fn name_hex_escapes() {
        let ops = parse_operations_lenient(b"/A#20B gs");
        assert_eq!(operands_of(&ops, "gs"), vec![Object::Name(b"A B".to_vec())]);
    }

    #[test]
    fn arrays_dicts_booleans_null_and_comments() {
        let ops = parse_operations_lenient(
            b"% a comment\n[3 1] 0 d\n<</Type/Foo/N 2>> BDC true false null gs",
        );
        assert_eq!(operators(&ops), vec!["d", "BDC", "gs"]);
        assert_eq!(
            operands_of(&ops, "d"),
            vec![
                Object::Array(vec![Object::Integer(3), Object::Integer(1)]),
                Object::Integer(0)
            ]
        );
        assert_eq!(
            operands_of(&ops, "gs"),
            vec![Object::Boolean(true), Object::Boolean(false), Object::Null]
        );
        let bdc = operands_of(&ops, "BDC");
        let Object::Dictionary(d) = &bdc[0] else {
            panic!("expected a dictionary, got {bdc:?}");
        };
        assert_eq!(d.get(b"N").unwrap(), &Object::Integer(2));
    }

    // -- The inline-image cases lopdf hard-fails on -------------------------

    #[test]
    fn stencil_without_bpc_or_cs() {
        // `/IM true` implies one bit per sample and no colour space, so lopdf's
        // mandatory /BPC and /CS lookups fail and the whole page is lost.
        // ceil(8*1*1/8) = 1 byte per row, 2 rows.
        let ops = parse_operations_lenient(b"q BI /W 8 /H 2 /IM true ID \xAA\xBB EI Q");
        assert_eq!(operators(&ops), vec!["q", "BI", "Q"]);
        assert_eq!(inline_data(&ops), vec![vec![0xAA, 0xBB]]);
    }

    #[test]
    fn cs_g_abbreviation() {
        // 8.9.7 Table 93 lists /G for DeviceGray, but it is absent from lopdf's list.
        let ops = parse_operations_lenient(b"BI /W 2 /H 2 /BPC 8 /CS /G ID \x01\x02\x03\x04 EI S");
        assert_eq!(operators(&ops), vec!["BI", "S"]);
        assert_eq!(inline_data(&ops), vec![vec![1, 2, 3, 4]]);
    }

    #[test]
    fn filtered_inline_image_with_length() {
        // Any /F is an outright Unimplemented error in lopdf. With /L the data length
        // is exact even though the geometry says nothing about the encoded size.
        let ops =
            parse_operations_lenient(b"BI /W 4 /H 4 /BPC 8 /CS /G /F /AHx /L 9 ID 41424344> EI Q");
        assert_eq!(operators(&ops), vec!["BI", "Q"]);
        assert_eq!(inline_data(&ops), vec![b"41424344>".to_vec()]);
    }

    #[test]
    fn filtered_inline_image_without_length_falls_back_to_scanning() {
        // No /L and a filter, so the length genuinely cannot be computed. This is the
        // only case that may scan, and the candidate must still validate.
        let ops = parse_operations_lenient(b"BI /W 4 /H 4 /BPC 8 /CS /G /F /AHx ID 41424344> EI Q");
        assert_eq!(operators(&ops), vec!["BI", "Q"]);
        assert_eq!(inline_data(&ops), vec![b"41424344>".to_vec()]);
    }

    // -- The false-EI hazard ----------------------------------------------

    #[test]
    fn binary_data_containing_the_bytes_ei() {
        // The data holds "EI" twice, the second occurrence whitespace-delimited on both
        // sides (0x20 before, NUL after — NUL is PDF white space). A scanning parser
        // resynchronizes into the middle of the image here.
        let mut s = Vec::new();
        s.extend_from_slice(b"q BI /W 4 /H 2 /BPC 8 /CS /G ID ");
        let data = vec![0x45, 0x49, 0x20, 0x45, 0x49, 0x00, 0xFF, 0x41];
        s.extend_from_slice(&data);
        s.extend_from_slice(b" EI 10 20 m S Q");
        let ops = parse_operations_lenient(&s);
        assert_eq!(inline_data(&ops), vec![data], "must skip the computed length");
        assert_eq!(operators(&ops), vec!["q", "BI", "m", "S", "Q"]);
    }

    #[test]
    fn binary_data_containing_a_whitespace_delimited_ei_followed_by_an_operator() {
        // The worst case: the pixel data contains " EI Q ", so the false match is
        // whitespace-delimited AND followed by a real operator. Every scan-based
        // heuristic accepts it. Only the computed length survives this.
        // 8 columns x 1 row x 8bpc x 1 comp = exactly 8 bytes.
        let data = b"x EI Q y";
        let mut s = Vec::new();
        s.extend_from_slice(b"q BI /W 8 /H 1 /BPC 8 /CS /G ID ");
        s.extend_from_slice(data);
        s.extend_from_slice(b" EI 1 0 0 1 5 5 cm Q");
        let ops = parse_operations_lenient(&s);
        assert_eq!(
            inline_data(&ops),
            vec![data.to_vec()],
            "the false ` EI Q ` inside the pixel data must not terminate the image"
        );
        assert_eq!(operators(&ops), vec!["q", "BI", "cm", "Q"]);
    }

    #[test]
    fn page_content_after_an_inline_image_survives() {
        // The actual user-visible bug: today the inline image aborts the whole stream,
        // so the text and path AFTER it are lost along with everything else.
        let mut s = Vec::new();
        s.extend_from_slice(b"BT /F1 9 Tf (before) Tj ET\n");
        s.extend_from_slice(b"BI /W 4 /H 1 /IM true ID \x0F EI\n");
        s.extend_from_slice(b"BT /F1 9 Tf (after) Tj ET 1 2 m 3 4 l S");
        let ops = parse_operations_lenient(&s);
        assert_eq!(
            operators(&ops),
            vec![
                "BT", "Tf", "Tj", "ET", "BI", "BT", "Tf", "Tj", "ET", "m", "l", "S"
            ]
        );
        let strings: Vec<Vec<u8>> = ops
            .iter()
            .filter(|o| o.operator == "Tj")
            .filter_map(|o| match o.operands.first() {
                Some(Object::String(v, _)) => Some(v.clone()),
                _ => None,
            })
            .collect();
        assert_eq!(strings, vec![b"before".to_vec(), b"after".to_vec()]);
    }

    #[test]
    fn crlf_after_id_is_not_taken_as_pixel_data() {
        // 8.9.7 allows exactly one white-space byte after ID, but producers write CRLF.
        // The LF must be treated as a separator, not as the first sample.
        let mut s = Vec::new();
        s.extend_from_slice(b"BI /W 2 /H 1 /BPC 8 /CS /G ID\r\n");
        s.extend_from_slice(&[0xAA, 0xBB]);
        s.extend_from_slice(b" EI Q");
        let ops = parse_operations_lenient(&s);
        assert_eq!(inline_data(&ops), vec![vec![0xAA, 0xBB]]);
        assert_eq!(operators(&ops), vec!["BI", "Q"]);
    }

    #[test]
    fn ambiguous_colorspace_is_resolved_by_verifying_ei() {
        // /CS names a page colour-space resource we cannot resolve, so the component
        // count is unknown. Candidates are tried (1, 3, 4 components => 8, 24, 32
        // bytes) and the one whose EI verifies is kept.
        let data = vec![0x41u8; 24]; // 4 x 2 x 8bpc x 3 components
        let mut s = Vec::new();
        s.extend_from_slice(b"BI /W 4 /H 2 /BPC 8 /CS /CS0 ID ");
        s.extend_from_slice(&data);
        s.extend_from_slice(b" EI Q");
        let ops = parse_operations_lenient(&s);
        assert_eq!(inline_data(&ops), vec![data]);
        assert_eq!(operators(&ops), vec!["BI", "Q"]);
    }

    #[test]
    fn truncated_inline_image_keeps_preceding_content() {
        // The stream ends inside the image data. Everything before it must survive.
        let ops = parse_operations_lenient(b"q 1 0 0 1 2 2 cm BI /W 900 /H 900 /IM true ID \x01\x02");
        assert_eq!(operators(&ops), vec!["q", "cm", "BI"]);
    }

    // -- Recovery and robustness -------------------------------------------

    #[test]
    fn recovers_after_unparseable_bytes() {
        let ops = parse_operations_lenient(b"q \xFF\xFE\x01 10 20 m 30 40 l S Q");
        let names = operators(&ops);
        for expected in ["q", "m", "l", "S", "Q"] {
            assert!(names.contains(&expected.to_string()), "lost {expected} in {names:?}");
        }
    }

    #[test]
    fn unterminated_constructs_do_not_hang() {
        // Each of these ends mid-token; the tokenizer must terminate regardless.
        for bad in [
            &b"(unterminated"[..],
            &b"<48656"[..],
            &b"<</Key"[..],
            &b"[1 2 3"[..],
            &b"/Name"[..],
            &b"BI /W 2"[..],
            &b"BI /W 2 /H 2 /BPC 8 /CS /G ID"[..],
        ] {
            let _ = parse_operations_lenient(bad);
        }
    }

    #[test]
    fn matches_the_end_to_end_fixture_payloads() {
        // The exact byte sequences golden_tests.rs builds, verified at the tokenizer
        // layer so a failure there can be attributed to the wiring rather than here.
        // Each asserts the recovered data length, since that is what decides whether
        // the image decodes at its true dimensions.
        let mut px: Vec<u8> = (0u8..16).map(|i| i.wrapping_mul(17)).collect();
        px[4] = b' ';
        px[5] = b'E';
        px[6] = b'I';
        px[7] = b' ';
        let mut bi = b"BI /W 4 /H 4 /CS /G /BPC 8 ID ".to_vec();
        bi.extend_from_slice(&px);
        bi.extend_from_slice(b" EI");
        let ops = parse_operations_lenient(&bi);
        assert_eq!(inline_data(&ops), vec![px], "4x4x8bpc gray is exactly 16 bytes");

        // /CS /G.
        let mut bi = b"BI /W 2 /H 2 /CS /G /BPC 8 ID ".to_vec();
        bi.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        bi.extend_from_slice(b" EI");
        assert_eq!(
            inline_data(&parse_operations_lenient(&bi)),
            vec![vec![0x00, 0x40, 0x80, 0xFF]]
        );

        // A missing /BPC defaults to 8 rather than being an error.
        let mut bi = b"BI /W 2 /H 2 /CS /G ID ".to_vec();
        bi.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        bi.extend_from_slice(b" EI");
        assert_eq!(
            inline_data(&parse_operations_lenient(&bi)),
            vec![vec![0x00, 0x40, 0x80, 0xFF]]
        );

        // /F /AHx with no /L: the length is not derivable, so this is the scan path.
        let ops = parse_operations_lenient(b"BI /W 2 /H 2 /CS /G /BPC 8 /F /AHx ID 004080FF> EI");
        assert_eq!(inline_data(&ops), vec![b"004080FF>".to_vec()]);
    }

    // -- Document-level recovery -------------------------------------------

    /// Minimal single-page document whose `/Contents` is `content`, uncompressed.
    fn one_page_doc(content: &[u8]) -> (Document, ObjectId) {
        let mut doc = Document::with_version("1.5");
        let content_id = doc.add_object(Stream::new(Dictionary::new(), content.to_vec()));
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "Contents" => content_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);
        (doc, page_id)
    }

    #[test]
    fn page_operations_recovers_a_page_lopdf_cannot_parse() {
        // No /BPC, so lopdf's inline-image parser errors inside `cut(...)` and the
        // whole content stream fails to decode.
        let mut content = b"q 1 0 0 1 10 20 cm\n".to_vec();
        content.extend_from_slice(b"BI /W 2 /H 2 /CS /G ID ");
        content.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        content.extend_from_slice(b" EI\nQ\n0 1 0 rg\n300 300 40 40 re f\n");
        let (doc, page_id) = one_page_doc(&content);

        // Establish that this really is the P0 rather than a test that would pass
        // anyway: lopdf must genuinely fail on this page.
        assert!(
            doc.get_and_decode_page_content(page_id).is_err(),
            "precondition: lopdf is expected to reject this content stream"
        );

        let (ops, recovered) = page_operations(&doc, page_id);
        assert!(recovered, "the lenient fallback must have produced the result");
        let names = operators(&ops);
        assert!(names.contains(&"BI".to_string()), "got {names:?}");
        // The operators AFTER the inline image are exactly what is lost today.
        for op in ["q", "cm", "Q", "rg", "re", "f"] {
            assert!(names.contains(&op.to_string()), "lost `{op}` after BI: {names:?}");
        }
        assert_eq!(inline_data(&ops), vec![vec![0x00, 0x40, 0x80, 0xFF]]);
    }

    #[test]
    fn page_operations_leaves_a_healthy_page_to_lopdf() {
        // A stream lopdf parses fine must come back from lopdf untouched, so wiring
        // the fallback in cannot regress any file that renders today.
        let content = b"q 1 0 0 1 10 20 cm BT /F1 12 Tf (hi) Tj ET Q 1 2 m 3 4 l S";
        let (doc, page_id) = one_page_doc(content);
        let strict = doc
            .get_and_decode_page_content(page_id)
            .expect("precondition: lopdf parses this")
            .operations;
        let (ops, recovered) = page_operations(&doc, page_id);
        assert!(!recovered, "the strict parser's result must be preferred");
        assert_eq!(operators(&ops), operators(&strict));
    }

    #[test]
    fn page_operations_handles_a_page_with_no_contents() {
        let mut doc = Document::with_version("1.5");
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        });
        let (ops, recovered) = page_operations(&doc, page_id);
        assert!(ops.is_empty());
        assert!(!recovered);
    }

    #[test]
    fn stream_operations_recovers_a_form_xobject_lopdf_cannot_parse() {
        // A form XObject / soft-mask group / tiling cell containing an inline image
        // hits Content::decode directly, and the caller drops the whole stream on Err —
        // so one BI blanks the entire form exactly as it blanks a whole page.
        let doc = Document::with_version("1.5");
        let mut content = b"q 0 0 1 rg\n".to_vec();
        content.extend_from_slice(b"BI /W 2 /H 2 /CS /G ID ");
        content.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        content.extend_from_slice(b" EI\nQ 5 5 20 20 re f\n");
        let stream = Stream::new(dictionary! { "Type" => "XObject", "Subtype" => "Form" }, content);

        assert!(
            Content::decode(&stream.content).is_err(),
            "precondition: lopdf is expected to reject this nested stream"
        );

        let ops = stream_operations(&doc, &stream);
        let names = operators(&ops);
        for op in ["q", "rg", "BI", "Q", "re", "f"] {
            assert!(names.contains(&op.to_string()), "lost `{op}`: {names:?}");
        }
    }

    #[test]
    fn the_depth_pre_check_only_counts_real_nesting() {
        // The guard decides whether lopdf's unbounded strict parser is safe to call, so a
        // false negative is a process kill. But a false POSITIVE silently demotes healthy
        // content to the lenient tokenizer, so brackets inside strings and comments must
        // not count.
        assert!(!nesting_is_too_deep(b"BT [(x)] TJ ET", MAX_DEPTH));
        let deep = |n: usize| {
            let mut v = b"BT ".to_vec();
            v.extend(std::iter::repeat_n(b'[', n));
            v.extend_from_slice(b"(x)");
            v.extend(std::iter::repeat_n(b']', n));
            v.extend_from_slice(b" TJ ET");
            v
        };
        assert!(!nesting_is_too_deep(&deep(MAX_DEPTH as usize), MAX_DEPTH));
        assert!(nesting_is_too_deep(&deep(MAX_DEPTH as usize + 1), MAX_DEPTH));
        // §7.3.4.2: a literal string may contain unescaped balanced parens and escaped
        // anything. None of these brackets are nesting.
        let mut s = b"BT (".to_vec();
        s.extend(std::iter::repeat_n(b'[', 200));
        s.extend_from_slice(b"(nested) \\) \\( ");
        s.extend_from_slice(b") Tj ET");
        assert!(!nesting_is_too_deep(&s, MAX_DEPTH));
        // §7.2.4 comment, and a §7.3.4.3 hex string.
        let mut c = b"% ".to_vec();
        c.extend(std::iter::repeat_n(b'[', 200));
        c.extend_from_slice(b"\nBT <");
        c.extend(std::iter::repeat_n(b'A', 40));
        c.extend_from_slice(b"> Tj ET");
        assert!(!nesting_is_too_deep(&c, MAX_DEPTH));
        // Dictionaries count, and an unterminated construct must not run past the end.
        assert!(nesting_is_too_deep(&b"<<".repeat(MAX_DEPTH as usize + 1), MAX_DEPTH));
        assert!(!nesting_is_too_deep(b"BT (unterminated", MAX_DEPTH));
        assert!(!nesting_is_too_deep(b"BT <unterminated", MAX_DEPTH));
    }

    #[test]
    fn stream_operations_leaves_a_healthy_nested_stream_to_lopdf() {
        let doc = Document::with_version("1.5");
        let stream = Stream::new(Dictionary::new(), b"q 1 0 0 1 3 4 cm 0 0 10 10 re f Q".to_vec());
        let strict = Content::decode(&stream.content).expect("precondition").operations;
        assert_eq!(operators(&stream_operations(&doc, &stream)), operators(&strict));
    }

    #[test]
    fn arbitrary_bytes_never_panic() {
        // Deterministic LCG, so a failure is always reproducible.
        let mut state: u32 = 0x1234_5678;
        let mut buf = vec![0u8; 4096];
        for _ in 0..64 {
            for slot in buf.iter_mut() {
                state = state.wrapping_mul(1_103_515_245).wrapping_add(12_345);
                *slot = (state >> 16) as u8;
            }
            let _ = parse_operations_lenient(&buf);
        }
        // Bytes biased towards content-stream syntax exercise deeper paths.
        let alphabet = b"BI ID EI /W /H /CS /G 0123456789 <<>>[]()\\% qQmlScmTjBTET";
        for _ in 0..64 {
            for slot in buf.iter_mut() {
                state = state.wrapping_mul(1_103_515_245).wrapping_add(12_345);
                *slot = alphabet[(state >> 16) as usize % alphabet.len()];
            }
            let _ = parse_operations_lenient(&buf);
        }
    }

    #[test]
    fn nesting_is_bounded() {
        let deep = format!("{}1{} m", "[".repeat(500), "]".repeat(500));
        let ops = parse_operations_lenient(deep.as_bytes());
        // The point is that it returns at all rather than overflowing the stack.
        assert!(operators(&ops).contains(&"m".to_string()));
    }

    #[test]
    fn inline_length_candidates_reject_a_filter_without_length() {
        let mut d = Dictionary::new();
        d.set("W", Object::Integer(4));
        d.set("H", Object::Integer(4));
        d.set("BPC", Object::Integer(8));
        d.set("CS", Object::Name(b"G".to_vec()));
        assert_eq!(inline_len_candidates(&d, 1024), vec![16]);
        d.set("F", Object::Name(b"AHx".to_vec()));
        assert!(
            inline_len_candidates(&d, 1024).is_empty(),
            "a filtered image's encoded length is not derivable from its geometry"
        );
        d.set("L", Object::Integer(7));
        assert_eq!(inline_len_candidates(&d, 1024), vec![7], "/L wins");
    }

    #[test]
    fn inline_row_length_rounds_up_to_whole_bytes() {
        // 7 columns at 1bpc is 1 byte per row, not 7/8 of one.
        let mut d = Dictionary::new();
        d.set("W", Object::Integer(7));
        d.set("H", Object::Integer(3));
        d.set("IM", Object::Boolean(true));
        assert_eq!(inline_len_candidates(&d, 1024), vec![3]);
    }

    #[test]
    fn a_length_longer_than_the_stream_is_not_allocated() {
        // A hostile /L must never drive an allocation.
        let mut d = Dictionary::new();
        d.set("W", Object::Integer(1));
        d.set("H", Object::Integer(1));
        d.set("L", Object::Integer(i64::MAX));
        assert!(inline_len_candidates(&d, 64).iter().all(|&n| n <= 64));
    }
}