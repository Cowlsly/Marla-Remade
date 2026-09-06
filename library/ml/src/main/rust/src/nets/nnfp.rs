//! The Now Playing audio fingerprinter: 415 ms of 16 kHz mono in, a 64-d embedding out.
//!
//! Ported from `music_detector.sound_model_2`, the second stage of Google's on-DSP Now Playing
//! pipeline. The first stage is a 8.2K-parameter music/not-music gate; this is the
//! 172,576-parameter network that runs once the gate fires and produces the descriptor an
//! on-device song database is searched with. Both consume the same log-mel front end, at the
//! same quantisation — see [`crate::microfrontend`], which is where the input comes from.
//!
//! # Shape of the network
//!
//! Alternating frequency and time convolutions, halving the mel axis each time the channel
//! count grows, then a two-stage depthwise head:
//!
//! ```text
//! channels     1 -> 8 -> 16 -> 24 -> 32 -> 48 -> 64 -> 96 -> 128 -> 96 -> 64 -> (512) -> 64
//! mel bins    32 -> 16 -> 16 ->  8 ->  8 ->  4 ->  4 ->  2 ->   2 ->  2 ->  2
//! frames     478 -> 478 -> 238 -> 238 -> 118 -> 118 -> 58 -> 58 ->  28 -> 28 -> 25
//! ```
//!
//! A [`Shape`] here is `(channels, frames, mel bins)`: **`h` is time and `w` is frequency**.
//! That is the export's own NHWC convention with the batch dropped, so a `(1, 4)` kernel runs
//! along frequency and a `(4, 1)` kernel along time. Reading it the other way round builds
//! cleanly and infers nonsense, which is the whole hazard of a hardcoded forward pass.
//!
//! # Why the input is 40 frames and not a stream
//!
//! The export is a **streaming** graph: six `CIRCULAR_BUFFER` ops carry state between
//! invocations, and it is called once per 10 ms hop. A [`Plan`] has no state — it is recorded
//! into one command buffer at construction and an inference is one submit — so that shape does
//! not survive the port.
//!
//! It does not need to. A circular buffer of depth 4 feeding a 4-tap temporal convolution is
//! exactly a `VALID` convolution over a real time axis, so unrolling the whole thing into a
//! fixed window is the same arithmetic with the state made explicit. Working backwards from the
//! 25 frames the head reads, through one undecimated temporal convolution and four that halve
//! the frame rate, gives [`WINDOW_FRAMES`]:
//!
//! ```text
//! frames     478 -> 478 -> 238 -> 238 -> 118 -> 118 -> 58 -> 58 -> 28 -> 28 -> 25
//! ```
//!
//! Unrolling is also *more* faithful than the stream at the edges: there is no warm-up transient
//! from zero-initialised buffers, because every frame the answer depends on is supplied.
//!
//! At 10 ms a frame that is 4.78 s of context, from [`WINDOW_SAMPLES`] samples — the right order
//! for identifying a track, and the reason the head is a learned depthwise over the *whole*
//! 25-step history rather than a pooling layer. One embedding is about 22 MMAC, which is nothing
//! next to the 2.2 GMAC this runtime already spends on U^2-Net.
//!
//! ## Why the temporal stride is 2, which the file does not say
//!
//! Four of the five temporal convolutions carry `StrideH = 2` in the export, and in the
//! streaming graph that stride is provably inert — a 4-tap kernel over a height of exactly 4
//! gives one output row at any stride. So the file alone cannot say whether it is a leftover
//! from the non-streaming training graph or a real decimation.
//!
//! It is real. The argument is in `detector/ARCHITECTURE.md` §4.6; the two loads that carry it:
//!
//! * The always-on gate in `sound_model` subgraph 3 sets `(2, 2)` on **every** depthwise,
//!   including `StrideW = 2` on an axis of width 1, which is definitively meaningless. Not one
//!   of its twelve strides does anything. That is what boilerplate looks like. This network
//!   never writes `(2, 2)`: its strides are per axis, complementary — frequency layers stride
//!   frequency, temporal layers stride time — and it stops downsampling at the last two layers.
//!   Three of them provably produce the recorded mel ramp. That is not an accident.
//! * The two readings are **mutually exclusive rather than merely different**. If the network
//!   was trained with temporal stride 2, then running it without decimation is numerically
//!   *wrong* and not just denser: each stage's 4-deep buffer would collect the stage below it at
//!   stride-1 spacing where training used stride-2. Only one reading matches these weights.
//!
//! What remains unknown is the *mechanism* — `cycles_max` is absent from every circular buffer,
//! so either TFLM's default is not 1 or the DSP host clocks the stages. That does not change
//! what this has to compute. If it is ever shown wrong, the reversal is [`TEMPORAL_STRIDE`] to 1
//! and [`WINDOW_FRAMES`] to 40, and nothing else: a mismatched pair fails at build time rather
//! than silently, because the head's reshape checks the element count.
//!
//! # Two things the export gets wrong about itself
//!
//! * Every convolution stores `Padding = VALID`, but for the five **frequency** convolutions
//!   that contradicts the output shapes the same file records: 32 bins with a 4-wide kernel at
//!   stride 2 gives 15 under `VALID` and the file says 16. The shapes are the safer source —
//!   the converter derives them from the op it actually ran — so frequency is padded `SAME` and
//!   time is genuinely `VALID`. Padding these `VALID` builds a net one bin narrower at every
//!   stage and a head that cannot reshape.
//! * The head's `[1,1,1,512] -> [1,1,8,64]` reshape is a **channel shuffle**, not a regrouping
//!   by input channel. See [`build`].
//!
//! # Weights are fp16, not int8
//!
//! The export is int8 per-tensor throughout. At 172,576 parameters that is 337 KB against
//! 346 KB at fp16, so quantisation buys nine kilobytes and costs the accuracy of per-tensor
//! int8 arithmetic; `scripts/ml/maml_convert.py` dequantises with each tensor's scale instead.
//! The consequence worth stating: this is a float evaluation of a network that was *trained*
//! quantisation-aware, so its output will not be bit-identical to the DSP's, and nothing here
//! has been compared against the DSP.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Mel channels in one frame, which is [`crate::microfrontend::Config::channels`].
pub const MEL_BINS: u32 = 32;

/// Frames of log-mel one embedding is computed from — the network's receptive field.
///
/// Derived by unrolling the streaming graph backwards from the 25 frames the head reads:
/// `op14` is undecimated so it costs 3, and each of the four convolutions below it inverts to
/// `(out - 1) * 2 + 4`, giving `25 -> 28 -> 58 -> 118 -> 238 -> 478`. Paired with
/// [`TEMPORAL_STRIDE`]; see the module documentation, and change neither alone.
pub const WINDOW_FRAMES: u32 = 478;

/// Stride of the four decimating temporal convolutions, along the frame axis.
///
/// Two, as the export records, even though that two is inert in the streaming graph it was
/// recorded from. The last temporal convolution does not decimate and is not affected. See the
/// module documentation for why this is not the training leftover it looks like.
pub const TEMPORAL_STRIDE: u32 = 2;

/// Frames the head reads, which is the depth of the export's last circular buffer.
const HEAD_FRAMES: u32 = 25;

/// Values in the embedding.
pub const EMBEDDING: u32 = 64;

/// 16 kHz samples behind one embedding: a 400-sample window plus 477 hops of 160.
///
/// 4.795 s rather than [`WINDOW_FRAMES`]'s 4.78, because the first frame needs a whole window
/// before the hops start.
pub const WINDOW_SAMPLES: usize = 400 + 160 * (WINDOW_FRAMES as usize - 1);

/// Tensors the `.maml` must hold, in the order [`build`] reads them.
pub const TENSORS: usize = 24;

/// Positions in the `.maml` tensor table, which has no names — only order.
///
/// The other half of this contract is `nnfp_layers` in `scripts/ml/maml_convert.py`, which
/// emits the tensors in exactly this sequence. A reordering loads cleanly and infers nonsense.
struct Layers {
    next: usize,
}

impl Layers {
    /// A convolution's weight and bias.
    fn conv(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }

    /// A tensor read as a constant rather than as a convolution's parameter.
    fn constant(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }
}

/// A convolution along the frequency axis: `SAME` padding, halving unless the kernel is 2 wide.
///
/// The mel axis is `w`, so the kernel is `(1, taps)`. `SAME` is asymmetric the way TFLite's is —
/// `pad_total = (out - 1) * stride + taps - in`, with the smaller half first — which for every
/// layer here works out to one column on each side, and for the 2-tap final layer to one column
/// on the right only.
fn frequency(b: &mut Builder, l: &mut Layers, x: Id, out_c: u32, taps: u32, stride: u32) -> Id {
    let bins = b.shape(x).w;
    let out_w = bins.div_ceil(stride);
    let total = ((out_w - 1) * stride + taps).saturating_sub(bins);
    b.conv(
        x,
        l.conv(),
        out_c,
        (1, taps),
        (1, stride),
        (1, 1),
        (0, total / 2, 0, total - total / 2),
        1,
        Act::Relu,
    )
}

/// A convolution along the time axis: 4 taps, `VALID`, at `stride` frames.
///
/// `stride` is [`TEMPORAL_STRIDE`] for the four layers that decimate and 1 for the last, which
/// the export leaves at full rate.
fn temporal(b: &mut Builder, l: &mut Layers, x: Id, out_c: u32, stride: u32) -> Id {
    b.conv(x, l.conv(), out_c, (4, 1), (stride, 1), (1, 1), (0, 0, 0, 0), 1, Act::Relu)
}

/// The forward pass, or the first shape or tensor that does not match.
///
/// # The head
///
/// The trunk ends at `(64, 25, 2)` — 25 frames of 2 mel bins across 64 channels — and the
/// export flattens time and frequency into one length-50 axis, time-major, before a full-width
/// depthwise convolution with `depth_multiplier = 8`. In this layout that flattening is a
/// relabelling and not a move, because `(64, 25, 2)` and `(64, 1, 50)` are the same elements in
/// the same order with `w = frame * 2 + bin`.
///
/// What follows is the part that is easy to get wrong. The depthwise writes output channel
/// `i = c * 8 + m`, and the export then reshapes `[1,1,1,512]` to `[1,1,8,64]` **flat**, so
/// element `i` lands at `(w = i / 64, channel = i % 64)`. That is a channel *shuffle*: the eight
/// values that are summed into output `ch` are `i = k * 64 + ch` for `k` in `0..8`, which come
/// from eight **different** input channels. Grouping them by their original channel instead —
/// the obvious reading — gives a wrong and plausible-looking answer.
///
/// There is no transpose op in this runtime and no reason to add one, because the shuffle is
/// free if the 512 values are relabelled as `(1, 8, 64)` instead: flat index `i` is then exactly
/// `(h = k, w = ch)`, so the eight terms of output `ch` are a column. Multiplying by the final
/// kernel and summing that column is an elementwise multiply and a mean over `h`, and the mean's
/// divisor is undone by the affine — `avg_pool` divides by the window unconditionally, and there
/// is no sum-pool. Two dispatches and no new shader.
pub fn build(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut b = Builder::new(weights);
    let mut layers = Layers { next: 0 };
    let l = &mut layers;

    let input = b.input(Shape::new(1, WINDOW_FRAMES, MEL_BINS));

    let x = frequency(&mut b, l, input, 8, 4, 2);
    let x = temporal(&mut b, l, x, 16, TEMPORAL_STRIDE);
    let x = frequency(&mut b, l, x, 24, 4, 2);
    let x = temporal(&mut b, l, x, 32, TEMPORAL_STRIDE);
    let x = frequency(&mut b, l, x, 48, 4, 2);
    let x = temporal(&mut b, l, x, 64, TEMPORAL_STRIDE);
    let x = frequency(&mut b, l, x, 96, 4, 2);
    let x = temporal(&mut b, l, x, 128, TEMPORAL_STRIDE);
    // The one frequency layer that neither widens the channels nor narrows the mel axis, and
    // below it the one temporal layer that does not decimate. The export stops downsampling
    // here on both axes, which is part of why its strides read as deliberate.
    let x = frequency(&mut b, l, x, 96, 2, 1);
    let trunk = temporal(&mut b, l, x, EMBEDDING, 1);

    let bins = b.shape(trunk).w;
    let flat = b.reshaped(trunk, Shape::new(EMBEDDING, 1, HEAD_FRAMES * bins));
    // `group = EMBEDDING` with eight times as many outputs is the export's `depth_multiplier`:
    // output channel `o` reads input channel `o / 8`, which is the `i = c * 8 + m` ordering.
    let wide = b.conv(
        flat,
        l.conv(),
        EMBEDDING * 8,
        (1, HEAD_FRAMES * bins),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        EMBEDDING,
        Act::Relu,
    );

    let shuffled = b.reshaped(wide, Shape::new(1, 8, EMBEDDING));
    let kernel = b.constant(l.constant(), Shape::new(1, 8, EMBEDDING));
    let scaled = b.mul(shuffled, kernel);
    let mean = b.avg_pool(scaled, (8, 1), (8, 1));
    let summed = b.affine(mean, 8.0, 0.0);
    let column = b.reshaped(summed, Shape::new(EMBEDDING, 1, 1));
    let bias = b.constant(l.constant(), Shape::new(EMBEDDING, 1, 1));
    let embedding = b.add(column, bias);

    b.finish(&[embedding])
}

#[cfg(test)]
mod tests {
    use super::super::tests::Shapes;
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source).expect("nnfp builds")
    }

    fn asked() -> Vec<(usize, Vec<u32>)> {
        let source = Shapes::new(TENSORS);
        let _ = build(&source);
        let borrowed = source.asked.borrow();
        borrowed.clone()
    }

    #[test]
    fn the_pass_consumes_the_whole_tensor_table() {
        let source = Shapes::new(TENSORS);
        assert!(build(&source).is_ok());
        assert_eq!(source.asked.borrow().len(), TENSORS);
    }

    #[test]
    fn the_layer_table_matches_the_tflite_export() {
        // Transcribed from ARCHITECTURE.md §4.2. The export stores a kernel `[out, kh, kw, in]`
        // and this runtime wants `[out, in / group, kh, kw]`, so every entry is that transpose;
        // `maml_convert.py` does it, and this is the assertion that the two agree.
        let asked = asked();
        let shape_at = |index: usize| -> Vec<u32> {
            asked
                .iter()
                .find(|(i, _)| *i == index)
                .map(|(_, dims)| dims.clone())
                .unwrap_or_default()
        };
        // Tensor 0, the first frequency convolution: one input channel, 4 taps along mel.
        assert_eq!(shape_at(0), vec![8, 1, 1, 4]);
        assert_eq!(shape_at(1), vec![8]);
        // Tensor 2, the first temporal convolution: 4 taps along time, 8 channels in.
        assert_eq!(shape_at(2), vec![16, 8, 4, 1]);
        // Tensor 16, the 2-tap frequency layer that does not narrow the mel axis.
        assert_eq!(shape_at(16), vec![96, 128, 1, 2]);
        // Tensor 18, the last temporal convolution, down from 96 channels to the embedding.
        assert_eq!(shape_at(18), vec![64, 96, 4, 1]);
        // Tensor 20, the depthwise head. `[512, 1, 1, 50]` and not `[512, 64, 1, 50]`: it is
        // grouped, so the second dimension is channels *per group*, which is one.
        assert_eq!(shape_at(20), vec![512, 1, 1, 50]);
        assert_eq!(shape_at(21), vec![512]);
        // Tensor 22, the final kernel, read as a constant in the shuffled layout rather than as
        // a convolution's weight. Rank 3, because that is what a constant is.
        assert_eq!(shape_at(22), vec![1, 8, 64]);
        assert_eq!(shape_at(23), vec![64, 1, 1]);
    }

    #[test]
    fn the_input_and_output_are_the_shapes_kotlin_expects() {
        let plan = plan();
        // One channel, 40 frames of 32 mel bins, which is the front end's output laid out
        // frame-major — exactly the order `microfrontend::Frontend::process` appends in.
        assert_eq!(
            plan.input().expect("one input").shape,
            Shape::new(1, WINDOW_FRAMES, MEL_BINS)
        );
        assert_eq!(plan.output().expect("one output").shape, Shape::new(EMBEDDING, 1, 1));
    }

    #[test]
    fn the_window_is_the_samples_the_frames_need() {
        // A hop short here and the last frame of the window is never computed, so the embedding
        // silently loses its most recent 10 ms.
        assert_eq!(WINDOW_SAMPLES, 76_720);
        assert_eq!((WINDOW_SAMPLES - 400) / 160 + 1, WINDOW_FRAMES as usize);
    }

    #[test]
    fn the_frequency_axis_halves_four_times_and_then_holds() {
        // 32 -> 16 -> 8 -> 4 -> 2 -> 2. Padding the frequency convolutions `VALID`, which is
        // what the export literally says, gives 15 -> 7 -> 3 -> 1 -> 1 instead and the head
        // cannot reshape. This is the assertion that catches that.
        let plan = plan();
        let widths: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv | Kind::ConvPoint, push, .. } if push.kw > 1 => {
                    Some((push.in_w, push.out_w))
                }
                _ => None,
            })
            .collect();
        assert_eq!(widths, vec![(32, 16), (16, 8), (8, 4), (4, 2), (2, 2), (50, 1)]);
    }

    #[test]
    fn the_frame_rate_halves_four_times_and_then_holds() {
        // 478 -> 238 -> 118 -> 58 -> 28 -> 25. The last temporal convolution does not decimate,
        // so it costs three frames rather than halving. `WINDOW_FRAMES` and `TEMPORAL_STRIDE`
        // have to move together, and this is where a mismatched pair shows up as a schedule
        // that does not land on the 25 frames the head reads.
        let plan = plan();
        let frames: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv | Kind::ConvPoint, push, .. } if push.kh == 4 => {
                    Some((push.in_h, push.out_h))
                }
                _ => None,
            })
            .collect();
        assert_eq!(frames, vec![(478, 238), (238, 118), (118, 58), (58, 28), (28, 25)]);
    }

    #[test]
    fn only_the_last_temporal_convolution_runs_at_full_rate() {
        // The export's own pattern, and the thing that makes its strides read as deliberate
        // rather than as boilerplate. See the module documentation.
        let plan = plan();
        let strides: Vec<u32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv | Kind::ConvPoint, push, .. } if push.kh == 4 => {
                    Some(push.stride_h)
                }
                _ => None,
            })
            .collect();
        assert_eq!(strides, vec![2, 2, 2, 2, 1]);
    }

    #[test]
    fn the_head_is_grouped_by_channel_and_not_dense() {
        // A `[512, 64, 1, 50]` dense kernel would be 1.6M weights against 25,600 and would build
        // and run. What makes it depthwise is `group`, and only this asserts it.
        let plan = plan();
        let grouped: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.group > 1 => {
                    Some((push.in_c, push.out_c, push.group))
                }
                _ => None,
            })
            .collect();
        assert_eq!(grouped, vec![(64, 512, 64)]);
    }

    #[test]
    fn the_op_inventory_matches_the_tflite() {
        let plan = plan();
        let count = |kind: Kind| {
            plan.ops
                .iter()
                .filter(|op| match op {
                    Op::Dispatch { kind: Kind::ConvPoint, .. } => matches!(kind, Kind::Conv),
                    Op::Dispatch { kind: k, .. } => *k == kind,
                    _ => false,
                })
                .count()
        };
        // Ten trunk convolutions plus the depthwise head.
        assert_eq!(count(Kind::Conv), 11);
        // The channel shuffle: multiply by the kernel, mean, undo the mean, add the bias.
        assert_eq!(count(Kind::Mul), 1);
        assert_eq!(count(Kind::AvgPool), 1);
        assert_eq!(count(Kind::Affine), 1);
        assert_eq!(count(Kind::Add), 1);
        // Nothing that would mean a shader this net does not need.
        assert_eq!(count(Kind::MaxPool), 0);
        assert_eq!(count(Kind::GlobalAvgPool), 0);
        assert_eq!(count(Kind::Resize), 0);
        assert_eq!(count(Kind::ConvTranspose), 0);
        // Three relabellings, each one `Op::Copy`: into the head, into the shuffle, and out.
        assert_eq!(plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).count(), 3);
    }

    #[test]
    fn no_op_reads_a_region_it_also_writes() {
        super::super::tests::assert_no_aliasing(&plan());
    }

    #[test]
    fn the_arena_is_bounded() {
        let plan = plan();
        let bytes = plan.arena_elems as u64 * 2;
        // The two widest activations are 8 channels of 478 frames by 16 bins and 16 of 238 by
        // 16, at 122 KB each, and they are live at the same time. Everything after the first
        // decimation is smaller, so a megabyte is the whole net with room to spare.
        assert!(bytes < 1024 * 1024, "arena is {bytes} bytes");
    }
}
