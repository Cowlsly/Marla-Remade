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

use tilecodec::mamaps::body::{
    Carriageway, FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_ONEWAY, FLAG_IS_TUNNEL,
};
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
            min_area_px: 0.0,
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
            min_area_px: 0.0,
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
            min_area_px: 0.0,
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
            min_area_px: 0.0,
        });
    }
    // A ferry is a route, and drawing it is what stops a coastal map looking disconnected.
    if tags.get("route") == Some("ferry") {
        return Some(Class::line(LAYER_ROADS, kind("ferry"), 9));
    }
    None
}

/// The four booleans, and the reason this layer was worth redoing.
///
/// `is_link` is derived from the class name rather than read from a tag, because OSM spells a slip
/// road as `highway=motorway_link` and there is no `link=yes`. That matches what upstream emits and
/// what the style filters with `!has is_link`.
///
/// `is_oneway` is a flag rather than side-table data because it is what decides whether a
/// carriageway has a centre line at all, and because a flag is part of `coalesce`'s merge key —
/// so a one-way and a two-way of the same class cannot collapse into one feature wearing
/// whichever direction came first.
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
    // `oneway=yes` and only that, which is the test [`osm_ingest::roads::is_oneway`] applies, so a
    // road cannot be one-way to the router and two-way to the carriageway drawn under it.
    // `oneway=-1` is a direction rather than a flag and neither models it.
    if tags.get("oneway") == Some("yes") {
        flags |= FLAG_IS_ONEWAY;
    }
    flags
}

/// The carriageway lane count baked into a `roads` feature, or zero when the way carries no
/// `lanes` tag.
///
/// The OSM `lanes` total (both directions), parsed by the same [`osm_ingest::tags::parse_int_tag`]
/// the routing graph and the `roads.pmtiles` layer use, so a road cannot describe its lane count
/// one way to the router and another to the basemap. Capped at [`osm_ingest::tags::MAX_LANES`]
/// (64), which fits a byte — the renderer stores it in the feature record's last byte and expands
/// it into that many parallel lanes with dividers at high zoom. A mistagged `lanes=999999999`
/// therefore becomes the cap rather than an absurd fan.
///
/// Only the total is baked, not the per-lane `turn:lanes` masks: those are variable length and
/// belong in a side table (the turn-arrow pass), while the count is one byte the parallel-lane
/// geometry needs first.
pub fn lane_count(tags: &(impl TagSource + ?Sized)) -> u8 {
    osm_ingest::tags::parse_int_tag(tags.get("lanes")).min(osm_ingest::tags::MAX_LANES) as u8
}

/// The per-lane turn-indication masks for a road, `(forward, backward)`, each a left-to-right
/// list of `LANE_*` bit sets from OSM `turn:lanes[:forward|:backward]`.
///
/// A verbatim reuse of the routing graph's own derivation
/// ([`osm_ingest::roads::lane_masks`]) via a [`RoadTags`](osm_ingest::roads::RoadTags) built from
/// this tag source, so a road cannot describe its lanes one way to the router and another to the
/// arrows drawn over it. Empty vectors when the way has no `turn:lanes`, which is almost every
/// road. Forward lanes are traversed toward the way's end (its junction), backward toward its
/// start — which is where the renderer places each direction's arrows.
pub fn turn_masks(tags: &(impl TagSource + ?Sized)) -> (Vec<u16>, Vec<u16>) {
    let rt = osm_ingest::roads::RoadTags {
        highway: tags.get("highway"),
        lanes: tags.get("lanes"),
        lanes_forward: tags.get("lanes:forward"),
        lanes_backward: tags.get("lanes:backward"),
        turn_lanes: tags.get("turn:lanes"),
        turn_lanes_forward: tags.get("turn:lanes:forward"),
        turn_lanes_backward: tags.get("turn:lanes:backward"),
        oneway: tags.get("oneway"),
        ..Default::default()
    };
    osm_ingest::roads::lane_masks(&rt)
}

/// How a road's lanes divide between the two directions, and which of the dividers between them
/// may not be crossed.
///
/// [`lane_count`] is the total; this is what the surface renderer needs on top of it to put the
/// centre line anywhere but the middle. `forward` runs toward the way's last point and `backward`
/// toward its first, the same convention [`turn_masks`] uses, and `oneway` is decided by
/// [`osm_ingest::roads::is_oneway`] so the split agrees with how the router traverses the road.
///
/// **An absent split stays absent.** A two-way road tagged only `lanes=4` comes back all-zero
/// rather than as 2/2, because 2/2 is a guess dressed as a survey — the renderer already draws an
/// unknown split down the middle, and on an odd total the guess would be wrong rather than
/// unhelpful. All-zero is also what lets a tile of untagged residential streets carry no
/// carriageway table at all.
pub fn carriageway(tags: &(impl TagSource + ?Sized)) -> Carriageway {
    let total = lane_count(tags) as u32;
    let forward = osm_ingest::tags::parse_int_tag(tags.get("lanes:forward"));
    let backward = osm_ingest::tags::parse_int_tag(tags.get("lanes:backward"));
    let (forward, backward) = if tags.get("oneway") == Some("yes") {
        // Every lane runs one way, so the total is the forward count even when the way also
        // carries a `lanes:forward` that agrees with it.
        (if forward > 0 { forward } else { total }, 0)
    } else {
        // One side tagged implies the other, and the pair is the more common tagging than either
        // alone. Saturating rather than wrapping: `lanes=2` with `lanes:backward=3` is a mistagged
        // road, and the answer to it is "nothing known about the other side".
        (
            if forward > 0 { forward } else { total.saturating_sub(backward) },
            if backward > 0 { backward } else { total.saturating_sub(forward) },
        )
    };
    // A split that does not add up to the total is not a split: the renderer reads the two
    // together, so 3 forward and 4 backward of five lanes would be a shape it cannot draw. The
    // dividers survive it — they are a property of the total, not of the division.
    let (forward, backward) = if forward + backward == total { (forward, backward) } else { (0, 0) };
    Carriageway {
        forward: forward.min(osm_ingest::tags::MAX_LANES) as u8,
        backward: backward.min(osm_ingest::tags::MAX_LANES) as u8,
        solid_dividers: solid_dividers(tags.get("change:lanes"), total),
    }
}

/// The interior dividers a lane change is prohibited across, a bit each from the leftmost.
///
/// Only the unsuffixed `change:lanes` is read. The `:forward`/`:backward` pair describes each
/// direction's lanes in *that direction's* left-to-right order, so stitching the two into one
/// carriageway-wide ordering needs the driving side — which is a property of the tile rather than
/// of the way and is not known here. The plain tag is already ordered the way this field is.
///
/// A divider is solid when either lane it separates forbids crossing it: lane `k` tagged
/// `not_right` or `no`, or lane `k + 1` tagged `not_left` or `no`.
fn solid_dividers(spec: Option<&str>, lanes: u32) -> u32 {
    let Some(spec) = spec.filter(|s| !s.is_empty()) else {
        return 0;
    };
    let values: Vec<&str> = spec.split('|').map(str::trim).collect();
    // A list that does not describe this road's lanes describes some other road's, and half of it
    // applied to this one would draw solid lines down the wrong gaps.
    if values.len() as u32 != lanes {
        return 0;
    }
    let mut bits = 0u32;
    // A road with more than 32 interior dividers has none recorded past the 32nd, which no real
    // road reaches.
    for k in 0..values.len().saturating_sub(1).min(32) {
        let blocked = matches!(values[k], "no" | "not_right")
            || matches!(values[k + 1], "no" | "not_left");
        if blocked {
            bits |= 1 << k;
        }
    }
    bits
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

    /// The carriageway lane count baked for the renderer's parallel-lane draw: the OSM `lanes`
    /// total, zero when absent, and capped so a mistagged count cannot become an absurd fan.
    #[test]
    fn the_lane_count_is_the_capped_osm_lanes_total() {
        assert_eq!(lane_count(&[("highway", "primary"), ("lanes", "4")][..]), 4);
        assert_eq!(lane_count(&[("highway", "residential")][..]), 0, "no tag, no lanes");
        assert_eq!(lane_count(&[("highway", "primary"), ("lanes", "0")][..]), 0);
        // A byte, and never past the shared MAX_LANES the router and pmtiles layer use.
        assert_eq!(
            lane_count(&[("highway", "motorway"), ("lanes", "999999999")][..]),
            osm_ingest::tags::MAX_LANES as u8,
        );
    }

    /// The per-lane turn masks reuse the routing graph's derivation, so the arrows drawn over a
    /// road agree with how it is routed: a oneway's plain `turn:lanes` is forward, a two-way's is
    /// neither, and the masks are the graph's `LANE_*` bits left to right.
    #[test]
    fn the_turn_masks_match_the_routing_graphs_derivation() {
        use osm_ingest::tags::{LANE_LEFT, LANE_RIGHT, LANE_THROUGH};
        // A oneway carries its plain `turn:lanes` as forward, none backward.
        let oneway: &[(&str, &str)] =
            &[("highway", "primary"), ("oneway", "yes"), ("turn:lanes", "left|through|through;right")];
        let (fwd, bwd) = turn_masks(oneway);
        assert_eq!(fwd, vec![LANE_LEFT, LANE_THROUGH, LANE_THROUGH | LANE_RIGHT]);
        assert!(bwd.is_empty());
        // Explicit forward/backward split on a two-way street.
        let split: &[(&str, &str)] = &[
            ("highway", "secondary"),
            ("turn:lanes:forward", "through|right"),
            ("turn:lanes:backward", "left"),
        ];
        let (fwd, bwd) = turn_masks(split);
        assert_eq!(fwd, vec![LANE_THROUGH, LANE_RIGHT]);
        assert_eq!(bwd, vec![LANE_LEFT]);
        // A road with no turn tags carries nothing either way.
        let (fwd, bwd) = turn_masks(&[("highway", "residential")][..]);
        assert!(fwd.is_empty() && bwd.is_empty());
    }

    /// A one-way is a flag rather than side-table data: it is what decides whether the carriageway
    /// has a centre line at all, and `coalesce` keys on flags — so a one-way and a two-way of the
    /// same class cannot merge into one feature wearing whichever direction came first.
    #[test]
    fn a_oneway_is_flagged_and_only_oneway_yes_counts() {
        let oneway = classify_tags(&[("highway", "primary"), ("oneway", "yes")]).expect("oneway");
        assert_eq!(oneway.flags, FLAG_IS_ONEWAY);
        // The same test the routing graph applies: `-1` is a direction rather than a flag, and
        // neither models it.
        for value in ["no", "-1", "reversible", "alternating", ""] {
            let class = classify_tags(&[("highway", "primary"), ("oneway", value)]).expect(value);
            assert_eq!(class.flags, 0, "oneway={value} is not a one-way here");
        }
        // And it combines with the other three.
        let ramp = classify_tags(&[("highway", "motorway_link"), ("oneway", "yes"), ("bridge", "yes")])
            .expect("ramp");
        assert_eq!(ramp.flags, FLAG_IS_LINK | FLAG_IS_BRIDGE | FLAG_IS_ONEWAY);
    }

    /// The directional split the surface renderer places a centre line from. A one-way puts every
    /// lane forward; a two-way needs one side tagged and infers the other from the total.
    #[test]
    fn the_carriageway_divides_the_lane_total_between_the_directions() {
        let split = |pairs: &[(&str, &str)]| carriageway(pairs);
        assert_eq!(
            split(&[("highway", "motorway"), ("oneway", "yes"), ("lanes", "3")]),
            Carriageway { forward: 3, backward: 0, solid_dividers: 0 },
            "every lane of a one-way runs forward",
        );
        assert_eq!(
            split(&[("highway", "primary"), ("lanes", "4"), ("lanes:backward", "1")]),
            Carriageway { forward: 3, backward: 1, solid_dividers: 0 },
            "one side tagged implies the other",
        );
        assert_eq!(
            split(&[
                ("highway", "primary"),
                ("lanes", "5"),
                ("lanes:forward", "3"),
                ("lanes:backward", "2"),
            ]),
            Carriageway { forward: 3, backward: 2, solid_dividers: 0 },
        );
    }

    /// **An absent split stays absent.** 2/2 for a road tagged only `lanes=4` would be a guess
    /// dressed as a survey, and the renderer already draws an unknown split down the middle. It is
    /// also what lets a tile of untagged residential streets carry no carriageway table at all.
    #[test]
    fn a_split_that_was_never_surveyed_or_does_not_add_up_is_left_unknown() {
        let split = |pairs: &[(&str, &str)]| carriageway(pairs);
        assert_eq!(
            split(&[("highway", "primary"), ("lanes", "4")]),
            Carriageway::default(),
            "a bare total says nothing about the division",
        );
        assert_eq!(split(&[("highway", "residential")][..]), Carriageway::default());
        // Mistagged: three forward and three backward of four lanes is a shape nothing can draw.
        assert_eq!(
            split(&[
                ("highway", "primary"),
                ("lanes", "4"),
                ("lanes:forward", "3"),
                ("lanes:backward", "3"),
            ]),
            Carriageway::default(),
        );
    }

    /// `change:lanes` marks the gaps a lane change is prohibited across, one bit per interior
    /// divider from the leftmost. Either lane can forbid the crossing.
    #[test]
    fn a_prohibited_lane_change_becomes_a_solid_divider() {
        let dividers = |pairs: &[(&str, &str)]| carriageway(pairs).solid_dividers;
        // Four lanes, three interior dividers. `not_right` on lane 0 makes divider 0 solid, `no`
        // on lane 2 makes dividers 1 and 2 solid.
        assert_eq!(
            dividers(&[
                ("highway", "primary"),
                ("lanes", "4"),
                ("change:lanes", "not_right|yes|no|yes"),
            ]),
            0b111,
        );
        assert_eq!(
            dividers(&[("highway", "primary"), ("lanes", "2"), ("change:lanes", "yes|yes")]),
            0,
            "a road nothing is prohibited on has no solid dividers",
        );
        // A list that does not describe this road's lanes describes some other road's, and half of
        // it applied here would draw solid lines down the wrong gaps.
        assert_eq!(
            dividers(&[("highway", "primary"), ("lanes", "4"), ("change:lanes", "no|no")]),
            0,
        );
        // The dividers are a property of the total, so they survive an unusable split.
        assert_eq!(
            dividers(&[("highway", "primary"), ("lanes", "3"), ("change:lanes", "yes|no|yes")]),
            0b11,
        );
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
