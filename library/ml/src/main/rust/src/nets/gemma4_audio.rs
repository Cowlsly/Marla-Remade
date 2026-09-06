//! Gemma 4's audio tower: 12 conformer layers over log-mel frames, out as soft tokens.
//!
//! The counterpart of [`super::gemma4_vision`] for sound. It takes the log-mel spectrogram
//! [`crate::logmel`] produces and emits the `[n, 1536]` block that stands in for a clip in the
//! decoder's prompt, between `boa` and `eoa`.
//!
//! # The export is the tower *and* the embedder
//!
//! `get_audio_features` calls `embed_audio(...)` and the exporter traced through it, so the
//! graph tail is `output_proj -> RMSNorm -> embedding_projection` and the result is already in
//! text-embedding space. `output_proj_dims` and `text_config.hidden_size` are both 1536 and are
//! **different things**: `1024 -> 1536` with a bias, then `1536 -> 1536` without one. Conflating
//! them because the numbers match would silently drop a matmul.
//!
//! There is no pooling. `Gemma4VisionPooler` has no audio equivalent, `forward` goes from the
//! last layer straight to `output_proj`, and the graph tail contains no reduction. One soft
//! token per subsampled frame.
//!
//! # A layer is a macaron conformer, not a transformer block
//!
//! `FFN(1/2) -> attention -> conv -> FFN(1/2) -> norm`, nine RMS norms and eighteen calibrated
//! clips deep. Three details are easy to read the other way round and all three are silent:
//!
//! * **[`RESIDUAL_WEIGHT`] applies to the branch, not the residual**, and only inside the two
//!   feed-forwards - after their post-norm, immediately before the add. Not in attention, not in
//!   the conv module.
//! * **The conv module is `conv -> norm -> act`**, and its depthwise convolution is **causal**:
//!   four taps of left pad and none of right. With `attention_context_right = 0` the whole tower
//!   is streaming-shaped.
//! * **The residual adds around attention and the conv module are plain.** Only the two
//!   feed-forwards carry the half.
//!
//! # Attention is a 12-wide sliding window, not a block-diagonal one
//!
//! `ATTEND(q, k) <=> 0 <= q - k <= 11`; see [`attends`]. `attention_context_left` is 13 and
//! every use site is `attention_context_left - 1`, so the attended span is **12**, self plus
//! eleven past. That has been confirmed three independent ways - the reference's
//! `(dist >= 0) & (dist < left_window_size)`, a standalone re-derivation, and executing the
//! extracted mask subgraph under onnxruntime - and it is the single most expensive thing to get
//! wrong here, because reading 13 as the span costs nothing visible.
//!
//! The export tiles this into `[nb, 12, 24]` blocks with a `_rel_shift` skew. That is a
//! computational tiling and changes nothing about which pairs attend: `q = 12` attends
//! `1..=12`, eleven of which are in the previous block. This module reproduces neither the
//! tiling nor a square map.
//!
//! **The score map is banded**: `scores[h][q][j]` with `j = 0..=11` and `k = q - 11 + j`, so it
//! is `[HEADS, T, ATTEND_SPAN]` and every slot is a pair the mask admits. Three things follow,
//! and they are why this was chosen over both alternatives:
//!
//! * **No softmax mode.** A 12-wide row is the softmax domain, so the plain existing
//!   [`super::Builder::softmax`] normalises it untouched. There is no windowed variant.
//! * **No skew.** In banded coordinates the relative offset *is* the column - `o = j + 1`,
//!   independent of `q` - so the pad/reshape/slice reconstruction disappears and the bias fuses
//!   into the scores op. See [`rel_column`].
//! * **No dense mask.** The export materialises a `[1, 1, S, S]` bool mask to gather a
//!   `[nb, 12, 24]` view out of it. Nothing here needs either.
//!
//! A square `[HEADS, T, T]` map was proposed and rejected: at the 750-token cap it is 31x the
//! memory and, decisively, 31x the arithmetic - 27.6 GFLOP against 0.89 - with 1.6% of its slots
//! live. Banded is half the cost of the export's own blocked layout again, because blocked
//! shares one 24-wide context across twelve queries and throws away half of it.
//!
//! **The start edge is the trap.** The first eleven queries have dead low slots - 66 in total,
//! `q = 0` having eleven and `q = 10` one. They are dead *slots* but never a dead *row*:
//! `j = 11` gives `k = q`, which is always admitted and always in range, so `q = 0` yields
//! `[0, ..., 0, 1]`, correct rather than degenerate. Dead slots are filled with [`MASK_FILL`].
//!
//! **Write the guard as `j + q + 1 >= ATTEND_SPAN`, in both the shader and any oracle.** It is
//! purely additive, so there is nothing to wrap in GLSL and nothing to panic in Rust, and it is
//! written against the band rather than the literal 11. [`band_key`] is that form; copy it
//! rather than re-deriving, because the obvious alternatives are all wrong in ways that do not
//! announce themselves:
//!
//! * `j >= 11 - q` **underflows** for every `q > 11`, wrapping to about 4.29e9 so that the guard
//!   rejects the whole band. Correct on the twelve rows `q = 0..=11` and empty on the 738 after
//!   them. It looks like the safe rearrangement and is the worst of them.
//! * `k >= 0` is vacuously true for an unsigned `k` and compilers discard it.
//! * `k < T` with unsigned `k` **is** safe in GLSL, and is the right idiom for the upper bound:
//!   a negative `k` wraps far above any legal index, so one compare catches both ends. But it
//!   relies on wrap being defined, which it is not in Rust - `q - (ATTEND_SPAN - 1)` on a `usize`
//!   panics in debug and wraps in release, so an oracle written that way crashes in debug
//!   exactly where it would be right in release, and every parity run is a debug build.
//!
//! The guard must gate the **read**, not the write. Computing `k`, using it, and masking
//! afterwards has already read out of bounds - live arena data belonging to another tensor,
//! yielding plausible numbers for the first eleven of 750 tokens rather than crashing.
//!
//! # The relative-position term is content-dependent
//!
//! Not an additive bias. The `[8, 128, 13]` table is `relative_k_proj(sinusoid)` folded by the
//! exporter - twelve of them, one per layer, not shared - and it is **dot-producted against the
//! query**, Transformer-XL style, so it cannot be precomputed per `(q, k)`. It is per head and
//! one-sided: thirteen offsets covering `d = 0..=12`, which is odd but is *not* `2w + 1` about
//! zero. See [`rel_column`] for the fencepost.
//!
//! The converter transposes it to `[heads, offsets, head_dim]` so that the dimension the dot
//! product contracts over is the contiguous one. Emitting the export's own axis order would be
//! the right size and the wrong strides, and would read wrong taps without a shape error.
//!
//! # The tensor order is the contract
//!
//! As everywhere in this tree the `.maml` is an ordered table with no names, and
//! `maml_convert.collect_gemma4_audio` writes it in exactly the order [`declare_layer`] reads
//! it. The export's initializers are anonymised (`val_2567`, `permute_9`, `_to_copy_16`), so the
//! converter matches on **node numbering** in topological order and asserts the counts, which
//! makes agreeing on this order more load-bearing here rather than less.
use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Channels through the tower. `audio_config.hidden_size`.
pub const D_MODEL: u32 = 1024;

/// Attention heads. Ordinary multi-head.
pub const HEADS: u32 = 8;

/// Channels per head. `HEADS * HEAD_DIM == D_MODEL`.
pub const HEAD_DIM: u32 = 128;

/// Feed-forward width, four times [`D_MODEL`]. There are **two** of these per layer.
pub const FFN: u32 = 4096;

/// Conformer layers. `audio_config.num_hidden_layers`.
pub const LAYERS: usize = 12;

/// Mel channels per frame, from [`crate::logmel::MELS`].
pub const MELS: u32 = 128;

/// Output channels of the two subsampling convolutions, in order.
pub const SSCP_CHANNELS: [u32; 2] = [128, 32];

/// Side of the subsampling convolutions' square kernel. Hard-coded in the reference class and
/// **not** `conv_kernel_size`, which is the conformer depthwise one - see [`CONV_KERNEL`].
pub const SSCP_KERNEL: u32 = 3;

/// Stride of the subsampling convolutions, on both axes. Two of them, so time reduces 4x.
pub const SSCP_STRIDE: u32 = 2;

/// Taps in the conformer's depthwise convolution. `audio_config.conv_kernel_size`.
pub const CONV_KERNEL: u32 = 5;

/// Left pad the depthwise convolution needs to be causal: `(CONV_KERNEL - 1) * dilation`.
pub const CONV_LEFT_PAD: u32 = CONV_KERNEL - 1;

/// Width the conv module's gate projection produces, split in half by the GLU.
pub const LCONV_GATE: u32 = 2 * D_MODEL;

/// Mel channels surviving the two stride-2 subsamplings, `128 -> 64 -> 32`.
pub const MELS_SUBSAMPLED: u32 = subsample(subsample(MELS));

/// Keys a query attends: itself and [`ATTEND_SPAN`]` - 1` past positions, contiguous.
///
/// `attention_context_left - 1`, and the reason this is a constant with a name rather than a
/// literal is that `attention_context_left` itself is 13 and reading *that* as the span is the
/// trap. See [`attends`].
pub const ATTEND_SPAN: u32 = 12;

/// Columns in a layer's relative-position table. **Not** [`ATTEND_SPAN`].
///
/// Thirteen because the export's `_rel_shift` skew consumes one - `[12, 13]` padded to `[12, 25]`,
/// reshaped to 300, sliced to 288, viewed as `[12, 24]`. The band width and the mask width are
/// different quantities that happen to be adjacent numbers. Column 0 is `d = 12`, which the
/// strict `d < 12` mask never lets through: the table has thirteen columns and twelve of them
/// are live.
pub const REL_OFFSETS: u32 = ATTEND_SPAN + 1;

/// `tanh(x / cap) * cap` on the attention logits. `audio_config.attention_logit_cap`.
///
/// Applied **after** the relative term is added and **before** the mask. That order is
/// load-bearing in both directions:
///
/// * The cap applies to the sum, so the bias cannot be folded into a post-cap addition.
/// * The mask must come after, which is why the reference fills with `-1e9` rather than `-cap`.
///   Capping a masked logit would squash it back up to `-50` and give masked keys real weight.
///
/// So the cap is **fused into the banded scores op** rather than run as a separate
/// [`super::Builder::softcap`] pass. A separate pass over a band already holding [`MASK_FILL`]
/// would map the sentinels to `tanh(-1310.08) * 50 = -50` - a finite weight the softmax would
/// then include. Fusing puts the sentinel in *after* the cap, which is correct by construction
/// rather than by remembering. That ordering error would not raise anything.
pub const LOGIT_CAP: f32 = 50.0;

/// What a dead band slot is filled with before the softmax sees it.
///
/// `-65504` is fp16's most negative finite value, and it is the faithful port rather than a
/// guard against our own representation. The reference uses `-1e9`, which is finite in its fp32
/// score path; this runtime's arena is fp16 (`shaders/common.glsl` declares `float16_t arena[]`),
/// where `-1e9` saturates to `-inf`. Both give `exp(x - peak) -> 0` on a row with any live entry,
/// but on a fully masked row `-inf` gives `peak = -inf` and `exp(NaN) = NaN`, where `-65504`
/// gives a uniform distribution - which is what the reference produces there too.
///
/// [`attends`] guarantees `j = 11` is live in every row, so this tower cannot produce a fully
/// masked row. The constant is still the right one: the guarantee is a property of the banded
/// layout and of passing clips unpadded, and a sentinel should not depend on either holding.
pub const MASK_FILL: f32 = -65504.0;

/// What a feed-forward's branch is scaled by before its residual add. `residual_weight`.
///
/// One shared fp16 scalar in the export feeding twenty-four `Mul`s: two per layer, both inside
/// [`FFN`] blocks, applied to the **branch** after its post-norm. Nowhere else.
pub const RESIDUAL_WEIGHT: f32 = 0.5;

/// The epsilon in every norm, RMS and layer alike. `rms_norm_eps`.
pub const EPSILON: f32 = 1e-6;

/// What the decoder reads, and `output_proj_dims`. Equal to `text_config.hidden_size` by
/// coincidence of configuration, not by construction.
pub const OUT_DIM: u32 = 1536;

/// The query scale the export folds to a constant: `(HEAD_DIM ** -0.5) / ln(2)`.
///
/// Note the `/ ln 2`, which is not a normalisation anyone would guess. Measured in the graph as
/// `val_279 = 0.127517431974411`, matching the formula to eight digits.
///
/// **The converter folds this into the per-layer per-channel query scale**, so the `[1024, 1, 1]`
/// tensor at `layer_at(i) + 26` already carries it and the banded scores op is passed a scale of
/// 1.0. Verified against the emitted file: zero deviation from `q_scale * tile(v, 8)` in all
/// twelve layers. Applying it again in the op would scale every query twice.
pub const Q_SCALE: f32 = 0.127_517_43;

/// The key scale the export folds to a constant: `ln(1 + e) / ln(2)`, i.e. `softplus(1) / ln 2`.
///
/// Measured as `val_280 = 1.8946361541748047`. A scalar, unlike the query's, which is
/// per-channel. It applies to the content term only - the relative term reads the table, which
/// carries no scaling of its own - which is the other reason neither scale can live in the op.
pub const K_SCALE: f32 = 1.894_636_2;

/// Samples the reference truncates a clip to: 30 s at 16 kHz.
///
/// This is what makes the token count bounded, and with it the arena. See [`MAX_TOKENS`].
pub const MAX_SAMPLES: usize = 480_000;

/// Soft tokens [`MAX_SAMPLES`] produces, and so the longest sequence the tower ever sees.
///
/// 2,999 mel frames subsampled twice. `750 * 40 ms = 30 s`, which is where
/// `audio_ms_per_token = 40` comes from: a 10 ms hop through two stride-2 convolutions.
///
/// **Under the banded layout there is no quadratic term anywhere in this tower** - a score map
/// is `[HEADS, T, 12]`, linear in `T`, because the attended span is 12 regardless of length. So
/// unlike the vision tower's `[12, 2304, 2304]` maps this bound is a convenience rather than a
/// load-bearing budget. That property belongs to the layout and not to the cap: a square
/// `[HEADS, T, T]` map would reintroduce the quadratic term by choice, bounded only by this
/// constant. It is the main reason the square map was rejected.
pub const MAX_TOKENS: u32 = 750;

/// Tensors one clip contributes: a `[2]` fp16 pair, minimum first.
const CLIP_TENSORS: usize = 1;

/// Clips in one layer. Ten clippable linears x 2 bounds, less two because q, k and v share a
/// single input clamp on `norm_pre_attn`'s output. Counted off the export, not guessed: the
/// graph has exactly 18 between consecutive layer-leading norms, for all twelve layers, with
/// none before the first and none after the last.
const CLIPS_PER_LAYER: usize = 18;

/// Quantised projections in one layer: two feed-forwards of two, q, k, v, post, and the conv
/// module's two.
const PROJECTIONS_PER_LAYER: usize = 10;

/// Tensors one quantised projection contributes: kernel, per-block scale, bias.
const PROJECTION_TENSORS: usize = 3;

/// Tensors one **unquantised** projection contributes: kernel and bias, as `Builder::conv`
/// reads them.
const DENSE_TENSORS: usize = 2;

/// RMS norms in one layer: two per feed-forward, two around attention, two in the conv module,
/// and the terminal one.
const NORMS_PER_LAYER: usize = 9;

/// Tensors one layer contributes, in file order.
///
/// The three that are not norms, clips or projections: the folded per-channel query scale, the
/// relative-position table, and the depthwise convolution's kernel and bias.
const LAYER_TENSORS: usize = NORMS_PER_LAYER
    + CLIPS_PER_LAYER * CLIP_TENSORS
    + PROJECTIONS_PER_LAYER * PROJECTION_TENSORS
    + 1
    + 1
    + DENSE_TENSORS;

/// The first subsampling convolution, `[128, 1, 3, 3]` over the mel treated as a one-channel
/// image, with a synthesised zero bias. Held at fp16: it is 1,152 parameters.
///
/// **Its two spatial axes are swapped by the converter**, because [`input_shape`] carries the
/// mel map transposed against the export. Swapping a convolution's input axes is only exact if
/// its kernel's are swapped with them, and a `3 x 3` kernel is not symmetric - so an
/// untransposed kernel here is the right shape and a different convolution. Measured: with the
/// swap this layout reproduces the export's `input_proj` output to cosine 0.99999994, without
/// it 0.906.
pub const SSCP_CONV0: usize = 0;

/// The layer norm after [`SSCP_CONV0`], `[128]` gain and a synthesised zero beta.
///
/// A **layer** norm, not an RMS norm - the only two in the tower, and the export spells them
/// `LayerNormalization` where every other norm is `SimplifiedLayerNormalization`. It normalises
/// over the convolution's output channels, which is this runtime's channel axis, so the two
/// permutes the export wraps it in are not needed. Dropping them is where the front end's
/// arena halves; see the module docs on layout.
pub const SSCP_NORM0: usize = SSCP_CONV0 + DENSE_TENSORS;

/// The second subsampling convolution, `[32, 128, 3, 3]`, with a zero bias. Spatial axes
/// swapped by the converter, as [`SSCP_CONV0`]'s are.
pub const SSCP_CONV1: usize = SSCP_NORM0 + DENSE_TENSORS;

/// The layer norm after [`SSCP_CONV1`], `[32]` gain and zero beta.
pub const SSCP_NORM1: usize = SSCP_CONV1 + DENSE_TENSORS;

/// The projection from the flattened subsampled map into the tower, `[1024, 1024]`, at **fp16**.
///
/// Square by arithmetic rather than by identity: `(128 // 4) * 32` is the two stride-2 halvings
/// of the 128 mel bins times the second convolution's channel count, and it coincidentally
/// equals [`D_MODEL`].
///
/// **Its input rows are permuted by the converter.** The export flattens `[channels, mel]`
/// mel-major (`m * 32 + c`); this runtime's free reshape of a `[32, 32, T]` map is channel-major
/// (`c * 32 + m`). Rather than transpose a 23 MB activation at runtime, the converter reorders
/// the weight's 1024 input rows once. Both orderings are the right size and only one is the
/// right matrix, so this is written down in both places on purpose.
///
/// Unquantised, like the vision tower's two ends and for the same reason: nothing downstream
/// averages away an error made before layer 0 has run.
pub const INPUT_PROJECTION: usize = SSCP_NORM1 + DENSE_TENSORS;

/// The projection to [`OUT_DIM`], `[1536, 1024]`, at **fp16**, with a **real** bias.
///
/// The one biased linear in the export, which is why the exporter split it into a `MatMul` and
/// an `Add` and why the `Add` is the node called `node_linear_133`.
pub const OUT_PROJECTION: usize = INPUT_PROJECTION + DENSE_TENSORS;

/// The embedder's pre-projection RMS norm, `[1536]`.
///
/// `with_scale=False` in the reference, so the exporter materialised an all-ones gain. That is
/// not a no-op - an RMS norm still divides by the RMS - and emitting the export's own ones
/// costs three kilobytes and removes a special case, so the converter emits it rather than
/// dropping it and having the runtime carry a gainless variant.
pub const EMBED_NORM: usize = OUT_PROJECTION + DENSE_TENSORS;

/// The embedder's `[1536, 1536]` projection into text-embedding space, at **fp16**, no bias.
pub const EMBED_PROJECTION: usize = EMBED_NORM + 1;

/// Tensors before any layer.
const SHARED_TENSORS: usize = EMBED_PROJECTION + DENSE_TENSORS;

/// Where the layers start.
const LAYER0: usize = SHARED_TENSORS;

/// Total tensors the `.maml` holds, and the count `maml_convert.py` must write.
pub const TENSORS: usize = SHARED_TENSORS + LAYERS * LAYER_TENSORS;

/// The first tensor of layer `index`.
pub fn layer_at(index: usize) -> usize {
    LAYER0 + index * LAYER_TENSORS
}

/// Positions surviving one stride-2, pad-1, kernel-3 convolution: `ceil(n / 2)`.
pub const fn subsample(n: u32) -> u32 {
    if n == 0 { 0 } else { (n + 1) / 2 }
}

/// Soft tokens `frames` mel frames produce, one per twice-subsampled frame.
///
/// The reference computes this as `mask[:, ::2][:, ::2].sum()` on a padded batch, which is the
/// same number only because a bare `[::2]` decimation is not a min over the convolution's
/// receptive field. This runtime records a plan per frame count and passes no padding, so every
/// subsampled frame is valid and the count is the convolution's own output length. See
/// [`prepare`].
pub const fn tokens(frames: u32) -> u32 {
    subsample(subsample(frames))
}

/// Whether query `q` reads key `k`. The whole attention mask.
///
/// `0 <= q - k <= 11`: itself and eleven past, contiguous, nothing ahead. Only the first eleven
/// positions of a clip are truncated, at the left edge.
pub const fn attends(q: u32, k: u32) -> bool {
    k <= q && q - k < ATTEND_SPAN
}

/// The column of a relative-position table that carries displacement `distance = q - k`.
///
/// `REL_OFFSETS - 1 - distance`, so column 12 is `d = 0` (a query against itself) and column 1
/// is `d = 11` (the oldest key it may read). Column **0** is `d = 12`, which [`attends`] never
/// admits: it is computed by the export's skew and thrown away by the mask.
///
/// This is spelled out because a fencepost here lands on that dead column, produces finite
/// numbers of the right magnitude, and fails nothing.
///
/// **In banded coordinates this collapses to `o = j + 1`.** With `k = band_key(q, j)` the
/// displacement is `q - k = 11 - j`, so the column is `12 - (11 - j) = j + 1`, independent of
/// `q`. That independence is what removes the export's `_rel_shift` skew entirely and lets the
/// bias fuse into the scores op: the shader indexes `table[h][j + 1][..]` directly.
pub const fn rel_column(distance: u32) -> u32 {
    REL_OFFSETS - 1 - distance
}

/// The key that band slot `j` of query `q` refers to: `q - (ATTEND_SPAN - 1) + j`.
///
/// `None` when it would fall before the start of the sequence, which happens only in the first
/// eleven queries. `j = ATTEND_SPAN - 1` always yields `Some(q)`, so no row is ever entirely
/// dead.
///
/// **This is the reference form of the guard and the shader should match it.** The test is
/// `j + q + 1 >= ATTEND_SPAN`: additive, so nothing can wrap in GLSL or panic in Rust, and
/// written against the band rather than the literal 11. The subtraction happens only after the
/// guard has proved the result non-negative - the smallest `q + j + 1` the guard admits is
/// exactly `ATTEND_SPAN`, so it cannot underflow even at the boundary. See the module docs for
/// the three plausible rearrangements that are wrong, one of which empties the band on 738 of
/// 750 queries.
///
/// # `Some(k)` means `k >= 0`. It does **not** mean `k < T`.
///
/// Only the lower bound is guarded, and that is sufficient here for a reason that belongs to the
/// layout rather than to this function: right context is zero, so `k <= q < T` always, and the
/// largest key any query produces is `T - 1`. The upper bound is free.
///
/// Give this tower a non-zero right context, or reuse this helper for a window that reaches
/// forward, and `k` can exceed `q`. The upper bound stops being free and its absence fails
/// **silently**, because this would keep returning `Some` for an out-of-range key and a caller
/// treating `Some` as "safe to index" would read past the end.
/// `the_band_never_reaches_past_the_last_query` trips if that assumption ever changes.
pub const fn band_key(q: u32, j: u32) -> Option<u32> {
    band_key_in(ATTEND_SPAN, q, j)
}

/// [`band_key`] for a window of `span` rather than [`ATTEND_SPAN`], and the one implementation
/// both of them share.
///
/// The op is **parameterised** by its band — `Builder::attn_scores_banded` takes it, the emit
/// puts it in `Push::kh`, and the shaders read it from there. So the CPU oracle must honour that
/// field too. Calling [`band_key`] from an arm that loops over `Push::kh` agrees with the shader
/// only when the band happens to be [`ATTEND_SPAN`], and disagrees silently at every other width
/// — at band 3 it computes `q + j + 1 - 12`, which sends query 0 entirely dead and query 13 to
/// keys 2, 3, 4 instead of 11, 12, 13.
///
/// Parameterised rather than duplicated because this expression has now had six wrong
/// rearrangements proposed by five people. There is one spelling of it in this crate and this is
/// it; [`band_key`] is a name for the [`ATTEND_SPAN`] case, not a second copy.
pub const fn band_key_in(span: u32, q: u32, j: u32) -> Option<u32> {
    if j < span && j + q + 1 >= span {
        Some(q + j + 1 - span)
    } else {
        None
    }
}

/// Declare every tensor of layer `index` against `weights`, in file order.
///
/// The order is the export's topological order, which is what the converter walks. Reading the
/// clips as part of the sequence rather than collecting them separately is deliberate: with
/// eighteen of them a layer and no names to match on, it is the only thing that keeps the two
/// sides in step.
pub fn declare_layer(weights: &dyn WeightSource, index: usize) -> Result<(), String> {
    let at = layer_at(index);
    let mut next = at;
    let plain = |dims: &[u32], n: &mut usize| -> Result<(), String> {
        let here = *n;
        *n += 1;
        weights.shaped(here, dims).map(|_| ())
    };
    let clip = |n: &mut usize| -> Result<(), String> {
        let here = *n;
        *n += CLIP_TENSORS;
        weights.shaped(here, &[2]).map(|_| ())
    };

    // Feed-forward one, the macaron's first half.
    plain(&[D_MODEL], &mut next)?; // ffw1 pre-norm
    clip(&mut next)?;
    projection(weights, &mut next, FFN, D_MODEL)?; // ffw1 up
    clip(&mut next)?;
    clip(&mut next)?; // after the silu
    projection(weights, &mut next, D_MODEL, FFN)?; // ffw1 down
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // ffw1 post-norm

    // Attention.
    plain(&[D_MODEL], &mut next)?; // pre-attention norm
    clip(&mut next)?; // shared by q, k and v
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // q
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // k
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // v
    clip(&mut next)?;
    clip(&mut next)?;
    clip(&mut next)?;
    // The folded `softplus(per_dim_scale) * Q_SCALE`, tiled from [128] to the full width so it
    // is an ordinary per-channel multiply rather than one that repeats every head. Rank three
    // because `Builder::constant` copies it into the arena as a `[D_MODEL, 1, 1]` operand.
    plain(&[D_MODEL, 1, 1], &mut next)?;
    // The relative-position table, transposed by the converter from the export's
    // [heads, head_dim, offsets] so that head_dim - the axis the dot product contracts over -
    // is contiguous.
    plain(&[HEADS, REL_OFFSETS, HEAD_DIM], &mut next)?;
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, HEADS * HEAD_DIM)?; // post
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // post-attention norm

    // The light convolution module.
    plain(&[D_MODEL], &mut next)?; // pre-norm
    clip(&mut next)?;
    projection(weights, &mut next, LCONV_GATE, D_MODEL)?; // gate projection, split by the GLU
    clip(&mut next)?;
    // The causal depthwise kernel, `[1024, 1, 5]` in the export, as a `1 x 5` grouped
    // convolution over a `[D_MODEL, 1, T]` sequence. Unquantised: 5,120 parameters.
    weights.shaped(next, &[D_MODEL, 1, 1, CONV_KERNEL])?;
    weights.shaped(next + 1, &[D_MODEL])?;
    next += DENSE_TENSORS;
    plain(&[D_MODEL], &mut next)?; // conv norm, after the convolution
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, D_MODEL)?; // exit projection
    clip(&mut next)?;

    // Feed-forward two, then the terminal norm.
    plain(&[D_MODEL], &mut next)?; // ffw2 pre-norm
    clip(&mut next)?;
    projection(weights, &mut next, FFN, D_MODEL)?;
    clip(&mut next)?;
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, FFN)?;
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // ffw2 post-norm
    plain(&[D_MODEL], &mut next)?; // terminal norm, no residual

    if next != layer_at(index + 1) {
        return Err(format!(
            "audio layer {index} declared {} tensors, not the {} its span allows",
            next - at,
            layer_at(index + 1) - at
        ));
    }
    Ok(())
}

/// One quantised `1 x 1` projection: kernel, per-block scale, bias.
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

/// One **unquantised** `1 x 1` projection: kernel and bias, the pair `Builder::conv` reads.
fn dense(weights: &dyn WeightSource, next: &mut usize, out: u32, inp: u32) -> Result<(), String> {
    weights.shaped(*next, &[out, inp, 1, 1])?;
    weights.shaped(*next + 1, &[out])?;
    *next += DENSE_TENSORS;
    Ok(())
}

/// Declare the tensors that sit outside any layer, in file order.
pub fn declare_shared(weights: &dyn WeightSource) -> Result<(), String> {
    let mut next = SSCP_CONV0;
    for (channels, inputs) in [(SSCP_CHANNELS[0], 1), (SSCP_CHANNELS[1], SSCP_CHANNELS[0])] {
        weights.shaped(next, &[channels, inputs, SSCP_KERNEL, SSCP_KERNEL])?;
        weights.shaped(next + 1, &[channels])?;
        next += DENSE_TENSORS;
        // Gain then beta, the pair `Builder::layer_norm` reads. The export has no beta; the
        // converter synthesises zeros so the norm needs no second form.
        weights.shaped(next, &[channels])?;
        weights.shaped(next + 1, &[channels])?;
        next += DENSE_TENSORS;
    }
    dense(weights, &mut next, D_MODEL, D_MODEL)?; // input projection
    dense(weights, &mut next, OUT_DIM, D_MODEL)?; // output projection, with a real bias
    weights.shaped(next, &[OUT_DIM])?; // the embedder's pre-projection norm
    next += 1;
    dense(weights, &mut next, OUT_DIM, OUT_DIM)?; // the embedder's projection
    if next != SHARED_TENSORS {
        return Err(format!("{next} shared tensors, not {SHARED_TENSORS}"));
    }
    Ok(())
}

/// Which pass [`build`] emits.
///
/// The frame count is part of the key because a plan is recorded at one shape and a clip's
/// length varies. [`crate::vulkan::Reshaped`] re-records when the key changes, and takes the key
/// by `Copy + PartialEq`, which is what this derives.
///
/// The three variants return different numbers of outputs, deliberately rather than folding
/// [`Mode::Sscp`] into `Trace { layers: 0 }`: a mode whose output count varies with a parameter
/// reads fine and then hands the caller the wrong tensor.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// A whole clip: one output, `[OUT_DIM, 1, tokens(frames)]`, already in text-embedding space.
    Clip {
        /// Mel frames in, before any subsampling.
        frames: u32,
    },
    /// Stop after `layers` layers and hand back both sides of the output projection.
    ///
    /// For parity bisection: comparing the whole tower can only say that the answer moved, and
    /// this says where. Outputs are `[hidden, tail]` - the tower's own state at `[D_MODEL, 1, T]`
    /// **before** `output_proj`, and `[OUT_DIM, 1, T]` after it and its bias. Two rather than one
    /// because the tail is a single `1024 x 1536` matmul and a disagreement could be either side
    /// of it.
    ///
    /// `layers: 0` stops before layer 0 and so reports the front end alone.
    Trace {
        /// Mel frames in.
        frames: u32,
        /// Layers to run, `0..=LAYERS`.
        layers: usize,
    },
    /// Both sides of the input projection: `[sscp_out, projected]`, each `[D_MODEL, 1, T]`.
    ///
    /// `projected` is bit-identical to [`Mode::Trace`]`{ layers: 0 }`'s `hidden` - the export has
    /// nothing but a reshape between them - which makes the pair a free self-check on a harness.
    Sscp {
        /// Mel frames in.
        frames: u32,
    },
}

impl Mode {
    /// The mel frame count this pass is recorded for.
    pub const fn frames(self) -> u32 {
        match self {
            Mode::Clip { frames } | Mode::Trace { frames, .. } | Mode::Sscp { frames } => frames,
        }
    }

    /// Soft tokens the pass emits, which is every output's width.
    pub const fn tokens(self) -> u32 {
        tokens(self.frames())
    }
}

/// Plan inputs, in declaration order.
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[1, MELS, frames]` | the log-mel spectrogram, laid out by [`prepare`] |
///
/// One, where [`super::gemma4_vision`] has three: this tower has no host-gathered position table
/// and no rotary, so the mel is the whole input.
pub const INPUTS: usize = 1;

/// The shortest clip this tower can be recorded for, in soft tokens.
///
/// [`super::Builder::attn_scores_banded`] refuses a band wider than the sequence, and the band
/// cannot simply be narrowed to `T`: column `j` reads relative offset `j + 1`, so a band of `T`
/// would read offsets `1..=T` where the displacements are `0..=T-1`, i.e. the wrong columns of
/// the table. The band is a property of the trained model, not of the clip.
///
/// `T = ATTEND_SPAN` needs 45 mel frames, which is 0.45 s of audio.
pub const MIN_TOKENS: u32 = ATTEND_SPAN;

/// Build the pass `mode` describes.
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    let frames = mode.frames();
    let seq = tokens(frames);
    if frames == 0 {
        return Err("a clip of no frames".into());
    }
    if seq > MAX_TOKENS {
        return Err(format!(
            "{frames} mel frames is {seq} soft tokens, past the {MAX_TOKENS} a clip may hold"
        ));
    }
    if seq < MIN_TOKENS {
        return Err(format!(
            "{frames} mel frames is {seq} soft tokens, under the {MIN_TOKENS} the \
             {ATTEND_SPAN}-wide attention band needs. See MIN_TOKENS: the band is the model's, \
             not the clip's."
        ));
    }
    let stop_after = match mode {
        Mode::Clip { .. } => LAYERS,
        Mode::Sscp { .. } => 0,
        Mode::Trace { layers, .. } if layers <= LAYERS => layers,
        Mode::Trace { layers, .. } => {
            return Err(format!("a trace of {layers} of {LAYERS} layers"));
        }
    };

    let mut builder = Builder::new(weights);
    let b = &mut builder;
    let mel = b.input(input_shape(frames));

    // The subsampling front end, over the mel as a one-channel image with the bins on the height
    // axis and time on the width - see `input_shape`. `layer_norm` normalises the channel axis,
    // which is where the export's two permutes per norm go.
    let x = sscp(b, mel, SSCP_CONV0, SSCP_NORM0, SSCP_CHANNELS[0]);
    let x = sscp(b, x, SSCP_CONV1, SSCP_NORM1, SSCP_CHANNELS[1]);
    // Free: `[32, 32, T]` and `[1024, 1, T]` are the same bytes, which is the whole reason the
    // mel is carried transposed. `INPUT_PROJECTION`'s rows are permuted to suit.
    let sscp_out = b.reshaped(x, Shape::new(D_MODEL, 1, seq));
    let projected = dense_point(b, INPUT_PROJECTION, sscp_out, D_MODEL);

    if let Mode::Sscp { .. } = mode {
        for index in 0..LAYERS {
            declare_layer(weights, index)?;
            name_layer(b, index);
        }
        name_tail(b);
        return builder.finish(&[sscp_out, projected]);
    }

    let mut x = projected;
    for index in 0..stop_after {
        x = layer(b, index, x)?;
    }
    for index in stop_after..LAYERS {
        declare_layer(weights, index)?;
        name_layer(b, index);
    }

    // `output_proj` is the one linear in the export with a real bias.
    let tail = dense_point(b, OUT_PROJECTION, x, OUT_DIM);
    if let Mode::Trace { .. } = mode {
        // Both sides of the projection, so a tower that agrees and an output that does not can
        // be told apart from a tower that never agreed.
        b.host_tensor(EMBED_NORM, &[OUT_DIM]);
        b.host_tensor(EMBED_PROJECTION, &[OUT_DIM, OUT_DIM, 1, 1]);
        b.host_tensor(EMBED_PROJECTION + 1, &[OUT_DIM]);
        return builder.finish(&[x, tail]);
    }

    // The embedder, which the exporter traced through: the result is already in text-embedding
    // space and scatters straight into the decoder's prompt.
    let normed = b.rms_norm(tail, EMBED_NORM, EPSILON);
    let out = dense_point(b, EMBED_PROJECTION, normed, OUT_DIM);
    builder.finish(&[out])
}

/// One subsampling stage: `conv -> layer norm -> ReLU`, halving both axes.
///
/// The convolution carries no bias and the norm no beta; the converter synthesises both as zeros
/// so this needs no special case. `Act::None` on the convolution rather than `Act::Relu` because
/// the norm sits between them.
fn sscp(b: &mut Builder, x: Id, kernel: usize, norm: usize, out: u32) -> Id {
    let convolved = b.conv(
        x,
        kernel,
        out,
        (SSCP_KERNEL, SSCP_KERNEL),
        (SSCP_STRIDE, SSCP_STRIDE),
        (1, 1),
        (1, 1, 1, 1),
        1,
        Act::None,
    );
    let normed = b.layer_norm(convolved, norm, EPSILON);
    b.activate(normed, Act::Relu)
}

/// A `1 x 1` int4 projection, which every linear inside a layer is.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv_int4(x, at, out, Act::None)
}

/// A `1 x 1` **fp16** projection, which the three at the ends are.
fn dense_point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv(x, at, out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None)
}

/// One macaron feed-forward half: `norm -> up -> silu -> down -> norm -> *0.5 -> add`.
///
/// [`RESIDUAL_WEIGHT`] scales the **branch**, after its post-norm and before the add. The four
/// clips are the export's, in its order: the norm's output, the up projection's output, the
/// activation's output, and the down projection's output.
#[allow(clippy::too_many_arguments)]
fn feed_forward(
    b: &mut Builder,
    x: Id,
    pre_norm: usize,
    clip_in: usize,
    up: usize,
    clip_up: usize,
    clip_act: usize,
    down: usize,
    clip_out: usize,
    post_norm: usize,
) -> Id {
    let h = b.rms_norm(x, pre_norm, EPSILON);
    let h = b.clamp(h, clip_in);
    let h = point(b, up, h, FFN);
    let h = b.clamp(h, clip_up);
    let h = b.activate(h, Act::Swish);
    let h = b.clamp(h, clip_act);
    let h = point(b, down, h, D_MODEL);
    let h = b.clamp(h, clip_out);
    let h = b.rms_norm(h, post_norm, EPSILON);
    let h = b.affine(h, RESIDUAL_WEIGHT, 0.0);
    b.add(x, h)
}

/// One conformer layer: `FFN(1/2) -> attention -> conv -> FFN(1/2) -> norm`.
fn layer(b: &mut Builder, index: usize, x: Id) -> Result<Id, String> {
    let at = layer_at(index);
    let mut next = at;
    let one = |n: &mut usize| {
        let here = *n;
        *n += 1;
        here
    };
    let proj = |n: &mut usize| {
        let here = *n;
        *n += PROJECTION_TENSORS;
        here
    };

    let ff1_pre = one(&mut next);
    let ff1_clip_in = one(&mut next);
    let ff1_up = proj(&mut next);
    let ff1_clip_up = one(&mut next);
    let ff1_clip_act = one(&mut next);
    let ff1_down = proj(&mut next);
    let ff1_clip_out = one(&mut next);
    let ff1_post = one(&mut next);
    let pre_attn = one(&mut next);
    let clip_qkv = one(&mut next);
    let q_proj = proj(&mut next);
    let k_proj = proj(&mut next);
    let v_proj = proj(&mut next);
    let clip_q = one(&mut next);
    let clip_k = one(&mut next);
    let clip_v = one(&mut next);
    let query_scale = one(&mut next);
    let relative = one(&mut next);
    let clip_attn = one(&mut next);
    let post_proj = proj(&mut next);
    let clip_post = one(&mut next);
    let post_attn = one(&mut next);
    let lconv_pre = one(&mut next);
    let lconv_clip_in = one(&mut next);
    let lconv_gate = proj(&mut next);
    let lconv_clip_gate = one(&mut next);
    let depthwise = one(&mut next);
    let _depthwise_bias = one(&mut next);
    let conv_norm = one(&mut next);
    let lconv_clip_act = one(&mut next);
    let lconv_exit = proj(&mut next);
    let lconv_clip_out = one(&mut next);
    let ff2_pre = one(&mut next);
    let ff2_clip_in = one(&mut next);
    let ff2_up = proj(&mut next);
    let ff2_clip_up = one(&mut next);
    let ff2_clip_act = one(&mut next);
    let ff2_down = proj(&mut next);
    let ff2_clip_out = one(&mut next);
    let ff2_post = one(&mut next);
    let norm_out = one(&mut next);
    if next != layer_at(index + 1) {
        return Err(format!("audio layer {index} read {} tensors", next - at));
    }

    let x = feed_forward(
        b, x, ff1_pre, ff1_clip_in, ff1_up, ff1_clip_up, ff1_clip_act, ff1_down, ff1_clip_out,
        ff1_post,
    );

    // Attention. q and k are fully scaled here, so the score op is passed 1.0: the query takes a
    // per-channel vector the converter folded `Q_SCALE` into, and the key a scalar. Neither can
    // live in the op - a vector does not factor out of a dot product, and the key's scalar
    // applies to the content term but not to the relative one.
    let normed = b.rms_norm(x, pre_attn, EPSILON);
    let normed = b.clamp(normed, clip_qkv);
    let q = point(b, q_proj, normed, HEADS * HEAD_DIM);
    let q = b.clamp(q, clip_q);
    let k = point(b, k_proj, normed, HEADS * HEAD_DIM);
    let k = b.clamp(k, clip_k);
    let v = point(b, v_proj, normed, HEADS * HEAD_DIM);
    let v = b.clamp(v, clip_v);
    let scale = b.constant(query_scale, Shape::new(D_MODEL, 1, 1));
    let q = b.mul_channel(q, scale);
    let k = b.affine(k, K_SCALE, 0.0);
    // The cap is fused into the scores op, before its own masking sentinel; see `LOGIT_CAP`.
    let scores =
        b.attn_scores_banded(q, k, HEADS, ATTEND_SPAN, relative, REL_OFFSETS, 1.0, LOGIT_CAP);
    let probs = b.softmax(scores);
    let mixed = b.attn_apply_banded(probs, v, HEADS, ATTEND_SPAN);
    let mixed = b.clamp(mixed, clip_attn);
    let attended = point(b, post_proj, mixed, D_MODEL);
    let attended = b.clamp(attended, clip_post);
    let attended = b.rms_norm(attended, post_attn, EPSILON);
    // Plain add: the half belongs to the feed-forwards only.
    let x = b.add(x, attended);

    // The light convolution module, `norm -> gate -> GLU -> causal conv -> norm -> silu -> exit`.
    let gated = b.rms_norm(x, lconv_pre, EPSILON);
    let gated = b.clamp(gated, lconv_clip_in);
    let gated = point(b, lconv_gate, gated, LCONV_GATE);
    let gated = b.clamp(gated, lconv_clip_gate);
    let value = b.slice_channels(gated, 0, D_MODEL);
    let gate = b.slice_channels(gated, D_MODEL, D_MODEL);
    let gate = b.activate(gate, Act::Sigmoid);
    let gated = b.mul(value, gate);
    // Causal: four taps of left pad and none of right, over the width axis.
    let gated = b.conv(
        gated,
        depthwise,
        D_MODEL,
        (1, CONV_KERNEL),
        (1, 1),
        (1, 1),
        (0, CONV_LEFT_PAD, 0, 0),
        D_MODEL,
        Act::None,
    );
    let gated = b.rms_norm(gated, conv_norm, EPSILON);
    let gated = b.activate(gated, Act::Swish);
    let gated = b.clamp(gated, lconv_clip_act);
    let gated = point(b, lconv_exit, gated, D_MODEL);
    let gated = b.clamp(gated, lconv_clip_out);
    let x = b.add(x, gated);

    let x = feed_forward(
        b, x, ff2_pre, ff2_clip_in, ff2_up, ff2_clip_up, ff2_clip_act, ff2_down, ff2_clip_out,
        ff2_post,
    );
    Ok(b.rms_norm(x, norm_out, EPSILON))
}

/// Declare every tensor of a layer [`build`] skipped, so [`Builder::finish`] still sees it read.
///
/// A [`Mode::Trace`] or [`Mode::Sscp`] stops early on purpose, and an unread tensor is otherwise
/// exactly what a forward pass that lost a layer looks like from the outside.
fn name_layer(b: &mut Builder, index: usize) {
    let at = layer_at(index);
    let mut next = at;
    let one = |b: &mut Builder, n: &mut usize, dims: &[u32]| {
        b.host_tensor(*n, dims);
        *n += 1;
    };
    let proj = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out, inp.div_ceil(crate::weights::I4_BLOCK)]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };
    let half = |b: &mut Builder, n: &mut usize| {
        one(b, n, &[D_MODEL]);
        one(b, n, &[2]);
        proj(b, n, FFN, D_MODEL);
        one(b, n, &[2]);
        one(b, n, &[2]);
        proj(b, n, D_MODEL, FFN);
        one(b, n, &[2]);
        one(b, n, &[D_MODEL]);
    };

    half(b, &mut next);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[2]);
    for _ in 0..3 {
        proj(b, &mut next, HEADS * HEAD_DIM, D_MODEL);
    }
    for _ in 0..3 {
        one(b, &mut next, &[2]);
    }
    one(b, &mut next, &[D_MODEL, 1, 1]);
    one(b, &mut next, &[HEADS, REL_OFFSETS, HEAD_DIM]);
    one(b, &mut next, &[2]);
    proj(b, &mut next, D_MODEL, HEADS * HEAD_DIM);
    one(b, &mut next, &[2]);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[2]);
    proj(b, &mut next, LCONV_GATE, D_MODEL);
    one(b, &mut next, &[2]);
    one(b, &mut next, &[D_MODEL, 1, 1, CONV_KERNEL]);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[D_MODEL]);
    one(b, &mut next, &[2]);
    proj(b, &mut next, D_MODEL, D_MODEL);
    one(b, &mut next, &[2]);
    half(b, &mut next);
    one(b, &mut next, &[D_MODEL]);
    debug_assert_eq!(next, layer_at(index + 1), "named {} tensors", next - at);
}

/// Declare the tail tensors a [`Mode::Sscp`] pass never reaches.
fn name_tail(b: &mut Builder) {
    b.host_tensor(OUT_PROJECTION, &[OUT_DIM, D_MODEL, 1, 1]);
    b.host_tensor(OUT_PROJECTION + 1, &[OUT_DIM]);
    b.host_tensor(EMBED_NORM, &[OUT_DIM]);
    b.host_tensor(EMBED_PROJECTION, &[OUT_DIM, OUT_DIM, 1, 1]);
    b.host_tensor(EMBED_PROJECTION + 1, &[OUT_DIM]);
}

/// The shape the export holds for a kernel this module declares as `dims`.
///
/// The inverse of the transposes `declare_layer` and `declare_shared` describe, so a converter
/// check can compare against the ONNX without either side restating the other's convention.
/// Scales, biases, norm gains and clip pairs have no counterpart to transpose and come back
/// unchanged.
///
/// Two cases beyond the decoder's and the vision tower's:
///
/// * The depthwise convolution is `[1024, 1, 5]` in the export and gains a unit height here, so
///   the rank differs rather than the order.
/// * The two subsampling kernels come back unchanged, which is correct for the *shape* even
///   though the converter swaps their spatial axes: both are `3 x 3`, so only the values move.
///   See [`SSCP_CONV0`].
pub fn as_exported(dims: &[u32]) -> Vec<u32> {
    match dims {
        [out, inp, 1, 1] => vec![*inp, *out],
        [out, 1, 1, taps] => vec![*out, 1, *taps],
        other => other.to_vec(),
    }
}

/// The shape of plan input 0 for a clip of `frames` mel frames.
///
/// `[1, MELS, frames]`: one channel, the mel bins on the height axis and time on the width.
///
/// **The two spatial axes are transposed against the reference**, which treats the mel as
/// `[1, frames, 128]`. The convolutions are square in kernel, stride and padding, so this is
/// exact **provided their kernels are transposed with it** - the converter does that; see
/// [`SSCP_CONV0`]. What it buys is the flatten into [`INPUT_PROJECTION`] becoming a relabelling
/// instead of a copy of a 23 MB map, and the export's two permutes around each channel-last
/// `LayerNormalization` disappearing, since this runtime's [`super::Builder::layer_norm`]
/// already normalises over the channel axis. The price is the row permutation baked into that
/// projection's weight, which the converter also applies.
///
/// Verified end to end rather than argued: running the export to `input_proj`'s output under
/// onnxruntime and this layout in numpy on the same input agrees to cosine 0.99999994.
pub fn input_shape(frames: u32) -> Shape {
    Shape::new(1, MELS, frames)
}

/// Plan input 0: the log-mel spectrogram, laid out as [`input_shape`] describes.
///
/// `mel` is what [`crate::logmel::LogMel::spectrogram`] appends - `frames` rows of
/// [`crate::logmel::MELS`] values, row major - and this transposes it into the mel-major,
/// time-minor order the plan reads.
///
/// # What the caller still owes, and what it does not
///
/// [`crate::logmel`] implements the reference's `_extract_spectrogram` and nothing around it.
/// The caller must resample to 16 kHz mono and truncate to [`MAX_SAMPLES`].
///
/// It must **not** zero-pad the samples to a multiple of 128. The reference does, so that a
/// batch stacks, and then carries a validity mask through the whole tower to undo it: two mask
/// multiplies in the front end, a masked attention term, and a `GatherND` on the output. A plan
/// here is recorded per frame count, exactly as [`super::gemma4_vision`] records one per patch
/// grid, so the clip is passed at its true length, every frame is valid and all of that
/// disappears.
///
/// Measured, not argued: against the export under onnxruntime, padded-and-masked versus
/// unpadded is bit-identical over 68 configurations, 50 of them at the one residue where the
/// second mask demonstrably does work. The second mask exists to zero a subsampled position
/// whose convolution window straddled the real/pad boundary; unpadded, that position does not
/// exist and the convolution's own edge padding supplies the same zero.
///
/// **If padding is ever reintroduced - to batch clips, say - the masks come back, and they come
/// back with a known signature.** Omitting them then corrupts exactly one soft token, the
/// **last** one, on clips where the frame count is `2 (mod 4)`: about a quarter of them. A
/// 30-second clip is 2,999 frames, which is `3 (mod 4)` and therefore immune, so a test that
/// only ever runs a full-length clip cannot see it.
pub fn prepare(mel: &[f32], frames: u32) -> Result<Vec<f32>, String> {
    let mels = MELS as usize;
    let count = frames as usize;
    if frames == 0 {
        return Err("a clip of no frames".into());
    }
    // Every arena figure this tower was budgeted against assumes T <= MAX_TOKENS, which the
    // reference guarantees by truncating to MAX_SAMPLES. Nothing downstream re-checks it: a
    // 60-second clip is 5,999 frames and 1,500 tokens, and `tokens`, `input_shape` and the plan
    // would all accept it and quietly allocate twice the arena that was approved.
    let want = tokens(frames);
    if want > MAX_TOKENS {
        return Err(format!(
            "{frames} mel frames is {want} soft tokens, past the {MAX_TOKENS} a clip may hold. \
             Truncate the waveform to {MAX_SAMPLES} samples first, as the reference does."
        ));
    }
    if mel.len() != count * mels {
        return Err(format!(
            "{} mel values for {frames} frames, not {}",
            mel.len(),
            count * mels
        ));
    }
    let mut out = vec![0.0; mel.len()];
    for frame in 0..count {
        for bin in 0..mels {
            out[bin * count + frame] = mel[frame * mels + bin];
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nets::tests::Shapes;

    #[test]
    fn the_layout_matches_the_converter() {
        let source = Shapes::new(TENSORS);
        declare_shared(&source).expect("the shared tensors");
        for index in 0..LAYERS {
            declare_layer(&source, index).expect("a layer");
        }
        assert_eq!(layer_at(LAYERS), TENSORS);
        // Every index exactly once, which is what a positional table means and what a cursor
        // that drifted by one inside a layer would break without changing the total.
        let mut seen: Vec<usize> = source.asked.borrow().iter().map(|(at, _)| *at).collect();
        seen.sort_unstable();
        assert_eq!(seen, (0..TENSORS).collect::<Vec<_>>());
    }

    #[test]
    fn the_layer_span_is_what_the_export_counts() {
        // Each of these was counted off audio_encoder_fp16.onnx rather than derived, and each
        // would shift every tensor after it if the export were re-exported differently:
        // 122 MatMul/Gemm named node_linear (12 * 10 + input and embedding projections),
        // 109 SimplifiedLayerNormalization (12 * 9 + the embedder's), 216 Clip (12 * 18, none
        // outside a layer), 14 Conv (2 + 12), 12 tables of [8, 128, 13].
        assert_eq!(NORMS_PER_LAYER * LAYERS + 1, 109);
        assert_eq!(CLIPS_PER_LAYER * LAYERS, 216);
        assert_eq!(PROJECTIONS_PER_LAYER * LAYERS + 2, 122);
        assert_eq!(LAYER_TENSORS, 61);
        assert_eq!(SHARED_TENSORS, 15);
        assert_eq!(TENSORS, 747);
    }

    #[test]
    fn the_two_ends_and_the_front_end_are_not_quantised() {
        // The mixed precision is the contract with `collect_gemma4_audio`, and a converter that
        // wrote a scale here would shift every index after it.
        assert_eq!(SSCP_NORM0, SSCP_CONV0 + 2);
        assert_eq!(INPUT_PROJECTION, SSCP_NORM1 + 2);
        assert_eq!(OUT_PROJECTION, INPUT_PROJECTION + 2);
        assert_eq!(EMBED_NORM, OUT_PROJECTION + 2);
        assert_eq!(EMBED_PROJECTION, EMBED_NORM + 1);
    }

    #[test]
    fn a_query_reads_itself_and_eleven_past_and_nothing_else() {
        // The worked examples from the contract, and the ones that separate the sliding window
        // from the block-diagonal reading it is easy to mistake it for. `q = 12` is the case
        // that decides it: under a block-diagonal mask it would read only itself.
        let reads = |q: u32, upto: u32| (0..upto).filter(|k| attends(q, *k)).collect::<Vec<_>>();
        assert_eq!(reads(0, 64), vec![0]);
        assert_eq!(reads(11, 64), (0..=11).collect::<Vec<_>>());
        assert_eq!(reads(12, 64), (1..=12).collect::<Vec<_>>());
        assert_eq!(reads(30, 64), (19..=30).collect::<Vec<_>>());
        // Nothing ahead, ever, and never more than the span.
        for q in 0..64_u32 {
            assert!(!attends(q, q + 1), "query {q} reads the future");
            assert_eq!(reads(q, 200).len() as u32, (q + 1).min(ATTEND_SPAN));
        }
    }

    #[test]
    fn the_relative_table_is_thirteen_wide_and_twelve_live() {
        // The fencepost. Column 12 is a query against itself and column 1 is the oldest key it
        // may read; column 0 is `d = 12`, which the mask never admits.
        assert_eq!(rel_column(0), 12);
        assert_eq!(rel_column(11), 1);
        assert_eq!(rel_column(ATTEND_SPAN), 0);
        let live: Vec<u32> = (0..REL_OFFSETS).filter(|d| attends(*d, 0)).map(rel_column).collect();
        assert_eq!(live, (1..=12).rev().collect::<Vec<_>>());
        assert_eq!(live.len() as u32, ATTEND_SPAN, "twelve of thirteen columns");
        assert!(!live.contains(&0), "column 0 is computed and thrown away");
    }

    #[test]
    fn the_band_agrees_with_the_mask_and_with_the_relative_column() {
        // The two index maps the banded scores op relies on, checked against each other rather
        // than each against my arithmetic. `band_key` decides which key a slot reads and
        // `rel_column` decides which table column weights it; a fencepost in either lands on a
        // plausible value, so the check is that they agree for every (q, j) at once.
        for q in 0..40_u32 {
            let mut live = 0;
            for j in 0..ATTEND_SPAN {
                match band_key(q, j) {
                    Some(k) => {
                        live += 1;
                        assert!(attends(q, k), "q {q} slot {j} reads {k}, which is not attended");
                        assert_eq!(q - k, ATTEND_SPAN - 1 - j, "displacement at q {q} slot {j}");
                        // The collapse that removes the skew: the column is j + 1, whatever q is.
                        assert_eq!(rel_column(q - k), j + 1, "column at q {q} slot {j}");
                    }
                    None => assert!(j < ATTEND_SPAN - 1 - q.min(ATTEND_SPAN - 1)),
                }
            }
            // Never a dead row: the last slot is the diagonal, always live and always in range.
            assert_eq!(band_key(q, ATTEND_SPAN - 1), Some(q));
            assert_eq!(live, (q + 1).min(ATTEND_SPAN), "live slots at q {q}");
            // Every key the mask admits is reachable from some slot, so the band loses nothing.
            let banded: Vec<u32> = (0..ATTEND_SPAN).filter_map(|j| band_key(q, j)).collect();
            let masked: Vec<u32> = (0..=q).filter(|k| attends(q, *k)).collect();
            assert_eq!(banded, masked, "the band and the mask disagree at q {q}");
        }
        assert_eq!(rel_column(0), ATTEND_SPAN, "the diagonal reads the last column");
        // The start edge, counted: 11 rows with dead slots, 66 in total.
        let dead: u32 = (0..40_u32)
            .map(|q| (0..ATTEND_SPAN).filter(|j| band_key(q, *j).is_none()).count() as u32)
            .sum();
        assert_eq!(dead, 66, "dead slots across the whole sequence");
    }

    #[test]
    fn the_additive_guard_is_the_only_rearrangement_that_holds() {
        // The team burned an afternoon on this expression, so it is pinned rather than trusted.
        // The signed condition is `q - (ATTEND_SPAN - 1) + j >= 0`, evaluated here in i64 where
        // it cannot wrap, and every candidate guard is checked against it over the whole
        // sequence and the whole band - the REACHABLE domain only, since a guard is not required
        // to be correct for queries past the end of the sequence.
        let span = i64::from(ATTEND_SPAN);
        let mut additive = 0;
        let mut subtractive = 0;
        for q in 0..MAX_TOKENS {
            for j in 0..ATTEND_SPAN {
                let signed = i64::from(q) - (span - 1) + i64::from(j);
                let want = signed >= 0;
                // The mandated form. No subtraction, so nothing to wrap or panic.
                if (j + q + 1 >= ATTEND_SPAN) != want {
                    additive += 1;
                }
                // The retracted one, `j >= 11 - q`, evaluated as u32 would evaluate it. Rust
                // would panic in debug rather than wrap, so the wrap is modelled explicitly -
                // GLSL is where it silently empties the band.
                let threshold = (ATTEND_SPAN - 1).wrapping_sub(q);
                if (j >= threshold) != want {
                    subtractive += 1;
                }
                assert_eq!(band_key(q, j).is_some(), want, "band_key at q {q} slot {j}");
            }
        }
        assert_eq!(additive, 0, "`j + q + 1 >= band` must match the signed condition everywhere");
        // Right on the twelve rows q = 0..=11, wrong on every row after them.
        assert_eq!(
            subtractive,
            (MAX_TOKENS - ATTEND_SPAN) * ATTEND_SPAN,
            "`j >= band - 1 - q` should be wrong on every query past the edge"
        );
    }

    #[test]
    fn the_band_never_reaches_past_the_last_query() {
        // `band_key` guards the LOWER bound only, and that is sufficient because right context is
        // zero: k <= q < T, so the upper bound is free. This asserts that property rather than
        // trusting it, because if the window ever reaches forward the helper keeps returning
        // Some(k) for an out-of-range k and a caller treating Some as "safe to index" reads past
        // the end. A comment would not fail; this does.
        let mut highest = 0;
        for q in 0..MAX_TOKENS {
            for j in 0..ATTEND_SPAN {
                if let Some(k) = band_key(q, j) {
                    assert!(k <= q, "q {q} slot {j} reads {k}, which is ahead of the query");
                    highest = highest.max(k);
                }
            }
        }
        assert_eq!(highest, MAX_TOKENS - 1, "the largest key any query reads");
        // The same statement in the form the shader needs: right context is zero.
        assert_eq!(band_key(0, ATTEND_SPAN - 1), Some(0));
        assert_eq!(band_key(MAX_TOKENS - 1, ATTEND_SPAN - 1), Some(MAX_TOKENS - 1));
        assert_eq!(band_key(MAX_TOKENS - 1, 0), Some(MAX_TOKENS - ATTEND_SPAN));
        // Past the band is not a slot at all, whatever the guard would say about it.
        assert_eq!(band_key(100, ATTEND_SPAN), None, "slot 12 is outside a 12-wide band");
    }

    #[test]
    fn the_fill_is_the_most_negative_finite_half() {
        // Not -1e9. The arena is fp16, where -1e9 saturates to -inf and a fully masked row would
        // give exp(NaN). -65504 underflows to zero against any live peak and stays finite.
        assert_eq!(MASK_FILL, -65504.0);
        assert!(MASK_FILL.is_finite());
        assert!(f64::from(MASK_FILL) < -f64::from(LOGIT_CAP) * 1000.0, "must swamp the softcap");
        // The reason the cap is fused rather than a separate pass: capping the sentinel would
        // bring it back to -50, which is a perfectly ordinary logit.
        let capped = (MASK_FILL / LOGIT_CAP).tanh() * LOGIT_CAP;
        assert!((capped + LOGIT_CAP).abs() < 1e-3, "a capped sentinel is {capped}, not -inf");
    }

    #[test]
    fn the_token_count_matches_the_reference_framing() {
        // From the contract's table, which was derived from the framing arithmetic rather than
        // from `ceil(ms / 40)`: the two agree only because the semicausal 160-sample prepend
        // makes them.
        for (seconds, frames, want) in [(1, 99, 25), (10, 999, 250), (30, 2999, 750)] {
            let samples = seconds * crate::logmel::SAMPLE_RATE as usize;
            assert_eq!(crate::logmel::frame_count(samples), frames, "{seconds}s frames");
            assert_eq!(tokens(frames as u32), want, "{seconds}s tokens");
            assert_eq!(want, seconds as u32 * 1000 / 40, "{seconds}s against 40 ms a token");
        }
        assert_eq!(crate::logmel::frame_count(MAX_SAMPLES), 2999);
        assert_eq!(tokens(2999), MAX_TOKENS);
        // The mel axis reduces the same way, which is where `input_proj`'s square shape comes
        // from: `(128 // 4) * 32`.
        assert_eq!(MELS_SUBSAMPLED, 32);
        assert_eq!(MELS_SUBSAMPLED * SSCP_CHANNELS[1], D_MODEL);
        assert_eq!(tokens(0), 0);
        assert_eq!(tokens(1), 1);
    }

    #[test]
    fn the_head_geometry_is_ordinary_multi_head() {
        assert_eq!(HEADS * HEAD_DIM, D_MODEL);
        assert_eq!(FFN, 4 * D_MODEL);
        assert_eq!(LCONV_GATE, 2 * D_MODEL);
        assert_eq!(CONV_LEFT_PAD, 4, "causal: four left, none right");
    }

    #[test]
    fn the_folded_scales_are_the_constants_the_export_holds() {
        // Measured in the graph as val_279 and val_280. The `/ ln 2` in the query scale is the
        // part nobody would guess, and reproducing `HEAD_DIM ** -0.5` alone would be off by
        // 1.44x with no shape to catch it.
        let q = (f64::from(HEAD_DIM).powf(-0.5) / 2.0_f64.ln()) as f32;
        let k = ((1.0 + 1.0_f64.exp()).ln() / 2.0_f64.ln()) as f32;
        assert!((q - Q_SCALE).abs() < 1e-7, "{q} against {Q_SCALE}");
        assert!((k - K_SCALE).abs() < 1e-6, "{k} against {K_SCALE}");
        assert!((q - 0.127_517_431_974_411).abs() < 1e-7);
        assert!((k - 1.894_636_154_174_804_7).abs() < 1e-6);
    }

    #[test]
    fn the_parameter_total_is_within_reach_of_the_published_size() {
        // 12 layers of two 4x feed-forwards, four attention projections, and a 2x-gated conv
        // module, plus the three end projections. This is not an estimate: it reproduces the
        // 294,387,712 quantisable elements counted off audio_encoder_fp16.onnx exactly, which
        // is 99.8% of the 589,840,640-byte .onnx_data - the remainder being the norms, the
        // clips, the relative tables and the two subsampling kernels.
        //
        // The tower is bigger than the vision one, 24.5 M parameters a layer against 18.9 M,
        // and that is a real budget line rather than a rounding error.
        let per_layer = 2 * (2 * D_MODEL * FFN)
            + 4 * D_MODEL * D_MODEL
            + D_MODEL * LCONV_GATE
            + D_MODEL * D_MODEL;
        let ends = D_MODEL * D_MODEL + OUT_DIM * D_MODEL + OUT_DIM * OUT_DIM;
        let total = u64::from(LAYERS as u32 * per_layer + ends);
        assert_eq!(total, 294_387_712, "against the export's initializer census");
        let megabytes = total as f64 * 2.0 / 1e6;
        assert!(
            (585.0..592.0).contains(&megabytes),
            "{megabytes:.0} MB against the export's 590 MB of fp16 weights"
        );
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // The layout, restated in the export's own convention and compared against shapes
        // transcribed by hand from audio_encoder_fp16.onnx. `declare_layer` and the converter
        // could drift together and the other tests would not see it, because both sides are
        // this module's convention; these numbers come from the file.
        let exported = |declare: &dyn Fn(&Shapes) -> Result<(), String>| -> Vec<Vec<u32>> {
            let source = Shapes::new(TENSORS);
            declare(&source).expect("the declaration");
            let asked = source.asked.borrow();
            asked.iter().map(|(_, d)| as_exported(d)).collect()
        };
        let count = |shapes: &[Vec<u32>], want: &[u32]| shapes.iter().filter(|s| *s == want).count();

        let layer = exported(&|s| declare_layer(s, 0));
        assert_eq!(layer.len(), LAYER_TENSORS);
        // The ten int4 projections, in the export's [in, out] order.
        assert_eq!(count(&layer, &[D_MODEL, FFN]), 2, "ffw1.up and ffw2.up: {layer:?}");
        assert_eq!(count(&layer, &[FFN, D_MODEL]), 2, "the two downs");
        assert_eq!(count(&layer, &[D_MODEL, D_MODEL]), 5, "q, k, v, post and lconv.exit");
        assert_eq!(count(&layer, &[D_MODEL, LCONV_GATE]), 1, "lconv.gate");
        // The depthwise loses the unit height it gains here.
        assert_eq!(count(&layer, &[D_MODEL, 1, CONV_KERNEL]), 1, "the depthwise kernel");
        // The relative table keeps the converter's transposed order; there is no [in, out] to
        // restate, and the export's own [8, 128, 13] is a different thing.
        assert_eq!(count(&layer, &[HEADS, REL_OFFSETS, HEAD_DIM]), 1, "the relative table");

        let shared = exported(&|s| declare_shared(s));
        assert_eq!(shared.len(), SHARED_TENSORS);
        assert_eq!(count(&shared, &[D_MODEL, D_MODEL]), 1, "input_projection: {shared:?}");
        assert_eq!(count(&shared, &[D_MODEL, OUT_DIM]), 1, "output_projection");
        assert_eq!(count(&shared, &[OUT_DIM, OUT_DIM]), 1, "embedder.projection");
        // Square kernels, so the converter's spatial swap moves values and not the shape.
        assert_eq!(count(&shared, &[SSCP_CHANNELS[0], 1, SSCP_KERNEL, SSCP_KERNEL]), 1);
        assert_eq!(
            count(&shared, &[SSCP_CHANNELS[1], SSCP_CHANNELS[0], SSCP_KERNEL, SSCP_KERNEL]),
            1
        );
        // A 1x1 projection is the only thing that transposes; everything else is left alone.
        assert_eq!(as_exported(&[2]), vec![2], "a clip pair");
        assert_eq!(as_exported(&[D_MODEL]), vec![D_MODEL], "a norm gain");
    }

    #[test]
    fn a_mode_carries_its_shape_and_its_token_count() {
        // `Reshaped` keys its re-record on this, so the derives are load-bearing: a Mode that
        // did not compare by value would re-record every call, and one that was not Copy would
        // not fit the `fn(&Offsets, S)` plan pointer at all.
        let clip = Mode::Clip { frames: 2999 };
        assert_eq!(clip.frames(), 2999);
        assert_eq!(clip.tokens(), MAX_TOKENS);
        assert_eq!(Mode::Trace { frames: 2999, layers: 0 }.tokens(), MAX_TOKENS);
        assert_eq!(Mode::Sscp { frames: 99 }.tokens(), 25);
        // Distinct keys, or a trace sweep would silently reuse layer 0's recording.
        assert_ne!(
            Mode::Trace { frames: 99, layers: 3 },
            Mode::Trace { frames: 99, layers: 4 }
        );
        assert_ne!(Mode::Clip { frames: 99 }, Mode::Sscp { frames: 99 });
        assert_eq!(Mode::Clip { frames: 99 }, Mode::Clip { frames: 99 });
        assert_eq!(INPUTS, 1, "the mel is the whole input");
    }

    #[test]
    fn every_mode_records_and_reads_every_tensor() {
        // `Builder::finish` refuses a file with an unread tensor, which is what a pass that lost
        // a layer looks like from the outside. The early-stopping modes therefore have to name
        // what they skip, and this is the test that they do - against the stub, so it runs
        // without the 166 MiB artefact.
        let frames = 999_u32;
        for mode in [
            Mode::Clip { frames },
            Mode::Sscp { frames },
            Mode::Trace { frames, layers: 0 },
            Mode::Trace { frames, layers: 1 },
            Mode::Trace { frames, layers: LAYERS },
        ] {
            let source = Shapes::new(TENSORS);
            let plan = build(&source, mode).unwrap_or_else(|e| panic!("{mode:?}: {e}"));
            assert!(!plan.ops.is_empty(), "{mode:?} recorded nothing");
            let mut seen: Vec<usize> = source.asked.borrow().iter().map(|(i, _)| *i).collect();
            seen.sort_unstable();
            seen.dedup();
            assert_eq!(seen, (0..TENSORS).collect::<Vec<_>>(), "{mode:?} left a tensor unread");
        }
    }

    #[test]
    fn a_clip_outside_the_recordable_range_is_refused() {
        // The band is the trained model's, not the clip's: narrowing it to T would read relative
        // offsets 1..=T where the displacements are 0..=T-1, i.e. the wrong table columns. So a
        // clip under MIN_TOKENS is refused rather than quietly mis-indexed.
        assert_eq!(tokens(44), 11, "44 frames is one token short of the band");
        let refused =
            build(&Shapes::new(TENSORS), Mode::Clip { frames: 44 }).expect_err("a 0.44 s clip");
        assert!(refused.contains("under the 12"), "{refused}");
        assert_eq!(tokens(45), MIN_TOKENS, "45 frames is exactly the band");
        assert!(build(&Shapes::new(TENSORS), Mode::Clip { frames: 45 }).is_ok());
        assert!(build(&Shapes::new(TENSORS), Mode::Clip { frames: 0 }).is_err());
        assert!(build(&Shapes::new(TENSORS), Mode::Clip { frames: 6000 }).is_err(), "past the cap");
        assert!(
            build(&Shapes::new(TENSORS), Mode::Trace { frames: 999, layers: LAYERS + 1 }).is_err()
        );
    }

    #[test]
    fn a_clip_past_the_thirty_second_cap_is_refused_by_prepare() {
        // Every arena figure was approved against T <= 750. Nothing downstream re-checks it, so
        // a caller that forgot to truncate would get twice the approved arena and no complaint.
        let over = crate::logmel::frame_count(2 * MAX_SAMPLES);
        assert!(tokens(over as u32) > MAX_TOKENS, "{over} frames should be over the cap");
        let mel = vec![0.0_f32; over * MELS as usize];
        let refused = prepare(&mel, over as u32).expect_err("a 60-second clip");
        assert!(refused.contains("past the 750"), "{refused}");
        // The cap itself is fine.
        let at_cap = crate::logmel::frame_count(MAX_SAMPLES);
        assert_eq!(tokens(at_cap as u32), MAX_TOKENS);
        assert!(prepare(&vec![0.0; at_cap * MELS as usize], at_cap as u32).is_ok());
        assert!(prepare(&[], 0).is_err(), "an empty clip");
    }

    #[test]
    fn prepare_lays_a_mel_bin_out_where_the_plan_reads_it() {
        // Row-major frames in, mel-major out. Getting this the wrong way round is a transpose
        // that changes no shape when the frame count happens to equal 128.
        let frames = 5_u32;
        let mut mel = vec![0.0_f32; frames as usize * MELS as usize];
        mel[3 * MELS as usize + 7] = 1.0; // frame 3, bin 7
        let laid = prepare(&mel, frames).expect("a spectrogram");
        assert_eq!(laid.len(), mel.len());
        assert_eq!(laid[7 * frames as usize + 3], 1.0);
        assert_eq!(laid.iter().filter(|v| **v != 0.0).count(), 1);
        assert_eq!(input_shape(frames), Shape::new(1, MELS, frames));
        assert!(prepare(&mel, frames + 1).is_err(), "a short spectrogram");
    }

    /// The one check that compares this layout against a real converter output rather than
    /// against a stub, which is what every other test here does.
    ///
    /// Ignored and a no-op without `GEMMA4_AUDIO_MAML`, because the file is a 166 MiB build
    /// artefact rather than a committed asset - unlike the nets in `tests/assets.rs`, whose
    /// `.maml`s are small enough to live in the tree. **Both the variable and an ignore-bypass
    /// flag are needed**; setting the variable alone leaves it skipped, silently:
    ///
    /// ```text
    /// GEMMA4_AUDIO_MAML=.../gemma4_audio.maml \
    ///   cargo test -p modelrunner --lib -- --include-ignored the_converter_output_matches
    /// ```
    ///
    /// `--ignored` in place of `--include-ignored` also works and runs only the ignored tests.
    ///
    /// `Shapes` records the dims a pass asks for and returns `Ok` without comparing them to
    /// anything, so the other tests prove this module is self-consistent and prove nothing about
    /// whether `collect_gemma4_audio` agrees with it. This is the test that closes that gap.
    #[test]
    #[ignore = "needs GEMMA4_AUDIO_MAML; the file is a build artefact, not a committed asset"]
    fn the_converter_output_matches_this_layout() {
        let Ok(path) = std::env::var("GEMMA4_AUDIO_MAML") else {
            println!("SKIPPED the_converter_output_matches_this_layout: GEMMA4_AUDIO_MAML unset");
            return;
        };
        let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::GEMMA4_AUDIO)
            .unwrap_or_else(|e| panic!("{path} is not a usable gemma4_audio .maml: {e}"));

        assert_eq!(weights.len(), TENSORS, "{path} holds the wrong number of tensors");
        declare_shared(&weights).expect("the shared tensors match the file");
        for index in 0..LAYERS {
            declare_layer(&weights, index)
                .unwrap_or_else(|e| panic!("layer {index} does not match the file: {e}"));
        }

        // The precision split is the contract with `collect_gemma4_audio.projection` and with
        // the parity harness's int4 control: quantise something we ship at fp16 and the device
        // looks better than it is, leave something fp16 that we ship at int4 and it looks worse.
        let quantised = (0..TENSORS)
            .filter(|i| weights.tensor(*i).expect("a tensor").dtype.is_quantised())
            .count();
        assert_eq!(quantised, LAYERS * PROJECTIONS_PER_LAYER, "int4 tensors");
        assert_eq!(TENSORS - quantised, 627, "fp16 tensors");

        // And the pass itself against the real file: every mode records and reads everything.
        let frames = 2999_u32;
        let clip = build(&weights, Mode::Clip { frames }).expect("the clip pass");
        assert_eq!(clip.outputs.len(), 1);
        assert!(!clip.ops.is_empty());
        println!(
            "gemma4_audio Clip at {frames} frames ({} tokens): {} ops, {} arena elems = {:.1} MiB",
            tokens(frames),
            clip.ops.len(),
            clip.arena_elems,
            clip.arena_elems as f64 * 2.0 / (1024.0 * 1024.0)
        );
        assert_eq!(build(&weights, Mode::Trace { frames, layers: 6 }).expect("t").outputs.len(), 2);
        assert_eq!(build(&weights, Mode::Sscp { frames }).expect("s").outputs.len(), 2);
        assert_eq!(
            build(&weights, Mode::Trace { frames, layers: 0 }).expect("t0").outputs.len(),
            2
        );
    }

    /// The plan **executed** on a real device, not merely recorded.
    ///
    /// Ignored, and needs both `GEMMA4_AUDIO_MAML` and a Vulkan device. Run as:
    ///
    /// ```text
    /// GEMMA4_AUDIO_MAML=.../gemma4_audio.maml \
    ///   cargo test -p modelrunner --lib -- --include-ignored the_plan_runs_on_a_device
    /// ```
    ///
    /// Recording a plan proves the shapes and the tensor bindings agree. It does not prove the
    /// arena fits, that every op kind has a pipeline, that the dispatch sizes are legal, or that
    /// descriptor limits hold at 750 tokens over a 49.8 MiB arena and 695 ops. Those only fail
    /// when something submits.
    ///
    /// # This says the tower RUNS. It does not say the tower is CORRECT.
    ///
    /// Those are two different claims and the difference is the whole reason this test is cheap.
    /// `examples/check_gemma4_vision_parity.rs` runs the vision tower on a GPU **and compares it
    /// to the reference**, and its result is a number: 0.999123. This runs the audio tower on a
    /// GPU and checks the output is alive, and its result is that nothing crashed. Both are "end
    /// to end on device"; only one of them is evidence of correctness. A systematically wrong
    /// tower passes everything below.
    ///
    /// Numerical parity is a separate task and a separate harness. Do not read a pass here as
    /// standing in for it.
    ///
    /// # Three assertions, and each covers a hole the previous two leave
    ///
    /// * **finite** - NaN is non-zero, so a tower emitting NaN everywhere satisfies the liveness
    ///   count below and looks exactly like a healthy one.
    /// * **non-zero** - a plan whose shaders never ran reads back an untouched arena, which is
    ///   zeros, and every shape assertion still passes. The input is a ramp rather than silence
    ///   for the same reason: a zero input cannot tell a working pass from a dead one.
    /// * **a function of its input** - a pass that ignored its input and emitted a fixed pattern
    ///   of biases would be finite, entirely non-zero, and identical at every length. That is
    ///   not hypothetical: the peak here is 16.453 at both 250 and 750 tokens, which looks like
    ///   exactly that failure and is in fact the ramp's period repeating.
    #[test]
    #[ignore = "needs GEMMA4_AUDIO_MAML and a Vulkan device"]
    fn the_plan_runs_on_a_device() {
        let Ok(path) = std::env::var("GEMMA4_AUDIO_MAML") else {
            // Visible under --nocapture, because a silent skip reports as a pass in 0.00s and
            // that is indistinguishable from a device run in any summary line.
            println!("SKIPPED the_plan_runs_on_a_device: GEMMA4_AUDIO_MAML is not set");
            return;
        };
        let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::GEMMA4_AUDIO)
            .expect("a gemma4_audio .maml");
        let context = crate::vulkan::context::shared().expect("this host has no usable Vulkan device");

        // The hard case first: the 30-second cap, which is where the arena and the descriptor
        // limits are largest. A short clip would exercise neither.
        for frames in [crate::logmel::frame_count(MAX_SAMPLES) as u32, 999] {
            let seq = tokens(frames);
            let plan = build(&weights, Mode::Clip { frames }).expect("the clip pass");
            let ops = plan.ops.len();
            let arena = plan.arena_elems;
            let mut net =
                crate::vulkan::run::Net::new(context.clone(), plan, &weights, crate::preprocess::RESCALE_ONLY)
                    .unwrap_or_else(|e| panic!("{frames} frames records into a command buffer: {e}"));

            // A ramp rather than zeros: a silent input cannot distinguish a working pass from
            // one whose shaders wrote nothing, since both give zeros out.
            let mel: Vec<f32> = (0..frames as usize * MELS as usize)
                .map(|i| ((i % 257) as f32 / 257.0 - 0.5) * 4.0)
                .collect();
            let input = prepare(&mel, frames).expect("the spectrogram lays out");
            let out = net
                .infer_raw_many(&[&input])
                .unwrap_or_else(|e| panic!("{frames} frames submits and reads back: {e}"));

            assert_eq!(out.len(), 1, "Clip has one output");
            let got = &out[0];
            assert_eq!(got.len(), (OUT_DIM * seq) as usize, "[{OUT_DIM}, 1, {seq}]");
            assert!(got.iter().all(|v| v.is_finite()), "{frames} frames produced a NaN or an inf");
            let nonzero = got.iter().filter(|v| **v != 0.0).count();
            assert!(
                nonzero * 20 > got.len(),
                "{frames} frames: only {nonzero} of {} outputs are non-zero, which is what an \
                 arena nothing wrote to looks like",
                got.len()
            );
            let peak = got.iter().fold(0.0_f32, |m, v| m.max(v.abs()));
            println!(
                "gemma4_audio ran at {frames} frames ({seq} tokens): {ops} ops, \
                 {:.1} MiB arena, {nonzero}/{} non-zero, peak {peak:.3}",
                arena as f64 * 2.0 / (1024.0 * 1024.0),
                got.len()
            );

            // THE OUTPUT MUST BE A FUNCTION OF THE INPUT. Liveness is not enough: a pass that
            // ignored its input and emitted a fixed pattern of biases would be finite, entirely
            // non-zero, and identical at every length - which is exactly what the equal peaks
            // above look like. They are equal because the ramp has period 257, so both lengths
            // contain the same local windows and a 12-wide causal tower sees the same extremes.
            // That is the benign explanation, and this is the check that distinguishes it from
            // the malign one rather than leaving it as an argument.
            let other: Vec<f32> = (0..frames as usize * MELS as usize)
                .map(|i| ((i % 149) as f32 / 149.0 - 0.5) * 7.0)
                .collect();
            let second = net
                .infer_raw_many(&[&prepare(&other, frames).expect("the second spectrogram")])
                .unwrap_or_else(|e| panic!("{frames} frames submits a second input: {e}"));
            let differing =
                got.iter().zip(&second[0]).filter(|(a, b)| (*a - *b).abs() > 1e-4).count();
            assert!(
                differing * 2 > got.len(),
                "{frames} frames: only {differing} of {} outputs moved when the input changed, \
                 so the pass is not reading it",
                got.len()
            );
            assert!(second[0].iter().all(|v| v.is_finite()), "the second input produced a NaN");
        }
    }








}
