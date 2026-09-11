//! Is a texel-buffer read faster than a storage-buffer read on this device?
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_read_path
//! ```
//!
//! Runs [`modelrunner::vulkan::imageprobe::compare`] several times and reports the median of
//! each path. That function is otherwise reachable only through the JNI entry point
//! `MlNative.imageProbeGemma4`, which needs an app, a JVM and a loaded model; the probe itself
//! needs none of those - it allocates its own 256 MB and synthesises everything else - so this
//! exposes it as a plain binary that runs over `adb shell`.
//!
//! Repeated rather than run once because the first sweep on a cold device is not the steady
//! state, and a single sample cannot show whether a difference between the two paths is larger
//! than the run-to-run spread.
use std::sync::Arc;

use modelrunner::vulkan::context::{self, Context};
use modelrunner::vulkan::imageprobe;

/// Sweeps per path. Odd, so the median is a sample rather than an average of two.
const RUNS: usize = 5;

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => {
            println!("no usable Vulkan device: {why}");
            return;
        }
    };
    report(&context);
}

fn report(context: &Arc<Context>) {
    let mut ssbo = Vec::with_capacity(RUNS);
    let mut texel = Vec::with_capacity(RUNS);

    println!("  run   storage GB/s   texel GB/s    ratio");
    for run in 1..=RUNS {
        match imageprobe::compare(context) {
            Ok((buffer, view)) => {
                let (b, t) = (buffer / 1e9, view / 1e9);
                println!("  {run:3}   {b:12.2}   {t:10.2}   {:6.2}x", t / b.max(1e-9));
                ssbo.push(b);
                texel.push(t);
            }
            Err(why) => println!("  {run:3}   probe failed: {why}"),
        }
    }

    let (Some(b), Some(t)) = (median(&mut ssbo), median(&mut texel)) else {
        println!();
        println!("no successful runs");
        return;
    };

    println!();
    println!("median  storage buffer {b:.2} GB/s   texel buffer {t:.2} GB/s   {:.2}x", t / b.max(1e-9));
    println!("spread  storage {:.2}-{:.2}   texel {:.2}-{:.2}", low(&ssbo), high(&ssbo), low(&texel), high(&texel));
    println!();
    println!("The storage-buffer figure is itself a read-bandwidth ceiling over 256 MB, so it is");
    println!("the denominator for every kernel number: a shader cannot beat it reading the same way.");
}

/// The middle sample, or `None` when nothing succeeded. Sorts `values` in place.
fn median(values: &mut [f64]) -> Option<f64> {
    if values.is_empty() {
        return None;
    }
    values.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    values.get(values.len() / 2).copied()
}

fn low(values: &[f64]) -> f64 {
    values.iter().copied().fold(f64::INFINITY, f64::min)
}

fn high(values: &[f64]) -> f64 {
    values.iter().copied().fold(f64::NEG_INFINITY, f64::max)
}
