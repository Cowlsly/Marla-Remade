//! `poi`: icons — the points MapLibre draws symbols for.
//!
//! The set is exactly the reference style's `pois` layer filter (37 kinds matched by `kind`
//! with a per-feature `min_zoom`): beaches to theatres. Each feature carries its `kind` as the
//! icon key — the names equal the v4/light sprite names where they overlap, so the renderer
//! resolves the image straight from the kind — and a coalesced display name (`name:en` →
//! `name`) for the label beneath the icon.
//!
//! # Source tags
//!
//! Upstream's POI kinds come from every corner of the tag space (`amenity`, `shop`, `leisure`,
//! `tourism`, `aeroway`, `railway`, `natural`, ...). This maps each sprite kind back to the OSM
//! tags that mean it, so the tiler emits only kinds the style draws: anything else (a bare
//! `amenity=yes`, a `shop=vacant`) is `None`.
//!
//! # Zoom
//!
//! The style gates each POI on its own `min_zoom` (`zoom >= min_zoom`): stations and airports
//! early, benches and toilets late. The floors here mirror that intent — transport and major
//! attractions first, amenities by size, street furniture last.

use tilecodec::mamaps::dict::LAYER_POI;

use super::{kind, Class, TagSource};

pub const FILTERS: &[&str] = &[
    "amenity",
    "shop",
    "leisure",
    "tourism",
    "aeroway",
    "railway",
    "natural",
    "highway",
    "building",
    "station",
    "name",
    "name:en",
];

/// Every `kind` this module can emit: exactly the reference style's `pois` filter set.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &[
    "beach",
    "forest",
    "marina",
    "park",
    "peak",
    "zoo",
    "garden",
    "bench",
    "aerodrome",
    "station",
    "bus_stop",
    "ferry_terminal",
    "stadium",
    "university",
    "library",
    "school",
    "animal",
    "toilets",
    "drinking_water",
    "post_office",
    "building",
    "townhall",
    "restaurant",
    "fast_food",
    "cafe",
    "bar",
    "supermarket",
    "convenience",
    "books",
    "beauty",
    "electronics",
    "clothes",
    "attraction",
    "museum",
    "theatre",
    "artwork",
];

/// A POI's kind and floor, from its tags; `None` for anything the style does not draw.
///
/// Floors are clamped to z14: builds cap at z14 and the renderer overzooms the deepest tile, so
/// a bench gated at z16 would otherwise be carried nowhere and never drawn.
pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    let (kind_name, min_zoom) = poi_kind(tags)?;
    let class = Class {
        layer: LAYER_POI,
        kind: kind(kind_name),
        kind_detail: tilecodec::mamaps::dict::NONE,
        flags: 0,
        // A POI is a point even when mapped as an area (a museum building, a park polygon):
        // extract centroids it, and the icon anchors at one coordinate.
        area: false,
        min_zoom: min_zoom.min(14),
        min_area_px: 0.0,
    };
    // Stations name their mode in the detail: `poi` kind `station` + a station detail is the
    // transit-station tag the renderer reads with POIs off and Transit on. Nothing else on the
    // layer carries a detail, so the field's presence alone is the signal.
    if kind_name == "station" {
        Some(Class {
            kind_detail: super::detail(super::transit::station_detail(tags)),
            ..class
        })
    } else {
        Some(class)
    }
}

/// The display name for a POI: `name:en` preferred, `name` as fallback, like `places`.
pub fn display_name(tags: &(impl TagSource + ?Sized)) -> Option<String> {
    super::places::display_name(tags)
}

/// Map OSM tags to one of the style's 37 POI kinds. Ordered so the specific wins over the
/// generic: a `tourism=museum` inside a `building=yes` is a museum, not a building.
fn poi_kind(tags: &(impl TagSource + ?Sized)) -> Option<(&'static str, u8)> {
    // Transport first: the earliest icons on the map.
    if tags.get("aeroway") == Some("aerodrome") {
        return Some(("aerodrome", 10));
    }
    if matches!(tags.get("railway"), Some("station" | "halt")) {
        return Some(("station", 11));
    }
    // A bare `station=` tag (no `railway=*`): an underground concourse mapped without the halt.
    if matches!(tags.get("station"), Some("subway" | "light_rail" | "tram" | "monorail")) {
        return Some(("station", 11));
    }
    if tags.get("highway") == Some("bus_stop") {
        return Some(("bus_stop", 13));
    }
    // A street tram stop. `railway=tram_stop` is the usual tagging and was missing entirely, so
    // tram stops existed only where a mapper had also tagged `station=tram` on a concourse — which
    // is the exception. `public_transport=platform` is deliberately not used as a fallback: it is
    // on bus shelters, rail platforms and ferry piers alike, and would multiply every stop by the
    // number of platforms it has.
    if matches!(tags.get("railway"), Some("tram_stop" | "halt_tram")) {
        return Some(("bus_stop", 13));
    }
    if tags.get("amenity") == Some("ferry_terminal") {
        return Some(("ferry_terminal", 11));
    }
    // Fuel is a driver's landmark, so it appears with the shops rather than the street furniture.
    if tags.get("amenity") == Some("fuel") {
        return Some(("fuel", 12));
    }
    // Nature and outdoors.
    match tags.get("natural") {
        Some("beach") => return Some(("beach", 12)),
        Some("peak") => return Some(("peak", 11)),
        Some("wood" | "forest") => return Some(("forest", 12)),
        _ => {}
    }
    if tags.get("leisure") == Some("marina") {
        return Some(("marina", 12));
    }
    if matches!(tags.get("leisure"), Some("park" | "garden")) {
        // `leisure=park` is a park, `leisure=garden` a garden; both green on the map.
        return Some((if tags.get("leisure") == Some("park") { "park" } else { "garden" }, 12));
    }
    if matches!(tags.get("tourism"), Some("zoo" | "aquarium")) {
        return Some(("zoo", 12));
    }
    if tags.get("tourism") == Some("attraction") {
        return Some(("attraction", 13));
    }
    if matches!(tags.get("tourism"), Some("museum" | "gallery")) {
        return Some(("museum", 13));
    }
    // Lodging. `guest_house` is included because OSM uses it for the small end of the same thing;
    // the app's Hotels chip means "somewhere to sleep", not "a building tagged tourism=hotel".
    if matches!(tags.get("tourism"), Some("hotel" | "motel" | "hostel" | "guest_house")) {
        return Some(("hotel", 12));
    }
    if tags.get("tourism") == Some("artwork") {
        return Some(("artwork", 14));
    }
    if tags.get("tourism") == Some("viewpoint") {
        return Some(("attraction", 13));
    }
    // Sport, school, civic.
    if matches!(tags.get("leisure"), Some("stadium" | "sports_centre" | "pitch")) {
        return Some(("stadium", 13));
    }
    if matches!(tags.get("amenity"), Some("university" | "college")) {
        return Some(("university", 13));
    }
    if matches!(tags.get("amenity"), Some("school" | "kindergarten")) {
        return Some(("school", 13));
    }
    if tags.get("amenity") == Some("library") {
        return Some(("library", 13));
    }
    if matches!(tags.get("amenity"), Some("townhall" | "courthouse" | "embassy")) {
        return Some(("townhall", 13));
    }
    if tags.get("amenity") == Some("post_office") {
        return Some(("post_office", 13));
    }
    // Food and shops. The kinds a category chip selects are carried from **12**, two levels
    // shallower than they are ever drawn while browsing. That gap is the point: the style gives
    // `poi-food` and `poi-shop` a `browse_minzoom` of 14, so the ambient map is unchanged, and
    // `Layer::draws_at_focused` drops to this floor for whichever kinds the active chip names.
    // Tapping "Restaurants" therefore reaches four times the area rather than showing the same
    // screenful with everything else removed.
    //
    // It costs archive size — these are the numerous kinds, and each extra level is another copy
    // of every one of them — which is why it stops at the chip kinds and at 12.
    if tags.get("amenity") == Some("restaurant") {
        return Some(("restaurant", 12));
    }
    if tags.get("amenity") == Some("fast_food") {
        return Some(("fast_food", 12));
    }
    if matches!(tags.get("amenity"), Some("cafe" | "ice_cream")) {
        return Some(("cafe", 12));
    }
    if matches!(tags.get("amenity"), Some("bar" | "pub" | "biergarten" | "nightclub")) {
        return Some(("bar", 13));
    }
    if tags.get("shop") == Some("supermarket") {
        return Some(("supermarket", 12));
    }
    if matches!(tags.get("shop"), Some("convenience" | "kiosk")) {
        return Some(("convenience", 12));
    }
    if tags.get("shop") == Some("books") {
        return Some(("books", 14));
    }
    if matches!(tags.get("shop"), Some("beauty" | "hairdresser" | "cosmetics")) {
        return Some(("beauty", 14));
    }
    if matches!(tags.get("shop"), Some("electronics" | "computer" | "mobile_phone")) {
        return Some(("electronics", 14));
    }
    if matches!(tags.get("shop"), Some("clothes" | "shoes" | "fashion")) {
        return Some(("clothes", 14));
    }
    // Money. `bank` is separate from `atm` and drawn earlier because a branch is a landmark and a
    // machine is not — but the app's ATM chip selects both, since most branches have a machine and
    // OSM frequently tags only the branch.
    if tags.get("amenity") == Some("bank") {
        return Some(("bank", 12));
    }
    // Not 12 like the rest of the ATM chip: there are a great many cash machines and most sit
    // inside a bank that is already carried. The chip selects `bank` as well, so it still finds
    // something two levels out.
    if tags.get("amenity") == Some("atm") {
        return Some(("atm", 14));
    }
    // Street furniture and the small stuff, last.
    if matches!(tags.get("amenity"), Some("bench" | "shelter")) {
        return Some(("bench", 16));
    }
    if matches!(tags.get("amenity"), Some("toilets" | "shower")) {
        return Some(("toilets", 16));
    }
    if tags.get("amenity") == Some("drinking_water") {
        return Some(("drinking_water", 16));
    }
    if matches!(tags.get("amenity"), Some("veterinary" | "animal_shelter")) {
        return Some(("animal", 15));
    }
    // Culture that is a building first in OSM: a theatre is `amenity=theatre`, and a bare
    // `building=yes` with a name is still something the style draws a `building` POI for.
    if tags.get("amenity") == Some("theatre") {
        return Some(("theatre", 14));
    }
    if tags.get("building").is_some_and(|v| v != "no") && display_name(tags).is_some() {
        return Some(("building", 15));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    fn kind_of(pairs: &[(&str, &str)]) -> String {
        let class = classify_tags(pairs).expect("a poi");
        dict::KINDS[class.kind as usize - 1].to_string()
    }

    fn zoom_of(pairs: &[(&str, &str)]) -> u8 {
        classify_tags(pairs).expect("a poi").min_zoom
    }

    /// A street tram stop is `railway=tram_stop`. It was matched by nothing, so trams only had a
    /// stop where a mapper had *also* tagged a `station=tram` concourse — the exception, not the
    /// rule, and the reason tram stops appeared to be missing from the map entirely.
    #[test]
    fn a_street_tram_stop_is_a_stop() {
        assert_eq!(kind_of(&[("railway", "tram_stop")]), "bus_stop");
        assert_eq!(zoom_of(&[("railway", "tram_stop")]), 13);
        // The concourse form still works, and still reads as a station.
        assert_eq!(kind_of(&[("station", "tram")]), "station");
    }

    /// A category chip is useless if its results only exist at the very deepest zoom. Everything a
    /// person would search for is carried from 13.
    #[test]
    fn anything_worth_searching_for_is_carried_from_zoom_13() {
        for tags in [
            vec![("amenity", "restaurant")],
            vec![("amenity", "fast_food")],
            vec![("amenity", "cafe")],
            vec![("amenity", "bar")],
            vec![("amenity", "fuel")],
            vec![("shop", "supermarket")],
            vec![("tourism", "hotel")],
            vec![("highway", "bus_stop")],
            vec![("railway", "tram_stop")],
        ] {
            assert!(
                zoom_of(&tags) <= 13,
                "{tags:?} should be carried by z13, is {}",
                zoom_of(&tags),
            );
        }
    }

    /// The kinds a category chip selects are carried two levels shallower than they are drawn
    /// while browsing, so tapping a chip reaches further out rather than just removing everything
    /// else from the same screenful. The style holds the browsing floor (`browse_minzoom`); this
    /// holds the data floor.
    #[test]
    fn a_chip_kind_is_carried_two_levels_before_it_is_drawn() {
        for tags in [
            vec![("amenity", "restaurant")],
            vec![("amenity", "fast_food")],
            vec![("amenity", "cafe")],
            vec![("amenity", "fuel")],
            vec![("shop", "supermarket")],
            vec![("shop", "convenience")],
            vec![("tourism", "hotel")],
            vec![("amenity", "bank")],
        ] {
            assert_eq!(zoom_of(&tags), 12, "{tags:?} is a chip kind and should be carried at z12");
        }
    }

    /// And the limit: street furniture stays at the deepest level. A bench two zooms out is noise,
    /// and there are a great many of them.
    #[test]
    fn street_furniture_stays_at_the_deepest_level() {
        for tags in [
            vec![("amenity", "bench")],
            vec![("amenity", "toilets")],
            vec![("amenity", "atm")],
        ] {
            assert_eq!(zoom_of(&tags), 14, "{tags:?} should stay at z14");
        }
    }

    #[test]
    fn every_style_poi_kind_is_reachable() {
        // The style draws 41 kinds; each must be producible from real OSM tags, or the tiler
        // ships a sprite name nothing ever carries.
        for (tags, want) in [
            (vec![("natural", "beach")], "beach"),
            (vec![("natural", "wood")], "forest"),
            (vec![("leisure", "marina")], "marina"),
            (vec![("leisure", "park")], "park"),
            (vec![("natural", "peak")], "peak"),
            (vec![("tourism", "zoo")], "zoo"),
            (vec![("leisure", "garden")], "garden"),
            (vec![("amenity", "bench")], "bench"),
            (vec![("aeroway", "aerodrome")], "aerodrome"),
            (vec![("railway", "station")], "station"),
            (vec![("highway", "bus_stop")], "bus_stop"),
            (vec![("amenity", "ferry_terminal")], "ferry_terminal"),
            (vec![("leisure", "stadium")], "stadium"),
            (vec![("amenity", "university")], "university"),
            (vec![("amenity", "library")], "library"),
            (vec![("amenity", "school")], "school"),
            (vec![("amenity", "veterinary")], "animal"),
            (vec![("amenity", "toilets")], "toilets"),
            (vec![("amenity", "drinking_water")], "drinking_water"),
            (vec![("amenity", "post_office")], "post_office"),
            (vec![("building", "yes"), ("name", "Empire State Building")], "building"),
            (vec![("amenity", "townhall")], "townhall"),
            (vec![("amenity", "restaurant")], "restaurant"),
            (vec![("amenity", "fast_food")], "fast_food"),
            (vec![("amenity", "cafe")], "cafe"),
            (vec![("amenity", "bar")], "bar"),
            (vec![("shop", "supermarket")], "supermarket"),
            (vec![("shop", "convenience")], "convenience"),
            (vec![("shop", "books")], "books"),
            (vec![("shop", "beauty")], "beauty"),
            (vec![("shop", "electronics")], "electronics"),
            (vec![("shop", "clothes")], "clothes"),
            (vec![("tourism", "attraction")], "attraction"),
            (vec![("tourism", "museum")], "museum"),
            (vec![("tourism", "artwork")], "artwork"),
            (vec![("amenity", "fuel")], "fuel"),
            (vec![("tourism", "hotel")], "hotel"),
            (vec![("tourism", "hostel")], "hotel"),
            (vec![("amenity", "atm")], "atm"),
            (vec![("amenity", "bank")], "bank"),
        ] {
            // `theatre` has no clean OSM tag mapping (the style draws `theatre` but OSM tags
            // theatres `amenity=theatre`, handled below) — every other kind must resolve.
            if want == "theatre" {
                continue;
            }
            assert_eq!(kind_of(&tags), want, "{tags:?}");
        }
        // Theatres are `amenity=theatre` in OSM.
        assert_eq!(kind_of(&[("amenity", "theatre")]), "theatre");
    }

    #[test]
    fn transport_is_early_and_benches_are_late() {
        let at = |tags: &[(&str, &str)]| classify_tags(tags).expect("poi").min_zoom;
        assert!(at(&[("aeroway", "aerodrome")]) < at(&[("amenity", "cafe")]));
        assert!(at(&[("railway", "station")]) < at(&[("amenity", "bench")]));
        // Clamped to the deepest build zoom: a bench gated at z16 would be carried nowhere in a
        // z14 build and never drawn (the renderer overzooms the deepest tile).
        assert_eq!(at(&[("amenity", "bench")]), 14);
    }

    #[test]
    fn a_poi_is_a_point_in_the_poi_layer() {
        let class = classify_tags(&[("amenity", "cafe"), ("name", "Blue Bottle")]).expect("cafe");
        assert_eq!(class.layer, dict::LAYER_POI);
        assert!(!class.area, "an icon anchors at one coordinate");
        assert_eq!(display_name(&[("name:en", "Cafe"), ("name", "Café")][..]), Some("Cafe".to_string()));
    }

    #[test]
    fn what_the_style_does_not_draw_is_not_a_poi() {
        for tags in [
            vec![("amenity", "yes")],
            vec![("amenity", "parking")],
            vec![("shop", "vacant")],
            vec![("highway", "residential")],
            vec![],
        ] {
            assert!(classify_tags(&tags).is_none(), "{tags:?} should not be a poi");
        }
    }
}
