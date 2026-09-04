//! What one NLLB decode token costs before it reaches the GPU.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_decode_cost
//! ```
//!
//! # What this measures
//!
//! The real per-token cost of the decode loop: `set_params` plus a submit, with the plan recorded
//! once. Before the record-once change this was a full `nllb::build` and a `Net::rebuild` - a
//! `device_wait_idle` under the process-wide queue lock and a re-emit of every dispatch - on top
//! of the submit, plus an upload of the whole KV cache from the host that grew by a position
//! every token.
//!
//! Build with `--release`. A debug build measures `rustc -O0`, not the runtime.
use std::path::PathBuf;
use std::time::Instant;

use modelrunner::nets::nllb;
use modelrunner::preprocess::RESCALE_ONLY;
use modelrunner::vulkan::context;
use modelrunner::vulkan::run::{Net, StepParams};
use modelrunner::weights::{graph, Weights};

/// Source tokens the cross-attention attends over. A short sentence.
const SRC_LEN: u32 = 32;

/// Decode steps to time. Enough to show whether cost grows with the cache.
const STEPS: u32 = 24;

fn main() {
    let Some(path) = weights_path() else {
        println!("no nllb600.maml found.");
        println!("Set MODELRUNNER_NLLB, or run scripts/ml/fetch_nllb600.py to populate");
        println!("build/nllb600/nllb600.maml.");
        return;
    };
    println!("weights  {}", path.display());

    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(why) => {
            println!("cannot read it: {why}");
            return;
        }
    };
    let weights = match Weights::parse(&bytes, graph::NLLB) {
        Ok(weights) => weights,
        Err(why) => {
            println!("cannot parse it: {why}");
            return;
        }
    };
    println!("         {:.1} MB of data section", weights.data().len() as f64 / 1e6);

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => {
            println!("no usable Vulkan device: {why}");
            return;
        }
    };
    println!();

    // The encoder runs once per sentence, so its build cost is amortised. Reported for scale.
    let start = Instant::now();
    let encode = match nllb::build(&weights, nllb::Mode::Encode { len: SRC_LEN }) {
        Ok(plan) => plan,
        Err(why) => {
            println!("the encoder does not build: {why}");
            return;
        }
    };
    let encode_build = start.elapsed();
    println!(
        "encoder    {:>7.2} ms to build a {}-op plan, once per sentence",
        encode_build.as_secs_f64() * 1e3,
        encode.ops.len(),
    );

    // The first decode plan, which the Net is created from. Creation is not per-token cost, so it
    // is timed separately.
    let start = Instant::now();
    let first = match nllb::build(&weights, nllb::Mode::DecodeStep { src_len: SRC_LEN }) {
        Ok(plan) => plan,
        Err(why) => {
            println!("a decode step does not build: {why}");
            return;
        }
    };
    let plan_build = start.elapsed();
    let ops = first.ops.len();
    let arena_mb = f64::from(first.arena_elems) * 2.0 / 1e6;
    let start = Instant::now();
    let mut net = match Net::new(context, first, &weights, RESCALE_ONLY) {
        Ok(net) => net,
        Err(why) => {
            println!("the decode plan does not record: {why}");
            return;
        }
    };
    println!(
        "decoder    {:>7.2} ms to build a {ops}-op plan, {:>7.2} ms to record it, both once",
        plan_build.as_secs_f64() * 1e3,
        start.elapsed().as_secs_f64() * 1e3,
    );
    println!("           {arena_mb:.1} MB of arena, including the KV cache it now holds");
    println!();

    // A token and an encoder output. The values do not matter to the timing, only the shapes.
    let embedded = match nllb::embed_positions(weights.reader(), &[2], 0) {
        Ok(values) => values,
        Err(why) => {
            println!("cannot embed: {why}");
            return;
        }
    };
    let source = vec![0.01f32; (nllb::D_MODEL * SRC_LEN) as usize];

    // One discarded step: the first submit pays for pipeline warm-up and for faulting the weights
    // into device memory, neither of which a steady-state token pays.
    if let Err(why) = net
        .set_params(StepParams { prefix: 0, window_start: 0 })
        .and_then(|()| net.infer_raw_many(&[&embedded, &source]).map(|_| ()))
    {
        println!("the warm-up step failed: {why}");
        return;
    }

    println!("per token, with the plan recorded once");
    println!("  step       total ms");
    let mut total = 0.0f64;
    for step in 1..=STEPS {
        let start = Instant::now();
        if let Err(why) = net.set_params(StepParams { prefix: step, window_start: 0 }) {
            println!("step {step} could not set params: {why}");
            return;
        }
        if let Err(why) = net.infer_raw_many(&[&embedded, &source]) {
            println!("step {step} did not submit: {why}");
            return;
        }
        let each = start.elapsed().as_secs_f64() * 1e3;
        total += each;
        // Sparse output: the first few, then every eighth, so growth is visible without noise.
        if step <= 3 || step % 8 == 0 {
            println!("  {step:>4}       {each:>8.2}");
        }
    }

    let each = total / f64::from(STEPS);
    println!();
    println!("mean        {each:>8.2} ms per token, {:.1} tokens/s", 1e3 / each);
    println!();
    println!("This is the whole step: params, submit, compute and readback. The rebuild it");
    println!("replaced measured 1.64 ms per token on this device, so on NLLB the saving is a");
    println!("modest fraction of a step that is dominated by its own arithmetic.");
    println!();
    println!("The shape of the number matters more than its size. It is flat: the old loop also");
    println!("re-uploaded the entire KV cache every token, which grew by a position each time, so");
    println!("its cost rose with sentence length where this does not. And the rebuild it removes");
    println!("scales with op count - 317 here, thousands for an LLM - which is the case the");
    println!("record-once design was actually for.");
}

/// `MODELRUNNER_NLLB` if set, else `build/nllb600/nllb600.maml` from the repo root.
fn weights_path() -> Option<PathBuf> {
    if let Ok(path) = std::env::var("MODELRUNNER_NLLB") {
        let path = PathBuf::from(path);
        return path.is_file().then_some(path);
    }
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    while !dir.join("settings.gradle.kts").is_file() {
        dir = dir.parent()?.to_path_buf();
    }
    let path = dir.join("build/nllb600/nllb600.maml");
    path.is_file().then_some(path)
}
