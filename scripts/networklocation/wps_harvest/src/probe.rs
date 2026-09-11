//! Random BSSID probing, for when the frontier has nothing in it.
//!
//! The crawl normally expands because every answer contains neighbours. That only works once
//! it knows a single real BSSID, and there is no open bulk dump to get one from — beaconDB's
//! dumps are announced but unpublished, and Mozilla Location Services was never able to
//! release its WiFi data at all. So the cold start is: guess addresses until one hits.
//!
//! ## Why guessing works at all
//!
//! A MAC is 48 bits, which sounds hopeless, but the top 24 are an IEEE-assigned OUI and only
//! about 37 000 of the 16.7 million possible OUIs are assigned to anyone. Drawing from
//! *assigned* OUI space is therefore some 450x better than drawing uniformly:
//!
//! | Drawing from | Candidate space | Rough hit rate |
//! | --- | --- | --- |
//! | Full 48-bit | 2.8e14 | ~1 in 140 000 |
//! | Assigned OUIs | ~6.2e11 | ~1 in 300 |
//! | OUIs already seen in responses | far smaller, and weighted by real deployment | better still |
//!
//! (Taking Apple's database to hold on the order of a billion access points.)
//!
//! So this learns. Every BSSID that comes back in any response contributes its OUI to a
//! reservoir, and probes are drawn from that reservoir with repeats — which weights them by
//! how common each vendor actually is in the wild, for free. A small fraction of probes go to
//! a uniformly random OUI instead, so the crawl can still discover vendors it has never seen.
//!
//! One real BSSID from anywhere — a local WiFi scan will do — skips the cold start entirely
//! and is worth far more than any amount of guessing.

use std::collections::HashMap;

/// Reservoir cap. Enough to represent the vendor distribution, bounded so a long crawl cannot
/// grow it without limit.
const MAX_RESERVOIR: usize = 1 << 20;

/// Fraction of probes (in 1/256ths) drawn from a uniformly random OUI rather than a learned
/// one, so an unseen vendor can still be discovered.
const EXPLORE_256: u64 = 13; // ~5%

/// Generates candidate BSSIDs, learning which OUIs are worth guessing.
pub struct Prober {
    /// Observed OUIs, with repeats, so a uniform draw is frequency-weighted.
    reservoir: Vec<u32>,
    /// Distinct OUIs seen, for reporting.
    distinct: HashMap<u32, u32>,
    state: u64,
    /// Candidates emitted.
    pub probes: u64,
    /// Candidates that came back with a location.
    pub hits: u64,
}

impl Prober {
    /// Seed the RNG deterministically, so a run can be reproduced.
    pub fn new(seed: u64) -> Prober {
        Prober {
            reservoir: Vec::new(),
            distinct: HashMap::new(),
            state: seed,
            probes: 0,
            hits: 0,
        }
    }

    fn next_u64(&mut self) -> u64 {
        // splitmix64
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Record that `mac` exists, so its vendor is worth guessing again. Returns whether it was
    /// accepted.
    ///
    /// Locally-administered and multicast OUIs are refused rather than stored: no real access
    /// point uses one, and keeping it would mean either probing addresses that can never hit
    /// or quietly rewriting it into some other vendor's range at draw time.
    pub fn observe(&mut self, mac: u64) -> bool {
        let oui = (mac >> 24) as u32 & 0xFF_FFFF;
        if oui & 0x03_0000 != 0 {
            return false;
        }
        *self.distinct.entry(oui).or_insert(0) += 1;
        if self.reservoir.len() < MAX_RESERVOIR {
            self.reservoir.push(oui);
        } else {
            // Replace a random slot: keeps the reservoir bounded while still tracking the
            // distribution rather than freezing whatever was seen first.
            let i = (self.next_u64() as usize) % self.reservoir.len();
            self.reservoir[i] = oui;
        }
        true
    }

    /// Load OUIs from a supplied list, e.g. the IEEE registry or a local WiFi scan. Returns
    /// how many were **accepted**, which can be fewer than the lines that parsed.
    ///
    /// Accepts anything containing `aa:bb:cc` or `aa-bb-cc` or `AABBCC` prefixes, one per
    /// line, and ignores the rest of each line — so an IEEE `oui.csv` or the output of a scan
    /// both work without preprocessing.
    pub fn load_ouis(&mut self, text: &str) -> usize {
        let mut added = 0;
        for line in text.lines() {
            if let Some(oui) = parse_oui(line) {
                if self.observe((oui as u64) << 24) {
                    added += 1;
                }
            }
        }
        added
    }

    /// Distinct OUIs known.
    pub fn known_ouis(&self) -> usize {
        self.distinct.len()
    }

    /// Hit rate so far, as a fraction of probes.
    pub fn hit_rate(&self) -> f64 {
        if self.probes == 0 {
            0.0
        } else {
            self.hits as f64 / self.probes as f64
        }
    }

    /// Next candidate BSSID.
    ///
    /// Never returns a locally-administered or multicast address: those are randomized or
    /// virtual, the store builder drops them, and Apple will not know them, so probing one is
    /// a wasted request. Learned OUIs are already clean (see [`Prober::observe`]); only the
    /// random exploration path needs masking.
    pub fn next_candidate(&mut self) -> u64 {
        self.probes += 1;
        let explore = (self.next_u64() & 0xFF) < EXPLORE_256;
        let oui = if self.reservoir.is_empty() || explore {
            ((self.next_u64() as u32) & 0xFF_FFFF) & !0x03_0000
        } else {
            let i = (self.next_u64() as usize) % self.reservoir.len();
            self.reservoir[i]
        };
        let suffix = self.next_u64() & 0xFF_FFFF;
        ((oui as u64) << 24) | suffix
    }

    /// Emit `n` candidates.
    pub fn candidates(&mut self, n: usize) -> Vec<u64> {
        (0..n).map(|_| self.next_candidate()).collect()
    }
}

/// Pull a 24-bit OUI off the front of a line, in any of the usual spellings.
fn parse_oui(line: &str) -> Option<u32> {
    let t = line.trim();
    if t.is_empty() || t.starts_with('#') {
        return None;
    }
    // Take the first token that looks like a MAC or OUI.
    for tok in t.split(|c: char| c.is_whitespace() || c == ',' || c == ';' || c == '"') {
        let hex: String = tok.chars().filter(|c| c.is_ascii_hexdigit()).collect();
        let sep_ok = tok.chars().all(|c| c.is_ascii_hexdigit() || c == ':' || c == '-');
        if sep_ok && hex.len() >= 6 {
            if let Ok(v) = u32::from_str_radix(&hex[..6], 16) {
                return Some(v);
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys;

    #[test]
    fn candidates_are_always_usable_addresses() {
        let mut p = Prober::new(1);
        for _ in 0..10_000 {
            let mac = p.next_candidate();
            assert!(mac < (1u64 << 48), "escaped 48 bits: {mac:#x}");
            assert!(
                !keys::is_randomized_mac(mac),
                "generated a locally-administered or multicast address: {mac:#012x}"
            );
        }
        assert_eq!(p.probes, 10_000);
    }

    #[test]
    fn probing_is_deterministic_for_a_given_seed() {
        let a: Vec<u64> = Prober::new(42).candidates(100);
        let b: Vec<u64> = Prober::new(42).candidates(100);
        assert_eq!(a, b);
        assert_ne!(a, Prober::new(43).candidates(100));
    }

    #[test]
    fn learned_ouis_dominate_the_draw() {
        let mut p = Prober::new(7);
        // One vendor, seen many times. b8:fb:b3 is globally administered.
        for _ in 0..1000 {
            let _ = p.observe(0xB8_FB_B3_00_00_00);
        }
        let hits = (0..2000).filter(|_| (p.next_candidate() >> 24) == 0xB8FBB3).count();
        // Everything except the exploration fraction should land on the known OUI.
        assert!(hits > 1700, "only {hits}/2000 probes used the learned OUI");
        assert!(hits < 2000, "exploration never fired; unseen vendors would be unreachable");
    }

    #[test]
    fn frequency_weighting_follows_the_observed_distribution() {
        let mut p = Prober::new(11);
        // Both globally administered: 0xAA would have the locally-administered bit set and be
        // refused, which is the point of the next test.
        for _ in 0..900 {
            let _ = p.observe(0xA8_00_00_00_00_00);
        }
        for _ in 0..100 {
            let _ = p.observe(0xCC_00_00_00_00_00);
        }
        let mut common = 0;
        let mut rare = 0;
        for _ in 0..5000 {
            match p.next_candidate() >> 24 {
                0xA80000 => common += 1,
                0xCC0000 => rare += 1,
                _ => {}
            }
        }
        assert!(common > rare * 4, "weighting is not tracking frequency: {common} vs {rare}");
    }

    #[test]
    fn locally_administered_ouis_are_refused_not_rewritten() {
        let mut p = Prober::new(13);
        // 0xAA has the locally-administered bit set; 0x01 is multicast.
        assert!(!p.observe(0xAA_00_00_00_00_00));
        assert!(!p.observe(0x01_00_00_00_00_00));
        assert_eq!(p.known_ouis(), 0, "neither is a real access point vendor");

        // And having refused them, it must not silently probe a neighbouring vendor instead.
        assert!(p.observe(0xA8_00_00_00_00_00));
        assert_eq!(p.known_ouis(), 1);
        for _ in 0..500 {
            let oui = p.next_candidate() >> 24;
            assert!(oui == 0xA80000 || p.reservoir.len() == 1, "unexpected OUI {oui:#08x}");
        }
    }

    #[test]
    fn with_nothing_learned_it_still_produces_candidates() {
        let mut p = Prober::new(3);
        assert_eq!(p.known_ouis(), 0);
        let c = p.candidates(50);
        assert_eq!(c.len(), 50);
        assert_eq!(c.iter().collect::<std::collections::HashSet<_>>().len(), 50, "all distinct");
    }

    #[test]
    fn the_reservoir_stays_bounded() {
        let mut p = Prober::new(5);
        for i in 0..(MAX_RESERVOIR as u64 + 5000) {
            // Keep the multicast/local bits clear so every one is accepted.
            let _ = p.observe((i << 24) & !0x03_0000_0000_0000);
        }
        assert!(p.reservoir.len() <= MAX_RESERVOIR);
    }

    #[test]
    fn oui_lists_parse_in_the_shapes_people_have_them() {
        let mut p = Prober::new(9);
        let added = p.load_ouis(
            "# comment\n\
             b8:fb:b3:86:18:8e\n\
             A8-BB-CC\n\
             001122\n\
             MA-L,102233,Some Vendor Inc,Address\n\
             11:22:33\n\
             \n\
             not a mac at all\n",
        );
        // Six lines parse; 11:22:33 is multicast and is refused.
        assert_eq!(added, 4, "expected four accepted OUIs");
        assert_eq!(p.known_ouis(), 4);
    }

    #[test]
    fn oui_parsing_rejects_noise() {
        assert_eq!(parse_oui(""), None);
        assert_eq!(parse_oui("# b8:fb:b3"), None);
        assert_eq!(parse_oui("hello world"), None);
        assert_eq!(parse_oui("12345"), None, "too short to be an OUI");
        assert_eq!(parse_oui("b8:fb:b3"), Some(0xB8FBB3));
        assert_eq!(parse_oui("B8FBB386188E"), Some(0xB8FBB3));
    }

    #[test]
    fn hit_rate_reports_what_actually_landed() {
        let mut p = Prober::new(2);
        let _ = p.candidates(200);
        p.hits = 4;
        assert!((p.hit_rate() - 0.02).abs() < 1e-9);
        assert_eq!(Prober::new(2).hit_rate(), 0.0, "no probes means no rate, not a division");
    }
}
