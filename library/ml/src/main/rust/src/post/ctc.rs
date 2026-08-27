//! CTC greedy decoding, and the character table it decodes against.
//!
//! # The label space
//!
//! PP-OCRv5's recogniser emits [`LOGITS`] = 838 values per timestep. PaddleOCR's
//! `CTCLabelDecode` builds that list as
//!
//! ```text
//!     index 0        the CTC blank
//!     index 1..=836  the 836 entries of the character dictionary, in file order
//!     index 837      the space, which `use_space_char` appends
//! ```
//!
//! so a label's character is `dictionary[index - 1]` — the off-by-one the plan warned
//! about, and the reason [`Dictionary::label`] exists rather than callers indexing.
//!
//! The dictionary ships as `ppocr_keys.txt`, one character per line, extracted from the
//! export's own `inference.yml` (`PostProcess.character_dict`). **Its order is
//! load-bearing**: the model's final layer emits logits positionally, so a reordered file
//! decodes to fluent-looking nonsense rather than failing. The space is *not* in the file
//! — no entry in it is whitespace, checked at load — so it is appended here, which keeps
//! the asset free of trailing-whitespace hazards that a text editor or a line-ending
//! conversion would silently eat.
//!
//! # Greedy, not beam search
//!
//! `ppocrv5.cpp` takes the argmax per timestep and collapses runs; there is no language
//! model and no beam. This reproduces that.

/// Logits the recogniser emits per timestep: blank + 836 characters + space.
pub const LOGITS: usize = 838;
/// Entries the dictionary file must hold.
pub const DICTIONARY_ENTRIES: usize = 836;

/// The character table, in label order.
#[derive(Debug)]
pub struct Dictionary {
    /// One entry per label from 1 upwards; index 0 (the blank) has no character.
    characters: Vec<String>,
}

impl Dictionary {
    /// Parse `ppocr_keys.txt`: one character per line, and the space appended.
    ///
    /// Rejects a file of the wrong length rather than decoding against a truncated table,
    /// which would shift every character above the missing one.
    pub fn parse(text: &str) -> Result<Dictionary, String> {
        // `lines` handles both endings, which matters because this asset is text in a
        // repo with mixed CRLF and LF.
        let mut characters: Vec<String> = text
            .lines()
            .map(|line| line.trim_end_matches('\r').to_string())
            .filter(|line| !line.is_empty())
            .collect();
        if characters.len() != DICTIONARY_ENTRIES {
            return Err(format!(
                "{} dictionary entries, expected {DICTIONARY_ENTRIES}",
                characters.len()
            ));
        }
        if let Some(bad) = characters.iter().position(|c| c.chars().count() != 1) {
            return Err(format!("dictionary entry {bad} is not a single character"));
        }
        // `use_space_char`, which is what makes the label space 838 rather than 837.
        characters.push(" ".to_string());
        Ok(Dictionary { characters })
    }

    /// The character for `label`, or `None` for the blank and for anything out of range.
    pub fn label(&self, label: usize) -> Option<&str> {
        if label == 0 {
            return None;
        }
        self.characters.get(label - 1).map(String::as_str)
    }

    /// How many labels there are, including the blank. Should equal [`LOGITS`].
    pub fn labels(&self) -> usize {
        self.characters.len() + 1
    }
}

/// One decoded line: its text and the mean confidence of the labels that produced it.
#[derive(Clone, Debug, PartialEq)]
pub struct Decoded {
    /// The text, with repeats collapsed and blanks removed.
    pub text: String,
    /// Mean probability of the kept timesteps, or 0 when nothing was kept.
    pub confidence: f32,
}

/// Collapse `logits` into text: argmax per timestep, drop blanks, drop repeats.
///
/// `logits` is `steps * LOGITS`, row-major — one row per timestep, already through the
/// graph's softmax. The classic CTC collapse: a label repeated across consecutive
/// timesteps is one character, and a blank between two identical labels is what separates
/// them into two.
pub fn decode(logits: &[f32], dictionary: &Dictionary) -> Result<Decoded, String> {
    let labels = dictionary.labels();
    if labels != LOGITS {
        return Err(format!("a dictionary of {labels} labels, expected {LOGITS}"));
    }
    if !logits.len().is_multiple_of(labels) {
        return Err(format!(
            "{} logits is not a whole number of {labels}-wide timesteps",
            logits.len()
        ));
    }

    let mut text = String::new();
    let mut total = 0.0f64;
    let mut kept = 0u32;
    let mut previous = usize::MAX;

    for step in logits.chunks_exact(labels) {
        let (best, &score) = step
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.total_cmp(b))
            .ok_or("an empty timestep")?;
        // A repeat of the immediately preceding label is the same character held across
        // two frames. A blank resets that, which is how "aa" is spelled at all.
        if best != previous {
            if let Some(character) = dictionary.label(best) {
                text.push_str(character);
                total += score as f64;
                kept += 1;
            }
        }
        previous = best;
    }

    let confidence = if kept > 0 { (total / kept as f64) as f32 } else { 0.0 };
    Ok(Decoded { text, confidence })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A dictionary of `a`, `b`, `c`, ... padded to the real length so `decode` accepts
    /// it, since a short one is refused on purpose.
    fn dictionary() -> Dictionary {
        let mut text = String::new();
        for index in 0..DICTIONARY_ENTRIES {
            // Printable ASCII cycling, so the first few entries are predictable.
            let code = b'a' + (index % 26) as u8;
            text.push(code as char);
            text.push('\n');
        }
        Dictionary::parse(&text).expect("the fixture dictionary parses")
    }

    /// One timestep whose argmax is `label`, at probability `score`.
    fn step(label: usize, score: f32) -> Vec<f32> {
        let mut row = vec![0.0; LOGITS];
        row[label] = score;
        row
    }

    fn logits(steps: &[(usize, f32)]) -> Vec<f32> {
        steps.iter().flat_map(|&(l, s)| step(l, s)).collect()
    }

    #[test]
    fn the_label_space_is_blank_then_the_dictionary_then_a_space() {
        let d = dictionary();
        assert_eq!(d.labels(), LOGITS);
        // 0 is the blank and decodes to nothing.
        assert_eq!(d.label(0), None);
        // 1 is the *first* dictionary entry: the `index - 1` the plan flagged.
        assert_eq!(d.label(1), Some("a"));
        assert_eq!(d.label(2), Some("b"));
        // The last label is the appended space, not a dictionary entry.
        assert_eq!(d.label(LOGITS - 1), Some(" "));
        assert_eq!(d.label(LOGITS), None);
    }

    #[test]
    fn a_run_of_one_label_collapses_to_one_character() {
        let d = dictionary();
        let got = decode(&logits(&[(1, 0.9), (1, 0.8), (1, 0.7)]), &d).expect("decodes");
        assert_eq!(got.text, "a");
        // Only the first of the run is scored, which is what the reference does.
        assert!((got.confidence - 0.9).abs() < 1e-6, "{}", got.confidence);
    }

    #[test]
    fn a_blank_between_two_identical_labels_keeps_both() {
        // The whole reason CTC has a blank: without it "aa" is indistinguishable from a
        // held "a".
        let d = dictionary();
        let got = decode(&logits(&[(1, 0.9), (0, 0.6), (1, 0.8)]), &d).expect("decodes");
        assert_eq!(got.text, "aa");
    }

    #[test]
    fn blanks_contribute_no_characters_and_no_confidence() {
        let d = dictionary();
        let got = decode(&logits(&[(0, 0.99), (0, 0.99)]), &d).expect("decodes");
        assert_eq!(got.text, "");
        assert_eq!(got.confidence, 0.0);
    }

    #[test]
    fn distinct_adjacent_labels_both_survive_without_a_blank() {
        let d = dictionary();
        let got = decode(&logits(&[(1, 0.9), (2, 0.8), (3, 0.7)]), &d).expect("decodes");
        assert_eq!(got.text, "abc");
        assert!((got.confidence - 0.8).abs() < 1e-6, "{}", got.confidence);
    }

    #[test]
    fn the_space_label_decodes_to_a_space() {
        let d = dictionary();
        let got = decode(&logits(&[(1, 0.9), (LOGITS - 1, 0.8), (2, 0.7)]), &d).expect("decodes");
        assert_eq!(got.text, "a b");
    }

    #[test]
    fn the_argmax_is_taken_across_the_whole_row() {
        // A row where the winner is not the only non-zero, so a decoder that took the
        // first positive value rather than the largest would differ.
        let d = dictionary();
        let mut row = vec![0.0; LOGITS];
        row[1] = 0.2;
        row[5] = 0.7;
        row[9] = 0.1;
        let got = decode(&row, &d).expect("decodes");
        assert_eq!(got.text, "e");
    }

    #[test]
    fn a_dictionary_of_the_wrong_length_is_refused() {
        let error = Dictionary::parse("a\nb\nc\n").expect_err("too short");
        assert!(error.contains("3 dictionary entries"), "{error}");
    }

    #[test]
    fn a_multi_character_dictionary_entry_is_refused() {
        let mut text = String::new();
        for index in 0..DICTIONARY_ENTRIES {
            text.push_str(if index == 4 { "ab" } else { "a" });
            text.push('\n');
        }
        let error = Dictionary::parse(&text).expect_err("a two-character entry");
        assert!(error.contains("entry 4"), "{error}");
    }

    #[test]
    fn carriage_returns_are_tolerated() {
        // The repo has mixed line endings and this asset is text, so a checkout that
        // converted it must still load.
        let mut text = String::new();
        for _ in 0..DICTIONARY_ENTRIES {
            text.push_str("a\r\n");
        }
        let d = Dictionary::parse(&text).expect("parses with CRLF");
        assert_eq!(d.label(1), Some("a"));
    }

    #[test]
    fn a_ragged_logit_array_is_refused() {
        let d = dictionary();
        let error = decode(&[0.0; LOGITS + 3], &d).expect_err("ragged");
        assert!(error.contains("not a whole number"), "{error}");
    }
}
