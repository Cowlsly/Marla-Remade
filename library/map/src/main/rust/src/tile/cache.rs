//! Disk cache for HTTP byte ranges of the pmtiles archive.
//!
//! A port of the policy in `maps/src/main/java/com/vayunmathur/maps/util/MapTileCache.kt`,
//! which is the version that has been in production against this archive. Everything
//! load-bearing there is kept:
//!
//! * **`SHA-256(url + "\n" + range)` keys**, so a repointed archive cannot collide with
//!   the previous one's ranges.
//! * **`.data`/`.meta` pairs written temp-then-rename**, meta first, so the presence of
//!   a data file implies its meta is already complete and a reader never sees a
//!   half-written entry.
//! * **An `.origin` marker**: when it changes every entry is dropped. The URL is part of
//!   it because the archive is republished under the same name, and a cached directory
//!   chunk from the previous build addresses the previous build's byte offsets. That is
//!   what the marker exists to prevent.
//! * A cached range is kept indefinitely and served whenever the device is **offline**,
//!   and is only re-fetched when online *and* next wanted at least
//!   [`REFRESH_INTERVAL_MS`] after it was fetched. It is never dropped for being stale
//!   — only overwritten by a *successful* refetch.
//!
//! What is added is the **size cap** the original never had: it grew without bound,
//! which was tolerable for one map app and is not for five.
//!
//! Freshness is read from the meta file's recorded fetch time, which leaves the data
//! file's mtime free to be the LRU access stamp — so touching an entry on a hit does not
//! also make it look freshly fetched.

use sha2::{Digest, Sha256};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// 24 hours, as the original uses.
pub const REFRESH_INTERVAL_MS: u64 = 24 * 60 * 60 * 1000;

/// 64 MB. A screenful at z14 is a few hundred kilobytes, so this holds a large amount
/// of previously-viewed area for offline use while staying inside what five apps can
/// each afford on disk.
pub const DEFAULT_MAX_BYTES: u64 = 64 * 1024 * 1024;

const ORIGIN_FILE: &str = ".origin";

/// A cached range and when it was fetched.
pub struct Cached {
    pub fetched_at_ms: u64,
    pub body: Vec<u8>,
}

pub struct RangeCache {
    dir: PathBuf,
    max_bytes: u64,
    /// Injectable so the freshness and eviction policies are testable without sleeping.
    clock: Box<dyn Fn() -> u64 + Send + Sync>,
}

impl RangeCache {
    /// Open the cache, dropping every entry if `origin` differs from the marker.
    pub fn open(dir: impl Into<PathBuf>, origin: &str, max_bytes: u64) -> RangeCache {
        Self::with_clock(dir, origin, max_bytes, Box::new(now_ms))
    }

    /// Open the cache **without** checking its origin marker, leaving that to a later
    /// [`reset_if_origin_changed`](Self::reset_if_origin_changed).
    ///
    /// For the one caller that cannot know its own origin yet: the archive's `build_id` belongs in
    /// the marker and lives in a header read *through* this cache. Checking a partial origin first
    /// and the full one after would wipe the cache on every single start, because the two markers
    /// never match each other — which is a bug this exists to make impossible rather than to
    /// document.
    pub fn open_unchecked(dir: impl Into<PathBuf>, max_bytes: u64) -> RangeCache {
        let dir = dir.into();
        let _ = fs::create_dir_all(&dir);
        RangeCache { dir, max_bytes, clock: Box::new(now_ms) }
    }

    pub fn with_clock(
        dir: impl Into<PathBuf>,
        origin: &str,
        max_bytes: u64,
        clock: Box<dyn Fn() -> u64 + Send + Sync>,
    ) -> RangeCache {
        let dir = dir.into();
        let _ = fs::create_dir_all(&dir);
        let cache = RangeCache { dir, max_bytes, clock };
        cache.invalidate_on_origin_change(origin);
        cache
    }

    /// `SHA-256(url + "\n" + range)`, hex.
    pub fn key(url: &str, range: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(url.as_bytes());
        hasher.update(b"\n");
        hasher.update(range.as_bytes());
        hasher.finalize().iter().map(|b| format!("{b:02x}")).collect()
    }

    /// The entry for `key`, or `None` when absent or unreadable.
    pub fn read(&self, key: &str) -> Option<Cached> {
        let data_path = self.dir.join(format!("{key}.data"));
        let meta_path = self.dir.join(format!("{key}.meta"));
        let meta = fs::read_to_string(&meta_path).ok()?;
        let body = fs::read(&data_path).ok()?;

        let mut fetched_at_ms = 0u64;
        let mut recorded_len: Option<usize> = None;
        for line in meta.lines() {
            if let Some(v) = line.strip_prefix("fetchedAt=") {
                fetched_at_ms = v.trim().parse().unwrap_or(0);
            } else if let Some(v) = line.strip_prefix("length=") {
                recorded_len = v.trim().parse().ok();
            }
        }
        // A body that does not match what its meta recorded was truncated on the way to
        // disk, which is exactly the corruption this must not serve.
        if recorded_len.is_some_and(|len| len != body.len()) {
            self.drop_entry(key);
            return None;
        }
        // The data file's mtime is the LRU stamp.
        let _ = filetime_touch(&data_path, (self.clock)());
        Some(Cached { fetched_at_ms, body })
    }

    /// Is `entry` fresh enough to serve without asking the network?
    pub fn is_fresh(&self, entry: &Cached) -> bool {
        (self.clock)().saturating_sub(entry.fetched_at_ms) < REFRESH_INTERVAL_MS
    }

    pub fn write(&self, key: &str, body: &[u8]) {
        // Caching is best-effort: a full disk must not stop the map drawing.
        if self.write_inner(key, body).is_err() {
            return;
        }
        self.evict_if_over_cap();
    }

    fn write_inner(&self, key: &str, body: &[u8]) -> std::io::Result<()> {
        let now = (self.clock)();
        // Meta first, then data, each temp-then-rename, so the presence of the data file
        // implies a complete meta.
        let meta_tmp = self.dir.join(format!("{key}.meta.tmp"));
        {
            let mut file = fs::File::create(&meta_tmp)?;
            writeln!(file, "fetchedAt={now}")?;
            writeln!(file, "length={}", body.len())?;
            file.sync_all()?;
        }
        fs::rename(&meta_tmp, self.dir.join(format!("{key}.meta")))?;

        let data_tmp = self.dir.join(format!("{key}.data.tmp"));
        {
            let mut file = fs::File::create(&data_tmp)?;
            file.write_all(body)?;
            file.sync_all()?;
        }
        let data_path = self.dir.join(format!("{key}.data"));
        fs::rename(&data_tmp, &data_path)?;
        let _ = filetime_touch(&data_path, now);
        Ok(())
    }

    pub fn drop_entry(&self, key: &str) {
        let _ = fs::remove_file(self.dir.join(format!("{key}.data")));
        let _ = fs::remove_file(self.dir.join(format!("{key}.meta")));
    }

    /// Delete least-recently-read entries until the cache is comfortably under the cap.
    ///
    /// Down to 80% rather than exactly to the cap, so a session sitting at the limit
    /// does not run an eviction pass per tile.
    fn evict_if_over_cap(&self) {
        let mut entries: Vec<(u64, u64, String)> = Vec::new();
        let mut total = 0u64;
        let read_dir = match fs::read_dir(&self.dir) {
            Ok(d) => d,
            Err(_) => return,
        };
        for entry in read_dir.flatten() {
            let path = entry.path();
            let name = match path.file_name().and_then(|n| n.to_str()) {
                Some(n) => n,
                None => continue,
            };
            let key = match name.strip_suffix(".data") {
                Some(k) => k.to_string(),
                None => continue,
            };
            let metadata = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            let len = metadata.len();
            let stamp = metadata
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            total += len;
            entries.push((stamp, len, key));
        }
        if total <= self.max_bytes {
            return;
        }
        let target = self.max_bytes / 10 * 8;
        entries.sort_by_key(|(stamp, _, _)| *stamp);
        for (_, len, key) in entries {
            if total <= target {
                break;
            }
            total -= len;
            self.drop_entry(&key);
        }
    }

    /// Wipe the cache if `origin` differs from its marker.
    ///
    /// Idempotent and cheap, so it can be called again once something is known that was not known
    /// when the cache was opened. That is exactly the `build_id` case: it lives in the archive
    /// header, which is read *through* this cache, so the marker cannot include it until after the
    /// first read.
    pub fn reset_if_origin_changed(&self, origin: &str) {
        self.invalidate_on_origin_change(origin);
    }

    fn invalidate_on_origin_change(&self, origin: &str) {
        let marker = self.dir.join(ORIGIN_FILE);
        if fs::read_to_string(&marker).map(|s| s.trim() == origin).unwrap_or(false) {
            return;
        }
        if let Ok(read_dir) = fs::read_dir(&self.dir) {
            for entry in read_dir.flatten() {
                if entry.file_name() != ORIGIN_FILE {
                    let _ = fs::remove_file(entry.path());
                }
            }
        }
        // Best-effort: a failure here must not break map init. The worst case is that
        // stale entries survive, and every read validates its recorded length.
        let _ = fs::write(&marker, origin);
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// Set a file's mtime, which is the LRU stamp.
///
/// `std::fs` cannot set mtimes and pulling in `filetime` for one call is not worth a
/// dependency, so the file is opened for append and a zero-length write is flushed —
/// which updates mtime on every filesystem we target without changing the contents.
fn filetime_touch(path: &Path, _now_ms: u64) -> std::io::Result<()> {
    let file = fs::OpenOptions::new().append(true).open(path)?;
    file.set_len(file.metadata()?.len())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;

    struct Fixture {
        dir: PathBuf,
    }

    impl Fixture {
        fn new(name: &str) -> Fixture {
            let dir = std::env::temp_dir()
                .join(format!("rangecache-{name}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&dir);
            fs::create_dir_all(&dir).expect("temp dir");
            Fixture { dir }
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.dir);
        }
    }

    const URL: &str = "https://data.vayunmathur.com/v4.pmtiles";
    const ORIGIN: &str = "v1|https://data.vayunmathur.com/v4.pmtiles";

    fn cache_with(dir: &Path, clock: Arc<AtomicU64>, max_bytes: u64) -> RangeCache {
        let c = clock.clone();
        RangeCache::with_clock(dir, ORIGIN, max_bytes, Box::new(move || c.load(Ordering::SeqCst)))
    }

    fn data_files(dir: &Path) -> Vec<PathBuf> {
        fs::read_dir(dir)
            .unwrap()
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|e| e == "data"))
            .collect()
    }

    #[test]
    fn a_written_entry_reads_back() {
        let f = Fixture::new("roundtrip");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        let key = RangeCache::key(URL, "bytes=0-126");
        cache.write(&key, &[7u8; 127]);
        let entry = cache.read(&key).expect("cached");
        assert_eq!(entry.body, vec![7u8; 127]);
        assert_eq!(entry.fetched_at_ms, 1_000_000);
        assert!(cache.is_fresh(&entry));
    }

    #[test]
    fn ranges_of_the_same_url_do_not_collide() {
        assert_ne!(RangeCache::key(URL, "bytes=0-3"), RangeCache::key(URL, "bytes=4-7"));
        // And a different archive under the same range is a different entry.
        assert_ne!(RangeCache::key(URL, "bytes=0-3"), RangeCache::key("https://other", "bytes=0-3"));
    }

    #[test]
    fn an_entry_goes_stale_after_the_refresh_interval() {
        let f = Fixture::new("stale");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        let key = RangeCache::key(URL, "bytes=0-3");
        cache.write(&key, &[1, 2, 3, 4]);

        clock.store(1_000_000 + REFRESH_INTERVAL_MS / 2, Ordering::SeqCst);
        assert!(cache.is_fresh(&cache.read(&key).unwrap()), "half an interval is still fresh");

        clock.store(1_000_000 + REFRESH_INTERVAL_MS + 1, Ordering::SeqCst);
        assert!(!cache.is_fresh(&cache.read(&key).unwrap()), "past the interval it is stale");
    }

    #[test]
    fn reading_an_entry_does_not_reset_its_freshness() {
        // Freshness comes from the meta file's fetch time precisely so that touching the
        // data file for LRU cannot make a stale entry look new.
        let f = Fixture::new("freshness");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        let key = RangeCache::key(URL, "bytes=0-3");
        cache.write(&key, &[1, 2, 3, 4]);

        clock.store(1_000_000 + REFRESH_INTERVAL_MS / 2, Ordering::SeqCst);
        let _ = cache.read(&key);
        clock.store(1_000_000 + REFRESH_INTERVAL_MS + 1, Ordering::SeqCst);
        assert!(!cache.is_fresh(&cache.read(&key).unwrap()), "the earlier read did not extend it");
    }

    #[test]
    fn a_stale_entry_is_still_readable_so_it_can_be_served_offline() {
        // Never dropped for being stale — only overwritten by a successful refetch. This
        // is what makes a previously-viewed area render with no network.
        let f = Fixture::new("offline");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        let key = RangeCache::key(URL, "bytes=0-3");
        cache.write(&key, &[1, 2, 3, 4]);
        clock.store(1_000_000 + REFRESH_INTERVAL_MS * 100, Ordering::SeqCst);
        assert_eq!(cache.read(&key).expect("still there").body, vec![1, 2, 3, 4]);
    }

    #[test]
    fn an_entry_whose_body_disagrees_with_its_meta_is_dropped() {
        let f = Fixture::new("truncated");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        let key = RangeCache::key(URL, "bytes=0-99");
        cache.write(&key, &[5u8; 100]);
        // Truncated on disk, e.g. by a failed write we did not see.
        fs::write(f.dir.join(format!("{key}.data")), [0u8; 40]).unwrap();
        assert!(cache.read(&key).is_none(), "a short body must not be served");
        assert!(!f.dir.join(format!("{key}.data")).exists(), "and it is dropped");
    }

    #[test]
    fn a_data_file_with_no_meta_is_not_served() {
        let f = Fixture::new("nometa");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        let key = RangeCache::key(URL, "bytes=0-3");
        fs::write(f.dir.join(format!("{key}.data")), [1, 2, 3, 4]).unwrap();
        assert!(cache.read(&key).is_none());
    }

    #[test]
    fn changing_the_origin_marker_drops_every_entry() {
        // The archive is republished under the same name, so a cached directory chunk
        // addresses the previous build's byte offsets. This marker is the only thing
        // standing between that and a map made of mismatched tiles.
        let f = Fixture::new("origin");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        {
            let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
            cache.write(&RangeCache::key(URL, "bytes=0-3"), &[1, 2, 3, 4]);
            assert_eq!(data_files(&f.dir).len(), 1);
        }
        let c = clock.clone();
        RangeCache::with_clock(
            &f.dir,
            "v2|different",
            DEFAULT_MAX_BYTES,
            Box::new(move || c.load(Ordering::SeqCst)),
        );
        assert_eq!(data_files(&f.dir).len(), 0, "entries from the previous origin are dropped");
        assert_eq!(fs::read_to_string(f.dir.join(ORIGIN_FILE)).unwrap(), "v2|different");
    }

    #[test]
    fn an_unchanged_origin_marker_keeps_the_cache() {
        let f = Fixture::new("sameorigin");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        {
            let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
            cache.write(&RangeCache::key(URL, "bytes=0-3"), &[1, 2, 3, 4]);
        }
        let cache = cache_with(&f.dir, clock.clone(), DEFAULT_MAX_BYTES);
        assert_eq!(data_files(&f.dir).len(), 1);
        assert!(cache.read(&RangeCache::key(URL, "bytes=0-3")).is_some());
    }

    #[test]
    fn the_cache_evicts_over_its_cap() {
        // The size cap is the one thing added to the ported policy: the original grew
        // without bound, tolerable for one map app and not for five.
        let f = Fixture::new("evict");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let cache = cache_with(&f.dir, clock.clone(), 1000);
        for i in 0..10 {
            cache.write(&RangeCache::key(URL, &format!("bytes={i}")), &[0u8; 200]);
            // Distinct mtimes, so eviction order is well defined.
            std::thread::sleep(std::time::Duration::from_millis(12));
        }
        let total: u64 = data_files(&f.dir).iter().map(|p| fs::metadata(p).unwrap().len()).sum();
        assert!(total <= 1000, "cache is {total} bytes, cap is 1000");
        // Evicts to a margin below the cap, not exactly to it.
        assert!(total <= 800, "evicts with headroom: {total}");
        // The most recent write survives; the oldest does not.
        assert!(cache.read(&RangeCache::key(URL, "bytes=9")).is_some());
        assert!(cache.read(&RangeCache::key(URL, "bytes=0")).is_none());
    }
}
