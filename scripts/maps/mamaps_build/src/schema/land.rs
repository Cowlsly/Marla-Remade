//! `landcover` and `landuse`: the long tail, and where a minimum **area** starts to matter.
//!
//! Two layers with one classifier, because they read the same tags and differ only in what they
//! mean: `landcover` is the natural surface of the world and is drawn at low zoom as a tint over
//! `earth`; `landuse` is what people do with a patch of it and is drawn at high zoom as a shape.
//! Upstream separates them by zoom — the style ramps `landcover` out by z7 and `landuse` in from z6
//! — so the same forest is a green wash on a continent and a named wood in a city.
//!
//! # Minimum area, not just minimum zoom
//!
//! This is the layer that needs both. A `min_zoom` alone cannot separate a national park from a
//! back garden: both are `leisure=park`, and there are four of the first and four million of the
//! second. So a polygon also has to be **big enough to see** — at least a few hundred square pixels
//! of the tile it would be drawn in — and below that it is not carried at all.
//!
//! # Where this will not match upstream
//!
//! Nowhere near exactly, and the plan says so: Planetiler's profile is thousands of lines of
//! accumulated judgement about which of forty `landuse` values is worth a tile at which zoom.
//! `scripts/maps/test/diff_kinds.py` measures the gap per kind. The bar is **visually equivalent**,
//! not count-identical.

use tilecodec::mamaps::dict::{LAYER_LANDCOVER, LAYER_LANDUSE};

use super::{kind, Class, TagSource};

pub const FILTERS: &[&str] =
    &["natural", "landuse", "leisure", "amenity", "tourism", "aeroway", "man_made", "boundary"];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &[
    // landcover
    "grassland",
    "barren",
    "urban_area",
    "farmland",
    "glacier",
    "scrub",
    "forest",
    "wetland",
    "sand",
    "bare_rock",
    // landuse
    "national_park",
    "park",
    "cemetery",
    "protected_area",
    "nature_reserve",
    "golf_course",
    "wood",
    "grass",
    "meadow",
    "military",
    "naval_base",
    "airfield",
    "allotments",
    "village_green",
    "playground",
    "garden",
    "dog_park",
    "pitch",
    "recreation_ground",
    "hospital",
    "industrial",
    "commercial",
    "residential",
    "railway",
    "school",
    "university",
    "college",
    "kindergarten",
    "beach",
    "zoo",
    "aerodrome",
    "runway",
    "taxiway",
    "pedestrian",
    "dam",
    "pier",
    "platform",
];

/// `natural=*`, which is `landcover`: the surface of the world rather than what is done with it.
///
/// `(OSM value, kind, min_zoom, min_area_px)`. The areas are in square pixels of a 256-unit tile,
/// which is how a threshold stays meaningful across zooms — see [`min_area_for`].
const LANDCOVER: &[(&str, &str, u8, f64)] = &[
    // A glacier or an ice sheet is a continental feature and there are few of them.
    ("glacier", "glacier", 2, 4.0),
    ("wood", "forest", 5, 8.0),
    ("scrub", "scrub", 6, 8.0),
    ("grassland", "grassland", 6, 8.0),
    ("heath", "scrub", 7, 8.0),
    ("wetland", "wetland", 7, 8.0),
    ("sand", "sand", 8, 4.0),
    ("beach", "sand", 10, 2.0),
    ("bare_rock", "bare_rock", 8, 8.0),
    ("scree", "bare_rock", 9, 8.0),
    ("shingle", "bare_rock", 10, 4.0),
    ("desert", "barren", 3, 8.0),
];

/// `landuse=*`, `leisure=*` and the rest, which is `landuse`.
///
/// `(key, OSM value, kind, min_zoom, min_area_px)`. Ordered, and the order is the tie-break for a
/// polygon carrying two of them.
const LANDUSE: &[(&str, &str, &str, u8, f64)] = &[
    // The big protected areas, which carry a continent.
    ("boundary", "national_park", "national_park", 4, 2.0),
    ("boundary", "protected_area", "protected_area", 5, 2.0),
    ("leisure", "nature_reserve", "nature_reserve", 6, 2.0),
    ("landuse", "forest", "forest", 6, 4.0),
    ("landuse", "military", "military", 6, 4.0),
    ("military", "naval_base", "naval_base", 8, 4.0),
    ("aeroway", "aerodrome", "aerodrome", 8, 2.0),
    ("landuse", "farmland", "farmland", 7, 8.0),
    ("landuse", "meadow", "meadow", 8, 8.0),
    ("landuse", "grass", "grass", 10, 4.0),
    ("landuse", "residential", "residential", 10, 8.0),
    ("landuse", "commercial", "commercial", 11, 4.0),
    ("landuse", "industrial", "industrial", 10, 4.0),
    ("landuse", "railway", "railway", 12, 4.0),
    ("landuse", "cemetery", "cemetery", 11, 2.0),
    ("landuse", "allotments", "allotments", 12, 2.0),
    ("landuse", "village_green", "village_green", 12, 2.0),
    ("landuse", "recreation_ground", "recreation_ground", 12, 2.0),
    ("leisure", "park", "park", 8, 2.0),
    ("leisure", "golf_course", "golf_course", 10, 2.0),
    ("leisure", "garden", "garden", 13, 1.0),
    ("leisure", "dog_park", "dog_park", 14, 1.0),
    ("leisure", "playground", "playground", 14, 1.0),
    ("leisure", "pitch", "pitch", 14, 1.0),
    ("amenity", "hospital", "hospital", 12, 2.0),
    ("amenity", "school", "school", 13, 1.0),
    ("amenity", "university", "university", 11, 2.0),
    ("amenity", "college", "college", 12, 2.0),
    ("amenity", "kindergarten", "kindergarten", 14, 1.0),
    ("tourism", "zoo", "zoo", 12, 2.0),
    ("aeroway", "runway", "runway", 11, 1.0),
    ("aeroway", "taxiway", "taxiway", 13, 1.0),
    ("highway", "pedestrian", "pedestrian", 13, 1.0),
    ("man_made", "pier", "pier", 13, 1.0),
    ("waterway", "dam", "dam", 12, 1.0),
    ("public_transport", "platform", "platform", 14, 1.0),
];

/// Classify a landcover or landuse polygon.
///
/// Only areas: a `landuse` line is a tagging mistake, and a `natural=tree_row` is not a surface.
pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if let Some(natural) = tags.get("natural") {
        if let Some((_, name, min_zoom, area)) =
            LANDCOVER.iter().find(|(value, _, _, _)| *value == natural)
        {
            return Some(with_area(LAYER_LANDCOVER, kind(name), *min_zoom, *area));
        }
    }
    for (key, value, name, min_zoom, area) in LANDUSE {
        if tags.get(key) == Some(value) {
            return Some(with_area(LAYER_LANDUSE, kind(name), *min_zoom, *area));
        }
    }
    None
}

/// A polygon with a minimum drawn area.
///
/// The area is smuggled through the `Class` in the only place it fits — see [`Class::min_area_px`].
fn with_area(layer: u8, kind: u16, min_zoom: u8, min_area_px: f64) -> Class {
    Class { min_area_px, ..Class::area(layer, kind, min_zoom) }
}

/// The minimum area a polygon needs at `extent`, in that tile's own square units.
///
/// Stated in square pixels of a 256-unit tile because that is the only scale that means anything: a
/// shape smaller than a couple of pixels is not detail, it is a speck, and there are millions of
/// them. Converted to the tile's extent here so the threshold is the same *visual* size whatever the
/// extent is.
pub fn min_area_units(min_area_px: f64, extent: u32) -> f64 {
    let scale = extent as f64 / 256.0;
    min_area_px * scale * scale
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    fn name(class: &Class) -> &'static str {
        dict::KINDS[class.kind as usize - 1]
    }

    #[test]
    fn natural_surfaces_go_to_landcover_and_human_use_to_landuse() {
        let wood = classify_tags(&[("natural", "wood")]).expect("wood");
        assert_eq!(wood.layer, dict::LAYER_LANDCOVER);
        assert_eq!(name(&wood), "forest");

        let park = classify_tags(&[("leisure", "park")]).expect("park");
        assert_eq!(park.layer, dict::LAYER_LANDUSE);
        assert_eq!(name(&park), "park");
        // Both are areas; neither is ever a line.
        assert!(wood.area && park.area);
    }

    /// The layer's whole difficulty: a national park and a back garden are both green polygons, and
    /// there are four of the first and four million of the second.
    #[test]
    fn the_minimum_zooms_separate_a_national_park_from_a_back_garden() {
        let at = |pairs: &[(&str, &str)]| classify_tags(pairs).expect("classified").min_zoom;
        assert_eq!(at(&[("boundary", "national_park")]), 4);
        assert!(at(&[("boundary", "national_park")]) < at(&[("leisure", "park")]));
        assert!(at(&[("leisure", "park")]) < at(&[("leisure", "garden")]));
        assert_eq!(at(&[("leisure", "garden")]), 13);
        assert_eq!(at(&[("leisure", "pitch")]), 14, "a tennis court is street-level");
        // A glacier carries a world tile.
        assert_eq!(at(&[("natural", "glacier")]), 2);
    }

    /// A minimum **zoom** cannot separate two things tagged identically; a minimum **area** can.
    /// Stated in square pixels of a 256-unit tile, so the threshold is the same visual size at any
    /// extent.
    #[test]
    fn a_minimum_area_is_a_visual_size_not_a_coordinate_count() {
        // 2 px of a 256-unit tile, expressed in a 4096-unit one, is 2 * 16 * 16.
        assert_eq!(min_area_units(2.0, 256), 2.0);
        assert_eq!(min_area_units(2.0, 4096), 512.0);
        assert_eq!(min_area_units(1.0, 4096), 256.0);
        // Every classified polygon carries one.
        for pairs in [
            vec![("natural", "wood")],
            vec![("leisure", "park")],
            vec![("landuse", "residential")],
        ] {
            assert!(classify_tags(&pairs).expect("classified").min_area_px > 0.0);
        }
    }

    /// Several OSM values collapse to one drawn kind, because the style has one colour for them and
    /// inventing more would be paint rather than data.
    #[test]
    fn several_osm_values_can_share_a_drawn_kind() {
        for value in ["bare_rock", "scree", "shingle"] {
            assert_eq!(name(&classify_tags(&[("natural", value)]).expect(value)), "bare_rock");
        }
        for value in ["scrub", "heath"] {
            assert_eq!(name(&classify_tags(&[("natural", value)]).expect(value)), "scrub");
        }
        for value in ["sand", "beach"] {
            assert_eq!(name(&classify_tags(&[("natural", value)]).expect(value)), "sand");
        }
    }

    /// `natural` is asked before everything, and the `landuse` list is ordered, so a polygon
    /// carrying two tags gets the more significant one.
    #[test]
    fn the_rules_are_ordered() {
        // A forest inside a national park is the national park, which is the bigger statement.
        let both = classify_tags(&[("landuse", "forest"), ("boundary", "national_park")])
            .expect("both");
        assert_eq!(name(&both), "national_park");
        // And a natural surface beats a use, because landcover draws under landuse.
        let surface = classify_tags(&[("natural", "wood"), ("leisure", "park")]).expect("surface");
        assert_eq!(surface.layer, dict::LAYER_LANDCOVER);
    }

    #[test]
    fn nothing_untagged_or_unrecognised_is_carried() {
        for pairs in [
            vec![("natural", "tree_row")],
            vec![("natural", "water")],
            vec![("landuse", "quarry")],
            vec![("leisure", "swimming_pool")],
            vec![("amenity", "parking")],
            vec![("building", "yes")],
            vec![],
        ] {
            assert!(classify_tags(&pairs).is_none(), "{pairs:?} should not be carried");
        }
    }
}
