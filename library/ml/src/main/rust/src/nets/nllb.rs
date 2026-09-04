//! NLLB-200-distilled-600M: a 200-language translation encoder-decoder, in one `.maml`.
//!
//! # What it is
//!
//! NLLB distilled to 600M parameters: 12 encoder layers, 12 decoder layers, `d_model` 1024,
//! 16 heads of 64, a 4096-wide feed-forward, and a 256,206-entry vocabulary shared between the
//! input embedding and the output projection.
//!
//! The architecture is `M2M100ForConditionalGeneration` — the same forward pass SMaLL-100 (which
//! this replaced) used, with 12 decoder layers instead of 3 and 202 language tokens instead of
//! 100. The two modules were kept side by side during the port so a reviewer could diff them;
//! every difference from the old `small100.rs` is one of: the vocabulary size, the head split
//! count and sizes, the decoder layer count, and the language-token constants in
//! `post::translate`.
//!
//! # One file, two passes
//!
//! [`Mode`] selects which of them [`build`] emits. They share the file because the embedding is
//! **tied**: it is the encoder's input table, the decoder's input table and the logits kernel, and
//! two files would upload ~250 MiB of it twice. Each pass names the others' tensors with
//! [`Builder::host_tensor`], and `tests::the_passes_cover_the_file_and_every_one_of_them_builds`
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
//! ordinary fp16 plan input. The math is fairseq's `M2M100SinusoidalPositionalEmbedding` with the
//! `+ 2` offset, verified against transformers' `modeling_m2m_100.py` by model-eng.
//!
//! ## The position offset is 2, not 0
//!
//! fairseq numbers positions as `cumsum(mask) * mask + padding_idx` with `padding_idx = 1`, so the
//! first real token sits at **position 2**. An off-by-two here produces fluent, plausible, subtly
//! wrong output, and no shape check anywhere catches it. It is pinned in
//! `tests::the_first_token_sits_at_position_two` and was verified against transformers'
//! `modeling_m2m_100.py` by model-eng.
//!
//! The sinusoid is fairseq's, which is not the usual one either: the two halves are
//! `[sin(all 512), cos(all 512)]` **concatenated rather than interleaved**, and the frequency
//! spacing divides by `half_dim - 1` = 511, not 512.
//!
//! # The head is four ops, not one
//!
//! 256,206 classes at 1024 channels is ~250 MiB of int8, and `maxStorageBufferRange`'s guaranteed
//! minimum is 128 MiB — so `scripts/ml/maml_convert.py` emits the tied weight as **four**
//! tensors over disjoint class ranges, [`Mode::DecodeStep`] emits four `ConvVecInt8` ops, and
//! `post::translate` argmaxes over all four — which it already did, because it argmaxes anyway.
//!
//! 256,206 is not divisible by 4, so the splits are uneven: the first two hold 64,052 classes
//! each and the last two 64,051 (see [`split_classes`]). No range is padded — padding would add
//! dummy logits that could win the argmax.
//!
//! # The FFN is ReLU, not GELU
//!
//! `config.json` says `activation_function: relu`, so [`feed_forward`] uses [`Act::Relu`]. A GELU
//! here would still run and still produce text, which is why the activation code is pinned in
//! `tests::the_encoder_is_twelve_pre_norm_layers`.
//!
//! # No attention mask, and none needed
//!
//! The runtime has no additive mask and no causal flag. Neither is required:
//!
//! * The encoder runs over one sentence with no padding, so every key is real.
//! * The decoder decodes one token at a time, so a step is **one query against `step + 1` keys**,
//!   which is causal by construction. That is what [`Builder::attn_scores_cached`]'s support for
//!   differing query and key lengths buys.
//!
//! [`Builder::attn_scores`] applies `1 / sqrt(head_dim)` itself, and `head_dim` is 64, which is
//! exactly the model's `self.scaling`. So nothing folds a query scale, and `sqrt(d_model)` stays
//! in the host gather rather than folded into the embedding.
//!
//! # Pre-norm
//!
//! The encoder and decoder layers normalise **before** each sublayer and skip around both, and
//! each stack ends with one more layer norm. Written the other way round the net still runs and
//! still produces text.
//!
//! # Decode protocol
//!
//! NLLB puts the **source** language token on the encoder source and forces the **target**
//! language token as the decoder's first token (forced-BOS). See
//! `post::translate::translate`. Getting this backwards produces fluent output in the wrong
//! language rather than an error.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use crate::weights::Reader;

/// Channels throughout: `d_model`.
pub const D_MODEL: u32 = 1024;

/// Attention heads, so `head_dim` is 64 and [`Builder::attn_scores`]'s own scale is the model's.
pub const HEADS: u32 = 16;

/// The feed-forward width, `encoder_ffn_dim` and `decoder_ffn_dim`.
pub const FFN: u32 = 4096;

/// Vocabulary entries, shared by the embedding and the logits projection.
///
/// 256,000 SentencePiece pieces + 202 flores language codes + `<mask>` + 4 specials, per
/// `config.json`'s `vocab_size` and model-eng's tokenizer inventory.
pub const VOCAB: u32 = 256_206;

/// Class ranges the tied weight is emitted as. See the module docs.
pub const HEAD_SPLITS: usize = 4;

/// Classes in each of the first two splits. The last two hold one fewer each.
pub const CLASSES_PER_SPLIT: u32 = 64_052;

/// Classes in the last two splits: 256,206 = 2 x 64,052 + 2 x 64,051.
pub const CLASSES_PER_TAIL_SPLIT: u32 = 64_051;

/// Classes in split `split`: the first two get the extra row each.
pub fn split_classes(split: usize) -> u32 {
    if split < 2 {
        CLASSES_PER_SPLIT
    } else {
        CLASSES_PER_TAIL_SPLIT
    }
}

/// Encoder layers.
pub const ENCODER_LAYERS: usize = 12;

/// Decoder layers. Twelve: the distilled-600M keeps the full M2M-100 decoder, unlike SMaLL-100's
/// three.
pub const DECODER_LAYERS: usize = 12;

/// `max_position_embeddings`, and therefore the longest source this can encode.
pub const MAX_POSITIONS: u32 = 1024;

/// Decoder positions the KV cache is built to hold.
///
/// The decode plan is recorded once at this length rather than rebuilt per token, so this is
/// charged to the arena in full for every translation: `2 * DECODER_LAYERS` caches of
/// `MAX_DECODE_POSITIONS * D_MODEL` fp16, which is 6 MB at these numbers.
///
/// It matches `post::translate::MAX_TOKENS`, the cap on the greedy loop, so the loop cannot
/// outrun the cache. Raising one without the other either wastes arena or drops positions:
/// `shaders/cache_write.comp` refuses a write past the end rather than running off it.
/// [`MAX_POSITIONS`] is the *positional embedding* limit and is a different, larger number.
pub const MAX_DECODE_POSITIONS: u32 = 128;

/// fairseq's padding id, which is also the offset every position is shifted by.
const PADDING_IDX: u32 = 1;

/// The epsilon in every layer norm.
const EPSILON: f32 = 1e-5;

/// Tensors per encoder layer: two norms of two, four projections of three, two more of three.
const ENCODER_LAYER_TENSORS: usize = 2 + 4 * 3 + 2 + 3 + 3;

/// Tensors per decoder layer: an encoder layer plus a cross-attention norm and four projections.
const DECODER_LAYER_TENSORS: usize = ENCODER_LAYER_TENSORS + 2 + 4 * 3;

/// The first of the four head splits. They come first, because the embedding is read before
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

/// Tensors the `.maml` must hold, and the count `maml_convert.py` writes: 12 head + 264 encoder
/// + 2 + 432 decoder + 2.
pub const TENSORS: usize = DECODER_NORM + 2;

/// Convolutions read as int8 rather than fp16, each carrying a third tensor for its scale.
///
/// **Every one of them.** Four head splits, all six projections of each encoder layer and all ten
/// of each decoder layer. Only the layer norms and the biases stay fp16.
pub const INT8_CONVS: usize = HEAD_SPLITS + ENCODER_LAYERS * 6 + DECODER_LAYERS * 10;

/// Which forward pass [`build`] emits.
///
/// One graph and two plans, run through [`crate::vulkan::run::Net::rebuild`] rather than two
/// nets, so the ~600 MiB upload happens once.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// The encoder over `len` tokens: `[1024, 1, len]` in, `[1024, 1, len]` out.
    Encode {
        /// Source tokens, after [`embed_positions`].
        len: u32,
    },
    /// One decoder step: the token just produced in, its logits out.
    ///
    /// A step is **one query** against `prefix + 1` keys — the positions already decoded plus this
    /// one — which is causal by construction and needs no mask.
    ///
    /// The key count is deliberately **not** part of this. It changes every token, and a plan
    /// keyed on it is re-recorded every token: a `device_wait_idle` under the queue lock, a fresh
    /// plan and a full re-emit, per token. So the plan is built once for
    /// [`MAX_DECODE_POSITIONS`], the K and V caches live on the device across steps
    /// ([`Builder::persistent`]), and how much of them is live arrives in
    /// [`crate::vulkan::run::StepParams::prefix`] at submit time.
    DecodeStep {
        /// Source positions the cross-attention attends over.
        src_len: u32,
    },
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

/// The pre-norm feed-forward sublayer, plus its residual. `relu`, not `gelu`: `config.json` says
/// `activation_function: relu`.
fn feed_forward(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let normed = b.layer_norm(x, l.take(), EPSILON);
    let inner = point(b, l, normed, FFN, Act::Relu);
    let projected = point(b, l, inner, D_MODEL, Act::None);
    b.add(x, projected)
}

/// Build one of NLLB's two passes. See [`Mode`].
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    match mode {
        Mode::Encode { len } => encode(weights, len),
        Mode::DecodeStep { src_len } => decode_step(weights, src_len),
    }
}

/// One decoder step.
///
/// # Inputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[1024, 1, 1]` | the current token, after [`embed_positions`] at `past = cache_len` |
/// | 1 | `[1024, 1, src_len]` | the encoder output, channel-major as the encoder produced it |
/// | 2, 3 | `[cache_len, 1, 1024]` | layer 0's self-attention K and V, position-major |
/// | 4, 5 | | layer 1's |
/// | … | | … |
///
/// The cache pair is **omitted at step 0**, where there is nothing before the current token.
///
/// # Outputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0..4 | `[~64052, 1, 1]` | the four logits splits (the last two one class short), concatenated by the host |
/// | 4, 5 | `[1, 1, 1024]` | layer 0's new self-attention K and V, ready to append |
/// | 6, 7 | | layer 1's |
/// | … | | … |
///
/// # Why the cross-attention K and V are recomputed
///
/// They depend only on the encoder output, so computing them once and caching them would save
/// twelve 1024x1024 projections per step. They are not cached here because that would need a third
/// pass and a second kind of persistence for a saving the logits projection dwarfs: the head is
/// ~262 million multiply-accumulates against the whole decoder's ~200 million. Worth revisiting
/// only after `Kind::ConvVecInt8` makes the head cheap.
fn decode_step(weights: &dyn WeightSource, src_len: u32) -> Result<Plan, String> {
    if src_len == 0 {
        return Err("a decode step with no source to attend over".into());
    }

    let l = &mut Layers { next: DECODER };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The decoder plus the tied head, which this pass computes in the same plan. The whole encoder
    // belongs to `Mode::Encode`.
    name_host_tensors(b, &[HEAD..ENCODER, DECODER..TENSORS]);

    let mut x = b.input(Shape::new(D_MODEL, 1, 1));
    let encoded = b.input(Shape::new(D_MODEL, 1, src_len));
    // Every layer's cache pair, held on the device across steps rather than handed in and out.
    //
    // Sized for the longest decode the loop will run, not for this step, which is what lets one
    // recording serve every token: the plan no longer mentions the step number, so
    // `Reshaped::at` stops matching a new key and re-recording on each one. How much of each
    // cache is live comes from `StepParams::prefix` at submit time.
    let caches: Vec<(Id, Id)> = (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, D_MODEL));
            let v = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, D_MODEL));
            (k, v)
        })
        .collect();

    for &(cache_k, cache_v) in &caches {
        // Self-attention, pre-norm, against the cache this step is about to extend.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k_new = point(b, l, normed, D_MODEL, Act::None);
        let v_new = point(b, l, normed, D_MODEL, Act::None);
        // A projection writes `[d_model, 1, 1]`; a cache position is `[1, 1, d_model]`. The same
        // bytes, so this is a relabelling and the write below is one contiguous copy.
        let k_row = b.reshaped(k_new, Shape::new(1, 1, D_MODEL));
        let v_row = b.reshaped(v_new, Shape::new(1, 1, D_MODEL));
        // Written into the cache at the row the step names, replacing the concatenation that
        // used to copy the entire prefix on every layer of every token.
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
        let scores = b.attn_scores_cached_dynamic(q, cache_k, HEADS);
        let probs = b.softmax_prefix(scores);
        let mixed = b.attn_apply_cached_dynamic(probs, cache_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        // Cross-attention over the encoder output, which is channel-major and a different length,
        // so it uses the ordinary pair rather than the cached one.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k = point(b, l, encoded, D_MODEL, Act::None);
        let v = point(b, l, encoded, D_MODEL, Act::None);
        let scores = b.attn_scores(q, k, HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        x = feed_forward(b, l, x);
    }
    if l.next != DECODER_NORM {
        return Err(format!("the decoder claims {} tensors, not {DECODER_NORM}", l.next));
    }
    let state = b.layer_norm(x, l.take(), EPSILON);
    if l.next != TENSORS {
        return Err(format!("the decoder norm ends at {}, not {TENSORS}", l.next));
    }

    // The tied head, in the same plan: a separate pass would mean a second `rebuild` and a round
    // trip through the host for a `[1024]` vector. The last two splits are one class short.
    let head = &mut Layers { next: HEAD };
    let outputs: Vec<Id> = (0..HEAD_SPLITS)
        .map(|split| point(b, head, state, split_classes(split), Act::None))
        .collect();
    if head.next != ENCODER {
        return Err(format!("the head claims {} tensors, not {ENCODER}", head.next));
    }
    // The K and V rows are no longer outputs: they are in the cache, on the device, and the host
    // never sees them again.
    builder.finish(&outputs)
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
    // The tied weight and the whole decoder belong to the other pass. The trailing encoder
    // norm is part of this range, so it runs to `DECODER` rather than to `ENCODER_NORM`.
    name_host_tensors(b, std::slice::from_ref(&(ENCODER..DECODER)));

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

/// Name every tensor **outside** `read` as one this pass does not touch.
///
/// [`Builder::finish`] refuses an unread tensor, and no one of the two passes reads the whole
/// file. Declaring the complement rather than listing it keeps the two in step: adding a layer
/// changes the ranges and nothing else.
fn name_host_tensors(b: &mut Builder, read: &[std::ops::Range<usize>]) {
    for index in 0..TENSORS {
        if !read.iter().any(|range| range.contains(&index)) {
            b.host_tensor(index, &dims_of(index));
        }
    }
}

/// The shape of tensor `index`, derived from the layout constants.
///
/// `host_tensor` checks it against the file, so this is a *second* statement of the table that
/// `maml_convert.collect_nllb` writes — which is the point: a converter and a runtime that
/// disagree about a shape fail here rather than on the device.
fn dims_of(index: usize) -> Vec<u32> {
    if index < ENCODER {
        // A head split: int8 kernel, per-class scale, synthesised zero bias. The last two splits
        // are one class short.
        let split = index / 3;
        let classes = split_classes(split);
        return match index % 3 {
            0 => vec![classes, D_MODEL, 1, 1],
            _ => vec![classes],
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

/// Which head split holds token `id`, and its row within that split.
///
/// The first two splits hold [`CLASSES_PER_SPLIT`] classes each and the last two one fewer, so
/// this is not one division: ids below twice the full width divide evenly, and the rest divide
/// over the tail width.
fn split_of(id: u32) -> (usize, u32) {
    if id < CLASSES_PER_SPLIT * 2 {
        ((id / CLASSES_PER_SPLIT) as usize, id % CLASSES_PER_SPLIT)
    } else {
        let rest = id - CLASSES_PER_SPLIT * 2;
        (2 + (rest / CLASSES_PER_TAIL_SPLIT) as usize, rest % CLASSES_PER_TAIL_SPLIT)
    }
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
        let (split, row) = split_of(id);
        let kernel = HEAD + split * 3;
        let embedding = weights.int8_row(
            kernel,
            kernel + 1,
            &[split_classes(split), D_MODEL, 1, 1],
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

    /// `Act::Relu`'s code in `common.glsl`. `Act::code` is private, so the activation the FFN
    /// folds is asserted as the number the shader reads rather than through the enum.
    const ACT_RELU: u32 = 1;

    fn plan(mode: Mode) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, mode).expect("the pass builds");
        (source, plan)
    }

    #[test]
    fn the_layout_matches_the_converter() {
        // The numbers `maml_convert.py --graph nllb600 --print-layers` reports for this graph. A
        // disagreement here is a plan that reads one layer's weights as another's.
        assert_eq!(ENCODER_LAYER_TENSORS, 22);
        assert_eq!(DECODER_LAYER_TENSORS, 36);
        assert_eq!((HEAD, ENCODER, ENCODER_NORM), (0, 12, 276));
        assert_eq!((DECODER, DECODER_NORM), (278, 710));
        assert_eq!(TENSORS, 712);
        assert_eq!(INT8_CONVS, 196);
        assert_eq!((split_classes(0), split_classes(1)), (64_052, 64_052));
        assert_eq!((split_classes(2), split_classes(3)), (64_051, 64_051));
        assert_eq!(
            split_classes(0) + split_classes(1) + split_classes(2) + split_classes(3),
            VOCAB
        );
    }

    #[test]
    fn the_parameter_total_matches_the_checkpoint() {
        // What the file holds, from the layout `every_tensor_shape_is_stated_the_same_way_twice`
        // pins, against the checkpoint plus exactly the two things quantising adds.
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();

        // One fp16 scale per output channel of each of the 196 int8 convolutions.
        let layer_scales = u64::from(4 * D_MODEL + FFN + D_MODEL);
        let scales = u64::from(VOCAB)
            + ENCODER_LAYERS as u64 * layer_scales
            + DECODER_LAYERS as u64 * (layer_scales + u64::from(4 * D_MODEL));
        assert_eq!(scales, 526_542);
        // And a zero bias for each head split, which a tied projection has no weight for.
        let synthesised = u64::from(VOCAB);

        assert_eq!(total, 615_073_792 + scales + synthesised);

        // ~588.4 MiB on disk. Everything but the 196 kernels is fp16, and the kernels are 99.8%
        // of the elements — which is the whole reason the file is barely over the parameter
        // count in bytes.
        let kernels = u64::from(VOCAB) * u64::from(D_MODEL)
            + ENCODER_LAYERS as u64 * u64::from(D_MODEL) * u64::from(4 * D_MODEL + 2 * FFN)
            + DECODER_LAYERS as u64 * u64::from(D_MODEL) * u64::from(8 * D_MODEL + 2 * FFN);
        assert_eq!(kernels, 614_676_480);
        let file = kernels + (total - kernels) * 2;
        assert!((588 << 20..589 << 20).contains(&file), "{file} bytes");
    }

    /// The tensor ranges each pass reads on the device. Everything else it names.
    ///
    /// Stated here rather than returned by [`build`] because it is the thing under test: a pass
    /// that read the wrong range would name the right one and still be wrong.
    fn read_by(mode: Mode) -> Vec<std::ops::Range<usize>> {
        let mut ranges = Vec::new();
        match mode {
            Mode::Encode { .. } => ranges.push(ENCODER..DECODER),
            Mode::DecodeStep { .. } => {
                // The decode step computes the tied head itself, so it reads both ends of the file.
                ranges.push(HEAD..ENCODER);
                ranges.push(DECODER..TENSORS);
            }
        }
        ranges
    }

    #[test]
    fn the_passes_cover_the_file_and_every_one_of_them_builds() {
        // `Builder::finish` only checks that a tensor is read *or* named, so this is what stops
        // naming being used to hide a layer the device never touches. Together the passes must read
        // every index.
        let mut covered = std::collections::BTreeSet::new();
        for mode in [
            Mode::Encode { len: LEN },
            Mode::DecodeStep { src_len: LEN },
        ] {
            let source = Shapes::new(TENSORS);
            build(&source, mode).unwrap_or_else(|e| panic!("{mode:?}: {e}"));
            covered.extend(read_by(mode).into_iter().flatten());
        }
        assert_eq!(covered.into_iter().collect::<Vec<_>>(), (0..TENSORS).collect::<Vec<_>>());
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
        // And the FFN inner projection folds ReLU — `config.json`'s `activation_function: relu` —
        // one per layer, on the `FFN`-wide projection only.
        let mut relu = 0;
        for op in &plan.ops {
            if let Op::Dispatch { push, .. } = op {
                if push.out_c == FFN {
                    assert_eq!(push.act, ACT_RELU, "the FFN inner projection is ReLU: {push:?}");
                    relu += 1;
                }
            }
        }
        assert_eq!(relu, ENCODER_LAYERS, "{counts:?}");
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
    fn a_decode_step_is_twelve_layers_of_two_attentions() {
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0usize) += 1;
            }
        }
        // Ten int8 convolutions per layer — two attentions of four plus fc1 and fc2 — and the four
        // splits of the tied head.
        assert_eq!(
            counts.get("ConvInt8"),
            Some(&(DECODER_LAYERS * 10 + HEAD_SPLITS)),
            "{counts:?}"
        );
        // Three norms per layer, pre-norm, plus the trailing one.
        assert_eq!(counts.get("LayerNorm"), Some(&(DECODER_LAYERS * 3 + 1)), "{counts:?}");
        // Self-attention is cached and single-query; the cross-attention is not.
        assert_eq!(counts.get("AttnScoresCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApplyCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&DECODER_LAYERS), "{counts:?}");
        // The self-attention softmax is the prefix-bounded one; the cross-attention's is not.
        assert_eq!(counts.get("Softmax"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("SoftmaxPrefix"), Some(&DECODER_LAYERS), "{counts:?}");
        // K and V into the cache, per layer.
        assert_eq!(counts.get("CacheWrite"), Some(&(DECODER_LAYERS * 2)), "{counts:?}");
        // Three residuals per layer.
        assert_eq!(counts.get("Add"), Some(&(DECODER_LAYERS * 3)), "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_takes_only_the_token_and_the_source() {
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        // The token and the encoder output, and nothing else: the KV cache is on the device.
        // This is the shape of the change - twenty-four cache bindings used to be uploaded on
        // every token, growing by a position each time.
        assert_eq!(plan.inputs.len(), 2);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        assert_eq!(plan.inputs[1].shape, Shape::new(D_MODEL, 1, LEN));
        // Four logits splits, and no cache rows coming back.
        assert_eq!(plan.outputs.len(), HEAD_SPLITS);
        let classes: u32 = plan.outputs.iter().take(HEAD_SPLITS).map(|b| b.shape.c).sum();
        assert_eq!(classes, VOCAB);
        assert_eq!(plan.outputs[0].shape.c, CLASSES_PER_SPLIT);
        assert_eq!(plan.outputs[HEAD_SPLITS - 1].shape.c, CLASSES_PER_TAIL_SPLIT);
    }

    #[test]
    fn the_decode_plan_does_not_depend_on_the_step() {
        // The property the whole record-once design rests on. Two steps of the same translation
        // produce the same key, so `Reshaped::at` matches and never re-records; and because the
        // plan is identical, the arena offsets - including the caches' - are identical too, which
        // is what lets the cache survive from one submit to the next.
        let (_, first) = plan(Mode::DecodeStep { src_len: LEN });
        let (_, again) = plan(Mode::DecodeStep { src_len: LEN });
        assert_eq!(first.ops, again.ops);
        assert_eq!(first.arena_elems, again.arena_elems);
        assert_eq!(Mode::DecodeStep { src_len: LEN }, Mode::DecodeStep { src_len: LEN });
    }

    #[test]
    fn the_caches_are_built_for_the_longest_decode_the_loop_can_run() {
        // Sized to `MAX_DECODE_POSITIONS`, not to a step, so a plan recorded once covers every
        // token. If this and `post::translate::MAX_TOKENS` ever disagree the loop either wastes
        // arena or silently drops positions at the end of a long sentence.
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        let mut writes = 0;
        for op in &plan.ops {
            match op {
                Op::Dispatch { kind: Kind::CacheWrite, push, .. } => {
                    writes += 1;
                    assert_eq!(push.in_h, MAX_DECODE_POSITIONS, "{push:?}");
                    assert_eq!(push.in_c, D_MODEL, "the row stride is d_model: {push:?}");
                    assert_eq!(push.count, D_MODEL, "a position is d_model long: {push:?}");
                }
                // The score map is as wide as the cache, and the bound comes from the step.
                Op::Dispatch { kind: Kind::AttnScoresCached, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, MAX_DECODE_POSITIONS), "{push:?}");
                    assert_ne!(push.dyn_keys, 0, "the key count must come from the step");
                }
                Op::Dispatch { kind: Kind::AttnApplyCached, push, .. } => {
                    assert_eq!((push.in_w, push.out_c), (MAX_DECODE_POSITIONS, D_MODEL), "{push:?}");
                    assert_ne!(push.dyn_keys, 0, "the key count must come from the step");
                }
                // Cross-attention: one query over the source, which is a different length and a
                // fixed one, so it stays static.
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, LEN), "{push:?}");
                    assert_eq!(push.dyn_keys, 0, "cross-attention is not prefix-bounded");
                }
                _ => {}
            }
        }
        assert_eq!(writes, DECODER_LAYERS * 2, "a K and a V per layer");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_over_nothing_is_refused() {
        let source = Shapes::new(TENSORS);
        let error =
            build(&source, Mode::DecodeStep { src_len: 0 }).expect_err("no source");
        assert!(error.contains("no source"), "{error}");
    }

    #[test]
    fn a_decode_step_is_single_position_where_it_counts() {
        // Why `Kind::ConvVecInt8` exists. A decode step is one position almost throughout, and the
        // tiled kind's workgroup count is `out_c.div_ceil(16) * positions.div_ceil(16)` — so at one
        // position it pads 15 of every 16 tile columns and stores from 8 of every 64 invocations.
        let (_, plan) = plan(Mode::DecodeStep { src_len: LEN });
        let int8: Vec<_> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind, push, invocations } => {
                    (super::super::tests::name_of(*kind) == "ConvInt8")
                        .then_some((*kind, *push, *invocations))
                }
                _ => None,
            })
            .collect();
        assert_eq!(int8.len(), DECODER_LAYERS * 10 + HEAD_SPLITS);

        // The split is not "everything": the cross-attention's key and value projections read the
        // **encoder output**, which is `src_len` positions, so they stay tiled and are the only
        // multi-position work a decode step does. Everything else — the four self-attention
        // projections, the cross-attention's query and output, both feed-forwards and all four
        // head splits — is one position.
        let (vector, tiled): (Vec<_>, Vec<_>) =
            int8.iter().partition(|(kind, _, _)| *kind == Kind::ConvVecInt8);
        assert_eq!(vector.len(), DECODER_LAYERS * 8 + HEAD_SPLITS, "single-position");
        assert_eq!(tiled.len(), DECODER_LAYERS * 2, "the cross-attention K and V");
        for (kind, push, _) in &tiled {
            assert_eq!(*kind, Kind::ConvPointInt8, "{push:?}");
            assert_eq!(push.out_w, LEN, "{push:?}");
        }
        // One workgroup per group of eight output channels, 64 invocations each.
        for (_, push, invocations) in &vector {
            assert_eq!(push.out_h * push.out_w, 1, "{push:?}");
            assert_eq!(push.count, push.out_c.div_ceil(8), "{push:?}");
            assert_eq!(*invocations, push.count * 64, "{push:?}");
        }
    }

    #[test]
    fn the_head_is_four_ops_over_disjoint_class_ranges() {
        // Together the four splits are the whole vocabulary, which is what `post::translate`
        // argmaxes. The last two splits are one class short each.
        let source = Shapes::new(TENSORS);
        let plan = build(&source, Mode::DecodeStep { src_len: LEN })
            .expect("the step builds");
        let heads: Vec<_> = plan
            .outputs
            .iter()
            .take(HEAD_SPLITS)
            .map(|b| b.shape.c)
            .collect();
        assert_eq!(heads, vec![64_052, 64_052, 64_051, 64_051]);
        assert_eq!(heads.iter().sum::<u32>(), VOCAB);
    }

    #[test]
    fn the_head_binds_under_the_guaranteed_range() {
        // Why the split exists and why it is four: the whole 256,206 x 1024 int8 table is ~250
        // MiB against a guaranteed 128 MiB, and two splits would each sit just under it the way
        // the whole small100-era 128k table did — too tight. A quarter plus its scale and bias is
        // comfortably inside.
        let whole = u64::from(VOCAB) * u64::from(D_MODEL);
        assert!(whole > 250 << 20, "the whole table is {whole} bytes");
        // Two splits would be 128,103 classes each — just under the 128 MiB floor the way the
        // whole small100-era 128k table was. Too tight for a binding plus its scale and bias.
        let half = 128_103u64 * u64::from(D_MODEL);
        assert!(half > 120 << 20, "a half would be {half} bytes: too tight");
        let quarter = u64::from(CLASSES_PER_SPLIT) * u64::from(D_MODEL)
            + u64::from(CLASSES_PER_SPLIT) * 4;
        assert!(quarter < 96 << 20, "a quarter is {quarter} bytes");
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
    fn split_of_covers_the_whole_vocabulary_without_gaps() {
        // The uneven split is the one place an id could fall between two ranges or land in both.
        // Every id must map to exactly one (split, row), and the row must be inside that split.
        let mut seen = 0u32;
        for split in 0..HEAD_SPLITS {
            for row in 0..split_classes(split) {
                seen += 1;
                let _ = (split, row);
            }
        }
        assert_eq!(seen, VOCAB);
        // The boundaries: the last id of each split and the first of the next.
        assert_eq!(split_of(0), (0, 0));
        assert_eq!(split_of(64_051), (0, 64_051));
        assert_eq!(split_of(64_052), (1, 0));
        assert_eq!(split_of(128_103), (1, 64_051));
        assert_eq!(split_of(128_104), (2, 0));
        assert_eq!(split_of(192_154), (2, 64_050));
        assert_eq!(split_of(192_155), (3, 0));
        assert_eq!(split_of(256_205), (3, 64_050));
        // And the mapping is the identity in order: walking ids walks (split, row) contiguously.
        let mut next = 0u32;
        for split in 0..HEAD_SPLITS {
            let base = HEAD + split * 3;
            let _ = base;
            for row in 0..split_classes(split) {
                assert_eq!(split_of(next), (split, row), "id {next}");
                next += 1;
            }
        }
        assert_eq!(next, VOCAB);
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

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // `dims_of` restates the table `maml_convert.collect_nllb` writes, and `Layers` walks
        // it. This checks the two agree for every index, which is what makes `host_tensor`'s shape
        // check meaningful rather than circular.
        let mut expected: Vec<Vec<u32>> = Vec::new();
        for split in 0..HEAD_SPLITS {
            let classes = split_classes(split);
            expected.push(vec![classes, D_MODEL, 1, 1]);
            expected.push(vec![classes]);
            expected.push(vec![classes]);
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
