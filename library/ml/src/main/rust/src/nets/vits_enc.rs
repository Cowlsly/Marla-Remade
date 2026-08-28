//! Piper's VITS text encoder: the `enc_p` module, phonemes to a latent distribution.
//!
//! # What it is
//!
//! An embedding lookup over 130 phoneme symbols, six post-norm transformer layers with
//! **relative** position attention, and a 1x1 projection to twice the channel count — the
//! mean and log-variance of the prior the flow and the vocoder sample from.
//!
//! 865 ONNX nodes and 111 tensors. Most of those nodes are not arithmetic: the export spends
//! 48 `Pad`s, 96 `Reshape`s and 42 `Slice`s building the `[heads, T, 2T-1]` relative form of
//! its attention and skewing it back, and none of that survives here. See
//! [`super::Kind::AttnScoresRelative`] for why nine taps is the same function.
//!
//! # Two things checked against the export rather than assumed
//!
//! **The mask is not needed.** Six `Where` nodes write `-10000` into the scores wherever the
//! padding mask is zero. At `input_lengths == T` they are bit-identically the identity, so an
//! utterance processed on its own does not need them — and this runtime speaks one utterance
//! at a time. Batching would need the mask back.
//!
//! **The scale is in the table.** VITS multiplies the embedding by `sqrt(d_model)`. The
//! converter folds that into the table, which is one op fewer and one rounding fewer:
//! `round(table * sqrt(d))` is closer than `round(table) * sqrt(d)`.
//!
//! # Where it sits
//!
//! `enc_p` is one of four modules the VITS export splits into, and one of the three that are
//! static enough to be a plan. The fourth, `dp`, samples noise and builds a monotonic
//! alignment out of `NonZero` and `ScatterND`; it belongs on the CPU. See
//! [`super::vits_dec`] for the whole picture.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Phoneme symbols in the table, from the voice's `.onnx.json` `num_symbols`.
pub const SYMBOLS: u32 = 130;

/// Channels throughout the encoder, VITS's `hidden_channels`.
pub const D_MODEL: u32 = 192;

/// Attention heads. `D_MODEL / HEADS` is 96, which is also the relative table's width.
pub const HEADS: u32 = 2;

/// The feed-forward layer's inner width.
pub const FFN: u32 = 768;

/// Transformer layers.
pub const LAYERS: usize = 6;

/// Entries in each attention layer's relative position table: `2 * window + 1` at
/// `window_size` 4.
pub const OFFSETS: u32 = 9;

/// Output channels: the mean and the log-variance of the prior, concatenated.
pub const STATS: u32 = D_MODEL * 2;

/// The epsilon in every one of the twelve layer norms.
const EPSILON: f32 = 1e-5;

/// Tensors the `.maml` must hold.
///
/// The embedding table, then eighteen per layer, then the projection's pair:
/// `1 + 6 * 18 + 2`. The eighteen are three attention projections (six), the two relative
/// tables (two — one tensor each, not a pair), the output projection (two), a layer norm
/// (two), the two feed-forward convolutions (four) and the second layer norm (two).
pub const TENSORS: usize = 111;

/// Hands out `.maml` tensor indices in the order the layers appear.
///
/// Unlike the other net modules this one deals in both pairs and singles, because a relative
/// position table is one tensor with no bias beside it.
struct Layers {
    next: usize,
}

impl Layers {
    /// A weight and the bias after it.
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }

    /// A lone tensor.
    fn take_one(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }
}

/// A 1x1 convolution along the sequence, which is what every projection here is.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// A `1 x 3` convolution padded to hold the length, which is what the feed-forward uses.
fn along(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 3), (1, 1), (1, 1), (0, 1, 0, 1), 1, act)
}

/// Build the encoder for an utterance of `phonemes` symbols.
///
/// The input is `[1, 1, phonemes]` of symbol ids, held as fp16 — exact, because fp16 carries
/// every integer to 2048 and there are 130 symbols. The output is `[384, 1, phonemes]`: the
/// prior's mean in the first 192 channels and its log-variance in the rest.
pub fn build(weights: &dyn WeightSource, phonemes: u32) -> Result<Plan, String> {
    if phonemes == 0 {
        return Err("an encoder pass over no phonemes".into());
    }

    let l = &mut Layers { next: 0 };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    let ids = b.input(Shape::new(1, 1, phonemes));
    let mut x = b.embed(ids, l.take_one(), SYMBOLS, D_MODEL);

    for _ in 0..LAYERS {
        // Attention. The three projections read the same tensor, and the relative tables
        // sit between the value projection and the output one, which is the order the
        // export's nodes appear in.
        let q = point(b, l, x, D_MODEL, Act::None);
        let k = point(b, l, x, D_MODEL, Act::None);
        let v = point(b, l, x, D_MODEL, Act::None);
        let scores = b.attn_scores_relative(q, k, HEADS, l.take_one(), OFFSETS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply_relative(probs, v, HEADS, l.take_one(), OFFSETS);
        let attended = point(b, l, mixed, D_MODEL, Act::None);
        // Post-norm: the residual is added first and normalised after, which is what the
        // export does and the opposite of the recogniser's transformer.
        let residual = b.add(x, attended);
        x = b.layer_norm(residual, l.take(), EPSILON);

        // Feed-forward, both convolutions three wide.
        let inner = along(b, l, x, FFN, Act::Relu);
        let projected = along(b, l, inner, D_MODEL, Act::None);
        let residual = b.add(x, projected);
        x = b.layer_norm(residual, l.take(), EPSILON);
    }

    let stats = point(b, l, x, STATS, Act::None);
    if l.next != TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {TENSORS}", l.next));
    }
    // Two outputs. The stats are the prior the flow samples; `x` is the hidden state *before*
    // the projection, which is what the duration predictor conditions on — VITS's
    // `TextEncoder` returns both for exactly that reason.
    builder.finish(&[stats, x])
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// A short sentence. Long enough that the relative band is interior somewhere, which at
    /// four or fewer phonemes it would not be.
    const PHONEMES: u32 = 24;

    fn plan(phonemes: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, phonemes).expect("the encoder builds");
        (source, plan)
    }

    #[test]
    fn the_pass_reads_every_tensor_in_the_file_exactly_once() {
        let (source, _) = plan(PHONEMES);
        let asked = source.asked.borrow();
        assert_eq!(asked.len(), TENSORS);
        let mut indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        indices.sort_unstable();
        assert_eq!(indices, (0..TENSORS).collect::<Vec<usize>>());
    }

    #[test]
    fn the_tensor_table_matches_the_export() {
        // Every parameter `enc_p` holds in en_GB-alan-low, summed. Each of the 111 names was
        // looked up in the export, so this pins the channel counts, the kernel widths and
        // the relative tables' shape at once — a structural test would pass without them.
        let (source, _) = plan(PHONEMES);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();
        assert_eq!(total, 6_317_568);
    }

    #[test]
    fn the_outputs_are_the_prior_and_the_hidden_state() {
        // Two bindings, in that order: the 384-channel stats the flow samples, and the
        // 192-channel hidden state the duration predictor conditions on. `post::duration`
        // reads the second, so swapping them would feed it the wrong tensor at the right
        // shape for half of it.
        let (_, plan) = plan(PHONEMES);
        let shapes: Vec<Shape> = plan.outputs.iter().map(|b| b.shape).collect();
        assert_eq!(
            shapes,
            vec![Shape::new(STATS, 1, PHONEMES), Shape::new(D_MODEL, 1, PHONEMES)]
        );
        // Ids, one per phoneme, not one per channel.
        assert_eq!(plan.input().expect("one input").shape, Shape::new(1, 1, PHONEMES));
    }

    #[test]
    fn every_layer_keeps_the_sequence_length() {
        // The feed-forward convolutions are three wide and padded by one, so nothing here
        // changes `T`. A missing pad would shorten the sequence at every layer and the
        // durations the predictor produced would no longer line up with it.
        let (_, plan) = plan(PHONEMES);
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
                assert_eq!(push.out_w, PHONEMES, "step {step} changed the length");
                assert_eq!(push.out_h, 1, "step {step} is not a sequence");
            }
        }
    }

    #[test]
    fn the_op_inventory_is_six_layers_of_relative_attention() {
        let (_, plan) = plan(PHONEMES);
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(format!("{kind:?}")).or_insert(0) += 1;
            }
        }
        // Four projections and two feed-forward convolutions per layer, plus the output
        // projection: `6 * 6 + 1`.
        assert_eq!(counts.get("Conv"), Some(&37), "{counts:?}");
        assert_eq!(counts.get("Embed"), Some(&1), "{counts:?}");
        assert_eq!(counts.get("AttnScoresRelative"), Some(&LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApplyRelative"), Some(&LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&LAYERS), "{counts:?}");
        // Two residuals and two norms per layer.
        assert_eq!(counts.get("Add"), Some(&(LAYERS * 2)), "{counts:?}");
        assert_eq!(counts.get("LayerNorm"), Some(&(LAYERS * 2)), "{counts:?}");
        // In particular no plain AttnScores or AttnApply: every layer here is relative, and
        // a mix would mean one layer had lost its position information.
        assert_eq!(counts.get("AttnScores"), None, "{counts:?}");
        assert_eq!(counts.get("AttnApply"), None, "{counts:?}");
        assert_eq!(counts.len(), 7, "{counts:?}");
    }

    #[test]
    fn every_attention_splits_d_model_into_two_heads_over_nine_offsets() {
        let (_, plan) = plan(PHONEMES);
        let mut seen = 0;
        for op in &plan.ops {
            match op {
                Op::Dispatch { kind: Kind::AttnScoresRelative, push, .. }
                | Op::Dispatch { kind: Kind::AttnApplyRelative, push, .. } => {
                    assert_eq!(push.group, HEADS, "{push:?}");
                    assert_eq!(push.kw, OFFSETS, "{push:?}");
                    seen += 1;
                }
                _ => {}
            }
        }
        assert_eq!(seen, LAYERS * 2);
    }

    #[test]
    fn a_sequence_shorter_than_the_relative_band_still_builds() {
        // A one-phoneme utterance is legal — "a" is a word. The band is then entirely
        // outside the sequence, which the taps skip rather than clamping.
        let (_, plan) = plan(1);
        assert_eq!(plan.outputs[0].shape.w, 1);
    }

    #[test]
    fn an_empty_utterance_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, 0).expect_err("no phonemes");
        assert!(error.contains("no phonemes"), "{error}");
    }

    #[test]
    fn no_op_reads_a_region_of_the_arena_that_it_also_writes() {
        let (_, plan) = plan(PHONEMES);
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_arena_is_bounded_at_a_long_sentence() {
        // Attention is quadratic in the phoneme count, and this is the only net here where
        // that matters: 24 phonemes is a short phrase and 400 is about as long as a
        // sentence Piper is asked to say in one go.
        for phonemes in [PHONEMES, 400] {
            let (_, plan) = plan(phonemes);
            let mib = plan.arena_elems as f32 * 2.0 / (1024.0 * 1024.0);
            println!("vits_enc at {phonemes} phonemes: {} elements, {mib:.2} MiB", plan.arena_elems);
            assert!(mib < 64.0, "{phonemes} phonemes wants {mib} MiB");
        }
    }
}
