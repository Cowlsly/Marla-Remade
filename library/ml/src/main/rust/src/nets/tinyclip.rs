//! TinyCLIP-ViT-8M/16 Text-3M: an image tower and a text tower, in one `.maml`.
//!
//! # What it is
//!
//! Two transformers that project into a shared 512-d space, which `:photos` compares with a cosine
//! for semantic search. Both are width 256 with 4 heads of 64 and a 1024-wide feed-forward; the
//! vision tower is 10 layers over 16x16 patches of a 224x224 image, the text tower 3 causal layers
//! over up to 77 tokens. 23,446,016 parameters, 22.6 MiB as int8.
//!
//! It replaces onnxruntime running `photos/src/main/assets/clip/model_int8.onnx`. That export was
//! also int8, so this is not a size win on the weights — the APK shrinks because
//! `onnxruntime-android`'s ~27 MB of native `.so` leaves with it. See
//! `photos/src/main/assets/clip/README.md` for why the `.maml` is built from the **fp32** graph
//! even though the shipped one was already quantised.
//!
//! It is also *more* accurate than what it replaces. Cosine of the normalised 512-d embedding
//! against the fp32 export is 0.999864 for an image and 0.999394 for a query, against the shipped
//! int8 file's 0.998478 and 0.982881 — the text tower's gap is the 49,408-row token table, which
//! that export quantised per *tensor* with a zero point and this one quantises per row. Measured by
//! `scripts/ml/onnx_parity.py tinyclip` and `tinyclip_text`.
//!
//! # One file, two passes
//!
//! [`Mode`] selects which of them [`build`] emits. Unlike SMaLL-100's three passes the two towers
//! share **no weights at all** — they share a file, and therefore one upload and one
//! [`crate::vulkan::run::Net`]. Each pass names the other's tensors with
//! [`Builder::host_tensor`], and `tests::the_passes_cover_the_file_and_every_one_of_them_builds` is
//! what keeps that from hiding a genuinely unread layer.
//!
//! # Pooling is on the host, and both towers pool a different position
//!
//! The vision tower pools the **class token**, position 0, and the text tower the **end-of-text
//! position**. In this runtime's `[d_model, 1, T]` layout a position is a *column*, so it is
//! strided rather than contiguous and there is no cheap device-side gather of one.
//!
//! Both plans therefore run `post_layernorm` / `final_layer_norm` and the 256 -> 512 projection
//! over **every** position and let the host read the column it wants. That is exact: a layer norm
//! is per position and a `1 x 1` convolution is per position, so column `p` of the output is what
//! the reference computes from position `p` alone. It costs 197 projections instead of one in the
//! vision tower, which is 25.8 of its 1,500 million multiply-accumulates.
//!
//! Getting the two the wrong way round, or pooling a mean, produces 512-d vectors that normalise
//! cleanly and are simply wrong. There is no shape that catches it.
//!
//! # `pre_layrnorm`
//!
//! The vision tower normalises **before** the transformer stack as well as after it, and upstream
//! misspells it — `CLIPVisionTransformer.pre_layrnorm`, with the `e` missing. The name is kept as
//! the export has it. Missing the layer entirely is, again, a plausible-looking embedding.
//!
//! # The text tower is causal, and only needs `eot + 1` positions
//!
//! Its three layers attend over the whole sequence at once behind a causal mask, which is
//! [`crate::nets::Kind::SoftmaxCausal`]. The mask is also why [`Mode::Text`] takes a length: the
//! pooled position can only read positions before it, so running `eot + 1` positions instead of the
//! tokenizer's padded 77 gives the **identical** vector for a quarter of the work on a typical
//! query. `ClipEmbedder` passes an all-ones attention mask, which combines with the causal mask to
//! no effect, so nothing else depends on the padding being present.
//!
//! # The attention scale is already the model's
//!
//! `CLIPAttention` scales the query by `head_dim ** -0.5`, and `head_dim` is 64, so the export's
//! own constant is 0.125 — exactly what [`Builder::attn_scores`] applies. Nothing folds a query
//! scale, and folding one would apply it twice.
//!
//! # The token embedding is gathered on the host
//!
//! There is no int8 `embed.comp`, and there does not need to be: CLIP's text positions are a
//! **learned table**, so a token's input vector is a function of one embedding row and one position
//! row. [`embed_positions`] reads both and adds them in f32, exactly as
//! `nets::small100::embed_positions` does, and hands the result in as an ordinary fp16 plan input.
//!
//! The cost is that the 12.6 MiB table is uploaded to the device and never read by a shader. It
//! stays in this file anyway: splitting it out would mean a second `.maml`, a second graph id and a
//! second asset for a tower that runs once per search query.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use crate::weights::Reader;

/// Channels throughout, both towers: `hidden_size`.
pub const WIDTH: u32 = 256;

/// Attention heads, so `head_dim` is 64 and [`Builder::attn_scores`]'s own scale is the model's.
pub const HEADS: u32 = 4;

/// The feed-forward width, `intermediate_size` in both configs.
pub const FFN: u32 = 1024;

/// Vision transformer layers.
pub const VISION_LAYERS: usize = 10;

/// Text transformer layers. Three is what makes the text tower 3M parameters.
pub const TEXT_LAYERS: usize = 3;

/// `patch_size`. The kernel and the stride of the patch embedding are both this.
pub const PATCH: u32 = 16;

/// The square input side, from `preprocessor_config.json`'s `crop_size`.
pub const IMAGE_SIZE: u32 = 224;

/// Patches along each edge: 224 / 16.
pub const GRID: u32 = IMAGE_SIZE / PATCH;

/// Sequence length the vision tower sees: the 14x14 patches plus the class token.
pub const VISION_POSITIONS: u32 = GRID * GRID + 1;

/// Byte-pair vocabulary entries.
pub const VOCAB: u32 = 49_408;

/// `max_position_embeddings`, and so the longest text this can embed.
pub const CONTEXT: u32 = 77;

/// The shared embedding space both towers project into.
pub const PROJECTION: u32 = 512;

/// The epsilon in every layer norm.
const EPSILON: f32 = 1e-5;

/// Tensors per encoder layer, the same for both towers: two norms of two, four projections of
/// three, and the two feed-forward projections.
const LAYER_TENSORS: usize = 2 + 4 * 3 + 2 + 3 + 3;

/// The patch embedding: int8 kernel, per-channel scale, synthesised zero bias.
const PATCH_CONV: usize = 0;

/// The class token, as the `[WIDTH, 1, 1]` a [`super::Kind::Constant`] wants.
const CLASS_TOKEN: usize = PATCH_CONV + 3;

/// The vision position table, transposed to `[WIDTH, 1, VISION_POSITIONS]` by the converter.
const IMAGE_POSITIONS: usize = CLASS_TOKEN + 1;

/// `pre_layrnorm`, before the stack. See the module docs.
const PRE_NORM: usize = IMAGE_POSITIONS + 1;

/// The first vision layer.
const VISION: usize = PRE_NORM + 2;

/// `post_layernorm`, after the stack.
const POST_NORM: usize = VISION + VISION_LAYERS * LAYER_TENSORS;

/// `visual_projection`, 256 -> 512 with no bias of its own.
const VISUAL_PROJECTION: usize = POST_NORM + 2;

/// The token embedding: int8 kernel and per-row scale, and **no bias** — nothing convolves with
/// it, so there is nothing for a bias to be added to. See [`embed_positions`].
const TOKENS: usize = VISUAL_PROJECTION + 3;

/// The text position table, left `[CONTEXT, WIDTH]` because the host reads it.
const TEXT_POSITIONS: usize = TOKENS + 2;

/// The first text layer.
const TEXT: usize = TEXT_POSITIONS + 1;

/// `final_layer_norm`, after the text stack.
const FINAL_NORM: usize = TEXT + TEXT_LAYERS * LAYER_TENSORS;

/// `text_projection`, 256 -> 512 with no bias of its own.
const TEXT_PROJECTION: usize = FINAL_NORM + 2;

/// Tensors the `.maml` must hold, and the count `maml_convert.py` writes.
pub const TENSORS: usize = TEXT_PROJECTION + 3;

/// Convolutions read as int8 rather than fp16, each carrying a third tensor for its scale.
///
/// The patch embedding, all six projections of each of the 13 layers, and both output heads. Only
/// the layer norms, the biases, the class token and the two position tables stay fp16, and together
/// they are 0.5% of the parameters.
///
/// The 12.6 MiB token embedding is int8 too and is **not** counted here: it is a table the host
/// gathers rows of, not a convolution. `maml_convert.py` reports its fidelity over
/// `INT8_CONVS + 1` tensors for that reason.
pub const INT8_CONVS: usize = 1 + (VISION_LAYERS + TEXT_LAYERS) * 6 + 2;

/// Which forward pass [`build`] emits.
///
/// One file and two plans, run through [`crate::vulkan::run::Net::rebuild`] rather than two nets,
/// so the 22.6 MiB upload happens once.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// The vision tower over one image: `[3, 224, 224]` in, `[512, 1, 197]` out.
    ///
    /// The embedding is column **0**, the class token. See the module docs for why the projection
    /// runs over every position.
    Image,
    /// The text tower over `len` positions: `[256, 1, len]` in, `[512, 1, len]` out.
    ///
    /// The input is [`embed_positions`]'s output. The embedding is the **last** column, which is
    /// the end-of-text position — the caller passes `len = eot + 1` and the causal mask makes that
    /// exact.
    Text {
        /// Positions to run, which is one past the end-of-text index.
        len: u32,
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

/// One `CLIPEncoderLayer`: pre-norm self-attention with a residual, then pre-norm feed-forward
/// with another.
///
/// `causal` is the only difference between the two towers' layers, and it is
/// [`Builder::softmax_causal`] rather than a mask tensor: the runtime has no additive mask.
fn encoder_layer(b: &mut Builder, l: &mut Layers, x: Id, causal: bool) -> Id {
    let normed = b.layer_norm(x, l.take(), EPSILON);
    let q = point(b, l, normed, WIDTH, Act::None);
    let k = point(b, l, normed, WIDTH, Act::None);
    let v = point(b, l, normed, WIDTH, Act::None);
    let scores = b.attn_scores(q, k, HEADS);
    let probs = if causal { b.softmax_causal(scores) } else { b.softmax(scores) };
    let mixed = b.attn_apply(probs, v, HEADS);
    let projected = point(b, l, mixed, WIDTH, Act::None);
    let x = b.add(x, projected);

    let normed = b.layer_norm(x, l.take(), EPSILON);
    // `hidden_act` is `gelu`, spelled as an `Erf` in the export, so this is the exact form rather
    // than the tanh approximation. See `Act::Gelu`.
    let inner = point(b, l, normed, FFN, Act::Gelu);
    let projected = point(b, l, inner, WIDTH, Act::None);
    b.add(x, projected)
}

/// Build one of TinyCLIP's two passes. See [`Mode`].
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    match mode {
        Mode::Image => image(weights),
        Mode::Text { len } => text(weights, len),
    }
}

/// The vision tower over one 224x224 image.
fn image(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    name_host_tensors(b, PATCH_CONV..TOKENS);

    let pixels = b.input(Shape::new(3, IMAGE_SIZE, IMAGE_SIZE));
    // Kernel and stride both 16, unpadded, so this is the non-overlapping patch projection and the
    // output is the 14x14 grid. `bias=False` upstream; the converter synthesised a zero.
    let l = &mut Layers { next: PATCH_CONV };
    let patches =
        b.conv_int8(pixels, l.take3(), WIDTH, (PATCH, PATCH), (PATCH, PATCH), (1, 1), (0, 0, 0, 0), 1, Act::None);
    // `[WIDTH, GRID, GRID]` and `[WIDTH, 1, GRID * GRID]` are the same bytes in the same order,
    // which is what `flatten(2)` means in the export.
    let patches = b.reshaped(patches, Shape::new(WIDTH, 1, GRID * GRID));

    let class = b.constant(CLASS_TOKEN, Shape::new(WIDTH, 1, 1));
    // The class token is **prepended**, so it is position 0 and the patches run 1..197. Appending
    // it instead would put the pooled vector at the end and shift every position embedding by one.
    let sequence = b.concat_positions(&[class, patches]);
    let positions = b.constant(IMAGE_POSITIONS, Shape::new(WIDTH, 1, VISION_POSITIONS));
    let mut x = b.add(sequence, positions);

    x = b.layer_norm(x, PRE_NORM, EPSILON);
    let l = &mut Layers { next: VISION };
    for _ in 0..VISION_LAYERS {
        x = encoder_layer(b, l, x, false);
    }
    if l.next != POST_NORM {
        return Err(format!("the vision stack claims {} tensors, not {POST_NORM}", l.next));
    }
    let normed = b.layer_norm(x, POST_NORM, EPSILON);
    let head = &mut Layers { next: VISUAL_PROJECTION };
    let projected = point(b, head, normed, PROJECTION, Act::None);
    if head.next != TOKENS {
        return Err(format!("the visual projection ends at {}, not {TOKENS}", head.next));
    }
    builder.finish(&[projected])
}

/// The text tower over `len` already-embedded positions.
fn text(weights: &dyn WeightSource, len: u32) -> Result<Plan, String> {
    if len == 0 {
        return Err("a text pass over no tokens".into());
    }
    if len > CONTEXT {
        return Err(format!("{len} tokens, past the {CONTEXT} positions the model has"));
    }

    let mut builder = Builder::new(weights);
    let b = &mut builder;
    name_host_tensors(b, TEXT..TENSORS);

    let mut x = b.input(Shape::new(WIDTH, 1, len));
    let l = &mut Layers { next: TEXT };
    for _ in 0..TEXT_LAYERS {
        x = encoder_layer(b, l, x, true);
    }
    if l.next != FINAL_NORM {
        return Err(format!("the text stack claims {} tensors, not {FINAL_NORM}", l.next));
    }
    let normed = b.layer_norm(x, FINAL_NORM, EPSILON);
    let head = &mut Layers { next: TEXT_PROJECTION };
    let projected = point(b, head, normed, PROJECTION, Act::None);
    if head.next != TENSORS {
        return Err(format!("the text projection ends at {}, not {TENSORS}", head.next));
    }
    builder.finish(&[projected])
}

/// Name every tensor **outside** `read` as one this pass does not touch.
///
/// [`Builder::finish`] refuses an unread tensor, and neither pass reads the whole file. Declaring
/// the complement rather than listing it keeps the two in step: adding a layer changes the range
/// and nothing else.
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
/// `maml_convert.collect_tinyclip` writes — which is the point: a converter and a runtime that
/// disagree about a shape fail here rather than on the device.
fn dims_of(index: usize) -> Vec<u32> {
    match index {
        PATCH_CONV => vec![WIDTH, 3, PATCH, PATCH],
        CLASS_TOKEN => vec![WIDTH, 1, 1],
        IMAGE_POSITIONS => vec![WIDTH, 1, VISION_POSITIONS],
        VISUAL_PROJECTION | TEXT_PROJECTION => vec![PROJECTION, WIDTH, 1, 1],
        TOKENS => vec![VOCAB, WIDTH, 1, 1],
        TEXT_POSITIONS => vec![CONTEXT, WIDTH],
        _ if index == TOKENS + 1 => vec![VOCAB],
        _ if index == VISUAL_PROJECTION + 1 || index == VISUAL_PROJECTION + 2 => vec![PROJECTION],
        _ if index == TEXT_PROJECTION + 1 || index == TEXT_PROJECTION + 2 => vec![PROJECTION],
        _ if (VISION..POST_NORM).contains(&index) => layer_dims((index - VISION) % LAYER_TENSORS),
        _ if (TEXT..FINAL_NORM).contains(&index) => layer_dims((index - TEXT) % LAYER_TENSORS),
        // The patch convolution's scale and bias, and the four layer norms outside the stacks.
        _ => vec![WIDTH],
    }
}

/// The shape of the `within`th tensor of an encoder layer.
fn layer_dims(within: usize) -> Vec<u32> {
    // A layer is a sequence of groups: `[2]` for a norm, `[out, in, 1, 1] [out] [out]` for a
    // projection. Walking them is shorter than a table and cannot disagree with [`Layers`].
    let mut groups: Vec<(usize, u32, u32)> = vec![(2, 0, 0)];
    groups.extend([(3, WIDTH, WIDTH); 4]);
    groups.push((2, 0, 0));
    groups.push((3, FFN, WIDTH));
    groups.push((3, WIDTH, FFN));

    let mut at = 0;
    for (size, out, inputs) in groups {
        if within < at + size {
            let offset = within - at;
            return match (size, offset) {
                // A norm's gamma and beta.
                (2, _) => vec![WIDTH],
                // A projection's kernel, then its scale and its bias.
                (_, 0) => vec![out, inputs, 1, 1],
                _ => vec![out],
            };
        }
        at += size;
    }
    vec![WIDTH]
}

/// The embedded and positioned text for `ids`, in the channel-major layout the plan wants.
///
/// The `[WIDTH, 1, ids.len()]` fp16 input to [`Mode::Text`], as f32 for the caller to upload.
///
/// `x[t] = token_embedding[ids[t]] + position_embedding[t]`, both read from the file and summed in
/// f32 before anything is rounded. CLIP's positions are a **learned table**, not sinusoids, so
/// unlike `nets::small100::embed_positions` there is nothing to compute — and there is no offset
/// either: position 0 is the `<|startoftext|>` token's, which is `ids[0]`.
pub fn embed_positions(weights: Reader<'_>, ids: &[u32]) -> Result<Vec<f32>, String> {
    if ids.is_empty() {
        return Err("an embedding of no tokens".into());
    }
    if ids.len() > CONTEXT as usize {
        return Err(format!("{} tokens, past the {CONTEXT} the model has", ids.len()));
    }
    let table = weights.fp16(TEXT_POSITIONS, &[CONTEXT, WIDTH])?;
    let width = WIDTH as usize;
    let mut out = vec![0.0f32; width * ids.len()];
    for (at, &id) in ids.iter().enumerate() {
        if id >= VOCAB {
            return Err(format!("token {id} is past the {VOCAB}-entry vocabulary"));
        }
        let embedding = weights.int8_row(TOKENS, TOKENS + 1, &[VOCAB, WIDTH, 1, 1], id)?;
        for (channel, value) in embedding.iter().enumerate() {
            let position = table
                .get(at * width + channel)
                .ok_or("the position table is shorter than the sequence")?;
            // Channel-major: this runtime indexes `[c, h, w]`, and both tables are `[w, c]`.
            let slot = out
                .get_mut(channel * ids.len() + at)
                .ok_or("an embedding row is wider than the model")?;
            *slot = value + position;
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// `<|startoftext|>`, three pieces and `<|endoftext|>`.
    const LEN: u32 = 5;

    fn plan(mode: Mode) -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source, mode).unwrap_or_else(|e| panic!("{mode:?}: {e}"))
    }

    fn counts(plan: &Plan) -> std::collections::BTreeMap<String, usize> {
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0usize) += 1;
            }
        }
        counts
    }

    #[test]
    fn the_layout_matches_the_converter() {
        // The numbers `maml_convert.py --graph tinyclip --print-layers` reports. A disagreement
        // here is a plan that reads one layer's weights as another's.
        assert_eq!(LAYER_TENSORS, 22);
        assert_eq!((PATCH_CONV, CLASS_TOKEN, IMAGE_POSITIONS, PRE_NORM), (0, 3, 4, 5));
        assert_eq!((VISION, POST_NORM, VISUAL_PROJECTION), (7, 227, 229));
        assert_eq!((TOKENS, TEXT_POSITIONS, TEXT), (232, 234, 235));
        assert_eq!((FINAL_NORM, TEXT_PROJECTION), (301, 303));
        assert_eq!(TENSORS, 306);
        assert_eq!(INT8_CONVS, 81);
        assert_eq!((GRID, VISION_POSITIONS), (14, 197));
    }

    #[test]
    fn the_parameter_total_matches_the_export() {
        // What the file holds, from the layout `every_tensor_shape_is_stated_the_same_way_twice`
        // pins, against the export plus exactly the two things quantising adds.
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();

        // One fp16 scale per output channel of each of the 81 int8 convolutions, plus one per row
        // of the token embedding.
        let layer_scales = u64::from(4 * WIDTH + FFN + WIDTH);
        let scales = u64::from(WIDTH)
            + (VISION_LAYERS + TEXT_LAYERS) as u64 * layer_scales
            + 2 * u64::from(PROJECTION)
            + u64::from(VOCAB);
        assert_eq!(scales, 80_640);
        // And a zero bias for the patch embedding and each projection head, none of which has one.
        let synthesised = u64::from(WIDTH) + 2 * u64::from(PROJECTION);

        assert_eq!(total, 23_446_016 + scales + synthesised);
    }

    #[test]
    fn the_file_is_the_size_the_asset_is() {
        // 23,734,912 bytes, which `maml_convert.py` prints and the checked-in asset is. Everything
        // but the 82 int8 tensors is fp16, and they are 99.5% of the elements — which is why the
        // file is barely over the parameter count in bytes.
        let int8 = u64::from(WIDTH) * 3 * u64::from(PATCH) * u64::from(PATCH)
            + (VISION_LAYERS + TEXT_LAYERS) as u64
                * u64::from(WIDTH)
                * u64::from(4 * WIDTH + 2 * FFN)
            + 2 * u64::from(PROJECTION) * u64::from(WIDTH)
            + u64::from(VOCAB) * u64::from(WIDTH);
        assert_eq!(int8, 23_330_816);
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();
        let file = int8 + (total - int8) * 2;
        // Plus the 64-byte header, a 32-byte table entry each, and up to 15 bytes of padding per
        // tensor. The asset is 23,734,912.
        let overhead = 64 + TENSORS as u64 * 32;
        assert!(
            (file + overhead..file + overhead + TENSORS as u64 * 16).contains(&23_734_912),
            "{file} + {overhead} against 23,734,912"
        );
    }

    /// The tensor range each pass reads on the device. Everything else it names.
    ///
    /// Stated here rather than returned by [`build`] because it is the thing under test: a pass
    /// that read the wrong range would name the right one and still be wrong.
    fn read_by(mode: Mode) -> std::ops::Range<usize> {
        match mode {
            Mode::Image => PATCH_CONV..TOKENS,
            Mode::Text { .. } => TEXT..TENSORS,
        }
    }

    #[test]
    fn the_passes_cover_the_file_and_every_one_of_them_builds() {
        // `Builder::finish` only checks that a tensor is read *or* named, so this is what stops
        // naming being used to hide a layer the device never touches. Together the two passes and
        // the host gather must account for every index.
        let mut covered = std::collections::BTreeSet::new();
        for mode in [Mode::Image, Mode::Text { len: 1 }, Mode::Text { len: LEN }] {
            plan(mode);
            covered.extend(read_by(mode));
        }
        // The three the host reads and no shader does: the token embedding, its scale, and the
        // text position table. `embed_positions` is the only reader of all three.
        covered.extend(TOKENS..TEXT);
        assert_eq!(covered.into_iter().collect::<Vec<_>>(), (0..TENSORS).collect::<Vec<_>>());
    }

    #[test]
    fn the_vision_tower_is_ten_pre_norm_layers_over_a_patch_grid() {
        let plan = plan(Mode::Image);
        let counts = counts(&plan);
        // Six int8 convolutions per layer, plus the patch embedding and the projection head.
        assert_eq!(counts.get("ConvInt8"), Some(&(VISION_LAYERS * 6 + 2)), "{counts:?}");
        // Three norms per layer would be post-norm. Pre-norm is two, plus `pre_layrnorm` and
        // `post_layernorm`.
        assert_eq!(counts.get("LayerNorm"), Some(&(VISION_LAYERS * 2 + 2)), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&VISION_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&VISION_LAYERS), "{counts:?}");
        // Not causal: an image's patches all see each other.
        assert_eq!(counts.get("Softmax"), Some(&VISION_LAYERS), "{counts:?}");
        assert_eq!(counts.get("SoftmaxCausal"), None, "{counts:?}");
        // Two residuals per layer, plus the position table.
        assert_eq!(counts.get("Add"), Some(&(VISION_LAYERS * 2 + 1)), "{counts:?}");
        assert_eq!(counts.get("Constant"), Some(&2), "{counts:?}");
        assert_eq!(counts.len(), 7, "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_text_tower_is_three_causal_layers() {
        let plan = plan(Mode::Text { len: LEN });
        let counts = counts(&plan);
        assert_eq!(counts.get("ConvInt8"), Some(&(TEXT_LAYERS * 6 + 1)), "{counts:?}");
        assert_eq!(counts.get("LayerNorm"), Some(&(TEXT_LAYERS * 2 + 1)), "{counts:?}");
        // Every softmax is causal, and there is no plain one anywhere in this pass. A single plain
        // one would let one layer read the future, which is fluent and wrong.
        assert_eq!(counts.get("SoftmaxCausal"), Some(&TEXT_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), None, "{counts:?}");
        assert_eq!(counts.get("Add"), Some(&(TEXT_LAYERS * 2)), "{counts:?}");
        // No class token and no device-side position table: the host built the input.
        assert_eq!(counts.get("Constant"), None, "{counts:?}");
        assert_eq!(counts.len(), 6, "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_patch_embedding_is_a_stride_sixteen_convolution_over_the_whole_image() {
        // The one convolution here that is not `1 x 1`. A wrong stride gives the right rank and the
        // wrong grid, and nothing downstream checks the sequence length against 197.
        let plan = plan(Mode::Image);
        let patch = plan
            .ops
            .iter()
            .find_map(|op| match op {
                Op::Dispatch { kind: Kind::ConvInt8, push, .. } if push.in_c == 3 => Some(*push),
                _ => None,
            })
            .expect("the patch embedding");
        assert_eq!((push_kernel(&patch), push_stride(&patch)), ((PATCH, PATCH), (PATCH, PATCH)));
        assert_eq!((patch.pad_t, patch.pad_l), (0, 0), "{patch:?}");
        assert_eq!((patch.in_h, patch.in_w), (IMAGE_SIZE, IMAGE_SIZE), "{patch:?}");
        assert_eq!((patch.out_c, patch.out_h, patch.out_w), (WIDTH, GRID, GRID), "{patch:?}");
    }

    fn push_kernel(push: &super::super::Push) -> (u32, u32) {
        (push.kh, push.kw)
    }

    fn push_stride(push: &super::super::Push) -> (u32, u32) {
        (push.stride_h, push.stride_w)
    }

    #[test]
    fn the_class_token_is_prepended_and_the_positions_are_added_after() {
        // 197 = 1 + 14 * 14, and the class token is position **0**. Appending it instead would put
        // the pooled vector at the end and shift every position embedding by one — a change no
        // shape catches.
        let plan = plan(Mode::Image);
        let copies: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match *op {
                Op::Copy { src, dst, elems } => Some((src, dst, elems)),
                _ => None,
            })
            .collect();
        // The reshape is one copy of the whole grid; the position concat is one run per channel per
        // part, so `WIDTH` of one element and `WIDTH` of 196.
        let single: Vec<_> = copies.iter().filter(|(_, _, elems)| *elems == 1).collect();
        let runs: Vec<_> = copies.iter().filter(|(_, _, elems)| *elems == GRID * GRID).collect();
        let whole: Vec<_> =
            copies.iter().filter(|(_, _, elems)| *elems == WIDTH * GRID * GRID).collect();
        assert_eq!(single.len(), WIDTH as usize, "{copies:?}");
        assert_eq!(runs.len(), WIDTH as usize, "{copies:?}");
        // And the reshape, which is one contiguous move of the whole grid because
        // `[WIDTH, GRID, GRID]` and `[WIDTH, 1, GRID * GRID]` are the same bytes.
        assert_eq!(whole.len(), 1, "{copies:?}");
        assert_eq!(copies.len(), 2 * WIDTH as usize + 1, "{copies:?}");
        // The class token's copies land at stride 197 starting at the sequence's base, and the
        // patch runs land one column later.
        let base = single.iter().map(|(_, dst, _)| *dst).min().expect("a class copy");
        for (index, (_, dst, _)) in single.iter().enumerate() {
            assert_eq!(*dst, base + index as u32 * VISION_POSITIONS, "{copies:?}");
        }
        for (index, (_, dst, _)) in runs.iter().enumerate() {
            assert_eq!(*dst, base + 1 + index as u32 * VISION_POSITIONS, "{copies:?}");
        }

        // And the position table is added to the 197-long sequence, not the 196-long grid.
        let added = plan
            .ops
            .iter()
            .find_map(|op| match op {
                Op::Dispatch { kind: Kind::Add, push, .. } if push.out_w == VISION_POSITIONS => {
                    Some(*push)
                }
                _ => None,
            })
            .expect("the position add");
        assert_eq!(added.out_c, WIDTH, "{added:?}");
    }

    #[test]
    fn both_towers_project_every_position_so_the_host_can_pool_one() {
        // The vision tower's embedding is column 0 and the text tower's the last column, and
        // neither is contiguous — so the projection runs over the whole sequence and the host picks.
        // Swapping which column is read is the mistake this documents; nothing here can catch it,
        // which is why both output widths are pinned.
        let image = plan(Mode::Image);
        assert_eq!(image.input().unwrap().shape, Shape::new(3, IMAGE_SIZE, IMAGE_SIZE));
        assert_eq!(image.output().unwrap().shape, Shape::new(PROJECTION, 1, VISION_POSITIONS));

        let text = plan(Mode::Text { len: LEN });
        assert_eq!(text.input().unwrap().shape, Shape::new(WIDTH, 1, LEN));
        assert_eq!(text.output().unwrap().shape, Shape::new(PROJECTION, 1, LEN));
    }

    #[test]
    fn the_text_score_maps_are_square_so_the_causal_mask_means_something() {
        // A causal mask is a statement about one sequence attending to itself, so the map has to be
        // `[heads, T, T]`. `Builder::softmax_causal` refuses anything else; this is what says the
        // tower never asks it to.
        let plan = plan(Mode::Text { len: LEN });
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::SoftmaxCausal, push, .. } = op {
                assert_eq!((push.out_c, push.out_h, push.out_w), (HEADS, LEN, LEN), "{push:?}");
            }
        }
    }

    #[test]
    fn a_single_token_query_still_builds() {
        // `len = eot + 1`, and the shortest possible query is `<|startoftext|><|endoftext|>` at
        // `eot = 1`. A length of 1 is what a caller passing `eot` rather than `eot + 1` on that
        // query would produce, so it has to build rather than panic — the causal softmax's row-0
        // distribution is `[1]`, which is exactly the fixture in `nets::reference`.
        let plan = plan(Mode::Text { len: 1 });
        assert_eq!(plan.output().unwrap().shape, Shape::new(PROJECTION, 1, 1));
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_pass_over_nothing_or_past_the_table_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, Mode::Text { len: 0 }).expect_err("no tokens");
        assert!(error.contains("no tokens"), "{error}");
        let source = Shapes::new(TENSORS);
        let error =
            build(&source, Mode::Text { len: CONTEXT + 1 }).expect_err("too long");
        assert!(error.contains("positions the model has"), "{error}");
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // `dims_of` restates the table `maml_convert.collect_tinyclip` writes, and `Layers` walks
        // it. This checks the two agree for every index, which is what makes `host_tensor`'s shape
        // check meaningful rather than circular.
        let projection = |out: u32, inputs: u32| {
            vec![vec![out, inputs, 1, 1], vec![out], vec![out]]
        };
        let norm = || vec![vec![WIDTH], vec![WIDTH]];
        let layer = || {
            let mut out = norm();
            for _ in 0..4 {
                out.extend(projection(WIDTH, WIDTH));
            }
            out.extend(norm());
            out.extend(projection(FFN, WIDTH));
            out.extend(projection(WIDTH, FFN));
            out
        };

        let mut expected: Vec<Vec<u32>> = Vec::new();
        expected.extend(projection(WIDTH, 3));
        // The patch kernel is the one that is not `1 x 1`, so its dims are restated by hand.
        expected[0] = vec![WIDTH, 3, PATCH, PATCH];
        expected.push(vec![WIDTH, 1, 1]);
        expected.push(vec![WIDTH, 1, VISION_POSITIONS]);
        expected.extend(norm());
        for _ in 0..VISION_LAYERS {
            expected.extend(layer());
        }
        expected.extend(norm());
        expected.extend(projection(PROJECTION, WIDTH));
        // The token embedding is a pair: kernel and per-row scale, and no bias.
        expected.push(vec![VOCAB, WIDTH, 1, 1]);
        expected.push(vec![VOCAB]);
        expected.push(vec![CONTEXT, WIDTH]);
        for _ in 0..TEXT_LAYERS {
            expected.extend(layer());
        }
        expected.extend(norm());
        expected.extend(projection(PROJECTION, WIDTH));

        assert_eq!(expected.len(), TENSORS);
        for (index, want) in expected.iter().enumerate() {
            assert_eq!(&dims_of(index), want, "tensor {index}");
        }
    }
}
