//! `wikidesc_build` — build the place-description archive.
//!
//! ```text
//! wikidesc_build <out.wikidesc> --pbf <planet.osm.pbf> --abstracts <enwiki-latest-abstract.xml>
//! ```
//!
//! Two sequential passes over two large files, holding neither.
//!
//! 1. Scan the `.pbf` for every element carrying an English `wikipedia` tag, recording the article
//!    title it wants against the element's tagged id. Resident set is one string per notable
//!    element, of order 10^6 — not the 80 GB the planet occupies on disk.
//! 2. Stream the abstracts dump once, and for every article somebody asked for, clean the lead and
//!    record it.
//!
//! The dump is not indexed and the places are not re-walked: either would mean holding a whole
//! input in memory. See `join` for why the pass goes in this direction.

use std::fs::File;
use std::io::{BufReader, BufWriter};
use std::path::PathBuf;
use std::process::ExitCode;

use osm_ingest::osm::{self, Element};
use osm_ingest::pbf;
use wikidesc::{ids, Abstracts, Wanted};

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("wikidesc_build: {e}");
            ExitCode::FAILURE
        }
    }
}

struct Args {
    out: PathBuf,
    pbf: PathBuf,
    abstracts: PathBuf,
}

fn parse_args() -> Result<Args, String> {
    let (mut out, mut pbf, mut abstracts) = (None, None, None);
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--pbf" => pbf = args.next().map(PathBuf::from),
            "--abstracts" => abstracts = args.next().map(PathBuf::from),
            other if other.starts_with("--") => return Err(format!("unknown flag {other}")),
            other if out.is_none() => out = Some(PathBuf::from(other)),
            other => return Err(format!("unexpected argument {other}")),
        }
    }
    Ok(Args {
        out: out.ok_or("usage: wikidesc_build <out.wikidesc> --pbf <x.osm.pbf> --abstracts <enwiki-latest-abstract.xml>")?,
        pbf: pbf.ok_or("missing --pbf")?,
        abstracts: abstracts.ok_or("missing --abstracts")?,
    })
}

fn run() -> Result<(), String> {
    let args = parse_args()?;

    // Pass 1. Nodes, ways and relations alike: a landmark is a node in one place, a building
    // outline in another and a multipolygon in a third, and which it is says nothing about
    // whether it has an article.
    let blobs = pbf::scan_blobs(&args.pbf).map_err(|e| e.to_string())?;
    let want = pbf::KIND_NODES | pbf::KIND_WAYS | pbf::KIND_RELATIONS;
    let (chunks, _kinds) = pbf::run_pass(
        &args.pbf,
        &blobs,
        None,
        want,
        "Pass 1: elements with a wikipedia tag",
        Vec::<(u64, String)>::new,
        |found, block| {
            let mut kinds = 0u8;
            osm::visit_block(block, want, &mut kinds, &mut |element| {
                // The tag IS the notability filter. Not a list of kinds: a mapper adds
                // `wikipedia` when an article exists, which is the question being asked, and
                // the list would never be finished.
                let (id, tags) = match element {
                    Element::Node(n) => (ids::node(n.id), n.tags),
                    Element::Way(w) => (ids::way(w.id), w.tags),
                    Element::Relation(r) => (ids::relation(r.id), r.tags),
                };
                if let Some(tag) = tags.get_str("wikipedia") {
                    if id != ids::ID_NONE {
                        found.push((id, tag.to_string()));
                    }
                }
                Ok(())
            })?;
            Ok(kinds)
        },
    )
    .map_err(|e| e.to_string())?;

    let mut wanted = Wanted::new();
    for chunk in chunks {
        for (id, tag) in chunk {
            wanted.add(id, &tag);
        }
    }
    println!(
        "wikidesc_build: {} element(s) want {} distinct English article(s)",
        wanted.places(),
        wanted.titles(),
    );
    if wanted.is_empty() {
        return Err("no element carries an English wikipedia tag - wrong extract?".to_string());
    }

    // Pass 2.
    let dump = File::open(&args.abstracts)
        .map_err(|e| format!("{}: {e}", args.abstracts.display()))?;
    // A big reader because this is one long sequential scan of several gigabytes.
    let dump = BufReader::with_capacity(1 << 22, dump);
    let (builder, stats) = wikidesc::join(&wanted, Abstracts::new(dump));
    println!(
        "wikidesc_build: matched {} article(s), described {} place(s), {} empty, {} missing",
        stats.matched, stats.described, stats.empty, stats.missing,
    );
    println!(
        "wikidesc_build: {} entry/entries, {} sharing text, {:.1} MB of text",
        builder.len(),
        builder.shared(),
        builder.blob_len() as f64 / 1_048_576.0,
    );

    let file = File::create(&args.out).map_err(|e| format!("{}: {e}", args.out.display()))?;
    let mut out = BufWriter::with_capacity(1 << 20, file);
    builder.write(&mut out).map_err(|e| e.to_string())?;
    drop(out);
    let bytes = std::fs::metadata(&args.out).map(|m| m.len()).unwrap_or(0);
    println!(
        "wikidesc_build: wrote {} ({:.1} MB)",
        args.out.display(),
        bytes as f64 / 1_048_576.0,
    );
    Ok(())
}
