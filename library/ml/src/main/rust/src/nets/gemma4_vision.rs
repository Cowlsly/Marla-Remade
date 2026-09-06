//! Gemma 4's vision tower: 16 layers over image patches, out as soft tokens for the decoder.
//!
//! Restores the image input that `com.google.ai.edge.litertlm` used to provide. The decoder in
//! [`super::gemma4`] is text-only; this produces the `[n, 1536]` block that stands in for an
//! image in its prompt.
//!
//! # The layer is the decoder's, with three differences
//!
//! Same pre-norm, same q/k/v norms, same gated feed-forward. What differs:
//!
//! * **Multi-head, not multi-query.** Twelve heads and twelve KV heads, so no grouping.
//! * **Two-dimensional RoPE.** A patch has a row and a column, so a 64-wide head is two 32-wide
//!   blocks rotated by each - see [`super::Builder::rotary_axes`].
//! * **Clipped linears.** `use_clipped_linears` in the config, and 11 `Clip`s a layer in the
//!   export, each with its own calibrated bounds. They bound activations that would otherwise
//!   leave fp16's range; dropping them changes every number downstream.
//!
//! # What the host does
//!
//! The export takes `pixel_values [patches, 768]` - already patchified, 16x16x3 - and
//! `pixel_position_ids [patches, 2]`. So the patch extraction, the position ids and the two
//! learned position-embedding gathers (a `[10240, 768]` table) are host work, as the decoder's
//! embedding is. That is not a simplification made here: it is what the export's own inputs are.
//! [`prepare`] does all of it.
//!
//! # The grid follows the image, and the padding does not come with it
//!
//! `Gemma4ImageProcessor` resizes an image to at most `max_patches(budget)` patches preserving
//! its aspect ratio, rounding each side down to a multiple of `POOL * PATCH` pixels, and then
//! **pads** the patch sequence to that maximum so a batch stacks. The padding is why the export
//! carries a sentinel of `-1` in the position ids, an additive `-65504` attention mask, and a
//! pooling written as a masked matrix multiply with a `GatherND` after it.
//!
//! None of that is needed here. A plan is recorded per shape anyway, so this records one per
//! [`Grid`] and passes the real patches with nothing appended. Every pooled cell is then exactly
//! full, the mask is all ones, and the pooling is an ordinary average - see [`pool`]. The price
//! is re-recording when the aspect ratio changes, which against sixteen layers over a couple of
//! thousand patches does not register.
//!
//! # The tensor order is the contract
//!
//! As everywhere else in this tree, the `.maml` is an ordered table with no names, and
//! `maml_convert.collect_gemma4_vision` writes it in exactly the order [`declare_layer`] reads
//! it. The export's initializers are anonymised (`_to_copy_104`), so unlike the decoder the
//! converter cannot match by name and walks the graph in topological order instead - which makes
//! agreeing on this order more load-bearing here, not less.
use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Channels through the tower. `hidden_size` in `vision_config`.
pub const D_MODEL: u32 = 768;

/// Attention heads. Ordinary multi-head: `num_key_value_heads` is the same.
pub const HEADS: u32 = 12;

/// Channels per head, which the 2-D rotary splits into two blocks of 32.
pub const HEAD_DIM: u32 = 64;

/// Blocks a head's rotary is split into: one for the patch row, one for its column.
pub const ROPE_AXES: u32 = 2;

/// Feed-forward width. `intermediate_size`.
pub const FFN: u32 = 3072;

/// Transformer layers. `num_hidden_layers`.
pub const LAYERS: usize = 16;

/// Patch side, in pixels. A patch is `16 * 16 * 3 = 768` values, which is why [`D_MODEL`] and the
/// patch projection's input width coincide - a coincidence, not a constraint.
pub const PATCH: u32 = 16;

/// The epsilon in every RMS norm. `rms_norm_eps`.
pub const EPSILON: f32 = 1e-6;

/// Whether `q_norm` and `k_norm`'s gammas already carry the attention scale.
///
/// They do, and this is a contract note rather than a switch: [`layer`] realises it by calling
/// [`Builder::attn_scores_prescaled`]. The export has no `Mul` between `q_proj` and the score
/// matmul - the path is `q_proj -> Clip -> q_norm -> rotary -> MatMul` - so there is no
/// `1 / sqrt(64)` to reproduce. The gammas are uniform scalars that differ per layer
/// (0.406, 0.355, 0.381 ...) and whose `q * k` product is 0.500 in every one of the sixteen, so
/// the scaling is trained rather than derived.
///
/// The same trap as the decoder's `gemma4::Q_NORM_CARRIES_SCALE`, and it fails the same silent
/// way: eight times too small, no shape error, every layout test green.
pub const SCALE_IS_IN_THE_NORMS: bool = true;

/// What the decoder reads: `text_config.hidden_size`.
pub const OUT_DIM: u32 = 1536;

/// Rows in the learned position table, gathered on the host. `position_embedding_size`.
pub const POSITIONS: u32 = 10240;

/// Patches averaged together per axis by the pooling. `pooling_kernel_size`.
pub const POOL: u32 = 3;

/// Soft-token budgets `Gemma4ImageProcessor` accepts, smallest first.
///
/// The budget decides everything downstream: an image is resized to at most `budget * POOL *
/// POOL` patches, and the tower's cost is quadratic in that. It is a genuine choice rather than a
/// constant because the top of this range does not fit on a phone - see [`Grid::for_image`].
pub const SOFT_TOKEN_BUDGETS: [u32; 5] = [70, 140, 280, 560, 1120];

/// The budget the reference processor defaults to, and what parity is measured at.
///
/// `default_output_length` in `vision_config`, and the top-level `vision_soft_tokens_per_image`.
/// At this budget a square image is 48x48 patches, whose two `[12, 2304, 2304]` score maps alone
/// are 254 MB of arena. [`SOFT_TOKEN_BUDGETS`]`[0]` is 64 soft tokens and 16 MB.
pub const DEFAULT_SOFT_TOKENS: u32 = 280;

/// The most patches an image is resized down to at `soft_tokens`.
pub const fn max_patches(soft_tokens: u32) -> u32 {
    soft_tokens * POOL * POOL
}

/// The base of the two-dimensional rotary. `vision_config.rope_parameters.rope_theta`.
pub const ROPE_THETA: f32 = 100.0;

/// The side of the block an image is resized to a multiple of, in pixels.
///
/// `POOL * PATCH`. Both axes are rounded down to a multiple of this so that the patch grid
/// divides by [`POOL`] exactly - which is what lets the pooling be an ordinary average over a
/// grid rather than the export's masked matrix. See [`Grid::for_image`].
pub const SIDE_MULTIPLE: u32 = POOL * PATCH;

/// Tensors one clip contributes: a `[2]` fp16 pair, minimum first.
const CLIP_TENSORS: usize = 1;

/// Clips in one layer. Counted off the export, not guessed - see the module docs.
const CLIPS_PER_LAYER: usize = 11;

/// Quantised projections in one layer: q, k, v, o, gate, up, down.
const PROJECTIONS_PER_LAYER: usize = 7;

/// Tensors one quantised projection contributes: kernel, per-block scale, bias.
const PROJECTION_TENSORS: usize = 3;

/// Tensors one **unquantised** projection contributes: kernel and bias, as [`Builder::conv`] reads
/// them. See [`PATCH_PROJECTION`].
const DENSE_TENSORS: usize = 2;

/// Unquantised tensors in one layer: four `d_model` norms and three `head_dim` ones.
const PLAIN_PER_LAYER: usize = 7;

/// Tensors one layer contributes, in file order.
const LAYER_TENSORS: usize = PLAIN_PER_LAYER
    + CLIPS_PER_LAYER * CLIP_TENSORS
    + PROJECTIONS_PER_LAYER * PROJECTION_TENSORS;

/// The patch projection, `[768, 768]` in the export, held at **fp16**.
///
/// # Why the two ends are not quantised
///
/// Everything between them is int4, which costs the tower far more than it costs the decoder:
/// sixteen layers turn a per-layer cosine of 0.9999 into 0.946 at the output. Measuring where
/// that comes from puts a disproportionate share at the two ends rather than spread evenly -
/// the patch projection is already at 0.9952 before layer 0 has run, and the output projection
/// alone takes the pooled state from 0.9747 to 0.9481.
///
/// Both are small: `768 x 768` and `1536 x 768` against sixteen layers of `4 x 768 x 768` plus
/// `3 x 768 x 3072`. Holding the pair at fp16 costs about 2.7 MB on a 95 MB file and removes the
/// error at the point where it has the whole tower left to be amplified through, and at the point
/// where nothing downstream can average it away.
pub const PATCH_PROJECTION: usize = 0;

/// The trailing norm before the output projection. Its gamma is all ones in the export, which is
/// not a no-op: an RMS norm still divides by the RMS.
pub const FINAL_NORM: usize = PATCH_PROJECTION + DENSE_TENSORS;

/// The projection to the decoder's width, `[1536, 768]`, held at **fp16**. See
/// [`PATCH_PROJECTION`].
pub const OUT_PROJECTION: usize = FINAL_NORM + 1;

/// The learned position table for a patch's **column**, `[10240, 768]`, read a row at a time.
///
/// Host-read for the same reason the decoder's embedding is: a pass needs a handful of rows out
/// of ten thousand, and binding the whole table to gather them would be absurd. Stored as an int4
/// triple like every other large tensor here.
///
/// Column and not row: `pixel_position_ids` is built by the reference preprocessor as
/// `meshgrid(arange(width), arange(height), indexing="xy")`, so component **0 is the column**,
/// and this is the table the export gathers with component 0. Getting the pair the wrong way
/// round transposes every image without changing a single shape.
pub const COLUMN_POSITIONS: usize = OUT_PROJECTION + DENSE_TENSORS;

/// The learned position table for a patch's **row**, `[10240, 768]`. See [`COLUMN_POSITIONS`].
pub const ROW_POSITIONS: usize = COLUMN_POSITIONS + PROJECTION_TENSORS;

/// Tensors before any layer.
const SHARED_TENSORS: usize = ROW_POSITIONS + PROJECTION_TENSORS;

/// Where the layers start.
const LAYER0: usize = SHARED_TENSORS;

/// Total tensors the `.maml` holds, and the count `maml_convert.py` must write.
pub const TENSORS: usize = SHARED_TENSORS + LAYERS * LAYER_TENSORS;

/// The first tensor of layer `index`.
pub fn layer_at(index: usize) -> usize {
    LAYER0 + index * LAYER_TENSORS
}

/// Declare every tensor of layer `index` against `weights`, in file order.
///
/// The order is the export's own topological order, which is what the converter walks. Reading
/// the clips as part of the sequence rather than collecting them separately is deliberate: it is
/// the only thing that keeps the two sides in step when the names carry no information.
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

    plain(&[D_MODEL], &mut next)?; // pre-attention norm
    clip(&mut next)?;
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // q_proj
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // k_proj
    projection(weights, &mut next, HEADS * HEAD_DIM, D_MODEL)?; // v_proj
    clip(&mut next)?;
    clip(&mut next)?;
    clip(&mut next)?;
    plain(&[HEAD_DIM], &mut next)?; // q_norm
    plain(&[HEAD_DIM], &mut next)?; // k_norm
    plain(&[HEAD_DIM], &mut next)?; // v_norm
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, HEADS * HEAD_DIM)?; // o_proj
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // post-attention norm
    plain(&[D_MODEL], &mut next)?; // pre-feed-forward norm
    clip(&mut next)?;
    projection(weights, &mut next, FFN, D_MODEL)?; // gate
    projection(weights, &mut next, FFN, D_MODEL)?; // up
    clip(&mut next)?;
    clip(&mut next)?;
    clip(&mut next)?;
    projection(weights, &mut next, D_MODEL, FFN)?; // down
    clip(&mut next)?;
    plain(&[D_MODEL], &mut next)?; // post-feed-forward norm
    if next != layer_at(index + 1) {
        return Err(format!(
            "vision layer {index} declared {} tensors, not the {} its span allows",
            next - at,
            layer_at(index + 1) - at
        ));
    }
    Ok(())
}

/// One quantised `1x1` projection: kernel, per-block scale, bias.
///
/// int4 like the decoder's, and for the same reason - the tower is 337 MB at fp16 and 99 MB at
/// four bits, on top of a download that is already large. The two projections at the ends are the
/// exception; see [`PATCH_PROJECTION`].
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

/// One **unquantised** `1x1` projection: kernel and bias, the pair [`Builder::conv`] reads.
fn dense(
    weights: &dyn WeightSource,
    next: &mut usize,
    out: u32,
    inp: u32,
) -> Result<(), String> {
    weights.shaped(*next, &[out, inp, 1, 1])?;
    weights.shaped(*next + 1, &[out])?;
    *next += DENSE_TENSORS;
    Ok(())
}

/// Declare the tensors that sit outside any layer, in file order.
pub fn declare_shared(weights: &dyn WeightSource) -> Result<(), String> {
    let mut next = PATCH_PROJECTION;
    dense(weights, &mut next, D_MODEL, D_MODEL)?;
    weights.shaped(next, &[D_MODEL])?;
    next += 1;
    dense(weights, &mut next, OUT_DIM, D_MODEL)?;
    for _ in 0..2 {
        weights.shaped_words(next, &[POSITIONS, D_MODEL, 1, 1])?;
        weights.shaped(next + 1, &[POSITIONS, D_MODEL.div_ceil(crate::weights::I4_BLOCK)])?;
        weights.shaped(next + 2, &[POSITIONS])?;
        next += PROJECTION_TENSORS;
    }
    if next != SHARED_TENSORS {
        return Err(format!("{next} shared tensors, not {SHARED_TENSORS}"));
    }
    Ok(())
}

/// The `[in, out]` shape the export holds for a kernel declared here as `dims`.
pub fn as_exported(dims: &[u32]) -> Vec<u32> {
    match dims {
        [out, inp, 1, 1] => vec![*inp, *out],
        other => other.to_vec(),
    }
}

/// The patch grid one image is resized to, in patches.
///
/// Both extents are multiples of [`POOL`], which is what the reference preprocessor guarantees by
/// rounding the pixel dimensions down to a multiple of [`SIDE_MULTIPLE`]. That guarantee is the
/// whole reason the pooling below is an ordinary average: with it, every pooled cell holds
/// exactly `POOL * POOL` patches and there is nothing to mask.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Grid {
    /// Patch rows, a multiple of [`POOL`].
    pub rows: u32,
    /// Patch columns, a multiple of [`POOL`].
    pub cols: u32,
}

impl Grid {
    /// The grid `Gemma4ImageProcessor` resizes a `width x height` image to at `soft_tokens`.
    ///
    /// Ported from `get_aspect_ratio_preserving_size`: scale so the area is at most
    /// `max_patches(soft_tokens)` patches, then round each side **down** to a multiple of
    /// [`SIDE_MULTIPLE`]. Rounding down rather than to nearest is what keeps the result inside
    /// the budget; the two fallbacks cover a sliver so extreme that one side rounds away.
    ///
    /// `soft_tokens` must be one of [`SOFT_TOKEN_BUDGETS`]. It is an argument rather than a
    /// constant because the cost is quadratic in the patch count and the reference default does
    /// not fit in a phone's budget - see [`DEFAULT_SOFT_TOKENS`].
    pub fn for_image(width: u32, height: u32, soft_tokens: u32) -> Result<Grid, String> {
        if !SOFT_TOKEN_BUDGETS.contains(&soft_tokens) {
            return Err(format!(
                "a budget of {soft_tokens} soft tokens, not one of {SOFT_TOKEN_BUDGETS:?}"
            ));
        }
        if width == 0 || height == 0 {
            return Err(format!("an image of {width}x{height}"));
        }
        let (w, h) = (f64::from(width), f64::from(height));
        let budget = f64::from(max_patches(soft_tokens)) * f64::from(PATCH) * f64::from(PATCH);
        let factor = (budget / (w * h)).sqrt();
        let side = f64::from(SIDE_MULTIPLE);
        let mut target_w = (factor * w / side).floor() as u32 * SIDE_MULTIPLE;
        let mut target_h = (factor * h / side).floor() as u32 * SIDE_MULTIPLE;
        // A sliver: one side is so much longer that the other rounds to nothing. Give the short
        // side one block and cap the long one, as the reference does.
        let longest = soft_tokens * SIDE_MULTIPLE;
        if target_h == 0 && target_w == 0 {
            return Err(format!("{width}x{height} resizes to nothing"));
        } else if target_h == 0 {
            target_h = SIDE_MULTIPLE;
            target_w = ((w / h).floor() as u32 * SIDE_MULTIPLE).max(SIDE_MULTIPLE).min(longest);
        } else if target_w == 0 {
            target_w = SIDE_MULTIPLE;
            target_h = ((h / w).floor() as u32 * SIDE_MULTIPLE).max(SIDE_MULTIPLE).min(longest);
        }
        let grid = Grid::new(target_h / PATCH, target_w / PATCH)?;
        if grid.soft_tokens() > soft_tokens {
            return Err(format!(
                "{width}x{height} resolved to {} soft tokens, past the {soft_tokens} asked for",
                grid.soft_tokens()
            ));
        }
        Ok(grid)
    }

    /// A grid of `rows x cols` patches, refusing one the pooling could not tile.
    pub fn new(rows: u32, cols: u32) -> Result<Grid, String> {
        let grid = Grid { rows, cols };
        if rows == 0 || cols == 0 {
            return Err(format!("a patch grid of {rows}x{cols}"));
        }
        if !rows.is_multiple_of(POOL) || !cols.is_multiple_of(POOL) {
            return Err(format!(
                "a {rows}x{cols} patch grid does not divide by the pooling kernel {POOL}, so a \
                 pooled cell would straddle the edge"
            ));
        }
        let ceiling = max_patches(SOFT_TOKEN_BUDGETS[SOFT_TOKEN_BUDGETS.len() - 1]);
        if grid.patches() > ceiling {
            return Err(format!(
                "{rows}x{cols} is {} patches, past the {ceiling} the largest budget allows",
                grid.patches()
            ));
        }
        Ok(grid)
    }

    /// Patches in the grid, and so the tower's sequence length.
    pub const fn patches(&self) -> u32 {
        self.rows * self.cols
    }

    /// Soft tokens the tower emits for this grid, one per pooled cell.
    pub const fn soft_tokens(&self) -> u32 {
        (self.rows / POOL) * (self.cols / POOL)
    }

    /// The `(width, height)` in pixels an image must be resized to to produce this grid.
    pub const fn pixels(&self) -> (u32, u32) {
        (self.cols * PATCH, self.rows * PATCH)
    }
}

/// Which pass [`build`] emits.
///
/// The grid is part of the key because a plan is recorded at one shape and the grid follows the
/// image's aspect ratio. [`crate::vulkan::Reshaped`] re-records when the key changes, which for a
/// tower this size is far cheaper than the pass itself.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// One image: a grid of patches in, [`Grid::soft_tokens`] soft tokens out.
    Image(Grid),
    /// Stop after `layers` layers and hand back the hidden state, for parity bisection.
    ///
    /// The whole-tower comparison in `examples/check_gemma4_vision_parity.rs` can only say that
    /// the answer moved; this says where. `layers: 0` stops before layer 0, so it reports the
    /// patch projection and the position embedding on their own.
    ///
    /// Outputs are `[hidden, pooled]`: the state after `layers` layers, and [`pool`] applied to
    /// it. Pooling a partial state is meaningless as a value but exact as a check, and having
    /// both means a disagreement can be placed either side of the pooling.
    Trace { grid: Grid, layers: usize },
}

impl Mode {
    /// The patch grid this pass is recorded for.
    pub const fn grid(self) -> Grid {
        match self {
            Mode::Image(grid) | Mode::Trace { grid, .. } => grid,
        }
    }
}

/// Plan inputs, in declaration order. `T` is [`Grid::patches`].
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[768, 1, T]` | the patches, normalised to `(p - 0.5) * 2` on the host |
/// | 1 | `[768, 1, T]` | `column_table[col] + row_table[row]`, gathered on the host |
/// | 2 | `[64, 1, T]` | rotary angles: cos/sin for the column, then for the row |
///
/// The position embedding is a separate input rather than folded into the patches because the
/// export adds it **after** the patch projection, not before - summing them on the host would put
/// it through a matrix it was never meant to see.
///
/// All three are built by [`prepare`].
pub const INPUTS: usize = 3;

/// Build the image pass.
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    let grid = mode.grid();
    let stop_after = match mode {
        Mode::Image(_) => LAYERS,
        Mode::Trace { layers, .. } if layers <= LAYERS => layers,
        Mode::Trace { layers, .. } => {
            return Err(format!("a trace of {layers} of {LAYERS} layers"));
        }
    };
    let patches = Grid::new(grid.rows, grid.cols)?.patches();
    let mut builder = Builder::new(weights);
    let b = &mut builder;

    let values = b.input(Shape::new(D_MODEL, 1, patches));
    let positions = b.input(Shape::new(D_MODEL, 1, patches));
    let angles = b.input(Shape::new(HEAD_DIM, 1, patches));

    // The two position tables are gathered on the host and arrive summed, as `positions`.
    for at in [COLUMN_POSITIONS, ROW_POSITIONS] {
        b.host_tensor(at, &[POSITIONS, D_MODEL, 1, 1]);
        b.host_tensor(at + 1, &[POSITIONS, D_MODEL.div_ceil(crate::weights::I4_BLOCK)]);
        b.host_tensor(at + 2, &[POSITIONS]);
    }

    // The patch projection, then the position embedding. Its input is `16 * 16 * 3`, which
    // happens to equal `d_model`. Unquantised - see `PATCH_PROJECTION`.
    let projected = dense_point(b, PATCH_PROJECTION, values, D_MODEL);
    let mut x = b.add(projected, positions);

    for index in 0..stop_after {
        x = layer(b, index, x, angles)?;
    }

    if let Mode::Trace { .. } = mode {
        for index in stop_after..LAYERS {
            declare_layer(weights, index)?;
            name_layer(b, index);
        }
        b.host_tensor(FINAL_NORM, &[D_MODEL]);
        b.host_tensor(OUT_PROJECTION, &[OUT_DIM, D_MODEL, 1, 1]);
        b.host_tensor(OUT_PROJECTION + 1, &[OUT_DIM]);
        // Both sides of the pooling, so a tower that agrees and an output that does not can be
        // told apart from a tower that never agreed.
        let pooled = pool(b, x, grid);
        return builder.finish(&[x, pooled]);
    }

    let pooled = pool(b, x, grid);
    let normed = b.rms_norm(pooled, FINAL_NORM, EPSILON);
    let out = dense_point(b, OUT_PROJECTION, normed, OUT_DIM);
    builder.finish(&[out])
}

/// Average each `POOL x POOL` block of patches into one soft token.
///
/// The export does this as a matrix multiply against a one-hot matrix it builds from the position
/// ids, divided by `POOL * POOL`, followed by a `GatherND` that drops the cells no patch landed
/// in. Both of those exist to cope with the padding it applies to reach a fixed patch count. This
/// runtime records a plan per grid instead of padding, so every cell is full and the whole thing
/// collapses to an average pool.
///
/// The two reshapes are free. A sequence is `[c, 1, T]` and a patch at `(row, col)` sits at
/// `T`-index `row * cols + col`, which is exactly the layout of a `[c, rows, cols]` map - the
/// same bytes, relabelled.
fn pool(b: &mut Builder, x: Id, grid: Grid) -> Id {
    let map = b.reshaped(x, Shape::new(D_MODEL, grid.rows, grid.cols));
    let pooled = b.avg_pool(map, (POOL, POOL), (POOL, POOL));
    let sequence = b.reshaped(pooled, Shape::new(D_MODEL, 1, grid.soft_tokens()));
    // The export scales by `sqrt(d_model)` here. An RMS norm follows immediately and is scale
    // invariant, so this changes nothing mathematically - it is kept because it is what keeps the
    // values in fp16's range on the way in, which is presumably why the export has it.
    b.affine(sequence, f64::from(D_MODEL).sqrt() as f32, 0.0)
}

/// Declare every tensor of a layer [`build`] skipped, so [`Builder::finish`] still sees them read.
///
/// A [`Mode::Trace`] stops early on purpose, and an unread tensor is otherwise exactly what a
/// forward pass that lost a layer looks like from the outside.
fn name_layer(b: &mut Builder, index: usize) {
    let at = layer_at(index);
    let mut next = at;
    let plain = |b: &mut Builder, n: &mut usize, dims: &[u32]| {
        b.host_tensor(*n, dims);
        *n += 1;
    };
    let proj = |b: &mut Builder, n: &mut usize, out: u32, inp: u32| {
        b.host_tensor(*n, &[out, inp, 1, 1]);
        b.host_tensor(*n + 1, &[out, inp.div_ceil(crate::weights::I4_BLOCK)]);
        b.host_tensor(*n + 2, &[out]);
        *n += PROJECTION_TENSORS;
    };

    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[2]);
    for _ in 0..3 {
        proj(b, &mut next, HEADS * HEAD_DIM, D_MODEL);
    }
    for _ in 0..3 {
        plain(b, &mut next, &[2]);
    }
    for _ in 0..3 {
        plain(b, &mut next, &[HEAD_DIM]);
    }
    plain(b, &mut next, &[2]);
    proj(b, &mut next, D_MODEL, HEADS * HEAD_DIM);
    plain(b, &mut next, &[2]);
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[D_MODEL]);
    plain(b, &mut next, &[2]);
    proj(b, &mut next, FFN, D_MODEL);
    proj(b, &mut next, FFN, D_MODEL);
    for _ in 0..3 {
        plain(b, &mut next, &[2]);
    }
    proj(b, &mut next, D_MODEL, FFN);
    plain(b, &mut next, &[2]);
    plain(b, &mut next, &[D_MODEL]);
    debug_assert_eq!(next, layer_at(index + 1), "named {} tensors", next - at);
}

/// A `1 x 1` int4 convolution, which every projection inside a layer is.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv_int4(x, at, out, Act::None)
}

/// A `1 x 1` **fp16** convolution, which the two projections at the ends are.
///
/// Spelled out rather than routed through `conv_same` so the stride, dilation and padding are
/// visible: this is a projection over positions, not a convolution over a map.
fn dense_point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv(x, at, out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None)
}

/// One encoder layer.
fn layer(b: &mut Builder, index: usize, x: Id, angles: Id) -> Result<Id, String> {
    let at = layer_at(index);
    let mut next = at;
    let plain = |n: &mut usize| {
        let here = *n;
        *n += 1;
        here
    };
    let clip = |n: &mut usize| {
        let here = *n;
        *n += CLIP_TENSORS;
        here
    };
    let proj = |n: &mut usize| {
        let here = *n;
        *n += PROJECTION_TENSORS;
        here
    };

    let pre_attn = plain(&mut next);
    let clip_in = clip(&mut next);
    let q_proj = proj(&mut next);
    let k_proj = proj(&mut next);
    let v_proj = proj(&mut next);
    let clip_q = clip(&mut next);
    let clip_k = clip(&mut next);
    let clip_v = clip(&mut next);
    let q_norm = plain(&mut next);
    let k_norm = plain(&mut next);
    let v_norm = plain(&mut next);
    let clip_mixed = clip(&mut next);
    let o_proj = proj(&mut next);
    let clip_o = clip(&mut next);
    let post_attn = plain(&mut next);
    let pre_ff = plain(&mut next);
    let clip_ff_in = clip(&mut next);
    let gate_proj = proj(&mut next);
    let up_proj = proj(&mut next);
    let clip_gate = clip(&mut next);
    let clip_up = clip(&mut next);
    let clip_gated = clip(&mut next);
    let down_proj = proj(&mut next);
    let clip_down = clip(&mut next);
    let post_ff = plain(&mut next);
    if next != layer_at(index + 1) {
        return Err(format!("vision layer {index} read {} tensors", next - at));
    }

    let normed = b.rms_norm(x, pre_attn, EPSILON);
    let normed = b.clamp(normed, clip_in);
    let q = point(b, q_proj, normed, HEADS * HEAD_DIM);
    let q = b.clamp(q, clip_q);
    let k = point(b, k_proj, normed, HEADS * HEAD_DIM);
    let k = b.clamp(k, clip_k);
    let v = point(b, v_proj, normed, HEADS * HEAD_DIM);
    let v = b.clamp(v, clip_v);
    // Per head against a `head_dim`-long gamma, as the decoder does.
    let q = b.rms_norm_grouped(q, q_norm, EPSILON, HEADS);
    let k = b.rms_norm_grouped(k, k_norm, EPSILON, HEADS);
    let v = b.rms_norm_grouped(v, v_norm, EPSILON, HEADS);
    // Two blocks per head: the patch's row rotates the first 32 channels, its column the rest.
    let q = b.rotary_axes(q, angles, HEADS, ROPE_AXES);
    let k = b.rotary_axes(k, angles, HEADS, ROPE_AXES);

    // No scale: the export goes straight from the rotary into the score matmul, and the
    // uniform q_norm and k_norm gammas carry it instead. See `SCALE_IS_IN_THE_NORMS`.
    let scores = b.attn_scores_prescaled(q, k, HEADS);
    let probs = b.softmax(scores);
    let mixed = b.attn_apply(probs, v, HEADS);
    let mixed = b.clamp(mixed, clip_mixed);
    let attended = point(b, o_proj, mixed, D_MODEL);
    let attended = b.clamp(attended, clip_o);
    let attended = b.rms_norm(attended, post_attn, EPSILON);
    let x = b.add(x, attended);

    let ff_in = b.rms_norm(x, pre_ff, EPSILON);
    let ff_in = b.clamp(ff_in, clip_ff_in);
    let gate = point(b, gate_proj, ff_in, FFN);
    let gate = b.clamp(gate, clip_gate);
    let up = point(b, up_proj, ff_in, FFN);
    let up = b.clamp(up, clip_up);
    let gate = b.activate(gate, Act::Gelu);
    let gated = b.mul(gate, up);
    let gated = b.clamp(gated, clip_gated);
    let ff = point(b, down_proj, gated, D_MODEL);
    let ff = b.clamp(ff, clip_down);
    let ff = b.rms_norm(ff, post_ff, EPSILON);
    Ok(b.add(x, ff))
}

/// The inverse frequencies of the two-dimensional rotary: `ROPE_THETA^(-i / half)`.
///
/// `half` is a quarter of [`HEAD_DIM`], because a head is [`ROPE_AXES`] blocks and each block
/// rotates half as many 2-planes as it has channels. For the shipped configuration that is 16
/// frequencies, and they come out as the powers of `0.75` the export holds as a constant.
pub fn inv_freq() -> Vec<f32> {
    let half = HEAD_DIM / ROPE_AXES / 2;
    (0..half)
        .map(|i| f64::from(ROPE_THETA).powf(-f64::from(i) / f64::from(half)) as f32)
        .collect()
}

/// The normalised patches, plan input 0: `[768, 1, T]`.
///
/// `pixels` is `width x height` ARGB_8888 in row-major order, already resized to
/// [`Grid::pixels`]. A patch's 768 values run `y`, then `x`, then channel - HWC inside the patch,
/// not CHW - and `p / 255` then `(p - 0.5) * 2` takes a byte to `[-1, 1]`. The export does the
/// second half itself; doing both here saves a pass over the arena.
pub fn patchify(grid: Grid, pixels: &[i32]) -> Result<Vec<f32>, String> {
    let (width, height) = grid.pixels();
    let wanted = width as usize * height as usize;
    if pixels.len() != wanted {
        return Err(format!(
            "{} pixels for a {width}x{height} image, not {wanted}",
            pixels.len()
        ));
    }
    let patches = grid.patches() as usize;
    let patch = PATCH as usize;
    let cols = grid.cols as usize;
    // `[768, 1, T]`, so a patch is a strided column and its 768 values are `T` apart.
    let mut values = vec![0.0; D_MODEL as usize * patches];
    for y in 0..height as usize {
        for x in 0..width as usize {
            let argb = pixels[y * width as usize + x];
            let at = ((y % patch) * patch + (x % patch)) * 3;
            let column = (y / patch) * cols + (x / patch);
            for (channel, shift) in [16, 8, 0].into_iter().enumerate() {
                let byte = f32::from(((argb >> shift) & 0xff) as u8);
                values[(at + channel) * patches + column] = (byte / 255.0 - 0.5) * 2.0;
            }
        }
    }
    Ok(values)
}

/// The rotary angles, plan input 2: `[64, 1, T]`.
///
/// Block 0 of a head is rotated by the patch's column and block 1 by its row, each written as
/// `half` cosines followed by `half` sines - the layout [`Builder::rotary_axes`] reads.
pub fn rotary_angles(grid: Grid) -> Vec<f32> {
    let patches = grid.patches() as usize;
    let frequencies = inv_freq();
    let half = frequencies.len();
    let mut angles = vec![0.0; HEAD_DIM as usize * patches];
    for row in 0..grid.rows as usize {
        for column in 0..grid.cols as usize {
            let at = row * grid.cols as usize + column;
            for (block, coordinate) in [column, row].into_iter().enumerate() {
                for (i, frequency) in frequencies.iter().enumerate() {
                    let theta = coordinate as f32 * frequency;
                    let base = block * 2 * half;
                    angles[(base + i) * patches + at] = theta.cos();
                    angles[(base + half + i) * patches + at] = theta.sin();
                }
            }
        }
    }
    angles
}

/// The three plan inputs for one image, in [`INPUTS`] order.
///
/// [`patchify`] and [`rotary_angles`] either side of the position gather, which is the only part
/// that needs the weights: `column_table[col] + row_table[row]`, summed because the export adds
/// them after the patch projection.
pub fn prepare(
    weights: &crate::weights::Reader<'_>,
    grid: Grid,
    pixels: &[i32],
) -> Result<[Vec<f32>; INPUTS], String> {
    let values = patchify(grid, pixels)?;
    let patches = grid.patches() as usize;

    // One row of each table per distinct coordinate, not per patch: a 48x48 grid is 2304 patches
    // but only 96 rows, and each row is a 768-wide int4 dequantisation.
    let table =
        |at: usize, index: u32| weights.int4_row(at, at + 1, &[POSITIONS, D_MODEL, 1, 1], index);
    let by_column = (0..grid.cols)
        .map(|c| table(COLUMN_POSITIONS, c))
        .collect::<Result<Vec<_>, _>>()?;
    let by_row = (0..grid.rows)
        .map(|r| table(ROW_POSITIONS, r))
        .collect::<Result<Vec<_>, _>>()?;

    let mut positions = vec![0.0; D_MODEL as usize * patches];
    for row in 0..grid.rows as usize {
        for column in 0..grid.cols as usize {
            let at = row * grid.cols as usize + column;
            for channel in 0..D_MODEL as usize {
                positions[channel * patches + at] =
                    by_column[column][channel] + by_row[row][channel];
            }
        }
    }
    Ok([values, positions, rotary_angles(grid)])
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
    }

    #[test]
    fn the_two_ends_are_not_quantised() {
        // The mixed precision is the contract with `collect_gemma4_vision`, and a converter that
        // wrote three tensors here instead of two would shift every index after it.
        assert_eq!(FINAL_NORM, PATCH_PROJECTION + 2);
        assert_eq!(COLUMN_POSITIONS, OUT_PROJECTION + 2);
        assert_eq!(SHARED_TENSORS, 11);
        assert_eq!(TENSORS, SHARED_TENSORS + LAYERS * LAYER_TENSORS);
    }

    #[test]
    fn the_parameter_total_is_within_reach_of_the_published_size() {
        // 16 layers of (4 * 768 * 768 attention + 3 * 768 * 3072 feed-forward), plus the patch
        // and output projections. The tower is ~300M parameters, which at fp16 is the 337 MB the
        // export weighs - the check is that this layout accounts for essentially all of it.
        let per_layer = 4 * D_MODEL * D_MODEL + 3 * D_MODEL * FFN;
        let total = LAYERS as u32 * per_layer + D_MODEL * D_MODEL + OUT_DIM * D_MODEL;
        let megabytes = f64::from(total) * 2.0 / 1e6;
        assert!(
            (300.0..350.0).contains(&megabytes),
            "{megabytes:.0} MB against the export's 337 MB"
        );
    }

    #[test]
    fn the_head_splits_into_two_rotary_blocks() {
        // The one thing about this tower that is not the decoder's shape.
        assert_eq!(HEAD_DIM % ROPE_AXES, 0);
        assert_eq!((HEAD_DIM / ROPE_AXES) % 2, 0, "each block still rotates 2-planes");
        assert_eq!(HEADS * HEAD_DIM, D_MODEL, "multi-head, so q is the full width");
    }

    #[test]
    fn the_inverse_frequencies_are_the_constant_the_export_holds() {
        // The export folds these into a `[1, 16, 1]` initializer whose values are the powers of
        // 0.75. That is `100^(-i/16)` to four figures, and this is the check that the theta and
        // the block width in this file reproduce it rather than merely being plausible.
        let got = inv_freq();
        assert_eq!(got.len(), 16);
        for (i, value) in got.iter().enumerate() {
            let want = 0.75_f64.powi(i as i32);
            assert!(
                (f64::from(*value) - want).abs() < 2e-3,
                "frequency {i} is {value}, not {want}"
            );
        }
    }

    #[test]
    fn a_square_image_resizes_to_the_grid_the_reference_processor_picks() {
        // 2520 patches of 16 pixels is 645120 pixels, whose square side is 803.2; rounded down to
        // a multiple of 48 that is 768, which is 48 patches and so 16x16 = 256 soft tokens.
        let grid = Grid::for_image(1000, 1000, DEFAULT_SOFT_TOKENS).expect("a square image");
        assert_eq!((grid.rows, grid.cols), (48, 48));
        assert_eq!(grid.pixels(), (768, 768));
        assert_eq!(grid.soft_tokens(), 256);
    }

    #[test]
    fn every_grid_stays_inside_its_budget_and_divides_by_the_pooling_kernel() {
        // The budget is the reason the resize rounds down, and the divisibility is what lets
        // `pool` be an average rather than the export's masked matrix. Both hold or neither the
        // plan nor the pooling is valid, so sweep a range of shapes rather than trusting one.
        for soft_tokens in SOFT_TOKEN_BUDGETS {
            for width in [1_u32, 17, 64, 640, 1920, 4000] {
                for height in [1_u32, 17, 64, 480, 1080, 4000] {
                    let grid = Grid::for_image(width, height, soft_tokens)
                        .unwrap_or_else(|e| panic!("{width}x{height} at {soft_tokens}: {e}"));
                    assert!(
                        grid.patches() <= max_patches(soft_tokens),
                        "{width}x{height} at {soft_tokens} gives {} patches",
                        grid.patches()
                    );
                    assert!(grid.soft_tokens() <= soft_tokens, "{width}x{height}");
                    assert_eq!(grid.rows % POOL, 0, "{width}x{height} rows");
                    assert_eq!(grid.cols % POOL, 0, "{width}x{height} columns");
                    // Every coordinate has to index the learned position table.
                    assert!(grid.rows <= POSITIONS && grid.cols <= POSITIONS);
                }
            }
        }
        assert!(Grid::for_image(64, 64, 99).is_err(), "an unsupported budget");
    }

    #[test]
    fn the_aspect_ratio_survives_the_resize() {
        // Rounding to a multiple of 48 pixels cannot preserve a ratio exactly, but it must not
        // transpose or squash one: a 2:1 image stays about 2:1.
        let grid = Grid::for_image(2000, 1000, DEFAULT_SOFT_TOKENS).expect("a wide image");
        let ratio = f64::from(grid.cols) / f64::from(grid.rows);
        assert!((ratio - 2.0).abs() < 0.15, "a 2:1 image became {ratio:.2}:1");
        assert!(grid.cols > grid.rows, "a wide image must stay wide");
    }

    #[test]
    fn a_grid_the_pooling_cannot_tile_is_refused() {
        assert!(Grid::new(16, 16).is_err(), "16 does not divide by 3");
        assert!(Grid::new(0, 3).is_err(), "an empty grid");
        assert!(Grid::new(3000, 3000).is_err(), "past the largest budget");
        assert!(Grid::new(48, 48).is_ok());
    }

    #[test]
    fn patchify_lays_a_pixel_out_where_the_export_reads_it() {
        // A 3x3-patch grid, so 48x48 pixels, with one pixel set to a known colour. The check is
        // that it lands at the channel and the position the export's patchify would put it: patch
        // `row * cols + col`, and within the patch `(y * 16 + x) * 3 + channel`.
        let grid = Grid::new(3, 3).expect("a grid");
        let (width, height) = grid.pixels();
        assert_eq!((width, height), (48, 48));
        let mut pixels = vec![0xff00_0000_u32 as i32; (width * height) as usize];
        // Red, at pixel (y = 20, x = 35): patch row 1, column 2, and (4, 3) inside it.
        pixels[20 * width as usize + 35] = 0xffff_0000_u32 as i32;

        let values = patchify(grid, &pixels).expect("patches");
        let patches = grid.patches() as usize;
        assert_eq!(values.len(), D_MODEL as usize * patches);
        let patch = 1 * 3 + 2;
        let within = (4 * 16 + 3) * 3;
        assert_eq!(values[within * patches + patch], 1.0, "red is saturated");
        assert_eq!(values[(within + 1) * patches + patch], -1.0, "green is off");
        assert_eq!(values[(within + 2) * patches + patch], -1.0, "blue is off");
        // Black elsewhere, which after the shift is -1 rather than 0.
        assert_eq!(values[within * patches], -1.0);
        assert!(patchify(grid, &pixels[1..]).is_err(), "a short buffer");
    }

    #[test]
    fn the_angles_rotate_the_first_block_by_the_column_and_the_second_by_the_row() {
        // The trap `rotary_axes` exists to avoid: if the two blocks were swapped, or if a block
        // held sines before cosines, every shape would still agree.
        let grid = Grid::new(3, 6).expect("a grid");
        let angles = rotary_angles(grid);
        let patches = grid.patches() as usize;
        assert_eq!(angles.len(), HEAD_DIM as usize * patches);
        let frequencies = inv_freq();
        let half = frequencies.len();

        // Patch 8 is row 1, column 2 of a 3-row, 6-column grid.
        let at = 1 * 6 + 2;
        for (i, frequency) in frequencies.iter().enumerate() {
            let column = 2.0 * frequency;
            let row = 1.0 * frequency;
            let close = |got: f32, want: f32, what: &str| {
                assert!((got - want).abs() < 1e-6, "{what} at {i} is {got}, not {want}");
            };
            close(angles[i * patches + at], column.cos(), "block 0 cosine");
            close(angles[(half + i) * patches + at], column.sin(), "block 0 sine");
            close(angles[(2 * half + i) * patches + at], row.cos(), "block 1 cosine");
            close(angles[(3 * half + i) * patches + at], row.sin(), "block 1 sine");
        }
        // Frequency 0 is 1.0, so the first channel of each block is the raw coordinate.
        assert!((angles[at] - 2.0_f32.cos()).abs() < 1e-6);
        assert!((angles[2 * half * patches + at] - 1.0_f32.cos()).abs() < 1e-6);
    }
}
