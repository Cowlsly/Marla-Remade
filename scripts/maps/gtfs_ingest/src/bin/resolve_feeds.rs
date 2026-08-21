//! `resolve_feeds` — turn the Transitous registry plus transitland-atlas into a
//! download plan whose feeds can carry MOTIS stop ids.
//!
//! Usage:
//!   resolve_feeds --registry DIR --atlas DIR --out PLAN.tsv
//!                 [--region GLOB] [--max-feeds N] [--report]
//!
//! `--registry` is an extracted `public-transport/transitous` checkout (its
//! `feeds/*.json`); `--atlas` is an extracted `transitland/transitland-atlas`
//! (its `feeds/*.dmfr.json`). Both are **fetched by the shell**, not here: this
//! crate has no HTTP stack and no dependencies at all, which is what lets it build
//! and run offline.
//!
//! The plan is a TSV of `name<TAB>url<TAB>motis_prefix`, which
//! `build_world_transit.sh` downloads and then turns into the three-field manifest
//! `manifest.rs::parse_feed_spec` reads.
//!
//! `--region` and `--max-feeds` are not conveniences. Resolving every `feeds/*.json`
//! means hundreds of feeds and many GB of downloads, against today's 18.9 MB
//! `world.transit` built from `.url` fields alone. Stage it: California first
//! (known-good), then a few regions, then the world.

use std::path::{Path, PathBuf};
use std::process::ExitCode;

use gtfs_ingest::json;
use gtfs_ingest::registry::{self, Resolution};

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let mut registry_dir: Option<PathBuf> = None;
    let mut atlas_dir: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut region = "*".to_string();
    let mut max_feeds = 0usize;
    let mut report = false;

    let mut i = 0;
    while i < argv.len() {
        let value = || argv.get(i + 1).cloned();
        match argv[i].as_str() {
            "--registry" => {
                registry_dir = value().map(PathBuf::from);
                i += 2;
            }
            "--atlas" => {
                atlas_dir = value().map(PathBuf::from);
                i += 2;
            }
            "--out" => {
                out = value().map(PathBuf::from);
                i += 2;
            }
            "--region" => {
                match value() {
                    Some(v) => region = v,
                    None => return fail("--region needs a value"),
                }
                i += 2;
            }
            "--max-feeds" => {
                let Some(raw) = value() else {
                    return fail("--max-feeds needs a value");
                };
                let Ok(n) = raw.parse() else {
                    return fail(&format!("--max-feeds wants a number, got '{raw}'"));
                };
                max_feeds = n;
                i += 2;
            }
            "--report" => {
                report = true;
                i += 1;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => {
                eprintln!("resolve_feeds: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
        }
    }

    let (Some(registry_dir), Some(atlas_dir), Some(out)) = (registry_dir, atlas_dir, out) else {
        eprintln!("resolve_feeds: --registry, --atlas and --out are all required");
        usage();
        return ExitCode::from(2);
    };

    // --- index the atlas ---
    let atlas_files = match json_files(&atlas_dir.join("feeds"), "*") {
        Ok(f) => f,
        Err(e) => return fail(&e),
    };
    let mut atlas_docs = Vec::with_capacity(atlas_files.len());
    for path in &atlas_files {
        match read_json(path) {
            Ok(v) => atlas_docs.push(v),
            // One malformed atlas document must not sink the whole resolution: the
            // atlas is a third-party checkout of thousands of files.
            Err(e) => eprintln!("resolve_feeds: skipping {}: {e}", path.display()),
        }
    }
    let atlas = registry::index_atlas(&atlas_docs);
    eprintln!(
        "resolve_feeds: indexed {} atlas feed(s) from {} document(s)",
        atlas.len(),
        atlas_docs.len()
    );

    // --- resolve the matching region files ---
    let region_files = match json_files(&registry_dir.join("feeds"), &region) {
        Ok(f) => f,
        Err(e) => return fail(&e),
    };
    if region_files.is_empty() {
        return fail(&format!(
            "no registry file matches --region '{region}' under {}/feeds",
            registry_dir.display()
        ));
    }
    eprintln!(
        "resolve_feeds: {} registry file(s) match --region '{region}'",
        region_files.len()
    );

    let mut resolution = Resolution::default();
    for path in &region_files {
        let name = path
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        match read_json(path) {
            Ok(doc) => registry::resolve_region(&name, &doc, &atlas, &mut resolution),
            Err(e) => eprintln!("resolve_feeds: skipping {}: {e}", path.display()),
        }
    }

    let before = resolution.feeds.len();
    registry::dedup(&mut resolution.feeds);
    let deduped = before - resolution.feeds.len();
    // Sorted before the cap, so --max-feeds takes a stable prefix rather than
    // whichever feeds the filesystem happened to list first.
    resolution.feeds.sort_by(|a, b| a.name.cmp(&b.name));
    if max_feeds > 0 && resolution.feeds.len() > max_feeds {
        eprintln!(
            "resolve_feeds: capping {} feed(s) at --max-feeds {max_feeds}",
            resolution.feeds.len()
        );
        resolution.feeds.truncate(max_feeds);
    }

    eprintln!(
        "resolve_feeds: {} feed(s) from {} source(s); {} skipped, {} duplicate(s) dropped",
        resolution.feeds.len(),
        resolution.considered,
        resolution.skipped.len(),
        deduped
    );
    if report {
        for f in &resolution.feeds {
            eprintln!("  {:<28} via {:<50} {}", f.name, f.via, f.url);
        }
        for s in &resolution.skipped {
            eprintln!("  SKIP {:<24} {}", s.name, s.why);
        }
    }
    if resolution.feeds.is_empty() {
        eprintln!("resolve_feeds: nothing resolved -- refusing to write an empty plan");
        return ExitCode::FAILURE;
    }

    let mut text = String::new();
    for f in &resolution.feeds {
        text.push_str(&format!("{}\t{}\t{}\n", f.name, f.url, f.source));
    }
    if let Err(e) = std::fs::write(&out, text) {
        return fail(&format!("cannot write {}: {e}", out.display()));
    }
    eprintln!("resolve_feeds: wrote {}", out.display());
    ExitCode::SUCCESS
}

fn fail(msg: &str) -> ExitCode {
    eprintln!("resolve_feeds: {msg}");
    ExitCode::FAILURE
}

fn usage() {
    eprintln!("usage: resolve_feeds --registry DIR --atlas DIR --out PLAN.tsv");
    eprintln!("                     [--region GLOB] [--max-feeds N] [--report]");
}

fn read_json(path: &Path) -> Result<json::Json, String> {
    let text = std::fs::read_to_string(path).map_err(|e| e.to_string())?;
    json::parse(&text)
}

/// `.json` files in `dir` whose stem matches `pattern`, sorted so a run is
/// reproducible whatever order the filesystem lists them in.
fn json_files(dir: &Path, pattern: &str) -> Result<Vec<PathBuf>, String> {
    let entries = std::fs::read_dir(dir)
        .map_err(|e| format!("cannot read {}: {e}", dir.display()))?;
    let mut out = Vec::new();
    for e in entries {
        let path = e.map_err(|e| e.to_string())?.path();
        if !path.is_file() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        if !name.ends_with(".json") {
            continue;
        }
        // Match on the stem, so `--region us-ca` finds `us-ca.json` and
        // `--region 'us-*'` finds every US state. The atlas's `.dmfr.json` files
        // have a `.dmfr` stem suffix, which `*` covers.
        let stem = name.trim_end_matches(".json");
        if glob_match(pattern, stem) {
            out.push(path);
        }
    }
    out.sort();
    Ok(out)
}

/// Shell-style `*` and `?` matching, which is what `--region` promises.
///
/// Iterative with a backtrack point rather than recursive: a pattern of all stars
/// against a long name is a well-known way to make the naive recursion take
/// exponential time.
fn glob_match(pattern: &str, text: &str) -> bool {
    let p: Vec<char> = pattern.chars().collect();
    let t: Vec<char> = text.chars().collect();
    let (mut pi, mut ti) = (0usize, 0usize);
    let mut star: Option<(usize, usize)> = None;
    while ti < t.len() {
        if pi < p.len() && (p[pi] == '?' || p[pi] == t[ti]) {
            pi += 1;
            ti += 1;
        } else if pi < p.len() && p[pi] == '*' {
            star = Some((pi, ti));
            pi += 1;
        } else if let Some((sp, st)) = star {
            // Backtrack: let the star consume one more character.
            pi = sp + 1;
            ti = st + 1;
            star = Some((sp, st + 1));
        } else {
            return false;
        }
    }
    while pi < p.len() && p[pi] == '*' {
        pi += 1;
    }
    pi == p.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn glob_matches_the_forms_region_promises() {
        assert!(glob_match("*", "us-ca"));
        assert!(glob_match("us-ca", "us-ca"));
        assert!(glob_match("us-*", "us-ca"));
        assert!(glob_match("us-*", "us-ny"));
        assert!(!glob_match("us-*", "de-by"));
        assert!(glob_match("*-ca", "us-ca"));
        assert!(glob_match("us-??", "us-ca"));
        assert!(!glob_match("us-??", "us-cal"));
        assert!(glob_match("*ca*", "us-ca-extra"));
        assert!(!glob_match("us-ca", "us-cal"));
        // An atlas document's stem keeps its `.dmfr`.
        assert!(glob_match("*", "bayarea.dmfr"));
    }

    #[test]
    fn glob_handles_the_pathological_patterns_without_hanging() {
        // The case the naive recursion goes exponential on.
        let text = "a".repeat(40) + "b";
        assert!(!glob_match("*a*a*a*a*a*a*a*a*c", &text));
        assert!(glob_match("*a*a*a*a*a*a*a*a*b", &text));
        assert!(glob_match("**", "anything"));
        assert!(glob_match("*", ""));
        assert!(!glob_match("?", ""));
        assert!(glob_match("", ""));
        assert!(!glob_match("", "x"));
    }
}
