//! String dictionaries and the column codec.
//!
//! ## The ordering contract
//!
//! A searchable dictionary must be sorted by **UTF-16 code unit**, because the reader binary
//! -searches it with a UTF-16 comparator (`cmp_utf16` in `geocoder.rs`). UTF-8 byte order and
//! UTF-16 order disagree for anything above the BMP: `U+10000` sorts *after* `U+FFFF` in UTF-8
//! but *before* it in UTF-16, because surrogates start at `0xD800`. Sorting the wrong way does
//! not corrupt anything visibly — forward lookups just start missing entries near the
//! disagreement.
//!
//! Non-searchable dictionaries (house numbers, postcodes) are never string-searched, only
//! displayed, so they are ordered by descending frequency instead. That puts the commonest
//! values at the smallest ids and keeps those columns near a byte per record.

use std::collections::HashMap;
use std::io::{self, Write};

use crate::format::{BLOCK, ZSTD_LEVEL};

// --------------------------------------------------------------------------- interner
/// Assigns a dense id to each distinct string, in first-seen order.
#[derive(Default)]
pub struct Interner {
    ids: HashMap<Box<str>, u32>,
    strings: Vec<Box<str>>,
    counts: Vec<u32>,
}

impl Interner {
    /// Empty interner.
    pub fn new() -> Interner {
        Interner::default()
    }

    /// Id for `s`, interning it if new. Counts occurrences for the frequency ordering.
    pub fn intern(&mut self, s: &str) -> u32 {
        if let Some(&id) = self.ids.get(s) {
            if let Some(c) = self.counts.get_mut(id as usize) {
                *c = c.saturating_add(1);
            }
            return id;
        }
        let id = self.strings.len() as u32;
        let boxed: Box<str> = s.into();
        let _ = self.ids.insert(boxed.clone(), id);
        self.strings.push(boxed);
        self.counts.push(1);
        id
    }

    /// Number of distinct strings.
    pub fn len(&self) -> usize {
        self.strings.len()
    }

    /// Whether nothing has been interned.
    pub fn is_empty(&self) -> bool {
        self.strings.is_empty()
    }

    /// The strings, by id.
    pub fn strings(&self) -> &[Box<str>] {
        &self.strings
    }

    /// Occurrence count per id.
    pub fn counts(&self) -> &[u32] {
        &self.counts
    }

    /// Final id order and the remap from interned id to final id.
    ///
    /// `searchable` picks the contract: UTF-16 order for dictionaries the reader binary-searches,
    /// descending frequency for the rest.
    pub fn finish(&self, searchable: bool) -> (Vec<&str>, Vec<u32>) {
        let mut order: Vec<u32> = (0..self.strings.len() as u32).collect();
        if searchable {
            order.sort_unstable_by(|&a, &b| {
                cmp_utf16(&self.strings[a as usize], &self.strings[b as usize])
            });
        } else {
            // Descending frequency, ties broken by UTF-16 order so the output is deterministic
            // rather than dependent on the order strings happened to be seen in.
            order.sort_unstable_by(|&a, &b| {
                self.counts[b as usize]
                    .cmp(&self.counts[a as usize])
                    .then_with(|| cmp_utf16(&self.strings[a as usize], &self.strings[b as usize]))
            });
        }
        let mut remap = vec![0u32; self.strings.len()];
        let mut sorted = Vec::with_capacity(self.strings.len());
        for (new_id, &old_id) in order.iter().enumerate() {
            remap[old_id as usize] = new_id as u32;
            sorted.push(&*self.strings[old_id as usize]);
        }
        (sorted, remap)
    }
}

/// Compare two `str`s as Java/Kotlin `String`s do: by UTF-16 code unit.
pub fn cmp_utf16(a: &str, b: &str) -> std::cmp::Ordering {
    let mut ai = a.encode_utf16();
    let mut bi = b.encode_utf16();
    loop {
        match (ai.next(), bi.next()) {
            (None, None) => return std::cmp::Ordering::Equal,
            (None, Some(_)) => return std::cmp::Ordering::Less,
            (Some(_), None) => return std::cmp::Ordering::Greater,
            (Some(x), Some(y)) if x == y => continue,
            (Some(x), Some(y)) => return x.cmp(&y),
        }
    }
}

// --------------------------------------------------------------------------- encoders
fn be32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_be_bytes());
}

fn be64(out: &mut Vec<u8>, v: i64) {
    out.extend_from_slice(&v.to_be_bytes());
}

fn zigzag(v: i32) -> u32 {
    ((v << 1) ^ (v >> 31)) as u32
}

fn write_varint(out: &mut Vec<u8>, mut v: u32) {
    loop {
        let b = (v & 0x7F) as u8;
        v >>= 7;
        if v == 0 {
            out.push(b);
            return;
        }
        out.push(b | 0x80);
    }
}

/// Encode a dictionary section body: `rawLen, compLen, zstd(count, [len, utf8]...)`.
pub fn encode_dict(strings: &[&str]) -> io::Result<Vec<u8>> {
    let mut raw = Vec::new();
    be32(&mut raw, strings.len() as u32);
    for s in strings {
        be32(&mut raw, s.len() as u32);
        raw.extend_from_slice(s.as_bytes());
    }
    let comp = zstd::encode_all(&raw[..], ZSTD_LEVEL)?;
    let mut out = Vec::with_capacity(comp.len() + 8);
    be32(&mut out, raw.len() as u32);
    be32(&mut out, comp.len() as u32);
    out.extend_from_slice(&comp);
    Ok(out)
}

/// Encode a column section body.
///
/// `rawLen`/`compLen` for every block come first as a directory, then the blocks. `prev` resets
/// at each block boundary, which is what makes a block independently decodable and is exactly
/// what the reader relies on to random-access a record without touching its neighbours.
///
/// Blocks are compressed in parallel. At level 19 zstd manages a couple of megabytes a second
/// per core, which on a planet-scale column is hours of the build spent in one thread; the
/// blocks are independent by construction, so this is the one place parallelism is free. The
/// results are reassembled in chunk order, so the output does not depend on the thread count.
pub fn encode_column(values: &[i32], delta: bool) -> io::Result<Vec<u8>> {
    let n = values.len();
    let blocks = n.div_ceil(BLOCK).max(usize::from(n == 0));

    let encoded: Vec<io::Result<(usize, Vec<u8>)>> =
        osm_ingest::par::map_chunks(values, BLOCK, |_start, chunk| {
            let mut raw = Vec::with_capacity(chunk.len() * 2);
            let mut prev = 0i32;
            for &v in chunk {
                let enc = if delta { v.wrapping_sub(prev) } else { v };
                write_varint(&mut raw, zigzag(enc));
                prev = v;
            }
            let comp = zstd::encode_all(&raw[..], ZSTD_LEVEL)?;
            Ok((raw.len(), comp))
        });

    let mut dir: Vec<(u32, u32)> = Vec::with_capacity(blocks);
    let mut bodies: Vec<u8> = Vec::new();
    for r in encoded {
        let (raw_len, comp) = r?;
        dir.push((raw_len as u32, comp.len() as u32));
        bodies.extend_from_slice(&comp);
    }
    // `map_chunks` yields nothing for an empty input, but the format still wants one empty
    // block so the directory and the reader's block count agree.
    if dir.is_empty() {
        let comp = zstd::encode_all(&[][..], ZSTD_LEVEL)?;
        dir.push((0, comp.len() as u32));
        bodies.extend_from_slice(&comp);
    }

    let mut out = Vec::with_capacity(bodies.len() + dir.len() * 8 + 8);
    be32(&mut out, n as u32);
    be32(&mut out, dir.len() as u32);
    for (raw_len, comp_len) in &dir {
        be32(&mut out, *raw_len);
        be32(&mut out, *comp_len);
    }
    out.extend_from_slice(&bodies);
    Ok(out)
}

/// Encode the grid section: `cellCount, [cellId, startRec]...`, uncompressed.
///
/// Left uncompressed because the reader's ring search probes it by binary search on open and
/// on every reverse lookup; a compressed directory would have to be inflated whole first.
pub fn encode_grid(cells: &[(i64, u32)]) -> Vec<u8> {
    let mut out = Vec::with_capacity(cells.len() * 12 + 4);
    be32(&mut out, cells.len() as u32);
    for &(cell, start) in cells {
        be64(&mut out, cell);
        be32(&mut out, start);
    }
    out
}

/// Write one section: a big-endian length, then the body.
pub fn write_section<W: Write>(out: &mut W, body: &[u8]) -> io::Result<u64> {
    out.write_all(&(body.len() as u32).to_be_bytes())?;
    out.write_all(body)?;
    Ok(body.len() as u64 + 4)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf16_order_differs_from_utf8_above_the_bmp() {
        // U+FFFF encodes as ef bf bf; U+10000 as f0 90 80 80. UTF-8 puts U+10000 second.
        let bmp = "\u{FFFF}";
        let astral = "\u{10000}";
        assert!(bmp.as_bytes() < astral.as_bytes(), "premise: UTF-8 puts the astral char second");
        // UTF-16 puts it first, because a surrogate pair starts at 0xD800.
        assert_eq!(cmp_utf16(astral, bmp), std::cmp::Ordering::Less);
    }

    #[test]
    fn utf16_order_agrees_with_the_obvious_cases() {
        use std::cmp::Ordering::*;
        assert_eq!(cmp_utf16("", "a"), Less);
        assert_eq!(cmp_utf16("a", "a"), Equal);
        assert_eq!(cmp_utf16("a", "b"), Less);
        assert_eq!(cmp_utf16("ab", "b"), Less);
        assert_eq!(cmp_utf16("Z", "a"), Less);
        assert_eq!(cmp_utf16("café", "cafe"), Greater);
    }

    #[test]
    fn searchable_dictionaries_come_out_in_utf16_order() {
        let mut i = Interner::new();
        for s in ["banana", "", "\u{10000}", "\u{FFFF}", "apple", "banana"] {
            let _ = i.intern(s);
        }
        let banana_old = i.intern("banana");
        let (sorted, remap) = i.finish(true);
        assert_eq!(sorted[0], "", "empty sorts first");
        for w in sorted.windows(2) {
            assert!(cmp_utf16(w[0], w[1]) == std::cmp::Ordering::Less, "{:?} !< {:?}", w[0], w[1]);
        }
        // The remap must send each interned id to where its string ended up.
        assert_eq!(sorted[remap[banana_old as usize] as usize], "banana");
    }

    #[test]
    fn frequency_dictionaries_put_the_commonest_value_at_id_zero() {
        let mut i = Interner::new();
        for _ in 0..10 {
            let _ = i.intern("1");
        }
        for _ in 0..3 {
            let _ = i.intern("22");
        }
        let _ = i.intern("333");
        let one_old = i.intern("1");
        let (sorted, remap) = i.finish(false);
        assert_eq!(sorted, vec!["1", "22", "333"]);
        assert_eq!(remap[one_old as usize], 0);
    }

    #[test]
    fn frequency_ties_are_broken_deterministically() {
        let mut a = Interner::new();
        let _ = a.intern("x");
        let _ = a.intern("y");
        let mut b = Interner::new();
        let _ = b.intern("y");
        let _ = b.intern("x");
        // Same multiset, different insertion order, same output.
        assert_eq!(a.finish(false).0, b.finish(false).0);
    }

    /// Decode a column the way the reader does, to check the encoder against something other
    /// than itself.
    fn decode_column(body: &[u8], delta: bool) -> Vec<i32> {
        let rd32 = |p: usize| -> u32 {
            u32::from_be_bytes([body[p], body[p + 1], body[p + 2], body[p + 3]])
        };
        let n = rd32(0) as usize;
        let blocks = rd32(4) as usize;
        let mut offset = 8 + blocks * 8;
        let mut out = Vec::with_capacity(n);
        for b in 0..blocks {
            let comp_len = rd32(8 + b * 8 + 4) as usize;
            let raw = zstd::decode_all(&body[offset..offset + comp_len]).unwrap();
            offset += comp_len;
            let mut p = 0usize;
            let mut prev = 0i32;
            let count = (n - b * BLOCK).min(BLOCK);
            for _ in 0..count {
                let mut v = 0u32;
                let mut shift = 0;
                loop {
                    let byte = raw[p];
                    p += 1;
                    v |= ((byte & 0x7F) as u32) << shift;
                    if byte & 0x80 == 0 {
                        break;
                    }
                    shift += 7;
                }
                let dec = ((v >> 1) as i32) ^ -((v & 1) as i32);
                let value = if delta { prev.wrapping_add(dec) } else { dec };
                out.push(value);
                prev = value;
            }
        }
        out
    }

    #[test]
    fn columns_round_trip_across_block_boundaries() {
        // Deliberately not a multiple of BLOCK, so the last block is short.
        let values: Vec<i32> = (0..BLOCK * 2 + 37).map(|i| (i as i32) * 7 - 1000).collect();
        for delta in [true, false] {
            let body = encode_column(&values, delta).unwrap();
            assert_eq!(decode_column(&body, delta), values, "delta={delta}");
        }
    }

    #[test]
    fn columns_round_trip_extremes_and_negatives() {
        let values = vec![0, -1, 1, i32::MIN, i32::MAX, -900_000_000, 1_800_000_000, 0];
        for delta in [true, false] {
            let body = encode_column(&values, delta).unwrap();
            assert_eq!(decode_column(&body, delta), values, "delta={delta}");
        }
    }

    #[test]
    fn an_empty_column_still_produces_a_readable_section() {
        let body = encode_column(&[], false).unwrap();
        assert_eq!(decode_column(&body, false), Vec::<i32>::new());
    }

    #[test]
    fn delta_encoding_actually_shrinks_a_sorted_column() {
        let values: Vec<i32> = (0..BLOCK).map(|i| 370_000_000 + i as i32).collect();
        let plain = encode_column(&values, false).unwrap().len();
        let delta = encode_column(&values, true).unwrap().len();
        assert!(delta < plain, "delta {delta} should beat plain {plain}");
    }

    #[test]
    fn dictionaries_round_trip() {
        let strings = vec!["", "Main Street", "café", "\u{10000}"];
        let body = encode_dict(&strings).unwrap();
        let raw_len = u32::from_be_bytes([body[0], body[1], body[2], body[3]]) as usize;
        let raw = zstd::decode_all(&body[8..]).unwrap();
        assert_eq!(raw.len(), raw_len);
        let count = u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]) as usize;
        assert_eq!(count, strings.len());
        let mut p = 4;
        for want in &strings {
            let len =
                u32::from_be_bytes([raw[p], raw[p + 1], raw[p + 2], raw[p + 3]]) as usize;
            p += 4;
            assert_eq!(std::str::from_utf8(&raw[p..p + len]).unwrap(), *want);
            p += len;
        }
    }

    #[test]
    fn the_grid_section_is_fixed_width_and_ascending() {
        let cells = vec![(0i64, 0u32), (5, 10), (7200, 25)];
        let body = encode_grid(&cells);
        assert_eq!(body.len(), 4 + cells.len() * 12);
        assert_eq!(u32::from_be_bytes([body[0], body[1], body[2], body[3]]), 3);
    }
}
