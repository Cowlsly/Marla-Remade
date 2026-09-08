//! Joining OSM places to Wikipedia leads.
//!
//! Two inputs, each far too large to hold twice: the admin places from a `.pbf`, and the abstracts
//! dump. The join is by article title, because an OSM place carries `wikipedia=en:Săcălaz` and the
//! dump's `<title>` is that same string. No Wikidata round trip is involved.
//!
//! # Which way round the pass goes
//!
//! Titles wanted are collected first, then the dump is streamed once and matched against them.
//! The other order — index the dump, then walk the places — would hold every English lead in
//! memory, which is the whole 6 GB file. This way the resident set is one string per admin place
//! with a `wikipedia` tag, of order 10^5-10^6, and the dump is never held at all.
//!
//! One title can be wanted by several ids: a city mapped as both a node and a relation is two OSM
//! elements and one article. [`Wanted`] therefore maps a title to *all* the ids that asked for it,
//! and [`Archive`](crate::Archive) stores the text once regardless, because its index is
//! id-to-offset.

use std::collections::HashMap;

use crate::abstracts::{english_title, Abstract};
use crate::{clean, Builder};

/// The titles the places want, and which OSM ids each belongs to.
#[derive(Default)]
pub struct Wanted {
    by_title: HashMap<String, Vec<u64>>,
}

impl Wanted {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record that `osm_id` wants the article its `wikipedia` tag names.
    ///
    /// A tag naming a non-English article, or no article, is ignored: this archive carries English
    /// leads and a German title would never match anything in the dump.
    pub fn add(&mut self, osm_id: u64, wikipedia_tag: &str) {
        let Some(title) = english_title(wikipedia_tag) else { return };
        if title.is_empty() {
            return;
        }
        self.by_title.entry(title.to_string()).or_default().push(osm_id);
    }

    /// Distinct titles wanted.
    pub fn titles(&self) -> usize {
        self.by_title.len()
    }

    /// Places wanting an article, counting an article wanted twice twice.
    pub fn places(&self) -> usize {
        self.by_title.values().map(Vec::len).sum()
    }

    pub fn is_empty(&self) -> bool {
        self.by_title.is_empty()
    }
}

/// What a join pass did, for the build log.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Stats {
    /// Articles in the dump that some place wanted.
    pub matched: usize,
    /// Places given a description.
    pub described: usize,
    /// Matched articles whose lead was empty, or became empty once cleaned.
    pub empty: usize,
    /// Titles nobody in the dump had. Usually a redirect, or a place tagged with an article that
    /// has since been renamed.
    pub missing: usize,
}

/// Stream `dump`, filling a [`Builder`] with the leads the places asked for.
///
/// Every id wanting a matched title gets the text; the archive stores one copy.
pub fn join(wanted: &Wanted, dump: impl Iterator<Item = Abstract>) -> (Builder, Stats) {
    let mut builder = Builder::new();
    let mut stats = Stats::default();
    let mut seen: HashMap<&str, ()> = HashMap::new();
    for article in dump {
        let Some(ids) = wanted.by_title.get(article.title.as_str()) else { continue };
        // A dump can repeat a title; the first wins, as it does in the archive itself.
        let key = wanted.by_title.get_key_value(article.title.as_str()).unwrap().0.as_str();
        if seen.insert(key, ()).is_some() {
            continue;
        }
        stats.matched += 1;
        let text = clean(&article.text);
        if text.is_empty() {
            stats.empty += 1;
            continue;
        }
        for &id in ids {
            builder.add(id, &text);
            stats.described += 1;
        }
    }
    stats.missing = wanted.titles() - stats.matched;
    (builder, stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Archive;

    fn article(title: &str, text: &str) -> Abstract {
        Abstract { title: title.to_string(), text: text.to_string() }
    }

    fn built(wanted: &Wanted, dump: Vec<Abstract>) -> (Archive, Stats) {
        let (builder, stats) = join(wanted, dump.into_iter());
        let mut bytes = Vec::new();
        builder.write(&mut bytes).expect("write");
        (Archive::parse(&bytes).expect("parse"), stats)
    }

    #[test]
    fn a_place_gets_the_lead_of_the_article_it_names() {
        let mut wanted = Wanted::new();
        wanted.add(42, "en:Săcălaz");
        let (archive, stats) = built(
            &wanted,
            vec![
                article("Paris", "Paris is in France."),
                article("Săcălaz", "Săcălaz is a [commune](https://x) in Timiș County."),
            ],
        );
        assert_eq!(archive.get(42), Some("Săcălaz is a commune in Timiș County."));
        assert_eq!(stats.matched, 1);
        assert_eq!(stats.described, 1);
        assert_eq!(stats.missing, 0, "the one title wanted was found");
    }

    /// A city mapped as both a node and a relation is two ids and one article.
    #[test]
    fn several_places_can_share_one_article() {
        let mut wanted = Wanted::new();
        wanted.add(1, "en:Paris");
        wanted.add(2, "en:Paris");
        let (archive, stats) = built(&wanted, vec![article("Paris", "Paris is in France.")]);
        assert_eq!(archive.get(1), Some("Paris is in France."));
        assert_eq!(archive.get(2), Some("Paris is in France."));
        assert_eq!(stats.matched, 1, "one article");
        assert_eq!(stats.described, 2, "two places");
        assert_eq!(archive.len(), 2);
    }

    #[test]
    fn a_title_nobody_has_is_counted_as_missing() {
        let mut wanted = Wanted::new();
        wanted.add(1, "en:Renamed Place");
        let (archive, stats) = built(&wanted, vec![article("Paris", "Paris is in France.")]);
        assert!(archive.is_empty());
        assert_eq!(stats.matched, 0);
        assert_eq!(stats.missing, 1);
    }

    /// A stub matches but has nothing to say. Better no entry than an empty one - the app cannot
    /// tell an empty description from a missing one.
    #[test]
    fn a_matched_article_with_an_empty_lead_is_not_stored() {
        let mut wanted = Wanted::new();
        wanted.add(1, "en:Stub");
        wanted.add(2, "en:Real");
        let (archive, stats) = built(
            &wanted,
            vec![article("Stub", ""), article("Real", "Something to say.")],
        );
        assert_eq!(archive.get(1), None);
        assert_eq!(archive.get(2), Some("Something to say."));
        assert_eq!(stats.matched, 2);
        assert_eq!(stats.empty, 1);
        assert_eq!(stats.described, 1);
    }

    /// A lead that is nothing but a parenthesised name list cleans away to nothing, and must be
    /// treated the same as a stub rather than stored as an empty string.
    #[test]
    fn a_lead_that_cleans_away_to_nothing_counts_as_empty() {
        let mut wanted = Wanted::new();
        wanted.add(1, "en:Names");
        let (archive, stats) = built(&wanted, vec![article("Names", "(German: X; Hungarian: Y)")]);
        assert_eq!(archive.get(1), None);
        assert_eq!(stats.empty, 1);
    }

    #[test]
    fn a_non_english_tag_is_ignored() {
        let mut wanted = Wanted::new();
        wanted.add(1, "de:Paris");
        wanted.add(2, "");
        assert!(wanted.is_empty(), "neither names an English article");
        assert_eq!(wanted.titles(), 0);
    }

    /// Dumps do contain repeated titles. The first wins, matching the archive's own rule, so a
    /// build does not depend on where in a 6 GB file a duplicate sits.
    #[test]
    fn a_repeated_title_in_the_dump_takes_the_first() {
        let mut wanted = Wanted::new();
        wanted.add(1, "en:Paris");
        let (archive, stats) = built(
            &wanted,
            vec![article("Paris", "First lead."), article("Paris", "Second lead.")],
        );
        assert_eq!(archive.get(1), Some("First lead."));
        assert_eq!(stats.matched, 1);
    }

    #[test]
    fn counts_distinguish_titles_from_places() {
        let mut wanted = Wanted::new();
        wanted.add(1, "en:Paris");
        wanted.add(2, "en:Paris");
        wanted.add(3, "en:Berlin");
        assert_eq!(wanted.titles(), 2);
        assert_eq!(wanted.places(), 3);
    }
}
