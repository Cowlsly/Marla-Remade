//! `buildings`: the densest layer in the schema, and the simplest to classify.
//!
//! Two kinds and almost no judgement — `building=*` is a building unless it says `no`. What makes it
//! the expensive layer is volume: a city tile holds thousands, and California holds millions. So the
//! style keeps it off below z14 and the schema does not carry it at all above that floor, which is
//! the same decision made twice for two different reasons — the style's is a draw-call cost, this
//! one is archive bytes.
//!
//! `building:part` is a separate kind because a 3D model tags the parts of a building separately
//! from its footprint, and drawing both means drawing the courtyard of a building twice.

use tilecodec::mamaps::body::{
    BuildingAttrs, ROOF_DOME, ROOF_FLAT, ROOF_GABLED, ROOF_HIPPED, ROOF_ORIENT_ACROSS,
    ROOF_ORIENT_ALONG, ROOF_PYRAMIDAL, ROOF_SKILLION,
};
use tilecodec::mamaps::dict::LAYER_BUILDINGS;

use super::{kind, Class, TagSource};

/// The pre-screen.
///
/// A superset of the keys the rules and [`attrs`] read: `building`/`building:part` decide the
/// classification, and the rest carry the S3DB attributes [`attrs`] parses. Listing the attribute
/// keys keeps a building's height and roof tags through the `osmium tags-filter` extract step, so
/// they are still present when [`attrs`] runs — a building that passed the screen on `building`
/// alone would otherwise arrive stripped of everything the 3D extrusion needs.
pub const FILTERS: &[&str] = &[
    "building",
    "building:part",
    "height",
    "building:levels",
    "building:min_level",
    "min_height",
    "roof:shape",
    "roof:height",
    "roof:levels",
    "roof:direction",
    "roof:orientation",
    "building:colour",
    "roof:colour",
    "building:material",
    "roof:material",
];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &["building", "building_part"];

/// The shallowest zoom a building is worth carrying at.
///
/// Matches the style's own floor for the `buildings` layer, so the archive holds nothing the
/// renderer would not draw. The two are independent decisions that happen to agree, and
/// `the_schema_floor_matches_the_styles` in the crate root is what keeps them agreeing.
pub const MIN_ZOOM: u8 = 14;

pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    // A part first: a way carrying both is a part of a larger footprint, and drawing it as a
    // footprint too would double-paint the courtyard.
    if tags.truthy("building:part") {
        return Some(Class::area(LAYER_BUILDINGS, kind("building_part"), MIN_ZOOM));
    }
    if tags.truthy("building") {
        return Some(Class::area(LAYER_BUILDINGS, kind("building"), MIN_ZOOM));
    }
    None
}

/// Roughly the height of one storey, in metres. OSM's own default for `building:levels` when a
/// building carries no explicit `height`, and what the S3DB wiki suggests. Rooms are ~2.5 m but a
/// storey includes floor and ceiling, so 3 m is the conventional figure the renderer expects back.
const METRES_PER_LEVEL: f64 = 3.0;

/// The S3DB attributes of a building, parsed from its tags — the build half of the 3D extrusion.
///
/// Called by [`crate::extract`] on any feature the classifier put in [`LAYER_BUILDINGS`], for both
/// `building` footprints and `building:part` parts. Every field falls back gracefully: a building
/// with no S3DB tags at all yields [`BuildingAttrs::default`], which the renderer extrudes as a
/// flat box at its own default height. Heights are stored in decimetres (0.1 m); directions are
/// quantised to a byte; colours resolve a CSS/named colour or, failing that, a `*:material`.
pub fn attrs(tags: &(impl TagSource + ?Sized)) -> BuildingAttrs {
    // `height` wins over `building:levels`; a building with neither leaves 0 for the renderer's
    // own default rather than guessing a storey count.
    let height = metres_dm(tags, "height")
        .or_else(|| levels_dm(tags, "building:levels"))
        .unwrap_or(0);
    let min_height = metres_dm(tags, "min_height")
        .or_else(|| levels_dm(tags, "building:min_level"))
        .unwrap_or(0);
    let roof_height =
        metres_dm(tags, "roof:height").or_else(|| levels_dm(tags, "roof:levels")).unwrap_or(0);
    let building_colour = tags
        .get("building:colour")
        .and_then(parse_colour)
        .or_else(|| tags.get("building:material").and_then(material_colour))
        .unwrap_or(0);
    let roof_colour = tags
        .get("roof:colour")
        .and_then(parse_colour)
        .or_else(|| tags.get("roof:material").and_then(material_colour))
        .unwrap_or(0);
    BuildingAttrs {
        height,
        min_height,
        roof_height,
        roof_shape: roof_shape(tags.get("roof:shape")),
        roof_direction: roof_direction(tags.get("roof:direction")),
        roof_orientation: match tags.get("roof:orientation") {
            Some("across") => ROOF_ORIENT_ACROSS,
            _ => ROOF_ORIENT_ALONG,
        },
        building_colour,
        roof_colour,
    }
}

/// A metre value (`12`, `12.5`, `12 m`) as decimetres, clamped to what a `u16` holds (6553.5 m,
/// past the tallest building on earth). `None` when the tag is absent or not a number.
fn metres_dm(tags: &(impl TagSource + ?Sized), key: &str) -> Option<u16> {
    let raw = tags.get(key)?;
    let metres = parse_leading_f64(raw)?;
    dm_of(metres)
}

/// A level count (`building:levels=3`) as a height in decimetres, at [`METRES_PER_LEVEL`] each.
fn levels_dm(tags: &(impl TagSource + ?Sized), key: &str) -> Option<u16> {
    let levels = parse_leading_f64(tags.get(key)?)?;
    dm_of(levels * METRES_PER_LEVEL)
}

/// Metres to decimetres, rounded and clamped to `u16`; negative or non-finite yields `None`.
fn dm_of(metres: f64) -> Option<u16> {
    if !metres.is_finite() || metres < 0.0 {
        return None;
    }
    Some((metres * 10.0).round().min(u16::MAX as f64) as u16)
}

/// The leading number of a value, ignoring a trailing unit like ` m`. OSM height tags are metres
/// by convention; a stray `ft` is rare enough to accept the bare number rather than convert.
fn parse_leading_f64(raw: &str) -> Option<f64> {
    let trimmed = raw.trim();
    let end = trimmed
        .find(|c: char| !(c.is_ascii_digit() || c == '.' || c == '-' || c == '+'))
        .unwrap_or(trimmed.len());
    trimmed[..end].parse::<f64>().ok()
}

/// An OSM `roof:shape` mapped to one of the six the renderer models. Anything else — round,
/// gambrel, mansard, sawtooth — falls back to flat, which is the plan's stated behaviour: a shape
/// the extruder cannot build is drawn as a flat cap rather than guessed at.
fn roof_shape(value: Option<&str>) -> u8 {
    match value {
        Some("gabled") => ROOF_GABLED,
        Some("hipped") => ROOF_HIPPED,
        Some("pyramidal") => ROOF_PYRAMIDAL,
        Some("skillion") => ROOF_SKILLION,
        Some("dome") => ROOF_DOME,
        _ => ROOF_FLAT,
    }
}

/// A `roof:direction` (degrees, or a compass point like `NE`) quantised to a byte: `deg * 256 /
/// 360`, which the renderer decodes as `v * 360 / 256`. Zero (north) for an absent or unparseable
/// value, which is the same default a flat roof carries anyway.
fn roof_direction(value: Option<&str>) -> u8 {
    let Some(raw) = value else { return 0 };
    let degrees = compass_degrees(raw.trim()).or_else(|| parse_leading_f64(raw)).unwrap_or(0.0);
    // Wrap into 0..360 before quantising, so 720 and -90 land where a compass would put them.
    let wrapped = degrees.rem_euclid(360.0);
    ((wrapped / 360.0 * 256.0).round() as i64).rem_euclid(256) as u8
}

/// The eight- and sixteen-point compass abbreviations OSM allows in a direction tag, in degrees.
fn compass_degrees(raw: &str) -> Option<f64> {
    let d = match raw.to_ascii_uppercase().as_str() {
        "N" => 0.0,
        "NNE" => 22.5,
        "NE" => 45.0,
        "ENE" => 67.5,
        "E" => 90.0,
        "ESE" => 112.5,
        "SE" => 135.0,
        "SSE" => 157.5,
        "S" => 180.0,
        "SSW" => 202.5,
        "SW" => 225.0,
        "WSW" => 247.5,
        "W" => 270.0,
        "WNW" => 292.5,
        "NW" => 315.0,
        "NNW" => 337.5,
        _ => return None,
    };
    Some(d)
}

/// A CSS/OSM colour — `#rgb`, `#rrggbb`, or a common colour name — as opaque `0xAARRGGBB`.
/// `None` for anything unrecognised, so the caller can fall back to a material.
fn parse_colour(raw: &str) -> Option<u32> {
    let s = raw.trim();
    if let Some(hex) = s.strip_prefix('#') {
        let rgb = match hex.len() {
            3 => {
                let mut v = 0u32;
                for c in hex.chars() {
                    let n = c.to_digit(16)?;
                    // Each nibble is doubled: #abc is #aabbcc.
                    v = (v << 8) | (n * 17);
                }
                v
            }
            6 => u32::from_str_radix(hex, 16).ok()?,
            _ => return None,
        };
        return Some(0xFF00_0000 | rgb);
    }
    named_colour(&s.to_ascii_lowercase()).map(|rgb| 0xFF00_0000 | rgb)
}

/// The common CSS/OSM colour names that actually appear on buildings, as `0xRRGGBB`.
///
/// Not the full 147-name CSS set: the long tail never appears on a `building:colour`, and a name
/// this misses falls through to the material palette or to "no colour", both of which are fine.
fn named_colour(name: &str) -> Option<u32> {
    let rgb = match name {
        "white" => 0xFFFFFF,
        "black" => 0x000000,
        "gray" | "grey" => 0x808080,
        "silver" => 0xC0C0C0,
        "lightgray" | "lightgrey" => 0xD3D3D3,
        "darkgray" | "darkgrey" => 0xA9A9A9,
        "red" => 0xFF0000,
        "darkred" => 0x8B0000,
        "maroon" => 0x800000,
        "brown" => 0xA52A2A,
        "sienna" => 0xA0522D,
        "orange" => 0xFFA500,
        "yellow" => 0xFFFF00,
        "gold" => 0xFFD700,
        "beige" => 0xF5F5DC,
        "cream" => 0xFFFDD0,
        "tan" => 0xD2B48C,
        "green" => 0x008000,
        "darkgreen" => 0x006400,
        "olive" => 0x808000,
        "lightgreen" => 0x90EE90,
        "blue" => 0x0000FF,
        "lightblue" => 0xADD8E6,
        "navy" => 0x000080,
        "cyan" => 0x00FFFF,
        "teal" => 0x008080,
        "purple" => 0x800080,
        "pink" => 0xFFC0CB,
        "terracotta" => 0xE2725B,
        _ => return None,
    };
    Some(rgb)
}

/// A `building:material` or `roof:material` mapped to a representative colour, as opaque
/// `0xAARRGGBB`. The palette a renderer falls back to when a building states its material but not
/// its colour, which is the common case on 3D-mapped cities.
fn material_colour(material: &str) -> Option<u32> {
    let rgb = match material.trim().to_ascii_lowercase().as_str() {
        "brick" | "brick_wall" => 0xB0_4A_39,
        "concrete" | "cement_block" => 0xB0_B0_B0,
        "glass" | "mirror" => 0xB8_D0_E0,
        "wood" | "timber_framing" => 0x9B_6B_43,
        "stone" | "sandstone" | "limestone" => 0xC8_BE_A8,
        "marble" => 0xF0_EE_E6,
        "metal" | "metal_sheet" | "steel" => 0xB4_B8_BC,
        "copper" => 0x7A_9E_8E,
        "plaster" | "stucco" | "render" => 0xE8_E0_D0,
        "roof_tiles" | "tile" | "tiles" | "clay" => 0xB5_6A_4A,
        "slate" => 0x50_54_5A,
        "tar_paper" | "asphalt" | "bitumen" => 0x3A_3A_3E,
        "grass" | "green_roof" => 0x6E_8B_3D,
        "gravel" => 0x9A_94_88,
        _ => return None,
    };
    Some(0xFF00_0000 | rgb)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    fn kind_name(class: &Class) -> &'static str {
        dict::KINDS[class.kind as usize - 1]
    }

    #[test]
    fn any_building_value_but_no_is_a_building() {
        // OSM tags a building's *type* in the same key, so the value is not a yes/no in practice:
        // `building=house`, `building=church`, `building=yes` are all buildings.
        for value in ["yes", "house", "church", "industrial", "retail", "1", "true"] {
            let class = classify_tags(&[("building", value)]).expect(value);
            assert_eq!(class.layer, dict::LAYER_BUILDINGS);
            assert_eq!(kind_name(&class), "building");
            assert!(class.area, "a building is an area");
            assert_eq!(class.min_zoom, MIN_ZOOM);
        }
    }

    #[test]
    fn building_no_is_not_a_building() {
        // A real tag: `building=no` marks a way that looks like a building and is not.
        assert!(classify_tags(&[("building", "no")]).is_none());
        assert!(classify_tags(&[("building", "false")]).is_none());
        assert!(classify_tags(&[("building", "0")]).is_none());
    }

    /// A 3D model tags parts separately from the footprint. Both are drawn, so they need different
    /// kinds — otherwise a building with parts paints its courtyard twice.
    #[test]
    fn a_part_is_its_own_kind_and_wins_over_the_footprint() {
        let part = classify_tags(&[("building:part", "yes")]).expect("part");
        assert_eq!(kind_name(&part), "building_part");
        let both = classify_tags(&[("building", "yes"), ("building:part", "yes")]).expect("both");
        assert_eq!(kind_name(&both), "building_part", "the part rule is asked first");
    }

    #[test]
    fn nothing_else_is_a_building() {
        for tags in [
            vec![("natural", "water")],
            vec![("highway", "residential")],
            vec![("building:levels", "3")],
            vec![],
        ] {
            assert!(classify_tags(&tags).is_none(), "{tags:?} should not be a building");
        }
    }

    /// The densest layer in the schema stays off the mid zooms. Without a floor, a z10 tile of a
    /// city is megabytes of footprints nothing draws.
    #[test]
    fn buildings_are_not_carried_below_street_zoom() {
        assert_eq!(MIN_ZOOM, 14);
        let class = classify_tags(&[("building", "yes")]).expect("building");
        assert_eq!(class.min_zoom, 14);
    }

    fn attrs_of(pairs: &[(&str, &str)]) -> BuildingAttrs {
        super::attrs(pairs)
    }

    #[test]
    fn a_plain_building_has_default_attrs() {
        // No S3DB tags at all: a flat box at the renderer's own default height, no colours.
        let a = attrs_of(&[("building", "yes")]);
        assert_eq!(a, BuildingAttrs::default());
        assert_eq!(a.height, 0, "0 means the renderer picks a default");
        assert_eq!(a.roof_shape, ROOF_FLAT);
    }

    #[test]
    fn height_is_metres_in_decimetres_and_levels_are_the_fallback() {
        // An explicit height wins and carries its fraction, stored as decimetres.
        assert_eq!(attrs_of(&[("building", "yes"), ("height", "12.5")]).height, 125);
        // A trailing unit is ignored — the number is metres by convention.
        assert_eq!(attrs_of(&[("building", "yes"), ("height", "20 m")]).height, 200);
        // Levels are the fallback at 3 m each, only when there is no explicit height.
        assert_eq!(attrs_of(&[("building", "yes"), ("building:levels", "4")]).height, 120);
        let both = attrs_of(&[("building", "yes"), ("height", "9"), ("building:levels", "4")]);
        assert_eq!(both.height, 90, "an explicit height wins over the level count");
    }

    #[test]
    fn min_height_and_roof_height_parse_with_their_level_fallbacks() {
        let a = attrs_of(&[
            ("building:part", "yes"),
            ("min_height", "8"),
            ("roof:height", "3.5"),
        ]);
        assert_eq!(a.min_height, 80);
        assert_eq!(a.roof_height, 35);
        let levels = attrs_of(&[
            ("building:part", "yes"),
            ("building:min_level", "2"),
            ("roof:levels", "1"),
        ]);
        assert_eq!(levels.min_height, 60, "two levels up at 3 m each");
        assert_eq!(levels.roof_height, 30);
    }

    #[test]
    fn roof_shapes_map_and_unknown_falls_back_to_flat() {
        for (tag, want) in [
            ("flat", ROOF_FLAT),
            ("gabled", ROOF_GABLED),
            ("hipped", ROOF_HIPPED),
            ("pyramidal", ROOF_PYRAMIDAL),
            ("skillion", ROOF_SKILLION),
            ("dome", ROOF_DOME),
            // Shapes the extruder does not model fall back to flat rather than guessing.
            ("gambrel", ROOF_FLAT),
            ("mansard", ROOF_FLAT),
            ("round", ROOF_FLAT),
        ] {
            assert_eq!(
                attrs_of(&[("building", "yes"), ("roof:shape", tag)]).roof_shape,
                want,
                "roof:shape={tag}",
            );
        }
    }

    #[test]
    fn roof_direction_takes_degrees_and_compass_points() {
        // 90° east quantises to a quarter of 256.
        assert_eq!(attrs_of(&[("building", "yes"), ("roof:direction", "90")]).roof_direction, 64);
        assert_eq!(attrs_of(&[("building", "yes"), ("roof:direction", "E")]).roof_direction, 64);
        assert_eq!(attrs_of(&[("building", "yes"), ("roof:direction", "N")]).roof_direction, 0);
        // A value past 360 wraps like a compass rather than clamping.
        assert_eq!(attrs_of(&[("building", "yes"), ("roof:direction", "450")]).roof_direction, 64);
        assert_eq!(
            attrs_of(&[("building", "yes"), ("roof:orientation", "across")]).roof_orientation,
            ROOF_ORIENT_ACROSS,
        );
        assert_eq!(
            attrs_of(&[("building", "yes")]).roof_orientation,
            ROOF_ORIENT_ALONG,
            "the default orientation is along",
        );
    }

    #[test]
    fn colours_parse_hex_and_names_with_a_material_fallback() {
        // Hex, short and long, opaque.
        assert_eq!(
            attrs_of(&[("building", "yes"), ("building:colour", "#ffcc00")]).building_colour,
            0xFF_FF_CC_00,
        );
        assert_eq!(
            attrs_of(&[("building", "yes"), ("building:colour", "#fc0")]).building_colour,
            0xFF_FF_CC_00,
            "a three-digit hex doubles each nibble",
        );
        // A named colour.
        assert_eq!(
            attrs_of(&[("building", "yes"), ("roof:colour", "red")]).roof_colour,
            0xFF_FF_00_00,
        );
        // No colour, but a material: the palette fills in.
        let brick = attrs_of(&[("building", "yes"), ("building:material", "brick")]);
        assert_ne!(brick.building_colour, 0, "a material resolves to a palette colour");
        assert_eq!(brick.building_colour >> 24, 0xFF, "and it is opaque");
        // An explicit colour beats the material.
        let both = attrs_of(&[
            ("building", "yes"),
            ("building:colour", "white"),
            ("building:material", "brick"),
        ]);
        assert_eq!(both.building_colour, 0xFF_FF_FF_FF, "the colour wins over the material");
        // No colour and no material: absent, so the style default is used.
        assert_eq!(attrs_of(&[("building", "yes")]).building_colour, 0);
    }

    #[test]
    fn a_giant_height_is_clamped_rather_than_wrapping() {
        // A vandalised height must not wrap a u16 into a short building.
        let a = attrs_of(&[("building", "yes"), ("height", "999999")]);
        assert_eq!(a.height, u16::MAX);
        // A negative height is nonsense and drops to the default rather than a huge unsigned value.
        assert_eq!(attrs_of(&[("building", "yes"), ("height", "-5")]).height, 0);
    }
}
