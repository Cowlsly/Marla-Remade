//! `wps_seed --kind wifi|cell --source NAME --out SHARD [INPUT|-]`
//!
//! Turn an open bulk dump into a record shard. Neither sorting nor filtering happens here —
//! `wps_build` owns both, because it is the only stage that sees every source at once and can
//! therefore tell a beacon two datasets disagree about from one that simply moves.

use std::fs::File;
use std::io::BufWriter;
use std::path::PathBuf;
use std::process::ExitCode;

use wps_harvest::record::{RecordWriter, Source};
use wps_harvest::seed::{self, Kind};

const USAGE: &str = "\
Usage: wps_seed --kind wifi|cell --source beacondb|opencellid --out SHARD [INPUT|-]

  --kind    what the dump holds
  --source  which dataset it is, recorded per record so a build can attribute a disagreement
  --out     record shard to write
  INPUT     CSV path, or - for stdin (pipe gunzip -c for a compressed dump). Default: -";

struct Args {
    kind: Kind,
    source: Source,
    out: PathBuf,
    input: String,
}

fn parse_args(argv: &[String]) -> Result<Args, String> {
    let (mut kind, mut source, mut out, mut input) = (None, None, None, None);
    let mut i = 0;
    while i < argv.len() {
        let a = argv[i].as_str();
        match a {
            "--kind" | "--source" | "--out" => {
                let v = argv.get(i + 1).ok_or_else(|| format!("{a} needs a value"))?;
                match a {
                    "--kind" => {
                        kind = Some(match v.as_str() {
                            "wifi" => Kind::Wifi,
                            "cell" => Kind::Cell,
                            other => return Err(format!("unknown --kind '{other}'")),
                        })
                    }
                    "--source" => {
                        source = Some(match v.as_str() {
                            "beacondb" => Source::Beacondb,
                            "opencellid" => Source::OpenCellId,
                            other => return Err(format!("unknown --source '{other}'")),
                        })
                    }
                    _ => out = Some(PathBuf::from(v)),
                }
                i += 2;
            }
            "-h" | "--help" => return Err("help".to_string()),
            other if other.starts_with("--") => return Err(format!("unknown option: {other}")),
            other => {
                if input.replace(other.to_string()).is_some() {
                    return Err("more than one input given".to_string());
                }
                i += 1;
            }
        }
    }
    Ok(Args {
        kind: kind.ok_or("--kind is required")?,
        source: source.ok_or("--source is required")?,
        out: out.ok_or("--out is required")?,
        input: input.unwrap_or_else(|| "-".to_string()),
    })
}

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match parse_args(&argv) {
        Ok(a) => a,
        Err(e) => {
            if e != "help" {
                eprintln!("error: {e}");
            }
            eprintln!("{USAGE}");
            return if e == "help" { ExitCode::SUCCESS } else { ExitCode::FAILURE };
        }
    };

    // A cell dump named as a WiFi one (or the reverse) would resolve a plausible-looking
    // header and produce a shard of nonsense keys, so say what is being read.
    println!(
        "Reading {} dump from {} as {:?}",
        match args.kind {
            Kind::Wifi => "wifi",
            Kind::Cell => "cell",
        },
        args.input,
        args.source
    );

    let input = match wps_harvest::open_input(&args.input) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("error: cannot read {}: {e}", args.input);
            return ExitCode::FAILURE;
        }
    };
    let file = match File::create(&args.out) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("error: cannot write {}: {e}", args.out.display());
            return ExitCode::FAILURE;
        }
    };

    let mut writer = RecordWriter::new(BufWriter::with_capacity(1 << 20, file));
    let started = std::time::Instant::now();
    let result = seed::read_dump(input, args.kind, args.source, |r| writer.push(&r));

    match result {
        Ok((rows, emitted, skipped)) => {
            if let Err(e) = writer.finish() {
                eprintln!("error: writing {}: {e}", args.out.display());
                return ExitCode::FAILURE;
            }
            let size = std::fs::metadata(&args.out).map(|m| m.len()).unwrap_or(0);
            println!(
                "{emitted} record(s) from {rows} row(s), {skipped} skipped, in {:.1}s",
                started.elapsed().as_secs_f64()
            );
            println!("Wrote {} ({})", args.out.display(), wps_harvest::human_bytes(size));
            if emitted == 0 {
                eprintln!("WARNING: 0 records. Check --kind matches the dump.");
            }
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
