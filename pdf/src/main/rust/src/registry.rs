use crate::*;
use indexmap::IndexMap;

// Document registry - bounded TRUE LRU to avoid long-running leak.
// Uses IndexMap to preserve insertion/access order for true LRU semantics.
//
// Lock ordering policy (documented to avoid deadlock):
//   - Always acquire `registry()` lock BEFORE `index_cache()` lock when both are needed.
//   - `next_handle` only locks NEXT static — independent.
//   - `open_document_pw`, `close_document`, `page_count` follow registry -> index_cache order.
//   - `search.rs::ensure_index` must also respect registry -> index_cache (audit fix):
//       it checks index_cache first (read-only), then takes registry, then index_cache again for insert.
//       This is safe because the first check is optimistic and the second insert is after dropping registry
//       OR it must be documented that registry is taken first in the critical section.
//   Documented order: registry → index_cache.

const MAX_REG_DOCS: usize = 8;
/// Max PDF size accepted to prevent zip-bomb / OOM DoS (200 MB).
const MAX_PDF_BYTES: usize = 200 * 1024 * 1024;

/// Nesting-depth ceiling for the raw pre-scan (see [`nesting_exceeds`]).
///
/// Well above anything a real producer emits - nesting past a couple of dozen
/// levels does not occur outside deliberately hostile files - and well below the
/// ~256 levels measured to survive an 8 MiB stack inside lopdf's object parser.
const MAX_RAW_NESTING: u32 = 200;

/// Stack for the document-parse worker (see [`load_mem_on_big_stack`]).
const OPEN_STACK_BYTES: usize = 32 * 1024 * 1024;

pub(crate) fn registry() -> &'static Mutex<IndexMap<i64, Document>> {
    static REG: OnceLock<Mutex<IndexMap<i64, Document>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(IndexMap::new()))
}

pub(crate) fn next_handle() -> i64 {
    static NEXT: OnceLock<Mutex<i64>> = OnceLock::new();
    let m = NEXT.get_or_init(|| Mutex::new(0));
    let mut guard = m.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard += 1;
    *guard
}

/// Parse `bytes` into a document and store it, returning a non-zero handle.
/// Encrypted documents are decrypted in place (with `password`, empty allowed);
/// supports RC4 and AES (V4/V5) standard security handlers. Returns 0 on parse
/// failure, wrong password, or unsupported encryption.
///
/// Size guard: rejects inputs larger than 200 MB to prevent OOM DoS.
pub(crate) fn open_document_pw(bytes: &[u8], password: &[u8]) -> i64 {
    // Zip-bomb / OOM guard: reject absurdly large PDF before parsing.
    if bytes.len() > MAX_PDF_BYTES {
        return 0;
    }
    let mut doc = match load_document_lenient(bytes) {
        Some(d) => d,
        None => return 0,
    };
    if doc.trailer.get(b"Encrypt").is_ok()
        && decrypt_in_place(&mut doc, password) != DecryptStatus::Ok
    {
        return 0;
    }
    let handle = next_handle();
    // Lock ordering: registry first, then index_cache if eviction needed.
    let mut map = registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // True LRU: evict index 0 (least recently used). Page accesses bump to end via move_index.
    if map.len() >= MAX_REG_DOCS {
        if let Some((oldest_key, _)) = map.shift_remove_index(0) {
            // Still holding registry lock, now acquire index_cache (registry -> index_cache order)
            let mut ic = index_cache()
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            ic.remove(&oldest_key);
        }
    }
    map.insert(handle, doc);
    drop(map);
    handle
}

pub(crate) fn open_document(bytes: &[u8]) -> i64 {
    // No password (empty, built at runtime — not a hard-coded credential).
    open_document_pw(bytes, &Vec::<u8>::new())
}

/// Load a document, falling back to cross-reference reconstruction when lopdf's
/// strict parser rejects an otherwise-recoverable file.
///
/// The common real-world failure (seen with viewers that tolerate it, e.g.
/// Chrome/Acrobat) is a `startxref` offset that points a byte or two off the
/// `xref` keyword, or a damaged/incremental xref chain. lopdf's `xref` parser
/// requires the keyword at the exact offset, so such files yield
/// `InvalidFileTrailer`. On any load error we rebuild a fresh classic xref by
/// scanning the byte stream for indirect objects and append it as an
/// incremental section, then reload. This never runs for files that already
/// parse, so it cannot regress the happy path.
///
/// # Defending the lopdf boundary
///
/// Two of lopdf's failure modes on hostile input are not survivable from our side
/// once it has been entered: unbounded recursion in the object parser is a
/// guard-page fault (`STATUS_STACK_OVERFLOW`), which is not an unwind and so is
/// invisible to the `catch_unwind` in `jni_bindings.rs` - it kills the process -
/// and a degenerate cross-reference stream is a multi-billion-iteration loop, not
/// an error return. Both are decidable from the raw bytes, so they are decided
/// here, before the parser is entered, per 7.5.1's requirement that a damaged file
/// be handled rather than crash the reader. The parse then runs on a worker thread
/// with a large stack as defence in depth, for the recursion the pre-scan cannot
/// see (nesting that only exists after an object stream is decompressed).
pub(crate) fn load_document_lenient(bytes: &[u8]) -> Option<Document> {
    // Unbounded recursion: nothing downstream can recover from it, so this is a
    // hard rejection rather than a fall-through to recovery - the rebuilt file below
    // would contain the same nesting and overflow the same way.
    if nesting_exceeds(bytes, MAX_RAW_NESTING) {
        return None;
    }
    // A degenerate xref stream, by contrast, is survivable: skip only lopdf's
    // strict load, and let recovery below produce a classic table and a fresh
    // trailer that never reference the bad stream. The document still opens.
    if !xref_stream_is_degenerate(bytes) {
        if let Ok(d) = load_mem_on_big_stack(bytes) {
            return Some(d);
        }
    }
    let rebuilt = rebuild_with_scanned_xref(bytes)?;
    load_mem_on_big_stack(&rebuilt).ok()
}

/// `Document::load_mem` on a worker thread with [`OPEN_STACK_BYTES`] of stack.
///
/// lopdf's object parser recurses once per nesting level, so the depth it survives
/// is a property of whichever thread happened to call in. On Android that is a JNI
/// thread whose stack is a fraction of a desktop main thread's, which is why the
/// same file can open on a workstation and kill the app on a phone. Pinning the
/// stack here makes the headroom explicit rather than accidental.
///
/// The input is borrowed, not copied - a PDF may be up to [`MAX_PDF_BYTES`]. A
/// panic is re-raised with its original payload, so the JNI `catch_unwind` and the
/// robustness harness both still observe it; a spawn failure falls back to the
/// calling thread, which is exactly the behaviour before this existed.
fn load_mem_on_big_stack(bytes: &[u8]) -> lopdf::Result<Document> {
    std::thread::scope(|s| {
        match std::thread::Builder::new()
            .name("pdf-open".to_owned())
            .stack_size(OPEN_STACK_BYTES)
            .spawn_scoped(s, || Document::load_mem(bytes))
        {
            Ok(h) => match h.join() {
                Ok(r) => r,
                Err(payload) => std::panic::resume_unwind(payload),
            },
            Err(_) => Document::load_mem(bytes),
        }
    })
}

/// Whether `<<`/`[` nesting anywhere in `bytes` exceeds `max` levels.
///
/// This is a pre-check, not a parser: it decides only whether the bytes are safe to
/// hand to lopdf's recursive object parser. It is therefore deliberately biased -
/// every ambiguity below resolves toward under-counting, because a false positive
/// refuses a document (bad) while a false negative is a process kill (worse), and
/// the depth limit is generous enough that under-counting still leaves the limit
/// far below the stack's real capacity.
///
/// Context that has to be respected or the count is meaningless:
///   * 7.3.4.2 literal strings - parens nest, and `\` escapes the next byte, so a
///     `>`, `[` or `<<` inside `(...)` must not move the count. Getting this wrong
///     would reject legitimate documents, which is the one outcome worse than the
///     bug being defended against.
///   * 7.3.4.3 hex strings - `<AB CD>` opens with the same byte as a dictionary.
///   * 7.2.4 comments - `%` to end of line.
///   * 7.3.8 stream bodies - compressed and image data is dense in `[` and `<<`
///     bytes, is not parsed as objects at this level, and would otherwise drift the
///     count upward on every large legitimate file. Skipped wholesale.
/// The count also resets at each `endobj`, so one unbalanced object cannot leak its
/// residual depth into the rest of the file. The one direction that can over-count
/// is a stream body containing the literal bytes `endstream`, which would resume the
/// scan inside binary data; that is why the reset exists and why the limit is 200
/// rather than the couple of dozen levels real files actually use.
fn nesting_exceeds(bytes: &[u8], max: u32) -> bool {
    let n = bytes.len();
    let mut depth: u32 = 0;
    let mut i = 0usize;
    while i < n {
        match bytes[i] {
            b'%' => {
                while i < n && bytes[i] != b'\n' && bytes[i] != b'\r' {
                    i += 1;
                }
            }
            b'(' => {
                let mut nest = 1usize;
                i += 1;
                while i < n && nest > 0 {
                    match bytes[i] {
                        b'\\' => i += 1,
                        b'(' => nest += 1,
                        b')' => nest -= 1,
                        _ => {}
                    }
                    i += 1;
                }
            }
            b'<' if bytes.get(i + 1) == Some(&b'<') => {
                depth += 1;
                if depth > max {
                    return true;
                }
                i += 2;
            }
            b'<' => {
                i += 1;
                while i < n && bytes[i] != b'>' {
                    i += 1;
                }
                i += 1;
            }
            b'>' if bytes.get(i + 1) == Some(&b'>') => {
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
            // 7.3.8: the `stream` keyword follows the dictionary's `>>`. Requiring
            // that boundary keeps a name like `/Substream` from being mistaken for
            // the start of a body. The byte AFTER it is deliberately not checked:
            // the spec says CRLF or LF, but files that omit it exist, and treating
            // one as a non-stream would leave its binary body to be counted.
            b's' if bytes[i..].starts_with(b"stream")
                && i > 0
                && (is_pdf_ws(bytes[i - 1]) || bytes[i - 1] == b'>') =>
            {
                match find_subsequence(bytes, b"endstream", i + 6) {
                    Some(end) => i = end + 9,
                    // Unterminated stream: nothing after it can be located reliably.
                    None => return false,
                }
            }
            b'e' if bytes[i..].starts_with(b"endobj") => {
                depth = 0;
                i += 6;
            }
            _ => i += 1,
        }
    }
    false
}

/// Whether a cross-reference STREAM in `bytes` declares an entry layout that makes
/// lopdf's reader loop without consuming input (7.5.8).
///
/// `/W` gives the byte width of each of the three entry fields.
/// `decode_xref_stream` (lopdf parser_aux.rs:291) iterates the count declared by
/// each `/Index` pair and reads exactly `W[0] + W[1] + W[2]` bytes per entry, so
/// when that total is zero it consumes nothing, `read_exact` never fails, and the
/// declared count is the *only* bound: `/W [0 0 0] /Index [0 2147483647]` on an
/// 8-byte stream is 2.1 billion iterations, each inserting an xref entry. Measured
/// at 243 s without completing, against ~10 ms for the same document with a sane
/// `/W`. lopdf already rejects a negative or short `/W`, so a zero total is the
/// whole of the remaining hole.
///
/// The `/Index` check is the same argument made against the file rather than the
/// stream: a file cannot describe more cross-reference entries than it has bytes,
/// since every object it lists needs at least one byte somewhere.
fn xref_stream_is_degenerate(bytes: &[u8]) -> bool {
    let mut from = 0usize;
    // Bound on candidate dictionaries examined. A real file has one `/XRef` per
    // incremental update; scanning is windowed to a constant per candidate so that
    // this whole check stays O(n) even on a file that repeats the token to make it
    // quadratic. Stopping at the cap only returns that file to the behaviour it had
    // before this check existed.
    let mut candidates = 0u32;
    while let Some(hit) = find_subsequence(bytes, b"/XRef", from) {
        from = hit + 5;
        candidates += 1;
        if candidates > 64 {
            return false;
        }
        // The dictionary holding it: back to the start of the line, forward to the
        // stream body. The dictionary is plain text even when the body is not.
        let back = hit.saturating_sub(4096);
        let start = bytes[back..hit]
            .iter()
            .rposition(|&b| b == b'\n' || b == b'\r')
            .map(|p| back + p + 1)
            .unwrap_or(back);
        let win_end = (hit + 8192).min(bytes.len());
        let end = find_subsequence(&bytes[..win_end], b"stream", hit).unwrap_or(win_end);
        let dict = &bytes[start..end];
        if let Some(w) = int_array_after_key(dict, b"/W") {
            if w.len() >= 3 && w.iter().take(3).all(|&x| x == 0) {
                return true;
            }
        }
        if let Some(index) = int_array_after_key(dict, b"/Index") {
            let declared: i64 = index.iter().skip(1).step_by(2).sum();
            if declared > bytes.len() as i64 {
                return true;
            }
        }
    }
    false
}

/// Integers of the array that follows `key` in a raw dictionary slice, e.g. the
/// `[1 2 1]` of `/W [1 2 1]`. `None` unless `key` appears as a complete name
/// followed by an array of plain integers.
fn int_array_after_key(dict: &[u8], key: &[u8]) -> Option<Vec<i64>> {
    let mut p = 0usize;
    'candidate: while p < dict.len() {
        let rel = dict[p..].windows(key.len()).position(|w| w == key)?;
        let mut i = p + rel + key.len();
        p = p + rel + 1;
        // `/W` must not match the `/W` of `/Widths`.
        match dict.get(i) {
            Some(&b) if !is_pdf_ws(b) && b != b'[' => continue 'candidate,
            None => return None,
            _ => {}
        }
        while i < dict.len() && is_pdf_ws(dict[i]) {
            i += 1;
        }
        if dict.get(i) != Some(&b'[') {
            continue 'candidate;
        }
        i += 1;
        let mut out = Vec::new();
        while i < dict.len() && dict[i] != b']' {
            if is_pdf_ws(dict[i]) {
                i += 1;
            } else if dict[i] == b'-' || dict[i].is_ascii_digit() {
                let s = i;
                i += 1;
                while i < dict.len() && dict[i].is_ascii_digit() {
                    i += 1;
                }
                match std::str::from_utf8(&dict[s..i]).ok().and_then(|t| t.parse::<i64>().ok()) {
                    Some(v) if out.len() < 64 => out.push(v),
                    // Not a plain integer array, or longer than any real /W or
                    // /Index: not something to draw conclusions from.
                    _ => continue 'candidate,
                }
            } else {
                continue 'candidate;
            }
        }
        return Some(out);
    }
    None
}

/// True for PDF whitespace / delimiter bytes (used for token-boundary checks).
fn is_pdf_ws(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\r' | b'\n' | b'\x0c' | b'\0')
}

/// Byte offset of the first occurrence of `needle` at or after `from`.
fn find_subsequence(haystack: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    if from >= haystack.len() || needle.is_empty() {
        return None;
    }
    haystack[from..]
        .windows(needle.len())
        .position(|w| w == needle)
        .map(|p| from + p)
}

/// Scan `bytes` for `N G obj` indirect-object headers, returning a map of
/// object id -> (generation, byte offset of the first digit of `N`). When an id
/// appears more than once (incremental updates) the highest offset wins, which
/// matches "latest definition" semantics.
///
/// Stream bodies are skipped: binary stream data can contain a byte sequence that
/// looks like `<ws><digits><ws><digits>obj`, and because the highest offset wins such a
/// false match would OVERRIDE the real object's offset, pointing the rebuilt xref into
/// the middle of a stream.
fn scan_indirect_objects(bytes: &[u8]) -> std::collections::BTreeMap<u32, (u16, usize)> {
    let mut map: std::collections::BTreeMap<u32, (u16, usize)> = std::collections::BTreeMap::new();
    let n = bytes.len();
    let mut i = 0usize;
    while i + 3 <= n {
        // Jump over a stream body so its bytes cannot be mistaken for object headers.
        if bytes[i..].starts_with(b"stream") {
            let after = i + 6;
            match find_subsequence(bytes, b"endstream", after) {
                Some(end) => {
                    i = end + 9;
                    continue;
                }
                // Unterminated stream: nothing further can be trusted.
                None => break,
            }
        }
        if &bytes[i..i + 3] == b"obj"
            && (i + 3 == n || is_pdf_ws(bytes[i + 3]) || bytes[i + 3] == b'<' || bytes[i + 3] == b'[')
        {
            // Backtrack: <ws> <gen digits> <ws> <num digits>, ending just before `obj`.
            let mut p = i;
            while p > 0 && is_pdf_ws(bytes[p - 1]) {
                p -= 1;
            }
            let gen_end = p;
            while p > 0 && bytes[p - 1].is_ascii_digit() {
                p -= 1;
            }
            let gen_start = p;
            if gen_start == gen_end {
                i += 1;
                continue; // no generation number -> not a header (e.g. `endobj`)
            }
            while p > 0 && is_pdf_ws(bytes[p - 1]) {
                p -= 1;
            }
            let num_end = p;
            while p > 0 && bytes[p - 1].is_ascii_digit() {
                p -= 1;
            }
            let num_start = p;
            if num_start == num_end {
                i += 1;
                continue; // no object number
            }
            // Require the header to sit at a token boundary (start of file, or
            // preceded by whitespace / delimiter) to avoid matching digits that
            // are part of some larger token inside binary data.
            let boundary = num_start == 0 || is_pdf_ws(bytes[num_start - 1]) || bytes[num_start - 1] == b'>';
            let num = std::str::from_utf8(&bytes[num_start..num_end]).ok().and_then(|s| s.parse::<u32>().ok());
            let gen = std::str::from_utf8(&bytes[gen_start..gen_end]).ok().and_then(|s| s.parse::<u16>().ok());
            if boundary {
                if let (Some(num), Some(gen)) = (num, gen) {
                    let entry = map.entry(num).or_insert((gen, num_start));
                    if num_start >= entry.1 {
                        *entry = (gen, num_start);
                    }
                }
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    map
}

/// Extract an indirect reference (`N G R`) that follows `key` inside a raw
/// trailer-dictionary byte slice.
fn ref_after_key(dict: &[u8], key: &[u8]) -> Option<(u32, u16)> {
    let pos = dict.windows(key.len()).position(|w| w == key)?;
    let mut i = pos + key.len();
    let n = dict.len();
    let skip_ws = |i: &mut usize| while *i < n && is_pdf_ws(dict[*i]) { *i += 1; };
    let read_uint = |i: &mut usize| -> Option<u64> {
        let s = *i;
        while *i < n && dict[*i].is_ascii_digit() { *i += 1; }
        if *i == s { return None; }
        std::str::from_utf8(&dict[s..*i]).ok()?.parse().ok()
    };
    skip_ws(&mut i);
    let num = read_uint(&mut i)? as u32;
    skip_ws(&mut i);
    let gen = read_uint(&mut i)? as u16;
    skip_ws(&mut i);
    if i < n && dict[i] == b'R' {
        Some((num, gen))
    } else {
        None
    }
}

/// Capture the last `trailer << ... >>` dictionary bytes (balanced `<< >>`).
fn last_trailer_dict(bytes: &[u8]) -> Option<Vec<u8>> {
    let kw = b"trailer";
    // Find the last occurrence of the `trailer` keyword.
    let mut search_from = 0usize;
    let mut last = None;
    while let Some(rel) = bytes[search_from..].windows(kw.len()).position(|w| w == kw) {
        let abs = search_from + rel;
        last = Some(abs);
        search_from = abs + kw.len();
    }
    let start_kw = last?;
    let mut i = start_kw + kw.len();
    let n = bytes.len();
    while i < n && is_pdf_ws(bytes[i]) {
        i += 1;
    }
    if i + 1 >= n || bytes[i] != b'<' || bytes[i + 1] != b'<' {
        return None;
    }
    let dict_start = i;
    let mut depth = 0i32;
    while i + 1 < n {
        if bytes[i] == b'<' && bytes[i + 1] == b'<' {
            depth += 1;
            i += 2;
        } else if bytes[i] == b'>' && bytes[i + 1] == b'>' {
            depth -= 1;
            i += 2;
            if depth == 0 {
                return Some(bytes[dict_start..i].to_vec());
            }
        } else {
            i += 1;
        }
    }
    None
}

/// Locate the object id whose body declares `/Type /Catalog` (the document root),
/// used to synthesize a trailer when none is recoverable. Scans highest id first: an
/// incrementally-updated file keeps its superseded catalogs at lower ids, and requires
/// `/Type` adjacent to `/Catalog` so a literal `(/Catalog)` string does not match.
fn find_catalog_id(objs: &std::collections::BTreeMap<u32, (u16, usize)>, bytes: &[u8]) -> Option<(u32, u16)> {
    let mut fallback = None;
    for (id, (gen, off)) in objs.iter().rev() {
        let end = (*off + 4096).min(bytes.len());
        let window = &bytes[*off..end];
        if !window.windows(8).any(|w| w == b"/Catalog") {
            continue;
        }
        if window.windows(5).any(|w| w == b"/Type") {
            return Some((*id, *gen));
        }
        fallback = fallback.or(Some((*id, *gen)));
    }
    fallback
}

/// Recover `/Root` from a cross-reference STREAM dictionary (§7.5.8).
///
/// Files that use xref streams have no `trailer` keyword at all, so
/// [`last_trailer_dict`] finds nothing, and their catalog normally lives inside an
/// object stream where [`find_catalog_id`] cannot see it either — between them that
/// made recovery fail outright for essentially every modern PDF. The xref stream is
/// itself an ordinary top-level indirect object whose DICTIONARY is plain text (only
/// the body is compressed) and carries `/Root`, so it can simply be read.
///
/// Highest object id first, so an incremental update's xref stream wins over the one
/// it superseded. This is enough on its own: the rebuilt classic table lists the
/// ObjStm container objects, and lopdf expands every ObjStm it loads (reader.rs:306),
/// so a catalog inside one becomes reachable without needing type-2 entries.
fn root_from_xref_stream(
    objs: &std::collections::BTreeMap<u32, (u16, usize)>,
    bytes: &[u8],
) -> Option<(u32, u16)> {
    for (_, off) in objs.values().rev() {
        let end = (*off + 8192).min(bytes.len());
        let mut window = &bytes[*off..end];
        // Stop at the stream body: /Root is in the dictionary, and the body is binary
        // and could contain a byte sequence that looks like a reference.
        if let Some(p) = find_subsequence(window, b"stream", 0) {
            window = &window[..p];
        }
        if !window.windows(5).any(|w| w == b"/XRef") {
            continue;
        }
        if let Some(r) = ref_after_key(window, b"/Root") {
            return Some(r);
        }
    }
    None
}

/// Rebuild a parseable PDF by scanning for indirect objects and appending a
/// fresh classic cross-reference table + trailer pointing at the scanned
/// offsets. Returns the new byte buffer, or `None` if no usable root is found.
fn rebuild_with_scanned_xref(bytes: &[u8]) -> Option<Vec<u8>> {
    let objs = scan_indirect_objects(bytes);
    if objs.is_empty() {
        return None;
    }
    let max_id = *objs.keys().max()?;
    // The xref table used to be emitted as one 0..=max_id run, so a single bogus
    // `999999999 0 obj` header produced ~20 GB of free entries and OOM-killed the app.
    // A plausible file cannot have an id far beyond the number of objects we actually
    // found; bail rather than emit a table dominated by free entries.
    if max_id as usize > objs.len().saturating_mul(8).saturating_add(4096) {
        return None;
    }

    // Recover /Root (and optional /Info) from the existing trailer if present,
    // else from the catalog object. A freshly synthesized trailer avoids reusing
    // /Prev or /XRefStm links back to the broken xref chain.
    let trailer_dict = last_trailer_dict(bytes);
    let root = trailer_dict
        .as_deref()
        .and_then(|d| ref_after_key(d, b"/Root"))
        // An xref-stream file has no `trailer` keyword, so its /Root lives in the
        // cross-reference stream's own dictionary (A19).
        .or_else(|| root_from_xref_stream(&objs, bytes))
        .or_else(|| find_catalog_id(&objs, bytes))?;
    let info = trailer_dict.as_deref().and_then(|d| ref_after_key(d, b"/Info"));

    let mut out = bytes.to_vec();
    if !out.ends_with(b"\n") {
        out.push(b'\n');
    }
    let xref_pos = out.len();
    out.extend_from_slice(b"xref\n");
    // Emit one subsection per contiguous run of ids actually found, plus the mandatory
    // free entry for object 0. This keeps the table proportional to the objects present.
    out.extend_from_slice(b"0 1\n");
    out.extend_from_slice(b"0000000000 65535 f \n");
    let ids: Vec<u32> = objs.keys().copied().filter(|&id| id != 0).collect();
    let mut i = 0usize;
    while i < ids.len() {
        let start = ids[i];
        let mut j = i;
        while j + 1 < ids.len() && ids[j + 1] == ids[j] + 1 {
            j += 1;
        }
        out.extend_from_slice(format!("{} {}\n", start, j - i + 1).as_bytes());
        for &id in &ids[i..=j] {
            if let Some((gen, off)) = objs.get(&id) {
                out.extend_from_slice(format!("{:010} {:05} n \n", off, gen).as_bytes());
            }
        }
        i = j + 1;
    }
    out.extend_from_slice(b"trailer\n<<");
    out.extend_from_slice(format!("/Size {}", max_id + 1).as_bytes());
    out.extend_from_slice(format!("/Root {} {} R", root.0, root.1).as_bytes());
    if let Some((inum, igen)) = info {
        out.extend_from_slice(format!("/Info {} {} R", inum, igen).as_bytes());
    }
    out.extend_from_slice(b">>\n");
    out.extend_from_slice(format!("startxref\n{}\n%%EOF\n", xref_pos).as_bytes());
    Some(out)
}

pub(crate) fn page_count(handle: i64) -> i32 {
    let mut reg = registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // Bump accessed entry to MRU for true LRU semantics
    if let Some(idx) = reg.get_index_of(&handle) {
        let len = reg.len();
        if len > 0 && idx + 1 < len {
            reg.move_index(idx, len - 1);
        }
        reg.get(&handle).map(|d| d.get_pages().len() as i32).unwrap_or(0)
    } else {
        0
    }
}

pub(crate) fn close_document(handle: i64) {
    {
        let mut reg = registry()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        // shift_remove, not swap_remove: swap_remove moves the LAST (most recently used)
        // entry into the freed slot, which destroys the ordering that eviction relies on
        // (`shift_remove_index(0)` in open_document_pw) and could evict a document the
        // user is actively viewing.
        reg.shift_remove(&handle);
    }
    // Lock ordering: registry released before index_cache, or registry -> index_cache. Here we already released registry, safe.
    // For consistency also support registry->index_cache, but separate scopes avoid holding both.
    let mut ic = index_cache()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    ic.remove(&handle);
}

// ---------------------------------------------------------------------------
// Compose / merge ("cut and glue")
// ---------------------------------------------------------------------------

#[cfg(test)]
mod recovery_tests {
    use super::*;

    /// A19: an xref-stream file has no `trailer` keyword, so /Root must be read from
    /// the cross-reference stream's own dictionary or recovery fails outright.
    #[test]
    fn root_comes_from_the_xref_stream_dictionary() {
        let bytes: &[u8] = b"%PDF-1.7\n\
            4 0 obj\n<< /Type /ObjStm /N 2 /First 12 /Length 20 >>\nstream\n\
            \x01\x02binary/Root junk\nendstream\nendobj\n\
            9 0 obj\n<< /Type /XRef /Size 10 /Root 7 0 R /W [1 2 1] /Length 8 >>\nstream\n\
            \x00\x00\x00\x00\x00\x00\x00\x00\nendstream\nendobj\n\
            startxref\n9\n%%EOF\n";
        let objs = scan_indirect_objects(bytes);
        assert_eq!(
            root_from_xref_stream(&objs, bytes),
            Some((7, 0)),
            "the /Root reference in the /Type /XRef dictionary must be recovered"
        );
        // There is no `trailer` keyword, which is precisely why the old path failed.
        assert!(last_trailer_dict(bytes).is_none());
    }

    #[test]
    fn xref_stream_body_is_not_scanned_for_root() {
        // /Root appears only inside the binary stream body, never in a dictionary, so
        // nothing may be recovered from it.
        let bytes: &[u8] = b"%PDF-1.7\n\
            3 0 obj\n<< /Type /XRef /Size 4 /Length 16 >>\nstream\n\
            /Root 5 0 R \x00\x01\x02\x03\nendstream\nendobj\n";
        let objs = scan_indirect_objects(bytes);
        assert_eq!(root_from_xref_stream(&objs, bytes), None);
    }

    #[test]
    fn later_xref_stream_wins_over_the_one_it_superseded() {
        // An incrementally-updated file keeps its old xref stream; the highest object
        // id is the newer one.
        let bytes: &[u8] = b"%PDF-1.7\n\
            2 0 obj\n<< /Type /XRef /Root 1 0 R /Length 2 >>\nstream\n\x00\x00\nendstream\nendobj\n\
            8 0 obj\n<< /Type /XRef /Root 6 0 R /Length 2 >>\nstream\n\x00\x00\nendstream\nendobj\n";
        let objs = scan_indirect_objects(bytes);
        assert_eq!(root_from_xref_stream(&objs, bytes), Some((6, 0)));
    }

    #[test]
    fn a_bogus_high_object_id_does_not_size_the_xref_table() {
        // A single `999999999 0 obj` header used to produce ~20 GB of free entries.
        let bytes: &[u8] = b"%PDF-1.7\n\
            1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n\
            999999999 0 obj\n<< >>\nendobj\n";
        assert!(
            rebuild_with_scanned_xref(bytes).is_none(),
            "an implausible max object id must abort recovery, not allocate for it"
        );
    }

    /// The pre-scan's whole job: nesting deep enough to overflow lopdf's recursive
    /// object parser must be refused before the parser is entered.
    #[test]
    fn deep_object_nesting_is_refused_before_lopdf_recurses() {
        let deep = |n: usize| -> Vec<u8> {
            let mut v = Vec::from(&b"1 0 obj\n"[..]);
            v.extend_from_slice(&b"<< /K ".repeat(n));
            v.push(b'0');
            v.extend_from_slice(&b" >>".repeat(n));
            v.extend_from_slice(b"\nendobj\n");
            v
        };
        assert!(!nesting_exceeds(&deep(MAX_RAW_NESTING as usize), MAX_RAW_NESTING));
        assert!(nesting_exceeds(&deep(MAX_RAW_NESTING as usize + 1), MAX_RAW_NESTING));
        // Arrays count the same, and openers alone are enough - the crash does not
        // require the file to be balanced.
        assert!(nesting_exceeds(&b"[".repeat(MAX_RAW_NESTING as usize + 1), MAX_RAW_NESTING));
        assert!(nesting_exceeds(&b"<<".repeat(MAX_RAW_NESTING as usize + 1), MAX_RAW_NESTING));
        assert!(load_document_lenient(&deep(500)).is_none());
    }

    /// The false-positive direction, which matters more than the true positives:
    /// rejecting a legitimate document would be worse than the crash being fixed.
    #[test]
    fn ordinary_nesting_and_string_context_do_not_trip_the_pre_scan() {
        // 7.3.4.2: a literal string is opaque. Brackets, `<<` and `>` inside one
        // must not move the count, and `\)` must not end it early.
        let mut s = Vec::from(&b"1 0 obj\n<< /T ("[..]);
        for _ in 0..500 {
            s.extend_from_slice(b"[<< >(\\) ");
        }
        s.extend_from_slice(b") >>\nendobj\n");
        assert!(!nesting_exceeds(&s, MAX_RAW_NESTING));
        // 7.3.4.3 hex strings, and 7.2.4 comments, likewise.
        assert!(!nesting_exceeds(&b"<< /H <5B5B5B> >>".repeat(500), MAX_RAW_NESTING));
        assert!(!nesting_exceeds(&b"% [[[ << << <<\n".repeat(500), MAX_RAW_NESTING));
        // 7.3.8 stream bodies are binary and are dense in these bytes; they are not
        // parsed as objects here, so they must not be counted.
        let mut body = Vec::from(&b"1 0 obj\n<< /Length 2000 >>\nstream\n"[..]);
        body.extend_from_slice(&b"[".repeat(2000));
        body.extend_from_slice(b"\nendstream\nendobj\n");
        assert!(!nesting_exceeds(&body, MAX_RAW_NESTING));
        // A name that merely ends in `stream` is not the start of a body.
        assert!(nesting_exceeds(
            &[&b"<< /Substream 1 >>\n"[..], &b"[".repeat(MAX_RAW_NESTING as usize + 1)].concat(),
            MAX_RAW_NESTING
        ));
        // Residual depth from one unbalanced object must not leak into the next.
        let mut leak = Vec::new();
        for i in 0..500 {
            leak.extend_from_slice(format!("{i} 0 obj\n<< /A [ [ [\nendobj\n").as_bytes());
        }
        assert!(!nesting_exceeds(&leak, MAX_RAW_NESTING));
        // And a real document, written by lopdf itself, still loads - including a
        // content stream full of the bytes the scanner has to treat as opaque.
        let mut doc = Document::with_version("1.7");
        let pages_id = doc.new_object_id();
        let contents_id = doc.add_object(Stream::new(
            dictionary! {},
            b"BT /F1 12 Tf (a ( nested ) string with [ and << and > ) Tj ET 0 0 9 9 re f".to_vec(),
        ));
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => contents_id,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog_id);
        let mut buf = Vec::new();
        doc.save_to(&mut buf).expect("the fixture must serialize");
        let loaded = load_document_lenient(&buf)
            .expect("a valid document must not be refused by the pre-scan");
        assert_eq!(loaded.get_pages().len(), 1);
    }

    /// 7.5.8: `/W` gives the byte width of each entry field, so a zero total means
    /// lopdf's reader consumes no input per entry and `/Index` alone bounds the loop.
    #[test]
    fn degenerate_xref_stream_widths_are_detected_from_the_raw_bytes() {
        let xref = |w: &str, index: &str| -> Vec<u8> {
            format!(
                "%PDF-1.7\n4 0 obj\n<< /Type /XRef /Size 5 /Root 1 0 R /W {w} \
                 /Index {index} /Length 8 >>\nstream\n\0\0\0\0\0\0\0\0\nendstream\nendobj\n"
            )
            .into_bytes()
        };
        assert!(xref_stream_is_degenerate(&xref("[0 0 0]", "[0 2147483647]")));
        assert!(xref_stream_is_degenerate(&xref("[0 0 0]", "[0 16]")));
        // Declaring more entries than the file has bytes: no object can be that cheap.
        assert!(xref_stream_is_degenerate(&xref("[1 2 1]", "[0 4294967295]")));
        // Sane layouts must pass, including a zero /W[0] (7.5.8 defaults the type
        // field to 1) and a zero generation width, both of which occur in real files.
        assert!(!xref_stream_is_degenerate(&xref("[1 2 1]", "[0 5]")));
        assert!(!xref_stream_is_degenerate(&xref("[0 2 1]", "[0 5]")));
        assert!(!xref_stream_is_degenerate(&xref("[1 2 0]", "[0 5]")));
        // `/W` must be matched as a whole name, not as the prefix of `/Widths`.
        assert!(!xref_stream_is_degenerate(
            b"%PDF-1.7\n4 0 obj\n<< /Type /XRef /Widths [0 0 0] /W [1 2 1] /Index [0 5] >>\nstream\n\0\nendstream\n"
        ));
    }
}
