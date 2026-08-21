//! Mapbox Vector Tile 2.1 codec — decode, edit, re-encode.
//!
//! Enough of the spec to composite tilesets. Geometry is kept as the **raw
//! command-integer stream** rather than decoded into rings, because the tile-join
//! step only ever has to move features between tiles, never reshape them: our own
//! writer emits points, while the base tileset's lines and polygons must pass
//! through untouched. Keeping them opaque means no clipper, no winding-order
//! rules, and no way to corrupt geometry we do not understand.
//!
//! Properties *are* decoded, since re-encoding rebuilds each layer's key/value
//! dictionaries. A re-encode is therefore semantically identical but not
//! byte-identical: dictionary order depends on first use, so an unchanged tile can
//! come back a few bytes different. Tests assert on the decoded model, which is
//! what the spec actually defines.
//!
//! Wire layout (`vector_tile.proto`):
//!   Tile    { repeated Layer layers = 3 }
//!   Layer   { required string name = 1, repeated Feature features = 2,
//!             repeated string keys = 3, repeated Value values = 4,
//!             optional uint32 extent = 5 [default 4096],
//!             required uint32 version = 15 [default 1] }
//!   Feature { optional uint64 id = 1, repeated uint32 tags = 2 [packed],
//!             optional GeomType type = 3, repeated uint32 geometry = 4 [packed] }
//!   Value   { string 1 | float 2 | double 3 | int64 4 | uint64 5 | sint64 6 |
//!             bool 7 }

use crate::proto::{self, err, Reader, Result, Writer, WIRE_BYTES, WIRE_VARINT};
use std::collections::HashMap;

/// The spec's default tile extent, and what tippecanoe emits.
pub const DEFAULT_EXTENT: u32 = 4096;
/// Vector tile spec major version we write. 2 is what every current producer uses.
pub const DEFAULT_VERSION: u32 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GeomType {
    Unknown,
    Point,
    LineString,
    Polygon,
}

impl GeomType {
    fn from_wire(v: u64) -> GeomType {
        match v {
            1 => GeomType::Point,
            2 => GeomType::LineString,
            3 => GeomType::Polygon,
            _ => GeomType::Unknown,
        }
    }
    fn to_wire(self) -> u64 {
        match self {
            GeomType::Unknown => 0,
            GeomType::Point => 1,
            GeomType::LineString => 2,
            GeomType::Polygon => 3,
        }
    }
}

/// A feature property value. The variants mirror the `Value` message's oneof-ish
/// set of optional fields.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    String(String),
    Float(f32),
    Double(f64),
    Int(i64),
    Uint(u64),
    SInt(i64),
    Bool(bool),
}

impl Value {
    /// Key for interning, so two equal values share a dictionary slot. Floats are
    /// keyed on their bit pattern: NaN never dedups, which is correct and avoids
    /// needing `Eq` on a float.
    fn dedup_key(&self) -> (u8, u64, String) {
        match self {
            Value::String(s) => (0, 0, s.clone()),
            Value::Float(f) => (1, f.to_bits() as u64, String::new()),
            Value::Double(d) => (2, d.to_bits(), String::new()),
            Value::Int(i) => (3, *i as u64, String::new()),
            Value::Uint(u) => (4, *u, String::new()),
            Value::SInt(i) => (5, *i as u64, String::new()),
            Value::Bool(b) => (6, *b as u64, String::new()),
        }
    }

    fn decode(payload: &[u8]) -> Result<Value> {
        let mut r = Reader::new(payload);
        let mut out: Option<Value> = None;
        while let Some((field, wire)) = r.next_field()? {
            match (field, wire) {
                (1, WIRE_BYTES) => out = Some(Value::String(r.string()?)),
                (2, proto::WIRE_I32) => out = Some(Value::Float(f32::from_bits(r.fixed32()?))),
                (3, proto::WIRE_I64) => out = Some(Value::Double(f64::from_bits(r.fixed64()?))),
                (4, WIRE_VARINT) => out = Some(Value::Int(r.ivarint()?)),
                (5, WIRE_VARINT) => out = Some(Value::Uint(r.uvarint()?)),
                (6, WIRE_VARINT) => out = Some(Value::SInt(r.svarint()?)),
                (7, WIRE_VARINT) => out = Some(Value::Bool(r.uvarint()? != 0)),
                (_, w) => r.skip(w)?,
            }
        }
        // An empty Value is legal protobuf but meaningless as a property; treat it
        // as the empty string rather than dropping the tag pair and desyncing the
        // feature's key/value pairing.
        Ok(out.unwrap_or_else(|| Value::String(String::new())))
    }

    fn encode(&self, out: &mut Writer) {
        match self {
            Value::String(s) => out.string_field(1, s),
            Value::Float(f) => out.fixed32_field(2, f.to_bits()),
            Value::Double(d) => out.fixed64_field(3, d.to_bits()),
            Value::Int(i) => out.ivarint_field(4, *i),
            Value::Uint(u) => out.varint_field(5, *u),
            Value::SInt(i) => out.svarint_field(6, *i),
            Value::Bool(b) => out.varint_field(7, *b as u64),
        };
    }
}

#[derive(Debug, Clone)]
pub struct Feature {
    pub id: Option<u64>,
    pub geom_type: GeomType,
    /// Raw MVT command integers, re-emitted verbatim. See the module docs.
    pub geometry: Vec<u32>,
    pub props: Vec<(String, Value)>,
}

impl Feature {
    pub fn get(&self, key: &str) -> Option<&Value> {
        self.props.iter().find(|(k, _)| k == key).map(|(_, v)| v)
    }
}

#[derive(Debug, Clone)]
pub struct Layer {
    pub name: String,
    pub version: u32,
    pub extent: u32,
    pub features: Vec<Feature>,
}

impl Layer {
    pub fn new(name: impl Into<String>) -> Layer {
        Layer {
            name: name.into(),
            version: DEFAULT_VERSION,
            extent: DEFAULT_EXTENT,
            features: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct Tile {
    pub layers: Vec<Layer>,
}

impl Tile {
    pub fn new() -> Tile {
        Tile { layers: Vec::new() }
    }

    pub fn layer(&self, name: &str) -> Option<&Layer> {
        self.layers.iter().find(|l| l.name == name)
    }

    pub fn layer_names(&self) -> Vec<&str> {
        self.layers.iter().map(|l| l.name.as_str()).collect()
    }

    /// Decode a tile body (already un-gzipped).
    pub fn decode(buf: &[u8]) -> Result<Tile> {
        let mut tile = Tile::new();
        let mut r = Reader::new(buf);
        while let Some((field, wire)) = r.next_field()? {
            match (field, wire) {
                (3, WIRE_BYTES) => tile.layers.push(decode_layer(r.bytes()?)?),
                (_, w) => r.skip(w)?,
            }
        }
        Ok(tile)
    }

    /// Encode to a tile body (caller gzips).
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Writer::new();
        let mut layer_buf = Writer::new();
        for layer in &self.layers {
            layer_buf.clear();
            encode_layer(layer, &mut layer_buf);
            out.message(3, &layer_buf);
        }
        out.into_vec()
    }
}

fn decode_layer(buf: &[u8]) -> Result<Layer> {
    let mut name = String::new();
    let mut version = 1u32;
    let mut extent = DEFAULT_EXTENT;
    let mut keys: Vec<String> = Vec::new();
    let mut values: Vec<Value> = Vec::new();
    // Features are collected raw first: the spec does not require the key/value
    // dictionaries to precede them, and tippecanoe in fact writes them last.
    let mut raw_features: Vec<&[u8]> = Vec::new();

    let mut r = Reader::new(buf);
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_BYTES) => name = r.string()?,
            (2, WIRE_BYTES) => raw_features.push(r.bytes()?),
            (3, WIRE_BYTES) => keys.push(r.string()?),
            (4, WIRE_BYTES) => values.push(Value::decode(r.bytes()?)?),
            (5, WIRE_VARINT) => extent = r.uvarint()? as u32,
            (15, WIRE_VARINT) => version = r.uvarint()? as u32,
            (_, w) => r.skip(w)?,
        }
    }
    if extent == 0 {
        return err("MVT layer extent 0");
    }

    let mut features = Vec::with_capacity(raw_features.len());
    for body in raw_features {
        features.push(decode_feature(body, &keys, &values)?);
    }
    Ok(Layer { name, version, extent, features })
}

fn decode_feature(buf: &[u8], keys: &[String], values: &[Value]) -> Result<Feature> {
    let mut id = None;
    let mut geom_type = GeomType::Unknown;
    let mut tags: Vec<u32> = Vec::new();
    let mut geometry: Vec<u32> = Vec::new();

    let mut r = Reader::new(buf);
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_VARINT) => id = Some(r.uvarint()?),
            (2, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut tags)?,
            (3, WIRE_VARINT) => geom_type = GeomType::from_wire(r.uvarint()?),
            (4, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut geometry)?,
            (_, w) => r.skip(w)?,
        }
    }
    if tags.len() % 2 != 0 {
        return err("MVT feature has an odd number of tag entries");
    }
    let mut props = Vec::with_capacity(tags.len() / 2);
    for pair in tags.chunks_exact(2) {
        let (ki, vi) = (pair[0] as usize, pair[1] as usize);
        // Out-of-range indices mean a corrupt tile; dropping the pair keeps the
        // rest of the feature usable, which matters when compositing a big archive.
        if let (Some(k), Some(v)) = (keys.get(ki), values.get(vi)) {
            props.push((k.clone(), v.clone()));
        }
    }
    Ok(Feature { id, geom_type, geometry, props })
}

fn encode_layer(layer: &Layer, out: &mut Writer) {
    out.string_field(1, &layer.name);

    // Dictionaries are rebuilt from the decoded properties, interning on first use.
    let mut keys: Vec<&str> = Vec::new();
    let mut key_idx: HashMap<&str, u32> = HashMap::new();
    let mut values: Vec<&Value> = Vec::new();
    let mut value_idx: HashMap<(u8, u64, String), u32> = HashMap::new();

    let mut feat_buf = Writer::new();
    let mut tag_buf = Writer::new();
    let mut geom_buf = Writer::new();
    for f in &layer.features {
        feat_buf.clear();
        if let Some(id) = f.id {
            feat_buf.varint_field(1, id);
        }

        tag_buf.clear();
        for (k, v) in &f.props {
            let ki = *key_idx.entry(k.as_str()).or_insert_with(|| {
                keys.push(k.as_str());
                (keys.len() - 1) as u32
            });
            let vi = *value_idx.entry(v.dedup_key()).or_insert_with(|| {
                values.push(v);
                (values.len() - 1) as u32
            });
            tag_buf.uvarint(ki as u64).uvarint(vi as u64);
        }
        if !tag_buf.is_empty() {
            feat_buf.bytes_field(2, tag_buf.as_slice());
        }

        // Field order follows the .proto's own numbering; readers must not care,
        // but matching it keeps a diff against tippecanoe output legible.
        if f.geom_type != GeomType::Unknown {
            feat_buf.varint_field(3, f.geom_type.to_wire());
        }
        if !f.geometry.is_empty() {
            geom_buf.clear();
            for &g in &f.geometry {
                geom_buf.uvarint(g as u64);
            }
            feat_buf.bytes_field(4, geom_buf.as_slice());
        }
        out.bytes_field(2, feat_buf.as_slice());
    }

    for k in &keys {
        out.string_field(3, k);
    }
    let mut val_buf = Writer::new();
    for v in &values {
        val_buf.clear();
        v.encode(&mut val_buf);
        out.message(4, &val_buf);
    }

    out.varint_field(5, layer.extent as u64);
    out.varint_field(15, layer.version as u64);
}

// --- Geometry command integers ------------------------------------------------

pub const CMD_MOVE_TO: u32 = 1;
pub const CMD_LINE_TO: u32 = 2;
pub const CMD_CLOSE_PATH: u32 = 7;

/// Pack a command id and its repeat count into a command integer.
#[inline]
pub fn command(cmd: u32, count: u32) -> u32 {
    (cmd & 0x7) | (count << 3)
}

/// Split a command integer into `(command, count)`.
#[inline]
pub fn command_parts(v: u32) -> (u32, u32) {
    (v & 0x7, v >> 3)
}

/// Build the geometry stream for a multipoint layer: one `MoveTo` covering every
/// point, with zigzagged deltas between successive points, as the spec requires.
pub fn encode_points(points: &[(i32, i32)]) -> Vec<u32> {
    if points.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(1 + points.len() * 2);
    out.push(command(CMD_MOVE_TO, points.len() as u32));
    let (mut cx, mut cy) = (0i32, 0i32);
    for &(x, y) in points {
        out.push(proto::zigzag_encode((x - cx) as i64) as u32);
        out.push(proto::zigzag_encode((y - cy) as i64) as u32);
        cx = x;
        cy = y;
    }
    out
}

/// Decode a point layer's geometry stream back to absolute tile coordinates.
/// Non-point geometry yields `None` — callers that need lines or polygons should
/// pass the raw stream through instead.
pub fn decode_points(geometry: &[u32]) -> Option<Vec<(i32, i32)>> {
    let mut out = Vec::new();
    let (mut cx, mut cy) = (0i32, 0i32);
    let mut i = 0usize;
    while i < geometry.len() {
        let (cmd, count) = command_parts(geometry[i]);
        i += 1;
        if cmd != CMD_MOVE_TO {
            return None;
        }
        for _ in 0..count {
            if i + 1 >= geometry.len() {
                return None;
            }
            cx += proto::zigzag_decode(geometry[i] as u64) as i32;
            cy += proto::zigzag_decode(geometry[i + 1] as u64) as i32;
            i += 2;
            out.push((cx, cy));
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The real tile lifted out of the published v5-ca.pmtiles. See
    /// `tests/fixtures/README.md`.
    const REAL_TILE: &[u8] = include_bytes!("../tests/fixtures/v5ca_z11_tile.mvt");

    fn point_feature(x: i32, y: i32, props: Vec<(&str, Value)>) -> Feature {
        Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: encode_points(&[(x, y)]),
            props: props.into_iter().map(|(k, v)| (k.to_string(), v)).collect(),
        }
    }

    #[test]
    fn command_integers_pack_and_unpack() {
        // The spec's worked example: MoveTo with count 1 is 9.
        assert_eq!(command(CMD_MOVE_TO, 1), 9);
        assert_eq!(command_parts(9), (CMD_MOVE_TO, 1));
        // LineTo with count 3 is 26.
        assert_eq!(command(CMD_LINE_TO, 3), 26);
        assert_eq!(command_parts(26), (CMD_LINE_TO, 3));
        assert_eq!(command_parts(command(CMD_CLOSE_PATH, 1)), (CMD_CLOSE_PATH, 1));
    }

    #[test]
    fn points_round_trip_through_the_geometry_stream() {
        let pts = vec![(0, 0), (25, 17), (4096, 4096), (10, 4000), (-5, -5)];
        let geom = encode_points(&pts);
        // One command integer plus two zigzag deltas per point.
        assert_eq!(geom.len(), 1 + pts.len() * 2);
        assert_eq!(command_parts(geom[0]), (CMD_MOVE_TO, pts.len() as u32));
        assert_eq!(decode_points(&geom).unwrap(), pts);
    }

    #[test]
    fn a_line_geometry_stream_is_not_mistaken_for_points() {
        let geom = vec![command(CMD_MOVE_TO, 1), 0, 0, command(CMD_LINE_TO, 1), 2, 2];
        assert!(decode_points(&geom).is_none(), "a LineTo must not decode as points");
    }

    #[test]
    fn a_tile_round_trips_through_encode_and_decode() {
        let mut layer = Layer::new("transit_stops");
        layer.features.push(point_feature(
            100,
            200,
            vec![
                ("name", Value::String("Embarcadero".into())),
                ("motis_id", Value::String("us-ca-SF-bayarea_901201".into())),
                ("route_type", Value::Uint(1)),
            ],
        ));
        layer.features.push(point_feature(
            300,
            400,
            vec![
                ("name", Value::String("Powell".into())),
                ("route_type", Value::Uint(0)),
            ],
        ));
        let tile = Tile { layers: vec![layer] };

        let back = Tile::decode(&tile.encode()).unwrap();
        assert_eq!(back.layer_names(), vec!["transit_stops"]);
        let l = back.layer("transit_stops").unwrap();
        assert_eq!(l.extent, DEFAULT_EXTENT);
        assert_eq!(l.version, DEFAULT_VERSION);
        assert_eq!(l.features.len(), 2);
        assert_eq!(
            l.features[0].get("motis_id"),
            Some(&Value::String("us-ca-SF-bayarea_901201".into()))
        );
        assert_eq!(l.features[0].get("route_type"), Some(&Value::Uint(1)));
        assert_eq!(decode_points(&l.features[0].geometry).unwrap(), vec![(100, 200)]);
        // The second feature never had a motis_id, and must not inherit one from
        // the shared dictionary.
        assert_eq!(l.features[1].get("motis_id"), None);
        assert_eq!(decode_points(&l.features[1].geometry).unwrap(), vec![(300, 400)]);
    }

    #[test]
    fn every_value_kind_round_trips() {
        let vals = vec![
            Value::String("s".into()),
            Value::Float(1.5),
            Value::Double(-2.25),
            Value::Int(-42),
            Value::Uint(42),
            Value::SInt(-7),
            Value::Bool(true),
            Value::Bool(false),
        ];
        let mut layer = Layer::new("vals");
        for (i, v) in vals.iter().enumerate() {
            layer.features.push(point_feature(i as i32, 0, vec![("v", v.clone())]));
        }
        let back = Tile::decode(&Tile { layers: vec![layer] }.encode()).unwrap();
        let l = back.layer("vals").unwrap();
        for (i, v) in vals.iter().enumerate() {
            assert_eq!(l.features[i].get("v"), Some(v), "value {i}");
        }
    }

    #[test]
    fn repeated_keys_and_values_are_interned_once() {
        let mut layer = Layer::new("dedup");
        for i in 0..5 {
            layer.features.push(point_feature(
                i,
                0,
                // Same key every time, and only two distinct values.
                vec![("route_type", Value::Uint((i % 2) as u64))],
            ));
        }
        let encoded = Tile { layers: vec![layer] }.encode();
        // Count the layer's key (field 3) and value (field 4) entries directly.
        let mut r = Reader::new(&encoded);
        let (_, _) = r.next_field().unwrap().unwrap();
        let body = r.bytes().unwrap();
        let (mut nkeys, mut nvals) = (0, 0);
        let mut lr = Reader::new(body);
        while let Some((f, w)) = lr.next_field().unwrap() {
            match f {
                3 => {
                    nkeys += 1;
                    lr.skip(w).unwrap();
                }
                4 => {
                    nvals += 1;
                    lr.skip(w).unwrap();
                }
                _ => lr.skip(w).unwrap(),
            }
        }
        assert_eq!(nkeys, 1, "one distinct key");
        assert_eq!(nvals, 2, "two distinct values across five features");
    }

    // --- Against genuine tippecanoe output ---------------------------------

    #[test]
    fn the_real_tile_decodes_to_what_tippecanoe_wrote() {
        let tile = Tile::decode(REAL_TILE).expect("the published tile decodes");
        let mut names = tile.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["earth", "roads", "water"]);
        for l in &tile.layers {
            assert_eq!(l.extent, 4096, "{} extent", l.name);
            assert_eq!(l.version, 2, "{} version", l.name);
        }
        assert_eq!(tile.layer("earth").unwrap().features.len(), 1);
        assert_eq!(tile.layer("roads").unwrap().features.len(), 1);
        assert_eq!(tile.layer("water").unwrap().features.len(), 2);
        // Lines and polygons, which is the pass-through case tile-join needs.
        assert_eq!(tile.layer("roads").unwrap().features[0].geom_type, GeomType::LineString);
        assert_eq!(tile.layer("earth").unwrap().features[0].geom_type, GeomType::Polygon);
    }

    #[test]
    fn the_real_tile_survives_a_re_encode() {
        // The round-trip proof the composite step rests on: re-encoding a
        // tippecanoe tile must preserve its layers, geometry and properties.
        // Bytes are NOT expected to match -- dictionary order depends on first use.
        let before = Tile::decode(REAL_TILE).unwrap();
        let after = Tile::decode(&before.encode()).unwrap();

        assert_eq!(before.layer_names(), after.layer_names());
        for (b, a) in before.layers.iter().zip(after.layers.iter()) {
            assert_eq!(b.name, a.name);
            assert_eq!(b.extent, a.extent);
            assert_eq!(b.version, a.version);
            assert_eq!(b.features.len(), a.features.len(), "{} feature count", b.name);
            for (bf, af) in b.features.iter().zip(a.features.iter()) {
                assert_eq!(bf.id, af.id);
                assert_eq!(bf.geom_type, af.geom_type);
                // Geometry is re-emitted verbatim, so this one IS byte-exact.
                assert_eq!(bf.geometry, af.geometry, "{} geometry", b.name);
                let mut bp = bf.props.clone();
                let mut ap = af.props.clone();
                bp.sort_by(|x, y| x.0.cmp(&y.0));
                ap.sort_by(|x, y| x.0.cmp(&y.0));
                assert_eq!(bp, ap, "{} properties", b.name);
            }
        }
    }

    #[test]
    fn a_re_encode_of_the_real_tile_is_stable() {
        // Second pass must be byte-identical to the first: dictionary order is a
        // function of the decoded model, so once through our writer it is fixed.
        let once = Tile::decode(REAL_TILE).unwrap().encode();
        let twice = Tile::decode(&once).unwrap().encode();
        assert_eq!(once, twice, "re-encoding must be idempotent");
    }

    #[test]
    fn a_truncated_real_tile_errors_rather_than_panicking() {
        // Range requests and partial writes both produce these.
        assert!(Tile::decode(&REAL_TILE[..REAL_TILE.len() / 2]).is_err());
    }
}
