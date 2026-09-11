//! Resumable crawl state: which BSSIDs have been seen, and which are still to be queried.
//!
//! A planet crawl runs for weeks and will be interrupted — by a reboot, a network outage, or
//! somebody pressing Ctrl-C. Losing the frontier would mean restarting from the seed, so both
//! halves of the state are on disk and both are safe to reload mid-run.
//!
//! ## Why the seen-set is approximate
//!
//! An exact set of a billion 48-bit keys is tens of gigabytes of hash table. A Bloom filter of
//! the same cardinality is about 1.2 GB at a 1% false-positive rate, and the cost of a false
//! positive here is that one BSSID never gets queried directly. That is nearly free: the
//! crawl reaches almost every AP as a *neighbour* in somebody else's response, so a skipped
//! query loses a small amount of expansion, not the beacon. Trading a rare missed query for
//! twenty times less memory is the right way round.
//!
//! Records themselves are never deduplicated here — that is `wps_build`'s job, working over
//! the sorted whole, where it can be exact.

use std::fs::{File, OpenOptions};
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

/// Bits per expected element, giving roughly a 1% false-positive rate at two hashes.
const BITS_PER_ELEMENT: u64 = 10;

const SEEN_MAGIC: &[u8; 8] = b"WPSSEEN1";
const QUEUE_MAGIC: &[u8; 8] = b"WPSQUE01";

fn mix(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
    z ^ (z >> 31)
}

/// Approximate membership over the BSSIDs the crawl has already encountered.
pub struct SeenSet {
    bits: Vec<u64>,
    nbits: u64,
    inserted: u64,
    path: PathBuf,
}

impl SeenSet {
    /// Size for `expected` elements, or reload from `path` if it already holds a filter of the
    /// same size. A size change starts a fresh filter rather than misreading the old one.
    pub fn open(path: &Path, expected: u64) -> io::Result<SeenSet> {
        let nbits = (expected.max(1) * BITS_PER_ELEMENT).next_power_of_two();
        let words = (nbits / 64) as usize;

        if path.exists() {
            let mut f = BufReader::new(File::open(path)?);
            let mut head = [0u8; 24];
            if f.read_exact(&mut head).is_ok() && &head[0..8] == SEEN_MAGIC {
                let stored_bits = u64::from_le_bytes(
                    head[8..16].try_into().map_err(|_| bad("short seen header"))?,
                );
                let inserted = u64::from_le_bytes(
                    head[16..24].try_into().map_err(|_| bad("short seen header"))?,
                );
                if stored_bits == nbits {
                    let mut bits = vec![0u64; words];
                    let mut buf = [0u8; 8];
                    for w in bits.iter_mut() {
                        f.read_exact(&mut buf)?;
                        *w = u64::from_le_bytes(buf);
                    }
                    return Ok(SeenSet { bits, nbits, inserted, path: path.to_path_buf() });
                }
            }
        }
        Ok(SeenSet {
            bits: vec![0u64; words],
            nbits,
            inserted: 0,
            path: path.to_path_buf(),
        })
    }

    fn positions(&self, key: u64) -> (u64, u64) {
        let h1 = mix(key);
        let h2 = mix(key ^ 0x9E3779B97F4A7C15) | 1;
        (h1 % self.nbits, h2 % self.nbits)
    }

    /// Whether `key` has probably been seen. False positives are possible, false negatives are
    /// not, which is the direction that keeps the crawl from looping.
    pub fn contains(&self, key: u64) -> bool {
        let (a, b) = self.positions(key);
        self.get(a) && self.get(b)
    }

    /// Record `key`. Returns whether it was new.
    pub fn insert(&mut self, key: u64) -> bool {
        let (a, b) = self.positions(key);
        let had = self.get(a) && self.get(b);
        self.set(a);
        self.set(b);
        if !had {
            self.inserted += 1;
        }
        !had
    }

    fn get(&self, bit: u64) -> bool {
        self.bits.get((bit >> 6) as usize).is_some_and(|w| (w >> (bit & 63)) & 1 == 1)
    }

    fn set(&mut self, bit: u64) {
        if let Some(w) = self.bits.get_mut((bit >> 6) as usize) {
            *w |= 1 << (bit & 63);
        }
    }

    /// How many distinct keys have been inserted, as the filter counts them.
    pub fn len(&self) -> u64 {
        self.inserted
    }

    /// Whether nothing has been inserted.
    pub fn is_empty(&self) -> bool {
        self.inserted == 0
    }

    /// Estimated false-positive rate at the current load, for the crawl's status line.
    pub fn false_positive_rate(&self) -> f64 {
        let k = 2.0;
        let exp = -(k * self.inserted as f64) / self.nbits as f64;
        (1.0 - exp.exp()).powf(k)
    }

    /// Write the filter out. Writes to a sibling temp file and renames, so an interrupted
    /// checkpoint leaves the previous one intact rather than a half-written filter that would
    /// load as garbage.
    pub fn checkpoint(&self) -> io::Result<()> {
        let tmp = self.path.with_extension("tmp");
        {
            let mut f = BufWriter::new(File::create(&tmp)?);
            f.write_all(SEEN_MAGIC)?;
            f.write_all(&self.nbits.to_le_bytes())?;
            f.write_all(&self.inserted.to_le_bytes())?;
            for w in &self.bits {
                f.write_all(&w.to_le_bytes())?;
            }
            f.flush()?;
        }
        std::fs::rename(&tmp, &self.path)
    }
}

/// The queue of BSSIDs still to be queried: an append-only file plus a read cursor.
///
/// Append-only because the crawl's whole shape is "every answer adds more questions", and a
/// file that only grows at one end and is consumed at the other never needs rewriting. The
/// cursor is a separate small file so checkpointing it is atomic on its own.
pub struct Frontier {
    file: File,
    path: PathBuf,
    cursor_path: PathBuf,
    /// Index of the next entry to hand out.
    cursor: u64,
    /// Number of entries appended.
    len: u64,
}

impl Frontier {
    /// Open or create the queue at `path`, restoring the cursor from `path.cursor`.
    pub fn open(path: &Path) -> io::Result<Frontier> {
        let cursor_path = path.with_extension("cursor");
        let mut file =
            OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path)?;
        let size = file.seek(SeekFrom::End(0))?;

        if size == 0 {
            file.write_all(QUEUE_MAGIC)?;
        } else {
            let _ = file.seek(SeekFrom::Start(0))?;
            let mut magic = [0u8; 8];
            file.read_exact(&mut magic)?;
            if &magic != QUEUE_MAGIC {
                return Err(bad("frontier file is not a crawl queue"));
            }
        }
        let len = size.saturating_sub(8) / 8;

        let cursor = std::fs::read(&cursor_path)
            .ok()
            .and_then(|b| b.get(0..8).map(|s| s.to_vec()))
            .and_then(|b| b.try_into().ok())
            .map_or(0, u64::from_le_bytes)
            .min(len);

        Ok(Frontier { file, path: path.to_path_buf(), cursor_path, cursor, len })
    }

    /// Append one BSSID.
    pub fn push(&mut self, mac: u64) -> io::Result<()> {
        let _ = self.file.seek(SeekFrom::End(0))?;
        self.file.write_all(&mac.to_le_bytes())?;
        self.len += 1;
        Ok(())
    }

    /// Take up to `n` BSSIDs, advancing the cursor.
    pub fn take(&mut self, n: usize) -> io::Result<Vec<u64>> {
        let available = (self.len - self.cursor).min(n as u64) as usize;
        if available == 0 {
            return Ok(Vec::new());
        }
        let _ = self.file.seek(SeekFrom::Start(8 + self.cursor * 8))?;
        let mut buf = vec![0u8; available * 8];
        self.file.read_exact(&mut buf)?;
        self.cursor += available as u64;

        let mut out = Vec::with_capacity(available);
        for chunk in buf.chunks_exact(8) {
            let mut a = [0u8; 8];
            a.copy_from_slice(chunk);
            out.push(u64::from_le_bytes(a));
        }
        Ok(out)
    }

    /// Entries not yet handed out.
    pub fn pending(&self) -> u64 {
        self.len - self.cursor
    }

    /// Entries appended over the queue's whole life.
    pub fn total(&self) -> u64 {
        self.len
    }

    /// Flush the queue and persist the cursor.
    ///
    /// The queue is flushed *before* the cursor is written, so a crash between the two
    /// re-queries some BSSIDs rather than skipping them. Re-querying is harmless; skipping
    /// loses a branch of the crawl permanently.
    pub fn checkpoint(&mut self) -> io::Result<()> {
        self.file.sync_data()?;
        let tmp = self.cursor_path.with_extension("cursor.tmp");
        std::fs::write(&tmp, self.cursor.to_le_bytes())?;
        std::fs::rename(&tmp, &self.cursor_path)
    }

    /// Path of the queue file, for status output.
    pub fn path(&self) -> &Path {
        &self.path
    }
}

fn bad(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TempDir(PathBuf);
    impl TempDir {
        fn new(tag: &str) -> TempDir {
            let p = std::env::temp_dir().join(format!("wpsfront-{tag}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&p);
            std::fs::create_dir_all(&p).unwrap();
            TempDir(p)
        }
    }
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn seen_set_never_reports_a_false_negative() {
        let d = TempDir::new("seen");
        let mut s = SeenSet::open(&d.0.join("seen.bin"), 10_000).unwrap();
        let keys: Vec<u64> = (0..5000).map(|i| mix(i) & 0xFFFF_FFFF_FFFF).collect();
        for &k in &keys {
            let _ = s.insert(k);
        }
        for &k in &keys {
            assert!(s.contains(k), "{k:#x} was inserted but reads as absent");
        }
        assert!(s.len() > 4900, "insert count should track distinct keys");
    }

    #[test]
    fn seen_set_false_positive_rate_stays_near_the_design_point() {
        let d = TempDir::new("fp");
        let mut s = SeenSet::open(&d.0.join("seen.bin"), 20_000).unwrap();
        for i in 0..20_000u64 {
            let _ = s.insert(mix(i));
        }
        let mut fp = 0;
        for i in 0..20_000u64 {
            if s.contains(mix(i ^ 0xDEAD_BEEF_0000_0000)) {
                fp += 1;
            }
        }
        let rate = fp as f64 / 20_000.0;
        assert!(rate < 0.05, "false-positive rate {rate} is too high to be useful");
    }

    #[test]
    fn seen_set_survives_a_checkpoint_and_reload() {
        let d = TempDir::new("reload");
        let path = d.0.join("seen.bin");
        let keys: Vec<u64> = (0..1000).map(mix).collect();
        {
            let mut s = SeenSet::open(&path, 10_000).unwrap();
            for &k in &keys {
                let _ = s.insert(k);
            }
            s.checkpoint().unwrap();
        }
        let s = SeenSet::open(&path, 10_000).unwrap();
        assert_eq!(s.len(), 1000);
        for &k in &keys {
            assert!(s.contains(k));
        }
    }

    #[test]
    fn resizing_starts_a_fresh_filter_rather_than_misreading_the_old_one() {
        let d = TempDir::new("resize");
        let path = d.0.join("seen.bin");
        {
            let mut s = SeenSet::open(&path, 10_000).unwrap();
            let _ = s.insert(42);
            s.checkpoint().unwrap();
        }
        let s = SeenSet::open(&path, 10_000_000).unwrap();
        assert_eq!(s.len(), 0);
        assert!(s.is_empty());
    }

    #[test]
    fn frontier_hands_out_entries_in_order_and_resumes() {
        let d = TempDir::new("queue");
        let path = d.0.join("frontier.bin");
        {
            let mut f = Frontier::open(&path).unwrap();
            for i in 0..100u64 {
                f.push(i).unwrap();
            }
            assert_eq!(f.take(10).unwrap(), (0..10).collect::<Vec<_>>());
            assert_eq!(f.pending(), 90);
            f.checkpoint().unwrap();
        }
        let mut f = Frontier::open(&path).unwrap();
        assert_eq!(f.total(), 100);
        assert_eq!(f.pending(), 90);
        assert_eq!(f.take(5).unwrap(), (10..15).collect::<Vec<_>>());
    }

    #[test]
    fn an_uncheckpointed_cursor_repeats_work_rather_than_losing_it() {
        let d = TempDir::new("nocheck");
        let path = d.0.join("frontier.bin");
        {
            let mut f = Frontier::open(&path).unwrap();
            for i in 0..20u64 {
                f.push(i).unwrap();
            }
            f.checkpoint().unwrap();
            // Consume without checkpointing, then "crash".
            assert_eq!(f.take(8).unwrap().len(), 8);
        }
        let mut f = Frontier::open(&path).unwrap();
        assert_eq!(f.take(1).unwrap(), vec![0], "must re-query, not skip past");
    }

    #[test]
    fn taking_from_an_exhausted_frontier_yields_nothing() {
        let d = TempDir::new("drained");
        let mut f = Frontier::open(&d.0.join("frontier.bin")).unwrap();
        f.push(1).unwrap();
        assert_eq!(f.take(10).unwrap(), vec![1]);
        assert_eq!(f.take(10).unwrap(), Vec::<u64>::new());
        assert_eq!(f.pending(), 0);
    }

    #[test]
    fn a_foreign_file_is_refused() {
        let d = TempDir::new("foreign");
        let path = d.0.join("frontier.bin");
        std::fs::write(&path, b"not a queue at all").unwrap();
        assert!(Frontier::open(&path).is_err());
    }
}
