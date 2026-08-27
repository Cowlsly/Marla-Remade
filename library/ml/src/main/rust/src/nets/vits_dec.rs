//! Piper's HiFi-GAN vocoder: the `dec` half of VITS, and the expensive half of speaking.
//!
//! # What it is
//!
//! 192 channels of latent at one frame per 256 output samples, upsampled 8x, 8x and 4x into
//! a single-channel waveform. Sixty-seven ONNX nodes and 23 weight layers — the smallest
//! part of the VITS graph by a wide margin, and by far the most arithmetic, because every
//! layer after the first upsample runs at audio rate.
//!
//! # Why this one first
//!
//! The full VITS export is 2755 nodes and cannot be a hardcoded plan: its duration
//! predictor samples noise and builds a monotonic alignment out of `NonZero`, `ScatterND`
//! and `GatherND`, and its output length depends on the durations it predicts. But the graph
//! keeps its Torch module paths, and they split it exactly the way the ncnn build already
//! did:
//!
//! | module  | nodes | shape                                            |
//! |---------|-------|--------------------------------------------------|
//! | `enc_p` |   865 | text encoder, near-static                        |
//! | `dp`    |  1455 | stochastic duration predictor — all the dynamism |
//! | `flow`  |   196 | normalising flow, static                         |
//! | `dec`   |    67 | this                                             |
//!
//! The dynamism is confined to `dp`, which is also the cheapest part: a few small
//! convolutions producing one number per phoneme. So `dp` and the alignment glue belong on
//! the CPU, and `enc_p`, `flow` and this belong on the GPU.
//!
//! # Everything here was already expressible
//!
//! The `Div` by 3 that averages the residual blocks is [`Kind::Affine`] at scale `1/3`. The
//! 1-D convolutions are `(1, k)` kernels. `Tanh` follows `conv_post` directly, so it fuses.
//! The one genuinely new op is [`super::Kind::LeakyRelu`], which cannot fuse: a ResBlock is
//! `h = h + conv(lrelu(h))`, so it sits in *front* of its convolution and reads the tensor
//! the skip connection also needs.
//!
//! # Length
//!
//! `frames` is a parameter because the utterance length is data-dependent — it comes from
//! the durations `dp` predicted, not from anything the caller chose. A plan is one fixed
//! shape, so the host has to pick a bucket and trim the tail; see the note on
//! `vulkan::run::Net`.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Latent channels in, which is VITS's `inter_channels`.
pub const LATENT: u32 = 192;

/// Channels after `conv_pre`, halving at each upsample.
pub const INITIAL: u32 = 256;

/// Total upsampling, so the output is `frames * SAMPLES_PER_FRAME` samples long.
pub const SAMPLES_PER_FRAME: u32 = 256;

/// Output sample rate, from the voice's `.onnx.json`. Carried here because a caller that
/// played the waveform at the wrong rate would get a working but wrongly-pitched voice.
pub const SAMPLE_RATE: u32 = 16_000;

/// The three upsampling stages, as `(kernel, stride)` on the width.
///
/// `8 * 8 * 4` is [`SAMPLES_PER_FRAME`]. The kernel is twice the stride in each case, which
/// is what makes the transposed convolutions overlap evenly rather than leaving a comb.
const UPSAMPLES: [(u32, u32); 3] = [(16, 8), (16, 8), (8, 4)];

/// Each stage's three residual blocks, as `(kernel, [dilation, dilation])`.
///
/// HiFi-GAN's ResBlock2: two convolutions per block, run at three kernel widths in parallel
/// and averaged. The dilations grow with the kernel so all three see a similar span.
const RESBLOCKS: [(u32, [u32; 2]); 3] = [(3, [1, 2]), (5, [2, 6]), (7, [3, 12])];

/// The slope of the fifteen LeakyReLUs inside the upsampling stages.
const SLOPE: f32 = 0.1;

/// The slope of the sixteenth, in front of `conv_post`.
///
/// A different value, and the reason [`super::Kind::LeakyRelu`] carries its alpha rather
/// than hardcoding one. Getting this wrong makes the last layer before the waveform quieter
/// and duller, which no shape or range check would notice.
const FINAL_SLOPE: f32 = 0.01;

/// Tensors the `.vkml` must hold: 23 layers, each a weight and a bias.
///
/// `conv_pre`, three transposed convolutions, `3 * 2` convolutions per stage, and
/// `conv_post` — `(1 + 3 + 18 + 1) * 2`. `conv_post` carries no bias in the export, so the
/// converter writes a zero one and the runtime stays uniform.
pub const TENSORS: usize = 46;

/// Hands out `.vkml` tensor indices in the order the layers appear.
struct Layers {
    next: usize,
}

impl Layers {
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }
}

/// A `1 x k` convolution along the waveform, dilated and padded to hold the length.
///
/// `pad = dilation * (k - 1) / 2` on each side, which is size-preserving for the odd kernels
/// this net uses and is what the export's pads say.
fn along(b: &mut Builder, l: &mut Layers, x: Id, out: u32, kernel: u32, dilation: u32) -> Id {
    let pad = dilation * (kernel - 1) / 2;
    b.conv(
        x,
        l.take(),
        out,
        (1, kernel),
        (1, 1),
        (1, dilation),
        (0, pad, 0, pad),
        1,
        Act::None,
    )
}

/// One ResBlock2: `h = h + conv(lrelu(h))`, twice.
///
/// `first` is the shared LeakyReLU of the block's input. All three blocks in a stage read the
/// same tensor, so the export computes that activation once and so does this — three
/// identical passes over an audio-rate tensor is not free.
fn resblock(
    b: &mut Builder,
    l: &mut Layers,
    input: Id,
    first: Id,
    channels: u32,
    kernel: u32,
    dilations: [u32; 2],
) -> Id {
    let inner = along(b, l, first, channels, kernel, dilations[0]);
    let residual = b.add(inner, input);
    let activated = b.leaky_relu(residual, SLOPE);
    let inner = along(b, l, activated, channels, kernel, dilations[1]);
    b.add(inner, residual)
}

/// Build the vocoder for an utterance of `frames` latent frames.
///
/// The output is `[1, 1, frames * 256]` in `-1..1`.
pub fn build(weights: &dyn WeightSource, frames: u32) -> Result<Plan, String> {
    if frames == 0 {
        return Err("a vocoder pass over no frames".into());
    }

    let l = &mut Layers { next: 0 };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    let input = b.input(Shape::new(LATENT, 1, frames));

    // `conv_pre` has no activation of its own; the first LeakyReLU belongs to stage 0.
    let mut x = along(b, l, input, INITIAL, 7, 1);
    let mut channels = INITIAL;
    for (kernel, stride) in UPSAMPLES {
        let activated = b.leaky_relu(x, SLOPE);
        // `pads` are `stride / 2` each side, which with `kernel == 2 * stride` makes the
        // output exactly `stride` times longer.
        let pad = stride / 2;
        channels /= 2;
        let upsampled = b.conv_transpose(
            activated,
            l.take(),
            channels,
            (1, kernel),
            (1, stride),
            (0, pad, 0, pad),
            Act::None,
        );

        // The three blocks run in parallel on the same tensor and are averaged, so the
        // shared activation is computed once here rather than inside each.
        let first = b.leaky_relu(upsampled, SLOPE);
        let mut total: Option<Id> = None;
        for (kernel, dilations) in RESBLOCKS {
            let block = resblock(b, l, upsampled, first, channels, kernel, dilations);
            total = Some(match total {
                Some(sum) => b.add(sum, block),
                None => block,
            });
        }
        let summed = total.ok_or("a stage with no residual blocks")?;
        // The mean of the three. An `Affine` rather than a divide op: this runtime has no
        // scalar division, and multiplying by the reciprocal is what one would compile to.
        x = b.affine(summed, 1.0 / RESBLOCKS.len() as f32, 0.0);
    }

    let activated = b.leaky_relu(x, FINAL_SLOPE);
    // Down to one channel, bounded into a waveform. `Tanh` fuses because it follows its
    // convolution rather than preceding one.
    let waveform = b.conv(
        activated,
        l.take(),
        1,
        (1, 7),
        (1, 1),
        (1, 1),
        (0, 3, 0, 3),
        1,
        Act::Tanh,
    );

    if l.next != TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {TENSORS}", l.next));
    }
    builder.finish(&[waveform])
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// About a second of speech at 16 kHz, which is 62.5 frames; 64 is the bucket above it.
    const FRAMES: u32 = 64;

    fn plan(frames: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, frames).expect("the vocoder builds");
        (source, plan)
    }

    #[test]
    fn the_pass_reads_every_tensor_in_the_file_exactly_once() {
        let (source, _) = plan(FRAMES);
        let asked = source.asked.borrow();
        assert_eq!(asked.len(), TENSORS);
        let mut indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        indices.sort_unstable();
        assert_eq!(indices, (0..TENSORS).collect::<Vec<usize>>());
    }

    #[test]
    fn the_output_is_one_channel_of_audio_at_256_samples_a_frame() {
        let (_, plan) = plan(FRAMES);
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(1, 1, FRAMES * SAMPLES_PER_FRAME)
        );
        assert_eq!(
            plan.input().expect("one input").shape,
            Shape::new(LATENT, 1, FRAMES)
        );
    }

    #[test]
    fn the_three_stages_upsample_by_exactly_eight_eight_and_four() {
        // 8 * 8 * 4 is 256. A transposed convolution whose pads did not match its stride
        // would drift by a few samples per stage, which compounds into a length that is
        // close to right and a waveform that is not.
        for frames in [1u32, 5, 64, 512] {
            let (_, plan) = plan(frames);
            assert_eq!(
                plan.output().expect("one output").shape.w,
                frames * SAMPLES_PER_FRAME,
                "at {frames} frames"
            );
        }
    }

    #[test]
    fn every_convolution_holds_the_length_it_was_given() {
        // Each dilated kernel is padded by `dilation * (k - 1) / 2`, so nothing but the
        // three transposed convolutions changes the width. One wrong pad would shorten the
        // audio and misalign every residual after it.
        let (_, plan) = plan(FRAMES);
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
                assert_eq!(push.out_w, push.in_w, "step {step} changed the length");
                assert_eq!(push.out_h, 1, "step {step} is not 1-D");
            }
        }
    }

    #[test]
    fn the_op_inventory_matches_the_export() {
        let (_, plan) = plan(FRAMES);
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(format!("{kind:?}")).or_insert(0) += 1;
            }
        }
        // 19 convolutions: `conv_pre`, 18 in the residual blocks, and `conv_post`. That is
        // 20 — `conv_post` is one of them, so 1 + 18 + 1.
        assert_eq!(counts.get("Conv"), Some(&20), "{counts:?}");
        assert_eq!(counts.get("ConvTranspose"), Some(&3), "{counts:?}");
        // Sixteen LeakyReLUs: one before each upsample, one shared per stage, one inside
        // each of the nine blocks, and one before `conv_post`.
        assert_eq!(counts.get("LeakyRelu"), Some(&(3 + 3 + 9 + 1)), "{counts:?}");
        // Two residual adds per block, plus two to sum each stage's three blocks.
        assert_eq!(counts.get("Add"), Some(&(9 * 2 + 3 * 2)), "{counts:?}");
        // One mean per stage.
        assert_eq!(counts.get("Affine"), Some(&3), "{counts:?}");
        assert_eq!(counts.len(), 5, "{counts:?}");
    }

    #[test]
    fn the_leaky_relu_slopes_are_the_exports_two_values() {
        // Fifteen at 0.1 and the last at 0.01. A single slope everywhere is the mistake this
        // pins, and it is inaudible in a spectrogram and audible in the output.
        let (_, plan) = plan(FRAMES);
        let slopes: Vec<f32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::LeakyRelu, push, .. } => {
                    Some(f32::from_bits(push.param0_bits))
                }
                _ => None,
            })
            .collect();
        assert_eq!(slopes.len(), 16);
        assert_eq!(slopes.iter().filter(|s| **s == SLOPE).count(), 15);
        assert_eq!(slopes.last().copied(), Some(FINAL_SLOPE));
    }

    #[test]
    fn each_stage_averages_its_three_blocks_rather_than_summing_them() {
        // Summing would make the signal three times too loud at every stage, which is 27x
        // by the output — clipped by the final tanh into something that sounds like speech
        // through a fuzz pedal.
        let (_, plan) = plan(FRAMES);
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::Affine, push, .. } = op {
                let scale = f32::from_bits(push.param0_bits);
                assert!((scale - 1.0 / 3.0).abs() < 1e-6, "{scale}");
                assert_eq!(f32::from_bits(push.param1_bits), 0.0);
            }
        }
    }

    #[test]
    fn the_tensor_table_matches_the_export() {
        // Every weight and bias the `dec` module of `en_GB-alan-low.onnx` holds, summed:
        // 23 layers, one of which (`conv_post`) carries no bias, so the converter writes a
        // zero one and this counts it.
        //
        // The same check `ppocr_rec` leans on, and the same reason: a channel count, kernel
        // width or dilation that is wrong here disagrees with the export, while every
        // structural test above would still pass.
        let (source, _) = plan(FRAMES);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();
        assert_eq!(total, 1_662_977);
    }

    #[test]
    fn a_zero_length_utterance_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, 0).expect_err("no frames");
        assert!(error.contains("no frames"), "{error}");
    }

    #[test]
    fn no_op_reads_a_region_of_the_arena_that_it_also_writes() {
        let (_, plan) = plan(FRAMES);
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_arena_is_bounded_at_a_realistic_utterance() {
        let (_, plan) = plan(FRAMES);
        let mib = plan.arena_elems as f32 * 2.0 / (1024.0 * 1024.0);
        let seconds = (FRAMES * SAMPLES_PER_FRAME) as f32 / SAMPLE_RATE as f32;
        println!(
            "vits_dec at {FRAMES} frames ({seconds:.2}s of audio): {} elements, {mib:.2} MiB",
            plan.arena_elems
        );
        assert!(mib < 64.0, "{mib} MiB");
    }
}
