//! What the discarded logits head costs on every prefill position.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_prefill_cost -- <gemma4_text.maml>
//! ```
//!
//! # What this measures
//!
//! A prompt is pushed one position at a time and the logits are kept only for the last of them.
//! `bridge.rs:2484` selects [`gemma4::Mode::DecodeStep.at(TIER)`] unconditionally, and `want_logits` is
//! checked at :2491 — *after* the pass — so the head runs for every position of the prompt and
//! the host throws the result away.
//!
//! [`gemma4::Mode::Prefill`] is the same pass without the head. This times both against the same
//! device and reports the difference, which is the saving available with no batching at all.
//!
//! # Why it needs measuring rather than deriving
//!
//! The head is 9 of the plan's ~1,094 ops but 227 MB of its 1.30 GB of weights — 0.8% by dispatch
//! and 17.5% by bytes, a factor of twenty apart. Which figure predicts the time depends on whether
//! these particular ops are dispatch-bound or bandwidth-bound, and the profile that established
//! "283 us per op" is an average over ops spanning four orders of magnitude in size. Four int4
//! projections over 262,144 classes are not average ops. So the derivation goes both ways and only
//! a stopwatch settles it.
//!
//! Build with `--release`. A debug build measures `rustc -O0`, not the runtime.
use std::path::PathBuf;
use std::time::Instant;

use modelrunner::nets::gemma4;
use modelrunner::preprocess::RESCALE_ONLY;
use modelrunner::vulkan::context;
use modelrunner::vulkan::run::{Net, StepParams};
use modelrunner::weights::{graph, Weights};

/// Positions to time in each mode. Enough to average out submit jitter.
const STEPS: u32 = 12;

/// The cache these examples record against: the top tier, so a long prompt fits.
const TIER: u32 = gemma4::MAX_CONTEXT;

fn main() {
    let Some(path) = std::env::args().nth(1).map(PathBuf::from) else {
        println!("usage: report_prefill_cost <gemma4_text.maml>");
        return;
    };
    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(why) => {
            println!("cannot read {}: {why}", path.display());
            return;
        }
    };
    let weights = match Weights::parse(&bytes, graph::GEMMA4_TEXT) {
        Ok(weights) => weights,
        Err(why) => {
            println!("cannot parse it: {why}");
            return;
        }
    };
    println!("weights   {:.2} GB", weights.data().len() as f64 / 1e9);

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => {
            println!("no usable Vulkan device: {why}");
            return;
        }
    };

    let decode = match gemma4::build(&weights, gemma4::Mode::DecodeStep.at(TIER)) {
        Ok(plan) => plan,
        Err(why) => {
            println!("the decode plan does not build: {why}");
            return;
        }
    };
    let prefill = match gemma4::build(&weights, gemma4::Mode::Prefill { tokens: 1 }.at(TIER)) {
        Ok(plan) => plan,
        Err(why) => {
            println!("the prefill plan does not build: {why}");
            return;
        }
    };
    println!(
        "decode    {:>5} ops, {:>6.1} MB arena",
        decode.ops.len(),
        f64::from(decode.arena_elems) * 2.0 / 1e6
    );
    println!(
        "prefill   {:>5} ops, {:>6.1} MB arena   ({} ops fewer)",
        prefill.ops.len(),
        f64::from(prefill.arena_elems) * 2.0 / 1e6,
        decode.ops.len() - prefill.ops.len()
    );
    println!();

    // Constructed at `DecodeStep`, exactly as `bridge.rs:2605` does, so the arena is sized for the
    // larger of the two and the switch to prefill cannot grow it. Growing is what would drop the
    // KV caches, and doing it in the same order as the shipping code is the point.
    let mut net = match Net::new(context, decode, &weights, RESCALE_ONLY) {
        Ok(net) => net,
        Err(why) => {
            println!("the decode plan does not record: {why}");
            return;
        }
    };

    // The values are irrelevant to timing; only the shapes reach the device.
    let hidden = vec![0.01f32; gemma4::D_MODEL as usize];
    let per_layer = vec![0.01f32; (gemma4::PER_LAYER * gemma4::LAYERS as u32) as usize];
    let local = vec![0.01f32; gemma4::HEAD_DIM as usize];
    let global = vec![0.01f32; gemma4::GLOBAL_HEAD_DIM as usize];

    let run = |net: &mut Net, label: &str| -> Option<f64> {
        // One discarded pass: the first submit pays for pipeline warm-up and for faulting 1.3 GB
        // of weights into device memory, neither of which is per-position cost.
        if let Err(why) = step(net, 0, &hidden, &per_layer, &local, &global) {
            println!("{label}: warm-up failed: {why}");
            return None;
        }
        let start = Instant::now();
        for position in 0..STEPS {
            if let Err(why) = step(net, position, &hidden, &per_layer, &local, &global) {
                println!("{label}: step {position} failed: {why}");
                return None;
            }
        }
        Some(start.elapsed().as_secs_f64() * 1e3 / f64::from(STEPS))
    };

    let Some(decode_ms) = run(&mut net, "decode") else { return };
    println!("decode    {decode_ms:>7.2} ms per position, head computed and discarded");

    // Switching plans is a `Reshaped::at` mode change, which is a `Net::rebuild`: a
    // `device_wait_idle` under the process-wide queue lock and every dispatch emitted again. It
    // happens once per turn in the shipping path — the prompt is pushed headless and the first
    // sampled token wants logits — so it is charged once against the whole prefill, not per
    // position. Timed because a saving of a few ms a position would be worth nothing if the
    // switch that unlocks it cost more than the prompt saves.
    let switch = Instant::now();
    if let Err(why) = net.rebuild(prefill) {
        println!("the prefill plan does not record: {why}");
        return;
    }
    let switch_ms = switch.elapsed().as_secs_f64() * 1e3;
    println!("switch    {switch_ms:>7.2} ms to re-record, once per turn");
    let Some(prefill_ms) = run(&mut net, "prefill") else { return };
    println!("prefill   {prefill_ms:>7.2} ms per position, no head");
    println!();

    let saved = decode_ms - prefill_ms;
    println!("saved     {saved:>7.2} ms per position, {:.1}%", 100.0 * saved / decode_ms);
    // The prompt measured on device was 1,871 tokens, all but the last of them headless.
    let over_prompt = saved * 1870.0 - switch_ms;
    println!(
        "          {:>7.1} s over a 1,871-token prompt, net of the one switch",
        over_prompt / 1e3
    );
    println!();

    // How much of a position is attention, and does attention cost anything at all?
    //
    // `Mode::Prefill { tokens: N }` is refused above one, so the N-sweep that would answer "does a
    // pass over N tokens cost one pass or N passes" cannot be run without first building the
    // batched path it is meant to de-risk. This is the closest probe that needs no new plan.
    //
    // `prefix` is the only thing that changes how much work the attention ops do: every cached
    // score and value op sums over `prefix + 1` keys, so at prefix 0 they touch one key and at
    // 2047 they touch 2048. Same plan, same dispatch count, same weights, same bytes read for
    // every projection - 2048x the attention arithmetic and 2048x the KV cache traffic.
    //
    // If the time is flat across this sweep, attention is free at these sizes and the pass is
    // paying for something fixed per dispatch or per weight byte. Both of those amortise over a
    // batch. If it climbs, attention is real per-token work and batching amortises less of the
    // pass than the op count suggests.
    println!("prefix sweep, same plan, attention work only:");
    for prefix in [0u32, 63, 255, 1023, 2047] {
        let start = Instant::now();
        for _ in 0..STEPS {
            if let Err(why) = step(&mut net, prefix, &hidden, &per_layer, &local, &global) {
                println!("  prefix {prefix}: {why}");
                return;
            }
        }
        let ms = start.elapsed().as_secs_f64() * 1e3 / f64::from(STEPS);
        println!("  prefix {prefix:>5} -> {ms:>7.2} ms   ({} keys attended)", prefix + 1);
    }
}

/// One position, with the step parameters the decode loop would set.
fn step(
    net: &mut Net,
    position: u32,
    hidden: &[f32],
    per_layer: &[f32],
    local: &[f32],
    global: &[f32],
) -> Result<(), String> {
    net.set_params(StepParams {
        prefix: position,
        window_start: position.saturating_sub(gemma4::WINDOW - 1),
    })?;
    net.infer_raw_many(&[hidden, per_layer, local, global]).map(|_| ())
}
