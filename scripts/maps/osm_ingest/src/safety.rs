//! The `safety` layer: baked road furniture -- speed cameras, ALPR, generic
//! surveillance, stop signs and traffic signals.
//!
//! A port of `normalize_safety.py`, which is the contract of record. Everything
//! here is node-based; `osmium export` produced the odd tagged way with these
//! tags, and the Python dropped anything that was not a `Point`, so we only ever
//! look at nodes.
//!
//! ## The classification order is load-bearing
//!
//! It is a chain of early returns, not a set of independent rules:
//!
//! 1. `highway=speed_camera`
//! 2. `enforcement=maxspeed` -- **before** the surveillance branch. A camera
//!    tagged both `man_made=surveillance` and `enforcement=maxspeed` is a speed
//!    camera, not a surveillance camera, and reordering these two silently
//!    reclassifies a whole category.
//! 3. `man_made=surveillance`, split into `alpr` or `surveillance`
//! 4. `highway=stop`
//! 5. `highway=traffic_signals`
//!
//! ## The ALPR heuristic is deliberately asymmetric
//!
//! DeFlock (deflock.me) tags automated licence-plate readers as
//! `man_made=surveillance` plus `surveillance:type=ALPR`, and Flock Safety is the
//! dominant operator and manufacturer. So:
//!
//! * `surveillance:type` is matched by **substring**, because real values include
//!   things like `ALPR;camera` and `traffic ANPR`.
//! * `camera:type` is matched by **exact equality** against `alpr`/`anpr`,
//!   because it is an enumerated field whose other values (`fixed`, `dome`,
//!   `panning`) would be corrupted by a substring rule.
//! * `operator` and `manufacturer` are matched by substring on `flock`, catching
//!   "Flock Safety", "Flock Safety LLC" and so on.
//!
//! That asymmetry is not an oversight in the Python and must survive the port.

use crate::geojson::{osm_ref, Feature, Geometry, Value};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    SpeedCamera,
    Alpr,
    Surveillance,
    StopSign,
    TrafficSignals,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::SpeedCamera => "speed_camera",
            Kind::Alpr => "alpr",
            Kind::Surveillance => "surveillance",
            Kind::StopSign => "stop_sign",
            Kind::TrafficSignals => "traffic_signals",
        }
    }
}

/// The keys `osmium tags-filter` narrowed on, and which therefore have to be in
/// the PBF pre-filter for this layer.
pub const FILTERS: [&str; 3] = [
    "highway=speed_camera,stop,traffic_signals",
    "man_made=surveillance",
    "enforcement=maxspeed",
];

/// Just the tags this layer reads, in the plain `Option<&str>` shape
/// [`crate::tags`] uses, so classification is testable with no PBF.
#[derive(Debug, Default, Clone, Copy)]
pub struct SafetyTags<'a> {
    pub highway: Option<&'a str>,
    pub man_made: Option<&'a str>,
    pub enforcement: Option<&'a str>,
    pub surveillance_type: Option<&'a str>,
    pub camera_type: Option<&'a str>,
    pub operator: Option<&'a str>,
    pub manufacturer: Option<&'a str>,
    pub name: Option<&'a str>,
    pub direction: Option<&'a str>,
    pub reference: Option<&'a str>,
}

/// `normalize_safety.py`'s `_truthy`: absent, empty, and the three "no" spellings
/// all count as missing, so a `direction=no` never reaches the layer.
pub fn truthy(v: Option<&str>) -> bool {
    !matches!(v, None | Some("") | Some("no") | Some("false") | Some("0"))
}

pub fn classify(t: &SafetyTags) -> Option<Kind> {
    if t.highway == Some("speed_camera") {
        return Some(Kind::SpeedCamera);
    }
    // A node on an enforcement=maxspeed relation is often tagged directly, and
    // this must be checked before the surveillance branch below.
    if t.enforcement == Some("maxspeed") {
        return Some(Kind::SpeedCamera);
    }

    if t.man_made == Some("surveillance") {
        let surv = t.surveillance_type.unwrap_or("").to_lowercase();
        let cam = t.camera_type.unwrap_or("").to_lowercase();
        let operator = t.operator.unwrap_or("").to_lowercase();
        let manufacturer = t.manufacturer.unwrap_or("").to_lowercase();
        let is_alpr = surv.contains("alpr")
            || surv.contains("anpr")
            || cam == "alpr"
            || cam == "anpr"
            || operator.contains("flock")
            || manufacturer.contains("flock");
        return Some(if is_alpr { Kind::Alpr } else { Kind::Surveillance });
    }

    if t.highway == Some("stop") {
        return Some(Kind::StopSign);
    }
    if t.highway == Some("traffic_signals") {
        return Some(Kind::TrafficSignals);
    }
    None
}

/// Build the emitted feature. Property order matches the Python's insertion
/// order: `kind`, then the carried attributes, then `osm_id`.
pub fn feature<'a>(kind: Kind, t: &SafetyTags<'a>, lon: f64, lat: f64, node_id: i64) -> Feature<'a> {
    let mut props: Vec<(&'a str, Value)> = vec![("kind", Value::str(kind.as_str()))];
    for (key, value) in [
        ("name", t.name),
        ("direction", t.direction),
        ("operator", t.operator),
        ("ref", t.reference),
        ("surveillance_type", t.surveillance_type),
    ] {
        if truthy(value) {
            props.push((key, Value::str(value.unwrap())));
        }
    }
    // Always a string, as the Python's `str(osm_id)` made it, and always the
    // `node/N` form the harness pairs features on.
    props.push(("osm_id", osm_ref("node", node_id)));
    Feature {
        geometry: Geometry::Point((lon, lat)),
        props,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The nine features of `scripts/maps/test/fixtures/safety_sample.geojsonseq`,
    /// transcribed as tag sets.
    ///
    /// The fixture is GeoJSON, which this crate has no reader for and does not
    /// want one -- it reads PBF. What the fixture actually pins is the tag-to-kind
    /// mapping, so the tags are what gets mirrored, and the assertions below are
    /// the twelve `test_normalize.py::test_safety` checks one for one.
    fn fixture() -> Vec<(&'static str, SafetyTags<'static>, bool)> {
        vec![
            // node/1001 -- highway=speed_camera with a direction.
            (
                "node/1001",
                SafetyTags {
                    highway: Some("speed_camera"),
                    direction: Some("forward"),
                    ..Default::default()
                },
                true,
            ),
            // node/1002 -- enforcement=maxspeed, the second route to speed_camera.
            (
                "node/1002",
                SafetyTags {
                    enforcement: Some("maxspeed"),
                    ..Default::default()
                },
                true,
            ),
            // node/1003 -- surveillance:type=ALPR, operator Flock Safety.
            (
                "node/1003",
                SafetyTags {
                    man_made: Some("surveillance"),
                    surveillance_type: Some("ALPR"),
                    operator: Some("Flock Safety"),
                    ..Default::default()
                },
                true,
            ),
            // node/1004 -- ALPR via camera:type instead.
            (
                "node/1004",
                SafetyTags {
                    man_made: Some("surveillance"),
                    camera_type: Some("alpr"),
                    ..Default::default()
                },
                true,
            ),
            // node/1005 -- a plain surveillance camera.
            (
                "node/1005",
                SafetyTags {
                    man_made: Some("surveillance"),
                    camera_type: Some("fixed"),
                    ..Default::default()
                },
                true,
            ),
            // node/1006 -- a stop sign.
            (
                "node/1006",
                SafetyTags {
                    highway: Some("stop"),
                    ..Default::default()
                },
                true,
            ),
            // node/1007 -- traffic signals.
            (
                "node/1007",
                SafetyTags {
                    highway: Some("traffic_signals"),
                    ..Default::default()
                },
                true,
            ),
            // node/1008 -- a cafe. Not safety furniture.
            (
                "node/1008",
                SafetyTags {
                    name: Some("Corner Cafe"),
                    ..Default::default()
                },
                false,
            ),
            // way/1009 -- highway=stop on a LineString. Classifies, but the
            // geometry filter drops it, which is why `emitted` is false.
            (
                "way/1009",
                SafetyTags {
                    highway: Some("stop"),
                    ..Default::default()
                },
                false,
            ),
        ]
    }

    /// The kinds the layer emits for the fixture, i.e. after both the
    /// classification and the Point-only geometry filter.
    fn fixture_kinds() -> Vec<Kind> {
        fixture()
            .iter()
            .filter(|(_, _, emitted)| *emitted)
            .filter_map(|(_, t, _)| classify(t))
            .collect()
    }

    fn count(kinds: &[Kind], want: Kind) -> usize {
        kinds.iter().filter(|k| **k == want).count()
    }

    #[test]
    fn emits_seven_features() {
        assert_eq!(fixture_kinds().len(), 7);
    }

    #[test]
    fn speed_camera_from_highway_and_from_enforcement() {
        let kinds = fixture_kinds();
        assert!(kinds.contains(&Kind::SpeedCamera));
        // Two routes to the same kind: the tag and the enforcement relation role.
        assert_eq!(count(&kinds, Kind::SpeedCamera), 2);
    }

    #[test]
    fn alpr_from_surveillance_type_and_from_camera_type() {
        let kinds = fixture_kinds();
        assert!(kinds.contains(&Kind::Alpr));
        assert_eq!(count(&kinds, Kind::Alpr), 2);
    }

    #[test]
    fn generic_surveillance_stop_sign_and_traffic_signals() {
        let kinds = fixture_kinds();
        assert!(kinds.contains(&Kind::Surveillance));
        assert!(kinds.contains(&Kind::StopSign));
        assert!(kinds.contains(&Kind::TrafficSignals));
    }

    #[test]
    fn nothing_non_safety_leaks_through() {
        let cafe = fixture()
            .into_iter()
            .find(|(id, _, _)| *id == "node/1008")
            .unwrap();
        assert_eq!(classify(&cafe.1), None, "a cafe is not road furniture");
    }

    #[test]
    fn alpr_keeps_its_operator() {
        let (_, tags, _) = fixture()
            .into_iter()
            .find(|(id, _, _)| *id == "node/1003")
            .unwrap();
        let f = feature(classify(&tags).unwrap(), &tags, -122.417, 37.777, 1003);
        let operator = f
            .props
            .iter()
            .find(|(k, _)| *k == "operator")
            .expect("operator carried through");
        match &operator.1 {
            Value::Str(s) => assert_eq!(s, b"Flock Safety"),
            _ => panic!("operator must be a string"),
        }
    }

    #[test]
    fn every_feature_carries_an_osm_id_and_point_geometry() {
        for (_, tags, emitted) in fixture() {
            if !emitted {
                continue;
            }
            let kind = classify(&tags).unwrap();
            let f = feature(kind, &tags, 0.0, 0.0, 1);
            assert!(matches!(f.geometry, Geometry::Point(_)));
            // osm_id is last and always present.
            assert_eq!(f.props.last().unwrap().0, "osm_id");
            assert_eq!(f.props.first().unwrap().0, "kind");
        }
    }

    // --- the rules the fixture cannot express on its own ---

    #[test]
    fn enforcement_wins_over_surveillance() {
        // The ordering trap: a camera tagged both ways is a speed camera. Getting
        // this backwards reclassifies every enforcement camera as surveillance.
        let t = SafetyTags {
            man_made: Some("surveillance"),
            enforcement: Some("maxspeed"),
            surveillance_type: Some("ALPR"),
            ..Default::default()
        };
        assert_eq!(classify(&t), Some(Kind::SpeedCamera));
    }

    #[test]
    fn surveillance_type_matches_by_substring_but_camera_type_by_equality() {
        fn surv<'a>(v: &'a str) -> SafetyTags<'a> {
            SafetyTags {
                man_made: Some("surveillance"),
                surveillance_type: Some(v),
                ..Default::default()
            }
        }
        // Substring, and case-insensitive.
        assert_eq!(classify(&surv("ALPR")), Some(Kind::Alpr));
        assert_eq!(classify(&surv("alpr;camera")), Some(Kind::Alpr));
        assert_eq!(classify(&surv("traffic ANPR")), Some(Kind::Alpr));
        assert_eq!(classify(&surv("public")), Some(Kind::Surveillance));

        fn cam<'a>(v: &'a str) -> SafetyTags<'a> {
            SafetyTags {
                man_made: Some("surveillance"),
                camera_type: Some(v),
                ..Default::default()
            }
        }
        assert_eq!(classify(&cam("alpr")), Some(Kind::Alpr));
        assert_eq!(classify(&cam("ANPR")), Some(Kind::Alpr));
        // Exact match only: an enumerated field must not be substring-matched, or
        // every `alpr_capable`-style value would silently become an ALPR.
        assert_eq!(classify(&cam("alpr;dome")), Some(Kind::Surveillance));
        assert_eq!(classify(&cam("fixed")), Some(Kind::Surveillance));
    }

    #[test]
    fn the_flock_heuristic_hits_operator_and_manufacturer() {
        let base = SafetyTags {
            man_made: Some("surveillance"),
            ..Default::default()
        };
        assert_eq!(
            classify(&SafetyTags {
                operator: Some("Flock Safety"),
                ..base
            }),
            Some(Kind::Alpr)
        );
        assert_eq!(
            classify(&SafetyTags {
                manufacturer: Some("Flock Safety LLC"),
                ..base
            }),
            Some(Kind::Alpr)
        );
        assert_eq!(
            classify(&SafetyTags {
                operator: Some("City of Oakland"),
                ..base
            }),
            Some(Kind::Surveillance)
        );
    }

    #[test]
    fn surveillance_type_is_kept_as_a_property() {
        // Undocumented in the schema table but emitted by the Python, and the app
        // may already be reading it. Dropping it would be a silent schema break.
        let t = SafetyTags {
            man_made: Some("surveillance"),
            surveillance_type: Some("ALPR"),
            ..Default::default()
        };
        let f = feature(Kind::Alpr, &t, 0.0, 0.0, 1);
        assert!(f.props.iter().any(|(k, _)| *k == "surveillance_type"));
    }

    #[test]
    fn falsey_attribute_values_are_dropped() {
        for v in ["", "no", "false", "0"] {
            let t = SafetyTags {
                highway: Some("stop"),
                direction: Some(v),
                name: Some(v),
                ..Default::default()
            };
            let f = feature(Kind::StopSign, &t, 0.0, 0.0, 1);
            assert_eq!(
                f.props.len(),
                2,
                "kind + osm_id only, but {v:?} was carried through"
            );
        }
        // Anything else is kept, including values that merely look falsey.
        let t = SafetyTags {
            highway: Some("stop"),
            direction: Some("both"),
            ..Default::default()
        };
        assert_eq!(feature(Kind::StopSign, &t, 0.0, 0.0, 1).props.len(), 3);
    }

    #[test]
    fn untagged_and_unrelated_nodes_classify_to_nothing() {
        assert_eq!(classify(&SafetyTags::default()), None);
        assert_eq!(
            classify(&SafetyTags {
                highway: Some("residential"),
                ..Default::default()
            }),
            None
        );
        // `man_made` that is not surveillance falls through to the highway rules.
        assert_eq!(
            classify(&SafetyTags {
                man_made: Some("mast"),
                ..Default::default()
            }),
            None
        );
    }

    #[test]
    fn the_pbf_filters_cover_every_classifying_tag() {
        let select = crate::select::Select::parse(&FILTERS).unwrap();
        for (id, tags, _) in fixture() {
            let get = |k: &str| match k {
                "highway" => tags.highway,
                "man_made" => tags.man_made,
                "enforcement" => tags.enforcement,
                _ => None,
            };
            // The pre-filter must never reject something the classifier accepts,
            // or the layer loses features for a reason nothing reports.
            if classify(&tags).is_some() {
                assert!(select.matches(get), "{id} classifies but is pre-filtered out");
            }
        }
    }
}
