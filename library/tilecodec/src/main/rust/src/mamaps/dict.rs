//! The archive-wide dictionary: layer names, `kind`s and `kind_detail`s, interned to integers.
//!
//! This is the largest single win over MVT. There, every tile carries its own string table and
//! every feature carries a key/value property map, so the renderer paid a `String` allocation per
//! feature per tile to read the one property it actually looks at. Here a feature's whole
//! attribute surface is two `u16`s and three bits, because that is all the style filters on.
//!
//! # Why the table is a constant and not observed
//!
//! Ids come from [`SCHEMA`]'s position, never from the order a build happened to encounter
//! values in. Two consequences, both load-bearing:
//!
//! * A California archive has a **byte-identical** dictionary to a planet one, so the section is
//!   comparable across builds and a diff of two archives is a diff of their tiles.
//! * An id never shifts between builds, so a cached tile from yesterday's archive cannot be
//!   reinterpreted against today's dictionary and come out a different colour. The
//!   [`build_id`](super::header::Header::build_id) still invalidates the cache; this makes the
//!   failure impossible rather than merely unlikely.
//!
//! The dictionary is still written to the file. A reader validates its own compiled-in table
//! against it on open, and `mamaps_dump` needs the names to print without linking the schema.
//!
//! # Size
//!
//! Seven layers, 79 kinds and 41 details, at a length byte each: 1092 bytes as measured, which is
//! what lets the header, the dictionary and the root index share one 16 KiB opening read.

use crate::proto::{err, Result};

/// `kind` and `kind_detail` id 0: the feature carries none.
///
/// Not a sentinel a reader has to guess at — the unfiltered layers (`earth`'s land, the
/// `landcover` fallback) genuinely have no `kind`, and the style draws them by matching
/// everything.
pub const NONE: u16 = 0;

/// The layers this format carries, in draw order.
///
/// A layer's id **is** its index here. Seven, which is what the style draws; the format has no
/// room for an eighth without a version bump, and that is deliberate — a layer nothing paints is
/// bytes every reader downloads and discards.
pub const LAYERS: &[&str] =
    &["earth", "water", "landcover", "landuse", "roads", "boundaries", "buildings"];

pub const LAYER_EARTH: u8 = 0;
pub const LAYER_WATER: u8 = 1;
pub const LAYER_LANDCOVER: u8 = 2;
pub const LAYER_LANDUSE: u8 = 3;
pub const LAYER_ROADS: u8 = 4;
pub const LAYER_BOUNDARIES: u8 = 5;
pub const LAYER_BUILDINGS: u8 = 6;

/// Every `kind` value the schema can emit, id 1 upward. Index 0 is [`NONE`].
///
/// Grouped by the layer that introduced each value and **append-only**: inserting in the middle
/// would renumber everything after it, which is the one thing this table exists to prevent. A
/// value shared by two layers appears once, because ids are archive-wide.
///
/// The vocabulary is **measured, not guessed**. Everything here appears in the published
/// `v4.pmtiles`, sampled by `tile_build`'s `mamaps_vocabulary` example, which flags any value the
/// table has no id for. Re-run it after an upstream republish.
pub const KINDS: &[&str] = &[
    // earth
    "island",
    // water
    "bay",
    "fjord",
    "lake",
    "ocean",
    "river",
    "sea",
    "strait",
    "stream",
    "water",
    // landcover
    "grassland",
    "barren",
    "urban_area",
    "farmland",
    "glacier",
    "scrub",
    // landuse. `grassland` and `scrub` are already above and are not repeated.
    "national_park",
    "park",
    "cemetery",
    "protected_area",
    "nature_reserve",
    "forest",
    "golf_course",
    "wood",
    "grass",
    "military",
    "naval_base",
    "airfield",
    "allotments",
    "village_green",
    "playground",
    "hospital",
    "industrial",
    "school",
    "university",
    "college",
    "beach",
    "zoo",
    "aerodrome",
    "runway",
    "taxiway",
    "pedestrian",
    "dam",
    "pier",
    // roads
    "highway",
    "major_road",
    "minor_road",
    "path",
    "other",
    "rail",
    // buildings
    "building",
    "building_part",
    // Appended after the first measurement against the published archive, which turned up 42 of
    // 54 features on the z0 tile carrying a value the table above had no id for. Append-only, so
    // these take fresh ids rather than disturbing any above.
    // earth
    "earth",
    "cliff",
    // water
    "canal",
    "dock",
    "fountain",
    "reef",
    "swimming_pool",
    // landuse
    "bare_rock",
    "commercial",
    "dog_park",
    "garden",
    "kindergarten",
    "meadow",
    "pitch",
    "platform",
    "railway",
    "recreation_ground",
    "residential",
    "sand",
    "wetland",
    // roads
    "aerialway",
    "ferry",
    // boundaries. The style filters these numerically through `kind_detail`, so no flat style layer
    // names them -- but they are what the data says, and the differential harness compares names.
    "country",
    "region",
    "county",
    "locality",
    "overlay_limit",
    "unrecognized_country",
];

/// Every `kind_detail` value, id 1 upward. Index 0 is [`NONE`].
///
/// A separate table from [`KINDS`], so `runway` as a `landuse` kind and `runway` as a road's
/// detail are different ids. They describe different things and collapsing them would make a
/// road's detail collide with a polygon's kind.
///
/// On `roads` this is the OSM highway class, which is what the upstream schema puts here — 30-odd
/// values, not the four the style happens to filter on. Carrying the rest is what lets the
/// generator be checked against upstream feature for feature, and what a future style would need
/// to draw a track differently from a motorway link.
///
/// `boundaries` does not appear here: its detail is a numeric admin level carried in the same
/// field under [`super::body::FLAG_DETAIL_NUMERIC`], because an admin level is an integer the
/// style compares with `<=` rather than a name it matches. The sample confirms it — upstream
/// writes it as an `SInt`, not a string.
pub const DETAILS: &[&str] = &[
    // The four the style filters on, first because they were here first.
    "runway",
    "taxiway",
    "pier",
    "service",
    // The rest of the road classes, measured from the published archive.
    "motorway",
    "motorway_link",
    "trunk",
    "trunk_link",
    "primary",
    "primary_link",
    "secondary",
    "secondary_link",
    "tertiary",
    "tertiary_link",
    "residential",
    "unclassified",
    "living_street",
    "alley",
    "track",
    "path",
    "footway",
    "sidewalk",
    "crossing",
    "steps",
    "corridor",
    "cycleway",
    "pedestrian",
    "rail",
    "subway",
    "tram",
    "light_rail",
    "cable_car",
    "turntable",
    // water
    "basin",
    "canal",
    "lake",
    "river",
    "stream",
    "rock",
    // buildings
    "yes",
];

/// The interned tables as the archive carries them.
///
/// Parsed from the file rather than assumed, so a reader can tell a mismatched archive from a
/// mismatched reader.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Dictionary {
    pub layers: Vec<String>,
    pub kinds: Vec<String>,
    pub details: Vec<String>,
}

impl Dictionary {
    /// The compiled-in schema, which is what a writer always emits.
    pub fn schema() -> Dictionary {
        let owned = |table: &[&str]| table.iter().map(|s| s.to_string()).collect();
        Dictionary { layers: owned(LAYERS), kinds: owned(KINDS), details: owned(DETAILS) }
    }

    pub fn layer_name(&self, id: u8) -> Option<&str> {
        self.layers.get(id as usize).map(String::as_str)
    }

    /// The name of a `kind` id, or `None` for [`NONE`] and for anything past the table.
    pub fn kind_name(&self, id: u16) -> Option<&str> {
        (id != NONE).then(|| self.kinds.get(id as usize - 1).map(String::as_str)).flatten()
    }

    pub fn detail_name(&self, id: u16) -> Option<&str> {
        (id != NONE).then(|| self.details.get(id as usize - 1).map(String::as_str)).flatten()
    }

    /// Three counted tables back to back, then zero padding to a 4-byte boundary.
    ///
    /// Each table is a `u16` count followed by that many `u8`-length-prefixed strings. Padding
    /// so the root index that follows starts aligned and a reader can slice it zero-copy.
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(1024);
        for table in [&self.layers, &self.kinds, &self.details] {
            out.extend_from_slice(&(table.len() as u16).to_le_bytes());
            for name in table {
                out.push(name.len() as u8);
                out.extend_from_slice(name.as_bytes());
            }
        }
        while out.len() % 4 != 0 {
            out.push(0);
        }
        out
    }

    pub fn parse(buf: &[u8]) -> Result<Dictionary> {
        let mut at = 0usize;
        let mut table = |what: &str, limit: usize| -> Result<Vec<String>> {
            if at + 2 > buf.len() {
                return err(format!("a .mamaps dictionary ends before its {what} count"));
            }
            let count = u16::from_le_bytes([buf[at], buf[at + 1]]) as usize;
            at += 2;
            if count > limit {
                return err(format!(
                    "a .mamaps dictionary declares {count} {what}, past the {limit} this format allows"
                ));
            }
            let mut names = Vec::with_capacity(count);
            for _ in 0..count {
                if at >= buf.len() {
                    return err(format!("a .mamaps dictionary ends inside its {what}"));
                }
                let len = buf[at] as usize;
                at += 1;
                if at + len > buf.len() {
                    return err(format!("a .mamaps {what} name runs past the dictionary"));
                }
                let name = std::str::from_utf8(&buf[at..at + len])
                    .map_err(|_| crate::proto::Error(format!("a .mamaps {what} name is not UTF-8")))?;
                names.push(name.to_string());
                at += len;
            }
            Ok(names)
        };
        // A layer id is a `u8` and a kind id a `u16`, so those are the real ceilings; naming them
        // here is what stops a corrupt count from asking for a gigabyte of `String`s.
        let layers = table("layer", u8::MAX as usize)?;
        let kinds = table("kind", u16::MAX as usize - 1)?;
        let details = table("kind_detail", u16::MAX as usize - 1)?;
        Ok(Dictionary { layers, kinds, details })
    }

    /// Refuse an archive whose tables are not the ones this build was compiled against.
    ///
    /// A **whole-table** comparison rather than a length check: the point of a constant table is
    /// that id 17 means `national_park` in every archive ever written, so an archive that
    /// disagrees anywhere would render some layer in the wrong colour with no other symptom.
    pub fn check_matches_schema(&self) -> Result<()> {
        let schema = Dictionary::schema();
        for (what, ours, theirs) in [
            ("layer", &schema.layers, &self.layers),
            ("kind", &schema.kinds, &self.kinds),
            ("kind_detail", &schema.details, &self.details),
        ] {
            if ours == theirs {
                continue;
            }
            let first = ours
                .iter()
                .zip(theirs.iter())
                .position(|(a, b)| a != b)
                .unwrap_or(ours.len().min(theirs.len()));
            return err(format!(
                "a .mamaps {what} table disagrees with this build's schema from id {first} \
                 ({} entries here, {} in the archive)",
                ours.len(),
                theirs.len(),
            ));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_dictionary_round_trips_and_stays_small_enough_for_the_prefix() {
        let schema = Dictionary::schema();
        let bytes = schema.serialize();
        // 1092 bytes as measured. The bound is what the opening prefix can spare beside the header
        // and a useful root, not a round number: at 2 KiB the root still addresses 1.9 M tiles.
        assert!(bytes.len() < 2048, "the dictionary is {} bytes", bytes.len());
        assert_eq!(bytes.len() % 4, 0, "the root index after it must start aligned");
        assert_eq!(Dictionary::parse(&bytes).expect("should parse"), schema);
    }

    /// **The invariant the whole table exists for.** An id is a position, so a value's id must
    /// not depend on anything a build observed. Pinned as literals: a diff that moves an entry
    /// shows up here as a changed number rather than as a recoloured map.
    #[test]
    fn kind_ids_are_positions_in_a_constant_table() {
        let d = Dictionary::schema();
        assert_eq!(d.kind_name(1), Some("island"), "the first kind");
        assert_eq!(d.kind_name(10), Some("water"));
        assert_eq!(d.kind_name(17), Some("national_park"));
        assert_eq!(d.kind_name(45), Some("highway"));
        assert_eq!(d.kind_name(52), Some("building_part"), "the last of the first draft");
        assert_eq!(d.kind_name(53), Some("earth"), "the first value appended after measuring");
        assert_eq!(d.kind_name(NONE), None, "id 0 is `this feature has no kind`");
        assert_eq!(d.kind_name(u16::MAX), None, "past the table");
        assert_eq!(d.detail_name(4), Some("service"));
        assert_eq!(d.detail_name(5), Some("motorway"), "the road classes follow");
        assert_eq!(d.layer_name(LAYER_ROADS), Some("roads"));
        assert_eq!(d.layer_name(7), None);
    }

    /// A duplicate would give one value two ids, so half the features carrying it would filter
    /// against a style whitelist and half would not.
    #[test]
    fn no_value_appears_twice_in_a_table() {
        for (what, table) in [("layer", LAYERS), ("kind", KINDS), ("detail", DETAILS)] {
            let mut seen: Vec<&str> = table.to_vec();
            seen.sort_unstable();
            let before = seen.len();
            seen.dedup();
            assert_eq!(before, seen.len(), "the {what} table repeats a value");
        }
    }

    /// Ids are `u8` for a layer and `u16` for the rest, and a name's length is one byte.
    #[test]
    fn every_table_fits_the_field_that_carries_its_ids() {
        assert!(LAYERS.len() <= u8::MAX as usize);
        assert!(KINDS.len() < u16::MAX as usize);
        assert!(DETAILS.len() < u16::MAX as usize);
        for name in LAYERS.iter().chain(KINDS).chain(DETAILS) {
            assert!(!name.is_empty(), "an empty name would be indistinguishable from padding");
            assert!(name.len() <= u8::MAX as usize, "`{name}` needs a longer length prefix");
        }
    }

    #[test]
    fn an_archive_whose_tables_differ_is_refused() {
        assert!(Dictionary::schema().check_matches_schema().is_ok());
        let mut renamed = Dictionary::schema();
        renamed.kinds[16] = "national_parks".to_string();
        let message = renamed.check_matches_schema().expect_err("should be refused").0;
        assert!(message.contains("from id 16"), "{message}");
        let mut short = Dictionary::schema();
        short.details.pop();
        assert!(short.check_matches_schema().is_err());
    }

    #[test]
    fn a_truncated_or_implausible_dictionary_is_refused() {
        let bytes = Dictionary::schema().serialize();
        for cut in [0, 1, 2, 5, 40, bytes.len() - 4] {
            assert!(Dictionary::parse(&bytes[..cut]).is_err(), "truncated at {cut}");
        }
        // A count no length field could carry, which is how a corrupt section would otherwise
        // ask for a gigabyte of `String`s.
        assert!(Dictionary::parse(&[0xFF, 0xFF]).is_err());
        // A name whose length runs off the end.
        assert!(Dictionary::parse(&[1, 0, 200, b'a']).is_err());
        // A name that is not text.
        assert!(Dictionary::parse(&[1, 0, 1, 0xFF]).is_err());
    }
}
