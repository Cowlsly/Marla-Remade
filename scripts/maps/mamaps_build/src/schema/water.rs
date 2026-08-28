//! `water`: the layer a map is mostly made of.
//!
//! Nine kinds, and the classification is nearly all `natural` and `waterway`. The judgement that
//! matters is not *which* kind — that part is a lookup — but the **minimum zoom**: an ocean belongs
//! on a world tile and a drainage ditch does not, and getting that wrong is the difference between
//! a readable coastline and a z4 tile with forty thousand ponds in it.
//!
//! # `ocean` is not here
//!
//! Upstream's `water/ocean` comes from the same stitched coastline that `earth` does, not from a
//! tag: there is no `natural=ocean` way in OSM. So it arrives with the coastline work, and until
//! then the sea is the renderer's background colour — which is what that colour is for, and why it
//! is the water colour rather than the land one.

use tilecodec::mamaps::dict::LAYER_WATER;

use super::{kind, Class, TagSource};

/// The pre-screen. A superset of what the rules below accept.
pub const FILTERS: &[&str] = &["natural", "waterway", "landuse", "water"];

/// Every `kind` this module can emit, for the dictionary-closure test.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] =
    &["bay", "fjord", "lake", "river", "sea", "strait", "stream", "water", "canal", "dock", "reef"];

/// Classify a water feature.
///
/// First match wins. `is_way` decides whether a closed way is a ring or a line, which for water is
/// almost always a ring — a `waterway=river` is the exception, and it is a line even when it closes
/// around an island.
pub fn classify(tags: &(impl TagSource + ?Sized), is_way: bool) -> Option<Class> {
    // `natural=*`, the bulk of it. Areas, every one.
    if let Some(natural) = tags.get("natural") {
        let class = match natural {
            // A sea and a bay carry a whole world tile; a strait or a fjord is a coastal
            // feature that only reads once the coast is on screen.
            "sea" => Some(Class::area(LAYER_WATER, kind("sea"), 0)),
            "bay" => Some(Class::area(LAYER_WATER, kind("bay"), 6)),
            "strait" => Some(Class::area(LAYER_WATER, kind("strait"), 6)),
            "fjord" => Some(Class::area(LAYER_WATER, kind("fjord"), 6)),
            "reef" => Some(Class::area(LAYER_WATER, kind("reef"), 10)),
            // The generic one, and by far the most common. A lake if `water` says so.
            "water" => Some(Class::area(LAYER_WATER, water_kind(tags), water_min_zoom(tags))),
            _ => None,
        };
        if class.is_some() {
            return class;
        }
    }

    // `waterway=*`. Rivers and canals are lines that widen into areas when tagged
    // `area=yes`; a stream is a line and stays one.
    if let Some(waterway) = tags.get("waterway") {
        let area = tags.get("area") == Some("yes") || !is_way;
        let (name, min_zoom) = match waterway {
            // A river is the only waterway a continent-scale map draws.
            "river" => ("river", 8),
            "canal" => ("canal", 10),
            "stream" => ("stream", 12),
            // A ditch or a drain is street-level detail at best, and there are millions.
            "ditch" | "drain" => ("stream", 14),
            "dock" => ("dock", 12),
            _ => return None,
        };
        let id = kind(name);
        return Some(if area {
            Class::area(LAYER_WATER, id, min_zoom)
        } else {
            Class::line(LAYER_WATER, id, min_zoom)
        });
    }

    // A reservoir or a basin is tagged on `landuse`, not `natural`.
    if let Some(landuse) = tags.get("landuse") {
        if matches!(landuse, "reservoir" | "basin") {
            return Some(Class::area(LAYER_WATER, kind("water"), 8));
        }
    }
    None
}

/// Which kind a `natural=water` polygon is, from its `water` tag.
///
/// Upstream distinguishes a lake from the generic `water`, and the style gives them the same colour
/// today — but the distinction is in the schema, so it is carried rather than flattened. A restyle
/// that wants lakes bluer than reservoirs then needs no rebuild.
fn water_kind(tags: &(impl TagSource + ?Sized)) -> u16 {
    match tags.get("water") {
        Some("lake") => kind("lake"),
        Some("river" | "canal") => kind("river"),
        Some("lagoon" | "oxbow" | "pond" | "reservoir" | "basin") => kind("water"),
        _ => kind("water"),
    }
}

/// How shallow a `natural=water` polygon is worth carrying.
///
/// A named lake is a landmark and an unnamed pond is not, and a name is the only signal in the tags
/// that separates them. This is the one place the classifier reads `name` at all — for a *decision*,
/// not to carry it.
fn water_min_zoom(tags: &(impl TagSource + ?Sized)) -> u8 {
    if tags.has("name") {
        6
    } else {
        10
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    /// Classify an element carrying exactly these tags.
    fn classify_tags(pairs: &[(&str, &str)], is_way: bool) -> Option<Class> {
        super::classify(pairs, is_way)
    }

    fn kind_name(class: &Class) -> &'static str {
        dict::KINDS[class.kind as usize - 1]
    }

    #[test]
    fn the_natural_tags_classify_as_areas() {
        for (value, expected, min_zoom) in [
            ("sea", "sea", 0u8),
            ("bay", "bay", 6),
            ("strait", "strait", 6),
            ("fjord", "fjord", 6),
            ("reef", "reef", 10),
        ] {
            let class = classify_tags(&[("natural", value)], true).expect(value);
            assert_eq!(kind_name(&class), expected);
            assert_eq!(class.layer, dict::LAYER_WATER);
            assert!(class.area, "{value} is an area");
            assert_eq!(class.min_zoom, min_zoom);
        }
    }

    /// The judgement that matters: a named lake is a landmark from z6, an unnamed pond is street
    /// detail. Without this a z6 tile carries every farm pond in the state.
    #[test]
    fn a_named_water_body_is_carried_far_shallower_than_an_unnamed_one() {
        let named = classify_tags(&[("natural", "water"), ("name", "Lake Tahoe")], true).expect("named");
        let pond = classify_tags(&[("natural", "water")], true).expect("unnamed");
        assert_eq!(named.min_zoom, 6);
        assert_eq!(pond.min_zoom, 10);
        assert!(named.min_zoom < pond.min_zoom);
    }

    #[test]
    fn the_water_tag_picks_the_kind() {
        let lake = classify_tags(&[("natural", "water"), ("water", "lake")], true).expect("lake");
        assert_eq!(kind_name(&lake), "lake");
        let river = classify_tags(&[("natural", "water"), ("water", "river")], true).expect("river");
        assert_eq!(kind_name(&river), "river");
        // Anything else, including an absent tag, is the generic kind rather than dropped.
        let pond = classify_tags(&[("natural", "water"), ("water", "pond")], true).expect("pond");
        assert_eq!(kind_name(&pond), "water");
        let bare = classify_tags(&[("natural", "water")], true).expect("bare");
        assert_eq!(kind_name(&bare), "water");
    }

    /// A river is a line that becomes an area when tagged one, which is how OSM models a wide
    /// river: a centreline way plus a riverbank polygon.
    #[test]
    fn a_waterway_is_a_line_unless_it_says_it_is_an_area() {
        let river = classify_tags(&[("waterway", "river")], true).expect("river");
        assert!(!river.area, "a river centreline is a line");
        assert_eq!(river.min_zoom, 8);
        let bank = classify_tags(&[("waterway", "river"), ("area", "yes")], true).expect("bank");
        assert!(bank.area);
        // A relation is always an area, whatever the tags say: a multipolygon is a multipolygon.
        let relation = classify_tags(&[("waterway", "river")], false).expect("relation");
        assert!(relation.area);
    }

    /// There are millions of ditches and drains. Carrying them above street level is what turns a
    /// mid-zoom tile into a mesh of blue hair.
    #[test]
    fn a_ditch_is_held_back_to_street_zoom_and_a_river_is_not() {
        let ditch = classify_tags(&[("waterway", "ditch")], true).expect("ditch");
        let stream = classify_tags(&[("waterway", "stream")], true).expect("stream");
        let river = classify_tags(&[("waterway", "river")], true).expect("river");
        assert_eq!(ditch.min_zoom, 14);
        assert_eq!(stream.min_zoom, 12);
        assert_eq!(river.min_zoom, 8);
        // A ditch is drawn as a stream: the style has no ditch colour and inventing one would be
        // paint, not data.
        assert_eq!(kind_name(&ditch), "stream");
    }

    #[test]
    fn a_reservoir_is_water_even_though_it_is_tagged_landuse() {
        let reservoir = classify_tags(&[("landuse", "reservoir")], true).expect("reservoir");
        assert_eq!(reservoir.layer, dict::LAYER_WATER);
        assert_eq!(kind_name(&reservoir), "water");
        assert!(classify_tags(&[("landuse", "residential")], true).is_none(), "not water");
    }

    /// First match wins, and `natural` is asked first. A polygon tagged both ways is water, because
    /// a lake with a boathouse on its edge is still a lake.
    #[test]
    fn natural_is_asked_before_waterway() {
        let both = classify_tags(&[("waterway", "stream"), ("natural", "water")], true).expect("both");
        assert_eq!(kind_name(&both), "water", "the natural rule won");
    }

    #[test]
    fn everything_else_is_not_water() {
        for tags in [
            vec![("building", "yes")],
            vec![("highway", "residential")],
            vec![("natural", "wood")],
            vec![("waterway", "weir")],
            vec![("name", "Nowhere")],
            vec![],
        ] {
            assert!(classify_tags(&tags, true).is_none(), "{tags:?} should not be water");
        }
    }
}
