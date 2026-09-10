//! Load the `.mdem` heightmap dataset `dem_ingest` produced and hand the tiler one grid per output
//! tile — the second half of WS-G's build side, closing the gap the wire format left open.
//!
//! `dem_ingest` fetches AWS terrarium PNG tiles and downsamples them into one small square `u16`
//! grid per map tile at a single zoom (`--out-zoom`, default 14; `--dim`, default 17), each sample
//! `metres + 32768` row-major from the tile's top-left. That is exactly the layout
//! [`tilecodec::mamaps::body::Heightmap`] carries on the wire, so a tile at the dataset's own zoom
//! is a **verbatim copy** of its grid — no resample, byte for byte what the ingest computed.
//!
//! A tile at any other zoom is **resampled**: `dim * dim` points are sampled across the tile's own
//! extent (the same slippy walk `dem_ingest` does over the terrarium grid), each bilinearly
//! interpolated from the covering dataset grid. A tile with no data under it at all — off the fetch
//! coverage, or open ocean the ingest dropped with `--skip-flat` — yields `None`, so that tile omits
//! the section and stays 16-byte, matching the format's rule.

use std::collections::HashMap;
use std::f64::consts::PI;
use std::path::Path;

use tilecodec::mamaps::body::Heightmap;
use tilecodec::pmtiles::tile_id;
use tilecodec::proto::{err, Error, Result};

/// The `.mdem` dataset's magic and version, as `dem_ingest` writes them.
const MAGIC: &[u8; 4] = b"MDEM";
const VERSION: u8 = 1;

/// The biased `u16` for zero metres: sea level, and what a sample with no DEM under it reads as.
/// The same bias [`Heightmap`] and the terrarium source use.
const SEA_LEVEL: u16 = 32768;

/// A loaded `.mdem` dataset: one `u16` grid per output tile, keyed by pmtiles tile id at the
/// dataset's own zoom.
pub struct Dem {
    /// The zoom the grids were sampled at — the one a tile can copy verbatim rather than resample.
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
            .map_err(|e| Error(format!("reading the DEM dataset {}: {e}", path.display())))?;
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

    /// The heightmap for one output tile, or `None` when no DEM covers it (so the tile omits the
    /// section and stays 16-byte).
    ///
    /// A tile at the dataset's own zoom is a verbatim copy of its grid — byte for byte what the
    /// ingest sampled, independent of neighbour coverage. Any other zoom is resampled onto this
    /// tile's grid.
    pub fn heightmap_for(&self, z: u8, x: u64, y: u64) -> Option<Heightmap> {
        if z == self.out_zoom {
            let samples = self.grids.get(&tile_id(z, x, y))?;
            return Some(Heightmap { dim: self.dim, samples: samples.clone() });
        }
        self.resample(z, x, y)
    }

    /// Resample the dataset's grids onto one tile's `dim * dim` grid, sampling `dim * dim` points
    /// across the tile's own extent from its top-left — the same slippy walk `dem_ingest` runs over
    /// the terrarium grid, sourced from the loaded grids instead of PNGs.
    fn resample(&self, z: u8, x: u64, y: u64) -> Option<Heightmap> {
        let dim = self.dim as usize;
        let mut samples = Vec::with_capacity(dim * dim);
        let mut any = false;
        for row in 0..dim {
            let fy = y as f64 + row as f64 / (self.dim as f64 - 1.0);
            let lat = tile_y_to_lat(fy, z);
            for col in 0..dim {
                let fx = x as f64 + col as f64 / (self.dim as f64 - 1.0);
                let lon = tile_x_to_lon(fx, z);
                match self.sample_at(lon, lat) {
                    Some(v) => {
                        any = true;
                        samples.push(v);
                    }
                    // No DEM here: sea level, as `dem_ingest` fills a missing terrarium pixel. A
                    // tile entirely over missing DEM is dropped below.
                    None => samples.push(SEA_LEVEL),
                }
            }
        }
        any.then_some(Heightmap { dim: self.dim, samples })
    }

    /// Build a dataset directly from grids, for tests that inject a synthetic DEM into the tiler
    /// rather than round-tripping bytes through disk.
    #[cfg(test)]
    pub(crate) fn from_grids(out_zoom: u8, dim: u16, grids: Vec<(u64, Vec<u16>)>) -> Dem {
        Dem { out_zoom, dim, grids: grids.into_iter().collect() }
    }

    /// The elevation at one lon/lat, bilinearly interpolated within the dataset grid that covers it,
    /// or `None` when that grid was not fetched.
    fn sample_at(&self, lon: f64, lat: f64) -> Option<u16> {
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

// --- slippy / web-mercator tile math, matching `dem_ingest` so a resample lands where the ingest
// would have sampled ---

fn lon_to_tile_x(lon: f64, z: u8) -> f64 {
    (lon + 180.0) / 360.0 * (1u64 << z) as f64
}

fn lat_to_tile_y(lat: f64, z: u8) -> f64 {
    let r = lat.clamp(-85.05112878, 85.05112878).to_radians();
    (1.0 - (r.tan() + 1.0 / r.cos()).ln() / PI) / 2.0 * (1u64 << z) as f64
}

fn tile_x_to_lon(x: f64, z: u8) -> f64 {
    x / (1u64 << z) as f64 * 360.0 - 180.0
}

fn tile_y_to_lat(y: f64, z: u8) -> f64 {
    let n = PI * (1.0 - 2.0 * y / (1u64 << z) as f64);
    n.sinh().atan().to_degrees()
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

    /// A ramp grid whose sample at `(col, row)` is `32768 + row * 10 + col`, so a read-back can name
    /// which cell it got.
    fn ramp(dim: u16) -> Vec<u16> {
        let d = dim as usize;
        let mut g = Vec::with_capacity(d * d);
        for row in 0..d {
            for col in 0..d {
                g.push(SEA_LEVEL + (row * 10 + col) as u16);
            }
        }
        g
    }

    #[test]
    fn a_tile_at_the_datasets_zoom_is_a_verbatim_copy() {
        let (z, dim) = (14u8, 17u16);
        let (x, y) = (2730u64, 6335u64);
        let grid = ramp(dim);
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, x, y), grid.clone())])).expect("parse");
        let hm = dem.heightmap_for(z, x, y).expect("a heightmap for the covered tile");
        assert_eq!(hm.dim, dim);
        assert_eq!(hm.samples, grid, "the grid is copied byte for byte at the dataset's own zoom");
        // And it reads back through the wire type's own accessor.
        assert_eq!(hm.sample(0, 0), Some(SEA_LEVEL));
        assert_eq!(hm.sample(3, 2), Some(SEA_LEVEL + 23));
        assert_eq!(Heightmap::metres(hm.sample(3, 2).unwrap()), 23);
        assert_eq!(hm.sample(dim, 0), None, "out of range");
    }

    #[test]
    fn a_tile_off_coverage_has_no_heightmap() {
        let (z, dim) = (14u8, 17u16);
        let dem = Dem::parse(&dataset(z, dim, &[(tile_id(z, 10, 10), ramp(dim))])).expect("parse");
        assert!(dem.heightmap_for(z, 999, 999).is_none(), "an uncovered tile omits the section");
    }

    /// A coarser tile resamples from the covering grids. Sampled over a tile whose whole extent is
    /// covered, a flat field must come back flat and non-empty.
    #[test]
    fn a_coarser_tile_resamples_within_range() {
        let (oz, dim) = (14u8, 17u16);
        // A z13 tile's dim*dim points span its three edge columns/rows of z14 tiles: x in
        // 2730..=2732 and y in 6334..=6336 for z13 (1365, 3167). Cover that whole 3x3 block so no
        // sample point falls off coverage (which would read as sea level).
        let flat: Vec<u16> = vec![SEA_LEVEL + 100; dim as usize * dim as usize];
        let mut tiles = Vec::new();
        for cx in 2730..=2732u64 {
            for cy in 6334..=6336u64 {
                tiles.push((tile_id(oz, cx, cy), flat.clone()));
            }
        }
        let dem = Dem::parse(&dataset(oz, dim, &tiles)).expect("parse");
        let hm = dem.heightmap_for(13, 1365, 3167).expect("a resampled heightmap");
        assert_eq!(hm.dim, dim);
        assert!(
            hm.samples.iter().all(|&s| s == SEA_LEVEL + 100),
            "resampling a flat 100 m field is still 100 m everywhere",
        );
    }

    #[test]
    fn a_short_dataset_is_refused() {
        assert!(Dem::parse(b"MDE").is_err(), "shorter than the header");
        assert!(Dem::parse(b"XXXX\x01\x0e\x11\x00\x00\x00\x00\x00").is_err(), "bad magic");
        // A header claiming one tile with no tile bytes behind it.
        let mut short = dataset(14, 17, &[]);
        short[8] = 1; // claim one tile
        assert!(Dem::parse(&short).is_err(), "a claimed tile past the end is refused");
    }
}
