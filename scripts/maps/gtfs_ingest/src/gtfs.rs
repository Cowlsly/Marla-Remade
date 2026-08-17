//! Minimal, dependency-free GTFS reader: an RFC 4180-ish CSV parser plus typed
//! loaders for the GTFS files the offline transit index needs
//! (`stops`, `routes`, `trips`, `stop_times`, `calendar`, `calendar_dates`).
//!
//! GTFS is CSV text; times may exceed 24:00:00 (legal, means "after service
//! midnight"). We keep everything in seconds-since-service-midnight.

use std::collections::HashMap;
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
