//! `roads`: six kinds, thirty details, and three booleans.
//!
//! **This is where roads finally become correct.** The MVT overlay this replaces carried a road's
//! class and nothing else, so a bridge drew as road-coloured tarmac laid over a river and a tunnel
//! drew as though it were on the surface. The three flags are the whole fix, and they cost three
//! bits.
//!
//! # kind versus kind_detail
//!
//! `kind` is the six-way class the style paints — one colour and one width ramp per class. It is
//! deliberately coarse: a map that gave `tertiary` its own colour would be unreadable.
//!
//! `kind_detail` is the OSM `highway` value itself, unreduced. Nothing in the style filters on more
//! than four of them today, and carrying the rest is what lets a future style draw a track
//! differently from a motorway link without a rebuild — and what lets the differential harness
//! compare this generator against upstream value for value rather than class for class.
//!
//! # Minimum zoom is the whole game
//!
//! A motorway belongs on a continent and a service road does not. Six thousand miles of residential
//! street at z8 is not detail, it is a grey wash — and it is also most of the bytes. The `min_zoom`
//! column below is the single most consequential table in this crate.

use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_TUNNEL};
use tilecodec::mamaps::dict::LAYER_ROADS;

use super::{detail, kind, Class, TagSource};

pub const FILTERS: &[&str] = &["highway", "railway", "aeroway", "route", "man_made"];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] =
    &["highway", "major_road", "minor_road", "path", "other", "rail", "ferry", "aerialway"];

/// Every `kind_detail` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const DETAILS: &[&str] = &[
    "motorway",
    "motorway_link",
    "trunk",
    "trunk_link",
    "primary",
    "primary_link",
    "secondary",
    "secondary_link",
    "tertiary",
    "tertiary_link",
    "residential",
    "unclassified",
    "living_street",
    "alley",
    "service",
    "track",
    "path",
    "footway",
    "sidewalk",
    "crossing",
    "steps",
    "corridor",
    "cycleway",
    "pedestrian",
    "rail",
    "subway",
    "tram",
    "light_rail",
    "turntable",
    "runway",
    "taxiway",
    "pier",
];

/// `(OSM highway value, kind, kind_detail, min_zoom)`, ordered by importance.
///
/// The `min_zoom` column is the accumulated judgement: a motorway carries a continent, a trunk road
/// a country, a residential street a neighbourhood. Wrong by two levels either way and a mid-zoom
/// tile is either empty or a grey wash.
const HIGHWAYS: &[(&str, &str, &str, u8)] = &[
    ("motorway", "highway", "motorway", 3),
    ("motorway_link", "highway", "motorway_link", 11),
    ("trunk", "major_road", "trunk", 5),
    ("trunk_link", "major_road", "trunk_link", 11),
    ("primary", "major_road", "primary", 7),
    ("primary_link", "major_road", "primary_link", 12),
    ("secondary", "major_road", "secondary", 9),
    ("secondary_link", "major_road", "secondary_link", 12),
    ("tertiary", "major_road", "tertiary", 10),
    ("tertiary_link", "major_road", "tertiary_link", 13),
    ("residential", "minor_road", "residential", 12),
    ("unclassified", "minor_road", "unclassified", 12),
    ("living_street", "minor_road", "living_street", 13),
    // A service road is every driveway and car-park aisle in the world. There are more of them
    // than of every other class combined, and they are street-level detail at best.
    ("service", "minor_road", "service", 14),
    ("road", "other", "unclassified", 13),
    ("track", "path", "track", 14),
    ("path", "path", "path", 14),
    ("footway", "path", "footway", 14),
    ("cycleway", "path", "cycleway", 14),
    ("bridleway", "path", "path", 14),
    ("steps", "path", "steps", 14),
    ("corridor", "path", "corridor", 15),
    ("pedestrian", "path", "pedestrian", 13),
];

/// `(OSM railway value, kind_detail, min_zoom)`.
///
/// One `kind` between them: the style draws every rail the same, and a map that coloured a tram
/// differently from a subway would be a transit diagram rather than a basemap.
const RAILWAYS: &[(&str, &str, u8)] = &[
    ("rail", "rail", 8),
    ("subway", "subway", 12),
    ("light_rail", "light_rail", 12),
    ("tram", "tram", 13),
    ("narrow_gauge", "rail", 11),
    ("monorail", "light_rail", 13),
    ("funicular", "light_rail", 13),
    ("turntable", "turntable", 15),
];

pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if let Some(highway) = tags.get("highway") {
        let (_, kind_name, detail_name, min_zoom) =
            HIGHWAYS.iter().find(|(value, _, _, _)| *value == highway)?;
        return Some(Class {
            layer: LAYER_ROADS,
            kind: kind(kind_name),
            kind_detail: detail(detail_name),
            flags: flags(tags, highway),
            area: false,
            min_zoom: *min_zoom,
        });
    }
    if let Some(railway) = tags.get("railway") {
        let (_, detail_name, min_zoom) = RAILWAYS.iter().find(|(value, _, _)| *value == railway)?;
        return Some(Class {
            layer: LAYER_ROADS,
            kind: kind("rail"),
            kind_detail: detail(detail_name),
            flags: flags(tags, railway),
            area: false,
            min_zoom: *min_zoom,
        });
    }
    // A runway is a line in this layer and a polygon in `landuse`. Both are drawn, because an
    // airport reads as a shape with a stripe down it.
    if let Some(aeroway) = tags.get("aeroway") {
        let detail_name = match aeroway {
            "runway" => "runway",
            "taxiway" => "taxiway",
            _ => return None,
        };
        return Some(Class {
            layer: LAYER_ROADS,
            kind: kind("other"),
            kind_detail: detail(detail_name),
            flags: flags(tags, aeroway),
            area: false,
            min_zoom: if aeroway == "runway" { 9 } else { 13 },
        });
    }
    // A pier is walkable, so it belongs with the paths rather than with the buildings.
    if tags.get("man_made") == Some("pier") {
        return Some(Class {
            layer: LAYER_ROADS,
            kind: kind("path"),
            kind_detail: detail("pier"),
            flags: flags(tags, "pier"),
            area: false,
            min_zoom: 13,
        });
    }
    // A ferry is a route, and drawing it is what stops a coastal map looking disconnected.
    if tags.get("route") == Some("ferry") {
        return Some(Class::line(LAYER_ROADS, kind("ferry"), 9));
    }
    None
}

/// The three booleans, and the reason this layer was worth redoing.
///
/// `is_link` is derived from the class name rather than read from a tag, because OSM spells a slip
/// road as `highway=motorway_link` and there is no `link=yes`. That matches what upstream emits and
/// what the style filters with `!has is_link`.
fn flags(tags: &(impl TagSource + ?Sized), value: &str) -> u8 {
    let mut flags = 0u8;
    // `tunnel=building_passage` is a tunnel; only `no` is not. Same for a bridge tagged `viaduct`
    // or `boardwalk`.
    if tags.truthy("tunnel") || tags.get("covered") == Some("yes") {
        flags |= FLAG_IS_TUNNEL;
    }
    if tags.truthy("bridge") {
        flags |= FLAG_IS_BRIDGE;
    }
    if value.ends_with("_link") {
        flags |= FLAG_IS_LINK;
    }
    flags
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    fn names(class: &Class) -> (&'static str, &'static str) {
        (
            dict::KINDS[class.kind as usize - 1],
            dict::DETAILS[class.kind_detail as usize - 1],
        )
    }

    #[test]
    fn a_road_carries_a_coarse_kind_and_its_exact_osm_class() {
        let motorway = classify_tags(&[("highway", "motorway")]).expect("motorway");
        assert_eq!(names(&motorway), ("highway", "motorway"));
        assert_eq!(motorway.layer, dict::LAYER_ROADS);
        assert!(!motorway.area, "a road is a line");

        // Four OSM classes collapse to one drawn colour, and each keeps its own detail.
        for (value, detail) in
            [("trunk", "trunk"), ("primary", "primary"), ("secondary", "secondary"), ("tertiary", "tertiary")]
        {
            let class = classify_tags(&[("highway", value)]).expect(value);
            assert_eq!(names(&class), ("major_road", detail));
        }
    }

    /// **The fix this layer was redone for.** Without these three bits a bridge is tarmac laid over
    /// a river and a tunnel is a road on the surface.
    #[test]
    fn a_bridge_a_tunnel_and_a_slip_road_are_all_flagged() {
        let bridge = classify_tags(&[("highway", "primary"), ("bridge", "yes")]).expect("bridge");
        assert_eq!(bridge.flags, FLAG_IS_BRIDGE);
        let tunnel = classify_tags(&[("highway", "primary"), ("tunnel", "yes")]).expect("tunnel");
        assert_eq!(tunnel.flags, FLAG_IS_TUNNEL);
        // A slip road is spelled in the class, not in a tag of its own.
        let link = classify_tags(&[("highway", "motorway_link")]).expect("link");
        assert_eq!(link.flags, FLAG_IS_LINK);
        assert_eq!(names(&link), ("highway", "motorway_link"));
        // And they combine: a flyover slip road is both.
        let both = classify_tags(&[("highway", "motorway_link"), ("bridge", "viaduct")])
            .expect("both");
        assert_eq!(both.flags, FLAG_IS_LINK | FLAG_IS_BRIDGE);
        // Only `no` is not a bridge. `bridge=viaduct` and `bridge=boardwalk` are.
        let flat = classify_tags(&[("highway", "primary"), ("bridge", "no")]).expect("flat");
        assert_eq!(flat.flags, 0);
    }

    #[test]
    fn a_covered_way_counts_as_a_tunnel() {
        // `covered=yes` is how an arcade or a building passage is tagged when it is not a tunnel
        // proper, and it draws the same way.
        let covered = classify_tags(&[("highway", "footway"), ("covered", "yes")]).expect("covered");
        assert_eq!(covered.flags, FLAG_IS_TUNNEL);
    }

    /// The most consequential column in this crate. A motorway carries a continent; a service road
    /// is every driveway in the world and there are more of them than of everything else combined.
    #[test]
    fn the_minimum_zooms_run_from_continent_to_street() {
        let at = |value: &str| classify_tags(&[("highway", value)]).expect(value).min_zoom;
        assert_eq!(at("motorway"), 3, "a motorway is a continental feature");
        assert!(at("trunk") < at("primary"));
        assert!(at("primary") < at("secondary"));
        assert!(at("secondary") < at("tertiary"));
        assert!(at("tertiary") < at("residential"));
        assert!(at("residential") < at("service"));
        assert_eq!(at("service"), 14, "a driveway is street-level at best");
        // A slip road is not carried shallower than the road it joins, which would draw a
        // disembodied stub.
        assert!(at("motorway_link") > at("motorway"));
        assert!(at("primary_link") > at("primary"));
    }

    #[test]
    fn every_railway_draws_as_one_kind_with_its_own_detail() {
        for (value, expected) in
            [("rail", "rail"), ("subway", "subway"), ("tram", "tram"), ("narrow_gauge", "rail")]
        {
            let class = classify_tags(&[("railway", value)]).expect(value);
            assert_eq!(names(&class), ("rail", expected));
        }
        // A rail line is a country-scale feature; a tram is not.
        let rail = classify_tags(&[("railway", "rail")]).expect("rail");
        let tram = classify_tags(&[("railway", "tram")]).expect("tram");
        assert!(rail.min_zoom < tram.min_zoom);
    }

    #[test]
    fn a_runway_a_pier_and_a_ferry_are_carried_in_this_layer() {
        let runway = classify_tags(&[("aeroway", "runway")]).expect("runway");
        assert_eq!(names(&runway), ("other", "runway"));
        let pier = classify_tags(&[("man_made", "pier")]).expect("pier");
        assert_eq!(names(&pier), ("path", "pier"), "a pier is walkable");
        let ferry = classify_tags(&[("route", "ferry")]).expect("ferry");
        assert_eq!(dict::KINDS[ferry.kind as usize - 1], "ferry");
    }

    /// `highway` is asked before `railway`, which matters at a level crossing: a way tagged both is
    /// the road, because that is what carries traffic.
    #[test]
    fn highway_is_asked_before_railway() {
        let crossing = classify_tags(&[("railway", "rail"), ("highway", "residential")])
            .expect("crossing");
        assert_eq!(names(&crossing).0, "minor_road");
    }

    #[test]
    fn an_unrecognised_value_is_not_a_road() {
        for tags in [
            vec![("highway", "bus_stop")],
            vec![("highway", "street_lamp")],
            vec![("railway", "abandoned")],
            vec![("aeroway", "gate")],
            vec![("man_made", "tower")],
            vec![("route", "bicycle")],
            vec![("building", "yes")],
            vec![],
        ] {
            assert!(classify_tags(&tags).is_none(), "{tags:?} should not be a road");
        }
    }
}
