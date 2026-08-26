//! The thread budget.
//!
//! Spelled the same way `osm_ingest::par` and `tile_build::par` spell it, so one
//! `MAPS_THREADS` export governs a whole build and the fan-out in `build_all.sh`
//! does not oversubscribe the box. `available_parallelism()` reports the whole
//! machine to every process, so six concurrent tools each claiming 32 threads on a
//! 32-core box finish slower than running in sequence.
//!
//! Unlike the other two crates this one has **no rayon**, and deliberately: this
//! crate's documented property is zero external dependencies, and its parallel work
//! is a handful of whole GTFS feeds per batch rather than millions of tiny items.
//! One `std::thread::scope` per batch is all that shape needs; work stealing would
//! buy nothing.

use std::sync::atomic::{AtomicUsize, Ordering};

/// 0 means "not set"; any other value is an explicit cap from `--threads`.
static OVERRIDE: AtomicUsize = AtomicUsize::new(0);

/// Cap the pool for the rest of the process. Called from `main` after parsing.
pub fn set_threads(n: usize) {
    OVERRIDE.store(n.max(1), Ordering::Relaxed);
}

/// Drop the override again, restoring the `MAPS_THREADS`-or-the-box default.
///
/// Not `#[cfg(test)]`: the binaries in this crate are separate compilation units, so
/// a test in `transit_stops` cannot see the library's test-only items.
pub fn clear_threads() {
    OVERRIDE.store(0, Ordering::Relaxed);
}

/// Threads to use. `--threads` beats `MAPS_THREADS` beats the box.
pub fn threads() -> usize {
    resolve(
        OVERRIDE.load(Ordering::Relaxed),
        std::env::var("MAPS_THREADS").ok().as_deref(),
    )
}

/// The precedence rule, split out so it can be tested without mutating the
/// process-wide override or the environment.
fn resolve(override_n: usize, env: Option<&str>) -> usize {
    if override_n > 0 {
        return override_n;
    }
    if let Some(n) = env_threads(env) {
        return n;
    }
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
}

/// A malformed or non-positive `MAPS_THREADS` is ignored rather than fatal: it is
/// usually exported once for a whole script run, and killing an eight-hour build
/// over a typo in an environment variable serves nobody.
fn env_threads(raw: Option<&str>) -> Option<usize> {
    static WARNED: std::sync::Once = std::sync::Once::new();
    let trimmed = raw?.trim();
    match trimmed.parse::<usize>() {
        Ok(n) if n > 0 => Some(n),
        _ => {
            WARNED.call_once(|| {
                eprintln!("WARNING: ignoring MAPS_THREADS='{trimmed}' (wants a positive count)");
            });
            None
        }
    }
}

/// Parse a `--threads N` value.
pub fn parse_threads(value: &str) -> std::result::Result<usize, String> {
    value
        .parse::<usize>()
        .ok()
        .filter(|n| *n > 0)
        .ok_or_else(|| format!("--threads wants a positive count, not {value}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_threads_rejects_zero_and_junk() {
        assert_eq!(parse_threads("8"), Ok(8));
        assert!(parse_threads("0").is_err());
        assert!(parse_threads("-1").is_err());
        assert!(parse_threads("many").is_err());
        assert!(parse_threads("").is_err());
    }

    #[test]
    fn the_flag_beats_the_env_beats_the_box() {
        let box_threads = resolve(0, None);
        assert_eq!(resolve(6, Some("2")), 6, "--threads wins");
        assert_eq!(resolve(0, Some("2")), 2, "MAPS_THREADS wins over the box");
        assert_eq!(resolve(6, None), 6);
        for junk in ["0", "many", "", "-4"] {
            assert_eq!(resolve(0, Some(junk)), box_threads, "MAPS_THREADS={junk}");
        }
        assert_eq!(resolve(0, Some(" 3 ")), 3, "surrounding space is tolerated");
    }
}
