//! Mapbox Vector Tile 2.1 codec — decode, edit, re-encode.
//!
//! ## The geometry contract, and how it changed
//!
//! [`Feature::geometry`] is the **raw command-integer stream**, never a decoded
//! ring. That is deliberate and it has not changed: [`crate::tiling::merge_tiles`]
//! only ever moves features between tiles, so carrying the stream through verbatim
//! means a re-encode cannot corrupt geometry we did not produce. That opaque path
//! is what makes the composite lossless, and it is load-bearing for the base
//! tileset's lines and polygons.
//!
//! What changed is that this module now also **builds and reads** those streams,
//! for the layers we tile ourselves: [`encode_points`], [`encode_lines`] and
//! [`encode_polygons`] on the way in, and [`decode_points`], [`decode_lines`] and
//! [`decode_polygons`] on the way out. The encoders sit *beside* the passthrough
//! rather than replacing it — a feature either came from a stream we are copying,
//! or from vertices we are encoding, and the two never meet.
//!
//! ## Polygon winding order
//!
//! The spec states it as signed area, not as a direction: applying the surveyor's
//! formula to a ring, an **exterior ring must come out positive and an interior
//! ring negative**. (Equivalently: clockwise and counter-clockwise on screen,
//! since tile `y` grows downward — which is why quoting the direction instead of
//! the sign is such a reliable way to get it backwards.)
//!
//! [`encode_polygons`] therefore computes [`signed_area`] and reverses the ring
//! when the sign is wrong, rather than trusting the caller. Input rings arrive from
//! a clipper and a simplifier, neither of which preserves orientation, so trusting
//! them would produce holes that render as fill and fills that render as holes.
//!
//! ## Property dictionaries
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
    ///
    /// The string is BORROWED. Cloning it here allocated once per string property per
    /// feature per encode, and the drop policy's binary search encodes the same tile
    /// about eleven times -- so on California z11 this was tens of millions of
    /// throwaway `String`s and the single largest cost in the tiler.
    fn dedup_key(&self) -> (u8, u64, &str) {
        match self {
            Value::String(s) => (0, 0, s.as_str()),
            Value::Float(f) => (1, f.to_bits() as u64, ""),
            Value::Double(d) => (2, d.to_bits(), ""),
            Value::Int(i) => (3, *i as u64, ""),
            Value::Uint(u) => (4, *u, ""),
            Value::SInt(i) => (5, *i as u64, ""),
            Value::Bool(b) => (6, *b as u64, ""),
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

/// One feature as the encoder actually needs it: nothing owned.
///
/// The producers already own their geometry and properties somewhere — the pyramid in a
/// candidate list, the point tiler in its input slice — and copying both into a
/// [`Feature`] just to encode it made the per-candidate `props` clone the largest
/// allocation in the tiler: measured at ~35% of single-threaded tile encode time, since
/// every property key and string value is a separate heap copy per tile a feature
/// touches.
///
/// [`encode_layer_from`] is the single implementation; [`Tile::encode`] feeds it views of
/// its owned features, so the two paths cannot drift apart and produce different bytes.
pub struct FeatureRef<'a> {
    pub id: Option<u64>,
    pub geom_type: GeomType,
    /// Raw MVT command integers, re-emitted verbatim. See the module docs.
    pub geometry: &'a [u32],
    pub props: &'a [(String, Value)],
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
    encode_layer_from(
        &layer.name,
        layer.extent,
        layer.version,
        layer.features.iter().map(|f| FeatureRef {
            id: f.id,
            geom_type: f.geom_type,
            geometry: &f.geometry,
            props: &f.props,
        }),
        out,
    );
}

/// Encode a single layer body from borrowed features.
///
/// The one implementation of the layer encoder: [`encode_layer`] is a thin view over
/// owned [`Feature`]s, and the tilers pass their own data straight in. See
/// [`FeatureRef`].
pub fn encode_layer_from<'a>(
    name: &str,
    extent: u32,
    version: u32,
    features: impl IntoIterator<Item = FeatureRef<'a>>,
    out: &mut Writer,
) {
    out.string_field(1, name);

    // Dictionaries are rebuilt from the decoded properties, interning on first use.
    let mut keys: Vec<&str> = Vec::new();
    let mut key_idx: HashMap<&str, u32> = HashMap::new();
    let mut values: Vec<&Value> = Vec::new();
    let mut value_idx: HashMap<(u8, u64, &str), u32> = HashMap::new();

    let mut feat_buf = Writer::new();
    let mut tag_buf = Writer::new();
    let mut geom_buf = Writer::new();
    for f in features {
        feat_buf.clear();
        if let Some(id) = f.id {
            feat_buf.varint_field(1, id);
        }

        tag_buf.clear();
        for (k, v) in f.props {
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
            for &g in f.geometry {
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

    out.varint_field(5, extent as u64);
    out.varint_field(15, version as u64);
}

/// Encode a whole one-layer tile body from borrowed features.
///
/// Byte-for-byte what `Tile { layers: vec![one] }.encode()` produces — the layer
/// wrapper is field 3 either way — which is what the tilers rely on.
pub fn encode_tile_from<'a>(
    name: &str,
    extent: u32,
    features: impl IntoIterator<Item = FeatureRef<'a>>,
) -> Vec<u8> {
    let mut layer_buf = Writer::new();
    encode_layer_from(name, extent, DEFAULT_VERSION, features, &mut layer_buf);
    let mut out = Writer::new();
    out.message(3, &layer_buf);
    out.into_vec()
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

/// Twice the signed area of a ring, by the surveyor's (shoelace) formula.
///
/// Doubled and kept as an integer so there is no division and no rounding: only
/// the **sign** decides winding order, and only zero decides degeneracy, so the
/// factor of two is irrelevant and dropping it would introduce a rounding step
/// into a decision that must be exact.
///
/// Per the MVT spec, a positive result means an exterior ring and a negative one
/// an interior ring. An explicit closing vertex is optional: the sum wraps from
/// the last vertex to the first either way, and a repeated vertex contributes
/// nothing.
///
/// `i64` throughout: two `i32` spans multiply to 62 bits, and a ring long enough
/// to overflow the accumulator would need more vertices than a tile can hold.
pub fn signed_area(ring: &[(i32, i32)]) -> i64 {
    let n = ring.len();
    if n < 3 {
        return 0;
    }
    let mut sum = 0i64;
    for i in 0..n {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % n];
        sum += x1 as i64 * y2 as i64 - x2 as i64 * y1 as i64;
    }
    sum
}

/// Build the geometry stream for a line layer.
///
/// Each part is `MoveTo(1)` then `LineTo(n-1)`, with zigzagged deltas and the
/// cursor carried across parts, as the spec requires. Parts with fewer than two
/// distinct vertices are skipped: a `LineTo(0)` is illegal, and a lone `MoveTo`
/// would encode a point inside a line layer.
pub fn encode_lines(lines: &[Vec<(i32, i32)>]) -> Vec<u32> {
    let mut out = Vec::new();
    let (mut cx, mut cy) = (0i32, 0i32);
    for line in lines {
        if line.len() < 2 {
            continue;
        }
        out.push(command(CMD_MOVE_TO, 1));
        push_delta(&mut out, line[0], &mut cx, &mut cy);
        out.push(command(CMD_LINE_TO, (line.len() - 1) as u32));
        for p in &line[1..] {
            push_delta(&mut out, *p, &mut cx, &mut cy);
        }
    }
    out
}

/// One polygon: its exterior ring first, then its holes.
pub type PolygonRings = Vec<Vec<(i32, i32)>>;

/// Build the geometry stream for a polygon layer.
///
/// Per polygon, the exterior ring comes first and its holes follow, each as
/// `MoveTo(1)`, `LineTo(n-1)`, `ClosePath`. Three things this function does that
/// the caller must not have to think about:
///
/// * **Closure is implicit.** `ClosePath` re-draws the edge back to the ring's
///   start, so an explicit closing vertex is stripped. Leaving it in emits a
///   zero-length segment, and some renderers treat that as a degenerate ring.
/// * **Orientation is derived, not trusted.** [`signed_area`] decides, and the ring
///   is reversed when the sign is wrong. The clipper and the simplifier upstream do
///   not preserve orientation, so the input's own winding means nothing.
/// * **Zero-area rings are dropped.** They cannot be oriented, and a hole with no
///   area is invisible at best.
///
/// A polygon whose exterior ring is dropped is dropped entirely, holes included: a
/// hole with nothing around it renders as solid fill.
pub fn encode_polygons(polygons: &[PolygonRings]) -> Vec<u32> {
    let mut out = Vec::new();
    let (mut cx, mut cy) = (0i32, 0i32);
    for rings in polygons {
        // Held back until the exterior is known good, so a dropped exterior takes
        // its holes with it instead of emitting orphans.
        let mut staged: Vec<Vec<(i32, i32)>> = Vec::with_capacity(rings.len());
        for (i, ring) in rings.iter().enumerate() {
            let mut open = ring.as_slice();
            while open.len() > 1 && open.first() == open.last() {
                open = &open[..open.len() - 1];
            }
            if open.len() < 3 {
                if i == 0 {
                    staged.clear();
                    break;
                }
                continue;
            }
            let area = signed_area(open);
            if area == 0 {
                if i == 0 {
                    staged.clear();
                    break;
                }
                continue;
            }
            // Exterior positive, interior negative, per the spec.
            let want_positive = i == 0;
            let mut ring: Vec<(i32, i32)> = open.to_vec();
            if (area > 0) != want_positive {
                ring.reverse();
            }
            staged.push(ring);
        }
        for ring in &staged {
            out.push(command(CMD_MOVE_TO, 1));
            push_delta(&mut out, ring[0], &mut cx, &mut cy);
            out.push(command(CMD_LINE_TO, (ring.len() - 1) as u32));
            for p in &ring[1..] {
                push_delta(&mut out, *p, &mut cx, &mut cy);
            }
            out.push(command(CMD_CLOSE_PATH, 1));
            // ClosePath moves the cursor back to the ring's start, so the next
            // ring's MoveTo delta is measured from there, not from the last vertex.
            cx = ring[0].0;
            cy = ring[0].1;
        }
    }
    out
}

#[inline]
fn push_delta(out: &mut Vec<u32>, (x, y): (i32, i32), cx: &mut i32, cy: &mut i32) {
    out.push(proto::zigzag_encode((x - *cx) as i64) as u32);
    out.push(proto::zigzag_encode((y - *cy) as i64) as u32);
    *cx = x;
    *cy = y;
}

/// Decode a line layer's geometry stream. `None` on anything that is not a
/// sequence of `MoveTo(1)` + `LineTo(n)` parts.
pub fn decode_lines(geometry: &[u32]) -> Option<Vec<Vec<(i32, i32)>>> {
    let mut out: Vec<Vec<(i32, i32)>> = Vec::new();
    let mut cursor = Cursor::new(geometry);
    while let Some((cmd, count)) = cursor.next_command() {
        match cmd {
            CMD_MOVE_TO => {
                if count != 1 {
                    return None;
                }
                out.push(vec![cursor.next_point()?]);
            }
            CMD_LINE_TO => {
                let line = out.last_mut()?;
                if count == 0 {
                    return None;
                }
                for _ in 0..count {
                    line.push(cursor.next_point()?);
                }
            }
            _ => return None,
        }
    }
    Some(out)
}

/// Decode a polygon layer's geometry stream into `[polygon][ring][vertex]`, with
/// each ring closed explicitly.
///
/// Rings are grouped into polygons by orientation, as the spec prescribes: a
/// positive-area ring starts a new polygon and negative-area rings attach to the
/// one before them. A stream that opens with a hole is rejected rather than
/// guessed at.
pub fn decode_polygons(geometry: &[u32]) -> Option<Vec<PolygonRings>> {
    let mut out: Vec<PolygonRings> = Vec::new();
    let mut current: Vec<(i32, i32)> = Vec::new();
    let mut cursor = Cursor::new(geometry);
    while let Some((cmd, count)) = cursor.next_command() {
        match cmd {
            CMD_MOVE_TO => {
                if count != 1 || !current.is_empty() {
                    return None;
                }
                current.push(cursor.next_point()?);
            }
            CMD_LINE_TO => {
                if current.is_empty() || count == 0 {
                    return None;
                }
                for _ in 0..count {
                    current.push(cursor.next_point()?);
                }
            }
            CMD_CLOSE_PATH => {
                if count != 1 || current.len() < 3 {
                    return None;
                }
                let ring = std::mem::take(&mut current);
                // ClosePath returns the cursor to the ring's start.
                cursor.set(ring[0]);
                let positive = signed_area(&ring) > 0;
                let mut closed = ring;
                closed.push(closed[0]);
                if positive {
                    out.push(vec![closed]);
                } else {
                    out.last_mut()?.push(closed);
                }
            }
            _ => return None,
        }
    }
    // An unterminated ring means a truncated stream.
    current.is_empty().then_some(out)
}

/// Walks a command stream, carrying the delta cursor.
struct Cursor<'a> {
    geometry: &'a [u32],
    i: usize,
    x: i32,
    y: i32,
}

impl<'a> Cursor<'a> {
    fn new(geometry: &'a [u32]) -> Cursor<'a> {
        Cursor { geometry, i: 0, x: 0, y: 0 }
    }

    fn next_command(&mut self) -> Option<(u32, u32)> {
        let v = *self.geometry.get(self.i)?;
        self.i += 1;
        Some(command_parts(v))
    }

    fn next_point(&mut self) -> Option<(i32, i32)> {
        let dx = *self.geometry.get(self.i)?;
        let dy = *self.geometry.get(self.i + 1)?;
        self.i += 2;
        self.x = self.x.wrapping_add(proto::zigzag_decode(dx as u64) as i32);
        self.y = self.y.wrapping_add(proto::zigzag_decode(dy as u64) as i32);
        Some((self.x, self.y))
    }

    fn set(&mut self, (x, y): (i32, i32)) {
        self.x = x;
        self.y = y;
    }
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

    // --- signed area -------------------------------------------------------

    #[test]
    fn signed_area_is_positive_for_the_specs_exterior_winding() {
        // MVT states the rule as a sign, not a direction: exterior rings are
        // positive under the surveyor's formula. In tile space, where y grows
        // downward, that is clockwise on screen -- top-right, bottom-right,
        // bottom-left, top-left. Quoting the direction instead of the sign is a
        // reliable way to get this backwards, which is why the encoder tests below
        // assert on the sign.
        let clockwise_on_screen = [(10, 0), (10, 10), (0, 10), (0, 0)];
        assert_eq!(signed_area(&clockwise_on_screen), 200);
        let mut other = clockwise_on_screen.to_vec();
        other.reverse();
        assert_eq!(signed_area(&other), -200);
    }

    #[test]
    fn signed_area_ignores_an_explicit_closing_vertex() {
        let open = [(10, 0), (10, 10), (0, 10), (0, 0)];
        let mut closed = open.to_vec();
        closed.push(open[0]);
        assert_eq!(signed_area(&open), signed_area(&closed));
        // Twice the area of a 10x10 square.
        assert_eq!(signed_area(&open).abs(), 200);
    }

    #[test]
    fn signed_area_is_zero_for_anything_that_encloses_nothing() {
        assert_eq!(signed_area(&[]), 0);
        assert_eq!(signed_area(&[(1, 1)]), 0);
        assert_eq!(signed_area(&[(0, 0), (5, 5)]), 0);
        // Collinear.
        assert_eq!(signed_area(&[(0, 0), (5, 0), (10, 0)]), 0);
        // A degenerate out-and-back.
        assert_eq!(signed_area(&[(0, 0), (10, 0), (0, 0)]), 0);
    }

    #[test]
    fn signed_area_does_not_overflow_at_the_coordinate_extremes() {
        // Two i32 spans multiply to 62 bits, so the accumulator must be i64.
        let big = i32::MAX;
        let a = signed_area(&[(0, 0), (big, 0), (big, big), (0, big)]);
        assert!(a != 0 && a.abs() > i32::MAX as i64, "{a}");
    }

    // --- line encoding -----------------------------------------------------

    fn line_roundtrip(lines: Vec<Vec<(i32, i32)>>) {
        let encoded = encode_lines(&lines);
        let decoded = decode_lines(&encoded).expect("our own output must decode");
        assert_eq!(decoded, lines);
    }

    #[test]
    fn a_line_round_trips() {
        line_roundtrip(vec![vec![(0, 0), (100, 0), (100, 100)]]);
        // Negative deltas, and a doubling-back.
        line_roundtrip(vec![vec![(500, 500), (0, 0), (500, 500), (-50, 20)]]);
        // Multiple parts share one cursor, which is where an off-by-one in the
        // delta chain would show up.
        line_roundtrip(vec![
            vec![(0, 0), (10, 10)],
            vec![(4000, 4000), (4090, 4000)],
            vec![(-5, -5), (0, 0), (5, 5)],
        ]);
    }

    #[test]
    fn the_line_command_stream_has_the_shape_the_spec_requires() {
        let g = encode_lines(&[vec![(3, 6), (8, 12), (20, 34)]]);
        // MoveTo(1), one point, LineTo(2), two points.
        assert_eq!(command_parts(g[0]), (CMD_MOVE_TO, 1));
        assert_eq!(command_parts(g[3]), (CMD_LINE_TO, 2));
        assert_eq!(g.len(), 1 + 2 + 1 + 4);
        // First point is an absolute-from-origin delta.
        assert_eq!(proto::zigzag_decode(g[1] as u64), 3);
        assert_eq!(proto::zigzag_decode(g[2] as u64), 6);
        // Second is relative to the first.
        assert_eq!(proto::zigzag_decode(g[4] as u64), 5);
        assert_eq!(proto::zigzag_decode(g[5] as u64), 6);
    }

    #[test]
    fn a_degenerate_line_part_is_skipped_not_emitted() {
        // A lone MoveTo would encode a point inside a line layer, and LineTo(0) is
        // illegal outright.
        assert!(encode_lines(&[vec![]]).is_empty());
        assert!(encode_lines(&[vec![(1, 1)]]).is_empty());
        // The valid parts around it still come through.
        let g = encode_lines(&[vec![(1, 1)], vec![(0, 0), (5, 5)], vec![]]);
        assert_eq!(decode_lines(&g).unwrap(), vec![vec![(0, 0), (5, 5)]]);
    }

    #[test]
    fn decode_lines_rejects_streams_that_are_not_lines() {
        // A multipoint MoveTo(3).
        assert!(decode_lines(&encode_points(&[(1, 1), (2, 2), (3, 3)])).is_none());
        // A LineTo with no preceding MoveTo.
        assert!(decode_lines(&[command(CMD_LINE_TO, 1), 2, 2]).is_none());
        // A ClosePath belongs to a polygon.
        assert!(decode_lines(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_CLOSE_PATH, 1)]).is_none());
        // Truncated payload.
        assert!(decode_lines(&[command(CMD_MOVE_TO, 1), 2]).is_none());
        assert!(decode_lines(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_LINE_TO, 2), 1, 1]).is_none());
        // Empty is a valid empty geometry, not an error.
        assert_eq!(decode_lines(&[]), Some(vec![]));
    }

    // --- polygon encoding: orientation, closure, holes ---------------------

    /// The unit square as an exterior ring, positive area.
    fn ext() -> Vec<(i32, i32)> {
        vec![(0, 0), (0, 100), (100, 100), (100, 0), (0, 0)]
    }

    /// A smaller square inside it, given in the *same* direction as `ext` -- so the
    /// encoder has to flip it.
    fn hole() -> Vec<(i32, i32)> {
        vec![(20, 20), (20, 80), (80, 80), (80, 20), (20, 20)]
    }

    #[test]
    fn the_encoder_derives_orientation_rather_than_trusting_the_input() {
        // Both rings arrive wound the same way. The clipper and the simplifier
        // upstream do not preserve orientation, so the input's winding means
        // nothing and the encoder must fix both.
        let g = encode_polygons(&[vec![ext(), hole()]]);
        let decoded = decode_polygons(&g).expect("our own output must decode");
        assert_eq!(decoded.len(), 1, "one polygon: {decoded:?}");
        assert_eq!(decoded[0].len(), 2, "exterior plus one hole");
        assert!(signed_area(&decoded[0][0]) > 0, "exterior must be positive");
        assert!(signed_area(&decoded[0][1]) < 0, "interior must be negative");

        // And the same when the input is wound the other way round.
        let mut e = ext();
        e.reverse();
        let mut h = hole();
        h.reverse();
        let decoded = decode_polygons(&encode_polygons(&[vec![e, h]])).unwrap();
        assert!(signed_area(&decoded[0][0]) > 0);
        assert!(signed_area(&decoded[0][1]) < 0);
    }

    #[test]
    fn every_ring_is_terminated_by_close_path_and_holes_follow_their_exterior() {
        let g = encode_polygons(&[vec![ext(), hole()]]);
        let mut commands = Vec::new();
        let mut i = 0;
        while i < g.len() {
            let (cmd, count) = command_parts(g[i]);
            commands.push(cmd);
            i += 1 + if cmd == CMD_CLOSE_PATH { 0 } else { count as usize * 2 };
        }
        assert_eq!(
            commands,
            vec![
                CMD_MOVE_TO, CMD_LINE_TO, CMD_CLOSE_PATH, // exterior
                CMD_MOVE_TO, CMD_LINE_TO, CMD_CLOSE_PATH, // its hole
            ]
        );
    }

    #[test]
    fn the_explicit_closing_vertex_is_stripped_because_close_path_implies_it() {
        // Same square, once closed and once not: the streams must be identical.
        let mut open = ext();
        open.pop();
        assert_eq!(encode_polygons(&[vec![ext()]]), encode_polygons(&[vec![open]]));
        // 4 corners: MoveTo(1) + 2 + LineTo(3) + 6 + ClosePath = 11 integers. A
        // retained closing vertex would make it LineTo(4) and 13.
        assert_eq!(encode_polygons(&[vec![ext()]]).len(), 11);
        // A ring closed several times over is still stripped to its corners.
        let mut twice = ext();
        twice.push((0, 0));
        assert_eq!(encode_polygons(&[vec![twice]]).len(), 11);
    }

    #[test]
    fn a_polygon_round_trips_with_its_rings_closed() {
        let decoded = decode_polygons(&encode_polygons(&[vec![ext(), hole()]])).unwrap();
        for ring in &decoded[0] {
            assert_eq!(ring.first(), ring.last(), "decode re-closes every ring");
            assert!(ring.len() >= 4);
        }
        // Geometry is preserved up to orientation and rotation of the vertex list.
        assert_eq!(signed_area(&decoded[0][0]).abs(), signed_area(&ext()).abs());
        assert_eq!(signed_area(&decoded[0][1]).abs(), signed_area(&hole()).abs());
    }

    #[test]
    fn several_polygons_are_grouped_by_orientation_on_the_way_back() {
        let far = vec![
            vec![(1000, 1000), (1000, 1100), (1100, 1100), (1100, 1000), (1000, 1000)],
            vec![(1020, 1020), (1020, 1080), (1080, 1080), (1080, 1020), (1020, 1020)],
        ];
        let g = encode_polygons(&[vec![ext(), hole()], far]);
        let decoded = decode_polygons(&g).unwrap();
        assert_eq!(decoded.len(), 2, "two polygons: {decoded:?}");
        assert_eq!(decoded[0].len(), 2);
        assert_eq!(decoded[1].len(), 2);
        for poly in &decoded {
            assert!(signed_area(&poly[0]) > 0);
            assert!(signed_area(&poly[1]) < 0);
        }
    }

    #[test]
    fn a_zero_area_or_too_short_ring_is_dropped() {
        // Collinear, so it cannot be oriented.
        assert!(encode_polygons(&[vec![vec![(0, 0), (5, 0), (10, 0), (0, 0)]]]).is_empty());
        assert!(encode_polygons(&[vec![vec![(0, 0), (5, 5)]]]).is_empty());
        assert!(encode_polygons(&[vec![vec![]]]).is_empty());
        // A degenerate hole is dropped, the exterior kept.
        let g = encode_polygons(&[vec![ext(), vec![(5, 5), (6, 5), (7, 5), (5, 5)]]]);
        let decoded = decode_polygons(&g).unwrap();
        assert_eq!(decoded[0].len(), 1, "exterior only");
    }

    #[test]
    fn losing_the_exterior_ring_drops_its_holes_too() {
        // A hole with nothing around it renders as solid fill in the layer's
        // colour, which is worse than the feature being absent.
        let g = encode_polygons(&[vec![vec![(0, 0), (5, 0), (10, 0), (0, 0)], hole()]]);
        assert!(g.is_empty(), "{g:?}");
        // The polygon after it is unaffected.
        let g = encode_polygons(&[
            vec![vec![(0, 0), (5, 0), (10, 0)], hole()],
            vec![ext()],
        ]);
        assert_eq!(decode_polygons(&g).unwrap().len(), 1);
    }

    #[test]
    fn close_path_resets_the_cursor_to_the_rings_start() {
        // The spec says ClosePath returns the cursor to the ring's first vertex, so
        // the next ring's MoveTo delta is measured from there. Getting this wrong
        // puts every ring after the first in the wrong place, which the round trip
        // is the only thing that catches.
        let a = vec![(0, 0), (0, 10), (10, 10), (10, 0), (0, 0)];
        let b = vec![(500, 500), (500, 510), (510, 510), (510, 500), (500, 500)];
        let decoded = decode_polygons(&encode_polygons(&[vec![a.clone()], vec![b.clone()]])).unwrap();
        assert_eq!(decoded.len(), 2);
        let corners = |ring: &[(i32, i32)]| {
            let mut c: Vec<(i32, i32)> = ring[..ring.len() - 1].to_vec();
            c.sort_unstable();
            c
        };
        assert_eq!(corners(&decoded[0][0]), corners(&a));
        assert_eq!(corners(&decoded[1][0]), corners(&b));
    }

    #[test]
    fn decode_polygons_rejects_malformed_streams() {
        // Opens with a hole: there is nothing to attach it to, and guessing would
        // silently turn a hole into a fill.
        let ring = [(20, 80), (80, 80), (80, 20), (20, 20)];
        assert!(signed_area(&ring) < 0, "a hole by area, not by intent");
        let mut g = vec![command(CMD_MOVE_TO, 1)];
        let (mut cx, mut cy) = (0i32, 0i32);
        push_delta(&mut g, ring[0], &mut cx, &mut cy);
        g.push(command(CMD_LINE_TO, 3));
        for p in &ring[1..] {
            push_delta(&mut g, *p, &mut cx, &mut cy);
        }
        g.push(command(CMD_CLOSE_PATH, 1));
        assert!(decode_polygons(&g).is_none(), "a leading hole is rejected");

        // Unterminated ring (no ClosePath).
        assert!(decode_polygons(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_LINE_TO, 2), 1, 1, 1, 1]).is_none());
        // ClosePath with too few vertices.
        assert!(decode_polygons(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_CLOSE_PATH, 1)]).is_none());
        // A second MoveTo before the ring is closed.
        assert!(decode_polygons(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_MOVE_TO, 1), 2, 2]).is_none());
        assert_eq!(decode_polygons(&[]), Some(vec![]));
    }

    #[test]
    fn the_decoders_do_not_confuse_each_others_geometry() {
        let points = encode_points(&[(1, 1), (2, 2)]);
        let lines = encode_lines(&[vec![(0, 0), (10, 10)]]);
        let polys = encode_polygons(&[vec![ext()]]);
        assert!(decode_points(&points).is_some());
        assert!(decode_points(&lines).is_none());
        assert!(decode_points(&polys).is_none());
        assert!(decode_lines(&points).is_none());
        assert!(decode_lines(&lines).is_some());
        assert!(decode_lines(&polys).is_none());
        assert!(decode_polygons(&points).is_none());
        // A line stream has no ClosePath, so it decodes as no polygons at all
        // rather than as a bogus one.
        assert_eq!(decode_polygons(&lines), None);
        assert!(decode_polygons(&polys).is_some());
    }

    // --- golden byte fixture ----------------------------------------------

    #[test]
    fn a_line_and_polygon_tile_encodes_to_exactly_these_bytes() {
        // A golden fixture, computed by hand from the wire layout in the module
        // docs. It pins the whole encode path -- field numbers, field order, packed
        // geometry, the property dictionaries -- so a change anywhere in it has to
        // be deliberate rather than incidental.
        let mut roads = Layer::new("roads");
        roads.features.push(Feature {
            id: None,
            geom_type: GeomType::LineString,
            geometry: encode_lines(&[vec![(0, 0), (2, 4)]]),
            props: vec![("kind".to_string(), Value::String("rail".into()))],
        });
        let body = Tile { layers: vec![roads] }.encode();

        // layer message:
        //   1 name  "roads"                     0a 05 72 6f 61 64 73
        //   2 feature:
        //        2 tags   [0, 0]                12 02 00 00
        //        3 type   2 (LineString)        18 02
        //        4 geom   [9, 0, 0, 10, 4, 8]   22 06 09 00 00 0a 04 08
        //   3 keys  "kind"                      1a 04 6b 69 6e 64
        //   4 values { 1: "rail" }              22 06 0a 04 72 61 69 6c
        //   5 extent 4096                       28 80 20
        //  15 version 2                         78 02
        #[rustfmt::skip]
        let layer: Vec<u8> = vec![
            0x0a, 0x05, b'r', b'o', b'a', b'd', b's',
            0x12, 0x0e,
                0x12, 0x02, 0x00, 0x00,
                0x18, 0x02,
                0x22, 0x06, 0x09, 0x00, 0x00, 0x0a, 0x04, 0x08,
            0x1a, 0x04, b'k', b'i', b'n', b'd',
            0x22, 0x06, 0x0a, 0x04, b'r', b'a', b'i', b'l',
            0x28, 0x80, 0x20,
            0x78, 0x02,
        ];
        let mut expected: Vec<u8> = vec![0x1a, layer.len() as u8];
        expected.extend_from_slice(&layer);
        assert_eq!(body, expected, "encoded {body:02x?}");

        // And it reads back as what it claims to be.
        let tile = Tile::decode(&body).unwrap();
        let f = &tile.layer("roads").unwrap().features[0];
        assert_eq!(f.geom_type, GeomType::LineString);
        assert_eq!(decode_lines(&f.geometry).unwrap(), vec![vec![(0, 0), (2, 4)]]);
        assert_eq!(f.get("kind"), Some(&Value::String("rail".into())));
    }

    #[test]
    fn a_polygon_features_geometry_survives_a_tile_round_trip() {
        // The encoders feed Feature::geometry, which the tile codec treats as
        // opaque -- so the two halves have to agree on the stream, and this is what
        // proves they do end to end.
        let mut admin = Layer::new("admin_city");
        admin.features.push(Feature {
            id: Some(7),
            geom_type: GeomType::Polygon,
            geometry: encode_polygons(&[vec![ext(), hole()]]),
            props: vec![("name".to_string(), Value::String("Oakland".into()))],
        });
        let tile = Tile::decode(&Tile { layers: vec![admin] }.encode()).unwrap();
        let f = &tile.layer("admin_city").unwrap().features[0];
        assert_eq!(f.id, Some(7));
        assert_eq!(f.geom_type, GeomType::Polygon);
        let rings = decode_polygons(&f.geometry).unwrap();
        assert_eq!(rings.len(), 1);
        assert_eq!(rings[0].len(), 2);
        assert!(signed_area(&rings[0][0]) > 0);
        assert!(signed_area(&rings[0][1]) < 0);
    }
}
