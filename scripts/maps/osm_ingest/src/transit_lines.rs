//! The `transit_lines` layer: OSM rail/subway/tram/… geometry for the app's
//! transit-line highlight.
//!
//! A port of `normalize_transit_lines.py`. Two sources feed it, and each can only
//! produce some of the kinds:
//!
//! * **railway ways** -- `railway=rail|subway|light_rail|tram|monorail|narrow_gauge`
//! * **route relations** -- `type=route` with `route=subway|tram|light_rail|train|monorail`
//!
//! The asymmetry is deliberate and encoded in the two maps. `rail` is only
//! reachable from a way, because `route=rail` is not a tagging convention; `train`
//! is only reachable from a relation, because that is how national rail services
//! are tagged; `narrow_gauge` folds into `rail`; and `route=bus` is in neither map,
//! so bus routes are dropped -- this layer is for the rail-like modes the highlight
//! styles.
//!
//! `railway` wins over `route`: a way carrying both is a physical piece of track,
//! and the route is a service running over it.
//!
//! ## The HSTORE parsing is gone, and why that is not a regression
//!
//! The Python spent a third of its length on `parse_hstore` and `merged_tags`,
//! unpacking a `"route"=>"subway","colour"=>"#DA291C"` string back into tags. That
//! existed for exactly one reason: relation geometry came from
//! `ogr2ogr -f GeoJSONSeq … multilinestrings`, and GDAL's OSM driver exposes a few
//! named fields and folds every other tag into an `other_tags` HSTORE. Reading
//! relations out of the PBF directly gives their tags as tags, so there is nothing
//! to unpack. The GDAL dependency goes with it.
//!
//! ## Relation geometry is an unordered MultiLineString
//!
//! A route relation's member ways are emitted as separate parts, in member order,
//! with no attempt to stitch them into a single continuous line. The layer's
//! contract allows it -- `MultiLineString` is what the Python emitted too, since
//! that is what GDAL produced -- and it is the right answer for a route: branches,
//! loops and split platforms mean the ways often do not form one path at all.
//!
//! ## One deliberate schema change
//!
//! `osm_id` for a relation is now `relation/9001`, where the GDAL path emitted a
//! bare `9001` (its `osm_id` field) while ways came through as `way/5001`. The two
//! sources disagreeing was an artefact of the toolchain, not a decision, so the
//! port uses the `<kind>/<id>` form throughout. The differential harness will show
//! this as a property difference on relation features; that is expected.

use crate::geojson::{osm_ref, Feature, Geometry, Value};

/// `railway=<value>` on a way -> the layer's `kind`.
pub const RAILWAY_KIND: [(&str, &str); 6] = [
    ("rail", "rail"),
    ("subway", "subway"),
    ("light_rail", "light_rail"),
    ("tram", "tram"),
    ("monorail", "monorail"),
    // Folded in: the highlight styles gauge-agnostic heavy rail the same way.
    ("narrow_gauge", "rail"),
];

/// `route=<value>` on a `type=route` relation -> the layer's `kind`.
pub const ROUTE_KIND: [(&str, &str); 5] = [
    ("subway", "subway"),
    ("tram", "tram"),
    ("light_rail", "light_rail"),
    ("train", "train"),
    ("monorail", "monorail"),
];

/// The `osmium tags-filter` expression for the way half of the layer.
pub const WAY_FILTERS: [&str; 1] = ["railway=rail,subway,light_rail,tram,monorail,narrow_gauge"];

/// The `osmium tags-filter` expression for the relation half.
pub const RELATION_FILTERS: [&str; 1] = ["route=subway,tram,light_rail,train,monorail"];

#[derive(Debug, Default, Clone, Copy)]
pub struct TransitTags<'a> {
    pub railway: Option<&'a str>,
    /// The relation's `type`. Absent is accepted, matching the Python's
    /// `tags.get("type") in (None, "route")`.
    pub type_: Option<&'a str>,
    pub route: Option<&'a str>,
    pub name: Option<&'a str>,
    pub reference: Option<&'a str>,
    pub colour: Option<&'a str>,
    /// The American spelling, accepted as a fallback.
    pub color: Option<&'a str>,
}

fn lookup(map: &[(&str, &'static str)], v: Option<&str>) -> Option<&'static str> {
    let v = v?;
    map.iter().find(|(k, _)| *k == v).map(|(_, kind)| *kind)
}

pub fn railway_kind(v: Option<&str>) -> Option<&'static str> {
    lookup(&RAILWAY_KIND, v)
}

pub fn route_kind(v: Option<&str>) -> Option<&'static str> {
    lookup(&ROUTE_KIND, v)
}

pub fn classify(t: &TransitTags) -> Option<&'static str> {
    // A way carrying both tags is track, not a service.
    if let Some(kind) = railway_kind(t.railway) {
        return Some(kind);
    }
    if matches!(t.type_, None | Some("route")) {
        return route_kind(t.route);
    }
    None
}

/// `normalize_transit_lines.py`'s `_truthy`.
pub fn truthy(v: Option<&str>) -> bool {
    !matches!(v, None | Some("") | Some("no") | Some("false") | Some("0"))
}

/// Whether a route relation's way member with this `role` is part of the line.
///
/// A PTv2 route relation carries more than its path: `platform` and `stop` members
/// describe where passengers board, and a platform is very often a **closed way**.
/// Including those drew every platform's outline as if it were track, which is what
/// put station-shaped boxes all over the transit layer.
///
/// Unroled members are the path, and `forward`/`backward` are as much of it — only
/// boarding infrastructure is excluded, by prefix, because PTv2 spells it
/// `platform_entry_only`, `stop_exit_only` and so on.
pub fn member_is_path(role: &[u8]) -> bool {
    !role.starts_with(b"platform") && !role.starts_with(b"stop")
}

/// British spelling first, then American.
pub fn colour<'a>(t: &TransitTags<'a>) -> Option<&'a str> {
    [t.colour, t.color].into_iter().find(|v| truthy(*v)).flatten()
}

/// Build the emitted feature. Property order matches the Python's insertion order:
/// `kind`, `name`, `ref`, `colour`, `osm_id`.
pub fn feature<'a>(
    kind: &'a str,
    t: &TransitTags<'a>,
    geometry: Geometry,
    element: &'a str,
    id: i64,
) -> Feature<'a> {
    let mut props: Vec<(&'a str, Value)> = vec![("kind", Value::str(kind))];
    if truthy(t.name) {
        props.push(("name", Value::str(t.name.unwrap())));
    }
    if truthy(t.reference) {
        props.push(("ref", Value::str(t.reference.unwrap())));
    }
    if let Some(c) = colour(t) {
        props.push(("colour", Value::str(c)));
    }
    props.push(("osm_id", osm_ref(element, id)));
    Feature { geometry, props }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn way(railway: &str) -> TransitTags<'_> {
        TransitTags { railway: Some(railway), ..Default::default() }
    }

    fn relation<'a>(route: &'a str) -> TransitTags<'a> {
        TransitTags {
            type_: Some("route"),
            route: Some(route),
            ..Default::default()
        }
    }

    /// The eight emitted features of
    /// `scripts/maps/test/fixtures/transit_lines_sample.geojsonseq`: six railway
    /// ways and two route relations. The fixture's platform way, highway way, bus
    /// route relation and station point are all dropped.
    fn fixture_kinds() -> Vec<&'static str> {
        [
            way("subway"),        // way/5001 Market St Subway
            way("light_rail"),    // way/5002
            way("tram"),          // way/5003
            way("monorail"),      // way/5004
            way("rail"),          // way/5005
            way("narrow_gauge"),  // way/5006 -> rail
            relation("subway"),   // relation 9001 Red Line
            relation("tram"),     // relation 9002 N Judah
        ]
        .iter()
        .filter_map(classify)
        .collect()
    }

    fn count(kinds: &[&str], want: &str) -> usize {
        kinds.iter().filter(|k| **k == want).count()
    }

    #[test]
    fn emits_eight_features() {
        assert_eq!(fixture_kinds().len(), 8);
    }

    #[test]
    fn a_way_and_a_relation_can_both_produce_the_same_kind() {
        let kinds = fixture_kinds();
        assert_eq!(count(&kinds, "subway"), 2, "railway=subway and route=subway");
        assert_eq!(count(&kinds, "tram"), 2, "railway=tram and route=tram");
    }

    #[test]
    fn light_rail_and_monorail_come_through() {
        let kinds = fixture_kinds();
        assert!(kinds.contains(&"light_rail"));
        assert!(kinds.contains(&"monorail"));
    }

    #[test]
    fn narrow_gauge_folds_into_rail() {
        // Two rail features from one railway=rail and one railway=narrow_gauge.
        assert_eq!(count(&fixture_kinds(), "rail"), 2);
        assert_eq!(classify(&way("narrow_gauge")), Some("rail"));
    }

    #[test]
    fn platforms_highways_and_bus_routes_are_dropped() {
        assert_eq!(classify(&way("platform")), None);
        assert_eq!(classify(&TransitTags::default()), None);
        // A highway way carries no railway tag at all.
        assert_eq!(
            classify(&TransitTags { route: None, ..Default::default() }),
            None
        );
        // route=bus is in neither map: this layer is the rail-like modes.
        assert_eq!(classify(&relation("bus")), None);
        assert_eq!(classify(&relation("ferry")), None);
    }

    #[test]
    fn train_is_only_reachable_from_a_relation_and_rail_only_from_a_way() {
        assert_eq!(classify(&relation("train")), Some("train"));
        // There is no railway=train convention, so a way cannot make one.
        assert_eq!(classify(&way("train")), None);
        assert_eq!(classify(&way("rail")), Some("rail"));
        // And route=rail is not a convention either.
        assert_eq!(classify(&relation("rail")), None);
    }

    #[test]
    fn railway_wins_over_route() {
        // Track carrying a service is track.
        let both = TransitTags {
            railway: Some("rail"),
            type_: Some("route"),
            route: Some("subway"),
            ..Default::default()
        };
        assert_eq!(classify(&both), Some("rail"));
    }

    #[test]
    fn an_absent_type_is_accepted_but_a_wrong_one_is_not() {
        // The Python tested `type in (None, "route")`.
        assert_eq!(
            classify(&TransitTags { route: Some("subway"), ..Default::default() }),
            Some("subway")
        );
        assert_eq!(
            classify(&TransitTags {
                type_: Some("multipolygon"),
                route: Some("subway"),
                ..Default::default()
            }),
            None
        );
    }

    #[test]
    fn the_relations_colour_and_ref_come_through() {
        // The Red Line fixture: colour "#DA291C", ref "Red", kind subway. These
        // arrived via GDAL's HSTORE before; now they are just tags.
        let t = TransitTags {
            type_: Some("route"),
            route: Some("subway"),
            name: Some("Red Line"),
            reference: Some("Red"),
            colour: Some("#DA291C"),
            ..Default::default()
        };
        assert_eq!(classify(&t), Some("subway"));
        let f = feature("subway", &t, Geometry::MultiLineString(vec![]), "relation", 9001);
        let keys: Vec<&str> = f.props.iter().map(|(k, _)| *k).collect();
        assert_eq!(keys, ["kind", "name", "ref", "colour", "osm_id"]);
        match &f.props[3].1 {
            Value::Str(s) => assert_eq!(s, b"#DA291C"),
            _ => panic!("colour must be a string"),
        }
    }

    #[test]
    fn the_american_colour_spelling_is_accepted() {
        // The N Judah fixture uses `color`.
        let t = TransitTags { color: Some("blue"), ..Default::default() };
        assert_eq!(colour(&t), Some("blue"));
        // British wins when both are set.
        let t = TransitTags {
            colour: Some("#DA291C"),
            color: Some("blue"),
            ..Default::default()
        };
        assert_eq!(colour(&t), Some("#DA291C"));
        // A falsey British value falls through to the American one.
        let t = TransitTags {
            colour: Some("no"),
            color: Some("blue"),
            ..Default::default()
        };
        assert_eq!(colour(&t), Some("blue"));
        assert_eq!(colour(&TransitTags::default()), None);
    }

    #[test]
    fn a_ways_ref_comes_through_too() {
        // The fixture has a way with ref "F".
        let t = TransitTags {
            railway: Some("tram"),
            reference: Some("F"),
            ..Default::default()
        };
        let f = feature("tram", &t, Geometry::LineString(vec![]), "way", 5003);
        assert!(f.props.iter().any(|(k, v)| *k == "ref"
            && matches!(v, Value::Str(s) if s == b"F")));
    }

    #[test]
    fn osm_id_uses_the_element_kind_for_both_sources() {
        // The one deliberate schema change: GDAL emitted a bare number for
        // relations, so the two sources disagreed. See the module docs.
        let t = way("rail");
        let f = feature("rail", &t, Geometry::LineString(vec![]), "way", 5005);
        match &f.props.last().unwrap().1 {
            Value::Str(s) => assert_eq!(s, b"way/5005"),
            _ => panic!(),
        }
        let t = relation("train");
        let f = feature("train", &t, Geometry::MultiLineString(vec![]), "relation", 9003);
        match &f.props.last().unwrap().1 {
            Value::Str(s) => assert_eq!(s, b"relation/9003"),
            _ => panic!(),
        }
    }

    #[test]
    fn falsey_names_and_refs_are_dropped() {
        for v in ["", "no", "false", "0"] {
            let t = TransitTags {
                railway: Some("rail"),
                name: Some(v),
                reference: Some(v),
                colour: Some(v),
                ..Default::default()
            };
            let f = feature("rail", &t, Geometry::LineString(vec![]), "way", 1);
            assert_eq!(f.props.len(), 2, "kind + osm_id only, but {v:?} survived");
        }
    }

    #[test]
    fn only_boarding_infrastructure_roles_are_excluded_from_the_path() {
        // The PTv2 role table. `platform*` and `stop*` describe where passengers
        // board; a platform is usually a closed way, and including it drew a box
        // around every station.
        for role in [
            "platform",
            "platform_entry_only",
            "platform_exit_only",
            "stop",
            "stop_entry_only",
            "stop_exit_only",
        ] {
            assert!(!member_is_path(role.as_bytes()), "{role} is not the path");
        }
        // Unroled members are the path, and a direction is still the path.
        for role in ["", "forward", "backward"] {
            assert!(member_is_path(role.as_bytes()), "{role} is the path");
        }
    }

    #[test]
    fn the_pbf_filters_cover_both_halves_of_the_layer() {
        let ways = crate::select::Select::parse(&WAY_FILTERS).unwrap();
        for (_, kind) in RAILWAY_KIND {
            let value = RAILWAY_KIND.iter().find(|(_, k)| *k == kind).unwrap().0;
            assert!(
                ways.matches(|k| if k == "railway" { Some(value) } else { None }),
                "railway={value} classifies but is pre-filtered out"
            );
        }
        let rels = crate::select::Select::parse(&RELATION_FILTERS).unwrap();
        for (value, _) in ROUTE_KIND {
            assert!(
                rels.matches(|k| if k == "route" { Some(value) } else { None }),
                "route={value} classifies but is pre-filtered out"
            );
        }
        // And the filters do not let bus through.
        assert!(!rels.matches(|k| if k == "route" { Some("bus") } else { None }));
    }
}
