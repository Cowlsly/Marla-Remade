//! Read an offline MusicBrainz pack ("MBP1").
//!
//! THIS LAYOUT MUST STAY IN SYNC WITH
//! `scripts/musicbrainz/mb_ingest/src/pack.rs`, which is the producer and the
//! authoritative spec. Every constant here is duplicated there and pinned by the
//! round-trip test in `tests/round_trip.rs`.
//!
//! Written to be copied into / path-depended on by `location_share_server`:
//!
//!   * it has **no dependencies** and does not own the mapping. `MbPack::open`
//!     takes a `&[u8]`, so the caller keeps using memmap2 exactly as it does for
//!     `traffic_edges.bin` (`main.rs:391-405`, `state.rs:26-28`) and hands the
//!     slice in. `open` only validates the header and directory, so calling it
//!     per request is cheap and avoids a self-referential `AppState` field.
//!   * every accessor is bounds-checked and returns `Option`/empty rather than
//!     panicking, in the style of `openlr/graph.rs:371-390`. It deliberately does
//!     *not* copy `handlers/maps/traffic.rs:285-292`, where `mmap.len() - 259200`
//!     underflows on a truncated file.
//!   * strings come back as `Cow<'a, str>`. Today the pool is uncompressed so
//!     they are always borrowed and free; the type is `Cow` so that turning on the
//!     compressed pool later does not break callers.

use std::borrow::Cow;
use std::collections::HashMap;
use std::fmt;

use crate::pack::{
    self, read_uvarint, read_zigzag, Mbid, IDX_MASK, KIND_ARTIST, KIND_RECORDING,
    KIND_RELEASE_GROUP, KIND_SHIFT, MAGIC, SECTION_COUNT, VERSION,
};

// --- errors ---

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PackError {
    TooSmall { len: usize },
    BadMagic { found: u32 },
    BadVersion { found: u32, expected: u32 },
    BadSectionCount { found: u32, expected: u32 },
    SectionOutOfBounds { index: usize, offset: u64, len: u64, file_len: u64 },
    SectionMisaligned { index: usize, offset: u64 },
    SectionTruncated { index: usize, need: u64, have: u64 },
    UnsupportedPoolCodec,
}

impl fmt::Display for PackError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PackError::TooSmall { len } => {
                write!(f, "pack is {len} bytes, too small to hold a header and directory")
            }
            PackError::BadMagic { found } => {
                write!(f, "bad magic {found:#010x}, expected {MAGIC:#010x} (\"MBP1\")")
            }
            PackError::BadVersion { found, expected } => {
                write!(f, "pack version {found}, this reader speaks {expected}")
            }
            PackError::BadSectionCount { found, expected } => {
                write!(f, "section count {found}, expected {expected}")
            }
            PackError::SectionOutOfBounds { index, offset, len, file_len } => write!(
                f,
                "section {index} spans {offset}..{} but the file is {file_len} bytes",
                offset.saturating_add(*len)
            ),
            PackError::SectionMisaligned { index, offset } => {
                write!(f, "section {index} starts at {offset}, which is not 8-byte aligned")
            }
            PackError::SectionTruncated { index, need, have } => write!(
                f,
                "section {index} needs {need} bytes for the row count in the header but has {have}"
            ),
            PackError::UnsupportedPoolCodec => write!(
                f,
                "pack uses a compressed string pool, which this reader cannot decode; \
                 refusing rather than returning garbage"
            ),
        }
    }
}

impl std::error::Error for PackError {}

// --- on-disk records ---

macro_rules! packed_rec {
    ($name:ident { $($field:ident : $ty:ty),* $(,)? }) => {
        #[repr(C, packed)]
        #[derive(Clone, Copy)]
        struct $name { $($field : $ty),* }
    };
}

packed_rec!(ArtistRec {
    name_off: u32,
    disamb_off: u32,
    area_off: u32,
    begin_date: u32,
    kind: u16,
    country: u16,
});
packed_rec!(RgRec {
    title_off: u32,
    first_date: u32,
    credit_idx: u32,
    primary: u16,
    secondary: u16,
});
packed_rec!(ReleaseRec {
    title_off: u32,
    rg_idx: u32,
    credit_idx: u32,
    date: u32,
    disamb_off: u32,
    status: u16,
    country: u16,
});
packed_rec!(MediumRec {
    format: u16,
    track_count: u16,
});
packed_rec!(RecordingRec {
    title_off: u32,
    dur_s: u16,
    credit_lo: u16,
    credit_hi: u8,
});

/// Read the `idx`-th `T` out of a section, or `None` if it does not fit.
///
/// `read_unaligned` because sections are only 8-byte aligned and the records are
/// `repr(C, packed)`; this is the same discipline as
/// `maps/src/main/rust/src/transit.rs`.
fn rec_at<T: Copy>(sec: &[u8], idx: usize) -> Option<T> {
    let size = std::mem::size_of::<T>();
    let start = idx.checked_mul(size)?;
    let end = start.checked_add(size)?;
    if end > sec.len() {
        return None;
    }
    // SAFETY: `start + size <= sec.len()` is checked above, T is a packed POD of
    // integers with no invalid bit patterns, and the read is unaligned.
    Some(unsafe { sec.as_ptr().add(start).cast::<T>().read_unaligned() })
}

fn u32_at(sec: &[u8], idx: usize) -> Option<u32> {
    let start = idx.checked_mul(4)?;
    let bytes = sec.get(start..start + 4)?;
    Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

// --- public value types ---

#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct Date {
    pub year: u16,
    pub month: u8,
    pub day: u8,
}

impl Date {
    fn unpack(v: u32) -> Date {
        Date {
            year: (v >> 9) as u16,
            month: ((v >> 5) & 0xf) as u8,
            day: (v & 0x1f) as u8,
        }
    }

    pub fn is_none(&self) -> bool {
        self.year == 0
    }

    /// The WS/2 spelling the app's DTOs already parse: "", "1973", "1973-03" or
    /// "1973-03-01".
    pub fn to_ws2(&self) -> String {
        if self.year == 0 {
            return String::new();
        }
        if self.month == 0 {
            return format!("{:04}", self.year);
        }
        if self.day == 0 {
            return format!("{:04}-{:02}", self.year, self.month);
        }
        format!("{:04}-{:02}-{:02}", self.year, self.month, self.day)
    }
}

#[derive(Clone, Copy, Default, Debug)]
pub struct Counts {
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

#[derive(Clone, Debug)]
pub struct Artist<'a> {
    pub name: Cow<'a, str>,
    pub disambiguation: Cow<'a, str>,
    pub area: Cow<'a, str>,
    pub begin_date: Date,
    /// WS/2 calls this `type`: "Person", "Group", ...
    pub kind: Cow<'a, str>,
    pub country: Cow<'a, str>,
}

#[derive(Clone, Debug)]
pub struct ReleaseGroup<'a> {
    pub title: Cow<'a, str>,
    pub first_release_date: Date,
    pub credit: Cow<'a, str>,
    pub primary_type: Cow<'a, str>,
    /// Only `secondary-types[0]`; the app renders no others.
    pub secondary_type: Cow<'a, str>,
}

#[derive(Clone, Debug)]
pub struct Release<'a> {
    pub title: Cow<'a, str>,
    pub rg_idx: u32,
    pub credit: Cow<'a, str>,
    pub date: Date,
    pub disambiguation: Cow<'a, str>,
    pub status: Cow<'a, str>,
    pub country: Cow<'a, str>,
}

#[derive(Clone, Debug)]
pub struct Recording<'a> {
    pub title: Cow<'a, str>,
    pub length_secs: u16,
    pub credit: Cow<'a, str>,
}

#[derive(Clone, Debug)]
pub struct Medium<'a> {
    pub position: u32,
    pub format: Cow<'a, str>,
    pub track_count: u32,
}

#[derive(Clone, Debug)]
pub struct Track<'a> {
    pub position: u32,
    /// Already resolved: the track's own title, or the recording's when the pack
    /// stores no override. Callers implement no fallback.
    pub title: Cow<'a, str>,
    pub credit: Cow<'a, str>,
    pub length_secs: u16,
    pub recording_idx: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SearchHit {
    pub idx: u32,
    pub score: u32,
}

// --- the pack ---

pub struct MbPack<'a> {
    bytes: &'a [u8],
    dir: [(u64, u64); SECTION_COUNT],
    counts: Counts,
    flags: u32,
    dump_date: u32,
}

impl fmt::Debug for MbPack<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("MbPack")
            .field("bytes", &self.bytes.len())
            .field("counts", &self.counts)
            .field("flags", &self.flags)
            .field("dump_date", &self.dump_date)
            .finish()
    }
}

impl<'a> MbPack<'a> {
    pub fn open(bytes: &'a [u8]) -> Result<MbPack<'a>, PackError> {
        let head_len = pack::HEADER_LEN as usize + SECTION_COUNT * 16;
        if bytes.len() < head_len {
            return Err(PackError::TooSmall { len: bytes.len() });
        }
        let g = |off: usize| {
            u32::from_le_bytes([bytes[off], bytes[off + 1], bytes[off + 2], bytes[off + 3]])
        };
        let magic = g(0x00);
        if magic != MAGIC {
            return Err(PackError::BadMagic { found: magic });
        }
        let version = g(0x04);
        if version != VERSION {
            return Err(PackError::BadVersion { found: version, expected: VERSION });
        }
        let section_count = g(0x08);
        if section_count as usize != SECTION_COUNT {
            return Err(PackError::BadSectionCount {
                found: section_count,
                expected: SECTION_COUNT as u32,
            });
        }
        let counts = Counts {
            artists: g(0x0C),
            credits: g(0x10),
            release_groups: g(0x14),
            releases: g(0x18),
            media: g(0x1C),
            tracks: g(0x20),
            recordings: g(0x24),
            isrcs: g(0x28),
            search_terms: g(0x2C),
            enums: g(0x30),
        };
        let flags = g(0x34);
        if flags & pack::FLAG_STRINGS_COMPRESSED != 0 {
            return Err(PackError::UnsupportedPoolCodec);
        }
        let dump_date = g(0x38);

        let mut dir = [(0u64, 0u64); SECTION_COUNT];
        let file_len = bytes.len() as u64;
        for (i, slot) in dir.iter_mut().enumerate() {
            let base = pack::HEADER_LEN as usize + i * 16;
            let rd = |o: usize| {
                let mut v = [0u8; 8];
                v.copy_from_slice(&bytes[o..o + 8]);
                u64::from_le_bytes(v)
            };
            let (off, len) = (rd(base), rd(base + 8));
            if len != 0 && off % 8 != 0 {
                return Err(PackError::SectionMisaligned { index: i, offset: off });
            }
            if off > file_len || len > file_len - off {
                return Err(PackError::SectionOutOfBounds {
                    index: i,
                    offset: off,
                    len,
                    file_len,
                });
            }
            *slot = (off, len);
        }

        let me = MbPack { bytes, dir, counts, flags, dump_date };
        me.check_capacity(pack::S_ARTISTS, counts.artists, pack::ARTIST_REC_LEN)?;
        me.check_capacity(pack::S_ARTIST_MBID, counts.artists, pack::MBID_TRUNC_LEN)?;
        me.check_capacity(pack::S_ARTIST_MBID_HI, 65537, 4)?;
        me.check_capacity(pack::S_CREDITS, counts.credits, 4)?;
        me.check_capacity(pack::S_RELEASE_GROUPS, counts.release_groups, pack::RG_REC_LEN)?;
        me.check_capacity(pack::S_RG_MBID, counts.release_groups, pack::MBID_TRUNC_LEN)?;
        me.check_capacity(pack::S_RG_MBID_HI, 65537, 4)?;
        me.check_capacity(pack::S_RELEASES, counts.releases, pack::RELEASE_REC_LEN)?;
        me.check_capacity(pack::S_RELEASE_MBID, counts.releases, pack::MBID_TRUNC_LEN)?;
        me.check_capacity(pack::S_RELEASE_MBID_HI, 65537, 4)?;
        me.check_capacity(pack::S_ARTIST_RGS_IDX, counts.artists + 1, 4)?;
        me.check_capacity(pack::S_RG_RELEASES_IDX, counts.release_groups + 1, 4)?;
        me.check_capacity(pack::S_MEDIA_IDX, counts.releases + 1, 4)?;
        me.check_capacity(pack::S_MEDIA, counts.media, pack::MEDIUM_REC_LEN)?;
        me.check_capacity(pack::S_TRACK_IDX, counts.media + 1, 4)?;
        me.check_capacity(pack::S_RECORDINGS, counts.recordings, pack::RECORDING_REC_LEN)?;
        me.check_capacity(pack::S_ENUM_POOL, counts.enums, 4)?;
        if me.has_recording_mbids() {
            me.check_capacity(pack::S_RECORDING_MBID, counts.recordings, 16)?;
        }
        if me.has_track_mbids() {
            me.check_capacity(pack::S_TRACK_MBID, counts.tracks, 16)?;
        }
        if me.has_isrcs() {
            me.check_capacity(pack::S_ISRCS, counts.isrcs, pack::ISRC_REC_LEN)?;
        }
        if counts.search_terms > 0 {
            me.check_capacity(
                pack::S_SEARCH_TERM_IDX,
                counts.search_terms + 1,
                pack::TERM_IDX_REC_LEN,
            )?;
        }
        Ok(me)
    }

    fn check_capacity(&self, index: usize, rows: u32, stride: usize) -> Result<(), PackError> {
        let need = rows as u64 * stride as u64;
        let have = self.dir[index].1;
        if have < need {
            return Err(PackError::SectionTruncated { index, need, have });
        }
        Ok(())
    }

    fn sec(&self, index: usize) -> &'a [u8] {
        let (off, len) = self.dir[index];
        &self.bytes[off as usize..(off + len) as usize]
    }

    pub fn counts(&self) -> Counts {
        self.counts
    }
    pub fn flags(&self) -> u32 {
        self.flags
    }
    pub fn dump_date(&self) -> u32 {
        self.dump_date
    }
    pub fn has_track_mbids(&self) -> bool {
        self.flags & pack::FLAG_TRACK_MBIDS != 0
    }
    pub fn has_recording_mbids(&self) -> bool {
        self.flags & pack::FLAG_RECORDING_MBIDS != 0
    }
    pub fn has_isrcs(&self) -> bool {
        self.flags & pack::FLAG_ISRCS != 0
    }
    pub fn has_recording_search(&self) -> bool {
        self.flags & pack::FLAG_RECORDING_SEARCH != 0
    }
    pub fn official_only(&self) -> bool {
        self.flags & pack::FLAG_OFFICIAL_ONLY != 0
    }

    /// Section byte lengths, for a build report or a `/status` handler.
    pub fn section_sizes(&self) -> Vec<(&'static str, u64)> {
        pack::SECTION_NAMES
            .iter()
            .zip(self.dir.iter())
            .map(|(name, &(_, len))| (*name, len))
            .collect()
    }

    // --- strings ---

    fn str_at(&self, off: u32) -> Cow<'a, str> {
        let pool = self.sec(pack::S_STRINGS);
        let start = off as usize;
        if start >= pool.len() {
            return Cow::Borrowed("");
        }
        let end = match pool[start..].iter().position(|&b| b == 0) {
            Some(n) => start + n,
            None => pool.len(),
        };
        String::from_utf8_lossy(&pool[start..end])
    }

    fn enum_str(&self, idx: u16) -> Cow<'a, str> {
        if idx == u16::MAX {
            return Cow::Borrowed("");
        }
        match u32_at(self.sec(pack::S_ENUM_POOL), idx as usize) {
            Some(off) => self.str_at(off),
            None => Cow::Borrowed(""),
        }
    }

    fn credit_at(&self, idx: u32) -> Cow<'a, str> {
        if idx == pack::NONE {
            return Cow::Borrowed("");
        }
        match u32_at(self.sec(pack::S_CREDITS), idx as usize) {
            Some(off) => self.str_at(off),
            None => Cow::Borrowed(""),
        }
    }

    // --- MBID lookup ---

    fn mbid_lookup(&self, recs_sec: usize, hi_sec: usize, count: u32, m: &Mbid) -> Option<u32> {
        let recs = self.sec(recs_sec);
        let hi = self.sec(hi_sec);
        let bucket = ((m[0] as usize) << 8) | m[1] as usize;
        let lo = u32_at(hi, bucket)?;
        let end = u32_at(hi, bucket + 1)?;
        if lo > end || end > count {
            return None;
        }
        let key = &m[2..];
        let (mut a, mut b) = (lo as usize, end as usize);
        while a < b {
            let mid = a + (b - a) / 2;
            let start = mid * pack::MBID_TRUNC_LEN;
            let rec = recs.get(start..start + pack::MBID_TRUNC_LEN)?;
            match rec.cmp(key) {
                std::cmp::Ordering::Less => a = mid + 1,
                std::cmp::Ordering::Greater => b = mid,
                std::cmp::Ordering::Equal => return Some(mid as u32),
            }
        }
        None
    }

    fn mbid_at(&self, recs_sec: usize, hi_sec: usize, count: u32, idx: u32) -> Option<Mbid> {
        if idx >= count {
            return None;
        }
        let recs = self.sec(recs_sec);
        let hi = self.sec(hi_sec);
        // Find the bucket owning `idx`: the last b with hi[b] <= idx.
        let (mut a, mut b) = (0usize, 65536usize);
        while a < b {
            let mid = a + (b - a) / 2;
            if u32_at(hi, mid + 1)? <= idx {
                a = mid + 1;
            } else {
                b = mid;
            }
        }
        let start = idx as usize * pack::MBID_TRUNC_LEN;
        let rec = recs.get(start..start + pack::MBID_TRUNC_LEN)?;
        let mut out = [0u8; 16];
        out[0] = (a >> 8) as u8;
        out[1] = (a & 0xff) as u8;
        out[2..].copy_from_slice(rec);
        Some(out)
    }

    pub fn artist_by_mbid(&self, m: &Mbid) -> Option<u32> {
        self.mbid_lookup(
            pack::S_ARTIST_MBID,
            pack::S_ARTIST_MBID_HI,
            self.counts.artists,
            m,
        )
    }

    pub fn release_group_by_mbid(&self, m: &Mbid) -> Option<u32> {
        self.mbid_lookup(pack::S_RG_MBID, pack::S_RG_MBID_HI, self.counts.release_groups, m)
    }

    pub fn release_by_mbid(&self, m: &Mbid) -> Option<u32> {
        self.mbid_lookup(
            pack::S_RELEASE_MBID,
            pack::S_RELEASE_MBID_HI,
            self.counts.releases,
            m,
        )
    }

    pub fn artist_mbid(&self, idx: u32) -> Option<Mbid> {
        self.mbid_at(
            pack::S_ARTIST_MBID,
            pack::S_ARTIST_MBID_HI,
            self.counts.artists,
            idx,
        )
    }

    pub fn release_group_mbid(&self, idx: u32) -> Option<Mbid> {
        self.mbid_at(pack::S_RG_MBID, pack::S_RG_MBID_HI, self.counts.release_groups, idx)
    }

    pub fn release_mbid(&self, idx: u32) -> Option<Mbid> {
        self.mbid_at(
            pack::S_RELEASE_MBID,
            pack::S_RELEASE_MBID_HI,
            self.counts.releases,
            idx,
        )
    }

    /// Recording MBIDs are stored raw and unsorted, in tracklist-clustered order,
    /// so this is a direct index and there is deliberately no reverse lookup.
    pub fn recording_mbid(&self, idx: u32) -> Option<Mbid> {
        if !self.has_recording_mbids() || idx >= self.counts.recordings {
            return None;
        }
        let sec = self.sec(pack::S_RECORDING_MBID);
        let start = idx as usize * 16;
        let rec = sec.get(start..start + 16)?;
        let mut out = [0u8; 16];
        out.copy_from_slice(rec);
        Some(out)
    }

    /// Always `None` in a tier-B pack: track MBIDs are the 918 MB tier B gives up.
    /// Callers must omit `MUSICBRAINZ_RELEASETRACKID` rather than invent a value.
    pub fn track_mbid(&self, idx: u32) -> Option<Mbid> {
        if !self.has_track_mbids() || idx >= self.counts.tracks {
            return None;
        }
        let sec = self.sec(pack::S_TRACK_MBID);
        let start = idx as usize * 16;
        let rec = sec.get(start..start + 16)?;
        let mut out = [0u8; 16];
        out.copy_from_slice(rec);
        Some(out)
    }

    // --- rows ---

    pub fn artist(&self, idx: u32) -> Option<Artist<'a>> {
        if idx >= self.counts.artists {
            return None;
        }
        let r: ArtistRec = rec_at(self.sec(pack::S_ARTISTS), idx as usize)?;
        Some(Artist {
            name: self.str_at(r.name_off),
            disambiguation: self.str_at(r.disamb_off),
            area: self.str_at(r.area_off),
            begin_date: Date::unpack(r.begin_date),
            kind: self.enum_str(r.kind),
            country: self.enum_str(r.country),
        })
    }

    pub fn release_group(&self, idx: u32) -> Option<ReleaseGroup<'a>> {
        if idx >= self.counts.release_groups {
            return None;
        }
        let r: RgRec = rec_at(self.sec(pack::S_RELEASE_GROUPS), idx as usize)?;
        Some(ReleaseGroup {
            title: self.str_at(r.title_off),
            first_release_date: Date::unpack(r.first_date),
            credit: self.credit_at(r.credit_idx),
            primary_type: self.enum_str(r.primary),
            secondary_type: self.enum_str(r.secondary),
        })
    }

    pub fn release(&self, idx: u32) -> Option<Release<'a>> {
        if idx >= self.counts.releases {
            return None;
        }
        let r: ReleaseRec = rec_at(self.sec(pack::S_RELEASES), idx as usize)?;
        Some(Release {
            title: self.str_at(r.title_off),
            rg_idx: r.rg_idx,
            credit: self.credit_at(r.credit_idx),
            date: Date::unpack(r.date),
            disambiguation: self.str_at(r.disamb_off),
            status: self.enum_str(r.status),
            country: self.enum_str(r.country),
        })
    }

    pub fn recording(&self, idx: u32) -> Option<Recording<'a>> {
        let r = self.recording_rec(idx)?;
        Some(Recording {
            title: self.str_at(r.title_off),
            length_secs: r.dur_s,
            credit: self.credit_at(credit_of(&r)),
        })
    }

    fn recording_rec(&self, idx: u32) -> Option<RecordingRec> {
        if idx >= self.counts.recordings {
            return None;
        }
        rec_at(self.sec(pack::S_RECORDINGS), idx as usize)
    }

    /// The release a recording first appeared on, for a recording search hit's
    /// `releases[0]`.
    pub fn recording_first_release(&self, idx: u32) -> Option<u32> {
        if idx >= self.counts.recordings {
            return None;
        }
        match u32_at(self.sec(pack::S_REC_FIRST_RELEASE), idx as usize) {
            Some(v) if v != pack::NONE && v < self.counts.releases => Some(v),
            _ => None,
        }
    }

    // --- child lists ---

    fn csr(&self, values: usize, index: usize, parent: u32, parents: u32) -> Vec<u32> {
        if parent >= parents {
            return Vec::new();
        }
        let idx = self.sec(index);
        let (Some(lo), Some(hi)) = (u32_at(idx, parent as usize), u32_at(idx, parent as usize + 1))
        else {
            return Vec::new();
        };
        if lo > hi {
            return Vec::new();
        }
        let vals = self.sec(values);
        (lo..hi).filter_map(|i| u32_at(vals, i as usize)).collect()
    }

    /// Release groups of an artist, already newest-first by first-release-date.
    pub fn artist_release_groups(&self, artist_idx: u32) -> Vec<u32> {
        self.csr(
            pack::S_ARTIST_RGS,
            pack::S_ARTIST_RGS_IDX,
            artist_idx,
            self.counts.artists,
        )
    }

    /// Releases of a release group, already sorted the way the edition list shows
    /// them (status, then date).
    pub fn release_group_releases(&self, rg_idx: u32) -> Vec<u32> {
        self.csr(
            pack::S_RG_RELEASES,
            pack::S_RG_RELEASES_IDX,
            rg_idx,
            self.counts.release_groups,
        )
    }

    /// The medium range of a release, as indices into MEDIA.
    fn media_range(&self, release_idx: u32) -> Option<(u32, u32)> {
        if release_idx >= self.counts.releases {
            return None;
        }
        let idx = self.sec(pack::S_MEDIA_IDX);
        let lo = u32_at(idx, release_idx as usize)?;
        let hi = u32_at(idx, release_idx as usize + 1)?;
        if lo > hi || hi > self.counts.media {
            return None;
        }
        Some((lo, hi))
    }

    pub fn release_media(&self, release_idx: u32) -> Vec<Medium<'a>> {
        let Some((lo, hi)) = self.media_range(release_idx) else {
            return Vec::new();
        };
        let sec = self.sec(pack::S_MEDIA);
        (lo..hi)
            .enumerate()
            .filter_map(|(ordinal, m)| {
                let r: MediumRec = rec_at(sec, m as usize)?;
                Some(Medium {
                    position: ordinal as u32 + 1,
                    format: self.enum_str(r.format),
                    track_count: r.track_count as u32,
                })
            })
            .collect()
    }

    /// Total track count across a release's media (the app's TRACKTOTAL / the
    /// edition list's `sum(media[].track-count)`), without decoding any tracks.
    pub fn release_track_count(&self, release_idx: u32) -> u32 {
        let Some((lo, hi)) = self.media_range(release_idx) else {
            return 0;
        };
        let sec = self.sec(pack::S_MEDIA);
        (lo..hi)
            .filter_map(|m| rec_at::<MediumRec>(sec, m as usize))
            .map(|r| r.track_count as u32)
            .sum()
    }

    /// Decode one medium's varint run.
    pub fn medium_tracks(&self, release_idx: u32, medium_ordinal: usize) -> Vec<Track<'a>> {
        let Some((lo, hi)) = self.media_range(release_idx) else {
            return Vec::new();
        };
        let m = lo as usize + medium_ordinal;
        if m >= hi as usize {
            return Vec::new();
        }
        self.tracks_of_medium(m as u32)
    }

    fn tracks_of_medium(&self, medium_idx: u32) -> Vec<Track<'a>> {
        let idx = self.sec(pack::S_TRACK_IDX);
        let (Some(start), Some(end)) =
            (u32_at(idx, medium_idx as usize), u32_at(idx, medium_idx as usize + 1))
        else {
            return Vec::new();
        };
        let stream = self.sec(pack::S_TRACKS);
        if start > end || end as usize > stream.len() {
            return Vec::new();
        }
        let mut out = Vec::new();
        let mut pos = start as usize;
        let mut prev_rec: i64 = -1;
        let mut ordinal = 0u32;
        while pos < end as usize {
            let Some(flags) = read_uvarint(stream, &mut pos) else { break };
            let rec_idx = if ordinal == 0 {
                match read_uvarint(stream, &mut pos) {
                    Some(v) => v as i64,
                    None => break,
                }
            } else {
                match read_zigzag(stream, &mut pos) {
                    Some(d) => prev_rec + d,
                    None => break,
                }
            };
            if rec_idx < 0 || rec_idx as u64 >= self.counts.recordings as u64 {
                break;
            }
            prev_rec = rec_idx;
            let rec_idx = rec_idx as u32;
            let mut title_off = None;
            let mut credit_idx = None;
            let mut position = ordinal + 1;
            let mut length = None;
            if flags & 1 != 0 {
                match read_uvarint(stream, &mut pos) {
                    Some(v) => title_off = Some(v as u32),
                    None => break,
                }
            }
            if flags & 2 != 0 {
                match read_uvarint(stream, &mut pos) {
                    Some(v) => credit_idx = Some(v as u32),
                    None => break,
                }
            }
            if flags & 4 != 0 {
                match read_uvarint(stream, &mut pos) {
                    Some(v) => position = v as u32,
                    None => break,
                }
            }
            if flags & 8 != 0 {
                match read_uvarint(stream, &mut pos) {
                    Some(v) => length = Some(v.min(u16::MAX as u64) as u16),
                    None => break,
                }
            }
            let rec = self.recording_rec(rec_idx);
            out.push(Track {
                position,
                title: match title_off {
                    Some(off) => self.str_at(off),
                    None => rec.map(|r| self.str_at(r.title_off)).unwrap_or(Cow::Borrowed("")),
                },
                credit: match credit_idx {
                    Some(c) => self.credit_at(c),
                    None => rec.map(|r| self.credit_at(credit_of(&r))).unwrap_or(Cow::Borrowed("")),
                },
                length_secs: length.unwrap_or_else(|| rec.map(|r| r.dur_s).unwrap_or(0)),
                recording_idx: rec_idx,
            });
            ordinal += 1;
        }
        out
    }

    /// Everything `GET /api/mb/release/:mbid` needs in one call.
    pub fn release_tracklist(&self, release_idx: u32) -> Vec<(Medium<'a>, Vec<Track<'a>>)> {
        let Some((lo, hi)) = self.media_range(release_idx) else {
            return Vec::new();
        };
        let sec = self.sec(pack::S_MEDIA);
        (lo..hi)
            .enumerate()
            .filter_map(|(ordinal, m)| {
                let r: MediumRec = rec_at(sec, m as usize)?;
                let medium = Medium {
                    position: ordinal as u32 + 1,
                    format: self.enum_str(r.format),
                    track_count: r.track_count as u32,
                };
                Some((medium, self.tracks_of_medium(m)))
            })
            .collect()
    }

    /// ISRCs of a recording as 12-character ASCII, unpacked from the stored 7 B.
    pub fn recording_isrcs(&self, rec_idx: u32) -> Vec<[u8; 12]> {
        if !self.has_isrcs() {
            return Vec::new();
        }
        let sec = self.sec(pack::S_ISRCS);
        let n = self.counts.isrcs as usize;
        let key_at = |i: usize| -> Option<u32> {
            let start = i * pack::ISRC_REC_LEN;
            let b = sec.get(start..start + 4)?;
            Some(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
        };
        // First index whose recording >= rec_idx.
        let (mut a, mut b) = (0usize, n);
        while a < b {
            let mid = a + (b - a) / 2;
            match key_at(mid) {
                Some(k) if k < rec_idx => a = mid + 1,
                Some(_) => b = mid,
                None => return Vec::new(),
            }
        }
        let mut out = Vec::new();
        let mut i = a;
        while i < n && key_at(i) == Some(rec_idx) {
            let start = i * pack::ISRC_REC_LEN + 4;
            let Some(raw) = sec.get(start..start + 7) else { break };
            let mut packed = [0u8; 7];
            packed.copy_from_slice(raw);
            out.push(pack::unpack_isrc(&packed));
            i += 1;
        }
        out
    }

    // --- search ---

    fn term_count(&self) -> usize {
        self.counts.search_terms as usize
    }

    fn term_idx(&self, i: usize) -> Option<(u32, u32)> {
        let sec = self.sec(pack::S_SEARCH_TERM_IDX);
        let start = i * pack::TERM_IDX_REC_LEN;
        let b = sec.get(start..start + 8)?;
        Some((
            u32::from_le_bytes([b[0], b[1], b[2], b[3]]),
            u32::from_le_bytes([b[4], b[5], b[6], b[7]]),
        ))
    }

    fn term_bytes(&self, i: usize) -> Option<&'a [u8]> {
        let terms = self.sec(pack::S_SEARCH_TERMS);
        let (start, _) = self.term_idx(i)?;
        let (next, _) = self.term_idx(i + 1)?;
        if next <= start {
            return None;
        }
        // The stored run includes a trailing NUL.
        terms.get(start as usize..next as usize - 1)
    }

    /// The half-open term-id range whose terms start with `prefix`.
    fn prefix_range(&self, prefix: &[u8]) -> (usize, usize) {
        let n = self.term_count();
        let lower = {
            let (mut a, mut b) = (0usize, n);
            while a < b {
                let mid = a + (b - a) / 2;
                match self.term_bytes(mid) {
                    Some(t) if t < prefix => a = mid + 1,
                    _ => b = mid,
                }
            }
            a
        };
        let mut hi = lower;
        // Capped for the same reason the postings scan is: a one-character query
        // otherwise walks the whole dictionary before any budget applies.
        let ceiling = n.min(lower + Self::MAX_TERMS_PER_TOKEN);
        while hi < ceiling {
            match self.term_bytes(hi) {
                Some(t) if t.starts_with(prefix) => hi += 1,
                _ => break,
            }
        }
        (lower, hi)
    }

    /// Terms scanned per query token. A one-letter query can match hundreds of
    /// thousands of terms; the cap keeps a pathological query bounded instead of
    /// walking the whole dictionary. Hits are still ranked, and exact matches sort
    /// first, so the truncation is invisible for any realistic query.
    const MAX_TERMS_PER_TOKEN: usize = 4096;
    /// Postings decoded per query token, same reasoning.
    const MAX_POSTINGS_PER_TOKEN: usize = 400_000;

    fn matches_for_token(&self, token: &[u8], kind: u32) -> HashMap<u32, u32> {
        let mut out: HashMap<u32, u32> = HashMap::new();
        let (lo, hi) = self.prefix_range(token);
        let postings = self.sec(pack::S_SEARCH_POSTINGS);
        let mut budget = Self::MAX_POSTINGS_PER_TOKEN;
        for t in lo..hi.min(lo + Self::MAX_TERMS_PER_TOKEN) {
            let exact = self.term_bytes(t) == Some(token);
            let weight = if exact { 2 } else { 1 };
            let (Some((_, start)), Some((_, end))) = (self.term_idx(t), self.term_idx(t + 1)) else {
                continue;
            };
            if start > end || end as usize > postings.len() {
                continue;
            }
            let mut pos = start as usize;
            let Some(n) = read_uvarint(postings, &mut pos) else { continue };
            let mut prev: u64 = 0;
            for _ in 0..n {
                if budget == 0 {
                    break;
                }
                budget -= 1;
                let Some(d) = read_uvarint(postings, &mut pos) else { break };
                prev += d;
                let refv = prev as u32;
                if refv >> KIND_SHIFT == kind {
                    let idx = refv & IDX_MASK;
                    let e = out.entry(idx).or_insert(0);
                    *e = (*e).max(weight);
                }
            }
            if budget == 0 {
                break;
            }
        }
        out
    }

    fn search(&self, q: &str, limit: usize, kind: u32, count: u32) -> Vec<SearchHit> {
        if limit == 0 || count == 0 || self.term_count() == 0 {
            return Vec::new();
        }
        let mut tokens = Vec::new();
        pack::search_terms(q, &mut tokens);
        if tokens.is_empty() {
            return Vec::new();
        }
        // AND across tokens: "dark side" should not return everything matching
        // "side". Intersecting the smallest set first keeps the work down.
        let mut acc: Option<HashMap<u32, u32>> = None;
        for token in &tokens {
            let m = self.matches_for_token(token.as_bytes(), kind);
            if m.is_empty() {
                return Vec::new();
            }
            acc = Some(match acc {
                None => m,
                Some(prev) => {
                    let (small, large) = if prev.len() <= m.len() { (prev, m) } else { (m, prev) };
                    small
                        .into_iter()
                        .filter_map(|(k, v)| large.get(&k).map(|w| (k, v + w)))
                        .collect()
                }
            });
            if acc.as_ref().is_some_and(|a| a.is_empty()) {
                return Vec::new();
            }
        }
        let acc = acc.unwrap_or_default();
        let mut hits: Vec<SearchHit> = acc
            .into_iter()
            .filter(|&(idx, _)| idx < count)
            .map(|(idx, exactness)| SearchHit {
                idx,
                score: exactness * 1000 + self.popularity(kind, idx).min(999),
            })
            .collect();
        // Ties break on index so results are deterministic for a given pack.
        hits.sort_by(|a, b| b.score.cmp(&a.score).then(a.idx.cmp(&b.idx)));
        hits.truncate(limit);
        hits
    }

    /// A free popularity proxy: how many children the entity has, which is a CSR
    /// range length already in the pack. Recordings have no child list, so they
    /// rank on match quality alone.
    fn popularity(&self, kind: u32, idx: u32) -> u32 {
        let (values_idx, parents) = match kind {
            KIND_ARTIST => (pack::S_ARTIST_RGS_IDX, self.counts.artists),
            KIND_RELEASE_GROUP => (pack::S_RG_RELEASES_IDX, self.counts.release_groups),
            _ => return 0,
        };
        if idx >= parents {
            return 0;
        }
        let sec = self.sec(values_idx);
        match (u32_at(sec, idx as usize), u32_at(sec, idx as usize + 1)) {
            (Some(lo), Some(hi)) if hi >= lo => hi - lo,
            _ => 0,
        }
    }

    /// Word-prefix search, ranked and truncated. Not substring, not Lucene: see
    /// the design doc's R7. Pass the user's raw query; normalisation happens here.
    pub fn search_artists(&self, q: &str, limit: usize) -> Vec<SearchHit> {
        self.search(q, limit, KIND_ARTIST, self.counts.artists)
    }

    pub fn search_release_groups(&self, q: &str, limit: usize) -> Vec<SearchHit> {
        self.search(q, limit, KIND_RELEASE_GROUP, self.counts.release_groups)
    }

    pub fn search_recordings(&self, q: &str, limit: usize) -> Vec<SearchHit> {
        if !self.has_recording_search() {
            return Vec::new();
        }
        self.search(q, limit, KIND_RECORDING, self.counts.recordings)
    }
}

fn credit_of(r: &RecordingRec) -> u32 {
    let lo = r.credit_lo as u32;
    let hi = r.credit_hi as u32;
    let v = lo | (hi << 16);
    if v == 0x00FF_FFFF {
        pack::NONE
    } else {
        v
    }
}

/// Encode a 24-bit credit index for a `RecordingRec`. Shared with the writer so
/// the two cannot drift.
pub fn encode_recording_credit(credit_idx: u32) -> (u16, u8) {
    let v = if credit_idx == pack::NONE { 0x00FF_FFFF } else { credit_idx };
    assert!(v <= 0x00FF_FFFF, "credit index {credit_idx} exceeds the 24-bit field");
    ((v & 0xFFFF) as u16, (v >> 16) as u8)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_garbage_without_panicking() {
        assert_eq!(MbPack::open(&[]).unwrap_err(), PackError::TooSmall { len: 0 });
        let mut buf = vec![0u8; 64 + SECTION_COUNT * 16];
        assert_eq!(MbPack::open(&buf).unwrap_err(), PackError::BadMagic { found: 0 });
        buf[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        buf[4..8].copy_from_slice(&99u32.to_le_bytes());
        assert_eq!(
            MbPack::open(&buf).unwrap_err(),
            PackError::BadVersion { found: 99, expected: VERSION }
        );
    }

    #[test]
    fn rejects_out_of_bounds_sections() {
        let mut buf = vec![0u8; 64 + SECTION_COUNT * 16];
        buf[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        buf[4..8].copy_from_slice(&VERSION.to_le_bytes());
        buf[8..12].copy_from_slice(&(SECTION_COUNT as u32).to_le_bytes());
        // Section 0 claims to run past the end of the file.
        buf[64..72].copy_from_slice(&8u64.to_le_bytes());
        buf[72..80].copy_from_slice(&u64::MAX.to_le_bytes());
        assert!(matches!(
            MbPack::open(&buf).unwrap_err(),
            PackError::SectionOutOfBounds { index: 0, .. }
        ));
    }

    #[test]
    fn refuses_a_compressed_pool_rather_than_guessing() {
        let mut buf = vec![0u8; 64 + SECTION_COUNT * 16];
        buf[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        buf[4..8].copy_from_slice(&VERSION.to_le_bytes());
        buf[8..12].copy_from_slice(&(SECTION_COUNT as u32).to_le_bytes());
        buf[0x34..0x38].copy_from_slice(&pack::FLAG_STRINGS_COMPRESSED.to_le_bytes());
        assert_eq!(MbPack::open(&buf).unwrap_err(), PackError::UnsupportedPoolCodec);
    }

    #[test]
    fn dates_render_as_ws2() {
        assert_eq!(Date { year: 0, month: 0, day: 0 }.to_ws2(), "");
        assert_eq!(Date { year: 1973, month: 0, day: 0 }.to_ws2(), "1973");
        assert_eq!(Date { year: 1973, month: 3, day: 0 }.to_ws2(), "1973-03");
        assert_eq!(Date { year: 1973, month: 3, day: 1 }.to_ws2(), "1973-03-01");
    }

    #[test]
    fn recording_credit_round_trips_through_24_bits() {
        for v in [0u32, 1, 0xFFFF, 0x1_0000, 0x00FF_FFFE, pack::NONE] {
            let (lo, hi) = encode_recording_credit(v);
            let r = RecordingRec { title_off: 0, dur_s: 0, credit_lo: lo, credit_hi: hi };
            assert_eq!(credit_of(&r), v, "credit {v}");
        }
    }
}
