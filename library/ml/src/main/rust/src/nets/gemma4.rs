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
pub const MAX_CONTEXT: u32 = 2048;

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

/// Unquantised tensors a cache-owning layer has: five `d_model` norms, `q_norm`, `k_norm`, and
/// the scalar.
const OWNING_PLAIN: usize = 8;

/// The same without `k_norm`.
const SHARED_PLAIN: usize = OWNING_PLAIN - 1;

/// Tensors a cache-owning layer contributes, in file order.
const OWNING_LAYER_TENSORS: usize = OWNING_PLAIN + OWNING_PROJECTIONS * PROJECTION_TENSORS;

/// Tensors a shared-cache layer contributes.
const SHARED_LAYER_TENSORS: usize = SHARED_PLAIN + SHARED_PROJECTIONS * PROJECTION_TENSORS;

/// Where the layers start. The shared head and tables come first.
const LAYER0: usize = SHARED_TENSORS;

/// Tensors before any layer.
///
/// The four splits of the logits head, each a projection triple; the per-layer projection and its
/// norm; and the trailing norm. The rotary tables are **not** here: see [`Mode`].
const SHARED_TENSORS: usize =
    HEAD_SPLITS * PROJECTION_TENSORS + PROJECTION_TENSORS + 1 + 1;

/// The first split of the logits head.
pub const HEAD: usize = 0;

/// The projection producing every layer's per-layer input, `[8960, 1536]` in the export.
pub const PER_LAYER_PROJECTION: usize = HEAD + HEAD_SPLITS * PROJECTION_TENSORS;

/// The norm over one layer's slice of that projection.
pub const PER_LAYER_NORM: usize = PER_LAYER_PROJECTION + PROJECTION_TENSORS;

/// The trailing norm, before the logits head.
pub const FINAL_NORM: usize = PER_LAYER_NORM + 1;

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
    let mut plain = |dims: &[u32], next: &mut usize| -> Result<(), String> {
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
    }
    projection(weights, &mut next, D_MODEL, HEADS * dim)?; // attn.o_proj
    plain(&[D_MODEL], &mut next)?; // post_attention_layernorm
    plain(&[D_MODEL], &mut next)?; // pre_feedforward_layernorm
    projection(weights, &mut next, inner * 2, D_MODEL)?; // mlp.gate_up_proj, fused
    projection(weights, &mut next, D_MODEL, inner)?; // mlp.down_proj
    plain(&[D_MODEL], &mut next)?; // post_feedforward_layernorm
    projection(weights, &mut next, PER_LAYER, D_MODEL)?; // per_layer.per_layer_input_gate
    projection(weights, &mut next, D_MODEL, PER_LAYER)?; // per_layer.per_layer_projection
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

/// One quantised `1x1` projection: kernel, per-output-channel scale, bias.
fn projection(
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

/// Which pass [`build`] emits.
///
/// One so far. A prefill over many positions at once would be a second, and is what the
/// `[C, 1, T]` shapes throughout leave room for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// One decoder step: one token in, its logits out.
    ///
    /// Carries no step number, so a whole generation is one recording. The live cache length and
    /// each layer's window arrive in [`crate::vulkan::run::StepParams`] at submit time - see
    /// [`crate::nets::Builder::persistent`] and the note on windows below.
    DecodeStep,
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
/// The rotary angles are one row each of `cos_cache_local` / `cos_cache_global` and their sine
/// counterparts, interleaved by the converter into the cosine-then-sine layout
/// [`crate::nets::Builder::rotary`] expects. Shipped rather than recomputed because the two layer
/// types use different thetas *and* the full layers use a `proportional` rope variant whose
/// formula is not worth re-deriving when the table is right there.
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
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    let Mode::DecodeStep = mode;
    let mut builder = Builder::new(weights);
    let b = &mut builder;

    let mut x = b.input(Shape::new(D_MODEL, 1, 1));
    let per_layer_inputs = b.input(Shape::new(PER_LAYER * LAYERS as u32, 1, 1));
    let angles_local = b.input(Shape::new(HEAD_DIM, 1, 1));
    let angles_global = b.input(Shape::new(GLOBAL_HEAD_DIM, 1, 1));
    // The shared `[1536, 8960]` projection and its norm produce a per-layer block from the hidden
    // state - `MatMul -> Scale -> Reshape` in the graph - which is a *second* source of per-layer
    // inputs alongside the one `embed_tokens.onnx` emits. How the two combine is not something
    // this module has established, so rather than guess at an add or a replace, both are resolved
    // on the host and arrive as `per_layer_inputs` above.
    //
    // That is a real gap, not a design: it is the one part of the forward pass with no reference
    // run behind it. Naming the tensors here keeps `finish`'s "nothing is unread" invariant
    // meaningful for the other 995.
    b.host_tensor(PER_LAYER_PROJECTION, &[PER_LAYER * LAYERS as u32, D_MODEL, 1, 1]);
    b.host_tensor(PER_LAYER_PROJECTION + 1, &[PER_LAYER * LAYERS as u32]);
    b.host_tensor(PER_LAYER_PROJECTION + 2, &[PER_LAYER * LAYERS as u32]);
    b.host_tensor(PER_LAYER_NORM, &[PER_LAYER]);

    // One cache pair per owning layer, sized for the whole context and held on the device.
    let caches: Vec<(Id, Id)> = (0..OWNS_CACHE_LAYERS)
        .map(|index| {
            let width = KV_HEADS * head_dim(index);
            let k = b.persistent(Shape::new(MAX_CONTEXT, 1, width));
            let v = b.persistent(Shape::new(MAX_CONTEXT, 1, width));
            (k, v)
        })
        .collect();

    for index in 0..LAYERS {
        let angles = if is_full_attention(index) { angles_global } else { angles_local };
        let (cache_k, cache_v) = *caches
            .get(cache_source(index))
            .ok_or_else(|| format!("layer {index} reads cache {}", cache_source(index)))?;
        x = layer(b, index, x, per_layer_inputs, angles, cache_k, cache_v)?;
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

/// A `1 x 1` int8 convolution, which every projection in this net is.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
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

    // Self-attention, pre-norm.
    let input_norm = plain(&mut next);
    let q_norm_at = plain(&mut next);
    let q_proj = proj(&mut next);
    let (k_norm_at, k_proj, v_proj) = if owns_cache(index) {
        (Some(plain(&mut next)), Some(proj(&mut next)), Some(proj(&mut next)))
    } else {
        (None, None, None)
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

    if let (Some(k_norm_at), Some(k_proj), Some(v_proj)) = (k_norm_at, k_proj, v_proj) {
        let k = point(b, k_proj, normed, KV_HEADS * dim);
        let k = b.rms_norm_grouped(k, k_norm_at, EPSILON, KV_HEADS);
        let k = b.rotary(k, angles, KV_HEADS);
        let v = point(b, v_proj, normed, KV_HEADS * dim);
        // `v_norm` in the export has an all-ones gamma, which is not a no-op: RMS normalisation
        // still divides by the RMS. The converter writes that gamma out like any other.
        let k_row = b.reshaped(k, Shape::new(1, 1, KV_HEADS * dim));
        let v_row = b.reshaped(v, Shape::new(1, 1, KV_HEADS * dim));
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
    }

    let scores = b.attn_scores_cached_prescaled(q, cache_k, HEADS, KV_HEADS);
    let probs = b.softmax_prefix(scores);
    let mixed = b.attn_apply_cached_grouped(probs, cache_v, HEADS, KV_HEADS);
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
    let gate_out = point(b, gate_at, x, PER_LAYER);
    let gated_in = b.activate(gate_out, Act::Gelu);
    let combined = b.mul(gated_in, mine);
    let projected = point(b, projection_at, combined, D_MODEL);
    // `layer_scalar` is **not** applied here. It is a single trained number multiplying this
    // branch, and the converter folds it into `per_layer_projection`'s weights - exact, free, and
    // one op fewer per layer than a broadcast multiply would be. It is still declared, so the
    // tensor table matches the export; see `declare_layer`.
    // `layer_scalar` is **not** applied here. It is a single trained number multiplying this
    // branch, and the converter folds it into `per_layer_projection`'s weights - exact, free, and
    // one op fewer per layer than a broadcast multiply would be. It stays in the file so the
    // tensor table matches the export one for one, and is named so that `finish`'s "nothing is
    // unread" invariant still means something for every tensor that *should* be read.
    b.host_tensor(scalar_at, &[1]);
    let branch = b_rms(b, projected, post_per_layer);
    Ok(b.add(x, branch))
}

/// `rms_norm` with this module's epsilon, which every norm here uses.
fn b_rms(b: &mut Builder, x: Id, at: usize) -> Id {
    b.rms_norm(x, at, EPSILON)
}

#[cfg(test)]
mod tests {
    use super::*;
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
        // Two fewer projections and one fewer norm: seven tensors, not three, because a
        // quantised projection is a triple in the file.
        assert_eq!(OWNING_LAYER_TENSORS - SHARED_LAYER_TENSORS, 7);
        assert_eq!(OWNING_LAYER_TENSORS, 8 + 8 * 3);
        assert_eq!(SHARED_LAYER_TENSORS, 7 + 6 * 3);
    }

    #[test]
    fn the_decode_pass_builds_and_reads_every_tensor() {
        // The whole 35-layer forward pass against the stub source, so `Builder::finish`'s own
        // invariant does the work: it refuses a plan that leaves a tensor unread, which is what
        // catches a layer walking its span at the wrong stride.
        let source = Shapes::new(TENSORS);
        let plan = build(&source, Mode::DecodeStep).expect("the decode pass builds");
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
        let first = build(&Shapes::new(TENSORS), Mode::DecodeStep).expect("builds");
        let again = build(&Shapes::new(TENSORS), Mode::DecodeStep).expect("builds");
        assert_eq!(first.ops, again.ops);
        assert_eq!(first.arena_elems, again.arena_elems);
    }

    #[test]
    fn the_attention_is_multi_query_and_never_double_scales() {
        // Two things that are invisible in the shapes. Every cached score map must take its key
        // count from the step and read one key/value head per eight query heads; and its scale
        // must be exactly one, because the export folded `1/sqrt(head_dim)` into `q_norm` and
        // deriving it again here would halve every logit.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep).expect("builds");
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
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep).expect("builds");
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
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep).expect("builds");
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
        // Gemma 4 has no biases, but this runtime's convolutions do and an int8 kernel needs a
        // scale, so the converter writes a triple. This is what pins the count the converter must
        // emit - a file with one tensor per projection would parse and then read the next
        // layer's weights as this one's bias.
        let source = Shapes::new(TENSORS);
        declare_layer(&source, 0).expect("layer 0");
        let asked = source.asked.borrow();
        let kernels = asked.iter().filter(|(_, d)| d.len() == 4).count();
        assert_eq!(kernels, OWNING_PROJECTIONS, "one kernel per projection");
        // Each kernel is followed by two vectors of its output width.
        for (offset, (_, dims)) in asked.iter().enumerate() {
            if dims.len() != 4 {
                continue;
            }
            let out = dims[0];
            let scale = asked.get(offset + 1).map(|(_, d)| d.clone());
            let bias = asked.get(offset + 2).map(|(_, d)| d.clone());
            assert_eq!(scale, Some(vec![out]), "the scale after a {dims:?} kernel");
            assert_eq!(bias, Some(vec![out]), "the bias after a {dims:?} kernel");
        }
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
            if dims.len() == 4 {
                assert_eq!(dims[2..], [1, 1], "tensor {index} is a 1x1 kernel: {dims:?}");
                assert_ne!(dims[0], 0);
            } else {
                assert_eq!(dims.len(), 1, "tensor {index} is a vector or a kernel: {dims:?}");
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
