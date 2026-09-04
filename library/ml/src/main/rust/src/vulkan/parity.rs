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
//! # Running them segmented
//!
//! [`super::segment`] windows the weights buffer when it is larger than
//! `maxStorageBufferRange`, and on any device this runtime actually targets it never is — so the
//! windowed path would otherwise ship as dead code. Re-running the whole suite with the range
//! forced down exercises it against the same oracle:
//!
//! ```text
//! MODELRUNNER_MAX_STORAGE_RANGE=33554432 \
//!   cargo test -p modelrunner --lib -- --ignored vulkan::parity
//! ```
//!
//! Every number must be identical to the unforced run: windowing changes which descriptor set is
//! bound and what the three weights offsets in [`crate::nets::Push`] are measured from, and
//! nothing else. The variable is read once, when the [`Context`] is created, which is why it is set
//! on the command line rather than inside a test - the device here is process-wide and shared.
//!
//! # Running them on the phone
//!
//! A desktop GPU passing these says nothing about Adreno, which is the only device this runtime
//! actually ships to, and audio is far less forgiving of a shader that is subtly wrong than a
//! segmentation mask is. The test binary cross-compiles and runs from a shell, because `ash` loads
//! `libvulkan.so` at run time rather than linking it:
//!
//! ```text
//! NDK=$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/<host>/bin
//! CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android31-clang \
//!   cargo test --release -p modelrunner --lib --no-run --target aarch64-linux-android
//! adb push target/aarch64-linux-android/release/deps/modelrunner-<hash> /data/local/tmp/mr_test
//! adb shell chmod 755 /data/local/tmp/mr_test
//! adb shell /data/local/tmp/mr_test --ignored vulkan::parity
//! ```
//!
//! The fixtures above need no assets and so run anywhere. The one that reads the shipped `.maml`
//! needs them pushed, and returns quietly when they are absent rather than failing a run that never
//! had them:
//!
//! ```text
//! adb push speech/src/main/assets/supertonic /data/local/tmp/
//! adb shell MODELRUNNER_ASSETS=/data/local/tmp/supertonic \
//!   /data/local/tmp/mr_test --ignored --nocapture the_shipped_supertonic
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
use crate::weights::{graph, write_mixed, Fixture, Streamed, Weights};

use super::context::{self, Context};
use super::run::{Net, StepParams};

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

/// Run `plan` on the device with the step parameters a decode plan reads.
fn on_device_at(plan: Plan, data: Vec<u8>, inputs: &[&[f32]], prefix: u32) -> Vec<Vec<f32>> {
    let weights = Weights::from_data(data);
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
        .expect("the plan records into a command buffer");
    net.set_params(StepParams { prefix, window_start: 0 })
        .expect("the step params are written");
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
fn cached_attention_agrees_with_the_reference() {
    // A whole decode-step self-attention: one query against a position-major cache, through
    // softmax and back out channel-major. `nets::reference` is the only oracle for the two new
    // shaders, so this is what says the SPIR-V matches it on real hardware.
    //
    // d_model 8 in two heads, five cached positions. The cache is `[5, 1, 8]` — positions as
    // channels — which is the layout that makes appending one position a single contiguous copy.
    // A shader that read it channel-major would produce the right shape from the wrong vectors.
    let query = spread(8, 0.0);
    let cache = spread(5 * 8, 0.7);
    agrees(
        "cached attention",
        &[Shape::new(8, 1, 1), Shape::new(5, 1, 8)],
        &[&query, &cache],
        &[],
        |b, ids| {
            let scores = b.attn_scores_cached(ids[0], ids[1], 2);
            let probs = b.softmax(scores);
            b.attn_apply_cached(probs, ids[1], 2)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_cached_score_map_alone_agrees_with_the_reference() {
    // The scores on their own, undivided by a softmax, so a wrong `1 / sqrt(head_dim)` or a
    // transposed head range shows up as a value rather than as a redistribution. Four heads of
    // two over three positions.
    let query = spread(8, 0.2);
    let cache = spread(3 * 8, 1.1);
    agrees(
        "cached scores",
        &[Shape::new(8, 1, 1), Shape::new(3, 1, 8)],
        &[&query, &cache],
        &[],
        |b, ids| b.attn_scores_cached(ids[0], ids[1], 4),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_causal_softmax_agrees_with_the_reference() {
    // `softmax_causal.comp`, the only new shader TinyCLIP needs. The CPU interpreter is the only
    // oracle for the SPIR-V, so this is what says the two compute the same mask on real hardware.
    //
    // Two heads over T 5, which makes both indexing mistakes visible: the query index is
    // `row % out_h`, so a shader taking the flat row would leave head 1 unmasked, and the bound is
    // `query + 1`, so an off-by-one shows up as row 0 being a pair rather than a point.
    let scores = spread(2 * 5 * 5, 0.4);
    agrees(
        "a causal softmax",
        &[Shape::new(2, 5, 5)],
        &[&scores],
        &[],
        |b, ids| b.softmax_causal(ids[0]),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_causal_text_attention_agrees_with_the_reference() {
    // The whole of TinyCLIP's text-tower attention: scores, the causal softmax and the weighted
    // sum, in the shapes the real tower uses in miniature. `attn_apply.comp` multiplies the masked
    // tail as well as the head, so a shader that left the tail stale rather than zeroing it is
    // invisible in the softmax's own output and shows up here.
    let q = spread(8 * 4, 0.1);
    let k = spread(8 * 4, 0.9);
    let v = spread(8 * 4, 1.6);
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let qi = builder.input(Shape::new(8, 1, 4));
    let ki = builder.input(Shape::new(8, 1, 4));
    let vi = builder.input(Shape::new(8, 1, 4));
    let scores = builder.attn_scores(qi, ki, 2);
    let probs = builder.softmax_causal(scores);
    let out = builder.attn_apply(probs, vi, 2);
    let plan = builder.finish(&[out]).expect("the fixture plan builds");
    compare("a causal text attention", plan, given.data().to_vec(), &[&q, &k, &v]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_position_concat_moves_the_same_columns_on_the_device() {
    // TinyCLIP's class token: `c * h` strided `vkCmdCopyBuffer`s rather than the one a channel
    // concat gets away with. What is under test is the destination stride, which is the output's
    // width and not the part's.
    let token = spread(6, 0.5);
    let grid = spread(6 * 4, 1.3);
    let given = Given::new(&[]).expect("no tensors");
    let mut builder = Builder::new(&given);
    let t = builder.input(Shape::new(6, 1, 1));
    let g = builder.input(Shape::new(6, 1, 4));
    let joined = builder.concat_positions(&[t, g]);
    let plan = builder.finish(&[joined]).expect("the fixture plan builds");
    compare("a position concat", plan, given.data().to_vec(), &[&token, &grid]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_reshape_moves_the_same_elements_on_the_device() {
    // The relabelling that lets a `[d_model, 1, 1]` projection become a `[1, 1, d_model]` cache
    // position. One `vkCmdCopyBuffer`, so what is under test is that the shapes either side agree
    // about the element count.
    let x = spread(16, 0.3);
    agrees(
        "reshape",
        &[Shape::new(16, 1, 1)],
        &[&x],
        &[],
        |b, ids| b.reshaped(ids[0], Shape::new(1, 1, 16)),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_decode_plan_built_at_the_maximum_matches_one_built_at_the_length() {
    // The invariant the whole record-once decode design rests on: a plan built for `MAX` cache
    // positions and run with `prefix = KEYS - 1` must produce what a plan built for exactly
    // `KEYS` positions produces. If that holds, the plan stops depending on the step number and
    // `Reshaped` stops re-recording per token.
    //
    // The cache is deliberately `MAX` positions of which only the first `KEYS` are real; the tail
    // is filled with values far outside the live range. Zeros there would let a shader that
    // ignored the bound still pass — `exp` of a zero score is a finite weight against a zero
    // value, which perturbs little. Large values cannot be silently absorbed: if any of the three
    // shaders reads past the bound, the softmax denominator or the weighted sum moves by orders
    // of magnitude and the comparison fails loudly.
    const D_MODEL: u32 = 64;
    const HEADS: u32 = 8;
    const KEYS: u32 = 5;
    const MAX: u32 = 32;

    let query = spread(D_MODEL as usize, 0.3);
    let live: Vec<f32> = spread((KEYS * D_MODEL) as usize, 1.1);
    // The same live prefix, then a tail no correct run may read.
    let mut padded = live.clone();
    padded.extend((0..((MAX - KEYS) * D_MODEL)).map(|i| if i % 2 == 0 { 90.0 } else { -90.0 }));

    // Built at exactly the live length, with the ordinary fixed-length ops.
    let at_length = {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(D_MODEL, 1, 1));
        let cache = builder.input(Shape::new(KEYS, 1, D_MODEL));
        let scores = builder.attn_scores_cached(q, cache, HEADS);
        let probs = builder.softmax(scores);
        let out = builder.attn_apply_cached(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the fixed-length decode plan builds");
        crate::nets::tests::assert_no_aliasing(&plan);
        on_device(plan, source.into_data(), &[&query, &live])
    };

    // Built at the maximum, told the real length at submit time.
    let build_at_maximum = || {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(D_MODEL, 1, 1));
        let cache = builder.input(Shape::new(MAX, 1, D_MODEL));
        let scores = builder.attn_scores_cached_dynamic(q, cache, HEADS);
        let probs = builder.softmax_prefix(scores);
        let out = builder.attn_apply_cached_dynamic(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the maximum-length decode plan builds");
        crate::nets::tests::assert_no_aliasing(&plan);
        (plan, source.into_data())
    };

    // `prefix` is the positions already cached, so this step attends over those plus its own.
    let (plan, data) = build_at_maximum();
    let at_maximum = on_device_at(plan, data, &[&query, &padded], KEYS - 1);
    matches("a decode plan built at its maximum", &at_length, &at_maximum);

    // And the bound is really being read, rather than the two agreeing for some other reason: at
    // a different prefix the same recording must produce something else. Without this the test
    // would still pass if every shader ignored `step_params` and used the full width, provided
    // the padding happened not to matter.
    let (plan, data) = build_at_maximum();
    let at_wrong_prefix = on_device_at(plan, data, &[&query, &padded], KEYS);
    let moved = at_length
        .iter()
        .zip(&at_wrong_prefix)
        .flat_map(|(a, b)| a.iter().zip(b))
        .any(|(a, b)| (a - b).abs() > TOLERANCE);
    assert!(moved, "attending over one more position changed nothing, so the bound is unread");
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_persistent_cache_accumulates_across_submits_of_one_recording() {
    // The end state Phase 2 is for: **one** recording, submitted once per token, each step
    // appending to a KV cache that lives in the arena and is never sent to the host. If this
    // holds, a decode loop stops rebuilding and stops round-tripping the cache.
    //
    // Checked against the thing it replaces: a plan built at exactly `STEPS` keys with the whole
    // cache handed in as an input, which is how the decoder works today.
    const D_MODEL: u32 = 64;
    const HEADS: u32 = 8;
    const STEPS: u32 = 6;
    const MAX: u32 = 32;

    // One row per step, each distinguishable so a step reading the wrong row is visible.
    let rows: Vec<Vec<f32>> =
        (0..STEPS).map(|s| spread(D_MODEL as usize, 0.4 + s as f32)).collect();

    // What the loop should converge on: every row already in a cache, attended at full length.
    let expected = {
        let mut flat: Vec<f32> = Vec::new();
        for row in &rows {
            flat.extend_from_slice(row);
        }
        let last = rows.last().expect("STEPS is not zero").clone();
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(D_MODEL, 1, 1));
        let cache = builder.input(Shape::new(STEPS, 1, D_MODEL));
        let scores = builder.attn_scores_cached(q, cache, HEADS);
        let probs = builder.softmax(scores);
        let out = builder.attn_apply_cached(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the fixed-length plan builds");
        on_device(plan, source.into_data(), &[&last, &flat])
    };

    // The decode loop: built once, at the maximum, with the cache held on the device.
    let source = Invented::new(0);
    let mut builder = Builder::new(&source);
    let row = builder.input(Shape::new(D_MODEL, 1, 1));
    let cache = builder.persistent(Shape::new(MAX, 1, D_MODEL));
    builder.cache_write(row, cache);
    let scores = builder.attn_scores_cached_dynamic(row, cache, HEADS);
    let probs = builder.softmax_prefix(scores);
    let out = builder.attn_apply_cached_dynamic(probs, cache, HEADS);
    let plan = builder.finish(&[out]).expect("the record-once decode plan builds");
    crate::nets::tests::assert_no_aliasing(&plan);

    let weights = Weights::from_data(source.into_data());
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
        .expect("the decode plan records into a command buffer");

    let mut last = Vec::new();
    for (step, row) in rows.iter().enumerate() {
        let prefix = u32::try_from(step).expect("STEPS is small");
        // The only thing that changes between submits. No rebuild, no re-record, and the cache
        // rows written by earlier steps are still in the arena.
        net.set_params(StepParams { prefix, window_start: 0 }).expect("the step params write");
        last = net.infer_raw_many(&[row]).expect("the decode step submits");
    }

    matches("a decode loop over one recording", &expected, &last);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn multi_query_attention_reads_one_cache_head_for_every_query_head() {
    // Gemma 4's text decoder is eight query heads against **one** key/value head, so a cache
    // position is an eighth the width. The mapping is what breaks silently: reading head `h`'s
    // slice out of a cache that only has one produces plausible numbers from the wrong offsets.
    //
    // Checked against an explicitly broadcast cache: replicating the single KV head eight times
    // and running ordinary multi-head attention must give the same answer.
    const HEAD_DIM: u32 = 16;
    const HEADS: u32 = 8;
    const KV_HEADS: u32 = 1;
    const KEYS: u32 = 5;
    let d_model = HEAD_DIM * HEADS;

    let query = spread((HEAD_DIM * HEADS) as usize, 0.3);
    // The narrow cache: `KEYS` positions of `KV_HEADS * HEAD_DIM`.
    let narrow: Vec<f32> = spread((KEYS * KV_HEADS * HEAD_DIM) as usize, 1.1);
    // The same data broadcast to every head, which is what MQA means.
    let mut wide = Vec::new();
    for key in 0..KEYS as usize {
        let row = narrow
            .get(key * (KV_HEADS * HEAD_DIM) as usize..(key + 1) * (KV_HEADS * HEAD_DIM) as usize)
            .expect("the narrow cache is rectangular");
        for _ in 0..HEADS {
            wide.extend_from_slice(row);
        }
    }

    let broadcast = {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(d_model, 1, 1));
        let cache = builder.input(Shape::new(KEYS, 1, d_model));
        let scores = builder.attn_scores_cached(q, cache, HEADS);
        let probs = builder.softmax(scores);
        let out = builder.attn_apply_cached(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the broadcast plan builds");
        on_device(plan, source.into_data(), &[&query, &wide])
    };

    let grouped = {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(d_model, 1, 1));
        let cache = builder.input(Shape::new(KEYS, 1, KV_HEADS * HEAD_DIM));
        let scores = builder.attn_scores_cached_grouped(q, cache, HEADS, KV_HEADS);
        let probs = builder.softmax_prefix(scores);
        let out = builder.attn_apply_cached_grouped(probs, cache, HEADS, KV_HEADS);
        let plan = builder.finish(&[out]).expect("the grouped plan builds");
        crate::nets::tests::assert_no_aliasing(&plan);
        on_device_at(plan, source.into_data(), &[&query, &narrow], KEYS - 1)
    };

    matches("multi-query attention against a broadcast cache", &broadcast, &grouped);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_sliding_window_ignores_everything_before_its_start() {
    // Gemma 4 alternates four sliding layers to one global one, window 512. A sliding layer must
    // not see the cache before `window_start`, and the failure mode is soft: attending to too
    // much is fluent and wrong rather than an error.
    //
    // Checked by construction: a window over `[start, prefix]` of a long cache must equal a full
    // attention over a cache holding only those positions.
    const HEAD_DIM: u32 = 16;
    const HEADS: u32 = 4;
    const KEYS: u32 = 9;
    const START: u32 = 4;
    let d_model = HEAD_DIM * HEADS;

    let query = spread(d_model as usize, 0.55);
    let full: Vec<f32> = spread((KEYS * d_model) as usize, 2.2);
    let inside: Vec<f32> = full
        .get((START * d_model) as usize..)
        .expect("the window is inside the cache")
        .to_vec();

    // Ordinary attention over just the windowed positions.
    let expected = {
        let source = Invented::new(0);
        let mut builder = Builder::new(&source);
        let q = builder.input(Shape::new(d_model, 1, 1));
        let cache = builder.input(Shape::new(KEYS - START, 1, d_model));
        let scores = builder.attn_scores_cached(q, cache, HEADS);
        let probs = builder.softmax(scores);
        let out = builder.attn_apply_cached(probs, cache, HEADS);
        let plan = builder.finish(&[out]).expect("the windowed-only plan builds");
        on_device(plan, source.into_data(), &[&query, &inside])
    };

    // The same window, expressed as a range over the whole cache.
    let source = Invented::new(0);
    let mut builder = Builder::new(&source);
    let q = builder.input(Shape::new(d_model, 1, 1));
    let cache = builder.input(Shape::new(KEYS, 1, d_model));
    let scores = builder.attn_scores_cached_dynamic(q, cache, HEADS);
    let probs = builder.softmax_prefix(scores);
    let out = builder.attn_apply_cached_dynamic(probs, cache, HEADS);
    let plan = builder.finish(&[out]).expect("the windowed plan builds");
    crate::nets::tests::assert_no_aliasing(&plan);

    let weights = Weights::from_data(source.into_data());
    let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
        .expect("the windowed plan records");
    net.set_params(StepParams { prefix: KEYS - 1, window_start: START })
        .expect("the window is written");
    let windowed = net.infer_raw_many(&[&query, &full]).expect("the windowed plan submits");

    matches("a sliding window over a longer cache", &expected, &windowed);

    // And the window is really read: opening it wider must change the answer, or the shaders are
    // ignoring `window_start` and this test proves nothing.
    net.set_params(StepParams { prefix: KEYS - 1, window_start: 0 }).expect("widen the window");
    let wide = net.infer_raw_many(&[&query, &full]).expect("the wide plan submits");
    let moved = expected
        .iter()
        .zip(&wide)
        .flat_map(|(a, b)| a.iter().zip(b))
        .any(|(a, b)| (a - b).abs() > TOLERANCE);
    assert!(moved, "opening the window changed nothing, so window_start is unread");
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_grouped_rms_norm_normalises_each_head_on_its_own() {
    // Gemma 4's QK-norm: a query is `[heads * head_dim, 1, 1]` and each head's slice is
    // normalised independently against one shared `head_dim`-long gamma.
    //
    // The failure this catches is normalising across heads instead of within them, which is what
    // a naive reshape does - the channels are head-major, so a `[head_dim, 1, heads]` view
    // strides the wrong way. Distinct per-head magnitudes make that visible: if the reduction
    // spanned heads, every head would share one scale factor and the ratios between them would
    // collapse.
    const HEAD_DIM: u32 = 8;
    const HEADS: u32 = 4;
    let channels = HEAD_DIM * HEADS;
    // Head `h` is scaled by `h + 1`, so the heads have deliberately different norms.
    let input: Vec<f32> = (0..channels)
        .map(|i| {
            let head = i / HEAD_DIM;
            spread(HEAD_DIM as usize, 0.9)[(i % HEAD_DIM) as usize] * (head as f32 + 1.0)
        })
        .collect();
    let gamma: Vec<f32> = spread(HEAD_DIM as usize, 2.5);

    agrees(
        "a grouped rms norm",
        &[Shape::new(channels, 1, 1)],
        &[&input],
        &[(vec![HEAD_DIM], gamma)],
        |b, ids| b.rms_norm_grouped(ids[0], 0, 1e-6, HEADS),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_softcap_saturates_the_logits_it_is_given() {
    // `final_logit_softcapping = 30`. The values below deliberately straddle the cap, because
    // the op is nearly the identity well inside it - a fixture that stayed in the linear region
    // would pass against no softcap at all.
    let input: Vec<f32> = (0..64)
        .map(|i| (i as f32 - 32.0) * 4.0)
        .collect();
    assert!(input.iter().any(|v| v.abs() > 30.0), "the fixture must exceed the cap");
    agrees_invented("a softcap", 0, &[Shape::new(64, 1, 1)], &[&input], |b, ids| {
        b.softcap(ids[0], 30.0)
    });
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_standalone_activation_matches_a_folded_one() {
    // `Kind::Activate` exists for a gated feed-forward, where half a fused projection must stay
    // linear. Checked against the same activation folded into a convolution: the two paths run
    // different shaders and must agree, or a gated MLP would differ from an ungated one for no
    // reason the shapes could show.
    let input = spread(48, 0.35);
    agrees_invented("a standalone gelu", 0, &[Shape::new(48, 1, 1)], &[&input], |b, ids| {
        b.activate(ids[0], Act::Gelu)
    });
}

#[test]
#[ignore = "needs a Vulkan device"]
fn an_int4_gemv_agrees_with_the_reference() {
    // `conv_vec_int4.comp`. The shape crosses several blocks on purpose: at `I4_BLOCK` 32 a
    // 100-tap contraction is four blocks, the last of them partial, which is where an off-by-one
    // in the block index or the nibble unpack shows up.
    //
    // Values span the whole signed range so a shader that read the nibbles unsigned - a
    // plausible mistake, and a different quantisation rather than a different spelling - cannot
    // pass.
    let out_channels = 20u32;
    let in_channels = 100u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 7) % 16 - 8) as i8)
        .collect();
    assert!(kernel.iter().any(|&v| v < 0) && kernel.iter().any(|&v| v > 0));
    // A distinct scale per block per channel, each an exact power of two so the interpreter and
    // the device read one number rather than two roundings of one.
    let scales: Vec<f32> = (0..out_channels * blocks)
        .map(|i| 0.007_812_5 * (1.0 + (i % 5) as f32))
        .collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 0.7);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::Relu);
    let plan = builder.finish(&[last]).expect("the int4 gemv fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("an int4 gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_int4_convolution_agrees_with_the_reference() {
    // `conv_point_int4.comp`, which folds the scale into the staged tile. That is only correct
    // while a 16-tap tile stays inside one 32-tap block, so the widths here are chosen to make
    // the tiling real: 40 positions is three tiles across, 100 taps is seven staging steps.
    let out_channels = 20u32;
    let in_channels = 100u32;
    let positions = 40u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 11) % 16 - 8) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels * blocks)
        .map(|i| 0.015_625 * (1.0 + (i % 3) as f32))
        .collect();
    let biases: Vec<f32> = spread(out_channels as usize, 0.4);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread((in_channels * positions) as usize, 0.5);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, positions));
    let last = builder.conv_int4(first, 0, out_channels, Act::Gelu);
    let plan = builder.finish(&[last]).expect("the tiled int4 fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt4, .. }
        )),
        "a multi-position int4 convolution must lower to the tiled shader",
    );
    compare("a tiled int4 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn the_gemv_int8_shader_agrees_with_the_reference() {
    // `conv_vec_int8.comp`, which every ungrouped single-position 1x1 int8 convolution lowers to.
    // It is a decode step's whole int8 workload bar the cross-attention keys and values, and it
    // reduces across all 64 lanes through shared memory where the other two int8 shaders give each
    // invocation a whole dot product — so a wrong reduction, a wrong row stride or a missing barrier
    // is invisible in the shape and shows up only here.
    //
    // The shape makes both ragged cases fire at once: 20 output channels is not a multiple of the
    // shader's 8 rows, so the last workgroup accumulates three rows that must be discarded at the
    // store; and 100 input channels is not a multiple of the 64-lane stride, so the inner loop's
    // last pass covers 36 lanes and the other 28 must contribute zero.
    let out_channels = 20u32;
    let in_channels = 100u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 37) % 251 - 125) as i8)
        .collect();
    // Distinct per channel, and each an exact multiple of a power of two so the interpreter and the
    // device read one number rather than two roundings of one.
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.007_812_5 * (1.0 + c as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(in_channels as usize, 0.7);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
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
    let plan = builder.finish(&[last]).expect("the gemv int8 fixture plan builds");
    // The lowering is automatic, so this is also what asserts it happened.
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
        )),
        "a single-position int8 convolution must lower to the gemv shader: {:?}",
        plan.ops,
    );
    compare("a gemv int8 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemv_int8_convolution_past_a_segment_boundary_agrees_with_the_reference() {
    // The same shader as above, but with its three tensors pushed past the first descriptor
    // window, so `Segments::rebase` has to move a **word** offset rather than an element one.
    //
    // Nothing else here reaches that path on a device. Every other fixture is a few kilobytes,
    // so it is one window with a zero base and `rebase` returns the push untouched; the only
    // segmented net is Supertonic, which has no single-position int8 convolution. That gap is
    // why `ConvVecInt8` was missing from the word-unit arm of `rebase` while `weight_reads`
    // classified it correctly — the file offsets were right, the rebased offsets were half of
    // right, and no test looked.
    //
    // Run it both ways. Unsegmented it is an ordinary gemv; under
    // `MODELRUNNER_MAX_STORAGE_RANGE=33554432` the padding below guarantees a non-zero base, and
    // a regression reads the wrong weights and fails the comparison rather than erroring.
    // 44 MiB of fp16. The file is then windowed at an 8 MiB stride and the three tensors below
    // land in the sixth window, 4 MiB *past* its 40 MiB base — deliberately not on the boundary
    // itself, where a word-rebased and an element-rebased offset would both saturate to zero and
    // agree by accident.
    const PAD_ELEMENTS: usize = 23_068_672;
    let out_channels = 20u32;
    let in_channels = 100u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 37) % 251 - 125) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.007_812_5 * (1.0 + c as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            // Never read. It exists only to move what follows into a later window.
            Fixture::F16(vec![PAD_ELEMENTS as u32], vec![0.0; PAD_ELEMENTS]),
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(in_channels as usize, 0.7);

    let mut builder = Builder::new(&weights);
    // The padding is never dispatched over, which `finish` would otherwise reject.
    builder.host_tensor(0, &[PAD_ELEMENTS as u32]);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int8(
        first,
        1,
        out_channels,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::Relu,
    );
    let plan = builder.finish(&[last]).expect("the padded gemv int8 fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
        )),
        "a single-position int8 convolution must lower to the gemv shader",
    );
    // Reported rather than asserted, because the useful configuration is the forced one and a
    // plain run should still check the numbers rather than skip.
    let bytes = weights.data().len() as u64;
    let range = device().limits.max_storage_buffer_range;
    if bytes > range {
        println!("{bytes} bytes over a {range}-byte range: the kernel is in a later window");
    } else {
        println!(
            "{bytes} bytes inside a {range}-byte range: one window, so this run does not \
             exercise rebasing. Set MODELRUNNER_MAX_STORAGE_RANGE=33554432."
        );
    }
    compare("a gemv int8 convolution past a boundary", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn the_gemv_and_tiled_int8_shaders_agree_with_each_other() {
    // The same weights over one position and over 16. `Builder` sends the first to
    // `conv_vec_int8.comp` and the second to `conv_point_int8.comp`, and the wide run's position 0
    // must equal the narrow one — the strongest statement available that adding the lowering
    // changed no numbers, and the one a device can make that the interpreter cannot, since the
    // interpreter serves both from the same `conv_int8`.
    let out_channels = 24u32;
    let in_channels = 64u32;
    let width = 16u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 53) % 241 - 120) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.015_625 * (1.0 + c as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 0.3);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");

    let narrow = spread(in_channels as usize, 0.4);
    // Channel-major, so position 0 of each channel is every `width`th element.
    let mut wide = vec![0.0f32; (in_channels * width) as usize];
    for channel in 0..in_channels as usize {
        wide[channel * width as usize] = narrow[channel];
    }

    let run = |shape: Shape, input: &[f32]| -> Vec<f32> {
        let mut builder = Builder::new(&weights);
        let first = builder.input(shape);
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
        let plan = builder.finish(&[last]).expect("the comparison plan builds");
        let mut out = on_device(plan, weights.data().to_vec(), &[input]);
        out.pop().expect("one output")
    };
    let one = run(Shape::new(in_channels, 1, 1), &narrow);
    let many = run(Shape::new(in_channels, 1, width), &wide);
    for channel in 0..out_channels as usize {
        let gemv = one[channel];
        let tiled = many[channel * width as usize];
        // Both accumulate in fp32 and store fp16, but in a different order, so the last fp16 place
        // can differ — the same tolerance `matches` applies.
        let tolerance = 1e-3 * gemv.abs().max(1.0);
        assert!(
            (gemv - tiled).abs() <= tolerance,
            "channel {channel}: gemv {gemv} against tiled {tiled}",
        );
    }
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
    //
    // Deliberately a `1 x 3` rather than a `1 x 1`: an ungrouped `1 x 1` now lowers to
    // `Kind::ConvPointInt8` instead, so a pointwise fixture here would leave `conv_int8.comp`
    // untested on the device. The spatial kernel also puts its zero padding under test, which is
    // the one thing the tiled path has none of.
    let out_channels = 4u32;
    let in_channels = 3u32;
    let width = 5u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels * 3) as i32)
        .map(|i| ((i * 7) % 61 - 30) as i8)
        .collect();
    let scales: Vec<f32> = vec![0.25, 0.5, 0.0625, 1.0];
    let biases: Vec<f32> = vec![0.5, -0.25, 1.0, 0.0];
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 3], kernel),
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
        (1, 3),
        (1, 1),
        (1, 1),
        (0, 1, 0, 1),
        1,
        Act::Relu,
    );
    let plan = builder.finish(&[last]).expect("the int8 fixture plan builds");
    compare("an int8 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_int8_convolution_agrees_with_the_reference() {
    // `conv_point_int8.comp`, which every ungrouped 1x1 int8 convolution lowers to and which
    // Supertonic's sampler is 92% made of by parameter count.
    //
    // The shape is chosen so nothing about the tiling is exercised only at its happy path: 20
    // output channels and 21 positions are each more than one 16-wide tile and neither divides it,
    // so the four tiles include partial ones in both axes, and 24 input channels make the
    // accumulation loop take two staging steps of which the second is half out of range. A shader
    // that dropped the zero-fill on an out-of-range staging slot, or that mismatched its two
    // barriers, is wrong only on a fixture with all three of those properties.
    let out_channels = 20u32;
    let in_channels = 24u32;
    let width = 21u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 37) % 251 - 125) as i8)
        .collect();
    // Distinct per channel, as in the untiled fixture, and every one an exact multiple of a power
    // of two so the interpreter and the device read one number rather than two roundings of one.
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.007_812_5 * (1.0 + c as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread((in_channels * width) as usize, 0.7);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, width));
    // Gelu because that is what follows these convolutions in the sampler, and because it is the
    // activation whose input range the dequantisation scale decides.
    let last = builder.conv_int8(
        first,
        0,
        out_channels,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::Gelu,
    );
    let plan = builder.finish(&[last]).expect("the tiled int8 fixture plan builds");
    compare("a tiled int8 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn report_the_tiled_int8_convolution_against_the_tiled_fp16_one() {
    // The measurement Phase 2 of the int8 plan is gated on, printed by
    // `cargo test -- --ignored --nocapture`. `conv_point_int8.comp` exists so that quantising
    // Supertonic's sampler does not move 92% of its parameters onto the untiled `conv_int8.comp`,
    // which was measured at 15.2 GFLOP/s against a device peak of order 1000. That is only worth
    // having if the int8 tiled path is close to the fp16 tiled one, because the alternative to
    // quantising is bundling 198 MB of fp16 rather than 105 MB of int8 — a size decision, not a
    // speed one, so a large slowdown here means take the size.
    //
    // Eight chained `512 -> 512` projections over 64 positions: the sampler's shape, deep enough
    // that the shader rather than the submit-and-read-back dominates.
    const CHANNELS: u32 = 512;
    const POSITIONS: u32 = 64;
    const LAYERS: usize = 8;
    const RUNS: usize = 20;

    // The two nets compute the same numbers, not merely the same shapes: the int8 weights are
    // exact small integers and the scale is an exact power of two, so dequantising is lossless
    // here. That is what lets the timings be compared against each other *and* the outputs
    // compared for equality, which is the only thing standing between a fast shader and a shader
    // that quietly wrote nothing.
    //
    // The divisor is what keeps eight chained layers inside fp16. A `512`-wide dot product of
    // weights spread over -3..3 has a gain of about `2 * sqrt(512) / DIVISOR`, so at 16 the chain
    // grows by ~2.8 a layer and reaches infinity by the eighth; at 64 it shrinks, which fp16
    // tolerates far better than it tolerates overflow.
    const DIVISOR: f32 = 64.0;
    let taps = (CHANNELS * CHANNELS) as usize;
    let int8_kernel: Vec<i8> = (0..taps).map(|i| (i % 7) as i8 - 3).collect();
    let fp16_kernel: Vec<f32> = int8_kernel.iter().map(|&w| f32::from(w) / DIVISOR).collect();
    let scales = vec![1.0 / DIVISOR; CHANNELS as usize];
    let biases = vec![0.0f32; CHANNELS as usize];

    let mut fp16_tensors = Vec::new();
    let mut int8_tensors = Vec::new();
    for _ in 0..LAYERS {
        let dims = vec![CHANNELS, CHANNELS, 1, 1];
        fp16_tensors.push(Fixture::F16(dims.clone(), fp16_kernel.clone()));
        fp16_tensors.push(Fixture::F16(vec![CHANNELS], biases.clone()));
        int8_tensors.push(Fixture::I8(dims, int8_kernel.clone()));
        int8_tensors.push(Fixture::F16(vec![CHANNELS], scales.clone()));
        int8_tensors.push(Fixture::F16(vec![CHANNELS], biases.clone()));
    }

    let input = spread((CHANNELS * POSITIONS) as usize, 0.5);
    let mut timings = Vec::new();
    let mut outputs = Vec::new();
    for (what, tensors, per_layer) in
        [("fp16", fp16_tensors, 2usize), ("int8", int8_tensors, 3usize)]
    {
        let blob = write_mixed(graph::SUPERTONIC_VE, &tensors);
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the blob parses");
        let mut builder = Builder::new(&weights);
        let mut x = builder.input(Shape::new(CHANNELS, 1, POSITIONS));
        for layer in 0..LAYERS {
            let at = layer * per_layer;
            x = if per_layer == 2 {
                builder.conv(x, at, CHANNELS, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Gelu)
            } else {
                builder.conv_int8(
                    x,
                    at,
                    CHANNELS,
                    (1, 1),
                    (1, 1),
                    (1, 1),
                    (0, 0, 0, 0),
                    1,
                    Act::Gelu,
                )
            };
        }
        let plan = builder.finish(&[x]).expect("the benchmark plan builds");
        let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
            .expect("the plan records into a command buffer");
        // One discarded run: the first submit pays for pipeline warm-up and for faulting the
        // weights into device memory, neither of which a steady-state utterance pays per step.
        let first = net.infer_raw(&input).expect("the benchmark submits and reads back");
        let start = std::time::Instant::now();
        for _ in 0..RUNS {
            net.infer_raw(&input).expect("the benchmark submits and reads back");
        }
        let each = start.elapsed().as_secs_f64() / RUNS as f64;
        // Two operations per multiply-accumulate, which is how the 15.2 GFLOP/s this shader
        // exists to escape was counted.
        let flops = 2.0 * f64::from(CHANNELS) * f64::from(CHANNELS) * f64::from(POSITIONS)
            * LAYERS as f64;
        println!("{what}: {:.3} ms per pass, {:.1} GFLOP/s", each * 1e3, flops / each / 1e9);
        timings.push(each);
        outputs.push(first);
    }

    match (timings.as_slice(), outputs.as_slice()) {
        ([fp16, int8], [from_fp16, from_int8]) => {
            println!("int8 / fp16 = {:.2}x", int8 / fp16);
            matches("the two benchmark nets", from_fp16, from_int8);
        }
        _ => panic!("two nets were timed"),
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_net_uploaded_from_a_file_agrees_with_one_uploaded_from_memory() {
    // The bundled path end to end: the same plan, the same weights, uploaded once from a `Vec<u8>`
    // and once by streaming a file in chunks. The two must produce identical output, because a
    // wrong `dst_offset` on any chunk but the first gives a net whose early layers are right and
    // whose later ones read whatever the buffer was allocated with — plausible-looking audio, not
    // an error.
    //
    // The blob is deliberately larger than the chunk size the upload uses, so more than one copy is
    // issued. A single-chunk fixture exercises none of the arithmetic that can be wrong.
    const CHANNELS: u32 = 1024;
    const LAYERS: usize = 6;
    let taps = (CHANNELS * CHANNELS) as usize;
    let kernel: Vec<f32> = (0..taps).map(|i| ((i % 11) as f32 - 5.0) / 1024.0).collect();
    let bias = vec![0.0f32; CHANNELS as usize];
    let mut tensors = Vec::new();
    for _ in 0..LAYERS {
        tensors.push(Fixture::F16(vec![CHANNELS, CHANNELS, 1, 1], kernel.clone()));
        tensors.push(Fixture::F16(vec![CHANNELS], bias.clone()));
    }
    let blob = write_mixed(graph::SUPERTONIC_VE, &tensors);
    assert!(
        blob.len() as u64 > Net::CHUNK_BYTES,
        "{} bytes fits in one {}-byte chunk, so nothing is being tested",
        blob.len(),
        Net::CHUNK_BYTES,
    );

    let input = spread((CHANNELS * 8) as usize, 0.25);
    let plan_for = |source: &dyn WeightSource| -> Plan {
        let mut builder = Builder::new(source);
        let mut x = builder.input(Shape::new(CHANNELS, 1, 8));
        for layer in 0..LAYERS {
            x = builder.conv_same(x, layer * 2, CHANNELS, 1, 1, Act::Relu);
        }
        builder.finish(&[x]).expect("the upload fixture plan builds")
    };

    let parsed = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let mut from_memory = Net::new(device(), plan_for(&parsed), &parsed, RESCALE_ONLY)
        .expect("the in-memory plan records");
    let want = from_memory.infer_raw(&input).expect("the in-memory net runs");

    // A leading pad, so the file's data section does not start where the file does — which is what
    // an asset inside an APK looks like, and the one thing an offset-free reader gets wrong.
    let path = std::env::temp_dir().join("modelrunner-upload-parity.maml");
    let mut staged = vec![0x5Au8; 8192];
    staged.extend_from_slice(&blob);
    std::fs::write(&path, &staged).expect("the fixture file writes");
    let file = std::fs::File::open(&path).expect("the fixture file reopens");
    let streamed = Streamed::open(file, 8192, blob.len() as u64, graph::SUPERTONIC_VE)
        .expect("the fixture file streams");
    let mut from_file = Net::new(device(), plan_for(&parsed), &streamed, RESCALE_ONLY)
        .expect("the streamed plan records");
    let got = from_file.infer_raw(&input).expect("the streamed net runs");

    matches("a net uploaded from a file", &want, &got);
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

/// The shipped Supertonic text encoder and vocoder, on the device against the interpreter.
///
/// The fixtures above are single ops over a handful of channels, and `onnx_parity.py` compares the
/// *interpreter* against onnxruntime. Neither says anything about a real net's depth on a real
/// device: an arena slot reused while a dispatch still reads it, or a barrier missing between two
/// of ninety convolutions, shows up only at that scale and only on hardware.
///
/// Which is what let a broken vocoder ship. Both marshalling steps were missing from `bridge.rs`
/// for a net whose interpreter parity was 0.999.
///
/// The widths are the ones a real utterance produced - 47 characters and 59 latent frames for
/// "Hello, this voice runs entirely on this device." - rather than the round 64 the scripts default
/// to, because a tail that is not a multiple of the workgroup is where indexing goes wrong.
#[test]
#[ignore = "needs a Vulkan device and the shipped supertonic assets"]
fn the_shipped_supertonic_nets_agree_with_the_reference_on_the_device() {
    let Some(dir) = assets() else {
        return;
    };
    let read = |name: &str| std::fs::read(dir.join(name));

    if let Ok(bytes) = read("supertonic_ttl.maml") {
        let weights = Weights::parse(&bytes, graph::SUPERTONIC_TTL).expect("the text encoder parses");
        let chars = 47u32;
        let plan = crate::nets::supertonic_text::build(&weights, chars).expect("the encoder builds");
        // Two lanes of ids and the voice's style, which is what the net's two inputs are.
        let ids: Vec<u32> = (0..chars).map(|i| (i * 173 + 11) % 8322).collect();
        let lanes = embed_lanes(&ids);
        let style = spread(256 * 50, 0.7);
        let host = run_multi(&plan, weights.data(), &[&lanes, &style])
            .expect("the interpreter runs the text encoder");
        let got = on_device(plan, weights.data().to_vec(), &[&lanes, &style]);
        report("the shipped text encoder at 47 characters", &host, &got);
    }

    if let Ok(bytes) = read("supertonic_voc.maml") {
        let weights = Weights::parse(&bytes, graph::SUPERTONIC_VOC).expect("the vocoder parses");
        let frames = 59u32;
        let plan = crate::nets::supertonic_vocoder::build(&weights, frames).expect("it builds");
        // The plan's own input, so this checks the net rather than `unpack_latent`, which is a host
        // permutation with fixtures of its own.
        let latent = spread(24 * 6 * frames as usize, 1.3);
        let host =
            run_multi(&plan, weights.data(), &[&latent]).expect("the interpreter runs the vocoder");
        let got = on_device(plan, weights.data().to_vec(), &[&latent]);
        report("the shipped vocoder at 59 frames", &host, &got);
    }
}

/// Print how far the device is from the interpreter, rather than asserting a threshold.
///
/// [`TOLERANCE`] is calibrated on fixtures a handful of ops deep. These nets are a hundred, so a
/// max element that exceeds it says nothing on its own — the question is whether the error is a few
/// fp16 ulps spread everywhere, which is drift, or a structure, which is a bug. Correlation and the
/// mean separate the two where a max cannot.
fn report(what: &str, host: &[Vec<f32>], got: &[Vec<f32>]) {
    assert_eq!(got.len(), host.len(), "{what}: output count");
    for (index, (host, got)) in host.iter().zip(got).enumerate() {
        assert_eq!(got.len(), host.len(), "{what}: output {index} length");
        let scale = host.iter().fold(0.0f32, |top, v| top.max(v.abs())).max(1e-3);
        let mut worst = 0.0f32;
        let mut total = 0.0f64;
        let mut over = 0usize;
        for (a, b) in host.iter().zip(got) {
            let error = (a - b).abs();
            worst = worst.max(error);
            total += f64::from(error);
            if error / scale > TOLERANCE {
                over += 1;
            }
        }
        let count = host.len();
        let mean = |v: &[f32]| v.iter().map(|&x| f64::from(x)).sum::<f64>() / count as f64;
        let (hm, gm) = (mean(host), mean(got));
        let mut cov = 0.0f64;
        let (mut hv, mut gv) = (0.0f64, 0.0f64);
        for (a, b) in host.iter().zip(got) {
            let (da, db) = (f64::from(*a) - hm, f64::from(*b) - gm);
            cov += da * db;
            hv += da * da;
            gv += db * db;
        }
        let corr = cov / (hv.sqrt() * gv.sqrt()).max(f64::MIN_POSITIVE);
        println!(
            "{what}: output {index} over {count} values, scale {scale:.4}\n  \
             max {worst:.6}  mean {:.7}  corr {corr:.8}  over tolerance {over} ({:.3}%)",
            total / count as f64,
            100.0 * over as f64 / count as f64,
        );
    }
}

/// Where the shipped `.maml` live: `MODELRUNNER_ASSETS` if set, else the checkout beside this crate.
///
/// The environment variable is what lets these run on a phone, where there is no checkout to walk
/// up to — push `speech/src/main/assets/supertonic` to `/data/local/tmp` and point this at it.
fn assets() -> Option<std::path::PathBuf> {
    if let Ok(dir) = std::env::var("MODELRUNNER_ASSETS") {
        let dir = std::path::PathBuf::from(dir);
        return dir.is_dir().then_some(dir);
    }
    let mut dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    while !dir.join("settings.gradle.kts").is_file() {
        dir = dir.parent()?.to_path_buf();
    }
    Some(dir.join("speech/src/main/assets/supertonic"))
}
