//! The tag → kind mapping: OSM tags in, `.mamaps` layer and interned ids out.
//!
//! **The main body of work and the main risk of this whole project.** Planetiler's Protomaps
//! profile is thousands of lines of accumulated judgement, and reproducing it is the thing that
//! takes weeks rather than days. So it lives here, alone, behind one function, with no I/O and no
//! geometry: [`classify`] takes tags and returns a [`Class`] or nothing, which makes every rule in
//! it a unit test rather than a screenshot.
//!
//! # The rules
//!
//! Ordered and **first match wins**, like the `osmium tags-filter` expressions this replaces and
//! like Planetiler's own dispatch. Order is therefore load-bearing: a `natural=water` way that also
//! carries `building=yes` is water, because water is asked first.
//!
//! Every layer is pre-screened by [`osm_ingest::select::Select`] before any rule runs. Tag lookup
//! is a linear scan over a block's string table, so at planet scale it matters a great deal whether
//! the common case reads three tags or thirty.
//!
//! # What a `Class` is not
//!
//! It carries no geometry and no name. Names are the largest thing this format drops — an upstream
//! `water` feature carries forty `name:*` localisations — and nothing in the style reads one.

use tilecodec::mamaps::dict;

pub mod boundaries;
pub mod buildings;
pub mod earth;
pub mod land;
pub mod roads;
pub mod water;

/// Tag lookup, so a rule is a pure function of its tags.
///
/// [`osm_ingest::osm::Tags`] borrows from the PBF block it was decoded out of, which would make
/// every test here a synthetic protobuf. Behind this trait a test is a list of pairs, which is what
/// makes the mapping — the risky part — cheap enough to cover properly.
pub trait TagSource {
    fn get(&self, key: &str) -> Option<&str>;

    fn has(&self, key: &str) -> bool {
        self.get(key).is_some()
    }

    /// Is this tag one of OSM's affirmatives?
    ///
    /// `yes`, `true` and `1` all mean yes, and a bridge tagged `viaduct` is still a bridge. Only
    /// `no` and absence mean no.
    fn truthy(&self, key: &str) -> bool {
        !matches!(self.get(key), None | Some("no") | Some("false") | Some("0"))
    }
}

impl TagSource for osm_ingest::osm::Tags<'_, '_> {
    fn get(&self, key: &str) -> Option<&str> {
        self.get_str(key)
    }
}

/// Tags as a plain list, for tests.
impl TagSource for [(&str, &str)] {
    fn get(&self, key: &str) -> Option<&str> {
        self.iter().find(|(k, _)| *k == key).map(|(_, v)| *v)
    }
}

/// Where a feature goes and what it is, once classified.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Class {
    /// An index into [`dict::LAYERS`].
    pub layer: u8,
    /// An index into [`dict::KINDS`], or [`dict::NONE`].
    pub kind: u16,
    /// An index into [`dict::DETAILS`], a number under [`FLAG_DETAIL_NUMERIC`], or
    /// [`dict::NONE`].
    ///
    /// [`FLAG_DETAIL_NUMERIC`]: tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC
    pub kind_detail: u16,
    pub flags: u8,
    /// Is this an area? Decides whether a closed way becomes a polygon or a line.
    ///
    /// Not derivable from the geometry: a closed way is a ring for a lake and a loop road for a
    /// cul-de-sac, and only the tags say which.
    pub area: bool,
    /// The shallowest zoom this feature is worth carrying at.
    ///
    /// Where most of the upstream profile's judgement lives, and the reason a world tile is not
    /// every pond in California. Compared against the tile's zoom, so a feature simply is not
    /// written above it.
    pub min_zoom: u8,
    /// The smallest drawn area worth carrying, in square pixels of a 256-unit tile.
    ///
    /// Zero for a line and for anything a zoom gate alone separates. What it is for is the case a
    /// zoom cannot fix: a national park and a back garden are both `leisure=park`, and only a size
    /// tells them apart. Converted to the tile's own units by [`land::min_area_units`].
    pub min_area_px: f64,
}

impl Class {
    /// A polygon feature with no detail and no flags.
    pub const fn area(layer: u8, kind: u16, min_zoom: u8) -> Class {
        Class {
            layer,
            kind,
            kind_detail: dict::NONE,
            flags: 0,
            area: true,
            min_zoom,
            min_area_px: 0.0,
        }
    }

    /// A line feature with no detail and no flags.
    pub const fn line(layer: u8, kind: u16, min_zoom: u8) -> Class {
        Class {
            layer,
            kind,
            kind_detail: dict::NONE,
            flags: 0,
            area: false,
            min_zoom,
            min_area_px: 0.0,
        }
    }
}

/// Look up a `kind` name's id at compile-time-ish cost.
///
/// A linear scan over 79 short strings, called once per classified feature. Interning at
/// classification time rather than at encode time is what keeps the encoder free of names entirely.
///
/// Panics on a name the table does not carry, because every name here is a literal in this crate
/// and `every_kind_this_schema_names_is_in_the_dictionary` proves them all.
pub fn kind(name: &str) -> u16 {
    match dict::KINDS.iter().position(|k| *k == name) {
        Some(index) => index as u16 + 1,
        None => panic!("the schema names kind `{name}`, which the dictionary has no id for"),
    }
}

/// Look up a `kind_detail` name's id.
pub fn detail(name: &str) -> u16 {
    match dict::DETAILS.iter().position(|d| *d == name) {
        Some(index) => index as u16 + 1,
        None => panic!("the schema names detail `{name}`, which the dictionary has no id for"),
    }
}

/// Which layers a build is producing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Layers {
    pub earth: bool,
    pub water: bool,
    pub buildings: bool,
    pub roads: bool,
    pub boundaries: bool,
    pub landcover: bool,
    pub landuse: bool,
}

impl Layers {
    pub fn all() -> Layers {
        Layers {
            earth: true,
            water: true,
            buildings: true,
            roads: true,
            boundaries: true,
            landcover: true,
            landuse: true,
        }
    }

    pub fn none() -> Layers {
        Layers {
            earth: false,
            water: false,
            buildings: false,
            roads: false,
            boundaries: false,
            landcover: false,
            landuse: false,
        }
    }

    /// Parse a comma-separated list, e.g. `water,roads`.
    pub fn parse(list: &str) -> Result<Layers, String> {
        let mut layers = Layers::none();
        for name in list.split(',').map(str::trim).filter(|s| !s.is_empty()) {
            match name {
                "earth" => layers.earth = true,
                "water" => layers.water = true,
                "buildings" => layers.buildings = true,
                "roads" => layers.roads = true,
                "boundaries" => layers.boundaries = true,
                "landcover" => layers.landcover = true,
                "landuse" => layers.landuse = true,
                other => {
                    return Err(format!(
                        "unknown layer `{other}`; this generator produces earth, water, buildings, \
                         roads, boundaries, landcover and landuse"
                    ))
                }
            }
        }
        if layers == Layers::none() {
            return Err("no layers selected".to_string());
        }
        Ok(layers)
    }

    /// Is this the layer a classifier just returned one for?
    fn wants(&self, layer: u8) -> bool {
        match layer {
            dict::LAYER_EARTH => self.earth,
            dict::LAYER_WATER => self.water,
            dict::LAYER_BUILDINGS => self.buildings,
            dict::LAYER_ROADS => self.roads,
            dict::LAYER_BOUNDARIES => self.boundaries,
            dict::LAYER_LANDCOVER => self.landcover,
            dict::LAYER_LANDUSE => self.landuse,
            _ => false,
        }
    }
}

/// Classify one element's tags, or `None` for the overwhelming majority that are not drawn.
///
/// Ordered and first-match-wins. `is_way` distinguishes a way from a relation, which matters
/// because a closed way's area-ness is a tag question while a multipolygon relation is always an
/// area.
pub fn classify(
    tags: &(impl TagSource + ?Sized),
    is_way: bool,
    layers: Layers,
) -> Option<Class> {
    if layers.earth {
        if let Some(class) = earth::classify(tags) {
            return Some(class);
        }
    }
    if layers.water {
        if let Some(class) = water::classify(tags, is_way) {
            return Some(class);
        }
    }
    // Before buildings, because a road bridge over a building passage is a road.
    if layers.roads {
        if let Some(class) = roads::classify(tags) {
            return Some(class);
        }
    }
    if layers.buildings {
        if let Some(class) = buildings::classify(tags) {
            return Some(class);
        }
    }
    if layers.boundaries {
        if let Some(class) = boundaries::classify(tags) {
            return Some(class);
        }
    }
    // Last, because it is the layer everything else is drawn on top of. One classifier produces
    // both `landcover` and `landuse`, so which of the two was asked for is checked after the fact.
    if layers.landcover || layers.landuse {
        if let Some(class) = land::classify(tags) {
            if layers.wants(class.layer) {
                return Some(class);
            }
        }
    }
    None
}

/// The `osmium tags-filter` expressions that pre-screen each layer, as one list.
///
/// Reading three tags to reject a feature instead of the thirty the rules would read between them.
/// A superset of what the rules accept, so a screen that lets something through is harmless and one
/// that rejects something is a bug.
pub fn filters(layers: Layers) -> Vec<&'static str> {
    let mut out = Vec::new();
    if layers.earth {
        out.extend_from_slice(earth::FILTERS);
    }
    if layers.water {
        out.extend_from_slice(water::FILTERS);
    }
    if layers.roads {
        out.extend_from_slice(roads::FILTERS);
    }
    if layers.buildings {
        out.extend_from_slice(buildings::FILTERS);
    }
    if layers.boundaries {
        out.extend_from_slice(boundaries::FILTERS);
    }
    if layers.landcover || layers.landuse {
        out.extend_from_slice(land::FILTERS);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every name this crate writes has to be in the dictionary, or [`kind`] and [`detail`] panic
    /// at runtime on whichever feature happens to hit that rule first.
    #[test]
    fn every_name_this_schema_uses_is_in_the_dictionary() {
        for name in water::KINDS
            .iter()
            .chain(buildings::KINDS)
            .chain(roads::KINDS)
            .chain(boundaries::KINDS)
            .chain(land::KINDS)
            .chain(earth::KINDS)
        {
            assert!(
                dict::KINDS.contains(name),
                "the schema names kind `{name}`, which the dictionary has no id for",
            );
            assert_eq!(dict::KINDS[kind(name) as usize - 1], *name);
        }
        for name in roads::DETAILS {
            assert!(
                dict::DETAILS.contains(name),
                "the schema names detail `{name}`, which the dictionary has no id for",
            );
            assert_eq!(dict::DETAILS[detail(name) as usize - 1], *name);
        }
    }

    #[test]
    fn a_layer_list_parses_and_rejects_what_this_generator_cannot_build() {
        assert_eq!(
            Layers::parse("water").expect("water"),
            Layers { water: true, ..Layers::none() },
        );
        assert_eq!(
            Layers::parse("earth,water,buildings,roads,boundaries,landcover,landuse").expect("all"),
            Layers::all(),
        );
        assert!(Layers::parse("places").is_err(), "this generator draws no labels");
        assert!(Layers::parse("").is_err(), "nothing selected");
    }

    /// Order is load-bearing and first match wins. A way tagged as both a road and a building is a
    /// road, because a building passage is something you drive through.
    #[test]
    fn the_rules_are_ordered_and_the_first_match_wins() {
        let both: &[(&str, &str)] = &[("highway", "residential"), ("building", "yes")];
        let class = classify(both, true, Layers::all()).expect("classified");
        assert_eq!(class.layer, dict::LAYER_ROADS);
        // And water is asked before either.
        let water: &[(&str, &str)] =
            &[("natural", "water"), ("highway", "residential"), ("building", "yes")];
        assert_eq!(classify(water, true, Layers::all()).expect("water").layer, dict::LAYER_WATER);
        // A layer that is switched off is not consulted, so the next rule wins instead.
        let only_buildings = Layers { buildings: true, ..Layers::none() };
        assert_eq!(
            classify(both, true, only_buildings).expect("building").layer,
            dict::LAYER_BUILDINGS,
        );
    }

    #[test]
    fn the_pre_screen_is_a_superset_of_what_the_rules_accept() {
        // Not provable in general, so this pins the shape: the screen is a list of tag *keys* the
        // rules actually read, in osmium's `tags-filter` spelling, and no layer has an empty one —
        // an empty screen would let every element in the file through to the rules.
        let all = filters(Layers::all());
        assert!(!all.is_empty());
        for filter in &all {
            assert!(!filter.is_empty(), "an empty screen matches everything");
            assert!(!filter.contains(' '), "`{filter}` is not a bare tag key");
        }
        assert!(
            filters(Layers { water: true, ..Layers::none() }).len()
                < all.len()
        );
        // Every key a rule reads for a *decision* has to be in the screen, or the rule never runs.
        for key in [
            "natural", "waterway", "landuse", "building", "highway", "railway", "boundary",
            "leisure", "amenity",
        ] {
            assert!(all.contains(&key), "the screen omits `{key}`");
        }
    }
}
