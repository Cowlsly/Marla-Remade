//! `mamaps_build` — one `.osm.pbf` in, one `.mamaps` archive out.
//!
//! The generator this project exists to build. Today it produces `water` and `buildings`; `roads`,
//! `boundaries`, `landcover`, `landuse` and `earth` follow, and the shape does not change when they
//! do because every layer is one module under [`schema`].
//!
//! ```text
//! mamaps_build --input california.osm.pbf --out california.mamaps
//!              [--layers water,buildings] [--min-zoom 0] [--max-zoom 14]
//!              [--simplification 1.0] [--build-id N] [--report FILE]
//!              [--keep-store] [--reuse-store]
//!
//! `--keep-store` leaves the feature spill and writes a small index beside it; `--reuse-store` then
//! skips stage A entirely and tiles from that spill. Stage A is 17.6 minutes of a north-america
//! build and identical every run for the same input and layer set, so the pair is what makes
//! iterating on the tiler affordable. The index records the source's length and mtime plus the layer
//! selection, and reuse refuses a spill that does not match — a stale spill would otherwise produce
//! an archive that looks fine and is missing most of the world.
//! ```
//!
//! # Why not through the existing tiler
//!
//! `tile_build` already turns GeoJSON-seq into PMTiles, and routing through it would have been less
//! new code. But it would also mean encoding to MVT and decoding again, which loses exactly the
//! attributes this format was built to carry: a road's `is_bridge`, a boundary's numeric admin
//! level, a ring's stated winding. So the geometry is reused — projection, significance,
//! simplification, tile bisection, clipping, all of `tile_build` — and only the last step, turning a
//! clipped shape into bytes, is this crate's.

use std::path::PathBuf;
use std::process::ExitCode;

mod coalesce;
mod extract;
mod rings;
mod shapefile;
mod schema;
mod store;
mod tiler;
mod tilespill;

/// The allocator, replaced because the default one was three quarters of the build.
///
/// A build tool has no business caring which allocator it gets, and this one did not until the
/// encode pass was measured against the machine it runs on: **1358 s of worker CPU against 180 s of
/// wall on a 32-core, 64-thread box** — a speed-up of 7.5 where the pool can give 64. Nothing in
/// that pass is shared. [`rings::normalise`], `body::serialize_into` and DEFLATE each read one tile
/// and write one tile, there is no lock and no atomic in the path with `MAPS_TIMING` off, and the
/// memory traffic works out at a few hundred MB/s against eight channels of DDR5. The only thing
/// all sixty-four threads still contend on is the heap.
///
/// And they hit it hard. `normalise` allocates a `Vec` per polygon group, another for its exterior
/// and one per hole it keeps, on the way to rebuilding a layer's arenas; z14 of a us-west build is
/// 44.4 M features across 943,401 tiles, which is tens of millions of allocate/free pairs on
/// Windows' process heap in about two and a half minutes.
///
/// Swapping it out took the tiling stage from **351 s to 110 s** on the same spill, for a
/// byte-identical archive:
///
/// | phase | default heap | mimalloc |
/// |---|---|---|
/// | map | 125.1 s | 72.9 s |
/// | merge | 33.2 s | 9.6 s |
/// | encode | 180.3 s | 21.6 s |
/// | append | 12.4 s | 3.3 s |
/// | *encode CPU: stage C* | *452.6 s* | *54.4 s* |
/// | *encode CPU: serialise* | *355.9 s* | *5.4 s* |
/// | *encode CPU: deflate* | *550.0 s* | *181.1 s* |
///
/// Serialisation is the one that says what this really was. It is a memcpy into a reused
/// [`tilecodec::mamaps::body::Scratch`] and it cost 356 s of CPU; it now costs 5.4 s. That was never
/// encoding, it was the handful of buffer growths per tile going to `HeapAlloc` under sixty-four
/// threads of contention.
///
/// Two things this is not. It is not a substitute for allocating less — `normalise` should be
/// threading scratch buffers through rather than building a `Vec` per ring, and a cheaper allocator
/// only makes that less urgent. And it is not free: mimalloc holds freed pages back rather than
/// returning them promptly, which took peak RSS from 6.86 GB to 10.14 GB on the same build. That
/// trade is worth revisiting if memory becomes the binding constraint again.
///
/// It cannot change a byte of the output — an allocator decides *where*, never *what* — so it needed
/// no argument about ordering, only a measurement. The us-west archive hashes the same either way.
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut input: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut report: Option<PathBuf> = None;
    let mut keep_store = false;
    let mut reuse_store = false;
    let mut layers = schema::Layers::all();
    let mut min_zoom = 0u8;
    let mut max_zoom = 14u8;
    let mut simplification = tiler::DEFAULT_SIMPLIFICATION;
    let mut build_id: Option<u64> = None;
    let mut coastline: Option<PathBuf> = None;

    let mut i = 1;
    while i < args.len() {
        let value = |name: &str| -> Result<String, String> {
            args.get(i + 1).cloned().ok_or_else(|| format!("{name} needs a value"))
        };
        let taken = match args[i].as_str() {
            "--input" => value("--input").map(|v| {
                input = Some(PathBuf::from(v));
                2
            }),
            "--out" => value("--out").map(|v| {
                out = Some(PathBuf::from(v));
                2
            }),
            "--report" => value("--report").map(|v| {
                report = Some(PathBuf::from(v));
                2
            }),
            "--keep-store" => {
                keep_store = true;
                Ok(1)
            }
            "--reuse-store" => {
                reuse_store = true;
                Ok(1)
            }
            "--coastline" => value("--coastline").map(|v| {
                coastline = Some(PathBuf::from(v));
                2
            }),
            "--layers" => value("--layers").and_then(|v| {
                layers = schema::Layers::parse(&v)?;
                Ok(2)
            }),
            "--min-zoom" => value("--min-zoom").and_then(|v| {
                min_zoom = v.parse().map_err(|_| "--min-zoom must be a number".to_string())?;
                Ok(2)
            }),
            "--max-zoom" => value("--max-zoom").and_then(|v| {
                max_zoom = v.parse().map_err(|_| "--max-zoom must be a number".to_string())?;
                Ok(2)
            }),
            "--simplification" => value("--simplification").and_then(|v| {
                simplification =
                    v.parse().map_err(|_| "--simplification must be a number".to_string())?;
                Ok(2)
            }),
            "--build-id" => value("--build-id").and_then(|v| {
                build_id = Some(v.parse().map_err(|_| "--build-id must be a number".to_string())?);
                Ok(2)
            }),
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => Err(format!("unexpected argument '{other}'")),
        };
        match taken {
            Ok(step) => i += step,
            Err(e) => {
                eprintln!("mamaps_build: {e}");
                usage();
                return ExitCode::from(2);
            }
        }
    }

    let (Some(input), Some(out)) = (input, out) else {
        usage();
        return ExitCode::from(2);
    };
    if min_zoom > max_zoom {
        eprintln!("mamaps_build: --min-zoom {min_zoom} is deeper than --max-zoom {max_zoom}");
        return ExitCode::from(2);
    }

    let settings = RunSettings {
        report,
        keep_store,
        reuse_store,
        coastline,
        layers,
        min_zoom,
        max_zoom,
        simplification,
        build_id,
    };
    match run(&input, &out, &settings) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("mamaps_build: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Everything a run needs beyond its input and output paths.
struct RunSettings {
    report: Option<PathBuf>,
    /// A prepared land polygon for `earth`'s mainland. Without it the layer carries islands only.
    coastline: Option<PathBuf>,
    layers: schema::Layers,
    min_zoom: u8,
    max_zoom: u8,
    simplification: f64,
    build_id: Option<u64>,
    /// Keep the feature spill and write its index, so a later run can `--reuse-store`.
    keep_store: bool,
    /// Skip stage A and read the spill an earlier `--keep-store` run left behind.
    reuse_store: bool,
}

fn run(
    input: &std::path::Path,
    out: &std::path::Path,
    run: &RunSettings,
) -> Result<(), String> {
    let (layers, min_zoom, max_zoom, simplification) =
        (run.layers, run.min_zoom, run.max_zoom, run.simplification);
    let report = run.report.as_deref();
    let started = std::time::Instant::now();
    println!("reading {}", input.display());
    // Features are spilled here rather than held: it was 4.9 GB of a measured 10.03 GB California
    // peak, and nothing reads them until the tiler does.
    let spill = out.with_extension("features.tmp");
    if run.coastline.is_some() && !layers.earth {
        return Err("--coastline was given but the earth layer is not selected".to_string());
    }
    if run.coastline.is_none() && layers.earth {
        // Said out loud, because a map with no mainland is a striking thing to discover later. It is
        // not an error: an island is real data and the renderer's backdrop is the water colour.
        println!("no --coastline given, so `earth` carries islands only and there is no mainland");
    }
    let provenance = store::Provenance::of(input, layers, run.coastline.is_some())
        .map_err(|e| e.to_string())?;
    // Stage A is most of a large build -- 17.6 minutes of a north-america run, and identical every
    // time for the same input and layer set. `--reuse-store` skips it, which is what makes iterating
    // on the tiler affordable. The index records what it was built from and `Store::open` refuses a
    // mismatch, so the shortcut cannot silently produce an archive missing most of the world.
    let (store, stats) = if run.reuse_store {
        let (store, features) =
            store::Store::open(&spill, provenance).map_err(|e| e.to_string())?;
        println!(
            "reusing the feature spill at {} ({} feature(s)); stage A skipped",
            spill.display(),
            features,
        );
        (store, extract::Stats { features, ..Default::default() })
    } else {
        let (store, stats) = extract::extract(input, layers, run.coastline.as_deref(), &spill)
            .map_err(|e| format!("{}: {e}", input.display()))?;
        println!(
            "classified {} way(s) and {} relation(s) -> {} feature(s), {} node(s) resolved",
            stats.ways_classified, stats.relations_classified, stats.features, stats.nodes_needed,
        );
        if stats.geometry_failed > 0 {
            // Expected at an extract's cut edges, and worth reporting because a large count means
            // something else.
            println!("  {} classified element(s) produced no geometry", stats.geometry_failed);
        }
        if stats.land_polygons > 0 {
            println!("  including {} prepared land polygon(s)", stats.land_polygons);
        }
        if run.keep_store {
            let index = store.save_index(provenance, stats.features).map_err(|e| e.to_string())?;
            println!("  wrote {} so --reuse-store can skip stage A", index.display());
        }
        (store, stats)
    };

    // The build id identifies the *data*: change the input, the zoom range, the layer set or the
    // simplification and every reader has to drop its cache. Derived rather than asked for, so a
    // forgotten `--build-id` cannot silently republish under the old one.
    let build_id = run.build_id.unwrap_or_else(|| {
        derive_build_id(input, layers, min_zoom, max_zoom, simplification, stats.features)
    });

    let settings = tiler::Settings {
        min_zoom,
        max_zoom,
        simplification,
        build_id,
        // Beside the archive, as the feature spill is. One zoom at a time, removed as each finishes.
        scratch: scratch_path(out),
    };
    let (bytes, per_zoom) = tiler::build(&store, &settings).map_err(|e| e.to_string())?;
    tiler::check_not_empty(&per_zoom).map_err(|e| e.to_string())?;
    std::fs::write(out, &bytes).map_err(|e| format!("cannot write {}: {e}", out.display()))?;
    // The spill is scratch. Removed on success; left behind on failure, where it is evidence, and
    // kept deliberately under `--keep-store`, where it is the input to the next run.
    if !run.keep_store && !run.reuse_store {
        let _ = std::fs::remove_file(&spill);
    }

    println!(
        "\n{:<6}{:>10}{:>12}{:>12}{:>10}{:>12}{:>8}{:>8}{:>8}{:>8}",
        "zoom", "tiles", "features", "points", "dropped", "bytes", "map_s", "merge_s", "enc_s",
        "app_s",
    );
    for z in &per_zoom {
        println!(
            "z{:<5}{:>10}{:>12}{:>12}{:>10}{:>12}{:>8.1}{:>8.1}{:>8.1}{:>8.1}",
            z.zoom,
            z.tiles,
            z.features,
            z.points,
            z.dropped,
            z.bytes,
            z.map_ms as f64 / 1000.0,
            z.merge_ms as f64 / 1000.0,
            z.encode_ms as f64 / 1000.0,
            z.append_ms as f64 / 1000.0,
        );
    }
    // The four phase columns summed, because which of them dominates is the only thing that says
    // whether more cores would help: map and encode run on the pool, merge and append do not.
    let (map, merge, encode, append) = per_zoom.iter().fold((0u64, 0u64, 0u64, 0u64), |a, z| {
        (a.0 + z.map_ms, a.1 + z.merge_ms, a.2 + z.encode_ms, a.3 + z.append_ms)
    });
    let serial = (merge + append) as f64;
    let total = (map + merge + encode + append).max(1) as f64;
    // What coalescing removed. The `features` column above is counted in `push`, during the map
    // phase and therefore BEFORE the merge that coalescing runs after, so it reports what OSM
    // yielded rather than what was written -- which is the number the map phase's cost tracks. This
    // line is what was actually encoded.
    let lines = per_zoom.iter().fold(coalesce::Stats::default(), |mut a, z| {
        a.add(z.lines);
        a
    });
    if lines.features_before > lines.features_after {
        println!(
            "       coalesced: {} line feature(s) -> {} ({:.0}x), {} part(s) -> {} ({:.1}x)",
            lines.features_before,
            lines.features_after,
            lines.features_before as f64 / lines.features_after.max(1) as f64,
            lines.parts_before,
            lines.parts_after,
            lines.parts_before as f64 / lines.parts_after.max(1) as f64,
        );
    }
    let (stage_c, serialize, deflate) = tiler::encode_seconds();
    // How close the archive came to the 65,535-feature cap on one tile-layer. Printed rather than
    // asserted because the answer is what decides whether the field has to widen, and widening it is
    // a `FORMAT_VERSION` bump and a matching renderer change.
    let widest = tiler::widest_layers();
    if !widest.is_empty() {
        let dict = tilecodec::mamaps::dict::Dictionary::schema();
        let worst = widest.iter().map(|(_, n)| *n).max().unwrap_or(0);
        let named: Vec<String> = widest
            .iter()
            .map(|(id, n)| {
                let name = dict.layer_name(*id).unwrap_or("?");
                format!("{name} {n}")
            })
            .collect();
        println!(
            "       widest tile-layer: {} (cap {}, {:.0}% used)",
            named.join(", "),
            u16::MAX,
            worst as f64 / u16::MAX as f64 * 100.0,
        );
    }
    println!(
        "total  map {:.1}s (of which {:.1}s deserialising the spill, on one thread)  merge {:.1}s  encode {:.1}s  append {:.1}s   ({:.0}% of tiling is serial)",
        map as f64 / 1000.0,
        tiler::read_seconds(),
        merge as f64 / 1000.0,
        encode as f64 / 1000.0,
        append as f64 / 1000.0,
        serial / total * 100.0,
    );
    // CPU, not wall, and summed across workers: compared against the encode wall above it says
    // whether that phase is short of work or short of parallelism. Only under `MAPS_TIMING`,
    // because three atomics per tile is not free at a million tiles.
    if stage_c + serialize + deflate > 0.0 {
        println!(
            "       encode CPU: stage C {stage_c:.1}s  serialise {serialize:.1}s  deflate {deflate:.1}s  \
             (={:.1}s of CPU against {:.1}s of wall)",
            stage_c + serialize + deflate,
            encode as f64 / 1000.0,
        );
    }
    println!(
        "\nwrote {} ({} bytes, build_id {build_id:#018x}) in {:.1}s",
        out.display(),
        bytes.len(),
        started.elapsed().as_secs_f64(),
    );
    if let Some(path) = report {
        std::fs::write(path, build_report(&stats, &per_zoom, build_id, bytes.len()))
            .map_err(|e| format!("cannot write {}: {e}", path.display()))?;
        println!("report {}", path.display());
    }
    Ok(())
}

/// Where the tiler parks one zoom's chunks while it merges them.
///
/// Beside the archive, as `<out>.tilechunks`, matching where the feature spill is placed. Appended
/// to the whole file name rather than replacing an extension, so `world.mamaps` and `world.tmp`
/// cannot collide on one path.
fn scratch_path(out: &std::path::Path) -> PathBuf {
    let mut p = out.as_os_str().to_owned();
    p.push(".tilechunks");
    PathBuf::from(p)
}

/// Hash the inputs that decide what an archive contains.
///
/// The input's **length and modification time** rather than its bytes: digesting 1.3 GB to decide a
/// cache key would double the build's I/O for a number that only has to change when the data does.
/// A rebuild from an unchanged file therefore keeps its id, which is what makes a byte-identical
/// rebuild byte-identical.
fn derive_build_id(
    input: &std::path::Path,
    layers: schema::Layers,
    min_zoom: u8,
    max_zoom: u8,
    simplification: f64,
    features: u64,
) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    let mut eat = |bytes: &[u8]| {
        for &b in bytes {
            h ^= b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
    };
    eat(b"mamaps_build/1");
    eat(input.to_string_lossy().as_bytes());
    if let Ok(meta) = std::fs::metadata(input) {
        eat(&meta.len().to_le_bytes());
        if let Ok(time) = meta.modified() {
            if let Ok(since) = time.duration_since(std::time::UNIX_EPOCH) {
                eat(&since.as_secs().to_le_bytes());
            }
        }
    }
    eat(&[
        u8::from(layers.earth),
        u8::from(layers.water),
        u8::from(layers.buildings),
        u8::from(layers.roads),
        u8::from(layers.boundaries),
        u8::from(layers.landcover),
        u8::from(layers.landuse),
        min_zoom,
        max_zoom,
    ]);
    eat(&simplification.to_le_bytes());
    eat(&features.to_le_bytes());
    // The schema table's own version, so a remapped kind invalidates every cache even when the
    // input has not moved.
    eat(&(tilecodec::mamaps::dict::KINDS.len() as u64).to_le_bytes());
    h
}

/// The build report, as JSON so a script can diff two builds.
fn build_report(
    stats: &extract::Stats,
    per_zoom: &[tiler::ZoomStats],
    build_id: u64,
    bytes: usize,
) -> String {
    let mut out = String::from("{\n");
    out.push_str(&format!("  \"build_id\": \"{build_id:#018x}\",\n"));
    out.push_str(&format!("  \"file_bytes\": {bytes},\n"));
    out.push_str(&format!("  \"ways_classified\": {},\n", stats.ways_classified));
    out.push_str(&format!("  \"relations_classified\": {},\n", stats.relations_classified));
    out.push_str(&format!("  \"features\": {},\n", stats.features));
    out.push_str(&format!("  \"geometry_failed\": {},\n", stats.geometry_failed));
    out.push_str(&format!("  \"nodes_needed\": {},\n", stats.nodes_needed));
    let lines = per_zoom.iter().fold(coalesce::Stats::default(), |mut a, z| {
        a.add(z.lines);
        a
    });
    out.push_str(&format!(
        "  \"coalesced\": {{ \"line_features_before\": {}, \"line_features_after\": {}, \
         \"parts_before\": {}, \"parts_after\": {} }},\n",
        lines.features_before, lines.features_after, lines.parts_before, lines.parts_after,
    ));
    out.push_str("  \"zooms\": [\n");
    for (i, z) in per_zoom.iter().enumerate() {
        out.push_str(&format!(
            "    {{ \"zoom\": {}, \"tiles\": {}, \"features\": {}, \"points\": {}, \
             \"dropped\": {}, \"bytes\": {}, \"map_ms\": {}, \"merge_ms\": {}, \
             \"encode_ms\": {}, \"append_ms\": {} }}{}\n",
            z.zoom,
            z.tiles,
            z.features,
            z.points,
            z.dropped,
            z.bytes,
            z.map_ms,
            z.merge_ms,
            z.encode_ms,
            z.append_ms,
            if i + 1 == per_zoom.len() { "" } else { "," },
        ));
    }
    out.push_str("  ]\n}\n");
    out
}

fn usage() {
    eprintln!(
        "usage: mamaps_build --input IN.osm.pbf --out OUT.mamaps\n\
         \x20                   [--layers earth,water,buildings,roads,boundaries,landcover,landuse]\n\
         \x20                   [--min-zoom N] [--max-zoom N]\n\
         \x20                   [--coastline LAND.geojsonseq]\n\
         \x20                   [--simplification F] [--build-id N] [--report FILE]\n\
         \x20                   [--keep-store] [--reuse-store]\n\
         \n\
         --keep-store   leave the feature spill and its index behind\n\
         --reuse-store  tile from that spill instead of re-running stage A"
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The schema's own floor for buildings and the style's have to agree, or the archive either
    /// carries what nothing draws or is asked for what it does not carry.
    #[test]
    fn the_schema_floor_matches_the_styles() {
        // The style's floor lives in `library/map`'s flat style, which this crate does not link. So
        // this pins the number and names where the other copy is: a change here without a change
        // there shows up as a tile the renderer asks for and does not get.
        assert_eq!(
            schema::buildings::MIN_ZOOM,
            14,
            "buildings' floor is 14 in library/map/src/main/rust/style/basemap.flat.json too",
        );
    }

    /// The id has to change when the data would, and not otherwise. A rebuild from an unchanged file
    /// keeps its id, which is what makes a byte-identical rebuild byte-identical.
    #[test]
    fn a_build_id_follows_the_inputs_that_decide_the_output() {
        let path = std::path::Path::new("nonexistent.osm.pbf");
        let base = derive_build_id(path, schema::Layers::all(), 0, 14, 1.0, 100);
        assert_eq!(base, derive_build_id(path, schema::Layers::all(), 0, 14, 1.0, 100), "stable");
        for other in [
            derive_build_id(path, schema::Layers::all(), 1, 14, 1.0, 100),
            derive_build_id(path, schema::Layers::all(), 0, 15, 1.0, 100),
            derive_build_id(path, schema::Layers::all(), 0, 14, 2.0, 100),
            derive_build_id(path, schema::Layers::all(), 0, 14, 1.0, 101),
            derive_build_id(
                path,
                schema::Layers { water: true, ..schema::Layers::none() },
                0,
                14,
                1.0,
                100,
            ),
            derive_build_id(std::path::Path::new("other.osm.pbf"), schema::Layers::all(), 0, 14, 1.0, 100),
        ] {
            assert_ne!(base, other, "a changed input should change the id");
        }
    }

    #[test]
    fn the_report_is_valid_json_shaped_output() {
        let stats = extract::Stats { features: 3, ..extract::Stats::default() };
        let zooms = vec![
            tiler::ZoomStats { zoom: 0, tiles: 1, features: 3, points: 12, dropped: 0, bytes: 40, ..Default::default() },
            tiler::ZoomStats { zoom: 1, tiles: 4, features: 3, points: 20, dropped: 1, bytes: 90, ..Default::default() },
        ];
        let report = build_report(&stats, &zooms, 42, 1024);
        assert!(report.starts_with("{\n") && report.ends_with("}\n"));
        assert_eq!(report.matches("\"zoom\":").count(), 2);
        // No trailing comma on the last entry, which is the one thing hand-written JSON gets wrong.
        assert!(!report.contains("},\n  ]"));
    }
}
