//! Minimal, dependency-free GTFS reader: an RFC 4180-ish CSV parser plus typed
//! loaders for the GTFS files the offline transit index needs
//! (`stops`, `routes`, `trips`, `stop_times`, `calendar`, `calendar_dates`,
//! `shapes`).
//!
//! GTFS is CSV text; times may exceed 24:00:00 (legal, means "after service
//! midnight"). We keep everything in seconds-since-service-midnight.
//!
//! `shapes.txt` gets its own streaming loader rather than a [`Csv`]: it is often
//! the largest file in a feed, and a `Csv` would hold every coordinate as a
//! `String`.

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

/// One `shapes.txt` polyline, in `shape_pt_sequence` order.
pub struct Shape {
    pub lat_e7: Vec<i32>,
    pub lon_e7: Vec<i32>,
    /// `shape_dist_traveled`, present only when *every* point carries one.
    /// Feed-defined units (km, mi, ft, ...): only ever compared, never converted.
    pub dist: Option<Vec<f64>>,
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

/// A `shapes.txt` row before ordering: `(sequence, lat_e7, lon_e7, dist)`.
type RawShapePoint = (i64, i32, i32, Option<f64>);

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
        let seq: i64 = get(c_seq).parse().unwrap_or(0);
        let dist = c_dist.and_then(|i| get(i).parse::<f64>().ok());
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
        let all_dist = pts.iter().all(|p| p.3.is_some());
        out.insert(
            id,
            Shape {
                lat_e7: pts.iter().map(|p| p.1).collect(),
                lon_e7: pts.iter().map(|p| p.2).collect(),
                dist: if all_dist {
                    Some(pts.iter().map(|p| p.3.unwrap_or(0.0)).collect())
                } else {
                    None
                },
            },
        );
    }
    Some(out)
}
