//! The `admin_city` layer: OSM `admin_level=8` boundaries, for the app's border
//! masks and labels.
//!
//! A port of `normalize_admin.py --level city`. Only the city level comes from OSM;
//! `admin_country` and `admin_region` come from Natural Earth, which is not
//! derivable from OSM and is handled elsewhere. See the README's caveats.
//!
//! ## `name_en` has no fallback to plain `name` here
//!
//! The three levels disagree, and the disagreement is in the Python:
//!
//! | level | `name` from | `name_en` from |
//! |---|---|---|
//! | country | `NAME_EN, name:en, NAME, name` | `NAME_EN, name:en` |
//! | region | `name_en, NAME_EN, name, NAME` | `name_en, NAME_EN, name:en` |
//! | **city** | `name:en, name, NAME_EN, NAME` | **`name:en, NAME_EN`** |
//!
//! So a city tagged only `name=Oakland` gets `name` and **no** `name_en`, where a
//! region tagged only `name=California` would get both. Adding the fallback would
//! be a defensible schema decision and a silent change to what the layer contains,
//! so it is not made here.
//!
//! ## The level filter
//!
//! `boundary=administrative` (absent counts as administrative) **and**
//! `admin_level=8`. A relation with neither key is accepted -- that branch existed
//! for Natural Earth rows, which carry no OSM admin tagging -- but a PBF relation
//! always has both, so in practice both must match.

use crate::geojson::{osm_ref, Feature, Geometry, Value};

/// The admin level this layer takes.
pub const CITY_LEVEL: i64 = 8;

/// The `osmium tags-filter` expression for the layer.
pub const FILTERS: [&str; 1] = ["boundary=administrative"];

#[derive(Debug, Default, Clone, Copy)]
pub struct AdminTags<'a> {
    pub boundary: Option<&'a str>,
    pub admin_level: Option<&'a str>,
    pub name_en_tag: Option<&'a str>,
    pub name: Option<&'a str>,
    /// The relation `type`, which must be `boundary` for a boundary relation.
    pub type_: Option<&'a str>,
}

/// `normalize_admin.py`'s `_get`: the first key whose value is neither absent nor
/// empty.
fn first<const N: usize>(values: [Option<&str>; N]) -> Option<&str> {
    values.into_iter().flatten().find(|v| !v.is_empty())
}

/// `is_admin_level(tags, 8)`.
pub fn is_city(t: &AdminTags) -> bool {
    if t.admin_level.is_none() && t.boundary.is_none() {
        // Natural Earth rows have no OSM admin tagging. A PBF relation always has
        // both, so this branch is unreachable from a PBF.
        return true;
    }
    if t.boundary.unwrap_or("administrative") != "administrative" {
        return false;
    }
    match t.admin_level {
        None => true,
        // A non-numeric admin_level is a tagging error, not level 8.
        Some(v) => v.trim().parse::<i64>() == Ok(CITY_LEVEL),
    }
}

/// `build_city`, plus the `"name" not in props` drop the Python applied afterwards:
/// `None` when the boundary is unnamed, since an unnamed border is useless for both
/// the mask and the labels.
pub fn feature<'a>(
    t: &AdminTags<'a>,
    geometry: Geometry,
    relation_id: i64,
) -> Option<Feature<'a>> {
    let name = first([t.name_en_tag, t.name])?;
    // No fallback to plain `name` -- see the module docs.
    let name_en = first([t.name_en_tag]);

    let mut props: Vec<(&'a str, Value)> = vec![
        ("admin_level", Value::num(CITY_LEVEL)),
        ("name", Value::str(name)),
    ];
    if let Some(v) = name_en {
        props.push(("name_en", Value::str(v)));
    }
    props.push(("osm_id", osm_ref("relation", relation_id)));
    Some(Feature { geometry, props })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn city<'a>(name: Option<&'a str>, name_en: Option<&'a str>) -> AdminTags<'a> {
        AdminTags {
            boundary: Some("administrative"),
            admin_level: Some("8"),
            name,
            name_en_tag: name_en,
            type_: Some("boundary"),
        }
    }

    fn poly() -> Geometry {
        Geometry::Polygon(vec![vec![
            (0.0, 0.0),
            (1.0, 0.0),
            (1.0, 1.0),
            (0.0, 1.0),
            (0.0, 0.0),
        ]])
    }

    fn props_of(t: &AdminTags) -> Vec<(String, String)> {
        feature(t, poly(), 1)
            .expect("a named city")
            .props
            .iter()
            .map(|(k, v)| {
                let rendered = match v {
                    Value::Str(s) => String::from_utf8_lossy(s).to_string(),
                    Value::Raw(r) => r.clone(),
                };
                (k.to_string(), rendered)
            })
            .collect()
    }

    /// `scripts/maps/test/fixtures/admin_city_sample.geojsonseq` holds San
    /// Francisco and Oakland at `admin_level=8`, plus a county at `admin_level=6`
    /// that must be dropped.
    #[test]
    fn only_admin_level_8_is_kept() {
        assert!(is_city(&city(Some("San Francisco"), None)));
        assert!(is_city(&city(Some("Oakland"), None)));
        let county = AdminTags {
            admin_level: Some("6"),
            ..city(Some("Alameda County"), None)
        };
        assert!(!is_city(&county), "a county is not a city");
    }

    #[test]
    fn admin_level_is_emitted_as_the_number_8() {
        let p = props_of(&city(Some("Oakland"), None));
        assert_eq!(p[0], ("admin_level".to_string(), "8".to_string()));
        // A number, not a string: the app's mask code reads it as one.
        match &feature(&city(Some("Oakland"), None), poly(), 1).unwrap().props[0].1 {
            Value::Raw(r) => assert_eq!(r, "8"),
            _ => panic!("admin_level must be a JSON number"),
        }
    }

    #[test]
    fn name_prefers_the_english_tag_then_falls_back() {
        // name:en wins.
        let p = props_of(&city(Some("Ciudad de México"), Some("Mexico City")));
        assert_eq!(p[1].1, "Mexico City");
        // Only `name`: that is the name.
        let p = props_of(&city(Some("Oakland"), None));
        assert_eq!(p[1].1, "Oakland");
    }

    #[test]
    fn name_en_has_no_fallback_to_plain_name() {
        // The city level's one asymmetry, and the trap: a region tagged only `name`
        // gets a `name_en` too, a city does not. Adding the fallback would be a
        // silent change to what the layer contains.
        let keys: Vec<String> = props_of(&city(Some("Oakland"), None))
            .into_iter()
            .map(|(k, _)| k)
            .collect();
        assert_eq!(keys, ["admin_level", "name", "osm_id"]);
        assert!(!keys.contains(&"name_en".to_string()));

        // With name:en, both appear.
        let keys: Vec<String> = props_of(&city(Some("Ciudad de México"), Some("Mexico City")))
            .into_iter()
            .map(|(k, _)| k)
            .collect();
        assert_eq!(keys, ["admin_level", "name", "name_en", "osm_id"]);
    }

    #[test]
    fn an_unnamed_boundary_is_dropped() {
        assert!(feature(&city(None, None), poly(), 1).is_none());
        assert!(feature(&city(Some(""), None), poly(), 1).is_none());
        // An empty name:en falls through to `name`.
        let f = feature(&city(Some("Oakland"), Some("")), poly(), 1).unwrap();
        assert_eq!(f.props.len(), 3, "name from `name`, and no name_en");
    }

    #[test]
    fn a_non_administrative_boundary_is_rejected() {
        for b in ["postal_code", "political", "maritime"] {
            let t = AdminTags { boundary: Some(b), ..city(Some("X"), None) };
            assert!(!is_city(&t), "boundary={b}");
        }
        // An absent boundary key defaults to administrative.
        let t = AdminTags { boundary: None, ..city(Some("X"), None) };
        assert!(is_city(&t));
    }

    #[test]
    fn a_malformed_admin_level_is_not_level_eight() {
        for v in ["", "eight", "8a", "8.0", "-8"] {
            let t = AdminTags { admin_level: Some(v), ..city(Some("X"), None) };
            assert!(!is_city(&t), "admin_level={v:?}");
        }
        // Whitespace around a good value is tolerated.
        let t = AdminTags { admin_level: Some(" 8 "), ..city(Some("X"), None) };
        assert!(is_city(&t));
    }

    #[test]
    fn osm_id_is_the_relation_form() {
        let p = props_of(&city(Some("Oakland"), None));
        assert_eq!(p.last().unwrap(), &("osm_id".to_string(), "relation/1".to_string()));
    }

    #[test]
    fn the_pbf_filter_covers_what_the_level_test_accepts() {
        let select = crate::select::Select::parse(&FILTERS).unwrap();
        let t = city(Some("Oakland"), None);
        assert!(is_city(&t));
        assert!(select.matches(|k| if k == "boundary" { t.boundary } else { None }));
    }
}
