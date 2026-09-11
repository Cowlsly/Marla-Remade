//! Crawl progress accounting and the status line.
//!
//! Split out from the crawler so the rate arithmetic and formatting can be tested without
//! making a single request.
//!
//! Two things this gets right that a naive counter does not:
//!
//! * **Rate is reported over a recent window as well as for the whole run.** A crawl that has
//!   been backing off for ten minutes still has a healthy-looking lifetime average, which is
//!   exactly when you want to know it has stalled.
//! * **Status is emitted on a timer, not every N queries.** Tying output to query count means
//!   a crawl that has stopped making progress also stops telling you anything.

use std::time::{Duration, Instant};

/// Why a request failed, counted separately because they mean different things: a 400 is the
/// protocol being wrong, a transport error is the network, and a malformed body is the service
/// answering with something unexpected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailKind {
    /// HTTP 400 — almost always a malformed request, i.e. the protocol has drifted.
    BadRequest,
    /// Any other HTTP status.
    Status,
    /// Connection refused, timed out, DNS, TLS.
    Transport,
    /// A 200 whose body could not be read as a response.
    Malformed,
}

/// Running totals for one crawl.
pub struct Progress {
    started: Instant,
    last_print: Instant,
    interval: Duration,
    window_started: Instant,
    window_queries: u64,

    /// Successful requests.
    pub queries: u64,
    /// Requests made, including failures.
    pub attempts: u64,
    /// BSSIDs seen for the first time.
    pub discovered: u64,
    /// Observations written to the shard.
    pub observations: u64,
    /// Failed requests.
    pub failures: u64,
    /// HTTP 400s specifically.
    pub bad_requests: u64,
    /// Other HTTP statuses.
    pub bad_status: u64,
    /// Transport errors.
    pub transport_errors: u64,
    /// Unreadable response bodies.
    pub malformed: u64,
}

impl Progress {
    /// Start accounting, printing a status line no more often than every `interval`.
    pub fn new(interval: Duration) -> Progress {
        let now = Instant::now();
        Progress {
            started: now,
            last_print: now,
            interval,
            window_started: now,
            window_queries: 0,
            queries: 0,
            attempts: 0,
            discovered: 0,
            observations: 0,
            failures: 0,
            bad_requests: 0,
            bad_status: 0,
            transport_errors: 0,
            malformed: 0,
        }
    }

    /// Record a successful request.
    pub fn success(&mut self, observations: u64, discovered: u64) {
        self.attempts += 1;
        self.queries += 1;
        self.window_queries += 1;
        self.observations += observations;
        self.discovered += discovered;
    }

    /// Record a failed request.
    pub fn failure(&mut self, kind: FailKind) {
        self.attempts += 1;
        self.failures += 1;
        match kind {
            FailKind::BadRequest => self.bad_requests += 1,
            FailKind::Status => self.bad_status += 1,
            FailKind::Transport => self.transport_errors += 1,
            FailKind::Malformed => self.malformed += 1,
        }
    }

    /// Whether enough time has passed to print again. Resets the timer when it says yes.
    pub fn should_print(&mut self) -> bool {
        if self.last_print.elapsed() >= self.interval {
            self.last_print = Instant::now();
            true
        } else {
            false
        }
    }

    /// Requests per second over the whole run.
    pub fn overall_rate(&self) -> f64 {
        rate(self.queries, self.started.elapsed())
    }

    /// Requests per second since the last window reset.
    pub fn window_rate(&self) -> f64 {
        rate(self.window_queries, self.window_started.elapsed())
    }

    /// Begin a new rate window. Called after printing, so each line's window rate covers the
    /// period since the previous line.
    pub fn reset_window(&mut self) {
        self.window_started = Instant::now();
        self.window_queries = 0;
    }

    /// How long the crawl has been running.
    pub fn elapsed(&self) -> Duration {
        self.started.elapsed()
    }

    /// New BSSIDs per successful query. Falling towards zero means the crawl is re-treading
    /// ground it has already covered.
    pub fn discovery_per_query(&self) -> f64 {
        if self.queries == 0 {
            0.0
        } else {
            self.discovered as f64 / self.queries as f64
        }
    }

    /// The periodic status line.
    pub fn line(&self, pending: u64, fp_rate: f64) -> String {
        let mut s = format!(
            "[{}] {} q ({:.2}/s, now {:.2}/s) | new {} ({:.0}/q) | pending {} | obs {} | fail {}",
            hms(self.elapsed()),
            count(self.queries),
            self.overall_rate(),
            self.window_rate(),
            count(self.discovered),
            self.discovery_per_query(),
            count(pending),
            count(self.observations),
            count(self.failures),
        );
        if self.failures > 0 {
            s.push_str(&format!(
                " ({}x400 {}xhttp {}xnet {}xbad)",
                self.bad_requests, self.bad_status, self.transport_errors, self.malformed
            ));
        }
        s.push_str(&format!(" | seen fp {:.2}%", fp_rate * 100.0));
        s
    }

    /// The closing summary.
    pub fn summary(&self, pending: u64) -> String {
        format!(
            "Stopped after {} in {}: {} quer(ies) of {} attempt(s) at {:.2}/s, \
             {} new BSSID(s), {} observation(s) written, {} still pending",
            count(self.attempts),
            hms(self.elapsed()),
            count(self.queries),
            count(self.attempts),
            self.overall_rate(),
            count(self.discovered),
            count(self.observations),
            count(pending),
        )
    }
}

fn rate(n: u64, over: Duration) -> f64 {
    let secs = over.as_secs_f64();
    if secs <= 0.0 {
        0.0
    } else {
        n as f64 / secs
    }
}

/// `HH:MM:SS`, growing past 24 hours rather than wrapping — a crawl runs for weeks.
pub fn hms(d: Duration) -> String {
    let t = d.as_secs();
    format!("{:02}:{:02}:{:02}", t / 3600, (t % 3600) / 60, t % 60)
}

/// Short human count: `1.9M`, `312k`, `847`.
pub fn count(n: u64) -> String {
    match n {
        0..=9_999 => n.to_string(),
        10_000..=999_999 => format!("{:.0}k", n as f64 / 1e3),
        1_000_000..=999_999_999 => format!("{:.2}M", n as f64 / 1e6),
        _ => format!("{:.2}B", n as f64 / 1e9),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn durations_format_and_do_not_wrap_at_a_day() {
        assert_eq!(hms(Duration::from_secs(0)), "00:00:00");
        assert_eq!(hms(Duration::from_secs(59)), "00:00:59");
        assert_eq!(hms(Duration::from_secs(3661)), "01:01:01");
        // A week-long crawl must read as 170 hours, not as day 7 hour 2.
        assert_eq!(hms(Duration::from_secs(612_000)), "170:00:00");
    }

    #[test]
    fn counts_stay_readable_at_every_scale() {
        assert_eq!(count(0), "0");
        assert_eq!(count(847), "847");
        assert_eq!(count(9_999), "9999");
        assert_eq!(count(312_400), "312k");
        assert_eq!(count(1_894_039), "1.89M");
        assert_eq!(count(287_460_836), "287.46M");
        assert_eq!(count(2_000_000_000), "2.00B");
    }

    #[test]
    fn failures_are_counted_by_kind() {
        let mut p = Progress::new(Duration::from_secs(30));
        p.failure(FailKind::BadRequest);
        p.failure(FailKind::BadRequest);
        p.failure(FailKind::Transport);
        p.failure(FailKind::Malformed);
        p.failure(FailKind::Status);
        assert_eq!(p.failures, 5);
        assert_eq!(p.bad_requests, 2);
        assert_eq!(p.transport_errors, 1);
        assert_eq!(p.malformed, 1);
        assert_eq!(p.bad_status, 1);
        assert_eq!(p.attempts, 5, "failures are attempts too");
        assert_eq!(p.queries, 0, "but not successes");
    }

    #[test]
    fn success_accumulates_observations_and_discoveries() {
        let mut p = Progress::new(Duration::from_secs(30));
        p.success(108, 108);
        p.success(104, 2);
        assert_eq!(p.queries, 2);
        assert_eq!(p.observations, 212);
        assert_eq!(p.discovered, 110);
        assert_eq!(p.discovery_per_query(), 55.0);
    }

    #[test]
    fn discovery_per_query_is_zero_before_any_query() {
        assert_eq!(Progress::new(Duration::from_secs(1)).discovery_per_query(), 0.0);
    }

    #[test]
    fn rates_are_zero_rather_than_infinite_at_the_start() {
        let p = Progress::new(Duration::from_secs(30));
        assert_eq!(p.overall_rate(), 0.0);
        assert_eq!(p.window_rate(), 0.0);
        assert_eq!(rate(5, Duration::ZERO), 0.0, "must not divide by zero");
    }

    #[test]
    fn the_window_rate_tracks_recent_work_not_the_whole_run() {
        let mut p = Progress::new(Duration::from_secs(30));
        for _ in 0..10 {
            p.success(1, 1);
        }
        std::thread::sleep(Duration::from_millis(60));
        // Both see the same 10 queries so far.
        assert!(p.window_rate() > 0.0);

        p.reset_window();
        std::thread::sleep(Duration::from_millis(60));
        // Nothing since the reset: the window has gone quiet even though the lifetime
        // average is still healthy. This is the stall signal the lifetime rate hides.
        assert_eq!(p.window_rate(), 0.0);
        assert!(p.overall_rate() > 0.0);
    }

    #[test]
    fn printing_is_rate_limited_by_time() {
        let mut p = Progress::new(Duration::from_millis(80));
        assert!(!p.should_print(), "must not fire immediately");
        std::thread::sleep(Duration::from_millis(100));
        assert!(p.should_print());
        assert!(!p.should_print(), "the timer resets when it fires");
    }

    #[test]
    fn the_status_line_reports_what_matters() {
        let mut p = Progress::new(Duration::from_secs(30));
        p.success(108, 108);
        let line = p.line(1_894_039, 0.0123);
        assert!(line.contains("1 q"), "{line}");
        assert!(line.contains("new 108"), "{line}");
        assert!(line.contains("pending 1.89M"), "{line}");
        assert!(line.contains("obs 108"), "{line}");
        assert!(line.contains("fail 0"), "{line}");
        assert!(line.contains("1.23%"), "{line}");
        assert!(!line.contains("x400"), "the breakdown is noise when nothing failed: {line}");
    }

    #[test]
    fn the_status_line_breaks_down_failures_once_there_are_any() {
        let mut p = Progress::new(Duration::from_secs(30));
        p.failure(FailKind::BadRequest);
        let line = p.line(0, 0.0);
        assert!(line.contains("fail 1"), "{line}");
        assert!(line.contains("1x400"), "{line}");
    }

    #[test]
    fn the_summary_distinguishes_attempts_from_successes() {
        let mut p = Progress::new(Duration::from_secs(30));
        p.success(10, 10);
        p.failure(FailKind::Transport);
        let s = p.summary(42);
        assert!(s.contains("1 quer(ies) of 2 attempt(s)"), "{s}");
        assert!(s.contains("42 still pending"), "{s}");
    }
}
