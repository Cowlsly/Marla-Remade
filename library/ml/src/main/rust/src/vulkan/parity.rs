//! The shaders, against [`crate::nets::reference`].
//!
//! Every parity number recorded for this runtime so far compares onnxruntime against the *host
//! interpreter*. That checks the forward passes in `nets/` — which is where the transcription
//! mistakes are — and says nothing whatever about the SPIR-V in `shaders/`, because until the
//! Vulkan layer built on the host there was no way to run it anywhere but a phone. A shader that
//! indexed a tensor wrongly would have shipped with every structural check green.
//!
//! So these run the same plan twice, once through `nets::reference` and once through
//! [`Net`], and require the two to agree. The interpreter is the oracle: it is the thing the
//! ONNX parity scripts already validated.
//!
//! # Running them
//!
//! `#[ignore]`d, because a host with no Vulkan must still pass `cargo test`:
//!
//! ```text
//! cargo test -p modelrunner --lib -- --ignored vulkan::parity
//! ```
//!
//! # Tolerance
//!
//! Not exact equality. Both sides store activations as fp16, but a shader reduces in parallel
//! and the interpreter sums left to right, so a dot product of `k` terms can differ in the last
//! fp16 place. The comparison is therefore relative to the magnitude of the tensor rather than
//! absolute — an absolute threshold either passes everything for a small output or fails a large
//! one for nothing.

use std::sync::{Arc, OnceLock};

use crate::nets::reference::{run_multi, Given, Invented};
use crate::nets::{embed_lanes, Act, Builder, Id, Plan, Shape, WeightSource};
use crate::preprocess::RESCALE_ONLY;
use crate::weights::{graph, write_mixed, Fixture, Weights};

use super::context::{self, Context};
use super::run::Net;

/// Fraction of the tensor's own scale two runs of the same plan may differ by.
///
/// fp16 carries about three decimal digits, so a single rounding is ~5e-4 relative. These plans
/// are a handful of ops deep, and the loosest of them — a softmax over an attention score map —
/// compounds that a few times.
const TOLERANCE: f32 = 4e-3;

/// The shared device, or the reason there is none.
///
/// Not a `panic`: a machine with no Vulkan is a legitimate host for everything else in this
/// crate, and the tests below are `#[ignore]`d precisely so that machine is never asked.
/// The shared device, held for the lifetime of the test process.
///
/// Deliberately not a fresh [`Context`] per test, and deliberately never dropped: every net in
/// the process then shares one device, which is what `:camera` does with its two segmenters and
/// so the arrangement whose thread safety is worth exercising. Letting the last `Arc` go between
/// tests would instead tear down and recreate the `VkInstance` concurrently with another test's
/// net — something no caller of this runtime ever does.
fn device() -> Arc<Context> {
    static HELD: OnceLock<Arc<Context>> = OnceLock::new();
    HELD.get_or_init(|| context::shared().expect("this host has no usable Vulkan device")).clone()
}

/// Run `plan` on the device and return its outputs, in [`Plan::outputs`] order.
fn on_device(plan: Plan, data: Vec<u8>, inputs: &[&[f32]]) -> Vec<Vec<f32>> {
    let weights = Weights::from_data(data);
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
        .expect("the plan records into a command buffer");
    net.infer_raw_many(inputs).expect("the command buffer submits and reads back")
}

/// Build `record`'s plan against `tensors`, run it both ways and require the two to agree.
///
/// Going through the real [`Builder`] is the point, as it is for the interpreter's own fixtures:
/// the shape propagation and the arena offsets are what a shader is indexing against, so a plan
/// assembled by hand would not be testing the thing that breaks.
fn agrees(
    what: &str,
    shapes: &[Shape],
    inputs: &[&[f32]],
    tensors: &[(Vec<u32>, Vec<f32>)],
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) {
    let given = Given::new(tensors).expect("the fixture tensors are consistent");
    let plan = build(&given, shapes, record);
    compare(what, plan, given.data().to_vec(), inputs);
}

/// [`agrees`], for a whole net whose weights are invented rather than given.
fn agrees_invented(
    what: &str,
    count: usize,
    shapes: &[Shape],
    inputs: &[&[f32]],
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) {
    let source = Invented::new(count);
    let plan = build(&source, shapes, record);
    compare(what, plan, source.into_data(), inputs);
}

fn build(
    source: &dyn WeightSource,
    shapes: &[Shape],
    record: impl FnOnce(&mut Builder, &[Id]) -> Id,
) -> Plan {
    let mut builder = Builder::new(source);
    let ids: Vec<Id> = shapes.iter().map(|&shape| builder.input(shape)).collect();
    let last = record(&mut builder, &ids);
    builder.finish(&[last]).expect("the fixture plan builds")
}

fn compare(what: &str, plan: Plan, data: Vec<u8>, inputs: &[&[f32]]) {
    let host = run_multi(&plan, &data, inputs).expect("the interpreter runs the plan");
    let got = on_device(plan, data, inputs);
    matches(what, &host, &got);
}

/// Require the interpreter's outputs and the device's to agree, one binding at a time.
fn matches(what: &str, host: &[Vec<f32>], got: &[Vec<f32>]) {
    assert_eq!(got.len(), host.len(), "{what}: output count");
    for (index, (host, got)) in host.iter().zip(got).enumerate() {
        assert_eq!(got.len(), host.len(), "{what}: output {index} length");
        // Scale from the interpreter's output, not from the pair, so a device result that came
        // back as zeros cannot shrink the threshold until it passes.
        let scale = host.iter().fold(0.0f32, |top, v| top.max(v.abs())).max(1e-3);
        let worst = host
            .iter()
            .zip(got)
            .enumerate()
            .max_by(|(_, (a, b)), (_, (c, d))| {
                (*a - *b).abs().total_cmp(&(*c - *d).abs())
            })
            .map(|(at, (a, b))| (at, *a, *b));
        if let Some((at, expected, actual)) = worst {
            let error = (expected - actual).abs() / scale;
            assert!(
                error <= TOLERANCE,
                "{what}: output {index} element {at} is {actual} on the device and {expected} \
                 on the host, {error} of the tensor's {scale} scale"
            );
        }
        // A shader that wrote nothing leaves the arena at whatever the last op left, which for
        // the first op in a plan is zero — and a zero tensor is within tolerance of a zero one.
        assert!(host.iter().any(|&v| v != 0.0), "{what}: the interpreter's output is all zeros");
    }
}

/// Values that are distinct, bounded and not symmetric about zero.
///
/// Symmetric inputs hide sign errors: a shader that swapped the halves of a rotary pair, or
/// transposed a score map, produces the right answer on data that happens to be even.
fn spread(count: usize, seed: f32) -> Vec<f32> {
    (0..count).map(|i| (i as f32 * 0.7 + seed).sin() * 0.8 + 0.1).collect()
}

#[test]
#[ignore = "needs a Vulkan device"]
fn the_cnn_spine_agrees_with_the_reference() {
    // Conv with a fused activation, the squeeze-excite pair, max pool, bilinear resize, add,
    // concat and a transposed convolution: the ops the two vision nets are made of.
    let input = spread(3 * 8 * 8, 0.0);
    agrees_invented(
        "the cnn spine",
        8,
        &[Shape::new(3, 8, 8)],
        &[&input],
        |b, ids| {
            let x = b.conv(ids[0], 0, 8, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::HardSwish);
            let pooled = b.global_avg_pool(x);
            let gate = b.conv_same(pooled, 2, 8, 1, 1, Act::Sigmoid);
            let gated = b.mul_channel(x, gate);
            let deep = b.max_pool_2x2(gated);
            let deep = b.conv_same(deep, 4, 8, 3, 1, Act::Relu);
            let up = b.resize_like(deep, gated);
            let merged = b.add(gated, up);
            let joined = b.concat(&[merged, up]);
            b.conv_transpose(joined, 6, 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::Sigmoid)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn rotary_agrees_with_the_reference() {
    // The half-split convention: `out[j] = x[j] cos - x[j + half] sin`, within each head. A
    // shader that paired adjacent channels instead — the other common convention — gets the
    // same shape and the same magnitude, so only the values catch it.
    let x = spread(8 * 4, 0.0);
    let angles = spread(4 * 4, 1.3);
    agrees(
        "rotary",
        &[Shape::new(8, 1, 4), Shape::new(4, 1, 4)],
        &[&x, &angles],
        &[],
        |b, ids| b.rotary(ids[0], ids[1], 2),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_folded_constant_agrees_with_the_reference() {
    // The only op that reads nothing from the arena, so it is also the only one whose output
    // offset the shader cannot cross-check against an input it just read.
    let x = spread(4 * 3, 0.4);
    let folded = spread(4 * 3, 2.1);
    agrees(
        "a folded constant",
        &[Shape::new(4, 1, 3)],
        &[&x],
        &[(vec![4, 1, 3], folded)],
        |b, ids| {
            let constant = b.constant(0, Shape::new(4, 1, 3));
            b.add(ids[0], constant)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_per_channel_shift_agrees_with_the_reference() {
    // `a + b` where b is C x 1 x 1. The mirror of mul_channel, and the one thing Supertonic's
    // four timestep conditioning layers need.
    let x = spread(4 * 3, 0.2);
    let shift = spread(4, 3.0);
    agrees(
        "a per-channel shift",
        &[Shape::new(4, 1, 3), Shape::new(4, 1, 1)],
        &[&x, &shift],
        &[],
        |b, ids| b.add_channel(ids[0], ids[1]),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn attention_agrees_with_the_reference() {
    // Deliberately not self-attention: `Q . K^T` is symmetric when Q == K, so a fixture built
    // from one tensor passes with the operands swapped and with the query and key axes
    // transposed. Four queries against five keys makes the score map rectangular, which pins
    // the orientation as well as the arithmetic.
    let q = spread(8 * 4, 0.0);
    let k = spread(8 * 5, 1.7);
    let v = spread(8 * 5, 2.9);
    agrees(
        "attention",
        &[Shape::new(8, 1, 4), Shape::new(8, 1, 5), Shape::new(8, 1, 5)],
        &[&q, &k, &v],
        &[],
        |b, ids| {
            let scores = b.attn_scores(ids[0], ids[1], 2);
            let probs = b.softmax(scores);
            b.attn_apply(probs, ids[2], 2)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn layer_norm_over_channels_agrees_with_the_reference() {
    // The reduction is strided here, not contiguous: these sequences are [d_model, 1, T], so
    // normalising over channels reads a stride apart. A shader that reduced over W instead
    // still writes plausible unit-variance numbers.
    let x = spread(8 * 5, 0.6);
    let gamma = spread(8, 1.1);
    let beta = spread(8, 2.3);
    agrees(
        "layer norm",
        &[Shape::new(8, 1, 5)],
        &[&x],
        &[(vec![8], gamma), (vec![8], beta)],
        |b, ids| b.layer_norm(ids[0], 0, 1e-5),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_two_lane_embedding_agrees_with_the_reference() {
    // Past 2048 rows an id does not survive a single fp16 lane, so it arrives as lo + 2048 * hi.
    // A shader that read only the low lane returns a real row of the table — the wrong one.
    let rows = 3000u32;
    let channels = 4u32;
    let ids = [7u32, 2048, 2999];
    let lanes = embed_lanes(&ids);
    let table = spread((rows * channels) as usize, 0.9);
    agrees(
        "a two-lane embedding",
        &[Shape::new(2, 1, ids.len() as u32)],
        &[&lanes],
        &[(vec![rows, channels], table)],
        |b, ids| b.embed(ids[0], 0, rows, channels),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn an_int8_convolution_agrees_with_the_reference() {
    // The only op whose weights are not fp16, so the only one where the shader and the interpreter
    // address the weights buffer differently: 32-bit words unpacked to bytes on one side, a byte
    // index into the undecoded blob on the other. Four output channels with four *different*
    // scales, because a shader that read `scale[0]` for every channel — which is what this op did
    // before the export turned out to be quantised per column — still returns the right shape and
    // three wrong channels.
    let out_channels = 4u32;
    let in_channels = 3u32;
    let width = 5u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 7) % 61 - 30) as i8)
        .collect();
    let scales: Vec<f32> = vec![0.25, 0.5, 0.0625, 1.0];
    let biases: Vec<f32> = vec![0.5, -0.25, 1.0, 0.0];
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread((in_channels * width) as usize, 0.3);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, width));
    let last = builder.conv_int8(
        first,
        0,
        out_channels,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::Relu,
    );
    let plan = builder.finish(&[last]).expect("the int8 fixture plan builds");
    compare("an int8 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_rebuilt_net_agrees_with_the_reference_at_each_shape() {
    // Supertonic runs one set of weights at whatever length the utterance turns out to be, so
    // `Net::rebuild` re-records a net instead of building a second one and uploading 198 MB
    // again. The widths go up and then back down on purpose: growing reallocates the arena and
    // rewrites the descriptor, while shrinking keeps the buffer it already has, and it is the
    // second visit to a narrow shape that would catch a descriptor left pointing at the arena
    // that was freed under it.
    let gamma = spread(8, 1.1);
    let beta = spread(8, 2.3);
    let given = Given::new(&[(vec![8], gamma), (vec![8], beta)])
        .expect("the fixture tensors are consistent");
    let data = given.data().to_vec();
    // Two ops, so the second reads out of the arena what the first wrote into it. A one-op plan
    // would pass on a rebind that had gone to the wrong buffer, since its only read is the input
    // copy the command buffer performs by handle rather than through the descriptor set.
    let at = |width: u32| {
        build(&given, &[Shape::new(8, 1, width)], |b, ids| {
            let normed = b.layer_norm(ids[0], 0, 1e-5);
            b.add(normed, ids[0])
        })
    };

    let weights = Weights::from_data(data.clone());
    let mut net = Net::new(device(), at(3), &weights, RESCALE_ONLY)
        .expect("the plan records into a command buffer");
    for width in [9u32, 4] {
        let plan = at(width);
        let input = spread(8 * width as usize, width as f32);
        let host = run_multi(&plan, &data, &[&input]).expect("the interpreter runs the plan");
        net.rebuild(plan).expect("the net re-records at the new shape");
        let got = net.infer_raw(&input).expect("the rebuilt buffer submits and reads back");
        matches(&format!("a net rebuilt at {width}"), &host, &got);
    }
}
