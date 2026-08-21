//! The tile pyramid driver, and the drop policy.
//!
//! Turns a list of lon/lat features into a PMTiles archive:
//!
//! ```text
//! per zoom, per feature: bounds -> tile range
//! per tile:              clip -> to_tile -> simplify -> encode -> drop policy
//!                        -> gzip -> pmtiles::Builder
//! ```
//!
//! # The drop policy
//!
//! tippecanoe keeps a tile under its size limit with `--drop-densest-as-needed`, a
//! heuristic that removes whichever features are locally densest and whose result
//! depends on the order it happened to visit them. We do not reproduce it. Instead:
//!
//! 1. Features in a tile are put in a **stable importance order**: largest bounding
//!    box first, ties broken by the feature's index in the input. Size is the best
//!    cheap proxy for "matters at this zoom" -- at z6 a state boundary should
//!    survive and a suburban street should not -- and the index makes it total, so
//!    two runs over the same input always drop the same features.
//! 2. The largest prefix of that order which fits the gzipped byte budget is kept,
//!    found by binary search. A prefix, so the kept set is always the top-k most
//!    important, never a scattered subset.
//! 3. If even one feature does not fit, that one feature is kept anyway and the
//!    tile is reported as over budget. An empty tile is a hole in the map; an
//!    oversized one is a slow tile.
//!
//! Consequences worth being explicit about:
//!
//! * **Per-tile feature counts will not match tippecanoe's.** That is the point of
//!   `test/diff_pmtiles.py --max-feature-delta`: the divergence is bounded and
//!   measured rather than unknown.
//! * **`--extend-zooms-if-still-dropping` is deliberately not reproduced.** It can
//!   push an archive past its own `--maximum-zoom`, so an archive's advertised zoom
//!   range stops being a fact about its contents. Here `--maxzoom` is exactly the
//!   deepest zoom present.
//! * **`--detect-shared-borders` is not implemented.** Adjacent admin polygons are
//!   simplified independently, so two countries sharing a border can end up with
//!   slightly different vertex sets along it. At low zoom that shows as a hairline
//!   gap or a hairline overlap. Reproducing it needs a shared topology pass across
//!   features, which is a project of its own.

use crate::clip::clip_geometry;
use crate::geom::{self, Geometry, IntGeometry};
use crate::mvt::{self, Feature as MvtFeature, GeomType, Layer, Tile, Value, DEFAULT_EXTENT};
use crate::pmtiles::{self, Builder};
use crate::proto::{err, Result};
use crate::simplify;

/// tippecanoe's default maximum gzipped tile size, and what the published
/// archives were built against.
pub const DEFAULT_MAX_TILE_BYTES: usize = 500_000;

/// One feature to tile: geometry in lon/lat, plus its properties.
#[derive(Debug, Clone)]
pub struct Feature {
    pub geometry: Geometry,
    pub props: Vec<(String, Value)>,
}

pub struct Options {
    pub layer: String,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub extent: u32,
    /// Simplification tolerance multiplier; 1.0 is the default policy.
    pub simplification: f64,
    pub max_tile_bytes: usize,
}

impl Options {
    pub fn new(layer: impl Into<String>, min_zoom: u8, max_zoom: u8) -> Options {
        Options {
            layer: layer.into(),
            min_zoom,
            max_zoom,
            extent: DEFAULT_EXTENT,
            simplification: 1.0,
            max_tile_bytes: DEFAULT_MAX_TILE_BYTES,
        }
    }
}

/// What one zoom cost, for the per-zoom report the drop policy owes the operator.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct ZoomStats {
    pub zoom: u8,
    pub tiles: usize,
    /// Feature instances placed into tiles before the drop policy ran. A feature
    /// spanning four tiles counts four times, which is what the budget sees.
    pub placed: usize,
    pub kept: usize,
    pub dropped: usize,
    /// Tiles that could not be brought under budget even at one feature.
    pub over_budget: usize,
    pub largest_tile_bytes: usize,
}

/// Build the archive, and a per-zoom report.
pub fn build_archive(features: &[Feature], opts: &Options) -> Result<(Vec<u8>, Vec<ZoomStats>)> {
    if opts.min_zoom > opts.max_zoom {
        return err("minzoom is above maxzoom");
    }
    if opts.extent == 0 {
        return err("extent 0");
    }

    let geom_type = dominant_geom_type(features);
    let buffer = geom::buffer_for(opts.extent);
    let mut builder = Builder::new();
    builder.min_zoom = opts.min_zoom;
    builder.max_zoom = opts.max_zoom;
    builder.center_zoom = opts.min_zoom;
    builder.metadata = metadata(&opts.layer, opts.min_zoom, opts.max_zoom).into_bytes();
    if let Some(b) = lonlat_bounds(features) {
        builder.min_lon_e7 = e7(b.min_x);
        builder.min_lat_e7 = e7(b.min_y);
        builder.max_lon_e7 = e7(b.max_x);
        builder.max_lat_e7 = e7(b.max_y);
        builder.center_lon_e7 = e7((b.min_x + b.max_x) / 2.0);
        builder.center_lat_e7 = e7((b.min_y + b.max_y) / 2.0);
    }

    let mut report = Vec::new();
    for z in opts.min_zoom..=opts.max_zoom {
        let mut stats = ZoomStats { zoom: z, ..Default::default() };
        let tolerance = simplify::tolerance_for(z, opts.max_zoom, opts.simplification);

        // Project once per zoom, not once per tile: a coastline can cross thousands
        // of tiles and the projection is the expensive part.
        let projected: Vec<Option<Geometry>> = features
            .iter()
            .map(|f| Some(geom::project_geometry(&f.geometry, z, opts.extent)))
            .collect();

        // tile -> the features that reach it, in input order.
        let mut by_tile: std::collections::HashMap<(u64, u64), Vec<usize>> =
            std::collections::HashMap::new();
        for (i, p) in projected.iter().enumerate() {
            let Some(p) = p else { continue };
            let Some(b) = geom::bounds(p) else { continue };
            let Some(range) = geom::tile_range(&b, z, opts.extent, buffer) else {
                continue;
            };
            for (tx, ty) in range.iter() {
                by_tile.entry((tx, ty)).or_default().push(i);
            }
        }

        let mut tiles: Vec<(u64, u64)> = by_tile.keys().copied().collect();
        tiles.sort_unstable();
        for (tx, ty) in tiles {
            let indices = &by_tile[&(tx, ty)];
            let rect = geom::tile_rect(tx, ty, opts.extent, buffer);

            // Clip, move into the tile, simplify. Anything that vanishes here never
            // reached the tile in the first place, so it is not a "drop".
            let mut candidates: Vec<(usize, IntGeometry)> = Vec::with_capacity(indices.len());
            for &i in indices {
                let p = projected[i].as_ref().expect("filtered above");
                let clipped = clip_geometry(p, &rect);
                let local = geom::to_tile(&clipped, tx, ty, opts.extent);
                let simplified = simplify::simplify(&local, tolerance);
                if simplified.is_empty() {
                    continue;
                }
                candidates.push((i, simplified));
            }
            if candidates.is_empty() {
                continue;
            }
            stats.placed += candidates.len();

            // The stable importance order: largest first, index breaking ties.
            candidates.sort_by(|a, b| {
                extent_of(&b.1)
                    .cmp(&extent_of(&a.1))
                    .then_with(|| a.0.cmp(&b.0))
            });

            let (body, kept, over) = fit_tile(&candidates, features, &opts.layer, geom_type, opts)?;
            stats.kept += kept;
            stats.dropped += candidates.len() - kept;
            if over {
                stats.over_budget += 1;
            }
            stats.largest_tile_bytes = stats.largest_tile_bytes.max(body.len());
            stats.tiles += 1;
            builder.add_tile_raw(pmtiles::tile_id(z, tx, ty), body);
        }
        report.push(stats);
    }

    Ok((builder.build()?, report))
}

/// Encode the largest prefix of `candidates` that fits the byte budget.
///
/// Binary search on the prefix length: `O(log n)` gzip calls per tile rather than
/// one per dropped feature, and the answer does not depend on the search order.
/// Returns the gzipped body, how many features it holds, and whether it is still
/// over budget -- which happens when even a single feature does not fit, and is the
/// one case worth telling the operator about.
fn fit_tile(
    candidates: &[(usize, IntGeometry)],
    features: &[Feature],
    layer_name: &str,
    geom_type: GeomType,
    opts: &Options,
) -> Result<(Vec<u8>, usize, bool)> {
    let encode = |n: usize| -> Vec<u8> {
        let mut layer = Layer::new(layer_name);
        layer.extent = opts.extent;
        for (i, g) in &candidates[..n] {
            let geometry = match g {
                IntGeometry::Points(p) => mvt::encode_points(p),
                IntGeometry::Lines(l) => mvt::encode_lines(l),
                IntGeometry::Polygons(p) => mvt::encode_polygons(p),
            };
            if geometry.is_empty() {
                continue;
            }
            layer.features.push(MvtFeature {
                id: None,
                geom_type,
                geometry,
                props: features[*i].props.clone(),
            });
        }
        crate::gz::compress(&Tile { layers: vec![layer] }.encode())
    };

    let all = encode(candidates.len());
    if all.len() <= opts.max_tile_bytes {
        return Ok((all, candidates.len(), false));
    }

    // Largest n in 1..len with encode(n) within budget. `lo` is always known to
    // fit or to be the floor of 1; `hi` is known not to.
    let mut lo = 1usize;
    let mut hi = candidates.len();
    let mut best = encode(1);
    while lo < hi {
        let mid = lo + (hi - lo).div_ceil(2);
        let body = encode(mid);
        if body.len() <= opts.max_tile_bytes {
            best = body;
            lo = mid;
        } else {
            hi = mid - 1;
        }
    }
    // One feature that does not fit is kept anyway: an empty tile is a hole in the
    // map, an oversized one is merely slow.
    let over = best.len() > opts.max_tile_bytes;
    Ok((best, lo, over))
}

/// A cheap importance proxy: the geometry's span in tile units, as a single
/// number. Bigger means more of the tile is affected by keeping it.
fn extent_of(g: &IntGeometry) -> i64 {
    let mut min = (i32::MAX, i32::MAX);
    let mut max = (i32::MIN, i32::MIN);
    let mut seen = false;
    let mut add = |(x, y): (i32, i32)| {
        seen = true;
        min = (min.0.min(x), min.1.min(y));
        max = (max.0.max(x), max.1.max(y));
    };
    match g {
        IntGeometry::Points(p) => p.iter().for_each(|p| add(*p)),
        IntGeometry::Lines(l) => l.iter().flatten().for_each(|p| add(*p)),
        IntGeometry::Polygons(p) => p.iter().flatten().flatten().for_each(|p| add(*p)),
    }
    if !seen {
        return 0;
    }
    (max.0 as i64 - min.0 as i64) + (max.1 as i64 - min.1 as i64)
}

/// The MVT geometry type for the layer.
///
/// MVT tags each feature individually, but a layer is styled as one thing, so a
/// mixed layer is a mistake somewhere upstream. Taking the first feature's type
/// and applying it throughout makes that mistake visible as wrong rendering rather
/// than hiding it as a silently split layer.
fn dominant_geom_type(features: &[Feature]) -> GeomType {
    match features.first().map(|f| &f.geometry) {
        Some(Geometry::Points(_)) => GeomType::Point,
        Some(Geometry::Lines(_)) => GeomType::LineString,
        Some(Geometry::Polygons(_)) => GeomType::Polygon,
        None => GeomType::Unknown,
    }
}

fn lonlat_bounds(features: &[Feature]) -> Option<geom::Rect> {
    let mut acc: Option<geom::Rect> = None;
    for f in features {
        if let Some(b) = geom::bounds(&f.geometry) {
            acc = Some(match acc {
                None => b,
                Some(a) => geom::Rect {
                    min_x: a.min_x.min(b.min_x),
                    min_y: a.min_y.min(b.min_y),
                    max_x: a.max_x.max(b.max_x),
                    max_y: a.max_y.max(b.max_y),
                },
            });
        }
    }
    acc
}

fn e7(deg: f64) -> i32 {
    (deg * 1e7).round().clamp(i32::MIN as f64, i32::MAX as f64) as i32
}

/// The `json` metadata blob a PMTiles archive carries. MapLibre does not need it to
/// render a styled layer, but `pmtiles show` and friends read it, so emitting a
/// truthful `vector_layers` list keeps the archive introspectable.
fn metadata(layer_name: &str, min_zoom: u8, max_zoom: u8) -> String {
    format!(
        "{{\"vector_layers\":[{{\"id\":\"{layer_name}\",\"minzoom\":{min_zoom},\
         \"maxzoom\":{max_zoom}}}]}}"
    )
}

/// Print the per-zoom report the drop policy owes the operator.
pub fn print_report(report: &[ZoomStats], out: &mut impl std::io::Write) -> std::io::Result<()> {
    writeln!(out, "  zoom  tiles   placed     kept  dropped  largest")?;
    for s in report {
        writeln!(
            out,
            "  z{:<4} {:>6} {:>8} {:>8} {:>8} {:>8}{}",
            s.zoom,
            s.tiles,
            s.placed,
            s.kept,
            s.dropped,
            s.largest_tile_bytes,
            if s.over_budget > 0 {
                format!("  ({} tile(s) over budget)", s.over_budget)
            } else {
                String::new()
            }
        )?;
    }
    Ok(())
}

// --- the shared CLI -------------------------------------------------------

/// Which geometry a binary will tile. A layer is styled as one thing, so mixing
/// kinds in one archive is a mistake upstream; each binary accepts exactly one and
/// counts what it turns away.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Accept {
    Lines,
    Polygons,
}

impl Accept {
    fn matches(self, g: &Geometry) -> bool {
        matches!(
            (self, g),
            (Accept::Lines, Geometry::Lines(_)) | (Accept::Polygons, Geometry::Polygons(_))
        )
    }

    fn describe(self) -> &'static str {
        match self {
            Accept::Lines => "LineString or MultiLineString",
            Accept::Polygons => "Polygon or MultiPolygon",
        }
    }
}

/// `tile_lines` and `tile_polygons` are the same program with a different
/// [`Accept`], so they share one implementation and differ only in the name they
/// print.
pub fn cli_main(name: &str, accept: Accept, argv: &[String]) -> std::process::ExitCode {
    use std::process::ExitCode;

    let usage = || {
        eprintln!("usage: {name} --geojson IN.geojsonseq --out OUT.pmtiles --layer NAME");
        eprintln!("                  [--minzoom N] [--maxzoom N] [--simplification F]");
        eprintln!("                  [--max-tile-bytes N] [--extent N]");
    };

    let mut geojson = None;
    let mut out = None;
    let mut layer = None;
    let mut min_zoom = 11u8;
    let mut max_zoom = 14u8;
    let mut simplification = 1.0f64;
    let mut max_tile_bytes = DEFAULT_MAX_TILE_BYTES;
    let mut extent = DEFAULT_EXTENT;

    let mut i = 0;
    while i < argv.len() {
        let value = || argv.get(i + 1).cloned();
        match argv[i].as_str() {
            "--geojson" => {
                geojson = value();
                i += 2;
            }
            "--out" => {
                out = value();
                i += 2;
            }
            "--layer" => {
                layer = value();
                i += 2;
            }
            // A bad numeric value is fatal rather than falling back to the default:
            // silently tiling z11-14 when the caller asked for z0-8 produces an
            // archive that looks fine and is wrong.
            flag @ ("--minzoom" | "--maxzoom" | "--simplification" | "--max-tile-bytes"
            | "--extent") => {
                let Some(raw) = value() else {
                    eprintln!("{name}: {flag} needs a value");
                    return ExitCode::from(2);
                };
                let ok = match flag {
                    "--minzoom" => raw.parse().map(|v| min_zoom = v).is_ok(),
                    "--maxzoom" => raw.parse().map(|v| max_zoom = v).is_ok(),
                    "--simplification" => raw.parse().map(|v| simplification = v).is_ok(),
                    "--max-tile-bytes" => raw.parse().map(|v| max_tile_bytes = v).is_ok(),
                    _ => raw.parse().map(|v| extent = v).is_ok(),
                };
                if !ok {
                    eprintln!("{name}: {flag} wants a number, got '{raw}'");
                    return ExitCode::from(2);
                }
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => {
                eprintln!("{name}: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
        }
    }

    let (Some(geojson), Some(out), Some(layer)) = (geojson, out, layer) else {
        eprintln!("{name}: --geojson, --out and --layer are all required");
        usage();
        return ExitCode::from(2);
    };
    if min_zoom > max_zoom {
        eprintln!("{name}: --minzoom {min_zoom} is above --maxzoom {max_zoom}");
        return ExitCode::from(2);
    }

    let text = match std::fs::read_to_string(&geojson) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("{name}: cannot read {geojson}: {e}");
            return ExitCode::FAILURE;
        }
    };

    let mut features = Vec::new();
    let mut skipped = 0usize;
    for (n, line) in text.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        match crate::geojson::parse_feature(line) {
            Some(f) if accept.matches(&f.geometry) => features.push(Feature {
                geometry: f.geometry,
                props: f.props,
            }),
            _ => {
                skipped += 1;
                if skipped <= 5 {
                    eprintln!(
                        "{name}: skipping line {} (not a {})",
                        n + 1,
                        accept.describe()
                    );
                }
            }
        }
    }
    if skipped > 5 {
        eprintln!("{name}: ... and {} more skipped line(s)", skipped - 5);
    }
    if features.is_empty() {
        eprintln!("{name}: no {} features in {geojson}", accept.describe());
        return ExitCode::FAILURE;
    }

    let opts = Options {
        layer: layer.clone(),
        min_zoom,
        max_zoom,
        extent,
        simplification,
        max_tile_bytes,
    };
    let (bytes, report) = match build_archive(&features, &opts) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("{name}: {e}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(e) = std::fs::write(&out, &bytes) {
        eprintln!("{name}: cannot write {out}: {e}");
        return ExitCode::FAILURE;
    }

    eprintln!(
        "{name}: wrote {out} ({:.1} MiB): {} feature(s), layer '{layer}', z{min_zoom}-{max_zoom}",
        bytes.len() as f64 / (1024.0 * 1024.0),
        features.len(),
    );
    let _ = print_report(&report, &mut std::io::stderr());
    let dropped: usize = report.iter().map(|s| s.dropped).sum();
    if dropped > 0 {
        eprintln!(
            "{name}: {dropped} feature placement(s) dropped by the {max_tile_bytes}-byte \
             per-tile budget"
        );
    }
    ExitCode::SUCCESS
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pmtiles::Archive;

    fn line(coords: &[(f64, f64)], props: Vec<(&str, Value)>) -> Feature {
        Feature {
            geometry: Geometry::Lines(vec![coords.to_vec()]),
            props: props.into_iter().map(|(k, v)| (k.to_string(), v)).collect(),
        }
    }

    fn square(min_lon: f64, min_lat: f64, size: f64, name: &str) -> Feature {
        Feature {
            geometry: Geometry::Polygons(vec![vec![vec![
                (min_lon, min_lat),
                (min_lon + size, min_lat),
                (min_lon + size, min_lat + size),
                (min_lon, min_lat + size),
                (min_lon, min_lat),
            ]]]),
            props: vec![("name".to_string(), Value::String(name.into()))],
        }
    }

    fn tile_at(archive: &Archive, z: u8, lon: f64, lat: f64) -> Option<Tile> {
        let (fx, fy) = geom::project(lon, lat, z);
        archive
            .tile(z, fx.floor() as u64, fy.floor() as u64)
            .unwrap()
            .map(|b| Tile::decode(&b).unwrap())
    }

    #[test]
    fn a_line_archive_round_trips_through_the_reader() {
        let features = vec![line(
            &[(-122.42, 37.77), (-122.40, 37.79), (-122.38, 37.78)],
            vec![("maxspeed", Value::String("25 mph".into()))],
        )];
        let (bytes, report) = build_archive(&features, &Options::new("maxspeed", 10, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!((a.header.min_zoom, a.header.max_zoom), (10, 12));
        assert!(String::from_utf8_lossy(&a.metadata).contains("maxspeed"));
        assert_eq!(report.len(), 3);

        for z in 10..=12u8 {
            let tile = tile_at(&a, z, -122.42, 37.77).unwrap_or_else(|| panic!("a tile at z{z}"));
            let l = tile.layer("maxspeed").unwrap();
            assert_eq!(l.features.len(), 1, "z{z}");
            assert_eq!(l.features[0].geom_type, GeomType::LineString);
            assert_eq!(
                l.features[0].get("maxspeed"),
                Some(&Value::String("25 mph".into()))
            );
            // And the geometry decodes as a line.
            assert!(mvt::decode_lines(&l.features[0].geometry).is_some());
        }
    }

    #[test]
    fn a_polygon_archive_keeps_its_winding_and_its_hole() {
        let with_hole = Feature {
            geometry: Geometry::Polygons(vec![vec![
                vec![(-122.5, 37.7), (-122.3, 37.7), (-122.3, 37.9), (-122.5, 37.9), (-122.5, 37.7)],
                vec![(-122.45, 37.75), (-122.35, 37.75), (-122.35, 37.85), (-122.45, 37.85), (-122.45, 37.75)],
            ]]),
            props: vec![("name".to_string(), Value::String("Oakland".into()))],
        };
        let (bytes, _) = build_archive(&[with_hole], &Options::new("admin_city", 9, 10)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let tile = tile_at(&a, 10, -122.4, 37.8).expect("a tile");
        let f = &tile.layer("admin_city").unwrap().features[0];
        assert_eq!(f.geom_type, GeomType::Polygon);
        let rings = mvt::decode_polygons(&f.geometry).expect("polygon geometry");
        assert_eq!(rings.len(), 1);
        assert_eq!(rings[0].len(), 2, "exterior plus its hole: {rings:?}");
        assert!(mvt::signed_area(&rings[0][0]) > 0, "exterior positive");
        assert!(mvt::signed_area(&rings[0][1]) < 0, "interior negative");
    }

    #[test]
    fn a_feature_spanning_several_tiles_appears_in_each_of_them() {
        // A line across most of California at z6 lands in more than one tile.
        let features = vec![line(&[(-124.0, 40.0), (-116.0, 33.0)], vec![])];
        let (bytes, report) = build_archive(&features, &Options::new("l", 6, 6)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert!(report[0].tiles > 1, "{:?}", report[0]);
        assert!(a.header.addressed_tiles > 1);
        // Every tile it reaches actually carries geometry.
        for (_, raw) in a.iter_tiles().unwrap() {
            let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
            let l = tile.layer("l").unwrap();
            assert_eq!(l.features.len(), 1);
            assert!(!l.features[0].geometry.is_empty());
        }
    }

    #[test]
    fn tiles_a_feature_does_not_reach_are_not_created() {
        let features = vec![line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![])];
        let (bytes, _) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        // A small line at z12 covers a handful of tiles, not the whole grid.
        assert!(a.header.addressed_tiles < 10, "{}", a.header.addressed_tiles);
        assert!(tile_at(&a, 12, -74.0, 40.7).is_none(), "nothing in New York");
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let features = vec![
            line(&[(-122.42, 37.77), (-122.40, 37.79)], vec![("a", Value::Uint(1))]),
            square(-122.5, 37.7, 0.1, "A"),
        ];
        let opts = Options::new("l", 10, 12);
        let (a, ra) = build_archive(&features, &opts).unwrap();
        let (b, rb) = build_archive(&features, &opts).unwrap();
        assert_eq!(a, b, "determinism is a regression surface here");
        assert_eq!(ra, rb);
    }

    // --- the drop policy ---------------------------------------------------

    #[test]
    fn nothing_is_dropped_when_the_tile_fits() {
        let features: Vec<Feature> = (0..50)
            .map(|i| {
                let d = i as f64 * 0.0001;
                line(&[(-122.42 + d, 37.77), (-122.41 + d, 37.78)], vec![])
            })
            .collect();
        let (_, report) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        assert_eq!(report[0].dropped, 0, "{:?}", report[0]);
        assert_eq!(report[0].over_budget, 0);
        assert!(report[0].kept >= 50);
    }

    #[test]
    fn a_tight_budget_drops_the_least_important_features_first() {
        // One long line and many short ones in the same tile, with a budget only a
        // few features wide. Importance is bbox span, so the long one must survive.
        let mut features = vec![line(
            &[(-122.45, 37.75), (-122.35, 37.85)],
            vec![("id", Value::String("long".into()))],
        )];
        for i in 0..200 {
            let d = i as f64 * 0.00005;
            features.push(line(
                &[(-122.40 + d, 37.80), (-122.3999 + d, 37.8001)],
                vec![("id", Value::String(format!("short{i}")))],
            ));
        }
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 400;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].dropped > 0, "the budget must have bitten: {:?}", report[0]);

        let a = Archive::parse(&bytes).unwrap();
        let tile = tile_at(&a, 11, -122.40, 37.80).expect("a tile");
        let ids: Vec<String> = tile
            .layer("l")
            .unwrap()
            .features
            .iter()
            .filter_map(|f| match f.get("id") {
                Some(Value::String(s)) => Some(s.clone()),
                _ => None,
            })
            .collect();
        assert!(ids.contains(&"long".to_string()), "the long line survives: {ids:?}");
    }

    #[test]
    fn a_dropping_tile_stays_within_its_budget() {
        let features: Vec<Feature> = (0..400)
            .map(|i| {
                let d = i as f64 * 0.00002;
                line(
                    &[(-122.40 + d, 37.80), (-122.399 + d, 37.801)],
                    vec![("name", Value::String(format!("street number {i}")))],
                )
            })
            .collect();
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 600;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].dropped > 0);
        assert!(
            report[0].largest_tile_bytes <= 600,
            "largest tile {} exceeds the 600-byte budget",
            report[0].largest_tile_bytes
        );
        // The archive still reads, which is the thing a bad drop breaks.
        let a = Archive::parse(&bytes).unwrap();
        for (_, raw) in a.iter_tiles().unwrap() {
            assert!(Tile::decode(&crate::gz::decompress(raw).unwrap()).is_ok());
        }
    }

    #[test]
    fn one_feature_too_big_for_the_budget_is_kept_and_reported() {
        // An empty tile is a hole in the map; an oversized one is merely slow.
        let long: Vec<(f64, f64)> = (0..4000)
            .map(|i| (-122.42 + (i % 97) as f64 * 0.0001, 37.77 + (i % 89) as f64 * 0.0001))
            .collect();
        let features = vec![line(&long, vec![("name", Value::String("wiggly".into()))])];
        let mut opts = Options::new("l", 14, 14);
        opts.max_tile_bytes = 50;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].over_budget > 0, "{:?}", report[0]);
        assert!(report[0].kept > 0, "the tile must not be empty");
        let a = Archive::parse(&bytes).unwrap();
        assert!(a.header.addressed_tiles > 0);
    }

    #[test]
    fn the_drop_policy_is_deterministic_under_a_tight_budget() {
        let features: Vec<Feature> = (0..300)
            .map(|i| {
                let d = i as f64 * 0.00003;
                line(
                    &[(-122.40 + d, 37.80), (-122.3995 + d, 37.8005)],
                    vec![("name", Value::String(format!("f{i}")))],
                )
            })
            .collect();
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 700;
        let (a, ra) = build_archive(&features, &opts).unwrap();
        let (b, rb) = build_archive(&features, &opts).unwrap();
        assert_eq!(a, b);
        assert_eq!(ra, rb);
        assert!(ra[0].dropped > 0, "the test is only meaningful if it dropped");
    }

    #[test]
    fn simplification_thins_the_shallow_zooms_and_spares_the_deepest() {
        // A wiggly line: at maxzoom the tolerance is zero, so every vertex the clip
        // left must still be there; below it, fewer.
        let coords: Vec<(f64, f64)> = (0..300)
            .map(|i| (-122.45 + i as f64 * 0.0002, 37.80 + (i % 2) as f64 * 0.00005))
            .collect();
        let (bytes, _) = build_archive(&[line(&coords, vec![])], &Options::new("l", 8, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let vertices_at = |z: u8| -> usize {
            let mut n = 0;
            for (id, raw) in a.iter_tiles().unwrap() {
                if pmtiles::tile_zxy(id).0 != z {
                    continue;
                }
                let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
                for f in &tile.layer("l").unwrap().features {
                    n += mvt::decode_lines(&f.geometry)
                        .map(|ls| ls.iter().map(|l| l.len()).sum::<usize>())
                        .unwrap_or(0);
                }
            }
            n
        };
        let deep = vertices_at(12);
        let shallow = vertices_at(8);
        assert!(deep > 0 && shallow > 0);
        assert!(
            shallow < deep,
            "z8 kept {shallow} vertices and z12 kept {deep}; simplification did nothing"
        );
    }

    #[test]
    fn features_that_clip_away_entirely_are_not_counted_as_drops() {
        // Two features far apart: each tile sees one of them, and the other's
        // absence is a clip, not a budget decision.
        let features = vec![
            line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![]),
            line(&[(-74.01, 40.71), (-74.00, 40.72)], vec![]),
        ];
        let (_, report) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        assert_eq!(report[0].dropped, 0, "{:?}", report[0]);
        assert_eq!(report[0].kept, report[0].placed);
    }

    #[test]
    fn an_empty_input_produces_an_empty_but_valid_archive() {
        let (bytes, report) = build_archive(&[], &Options::new("l", 10, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!(a.header.addressed_tiles, 0);
        assert!(report.iter().all(|s| s.tiles == 0));
    }

    #[test]
    fn bad_options_are_refused() {
        let features = vec![line(&[(0.0, 0.0), (1.0, 1.0)], vec![])];
        let mut opts = Options::new("l", 12, 10);
        assert!(build_archive(&features, &opts).is_err(), "minzoom above maxzoom");
        opts = Options::new("l", 10, 12);
        opts.extent = 0;
        assert!(build_archive(&features, &opts).is_err(), "extent 0");
    }

    #[test]
    fn the_report_prints_one_row_per_zoom() {
        let (_, report) = build_archive(
            &[line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![])],
            &Options::new("l", 10, 11),
        )
        .unwrap();
        let mut out = Vec::new();
        print_report(&report, &mut out).unwrap();
        let text = String::from_utf8(out).unwrap();
        assert!(text.contains("z10"), "{text}");
        assert!(text.contains("z11"), "{text}");
    }
}
