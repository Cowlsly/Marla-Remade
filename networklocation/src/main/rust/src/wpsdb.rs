//! Offline key → coordinate lookup over a packed `WPSDB2` store, exposed via JNI.
//!
//! One generic reader serves both offline stores the app ships (mirroring the offline
//! geocoder pattern in `geocoder.rs`):
//!   * `wifi-v2.wpsdb`  — 48-bit MAC (BSSID) key → coord + accuracy
//!   * `cells-v2.wpsdb` — 84-bit packed cell key → coord + accuracy
//!
//! The on-disk format is produced by `scripts/networklocation/wps_harvest` (`wps_build`).
//! All multi-byte scalars are little-endian; the bit-packed arrays are LSB-first within
//! each byte (bit `k` of a value lives at `buf[p >> 3] & (1 << (p & 7))`).
//!
//! ```text
//! 0  magic "WPSDB2\0\0"                8 B
//! 8  key_kind:u8, universe_bits:u8, payload_kind:u8, l:u8
//! 12 select_sample_log2:u8, reserved:u8 * 3
//! 16 n:u64
//! 24 high_len_bits:u64
//! 32 high_len:u64
//! 40 high[high_len]
//!    zeros_len:u64,   zeros[zeros_len]      (u64 each: zeros before bit b << sample_log2)
//!    low_len:u64,     low[low_len]          (n * l bits, bit-packed)
//!    payload_len:u64, payload[payload_len]  (n * 87 bits, bit-packed)
//! ```
//!
//! Membership and index come from the Elias–Fano upper bitvector (`high`). At world scale
//! that vector is hundreds of MB, far too much to hold in an always-bound system service, so
//! it is **mmap'd** rather than read: random `select0` probes hit the page cache and the
//! resident set stays proportional to what is actually touched. The `select0` sample table is
//! precomputed by the builder and read once (a few MB at a billion records) instead of being
//! derived by scanning the whole bitvector at open. `low` and `payload` are read on demand
//! via positional reads, so a multi-gigabyte store costs almost no memory.
//!
//! ## Coordinates
//!
//! Records store `lat`/`lon` as **integer degrees x 1e8**, which is bit-exact against the
//! source the stores are built from, plus the beacon's own **horizontal accuracy in metres**.
//! The previous format quantized to a 20 m grid and carried no accuracy at all, so every
//! offline fix was reported with the same invented radius and the solver's uncertainty
//! weighting — which is fully wired, see `jni.rs` and `multilateration.rs` — did nothing.

use std::fs::File;
use std::os::unix::fs::FileExt;
use std::os::unix::io::{AsRawFd, FromRawFd};

use jni::objects::JClass;
use jni::sys::{jdoubleArray, jint, jlong};
use jni::JNIEnv;

const MAGIC: &[u8; 8] = b"WPSDB2\x00\x00";

/// `high` begins here, immediately after the fixed header.
const HIGH_OFF: u64 = 40;

// --------------------------------------------------------------------------- payload codec
// One record is 87 bits, LSB-first: lat(35) | lon(36) | accuracy(16).
const LAT_BITS: u32 = 35;
const LON_BITS: u32 = 36;
const ACC_BITS: u32 = 16;
const RECORD_BITS: u32 = LAT_BITS + LON_BITS + ACC_BITS;

/// Bias that maps `lat * 1e8` from [-90, 90] onto [0, 180e8], which fits 35 bits.
const LAT_BIAS: i64 = 90_00_000_000;
/// Bias that maps `lon * 1e8` from [-180, 180] onto [0, 360e8], which fits 36 bits.
const LON_BIAS: i64 = 180_00_000_000;
const COORD_SCALE: f64 = 1e8;

/// Stored accuracy sentinel for "the source did not report one".
const ACC_UNKNOWN: u64 = 0xFFFF;
/// What `lookup` reports for [`ACC_UNKNOWN`]. Negative so it cannot be mistaken for a radius;
/// the Kotlin side substitutes its own conservative default.
const ACC_UNKNOWN_OUT: f64 = -1.0;

const PAYLOAD_LATLON_E8_ACC16: u8 = 1;

/// `l` is the Elias–Fano low-part width. Capped so a low value always fits a `u64`, which is
/// what the bit reader returns. The builder applies the same cap; exceeding it only ever
/// lengthens `high`, so the cap costs nothing at real record counts.
const MAX_L: u8 = 64;

// --------------------------------------------------------------------------- byte source
/// Positional reader over a region of a file, starting at `base`.
struct Src {
    file: File,
    base: u64,
}
impl Src {
    fn read(&self, pos: u64, len: usize) -> Option<Vec<u8>> {
        let mut b = vec![0u8; len];
        self.file.read_exact_at(&mut b, self.base + pos).ok()?;
        Some(b)
    }
    fn rd_u64(&self, pos: u64) -> Option<u64> {
        let b = self.read(pos, 8)?;
        Some(u64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
    }
    fn rd_u8(&self, pos: u64) -> Option<u8> {
        Some(self.read(pos, 1)?[0])
    }
}

// --------------------------------------------------------------------------- mmap
/// A read-only mapping of one region of the store. Only `high` uses this: it is the one
/// section with unpredictable access that is too large to hold resident.
struct Map {
    /// Page-aligned mapping base, as returned by `mmap`.
    addr: *mut libc::c_void,
    /// Length of the mapping, including the alignment slack before `offset`.
    len: usize,
    /// Where the requested region starts within the mapping.
    slack: usize,
}

// The mapping is read-only and never mutated after construction.
unsafe impl Send for Map {}
unsafe impl Sync for Map {}

impl Map {
    fn new(file: &File, offset: u64, len: usize) -> Option<Map> {
        if len == 0 {
            return None;
        }
        let page = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
        if page <= 0 {
            return None;
        }
        let page = page as u64;
        let aligned = offset - (offset % page);
        let slack = (offset - aligned) as usize;
        let maplen = slack.checked_add(len)?;
        let addr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                maplen,
                libc::PROT_READ,
                libc::MAP_PRIVATE,
                file.as_raw_fd(),
                aligned as libc::off_t,
            )
        };
        if addr == libc::MAP_FAILED {
            return None;
        }
        // Probes are scattered single-bit tests, so readahead is wasted IO.
        unsafe { libc::madvise(addr, maplen, libc::MADV_RANDOM) };
        Some(Map { addr, len: maplen, slack })
    }

    #[inline]
    fn as_slice(&self) -> &[u8] {
        unsafe {
            std::slice::from_raw_parts(
                (self.addr as *const u8).add(self.slack),
                self.len - self.slack,
            )
        }
    }
}

impl Drop for Map {
    fn drop(&mut self) {
        unsafe { libc::munmap(self.addr, self.len) };
    }
}

// --------------------------------------------------------------------------- reader
pub struct Reader {
    src: Src,
    n: u64,
    l: u8,
    universe_bits: u8,
    high_len_bits: u64,
    /// mmap of the Elias–Fano upper bitvector.
    high: Map,
    /// `zero_samples[b]` = number of zero bits in `high[0 .. b << sample_log2)`.
    zero_samples: Vec<u64>,
    sample_log2: u8,
    low_off: u64,
    payload_off: u64,
}

impl Reader {
    fn open(fd: i32, offset: i64) -> Option<Reader> {
        let dupfd = unsafe { libc::dup(fd) };
        if dupfd < 0 {
            return None;
        }
        let file = unsafe { File::from_raw_fd(dupfd) };
        Reader::from_src(Src { file, base: offset as u64 })
    }

    /// Open a `.wpsdb` straight from a filesystem path (base offset 0).
    pub fn open_path<P: AsRef<std::path::Path>>(path: P) -> Option<Reader> {
        let file = File::open(path).ok()?;
        Reader::from_src(Src { file, base: 0 })
    }

    fn from_src(src: Src) -> Option<Reader> {
        if src.read(0, 8)?[..] != MAGIC[..] {
            return None;
        }
        let _key_kind = src.rd_u8(8)?;
        let universe_bits = src.rd_u8(9)?;
        let payload_kind = src.rd_u8(10)?;
        let l = src.rd_u8(11)?;
        let sample_log2 = src.rd_u8(12)?;

        // Reject anything this build cannot decode rather than silently mis-reading it.
        if payload_kind != PAYLOAD_LATLON_E8_ACC16 || l > MAX_L {
            return None;
        }
        // `index` shifts a key right by `l` into a u64 bucket number.
        if universe_bits == 0 || universe_bits > 127 || (universe_bits as u32).saturating_sub(l as u32) > 64 {
            return None;
        }
        // `select0` scans at most one sample block, so an absurd stride would make lookups
        // unbounded; a zero stride would divide by zero.
        if sample_log2 < 6 || sample_log2 > 20 {
            return None;
        }

        let n = src.rd_u64(16)?;
        let high_len_bits = src.rd_u64(24)?;
        let high_len = src.rd_u64(32)?;
        if high_len_bits < n || high_len < high_len_bits.div_ceil(8) {
            return None;
        }

        let zeros_len_off = HIGH_OFF + high_len;
        let zeros_len = src.rd_u64(zeros_len_off)?;
        let zeros_off = zeros_len_off + 8;
        if zeros_len % 8 != 0 {
            return None;
        }
        // One sample per block, plus the leading zero. Bounded by high_len_bits, so a
        // corrupt length cannot make this allocate wildly.
        let expect_zeros = (high_len_bits >> sample_log2) + 1;
        if zeros_len / 8 != expect_zeros {
            return None;
        }
        let zeros_raw = src.read(zeros_off, zeros_len as usize)?;
        let zero_samples: Vec<u64> = zeros_raw
            .chunks_exact(8)
            .map(|c| u64::from_le_bytes([c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]]))
            .collect();

        let low_len_off = zeros_off + zeros_len;
        let low_len = src.rd_u64(low_len_off)?;
        let low_off = low_len_off + 8;
        if low_len < (n * l as u64).div_ceil(8) {
            return None;
        }

        let payload_len_off = low_off + low_len;
        let payload_len = src.rd_u64(payload_len_off)?;
        let payload_off = payload_len_off + 8;
        if payload_len < (n * RECORD_BITS as u64).div_ceil(8) {
            return None;
        }

        // Every section must actually be on disk. This is what catches a partially
        // downloaded store, whose header parses fine and whose sizes are self-consistent;
        // without it the `high` mapping would extend past EOF and a probe into the missing
        // tail would raise SIGBUS rather than miss.
        let need = src.base + payload_off + payload_len;
        if src.file.metadata().ok()?.len() < need {
            return None;
        }

        let high = Map::new(&src.file, src.base + HIGH_OFF, high_len as usize)?;

        Some(Reader {
            src,
            n,
            l,
            universe_bits,
            high_len_bits,
            high,
            zero_samples,
            sample_log2,
            low_off,
            payload_off,
        })
    }

    #[inline]
    fn high_bit(&self, p: u64) -> bool {
        let buf = self.high.as_slice();
        (buf[(p >> 3) as usize] >> (p & 7)) & 1 == 1
    }

    /// Position of the zero bit whose 0-indexed rank is `j` (i.e. the `(j+1)`-th zero), or
    /// `None` when fewer than `j+1` zeros exist.
    fn select0(&self, j: u64) -> Option<u64> {
        let total_zeros = self.high_len_bits - self.n;
        if j >= total_zeros {
            return None;
        }
        // Largest block b with zero_samples[b] <= j.
        let mut lo = 0usize;
        let mut hi = self.zero_samples.len() - 1;
        while lo < hi {
            let mid = (lo + hi + 1) / 2;
            if self.zero_samples[mid] <= j {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        let mut count = self.zero_samples[lo];
        let mut p = (lo as u64) << self.sample_log2;
        while p < self.high_len_bits {
            if !self.high_bit(p) {
                if count == j {
                    return Some(p);
                }
                count += 1;
            }
            p += 1;
        }
        None
    }

    /// Read `nbits` bits (LSB-first) starting at bit `bit_index` within the section at file
    /// byte offset `section_off`. `nbits <= 64`.
    fn read_bits(&self, section_off: u64, bit_index: u64, nbits: u32) -> Option<u64> {
        if nbits == 0 {
            return Some(0);
        }
        let first_bit = bit_index & 7;
        let byte_pos = section_off + (bit_index >> 3);
        let nbytes = ((first_bit + nbits as u64).div_ceil(8)) as usize;
        let buf = self.src.read(byte_pos, nbytes)?;
        Some(extract_bits(&buf, first_bit as u32, nbits))
    }

    #[inline]
    fn low(&self, i: u64) -> Option<u64> {
        self.read_bits(self.low_off, i * self.l as u64, self.l as u32)
    }

    /// Decode record `i`. One positional read covers the whole 87-bit record, so a lookup
    /// costs a single `pread` here rather than one per field.
    fn record(&self, i: u64) -> Option<(f64, f64, f64)> {
        let bit_index = i * RECORD_BITS as u64;
        let first_bit = (bit_index & 7) as u32;
        let byte_pos = self.payload_off + (bit_index >> 3);
        let nbytes = ((first_bit + RECORD_BITS).div_ceil(8)) as usize;
        let buf = self.src.read(byte_pos, nbytes)?;

        let lat_u = extract_bits(&buf, first_bit, LAT_BITS);
        let lon_u = extract_bits(&buf, first_bit + LAT_BITS, LON_BITS);
        let acc_u = extract_bits(&buf, first_bit + LAT_BITS + LON_BITS, ACC_BITS);

        let lat = (lat_u as i64 - LAT_BIAS) as f64 / COORD_SCALE;
        let lon = (lon_u as i64 - LON_BIAS) as f64 / COORD_SCALE;
        let acc = if acc_u == ACC_UNKNOWN { ACC_UNKNOWN_OUT } else { acc_u as f64 };
        Some((lat, lon, acc))
    }

    /// Elias–Fano index of `key`, or `None` if `key` is not in the set.
    fn index(&self, key: u128) -> Option<u64> {
        let l = self.l as u32;
        let hi = (key >> l) as u64;
        let lo = if l == 0 { 0 } else { (key & ((1u128 << l) - 1)) as u64 };

        // i0 = number of keys with upper < hi; `start` = first high-bit position of bucket hi.
        let (mut i, start) = if hi == 0 {
            (0u64, 0u64)
        } else {
            let s0 = self.select0(hi - 1)?;
            let start = s0 + 1;
            (start - hi, start)
        };

        let mut p = start;
        let mut bucket = hi;
        while p < self.high_len_bits {
            if self.high_bit(p) {
                if bucket != hi {
                    break;
                }
                if self.low(i)? == lo {
                    return Some(i);
                }
                i += 1;
            } else {
                bucket += 1;
                if bucket > hi {
                    break;
                }
            }
            p += 1;
        }
        None
    }

    /// Look up `key`; returns `(lat, lon, accuracy_m)` or `None` for an unknown key.
    /// A negative accuracy means the source did not report one.
    pub fn lookup(&self, key: u128) -> Option<(f64, f64, f64)> {
        let i = self.index(key)?;
        self.record(i)
    }
}

/// Pull `nbits` (<= 64) LSB-first from `buf` starting at bit `first_bit`.
#[inline]
fn extract_bits(buf: &[u8], first_bit: u32, nbits: u32) -> u64 {
    let mut v = 0u64;
    for k in 0..nbits {
        let p = (first_bit + k) as usize;
        if (buf[p >> 3] >> (p & 7)) & 1 == 1 {
            v |= 1u64 << k;
        }
    }
    v
}

// --------------------------------------------------------------------------- JNI
type Handle = Reader;

/// `open(fd, offset, length) -> handle` (0 on failure). The native side dups `fd`, so the
/// caller may close its own descriptor after this returns. `length` is reserved (the section
/// framing bounds every read) and kept for API symmetry with `GeocoderNative`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_open<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    _length: jlong,
) -> jlong {
    match Reader::open(fd, offset) {
        Some(r) => Box::into_raw(Box::new(r)) as jlong,
        None => 0,
    }
}

/// `lookup(handle, keyHi, keyLo) -> double[3]` (`[lat, lon, accuracyMeters]`), or null for an
/// unknown key. The key is 128 bits split across two longs: a 48-bit WiFi MAC leaves `keyHi`
/// zero, an 84-bit cell key does not. A negative accuracy means the store had none.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_lookup<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    key_hi: jlong,
    key_lo: jlong,
) -> jdoubleArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let reader = unsafe { &*(handle as *const Handle) };
    let key = ((key_hi as u64 as u128) << 64) | (key_lo as u64 as u128);
    match reader.lookup(key) {
        Some((lat, lon, acc)) => {
            let arr = match env.new_double_array(3) {
                Ok(a) => a,
                Err(_) => return std::ptr::null_mut(),
            };
            if env.set_double_array_region(&arr, 0, &[lat, lon, acc]).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        None => std::ptr::null_mut(),
    }
}

/// `close(handle)` — frees the reader, its mapping and its dup'd fd. Safe to call with 0.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_close<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Handle));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::io::Write;

    // --- fixture builder ---------------------------------------------------
    //
    // A transcription of `wps_build`'s writer, kept here so `cargo test` is meaningful
    // without a multi-gigabyte store. The reader is checked against it two ways: `index()`
    // resolves the keys it was given, and `reconstruct()` walks the upper bitvector by a
    // different code path and must recover the same key list.

    struct BitWriter {
        buf: Vec<u8>,
        bits: u64,
    }
    impl BitWriter {
        fn new() -> BitWriter {
            BitWriter { buf: Vec::new(), bits: 0 }
        }
        fn push(&mut self, v: u64, nbits: u32) {
            for k in 0..nbits {
                if (self.bits & 7) == 0 {
                    self.buf.push(0);
                }
                if (v >> k) & 1 == 1 {
                    let p = self.bits;
                    self.buf[(p >> 3) as usize] |= 1 << (p & 7);
                }
                self.bits += 1;
            }
        }
        fn push_bit(&mut self, set: bool) {
            self.push(u64::from(set), 1);
        }
    }

    fn choose_l(universe_bits: u8, n: u64) -> u8 {
        if n == 0 {
            return universe_bits.min(MAX_L);
        }
        let u = 1u128 << universe_bits;
        let mut l = 0u8;
        while l < MAX_L && (u >> (l + 1)) >= n as u128 {
            l += 1;
        }
        l
    }

    /// Write a valid `WPSDB2` to `path`. `records` need not be sorted or unique.
    fn write_fixture(
        path: &std::path::Path,
        universe_bits: u8,
        records: &[(u128, i64, i64, u64)],
        sample_log2: u8,
    ) {
        let mut recs = records.to_vec();
        recs.sort_by_key(|r| r.0);
        recs.dedup_by_key(|r| r.0);
        let n = recs.len() as u64;
        let l = choose_l(universe_bits, n);

        // Elias-Fano upper bitvector: for each key in order, a run of zeros advancing the
        // bucket, then a one.
        let mut high = BitWriter::new();
        let mut prev_bucket = 0u64;
        for &(key, ..) in &recs {
            let bucket = (key >> l) as u64;
            for _ in prev_bucket..bucket {
                high.push_bit(false);
            }
            high.push_bit(true);
            prev_bucket = bucket;
        }
        // Pad out the bucket space so `select0(hi - 1)` resolves for every reachable bucket.
        let buckets = 1u64 << (universe_bits as u32 - l as u32);
        for _ in prev_bucket..buckets {
            high.push_bit(false);
        }
        let high_len_bits = high.bits;

        let mut zeros: Vec<u64> = vec![0];
        let mut count = 0u64;
        for p in 0..high_len_bits {
            if (high.buf[(p >> 3) as usize] >> (p & 7)) & 1 == 0 {
                count += 1;
            }
            if ((p + 1) & ((1u64 << sample_log2) - 1)) == 0 {
                zeros.push(count);
            }
        }
        assert_eq!(zeros.len() as u64, (high_len_bits >> sample_log2) + 1);

        let mut low = BitWriter::new();
        for &(key, ..) in &recs {
            let lo = if l == 0 { 0 } else { (key & ((1u128 << l) - 1)) as u64 };
            low.push(lo, l as u32);
        }

        let mut payload = BitWriter::new();
        for &(_, lat_e8, lon_e8, acc) in &recs {
            payload.push((lat_e8 + LAT_BIAS) as u64, LAT_BITS);
            payload.push((lon_e8 + LON_BIAS) as u64, LON_BITS);
            payload.push(acc, ACC_BITS);
        }

        let mut out: Vec<u8> = Vec::new();
        out.extend_from_slice(MAGIC);
        out.push(1); // key_kind
        out.push(universe_bits);
        out.push(PAYLOAD_LATLON_E8_ACC16);
        out.push(l);
        out.push(sample_log2);
        out.extend_from_slice(&[0, 0, 0]);
        out.extend_from_slice(&n.to_le_bytes());
        out.extend_from_slice(&high_len_bits.to_le_bytes());
        out.extend_from_slice(&(high.buf.len() as u64).to_le_bytes());
        assert_eq!(out.len() as u64, HIGH_OFF);
        out.extend_from_slice(&high.buf);
        out.extend_from_slice(&((zeros.len() * 8) as u64).to_le_bytes());
        for z in &zeros {
            out.extend_from_slice(&z.to_le_bytes());
        }
        out.extend_from_slice(&(low.buf.len() as u64).to_le_bytes());
        out.extend_from_slice(&low.buf);
        out.extend_from_slice(&(payload.buf.len() as u64).to_le_bytes());
        out.extend_from_slice(&payload.buf);

        let mut f = File::create(path).unwrap();
        f.write_all(&out).unwrap();
    }

    struct Fixture {
        dir: std::path::PathBuf,
    }
    impl Fixture {
        fn new(name: &str) -> Fixture {
            let dir = std::env::temp_dir().join(format!("wpsdb2-{name}-{}", std::process::id()));
            std::fs::create_dir_all(&dir).unwrap();
            Fixture { dir }
        }
        fn path(&self) -> std::path::PathBuf {
            self.dir.join("store.wpsdb")
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// Reconstruct every (index, key) pair by walking the Elias–Fano upper bitvector — the
    /// inverse of `index()` — so tests cross-check membership against a second code path.
    fn reconstruct(r: &Reader) -> Vec<u128> {
        let l = r.l as u32;
        let mut keys = Vec::with_capacity(r.n as usize);
        let mut idx = 0u64;
        let mut p = 0u64;
        while p < r.high_len_bits && idx < r.n {
            if r.high_bit(p) {
                let upper = (p - idx) as u128;
                let lo = r.low(idx).unwrap() as u128;
                keys.push((upper << l) | lo);
                idx += 1;
            }
            p += 1;
        }
        keys
    }

    /// Deterministic LCG so the tests need no rand dep.
    fn lcg(state: &mut u64) -> u64 {
        *state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        *state
    }

    fn wifi_records(count: usize) -> Vec<(u128, i64, i64, u64)> {
        let mut state = 0x1234_5678_9abc_def0u64;
        (0..count)
            .map(|i| {
                let key = (lcg(&mut state) & ((1u64 << 48) - 1)) as u128;
                let lat = (lcg(&mut state) % 18_000_000_000) as i64 - LAT_BIAS;
                let lon = (lcg(&mut state) % 36_000_000_000) as i64 - LON_BIAS;
                let acc = if i % 17 == 0 { ACC_UNKNOWN } else { (i % 500) as u64 };
                (key, lat, lon, acc)
            })
            .collect()
    }

    // --- tests -------------------------------------------------------------

    #[test]
    fn header_is_parsed() {
        let f = Fixture::new("header");
        let recs = wifi_records(500);
        write_fixture(&f.path(), 48, &recs, 12);
        let r = Reader::open_path(f.path()).expect("fixture must open");
        assert_eq!(r.universe_bits, 48);
        assert!(r.n > 0 && r.n <= 500);
        assert!(r.high_len_bits >= r.n, "high bitvector shorter than key count");
        assert!(r.l <= MAX_L);
    }

    /// Every key round-trips through `index()`, and `lookup` decodes exactly the coordinate
    /// and accuracy the builder wrote — including the "no accuracy" sentinel.
    #[test]
    fn known_keys_resolve_exactly() {
        let f = Fixture::new("known");
        let mut recs = wifi_records(2000);
        recs.sort_by_key(|r| r.0);
        recs.dedup_by_key(|r| r.0);
        write_fixture(&f.path(), 48, &recs, 12);
        let r = Reader::open_path(f.path()).unwrap();

        assert_eq!(reconstruct(&r), recs.iter().map(|x| x.0).collect::<Vec<_>>());

        for (i, &(key, lat_e8, lon_e8, acc)) in recs.iter().enumerate() {
            assert_eq!(r.index(key), Some(i as u64), "index mismatch for {key:#x}");
            let (lat, lon, got_acc) = r.lookup(key).expect("known key resolves");
            // e8 is exact in f64 (well under 2^53), so this is an equality check, not epsilon.
            assert_eq!(lat, lat_e8 as f64 / COORD_SCALE, "lat mismatch for {key:#x}");
            assert_eq!(lon, lon_e8 as f64 / COORD_SCALE, "lon mismatch for {key:#x}");
            if acc == ACC_UNKNOWN {
                assert!(got_acc < 0.0, "unknown accuracy must be negative");
            } else {
                assert_eq!(got_acc, acc as f64, "accuracy mismatch for {key:#x}");
            }
            assert!((-90.0..=90.0).contains(&lat));
            assert!((-180.0..=180.0).contains(&lon));
        }
    }

    /// Random unknown keys are all rejected — zero false positives.
    #[test]
    fn unknown_keys_rejected() {
        let f = Fixture::new("unknown");
        let recs = wifi_records(2000);
        write_fixture(&f.path(), 48, &recs, 12);
        let r = Reader::open_path(f.path()).unwrap();

        let known: HashMap<u128, ()> = reconstruct(&r).into_iter().map(|k| (k, ())).collect();
        let mut state = 0xdead_beef_cafe_babeu64;
        let mut fp = 0;
        for _ in 0..50_000 {
            let key = (lcg(&mut state) & ((1u64 << 48) - 1)) as u128;
            if !known.contains_key(&key) && r.lookup(key).is_some() {
                fp += 1;
            }
        }
        assert_eq!(fp, 0, "expected zero false positives, got {fp}");
    }

    /// The 84-bit cell keyspace exercises the u128 path and the `MAX_L` cap, which a 48-bit
    /// universe never reaches.
    #[test]
    fn cell_scale_keys_resolve() {
        let f = Fixture::new("cell");
        let mut state = 0x0f0f_0f0f_0f0f_0f0fu64;
        let mut recs: Vec<(u128, i64, i64, u64)> = (0..1000)
            .map(|_| {
                let hi = (lcg(&mut state) & ((1u64 << 20) - 1)) as u128;
                let key = (hi << 64) | lcg(&mut state) as u128;
                (key, 37_77_000_000i64, -122_41_000_000i64, 250)
            })
            .collect();
        recs.sort_by_key(|r| r.0);
        recs.dedup_by_key(|r| r.0);
        write_fixture(&f.path(), 84, &recs, 12);

        let r = Reader::open_path(f.path()).unwrap();
        assert_eq!(r.l, MAX_L, "an 84-bit universe at this n must hit the l cap");
        for (i, &(key, ..)) in recs.iter().enumerate() {
            assert_eq!(r.index(key), Some(i as u64), "cell key {key:#x} rejected");
        }
        let (lat, lon, acc) = r.lookup(recs[0].0).unwrap();
        assert_eq!((lat, lon, acc), (37.77, -122.41, 250.0));
    }

    /// Extremes must survive the bias, and a store whose payload straddles byte boundaries
    /// (87 bits is coprime with 8) must decode every record, not just aligned ones.
    #[test]
    fn coordinate_extremes_and_bit_alignment() {
        let f = Fixture::new("extremes");
        let recs: Vec<(u128, i64, i64, u64)> = vec![
            (1, -LAT_BIAS, -LON_BIAS, 0),
            (2, LAT_BIAS, LON_BIAS, 65534),
            (3, 0, 0, ACC_UNKNOWN),
            (4, 1, -1, 1),
            (5, -LAT_BIAS + 1, LON_BIAS - 1, 12345),
            (6, 45_00_000_001, -93_26_543_210, 7),
        ];
        write_fixture(&f.path(), 48, &recs, 6);
        let r = Reader::open_path(f.path()).unwrap();
        for &(key, lat_e8, lon_e8, acc) in &recs {
            let (lat, lon, got) = r.lookup(key).expect("key resolves");
            assert_eq!(lat, lat_e8 as f64 / COORD_SCALE);
            assert_eq!(lon, lon_e8 as f64 / COORD_SCALE);
            if acc == ACC_UNKNOWN {
                assert!(got < 0.0);
            } else {
                assert_eq!(got, acc as f64);
            }
        }
        assert_eq!(r.lookup(1).unwrap().0, -90.0);
        assert_eq!(r.lookup(2).unwrap().1, 180.0);
    }

    /// Cross-check against a store built by `wps_harvest` rather than by the fixture writer
    /// above.
    ///
    /// The fixture writer is a transcription of `wps_build`'s, which means the round-trip
    /// tests would still pass if both sides shared a misunderstanding of the format. Pointing
    /// `WPSDB2_TEST` at a real store closes that gap, and is also how a planet build gets
    /// smoke-tested before it is published:
    ///
    /// ```sh
    /// WPSDB2_TEST=/path/to/wifi-v2.wpsdb cargo test -p ... wpsdb -- --nocapture
    /// ```
    ///
    /// Walking the bitvector is cheap (it is mmap'd), but every key's low bits and payload
    /// cost a positional read, so a store with hundreds of millions of records is sampled
    /// rather than reconstructed whole.
    #[test]
    fn a_real_store_opens_and_resolves() {
        let Ok(path) = std::env::var("WPSDB2_TEST") else {
            eprintln!("skip: set WPSDB2_TEST to a .wpsdb built by wps_build");
            return;
        };
        let r = Reader::open_path(&path)
            .unwrap_or_else(|| panic!("{path} exists but failed the header check"));
        assert!(r.n > 0, "a published store must not be empty");
        assert!(r.high_len_bits >= r.n);

        const SAMPLES: u64 = 3000;
        let stride = (r.n / SAMPLES).max(1);

        let l = r.l as u32;
        let mut checked = 0u64;
        let mut prev_key: Option<u128> = None;
        let mut idx = 0u64;
        let mut p = 0u64;
        while p < r.high_len_bits && idx < r.n {
            if r.high_bit(p) {
                if idx % stride == 0 {
                    // Recover this key the long way round — from the bitvector — then check
                    // `index()` finds its way back to the same slot.
                    let upper = (p - idx) as u128;
                    let lo = r.low(idx).expect("low bits readable") as u128;
                    let key = (upper << l) | lo;

                    assert!(key < (1u128 << r.universe_bits), "key {key:#x} escapes the universe");
                    if let Some(prev) = prev_key {
                        assert!(prev < key, "keys are not strictly ascending");
                    }
                    prev_key = Some(key);

                    assert_eq!(
                        r.index(key),
                        Some(idx),
                        "known key {key:#x} did not resolve to its own index"
                    );
                    let (lat, lon, acc) = r.lookup(key).expect("known key resolves");
                    assert!((-90.0..=90.0).contains(&lat), "lat out of range: {lat}");
                    assert!((-180.0..=180.0).contains(&lon), "lon out of range: {lon}");
                    assert!(acc < 0.0 || (0.0..=65534.0).contains(&acc), "implausible acc: {acc}");
                    checked += 1;
                }
                idx += 1;
            }
            p += 1;
        }
        assert_eq!(idx, r.n, "bitvector holds {idx} keys, header says {}", r.n);
        assert!(checked > 0, "nothing was sampled");
        eprintln!(
            "{path}: {} records, universe {} bits, l={}, {checked} sampled",
            r.n, r.universe_bits, r.l
        );
    }

    /// A store from the previous format must be refused outright rather than mis-decoded:
    /// its records are 41-bit grid codes, so reading them as 87-bit e8 records would return
    /// plausible-looking but wrong coordinates.
    #[test]
    fn wpsdb1_is_rejected() {
        let f = Fixture::new("v1");
        let mut bytes = b"WPSDB1\x00\x00".to_vec();
        bytes.extend_from_slice(&41u32.to_le_bytes());
        bytes.extend_from_slice(&[0u8; 64]);
        std::fs::write(f.path(), &bytes).unwrap();
        assert!(Reader::open_path(f.path()).is_none(), "WPSDB1 must not open as WPSDB2");
    }

    #[test]
    fn truncated_store_is_rejected() {
        let f = Fixture::new("trunc");
        let recs = wifi_records(300);
        write_fixture(&f.path(), 48, &recs, 12);
        let full = std::fs::read(f.path()).unwrap();
        for cut in [8usize, 24, 40, full.len() / 2, full.len() - 1] {
            std::fs::write(f.path(), &full[..cut]).unwrap();
            assert!(
                Reader::open_path(f.path()).is_none(),
                "a store truncated to {cut} bytes must not open"
            );
        }
    }
}
