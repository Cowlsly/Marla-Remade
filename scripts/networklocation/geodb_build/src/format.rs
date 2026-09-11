//! The `geocoder-v3.geodb` on-disk format, in one place.
//!
//! Every constant here is duplicated by hand in the reader,
//! `networklocation/src/main/rust/src/geocoder.rs`. That is the format's main hazard: the two
//! sides share no code, and a divergence does not fail loudly — it decodes to plausible
//! nonsense. `write::verify` reads a finished database back through an independent path, and
//! the reader has fixture tests, but neither replaces keeping this list and its counterpart in
//! step.
//!
//! ```text
//! 0  magic  u32 = "MAGE"
//! 4  version u32 = 3
//! 8  n      u32
//! 12 21 sections, each [u32 size][body]:
//!      [0..6]   dictionaries: name, house, street, city, state, country, postcode
//!      [7..16]  columns: lat, lon, name, house, street, city, state, country, postcode, kind
//!      [17]     grid
//!      [18]     fwd      (address-ordered permutation)
//!      [19]     nm_name  (name ids, ascending)
//!      [20]     nm_rec   (record index for each nm_name entry)
//! ```
//!
//! All integers are **big-endian**, matching v2.
//!
//! ## What changed from v2
//!
//! - Coordinates are **e7**, not e6. OSM stores e7, so v2's e6 threw away a decimal digit for
//!   nothing. This is the change that forces [`morton_in_cell`] to widen (see below).
//! - Two new columns, `name` and `kind`, and a `name` dictionary. v2 could only hold addresses
//!   because it had nowhere to put a feature's name or to say what sort of feature it was.
//! - Two new sections, `nm_name`/`nm_rec`, indexing records by name so forward search can find
//!   a POI or a place rather than only an address.

/// `"MAGE"`.
pub const MAGIC: u32 = 0x4D41_4745;
/// Format version. v2 was address-only and e6.
pub const VERSION: u32 = 3;
/// Records per compressed column block.
pub const BLOCK: usize = 4096;
/// Zstandard level for column blocks and dictionaries.
pub const ZSTD_LEVEL: i32 = 19;

/// Grid cell edge in e7 units: 0.05 degrees, about 5.5 km of latitude.
pub const CELL_E7: i64 = 500_000;
/// Cell columns around the planet.
pub const COLS: i64 = 3_600_000_000 / CELL_E7;
/// Lowest representable latitude, e7.
pub const MIN_LAT_E7: i32 = -900_000_000;
/// Lowest representable longitude, e7.
pub const MIN_LON_E7: i32 = -1_800_000_000;

/// Number of string dictionaries.
pub const DICTS: usize = 7;
/// Number of record columns.
pub const COLUMNS: usize = 10;
/// Total sections after the header.
pub const SECTIONS: usize = DICTS + COLUMNS + 4;

/// Dictionary slots, in section order.
pub const D_NAME: usize = 0;
/// House-number dictionary.
pub const D_HOUSE: usize = 1;
/// Street-name dictionary.
pub const D_STREET: usize = 2;
/// City dictionary.
pub const D_CITY: usize = 3;
/// State dictionary.
pub const D_STATE: usize = 4;
/// Country-code dictionary.
pub const D_COUNTRY: usize = 5;
/// Postcode dictionary.
pub const D_POSTCODE: usize = 6;

/// Column slots, in section order.
pub const C_LAT: usize = 0;
/// Longitude column.
pub const C_LON: usize = 1;
/// Name dictionary id.
pub const C_NAME: usize = 2;
/// House-number dictionary id.
pub const C_HOUSE: usize = 3;
/// Street dictionary id.
pub const C_STREET: usize = 4;
/// City dictionary id.
pub const C_CITY: usize = 5;
/// State dictionary id.
pub const C_STATE: usize = 6;
/// Country dictionary id.
pub const C_COUNTRY: usize = 7;
/// Postcode dictionary id.
pub const C_POSTCODE: usize = 8;
/// Record kind, one of the `K_*` values.
pub const C_KIND: usize = 9;

/// Which columns are delta-encoded against the previous value in their block.
pub const DELTA: [bool; COLUMNS] =
    [true, true, false, false, false, false, false, false, false, false];

/// Column names, for the writer's size report.
pub const COL_NAME: [&str; COLUMNS] =
    ["lat", "lon", "name", "house", "street", "city", "state", "country", "postcode", "kind"];

/// Dictionaries whose order is a **searchable** contract: the reader binary-searches them, so
/// they must be sorted by UTF-16 code unit. The rest are frequency-ordered for size.
pub const SEARCHABLE: [bool; DICTS] = [true, false, true, true, true, true, false];

/// A postal address.
pub const K_ADDRESS: u8 = 1;
/// A named road, sampled along its length.
pub const K_STREET: u8 = 2;
/// A named point of interest.
pub const K_POI: u8 = 3;
/// A populated place: city, town, village, suburb, neighbourhood.
pub const K_PLACE: u8 = 4;

/// Grid cell containing a coordinate. Row-major, `COLS` cells per row.
pub fn cell_id(lat_e7: i32, lon_e7: i32) -> i64 {
    let row = (lat_e7 as i64 - MIN_LAT_E7 as i64) / CELL_E7;
    let col = (lon_e7 as i64 - MIN_LON_E7 as i64) / CELL_E7;
    row * COLS + col
}

/// Z-order key of a coordinate **within** its cell.
///
/// Records are ordered by cell and then by this, so both delta columns stay small: a plain
/// `(lat, lon)` sort keeps the latitude delta tiny but lets longitude swing across the whole
/// cell on every row.
///
/// v2 interleaved 16 bits per axis, which worked only because an e6 cell is 50 000 units wide.
/// **An e7 cell is 500 000 units and needs 19**, so this interleaves 20. Getting this wrong
/// does not fail — it silently produces a worse ordering and, if the offsets overflowed, a
/// wrong one.
pub fn morton_in_cell(lat_e7: i32, lon_e7: i32) -> u64 {
    let lat_off = ((lat_e7 as i64 - MIN_LAT_E7 as i64) % CELL_E7) as u64;
    let lon_off = ((lon_e7 as i64 - MIN_LON_E7 as i64) % CELL_E7) as u64;
    (interleave20(lat_off) << 1) | interleave20(lon_off)
}

/// Spread the low 20 bits of `v` into the even bit positions of a 40-bit result.
fn interleave20(v: u64) -> u64 {
    let mut r = 0u64;
    for i in 0..20 {
        r |= ((v >> i) & 1) << (2 * i);
    }
    r
}

/// Whether a coordinate is inside the representable range.
pub fn in_range(lat_e7: i32, lon_e7: i32) -> bool {
    (MIN_LAT_E7..=900_000_000).contains(&lat_e7) && (MIN_LON_E7..=1_800_000_000).contains(&lon_e7)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cells_tile_the_planet_without_gaps_or_overlap() {
        assert_eq!(COLS, 7200);
        // South-west corner is cell 0.
        assert_eq!(cell_id(MIN_LAT_E7, MIN_LON_E7), 0);
        // One cell east.
        assert_eq!(cell_id(MIN_LAT_E7, MIN_LON_E7 + CELL_E7 as i32), 1);
        // One cell north.
        assert_eq!(cell_id(MIN_LAT_E7 + CELL_E7 as i32, MIN_LON_E7), COLS);
        // The north-east corner stays inside i64 comfortably.
        let last = cell_id(900_000_000, 1_800_000_000);
        assert!(last > 0 && last < 26_000_000, "cell id {last} out of expected range");
    }

    #[test]
    fn a_cell_is_a_twentieth_of_a_degree() {
        // Anchored at a cell boundary so the arithmetic is unambiguous: 0.05 degrees is one
        // cell, so 0.04 stays inside and 0.06 does not.
        let lat = MIN_LAT_E7 + 2555 * CELL_E7 as i32;
        let a = cell_id(lat, -122_4194200);
        assert_eq!(cell_id(lat + 400_000, -122_4194200), a);
        assert_ne!(cell_id(lat + 600_000, -122_4194200), a);
        // And a cell step north moves by exactly one grid row.
        assert_eq!(cell_id(lat + CELL_E7 as i32, -122_4194200), a + COLS);
    }

    #[test]
    fn morton_offsets_cannot_overflow_the_interleave() {
        // The whole point of widening to 20 bits: an e7 cell is 500 000 units, which needs 19.
        assert!(CELL_E7 <= (1 << 20), "cell wider than the interleave can represent");
        // Extremes of a cell.
        let base_lat = MIN_LAT_E7;
        let base_lon = MIN_LON_E7;
        let hi = morton_in_cell(base_lat + CELL_E7 as i32 - 1, base_lon + CELL_E7 as i32 - 1);
        assert!(hi < (1u64 << 40), "morton escaped 40 bits: {hi:#x}");
        assert_eq!(morton_in_cell(base_lat, base_lon), 0);
    }

    #[test]
    fn morton_orders_nearby_points_together() {
        // Two points a metre apart must be far closer in Z-order than two a cell apart.
        let a = morton_in_cell(37_7749300, -122_4194200);
        let b = morton_in_cell(37_7749400, -122_4194200);
        let near = a.abs_diff(b);
        let far = a.abs_diff(morton_in_cell(37_7749300 + 400_000, -122_4194200));
        assert!(near < far, "z-order does not preserve locality: {near} vs {far}");
    }

    #[test]
    fn morton_is_injective_within_a_cell() {
        let mut seen = std::collections::HashSet::new();
        for dlat in 0..40 {
            for dlon in 0..40 {
                let m = morton_in_cell(
                    MIN_LAT_E7 + dlat * 12_345,
                    MIN_LON_E7 + dlon * 12_345,
                );
                assert!(seen.insert(m), "collision at ({dlat}, {dlon})");
            }
        }
    }

    #[test]
    fn range_check_matches_the_e7_field_width() {
        assert!(in_range(0, 0));
        assert!(in_range(900_000_000, 1_800_000_000));
        assert!(in_range(-900_000_000, -1_800_000_000));
        assert!(!in_range(900_000_001, 0));
        assert!(!in_range(0, -1_800_000_001));
        // e7 longitude is the widest value stored and must still fit an i32.
        assert!(1_800_000_000i64 < i32::MAX as i64);
    }

    #[test]
    fn section_count_matches_the_layout() {
        assert_eq!(SECTIONS, 21);
        assert_eq!(DELTA.len(), COLUMNS);
        assert_eq!(COL_NAME.len(), COLUMNS);
        assert_eq!(SEARCHABLE.len(), DICTS);
    }
}
