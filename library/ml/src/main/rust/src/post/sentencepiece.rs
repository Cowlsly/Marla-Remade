//! SMaLL-100's SentencePiece tokenizer: text to token ids and back.
//!
//! # What was inside the ncnn AAR
//!
//! There is no app-side tokenizer today — encode and decode both live in the `.so`, so a wrong
//! piece boundary could not be read, let alone tested. This is that code, in the open.
//!
//! # It is BPE, and the "scores" are merge ranks
//!
//! The model file is named `sentencepiece.bpe.model` and its `trainer_spec.model_type` is 2, BPE.
//! Its pieces carry scores of 0, -1, -2, ... which are **negative merge ranks** rather than log
//! probabilities: `,` scores -119149 because it is a very late merge. So encoding is greedy
//! pairwise merging — repeatedly join the adjacent pair whose concatenation is in the vocabulary
//! with the highest score, leftmost on a tie — and not the Viterbi a Unigram model would need.
//! Reading the scores as log probabilities and running Viterbi produces plausible pieces and the
//! wrong ones.
//!
//! # Normalisation is the caller's job
//!
//! The model's `normalizer_spec` is `nmt_nfkc` with a 237 KB `precompiled_charsmap`. Carrying
//! that in the APK is not worth it when the platform has
//! `java.text.Normalizer.normalize(text, Form.NFKC)`, so [`Table::encode`] takes text that is
//! **already NFKC** and does the rest: collapse whitespace runs, trim, prefix the metaspace and
//! substitute it for every space. Checked against the real `sentencepiece` on 20 samples across
//! Latin, Cyrillic, Arabic, Devanagari, Han, Kana, Hangul, fullwidth forms and emoji — NFKC plus
//! that whitespace handling agrees with `nmt_nfkc` on all of them.
//!
//! The table itself is `scripts/ml/small100_tokenizer.py`'s output; see it for the format.

use std::collections::HashMap;

/// The magic the converter writes.
const MAGIC: &[u8; 4] = b"SPM1";

/// SentencePiece's word-start marker, `U+2581 LOWER ONE EIGHTH BLOCK`.
pub const METASPACE: char = '\u{2581}';

/// fairseq's specials, which the table is checked against rather than assumed to hold.
pub const BOS: u32 = 0;
/// The padding id.
pub const PAD: u32 = 1;
/// End of sentence, which the tokenizer appends and the decode loop stops on.
pub const EOS: u32 = 2;
/// The id for a character no piece covers.
pub const UNK: u32 = 3;

/// A borrowed view of the vocabulary: piece bytes, merge ranks and ids.
pub struct Table<'a> {
    /// One `(offset, length, score)` per id, so an id indexes directly.
    pieces: Vec<(u32, u16, i32)>,
    blob: &'a [u8],
    /// Piece bytes to `(id, score)`. Empty pieces are absent, and the lowest id wins a duplicate.
    by_piece: HashMap<&'a [u8], (u32, i32)>,
}

impl<'a> Table<'a> {
    /// Parse the converter's output. Borrows `bytes`, so nothing is copied.
    pub fn parse(bytes: &'a [u8]) -> Result<Table<'a>, String> {
        if bytes.len() < 8 || &bytes[0..4] != MAGIC {
            return Err("not a SPM1 tokenizer table".into());
        }
        let count = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]) as usize;
        let mut pieces = Vec::with_capacity(count);
        let mut by_piece = HashMap::with_capacity(count);
        let mut at = 8usize;
        for id in 0..count {
            let head = bytes
                .get(at..at + 6)
                .ok_or_else(|| format!("the table ends inside piece {id}"))?;
            let score = i32::from_le_bytes([head[0], head[1], head[2], head[3]]);
            let length = u16::from_le_bytes([head[4], head[5]]);
            at += 6;
            let piece = bytes
                .get(at..at + length as usize)
                .ok_or_else(|| format!("piece {id} runs past the table"))?;
            pieces.push((at as u32, length, score));
            at += length as usize;
            // Eight of SMaLL-100's entries are empty padding, and would otherwise match every
            // lookup of the empty string.
            if !piece.is_empty() {
                by_piece.entry(piece).or_insert((id as u32, score));
            }
        }
        if at != bytes.len() {
            return Err(format!("{} bytes left after {count} pieces", bytes.len() - at));
        }
        let table = Table { pieces, blob: bytes, by_piece };
        for (id, name) in [(BOS, "<s>"), (PAD, "<pad>"), (EOS, "</s>"), (UNK, "<unk>")] {
            match table.piece(id) {
                Some(found) if found == name.as_bytes() => {}
                other => {
                    return Err(format!(
                        "id {id} is {:?}, not {name}",
                        other.map(String::from_utf8_lossy)
                    ))
                }
            }
        }
        Ok(table)
    }

    /// Entries the table holds, which is also one past the highest id.
    pub fn len(&self) -> usize {
        self.pieces.len()
    }

    /// Whether the table is empty, which a parsed one never is.
    pub fn is_empty(&self) -> bool {
        self.pieces.is_empty()
    }

    /// One id's piece as UTF-8 bytes.
    pub fn piece(&self, id: u32) -> Option<&'a [u8]> {
        let &(offset, length, _) = self.pieces.get(id as usize)?;
        self.blob.get(offset as usize..offset as usize + length as usize)
    }

    /// Token ids for text that is **already NFKC-normalised**.
    ///
    /// Does not append [`EOS`] or prepend a language token: the decode loop owns the
    /// sequence, because SMaLL-100 puts the *target* language token on the **source** side and
    /// that is easy to get backwards.
    pub fn encode(&self, normalised: &str) -> Vec<u32> {
        let prepared = self.prepare(normalised);
        if prepared.is_empty() {
            return Vec::new();
        }
        // Symbols as byte ranges into `prepared`, so a merge is a range join rather than a
        // string concatenation. A sentence of 200 characters does 200 merges of 200 lookups.
        let mut symbols: Vec<(usize, usize)> = prepared
            .char_indices()
            .map(|(at, c)| (at, at + c.len_utf8()))
            .collect();
        loop {
            let mut best: Option<(i32, usize)> = None;
            for index in 0..symbols.len().saturating_sub(1) {
                let joined = &prepared[symbols[index].0..symbols[index + 1].1];
                let Some(&(_, score)) = self.by_piece.get(joined.as_bytes()) else {
                    continue;
                };
                // Highest score wins, and the leftmost of equal scores, which is what
                // sentencepiece does. `>` rather than `>=` keeps the leftmost.
                if best.is_none_or(|(top, _)| score > top) {
                    best = Some((score, index));
                }
            }
            let Some((_, index)) = best else { break };
            symbols[index].1 = symbols[index + 1].1;
            symbols.remove(index + 1);
        }
        symbols
            .iter()
            .map(|&(from, to)| {
                self.by_piece
                    .get(&prepared.as_bytes()[from..to])
                    .map_or(UNK, |&(id, _)| id)
            })
            .collect()
    }

    /// NFKC-normalised text as sentencepiece feeds it to the merge loop.
    ///
    /// Whitespace runs collapse to one space and the ends are trimmed
    /// (`remove_extra_whitespaces`), then every space becomes the metaspace and one is prefixed
    /// (`add_dummy_prefix`) so a word at the start of a sentence tokenises like the same word in
    /// the middle of one.
    fn prepare(&self, normalised: &str) -> String {
        let mut out = String::with_capacity(normalised.len() + METASPACE.len_utf8());
        for word in normalised.split_whitespace() {
            out.push(METASPACE);
            out.push_str(word);
        }
        out
    }

    /// Text for a run of ids, with the metaspace turned back into spaces.
    ///
    /// Ids past the table are skipped rather than replacing the whole string: a decode loop that
    /// produced one bad token should lose a word, not the translation.
    pub fn decode(&self, ids: &[u32]) -> String {
        let mut out = String::new();
        for &id in ids {
            if let Some(piece) = self.piece(id) {
                out.push_str(&String::from_utf8_lossy(piece));
            }
        }
        out.replace(METASPACE, " ").trim().to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The converter's format, for a handful of pieces.
    ///
    /// The scores are merge ranks, so a **higher** number merges first. fairseq's four specials
    /// have to be at 0..3 for `parse` to accept the table.
    fn table(extra: &[(&str, i32)]) -> Vec<u8> {
        let mut out = MAGIC.to_vec();
        let pieces: Vec<(&str, i32)> = [("<s>", 0), ("<pad>", 0), ("</s>", 0), ("<unk>", 0)]
            .into_iter()
            .chain(extra.iter().copied())
            .collect();
        out.extend((pieces.len() as u32).to_le_bytes());
        for (piece, score) in pieces {
            out.extend(score.to_le_bytes());
            out.extend((piece.len() as u16).to_le_bytes());
            out.extend(piece.as_bytes());
        }
        out
    }

    #[test]
    fn a_table_rejects_one_whose_specials_are_not_fairseqs() {
        let mut bytes = MAGIC.to_vec();
        bytes.extend(1u32.to_le_bytes());
        bytes.extend(0i32.to_le_bytes());
        bytes.extend(3u16.to_le_bytes());
        bytes.extend(b"abc");
        let error = Table::parse(&bytes).err().expect("wrong specials");
        assert!(error.contains("not <s>"), "{error}");
    }

    #[test]
    fn a_table_rejects_a_truncated_record() {
        let mut bytes = table(&[("a", -1)]);
        bytes.truncate(bytes.len() - 1);
        let error = Table::parse(&bytes).err().expect("truncated");
        assert!(error.contains("past the table") || error.contains("ends inside"), "{error}");
    }

    #[test]
    fn the_highest_rank_merges_first() {
        // "ab" scores above "bc", so `abc` must merge to `ab` + `c` and not `a` + `bc`. Both
        // give three-then-two symbols, so only the ids tell them apart — which is exactly how a
        // rank comparison written backwards survives every shape check.
        let bytes = table(&[
            ("\u{2581}", -50),
            ("a", -50),
            ("b", -50),
            ("c", -50),
            ("ab", -1),
            ("bc", -2),
        ]);
        let parsed = Table::parse(&bytes).expect("parses");
        let ids = parsed.encode("abc");
        // ids: metaspace 4, a 5, b 6, c 7, ab 8, bc 9. The dummy prefix leads.
        assert_eq!(ids, vec![4, 8, 7]);
    }

    #[test]
    fn equal_ranks_merge_leftmost() {
        // "aa" matches at two places in "▁aaa" and at the same rank. sentencepiece takes the
        // left one, giving `▁` + `aa` + `a`; taking the right one would give `▁` + `a` + `aa`,
        // which is the same three symbols and the same length with two ids transposed.
        let bytes = table(&[("\u{2581}", -50), ("a", -50), ("aa", -1)]);
        let parsed = Table::parse(&bytes).expect("parses");
        // ids: metaspace 4, a 5, aa 6.
        assert_eq!(parsed.encode("aaa"), vec![4, 6, 5]);
        assert_ne!(parsed.encode("aaa"), vec![4, 5, 6]);
    }

    #[test]
    fn a_character_with_no_piece_becomes_unk() {
        // There is no piece for 'z', and no unknown-token substitution to fall back on.
        let bytes = table(&[("\u{2581}", -50), ("a", -50)]);
        let parsed = Table::parse(&bytes).expect("parses");
        assert_eq!(parsed.encode("az"), vec![4, 5, UNK]);
    }

    #[test]
    fn whitespace_collapses_and_every_word_gets_a_metaspace() {
        // Tabs, newlines and runs of spaces are one separator, the ends are trimmed, and each
        // word carries its own leading metaspace — which is why `the` mid-sentence and `The` at
        // the start tokenise alike.
        let bytes = table(&[("\u{2581}", -50), ("a", -50), ("b", -50)]);
        let parsed = Table::parse(&bytes).expect("parses");
        // Two words: metaspace a, metaspace b.
        assert_eq!(parsed.encode("  a \t\n b  "), vec![4, 5, 4, 6]);
        assert_eq!(parsed.encode("   "), Vec::<u32>::new());
        assert_eq!(parsed.encode(""), Vec::<u32>::new());
    }

    #[test]
    fn decode_turns_the_metaspace_back_into_spaces_and_trims() {
        let bytes = table(&[("\u{2581}the", -1), ("\u{2581}cat", -2), ("s", -3)]);
        let parsed = Table::parse(&bytes).expect("parses");
        assert_eq!(parsed.decode(&[4, 5, 6]), "the cats");
        // An id past the table loses a word rather than the sentence.
        assert_eq!(parsed.decode(&[4, 999, 5]), "the cat");
        assert_eq!(parsed.decode(&[]), "");
    }

    /// The real vocabulary, against ids taken from `sentencepiece` itself.
    ///
    /// Skipped rather than ignored: the table is a runtime download rather than a checked-in
    /// asset, so `SMALL100_TOKENIZER` is how you point at one. `scripts/ml/fetch_small100.py`
    /// builds it, and prints the command.
    #[test]
    fn the_real_vocabulary_agrees_with_sentencepiece() {
        let Ok(path) = std::env::var("SMALL100_TOKENIZER") else {
            return;
        };
        let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
        let parsed = Table::parse(&bytes).expect("the real table parses");
        assert_eq!(parsed.len(), 128_112);
        // Every one of these came out of `sentencepiece.SentencePieceProcessor` on the shipped
        // model, mapped through fairseq's vocabulary order.
        let cases: [(&str, &[u32]); 6] = [
            ("Hello, world!", &[65_761, 4, 55_185, 30]),
            ("café naïve", &[40_244, 18, 6_460, 470]),
            ("  multiple   spaces\tand\nnewlines  ", &[119_683, 654, 44_628, 1_019, 19_420, 116_356]),
            ("한국어", &[13_740, 2_254]),
            ("emoji 🙂 done", &[21_761, 720, 6_459, 111_108]),
            // Fullwidth forms, already folded by the caller's NFKC.
            ("Full A", &[26_033, 58]),
        ];
        for (text, want) in cases {
            assert_eq!(parsed.encode(text), want, "encoding {text:?}");
        }
        // And a round trip, which is what the decode loop ends with.
        assert_eq!(parsed.decode(&[1_197, 6_308]), "the cat");
    }
}
