//! Separates fixed per-call cost from streaming rate in the int4 gemv, by measurement.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_gemv_overhead
//! ```
//!
//! # Why this exists
//!
//! `report_gemv_scaling` sweeps six matrix shapes and its smallest is 1.18 MB, which is large
//! enough that fixed cost and streaming cost are entangled. Fitting `time = fixed + bytes/rate`
//! to those six points recovers the split, but a two-parameter fit to six noisy points is weak
//! evidence and the intercept it produces varies by 2x depending on which points are included.
//!
//! This measures the intercept instead. It sweeps down to a `[64, 1536]` projection - 49 kB of
//! weights, which even a slow kernel streams in tens of microseconds - so the wall time of the
//! smallest shapes *is* the fixed cost, near enough, with no fit involved.
//!
//! # What the fixed cost is, and is not
//!
//! Each timed iteration is one `Net::infer_raw`: a host staging write of the input vector, one
//! `queue_submit`, a fence wait, and a readback. It is a **host round trip**, of which the GPU
//! dispatch is one part.
//!
//! That matters for how the number should be read. A real decode step issues on the order of a
//! thousand dispatches inside a single submit, so it pays this round trip roughly once per
//! token rather than once per projection. **The intercept measured here is therefore mostly an
//! artefact of the harness, not a per-projection cost the decoder actually pays** - which is
//! exactly why it has to be measured and subtracted rather than left in the headline number.
//!
//! The figure that survives the subtraction is the streaming rate, and that one is real.
use std::sync::Arc;

use modelrunner::nets::{Act, Builder, Shape, WeightSource};
use modelrunner::preprocess::RESCALE_ONLY;
use modelrunner::vulkan::context;
use modelrunner::vulkan::run::Net;
use modelrunner::weights::{Blob, Dtype, Tensor};

/// A blob of identical bytes with a matching tensor table.
struct Filler {
    bytes: Vec<u8>,
    table: Vec<Tensor>,
}

impl Blob for Filler {
    fn data_len(&self) -> u64 {
        self.bytes.len() as u64
    }
    fn tensors(&self) -> &[Tensor] {
        &self.table
    }
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let from = offset as usize;
        let span = self.bytes.get(from..from + into.len()).ok_or_else(|| {
            format!("a read of {} at {offset} past {}", into.len(), self.bytes.len())
        })?;
        into.copy_from_slice(span);
        Ok(())
    }
}

/// Lays tensors out back to back in the order they are asked for, so no model file is needed.
struct Invented;

impl WeightSource for Invented {
    fn shaped(&self, _index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(0)
    }
    fn shaped_words(&self, _index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(0)
    }
    fn count(&self) -> usize {
        3
    }
}

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };

    // Small shapes first. The first few are dominated by the round trip, which is the point.
    let shapes: [u32; 11] =
        [64, 128, 256, 512, 1024, 1536, 3072, 6144, 12288, 24576, 65536];

    println!("  {:>7}  {:>9}  {:>9}  {:>9}", "out", "MB", "ms", "GB/s");
    let mut samples: Vec<(f64, f64)> = Vec::new();
    for out in shapes {
        match measure(&context, out, 1536) {
            Ok((bytes, seconds)) => {
                let mb = bytes as f64 / 1e6;
                println!(
                    "  {out:>7}  {mb:>9.3}  {:>9.3}  {:>9.2}",
                    seconds * 1000.0,
                    bytes as f64 / seconds / 1e9
                );
                samples.push((bytes as f64, seconds));
            }
            Err(why) => println!("  {out:>7}  {why}"),
        }
    }

    // The smallest shape moves 49 kB. Whatever it costs is overhead, not streaming.
    let Some(&(small_bytes, floor)) = samples.first() else {
        return println!("nothing measured");
    };
    let Some(&(big_bytes, big_seconds)) = samples.last() else {
        return println!("nothing measured");
    };

    println!();
    println!("fixed cost   {:.3} ms   (wall time of a {:.0} kB gemv, which is almost all round trip)", floor * 1000.0, small_bytes / 1e3);

    let corrected = big_seconds - floor;
    if corrected > 0.0 {
        println!(
            "streaming    {:.2} GB/s  (largest shape, {:.1} MB, with that fixed cost subtracted)",
            big_bytes / corrected / 1e9,
            big_bytes / 1e6
        );
        println!(
            "uncorrected  {:.2} GB/s  (the number report_gemv_scaling prints)",
            big_bytes / big_seconds / 1e9
        );
    }
    println!();
    println!("Compare the streaming figure against report_read_path's storage-buffer column.");
    println!("That comparison is the one that is NOT an artefact of per-call overhead.");
}

/// Time one int4 gemv of `out` by `inputs`, returning its weight bytes and the best seconds.
fn measure(
    context: &Arc<context::Context>,
    out: u32,
    inputs: u32,
) -> Result<(u64, f64), String> {
    let source = Invented;
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(inputs, 1, 1));
    let projected = builder.conv_int4(input, 0, out, Act::None);
    let plan = builder.finish(&[projected])?;
    let blocks = inputs.div_ceil(32);
    let weight_bytes = (u64::from(out) * u64::from(inputs)) / 2;
    let scale_bytes = u64::from(out) * u64::from(blocks) * 2;
    let bias_bytes = u64::from(out) * 2;
    let total = weight_bytes + scale_bytes + bias_bytes + 4096;
    let table = vec![
        Tensor {
            rank: 1,
            dims: [(total / 2) as u32, 0, 0, 0],
            offset: 0,
            len: (total / 2) as u32,
            dtype: Dtype::F16,
        };
        3
    ];
    let data = Filler { bytes: vec![0x11u8; total as usize], table };
    let mut net = Net::new(Arc::clone(context), plan, &data, RESCALE_ONLY)?;
    let feed = vec![0.5f32; inputs as usize];
    // One warm pass, then the best of nine. More repeats than the scaling probe because the
    // small shapes are short enough that a single scheduling hiccup dominates a best-of-five.
    net.infer_raw(&feed)?;
    let mut best = f64::MAX;
    for _ in 0..9 {
        let started = std::time::Instant::now();
        net.infer_raw(&feed)?;
        best = best.min(started.elapsed().as_secs_f64());
    }
    Ok((weight_bytes, best))
}
