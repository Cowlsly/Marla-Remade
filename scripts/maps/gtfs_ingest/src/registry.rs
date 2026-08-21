//! Resolving the Transitous registry against transitland-atlas, so a feed's stops
//! can carry MOTIS ids.
//!
//! A port of `build_ca_transit.ps1`'s resolution, generalised from one region file
//! to a glob over all of them. `build_world_transit.sh` used to scrape `url` fields
//! out of the registry, which silently dropped every source referenced only by
//! `transitland-atlas-id` -- **38 of California's 49**. Worse, index-based feed names
//! (`us_ca_0`, `us_ca_1`, …) meant its manifest was two-field, so nothing had a
//! MOTIS prefix and live delays could never be matched to a stop.
//!
//! # Why the atlas is consulted even when the registry has a URL
//!
//! An agency is usually listed **twice** in the atlas: once for its schedule
//! (`spec: gtfs`) and once for its realtime feed (`spec: gtfs-rt`). Only the atlas
//! entry's `spec` distinguishes them. If the registry's `url-override` were trusted
//! first, a realtime endpoint could win the dedup and knock the real schedule out of
//! the build -- a feed silently missing its timetable, which is worse than a feed
//! that is loudly absent. So the atlas entry is resolved and its `spec` checked
//! before any URL is chosen.
//!
//! # URL precedence
//!
//! 1. `url-override` from the registry -- a deliberate correction, so it wins.
//! 2. `url` from the registry.
//! 3. `urls.static_current` from the atlas, **unless** the feed has an
//!    `authorization` block, in which case an unauthenticated fetch will fail.
//! 4. The first `urls.static_historic` entry -- stale beats absent.
//! 5. `static_current` anyway, as a last resort, flagged as likely to fail.
//!
//! # Two names per feed, and why
//!
//! [`Resolved::name`] is sanitised for use as a directory and a string-pool entry;
//! [`Resolved::source`] is the registry's original name, which is what composes the
//! MOTIS prefix. `SF-bayarea` sanitises to `sf_bayarea` and cannot be recovered from
//! it, which is exactly why the two are carried separately and why the manifest has
//! three fields.

use crate::json::Json;

/// One usable feed.
#[derive(Debug, Clone, PartialEq)]
pub struct Resolved {
    /// Filename- and pool-safe. Also the manifest's first field.
    pub name: String,
    /// The registry's original source name, unmangled. Composes the MOTIS prefix.
    pub source: String,
    pub url: String,
    /// Which rule chose the URL, for the report.
    pub via: &'static str,
}

/// One source that could not be used, and why. Reported rather than dropped: a feed
/// vanishing without explanation is how 38 of California's agencies went missing for
/// as long as they did.
#[derive(Debug, Clone, PartialEq)]
pub struct Skipped {
    pub name: String,
    pub why: String,
}

#[derive(Debug, Default)]
pub struct Resolution {
    pub feeds: Vec<Resolved>,
    pub skipped: Vec<Skipped>,
    /// Sources seen across every region file.
    pub considered: usize,
}

/// `Get-SafeName`: lowercase, and every run of non-alphanumerics becomes one `_`.
pub fn safe_name(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut pending = false;
    for c in s.chars() {
        if c.is_ascii_alphanumeric() {
            if pending && !out.is_empty() {
                out.push('_');
            }
            pending = false;
            out.push(c.to_ascii_lowercase());
        } else {
            pending = true;
        }
    }
    out
}

/// An index of every atlas feed by its id, built from the `*.dmfr.json` documents.
pub fn index_atlas(docs: &[Json]) -> std::collections::HashMap<&str, &Json> {
    let mut out = std::collections::HashMap::new();
    for doc in docs {
        for feed in doc.get("feeds").map(Json::as_array).unwrap_or_default() {
            if let Some(id) = feed.get("id").and_then(Json::as_str) {
                out.insert(id, feed);
            }
        }
    }
    out
}

/// A URL that is a realtime endpoint rather than a schedule zip.
///
/// Tests the **path**, not the whole URL, and treats a `.zip` as definitively
/// static. `build_world_transit.sh` matched `*realtime*` against the whole URL,
/// which threw away Golden Gate Transit's perfectly good schedule zip because it is
/// served from `realtime.goldengate.org`.
pub fn is_realtime(url: &str) -> bool {
    let path = url.split('?').next().unwrap_or(url);
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".zip") {
        return false;
    }
    lower.contains("gtfs-rt")
        || lower.contains("gtfs_rt")
        || lower.contains("gtfsrt")
        || lower.ends_with(".pb")
        || url.contains("feed_type=")
}

/// Resolve one registry region file's sources.
///
/// `region` is the file's base name (`us-ca`), which prefixes the MOTIS namespace.
pub fn resolve_region(
    region: &str,
    registry: &Json,
    atlas: &std::collections::HashMap<&str, &Json>,
    out: &mut Resolution,
) {
    for s in registry.get("sources").map(Json::as_array).unwrap_or_default() {
        out.considered += 1;
        let atlas_id = s.get("transitland-atlas-id").and_then(Json::as_str);
        let name = s
            .get("name")
            .and_then(Json::as_str)
            .or(atlas_id)
            .unwrap_or("")
            .to_string();
        let mut skip = |why: String| out.skipped.push(Skipped { name: name.clone(), why });

        if s.get("skip").is_some_and(Json::is_truthy) {
            let reason = s
                .get("skip-reason")
                .and_then(Json::as_str)
                .unwrap_or("no reason given");
            skip(format!("registry says skip: {reason}"));
            continue;
        }
        if let Some(spec) = s.get("spec").and_then(Json::as_str) {
            if spec != "gtfs" {
                skip(format!("registry spec is {spec}"));
                continue;
            }
        }
        if name.is_empty() {
            skip("source has neither a name nor an atlas id".to_string());
            continue;
        }

        // The atlas entry is resolved BEFORE any URL is chosen. See the module docs.
        let mut feed: Option<&Json> = None;
        if let Some(id) = atlas_id {
            match atlas.get(id) {
                None => {
                    skip(format!("atlas id {id} is not in the atlas"));
                    continue;
                }
                Some(f) => {
                    match f.get("spec").and_then(Json::as_str) {
                        // A missing spec means gtfs, which is the atlas default.
                        None | Some("gtfs") => feed = Some(f),
                        Some(other) => {
                            skip(format!("atlas spec is {other}"));
                            continue;
                        }
                    }
                }
            }
        }

        let needs_key = feed.is_some_and(|f| f.get("authorization").is_some_and(Json::is_truthy));
        let urls = feed.and_then(|f| f.get("urls"));
        let current = urls.and_then(|u| u.get("static_current")).and_then(Json::as_str);
        let historic = urls
            .and_then(|u| u.get("static_historic"))
            .map(Json::as_array)
            .unwrap_or_default()
            .iter()
            .filter_map(Json::as_str)
            .find(|u| !u.is_empty());

        let chosen: Option<(&str, &'static str)> = s
            .get("url-override")
            .and_then(Json::as_str)
            .filter(|u| !u.is_empty())
            .map(|u| (u, "url-override"))
            .or_else(|| {
                s.get("url")
                    .and_then(Json::as_str)
                    .filter(|u| !u.is_empty())
                    .map(|u| (u, "registry url"))
            })
            .or_else(|| {
                current
                    .filter(|u| !u.is_empty() && !needs_key)
                    .map(|u| (u, "atlas static_current"))
            })
            .or_else(|| {
                historic.map(|u| {
                    if needs_key {
                        (u, "atlas static_historic (static_current needs a key)")
                    } else {
                        (u, "atlas static_historic")
                    }
                })
            })
            .or_else(|| {
                current
                    .filter(|u| !u.is_empty())
                    .map(|u| (u, "atlas static_current (needs a key; will likely fail)"))
            });

        let Some((url, via)) = chosen else {
            skip("no usable GTFS URL".to_string());
            continue;
        };
        if is_realtime(url) {
            skip(format!("URL is a realtime endpoint: {url}"));
            continue;
        }

        out.feeds.push(Resolved {
            name: safe_name(&name),
            source: format!("{region}-{name}"),
            url: url.to_string(),
            via,
        });
    }
}

/// Drop duplicates, keeping the first of each name and then of each URL.
///
/// Two passes because they catch different things: the same agency listed in two
/// region files shares a name, and two differently-named sources pointing at one
/// combined feed share a URL. Both would otherwise be downloaded and merged twice.
pub fn dedup(feeds: &mut Vec<Resolved>) {
    let mut names = std::collections::HashSet::new();
    feeds.retain(|f| names.insert(f.name.clone()));
    let mut urls = std::collections::HashSet::new();
    feeds.retain(|f| urls.insert(f.url.clone()));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::json;

    fn atlas_docs(text: &str) -> Vec<Json> {
        vec![json::parse(text).unwrap()]
    }

    fn resolve(region_text: &str, atlas_text: &str) -> Resolution {
        let docs = atlas_docs(atlas_text);
        let atlas = index_atlas(&docs);
        let registry = json::parse(region_text).unwrap();
        let mut out = Resolution::default();
        resolve_region("us-ca", &registry, &atlas, &mut out);
        out
    }

    const ATLAS: &str = r#"{"feeds":[
        {"id":"f-static","spec":"gtfs","urls":{"static_current":"https://a/sched.zip"}},
        {"id":"f-rt","spec":"gtfs-rt","urls":{"static_current":"https://a/rt.pb"}},
        {"id":"f-keyed","spec":"gtfs","authorization":{"type":"query_param"},
         "urls":{"static_current":"https://a/keyed.zip",
                 "static_historic":["https://a/old.zip"]}},
        {"id":"f-nospec","urls":{"static_current":"https://a/nospec.zip"}},
        {"id":"f-nourl","spec":"gtfs","urls":{}}
    ]}"#;

    #[test]
    fn safe_name_matches_get_safe_name() {
        assert_eq!(safe_name("SF-bayarea"), "sf_bayarea");
        assert_eq!(safe_name("Golden Gate Transit"), "golden_gate_transit");
        assert_eq!(safe_name("--leading and trailing--"), "leading_and_trailing");
        assert_eq!(safe_name("a...b"), "a_b");
        assert_eq!(safe_name("ALLCAPS"), "allcaps");
        assert_eq!(safe_name(""), "");
        assert_eq!(safe_name("---"), "");
    }

    #[test]
    fn a_source_referenced_only_by_atlas_id_resolves() {
        // The whole point: build_world_transit.sh dropped these, 38 of 49 in CA.
        let r = resolve(r#"{"sources":[{"transitland-atlas-id":"f-static"}]}"#, ATLAS);
        assert_eq!(r.feeds.len(), 1, "{:?}", r.skipped);
        assert_eq!(r.feeds[0].url, "https://a/sched.zip");
        assert_eq!(r.feeds[0].via, "atlas static_current");
        // The name falls back to the atlas id when the registry gives none.
        assert_eq!(r.feeds[0].name, "f_static");
        assert_eq!(r.feeds[0].source, "us-ca-f-static");
    }

    #[test]
    fn the_motis_prefix_keeps_the_unmangled_source_name() {
        let r = resolve(
            r#"{"sources":[{"name":"SF-bayarea","transitland-atlas-id":"f-static"}]}"#,
            ATLAS,
        );
        // Sanitising is lossy, which is why both names are carried.
        assert_eq!(r.feeds[0].name, "sf_bayarea");
        assert_eq!(r.feeds[0].source, "us-ca-SF-bayarea");
    }

    #[test]
    fn url_precedence_runs_override_registry_current_historic() {
        let r = resolve(
            r#"{"sources":[{"name":"A","transitland-atlas-id":"f-static",
                 "url-override":"https://o/x.zip","url":"https://r/x.zip"}]}"#,
            ATLAS,
        );
        assert_eq!(r.feeds[0].url, "https://o/x.zip");
        assert_eq!(r.feeds[0].via, "url-override");

        let r = resolve(
            r#"{"sources":[{"name":"A","transitland-atlas-id":"f-static","url":"https://r/x.zip"}]}"#,
            ATLAS,
        );
        assert_eq!(r.feeds[0].url, "https://r/x.zip");
        assert_eq!(r.feeds[0].via, "registry url");
    }

    #[test]
    fn an_authorized_feed_prefers_a_historic_url_over_one_needing_a_key() {
        // static_current would 401. Stale beats absent.
        let r = resolve(r#"{"sources":[{"name":"K","transitland-atlas-id":"f-keyed"}]}"#, ATLAS);
        assert_eq!(r.feeds[0].url, "https://a/old.zip");
        assert_eq!(r.feeds[0].via, "atlas static_historic (static_current needs a key)");
    }

    #[test]
    fn a_keyed_feed_with_no_history_falls_back_and_says_so() {
        let atlas = atlas_docs(
            r#"{"feeds":[{"id":"f-k","spec":"gtfs","authorization":{"type":"header"},
                 "urls":{"static_current":"https://a/keyed.zip"}}]}"#,
        );
        let index = index_atlas(&atlas);
        let mut out = Resolution::default();
        resolve_region(
            "us-ca",
            &json::parse(r#"{"sources":[{"name":"K","transitland-atlas-id":"f-k"}]}"#).unwrap(),
            &index,
            &mut out,
        );
        assert_eq!(out.feeds[0].url, "https://a/keyed.zip");
        assert!(out.feeds[0].via.contains("will likely fail"));
    }

    #[test]
    fn the_atlas_spec_is_checked_before_any_url_is_chosen() {
        // A realtime atlas entry is refused even though the registry supplies a
        // perfectly fetchable override. Trusting the override first is how a
        // realtime endpoint wins the dedup and knocks out the real schedule.
        let r = resolve(
            r#"{"sources":[{"name":"RT","transitland-atlas-id":"f-rt",
                 "url-override":"https://o/looks-fine.zip"}]}"#,
            ATLAS,
        );
        assert!(r.feeds.is_empty(), "{:?}", r.feeds);
        assert_eq!(r.skipped.len(), 1);
        assert!(r.skipped[0].why.contains("atlas spec is gtfs-rt"), "{:?}", r.skipped);
    }

    #[test]
    fn a_missing_atlas_spec_counts_as_gtfs() {
        let r = resolve(r#"{"sources":[{"name":"N","transitland-atlas-id":"f-nospec"}]}"#, ATLAS);
        assert_eq!(r.feeds.len(), 1, "{:?}", r.skipped);
        assert_eq!(r.feeds[0].url, "https://a/nospec.zip");
    }

    #[test]
    fn every_skip_reason_is_reported_rather_than_silent() {
        let cases = [
            (
                r#"{"sources":[{"name":"S","skip":true,"skip-reason":"broken since 2019"}]}"#,
                "registry says skip: broken since 2019",
            ),
            (
                r#"{"sources":[{"name":"S","spec":"gtfs-rt","url":"https://a/x.zip"}]}"#,
                "registry spec is gtfs-rt",
            ),
            (
                r#"{"sources":[{"name":"S","transitland-atlas-id":"f-missing"}]}"#,
                "atlas id f-missing is not in the atlas",
            ),
            (
                r#"{"sources":[{"name":"S","transitland-atlas-id":"f-nourl"}]}"#,
                "no usable GTFS URL",
            ),
            (r#"{"sources":[{"name":"S"}]}"#, "no usable GTFS URL"),
            (r#"{"sources":[{"url":"https://a/x.zip"}]}"#, "neither a name nor an atlas id"),
        ];
        for (text, want) in cases {
            let r = resolve(text, ATLAS);
            assert!(r.feeds.is_empty(), "{text} should not resolve");
            assert_eq!(r.skipped.len(), 1, "{text}");
            assert!(
                r.skipped[0].why.contains(want),
                "{text}: wanted {want:?}, got {:?}",
                r.skipped[0].why
            );
        }
    }

    #[test]
    fn realtime_detection_looks_at_the_path_and_spares_zips() {
        // The Golden Gate case: a schedule zip on a host called `realtime`.
        assert!(!is_realtime("https://realtime.goldengate.org/gtfs/sched.zip"));
        assert!(!is_realtime("https://a/gtfs.zip?key=1"));
        // Genuine realtime endpoints.
        assert!(is_realtime("https://a/gtfs-rt/trips"));
        assert!(is_realtime("https://a/gtfs_rt?agency=x"));
        assert!(is_realtime("https://a/gtfsrt/vehicles"));
        assert!(is_realtime("https://a/feed.pb"));
        assert!(is_realtime("https://a/api/feed?feed_type=trip"));
        // A query string alone does not make it realtime.
        assert!(!is_realtime("https://a/download?agency=x"));
    }

    #[test]
    fn a_realtime_url_is_skipped_even_when_the_atlas_says_gtfs() {
        let r = resolve(
            r#"{"sources":[{"name":"R","transitland-atlas-id":"f-static",
                 "url-override":"https://a/gtfs-rt/trips"}]}"#,
            ATLAS,
        );
        assert!(r.feeds.is_empty());
        assert!(r.skipped[0].why.contains("realtime endpoint"));
    }

    #[test]
    fn dedup_drops_repeats_by_name_then_by_url() {
        let mut feeds = vec![
            Resolved { name: "a".into(), source: "r-A".into(), url: "u1".into(), via: "x" },
            // Same name from another region file.
            Resolved { name: "a".into(), source: "s-A".into(), url: "u2".into(), via: "x" },
            // Different name, same combined feed.
            Resolved { name: "b".into(), source: "r-B".into(), url: "u1".into(), via: "x" },
            Resolved { name: "c".into(), source: "r-C".into(), url: "u3".into(), via: "x" },
        ];
        dedup(&mut feeds);
        assert_eq!(
            feeds.iter().map(|f| f.name.as_str()).collect::<Vec<_>>(),
            ["a", "c"]
        );
        // The first of each is the one kept, so the result is order-stable.
        assert_eq!(feeds[0].source, "r-A");
    }

    #[test]
    fn a_registry_with_no_sources_resolves_to_nothing_without_erroring() {
        let r = resolve("{}", ATLAS);
        assert_eq!(r.considered, 0);
        assert!(r.feeds.is_empty() && r.skipped.is_empty());
        let r = resolve(r#"{"sources":[]}"#, ATLAS);
        assert_eq!(r.considered, 0);
    }

    #[test]
    fn the_atlas_index_covers_every_document() {
        let docs = vec![
            json::parse(r#"{"feeds":[{"id":"a"},{"id":"b"}]}"#).unwrap(),
            json::parse(r#"{"feeds":[{"id":"c"}]}"#).unwrap(),
            // A document with no feeds must not break the index.
            json::parse(r#"{"license":{}}"#).unwrap(),
        ];
        let index = index_atlas(&docs);
        assert_eq!(index.len(), 3);
        assert!(index.contains_key("a") && index.contains_key("c"));
    }
}
