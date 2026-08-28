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

use tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC;
use tilecodec::mamaps::dict::LAYER_BOUNDARIES;

use super::{kind, Class, TagSource};

pub const FILTERS: &[&str] = &["boundary", "admin_level"];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &["country", "region", "county", "locality"];

/// The deepest administrative level worth drawing.
///
/// Below 8 is a ward or a neighbourhood: real data, and a line nobody has ever wanted on a basemap.
const MAX_LEVEL: u16 = 8;

pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if tags.get("boundary") != Some("administrative") {
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
        // A border is a line even when it closes: the style strokes it, and filling it would paint
        // over every layer inside the country.
        area: false,
        min_zoom: min_zoom_for(level),
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

    /// A border is a line even when it closes. Filling it would paint over every layer inside the
    /// country.
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
