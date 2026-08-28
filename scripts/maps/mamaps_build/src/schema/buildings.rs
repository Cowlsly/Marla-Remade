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

use tilecodec::mamaps::dict::LAYER_BUILDINGS;

use super::{kind, Class, TagSource};

/// The pre-screen.
pub const FILTERS: &[&str] = &["building", "building:part"];

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
}
