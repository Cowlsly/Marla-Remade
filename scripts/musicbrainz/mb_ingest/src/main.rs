//! CLI wrapper. All the logic is in the library so the server can call the same
//! ingest code in-process instead of shelling out to this binary.
//!
//! ```text
//! mb_ingest build <mbdump.tar.bz2 | dir> <out.pack> [--tier-a] [--official-only]
//!                                                   [--no-isrcs] [--no-recording-search]
//!                                                   [--no-recording-mbids] [--raw-strings]
//!                                                   [--work-dir <dir>] [-q]
//! mb_ingest fixture <dir>          write the synthetic test dump
//! mb_ingest inspect <out.pack>     header, section sizes and a smoke query
//! mb_ingest query <out.pack> <text> run all three searches and dump one result each
//! ```

use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use mb_ingest::build::{build, BuildOptions, TABLES};
use mb_ingest::copy::Input;
use mb_ingest::pack::format_mbid;
use mb_ingest::reader::MbPack;

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("mb_ingest: {e}");
            ExitCode::FAILURE
        }
    }
}

fn usage() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidInput,
        "usage:\n  \
         mb_ingest build <mbdump.tar.bz2|dir> <out.pack> [--tier-a] [--official-only] \
         [--no-isrcs] [--no-recording-search] [--no-recording-mbids] [--raw-strings] \
         [--work-dir <dir>] [-q]\n  \
         mb_ingest fixture <dir>\n  \
         mb_ingest inspect <pack>\n  \
         mb_ingest query <pack> <text>",
    )
}

fn run() -> io::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("build") => cmd_build(&args[1..]),
        Some("fixture") => {
            let dir = args.get(1).ok_or_else(usage)?;
            mb_ingest::fixture::write_fixture(Path::new(dir))?;
            println!("wrote synthetic mbdump to {dir}");
            Ok(())
        }
        Some("inspect") => cmd_inspect(args.get(1).ok_or_else(usage)?),
        Some("query") => cmd_query(
            args.get(1).ok_or_else(usage)?,
            args.get(2).ok_or_else(usage)?,
        ),
        _ => Err(usage()),
    }
}

fn cmd_build(args: &[String]) -> io::Result<()> {
    let mut positional: Vec<&String> = Vec::new();
    let mut opts = BuildOptions { verbose: true, ..BuildOptions::default() };
    let mut args_iter = args.iter();
    while let Some(a) = args_iter.next() {
        match a.as_str() {
            "--tier-a" => opts.include_track_mbids = true,
            "--official-only" => opts.official_only = true,
            "--no-isrcs" => opts.include_isrcs = false,
            "--no-recording-search" => opts.include_recording_search = false,
            "--no-recording-mbids" => opts.include_recording_mbids = false,
            "--raw-strings" => opts.compress_strings = false,
            "--work-dir" => {
                let dir = args_iter.next().ok_or_else(usage)?;
                opts.work_dir = Some(PathBuf::from(dir));
            }
            "-q" | "--quiet" => opts.verbose = false,
            other if other.starts_with('-') => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("unknown flag {other}"),
                ))
            }
            _ => positional.push(a),
        }
    }
    if positional.len() != 2 {
        return Err(usage());
    }
    let input = Input::detect(Path::new(positional[0]));
    if matches!(input, Input::Archive(_)) {
        eprintln!(
            "note: reading tables straight out of an archive costs one bz2 decompression \
             pass per table ({} of them), because bz2 is not seekable. That is fine for a \
             fixture; for the 7 GB full export the server's single-traversal spill is the \
             production path.",
            TABLES.len()
        );
    }
    // Write to a .tmp and rename, so a crashed build never leaves a half-written
    // pack where the server will mmap it (the sb_build.sh:90-92 convention).
    let final_path = PathBuf::from(positional[1]);
    let tmp_path = final_path.with_extension("pack.tmp");
    let started = std::time::Instant::now();
    let stats = {
        let mut file = std::fs::File::create(&tmp_path)?;
        let stats = build(&input, &mut file, &opts, &mut io::stderr())?;
        file.flush()?;
        stats
    };
    std::fs::rename(&tmp_path, &final_path)?;
    print!("{}", stats.report());
    println!(
        "wrote {} ({:.2} GB) in {:.1}s",
        final_path.display(),
        stats.total_bytes as f64 / 1e9,
        started.elapsed().as_secs_f64()
    );
    Ok(())
}

fn cmd_query(path: &str, text: &str) -> io::Result<()> {
    let bytes = std::fs::read(path)?;
    let pack = MbPack::open(&bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let t0 = std::time::Instant::now();
    let artists = pack.search_artists(text, 5);
    let artist_ms = t0.elapsed().as_secs_f64() * 1000.0;
    println!("search_artists({text:?}) -> {} hits in {artist_ms:.1} ms", artists.len());
    for h in &artists {
        let a = pack.artist(h.idx).expect("artist row");
        println!(
            "  [{}] {} ({}) {} {} — {} release groups",
            h.score,
            a.name,
            a.kind,
            a.country,
            a.begin_date.to_ws2(),
            pack.artist_release_groups(h.idx).len()
        );
    }
    let t0 = std::time::Instant::now();
    let rgs = pack.search_release_groups(text, 5);
    println!(
        "search_release_groups({text:?}) -> {} hits in {:.1} ms",
        rgs.len(),
        t0.elapsed().as_secs_f64() * 1000.0
    );
    for h in &rgs {
        let g = pack.release_group(h.idx).expect("rg row");
        println!(
            "  [{}] {} — {} ({} {}) {} releases",
            h.score,
            g.title,
            g.credit,
            g.primary_type,
            g.first_release_date.to_ws2(),
            pack.release_group_releases(h.idx).len()
        );
    }
    let t0 = std::time::Instant::now();
    let recs = pack.search_recordings(text, 5);
    println!(
        "search_recordings({text:?}) -> {} hits in {:.1} ms",
        recs.len(),
        t0.elapsed().as_secs_f64() * 1000.0
    );
    for h in &recs {
        let r = pack.recording(h.idx).expect("recording row");
        let isrcs: Vec<String> = pack
            .recording_isrcs(h.idx)
            .iter()
            .map(|i| String::from_utf8_lossy(i).into_owned())
            .collect();
        println!(
            "  [{}] {} — {} ({}s) {} {}",
            h.score,
            r.title,
            r.credit,
            r.length_secs,
            pack.recording_mbid(h.idx).map(|m| format_mbid(&m)).unwrap_or_default(),
            isrcs.join(",")
        );
    }
    // Walk the top release-group hit end to end: the exact path
    // GET /api/mb/release-group/:mbid then /api/mb/release/:mbid takes.
    if let Some(h) = rgs.first() {
        let t0 = std::time::Instant::now();
        let editions = pack.release_group_releases(h.idx);
        println!("\ntop release group has {} editions:", editions.len());
        for &r in editions.iter().take(3) {
            let rel = pack.release(r).expect("release");
            println!(
                "  {} [{}] {} {} — {} tracks across {} media",
                rel.title,
                rel.status,
                rel.date.to_ws2(),
                rel.country,
                pack.release_track_count(r),
                pack.release_media(r).len()
            );
        }
        if let Some(&r) = editions.first() {
            let list = pack.release_tracklist(r);
            for (medium, tracks) in list.iter().take(1) {
                println!("  medium {} {}:", medium.position, medium.format);
                for t in tracks.iter().take(3) {
                    println!("    {:>3}. {} — {} ({}s)", t.position, t.title, t.credit, t.length_secs);
                }
            }
        }
        println!("  walked in {:.1} ms", t0.elapsed().as_secs_f64() * 1000.0);
    }
    Ok(())
}

fn cmd_inspect(path: &str) -> io::Result<()> {
    let bytes = std::fs::read(path)?;
    let pack = MbPack::open(&bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let c = pack.counts();
    println!("dump date            {}", pack.dump_date());
    println!("flags                {:#08b}", pack.flags());
    println!("  track MBIDs        {}", pack.has_track_mbids());
    println!("  recording MBIDs    {}", pack.has_recording_mbids());
    println!("  ISRCs              {}", pack.has_isrcs());
    println!("  recording search   {}", pack.has_recording_search());
    println!("  official only      {}", pack.official_only());
    println!("  strings compressed {}", pack.strings_compressed());
    println!(
        "counts               artists {} credits {} rgs {} releases {} media {} tracks {} \
         recordings {} isrcs {} terms {}",
        c.artists, c.credits, c.release_groups, c.releases, c.media, c.tracks, c.recordings,
        c.isrcs, c.search_terms
    );
    for (name, len) in pack.section_sizes() {
        if len > 0 {
            println!("  {name:<20} {:>12.3} MB", len as f64 / 1e6);
        }
    }
    // Smoke query: walk the first release end to end, which touches every section
    // an /api/mb/release/:mbid request would.
    if c.releases > 0 {
        let mbid = pack.release_mbid(0).expect("release 0 has an MBID");
        let rel = pack.release(0).expect("release 0");
        println!("\nfirst release {} \"{}\" [{}]", format_mbid(&mbid), rel.title, rel.status);
        for (medium, tracks) in pack.release_tracklist(0) {
            println!("  medium {} {} ({} tracks)", medium.position, medium.format, medium.track_count);
            for t in tracks {
                let isrcs: Vec<String> = pack
                    .recording_isrcs(t.recording_idx)
                    .iter()
                    .map(|i| String::from_utf8_lossy(i).into_owned())
                    .collect();
                println!(
                    "    {:>3}. {} — {} ({}s) rec {} {}",
                    t.position,
                    t.title,
                    t.credit,
                    t.length_secs,
                    pack.recording_mbid(t.recording_idx).map(|m| format_mbid(&m)).unwrap_or_default(),
                    isrcs.join(",")
                );
            }
        }
    }
    Ok(())
}
