//! Shared GeoJSON writer for the extracted vector layers.
//!
//! The layer extractors emit newline-delimited GeoJSON (geojsonseq), which is
//! what the tilers read. This module is the single place that turns geometry and
//! properties into bytes, so every layer produces the same shape.
//!
//! ## Matching the Python normalisers
//!
//! The pipeline this replaces ended in
//! `json.dumps(feature, separators=(",", ":"), ensure_ascii=False)`, so:
//!
//! * Key order is `type`, `geometry`, `properties`, and properties are written in
//!   the order the caller lists them. GeoJSON objects are unordered by spec, but
//!   fixing the order is what makes two runs byte-identical and a diff readable.
//! * No whitespace anywhere: `,` and `:` separate, nothing else.
//! * Non-ASCII passes through as UTF-8 rather than becoming `\uXXXX`, which is
//!   what `ensure_ascii=False` does. Only the JSON-mandatory escapes and C0
//!   controls are rewritten.
//!
//! One deliberate difference: coordinates are written with a fixed 7 decimal
//! places, matching `poi_build`, where Python wrote the shortest round-tripping
//! representation. `-122.4194` therefore comes out as `-122.4194000`. The values
//! are identical once parsed -- OSM coordinates are 1e-7 integers, so 7 places is
//! exact -- and the differential harness compares parsed numbers, not bytes.

use std::io::{self, Write};

/// A coordinate pair in GeoJSON order: longitude, then latitude.
pub type Coord = (f64, f64);

/// The geometry shapes the extracted layers produce.
///
/// `Polygon` and `MultiPolygon` carry rings as the spec orders them: the exterior
/// ring first, then its holes.
pub enum Geometry {
    Point(Coord),
    LineString(Vec<Coord>),
    MultiLineString(Vec<Vec<Coord>>),
    Polygon(Vec<Vec<Coord>>),
    MultiPolygon(Vec<Vec<Vec<Coord>>>),
}

impl Geometry {
    pub fn type_name(&self) -> &'static str {
        match self {
            Geometry::Point(_) => "Point",
            Geometry::LineString(_) => "LineString",
            Geometry::MultiLineString(_) => "MultiLineString",
            Geometry::Polygon(_) => "Polygon",
            Geometry::MultiPolygon(_) => "MultiPolygon",
        }
    }

    /// True when there is nothing to draw. An empty geometry is never emitted:
    /// tippecanoe and our own tilers both treat it as a feature that vanishes at
    /// render time, which is worse than not being in the file.
    pub fn is_empty(&self) -> bool {
        match self {
            Geometry::Point(_) => false,
            Geometry::LineString(l) => l.len() < 2,
            Geometry::MultiLineString(ls) => ls.iter().all(|l| l.len() < 2),
            Geometry::Polygon(rings) => rings.first().is_none_or(|r| r.len() < 4),
            Geometry::MultiPolygon(polys) => polys
                .iter()
                .all(|rings| rings.first().is_none_or(|r| r.len() < 4)),
        }
    }
}

/// A property value.
pub enum Value {
    /// A JSON string. Escaped on write; UTF-8 bytes pass through.
    Str(Vec<u8>),
    /// A pre-rendered JSON literal (a number, `true`/`false`/`null`), written
    /// verbatim. Rendering numbers at the call site keeps this module out of the
    /// business of deciding a layer's numeric formatting.
    Raw(String),
}

impl Value {
    pub fn str(s: impl AsRef<[u8]>) -> Value {
        Value::Str(s.as_ref().to_vec())
    }

    pub fn num(n: impl std::fmt::Display) -> Value {
        Value::Raw(n.to_string())
    }
}

pub struct Feature<'a> {
    pub geometry: Geometry,
    pub props: Vec<(&'a str, Value)>,
}

/// Escape a string for a JSON string literal. UTF-8 bytes pass through verbatim;
/// only the JSON-mandatory escapes and C0 controls are rewritten.
pub fn json_escape(s: &[u8], out: &mut Vec<u8>) {
    for &c in s {
        match c {
            b'"' => out.extend_from_slice(b"\\\""),
            b'\\' => out.extend_from_slice(b"\\\\"),
            b'\n' => out.extend_from_slice(b"\\n"),
            b'\r' => out.extend_from_slice(b"\\r"),
            b'\t' => out.extend_from_slice(b"\\t"),
            c if c < 0x20 => out.extend_from_slice(format!("\\u{c:04x}").as_bytes()),
            c => out.push(c),
        }
    }
}

/// Render one feature into `line`, which is cleared first, and terminate it with
/// a newline. Reusing one buffer across a whole layer avoids a per-feature
/// allocation.
pub fn render_feature(f: &Feature, line: &mut Vec<u8>) {
    line.clear();
    line.extend_from_slice(b"{\"type\":\"Feature\",\"geometry\":{\"type\":\"");
    line.extend_from_slice(f.geometry.type_name().as_bytes());
    line.extend_from_slice(b"\",\"coordinates\":");
    write_coords(&f.geometry, line);
    line.extend_from_slice(b"},\"properties\":{");
    for (i, (key, value)) in f.props.iter().enumerate() {
        if i > 0 {
            line.push(b',');
        }
        line.push(b'"');
        json_escape(key.as_bytes(), line);
        line.extend_from_slice(b"\":");
        match value {
            Value::Str(s) => {
                line.push(b'"');
                json_escape(s, line);
                line.push(b'"');
            }
            Value::Raw(r) => line.extend_from_slice(r.as_bytes()),
        }
    }
    line.extend_from_slice(b"}}\n");
}

pub fn write_feature<W: Write>(out: &mut W, f: &Feature, line: &mut Vec<u8>) -> io::Result<()> {
    render_feature(f, line);
    out.write_all(line)
}

fn write_coord((lon, lat): Coord, out: &mut Vec<u8>) {
    // `write!` into a Vec<u8> is infallible.
    let _ = write!(out, "[{lon:.7},{lat:.7}]");
}

fn write_ring(ring: &[Coord], out: &mut Vec<u8>) {
    out.push(b'[');
    for (i, c) in ring.iter().enumerate() {
        if i > 0 {
            out.push(b',');
        }
        write_coord(*c, out);
    }
    out.push(b']');
}

fn write_rings(rings: &[Vec<Coord>], out: &mut Vec<u8>) {
    out.push(b'[');
    for (i, r) in rings.iter().enumerate() {
        if i > 0 {
            out.push(b',');
        }
        write_ring(r, out);
    }
    out.push(b']');
}

fn write_coords(g: &Geometry, out: &mut Vec<u8>) {
    match g {
        Geometry::Point(c) => write_coord(*c, out),
        Geometry::LineString(l) => write_ring(l, out),
        Geometry::MultiLineString(ls) => write_rings(ls, out),
        Geometry::Polygon(rings) => write_rings(rings, out),
        Geometry::MultiPolygon(polys) => {
            out.push(b'[');
            for (i, rings) in polys.iter().enumerate() {
                if i > 0 {
                    out.push(b',');
                }
                write_rings(rings, out);
            }
            out.push(b']');
        }
    }
}

/// An OSM element reference in the `node/123` form the normalisers emitted, and
/// which the app and the differential harness pair features on.
pub fn osm_ref(kind: &str, id: i64) -> Value {
    Value::Str(format!("{kind}/{id}").into_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn render(f: &Feature) -> String {
        let mut line = Vec::new();
        render_feature(f, &mut line);
        String::from_utf8(line).unwrap()
    }

    #[test]
    fn a_point_feature_matches_the_python_shape() {
        // Exactly what json.dumps(..., separators=(",", ":"), ensure_ascii=False)
        // produced: no whitespace, key order type/geometry/properties.
        let f = Feature {
            geometry: Geometry::Point((-122.4194, 37.7749)),
            props: vec![
                ("kind", Value::str("speed_camera")),
                ("direction", Value::str("forward")),
                ("osm_id", osm_ref("node", 1001)),
            ],
        };
        assert_eq!(
            render(&f),
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\
             \"coordinates\":[-122.4194000,37.7749000]},\"properties\":\
             {\"kind\":\"speed_camera\",\"direction\":\"forward\",\
             \"osm_id\":\"node/1001\"}}\n"
        );
    }

    #[test]
    fn numbers_are_written_verbatim() {
        let f = Feature {
            geometry: Geometry::Point((0.0, 0.0)),
            props: vec![("type", Value::num(12u16)), ("osm_id", Value::num(-200i64))],
        };
        assert!(render(&f).contains("\"type\":12,\"osm_id\":-200"));
    }

    #[test]
    fn lines_and_polygons_nest_correctly() {
        let line = Feature {
            geometry: Geometry::LineString(vec![(-122.42, 37.77), (-122.41, 37.78)]),
            props: vec![],
        };
        assert!(render(&line).contains(
            "\"coordinates\":[[-122.4200000,37.7700000],[-122.4100000,37.7800000]]"
        ));

        let multi = Feature {
            geometry: Geometry::MultiLineString(vec![vec![(0.0, 0.0), (1.0, 1.0)]]),
            props: vec![],
        };
        assert!(render(&multi)
            .contains("\"coordinates\":[[[0.0000000,0.0000000],[1.0000000,1.0000000]]]"));

        // A polygon's exterior ring comes first, then its holes.
        let poly = Feature {
            geometry: Geometry::Polygon(vec![
                vec![(0.0, 0.0), (2.0, 0.0), (2.0, 2.0), (0.0, 2.0), (0.0, 0.0)],
                vec![(0.5, 0.5), (1.5, 0.5), (1.5, 1.5), (0.5, 1.5), (0.5, 0.5)],
            ]),
            props: vec![],
        };
        let s = render(&poly);
        assert!(s.contains("\"type\":\"Polygon\""));
        assert!(
            s.find("0.5000000").unwrap() > s.find("2.0000000").unwrap(),
            "the hole must be written after the exterior ring: {s}"
        );
    }

    #[test]
    fn empty_properties_still_produce_a_valid_object() {
        let f = Feature {
            geometry: Geometry::Point((1.0, 2.0)),
            props: vec![],
        };
        assert!(render(&f).ends_with("\"properties\":{}}\n"));
    }

    #[test]
    fn json_escapes_only_what_it_must() {
        let mut out = Vec::new();
        json_escape("Ben \"B\" \\ Jerry\n\tCafé".as_bytes(), &mut out);
        assert_eq!(
            String::from_utf8(out).unwrap(),
            "Ben \\\"B\\\" \\\\ Jerry\\n\\tCafé"
        );
        let mut out = Vec::new();
        json_escape(&[0x01, 0x1f], &mut out);
        assert_eq!(String::from_utf8(out).unwrap(), "\\u0001\\u001f");
    }

    #[test]
    fn a_name_with_a_quote_survives_a_round_trip_through_the_writer() {
        let f = Feature {
            geometry: Geometry::Point((0.0, 0.0)),
            props: vec![("name", Value::str("Joe's \"Diner\""))],
        };
        assert!(render(&f).contains("\"name\":\"Joe's \\\"Diner\\\"\""));
    }

    #[test]
    fn emptiness_is_judged_per_geometry_kind() {
        assert!(!Geometry::Point((0.0, 0.0)).is_empty());
        assert!(Geometry::LineString(vec![(0.0, 0.0)]).is_empty());
        assert!(!Geometry::LineString(vec![(0.0, 0.0), (1.0, 1.0)]).is_empty());
        assert!(Geometry::MultiLineString(vec![vec![(0.0, 0.0)]]).is_empty());
        // A ring needs 4 points: three distinct corners plus the repeated close.
        assert!(Geometry::Polygon(vec![vec![(0.0, 0.0), (1.0, 0.0), (0.0, 0.0)]]).is_empty());
        assert!(!Geometry::Polygon(vec![vec![
            (0.0, 0.0),
            (1.0, 0.0),
            (1.0, 1.0),
            (0.0, 0.0)
        ]])
        .is_empty());
        assert!(Geometry::MultiPolygon(vec![]).is_empty());
    }
}
