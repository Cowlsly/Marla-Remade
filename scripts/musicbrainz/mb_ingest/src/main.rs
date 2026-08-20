//! CLI wrapper. All the logic is in the library so the server can call the same
//! ingest code in-process instead of shelling out to this binary.
//!
//! ```text
//! mb_ingest build <mbdump.tar.bz2 | dir> <out.pack> [--tier-a] [--official-only]
//!                                                   [--no-isrcs] [--no-recording-search]
//!                                                   [--no-recording-mbids] [-q]
//! mb_ingest fixture <dir>          write the synthetic test dump
//! mb_ingest inspect <out.pack>     header, section sizes and a smoke query
//! ```

use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use mb_ingest::build::{build, BuildOptions};
use mb_ingest::copy::Input;
use mb_ingest::fixture::write_fixture;
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
         [--no-isrcs] [--no-recording-search] [--no-recording-mbids] [-q]\n  \
         mb_ingest fixture <dir>\n  \
         mb_ingest inspect <pack>",
    )
}

fn run() -> io::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("build") => cmd_build(&args[1..]),
        Some("fixture") => {
            let dir = args.get(1).ok_or_else(usage)?;
            write_fixture(Path::new(dir))?;
            println!("wrote synthetic mbdump to {dir}");
            Ok(())
        }
        Some("inspect") => cmd_inspect(args.get(1).ok_or_else(usage)?),
        _ => Err(usage()),
    }
}

fn cmd_build(args: &[String]) -> io::Result<()> {
    let mut positional: Vec<&String> = Vec::new();
    let mut opts = BuildOptions { verbose: true, ..BuildOptions::default() };
    for a in args {
        match a.as_str() {
            "--tier-a" => opts.include_track_mbids = true,
            "--official-only" => opts.official_only = true,
            "--no-isrcs" => opts.include_isrcs = false,
            "--no-recording-search" => opts.include_recording_search = false,
            "--no-recording-mbids" => opts.include_recording_mbids = false,
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
