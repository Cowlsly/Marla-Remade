//! `wikidesc` — a place-description archive, keyed by OSM id.
//!
//! One paragraph of plain text per notable place, so tapping a city, a station or a landmark
//! offline shows what it is rather than only its name.
//!
//! # What counts as notable
//!
//! **Anything carrying an English `wikipedia` tag, whatever it is.** Not a list of kinds.
//!
//! The instinct is to enumerate — admin places, then stations, then parks, then monuments — and it
//! is the wrong shape. The list is never finished, every addition needs a rebuild to take effect,
//! and it answers the wrong question: not "is this a park" but "does anyone consider this worth
//! writing about". OSM already carries that answer. A mapper adds `wikipedia=en:…` when an article
//! exists, so the tag *is* the notability filter, maintained by people who know the place.
//!
//! It sorts the hard cases by itself. Grand Central Terminal has the tag; the bus stop outside
//! does not. Central Park has it; a housing estate's lawn does not. The French Laundry has it and
//! the restaurant next door does not — and that is correct, because the point was never to exclude
//! restaurants, it was to exclude things with nothing to say.
//!
//! It is also the size control. Roughly 1.5 M OSM elements carry a `wikipedia` tag, against some
//! 8 M carrying `wikidata` — the latter is largely bot-added and mostly has no English article
//! behind it. Gating on `wikipedia` picks the set that can actually be filled in.
//!
//! # The key
//!
//! The OSM element id, which `.mamaps` already carries per feature in its v3 feature-id side table
//! and surfaces to the app as `PlacedLabel.featureId`. So this needs no change to the basemap
//! format.
//!
//! See [`clean`] for what a Wikipedia lead is reduced to, and why so aggressively.

pub mod abstracts;
pub mod archive;
pub mod clean;
pub mod ids;
pub mod join;

pub use abstracts::{english_title, Abstract, Abstracts};
pub use archive::{Archive, Builder};
pub use clean::clean;
pub use ids::tagged_id;
pub use join::{join, Wanted};
