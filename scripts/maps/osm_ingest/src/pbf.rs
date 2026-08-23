//! `.osm.pbf` container: blob framing, inflate, `PrimitiveBlock` decode, and the
//! parallel pass driver.
//!
//! A `.osm.pbf` is a flat sequence of
//! `[u32 big-endian header_len][BlobHeader][Blob]`. Because the framing is
//! self-describing we can cheaply *scan* the file once, recording each blob's
//! `(offset, len)`, and afterwards let N threads each seek to and inflate their
//! own blobs from their own `File` handle. That replaces the old C++
//! `ThreadPool`/`MoveTask`/throttling-condvar machinery — and the data race its
//! comments described — with a plain `std::thread::scope`.
//!
//! Work is split into fixed contiguous *chunks* of blobs (not dynamically per
//! blob) so results can be merged in chunk order. That is what makes the whole
//! tool deterministic: name-pool offsets and record order no longer depend on
//! thread scheduling, which the C++ generator's did.

use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::sync::atomic::{AtomicU8, AtomicUsize, Ordering};
use std::sync::Mutex;

use crate::proto::{self, Error, Reader, Result, WIRE_BYTES};

/// Kind bits: which entity types a pass wants, and which a blob contains.
pub const KIND_NODES: u8 = 1;
pub const KIND_WAYS: u8 = 2;
pub const KIND_RELATIONS: u8 = 4;

/// A blob is capped at 32 MiB uncompressed by the format spec; allow some slack
/// but refuse absurd sizes so a corrupt length can't trigger a huge allocation.
const MAX_BLOB_BYTES: usize = 128 * 1024 * 1024;

/// Blobs per work chunk. Fixed (rather than derived from the core count) so the
/// merge order — and therefore every output byte — is identical on any machine.
const CHUNK_BLOBS: usize = 64;

/// Where one `OSMData` blob lives in the file.
#[derive(Clone, Copy)]
pub struct BlobLoc {
    pub offset: u64,
    pub datasize: u32,
}

/// Scan the framing once, returning every `OSMData` blob's location. Only blob
/// *headers* are read, so this is I/O-cheap even on a 1.3 GB extract.
pub fn scan_blobs(path: &Path) -> Result<Vec<BlobLoc>> {
    let file = File::open(path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
    let total = file
        .metadata()
        .map_err(|e| Error(format!("cannot stat {}: {e}", path.display())))?
        .len();
    let mut r = BufReader::with_capacity(1 << 20, file);
    let mut out = Vec::new();
    let mut pos: u64 = 0;
    let mut header = Vec::new();

    while pos < total {
        let mut len_buf = [0u8; 4];
        if let Err(e) = r.read_exact(&mut len_buf) {
            return Err(Error(format!("truncated blob header length at {pos}: {e}")));
        }
        let header_len = u32::from_be_bytes(len_buf) as usize;
        if header_len == 0 || header_len > 64 * 1024 {
            return proto::err(format!("implausible BlobHeader length {header_len} at {pos}"));
        }
        header.clear();
        header.resize(header_len, 0);
        r.read_exact(&mut header)
            .map_err(|e| Error(format!("truncated BlobHeader at {pos}: {e}")))?;

        let (kind, datasize) = decode_blob_header(&header)?;
        let blob_offset = pos + 4 + header_len as u64;
        if datasize as usize > MAX_BLOB_BYTES {
            return proto::err(format!("blob at {blob_offset} claims {datasize} bytes"));
        }
        if kind == b"OSMData" {
            out.push(BlobLoc {
                offset: blob_offset,
                datasize,
            });
        }
        pos = blob_offset + datasize as u64;
        // seek_relative keeps the read buffer when the target is already inside it.
        // Blobs average tens of KB, so one 1 MiB fill covers many headers; a plain
        // `seek` would discard the buffer and refill it for every single blob.
        r.seek_relative(datasize as i64)
            .map_err(|e| Error(format!("seek to {pos} failed: {e}")))?;
    }
    Ok(out)
}

/// `BlobHeader { required string type = 1; required int32 datasize = 3; }`
fn decode_blob_header(buf: &[u8]) -> Result<(&[u8], u32)> {
    let mut r = Reader::new(buf);
    let mut kind: &[u8] = b"";
    let mut datasize: Option<u32> = None;
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_BYTES) => kind = r.bytes()?,
            (3, proto::WIRE_VARINT) => datasize = Some(r.uvarint()? as u32),
            _ => r.skip(wire)?,
        }
    }
    match datasize {
        Some(d) => Ok((kind, d)),
        None => proto::err("BlobHeader without datasize"),
    }
}

/// Inflate one blob's payload into `out`.
///
/// `Blob { bytes raw = 1; int32 raw_size = 2; bytes zlib_data = 3; ... }`.
/// The compressions we cannot decode without a C library (lzma/bzip2/lz4/zstd)
/// are reported as a clear error rather than silently skipped — a skipped blob
/// would produce a quietly incomplete graph.
pub fn inflate_blob(blob: &[u8], out: &mut Vec<u8>) -> Result<()> {
    let mut r = Reader::new(blob);
    let mut raw: Option<&[u8]> = None;
    let mut raw_size: Option<usize> = None;
    let mut zlib: Option<&[u8]> = None;
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_BYTES) => raw = Some(r.bytes()?),
            (2, proto::WIRE_VARINT) => raw_size = Some(r.uvarint()? as usize),
            (3, WIRE_BYTES) => zlib = Some(r.bytes()?),
            (4, WIRE_BYTES) => return proto::err("blob uses lzma compression (unsupported)"),
            (5, WIRE_BYTES) => return proto::err("blob uses bzip2 compression (unsupported)"),
            (6, WIRE_BYTES) => return proto::err("blob uses lz4 compression (unsupported)"),
            (7, WIRE_BYTES) => return proto::err("blob uses zstd compression (unsupported)"),
            _ => r.skip(wire)?,
        }
    }

    out.clear();
    if let Some(z) = zlib {
        let limit = raw_size.unwrap_or(MAX_BLOB_BYTES).min(MAX_BLOB_BYTES);
        // `decompress_to_vec_zlib_with_limit` parses the zlib header AND verifies
        // the Adler-32 trailer, so a mis-framed or corrupt blob fails loudly.
        let data = miniz_oxide::inflate::decompress_to_vec_zlib_with_limit(z, limit)
            .map_err(|e| Error(format!("inflate failed: {e:?}")))?;
        *out = data;
    } else if let Some(bytes) = raw {
        out.extend_from_slice(bytes);
    } else {
        return proto::err("blob has neither raw nor zlib_data");
    }

    if let Some(want) = raw_size {
        if out.len() != want {
            return proto::err(format!(
                "blob inflated to {} bytes but raw_size says {want}",
                out.len()
            ));
        }
    }
    Ok(())
}

/// Inflate and decode the first data blob, so a file we cannot read fails in
/// seconds instead of at the end of a multi-hour pass.
///
/// Worth its own step because the compression is a property of the whole file:
/// a planet mirror published with zstd blobs is unreadable here, and finding
/// that out after an 80 GB download and one full pass is the expensive way to
/// learn it.
pub fn probe_compression(path: &Path, blobs: &[BlobLoc]) -> Result<()> {
    let Some(first) = blobs.first() else {
        return Ok(());
    };
    let mut file =
        File::open(path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
    let mut compressed = Vec::new();
    let mut inflated = Vec::new();
    read_exact_at(&mut file, first, &mut compressed)?;
    inflate_blob(&compressed, &mut inflated)?;
    PrimitiveBlock::decode(&inflated)?;
    Ok(())
}

/// One decoded `PrimitiveBlock`. Strings and group bodies are borrowed from the
/// inflated buffer, so decoding a block allocates only the two index vectors.
pub struct PrimitiveBlock<'a> {
    pub strings: Vec<&'a [u8]>,
    pub granularity: i64,
    pub lat_offset: i64,
    pub lon_offset: i64,
    pub groups: Vec<&'a [u8]>,
}

impl<'a> PrimitiveBlock<'a> {
    pub fn decode(buf: &'a [u8]) -> Result<PrimitiveBlock<'a>> {
        let mut block = PrimitiveBlock {
            strings: Vec::new(),
            granularity: 100,
            lat_offset: 0,
            lon_offset: 0,
            groups: Vec::new(),
        };
        let mut r = Reader::new(buf);
        while let Some((field, wire)) = r.next_field()? {
            match (field, wire) {
                (1, WIRE_BYTES) => {
                    // StringTable { repeated bytes s = 1; }
                    let mut st = Reader::new(r.bytes()?);
                    while let Some((f, w)) = st.next_field()? {
                        if (f, w) == (1, WIRE_BYTES) {
                            block.strings.push(st.bytes()?);
                        } else {
                            st.skip(w)?;
                        }
                    }
                }
                (2, WIRE_BYTES) => block.groups.push(r.bytes()?),
                (17, proto::WIRE_VARINT) => block.granularity = r.ivarint()?,
                (19, proto::WIRE_VARINT) => block.lat_offset = r.ivarint()?,
                (20, proto::WIRE_VARINT) => block.lon_offset = r.ivarint()?,
                _ => r.skip(wire)?,
            }
        }
        if block.granularity <= 0 {
            return proto::err(format!("block granularity {}", block.granularity));
        }
        Ok(block)
    }

    #[inline]
    pub fn string(&self, idx: u32) -> &'a [u8] {
        self.strings.get(idx as usize).copied().unwrap_or(b"")
    }

    /// Raw coordinate delta -> hundredths of a nanodegree -> 1e-7 degrees, the
    /// integer libosmium's `Location` stores. Truncating division matches
    /// libosmium's `convert_pbf_lat`, so coordinates are bit-identical to what
    /// the old C++ tools saw.
    #[inline]
    pub fn lat_e7(&self, delta: i64) -> i32 {
        ((self.lat_offset + self.granularity * delta) / 100) as i32
    }

    #[inline]
    pub fn lon_e7(&self, delta: i64) -> i32 {
        ((self.lon_offset + self.granularity * delta) / 100) as i32
    }
}

/// Run one pass over the file in parallel, folding each chunk's accumulator as
/// soon as it is complete.
///
/// `make` builds a fresh accumulator per chunk, `body` is called once per blob
/// with that chunk's accumulator and must return the kind bits the blob held, and
/// `sink` receives finished accumulators **in chunk order** so merging stays
/// deterministic.
///
/// Prefer this to [`run_pass`] for anything whose accumulators are large. A
/// planet-scale pass cannot afford `run_pass`'s contract of handing back every
/// chunk at once: on California the pass-2 node accumulators are about a
/// gigabyte, and the caller then folds them into a second copy, so the two peak
/// together. Here a chunk is folded and freed while its neighbours are still
/// being decoded, which also overlaps the merge with the I/O.
///
/// `sink` is called under a lock, so it is serialised against the workers. That
/// is the same single-threaded merge [`run_pass`]'s callers already do, only
/// earlier.
///
/// `blob_kinds` (from a previous full pass) lets later passes skip blobs that
/// hold nothing they want — a real win because a PBF stores all nodes first,
/// then ways, then relations. A skipped blob's incoming kinds are carried into
/// the returned mask, so a filtered pass's mask is still a complete description
/// of the file and can be fed to the pass after it.
pub fn run_pass_sink<S, F, G, H>(
    path: &Path,
    blobs: &[BlobLoc],
    blob_kinds: Option<&[u8]>,
    want: u8,
    label: &str,
    make: F,
    body: G,
    sink: H,
) -> Result<Vec<u8>>
where
    S: Send,
    F: Fn() -> S + Sync,
    G: Fn(&mut S, &PrimitiveBlock) -> Result<u8> + Sync,
    H: FnMut(S) -> Result<()> + Send,
{
    let n_chunks = blobs.len().div_ceil(CHUNK_BLOBS).max(1);
    let drain: Mutex<Drain<S, H>> = Mutex::new(Drain {
        slots: (0..n_chunks).map(|_| None).collect(),
        next: 0,
        sink,
        err: None,
    });
    let kinds: Vec<AtomicU8> = (0..blobs.len()).map(|_| AtomicU8::new(0)).collect();

    let next_chunk = AtomicUsize::new(0);
    let done_blobs = AtomicUsize::new(0);
    let last_pct = AtomicUsize::new(usize::MAX);
    let n_threads = crate::par::threads().min(n_chunks);
    let mut first_err: Option<Error> = None;

    std::thread::scope(|scope| {
        let mut handles = Vec::with_capacity(n_threads);
        for _ in 0..n_threads {
            handles.push(scope.spawn(|| -> Result<()> {
                let mut file = File::open(path)
                    .map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
                let mut compressed = Vec::new();
                let mut inflated = Vec::new();
                loop {
                    let chunk = next_chunk.fetch_add(1, Ordering::Relaxed);
                    if chunk >= n_chunks {
                        return Ok(());
                    }
                    let start = chunk * CHUNK_BLOBS;
                    let end = ((chunk + 1) * CHUNK_BLOBS).min(blobs.len());
                    let mut state = make();
                    for (i, loc) in blobs[start..end].iter().enumerate() {
                        let idx = start + i;
                        match blob_kinds.filter(|k| k[idx] & want == 0) {
                            Some(k) => kinds[idx].store(k[idx], Ordering::Relaxed),
                            None => {
                                read_exact_at(&mut file, loc, &mut compressed)?;
                                inflate_blob(&compressed, &mut inflated)?;
                                let block = PrimitiveBlock::decode(&inflated)?;
                                let seen = body(&mut state, &block)?;
                                kinds[idx].store(seen, Ordering::Relaxed);
                            }
                        }
                        report(label, &done_blobs, &last_pct, blobs.len());
                    }
                    drain.lock().expect("chunk slot mutex").deposit(chunk, state);
                }
            }));
        }
        for h in handles {
            match h.join() {
                Ok(Ok(())) => {}
                Ok(Err(e)) => {
                    if first_err.is_none() {
                        first_err = Some(e);
                    }
                }
                Err(_) => {
                    if first_err.is_none() {
                        first_err = Some(Error("a reader thread panicked".into()));
                    }
                }
            }
        }
    });

    if let Some(e) = first_err {
        return Err(e);
    }
    let mut d = drain.into_inner().expect("chunk slot mutex");
    if let Some(e) = d.err.take() {
        return Err(e);
    }
    // Every chunk was deposited, and deposits drain in order, so nothing can be
    // left behind unless a sink call failed above.
    debug_assert_eq!(d.next, n_chunks, "chunks left undrained");
    println!("\r{label:<28} [100%]");
    Ok(kinds.iter().map(|k| k.load(Ordering::Relaxed)).collect())
}

/// Holds finished chunks until their turn comes, so the sink sees chunk order
/// however the workers finish.
struct Drain<S, H> {
    slots: Vec<Option<S>>,
    next: usize,
    sink: H,
    err: Option<Error>,
}

impl<S, H: FnMut(S) -> Result<()>> Drain<S, H> {
    fn deposit(&mut self, chunk: usize, state: S) {
        self.slots[chunk] = Some(state);
        while self.next < self.slots.len() {
            let Some(s) = self.slots[self.next].take() else {
                break;
            };
            self.next += 1;
            // Keep draining after a failure: the pass reports the first error at
            // the end, and stopping here would strand the remaining chunks.
            if let Err(e) = (self.sink)(s) {
                if self.err.is_none() {
                    self.err = Some(e);
                }
            }
        }
    }
}

/// Run one pass over the file in parallel, returning every chunk's accumulator.
///
/// Convenience over [`run_pass_sink`] for passes whose accumulators are small
/// enough that holding them all costs nothing. Accumulators come back in chunk
/// order, so merging them is deterministic.
pub fn run_pass<S, F, G>(
    path: &Path,
    blobs: &[BlobLoc],
    blob_kinds: Option<&[u8]>,
    want: u8,
    label: &str,
    make: F,
    body: G,
) -> Result<(Vec<S>, Vec<u8>)>
where
    S: Send,
    F: Fn() -> S + Sync,
    G: Fn(&mut S, &PrimitiveBlock) -> Result<u8> + Sync,
{
    let mut out: Vec<S> = Vec::new();
    let kinds = run_pass_sink(path, blobs, blob_kinds, want, label, make, body, |s| {
        out.push(s);
        Ok(())
    })?;
    Ok((out, kinds))
}

fn read_exact_at(file: &mut File, loc: &BlobLoc, out: &mut Vec<u8>) -> Result<()> {
    file.seek(SeekFrom::Start(loc.offset))
        .map_err(|e| Error(format!("seek to {} failed: {e}", loc.offset)))?;
    out.clear();
    out.resize(loc.datasize as usize, 0);
    file.read_exact(out)
        .map_err(|e| Error(format!("short read at {}: {e}", loc.offset)))
}

fn report(label: &str, done: &AtomicUsize, last_pct: &AtomicUsize, total: usize) {
    let n = done.fetch_add(1, Ordering::Relaxed) + 1;
    if total == 0 {
        return;
    }
    let pct = n * 100 / total;
    let prev = last_pct.load(Ordering::Relaxed);
    if pct != prev
        && last_pct
            .compare_exchange(prev, pct, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    {
        print!("\r{label:<28} [{pct}%] ");
        let _ = std::io::stdout().flush();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testpbf;

    #[test]
    fn round_trips_a_synthetic_file() {
        let (path, _dir) = testpbf::write_sample("pbf_pass");

        let blobs = scan_blobs(&path).unwrap();
        assert_eq!(blobs.len(), 1, "the OSMHeader blob is not returned");

        let (states, kinds) = run_pass(
            &path,
            &blobs,
            None,
            KIND_NODES | KIND_WAYS | KIND_RELATIONS,
            "test",
            || (0usize, 0usize, 0usize),
            |s: &mut (usize, usize, usize), block| {
                let mut mask = 0;
                crate::osm::visit_block(
                    block,
                    KIND_NODES | KIND_WAYS | KIND_RELATIONS,
                    &mut mask,
                    &mut |el: crate::osm::Element| {
                        match el {
                            crate::osm::Element::Node(_) => s.0 += 1,
                            crate::osm::Element::Way(_) => s.1 += 1,
                            crate::osm::Element::Relation(_) => s.2 += 1,
                        }
                        Ok(())
                    },
                )?;
                Ok(mask)
            },
        )
        .unwrap();
        let totals = states
            .iter()
            .fold((0, 0, 0), |a, b| (a.0 + b.0, a.1 + b.1, a.2 + b.2));
        assert_eq!(totals, (testpbf::NODE_COUNT, 3, 1));
        assert_eq!(kinds[0], KIND_NODES | KIND_WAYS | KIND_RELATIONS);
    }

    #[test]
    fn blob_kind_filter_skips_whole_blobs() {
        let (path, _dir) = testpbf::write_sample("pbf_filter");
        let blobs = scan_blobs(&path).unwrap();
        // Pretend the only blob holds nothing but nodes; a ways pass must then
        // never even read it.
        let (states, _) = run_pass(
            &path,
            &blobs,
            Some(&[KIND_NODES]),
            KIND_WAYS,
            "test",
            || 0usize,
            |s: &mut usize, _block| {
                *s += 1;
                Ok(0)
            },
        )
        .unwrap();
        assert_eq!(states.iter().sum::<usize>(), 0);
    }

    #[test]
    fn a_skipped_blob_keeps_its_kinds_in_the_returned_mask() {
        let (path, _dir) = testpbf::write_sample("pbf_carry");
        let blobs = scan_blobs(&path).unwrap();
        let all = KIND_NODES | KIND_WAYS | KIND_RELATIONS;
        // A relations-only pass skips this nodes-and-ways-and-relations blob only
        // if we lie about its contents; do that, and check the lie comes back
        // rather than a zero. A pass that dropped the kinds would make its own
        // returned mask useless to the pass after it.
        let (_, kinds) = run_pass(
            &path,
            &blobs,
            Some(&[KIND_NODES]),
            KIND_WAYS,
            "test",
            || (),
            |_: &mut (), _| Ok(all),
        )
        .unwrap();
        assert_eq!(kinds, vec![KIND_NODES]);

        // A blob the pass does read reports what it actually saw.
        let (_, kinds) = run_pass(
            &path,
            &blobs,
            Some(&[KIND_WAYS]),
            KIND_WAYS,
            "test",
            || (),
            |_: &mut (), _| Ok(all),
        )
        .unwrap();
        assert_eq!(kinds, vec![all]);
    }

    #[test]
    fn the_compression_probe_accepts_zlib_and_rejects_zstd() {
        let (path, dir) = testpbf::write_sample("pbf_probe");
        let blobs = scan_blobs(&path).unwrap();
        probe_compression(&path, &blobs).unwrap();

        // One OSMData blob whose only field is `zstd_data`.
        let blob = [7 << 3 | WIRE_BYTES, 1, 0];
        let mut header = vec![1 << 3 | WIRE_BYTES, 7];
        header.extend_from_slice(b"OSMData");
        header.extend_from_slice(&[3 << 3 | proto::WIRE_VARINT, blob.len() as u8]);
        let mut file = Vec::new();
        file.extend_from_slice(&(header.len() as u32).to_be_bytes());
        file.extend_from_slice(&header);
        file.extend_from_slice(&blob);
        let zstd_path = dir.join("zstd.osm.pbf");
        std::fs::write(&zstd_path, &file).unwrap();

        let blobs = scan_blobs(&zstd_path).unwrap();
        assert_eq!(blobs.len(), 1);
        let err = probe_compression(&zstd_path, &blobs).unwrap_err();
        assert!(err.0.contains("zstd"), "{}", err.0);
    }

    #[test]
    fn raw_size_mismatch_is_rejected() {
        // Blob claiming a raw_size that disagrees with the payload.
        let mut blob = Vec::new();
        blob.push(1 << 3 | WIRE_BYTES);
        blob.push(3);
        blob.extend_from_slice(b"abc");
        blob.push(2 << 3 | proto::WIRE_VARINT);
        blob.push(9);
        let mut out = Vec::new();
        assert!(inflate_blob(&blob, &mut out).is_err());
    }

    #[test]
    fn unsupported_compression_is_reported() {
        let mut blob = vec![7 << 3 | WIRE_BYTES, 1, 0];
        let mut out = Vec::new();
        let err = inflate_blob(&blob, &mut out).unwrap_err();
        assert!(err.0.contains("zstd"), "{}", err.0);
        blob[0] = 4 << 3 | WIRE_BYTES;
        let err = inflate_blob(&blob, &mut out).unwrap_err();
        assert!(err.0.contains("lzma"), "{}", err.0);
    }
}
