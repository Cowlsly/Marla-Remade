//! Supertonic 3's vocoder: a latent to a 44.1 kHz waveform.
//!
//! # What it is
//!
//! A ConvNeXt stack, not a HiFi-GAN. Ten blocks of a dilated depthwise convolution, a layer
//! norm, a widening 1x1, a GELU and a narrowing 1x1, with a residual around each. 401 ONNX
//! nodes and 25,333,249 parameters, and no transposed convolution anywhere: the upsampling is
//! a reinterpretation rather than an operation.
//!
//! # The two reinterpretations, both free
//!
//! **In.** The latent arrives as `[144, L]` and the first convolution wants 24 channels. 144 is
//! `24 * 6`, the `chunk_compress_factor`, but the unpacking is an **interleave and not a plain
//! reshape**: the export reshapes to `[24, 6, L]`, transposes the last two axes and flattens
//! again, so position `p` of channel `c` comes from `latent[c * 6 + p % 6][p / 6]`. See
//! [`unpack_latent`]. Treating it as a flat reinterpretation produced audio that correlated with
//! the reference at 0.009 — structurally wrong rather than merely imprecise, and the sort of
//! mistake that sounds like noise rather than like a bug.
//!
//! **Out.** The last convolution emits `[512, 1, T]` and the waveform is
//! `sample[t * 512 + s] = out[s][t]`. See [`interleave`]. That transpose happens on the host,
//! which is copying into an audio buffer regardless. Measured: 3072 output samples per input
//! latent frame, at `L = 54`, `55` and `108`.
//!
//! # Edge padding, and the three folds it permits
//!
//! Every convolution here is preceded by an ONNX `Pad` with **`mode=edge`** and has `pads` of
//! zero itself. That is why nothing shrinks despite seven-wide kernels dilated up to four, and
//! it is why [`Builder::edge_padding`] exists.
//!
//! It also makes three per-channel affines foldable at conversion time, so this module needs no
//! op for any of them:
//!
//! * The input's `Mul` and `Add` by `[24]` fold into `embed`. `conv(a x + b)` is
//!   `conv_{aW}(x) + sum(W b)` only when the padding **replicates** — with zero padding the pad
//!   contributes `0` rather than `b` and the fold is wrong at the border. That is the exact bug
//!   `scripts/ml/ppocr_fold.py` refuses to risk, and the opposite conclusion applies here.
//! * Each block's ConvNeXt layer scale, a `[512]` gamma, folds into its `pwconv2` — trivially,
//!   since a 1x1 has no border at all.
//! * `final_norm`, a `BatchNormalization`, folds into `head/layer1`.
//!
//! See `scripts/ml/maml_convert.py` for all three.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Channels the stack works in.
pub const CHANNELS: u32 = 512;

/// The widening inside each block.
pub const INNER: u32 = CHANNELS * 4;

/// Latent channels the vocoder reads, after the `[144, L]` to `[24, 6L]` reinterpretation.
pub const LATENT: u32 = 24;

/// How many latent channels are folded into the length. `chunk_compress_factor`.
pub const COMPRESS: u32 = 6;

/// Latent channels as the caller holds them, before the reinterpretation.
pub const PACKED: u32 = LATENT * COMPRESS;

/// Output samples per position of the stack, which is also `head/layer2`'s channel count.
pub const SAMPLES_PER_POSITION: u32 = 512;

/// Output samples per *input latent frame*: `SAMPLES_PER_POSITION * COMPRESS`. Verified against
/// onnxruntime at three lengths.
pub const SAMPLES_PER_FRAME: u32 = SAMPLES_PER_POSITION * COMPRESS;

/// The voice's sample rate.
pub const SAMPLE_RATE: u32 = 44_100;

/// ConvNeXt blocks.
pub const BLOCKS: usize = 10;

/// Each block's depthwise dilation, read from the export in order. Not a repeating cycle after
/// the sixth, which is why it is a table rather than `3.pow(i % 3)`.
const DILATIONS: [u32; BLOCKS] = [1, 2, 4, 1, 2, 4, 1, 1, 1, 1];

/// The depthwise kernel width.
const DEPTHWISE: u32 = 7;

/// The epsilon in all ten layer norms.
const EPSILON: f32 = 1e-5;

/// Tensors the `.maml` must hold.
///
/// `embed` (2), ten blocks of eight, `head/layer1` (2), its PReLU slope (1) and `head/layer2`
/// (2, the second a synthesised zero bias): `2 + 80 + 2 + 1 + 2`.
pub const TENSORS: usize = 87;

/// Hands out `.maml` tensor indices in the order the layers appear.
struct Layers {
    next: usize,
}

impl Layers {
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }

    fn take_one(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }
}

/// Unpack a `[144, frames]` latent into the `[24, 6 * frames]` the plan reads.
///
/// **Not** a flat reinterpretation. The export reshapes `[144, L]` to `[24, 6, L]`, transposes
/// the last two axes and flattens, so position `p` of channel `c` comes from
/// `latent[c * 6 + p % 6][p / 6]`. Assuming a plain reshape correlated with the reference at
/// 0.009 rather than 0.99, so this is worth a fixture of its own.
pub fn unpack_latent(latent: &[f32], frames: usize) -> Result<Vec<f32>, String> {
    let packed = PACKED as usize;
    let compress = COMPRESS as usize;
    if latent.len() != packed * frames {
        return Err(format!(
            "{} latent values for {packed} channels over {frames} frames",
            latent.len()
        ));
    }
    let positions = frames * compress;
    let mut out = vec![0.0f32; LATENT as usize * positions];
    for channel in 0..LATENT as usize {
        for position in 0..positions {
            let source = (channel * compress + position % compress) * frames + position / compress;
            out[channel * positions + position] = latent[source];
        }
    }
    Ok(out)
}

/// Read the plan's `[512, 1, T]` output as a waveform: `sample[t * 512 + s] = out[s][t]`.
pub fn interleave(channelled: &[f32]) -> Vec<f32> {
    let channels = SAMPLES_PER_POSITION as usize;
    let positions = channelled.len() / channels.max(1);
    let mut out = vec![0.0f32; channelled.len()];
    for channel in 0..channels {
        for position in 0..positions {
            out[position * channels + channel] = channelled[channel * positions + position];
        }
    }
    out
}

/// Build the vocoder for a latent of `frames` frames.
///
/// The input is `[24, 1, 6 * frames]` — the caller's `[144, frames]` latent, reinterpreted. The
/// output is `[512, 1, 6 * frames]`, which the caller reads transposed as
/// `frames * SAMPLES_PER_FRAME` samples.
pub fn build(weights: &dyn WeightSource, frames: u32) -> Result<Plan, String> {
    if frames == 0 {
        return Err("a vocoder pass over no frames".into());
    }
    let positions = frames
        .checked_mul(COMPRESS)
        .ok_or("a latent longer than an index can hold")?;

    let l = &mut Layers { next: 0 };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // Every convolution in this network replicates its border. See the module docs.
    b.edge_padding();

    let input = b.input(Shape::new(LATENT, 1, positions));
    // `embed` also carries the input's per-channel affine, folded in by the converter.
    let mut x = along(
        b,
        l,
        input,
        Along { out: CHANNELS, kernel: DEPTHWISE, dilation: 1, group: 1, act: Act::None },
    );

    for &dilation in &DILATIONS {
        let depthwise = along(
            b,
            l,
            x,
            Along {
                out: CHANNELS,
                kernel: DEPTHWISE,
                dilation,
                group: CHANNELS,
                act: Act::None,
            },
        );
        let normed = b.layer_norm(depthwise, l.take(), EPSILON);
        let widened = point(b, l, normed, INNER, Act::Gelu);
        // `pwconv2` carries the block's layer scale, folded in by the converter.
        let narrowed = point(b, l, widened, CHANNELS, Act::None);
        x = b.add(x, narrowed);
    }

    // `head/layer1` carries `final_norm`, folded in. Its PReLU slope is one shared number in the
    // export, which the converter widens to a value per channel.
    let slope = l.next + 2;
    let widened = along(
        b,
        l,
        x,
        Along { out: INNER, kernel: 3, dilation: 1, group: 1, act: Act::PRelu(slope) },
    );
    l.take_one();
    let samples = point(b, l, widened, SAMPLES_PER_POSITION, Act::None);

    if l.next != TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {TENSORS}", l.next));
    }
    builder.finish(&[samples])
}

/// A convolution along the sequence, padded so the length is unchanged.
///
/// The padding is **causal**: the whole `dilation * (kernel - 1)` goes on the left and none on
/// the right, which is what the export's `Pad` nodes carry — `[0, 0, 6, 0, 0, 0]` for a 7-tap at
/// dilation 1, growing to 12 and 24 at dilations 2 and 4. So each output position sees only
/// itself and its past, which is what lets a vocoder stream.
///
/// Implementing this symmetrically instead shifts the signal a little further at every one of
/// the eleven padded convolutions. The output stays the right length and the right magnitude and
/// correlates with the reference at 0.02, which sounds like noise rather than like a bug.
struct Along {
    /// Output channels.
    out: u32,
    /// Kernel width along the sequence.
    kernel: u32,
    /// Dilation along the sequence.
    dilation: u32,
    /// Convolution groups; `CHANNELS` for the depthwise ones.
    group: u32,
    act: Act,
}

fn along(b: &mut Builder, l: &mut Layers, x: Id, shape: Along) -> Id {
    let Along { out, kernel, dilation, group, act } = shape;
    let past = dilation * (kernel - 1);
    b.conv(
        x,
        l.take(),
        out,
        (1, kernel),
        (1, 1),
        (1, dilation),
        // (top, left, bottom, right): everything on the left.
        (0, past, 0, 0),
        group,
        act,
    )
}

/// A `1 x 1` convolution, which the tiled shader serves.
fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv(x, l.take(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// About 3.8 seconds of audio, which is what the reference sentence produced.
    const FRAMES: u32 = 54;

    fn plan(frames: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, frames).expect("the vocoder builds");
        (source, plan)
    }

    #[test]
    fn unpacking_the_latent_interleaves_rather_than_reshaping() {
        // Two frames of the 144 packed channels, each value encoding its own (channel, frame) so
        // the mapping is readable. A plain reshape would give a different answer for every
        // position except those where `p % 6 == 0`, which is why it correlated at 0.009 rather
        // than failing outright.
        let frames = 2usize;
        let packed = PACKED as usize;
        let latent: Vec<f32> =
            (0..packed * frames).map(|i| (i / frames) as f32 * 100.0 + (i % frames) as f32).collect();
        let got = unpack_latent(&latent, frames).expect("unpacks");
        let positions = frames * COMPRESS as usize;
        assert_eq!(got.len(), LATENT as usize * positions);
        // Channel 0, position 0..12: source channel `p % 6`, source frame `p / 6`.
        let want: Vec<f32> = (0..positions)
            .map(|p| (p % 6) as f32 * 100.0 + (p / 6) as f32)
            .collect();
        assert_eq!(&got[0..positions], &want[..]);
        // Channel 3 starts at packed channel 18, so its first position is 1800.
        assert_eq!(got[3 * positions], 1800.0);
        // And a plain reshape would have put packed channel 3 frame 0 there instead.
        assert_ne!(got[3 * positions], 300.0);
    }

    #[test]
    fn unpacking_refuses_a_latent_of_the_wrong_length() {
        let error = unpack_latent(&[0.0; 10], 2).expect_err("wrong length");
        assert!(error.contains("10 latent values"), "{error}");
    }

    #[test]
    fn interleaving_reads_the_output_plane_as_a_waveform() {
        // Two positions of the 512 channels. Sample `t * 512 + s` is channel `s` at position `t`.
        let channels = SAMPLES_PER_POSITION as usize;
        let plane: Vec<f32> = (0..channels * 2).map(|i| i as f32).collect();
        let got = interleave(&plane);
        // Channel 0 position 0, then channel 1 position 0, ...
        assert_eq!(got[0], 0.0);
        assert_eq!(got[1], 2.0);
        assert_eq!(got[channels], 1.0);
        assert_eq!(got.len(), plane.len());
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
    fn the_tensor_table_matches_the_export() {
        // The export's weighted layers — convolutions, the ten layer norms, `final_norm` and the
        // PReLU — total 25,333,249 parameters. Three differences, each derived rather than
        // guessed, because a first attempt at this arithmetic was wrong by 5,167:
        //
        //   - `final_norm`'s four `[512]` vectors are folded into `head/layer1`:      -2,048
        //   - the PReLU's single shared slope is widened to one per channel:      +2,048 - 1
        //   - `head/layer2` has no bias in the export, so one is synthesised:        +512
        //
        // The input's `[24]` affine and the ten `[512]` layer scales do not appear: they are
        // `Mul`/`Add` nodes rather than layer weights, so they were never in the 25,333,249, and
        // folding them changes the values of `embed` and each `pwconv2` without changing any
        // count.
        let (source, _) = plan(FRAMES);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();
        assert_eq!(total, 25_333_249 - 2_048 + 2_048 - 1 + 512);
        // And spelled out, so a reordering that preserved the sum would still be caught.
        assert_eq!(total, 25_333_760);
    }

    #[test]
    fn the_input_is_the_unpacked_latent_and_the_output_is_one_sample_per_channel() {
        let (_, plan) = plan(FRAMES);
        // 24 channels over six times the frames, not 144 over the frames: the reinterpretation
        // happens in the caller's head, not in a shader.
        assert_eq!(
            plan.input().expect("one input").shape,
            Shape::new(LATENT, 1, FRAMES * COMPRESS)
        );
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(SAMPLES_PER_POSITION, 1, FRAMES * COMPRESS)
        );
        // Which is 3072 samples per latent frame, the figure measured against onnxruntime.
        let samples = SAMPLES_PER_POSITION * FRAMES * COMPRESS;
        assert_eq!(samples, FRAMES * SAMPLES_PER_FRAME);
    }

    #[test]
    fn every_convolution_replicates_its_border() {
        // The whole network is edge-padded. A single zero-padded convolution would corrupt the
        // ends of the waveform and leave the middle correct, which is an audible click at the
        // start and finish of every utterance and nothing else.
        let (_, plan) = plan(FRAMES);
        let mut convolutions = 0;
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
                assert_ne!(push.pad_edge, 0, "step {step} pads with zeros");
                convolutions += 1;
            }
        }
        // The 1x1s go to the tiled shader, which has no border to pad: `embed`, ten depthwise
        // and `head/layer1`.
        assert_eq!(convolutions, 1 + BLOCKS + 1);
    }

    #[test]
    fn nothing_changes_the_sequence_length() {
        let (_, plan) = plan(FRAMES);
        let positions = FRAMES * COMPRESS;
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind, push, .. } = op {
                if matches!(kind, Kind::Conv | Kind::ConvPoint) {
                    assert_eq!(push.out_w, positions, "step {step} changed the length");
                }
            }
        }
    }

    #[test]
    fn the_dilations_are_the_exports_own_sequence() {
        // 1, 2, 4, 1, 2, 4, then four more at 1. Not a repeating cycle, which is why it is a
        // table: assuming `3^(i % 3)` or a continuing 1/2/4 would be wrong from block six on.
        let (_, plan) = plan(FRAMES);
        let found: Vec<u32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                // The depthwise convolutions are the grouped ones.
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.group == CHANNELS => {
                    Some(push.dil_w)
                }
                _ => None,
            })
            .collect();
        assert_eq!(found, DILATIONS.to_vec());
    }

    #[test]
    fn the_op_inventory_is_ten_convnext_blocks() {
        let (_, plan) = plan(FRAMES);
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0) += 1;
            }
        }
        // embed, ten depthwise, head/layer1, ten pwconv1, ten pwconv2, head/layer2.
        assert_eq!(counts.get("Conv"), Some(&(1 + BLOCKS + 1 + BLOCKS * 2 + 1)), "{counts:?}");
        assert_eq!(counts.get("LayerNorm"), Some(&BLOCKS), "{counts:?}");
        assert_eq!(counts.get("Add"), Some(&BLOCKS), "{counts:?}");
        // No transposed convolution: the upsample is the output reinterpretation.
        assert_eq!(counts.get("ConvTranspose"), None, "{counts:?}");
        assert_eq!(counts.len(), 3, "{counts:?}");
    }

    #[test]
    fn a_zero_length_latent_is_refused() {
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
    fn the_arena_is_bounded_at_a_long_utterance() {
        for frames in [FRAMES, 216] {
            let (_, plan) = plan(frames);
            let mib = plan.arena_elems as f32 * 2.0 / (1024.0 * 1024.0);
            println!("supertonic_vocoder at {frames} frames: {mib:.2} MiB");
            assert!(mib < 512.0, "{frames} frames wants {mib} MiB");
        }
    }
}
