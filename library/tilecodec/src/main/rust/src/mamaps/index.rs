//! The two-level index: a root of fixed-stride entries, each pointing at one leaf.
//!
//! Both levels are **uncompressed and fixed stride**, which is the whole design. The root can
//! then be used straight out of the bytes a reader already fetched to read the header, with no
//! inflate step and no sequential decode — and a leaf can be binary-searched, because
//! [`LeafEntry::tile_id_lo`] is absolute within its leaf rather than a delta from the entry
//! before it.
//!
//! # The prefix budget
//!
//! A reader opens with one 16 KiB read. Header (128) plus dictionary (under 1 KiB) leaves about
//! 15 KiB, which is 476 root entries; at 4096 tiles each that addresses 1.95 M tiles without a
//! second request. California needs under 100 leaves. Past 1.95 M,
//! [`Header::leaf_entry_capacity`](super::header::Header::leaf_entry_capacity) doubles rather
//! than the root growing — the same trick `pmtiles::split_entries` uses, and for the same reason.
//!
//! [`super::write`] **asserts** the budget. A root that does not fit is a build failure, never a
//! silent third round trip charged to every reader forever.
//!
//! # Addressing
//!
//! Tile ids are `pmtiles::tile_id` unchanged: space-filling, zoom-major and monotonic. Those are
//! exactly the three properties the generator's spill bucketing and pyramid ranges already depend
//! on, so inventing a curve here would buy nothing and break that coupling.

use crate::proto::{err, Result};

pub const ROOT_ENTRY_LEN: usize = 32;
pub const LEAF_ENTRY_LEN: usize = 16;

/// One leaf's worth of the tile-id space.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RootEntry {
    /// The first tile id this leaf covers. Ascending across the root, so it binary-searches.
    pub base_tile_id: u64,
    /// Where the leaf's entries start, relative to
    /// [`Header::leaf_offset`](super::header::Header::leaf_offset).
    pub leaf_offset: u64,
    /// What every [`LeafEntry::offset_delta`] in this leaf is relative to, itself relative to
    /// [`Header::data_offset`](super::header::Header::data_offset).
    ///
    /// Two levels of relative offset so a leaf entry's own offset field stays 32-bit: a planet
    /// data section is past 4 GiB, but one leaf's 4096 bodies are not.
    pub base_data_offset: u64,
    pub leaf_entry_count: u32,
}

/// One tile, or a run of consecutive tiles that share a body.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LeafEntry {
    /// This entry's first tile id, **less its leaf's `base_tile_id`**.
    ///
    /// Absolute within the leaf rather than a delta from the previous entry, so a lookup is a
    /// binary search rather than a walk from the front. `u32` bounds a leaf's id span; the writer
    /// asserts and splits rather than leaving that as a panic waiting past z15.
    pub tile_id_lo: u32,
    /// How many consecutive tile ids share this body. Never zero.
    ///
    /// What makes ocean and empty tiles nearly free: on the planet archive, run-length and
    /// content dedup together collapse 1.57 M addressed tiles to 1.03 M stored bodies.
    pub run_length: u32,
    /// Where the body starts, relative to this leaf's `base_data_offset`.
    pub offset_delta: u32,
    /// The body's stored length — compressed, if the header says bodies are.
    pub length: u32,
}

pub fn serialize_root(entries: &[RootEntry]) -> Vec<u8> {
    let mut out = Vec::with_capacity(entries.len() * ROOT_ENTRY_LEN);
    for e in entries {
        out.extend_from_slice(&e.base_tile_id.to_le_bytes());
        out.extend_from_slice(&e.leaf_offset.to_le_bytes());
        out.extend_from_slice(&e.base_data_offset.to_le_bytes());
        out.extend_from_slice(&e.leaf_entry_count.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
    }
    debug_assert_eq!(out.len(), entries.len() * ROOT_ENTRY_LEN);
    out
}

pub fn parse_root(buf: &[u8]) -> Result<Vec<RootEntry>> {
    if buf.len() % ROOT_ENTRY_LEN != 0 {
        return err(format!(
            "a .mamaps root index of {} bytes is not a whole number of {ROOT_ENTRY_LEN} byte entries",
            buf.len(),
        ));
    }
    let mut out = Vec::with_capacity(buf.len() / ROOT_ENTRY_LEN);
    for chunk in buf.chunks_exact(ROOT_ENTRY_LEN) {
        if u32::from_le_bytes([chunk[28], chunk[29], chunk[30], chunk[31]]) != 0 {
            return err("a .mamaps root entry has a non-zero reserved word");
        }
        out.push(RootEntry {
            base_tile_id: u64_at(chunk, 0),
            leaf_offset: u64_at(chunk, 8),
            base_data_offset: u64_at(chunk, 16),
            leaf_entry_count: u32::from_le_bytes([chunk[24], chunk[25], chunk[26], chunk[27]]),
        });
    }
    // Ascending, or the binary search below would silently return the wrong leaf rather than
    // failing. Checked once on open, not per lookup.
    if out.windows(2).any(|pair| pair[1].base_tile_id <= pair[0].base_tile_id) {
        return err("a .mamaps root index is not ordered by tile id");
    }
    Ok(out)
}

pub fn serialize_leaf(entries: &[LeafEntry]) -> Vec<u8> {
    let mut out = Vec::with_capacity(entries.len() * LEAF_ENTRY_LEN);
    for e in entries {
        out.extend_from_slice(&e.tile_id_lo.to_le_bytes());
        out.extend_from_slice(&e.run_length.to_le_bytes());
        out.extend_from_slice(&e.offset_delta.to_le_bytes());
        out.extend_from_slice(&e.length.to_le_bytes());
    }
    out
}

pub fn parse_leaf(buf: &[u8]) -> Result<Vec<LeafEntry>> {
    if buf.len() % LEAF_ENTRY_LEN != 0 {
        return err(format!(
            "a .mamaps leaf of {} bytes is not a whole number of {LEAF_ENTRY_LEN} byte entries",
            buf.len(),
        ));
    }
    let mut out = Vec::with_capacity(buf.len() / LEAF_ENTRY_LEN);
    for chunk in buf.chunks_exact(LEAF_ENTRY_LEN) {
        let at = |o: usize| u32::from_le_bytes([chunk[o], chunk[o + 1], chunk[o + 2], chunk[o + 3]]);
        let entry = LeafEntry {
            tile_id_lo: at(0),
            run_length: at(4),
            offset_delta: at(8),
            length: at(12),
        };
        // A zero run covers no tile, so it could never be found — but it would also make the
        // `tile_id_lo + run_length` bound below reject the entry's own first tile.
        if entry.run_length == 0 {
            return err("a .mamaps leaf entry covers zero tiles");
        }
        out.push(entry);
    }
    if out.windows(2).any(|pair| pair[1].tile_id_lo <= pair[0].tile_id_lo) {
        return err("a .mamaps leaf is not ordered by tile id");
    }
    Ok(out)
}

fn u64_at(buf: &[u8], o: usize) -> u64 {
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
}

/// The leaf covering `want`: the last entry whose `base_tile_id <= want`.
///
/// No upper bound check, because the root partitions the whole id space — the last leaf owns
/// everything past its base, and whether the tile is actually *in* it is the leaf's answer.
pub fn find_leaf(root: &[RootEntry], want: u64) -> Option<&RootEntry> {
    match root.binary_search_by(|e| e.base_tile_id.cmp(&want)) {
        Ok(i) => root.get(i),
        Err(0) => None,
        Err(i) => root.get(i - 1),
    }
}

/// The entry covering `want`, which is a leaf-relative id.
pub fn find_tile(leaf: &[LeafEntry], want: u32) -> Option<&LeafEntry> {
    let entry = match leaf.binary_search_by(|e| e.tile_id_lo.cmp(&want)) {
        Ok(i) => leaf.get(i)?,
        Err(0) => return None,
        Err(i) => leaf.get(i - 1)?,
    };
    // Inside the run, or the archive simply does not hold this tile — which is the ordinary
    // answer anywhere off the edge of its coverage, not an error.
    (want - entry.tile_id_lo < entry.run_length).then_some(entry)
}

/// Does `span` fit the `u32` a [`LeafEntry::tile_id_lo`] carries?
///
/// Asked by the writer before it commits to a leaf boundary. A leaf covering a wide stretch of a
/// deep zoom can span more than 4 G ids, and finding that out by truncation would put a body at
/// the wrong tile with no diagnostic at all.
pub fn span_fits(span: u64) -> bool {
    span <= u32::MAX as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pmtiles::{tile_id, tile_zxy, zoom_base};

    fn root_of(bases: &[u64]) -> Vec<RootEntry> {
        bases
            .iter()
            .enumerate()
            .map(|(i, base)| RootEntry {
                base_tile_id: *base,
                leaf_offset: (i * 64) as u64,
                base_data_offset: (i * 1000) as u64,
                leaf_entry_count: 4,
            })
            .collect()
    }

    #[test]
    fn a_root_entry_is_thirty_two_bytes_and_a_leaf_entry_sixteen() {
        // Fixed stride is what lets the root be used straight out of the opening prefix.
        assert_eq!(serialize_root(&root_of(&[0, 5])).len(), 2 * ROOT_ENTRY_LEN);
        assert_eq!(
            serialize_leaf(&[LeafEntry {
                tile_id_lo: 0,
                run_length: 1,
                offset_delta: 0,
                length: 9,
            }])
            .len(),
            LEAF_ENTRY_LEN,
        );
    }

    #[test]
    fn both_levels_round_trip() {
        let root = root_of(&[0, 4096, 8192]);
        assert_eq!(parse_root(&serialize_root(&root)).expect("root"), root);
        let leaf = vec![
            LeafEntry { tile_id_lo: 0, run_length: 3, offset_delta: 0, length: 40 },
            LeafEntry { tile_id_lo: 3, run_length: 1, offset_delta: 40, length: 12 },
            LeafEntry { tile_id_lo: 9, run_length: 2, offset_delta: 52, length: 7 },
        ];
        assert_eq!(parse_leaf(&serialize_leaf(&leaf)).expect("leaf"), leaf);
    }

    /// **Invariant 1 of the plan's verification list.** Header plus dictionary plus root must fit
    /// the one 16 KiB read a reader opens with, at a scale well past California.
    #[test]
    fn a_root_addressing_two_million_tiles_fits_the_opening_prefix() {
        let prefix = crate::stream::OPEN_PREFIX_BYTES as usize;
        let dictionary = super::super::dict::Dictionary::schema().serialize().len();
        let budget = prefix - super::super::header::HEADER_LEN - dictionary;
        let entries = budget / ROOT_ENTRY_LEN;
        assert!(entries >= 476, "only {entries} root entries fit the prefix");
        assert!(
            entries * 4096 >= 1_950_000,
            "{} tiles addressable in one request",
            entries * 4096,
        );
        // And the bytes really are that size, not merely the arithmetic.
        let root = root_of(&(0..entries as u64).map(|i| i * 4096).collect::<Vec<_>>());
        let bytes = serialize_root(&root);
        assert!(
            super::super::header::HEADER_LEN + dictionary + bytes.len() <= prefix,
            "header + dictionary + {} root entries is {} bytes",
            entries,
            super::super::header::HEADER_LEN + dictionary + bytes.len(),
        );
    }

    #[test]
    fn a_lookup_finds_the_leaf_that_covers_a_tile() {
        let root = root_of(&[100, 200, 300]);
        assert_eq!(find_leaf(&root, 99), None, "before the first leaf");
        assert_eq!(find_leaf(&root, 100).map(|e| e.base_tile_id), Some(100), "exactly the base");
        assert_eq!(find_leaf(&root, 150).map(|e| e.base_tile_id), Some(100));
        assert_eq!(find_leaf(&root, 200).map(|e| e.base_tile_id), Some(200));
        // The last leaf owns everything past its base; whether the tile is in it is its answer.
        assert_eq!(find_leaf(&root, 9_999).map(|e| e.base_tile_id), Some(300));
        assert_eq!(find_leaf(&[], 1), None);
    }

    #[test]
    fn a_run_covers_its_whole_span_and_nothing_past_it() {
        let leaf = vec![
            LeafEntry { tile_id_lo: 10, run_length: 4, offset_delta: 0, length: 8 },
            LeafEntry { tile_id_lo: 20, run_length: 1, offset_delta: 8, length: 8 },
        ];
        assert!(find_tile(&leaf, 9).is_none(), "before the first entry");
        for want in 10..14 {
            assert_eq!(find_tile(&leaf, want).map(|e| e.tile_id_lo), Some(10), "{want} is in the run");
        }
        // A hole between the runs is the ordinary answer off the edge of coverage.
        for want in [14, 15, 19, 21, 5000] {
            assert!(find_tile(&leaf, want).is_none(), "{want} is not stored");
        }
        assert_eq!(find_tile(&leaf, 20).map(|e| e.tile_id_lo), Some(20));
    }

    #[test]
    fn an_unordered_or_impossible_index_is_refused() {
        let mut root = root_of(&[0, 4096]);
        root.swap(0, 1);
        assert!(parse_root(&serialize_root(&root)).is_err(), "descending bases");
        let repeated = root_of(&[7, 7]);
        assert!(parse_root(&serialize_root(&repeated)).is_err(), "two leaves at one base");
        assert!(parse_root(&[0u8; ROOT_ENTRY_LEN - 1]).is_err(), "a partial entry");

        let leaf = vec![
            LeafEntry { tile_id_lo: 5, run_length: 1, offset_delta: 0, length: 1 },
            LeafEntry { tile_id_lo: 5, run_length: 1, offset_delta: 1, length: 1 },
        ];
        assert!(parse_leaf(&serialize_leaf(&leaf)).is_err(), "two entries at one id");
        let empty_run = vec![LeafEntry { tile_id_lo: 0, run_length: 0, offset_delta: 0, length: 1 }];
        assert!(parse_leaf(&serialize_leaf(&empty_run)).is_err(), "a run covering no tile");
        assert!(parse_leaf(&[0u8; LEAF_ENTRY_LEN + 1]).is_err(), "a partial entry");
    }

    #[test]
    fn a_reserved_word_a_later_version_may_claim_is_checked() {
        let mut bytes = serialize_root(&root_of(&[0]));
        bytes[28] = 1;
        assert!(parse_root(&bytes).is_err());
    }

    /// **Invariant 4.** The generator's spill buckets and its per-zoom passes both key on
    /// `zoom_base(z)..zoom_base(z + 1)`, so a leaf boundary landing inside a zoom is fine but the
    /// ids themselves must stay zoom-major and monotonic. This pins the property this index
    /// inherits rather than re-derives.
    #[test]
    fn tile_ids_stay_zoom_major_and_monotonic_across_the_archives_range() {
        for z in 0..=14u8 {
            let (lo, hi) = (zoom_base(z), zoom_base(z + 1));
            let n = 1u64 << z;
            for (x, y) in [(0, 0), (n / 2, n / 2), (n - 1, n - 1)] {
                let id = tile_id(z, x, y);
                assert!((lo..hi).contains(&id), "z{z}/{x}/{y} is outside its zoom's range");
                assert_eq!(tile_zxy(id), (z, x, y), "the id does not round trip");
            }
        }
        // And every zoom's whole range precedes the next, which is what makes a root ordered by
        // tile id also ordered by zoom.
        for z in 0..14u8 {
            assert!(zoom_base(z + 1) > zoom_base(z));
        }
    }

    #[test]
    fn a_leaf_span_wider_than_a_u32_is_rejected_rather_than_truncated() {
        assert!(span_fits(u32::MAX as u64));
        assert!(!span_fits(u32::MAX as u64 + 1));
        // Real: one z15 leaf covering a wide stretch is nowhere near this, but a z16 archive
        // built with a large capacity could be, and truncation would put a body at another tile.
        assert!(span_fits(4096));
    }
}
