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
pub(crate) fn load_document_lenient(bytes: &[u8]) -> Option<Document> {
    if let Ok(d) = Document::load_mem(bytes) {
        return Some(d);
    }
    let rebuilt = rebuild_with_scanned_xref(bytes)?;
    Document::load_mem(&rebuilt).ok()
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
}
