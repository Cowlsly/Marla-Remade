//! U^2-Net *portable*, the `:photos` salient-object detector, at a fixed 320x320.
//!
//! # Why this is written as RSU blocks and not 119 transcribed layers
//!
//! The upstream ONNX is a dynamic-shape export: 119 `Conv`, 33 `MaxPool`, 38 `Resize`
//! and 51 real `Concat`, buried in 266 `Constant` and 114 `Shape` nodes that exist
//! only to compute resize targets at run time. All of that scaffolding folds away at a
//! fixed 320x320 and is ignored.
//!
//! What is left is textbook U^2-Netp — six nested residual U-blocks down, five back up,
//! six side outputs fused by a 1x1. Writing it as [`rsu7`]..[`rsu4f`] rather than as a
//! flat list of layers is not a shortcut: the block structure is what makes the
//! ordered weight indices predictable, so a drift between this file and the converter
//! shows up as a shape mismatch on a specific layer instead of as a plausible-looking
//! but wrong mask. The counts above are all rederived and asserted in [`tests`].
//!
//! Channel widths are uniform: every block is `(in, mid = 16, out = 64)`, which is what
//! makes this the "portable" variant rather than full U^2-Net's widening stack.
//!
//! # Memory
//!
//! The packed activation arena is **76 MiB** at 320x320, on top of 2.1 MiB of weights.
//! That is large, and it is measured rather than estimated — `cargo test --test assets
//! -- --nocapture` prints it.
//!
//! It is the graph's live set, not allocator waste: a 64-channel tensor at full
//! resolution is 13 MiB, `stage1d` is fed the 128-channel concatenation of two of them,
//! and `stage1`'s output has to stay resident across the whole decoder to be that
//! concatenation's second half. Best fit and first fit reach the same figure.
//!
//! It is accepted because this runs once, on a deliberate tap, in an app that already
//! holds full-resolution bitmaps of comparable size, and `SubjectSegmenter` frees it on
//! `close`. The lever if that stops being true is to allocate a concatenation's
//! destination first and let its producers write straight into their own slice, which
//! would remove one full-resolution copy per decoder stage.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// The side length the graph is lowered at. Every `Resize` target and every pooled
/// extent below is a power-of-two division of it.
pub const SIZE: u32 = 320;

/// Channels every block's internal convolutions run at.
const MID: u32 = 16;
/// Channels every block emits, and every side output reads.
const OUT: u32 = 64;
/// Tensors in the file: 119 layers, weight and bias each.
pub const TENSORS: usize = 238;

/// Hands out `.maml` tensor indices in the order the layers appear.
///
/// The whole contract with `scripts/ml/maml_convert.py` is this order, so it is
/// consumed by a counter rather than written out per layer: a counter cannot
/// accidentally skip or repeat an index, and [`Builder::finish`] rejects a pass that
/// did not consume the file exactly.
struct Layers {
    next: usize,
}

impl Layers {
    /// The index of the next layer's weight; its bias is the one after.
    fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }
}

/// `Conv 3x3 + ReLU` with `pad == dilation`, so the spatial size is unchanged.
///
/// Upstream calls this `REBNCONV` — convolution, batch norm, ReLU — but the export has
/// the batch norm folded into the convolution's weight and bias, which is why there is
/// no normalisation op anywhere in this runtime.
fn rebnconv(b: &mut Builder, layers: &mut Layers, x: Id, out: u32, dilation: u32) -> Id {
    b.conv_same(x, layers.take(), out, 3, dilation, Act::Relu)
}

/// The deepest block: five pools down, a dilated bottom, five bilinear steps back up.
fn rsu7(b: &mut Builder, layers: &mut Layers, x: Id) -> Id {
    let hxin = rebnconv(b, layers, x, OUT, 1);

    let hx1 = rebnconv(b, layers, hxin, MID, 1);
    let hx = b.max_pool_2x2(hx1);
    let hx2 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx2);
    let hx3 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx3);
    let hx4 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx4);
    let hx5 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx5);
    let hx6 = rebnconv(b, layers, hx, MID, 1);

    // The bottom of the U is dilated rather than pooled, which is how an RSU keeps a
    // wide receptive field without a sixth halving.
    let hx7 = rebnconv(b, layers, hx6, MID, 2);

    let hx = b.concat(&[hx7, hx6]);
    let hx6d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx6d, hx5);
    let hx = b.concat(&[up, hx5]);
    let hx5d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx5d, hx4);
    let hx = b.concat(&[up, hx4]);
    let hx4d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx4d, hx3);
    let hx = b.concat(&[up, hx3]);
    let hx3d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx3d, hx2);
    let hx = b.concat(&[up, hx2]);
    let hx2d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx2d, hx1);
    let hx = b.concat(&[up, hx1]);
    let hx1d = rebnconv(b, layers, hx, OUT, 1);

    b.add(hx1d, hxin)
}

/// Four pools down. One level shallower than [`rsu7`].
fn rsu6(b: &mut Builder, layers: &mut Layers, x: Id) -> Id {
    let hxin = rebnconv(b, layers, x, OUT, 1);

    let hx1 = rebnconv(b, layers, hxin, MID, 1);
    let hx = b.max_pool_2x2(hx1);
    let hx2 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx2);
    let hx3 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx3);
    let hx4 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx4);
    let hx5 = rebnconv(b, layers, hx, MID, 1);
    let hx6 = rebnconv(b, layers, hx5, MID, 2);

    let hx = b.concat(&[hx6, hx5]);
    let hx5d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx5d, hx4);
    let hx = b.concat(&[up, hx4]);
    let hx4d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx4d, hx3);
    let hx = b.concat(&[up, hx3]);
    let hx3d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx3d, hx2);
    let hx = b.concat(&[up, hx2]);
    let hx2d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx2d, hx1);
    let hx = b.concat(&[up, hx1]);
    let hx1d = rebnconv(b, layers, hx, OUT, 1);

    b.add(hx1d, hxin)
}

/// Three pools down.
fn rsu5(b: &mut Builder, layers: &mut Layers, x: Id) -> Id {
    let hxin = rebnconv(b, layers, x, OUT, 1);

    let hx1 = rebnconv(b, layers, hxin, MID, 1);
    let hx = b.max_pool_2x2(hx1);
    let hx2 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx2);
    let hx3 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx3);
    let hx4 = rebnconv(b, layers, hx, MID, 1);
    let hx5 = rebnconv(b, layers, hx4, MID, 2);

    let hx = b.concat(&[hx5, hx4]);
    let hx4d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx4d, hx3);
    let hx = b.concat(&[up, hx3]);
    let hx3d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx3d, hx2);
    let hx = b.concat(&[up, hx2]);
    let hx2d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx2d, hx1);
    let hx = b.concat(&[up, hx1]);
    let hx1d = rebnconv(b, layers, hx, OUT, 1);

    b.add(hx1d, hxin)
}

/// Two pools down. The shallowest block that still pools.
fn rsu4(b: &mut Builder, layers: &mut Layers, x: Id) -> Id {
    let hxin = rebnconv(b, layers, x, OUT, 1);

    let hx1 = rebnconv(b, layers, hxin, MID, 1);
    let hx = b.max_pool_2x2(hx1);
    let hx2 = rebnconv(b, layers, hx, MID, 1);
    let hx = b.max_pool_2x2(hx2);
    let hx3 = rebnconv(b, layers, hx, MID, 1);
    let hx4 = rebnconv(b, layers, hx3, MID, 2);

    let hx = b.concat(&[hx4, hx3]);
    let hx3d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx3d, hx2);
    let hx = b.concat(&[up, hx2]);
    let hx2d = rebnconv(b, layers, hx, MID, 1);
    let up = b.resize_like(hx2d, hx1);
    let hx = b.concat(&[up, hx1]);
    let hx1d = rebnconv(b, layers, hx, OUT, 1);

    b.add(hx1d, hxin)
}

/// The "F" block: no pooling and no resizing at all. Depth comes from dilation
/// 1/2/4/8 instead, which is what lets the two deepest stages run at 20x20 and 10x10
/// without shrinking further.
fn rsu4f(b: &mut Builder, layers: &mut Layers, x: Id) -> Id {
    let hxin = rebnconv(b, layers, x, OUT, 1);

    let hx1 = rebnconv(b, layers, hxin, MID, 1);
    let hx2 = rebnconv(b, layers, hx1, MID, 2);
    let hx3 = rebnconv(b, layers, hx2, MID, 4);
    let hx4 = rebnconv(b, layers, hx3, MID, 8);

    let hx = b.concat(&[hx4, hx3]);
    let hx3d = rebnconv(b, layers, hx, MID, 4);
    let hx = b.concat(&[hx3d, hx2]);
    let hx2d = rebnconv(b, layers, hx, MID, 2);
    let hx = b.concat(&[hx2d, hx1]);
    let hx1d = rebnconv(b, layers, hx, OUT, 1);

    b.add(hx1d, hxin)
}

/// Compile the whole forward pass.
///
/// Of the seven outputs the ONNX declares — the fused `d0` and the six per-scale side
/// maps — only `d0` is produced. `MlSegmentation.kt` never read the others.
pub fn build(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut b = Builder::new(weights);
    let mut layers = Layers { next: 0 };
    let l = &mut layers;

    // Encoder. Each stage halves the resolution: 320, 160, 80, 40, 20, 10.
    let input = b.input(Shape::new(3, SIZE, SIZE));
    let hx1 = rsu7(&mut b, l, input);
    let hx = b.max_pool_2x2(hx1);
    let hx2 = rsu6(&mut b, l, hx);
    let hx = b.max_pool_2x2(hx2);
    let hx3 = rsu5(&mut b, l, hx);
    let hx = b.max_pool_2x2(hx3);
    let hx4 = rsu4(&mut b, l, hx);
    let hx = b.max_pool_2x2(hx4);
    let hx5 = rsu4f(&mut b, l, hx);
    let hx = b.max_pool_2x2(hx5);
    let hx6 = rsu4f(&mut b, l, hx);

    // Decoder. Each stage takes the concatenation of the upsampled deeper result and
    // the encoder skip at the same resolution, hence the 128-channel block inputs.
    let up = b.resize_like(hx6, hx5);
    let hx = b.concat(&[up, hx5]);
    let hx5d = rsu4f(&mut b, l, hx);
    let up = b.resize_like(hx5d, hx4);
    let hx = b.concat(&[up, hx4]);
    let hx4d = rsu4(&mut b, l, hx);
    let up = b.resize_like(hx4d, hx3);
    let hx = b.concat(&[up, hx3]);
    let hx3d = rsu5(&mut b, l, hx);
    let up = b.resize_like(hx3d, hx2);
    let hx = b.concat(&[up, hx2]);
    let hx2d = rsu6(&mut b, l, hx);
    let up = b.resize_like(hx2d, hx1);
    let hx = b.concat(&[up, hx1]);
    let hx1d = rsu7(&mut b, l, hx);

    // Six side maps, each a 3x3 down to one channel, all resized to full resolution
    // and fused by a 1x1. The sigmoid is the last thing the net does, so it fuses
    // into that 1x1 and there is no standalone activation pass anywhere.
    let d1 = b.conv_same(hx1d, l.take(), 1, 3, 1, Act::None);
    let mut sides = vec![d1];
    for source in [hx2d, hx3d, hx4d, hx5d, hx6] {
        let side = b.conv_same(source, l.take(), 1, 3, 1, Act::None);
        let up = b.resize_like(side, d1);
        sides.push(up);
    }
    let fused = b.concat(&sides);
    let d0 = b.conv_same(fused, l.take(), 1, 1, 1, Act::Sigmoid);

    b.finish(&[d0])
}

#[cfg(test)]
mod tests {
    use super::super::tests::Shapes;
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source).expect("u2netp builds")
    }

    /// Whether two kinds are the same graph operation, folding `Conv`''s tiled lowering in.
    fn same_op(found: Kind, wanted: Kind) -> bool {
        let fold = |k: Kind| if matches!(k, Kind::ConvPoint) { Kind::Conv } else { k };
        fold(found) == fold(wanted)
    }

    fn dispatches(plan: &Plan, kind: Kind) -> usize {
        plan.ops
            .iter()
            // `ConvPoint` is a lowering of `Conv`, not a different graph op: an ungrouped 1x1
            // goes to the tiled shader. These tests are about what the network contains, so the
            // two are counted as one.
            .filter(|op| matches!(op, Op::Dispatch { kind: k, .. } if same_op(*k, kind)))
            .count()
    }

    #[test]
    fn the_op_inventory_matches_the_onnx() {
        // These are the counts `scripts/ml/maml_convert.py` asserts against the ONNX
        // itself, rederived here from the RSU structure. If the two ever disagree, one
        // of them is wrong about the network.
        let plan = plan();
        assert_eq!(dispatches(&plan, Kind::Conv), 119);
        assert_eq!(dispatches(&plan, Kind::MaxPool), 33);
        assert_eq!(dispatches(&plan, Kind::Resize), 38);
        assert_eq!(dispatches(&plan, Kind::Add), 11);
        // 51 real Concats in the ONNX; the other 76 are shape scaffolding. Each lowers
        // to one copy per part, and every one here joins exactly two parts except the
        // final six-way side-output fuse.
        let copies = plan.ops.iter().filter(|op| matches!(op, Op::Copy { .. })).count();
        assert_eq!(copies, 50 * 2 + 6);
        // Nothing else: no ConvTranspose, no squeeze-excite.
        assert_eq!(dispatches(&plan, Kind::ConvTranspose), 0);
        assert_eq!(dispatches(&plan, Kind::GlobalAvgPool), 0);
        assert_eq!(dispatches(&plan, Kind::MulBroadcast), 0);
    }

    #[test]
    fn the_pass_consumes_the_whole_tensor_table() {
        // `Builder::finish` enforces this, so reaching here at all is the assertion;
        // the explicit count guards against `TENSORS` drifting from the converter.
        let source = Shapes::new(TENSORS);
        assert!(build(&source).is_ok());
        assert_eq!(source.asked.borrow().len(), TENSORS);
    }

    #[test]
    fn the_layer_table_matches_the_onnx_export() {
        // Spot-checks at the boundaries the structure is derived from, taken from
        // `maml_convert.py --print-layers` on the pinned ONNX. Every one of these is a
        // place a miscounted block would first show up.
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
        // Layer 0: the only 3-channel input in the net.
        assert_eq!(shape_at(0), vec![64, 3, 3, 3]);
        // Layer 13 closes stage1's RSU7 back up to 64 channels.
        assert_eq!(shape_at(26), vec![64, 32, 3, 3]);
        // Layer 30 (stage5, the first RSU4F) is 64 -> 64: no concat feeding it.
        assert_eq!(shape_at(88), vec![64, 64, 3, 3]);
        // Layer 30's decoder counterpart takes a 128-channel concat.
        assert_eq!(shape_at(120), vec![64, 128, 3, 3]);
        // Layers 112..117: the six side outputs, 64 -> 1.
        assert_eq!(shape_at(224), vec![1, 64, 3, 3]);
        assert_eq!(shape_at(234), vec![1, 64, 3, 3]);
        // Layer 118: the 1x1 that fuses the six side maps.
        assert_eq!(shape_at(236), vec![1, 6, 1, 1]);
    }

    #[test]
    fn the_input_and_output_are_the_shapes_kotlin_expects() {
        let plan = plan();
        assert_eq!(plan.input().expect("one input").shape, Shape::new(3, SIZE, SIZE));
        assert_eq!(plan.output().expect("one output").shape, Shape::new(1, SIZE, SIZE));
    }

    #[test]
    fn the_arena_is_far_smaller_than_the_sum_of_the_intermediates() {
        let plan = plan();
        // Measured at 76 MiB. Bounded rather than pinned, so a refactor that shuffles
        // allocation order does not fail the build, but one that breaks reuse — which
        // would put this in the hundreds of MB — does.
        let bytes = plan.arena_elems as u64 * 2;
        assert!(bytes < 88 * 1024 * 1024, "arena is {bytes} bytes");
        // And it must be big enough for the two largest single tensors to coexist:
        // stage1d's RSU7 input is a 128-channel concat at full resolution.
        assert!(bytes > 128 * SIZE as u64 * SIZE as u64 * 2, "arena is {bytes} bytes");
    }

    #[test]
    fn u2netp_pools_only_even_extents() {
        // `Builder::max_pool_2x2` fails on an odd extent because ONNX marks these
        // ceil_mode=1 and this runtime floors. 320 halves five times to 10, so the two
        // agree — but only at this input size, which is why SIZE is not a parameter.
        let mut side = SIZE;
        for _ in 0..5 {
            assert_eq!(side % 2, 0, "{side} is odd");
            side /= 2;
        }
        assert_eq!(side, 10);
    }

    #[test]
    fn no_op_reads_a_region_it_also_writes() {
        super::super::tests::assert_no_aliasing(&plan());
    }
}
