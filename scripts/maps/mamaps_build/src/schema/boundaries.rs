//! `boundaries`: the one layer whose detail is a number.
//!
//! An administrative boundary's whole meaning is its **level** — 2 is a country, 4 a state, 6 a
//! county, 8 a city — and the style compares it with `<=` rather than matching a name: one layer for
//! country borders and another for everything below. So the level is carried as a plain integer in
//! the `kind_detail` field under [`FLAG_DETAIL_NUMERIC`], rather than interned. Interning it would
//! mean interning every integer, and comparing `<=` against an id that is not ordered like the value
//! would be worse than useless.
//!
//! A `kind` is carried too, for the differential harness and for a future style that wants a county
//! line dashed differently from a state line. It is derivable from the level, which is exactly why
//! the level is what the style reads.
//!
//! # A boundary is a line, and cannot be an area
//!
//! Carrying the region as an area was tried, so that tapping a city could dim everything outside
//! it, and it has to be reverted: **clipping a polygon to a tile adds segments along the tile
//! edge** to close the ring, and the style's boundary layers are `line`, which strokes a polygon's
//! outline. Those synthetic edges are then drawn, and the map is covered in a grid of tile borders.
//! Clipping a *line* merely truncates it, which is why the line form has never had this problem.
//!
//! There is no way to opt out from the style side either: the `boundaries` entry declares no
//! `kinds`, so it matches every feature in the layer and would stroke a polygon whatever kind it
//! carried.
//!
//! Whatever eventually feeds a region mask therefore needs somewhere the boundary style does not
//! reach — a kind the layer excludes, or a layer of its own — decided together with the mask
//! rather than guessed at here.

use tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC;
use tilecodec::mamaps::dict::LAYER_BOUNDARIES;

use super::{kind, Class, TagSource};

pub const FILTERS: &[&str] = &["boundary", "admin_level", "maritime"];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &["country", "region", "county", "locality", "region_area"];

/// The deepest administrative level worth drawing.
///
/// Below 8 is a ward or a neighbourhood: real data, and a line nobody has ever wanted on a basemap.
const MAX_LEVEL: u16 = 8;

/// The region's *shape*, for a relation that has one.
///
/// Emitted **in addition to** the border line from [`classify`], never instead of it: the line is
/// what draws the border, and this is only read by the region mask. Both are needed, and they
/// cannot be the same feature — see the module docs for the tile-edge grid that results from
/// trying.
///
/// `None` for a way, because a single way is one segment of a border and encloses nothing. A
/// relation whose rings will not close simply yields no area, and the border is unaffected.
pub fn region_area(tags: &(impl TagSource + ?Sized), is_way: bool) -> Option<Class> {
    if is_way {
        return None;
    }
    let line = classify(tags)?;
    Some(Class {
        layer: LAYER_BOUNDARIES,
        kind: kind("region_area"),
        // The same level the border carries, so the mask can tell a country from a city.
        kind_detail: line.kind_detail,
        flags: FLAG_DETAIL_NUMERIC,
        area: true,
        // One level shallower than the border line. A mask is drawn over a whole region, so it is
        // wanted at the zoom where the region *fits on screen* — which is about where its label
        // appears, not where its border becomes legible.
        min_zoom: line.min_zoom.saturating_sub(1),
        min_area_px: 0.0,
    })
}

pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if tags.get("boundary") != Some("administrative") {
        return None;
    }
    // A maritime boundary (an EEZ or territorial-water limit drawn across open sea) is
    // `boundary=administrative` over water, not inland/coastline admin, and the reference
    // style's boundary layers do not show it. Dropped here, in the tiler, so the layer
    // carries what the style draws rather than lines across the ocean.
    if tags.get("maritime") == Some("yes") {
        return None;
    }
    let level: u16 = tags.get("admin_level")?.trim().parse().ok()?;
    if level == 0 || level > MAX_LEVEL {
        return None;
    }
    Some(Class {
        layer: LAYER_BOUNDARIES,
        kind: kind(kind_for(level)),
        // The level itself, not an id. This is the only field in the format that is a number.
        kind_detail: level,
        flags: FLAG_DETAIL_NUMERIC,
        // A border is a line even when it closes. Filling it would paint over every layer inside
        // the country, and — the reason an attempt to make it an area had to be reverted — a
        // polygon clipped to a tile grows edges along the tile boundary, which the style's `line`
        // layers then stroke as a grid across the whole map. See the module docs.
        area: false,
        min_zoom: min_zoom_for(level),
        min_area_px: 0.0,
    })
}

/// The name for an administrative level, as upstream spells it.
fn kind_for(level: u16) -> &'static str {
    match level {
        0..=2 => "country",
        3..=4 => "region",
        5..=6 => "county",
        _ => "locality",
    }
}

/// How shallow a level is worth drawing.
///
/// A country border carries a world tile. A city limit at z4 is noise — and there are a hundred
/// thousand of them.
fn min_zoom_for(level: u16) -> u8 {
    match level {
        0..=2 => 0,
        3..=4 => 3,
        5..=6 => 6,
        _ => 9,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    /// **What the style actually reads.** `boundaries_country` filters `kind_detail <= 2`, so the
    /// level has to arrive as a number that compares correctly — not as an interned id whose
    /// ordering is an accident of table position.
    #[test]
    fn the_level_is_carried_as_a_number_not_an_id() {
        for level in 1..=8u16 {
            let class =
                classify_tags(&[("boundary", "administrative"), ("admin_level", &level.to_string())])
                    .expect("a level");
            assert_eq!(class.kind_detail, level, "the field holds the level itself");
            assert_eq!(class.flags, FLAG_DETAIL_NUMERIC, "and says so");
        }
        // The comparison the style makes.
        let country = classify_tags(&[("boundary", "administrative"), ("admin_level", "2")])
            .expect("country");
        let city = classify_tags(&[("boundary", "administrative"), ("admin_level", "8")])
            .expect("city");
        assert!(country.kind_detail <= 2, "drawn by boundaries_country");
        assert!(city.kind_detail > 2, "drawn by the other layer");
    }

    #[test]
    fn a_level_maps_to_the_name_upstream_uses() {
        let name = |level: &str| {
            let class = classify_tags(&[("boundary", "administrative"), ("admin_level", level)])
                .expect(level);
            dict::KINDS[class.kind as usize - 1]
        };
        assert_eq!(name("2"), "country");
        assert_eq!(name("4"), "region");
        assert_eq!(name("6"), "county");
        assert_eq!(name("8"), "locality");
    }

    /// A relation yields the border *and* the region's shape, as two features.
    ///
    /// They cannot be one. The border must stay a line, because clipping a polygon to a tile adds
    /// segments along the tile edge and the style's `boundaries` layer strokes polygon outlines —
    /// which drew a grid across the whole map when this was tried as a single area feature.
    #[test]
    fn a_relation_yields_a_border_line_and_a_region_shape() {
        let tags = [("boundary", "administrative"), ("admin_level", "8")];
        let line = classify_tags(&tags).expect("the border");
        assert!(!line.area, "the border is drawn, so it stays a line");

        let shape = super::region_area(&tags[..], false).expect("the region");
        assert!(shape.area, "the region is a shape, and nothing draws it");
        assert_eq!(shape.layer, dict::LAYER_BOUNDARIES);
        assert_eq!(
            dict::KINDS[shape.kind as usize - 1],
            "region_area",
            "a kind the boundaries layer's whitelist excludes, or it would be stroked",
        );
        assert_eq!(shape.kind_detail, line.kind_detail, "same level, so the mask can tell a city from a country");
        assert!(shape.min_zoom < line.min_zoom, "a mask is wanted where the region fits on screen");
    }

    /// A single way is one segment of a border and encloses nothing.
    #[test]
    fn a_way_has_no_region_shape() {
        let tags = [("boundary", "administrative"), ("admin_level", "2")];
        assert!(super::region_area(&tags[..], true).is_none());
        assert!(super::region_area(&tags[..], false).is_some());
    }

    /// A border is a line even when it closes. Filling it would paint over every layer inside the
    /// country — and a polygon clipped to a tile grows edges along the tile boundary, which the
    /// style's `line` layers stroke as a grid across the map. That is not theoretical: it was
    /// tried, and the tile grid was immediately visible.
    #[test]
    fn a_boundary_is_never_an_area() {
        let class = classify_tags(&[("boundary", "administrative"), ("admin_level", "2")])
            .expect("country");
        assert!(!class.area);
        assert_eq!(class.layer, dict::LAYER_BOUNDARIES);
    }

    #[test]
    fn a_country_border_is_carried_at_world_zoom_and_a_city_limit_is_not() {
        let at = |level: &str| {
            classify_tags(&[("boundary", "administrative"), ("admin_level", level)])
                .expect(level)
                .min_zoom
        };
        assert_eq!(at("2"), 0, "a country border carries a world tile");
        assert!(at("2") < at("4"));
        assert!(at("4") < at("6"));
        assert!(at("6") < at("8"));
        assert_eq!(at("8"), 9, "there are a hundred thousand city limits");
    }

    #[test]
    fn a_level_below_a_city_is_not_drawn() {
        // Level 9 and 10 are wards and neighbourhoods: real data, and a line nobody wants.
        for level in ["9", "10", "11"] {
            assert!(
                classify_tags(&[("boundary", "administrative"), ("admin_level", level)]).is_none(),
                "level {level} should not be drawn",
            );
        }
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", "0")]).is_none(),
            "level 0 is not a level",
        );
    }

    #[test]
    fn a_maritime_administrative_boundary_is_not_drawn() {
        // An EEZ / territorial-water limit: `boundary=administrative` over water, tagged
        // `maritime=yes`. The reference style's boundary layers show inland/coastline admin
        // only, so the tiler drops these rather than drawing lines across open sea.
        assert!(
            classify_tags(&[
                ("boundary", "administrative"),
                ("admin_level", "2"),
                ("maritime", "yes"),
            ])
            .is_none(),
            "a maritime boundary should not be a boundary",
        );
        // And the inland equivalent still classifies.
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", "2")]).is_some(),
            "an inland country border still counts",
        );
    }

    #[test]
    fn only_an_administrative_boundary_with_a_readable_level_counts() {
        for tags in [
            // A protected area or a maritime boundary is a boundary and not an administrative one.
            vec![("boundary", "protected_area"), ("admin_level", "2")],
            vec![("boundary", "maritime"), ("admin_level", "2")],
            // Administrative but with no level, or an unreadable one.
            vec![("boundary", "administrative")],
            vec![("boundary", "administrative"), ("admin_level", "")],
            vec![("boundary", "administrative"), ("admin_level", "two")],
            vec![("boundary", "administrative"), ("admin_level", "4;6")],
            vec![("admin_level", "2")],
            vec![],
        ] {
            assert!(classify_tags(&tags).is_none(), "{tags:?} should not be a boundary");
        }
        // Whitespace around a level is common in real data and is not a reason to drop a border.
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", " 4 ")]).is_some(),
            "a padded level still parses",
        );
    }
}
