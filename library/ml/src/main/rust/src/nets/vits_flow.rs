//! Piper's VITS normalising flow: the `flow` module, run in reverse.
//!
//! # What it is
//!
//! Four residual coupling layers with a channel reversal between each, applied in reverse
//! order. Each coupling splits its 192 channels in half, runs a four-layer WaveNet over the
//! first half to predict a shift for the second, and subtracts it. 196 ONNX nodes and 80
//! tensors.
//!
//! In VITS the flow maps the prior the encoder produced into the latent the vocoder decodes.
//! At inference it runs backwards, which for a mean-only coupling is a subtraction rather
//! than the division the forward direction needs.
//!
//! # Three things the export leaves in that are not there
//!
//! Each read from the graph rather than assumed:
//!
//! * **The log-scale is zero.** The coupling is mean-only, so its `post` convolution emits
//!   96 channels — a shift and no scale. The export still builds the scale: a
//!   `ConstantOfShape` of zeros, a `Neg` and an `Exp`, which is `exp(-0) = 1`, and a `Mul`
//!   by it. Four of each, all identity.
//! * **The mask is all ones.** 32 `Mul`s by the padding mask, which for one unpadded
//!   utterance is the identity — the same argument as [`super::vits_enc`].
//! * **The flips are one `Slice` each.** `torch.flip(x, [1])` exports as a slice with a
//!   negative step, not as a permutation table.
//!
//! # Why the flips are not folded away
//!
//! A fixed channel permutation could be pushed into the convolutions either side of it. It is
//! [`super::Kind::FlipChannels`] instead because the fold has to thread through a split and a
//! concatenation, and what it saves is one pass over `[192, 1, T]`. Without the flips a
//! coupling would only ever transform the same half of the channels.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Channels in and out, VITS's `inter_channels`.
pub const CHANNELS: u32 = 192;

/// Channels each coupling layer transforms, which is half of them.
pub const HALF: u32 = CHANNELS / 2;

/// The WaveNet's width inside a coupling layer.
pub const HIDDEN: u32 = 192;

/// Coupling layers, each preceded by a channel reversal.
pub const COUPLINGS: usize = 4;

/// Convolutions in each coupling's WaveNet.
const WAVENET_LAYERS: usize = 4;

/// Tensors the `.maml` must hold: ten convolutions per coupling, each a weight and a bias.
///
/// `pre`, four `in_layers`, four `res_skip_layers` and `post` — `4 * 10 * 2`.
pub const TENSORS: usize = 80;

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
}

/// One residual coupling layer, in the reverse direction.
///
/// `x0` conditions a WaveNet whose output shifts `x1`; `x0` itself passes through. Returns
/// the concatenation, which is what the next flip reverses.
fn coupling(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let x0 = b.slice_channels(x, 0, HALF);
    let x1 = b.slice_channels(x, HALF, HALF);

    // The WaveNet. `skip` accumulates every layer's contribution; the first layer starts it
    // rather than adding to a zero tensor, which would be a pass over 192 x T of nothing.
    let mut h = b.conv(x0, l.take(), HIDDEN, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
    let mut skip: Option<Id> = None;
    for layer in 0..WAVENET_LAYERS {
        let last = layer == WAVENET_LAYERS - 1;
        // Kernel five, padded by two, so the length is unchanged. Every dilation in this
        // voice is one, which is not WaveNet's usual 1/3/9/27 — read from the export.
        let gated = b.conv(
            h,
            l.take(),
            HIDDEN * 2,
            (1, 5),
            (1, 1),
            (1, 1),
            (0, 2, 0, 2),
            1,
            Act::None,
        );
        let acts = b.gated_tanh(gated);
        // The last layer's projection emits only a skip; the others emit a residual too.
        let width = if last { HIDDEN } else { HIDDEN * 2 };
        let projected =
            b.conv(acts, l.take(), width, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
        let contribution = if last {
            projected
        } else {
            let residual = b.slice_channels(projected, 0, HIDDEN);
            h = b.add(h, residual);
            b.slice_channels(projected, HIDDEN, HIDDEN)
        };
        skip = Some(match skip {
            Some(total) => b.add(total, contribution),
            None => contribution,
        });
    }
    let skip = skip.expect("a WaveNet with no layers");

    let shift = b.conv(skip, l.take(), HALF, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
    // Reverse of `x1 * exp(logs) + m` at `logs == 0`, which is a subtraction. There is no
    // subtract op, and negating the shift is one pass over 96 x T.
    let negated = b.affine(shift, -1.0, 0.0);
    let shifted = b.add(x1, negated);
    b.concat(&[x0, shifted])
}

/// Build the flow for an utterance of `frames` latent frames.
///
/// Input and output are both `[192, 1, frames]`: the prior sampled at frame rate in, the
/// latent the vocoder decodes out.
pub fn build(weights: &dyn WeightSource, frames: u32) -> Result<Plan, String> {
    if frames == 0 {
        return Err("a flow pass over no frames".into());
    }

    let l = &mut Layers { next: 0 };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    let input = b.input(Shape::new(CHANNELS, 1, frames));

    // Reverse order, so a flip comes first: the export's node order is flows.7, flows.6,
    // flows.5, ... and the odd-numbered flows are the flips.
    let mut x = input;
    for _ in 0..COUPLINGS {
        x = b.flip_channels(x);
        x = coupling(b, l, x);
    }

    if l.next != TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {TENSORS}", l.next));
    }
    builder.finish(&[x])
}

#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// About a second of audio at 16 kHz and 256 samples a frame.
    const FRAMES: u32 = 64;

    fn plan(frames: u32) -> (Shapes, Plan) {
        let source = Shapes::new(TENSORS);
        let plan = build(&source, frames).expect("the flow builds");
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
    fn the_tensor_table_matches_the_export() {
        // Every parameter the `flow` module of en_GB-alan-low holds. This pins the WaveNet's
        // widths, including that the *last* projection emits 192 channels and the other three
        // emit 384 — a detail no structural test would notice.
        let (source, _) = plan(FRAMES);
        let total: u64 = source
            .asked
            .borrow()
            .iter()
            .map(|(_, dims)| dims.iter().map(|&d| d as u64).product::<u64>())
            .sum();
        assert_eq!(total, 7_090_560);
    }

    #[test]
    fn the_flow_preserves_its_shape() {
        // A normalising flow is a bijection, so in and out are the same shape. A coupling
        // that concatenated its halves the wrong way round would still be 192 channels, but
        // the tensor count check above would not catch it — the ordering test below does.
        let (_, plan) = plan(FRAMES);
        let shape = Shape::new(CHANNELS, 1, FRAMES);
        assert_eq!(plan.input().expect("one input").shape, shape);
        assert_eq!(plan.output().expect("one output").shape, shape);
    }

    #[test]
    fn every_convolution_holds_the_frame_count() {
        let (_, plan) = plan(FRAMES);
        for (step, op) in plan.ops.iter().enumerate() {
            if let Op::Dispatch { kind: Kind::Conv, push, .. } = op {
                assert_eq!(push.out_w, FRAMES, "step {step} changed the length");
            }
        }
    }

    #[test]
    fn the_op_inventory_is_four_couplings_each_with_a_flip() {
        let (_, plan) = plan(FRAMES);
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0) += 1;
            }
        }
        assert_eq!(counts.get("Conv"), Some(&40), "{counts:?}");
        assert_eq!(counts.get("FlipChannels"), Some(&COUPLINGS), "{counts:?}");
        assert_eq!(counts.get("GatedTanh"), Some(&(COUPLINGS * WAVENET_LAYERS)), "{counts:?}");
        // One negation of the shift per coupling.
        assert_eq!(counts.get("Affine"), Some(&COUPLINGS), "{counts:?}");
        // Three residual adds, three skip accumulations and the shift, per coupling.
        assert_eq!(counts.get("Add"), Some(&(COUPLINGS * 7)), "{counts:?}");
        assert_eq!(counts.len(), 5, "{counts:?}");
        // Two splits of the input, six inside the WaveNet, and two copies for the
        // concatenation — all `Op::Copy`, no shader.
        let copies = plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).count();
        assert_eq!(copies, COUPLINGS * 10, "{copies}");
    }

    #[test]
    fn the_wavenet_widens_to_384_and_narrows_back() {
        // `in_layers` emits twice the hidden width because the gated activation consumes a
        // filter and a gate together; the gate halves it again.
        let (_, plan) = plan(FRAMES);
        let gates: Vec<(u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::GatedTanh, push, .. } => Some((push.out_c, push.count)),
                _ => None,
            })
            .collect();
        assert_eq!(gates.len(), COUPLINGS * WAVENET_LAYERS);
        for (channels, count) in gates {
            assert_eq!(channels, HIDDEN);
            assert_eq!(count, HIDDEN * FRAMES);
        }
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
        println!("vits_flow at {FRAMES} frames: {} elements, {mib:.2} MiB", plan.arena_elems);
        assert!(mib < 64.0, "{mib} MiB");
    }
}
