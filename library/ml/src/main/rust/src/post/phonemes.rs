//! Text to Piper phoneme ids, through the voice's grapheme-to-phoneme dictionary.
//!
//! # No espeak-ng on device
//!
//! Piper is normally driven by espeak-ng, which is a large GPL C library. It is not needed at
//! runtime here and never was: `scripts/speech/generate_piper_dict.py` runs espeak-ng on the
//! **build machine** to turn a word list into a `<lang>-word_id.bin` lookup table, and that
//! table ships with the voice. So this module is a dictionary reader, not a phonemiser.
//!
//! # The file
//!
//! Entries back to back, no header:
//!
//! ```text
//! word '\0' id id id ... '\xff'
//! ```
//!
//! Each id is one byte indexing the voice's `phoneme_id_map`. Lookup is case-insensitive, so
//! only one case is stored. Ids 0 to 3 are reserved — pad, beginning, end and space — and
//! never appear in an entry.
//!
//! # The one convention taken on trust
//!
//! How the per-word ids are assembled into a sequence is `piper1-gpl`'s
//! `phonemes_to_ids`: a beginning marker, then every phoneme followed by a pad, then an end
//! marker, with words separated by the space phoneme. The dictionary's own generator
//! corroborates the four reserved ids, but the assembly itself happens inside the ncnn AAR
//! this replaces and so could not be diffed against a running reference. If synthesis comes
//! out fluent but subtly wrong, this is the first thing to check.

use std::collections::HashMap;

/// Separates a phoneme from the next. Piper interleaves it between every pair.
pub const PAD: u8 = 0;

/// Starts the sequence.
pub const BOS: u8 = 1;

/// Ends the sequence.
pub const EOS: u8 = 2;

/// Between words.
pub const SPACE: u8 = 3;

/// Terminates an entry's id list in the file, so it can never be a phoneme id.
const TERMINATOR: u8 = 0xFF;

/// A voice's grapheme-to-phoneme dictionary.
#[derive(Debug)]
pub struct Dictionary {
    words: HashMap<String, Vec<u8>>,
}

impl Dictionary {
    /// Parse a `<lang>-word_id.bin`.
    ///
    /// A malformed trailing entry is refused rather than ignored: a truncated download that
    /// still parsed would lose whichever words came after the truncation, and the fallback
    /// spelling would quietly stand in for them.
    pub fn parse(bytes: &[u8]) -> Result<Dictionary, String> {
        let mut words = HashMap::new();
        let mut at = 0usize;
        while at < bytes.len() {
            let separator = bytes[at..]
                .iter()
                .position(|&b| b == 0)
                .ok_or("an entry with no terminator after its word")?;
            let word = std::str::from_utf8(&bytes[at..at + separator])
                .map_err(|e| format!("an entry whose word is not UTF-8: {e}"))?;
            let rest = at + separator + 1;
            let end = bytes[rest..]
                .iter()
                .position(|&b| b == TERMINATOR)
                .ok_or_else(|| format!("the entry for {word:?} has no id terminator"))?;
            let ids = bytes[rest..rest + end].to_vec();
            if ids.iter().any(|&id| id <= SPACE) {
                return Err(format!(
                    "the entry for {word:?} contains a reserved id; ids 0..=3 are pad, \
                     beginning, end and space"
                ));
            }
            words.insert(word.to_lowercase(), ids);
            at = rest + end + 1;
        }
        if words.is_empty() {
            return Err("a dictionary with no entries".into());
        }
        Ok(Dictionary { words })
    }

    /// Words the dictionary holds.
    pub fn len(&self) -> usize {
        self.words.len()
    }

    /// Whether it holds none.
    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
    }

    /// The phonemes of one word, or `None` if it is absent.
    fn look_up(&self, word: &str) -> Option<&[u8]> {
        self.words.get(&word.to_lowercase()).map(|ids| ids.as_slice())
    }

    /// The phonemes of `word`, spelling it out character by character if it is absent.
    ///
    /// That fallback is what the ncnn path did, and it is why the generator adds every digit
    /// and single letter to the dictionary: without them an unknown word would produce
    /// nothing at all rather than a stilted reading.
    fn phonemes_of(&self, word: &str) -> Vec<u8> {
        if let Some(found) = self.look_up(word) {
            return found.to_vec();
        }
        let mut out = Vec::new();
        for character in word.chars() {
            let single = character.to_string();
            if let Some(found) = self.look_up(&single) {
                out.extend_from_slice(found);
            }
        }
        out
    }
}

/// Split `text` into the runs of characters the dictionary is keyed on.
///
/// Anything that is not alphanumeric or an apostrophe separates words. Punctuation is dropped
/// rather than voiced: the dictionary holds no entry for it, so keeping it would only trigger
/// the spelling fallback and read a full stop out as letters.
fn words(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    for character in text.chars() {
        if character.is_alphanumeric() || character == '\'' {
            current.push(character);
        } else if !current.is_empty() {
            out.push(std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        out.push(current);
    }
    out
}

/// Turn `text` into the id sequence the text encoder takes.
///
/// Returns ids in `0..=255`; the caller widens them to the encoder's input. An input with no
/// pronounceable words gives `[BOS, EOS]`, which synthesises to silence rather than failing —
/// a text-to-speech engine handed a string of emoji should say nothing, not refuse.
pub fn to_ids(text: &str, dictionary: &Dictionary) -> Vec<u8> {
    let mut ids = vec![BOS];
    let mut first = true;
    for word in words(text) {
        let phonemes = dictionary.phonemes_of(&word);
        if phonemes.is_empty() {
            continue;
        }
        if !first {
            ids.push(SPACE);
            ids.push(PAD);
        }
        first = false;
        for phoneme in phonemes {
            ids.push(phoneme);
            ids.push(PAD);
        }
    }
    ids.push(EOS);
    ids
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `word \0 ids \xff` for each pair.
    fn build(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut out = Vec::new();
        for (word, ids) in entries {
            out.extend_from_slice(word.as_bytes());
            out.push(0);
            out.extend_from_slice(ids);
            out.push(TERMINATOR);
        }
        out
    }

    fn dictionary() -> Dictionary {
        Dictionary::parse(&build(&[
            ("hello", &[10, 11, 12]),
            ("world", &[20, 21]),
            ("a", &[30]),
            ("b", &[31]),
            ("7", &[40]),
        ]))
        .expect("parses")
    }

    #[test]
    fn entries_are_read_back_in_full() {
        let d = dictionary();
        assert_eq!(d.len(), 5);
        assert_eq!(d.look_up("hello"), Some(&[10u8, 11, 12][..]));
        assert_eq!(d.look_up("world"), Some(&[20u8, 21][..]));
        assert_eq!(d.look_up("missing"), None);
    }

    #[test]
    fn lookup_ignores_case() {
        // The generator stores one case, so a capitalised sentence has to still find its
        // words — otherwise every sentence's first word would be spelled out.
        let d = dictionary();
        assert_eq!(d.look_up("HELLO"), Some(&[10u8, 11, 12][..]));
        assert_eq!(d.look_up("Hello"), Some(&[10u8, 11, 12][..]));
    }

    #[test]
    fn a_sequence_is_bracketed_and_pad_interleaved() {
        // `piper1-gpl`'s `phonemes_to_ids`: BOS, then each phoneme followed by PAD, then EOS.
        let d = dictionary();
        assert_eq!(to_ids("hello", &d), vec![BOS, 10, PAD, 11, PAD, 12, PAD, EOS]);
    }

    #[test]
    fn words_are_separated_by_the_space_phoneme() {
        let d = dictionary();
        assert_eq!(
            to_ids("a b", &d),
            vec![BOS, 30, PAD, SPACE, PAD, 31, PAD, EOS]
        );
    }

    #[test]
    fn an_unknown_word_is_spelled_out() {
        // "ab" is not an entry, but "a" and "b" are, so it reads as two letters rather than
        // vanishing. This is why the generator seeds every letter and digit.
        let d = dictionary();
        assert_eq!(to_ids("ab", &d), vec![BOS, 30, PAD, 31, PAD, EOS]);
        // A character with no entry at all contributes nothing rather than breaking the word.
        assert_eq!(to_ids("aQb", &d), vec![BOS, 30, PAD, 31, PAD, EOS]);
    }

    #[test]
    fn punctuation_separates_words_without_being_voiced() {
        let d = dictionary();
        // The comma splits, and is not itself read out.
        assert_eq!(
            to_ids("a, b", &d),
            vec![BOS, 30, PAD, SPACE, PAD, 31, PAD, EOS]
        );
        // An apostrophe stays inside a word, because contractions are dictionary entries.
        assert_eq!(words("don't stop"), vec!["don't", "stop"]);
    }

    #[test]
    fn digits_are_looked_up_like_words() {
        let d = dictionary();
        assert_eq!(to_ids("7", &d), vec![BOS, 40, PAD, EOS]);
    }

    #[test]
    fn nothing_pronounceable_gives_silence_rather_than_an_error() {
        // A string of emoji should say nothing. Refusing would make the caller decide what to
        // do about a case that is not a failure.
        let d = dictionary();
        assert_eq!(to_ids("!!!", &d), vec![BOS, EOS]);
        assert_eq!(to_ids("", &d), vec![BOS, EOS]);
    }

    #[test]
    fn a_truncated_dictionary_is_refused() {
        // A half-downloaded file that still parsed would silently lose every word after the
        // cut, and the spelling fallback would stand in for them.
        let mut bytes = build(&[("hello", &[10, 11])]);
        bytes.pop();
        let error = Dictionary::parse(&bytes).expect_err("no terminator");
        assert!(error.contains("no id terminator"), "{error}");
    }

    #[test]
    fn an_entry_holding_a_reserved_id_is_refused() {
        // Ids 0..=3 are structural. One inside an entry would inject a sentence boundary in
        // the middle of a word.
        let bytes = build(&[("hello", &[10, SPACE, 12])]);
        let error = Dictionary::parse(&bytes).expect_err("reserved id");
        assert!(error.contains("reserved id"), "{error}");
    }

    #[test]
    fn an_empty_dictionary_is_refused() {
        assert!(Dictionary::parse(&[]).is_err());
    }
}
