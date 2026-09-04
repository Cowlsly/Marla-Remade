//! Maia3's 4352 move logits, assembled from the two tensors [`crate::nets::maia`] emits.
//!
//! Pure arithmetic on model outputs — no chess knowledge, no legality, no sampling. The app
//! needs its own legal move list for all three of those, so they stay in Kotlin and this
//! stays here, where the rest of the repo puts a decode that only the model's own layout
//! explains (cf. [`super::whisper`], [`super::translate`]).
//!
//! # The vocabulary
//!
//! * `0..4095` — `from_square * 64 + to_square`, square `rank * 8 + file`, a1 = 0, h8 = 63.
//!   This is `scores_base` read row-major, so it is a straight copy.
//! * `4096..4351` — `4096 + from_file * 32 + to_file * 4 + piece`, piece in q, r, b, n.
//!
//! A promotion logit is its underlying from-to score **plus** a per-destination-file,
//! per-piece bias. Promotions are always rank 7 to rank 8 because the board is mirrored for
//! black, so the from-square is `48 + from_file` and the to-square `56 + to_file`.
//!
//! # The two scales do not cancel
//!
//! `scores_base` is already divided by `sqrt(head_hid_dim)` — [`crate::nets::maia`] gets
//! that from [`crate::nets::Builder::attn_scores`]'s own scale — while the promotion bias is
//! *multiplied* by the same `sqrt(head_hid_dim)`. That is upstream's arithmetic and not a
//! mistake to tidy up: the bias is a raw projection and the score is a scaled dot product,
//! and the two are added in that state.

use crate::nets::maia::{HEAD_HIDDEN, MOVES, PROMOTIONS, SQUARES};

/// Files on a board, and so promotion source and destination squares.
const FILES: usize = 8;

/// Assemble the move logits from a forward pass's two outputs.
///
/// `scores` is `[1, 64, 64]` read row-major as `from * 64 + to`, and `promo` is
/// `[PROMOTIONS, 1, 64]` — the promotion projection over **every** square, of which only
/// the eight on rank 8 are used. The head runs over all 64 because a `1 x 1` convolution is
/// per position and there is no cheap device-side gather of eight columns; the waste is 224
/// multiply-accumulates.
pub fn logits(scores: &[f32], promo: &[f32]) -> Result<Vec<f32>, String> {
    let squares = SQUARES as usize;
    if scores.len() != squares * squares {
        return Err(format!("{} scores, not {}", scores.len(), squares * squares));
    }
    if promo.len() != PROMOTIONS as usize * squares {
        return Err(format!(
            "{} promotion values, not {}",
            promo.len(),
            PROMOTIONS as usize * squares
        ));
    }

    let mut out = Vec::with_capacity(MOVES);
    out.extend_from_slice(scores);

    let scale = (HEAD_HIDDEN as f32).sqrt();
    for from_file in 0..FILES {
        let from_square = 48 + from_file;
        for to_file in 0..FILES {
            let to_square = 56 + to_file;
            let base = scores[from_square * squares + to_square];
            for piece in 0..PROMOTIONS as usize {
                // `promo` is channel-major: piece `p` at square `s` is `p * 64 + s`.
                out.push(base + promo[piece * squares + to_square] * scale);
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Scores that identify their own from-to pair, and promotion biases that identify
    /// their own piece and square, so a transposed index is visible rather than plausible.
    fn fixture() -> (Vec<f32>, Vec<f32>) {
        let squares = SQUARES as usize;
        let scores: Vec<f32> =
            (0..squares * squares).map(|i| i as f32).collect();
        let promo: Vec<f32> = (0..PROMOTIONS as usize * squares)
            .map(|i| (i as f32) / (HEAD_HIDDEN as f32).sqrt())
            .collect();
        (scores, promo)
    }

    #[test]
    fn the_first_4096_logits_are_the_score_map_unchanged() {
        let (scores, promo) = fixture();
        let out = logits(&scores, &promo).unwrap();
        assert_eq!(out.len(), MOVES);
        assert_eq!(&out[..4096], &scores[..]);
    }

    #[test]
    fn a_promotion_logit_is_its_from_to_score_plus_the_scaled_bias() {
        let (scores, promo) = fixture();
        let out = logits(&scores, &promo).unwrap();
        let squares = SQUARES as usize;
        // e7e8q: from file 4 (e7 = square 52), to file 4 (e8 = square 60), piece 0.
        let at = 4096 + 4 * 32 + 4 * 4;
        let want = scores[52 * squares + 60] + promo[60] * (HEAD_HIDDEN as f32).sqrt();
        assert!((out[at] - want).abs() < 1e-3, "{} vs {want}", out[at]);
        // The knight underpromotion on the same pair differs only by its bias channel.
        let knight = out[at + 3] - out[at];
        let expected = (promo[3 * squares + 60] - promo[60]) * (HEAD_HIDDEN as f32).sqrt();
        assert!((knight - expected).abs() < 1e-3, "{knight} vs {expected}");
    }

    #[test]
    fn every_promotion_index_maps_to_rank_seven_and_rank_eight() {
        // All 256 promotion logits, checked against the from-to score they are built on.
        // A source square off rank 7 or a destination off rank 8 would show up as a
        // mismatch here rather than as a legal-looking but wrong move.
        let (scores, promo) = fixture();
        let out = logits(&scores, &promo).unwrap();
        let squares = SQUARES as usize;
        for from_file in 0..FILES {
            for to_file in 0..FILES {
                for piece in 0..PROMOTIONS as usize {
                    let at = 4096 + from_file * 32 + to_file * 4 + piece;
                    let base = scores[(48 + from_file) * squares + (56 + to_file)];
                    let bias = promo[piece * squares + (56 + to_file)]
                        * (HEAD_HIDDEN as f32).sqrt();
                    assert!(
                        (out[at] - (base + bias)).abs() < 1e-3,
                        "index {at}: {} vs {}",
                        out[at],
                        base + bias
                    );
                }
            }
        }
    }

    #[test]
    fn the_bias_is_multiplied_by_the_scale_rather_than_divided_by_it() {
        // A zero score map and a single unit bias, so the scale is the only thing left.
        let squares = SQUARES as usize;
        let scores = vec![0.0f32; squares * squares];
        let mut promo = vec![0.0f32; PROMOTIONS as usize * squares];
        promo[56] = 1.0;
        let out = logits(&scores, &promo).unwrap();
        // a7a8q.
        assert!((out[4096] - (HEAD_HIDDEN as f32).sqrt()).abs() < 1e-3, "{}", out[4096]);
    }

    #[test]
    fn a_tensor_of_the_wrong_length_is_refused() {
        let (scores, promo) = fixture();
        assert!(logits(&scores[..100], &promo).is_err());
        assert!(logits(&scores, &promo[..100]).is_err());
    }
}
