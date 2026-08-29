//! SMaLL-100: a 100-language translation encoder-decoder, in one `.maml`.
//!
//! # What it is
//!
//! M2M-100 distilled to a 3-layer decoder: 12 encoder layers, 3 decoder layers, `d_model` 1024,
//! 16 heads of 64, a 4096-wide feed-forward, and a 128,112-entry vocabulary shared between the
//! input embedding and the output projection. 332,735,488 parameters, 318 MiB as int8.
//!
//! The Kotlin it replaces claimed "36 enc + 13 dec Gemm+MHA layers". Those were **ncnn's** counts,
//! with multi-head attention fused into one layer, and they do not map onto anything here;
//! `scripts/ml/fetch_small100.py` asserts the real numbers against the checkpoint's `config.json`
//! rather than trusting either.
//!
//! # One file, three passes
//!
//! [`Mode`] selects which of them [`build`] emits. They share the file because the embedding is
//! **tied**: it is the encoder's input table, the decoder's input table and the logits kernel, and
//! two files would upload 125 MiB of it twice. Each pass names the others' tensors with
//! [`Builder::host_tensor`], and `tests::the_passes_partition_the_file_and_every_one_of_them_builds`
//! is what keeps that from hiding a genuinely unread layer.
//!
//! # The embedding is gathered on the host
//!
//! There is no int8 `embed.comp`, and there does not need to be. M2M-100's positions are **static
//! sinusoids**, so a token's input vector is a function of one table row and one integer:
//!
//! ```text
//! x[t] = embedding[id[t]] * sqrt(1024) + sinusoid(t + 2)
//! ```
//!
//! [`embed_positions`] reads the row with [`crate::weights::Reader::int8_row`], dequantises it by
//! that row's own scale, scales and adds the position in f32, and hands the result in as an
//! ordinary fp16 plan input. That is 1 KB of file reads per token against a 125 MiB upload, it
//! keeps `sqrt(1024)` out of the int8 rounding, and it is why the ncnn build's
//! `pos_weights.f32.bin` download is gone.
//!
//! ## The position offset is 2, not 0
//!
//! fairseq numbers positions as `cumsum(mask) * mask + padding_idx` with `padding_idx = 1`, so the
//! first real token sits at **position 2**. An off-by-two here produces fluent, plausible, subtly
//! wrong output, and no shape check anywhere catches it. It is pinned in
//! `tests::the_first_token_sits_at_position_two` and against onnxruntime.
//!
//! The sinusoid is fairseq's, which is not the usual one either: the two halves are
//! `[sin(all 512), cos(all 512)]` **concatenated rather than interleaved**, and the frequency
//! spacing divides by `half_dim - 1` = 511, not 512.
//!
//! # The head is two ops, not one
//!
//! 128,112 classes at 1024 channels is 125.1 MiB of int8, and `maxStorageBufferRange`'s guaranteed
//! minimum is 128 MiB — so one binding would be inside the limit only just, and an fp16 embedding
//! would not fit at all. `scripts/ml/maml_convert.py` therefore emits the tied weight as **two**
//! tensors over disjoint class ranges, [`Mode::Logits`] emits two `ConvPointInt8` ops, and
//! `post::translate` argmaxes over both halves — which it already did, because it argmaxes anyway.
//!
//! # No attention mask, and none needed
//!
//! The runtime has no additive mask and no causal flag. Neither is required:
//!
//! * The encoder runs over one sentence with no padding, so every key is real.
//! * The decoder decodes one token at a time, so a step is **one query against `step + 1` keys**,
//!   which is causal by construction. That is what [`Builder::attn_scores`]'s support for
//!   differing query and key lengths buys.
//!
//! [`Builder::attn_scores`] applies `1 / sqrt(head_dim)` itself, and `head_dim` is 64, which is
//! exactly M2M-100's `self.scaling`. So nothing folds a query scale, unlike Supertonic's text
//! encoder.
//!
//! # Pre-norm
//!
//! `M2M100EncoderLayer` and `M2M100DecoderLayer` normalise **before** each sublayer and skip
//! around both, and each stack ends with one more layer norm. Written the other way round the net
//! still runs and still produces text.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use crate::weights::Reader;

/// Channels throughout: `d_model`.
pub const D_MODEL: u32 = 1024;

/// Attention heads, so `head_dim` is 64 and [`Builder::attn_scores`]'s own scale is the model's.
pub const HEADS: u32 = 16;

/// The feed-forward width, `encoder_ffn_dim` and `decoder_ffn_dim`.
pub const FFN: u32 = 4096;

/// Vocabulary entries, shared by the embedding and the logits projection.
pub const VOCAB: u32 = 128_112;

/// Class ranges the tied weight is emitted as. See the module docs.
pub const HEAD_SPLITS: usize = 2;

/// Classes in each half. 128,112 is `2 * 64,056`, so neither range is padded.
pub const CLASSES_PER_SPLIT: u32 = VOCAB / HEAD_SPLITS as u32;

/// Encoder layers.
pub const ENCODER_LAYERS: usize = 12;

/// Decoder layers. Three is what makes SMaLL-100 four times faster than M2M-100.
pub const DECODER_LAYERS: usize = 3;

/// `max_position_embeddings`, and therefore the longest source this can encode.
pub const MAX_POSITIONS: u32 = 1024;

/// fairseq's padding id, which is also the offset every position is shifted by.
const PADDING_IDX: u32 = 1;

/// The epsilon in every layer norm.
const EPSILON: f32 = 1e-5;

/// Tensors per encoder layer: two norms of two, four projections of three, two more of three.
const ENCODER_LAYER_TENSORS: usize = 2 + 4 * 3 + 2 + 3 + 3;

/// Tensors per decoder layer: an encoder layer plus a cross-attention norm and four projections.
const DECODER_LAYER_TENSORS: usize = ENCODER_LAYER_TENSORS + 2 + 4 * 3;

/// The first of the two head halves. They come first, because the embedding is read before
/// anything else and the file is written in forward order.
const HEAD: usize = 0;

/// The first encoder layer.
const ENCODER: usize = HEAD + HEAD_SPLITS * 3;

/// The encoder's trailing layer norm.
const ENCODER_NORM: usize = ENCODER + ENCODER_LAYERS * ENCODER_LAYER_TENSORS;

/// The first decoder layer.
const DECODER: usize = ENCODER_NORM + 2;

/// The decoder's trailing layer norm.
const DECODER_NORM: usize = DECODER + DECODER_LAYERS * DECODER_LAYER_TENSORS;

/// Tensors the `.maml` must hold, and the count `maml_convert.py` writes.
pub const TENSORS: usize = DECODER_NORM + 2;

/// Convolutions read as int8 rather than fp16, each carrying a third tensor for its scale.
///
/// **Every one of them.** Both head halves, all six projections of each encoder layer and all ten
/// of each decoder layer — 104 tensors and 99.98% of the parameters. Nothing is excluded, and that
/// is a measurement rather than an omission: `maml_convert.collect_small100` reports the worst
/// per-tensor correlation between the fp32 weight and `int8 * scale` over all 104, and it is
/// 0.999808 on a head half, against the 0.999 floor Supertonic's reverted text encoder failed at
/// 0.99212.
///
/// Only the layer norms and the biases stay fp16, and they are 0.02% of the file. Quantising a
/// rank-1 per-channel affine would save 250 KB and cost the normalisation its precision.
///
/// The reason this is even possible is that the weights come from the **fp32 checkpoint**: the
/// int8 ONNX exports that exist for SMaLL-100 quantise per tensor, which throws away a factor of
/// four on the embedding alone. See `CHECKPOINTS` in `scripts/ml/maml_convert.py`.
pub const INT8_CONVS: usize = HEAD_SPLITS + ENCODER_LAYERS * 6 + DECODER_LAYERS * 10;

/// Which forward pass [`build`] emits.
///
/// One graph and three plans, run through [`crate::vulkan::run::Net::rebuild`] rather than three
/// nets, so the 318 MiB upload happens once.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// The encoder over `len` tokens: `[1024, 1, len]` in, `[1024, 1, len]` out.
    Encode {
        /// Source tokens, after [`embed_positions`].
        len: u32,
    },
    /// The tied logits projection: one `[1024]` state in, two half-vocabularies out.
    Logits,
}

/// Hands out `.maml` tensor indices in the order the layers appear.
struct Layers {
    next: usize,
}

impl Layers {
    /// A weight and the bias after it: a layer norm's gamma and beta.
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }

    /// An int8 kernel, its per-output-channel scale, and the bias after that.
    fn take3(&mut self) -> usize {
        let index = self.next;
        self.next += 3;
        index
    }
}

/// A `1 x 1` convolution with an int8 kernel, which every projection here is.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv_int8(x, l.take3(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// One pre-norm attention sublayer, plus its residual.
///
/// `queries` is where the query comes from and `keys` where the key and value do. They are the
/// same tensor for self-attention and differ for the cross-attention, which is the only difference
/// between the two — and the reason `attn_scores` and `attn_apply` both take two lengths.
fn attention(b: &mut Builder, l: &mut Layers, residual: Id, queries: Id, keys: Id) -> Id {
    let q = point(b, l, queries, D_MODEL, Act::None);
    let k = point(b, l, keys, D_MODEL, Act::None);
    let v = point(b, l, keys, D_MODEL, Act::None);
    let scores = b.attn_scores(q, k, HEADS);
    let probs = b.softmax(scores);
    let mixed = b.attn_apply(probs, v, HEADS);
    let projected = point(b, l, mixed, D_MODEL, Act::None);
    b.add(residual, projected)
}

/// The pre-norm feed-forward sublayer, plus its residual. `relu`, not `gelu`.
fn feed_forward(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let normed = b.layer_norm(x, l.take(), EPSILON);
    let inner = point(b, l, normed, FFN, Act::Relu);
    let projected = point(b, l, inner, D_MODEL, Act::None);
    b.add(x, projected)
}

/// Build one of SMaLL-100's three passes. See [`Mode`].
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    match mode {
        Mode::Encode { len } => encode(weights, len),
        Mode::Logits => logits(weights),
    }
}

/// The encoder over `len` already-embedded positions.
fn encode(weights: &dyn WeightSource, len: u32) -> Result<Plan, String> {
    if len == 0 {
        return Err("an encoder pass over no tokens".into());
    }
    if len > MAX_POSITIONS {
        return Err(format!("{len} tokens, past the {MAX_POSITIONS} positions the model has"));
    }

    let l = &mut Layers { next: ENCODER };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The tied weight and the whole decoder belong to the other two passes. The trailing encoder
    // norm is part of this range, so it runs to `DECODER` rather than to `ENCODER_NORM`.
    name_host_tensors(b, ENCODER..DECODER);

    let x = b.input(Shape::new(D_MODEL, 1, len));
    let mut x = x;
    for _ in 0..ENCODER_LAYERS {
        let normed = b.layer_norm(x, l.take(), EPSILON);
        x = attention(b, l, x, normed, normed);
        x = feed_forward(b, l, x);
    }
    if l.next != ENCODER_NORM {
        return Err(format!("the encoder claims {} tensors, not {ENCODER_NORM}", l.next));
    }
    let out = b.layer_norm(x, l.take(), EPSILON);
    if l.next != DECODER {
        return Err(format!("the encoder norm ends at {}, not {DECODER}", l.next));
    }
    builder.finish(&[out])
}

/// The tied logits projection over one decoder state.
///
/// Two ops rather than one, over disjoint class ranges, so no binding approaches
/// `maxStorageBufferRange`. Both outputs come back in declaration order and `post::translate`
/// argmaxes across them, which needs no change: it argmaxed a single 128,112-wide vector before,
/// and the winner of two halves is the winner of the whole.
fn logits(weights: &dyn WeightSource) -> Result<Plan, String> {
    let l = &mut Layers { next: HEAD };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    name_host_tensors(b, HEAD..ENCODER);

    let state = b.input(Shape::new(D_MODEL, 1, 1));
    let halves: Vec<Id> = (0..HEAD_SPLITS)
        .map(|_| point(b, l, state, CLASSES_PER_SPLIT, Act::None))
        .collect();
    if l.next != ENCODER {
        return Err(format!("the head claims {} tensors, not {ENCODER}", l.next));
    }
    builder.finish(&halves)
}

/// Name every tensor **outside** `read` as one this pass does not touch.
///
/// [`Builder::finish`] refuses an unread tensor, and no one of the three passes reads the whole
/// file. Declaring the complement rather than listing it keeps the two in step: adding a layer
/// changes the range and nothing else.
fn name_host_tensors(b: &mut Builder, read: std::ops::Range<usize>) {
    for index in 0..TENSORS {
        if !read.contains(&index) {
            b.host_tensor(index, &dims_of(index));
        }
    }
}

/// The shape of tensor `index`, derived from the layout constants.
///
/// `host_tensor` checks it against the file, so this is a *second* statement of the table that
/// `maml_convert.collect_small100` writes — which is the point: a converter and a runtime that
/// disagree about a shape fail here rather than on the device.
fn dims_of(index: usize) -> Vec<u32> {
    if index < ENCODER {
        // A head half: int8 kernel, per-class scale, synthesised zero bias.
        return match index % 3 {
            0 => vec![CLASSES_PER_SPLIT, D_MODEL, 1, 1],
            _ => vec![CLASSES_PER_SPLIT],
        };
    }
    let (base, per_layer, layers) = if index < DECODER {
        (ENCODER, ENCODER_LAYER_TENSORS, ENCODER_LAYERS)
    } else {
        (DECODER, DECODER_LAYER_TENSORS, DECODER_LAYERS)
    };
    let end = base + per_layer * layers;
    if index >= end {
        // One of the two trailing layer norms.
        return vec![D_MODEL];
    }
    layer_dims((index - base) % per_layer, per_layer)
}

/// The shape of the `within`th tensor of a layer of `per_layer` tensors.
fn layer_dims(within: usize, per_layer: usize) -> Vec<u32> {
    // A layer is a sequence of groups: `[2]` for a norm, `[out, in, 1, 1] [out] [out]` for a
    // projection. Walking them is shorter than a table and cannot disagree with `Layers`.
    let mut groups: Vec<(usize, u32, u32)> = vec![(2, 0, 0)];
    groups.extend([(3, D_MODEL, D_MODEL); 4]);
    if per_layer == DECODER_LAYER_TENSORS {
        groups.push((2, 0, 0));
        groups.extend([(3, D_MODEL, D_MODEL); 4]);
    }
    groups.push((2, 0, 0));
    groups.push((3, FFN, D_MODEL));
    groups.push((3, D_MODEL, FFN));

    let mut at = 0;
    for (size, out, inputs) in groups {
        if within < at + size {
            let offset = within - at;
            return match (size, offset) {
                // A norm's gamma and beta.
                (2, _) => vec![D_MODEL],
                // A projection's kernel, then its scale and its bias.
                (_, 0) => vec![out, inputs, 1, 1],
                _ => vec![out],
            };
        }
        at += size;
    }
    vec![D_MODEL]
}

/// The embedded, scaled and positioned source for `ids`, in the channel-major layout the plan
/// wants.
///
/// The `[1024, 1, len]` fp16 input to [`Mode::Encode`], as f32 for the caller to upload. See the
/// module docs for why this is on the host and for the `+ 2` on the position.
///
/// `past` is how many positions precede these ids, which is 0 for the encoder and the step number
/// for the decoder — fairseq's `past_key_values_length`, and it lands in the same arithmetic.
pub fn embed_positions(
    weights: Reader<'_>,
    ids: &[u32],
    past: u32,
) -> Result<Vec<f32>, String> {
    let width = D_MODEL as usize;
    let mut out = vec![0.0f32; width * ids.len()];
    let scale = (D_MODEL as f32).sqrt();
    for (at, &id) in ids.iter().enumerate() {
        if id >= VOCAB {
            return Err(format!("token {id} is past the {VOCAB}-entry vocabulary"));
        }
        let half = (id / CLASSES_PER_SPLIT) as usize;
        let row = id % CLASSES_PER_SPLIT;
        let kernel = HEAD + half * 3;
        let embedding = weights.int8_row(
            kernel,
            kernel + 1,
            &[CLASSES_PER_SPLIT, D_MODEL, 1, 1],
            row,
        )?;
        let position = past + at as u32 + 1 + PADDING_IDX;
        // `make_weights(num_positions + padding_idx + 1, ...)`, so the table's last row is
        // `MAX_POSITIONS + PADDING_IDX`.
        if position > MAX_POSITIONS + PADDING_IDX {
            return Err(format!("position {position} is past the model's table"));
        }
        for (channel, value) in embedding.iter().enumerate() {
            // Channel-major: this runtime indexes `[c, h, w]`, and the export is `[w, c]`.
            let slot = out
                .get_mut(channel * ids.len() + at)
                .ok_or("an embedding row is wider than d_model")?;
            *slot = value * scale + sinusoid(position, channel);
        }
    }
    Ok(out)
}

/// fairseq's sinusoidal position, channel `channel` of position `position`.
///
/// Not the interleaved `sin, cos, sin, cos` of the original transformer paper: the halves are
/// **concatenated**, so channels `0..512` are all the sines and `512..1024` all the cosines. And
/// the frequency spacing divides `ln(10000)` by `half_dim - 1`, not by `half_dim`.
///
/// `padding_idx` is 1 and its row is zeroed upstream, which never matters here because
/// [`embed_positions`] never asks for it — the smallest position it can produce is 2.
fn sinusoid(position: u32, channel: usize) -> f32 {
    let half = D_MODEL as usize / 2;
    let index = channel % half;
    let spacing = (10_000.0f32).ln() / (half as f32 - 1.0);
    let angle = position as f32 * (-(index as f32) * spacing).exp();
    if channel < half {
        angle.sin()
    } else {
        angle.cos()
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// A short sentence: a language token, six pieces and `</s>`.
    const LEN: u32 = 8;

    fn plan(mode: Mode) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, mode).expect("the pass builds");
        (source, plan)
    }

    #[test]
    fn the_layout_matches_the_converter() {
        // The numbers `maml_convert.py --print-layers` reports for this graph. A disagreement here
        // is a plan that reads one layer's weights as another's.
        assert_eq!(ENCODER_LAYER_TENSORS, 22);
        assert_eq!(DECODER_LAYER_TENSORS, 36);
        assert_eq!((HEAD, ENCODER, ENCODER_NORM), (0, 6, 270));
        assert_eq!((DECODER, DECODER_NORM), (272, 380));
        assert_eq!(TENSORS, 382);
        assert_eq!(INT8_CONVS, 104);
        assert_eq!(CLASSES_PER_SPLIT, 64_056);
    }

    #[test]
    fn the_parameter_total_matches_the_checkpoint() {
        // What the file holds, from the layout `every_tensor_shape_is_stated_the_same_way_twice`
        // pins, against the checkpoint plus exactly the two things quantising adds.
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();

        // One fp16 scale per output channel of each of the 104 int8 convolutions.
        let scales = u64::from(CLASSES_PER_SPLIT) * HEAD_SPLITS as u64
            + ENCODER_LAYERS as u64 * u64::from(4 * D_MODEL + FFN + D_MODEL)
            + DECODER_LAYERS as u64 * u64::from(8 * D_MODEL + FFN + D_MODEL);
        assert_eq!(scales, 278_640);
        // And a zero bias for each head half, which a tied projection has no weight for.
        let synthesised = u64::from(CLASSES_PER_SPLIT) * HEAD_SPLITS as u64;

        assert_eq!(total, 332_735_488 + scales + synthesised);

        // 318.3 MiB on disk, against 1.14 GB of ncnn fp16 on the mirror today. Everything but the
        // 104 kernels is fp16, and the kernels are 99.8% of the elements — which is the whole
        // reason the file is a third of a fp16 one rather than half.
        let kernels = u64::from(CLASSES_PER_SPLIT) * u64::from(D_MODEL) * HEAD_SPLITS as u64
            + ENCODER_LAYERS as u64 * u64::from(D_MODEL) * u64::from(4 * D_MODEL + 2 * FFN)
            + DECODER_LAYERS as u64 * u64::from(D_MODEL) * u64::from(8 * D_MODEL + 2 * FFN);
        assert_eq!(kernels, 332_513_280);
        let file = kernels + (total - kernels) * 2;
        assert!((318 << 20..319 << 20).contains(&file), "{file} bytes");
    }

    /// The tensor range each pass reads on the device. Everything else it names.
    ///
    /// Stated here rather than returned by [`build`] because it is the thing under test: a pass
    /// that read the wrong range would name the right one and still be wrong.
    fn read_by(mode: Mode) -> std::ops::Range<usize> {
        match mode {
            Mode::Encode { .. } => ENCODER..DECODER,
            Mode::Logits => HEAD..ENCODER,
        }
    }

    #[test]
    fn the_passes_partition_the_file_and_every_one_of_them_builds() {
        // `Builder::finish` only checks that a tensor is read *or* named, so this is what stops
        // naming being used to hide a layer the device never touches. The ranges must tile
        // `0..TENSORS` with no gap and no overlap.
        //
        // The encoder's range runs to `DECODER`, so it covers the trailing encoder norm; the
        // decoder's remaining range is `DECODER..TENSORS`, which the decode step claims.
        let mut covered: Vec<usize> = Vec::new();
        for mode in [Mode::Encode { len: LEN }, Mode::Logits] {
            let source = Shapes::new(TENSORS);
            build(&source, mode).unwrap_or_else(|e| panic!("{mode:?}: {e}"));
            covered.extend(read_by(mode));
        }
        covered.extend(DECODER..TENSORS);
        covered.sort_unstable();
        assert_eq!(covered, (0..TENSORS).collect::<Vec<usize>>());
    }

    #[test]
    fn the_encoder_is_twelve_pre_norm_layers() {
        let (_, plan) = plan(Mode::Encode { len: LEN });
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0usize) += 1;
            }
        }
        // Six int8 convolutions per layer: q, k, v, the output projection, fc1 and fc2.
        assert_eq!(counts.get("ConvInt8"), Some(&(ENCODER_LAYERS * 6)), "{counts:?}");
        // Three norms per layer would be post-norm. Pre-norm is two, plus one at the end.
        assert_eq!(counts.get("LayerNorm"), Some(&(ENCODER_LAYERS * 2 + 1)), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&ENCODER_LAYERS), "{counts:?}");
        // Two residuals per layer.
        assert_eq!(counts.get("Add"), Some(&(ENCODER_LAYERS * 2)), "{counts:?}");
        assert_eq!(counts.len(), 6, "{counts:?}");
    }

    #[test]
    fn the_encoder_keeps_its_length_and_width() {
        let (_, plan) = plan(Mode::Encode { len: LEN });
        assert_eq!(plan.input().unwrap().shape, Shape::new(D_MODEL, 1, LEN));
        assert_eq!(plan.output().unwrap().shape, Shape::new(D_MODEL, 1, LEN));
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_attention_score_maps_are_square_in_the_encoder() {
        // Self-attention over one sentence, so queries and keys are the same positions. The
        // cross-attention in the decode step is what makes them differ.
        let (_, plan) = plan(Mode::Encode { len: LEN });
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::AttnScores, push, .. } = op {
                assert_eq!((push.group, push.out_h, push.out_w), (HEADS, LEN, LEN), "{push:?}");
            }
        }
    }

    #[test]
    fn the_head_is_two_ops_over_disjoint_class_ranges() {
        let (_, plan) = plan(Mode::Logits);
        let dispatches: Vec<_> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind, push, .. } => Some((*kind, *push)),
                _ => None,
            })
            .collect();
        assert_eq!(dispatches.len(), HEAD_SPLITS, "{dispatches:?}");
        for (kind, push) in &dispatches {
            assert_eq!(super::super::tests::name_of(*kind), "ConvInt8");
            assert_eq!(push.out_c, CLASSES_PER_SPLIT, "{push:?}");
            assert_eq!(push.in_c, D_MODEL, "{push:?}");
        }
        // Different kernels, which is what "disjoint ranges" means in the file.
        assert_ne!(dispatches[0].1.weight, dispatches[1].1.weight);
        assert_eq!(plan.outputs.len(), HEAD_SPLITS);
        for binding in &plan.outputs {
            assert_eq!(binding.shape, Shape::new(CLASSES_PER_SPLIT, 1, 1));
        }
        // Together they are the whole vocabulary, which is what `post::translate` argmaxes.
        let classes: u32 = plan.outputs.iter().map(|b| b.shape.c).sum();
        assert_eq!(classes, VOCAB);
    }

    #[test]
    fn the_head_binds_under_the_guaranteed_range() {
        // Why the split exists: 128,112 x 1024 int8 is 125.1 MiB against a guaranteed 128 MiB, and
        // fp16 would be 250. A half plus its scale and bias is comfortably inside either.
        let whole = u64::from(VOCAB) * u64::from(D_MODEL);
        assert!(whole > 120 << 20, "the whole table is {whole} bytes");
        let half = u64::from(CLASSES_PER_SPLIT) * u64::from(D_MODEL)
            + u64::from(CLASSES_PER_SPLIT) * 4;
        assert!(half < 96 << 20, "a half is {half} bytes");
    }

    #[test]
    fn the_first_token_sits_at_position_two() {
        // fairseq's `cumsum(mask) * mask + padding_idx`, which is the off-by-two that produces
        // fluent, plausible, wrong output. Asserted through the one arithmetic that uses it.
        assert_eq!(1 + PADDING_IDX, 2);
        // The decoder at step `s` has `past = s` and one id, and lands on `s + 2` by the same
        // expression: `past + at + 1 + padding_idx` with `at` zero.
        for step in 0..4u32 {
            assert_eq!(step + 1 + PADDING_IDX, step + 2);
        }
    }

    #[test]
    fn the_sinusoid_is_fairseqs_concatenated_halves() {
        // Position 0's sines are all zero and its cosines all one, which distinguishes the
        // concatenated layout from the interleaved one immediately: interleaved would alternate.
        let half = D_MODEL as usize / 2;
        for channel in 0..half {
            assert_eq!(sinusoid(0, channel), 0.0, "channel {channel}");
        }
        for channel in half..D_MODEL as usize {
            assert_eq!(sinusoid(0, channel), 1.0, "channel {channel}");
        }
        // The lowest frequency is 1, and the highest is `1 / 10000` — which needs the spacing to
        // divide by `half - 1`. Dividing by `half` puts the last channel at 10000^(-511/512).
        assert!((sinusoid(1, 0) - 1.0f32.sin()).abs() < 1e-6);
        let smallest = (1.0f32 / 10_000.0).sin();
        assert!((sinusoid(1, half - 1) - smallest).abs() < 1e-6, "{}", sinusoid(1, half - 1));
    }

    #[test]
    fn a_pass_over_nothing_or_past_the_table_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, Mode::Encode { len: 0 }).expect_err("no tokens");
        assert!(error.contains("no tokens"), "{error}");
        let source = Shapes::new(TENSORS);
        let error =
            build(&source, Mode::Encode { len: MAX_POSITIONS + 1 }).expect_err("too long");
        assert!(error.contains("positions the model has"), "{error}");
    }

    /// The host embedding gather against the real 318 MiB file.
    ///
    /// Skipped without `SMALL100_MAML`, like the tokenizer's real-vocabulary test: the file is a
    /// runtime download rather than a checked-in asset. `scripts/ml/fetch_small100.py` builds it.
    ///
    /// The pinned numbers came from reading the same file in Python and applying transformers'
    /// `M2M100SinusoidalPositionalEmbedding.get_embedding` verbatim, so this is a cross-check of
    /// three things at once: the int8 row dequantisation, `sqrt(1024)`, and the sinusoid's
    /// concatenated-halves convention at the `+ 2` position.
    #[test]
    fn the_host_gather_agrees_with_the_reference() {
        let Ok(path) = std::env::var("SMALL100_MAML") else {
            return;
        };
        let file = std::fs::File::open(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
        let len = file.metadata().expect("metadata").len();
        let streamed = crate::weights::Streamed::open(file, 0, len, crate::weights::graph::SMALL100)
            .expect("the real file opens");
        let table = streamed.offsets();
        let reader = Reader::new(&table, &streamed);

        // `__en__`, then the first piece of "Hello, world!", then `</s>` — at positions 2, 3, 4.
        let ids = [128_022u32, 65_761, 2];
        let out = embed_positions(reader, &ids, 0).expect("the gather succeeds");
        assert_eq!(out.len(), D_MODEL as usize * ids.len());

        // Channel-major: `out[c * len + t]`. A position-major layout would put these six values
        // contiguously at the start instead, so this also pins the transpose.
        let at = |channel: usize, token: usize| out[channel * ids.len() + token];
        let want: [(usize, f32); 9] = [
            (0, 8.618_953),
            (1, 2.024_961),
            (2, 1.425_964),
            (3, 0.703_241),
            (4, 0.835_844),
            (5, 0.722_445),
            (511, 0.856_828),
            (512, -1.884_653),
            (1023, 0.020_996),
        ];
        for (channel, expected) in want {
            let got = at(channel, 0);
            assert!(
                (got - expected).abs() < 2e-3,
                "channel {channel}: {got} against {expected}"
            );
        }

        // The same token at two positions must differ by exactly the two sinusoids' difference —
        // the property that fails if the position offset or the layout is wrong, with no fixture.
        let twice = embed_positions(reader, &[7, 7], 0).expect("the gather succeeds");
        for channel in 0..D_MODEL as usize {
            let first = twice[channel * 2];
            let second = twice[channel * 2 + 1];
            let delta = sinusoid(2, channel) - sinusoid(3, channel);
            assert!(
                ((first - second) - delta).abs() < 1e-3,
                "channel {channel}: {first} - {second} against {delta}"
            );
        }

        // `past` shifts the position, which is how a decode step at step `s` lands on `s + 2`.
        let shifted = embed_positions(reader, &[7], 1).expect("the gather succeeds");
        for channel in 0..D_MODEL as usize {
            let expected = twice[channel * 2] - sinusoid(2, channel) + sinusoid(3, channel);
            assert!((shifted[channel] - expected).abs() < 1e-3, "channel {channel}");
        }
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // `dims_of` restates the table `maml_convert.collect_small100` writes, and `Layers` walks
        // it. This checks the two agree for every index, which is what makes `host_tensor`'s shape
        // check meaningful rather than circular.
        let mut expected: Vec<Vec<u32>> = Vec::new();
        for _ in 0..HEAD_SPLITS {
            expected.push(vec![CLASSES_PER_SPLIT, D_MODEL, 1, 1]);
            expected.push(vec![CLASSES_PER_SPLIT]);
            expected.push(vec![CLASSES_PER_SPLIT]);
        }
        let projection = |out: u32, inputs: u32| {
            vec![vec![out, inputs, 1, 1], vec![out], vec![out]]
        };
        let norm = || vec![vec![D_MODEL], vec![D_MODEL]];
        for (layers, cross) in [(ENCODER_LAYERS, false), (DECODER_LAYERS, true)] {
            for _ in 0..layers {
                expected.extend(norm());
                for _ in 0..4 {
                    expected.extend(projection(D_MODEL, D_MODEL));
                }
                if cross {
                    expected.extend(norm());
                    for _ in 0..4 {
                        expected.extend(projection(D_MODEL, D_MODEL));
                    }
                }
                expected.extend(norm());
                expected.extend(projection(FFN, D_MODEL));
                expected.extend(projection(D_MODEL, FFN));
            }
            expected.extend(norm());
        }
        assert_eq!(expected.len(), TENSORS);
        for (index, want) in expected.iter().enumerate() {
            assert_eq!(&dims_of(index), want, "tensor {index}");
        }
    }
}
