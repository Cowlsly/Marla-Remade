//! The `roads` layer: road geometry with the speed, lane and width attributes the
//! app needs to draw and label roads itself.
//!
//! This is the layer that retires [`crate::maxspeed`]. That one existed to answer
//! exactly one question -- what is the posted limit under the puck -- and carried a
//! way's geometry only as the thing the answer was attached to. Owning road
//! rendering in-app needs the same geometry plus lanes and width, and a second
//! near-identical layer of every road in the world is not worth a property.
//!
//! ## Speed is carried BOTH ways, deliberately
//!
//! `maxspeed` is the OSM tag verbatim and `maxspeed_kmh` is
//! [`crate::tags::parse_maxspeed`]'s km/h number. Neither is redundant:
//!
//! * The **number** is what a data-driven style expression can compare against,
//!   because MVT gives the app an int and not a string it has to parse per feature.
//! * The **raw string** is the only thing that survives `maxspeed=DE:urban`,
//!   `GB:nsl_single` and friends -- `parse_maxspeed` returns 0 for every implicit
//!   country scheme -- and the only thing that records whether a limit was authored
//!   in mph, which is what decides the unit the app's speed badge renders in.
//!   `OsmMaxspeed.kt::parseMaxspeed` resolves both and stays the consumer of record.
//!
//! `maxspeed_kmh` is **omitted, not zeroed**, when there is no numeric limit, so
//! `none`, `walk`, `signals` and `DE:urban` are absent rather than claiming a limit
//! of nought. `crate::maxspeed`'s guard test spells out what each of them becomes.
//!
//! ## Lane masks are the graph's, unchanged
//!
//! `turn_lanes_forward` / `turn_lanes_backward` are `|`-separated decimal
//! [`crate::tags`] `LANE_*` masks, one token per lane, left to right -- the same
//! bits, in the same order, that the routing graph writes and
//! `maps/src/main/rust/src/graph.rs` reads. Which tag feeds which direction follows
//! `graph_build::way_attrs` exactly, so a road cannot describe its lanes one way to
//! the router and another to the renderer.

use crate::geojson::{osm_ref, Feature, Geometry, Value};
use crate::tags;

/// The `highway=*` values this layer carries: the set [`crate::tags::get_hw_id`] gives
/// a non-zero id, since that id *is* the emitted `class`, plus the `*_link` ramps it
/// has no id for (see [`base_highway`]).
pub const CLASSES: [&str; 15] = [
    "motorway",
    "trunk",
    "primary",
    "secondary",
    "tertiary",
    "unclassified",
    "residential",
    "service",
    "living_street",
    "pedestrian",
    "track",
    "footway",
    "cycleway",
    "path",
    "steps",
];

/// The `*_link` values, which are slip roads of a [`CLASSES`] parent.
pub const LINK_CLASSES: [&str; 5] = [
    "motorway_link",
    "trunk_link",
    "primary_link",
    "secondary_link",
    "tertiary_link",
];

/// The `osmium tags-filter` expression, kept in step with [`CLASSES`] and
/// [`LINK_CLASSES`] by a test.
pub const FILTERS: [&str; 1] = ["highway=motorway,trunk,primary,secondary,tertiary,\
unclassified,residential,service,living_street,pedestrian,track,footway,cycleway,\
path,steps,motorway_link,trunk_link,primary_link,secondary_link,tertiary_link"];

#[derive(Debug, Default, Clone, Copy)]
pub struct RoadTags<'a> {
    pub highway: Option<&'a str>,
    pub maxspeed: Option<&'a str>,
    pub lanes: Option<&'a str>,
    pub lanes_forward: Option<&'a str>,
    pub lanes_backward: Option<&'a str>,
    pub turn_lanes: Option<&'a str>,
    pub turn_lanes_forward: Option<&'a str>,
    pub turn_lanes_backward: Option<&'a str>,
    pub oneway: Option<&'a str>,
    pub width: Option<&'a str>,
    pub bridge: Option<&'a str>,
    pub tunnel: Option<&'a str>,
    pub layer: Option<&'a str>,
}

/// The parent class of a `highway` value: `motorway_link` -> `motorway`.
///
/// A slip road IS its parent class as far as drawing goes -- it is the same asphalt at
/// the same width, and a map that omits interchange ramps has holes in every
/// motorway junction. [`crate::tags::get_hw_id`] has no id for one, and cannot be
/// given one: those numbers are an on-disk contract with
/// `maps/src/main/rust/src/graph.rs` and renumbering them would reinterpret every
/// edge in an already-built graph. So the suffix is dropped here instead.
pub fn base_highway(value: Option<&str>) -> Option<&str> {
    let v = value?;
    Some(v.strip_suffix("_link").unwrap_or(v))
}

/// The road class number, or `None` for a `highway` value that is not a road.
pub fn classify(t: &RoadTags) -> Option<u8> {
    match tags::get_hw_id(base_highway(t.highway)) {
        0 => None,
        id => Some(id),
    }
}

/// An OSM flag tag that is set. Absent, empty and `no` are all unset; anything
/// else counts, because `bridge=viaduct` and `tunnel=building_passage` are as much
/// a bridge and a tunnel as `yes` is.
fn flag(v: Option<&str>) -> bool {
    matches!(v, Some(s) if !s.is_empty() && s != "no")
}

/// `layer=*` as a signed number for draw order. Absent or unparseable is 0, the
/// same as ground level; the `1;2` and `0.5` forms real data contains are not
/// orderings this layer can express, so they get ground level rather than a guess.
pub fn parse_layer(v: Option<&str>) -> i32 {
    v.and_then(|s| s.trim().parse::<i32>().ok()).unwrap_or(0)
}

/// Per-lane `LANE_*` masks for each direction, `(forward, backward)`.
///
/// A verbatim copy of `graph_build::way_attrs`'s derivation: forward lanes come
/// from `turn:lanes:forward`, or from a plain `turn:lanes` when the way is a
/// oneway; backward from `turn:lanes:backward`, and a oneway has none. The plain
/// `lanes*` counts only pad or truncate a real `turn:lanes` list.
pub fn lane_masks(t: &RoadTags) -> (Vec<u16>, Vec<u16>) {
    let oneway = is_oneway(t);
    let count_all = tags::parse_int_tag(t.lanes);
    let count_fwd = tags::parse_int_tag(t.lanes_forward);
    let count_bwd = tags::parse_int_tag(t.lanes_backward);

    let fwd_spec = t.turn_lanes_forward.or(if oneway { t.turn_lanes } else { None });
    let fwd_hint = if count_fwd > 0 {
        count_fwd
    } else if oneway {
        count_all
    } else {
        0
    };
    let bwd_spec = if oneway { None } else { t.turn_lanes_backward };
    (
        tags::build_dir_lanes(fwd_spec, fwd_hint),
        tags::build_dir_lanes(bwd_spec, count_bwd),
    )
}

/// `oneway=yes`, and only that -- the same test the routing graph applies, so the
/// two cannot disagree about a road. `oneway=-1` is a direction rather than a
/// flag and neither models it.
pub fn is_oneway(t: &RoadTags) -> bool {
    t.oneway == Some("yes")
}

/// One direction's masks as the `|`-separated decimal tokens the property holds.
pub fn pack_lanes(masks: &[u16]) -> String {
    let mut out = String::new();
    for (i, m) in masks.iter().enumerate() {
        if i > 0 {
            out.push('|');
        }
        out.push_str(&m.to_string());
    }
    out
}

/// Build the emitted feature.
///
/// Every attribute past `class` and `osm_id` is **omitted when it has nothing to
/// say**, rather than written as a zero or an empty string. At planet scale this is
/// most of the layer's size: the great majority of roads carry no `turn:lanes`, no
/// `width` and no `layer`, and a property present on every feature is a property
/// paid for on every feature.
pub fn feature<'a>(
    class: u8,
    t: &RoadTags<'a>,
    geometry: Geometry,
    way_id: i64,
) -> Feature<'a> {
    let mut props: Vec<(&'a str, Value)> = vec![("class", Value::num(class))];

    if let Some(v) = t.maxspeed.filter(|v| !v.is_empty()) {
        props.push(("maxspeed", Value::str(v)));
    }
    let kmh = tags::parse_maxspeed(t.maxspeed);
    if kmh > 0 {
        props.push(("maxspeed_kmh", Value::num(kmh)));
    }

    let lanes = tags::parse_int_tag(t.lanes);
    if lanes > 0 {
        props.push(("lanes", Value::num(lanes.min(tags::MAX_LANES))));
    }
    let (fwd, bwd) = lane_masks(t);
    if !fwd.is_empty() {
        props.push(("turn_lanes_forward", Value::str(pack_lanes(&fwd))));
    }
    if !bwd.is_empty() {
        props.push(("turn_lanes_backward", Value::str(pack_lanes(&bwd))));
    }

    if is_oneway(t) {
        props.push(("oneway", Value::num(1u8)));
    }
    let width = tags::parse_width_m(t.width);
    if width > 0.0 {
        // Two decimals: centimetre resolution is already finer than the tag is
        // ever surveyed to, and a fixed form keeps two runs byte-identical.
        props.push(("width", Value::Raw(format!("{width:.2}"))));
    }
    if flag(t.bridge) {
        props.push(("bridge", Value::num(1u8)));
    }
    if flag(t.tunnel) {
        props.push(("tunnel", Value::num(1u8)));
    }
    let layer = parse_layer(t.layer);
    if layer != 0 {
        props.push(("layer", Value::num(layer)));
    }

    props.push(("osm_id", osm_ref("way", way_id)));
    Feature { geometry, props }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tags::{LANE_LEFT, LANE_NONE, LANE_RIGHT, LANE_THROUGH};

    fn road(highway: &str) -> RoadTags<'_> {
        RoadTags { highway: Some(highway), ..Default::default() }
    }

    fn keys<'a>(f: &Feature<'a>) -> Vec<&'a str> {
        f.props.iter().map(|(k, _)| *k).collect()
    }

    fn rendered(f: &Feature) -> String {
        let mut line = Vec::new();
        crate::geojson::render_feature(f, &mut line);
        String::from_utf8(line).unwrap()
    }

    #[test]
    fn the_class_is_the_graphs_own_road_type_number() {
        // Sharing `get_hw_id` is what keeps the rendered class and the routed
        // class the same fact about a road.
        assert_eq!(classify(&road("motorway")), Some(1));
        assert_eq!(classify(&road("residential")), Some(7));
        assert_eq!(classify(&road("steps")), Some(15));
        // Not a road: no feature at all.
        assert_eq!(classify(&road("proposed")), None);
        assert_eq!(classify(&road("")), None);
        assert_eq!(classify(&RoadTags::default()), None);
    }

    #[test]
    fn a_slip_road_takes_its_parent_class() {
        // `get_hw_id` has no id for a `*_link` and cannot be given one without
        // renumbering the graph's on-disk road types, so the suffix is dropped.
        assert_eq!(classify(&road("motorway_link")), classify(&road("motorway")));
        assert_eq!(classify(&road("tertiary_link")), Some(5));
        assert_eq!(base_highway(Some("primary_link")), Some("primary"));
        assert_eq!(base_highway(Some("primary")), Some("primary"));
        // Not every `_link` suffix is a road: the parent still has to classify.
        assert_eq!(classify(&road("raceway_link")), None);
    }

    #[test]
    fn the_pbf_filter_and_the_class_set_agree() {
        let select = crate::select::Select::parse(&FILTERS).unwrap();
        for value in CLASSES {
            assert_ne!(
                tags::get_hw_id(Some(value)),
                0,
                "highway={value} is filtered in but has no class"
            );
            assert!(
                select.matches(|k| if k == "highway" { Some(value) } else { None }),
                "highway={value} classifies but is pre-filtered out"
            );
        }
        // Every ramp is let through and lands on its parent's class.
        for value in LINK_CLASSES {
            assert!(
                select.matches(|k| if k == "highway" { Some(value) } else { None }),
                "highway={value} classifies but is pre-filtered out"
            );
            let parent = value.strip_suffix("_link").unwrap();
            assert_eq!(classify(&road(value)), classify(&road(parent)), "{value}");
        }
        // And nothing outside the set gets through.
        for value in ["proposed", "construction", "raceway", "bus_guideway"] {
            assert_eq!(tags::get_hw_id(Some(value)), 0, "{value}");
            assert!(!select.matches(|k| if k == "highway" { Some(value) } else { None }));
        }
    }

    #[test]
    fn speed_is_carried_as_both_the_raw_tag_and_a_number() {
        let t = RoadTags { highway: Some("residential"), maxspeed: Some("25 mph"), ..Default::default() };
        let f = feature(7, &t, Geometry::LineString(vec![]), 3001);
        assert_eq!(keys(&f), ["class", "maxspeed", "maxspeed_kmh", "osm_id"]);
        let s = rendered(&f);
        assert!(s.contains("\"maxspeed\":\"25 mph\""), "{s}");
        assert!(s.contains("\"maxspeed_kmh\":40"), "{s}");
    }

    #[test]
    fn a_speed_the_number_cannot_hold_keeps_its_string_and_omits_the_number() {
        // The whole reason both are emitted. `parse_maxspeed` answers 0 for every
        // one of these, and 0 is not a speed limit, so the property is absent and
        // the app falls back to parsing the raw value.
        for raw in ["none", "walk", "signals", "DE:urban", "GB:nsl_single"] {
            let t = RoadTags { highway: Some("motorway"), maxspeed: Some(raw), ..Default::default() };
            let f = feature(1, &t, Geometry::LineString(vec![]), 1);
            assert_eq!(keys(&f), ["class", "maxspeed", "osm_id"], "{raw}");
            assert!(rendered(&f).contains(&format!("\"maxspeed\":\"{raw}\"")), "{raw}");
        }
        // And a way with no maxspeed tag at all carries neither.
        let f = feature(7, &road("residential"), Geometry::LineString(vec![]), 1);
        assert_eq!(keys(&f), ["class", "osm_id"]);
    }

    #[test]
    fn turn_lanes_pack_to_the_graphs_masks_in_lane_order() {
        let t = RoadTags {
            highway: Some("primary"),
            turn_lanes_forward: Some("left|through|through;right"),
            ..Default::default()
        };
        let (fwd, bwd) = lane_masks(&t);
        assert_eq!(fwd, vec![LANE_LEFT, LANE_THROUGH, LANE_THROUGH | LANE_RIGHT]);
        assert!(bwd.is_empty());
        assert_eq!(
            pack_lanes(&fwd),
            format!("{LANE_LEFT}|{LANE_THROUGH}|{}", LANE_THROUGH | LANE_RIGHT)
        );
        let f = feature(3, &t, Geometry::LineString(vec![]), 1);
        assert_eq!(keys(&f), ["class", "turn_lanes_forward", "osm_id"]);
    }

    #[test]
    fn lane_direction_derivation_matches_the_routing_graph() {
        // A plain `turn:lanes` belongs to the forward direction only on a oneway;
        // on a two-way street it is ambiguous and the graph takes neither side.
        let two_way = RoadTags {
            highway: Some("residential"),
            turn_lanes: Some("left|through"),
            ..Default::default()
        };
        assert_eq!(lane_masks(&two_way), (Vec::new(), Vec::new()));

        let one_way = RoadTags { oneway: Some("yes"), ..two_way };
        assert_eq!(lane_masks(&one_way).0, vec![LANE_LEFT, LANE_THROUGH]);
        assert!(lane_masks(&one_way).1.is_empty());

        // A oneway never carries backward lanes, whatever the tag says.
        let contradictory = RoadTags {
            oneway: Some("yes"),
            turn_lanes_backward: Some("through"),
            ..two_way
        };
        assert!(lane_masks(&contradictory).1.is_empty());

        // `lanes:forward` pads a short list; on a oneway plain `lanes` does.
        let padded = RoadTags {
            highway: Some("primary"),
            turn_lanes_forward: Some("left"),
            lanes_forward: Some("3"),
            ..Default::default()
        };
        assert_eq!(lane_masks(&padded).0, vec![LANE_LEFT, LANE_NONE, LANE_NONE]);
    }

    #[test]
    fn a_mistagged_lane_count_cannot_become_an_allocation() {
        // `MAX_LANES` guards both the padding and the emitted count.
        let t = RoadTags {
            highway: Some("motorway"),
            turn_lanes_forward: Some("through"),
            lanes: Some("999999999"),
            lanes_forward: Some("999999999"),
            ..Default::default()
        };
        assert_eq!(lane_masks(&t).0.len(), tags::MAX_LANES as usize);
        let s = rendered(&feature(1, &t, Geometry::LineString(vec![]), 1));
        assert!(s.contains(&format!("\"lanes\":{}", tags::MAX_LANES)), "{s}");
    }

    #[test]
    fn width_converts_feet_and_rejects_nonsense() {
        assert!((tags::parse_width_m(Some("3.5")) - 3.5).abs() < 1e-9);
        assert!((tags::parse_width_m(Some("3.5 m")) - 3.5).abs() < 1e-9);
        assert_eq!(tags::parse_width_m(Some("wide")), 0.0);
        assert_eq!(tags::parse_width_m(Some("-2")), 0.0);
        assert_eq!(tags::parse_width_m(None), 0.0);
        // Every form of feet, however it is spaced or cased. Reading one of these as
        // metres would make a residential street wider than a motorway.
        for v in ["10 ft", "10ft", "10 FT ", "10 Feet", "10'"] {
            assert!((tags::parse_width_m(Some(v)) - 3.048).abs() < 1e-9, "{v}");
        }

        // Emitted with two decimals, so two runs are byte-identical.
        let t = RoadTags { highway: Some("residential"), width: Some("10 ft"), ..Default::default() };
        assert!(rendered(&feature(7, &t, Geometry::LineString(vec![]), 1)).contains("\"width\":3.05"));
    }

    #[test]
    fn draw_order_flags_survive_their_real_tag_values() {
        // `bridge=viaduct` is a bridge; `tunnel=no` is not a tunnel.
        let t = RoadTags {
            highway: Some("motorway"),
            bridge: Some("viaduct"),
            tunnel: Some("no"),
            layer: Some("-1"),
            ..Default::default()
        };
        let f = feature(1, &t, Geometry::LineString(vec![]), 1);
        assert_eq!(keys(&f), ["class", "bridge", "layer", "osm_id"]);
        assert!(rendered(&f).contains("\"layer\":-1"));

        // Ground level is the default, so it is never written.
        for v in [None, Some("0"), Some(""), Some("1;2"), Some("half")] {
            assert_eq!(parse_layer(v), 0, "{v:?}");
        }
    }

    #[test]
    fn a_bare_road_is_class_plus_osm_id_and_nothing_else() {
        // The common case at planet scale, and the reason every other property is
        // conditional.
        let f = feature(7, &road("residential"), Geometry::LineString(vec![(0.0, 0.0), (1.0, 1.0)]), 42);
        assert_eq!(keys(&f), ["class", "osm_id"]);
        assert!(rendered(&f).contains("\"osm_id\":\"way/42\""));
    }
}
