//! Assembling extracted rows into a `geocoder-v3.geodb`.
//!
//! Three orderings decide whether the finished database works, and none of them fails loudly:
//!
//! 1. **Records are ordered by grid cell, then Z-order within the cell.** Reverse lookup walks
//!    outward from the query's cell, so this ordering *is* the spatial index.
//! 2. **Searchable dictionaries are UTF-16 sorted**, because the reader binary-searches them
//!    with a UTF-16 comparator. See `codec::cmp_utf16`.
//! 3. **The forward and name indexes are permutations** into grid order, each sorted by its own
//!    key. Get one out of step with its dictionary and forward search returns the wrong rows.
//!
//! [`verify`] reads the finished file back through a path that shares no code with the writer,
//! which is the only real check that all three held.

use std::fs::File;
use std::io::{self, BufWriter, Read, Write};
use std::path::Path;

use crate::codec::{cmp_utf16, encode_column, encode_dict, encode_grid, write_section};
use crate::extract::{Row, Strings};
use crate::format::*;

/// Sizes of each part of the finished file, for the build report.
#[derive(Debug, Default, Clone)]
pub struct Report {
    /// Records written.
    pub n: usize,
    /// Total file size.
    pub total: u64,
    /// `(name, bytes)` per dictionary.
    pub dicts: Vec<(&'static str, u64)>,
    /// `(name, bytes)` per column.
    pub columns: Vec<(&'static str, u64)>,
    /// Grid directory size.
    pub grid: u64,
    /// Forward index size.
    pub fwd: u64,
    /// Name index size, both sections.
    pub name_index: u64,
    /// Distinct strings per dictionary.
    pub dict_counts: Vec<(&'static str, usize)>,
}

impl Report {
    /// Bits per record, the number worth watching between builds.
    pub fn bits_per_record(&self) -> f64 {
        if self.n == 0 {
            0.0
        } else {
            (self.total as f64 * 8.0) / self.n as f64
        }
    }
}

const DICT_NAME: [&str; DICTS] =
    ["name", "house", "street", "city", "state", "country", "postcode"];

/// Sort `rows` into grid order and write the database to `out_path`.
///
/// `strings` holds the dictionaries the rows' ids refer to, filled during extraction. Consumes
/// both in place: at planet scale this is tens of gigabytes and copying would double the peak.
pub fn write(out_path: &Path, rows: &mut Vec<Row>, strings: &mut Strings) -> io::Result<Report> {
    // --- ordering -------------------------------------------------------
    // Cell first so the grid directory is contiguous, Z-order within it so both coordinate
    // columns delta well, then a total tiebreak so two runs produce identical bytes.
    //
    // The tiebreak compares dictionary ids rather than strings. Ids are assigned in
    // first-seen order, which depends on how the PBF's blobs were chunked, so this alone
    // would not be deterministic — `sort_rows_deterministically` below re-sorts each tied run
    // once the final (content-derived) ids are known.
    rows.sort_unstable_by(|a, b| {
        cell_id(a.lat_e7, a.lon_e7)
            .cmp(&cell_id(b.lat_e7, b.lon_e7))
            .then_with(|| {
                morton_in_cell(a.lat_e7, a.lon_e7).cmp(&morton_in_cell(b.lat_e7, b.lon_e7))
            })
            .then_with(|| a.kind.cmp(&b.kind))
            .then_with(|| a.lat_e7.cmp(&b.lat_e7))
            .then_with(|| a.lon_e7.cmp(&b.lon_e7))
    });

    let n = rows.len();

    // --- dictionaries ---------------------------------------------------
    let mut dict_bodies: Vec<Vec<u8>> = Vec::with_capacity(DICTS);
    let mut dict_counts = Vec::with_capacity(DICTS);
    let mut remaps: Vec<Vec<u32>> = Vec::with_capacity(DICTS);
    for d in 0..DICTS {
        let (sorted, remap) = strings.dicts[d].finish(SEARCHABLE[d]);
        dict_counts.push((DICT_NAME[d], sorted.len()));
        dict_bodies.push(encode_dict(&sorted)?);
        remaps.push(remap);
    }
    // Interned ids are first-seen order; the file stores final order.
    for row in rows.iter_mut() {
        for d in 0..DICTS {
            row.ids[d] = remaps[d][row.ids[d] as usize];
        }
    }
    drop(remaps);
    strings.dicts = Vec::new();

    // Now that ids are content-derived, break the remaining ties by them, so the output does
    // not depend on the order the PBF happened to be read in.
    sort_rows_deterministically(rows);

    // --- columns --------------------------------------------------------
    let mut cols: Vec<Vec<i32>> = (0..COLUMNS).map(|_| Vec::with_capacity(n)).collect();
    for r in rows.iter() {
        cols[C_LAT].push(r.lat_e7);
        cols[C_LON].push(r.lon_e7);
        cols[C_NAME].push(r.ids[D_NAME] as i32);
        cols[C_HOUSE].push(r.ids[D_HOUSE] as i32);
        cols[C_STREET].push(r.ids[D_STREET] as i32);
        cols[C_CITY].push(r.ids[D_CITY] as i32);
        cols[C_STATE].push(r.ids[D_STATE] as i32);
        cols[C_COUNTRY].push(r.ids[D_COUNTRY] as i32);
        cols[C_POSTCODE].push(r.ids[D_POSTCODE] as i32);
        cols[C_KIND].push(r.kind as i32);
    }

    // --- grid directory --------------------------------------------------
    let mut grid: Vec<(i64, u32)> = Vec::new();
    for (i, r) in rows.iter().enumerate() {
        let cell = cell_id(r.lat_e7, r.lon_e7);
        if grid.last().map(|&(c, _)| c) != Some(cell) {
            grid.push((cell, i as u32));
        }
    }

    // --- forward index: sorted by (country, state, city, street, house) ---
    let mut fwd: Vec<u32> = (0..n as u32).collect();
    fwd.sort_unstable_by(|&a, &b| {
        let (x, y) = (&rows[a as usize].ids, &rows[b as usize].ids);
        x[D_COUNTRY]
            .cmp(&y[D_COUNTRY])
            .then(x[D_STATE].cmp(&y[D_STATE]))
            .then(x[D_CITY].cmp(&y[D_CITY]))
            .then(x[D_STREET].cmp(&y[D_STREET]))
            .then(x[D_HOUSE].cmp(&y[D_HOUSE]))
            .then(a.cmp(&b))
    });

    // --- name index: every named record, by name id ----------------------
    // Records with no name are excluded rather than bunched at id 0: they can never match a
    // name query, and leaving them in would put tens of millions of dead entries in front of
    // every binary search.
    let empty_name = find_empty_name_id(&dict_bodies[D_NAME]);
    let mut named: Vec<u32> = (0..n as u32)
        .filter(|&i| Some(rows[i as usize].ids[D_NAME]) != empty_name)
        .collect();
    named.sort_unstable_by(|&a, &b| {
        rows[a as usize].ids[D_NAME].cmp(&rows[b as usize].ids[D_NAME]).then(a.cmp(&b))
    });
    let nm_name: Vec<i32> = named.iter().map(|&i| rows[i as usize].ids[D_NAME] as i32).collect();
    let nm_rec: Vec<i32> = named.iter().map(|&i| i as i32).collect();

    // --- write ------------------------------------------------------------
    let mut out = BufWriter::with_capacity(1 << 22, File::create(out_path)?);
    out.write_all(&MAGIC.to_be_bytes())?;
    out.write_all(&VERSION.to_be_bytes())?;
    out.write_all(&(n as u32).to_be_bytes())?;

    let mut report = Report { n, ..Report::default() };
    for d in 0..DICTS {
        report.dicts.push((DICT_NAME[d], write_section(&mut out, &dict_bodies[d])?));
    }
    drop(dict_bodies);
    for c in 0..COLUMNS {
        let body = encode_column(&cols[c], DELTA[c])?;
        report.columns.push((COL_NAME[c], write_section(&mut out, &body)?));
        cols[c] = Vec::new();
    }
    report.grid = write_section(&mut out, &encode_grid(&grid))?;

    let fwd_col: Vec<i32> = fwd.iter().map(|&v| v as i32).collect();
    report.fwd = write_section(&mut out, &encode_column(&fwd_col, true)?)?;
    report.name_index = write_section(&mut out, &encode_column(&nm_name, true)?)?
        + write_section(&mut out, &encode_column(&nm_rec, false)?)?;

    out.flush()?;
    report.dict_counts = dict_counts;
    report.total = std::fs::metadata(out_path)?.len();
    Ok(report)
}

/// Break ties among records that share a cell, Z-order, kind and position, using their final
/// dictionary ids.
///
/// Without this the output depends on the order the PBF's blobs happened to be chunked into,
/// because interned ids are assigned in first-seen order. Sorting once more on the *final*
/// ids — which are derived from the content, not the reading order — makes two runs over the
/// same input produce identical bytes.
fn sort_rows_deterministically(rows: &mut [Row]) {
    rows.sort_unstable_by(|a, b| {
        cell_id(a.lat_e7, a.lon_e7)
            .cmp(&cell_id(b.lat_e7, b.lon_e7))
            .then_with(|| {
                morton_in_cell(a.lat_e7, a.lon_e7).cmp(&morton_in_cell(b.lat_e7, b.lon_e7))
            })
            .then_with(|| a.kind.cmp(&b.kind))
            .then_with(|| a.lat_e7.cmp(&b.lat_e7))
            .then_with(|| a.lon_e7.cmp(&b.lon_e7))
            .then_with(|| a.ids.cmp(&b.ids))
    });
}

/// Id of the empty string in an encoded dictionary, if it holds one.
fn find_empty_name_id(dict_body: &[u8]) -> Option<u32> {
    let raw = zstd::decode_all(dict_body.get(8..)?).ok()?;
    let count = u32::from_be_bytes(raw.get(0..4)?.try_into().ok()?);
    let mut p = 4usize;
    for id in 0..count {
        let len = u32::from_be_bytes(raw.get(p..p + 4)?.try_into().ok()?) as usize;
        p += 4;
        if len == 0 {
            return Some(id);
        }
        p += len;
    }
    None
}

// --------------------------------------------------------------------------- verify
/// Read a finished database back and check it against the rows it was built from.
///
/// Independent of the writer: it walks the file sequentially, decodes every column, and
/// reconstructs each record's strings through the dictionaries. That catches the failures the
/// writer cannot see in itself — a dictionary sorted one way and indexed another, a column
/// written in a different order from the one the grid describes, an off-by-one in a block
/// directory.
pub fn verify(path: &Path, expected: &[Row]) -> io::Result<()> {
    let mut buf = Vec::new();
    let _ = File::open(path)?.read_to_end(&mut buf)?;
    let bad = |m: String| io::Error::new(io::ErrorKind::InvalidData, m);

    let rd32 = |p: usize| -> u32 {
        u32::from_be_bytes([buf[p], buf[p + 1], buf[p + 2], buf[p + 3]])
    };
    if rd32(0) != MAGIC {
        return Err(bad("bad magic".into()));
    }
    if rd32(4) != VERSION {
        return Err(bad(format!("version {}, expected {VERSION}", rd32(4))));
    }
    let n = rd32(8) as usize;
    if n != expected.len() {
        return Err(bad(format!("{n} records, expected {}", expected.len())));
    }

    let mut cursor = 12usize;
    let mut sections: Vec<&[u8]> = Vec::with_capacity(SECTIONS);
    for s in 0..SECTIONS {
        if cursor + 4 > buf.len() {
            return Err(bad(format!("file ends before section {s}")));
        }
        let size = rd32(cursor) as usize;
        cursor += 4;
        let end = cursor.checked_add(size).ok_or_else(|| bad("section size overflow".into()))?;
        if end > buf.len() {
            return Err(bad(format!("section {s} runs past the end of the file")));
        }
        sections.push(&buf[cursor..end]);
        cursor = end;
    }
    if cursor != buf.len() {
        return Err(bad(format!("{} trailing byte(s)", buf.len() - cursor)));
    }

    let dicts: Vec<Vec<String>> =
        sections[..DICTS].iter().map(|s| decode_dict(s)).collect::<io::Result<_>>()?;
    for (d, dict) in dicts.iter().enumerate() {
        if SEARCHABLE[d] {
            for w in dict.windows(2) {
                if cmp_utf16(&w[0], &w[1]) != std::cmp::Ordering::Less {
                    return Err(bad(format!(
                        "dictionary {} is not in UTF-16 order: {:?} then {:?}",
                        DICT_NAME[d], w[0], w[1]
                    )));
                }
            }
        }
    }

    let cols: Vec<Vec<i32>> = (0..COLUMNS)
        .map(|c| decode_column(sections[DICTS + c], DELTA[c]))
        .collect::<io::Result<_>>()?;
    for (c, col) in cols.iter().enumerate() {
        if col.len() != n {
            return Err(bad(format!("column {} has {} values, expected {n}", COL_NAME[c], col.len())));
        }
    }

    let text = |c: usize, d: usize, i: usize| -> io::Result<&str> {
        let id = cols[c][i];
        dicts[d]
            .get(id as usize)
            .map(|s| s.as_str())
            .ok_or_else(|| bad(format!("record {i}: {} id {id} out of range", COL_NAME[c])))
    };

    for i in 0..n {
        let e = &expected[i];
        if cols[C_LAT][i] != e.lat_e7 || cols[C_LON][i] != e.lon_e7 {
            return Err(bad(format!(
                "record {i}: coords ({}, {}) != ({}, {})",
                cols[C_LAT][i], cols[C_LON][i], e.lat_e7, e.lon_e7
            )));
        }
        if cols[C_KIND][i] != e.kind as i32 {
            return Err(bad(format!("record {i}: kind {} != {}", cols[C_KIND][i], e.kind)));
        }
        // `write` rewrites each row's ids to their final, dictionary-order values, so the rows
        // handed back are exactly what should be on disk. Every id must also address a real
        // dictionary entry, which is what `text` checks.
        for (c, d, label) in [
            (C_NAME, D_NAME, "name"),
            (C_HOUSE, D_HOUSE, "house"),
            (C_STREET, D_STREET, "street"),
            (C_CITY, D_CITY, "city"),
            (C_STATE, D_STATE, "state"),
            (C_COUNTRY, D_COUNTRY, "country"),
            (C_POSTCODE, D_POSTCODE, "postcode"),
        ] {
            let _ = text(c, d, i)?;
            if cols[c][i] != e.ids[d] as i32 {
                return Err(bad(format!(
                    "record {i}: {label} id {} != {}",
                    cols[c][i], e.ids[d]
                )));
            }
        }
    }

    // The grid must describe the record order that was actually written.
    let grid = decode_grid(sections[DICTS + COLUMNS])?;
    let mut prev_cell = i64::MIN;
    for &(cell, start) in &grid {
        if cell <= prev_cell {
            return Err(bad(format!("grid cells not ascending at {cell}")));
        }
        prev_cell = cell;
        let s = start as usize;
        if s >= n || cell_id(cols[C_LAT][s], cols[C_LON][s]) != cell {
            return Err(bad(format!("grid entry for cell {cell} points at the wrong record")));
        }
    }
    for i in 1..n {
        let a = cell_id(cols[C_LAT][i - 1], cols[C_LON][i - 1]);
        let b = cell_id(cols[C_LAT][i], cols[C_LON][i]);
        if b < a {
            return Err(bad(format!("records are not in cell order at {i}")));
        }
    }

    // Both indexes must be permutations of valid record ids, each sorted by its own key.
    let fwd = decode_column(sections[DICTS + COLUMNS + 1], true)?;
    if fwd.len() != n {
        return Err(bad(format!("forward index has {} entries, expected {n}", fwd.len())));
    }
    let mut seen = vec![false; n];
    let mut prev_key: Option<[i32; 5]> = None;
    for &r in &fwd {
        let i = r as usize;
        if i >= n || seen[i] {
            return Err(bad("forward index is not a permutation".into()));
        }
        seen[i] = true;
        let key =
            [cols[C_COUNTRY][i], cols[C_STATE][i], cols[C_CITY][i], cols[C_STREET][i], cols[C_HOUSE][i]];
        if let Some(p) = prev_key {
            if key < p {
                return Err(bad("forward index is not sorted by its key".into()));
            }
        }
        prev_key = Some(key);
    }

    let nm_name = decode_column(sections[DICTS + COLUMNS + 2], true)?;
    let nm_rec = decode_column(sections[DICTS + COLUMNS + 3], false)?;
    if nm_name.len() != nm_rec.len() {
        return Err(bad("name index halves disagree on length".into()));
    }
    let mut prev = i32::MIN;
    for (k, (&name_id, &rec)) in nm_name.iter().zip(nm_rec.iter()).enumerate() {
        if name_id < prev {
            return Err(bad(format!("name index not ascending at {k}")));
        }
        prev = name_id;
        let i = rec as usize;
        if i >= n {
            return Err(bad(format!("name index entry {k} points past the end")));
        }
        if cols[C_NAME][i] != name_id {
            return Err(bad(format!("name index entry {k} disagrees with the name column")));
        }
        if dicts[D_NAME][name_id as usize].is_empty() {
            return Err(bad(format!("name index entry {k} points at an unnamed record")));
        }
    }
    Ok(())
}

fn decode_dict(body: &[u8]) -> io::Result<Vec<String>> {
    let bad = |m: &str| io::Error::new(io::ErrorKind::InvalidData, m.to_string());
    if body.len() < 8 {
        return Err(bad("dictionary section too short"));
    }
    let raw = zstd::decode_all(&body[8..])?;
    let count = u32::from_be_bytes(raw[0..4].try_into().map_err(|_| bad("short dict"))?) as usize;
    let mut out = Vec::with_capacity(count);
    let mut p = 4usize;
    for _ in 0..count {
        let len =
            u32::from_be_bytes(raw[p..p + 4].try_into().map_err(|_| bad("short dict"))?) as usize;
        p += 4;
        out.push(
            String::from_utf8(raw[p..p + len].to_vec())
                .map_err(|_| bad("dictionary entry is not UTF-8"))?,
        );
        p += len;
    }
    Ok(out)
}

fn decode_column(body: &[u8], delta: bool) -> io::Result<Vec<i32>> {
    let bad = |m: &str| io::Error::new(io::ErrorKind::InvalidData, m.to_string());
    let rd32 = |p: usize| -> u32 {
        u32::from_be_bytes([body[p], body[p + 1], body[p + 2], body[p + 3]])
    };
    if body.len() < 8 {
        return Err(bad("column section too short"));
    }
    let n = rd32(0) as usize;
    let blocks = rd32(4) as usize;
    let mut offset = 8 + blocks * 8;
    let mut out = Vec::with_capacity(n);
    for b in 0..blocks {
        let comp_len = rd32(8 + b * 8 + 4) as usize;
        let raw = zstd::decode_all(&body[offset..offset + comp_len])?;
        offset += comp_len;
        let mut p = 0usize;
        let mut prev = 0i32;
        let count = n.saturating_sub(b * BLOCK).min(BLOCK);
        for _ in 0..count {
            let mut v = 0u32;
            let mut shift = 0;
            loop {
                let byte = *raw.get(p).ok_or_else(|| bad("block ran out of bytes"))?;
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
    Ok(out)
}

fn decode_grid(body: &[u8]) -> io::Result<Vec<(i64, u32)>> {
    let count = u32::from_be_bytes(
        body[0..4].try_into().map_err(|_| io::Error::other("short grid"))?,
    ) as usize;
    let mut out = Vec::with_capacity(count);
    let mut p = 4usize;
    for _ in 0..count {
        let cell = i64::from_be_bytes(
            body[p..p + 8].try_into().map_err(|_| io::Error::other("short grid"))?,
        );
        let start = u32::from_be_bytes(
            body[p + 8..p + 12].try_into().map_err(|_| io::Error::other("short grid"))?,
        );
        out.push((cell, start));
        p += 12;
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TempDir(std::path::PathBuf);
    impl TempDir {
        fn new(tag: &str) -> TempDir {
            let p = std::env::temp_dir().join(format!("geodb-{tag}-{}", std::process::id()));
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

    /// Build rows the way extraction does, so the test exercises the same interning path the
    /// real build uses rather than a shortcut around it.
    fn sample_rows(count: usize) -> (Vec<Row>, Strings) {
        let mut strings = Strings::default();
        let mut state = 0x1234_5678u64;
        let mut next = || {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1);
            state
        };
        let rows = (0..count)
            .map(|i| {
                let kind = match i % 4 {
                    0 => K_ADDRESS,
                    1 => K_STREET,
                    2 => K_POI,
                    _ => K_PLACE,
                };
                let name =
                    if kind == K_ADDRESS { String::new() } else { format!("Feature {}", i % 977) };
                let house = if kind == K_ADDRESS { format!("{}", i % 200) } else { String::new() };
                let street =
                    if kind == K_ADDRESS { format!("Street {}", i % 313) } else { String::new() };
                let ids = [
                    strings.dicts[D_NAME].intern(&name),
                    strings.dicts[D_HOUSE].intern(&house),
                    strings.dicts[D_STREET].intern(&street),
                    strings.dicts[D_CITY].intern(&format!("City {}", i % 47)),
                    strings.dicts[D_STATE].intern(&format!("State {}", i % 11)),
                    strings.dicts[D_COUNTRY].intern(["US", "GB", "DE", "JP"][i % 4]),
                    strings.dicts[D_POSTCODE].intern(&format!("{:05}", i % 9000)),
                ];
                Row {
                    lat_e7: (next() % 1_800_000_000) as i64 as i32 - 900_000_000,
                    lon_e7: ((next() % 3_600_000_000) as i64 - 1_800_000_000) as i32,
                    kind,
                    ids,
                }
            })
            .collect();
        (rows, strings)
    }

    #[test]
    fn a_database_round_trips_through_the_verifier() {
        let d = TempDir::new("roundtrip");
        let (mut rows, mut strings) = sample_rows(BLOCK * 2 + 123);
        let out = d.0.join("geocoder-v3.geodb");
        let report = write(&out, &mut rows, &mut strings).unwrap();
        assert_eq!(report.n, rows.len());
        assert!(report.total > 0);
        // `write` sorts in place and rewrites ids, so `rows` is now exactly what the file
        // should contain.
        verify(&out, &rows).unwrap();
    }

    #[test]
    fn records_come_out_in_grid_then_z_order() {
        let d = TempDir::new("order");
        let (mut rows, mut strings) = sample_rows(2000);
        let out = d.0.join("g.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        for w in rows.windows(2) {
            let (a, b) = (&w[0], &w[1]);
            let ca = cell_id(a.lat_e7, a.lon_e7);
            let cb = cell_id(b.lat_e7, b.lon_e7);
            assert!(ca <= cb, "cells out of order");
            if ca == cb {
                assert!(
                    morton_in_cell(a.lat_e7, a.lon_e7) <= morton_in_cell(b.lat_e7, b.lon_e7),
                    "z-order broken inside a cell"
                );
            }
        }
    }

    #[test]
    fn two_runs_produce_identical_bytes() {
        let d = TempDir::new("determinism");
        let (mut a, mut sa) = sample_rows(1500);
        let (mut b, mut sb) = sample_rows(1500);
        // Feed the second run in a different order; the sort must erase the difference.
        b.reverse();
        let pa = d.0.join("a.geodb");
        let pb = d.0.join("b.geodb");
        let _ = write(&pa, &mut a, &mut sa).unwrap();
        let _ = write(&pb, &mut b, &mut sb).unwrap();
        assert_eq!(std::fs::read(&pa).unwrap(), std::fs::read(&pb).unwrap());
    }

    #[test]
    fn unicode_survives_the_dictionaries() {
        let d = TempDir::new("unicode");
        let mut strings = Strings::default();
        let mut rows: Vec<Row> = ["Caf\u{e9} de Flore", "\u{6771}\u{4eac}\u{99c5}", "\u{395}\u{3bb}\u{3bb}", "\u{1f3d4} Peak", "\u{cd}safj\u{f6}r\u{f0}ur"]
            .iter()
            .enumerate()
            .map(|(i, name)| Row {
                lat_e7: 37_0000000 + i as i32 * 1000,
                lon_e7: -122_0000000,
                kind: K_POI,
                ids: [
                    strings.dicts[D_NAME].intern(name),
                    strings.dicts[D_HOUSE].intern(""),
                    strings.dicts[D_STREET].intern(""),
                    strings.dicts[D_CITY].intern("Somewhere"),
                    strings.dicts[D_STATE].intern(""),
                    strings.dicts[D_COUNTRY].intern("US"),
                    strings.dicts[D_POSTCODE].intern(""),
                ],
            })
            .collect();
        let out = d.0.join("u.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();

        // The names must survive as bytes, not merely as ids.
        let buf = std::fs::read(&out).unwrap();
        let size = u32::from_be_bytes(buf[12..16].try_into().unwrap()) as usize;
        let names = decode_dict(&buf[16..16 + size]).unwrap();
        assert!(names.iter().any(|s| s == "\u{6771}\u{4eac}\u{99c5}"), "{names:?}");
        assert!(names.iter().any(|s| s == "\u{1f3d4} Peak"), "{names:?}");
    }

    #[test]
    fn the_name_index_covers_named_records_and_excludes_the_rest() {
        let d = TempDir::new("nameidx");
        let (mut rows, mut strings) = sample_rows(500);
        let named = rows.iter().filter(|r| r.kind != K_ADDRESS).count();
        let out = d.0.join("n.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();

        let buf = std::fs::read(&out).unwrap();
        let mut cursor = 12usize;
        let mut sections = Vec::new();
        for _ in 0..SECTIONS {
            let size = u32::from_be_bytes(buf[cursor..cursor + 4].try_into().unwrap()) as usize;
            cursor += 4;
            sections.push(&buf[cursor..cursor + size]);
            cursor += size;
        }
        let nm_rec = decode_column(sections[DICTS + COLUMNS + 3], false).unwrap();
        assert_eq!(nm_rec.len(), named, "every named record must be indexed, and only those");
    }

    #[test]
    fn a_single_record_database_is_valid() {
        let d = TempDir::new("one");
        let (mut rows, mut strings) = sample_rows(1);
        let out = d.0.join("one.geodb");
        let report = write(&out, &mut rows, &mut strings).unwrap();
        assert_eq!(report.n, 1);
        verify(&out, &rows).unwrap();
    }

    #[test]
    fn the_verifier_rejects_a_database_that_does_not_match() {
        let d = TempDir::new("mismatch");
        let (mut rows, mut strings) = sample_rows(100);
        let out = d.0.join("m.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();

        let mut wrong = rows.clone();
        wrong[7].ids[D_NAME] = wrong[7].ids[D_NAME].wrapping_add(1);
        assert!(verify(&out, &wrong).is_err(), "a changed name id must be caught");

        let mut short = rows.clone();
        let _ = short.pop();
        assert!(verify(&out, &short).is_err(), "a record count change must be caught");
    }

    #[test]
    fn trailing_bytes_are_rejected() {
        let d = TempDir::new("trailing");
        let (mut rows, mut strings) = sample_rows(50);
        let out = d.0.join("t.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        let mut buf = std::fs::read(&out).unwrap();
        buf.push(0);
        std::fs::write(&out, &buf).unwrap();
        assert!(verify(&out, &rows).is_err());
    }
}
