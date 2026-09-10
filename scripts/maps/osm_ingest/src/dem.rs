//! Sample the `.mdem` heightmap dataset (`dem_ingest`) at graph-node coordinates so
//! `write_graph` can bake a per-node elevation into `elevation.bin` — the build side of
//! WS-G's route elevation profile.
//!
//! `dem_ingest` fetches AWS terrarium PNG tiles and downsamples them into one small square `u16`
//! grid per map tile at a single zoom (`--out-zoom`, default 14; `--dim`, default 17), each sample
//! `metres + 32768` row-major from the tile's top-left. That is exactly the layout
//! `mamaps_build`'s `dem.rs` and the wire `Heightmap` carry; this is a *self-contained* reader —
//! `osm_ingest` is deliberately detached from the Android workspace (see `Cargo.toml`), so it
//! parses the format and reproduces the PMTiles Hilbert tile id itself rather than depending on
//! `tilecodec`.
//!
//! [`Dem::sample_metres`] bilinearly interpolates a single lon/lat within the dataset grid that
//! covers it, returning **metres** (already un-biased), or `None` when no fetched grid covers the
//! point — a node off the DEM coverage (open ocean, or outside the build bbox) bakes as 0.

use std::collections::HashMap;
use std::f64::consts::PI;
use std::path::Path;

use crate::proto::{err, Result};

/// The `.mdem` dataset's magic and version, as `dem_ingest` writes them.
const MAGIC: &[u8; 4] = b"MDEM";
const VERSION: u8 = 1;

/// The biased `u16` for zero metres: sea level, and the bias every terrarium sample carries.
const SEA_LEVEL: i32 = 32768;

/// A loaded `.mdem` dataset: one `u16` grid per output tile, keyed by PMTiles tile id at the
/// dataset's own zoom.
pub struct Dem {
    /// The zoom the grids were sampled at.
    out_zoom: u8,
    /// The grid side length, so every grid is `dim * dim` samples. Shared by every tile.
    dim: u16,
    /// `tile_id(out_zoom, x, y)` -> the tile's `dim * dim` samples, row-major from its top-left.
    grids: HashMap<u64, Vec<u16>>,
}

impl Dem {
    /// Read and parse a `.mdem` dataset from disk.
    pub fn load(path: &Path) -> Result<Dem> {
        let bytes = std::fs::read(path)
            .map_err(|e| crate::proto::Error(format!("reading the DEM dataset {}: {e}", path.display())))?;
        Dem::parse(&bytes)
    }

    /// Parse a `.mdem` dataset: a 12-byte header (`MDEM`, version, out_zoom, `dim` u16, tile count
    /// u32), then per tile a `u64` tile id and its `dim * dim` `u16` samples, little-endian.
    fn parse(bytes: &[u8]) -> Result<Dem> {
        if bytes.len() < 12 {
            return err("a .mdem dataset is shorter than its 12-byte header");
        }
        if &bytes[0..4] != MAGIC {
            return err("not a .mdem dataset (bad magic)");
        }
        if bytes[4] != VERSION {
            return err(format!("unsupported .mdem version {} (this build reads {VERSION})", bytes[4]));
        }
        let out_zoom = bytes[5];
        let dim = u16::from_le_bytes([bytes[6], bytes[7]]);
        if dim < 2 {
            return err("a .mdem grid dimension is less than 2");
        }
        let count = u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]) as usize;
        let cells = dim as usize * dim as usize;
        // One tile: an 8-byte id then `cells` little-endian u16 samples.
        let stride = 8 + cells * 2;
        let mut at = 12usize;
        let mut grids = HashMap::with_capacity(count);
        for _ in 0..count {
            if at + stride > bytes.len() {
                return err("a .mdem tile runs past the end of the dataset");
            }
            let id = u64::from_le_bytes(bytes[at..at + 8].try_into().expect("8 bytes"));
            at += 8;
            let mut samples = Vec::with_capacity(cells);
            for i in 0..cells {
                let o = at + i * 2;
                samples.push(u16::from_le_bytes([bytes[o], bytes[o + 1]]));
            }
            at += cells * 2;
            grids.insert(id, samples);
        }
        Ok(Dem { out_zoom, dim, grids })
    }

    /// Elevation in **metres** at one lon/lat, bilinearly interpolated within the dataset grid that
    /// covers it, or `None` when that grid was not fetched (the node bakes as 0 metres).
    pub fn sample_metres(&self, lon: f64, lat: f64) -> Option<i16> {
        let biased = self.sample_biased(lon, lat)?;
        Some((i32::from(biased) - SEA_LEVEL).clamp(i16::MIN as i32, i16::MAX as i32) as i16)
    }

    /// The raw biased `u16` sample (metres + 32768), or `None` off coverage. Mirrors the sampler in
    /// `mamaps_build`'s `dem.rs` so a bake lands where the ingest would have sampled.
    fn sample_biased(&self, lon: f64, lat: f64) -> Option<u16> {
        let n = (1u64 << self.out_zoom) as f64;
        // The fractional tile position at the dataset zoom. Clamped so a point exactly on the
        // world's right/bottom edge lands in the last tile rather than one past it.
        let tx = lon_to_tile_x(lon, self.out_zoom).clamp(0.0, n - f64::EPSILON);
        let ty = lat_to_tile_y(lat, self.out_zoom).clamp(0.0, n - f64::EPSILON);
        let ix = tx.floor() as u64;
        let iy = ty.floor() as u64;
        let grid = self.grids.get(&tile_id(self.out_zoom, ix, iy))?;
        let last = self.dim as usize - 1;
        // The point's position within the tile's grid: the fraction across the tile scaled onto the
        // `dim - 1` cells between the grid's edge samples.
        let gx = (tx - ix as f64) * last as f64;
        let gy = (ty - iy as f64) * last as f64;
        let x0 = gx.floor() as usize;
        let y0 = gy.floor() as usize;
        let x1 = (x0 + 1).min(last);
        let y1 = (y0 + 1).min(last);
        let fx = gx - x0 as f64;
        let fy = gy - y0 as f64;
        let d = self.dim as usize;
        let at = |cx: usize, cy: usize| grid[cy * d + cx] as f64;
        let top = at(x0, y0) * (1.0 - fx) + at(x1, y0) * fx;
        let bot = at(x0, y1) * (1.0 - fx) + at(x1, y1) * fx;
        let v = top * (1.0 - fy) + bot * fy;
        Some(v.round().clamp(0.0, u16::MAX as f64) as u16)
    }
}

// --- PMTiles Hilbert tile ids (a self-contained copy of `tilecodec::pmtiles::tile_id`, so a
// dataset written with that function reads back here without a `tilecodec` dependency) ---

/// Number of tiles in every zoom below `z`, i.e. the first tile id at `z`. `(4^z - 1) / 3`.
fn zoom_base(z: u8) -> u64 {
    ((1u64 << (2 * z as u32)) - 1) / 3
}

/// Rotate/flip a quadrant. The canonical Hilbert helper.
fn rot(n: u64, x: &mut u64, y: &mut u64, rx: u64, ry: u64) {
    if ry == 0 {
        if rx == 1 {
            *x = n.wrapping_sub(1).wrapping_sub(*x);
            *y = n.wrapping_sub(1).wrapping_sub(*y);
        }
        std::mem::swap(x, y);
    }
}

/// `(z, x, y)` -> PMTiles tile id.
fn tile_id(z: u8, x: u64, y: u64) -> u64 {
    let n = 1u64 << z;
    let (mut x, mut y) = (x, y);
    let mut d = 0u64;
    let mut s = n / 2;
    while s > 0 {
        let rx = u64::from(x & s > 0);
        let ry = u64::from(y & s > 0);
        d += s * s * ((3 * rx) ^ ry);
        rot(n, &mut x, &mut y, rx, ry);
        s /= 2;
    }
    zoom_base(z) + d
}

// --- slippy / web-mercator tile math, matching `dem_ingest` / `mamaps_build`'s `dem.rs` ---

fn lon_to_tile_x(lon: f64, z: u8) -> f64 {
    (lon + 180.0) / 360.0 * (1u64 << z) as f64
}

fn lat_to_tile_y(lat: f64, z: u8) -> f64 {
    let r = lat.clamp(-85.05112878, 85.05112878).to_radians();
    (1.0 - (r.tan() + 1.0 / r.cos()).ln() / PI) / 2.0 * (1u64 << z) as f64
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Serialise a synthetic `.mdem` dataset the way `dem_ingest` does, for the parser to read back.
    fn dataset(out_zoom: u8, dim: u16, tiles: &[(u64, Vec<u16>)]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        out.push(out_zoom);
        out.extend_from_slice(&dim.to_le_bytes());
        out.extend_from_slice(&(tiles.len() as u32).to_le_bytes());
        for (id, grid) in tiles {
            out.extend_from_slice(&id.to_le_bytes());
            for &s in grid {
                out.extend_from_slice(&s.to_le_bytes());
            }
        }
        out
    }

    /// A ramp grid whose sample at `(col, row)` is `32768 + row * 10 + col`.
    fn ramp(dim: u16) -> Vec<u16> {
        let d = dim as usize;
        let mut g = Vec::with_capacity(d * d);
        for row in 0..d {
            for col in 0..d {
                g.push(SEA_LEVEL as u16 + (row * 10 + col) as u16);
            }
        }
        g
    }

    /// The lon/lat at the top-left corner of a dataset tile, so a sample there hits grid cell (0,0).
    fn tile_top_left(z: u8, x: u64, y: u64) -> (f64, f64) {
        let n = (1u64 << z) as f64;
        let lon = x as f64 / n * 360.0 - 180.0;
        let ang = PI * (1.0 - 2.0 * y as f64 / n);
        let lat = ang.sinh().atan().to_degrees();
        (lon, lat)
    }

    #[test]
    fn samples_read_back_as_unbiased_metres_over_synthetic_terrain() {
        let (z, dim) = (14u8, 17u16);
        let (x, y) = (2730u64, 6335u64);
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, x, y), ramp(dim))])).expect("parse");
        // The tile's top-left corner is grid cell (0, 0) == SEA_LEVEL, i.e. 0 metres.
        let (lon, lat) = tile_top_left(z, x, y);
        assert_eq!(dem.sample_metres(lon, lat), Some(0), "corner is sea level = 0 m");
    }

    #[test]
    fn a_point_off_coverage_has_no_sample() {
        let (z, dim) = (14u8, 17u16);
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, 2730, 6335), ramp(dim))])).expect("parse");
        // Antarctica, nowhere near the one fetched California tile.
        assert!(dem.sample_metres(0.0, -80.0).is_none(), "a point off coverage bakes as 0");
    }

    #[test]
    fn interior_terrain_interpolates_to_a_positive_elevation() {
        let (z, dim) = (14u8, 17u16);
        let (x, y) = (2730u64, 6335u64);
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, x, y), ramp(dim))])).expect("parse");
        // A point a third of the way into the tile is inside the ramp, so strictly above sea level.
        let n = (1u64 << z) as f64;
        let lon = (x as f64 + 0.5) / n * 360.0 - 180.0;
        let ang = PI * (1.0 - 2.0 * (y as f64 + 0.5) / n);
        let lat = ang.sinh().atan().to_degrees();
        assert!(dem.sample_metres(lon, lat).unwrap() > 0, "interior of the ramp is above 0 m");
    }

    #[test]
    fn a_short_dataset_is_refused() {
        assert!(Dem::parse(b"MDE").is_err(), "shorter than the header");
        assert!(Dem::parse(b"XXXX\x01\x0e\x11\x00\x00\x00\x00\x00").is_err(), "bad magic");
        let mut short = dataset(14, 17, &[]);
        short[8] = 1; // claim one tile with no bytes behind it
        assert!(Dem::parse(&short).is_err(), "a claimed tile past the end is refused");
    }
}
