//! The thread budget, and the pool every parallel stage in this crate runs on.
//!
//! Sized the same way `osm_ingest::par` sizes its pool, and deliberately so: the
//! build scripts fan out several of these tools at once, and
//! `available_parallelism()` reports the whole box to every one of them. Six
//! concurrent layer builds each claiming 32 threads on a 32-core machine finish
//! slower than running in sequence, so the cap has to be settable from outside
//! the process. Hence `--threads` and `MAPS_THREADS`, spelled identically in both
//! crates so one `MAPS_THREADS` export governs a whole build.
//!
//! Parallelism here never changes an output byte. Every stage either collects
//! results into a slot indexed by input position, or writes them in a
//! sequentially-folded second pass; see [`crate::pyramid::build_archive_to`].

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

/// 0 means "not set"; any other value is an explicit cap from `--threads`.
static OVERRIDE: AtomicUsize = AtomicUsize::new(0);

/// Cap the pool for the rest of the process. Called from `main` after argument
/// parsing.
pub fn set_threads(n: usize) {
    OVERRIDE.store(n.max(1), Ordering::Relaxed);
}

/// Drop the override again, so a test can leave the process as it found it.
#[cfg(test)]
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

/// The most workers a phase that dislikes them should use — **not currently applied**.
///
/// Left as a documented dead end rather than deleted, because the measurement is real and the next
/// person to look at thread counts should start from it rather than repeat it.
///
/// Tiling one us-west z13 archive four times from the same feature spill, back to back in one command
/// so the machine state is shared, on a 64-thread box:
///
/// | threads | map  | merge | encode | append |
/// |---------|------|-------|--------|--------|
/// | 64      | 87.5 | 9.4   | 42.0   | 5.2    |
/// | 32      | 80.1 | 6.6   | 26.3   | 2.6    |
/// | 16      | 76.5 | 5.4   | 29.0   | 2.0    |
/// | 8       | 74.7 | 4.4   | 29.3   | 1.5    |
///
/// Encode looks 37% faster at 32 than at 64, every phase looks better with fewer workers, and all four
/// archives were byte-identical.
///
/// # Why it is not applied
///
/// Neither a global cap nor an encode-only pool survived a full z14 build. This box produced 610.1,
/// 631.0, 563.4 and 786.0 second us-west z14 builds that all wrote the **same archive bytes** — ±20%
/// of wall clock, several times the effect being chased, because other work shares the machine. The
/// z13 table above is internally consistent only because its four runs were consecutive; comparing it
/// against a z14 run measured an hour later compares machine load, not code.
///
/// Validating this needs an A/B where both arms run back to back on an otherwise idle box, ideally
/// interleaved and repeated. `--threads` and `MAPS_THREADS` are how to do that; see also
/// [`min_task_len`], which documents the same anti-scaling from the scheduling side and *is* applied,
/// because a synthetic benchmark could isolate it.
#[allow(dead_code)]
const NARROW_THREADS_UNVALIDATED: usize = 32;

/// A malformed or non-positive `MAPS_THREADS` is ignored rather than fatal: it is
/// usually exported once for a whole script run, and killing an eight-hour planet
/// build over a typo in an environment variable serves nobody.
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

/// Our own pool rather than rayon's global one, so the cap applies even when
/// something else in the process has already initialised the global.
///
/// Cached, and rebuilt only when the requested count changes. In production that
/// happens once, because `--threads` is read before any tiling starts; the reason
/// it is not a `OnceLock` is that the byte-identity tests build the same archive at
/// 1, 2, 3 and 32 threads in one process, and a pool fixed on first use would make
/// that test assert nothing.
///
/// If rayon refuses to build a pool — which in practice means the OS would not give
/// us threads — fall back to a single-threaded one rather than failing the build:
/// every caller is a `map` whose serial execution is correct, just slower.
fn pool() -> Arc<rayon::ThreadPool> {
    static POOL: Mutex<Option<(usize, Arc<rayon::ThreadPool>)>> = Mutex::new(None);
    let want = threads();
    let mut cached = POOL.lock().expect("thread pool mutex");
    if let Some((have, p)) = cached.as_ref() {
        if *have == want {
            return Arc::clone(p);
        }
    }
    let built = Arc::new(build_pool(want).unwrap_or_else(|e| {
        eprintln!("WARNING: cannot start a {want}-thread pool ({e}); continuing on one thread");
        build_pool(1).expect("a one-thread pool")
    }));
    *cached = Some((want, Arc::clone(&built)));
    built
}

fn build_pool(n: usize) -> std::result::Result<rayon::ThreadPool, rayon::ThreadPoolBuildError> {
    rayon::ThreadPoolBuilder::new()
        .num_threads(n)
        // As big as the main thread's, because that is where this work used to run.
        // rayon workers otherwise get std's 2 MiB default, so parallelising a deep
        // call path silently shrinks its stack by 4x -- and the failure mode is an
        // abort partway through a planet build, not an error. 8 MiB matches what the
        // Rust toolchain gives `main` on Windows.
        .stack_size(8 * 1024 * 1024)
        .thread_name(|i| format!("tile_build-{i}"))
        .build()
}

/// Run `f` on the pool, so it sees our thread cap rather than rayon's global one.
pub fn install<R: Send>(f: impl FnOnce() -> R + Send) -> R {
    pool().install(f)
}

/// How many items to hand the pool at once.
///
/// The tilers map over items whose results are held until the batch is written, so
/// the batch size is what bounds that memory. It has to be large enough to keep
/// every core fed — a batch of `threads` items means one task each and a barrier
/// after every one — while still holding only a bounded number of results. Encoded
/// tile bodies and spill records are kilobytes each, so a few thousand is a few MB:
/// measured peak RSS moves by ~12% between 1 and 32 threads, not by 32x.
pub fn batch_len() -> usize {
    threads().saturating_mul(64).max(512)
}

/// The smallest number of items worth handing one worker, for a map of `items`.
///
/// One tile's encode is tens of microseconds. Handed to rayon one at a time, the
/// work-stealing and spinning cost more than the work: measured on 200k synthetic
/// roads at z14, the encode pass went 4.15s at one thread to 1.81s at four and then
/// back UP to 3.51s at 32, purely on scheduling overhead. Batching several items into
/// each task amortises that away.
///
/// But it MUST scale with the input, and a fixed floor was a serious bug. A constant
/// 16 meant rayon refused to split any map of fewer than 32 items — and the tile
/// encode pass maps over one BUCKET's tile groups, of which California z11 has only
/// ~7 (1,899 tiles over 256 buckets). The whole pass therefore ran on one thread:
/// 86.7s of wall clock for 92s of CPU, an effective parallelism of 1.06 on a 64-core
/// box, while z16 with ~3,500 groups per bucket parallelised fine and hid it.
///
/// So: aim for a few tasks per thread, and never fewer tasks than there are items.
pub fn min_task_len(items: usize) -> usize {
    let want_tasks = threads().saturating_mul(4).max(1);
    (items / want_tasks).max(1)
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

    /// A batch has to hold enough tasks to keep the pool busy, and a small map must
    /// still be split at all — the bug this guards is a floor so high that a map of
    /// one bucket's ~7 tile groups became a single task on a 64-core box.
    #[test]
    fn a_small_map_is_still_split_across_the_pool() {
        for n in [1usize, 2, 3, 32, 64] {
            set_threads(n);
            assert_eq!(min_task_len(7), 1, "{n} threads: 7 items must give 7 tasks");
            assert_eq!(min_task_len(0), 1, "an empty map must not divide by zero");
            let big = 100_000;
            let tasks = big / min_task_len(big);
            assert!(
                tasks >= n * 2,
                "{n} threads: {tasks} task(s) for {big} items is not enough to fill \
                 the pool"
            );
            let tasks_small = 7 / min_task_len(7);
            assert!(tasks_small >= 1.min(n));
        }
        clear_threads();
    }
}
