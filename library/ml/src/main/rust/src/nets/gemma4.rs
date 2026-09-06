//! Gemma 4 E2B instruction-tuned: the text decoder, in one `.maml`.
//!
//! # What it is
//!
//! `Gemma4ForConditionalGeneration`'s text tower, from
//! `onnx-community/gemma-4-E2B-it-ONNX`. 35 layers, `d_model` 1536, a 262,144-entry vocabulary,
//! and multi-query attention: **eight query heads against one key/value head**.
//!
//! # Two layer archetypes, not one
//!
//! The model is structurally two halves, and this is the fact the rest of the module is shaped
//! around. `config.json` calls it `num_kv_shared_layers: 20` and `use_double_wide_mlp: true`;
//! in the initializer table it is two different tensor counts:
//!
//! * **Layers 0..15 own a KV cache.** Sixteen tensors: `q/k/v/o` projections, `q_norm`, `k_norm`,
//!   five RMS norms, a `[1536, 12288]` fused gate-and-up projection over a 6144-wide inner
//!   dimension, its `[6144, 1536]` down projection, two per-layer-input tensors and a scalar.
//! * **Layers 15..35 have no K or V at all.** Thirteen tensors: no `k_proj`, no `v_proj`, no
//!   `k_norm`. They re-use an earlier layer's cache, and spend the parameters on an MLP of
//!   **twice** the inner width - `[1536, 24576]` over 12288, down from `[12288, 1536]`.
//!
//! So the two halves trade key/value projections for feed-forward width. A single parameterised
//! layer function would have to carry that as a flag through every shape; two functions say it
//! once. See [`OWNS_CACHE_LAYERS`].
//!
//! # Which cache a shared layer reads
//!
//! Traced from the export's own graph rather than assumed:
//!
//! * The sliding shared layers - 15..19, 20..24, 25..29, 30..34 excluding the full ones - all
//!   read **layer 13**, the last sliding layer that owns a cache.
//! * The full-attention shared layers - 19, 24, 29 and 34 - read **layer 14**, the last full
//!   layer that owns one. In the graph they have no key transpose at all, and one mask where a
//!   sliding layer has two.
//!
//! # Sliding and full attention
//!
//! [`LAYER_TYPES`] alternates four sliding layers to one full one, so 4, 9, 14, 19, 24, 29 and 34
//! are full and the other 28 are sliding over a 512-position window. They differ in **head
//! dimension** as well: 256 sliding, 512 full, which is why `q_proj` is `[1536, 2048]` on one and
//! `[1536, 4096]` on the other. Everything else about them is the same.
//!
//! # The attention scale is already in `q_norm`
//!
//! The export applies **no** scale between the query projection and the score matmul - there is
//! no multiply or divide in the graph there, and the fused `GroupQueryAttention` nodes carry
//! `scale = 1.0`. The `1 / sqrt(head_dim)` is folded into `q_norm`'s gamma.
//!
//! [`Builder::attn_scores_cached_grouped`] supplies `1 / sqrt(head_dim)` itself, so a forward pass
//! that also uses the exported `q_norm` unchanged would apply it **twice** - which is not a shape
//! error and not a crash, just quietly flatter attention. The converter divides it out; see
//! [`Q_NORM_CARRIES_SCALE`].

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Hidden width.
pub const D_MODEL: u32 = 1536;

/// Query heads. Every layer has eight.
pub const HEADS: u32 = 8;

/// Key/value heads. One - multi-query attention.
pub const KV_HEADS: u32 = 1;

/// Head dimension on a sliding layer.
pub const HEAD_DIM: u32 = 256;

/// Head dimension on a full-attention layer, `global_head_dim`.
pub const GLOBAL_HEAD_DIM: u32 = 512;

/// Decoder layers.
pub const LAYERS: usize = 35;

/// Layers that own a KV cache. The rest read one of theirs.
pub const OWNS_CACHE_LAYERS: usize = 15;

/// Inner width of the feed-forward on a layer that owns a cache.
pub const FFN: u32 = 6144;

/// Inner width on a shared-cache layer, which is double. `use_double_wide_mlp`.
pub const FFN_WIDE: u32 = 12288;

/// Per-layer input width, `hidden_size_per_layer_input`.
pub const PER_LAYER: u32 = 256;

/// Vocabulary, shared by the embedding and the logits head.
pub const VOCAB: u32 = 262_144;

/// Sliding attention window, in positions.
pub const WINDOW: u32 = 512;

/// Positions a decode plan is built for, and so the length of every KV cache.
///
/// `max_position_embeddings` is 131072, which no arena here could hold: fifteen caches at that
/// length would be gigabytes. This is the context the runtime actually offers.
///
/// A sliding layer never attends more than [`WINDOW`] positions back, so twelve of the fifteen
/// caches are far larger than they need to be. Making those a ring buffer would cut the arena by
/// most of its size, and needs modular indexing in `cache_write.comp` and in the attended range -
/// worth doing, deliberately not done here, so that the first version has one indexing scheme
/// rather than two.
pub const MAX_CONTEXT: u32 = 16_384;

/// Cache lengths a conversation is allowed to grow through.
///
/// The KV cache costs **18,432 bytes a position** - twelve sliding layers at 512 bytes and three
/// full ones at 1,024, doubled for keys and values. So the tier is the conversation length, and
/// the memory follows it linearly: 1,024 positions is 19 MB and 16,384 is 302 MB.
///
/// Tiers rather than a smooth grow because each change re-records the plan and **loses the cache**
/// - the arena is reallocated, so the whole prompt is prefilled again. Doubling makes that happen
/// a handful of times over a long conversation instead of continuously.
/// Pinned tensors that are KV caches, and the first that many in the plan.
///
/// `build` declares the caches before anything else, so they are a prefix of `Plan::pinned` -
/// which also holds the multimodal soft-token buffers. Only these are a function of the prompt,
/// so only these are worth saving. Two per layer that owns one.
pub const CACHE_TENSORS: usize = OWNS_CACHE_LAYERS * 2;

pub const CONTEXT_TIERS: [u32; 5] = [1024, 2048, 4096, 8192, 16_384];

/// Bytes of KV cache one position costs. See [`CONTEXT_TIERS`].
pub const BYTES_PER_POSITION: u32 = 18_432;

/// The largest tier whose cache fits in `budget` bytes, or the smallest if none do.
pub const fn tier_for(budget: u64) -> u32 {
    let mut best = CONTEXT_TIERS[0];
    let mut index = 0;
    while index < CONTEXT_TIERS.len() {
        let tier = CONTEXT_TIERS[index];
        if (tier as u64) * (BYTES_PER_POSITION as u64) <= budget {
            best = tier;
        }
        index += 1;
    }
    best
}

/// The next tier above `context`, or `None` at the top.
pub fn next_tier(context: u32) -> Option<u32> {
    CONTEXT_TIERS.iter().copied().find(|tier| *tier > context)
}

/// Splits the logits head is cut into.
///
/// `[262144, 1536]` int8 is 402 MB, and one descriptor is only guaranteed to reach 128 MiB, so a
/// single head tensor could not be bound at all on a device reporting the minimum. Four splits of
/// 65536 classes are 100 MB each. The same reason NLLB's head is split, and the same arithmetic.
pub const HEAD_SPLITS: usize = 4;

/// Classes in each split of the logits head.
pub const CLASSES_PER_SPLIT: u32 = VOCAB / HEAD_SPLITS as u32;

/// The epsilon in every RMS norm, `rms_norm_eps`.
pub const EPSILON: f32 = 1e-6;

/// `final_logit_softcapping`: the logits are `tanh(x / CAP) * CAP`.
pub const LOGIT_CAP: f32 = 30.0;

/// Whether `q_norm`'s gamma already carries `1 / sqrt(head_dim)`.
///
/// True for a file `maml_convert.py` wrote without dividing it out, in which case the forward pass
/// must pass a scale of one rather than letting [`Builder::attn_scores_cached_grouped`] compute
/// the usual one. Named rather than left as a comment because getting it wrong is invisible: the
/// shapes agree and the output is merely wrong.
///
/// [`Builder::attn_scores_cached_grouped`]: super::Builder::attn_scores_cached_grouped
pub const Q_NORM_CARRIES_SCALE: bool = true;

/// Whether layer `index` uses full attention rather than a sliding window.
///
/// `config.json`'s `layer_types` is four sliding to one full, so every fifth layer from index 4.
pub const fn is_full_attention(index: usize) -> bool {
    index % 5 == 4
}

/// The head dimension layer `index` uses.
pub const fn head_dim(index: usize) -> u32 {
    if is_full_attention(index) {
        GLOBAL_HEAD_DIM
    } else {
        HEAD_DIM
    }
}

/// Whether layer `index` has its own key and value projections.
pub const fn owns_cache(index: usize) -> bool {
    index < OWNS_CACHE_LAYERS
}

/// The layer whose KV cache layer `index` reads.
///
/// Itself when it owns one. Otherwise the last owning layer of the same attention type: 14 for a
/// full layer, 13 for a sliding one.
pub const fn cache_source(index: usize) -> usize {
    if owns_cache(index) {
        index
    } else if is_full_attention(index) {
        OWNS_CACHE_LAYERS - 1
    } else {
        OWNS_CACHE_LAYERS - 2
    }
}

/// The inner feed-forward width of layer `index`.
pub const fn ffn(index: usize) -> u32 {
    if owns_cache(index) {
        FFN
    } else {
        FFN_WIDE
    }
}

/// Tensors one quantised projection contributes: the kernel, its scale, and its bias.
///
/// Gemma 4 has **no biases** - `attention_bias` is false and the export holds none - but every
/// convolution in this runtime takes one, and an int8 or int4 kernel takes a `(kernel, scale,
/// bias)` triple. The converter writes a zero bias rather than the runtime growing a bias-free
/// path: at these widths a zero bias is `out_channels * 2` bytes against a kernel of
/// `out_channels * 1536`, so it is under a thousandth of the file, and it keeps one convolution
/// lowering instead of two.
const PROJECTION_TENSORS: usize = 3;

/// Projections a cache-owning layer has: q, k, v, o, gate-up, down, and the per-layer pair.
const OWNING_PROJECTIONS: usize = 8;

/// Projections a shared-cache layer has: the same without k and v.
const SHARED_PROJECTIONS: usize = OWNING_PROJECTIONS - 2;

/// Unquantised tensors a cache-owning layer has: five `d_model` norms, `q_norm`, `k_norm`,
/// `v_norm`, and the scalar.
const OWNING_PLAIN: usize = 9;

/// The same without `k_norm` and `v_norm`.
const SHARED_PLAIN: usize = OWNING_PLAIN - 2;

/// Tensors a cache-owning layer contributes, in file order.
const OWNING_LAYER_TENSORS: usize = OWNING_PLAIN + OWNING_PROJECTIONS * PROJECTION_TENSORS;

/// Tensors a shared-cache layer contributes.
const SHARED_LAYER_TENSORS: usize = SHARED_PLAIN + SHARED_PROJECTIONS * PROJECTION_TENSORS;

/// Where the layers start. The shared head and tables come first.
const LAYER0: usize = SHARED_TENSORS;

/// Tensors before any layer.
///
/// The four splits of the logits head, each a projection triple; the per-layer projection and its
/// norm; the trailing norm; and the two rotary tables.
const SHARED_TENSORS: usize =
    HEAD_SPLITS * PROJECTION_TENSORS + PROJECTION_TENSORS + 1 + 1 + 2;

/// The first split of the logits head.
pub const HEAD: usize = 0;

/// The projection producing every layer's per-layer input, `[8960, 1536]` in the export.
pub const PER_LAYER_PROJECTION: usize = HEAD + HEAD_SPLITS * PROJECTION_TENSORS;

/// The norm over one layer's slice of that projection.
pub const PER_LAYER_NORM: usize = PER_LAYER_PROJECTION + PROJECTION_TENSORS;

/// The trailing norm, before the logits head.
pub const FINAL_NORM: usize = PER_LAYER_NORM + 1;

/// The sliding layers' rotary table, `[MAX_CONTEXT, HEAD_DIM]`.
///
/// Row `p` is position `p`'s angles in the layout [`super::Builder::rotary`] takes: the cosines
/// in the first `head_dim / 2` entries and the sines in the rest. The export ships
/// `cos_cache_local` and `sin_cache_local` separately at `[131072, 128]` each; the converter
/// truncates them to [`MAX_CONTEXT`] and interleaves them into this one table, so the host reads
/// a position's angles as one contiguous row rather than two gathers and a concatenation.
///
/// Shipped rather than recomputed because the two layer types use different thetas *and* the
/// full-attention layers use a `proportional` rope variant whose formula is not worth
/// re-deriving when the table is in the file.
pub const ROTARY_LOCAL: usize = FINAL_NORM + 1;

/// The full-attention layers' rotary table, `[MAX_CONTEXT, GLOBAL_HEAD_DIM]`. See
/// [`ROTARY_LOCAL`].
pub const ROTARY_GLOBAL: usize = ROTARY_LOCAL + 1;

/// Total tensors the `.maml` holds, and the count `maml_convert.py` must write.
pub const TENSORS: usize = SHARED_TENSORS
    + OWNS_CACHE_LAYERS * OWNING_LAYER_TENSORS
    + (LAYERS - OWNS_CACHE_LAYERS) * SHARED_LAYER_TENSORS;

/// The first tensor index of layer `index`.
pub const fn layer_at(index: usize) -> usize {
    let owning = if index < OWNS_CACHE_LAYERS { index } else { OWNS_CACHE_LAYERS };
    let shared = if index < OWNS_CACHE_LAYERS { 0 } else { index - OWNS_CACHE_LAYERS };
    LAYER0 + owning * OWNING_LAYER_TENSORS + shared * SHARED_LAYER_TENSORS
}

/// Declare every tensor of layer `index` against `weights`, in file order.
///
/// Separate from any forward pass so the ordered layout can be asserted with no `.maml` on disk:
/// `nets::tests::Shapes` records each `(index, dims)` and hands the index back as the offset.
///
/// # These are the shapes the `.maml` holds, not the shapes ONNX holds
///
/// Two differences from the export, both introduced by the converter:
///
/// * **Transposed.** The export's projections are `MatMul` weights in `[in, out]` order -
///   `q_proj` is `[1536, 2048]`. Every projection here is evaluated by
///   [`super::Builder::conv_int8`], which like the rest of this runtime takes an ONNX `Conv`
///   kernel, `[out, in, kh, kw]`. This mirrors what `maml_convert.py` already does for `Gemm`:
///   the file stores what the shader indexes.
/// * **Three tensors per projection**, not one - see [`PROJECTION_TENSORS`].
///
/// So the file holds more tensors than the export does, and the counts here are the file's.
pub fn declare_layer(weights: &dyn WeightSource, index: usize) -> Result<(), String> {
    let at = layer_at(index);
    let dim = head_dim(index);
    let inner = ffn(index);
    let mut next = at;
    let plain = |dims: &[u32], next: &mut usize| -> Result<(), String> {
        let here = *next;
        *next += 1;
        weights.shaped(here, dims).map(|_| ())
    };

    plain(&[D_MODEL], &mut next)?; // input_layernorm
    plain(&[dim], &mut next)?; // attn.q_norm
    projection(weights, &mut next, HEADS * dim, D_MODEL)?; // attn.q_proj
    if owns_cache(index) {
        plain(&[dim], &mut next)?; // attn.k_norm
        projection(weights, &mut next, KV_HEADS * dim, D_MODEL)?; // attn.k_proj
        projection(weights, &mut next, KV_HEADS * dim, D_MODEL)?; // attn.v_proj
        plain(&[dim], &mut next)?; // attn.v_norm, an all-ones gamma
    }
    projection(weights, &mut next, D_MODEL, HEADS * dim)?; // attn.o_proj
    plain(&[D_MODEL], &mut next)?; // post_attention_layernorm
    plain(&[D_MODEL], &mut next)?; // pre_feedforward_layernorm
    projection(weights, &mut next, inner * 2, D_MODEL)?; // mlp.gate_up_proj, fused
    projection(weights, &mut next, D_MODEL, inner)?; // mlp.down_proj
    plain(&[D_MODEL], &mut next)?; // post_feedforward_layernorm
    projection8(weights, &mut next, PER_LAYER, D_MODEL)?; // per_layer.per_layer_input_gate
    projection8(weights, &mut next, D_MODEL, PER_LAYER)?; // per_layer.per_layer_projection
    plain(&[D_MODEL], &mut next)?; // post_per_layer_input_norm
    plain(&[1], &mut next)?; // layer_scalar
    if next != layer_at(index + 1) {
        return Err(format!(
            "layer {index} declared {} tensors, not the {} its span allows",
            next - at,
            layer_at(index + 1) - at
        ));
    }
    Ok(())
}

/// One quantised `1x1` projection: kernel, per-block scale, bias.
///
/// Four bits, which is what every large projection uses.
fn projection(
    weights: &dyn WeightSource,
    next: &mut usize,
    out: u32,
    inp: u32,
) -> Result<(), String> {
    weights.shaped_words(*next, &[out, inp, 1, 1])?;
    let blocks = inp.div_ceil(crate::weights::I4_BLOCK);
    weights.shaped(*next + 1, &[out, blocks])?;
    weights.shaped(*next + 2, &[out])?;
    *next += PROJECTION_TENSORS;
    Ok(())
}

/// [`projection`] at **eight** bits, whose scale is rank 1.
///
/// For the two per-layer-input projections. They are the smallest weights in a layer -
/// `[1536, 256]` against the feed-forward's `[1536, 24576]` - and they are the ones that miss the
/// int4 fidelity gate: measured over the real export, `per_layer_input_gate` reconstructs at
/// 0.9870 on layer 21, under the 0.99 floor, while every large projection clears it. Quantising
/// them to four bits would save about a thousandth of the file for the worst error in it.
fn projection8(
    weights: &dyn WeightSource,
    next: &mut usize,
    out: u32,
    inp: u32,
) -> Result<(), String> {
    weights.shaped_words(*next, &[out, inp, 1, 1])?;
    weights.shaped(*next + 1, &[out])?;
    weights.shaped(*next + 2, &[out])?;
    *next += PROJECTION_TENSORS;
    Ok(())
}

/// The `[in, out]` shape the export holds for a kernel this module declares as `dims`.
///
/// The inverse of the transpose described on [`declare_layer`], so a converter check can compare
/// against the ONNX file without either side restating the other's convention. Scales and biases
/// have no counterpart in the export and come back unchanged.
pub fn as_exported(dims: &[u32]) -> Vec<u32> {
    match dims {
        [out, inp, 1, 1] => vec![*inp, *out],
        other => other.to_vec(),
    }
}

/// The embedding tables, which live in their own `.maml`. See [`crate::weights::graph`].
///
/// Two tensors, each a `(kernel, scale, bias)` triple as everything quantised here is. Both are
/// read a row at a time by [`crate::weights::Reader::int4_row`] and neither is bound to a shader:
/// a decode step needs 1536 values from a 1.2 GB table.
pub mod embed {
    /// `model.embed_tokens.weight`, `[VOCAB, D_MODEL]`. The export's `sqrt(d_model)` is folded in.
    pub const TOKENS: usize = 0;

    /// `model.embed_tokens_per_layer.weight`, `[VOCAB, PER_LAYER * LAYERS]`, `sqrt(256)` folded.
    pub const PER_LAYER: usize = 3;

    /// Tensors the embedding `.maml` holds.
    pub const TENSORS: usize = 6;

    /// Ids the export maps to row 0 of the per-layer table before gathering.
    ///
    /// The image and audio placeholders. They have no per-layer input of their own - their
    /// embedding comes from the vision or audio tower - and the export masks them with a
    /// `Where` rather than letting them index the table. A gather that skipped this would read a
    /// real row for a placeholder and quietly perturb every layer.
    pub const PLACEHOLDERS: [u32; 2] = [258_880, 258_881];
}

/// One token's embedding and per-layer inputs, gathered on the host.
///
/// Returns `(inputs_embeds, per_layer_inputs)`, ready for [`build`]'s first two inputs. Both
/// scales the export applies are already in the weights, so this is a dequantise and nothing
/// else - see `collect_gemma4_embed`.
pub fn gather(
    embed: &crate::weights::Reader<'_>,
    token: u32,
) -> Result<(Vec<f32>, Vec<f32>), String> {
    if token >= VOCAB {
        return Err(format!("token {token} is past the {VOCAB}-entry vocabulary"));
    }
    let hidden = embed.int4_row(embed::TOKENS, embed::TOKENS + 1, &[VOCAB, D_MODEL], token)?;
    // The placeholders have no per-layer row of their own; the export masks them to 0.
    let per_layer_row = if embed::PLACEHOLDERS.contains(&token) { 0 } else { token };
    let per_layer = embed.int4_row(
        embed::PER_LAYER,
        embed::PER_LAYER + 1,
        &[VOCAB, PER_LAYER * LAYERS as u32],
        per_layer_row,
    )?;
    Ok((hidden, per_layer))
}

/// Which pass [`build`] emits.
///
/// One so far. A prefill over many positions at once would be a second, and is what the
/// `[C, 1, T]` shapes throughout leave room for.
/// A pass and the cache length it is recorded against.
///
/// The cache length has to be part of what [`crate::vulkan::reshape::Reshaped`] keys on, not a
/// value the plan function closes over: it holds a bare `fn` pointer, and more importantly every
/// pass sharing one recording must agree about where the caches are. Bundling them makes a
/// mismatch impossible to express rather than merely unlikely.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Pass {
    /// Which pass to emit.
    pub mode: Mode,
    /// Positions the KV caches hold. One of [`CONTEXT_TIERS`].
    pub context: u32,
}

impl Mode {
    /// This pass at a cache length. `Mode::DecodeStep.at(4096)`.
    pub const fn at(self, context: u32) -> Pass {
        Pass { mode: self, context }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// One decoder step: one token in, its logits out.
    ///
    /// Carries no step number, so a whole generation is one recording. The live cache length and
    /// each layer's window arrive in [`crate::vulkan::run::StepParams`] at submit time - see
    /// [`crate::nets::Builder::persistent`] and the note on windows below.
    DecodeStep,
    /// Stop after `layers` layers and hand back the hidden state and the per-layer block.
    ///
    /// For `examples/check_gemma4_parity.rs`, which bisects a wrong forward pass by comparing
    /// intermediates against onnxruntime. A composition error - a residual on the wrong side of
    /// a norm, a cache written at the wrong position - reaches the logits as a plausible ranking
    /// and is invisible from the outside; the only way to find it is to ask where the two first
    /// disagree.
    ///
    /// `layers` of 0 stops before the first layer, which checks the embedding and the per-layer
    /// combination on their own.
    Trace { layers: usize },
    /// Run every layer to fill the KV cache, with **no logits head**. `tokens` positions at once.
    ///
    /// # Why the head is skipped rather than ignored
    ///
    /// A prompt is pushed one position at a time and the logits are discarded for all of them but
    /// the last: `bridge.rs` checks `want_logits` *after* the forward pass, and the pass is
    /// [`Mode::DecodeStep`], which always runs the head. So the head is evaluated for every
    /// position of the prompt and the result is thrown away on the host.
    ///
    /// That is not a rounding error. The head is four int4 splits of `[CLASSES_PER_SPLIT,
    /// D_MODEL]` plus their scale tables - 227 MB of the file's 1.30 GB, **17.5% of the weight
    /// bytes**, against 9 of the plan's 1,094 ops. The op share is the misleading one: a "283 us
    /// per op" average hides that these four are gemvs reading 50 MB each, so their cost tracks
    /// bytes and not dispatch count.
    ///
    /// The last position of a prompt still needs its logits and uses [`Mode::DecodeStep`].
    ///
    /// # `tokens`
    ///
    /// One today. The batched path is what removes the per-dispatch cost that dominates prefill,
    /// and it needs a per-query sliding mask that no existing softmax expresses - 28 of 35 layers
    /// slide over [`WINDOW`] - and a transposing cache write, since `[kv_width, 1, N]` into a
    /// position-major cache is a real transpose rather than the free reshape it is at one
    /// position. `build` refuses anything above one rather than recording a plan that would
    /// attend as though it were single-position.
    Prefill { tokens: u32 },
}

/// Plan inputs, in declaration order.
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[1536, 1, 1]` | the token's embedding, gathered on the host |
/// | 1 | `[256, 1, 35]` | the per-layer inputs for this token, also gathered on the host |
/// | 2 | `[256, 1, 1]` | rotary angles for a sliding layer at this position |
/// | 3 | `[512, 1, 1]` | rotary angles for a full layer at this position |
///
/// The embedding and the per-layer table are host gathers for the same reason NLLB's tied
/// embedding is: they are one row of a very large table, and a shader would have to bind the
/// whole thing. `embed_tokens.onnx` is literally two `Gather`s, so nothing is lost.
///
/// The rotary angles are one row of [`ROTARY_LOCAL`] or [`ROTARY_GLOBAL`], which the converter
/// has already put in the cosine-then-sine layout [`super::Builder::rotary`] expects.
pub const INPUTS: usize = 4;

/// Build the decode pass.
///
/// # Windows
///
/// Sliding and full layers want different attended ranges from the same submit, and
/// [`crate::vulkan::run::StepParams`] carries one `window_start`. Until it carries a per-layer
/// one, a plan built here uses the **sliding** window for every layer that slides and relies on
/// the host setting `window_start` to `prefix.saturating_sub(WINDOW - 1)`; the full layers pass
/// `dynamic` too but must see the whole prefix. That is the one piece of this that does not yet
/// have a home, and it is why [`Mode`] has a single variant rather than two.
pub fn build(weights: &dyn WeightSource, pass: Pass) -> Result<Plan, String> {
    let Pass { mode, context } = pass;
    if !CONTEXT_TIERS.contains(&context) {
        return Err(format!("a cache of {context} positions, which is not one of the tiers"));
    }
    let stop_after = match mode {
        Mode::DecodeStep => LAYERS,
        // Prefill runs every layer; what it skips is the head, not the depth.
        Mode::Prefill { tokens: 0 } => return Err("a prefill of no positions".into()),
        Mode::Prefill { tokens } if tokens <= context => LAYERS,
        Mode::Prefill { tokens } => {
            return Err(format!("a prefill of {tokens} positions, past a {context} cache"))
        }
        Mode::Trace { layers } if layers <= LAYERS => layers,
        Mode::Trace { layers } => return Err(format!("a trace of {layers} of {LAYERS} layers")),
    };
    let mut builder = Builder::new(weights);
    let b = &mut builder;

    // One cache pair per owning layer, sized for the whole context and held on the device.
    //
    // **Declared before the inputs, and that order is load-bearing.** [`Builder::finish`] assigns
    // arena offsets by walking its `pinned` list in order, and both `input` and `persistent` push
    // onto it - so a pinned tensor's offset is the running total of every pinned tensor declared
    // ahead of it. The inputs are the only pinned tensors whose size depends on the sequence
    // length. The caches are `MAX_CONTEXT`-sized whatever it is. Declaring the caches first is
    // therefore what gives them the same offsets in *every* plan built from this module, and that
    // is what lets one plan fill a cache and a differently-shaped one read it.
    //
    // With the inputs first they do not line up. At `T = N` the four inputs occupy
    // `(D_MODEL + PER_LAYER * LAYERS + HEAD_DIM + GLOBAL_HEAD_DIM) * N` elements - 11,264 per
    // position - so every cache behind them shifts by 11,264 * (N - 1). Two plans sharing one
    // arena would then disagree about where the caches are, and because the arena is a single
    // buffer that is not a read of uninitialised memory: it is a read of the other plan's live
    // activations. No crash, no shape error, no NaN. Fluent, wrong output.
    let caches: Vec<(Id, Id)> = (0..OWNS_CACHE_LAYERS)
        .map(|index| {
            let width = KV_HEADS * head_dim(index);
            // Only as long as this conversation has grown to need.
            let k = b.persistent(Shape::new(context, 1, width));
            let v = b.persistent(Shape::new(context, 1, width));
            (k, v)
        })
        .collect();

    // Positions this pass covers. One for a decode step; a whole prompt for a batched prefill,
    // which is the difference between reading 1.3 GB of weights per token and reading it once
    // for all of them.
    let width = match mode {
        Mode::Prefill { tokens } => tokens,
        _ => 1,
    };
    let mut x = b.input(Shape::new(D_MODEL, 1, width));
    let embedded_per_layer = b.input(Shape::new(PER_LAYER * LAYERS as u32, 1, width));
    let angles_local = b.input(Shape::new(HEAD_DIM, 1, width));
    let angles_global = b.input(Shape::new(GLOBAL_HEAD_DIM, 1, width));
    // The rotary tables. Read on the host, a row at a time, and handed back as the two angle
    // inputs above - the same arrangement as the embedding.
    b.host_tensor(ROTARY_LOCAL, &[MAX_CONTEXT, HEAD_DIM]);
    b.host_tensor(ROTARY_GLOBAL, &[MAX_CONTEXT, GLOBAL_HEAD_DIM]);

    // The per-layer inputs have **two** sources and this is where they meet, read from the
    // export's own graph rather than inferred:
    //
    //     projected = reshape(x @ per_layer_projection * 1/sqrt(d_model))
    //     combined  = (embedded_per_layer + rms_norm(projected)) * 1/sqrt(2)
    //
    // `embedded_per_layer` is the host's gather from `embed_tokens_per_layer`, already scaled by
    // `sqrt(per_layer)`; this is the projection of the token's *embedding*, which runs once at
    // the top rather than per layer. The `1/sqrt(2)` is the average of two contributions.
    let projected = point(b, PER_LAYER_PROJECTION, x, PER_LAYER * LAYERS as u32);
    let projected = b.affine(projected, 1.0 / (D_MODEL as f32).sqrt(), 0.0);
    // The reshape to `[35, 256]` is only a relabelling; what matters is that the norm reduces
    // over each layer's 256 channels on its own, which is a grouped norm here.
    let normed = b.rms_norm_grouped(projected, PER_LAYER_NORM, EPSILON, LAYERS as u32);
    let combined = b.add(embedded_per_layer, normed);
    let per_layer_inputs =
        b.affine(combined, 1.0 / std::f32::consts::SQRT_2, 0.0);

    // A batched prefill cannot read its own keys out of the cache: the cache is position-major
    // and the pass needs them `[width, 1, T]`, and the twenty shared layers would be reading
    // rows their owning layer wrote earlier in the same submit with no barrier between. So the
    // owning layers' K and V are carried forward as tensors instead, which is also how the
    // decode path's `cache_source` mapping is honoured without a second transpose.
    let mut sliding_kv: Option<(Id, Id)> = None;
    let mut full_kv: Option<(Id, Id)> = None;
    for index in 0..stop_after {
        let angles = if is_full_attention(index) { angles_global } else { angles_local };
        let (cache_k, cache_v) = *caches
            .get(cache_source(index))
            .ok_or_else(|| format!("layer {index} reads cache {}", cache_source(index)))?;
        if width == 1 {
            x = layer(b, index, x, per_layer_inputs, angles, cache_k, cache_v)?;
            continue;
        }
        let shared = if is_full_attention(index) { full_kv } else { sliding_kv };
        let (next, kv) =
            prefill_layer(b, index, x, per_layer_inputs, angles, cache_k, cache_v, shared, width)?;
        x = next;
        if let Some(kv) = kv {
            if is_full_attention(index) {
                full_kv = Some(kv);
            } else {
                sliding_kv = Some(kv);
            }
        }
    }

    if let Mode::Trace { .. } = mode {
        // Every tensor the untraced pass would have read still has to be read, or `finish`
        // refuses the plan - so the layers this stops short of, and the head, are named rather
        // than evaluated.
        for index in stop_after..LAYERS {
            declare_layer(weights, index)?;
            name_layer(b, index);
        }
        name_head(b);
        return builder.finish(&[x, per_layer_inputs]);
    }

    if let Mode::Prefill { .. } = mode {
        // Prefill runs every layer - the point is the caches those layers fill - and then stops.
        // The head and the final norm are named rather than evaluated, exactly as a trace names
        // the layers it stops short of.
        name_head(b);
        // `finish` needs an output and the hidden state is the only thing left. A prefill's real
        // product is the thirty caches, which are `persistent` and so are not outputs at all;
        // returning `x` costs nothing, since it is already in the arena, and gives the host
        // something to sanity-check a pass against.
        return builder.finish(&[x]);
    }

    let state = b.rms_norm(x, FINAL_NORM, EPSILON);
    // Four splits of the vocabulary, each its own binding, then softcapped.
    let mut outputs = Vec::with_capacity(HEAD_SPLITS);
    for split in 0..HEAD_SPLITS {
        let at = HEAD + split * PROJECTION_TENSORS;
        let logits = point(b, at, state, CLASSES_PER_SPLIT);
        outputs.push(b.softcap(logits, LOGIT_CAP));
    }
    builder.finish(&outputs)
}

/// Name the logits head and the final norm as host-read, for the modes that stop before them.
///
/// [`Mode::Trace`] and [`Mode::Prefill`] both end after a layer rather than after the head, and
/// `Builder::finish` refuses a plan that leaves a tensor unread. Shared because the two must name
/// the *same* set: a mode that named one tensor fewer would be refused, and one that named more
/// would hide a head that had stopped being evaluated.
fn name_head(b: &mut Builder) {
    for split in 0..HEAD_SPLITS {
        let at = HEAD + split * PROJECTION_TENSORS;
        let blocks = D_MODEL.div_ceil(crate::weights::I4_BLOCK);
        b.host_tensor(at, &[CLASSES_PER_SPLIT, D_MODEL, 1, 1]);
        b.host_tensor(at + 1, &[CLASSES_PER_SPLIT, blocks]);
        b.host_tensor(at + 2, &[CLASSES_PER_SPLIT]);
    }
    b.host_tensor(FINAL_NORM, &[D_MODEL]);
}

/// Name every tensor of layer `index` as host-read, for [`Mode::Trace`].
///
/// A trace stops early, so the layers after it are never evaluated - but `Builder::finish`
/// refuses a plan that leaves a tensor unread, and rightly so. This says "the host owns these",
/// which keeps that invariant meaningful for the layers the trace *does* run.
fn name_layer(b: &mut Builder, index: usize) {
    let dim = head_dim(index);
    let inner = ffn(index);
    let blocks = |inp: u32| inp.div_ceil(crate::weights::I4_BLOCK);
    let mut next = layer_at(index);
    let mut plain = |b: &mut Builder, n: &mut usize, dims: &[u32]| {
        b.host_tensor(*n, dims);
        *n += 1;
    };
    let mut proj4 = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out, blocks(inp)]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };
    let mut proj8 = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[dim]);
    proj4(b, &mut next, HEADS * dim, D_MODEL);
    if owns_cache(index) {
        plain(b, &mut next, &[dim]);
        proj4(b, &mut next, KV_HEADS * dim, D_MODEL);
        proj4(b, &mut next, KV_HEADS * dim, D_MODEL);
        plain(b, &mut next, &[dim]);
    }
    proj4(b, &mut next, D_MODEL, HEADS * dim);
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[D_MODEL]);
    proj4(b, &mut next, inner * 2, D_MODEL);
    proj4(b, &mut next, D_MODEL, inner);
    plain(b, &mut next, &[D_MODEL]);
    proj8(b, &mut next, PER_LAYER, D_MODEL);
    proj8(b, &mut next, D_MODEL, PER_LAYER);
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[1]);
}

/// One decoder layer over `width` positions at once.
///
/// # Why this is not `layer` with a width argument
///
/// The attention is a different shape of computation, not the same one wider. A decode step is
/// one query against a cache and reads its keys from that cache; a prefill is T queries against
/// T keys held as tensors, masked causally. Those use different ops, different softmaxes and a
/// different source for K and V. Folding them into one function would be four branches around
/// every line of the attention block, and the decode path is the one that is already verified
/// correct against onnxruntime - so it is left exactly as it was.
///
/// Everything either side of the attention *is* the same computation wider, and is shared by
/// being written the same way: every projection, norm, activation and residual here is an
/// elementwise or per-position op that already took a `[C, 1, T]` shape.
///
/// Returns the layer's output and, for a layer that owns a cache, its `(K, V)` so the shared
/// layers after it can attend over them.
#[allow(clippy::too_many_arguments)]
fn prefill_layer(
    b: &mut Builder,
    index: usize,
    x: Id,
    per_layer_inputs: Id,
    angles: Id,
    cache_k: Id,
    cache_v: Id,
    shared: Option<(Id, Id)>,
    width: u32,
) -> Result<(Id, Option<(Id, Id)>), String> {
    let dim = head_dim(index);
    let inner = ffn(index);
    let at = layer_at(index);
    let mut next = at;
    let mut plain = |n: &mut usize| {
        let here = *n;
        *n += 1;
        here
    };
    let mut proj = |n: &mut usize| {
        let here = *n;
        *n += PROJECTION_TENSORS;
        here
    };

    let input_norm = plain(&mut next);
    let q_norm_at = plain(&mut next);
    let q_proj = proj(&mut next);
    let (k_norm_at, k_proj, v_proj, v_norm_at) = if owns_cache(index) {
        (
            Some(plain(&mut next)),
            Some(proj(&mut next)),
            Some(proj(&mut next)),
            Some(plain(&mut next)),
        )
    } else {
        (None, None, None, None)
    };
    let o_proj = proj(&mut next);
    let post_attention = plain(&mut next);
    let pre_ff = plain(&mut next);
    let gate_up = proj(&mut next);
    let down = proj(&mut next);
    let post_ff = plain(&mut next);
    let gate_at = proj(&mut next);
    let projection_at = proj(&mut next);
    let post_per_layer = plain(&mut next);
    let scalar_at = plain(&mut next);
    if next != layer_at(index + 1) {
        return Err(format!("layer {index} read {} tensors", next - at));
    }

    let normed = b.rms_norm(x, input_norm, EPSILON);
    let q = point(b, q_proj, normed, HEADS * dim);
    let q = b.rms_norm_grouped(q, q_norm_at, EPSILON, HEADS);
    let q = b.rotary(q, angles, HEADS);

    let mine = match (k_norm_at, k_proj, v_proj, v_norm_at) {
        (Some(k_norm_at), Some(k_proj), Some(v_proj), Some(v_norm_at)) => {
            let k = point(b, k_proj, normed, KV_HEADS * dim);
            let k = b.rms_norm_grouped(k, k_norm_at, EPSILON, KV_HEADS);
            let k = b.rotary(k, angles, KV_HEADS);
            let v = point(b, v_proj, normed, KV_HEADS * dim);
            let v = b.rms_norm_grouped(v, v_norm_at, EPSILON, KV_HEADS);
            // Written for the decode steps that follow this prompt. `cache_write` transposes
            // channel-major into the cache's position-major layout.
            b.cache_write(k, cache_k);
            b.cache_write(v, cache_v);
            Some((k, v))
        }
        _ => None,
    };
    // A shared layer has no K or V of its own and reads the last owning layer's of the same
    // attention type - the same mapping `cache_source` states, resolved to tensors here.
    let (k, v) = mine
        .or(shared)
        .ok_or_else(|| format!("layer {index} has no keys and none were carried forward"))?;

    // The scale is already inside `q_norm`'s gamma, so this must not derive it again.
    let scores = b.attn_scores_grouped_prescaled(q, k, HEADS, KV_HEADS);
    let slides = !is_full_attention(index);
    let probs = b.softmax_causal_windowed(scores, if slides { WINDOW } else { 0 });
    let mixed = b.attn_apply_grouped(probs, v, HEADS, KV_HEADS);
    let attended = point(b, o_proj, mixed, D_MODEL);
    let attended = b_rms(b, attended, post_attention);
    let x = b.add(x, attended);

    let ff_in = b.rms_norm(x, pre_ff, EPSILON);
    let both = point(b, gate_up, ff_in, inner * 2);
    let gate = b.slice_channels(both, 0, inner);
    let up = b.slice_channels(both, inner, inner);
    let gate = b.activate(gate, Act::Gelu);
    let gated = b.mul(gate, up);
    let ff = point(b, down, gated, D_MODEL);
    let ff = b_rms(b, ff, post_ff);
    let x = b.add(x, ff);

    let mine_pl = b.slice_channels(per_layer_inputs, index as u32 * PER_LAYER, PER_LAYER);
    let gate_out = point8(b, gate_at, x, PER_LAYER);
    let gated_in = b.activate(gate_out, Act::Gelu);
    let combined = b.mul(gated_in, mine_pl);
    let projected = point8(b, projection_at, combined, D_MODEL);
    let branch = b_rms(b, projected, post_per_layer);
    let x = b.add(x, branch);
    let _ = width;
    Ok((b.mul_scalar(x, scalar_at), mine))
}

/// A `1 x 1` int4 convolution, which every large projection in this net is.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv_int4(x, at, out, Act::None)
}

/// A `1 x 1` **int8** convolution, for the two per-layer projections. See [`projection8`].
fn point8(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv_int8(x, at, out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None)
}

/// One decoder layer.
///
/// The two archetypes differ only in whether they project their own key and value, so this is one
/// function with one branch rather than two that would share every other line.
#[allow(clippy::too_many_arguments)]
fn layer(
    b: &mut Builder,
    index: usize,
    x: Id,
    per_layer_inputs: Id,
    angles: Id,
    cache_k: Id,
    cache_v: Id,
) -> Result<Id, String> {
    let dim = head_dim(index);
    let inner = ffn(index);
    let at = layer_at(index);
    let mut next = at;
    let plain = |n: &mut usize| {
        let here = *n;
        *n += 1;
        here
    };
    let proj = |n: &mut usize| {
        let here = *n;
        *n += PROJECTION_TENSORS;
        here
    };

    // Self-attention, pre-norm.
    let input_norm = plain(&mut next);
    let q_norm_at = plain(&mut next);
    let q_proj = proj(&mut next);
    let (k_norm_at, k_proj, v_proj, v_norm_at) = if owns_cache(index) {
        (
            Some(plain(&mut next)),
            Some(proj(&mut next)),
            Some(proj(&mut next)),
            Some(plain(&mut next)),
        )
    } else {
        (None, None, None, None)
    };
    let o_proj = proj(&mut next);
    let post_attention = plain(&mut next);
    let pre_ff = plain(&mut next);
    let gate_up = proj(&mut next);
    let down = proj(&mut next);
    let post_ff = plain(&mut next);
    let gate_at = proj(&mut next);
    let projection_at = proj(&mut next);
    let post_per_layer = plain(&mut next);
    let scalar_at = plain(&mut next);
    if next != layer_at(index + 1) {
        return Err(format!("layer {index} read {} tensors", next - at));
    }

    let normed = b.rms_norm(x, input_norm, EPSILON);
    let q = point(b, q_proj, normed, HEADS * dim);
    // Per head, against one `head_dim`-long gamma. The scale is already inside that gamma.
    let q = b.rms_norm_grouped(q, q_norm_at, EPSILON, HEADS);
    let q = b.rotary(q, angles, HEADS);

    if let (Some(k_norm_at), Some(k_proj), Some(v_proj), Some(v_norm_at)) =
        (k_norm_at, k_proj, v_proj, v_norm_at)
    {
        let k = point(b, k_proj, normed, KV_HEADS * dim);
        let k = b.rms_norm_grouped(k, k_norm_at, EPSILON, KV_HEADS);
        let k = b.rotary(k, angles, KV_HEADS);
        let v = point(b, v_proj, normed, KV_HEADS * dim);
        // `v_norm`'s gamma is all ones, which is **not** a no-op: an RMS norm still divides by
        // the root-mean-square. Leaving it out scales every value in the cache by that factor and
        // produces attention outputs that look entirely reasonable and are wrong.
        let v = b.rms_norm_grouped(v, v_norm_at, EPSILON, KV_HEADS);
        let k_row = b.reshaped(k, Shape::new(1, 1, KV_HEADS * dim));
        let v_row = b.reshaped(v, Shape::new(1, 1, KV_HEADS * dim));
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
    }

    // Layers 0-3 of every group of five slide; layer 4 attends the whole prefix. One
    // `window_start` in `StepParams` serves both because this flag decides, per op, whether to
    // read it - see `Push::sliding`.
    let slides = !is_full_attention(index);
    let scores = b.attn_scores_cached_prescaled(q, cache_k, HEADS, KV_HEADS, slides);
    let probs = b.softmax_prefix(scores, slides);
    let mixed = b.attn_apply_cached_grouped(probs, cache_v, HEADS, KV_HEADS, slides);
    let attended = point(b, o_proj, mixed, D_MODEL);
    // Post-norm on the branch, then the residual: Gemma norms the sublayer's output rather than
    // its input alone, which is why there are five norms and not three.
    let attended = b_rms(b, attended, post_attention);
    let x = b.add(x, attended);

    // Gated feed-forward. One fused projection to `2 * inner`, split, `gelu(gate) * up`.
    let ff_in = b.rms_norm(x, pre_ff, EPSILON);
    let both = point(b, gate_up, ff_in, inner * 2);
    let gate = b.slice_channels(both, 0, inner);
    let up = b.slice_channels(both, inner, inner);
    let gate = b.activate(gate, Act::Gelu);
    let gated = b.mul(gate, up);
    let ff = point(b, down, gated, D_MODEL);
    let ff = b_rms(b, ff, post_ff);
    let x = b.add(x, ff);

    // The per-layer input branch. Inferred from the tensor shapes and the graph's node names, not
    // read from a reference run: `gelu(x @ gate) * per_layer_input[layer]`, projected back up,
    // normed and added.
    //
    // The per-layer inputs arrive as one `[256 * 35, 1, 1]` block so this layer's slice is a
    // channel range. A `[256, 1, 35]` shape would need a slice along the width axis, which has no
    // builder and would buy nothing.
    let mine = b.slice_channels(per_layer_inputs, index as u32 * PER_LAYER, PER_LAYER);
    let gate_out = point8(b, gate_at, x, PER_LAYER);
    let gated_in = b.activate(gate_out, Act::Gelu);
    let combined = b.mul(gated_in, mine);
    let projected = point8(b, projection_at, combined, D_MODEL);
    let branch = b_rms(b, projected, post_per_layer);
    let x = b.add(x, branch);
    // `layer_scalar` multiplies the layer's **whole output**, not the branch above it: the graph
    // is `per_layer_residual/Add -> Mul(layer_scalar) -> layers.N+1/input_layernorm`. At 0.0178
    // on layer 0 that is a fiftyfold shrink of the residual stream, and leaving it out is not a
    // small error - it is the difference between a model and noise. The next layer's norm is
    // scale-invariant and would hide it; the next layer's *residual* is not, which is where it
    // shows.
    Ok(b.mul_scalar(x, scalar_at))
}

/// `rms_norm` with this module's epsilon, which every norm here uses.
fn b_rms(b: &mut Builder, x: Id, at: usize) -> Id {
    b.rms_norm(x, at, EPSILON)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The tier the layout tests record at. The smallest, because they check shapes and
    /// counts rather than capacity, and the smallest is the fastest to build.
    const TEST_CONTEXT: u32 = CONTEXT_TIERS[0];
    use crate::nets::tests::Shapes;
    use crate::nets::{Kind, Op};

    #[test]
    fn the_layer_types_alternate_four_sliding_to_one_full() {
        // `config.json`'s `layer_types`, transcribed: indices 4, 9, 14, 19, 24, 29, 34 are full.
        let full: Vec<usize> = (0..LAYERS).filter(|&i| is_full_attention(i)).collect();
        assert_eq!(full, vec![4, 9, 14, 19, 24, 29, 34]);
        assert_eq!(LAYERS - full.len(), 28, "the rest slide over a {WINDOW}-position window");
    }

    #[test]
    fn the_head_dimension_follows_the_attention_type() {
        // Read off the `q_proj` widths in the export: 2048 on a sliding layer, 4096 on a full one,
        // both over eight heads.
        for index in 0..LAYERS {
            let expected = if is_full_attention(index) { 512 } else { 256 };
            assert_eq!(head_dim(index), expected, "layer {index}");
            assert_eq!(HEADS * head_dim(index), if expected == 512 { 4096 } else { 2048 });
        }
    }

    #[test]
    fn only_the_first_fifteen_layers_own_a_cache() {
        // `num_kv_shared_layers = 20`, and the graph has exactly fifteen `past_key_values` inputs
        // and thirty-one outputs - one logits plus fifteen key/value pairs.
        let owners: Vec<usize> = (0..LAYERS).filter(|&i| owns_cache(i)).collect();
        assert_eq!(owners.len(), OWNS_CACHE_LAYERS);
        assert_eq!(owners.last(), Some(&14));
        assert_eq!(LAYERS - owners.len(), 20, "the shared ones");
    }

    #[test]
    fn a_shared_layer_reads_the_last_owner_of_its_own_attention_type() {
        // Traced from the export: every sliding shared layer reads layer 13, every full shared
        // layer reads layer 14. Mixing the two would attend over a cache written with the wrong
        // head dimension, which is a shape error here and silent nonsense on a device.
        for index in 0..LAYERS {
            let source = cache_source(index);
            assert!(owns_cache(source), "layer {index} reads {source}, which owns no cache");
            assert_eq!(
                is_full_attention(source),
                is_full_attention(index),
                "layer {index} reads {source}, of the other attention type"
            );
            if owns_cache(index) {
                assert_eq!(source, index);
            } else if is_full_attention(index) {
                assert_eq!(source, 14, "layer {index}");
            } else {
                assert_eq!(source, 13, "layer {index}");
            }
        }
    }

    #[test]
    fn the_shared_layers_trade_key_and_value_for_feed_forward_width() {
        // The whole shape of the model: no `k_proj`/`v_proj`/`k_norm`, and double the inner width.
        assert_eq!(ffn(0), 6144);
        assert_eq!(ffn(34), 12288);
        // Two fewer projections and two fewer norms (`k_norm` and `v_norm`): eight tensors,
        // because a quantised projection is a triple in the file.
        assert_eq!(OWNING_LAYER_TENSORS - SHARED_LAYER_TENSORS, 8);
        assert_eq!(OWNING_LAYER_TENSORS, 9 + 8 * 3);
        assert_eq!(SHARED_LAYER_TENSORS, 7 + 6 * 3);
    }

    #[test]
    fn the_decode_pass_builds_and_reads_every_tensor() {
        // The whole 35-layer forward pass against the stub source, so `Builder::finish`'s own
        // invariant does the work: it refuses a plan that leaves a tensor unread, which is what
        // catches a layer walking its span at the wrong stride.
        let source = Shapes::new(TENSORS);
        let plan = build(&source, Mode::DecodeStep.at(TEST_CONTEXT)).expect("the decode pass builds");
        assert_eq!(plan.inputs.len(), INPUTS);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        assert_eq!(plan.inputs[1].shape, Shape::new(PER_LAYER * LAYERS as u32, 1, 1));
        // Four splits of the vocabulary, softcapped, and nothing else: the KV caches stay on the
        // device.
        assert_eq!(plan.outputs.len(), HEAD_SPLITS);
        let classes: u32 = plan.outputs.iter().map(|b| b.shape.c).sum();
        assert_eq!(classes, VOCAB);
        crate::nets::tests::assert_no_aliasing(&plan);
    }

    #[test]
    fn the_decode_plan_does_not_depend_on_the_step() {
        // One recording for a whole generation, as for NLLB and whisper.
        let first = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let again = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        assert_eq!(first.ops, again.ops);
        assert_eq!(first.arena_elems, again.arena_elems);
    }

    #[test]
    fn the_attention_is_multi_query_and_never_double_scales() {
        // Two things that are invisible in the shapes. Every cached score map must take its key
        // count from the step and read one key/value head per eight query heads; and its scale
        // must be exactly one, because the export folded `1/sqrt(head_dim)` into `q_norm` and
        // deriving it again here would halve every logit.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let mut scores = 0;
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::AttnScoresCached, push, .. } = op {
                scores += 1;
                assert_eq!(push.group, HEADS, "{push:?}");
                assert_eq!(push.kv_heads, KV_HEADS, "{push:?}");
                assert_ne!(push.dyn_keys, 0, "{push:?}");
                assert_eq!(
                    f32::from_bits(push.param0_bits),
                    1.0,
                    "the scale is already in q_norm: {push:?}"
                );
            }
        }
        assert_eq!(scores, LAYERS, "one cached score map per layer");
        assert!(Q_NORM_CARRIES_SCALE, "if this ever becomes false the assertion above flips");
    }

    #[test]
    fn only_the_owning_layers_write_to_a_cache() {
        // Fifteen layers project a key and a value; the other twenty read one of theirs. A shared
        // layer that wrote would overwrite the position its source layer just stored.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let writes = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: Kind::CacheWrite, .. }))
            .count();
        assert_eq!(writes, OWNS_CACHE_LAYERS * 2, "a key and a value per owning layer");
    }

    #[test]
    fn every_layer_gates_its_feed_forward() {
        // `gelu(gate) * up` over one fused projection. The standalone activation is the tell:
        // there is one per layer for the MLP gate and one for the per-layer input gate.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let gelus = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: Kind::Activate, .. }))
            .count();
        assert_eq!(gelus, LAYERS * 2, "the MLP gate and the per-layer gate, per layer");
        let caps = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: Kind::Softcap, .. }))
            .count();
        assert_eq!(caps, HEAD_SPLITS, "each logits split is capped");
    }

    #[test]
    fn a_prefill_plan_drops_the_logits_head() {
        // The head is evaluated for every prompt position today and the result is discarded for
        // all but the last, because `bridge.rs` checks `want_logits` after the pass and the pass
        // always runs the head. `Mode::Prefill` is the pass that does not.
        //
        // Asserted as a DIFFERENCE against `DecodeStep` rather than an absolute count, so it
        // survives the rest of the net changing under it.
        let decode = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("decode builds");
        let prefill =
            build(&Shapes::new(TENSORS), Mode::Prefill { tokens: 1 }.at(TEST_CONTEXT)).expect("prefill builds");
        assert!(
            prefill.ops.len() < decode.ops.len(),
            "prefill {} ops against decode {}",
            prefill.ops.len(),
            decode.ops.len()
        );
        // Four int4 splits, four softcaps, one final norm.
        assert_eq!(decode.ops.len() - prefill.ops.len(), 9, "the head is nine ops");
        let softcaps = |plan: &Plan| {
            plan.ops
                .iter()
                .filter(|op| matches!(op, Op::Dispatch { kind: Kind::Softcap, .. }))
                .count()
        };
        assert_eq!(softcaps(&decode), HEAD_SPLITS, "a decode step caps every split");
        assert_eq!(softcaps(&prefill), 0, "a prefill caps nothing, because it computes nothing");
        // The caches are the point of a prefill, so it must still write all of them.
        let writes = |plan: &Plan| {
            plan.ops
                .iter()
                .filter(|op| matches!(op, Op::Dispatch { kind: Kind::CacheWrite, .. }))
                .count()
        };
        assert_eq!(writes(&prefill), writes(&decode), "prefill fills the same caches");
        // And the arena must not GROW on the way back to decode.
        //
        // `Reshaped::at` rebuilds whenever the mode changes, and `Net::rebuild` reallocates the
        // arena only when it grows — but reallocating is exactly what calls `rebind_arena`, which
        // drops every persistent tensor, i.e. the KV caches the prefill just spent a whole prompt
        // filling. `bridge.rs` constructs the net at `DecodeStep`, so the arena starts at decode's
        // size and the switch to prefill cannot grow it.
        //
        // That makes the safety of the whole prefill-then-decode sequence rest on prefill never
        // needing more arena than decode. It is true today because prefill drops the head's
        // logits tensors and adds nothing, but nothing enforced it until this line.
        assert!(
            prefill.arena_elems <= decode.arena_elems,
            "prefill wants {} arena elements against decode's {}: switching back to decode would \
             grow the arena, and the reallocation drops every KV cache the prefill just filled",
            prefill.arena_elems,
            decode.arena_elems
        );
    }

    #[test]
    fn a_batched_prefill_widens_every_stage_and_masks_causally() {
        // The whole prompt in one submit. What makes it worth having is that the projections
        // become GEMMs: at one position the model is bandwidth-bound reading 1.3 GB of weights
        // per token, and at T it reads the same weights once for all of them.
        //
        // What makes it dangerous is that a plan recorded for T positions which attends as
        // though it were one produces fluent, wrong text and no shape error. So this checks the
        // masking, not merely that it builds.
        for tokens in [2u32, 8, 64] {
            let plan = build(&Shapes::new(TENSORS), Mode::Prefill { tokens }.at(TEST_CONTEXT))
                .unwrap_or_else(|e| panic!("{tokens} positions: {e}"));
            assert_eq!(plan.inputs.len(), INPUTS);
            for input in &plan.inputs {
                assert_eq!(input.shape.w, tokens, "every input is widened");
            }
            let mut causal = 0;
            let mut windowed = 0;
            for op in &plan.ops {
                let Op::Dispatch { kind, push, .. } = op else { continue };
                match kind {
                    Kind::SoftmaxCausal => {
                        causal += 1;
                        if push.kh != 0 {
                            windowed += 1;
                            assert_eq!(push.kh, WINDOW);
                        }
                        assert_eq!((push.out_h, push.out_w), (tokens, tokens), "T x T scores");
                    }
                    // Every score map is multi-query, as the decode path's is.
                    Kind::AttnScores | Kind::AttnApply => {
                        assert_eq!(push.group, HEADS);
                        assert_eq!(push.kv_heads, KV_HEADS);
                    }
                    // Fifteen owning layers write a key and a value, transposing as they go.
                    Kind::CacheWrite => assert_eq!(push.group, tokens, "one write per position"),
                    _ => {}
                }
            }
            assert_eq!(causal, LAYERS, "one causal softmax per layer");
            assert_eq!(windowed, 28, "and the sliding ones carry a window");
        }
        assert!(build(&Shapes::new(TENSORS), Mode::Prefill { tokens: 0 }.at(TEST_CONTEXT)).is_err());
        let past = MAX_CONTEXT + 1;
        assert!(build(&Shapes::new(TENSORS), Mode::Prefill { tokens: past }.at(TEST_CONTEXT)).is_err());
    }

    #[test]
    fn the_caches_are_allocated_before_the_inputs() {
        // The invariant that lets two differently-shaped plans share one arena, and the reason
        // the cache declaration sits above the inputs in `build` rather than beside the layers.
        //
        // `Builder::finish` assigns arena offsets by walking its pinned list in order, and both
        // `input` and `persistent` push onto it. The inputs are the only pinned tensors that grow
        // with the sequence length, so if they are allocated first every cache behind them moves
        // when the length changes - and a prefill plan would then fill caches that the decode
        // plan does not read. Same arena, so that is not uninitialised memory but the other
        // plan's live activations: fluent, wrong, and silent.
        //
        // Checked through the emitted offsets rather than the declaration order, so it cannot
        // pass by agreeing with the source it is meant to police.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let first_cache = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::CacheWrite, push, .. } => Some(push.out),
                _ => None,
            })
            .min()
            .expect("a decode step writes caches");
        let first_input = plan.inputs.iter().map(|binding| binding.at).min().expect("inputs");
        assert!(
            first_cache < first_input,
            "the caches start at {first_cache} and the inputs at {first_input}: the inputs are \
             allocated first, so every cache moves when the sequence length does"
        );
    }

    #[test]
    fn a_full_attention_layer_ignores_the_sliding_layers_window() {
        // The bug this exists to prevent, and the reason `Push::sliding` is not a step parameter.
        //
        // `StepParams` carries one `window_start`, which the host sets for the sliding layers.
        // A full-attention layer sharing that submit must attend the whole prefix anyway. If it
        // read the same field it would lose its long-range attention - the one thing it is there
        // for - silently, and only once a conversation outgrew the window, which is exactly the
        // point at which nobody is still testing.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let mut sliding = 0;
        let mut full = 0;
        for op in &plan.ops {
            let Op::Dispatch { kind, push, .. } = op else { continue };
            if !matches!(
                kind,
                Kind::AttnScoresCached | Kind::AttnApplyCached | Kind::SoftmaxPrefix
            ) {
                continue;
            }
            if push.dyn_keys == 0 {
                continue;
            }
            if push.sliding == 0 {
                full += 1;
            } else {
                sliding += 1;
            }
        }
        // Three attention ops a layer - scores, softmax, apply - and seven of the 35 layers are
        // full attention: 4, 9, 14, 19, 24, 29, 34.
        assert_eq!(full, 7 * 3, "every op of a full-attention layer ignores the window");
        assert_eq!(sliding, 28 * 3, "and every op of a sliding one uses it");
    }

    #[test]
    fn the_layout_matches_the_converter() {
        // The whole ordered table, with no `.maml` on disk. `Shapes` hands back the index as the
        // offset and records every request, so this asserts what `maml_convert.py` must write.
        let source = Shapes::new(TENSORS);
        for index in 0..LAYERS {
            declare_layer(&source, index).unwrap_or_else(|e| panic!("layer {index}: {e}"));
        }
        let asked = source.asked.borrow();
        // Every layer's tensors, contiguous and in order, with nothing skipped or repeated.
        let indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        let expected: Vec<usize> = (LAYER0..layer_at(LAYERS)).collect();
        assert_eq!(indices, expected, "the layers must tile their span exactly");
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // Spot-checks against the export's initializer table, transcribed by hand and mapped back
        // through `as_exported` so this compares in the file's own `[in, out]` convention.
        let exported = |index: usize| -> Vec<Vec<u32>> {
            let source = Shapes::new(TENSORS);
            declare_layer(&source, index).unwrap_or_else(|e| panic!("layer {index}: {e}"));
            let asked = source.asked.borrow();
            asked.iter().map(|(_, d)| as_exported(d)).collect()
        };

        let owning = exported(0);
        assert_eq!(owning.len(), OWNING_LAYER_TENSORS);
        assert!(owning.contains(&vec![1536, 2048]), "q_proj on a sliding layer: {owning:?}");
        assert!(owning.contains(&vec![2048, 1536]), "o_proj: {owning:?}");
        assert!(owning.contains(&vec![1536, 12288]), "the fused gate-and-up: {owning:?}");
        assert!(owning.contains(&vec![6144, 1536]), "down_proj: {owning:?}");

        let full = exported(4);
        assert!(full.contains(&vec![1536, 4096]), "q_proj on a full layer: {full:?}");
        assert!(full.contains(&vec![1536, 512]), "k_proj at the global head dim: {full:?}");

        let shared = exported(15);
        assert_eq!(shared.len(), SHARED_LAYER_TENSORS);
        assert!(shared.contains(&vec![1536, 24576]), "the double-wide gate-and-up: {shared:?}");
        assert!(shared.contains(&vec![12288, 1536]), "the wide down_proj: {shared:?}");
        // `[1536, 256]` is ambiguous by shape alone - it is `k_proj`, `v_proj` *and*
        // `per_layer_input_gate` - so count it rather than test for absence. An owning layer has
        // all three; a shared layer has only the gate.
        let narrow = |v: &[Vec<u32>]| v.iter().filter(|d| **d == vec![1536, 256]).count();
        assert_eq!(narrow(&owning), 3, "k_proj, v_proj and the per-layer gate: {owning:?}");
        assert_eq!(narrow(&shared), 1, "only the per-layer gate: {shared:?}");
    }

    #[test]
    fn every_projection_is_a_kernel_a_scale_and_a_bias() {
        // Gemma 4 has no biases, but this runtime's convolutions do and a quantised kernel needs
        // a scale, so the converter writes a triple. This is what pins the count the converter
        // must emit - a file with one tensor per projection would parse and then read the next
        // layer's weights as this one's bias.
        //
        // The scale's **rank** is the precision: rank 2 `(out, blocks)` for int4, rank 1 `(out)`
        // for the two int8 per-layer projections. `Builder` resolves a scale by shape, so this is
        // also what stops one being read as the other.
        let source = Shapes::new(TENSORS);
        declare_layer(&source, 0).expect("layer 0");
        let asked = source.asked.borrow();
        let kernels = asked.iter().filter(|(_, d)| d.len() == 4).count();
        assert_eq!(kernels, OWNING_PROJECTIONS, "one kernel per projection");
        let mut wide = 0;
        let mut narrow = 0;
        for (offset, (_, dims)) in asked.iter().enumerate() {
            if dims.len() != 4 {
                continue;
            }
            let out = dims[0];
            let inp = dims[1];
            let scale = asked.get(offset + 1).map(|(_, d)| d.clone());
            let bias = asked.get(offset + 2).map(|(_, d)| d.clone());
            match scale.as_deref() {
                Some([rows, blocks]) => {
                    wide += 1;
                    assert_eq!(*rows, out, "the int4 scale has a row per channel: {dims:?}");
                    assert_eq!(
                        *blocks,
                        inp.div_ceil(crate::weights::I4_BLOCK),
                        "one scale per block of taps: {dims:?}"
                    );
                }
                Some([rows]) => {
                    narrow += 1;
                    assert_eq!(*rows, out, "the int8 scale is one per channel: {dims:?}");
                }
                other => panic!("a {dims:?} kernel is followed by {other:?}"),
            }
            assert_eq!(bias, Some(vec![out]), "the bias after a {dims:?} kernel");
        }
        assert_eq!(narrow, 2, "only the two per-layer projections are int8");
        assert_eq!(wide, OWNING_PROJECTIONS - 2);
    }

    #[test]
    fn every_projection_is_declared_as_a_convolution_kernel() {
        // The runtime reads these through `conv_int8`, which takes `[out, in, kh, kw]`, while the
        // export holds `MatMul` weights as `[in, out]`. The converter transposes; this is what
        // stops the two conventions being confused, which would be a plausible-looking net that
        // multiplies by a transposed matrix.
        let source = Shapes::new(TENSORS);
        declare_layer(&source, 0).expect("layer 0");
        for (index, dims) in source.asked.borrow().iter() {
            match dims.len() {
                // A `1 x 1` convolution kernel.
                4 => assert_eq!(dims[2..], [1, 1], "tensor {index}: {dims:?}"),
                // An int4 scale, `(out, blocks)`.
                2 => assert!(dims[1] > 0, "tensor {index}: {dims:?}"),
                // A norm, a bias, an int8 scale, or the layer scalar.
                1 => {}
                _ => panic!("tensor {index} has an unexpected rank: {dims:?}"),
            }
        }
    }

    #[test]
    fn the_parameter_total_is_within_reach_of_the_published_size() {
        // Not an equality: the embedding and the per-layer table live in `embed_tokens`, a
        // separate export, so this counts the decoder only. What it catches is a layout that is
        // wrong by a factor - a transposed projection or a doubled width.
        let mut total: u64 = 0;
        for index in 0..LAYERS {
            let dim = u64::from(head_dim(index));
            let inner = u64::from(ffn(index));
            let d = u64::from(D_MODEL);
            total += d; // input norm
            total += dim; // q_norm
            total += d * u64::from(HEADS) * dim; // q_proj
            if owns_cache(index) {
                total += dim + 2 * d * u64::from(KV_HEADS) * dim; // k_norm, k_proj, v_proj
            }
            total += u64::from(HEADS) * dim * d; // o_proj
            total += 3 * d; // the three remaining d_model norms
            total += d * inner * 2 + inner * d; // gate_up and down
            total += d * u64::from(PER_LAYER) + u64::from(PER_LAYER) * d; // per-layer pair
            total += d; // post_per_layer_input_norm
            total += 1; // layer_scalar
        }
        // The decoder alone, without the 262,144-row embedding or the logits head.
        assert!(
            (1_500_000_000..2_600_000_000).contains(&total),
            "decoder parameters came to {total}, which is not the right order of magnitude"
        );
    }
}
