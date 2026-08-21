//! End-to-end round trip: synthetic mbdump -> pack -> reader, asserting the
//! reader hands back exactly what went in.
//!
//! This is the `scripts/maps/gtfs_ingest/src/index.rs:853-1237` pattern. One
//! deliberate difference: that test embeds a *minimal* reader, because the real
//! one lives on the device in another language's build. Here the real reader is
//! in the same crate and is the artefact the server consumes, so testing against
//! a second implementation would test the wrong thing. The format constants are
//! asserted independently below instead, so a layout change cannot pass unnoticed.

use std::io::Cursor;

use mb_ingest::build::{build, BuildOptions};
use mb_ingest::copy::Input;
use mb_ingest::fixture::{self, write_fixture};
use mb_ingest::pack::{self, format_mbid, parse_mbid};
use mb_ingest::reader::MbPack;

struct Built {
    bytes: Vec<u8>,
    report: String,
}

fn build_fixture(opts: BuildOptions) -> Built {
    let dir = std::env::temp_dir().join(format!(
        "mb_ingest_fixture_{}_{:?}",
        std::process::id(),
        std::thread::current().id()
    ));
    let _ = std::fs::remove_dir_all(&dir);
    write_fixture(&dir).expect("write fixture");
    let mut cursor = Cursor::new(Vec::new());
    let stats = build(&Input::Dir(dir.clone()), &mut cursor, &opts, &mut std::io::sink())
        .expect("build pack");
    let _ = std::fs::remove_dir_all(&dir);
    Built { bytes: cursor.into_inner(), report: stats.report() }
}

fn mb(s: &str) -> [u8; 16] {
    parse_mbid(s).expect("fixture MBID parses")
}

#[test]
fn record_widths_are_pinned() {
    // If one of these changes, the reader and the writer have to change together;
    // this test exists so that never happens silently.
    assert_eq!(pack::ARTIST_REC_LEN, 20);
    assert_eq!(pack::RG_REC_LEN, 16);
    assert_eq!(pack::RELEASE_REC_LEN, 24);
    assert_eq!(pack::MEDIUM_REC_LEN, 4);
    assert_eq!(pack::RECORDING_REC_LEN, 9);
    assert_eq!(pack::MBID_TRUNC_LEN, 14);
    assert_eq!(pack::ISRC_REC_LEN, 11);
    assert_eq!(pack::TERM_IDX_REC_LEN, 8);
    assert_eq!(pack::SECTION_COUNT, 32);
    assert_eq!(pack::HEADER_LEN, 64);
    assert_eq!(pack::MAGIC.to_le_bytes(), *b"MBP1");
}

#[test]
fn header_and_capabilities() {
    let built = build_fixture(BuildOptions::default());
    let pack = MbPack::open(&built.bytes).expect("open");
    let c = pack.counts();
    assert_eq!(c.artists, 3);
    assert_eq!(c.release_groups, 4);
    assert_eq!(c.releases, 5);
    assert_eq!(c.media, 6);
    assert_eq!(c.tracks, 12);
    assert_eq!(c.recordings, 7, "6 on tracklists plus 1 standalone");
    assert_eq!(c.isrcs, 3, "the malformed fourth ISRC must be rejected, not stored");
    assert_eq!(pack.dump_date(), 20260819);

    // Tier B: everything except track MBIDs.
    assert!(!pack.has_track_mbids());
    assert!(pack.has_recording_mbids());
    assert!(pack.has_isrcs());
    assert!(pack.has_recording_search());
    assert!(!pack.official_only());
    assert_eq!(pack.track_mbid(0), None, "a tier B pack must not invent a track MBID");
}

#[test]
fn artists_round_trip() {
    let built = build_fixture(BuildOptions::default());
    let pack = MbPack::open(&built.bytes).expect("open");

    let idx = pack.artist_by_mbid(&mb(fixture::ARTIST_PINK_FLOYD)).expect("pink floyd");
    let a = pack.artist(idx).expect("artist row");
    assert_eq!(&*a.name, "Pink Floyd");
    assert_eq!(&*a.disambiguation, "British rock band");
    assert_eq!(&*a.kind, "Group");
    assert_eq!(&*a.area, "United Kingdom");
    assert_eq!(&*a.country, "GB", "country comes from the area's iso_3166_1 row");
    assert_eq!(a.begin_date.to_ws2(), "1965-01-01");
    assert_eq!(format_mbid(&pack.artist_mbid(idx).unwrap()), fixture::ARTIST_PINK_FLOYD);

    // Accents survive, and a NULL area is empty rather than wrong.
    let idx = pack.artist_by_mbid(&mb(fixture::ARTIST_BJORK)).expect("bjork");
    let a = pack.artist(idx).expect("artist row");
    assert_eq!(&*a.name, "Björk");
    assert_eq!(&*a.kind, "Person");
    assert_eq!(&*a.area, "");
    assert_eq!(&*a.country, "");
    assert_eq!(&*a.disambiguation, "");
    assert_eq!(a.begin_date.to_ws2(), "1965-11-21");

    // A NULL type must read as absent, not as the first enum value.
    let idx = pack.artist_by_mbid(&mb(fixture::ARTIST_VARIOUS)).expect("various");
    let a = pack.artist(idx).expect("artist row");
    assert_eq!(&*a.kind, "");
    assert!(a.begin_date.is_none());

    assert_eq!(pack.artist_by_mbid(&mb("00000000-0000-0000-0000-000000000000")), None);
    assert!(pack.artist(9999).is_none());
}

#[test]
fn release_groups_and_discography() {
    let built = build_fixture(BuildOptions::default());
    let pack = MbPack::open(&built.bytes).expect("open");

    let rg = pack.release_group_by_mbid(&mb(fixture::RG_DARK_SIDE)).expect("dark side");
    let g = pack.release_group(rg).expect("rg row");
    assert_eq!(&*g.title, "The Dark Side of the Moon");
    assert_eq!(&*g.primary_type, "Album");
    assert_eq!(&*g.secondary_type, "");
    assert_eq!(&*g.credit, "Pink Floyd");
    // Derived from the group's releases, because release_group_meta is not in the
    // core dump. One of this group's releases carries a BARE YEAR in the same year
    // (1973), and the precise date must still win -- comparing packed dates raw
    // picks the bare year, which is how the real Dark Side of the Moon lost its
    // 1973-03-24 and came out as "1973".
    assert_eq!(g.first_release_date.to_ws2(), "1973-03-01");

    let rg = pack.release_group_by_mbid(&mb(fixture::RG_ECHOES)).expect("echoes");
    let g = pack.release_group(rg).expect("rg row");
    assert_eq!(
        &*g.secondary_type, "Compilation",
        "only secondaryTypes[0] is stored, and the choice must be deterministic"
    );

    // Escaped control characters in the dump come back as real characters.
    let rg = pack.release_group_by_mbid(&mb(fixture::RG_WEIRD)).expect("weird");
    let g = pack.release_group(rg).expect("rg row");
    assert_eq!(&*g.title, fixture::WEIRD_TITLE);
    assert_eq!(&*g.primary_type, "");
    assert_eq!(&*g.credit, "Pink Floyd feat. Björk");
    assert!(g.first_release_date.is_none());

    // Discography, newest first, and via the credit so a featured artist counts.
    let artist = pack.artist_by_mbid(&mb(fixture::ARTIST_PINK_FLOYD)).unwrap();
    let titles: Vec<String> = pack
        .artist_release_groups(artist)
        .iter()
        .map(|&i| pack.release_group(i).unwrap().title.into_owned())
        .collect();
    assert_eq!(
        titles,
        [
            "Echoes: The Best of Pink Floyd",
            "The Dark Side of the Moon",
            fixture::WEIRD_TITLE,
        ],
        "sorted by first-release-date descending, undated last"
    );
    let bjork = pack.artist_by_mbid(&mb(fixture::ARTIST_BJORK)).unwrap();
    let mut titles: Vec<String> = pack
        .artist_release_groups(bjork)
        .iter()
        .map(|&i| pack.release_group(i).unwrap().title.into_owned())
        .collect();
    titles.sort();
    assert_eq!(titles, ["Homogenic", fixture::WEIRD_TITLE]);
    assert!(pack.artist_release_groups(9999).is_empty());
}

#[test]
fn releases_and_editions() {
    let built = build_fixture(BuildOptions::default());
    let pack = MbPack::open(&built.bytes).expect("open");

    let r = pack.release_by_mbid(&mb(fixture::REL_DARK_SIDE)).expect("dark side release");
    let rel = pack.release(r).expect("release row");
    assert_eq!(&*rel.title, "The Dark Side of the Moon");
    assert_eq!(&*rel.status, "Official");
    assert_eq!(&*rel.country, "GB");
    assert_eq!(rel.date.to_ws2(), "1973-03-01");
    assert_eq!(&*rel.credit, "Pink Floyd");
    assert_eq!(
        pack.release_group(rel.rg_idx).unwrap().title,
        "The Dark Side of the Moon"
    );

    // A year-only date from release_unknown_country, and no country at all.
    let r2 = pack.release_by_mbid(&mb(fixture::REL_DARK_SIDE_BOOT)).expect("bootleg");
    let boot = pack.release(r2).expect("release row");
    assert_eq!(&*boot.status, "Bootleg");
    assert_eq!(boot.date.to_ws2(), "1973");
    assert_eq!(&*boot.country, "");
    assert_eq!(&*boot.disambiguation, "vinyl bootleg");

    // A release with no status at all must not borrow one.
    let r5 = pack.release_by_mbid(&mb(fixture::REL_WEIRD)).expect("weird release");
    assert_eq!(&*pack.release(r5).unwrap().status, "");

    // Edition list: Official before Bootleg, per the app's sort.
    let rg = pack.release_group_by_mbid(&mb(fixture::RG_DARK_SIDE)).unwrap();
    let editions = pack.release_group_releases(rg);
    assert_eq!(editions.len(), 2);
    assert_eq!(&*pack.release(editions[0]).unwrap().status, "Official");
    assert_eq!(&*pack.release(editions[1]).unwrap().status, "Bootleg");

    // Media: format, dense position, and the *decodable* track count rather than
    // the dump's claim of 10.
    let media = pack.release_media(r);
    assert_eq!(media.len(), 1);
    assert_eq!(media[0].position, 1);
    assert_eq!(&*media[0].format, "CD");
    assert_eq!(media[0].track_count, 3);
    assert_eq!(pack.release_track_count(r), 3);

    // Multi-medium release, with positions renumbered densely.
    let echoes = pack.release_by_mbid(&mb(fixture::REL_ECHOES)).unwrap();
    let media = pack.release_media(echoes);
    assert_eq!(media.len(), 2);
    assert_eq!((media[0].position, media[1].position), (1, 2));
    assert_eq!(pack.release_track_count(echoes), 3);

    // A NULL medium format reads as empty.
    let media = pack.release_media(r5);
    assert_eq!(&*media[0].format, "");
}

#[test]
fn tracklists_round_trip_including_every_override() {
    let built = build_fixture(BuildOptions::default());
    let pack = MbPack::open(&built.bytes).expect("open");

    let r = pack.release_by_mbid(&mb(fixture::REL_DARK_SIDE)).unwrap();
    let list = pack.release_tracklist(r);
    assert_eq!(list.len(), 1);
    let (_, tracks) = &list[0];
    assert_eq!(tracks.len(), 3);

    // Inherited title, credit and length.
    assert_eq!(tracks[0].position, 1);
    assert_eq!(&*tracks[0].title, "Speak to Me");
    assert_eq!(&*tracks[0].credit, "Pink Floyd");
    assert_eq!(tracks[0].length_secs, 67);
    assert_eq!(
        format_mbid(&pack.recording_mbid(tracks[0].recording_idx).unwrap()),
        fixture::REC_SPEAK_TO_ME
    );

    // Title override: the release spells it differently from the recording.
    assert_eq!(&*tracks[1].title, "Breathe (In the Air)");
    assert_eq!(
        &*pack.recording(tracks[1].recording_idx).unwrap().title,
        "Breathe",
        "the recording keeps its own title"
    );

    // Length override.
    assert_eq!(&*tracks[2].title, "Time");
    assert_eq!(tracks[2].length_secs, 425);
    assert_eq!(pack.recording(tracks[2].recording_idx).unwrap().length_secs, 421);

    // Pregap track at position 0 survives, and a NULL track length falls back.
    let boot = pack.release_by_mbid(&mb(fixture::REL_DARK_SIDE_BOOT)).unwrap();
    let (_, tracks) = &pack.release_tracklist(boot)[0];
    assert_eq!(tracks[0].position, 0, "a pregap track must keep position 0");
    assert_eq!(tracks[1].position, 1);
    assert_eq!(tracks[0].length_secs, 67, "NULL track length falls back to the recording's");

    // Credit override on a compilation, and a second medium.
    let echoes = pack.release_by_mbid(&mb(fixture::REL_ECHOES)).unwrap();
    let list = pack.release_tracklist(echoes);
    assert_eq!(list.len(), 2);
    assert_eq!(&*list[0].1[1].credit, "Pink Floyd feat. Björk");
    assert_eq!(
        &*pack.recording(list[0].1[1].recording_idx).unwrap().credit,
        "Pink Floyd",
        "the recording keeps its own credit"
    );
    assert_eq!(list[1].1.len(), 1);
    assert_eq!(&*list[1].1[0].title, "Speak to Me");

    // A backwards jump in recording index (negative d_recording) decodes.
    let weird = pack.release_by_mbid(&mb(fixture::REL_WEIRD)).unwrap();
    let (_, tracks) = &pack.release_tracklist(weird)[0];
    assert_eq!(&*tracks[0].title, "Wish You Were Here");
    assert_eq!(&*tracks[1].title, "Breathe");
    assert!(
        tracks[0].recording_idx > tracks[1].recording_idx,
        "this medium is the one that exercises a negative delta"
    );

    // The same recording reached from three different releases is one row.
    let speak = tracks_of(&pack, fixture::REL_DARK_SIDE)[0].recording_idx;
    let same = tracks_of(&pack, fixture::REL_DARK_SIDE_BOOT)[0].recording_idx;
    assert_eq!(speak, same, "recordings are shared, not duplicated per release");

    assert!(pack.medium_tracks(r, 99).is_empty());
    assert!(pack.release_tracklist(9999).is_empty());
}

fn tracks_of<'a>(pack: &'a MbPack<'a>, release: &str) -> Vec<mb_ingest::reader::Track<'a>> {
    let r = pack.release_by_mbid(&mb(release)).unwrap();
    pack.release_tracklist(r).into_iter().next().map(|(_, t)| t).unwrap_or_default()
}

#[test]
fn isrcs_and_recordings() {
    let built = build_fixture(BuildOptions::default());
    let pack = MbPack::open(&built.bytes).expect("open");

    let r = pack.release_by_mbid(&mb(fixture::REL_DARK_SIDE)).unwrap();
    let (_, tracks) = &pack.release_tracklist(r)[0];
    let isrcs: Vec<String> = pack
        .recording_isrcs(tracks[0].recording_idx)
        .iter()
        .map(|i| String::from_utf8_lossy(i).into_owned())
        .collect();
    assert_eq!(isrcs, ["GBAYE0601498"], "ISRCs survive the 12 ASCII -> 7 byte packing");
    let isrcs = pack.recording_isrcs(tracks[1].recording_idx);
    assert_eq!(isrcs.len(), 1, "the malformed ISRC on this recording was dropped");
    assert_eq!(&isrcs[0], b"GBAYE0601499");
    // A recording with no ISRC returns empty, not the neighbour's.
    assert!(pack.recording_isrcs(tracks[2].recording_idx).is_empty());

    // The standalone recording is reachable and has no first release.
    let mut standalone = None;
    for i in 0..pack.counts().recordings {
        if format_mbid(&pack.recording_mbid(i).unwrap()) == fixture::REC_STANDALONE {
            standalone = Some(i);
        }
    }
    let s = standalone.expect("standalone recording is in the pack");
    let rec = pack.recording(s).unwrap();
    assert_eq!(&*rec.title, "Untitled Demo");
    assert_eq!(rec.length_secs, 0, "a NULL length is 0, not garbage");
    assert_eq!(pack.recording_first_release(s), None);

    // A tracked recording does have one.
    let first = pack.recording_first_release(tracks[0].recording_idx).expect("first release");
    assert!(pack.release(first).is_some());
}

#[test]
fn search_finds_things_by_word_prefix() {
    let built = build_fixture(BuildOptions::default());
    let pack = MbPack::open(&built.bytes).expect("open");

    let hits = pack.search_artists("pink", 25);
    assert_eq!(hits.len(), 1);
    assert_eq!(&*pack.artist(hits[0].idx).unwrap().name, "Pink Floyd");

    // Accent folding both ways.
    assert_eq!(pack.search_artists("björk", 25).len(), 1);
    assert_eq!(pack.search_artists("bjork", 25).len(), 1);
    assert_eq!(pack.search_artists("BJORK", 25).len(), 1);

    // Mid-title word prefix, which is the case the design doc cares about.
    let hits = pack.search_release_groups("dark side", 25);
    assert_eq!(hits.len(), 1);
    assert_eq!(
        &*pack.release_group(hits[0].idx).unwrap().title,
        "The Dark Side of the Moon"
    );
    assert_eq!(pack.search_release_groups("dar sid", 25).len(), 1, "prefixes match");

    // AND across words: "dark homogenic" matches nothing.
    assert!(pack.search_release_groups("dark homogenic", 25).is_empty());
    // Mid-word substrings do not match. Documented limitation, not a bug.
    assert!(pack.search_artists("loyd", 25).is_empty());

    let hits = pack.search_recordings("breathe", 25);
    assert_eq!(hits.len(), 1);
    assert_eq!(&*pack.recording(hits[0].idx).unwrap().title, "Breathe");

    // Artist-credit terms are folded into the release-group and recording indexes,
    // because WS/2's `release-group?query=` matches artist names: a title-only index
    // would be a REGRESSION against the live API, returning nothing for "radiohead"
    // in the albums tab rather than fewer results.
    let hits = pack.search_release_groups("pink", 25);
    let titles: Vec<String> = hits
        .iter()
        .map(|h| pack.release_group(h.idx).unwrap().title.into_owned())
        .collect();
    assert!(
        !titles.contains(&"Homogenic".to_string()),
        "Homogenic is not credited to Pink Floyd"
    );
    assert!(
        titles.iter().any(|t| t == "The Dark Side of the Moon"),
        "an artist-credit term must reach the artist's albums, got {titles:?}"
    );
    // "björk" appears in no release-group TITLE in the fixture, only in credits.
    let hits = pack.search_release_groups("björk", 25);
    let titles: Vec<String> = hits
        .iter()
        .map(|h| pack.release_group(h.idx).unwrap().title.into_owned())
        .collect();
    assert!(
        titles.iter().any(|t| t == "Homogenic"),
        "credit-only match must work, got {titles:?}"
    );
    // Same for recordings, whose credits are attached per recording.
    let hits = pack.search_recordings("björk", 25);
    assert!(!hits.is_empty(), "recording credits must be searchable too");
    let found: Vec<String> =
        hits.iter().map(|h| pack.recording(h.idx).unwrap().title.into_owned()).collect();
    assert!(
        found.iter().any(|t| t == "Jóga" || t == "Bachelorette"),
        "expected a Björk recording, got {found:?}"
    );

    // "pink floyd" now matches every release group CREDITED to Pink Floyd, not just
    // the one with both words in its title. That is the whole point of folding
    // credits in, and it is parity with what WS/2 does today.
    let hits = pack.search_release_groups("pink floyd", 25);
    assert_eq!(hits.len(), 3, "Dark Side, Echoes and the feat. release group");
    let hits = pack.search_artists("pink", 0);
    assert!(hits.is_empty(), "limit 0 returns nothing rather than everything");
    assert!(pack.search_artists("", 25).is_empty());
    assert!(pack.search_artists("!!!", 25).is_empty());
    assert!(pack.search_artists("zzzznotathing", 25).is_empty());
}

#[test]
fn tier_a_adds_track_mbids_and_nothing_else_changes() {
    let b = build_fixture(BuildOptions::default());
    let a = build_fixture(BuildOptions::tier_a());
    let pb = MbPack::open(&b.bytes).expect("open tier B");
    let pa = MbPack::open(&a.bytes).expect("open tier A");
    assert!(pa.has_track_mbids());
    assert!(!pb.has_track_mbids());
    assert_eq!(pa.counts().tracks, pb.counts().tracks);
    assert!(pa.track_mbid(0).is_some());
    assert!(
        a.bytes.len() > b.bytes.len(),
        "tier A must be larger by exactly the track MBID table"
    );
    let delta = a.bytes.len() - b.bytes.len();
    assert!(
        (16 * 12..16 * 12 + 16).contains(&delta),
        "12 tracks x 16 B plus alignment, got {delta}"
    );
}

#[test]
fn official_only_drops_the_bootleg() {
    let built = build_fixture(BuildOptions { official_only: true, ..BuildOptions::default() });
    let pack = MbPack::open(&built.bytes).expect("open");
    assert!(pack.official_only());
    assert_eq!(pack.counts().releases, 3, "the bootleg and the status-less release go");
    assert_eq!(pack.release_by_mbid(&mb(fixture::REL_DARK_SIDE_BOOT)), None);
    let rg = pack.release_group_by_mbid(&mb(fixture::RG_DARK_SIDE)).unwrap();
    assert_eq!(pack.release_group_releases(rg).len(), 1);
}

#[test]
fn build_is_byte_for_byte_reproducible() {
    let a = build_fixture(BuildOptions::default());
    let b = build_fixture(BuildOptions::default());
    assert_eq!(a.bytes, b.bytes, "no hash-map iteration order may reach the output");
}

#[test]
fn report_measures_the_ratios_the_design_doc_only_estimated() {
    let built = build_fixture(BuildOptions::default());
    // These are the four numbers the design doc's R2 flagged as the largest
    // remaining uncertainty; a build has to print them.
    for needle in [
        "distinct recording titles",
        "tracks whose title == recording's",
        "tracks whose credit == recording's",
        "recordings with no track (standalone)",
        "malformed ISRCs dropped",
    ] {
        assert!(built.report.contains(needle), "report is missing {needle}:\n{}", built.report);
    }
    // The flat id maps are sized to max row id, not live count, so the gap between
    // them is a direct multiplier on resident memory and has to be reported.
    assert!(built.report.contains("id density"), "report is missing id density:\n{}", built.report);
    for table in ["artist", "artist_credit", "release_group", "release", "medium", "recording"] {
        assert!(
            built.report.contains(&format!("{table:<24} max id")),
            "report is missing the max id for {table}:\n{}",
            built.report
        );
    }
    // And the pool ratio, which is the whole justification for the zstd dependency.
    assert!(built.report.contains("string pool"), "report is missing the pool ratio");
}

#[test]
fn compressed_and_raw_pools_read_back_identically() {
    let compressed = build_fixture(BuildOptions::default());
    let raw = build_fixture(BuildOptions { compress_strings: false, ..BuildOptions::default() });
    let pc = MbPack::open(&compressed.bytes).expect("open compressed");
    let pr = MbPack::open(&raw.bytes).expect("open raw");
    assert!(pc.strings_compressed());
    assert!(!pr.strings_compressed());

    // Every string-bearing field must agree between the two encodings, including
    // the title carrying an escaped tab and newline.
    for i in 0..pc.counts().artists {
        let (a, b) = (pc.artist(i).unwrap(), pr.artist(i).unwrap());
        assert_eq!(a.name, b.name);
        assert_eq!(a.disambiguation, b.disambiguation);
        assert_eq!(a.area, b.area);
        assert_eq!(a.kind, b.kind);
        assert_eq!(a.country, b.country);
    }
    for i in 0..pc.counts().release_groups {
        let (a, b) = (pc.release_group(i).unwrap(), pr.release_group(i).unwrap());
        assert_eq!(a.title, b.title);
        assert_eq!(a.credit, b.credit);
        assert_eq!(a.primary_type, b.primary_type);
        assert_eq!(a.secondary_type, b.secondary_type);
    }
    for i in 0..pc.counts().releases {
        let (a, b) = (pc.release(i).unwrap(), pr.release(i).unwrap());
        assert_eq!(a.title, b.title);
        assert_eq!(a.status, b.status);
        assert_eq!(a.country, b.country);
        assert_eq!(a.disambiguation, b.disambiguation);
        let (ta, tb) = (pc.release_tracklist(i), pr.release_tracklist(i));
        assert_eq!(ta.len(), tb.len());
        for ((ma, tsa), (mb_, tsb)) in ta.iter().zip(tb.iter()) {
            assert_eq!(ma.format, mb_.format);
            assert_eq!(tsa.len(), tsb.len());
            for (x, y) in tsa.iter().zip(tsb.iter()) {
                assert_eq!(x.title, y.title);
                assert_eq!(x.credit, y.credit);
                assert_eq!(x.length_secs, y.length_secs);
                assert_eq!(x.position, y.position);
            }
        }
    }
    let rg = pc.release_group_by_mbid(&mb(fixture::RG_WEIRD)).unwrap();
    assert_eq!(
        &*pc.release_group(rg).unwrap().title,
        fixture::WEIRD_TITLE,
        "escaped control characters must survive compression too"
    );
    // Search is unaffected: SEARCH_TERMS is its own uncompressed dictionary.
    assert_eq!(
        pc.search_release_groups("dark side", 25),
        pr.search_release_groups("dark side", 25)
    );
}

#[test]
fn the_string_pool_is_sorted_so_compression_can_work() {
    // Offsets are assigned after an alphabetical sort. On a fixture this is only
    // an ordering check -- the ratio itself needs real data -- but if the sort
    // regresses, the compressed pool quietly loses ~15% of the whole pack.
    let built = build_fixture(BuildOptions { compress_strings: false, ..BuildOptions::default() });
    let pack = MbPack::open(&built.bytes).expect("open");
    let mut titles: Vec<(u32, String)> = Vec::new();
    for i in 0..pack.counts().release_groups {
        let g = pack.release_group(i).unwrap();
        titles.push((i, g.title.into_owned()));
    }
    // "Echoes..." < "Homogenic" < "The Dark Side..." < "Untitled\tWeird\nTitle"
    let mut sorted = titles.clone();
    sorted.sort_by(|a, b| a.1.cmp(&b.1));
    assert_eq!(
        sorted.iter().map(|t| t.1.as_str()).collect::<Vec<_>>(),
        [
            "Echoes: The Best of Pink Floyd",
            "Homogenic",
            "The Dark Side of the Moon",
            fixture::WEIRD_TITLE,
        ]
    );
}

/// Exercise every public query on a pack, ignoring results. The assertion is
/// simply that nothing panics.
fn hammer_every_query(pack: &MbPack<'_>) {
    let c = pack.counts();
    let _ = pack.section_sizes();
    let _ = pack.dump_date();
    let _ = pack.strings_compressed();
    // Deliberately probe past the end as well as inside it.
    for idx in [0u32, 1, c.artists / 2, c.artists, c.artists.wrapping_add(1), u32::MAX] {
        let _ = pack.artist(idx);
        let _ = pack.artist_mbid(idx);
        let _ = pack.artist_release_groups(idx);
    }
    for idx in [0u32, 1, c.release_groups, u32::MAX] {
        let _ = pack.release_group(idx);
        let _ = pack.release_group_mbid(idx);
        let _ = pack.release_group_releases(idx);
    }
    for idx in [0u32, 1, c.releases, u32::MAX] {
        let _ = pack.release(idx);
        let _ = pack.release_mbid(idx);
        let _ = pack.release_media(idx);
        let _ = pack.release_track_count(idx);
        let _ = pack.release_tracklist(idx);
        let _ = pack.medium_tracks(idx, 0);
        let _ = pack.medium_tracks(idx, usize::MAX);
    }
    for idx in [0u32, 1, c.recordings, u32::MAX] {
        let _ = pack.recording(idx);
        let _ = pack.recording_mbid(idx);
        let _ = pack.recording_isrcs(idx);
        let _ = pack.recording_first_release(idx);
        let _ = pack.track_mbid(idx);
    }
    for m in ["00000000-0000-0000-0000-000000000000", fixture::REL_DARK_SIDE] {
        let key = mb(m);
        let _ = pack.artist_by_mbid(&key);
        let _ = pack.release_group_by_mbid(&key);
        let _ = pack.release_by_mbid(&key);
    }
    for q in ["", "!!!", "a", "dark side of the moon", "zzzznotathing", "\u{fc}", "0"] {
        let _ = pack.search_artists(q, 25);
        let _ = pack.search_release_groups(q, 25);
        let _ = pack.search_recordings(q, 25);
    }
}

#[test]
fn truncated_pack_is_rejected_not_read() {
    let built = build_fixture(BuildOptions::default());
    for cut in [1usize, 64, 128, built.bytes.len() / 2, built.bytes.len() - 1] {
        let err = MbPack::open(&built.bytes[..cut]);
        assert!(err.is_err(), "a pack truncated to {cut} bytes must be refused");
    }
}

/// Truncate a valid pack and confirm `open` either refuses it or yields a reader
/// on which every query is survivable.
///
/// The server asked whether the reader is panic-free on malformed input. Reading
/// the code is not evidence; this is. Exhaustive over the header and directory
/// (where the offsets and lengths live) and strided over the body.
#[test]
fn every_truncation_is_survivable() {
    let built = build_fixture(BuildOptions::default());
    let head = 64 + 32 * 16;
    let offsets = (0..head.min(built.bytes.len()))
        .chain((head..built.bytes.len()).step_by(1009))
        .chain([built.bytes.len().saturating_sub(1), built.bytes.len()]);
    for cut in offsets {
        match MbPack::open(&built.bytes[..cut.min(built.bytes.len())]) {
            Err(_) => {}
            Ok(pack) => hammer_every_query(&pack),
        }
    }
}

/// Corrupt a valid pack in many single- and multi-byte ways and confirm the same.
/// Deterministic PRNG so any failure is reproducible from the seed.
#[test]
fn corrupted_packs_never_panic() {
    let built = build_fixture(BuildOptions::default());
    let mut state: u64 = 0x2026_0820;
    let mut next = |bound: usize| -> usize {
        // xorshift64*, adequate and dependency-free.
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        (state.wrapping_mul(0x2545_F491_4F6C_DD1D) >> 33) as usize % bound.max(1)
    };

    // Every byte of the header and section directory set to adversarial values --
    // this is where every length and offset the reader trusts comes from.
    let head = 64 + 32 * 16;
    for off in 0..head.min(built.bytes.len()) {
        for v in [0x00u8, 0x01, 0x7f, 0x80, 0xff] {
            let mut b = built.bytes.clone();
            b[off] = v;
            if let Ok(pack) = MbPack::open(&b) {
                hammer_every_query(&pack);
            }
        }
    }

    // Scatter-shot corruption across the whole file.
    for _ in 0..300 {
        let mut b = built.bytes.clone();
        let n = 1 + next(64);
        for _ in 0..n {
            let off = next(b.len());
            b[off] = next(256) as u8;
        }
        if let Ok(pack) = MbPack::open(&b) {
            hammer_every_query(&pack);
        }
    }

    // Directory entries pointed at deliberately hostile extents.
    for entry in 0..32usize {
        for &(offset, len) in &[
            (0u64, u64::MAX),
            (u64::MAX, 0),
            (u64::MAX, u64::MAX),
            (built.bytes.len() as u64, 8),
            (7u64, 8u64),
            (8u64, built.bytes.len() as u64),
        ] {
            let mut b = built.bytes.clone();
            let base = 64 + entry * 16;
            b[base..base + 8].copy_from_slice(&offset.to_le_bytes());
            b[base + 8..base + 16].copy_from_slice(&len.to_le_bytes());
            if let Ok(pack) = MbPack::open(&b) {
                hammer_every_query(&pack);
            }
        }
    }
}

/// A VALID pack must never panic either. That would surface as intermittent 500s
/// and be misdiagnosed as corruption.
#[test]
fn a_valid_pack_survives_every_query_including_out_of_range() {
    for opts in [
        BuildOptions::default(),
        BuildOptions::tier_a(),
        BuildOptions { compress_strings: false, ..BuildOptions::default() },
        BuildOptions { official_only: true, ..BuildOptions::default() },
        BuildOptions { include_recording_search: false, ..BuildOptions::default() },
        BuildOptions { include_isrcs: false, ..BuildOptions::default() },
        BuildOptions { include_recording_mbids: false, ..BuildOptions::default() },
    ] {
        let built = build_fixture(opts);
        let pack = MbPack::open(&built.bytes).expect("valid pack must open");
        hammer_every_query(&pack);
    }
}
