//! The `wikidesc` container: an id-sorted index over a blob of NUL-terminated paragraphs.
//!
//! ```text
//! magic    8   "WIKIDESC"
//! version  4   u32 LE
//! count    4   u32 LE          number of index entries
//! index   16n  (osm_id u64 LE, offset u64 LE), ASCENDING BY osm_id
//! blob     …   UTF-8 paragraphs, each NUL-terminated
//! ```
//!
//! # Why the index is sorted
//!
//! A lookup is a binary search followed by one read up to the next NUL. Sorted order is what makes
//! that possible without loading anything, so the same file works whether it is downloaded whole —
//! which is the plan, because descriptions have to survive going offline — or range-read like the
//! basemap. Nothing about the format forces that choice.
//!
//! # Why offsets rather than lengths
//!
//! Two ids can point at the same offset, and identical text is then stored once. That matters more
//! than it sounds: the lead of a small settlement is often a single templated sentence, and across
//! a planet the repeats are not rare. It costs nothing — the terminator already delimits the text,
//! so a length field would only be redundant.

use std::collections::HashMap;
use std::io::{Read, Write};

pub const MAGIC: &[u8; 8] = b"WIKIDESC";
pub const VERSION: u32 = 1;
/// `magic` + `version` + `count`.
const HEADER_LEN: usize = 16;
const ENTRY_LEN: usize = 16;

/// Collects descriptions and writes the archive.
#[derive(Default)]
pub struct Builder {
    /// `osm_id -> offset into `blob``.
    entries: Vec<(u64, u64)>,
    /// Text already written, so a repeat costs one index entry rather than a second copy.
    seen: HashMap<String, u64>,
    blob: Vec<u8>,
}

impl Builder {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record `text` for `osm_id`.
    ///
    /// Empty text is dropped rather than stored: an entry that resolves to nothing is worse than
    /// no entry, because the caller cannot tell "no article" from "an article that said nothing".
    /// A repeated id keeps the first text, which makes the build order-independent.
    pub fn add(&mut self, osm_id: u64, text: &str) {
        if text.is_empty() || self.entries.iter().any(|&(id, _)| id == osm_id) {
            return;
        }
        let offset = match self.seen.get(text) {
            Some(&at) => at,
            None => {
                let at = self.blob.len() as u64;
                self.blob.extend_from_slice(text.as_bytes());
                self.blob.push(0);
                self.seen.insert(text.to_string(), at);
                at
            }
        };
        self.entries.push((osm_id, offset));
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Bytes the blob would occupy, i.e. the archive minus its index.
    pub fn blob_len(&self) -> usize {
        self.blob.len()
    }

    /// How many entries share text with an earlier one.
    pub fn shared(&self) -> usize {
        self.entries.len() - self.seen.len()
    }

    pub fn write(mut self, out: &mut impl Write) -> std::io::Result<()> {
        // Sorted here rather than demanded of the caller: the reader binary-searches, and a
        // generator that streams a dump has no reason to produce ids in order.
        self.entries.sort_unstable_by_key(|&(id, _)| id);
        out.write_all(MAGIC)?;
        out.write_all(&VERSION.to_le_bytes())?;
        out.write_all(&(self.entries.len() as u32).to_le_bytes())?;
        for (id, offset) in &self.entries {
            out.write_all(&id.to_le_bytes())?;
            out.write_all(&offset.to_le_bytes())?;
        }
        out.write_all(&self.blob)
    }
}

/// A parsed archive held in memory.
pub struct Archive {
    index: Vec<(u64, u64)>,
    blob: Vec<u8>,
}

impl Archive {
    pub fn parse(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() < HEADER_LEN {
            return Err(format!("too short to be a wikidesc archive: {} bytes", bytes.len()));
        }
        if &bytes[..8] != MAGIC {
            return Err("not a wikidesc archive: bad magic".to_string());
        }
        let version = u32::from_le_bytes(bytes[8..12].try_into().unwrap());
        if version != VERSION {
            return Err(format!("wikidesc version {version}, expected {VERSION}"));
        }
        let count = u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize;
        let blob_at = HEADER_LEN + count * ENTRY_LEN;
        if bytes.len() < blob_at {
            return Err(format!("index of {count} needs {blob_at} bytes, file has {}", bytes.len()));
        }
        let mut index = Vec::with_capacity(count);
        for i in 0..count {
            let at = HEADER_LEN + i * ENTRY_LEN;
            index.push((
                u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap()),
                u64::from_le_bytes(bytes[at + 8..at + 16].try_into().unwrap()),
            ));
        }
        Ok(Archive { index, blob: bytes[blob_at..].to_vec() })
    }

    pub fn read(source: &mut impl Read) -> Result<Self, String> {
        let mut bytes = Vec::new();
        source.read_to_end(&mut bytes).map_err(|e| e.to_string())?;
        Archive::parse(&bytes)
    }

    pub fn len(&self) -> usize {
        self.index.len()
    }

    pub fn is_empty(&self) -> bool {
        self.index.is_empty()
    }

    /// The description for `osm_id`, or `None`.
    ///
    /// Binary search, then read to the terminator. A truncated final run — a file cut short in
    /// transit — reads to the end rather than failing, because a partial sentence is still better
    /// than an error in a bottom sheet.
    pub fn get(&self, osm_id: u64) -> Option<&str> {
        let at = self.index.binary_search_by_key(&osm_id, |&(id, _)| id).ok()?;
        let start = self.index[at].1 as usize;
        if start >= self.blob.len() {
            return None;
        }
        let rest = &self.blob[start..];
        let end = rest.iter().position(|&b| b == 0).unwrap_or(rest.len());
        std::str::from_utf8(&rest[..end]).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn build(pairs: &[(u64, &str)]) -> Vec<u8> {
        let mut b = Builder::new();
        for &(id, text) in pairs {
            b.add(id, text);
        }
        let mut out = Vec::new();
        b.write(&mut out).expect("write");
        out
    }

    #[test]
    fn a_description_survives_a_round_trip() {
        let bytes = build(&[(1, "Paris is the capital of France."), (2, "Berlin is in Germany.")]);
        let a = Archive::parse(&bytes).expect("parse");
        assert_eq!(a.len(), 2);
        assert_eq!(a.get(1), Some("Paris is the capital of France."));
        assert_eq!(a.get(2), Some("Berlin is in Germany."));
        assert_eq!(a.get(3), None, "an id with no article");
    }

    /// Ids arrive in whatever order the dump yields them; the reader binary-searches, so the
    /// writer sorts.
    #[test]
    fn the_index_is_sorted_whatever_order_ids_arrive_in() {
        let bytes = build(&[(900, "nine"), (7, "seven"), (500, "five"), (1, "one")]);
        let a = Archive::parse(&bytes).expect("parse");
        let ids: Vec<u64> = a.index.iter().map(|&(id, _)| id).collect();
        assert_eq!(ids, vec![1, 7, 500, 900]);
        for (id, want) in [(1, "one"), (7, "seven"), (500, "five"), (900, "nine")] {
            assert_eq!(a.get(id), Some(want));
        }
    }

    /// The reason offsets are stored rather than lengths. Templated one-line leads repeat across a
    /// planet, and a repeat should cost 16 bytes of index, not a second copy of the text.
    #[test]
    fn identical_text_is_stored_once() {
        let shared = "A village in Timiș County, Romania.";
        let mut b = Builder::new();
        for id in 1..=100u64 {
            b.add(id, shared);
        }
        assert_eq!(b.len(), 100);
        assert_eq!(b.shared(), 99, "ninety-nine of them share the first copy");
        assert_eq!(b.blob_len(), shared.len() + 1, "one copy plus its terminator");

        let mut bytes = Vec::new();
        b.write(&mut bytes).expect("write");
        let a = Archive::parse(&bytes).expect("parse");
        assert_eq!(a.get(1), Some(shared));
        assert_eq!(a.get(100), Some(shared));
    }

    /// An entry resolving to nothing is worse than no entry: the caller cannot tell it from a real
    /// empty article.
    #[test]
    fn empty_text_is_not_stored() {
        let bytes = build(&[(1, ""), (2, "real")]);
        let a = Archive::parse(&bytes).expect("parse");
        assert_eq!(a.len(), 1);
        assert_eq!(a.get(1), None);
        assert_eq!(a.get(2), Some("real"));
    }

    #[test]
    fn a_repeated_id_keeps_the_first_text() {
        let bytes = build(&[(1, "first"), (1, "second")]);
        let a = Archive::parse(&bytes).expect("parse");
        assert_eq!(a.len(), 1);
        assert_eq!(a.get(1), Some("first"), "so a build does not depend on input order");
    }

    #[test]
    fn an_empty_archive_is_valid() {
        let bytes = build(&[]);
        let a = Archive::parse(&bytes).expect("parse");
        assert!(a.is_empty());
        assert_eq!(a.get(1), None);
    }

    #[test]
    fn utf8_survives_intact() {
        let text = "Săcălaz is a commune in Timiș County, Romania.";
        let bytes = build(&[(1, text)]);
        assert_eq!(Archive::parse(&bytes).expect("parse").get(1), Some(text));
    }

    #[test]
    fn a_file_that_is_not_an_archive_is_rejected_rather_than_misread() {
        assert!(Archive::parse(b"").is_err());
        assert!(Archive::parse(b"short").is_err());
        assert!(Archive::parse(b"NOTMAGIC\x01\0\0\0\0\0\0\0").is_err());
        // Right magic, wrong version.
        let mut wrong = MAGIC.to_vec();
        wrong.extend_from_slice(&99u32.to_le_bytes());
        wrong.extend_from_slice(&0u32.to_le_bytes());
        assert!(Archive::parse(&wrong).is_err());
        // A count the file cannot possibly hold.
        let mut lying = MAGIC.to_vec();
        lying.extend_from_slice(&VERSION.to_le_bytes());
        lying.extend_from_slice(&1000u32.to_le_bytes());
        assert!(Archive::parse(&lying).is_err());
    }

    /// A download cut short. A partial sentence beats an error in a bottom sheet.
    #[test]
    fn a_truncated_final_run_reads_to_the_end() {
        let mut bytes = build(&[(1, "a complete sentence.")]);
        bytes.truncate(bytes.len() - 5);
        let a = Archive::parse(&bytes).expect("the index is intact");
        assert_eq!(a.get(1), Some("a complete sente"));
    }
}
