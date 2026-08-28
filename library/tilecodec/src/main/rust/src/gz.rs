//! gzip framing over `miniz_oxide`'s raw DEFLATE.
//!
//! PMTiles gzips both its directories and its tiles, and MapLibre's pmtiles
//! plugin expects exactly that, so the tile builder has to produce real gzip
//! streams rather than bare deflate or zlib. `miniz_oxide` only does raw DEFLATE
//! (and zlib), so the 10-byte header, CRC32 and ISIZE trailer are added here.
//!
//! `osm_ingest` gets away with `miniz_oxide` alone because an `.osm.pbf` uses
//! zlib blobs and is only ever read. This crate both reads and writes.

use crate::proto::{err, Error, Result};

const FHCRC: u8 = 1 << 1;
const FEXTRA: u8 = 1 << 2;
const FNAME: u8 = 1 << 3;
const FCOMMENT: u8 = 1 << 4;

/// DEFLATE level. 9 is the usual archive setting: tiles are written once and
/// streamed by range request many times, so bytes matter more than build time.
///
/// Six was tried, on the theory that `encode`'s 43.9 s of a 135.8 s California build was
/// mostly compression. It is not: level six measured 44.8 s — inside the noise — for 0.12%
/// more bytes. The cost in that phase is stage C, body serialisation and *building the
/// compressor* ([`Compressor`]), not the deflate itself, so there is nothing here to trade
/// and the better ratio is free. Worth recording so nobody re-runs the experiment.
const LEVEL: u8 = 9;

/// Wrap `data` in a gzip stream.
///
/// Convenience over [`Compressor`] for one-shot callers (directories, tests). The
/// tilers use a per-worker [`Compressor`] instead — see its docs for why.
pub fn compress(data: &[u8]) -> Vec<u8> {
    frame(data, &miniz_oxide::deflate::compress_to_vec(data, LEVEL))
}

/// A reusable DEFLATE state, so a tile's compression does not pay for building one.
///
/// `miniz_oxide::deflate::compress_to_vec` constructs a fresh `CompressorOxide` per
/// call, and that state is large — a 32 KiB dictionary plus hash and Huffman tables,
/// hundreds of kilobytes, much of it zeroed on construction. On one core that block
/// is reused by the allocator and stays cache-hot, so it costs almost nothing. Across
/// 32 cores each worker rebuilds its own every tile, and the encode pass becomes
/// memory-bandwidth bound: measured on 200k synthetic roads at z14, it went 3.72s at
/// one thread to 1.55s at four and then back UP to 3.23s at 32. Reusing one state per
/// worker is what removes that ceiling.
///
/// Output is byte-for-byte what `compress` produces — `reset` keeps the parameters,
/// and `the_reusable_compressor_matches_the_one_shot_one` holds the two to it.
///
/// The state is BOXED, and that is not cosmetic. `CompressorOxide` is 65,712 bytes of
/// inline arrays — a 64 KiB LZ code buffer, a 32 KiB dictionary, a 64 KiB hash chain —
/// and the tilers hand one to `rayon`'s `map_init`, which keeps a live value per split
/// of its recursive descent. Held by value that put tens of these on a worker's stack
/// and overflowed it: a California roads build died at z15 encode with
/// `thread 'tile_build-1' has overflowed its stack`, 186 s in. On the heap the
/// per-worker slot is a pointer, and the recursion costs nothing.
pub struct Compressor {
    state: Box<miniz_oxide::deflate::core::CompressorOxide>,
    /// Grown to the largest tile seen and then reused, so the output buffer is not
    /// reallocated per tile either.
    scratch: Vec<u8>,
    used: bool,
}

impl Default for Compressor {
    fn default() -> Compressor {
        Compressor::new()
    }
}

impl Compressor {
    pub fn new() -> Compressor {
        // The flags `compress_to_vec(_, LEVEL)` computes: window_bits 0 (raw DEFLATE,
        // no zlib wrapper) and the default strategy. Matching them is what makes the
        // two functions produce the same bytes.
        let flags = miniz_oxide::deflate::core::create_comp_flags_from_zip_params(
            LEVEL as i32,
            0,
            0,
        );
        Compressor {
            state: Box::new(miniz_oxide::deflate::core::CompressorOxide::new(flags)),
            scratch: Vec::new(),
            used: false,
        }
    }

    /// Wrap `data` in a gzip stream, reusing this compressor's state.
    pub fn compress(&mut self, data: &[u8]) -> Vec<u8> {
        // Length first, so the mutable borrow ends before `frame` reads `scratch`.
        let n = self.deflate(data).len();
        frame(data, &self.scratch[..n])
    }

    /// Raw DEFLATE of `data`, reusing this compressor's state.
    ///
    /// Exactly the bytes `miniz_oxide::deflate::compress_to_vec(data, LEVEL)` returns, minus
    /// the 65,712-byte state it would build to return them. Split out of [`Self::compress`]
    /// because `.mamaps` bodies are raw DEFLATE behind an uncompressed header rather than a
    /// gzip frame, and that path needs the reuse just as badly — see
    /// [`crate::mamaps::write::compress_body_with`].
    pub fn deflate(&mut self, data: &[u8]) -> &[u8] {
        use miniz_oxide::deflate::core::{compress, TDEFLFlush, TDEFLStatus};

        if self.used {
            self.state.reset();
        }
        self.used = true;

        // Deflate output does not depend on how the output buffer is chunked, only on
        // the input and the parameters, so growing rather than guessing right is safe.
        let want = data.len().saturating_add(data.len() / 2).saturating_add(64);
        if self.scratch.len() < want {
            self.scratch.resize(want, 0);
        }

        let mut input = data;
        let mut at = 0usize;
        loop {
            let (status, read, wrote) =
                compress(&mut self.state, input, &mut self.scratch[at..], TDEFLFlush::Finish);
            at += wrote;
            match status {
                TDEFLStatus::Done => break,
                TDEFLStatus::Okay if read <= input.len() => {
                    input = &input[read..];
                    if self.scratch.len() - at < 64 {
                        let grown = (self.scratch.len() * 2).max(at + 64);
                        self.scratch.resize(grown, 0);
                    }
                }
                // Upstream panics here too: the remaining statuses are "bad parameters"
                // and "output buffer of length zero", neither of which can happen above.
                other => panic!("deflate failed unexpectedly: {other:?}"),
            }
        }
        &self.scratch[..at]
    }
}

/// The 10-byte gzip header, the deflate body, then CRC32 and ISIZE.
fn frame(data: &[u8], body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(body.len() + 18);
    out.extend_from_slice(&[
        0x1f, 0x8b, // magic
        0x08, // CM = DEFLATE
        0x00, // FLG: no extra fields
        0x00, 0x00, 0x00, 0x00, // MTIME 0, so output is reproducible
        0x00, // XFL
        0xff, // OS = unknown, again for reproducibility
    ]);
    out.extend_from_slice(body);
    out.extend_from_slice(&crc32(data).to_le_bytes());
    out.extend_from_slice(&(data.len() as u32).to_le_bytes());
    out
}

/// Unwrap a gzip stream. Tolerates the optional header fields a stream from
/// another producer may carry, since we read files tippecanoe wrote.
pub fn decompress(data: &[u8]) -> Result<Vec<u8>> {
    if data.len() < 18 {
        return err("gzip stream too short");
    }
    if data[0] != 0x1f || data[1] != 0x8b {
        return err("not a gzip stream (bad magic)");
    }
    if data[2] != 0x08 {
        return err(format!("unsupported gzip compression method {}", data[2]));
    }
    let flags = data[3];
    let mut pos = 10usize;
    if flags & FEXTRA != 0 {
        if pos + 2 > data.len() {
            return err("truncated gzip FEXTRA");
        }
        let xlen = u16::from_le_bytes([data[pos], data[pos + 1]]) as usize;
        pos += 2 + xlen;
    }
    for flag in [FNAME, FCOMMENT] {
        if flags & flag != 0 {
            // NUL-terminated string.
            let start = pos;
            while pos < data.len() && data[pos] != 0 {
                pos += 1;
            }
            if pos >= data.len() {
                return err("unterminated gzip header string");
            }
            pos += 1;
            let _ = start;
        }
    }
    if flags & FHCRC != 0 {
        pos += 2;
    }
    // The last 8 bytes are CRC32 + ISIZE, not deflate data.
    if pos + 8 > data.len() {
        return err("truncated gzip stream");
    }
    let body = &data[pos..data.len() - 8];
    let out = miniz_oxide::inflate::decompress_to_vec(body)
        .map_err(|e| Error(format!("gzip inflate failed: {e:?}")))?;

    let tail = &data[data.len() - 8..];
    let want_crc = u32::from_le_bytes([tail[0], tail[1], tail[2], tail[3]]);
    let want_len = u32::from_le_bytes([tail[4], tail[5], tail[6], tail[7]]);
    // Checked, not assumed: a silently corrupt directory would otherwise surface
    // much later as a nonsensical tile offset.
    if want_len != out.len() as u32 {
        return err(format!("gzip length mismatch: header {want_len}, got {}", out.len()));
    }
    let got_crc = crc32(&out);
    if want_crc != got_crc {
        return err(format!("gzip CRC mismatch: header {want_crc:#x}, got {got_crc:#x}"));
    }
    Ok(out)
}

/// IEEE CRC-32, table-built on first use.
fn crc32(data: &[u8]) -> u32 {
    let table = crc_table();
    let mut crc = 0xFFFF_FFFFu32;
    for &b in data {
        crc = table[((crc ^ b as u32) & 0xFF) as usize] ^ (crc >> 8);
    }
    crc ^ 0xFFFF_FFFF
}

fn crc_table() -> &'static [u32; 256] {
    use std::sync::OnceLock;
    static TABLE: OnceLock<[u32; 256]> = OnceLock::new();
    TABLE.get_or_init(|| {
        let mut t = [0u32; 256];
        for (i, slot) in t.iter_mut().enumerate() {
            let mut c = i as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            }
            *slot = c;
        }
        t
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The same z11 tile as the MVT fixture, but still gzipped exactly as the
    /// published archive stores it. Proves our reader handles a real producer's
    /// stream, not just our own writer's.
    const REAL_GZ: &[u8] = include_bytes!("../tests/fixtures/v5ca_z11_tile.mvt.gz");
    const REAL_RAW: &[u8] = include_bytes!("../tests/fixtures/v5ca_z11_tile.mvt");

    /// The reusable compressor must be indistinguishable from the one-shot one, or
    /// every archive built by a tiler differs from one built by a test helper.
    ///
    /// Reuse is the interesting half: a `reset` that left dictionary or Huffman state
    /// behind would still produce a VALID gzip stream, just a different one, and only
    /// comparing the bytes of the second and later calls catches it. So the corpus is
    /// run through a single compressor in sequence, and each result is checked against
    /// [`Compressor::deflate`] is what `.mamaps` bodies go through, so it has to match the
    /// one-shot call just as [`Compressor::compress`] does — and it must keep matching across a
    /// run of bodies, because a generator reuses one state for a whole zoom.
    ///
    /// The sizes below are the shape a tiler actually produces: one large body, then hundreds of
    /// small ones. A `reset` that dropped the dictionary would still pass on the first body.
    #[test]
    fn the_reusable_deflate_matches_the_one_shot_one() {
        let mut corpus: Vec<Vec<u8>> = vec![
            Vec::new(),
            b"x".to_vec(),
            REAL_RAW.to_vec(),
            vec![7u8; 250_000],
        ];
        for i in 0..200u32 {
            corpus.push(
                format!("body {i}: a tile layer with repeated repeated repeated keys")
                    .into_bytes(),
            );
        }
        let mut reusable = Compressor::new();
        for (i, data) in corpus.iter().enumerate() {
            let want = miniz_oxide::deflate::compress_to_vec(data, LEVEL);
            let got = reusable.deflate(data).to_vec();
            assert!(
                got == want,
                "corpus[{i}] ({} byte(s)): reused {} byte(s), one-shot {}",
                data.len(),
                got.len(),
                want.len(),
            );
        }
    }

    /// a fresh one-shot call.
    #[test]
    fn the_reusable_compressor_matches_the_one_shot_one() {
        let mut corpus: Vec<Vec<u8>> = vec![
            Vec::new(),
            b"a".to_vec(),
            b"hello hello hello hello hello".to_vec(),
            REAL_RAW.to_vec(),
            // Highly compressible, then barely compressible: two very different paths
            // through the Huffman tables, back to back.
            vec![0u8; 100_000],
            (0..70_000u32).map(|i| (i.wrapping_mul(2654435761) >> 13) as u8).collect(),
        ];
        // A run of small inputs after a large one, which is what a tiler actually does.
        for i in 0..40u32 {
            corpus.push(format!("tile body number {i} with some repetition repetition").into_bytes());
        }

        let mut reusable = Compressor::new();
        for (i, data) in corpus.iter().enumerate() {
            let want = compress(data);
            let got = reusable.compress(data);
            assert!(
                got == want,
                "corpus[{i}] ({} byte(s)): reused {} byte(s), one-shot {}",
                data.len(),
                got.len(),
                want.len()
            );
            assert_eq!(&decompress(&got).unwrap(), data, "corpus[{i}] round trip");
        }
    }

    /// The compressor must stay small enough to live in a per-split slot.
    ///
    /// `rayon`'s `map_init` keeps one live value per split of its recursive descent, so
    /// a large-by-value `Compressor` overflows a worker's 2 MiB stack rather than
    /// merely being wasteful — which is exactly how a California roads build died
    /// before the state was boxed. A pointer plus a `Vec` is the whole struct.
    #[test]
    fn the_compressor_state_stays_on_the_heap() {
        let size = std::mem::size_of::<Compressor>();
        assert!(
            size <= 64,
            "Compressor is {size} bytes; the deflate state must stay boxed, or the \
             tilers put one of these per rayon split on a worker's stack"
        );
    }

    #[test]
    fn crc32_matches_the_known_check_value() {
        // The standard CRC-32 check value for "123456789".
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
        assert_eq!(crc32(b""), 0);
    }

    #[test]
    fn round_trips_including_empty_and_incompressible_input() {
        for case in [
            b"".to_vec(),
            b"hello".to_vec(),
            vec![0u8; 100_000],
            (0..=255u8).cycle().take(50_000).collect::<Vec<_>>(),
        ] {
            let gz = compress(&case);
            assert_eq!(&gz[..3], &[0x1f, 0x8b, 0x08], "gzip magic + method");
            assert_eq!(decompress(&gz).unwrap(), case, "len {}", case.len());
        }
    }

    #[test]
    fn compression_is_reproducible() {
        // MTIME and OS are pinned, so two builds of the same tile are identical
        // and an unchanged layer produces an unchanged archive.
        assert_eq!(compress(b"the same bytes"), compress(b"the same bytes"));
    }

    #[test]
    fn decompresses_a_real_archive_stream() {
        let out = decompress(REAL_GZ).expect("the published tile's gzip stream");
        assert_eq!(out, REAL_RAW, "must inflate to the same MVT bytes");
    }

    #[test]
    fn a_real_stream_survives_our_own_round_trip() {
        let raw = decompress(REAL_GZ).unwrap();
        assert_eq!(decompress(&compress(&raw)).unwrap(), raw);
    }

    #[test]
    fn corruption_is_detected_rather_than_returned() {
        let mut gz = compress(b"important tile bytes");
        let n = gz.len();
        // Flip a bit in the CRC trailer.
        gz[n - 5] ^= 0x01;
        assert!(decompress(&gz).is_err(), "a bad CRC must be rejected");

        let mut gz = compress(b"important tile bytes");
        let n = gz.len();
        // Corrupt the ISIZE trailer.
        gz[n - 1] ^= 0xFF;
        assert!(decompress(&gz).is_err(), "a bad length must be rejected");
    }

    #[test]
    fn non_gzip_input_errors() {
        assert!(decompress(b"not gzip at all, but long enough").is_err());
        assert!(decompress(b"short").is_err());
    }

    #[test]
    fn optional_header_fields_are_skipped() {
        // Hand-build a stream carrying FNAME, which some producers set.
        let body = miniz_oxide::deflate::compress_to_vec(b"payload", LEVEL);
        let mut gz = vec![0x1f, 0x8b, 0x08, FNAME, 0, 0, 0, 0, 0, 0xff];
        gz.extend_from_slice(b"tile.mvt\0");
        gz.extend_from_slice(&body);
        gz.extend_from_slice(&crc32(b"payload").to_le_bytes());
        gz.extend_from_slice(&(7u32).to_le_bytes());
        assert_eq!(decompress(&gz).unwrap(), b"payload");
    }
}
