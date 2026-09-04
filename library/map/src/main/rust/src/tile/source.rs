//! The byte source under the PMTiles reader: the disk cache in front of Kotlin's HTTP
//! stack.
//!
//! # The policy
//!
//! Ported from `maps/.../util/MapTileCache.kt`, the version that has been in production
//! against this archive:
//!
//! * A cached range is served without touching the network when it is **fresh**, or
//!   whenever the device is **offline** — stale but usable, so a previously-viewed area
//!   keeps rendering with no network.
//! * A failed fetch falls back to a stale entry rather than leaving a hole in the map.
//! * **Only a 206 whose body is exactly the requested length is stored.** A `200`
//!   whole-file reply to a range request is what produced the "Prefix string too short"
//!   pmtiles failures the original records at `MapTileCache.kt:287-290`; caching one
//!   poisons every later read of that range.
//!
//! # Why HTTP goes back out through Kotlin
//!
//! `library/jni-http` is a *"flat-frame JNI HTTP bridge shared by the Rust
//! extractors — no object creation on the hot path"*. Using it means range requests are
//! served by `:library:network` and keep its reduced CA bundle and
//! `HttpURLConnection`-only policy, and this crate carries no HTTP client, no TLS stack
//! and no second trust store.

use crate::tile::cache::RangeCache;
use tilecodec::proto::{err, Result};
use tilecodec::stream::RangeReader;

/// The self-hosted basemap archive: Protomaps v4 schema, z0-16.
///
/// The same file `maps` streams through MapLibre (`MapTileCache.BASEMAP_PMTILES_URL`),
/// without the `pmtiles://` scheme prefix MapLibre needs to route it.
pub const BASEMAP_PMTILES_URL: &str = "https://data.vayunmathur.com/v4.pmtiles";

/// Where this renderer reads its tiles from.
///
/// Planet cutover: now points at the live planet.mamaps uploaded to
/// https://data.vayunmathur.com/planet.mamaps (67.85 GB, build_id 0x2e729b4449d0b1cc).
/// BASEMAP_PMTILES_URL is kept as revert fallback.
pub const BASEMAP_ARCHIVE_URL: &str = "https://data.vayunmathur.com/planet.mamaps";

/// Bumped when the on-disk cache layout changes, so old entries are dropped rather than
/// misread. The URL is in the marker too, because the archive is republished under the
/// same name and a cached directory chunk addresses the byte offsets of the build it came
/// from.
pub const CACHE_FORMAT: &str = "v1";

/// The cache's origin marker: the layout version, the URL, and the archive's own `build_id`.
///
/// `build_id` is what makes republishing under a stable name safe. The URL alone is not enough — it
/// is deliberately the *same* URL every build, so a cached leaf index would otherwise keep
/// addressing byte offsets from the build it came from, and a user would sit on a stale map forever
/// with no way to notice.
///
/// # Why it is checked after the archive opens rather than before
///
/// The id lives in the archive header, and the header is read *through* this cache. So the cache is
/// opened with [`RangeCache::open_unchecked`], the archive opens, and *then* this marker is compared
/// once — see [`CachingRangeReader::reset_origin`]. Checking a partial marker first and this one
/// after would wipe the cache on every start, because the two can never match.
///
/// A prefix served from a stale cache entry reports the stale id, which is correct-but-late: that
/// entry is refetched within the refresh interval and the wipe happens then. Either way it costs no
/// extra request, because the header is already in the prefix a reader must fetch to open the
/// archive at all.
pub fn basemap_origin(url: &str, build_id: u64) -> String {
    format!("{CACHE_FORMAT}|{url}|{build_id:#018x}")
}

/// What a range fetch returned.
pub struct RangeResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

/// Fetches a byte range. Separated from the policy so the policy is testable without a
/// JVM.
pub trait RangeFetcher {
    fn fetch(&self, url: &str, range: &str) -> Result<RangeResponse>;
}

pub struct CachingRangeReader<F: RangeFetcher> {
    url: String,
    cache: RangeCache,
    fetcher: F,
    /// Set from Kotlin, which owns the `ConnectivityManager`. When offline the network is
    /// not attempted at all and a stale entry is served instead.
    online: std::sync::atomic::AtomicBool,
}

impl<F: RangeFetcher> CachingRangeReader<F> {
    pub fn new(url: impl Into<String>, cache: RangeCache, fetcher: F) -> Self {
        CachingRangeReader {
            url: url.into(),
            cache,
            fetcher,
            online: std::sync::atomic::AtomicBool::new(true),
        }
    }

    pub fn set_online(&self, online: bool) {
        self.online.store(online, std::sync::atomic::Ordering::Relaxed);
    }

    /// Re-check the cache's origin marker, now that the archive's `build_id` is known.
    ///
    /// See [`basemap_origin`] for why this is a second step rather than part of opening the cache.
    pub fn reset_origin(&self, origin: &str) {
        self.cache.reset_if_origin_changed(origin);
    }

    fn is_online(&self) -> bool {
        self.online.load(std::sync::atomic::Ordering::Relaxed)
    }
}

impl<F: RangeFetcher> RangeReader for CachingRangeReader<F> {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        if length == 0 {
            return Ok(Vec::new());
        }
        let range = format!("bytes={}-{}", offset, offset + length as u64 - 1);
        let key = RangeCache::key(&self.url, &range);

        let cached = self.cache.read(&key);
        if let Some(entry) = &cached {
            if self.cache.is_fresh(entry) || !self.is_online() {
                return Ok(entry.body.clone());
            }
        }

        let response = match self.fetcher.fetch(&self.url, &range) {
            Ok(r) => r,
            Err(e) => {
                // Went offline mid-session: a stale entry is far better than a hole.
                if let Some(entry) = cached {
                    return Ok(entry.body);
                }
                return Err(e);
            }
        };

        if !(200..300).contains(&response.status) {
            if let Some(entry) = cached {
                return Ok(entry.body);
            }
            return err(format!(
                "range request for {range} of {} failed with HTTP {}",
                self.url, response.status
            ));
        }
        // Only a body we trust: a 206 partial whose length matches the request.
        if response.status == 206 && response.body.len() == length as usize {
            self.cache.write(&key, &response.body);
        }
        Ok(response.body)
    }
}

/// A local file as a [`RangeReader`], for `file://` archives pushed to the device.
///
/// Each worker opens its **own** handle, so `read(offset, length)` is `read_at` and needs no
/// seek lock. Placed here (beside `CachingRangeReader`) so the bridge can choose between this
/// and the network path without the codec crate depending on Android.
///
/// Short reads are returned short — the same contract `CachingRangeReader` and the `RangeReader`
/// trait use — and `exact` in `mamaps::read` turns a short body into a parse error.
pub struct FileRangeReader {
    file: std::fs::File,
}

impl FileRangeReader {
    pub fn open(path: &std::path::Path) -> Result<Self> {
        let file = std::fs::File::open(path)
            .map_err(|e| tilecodec::proto::Error(format!("cannot open local archive {}: {e}", path.display())))?;
        Ok(FileRangeReader { file })
    }
}

impl tilecodec::stream::RangeReader for FileRangeReader {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        if length == 0 {
            return Ok(Vec::new());
        }
        self.read_at(offset, length)
    }
}

#[cfg(target_os = "android")]
impl FileRangeReader {
    fn read_at(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        use std::os::unix::fs::FileExt;
        let mut buf = vec![0u8; length as usize];
        let mut got = 0usize;
        while got < buf.len() {
            let n = self.file.read_at(&mut buf[got..], offset + got as u64).map_err(|e| {
                tilecodec::proto::Error(format!("local archive read at {offset}+{got} failed: {e}"))
            })?;
            if n == 0 {
                break;
            }
            got += n;
        }
        buf.truncate(got);
        Ok(buf)
    }
}

#[cfg(not(target_os = "android"))]
impl FileRangeReader {
    fn read_at(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        use std::io::{Read, Seek, SeekFrom};
        let mut file = &self.file;
        file.seek(SeekFrom::Start(offset))
            .map_err(|e| tilecodec::proto::Error(format!("local archive seek at {offset} failed: {e}")))?;
        let mut buf = vec![0u8; length as usize];
        let mut got = 0usize;
        while got < buf.len() {
            let n = file.read(&mut buf[got..])
                .map_err(|e| tilecodec::proto::Error(format!("local archive read at {offset}+{got} failed: {e}")))?;
            if n == 0 {
                break;
            }
            got += n;
        }
        buf.truncate(got);
        Ok(buf)
    }
}

/// A [`RangeFetcher`] over `library/jni-http`, and so over `:library:network`.
#[cfg(target_os = "android")]
pub struct JniRangeFetcher;

#[cfg(target_os = "android")]
impl RangeFetcher for JniRangeFetcher {
    fn fetch(&self, url: &str, range: &str) -> Result<RangeResponse> {
        // `Header` is an owned `(String, String)` pair, and `body` is `Option`, not a
        // slice: a GET has none.
        let headers = [("Range".to_string(), range.to_string())];
        match jni_http::request(jni_http::Method::Get, url, &headers, None) {
            Ok(response) => {
                // status 0 means the request never completed — a transport error the
                // bridge reports in-band rather than as an Err.
                if response.status == 0 {
                    let detail = response.error().unwrap_or_else(|| "transport failure".into());
                    return err(format!("range request for {range} of {url} failed: {detail}"));
                }
                Ok(RangeResponse { status: response.status, body: response.body })
            }
            Err(e) => err(format!("range request for {range} of {url} failed: {e:?}")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;

    struct Fixture {
        dir: PathBuf,
    }

    impl Fixture {
        fn new(name: &str) -> Fixture {
            let dir = std::env::temp_dir()
                .join(format!("rangesource-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).expect("temp dir");
            Fixture { dir }
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// Records every range asked for, and answers with a canned status and body.
    struct Fake {
        status: u16,
        body: Vec<u8>,
        fail: bool,
        ranges: RefCell<Vec<String>>,
    }

    impl Fake {
        fn ok(body: Vec<u8>) -> Fake {
            Fake { status: 206, body, fail: false, ranges: RefCell::new(Vec::new()) }
        }
    }

    impl RangeFetcher for Fake {
        fn fetch(&self, _url: &str, range: &str) -> Result<RangeResponse> {
            self.ranges.borrow_mut().push(range.to_string());
            if self.fail {
                return err("no route to host");
            }
            Ok(RangeResponse { status: self.status, body: self.body.clone() })
        }
    }

    const URL: &str = BASEMAP_PMTILES_URL;

    fn reader(dir: &std::path::Path, clock: Arc<AtomicU64>, fetcher: Fake) -> CachingRangeReader<Fake> {
        let c = clock.clone();
        let cache = RangeCache::with_clock(
            dir,
            "v1|test",
            crate::tile::cache::DEFAULT_MAX_BYTES,
            Box::new(move || c.load(Ordering::SeqCst)),
        );
        CachingRangeReader::new(URL, cache, fetcher)
    }

    #[test]
    fn the_range_header_names_the_exact_byte_range() {
        let f = Fixture::new("header");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let r = reader(&f.dir, clock, Fake::ok(vec![0u8; 127]));
        r.read(0, 127).expect("read");
        assert_eq!(r.fetcher.ranges.borrow()[0], "bytes=0-126");
        r.read(2_409_278, 4).ok();
        assert_eq!(r.fetcher.ranges.borrow()[1], "bytes=2409278-2409281");
    }

    #[test]
    fn a_fresh_entry_is_served_without_touching_the_network() {
        let f = Fixture::new("fresh");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        {
            let r = reader(&f.dir, clock.clone(), Fake::ok(vec![7u8; 16]));
            assert_eq!(r.read(0, 16).unwrap(), vec![7u8; 16]);
            assert_eq!(r.fetcher.ranges.borrow().len(), 1);
        }
        // A second reader over the same directory: the entry is on disk and fresh.
        let r = reader(&f.dir, clock, Fake::ok(vec![9u8; 16]));
        assert_eq!(r.read(0, 16).unwrap(), vec![7u8; 16], "the cached bytes, not the new ones");
        assert!(r.fetcher.ranges.borrow().is_empty(), "no request at all");
    }

    #[test]
    fn a_stale_entry_is_refetched_when_online() {
        let f = Fixture::new("refetch");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        {
            let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 4]));
            r.read(0, 4).unwrap();
        }
        clock.fetch_add(crate::tile::cache::REFRESH_INTERVAL_MS + 1, Ordering::SeqCst);
        let r = reader(&f.dir, clock, Fake::ok(vec![2u8; 4]));
        assert_eq!(r.read(0, 4).unwrap(), vec![2u8; 4], "the refreshed bytes");
        assert_eq!(r.fetcher.ranges.borrow().len(), 1);
    }

    #[test]
    fn a_stale_entry_is_served_offline_rather_than_failing() {
        // The whole point of the disk cache.
        let f = Fixture::new("offline");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        {
            let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 4]));
            r.read(0, 4).unwrap();
        }
        clock.fetch_add(crate::tile::cache::REFRESH_INTERVAL_MS * 10, Ordering::SeqCst);
        let r = reader(&f.dir, clock, Fake::ok(vec![2u8; 4]));
        r.set_online(false);
        assert_eq!(r.read(0, 4).unwrap(), vec![1u8; 4], "the stale bytes");
        assert!(r.fetcher.ranges.borrow().is_empty(), "offline must not attempt a request");
    }

    #[test]
    fn a_failed_fetch_falls_back_to_a_stale_entry() {
        let f = Fixture::new("failover");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        {
            let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 4]));
            r.read(0, 4).unwrap();
        }
        clock.fetch_add(crate::tile::cache::REFRESH_INTERVAL_MS + 1, Ordering::SeqCst);
        let failing = Fake { status: 206, body: Vec::new(), fail: true, ranges: RefCell::new(Vec::new()) };
        let r = reader(&f.dir, clock, failing);
        assert_eq!(r.read(0, 4).unwrap(), vec![1u8; 4], "went offline mid-session");
    }

    #[test]
    fn a_failed_fetch_with_nothing_cached_propagates() {
        let f = Fixture::new("nofallback");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let failing = Fake { status: 206, body: Vec::new(), fail: true, ranges: RefCell::new(Vec::new()) };
        let r = reader(&f.dir, clock, failing);
        assert!(r.read(0, 4).is_err());
    }

    #[test]
    fn a_server_error_falls_back_to_the_cache_and_otherwise_fails() {
        let f = Fixture::new("servererror");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        {
            let r = reader(&f.dir, clock.clone(), Fake::ok(vec![1u8; 4]));
            r.read(0, 4).unwrap();
        }
        clock.fetch_add(crate::tile::cache::REFRESH_INTERVAL_MS + 1, Ordering::SeqCst);
        let erroring = Fake { status: 503, body: vec![9u8; 4], fail: false, ranges: RefCell::new(Vec::new()) };
        let r = reader(&f.dir, clock, erroring);
        assert_eq!(r.read(0, 4).unwrap(), vec![1u8; 4], "a 503 must not replace a good entry");
        // With nothing cached for a different range, it is an error.
        assert!(r.read(64, 4).is_err());
    }

    #[test]
    fn a_whole_file_200_reply_to_a_range_request_is_never_cached() {
        // This is the failure the original records: a 200 whole-file reply stored as a
        // range is what produced the "Prefix string too short" pmtiles header errors.
        let f = Fixture::new("wholefile");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let whole = Fake { status: 200, body: vec![3u8; 4096], fail: false, ranges: RefCell::new(Vec::new()) };
        let r = reader(&f.dir, clock, whole);
        assert_eq!(r.read(0, 16).unwrap().len(), 4096, "returned to the caller, which rejects it");
        let cached = std::fs::read_dir(&f.dir)
            .unwrap()
            .flatten()
            .filter(|e| e.path().extension().is_some_and(|x| x == "data"))
            .count();
        assert_eq!(cached, 0, "nothing may reach the disk");
    }

    #[test]
    fn a_206_whose_body_is_the_wrong_length_is_never_cached() {
        let f = Fixture::new("shortpartial");
        let clock = Arc::new(AtomicU64::new(1_000_000));
        let r = reader(&f.dir, clock, Fake::ok(vec![3u8; 8]));
        r.read(0, 16).unwrap();
        let cached = std::fs::read_dir(&f.dir)
            .unwrap()
            .flatten()
            .filter(|e| e.path().extension().is_some_and(|x| x == "data"))
            .count();
        assert_eq!(cached, 0, "a short partial may not be cached");
    }
    /// **The whole point of carrying a `build_id`.** The archive is republished under the *same* URL
    /// every build, so the URL alone cannot invalidate anything: a cached leaf index keeps addressing
    /// byte offsets from the build it came from, and a user sits on a stale map with no way to
    /// notice. The id is what changes.
    #[test]
    fn republishing_under_the_same_url_wipes_the_cache_when_the_build_id_changes() {
        let dir = std::env::temp_dir().join(format!("mamaps_origin_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let url = "https://example.invalid/basemap.mamaps";

        // First run against a brand-new cache. The prefix was fetched before the id was known, so
        // recording the id drops it: one wasted request, once, on a fresh install. Cheaper than any
        // scheme that avoids it.
        let cache = RangeCache::open_unchecked(&dir, 1 << 20);
        cache.write(&RangeCache::key(url, "bytes=0-15"), b"the first build");
        cache.reset_if_origin_changed(&basemap_origin(url, 1));

        // Everything cached from here on belongs to build 1 and survives.
        cache.write(&RangeCache::key(url, "bytes=0-15"), b"the first build");
        cache.write(&RangeCache::key(url, "bytes=64-79"), b"a leaf of one  ");

        // Second run, same build. **The regression this test exists for**: a marker scheme that
        // wiped here would clear the whole cache on every single start, and the map would refetch
        // the world every time the app opened.
        let cache = RangeCache::open_unchecked(&dir, 1 << 20);
        cache.reset_if_origin_changed(&basemap_origin(url, 1));
        assert!(
            cache.read(&RangeCache::key(url, "bytes=0-15")).is_some(),
            "restarting against the same build wiped the cache",
        );
        assert!(cache.read(&RangeCache::key(url, "bytes=64-79")).is_some());

        // A republish, same URL. Every entry goes, because every offset in it belongs to build 1.
        cache.reset_if_origin_changed(&basemap_origin(url, 2));
        for range in ["bytes=0-15", "bytes=64-79"] {
            assert!(
                cache.read(&RangeCache::key(url, range)).is_none(),
                "a republish under the same name left build 1's {range} in the cache",
            );
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_origin_marker_names_the_layout_the_url_and_the_build() {
        let url = "https://example.invalid/basemap.mamaps";
        assert!(basemap_origin(url, 1).starts_with(&format!("{CACHE_FORMAT}|{url}|")));
        assert_eq!(basemap_origin(url, 1), basemap_origin(url, 1), "stable");
        assert_ne!(basemap_origin(url, 1), basemap_origin(url, 2), "a republish");
        // A different archive at a different URL is a different origin even at the same id, which is
        // what keeps a debug build pointed at a local file from poisoning the real cache.
        assert_ne!(basemap_origin(url, 1), basemap_origin("https://other.invalid/x", 1));
    }
}
