//! Greedy-decodes NLLB against the recorded HuggingFace token sequence.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example check_nllb_parity
//! ```
//!
//! # Why this exists
//!
//! `library/ml/src/main/rust/tests/assets.rs` cannot cover NLLB: the weights are a 617 MB runtime
//! download, not a committed asset, so nothing in `cargo test` ever runs the real model. The
//! device-side parity fixtures check one op at a time against the CPU interpreter, which says
//! nothing about whether twelve decoder layers and a KV cache produce the right *tokens*.
//!
//! `scripts/ml/nllb_parity_fixture.json` holds what fp32 HuggingFace produced for a handful of
//! sentences, at a pinned revision. This drives the same greedy loop `post::translate` runs, on
//! the device, and compares ids.
//!
//! # What it is for
//!
//! Changing how the decoder holds its KV cache is a change that keeps every shape and every op
//! count identical while making the numbers wrong - which is precisely the class of change the
//! per-op fixtures cannot catch. Run this before such a change and after it; the ids must not
//! move.
//!
//! The loop here is a transcription of `post::translate::translate`, not a call to it, because
//! that takes a `Table` to tokenise with and the fixture already carries token ids. Keeping the
//! tokenizer out means a mismatch here is the *model*, which is the point.
use std::path::PathBuf;

use modelrunner::nets::nllb;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{graph, Weights};

/// `</s>`, both the decoder's start token and its stop token.
const EOS: u32 = 2;

/// The cap `post::translate::MAX_TOKENS` applies.
const MAX_TOKENS: usize = 128;

fn main() {
    let Some(weights_path) = find(&["build/nllb600/nllb600.maml"], "MODELRUNNER_NLLB") else {
        println!("no nllb600.maml found. Set MODELRUNNER_NLLB, or run scripts/ml/fetch_nllb600.py");
        return;
    };
    let Some(fixture_path) = find(&["scripts/ml/nllb_parity_fixture.json"], "MODELRUNNER_FIXTURE")
    else {
        println!("scripts/ml/nllb_parity_fixture.json is missing");
        return;
    };
    println!("weights  {}", weights_path.display());
    println!("fixture  {}", fixture_path.display());

    let fixture = match std::fs::read_to_string(&fixture_path) {
        Ok(text) => text,
        Err(why) => {
            println!("cannot read the fixture: {why}");
            return;
        }
    };
    let pairs = parse(&fixture);
    if pairs.is_empty() {
        println!("the fixture has no pairs this can read");
        return;
    }

    let bytes = match std::fs::read(&weights_path) {
        Ok(bytes) => bytes,
        Err(why) => {
            println!("cannot read the weights: {why}");
            return;
        }
    };
    let weights = match Weights::parse(&bytes, graph::NLLB) {
        Ok(weights) => weights,
        Err(why) => {
            println!("cannot parse the weights: {why}");
            return;
        }
    };
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => {
            println!("no usable Vulkan device: {why}");
            return;
        }
    };
    println!();

    let mut failures = 0;
    for pair in &pairs {
        match decode(&context, &weights, pair) {
            Ok(got) => {
                // The fixture's sequence opens with `</s>` and the forced language token, which
                // the loop consumes as framing rather than emitting. Compare against the rest.
                let want: Vec<u32> = pair.expected.iter().copied().skip(2).collect();
                let want: Vec<u32> =
                    want.iter().copied().take_while(|&id| id != EOS).collect();
                if got == want {
                    println!("ok    {:?}", pair.text);
                    println!("      {} tokens", got.len());
                } else {
                    failures += 1;
                    println!("FAIL  {:?}", pair.text);
                    println!("      want {want:?}");
                    println!("      got  {got:?}");
                }
            }
            Err(why) => {
                failures += 1;
                println!("FAIL  {:?}: {why}", pair.text);
            }
        }
    }

    println!();
    if failures == 0 {
        println!("all {} pairs match the recorded HuggingFace output", pairs.len());
    } else {
        println!("{failures} of {} pairs differ", pairs.len());
    }
}

/// One sentence from the fixture.
struct Pair {
    text: String,
    source: Vec<u32>,
    target: u32,
    expected: Vec<u32>,
}

/// Greedy-decode one pair on the device, returning the ids the loop would emit.
fn decode(
    context: &std::sync::Arc<context::Context>,
    weights: &Weights,
    pair: &Pair,
) -> Result<Vec<u32>, String> {
    let src_len = u32::try_from(pair.source.len()).map_err(|_| "a source longer than u32")?;
    let mut net = Reshaped::new(
        std::sync::Arc::clone(context),
        weights,
        nllb::Mode::Encode { len: src_len },
        |offsets, mode| nllb::build(offsets, mode),
    )?;

    // The encoder, once. Its output conditions every step.
    let embedded = nllb::embed_positions(weights.reader(), &pair.source, 0)?;
    let encoded = {
        let at = net.at(nllb::Mode::Encode { len: src_len })?;
        let out = at.infer_raw(&embedded)?;
        out.into_iter().next().ok_or("the encoder returned nothing")?
    };

    let width = nllb::D_MODEL as usize;
    let mut produced = Vec::new();
    let mut token = EOS;
    for step in 0..MAX_TOKENS {
        let cache_len = u32::try_from(step).map_err(|_| "a step past u32")?;
        if cache_len >= nllb::MAX_DECODE_POSITIONS {
            break;
        }
        let embedded = nllb::embed_positions(weights.reader(), &[token], cache_len)?;
        // The same key every step, so this re-records once - on the switch out of `Encode` - and
        // never again. The KV cache stays in the arena between submits.
        let at = net.at(nllb::Mode::DecodeStep { src_len })?;
        at.set_params(StepParams { prefix: cache_len, window_start: 0 })?;
        let out = at.infer_raw_many(&[&embedded, &encoded])?;

        let mut logits: Vec<f32> = Vec::with_capacity(nllb::VOCAB as usize);
        for split in out.iter().take(nllb::HEAD_SPLITS) {
            logits.extend_from_slice(split);
        }
        if logits.len() != nllb::VOCAB as usize {
            return Err(format!("{} logits, not {} (width {width})", logits.len(), nllb::VOCAB));
        }

        // Step 0's argmax is discarded: HuggingFace forces the target language token there.
        let next = if step == 0 { pair.target } else { argmax(&logits)? };
        if next == EOS {
            break;
        }
        if step > 0 {
            produced.push(next);
        }
        token = next;
    }
    Ok(produced)
}

/// Largest index, ties to the lowest, NaN skipped. Matches `post::translate::argmax`.
fn argmax(logits: &[f32]) -> Result<u32, String> {
    let mut best: Option<(usize, f32)> = None;
    for (index, &value) in logits.iter().enumerate() {
        if value.is_nan() {
            continue;
        }
        if best.is_none_or(|(_, top)| value > top) {
            best = Some((index, value));
        }
    }
    best.map(|(index, _)| index as u32).ok_or_else(|| "no finite logit".to_string())
}

/// Pull the pairs out of the fixture.
///
/// A hand-rolled reader rather than a JSON crate: this crate's only dependencies are `ash` and
/// `jni`, and a diagnostic is not a reason to add a third. The fixture's shape is fixed and
/// committed beside this, so the parsing does not have to be general - only honest about
/// failing, which it does by returning fewer pairs than the file has.
fn parse(text: &str) -> Vec<Pair> {
    let mut pairs = Vec::new();
    for block in text.split("\"input_text\"").skip(1) {
        let Some(input) = string_after(block, "") else { continue };
        let Some(source) = numbers_after(block, "\"encoder_input_ids\"") else { continue };
        let Some(expected) = numbers_after(block, "\"greedy_output_ids\"") else { continue };
        let Some(target) = numbers_after(block, "\"tgt_lang_id\"").and_then(|v| v.first().copied())
        else {
            continue;
        };
        pairs.push(Pair { text: input, source, target, expected });
    }
    pairs
}

/// The first quoted string in `block` after `key`.
fn string_after(block: &str, key: &str) -> Option<String> {
    let rest = block.split_once(key).map_or(block, |(_, rest)| rest);
    let start = rest.find('"')? + 1;
    let body = rest.get(start..)?;
    let end = body.find('"')?;
    body.get(..end).map(str::to_owned)
}

/// Every integer between the brackets that follow `key`, or the single value after it.
fn numbers_after(block: &str, key: &str) -> Option<Vec<u32>> {
    let (_, rest) = block.split_once(key)?;
    let rest = rest.trim_start().strip_prefix(':')?.trim_start();
    let body = match rest.strip_prefix('[') {
        Some(list) => list.get(..list.find(']')?)?,
        // A bare number, terminated by the next comma or brace.
        None => rest.get(..rest.find([',', '\n', '}'])?)?,
    };
    let mut out = Vec::new();
    for piece in body.split(',') {
        let piece = piece.trim();
        if piece.is_empty() {
            continue;
        }
        out.push(piece.parse::<u32>().ok()?);
    }
    Some(out)
}

/// `variable` if it names a file, else the first of `candidates` under the repo root.
fn find(candidates: &[&str], variable: &str) -> Option<PathBuf> {
    if let Ok(path) = std::env::var(variable) {
        let path = PathBuf::from(path);
        return path.is_file().then_some(path);
    }
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    while !dir.join("settings.gradle.kts").is_file() {
        dir = dir.parent()?.to_path_buf();
    }
    candidates.iter().map(|name| dir.join(name)).find(|path| path.is_file())
}
