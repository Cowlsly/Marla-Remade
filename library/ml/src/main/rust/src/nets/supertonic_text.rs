//! Supertonic 3's text encoder: characters and a voice to a 256-channel conditioning sequence.
//!
//! # What it is
//!
//! The duration predictor's sentence encoder, four times wider, plus a style path it does not
//! have. 1934 ONNX nodes and 9,000,960 parameters: a character embedding, six ConvNeXt blocks at
//! 256/1024, four layers of relative-position attention, a skip around the whole attention
//! stack, and then two **cross**-attentions from the text over 50 style tokens.
//!
//! Same as `nets::supertonic_duration` and for the same reasons: symmetric edge padding, six
//! `[1, 256, 1]` layer scales folded into their `pwconv2`, post-norm attention layers whose
//! feed-forward convolutions are 1x1, and a padding mask that is the identity for one
//! utterance. Two differences: the depthwise dilations are **1, 1, 2, 2, 4, 4** rather than all
//! ones, and there is no sentence token — the output is the whole sequence.
//!
//! `proj_out` appears in `onnx/tts.json` but not in the export: its input and output widths are
//! both 256, so it is an identity and carries no weights.
//!
//! # The style path
//!
//! `/speech_prompted_text_encoder/`, two attentions over the voice's 50 style tokens:
//!
//! ```text
//! h1 = x  + out_fc1(attend(Wq1 x,  K1, Wv1 style))
//! h2 = x  + out_fc2(attend(Wq2 h1, K2, Wv2 style))
//! out = layer_norm(h2)
//! ```
//!
//! The second attention's **query** comes from `h1` but its residual comes from `x` again, not
//! from `h1`. That is what the export does and it is easy to write the other way round.
//!
//! Three things that are not obvious:
//!
//! * **The keys are constant.** `K = tanh(W_key . style_key + b_key)`, and `style_key` is a
//!   learned `[50, 256]` table, so the whole expression folds to one tensor per attention at
//!   conversion time. It has to reach the arena to be an attention operand, which is what
//!   [`super::Kind::Constant`] is for. This removes two convolutions, two biases and two
//!   tanhs.
//! * **The scale is `/16`, not `1 / sqrt(head_dim)`.** Two heads of 128, so
//!   [`Builder::attn_scores`]'s own scale is `sqrt(2)` too large. The converter folds
//!   `sqrt(128) / 16` into `W_query`, which is exact and costs no op.
//! * **`style_ttl` is position-major in the export** (`[1, 50, 256]`) and this runtime is
//!   channel-major, so the caller passes it transposed to `[256, 1, 50]`.
//!
//! # Weights that are `MatMul`s, not `Conv`s
//!
//! Every projection in the style path is a `MatMul` over `[T, 256]`, so its weight is
//! `[in, out]` — the **transpose** of the `[out, in, 1, 1]` a 1x1 convolution reads. All eight
//! are 256x256, where a missed transpose is invisible in the shape and produces a plausible
//! wrong answer. The converter transposes them; `scripts/ml/supertonic_fold.py` says so at the
//! one place it happens.
//!
//! # Measured parity, and why it is above the script's bar
//!
//! Against onnxruntime at 24 characters, on `text_emb`: correlation 0.99948766, max 0.077335 on
//! values reaching 1.46. `onnx_parity.py`'s "fp16 weights alone" bar is 0.023410, so the runtime
//! is over three times it — and that bar keeps activations in fp32, which this runtime does not.
//!
//! Walking the same run inwards shows diffuse fp16 accumulation rather than one wrong layer:
//!
//! | tensor | `|values|` | runtime max | in fp16 ULPs |
//! | --- | --- | --- | --- |
//! | after the six ConvNeXt blocks | 0.72 | 0.00267 | ~5 |
//! | after the four attention layers | 7.29 | 0.0724 | ~15 |
//! | after the final layer norm | 1.46 | 0.0773 | ~80 |
//!
//! Five ULPs after roughly twenty-four fp16 stores, fifteen after forty. The last row is the
//! layer norm dividing by a standard deviation smaller than the signal it normalises, which
//! amplifies whatever came in. Correlation stays above 0.9999 at every depth, and a structural
//! error in a net this size shows up an order of magnitude below that — the duration predictor's
//! two wrong hypotheses correlated at 0.02 and 0.009.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Characters in the embedding table. No sentence token here, unlike the duration predictor.
pub const SYMBOLS: u32 = 8322;

/// Channels throughout. `char_emb_dim`, `hidden_channels` and `text_dim` are all 256.
pub const D_MODEL: u32 = 256;

/// The ConvNeXt widening and the attention feed-forward's width: `intermediate_dim` and
/// `filter_channels`, both 1024.
pub const INNER: u32 = 1024;

/// Heads in the relative-position attention, so `head_dim` is 64 — the relative table's width.
pub const HEADS: u32 = 4;

/// Entries in each relative position table: `2 * window + 1` at `window_size` 4.
pub const OFFSETS: u32 = 9;

/// ConvNeXt blocks.
pub const BLOCKS: usize = 6;

/// Relative-attention layers.
pub const ATTN_LAYERS: usize = 4;

/// Style tokens the two cross-attentions attend over. `n_style`.
pub const STYLE_TOKENS: u32 = 50;

/// Heads in each style cross-attention, so `head_dim` is 128. `n_heads` of
/// `speech_prompted_text_encoder`.
pub const STYLE_HEADS: u32 = 2;

/// Cross-attentions in the style path.
pub const STYLE_ATTENTIONS: usize = 2;

/// Each block's depthwise dilation, read from the export in order. `dilation_lst`, and not the
/// duration predictor's all-ones.
const DILATIONS: [u32; BLOCKS] = [1, 1, 2, 2, 4, 4];

/// The depthwise kernel width, `ksz`.
const KERNEL: u32 = 5;

/// The epsilon in all fifteen layer norms.
const EPSILON: f32 = 1e-5;

/// Tensors the `.maml` must hold.
///
/// The embedding table (1), six blocks of eight (48), four attention layers of eighteen (72),
/// two style attentions of seven (14) and the final layer norm (2). The seven are `W_query`,
/// the folded constant keys, `W_value` and `out_fc` - a pair each except the keys.
///
/// # This net stays fp16, and it was measured rather than assumed
///
/// Every other Supertonic net quantises its ungrouped `1 x 1`s to int8. This one was converted the
/// same way and reverted, because the trade is bad at both ends:
///
/// * **It costs the most accuracy of the four.** `onnx_parity.py` against this export: fp16
///   correlates at 0.99900 and int8 at 0.99212, below the 0.999 the other three clear comfortably.
///   The cause is depth, not a bug - a per-tensor round trip of all 66 quantised kernels put every
///   one at ~0.0042 worst-row relative error against the 0.00394 an absmax int8 quantiser can
///   achieve, so no layer is pathological. Twelve sequential stages each losing 0.4% is simply what
///   compounding looks like.
/// * **It saves the least size.** 17.8 MB to 11.1 MB, so 6.6 MB of a 198 MB bundle. The sampler and
///   the vocoder are 178 MB of that 198 and carry the same proportion of quantisable weight.
///
/// Dropping it still leaves the bundle under its target, so there is nothing to buy with the
/// accuracy. Revisit only if a per-channel *activation* scale ever lands, which is what would stop
/// the error compounding.
pub const TENSORS: usize = 137;

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

    /// A lone tensor: the embedding table, a relative position table, the folded style keys.
    fn take_one(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }
}

/// A `1 x 1` convolution, which every projection here is.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// The depthwise convolution along the sequence, padded symmetrically so the length holds.
fn depthwise(b: &mut Builder, l: &mut Layers, x: Id, dilation: u32) -> Id {
    let each = dilation * (KERNEL - 1) / 2;
    b.conv(
        x,
        l.take(),
        D_MODEL,
        (1, KERNEL),
        (1, 1),
        (1, dilation),
        // (top, left, bottom, right). `2 * dilation` each side, verified against the export's
        // own `Pad` inputs at all six dilations.
        (0, each, 0, each),
        D_MODEL,
        Act::None,
    )
}

/// Build the encoder for an utterance of `chars` characters.
///
/// Two inputs, in this order: the character ids as the two lanes [`super::Kind::Embed`] wants,
/// and the voice's `style_ttl` **transposed** to `[256, 1, 50]`. The output is
/// `[256, 1, chars]`, which the sampler conditions on.
pub fn build(weights: &dyn WeightSource, chars: u32) -> Result<Plan, String> {
    if chars == 0 {
        return Err("a text encoder pass over no characters".into());
    }

    let l = &mut Layers { next: 0 };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // Every padded convolution here replicates its border.
    b.edge_padding();

    let ids = b.input(Shape::new(2, 1, chars));
    let style = b.input(Shape::new(D_MODEL, 1, STYLE_TOKENS));
    let mut x = b.embed(ids, l.take_one(), SYMBOLS, D_MODEL);

    for &dilation in &DILATIONS {
        let along = depthwise(b, l, x, dilation);
        let normed = b.layer_norm(along, l.take(), EPSILON);
        let widened = point(b, l, normed, INNER, Act::Gelu);
        // `pwconv2` carries the block's layer scale, folded in by the converter.
        let narrowed = point(b, l, widened, D_MODEL, Act::None);
        x = b.add(x, narrowed);
    }
    // The attention encoder is skipped around as a whole, as in the duration predictor.
    let convnext = x;

    for _ in 0..ATTN_LAYERS {
        let q = point(b, l, x, D_MODEL, Act::None);
        let k = point(b, l, x, D_MODEL, Act::None);
        let v = point(b, l, x, D_MODEL, Act::None);
        let scores = b.attn_scores_relative(q, k, HEADS, l.take_one(), OFFSETS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply_relative(probs, v, HEADS, l.take_one(), OFFSETS);
        let attended = point(b, l, mixed, D_MODEL, Act::None);
        let residual = b.add(x, attended);
        x = b.layer_norm(residual, l.take(), EPSILON);

        let inner = point(b, l, x, INNER, Act::Relu);
        let projected = point(b, l, inner, D_MODEL, Act::None);
        let residual = b.add(x, projected);
        x = b.layer_norm(residual, l.take(), EPSILON);
    }
    let text = b.add(convnext, x);

    // The style path. Both attentions read `text` as their residual; only the second's query
    // comes from the first's output.
    let mut query_source = text;
    let mut attended = text;
    for _ in 0..STYLE_ATTENTIONS {
        let q = point(b, l, query_source, D_MODEL, Act::None);
        // `tanh(W_key . style_key + b_key)`, folded to one tensor. It is an attention operand
        // rather than a kernel, so it is loaded into the arena.
        let keys = b.constant(l.take_one(), Shape::new(D_MODEL, 1, STYLE_TOKENS));
        let values = point(b, l, style, D_MODEL, Act::None);
        let scores = b.attn_scores(q, keys, STYLE_HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, values, STYLE_HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        attended = b.add(text, projected);
        query_source = attended;
    }
    let out = b.layer_norm(attended, l.take(), EPSILON);

    if l.next != TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {TENSORS}", l.next));
    }
    builder.finish(&[out])
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// A short sentence.
    const CHARS: u32 = 24;

    fn plan(chars: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, chars).expect("the text encoder builds");
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
        // The export's initializers total 9,000,960 parameters. Three differences:
        //
        //   - the six `[1, 256, 1]` layer scales fold into their `pwconv2`:      -1,536
        //   - `W_key` and its bias fold into the constant keys, twice:         -131,584
        //   - `style_key` (12,800) becomes one folded `[256, 50]` per
        //     attention rather than one table shared by both:                   +12,800
        let (source, _) = plan(CHARS);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();
        assert_eq!(total, 9_000_960 - 1_536 - 131_584 + 12_800);
        // And spelled out, so a reordering that preserved the sum would still be caught.
        assert_eq!(total, 8_880_640);
    }

    #[test]
    fn the_inputs_are_ids_and_a_transposed_style() {
        // `style_ttl` is `[1, 50, 256]` in the export and `[256, 1, 50]` here: 50 positions of
        // 256 channels, which is the layout every other sequence in this runtime uses. Feeding
        // it untransposed would be 256 positions of 50 channels — a shape the plan refuses
        // rather than a wrong answer, which is the point of checking it here.
        let (_, plan) = plan(CHARS);
        let inputs: Vec<Shape> = plan.inputs.iter().map(|b| b.shape).collect();
        assert_eq!(
            inputs,
            vec![Shape::new(2, 1, CHARS), Shape::new(D_MODEL, 1, STYLE_TOKENS)]
        );
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(D_MODEL, 1, CHARS)
        );
    }

    #[test]
    fn the_style_attention_is_a_cross_attention_over_fifty_tokens() {
        // The score maps are `[2, chars, 50]` — not square, which is the whole reason the
        // attention ops take two lengths. The relative ones are square, so this also pins that
        // the two kinds have not been swapped.
        let (_, plan) = plan(CHARS);
        let cross: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    Some((push.out_c, push.out_h, push.out_w))
                }
                _ => None,
            })
            .collect();
        assert_eq!(cross, vec![(STYLE_HEADS, CHARS, STYLE_TOKENS); STYLE_ATTENTIONS]);
        let square: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::AttnScoresRelative, push, .. } => {
                    Some((push.out_c, push.out_h, push.out_w))
                }
                _ => None,
            })
            .collect();
        assert_eq!(square, vec![(HEADS, CHARS, CHARS); ATTN_LAYERS]);
    }

    #[test]
    fn the_style_mix_writes_one_vector_per_character() {
        // 50 keys in, `chars` out. An `attn_apply` that took its width from the values would
        // write a 50-position tensor and the residual after it would refuse the shape.
        let (_, plan) = plan(CHARS);
        let mixes: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::AttnApply, push, .. } => Some((push.in_w, push.out_w)),
                _ => None,
            })
            .collect();
        assert_eq!(mixes, vec![(STYLE_TOKENS, CHARS); STYLE_ATTENTIONS]);
    }

    #[test]
    fn the_style_keys_are_loaded_from_the_weights_file() {
        // Two of them, one per attention, each `[256, 1, 50]`. If these were still a
        // convolution over a `style_key` input the plan would have three inputs.
        let (_, plan) = plan(CHARS);
        let loaded: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Constant, push, .. } => {
                    Some((push.out_c, push.out_h, push.out_w))
                }
                _ => None,
            })
            .collect();
        assert_eq!(loaded, vec![(D_MODEL, 1, STYLE_TOKENS); STYLE_ATTENTIONS]);
        assert_eq!(plan.inputs.len(), 2);
    }

    #[test]
    fn the_depthwise_dilations_are_the_exports_own_sequence() {
        // 1, 1, 2, 2, 4, 4 — pairs, unlike the vocoder's 1, 2, 4, 1, 2, 4 and unlike the
        // duration predictor's all-ones. The pad follows the dilation, so getting the table
        // wrong shifts the signal as well as widening the wrong receptive field.
        let (_, plan) = plan(CHARS);
        let found: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.group == D_MODEL => {
                    Some((push.dil_w, push.pad_l))
                }
                _ => None,
            })
            .collect();
        let want: Vec<(u32, u32)> =
            DILATIONS.iter().map(|&d| (d, d * (KERNEL - 1) / 2)).collect();
        assert_eq!(found, want);
    }

    #[test]
    fn nothing_changes_the_sequence_length() {
        let (_, plan) = plan(CHARS);
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
                // The style path's `W_value` runs over the 50 tokens; everything else over
                // the characters.
                assert!(
                    push.out_w == CHARS || push.out_w == STYLE_TOKENS,
                    "step {step} produced {} positions",
                    push.out_w
                );
                assert_ne!(push.pad_edge, 0, "step {step} pads with zeros");
            }
        }
    }

    #[test]
    fn the_op_inventory_is_six_blocks_four_layers_and_two_style_attentions() {
        let (_, plan) = plan(CHARS);
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0) += 1;
            }
        }
        // Three per block, six per attention layer, three per style attention.
        let convolutions = BLOCKS * 3 + ATTN_LAYERS * 6 + STYLE_ATTENTIONS * 3;
        assert_eq!(counts.get("Conv"), Some(&convolutions), "{counts:?}");
        assert_eq!(counts.get("Embed"), Some(&1), "{counts:?}");
        assert_eq!(counts.get("Constant"), Some(&STYLE_ATTENTIONS), "{counts:?}");
        assert_eq!(counts.get("AttnScoresRelative"), Some(&ATTN_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApplyRelative"), Some(&ATTN_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&STYLE_ATTENTIONS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&STYLE_ATTENTIONS), "{counts:?}");
        assert_eq!(
            counts.get("Softmax"),
            Some(&(ATTN_LAYERS + STYLE_ATTENTIONS)),
            "{counts:?}"
        );
        // Six block residuals, two per attention layer, the whole-stack skip, and one per
        // style attention.
        assert_eq!(
            counts.get("Add"),
            Some(&(BLOCKS + ATTN_LAYERS * 2 + 1 + STYLE_ATTENTIONS)),
            "{counts:?}"
        );
        assert_eq!(
            counts.get("LayerNorm"),
            Some(&(BLOCKS + ATTN_LAYERS * 2 + 1)),
            "{counts:?}"
        );
        assert_eq!(counts.len(), 10, "{counts:?}");
    }

    #[test]
    fn an_empty_utterance_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, 0).expect_err("no characters");
        assert!(error.contains("no characters"), "{error}");
    }

    #[test]
    fn no_op_reads_a_region_of_the_arena_that_it_also_writes() {
        let (_, plan) = plan(CHARS);
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_arena_is_bounded_at_a_long_sentence() {
        for chars in [CHARS, 400] {
            let (_, plan) = plan(chars);
            let mib = plan.arena_elems as f32 * 2.0 / (1024.0 * 1024.0);
            println!("supertonic_text at {chars} chars: {mib:.2} MiB");
            assert!(mib < 128.0, "{chars} chars wants {mib} MiB");
        }
    }
}
