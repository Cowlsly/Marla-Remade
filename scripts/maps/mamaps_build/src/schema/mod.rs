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

pub mod buildings;
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
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
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
}

impl Class {
    /// A polygon feature with no detail and no flags.
    pub const fn area(layer: u8, kind: u16, min_zoom: u8) -> Class {
        Class { layer, kind, kind_detail: dict::NONE, flags: 0, area: true, min_zoom }
    }

    /// A line feature with no detail and no flags.
    pub const fn line(layer: u8, kind: u16, min_zoom: u8) -> Class {
        Class { layer, kind, kind_detail: dict::NONE, flags: 0, area: false, min_zoom }
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

/// Which layers a build is producing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Layers {
    pub water: bool,
    pub buildings: bool,
}

impl Layers {
    pub fn all() -> Layers {
        Layers { water: true, buildings: true }
    }

    /// Parse a comma-separated list, e.g. `water,buildings`.
    pub fn parse(list: &str) -> Result<Layers, String> {
        let mut layers = Layers { water: false, buildings: false };
        for name in list.split(',').map(str::trim).filter(|s| !s.is_empty()) {
            match name {
                "water" => layers.water = true,
                "buildings" => layers.buildings = true,
                other => {
                    return Err(format!(
                        "unknown layer `{other}`; this generator produces water and buildings"
                    ))
                }
            }
        }
        if !layers.water && !layers.buildings {
            return Err("no layers selected".to_string());
        }
        Ok(layers)
    }
}

/// Classify one element's tags, or `None` for the overwhelming majority that are not drawn.
///
/// Ordered and first-match-wins. `is_way` distinguishes a way from a relation, which matters
/// because a closed way's area-ness is a tag question while a multipolygon relation is always an
/// area.
pub fn classify(tags: &(impl TagSource + ?Sized), is_way: bool, layers: Layers) -> Option<Class> {
    if layers.water {
        if let Some(class) = water::classify(tags, is_way) {
            return Some(class);
        }
    }
    if layers.buildings {
        if let Some(class) = buildings::classify(tags) {
            return Some(class);
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
    if layers.water {
        out.extend_from_slice(water::FILTERS);
    }
    if layers.buildings {
        out.extend_from_slice(buildings::FILTERS);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every name this crate writes has to be in the dictionary, or [`kind`] panics at runtime on
    /// whichever feature happens to hit that rule first.
    #[test]
    fn every_kind_this_schema_names_is_in_the_dictionary() {
        for name in water::KINDS.iter().chain(buildings::KINDS) {
            assert!(
                dict::KINDS.contains(name),
                "the schema names `{name}`, which the dictionary has no id for",
            );
            // And the lookup really resolves rather than merely being present.
            assert_eq!(dict::KINDS[kind(name) as usize - 1], *name);
        }
    }

    #[test]
    fn a_layer_list_parses_and_rejects_what_this_generator_cannot_build() {
        assert_eq!(Layers::parse("water").expect("water"), Layers { water: true, buildings: false });
        assert_eq!(Layers::parse("water,buildings").expect("both"), Layers::all());
        assert_eq!(Layers::parse(" buildings , water ").expect("spaces"), Layers::all());
        assert!(Layers::parse("roads").is_err(), "not until phase 6");
        assert!(Layers::parse("").is_err(), "nothing selected");
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
        assert!(filters(Layers { water: true, buildings: false }).len() < all.len());
        // Every key a rule reads for a *decision* has to be in the screen, or the rule never runs.
        for key in ["natural", "waterway", "landuse", "building"] {
            assert!(all.contains(&key), "the screen omits `{key}`");
        }
    }
}
