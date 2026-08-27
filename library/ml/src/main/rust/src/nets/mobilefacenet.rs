//! MobileFaceNet (`w600k_mbf`), the 512-d face embedding `:photos` clusters people by.
//!
//! # Shape of the network
//!
//! A MobileNetV2-style trunk at a fixed 112x112: a four-convolution stem down to
//! 28x28, then three stages of [`residual`] blocks — 4 at 128 channels, 6 at 256, 2 at
//! 256 again — separated by transitions that widen and halve the resolution. It ends
//! with a global depthwise-ish head: 1x1 to 512, 1x1 down to 64, and a fully-connected
//! layer to 512.
//!
//! 112 -> 56 -> 28 -> 14 -> 7, on four stride-2 convolutions.
//!
//! # Three things worth knowing about it
//!
//! * **Every activation is a `PRelu`, and every one fuses.** All 34 follow a `Conv`
//!   directly, so this net dispatches no standalone activation pass — see [`Act::PRelu`],
//!   which carries the slope tensor's index. The slope is per *channel*, so a shader
//!   that broadcast one value would still produce a plausible-looking embedding.
//! * **The final `Gemm` is a convolution here, not an inner product.** Its weight is
//!   `[512, 3136]` over a flattened `64 x 7 x 7`, and `scripts/ml/vkml_convert.py`
//!   reshapes that to `[512, 64, 7, 7]` — a kernel covering the whole spatial extent,
//!   which the existing `conv.comp` computes exactly. That is why there is no
//!   `InnerProduct` pipeline.
//! * **The `BatchNormalization` after it is folded into that layer** by the converter,
//!   so it is invisible here. A batch norm at inference is a per-channel affine, so the
//!   fold is exact rather than an approximation.
//!
//! The **L2 normalisation of the output stays in Kotlin**, where `FaceRecognizer`
//! already does it. The embedding this returns is unnormalised, as ncnn's was.
//!
//! Preprocessing is `(value - 127.5) / 127.5` on an aligned 112x112 crop. Note the
//! divisor: [`super::scrfd`] uses `1 / 128`, and sharing a constant between the two
//! would be wrong in one of them.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};

/// The side length the graph is lowered at. Fixed, unlike [`super::scrfd`].
pub const SIZE: u32 = 112;
/// Length of the embedding this produces.
pub const EMBEDDING: u32 = 512;
/// Tensors in the file: 49 convolutions and one folded `Gemm` at two each, plus 34
/// `PRelu` slopes at one.
pub const TENSORS: usize = 134;

/// Hands out `.vkml` tensor indices in the order the layers appear.
///
/// Unlike [`super::selfie`]'s, this cannot be a counter that steps by two: a `PRelu`
/// contributes one tensor and a convolution two, so the two kinds are asked for
/// separately and the stride is whatever the graph's order implies. `--print-layers`
/// reports the resulting index next to each layer, which is what this was transcribed
/// against.
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

    /// A `PRelu`'s per-channel slope.
    fn slope(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }
}

/// A 1x1 convolution, which is most of this net.
fn project(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: bool) -> Id {
    let weight = l.conv();
    let act = if act { Act::PRelu(l.slope()) } else { Act::None };
    b.conv(x, weight, out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// A 3x3 depthwise convolution with a fused `PRelu`, `pads = [1, 1, 1, 1]`.
fn depthwise(b: &mut Builder, l: &mut Layers, x: Id, stride: u32) -> Id {
    let channels = b.shape(x).c;
    let weight = l.conv();
    let slope = l.slope();
    b.conv(
        x,
        weight,
        channels,
        (3, 3),
        (stride, stride),
        (1, 1),
        (1, 1, 1, 1),
        channels,
        Act::PRelu(slope),
    )
}

/// One residual block: expand 1x1, depthwise 3x3, project 1x1, and add the input back.
///
/// The projection has **no** activation — the `PRelu` sits before the add, not after
/// it — which is what makes the residual path linear.
fn residual(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let channels = b.shape(x).c;
    let expanded = project(b, l, x, channels, true);
    let spatial = depthwise(b, l, expanded, 1);
    let projected = project(b, l, spatial, channels, false);
    b.add(x, projected)
}

/// A transition: widen to `wide`, halve the resolution, then project to `out`.
fn transition(b: &mut Builder, l: &mut Layers, x: Id, wide: u32, out: u32) -> Id {
    let widened = project(b, l, x, wide, true);
    let halved = depthwise(b, l, widened, 2);
    project(b, l, halved, out, false)
}

/// Compile the whole forward pass.
pub fn build(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut b = Builder::new(weights);
    let mut layers = Layers { next: 0 };
    let l = &mut layers;
    let input = b.input(Shape::new(3, SIZE, SIZE));

    // Stem: 112 -> 56 -> 28.
    let weight = l.conv();
    let slope = l.slope();
    let x = b.conv(
        input,
        weight,
        128,
        (3, 3),
        (2, 2),
        (1, 1),
        (1, 1, 1, 1),
        1,
        Act::PRelu(slope),
    );
    // A grouped 3x3 at `group = 64`, so two input channels per group — neither dense
    // nor depthwise, and the one layer in either face model that is genuinely both.
    let weight = l.conv();
    let slope = l.slope();
    let x = b.conv(
        x,
        weight,
        128,
        (3, 3),
        (1, 1),
        (1, 1),
        (1, 1, 1, 1),
        64,
        Act::PRelu(slope),
    );
    let x = project(&mut b, l, x, 128, true);
    let x = depthwise(&mut b, l, x, 2);
    let mut x = project(&mut b, l, x, 128, false);

    // 4 blocks at 128 channels, 28x28.
    for _ in 0..4 {
        x = residual(&mut b, l, x);
    }

    // 28 -> 14, widening to 256.
    let mut x = transition(&mut b, l, x, 256, 256);
    // 6 blocks at 256 channels, 14x14.
    for _ in 0..6 {
        x = residual(&mut b, l, x);
    }

    // 14 -> 7. Widens to 512 and comes back down to 256, which is why `transition`
    // takes both counts.
    let mut x = transition(&mut b, l, x, 512, 256);
    // 2 blocks at 256 channels, 7x7.
    for _ in 0..2 {
        x = residual(&mut b, l, x);
    }

    // Head: 512, then a narrow 64 that the fully-connected layer reads.
    let x = project(&mut b, l, x, 512, true);
    let narrow = project(&mut b, l, x, 64, true);
    // The `Gemm`, as a convolution whose kernel is the whole 7x7 map. Output is
    // `512 x 1 x 1`, which the host reads as a flat 512-vector.
    let weight = l.conv();
    let embedding = b.conv(
        narrow,
        weight,
        EMBEDDING,
        (SIZE / 16, SIZE / 16),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::None,
    );
    b.finish(&[embedding])
}

#[cfg(test)]
mod tests {
    use super::super::tests::Shapes;
    use super::super::{Kind, Op};
    use super::*;

    fn plan() -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source).expect("mobilefacenet builds")
    }

    fn dispatches(plan: &Plan, kind: Kind) -> usize {
        plan.ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: k, .. } if *k == kind))
            .count()
    }

    #[test]
    fn the_op_inventory_matches_the_onnx() {
        // The counts `scripts/ml/vkml_convert.py` asserts against the ONNX itself. The
        // 49 convolutions include the `Gemm`, which is lowered as one, so the export's
        // 49 `Conv` plus 1 `Gemm` is 50 dispatches here.
        let plan = plan();
        assert_eq!(dispatches(&plan, Kind::Conv), 50);
        assert_eq!(dispatches(&plan, Kind::Add), 12);
        // No pooling, no resize, no concatenation and no transposed convolution: this is
        // the plainest graph in the runtime.
        assert_eq!(dispatches(&plan, Kind::ConvTranspose), 0);
        assert_eq!(dispatches(&plan, Kind::MaxPool), 0);
        assert_eq!(dispatches(&plan, Kind::Resize), 0);
        assert_eq!(dispatches(&plan, Kind::ResizeNearest), 0);
        assert_eq!(dispatches(&plan, Kind::GlobalAvgPool), 0);
        assert_eq!(dispatches(&plan, Kind::MulBroadcast), 0);
        assert_eq!(plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).count(), 0);
    }

    #[test]
    fn the_pass_consumes_the_whole_tensor_table() {
        let source = Shapes::new(TENSORS);
        assert!(build(&source).is_ok());
        assert_eq!(source.asked.borrow().len(), TENSORS);
    }

    #[test]
    fn every_convolution_but_the_residual_projections_fuses_a_prelu() {
        // 34 `PRelu` in the export, all fused. The 16 that do not have one are the 12
        // residual projections, the 3 transition projections and the final `Gemm` —
        // exactly the layers whose output is added to or read as a result.
        let plan = plan();
        let fused = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { push, .. } if push.act == Act::PRelu(0).code()))
            .count();
        assert_eq!(fused, 34);
    }

    #[test]
    fn each_fused_prelu_reads_its_own_slope() {
        // A transcription that reused one index, or that let the counter drift by
        // treating a slope as two tensors, would land several layers on the same offset.
        let plan = plan();
        let mut slopes: Vec<u32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { push, .. } if push.act == Act::PRelu(0).code() => {
                    Some(push.act_weight)
                }
                _ => None,
            })
            .collect();
        let total = slopes.len();
        slopes.sort_unstable();
        slopes.dedup();
        assert_eq!(slopes.len(), total, "two layers share a PReLU slope");
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
        assert_eq!(shape_at(0), vec![128, 3, 3, 3]);
        // Its slope, one tensor after the bias. A rank-3 `[c, 1, 1]`, which is what
        // ONNX exports a per-channel `PRelu` as.
        assert_eq!(shape_at(2), vec![128, 1, 1]);
        // Tensor 3, the `group = 64` convolution: two input channels per group, so
        // `[128, 2, 3, 3]`. Read as depthwise it would be `[128, 1, 3, 3]`.
        assert_eq!(shape_at(3), vec![128, 2, 3, 3]);
        // Tensor 9, the first depthwise proper.
        assert_eq!(shape_at(9), vec![128, 1, 3, 3]);
        // Tensor 46, the first transition widening to 256.
        assert_eq!(shape_at(46), vec![256, 128, 1, 1]);
        // Tensor 129, the narrow 64-channel layer the fully-connected one reads.
        assert_eq!(shape_at(129), vec![64, 512, 1, 1]);
        // Tensor 132, the `Gemm` as a 7x7 kernel over 64 channels. `[512, 3136]` — the
        // shape it has in the ONNX — would mean the converter did not reshape it.
        assert_eq!(shape_at(132), vec![512, 64, 7, 7]);
    }

    #[test]
    fn the_input_and_output_are_the_shapes_kotlin_expects() {
        let plan = plan();
        assert_eq!(plan.input().expect("one input").shape, Shape::new(3, SIZE, SIZE));
        // `512 x 1 x 1`, which the host reads as a flat 512-vector.
        assert_eq!(plan.output().expect("one output").shape, Shape::new(EMBEDDING, 1, 1));
    }

    #[test]
    fn the_resolution_halves_exactly_four_times() {
        // 112 -> 56 -> 28 -> 14 -> 7. If the stem's grouped 3x3 were given stride 2 by
        // mistake the net would still build, at a quarter of the spatial extent, and the
        // final 7x7 kernel would then cover the whole map of a *different* size.
        let plan = plan();
        let strided: Vec<u32> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::Conv, push, .. } if push.stride_h == 2 => {
                    Some(push.in_h)
                }
                _ => None,
            })
            .collect();
        assert_eq!(strided, vec![112, 56, 28, 14]);
    }

    #[test]
    fn no_op_reads_a_region_it_also_writes() {
        super::super::tests::assert_no_aliasing(&plan());
    }

    #[test]
    fn the_arena_is_bounded() {
        let plan = plan();
        let bytes = plan.arena_elems as u64 * 2;
        // 128x56x56 fp16 is 800 KB and is the widest thing here, so a few MB is the
        // whole net. This runs once per detected face during an indexing pass.
        assert!(bytes < 8 * 1024 * 1024, "arena is {bytes} bytes");
    }
}
