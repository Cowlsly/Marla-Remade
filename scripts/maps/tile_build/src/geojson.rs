//! Reading the GeoJSONSeq the extractors emit.
//!
//! One `Feature` per line, with any of the six geometry types. Deliberately **not**
//! a general GeoJSON parser and deliberately not serde: this crate's only
//! dependency is `miniz_oxide`, which is what lets it resolve and build offline,
//! and adding a JSON stack for a format we also write ourselves would throw that
//! away.
//!
//! What that costs, stated plainly: nested objects inside `properties` are
//! ignored, and so is any geometry the six types do not cover
//! (`GeometryCollection`). Both are things our own writers never produce, and both
//! are counted and reported rather than silently skipped.
//!
//! Key order is *not* assumed. The geometry object is located and its extent
//! found by brace counting, then `type` and `coordinates` are looked up inside it,
//! so `{"coordinates":...,"type":...}` reads the same as the other way round.

use crate::geom::{Geometry, Pt};
use crate::mvt::Value;

/// One parsed feature: geometry in lon/lat, plus its flat properties.
#[derive(Debug, Clone, PartialEq)]
pub struct Feature {
    pub geometry: Geometry,
    pub props: Vec<(String, Value)>,
}

/// Parse one line. `None` when the line is not a feature we can use -- malformed,
/// or a geometry type this reader does not cover.
pub fn parse_feature(line: &str) -> Option<Feature> {
    // Work on the object's body, so "top level" means depth 0 for both this call
    // and the ones on the nested objects below.
    let body = strip_outer_braces(line)?;
    let geometry = find_object(body, "\"geometry\"")?;
    let type_name = string_value(geometry, "\"type\"")?;
    let coords_at = find_key(geometry, "\"coordinates\"")?;
    let (coords, _) = parse_coords(&geometry[coords_at..])?;

    let geometry = match type_name.as_str() {
        "Point" => Geometry::Points(vec![coords.as_point()?]),
        "MultiPoint" => Geometry::Points(coords.as_line()?),
        "LineString" => Geometry::Lines(vec![coords.as_line()?]),
        "MultiLineString" => Geometry::Lines(coords.as_lines()?),
        "Polygon" => Geometry::Polygons(vec![coords.as_lines()?]),
        "MultiPolygon" => Geometry::Polygons(coords.as_polygons()?),
        _ => return None,
    };

    let props = match find_object(body, "\"properties\"") {
        Some(p) => parse_props(p),
        None => Vec::new(),
    };
    Some(Feature { geometry, props })
}

/// The contents of one enclosing `{...}`.
fn strip_outer_braces(s: &str) -> Option<&str> {
    let s = s.trim();
    s.strip_prefix('{')?.strip_suffix('}')
}

/// The body of the object at `key`, without its braces. `hay` is an object *body*,
/// so its own keys sit at depth 0.
///
/// Brace counting, ignoring braces inside strings, so a `properties` value that
/// happens to contain `{` in a name does not truncate the object.
fn find_object<'a>(hay: &'a str, key: &str) -> Option<&'a str> {
    let at = find_key(hay, key)?;
    let b = hay.as_bytes();
    let mut i = at;
    while i < b.len() && b[i] != b'{' {
        // A non-object value for this key: not something we can read.
        if b[i] == b'"' || b[i] == b'[' {
            return None;
        }
        i += 1;
    }
    if i >= b.len() {
        return None;
    }
    let start = i + 1;
    let mut depth = 0usize;
    let mut in_str = false;
    while i < b.len() {
        match b[i] {
            b'\\' if in_str => i += 1,
            b'"' => in_str = !in_str,
            b'{' if !in_str => depth += 1,
            b'}' if !in_str => {
                depth -= 1;
                if depth == 0 {
                    return Some(&hay[start..i]);
                }
            }
            _ => {}
        }
        i += 1;
    }
    None
}

/// Byte offset just past `key":` at the top nesting level of `hay`.
fn find_key(hay: &str, key: &str) -> Option<usize> {
    let b = hay.as_bytes();
    let k = key.as_bytes();
    let mut i = 0usize;
    let mut depth = 0i32;
    let mut in_str = false;
    while i < b.len() {
        match b[i] {
            b'\\' if in_str => {
                i += 2;
                continue;
            }
            b'"' if !in_str && depth == 0 && b[i..].starts_with(k) => {
                let mut j = i + k.len();
                while j < b.len() && (b[j] == b' ' || b[j] == b':') {
                    if b[j] == b':' {
                        return Some(j + 1);
                    }
                    j += 1;
                }
                i = j;
                continue;
            }
            b'"' => in_str = !in_str,
            b'{' | b'[' if !in_str => depth += 1,
            b'}' | b']' if !in_str => depth -= 1,
            _ => {}
        }
        i += 1;
    }
    None
}

fn string_value(hay: &str, key: &str) -> Option<String> {
    let at = find_key(hay, key)?;
    let rest = hay[at..].trim_start();
    let rest = rest.strip_prefix('"')?;
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

/// A JSON value that is either a number or a list, which is all a `coordinates`
/// member can be at any depth.
#[derive(Debug, PartialEq)]
enum Coords {
    Num(f64),
    List(Vec<Coords>),
}

impl Coords {
    fn as_point(&self) -> Option<Pt> {
        match self {
            Coords::List(v) if v.len() >= 2 => match (&v[0], &v[1]) {
                // Extra ordinates (elevation, measure) are ignored, not rejected:
                // GeoJSON permits them and they carry nothing a tile can show.
                (Coords::Num(x), Coords::Num(y)) => Some((*x, *y)),
                _ => None,
            },
            _ => None,
        }
    }

    fn as_line(&self) -> Option<Vec<Pt>> {
        match self {
            Coords::List(v) => v.iter().map(Coords::as_point).collect(),
            _ => None,
        }
    }

    fn as_lines(&self) -> Option<Vec<Vec<Pt>>> {
        match self {
            Coords::List(v) => v.iter().map(Coords::as_line).collect(),
            _ => None,
        }
    }

    fn as_polygons(&self) -> Option<Vec<Vec<Vec<Pt>>>> {
        match self {
            Coords::List(v) => v.iter().map(Coords::as_lines).collect(),
            _ => None,
        }
    }
}

/// Parse a number-or-list value, returning it and the bytes consumed.
fn parse_coords(s: &str) -> Option<(Coords, usize)> {
    let b = s.as_bytes();
    let mut i = 0usize;
    while i < b.len() && b[i].is_ascii_whitespace() {
        i += 1;
    }
    if i >= b.len() {
        return None;
    }
    if b[i] == b'[' {
        i += 1;
        let mut items = Vec::new();
        loop {
            while i < b.len() && (b[i].is_ascii_whitespace() || b[i] == b',') {
                i += 1;
            }
            if i >= b.len() {
                return None;
            }
            if b[i] == b']' {
                return Some((Coords::List(items), i + 1));
            }
            let (v, used) = parse_coords(&s[i..])?;
            items.push(v);
            i += used;
        }
    }
    // A number: take the longest run of characters a JSON number can use.
    let start = i;
    while i < b.len() && matches!(b[i], b'0'..=b'9' | b'-' | b'+' | b'.' | b'e' | b'E') {
        i += 1;
    }
    if i == start {
        return None;
    }
    Some((Coords::Num(s[start..i].parse().ok()?), i))
}

/// Parse a flat JSON object body into properties. Strings and numbers only, which
/// is all our emitters write; a nested object or array value is skipped.
pub fn parse_props(body: &str) -> Vec<(String, Value)> {
    let mut out = Vec::new();
    let b = body.as_bytes();
    let mut i = 0usize;
    while i < b.len() {
        // Key.
        while i < b.len() && b[i] != b'"' {
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        i += 1;
        let ks = i;
        while i < b.len() && b[i] != b'"' {
            if b[i] == b'\\' {
                i += 1;
            }
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        let key = unescape(&body[ks..i]);
        i += 1;
        while i < b.len() && b[i] != b':' {
            i += 1;
        }
        i += 1;
        while i < b.len() && b[i].is_ascii_whitespace() {
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        match b[i] {
            b'"' => {
                i += 1;
                let vs = i;
                while i < b.len() && b[i] != b'"' {
                    if b[i] == b'\\' {
                        i += 1;
                    }
                    i += 1;
                }
                out.push((key, Value::String(unescape(&body[vs..i.min(body.len())]))));
                i += 1;
            }
            open @ (b'{' | b'[') => {
                // Skip the whole nested value. Reporting it is the caller's job;
                // guessing a scalar for it would be worse than omitting it.
                let close = if open == b'{' { b'}' } else { b']' };
                let mut depth = 0usize;
                let mut in_str = false;
                while i < b.len() {
                    match b[i] {
                        b'\\' if in_str => i += 1,
                        b'"' => in_str = !in_str,
                        c if c == open && !in_str => depth += 1,
                        c if c == close && !in_str => {
                            depth -= 1;
                            if depth == 0 {
                                i += 1;
                                break;
                            }
                        }
                        _ => {}
                    }
                    i += 1;
                }
            }
            _ => {
                let vs = i;
                while i < b.len() && b[i] != b',' && b[i] != b'}' {
                    i += 1;
                }
                let raw = body[vs..i].trim();
                // u64 before f64 so an integer property stays an integer on the
                // wire, which is what tippecanoe emitted and what the styles read.
                if let Ok(u) = raw.parse::<u64>() {
                    out.push((key, Value::Uint(u)));
                } else if let Ok(f) = raw.parse::<f64>() {
                    out.push((key, Value::Double(f)));
                } else if raw == "true" || raw == "false" {
                    out.push((key, Value::Bool(raw == "true")));
                }
            }
        }
        while i < b.len() && b[i] != b',' {
            i += 1;
        }
        i += 1;
    }
    out
}

pub fn unescape(s: &str) -> String {
    if !s.contains('\\') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match it.next() {
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('t') => out.push('\t'),
            Some('u') => {
                let hex: String = it.by_ref().take(4).collect();
                if let Some(ch) = u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32) {
                    out.push(ch);
                }
            }
            Some(other) => out.push(other),
            None => break,
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_what_osm_extract_emits_for_a_point() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[-122.4194000,37.7749000]},"properties":{"kind":"speed_camera","osm_id":"node/1001"}}"#;
        let f = parse_feature(line).unwrap();
        assert_eq!(f.geometry, Geometry::Points(vec![(-122.4194, 37.7749)]));
        assert_eq!(
            f.props,
            vec![
                ("kind".to_string(), Value::String("speed_camera".into())),
                ("osm_id".to_string(), Value::String("node/1001".into())),
            ]
        );
    }

    #[test]
    fn reads_a_linestring_and_a_multilinestring() {
        let line = r#"{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-122.42,37.77],[-122.41,37.78]]},"properties":{"maxspeed":"25 mph"}}"#;
        let f = parse_feature(line).unwrap();
        assert_eq!(
            f.geometry,
            Geometry::Lines(vec![vec![(-122.42, 37.77), (-122.41, 37.78)]])
        );

        let line = r#"{"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[[[0,0],[1,1]],[[2,2],[3,3]]]},"properties":{}}"#;
        let f = parse_feature(line).unwrap();
        assert_eq!(
            f.geometry,
            Geometry::Lines(vec![
                vec![(0.0, 0.0), (1.0, 1.0)],
                vec![(2.0, 2.0), (3.0, 3.0)]
            ])
        );
    }

    #[test]
    fn reads_a_polygon_with_a_hole_and_a_multipolygon() {
        let line = r#"{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[0,0],[4,0],[4,4],[0,4],[0,0]],[[1,1],[2,1],[2,2],[1,1]]]},"properties":{"name":"Oakland"}}"#;
        let f = parse_feature(line).unwrap();
        let Geometry::Polygons(polys) = f.geometry else { panic!() };
        assert_eq!(polys.len(), 1);
        assert_eq!(polys[0].len(), 2, "exterior plus its hole");
        assert_eq!(polys[0][0].len(), 5);

        let line = r#"{"type":"Feature","geometry":{"type":"MultiPolygon","coordinates":[[[[0,0],[1,0],[1,1],[0,0]]],[[[5,5],[6,5],[6,6],[5,5]]]]},"properties":{}}"#;
        let Geometry::Polygons(polys) = parse_feature(line).unwrap().geometry else { panic!() };
        assert_eq!(polys.len(), 2);
        assert_eq!(polys[0][0].len(), 4);
    }

    #[test]
    fn reads_a_multipoint() {
        let line = r#"{"type":"Feature","geometry":{"type":"MultiPoint","coordinates":[[0,0],[1,1]]},"properties":{}}"#;
        assert_eq!(
            parse_feature(line).unwrap().geometry,
            Geometry::Points(vec![(0.0, 0.0), (1.0, 1.0)])
        );
    }

    #[test]
    fn key_order_inside_the_geometry_does_not_matter() {
        let a = r#"{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]},"properties":{}}"#;
        let b = r#"{"type":"Feature","geometry":{"coordinates":[[0,0],[1,1]],"type":"LineString"},"properties":{}}"#;
        assert_eq!(parse_feature(a), parse_feature(b));
    }

    #[test]
    fn properties_before_geometry_still_reads() {
        let line = r#"{"type":"Feature","properties":{"name":"X"},"geometry":{"type":"Point","coordinates":[1,2]}}"#;
        let f = parse_feature(line).unwrap();
        assert_eq!(f.geometry, Geometry::Points(vec![(1.0, 2.0)]));
        assert_eq!(f.props, vec![("name".to_string(), Value::String("X".into()))]);
    }

    #[test]
    fn numeric_and_boolean_properties_keep_their_types() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"type":12,"score":1.5,"neg":-3,"ok":true}}"#;
        let f = parse_feature(line).unwrap();
        assert_eq!(
            f.props,
            vec![
                ("type".to_string(), Value::Uint(12)),
                ("score".to_string(), Value::Double(1.5)),
                ("neg".to_string(), Value::Double(-3.0)),
                ("ok".to_string(), Value::Bool(true)),
            ]
        );
    }

    #[test]
    fn escapes_and_awkward_names_survive() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"name":"A\"B\\C","other":"{not an object}"}}"#;
        let f = parse_feature(line).unwrap();
        assert_eq!(f.props[0].1, Value::String("A\"B\\C".into()));
        // A brace inside a string must not truncate the properties object.
        assert_eq!(f.props[1].1, Value::String("{not an object}".into()));
    }

    #[test]
    fn a_nested_property_value_is_skipped_not_guessed_at() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"a":1,"nested":{"x":1},"b":2}}"#;
        let f = parse_feature(line).unwrap();
        let keys: Vec<&str> = f.props.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(keys, vec!["a", "b"]);
    }

    #[test]
    fn extra_ordinates_are_ignored() {
        // GeoJSON allows a third (elevation) ordinate. A tile has nowhere to put it.
        let line = r#"{"type":"Feature","geometry":{"type":"LineString","coordinates":[[1,2,300],[3,4,500]]},"properties":{}}"#;
        assert_eq!(
            parse_feature(line).unwrap().geometry,
            Geometry::Lines(vec![vec![(1.0, 2.0), (3.0, 4.0)]])
        );
    }

    #[test]
    fn scientific_notation_and_signs_parse() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[-1.22e2,+3.7e1]},"properties":{}}"#;
        assert_eq!(
            parse_feature(line).unwrap().geometry,
            Geometry::Points(vec![(-122.0, 37.0)])
        );
    }

    #[test]
    fn a_feature_with_no_properties_member_reads_as_empty() {
        let line = r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]}}"#;
        assert_eq!(parse_feature(line).unwrap().props, vec![]);
    }

    #[test]
    fn unusable_lines_are_rejected_rather_than_guessed_at() {
        for bad in [
            "",
            "not json",
            r#"{"type":"Feature"}"#,
            // No coordinates.
            r#"{"type":"Feature","geometry":{"type":"Point"}}"#,
            // A geometry type this reader does not cover.
            r#"{"type":"Feature","geometry":{"type":"GeometryCollection","coordinates":[]}}"#,
            // Point with one ordinate.
            r#"{"type":"Feature","geometry":{"type":"Point","coordinates":[1]}}"#,
            // Wrong nesting for the declared type.
            r#"{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[0,0],[1,1]]}}"#,
            // Truncated array.
            r#"{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0,0],[1,"#,
        ] {
            assert!(parse_feature(bad).is_none(), "{bad:?} should not parse");
        }
    }

    #[test]
    fn a_top_level_type_key_is_not_mistaken_for_the_geometrys() {
        // "type" appears at the top level too, and inside properties. Only the one
        // inside the geometry object may decide the geometry.
        let line = r#"{"type":"Feature","properties":{"type":99},"geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]}}"#;
        let f = parse_feature(line).unwrap();
        assert!(matches!(f.geometry, Geometry::Lines(_)));
        assert_eq!(f.props, vec![("type".to_string(), Value::Uint(99))]);
    }
}
