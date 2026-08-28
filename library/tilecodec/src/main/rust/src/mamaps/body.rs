//! One tile body: every layer's geometry, flat and renderer-shaped.
//!
//! # What is not here
//!
//! **Triangles.** Pre-tessellating would bake the style's layer set into the data, so a restyle
//! would mean a rebuild — and the whole point of splitting one data-driven layer into several is
//! that it is a *paint* decision.
//!
//! **Points.** The renderer decodes them and throws them away, so they are not carried. A
//! geometry type is line or polygon and nothing else.
//!
//! **A property map.** A feature's whole attribute surface is `kind`, `kind_detail` and three
//! bits, because that is all the style filters on. No per-tile string table, no keys, no values.
//!
//! # Why all layers share one body
//!
//! Splitting per layer would multiply a cold tile's range requests by seven, and the renderer
//! wants every style layer for one tile at once anyway. One body, one request.
//!
//! # Layout
//!
//! ```text
//! [BodyHeader 16][LayerIndex × n][per layer: FeatureRecord[] PartEntry[] coords]
//! ```
//!
//! Every section is 4-byte aligned so a reader takes zero-copy slices, and coordinates are
//! `i16` pairs in extent units with room either side for the clip buffer.
//!
//! `raw_len` lives in the body header, which is **outside** the compressed frame, so a
//! decompressing reader knows the exact output size before it starts: an exact-length read from
//! the leaf entry and a single allocation from here.

use crate::proto::{err, Result};

pub const BODY_HEADER_LEN: usize = 16;
pub const LAYER_INDEX_LEN: usize = 12;
pub const FEATURE_RECORD_LEN: usize = 16;
pub const PART_ENTRY_LEN: usize = 12;

/// A feature whose geometry is one or more open paths.
pub const GEOM_LINE: u8 = 1;
/// A feature whose geometry is one exterior ring plus its holes.
pub const GEOM_POLYGON: u8 = 2;

pub const FLAG_IS_TUNNEL: u8 = 1 << 0;
pub const FLAG_IS_BRIDGE: u8 = 1 << 1;
pub const FLAG_IS_LINK: u8 = 1 << 2;
/// `kind_detail` is a number, not an id into
/// [`dict::DETAILS`](super::dict::DETAILS).
///
/// What lets `boundaries` carry an admin level in the same field a road carries `service` in: the
/// style compares an admin level with `<=`, so interning it would mean interning every integer.
pub const FLAG_DETAIL_NUMERIC: u8 = 1 << 3;

const KNOWN_FEATURE_FLAGS: u8 =
    FLAG_IS_TUNNEL | FLAG_IS_BRIDGE | FLAG_IS_LINK | FLAG_DETAIL_NUMERIC;

/// A ring wound counter-clockwise: the outside of a polygon.
pub const WINDING_OUTER: u16 = 0;
/// A ring wound clockwise: a hole.
pub const WINDING_HOLE: u16 = 1;

/// The tile grid a body's coordinates are in. 4096, as MVT uses, so nothing downstream rescales.
pub const DEFAULT_EXTENT: u16 = 4096;

/// One feature.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Feature {
    /// An id into [`dict::KINDS`](super::dict::KINDS), or
    /// [`dict::NONE`](super::dict::NONE).
    pub kind: u16,
    /// An id into [`dict::DETAILS`](super::dict::DETAILS), or a plain number when
    /// [`FLAG_DETAIL_NUMERIC`] is set.
    pub kind_detail: u16,
    pub geom_type: u8,
    pub flags: u8,
    /// Where this feature's parts start in the layer's part table.
    pub parts_offset: u32,
    pub part_count: u32,
}

impl Feature {
    pub fn is_tunnel(&self) -> bool {
        self.flags & FLAG_IS_TUNNEL != 0
    }

    pub fn is_bridge(&self) -> bool {
        self.flags & FLAG_IS_BRIDGE != 0
    }

    pub fn is_link(&self) -> bool {
        self.flags & FLAG_IS_LINK != 0
    }

    /// The numeric `kind_detail`, e.g. a boundary's admin level, or `None` when the field is an
    /// interned id.
    pub fn detail_number(&self) -> Option<u16> {
        (self.flags & FLAG_DETAIL_NUMERIC != 0).then_some(self.kind_detail)
    }
}

/// One path: a line, or one ring of a polygon.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Part {
    /// Where this part's points start in the layer's coordinate arena, in points not bytes.
    pub coord_start: u32,
    pub point_count: u32,
    /// [`WINDING_OUTER`] or [`WINDING_HOLE`], stated rather than derived.
    ///
    /// Explicit because the generator already computed the signed area in `f64` with no frame
    /// budget, and a reader recovering it from `i16` coordinates that have been clipped and
    /// quantised can get a near-degenerate ring wrong.
    pub winding: u16,
}

impl Part {
    pub fn is_hole(&self) -> bool {
        self.winding == WINDING_HOLE
    }
}

/// One layer's features, parts and coordinates, decoded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layer {
    pub layer_id: u8,
    pub features: Vec<Feature>,
    pub parts: Vec<Part>,
    /// `x, y` pairs in extent units.
    pub coords: Vec<(i16, i16)>,
}

impl Layer {
    pub fn new(layer_id: u8) -> Layer {
        Layer { layer_id, features: Vec::new(), parts: Vec::new(), coords: Vec::new() }
    }

    /// The points of one part, as a slice of the arena.
    pub fn points(&self, part: &Part) -> &[(i16, i16)] {
        let start = part.coord_start as usize;
        &self.coords[start..start + part.point_count as usize]
    }

    /// The parts of one feature.
    pub fn parts_of(&self, feature: &Feature) -> &[Part] {
        let start = feature.parts_offset as usize;
        &self.parts[start..start + feature.part_count as usize]
    }
}

/// A whole tile.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Body {
    pub extent: u16,
    pub layers: Vec<Layer>,
}

impl Body {
    pub fn new(extent: u16) -> Body {
        Body { extent, layers: Vec::new() }
    }

    pub fn layer(&self, layer_id: u8) -> Option<&Layer> {
        self.layers.iter().find(|l| l.layer_id == layer_id)
    }

    /// The decompressed size a body's bytes will occupy, read without decoding anything else.
    ///
    /// Outside the compressed frame on purpose: it is what lets a reader allocate exactly once.
    /// Needs only the first eight bytes, so it can be answered from a frame's uncompressed prefix.
    pub fn raw_len(bytes: &[u8]) -> Result<u32> {
        if bytes.len() < 8 {
            return err("a .mamaps body is too short to declare its own length");
        }
        Ok(u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]))
    }

    /// Body header: `0..4` magic-and-version, `4..8` raw_len, `8..10` extent, `10` layer_count,
    /// `11` flags, `12..16` reserved.
    pub fn parse(buf: &[u8]) -> Result<Body> {
        if buf.len() < BODY_HEADER_LEN {
            return err("a .mamaps body is shorter than its own header");
        }
        if buf[0..3] != *b"MBD" {
            return err("not a .mamaps tile body (bad magic)");
        }
        if buf[3] != super::header::FORMAT_VERSION {
            return err(format!("unsupported .mamaps body version {}", buf[3]));
        }
        let raw_len = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]) as usize;
        if raw_len != buf.len() {
            return err(format!(
                "a .mamaps body declares {raw_len} bytes but is {}",
                buf.len(),
            ));
        }
        let extent = u16::from_le_bytes([buf[8], buf[9]]);
        if extent == 0 {
            return err("a .mamaps body has a zero extent");
        }
        let layer_count = buf[10] as usize;
        if u32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]) != 0 {
            return err("a .mamaps body has a non-zero reserved word");
        }

        let index_end = BODY_HEADER_LEN + layer_count * LAYER_INDEX_LEN;
        if index_end > buf.len() {
            return err("a .mamaps body's layer index runs past its end");
        }
        let mut layers = Vec::with_capacity(layer_count);
        let mut previous: Option<u8> = None;
        for i in 0..layer_count {
            let at = BODY_HEADER_LEN + i * LAYER_INDEX_LEN;
            let u32_at = |o: usize| {
                u32::from_le_bytes([buf[at + o], buf[at + o + 1], buf[at + o + 2], buf[at + o + 3]])
            };
            let layer_id = buf[at];
            // Ascending and distinct, so `Body::layer` is unambiguous and a corrupt body cannot
            // present two layers with one id.
            if previous.is_some_and(|p| layer_id <= p) {
                return err("a .mamaps body's layers are not ordered by id");
            }
            previous = Some(layer_id);
            let feature_count = u16::from_le_bytes([buf[at + 2], buf[at + 3]]) as usize;
            let (offset, length) = (u32_at(4) as usize, u32_at(8) as usize);
            let end = offset.checked_add(length).ok_or_else(|| {
                crate::proto::Error("a .mamaps layer's extent overflows".to_string())
            })?;
            if offset < index_end || end > buf.len() {
                return err("a .mamaps layer's payload is outside the body");
            }
            layers.push(parse_layer(layer_id, feature_count, &buf[offset..end])?);
        }
        Ok(Body { extent, layers })
    }
}

/// One layer's payload: features, then parts, then the coordinate arena.
///
/// Every count is bounded by the slice it is read from before anything is allocated, so a corrupt
/// body cannot ask for a gigabyte of `Vec`.
fn parse_layer(layer_id: u8, feature_count: usize, buf: &[u8]) -> Result<Layer> {
    let features_len = feature_count * FEATURE_RECORD_LEN;
    if features_len > buf.len() {
        return err("a .mamaps layer's features run past its payload");
    }
    let mut features = Vec::with_capacity(feature_count);
    let mut parts_needed = 0usize;
    for i in 0..feature_count {
        let at = i * FEATURE_RECORD_LEN;
        let u16_at = |o: usize| u16::from_le_bytes([buf[at + o], buf[at + o + 1]]);
        let u32_at = |o: usize| {
            u32::from_le_bytes([buf[at + o], buf[at + o + 1], buf[at + o + 2], buf[at + o + 3]])
        };
        let geom_type = buf[at + 4];
        if !matches!(geom_type, GEOM_LINE | GEOM_POLYGON) {
            return err(format!("a .mamaps feature has geometry type {geom_type}, which this format does not carry"));
        }
        let flags = buf[at + 5];
        if flags & !KNOWN_FEATURE_FLAGS != 0 {
            return err("a .mamaps feature sets unknown flags");
        }
        let feature = Feature {
            kind: u16_at(0),
            kind_detail: u16_at(2),
            geom_type,
            flags,
            parts_offset: u32_at(8),
            part_count: u32_at(12),
        };
        if feature.part_count == 0 {
            return err("a .mamaps feature has no geometry");
        }
        let end = (feature.parts_offset as usize)
            .checked_add(feature.part_count as usize)
            .ok_or_else(|| crate::proto::Error("a .mamaps feature's parts overflow".to_string()))?;
        parts_needed = parts_needed.max(end);
        features.push(feature);
    }

    let parts_at = features_len;
    let parts_len = parts_needed * PART_ENTRY_LEN;
    if parts_at + parts_len > buf.len() {
        return err("a .mamaps layer's parts run past its payload");
    }
    let mut parts = Vec::with_capacity(parts_needed);
    let mut coords_needed = 0usize;
    for i in 0..parts_needed {
        let at = parts_at + i * PART_ENTRY_LEN;
        let u32_at = |o: usize| {
            u32::from_le_bytes([buf[at + o], buf[at + o + 1], buf[at + o + 2], buf[at + o + 3]])
        };
        let winding = u16::from_le_bytes([buf[at + 8], buf[at + 9]]);
        if !matches!(winding, WINDING_OUTER | WINDING_HOLE) {
            return err(format!("a .mamaps part has winding {winding}, which is neither outer nor hole"));
        }
        if u16::from_le_bytes([buf[at + 10], buf[at + 11]]) != 0 {
            return err("a .mamaps part has a non-zero reserved half-word");
        }
        let part = Part { coord_start: u32_at(0), point_count: u32_at(4), winding };
        let end = (part.coord_start as usize)
            .checked_add(part.point_count as usize)
            .ok_or_else(|| crate::proto::Error("a .mamaps part's coordinates overflow".to_string()))?;
        coords_needed = coords_needed.max(end);
        parts.push(part);
    }

    let coords_at = align4(parts_at + parts_len);
    if coords_at + coords_needed * 4 > buf.len() {
        return err("a .mamaps layer's coordinates run past its payload");
    }
    let mut coords = Vec::with_capacity(coords_needed);
    for i in 0..coords_needed {
        let at = coords_at + i * 4;
        coords.push((
            i16::from_le_bytes([buf[at], buf[at + 1]]),
            i16::from_le_bytes([buf[at + 2], buf[at + 3]]),
        ));
    }
    Ok(Layer { layer_id, features, parts, coords })
}

/// Round up to the next 4-byte boundary, so every section starts where a reader can slice it.
pub(crate) fn align4(at: usize) -> usize {
    (at + 3) & !3
}

/// Serialise a body.
///
/// Deliberately available without the `write` feature: a synthetic body is how the reader's own
/// tests get something to read, and `mamaps_dump` round-trips one to check a body is sane.
pub fn serialize(body: &Body) -> Result<Vec<u8>> {
    let mut layers = body.layers.clone();
    // Ascending by id, which the parser requires and `Body::layer` assumes. Sorted rather than
    // rejected, because a caller assembling a tile per style layer has no reason to care.
    layers.sort_by_key(|l| l.layer_id);
    if layers.windows(2).any(|pair| pair[0].layer_id == pair[1].layer_id) {
        return err("a .mamaps body cannot carry two layers with the same id");
    }
    for layer in &layers {
        if layer.features.len() > u16::MAX as usize {
            return err(format!(
                "layer {} has {} features, past the {} a .mamaps body can index",
                layer.layer_id,
                layer.features.len(),
                u16::MAX,
            ));
        }
    }

    let index_end = BODY_HEADER_LEN + layers.len() * LAYER_INDEX_LEN;
    let mut payloads: Vec<(u8, u16, Vec<u8>)> = Vec::with_capacity(layers.len());
    for layer in &layers {
        let mut out = Vec::new();
        for feature in &layer.features {
            out.extend_from_slice(&feature.kind.to_le_bytes());
            out.extend_from_slice(&feature.kind_detail.to_le_bytes());
            out.push(feature.geom_type);
            out.push(feature.flags);
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&feature.parts_offset.to_le_bytes());
            out.extend_from_slice(&feature.part_count.to_le_bytes());
        }
        for part in &layer.parts {
            out.extend_from_slice(&part.coord_start.to_le_bytes());
            out.extend_from_slice(&part.point_count.to_le_bytes());
            out.extend_from_slice(&part.winding.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
        }
        while out.len() % 4 != 0 {
            out.push(0);
        }
        for (x, y) in &layer.coords {
            out.extend_from_slice(&x.to_le_bytes());
            out.extend_from_slice(&y.to_le_bytes());
        }
        payloads.push((layer.layer_id, layer.features.len() as u16, out));
    }

    let mut offset = align4(index_end);
    let mut index = Vec::with_capacity(layers.len() * LAYER_INDEX_LEN);
    for (layer_id, feature_count, payload) in &payloads {
        index.push(*layer_id);
        index.push(0);
        index.extend_from_slice(&feature_count.to_le_bytes());
        index.extend_from_slice(&(offset as u32).to_le_bytes());
        index.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        offset += payload.len();
    }

    let mut out = Vec::with_capacity(offset);
    out.extend_from_slice(b"MBD");
    out.push(super::header::FORMAT_VERSION);
    // Patched below, once the length is known.
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&body.extent.to_le_bytes());
    out.push(layers.len() as u8);
    out.push(0);
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&index);
    while out.len() % 4 != 0 {
        out.push(0);
    }
    for (_, _, payload) in &payloads {
        out.extend_from_slice(payload);
    }
    let raw_len = out.len() as u32;
    out[4..8].copy_from_slice(&raw_len.to_le_bytes());
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mamaps::dict;

    /// A tile with one polygon carrying a hole and one road, which between them exercise every
    /// field the format has.
    fn sample() -> Body {
        let mut water = Layer::new(dict::LAYER_WATER);
        water.features.push(Feature {
            kind: 4,
            kind_detail: dict::NONE,
            geom_type: GEOM_POLYGON,
            flags: 0,
            parts_offset: 0,
            part_count: 2,
        });
        water.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
        water.parts.push(Part { coord_start: 4, point_count: 4, winding: WINDING_HOLE });
        water.coords = vec![
            (0, 0),
            (4096, 0),
            (4096, 4096),
            (0, 4096),
            (1000, 1000),
            (1000, 2000),
            (2000, 2000),
            (2000, 1000),
        ];

        let mut roads = Layer::new(dict::LAYER_ROADS);
        roads.features.push(Feature {
            kind: 45,
            kind_detail: 4,
            geom_type: GEOM_LINE,
            flags: FLAG_IS_BRIDGE | FLAG_IS_LINK,
            parts_offset: 0,
            part_count: 1,
        });
        roads.parts.push(Part { coord_start: 0, point_count: 3, winding: WINDING_OUTER });
        roads.coords = vec![(-64, 10), (2048, 2048), (4160, 4000)];

        Body { extent: DEFAULT_EXTENT, layers: vec![roads, water] }
    }

    #[test]
    fn a_body_round_trips_with_its_layers_in_id_order() {
        let bytes = serialize(&sample()).expect("should serialize");
        let parsed = Body::parse(&bytes).expect("should parse");
        assert_eq!(parsed.extent, DEFAULT_EXTENT);
        // Sorted on the way out, whatever order the caller assembled them in.
        assert_eq!(
            parsed.layers.iter().map(|l| l.layer_id).collect::<Vec<_>>(),
            vec![dict::LAYER_WATER, dict::LAYER_ROADS],
        );
        let expected = {
            let mut body = sample();
            body.layers.sort_by_key(|l| l.layer_id);
            body
        };
        assert_eq!(parsed, expected);
    }

    #[test]
    fn every_fixed_record_is_the_width_the_format_declares() {
        // A stride that drifted would misread every record after the first.
        let bytes = serialize(&sample()).expect("serialize");
        assert_eq!(&bytes[0..3], b"MBD");
        assert_eq!(Body::raw_len(&bytes).expect("raw_len"), bytes.len() as u32);
        // Two layers: header, two index entries, then payloads.
        assert_eq!(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN, 40);
        assert_eq!(FEATURE_RECORD_LEN, 16);
        assert_eq!(PART_ENTRY_LEN, 12);
    }

    /// `raw_len` is read from the body header, which sits outside the compressed frame, so a
    /// decompressing reader knows the output size before it starts and allocates once.
    #[test]
    fn raw_len_is_readable_from_the_first_eight_bytes_alone() {
        let bytes = serialize(&sample()).expect("serialize");
        assert_eq!(Body::raw_len(&bytes[..8]).expect("raw_len"), bytes.len() as u32);
        assert!(Body::raw_len(&bytes[..7]).is_err(), "too short to declare a length");
    }

    #[test]
    fn every_layer_payload_starts_four_byte_aligned() {
        // What lets a reader take zero-copy slices of the coordinate arena.
        let bytes = serialize(&sample()).expect("serialize");
        let layer_count = bytes[10] as usize;
        for i in 0..layer_count {
            let at = BODY_HEADER_LEN + i * LAYER_INDEX_LEN;
            let offset =
                u32::from_le_bytes([bytes[at + 4], bytes[at + 5], bytes[at + 6], bytes[at + 7]]);
            assert_eq!(offset % 4, 0, "layer {i} starts at {offset}");
        }
    }

    #[test]
    fn a_features_geometry_reads_back_through_the_part_table() {
        let bytes = serialize(&sample()).expect("serialize");
        let body = Body::parse(&bytes).expect("parse");
        let water = body.layer(dict::LAYER_WATER).expect("water");
        let feature = &water.features[0];
        let parts = water.parts_of(feature);
        assert_eq!(parts.len(), 2);
        assert!(!parts[0].is_hole(), "the exterior is outer");
        assert!(parts[1].is_hole());
        assert_eq!(water.points(&parts[0]).len(), 4);
        assert_eq!(water.points(&parts[1])[0], (1000, 1000));

        let roads = body.layer(dict::LAYER_ROADS).expect("roads");
        let road = &roads.features[0];
        assert!(road.is_bridge() && road.is_link() && !road.is_tunnel());
        assert_eq!(road.detail_number(), None, "an interned detail, not a number");
        // Coordinates may leave the tile: the clip keeps a buffer either side.
        assert_eq!(roads.points(&roads.parts_of(road)[0])[0], (-64, 10));
    }

    /// A boundary's admin level shares the field a road's `service` uses, discriminated by a flag,
    /// because the style compares an admin level with `<=` rather than matching a name.
    #[test]
    fn a_numeric_detail_is_a_number_and_an_interned_one_is_not() {
        let mut boundaries = Layer::new(dict::LAYER_BOUNDARIES);
        boundaries.features.push(Feature {
            kind: dict::NONE,
            kind_detail: 2,
            geom_type: GEOM_LINE,
            flags: FLAG_DETAIL_NUMERIC,
            parts_offset: 0,
            part_count: 1,
        });
        boundaries.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
        boundaries.coords = vec![(0, 0), (100, 100)];
        let bytes = serialize(&Body { extent: DEFAULT_EXTENT, layers: vec![boundaries] })
            .expect("serialize");
        let body = Body::parse(&bytes).expect("parse");
        let feature = &body.layer(dict::LAYER_BOUNDARIES).expect("boundaries").features[0];
        assert_eq!(feature.detail_number(), Some(2), "admin level 2, a country border");
    }

    #[test]
    fn an_empty_body_is_valid_and_is_what_an_ocean_tile_costs() {
        let bytes = serialize(&Body::new(DEFAULT_EXTENT)).expect("serialize");
        assert_eq!(bytes.len(), BODY_HEADER_LEN, "nothing but the header");
        let body = Body::parse(&bytes).expect("parse");
        assert!(body.layers.is_empty());
        assert_eq!(body.extent, DEFAULT_EXTENT);
    }

    #[test]
    fn a_layer_with_no_features_still_round_trips() {
        let body = Body { extent: DEFAULT_EXTENT, layers: vec![Layer::new(dict::LAYER_EARTH)] };
        let parsed = Body::parse(&serialize(&body).expect("serialize")).expect("parse");
        assert_eq!(parsed, body);
    }

    #[test]
    fn a_body_carrying_two_layers_with_one_id_is_refused() {
        let body = Body {
            extent: DEFAULT_EXTENT,
            layers: vec![Layer::new(dict::LAYER_WATER), Layer::new(dict::LAYER_WATER)],
        };
        assert!(serialize(&body).is_err());
    }

    /// A corrupt body must fail here, not by indexing past a slice or allocating on a count it
    /// read out of the same corrupt bytes.
    #[test]
    fn a_corrupt_body_is_refused_rather_than_decoded() {
        let good = serialize(&sample()).expect("serialize");
        assert!(Body::parse(&good[..BODY_HEADER_LEN - 1]).is_err(), "shorter than the header");
        assert!(Body::parse(&good[..good.len() - 4]).is_err(), "declares more than it is");

        let cases: &[(&str, fn(&mut Vec<u8>))] = &[
            ("bad magic", |b| b[0] = b'X'),
            ("a newer body version", |b| b[3] = 99),
            ("a zero extent", |b| b[8..10].copy_from_slice(&0u16.to_le_bytes())),
            ("a dirty reserved word", |b| b[12] = 1),
            ("a layer count past the index", |b| b[10] = 200),
            ("a layer payload outside the body", |b| {
                b[20..24].copy_from_slice(&999_999u32.to_le_bytes())
            }),
            ("a layer payload starting inside the index", |b| {
                b[20..24].copy_from_slice(&0u32.to_le_bytes())
            }),
        ];
        for (what, break_it) in cases {
            let mut bytes = good.clone();
            break_it(&mut bytes);
            assert!(Body::parse(&bytes).is_err(), "{what} should be refused");
        }
    }

    /// Points are not carried at all: the renderer decoded them and threw them away, so a body
    /// claiming one is a body written by something that misunderstood the format.
    #[test]
    fn a_point_geometry_is_refused_because_this_format_has_none() {
        let mut bytes = serialize(&sample()).expect("serialize");
        // The first layer's first feature record: water, at the aligned payload start.
        let at = align4(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN);
        bytes[at + 4] = 0;
        assert!(Body::parse(&bytes).is_err(), "geometry type 0");
        bytes[at + 4] = 3;
        assert!(Body::parse(&bytes).is_err(), "a point");
        bytes[at + 4] = GEOM_POLYGON;
        assert!(Body::parse(&bytes).is_ok(), "and the sample is otherwise fine");
    }

    #[test]
    fn a_feature_or_part_pointing_outside_its_own_tables_is_refused() {
        let at = align4(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN);
        let cases: &[(&str, usize, u32)] = &[
            ("a part table past the payload", at + 8, 9_999),
            ("a feature with no geometry", at + 12, 0),
            ("more parts than the payload holds", at + 12, 9_999),
        ];
        for (what, offset, value) in cases {
            let mut bytes = serialize(&sample()).expect("serialize");
            bytes[*offset..*offset + 4].copy_from_slice(&value.to_le_bytes());
            assert!(Body::parse(&bytes).is_err(), "{what} should be refused");
        }
    }

    #[test]
    fn a_winding_that_is_neither_outer_nor_hole_is_refused() {
        let mut bytes = serialize(&sample()).expect("serialize");
        // Water's part table follows its single 16-byte feature record.
        let at = align4(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN) + FEATURE_RECORD_LEN;
        bytes[at + 8..at + 10].copy_from_slice(&7u16.to_le_bytes());
        assert!(Body::parse(&bytes).is_err());
    }

    #[test]
    fn an_unknown_feature_flag_is_refused() {
        let mut bytes = serialize(&sample()).expect("serialize");
        let at = align4(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN);
        bytes[at + 5] = 0x80;
        assert!(Body::parse(&bytes).is_err());
    }

    /// Byte-for-byte the same input gives byte-for-byte the same body, because nothing in the
    /// encoder iterates a hash map. Invariant 5 of the plan's list, at the body level.
    #[test]
    fn serialising_the_same_body_twice_gives_the_same_bytes() {
        assert_eq!(serialize(&sample()).expect("a"), serialize(&sample()).expect("b"));
        // And assembling the layers in the other order does too, because they are sorted.
        let mut reordered = sample();
        reordered.layers.reverse();
        assert_eq!(serialize(&sample()).expect("a"), serialize(&reordered).expect("b"));
    }
}
