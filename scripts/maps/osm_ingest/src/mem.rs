//! Peak resident set size, so a build reports its own memory ceiling.
//!
//! The planet-scale gates for `road_graph` are memory gates: the question is
//! never "did it finish" but "how close did it come to the box". Having the tool
//! print its own `VmHWM` means every run is a measurement, including the ones
//! nobody thought to instrument, and it removes the step where a gate is judged
//! from a `top` reading somebody happened to catch.
//!
//! `VmHWM` is a high-water *mark*, so it survives the frees that a `top` sample
//! between two phases would miss. It does not include the page cache or dirty
//! pages, which is the one thing it cannot answer and the reason the graph
//! writer prefers sequential writes to computed-offset ones.

/// Peak RSS in bytes, or `None` where the platform does not expose it.
///
/// Only Linux is implemented: that is where the scale gates run, and the
/// alternative on other platforms is a raw FFI declaration to carry a number
/// nothing reads.
pub fn peak_rss_bytes() -> Option<u64> {
    #[cfg(target_os = "linux")]
    {
        let status = std::fs::read_to_string("/proc/self/status").ok()?;
        parse_vm_hwm(&status)
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

/// `VmHWM:\t  123456 kB` -> bytes.
#[cfg(any(target_os = "linux", test))]
fn parse_vm_hwm(status: &str) -> Option<u64> {
    let line = status.lines().find(|l| l.starts_with("VmHWM:"))?;
    let kb: u64 = line
        .split_whitespace()
        .nth(1)
        .and_then(|v| v.parse().ok())?;
    Some(kb * 1024)
}

/// `"4.97 GB"`, or a note that the platform does not report it. Callers put this
/// straight into a log line.
pub fn peak_rss_report() -> String {
    match peak_rss_bytes() {
        Some(b) => format!("{:.2} GB", b as f64 / (1u64 << 30) as f64),
        None => "not reported on this platform".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vm_hwm_is_read_in_kilobytes() {
        let status = "Name:\troad_graph\nVmPeak:\t 9999999 kB\nVmHWM:\t 5212672 kB\nVmRSS:\t 12 kB\n";
        assert_eq!(parse_vm_hwm(status), Some(5_212_672 * 1024));
        // VmRSS must not be mistaken for the high-water mark, and a status file
        // without the field (older kernels, or a sandbox) is absent, not zero.
        assert_eq!(parse_vm_hwm("VmRSS:\t 12 kB\n"), None);
        assert_eq!(parse_vm_hwm("VmHWM:\n"), None);
    }

    #[test]
    fn the_report_is_a_string_on_every_platform() {
        let s = peak_rss_report();
        assert!(!s.is_empty());
        #[cfg(target_os = "linux")]
        assert!(s.ends_with(" GB"), "{s}");
    }
}
