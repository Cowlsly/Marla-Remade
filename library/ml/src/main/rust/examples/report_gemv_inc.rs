//! Does `in_c` - and so the gemv loop's trip count - change the rate?
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_gemv_inc
//! ```
//!
//! # Why this shape sweep and not another
//!
//! Every sweep taken so far holds `in = 1536` and varies `out`. At `I4_BLOCK` 32 the loop in
//! `conv_vec_int4.comp:75` strides 2048 taps, so `in = 1536` pins the trip count at **one** and
//! the active lanes at **48 of 64** in every row of every one of those tables. The variable
//! nobody has moved is the one the loop actually depends on.
//!
//! `down_proj` is `[1536, 6144]` and `[1536, 12288]` in this model - 22% of the weight bytes -
//! and those run the loop three and six times with all 64 lanes busy. So the model already
//! contains both regimes; only the measurements are missing.
//!
//! # The discriminator
//!
//! Rows are grouped so that `out * in` - and hence weight bytes and total multiply-adds - is
//! held constant while the trip count moves. Same work, same traffic, different loop:
//!
//! * if the degenerate loop is what costs us, the long-`in` rows are markedly faster;
//! * if the dequant arithmetic is what costs us, every row in a group reads the same, because
//!   each does the same number of unpacks either way.
//!
//! The two hypotheses differ in sign here, which no sweep of `out` can do.
use std::sync::Arc;

use modelrunner::nets::{Act, Builder, Shape, WeightSource};
use modelrunner::preprocess::RESCALE_ONLY;
use modelrunner::vulkan::context;
use modelrunner::vulkan::run::Net;
use modelrunner::weights::{Blob, Dtype, Tensor};

/// A blob of identical bytes with a matching tensor table. As `report_gemv_scaling`.
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

/// Trip count and active lanes for a given `in_c`, mirroring `conv_vec_int4.comp:75`.
fn loop_shape(inputs: u32) -> (u32, u32) {
    let stride = 64 * 32;
    let iterations = inputs.div_ceil(stride);
    let active = (inputs.div_ceil(32)).min(64);
    (iterations, active)
}

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    println!("  constant-bytes groups: only in_c moves, so weight bytes and total MACs are fixed");
    println!(
        "  {:>7}  {:>7}  {:>7}  {:>6}  {:>6}  {:>9}",
        "out", "in", "MB", "iters", "lanes", "GB/s"
    );
    let groups: [&[(u32, u32)]; 3] = [
        // ~9.4 MB of weights each.
        &[(12288, 1536), (3072, 6144), (1536, 12288)],
        // ~4.7 MB each.
        &[(6144, 1536), (1536, 6144)],
        // The real down_proj pair beside the projection shape they share an output width with.
        &[(1536, 1536), (1536, 6144), (1536, 12288)],
    ];
    for (index, group) in groups.iter().enumerate() {
        println!("  --- group {} ---", index + 1);
        for &(out, inputs) in group.iter() {
            let (iterations, active) = loop_shape(inputs);
            match measure(&context, out, inputs) {
                Ok((bytes, rate)) => println!(
                    "  {out:>7}  {inputs:>7}  {:>7.1}  {iterations:>6}  {active:>6}  {:>9.2}",
                    bytes as f64 / 1e6,
                    rate / 1e9
                ),
                Err(why) => println!("  {out:>7}  {inputs:>7}  {why}"),
            }
        }
    }
}

/// Time one int4 gemv of `out` by `inputs`, returning its weight bytes and the rate reached.
fn measure(context: &Arc<context::Context>, out: u32, inputs: u32) -> Result<(u64, f64), String> {
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
    net.infer_raw(&feed)?;
    let mut best = f64::MAX;
    for _ in 0..7 {
        let started = std::time::Instant::now();
        net.infer_raw(&feed)?;
        best = best.min(started.elapsed().as_secs_f64());
    }
    Ok((weight_bytes, weight_bytes as f64 / best))
}
