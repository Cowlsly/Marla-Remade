//! Read the tables of a MusicBrainz full export.
//!
//! The tables in `mbdump.tar.bz2` are PostgreSQL `COPY ... TO` output in the
//! default TEXT format: one row per line, columns separated by TAB, `\N` for
//! NULL, and backslash escapes (`\\`, `\t`, `\n`, `\r`, `\b`, `\f`, `\v`) for the
//! characters that would otherwise break the framing. Real MusicBrainz titles do
//! contain tabs and newlines, so unescaping is not optional.
//!
//! Verified against the live export rather than assumed:
//!   * the archive member names in `mbdump.tar.bz2` are `mbdump/<table>` with no
//!     schema prefix (`mbdump/area`, `mbdump/artist`, ...), in ASCII order, after
//!     the leading `TIMESTAMP`, `COPYING`, `README`, `REPLICATION_SEQUENCE` and
//!     `SCHEMA_SEQUENCE` members. The non-`musicbrainz`-schema dumps use
//!     `mbdump/<schema>.<table>` instead (confirmed on
//!     `mbdump-documentation.tar.bz2`), so both spellings are accepted.
//!   * `TIMESTAMP` holds e.g. `2026-08-19 00:25:41.835127+00`.
//!
//! Two input shapes are supported. A directory is the fast path for repeated
//! builds and the only one the tests use. A `.tar.bz2` is streamed through the
//! system `tar`, so a full build never materialises the ~40 GB of extracted
//! tables; the cost is one decompression pass per table read.

use std::borrow::Cow;
use std::fs::File;
use std::io::{self, BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

/// One row of a COPY dump, borrowing the line buffer.
pub struct Row<'a> {
    line: &'a [u8],
    fields: &'a [(u32, u32)],
}

impl<'a> Row<'a> {
    pub fn len(&self) -> usize {
        self.fields.len()
    }

    pub fn is_empty(&self) -> bool {
        self.fields.is_empty()
    }

    pub fn raw(&self, i: usize) -> &'a [u8] {
        match self.fields.get(i) {
            Some(&(a, b)) => &self.line[a as usize..b as usize],
            None => &[],
        }
    }

    pub fn is_null(&self, i: usize) -> bool {
        self.raw(i) == b"\\N"
    }

    /// The field with COPY escapes resolved. NULL reads as `""`; callers that
    /// care about the difference ask `is_null` first.
    pub fn str(&self, i: usize) -> Cow<'a, str> {
        let raw = self.raw(i);
        if raw == b"\\N" {
            return Cow::Borrowed("");
        }
        if !raw.contains(&b'\\') {
            return String::from_utf8_lossy(raw);
        }
        let mut out = Vec::with_capacity(raw.len());
        let mut it = raw.iter().copied();
        while let Some(b) = it.next() {
            if b != b'\\' {
                out.push(b);
                continue;
            }
            match it.next() {
                Some(b'n') => out.push(b'\n'),
                Some(b't') => out.push(b'\t'),
                Some(b'r') => out.push(b'\r'),
                Some(b'b') => out.push(8),
                Some(b'f') => out.push(12),
                Some(b'v') => out.push(11),
                Some(b'\\') => out.push(b'\\'),
                Some(other) => {
                    out.push(b'\\');
                    out.push(other);
                }
                None => out.push(b'\\'),
            }
        }
        Cow::Owned(String::from_utf8_lossy(&out).into_owned())
    }

    pub fn i64(&self, i: usize) -> Option<i64> {
        let raw = self.raw(i);
        if raw.is_empty() || raw == b"\\N" {
            return None;
        }
        let (neg, digits) = match raw[0] {
            b'-' => (true, &raw[1..]),
            _ => (false, raw),
        };
        if digits.is_empty() {
            return None;
        }
        let mut v: i64 = 0;
        for &c in digits {
            if !c.is_ascii_digit() {
                return None;
            }
            v = v.checked_mul(10)?.checked_add((c - b'0') as i64)?;
        }
        Some(if neg { -v } else { v })
    }

    pub fn u32(&self, i: usize) -> Option<u32> {
        match self.i64(i) {
            Some(v) if (0..=u32::MAX as i64).contains(&v) => Some(v as u32),
            _ => None,
        }
    }

    /// Does the field look like a bare (unhyphenated or hyphenated) UUID?
    pub fn looks_like_uuid(&self, i: usize) -> bool {
        let raw = self.raw(i);
        let hex = raw.iter().filter(|c| c.is_ascii_hexdigit()).count();
        let dashes = raw.iter().filter(|&&c| c == b'-').count();
        hex == 32 && hex + dashes == raw.len()
    }
}

/// A table's byte stream plus, for the archive path, the `tar` process feeding it.
type TableStream = (Box<dyn Read>, Option<Child>);

/// Where the tables come from.
pub enum Input {
    /// A directory holding the table files, either `<dir>/<table>` or
    /// `<dir>/mbdump/<table>` (i.e. an extracted archive works unchanged).
    Dir(PathBuf),
    /// An `mbdump*.tar.bz2`, streamed through the system `tar`.
    Archive(PathBuf),
}

impl Input {
    /// Classify by extension so the CLI takes either without a flag.
    pub fn detect(path: &Path) -> Input {
        let s = path.to_string_lossy().to_ascii_lowercase();
        if s.ends_with(".tar.bz2") || s.ends_with(".tbz2") || s.ends_with(".tar") {
            Input::Archive(path.to_path_buf())
        } else {
            Input::Dir(path.to_path_buf())
        }
    }

    pub fn describe(&self) -> String {
        match self {
            Input::Dir(p) => format!("directory {}", p.display()),
            Input::Archive(p) => format!("archive {}", p.display()),
        }
    }

    /// The dump's `TIMESTAMP` as `YYYYMMDD`, or 0 when it is not readable.
    pub fn dump_date(&self) -> u32 {
        let text = match self {
            Input::Dir(p) => {
                std::fs::read_to_string(p.join("TIMESTAMP")).ok()
            }
            Input::Archive(p) => {
                let out = Command::new("tar").arg("-xOf").arg(p).arg("TIMESTAMP").output().ok();
                out.and_then(|o| String::from_utf8(o.stdout).ok())
            }
        };
        let text = match text {
            Some(t) => t,
            None => return 0,
        };
        let d: Vec<u8> = text.bytes().filter(|c| c.is_ascii_digit()).take(8).collect();
        if d.len() < 8 {
            return 0;
        }
        std::str::from_utf8(&d).ok().and_then(|s| s.parse().ok()).unwrap_or(0)
    }

    fn open_reader(&self, table: &str) -> io::Result<Option<TableStream>> {
        match self {
            Input::Dir(dir) => {
                for cand in [dir.join("mbdump").join(table), dir.join(table)] {
                    if cand.is_file() {
                        return Ok(Some((Box::new(File::open(cand)?), None)));
                    }
                }
                Ok(None)
            }
            Input::Archive(path) => {
                // `tar` picks the decompressor from the magic bytes, so this works
                // for .tar, .tar.bz2 and .tar.gz alike, and streams: nothing is
                // written to disk. bsdtar (Windows) and GNU tar both support it.
                let member = format!("mbdump/{table}");
                let mut child = Command::new("tar")
                    .arg("-xOf")
                    .arg(path)
                    .arg(&member)
                    .stdout(Stdio::piped())
                    .stderr(Stdio::null())
                    .spawn()
                    .map_err(|e| {
                        io::Error::new(
                            e.kind(),
                            format!(
                                "could not run `tar` to stream {member} out of {}: {e}. \
                                 Archive input needs tar on PATH; extract the archive and \
                                 pass the directory instead.",
                                path.display()
                            ),
                        )
                    })?;
                let out = child.stdout.take().expect("piped stdout");
                Ok(Some((Box::new(out), Some(child))))
            }
        }
    }

    /// Call `f` once per row of `table`. Returns the number of rows, or `None` if
    /// the table is not in this input at all.
    ///
    /// Rows are handed out as borrowed slices and never accumulated, so this is
    /// safe on `track` (57 M rows) as long as the caller does not accumulate
    /// either.
    pub fn each_row<F>(&self, table: &str, mut f: F) -> io::Result<Option<u64>>
    where
        F: FnMut(&Row<'_>) -> io::Result<()>,
    {
        let Some((reader, child)) = self.open_reader(table)? else {
            return Ok(None);
        };
        let mut reader = BufReader::with_capacity(1 << 20, reader);
        let mut line: Vec<u8> = Vec::with_capacity(4096);
        let mut fields: Vec<(u32, u32)> = Vec::with_capacity(32);
        let mut rows = 0u64;
        loop {
            line.clear();
            let n = reader.read_until(b'\n', &mut line)?;
            if n == 0 {
                break;
            }
            while matches!(line.last(), Some(b'\n') | Some(b'\r')) {
                line.pop();
            }
            if line.is_empty() {
                continue;
            }
            fields.clear();
            let mut start = 0u32;
            for (i, &c) in line.iter().enumerate() {
                if c == b'\t' {
                    fields.push((start, i as u32));
                    start = i as u32 + 1;
                }
            }
            fields.push((start, line.len() as u32));
            f(&Row { line: &line, fields: &fields })?;
            rows += 1;
        }
        if let Some(mut child) = child {
            // A non-zero tar exit means the member was missing or the stream was
            // truncated; treating that as an empty table would silently produce a
            // wrong pack.
            let status = child.wait()?;
            if !status.success() && rows == 0 {
                return Ok(None);
            }
            if !status.success() {
                return Err(other_err(format!(
                    "tar failed after {rows} rows of {table}; the archive is truncated"
                )));
            }
        }
        Ok(Some(rows))
    }
}

/// A table's expected shape, checked on the first row.
///
/// The column order of a COPY dump is the table's physical column order, which
/// this crate hard-codes from `admin/sql/CreateTables.sql`. If MusicBrainz ever
/// reorders or inserts a column, silently reading the wrong field would produce a
/// plausible-looking but wrong pack, so every table asserts its arity and spot
/// checks the columns whose types are distinctive.
pub struct Shape {
    pub table: &'static str,
    pub min_fields: usize,
    /// Column indices that must parse as integers.
    pub ints: &'static [usize],
    /// Column indices that must look like UUIDs.
    pub uuids: &'static [usize],
}

impl Shape {
    pub fn check(&self, row: &Row<'_>) -> io::Result<()> {
        if row.len() < self.min_fields {
            return Err(other_err(format!(
                "table `{}`: expected at least {} columns, found {}. The dump's schema \
                 does not match what this build was written against; check \
                 SCHEMA_SEQUENCE and admin/sql/CreateTables.sql before trusting any pack.",
                self.table,
                self.min_fields,
                row.len()
            )));
        }
        for &i in self.ints {
            if !row.is_null(i) && row.i64(i).is_none() {
                return Err(other_err(format!(
                    "table `{}`: column {i} should be an integer but is {:?}. Column order \
                     has almost certainly changed upstream.",
                    self.table,
                    String::from_utf8_lossy(row.raw(i))
                )));
            }
        }
        for &i in self.uuids {
            if !row.looks_like_uuid(i) {
                return Err(other_err(format!(
                    "table `{}`: column {i} should be a UUID but is {:?}. Column order has \
                     almost certainly changed upstream.",
                    self.table,
                    String::from_utf8_lossy(row.raw(i))
                )));
            }
        }
        Ok(())
    }
}

pub fn other_err(msg: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row_of(line: &str) -> (Vec<u8>, Vec<(u32, u32)>) {
        let bytes = line.as_bytes().to_vec();
        let mut fields = Vec::new();
        let mut start = 0u32;
        for (i, &c) in bytes.iter().enumerate() {
            if c == b'\t' {
                fields.push((start, i as u32));
                start = i as u32 + 1;
            }
        }
        fields.push((start, bytes.len() as u32));
        (bytes, fields)
    }

    #[test]
    fn parses_copy_text_format() {
        let (line, fields) = row_of("42\tPink Floyd\t\\N\t\\N\t1965\t");
        let r = Row { line: &line, fields: &fields };
        assert_eq!(r.len(), 6);
        assert_eq!(r.u32(0), Some(42));
        assert_eq!(&*r.str(1), "Pink Floyd");
        assert!(r.is_null(2));
        assert_eq!(&*r.str(2), "");
        assert_eq!(r.i64(2), None);
        assert_eq!(r.u32(4), Some(1965));
        assert_eq!(&*r.str(5), "", "trailing empty field is an empty string, not NULL");
        assert!(!r.is_null(5));
    }

    #[test]
    fn unescapes_titles_that_would_break_framing() {
        let (line, fields) = row_of("1\tA\\tB\\nC\\\\D\t2");
        let r = Row { line: &line, fields: &fields };
        assert_eq!(&*r.str(1), "A\tB\nC\\D");
        assert_eq!(r.u32(2), Some(2));
    }

    #[test]
    fn rejects_bad_integers_and_detects_uuids() {
        let (line, fields) = row_of("x\tf27ec8db-af05-4f36-916e-3d57f91ecf5e\tnope");
        let r = Row { line: &line, fields: &fields };
        assert_eq!(r.i64(0), None);
        assert!(r.looks_like_uuid(1));
        assert!(!r.looks_like_uuid(2));
        let shape = Shape { table: "t", min_fields: 3, ints: &[0], uuids: &[1] };
        assert!(shape.check(&r).is_err(), "a non-integer id must be caught, not ignored");
        let shape = Shape { table: "t", min_fields: 4, ints: &[], uuids: &[] };
        assert!(shape.check(&r).is_err(), "too few columns must be caught");
    }
}
