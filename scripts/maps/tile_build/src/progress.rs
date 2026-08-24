//! A one-line progress bar for the long tiling loops.
//!
//! Deliberately the same shape `osm_ingest`'s passes print (`label [ 42%]`, carriage
//! return, no newline until the end) so a build log reads consistently across the two
//! crates. A planet layer spends minutes to hours per zoom and every tiler was
//! previously silent for all of it.
//!
//! Single-threaded, so no atomics: the tiling loops are one thread each.
use std::io::Write;

/// Progress over one zoom's tile loop.
pub struct Progress {
    label: String,
    total: usize,
    done: usize,
    last_pct: usize,
    on: bool,
}

impl Progress {
    /// `unit` names what is being counted, because the number is not always the tile
    /// count the report ends up printing — see [`Progress::new`]'s callers.
    pub fn new(label: String, total: usize, unit: &str, on: bool) -> Progress {
        let p = Progress {
            label,
            total,
            done: 0,
            last_pct: usize::MAX,
            on,
        };
        if p.on {
            print!("\r{:<28} [  0%] {} {unit}", p.label, p.total);
            let _ = std::io::stdout().flush();
        }
        p
    }

    /// One unit done. Throttled to whole percent: a planet zoom has millions of tiles
    /// and a write per tile would cost more than the tiling.
    pub fn tick(&mut self, unit: &str) {
        self.done += 1;
        if !self.on || self.total == 0 {
            return;
        }
        let pct = self.done * 100 / self.total;
        if pct != self.last_pct {
            self.last_pct = pct;
            print!("\r{:<28} [{pct:>3}%] {} {unit}", self.label, self.total);
            let _ = std::io::stdout().flush();
        }
    }

    /// Leave the finished line on screen, so a multi-zoom build shows every zoom.
    pub fn finish(&self, unit: &str) {
        if self.on {
            println!("\r{:<28} [100%] {} {unit}", self.label, self.total);
        }
    }
}
