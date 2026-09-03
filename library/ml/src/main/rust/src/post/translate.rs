//! NLLB's decode loop: source text to translated text, one token at a time.
//!
//! # What was inside the ncnn AAR
//!
//! The loop, like the tokenizer, lived in the `.so`. The two nets arrive here as a **trait** so
//! the sequencing is host-testable against stubs — the pattern `post::ocr` and `post::supertonic`
//! both use — because the order of the special tokens is where the mistakes are and none of them
//! needs a device to catch.
//!
//! # The source language token goes on the SOURCE side; the decoder starts from </s>
//!
//! This is the one thing about NLLB that is easy to get backwards, and getting it backwards
//! produces fluent output in the wrong language rather than an error:
//!
//! ```text
//! source  = [source_language_token] ++ tokenizer(text) ++ [</s>]
//! decoder input starts from </s> (id 2); the first *generated* token is forced to the
//! target language token (forced-BOS), then decoding proceeds autoregressively
//! ```
//!
//! That forcing is HuggingFace `generate`'s `forced_bos_token_id`: the step-0 logits are
//! overridden, so the model never gets a vote on the first output. Every reference
//! `greedy_output_ids` in `scripts/ml/nllb_parity_fixture.json` is `[2, tgt, content…, 2]`,
//! which is exactly the framing [`translate`] replays.
//!
//! SMaLL-100, which this replaced, did it the other way round: the *target* token on the source
//! and `</s>`-start. See [`translate`].
//!
//! # Greedy, not beam
//!
//! The reference export's `generation_config.json` says `num_beams: 5`, but that is the
//! HuggingFace default carried along by `_from_model_config` — the reference Android example
//! decodes greedily, and so does this. Beam 5 would mean five decoder states and five times the
//! work per token for a phrase-sized translation. The cost is some quality on longer sentences.

use super::sentencepiece::{Table, EOS};

/// Decoder start token: `</s>` (id 2), which is also `decoder_start_token_id`.
///
/// The decoder input starts here — not at the language token. The language token is *generated*
/// first, forced: step 0 of [`translate`] overrides the logits with the target token.
pub const DECODER_START: u32 = EOS;

/// Tokens the loop will emit before giving up.
///
/// The same 128 the decoder's KV cache is built for, so the loop cannot outrun it. A translation
/// of a sentence is tens of tokens; this is the guard against a model that never emits `</s>`,
/// which greedy decoding can do by repeating itself.
pub const MAX_TOKENS: usize = 128;

/// The two GPU stages, as a trait, so the loop below is host-testable.
pub trait Nets {
    /// The encoder over the whole source sequence, returning `[source.len(), 1024]`.
    ///
    /// Run once. Its output conditions every decoder step, and the cross-attention keys and
    /// values derived from it are computed on the first step and then frozen.
    fn encode(&mut self, source: &[u32]) -> Result<Vec<f32>, String>;

    /// One decoder step: the previous token in, the logits over the vocabulary out.
    ///
    /// `step` is the position, so the implementation knows where in its KV cache to write and how
    /// far the causal mask reaches.
    fn decode_step(&mut self, token: u32, step: usize, encoded: &[f32]) -> Result<Vec<f32>, String>;
}

/// Translate `normalised` from the language `source_token` names into the language `target_token`
/// names.
///
/// The first of NLLB's 202 language tokens, `ace_Arab` through `zul_Latn` in flores order.
///
/// Model-eng's tokenizer inventory: the 202 flores codes sit at ids `256001..256202` (after the
/// 256,000 SentencePiece pieces), with `<mask>` at 256203 and fairseq's specials at 0..3.
pub const FIRST_NLLB_LANG_TOKEN: u32 = 256_001;

/// Language tokens NLLB holds: the 202 flores200 codes.
pub const NLLB_LANGUAGES: u32 = 202;

/// Whether `token` is one of NLLB's language tokens.
pub fn is_nllb_lang_token(token: u32) -> bool {
    (FIRST_NLLB_LANG_TOKEN..FIRST_NLLB_LANG_TOKEN + NLLB_LANGUAGES).contains(&token)
}

/// Translate `normalised` from the language `source_token` names into the language `target_token`
/// names, with NLLB's protocol.
///
/// `normalised` must already be NFKC; see [`Table::encode`]. Both tokens are validated here:
/// swapping them produces fluent output in the wrong language rather than an error.
///
/// The decoder starts from `</s>` ([`DECODER_START`]) and step 0 is *forced* to `target_token`
/// — HuggingFace's `forced_bos_token_id` — so the step-0 argmax is discarded, the forced token
/// is fed back as step 1's input but never emitted as text, and decoding proceeds greedily from
/// there, stopping at the first `</s>`.
///
/// An empty result means the text had nothing to translate, which is not a failure.
pub fn translate(
    nets: &mut dyn Nets,
    table: &Table,
    source_token: u32,
    target_token: u32,
    normalised: &str,
) -> Result<String, String> {
    for (what, token) in [("source", source_token), ("target", target_token)] {
        if !is_nllb_lang_token(token) {
            return Err(format!(
                "{what} token {token} is not one of the {NLLB_LANGUAGES} NLLB language tokens \
                 from {FIRST_NLLB_LANG_TOKEN}"
            ));
        }
    }
    let body = table.encode(normalised);
    if body.is_empty() {
        return Ok(String::new());
    }

    // The SOURCE language token leads the source, and the tokenizer's own `</s>` closes it.
    let mut source = Vec::with_capacity(body.len() + 2);
    source.push(source_token);
    source.extend(body);
    source.push(EOS);

    let encoded = nets.encode(&source)?;

    let mut produced: Vec<u32> = Vec::new();
    // The decoder starts from `</s>`, and step 0 is forced to the target language — the
    // `forced_bos_token_id` override. The forced token is fed back as step 1's input but never
    // emitted: it is framing, not translated text.
    let mut token = DECODER_START;
    for step in 0..MAX_TOKENS {
        let logits = nets.decode_step(token, step, &encoded)?;
        let next = if step == 0 {
            target_token
        } else {
            argmax(&logits).ok_or_else(|| {
                format!("the decoder returned {} logits at step {step}", logits.len())
            })?
        };
        if next == EOS {
            break;
        }
        if step > 0 {
            produced.push(next);
        }
        token = next;
    }
    Ok(table.decode(&produced))
}

/// The index of the largest value, or `None` for an empty slice.
///
/// Ties take the lowest index, which is what `argmax` does everywhere else and what makes a
/// greedy decode reproducible. NaN never wins, so a net that produced one degrades to picking
/// some other token rather than to whichever comparison happened first.
fn argmax(logits: &[f32]) -> Option<u32> {
    let mut best: Option<(usize, f32)> = None;
    for (index, &value) in logits.iter().enumerate() {
        if value.is_nan() {
            continue;
        }
        if best.is_none_or(|(_, top)| value > top) {
            best = Some((index, value));
        }
    }
    best.map(|(index, _)| index as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Records what the loop asked for, and answers with a scripted sequence of tokens.
    struct Scripted {
        /// One token per step, the last of which is usually `EOS`.
        script: Vec<u32>,
        /// The source the encoder was given, for the assertions about special tokens.
        source: Vec<u32>,
        /// Every `(token, step)` the decoder saw.
        steps: Vec<(u32, usize)>,
        vocabulary: usize,
    }

    impl Nets for Scripted {
        fn encode(&mut self, source: &[u32]) -> Result<Vec<f32>, String> {
            self.source = source.to_vec();
            // One 1024-wide row per source token, as the real encoder returns.
            Ok(vec![0.5; source.len() * 1024])
        }

        fn decode_step(
            &mut self,
            token: u32,
            step: usize,
            encoded: &[f32],
        ) -> Result<Vec<f32>, String> {
            assert_eq!(encoded.len(), self.source.len() * 1024);
            self.steps.push((token, step));
            let mut logits = vec![0.0f32; self.vocabulary];
            // The scripted token wins; running off the end asks for `EOS`.
            let wanted = self.script.get(step).copied().unwrap_or(EOS);
            logits[wanted as usize] = 1.0;
            Ok(logits)
        }
    }

    fn scripted(script: &[u32]) -> Scripted {
        Scripted {
            script: script.to_vec(),
            source: Vec::new(),
            steps: Vec::new(),
            vocabulary: 200,
        }
    }

    /// A table whose ids 10.. are the ASCII letters, so a script maps to readable text.
    fn table_bytes() -> Vec<u8> {
        let mut out = b"SPM1".to_vec();
        let pieces: Vec<String> = ["<s>", "<pad>", "</s>", "<unk>"]
            .iter()
            .map(|s| s.to_string())
            .chain((0..6).map(|i| format!("\u{2581}w{i}")))
            .chain((0..26).map(|i| ((b'a' + i) as char).to_string()))
            .collect();
        out.extend((pieces.len() as u32).to_le_bytes());
        for (index, piece) in pieces.iter().enumerate() {
            // Later pieces merge first, so the multi-character ones win.
            out.extend((-(index as i32)).to_le_bytes());
            out.extend((piece.len() as u16).to_le_bytes());
            out.extend(piece.as_bytes());
        }
        out
    }

    #[test]
    fn the_forced_bos_frames_the_output_like_the_reference() {
        // The parity fixture's `greedy_output_ids` are `[2, tgt, content…, 2]`; this replays that
        // framing through the loop: step 0 sees `</s>`, is forced to the target, emits it back at
        // step 1 without emitting it as text, then decodes content until `</s>`.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let source = FIRST_NLLB_LANG_TOKEN;
        let target = FIRST_NLLB_LANG_TOKEN + 1;
        let mut nets = scripted(&[10, 11, EOS]);
        translate(&mut nets, &table, source, target, "a").expect("translates");
        // Step 0's scripted winner (10) is discarded by the forcing; step 1 sees script[1].
        assert_eq!(nets.steps, vec![(DECODER_START, 0), (target, 1), (11, 2)]);
    }

    #[test]
    fn step_zero_forces_the_target_token_regardless_of_the_logits() {
        // `forced_bos_token_id`: the step-0 argmax is discarded even when it has a clear winner,
        // the forced token is fed back but never emitted, and decoding proceeds from there.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let source = FIRST_NLLB_LANG_TOKEN;
        let target = FIRST_NLLB_LANG_TOKEN + 1;
        // The script's step-0 winner is 30 — it must lose to the forced target.
        let mut nets = scripted(&[30, 31, EOS]);
        translate(&mut nets, &table, source, target, "a").expect("translates");
        assert_eq!(nets.steps, vec![(DECODER_START, 0), (target, 1), (31, 2)]);
    }

    #[test]
    fn eos_ends_the_loop_and_is_not_emitted() {
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[14, 15, EOS, 16]);
        let got = translate(&mut nets, &table, FIRST_NLLB_LANG_TOKEN, FIRST_NLLB_LANG_TOKEN + 1, "a")
            .expect("translates");
        // Step 0's scripted winner (14, 'e') is discarded by the forcing, so only 'f' survives;
        // 16 is past the `</s>` and unseen.
        assert_eq!(got, "f");
        assert_eq!(nets.steps.len(), 3);
    }

    #[test]
    fn a_decoder_that_never_stops_is_capped() {
        // Greedy decoding can loop forever by repeating itself, and the KV cache is only 128
        // deep, so the loop stops rather than running past it.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        // A script of one repeated non-EOS token, longer than the cap.
        let script = vec![20u32; MAX_TOKENS + 10];
        let mut nets = scripted(&script);
        let got = translate(
            &mut nets,
            &table,
            FIRST_NLLB_LANG_TOKEN,
            FIRST_NLLB_LANG_TOKEN + 1,
            "a",
        )
        .expect("translates");
        assert_eq!(nets.steps.len(), MAX_TOKENS);
        // Step 0 is the forced target, never emitted, so the cap yields MAX_TOKENS - 1 chars.
        assert_eq!(got.chars().count(), MAX_TOKENS - 1);
    }

    #[test]
    fn text_with_nothing_to_translate_returns_nothing_and_runs_no_net() {
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[EOS]);
        let got = translate(
            &mut nets,
            &table,
            FIRST_NLLB_LANG_TOKEN,
            FIRST_NLLB_LANG_TOKEN + 1,
            "   ",
        )
        .expect("translates");
        assert_eq!(got, "");
        assert!(nets.source.is_empty(), "the encoder ran on nothing");
        assert!(nets.steps.is_empty(), "the decoder ran on nothing");
    }

    #[test]
    fn a_token_outside_the_language_range_is_refused() {
        // 256,001 through 256,202. A plain vocabulary id in either slot would be a silent
        // mistranslation.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[EOS]);
        let good = FIRST_NLLB_LANG_TOKEN;
        for (source, target) in [
            (0, good),
            (good, 2),
            (FIRST_NLLB_LANG_TOKEN - 1, good),
            (good, FIRST_NLLB_LANG_TOKEN + NLLB_LANGUAGES),
        ] {
            let error = translate(&mut nets, &table, source, target, "a")
                .expect_err("a non-language token");
            assert!(error.contains("NLLB language tokens"), "{error}");
        }
    }

    #[test]
    fn argmax_takes_the_lowest_index_of_a_tie_and_skips_nan() {
        assert_eq!(argmax(&[1.0, 3.0, 3.0, 2.0]), Some(1));
        assert_eq!(argmax(&[f32::NAN, 1.0]), Some(1));
        assert_eq!(argmax(&[f32::NAN]), None);
        assert_eq!(argmax(&[]), None);
        // Negative logits are normal — a softmax was never taken.
        assert_eq!(argmax(&[-5.0, -1.0, -9.0]), Some(1));
    }

    #[test]
    fn the_loop_replays_the_parity_fixture_framing() {
        // `scripts/ml/nllb_parity_fixture.json`, pair 1 (eng_Latn -> fra_Latn): the reference
        // `greedy_output_ids` are `[2, 256057, content…, 2]`. The script feeds the content back
        // from step 1 on (step 0 is forced); the loop must walk exactly that chain — forced BOS
        // fed back but never emitted, content in order, stop at the trailing `</s>`.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let target = 256_057u32; // fra_Latn
        let content = [17994u32, 141190, 248079, 25358, 123732, 248105, 30213, 385];
        // script[0] is discarded by the forcing; script[1..] answer steps 1... The fixture ids
        // need a full-size vocabulary, which the 200-entry helper cannot hold.
        let mut script = vec![0u32];
        script.extend(content);
        script.push(EOS);
        let mut nets = Scripted {
            script,
            source: Vec::new(),
            steps: Vec::new(),
            vocabulary: 300_000,
        };
        translate(&mut nets, &table, 256_047, target, "a").expect("translates");
        let mut want = vec![(DECODER_START, 0), (target, 1)];
        want.extend(content.iter().enumerate().map(|(i, &id)| (id, i + 2)));
        assert_eq!(nets.steps, want);
        // And the source framing matches the fixture's `encoder_input_ids` convention:
        // source-lang token leads, `</s>` trails.
        assert_eq!(nets.source.first(), Some(&256_047u32));
        assert_eq!(nets.source.last(), Some(&EOS));
    }

    #[test]
    fn the_source_token_leads_the_source_and_eos_starts_the_decoder() {
        // The trap this module exists to document: the SOURCE language leads the source, and the
        // decoder starts from `</s>` with step 0 forced to the TARGET language token. All three
        // are asserted, because swapping source and target produces fluent text in the wrong
        // language.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[EOS]);
        let source = FIRST_NLLB_LANG_TOKEN + 3;
        let target = FIRST_NLLB_LANG_TOKEN + 9;
        translate(&mut nets, &table, source, target, "a b").expect("translates");
        assert_eq!(nets.source.first(), Some(&source));
        assert_eq!(nets.source.last(), Some(&EOS));
        // The decoder was primed with `</s>`, not with the language token.
        assert_eq!(nets.steps.first(), Some(&(DECODER_START, 0)));
        // The forced-BOS token is framing, not output: it is fed back at step 1 but never
        // emitted as translated text. The script answers EOS at step 1, so nothing is produced.
        assert_eq!(nets.steps.get(1), Some(&(target, 1)));
        assert!(nets.source.contains(&source));
        assert!(!nets.source.contains(&target), "the target token is not on the source side");
    }
}
