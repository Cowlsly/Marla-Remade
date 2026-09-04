//! Maia3-5M: a human-move predictor for `:games:chess`, one forward pass per move.
//!
//! # What it is
//!
//! An encoder-only transformer over 64 tokens, one per square — the "Chessformer" of
//! <https://github.com/CSSLab/maia3>. Width 256, 8 blocks, 8 heads of 32, a 512-wide
//! feed-forward. 5,230,084 parameters, 6.8 MiB as int8 once the shared attention-bias kernel
//! is replicated (see below).
//!
//! It replaces Stockfish, which searched to depth 8 behind an 86 MB NNUE. This searches
//! nothing at all: the board goes in, 4352 move logits come out, and the app masks them to
//! its own legal move list and samples. That is the point of the model — it is trained to
//! predict what a *human of a given rating* plays, so a weak setting blunders the way a
//! beginner does rather than the way a crippled search does.
//!
//! # Strength is an input, not a handicap
//!
//! `SelfElo` and `OppoElo` are 0..5000 and enter as a linear blend of two learned
//! 128-vectors, concatenated onto every square's token. One weights file therefore covers
//! every difficulty; see [`elo_embedding`], and note its inverted naming.
//!
//! # The history is the current position, eight times
//!
//! The model takes 8 plies of history as 96 planes. The shipped `maia3-5m` launcher runs
//! with `use_uci_history=False`, and in that mode upstream seeds its deque with the current
//! position alone and pads by repeating it — so the 96 planes are the current position
//! eight times over. [`tokens`] builds exactly that, which is what makes parity against the
//! preset meaningful and means the app needs no move history.
//!
//! Real 8-ply history is deliberately not implemented: in `use_uci_history` mode each
//! historical position is mirrored by *its own* side to move, so the perspective alternates
//! every ply, and that is easy to get quietly wrong for no gain over what ships.
//!
//! # Post-norm, and RMSNorm inside the block
//!
//! Two things differ from [`super::tinyclip`] and copying that file by reflex gets both
//! wrong. The blocks are **post-norm** — `x = norm(x + sublayer(x))`, not
//! `x = x + sublayer(norm(x))` — and the in-block norm is [`Builder::rms_norm`], which has
//! no mean subtraction and no beta. The two norms outside the stack are ordinary layer
//! norms. Neither mistake changes a single shape.
//!
//! # The attention bias, and why its kernel is replicated eight times
//!
//! Every block generates its own additive `[8, 64, 64]` bias from the board — upstream
//! calls it GAB, and the checkpoint calls it smolgen. The generator pools the squares to
//! `[256]`, runs it through two projections to `[512]`, reads that as `[8, 64]`, and
//! expands it with `einsum("bhi,oi->bho")` against a `[4096, 64]` weight **shared by all
//! eight blocks**.
//!
//! There is no transpose or permute op in this runtime — the layout inventory is
//! `reshaped` / `slice_channels` / `concat` / `concat_positions`, all relabels or copies —
//! so that einsum is expressed as a **grouped 1x1 convolution**: `group = 8`, 512 in,
//! 32768 out, kernel `[32768, 64, 1, 1]`. Group `g` reads input channels
//! `[g*64, (g+1)*64)` and writes `[g*4096, (g+1)*4096)`, which is the einsum with the
//! output already in `[8, 64, 64]` order.
//!
//! The cost is that the converter replicates the `[4096, 64]` weight eight times, +1.84M
//! elements. It is replicated **once, not per block**: the parameter is shared, so all eight
//! blocks pass [`SMOLGEN`] as their `weight_index`. Avoiding the replication would need a new
//! transpose shader, and the memory is the cheaper trade — more so at int8, where the
//! replicated kernel costs 2.1 MB rather than the 4.2 MB it would at fp16.
//!
//! # int8, and why not int4
//!
//! Every `1 x 1` convolution is [`Builder::conv_int8`], quantised per output channel from the
//! fp32 checkpoint. The norms, the biases and the two elo embeddings stay fp16 and together
//! are 1% of the parameters. 13.3 MiB to 6.8 MiB.
//!
//! Measured rather than assumed, by `scripts/ml/maia_quant_eval.py`. The worst per-tensor
//! correlation against fp32 is 0.999941, and over 400 positions at each of the four shipping
//! ratings the quantised model picks a different move 0.5% to 1.0% of the time — always where
//! the top two legal moves are within 0.017 of each other, which is inside the deviation fp16
//! rounding produces on its own.
//!
//! **int4 was measured and rejected.** At 4 bits the same harness agrees on 87% to 91% of
//! moves depending on the group size, and 14 to 29 of the disagreements are on margins wider
//! than fp16 rounding — the model changes its mind rather than losing a coin flip. Sub-row
//! grouping does not rescue it and gives back most of the saving (per-16 is 4.2 MiB against
//! per-channel's 3.5). 5.2M parameters have no redundancy to spare, unlike the large models
//! int4 is usually quoted for.
//!
//! The attention-bias kernel is 30% of the file and is read by every block, so int8 halves
//! this net's weight traffic as well as its size — the part that matters if a move ever feels
//! slow.
//!
//! # The bias is added after the scale
//!
//! [`Builder::attn_scores`] applies `1 / sqrt(head_dim)` itself, and the bias is added to
//! the result. That matches `F.multi_head_attention_forward`, which scales `q` before
//! adding `attn_mask` — so the bias is *not* scaled. Adding it first would divide it by
//! `sqrt(32)`.
//!
//! # What is not emitted
//!
//! The value (win/draw/loss) and ponder heads, and the `last_ln` in front of them. Neither
//! is needed to pick a move, the collector controls the tensor list, so they are simply not
//! in the file. An evaluation bar would want them back.
//!
//! # Move vocabulary
//!
//! [`build`] emits two tensors and `post::maia` assembles the 4352 logits from them:
//!
//! * `0..4095` — `from_square * 64 + to_square`, square `rank * 8 + file`, a1 = 0, h8 = 63
//! * `4096..4351` — `4096 + from_file * 32 + to_file * 4 + piece`, piece in q, r, b, n
//!
//! Promotions are always rank 7 to rank 8 because the board is mirrored for black.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use crate::weights::Reader;

/// Channels through the stack: `dim_vit`.
pub const WIDTH: u32 = 256;

/// Attention heads, so `head_dim` is 32 and [`Builder::attn_scores`]'s scale is the model's.
pub const HEADS: u32 = 8;

/// The feed-forward width: `dim_vit * mlp_ratio`, 256 * 2.0.
pub const FFN: u32 = 512;

/// Encoder blocks, `num_blocks`.
pub const BLOCKS: usize = 8;

/// Tokens, one per square of the board. The whole sequence length; there is no class token.
pub const SQUARES: u32 = 64;

/// Board planes per ply: six piece types for each colour.
pub const PLANES: u32 = 12;

/// Plies of history the token projection expects, `history`.
pub const HISTORY: u32 = 8;

/// Each elo embedding's width, `dim_emb`.
pub const ELO_DIM: u32 = 128;

/// The token projection's input width: `12 * history` planes, then both elo embeddings.
pub const INPUT: u32 = PLANES * HISTORY + 2 * ELO_DIM;

/// The highest elo the model interpolates between its two embeddings. See [`elo_embedding`].
pub const ELO_MAX: f32 = 5000.0;

/// The attention bias generator's intermediate width, `gab_intermediate_dim`.
const BIAS_HIDDEN: u32 = 64;

/// Values the bias generator produces per head, `gab_gen_size`.
const BIAS_GEN: u32 = 64;

/// The bias generator's expanded output: one `[64, 64]` map per head.
const BIAS_OUT: u32 = HEADS * SQUARES * SQUARES;

/// The policy head's width, `head_hid_dim`. Equal to [`WIDTH`], which is why
/// `attn_scores(from, to, 1)`'s own `1 / sqrt(head_dim)` is the model's `1 / sqrt(256)`.
pub const HEAD_HIDDEN: u32 = 256;

/// Promotion pieces the head scores, in the vocabulary's order: queen, rook, bishop, knight.
pub const PROMOTIONS: u32 = 4;

/// Logits [`crate::post::maia`] assembles: 64x64 from-to pairs plus 8x8x4 promotions.
pub const MOVES: usize = (SQUARES * SQUARES) as usize + (8 * 8 * PROMOTIONS) as usize;

/// The epsilon in every `nn.LayerNorm`, which is torch's default.
const EPSILON: f32 = 1e-5;

/// The epsilon in every `torch.nn.RMSNorm`.
///
/// Upstream constructs them as `RMSNorm(d_model)` with no `eps`, and torch resolves that to
/// `torch.finfo(x.dtype).eps` at fp32 rather than to a layer norm's 1e-5.
const RMS_EPSILON: f32 = 1.192_092_9e-7;

/// `elo_embedding_low.weight`, `[1, ELO_DIM]`. Read by the host, not by a shader.
const ELO_LOW: usize = 0;

/// `elo_embedding_high.weight`, `[1, ELO_DIM]`.
const ELO_HIGH: usize = ELO_LOW + 1;

/// `token_projection`: int8 kernel `[WIDTH, INPUT, 1, 1]`, per-channel scale, bias.
const TOKEN_PROJECTION: usize = ELO_HIGH + 1;

/// The attention bias kernel, replicated to `[BIAS_OUT, BIAS_GEN, 1, 1]`, its scale, and a
/// synthesised zero bias.
///
/// One tensor for all eight blocks: they share the parameter, so they share the index. See
/// the module docs for why it is a grouped convolution.
const SMOLGEN: usize = TOKEN_PROJECTION + 3;

/// The first encoder block.
const BLOCK: usize = SMOLGEN + 3;

/// Tensors per encoder block, in the order [`encoder_block`] takes them.
///
/// Eight projections of three — the bias generator's two, q, k, v, the output projection and
/// the two feed-forward — two layer norms of two, and the two RMS norms of one.
const BLOCK_TENSORS: usize = 8 * 3 + 2 * 2 + 2 * 1;

/// `transformer.norm`, the layer norm closing the stack.
const FINAL_NORM: usize = BLOCK + BLOCKS * BLOCK_TENSORS;

/// `proj_sq_from`: int8 kernel `[HEAD_HIDDEN, WIDTH, 1, 1]`, scale, synthesised zero bias.
const SQ_FROM: usize = FINAL_NORM + 2;

/// `proj_sq_to`.
const SQ_TO: usize = SQ_FROM + 3;

/// `promo_bias_proj`: int8 kernel `[PROMOTIONS, HEAD_HIDDEN, 1, 1]`, scale, zero bias.
const PROMO: usize = SQ_TO + 3;

/// Tensors the `.maml` must hold, and the count `maml_convert.py` writes.
pub const TENSORS: usize = PROMO + 3;

/// Convolutions read as int8, each carrying a third tensor for its per-channel scale.
///
/// The token projection, the attention bias kernel, eight projections in each of the eight
/// blocks, and the three policy heads. Only the norms, the biases and the two elo embeddings
/// stay fp16, and together they are 1% of the parameters.
///
/// This counts **kernels in the file**, not dispatches: the bias kernel is one tensor that all
/// eight blocks read, so the plan runs seven more int8 convolutions than there are here.
pub const INT8_CONVS: usize = 2 + BLOCKS * 8 + 3;

/// Hands out `.maml` tensor indices in the order the layers appear.
struct Layers {
    next: usize,
}

impl Layers {
    /// A weight and the bias after it, or a layer norm's gamma and beta.
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

    /// A lone tensor: an RMS norm's gain, which has no beta to follow it.
    fn take1(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }
}

/// A `1 x 1` convolution with an int8 kernel, which every linear projection here is.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv_int8(x, l.take3(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// The additive `[HEADS, SQUARES, SQUARES]` attention bias this block generates from `x`.
///
/// `mean(squares) -> sm2 -> gelu -> ln1 -> sm3 -> gelu -> ln2 -> einsum`. Note that each
/// GELU is applied *before* its norm, not after: upstream writes `y = ln1(sm_act(sm2(y)))`.
fn attention_bias(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    // `[WIDTH, 1, SQUARES]` averaged over the width is upstream's `torch.mean(x, dim=1)`.
    let pooled = b.global_avg_pool(x);
    let y = point(b, l, pooled, BIAS_HIDDEN, Act::Gelu);
    let y = b.layer_norm(y, l.take(), EPSILON);
    let y = point(b, l, y, HEADS * BIAS_GEN, Act::Gelu);
    let y = b.layer_norm(y, l.take(), EPSILON);
    // The grouped expansion. `group = HEADS`, so head `h` reads only its own `BIAS_GEN`
    // values — which is what the einsum's `bhi` index does. Grouped, so it lowers to the
    // untiled `Kind::ConvInt8` rather than the pointwise one; that is the same shader the
    // fp16 version would have used, since a grouped 1x1 has no pointwise fast path either.
    let expanded = b.conv_int8(
        y,
        SMOLGEN,
        BIAS_OUT,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        HEADS,
        Act::None,
    );
    b.reshaped(expanded, Shape::new(HEADS, SQUARES, SQUARES))
}

/// One `EncoderOnlyBlock`: **post-norm** self-attention with a residual, then post-norm
/// feed-forward with another.
fn encoder_block(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let bias = attention_bias(b, l, x);

    let q = point(b, l, x, WIDTH, Act::None);
    let k = point(b, l, x, WIDTH, Act::None);
    let v = point(b, l, x, WIDTH, Act::None);
    // `attn_scores` has already applied `1 / sqrt(head_dim)`, and the bias is added to the
    // scaled scores rather than to `q`. See the module docs.
    let scores = b.attn_scores(q, k, HEADS);
    let scores = b.add(scores, bias);
    let probs = b.softmax(scores);
    let mixed = b.attn_apply(probs, v, HEADS);
    let projected = point(b, l, mixed, WIDTH, Act::None);
    // Post-norm: the norm is outside the residual, not inside it.
    let x = b.add(x, projected);
    let x = b.rms_norm(x, l.take1(), RMS_EPSILON);

    let inner = point(b, l, x, FFN, Act::Gelu);
    let projected = point(b, l, inner, WIDTH, Act::None);
    let x = b.add(x, projected);
    b.rms_norm(x, l.take1(), RMS_EPSILON)
}

/// Build the forward pass: `[INPUT, 1, SQUARES]` in, `[1, 64, 64]` and `[PROMOTIONS, 1, 64]` out.
///
/// The two outputs are `scores_base` and the promotion projection over every square.
/// `post::maia` turns them into the 4352-logit move vector; see [`crate::post::maia`] for
/// why the promotion head runs over all 64 positions rather than the eight it needs.
pub fn build(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The elo tables are read on the host by `elo_embedding`, so nothing in the plan touches
    // them and `finish` would otherwise refuse the file.
    b.host_tensor(ELO_LOW, &[1, ELO_DIM]);
    b.host_tensor(ELO_HIGH, &[1, ELO_DIM]);

    let tokens = b.input(Shape::new(INPUT, 1, SQUARES));
    let head = &mut Layers { next: TOKEN_PROJECTION };
    let mut x = point(b, head, tokens, WIDTH, Act::None);
    if head.next != SMOLGEN {
        return Err(format!("the token projection ends at {}, not {SMOLGEN}", head.next));
    }

    let l = &mut Layers { next: BLOCK };
    for _ in 0..BLOCKS {
        x = encoder_block(b, l, x);
    }
    if l.next != FINAL_NORM {
        return Err(format!("the stack claims {} tensors, not {FINAL_NORM}", l.next));
    }
    let x = b.layer_norm(x, FINAL_NORM, EPSILON);

    let policy = &mut Layers { next: SQ_FROM };
    let sq_from = point(b, policy, x, HEAD_HIDDEN, Act::None);
    let sq_to = point(b, policy, x, HEAD_HIDDEN, Act::None);
    // `einsum("bid,bjd->bij") / sqrt(head_hid_dim)` with one head, whose `head_dim` is
    // `HEAD_HIDDEN` — so the scale this derives is exactly the model's.
    let scores = b.attn_scores(sq_from, sq_to, 1);
    let promo = point(b, policy, sq_to, PROMOTIONS, Act::None);
    if policy.next != TENSORS {
        return Err(format!("the policy head ends at {}, not {TENSORS}", policy.next));
    }
    builder.finish(&[scores, promo])
}

/// The blended elo vector for `elo`, `ELO_DIM` long.
///
/// Upstream's `interpolate_elo`, including its inverted naming: `weight_low` is
/// `elo / 5000` and multiplies `elo_embedding_low`, so at elo 0 the vector is entirely
/// `elo_embedding_high`. Reproduced rather than corrected — the names are backwards, the
/// arithmetic is what the weights were trained against.
pub fn elo_embedding(weights: Reader<'_>, elo: f32) -> Result<Vec<f32>, String> {
    let clamped = elo.clamp(0.0, ELO_MAX);
    let weight_low = clamped / ELO_MAX;
    let weight_high = 1.0 - weight_low;
    let low = weights.fp16(ELO_LOW, &[1, ELO_DIM])?;
    let high = weights.fp16(ELO_HIGH, &[1, ELO_DIM])?;
    Ok(low
        .iter()
        .zip(high.iter())
        .map(|(&l, &h)| weight_low * l + weight_high * h)
        .collect())
}

/// The `[INPUT, 1, SQUARES]` plan input, as f32 for the caller to upload.
///
/// `planes` is the board as `PLANES * SQUARES` in plane-major order — plane `p`, square `s`
/// at `p * 64 + s`, square `rank * 8 + file` — already mirrored and colour-swapped if black
/// is to move. The eight history plies are that same board repeated; see the module docs.
///
/// The two elo vectors are broadcast across all 64 squares, which is upstream's
/// `unsqueeze(1).expand(-1, 64, -1)`.
pub fn tokens(planes: &[f32], self_elo: &[f32], oppo_elo: &[f32]) -> Result<Vec<f32>, String> {
    let squares = SQUARES as usize;
    if planes.len() != PLANES as usize * squares {
        return Err(format!("{} board values, not {}", planes.len(), PLANES as usize * squares));
    }
    if self_elo.len() != ELO_DIM as usize || oppo_elo.len() != ELO_DIM as usize {
        return Err(format!(
            "elo vectors of {} and {}, not {ELO_DIM} each",
            self_elo.len(),
            oppo_elo.len()
        ));
    }

    // Channel-major, like every other input to this runtime: channel `c`, square `s` at
    // `c * 64 + s`.
    let mut out = vec![0.0f32; INPUT as usize * squares];
    for ply in 0..HISTORY as usize {
        let base = ply * PLANES as usize * squares;
        out[base..base + PLANES as usize * squares].copy_from_slice(planes);
    }
    let after_board = (PLANES * HISTORY) as usize * squares;
    for (channel, &value) in self_elo.iter().chain(oppo_elo.iter()).enumerate() {
        let at = after_board + channel * squares;
        out[at..at + squares].fill(value);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source).unwrap_or_else(|e| panic!("{e}"))
    }

    fn counts(plan: &Plan) -> std::collections::BTreeMap<String, usize> {
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            let name = match op {
                Op::Copy { .. } => "Copy".to_string(),
                Op::Dispatch { kind, .. } => super::super::tests::name_of(*kind),
            };
            *counts.entry(name).or_insert(0) += 1;
        }
        counts
    }

    #[test]
    fn the_layout_constants_walk_the_whole_file() {
        // The cursor arithmetic in `build` is what actually enforces this; the constants
        // are restated here so a mis-sized `BLOCK_TENSORS` fails as a number rather than
        // as a shape mismatch deep in the stack.
        assert_eq!(BLOCK_TENSORS, 30);
        assert_eq!(BLOCK, 8);
        assert_eq!(FINAL_NORM, 8 + 8 * 30);
        assert_eq!(TENSORS, FINAL_NORM + 2 + 3 + 3 + 3);
        assert_eq!(TENSORS, 259);
        // Two globals, eight per block, three in the policy head.
        assert_eq!(INT8_CONVS, 69);
        // `build` returns `Err` rather than panicking if any cursor lands short.
        plan();
    }

    #[test]
    fn the_pass_reads_every_tensor_in_the_file() {
        // `Builder::finish` refuses a tensor that is neither read nor named, so a plan that
        // builds at all has covered the file. The two elo tables are the only host ones.
        let source = Shapes::new(TENSORS);
        assert!(build(&source).is_ok());
        assert!(build(&Shapes::new(TENSORS - 1)).is_err());
    }

    #[test]
    fn the_outputs_are_the_score_map_and_the_promotion_projection() {
        let plan = plan();
        let shapes: Vec<Shape> = plan.outputs.iter().map(|o| o.shape).collect();
        assert_eq!(shapes, vec![Shape::new(1, SQUARES, SQUARES), Shape::new(PROMOTIONS, 1, SQUARES)]);
        assert_eq!(plan.inputs.len(), 1);
        assert_eq!(plan.inputs[0].shape, Shape::new(INPUT, 1, SQUARES));
    }

    #[test]
    fn every_block_normalises_twice_with_an_rms_norm_and_never_with_a_layer_norm() {
        // Post-norm with RMSNorm inside the block: 16 RMS norms for the eight blocks, and
        // layer norms only in the bias generators (2 per block) and closing the stack.
        let counts = counts(&plan());
        assert_eq!(counts.get("RmsNorm"), Some(&(2 * BLOCKS)));
        assert_eq!(counts.get("LayerNorm"), Some(&(2 * BLOCKS + 1)));
    }

    #[test]
    fn the_bias_generator_is_a_grouped_convolution_the_head_split_can_read() {
        // Grouping is what keeps `Node::ConvInt8`'s pointwise fast path from taking this,
        // and is also the whole reason the einsum needs no transpose. It is therefore the
        // one op in the plan that is an untiled `ConvInt8` rather than a `ConvPointInt8`.
        let plan = plan();
        let grouped: Vec<&Op> = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: Kind::ConvInt8, .. }))
            .collect();
        assert_eq!(grouped.len(), BLOCKS);
        for op in grouped {
            let Op::Dispatch { push, .. } = op else { unreachable!() };
            assert_eq!(push.group, HEADS);
            assert_eq!(push.in_c, HEADS * BIAS_GEN);
            assert_eq!(push.out_c, BIAS_OUT);
        }
    }

    #[test]
    fn every_projection_is_int8_and_every_norm_is_not() {
        // The norms, the biases and the elo tables are the only fp16 left. A projection that
        // slipped back to `Builder::conv` would still build and still run, just twice the
        // size, and no shape would catch it.
        let counts = counts(&plan());
        let int8 = counts.get("ConvInt8").copied().unwrap_or(0)
            + counts.get("ConvPointInt8").copied().unwrap_or(0)
            + counts.get("ConvVecInt8").copied().unwrap_or(0);
        // Dispatches, not tensors, and the two differ by exactly the sharing: the token
        // projection, eight projections in each of eight blocks, three policy heads, and one
        // attention-bias expansion *per block* — but all eight of those read the one
        // replicated kernel at `SMOLGEN`, so the file holds `INT8_CONVS` kernels and the plan
        // runs seven more convolutions than that.
        assert_eq!(int8, 1 + BLOCKS * 9 + 3);
        assert_eq!(int8, INT8_CONVS + BLOCKS - 1);
        assert_eq!(counts.get("Conv"), None, "an fp16 convolution is left in the plan");
    }

    #[test]
    fn the_attention_bias_is_added_to_the_scaled_scores() {
        // An `Add` of two `[HEADS, SQUARES, SQUARES]` operands, once per block, between the
        // score map and the softmax. Adding the bias to `q` instead would divide it by
        // `sqrt(head_dim)`.
        let plan = plan();
        let mut seen = 0;
        for pair in plan.ops.windows(3) {
            let [Op::Dispatch { kind: Kind::AttnScores, .. }, Op::Dispatch { kind: Kind::Add, push, .. }, Op::Dispatch { kind: Kind::Softmax, .. }] =
                pair
            else {
                continue;
            };
            assert_eq!((push.in_c, push.in_h, push.in_w), (HEADS, SQUARES, SQUARES));
            seen += 1;
        }
        assert_eq!(seen, BLOCKS);
    }

    #[test]
    fn the_policy_score_map_uses_the_models_own_scale() {
        // `attn_scores` derives `1 / sqrt(head_dim)`, and with one head over `HEAD_HIDDEN`
        // channels that is `1 / sqrt(256)` — upstream's `/ math.sqrt(head_hid_dim)`.
        let plan = plan();
        let last = plan
            .ops
            .iter()
            .rev()
            .find_map(|op| match op {
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => Some(*push),
                _ => None,
            })
            .expect("a score map");
        assert_eq!(last.group, 1);
        let scale = f32::from_bits(last.param0_bits);
        assert!(
            (scale - 1.0 / (HEAD_HIDDEN as f32).sqrt()).abs() < 1e-9,
            "scale {scale}"
        );
    }

    #[test]
    fn no_op_reads_what_it_writes() {
        assert_no_aliasing(&plan());
    }

    #[test]
    fn the_elo_blend_is_upstreams_and_so_reads_backwards() {
        // At elo 0 the vector is `elo_embedding_high`, and at 5000 it is `_low`. The names
        // are upstream's and they are the wrong way round; the arithmetic is what matters.
        let blob = crate::weights::write_mixed(
            crate::weights::graph::MAIA,
            &[
                crate::weights::Fixture::F16(vec![1, ELO_DIM], vec![1.0; ELO_DIM as usize]),
                crate::weights::Fixture::F16(vec![1, ELO_DIM], vec![3.0; ELO_DIM as usize]),
            ],
        );
        let weights = crate::weights::Weights::parse(&blob, crate::weights::graph::MAIA)
            .expect("the fixture blob parses");
        let at_zero = elo_embedding(weights.reader(), 0.0).unwrap();
        let at_max = elo_embedding(weights.reader(), ELO_MAX).unwrap();
        let midpoint = elo_embedding(weights.reader(), ELO_MAX / 2.0).unwrap();
        assert!(at_zero.iter().all(|&v| (v - 3.0).abs() < 1e-3), "{:?}", &at_zero[..4]);
        assert!(at_max.iter().all(|&v| (v - 1.0).abs() < 1e-3), "{:?}", &at_max[..4]);
        assert!(midpoint.iter().all(|&v| (v - 2.0).abs() < 1e-3), "{:?}", &midpoint[..4]);
        // Past either end it clamps rather than extrapolating, as `torch.clamp` does.
        let beyond = elo_embedding(weights.reader(), 9000.0).unwrap();
        assert!(beyond.iter().all(|&v| (v - 1.0).abs() < 1e-3));
    }

    #[test]
    fn tokens_repeats_the_board_across_every_ply_and_broadcasts_the_elos() {
        // A board with one distinguishable value per plane-square, so a ply written in the
        // wrong place is visible.
        let planes: Vec<f32> =
            (0..PLANES * SQUARES).map(|i| i as f32).collect();
        let self_elo: Vec<f32> = (0..ELO_DIM).map(|i| 1000.0 + i as f32).collect();
        let oppo_elo: Vec<f32> = (0..ELO_DIM).map(|i| 2000.0 + i as f32).collect();
        let out = tokens(&planes, &self_elo, &oppo_elo).unwrap();
        assert_eq!(out.len(), INPUT as usize * SQUARES as usize);

        let stride = (PLANES * SQUARES) as usize;
        for ply in 0..HISTORY as usize {
            assert_eq!(&out[ply * stride..(ply + 1) * stride], &planes[..], "ply {ply}");
        }
        // Each elo channel is one value repeated across all 64 squares, and self comes
        // before oppo.
        let after = (PLANES * HISTORY * SQUARES) as usize;
        for channel in 0..ELO_DIM as usize {
            let at = after + channel * SQUARES as usize;
            assert!(out[at..at + 64].iter().all(|&v| v == self_elo[channel]));
        }
        let after_self = after + (ELO_DIM * SQUARES) as usize;
        for channel in 0..ELO_DIM as usize {
            let at = after_self + channel * SQUARES as usize;
            assert!(out[at..at + 64].iter().all(|&v| v == oppo_elo[channel]));
        }
    }

    #[test]
    fn tokens_refuses_a_board_or_an_elo_vector_of_the_wrong_length() {
        let planes = vec![0.0; (PLANES * SQUARES) as usize];
        let elo = vec![0.0; ELO_DIM as usize];
        assert!(tokens(&planes[..10], &elo, &elo).is_err());
        assert!(tokens(&planes, &elo[..10], &elo).is_err());
        assert!(tokens(&planes, &elo, &elo[..10]).is_err());
    }
}
