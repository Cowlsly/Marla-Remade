//! Generates text with Gemma 4 on the Vulkan runtime.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example run_gemma4 -- \
//!     gemma4_text.maml gemma4_embed.maml gemma4_tokenizer.spm1 "The capital of France is" 20
//! ```
//!
//! # Why this exists beside `check_gemma4_parity`
//!
//! Parity checks one step against a reference and reports a cosine. That proves the arithmetic
//! and says nothing about whether the thing is *usable*: a model can match logits on step one and
//! still fall apart at step twenty because the KV cache is written at the wrong position, the
//! window slides the wrong way, or the record-once plan reads a stale prefix. Only generation
//! surfaces those, and only past the point where the cache stops being empty.
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;

use modelrunner::nets::gemma4;
use modelrunner::post::sentencepiece::{Table, GEMMA};
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{graph, Weights};

/// The cache these examples record against: the top tier, so a long prompt fits.
///
/// `GEMMA4_TIER` overrides it. Needed because a `.maml` carries rotary tables sized for the
/// `MAX_CONTEXT` it was converted at, and the current source says 16,384 while the weights on
/// the test device were converted at 2,048 — which fails at load with
/// `tensor 17 is [2048, 256], the forward pass wants [16384, 256]`. Overriding lets an older
/// export be measured without a reconvert; it does not paper over anything, because a wrong
/// value still fails loudly at the same check.
fn tier() -> u32 {
    std::env::var("GEMMA4_TIER")
        .ok()
        .and_then(|v| v.parse::<u32>().ok())
        .filter(|v| *v > 0)
        .unwrap_or(gemma4::MAX_CONTEXT)
}

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed), Some(tokenizer), Some(prompt)) =
        (args.next(), args.next(), args.next(), args.next())
    else {
        println!("usage: run_gemma4 <text.maml> <embed.maml> <table.spm1> <prompt> [tokens]");
        return;
    };
    let limit: usize = args.next().and_then(|n| n.parse().ok()).unwrap_or(24);
    // `--chat` wraps the prompt in the turn markers the instruction-tuned model expects, which
    // is what `Gemma4Handle.render` does in the app. Without it a bare prompt is a completion,
    // and an instruction-tuned Gemma answers most of those by ending the turn immediately - so
    // timings taken without it are not timings of a chat.
    let chat = std::env::args().any(|a| a == "--chat");

    let read = |p: &str| std::fs::read(PathBuf::from(p));
    let (Ok(text_bytes), Ok(embed_bytes), Ok(table_bytes)) =
        (read(&text), read(&embed), read(&tokenizer))
    else {
        println!("cannot read one of the three files");
        return;
    };
    let (Ok(weights), Ok(embed_weights)) = (
        Weights::parse(&text_bytes, graph::GEMMA4_TEXT),
        Weights::parse(&embed_bytes, graph::GEMMA4_EMBED),
    ) else {
        println!("the weights do not parse");
        return;
    };
    let table = match Table::parse_with(&table_bytes, GEMMA) {
        Ok(table) => table,
        Err(why) => return println!("the tokenizer does not parse: {why}"),
    };

    // Only the four sentencepiece specials below are matched, so a chat marker like `<|turn>`
    // would tokenise as `<`, `|`, `turn`, `>` instead of the single id the model was trained on.
    // That fails silently and plausibly: the run completes, the output reads like prose, and the
    // prompt the model saw was structurally different from the one that was written.
    //
    // The marker set belongs to the template rather than the tokenizer, which is why
    // `encode_with_specials` takes it as an argument and why the app passes `Gemma4Handle.MARKERS`
    // across JNI. An example cannot read a Kotlin companion object, so the two lists cannot be
    // shared and will drift. Refusing is what stops that drift from being drawn a conclusion from.
    if chat {
        return run_chat(&weights, &embed_weights, &table, &prompt, limit);
    }
    if prompt.contains("<|") || prompt.contains("|>") {
        println!(
            "this example takes a plain completion prompt, not a chat-formatted one.\n\
             it found a chat marker (`<|` or `|>`), which it would encode as literal characters \
             rather than as the single ids the model was trained on - silently, and the output \
             would look reasonable.\n\
             for chat, tool or multimodal prompts go through `Gemma4Handle.generate`, which passes \
             the full marker set."
        );
        return;
    }

    let specials = ["<bos>", "<eos>", "<pad>", "<unk>"];
    let mut tokens = vec![GEMMA.bos];
    tokens.extend(table.encode_with_specials(&prompt, &specials));
    println!("prompt {prompt:?}");
    println!("  {} tokens: {tokens:?}", tokens.len());

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    match generate(&context, &weights, &embed_weights, &mut tokens, limit) {
        Ok((elapsed, produced)) => {
            println!();
            println!("generated {:?}", table.decode(&produced));
            println!();
            println!("  {} tokens in {:.2}s", produced.len(), elapsed);
            println!("  {:.1} ms a token", elapsed * 1000.0 / produced.len().max(1) as f64);
        }
        Err(why) => println!("generation failed: {why}"),
    }
}

/// Positions one prefill submit covers. Attention is quadratic in this.
const PREFILL_CHUNK_DEFAULT: usize = 128;

/// `GEMMA4_PREFILL_CHUNK` overrides it, for sweeping the cost curve against the fence timeout.
fn prefill_chunk() -> usize {
    std::env::var("GEMMA4_PREFILL_CHUNK")
        .ok()
        .and_then(|v| v.parse().ok())
        .filter(|v| *v > 0)
        .unwrap_or(PREFILL_CHUNK_DEFAULT)
}

/// Write one position's `values` into column `column` of a `[C, 1, width]` block.
fn place(into: &mut [f32], values: &[f32], column: u32, width: u32) {
    for (channel, &value) in values.iter().enumerate() {
        if let Some(slot) = into.get_mut(channel * width as usize + column as usize) {
            *slot = value;
        }
    }
}

/// Ids that end a generation, from `generation_config.json`'s `eos_token_id`.
///
/// Three, not one. `<eos>` is 1, but an instruction-tuned Gemma ends its *reply* with `<turn|>`
/// (106) and the tokenizer's `<eos>` almost never appears - stopping only on 1 lets the model run
/// on emitting turn markers until the token budget does the stopping instead.
const STOP: [u32; 3] = [1, 106, 50];

/// Feed the prompt, then sample greedily until `limit` new tokens or a stop id.
fn generate(
    context: &Arc<context::Context>,
    weights: &Weights,
    embed: &Weights,
    tokens: &mut Vec<u32>,
    limit: usize,
) -> Result<(f64, Vec<u32>), String> {
    let mut net = Reshaped::new(Arc::clone(context), weights, gemma4::Mode::DecodeStep.at(tier()), |o, m| {
        gemma4::build(o, m)
    })?;
    let reader = embed.reader();
    let rotary = weights.reader();
    // The tables are read once rather than per step: 2048 rows of fp16 is a few megabytes and
    // re-reading them each token would dominate the measurement below.
    let local = rotary.fp16(gemma4::ROTARY_LOCAL, &[tier(), gemma4::HEAD_DIM])?;
    let global =
        rotary.fp16(gemma4::ROTARY_GLOBAL, &[tier(), gemma4::GLOBAL_HEAD_DIM])?;

    let prompt_len = tokens.len();
    let started = Instant::now();

    // The prompt in batched submits, then one decode step per generated token. Everything but
    // the last prompt token only fills the cache, so its logits would be computed and thrown
    // away - which at 262,144 classes is most of the pass.
    let prefill_started = Instant::now();
    let mut fed = 0usize;
    // `GEMMA4_NO_PREFILL=1` feeds the prompt one position at a time instead, which is what the
    // decode path does. Kept as a switch rather than deleted: batched prefill and sequential
    // prefill must produce the same tokens, and the only way to know they do is to run both.
    let batched = std::env::var("GEMMA4_NO_PREFILL").is_err();
    let chunk_size = if batched { prefill_chunk() } else { 1 };
    for chunk in tokens[..prompt_len - 1].chunks(chunk_size) {
        let width = chunk.len() as u32;
        let base = fed as u32;
        let mut hidden = vec![0f32; (gemma4::D_MODEL * width) as usize];
        let mut per_layer =
            vec![0f32; (gemma4::PER_LAYER * gemma4::LAYERS as u32 * width) as usize];
        let mut angles_local = vec![0f32; (gemma4::HEAD_DIM * width) as usize];
        let mut angles_global = vec![0f32; (gemma4::GLOBAL_HEAD_DIM * width) as usize];
        for (offset, &token) in chunk.iter().enumerate() {
            let column = offset as u32;
            let position = base + column;
            let (h, p) = gemma4::gather(&reader, token)?;
            place(&mut hidden, &h, column, width);
            place(&mut per_layer, &p, column, width);
            let take = |table: &[f32], dim: u32| {
                let from = (position * dim) as usize;
                table[from..from + dim as usize].to_vec()
            };
            place(&mut angles_local, &take(&local, gemma4::HEAD_DIM), column, width);
            place(&mut angles_global, &take(&global, gemma4::GLOBAL_HEAD_DIM), column, width);
        }
        let at = net.at(if batched {
            gemma4::Mode::Prefill { tokens: width }.at(tier())
        } else {
            gemma4::Mode::DecodeStep.at(tier())
        })?;
        at.set_params(StepParams {
            prefix: base,
            window_start: base.saturating_sub(gemma4::WINDOW - 1),
        })?;
        at.infer_raw_many(&[&hidden, &per_layer, &angles_local, &angles_global])?;
        fed += chunk.len();
    }
    println!(
        "  prefill {} tokens in {:.2}s  ({:.2} ms a token){}",
        fed,
        prefill_started.elapsed().as_secs_f64(),
        prefill_started.elapsed().as_secs_f64() * 1000.0 / fed.max(1) as f64,
        if batched { "" } else { "  [sequential]" },
    );

    let mut generated = 0;
    for step in fed..prompt_len + limit {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        if position >= tier() {
            break;
        }
        let token = tokens[step];
        let (hidden, per_layer) = gemma4::gather(&reader, token)?;
        let row = |table: &[f32], width: u32| {
            let from = (position * width) as usize;
            table[from..from + width as usize].to_vec()
        };
        let at = net.at(gemma4::Mode::DecodeStep.at(tier()))?;
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[
            &hidden,
            &per_layer,
            &row(&local, gemma4::HEAD_DIM),
            &row(&global, gemma4::GLOBAL_HEAD_DIM),
        ])?;

        // Only the last prompt token's logits are a prediction; the rest just fill the cache.
        if step + 1 < prompt_len {
            continue;
        }
        let mut best = (f32::NEG_INFINITY, 0u32);
        let mut base = 0u32;
        for split in out.iter().take(gemma4::HEAD_SPLITS) {
            for (offset, &value) in split.iter().enumerate() {
                if value > best.0 {
                    best = (value, base + offset as u32);
                }
            }
            base += split.len() as u32;
        }
        if STOP.contains(&best.1) {
            break;
        }
        tokens.push(best.1);
        generated += 1;
        if generated >= limit {
            break;
        }
    }
    Ok((started.elapsed().as_secs_f64(), tokens[prompt_len..].to_vec()))
}

/// One templated turn, timed the way a user experiences it.
///
/// Mirrors `Gemma4Handle.render` and `Gemma4Handle.generate`: the same markers, the same marker
/// set passed to the encoder, the same stop ids. Kept in step by hand, which is why the plain
/// path above refuses chat markers rather than letting the two drift silently.
fn run_chat(
    weights: &Weights,
    embed: &Weights,
    table: &Table<'_>,
    prompt: &str,
    limit: usize,
) -> () {
    const MARKERS: [&str; 11] = [
        "<bos>", "<eos>", "<|turn>", "<turn|>", "<|tool>", "<tool|>", "<|tool_call>",
        "<tool_call|>", "<|tool_response>", "<tool_response|>", "<|\"|>",
    ];
    let rendered = format!("<bos><|turn>user\n{prompt}<turn|>\n<|turn>model\n");
    let tokens = table.encode_with_specials(&rendered, &MARKERS);
    println!("prompt {prompt:?}  ({} tokens templated)", tokens.len());

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    let opened = Instant::now();
    let mut net = match Reshaped::new(
        std::sync::Arc::clone(&context),
        weights,
        gemma4::Mode::DecodeStep.at(tier()),
        |o, m| gemma4::build(o, m),
    ) {
        Ok(net) => net,
        Err(why) => return println!("the model did not open: {why}"),
    };
    let reader = embed.reader();
    let rotary = weights.reader();
    let (local, global) = match (
        rotary.fp16(gemma4::ROTARY_LOCAL, &[tier(), gemma4::HEAD_DIM]),
        rotary.fp16(gemma4::ROTARY_GLOBAL, &[tier(), gemma4::GLOBAL_HEAD_DIM]),
    ) {
        (Ok(l), Ok(g)) => (l, g),
        _ => return println!("the rotary tables did not load"),
    };
    println!("  open    {:.2}s", opened.elapsed().as_secs_f64());

    let first = Instant::now();
    let mut fed = 0usize;
    for chunk in tokens[..tokens.len() - 1].chunks(prefill_chunk()) {
        let width = chunk.len() as u32;
        let base = fed as u32;
        let mut hidden = vec![0f32; (gemma4::D_MODEL * width) as usize];
        let mut per_layer =
            vec![0f32; (gemma4::PER_LAYER * gemma4::LAYERS as u32 * width) as usize];
        let mut al = vec![0f32; (gemma4::HEAD_DIM * width) as usize];
        let mut ag = vec![0f32; (gemma4::GLOBAL_HEAD_DIM * width) as usize];
        for (offset, &token) in chunk.iter().enumerate() {
            let column = offset as u32;
            let position = base + column;
            let Ok((h, p)) = gemma4::gather(&reader, token) else { return };
            place(&mut hidden, &h, column, width);
            place(&mut per_layer, &p, column, width);
            let from = (position * gemma4::HEAD_DIM) as usize;
            place(&mut al, &local[from..from + gemma4::HEAD_DIM as usize], column, width);
            let from = (position * gemma4::GLOBAL_HEAD_DIM) as usize;
            place(&mut ag, &global[from..from + gemma4::GLOBAL_HEAD_DIM as usize], column, width);
        }
        let Ok(at) = net.at(gemma4::Mode::Prefill { tokens: width }.at(tier())) else { return };
        let _ = at.set_params(StepParams {
            prefix: base,
            window_start: base.saturating_sub(gemma4::WINDOW - 1),
        });
        if at.infer_raw_many(&[&hidden, &per_layer, &al, &ag]).is_err() {
            return println!("prefill failed");
        }
        fed += chunk.len();
    }
    let prefilled = first.elapsed();

    let mut produced: Vec<u32> = Vec::new();
    let mut next = tokens[tokens.len() - 1];
    let mut first_token = None;
    // Where the decode step's wall time actually goes.
    //
    // The measured step is 282 ms while the GPU's own gemv work accounts for roughly 71 ms
    // (1.30 GB at the 18.4 GB/s a faithful probe of this kernel reaches), leaving ~211 ms
    // unattributed. Before that is blamed on per-dispatch cost, note this loop does two large
    // pieces of HOST work per token that no GPU probe models: `gemma4::gather` reads embedding
    // rows out of a 1.55 GB mapped file, and the argmax scans 262,144 logits. Split them rather
    // than assume.
    let mut spent_gather = std::time::Duration::ZERO;
    let mut spent_infer = std::time::Duration::ZERO;
    let mut spent_argmax = std::time::Duration::ZERO;
    let decode_started = Instant::now();
    for step in 0..limit {
        let position = (fed + step) as u32;
        let at_gather = Instant::now();
        let Ok((hidden, per_layer)) = gemma4::gather(&reader, next) else { break };
        let from = (position * gemma4::HEAD_DIM) as usize;
        let al = local[from..from + gemma4::HEAD_DIM as usize].to_vec();
        let from = (position * gemma4::GLOBAL_HEAD_DIM) as usize;
        let ag = global[from..from + gemma4::GLOBAL_HEAD_DIM as usize].to_vec();
        spent_gather += at_gather.elapsed();

        let at_infer = Instant::now();
        let Ok(at) = net.at(gemma4::Mode::DecodeStep.at(tier())) else { break };
        let _ = at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        });
        let Ok(out) = at.infer_raw_many(&[&hidden, &per_layer, &al, &ag]) else { break };
        spent_infer += at_infer.elapsed();

        let at_argmax = Instant::now();
        let mut best = (f32::NEG_INFINITY, 0u32);
        let mut base = 0u32;
        for split in out.iter().take(gemma4::HEAD_SPLITS) {
            for (offset, &value) in split.iter().enumerate() {
                if value > best.0 {
                    best = (value, base + offset as u32);
                }
            }
            base += split.len() as u32;
        }
        spent_argmax += at_argmax.elapsed();

        if first_token.is_none() {
            first_token = Some(first.elapsed());
        }
        if STOP.contains(&best.1) {
            break;
        }
        produced.push(best.1);
        next = best.1;
    }
    let decoded = decode_started.elapsed();

    println!("  prefill {:.0} ms for {fed} tokens", prefilled.as_secs_f64() * 1000.0);
    println!(
        "  first   {:.0} ms to the first token",
        first_token.unwrap_or(prefilled).as_secs_f64() * 1000.0
    );
    println!(
        "  decode  {:.0} ms for {} tokens ({:.0} ms each)",
        decoded.as_secs_f64() * 1000.0,
        produced.len(),
        decoded.as_secs_f64() * 1000.0 / produced.len().max(1) as f64
    );
    println!("  reply   {:?}", table.decode(&produced));
    let steps = produced.len().max(1) as f64;
    println!();
    println!("  decode step breakdown, per token, over {} steps:", produced.len());
    println!(
        "    gather  {:6.1} ms   HOST: embedding rows from the 1.55 GB mapped file + rotary slices",
        spent_gather.as_secs_f64() * 1000.0 / steps,
    );
    println!(
        "    infer   {:6.1} ms   upload + submit + fence + readback (the only part with GPU in it)",
        spent_infer.as_secs_f64() * 1000.0 / steps,
    );
    println!(
        "    argmax  {:6.1} ms   HOST: scan of 262,144 logits",
        spent_argmax.as_secs_f64() * 1000.0 / steps,
    );
}
