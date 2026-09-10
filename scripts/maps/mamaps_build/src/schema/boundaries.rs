//! `boundaries`: the one layer whose detail is a number.
//!
//! An administrative boundary's whole meaning is its **level** — 2 is a country, 4 a state, 6 a
//! county, 8 a city — and the style compares it with `<=` rather than matching a name: one layer for
//! country borders and another for everything below. So the level is carried as a plain integer in
//! the `kind_detail` field under [`FLAG_DETAIL_NUMERIC`], rather than interned. Interning it would
//! mean interning every integer, and comparing `<=` against an id that is not ordered like the value
//! would be worse than useless.
//!
//! A `kind` is carried too, for the differential harness and for a future style that wants a county
//! line dashed differently from a state line. It is derivable from the level, which is exactly why
//! the level is what the style reads.
//!
//! # A boundary is a line, and cannot be an area
//!
//! Carrying the region as an area was tried, so that tapping a city could dim everything outside
//! it, and it has to be reverted: **clipping a polygon to a tile adds segments along the tile
//! edge** to close the ring, and the style's boundary layers are `line`, which strokes a polygon's
//! outline. Those synthetic edges are then drawn, and the map is covered in a grid of tile borders.
//! Clipping a *line* merely truncates it, which is why the line form has never had this problem.
//!
//! There is no way to opt out from the style side either: the `boundaries` entry declares no
//! `kinds`, so it matches every feature in the layer and would stroke a polygon whatever kind it
//! carried.
//!
//! Whatever eventually feeds a region mask therefore needs somewhere the boundary style does not
//! reach — a kind the layer excludes, or a layer of its own — decided together with the mask
//! rather than guessed at here.
//!
//! # The marking convention rides on this layer too
//!
//! Which side traffic keeps and what colour separates the directions are properties of the
//! *country*, and the only country geometry this build has is the `admin_level=2` relations it
//! already streams for the border lines. [`Conventions`] turns those into a coarse grid the tiler
//! stamps onto each tile's body, so the renderer never has to carry a polygon set to answer a
//! question that never changes for a given tile.

use std::collections::HashMap;

use tilecodec::mamaps::body::{MarkingConvention, FLAG_DETAIL_NUMERIC};
use tilecodec::mamaps::dict::LAYER_BOUNDARIES;

use super::{kind, Class, TagSource};

pub const FILTERS: &[&str] = &["boundary", "admin_level", "maritime"];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &["country", "region", "county", "locality", "region_area"];

/// The deepest administrative level worth drawing.
///
/// Below 8 is a ward or a neighbourhood: real data, and a line nobody has ever wanted on a basemap.
const MAX_LEVEL: u16 = 8;

/// The region's *shape*, for a relation that has one.
///
/// Emitted **in addition to** the border line from [`classify`], never instead of it: the line is
/// what draws the border, and this is only read by the region mask. Both are needed, and they
/// cannot be the same feature — see the module docs for the tile-edge grid that results from
/// trying.
///
/// `None` for a way, because a single way is one segment of a border and encloses nothing. A
/// relation whose rings will not close simply yields no area, and the border is unaffected.
pub fn region_area(tags: &(impl TagSource + ?Sized), is_way: bool) -> Option<Class> {
    if is_way {
        return None;
    }
    let line = classify(tags)?;
    Some(Class {
        layer: LAYER_BOUNDARIES,
        kind: kind("region_area"),
        // The same level the border carries, so the mask can tell a country from a city.
        kind_detail: line.kind_detail,
        flags: FLAG_DETAIL_NUMERIC,
        area: true,
        // One level shallower than the border line. A mask is drawn over a whole region, so it is
        // wanted at the zoom where the region *fits on screen* — which is about where its label
        // appears, not where its border becomes legible.
        min_zoom: line.min_zoom.saturating_sub(1),
        min_area_px: 0.0,
    })
}

pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if tags.get("boundary") != Some("administrative") {
        return None;
    }
    // A maritime boundary (an EEZ or territorial-water limit drawn across open sea) is
    // `boundary=administrative` over water, not inland/coastline admin, and the reference
    // style's boundary layers do not show it. Dropped here, in the tiler, so the layer
    // carries what the style draws rather than lines across the ocean.
    if tags.get("maritime") == Some("yes") {
        return None;
    }
    let level: u16 = tags.get("admin_level")?.trim().parse().ok()?;
    if level == 0 || level > MAX_LEVEL {
        return None;
    }
    Some(Class {
        layer: LAYER_BOUNDARIES,
        kind: kind(kind_for(level)),
        // The level itself, not an id. This is the only field in the format that is a number.
        kind_detail: level,
        flags: FLAG_DETAIL_NUMERIC,
        // A border is a line even when it closes. Filling it would paint over every layer inside
        // the country, and — the reason an attempt to make it an area had to be reverted — a
        // polygon clipped to a tile grows edges along the tile boundary, which the style's `line`
        // layers then stroke as a grid across the whole map. See the module docs.
        area: false,
        min_zoom: min_zoom_for(level),
        min_area_px: 0.0,
    })
}

/// The name for an administrative level, as upstream spells it.
fn kind_for(level: u16) -> &'static str {
    match level {
        0..=2 => "country",
        3..=4 => "region",
        5..=6 => "county",
        _ => "locality",
    }
}

/// How shallow a level is worth drawing.
///
/// A country border carries a world tile. A city limit at z4 is noise — and there are a hundred
/// thousand of them.
fn min_zoom_for(level: u16) -> u8 {
    match level {
        0..=2 => 0,
        3..=4 => 3,
        5..=6 => 6,
        _ => 9,
    }
}

/// The ISO 3166-1 alpha-2 code of a country relation, or `None` for anything that is not one.
///
/// `admin_level=2` and nothing else: level 4 is a state, and a state has no traffic convention of
/// its own anywhere this build covers. The code is read verbatim rather than matched against a
/// list, because [`convention_for`] is the only thing that looks at it and an unknown code there
/// is already the default rather than an error.
pub fn country_code(tags: &(impl TagSource + ?Sized)) -> Option<&str> {
    if tags.get("boundary") != Some("administrative") || tags.get("admin_level")?.trim() != "2" {
        return None;
    }
    tags.get("ISO3166-1").map(str::trim).filter(|code| code.len() == 2)
}

/// Countries where traffic keeps left.
///
/// The list is what it is: there is no rule to derive it from, and the ones missing from it get
/// right-hand traffic, which is most of the world by land area and the safer thing to be wrong
/// about. British overseas territories are here individually because OSM gives each its own
/// `admin_level=2` relation and its own code.
const LEFT_HAND: &[&str] = &[
    "AG", "AI", "AU", "BB", "BD", "BM", "BN", "BS", "BT", "BW", "CC", "CK", "CX", "CY", "DM", "FJ",
    "FK", "GB", "GD", "GG", "GY", "HK", "ID", "IE", "IM", "IN", "JE", "JM", "JP", "KE", "KI", "KN",
    "KY", "LC", "LK", "LS", "MO", "MS", "MT", "MU", "MV", "MW", "MY", "MZ", "NA", "NF", "NP", "NR",
    "NU", "NZ", "PG", "PK", "PN", "SB", "SC", "SG", "SH", "SR", "SZ", "TC", "TH", "TL", "TO", "TT",
    "TV", "TZ", "UG", "VC", "VG", "VI", "WS", "ZA", "ZM", "ZW",
];

/// Countries where a **yellow** line separates opposing directions.
///
/// The Americas, plus Japan and South Korea. Everywhere else the centre line is white and only its
/// style distinguishes it from a lane divider, which is what [`MarkingConvention`]'s default says.
const YELLOW_CENTRE: &[&str] = &[
    "AR", "BO", "BR", "BS", "BZ", "CA", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "GY", "HN", "HT",
    "JM", "JP", "KR", "MX", "NI", "PA", "PE", "PR", "PY", "SR", "SV", "TT", "US", "UY", "VE",
];

/// The convention a country's roads are marked to.
///
/// An unlisted code is right-hand traffic with a white centre line, which is
/// [`MarkingConvention`]'s own default and what the renderer falls back to when a tile carries no
/// convention at all — so an unrecognised country and an unresolved tile draw the same, rather
/// than differently for no reason a reader could see.
pub fn convention_for(code: &str) -> MarkingConvention {
    let listed = |list: &[&str]| list.iter().any(|entry| entry.eq_ignore_ascii_case(code));
    MarkingConvention { left_hand: listed(LEFT_HAND), yellow_centre: listed(YELLOW_CENTRE) }
}

/// The zoom the country under a tile is resolved at.
///
/// **This is the whole affordability argument.** A California build is 12.7 M z14 tiles and a
/// planet build is billions, and a point-in-polygon test against every country for each of them is
/// not a cost this build can carry — while the answer is identical across enormous stretches of
/// them, because a convention is a property of a country rather than of a street. So it is
/// resolved once per z6 tile (4096 of them for the whole world, a few hundred kilometres each) and
/// every tile below inherits its ancestor's.
///
/// The error that buys is confined to z6 tiles a border runs through, and only where the two
/// countries either side disagree — the United States and Mexico agree, so do France and Germany;
/// Thailand and Cambodia do not. A handful of tiles drawing a centre line on the wrong side is the
/// price of not projecting a polygon set per tile.
pub const COARSE_ZOOM: u8 = 6;

/// Which convention applies where, as a sparse grid of [`COARSE_ZOOM`] tiles.
///
/// Sparse because most of the grid is ocean, and because a build of one state has no opinion about
/// the rest of the world: an unclaimed tile reads back as [`MarkingConvention::default`], which is
/// exactly what a tile with no convention at all means to the renderer.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Conventions {
    /// `(convention, was claimed by an interior tile)`, keyed by `y * 2^COARSE_ZOOM + x`.
    ///
    /// The flag is what settles a coastal or border tile. A country claims a tile twice over: once
    /// for covering its centre, which is unambiguous, and once for merely having a boundary vertex
    /// in it, which several countries can do at once. An interior claim therefore overrides a
    /// border claim, and between two claims of the same standing the first wins — which is
    /// relation order, which is the PBF's order, which is what keeps the archive reproducible.
    at: HashMap<u32, (MarkingConvention, bool)>,
}

impl Conventions {
    /// Stamp one country's shape onto the grid.
    ///
    /// `polygons` is the relation's assembled rings in lon/lat, exactly as the `boundaries` region
    /// shape carries them — outer first, then holes, which is what makes the even-odd fill below
    /// treat an enclave as a hole rather than as more of the country.
    pub fn add(&mut self, code: &str, polygons: &[Vec<Vec<(f64, f64)>>]) {
        let convention = convention_for(code);
        let side = (1u32 << COARSE_ZOOM) as f64;
        for rings in polygons {
            // Projected once per ring rather than per scanline: this is the same
            // [`tile_build::geom::project`] the tiler places every feature with, so a country's
            // edge lands where its border line does.
            let projected: Vec<Vec<(f64, f64)>> = rings
                .iter()
                .map(|ring| {
                    ring.iter()
                        .map(|&(lon, lat)| tile_build::geom::project(lon, lat, COARSE_ZOOM))
                        .collect()
                })
                .collect();
            for ring in &projected {
                for &(x, y) in ring {
                    self.claim(x, y, convention, false);
                }
            }
            self.fill(&projected, convention, side);
        }
    }

    /// Claim every coarse tile whose centre the polygon covers, one row at a time.
    ///
    /// A scanline rather than a test per tile: the crossings of one horizontal line against every
    /// ring cost one pass over the vertices, and there are only 64 rows in the whole world. Testing
    /// each candidate tile instead would walk a country's rings once per tile, and a country's
    /// rings run to hundreds of thousands of points.
    fn fill(&mut self, rings: &[Vec<(f64, f64)>], convention: MarkingConvention, side: f64) {
        let (mut top, mut bottom) = (f64::MAX, f64::MIN);
        for ring in rings {
            for &(_, y) in ring {
                top = top.min(y);
                bottom = bottom.max(y);
            }
        }
        if top > bottom {
            return;
        }
        let first = top.floor().max(0.0) as u32;
        let last = bottom.floor().min(side - 1.0) as u32;
        let mut crossings: Vec<f64> = Vec::new();
        for row in first..=last {
            let at = row as f64 + 0.5;
            crossings.clear();
            for ring in rings {
                // Wrapping rather than `windows(2)`, so a ring that arrived unclosed still gets its
                // closing edge. A closed one's wrap edge is degenerate and crosses nothing.
                for i in 0..ring.len() {
                    let (ax, ay) = ring[i];
                    let (bx, by) = ring[(i + 1) % ring.len()];
                    // Half-open in `y`, so a vertex exactly on the scanline is counted once rather
                    // than zero or twice — the usual even-odd rule.
                    if (ay <= at) == (by <= at) {
                        continue;
                    }
                    crossings.push(ax + (at - ay) * (bx - ax) / (by - ay));
                }
            }
            crossings.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
            for span in crossings.chunks(2) {
                let [from, to] = span else { continue };
                // A tile is inside when its *centre* is, which is `tx + 0.5` in `from..=to`.
                let low = (from - 0.5).ceil().max(0.0);
                let high = (to - 0.5).floor().min(side - 1.0);
                let mut tx = low;
                while tx <= high {
                    self.claim(tx, row as f64, convention, true);
                    tx += 1.0;
                }
            }
        }
    }

    fn claim(&mut self, x: f64, y: f64, convention: MarkingConvention, interior: bool) {
        let side = 1u32 << COARSE_ZOOM;
        if !(x.is_finite() && y.is_finite()) {
            return;
        }
        let (tx, ty) = (
            (x.floor().max(0.0) as u32).min(side - 1),
            (y.floor().max(0.0) as u32).min(side - 1),
        );
        let held = self.at.entry(ty * side + tx).or_insert((convention, interior));
        // An interior claim outranks the border claim of whichever country reached the tile first.
        if interior && !held.1 {
            *held = (convention, true);
        }
    }

    /// The convention for a tile, or the default where nothing claimed its coarse ancestor.
    ///
    /// A tile at or below [`COARSE_ZOOM`] inherits the grid cell it sits in; one above it takes
    /// the cell at its own centre, which is the only sensible answer when a tile spans several
    /// countries and is drawn at a zoom where no lane marking is visible anyway.
    pub fn at_tile(&self, z: u8, x: u64, y: u64) -> MarkingConvention {
        let side = 1u64 << COARSE_ZOOM;
        let (cx, cy) = if z >= COARSE_ZOOM {
            let shift = z - COARSE_ZOOM;
            (x >> shift, y >> shift)
        } else {
            let step = 1u64 << (COARSE_ZOOM - z);
            ((x * step) + step / 2, (y * step) + step / 2)
        };
        let key = (cy.min(side - 1) * side + cx.min(side - 1)) as u32;
        self.at.get(&key).map(|(convention, _)| *convention).unwrap_or_default()
    }

    /// Does the grid know anything at all? Empty for a build with the `boundaries` layer switched
    /// off, or one whose extract holds no country relation.
    pub fn is_empty(&self) -> bool {
        self.at.is_empty()
    }

    /// The grid as the store index carries it: a `u32` count, then a `u32` cell key and a
    /// [`MarkingConvention`] byte each, ascending by key so a reused store reproduces the run that
    /// built it byte for byte.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut keys: Vec<&u32> = self.at.keys().collect();
        keys.sort_unstable();
        let mut out = Vec::with_capacity(4 + keys.len() * 5);
        out.extend_from_slice(&(keys.len() as u32).to_le_bytes());
        for key in keys {
            out.extend_from_slice(&key.to_le_bytes());
            out.push(self.at[key].0.to_byte());
        }
        out
    }

    /// The inverse of [`Conventions::to_bytes`], returning the grid and the bytes it consumed.
    pub fn from_bytes(raw: &[u8]) -> osm_ingest::proto::Result<(Conventions, usize)> {
        if raw.len() < 4 {
            return osm_ingest::proto::err(
                "a store index's convention grid is truncated".to_string(),
            );
        }
        let count = u32::from_le_bytes(raw[..4].try_into().expect("four bytes")) as usize;
        let end = 4 + count * 5;
        if raw.len() < end {
            return osm_ingest::proto::err(format!(
                "a store index claims {count} convention cell(s) and holds {}",
                (raw.len() - 4) / 5,
            ));
        }
        let mut at = HashMap::with_capacity(count);
        for cell in raw[4..end].chunks_exact(5) {
            let key = u32::from_le_bytes(cell[..4].try_into().expect("four bytes"));
            let convention = MarkingConvention::from_byte(cell[4])
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
            // Resolved already, so every reloaded cell counts as an interior claim — nothing adds
            // to a grid that came off disk.
            at.insert(key, (convention, true));
        }
        Ok((Conventions { at }, end))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    /// **What the style actually reads.** `boundaries_country` filters `kind_detail <= 2`, so the
    /// level has to arrive as a number that compares correctly — not as an interned id whose
    /// ordering is an accident of table position.
    #[test]
    fn the_level_is_carried_as_a_number_not_an_id() {
        for level in 1..=8u16 {
            let class =
                classify_tags(&[("boundary", "administrative"), ("admin_level", &level.to_string())])
                    .expect("a level");
            assert_eq!(class.kind_detail, level, "the field holds the level itself");
            assert_eq!(class.flags, FLAG_DETAIL_NUMERIC, "and says so");
        }
        // The comparison the style makes.
        let country = classify_tags(&[("boundary", "administrative"), ("admin_level", "2")])
            .expect("country");
        let city = classify_tags(&[("boundary", "administrative"), ("admin_level", "8")])
            .expect("city");
        assert!(country.kind_detail <= 2, "drawn by boundaries_country");
        assert!(city.kind_detail > 2, "drawn by the other layer");
    }

    #[test]
    fn a_level_maps_to_the_name_upstream_uses() {
        let name = |level: &str| {
            let class = classify_tags(&[("boundary", "administrative"), ("admin_level", level)])
                .expect(level);
            dict::KINDS[class.kind as usize - 1]
        };
        assert_eq!(name("2"), "country");
        assert_eq!(name("4"), "region");
        assert_eq!(name("6"), "county");
        assert_eq!(name("8"), "locality");
    }

    /// A relation yields the border *and* the region's shape, as two features.
    ///
    /// They cannot be one. The border must stay a line, because clipping a polygon to a tile adds
    /// segments along the tile edge and the style's `boundaries` layer strokes polygon outlines —
    /// which drew a grid across the whole map when this was tried as a single area feature.
    #[test]
    fn a_relation_yields_a_border_line_and_a_region_shape() {
        let tags = [("boundary", "administrative"), ("admin_level", "8")];
        let line = classify_tags(&tags).expect("the border");
        assert!(!line.area, "the border is drawn, so it stays a line");

        let shape = super::region_area(&tags[..], false).expect("the region");
        assert!(shape.area, "the region is a shape, and nothing draws it");
        assert_eq!(shape.layer, dict::LAYER_BOUNDARIES);
        assert_eq!(
            dict::KINDS[shape.kind as usize - 1],
            "region_area",
            "a kind the boundaries layer's whitelist excludes, or it would be stroked",
        );
        assert_eq!(shape.kind_detail, line.kind_detail, "same level, so the mask can tell a city from a country");
        assert!(shape.min_zoom < line.min_zoom, "a mask is wanted where the region fits on screen");
    }

    /// A single way is one segment of a border and encloses nothing.
    #[test]
    fn a_way_has_no_region_shape() {
        let tags = [("boundary", "administrative"), ("admin_level", "2")];
        assert!(super::region_area(&tags[..], true).is_none());
        assert!(super::region_area(&tags[..], false).is_some());
    }

    /// A border is a line even when it closes. Filling it would paint over every layer inside the
    /// country — and a polygon clipped to a tile grows edges along the tile boundary, which the
    /// style's `line` layers stroke as a grid across the map. That is not theoretical: it was
    /// tried, and the tile grid was immediately visible.
    #[test]
    fn a_boundary_is_never_an_area() {
        let class = classify_tags(&[("boundary", "administrative"), ("admin_level", "2")])
            .expect("country");
        assert!(!class.area);
        assert_eq!(class.layer, dict::LAYER_BOUNDARIES);
    }

    #[test]
    fn a_country_border_is_carried_at_world_zoom_and_a_city_limit_is_not() {
        let at = |level: &str| {
            classify_tags(&[("boundary", "administrative"), ("admin_level", level)])
                .expect(level)
                .min_zoom
        };
        assert_eq!(at("2"), 0, "a country border carries a world tile");
        assert!(at("2") < at("4"));
        assert!(at("4") < at("6"));
        assert!(at("6") < at("8"));
        assert_eq!(at("8"), 9, "there are a hundred thousand city limits");
    }

    #[test]
    fn a_level_below_a_city_is_not_drawn() {
        // Level 9 and 10 are wards and neighbourhoods: real data, and a line nobody wants.
        for level in ["9", "10", "11"] {
            assert!(
                classify_tags(&[("boundary", "administrative"), ("admin_level", level)]).is_none(),
                "level {level} should not be drawn",
            );
        }
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", "0")]).is_none(),
            "level 0 is not a level",
        );
    }

    #[test]
    fn a_maritime_administrative_boundary_is_not_drawn() {
        // An EEZ / territorial-water limit: `boundary=administrative` over water, tagged
        // `maritime=yes`. The reference style's boundary layers show inland/coastline admin
        // only, so the tiler drops these rather than drawing lines across open sea.
        assert!(
            classify_tags(&[
                ("boundary", "administrative"),
                ("admin_level", "2"),
                ("maritime", "yes"),
            ])
            .is_none(),
            "a maritime boundary should not be a boundary",
        );
        // And the inland equivalent still classifies.
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", "2")]).is_some(),
            "an inland country border still counts",
        );
    }

    /// The convention is a property of a *country*, so nothing below level 2 carries one: a state
    /// has no driving side of its own anywhere this build covers.
    #[test]
    fn only_a_level_two_relation_with_an_iso_code_is_a_country() {
        let code = |pairs: &[(&str, &str)]| super::country_code(pairs).map(str::to_string);
        assert_eq!(
            code(&[("boundary", "administrative"), ("admin_level", "2"), ("ISO3166-1", "JP")]),
            Some("JP".to_string()),
        );
        // Whitespace around a level is common in real data, as `classify` already allows.
        assert_eq!(
            code(&[("boundary", "administrative"), ("admin_level", " 2 "), ("ISO3166-1", "GB")]),
            Some("GB".to_string()),
        );
        for tags in [
            // A state, which has no convention of its own.
            vec![("boundary", "administrative"), ("admin_level", "4"), ("ISO3166-1", "US")],
            // A country with no code to look up.
            vec![("boundary", "administrative"), ("admin_level", "2")],
            // A three-letter code is the alpha-3 field under the wrong key.
            vec![("boundary", "administrative"), ("admin_level", "2"), ("ISO3166-1", "JPN")],
            vec![("boundary", "protected_area"), ("admin_level", "2"), ("ISO3166-1", "JP")],
            vec![("ISO3166-1", "JP")],
        ] {
            assert!(super::country_code(&tags[..]).is_none(), "{tags:?} is not a country");
        }
    }

    #[test]
    fn the_convention_table_carries_the_driving_side_and_the_centre_line_colour() {
        let at = super::convention_for;
        assert_eq!(at("GB"), MarkingConvention { left_hand: true, yellow_centre: false });
        assert_eq!(at("JP"), MarkingConvention { left_hand: true, yellow_centre: true });
        assert_eq!(at("US"), MarkingConvention { left_hand: false, yellow_centre: true });
        assert_eq!(at("FR"), MarkingConvention::default(), "right-hand and white");
        // An unrecognised code draws the same as an unresolved tile, rather than differently for
        // no reason a reader could see.
        assert_eq!(at("ZZ"), MarkingConvention::default());
        assert_eq!(at("gb"), at("GB"), "OSM's own casing is not the only casing in the wild");
    }

    /// A square degree box as the relation assembler would hand one over: one polygon, one closed
    /// outer ring, lon/lat.
    fn box_over(west: f64, south: f64, east: f64, north: f64) -> Vec<Vec<Vec<(f64, f64)>>> {
        vec![vec![vec![
            (west, south),
            (east, south),
            (east, north),
            (west, north),
            (west, south),
        ]]]
    }

    /// The tile a lon/lat falls in at `z`.
    fn tile_at(lon: f64, lat: f64, z: u8) -> (u64, u64) {
        let (x, y) = tile_build::geom::project(lon, lat, z);
        (x as u64, y as u64)
    }

    /// **Resolved coarse, inherited downward.** A California build is 12.7 M z14 tiles and the
    /// answer is the same across all of them, so the country is decided once per z6 cell and every
    /// tile below reads its ancestor's.
    #[test]
    fn a_country_claims_the_coarse_tiles_it_covers_and_every_tile_below_inherits() {
        let mut grid = Conventions::default();
        grid.add("JP", &box_over(130.0, 30.0, 145.0, 45.0));

        let inside = MarkingConvention { left_hand: true, yellow_centre: true };
        let (x, y) = tile_at(137.0, 37.0, 14);
        assert_eq!(grid.at_tile(14, x, y), inside, "a z14 tile reads its z6 ancestor");
        // Its neighbour is a different z14 tile in the same coarse cell, so it must agree.
        assert_eq!(grid.at_tile(14, x + 1, y + 1), inside);
        let (cx, cy) = tile_at(137.0, 37.0, COARSE_ZOOM);
        assert_eq!(grid.at_tile(COARSE_ZOOM, cx, cy), inside, "and so does the cell itself");
    }

    /// Nothing claimed it, so it draws right-hand and white — which is most of the world by land
    /// area and the safer thing to be wrong about.
    #[test]
    fn an_unclaimed_tile_falls_back_to_right_hand_and_white() {
        let mut grid = Conventions::default();
        grid.add("JP", &box_over(130.0, 30.0, 145.0, 45.0));
        let (x, y) = tile_at(-100.0, 40.0, 14);
        assert_eq!(grid.at_tile(14, x, y), MarkingConvention::default());
        assert!(!Conventions::default().at_tile(14, 0, 0).left_hand, "and so does an empty grid");
    }

    /// A coastal or border cell is claimed by every country with a boundary vertex in it, and only
    /// one of them covers its centre. Covering the centre is the unambiguous claim, so it wins
    /// whatever order the relations arrived in.
    #[test]
    fn covering_a_cells_centre_outranks_merely_having_a_border_in_it() {
        let mut grid = Conventions::default();
        // A shape too small to cover any cell centre: it can only ever be a border claim.
        grid.add("JP", &box_over(-100.02, 40.0, -100.0, 40.02));
        // And a shape that swallows it whole.
        grid.add("US", &box_over(-110.0, 35.0, -95.0, 45.0));
        let (x, y) = tile_at(-100.01, 40.01, 14);
        assert_eq!(
            grid.at_tile(14, x, y),
            MarkingConvention { left_hand: false, yellow_centre: true },
            "the interior claim came second and still won",
        );
    }

    /// `--reuse-store` has to reproduce the run that built the spill. A grid that did not survive
    /// the index would leave a reused build drawing every road right-hand and white, which is not
    /// a difference anything downstream could see.
    #[test]
    fn the_grid_survives_the_store_index() {
        let mut grid = Conventions::default();
        grid.add("JP", &box_over(130.0, 30.0, 145.0, 45.0));
        grid.add("GB", &box_over(-8.0, 50.0, 2.0, 59.0));
        assert!(!grid.is_empty());

        let bytes = grid.to_bytes();
        let (back, used) = Conventions::from_bytes(&bytes).expect("read the grid back");
        assert_eq!(used, bytes.len(), "the whole grid was consumed");
        for (lon, lat) in [(137.0, 37.0), (-3.0, 54.0), (-100.0, 40.0)] {
            let (x, y) = tile_at(lon, lat, 14);
            assert_eq!(back.at_tile(14, x, y), grid.at_tile(14, x, y), "at {lon},{lat}");
        }
        // Ascending by cell key, so two runs of the same build write the same index bytes.
        assert_eq!(bytes, back.to_bytes());
    }

    #[test]
    fn a_truncated_convention_grid_is_an_error_rather_than_a_short_read() {
        let mut grid = Conventions::default();
        grid.add("GB", &box_over(-8.0, 50.0, 2.0, 59.0));
        let bytes = grid.to_bytes();
        assert!(Conventions::from_bytes(&bytes[..bytes.len() - 1]).is_err(), "a cut cell");
        assert!(Conventions::from_bytes(&bytes[..2]).is_err(), "a cut count");
        // And the empty grid, which is what a build with no country relation writes.
        let (empty, used) = Conventions::from_bytes(&Conventions::default().to_bytes())
            .expect("an empty grid is readable");
        assert!(empty.is_empty());
        assert_eq!(used, 4);
    }

    #[test]
    fn only_an_administrative_boundary_with_a_readable_level_counts() {
        for tags in [
            // A protected area or a maritime boundary is a boundary and not an administrative one.
            vec![("boundary", "protected_area"), ("admin_level", "2")],
            vec![("boundary", "maritime"), ("admin_level", "2")],
            // Administrative but with no level, or an unreadable one.
            vec![("boundary", "administrative")],
            vec![("boundary", "administrative"), ("admin_level", "")],
            vec![("boundary", "administrative"), ("admin_level", "two")],
            vec![("boundary", "administrative"), ("admin_level", "4;6")],
            vec![("admin_level", "2")],
            vec![],
        ] {
            assert!(classify_tags(&tags).is_none(), "{tags:?} should not be a boundary");
        }
        // Whitespace around a level is common in real data and is not a reason to drop a border.
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", " 4 ")]).is_some(),
            "a padded level still parses",
        );
    }
}
