//! Feed-spec parsing shared by the crate's binaries.
//!
//! A spec names one GTFS feed and, optionally, the Transitous id namespace its
//! stops live in. `gtfs_ingest` bakes that namespace into the `.transit` pack and
//! `transit_stops` writes it into the basemap layer, so both read it the same way.

use std::path::{Path, PathBuf};

/// One feed: `(feed_name, gtfs_dir, motis_prefix)`. The prefix is empty when the
/// caller does not know it.
pub type FeedSpec = (String, PathBuf, String);

/// Parse `feed_name=dir`, `feed_name=dir=motis_prefix`, or a bare `dir` (name then
/// defaults to the dir's base name, with no prefix).
///
/// The third field is the feed's Transitous id namespace, which composes into a
/// MOTIS stop id (`<prefix>_<gtfs stop_id>`). Two-field specs stay valid, so
/// `build_world_transit.sh` is unaffected and its packs simply carry no ids.
/// Fields split on `=`, so neither the directory nor the prefix may contain one.
pub fn parse_feed_spec(s: &str) -> FeedSpec {
    let mut parts = s.splitn(3, '=');
    match (parts.next(), parts.next()) {
        (Some(name), Some(dir)) => (
            name.to_string(),
            PathBuf::from(dir),
            parts.next().unwrap_or("").trim().to_string(),
        ),
        _ => {
            let dir = PathBuf::from(s);
            let name = dir
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| s.to_string());
            (name, dir, String::new())
        }
    }
}

/// Read a manifest of feed-spec lines (`#` comments / blanks ignored).
pub fn read_manifest(path: &Path) -> Result<Vec<FeedSpec>, String> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("cannot read manifest {}: {e}", path.display()))?;
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        out.push(parse_feed_spec(line));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_two_field_spec_carries_no_motis_prefix() {
        // build_world_transit.sh emits these; its packs must keep building.
        let (name, dir, prefix) = parse_feed_spec("sfmuni=/tmp/gtfs/sfmuni");
        assert_eq!(name, "sfmuni");
        assert_eq!(dir, PathBuf::from("/tmp/gtfs/sfmuni"));
        assert_eq!(prefix, "");
    }

    #[test]
    fn a_three_field_spec_carries_the_motis_prefix() {
        let (name, dir, prefix) =
            parse_feed_spec("sf_bayarea=/tmp/gtfs/sf_bayarea=us-ca-SF-bayarea");
        assert_eq!(name, "sf_bayarea");
        assert_eq!(dir, PathBuf::from("/tmp/gtfs/sf_bayarea"));
        // The unmangled Transitous source name, which Get-SafeName destroys in
        // the feed name beside it.
        assert_eq!(prefix, "us-ca-SF-bayarea");
    }

    #[test]
    fn a_bare_dir_names_itself() {
        let (name, dir, prefix) = parse_feed_spec("/tmp/gtfs/sfmuni");
        assert_eq!(name, "sfmuni");
        assert_eq!(dir, PathBuf::from("/tmp/gtfs/sfmuni"));
        assert_eq!(prefix, "");
    }

    #[test]
    fn a_windows_dir_survives_the_split() {
        // Drive letters use `:`, not `=`, so they are not split points.
        let (name, dir, prefix) = parse_feed_spec(r"sf=C:\work\gtfs\sf=us-ca-SFMTA");
        assert_eq!(name, "sf");
        assert_eq!(dir, PathBuf::from(r"C:\work\gtfs\sf"));
        assert_eq!(prefix, "us-ca-SFMTA");
    }
}
