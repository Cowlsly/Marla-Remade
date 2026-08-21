//! A minimal JSON reader, for the transit registries.
//!
//! `gtfs_ingest` has **zero** dependencies, which is what lets it build and resolve
//! offline on any box. The Transitous and transitland-atlas registries are JSON, so
//! reading them needs a parser; pulling in serde for two file formats would trade
//! that property away.
//!
//! Deliberately small, and enough for exactly this job:
//!
//! * Objects, arrays, strings, numbers, `true`/`false`/`null`.
//! * String escapes including `\uXXXX`, with surrogate pairs joined -- agency names
//!   in these registries are full Unicode.
//! * Numbers are kept as `f64`; nothing here reads one, but discarding them would
//!   make the parser reject documents it should accept.
//!
//! What it does not do: streaming, duplicate-key policy, or any error recovery. A
//! malformed registry is an error with a byte offset, not a partial parse.

use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Number(f64),
    String(String),
    Array(Vec<Json>),
    Object(BTreeMap<String, Json>),
}

impl Json {
    /// A member of an object, or `None` when this is not an object or the key is
    /// absent. Chains, so `v.get("urls").and_then(|u| u.get("static_current"))`
    /// reads the way the document does.
    pub fn get(&self, key: &str) -> Option<&Json> {
        match self {
            Json::Object(m) => m.get(key),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Json::String(s) => Some(s.as_str()),
            _ => None,
        }
    }

    /// The elements of an array, or an empty slice. A registry field that is
    /// sometimes a single value and sometimes a list is common enough that callers
    /// should not have to branch.
    pub fn as_array(&self) -> &[Json] {
        match self {
            Json::Array(v) => v.as_slice(),
            _ => &[],
        }
    }

    /// Truthiness in the sense the registries use: present, and not `null`, `false`,
    /// an empty string, or an empty container.
    pub fn is_truthy(&self) -> bool {
        match self {
            Json::Null => false,
            Json::Bool(b) => *b,
            Json::Number(n) => *n != 0.0,
            Json::String(s) => !s.is_empty(),
            Json::Array(v) => !v.is_empty(),
            Json::Object(m) => !m.is_empty(),
        }
    }
}

pub fn parse(text: &str) -> Result<Json, String> {
    let b = text.as_bytes();
    let mut i = 0usize;
    let v = value(b, &mut i)?;
    skip_ws(b, &mut i);
    if i != b.len() {
        return Err(format!("trailing bytes at offset {i}"));
    }
    Ok(v)
}

fn skip_ws(b: &[u8], i: &mut usize) {
    while *i < b.len() && matches!(b[*i], b' ' | b'\t' | b'\n' | b'\r') {
        *i += 1;
    }
}

fn expect(b: &[u8], i: &mut usize, c: u8) -> Result<(), String> {
    skip_ws(b, i);
    if b.get(*i) != Some(&c) {
        return Err(format!(
            "expected '{}' at offset {i}, found {:?}",
            c as char,
            b.get(*i).map(|x| *x as char)
        ));
    }
    *i += 1;
    Ok(())
}

fn value(b: &[u8], i: &mut usize) -> Result<Json, String> {
    skip_ws(b, i);
    match b.get(*i) {
        None => Err(format!("unexpected end of input at offset {i}")),
        Some(b'{') => {
            *i += 1;
            let mut map = BTreeMap::new();
            skip_ws(b, i);
            if b.get(*i) == Some(&b'}') {
                *i += 1;
                return Ok(Json::Object(map));
            }
            loop {
                skip_ws(b, i);
                let key = string(b, i)?;
                expect(b, i, b':')?;
                map.insert(key, value(b, i)?);
                skip_ws(b, i);
                match b.get(*i) {
                    Some(b',') => *i += 1,
                    Some(b'}') => {
                        *i += 1;
                        return Ok(Json::Object(map));
                    }
                    other => {
                        return Err(format!(
                            "expected ',' or '}}' at offset {i}, found {:?}",
                            other.map(|x| *x as char)
                        ))
                    }
                }
            }
        }
        Some(b'[') => {
            *i += 1;
            let mut items = Vec::new();
            skip_ws(b, i);
            if b.get(*i) == Some(&b']') {
                *i += 1;
                return Ok(Json::Array(items));
            }
            loop {
                items.push(value(b, i)?);
                skip_ws(b, i);
                match b.get(*i) {
                    Some(b',') => *i += 1,
                    Some(b']') => {
                        *i += 1;
                        return Ok(Json::Array(items));
                    }
                    other => {
                        return Err(format!(
                            "expected ',' or ']' at offset {i}, found {:?}",
                            other.map(|x| *x as char)
                        ))
                    }
                }
            }
        }
        Some(b'"') => Ok(Json::String(string(b, i)?)),
        Some(b't') => literal(b, i, "true").map(|_| Json::Bool(true)),
        Some(b'f') => literal(b, i, "false").map(|_| Json::Bool(false)),
        Some(b'n') => literal(b, i, "null").map(|_| Json::Null),
        Some(_) => number(b, i),
    }
}

fn literal(b: &[u8], i: &mut usize, want: &str) -> Result<(), String> {
    if b[*i..].starts_with(want.as_bytes()) {
        *i += want.len();
        Ok(())
    } else {
        Err(format!("expected '{want}' at offset {i}"))
    }
}

fn number(b: &[u8], i: &mut usize) -> Result<Json, String> {
    let start = *i;
    while *i < b.len() && matches!(b[*i], b'0'..=b'9' | b'-' | b'+' | b'.' | b'e' | b'E') {
        *i += 1;
    }
    if *i == start {
        return Err(format!("not a value at offset {start}"));
    }
    std::str::from_utf8(&b[start..*i])
        .ok()
        .and_then(|s| s.parse().ok())
        .map(Json::Number)
        .ok_or_else(|| format!("bad number at offset {start}"))
}

fn string(b: &[u8], i: &mut usize) -> Result<String, String> {
    expect(b, i, b'"')?;
    let mut out = String::new();
    loop {
        match b.get(*i) {
            None => return Err(format!("unterminated string at offset {i}")),
            Some(b'"') => {
                *i += 1;
                return Ok(out);
            }
            Some(b'\\') => {
                *i += 1;
                match b.get(*i) {
                    Some(b'"') => out.push('"'),
                    Some(b'\\') => out.push('\\'),
                    Some(b'/') => out.push('/'),
                    Some(b'b') => out.push('\u{8}'),
                    Some(b'f') => out.push('\u{c}'),
                    Some(b'n') => out.push('\n'),
                    Some(b'r') => out.push('\r'),
                    Some(b't') => out.push('\t'),
                    Some(b'u') => {
                        let hi = hex4(b, i)?;
                        // A high surrogate must be followed by its low half, or the
                        // agency name comes out as two replacement characters.
                        if (0xD800..0xDC00).contains(&hi) {
                            if b.get(*i + 1) == Some(&b'\\') && b.get(*i + 2) == Some(&b'u') {
                                *i += 2;
                                let lo = hex4(b, i)?;
                                if (0xDC00..0xE000).contains(&lo) {
                                    let c = 0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00);
                                    out.push(char::from_u32(c).unwrap_or('\u{fffd}'));
                                } else {
                                    out.push('\u{fffd}');
                                    out.push(char::from_u32(lo).unwrap_or('\u{fffd}'));
                                }
                            } else {
                                out.push('\u{fffd}');
                            }
                        } else {
                            out.push(char::from_u32(hi).unwrap_or('\u{fffd}'));
                        }
                    }
                    other => {
                        return Err(format!(
                            "bad escape at offset {i}: {:?}",
                            other.map(|x| *x as char)
                        ))
                    }
                }
                *i += 1;
            }
            Some(_) => {
                // Copy the whole UTF-8 run up to the next escape or quote in one go.
                let start = *i;
                while *i < b.len() && b[*i] != b'"' && b[*i] != b'\\' {
                    *i += 1;
                }
                out.push_str(
                    std::str::from_utf8(&b[start..*i])
                        .map_err(|_| format!("invalid UTF-8 at offset {start}"))?,
                );
            }
        }
    }
}

/// Read the four hex digits after a `\u`, leaving `i` on the last of them.
fn hex4(b: &[u8], i: &mut usize) -> Result<u32, String> {
    let start = *i + 1;
    let end = start + 4;
    if end > b.len() {
        return Err(format!("truncated \\u escape at offset {start}"));
    }
    let s = std::str::from_utf8(&b[start..end]).map_err(|_| format!("bad \\u at {start}"))?;
    let v = u32::from_str_radix(s, 16).map_err(|_| format!("bad \\u escape at offset {start}"))?;
    *i = end - 1;
    Ok(v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_shapes_a_registry_uses() {
        let v = parse(
            r#"{"sources":[{"name":"SF-bayarea","transitland-atlas-id":"f-9q9-bayarea",
               "url":"https://example.com/gtfs.zip","skip":false,"spec":"gtfs"}]}"#,
        )
        .unwrap();
        let s = &v.get("sources").unwrap().as_array()[0];
        assert_eq!(s.get("name").unwrap().as_str(), Some("SF-bayarea"));
        assert_eq!(
            s.get("transitland-atlas-id").unwrap().as_str(),
            Some("f-9q9-bayarea")
        );
        assert!(!s.get("skip").unwrap().is_truthy());
        assert_eq!(v.get("nope"), None);
    }

    #[test]
    fn reads_a_nested_atlas_entry() {
        let v = parse(
            r#"{"feeds":[{"id":"f-9q9-bayarea","spec":"gtfs",
               "urls":{"static_current":"https://a/x.zip",
                       "static_historic":["https://a/old.zip","https://a/older.zip"]},
               "authorization":{"type":"query_param"}}]}"#,
        )
        .unwrap();
        let f = &v.get("feeds").unwrap().as_array()[0];
        let urls = f.get("urls").unwrap();
        assert_eq!(urls.get("static_current").unwrap().as_str(), Some("https://a/x.zip"));
        assert_eq!(urls.get("static_historic").unwrap().as_array().len(), 2);
        assert!(f.get("authorization").unwrap().is_truthy());
    }

    #[test]
    fn handles_escapes_and_unicode() {
        let v = parse(r#"{"a":"quote\" back\\ slash\/ nl\n tab\t","b":"\u00e9\u4e2d"}"#).unwrap();
        assert_eq!(
            v.get("a").unwrap().as_str(),
            Some("quote\" back\\ slash/ nl\n tab\t")
        );
        assert_eq!(v.get("b").unwrap().as_str(), Some("é中"));
    }

    #[test]
    fn joins_surrogate_pairs() {
        // An emoji in an agency name, which is not hypothetical.
        let v = parse(r#"{"a":"\ud83d\ude80"}"#).unwrap();
        assert_eq!(v.get("a").unwrap().as_str(), Some("🚀"));
        // A lone high surrogate becomes a replacement character rather than an error.
        let v = parse(r#"{"a":"\ud83dx"}"#).unwrap();
        assert_eq!(v.get("a").unwrap().as_str(), Some("\u{fffd}x"));
    }

    #[test]
    fn numbers_booleans_and_null_round_trip_enough_to_not_reject_a_document() {
        let v = parse(r#"{"a":1,"b":-2.5,"c":1e3,"d":true,"e":false,"f":null}"#).unwrap();
        assert_eq!(v.get("a"), Some(&Json::Number(1.0)));
        assert_eq!(v.get("b"), Some(&Json::Number(-2.5)));
        assert_eq!(v.get("c"), Some(&Json::Number(1000.0)));
        assert!(v.get("d").unwrap().is_truthy());
        assert!(!v.get("e").unwrap().is_truthy());
        assert!(!v.get("f").unwrap().is_truthy());
    }

    #[test]
    fn whitespace_and_empty_containers_are_fine() {
        assert_eq!(parse("  {  }  ").unwrap(), Json::Object(BTreeMap::new()));
        assert_eq!(parse("[ ]").unwrap(), Json::Array(vec![]));
        assert_eq!(
            parse("{\n\t\"a\" : [ 1 , 2 ]\n}").unwrap().get("a").unwrap().as_array().len(),
            2
        );
    }

    #[test]
    fn malformed_input_is_an_error_with_an_offset_not_a_partial_parse() {
        for bad in [
            "",
            "{",
            "{\"a\"}",
            "{\"a\":}",
            "{\"a\":1,}",
            "[1,]",
            "\"unterminated",
            "{\"a\":1} trailing",
            "tru",
            "{\"a\":\"\\q\"}",
            "{\"a\":\"\\u00\"}",
        ] {
            let err = parse(bad).unwrap_err();
            assert!(!err.is_empty(), "{bad:?} must report something");
        }
    }

    #[test]
    fn accessors_do_not_panic_on_the_wrong_shape() {
        let v = Json::String("x".into());
        assert_eq!(v.get("a"), None);
        assert!(v.as_array().is_empty());
        assert_eq!(Json::Null.as_str(), None);
        assert!(Json::Number(1.0).as_array().is_empty());
    }
}
