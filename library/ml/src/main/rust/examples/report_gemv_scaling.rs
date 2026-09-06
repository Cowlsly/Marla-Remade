//! Does a gemv reach the same bandwidth at every matrix size?
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_gemv_scaling
//! ```
//!
//! # The question
//!
//! On a Tensor G4 the logits head reads its 201 MB of int4 weights at about 9.6 GB/s while the
//! per-layer projections manage 4.9, using the *same shader*. The only difference is shape: the
//! head is four `[65536, 1536]` matrices and a projection is `[1536, 1536]` or thereabouts.
//!
//! If a dispatch needs a while to reach streaming speed - filling the pipeline, warming the
//! caches, getting enough workgroups in flight - then small dispatches never get there and the
//! achieved bandwidth rises with size. That would make merging independent projections into one
//! dispatch worth doing, and would explain why removing dispatches did nothing while making them
//! *bigger* might.
//!
//! If instead every size reaches the same rate, shape is not the story and the difference lies
//! somewhere else.
use std::sync::Arc;

use modelrunner::nets::{Act, Builder, Shape, WeightSource};
use modelrunner::vulkan::context;
use modelrunner::preprocess::RESCALE_ONLY;
use modelrunner::vulkan::run::Net;
use modelrunner::weights::{Blob, Dtype, Tensor};

/// A blob of identical bytes with a matching tensor table.
///
/// The table is not decoration: `vulkan::segment` uses it to bound what each op may read, and
/// refuses a plan whose reads fall outside it.
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
///
/// A bandwidth measurement does not care what the numbers are, only that the reads land inside
/// the blob and cover the right span.
struct Invented;

impl WeightSource for Invented {
    fn shaped(&self, _index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(0)
    }
    fn shaped_words(&self, _index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(0)
    }
    fn count(&self) -> usize {
        // Exactly what one int4 convolution reads: weights, per-block scales, bias.
        3
    }
}

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    println!("  {:>7}  {:>7}  {:>9}  {:>9}", "out", "in", "MB", "GB/s");
    // Square-ish shapes spanning a projection through to a logits split.
    for (out, inputs) in [
        (1536u32, 1536u32),
        (3072, 1536),
        (6144, 1536),
        (12288, 1536),
        (24576, 1536),
        (65536, 1536),
    ] {
        match measure(&context, out, inputs) {
            Ok((bytes, rate)) => println!(
                "  {out:>7}  {inputs:>7}  {:>9.1}  {:>9.2}",
                bytes as f64 / 1e6,
                rate / 1e9
            ),
            Err(why) => println!("  {out:>7}  {inputs:>7}  {why}"),
        }
    }
}

/// Time one int4 gemv of `out` by `inputs`, returning its weight bytes and the rate reached.
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
    // Int4 weights, a per-block scale table and a bias. The blob only has to be large enough
    // and correctly shaped; the numbers in it do not matter to a bandwidth measurement.
    let blocks = inputs.div_ceil(32);
    let weight_bytes = (u64::from(out) * u64::from(inputs)) / 2;
    let scale_bytes = u64::from(out) * u64::from(blocks) * 2;
    let bias_bytes = u64::from(out) * 2;
    // Every tensor starts at zero and spans the whole blob.
    //
    // They alias, which would be nonsense for inference and is exactly right here: the question
    // is how fast `out * inputs / 2` bytes of weights can be pulled through, not what the
    // numbers mean. Overlapping also sidesteps having to mirror `Invented`'s offset arithmetic
    // in two places, which is the kind of duplication that has already caused trouble.
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
    // One warm pass, then the best of five: the first pays for any lazy allocation.
    net.infer_raw(&feed)?;
    let mut best = f64::MAX;
    for _ in 0..5 {
        let started = std::time::Instant::now();
        net.infer_raw(&feed)?;
        best = best.min(started.elapsed().as_secs_f64());
    }
    Ok((weight_bytes, weight_bytes as f64 / best))
}
