//! `places`: labels — the one layer that is points with names.
//!
//! A place is a node (or exceptionally a way/relation centroid) tagged `place=*`, carrying a
//! coalesced display name (`name:en` preferred, falling back to `name`, as the reference style
//! does with its `coalesce(get(name:en), get(name), ...)` text fields) and a `kind` mirroring
//! the reference style's `places` filters: `country`, `region` (a named state/province),
//! `locality` (a city or town) and `neighbourhood`/`macrohood` (a subplace).
//!
//! # Why points
//!
//! Upstream's `places` is points: a label is anchored at one coordinate, not drawn from an area.
//! The tiler therefore emits one point feature per place, and the body's [`GEOM_POINT`] carries
//! it. A place mapped as a way or relation is labelled at its centroid (computed in extract),
//! which is what MapLibre does with area labels.
//!
//! [`GEOM_POINT`]: tilecodec::mamaps::body::GEOM_POINT
//!
//! # Rank and zoom
//!
//! The style draws a place when `zoom >= min_zoom`, where the important places surface early: a
//! country at z0, a region at z3, a city by population, a suburb late. `min_zoom` here mirrors
//! that: capitals and millions-strong cities at z2–z4, towns at z6–z8, suburbs at z10+. The
//! numeric `kind_detail` carries the population rank the style's symbol-sort uses, so a later
//! renderer can order labels without re-reading OSM.

use tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC;
use tilecodec::mamaps::dict::LAYER_PLACES;

use super::{kind, Class, TagSource};

pub const FILTERS: &[&str] = &["place", "name", "name:en", "population", "capital"];

/// Every `kind` this module can emit, mirroring the reference style's `places` filters.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &["country", "region", "locality", "neighbourhood", "macrohood"];

/// A country's label carries the world tile, like its border.
const Z_COUNTRY: u8 = 0;
/// A named state/province surfaces with the region borders.
const Z_REGION: u8 = 3;

/// Classify a `place=*` element. `name` is coalesced `name:en` → `name` by the caller (extract),
/// because the spill carries one string per feature; this function decides kind and zoom.
pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    let place = tags.get("place")?;
    let (kind_name, min_zoom) = match place {
        "country" => ("country", Z_COUNTRY),
        "state" | "province" => ("region", Z_REGION),
        "city" => ("locality", city_zoom(tags)),
        "town" => ("locality", 6),
        "village" | "hamlet" => ("locality", 9),
        "suburb" | "borough" => ("neighbourhood", 10),
        "neighbourhood" | "quarter" => ("neighbourhood", 11),
        // A macrohood is an upstream grouping above neighbourhoods; rare in OSM, drawn late.
        "macrohood" => ("macrohood", 10),
        _ => return None,
    };
    Some(Class {
        layer: LAYER_PLACES,
        kind: kind(kind_name),
        // The population rank the style sorts by, when known. Zero (unknown) sorts last;
        // carrying it numerically is what lets a later renderer order labels.
        kind_detail: rank_of(tags),
        flags: FLAG_DETAIL_NUMERIC,
        // A label is a point even when the place was mapped as an area: extract centroids it.
        area: false,
        min_zoom,
        min_area_px: 0.0,
    })
}

/// The display name for a place: `name:en` preferred, `name` as fallback.
///
/// Mirrors the reference style's `coalesce(get(name:en), get(name), ...)`. The `pgf:name`
/// fallback is upstream's own preprocessing and has no OSM source, so it is not read here.
pub fn display_name(tags: &(impl TagSource + ?Sized)) -> Option<String> {
    tags
        .get("name:en")
        .or_else(|| tags.get("name"))
        .map(str::trim)
        .filter(|name| !name.is_empty())
        .map(str::to_string)
}

/// A city's zoom from its weight: capitals and million-strong cities at z2–z4, the rest at z5.
///
/// Population is the signal when present (`population=*`); `capital=*` promotes one step,
/// because a capital of 200 k outranks a town of 500 k on every basemap.
fn city_zoom(tags: &(impl TagSource + ?Sized)) -> u8 {
    let population = population_of(tags);
    let capital = tags.get("capital") == Some("yes");
    let zoom: u8 = if population >= 5_000_000 {
        2
    } else if population >= 1_000_000 {
        3
    } else if population >= 100_000 {
        4
    } else {
        5
    };
    if capital { zoom.saturating_sub(1) } else { zoom }
}

/// A 0–255 population rank for `kind_detail`: millions at the top, unknown at zero.
///
/// Logarithmic-ish in three steps, because what matters is the order of magnitude: a city of
/// 8 M outranks one of 800 k, and both outrank an unpopulated hamlet nobody counted.
fn rank_of(tags: &(impl TagSource + ?Sized)) -> u16 {
    let population = population_of(tags);
    if population >= 5_000_000 {
        3
    } else if population >= 500_000 {
        2
    } else if population > 0 {
        1
    } else {
        0
    }
    .min(u16::MAX)
}

fn population_of(tags: &(impl TagSource + ?Sized)) -> u64 {
    tags
        .get("population")
        .map(str::trim)
        .and_then(|raw| raw.replace([' ', ','], "").parse::<u64>().ok())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    #[test]
    fn a_country_is_labelled_from_world_zoom() {
        let class = classify_tags(&[("place", "country"), ("name", "France")]).expect("country");
        assert_eq!(class.layer, dict::LAYER_PLACES);
        assert_eq!(dict::KINDS[class.kind as usize - 1], "country");
        assert_eq!(class.min_zoom, 0, "a country label carries a world tile");
        assert!(!class.area, "a label is a point even for an area-mapped place");
    }

    #[test]
    fn a_city_zoom_follows_its_weight() {
        let at = |tags: &[(&str, &str)]| classify_tags(tags).expect("city").min_zoom;
        assert_eq!(at(&[("place", "city"), ("population", "8000000")]), 2, "a megacity");
        assert_eq!(at(&[("place", "city"), ("population", "2000000")]), 3);
        assert_eq!(at(&[("place", "city")]), 5, "an uncounted city");
        assert_eq!(at(&[("place", "city"), ("capital", "yes")]), 4, "a capital promotes one step");
        assert_eq!(
            at(&[("place", "city"), ("population", "8000000"), ("capital", "yes")]),
            1,
            "but never past z1 for a city",
        );
    }

    #[test]
    fn towns_villages_and_suburbs_layer_by_size() {
        assert_eq!(classify_tags(&[("place", "town")]).expect("town").min_zoom, 6);
        assert_eq!(classify_tags(&[("place", "village")]).expect("village").min_zoom, 9);
        let suburb = classify_tags(&[("place", "suburb")]).expect("suburb");
        assert_eq!(dict::KINDS[suburb.kind as usize - 1], "neighbourhood");
        assert_eq!(suburb.min_zoom, 10);
    }

    #[test]
    fn the_name_prefers_english_and_falls_back() {
        let name_of = |pairs: &[(&str, &str)]| display_name(pairs);
        assert_eq!(
            name_of(&[("name:en", "Munich"), ("name", "München")]),
            Some("Munich".to_string()),
        );
        assert_eq!(name_of(&[("name", "München")]), Some("München".to_string()));
        assert_eq!(name_of(&[("place", "city")]), None, "unnamed");
        assert_eq!(name_of(&[("name", "  ")]), None, "blank");
    }

    #[test]
    fn only_named_kinds_of_place_count() {
        for tags in [
            vec![("place", "island")],
            vec![("place", "islet")],
            vec![("place", "sea")],
            vec![("amenity", "cafe")],
            vec![],
        ] {
            assert!(classify_tags(&tags).is_none(), "{tags:?} should not be a place label");
        }
        // Islands are `earth`, not `places`: the island label comes from the earth kind.
        assert!(classify_tags(&[("place", "islet")]).is_none());
    }

    #[test]
    fn the_rank_is_numeric_and_sorts_by_weight() {
        let rank = |tags: &[(&str, &str)]| classify_tags(tags).expect("place").kind_detail;
        assert!(rank(&[("place", "city"), ("population", "8000000")])
            > rank(&[("place", "city"), ("population", "100000")]));
        assert_eq!(rank(&[("place", "city")]), 0, "unknown sorts last");
        let class = classify_tags(&[("place", "city"), ("population", "8000000")]).expect("city");
        // Numeric, like boundaries' admin level: the renderer compares, not matches.
        assert_eq!(class.flags, FLAG_DETAIL_NUMERIC);
        assert_eq!(class.kind_detail, 3);
    }
}
