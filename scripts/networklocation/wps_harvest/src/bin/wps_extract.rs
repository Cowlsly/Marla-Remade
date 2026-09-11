//! `wps_extract V1.wpsdb --out seed-bssids.txt`
//!
//! Pull the BSSIDs out of a previous-generation `WPSDB1` store, to seed a crawl's frontier.
//!
//! **Identities only.** The v1 coordinates are not read and deliberately cannot be: v1
//! quantized to a 20 m grid and recorded no accuracy, so importing a position would mean
//! inventing one. Worse, `quality::reduce` keeps whichever observation claims the better
//! accuracy — and since Apple typically reports 20-100 m for WiFi, a v1 position labelled 20 m
//! would beat most real crawled measurements and pin the beacon to its old grid cell. Every
//! coordinate in the new store comes from a source that also said how accurate it was.
//!
//! This is nonetheless the best seed available: there is no open bulk WiFi dump, and the
//! alternatives are a local scan (one metro) or guessing addresses for hours.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::process::ExitCode;

use wps_harvest::keys;
use wps_harvest::v1;

const USAGE: &str = "\
Usage: wps_extract V1.wpsdb --out FILE [--stride N]

  --out     BSSIDs to write, one per line, for wps_crawl --seed
  --stride  keep every Nth BSSID (default 1 = all). The crawl reaches a beacon's neighbours
            anyway, so a thinned list still covers the same ground with a smaller frontier.";

struct Args {
    input: PathBuf,
    out: PathBuf,
    stride: usize,
}

fn parse_args(argv: &[String]) -> Result<Args, String> {
    let mut input = None;
    let mut out = None;
    let mut stride = 1usize;

    let mut i = 0;
    while i < argv.len() {
        let a = argv[i].as_str();
        let value = || argv.get(i + 1).cloned().ok_or_else(|| format!("{a} needs a value"));
        match a {
            "--out" => {
                out = Some(PathBuf::from(value()?));
                i += 2;
            }
            "--stride" => {
                stride = value()?.parse().map_err(|_| "--stride must be a number")?;
                i += 2;
            }
            "-h" | "--help" => return Err("help".to_string()),
            other if other.starts_with('-') => return Err(format!("unknown option: {other}")),
            other => {
                if input.replace(PathBuf::from(other)).is_some() {
                    return Err("more than one input given".to_string());
                }
                i += 1;
            }
        }
    }
    Ok(Args {
        input: input.ok_or("an input .wpsdb is required")?,
        out: out.ok_or("--out is required")?,
        stride: stride.max(1),
    })
}

fn format_mac(mac: u64) -> String {
    let b = mac.to_be_bytes();
    format!("{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}", b[2], b[3], b[4], b[5], b[6], b[7])
}

fn run(args: &Args) -> std::io::Result<()> {
    println!("Reading {}", args.input.display());
    let buf = std::fs::read(&args.input)?;
    println!("  {} on disk", wps_harvest::human_bytes(buf.len() as u64));

    let started = std::time::Instant::now();
    let store = v1::parse(&buf)?;
    println!(
        "  {} key(s), {}-bit universe, parsed in {:.1}s",
        store.keys.len(),
        store.universe_bits,
        started.elapsed().as_secs_f64()
    );

    if store.universe_bits != keys::WIFI_UNIVERSE_BITS {
        // v1 cell keys packed mcc|mnc|tac|cid with no radio type and a 28-bit cell id, so a 5G
        // NCI was already truncated when they were written. There is no way to recover which
        // tower a key meant, and a crawl seeded with them would query identities that never
        // existed.
        eprintln!(
            "error: this is a {}-bit (cell) store. v1 cell keys carry no radio type and a \
             truncated cell id, so they do not identify a real tower. Seed cells from \
             OpenCelliD instead.",
            store.universe_bits
        );
        return Ok(());
    }

    let mut w = BufWriter::with_capacity(1 << 20, File::create(&args.out)?);
    let (mut written, mut randomized) = (0u64, 0u64);
    for (i, &mac) in store.keys.iter().enumerate() {
        // v1 never filtered these. They are not stable identifiers, Apple will not know them,
        // and the store builder drops them — so querying one is a wasted request.
        if keys::is_randomized_mac(mac) {
            randomized += 1;
            continue;
        }
        if i % args.stride != 0 {
            continue;
        }
        writeln!(w, "{}", format_mac(mac))?;
        written += 1;
    }
    w.flush()?;

    println!(
        "Wrote {} ({written} BSSID(s); {randomized} randomized or locally-administered skipped)",
        args.out.display()
    );
    Ok(())
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
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}
