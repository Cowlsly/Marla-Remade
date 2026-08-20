//! A small synthetic mbdump, in the exact shape of the real one.
//!
//! Column counts and orders match `admin/sql/CreateTables.sql` for schema
//! sequence 31 exactly, so the ingest passes exercised here are the same code
//! paths a 7 GB run takes. The fixture deliberately includes the awkward cases:
//! NULL columns (`\N`), a title containing an escaped tab and newline, accented
//! text (search folding), a track whose title differs from its recording's, a
//! track whose credit differs, a pregap track at position 0, a track whose length
//! differs from its recording's, a multi-medium release, recordings reused across
//! releases (so `d_recording` is not always 1), a standalone recording with no
//! track, a release with no status, a release group with a secondary type, and a
//! malformed ISRC that must be rejected rather than stored.
//!
//! There is deliberately **no `release_group_meta`**: that table is not in the
//! core dump, so first-release dates are derived from release dates. The fixture
//! therefore also exercises that derivation, including a release group whose only
//! release has no date at all.

use std::fs;
use std::io::{self, Write};
use std::path::Path;

fn table(dir: &Path, name: &str, rows: &[&[&str]]) -> io::Result<()> {
    let mut f = fs::File::create(dir.join(name))?;
    for row in rows {
        f.write_all(row.join("\t").as_bytes())?;
        f.write_all(b"\n")?;
    }
    Ok(())
}

/// Artist / release-group / release / recording MBIDs the round-trip test asserts
/// against. Chosen so the MBID sort order is the obvious one.
pub const ARTIST_PINK_FLOYD: &str = "11111111-1111-1111-1111-111111111111";
pub const ARTIST_BJORK: &str = "22222222-2222-2222-2222-222222222222";
pub const ARTIST_VARIOUS: &str = "33333333-3333-3333-3333-333333333333";
pub const RG_DARK_SIDE: &str = "a1000000-0000-0000-0000-000000000001";
pub const RG_HOMOGENIC: &str = "a2000000-0000-0000-0000-000000000002";
pub const RG_ECHOES: &str = "a3000000-0000-0000-0000-000000000003";
pub const RG_WEIRD: &str = "a4000000-0000-0000-0000-000000000004";
pub const REL_DARK_SIDE: &str = "b1000000-0000-0000-0000-000000000001";
pub const REL_DARK_SIDE_BOOT: &str = "b2000000-0000-0000-0000-000000000002";
pub const REL_HOMOGENIC: &str = "b3000000-0000-0000-0000-000000000003";
pub const REL_ECHOES: &str = "b4000000-0000-0000-0000-000000000004";
pub const REL_WEIRD: &str = "b5000000-0000-0000-0000-000000000005";
pub const REC_SPEAK_TO_ME: &str = "c1000000-0000-0000-0000-000000000001";
pub const REC_BREATHE: &str = "c2000000-0000-0000-0000-000000000002";
pub const REC_TIME: &str = "c3000000-0000-0000-0000-000000000003";
pub const REC_JOGA: &str = "c4000000-0000-0000-0000-000000000004";
pub const REC_BACHELORETTE: &str = "c5000000-0000-0000-0000-000000000005";
pub const REC_WISH: &str = "c6000000-0000-0000-0000-000000000006";
pub const REC_STANDALONE: &str = "c7000000-0000-0000-0000-000000000007";

/// The title that carries escaped control characters. The dump spells it
/// `Untitled\tWeird\nTitle`; the reader must hand back the real characters.
pub const WEIRD_TITLE: &str = "Untitled\tWeird\nTitle";

const N: &str = "\\N";
const T: &str = "t";
const F: &str = "f";
const TS: &str = "2026-08-19 00:00:00+00";

pub fn write_fixture(dir: &Path) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    fs::write(dir.join("TIMESTAMP"), "2026-08-19 00:25:41.835127+00\n")?;
    fs::write(dir.join("SCHEMA_SEQUENCE"), "31\n")?;

    // area: id, gid, name, type, edits_pending, last_updated, begin y/m/d,
    //       end y/m/d, ended, comment
    table(
        dir,
        "area",
        &[
            &["1", "e0000000-0000-0000-0000-000000000001", "United Kingdom", "1", "0", TS, N, N, N, N, N, N, F, ""],
            &["2", "e0000000-0000-0000-0000-000000000002", "United States", "1", "0", TS, N, N, N, N, N, N, F, ""],
            &["3", "e0000000-0000-0000-0000-000000000003", "Berlin", "3", "0", TS, N, N, N, N, N, N, F, ""],
        ],
    )?;
    // iso_3166_1: area, code
    table(dir, "iso_3166_1", &[&["1", "GB"], &["2", "US"]])?;

    // <enum>: id, name, parent, child_order, description, gid
    let enum_row = |id: &'static str, name: &'static str| -> Vec<&'static str> {
        vec![id, name, N, "0", N, "f0000000-0000-0000-0000-000000000000"]
    };
    let artist_types = [enum_row("1", "Person"), enum_row("2", "Group")];
    table(dir, "artist_type", &artist_types.iter().map(|r| r.as_slice()).collect::<Vec<_>>())?;
    let rg_primary = [enum_row("1", "Album"), enum_row("2", "Single")];
    table(
        dir,
        "release_group_primary_type",
        &rg_primary.iter().map(|r| r.as_slice()).collect::<Vec<_>>(),
    )?;
    let rg_secondary = [enum_row("1", "Compilation"), enum_row("3", "Live")];
    table(
        dir,
        "release_group_secondary_type",
        &rg_secondary.iter().map(|r| r.as_slice()).collect::<Vec<_>>(),
    )?;
    let statuses = [enum_row("1", "Official"), enum_row("3", "Bootleg")];
    table(dir, "release_status", &statuses.iter().map(|r| r.as_slice()).collect::<Vec<_>>())?;
    // medium_format: id, name, parent, child_order, year, has_discids, description, gid
    table(
        dir,
        "medium_format",
        &[
            &["1", "CD", N, "0", "1982", T, N, "f1000000-0000-0000-0000-000000000001"],
            &["7", "12\" Vinyl", N, "0", N, F, N, "f1000000-0000-0000-0000-000000000007"],
        ],
    )?;

    // artist: id, gid, name, sort_name, begin y/m/d, end y/m/d, type, area,
    //         gender, comment, edits_pending, last_updated, ended,
    //         begin_area, end_area
    table(
        dir,
        "artist",
        &[
            &["1", ARTIST_PINK_FLOYD, "Pink Floyd", "Pink Floyd", "1965", "1", "1", N, N, N, "2", "1", N, "British rock band", "0", TS, F, N, N],
            &["2", ARTIST_BJORK, "Björk", "Björk", "1965", "11", "21", N, N, N, "1", N, "2", "", "0", TS, F, N, N],
            &["3", ARTIST_VARIOUS, "Various Artists", "Various Artists", N, N, N, N, N, N, N, N, N, "", "0", TS, F, N, N],
        ],
    )?;

    // artist_credit: id, name, artist_count, ref_count, created, edits_pending, gid
    table(
        dir,
        "artist_credit",
        &[
            &["1", "Pink Floyd", "1", "10", TS, "0", "a0000000-0000-0000-0000-000000000001"],
            &["2", "Björk", "1", "5", TS, "0", "a0000000-0000-0000-0000-000000000002"],
            &["3", "Pink Floyd feat. Björk", "2", "1", TS, "0", "a0000000-0000-0000-0000-000000000003"],
            &["4", "Various Artists", "1", "3", TS, "0", "a0000000-0000-0000-0000-000000000004"],
        ],
    )?;
    // artist_credit_name: artist_credit, position, artist, name, join_phrase
    table(
        dir,
        "artist_credit_name",
        &[
            &["1", "0", "1", "Pink Floyd", ""],
            &["2", "0", "2", "Björk", ""],
            &["3", "0", "1", "Pink Floyd", " feat. "],
            &["3", "1", "2", "Björk", ""],
            &["4", "0", "3", "Various Artists", ""],
        ],
    )?;

    // release_group: id, gid, name, artist_credit, type, comment,
    //                edits_pending, last_updated
    table(
        dir,
        "release_group",
        &[
            &["1", RG_DARK_SIDE, "The Dark Side of the Moon", "1", "1", "", "0", TS],
            &["2", RG_HOMOGENIC, "Homogenic", "2", "1", "", "0", TS],
            &["3", RG_ECHOES, "Echoes: The Best of Pink Floyd", "1", "1", "", "0", TS],
            &["4", RG_WEIRD, "Untitled\\tWeird\\nTitle", "3", N, "", "0", TS],
        ],
    )?;
    // release_group_secondary_type_join: release_group, secondary_type, created
    table(dir, "release_group_secondary_type_join", &[&["3", "1", TS], &["3", "3", TS]])?;

    // release: id, gid, name, artist_credit, release_group, status, packaging,
    //          language, script, barcode, comment, edits_pending, quality,
    //          last_updated
    table(
        dir,
        "release",
        &[
            &["1", REL_DARK_SIDE, "The Dark Side of the Moon", "1", "1", "1", N, "120", "28", "5099902988603", "", "0", "2", TS],
            &["2", REL_DARK_SIDE_BOOT, "The Dark Side of the Moon", "1", "1", "3", N, N, N, N, "vinyl bootleg", "0", "-1", TS],
            &["3", REL_HOMOGENIC, "Homogenic", "2", "2", "1", N, N, N, N, "", "0", "1", TS],
            &["4", REL_ECHOES, "Echoes: The Best of Pink Floyd", "1", "3", "1", N, N, N, N, "", "0", "1", TS],
            &["5", REL_WEIRD, "Untitled\\tWeird\\nTitle", "3", "4", N, N, N, N, N, "", "0", "-1", TS],
        ],
    )?;
    // release_country: release, country(area id), date y/m/d
    // Release 1 carries a precise date; release 2 (same release group) carries a
    // BARE YEAR in the same year. The group's derived first-release-date must be
    // the precise one -- comparing the packed values raw picks the bare year, which
    // is exactly the bug that lost 1973-03-24 for The Dark Side of the Moon.
    table(dir, "release_country", &[&["1", "1", "1973", "3", "1"], &["3", "2", "1997", "9", "22"]])?;
    // release_unknown_country: release, date y/m/d
    table(
        dir,
        "release_unknown_country",
        &[&["2", "1973", N, N], &["4", "2001", "11", "5"]],
    )?;

    // medium: id, release, position, format, name, edits_pending, last_updated,
    //         track_count, gid
    // Medium 1 claims 10 tracks but carries 3: the pack must publish the count it
    // can actually serve, not the dump's.
    table(
        dir,
        "medium",
        &[
            &["1", "1", "1", "1", "", "0", TS, "10", "d0000000-0000-0000-0000-000000000001"],
            &["2", "2", "1", "7", "", "0", TS, "2", "d0000000-0000-0000-0000-000000000002"],
            &["3", "3", "1", "1", "", "0", TS, "2", "d0000000-0000-0000-0000-000000000003"],
            &["4", "4", "1", "1", "", "0", TS, "2", "d0000000-0000-0000-0000-000000000004"],
            &["5", "4", "2", "1", "", "0", TS, "1", "d0000000-0000-0000-0000-000000000005"],
            &["6", "5", "1", N, "", "0", TS, "2", "d0000000-0000-0000-0000-000000000006"],
        ],
    )?;

    // recording: id, gid, name, artist_credit, length(ms), comment,
    //            edits_pending, last_updated, video
    table(
        dir,
        "recording",
        &[
            &["1", REC_SPEAK_TO_ME, "Speak to Me", "1", "67000", "", "0", TS, F],
            &["2", REC_BREATHE, "Breathe", "1", "163000", "", "0", TS, F],
            &["3", REC_TIME, "Time", "1", "421000", "", "0", TS, F],
            &["4", REC_JOGA, "Jóga", "2", "305000", "", "0", TS, F],
            &["5", REC_BACHELORETTE, "Bachelorette", "2", "340000", "", "0", TS, F],
            &["6", REC_WISH, "Wish You Were Here", "1", "334000", "", "0", TS, F],
            &["7", REC_STANDALONE, "Untitled Demo", "1", N, "unreleased", "0", TS, F],
        ],
    )?;

    // track: id, gid, recording, medium, position, number, name, artist_credit,
    //        length(ms), edits_pending, last_updated, is_data_track
    let g = |n: u32| -> String { format!("d1000000-0000-0000-0000-{n:012}") };
    let gids: Vec<String> = (1..=12).map(g).collect();
    table(
        dir,
        "track",
        &[
            // medium 1: track 2's title differs, track 3's length differs
            &["1", &gids[0], "1", "1", "1", "1", "Speak to Me", "1", "67000", "0", TS, F],
            &["2", &gids[1], "2", "1", "2", "2", "Breathe (In the Air)", "1", "163000", "0", TS, F],
            &["3", &gids[2], "3", "1", "3", "3", "Time", "1", "425000", "0", TS, F],
            // medium 2: a pregap track at position 0
            &["4", &gids[3], "1", "2", "0", "0", "Speak to Me", "1", N, "0", TS, F],
            &["5", &gids[4], "2", "2", "1", "1", "Breathe", "1", N, "0", TS, F],
            // medium 3
            &["6", &gids[5], "4", "3", "1", "1", "Jóga", "2", "305000", "0", TS, F],
            &["7", &gids[6], "5", "3", "2", "2", "Bachelorette", "2", "340000", "0", TS, F],
            // medium 4: track 2's credit differs from its recording's
            &["8", &gids[7], "3", "4", "1", "1", "Time", "1", "421000", "0", TS, F],
            &["9", &gids[8], "6", "4", "2", "2", "Wish You Were Here", "3", "334000", "0", TS, F],
            // medium 5
            &["10", &gids[9], "1", "5", "1", "1", "Speak to Me", "1", "67000", "0", TS, F],
            // medium 6: recordings out of clustered order, so d_recording is negative
            &["11", &gids[10], "6", "6", "1", "1", "Wish You Were Here", "1", "334000", "0", TS, F],
            &["12", &gids[11], "2", "6", "2", "2", "Breathe", "1", "163000", "0", TS, F],
        ],
    )?;

    // isrc: id, recording, isrc, edits_pending, created
    table(
        dir,
        "isrc",
        &[
            &["1", "1", "GBAYE0601498", "0", TS],
            &["2", "2", "GBAYE0601499", "0", TS],
            &["3", "4", "ISABC1200001", "0", TS],
            &["4", "2", "not-an-isrc!", "0", TS],
        ],
    )?;

    Ok(())
}
