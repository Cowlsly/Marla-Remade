//! SMaLL-100's decode loop: source text to translated text, one token at a time.
//!
//! # What was inside the ncnn AAR
//!
//! The loop, like the tokenizer, lived in the `.so`. The two nets arrive here as a **trait** so
//! the sequencing is host-testable against stubs — the pattern `post::ocr` and `post::supertonic`
//! both use — because the order of the special tokens is where the mistakes are and none of them
//! needs a device to catch.
//!
//! # The target language token goes on the SOURCE side
//!
//! This is the one thing about SMaLL-100 that is easy to get backwards, and getting it backwards
//! produces fluent output in the wrong language rather than an error. Its distillation gives the
//! *encoder* the target language:
//!
//! ```text
//! source  = [target_language_token] ++ tokenizer(text) ++ [</s>]
//! decoder starts from </s> (id 2), not from the language token
//! ```
//!
//! M2M-100 proper puts a source-language token on the source and a target-language token on the
//! decoder; SMaLL-100 does neither of those. See [`translate`].
//!
//! # Greedy, not beam
//!
//! The reference export's `generation_config.json` says `num_beams: 5`, but that is the
//! HuggingFace default carried along by `_from_model_config` — the reference Android example
//! decodes greedily, and so does this. Beam 5 would mean five decoder states and five times the
//! work per token for a phrase-sized translation. The cost is some quality on longer sentences.

use super::sentencepiece::{Table, EOS};

/// The first of the 100 language tokens, `af`. They are contiguous from here.
///
/// Cross-checked against `Small100Model.LANG_ID`'s `FIRST_LANG_ID` and against the export's own
/// `lang_tokens`, whose minimum is this and whose ids have no gaps.
pub const FIRST_LANG_TOKEN: u32 = 128_004;

/// Language tokens the model holds.
pub const LANGUAGES: u32 = 100;

/// What the decoder is primed with, which is `</s>` and not a language token.
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

/// Translate `normalised` into the language `target_token` names.
///
/// `normalised` must already be NFKC; see [`Table::encode`]. `target_token` is one of the 100
/// language tokens, which the caller resolves from a language code — the Kotlin side already has
/// that table, and duplicating 100 entries here to look one up would be two places to get wrong.
///
/// An empty result means the text had nothing to translate, which is not a failure.
pub fn translate(
    nets: &mut dyn Nets,
    table: &Table,
    target_token: u32,
    normalised: &str,
) -> Result<String, String> {
    if !(FIRST_LANG_TOKEN..FIRST_LANG_TOKEN + LANGUAGES).contains(&target_token) {
        return Err(format!(
            "{target_token} is not one of the {LANGUAGES} language tokens from {FIRST_LANG_TOKEN}"
        ));
    }
    let body = table.encode(normalised);
    if body.is_empty() {
        return Ok(String::new());
    }

    // The language token leads the SOURCE, and the tokenizer's own `</s>` closes it.
    let mut source = Vec::with_capacity(body.len() + 2);
    source.push(target_token);
    source.extend(body);
    source.push(EOS);

    let encoded = nets.encode(&source)?;

    let mut produced: Vec<u32> = Vec::new();
    let mut token = DECODER_START;
    for step in 0..MAX_TOKENS {
        let logits = nets.decode_step(token, step, &encoded)?;
        let next = argmax(&logits)
            .ok_or_else(|| format!("the decoder returned {} logits at step {step}", logits.len()))?;
        if next == EOS {
            break;
        }
        produced.push(next);
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
    fn the_language_token_leads_the_source_and_eos_closes_it() {
        // The trap this module exists to document: SMaLL-100 puts the *target* language on the
        // *source* side, and the decoder starts from `</s>` rather than from that token. Both
        // halves are asserted, because swapping them produces fluent text in the wrong language.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[EOS]);
        let target = FIRST_LANG_TOKEN + 7;
        translate(&mut nets, &table, target, "a b").expect("translates");
        assert_eq!(nets.source.first(), Some(&target));
        assert_eq!(nets.source.last(), Some(&EOS));
        // And the decoder was primed with `</s>`, not with the language token.
        assert_eq!(nets.steps.first(), Some(&(DECODER_START, 0)));
    }

    #[test]
    fn each_step_feeds_the_previous_token_back() {
        // Greedy decoding is a chain: step n sees what step n-1 chose. Feeding the original
        // start token every time would still terminate and still produce text.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[30, 31, 32, EOS]);
        translate(&mut nets, &table, FIRST_LANG_TOKEN, "a").expect("translates");
        assert_eq!(nets.steps, vec![(DECODER_START, 0), (30, 1), (31, 2), (32, 3)]);
    }

    #[test]
    fn eos_ends_the_loop_and_is_not_emitted() {
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[14, 15, EOS, 16]);
        let got = translate(&mut nets, &table, FIRST_LANG_TOKEN, "a").expect("translates");
        // ids 14 and 15 are 'e' and 'f' in the fixture table; 16 is past the `</s>` and unseen.
        assert_eq!(got, "ef");
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
        let got = translate(&mut nets, &table, FIRST_LANG_TOKEN, "a").expect("translates");
        assert_eq!(nets.steps.len(), MAX_TOKENS);
        assert_eq!(got.chars().count(), MAX_TOKENS);
    }

    #[test]
    fn text_with_nothing_to_translate_returns_nothing_and_runs_no_net() {
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[EOS]);
        let got = translate(&mut nets, &table, FIRST_LANG_TOKEN, "   ").expect("translates");
        assert_eq!(got, "");
        assert!(nets.source.is_empty(), "the encoder ran on nothing");
        assert!(nets.steps.is_empty(), "the decoder ran on nothing");
    }

    #[test]
    fn a_token_outside_the_language_range_is_refused() {
        // 128,004 through 128,103. A plain vocabulary id here would be a silent mistranslation.
        let bytes = table_bytes();
        let table = Table::parse(&bytes).expect("parses");
        let mut nets = scripted(&[EOS]);
        for wrong in [0, 2, FIRST_LANG_TOKEN - 1, FIRST_LANG_TOKEN + LANGUAGES] {
            let error =
                translate(&mut nets, &table, wrong, "a").expect_err("a non-language token");
            assert!(error.contains("language tokens"), "{error}");
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
}
