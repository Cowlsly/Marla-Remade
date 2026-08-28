//! Converting one MVT tile into a `.mamaps` body.
//!
//! The bridge between the two formats, and it exists for three callers rather than one:
//!
//! * `mamaps_from_pmtiles` converts a whole archive, which is how the container and the renderer
//!   were first validated end to end — on data the tiler already produced and the app already
//!   drew, before any tag→kind schema work existed to be wrong.
//! * `library/map`'s host probes read the published `v4.pmtiles` and need a body to tessellate.
//! * The differential harness compares a generated archive against upstream tile for tile, which
//!   means putting both through the same decoder.
//!
//! # What is dropped, and why that is the point
//!
//! Points, every property but `kind`, and the per-tile string table. The renderer reads exactly
//! one property and throws points away, so this is not lossy in any sense that reaches a pixel —
//! it is the whole saving the format exists for.
//!
//! A `kind` this crate's schema does not know becomes [`dict::NONE`] rather than an error. The
//! upstream schema is not ours and it changes; a value we cannot name is a feature drawn by the
//! unfiltered fallback layer, which is what MapLibre does with it too. [`Stats`] counts them so a
//! conversion says how much it did not understand.

use crate::mamaps::body::{
    Body, Feature, Layer, Part, DEFAULT_EXTENT, FLAG_DETAIL_NUMERIC, FLAG_IS_BRIDGE, FLAG_IS_LINK,
    FLAG_IS_TUNNEL, GEOM_LINE, GEOM_POLYGON, WINDING_HOLE, WINDING_OUTER,
};
use crate::mamaps::dict;
use crate::mvt::{self, GeomType, Tile, Value};
use crate::proto::{err, Result};

/// What a conversion could not carry, so a caller can report it rather than discover it on screen.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Stats {
    pub features: u64,
    /// Features whose `kind` is not in [`dict::KINDS`]. Drawn by an unfiltered layer, not lost.
    pub unknown_kinds: u64,
    /// Point features, which this format does not carry at all.
    pub points_dropped: u64,
    /// Source layers with no counterpart in [`dict::LAYERS`], skipped whole.
    pub layers_skipped: u64,
    /// Coordinates outside what an `i16` holds, clamped to its range.
    ///
    /// Should be zero: Protomaps buffers a tile by a few percent, so an `i16` has eight times the
    /// headroom needed. A non-zero count means the input is not what it claims and the geometry is
    /// no longer where it was.
    pub coords_clamped: u64,
}

/// Convert every layer of an MVT tile this format has a counterpart for.
pub fn from_tile(tile: &Tile) -> Result<(Body, Stats)> {
    let mut stats = Stats::default();
    let mut extent: Option<u32> = None;
    let mut layers = Vec::new();

    // Driven by the **schema's** layer order rather than the tile's, so the output is the same
    // whatever order a producer wrote its layers in.
    for (layer_id, name) in dict::LAYERS.iter().enumerate() {
        let Some(source) = tile.layer(name) else { continue };
        // One extent for the whole body: every layer of a Protomaps tile shares one, and carrying
        // a per-layer extent would mean a scale factor per draw for no benefit anyone has ever
        // needed.
        match extent {
            None => extent = Some(source.extent),
            Some(seen) if seen != source.extent => {
                return err(format!(
                    "an MVT tile mixes extents ({seen} and {}), which a .mamaps body cannot carry",
                    source.extent,
                ));
            }
            Some(_) => {}
        }
        let mut layer = Layer::new(layer_id as u8);
        for feature in &source.features {
            stats.features += 1;
            let kind = match feature.get("kind").and_then(as_str) {
                Some(name) => match kind_id(name) {
                    Some(id) => id,
                    None => {
                        stats.unknown_kinds += 1;
                        dict::NONE
                    }
                },
                None => dict::NONE,
            };
            let detail = feature
                .get("kind_detail")
                .and_then(as_str)
                .and_then(detail_id)
                .unwrap_or(dict::NONE);
            // The three booleans, which the upstream schema carries as properties and the MVT path
            // used to drop on the floor. A bridge drawn as plain tarmac laid over a river is what
            // this fixes.
            let mut flags = 0u8;
            if feature.get("is_tunnel").is_some_and(truthy) {
                flags |= FLAG_IS_TUNNEL;
            }
            if feature.get("is_bridge").is_some_and(truthy) {
                flags |= FLAG_IS_BRIDGE;
            }
            if feature.get("is_link").is_some_and(truthy) {
                flags |= FLAG_IS_LINK;
            }
            // A boundary's detail is an admin level, which upstream writes as a number rather than a
            // string. Carried as one, under the flag that says so.
            let (detail, flags) = match numeric(feature.get("kind_detail")) {
                Some(level) => (level, flags | FLAG_DETAIL_NUMERIC),
                None => (detail, flags),
            };
            match feature.geom_type {
                GeomType::Polygon => {
                    let Some(polygons) = mvt::decode_polygons(&feature.geometry) else { continue };
                    // One feature per polygon rather than one per multipolygon, so a feature's
                    // parts are exactly one exterior and its holes. That is what the tessellator
                    // wants, and it makes "one outer per feature" an invariant a test can state.
                    for rings in &polygons {
                        push_feature(&mut layer, kind, detail, flags, GEOM_POLYGON, rings, &mut stats);
                    }
                }
                GeomType::LineString => {
                    let Some(lines) = mvt::decode_lines(&feature.geometry) else { continue };
                    push_feature(&mut layer, kind, detail, flags, GEOM_LINE, &lines, &mut stats);
                }
                // The renderer decoded points and threw them away, so they are not carried.
                GeomType::Point => stats.points_dropped += 1,
                GeomType::Unknown => {}
            }
        }
        if !layer.features.is_empty() {
            layers.push(layer);
        }
    }

    // Anything the tile carries that the schema has no id for.
    stats.layers_skipped = tile
        .layers
        .iter()
        .filter(|source| !dict::LAYERS.contains(&source.name.as_str()))
        .count() as u64;

    let extent = extent.unwrap_or(DEFAULT_EXTENT as u32);
    let extent = u16::try_from(extent).map_err(|_| {
        crate::proto::Error(format!("an MVT extent of {extent} does not fit a .mamaps body"))
    })?;
    Ok((Body { extent, layers }, stats))
}

/// Append one feature and its parts, skipping degenerate paths.
///
/// A part of fewer than two points is not a path: it cannot stroke and it cannot bound an area, and
/// keeping it would mean every consumer checking. If nothing survives, no feature is written.
fn push_feature(
    layer: &mut Layer,
    kind: u16,
    kind_detail: u16,
    flags: u8,
    geom_type: u8,
    parts: &[Vec<(i32, i32)>],
    stats: &mut Stats,
) {
    let parts_offset = layer.parts.len() as u32;
    for (index, points) in parts.iter().enumerate() {
        if points.len() < 2 {
            continue;
        }
        let coord_start = layer.coords.len() as u32;
        for &(x, y) in points {
            layer.coords.push((clamp(x, stats), clamp(y, stats)));
        }
        layer.parts.push(Part {
            coord_start,
            point_count: points.len() as u32,
            // MVT's own convention, which the decoder preserves: a polygon's first ring is its
            // exterior and the rest are holes. Winding is stated here rather than recovered from
            // the signed area, which is unreliable on a near-degenerate clipped ring.
            winding: if geom_type == GEOM_POLYGON && index > 0 { WINDING_HOLE } else { WINDING_OUTER },
        });
    }
    let part_count = layer.parts.len() as u32 - parts_offset;
    if part_count == 0 {
        return;
    }
    layer.features.push(Feature { kind, kind_detail, geom_type, flags, parts_offset, part_count });
}

/// A property value as a small non-negative integer, for a boundary's admin level.
///
/// Upstream writes it as an `SInt`, not a string, which is what makes it the one attribute in the
/// schema that is a number rather than a name.
fn numeric(value: Option<&Value>) -> Option<u16> {
    match value? {
        Value::Int(v) | Value::SInt(v) => u16::try_from(*v).ok(),
        Value::Uint(v) => u16::try_from(*v).ok(),
        Value::Float(v) => u16::try_from(*v as i64).ok(),
        Value::Double(v) => u16::try_from(*v as i64).ok(),
        _ => None,
    }
}

/// Is a property one of the truthy spellings? Upstream writes `is_bridge` as a boolean.
fn truthy(value: &Value) -> bool {
    match value {
        Value::Bool(v) => *v,
        Value::String(s) => !matches!(s.as_str(), "" | "no" | "false" | "0"),
        Value::Int(v) | Value::SInt(v) => *v != 0,
        Value::Uint(v) => *v != 0,
        _ => true,
    }
}

/// An extent-unit coordinate as an `i16`, counting anything that would not fit.
fn clamp(v: i32, stats: &mut Stats) -> i16 {
    match i16::try_from(v) {
        Ok(v) => v,
        Err(_) => {
            stats.coords_clamped += 1;
            v.clamp(i16::MIN as i32, i16::MAX as i32) as i16
        }
    }
}

/// A `kind` name's id, or `None` when the schema has no counterpart.
pub fn kind_id(name: &str) -> Option<u16> {
    dict::KINDS.iter().position(|k| *k == name).map(|i| i as u16 + 1)
}

/// A `kind_detail` name's id, or `None`.
pub fn detail_id(name: &str) -> Option<u16> {
    dict::DETAILS.iter().position(|d| *d == name).map(|i| i as u16 + 1)
}

fn as_str(value: &Value) -> Option<&str> {
    match value {
        Value::String(s) => Some(s.as_str()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mvt::{Feature as MvtFeature, Layer as MvtLayer};

    fn line_feature(kind: &str, points: &[(i32, i32)]) -> MvtFeature {
        MvtFeature {
            id: None,
            geom_type: GeomType::LineString,
            geometry: mvt::encode_lines(&[points.to_vec()]),
            props: vec![("kind".to_string(), Value::String(kind.to_string()))],
        }
    }

    fn tile_with(layers: Vec<MvtLayer>) -> Tile {
        Tile { layers }
    }

    fn layer_of(name: &str, features: Vec<MvtFeature>) -> MvtLayer {
        MvtLayer { features, ..MvtLayer::new(name) }
    }

    fn roads_layer(features: Vec<MvtFeature>) -> MvtLayer {
        layer_of("roads", features)
    }

    #[test]
    fn a_line_feature_converts_with_its_kind_interned() {
        let tile = tile_with(vec![roads_layer(vec![line_feature(
            "highway",
            &[(0, 0), (100, 100), (200, 50)],
        )])]);
        let (body, stats) = from_tile(&tile).expect("convert");
        assert_eq!(body.extent, 4096);
        let layer = body.layer(dict::LAYER_ROADS).expect("roads");
        assert_eq!(layer.features.len(), 1);
        assert_eq!(layer.features[0].kind, kind_id("highway").expect("highway"));
        assert_eq!(layer.features[0].geom_type, GEOM_LINE);
        assert_eq!(layer.points(&layer.parts_of(&layer.features[0])[0]).len(), 3);
        assert_eq!(stats.features, 1);
        assert_eq!(stats.unknown_kinds, 0);
    }

    /// A `kind` the schema has no id for is drawn by the unfiltered fallback rather than dropped,
    /// which is what MapLibre does with it too. Counted so a conversion can report it.
    #[test]
    fn an_unknown_kind_becomes_none_and_is_counted() {
        let tile = tile_with(vec![roads_layer(vec![line_feature(
            "hyperloop",
            &[(0, 0), (10, 10)],
        )])]);
        let (body, stats) = from_tile(&tile).expect("convert");
        let layer = body.layer(dict::LAYER_ROADS).expect("roads");
        assert_eq!(layer.features[0].kind, dict::NONE);
        assert_eq!(stats.unknown_kinds, 1);
    }

    /// A source layer the schema does not carry is skipped whole and counted, not an error: the
    /// upstream archive has 20-odd layers and this format draws seven.
    #[test]
    fn a_layer_the_schema_does_not_carry_is_skipped_and_counted() {
        let tile = tile_with(vec![
            layer_of("places", vec![line_feature("locality", &[(0, 0), (1, 1)])]),
            roads_layer(vec![line_feature("highway", &[(0, 0), (1, 1)])]),
        ]);
        let (body, stats) = from_tile(&tile).expect("convert");
        assert_eq!(body.layers.len(), 1, "only roads");
        assert_eq!(stats.layers_skipped, 1);
    }

    #[test]
    fn a_point_feature_is_dropped_because_the_format_has_none() {
        let point = MvtFeature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(10, 20)]),
            props: vec![("kind".to_string(), Value::String("peak".to_string()))],
        };
        let tile = tile_with(vec![roads_layer(vec![point])]);
        let (body, stats) = from_tile(&tile).expect("convert");
        assert!(body.layers.is_empty(), "nothing survives");
        assert_eq!(stats.points_dropped, 1);
    }

    /// A part of fewer than two points is not a path: it cannot stroke and cannot bound an area, so
    /// dropping it here means no consumer has to check.
    #[test]
    fn a_degenerate_part_is_dropped_and_takes_its_feature_with_it_if_nothing_is_left() {
        let tile = tile_with(vec![roads_layer(vec![line_feature("highway", &[(5, 5)])])]);
        let (body, _) = from_tile(&tile).expect("convert");
        assert!(body.layers.is_empty());
    }

    /// Layers come out in the **schema's** order, so the same tile written with its layers in any
    /// order converts to the same body. Half of why a conversion is reproducible.
    #[test]
    fn output_order_is_the_schemas_not_the_tiles() {
        let roads = roads_layer(vec![line_feature("highway", &[(0, 0), (1, 1)])]);
        let water = layer_of("water", vec![line_feature("lake", &[(0, 0), (1, 1)])]);
        let forward = from_tile(&tile_with(vec![water.clone(), roads.clone()])).expect("a").0;
        let reverse = from_tile(&tile_with(vec![roads, water])).expect("b").0;
        assert_eq!(forward, reverse);
        assert_eq!(
            forward.layers.iter().map(|l| l.layer_id).collect::<Vec<_>>(),
            vec![dict::LAYER_WATER, dict::LAYER_ROADS],
            "water precedes roads, as the schema lists them",
        );
    }

    #[test]
    fn a_tile_mixing_extents_is_refused_rather_than_silently_rescaled() {
        let tile = tile_with(vec![
            roads_layer(vec![line_feature("highway", &[(0, 0), (1, 1)])]),
            MvtLayer {
                extent: 512,
                features: vec![line_feature("lake", &[(0, 0), (1, 1)])],
                ..MvtLayer::new("water")
            },
        ]);
        assert!(from_tile(&tile).is_err());
    }

    #[test]
    fn a_coordinate_too_large_for_an_i16_is_clamped_and_counted() {
        // Should never happen: a Protomaps tile is buffered by a few percent and an `i16` has
        // eight times the headroom. A count above zero means the input is not what it claims.
        let tile =
            tile_with(vec![roads_layer(vec![line_feature("highway", &[(0, 0), (99_999, 0)])])]);
        let (body, stats) = from_tile(&tile).expect("convert");
        assert_eq!(stats.coords_clamped, 1);
        let layer = body.layer(dict::LAYER_ROADS).expect("roads");
        assert_eq!(layer.coords[1].0, i16::MAX);
    }

    #[test]
    fn an_empty_tile_converts_to_an_empty_body() {
        let (body, stats) = from_tile(&Tile::new()).expect("convert");
        assert!(body.layers.is_empty());
        assert_eq!(stats, Stats::default());
    }

    /// The real published z0 tile, which is the one the format has to survive: it is the ocean
    /// polygon with 105 holes, and it is where every ring pathology in the archive lives.
    /// The three booleans and the numeric admin level, which the MVT path used to drop. A bridge
    /// drawn as plain tarmac laid over a river is what carrying them fixes.
    #[test]
    fn the_flags_and_a_numeric_admin_level_survive_the_conversion() {
        let mut road = line_feature("highway", &[(0, 0), (10, 10)]);
        road.props.push(("is_bridge".to_string(), Value::Bool(true)));
        road.props.push(("is_link".to_string(), Value::String("yes".to_string())));
        // Present but false: must not set the flag.
        road.props.push(("is_tunnel".to_string(), Value::Bool(false)));
        let mut border = line_feature("country", &[(0, 0), (10, 10)]);
        border.props.push(("kind_detail".to_string(), Value::SInt(2)));

        let tile = tile_with(vec![
            roads_layer(vec![road]),
            layer_of("boundaries", vec![border]),
        ]);
        let (body, _) = from_tile(&tile).expect("convert");

        let road = &body.layer(dict::LAYER_ROADS).expect("roads").features[0];
        assert!(road.is_bridge() && road.is_link() && !road.is_tunnel());
        assert_eq!(road.detail_number(), None, "a road's detail is a name");

        let border = &body.layer(dict::LAYER_BOUNDARIES).expect("boundaries").features[0];
        assert_eq!(border.detail_number(), Some(2), "a country border, as the style compares it");
    }

    #[test]
    fn the_published_z0_tile_converts_and_re_encodes() {
        let raw = include_bytes!("../../tests/fixtures/v4_z0_tile.mvt");
        let tile = Tile::decode(raw).expect("the fixture should decode");
        let (body, stats) = from_tile(&tile).expect("convert");
        assert!(stats.features > 0, "the z0 tile has features");
        assert_eq!(stats.coords_clamped, 0, "a buffered tile stays inside an i16");
        assert!(!body.layers.is_empty());
        // Round-trips through the container, which is the thing this bridge exists to feed.
        let bytes = crate::mamaps::body::serialize(&body).expect("serialize");
        assert_eq!(Body::parse(&bytes).expect("parse"), body);
        // **A size regression guard, not a claim to be smaller.** Measured on this tile: MVT
        // 93 228 raw / 67 722 deflated, `.mamaps` 111 007 / 81 691 — 1.19x and 1.21x. The format is
        // not a saving in bytes; what it wins is the decode, and z0 is its worst case because the
        // ocean polygon is 42 000 points against 54 features, so almost nothing is attributes.
        // Reverting the arena to flat `i16` pairs would put this at 192 692 (2.01x deflated), which
        // is what this bound exists to catch.
        assert!(
            bytes.len() < raw.len() * 3 / 2,
            "{} bytes of .mamaps against {} of MVT: the arena encoding has regressed",
            bytes.len(),
            raw.len(),
        );
    }
}
