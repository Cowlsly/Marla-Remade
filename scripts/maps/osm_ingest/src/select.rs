//! Generic tag matching: the `osmium tags-filter` equivalent.
//!
//! Each layer script narrows a PBF with an expression like
//!
//! ```text
//! osmium tags-filter n/highway=speed_camera,stop,traffic_signals \
//!                    n/man_made=surveillance \
//!                    n/enforcement=maxspeed
//! ```
//!
//! [`Select`] is that expression: a set of `key=v1,v2` clauses, matching when
//! **any** of them does, which is what osmium's OR semantics mean. A clause with
//! no `=` matches on key presence alone (`w/maxspeed`).
//!
//! ## Why a lookup closure rather than a tag map
//!
//! [`Select::matches`] takes `impl Fn(&str) -> Option<&str>`, the shape
//! [`crate::osm::Tags::get_str`] already has. That means no map is built per
//! element on the hot path, and -- more usefully -- a selector can be unit-tested
//! against a plain array of pairs with no PBF anywhere in sight, exactly like the
//! classifiers in [`crate::tags`].
//!
//! This is a pre-filter, not the layer's schema decision. It exists to skip the
//! overwhelming majority of elements cheaply; the per-layer classifier still has
//! the final say on what is emitted and as what.

use crate::proto::{Error, Result};

/// One `key` or `key=v1,v2` clause.
#[derive(Debug, Clone, PartialEq)]
pub struct Clause {
    pub key: String,
    /// Empty means "any value", i.e. match on the key being present at all.
    pub values: Vec<String>,
}

impl Clause {
    fn matches(&self, value: Option<&str>) -> bool {
        match value {
            None => false,
            Some(v) => {
                if self.values.is_empty() {
                    // osmium's bare `w/maxspeed` matches a present key whatever
                    // its value, including the empty string.
                    true
                } else {
                    self.values.iter().any(|w| w == v)
                }
            }
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Select {
    pub clauses: Vec<Clause>,
}

impl Select {
    /// Parse one clause per element of `specs`, in the `key` or `key=v1,v2` form.
    pub fn parse(specs: &[&str]) -> Result<Select> {
        let mut clauses = Vec::with_capacity(specs.len());
        for spec in specs {
            let spec = spec.trim();
            if spec.is_empty() {
                return Err(Error("empty tag filter".into()));
            }
            let (key, values) = match spec.split_once('=') {
                None => (spec.to_string(), Vec::new()),
                Some((k, v)) => {
                    let k = k.trim();
                    if k.is_empty() {
                        return Err(Error(format!("tag filter has no key: {spec}")));
                    }
                    let values: Vec<String> = v
                        .split(',')
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .map(str::to_string)
                        .collect();
                    if values.is_empty() {
                        return Err(Error(format!("tag filter has no values: {spec}")));
                    }
                    (k.to_string(), values)
                }
            };
            clauses.push(Clause { key, values });
        }
        if clauses.is_empty() {
            return Err(Error("a selector needs at least one tag filter".into()));
        }
        Ok(Select { clauses })
    }

    /// True when any clause matches. `get` is a tag lookup, e.g.
    /// `|k| tags.get_str(k)`.
    pub fn matches<'a, F>(&self, get: F) -> bool
    where
        F: Fn(&str) -> Option<&'a str>,
    {
        self.clauses.iter().any(|c| c.matches(get(&c.key)))
    }

    /// Every key any clause mentions, deduplicated. Useful for a cheap
    /// "does this element carry any interesting key at all" screen.
    pub fn keys(&self) -> Vec<&str> {
        let mut out: Vec<&str> = self.clauses.iter().map(|c| c.key.as_str()).collect();
        out.sort_unstable();
        out.dedup();
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A tag lookup over a plain slice -- the point of the closure-based API is
    /// that a test needs nothing more than this.
    fn tags<'a>(pairs: &'a [(&'a str, &'a str)]) -> impl Fn(&str) -> Option<&'a str> + 'a {
        move |k| pairs.iter().find(|(pk, _)| *pk == k).map(|(_, v)| *v)
    }

    #[test]
    fn parses_the_osmium_clause_forms() {
        let s = Select::parse(&[
            "highway=speed_camera,stop,traffic_signals",
            "man_made=surveillance",
            "maxspeed",
        ])
        .unwrap();
        assert_eq!(s.clauses.len(), 3);
        assert_eq!(s.clauses[0].key, "highway");
        assert_eq!(s.clauses[0].values, ["speed_camera", "stop", "traffic_signals"]);
        // A bare key means "present, any value".
        assert_eq!(s.clauses[2].key, "maxspeed");
        assert!(s.clauses[2].values.is_empty());
    }

    #[test]
    fn tolerates_whitespace_and_trailing_commas() {
        let s = Select::parse(&[" highway = rail, subway , "]).unwrap();
        assert_eq!(s.clauses[0].key, "highway");
        assert_eq!(s.clauses[0].values, ["rail", "subway"]);
    }

    #[test]
    fn rejects_nonsense() {
        assert!(Select::parse(&[]).is_err());
        assert!(Select::parse(&[""]).is_err());
        assert!(Select::parse(&["=value"]).is_err());
        assert!(Select::parse(&["key="]).is_err());
        assert!(Select::parse(&["key=,,"]).is_err());
    }

    #[test]
    fn clauses_are_ored_not_anded() {
        let s = Select::parse(&["highway=speed_camera", "man_made=surveillance"]).unwrap();
        assert!(s.matches(tags(&[("highway", "speed_camera")])));
        assert!(s.matches(tags(&[("man_made", "surveillance")])));
        // Either alone is enough; neither is not.
        assert!(!s.matches(tags(&[("highway", "residential")])));
        assert!(!s.matches(tags(&[])));
    }

    #[test]
    fn a_bare_key_matches_any_value_including_empty() {
        let s = Select::parse(&["maxspeed"]).unwrap();
        assert!(s.matches(tags(&[("maxspeed", "50")])));
        assert!(s.matches(tags(&[("maxspeed", "none")])));
        // Present but empty still counts as present, as in osmium.
        assert!(s.matches(tags(&[("maxspeed", "")])));
        assert!(!s.matches(tags(&[("highway", "residential")])));
    }

    #[test]
    fn value_matching_is_exact_not_substring() {
        let s = Select::parse(&["railway=rail"]).unwrap();
        assert!(s.matches(tags(&[("railway", "rail")])));
        // `narrow_gauge` contains no "rail" substring issue, but `light_rail`
        // does -- and must not match a `railway=rail` clause.
        assert!(!s.matches(tags(&[("railway", "light_rail")])));
        assert!(!s.matches(tags(&[("railway", "railway")])));
    }

    #[test]
    fn keys_are_deduplicated_and_sorted() {
        let s = Select::parse(&["highway=stop", "man_made=surveillance", "highway=speed_camera"])
            .unwrap();
        assert_eq!(s.keys(), ["highway", "man_made"]);
    }
}
