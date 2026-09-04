//! `transit`: coloured rail lines — the one layer whose data comes from relations, not tags.
//!
//! A transit line is an OSM **route relation** (`type=route`, `route=subway/light_rail/tram/
//! train/monorail`) whose members are the railway ways it runs over. The relation carries the
//! official line colour (`colour=`/`color=`); each member way is emitted into the `transit`
//! layer as a line carrying that colour in `transit_color` (`0xRRGGBB`). The same way keeps its
//! `roads` rail feature — the grey dashed casing from the reference style's `roads_rail` — so
//! the coloured line draws over neutral track exactly like a metro map.
//!
//! # Why the colour lives on the way
//!
//! The renderer draws per-feature, not per-relation: a tile holds ways, and joining them back to
//! their relations on device would mean shipping the relation membership. Aggregating in stage A
//! (where the member table already exists for boundaries) puts the colour where the geometry is.
//!
//! # Multi-route ways
//!
//! Shared track belongs to several relations (express + local, or two lines through downtown).
//! One way carries one colour, so the first writer wins under a deterministic order: relations
//! with an explicit colour before fallbacks, lower relation id first within each group. Explicit
//! over fallback because an official colour is data and a fallback is a guess; id order because
//! it is stable across builds. See [`assign_colors`].
//!
//! # Stations
//!
//! Stations stay on the `poi` layer as kind `station` (task 50), but with a `kind_detail`
//! naming the station mode — which is what makes them identifiable as *transit* stations when
//! the POI toggle is off. See [`station_detail`].

use tilecodec::mamaps::dict::LAYER_TRANSIT;

use super::{detail, kind, Class, TagSource};

pub const FILTERS: &[&str] = &["type", "route", "colour", "color", "station"];

/// The route modes this layer carries, with the zoom each surfaces at.
///
/// Floors mirror [`crate::schema::roads`]' `RAILWAYS` table: trunk rail early, street tram late.
/// A metro map without its trunk lines at z8 is decoration, and a tram at z8 is noise.
pub const ROUTE_MODES: &[(&str, u8)] = &[
    ("subway", 12),
    ("light_rail", 12),
    ("tram", 13),
    ("train", 8),
    ("monorail", 13),
];

/// Is this a `type=route` relation in one of the carried modes?
pub fn is_transit_route(tags: &(impl TagSource + ?Sized)) -> bool {
    tags.get("type") == Some("route")
        && tags.get("route").is_some_and(|mode| ROUTE_MODES.iter().any(|(m, _)| *m == mode))
}

/// The mode of a relation already known to be a transit route.
pub fn route_mode(tags: &(impl TagSource + ?Sized)) -> Option<&'static str> {
    let mode = tags.get("route")?;
    ROUTE_MODES.iter().find(|(m, _)| *m == mode).map(|(m, _)| *m)
}

/// A transit line's class: layer `transit`, kind `rail`, detail naming the mode.
///
/// The kind stays `rail` (what the reference style's `roads_rail` matches) while the detail
/// distinguishes a subway from a tram — which is what lets the renderer vary width by mode
/// while keying colour off `transit_color`.
pub fn transit_class(mode: &str) -> Option<Class> {
    let (_, min_zoom) = ROUTE_MODES.iter().find(|(m, _)| *m == mode)?;
    Some(Class {
        layer: LAYER_TRANSIT,
        kind: kind("rail"),
        kind_detail: detail(mode),
        flags: 0,
        area: false,
        min_zoom: (*min_zoom).min(14),
        min_area_px: 0.0,
    })
}

/// A route relation's official line colour as `0xRRGGBB`, or `None` when it names none.
///
/// Reads `colour=` first, then the US spelling `color=`. Accepts `#RRGGBB`, bare `RRGGBB` and
/// `#RGB`; anything else (a name like `red`, a malformed hex) is `None` and the caller falls
/// back per mode rather than guessing from a string no parser agreed on.
pub fn parse_color(tags: &(impl TagSource + ?Sized)) -> Option<u32> {
    let raw = tags.get("colour").or_else(|| tags.get("color"))?;
    hex_color(raw.trim())
}

fn hex_color(raw: &str) -> Option<u32> {
    let hex = raw.strip_prefix('#').unwrap_or(raw);
    let expanded = match hex.len() {
        3 => {
            let mut out = String::with_capacity(6);
            for c in hex.chars() {
                if !c.is_ascii_hexdigit() {
                    return None;
                }
                out.push(c);
                out.push(c);
            }
            out
        }
        6 => {
            if !hex.chars().all(|c| c.is_ascii_hexdigit()) {
                return None;
            }
            hex.to_string()
        }
        _ => return None,
    };
    u32::from_str_radix(&expanded, 16).ok()
}

/// The fallback line colour per mode, as `0xRRGGBB`, for relations that name none.
///
/// Chosen to read as a metro map at a glance — trunk red, surface green, street yellow, regional
/// blue, guideway grey — and reported per build, because a fallback is a guess wearing data's
/// clothes and the count of guesses is the honest number.
pub fn fallback_color(mode: &str) -> u32 {
    match mode {
        "subway" => 0xE4002B,
        "light_rail" => 0x00985F,
        "tram" => 0xFFD200,
        "train" => 0x0057A8,
        "monorail" => 0x9D9D9D,
        _ => 0x666666,
    }
}

/// Assign colours to member ways across route relations, deterministically.
///
/// Returns `(way id, colour, winning mode)` with at most one entry per way: relations carrying
/// an explicit colour go first (lowest id first), then fallbacks (lowest id first). A way shared
/// by two lines keeps the first assignment — explicit over guess, stable over time.
pub fn assign_colors(
    routes: &[(i64, &'static str, Option<u32>, Vec<i64>)],
) -> Vec<(i64, u32, &'static str)> {
    let mut ordered: Vec<(bool, i64, &'static str, Option<u32>, &[i64])> = routes
        .iter()
        .map(|(id, mode, color, members)| (color.is_none(), *id, *mode, *color, members.as_slice()))
        .collect();
    // Explicit colours first, then by relation id: `false < true`, so `sort` puts the haves up
    // front and the lowest id wins within each group.
    ordered.sort_by_key(|(fallback, id, _, _, _)| (*fallback, *id));
    let mut seen: std::collections::HashSet<i64> = std::collections::HashSet::new();
    let mut out = Vec::new();
    for (_, _, mode, color, members) in ordered {
        let color = color.unwrap_or_else(|| fallback_color(mode));
        for member in members {
            if seen.insert(*member) {
                out.push((*member, color, mode));
            }
        }
    }
    out
}

/// The station mode for a `poi` station feature's `kind_detail`.
///
/// `station=subway/light_rail/tram` name the mode directly; a bare `railway=station` is a
/// `station`; a `railway=halt` is a `halt`. This is the tag the renderer reads to show stations
/// when Transit is ON even with POIs OFF: `poi` layer, kind `station`, detail naming one of
/// these — everything else on the layer is a different kind already.
pub fn station_detail(tags: &(impl TagSource + ?Sized)) -> &'static str {
    match tags.get("station") {
        Some("subway") => "subway",
        Some("light_rail") => "light_rail",
        Some("tram") => "tram",
        Some("monorail") => "monorail",
        _ => match tags.get("railway") {
            Some("halt") => "halt",
            _ => "station",
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn is_route(pairs: &[(&str, &str)]) -> bool {
        super::is_transit_route(pairs)
    }

    fn color_of(pairs: &[(&str, &str)]) -> Option<u32> {
        super::parse_color(pairs)
    }

    fn station_mode(pairs: &[(&str, &str)]) -> &'static str {
        super::station_detail(pairs)
    }

    #[test]
    fn only_rail_route_relations_count() {
        assert!(is_route(&[("type", "route"), ("route", "subway")]));
        assert!(is_route(&[("type", "route"), ("route", "train")]));
        assert!(!is_route(&[("type", "route"), ("route", "bus")]), "buses are not rail");
        assert!(!is_route(&[("type", "route"), ("route", "ferry")]), "nor ferries");
        assert!(!is_route(&[("type", "multipolygon"), ("route", "subway")]), "wrong type");
        assert!(!is_route(&[("route", "subway")]), "no type at all");
        assert!(!is_route(&[]), "nothing");
    }

    #[test]
    fn colours_parse_hex_strictly() {
        assert_eq!(color_of(&[("colour", "#E4002B")]), Some(0xE4002B));
        assert_eq!(color_of(&[("colour", "E4002B")]), Some(0xE4002B), "bare hex");
        assert_eq!(color_of(&[("color", "#f00")]), Some(0xFF0000), "short hex + US spelling");
        assert_eq!(color_of(&[("colour", "red")]), None, "a name is not a colour");
        assert_eq!(color_of(&[("colour", "#GGGGGG")]), None, "not hex");
        assert_eq!(color_of(&[]), None, "absent");
        // `colour` wins over `color` when both are present.
        assert_eq!(
            color_of(&[("colour", "#111111"), ("color", "#222222")]),
            Some(0x111111)
        );
    }

    #[test]
    fn every_mode_has_a_class_and_a_fallback() {
        for (mode, _) in ROUTE_MODES {
            let class = transit_class(mode).expect("a class");
            assert_eq!(class.layer, dict::LAYER_TRANSIT);
            assert_eq!(dict::KINDS[class.kind as usize - 1], "rail");
            assert!(!class.area, "a line, even looped");
        }
        assert!(transit_class("bus").is_none());
        // Fallbacks are opaque brights, never zero (zero means "no colour" on the wire).
        for (mode, _) in ROUTE_MODES {
            assert_ne!(fallback_color(mode), 0, "{mode}");
        }
    }

    #[test]
    fn colour_assignment_is_explicit_first_then_stable() {
        // Two relations share way 7: the explicit colour wins regardless of id order.
        let routes = vec![
            (20, "tram", None, vec![7, 8]),
            (10, "subway", Some(0x111111), vec![7, 9]),
        ];
        let mut got = assign_colors(&routes);
        got.sort_unstable();
        assert_eq!(
            got,
            vec![(7, 0x111111, "subway"), (8, fallback_color("tram"), "tram"), (9, 0x111111, "subway")]
        );
        // Same input twice is the same output: id order, not hash order.
        let mut again = assign_colors(&routes);
        again.sort_unstable();
        assert_eq!(got, again);
    }

    #[test]
    fn stations_name_their_mode() {
        assert_eq!(station_mode(&[("railway", "station")]), "station");
        assert_eq!(station_mode(&[("railway", "halt")]), "halt");
        assert_eq!(
            station_mode(&[("railway", "station"), ("station", "subway")]),
            "subway"
        );
        assert_eq!(
            station_mode(&[("station", "light_rail")]),
            "light_rail",
            "a station tag alone counts"
        );
    }
}
