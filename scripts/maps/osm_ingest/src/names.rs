//! Deduplicating NUL-terminated string pool.
//!
//! Both `road_names.bin` and `poi_names.bin` use the same convention: every
//! unique string is stored exactly once, NUL-terminated and concatenated, and a
//! "name offset" is the byte offset of the string's first byte. The device reads
//! a name by scanning forward from that offset to the NUL.
//!
//! Interning is single-threaded on purpose. The parallel passes accumulate names
//! per work chunk and the chunks are merged in index order, so a name's offset
//! depends only on the input file — not on thread scheduling, which is what made
//! the old C++ generator's `road_names.bin` differ between two runs of itself.

use std::collections::HashMap;
use std::io::{self, Write};

/// Sentinel meaning "no name" in a `name_offset` field.
pub const NO_NAME: u32 = 0xFFFF_FFFF;

pub struct NamePool<W: Write> {
    offsets: HashMap<Vec<u8>, u32>,
    next: u32,
    out: W,
}

impl<W: Write> NamePool<W> {
    pub fn new(out: W) -> Self {
        NamePool {
            offsets: HashMap::new(),
            next: 0,
            out,
        }
    }

    /// Offset of `name`, appending it to the pool the first time it is seen.
    ///
    /// Fails rather than wrapping once the pool would pass [`NO_NAME`]: a name
    /// offset is a `u32` in the on-disk edge record, so the format caps the pool
    /// at 4 GiB, and a wrapped `next` would hand every later name an offset that
    /// points into the middle of an earlier string. The failure mode is
    /// permanently wrong street names on device, which no later stage can detect.
    pub fn intern(&mut self, name: &[u8]) -> io::Result<u32> {
        if let Some(off) = self.offsets.get(name) {
            return Ok(*off);
        }
        let off = self.next;
        let grown = u32::try_from(name.len() as u64 + 1)
            .ok()
            .and_then(|n| off.checked_add(n))
            .filter(|n| *n < NO_NAME)
            .ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("name pool would pass {NO_NAME} bytes at offset {off}"),
                )
            })?;
        self.out.write_all(name)?;
        self.out.write_all(&[0])?;
        self.next = grown;
        self.offsets.insert(name.to_vec(), off);
        Ok(off)
    }

    pub fn unique_count(&self) -> usize {
        self.offsets.len()
    }

    pub fn byte_len(&self) -> u32 {
        self.next
    }

    pub fn finish(mut self) -> io::Result<W> {
        self.out.flush()?;
        Ok(self.out)
    }

    /// Start the pool near its ceiling, so the overflow guard can be tested
    /// without writing 4 GiB of names.
    #[cfg(test)]
    fn seek_to(&mut self, next: u32) {
        self.next = next;
    }
}

/// Per-chunk name table used by the parallel passes: it maps a name to a
/// chunk-local id, and the chunk's names are later interned into the real pool
/// in local-id order.
#[derive(Default)]
pub struct LocalNames {
    ids: HashMap<Vec<u8>, u32>,
    pub names: Vec<Vec<u8>>,
}

impl LocalNames {
    pub fn id(&mut self, name: &[u8]) -> u32 {
        if let Some(id) = self.ids.get(name) {
            return *id;
        }
        let id = self.names.len() as u32;
        self.names.push(name.to_vec());
        self.ids.insert(name.to_vec(), id);
        id
    }

    /// Intern every local name into `pool`, returning local id -> pool offset.
    pub fn flush<W: Write>(&self, pool: &mut NamePool<W>) -> io::Result<Vec<u32>> {
        self.names.iter().map(|n| pool.intern(n)).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dedupes_and_nul_terminates() {
        let mut pool = NamePool::new(Vec::new());
        assert_eq!(pool.intern(b"Main St").unwrap(), 0);
        assert_eq!(pool.intern(b"Oak Ave").unwrap(), 8);
        assert_eq!(pool.intern(b"Main St").unwrap(), 0, "second hit reuses");
        assert_eq!(pool.intern(b"").unwrap(), 16, "empty name still costs its NUL");
        assert_eq!(pool.unique_count(), 3);
        assert_eq!(pool.byte_len(), 17);
        assert_eq!(pool.finish().unwrap(), b"Main St\0Oak Ave\0\0".to_vec());
    }

    #[test]
    fn the_pool_refuses_to_wrap_past_the_u32_offset_ceiling() {
        let mut pool = NamePool::new(io::sink());
        pool.seek_to(NO_NAME - 8);
        // Six bytes plus a NUL lands one short of the sentinel, which is still a
        // usable offset.
        assert_eq!(pool.intern(b"Ada St").unwrap(), NO_NAME - 8);
        assert_eq!(pool.byte_len(), NO_NAME - 1);
        // The next name would wrap into offsets earlier names already own.
        let err = pool.intern(b"Bee St").unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        // A name that would land exactly on the sentinel is refused too.
        let mut pool = NamePool::new(io::sink());
        pool.seek_to(NO_NAME - 1);
        assert!(pool.intern(b"").is_err());
        // ...and the pool is unchanged, so the offsets already handed out stay
        // valid for whatever the caller does with the error.
        assert_eq!(pool.byte_len(), NO_NAME - 1);
        assert_eq!(pool.unique_count(), 0);
    }

    #[test]
    fn local_names_map_to_pool_offsets_in_local_order() {
        let mut a = LocalNames::default();
        assert_eq!(a.id(b"B"), 0);
        assert_eq!(a.id(b"A"), 1);
        assert_eq!(a.id(b"B"), 0);

        let mut b = LocalNames::default();
        assert_eq!(b.id(b"A"), 0);
        assert_eq!(b.id(b"C"), 1);

        let mut pool = NamePool::new(Vec::new());
        // Chunk order decides offsets: B, A, then C (A already interned).
        assert_eq!(a.flush(&mut pool).unwrap(), vec![0, 2]);
        assert_eq!(b.flush(&mut pool).unwrap(), vec![2, 4]);
        assert_eq!(pool.finish().unwrap(), b"B\0A\0C\0".to_vec());
    }
}
