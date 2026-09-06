//! Runs Gemma 4's decode step on the GPU and compares its logits with onnxruntime's.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example check_gemma4_parity -- \
//!     gemma4_text.maml gemma4_embed.maml logits_golden.json
//! ```
//!
//! # This is the check nothing else substitutes for
//!
//! Every other test in this port checks *layout* - that 999 tensors are declared in the order the
//! converter writes them, that a scale has the rank its precision implies, that one op agrees
//! with a CPU oracle. All of that can pass while the model is wrong, because a transposed read
//! agrees with itself and a doubled scale is still a number.
//!
//! Thirty-five layers in sequence against real weights is the only thing that catches an error of
//! *composition*: a residual added before its norm instead of after, a rotary applied to the key
//! but not the query, a cache written at the wrong position. Those produce plausible logits.
//!
//! # What agreement to expect
//!
//! The reference is fp16 weights in fp32 arithmetic; this is int4 weights with a per-block scale.
//! The logits will not match to the bit and should not be expected to. What must match is the
//! **argmax** and the broad ordering of the top few - a model that is right differs from the
//! reference by quantisation noise, and a model that is wrong differs by rearranging the ranking.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::nets::gemma4;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{graph, Weights};

/// The cache these examples record against: the top tier, so a long prompt fits.
const TIER: u32 = gemma4::MAX_CONTEXT;

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed), Some(golden)) =
        (args.next().map(PathBuf::from), args.next().map(PathBuf::from), args.next())
    else {
        println!("usage: check_gemma4_parity <text.maml> <embed.maml> <golden.json>");
        return;
    };
    let (text_bytes, embed_bytes) = match (std::fs::read(&text), std::fs::read(&embed)) {
        (Ok(a), Ok(b)) => (a, b),
        (a, b) => {
            println!("cannot read the weights: {:?} {:?}", a.err(), b.err());
            return;
        }
    };
    let weights = match Weights::parse(&text_bytes, graph::GEMMA4_TEXT) {
        Ok(w) => w,
        Err(why) => return println!("the text model does not parse: {why}"),
    };
    let embed_weights = match Weights::parse(&embed_bytes, graph::GEMMA4_EMBED) {
        Ok(w) => w,
        Err(why) => return println!("the embedding does not parse: {why}"),
    };
    let golden = match std::fs::read_to_string(&golden) {
        Ok(text) => text,
        Err(why) => return println!("cannot read the golden: {why}"),
    };
    let (tokens, want_ids, want_logits) = parse_golden(&golden);
    if tokens.is_empty() {
        return println!("the golden holds no tokens");
    }
    println!("tokens {tokens:?}");

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    match run(&context, &weights, &embed_weights, &tokens) {
        Ok(logits) => report(&logits, &want_ids, &want_logits),
        Err(why) => println!("the decode failed: {why}"),
    }

    // Bisect: where do the two first disagree? Only useful when the logits already failed, but
    // cheap enough to always print.
    let trace_path = std::env::args().nth(4);
    let Some(trace_path) = trace_path else { return };
    let Ok(trace) = std::fs::read_to_string(&trace_path) else {
        return println!("cannot read {trace_path}");
    };
    println!();
    println!("bisect against the reference's intermediates");
    for (label, layers) in [("ple_combined", 0usize), ("l0_out", 1), ("l1_out", 2)] {
        let Some(want) = field(&trace, label) else {
            println!("  {label:<14} not in the trace file");
            continue;
        };
        match trace_run(&context, &weights, &embed_weights, &tokens, layers) {
            Ok((hidden, per_layer)) => {
                let got: &[f32] = if label == "ple_combined" { &per_layer } else { &hidden };
                println!("  {label:<14} {}", compare(got, &want));
            }
            Err(why) => println!("  {label:<14} failed: {why}"),
        }
    }
}

/// Cosine and relative RMS between a device tensor and the reference's.
fn compare(got: &[f32], want: &[f32]) -> String {
    if got.len() != want.len() {
        return format!("length {} against {}", got.len(), want.len());
    }
    let dot: f64 = got.iter().zip(want).map(|(a, b)| f64::from(*a) * f64::from(*b)).sum();
    let na: f64 = got.iter().map(|a| f64::from(*a) * f64::from(*a)).sum::<f64>().sqrt();
    let nb: f64 = want.iter().map(|b| f64::from(*b) * f64::from(*b)).sum::<f64>().sqrt();
    let cosine = dot / (na * nb).max(1e-30);
    let verdict = if cosine > 0.99 { "ok" } else { "WRONG" };
    format!("cosine {cosine:.6}  rms {:.4} against {:.4}  {verdict}", na / got.len() as f64, nb / want.len() as f64)
}

/// One named array from the trace file.
fn field(text: &str, key: &str) -> Option<Vec<f32>> {
    let at = text.find(&format!("\"{key}\""))?;
    let rest = &text[at..];
    let open = rest.find('[')?;
    let close = rest[open..].find(']')?;
    Some(rest[open + 1..open + close].split(',').filter_map(|p| p.trim().parse().ok()).collect())
}

/// [`run`] stopping after `layers` layers, returning `(hidden, per_layer)`.
fn trace_run(
    context: &Arc<context::Context>,
    weights: &Weights,
    embed: &Weights,
    tokens: &[u32],
    layers: usize,
) -> Result<(Vec<f32>, Vec<f32>), String> {
    let mode = gemma4::Mode::Trace { layers }.at(TIER);
    let mut net =
        Reshaped::new(Arc::clone(context), weights, mode, |offsets, mode| gemma4::build(offsets, mode))?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut last = (Vec::new(), Vec::new());
    for (step, &token) in tokens.iter().enumerate() {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        let (hidden, per_layer) = gemma4::gather(&reader, token)?;
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
        let at = net.at(mode)?;
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[&hidden, &per_layer, &angles_local, &angles_global])?;
        let mut it = out.into_iter();
        last = (it.next().unwrap_or_default(), it.next().unwrap_or_default());
    }
    Ok(last)
}

/// Feed every token in order and return the last step's logits.
fn run(
    context: &Arc<context::Context>,
    weights: &Weights,
    embed: &Weights,
    tokens: &[u32],
) -> Result<Vec<f32>, String> {
    let mut net = Reshaped::new(
        Arc::clone(context),
        weights,
        gemma4::Mode::DecodeStep.at(TIER),
        |offsets, mode| gemma4::build(offsets, mode),
    )?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut logits = Vec::new();
    for (step, &token) in tokens.iter().enumerate() {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        if position >= gemma4::MAX_CONTEXT {
            return Err(format!("position {position} is past MAX_CONTEXT"));
        }
        let (hidden, per_layer) = gemma4::gather(&reader, token)?;
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;

        let at = net.at(gemma4::Mode::DecodeStep.at(TIER))?;
        // The sliding layers attend `[position - WINDOW + 1, position]`; the full ones want the
        // whole prefix. One `window_start` serves both only while the prefix is inside the
        // window, which it is for a golden this short - a longer one needs the per-layer window
        // this runtime does not carry yet.
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[&hidden, &per_layer, &angles_local, &angles_global])?;
        logits.clear();
        for split in out.iter().take(gemma4::HEAD_SPLITS) {
            logits.extend_from_slice(split);
        }
    }
    if logits.len() != gemma4::VOCAB as usize {
        return Err(format!("{} logits, not {}", logits.len(), gemma4::VOCAB));
    }
    Ok(logits)
}

/// One position's angles from a rotary table, which is plain fp16.
fn rotary_row(
    reader: &modelrunner::weights::Reader<'_>,
    index: usize,
    width: u32,
    position: u32,
) -> Result<Vec<f32>, String> {
    let all = reader.fp16(index, &[gemma4::MAX_CONTEXT, width])?;
    let from = (position * width) as usize;
    all.get(from..from + width as usize)
        .map(<[f32]>::to_vec)
        .ok_or_else(|| format!("position {position} is past the rotary table"))
}

/// Print the comparison, and say plainly whether it passed.
fn report(got: &[f32], want_ids: &[u32], want_logits: &[f32]) {
    let mut order: Vec<u32> = (0..got.len() as u32).collect();
    order.sort_by(|&a, &b| got[b as usize].total_cmp(&got[a as usize]));
    let top: Vec<u32> = order.iter().copied().take(10).collect();

    println!();
    println!("  reference top10 {want_ids:?}");
    println!("  device    top10 {top:?}");
    println!();
    println!("  {:>8}  {:>12}  {:>12}", "id", "reference", "device");
    for (rank, &id) in want_ids.iter().enumerate().take(10) {
        println!(
            "  {id:>8}  {:>12.4}  {:>12.4}",
            want_logits.get(rank).copied().unwrap_or(f32::NAN),
            got.get(id as usize).copied().unwrap_or(f32::NAN),
        );
    }
    println!();
    let argmax_ok = top.first() == want_ids.first();
    let overlap = top.iter().filter(|id| want_ids.contains(id)).count();
    println!("  argmax {}", if argmax_ok { "MATCHES" } else { "DIFFERS" });
    println!("  {overlap} of the reference's top 10 are in the device's top 10");
    if argmax_ok && overlap >= 8 {
        println!();
        println!("PASS: the forward pass agrees with onnxruntime within quantisation noise");
    } else {
        println!();
        println!("FAIL: this is a composition error, not quantisation - the ranking moved");
    }
}

/// `{"tokens": [...], "top_ids": [...], "top_logits": [...]}`.
fn parse_golden(text: &str) -> (Vec<u32>, Vec<u32>, Vec<f32>) {
    let numbers = |key: &str| -> String {
        let Some(at) = text.find(key) else { return String::new() };
        let rest = &text[at + key.len()..];
        let Some(open) = rest.find('[') else { return String::new() };
        let Some(close) = rest[open..].find(']') else { return String::new() };
        rest[open + 1..open + close].to_string()
    };
    let ints = |s: String| -> Vec<u32> {
        s.split(',').filter_map(|p| p.trim().parse().ok()).collect()
    };
    let floats = |s: String| -> Vec<f32> {
        s.split(',').filter_map(|p| p.trim().parse().ok()).collect()
    };
    (
        ints(numbers("\"tokens\"")),
        ints(numbers("\"top_ids\"")),
        floats(numbers("\"top_logits\"")),
    )
}
