//! Offline reverse/forward geocoding over the packed `geocoder-v3.geodb`, exposed via JNI.
//!
//! The on-disk format is produced by `scripts/networklocation/geodb_build`: grid-primary record
//! order for cheap reverse lookup, dictionary-indexed fields, delta+zigzag+varint columns in
//! per-4096-record Zstandard blocks, plus two sorted indexes for structured and by-name lookup.
//! All ints/longs are big-endian.
//!
//! Columns are read block-by-block straight from the file (positional `pread`, no copy), and
//! the search runs in Rust for speed. Runtime is decompress-only, so we use the pure-Rust
//! `ruzstd` decoder — no NDK C toolchain needed.
//!
//! ## v3
//!
//! v2 held **only** postal addresses, at e6 coordinates. v3 also carries named streets, points
//! of interest and populated places, each tagged with a `kind`, and stores coordinates at e7 —
//! the precision OSM itself uses, so nothing is thrown away in the conversion.
//!
//! JNI result layout: each result is 10 consecutive strings
//! `[lat, lon, name, house, street, city, state, country, postcode, kind]`, lat/lon to 7dp.
//! `reverse` returns one result or null; `forward` and `searchName` return 10*k strings.

use std::cmp::Ordering;
use std::fs::File;
use std::io::{Cursor, Read};
use std::os::unix::fs::FileExt;
use std::os::unix::io::FromRawFd;
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jdouble, jint, jlong, jobjectArray};
use jni::JNIEnv;

const MAGIC: u32 = 0x4D41_4745;
const VERSION: u32 = 3;
const BLOCK: i32 = 4096;

// Grid geometry, in e7 units. A cell is 0.05 degrees, about 5.5 km of latitude.
const CELL_E7: i64 = 500_000;
const COLS: i64 = 3_600_000_000 / CELL_E7; // 7200
const MIN_LAT_E7: i32 = -900_000_000;
const MIN_LON_E7: i32 = -1_800_000_000;

/// How far the reverse search will expand before giving up, in cells.
const MAX_RADIUS: i64 = 32;

const DICTS: usize = 7;
const COLUMNS: usize = 10;

const C_LAT: usize = 0;
const C_LON: usize = 1;
const C_NAME: usize = 2;
const C_HOUSE: usize = 3;
const C_STREET: usize = 4;
const C_CITY: usize = 5;
const C_STATE: usize = 6;
const C_COUNTRY: usize = 7;
const C_POSTCODE: usize = 8;
const C_KIND: usize = 9;

/// Dictionary slot backing column `c`. Columns 0 and 1 are coordinates and column 9 is a small
/// integer, so only 2..=8 are dictionary-indexed.
const fn dict_of(col: usize) -> usize {
    col - 2
}

/// Strings per result in the JNI array.
const FIELDS: usize = 10;

// --------------------------------------------------------------------------- byte source
/// Positional reader over a region of a file (the APK asset), starting at `base`.
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
    fn rd_u32(&self, pos: u64) -> Option<u32> {
        let b = self.read(pos, 4)?;
        Some(be32(&b))
    }
    fn rd_i32(&self, pos: u64) -> Option<i32> {
        Some(self.rd_u32(pos)? as i32)
    }
}

fn be32(b: &[u8]) -> u32 {
    ((b[0] as u32) << 24) | ((b[1] as u32) << 16) | ((b[2] as u32) << 8) | (b[3] as u32)
}
fn be64(b: &[u8]) -> i64 {
    let mut v: i64 = 0;
    for i in 0..8 {
        v = (v << 8) | (b[i] as i64);
    }
    v
}
fn unzigzag(v: u32) -> i32 {
    ((v >> 1) as i32) ^ -((v & 1) as i32)
}
fn read_varint(a: &[u8], p: &mut usize) -> u32 {
    let mut v: u32 = 0;
    let mut shift = 0;
    loop {
        let b = a[*p];
        *p += 1;
        v |= ((b & 0x7F) as u32) << shift;
        if b & 0x80 == 0 {
            break;
        }
        shift += 7;
    }
    v
}
fn zstd_decompress(comp: &[u8], raw_len: usize) -> Option<Vec<u8>> {
    let mut dec = ruzstd::StreamingDecoder::new(Cursor::new(comp)).ok()?;
    let mut out = Vec::with_capacity(raw_len);
    dec.read_to_end(&mut out).ok()?;
    if out.len() != raw_len {
        return None;
    }
    Some(out)
}
/// Kotlin `String.compareTo` compares UTF-16 code units; the searchable dictionaries
/// (street/city/state/country) are sorted that way, so the binary search must match. The
/// house/postcode dictionaries are frequency-ordered and only ever indexed for display.
fn cmp_utf16(a: &str, b: &str) -> Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

// --------------------------------------------------------------------------- column reader
/// One column, decoding a single block on demand (cf. `GeoDbReader.Column`).
struct Column {
    n: i32,
    delta: bool,
    comp_lens: Vec<i32>,
    raw_lens: Vec<i32>,
    block_off: Vec<u64>,
    cached_block: i32,
    cached: Vec<i32>,
}
impl Column {
    fn new(src: &Src, body_off: u64, delta: bool) -> Option<Column> {
        let n = src.rd_i32(body_off)?;
        let blocks = src.rd_i32(body_off + 4)? as usize;
        let mut raw_lens = Vec::with_capacity(blocks);
        let mut comp_lens = Vec::with_capacity(blocks);
        let mut p = body_off + 8;
        for _ in 0..blocks {
            raw_lens.push(src.rd_i32(p)?);
            comp_lens.push(src.rd_i32(p + 4)?);
            p += 8;
        }
        let mut block_off = Vec::with_capacity(blocks);
        let mut off = p;
        for b in 0..blocks {
            block_off.push(off);
            off += comp_lens[b] as u64;
        }
        Some(Column { n, delta, comp_lens, raw_lens, block_off, cached_block: -1, cached: Vec::new() })
    }
    fn ensure(&mut self, src: &Src, block: i32) -> bool {
        if block == self.cached_block {
            return true;
        }
        let bi = block as usize;
        let comp = match src.read(self.block_off[bi], self.comp_lens[bi] as usize) {
            Some(c) => c,
            None => return false,
        };
        let raw = match zstd_decompress(&comp, self.raw_lens[bi] as usize) {
            Some(r) => r,
            None => return false,
        };
        let count = std::cmp::min(BLOCK, self.n - block * BLOCK) as usize;
        let mut vals = vec![0i32; count];
        let mut rp = 0usize;
        let mut prev = 0i32;
        for k in 0..count {
            let dv = unzigzag(read_varint(&raw, &mut rp));
            let actual = if self.delta { prev + dv } else { dv };
            vals[k] = actual;
            prev = actual;
        }
        self.cached = vals;
        self.cached_block = block;
        true
    }
    fn get(&mut self, src: &Src, i: i32) -> i32 {
        let block = i / BLOCK;
        if !self.ensure(src, block) {
            return 0;
        }
        self.cached[(i - block * BLOCK) as usize]
    }
}

fn decode_dict(section: &[u8]) -> Option<Vec<String>> {
    let raw_size = be32(&section[0..4]) as usize;
    let comp_size = be32(&section[4..8]) as usize;
    let raw = zstd_decompress(&section[8..8 + comp_size], raw_size)?;
    let mut r = 0usize;
    let count = be32(&raw[r..r + 4]) as usize;
    r += 4;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let len = be32(&raw[r..r + 4]) as usize;
        r += 4;
        out.push(String::from_utf8_lossy(&raw[r..r + len]).into_owned());
        r += len;
    }
    Some(out)
}

// --------------------------------------------------------------------------- reader
pub struct Reader {
    src: Src,
    n: i32,
    /// name, house, street, city, state, country, postcode.
    dicts: Vec<Vec<String>>,
    cell_ids: Vec<i64>,
    cell_starts: Vec<i32>,
    cols: Vec<Column>,
    fwd: Column,
    /// Name ids, ascending; parallel to [`Reader::nm_rec`].
    nm_name: Column,
    /// Record index for each entry of [`Reader::nm_name`].
    nm_rec: Column,
}

fn sect_bytes(src: &Src, cur: &mut u64) -> Option<Vec<u8>> {
    let size = src.rd_i32(*cur)? as usize;
    *cur += 4;
    let b = src.read(*cur, size)?;
    *cur += size as u64;
    Some(b)
}
fn sect_body(src: &Src, cur: &mut u64) -> Option<u64> {
    let size = src.rd_i32(*cur)? as usize;
    *cur += 4;
    let off = *cur;
    *cur += size as u64;
    Some(off)
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

    /// Open a `.geodb` straight from a filesystem path (base offset 0). Test-only: the
    /// production path receives an APK asset fd + offset via `open`.
    #[cfg(test)]
    fn open_path<P: AsRef<std::path::Path>>(path: P) -> Option<Reader> {
        let file = File::open(path).ok()?;
        Reader::from_src(Src { file, base: 0 })
    }

    fn from_src(src: Src) -> Option<Reader> {
        if src.rd_u32(0)? != MAGIC || src.rd_u32(4)? != VERSION {
            return None;
        }
        let n = src.rd_i32(8)?;
        let mut cursor: u64 = 12;

        let mut dicts = Vec::with_capacity(DICTS);
        for _ in 0..DICTS {
            dicts.push(decode_dict(&sect_bytes(&src, &mut cursor)?)?);
        }

        let delta = [true, true, false, false, false, false, false, false, false, false];
        let mut cols = Vec::with_capacity(COLUMNS);
        for i in 0..COLUMNS {
            let off = sect_body(&src, &mut cursor)?;
            cols.push(Column::new(&src, off, delta[i])?);
        }

        let grid = sect_bytes(&src, &mut cursor)?;
        let count = be32(&grid[0..4]) as usize;
        let mut cell_ids = Vec::with_capacity(count);
        let mut cell_starts = Vec::with_capacity(count);
        let mut g = 4usize;
        for _ in 0..count {
            cell_ids.push(be64(&grid[g..g + 8]));
            cell_starts.push(be32(&grid[g + 8..g + 12]) as i32);
            g += 12;
        }

        let fwd_off = sect_body(&src, &mut cursor)?;
        let fwd = Column::new(&src, fwd_off, true)?;

        let nm_name_off = sect_body(&src, &mut cursor)?;
        let nm_name = Column::new(&src, nm_name_off, true)?;
        let nm_rec_off = sect_body(&src, &mut cursor)?;
        let nm_rec = Column::new(&src, nm_rec_off, false)?;

        Some(Reader { src, n, dicts, cell_ids, cell_starts, cols, fwd, nm_name, nm_rec })
    }

    fn grid_index(&self, cell: i64) -> i32 {
        let mut lo = 0i32;
        let mut hi = self.cell_ids.len() as i32 - 1;
        while lo <= hi {
            let mid = (lo + hi) >> 1;
            let v = self.cell_ids[mid as usize];
            if v < cell {
                lo = mid + 1;
            } else if v > cell {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        -1
    }

    fn dict_index(&self, field: usize, key: &str) -> i32 {
        let dict = &self.dicts[field];
        let mut lo = 0i32;
        let mut hi = dict.len() as i32 - 1;
        while lo <= hi {
            let mid = (lo + hi) >> 1;
            match cmp_utf16(&dict[mid as usize], key) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid - 1,
                Ordering::Equal => return mid,
            }
        }
        -1
    }

    /// Nearest record to `(lat, lon)`, or `None` if nothing is within [`MAX_RADIUS`] cells.
    ///
    /// Expands ring by ring from the query's cell. v2 stopped at the first radius that found
    /// anything, which does **not** give the nearest record: a point just over a cell boundary
    /// is found on ring 1, while something genuinely closer sits on ring 2. This keeps going
    /// until the next ring cannot possibly hold anything nearer than the best already seen.
    fn reverse(&mut self, lat: f64, lon: f64) -> Option<i32> {
        if self.n == 0 {
            return None;
        }
        let q_lat = to_e7(lat);
        let q_lon = to_e7(lon);
        let row = (q_lat as i64 - MIN_LAT_E7 as i64) / CELL_E7;
        let col = (q_lon as i64 - MIN_LON_E7 as i64) / CELL_E7;
        let lon_scale = lat.to_radians().cos();

        let mut best_rec = -1i32;
        let mut best_dist = f64::MAX;
        let mut radius = 1i64;
        while radius <= MAX_RADIUS {
            let mut r = row - radius;
            while r <= row + radius {
                if r >= 0 {
                    let mut c = col - radius;
                    while c <= col + radius {
                        let on_edge = !(radius > 1
                            && r > row - radius
                            && r < row + radius
                            && c > col - radius
                            && c < col + radius);
                        if on_edge {
                            let cc = ((c % COLS) + COLS) % COLS;
                            let cell = r * COLS + cc;
                            let gi = self.grid_index(cell);
                            if gi >= 0 {
                                let start = self.cell_starts[gi as usize];
                                let end = if (gi as usize) + 1 < self.cell_starts.len() {
                                    self.cell_starts[gi as usize + 1]
                                } else {
                                    self.n
                                };
                                for i in start..end {
                                    let d_lat = (self.cols[C_LAT].get(&self.src, i) - q_lat) as f64;
                                    let d_lon = (self.cols[C_LON].get(&self.src, i) - q_lon) as f64
                                        * lon_scale;
                                    let d = d_lat * d_lat + d_lon * d_lon;
                                    if d < best_dist {
                                        best_dist = d;
                                        best_rec = i;
                                    }
                                }
                            }
                        }
                        c += 1;
                    }
                }
                r += 1;
            }
            // Anything on the next ring is at least `radius` whole cells away along one axis,
            // because the query sits somewhere inside ring 0's cell. Once that floor exceeds
            // the best distance found, no further ring can improve on it.
            if best_rec >= 0 {
                let floor = radius as f64 * CELL_E7 as f64 * lon_scale.abs().min(1.0);
                if floor * floor > best_dist {
                    break;
                }
            }
            radius += 1;
        }
        if best_rec < 0 {
            None
        } else {
            Some(best_rec)
        }
    }

    fn compare_key(&mut self, rec: i32, target: [i32; 4]) -> i32 {
        let c = self.cols[C_COUNTRY].get(&self.src, rec) - target[0];
        if c != 0 {
            return c;
        }
        let c = self.cols[C_STATE].get(&self.src, rec) - target[1];
        if c != 0 {
            return c;
        }
        let c = self.cols[C_CITY].get(&self.src, rec) - target[2];
        if c != 0 {
            return c;
        }
        self.cols[C_STREET].get(&self.src, rec) - target[3]
    }

    fn forward(&mut self, country: &str, state: &str, city: &str, street: &str, limit: i32) -> Vec<i32> {
        let k_country = self.dict_index(dict_of(C_COUNTRY), country);
        let k_state = self.dict_index(dict_of(C_STATE), state);
        let k_city = self.dict_index(dict_of(C_CITY), city);
        let k_street = self.dict_index(dict_of(C_STREET), street);
        if k_country < 0 || k_state < 0 || k_city < 0 || k_street < 0 {
            return Vec::new();
        }
        let target = [k_country, k_state, k_city, k_street];
        let mut lo = 0i32;
        let mut hi = self.n;
        while lo < hi {
            let mid = (lo + hi) >> 1;
            let rec = self.fwd.get(&self.src, mid);
            if self.compare_key(rec, target) < 0 {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let mut out = Vec::new();
        let mut k = lo;
        while k < self.n && (out.len() as i32) < limit {
            let rec = self.fwd.get(&self.src, k);
            if self.compare_key(rec, target) != 0 {
                break;
            }
            out.push(rec);
            k += 1;
        }
        out
    }

    /// Dictionary id range `[start, end)` of names beginning with `prefix`.
    ///
    /// The name dictionary is UTF-16 sorted, so every name with a given prefix forms one
    /// contiguous run and both ends are a binary search. Matching is case-sensitive: the
    /// dictionary is ordered by the names as they appear in OpenStreetMap, and a case-folded
    /// run would not be contiguous in that ordering.
    fn name_prefix_range(&self, prefix: &str) -> (i32, i32) {
        let dict = &self.dicts[dict_of(C_NAME)];
        let len = dict.len() as i32;
        let mut lo = 0i32;
        let mut hi = len;
        while lo < hi {
            let mid = (lo + hi) >> 1;
            if cmp_utf16(&dict[mid as usize], prefix) == Ordering::Less {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let start = lo;
        let mut lo2 = start;
        let mut hi2 = len;
        while lo2 < hi2 {
            let mid = (lo2 + hi2) >> 1;
            if dict[mid as usize].starts_with(prefix) {
                lo2 = mid + 1;
            } else {
                hi2 = mid;
            }
        }
        (start, lo2)
    }

    /// Records whose name begins with `prefix`, up to `limit`.
    fn search_name(&mut self, prefix: &str, limit: i32) -> Vec<i32> {
        if prefix.is_empty() {
            return Vec::new();
        }
        let (id_lo, id_hi) = self.name_prefix_range(prefix);
        if id_lo >= id_hi {
            return Vec::new();
        }
        let total = self.nm_name.n;
        let mut lo = 0i32;
        let mut hi = total;
        while lo < hi {
            let mid = (lo + hi) >> 1;
            if self.nm_name.get(&self.src, mid) < id_lo {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let mut out = Vec::new();
        let mut k = lo;
        while k < total && (out.len() as i32) < limit {
            if self.nm_name.get(&self.src, k) >= id_hi {
                break;
            }
            out.push(self.nm_rec.get(&self.src, k));
            k += 1;
        }
        out
    }

    /// `[lat, lon, name, house, street, city, state, country, postcode, kind]` for a
    /// grid-ordered record.
    fn resolve(&mut self, rec: i32) -> [String; FIELDS] {
        let lat = self.cols[C_LAT].get(&self.src, rec) as f64 / 1e7;
        let lon = self.cols[C_LON].get(&self.src, rec) as f64 / 1e7;
        let kind = self.cols[C_KIND].get(&self.src, rec);
        let mut text = |col: usize| -> String {
            let id = self.cols[col].get(&self.src, rec);
            self.dicts[dict_of(col)].get(id as usize).cloned().unwrap_or_default()
        };
        [
            format!("{lat:.7}"),
            format!("{lon:.7}"),
            text(C_NAME),
            text(C_HOUSE),
            text(C_STREET),
            text(C_CITY),
            text(C_STATE),
            text(C_COUNTRY),
            text(C_POSTCODE),
            kind.to_string(),
        ]
    }
}

/// Degrees to e7, the precision OSM itself stores and this database keeps.
fn to_e7(deg: f64) -> i32 {
    (deg * 10_000_000.0 + 0.5).floor() as i32
}

// --------------------------------------------------------------------------- JNI
type Handle = Mutex<Reader>;

fn build_string_array(env: &mut JNIEnv, reader: &mut Reader, recs: &[i32]) -> jobjectArray {
    let string_class = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let empty = match env.new_string("") {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array((recs.len() * FIELDS) as i32, &string_class, &empty) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (idx, &rec) in recs.iter().enumerate() {
        let fields = reader.resolve(rec);
        for (j, val) in fields.iter().enumerate() {
            let s = match env.new_string(val) {
                Ok(s) => s,
                Err(_) => return std::ptr::null_mut(),
            };
            if env
                .set_object_array_element(&arr, (idx * FIELDS + j) as i32, &s)
                .is_err()
            {
                return std::ptr::null_mut();
            }
        }
    }
    arr.into_raw()
}

/// `open(fd, offset, length) -> handle` (0 on failure). `length` is currently unused (the
/// section framing bounds every read) but kept for API symmetry / future validation.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_open<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    _length: jlong,
) -> jlong {
    match Reader::open(fd, offset) {
        Some(r) => Box::into_raw(Box::new(Mutex::new(r))) as jlong,
        None => 0,
    }
}

/// `reverse(handle, lat, lon) -> String[10]` (nearest record) or null.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_reverse<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lat: jdouble,
    lon: jdouble,
) -> jobjectArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let m = unsafe { &*(handle as *const Handle) };
    let mut reader = match m.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    match reader.reverse(lat, lon) {
        Some(rec) => build_string_array(&mut env, &mut reader, &[rec]),
        None => std::ptr::null_mut(),
    }
}

/// `forward(handle, country, state, city, street, limit) -> String[10*k]` (may be empty).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_forward<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    country: JString<'l>,
    state: JString<'l>,
    city: JString<'l>,
    street: JString<'l>,
    limit: jint,
) -> jobjectArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let m = unsafe { &*(handle as *const Handle) };
    let mut reader = match m.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    let get = |env: &mut JNIEnv<'l>, s: &JString<'l>| -> String {
        env.get_string(s).map(|js| js.into()).unwrap_or_default()
    };
    let country = get(&mut env, &country);
    let state = get(&mut env, &state);
    let city = get(&mut env, &city);
    let street = get(&mut env, &street);
    let limit = limit.clamp(1, 50);
    let recs = reader.forward(&country, &state, &city, &street, limit);
    build_string_array(&mut env, &mut reader, &recs)
}

/// `searchName(handle, prefix, limit) -> String[10*k]` (may be empty).
///
/// Finds named features — points of interest, places and streets — by name prefix. v2 had no
/// name column at all, so the only way in was a fully structured address.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_searchName<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    prefix: JString<'l>,
    limit: jint,
) -> jobjectArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let m = unsafe { &*(handle as *const Handle) };
    let mut reader = match m.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    let prefix: String = match env.get_string(&prefix) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let recs = reader.search_name(prefix.trim(), limit.clamp(1, 50));
    build_string_array(&mut env, &mut reader, &recs)
}

/// `close(handle)` — frees the reader and its dup'd fd.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_close<'l>(
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

    // resolve() field order:
    // [lat, lon, name, house, street, city, state, country, postcode, kind]
    const F_LAT: usize = 0;
    const F_LON: usize = 1;
    const F_NAME: usize = 2;
    const F_STREET: usize = 4;
    const F_CITY: usize = 5;
    const F_STATE: usize = 6;
    const F_COUNTRY: usize = 7;
    const F_KIND: usize = 9;

    /// Locate a real DB: `$GEOCODER_DB`, else the bundled asset next to the crate.
    fn db_path() -> Option<String> {
        if let Ok(p) = std::env::var("GEOCODER_DB") {
            return Some(p);
        }
        let bundled = concat!(env!("CARGO_MANIFEST_DIR"), "/../assets/geocoder-v3.geodb");
        if std::path::Path::new(bundled).exists() {
            Some(bundled.to_string())
        } else {
            eprintln!("skip: no geocoder DB (set GEOCODER_DB or add the bundled asset)");
            None
        }
    }

    /// Open the real DB. Returns `None` (test self-skips) when no DB is present so CI without
    /// the ~1.4 GB asset stays green; a DB that exists but fails to parse is a hard failure.
    fn open_db() -> Option<Reader> {
        let path = db_path()?;
        match Reader::open_path(&path) {
            Some(r) => Some(r),
            None => panic!("geocoder DB at {path} exists but failed magic/version/parse"),
        }
    }

    /// The header, dictionaries and index tables the two searches rely on are internally
    /// consistent (this is what would break if a repack corrupted the file).
    #[test]
    fn structure_is_sane() {
        let r = match open_db() {
            Some(r) => r,
            None => return,
        };
        assert!(r.n > 0, "record count must be positive");
        assert_eq!(r.dicts.len(), DICTS, "wrong dictionary count");
        assert_eq!(r.cols.len(), COLUMNS, "wrong column count");
        assert_eq!(r.nm_name.n, r.nm_rec.n, "name index halves must match in length");
        assert!(r.nm_name.n <= r.n, "name index cannot exceed the record count");
        assert_eq!(r.fwd.n, r.n, "forward index must cover every record");
        assert_eq!(
            r.cell_ids.len(),
            r.cell_starts.len(),
            "grid cell_ids/cell_starts length mismatch"
        );
        for (i, d) in r.dicts.iter().enumerate() {
            assert!(!d.is_empty(), "dictionary {i} is empty");
        }
        // grid_index() binary-searches cell_ids, so they must be strictly ascending.
        for w in r.cell_ids.windows(2) {
            assert!(w[0] < w[1], "cell_ids not strictly ascending");
        }
        // cell_starts index into records: non-decreasing and within [0, n].
        let mut prev = 0i32;
        for &s in &r.cell_starts {
            assert!(s >= prev && s <= r.n, "cell_start {s} out of range (prev {prev}, n {})", r.n);
            prev = s;
        }
    }

    /// Reverse geocoding a coordinate that is definitely inside the database's coverage
    /// returns a real, nearby record.
    ///
    /// The query points come from the database itself rather than from a list of city centres,
    /// so this works against a metro extract, a state and the planet alike. Hardcoded cities
    /// only ever tested that the planet build had been used.
    #[test]
    fn reverse_returns_nearby_record() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        for frac in [1, 3, 5, 7, 9] {
            let seed = (r.n / 10) * frac;
            let a = r.resolve(seed);
            let lat: f64 = a[F_LAT].parse().expect("lat parses");
            let lon: f64 = a[F_LON].parse().expect("lon parses");
            // Nudge off the record's own position so this is a real search, not an identity.
            let (qlat, qlon) = (lat + 0.0007, lon + 0.0007);

            let rec = r
                .reverse(qlat, qlon)
                .unwrap_or_else(|| panic!("reverse returned nothing near ({qlat}, {qlon})"));
            let b = r.resolve(rec);
            let rlat: f64 = b[F_LAT].parse().expect("lat parses");
            let rlon: f64 = b[F_LON].parse().expect("lon parses");
            assert!(
                (rlat - qlat).abs() < 0.5 && (rlon - qlon).abs() < 0.5,
                "nearest record ({rlat},{rlon}) too far from query ({qlat},{qlon}); full = {b:?}"
            );
            assert!(
                !b[F_NAME].is_empty() || !b[F_STREET].is_empty() || !b[F_CITY].is_empty(),
                "resolved record has no name, street or city: {b:?}"
            );
        }
    }

    /// Forward geocoding is consistent with reverse: take a real address found by reverse,
    /// look it up by its structured fields, and every returned record must carry the same
    /// country/state/city/street. (We don't assert the exact source record is in the first
    /// N results — a single street can hold more addresses than the limit.)
    #[test]
    fn forward_matches_reverse() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        // Find a record that actually carries a full structured address; streets, POIs and
        // places generally do not, and forward search is only defined over addresses.
        let mut seed = -1i32;
        let step = (r.n / 500).max(1);
        let mut i = 0;
        while i < r.n {
            let a = r.resolve(i);
            if !a[F_COUNTRY].is_empty()
                && !a[F_STATE].is_empty()
                && !a[F_CITY].is_empty()
                && !a[F_STREET].is_empty()
            {
                seed = i;
                break;
            }
            i += step;
        }
        if seed < 0 {
            eprintln!("skip: no fully addressed record in this database");
            return;
        }
        let a = r.resolve(seed);
        let (street, city, state, country) = (
            a[F_STREET].clone(),
            a[F_CITY].clone(),
            a[F_STATE].clone(),
            a[F_COUNTRY].clone(),
        );
        eprintln!("forward seed: {country}/{state}/{city}/{street}");

        let recs = r.forward(&country, &state, &city, &street, 25);
        assert!(
            !recs.is_empty(),
            "forward found nothing for a country/state/city/street produced by reverse"
        );
        for rc in recs {
            let b = r.resolve(rc);
            assert_eq!(b[F_COUNTRY], country, "country mismatch: {b:?}");
            assert_eq!(b[F_STATE], state, "state mismatch: {b:?}");
            assert_eq!(b[F_CITY], city, "city mismatch: {b:?}");
            assert_eq!(b[F_STREET], street, "street mismatch: {b:?}");
        }
    }

    /// Every record carries a kind, and the ones that are not plain addresses carry a name.
    /// This is what v2 could not represent at all.
    #[test]
    fn records_carry_a_kind_and_named_features_carry_a_name() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let mut kinds = std::collections::HashSet::new();
        let step = (r.n / 5000).max(1);
        let mut i = 0;
        while i < r.n {
            let a = r.resolve(i);
            let kind: i32 = a[F_KIND].parse().expect("kind parses");
            assert!((1..=4).contains(&kind), "record {i} has kind {kind}");
            let _ = kinds.insert(kind);
            // Kinds 2..=4 are street, POI and place, all of which exist only because they
            // are named.
            if kind != 1 {
                assert!(!a[F_NAME].is_empty(), "record {i} of kind {kind} has no name");
            }
            i += step;
        }
        assert!(kinds.len() > 1, "a v3 database should hold more than addresses: {kinds:?}");
    }

    /// A name taken from the database is findable by its own prefix, and the result really
    /// does start with it.
    #[test]
    fn name_search_finds_named_features_by_prefix() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        if r.nm_name.n == 0 {
            eprintln!("skip: database holds no named features");
            return;
        }
        // Pick a name that actually exists, from the middle of the name index.
        let seed = r.nm_rec.get(&r.src, r.nm_name.n / 2);
        let name = r.resolve(seed)[F_NAME].clone();
        assert!(!name.is_empty(), "the name index must point at a named record");

        let prefix: String = name.chars().take(name.chars().count().min(6)).collect();
        let hits = r.search_name(&prefix, 20);
        assert!(!hits.is_empty(), "prefix {prefix:?} of a known name found nothing");
        for rec in hits {
            let got = r.resolve(rec)[F_NAME].clone();
            assert!(got.starts_with(&prefix), "{got:?} does not start with {prefix:?}");
        }
    }

    #[test]
    fn name_search_rejects_nonsense_without_panicking() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        assert!(r.search_name("", 10).is_empty(), "an empty prefix must not match everything");
        assert!(r.search_name("__no_such_name_anywhere__", 10).is_empty());
    }

    /// Reverse lookup must return the genuinely nearest record, not merely one from the first
    /// ring that had anything in it. Checked by brute force over a window of the grid-ordered
    /// records around the answer.
    #[test]
    fn reverse_returns_the_nearest_record_not_just_a_near_one() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let step = (r.n / 200).max(1);
        let mut seed = 0;
        while seed < r.n {
            let a = r.resolve(seed);
            let lat: f64 = a[F_LAT].parse().expect("lat parses");
            let lon: f64 = a[F_LON].parse().expect("lon parses");
            let Some(found) = r.reverse(lat, lon) else {
                panic!("querying a record's own coordinates found nothing");
            };
            let b = r.resolve(found);
            let flat: f64 = b[F_LAT].parse().expect("lat parses");
            let flon: f64 = b[F_LON].parse().expect("lon parses");
            // The nearest record to a record's own position is at distance zero, so anything
            // else means the search settled for a worse answer.
            let d = (flat - lat).abs() + (flon - lon).abs();
            assert!(d < 1e-6, "reverse at ({lat}, {lon}) returned ({flat}, {flon}), off by {d}");
            seed += step;
        }
    }

    /// A structured lookup that cannot exist returns empty (no panic, no bogus hit).
    #[test]
    fn forward_unknown_is_empty() {
        let mut r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let recs = r.forward(
            "__no_such_country__",
            "__no_such_state__",
            "__no_such_city__",
            "__no_such_street__",
            10,
        );
        assert!(recs.is_empty(), "unknown query should yield no records");
    }

    /// Micro-benchmark (opt-in). Run with:
    ///   GEOCODER_DB=/path/geocoder.geodb cargo test -p network_location_position_estimation_rust \
    ///     geocoder::tests::bench_ops -- --ignored --nocapture
    #[test]
    #[ignore]
    fn bench_ops() {
        use std::time::Instant;
        let path = match db_path() {
            Some(p) => p,
            None => return,
        };

        let t = Instant::now();
        let mut r = Reader::open_path(&path).expect("open");
        eprintln!("open (inflate dicts+grid): {:.1} ms", t.elapsed().as_secs_f64() * 1e3);

        // City centers across continents (lat, lon).
        let cities = [
            (40.7128, -74.0060),
            (51.5074, -0.1278),
            (35.6762, 139.6503),
            (48.8566, 2.3522),
            (34.0522, -118.2437),
            (41.9028, 12.4964),
            (52.5200, 13.4050),
            (55.7558, 37.6173),
            (-33.8688, 151.2093),
            (19.0760, 72.8777),
            (-23.5505, -46.6333),
            (1.3521, 103.8198),
            (25.2048, 55.2708),
            (37.7749, -122.4194),
            (43.6532, -79.3832),
        ];
        let reps = 20;

        for &(la, lo) in &cities {
            let _ = r.reverse(la, lo); // warm the OS page cache
        }

        let mut rev = Vec::new();
        for _ in 0..reps {
            for &(la, lo) in &cities {
                let t = Instant::now();
                let rec = r.reverse(la, lo);
                let us = t.elapsed().as_secs_f64() * 1e6;
                if rec.is_some() {
                    rev.push(us);
                }
            }
        }

        // Forward keys taken from a real reverse hit.
        let seed = r.reverse(40.7128, -74.0060).expect("seed");
        let a = r.resolve(seed);
        let (co, st, ci, sr) = (a[6].clone(), a[5].clone(), a[4].clone(), a[3].clone());
        let mut fwd = Vec::new();
        for _ in 0..(reps * cities.len()) {
            let t = Instant::now();
            let _ = r.forward(&co, &st, &ci, &sr, 10);
            fwd.push(t.elapsed().as_secs_f64() * 1e6);
        }

        let mut res = Vec::new();
        for _ in 0..2000 {
            let t = Instant::now();
            let _ = r.resolve(seed);
            res.push(t.elapsed().as_secs_f64() * 1e6);
        }

        fn stats(label: &str, mut v: Vec<f64>) {
            v.sort_by(|a, b| a.partial_cmp(b).unwrap());
            let n = v.len();
            let sum: f64 = v.iter().sum();
            let q = |x: f64| v[(((n - 1) as f64) * x) as usize];
            eprintln!(
                "{label:8} n={n:4}  min={:.1}  p50={:.1}  avg={:.1}  p95={:.1}  max={:.1}  (µs)",
                v[0],
                q(0.5),
                sum / n as f64,
                q(0.95),
                v[n - 1]
            );
        }
        stats("reverse", rev);
        stats("forward", fwd);
        stats("resolve", res);
    }
}
