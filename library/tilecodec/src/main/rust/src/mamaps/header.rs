//! The `.mamaps` header: 128 fixed bytes that locate every other section.
//!
//! Every section is found **only** through an offset declared here. Nothing is inferred from
//! layout, because layout is not fixed: the real 137 GB PMTiles archive puts its leaf
//! directories *after* its tile data, and a reader that assumed otherwise would address the
//! wrong bytes. The same freedom is deliberately kept here.
//!
//! The header is small enough that it always arrives inside the reader's opening prefix
//! together with the dictionary and the root index, which is what makes a cold open one range
//! request. [`super::write`] asserts that budget rather than letting a build silently cost every
//! reader a third round trip.

use crate::proto::{err, Result};

pub const MAGIC: &[u8; 7] = b"MAMAPS\0";

/// Bumped only for a change a reader cannot ignore. The archive carries a
/// [`build_id`](Header::build_id) for "same format, different data".
pub const FORMAT_VERSION: u8 = 1;

pub const HEADER_LEN: usize = 128;

/// Bodies are compressed frames; clear means the body is stored raw.
pub const FLAG_BODIES_COMPRESSED: u16 = 1 << 0;
/// At least one leaf entry has a `run_length` above 1, so a reader must honour runs.
pub const FLAG_RUN_LENGTH_PRESENT: u16 = 1 << 1;
/// Ring winding and hole containment were normalised at build time.
///
/// What lets `tess::fill`'s repair pass be skipped: with this set, every polygon part group has
/// exactly one CCW outer and its holes are CW and strictly inside it.
pub const FLAG_RINGS_VALIDATED: u16 = 1 << 2;

const KNOWN_FLAGS: u16 =
    FLAG_BODIES_COMPRESSED | FLAG_RUN_LENGTH_PRESENT | FLAG_RINGS_VALIDATED;

/// Bodies stored as written.
pub const COMPRESSION_NONE: u8 = 0;
/// Raw DEFLATE, one independent frame per body.
///
/// Not zstd, which is what a container designed on paper would reach for. This crate's whole
/// dependency list is `miniz_oxide` — pure Rust, so it cross-compiles for
/// `aarch64-linux-android` with no C toolchain and nothing to configure — and zstd's encoder
/// would end that. Not gzip either: gzip costs 18 bytes of framing per body and existed only
/// because MapLibre requires it, which nothing reading this format does.
pub const COMPRESSION_DEFLATE: u8 = 1;

/// The deepest zoom a `tile_id_lo` can span inside one leaf. See [`super::index`].
pub const MAX_ZOOM: u8 = 22;

/// What a reader must know before it can address anything.
///
/// Field order **is** wire order, and the byte map is in [`Header::parse`]. Lengths are `u32`
/// wherever a section cannot plausibly exceed 4 GiB; offsets are `u64` without exception,
/// because the data section on its own is already past that on a planet build.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    pub flags: u16,
    pub compression: u8,
    pub layer_count: u8,
    pub min_zoom: u8,
    pub max_zoom: u8,
    /// Identifies the *data*, not the format: hashed over the generator revision, the input
    /// digest, the zoom range, the layer set and the simplification parameters.
    ///
    /// This is the only thing standing between republishing under a stable `immutable` URL and
    /// every existing reader serving stale tiles forever. It costs no extra request, because it
    /// is already in the prefix a reader fetches to open the archive at all.
    pub build_id: u64,
    /// The whole file, so a truncated download is caught on open rather than at the first tile
    /// that happens to land past the end.
    pub file_len: u64,
    pub dict_offset: u64,
    pub dict_len: u32,
    /// Leaf entries per leaf. A power of two, doubled at build time when the root would not fit.
    pub leaf_entry_capacity: u32,
    pub root_offset: u64,
    pub root_len: u32,
    pub leaf_count: u32,
    pub leaf_offset: u64,
    pub leaf_len: u32,
    pub data_offset: u64,
    pub data_len: u64,
    /// Tiles that resolve to a body, counting every id a run covers.
    pub tiles_addressed: u64,
    /// Bodies actually stored, after run-length and content dedup.
    pub bodies_written: u64,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
}

impl Header {
    pub fn compressed(&self) -> bool {
        self.flags & FLAG_BODIES_COMPRESSED != 0
    }

    pub fn rings_validated(&self) -> bool {
        self.flags & FLAG_RINGS_VALIDATED != 0
    }

    /// Read a header out of the opening prefix.
    ///
    /// Byte map, all little-endian: `0..7` magic, `7` format version, `8..10` header_len,
    /// `10..12` flags, `12` compression, `13` layer_count, `14` min_zoom, `15` max_zoom,
    /// `16..24` build_id, `24..32` file_len, `32..40` dict_offset, `40..44` dict_len,
    /// `44..48` leaf_entry_capacity, `48..56` root_offset, `56..60` root_len, `60..64`
    /// leaf_count, `64..72` leaf_offset, `72..76` leaf_len, `76..80` reserved, `80..88`
    /// data_offset, `88..96` data_len, `96..104` tiles_addressed, `104..112` bodies_written,
    /// `112..128` the bbox as four `i32` of degrees times 1e7.
    ///
    /// Every `u64` sits on an 8-byte boundary so a reader may take them as aligned loads.
    pub fn parse(buf: &[u8]) -> Result<Header> {
        if buf.len() < HEADER_LEN {
            return err(format!("a .mamaps header is {HEADER_LEN} bytes, got {}", buf.len()));
        }
        if &buf[0..7] != MAGIC {
            return err("not a .mamaps archive (bad magic)");
        }
        if buf[7] != FORMAT_VERSION {
            return err(format!(
                "unsupported .mamaps format version {} (this reader speaks {FORMAT_VERSION})",
                buf[7],
            ));
        }
        let u16_at = |o: usize| u16::from_le_bytes([buf[o], buf[o + 1]]);
        let u32_at = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let i32_at = |o: usize| i32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o],
                buf[o + 1],
                buf[o + 2],
                buf[o + 3],
                buf[o + 4],
                buf[o + 5],
                buf[o + 6],
                buf[o + 7],
            ])
        };

        let header_len = u16_at(8);
        if header_len as usize != HEADER_LEN {
            return err(format!("a .mamaps header declares {header_len} bytes, not {HEADER_LEN}"));
        }
        let flags = u16_at(10);
        // An unknown flag means the writer recorded something about the bodies that this reader
        // would ignore, and every flag in this format changes how a body must be handled. Better
        // to refuse the archive than to draw it wrong.
        if flags & !KNOWN_FLAGS != 0 {
            return err(format!("a .mamaps header sets unknown flags {:#06X}", flags & !KNOWN_FLAGS));
        }
        // Reserved bytes are checked rather than skipped, so a later version cannot put a field
        // here and have this reader silently ignore it.
        if u32_at(76) != 0 {
            return err("a .mamaps header has a non-zero reserved word");
        }
        let header = Header {
            flags,
            compression: buf[12],
            layer_count: buf[13],
            min_zoom: buf[14],
            max_zoom: buf[15],
            build_id: u64_at(16),
            file_len: u64_at(24),
            dict_offset: u64_at(32),
            dict_len: u32_at(40),
            leaf_entry_capacity: u32_at(44),
            root_offset: u64_at(48),
            root_len: u32_at(56),
            leaf_count: u32_at(60),
            leaf_offset: u64_at(64),
            leaf_len: u32_at(72),
            data_offset: u64_at(80),
            data_len: u64_at(88),
            tiles_addressed: u64_at(96),
            bodies_written: u64_at(104),
            min_lon_e7: i32_at(112),
            min_lat_e7: i32_at(116),
            max_lon_e7: i32_at(120),
            max_lat_e7: i32_at(124),
        };
        header.check()?;
        Ok(header)
    }

    /// Everything a later read would otherwise discover the hard way.
    ///
    /// A corrupt or hostile header must not be able to name a section that overlaps another, runs
    /// past the file, or is implausibly large — because every subsequent read takes its length
    /// from a field here, and a wrong length is a read that either fails somewhere uninformative
    /// or poisons a disk cache entry with a body of the wrong size.
    fn check(&self) -> Result<()> {
        if !matches!(self.compression, COMPRESSION_NONE | COMPRESSION_DEFLATE) {
            return err(format!("unknown .mamaps compression {}", self.compression));
        }
        if self.compressed() != (self.compression != COMPRESSION_NONE) {
            return err("a .mamaps header disagrees with itself about whether bodies are compressed");
        }
        if self.min_zoom > self.max_zoom || self.max_zoom > MAX_ZOOM {
            return err(format!(
                "a .mamaps header declares an impossible zoom range {}..={}",
                self.min_zoom, self.max_zoom,
            ));
        }
        if !self.leaf_entry_capacity.is_power_of_two() {
            return err(format!(
                "a .mamaps leaf capacity of {} is not a power of two",
                self.leaf_entry_capacity,
            ));
        }
        if self.leaf_count == 0 {
            return err("a .mamaps archive needs at least one leaf");
        }
        if self.file_len < HEADER_LEN as u64 {
            return err(format!("a .mamaps header declares a {} byte file", self.file_len));
        }
        let sections = [
            ("the dictionary", self.dict_offset, self.dict_len as u64),
            ("the root index", self.root_offset, self.root_len as u64),
            ("the leaf index", self.leaf_offset, self.leaf_len as u64),
            ("the tile data", self.data_offset, self.data_len),
        ];
        for (what, offset, len) in sections {
            if offset < HEADER_LEN as u64 {
                return err(format!("{what} overlaps the .mamaps header"));
            }
            match offset.checked_add(len) {
                Some(end) if end <= self.file_len => {}
                _ => return err(format!("{what} runs past the end of the .mamaps file")),
            }
        }
        // Pairwise, because a writer that patched one offset and not another would otherwise
        // produce a file whose index and data disagree about where a body lives.
        for (i, (what, offset, len)) in sections.iter().enumerate() {
            for (other, other_offset, other_len) in &sections[i + 1..] {
                if *len == 0 || *other_len == 0 {
                    continue;
                }
                if *offset < other_offset + other_len && *other_offset < offset + len {
                    return err(format!("{what} and {other} overlap in the .mamaps file"));
                }
            }
        }
        if self.root_len as usize % super::index::ROOT_ENTRY_LEN != 0 {
            return err(format!(
                "a .mamaps root index of {} bytes is not a whole number of {} byte entries",
                self.root_len,
                super::index::ROOT_ENTRY_LEN,
            ));
        }
        if self.bodies_written > self.tiles_addressed {
            return err("a .mamaps header stores more bodies than it addresses tiles");
        }
        Ok(())
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_LEN);
        out.extend_from_slice(MAGIC);
        out.push(FORMAT_VERSION);
        out.extend_from_slice(&(HEADER_LEN as u16).to_le_bytes());
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.push(self.compression);
        out.push(self.layer_count);
        out.push(self.min_zoom);
        out.push(self.max_zoom);
        out.extend_from_slice(&self.build_id.to_le_bytes());
        out.extend_from_slice(&self.file_len.to_le_bytes());
        out.extend_from_slice(&self.dict_offset.to_le_bytes());
        out.extend_from_slice(&self.dict_len.to_le_bytes());
        out.extend_from_slice(&self.leaf_entry_capacity.to_le_bytes());
        out.extend_from_slice(&self.root_offset.to_le_bytes());
        out.extend_from_slice(&self.root_len.to_le_bytes());
        out.extend_from_slice(&self.leaf_count.to_le_bytes());
        out.extend_from_slice(&self.leaf_offset.to_le_bytes());
        out.extend_from_slice(&self.leaf_len.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&self.data_offset.to_le_bytes());
        out.extend_from_slice(&self.data_len.to_le_bytes());
        out.extend_from_slice(&self.tiles_addressed.to_le_bytes());
        out.extend_from_slice(&self.bodies_written.to_le_bytes());
        for v in [self.min_lon_e7, self.min_lat_e7, self.max_lon_e7, self.max_lat_e7] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        debug_assert_eq!(out.len(), HEADER_LEN);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A header naming sections that do not overlap, in a file big enough to hold them.
    fn plausible() -> Header {
        Header {
            flags: FLAG_BODIES_COMPRESSED,
            compression: COMPRESSION_DEFLATE,
            layer_count: 7,
            min_zoom: 0,
            max_zoom: 14,
            build_id: 0x0123_4567_89AB_CDEF,
            file_len: 4096,
            dict_offset: 128,
            dict_len: 512,
            leaf_entry_capacity: 4096,
            root_offset: 640,
            root_len: 64,
            leaf_count: 2,
            leaf_offset: 704,
            leaf_len: 32,
            data_offset: 736,
            data_len: 3360,
            tiles_addressed: 9,
            bodies_written: 4,
            min_lon_e7: -1_242_000_000,
            min_lat_e7: 324_000_000,
            max_lon_e7: -1_140_000_000,
            max_lat_e7: 420_000_000,
        }
    }

    #[test]
    fn a_header_round_trips_through_its_own_bytes() {
        let header = plausible();
        let bytes = header.serialize();
        assert_eq!(bytes.len(), HEADER_LEN, "the header is a fixed 128 bytes");
        assert_eq!(Header::parse(&bytes).expect("should parse"), header);
    }

    #[test]
    fn every_u64_field_is_eight_byte_aligned() {
        // So a reader may take them as aligned loads out of a zero-copy prefix slice.
        for offset in [16, 24, 32, 48, 64, 80, 88, 96, 104] {
            assert_eq!(offset % 8, 0, "a u64 sits at byte {offset}");
        }
    }

    #[test]
    fn a_header_with_trailing_bytes_still_parses() {
        // It arrives inside a 16 KiB prefix, never on its own.
        let mut bytes = plausible().serialize();
        bytes.extend_from_slice(&[0xAB; 1024]);
        assert!(Header::parse(&bytes).is_ok());
    }

    #[test]
    fn a_truncated_or_foreign_header_is_refused() {
        let bytes = plausible().serialize();
        assert!(Header::parse(&bytes[..HEADER_LEN - 1]).is_err(), "short");
        let mut wrong_magic = bytes.clone();
        wrong_magic[0] = b'P';
        assert!(Header::parse(&wrong_magic).is_err(), "PMTiles is not this format");
        let mut wrong_version = bytes.clone();
        wrong_version[7] = FORMAT_VERSION + 1;
        assert!(Header::parse(&wrong_version).is_err(), "a newer format");
    }

    /// An unknown flag means the writer said something about the bodies this reader would
    /// ignore, and every flag here changes how a body must be handled.
    #[test]
    fn an_unknown_flag_or_a_dirty_reserved_word_is_refused() {
        let mut bytes = plausible().serialize();
        bytes[11] = 0x80;
        assert!(Header::parse(&bytes).is_err(), "an unknown flag");
        let mut bytes = plausible().serialize();
        bytes[76] = 1;
        assert!(Header::parse(&bytes).is_err(), "a reserved word a later version may claim");
    }

    /// Every later read takes its length from a header field, so a header that names an
    /// impossible section has to fail here rather than somewhere uninformative.
    #[test]
    fn a_header_whose_sections_do_not_fit_the_file_is_refused() {
        let cases: &[(&str, fn(&mut Header))] = &[
            ("data past the end", |h| h.data_len = 1 << 40),
            ("a section inside the header", |h| h.dict_offset = 8),
            ("the dictionary over the root", |h| h.dict_len = 600),
            ("a file shorter than the header", |h| h.file_len = 8),
            ("a zoom range inverted", |h| h.min_zoom = 15),
            ("a zoom past the renderer's maximum", |h| h.max_zoom = 30),
            ("a leaf capacity that is not a power of two", |h| h.leaf_entry_capacity = 3000),
            ("no leaves at all", |h| h.leaf_count = 0),
            ("an unknown compression", |h| h.compression = 9),
            ("more bodies than addressed tiles", |h| h.bodies_written = 10),
            ("a root that is not whole entries", |h| h.root_len = 63),
            ("a flag that contradicts the compression byte", |h| {
                h.compression = COMPRESSION_NONE;
            }),
        ];
        for (what, break_it) in cases {
            let mut header = plausible();
            break_it(&mut header);
            assert!(Header::parse(&header.serialize()).is_err(), "{what} should be refused");
        }
    }

    #[test]
    fn an_offset_length_pair_cannot_overflow_into_looking_valid() {
        let mut header = plausible();
        header.data_offset = u64::MAX - 8;
        header.data_len = 64;
        assert!(Header::parse(&header.serialize()).is_err(), "the end wraps");
    }
}
