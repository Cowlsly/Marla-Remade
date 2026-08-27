//! The two hardcoded forward passes, and the small compiler they are written against.
//!
//! # Why a plan and not a graph interpreter
//!
//! Each network is code: [`u2netp::build`] and [`selfie::build`] call [`Builder`] in
//! the order the ONNX nodes run. There is no operator table, no name lookup and no
//! topology in the weights file — a `.vkml` is ordered tensors and nothing else.
//!
//! But the *shape* of the pass being code does not mean the offsets should be. So a
//! builder call records a [`Node`] against symbolic tensor ids, and [`Builder::finish`]
//! then does three things a hand-written pass would get wrong:
//!
//! 1. Propagates shapes, so every conv's output size is derived from its pads,
//!    stride and dilation rather than restated.
//! 2. Computes each tensor's last use and packs the arena with a free list, so U^2-Netp
//!    at 320x320 reuses memory instead of holding every intermediate at once.
//! 3. Resolves everything to `u32` element offsets in one place, which is the
//!    arithmetic the host tests check.
//!
//! The result is a flat [`Plan`] of [`Op`]s. `vulkan::run` records it into **one**
//! command buffer once, at construction, so an inference is an upload, one submit and
//! one readback — nothing here runs per frame.
//!
//! # Host-testable
//!
//! Nothing in this module or its children touches Vulkan. Builders take a
//! [`WeightSource`] rather than a [`crate::weights::Weights`], so `cargo test` builds
//! both real networks, in full, with no device and no asset.

/// A CPU implementation of every op, to check the resolved plans against. Test-only,
/// so it adds nothing to the shipped `.so`.
#[cfg(test)]
pub mod reference;
pub mod mobilefacenet;
pub mod ppocr_det;
pub mod ppocr_rec;
pub mod scrfd;
pub mod selfie;
pub mod u2netp;
pub mod vits_dec;

/// A `1 x c x h x w` fp16 tensor. Batch is always 1; neither net is ever batched.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Shape {
    /// Channels.
    pub c: u32,
    /// Height.
    pub h: u32,
    /// Width.
    pub w: u32,
}

impl Shape {
    /// A shape, for readability at the call sites in the net modules.
    pub const fn new(c: u32, h: u32, w: u32) -> Shape {
        Shape { c, h, w }
    }

    /// Elements, which for fp16 is bytes / 2. Not `usize`, because every consumer is
    /// a `u32` push constant or a `u32` device offset.
    // No `is_empty`: a zero-sized tensor is a bug the builder rejects, not a state worth
    // asking about.
    #[allow(clippy::len_without_is_empty)]
    pub fn len(&self) -> u32 {
        self.c * self.h * self.w
    }
}

/// The activation a layer folds into its own store, saving a full pass over the
/// output. U^2-Netp is 112 ReLUs over up to 6.5M elements each; not fusing them would
/// roughly double its memory traffic.
///
/// Fusing is not merely an optimisation for [`Act::PRelu`]: MobileFaceNet's 34 `PRelu`
/// nodes each follow a `Conv` directly, so there is no shape of graph in which one
/// would need to stand alone.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Act {
    /// Store the accumulator as-is.
    None,
    /// `max(x, 0)`.
    Relu,
    /// `x * clamp(x/6 + 0.5, 0, 1)`, i.e. ONNX `HardSwish` at its default alpha/beta.
    HardSwish,
    /// `1 / (1 + exp(-x))`.
    Sigmoid,
    /// ONNX `PRelu`: `x < 0 ? slope[c] * x : x`, one slope per output channel.
    ///
    /// Unlike the others this carries state — the slope's position in the `.vkml`
    /// tensor table, which [`Builder`] resolves to an offset like any other weight and
    /// passes down in [`Push::act_weight`]. A plain `Relu` is the same thing at slope
    /// zero, and is kept separate because it needs no memory traffic at all.
    PRelu(usize),
    /// `clamp(x, 0, 1)`.
    ///
    /// This is a **normalised** `HardSigmoid`. ONNX's is `clamp(alpha * x + beta, 0, 1)`
    /// and PP-OCRv5 uses two different alphas, but `alpha` and `beta` fold into the
    /// convolution's weight and bias — `scripts/ml/ppocr_fold.py` does it — so the
    /// runtime needs one parameterless clamp rather than an activation carrying two
    /// floats through the push block.
    Clip01,
    /// `x / (1 + exp(-x))`, i.e. ONNX `Sigmoid` multiplied by its own input.
    ///
    /// Seven uses in PP-OCRv5 recognition, where the export spells it out as
    /// `Mul(x, Sigmoid(x))`; the fold recognises that the way it recognises HardSwish.
    Swish,
    /// `tanh(x)`.
    ///
    /// One use: the last thing Piper's HiFi-GAN vocoder does, which is what bounds its
    /// output to a waveform. It follows `conv_post` directly, so unlike that net's
    /// LeakyReLUs it does fuse — see [`Kind::LeakyRelu`].
    Tanh,
}

impl Act {
    fn code(self) -> u32 {
        match self {
            Act::None => 0,
            Act::Relu => 1,
            Act::HardSwish => 2,
            Act::Sigmoid => 3,
            Act::PRelu(_) => 4,
            Act::Clip01 => 5,
            Act::Swish => 6,
            Act::Tanh => 7,
        }
    }
}

/// Which compute pipeline an [`Op::Dispatch`] wants.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// Dense, grouped or dilated convolution with a fused activation.
    Conv,
    /// Transposed convolution. One use, in the selfie net's 2x upsample to 256x256.
    ConvTranspose,
    /// Max pooling.
    MaxPool,
    /// Average pooling over an explicit window, floored and unpadded.
    ///
    /// One use: the pool that turns PP-OCRv5 recognition's `[480, 3, 80]` feature map
    /// into the `[480, 1, 40]` sequence its transformer reads, with kernel and stride
    /// both `(3, 2)`. The asymmetric window is the reason this carries `kh`/`kw` rather
    /// than one size, and the reason it is not [`Kind::GlobalAvgPool`] with a flag — that
    /// one reduces all of H and W to a single value and needs no window arithmetic.
    AvgPool,
    /// Bilinear resize, `half_pixel` coordinates.
    Resize,
    /// Nearest-neighbour resize, `asymmetric` coordinates and `floor` rounding.
    ///
    /// SCRFD's feature-pyramid upsamples are `mode=nearest`, which is a different
    /// pipeline rather than a flag on [`Kind::Resize`] because the two share no
    /// arithmetic: `src = floor(dst * in / out)` against `(dst + 0.5) * in / out - 0.5`.
    /// Running an FPN through the bilinear one blurs every lateral it adds.
    ResizeNearest,
    /// Mean over H and W, keeping C. The squeeze in a squeeze-excite block.
    GlobalAvgPool,
    /// Elementwise sum of two equal shapes.
    Add,
    /// `a * b` where `b` is `C x 1 x 1`. The excite in a squeeze-excite block.
    MulBroadcast,
    /// `x * scale + shift`, both scalars. PP-OCRv5's "learnable affine block".
    ///
    /// One pass over the data for two multiplies. It exists because these sit *after* an
    /// activation, so unlike everything else constant in that export they cannot be
    /// folded into the preceding convolution's weight.
    ///
    /// # Why they cannot all fold forward either
    ///
    /// Pushing one into the *following* convolution looks free: `conv(a * x + t)[m]` is
    /// `a * conv(x)[m] + t * sum(W[m])`. That identity holds at an interior pixel and
    /// **fails at a border one**, because a padded convolution reads zero outside the
    /// input rather than `t`, so the constant's real contribution there is `t` times the
    /// sum of only the in-bounds taps. The correction varies per output position, so it is
    /// not a bias and there is no fold.
    ///
    /// `scripts/ml/ppocr_fold.py` therefore folds an affine only into an *unpadded*
    /// convolution, which leaves 14 in detection and 16 in recognition — all of them
    /// feeding either a padded depthwise or a squeeze-excite's two branches.
    ///
    /// Getting this wrong is invisible to every structural check: the layer table, the
    /// tensor count and the digest are all unchanged, and only the weight *values* differ.
    /// `scripts/ml/onnx_parity.py` is what caught it.
    Affine,
    /// Layer normalisation **over the channel axis**, with a per-channel affine.
    ///
    /// # Why over channels
    ///
    /// PP-OCRv5's recogniser is a CNN feeding a small transformer — `d_model` 120, 8
    /// heads, two blocks — and this runtime keeps its sequences in the layout the CNN
    /// already produces: `[d_model, 1, T]`, features in channels and the sequence along
    /// the width. Two things fall out of that and neither is an accident:
    ///
    /// * **Every linear projection is a 1x1 convolution.** All five feed-forward `Gemm`s
    ///   and all six attention projections are already expressible, so they need no new
    ///   pipeline.
    /// * **Splitting `d_model` into heads is free.** `[120, 1, T]` read as `[8, 15, T]`
    ///   is the same bytes in the same order, so there is no `Permute` and no `Reshape`
    ///   anywhere in the attention — the attention pipelines take the head count in
    ///   [`Push::group`] and index accordingly.
    ///
    /// The cost is that the reduction here is strided rather than contiguous. At
    /// `d_model` 120 that is 120 loads a stride apart per position, which is nothing
    /// against what a contiguous layout would cost in permutes.
    LayerNorm,
    /// `S[h][i][j] = scale * sum_d Q[h][d][i] * K[h][d][j]`, attention's score map.
    ///
    /// Contracts over the *middle* axis of `[heads, head_dim, T]`, which is what makes
    /// the head split free — see [`Kind::LayerNorm`]. Takes the head count in
    /// [`Push::group`] and `1 / sqrt(head_dim)` in [`Push::param0_bits`].
    ///
    /// The output is `[heads, T, T]` with the key index innermost, so the row a
    /// [`Kind::Softmax`] normalises is contiguous.
    AttnScores,
    /// Softmax over the last axis, one row at a time.
    ///
    /// Kept separate from [`Kind::AttnScores`] rather than fused into it. Fusing would
    /// save writing a `heads * T * T` intermediate — 100 KiB at the recogniser's sizes,
    /// which is not a cost worth a shader that does two things — and would give up the
    /// per-score parallelism, since a fused pass has to be one invocation per *row* to
    /// see the whole distribution it is normalising.
    Softmax,
    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`, attention's weighted sum.
    ///
    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`, attention's weighted sum.
    ///
    /// Writes `[d_model, 1, T]`, so the head concatenation that normally follows
    /// attention is not an op: output channel `c` belongs to head `c / head_dim` and
    /// lands where the concatenated result wants it. Head count in [`Push::group`].
    AttnApply,
    /// `x < 0 ? alpha * x : x`, as a standalone pass. 16 uses in Piper's HiFi-GAN vocoder.
    ///
    /// The only activation here that is **not** fused into the layer before it, because in
    /// a HiFi-GAN ResBlock it is not behind a layer at all: the block is
    /// `h = h + conv(lrelu(h))`, so it sits in front of its convolution, and the tensor it
    /// reads is also the residual's other operand — folding it backwards would consume the
    /// skip connection.
    ///
    /// `alpha` arrives in [`Push::param0_bits`] rather than being hardcoded, because the
    /// export uses two slopes: 0.1 for the fifteen inside the upsampling stages and 0.01
    /// for the one in front of `conv_post`.
    ///
    /// Distinct from [`Act::PRelu`], which is the same function with a *learned* slope per
    /// channel read from the weights file.
    LeakyRelu,
}

/// The push-constant block every shader declares.
///
/// `repr(C)` so field order is declaration order, which is what the SPIR-V offsets
/// assume. Deliberately one block shared by every pipeline: it fits inside the
/// 128 bytes the spec guarantees (asserted in [`tests`]), so there is a single
/// pipeline layout, no uniform buffers and no descriptor writes after setup.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Push {
    /// Element offset of the first input in the activation arena.
    pub in0: u32,
    /// Element offset of the second input, for the binary ops.
    pub in1: u32,
    /// Element offset of the output in the arena.
    pub out: u32,
    /// Element offset of the kernel in the weights buffer.
    pub weight: u32,
    /// Element offset of the bias in the weights buffer.
    pub bias: u32,
    /// Input channels.
    pub in_c: u32,
    /// Input height.
    pub in_h: u32,
    /// Input width.
    pub in_w: u32,
    /// Output channels.
    pub out_c: u32,
    /// Output height.
    pub out_h: u32,
    /// Output width.
    pub out_w: u32,
    /// Kernel height.
    pub kh: u32,
    /// Kernel width.
    pub kw: u32,
    /// Vertical stride.
    pub stride_h: u32,
    /// Horizontal stride.
    pub stride_w: u32,
    /// Vertical dilation.
    pub dil_h: u32,
    /// Horizontal dilation.
    pub dil_w: u32,
    /// Padding above row 0. ONNX's `pads[0]`.
    pub pad_t: u32,
    /// Padding left of column 0. ONNX's `pads[1]`.
    pub pad_l: u32,
    /// Convolution groups. `group == in_c == out_c` is depthwise.
    pub group: u32,
    /// [`Act::code`].
    pub act: u32,
    /// Element offset of the per-channel slope [`Act::PRelu`] reads, in the weights
    /// buffer. Zero and unread for every other activation.
    pub act_weight: u32,
    /// [`Kind::Affine`]'s multiplier, or [`Kind::AttnScores`]'s `1 / sqrt(head_dim)`,
    /// as raw bits. Unread by everything else.
    ///
    /// `f32` bits rather than an `f32` field so [`Push`] stays all-`u32` and
    /// `push_bytes` can keep treating it as a plain byte block with no padding.
    pub param0_bits: u32,
    /// [`Kind::Affine`]'s addend, or [`Kind::LayerNorm`]'s epsilon.
    pub param1_bits: u32,
    /// Output elements, so an over-dispatched workgroup can bail.
    ///
    /// Not always the element count: [`Kind::LayerNorm`] and [`Kind::Softmax`] each run
    /// one invocation over a whole reduction, so for them this is the number of those.
    pub count: u32,
}

/// One step of a compiled forward pass.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Op {
    /// Run `kind` over `invocations` output elements.
    Dispatch {
        /// The pipeline to bind.
        kind: Kind,
        /// Its parameters.
        push: Push,
        /// Output elements, one per invocation.
        invocations: u32,
    },
    /// A contiguous copy inside the arena, in fp16 elements.
    ///
    /// This is how `Concat` along the channel axis is done, and it needs no shader:
    /// in NCHW a run of channels *is* contiguous, so concatenation is placing each
    /// part end to end. `vkCmdCopyBuffer` does that faster than a compute pass and
    /// is why there is no `concat.comp`.
    Copy {
        /// Source element offset.
        src: u32,
        /// Destination element offset.
        dst: u32,
        /// Elements to move.
        elems: u32,
    },
}

/// Where one of a net's inputs or outputs lives, and what shape it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Binding {
    /// Element offset in the activation arena.
    pub at: u32,
    /// The tensor's shape, so the host does not restate it.
    pub shape: Shape,
}

/// A compiled forward pass: what to run, and how much scratch it needs.
#[derive(Debug, PartialEq, Eq)]
pub struct Plan {
    /// In order. Each depends on the results of the ones before it.
    pub ops: Vec<Op>,
    /// Elements the activation arena must hold.
    pub arena_elems: u32,
    /// Where the preprocessed inputs go, in the order they were declared.
    ///
    /// A `Vec` rather than one binding because the models this runtime is growing into
    /// are not single-input: VITS's duration predictor takes three tensors and the
    /// SMaLL-100 decoder four. Both shipping nets declare exactly one.
    pub inputs: Vec<Binding>,
    /// Where the results come back from, in the order [`Builder::finish`] was given.
    ///
    /// SCRFD has **nine** — score, box and keypoint maps at each of three strides —
    /// which is the reason this is a list.
    pub outputs: Vec<Binding>,
}

impl Plan {
    /// The only input, for the nets that have exactly one.
    pub fn input(&self) -> Result<Binding, String> {
        match self.inputs.as_slice() {
            [only] => Ok(*only),
            other => Err(format!("this net has {} inputs, not one", other.len())),
        }
    }

    /// The only output, for the nets that have exactly one.
    pub fn output(&self) -> Result<Binding, String> {
        match self.outputs.as_slice() {
            [only] => Ok(*only),
            other => Err(format!("this net has {} outputs, not one", other.len())),
        }
    }
}

/// Where a net's weights come from.
///
/// An indirection purely so the net modules are host-testable: the real
/// implementation is [`crate::weights::Weights`], and [`tests::Shapes`] is a stub that
/// only checks the shapes it is asked for. That lets `cargo test` build both networks
/// in full with no `.vkml` on disk.
pub trait WeightSource {
    /// The fp16 element offset of tensor `index`, which must have shape `dims`.
    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String>;

    /// How many tensors there are, so a builder can insist it consumed all of them.
    fn count(&self) -> usize;
}

impl WeightSource for crate::weights::Weights {
    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        Ok(crate::weights::Weights::shaped(self, index, dims)?.elem_offset())
    }

    fn count(&self) -> usize {
        self.len()
    }
}

/// A tensor in the graph being built. Copy, so it can be passed and reused freely.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Id(usize);

/// An unresolved step, against [`Id`]s rather than offsets.
#[derive(Clone, Debug)]
enum Node {
    Conv {
        input: Id,
        out: Id,
        weight: u32,
        bias: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pad: (u32, u32),
        group: u32,
        act: Act,
        /// Resolved offset of [`Act::PRelu`]'s slope, zero otherwise.
        act_weight: u32,
        transpose: bool,
    },
    MaxPool {
        input: Id,
        out: Id,
        kernel: (u32, u32),
        stride: (u32, u32),
    },
    AvgPool {
        input: Id,
        out: Id,
        kernel: (u32, u32),
        stride: (u32, u32),
    },
    Resize {
        input: Id,
        out: Id,
        nearest: bool,
    },
    GlobalAvgPool {
        input: Id,
        out: Id,
    },
    Binary {
        kind: Kind,
        a: Id,
        b: Id,
        out: Id,
    },
    Concat {
        parts: Vec<Id>,
        out: Id,
    },
    Affine {
        input: Id,
        out: Id,
        scale: f32,
        shift: f32,
    },
    LayerNorm {
        input: Id,
        out: Id,
        gamma: u32,
        beta: u32,
        epsilon: f32,
    },
    AttnScores {
        q: Id,
        k: Id,
        out: Id,
        heads: u32,
        scale: f32,
    },
    Softmax {
        input: Id,
        out: Id,
    },
    LeakyRelu {
        input: Id,
        out: Id,
        alpha: f32,
    },
    AttnApply {
        probs: Id,
        v: Id,
        out: Id,
        heads: u32,
    },
}

/// Records a forward pass, then packs and resolves it.
pub struct Builder<'a> {
    weights: &'a dyn WeightSource,
    shapes: Vec<Shape>,
    nodes: Vec<Node>,
    /// Tensors that must keep a stable offset for the whole pass: the inputs and the
    /// outputs. Everything else is free to be reused once its last reader has run.
    pinned: Vec<Id>,
    error: Option<String>,
    inputs: Vec<Id>,
    /// One flag per tensor in the file, set when the pass reads it. See
    /// [`Builder::finish`], which insists every one was.
    read: Vec<bool>,
}

/// Arena allocations are aligned to this many fp16 elements, i.e. 16 bytes — the same
/// boundary `.vkml` aligns its tensors to.
const ALIGN_ELEMS: u32 = 8;

impl<'a> Builder<'a> {
    /// Start recording a pass. Declare its inputs with [`Builder::input`].
    pub fn new(weights: &'a dyn WeightSource) -> Builder<'a> {
        Builder {
            read: vec![false; weights.count()],
            weights,
            shapes: Vec::new(),
            nodes: Vec::new(),
            pinned: Vec::new(),
            error: None,
            inputs: Vec::new(),
        }
    }

    /// Declare an input of `shape`.
    ///
    /// Callable more than once, in which case [`Plan::inputs`] lists them in this
    /// order and the host must upload them in the same one.
    pub fn input(&mut self, shape: Shape) -> Id {
        let id = self.tensor(shape);
        self.inputs.push(id);
        self.pinned.push(id);
        id
    }

    fn tensor(&mut self, shape: Shape) -> Id {
        self.shapes.push(shape);
        Id(self.shapes.len() - 1)
    }

    /// The first error recorded, so the net modules can chain calls without a `?` on
    /// each of 119 layers and still fail loudly.
    fn fail(&mut self, message: String) {
        if self.error.is_none() {
            self.error = Some(message);
        }
    }

    fn shape_of(&self, id: Id) -> Shape {
        // Ids only come from `tensor`, so this cannot be out of range; a zero shape
        // is returned rather than panicking if a future refactor breaks that, because
        // `finish` will reject the plan anyway.
        self.shapes.get(id.0).copied().unwrap_or(Shape::new(0, 0, 0))
    }

    /// The shape of `id`, for the net modules that need a channel count to size the
    /// next layer — a squeeze-excite's expand stage, for instance.
    pub fn shape(&self, id: Id) -> Shape {
        self.shape_of(id)
    }

    /// A convolution, with ONNX's semantics: weights `[m, in_c/group, kh, kw]`, pads
    /// `[top, left, bottom, right]`, output size floor-divided.
    ///
    /// `weight_index` and `bias_index` are positions in the `.vkml` tensor table, so a
    /// net module reads as the ordered list of layers that it is.
    #[allow(clippy::too_many_arguments)]
    pub fn conv(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let splits = group != 0
            && in_shape.c.is_multiple_of(group)
            && m.is_multiple_of(group);
        if !splits {
            self.fail(format!(
                "tensor {weight_index}: {} in / {m} out channels do not split into \
                 {group} groups",
                in_shape.c
            ));
        }
        let per_group = in_shape.c.checked_div(group).unwrap_or(0);
        let weight = self.weight(weight_index, &[m, per_group, kh, kw]);
        let bias = self.weight(weight_index + 1, &[m]);

        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = conv_out(in_shape.h, kh, stride.0, dilation.0, pad_t + pad_b);
        let out_w = conv_out(in_shape.w, kw, stride.1, dilation.1, pad_l + pad_r);
        let act_weight = self.act_weight(act, m);
        let out = self.tensor(Shape::new(m, out_h, out_w));
        self.nodes.push(Node::Conv {
            input,
            out,
            weight,
            bias,
            kernel,
            stride,
            dilation,
            pad: (pad_t, pad_l),
            group,
            act,
            act_weight,
            transpose: false,
        });
        out
    }

    /// Resolve [`Act::PRelu`]'s slope tensor, which is `[channels, 1, 1]` in the ONNX
    /// exports this reads.
    fn act_weight(&mut self, act: Act, channels: u32) -> u32 {
        match act {
            Act::PRelu(index) => self.weight(index, &[channels, 1, 1]),
            _ => 0,
        }
    }

    /// The common case in both nets: `3x3` or `1x1`, stride 1, `pad == dilation`
    /// (which keeps the output size), one group.
    pub fn conv_same(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: u32,
        dilation: u32,
        act: Act,
    ) -> Id {
        // `pad == dilation` holds the spatial size only for an odd kernel, which is
        // the only case either net uses it for.
        let pad = if kernel == 1 { 0 } else { dilation };
        self.conv(
            input,
            weight_index,
            m,
            (kernel, kernel),
            (1, 1),
            (dilation, dilation),
            (pad, pad, pad, pad),
            1,
            act,
        )
    }

    /// A transposed convolution: weights `[in_c, m/group, kh, kw]`, output
    /// `(in - 1) * stride + dilation * (k - 1) + 1 - pads`.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_transpose(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        pads: (u32, u32, u32, u32),
        act: Act,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let weight = self.weight(weight_index, &[in_shape.c, m, kh, kw]);
        let bias = self.weight(weight_index + 1, &[m]);
        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = deconv_out(in_shape.h, kh, stride.0, pad_t + pad_b);
        let out_w = deconv_out(in_shape.w, kw, stride.1, pad_l + pad_r);
        let act_weight = self.act_weight(act, m);
        let out = self.tensor(Shape::new(m, out_h, out_w));
        self.nodes.push(Node::Conv {
            input,
            out,
            weight,
            bias,
            kernel,
            stride,
            dilation: (1, 1),
            pad: (pad_t, pad_l),
            group: 1,
            act,
            act_weight,
            transpose: true,
        });
        out
    }

    fn weight(&mut self, index: usize, dims: &[u32]) -> u32 {
        match self.read.get_mut(index) {
            Some(slot) => *slot = true,
            None => self.fail(format!(
                "tensor {index}: the file holds {}",
                self.weights.count()
            )),
        }
        match self.weights.shaped(index, dims) {
            Ok(offset) => offset,
            Err(e) => {
                self.fail(e);
                0
            }
        }
    }

    /// `2x2` stride-2 max pooling, which is the only pooling either net does.
    ///
    /// ONNX marks these `ceil_mode=1`, but every pooled extent in U^2-Netp is even
    /// (320 halves five times to 10), so ceil and floor agree and the distinction is
    /// deliberately not modelled. [`tests::u2netp_pools_only_even_extents`] holds that.
    pub fn max_pool_2x2(&mut self, input: Id) -> Id {
        let in_shape = self.shape_of(input);
        if !in_shape.h.is_multiple_of(2) || !in_shape.w.is_multiple_of(2) {
            self.fail(format!(
                "max_pool_2x2 on {}x{}: ceil_mode is not modelled, so an odd extent \
                 would silently drop a row",
                in_shape.h, in_shape.w
            ));
        }
        let out = self.tensor(Shape::new(in_shape.c, in_shape.h / 2, in_shape.w / 2));
        self.nodes.push(Node::MaxPool {
            input,
            out,
            kernel: (2, 2),
            stride: (2, 2),
        });
        out
    }

    /// Average pooling over `kernel` at `stride`, which must tile the input exactly.
    ///
    /// The one use is `(3, 2)` at `(3, 2)` on a `3 x 80` map, so it tiles. Refusing
    /// anything else is deliberate: a window that overhangs makes the divisor a question
    /// — ONNX has `count_include_pad` for it and the two answers differ — and the shader
    /// divides by `kh * kw` unconditionally. A ragged extent would silently scale the
    /// edge of the sequence.
    pub fn avg_pool(&mut self, input: Id, kernel: (u32, u32), stride: (u32, u32)) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let out_h = conv_out(in_shape.h, kh, stride.0, 1, 0);
        let out_w = conv_out(in_shape.w, kw, stride.1, 1, 0);
        for (axis, extent, k, s, out) in [
            ('h', in_shape.h, kh, stride.0, out_h),
            ('w', in_shape.w, kw, stride.1, out_w),
        ] {
            if out == 0 || out.saturating_sub(1) * s + k != extent {
                self.fail(format!(
                    "avg_pool of {k} at stride {s} does not tile {extent} along {axis}, so \
                     the divisor would not be the window size"
                ));
            }
        }
        let out = self.tensor(Shape::new(in_shape.c, out_h, out_w));
        self.nodes.push(Node::AvgPool { input, out, kernel, stride });
        out
    }
    /// Bilinear resize to `like`'s spatial size, which is how both shipping nets always
    /// use it — U^2-Net's `_upsample_like`, and the selfie net's decoder skips.
    pub fn resize_like(&mut self, input: Id, like: Id) -> Id {
        let target = self.shape_of(like);
        self.resize_to(input, target.h, target.w)
    }

    /// Bilinear resize to an explicit size.
    pub fn resize_to(&mut self, input: Id, h: u32, w: u32) -> Id {
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, h, w));
        self.nodes.push(Node::Resize { input, out, nearest: false });
        out
    }

    /// Nearest-neighbour resize to `like`'s spatial size — SCRFD's two FPN upsamples.
    pub fn resize_nearest_like(&mut self, input: Id, like: Id) -> Id {
        let target = self.shape_of(like);
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, target.h, target.w));
        self.nodes.push(Node::Resize { input, out, nearest: true });
        out
    }

    /// Mean over H and W, to `C x 1 x 1`.
    pub fn global_avg_pool(&mut self, input: Id) -> Id {
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, 1, 1));
        self.nodes.push(Node::GlobalAvgPool { input, out });
        out
    }

    /// Elementwise `a + b`. Shapes must match.
    pub fn add(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sa != sb {
            self.fail(format!("add of {sa:?} and {sb:?}"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::Add, a, b, out });
        out
    }

    /// `a * b` with `b` broadcast over H and W — the excite half of a squeeze-excite
    /// block, where `b` came from [`Builder::global_avg_pool`].
    pub fn mul_channel(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sb.c != sa.c || sb.h != 1 || sb.w != 1 {
            self.fail(format!("mul_channel of {sa:?} by {sb:?}, which is not Cx1x1"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::MulBroadcast, a, b, out });
        out
    }

    /// Concatenate along the channel axis. Spatial sizes must match.
    pub fn concat(&mut self, parts: &[Id]) -> Id {
        let shapes: Vec<Shape> = parts.iter().map(|&p| self.shape_of(p)).collect();
        let first = match shapes.first() {
            Some(&s) => s,
            None => {
                self.fail("concat of nothing".into());
                Shape::new(0, 0, 0)
            }
        };
        let mut channels = 0;
        for s in &shapes {
            if s.h != first.h || s.w != first.w {
                self.fail(format!("concat of {first:?} with {s:?}"));
            }
            channels += s.c;
        }
        let out = self.tensor(Shape::new(channels, first.h, first.w));
        self.nodes.push(Node::Concat { parts: parts.to_vec(), out });
        out
    }

    /// `x * scale + shift`, elementwise with scalar parameters. See [`Kind::Affine`].
    pub fn affine(&mut self, input: Id, scale: f32, shift: f32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Affine { input, out, scale, shift });
        out
    }

    /// Layer normalisation over the channel axis, with a per-channel affine.
    ///
    /// `weight_index` is the gamma tensor's position in the `.vkml` table; beta follows
    /// it, the way a convolution's bias follows its weight.
    pub fn layer_norm(&mut self, input: Id, weight_index: usize, epsilon: f32) -> Id {
        let shape = self.shape_of(input);
        let gamma = self.weight(weight_index, &[shape.c]);
        let beta = self.weight(weight_index + 1, &[shape.c]);
        let out = self.tensor(shape);
        self.nodes.push(Node::LayerNorm { input, out, gamma, beta, epsilon });
        out
    }

    /// Attention scores from `q` and `k`, both `[d_model, 1, T]`, into `[heads, T, T]`.
    ///
    /// The `1 / sqrt(head_dim)` scale is derived here rather than taken as an argument:
    /// it is a property of the head geometry, not a trained value, so there is no call
    /// site that could legitimately pass a different one.
    pub fn attn_scores(&mut self, q: Id, k: Id, heads: u32) -> Id {
        let (sq, sk) = (self.shape_of(q), self.shape_of(k));
        if sq != sk {
            self.fail(format!("attention over q {sq:?} and k {sk:?}"));
        }
        if sq.h != 1 {
            self.fail(format!(
                "attention on {sq:?}: a sequence is [d_model, 1, T], so a height above \
                 one would silently reinterpret the layout"
            ));
        }
        if heads == 0 || !sq.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sq.c));
        }
        let head_dim = sq.c.checked_div(heads).unwrap_or(0);
        let scale = 1.0 / (head_dim.max(1) as f32).sqrt();
        let out = self.tensor(Shape::new(heads, sq.w, sq.w));
        self.nodes.push(Node::AttnScores { q, k, out, heads, scale });
        out
    }

    /// Softmax over the last axis, which for a score map is one query's distribution.
    pub fn softmax(&mut self, input: Id) -> Id {
        let shape = self.shape_of(input);
        if shape.w == 0 {
            self.fail(format!("a softmax over {shape:?}, whose last axis is empty"));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax { input, out });
        out
    }

    /// `x < 0 ? alpha * x : x`, as its own pass. See [`Kind::LeakyRelu`] for why it is not
    /// fused into the layer before it.
    pub fn leaky_relu(&mut self, input: Id, alpha: f32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::LeakyRelu { input, out, alpha });
        out
    }

    /// Apply `probs`, a `[heads, T, T]` score map, to `v`, a `[d_model, 1, T]` sequence.
    pub fn attn_apply(&mut self, probs: Id, v: Id, heads: u32) -> Id {
        let (sp, sv) = (self.shape_of(probs), self.shape_of(v));
        if sp != Shape::new(heads, sv.w, sv.w) {
            self.fail(format!(
                "attention weights {sp:?} do not match {heads} heads over a sequence of \
                 {} from {sv:?}",
                sv.w
            ));
        }
        if sv.h != 1 {
            self.fail(format!("attention values {sv:?} are not [d_model, 1, T]"));
        }
        if heads == 0 || !sv.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sv.c));
        }
        let out = self.tensor(sv);
        self.nodes.push(Node::AttnApply { probs, v, out, heads });
        out
    }

    /// Pack the arena and resolve every offset, with `outputs` as the result tensors.
    pub fn finish(mut self, outputs: &[Id]) -> Result<Plan, String> {
        self.pinned.extend_from_slice(outputs);
        if let Some(e) = self.error.take() {
            return Err(e);
        }
        if self.inputs.is_empty() {
            return Err("a pass with no input".into());
        }
        if outputs.is_empty() {
            return Err("a pass with no output".into());
        }
        // Every tensor in the file must have been read. An unread one is the shape of a
        // forward pass that stopped early or skipped a layer, which is otherwise
        // invisible — the file loads, the pass runs, and one layer convolves with
        // whatever its neighbour's weights happen to be.
        if let Some(index) = self.read.iter().position(|&read| !read) {
            return Err(format!(
                "the forward pass never reads tensor {index} of {}",
                self.read.len()
            ));
        }

        let last_use = self.last_use();
        let mut arena = Arena::new();
        let mut offsets: Vec<Option<u32>> = vec![None; self.shapes.len()];
        for &Id(id) in &self.pinned {
            let shape = self.shapes.get(id).copied().unwrap_or(Shape::new(0, 0, 0));
            *offsets.get_mut(id).ok_or("pinned id out of range")? =
                Some(arena.alloc(shape.len()));
        }

        let mut ops = Vec::new();
        for (step, node) in self.nodes.iter().enumerate() {
            // Allocate this node's output before freeing its inputs: an op reads and
            // writes the same buffer, so overlapping them would be a data race that
            // no barrier can fix.
            let out = node.out();
            if offsets.get(out.0).copied().flatten().is_none() {
                let shape = self.shapes.get(out.0).copied().ok_or("output id out of range")?;
                *offsets.get_mut(out.0).ok_or("output id out of range")? =
                    Some(arena.alloc(shape.len()));
            }

            let at = |id: Id| -> Result<u32, String> {
                offsets
                    .get(id.0)
                    .copied()
                    .flatten()
                    .ok_or_else(|| format!("step {step} reads tensor {} before it is written", id.0))
            };
            let shape = |id: Id| -> Shape {
                self.shapes.get(id.0).copied().unwrap_or(Shape::new(0, 0, 0))
            };
            self.emit(node, &at, &shape, &mut ops)?;

            for (id, &last) in last_use.iter().enumerate() {
                if last == Some(step) && !self.pinned.contains(&Id(id)) {
                    if let Some(offset) = offsets.get(id).copied().flatten() {
                        let len = self.shapes.get(id).map(|s| s.len()).unwrap_or(0);
                        arena.free(offset, len);
                    }
                }
            }
        }

        let binding = |id: Id| -> Result<Binding, String> {
            let at = offsets
                .get(id.0)
                .copied()
                .flatten()
                .ok_or_else(|| format!("tensor {} was never allocated", id.0))?;
            let shape = self
                .shapes
                .get(id.0)
                .copied()
                .ok_or_else(|| format!("tensor {} has no shape", id.0))?;
            Ok(Binding { at, shape })
        };
        Ok(Plan {
            ops,
            arena_elems: arena.high_water,
            inputs: self.inputs.iter().map(|&id| binding(id)).collect::<Result<_, _>>()?,
            outputs: outputs.iter().map(|&id| binding(id)).collect::<Result<_, _>>()?,
        })
    }

    /// For each tensor, the last step that reads it, or `None` if nothing does.
    fn last_use(&self) -> Vec<Option<usize>> {
        let mut last = vec![None; self.shapes.len()];
        for (step, node) in self.nodes.iter().enumerate() {
            for Id(id) in node.inputs() {
                if let Some(slot) = last.get_mut(id) {
                    *slot = Some(step);
                }
            }
        }
        last
    }

    fn emit(
        &self,
        node: &Node,
        at: &dyn Fn(Id) -> Result<u32, String>,
        shape: &dyn Fn(Id) -> Shape,
        ops: &mut Vec<Op>,
    ) -> Result<(), String> {
        match node {
            Node::Conv {
                input,
                out,
                weight,
                bias,
                kernel,
                stride,
                dilation,
                pad,
                group,
                act,
                act_weight,
                transpose,
            } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: if *transpose { Kind::ConvTranspose } else { Kind::Conv },
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        weight: *weight,
                        bias: *bias,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        dil_h: dilation.0,
                        dil_w: dilation.1,
                        pad_t: pad.0,
                        pad_l: pad.1,
                        group: *group,
                        act: act.code(),
                        act_weight: *act_weight,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::MaxPool { input, out, kernel, stride } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::MaxPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AvgPool { input, out, kernel, stride } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AvgPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Resize { input, out, nearest } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: if *nearest { Kind::ResizeNearest } else { Kind::Resize },
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::GlobalAvgPool { input, out } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::GlobalAvgPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: 1,
                        out_w: 1,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Binary { kind, a, b, out } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: *kind,
                    push: Push {
                        in0: at(*a)?,
                        in1: at(*b)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Concat { parts, out } => {
                let base = at(*out)?;
                let mut written = 0;
                for &part in parts {
                    let len = shape(part).len();
                    ops.push(Op::Copy {
                        src: at(part)?,
                        dst: base + written,
                        elems: len,
                    });
                    written += len;
                }
            }
            Node::Affine { input, out, scale, shift } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Affine,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param0_bits: scale.to_bits(),
                        param1_bits: shift.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::LayerNorm { input, out, gamma, beta, epsilon } => {
                let so = shape(*out);
                // One invocation per position, each reducing over the channels, so the
                // dispatch is the spatial extent rather than the element count.
                let positions = so.h * so.w;
                ops.push(Op::Dispatch {
                    kind: Kind::LayerNorm,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        weight: *gamma,
                        bias: *beta,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param1_bits: epsilon.to_bits(),
                        count: positions,
                        ..Push::default()
                    },
                    invocations: positions,
                });
            }
            Node::AttnScores { q, k, out, heads, scale } => {
                let (si, so) = (shape(*q), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScores,
                    push: Push {
                        in0: at(*q)?,
                        in1: at(*k)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        param0_bits: scale.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Softmax { input, out } => {
                let so = shape(*out);
                // One invocation per row of the last axis, each normalising `out_w`
                // contiguous elements, so the dispatch is rows rather than elements.
                let rows = so.c * so.h;
                ops.push(Op::Dispatch {
                    kind: Kind::Softmax,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: rows,
                        ..Push::default()
                    },
                    invocations: rows,
                });
            }
            Node::AttnApply { probs, v, out, heads } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApply,
                    push: Push {
                        in0: at(*probs)?,
                        in1: at(*v)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::LeakyRelu { input, out, alpha } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::LeakyRelu,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param0_bits: alpha.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
        }
        Ok(())
    }
}

impl Node {
    fn out(&self) -> Id {
        match self {
            Node::Conv { out, .. }
            | Node::MaxPool { out, .. }
            | Node::AvgPool { out, .. }
            | Node::Resize { out, .. }
            | Node::GlobalAvgPool { out, .. }
            | Node::Binary { out, .. }
            | Node::Affine { out, .. }
            | Node::LayerNorm { out, .. }
            | Node::AttnScores { out, .. }
            | Node::Softmax { out, .. }
            | Node::LeakyRelu { out, .. }
            | Node::AttnApply { out, .. }
            | Node::Concat { out, .. } => *out,
        }
    }

    fn inputs(&self) -> Vec<Id> {
        match self {
            Node::Conv { input, .. }
            | Node::MaxPool { input, .. }
            | Node::AvgPool { input, .. }
            | Node::Resize { input, .. }
            | Node::Affine { input, .. }
            | Node::LayerNorm { input, .. }
            | Node::Softmax { input, .. }
            | Node::LeakyRelu { input, .. }
            | Node::GlobalAvgPool { input, .. } => vec![*input],
            Node::Binary { a, b, .. } => vec![*a, *b],
            Node::AttnScores { q: a, k: b, .. } | Node::AttnApply { probs: a, v: b, .. } => {
                vec![*a, *b]
            }
            Node::Concat { parts, .. } => parts.clone(),
        }
    }
}

/// `floor((in + pad - dilation * (k - 1) - 1) / stride) + 1`, ONNX's convolution
/// output size.
fn conv_out(input: u32, kernel: u32, stride: u32, dilation: u32, pad_total: u32) -> u32 {
    let effective = dilation * (kernel - 1) + 1;
    let padded = input + pad_total;
    if padded < effective || stride == 0 {
        return 0;
    }
    (padded - effective) / stride + 1
}

/// `(in - 1) * stride + k - pad`, ONNX's transposed-convolution output size at
/// `dilation = 1` and no `output_padding` — which is the only form used.
fn deconv_out(input: u32, kernel: u32, stride: u32, pad_total: u32) -> u32 {
    let full = (input.max(1) - 1) * stride + kernel;
    full.saturating_sub(pad_total)
}

/// A first-fit free-list allocator over the activation arena.
///
/// The arena is one `VkBuffer`, so an "allocation" is an element offset into it. The
/// list is kept sorted and coalesced, which for the few hundred allocations either
/// net makes is far cheaper than the memory it saves: U^2-Netp's live set peaks well
/// below the sum of its intermediates, and holding them all would be tens of MB of
/// device memory for a net that reuses almost everything.
struct Arena {
    free: Vec<(u32, u32)>,
    high_water: u32,
}

impl Arena {
    fn new() -> Arena {
        Arena { free: Vec::new(), high_water: 0 }
    }

    fn alloc(&mut self, len: u32) -> u32 {
        let len = round_up(len.max(1));
        // Best fit. Measured against first fit on both real networks it makes no
        // difference to the high-water mark — U^2-Netp lands on 76 MiB either way — but it
        // is the better default for the shape of these allocations, which range from 64
        // elements to 6.5M, and it costs a linear scan of a list that never exceeds a
        // few dozen entries.
        //
        // The remaining ~30% over the true live set is fragmentation that no fit policy
        // fixes: a freed 13 MiB tensor gets split for a 6.6 MiB request and the halves
        // are then too small for the next 13 MiB one. Closing it needs the graph
        // changed rather than the allocator — see the note on memory in
        // `nets::u2netp`.
        let mut best: Option<usize> = None;
        for (i, &(_, size)) in self.free.iter().enumerate() {
            if size >= len && best.is_none_or(|b| self.free.get(b).is_some_and(|&(_, s)| size < s)) {
                best = Some(i);
            }
        }
        if let Some(i) = best {
            if let Some(&(start, size)) = self.free.get(i) {
                if size == len {
                    let _ = self.free.remove(i);
                } else if let Some(slot) = self.free.get_mut(i) {
                    *slot = (start + len, size - len);
                }
                return start;
            }
        }
        let start = self.high_water;
        self.high_water += len;
        start
    }

    fn free(&mut self, offset: u32, len: u32) {
        let len = round_up(len.max(1));
        let at = self.free.partition_point(|&(start, _)| start < offset);
        self.free.insert(at, (offset, len));
        // Coalesce with both neighbours, so a net that frees a run of equal-sized
        // tensors gets one big block back rather than a fragmented list.
        let mut i = 0;
        while i + 1 < self.free.len() {
            let (a_start, a_len) = self.free.get(i).copied().unwrap_or((0, 0));
            let (b_start, b_len) = self.free.get(i + 1).copied().unwrap_or((0, 0));
            if a_start + a_len == b_start {
                if let Some(slot) = self.free.get_mut(i) {
                    *slot = (a_start, a_len + b_len);
                }
                let _ = self.free.remove(i + 1);
            } else {
                i += 1;
            }
        }
    }
}

fn round_up(len: u32) -> u32 {
    len.div_ceil(ALIGN_ELEMS) * ALIGN_ELEMS
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A [`WeightSource`] that knows only shapes.
    ///
    /// It hands back the tensor index as the offset, which is meaningless as an
    /// address but makes the plan reproducible, and it records every shape it was
    /// asked for so a test can assert the whole ordered layer table without a
    /// `.vkml`.
    pub struct Shapes {
        pub asked: std::cell::RefCell<Vec<(usize, Vec<u32>)>>,
        pub count: usize,
    }

    impl Shapes {
        pub fn new(count: usize) -> Shapes {
            Shapes { asked: std::cell::RefCell::new(Vec::new()), count }
        }
    }

    impl WeightSource for Shapes {
        fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
            self.asked.borrow_mut().push((index, dims.to_vec()));
            Ok(index as u32)
        }

        fn count(&self) -> usize {
            self.count
        }
    }

    /// Assert that no op reads a region of the arena that it also writes.
    ///
    /// Every op reads and writes the same `VkBuffer`, and a convolution invocation reads
    /// many elements to write one, so a producer whose output overlapped its own input
    /// would be a data race that no barrier can fix and no test of the *output* would
    /// catch — it would just make the mask slightly wrong, differently on each driver.
    ///
    /// [`Builder::finish`] prevents it by allocating a node's output before freeing its
    /// inputs. This is the check that it worked, run against both real networks.
    pub fn assert_no_aliasing(plan: &Plan) {
        let disjoint = |a: (u32, u32), b: (u32, u32)| a.0 + a.1 <= b.0 || b.0 + b.1 <= a.0;
        for (step, op) in plan.ops.iter().enumerate() {
            let (out, reads) = match *op {
                Op::Copy { src, dst, elems } => ((dst, elems), vec![(src, elems)]),
                Op::Dispatch { kind, push, .. } => {
                    let dense = push.in_c * push.in_h * push.in_w;
                    // Not `push.count`: the reducing kinds dispatch one invocation per
                    // reduction rather than per element, so their `count` understates
                    // what they write. Every output here is dense.
                    let written = push.out_c * push.out_h * push.out_w;
                    let reads = match kind {
                        Kind::Add => vec![(push.in0, written), (push.in1, written)],
                        // The gate is one value per channel, broadcast over H and W.
                        Kind::MulBroadcast => {
                            vec![(push.in0, written), (push.in1, push.in_c)]
                        }
                        // Q and K are both whole sequences.
                        Kind::AttnScores => vec![(push.in0, dense), (push.in1, dense)],
                        // The score map is `[heads, T, T]`; V is a sequence.
                        Kind::AttnApply => {
                            vec![(push.in0, push.group * push.out_w * push.out_w), (push.in1, dense)]
                        }
                        _ => vec![(push.in0, dense)],
                    };
                    ((push.out, written), reads)
                }
            };
            for read in reads {
                assert!(
                    disjoint(out, read),
                    "step {step} writes {}..{} and reads {}..{}",
                    out.0,
                    out.0 + out.1,
                    read.0,
                    read.0 + read.1,
                );
            }
        }
    }

    #[test]
    fn the_push_block_is_inside_the_guaranteed_limit() {
        // 128 bytes is the minimum `maxPushConstantsSize` the spec requires, so
        // staying under it means no device can reject this.
        assert!(
            std::mem::size_of::<Push>() <= 128,
            "{} bytes exceeds the guaranteed 128",
            std::mem::size_of::<Push>()
        );
    }

    #[test]
    fn the_push_block_has_no_padding() {
        // The shaders read it at fixed offsets, so a gap Rust inserted would shift
        // every field after it.
        assert_eq!(std::mem::size_of::<Push>(), 25 * 4);
        assert_eq!(std::mem::align_of::<Push>(), 4);
    }

    #[test]
    fn conv_output_sizes_match_onnx() {
        // The selfie net's first layer: 256 -> 128 with asymmetric pads [0, 0, 1, 1], so
        // one row and column of padding in total.
        assert_eq!(conv_out(256, 3, 2, 1, 1), 128);
        // Its 5x5 stride-2 depthwise, pads [1,1,2,2]: 32 -> 16.
        assert_eq!(conv_out(32, 5, 2, 1, 1 + 2), 16);
        // U^2-Netp's dilated 3x3s, where pad == dilation holds the size.
        assert_eq!(conv_out(320, 3, 1, 1, 2), 320);
        assert_eq!(conv_out(20, 3, 1, 8, 16), 20);
        // 1x1, the majority of the selfie net.
        assert_eq!(conv_out(16, 1, 1, 1, 0), 16);
    }

    #[test]
    fn transposed_conv_output_size_matches_onnx() {
        // The selfie net's only ConvTranspose: 2x2 stride 2, 128 -> 256.
        assert_eq!(deconv_out(128, 2, 2, 0), 256);
    }

    #[test]
    fn the_arena_reuses_a_freed_block_rather_than_growing() {
        let mut arena = Arena::new();
        let a = arena.alloc(64);
        let b = arena.alloc(64);
        assert_eq!((a, b), (0, 64));
        arena.free(a, 64);
        // The freed block is the first fit, so this must land back at 0 and leave the
        // high-water mark alone. If it does not, neither net's arena is bounded.
        assert_eq!(arena.alloc(64), 0);
        assert_eq!(arena.high_water, 128);
    }

    #[test]
    fn the_arena_coalesces_adjacent_frees() {
        let mut arena = Arena::new();
        let a = arena.alloc(64);
        let b = arena.alloc(64);
        let c = arena.alloc(64);
        arena.free(a, 64);
        arena.free(c, 64);
        arena.free(b, 64);
        assert_eq!(arena.free, vec![(0, 192)]);
        // All three coalesced, so a request larger than any one of them fits.
        assert_eq!(arena.alloc(192), 0);
        assert_eq!(arena.high_water, 192);
    }

    #[test]
    fn every_allocation_stays_16_byte_aligned() {
        let mut arena = Arena::new();
        // 3 elements is 6 bytes: the round-up is what keeps the next tensor aligned.
        for _ in 0..8 {
            assert_eq!(arena.alloc(3) % ALIGN_ELEMS, 0);
        }
    }

    #[test]
    fn a_wrong_weight_shape_fails_the_build() {
        struct Wrong;
        impl WeightSource for Wrong {
            fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
                Err(format!("tensor {index} is not {dims:?}"))
            }
            fn count(&self) -> usize {
                2
            }
        }
        let mut builder = Builder::new(&Wrong);
        let input = builder.input(Shape::new(3, 8, 8));
        let out = builder.conv_same(input, 0, 4, 3, 1, Act::Relu);
        let error = builder.finish(&[out]).expect_err("bad weights");
        assert!(error.contains("tensor 0"), "{error}");
    }

    #[test]
    fn a_pass_that_leaves_tensors_unread_fails_the_build() {
        let source = Shapes::new(4);
        let mut builder = Builder::new(&source);
        let input = builder.input(Shape::new(3, 8, 8));
        let out = builder.conv_same(input, 0, 4, 3, 1, Act::Relu);
        let error = builder.finish(&[out]).expect_err("short pass");
        // Named by index, not just counted: a pass that skipped a layer in the middle
        // reads the right *number* of tensors and the wrong ones.
        assert!(error.contains("never reads tensor 2 of 4"), "{error}");
    }

    #[test]
    fn concat_lowers_to_contiguous_copies_and_no_shader() {
        let source = Shapes::new(0);
        let mut builder = Builder::new(&source);
        let a = builder.input(Shape::new(2, 2, 2));
        let b = builder.resize_to(a, 2, 2);
        let joined = builder.concat(&[a, b]);
        let plan = builder.finish(&[joined]).expect("builds");
        let copies: Vec<&Op> = plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).collect();
        assert_eq!(copies.len(), 2);
        // The second part lands exactly one part's worth of elements after the first:
        // in NCHW a channel run is contiguous, which is the whole reason concat needs
        // no shader.
        match (copies.first(), copies.get(1)) {
            (Some(Op::Copy { dst: first, elems, .. }), Some(Op::Copy { dst: second, .. })) => {
                assert_eq!(*second, first + elems);
            }
            other => panic!("expected two copies, got {other:?}"),
        }
    }
}
