//! Minimal, dependency-free GTFS reader: an RFC 4180-ish CSV parser plus typed
//! loaders for the GTFS files the offline transit index needs
//! (`stops`, `routes`, `trips`, `stop_times`, `calendar`, `calendar_dates`,
//! `shapes`).
//!
//! GTFS is CSV text; times may exceed 24:00:00 (legal, means "after service
//! midnight"). We keep everything in seconds-since-service-midnight.
//!
//! `stop_times.txt` and `shapes.txt` get streaming loaders rather than a [`Csv`]:
//! they are the two largest files in a feed, and a `Csv` would hold every
//! coordinate and every time as a `String`.

use std::collections::HashMap;
use std::io::BufRead;
use std::path::Path;

/// A parsed CSV table: the header row plus data rows (each a `Vec<String>`).
pub struct Csv {
    header: Vec<String>,
    pub rows: Vec<Vec<String>>,
    col: HashMap<String, usize>,
}

impl Csv {
    /// Index of the named column, if present.
    pub fn idx(&self, name: &str) -> Option<usize> {
        self.col.get(name).copied()
    }

    /// Cell value for `row` at column `name`, or "" when absent/short.
    pub fn get<'a>(&self, row: &'a [String], name: &str) -> &'a str {
        match self.idx(name).and_then(|i| row.get(i)) {
            Some(s) => s.as_str(),
            None => "",
        }
    }

    #[allow(dead_code)]
    pub fn header(&self) -> &[String] {
        &self.header
    }
}

/// Parse RFC 4180-ish CSV from `text`. Handles quoted fields containing commas,
/// embedded newlines and escaped quotes (`""`), plus a leading UTF-8 BOM.
pub fn parse_csv(text: &str) -> Csv {
    let mut records: Vec<Vec<String>> = Vec::new();
    let mut field = String::new();
    let mut record: Vec<String> = Vec::new();
    let mut in_quotes = false;
    let mut chars = text.chars().peekable();
    let mut started = false;

    // Strip a leading BOM if present.
    if let Some('\u{feff}') = chars.peek().copied() {
        let _ = chars.next();
    }

    while let Some(c) = chars.next() {
        started = true;
        if in_quotes {
            if c == '"' {
                if chars.peek().copied() == Some('"') {
                    field.push('"');
                    let _ = chars.next();
                } else {
                    in_quotes = false;
                }
            } else {
                field.push(c);
            }
            continue;
        }
        match c {
            '"' => in_quotes = true,
            ',' => {
                record.push(std::mem::take(&mut field));
            }
            '\r' => { /* swallow; handled by \n */ }
            '\n' => {
                record.push(std::mem::take(&mut field));
                records.push(std::mem::take(&mut record));
            }
            _ => field.push(c),
        }
    }
    // Flush a trailing record with no final newline.
    if started && (!field.is_empty() || !record.is_empty()) {
        record.push(field);
        records.push(record);
    }

    let mut iter = records.into_iter();
    let header = iter.next().unwrap_or_default();
    let mut col = HashMap::new();
    for (i, h) in header.iter().enumerate() {
        col.insert(h.trim().to_string(), i);
    }
    let rows: Vec<Vec<String>> = iter.filter(|r| !(r.len() == 1 && r[0].is_empty())).collect();
    Csv { header, rows, col }
}

/// Read `dir/name` as UTF-8 (lossy) and parse it as CSV. Returns `None` when the
/// file is absent (optional GTFS files such as `calendar_dates.txt`).
pub fn read_table(dir: &Path, name: &str) -> Option<Csv> {
    let path = dir.join(name);
    let bytes = std::fs::read(&path).ok()?;
    Some(parse_csv(&String::from_utf8_lossy(&bytes)))
}

/// Parse a GTFS `HH:MM:SS` time (hours may exceed 24) into seconds since
/// service midnight. Returns `None` for blank/malformed values.
pub fn parse_gtfs_time(s: &str) -> Option<u32> {
    let s = s.trim();
    if s.is_empty() {
        return None;
    }
    let mut parts = s.split(':');
    let h: u32 = parts.next()?.trim().parse().ok()?;
    let m: u32 = parts.next()?.trim().parse().ok()?;
    let sec: u32 = parts.next().unwrap_or("0").trim().parse().ok()?;
    Some(h * 3600 + m * 60 + sec)
}

/// Parse a GTFS `YYYYMMDD` service date into an integer `yyyymmdd`.
pub fn parse_gtfs_date(s: &str) -> Option<u32> {
    let s = s.trim();
    if s.len() != 8 {
        return None;
    }
    s.parse().ok()
}

/// GTFS lets a `stop_times.txt` row give only one of arrival/departure; the
/// missing one means "no dwell here". A row with neither is unusable.
pub fn stop_time_pair(arrival: &str, departure: &str) -> Option<(u32, u32)> {
    match (parse_gtfs_time(arrival), parse_gtfs_time(departure)) {
        (Some(a), Some(d)) => Some((a, d)),
        (Some(a), None) => Some((a, a)),
        (None, Some(d)) => Some((d, d)),
        (None, None) => None,
    }
}

/// One `shapes.txt` polyline, in `shape_pt_sequence` order.
pub struct Shape {
    pub lat_e7: Vec<i32>,
    pub lon_e7: Vec<i32>,
    /// `shape_dist_traveled`, present only when *every* point carries one.
    /// Feed-defined units (km, mi, ft, ...): only ever compared, never converted,
    /// so `f32`'s ~7 significant digits are ample and halve what a big feed's
    /// shapes cost to hold.
    pub dist: Option<Vec<f32>>,
}

/// Split one CSV line into fields, honouring quotes and `""` escapes. Unlike
/// [`parse_csv`] this cannot span newlines, which `shapes.txt` never needs — all
/// five of its columns are numbers or an id.
fn split_line(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut field = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if in_quotes {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    field.push('"');
                    let _ = chars.next();
                } else {
                    in_quotes = false;
                }
            } else {
                field.push(c);
            }
        } else if c == '"' {
            in_quotes = true;
        } else if c == ',' {
            out.push(std::mem::take(&mut field));
        } else {
            field.push(c);
        }
    }
    out.push(field);
    out
}

/// One usable `stop_times.txt` row, already typed. Borrowed from the reader's
/// line buffer, so it must be consumed before the next row is read.
pub struct StopTimeRow<'a> {
    pub trip_id: &'a str,
    pub stop_id: &'a str,
    /// `stop_sequence`. GTFS defines it as a non-negative integer; a value that
    /// will not parse becomes 0, which sorts first and, the sort being stable,
    /// leaves such rows in file order.
    pub seq: u32,
    pub arr: u32,
    pub dep: u32,
    /// `shape_dist_traveled` in the feed's own units, an ordering key only, or
    /// NaN when the row carries none. A sentinel rather than an `Option` because
    /// this is the field that decides how big a `stop_times` row is, and there
    /// are billions of them.
    pub dist: f32,
}

/// Stream `dir/stop_times.txt`, calling `f` once per row that carries a trip id
/// and a usable time. Returns `None` only when the file is absent.
///
/// `stop_times.txt` is the largest file in almost every feed — 52 GB of `Csv` for
/// `great_britain` alone — and every row is reduced to a handful of bytes
/// immediately, so materializing it as `Vec<Vec<String>>` first is pure waste.
/// Like [`read_shapes`] this is `BufReader` + `read_until` + an immediate typed
/// parse.
///
/// Rows arrive in **file order**, which callers depend on: the `stop_sequence`
/// sort downstream is stable, so feeds that repeat a `stop_sequence` within a
/// trip are resolved by their file order.
pub fn stream_stop_times(dir: &Path, mut f: impl FnMut(StopTimeRow)) -> Option<()> {
    let file = std::fs::File::open(dir.join("stop_times.txt")).ok()?;
    let mut reader = std::io::BufReader::new(file);
    let mut raw: Vec<u8> = Vec::new();

    reader.read_until(b'\n', &mut raw).ok()?;
    let header_line = String::from_utf8_lossy(&raw);
    let header = split_line(header_line.trim_end_matches(['\n', '\r']));
    // Every column is optional, exactly as [`Csv::get`] treats a missing one: a
    // feed with no `stop_id` column yields no usable rows rather than an error.
    let col = |name: &str| {
        header.iter().position(|h| h.trim().trim_start_matches('\u{feff}') == name)
    };
    let (c_trip, c_stop) = (col("trip_id"), col("stop_id"));
    let (c_seq, c_arr, c_dep) =
        (col("stop_sequence"), col("arrival_time"), col("departure_time"));
    let c_dist = col("shape_dist_traveled");

    loop {
        raw.clear();
        if reader.read_until(b'\n', &mut raw).ok()? == 0 {
            break;
        }
        let line = String::from_utf8_lossy(&raw);
        let line = line.trim_end_matches(['\n', '\r']);
        if line.is_empty() {
            continue;
        }
        let fields = split_line(line);
        let get = |i: Option<usize>| i.and_then(|i| fields.get(i)).map(|s| s.trim()).unwrap_or("");
        let trip_id = get(c_trip);
        if trip_id.is_empty() {
            continue;
        }
        let Some((arr, dep)) = stop_time_pair(get(c_arr), get(c_dep)) else {
            continue;
        };
        f(StopTimeRow {
            trip_id,
            stop_id: get(c_stop),
            seq: get(c_seq).parse().unwrap_or(0),
            arr,
            dep,
            dist: get(c_dist).parse().unwrap_or(f32::NAN),
        });
    }
    Some(())
}

/// A `shapes.txt` row before ordering: `(sequence, lat_e7, lon_e7, dist)`, where
/// `dist` is NaN when the row has none. 16 bytes rather than the 32 an
/// `(i64, i32, i32, Option<f64>)` needs — this intermediate has exactly the
/// defect the streaming loader exists to avoid.
type RawShapePoint = (u32, i32, i32, f32);

/// Stream `dir/shapes.txt` into one polyline per `shape_id`. Returns `None` when
/// the file is absent (it is optional) or carries no usable columns.
pub fn read_shapes(dir: &Path) -> Option<HashMap<String, Shape>> {
    let file = std::fs::File::open(dir.join("shapes.txt")).ok()?;
    let mut reader = std::io::BufReader::new(file);
    let mut raw: Vec<u8> = Vec::new();

    reader.read_until(b'\n', &mut raw).ok()?;
    let header_line = String::from_utf8_lossy(&raw);
    let header = split_line(header_line.trim_end_matches(['\n', '\r']));
    let col = |name: &str| {
        header.iter().position(|h| h.trim().trim_start_matches('\u{feff}') == name)
    };
    let (c_id, c_lat, c_lon, c_seq) =
        (col("shape_id")?, col("shape_pt_lat")?, col("shape_pt_lon")?, col("shape_pt_sequence")?);
    let c_dist = col("shape_dist_traveled");

    // (sequence, lat_e7, lon_e7, dist) per shape, sorted once at the end.
    let mut acc: HashMap<String, Vec<RawShapePoint>> = HashMap::new();
    loop {
        raw.clear();
        if reader.read_until(b'\n', &mut raw).ok()? == 0 {
            break;
        }
        let line = String::from_utf8_lossy(&raw);
        let line = line.trim_end_matches(['\n', '\r']);
        if line.is_empty() {
            continue;
        }
        let f = split_line(line);
        let get = |i: usize| f.get(i).map(|s| s.trim()).unwrap_or("");
        let id = get(c_id);
        if id.is_empty() {
            continue;
        }
        let lat: f64 = match get(c_lat).parse() {
            Ok(v) => v,
            Err(_) => continue,
        };
        let lon: f64 = match get(c_lon).parse() {
            Ok(v) => v,
            Err(_) => continue,
        };
        let seq: u32 = get(c_seq).parse().unwrap_or(0);
        let dist = c_dist.map_or(f32::NAN, |i| get(i).parse().unwrap_or(f32::NAN));
        acc.entry(id.to_string()).or_default().push((
            seq,
            (lat * 1e7) as i32,
            (lon * 1e7) as i32,
            dist,
        ));
    }

    let mut out = HashMap::with_capacity(acc.len());
    for (id, mut pts) in acc {
        pts.sort_by_key(|p| p.0);
        let all_dist = pts.iter().all(|p| !p.3.is_nan());
        out.insert(
            id,
            Shape {
                lat_e7: pts.iter().map(|p| p.1).collect(),
                lon_e7: pts.iter().map(|p| p.2).collect(),
                dist: if all_dist {
                    Some(pts.iter().map(|p| p.3).collect())
                } else {
                    None
                },
            },
        );
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The `shapes.txt` intermediate is one of the two structures a world build
    /// has billions of, so its size is part of the contract, not an accident.
    #[test]
    fn a_raw_shape_point_stays_sixteen_bytes() {
        assert_eq!(std::mem::size_of::<RawShapePoint>(), 16);
    }

    /// The streaming loader must agree with the [`Csv`] path row for row: the
    /// tests that pin the pack's contents feed it a `Csv`, while the host tool
    /// streams, and a divergence would mean the two build different packs.
    #[test]
    fn streaming_stop_times_matches_parsing_the_whole_table() {
        // A BOM, a quoted comma, a blank line, each arrival/departure fallback,
        // an unparseable sequence, a row with no usable time, a row with no trip
        // id, and a row that simply stops early.
        let text = "\u{feff}trip_id,stop_id,stop_sequence,arrival_time,departure_time,shape_dist_traveled\n\
             T1,S1,1,08:00:00,08:00:05,0\n\
             T1,\"S,2\",2,08:10:00,,1.5\n\
             \n\
             T1,S3,3,,08:20:00,\n\
             ,S4,4,08:30:00,08:30:00,9\n\
             T2,S5,notanumber,25:10:00,25:10:00,\n\
             T2,S6,6,,,7\n\
             T2,S7\n";

        let dir = std::env::temp_dir()
            .join(format!("gtfs_ingest_stream_{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        std::fs::write(dir.join("stop_times.txt"), text).expect("write stop_times.txt");

        // NaN is the "no shape_dist_traveled" sentinel and compares unequal to
        // itself, so rows are compared by the dist's bits.
        type Row = (String, String, u32, u32, u32, u32);
        let mut streamed: Vec<Row> = Vec::new();
        stream_stop_times(&dir, |r| {
            streamed.push((
                r.trip_id.to_string(),
                r.stop_id.to_string(),
                r.seq,
                r.arr,
                r.dep,
                r.dist.to_bits(),
            ));
        })
        .expect("stop_times.txt is present");
        let _ = std::fs::remove_dir_all(&dir);

        let csv = parse_csv(text);
        let tabled: Vec<Row> = csv
            .rows
            .iter()
            .filter_map(|row| {
                let trip_id = csv.get(row, "trip_id");
                if trip_id.is_empty() {
                    return None;
                }
                let (arr, dep) = stop_time_pair(
                    csv.get(row, "arrival_time"),
                    csv.get(row, "departure_time"),
                )?;
                Some((
                    trip_id.to_string(),
                    csv.get(row, "stop_id").to_string(),
                    csv.get(row, "stop_sequence").trim().parse().unwrap_or(0),
                    arr,
                    dep,
                    csv.get(row, "shape_dist_traveled")
                        .trim()
                        .parse()
                        .unwrap_or(f32::NAN)
                        .to_bits(),
                ))
            })
            .collect();

        assert_eq!(streamed, tabled);
        // Spelled out so the filters themselves are pinned, not just the two
        // paths' agreement: T1/S1 keeps both times, "S,2" survives the quoted
        // comma with departure falling back to arrival, S3 arrives at its
        // departure, S4 has no trip, S5's sequence is unusable and its time is
        // past midnight, and S6/S7 carry no time at all.
        assert_eq!(streamed.len(), 4, "{streamed:?}");
        assert_eq!(streamed[0], ("T1".into(), "S1".into(), 1, 28800, 28805, 0f32.to_bits()));
        assert_eq!(streamed[1], ("T1".into(), "S,2".into(), 2, 29400, 29400, 1.5f32.to_bits()));
        assert_eq!(streamed[2].3, 30000, "an absent arrival takes the departure");
        assert_eq!(streamed[3], ("T2".into(), "S5".into(), 0, 90600, 90600, f32::NAN.to_bits()));
    }
}
