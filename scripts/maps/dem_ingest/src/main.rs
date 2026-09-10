//! Decode AWS Terrain Tiles ("terrarium" PNG) into a per-map-tile `u16` heightmap grid dataset —
//! the build half of WS-G's 3D terrain relief and the second of the two v6 `.mamaps` side tables.
//!
//! # What it does
//!
//! Given a directory of terrarium PNG tiles (fetched by `build_all.sh`, laid out `<z>/<x>/<y>.png`
//! in the usual slippy convention) and a bounding box, it produces one small square `u16` grid per
//! output map tile by sampling the DEM at each grid point. A sample is metres above sea level
//! **biased by 32768** — `stored = metres + 32768` — which is exactly the number the terrarium
//! encoding already carries before its `-32768`, so no precision is lost round-tripping it, and it
//! is the same convention [`tilecodec::mamaps::body::Heightmap`] stores on the wire.
//!
//! terrarium decode: `elevation_m = R*256 + G + B/256 - 32768`, so `stored = round(R*256 + G +
//! B/256)`.
//!
//! # Why the network is not here
//!
//! `build_all.sh` owns network I/O for every stage; this crate does the bytes-to-bytes work alone
//! (PNG decode, height decode, downsample), which is why its only dependency is the pure-Rust
//! `miniz_oxide` a PNG's zlib IDAT needs to inflate.
//!
//! # Output
//!
//! A single `.mdem` dataset: a header, then per non-empty tile a pmtiles tile id and its
//! `dim * dim` samples, row-major from the tile's top-left. Ocean/flat tiles are omitted with
//! `--skip-flat`, matching the wire format's rule that an elevation-free tile carries no section.
//! Wiring this dataset into the archive's `BODY_FLAG_HEIGHTMAP` section is a small follow-up step
//! (or WS-G's, at render integration); this tool produces the grids the format is already able to
//! carry and round-trip.

use std::collections::HashMap;
use std::f64::consts::PI;
use std::path::PathBuf;

use tilecodec::pmtiles::tile_id;

const OUT_MAGIC: &[u8; 4] = b"MDEM";
const OUT_VERSION: u8 = 1;

fn main() {
    if let Err(e) = run() {
        eprintln!("dem_ingest: {e}");
        std::process::exit(1);
    }
}

struct Args {
    tiles_dir: PathBuf,
    out: PathBuf,
    out_zoom: u8,
    dem_zoom: u8,
    dim: u16,
    bbox: (f64, f64, f64, f64),
    skip_flat: bool,
}

fn run() -> Result<(), String> {
    let args = parse_args()?;
    if args.dim < 2 {
        return Err("--dim must be at least 2".to_string());
    }
    let n = 1u64 << args.out_zoom;
    // The output tile range the bbox covers, clamped to the world.
    let (min_lon, min_lat, max_lon, max_lat) = args.bbox;
    let x0 = lon_to_tile_x(min_lon, args.out_zoom).floor().max(0.0) as u64;
    let x1 = lon_to_tile_x(max_lon, args.out_zoom).ceil().min(n as f64) as u64;
    // Latitude runs the other way: max_lat is the smaller tile y.
    let y0 = lat_to_tile_y(max_lat, args.out_zoom).floor().max(0.0) as u64;
    let y1 = lat_to_tile_y(min_lat, args.out_zoom).ceil().min(n as f64) as u64;
    if x1 <= x0 || y1 <= y0 {
        return Err(format!(
            "the bbox {min_lon},{min_lat},{max_lon},{max_lat} covers no z{} tiles",
            args.out_zoom
        ));
    }

    // Decoded DEM tiles, cached across output tiles: an output tile's neighbours share DEM tiles,
    // and re-inflating a PNG per grid point would dominate. `None` marks a tile the fetch missed.
    let mut dem_cache: HashMap<(u64, u64), Option<Vec<u16>>> = HashMap::new();
    let mut tiles: Vec<(u64, Vec<u16>)> = Vec::new();
    let mut missing = 0u64;

    for oy in y0..y1 {
        for ox in x0..x1 {
            match sample_tile(&args, ox, oy, &mut dem_cache)? {
                Some(grid) => {
                    if args.skip_flat && is_flat(&grid) {
                        continue;
                    }
                    tiles.push((tile_id(args.out_zoom, ox, oy), grid));
                }
                None => missing += 1,
            }
        }
    }

    // Ascending by tile id, so the dataset is deterministic and a merge into an archive is a
    // straight join in tile order.
    tiles.sort_by_key(|(id, _)| *id);
    write_dataset(&args, &tiles)?;
    println!(
        "dem_ingest: wrote {} tile(s) at z{} ({}x{}) to {} ({} output tile(s) had no DEM coverage)",
        tiles.len(),
        args.out_zoom,
        args.dim,
        args.dim,
        args.out.display(),
        missing,
    );
    Ok(())
}

/// Sample one output tile's grid, or `None` when no DEM tile under it was fetched.
fn sample_tile(
    args: &Args,
    ox: u64,
    oy: u64,
    cache: &mut HashMap<(u64, u64), Option<Vec<u16>>>,
) -> Result<Option<Vec<u16>>, String> {
    let dim = args.dim as usize;
    let out_n = (1u64 << args.out_zoom) as f64;
    let dem_n = (1u64 << args.dem_zoom) as f64;
    let mut grid = Vec::with_capacity(dim * dim);
    let mut any_data = false;
    for row in 0..dim {
        // Grid point fractional position within the output tile, top-left origin.
        let fy = oy as f64 + row as f64 / (dim as f64 - 1.0);
        let lat = tile_y_to_lat(fy, args.out_zoom);
        for col in 0..dim {
            let fx = ox as f64 + col as f64 / (dim as f64 - 1.0);
            let lon = tile_x_to_lon(fx, args.out_zoom);
            // The DEM tile and pixel this lon/lat lands in at the DEM zoom.
            let gx = ((lon + 180.0) / 360.0 * dem_n).clamp(0.0, dem_n - f64::EPSILON);
            let gy = lat_to_tile_y(lat, args.dem_zoom).clamp(0.0, dem_n - f64::EPSILON);
            let dtx = gx.floor() as u64;
            let dty = gy.floor() as u64;
            let px = (((gx - dtx as f64) * 256.0) as usize).min(255);
            let py = (((gy - dty as f64) * 256.0) as usize).min(255);
            let sample = match dem_tile(args, dtx, dty, cache)? {
                Some(tile) => {
                    any_data = true;
                    tile[py * 256 + px]
                }
                // No DEM here: sea level. A tile entirely over missing DEM is dropped below.
                None => 32768,
            };
            grid.push(sample);
        }
        let _ = out_n; // documents the projection's modulus; kept for clarity.
    }
    Ok(any_data.then_some(grid))
}

/// A decoded terrarium DEM tile as 256*256 biased `u16` samples, or `None` if the file is absent.
fn dem_tile<'a>(
    args: &Args,
    x: u64,
    y: u64,
    cache: &'a mut HashMap<(u64, u64), Option<Vec<u16>>>,
) -> Result<&'a Option<Vec<u16>>, String> {
    if !cache.contains_key(&(x, y)) {
        let path = args
            .tiles_dir
            .join(args.dem_zoom.to_string())
            .join(x.to_string())
            .join(format!("{y}.png"));
        let decoded = if path.exists() {
            let bytes = std::fs::read(&path).map_err(|e| format!("reading {}: {e}", path.display()))?;
            Some(decode_terrarium(&bytes).map_err(|e| format!("decoding {}: {e}", path.display()))?)
        } else {
            None
        };
        cache.insert((x, y), decoded);
    }
    Ok(cache.get(&(x, y)).expect("just inserted"))
}

/// A DEM grid is "flat" — an ocean or a plain at one level — when every sample is within a metre or
/// two of the same height. Such a tile carries no relief worth a section, so it is omitted.
fn is_flat(grid: &[u16]) -> bool {
    let (mut lo, mut hi) = (u16::MAX, u16::MIN);
    for &s in grid {
        lo = lo.min(s);
        hi = hi.max(s);
    }
    // Sea level is 32768; a grid that is all within ±2 m of one level (including sea) is flat.
    hi.saturating_sub(lo) <= 2
}

/// Decode a terrarium PNG into 256*256 biased `u16` height samples.
fn decode_terrarium(png: &[u8]) -> Result<Vec<u16>, String> {
    let image = decode_png(png)?;
    if image.width != 256 || image.height != 256 {
        return Err(format!("a terrarium tile is 256x256, got {}x{}", image.width, image.height));
    }
    let ch = image.channels as usize;
    let mut out = Vec::with_capacity(256 * 256);
    for i in 0..256 * 256 {
        let base = i * ch;
        let (r, g, b) = (
            image.pixels[base] as u32,
            image.pixels[base + 1] as u32,
            image.pixels[base + 2] as u32,
        );
        out.push(terrarium_stored(r, g, b));
    }
    Ok(out)
}

/// The biased `u16` for one terrarium pixel: `round(R*256 + G + B/256)`, which is
/// `elevation_m + 32768` and so lands sea level at 32768.
fn terrarium_stored(r: u32, g: u32, b: u32) -> u16 {
    // B/256 rounds to 0 or 1; the top of the range clamps rather than wraps.
    let v = r * 256 + g + if b >= 128 { 1 } else { 0 };
    v.min(u16::MAX as u32) as u16
}

struct Image {
    width: u32,
    height: u32,
    channels: u8,
    pixels: Vec<u8>,
}

/// A minimal PNG decoder: 8-bit RGB or RGBA, non-interlaced, which is what every terrarium tile is.
///
/// Not a general PNG library — palette, 16-bit, greyscale and Adam7 interlace are refused rather
/// than half-supported, because a terrarium tile is never any of them and a silent misdecode would
/// paint the wrong mountain.
fn decode_png(bytes: &[u8]) -> Result<Image, String> {
    const SIG: [u8; 8] = [137, 80, 78, 71, 13, 10, 26, 10];
    if bytes.len() < 8 || bytes[..8] != SIG {
        return Err("not a PNG (bad signature)".to_string());
    }
    let mut at = 8usize;
    let mut width = 0u32;
    let mut height = 0u32;
    let mut colour_type = 0u8;
    let mut idat: Vec<u8> = Vec::new();
    let mut seen_ihdr = false;
    while at + 8 <= bytes.len() {
        let len = u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]) as usize;
        let kind = &bytes[at + 4..at + 8];
        let data_start = at + 8;
        let data_end = data_start
            .checked_add(len)
            .ok_or_else(|| "a PNG chunk length overflows".to_string())?;
        if data_end + 4 > bytes.len() {
            return Err("a PNG chunk runs past the file".to_string());
        }
        let data = &bytes[data_start..data_end];
        match kind {
            b"IHDR" => {
                if len != 13 {
                    return Err("a PNG IHDR is not 13 bytes".to_string());
                }
                width = u32::from_be_bytes([data[0], data[1], data[2], data[3]]);
                height = u32::from_be_bytes([data[4], data[5], data[6], data[7]]);
                let bit_depth = data[8];
                colour_type = data[9];
                let interlace = data[12];
                if bit_depth != 8 {
                    return Err(format!("this decoder needs 8-bit PNG, got {bit_depth}-bit"));
                }
                if interlace != 0 {
                    return Err("this decoder does not read interlaced PNG".to_string());
                }
                if colour_type != 2 && colour_type != 6 {
                    return Err(format!(
                        "this decoder reads RGB(2) or RGBA(6), got colour type {colour_type}"
                    ));
                }
                seen_ihdr = true;
            }
            b"IDAT" => idat.extend_from_slice(data),
            b"IEND" => break,
            _ => {}
        }
        at = data_end + 4; // skip the trailing CRC
    }
    if !seen_ihdr {
        return Err("a PNG has no IHDR".to_string());
    }
    let channels: u8 = if colour_type == 6 { 4 } else { 3 };
    let raw = miniz_oxide::inflate::decompress_to_vec_zlib(&idat)
        .map_err(|e| format!("inflating IDAT failed: {e:?}"))?;
    unfilter(&raw, width, height, channels)
}

/// Reverse PNG's per-scanline filters into a flat pixel buffer. Each scanline is one filter byte
/// then `width * channels` bytes; filters 0..=4 are None, Sub, Up, Average and Paeth.
fn unfilter(raw: &[u8], width: u32, height: u32, channels: u8) -> Result<Image, String> {
    let ch = channels as usize;
    let stride = width as usize * ch;
    let expected = (stride + 1) * height as usize;
    if raw.len() < expected {
        return Err(format!("inflated PNG is {} bytes, expected {expected}", raw.len()));
    }
    let mut out = vec![0u8; stride * height as usize];
    for row in 0..height as usize {
        let filter = raw[row * (stride + 1)];
        let src = &raw[row * (stride + 1) + 1..row * (stride + 1) + 1 + stride];
        let (prev, cur) = out.split_at_mut(row * stride);
        let cur = &mut cur[..stride];
        let prev_row: &[u8] = if row == 0 { &[] } else { &prev[(row - 1) * stride..row * stride] };
        for i in 0..stride {
            let a = if i >= ch { cur[i - ch] as i32 } else { 0 };
            let b = if !prev_row.is_empty() { prev_row[i] as i32 } else { 0 };
            let c = if !prev_row.is_empty() && i >= ch { prev_row[i - ch] as i32 } else { 0 };
            let x = src[i] as i32;
            let value = match filter {
                0 => x,
                1 => x + a,
                2 => x + b,
                3 => x + (a + b) / 2,
                4 => x + paeth(a, b, c),
                other => return Err(format!("unknown PNG filter {other}")),
            };
            cur[i] = (value & 0xff) as u8;
        }
    }
    Ok(Image { width, height, channels, pixels: out })
}

fn paeth(a: i32, b: i32, c: i32) -> i32 {
    let p = a + b - c;
    let (pa, pb, pc) = ((p - a).abs(), (p - b).abs(), (p - c).abs());
    if pa <= pb && pa <= pc {
        a
    } else if pb <= pc {
        b
    } else {
        c
    }
}

// --- slippy / web-mercator tile math ---

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

// --- output ---

fn write_dataset(args: &Args, tiles: &[(u64, Vec<u16>)]) -> Result<(), String> {
    let mut out = Vec::with_capacity(16 + tiles.len() * (8 + args.dim as usize * args.dim as usize * 2));
    out.extend_from_slice(OUT_MAGIC);
    out.push(OUT_VERSION);
    out.push(args.out_zoom);
    out.extend_from_slice(&args.dim.to_le_bytes());
    out.extend_from_slice(&(tiles.len() as u32).to_le_bytes());
    for (id, grid) in tiles {
        out.extend_from_slice(&id.to_le_bytes());
        for &s in grid {
            out.extend_from_slice(&s.to_le_bytes());
        }
    }
    std::fs::write(&args.out, &out).map_err(|e| format!("writing {}: {e}", args.out.display()))
}

// --- CLI ---

fn parse_args() -> Result<Args, String> {
    let mut tiles_dir: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut out_zoom = 14u8;
    let mut dem_zoom = 12u8;
    let mut dim = 17u16;
    let mut bbox: Option<(f64, f64, f64, f64)> = None;
    let mut skip_flat = false;
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--tiles-dir" => tiles_dir = Some(PathBuf::from(next(&mut it, "--tiles-dir")?)),
            "--out" => out = Some(PathBuf::from(next(&mut it, "--out")?)),
            "--out-zoom" => out_zoom = next(&mut it, "--out-zoom")?.parse().map_err(|_| "--out-zoom must be a number")?,
            "--dem-zoom" => dem_zoom = next(&mut it, "--dem-zoom")?.parse().map_err(|_| "--dem-zoom must be a number")?,
            "--dim" => dim = next(&mut it, "--dim")?.parse().map_err(|_| "--dim must be a number")?,
            "--bbox" => bbox = Some(parse_bbox(&next(&mut it, "--bbox")?)?),
            "--skip-flat" => skip_flat = true,
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            other => return Err(format!("unknown argument {other} (try --help)")),
        }
    }
    Ok(Args {
        tiles_dir: tiles_dir.ok_or("--tiles-dir is required")?,
        out: out.ok_or("--out is required")?,
        out_zoom,
        dem_zoom,
        dim,
        bbox: bbox.ok_or("--bbox is required")?,
        skip_flat,
    })
}

fn next(it: &mut impl Iterator<Item = String>, flag: &str) -> Result<String, String> {
    it.next().ok_or_else(|| format!("{flag} needs a value"))
}

fn parse_bbox(s: &str) -> Result<(f64, f64, f64, f64), String> {
    let parts: Vec<f64> = s
        .split(',')
        .map(|p| p.trim().parse::<f64>().map_err(|_| format!("bad bbox component `{p}`")))
        .collect::<Result<_, _>>()?;
    match parts.as_slice() {
        [min_lon, min_lat, max_lon, max_lat] => Ok((*min_lon, *min_lat, *max_lon, *max_lat)),
        _ => Err("--bbox is minlon,minlat,maxlon,maxlat".to_string()),
    }
}

fn print_help() {
    println!(
        "dem_ingest --tiles-dir DIR --bbox minlon,minlat,maxlon,maxlat --out FILE\n\
         \n\
         Decode terrarium PNG tiles (laid out DIR/<z>/<x>/<y>.png) into a per-map-tile u16\n\
         heightmap grid dataset (metres + 32768 bias).\n\
         \n\
         Options:\n\
         \x20 --tiles-dir DIR   directory of terrarium PNGs at the DEM zoom (required)\n\
         \x20 --bbox BOX        minlon,minlat,maxlon,maxlat to cover (required)\n\
         \x20 --out FILE        the .mdem dataset to write (required)\n\
         \x20 --out-zoom Z      map tile zoom to sample a grid per (default 14)\n\
         \x20 --dem-zoom Z      zoom the terrarium tiles were fetched at (default 12, ~30 m)\n\
         \x20 --dim N           grid side length, N*N samples per tile (default 17)\n\
         \x20 --skip-flat       omit tiles with no relief (ocean/flat), matching the wire format\n"
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terrarium_decode_puts_sea_level_at_the_bias() {
        // (128,0,0) is exactly 32768 raw, i.e. 0 m elevation → stored 32768.
        assert_eq!(terrarium_stored(128, 0, 0), 32768);
        // One metre up.
        assert_eq!(terrarium_stored(128, 1, 0), 32769);
        // A metre of sub-metre precision rounds up through the blue channel.
        assert_eq!(terrarium_stored(128, 0, 200), 32769);
        // The Dead Sea shore, ~-430 m, is below the bias.
        assert_eq!(terrarium_stored(126, 82, 0), 32338);
        // The clamp holds at the top rather than wrapping.
        assert_eq!(terrarium_stored(255, 255, 255), u16::MAX);
    }

    #[test]
    fn the_slippy_math_round_trips_a_tile_corner() {
        // A tile's top-left corner maps to a lon/lat that maps back to the tile origin.
        for z in [0u8, 8, 12, 14] {
            let (x, y) = (5u64.min((1 << z) - 1), 9u64.min((1 << z) - 1));
            let lon = tile_x_to_lon(x as f64, z);
            let lat = tile_y_to_lat(y as f64, z);
            assert!((lon_to_tile_x(lon, z) - x as f64).abs() < 1e-6, "z{z} lon round trip");
            assert!((lat_to_tile_y(lat, z) - y as f64).abs() < 1e-6, "z{z} lat round trip");
        }
    }

    #[test]
    fn unfilter_reverses_the_sub_filter() {
        // One 2x1 RGB scanline, filter 1 (Sub): the second pixel is a delta from the first.
        let width = 2;
        let height = 1;
        // filter byte 1, then pixel0 = (10,20,30), pixel1 delta = (1,2,3) → decodes to (11,22,33).
        let raw = vec![1u8, 10, 20, 30, 1, 2, 3];
        let img = unfilter(&raw, width, height, 3).expect("unfilter");
        assert_eq!(img.pixels, vec![10, 20, 30, 11, 22, 33]);
    }

    #[test]
    fn is_flat_omits_a_level_grid_but_keeps_relief() {
        assert!(is_flat(&vec![32768; 289]), "an all-sea-level grid is flat");
        let mut relief = vec![32768u16; 289];
        relief[100] = 32768 + 50;
        assert!(!is_flat(&relief), "50 m of relief is not flat");
    }
}
