//! whisper-base: a 30-second audio encoder and a KV-cached decoder, in one `.maml`.
//!
//! # What it is
//!
//! An encoder-decoder transformer over log-mel spectrograms: a two-convolution stem that turns
//! `[80, 3000]` mel frames into 1500 positions, then 6 encoder layers; 6 decoder layers with self
//! and cross attention; `d_model` 512, 8 heads of 64, a 2048-wide feed-forward, and a 51,865-entry
//! vocabulary shared between the input embedding and the logits projection. 72,593,920 parameters,
//! 70.6 MiB as int8.
//!
//! It replaces onnxruntime running two int8 ONNX exports totalling 76.9 MB
//! (`speech/src/main/assets/whisper-base/*.onnx`), and with them the last `onnxruntime-android`
//! dependency in the tree — ~27 MB of native `.so`, which was 35% of the `:speech` APK.
//!
//! It is also **four times closer** to the fp32 checkpoint than what it replaces. Over the encoder's
//! `[1500, 512]` output on one deterministic window, against an fp32 reference computed from the
//! checkpoint directly:
//!
//! | | max | mean | correlation |
//! | :--- | ---: | ---: | ---: |
//! | the shipped `encoder_model_int8.onnx` | 14.33 | 0.1101 | 0.995204 |
//! | this `.maml` | **3.19** | **0.0293** | **0.999633** |
//!
//! on a tensor whose largest value is 22.8. That gap is per-output-channel quantisation against the
//! export's per-tensor dynamic quantisation, the same difference `nets::small100` was ported for.
//! `scripts/ml/onnx_parity.py whisper` reproduces the middle column of it.
//!
//! # The arithmetic, measured rather than estimated
//!
//! ~43.7 GMAC per 30-second window in the encoder, regardless of how much speech is in it: 7.02 GMAC
//! per layer — 1.57 in the four projections, 3.15 in the feed-forward and 2.30 in the
//! `[8, 1500, 1500]` attention — plus 1.55 in the conv stem. A decode step is 60 MMAC by comparison.
//! Whether that is usable is a device question and the one open item in this port.
//!
//! # One file, two passes
//!
//! [`Mode`] selects which. They share the file because the embedding is **tied**: it is the
//! decoder's input table and the logits kernel, and two files would upload 26.6 MB of it twice. At
//! 51,865 x 512 that binding is well inside `maxStorageBufferRange`'s guaranteed 128 MiB, so unlike
//! `nets::small100`'s 128,112-row head it needs no class split.
//!
//! # The encoder produces the cross-attention keys and values, not the hidden states
//!
//! Whisper's cross-attention reads the encoder output through each decoder layer's own `k_proj` and
//! `v_proj`, and those depend on nothing that changes between steps. Recomputing them per step is
//! what `nets::small100` does, and it is fine there because a source sentence is tens of positions.
//! Here it is **1500**: twelve `512 x 512` projections over 1500 positions is 4.7 GMAC *per decode
//! step*, against the 26.5 MMAC of the logits head. That is 177 times the head, and at 224 tokens it
//! is a thousand GMAC.
//!
//! So [`Mode::Encode`] runs them once and hands back **twelve** `[512, 1, 1500]` tensors, and
//! [`Mode::DecodeStep`] takes them as inputs. The host then re-uploads 18.4 MB per step, which at a
//! few GB/s is an order of magnitude cheaper than recomputing them — measured against arithmetic, not
//! on a device, and flagged for the device. Nothing is transposed: a single-query cross-attention
//! reads a channel-major sequence through the ordinary [`Builder::attn_scores`] pair, exactly as
//! `nets::small100`'s does.
//!
//! # The two position tables go opposite ways
//!
//! Both are real tensors in the checkpoint, not computed sinusoids. The **encoder's** `[1500, 512]`
//! is added to the conv stem's output, which is a device tensor, so the converter transposes it to
//! `[512, 1, 1500]` and it arrives as a [`crate::nets::Kind::Constant`]. The **decoder's**
//! `[448, 512]` is added to a gathered embedding row on the *host*, in f32, as
//! [`embed_positions`] does — so it is left `[448, 512]` and never reaches a shader.
//!
//! There is no `sqrt(d_model)` on the embedding: `config.json` has `scale_embedding: false`.
//!
//! # The conv stem's stride is what sets the sequence length
//!
//! `conv1` is `1 x 3` stride 1 and `conv2` is `1 x 3` **stride 2**, both `same`-padded, both
//! followed by GELU. 3000 mel frames therefore become 1500 encoder positions. A wrong stride gives
//! the right rank and the wrong length, and nothing downstream checks the length — which is why
//! `tests::the_conv_stem_halves_the_frame_count` pins it.
//!
//! `conv2` is 1.18 GMAC on [`crate::nets::Kind::ConvInt8`]'s untiled path, because the tiled int8
//! shader is `1 x 1` only. Flagged to measure rather than pre-optimised.
//!
//! # No attention mask, and none needed
//!
//! * The encoder attends over one 30-second window with no padding, so every key is real.
//! * The decoder decodes one token at a time, so a step is **one query against `cache_len + 1`
//!   keys**, which is causal by construction. That is what [`Builder::attn_scores_cached`] is for.
//! * The cross-attention is one query over all 1500 encoder positions.
//!
//! [`Builder::attn_scores`] applies `1 / sqrt(head_dim)` itself and `head_dim` is 64, which is
//! exactly `WhisperAttention`'s own scaling. So nothing folds a query scale.
//!
//! # Pre-norm
//!
//! `WhisperEncoderLayer` and `WhisperDecoderLayer` normalise **before** each sublayer and skip
//! around both, and each stack ends with one more layer norm. Written the other way round the net
//! still runs and still produces words.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use crate::weights::Reader;

/// Channels throughout: `d_model`.
pub const D_MODEL: u32 = 512;

/// Attention heads, so `head_dim` is 64 and [`Builder::attn_scores`]'s own scale is the model's.
pub const HEADS: u32 = 8;

/// The feed-forward width, `encoder_ffn_dim` and `decoder_ffn_dim`.
pub const FFN: u32 = 2048;

/// Mel bins the front end produces, `num_mel_bins`.
pub const MELS: u32 = 80;

/// Mel frames in one 30-second window, which is all whisper ever consumes.
pub const MEL_FRAMES: u32 = 3000;

/// Encoder positions, `max_source_positions`. [`MEL_FRAMES`] after `conv2`'s stride 2.
pub const SOURCE_POSITIONS: u32 = MEL_FRAMES / 2;

/// Decoder positions, `max_target_positions`, and so the longest transcript of one window.
pub const MAX_POSITIONS: u32 = 448;

/// Vocabulary entries, shared by the embedding and the logits projection.
pub const VOCAB: u32 = 51_865;

/// Encoder layers.
pub const ENCODER_LAYERS: usize = 6;

/// Decoder layers.
pub const DECODER_LAYERS: usize = 6;

/// The conv stem's kernel width. Both convolutions are `1 x 3`, `same`-padded.
const CONV_KERNEL: u32 = 3;

/// The epsilon in every layer norm.
const EPSILON: f32 = 1e-5;

/// Tensors per encoder layer: two norms of two, four projections of three, two more of three.
const ENCODER_LAYER_TENSORS: usize = 2 + 4 * 3 + 2 + 3 + 3;

/// Tensors per decoder layer: an encoder layer plus a cross-attention norm and four projections.
const DECODER_LAYER_TENSORS: usize = ENCODER_LAYER_TENSORS + 2 + 4 * 3;

/// `conv1`: int8 kernel, per-channel scale, bias.
const CONV1: usize = 0;

/// `conv2`, the stride-2 one.
const CONV2: usize = CONV1 + 3;

/// The encoder position table, transposed to `[D_MODEL, 1, SOURCE_POSITIONS]`.
const ENC_POSITIONS: usize = CONV2 + 3;

/// The first encoder layer.
const ENCODER: usize = ENC_POSITIONS + 1;

/// The encoder's trailing layer norm.
const ENC_NORM: usize = ENCODER + ENCODER_LAYERS * ENCODER_LAYER_TENSORS;

/// The tied embedding: int8 kernel, per-class scale, synthesised zero bias.
///
/// Two roles, one tensor. [`embed_positions`] gathers a row of it on the host to build a decode
/// step's input, and [`Mode::DecodeStep`] binds the same tensor as the logits kernel.
const HEAD: usize = ENC_NORM + 2;

/// The decoder position table, left `[MAX_POSITIONS, D_MODEL]` for the host.
const DEC_POSITIONS: usize = HEAD + 3;

/// The first decoder layer.
const DECODER: usize = DEC_POSITIONS + 1;

/// The decoder's trailing layer norm.
const DEC_NORM: usize = DECODER + DECODER_LAYERS * DECODER_LAYER_TENSORS;

/// Tensors the `.maml` must hold, and the count `maml_convert.py` writes.
pub const TENSORS: usize = DEC_NORM + 2;

/// Convolutions read as int8 rather than fp16, each carrying a third tensor for its scale.
///
/// The conv stem's two, all six projections of each encoder layer, all ten of each decoder layer,
/// and the tied head. Only the layer norms, the biases and the two position tables stay fp16, and
/// together they are 1.5% of the parameters.
pub const INT8_CONVS: usize = 2 + ENCODER_LAYERS * 6 + DECODER_LAYERS * 10 + 1;

/// Which forward pass [`build`] emits.
///
/// One file and two plans, run through [`crate::vulkan::run::Net::rebuild`] rather than two nets, so
/// the 70.6 MiB upload happens once.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// The audio encoder, and every decoder layer's cross-attention K and V.
    ///
    /// `[80, 1, 3000]` in — the log-mel window. **Thirteen** outputs: the `[512, 1, 1500]` hidden
    /// states, then layer 0's cross K and V, layer 1's, and so on, all the same shape.
    ///
    /// The hidden states are not needed by [`Mode::DecodeStep`] — the caches are what it reads — but
    /// they are what `scripts/ml/onnx_parity.py` compares against the reference export, and the
    /// readback is 1.5 MB once per utterance.
    Encode,
    /// One decoder step against a `cache_len`-position self-attention cache.
    ///
    /// A step is **one query** against `cache_len + 1` keys — the positions already decoded plus
    /// this one — which is causal by construction and needs no mask.
    DecodeStep {
        /// Positions already in the self-attention cache, which is also the step number.
        cache_len: u32,
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

    /// Step over `count` projections this pass does not read. See [`Mode::Encode`].
    fn skip3(&mut self, count: usize) {
        self.next += count * 3;
    }
}

/// A `1 x 1` convolution with an int8 kernel, which every projection here is.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv_int8(x, l.take3(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// The pre-norm feed-forward sublayer, plus its residual. GELU, not ReLU.
fn feed_forward(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let normed = b.layer_norm(x, l.take(), EPSILON);
    let inner = point(b, l, normed, FFN, Act::Gelu);
    let projected = point(b, l, inner, D_MODEL, Act::None);
    b.add(x, projected)
}

/// The `.maml` index of decoder layer `layer`'s first tensor.
fn decoder_layer(layer: usize) -> usize {
    DECODER + layer * DECODER_LAYER_TENSORS
}

/// Decoder layer `layer`'s cross-attention **key** projection triple. The value triple follows it.
///
/// Derived from the layout rather than tabulated, because [`Mode::Encode`] reads exactly these two
/// triples out of each decoder layer and [`Mode::DecodeStep`] reads everything else: a wrong offset
/// here makes one pass read the other's weights.
fn cross_kv(layer: usize) -> usize {
    // A layer is: self norm (2), four self projections (12), cross norm (2), then cross q (3).
    decoder_layer(layer) + 2 + 4 * 3 + 2 + 3
}

/// Build one of whisper's two passes. See [`Mode`].
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    match mode {
        Mode::Encode => encode(weights),
        Mode::DecodeStep { cache_len } => decode_step(weights, cache_len),
    }
}

/// The audio encoder over one 30-second window, plus the twelve cross-attention caches.
fn encode(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    name_host_tensors(b, &device_tensors(Mode::Encode));

    let mel = b.input(Shape::new(MELS, 1, MEL_FRAMES));
    // `1 x 3` same-padded, GELU, stride 1 then stride 2. The second stride is what turns 3000 mel
    // frames into the 1500 positions the decoder cross-attends over.
    let l = &mut Layers { next: CONV1 };
    let x = b.conv_int8(
        mel,
        l.take3(),
        D_MODEL,
        (1, CONV_KERNEL),
        (1, 1),
        (1, 1),
        (0, 1, 0, 1),
        1,
        Act::Gelu,
    );
    let x = b.conv_int8(
        x,
        l.take3(),
        D_MODEL,
        (1, CONV_KERNEL),
        (1, 2),
        (1, 1),
        (0, 1, 0, 1),
        1,
        Act::Gelu,
    );
    let positions = b.constant(ENC_POSITIONS, Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
    let mut x = b.add(x, positions);

    let l = &mut Layers { next: ENCODER };
    for _ in 0..ENCODER_LAYERS {
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k = point(b, l, normed, D_MODEL, Act::None);
        let v = point(b, l, normed, D_MODEL, Act::None);
        let scores = b.attn_scores(q, k, HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);
        x = feed_forward(b, l, x);
    }
    if l.next != ENC_NORM {
        return Err(format!("the encoder claims {} tensors, not {ENC_NORM}", l.next));
    }
    let encoded = b.layer_norm(x, ENC_NORM, EPSILON);

    // Each decoder layer's cross-attention key and value, computed once for the whole transcript.
    let mut outputs = Vec::with_capacity(1 + DECODER_LAYERS * 2);
    outputs.push(encoded);
    for layer in 0..DECODER_LAYERS {
        let cross = &mut Layers { next: cross_kv(layer) };
        outputs.push(point(b, cross, encoded, D_MODEL, Act::None));
        outputs.push(point(b, cross, encoded, D_MODEL, Act::None));
        if cross.next != cross_kv(layer) + 6 {
            return Err(format!("layer {layer}'s cross cache ends at {}", cross.next));
        }
    }
    builder.finish(&outputs)
}

/// One decoder step.
///
/// # Inputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[512, 1, 1]` | the current token, after [`embed_positions`] at `past = cache_len` |
/// | 1..13 | `[512, 1, 1500]` | each layer's cross-attention K then V, from [`Mode::Encode`] |
/// | 13..25 | `[cache_len, 1, 512]` | each layer's self-attention K then V, position-major |
///
/// The self-attention pairs are **omitted at step 0**, where there is nothing before the current
/// token: a `[0, 1, 512]` binding would be a zero-length upload, which Vulkan refuses.
///
/// # Outputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[51865, 1, 1]` | the logits, which the host argmaxes |
/// | 1..13 | `[1, 1, 512]` | each layer's new self-attention K then V, ready to append |
fn decode_step(weights: &dyn WeightSource, cache_len: u32) -> Result<Plan, String> {
    if cache_len >= MAX_POSITIONS {
        return Err(format!("a cache of {cache_len}, at the {MAX_POSITIONS}-position limit"));
    }

    let mut builder = Builder::new(weights);
    let b = &mut builder;
    name_host_tensors(b, &device_tensors(Mode::DecodeStep { cache_len }));

    let mut x = b.input(Shape::new(D_MODEL, 1, 1));
    // Declared up front, in layer order, so the host uploads them as one block.
    let cross: Vec<(Id, Id)> = (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.input(Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
            let v = b.input(Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
            (k, v)
        })
        .collect();
    let past: Vec<Option<(Id, Id)>> = (0..DECODER_LAYERS)
        .map(|_| {
            (cache_len > 0).then(|| {
                let k = b.input(Shape::new(cache_len, 1, D_MODEL));
                let v = b.input(Shape::new(cache_len, 1, D_MODEL));
                (k, v)
            })
        })
        .collect();

    let mut produced: Vec<Id> = Vec::with_capacity(DECODER_LAYERS * 2);
    for (layer, (&(cross_k, cross_v), held)) in cross.iter().zip(&past).enumerate() {
        let l = &mut Layers { next: decoder_layer(layer) };

        // Self-attention, pre-norm, against the growing cache.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k_new = point(b, l, normed, D_MODEL, Act::None);
        let v_new = point(b, l, normed, D_MODEL, Act::None);
        // A projection writes `[d_model, 1, 1]`; a cache position is `[1, 1, d_model]`. The same
        // bytes, so this is a relabelling and the append below is one contiguous copy.
        let k_row = b.reshaped(k_new, Shape::new(1, 1, D_MODEL));
        let v_row = b.reshaped(v_new, Shape::new(1, 1, D_MODEL));
        let (k, v) = match *held {
            Some((past_k, past_v)) => (b.concat(&[past_k, k_row]), b.concat(&[past_v, v_row])),
            None => (k_row, v_row),
        };
        let scores = b.attn_scores_cached(q, k, HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply_cached(probs, v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);
        produced.push(k_row);
        produced.push(v_row);

        // Cross-attention over the encoder output. One query against 1500 channel-major keys, so it
        // uses the ordinary pair rather than the cached one, and needs no transpose.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        // The key and value projections were run by `Mode::Encode`; their results are the inputs.
        l.skip3(2);
        let scores = b.attn_scores(q, cross_k, HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, cross_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        x = feed_forward(b, l, x);
        if l.next != decoder_layer(layer + 1) {
            return Err(format!("layer {layer} ends at {}, not {}", l.next, decoder_layer(layer + 1)));
        }
    }
    let state = b.layer_norm(x, DEC_NORM, EPSILON);

    // The tied head, in the same plan: a separate pass would mean a second rebuild and a round trip
    // through the host for a `[512]` vector.
    let head = &mut Layers { next: HEAD };
    let logits = point(b, head, state, VOCAB, Act::None);
    if head.next != DEC_POSITIONS {
        return Err(format!("the head claims {} tensors, not {DEC_POSITIONS}", head.next));
    }
    let mut outputs = vec![logits];
    outputs.append(&mut produced);
    builder.finish(&outputs)
}

/// Every tensor `mode` reads on the **device**, in ascending order.
///
/// Not a range, unlike `nets::small100`'s: [`Mode::Encode`] reads the whole encoder *and* two
/// projections out of each decoder layer, because Whisper's cross-attention keys and values are a
/// decoder weight applied to an encoder result. See the module docs.
fn device_tensors(mode: Mode) -> Vec<usize> {
    let mut read: Vec<usize> = Vec::new();
    match mode {
        Mode::Encode => {
            read.extend(CONV1..HEAD);
            for layer in 0..DECODER_LAYERS {
                read.extend(cross_kv(layer)..cross_kv(layer) + 6);
            }
        }
        Mode::DecodeStep { .. } => {
            read.extend(HEAD..HEAD + 3);
            let skipped: std::collections::BTreeSet<usize> = (0..DECODER_LAYERS)
                .flat_map(|layer| cross_kv(layer)..cross_kv(layer) + 6)
                .collect();
            read.extend((DECODER..TENSORS).filter(|index| !skipped.contains(index)));
        }
    }
    read.sort_unstable();
    read
}

/// Name every tensor **outside** `read` as one this pass does not touch.
///
/// [`Builder::finish`] refuses an unread tensor, and neither pass reads the whole file. Declaring
/// the complement rather than listing it keeps the two in step.
fn name_host_tensors(b: &mut Builder, read: &[usize]) {
    for index in 0..TENSORS {
        if !read.contains(&index) {
            b.host_tensor(index, &dims_of(index));
        }
    }
}

/// The shape of tensor `index`, derived from the layout constants.
///
/// `host_tensor` checks it against the file, so this is a *second* statement of the table that
/// `maml_convert.collect_whisper` writes — which is the point: a converter and a runtime that
/// disagree about a shape fail here rather than on the device.
fn dims_of(index: usize) -> Vec<u32> {
    match index {
        CONV1 => vec![D_MODEL, MELS, 1, CONV_KERNEL],
        CONV2 => vec![D_MODEL, D_MODEL, 1, CONV_KERNEL],
        ENC_POSITIONS => vec![D_MODEL, 1, SOURCE_POSITIONS],
        HEAD => vec![VOCAB, D_MODEL, 1, 1],
        DEC_POSITIONS => vec![MAX_POSITIONS, D_MODEL],
        _ if index == HEAD + 1 || index == HEAD + 2 => vec![VOCAB],
        _ if (ENCODER..ENC_NORM).contains(&index) => {
            layer_dims((index - ENCODER) % ENCODER_LAYER_TENSORS, ENCODER_LAYER_TENSORS)
        }
        _ if (DECODER..DEC_NORM).contains(&index) => {
            layer_dims((index - DECODER) % DECODER_LAYER_TENSORS, DECODER_LAYER_TENSORS)
        }
        // The two convolutions' scales and biases, and the two trailing layer norms.
        _ => vec![D_MODEL],
    }
}

/// The shape of the `within`th tensor of a layer of `per_layer` tensors.
fn layer_dims(within: usize, per_layer: usize) -> Vec<u32> {
    // A layer is a sequence of groups: `[2]` for a norm, `[out, in, 1, 1] [out] [out]` for a
    // projection. Walking them is shorter than a table and cannot disagree with [`Layers`].
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

/// The embedded and positioned tokens for `ids`, in the channel-major layout the plan wants.
///
/// The `[D_MODEL, 1, ids.len()]` fp16 input to [`Mode::DecodeStep`], as f32 for the caller to
/// upload. `x[t] = embed_tokens[ids[t]] + embed_positions[past + t]`, both read from the file and
/// summed in f32 before anything is rounded.
///
/// `past` is how many positions precede these ids, which is the step number. There is **no offset**,
/// unlike `nets::small100`'s fairseq `+ 2`: whisper's first prompt token sits at position 0.
///
/// And no `sqrt(d_model)`: `config.json` has `scale_embedding: false`.
pub fn embed_positions(
    weights: Reader<'_>,
    ids: &[u32],
    past: u32,
) -> Result<Vec<f32>, String> {
    if ids.is_empty() {
        return Err("an embedding of no tokens".into());
    }
    let last = past as usize + ids.len();
    if last > MAX_POSITIONS as usize {
        return Err(format!("position {last} is past the model's {MAX_POSITIONS}-entry table"));
    }
    let table = weights.fp16(DEC_POSITIONS, &[MAX_POSITIONS, D_MODEL])?;
    let width = D_MODEL as usize;
    let mut out = vec![0.0f32; width * ids.len()];
    for (at, &id) in ids.iter().enumerate() {
        if id >= VOCAB {
            return Err(format!("token {id} is past the {VOCAB}-entry vocabulary"));
        }
        let embedding = weights.int8_row(HEAD, HEAD + 1, &[VOCAB, D_MODEL, 1, 1], id)?;
        let position = (past as usize + at) * width;
        for (channel, value) in embedding.iter().enumerate() {
            let learned = table
                .get(position + channel)
                .ok_or("the position table is shorter than the sequence")?;
            // Channel-major: this runtime indexes `[c, h, w]`, and both tables are `[w, c]`.
            let slot = out
                .get_mut(channel * ids.len() + at)
                .ok_or("an embedding row is wider than d_model")?;
            *slot = value + learned;
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

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
        // The numbers `maml_convert.py --graph whisper --print-layers` reports. A disagreement here
        // is a plan that reads one layer's weights as another's.
        assert_eq!((ENCODER_LAYER_TENSORS, DECODER_LAYER_TENSORS), (22, 36));
        assert_eq!((CONV1, CONV2, ENC_POSITIONS, ENCODER), (0, 3, 6, 7));
        assert_eq!((ENC_NORM, HEAD, DEC_POSITIONS, DECODER), (139, 141, 144, 145));
        assert_eq!((DEC_NORM, TENSORS), (361, 363));
        assert_eq!(INT8_CONVS, 99);
        assert_eq!(SOURCE_POSITIONS, 1500);
        // Cross-attention K sits 19 tensors into a decoder layer, and V three after it.
        assert_eq!(cross_kv(0), DECODER + 19);
        assert_eq!(cross_kv(1), DECODER + DECODER_LAYER_TENSORS + 19);
    }

    #[test]
    fn the_parameter_total_matches_the_checkpoint() {
        // What the file holds, from the layout `every_tensor_shape_is_stated_the_same_way_twice`
        // pins, against the checkpoint plus exactly the two things quantising adds.
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();

        // One fp16 scale per output channel of each of the 99 int8 convolutions.
        let layer_scales = u64::from(4 * D_MODEL + FFN + D_MODEL);
        let scales = 2 * u64::from(D_MODEL)
            + ENCODER_LAYERS as u64 * layer_scales
            + DECODER_LAYERS as u64 * (layer_scales + u64::from(4 * D_MODEL))
            + u64::from(VOCAB);
        assert_eq!(scales, 120_473);
        // And a zero bias for the tied head and for every `k_proj`, none of which has one.
        let keys = ENCODER_LAYERS as u64 + DECODER_LAYERS as u64 * 2;
        let synthesised = u64::from(VOCAB) + keys * u64::from(D_MODEL);
        assert_eq!(synthesised, 51_865 + 9216);

        assert_eq!(total, 72_593_920 + scales + synthesised);
    }

    #[test]
    fn the_passes_cover_the_file_and_every_one_of_them_builds() {
        // `Builder::finish` only checks that a tensor is read *or* named, so this is what stops
        // naming being used to hide a layer the device never touches. Together the two passes and
        // the host gather must account for every index.
        let mut covered = std::collections::BTreeSet::new();
        for mode in [Mode::Encode, Mode::DecodeStep { cache_len: 0 }, Mode::DecodeStep { cache_len: 7 }] {
            plan(mode);
            covered.extend(device_tensors(mode));
        }
        // The one the host reads and no shader does: the decoder position table. The tied embedding
        // is host-read too, but the decode step also binds it as the logits kernel.
        covered.insert(DEC_POSITIONS);
        assert_eq!(covered.into_iter().collect::<Vec<_>>(), (0..TENSORS).collect::<Vec<_>>());
    }

    #[test]
    fn the_two_passes_partition_the_cross_attention_projections() {
        // The one place the passes interleave: `Mode::Encode` reads each decoder layer's cross K and
        // V, and `Mode::DecodeStep` reads everything else in that layer. An overlap would mean one
        // pass computing what the other already did; a gap would leave a tensor unread by both, which
        // `the_passes_cover_the_file...` catches.
        let encode: std::collections::BTreeSet<usize> =
            device_tensors(Mode::Encode).into_iter().collect();
        let decode: std::collections::BTreeSet<usize> =
            device_tensors(Mode::DecodeStep { cache_len: 3 }).into_iter().collect();
        let both: Vec<usize> = encode.intersection(&decode).copied().collect();
        assert!(both.is_empty(), "read by both passes: {both:?}");
        for layer in 0..DECODER_LAYERS {
            for index in cross_kv(layer)..cross_kv(layer) + 6 {
                assert!(encode.contains(&index), "layer {layer} tensor {index}");
                assert!(!decode.contains(&index), "layer {layer} tensor {index}");
            }
            // And the cross-attention query and output projections belong to the decode step.
            assert!(decode.contains(&(cross_kv(layer) - 3)), "layer {layer} cross q");
            assert!(decode.contains(&(cross_kv(layer) + 6)), "layer {layer} cross out");
        }
    }

    #[test]
    fn the_conv_stem_halves_the_frame_count() {
        // 3000 mel frames to 1500 encoder positions, which is `conv2`'s stride 2 and nothing else.
        // A wrong stride gives the right rank and the wrong length, and nothing downstream checks it.
        let plan = plan(Mode::Encode);
        let convs: Vec<super::super::Push> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::ConvInt8, push, .. } if push.kw == CONV_KERNEL => {
                    Some(*push)
                }
                _ => None,
            })
            .collect();
        assert_eq!(convs.len(), 2, "{convs:?}");
        let (first, second) = (convs[0], convs[1]);
        assert_eq!((first.in_c, first.in_w), (MELS, MEL_FRAMES), "{first:?}");
        assert_eq!((first.stride_h, first.stride_w), (1, 1), "{first:?}");
        // Same-padded, so stride 1 holds the length.
        assert_eq!((first.out_c, first.out_h, first.out_w), (D_MODEL, 1, MEL_FRAMES), "{first:?}");
        assert_eq!((second.stride_h, second.stride_w), (1, 2), "{second:?}");
        assert_eq!(
            (second.out_c, second.out_h, second.out_w),
            (D_MODEL, 1, SOURCE_POSITIONS),
            "{second:?}"
        );
        // Both `same`-padded on the width only: a `1 x 3` kernel over a height-1 sequence.
        for push in [first, second] {
            assert_eq!((push.pad_t, push.pad_l, push.kh), (0, 1, 1), "{push:?}");
        }
    }

    #[test]
    fn the_encoder_is_six_pre_norm_layers_over_fifteen_hundred_positions() {
        let plan = plan(Mode::Encode);
        let counts = counts(&plan);
        // Six int8 convolutions per layer, the two conv-stem ones, and the twelve cross-attention
        // key and value projections this pass computes for the decoder.
        assert_eq!(
            counts.get("ConvInt8"),
            Some(&(ENCODER_LAYERS * 6 + 2 + DECODER_LAYERS * 2)),
            "{counts:?}"
        );
        // Three norms per layer would be post-norm. Pre-norm is two, plus one at the end.
        assert_eq!(counts.get("LayerNorm"), Some(&(ENCODER_LAYERS * 2 + 1)), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&ENCODER_LAYERS), "{counts:?}");
        // Not causal: an audio window's positions all see each other.
        assert_eq!(counts.get("SoftmaxCausal"), None, "{counts:?}");
        // Two residuals per layer, plus the position table.
        assert_eq!(counts.get("Add"), Some(&(ENCODER_LAYERS * 2 + 1)), "{counts:?}");
        assert_eq!(counts.get("Constant"), Some(&1), "{counts:?}");
        assert_eq!(counts.len(), 7, "{counts:?}");

        assert_eq!(plan.input().unwrap().shape, Shape::new(MELS, 1, MEL_FRAMES));
        // The hidden states, then twelve caches in layer order — all the same shape.
        assert_eq!(plan.outputs.len(), 1 + DECODER_LAYERS * 2);
        for binding in &plan.outputs {
            assert_eq!(binding.shape, Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
        }
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_encoder_score_maps_are_square_at_fifteen_hundred() {
        // Self-attention over one window, so queries and keys are the same positions. This is also
        // the shape the arena is dominated by: `[8, 1500, 1500]` is 36 MB of fp16.
        let plan = plan(Mode::Encode);
        let mut seen = 0;
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::AttnScores, push, .. } = op {
                assert_eq!(
                    (push.group, push.out_h, push.out_w),
                    (HEADS, SOURCE_POSITIONS, SOURCE_POSITIONS),
                    "{push:?}"
                );
                seen += 1;
            }
        }
        assert_eq!(seen, ENCODER_LAYERS);
    }

    #[test]
    fn report_the_encoder_arena() {
        // Not an assertion so much as the number the port's one open question needs, printed by
        // `cargo test -- --nocapture`. The arena is bound whole with `range(arena_size)`, and
        // `maxStorageBufferRange`'s guaranteed minimum is 128 MiB — so if this approaches that,
        // `vulkan::segment` is the machinery to reuse. Only a device reports the real limit.
        let plan = plan(Mode::Encode);
        let bytes = u64::from(plan.arena_elems) * 2;
        println!(
            "whisper encoder arena: {} KiB ({:.1} MiB) for {} ops",
            bytes / 1024,
            bytes as f64 / (1 << 20) as f64,
            plan.ops.len()
        );
        // One score map and its softmax are 36 MB each, so anything under that is a plan that is not
        // doing the work; anything over 128 MiB cannot be bound on a device at the guaranteed floor.
        let map = u64::from(HEADS) * u64::from(SOURCE_POSITIONS) * u64::from(SOURCE_POSITIONS) * 2;
        assert!(bytes > map, "{bytes} bytes is under one score map's {map}");
        assert!(bytes < 128 << 20, "{bytes} bytes is past the guaranteed binding range");
    }

    #[test]
    fn a_decode_step_is_six_layers_of_two_attentions() {
        let plan = plan(Mode::DecodeStep { cache_len: 3 });
        let counts = counts(&plan);
        // Eight int8 convolutions per layer — the four self-attention projections, the
        // cross-attention's query and output, and the two feed-forwards — plus the tied head. The
        // cross-attention's key and value belong to `Mode::Encode`.
        assert_eq!(counts.get("ConvInt8"), Some(&(DECODER_LAYERS * 8 + 1)), "{counts:?}");
        // Three norms per layer, pre-norm, plus the trailing one.
        assert_eq!(counts.get("LayerNorm"), Some(&(DECODER_LAYERS * 3 + 1)), "{counts:?}");
        // Self-attention is cached and single-query; the cross-attention is not.
        assert_eq!(counts.get("AttnScoresCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApplyCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&(DECODER_LAYERS * 2)), "{counts:?}");
        // Three residuals per layer.
        assert_eq!(counts.get("Add"), Some(&(DECODER_LAYERS * 3)), "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_declares_its_caches_in_and_out() {
        let cache_len = 5;
        let plan = plan(Mode::DecodeStep { cache_len });
        // The token, twelve cross caches, then twelve self caches.
        assert_eq!(plan.inputs.len(), 1 + DECODER_LAYERS * 4);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        for binding in &plan.inputs[1..1 + DECODER_LAYERS * 2] {
            // Channel-major, because a single-query cross-attention reads a sequence directly.
            assert_eq!(binding.shape, Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
        }
        for binding in &plan.inputs[1 + DECODER_LAYERS * 2..] {
            // Position-major: the cache's channels are the positions.
            assert_eq!(binding.shape, Shape::new(cache_len, 1, D_MODEL));
        }
        // The logits, then the step's own K and V per layer.
        assert_eq!(plan.outputs.len(), 1 + DECODER_LAYERS * 2);
        assert_eq!(plan.outputs[0].shape, Shape::new(VOCAB, 1, 1));
        for binding in &plan.outputs[1..] {
            assert_eq!(binding.shape, Shape::new(1, 1, D_MODEL));
        }
    }

    #[test]
    fn the_first_step_has_no_self_cache_but_still_has_the_cross_one() {
        // At step 0 the only self-attention key is the token itself, so there is nothing to
        // concatenate and no self-cache input. The cross caches are still there: they do not grow.
        let plan = plan(Mode::DecodeStep { cache_len: 0 });
        assert_eq!(plan.inputs.len(), 1 + DECODER_LAYERS * 2);
        assert_eq!(plan.outputs.len(), 1 + DECODER_LAYERS * 2);
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::AttnScoresCached, push, .. } = op {
                assert_eq!((push.group, push.out_w), (HEADS, 1), "{push:?}");
            }
        }
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_attends_over_the_prefix_and_the_whole_window() {
        let cache_len = 9;
        let plan = plan(Mode::DecodeStep { cache_len });
        for op in &plan.ops {
            match op {
                // Self-attention: one query, `cache_len + 1` keys. An off-by-one here would drop the
                // current token from its own attention, which is fluent and wrong.
                Op::Dispatch { kind: Kind::AttnScoresCached, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, cache_len + 1), "{push:?}");
                }
                Op::Dispatch { kind: Kind::AttnApplyCached, push, .. } => {
                    assert_eq!((push.in_w, push.out_c), (cache_len + 1, D_MODEL), "{push:?}");
                }
                // Cross-attention: one query over all 1500 encoder positions.
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, SOURCE_POSITIONS), "{push:?}");
                }
                _ => {}
            }
        }
    }

    #[test]
    fn a_decode_step_is_single_position_so_its_int8_work_is_a_gemv() {
        // Why `Kind::ConvVecInt8` exists, and why it matters more here than in SMaLL-100: *every*
        // int8 convolution in a whisper decode step is one position, including the 26.6 MB head,
        // because the cross-attention's multi-position key and value projections moved to the
        // encoder pass.
        let plan = plan(Mode::DecodeStep { cache_len: 3 });
        let int8: Vec<(Kind, super::super::Push)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind, push, .. }
                    if super::super::tests::name_of(*kind) == "ConvInt8" =>
                {
                    Some((*kind, *push))
                }
                _ => None,
            })
            .collect();
        assert_eq!(int8.len(), DECODER_LAYERS * 8 + 1);
        for (kind, push) in &int8 {
            assert_eq!(*kind, Kind::ConvVecInt8, "{push:?}");
            assert_eq!(push.out_h * push.out_w, 1, "{push:?}");
        }
    }

    #[test]
    fn the_head_is_one_binding_under_the_guaranteed_range() {
        // Why whisper needs no class split where SMaLL-100 does: 51,865 x 512 int8 is 25.3 MiB
        // against a guaranteed 128 MiB, and even fp16 would fit.
        let whole = u64::from(VOCAB) * u64::from(D_MODEL);
        assert!(whole < 32 << 20, "the head is {whole} bytes");
        let plan = plan(Mode::DecodeStep { cache_len: 0 });
        let heads: Vec<&Op> = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { push, .. } if push.out_c == VOCAB))
            .collect();
        assert_eq!(heads.len(), 1, "{heads:?}");
    }

    #[test]
    fn a_step_past_the_position_table_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, Mode::DecodeStep { cache_len: MAX_POSITIONS })
            .expect_err("cache full");
        assert!(error.contains("position limit") || error.contains("limit"), "{error}");
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // `dims_of` restates the table `maml_convert.collect_whisper` writes, and `Layers` walks it.
        // This checks the two agree for every index, which is what makes `host_tensor`'s shape check
        // meaningful rather than circular.
        let projection = |out: u32, inputs: u32| {
            vec![vec![out, inputs, 1, 1], vec![out], vec![out]]
        };
        let norm = || vec![vec![D_MODEL], vec![D_MODEL]];
        let layer = |cross: bool| {
            let mut out = norm();
            for _ in 0..4 {
                out.extend(projection(D_MODEL, D_MODEL));
            }
            if cross {
                out.extend(norm());
                for _ in 0..4 {
                    out.extend(projection(D_MODEL, D_MODEL));
                }
            }
            out.extend(norm());
            out.extend(projection(FFN, D_MODEL));
            out.extend(projection(D_MODEL, FFN));
            out
        };

        let mut expected: Vec<Vec<u32>> = Vec::new();
        expected.push(vec![D_MODEL, MELS, 1, CONV_KERNEL]);
        expected.push(vec![D_MODEL]);
        expected.push(vec![D_MODEL]);
        expected.push(vec![D_MODEL, D_MODEL, 1, CONV_KERNEL]);
        expected.push(vec![D_MODEL]);
        expected.push(vec![D_MODEL]);
        expected.push(vec![D_MODEL, 1, SOURCE_POSITIONS]);
        for _ in 0..ENCODER_LAYERS {
            expected.extend(layer(false));
        }
        expected.extend(norm());
        expected.extend(projection(VOCAB, D_MODEL));
        expected.push(vec![MAX_POSITIONS, D_MODEL]);
        for _ in 0..DECODER_LAYERS {
            expected.extend(layer(true));
        }
        expected.extend(norm());

        assert_eq!(expected.len(), TENSORS);
        for (index, want) in expected.iter().enumerate() {
            assert_eq!(&dims_of(index), want, "tensor {index}");
        }
    }
}
