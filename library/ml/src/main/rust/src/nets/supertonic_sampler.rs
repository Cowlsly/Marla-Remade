//! Supertonic 3's flow-matching sampler: the velocity field, run N times per utterance.
//!
//! # What it is
//!
//! The expensive one. 1004 ONNX nodes and 64,013,449 parameters, of which 92% are the 56
//! pointwise convolutions inside its 28 ConvNeXt blocks, and it runs 16 times per utterance —
//! twice per step, for the reason below. Everything else in Supertonic is a rounding error
//! against it.
//!
//! Its shape is four **main blocks**, each of them
//!
//! ```text
//! 4 ConvNeXt blocks at dilations 1, 2, 4, 8
//! + a per-channel shift from the timestep
//! 1 ConvNeXt block
//! + an 8-head cross-attention over the text, with rotary positions
//! 1 ConvNeXt block
//! + a 2-head cross-attention over the 50 style tokens
//! ```
//!
//! then four more ConvNeXt blocks at dilation 1, between a `[512, 144, 1]` projection in and a
//! `[144, 512, 1]` projection out. The export flattens the four into `main_blocks.0` through
//! `main_blocks.23`, six entries each.
//!
//! # It is classifier-free guidance, and that doubles the cost
//!
//! The export's first three nodes are `Tile(x, [2, 1, 1])`: it runs the **whole network twice**,
//! once on the real text and style and once on two learned unconditional tokens, and combines
//! them at the end. Read off the graph:
//!
//! ```text
//! v = 4 * conditional - 3 * unconditional
//! denoised = (noisy_latent + v / total_step) * latent_mask
//! ```
//!
//! So the ONNX is not a velocity field but a whole **Euler step**, guidance scale 4 baked in.
//! This runtime has no batch axis and cannot fake one — putting the two branches side by side
//! along the sequence would let the depthwise convolutions mix them — so the plan is one branch
//! and [`crate::post::supertonic`] runs it twice. That is a real doubling of the sampler's cost against
//! any measurement taken of a single branch.
//!
//! Both branches are the same plan with different **inputs**: the unconditional one passes
//! `uncond_masker.text_special_token` broadcast over the text positions, and
//! `style_value_special_token` in place of the voice. Only the style *keys* differ structurally,
//! and those are folded constants (below), so they are an input too.
//!
//! # What the host computes, and why
//!
//! Four things never reach a shader, and [`Builder::host_tensor`] names each so
//! [`Builder::finish`] still refuses an *accidentally* unread tensor:
//!
//! * **The timestep conditioning.** A sinusoidal embedding of `current_step / total_step`, a
//!   two-layer MLP with a Mish in the middle, and then four `Linear`s to 512 numbers each — all
//!   a function of two scalars. That is `Sin`, `Cos`, `Softplus` and `Tanh` shaders for 2,048
//!   values, against a net that does 64M multiply-adds a position. The host evaluates it and
//!   passes `[2048, 1, 1]`, which [`Builder::slice_channels`] cuts into four per-channel shifts.
//! * **The rotary angles.** `(position / length) * theta`, so they depend on the two sequence
//!   lengths and on nothing learned. See [`super::Kind::Rotary`].
//! * **The style keys**, `tanh(W_key . style_key + b_key)`, entirely constant — and different per
//!   guidance branch *and* per main block, since the four style attentions share one `style_key`
//!   but each has its own `W_key`. Folding only the first was a real bug here: the net was exact
//!   through ten of its twenty-four sub-blocks and then correlated at 0.29.
//! * **The unconditional tokens**, which are the other branch's inputs.
//!
//! # Two scales that are not `1 / sqrt(head_dim)`
//!
//! Both attentions divide their scores by **16**. The text one has heads of 64 and the style one
//! heads of 128, so [`Builder::attn_scores`]'s own `1 / sqrt(head_dim)` is 2x and `sqrt(2)`x too
//! large respectively. The converter folds the difference into each `W_query`, which is exact —
//! and safe across the rotary, because a rotation commutes with a scalar.

//! # Measured parity
//!
//! Against onnxruntime on `denoised_latent` at 32 frames and 16 characters \u2014 the whole Euler step
//! over both guidance branches, so both plan runs and all the host arithmetic: correlation
//! 0.99999981, max 0.006019 on values reaching 5.13. Every one of the 25 stage boundaries inside
//! the net agrees to better than 0.99998.
//!
//! Getting there took one bisect. The first attempt folded a single style `W_key` and reused it
//! for all four style attentions; the net was then exact through `main_blocks.10` and correlated
//! at 0.29 from `main_blocks.11` on. A per-stage comparison found it in one run, which is much
//! faster than reasoning about a 1004-node graph.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Latent channels in and out. `ldim * chunk_compress_factor`, and the vocoder's `PACKED`.
pub const LATENT: u32 = 144;

/// Channels the stack works in. `vector_field.proj_in.odim`.
pub const CHANNELS: u32 = 512;

/// The ConvNeXt widening. `intermediate_dim`.
pub const INNER: u32 = 2048;

/// The text conditioning's width, which is the text encoder's output. `text_dim`.
pub const TEXT: u32 = 256;

/// The style conditioning's width. `style_dim`.
pub const STYLE: u32 = 256;

/// Style tokens each style cross-attention attends over. `n_style`.
pub const STYLE_TOKENS: u32 = 50;

/// Heads in the text cross-attention, so `head_dim` is 64. `text_cond_layer.n_heads`.
pub const TEXT_HEADS: u32 = 8;

/// Heads in the style cross-attention, so `head_dim` is 128.
pub const STYLE_HEADS: u32 = 2;

/// The timestep embedding's width. `time_encoder.time_dim`.
pub const TIME: u32 = 64;

/// The timestep MLP's inner width. `time_encoder.hdim`.
pub const TIME_INNER: u32 = 256;

/// Main blocks, each one four ConvNeXt blocks, a timestep shift, two conditionings and two more
/// ConvNeXt blocks.
pub const MAIN_BLOCKS: usize = 4;

/// ConvNeXt blocks in each main block's leading stack, at dilations 1, 2, 4 and 8.
const LEADING: [u32; 4] = [1, 2, 4, 8];

/// ConvNeXt blocks after the last main block, all at dilation 1. `last_convnext.num_layers`.
pub const TRAILING: usize = 4;

/// ConvNeXt blocks in the whole net: `4 * (4 + 1 + 1) + 4`.
pub const BLOCKS: usize = MAIN_BLOCKS * 6 + TRAILING;

/// The depthwise kernel width, `ksz`.
const KERNEL: u32 = 5;

/// The epsilon in all 36 layer norms.
const EPSILON: f32 = 1e-5;

/// Rotary frequencies, which is `head_dim / 2` of the text attention.
pub const FREQUENCIES: u32 = 32;

/// The guidance scale the export bakes in: `v = 4 * conditional - 3 * unconditional`.
pub const GUIDANCE: f32 = 4.0;

/// Tensors the plan itself reads: `proj_in`, 28 ConvNeXt blocks of eight, four main blocks'
/// conditioning, and `proj_out`.
pub const PLAN_TENSORS: usize = 2 + BLOCKS * 8 + MAIN_BLOCKS * 18 + 2;

/// Tensors the `.maml` must hold: the plan's, then the host's eighteen.
pub const TENSORS: usize = PLAN_TENSORS + 18;

/// The rotary `theta`, `[32]`. `rotary_scale * rotary_base ^ (-j / 32)`.
pub const HOST_THETA: usize = PLAN_TENSORS;

/// The timestep embedding's sinusoidal frequencies, `[32]`.
pub const HOST_FREQUENCIES: usize = PLAN_TENSORS + 1;

/// The timestep MLP's first `Linear`, `[256, 64]` then `[256]`.
pub const HOST_MLP_IN: usize = PLAN_TENSORS + 2;

/// The timestep MLP's second `Linear`, `[64, 256]` then `[64]`.
pub const HOST_MLP_OUT: usize = PLAN_TENSORS + 4;

/// The four per-block timestep `Linear`s, `[512, 64]` and `[512]` each, in block order.
pub const HOST_TIME_LINEARS: usize = PLAN_TENSORS + 6;

/// `uncond_masker.text_special_token`, `[256]`, broadcast over every text position.
pub const HOST_TEXT_TOKEN: usize = PLAN_TENSORS + 14;

/// `uncond_masker.style_value_special_token`, `[256, 50]` and already transposed.
pub const HOST_STYLE_TOKEN: usize = PLAN_TENSORS + 15;

/// The folded conditional style keys, `[4 * 256, 50]` — one 256-channel block per main block.
pub const HOST_KEYS_CONDITIONAL: usize = PLAN_TENSORS + 16;

/// The folded unconditional style keys, `[4 * 256, 50]`.
pub const HOST_KEYS_UNCONDITIONAL: usize = PLAN_TENSORS + 17;

/// Hands out `.maml` tensor indices in the order the layers appear.
struct Layers {
    next: usize,
}

impl Layers {
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }
}

/// A `1 x 1` convolution, which every projection here is.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// One ConvNeXt block: depthwise, layer norm, widening 1x1 with a GELU, narrowing 1x1, residual.
///
/// Symmetric edge padding of `2 * dilation` each side, as in the text encoder. The block's
/// `[1, 512, 1]` gamma is folded into `pwconv2` by the converter.
fn convnext(b: &mut Builder, l: &mut Layers, x: Id, dilation: u32) -> Id {
    let each = dilation * (KERNEL - 1) / 2;
    let along = b.conv(
        x,
        l.take(),
        CHANNELS,
        (1, KERNEL),
        (1, 1),
        (1, dilation),
        (0, each, 0, each),
        CHANNELS,
        Act::None,
    );
    let normed = b.layer_norm(along, l.take(), EPSILON);
    let widened = point(b, l, normed, INNER, Act::Gelu);
    let narrowed = point(b, l, widened, CHANNELS, Act::None);
    b.add(x, narrowed)
}

/// Build one guidance branch of the sampler.
///
/// Seven inputs, in this order:
///
/// 0. `noisy_latent`, `[144, 1, frames]`
/// 1. the text conditioning, `[256, 1, chars]` — the text encoder's output, or the unconditional
///    token broadcast
/// 2. the folded style keys, `[1024, 1, 50]` — four stacked 256-channel blocks, one per main
///    block
/// 3. the style values, `[256, 1, 50]` — the voice's `style_ttl` transposed, or the unconditional
///    token
/// 4. the four timestep shifts, `[2048, 1, 1]`
/// 5. the query rotary angles, `[64, 1, frames]`
/// 6. the key rotary angles, `[64, 1, chars]`
///
/// The output is this branch's velocity, `[144, 1, frames]`. Combining the two branches and
/// taking the Euler step is [`crate::post::supertonic::step`].
pub fn build(weights: &dyn WeightSource, frames: u32, chars: u32) -> Result<Plan, String> {
    if frames == 0 {
        return Err("a sampler pass over no frames".into());
    }
    if chars == 0 {
        return Err("a sampler pass over no characters".into());
    }

    let l = &mut Layers { next: 0 };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // Every padded convolution here replicates its border; all 28 `Pad`s are `mode=edge`.
    b.edge_padding();

    let latent = b.input(Shape::new(LATENT, 1, frames));
    let text = b.input(Shape::new(TEXT, 1, chars));
    let style_keys = b.input(Shape::new(STYLE * MAIN_BLOCKS as u32, 1, STYLE_TOKENS));
    let style_values = b.input(Shape::new(STYLE, 1, STYLE_TOKENS));
    let shifts = b.input(Shape::new(CHANNELS * MAIN_BLOCKS as u32, 1, 1));
    let query_angles = b.input(Shape::new(TEXT_HEADS * 2 * FREQUENCIES / TEXT_HEADS, 1, frames));
    let key_angles = b.input(Shape::new(2 * FREQUENCIES, 1, chars));

    // `proj_in` has no bias in the export; the converter synthesises a zero one.
    let mut x = point(b, l, latent, CHANNELS, Act::None);

    for block in 0..MAIN_BLOCKS {
        for &dilation in &LEADING {
            x = convnext(b, l, x, dilation);
        }

        // The timestep shift, one 512-vector per main block out of the host's `[2048, 1, 1]`.
        let shift = b.slice_channels(shifts, CHANNELS * block as u32, CHANNELS);
        x = b.add_channel(x, shift);

        x = convnext(b, l, x, 1);

        // The text cross-attention. Rotary on both sides, and the angles are normalised by each
        // sequence's own length so a query at the middle of the latent meets a key at the middle
        // of the text.
        let query = point(b, l, x, CHANNELS, Act::None);
        let query = b.rotary(query, query_angles, TEXT_HEADS);
        let keys = point(b, l, text, CHANNELS, Act::None);
        let keys = b.rotary(keys, key_angles, TEXT_HEADS);
        let values = point(b, l, text, CHANNELS, Act::None);
        let scores = b.attn_scores(query, keys, TEXT_HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, values, TEXT_HEADS);
        let projected = point(b, l, mixed, CHANNELS, Act::None);
        let residual = b.add(x, projected);
        x = b.layer_norm(residual, l.take(), EPSILON);

        x = convnext(b, l, x, 1);

        // The style cross-attention.

        // The style cross-attention. Its keys arrive already through `tanh`, folded, and each
        // main block has its own: they share one `style_key` but not one `W_key`.
        let keys = b.slice_channels(style_keys, STYLE * block as u32, STYLE);
        let query = point(b, l, x, STYLE, Act::None);
        let values = point(b, l, style_values, STYLE, Act::None);
        let scores = b.attn_scores(query, keys, STYLE_HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, values, STYLE_HEADS);
        let projected = point(b, l, mixed, CHANNELS, Act::None);
        let residual = b.add(x, projected);
        x = b.layer_norm(residual, l.take(), EPSILON);
    }

    for _ in 0..TRAILING {
        x = convnext(b, l, x, 1);
    }

    // `proj_out` has no bias either.
    let velocity = point(b, l, x, LATENT, Act::None);

    if l.next != PLAN_TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {PLAN_TENSORS}", l.next));
    }
    // Named rather than skipped: see `Builder::host_tensor`.
    b.host_tensor(HOST_THETA, &[FREQUENCIES]);
    b.host_tensor(HOST_FREQUENCIES, &[FREQUENCIES]);
    b.host_tensor(HOST_MLP_IN, &[TIME_INNER, TIME]);
    b.host_tensor(HOST_MLP_IN + 1, &[TIME_INNER]);
    b.host_tensor(HOST_MLP_OUT, &[TIME, TIME_INNER]);
    b.host_tensor(HOST_MLP_OUT + 1, &[TIME]);
    for block in 0..MAIN_BLOCKS {
        b.host_tensor(HOST_TIME_LINEARS + block * 2, &[CHANNELS, TIME]);
        b.host_tensor(HOST_TIME_LINEARS + block * 2 + 1, &[CHANNELS]);
    }
    b.host_tensor(HOST_TEXT_TOKEN, &[TEXT]);
    b.host_tensor(HOST_STYLE_TOKEN, &[STYLE, STYLE_TOKENS]);
    b.host_tensor(HOST_KEYS_CONDITIONAL, &[STYLE * MAIN_BLOCKS as u32, STYLE_TOKENS]);
    b.host_tensor(HOST_KEYS_UNCONDITIONAL, &[STYLE * MAIN_BLOCKS as u32, STYLE_TOKENS]);

    builder.finish(&[velocity])
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// About four seconds of audio, and a sentence to match.
    const FRAMES: u32 = 54;
    const CHARS: u32 = 24;

    fn plan(frames: u32, chars: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, frames, chars).expect("the sampler builds");
        (source, plan)
    }

    #[test]
    fn the_pass_reads_every_tensor_in_the_file_exactly_once() {
        let (source, _) = plan(FRAMES, CHARS);
        let asked = source.asked.borrow();
        assert_eq!(asked.len(), TENSORS);
        let mut indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        indices.sort_unstable();
        assert_eq!(indices, (0..TENSORS).collect::<Vec<usize>>());
    }

    #[test]
    fn the_tensor_table_matches_the_export() {
        // Checked as a sum of its own parts, because the export's float initializer total
        // (64,013,449) also holds a few dozen scalar literals — the guidance 4 and 3, the
        // score divisor 16, the GELU constants — which are initializers here rather than
        // `Constant` nodes, so an exact equation against it would be reconciling weights
        // against arithmetic. The material differences from that figure are:
        //
        //   - the 28 `[1, 512, 1]` layer scales fold into their `pwconv2`:      -14,336
        //   - the four style `W_key`s and their biases fold into the keys:     -263,168
        //   - the folded keys are four stacked `[256, 50]` per branch, where
        //     the export holds one `[1, 50, 256]` `style_key` per branch:      +76,800
        //   - `proj_in` and `proj_out` have no bias, so two are synthesised:      +656
        //   - the timestep embedding's frequency table is a `Constant` node
        //     in the export rather than an initializer, so it is new here:         +32
        //
        // `increments` is an int64 ramp and never counted; the two `[1, 50, 256]` style key
        // constants leave and eight folded `[256, 50]` tensors arrive.
        let (source, _) = plan(FRAMES, CHARS);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();

        let projection = |a: u64, b: u64| a * b + a;
        let block = 2_560 + 512 + 512 + 512 + 1_048_576 + 2_048 + 1_048_576 + 512;
        let text_attention = projection(512, 512) * 2 + projection(512, 256) * 2 + 1_024;
        let style_attention =
            projection(256, 512) + projection(256, 256) + projection(512, 256) + 1_024;
        let host = 32 + 32
            + projection(256, 64)
            + projection(64, 256)
            + projection(512, 64) * MAIN_BLOCKS as u64
            + 256
            + 12_800
            + 12_800 * 2 * MAIN_BLOCKS as u64;
        assert_eq!(
            total,
            projection(512, 144)
                + block * BLOCKS as u64
                + (text_attention + style_attention) * MAIN_BLOCKS as u64
                + projection(144, 512)
                + host
        );
        // And spelled out, so a reordering that preserved the sum would still be caught.
        assert_eq!(total, 63_813_392);
    }

    #[test]
    fn the_inputs_are_the_latent_the_two_conditionings_and_the_two_angle_tables() {
        let (_, plan) = plan(FRAMES, CHARS);
        let inputs: Vec<Shape> = plan.inputs.iter().map(|b| b.shape).collect();
        assert_eq!(
            inputs,
            vec![
                Shape::new(LATENT, 1, FRAMES),
                Shape::new(TEXT, 1, CHARS),
                Shape::new(STYLE * MAIN_BLOCKS as u32, 1, STYLE_TOKENS),
                Shape::new(STYLE, 1, STYLE_TOKENS),
                Shape::new(CHANNELS * MAIN_BLOCKS as u32, 1, 1),
                Shape::new(2 * FREQUENCIES, 1, FRAMES),
                Shape::new(2 * FREQUENCIES, 1, CHARS),
            ]
        );
        // One branch's velocity, at the latent's own shape. Not the Euler step: that is
        // `post::supertonic::step`, which needs both branches.
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(LATENT, 1, FRAMES)
        );
    }

    #[test]
    fn the_text_attention_is_a_cross_attention_of_frames_against_characters() {
        // `[8, frames, chars]` — the shape that made the cross-attention change necessary. The
        // style one is `[2, frames, 50]`.
        let (_, plan) = plan(FRAMES, CHARS);
        let maps: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    Some((push.out_c, push.out_h, push.out_w))
                }
                _ => None,
            })
            .collect();
        let mut want = Vec::new();
        for _ in 0..MAIN_BLOCKS {
            want.push((TEXT_HEADS, FRAMES, CHARS));
            want.push((STYLE_HEADS, FRAMES, STYLE_TOKENS));
        }
        assert_eq!(maps, want);
    }

    #[test]
    fn rotary_runs_on_both_sides_of_every_text_attention() {
        // Eight in all: the query over the latent's length and the key over the text's. A
        // rotary on only one side would leave the two sequences in different frames and the
        // alignment would drift with the length ratio.
        let (_, plan) = plan(FRAMES, CHARS);
        let widths: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Rotary, push, .. } => {
                    Some((push.group, push.in_c, push.out_w))
                }
                _ => None,
            })
            .collect();
        let mut want = Vec::new();
        for _ in 0..MAIN_BLOCKS {
            want.push((TEXT_HEADS, 2 * FREQUENCIES, FRAMES));
            want.push((TEXT_HEADS, 2 * FREQUENCIES, CHARS));
        }
        assert_eq!(widths, want);
    }

    #[test]
    fn the_depthwise_dilations_are_the_exports_own_sequence() {
        // Per main block 1, 2, 4, 8 then 1 then 1; then four more at 1. Sixteen at dilation 1,
        // four each at 2, 4 and 8 — which is exactly the export's `Conv` inventory.
        let (_, plan) = plan(FRAMES, CHARS);
        let found: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.group == CHANNELS => {
                    Some((push.dil_w, push.pad_l))
                }
                _ => None,
            })
            .collect();
        let mut want = Vec::new();
        for _ in 0..MAIN_BLOCKS {
            for &dilation in &LEADING {
                want.push((dilation, dilation * (KERNEL - 1) / 2));
            }
            want.push((1, 2));
            want.push((1, 2));
        }
        for _ in 0..TRAILING {
            want.push((1, 2));
        }
        assert_eq!(found, want);
        let ones = found.iter().filter(|&&(d, _)| d == 1).count();
        assert_eq!(ones, 16);
        assert_eq!(found.len(), BLOCKS);
    }

    #[test]
    fn each_main_block_takes_its_own_slice_of_the_timestep_shifts() {
        // Four shifts of 512 out of one `[2048, 1, 1]` input, in block order. Reading the same
        // slice four times would condition every block on the first one's timestep - which at
        // step 0 of 16 is not even wrong by much, and gets worse as the step index grows.
        let (_, plan) = plan(FRAMES, CHARS);
        let shifts: Vec<u32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::AddBroadcast, push, .. } => Some(push.out_c),
                _ => None,
            })
            .collect();
        assert_eq!(shifts, vec![CHANNELS; MAIN_BLOCKS]);
        // Four timestep slices and four style-key slices, each lowering to one copy.
        let copies = plan.ops.iter().filter(|op| matches!(op, Op::Copy { .. })).count();
        assert_eq!(copies, MAIN_BLOCKS * 2);
    }

    #[test]
    fn the_op_inventory_is_twenty_eight_convnext_blocks_and_eight_attentions() {
        let (_, plan) = plan(FRAMES, CHARS);
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0) += 1;
            }
        }
        // Three per ConvNeXt block, four per text attention (query, key, value, output),
        // three per style attention, plus the two projections.
        let convolutions = BLOCKS * 3 + MAIN_BLOCKS * (4 + 3) + 2;
        assert_eq!(counts.get("Conv"), Some(&convolutions), "{counts:?}");
        assert_eq!(counts.get("Rotary"), Some(&(MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&(MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&(MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&(MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("AddBroadcast"), Some(&MAIN_BLOCKS), "{counts:?}");
        assert_eq!(counts.get("LayerNorm"), Some(&(BLOCKS + MAIN_BLOCKS * 2)), "{counts:?}");
        assert_eq!(counts.get("Add"), Some(&(BLOCKS + MAIN_BLOCKS * 2)), "{counts:?}");
        // No relative attention here: the positions are rotary.
        assert_eq!(counts.get("AttnScoresRelative"), None, "{counts:?}");
        assert_eq!(counts.get("Embed"), None, "{counts:?}");
        assert_eq!(counts.len(), 8, "{counts:?}");
    }

    #[test]
    fn nothing_changes_the_latent_length() {
        let (_, plan) = plan(FRAMES, CHARS);
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
                assert!(
                    push.out_w == FRAMES || push.out_w == CHARS || push.out_w == STYLE_TOKENS,
                    "step {step} produced {} positions",
                    push.out_w
                );
                assert_ne!(push.pad_edge, 0, "step {step} pads with zeros");
            }
        }
    }

    #[test]
    fn an_empty_latent_or_text_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, 0, CHARS).expect_err("no frames");
        assert!(error.contains("no frames"), "{error}");
        let source = Shapes::new(TENSORS);
        let error = build(&source, FRAMES, 0).expect_err("no characters");
        assert!(error.contains("no characters"), "{error}");
    }

    #[test]
    fn no_op_reads_a_region_of_the_arena_that_it_also_writes() {
        let (_, plan) = plan(FRAMES, CHARS);
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_arena_is_bounded_at_a_long_utterance() {
        // This is the net that decides how much device memory a voice needs: 2048 channels over
        // the latent, and an `[8, frames, chars]` score map four times.
        for (frames, chars) in [(FRAMES, CHARS), (216, 400)] {
            let (_, plan) = plan(frames, chars);
            let mib = plan.arena_elems as f32 * 2.0 / (1024.0 * 1024.0);
            println!("supertonic_sampler at {frames} frames, {chars} chars: {mib:.2} MiB");
            assert!(mib < 256.0, "{frames} frames wants {mib} MiB");
        }
    }
}
