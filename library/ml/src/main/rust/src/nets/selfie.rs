//! MediaPipe Selfie Segmentation, the `:camera` portrait matte, at a fixed 256x256.
//!
//! # Shape of the network
//!
//! A MobileNetV3-style encoder — inverted-residual blocks of expand 1x1, depthwise
//! kxk, squeeze-excite, project 1x1 — down to 16x16, then three decoder blocks back up
//! to 128x128, then a 2x2 transposed convolution to 256x256 and a sigmoid.
//!
//! Unlike U^2-Netp this graph is irregular, so it is written out layer by layer against
//! the ordered `.vkml` indices, with the three repeated shapes ([`squeeze_excite`],
//! [`inverted_residual`], [`final_block`]) factored out. The layer table it is
//! transcribed from is `vkml_convert.py --print-layers` on the pinned ONNX.
//!
//! # Three things about it that are easy to get wrong
//!
//! * **The first three strided convolutions pad asymmetrically** — ONNX `pads`
//!   `[0, 0, 1, 1]`, i.e. nothing above or left, one below and right — and the 5x5 is
//!   `[1, 1, 2, 2]`. Padding them symmetrically still produces the right output size,
//!   and a mask shifted half a pixel, which is exactly the kind of error nothing here
//!   would catch.
//! * **Two squeeze-excite blocks gate a different tensor than they measure.** The
//!   `conv7`/`conv8` pair pools `block4`'s output but multiplies `conv7`'s, and each
//!   [`final_block`] pools the sum but multiplies the skip. Reusing
//!   [`squeeze_excite`] for those would be wrong.
//! * **Every activation fuses into the convolution before it.** All 22 ReLUs, 11
//!   HardSwishes and 11 Sigmoids in the export directly follow a `Conv`, so this net
//!   dispatches no standalone activation pass.
//!
//! Preprocessing is a rescale by 1/255 with **no** mean/std — the upstream processor
//! sets `do_normalize: false` — which is unlike the ImageNet normalisation
//! [`super::u2netp`] needs.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// The side length the graph is lowered at.
pub const SIZE: u32 = 256;
/// Tensors in the file: 55 layers, weight and bias each.
pub const TENSORS: usize = 110;

/// Hands out `.vkml` tensor indices in the order the layers appear. See
/// [`super::u2netp`] for why this is a counter.
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

/// Squeeze-excite: pool `x` to one value per channel, squeeze to `reduce` channels
/// through a ReLU, expand back through a sigmoid, and scale `x` by the result.
///
/// Only for the case where the tensor measured is also the tensor gated. The two
/// places that differ are written out at their call sites.
fn squeeze_excite(b: &mut Builder, layers: &mut Layers, x: Id, reduce: u32) -> Id {
    let channels = b.shape(x).c;
    let pooled = b.global_avg_pool(x);
    let squeezed = b.conv_same(pooled, layers.take(), reduce, 1, 1, Act::Relu);
    let gate = b.conv_same(squeezed, layers.take(), channels, 1, 1, Act::Sigmoid);
    b.mul_channel(x, gate)
}

/// An inverted-residual block at unchanged resolution: expand, depthwise, gate,
/// project, and add the input back.
fn inverted_residual(
    b: &mut Builder,
    layers: &mut Layers,
    x: Id,
    expand: u32,
    kernel: u32,
    reduce: u32,
    out: u32,
) -> Id {
    let expanded = b.conv_same(x, layers.take(), expand, 1, 1, Act::HardSwish);
    // Depthwise: one group per channel, so the kernel is `[expand, 1, k, k]`.
    let pad = kernel / 2;
    let spatial = b.conv(
        expanded,
        layers.take(),
        expand,
        (kernel, kernel),
        (1, 1),
        (1, 1),
        (pad, pad, pad, pad),
        expand,
        Act::HardSwish,
    );
    let gated = squeeze_excite(b, layers, spatial, reduce);
    let projected = b.conv_same(gated, layers.take(), out, 1, 1, Act::None);
    b.add(x, projected)
}

/// One decoder stage: upsample `deep` to `skip`'s resolution, project it to `skip`'s
/// channel count, and merge the two through a gate and a depthwise residual.
///
/// Note which tensor each half uses: the gate is *measured* from `projected + skip` but
/// *applied* to `skip` alone, and then `projected` is added a second time. That is what
/// the export does, and it is not the same as gating the sum.
fn final_block(b: &mut Builder, layers: &mut Layers, deep: Id, skip: Id, channels: u32) -> Id {
    let up = b.resize_like(deep, skip);
    let projected = b.conv_same(up, layers.take(), channels, 1, 1, Act::None);
    let merged = b.add(projected, skip);

    let pooled = b.global_avg_pool(merged);
    let squeezed = b.conv_same(pooled, layers.take(), channels, 1, 1, Act::Relu);
    let gate = b.conv_same(squeezed, layers.take(), channels, 1, 1, Act::Sigmoid);
    let gated = b.mul_channel(skip, gate);
    let combined = b.add(projected, gated);

    let refined = b.conv_same(combined, layers.take(), channels, 1, 1, Act::Relu);
    let depthwise = b.conv(
        refined,
        layers.take(),
        channels,
        (3, 3),
        (1, 1),
        (1, 1),
        (1, 1, 1, 1),
        channels,
        Act::Relu,
    );
    b.add(refined, depthwise)
}

/// Compile the whole forward pass.
pub fn build(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut b = Builder::new(weights, Shape::new(3, SIZE, SIZE));
    let mut layers = Layers { next: 0 };
    let l = &mut layers;
    let input = b.input();

    // Stem. `pads = [0, 0, 1, 1]` on all three strided 3x3s: no padding above or left.
    // 256 -> 128 -> 64 -> 32.
    let stem = b.conv(input, l.take(), 16, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::HardSwish);
    let x = b.conv_same(stem, l.take(), 16, 1, 1, Act::Relu);
    let x = b.conv(x, l.take(), 16, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 16, Act::Relu);
    let x = squeeze_excite(&mut b, l, x, 8);
    // Kept for the third decoder skip.
    let skip_64 = b.conv_same(x, l.take(), 16, 1, 1, Act::None);

    let x = b.conv_same(skip_64, l.take(), 72, 1, 1, Act::Relu);
    let x = b.conv(x, l.take(), 72, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 72, Act::Relu);
    let projected = b.conv_same(x, l.take(), 24, 1, 1, Act::None);

    // A residual pair at 32x32, without a squeeze-excite: this one is a plain
    // inverted residual, which is why it is not `inverted_residual` above.
    let x = b.conv_same(projected, l.take(), 88, 1, 1, Act::Relu);
    let x = b.conv(x, l.take(), 88, (3, 3), (1, 1), (1, 1), (1, 1, 1, 1), 88, Act::Relu);
    let x = b.conv_same(x, l.take(), 24, 1, 1, Act::None);
    // Kept for the second decoder skip.
    let skip_32 = b.add(projected, x);

    // 32 -> 16, on a 5x5 depthwise with `pads = [1, 1, 2, 2]`.
    let x = b.conv_same(skip_32, l.take(), 96, 1, 1, Act::HardSwish);
    let x = b.conv(x, l.take(), 96, (5, 5), (2, 2), (1, 1), (1, 1, 2, 2), 96, Act::HardSwish);
    let x = squeeze_excite(&mut b, l, x, 24);
    let x = b.conv_same(x, l.take(), 32, 1, 1, Act::None);

    // Four inverted-residual blocks at 16x16. The last two narrow the expansion from
    // 128 to 96 channels.
    let x = inverted_residual(&mut b, l, x, 128, 5, 32, 32);
    let x = inverted_residual(&mut b, l, x, 128, 5, 32, 32);
    let x = inverted_residual(&mut b, l, x, 96, 5, 24, 32);
    let trunk = inverted_residual(&mut b, l, x, 96, 5, 24, 32);

    // A squeeze-excite spanning two tensors: the gate is measured from `trunk` but
    // applied to `wide`, and there is no ReLU-squeeze stage.
    let wide = b.conv_same(trunk, l.take(), 128, 1, 1, Act::Relu);
    let pooled = b.global_avg_pool(trunk);
    let gate = b.conv_same(pooled, l.take(), 128, 1, 1, Act::Sigmoid);
    let bottleneck = b.mul_channel(wide, gate);

    // Decoder: 16 -> 32 -> 64 -> 128.
    let x = final_block(&mut b, l, bottleneck, skip_32, 24);
    let x = final_block(&mut b, l, x, skip_64, 16);
    let x = final_block(&mut b, l, x, stem, 16);

    // 128 -> 256, and the sigmoid that makes it an alpha.
    let alphas = b.conv_transpose(x, l.take(), 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::Sigmoid);
    b.finish(alphas)
}

#[cfg(test)]
mod tests {
    use super::super::tests::Shapes;
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source).expect("selfie builds")
    }

    fn dispatches(plan: &Plan, kind: Kind) -> usize {
        plan.ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: k, .. } if *k == kind))
            .count()
    }

    #[test]
    fn the_op_inventory_matches_the_onnx() {
        // The counts `scripts/ml/vkml_convert.py` asserts against the ONNX itself.
        let plan = plan();
        assert_eq!(dispatches(&plan, Kind::Conv), 54);
        assert_eq!(dispatches(&plan, Kind::ConvTranspose), 1);
        assert_eq!(dispatches(&plan, Kind::Add), 14);
        assert_eq!(dispatches(&plan, Kind::MulBroadcast), 10);
        assert_eq!(dispatches(&plan, Kind::GlobalAvgPool), 10);
        assert_eq!(dispatches(&plan, Kind::Resize), 3);
        // No pooling, and no real concatenation: the export's three `Concat` nodes are
        // all axis-0 shape scaffolding for the resize targets, which folds away here.
        assert_eq!(dispatches(&plan, Kind::MaxPool), 0);
        assert_eq!(plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).count(), 0);
    }

    #[test]
    fn the_pass_consumes_the_whole_tensor_table() {
        let source = Shapes::new(TENSORS);
        assert!(build(&source).is_ok());
        assert_eq!(source.asked.borrow().len(), TENSORS);
    }

    #[test]
    fn the_layer_table_matches_the_onnx_export() {
        let source = Shapes::new(TENSORS);
        let _ = build(&source);
        let asked = source.asked.borrow();
        let shape_at = |index: usize| -> Vec<u32> {
            asked
                .iter()
                .find(|(i, _)| *i == index)
                .map(|(_, dims)| dims.clone())
                .unwrap_or_default()
        };
        // Layer 0, the stem.
        assert_eq!(shape_at(0), vec![16, 3, 3, 3]);
        // Layer 2, the first depthwise: `[16, 1, 3, 3]` is what group == channels
        // looks like, and the shape most likely to be wrong if grouping is misread.
        assert_eq!(shape_at(4), vec![16, 1, 3, 3]);
        // Layers 3 and 4, the first squeeze-excite: 16 -> 8 -> 16.
        assert_eq!(shape_at(6), vec![8, 16, 1, 1]);
        assert_eq!(shape_at(8), vec![16, 8, 1, 1]);
        // Layer 13, the 5x5 depthwise that drops to 16x16.
        assert_eq!(shape_at(26), vec![96, 1, 5, 5]);
        // Layer 39, the first decoder projection: 128 channels in, 24 out.
        assert_eq!(shape_at(78), vec![24, 128, 1, 1]);
        // Layer 54, the transposed convolution. Its weight is `[in, out/group, k, k]`
        // rather than `[out, in/group, k, k]` — the one layout inversion in either net.
        assert_eq!(shape_at(108), vec![16, 1, 2, 2]);
    }

    #[test]
    fn the_input_and_output_are_the_shapes_kotlin_expects() {
        let plan = plan();
        assert_eq!(plan.input_shape, Shape::new(3, SIZE, SIZE));
        assert_eq!(plan.output_shape, Shape::new(1, SIZE, SIZE));
    }

    #[test]
    fn the_strided_convolutions_keep_their_asymmetric_padding() {
        // Symmetric padding would give the same output sizes and a mask shifted half a
        // pixel, so the asymmetry is asserted rather than trusted to the transcription.
        let plan = plan();
        let strided: Vec<(u32, u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.stride_h == 2 => {
                    Some((push.kh, push.pad_t, push.pad_l, push.in_h))
                }
                _ => None,
            })
            .collect();
        assert_eq!(
            strided,
            vec![
                // The three 3x3s: `pads = [0, 0, 1, 1]`, so nothing above or left.
                (3, 0, 0, 256),
                (3, 0, 0, 128),
                (3, 0, 0, 64),
                // The 5x5: `pads = [1, 1, 2, 2]`.
                (5, 1, 1, 32),
            ]
        );
    }

    #[test]
    fn the_two_cross_tensor_gates_do_not_multiply_what_they_measured() {
        // The `conv7`/`conv8` pair and all three decoder blocks pool one tensor and
        // scale another. If `squeeze_excite` had been reused for them, every one of
        // these would read its own pooled input instead.
        let plan = plan();
        let crossed = plan
            .ops
            .iter()
            .enumerate()
            .filter(|(_, op)| matches!(op, Op::Dispatch { kind: Kind::MulBroadcast, .. }))
            .filter(|(index, op)| {
                let gated = match op {
                    Op::Dispatch { push, .. } => push.in0,
                    _ => return false,
                };
                // Walk back to the pool that produced this gate's chain and compare
                // what it read against what is being scaled.
                plan.ops[..*index]
                    .iter()
                    .rev()
                    .find_map(|earlier| match earlier {
                        Op::Dispatch { kind: Kind::GlobalAvgPool, push, .. } => Some(push.in0),
                        _ => None,
                    })
                    .is_some_and(|measured| measured != gated)
            })
            .count();
        assert_eq!(crossed, 4);
    }

    #[test]
    fn no_op_reads_a_region_it_also_writes() {
        super::super::tests::assert_no_aliasing(&plan());
    }

    #[test]
    fn the_arena_is_bounded() {
        let plan = plan();
        let bytes = plan.arena_elems as u64 * 2;
        // This one runs on the 15 fps preview path, so its footprint matters more than
        // U^2-Netp's. 256x256x16 fp16 is 2 MB; a handful of those is the whole net.
        assert!(bytes < 16 * 1024 * 1024, "arena is {bytes} bytes");
    }
}
