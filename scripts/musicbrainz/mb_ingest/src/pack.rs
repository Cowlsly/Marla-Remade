//! Serialize the offline MusicBrainz catalogue to the on-disk `.pack` format and
//! provide the writer primitives (string pool, enum pool, varints, section
//! directory) the ingest passes in `build.rs` are built out of.
//!
//! ON-DISK FORMAT v1 ("MBP1", little-endian, mmap-friendly, read via
//! `read_unaligned`). THIS LAYOUT MUST STAY IN SYNC WITH
//! `scripts/musicbrainz/mb_ingest/src/reader.rs`, which is the consumer copied
//! into / path-depended on by `location_share_server`.
//!
//! Design provenance: `c:\Users\Vayun\.llms\plans\mb_pack.plan.md` (pack-designer,
//! team `mb-offline`, task 2). Section numbering below is that document's §2.3
//! numbering, preserved even where a section is now unused, so the two can be
//! read side by side. Conventions are lifted from `scripts/maps/gtfs_ingest/src/index.rs`
//! ("TRX2"): fixed header, `{u64 offset, u64 len}` section directory, 8-byte
//! aligned sections, an interning string pool, CSR child lists, and a varint
//! stream for the innermost high-cardinality table.
//!
//! # The trap this format exists to avoid
//!
//! `index.rs:15-27` records the v1 GTFS failure: a fixed-size record *per stop
//! per trip* made a world pack 10-20 GB and overflowed a u32 ceiling. `track` is
//! this format's `stop_time` -- 57.4 M rows, always read as a contiguous run per
//! medium. So tracks are never a fixed-size record and never a `Vec<TrackRec>`
//! in the writer: they are a varint stream (§ TRACKS) addressed by a per-medium
//! byte offset, factored against their recording so the common track costs ~2 B.
//! Section offsets and lengths are **u64**, so the file may exceed 4 GB.
//!
//! # Header (64 bytes)
//!
//! ```text
//! 0x00 u32 magic        = 0x3150424D ("MBP1")
//! 0x04 u32 version      = 1
//! 0x08 u32 section_count= 32
//! 0x0C u32 artist_count
//! 0x10 u32 credit_count
//! 0x14 u32 release_group_count
//! 0x18 u32 release_count
//! 0x1C u32 medium_count
//! 0x20 u32 track_count
//! 0x24 u32 recording_count
//! 0x28 u32 isrc_count
//! 0x2C u32 search_term_count
//! 0x30 u32 enum_count
//! 0x34 u32 flags
//! 0x38 u32 dump_date    // YYYYMMDD of the source mbdump, 0 if unknown
//! 0x3C u32 string_block_count
//! ```
//!
//! Then the section directory: `section_count * { u64 offset, u64 len }`,
//! absolute byte offsets from file start, every section 8-byte aligned. Absent
//! sections are present in the directory with `len == 0` rather than renumbered,
//! so one reader serves every tier of the design doc's §2.4 without a version
//! bump; `flags` says which capabilities are really there.
//!
//! `flags` bits: 0 track MBIDs present, 1 recording MBIDs present, 2 ISRCs
//! present, 3 recording search present, 4 official-releases-only, 5 string pool
//! block-compressed with zstd.
//!
//! String pool offset 0 is the empty string. Dates pack into a u32 as
//! `year<<9 | month<<5 | day`, 0 meaning unknown.
//!
//! # Sections
//!
//! ```text
//!  0 STRINGS          The string pool. Offsets are byte offsets into the
//!                     UNCOMPRESSED pool. Strings are sorted alphabetically, which
//!                     is worth ~0.5 MB per MB to zstd and is free because offsets
//!                     are assigned after the sort. When flags bit 5 is set this
//!                     section holds zstd blocks, each covering exactly
//!                     STRING_BLOCK_SIZE (64 KiB) of uncompressed pool, so a pool
//!                     offset names its block by division. A string may straddle a
//!                     block boundary; a reader that does not find the terminating
//!                     NUL continues into the next block.
//!  1 STRINGS_BLKIDX   u32[string_block_count + 1] byte offsets into STRINGS of
//!                     each compressed block. Empty when the pool is raw.
//!  2 ARTISTS          ArtistRec[artist_count], 20 B, ordered by MBID ascending.
//!  3 ARTIST_MBID      artist_count * 14 B: bytes 2..16 of the MBID. The top two
//!                     bytes are implied by the bucket (see ARTIST_MBID_HI), so a
//!                     lookup is bucket -> binary search, and the full MBID is
//!                     reconstructable from (bucket, record).
//!  4 ARTIST_MBID_HI   u32[65537] prefix offsets: bucket b (the MBID's first two
//!                     bytes big-endian) occupies [HI[b], HI[b+1]).
//!  5 CREDITS          u32[credit_count] STRINGS offsets of pre-rendered artist
//!                     credit display strings ("A feat. B"). Rendered at build
//!                     time because nothing reads the per-artist breakdown.
//!  6 RELEASE_GROUPS   ReleaseGroupRec[release_group_count], 16 B, MBID order.
//!  7 RG_MBID          as ARTIST_MBID.
//!  8 RG_MBID_HI       as ARTIST_MBID_HI.
//!  9 ARTIST_RGS       u32[] release-group indices, CSR values, already sorted
//!                     newest-first by first-release-date (the discography order
//!                     of MusicBrainzViewModel.kt:207) so the server sorts nothing.
//! 10 ARTIST_RGS_IDX   u32[artist_count + 1] prefix offsets into ARTIST_RGS.
//! 11 RELEASES         ReleaseRec[release_count], 24 B, MBID order.
//! 12 RELEASE_MBID     as ARTIST_MBID.
//! 13 RELEASE_MBID_HI  as ARTIST_MBID_HI.
//! 14 RG_RELEASES      u32[] release indices, CSR values, already sorted by
//!                     (status, date) per MusicBrainzViewModel.kt:238.
//! 15 RG_RELEASES_IDX  u32[release_group_count + 1] prefix offsets.
//! 16 MEDIA_IDX        u32[release_count + 1] prefix offsets into MEDIA.
//! 17 MEDIA            MediumRec[medium_count], 4 B. `position` is implicit: the
//!                     ordinal within the release's MEDIA range, +1, except where
//!                     the source positions are irregular, in which case the
//!                     medium's tracks carry it (see TRACKS bit 2 for tracks;
//!                     medium positions are renumbered densely, which is what the
//!                     UI shows as DISCNUMBER).
//! 18 TRACK_IDX        u32[medium_count + 1] **byte** offsets into TRACKS, so a
//!                     medium's tracks are one contiguous varint run. Byte, not
//!                     row, exactly as ROUTE_TRIPS is addressed in index.rs:50-54.
//! 19 TRACKS           varint stream, per track:
//!                       uvarint flags
//!                         bit 0  title differs from the recording's
//!                         bit 1  credit differs from the recording's
//!                         bit 2  position is not (ordinal + 1)
//!                         bit 3  length differs from the recording's
//!                       recording reference: the **first** track of a medium
//!                         carries `uvarint recording_idx` absolute; every later
//!                         track carries `zigzag varint d_recording` relative to
//!                         the previous track of the same medium. Absolute-per-
//!                         medium is what makes a medium decodable on its own,
//!                         which random access requires; it costs ~22 MB at full
//!                         scale and buys back the whole point of TRACK_IDX.
//!                       [bit 0] uvarint title_off    (STRINGS offset)
//!                       [bit 1] uvarint credit_idx   (CREDITS index)
//!                       [bit 2] uvarint position
//!                       [bit 3] uvarint length_secs
//!                     Overrides are INLINE rather than in the side tables the
//!                     design doc's §4.4 proposed: inline only widens the ~12% of
//!                     tracks that actually differ, costs ~46 MB less than
//!                     TRACK_TITLE_EXC + TRACK_CREDIT_EXC, and removes a binary
//!                     search from the hot path. Sections 20 and 21 are therefore
//!                     unused.
//! 20 (unused)         reserved; was TRACK_TITLE_EXC.
//! 21 (unused)         reserved; was TRACK_CREDIT_EXC.
//! 22 TRACK_MBID       track_count * 16 B raw MBIDs in TRACKS order. **Absent
//!                     (len 0) in tier B**, which is the whole of tier B: 57.4 M
//!                     x 16 B = 918 MB, the largest single section in the format.
//!                     flags bit 0 is clear when it is absent.
//! 23 RECORDINGS       RecordingRec[recording_count], 9 B, in the clustered order
//!                     described below.
//! 24 RECORDING_MBID   recording_count * 16 B raw MBIDs, **unsorted**, same order
//!                     as RECORDINGS. Deliberate: nothing looks a recording up by
//!                     MBID (the WS/2 `recording/<mbid>` endpoint is dead code
//!                     with no callers), so recordings are instead ordered by
//!                     first appearance in release -> medium -> track order. That
//!                     makes a tracklist's `d_recording` deltas mostly 1, which is
//!                     what keeps TRACKS at ~2 B/track. Sorting these MBIDs would
//!                     buy a 14 B truncation and cost more than it saves.
//! 25 REC_FIRST_RELEASE u32[recording_count] release index of the recording's
//!                     first appearance, or NONE. Only used to fill the
//!                     `releases[0]` field of a recording search hit.
//! 26 ISRCS            IsrcRec[isrc_count], 11 B, sorted by recording index:
//!                     u32 recording index + 7 B packed ISRC (50 bits: 2 letters,
//!                     3 base-36 chars, 7 digits -- the shape the schema's own
//!                     CHECK constraint enforces). Binary search by recording.
//! 27 (unused)         reserved; was ISRC_BLKIDX.
//! 28 SEARCH_TERMS     NUL-terminated normalised search terms, sorted ascending.
//! 29 SEARCH_POSTINGS  per term, a varint block: uvarint n, then n ascending
//!                     Δ-varint entity refs. A ref is `kind << 30 | index` with
//!                     kind 0 artist, 1 release group, 2 recording.
//! 30 SEARCH_TERM_IDX  TermIdxRec[search_term_count + 1], 8 B: byte offset into
//!                     SEARCH_TERMS and byte offset into SEARCH_POSTINGS. The
//!                     trailing sentinel gives the last term's extents.
//! 31 ENUM_POOL        u32[enum_count] STRINGS offsets for the small enumerations
//!                     (artist type, release-group primary/secondary type,
//!                     release status, medium format, country code). Records hold
//!                     a u16 index into this table instead of a 4 B pool offset,
//!                     which is worth ~56 MB across ARTISTS/RELEASE_GROUPS/
//!                     RELEASES/MEDIA at full scale.
//! ```
//!
//! # Reproducibility
//!
//! Byte-for-byte reproducible for a given mbdump: artist / release-group /
//! release order is MBID ascending, recording order is release -> medium -> track
//! first appearance with standalone recordings appended in source id order, the
//! search dictionary is sorted, and no hash-map iteration order reaches the
//! output.

use std::collections::HashMap;
use std::io::{self, Seek, SeekFrom, Write};

pub const MAGIC: u32 = 0x3150_424D; // "MBP1"
pub const VERSION: u32 = 1;
pub const SECTION_COUNT: usize = 32;
pub const HEADER_LEN: u64 = 64;
pub const NONE: u32 = 0xFFFF_FFFF;

pub const S_STRINGS: usize = 0;
pub const S_STRINGS_BLKIDX: usize = 1;
pub const S_ARTISTS: usize = 2;
pub const S_ARTIST_MBID: usize = 3;
pub const S_ARTIST_MBID_HI: usize = 4;
pub const S_CREDITS: usize = 5;
pub const S_RELEASE_GROUPS: usize = 6;
pub const S_RG_MBID: usize = 7;
pub const S_RG_MBID_HI: usize = 8;
pub const S_ARTIST_RGS: usize = 9;
pub const S_ARTIST_RGS_IDX: usize = 10;
pub const S_RELEASES: usize = 11;
pub const S_RELEASE_MBID: usize = 12;
pub const S_RELEASE_MBID_HI: usize = 13;
pub const S_RG_RELEASES: usize = 14;
pub const S_RG_RELEASES_IDX: usize = 15;
pub const S_MEDIA_IDX: usize = 16;
pub const S_MEDIA: usize = 17;
pub const S_TRACK_IDX: usize = 18;
pub const S_TRACKS: usize = 19;
pub const S_TRACK_MBID: usize = 22;
pub const S_RECORDINGS: usize = 23;
pub const S_RECORDING_MBID: usize = 24;
pub const S_REC_FIRST_RELEASE: usize = 25;
pub const S_ISRCS: usize = 26;
pub const S_SEARCH_TERMS: usize = 28;
pub const S_SEARCH_POSTINGS: usize = 29;
pub const S_SEARCH_TERM_IDX: usize = 30;
pub const S_ENUM_POOL: usize = 31;

pub const FLAG_TRACK_MBIDS: u32 = 1 << 0;
pub const FLAG_RECORDING_MBIDS: u32 = 1 << 1;
pub const FLAG_ISRCS: u32 = 1 << 2;
pub const FLAG_RECORDING_SEARCH: u32 = 1 << 3;
pub const FLAG_OFFICIAL_ONLY: u32 = 1 << 4;
pub const FLAG_STRINGS_COMPRESSED: u32 = 1 << 5;

/// Record widths, duplicated in the reader; the round-trip test pins them.
pub const ARTIST_REC_LEN: usize = 20;
pub const RG_REC_LEN: usize = 16;
pub const RELEASE_REC_LEN: usize = 24;
pub const MEDIUM_REC_LEN: usize = 4;
pub const RECORDING_REC_LEN: usize = 9;
pub const MBID_TRUNC_LEN: usize = 14;
pub const ISRC_REC_LEN: usize = 11;
pub const TERM_IDX_REC_LEN: usize = 8;

/// Search entity kinds, encoded in the top two bits of a posting.
pub const KIND_ARTIST: u32 = 0;
pub const KIND_RELEASE_GROUP: u32 = 1;
pub const KIND_RECORDING: u32 = 2;
pub const KIND_SHIFT: u32 = 30;
pub const IDX_MASK: u32 = (1 << KIND_SHIFT) - 1;

/// The largest entity index a search posting can name. Checked at build time
/// because silently truncating here is the modern version of the u32 stop-time
/// overflow recorded in `index.rs:15-27`.
pub const MAX_SEARCHABLE_IDX: u32 = IDX_MASK;

pub type Mbid = [u8; 16];

// --- varints (unsigned LEB128, as index.rs:106-117) ---

pub fn write_uvarint(out: &mut Vec<u8>, mut x: u64) {
    loop {
        let b = (x & 0x7f) as u8;
        x >>= 7;
        if x != 0 {
            out.push(b | 0x80);
        } else {
            out.push(b);
            break;
        }
    }
}

pub fn write_zigzag(out: &mut Vec<u8>, x: i64) {
    write_uvarint(out, ((x << 1) ^ (x >> 63)) as u64);
}

/// Decode a uvarint at `bytes[*pos]`, advancing `pos`. `None` on a truncated or
/// over-long encoding rather than a panic or a wrapped value.
pub fn read_uvarint(bytes: &[u8], pos: &mut usize) -> Option<u64> {
    let mut out: u64 = 0;
    let mut shift = 0;
    loop {
        let b = *bytes.get(*pos)?;
        *pos += 1;
        if shift >= 64 {
            return None;
        }
        out |= ((b & 0x7f) as u64) << shift;
        if b & 0x80 == 0 {
            return Some(out);
        }
        shift += 7;
    }
}

pub fn read_zigzag(bytes: &[u8], pos: &mut usize) -> Option<i64> {
    let u = read_uvarint(bytes, pos)?;
    Some(((u >> 1) as i64) ^ -((u & 1) as i64))
}

// --- dates ---

/// Pack a partial date into a u32 as `year<<9 | month<<5 | day`. 0 = unknown.
/// Out-of-range components are dropped rather than corrupting the neighbours.
pub fn pack_date(year: Option<i64>, month: Option<i64>, day: Option<i64>) -> u32 {
    let y = match year {
        Some(y) if (1..=9999).contains(&y) => y as u32,
        _ => return 0,
    };
    let m = match month {
        Some(m) if (1..=12).contains(&m) => m as u32,
        _ => 0,
    };
    let d = if m == 0 {
        0
    } else {
        match day {
            Some(d) if (1..=31).contains(&d) => d as u32,
            _ => 0,
        }
    };
    (y << 9) | (m << 5) | d
}

/// Ordering key for "which of these dates is earliest", with **unknown month and
/// day sorting LAST within their year**, and unknown dates sorting last overall.
///
/// The packed representation cannot be compared directly for this. `1973` packs
/// with month and day zero, so a raw `<` makes it earlier than `1973-03-24`, and
/// picking the minimum then loses the precise date. That is not a theoretical
/// concern: MusicBrainz gives *The Dark Side of the Moon* a first-release-date of
/// 1973-03-24, and deriving it with a raw comparison yielded a bare `1973` because
/// some other release of the group carries a year-only date. Verified against
/// musicbrainz.org/ws/2 for release group f5093c06-23e3-404f-aeaa-40f72885ee3a.
pub fn date_rank(packed: u32) -> u32 {
    if packed == 0 {
        return u32::MAX;
    }
    let y = packed >> 9;
    let m = (packed >> 5) & 0xf;
    let d = packed & 0x1f;
    let m = if m == 0 { 13 } else { m };
    let d = if d == 0 { 32 } else { d };
    (y << 11) | (m << 6) | d
}

// --- MBIDs ---

/// Parse an MBID.
///
/// This is the ONE canonical parser — `location_share_server` should call it
/// rather than writing a second one, because two parsers that disagree on case or
/// on brace forms would return "not found" for a perfectly valid MBID.
///
/// Accepts: canonical hyphenated, bare 32-hex, any case, optional surrounding
/// whitespace, and optional `{...}` or `urn:uuid:` wrappers, all of which appear
/// in the wild. Rejects anything that is not exactly 32 hex digits once the
/// permitted decoration is removed.
pub fn parse_mbid(s: &str) -> Option<Mbid> {
    let mut t = s.trim();
    if let Some(rest) = t.strip_prefix('{') {
        t = rest.strip_suffix('}')?;
    }
    for prefix in ["urn:uuid:", "URN:UUID:", "urn:UUID:"] {
        if let Some(rest) = t.strip_prefix(prefix) {
            t = rest;
            break;
        }
    }
    let mut out = [0u8; 16];
    let mut nibble = 0usize;
    for c in t.chars() {
        if c == '-' {
            continue;
        }
        let v = match c {
            '0'..='9' => c as u8 - b'0',
            'a'..='f' => c as u8 - b'a' + 10,
            'A'..='F' => c as u8 - b'A' + 10,
            _ => return None,
        };
        if nibble >= 32 {
            return None;
        }
        if nibble.is_multiple_of(2) {
            out[nibble / 2] = v << 4;
        } else {
            out[nibble / 2] |= v;
        }
        nibble += 1;
    }
    if nibble == 32 {
        Some(out)
    } else {
        None
    }
}

pub fn format_mbid(m: &Mbid) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(36);
    for (i, b) in m.iter().enumerate() {
        if i == 4 || i == 6 || i == 8 || i == 10 {
            s.push('-');
        }
        s.push(HEX[(b >> 4) as usize] as char);
        s.push(HEX[(b & 0xf) as usize] as char);
    }
    s
}

// --- ISRC packing (2 letters, 3 base-36, 7 digits = 50 bits -> 7 bytes) ---

/// Pack a 12-character ISRC into 7 bytes. `None` for anything that does not
/// match the shape the MusicBrainz schema's own CHECK constraint enforces,
/// `^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$`; callers count and report rejects rather than
/// writing a corrupt record.
pub fn pack_isrc(s: &[u8]) -> Option<[u8; 7]> {
    if s.len() != 12 {
        return None;
    }
    let mut v: u64 = 0;
    for &c in &s[0..2] {
        if !c.is_ascii_uppercase() {
            return None;
        }
        v = v * 26 + (c - b'A') as u64;
    }
    for &c in &s[2..5] {
        let d = match c {
            b'0'..=b'9' => (c - b'0') as u64,
            b'A'..=b'Z' => (c - b'A') as u64 + 10,
            _ => return None,
        };
        v = v * 36 + d;
    }
    let mut n: u64 = 0;
    for &c in &s[5..12] {
        if !c.is_ascii_digit() {
            return None;
        }
        n = n * 10 + (c - b'0') as u64;
    }
    // 26*26 = 676 (10 bits), 36^3 = 46656 (16 bits), 10^7 (24 bits) = 50 bits.
    let packed = (v << 24) | n;
    let mut out = [0u8; 7];
    for (i, o) in out.iter_mut().enumerate() {
        *o = (packed >> (8 * i)) as u8;
    }
    Some(out)
}

pub fn unpack_isrc(b: &[u8; 7]) -> [u8; 12] {
    let mut packed: u64 = 0;
    for (i, &x) in b.iter().enumerate() {
        packed |= (x as u64) << (8 * i);
    }
    let n = packed & 0xFF_FFFF;
    let mut v = packed >> 24;
    let mut out = [b'0'; 12];
    let mut digits = n;
    for i in (5..12).rev() {
        out[i] = b'0' + (digits % 10) as u8;
        digits /= 10;
    }
    for i in (2..5).rev() {
        let d = (v % 36) as u8;
        out[i] = if d < 10 { b'0' + d } else { b'A' + d - 10 };
        v /= 36;
    }
    for i in (0..2).rev() {
        out[i] = b'A' + (v % 26) as u8;
        v /= 26;
    }
    out
}

// --- string pool ---

/// Interning pool.
///
/// `intern` returns a dense **symbol id**, not a byte offset. Byte offsets are
/// only assigned by [`StringPool::finalize`], which first sorts every distinct
/// string alphabetically. That deferral is what makes the compressed pool worth
/// having: zstd over 64 KB blocks of *sorted* short text gets ~3.5x, where the
/// same text in interning order gets ~2.5-3x, and the offsets cannot be reordered
/// after the fact because they are baked into the inline overrides of the TRACKS
/// varint stream, where changing an offset changes the varint's width.
///
/// The index is open-addressed over u32 symbol ids rather than a
/// `HashMap<String, u32>`: at full scale there are ~23 M distinct strings, and a
/// `HashMap<String, u32>` costs ~1.84 GB steady with a ~2.3 GB transient spike at
/// its final resize, against a 4 GB budget for the whole build. This costs 4 bytes
/// per slot.
pub struct StringPool {
    /// Staging bytes in interning order, each string NUL-terminated.
    bytes: Vec<u8>,
    /// Symbol id -> start offset in `bytes`.
    starts: Vec<u32>,
    /// Open-addressed index; slot values are `sym + 1`, 0 means empty.
    slots: Vec<u32>,
}

/// A dense string id handed out during the build. `SYM_EMPTY` is the empty string.
pub type Sym = u32;
pub const SYM_EMPTY: Sym = 0;

impl Default for StringPool {
    fn default() -> Self {
        Self::new()
    }
}

impl StringPool {
    pub fn new() -> StringPool {
        // Symbol 0 is the empty string, so a missing value needs no sentinel.
        StringPool { bytes: vec![0], starts: vec![0], slots: vec![0; 1024] }
    }

    pub fn distinct(&self) -> usize {
        self.starts.len()
    }

    pub fn staging_bytes(&self) -> usize {
        self.bytes.len()
    }

    fn hash(s: &[u8]) -> u64 {
        // FNV-1a: cheap, no dependency, good enough for short titles.
        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        for &b in s {
            h ^= b as u64;
            h = h.wrapping_mul(0x0000_0100_0000_01b3);
        }
        h
    }

    /// The bytes of a symbol, without its NUL.
    pub fn get(&self, sym: Sym) -> &[u8] {
        let start = self.starts[sym as usize] as usize;
        let end = self.bytes[start..].iter().position(|&b| b == 0).unwrap() + start;
        &self.bytes[start..end]
    }

    fn grow(&mut self) {
        let mut slots = vec![0u32; self.slots.len() * 2];
        let mask = slots.len() - 1;
        for i in 0..self.slots.len() {
            let v = self.slots[i];
            if v == 0 {
                continue;
            }
            let mut j = (Self::hash(self.get(v - 1)) as usize) & mask;
            while slots[j] != 0 {
                j = (j + 1) & mask;
            }
            slots[j] = v;
        }
        self.slots = slots;
    }

    pub fn intern(&mut self, s: &str) -> Sym {
        if s.is_empty() {
            return SYM_EMPTY;
        }
        if self.starts.len() * 4 >= self.slots.len() * 3 {
            self.grow();
        }
        let mask = self.slots.len() - 1;
        let mut j = (Self::hash(s.as_bytes()) as usize) & mask;
        loop {
            let v = self.slots[j];
            if v == 0 {
                let off = self.bytes.len();
                assert!(
                    off + s.len() < u32::MAX as usize,
                    "the string pool passed the u32 offset ceiling at {off} bytes; it is the \
                     first field in this format that would overflow (design doc §4.7 / R8)"
                );
                self.bytes.extend_from_slice(s.as_bytes());
                self.bytes.push(0);
                let sym = self.starts.len() as u32;
                self.starts.push(off as u32);
                self.slots[j] = sym + 1;
                return sym;
            }
            if self.get(v - 1) == s.as_bytes() {
                return v - 1;
            }
            j = (j + 1) & mask;
        }
    }

    /// Look a string up without interning it.
    pub fn lookup(&self, s: &str) -> Option<Sym> {
        if s.is_empty() {
            return Some(SYM_EMPTY);
        }
        let mask = self.slots.len() - 1;
        let mut j = (Self::hash(s.as_bytes()) as usize) & mask;
        loop {
            let v = self.slots[j];
            if v == 0 {
                return None;
            }
            if self.get(v - 1) == s.as_bytes() {
                return Some(v - 1);
            }
            j = (j + 1) & mask;
        }
    }

    /// Symbols in alphabetical order, without consuming the pool. Index into the
    /// result is a symbol's rank.
    pub fn sorted_order(&self) -> Vec<Sym> {
        let mut order: Vec<Sym> = (0..self.starts.len() as u32).collect();
        let bytes = &self.bytes;
        let starts = &self.starts;
        let key = |sym: &u32| -> &[u8] {
            let start = starts[*sym as usize] as usize;
            let end = bytes[start..].iter().position(|&b| b == 0).unwrap() + start;
            &bytes[start..end]
        };
        order.sort_unstable_by(|a, b| key(a).cmp(key(b)));
        order
    }

    /// Sort the distinct strings alphabetically and assign final byte offsets.
    ///
    /// The sorted bytes are deliberately **not** materialised — that would need a
    /// second 635 MB buffer. Offsets are computed from the sorted order, and
    /// [`FinalPool::write_strings`] later streams the strings out of the staging
    /// buffer in that order.
    pub fn finalize(mut self) -> FinalPool {
        // The index is dead weight from here on.
        self.slots = Vec::new();
        self.slots.shrink_to_fit();
        let n = self.starts.len();
        let mut order: Vec<u32> = (0..n as u32).collect();
        {
            let bytes = &self.bytes;
            let starts = &self.starts;
            let key = |sym: &u32| -> &[u8] {
                let start = starts[*sym as usize] as usize;
                let end = bytes[start..].iter().position(|&b| b == 0).unwrap() + start;
                &bytes[start..end]
            };
            order.sort_unstable_by(|a, b| key(a).cmp(key(b)));
        }
        let mut offsets = vec![0u32; n];
        let mut off: u64 = 0;
        for &sym in &order {
            offsets[sym as usize] = off as u32;
            let len = {
                let start = self.starts[sym as usize] as usize;
                self.bytes[start..].iter().position(|&b| b == 0).unwrap()
            };
            off += len as u64 + 1;
        }
        assert!(
            off <= u32::MAX as u64,
            "the string pool is {off} bytes, past the u32 offset ceiling (design doc R8)"
        );
        FinalPool { bytes: self.bytes, starts: self.starts, order, offsets, total: off as u32 }
    }
}

/// A finalised pool: symbols resolve to byte offsets into the alphabetically
/// sorted pool that `write_strings` emits.
pub struct FinalPool {
    bytes: Vec<u8>,
    starts: Vec<u32>,
    order: Vec<u32>,
    offsets: Vec<u32>,
    total: u32,
}

impl FinalPool {
    pub fn offset(&self, sym: Sym) -> u32 {
        self.offsets[sym as usize]
    }

    pub fn get(&self, sym: Sym) -> &[u8] {
        let start = self.starts[sym as usize] as usize;
        let end = self.bytes[start..].iter().position(|&b| b == 0).unwrap() + start;
        &self.bytes[start..end]
    }

    pub fn distinct(&self) -> usize {
        self.offsets.len()
    }

    /// Uncompressed size of the pool.
    pub fn raw_len(&self) -> u32 {
        self.total
    }

    /// Stream the pool in sorted order through `sink`, one string (with its NUL)
    /// at a time. Never builds a second copy of the pool.
    pub fn write_strings<F: FnMut(&[u8]) -> io::Result<()>>(&self, mut sink: F) -> io::Result<()> {
        for &sym in &self.order {
            let start = self.starts[sym as usize] as usize;
            let end = self.bytes[start..].iter().position(|&b| b == 0).unwrap() + start;
            sink(&self.bytes[start..=end])?;
        }
        Ok(())
    }
}

/// Uncompressed span covered by one block of the compressed string pool. A pool
/// offset's block is `off / STRING_BLOCK_SIZE`, so the mapping needs no lookup.
pub const STRING_BLOCK_SIZE: usize = 64 * 1024;

/// zstd level for the pool. The pack is built once and read forever, and the
/// user's stated goal is the smallest possible file, so this is deliberately at
/// the expensive end.
pub const STRING_ZSTD_LEVEL: i32 = 19;

/// Compresses the string pool into `STRING_BLOCK_SIZE` blocks and records where
/// each block landed.
///
/// Blocks break at exact multiples of `STRING_BLOCK_SIZE`, so a string may
/// straddle two blocks; the reader continues into the next block when it does not
/// find the terminating NUL. Keeping the boundaries exact is what lets a pool
/// offset name its block by division instead of a binary search.
pub struct StringBlockWriter {
    pending: Vec<u8>,
    out: Vec<u8>,
    block_offsets: Vec<u32>,
}

impl Default for StringBlockWriter {
    fn default() -> Self {
        Self::new()
    }
}

impl StringBlockWriter {
    pub fn new() -> StringBlockWriter {
        StringBlockWriter {
            pending: Vec::with_capacity(STRING_BLOCK_SIZE * 2),
            out: Vec::new(),
            block_offsets: vec![0],
        }
    }

    pub fn push(&mut self, bytes: &[u8]) -> io::Result<()> {
        self.pending.extend_from_slice(bytes);
        while self.pending.len() >= STRING_BLOCK_SIZE {
            self.flush_block(STRING_BLOCK_SIZE)?;
        }
        Ok(())
    }

    fn flush_block(&mut self, len: usize) -> io::Result<()> {
        let block = zstd::bulk::compress(&self.pending[..len], STRING_ZSTD_LEVEL)?;
        self.out.extend_from_slice(&block);
        assert!(
            self.out.len() <= u32::MAX as usize,
            "the compressed string pool passed the u32 block-offset ceiling"
        );
        self.block_offsets.push(self.out.len() as u32);
        self.pending.drain(..len);
        Ok(())
    }

    /// Returns the compressed bytes and the block offset index.
    pub fn finish(mut self) -> io::Result<(Vec<u8>, Vec<u32>)> {
        if !self.pending.is_empty() {
            let len = self.pending.len();
            self.flush_block(len)?;
        }
        Ok((self.out, self.block_offsets))
    }
}

/// The small enumerations (artist type, RG types, release status, medium format,
/// country code) share one table so records can hold a u16 index instead of a 4 B
/// pool offset, which is worth ~56 MB across ARTISTS / RELEASE_GROUPS / RELEASES /
/// MEDIA at full scale.
pub struct EnumPool {
    syms: Vec<Sym>,
    map: HashMap<String, u16>,
}

impl Default for EnumPool {
    fn default() -> Self {
        Self::new()
    }
}

impl EnumPool {
    pub fn new() -> EnumPool {
        EnumPool { syms: Vec::new(), map: HashMap::new() }
    }

    /// `None` is represented by `NONE_ENUM`, which resolves to the empty string.
    pub const NONE_ENUM: u16 = u16::MAX;

    pub fn intern(&mut self, pool: &mut StringPool, s: &str) -> u16 {
        if s.is_empty() {
            return Self::NONE_ENUM;
        }
        if let Some(&i) = self.map.get(s) {
            return i;
        }
        assert!(
            self.syms.len() < u16::MAX as usize,
            "ENUM_POOL overflowed 65535 entries; a genuinely large vocabulary does not \
             belong in this table"
        );
        let i = self.syms.len() as u16;
        let sym = pool.intern(s);
        self.syms.push(sym);
        self.map.insert(s.to_string(), i);
        i
    }

    pub fn syms(&self) -> &[Sym] {
        &self.syms
    }
}

// --- section writer ---

/// Streams sections to the pack file, patching the directory at the end.
///
/// Sections are streamed rather than assembled into one `Vec<u8>` because at full
/// scale the sections total more than 2 GB; buffering them all is the memory
/// failure this format is designed around.
pub struct PackWriter<W: Write + Seek> {
    w: W,
    dir: Vec<(u64, u64)>,
    pos: u64,
    open: Option<(usize, u64)>,
    header: [u8; HEADER_LEN as usize],
}

impl<W: Write + Seek> PackWriter<W> {
    pub fn new(mut w: W) -> io::Result<PackWriter<W>> {
        let dir_len = (SECTION_COUNT * 16) as u64;
        let zeros = vec![0u8; (HEADER_LEN + dir_len) as usize];
        w.write_all(&zeros)?;
        Ok(PackWriter {
            w,
            dir: vec![(0, 0); SECTION_COUNT],
            pos: HEADER_LEN + dir_len,
            open: None,
            header: [0u8; HEADER_LEN as usize],
        })
    }

    fn align(&mut self) -> io::Result<()> {
        let pad = (8 - (self.pos % 8)) % 8;
        if pad != 0 {
            self.w.write_all(&[0u8; 8][..pad as usize])?;
            self.pos += pad;
        }
        Ok(())
    }

    pub fn begin(&mut self, index: usize) -> io::Result<()> {
        assert!(self.open.is_none(), "section {index} begun while another is open");
        assert!(index < SECTION_COUNT, "section index {index} out of range");
        assert!(self.dir[index] == (0, 0), "section {index} written twice");
        self.align()?;
        self.open = Some((index, self.pos));
        Ok(())
    }

    pub fn write(&mut self, bytes: &[u8]) -> io::Result<()> {
        assert!(self.open.is_some(), "write with no section open");
        self.w.write_all(bytes)?;
        self.pos += bytes.len() as u64;
        Ok(())
    }

    pub fn end(&mut self) -> io::Result<u64> {
        let (index, start) = self.open.take().expect("end with no section open");
        let len = self.pos - start;
        self.dir[index] = (start, len);
        Ok(len)
    }

    /// Write a whole section from one buffer.
    pub fn section(&mut self, index: usize, bytes: &[u8]) -> io::Result<u64> {
        self.begin(index)?;
        self.write(bytes)?;
        self.end()
    }

    pub fn section_u32(&mut self, index: usize, vals: &[u32]) -> io::Result<u64> {
        self.begin(index)?;
        // Chunked so a 65 M-entry index does not need a second full-size buffer.
        let mut buf = Vec::with_capacity(4 * 4096);
        for chunk in vals.chunks(4096) {
            buf.clear();
            for v in chunk {
                buf.extend_from_slice(&v.to_le_bytes());
            }
            self.write(&buf)?;
        }
        self.end()
    }

    #[allow(clippy::too_many_arguments)]
    pub fn set_header(
        &mut self,
        counts: &HeaderCounts,
        flags: u32,
        dump_date: u32,
        string_block_count: u32,
    ) {
        let mut h = [0u8; HEADER_LEN as usize];
        let mut put = |off: usize, v: u32| h[off..off + 4].copy_from_slice(&v.to_le_bytes());
        put(0x00, MAGIC);
        put(0x04, VERSION);
        put(0x08, SECTION_COUNT as u32);
        put(0x0C, counts.artists);
        put(0x10, counts.credits);
        put(0x14, counts.release_groups);
        put(0x18, counts.releases);
        put(0x1C, counts.media);
        put(0x20, counts.tracks);
        put(0x24, counts.recordings);
        put(0x28, counts.isrcs);
        put(0x2C, counts.search_terms);
        put(0x30, counts.enums);
        put(0x34, flags);
        put(0x38, dump_date);
        put(0x3C, string_block_count);
        self.header = h;
    }

    /// Patch the header and directory. Returns the total file length.
    pub fn finish(mut self) -> io::Result<u64> {
        assert!(self.open.is_none(), "finish with a section still open");
        let total = self.pos;
        self.w.seek(SeekFrom::Start(0))?;
        self.w.write_all(&self.header)?;
        let mut dir = Vec::with_capacity(SECTION_COUNT * 16);
        for &(off, len) in &self.dir {
            dir.extend_from_slice(&off.to_le_bytes());
            dir.extend_from_slice(&len.to_le_bytes());
        }
        self.w.write_all(&dir)?;
        self.w.flush()?;
        Ok(total)
    }

    pub fn section_sizes(&self) -> Vec<(usize, u64)> {
        self.dir.iter().enumerate().map(|(i, &(_, len))| (i, len)).collect()
    }
}

#[derive(Default, Clone, Copy)]
pub struct HeaderCounts {
    pub artists: u32,
    pub credits: u32,
    pub release_groups: u32,
    pub releases: u32,
    pub media: u32,
    pub tracks: u32,
    pub recordings: u32,
    pub isrcs: u32,
    pub search_terms: u32,
    pub enums: u32,
}

/// Human-readable names, only for the build report.
pub const SECTION_NAMES: [&str; SECTION_COUNT] = [
    "STRINGS",
    "STRINGS_BLKIDX",
    "ARTISTS",
    "ARTIST_MBID",
    "ARTIST_MBID_HI",
    "CREDITS",
    "RELEASE_GROUPS",
    "RG_MBID",
    "RG_MBID_HI",
    "ARTIST_RGS",
    "ARTIST_RGS_IDX",
    "RELEASES",
    "RELEASE_MBID",
    "RELEASE_MBID_HI",
    "RG_RELEASES",
    "RG_RELEASES_IDX",
    "MEDIA_IDX",
    "MEDIA",
    "TRACK_IDX",
    "TRACKS",
    "(unused 20)",
    "(unused 21)",
    "TRACK_MBID",
    "RECORDINGS",
    "RECORDING_MBID",
    "REC_FIRST_RELEASE",
    "ISRCS",
    "(unused 27)",
    "SEARCH_TERMS",
    "SEARCH_POSTINGS",
    "SEARCH_TERM_IDX",
    "ENUM_POOL",
];

/// Build the `{bucket -> [start, end)}` prefix table for a sorted MBID list and
/// the truncated 14-byte records that go with it.
pub fn build_mbid_table(sorted: &[Mbid]) -> (Vec<u8>, Vec<u32>) {
    let mut recs = Vec::with_capacity(sorted.len() * MBID_TRUNC_LEN);
    let mut hi = vec![0u32; 65537];
    for m in sorted {
        recs.extend_from_slice(&m[2..]);
        let bucket = ((m[0] as usize) << 8) | m[1] as usize;
        hi[bucket + 1] += 1;
    }
    for i in 0..65536 {
        hi[i + 1] += hi[i];
    }
    (recs, hi)
}

/// Normalise a string for the search index: lowercase, strip accents on the
/// Latin-1 range, and split on anything that is not alphanumeric.
///
/// Deliberately not a full Unicode normaliser: this crate has no dependencies,
/// and the app's current behaviour is Lucene's, which we cannot reproduce
/// anyway (design doc R7). CJK text yields one term per run of ideographs, which
/// makes it prefix-searchable as a whole title but not per character.
pub fn search_terms(s: &str, out: &mut Vec<String>) {
    let mut cur = String::new();
    for ch in s.chars() {
        let c = fold_char(ch);
        if c.is_alphanumeric() {
            for l in c.to_lowercase() {
                cur.push(l);
            }
        } else if !cur.is_empty() {
            out.push(std::mem::take(&mut cur));
        }
    }
    if !cur.is_empty() {
        out.push(cur);
    }
}

fn fold_char(c: char) -> char {
    match c {
        'à'..='å' | 'À'..='Å' => 'a',
        'è'..='ë' | 'È'..='Ë' => 'e',
        'ì'..='ï' | 'Ì'..='Ï' => 'i',
        'ò'..='ö' | 'Ò'..='Ö' => 'o',
        'ù'..='ü' | 'Ù'..='Ü' => 'u',
        'ç' | 'Ç' => 'c',
        'ñ' | 'Ñ' => 'n',
        'ý' | 'ÿ' | 'Ý' => 'y',
        'ø' | 'Ø' => 'o',
        'æ' | 'Æ' => 'a',
        'ß' => 's',
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uvarint_round_trips() {
        for v in [0u64, 1, 127, 128, 300, 16383, 16384, u32::MAX as u64, u64::MAX] {
            let mut buf = Vec::new();
            write_uvarint(&mut buf, v);
            let mut pos = 0;
            assert_eq!(read_uvarint(&buf, &mut pos), Some(v), "value {v}");
            assert_eq!(pos, buf.len());
        }
    }

    #[test]
    fn zigzag_round_trips() {
        for v in [0i64, 1, -1, 63, -64, 1000, -1000, i32::MIN as i64, i32::MAX as i64] {
            let mut buf = Vec::new();
            write_zigzag(&mut buf, v);
            let mut pos = 0;
            assert_eq!(read_zigzag(&buf, &mut pos), Some(v), "value {v}");
        }
    }

    #[test]
    fn truncated_uvarint_is_none_not_panic() {
        assert_eq!(read_uvarint(&[0x80], &mut 0), None);
        assert_eq!(read_uvarint(&[], &mut 0), None);
    }

    #[test]
    fn mbid_round_trips() {
        let s = "f27ec8db-af05-4f36-916e-3d57f91ecf5e";
        let m = parse_mbid(s).unwrap();
        assert_eq!(format_mbid(&m), s);
        assert_eq!(parse_mbid("F27EC8DB-AF05-4F36-916E-3D57F91ECF5E"), Some(m));
        assert_eq!(parse_mbid("f27ec8dbaf054f36916e3d57f91ecf5e"), Some(m));
        assert_eq!(parse_mbid("not-a-mbid"), None);
        assert_eq!(parse_mbid(""), None);
        assert_eq!(parse_mbid("f27ec8db-af05-4f36-916e-3d57f91ecf5e-extra"), None);
    }

    #[test]
    fn mbid_parses_the_forms_that_turn_up_in_the_wild() {
        // One canonical parser, so the server cannot disagree with the pack about
        // whether a valid MBID exists.
        let m = parse_mbid("f27ec8db-af05-4f36-916e-3d57f91ecf5e").unwrap();
        for variant in [
            "  f27ec8db-af05-4f36-916e-3d57f91ecf5e  ",
            "{f27ec8db-af05-4f36-916e-3d57f91ecf5e}",
            "urn:uuid:f27ec8db-af05-4f36-916e-3d57f91ecf5e",
            "URN:UUID:F27EC8DB-AF05-4F36-916E-3D57F91ECF5E",
            "F27ec8Db-aF05-4f36-916E-3d57F91EcF5e",
        ] {
            assert_eq!(parse_mbid(variant), Some(m), "should accept {variant:?}");
        }
        for bad in [
            "{f27ec8db-af05-4f36-916e-3d57f91ecf5e",
            "f27ec8db-af05-4f36-916e-3d57f91ecf5",
            "g27ec8db-af05-4f36-916e-3d57f91ecf5e",
            "f27ec8db af05 4f36 916e 3d57f91ecf5e",
            "urn:uuid:",
        ] {
            assert_eq!(parse_mbid(bad), None, "should reject {bad:?}");
        }
    }

    #[test]
    fn isrc_round_trips() {
        for s in ["GBAYE0601498", "USRC17607839", "ZZZ990000000", "AAA000000000"] {
            let p = pack_isrc(s.as_bytes()).unwrap_or_else(|| panic!("pack {s}"));
            assert_eq!(&unpack_isrc(&p), s.as_bytes(), "isrc {s}");
        }
        assert_eq!(pack_isrc(b"TOOSHORT"), None);
        assert_eq!(pack_isrc(b"gbaye0601498"), None, "lowercase must be rejected");
        assert_eq!(pack_isrc(b"GBAYE060149X"), None, "non-digit designation");
    }

    #[test]
    fn string_pool_dedups_and_sorts_at_finalize() {
        let mut p = StringPool::new();
        assert_eq!(p.intern(""), SYM_EMPTY);
        let a = p.intern("Pink Floyd");
        let b = p.intern("Pink Floyd");
        assert_eq!(a, b);
        assert_ne!(a, p.intern("Pink Floyd "));
        let zebra = p.intern("zebra");
        let apple = p.intern("apple");
        // Force several table growths.
        for i in 0..5000 {
            p.intern(&format!("title {i}"));
        }
        let again = p.intern("title 4999");
        assert_eq!(p.distinct(), 5005, "empty + 2 floyds + zebra + apple + 5000 titles");

        let f = p.finalize();
        assert_eq!(f.offset(SYM_EMPTY), 0, "the empty string must stay at offset 0");
        assert_eq!(f.get(a), b"Pink Floyd");
        assert_eq!(f.get(again), b"title 4999");
        assert!(
            f.offset(apple) < f.offset(zebra),
            "finalize must sort alphabetically, or the compressed pool loses its ratio"
        );

        // The streamed pool is NUL-terminated, sorted, and exactly raw_len bytes.
        let mut out = Vec::new();
        f.write_strings(|s| {
            out.extend_from_slice(s);
            Ok(())
        })
        .unwrap();
        assert_eq!(out.len(), f.raw_len() as usize);
        assert_eq!(out[0], 0, "offset 0 is the empty string's terminator");
        let at = |off: u32| {
            let s = off as usize;
            let e = out[s..].iter().position(|&b| b == 0).unwrap() + s;
            String::from_utf8(out[s..e].to_vec()).unwrap()
        };
        assert_eq!(at(f.offset(a)), "Pink Floyd");
        assert_eq!(at(f.offset(zebra)), "zebra");
        assert_eq!(at(f.offset(again)), "title 4999");
    }

    #[test]
    fn string_blocks_round_trip_including_a_straddling_string() {
        let mut p = StringPool::new();
        // Enough text to cross several 64 KiB block boundaries, plus one string long
        // enough that it cannot sit inside a single block.
        let mut syms = Vec::new();
        for i in 0..20_000 {
            syms.push(p.intern(&format!("recording title number {i}")));
        }
        let huge = "x".repeat(STRING_BLOCK_SIZE + 1234);
        let huge_sym = p.intern(&huge);
        let f = p.finalize();
        let mut w = StringBlockWriter::new();
        f.write_strings(|s| w.push(s)).unwrap();
        let (compressed, blocks) = w.finish().unwrap();
        assert!(blocks.len() >= 3, "expected several blocks, got {}", blocks.len());
        assert_eq!(*blocks.last().unwrap() as usize, compressed.len());
        assert!(
            compressed.len() < f.raw_len() as usize,
            "compression must actually shrink the pool"
        );

        // Decode exactly as the reader does: locate the block by division, then
        // continue into later blocks until the NUL is found.
        let read = |off: u32| -> String {
            let mut block = off as usize / STRING_BLOCK_SIZE;
            let mut pos = off as usize % STRING_BLOCK_SIZE;
            let mut out: Vec<u8> = Vec::new();
            loop {
                let (a, b) = (blocks[block] as usize, blocks[block + 1] as usize);
                let raw =
                    zstd::bulk::decompress(&compressed[a..b], STRING_BLOCK_SIZE).unwrap();
                let slice = &raw[pos.min(raw.len())..];
                match slice.iter().position(|&c| c == 0) {
                    Some(n) => {
                        out.extend_from_slice(&slice[..n]);
                        break;
                    }
                    None => {
                        out.extend_from_slice(slice);
                        block += 1;
                        pos = 0;
                        if block + 1 >= blocks.len() {
                            break;
                        }
                    }
                }
            }
            String::from_utf8(out).unwrap()
        };
        assert_eq!(read(f.offset(syms[0])), "recording title number 0");
        assert_eq!(read(f.offset(syms[19_999])), "recording title number 19999");
        assert_eq!(read(f.offset(huge_sym)), huge, "a string spanning blocks must survive");
    }

    #[test]
    fn date_rank_sorts_partial_dates_last_within_their_year() {
        let y = pack_date(Some(1973), None, None);
        let ym = pack_date(Some(1973), Some(3), None);
        let ymd = pack_date(Some(1973), Some(3), Some(24));
        let earlier = pack_date(Some(1973), Some(1), Some(1));
        let unknown = 0u32;
        // The precise date must win the minimum against a bare year in the same
        // year -- the raw packed comparison gets this backwards and cost us the
        // real first-release-date of The Dark Side of the Moon.
        assert!(date_rank(ymd) < date_rank(y), "1973-03-24 must beat 1973");
        assert!(date_rank(ym) < date_rank(y), "1973-03 must beat 1973");
        assert!(date_rank(ymd) < date_rank(ym), "1973-03-24 must beat 1973-03");
        assert!(date_rank(earlier) < date_rank(ymd), "January still beats March");
        assert!(date_rank(y) < date_rank(unknown), "a known year beats no date");
        // Across years the ordinary ordering still holds.
        assert!(date_rank(pack_date(Some(1972), None, None)) < date_rank(ymd));
        // And the raw packed comparison really is wrong, so the test is meaningful.
        assert!(y < ymd, "raw packed order is the trap this function exists to avoid");
    }

    #[test]
    fn dates_pack() {
        assert_eq!(pack_date(None, None, None), 0);
        let d = pack_date(Some(1973), Some(3), Some(1));
        assert_eq!(d >> 9, 1973);
        assert_eq!((d >> 5) & 0xf, 3);
        assert_eq!(d & 0x1f, 1);
        // A day without a month is meaningless; drop it rather than store a lie.
        assert_eq!(pack_date(Some(1973), None, Some(4)) & 0x1f, 0);
        assert_eq!(pack_date(Some(1973), Some(13), None) >> 5 & 0xf, 0);
    }

    #[test]
    fn search_terms_split_and_fold() {
        let mut t = Vec::new();
        search_terms("The Dark Side of the Moon", &mut t);
        assert_eq!(t, ["the", "dark", "side", "of", "the", "moon"]);
        t.clear();
        search_terms("Björk — Homogénic (Remastered)", &mut t);
        assert_eq!(t, ["bjork", "homogenic", "remastered"]);
        t.clear();
        search_terms("!!!", &mut t);
        assert!(t.is_empty());
    }

    #[test]
    fn mbid_buckets_are_prefix_sums() {
        let a = parse_mbid("0000ffff-0000-0000-0000-000000000001").unwrap();
        let b = parse_mbid("0000ffff-0000-0000-0000-000000000002").unwrap();
        let c = parse_mbid("ffff0000-0000-0000-0000-000000000003").unwrap();
        let (recs, hi) = build_mbid_table(&[a, b, c]);
        assert_eq!(recs.len(), 3 * MBID_TRUNC_LEN);
        assert_eq!(hi[0], 0);
        assert_eq!(hi[1] - hi[0], 2, "both 0x0000-prefixed MBIDs land in bucket 0");
        assert_eq!(hi[0xffff + 1] - hi[0xffff], 1);
        assert_eq!(hi[65536], 3);
    }
}
