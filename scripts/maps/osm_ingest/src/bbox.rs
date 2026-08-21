//! Bounding-box filtering: the `osmium extract -b` equivalent.
//!
//! The layer scripts clip a PBF with `osmium extract --overwrite -b BOX` before
//! reading it. Doing the clip inline instead removes both the osmium dependency
//! and the intermediate file, at the cost of still walking the whole PBF -- which
//! the extractor has to do anyway to resolve way and relation members.
//!
//! ## Which strategy this reproduces
//!
//! `osmium extract`'s default is `complete_ways`: a way is kept if **any** of its
//! nodes falls inside the box, and then *all* of that way's nodes are kept, even
//! the ones outside. That is why a clipped extract has geometry hanging past the
//! box edge, and it is the right behaviour here too -- truncating a way at the box
//! boundary would invent a vertex that is not in OSM, and the tiler clips
//! properly per tile later anyway.
//!
//! So [`BBox`] only ever answers "is this coordinate inside", and the callers
//! decide what that means for a multi-node element: keep the whole thing if one
//! vertex qualifies.

use crate::proto::{Error, Result};

/// A geographic bounding box in degrees.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BBox {
    pub min_lon: f64,
    pub min_lat: f64,
    pub max_lon: f64,
    pub max_lat: f64,
}

impl BBox {
    /// Parse the `minlon,minlat,maxlon,maxlat` form every script in this
    /// directory uses for `--bbox`.
    ///
    /// Reversed bounds are an error rather than being silently swapped: a
    /// transposed box usually means lat and lon were entered the wrong way round,
    /// and normalising it would produce a plausible-looking extract of the wrong
    /// place.
    pub fn parse(s: &str) -> Result<BBox> {
        let parts: Vec<&str> = s.split(',').map(str::trim).collect();
        if parts.len() != 4 {
            return Err(Error(format!(
                "--bbox wants minlon,minlat,maxlon,maxlat, got {} field(s): {s}",
                parts.len()
            )));
        }
        let mut v = [0.0f64; 4];
        for (i, p) in parts.iter().enumerate() {
            v[i] = p
                .parse()
                .map_err(|_| Error(format!("--bbox field {} is not a number: {p}", i + 1)))?;
        }
        let bbox = BBox {
            min_lon: v[0],
            min_lat: v[1],
            max_lon: v[2],
            max_lat: v[3],
        };
        if bbox.min_lon >= bbox.max_lon || bbox.min_lat >= bbox.max_lat {
            return Err(Error(format!(
                "--bbox is empty or reversed: min ({}, {}) is not below max ({}, {})",
                bbox.min_lon, bbox.min_lat, bbox.max_lon, bbox.max_lat
            )));
        }
        if !(-180.0..=180.0).contains(&bbox.min_lon)
            || !(-180.0..=180.0).contains(&bbox.max_lon)
            || !(-90.0..=90.0).contains(&bbox.min_lat)
            || !(-90.0..=90.0).contains(&bbox.max_lat)
        {
            return Err(Error(format!("--bbox is outside the world: {s}")));
        }
        Ok(bbox)
    }

    /// Inclusive on every edge, matching osmium: a node exactly on the boundary
    /// is inside.
    pub fn contains(&self, lon: f64, lat: f64) -> bool {
        self.contains_e7(to_e7(lat), to_e7(lon))
    }

    /// The same test against the 1e-7 integers the PBF decoder produces, which is
    /// what the hot paths have.
    ///
    /// Both entry points compare **integers**, not degrees. Going the other way
    /// -- widening `lat_e7` to a float and comparing against the parsed bounds --
    /// loses a coordinate that sits exactly on an edge: `1e-7` is not
    /// representable in binary, so `-1_226_000_000 * 1e-7` lands a hair below
    /// `-122.6` and falls out of a box whose western edge is exactly that. OSM
    /// coordinates are 1e-7 integers to begin with, so rounding the bounds into
    /// that space instead is both exact and the comparison the data deserves.
    pub fn contains_e7(&self, lat_e7: i32, lon_e7: i32) -> bool {
        lon_e7 >= to_e7(self.min_lon)
            && lon_e7 <= to_e7(self.max_lon)
            && lat_e7 >= to_e7(self.min_lat)
            && lat_e7 <= to_e7(self.max_lat)
    }
}

fn to_e7(deg: f64) -> i32 {
    (deg * 1e7).round() as i32
}

/// `true` when there is no box, or the point is inside it. The shape callers
/// actually want, since `--bbox` is optional everywhere.
pub fn keep_e7(bbox: Option<&BBox>, lat_e7: i32, lon_e7: i32) -> bool {
    bbox.is_none_or(|b| b.contains_e7(lat_e7, lon_e7))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SF: &str = "-122.6,37.2,-121.7,37.9";

    #[test]
    fn parses_the_script_form() {
        let b = BBox::parse(SF).unwrap();
        assert_eq!(
            b,
            BBox {
                min_lon: -122.6,
                min_lat: 37.2,
                max_lon: -121.7,
                max_lat: 37.9,
            }
        );
        // Whitespace around the fields is tolerated.
        assert_eq!(BBox::parse(" -122.6 , 37.2 , -121.7 , 37.9 ").unwrap(), b);
    }

    #[test]
    fn rejects_malformed_boxes() {
        for bad in [
            "",
            "1,2,3",
            "1,2,3,4,5",
            "a,2,3,4",
            // Reversed rather than silently swapped: this is the lat/lon
            // transposition mistake, and normalising it hides a wrong extract.
            "-121.7,37.2,-122.6,37.9",
            "-122.6,37.9,-121.7,37.2",
            // Degenerate.
            "-122.6,37.2,-122.6,37.9",
            // Off the world.
            "-200,37.2,-121.7,37.9",
            "-122.6,-95,-121.7,37.9",
        ] {
            assert!(BBox::parse(bad).is_err(), "{bad:?} should not parse");
        }
    }

    #[test]
    fn containment_is_inclusive_on_every_edge() {
        let b = BBox::parse(SF).unwrap();
        assert!(b.contains(-122.4194, 37.7749)); // San Francisco
        assert!(b.contains(-122.6, 37.2)); // min corner
        assert!(b.contains(-121.7, 37.9)); // max corner
        assert!(!b.contains(-122.61, 37.7)); // just west
        assert!(!b.contains(-122.4, 38.0)); // just north
        assert!(!b.contains(-74.0, 40.7)); // New York
    }

    #[test]
    fn the_e7_path_agrees_with_the_float_path() {
        let b = BBox::parse(SF).unwrap();
        assert!(b.contains_e7(377_749_000, -1_224_194_000));
        assert!(!b.contains_e7(407_000_000, -740_000_000));
        // Exactly on both corners, expressed as integers. This is the case that
        // a float comparison gets wrong: -1_226_000_000 * 1e-7 is very slightly
        // west of -122.6, so the node falls out of its own bounding box.
        assert!(b.contains_e7(372_000_000, -1_226_000_000));
        assert!(b.contains_e7(379_000_000, -1_217_000_000));
        // One unit outside is outside.
        assert!(!b.contains_e7(372_000_000, -1_226_000_001));
        assert!(!b.contains_e7(371_999_999, -1_226_000_000));
    }

    #[test]
    fn no_box_keeps_everything() {
        assert!(keep_e7(None, 407_000_000, -740_000_000));
        let b = BBox::parse(SF).unwrap();
        assert!(!keep_e7(Some(&b), 407_000_000, -740_000_000));
        assert!(keep_e7(Some(&b), 377_749_000, -1_224_194_000));
    }
}
