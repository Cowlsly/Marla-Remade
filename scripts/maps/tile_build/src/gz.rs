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
const LEVEL: u8 = 9;

/// Wrap `data` in a gzip stream.
pub fn compress(data: &[u8]) -> Vec<u8> {
    let body = miniz_oxide::deflate::compress_to_vec(data, LEVEL);
    let mut out = Vec::with_capacity(body.len() + 18);
    out.extend_from_slice(&[
        0x1f, 0x8b, // magic
        0x08, // CM = DEFLATE
        0x00, // FLG: no extra fields
        0x00, 0x00, 0x00, 0x00, // MTIME 0, so output is reproducible
        0x00, // XFL
        0xff, // OS = unknown, again for reproducibility
    ]);
    out.extend_from_slice(&body);
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
