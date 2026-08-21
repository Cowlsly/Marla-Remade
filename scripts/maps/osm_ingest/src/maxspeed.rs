//! The `maxspeed` layer: posted speed limits, for the app's `MaxspeedSource`.
//!
//! A port of `normalize_maxspeed.py`, which is the contract of record.
//!
//! ## The value is a RAW STRING and must stay one
//!
//! The emitted `maxspeed` property is the OSM tag verbatim: `"25 mph"`,
//! `"100 km/h"`, `"50"`, `"none"`, `"walk"`, `"signals"`. The app parses it itself,
//! in `OsmMaxspeed.kt::parseMaxspeed`, because the distinctions matter to it --
//! `none` on an autobahn is not the same as an absent limit, and `walk` is not a
//! number at all.
//!
//! **Do not route this through [`crate::tags::parse_maxspeed`].** That function
//! exists for the routing graph, where a speed has to become a `u8` of km/h to go
//! in an edge record, so it normalises units and collapses everything
//! non-numeric to `0`. Sending the layer through it would turn `"25 mph"` into
//! `40`, `"none"` into `0`, and `"walk"` into `0` -- three different facts about a
//! road becoming the same one, with nothing in the pipeline to notice.
//!
//! ## Tag precedence
//!
//! First non-empty of `maxspeed`, `maxspeed:forward`, `maxspeed:backward`. The
//! direction-agnostic tag wins; the directional ones are a fallback, and the layer
//! does not record which one it used. That is a known simplification -- a road with
//! different limits each way is rendered with one of them -- and it is what the
//! Python did.

use crate::geojson::{osm_ref, Feature, Geometry, Value};

/// The keys `osmium tags-filter` narrowed on for this layer.
pub const FILTERS: [&str; 3] = ["maxspeed", "maxspeed:forward", "maxspeed:backward"];

#[derive(Debug, Default, Clone, Copy)]
pub struct MaxspeedTags<'a> {
    pub maxspeed: Option<&'a str>,
    pub maxspeed_forward: Option<&'a str>,
    pub maxspeed_backward: Option<&'a str>,
    pub highway: Option<&'a str>,
    pub name: Option<&'a str>,
}

/// The raw tag value to emit, or `None` when the way has no usable limit.
pub fn extract<'a>(t: &MaxspeedTags<'a>) -> Option<&'a str> {
    [t.maxspeed, t.maxspeed_forward, t.maxspeed_backward]
        .into_iter()
        .flatten()
        .find(|v| !v.is_empty())
}

/// Build the emitted feature. Property order matches the Python's insertion order:
/// `maxspeed`, then `highway`, then `name`, then `osm_id`.
///
/// `highway` and `name` are kept on plain emptiness, **not** on the `_truthy` test
/// the safety layer uses: `normalize_maxspeed.py` has no `_truthy` at all, so
/// `highway=no` survives here where `direction=no` would not survive there. An
/// inconsistency in the original, reproduced deliberately rather than tidied, since
/// tidying it would change what the layer contains.
pub fn feature<'a>(
    maxspeed: &'a str,
    t: &MaxspeedTags<'a>,
    geometry: Geometry,
    way_id: i64,
) -> Feature<'a> {
    let mut props: Vec<(&'a str, Value)> = vec![("maxspeed", Value::str(maxspeed))];
    if let Some(v) = t.highway.filter(|v| !v.is_empty()) {
        props.push(("highway", Value::str(v)));
    }
    if let Some(v) = t.name.filter(|v| !v.is_empty()) {
        props.push(("name", Value::str(v)));
    }
    props.push(("osm_id", osm_ref("way", way_id)));
    Feature { geometry, props }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tags<'a>(ms: Option<&'a str>, fwd: Option<&'a str>, bwd: Option<&'a str>) -> MaxspeedTags<'a> {
        MaxspeedTags {
            maxspeed: ms,
            maxspeed_forward: fwd,
            maxspeed_backward: bwd,
            ..Default::default()
        }
    }

    /// The seven emitted features of
    /// `scripts/maps/test/fixtures/maxspeed_sample.geojsonseq`, as tag sets. The
    /// fixture also holds a way with no maxspeed and a Point that has one; both are
    /// dropped, the first by [`extract`] and the second by the geometry filter.
    fn fixture_values() -> Vec<&'static str> {
        [
            tags(Some("25 mph"), None, None),
            tags(Some("50"), None, None),
            tags(Some("100 km/h"), None, None),
            tags(None, Some("30 mph"), None),
            tags(Some("none"), None, None),
            tags(Some("walk"), None, None),
            tags(Some("signals"), None, None),
        ]
        .iter()
        .filter_map(extract)
        .collect()
    }

    #[test]
    fn emits_seven_values() {
        assert_eq!(fixture_values().len(), 7);
    }

    #[test]
    fn every_raw_form_survives_verbatim() {
        // This is the whole point of the layer: the app parses these itself.
        let values = fixture_values();
        for want in ["25 mph", "50", "100 km/h", "30 mph", "none", "walk", "signals"] {
            assert!(values.contains(&want), "{want:?} was lost: {values:?}");
        }
    }

    #[test]
    fn tags_rs_parse_maxspeed_would_destroy_every_one_of_them() {
        // Not a test of this module so much as a guard on the wiring: if anyone
        // routes the layer through the graph's parser, these are the answers they
        // get instead. "25 mph" becomes 40, and the three non-numeric forms all
        // become the same 0.
        assert_eq!(crate::tags::parse_maxspeed(Some("25 mph")), 40);
        assert_eq!(crate::tags::parse_maxspeed(Some("100 km/h")), 100);
        for lossy in ["none", "walk", "signals"] {
            assert_eq!(crate::tags::parse_maxspeed(Some(lossy)), 0, "{lossy}");
        }
    }

    #[test]
    fn precedence_is_maxspeed_then_forward_then_backward() {
        assert_eq!(extract(&tags(Some("50"), Some("30"), Some("20"))), Some("50"));
        assert_eq!(extract(&tags(None, Some("30"), Some("20"))), Some("30"));
        assert_eq!(extract(&tags(None, None, Some("20"))), Some("20"));
        assert_eq!(extract(&tags(None, None, None)), None);
    }

    #[test]
    fn an_empty_tag_falls_through_to_the_next() {
        // A present-but-empty tag is not a speed limit.
        assert_eq!(extract(&tags(Some(""), Some("30"), None)), Some("30"));
        assert_eq!(extract(&tags(Some(""), Some(""), Some(""))), None);
    }

    #[test]
    fn the_emitted_schema_is_maxspeed_highway_name_osm_id() {
        let t = MaxspeedTags {
            maxspeed: Some("25 mph"),
            highway: Some("residential"),
            name: Some("Main St"),
            ..Default::default()
        };
        let f = feature("25 mph", &t, Geometry::LineString(vec![(0.0, 0.0), (1.0, 1.0)]), 3001);
        let keys: Vec<&str> = f.props.iter().map(|(k, _)| *k).collect();
        assert_eq!(keys, ["maxspeed", "highway", "name", "osm_id"]);
        match &f.props[3].1 {
            Value::Str(s) => assert_eq!(s, b"way/3001"),
            _ => panic!("osm_id must be the way/N string form"),
        }
    }

    #[test]
    fn highway_and_name_are_omitted_only_when_empty() {
        let bare = MaxspeedTags { maxspeed: Some("50"), ..Default::default() };
        assert_eq!(
            feature("50", &bare, Geometry::LineString(vec![]), 1).props.len(),
            2,
            "maxspeed + osm_id only"
        );
        let empty = MaxspeedTags {
            maxspeed: Some("50"),
            highway: Some(""),
            name: Some(""),
            ..Default::default()
        };
        assert_eq!(feature("50", &empty, Geometry::LineString(vec![]), 1).props.len(), 2);

        // `highway=no` IS kept: normalize_maxspeed.py used plain truthiness, not
        // its own `_truthy`, unlike the safety layer.
        let no = MaxspeedTags {
            maxspeed: Some("50"),
            highway: Some("no"),
            ..Default::default()
        };
        assert_eq!(feature("50", &no, Geometry::LineString(vec![]), 1).props.len(), 3);
    }

    #[test]
    fn the_pbf_filters_cover_every_tag_extract_reads() {
        let select = crate::select::Select::parse(&FILTERS).unwrap();
        for (ms, fwd, bwd) in [
            (Some("50"), None, None),
            (None, Some("30 mph"), None),
            (None, None, Some("20")),
        ] {
            let t = tags(ms, fwd, bwd);
            let get = |k: &str| match k {
                "maxspeed" => t.maxspeed,
                "maxspeed:forward" => t.maxspeed_forward,
                "maxspeed:backward" => t.maxspeed_backward,
                _ => None,
            };
            assert!(extract(&t).is_some());
            assert!(select.matches(get), "extract accepts what the filter rejects");
        }
    }
}
