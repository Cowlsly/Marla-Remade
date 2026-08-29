//! Supertonic 3's duration predictor: text to one number, the utterance's length in seconds.
//!
//! # What it is
//!
//! The smallest of the four nets — 1086 ONNX nodes and 864,706 parameters — and the first to
//! run, because everything after it is shaped by the answer. A character embedding, six
//! ConvNeXt blocks, two layers of relative-position attention, and a two-layer head that
//! reduces the whole sentence to a single scalar. [`latent_frames`] turns that into the length
//! the sampler and the vocoder are built for.
//!
//! # The sentence token, which is why the sequence is `T + 1`
//!
//! The export holds a learned `[1, 64, 1]` `sentence_token` and **prepends** it to the
//! embedded text, then reads position 0 back out at the end (`/sentence_encoder/Slice_1`) — a
//! CLS token, so the reduction is a slice rather than a pool. There is no op here for
//! concatenating along the sequence, and none is needed: the converter appends the token to the
//! embedding table as row [`SENTENCE_TOKEN`], and the caller prepends that id. The lookup then
//! produces `concat(token, embedded)` for free.
//!
//! The token row is the *last* row, so [`super::Kind::Embed`]'s out-of-range clamp lands on it
//! rather than on a character. That is only safe because the text front end drops unmapped
//! codepoints instead of emitting an out-of-range id.
//!
//! # Three things checked against the export rather than carried over from the vocoder
//!
//! **The padding is symmetric, not causal.** `nets::supertonic_vocoder` puts the whole
//! `dilation * (kernel - 1)` on the left, and assuming the same here would be wrong: the
//! `Pad` before each depthwise convolution is `[0, 0, 2, 0, 0, 2]`, two each side of a 5-tap at
//! dilation 1. Still `mode=edge`, so [`Builder::edge_padding`] is still right.
//!
//! **The layer scales are here too.** Each block ends in a `Mul` by a `[1, 64, 1]` gamma — 384
//! parameters of 864,706, invisible in any count check. The converter folds all six into their
//! block's `pwconv2`, which is a 1x1 and so has no border to get wrong.
//!
//! **The mask is not needed.** 58 `Mul` nodes multiply by the padding mask, which for one
//! utterance is all ones — including the leading one the token's position gets. The same
//! argument as [`super::supertonic_text`], and it is why `text_mask` is not an input here.
//!
//! # The whole net is one plan
//!
//! Including the head. Its `Gemm [128, 192]` reads the sliced position and the style
//! concatenated, which is [`Builder::concat`] of a `[64, 1, 1]` and a `[128, 1, 1]`, and both
//! `Gemm`s are then 1x1 convolutions over a single position. The `Slice` is a 1x1 convolution
//! **strided by the sequence length**, which reads position 0 and nothing else. Only the final
//! `Exp` is left for the host, on one scalar: see [`seconds`].
//!
//! # Measured parity
//!
//! Against onnxruntime on `/sentence_encoder/Add_output_0` at 24 characters: correlation
//! 0.99999959, max 0.008547 on a tensor whose own values reach 3.21. That is about four fp16
//! ULPs at that magnitude and *above* the 0.001743 the script's fp16-weights bar reports,
//! because the bar rounds weights only and this runtime holds activations in fp16 as well —
//! through six residual blocks and ten layer norms. The predicted duration itself agrees to
//! 0.12%: 1.556031 seconds against 1.557878, which is the same [`latent_frames`] either way.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Characters in the embedding table, from `onnx/unicode_indexer.json`'s 8,321 mapped
/// codepoints plus the unused row 0.
pub const SYMBOLS: u32 = 8322;

/// The id of the appended `sentence_token` row. See the module docs.
pub const SENTENCE_TOKEN: u32 = SYMBOLS;

/// Rows the `.maml`'s embedding table holds: the characters and the sentence token.
pub const TABLE_ROWS: u32 = SYMBOLS + 1;

/// Channels throughout the sentence encoder. `char_emb_dim` and `hidden_channels`.
pub const D_MODEL: u32 = 64;

/// The ConvNeXt widening, and the attention feed-forward's width. `intermediate_dim` and
/// `filter_channels`, which are the same 256 here.
pub const INNER: u32 = 256;

/// Attention heads, so `D_MODEL / HEADS` is 32 — which is also the relative table's width.
pub const HEADS: u32 = 2;

/// Entries in each attention layer's relative position table: `2 * window + 1` at
/// `window_size` 4, as in [`super::supertonic_text`].
pub const OFFSETS: u32 = 9;

/// ConvNeXt blocks.
pub const BLOCKS: usize = 6;

/// Relative-attention layers.
pub const ATTN_LAYERS: usize = 2;

/// The style vector's width: `n_style * style_dim`, 8 by 16, flattened.
pub const STYLE: u32 = 128;

/// The predictor head's inner width. `hdim`.
pub const HIDDEN: u32 = 128;

/// The depthwise kernel width, `ksz`. Dilation is 1 in all six blocks.
const KERNEL: u32 = 5;

/// The epsilon in all ten layer norms.
const EPSILON: f32 = 1e-5;

/// Tensors the `.maml` must hold.
///
/// The embedding table (1), six blocks of eight (48), two attention layers of eighteen (36),
/// `proj_out` (2, the second a synthesised zero bias), the head's first `Gemm` (2), its PReLU
/// slope (1) and its second `Gemm` (2) — 92 at fp16 — plus one per quantised convolution for its
/// scale. See [`INT8_CONVS`].
pub const TENSORS: usize = 92 + INT8_CONVS;

/// Convolutions read as int8 rather than fp16, each carrying a third tensor for its scale.
///
/// Every ungrouped `1 x 1` in the blocks and the attention layers: two per block (`pwconv1` and
/// `pwconv2`) and six per attention layer (q, k, v, the output projection and the two
/// feed-forward projections).
///
/// Three of this net's `1 x 1`s are **not** here, and each for a reason the runtime enforces rather
/// than a judgement call:
///
/// * `proj_out` is strided by the whole sequence, and the tiled int8 shader is stride-1 only.
/// * the head's first `Gemm` carries [`Act::PRelu`], whose per-channel slope wants the same push
///   offset the dequantisation scale occupies — `Builder::conv_int8` refuses it.
/// * the head's second `Gemm` is 128 to 1, so quantising it would save 128 bytes while putting the
///   single value this net exists to produce through a coarser weight.
///
/// That leaves 34% of the parameters quantised. The cap is the embedding table, which is 61% of
/// this net on its own and has no int8 path — `embed.comp` is fp16 only.
pub const INT8_CONVS: usize = BLOCKS * 2 + ATTN_LAYERS * 6;

/// Latent frames for an utterance of `seconds`, which is what the sampler and the vocoder are
/// built for.
///
/// One frame is [`super::supertonic_vocoder::SAMPLES_PER_FRAME`] samples at
/// [`super::supertonic_vocoder::SAMPLE_RATE`]. Verified against onnxruntime at 54, 55 and 108.
pub fn latent_frames(seconds: f32) -> u32 {
    let per_frame = super::supertonic_vocoder::SAMPLES_PER_FRAME as f32;
    let frames = seconds * super::supertonic_vocoder::SAMPLE_RATE as f32 / per_frame;
    frames.round().max(1.0) as u32
}

/// The export's trailing `Exp`, on the plan's one output value.
///
/// A single transcendental on a single scalar, which is not worth a shader or a `Kind`.
pub fn seconds(log_seconds: f32) -> f32 {
    log_seconds.exp()
}

/// Hands out `.maml` tensor indices in the order the layers appear.
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

    /// An int8 kernel, its per-output-channel scale, and the bias after that.
    ///
    /// Three rather than two, which is why quantising a convolution shifts every later index. The
    /// order is the one `Builder::conv_int8` reads and `supertonic_fold.py` writes; getting it
    /// wrong puts an fp16 tensor where the kernel should be, and `WeightSource::shaped_words`
    /// refuses that rather than reading it as bytes.
    fn take3(&mut self) -> usize {
        let index = self.next;
        self.next += 3;
        index
    }

    /// A lone tensor: the embedding table, a relative position table, a PReLU slope.
    fn take_one(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }
}

/// A `1 x 1` convolution, which every projection and both head `Gemm`s are.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// [`point`] with an int8 kernel. See [`INT8_CONVS`] for which convolutions get one.
fn point_int8(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv_int8(x, l.take3(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// The depthwise convolution along the sequence, padded symmetrically so the length holds.
fn depthwise(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let each = (KERNEL - 1) / 2;
    b.conv(
        x,
        l.take(),
        D_MODEL,
        (1, KERNEL),
        (1, 1),
        (1, 1),
        // (top, left, bottom, right). Two each side, not four on the left: this net's `Pad`
        // nodes are symmetric where the vocoder's are causal.
        (0, each, 0, each),
        D_MODEL,
        Act::None,
    )
}

/// Build the predictor for an utterance of `chars` characters.
///
/// Two inputs, in this order: the character ids as the two lanes
/// [`super::Kind::Embed`] wants over `chars + 1` positions — [`SENTENCE_TOKEN`] first — and the
/// voice's `style_dp` flattened to `[128, 1, 1]`. Two outputs: the sentence encoder's
/// `[64, 1, chars + 1]` state, which is the tensor worth checking for parity, and the one value
/// [`seconds`] exponentiates.
pub fn build(weights: &dyn WeightSource, chars: u32) -> Result<Plan, String> {
    if chars == 0 {
        return Err("a duration pass over no characters".into());
    }
    // The sentence token leads the sequence. See the module docs.
    let positions = chars.checked_add(1).ok_or("an utterance longer than an index can hold")?;

    let l = &mut Layers { next: 0 };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // Every padded convolution here replicates its border.
    b.edge_padding();

    let ids = b.input(Shape::new(2, 1, positions));
    let style = b.input(Shape::new(STYLE, 1, 1));
    let mut x = b.embed(ids, l.take_one(), TABLE_ROWS, D_MODEL);

    for _ in 0..BLOCKS {
        let along = depthwise(b, l, x);
        let normed = b.layer_norm(along, l.take(), EPSILON);
        let widened = point_int8(b, l, normed, INNER, Act::Gelu);
        // `pwconv2` carries the block's layer scale, folded in by the converter.
        let narrowed = point_int8(b, l, widened, D_MODEL, Act::None);
        x = b.add(x, narrowed);
    }
    // The attention encoder is skipped around as a whole, not just per layer.
    let convnext = x;

    for _ in 0..ATTN_LAYERS {
        let q = point_int8(b, l, x, D_MODEL, Act::None);
        let k = point_int8(b, l, x, D_MODEL, Act::None);
        let v = point_int8(b, l, x, D_MODEL, Act::None);
        let scores = b.attn_scores_relative(q, k, HEADS, l.take_one(), OFFSETS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply_relative(probs, v, HEADS, l.take_one(), OFFSETS);
        let attended = point_int8(b, l, mixed, D_MODEL, Act::None);
        // Post-norm: the residual is added first and normalised after.
        let residual = b.add(x, attended);
        x = b.layer_norm(residual, l.take(), EPSILON);

        // Both feed-forward convolutions are 1x1 here, so neither has a border.
        let inner = point_int8(b, l, x, INNER, Act::Relu);
        let projected = point_int8(b, l, inner, D_MODEL, Act::None);
        let residual = b.add(x, projected);
        x = b.layer_norm(residual, l.take(), EPSILON);
    }
    let encoded = b.add(convnext, x);

    // `Slice_1` and `proj_out` at once: a 1x1 convolution strided by the whole sequence reads
    // position 0 and produces one position, which is what the CLS reduction is.
    let leading = b.conv(
        encoded,
        l.take(),
        D_MODEL,
        (1, 1),
        (1, positions),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::None,
    );

    let joined = b.concat(&[leading, style]);
    // The head's PReLU has one shared slope in the export, which the converter widens to a
    // value per channel.
    let slope = l.next + 2;
    let hidden = point(b, l, joined, HIDDEN, Act::PRelu(slope));
    l.take_one();
    let log_seconds = point(b, l, hidden, 1, Act::None);

    if l.next != TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {TENSORS}", l.next));
    }
    builder.finish(&[encoded, log_seconds])
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// A short sentence. Long enough that the relative band is interior somewhere.
    const CHARS: u32 = 24;

    fn plan(chars: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, chars).expect("the predictor builds");
        (source, plan)
    }

    #[test]
    fn the_pass_reads_every_tensor_in_the_file_exactly_once() {
        let (source, _) = plan(CHARS);
        let asked = source.asked.borrow();
        assert_eq!(asked.len(), TENSORS);
        let mut indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        indices.sort_unstable();
        assert_eq!(indices, (0..TENSORS).collect::<Vec<usize>>());
    }

    #[test]
    fn the_tensor_table_matches_the_export() {
        // The export's initializers total 864,706 parameters. Four differences, each derived
        // rather than guessed:
        //
        //   - the six `[1, 64, 1]` layer scales fold into their `pwconv2`:        -384
        //   - `proj_out` has no bias in the export, so a zero one is synthesised:  +64
        //   - the head PReLU's single shared slope is widened to one per channel: +127
        //   - each quantised convolution gains an `[out]` dequantisation scale:  +3072
        //
        // `sentence_token` is not a difference: its 64 values move from a standalone tensor
        // into the embedding table's last row, so the total is unchanged either way.
        let (source, _) = plan(CHARS);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();
        // The scales, spelled out per shape so a miscount is visible rather than absorbed:
        // `pwconv1` and both feed-forwards' first stage are `INNER` wide, everything else
        // `D_MODEL`.
        let scales = (BLOCKS as u64) * (INNER + D_MODEL) as u64
            + (ATTN_LAYERS as u64) * (4 * D_MODEL + INNER + D_MODEL) as u64;
        assert_eq!(scales, 3072);
        assert_eq!(total, 864_706 - 384 + 64 + 127 + scales);
        // And spelled out, so a reordering that preserved the sum would still be caught.
        assert_eq!(total, 867_585);
    }

    #[test]
    fn the_sequence_is_one_longer_than_the_text() {
        // The sentence token leads it. A net built for `chars` positions instead would run,
        // produce a number of the right magnitude, and read the wrong position at the end.
        let (_, plan) = plan(CHARS);
        let shapes: Vec<Shape> = plan.inputs.iter().map(|b| b.shape).collect();
        assert_eq!(shapes, vec![Shape::new(2, 1, CHARS + 1), Shape::new(STYLE, 1, 1)]);
        let outputs: Vec<Shape> = plan.outputs.iter().map(|b| b.shape).collect();
        assert_eq!(
            outputs,
            vec![Shape::new(D_MODEL, 1, CHARS + 1), Shape::new(1, 1, 1)]
        );
    }

    #[test]
    fn the_ids_arrive_in_two_lanes_because_the_table_is_past_2048() {
        // 8,323 rows against fp16's 2,048 exact integers. A single-lane id tensor would make
        // every character past 2048 read a neighbouring row — plausible text, wrong duration.
        let (_, plan) = plan(CHARS);
        const _: () = assert!(TABLE_ROWS > super::super::EMBED_LANE);
        assert_eq!(plan.inputs[0].shape.c, 2);
        let lanes = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Embed, push, .. } => Some((push.in_c, push.in_w)),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(lanes, vec![(2, TABLE_ROWS)]);
    }

    #[test]
    fn the_depthwise_convolutions_pad_symmetrically_and_replicate() {
        // Two each side of a 5-tap, not four on the left. The vocoder is the other way round,
        // and copying it here correlated at 0.02 there — the same mistake in reverse.
        let (_, plan) = plan(CHARS);
        let mut seen = 0;
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
                if push.group == D_MODEL {
                    assert_eq!(push.kw, KERNEL, "step {step}");
                    assert_eq!(push.pad_l, (KERNEL - 1) / 2, "step {step}");
                    assert_eq!(push.dil_w, 1, "step {step}");
                    seen += 1;
                }
                assert_ne!(push.pad_edge, 0, "step {step} pads with zeros");
            }
        }
        assert_eq!(seen, BLOCKS);
    }

    #[test]
    fn the_reduction_reads_position_zero_and_no_other() {
        // `proj_out` is strided by the whole sequence, so it produces one position from
        // position 0. A stride of one would produce `T + 1` of them and the concat that
        // follows would refuse the shape — which is the point of doing it this way.
        let (_, plan) = plan(CHARS);
        let strided: Vec<&super::super::Push> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.stride_w > 1 => Some(push),
                _ => None,
            })
            .collect();
        assert_eq!(strided.len(), 1);
        assert_eq!(strided[0].stride_w, CHARS + 1);
        assert_eq!(strided[0].out_w, 1);
        assert_eq!(strided[0].out_c, D_MODEL);
    }

    #[test]
    fn the_head_joins_the_sentence_with_the_style() {
        // 64 from position 0 and 128 from `style_dp`, in that order: the export's
        // `/predictor/Concat_2` puts the text first, and swapping them would read the style
        // through the text's weights at exactly the right total width.
        let (_, plan) = plan(CHARS);
        let concats = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Copy { .. }))
            .count();
        // A concat lowers to one copy per part.
        assert!(concats >= 2, "{concats} copies for a two-part concat");
        let widths: Vec<u32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv | Kind::ConvPoint, push, .. }
                    if push.out_w == 1 =>
                {
                    Some(push.in_c)
                }
                _ => None,
            })
            .collect();
        assert_eq!(widths, vec![D_MODEL, D_MODEL + STYLE, HIDDEN]);
    }

    #[test]
    fn the_op_inventory_is_six_convnext_blocks_and_two_attention_layers() {
        let (_, plan) = plan(CHARS);
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0) += 1;
            }
        }
        // Per block a depthwise and two 1x1s; per attention layer q/k/v/o and two
        // feed-forward 1x1s; then `proj_out` and the head's two `Gemm`s. All but the depthwise
        // ones, `proj_out` and the head are quantised - see `INT8_CONVS`.
        let convolutions = BLOCKS * 3 + ATTN_LAYERS * 6 + 3;
        assert_eq!(counts.get("Conv"), Some(&(convolutions - INT8_CONVS)), "{counts:?}");
        assert_eq!(counts.get("ConvInt8"), Some(&INT8_CONVS), "{counts:?}");
        assert_eq!(counts.get("Embed"), Some(&1), "{counts:?}");
        assert_eq!(counts.get("AttnScoresRelative"), Some(&ATTN_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApplyRelative"), Some(&ATTN_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&ATTN_LAYERS), "{counts:?}");
        // Six block residuals, two per attention layer, and the skip around the whole
        // attention encoder.
        assert_eq!(counts.get("Add"), Some(&(BLOCKS + ATTN_LAYERS * 2 + 1)), "{counts:?}");
        assert_eq!(counts.get("LayerNorm"), Some(&(BLOCKS + ATTN_LAYERS * 2)), "{counts:?}");
        // No pool: the reduction is the strided convolution, not an average over the
        // sentence. A `GlobalAvgPool` here would be the wrong model producing a plausible
        // number.
        assert_eq!(counts.get("GlobalAvgPool"), None, "{counts:?}");
        assert_eq!(counts.len(), 8, "{counts:?}");
    }

    #[test]
    fn a_one_character_utterance_still_builds() {
        // Two positions then, the token and the character. The relative band is entirely
        // outside the sequence, which the taps skip.
        let (_, plan) = plan(1);
        assert_eq!(plan.outputs[1].shape, Shape::new(1, 1, 1));
    }

    #[test]
    fn an_empty_utterance_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, 0).expect_err("no characters");
        assert!(error.contains("no characters"), "{error}");
    }

    #[test]
    fn a_duration_becomes_the_latent_length_the_vocoder_was_measured_at() {
        // 3072 samples a frame at 44.1 kHz, so a frame is 69.66 ms. The three lengths in
        // `supertonic_vocoder`'s docs were measured against onnxruntime.
        assert_eq!(latent_frames(54.0 * 3072.0 / 44_100.0), 54);
        assert_eq!(latent_frames(55.0 * 3072.0 / 44_100.0), 55);
        assert_eq!(latent_frames(108.0 * 3072.0 / 44_100.0), 108);
        // And a duration too short to fill a frame still gets one, rather than a plan over
        // nothing that `supertonic_vocoder::build` would refuse.
        assert_eq!(latent_frames(0.001), 1);
    }

    #[test]
    fn the_tail_exponentiates() {
        // The export's `Exp`, kept on the host. `log(2)` seconds out is two seconds.
        assert!((seconds(std::f32::consts::LN_2) - 2.0).abs() < 1e-6);
    }

    #[test]
    fn no_op_reads_a_region_of_the_arena_that_it_also_writes() {
        let (_, plan) = plan(CHARS);
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_arena_is_bounded_at_a_long_sentence() {
        // Attention is quadratic in the character count, and unlike phonemes these are
        // characters: 24 is a phrase and 400 is a long sentence.
        for chars in [CHARS, 400] {
            let (_, plan) = plan(chars);
            let mib = plan.arena_elems as f32 * 2.0 / (1024.0 * 1024.0);
            println!("supertonic_duration at {chars} chars: {mib:.2} MiB");
            assert!(mib < 64.0, "{chars} chars wants {mib} MiB");
        }
    }
}
