//! A CPU implementation of every op, and an interpreter that runs a [`Plan`] on the host.
//!
//! # Why this exists
//!
//! Until now nothing checked the *numbers* this runtime produces. Both shipping nets
//! emit a segmentation mask, and a mask is confirmed by looking at it — so a
//! transposed kernel, a half-pixel shift or a misread group count would show up as a
//! slightly worse matte on one driver and nothing at all in CI.
//!
//! That was defensible for two nets whose output is an image. It stops being
//! defensible for the models this runtime is growing into: a face embedding that is
//! quietly wrong corrupts clustering with no visible symptom, and a CTC decode that is
//! one logit out returns confident nonsense. So every op gets a reference here, and
//! every op's semantics get pinned by a fixture that was computed by hand rather than
//! by running this code.
//!
//! # What it is a reference *for*
//!
//! Not for the shaders — they cannot run on the host, so this cannot diff against
//! them. It is a reference for the **plan**: [`super::Builder`] resolves shapes, group
//! splits, pads and arena offsets, and running the resolved [`Push`] blocks through an
//! independent implementation is what shows that arithmetic is right end to end.
//!
//! The op bodies below are written from ONNX's definitions. Where they agree with the
//! corresponding `.comp` file that is the result being asserted, not a shortcut: the
//! shader is the thing under test, and a fixture that was derived from it would test
//! nothing.
//!
//! # fp16 storage, fp32 arithmetic — reproduced exactly
//!
//! The shaders keep activations and weights in fp16 and accumulate in fp32.
//! [`Reference`] holds its arena as `f32`, but **every store is round-tripped through
//! fp16 first**, so each value is exactly the one the device would hold. Accumulation
//! order matches the shaders' loop nesting too, which is what makes a bit-for-bit
//! comparison against a device run meaningful rather than approximate.
//!
//! # Test-only
//!
//! `#[cfg(test)]`, so none of it reaches the shipped `.so`.

use std::cell::RefCell;

use super::{Id, Kind, Op, Plan, Push, Shape, WeightSource};
use crate::preprocess::{f16_to_f32, f32_to_f16};

/// The `act` codes from `common.glsl`, which [`super::Act::code`] produces.
mod act {
    /// Store the accumulator unchanged.
    pub const NONE: u32 = 0;
    /// `max(x, 0)`.
    pub const RELU: u32 = 1;
    /// ONNX `HardSwish` at its default alpha and beta.
    pub const HARDSWISH: u32 = 2;
    /// The logistic function.
    pub const SIGMOID: u32 = 3;
    /// `x < 0 ? slope[c] * x : x`.
    pub const PRELU: u32 = 4;
    /// `clamp(x, 0, 1)`, a normalised `HardSigmoid`.
    pub const CLIP01: u32 = 5;
    /// `x * sigmoid(x)`.
    pub const SWISH: u32 = 6;
}

/// The fused activation, mirroring `activate` in `common.glsl`.
///
/// `slope` is the PReLU coefficient for this output channel, already fetched; the
/// others ignore it.
fn activate(x: f32, kind: u32, slope: f32) -> f32 {
    match kind {
        act::RELU => x.max(0.0),
        act::HARDSWISH => x * (x * (1.0 / 6.0) + 0.5).clamp(0.0, 1.0),
        act::SIGMOID => 1.0 / (1.0 + (-x).exp()),
        act::PRELU => {
            if x < 0.0 {
                x * slope
            } else {
                x
            }
        }
        act::CLIP01 => x.clamp(0.0, 1.0),
        act::SWISH => x / (1.0 + (-x).exp()),
        _ => x,
    }
}

/// Round `value` to fp16 and back, which is what storing it in the arena does.
fn through_f16(value: f32) -> f32 {
    f16_to_f32(f32_to_f16(value))
}

/// Run `plan` on the host and return its outputs, in [`Plan::outputs`] order.
///
/// `weights` is the `.vkml` data section verbatim — the same bytes the device gets —
/// so this accepts [`crate::weights::Weights::data`] and a shipped asset directly.
/// `inputs` is one fp32 slice per [`Plan::inputs`] entry; each is rounded to fp16 on
/// the way in, exactly as an upload would.
pub fn run_multi(
    plan: &Plan,
    weights: &[u8],
    inputs: &[&[f32]],
) -> Result<Vec<Vec<f32>>, String> {
    let mut reference = Reference::new(plan, weights, inputs)?;
    reference.execute(plan)?;
    plan.outputs
        .iter()
        .map(|out| reference.read(out.at, out.shape.len()))
        .collect()
}

/// [`run_multi`] for the single-input, single-output nets.
pub fn run(plan: &Plan, weights: &[u8], input: &[f32]) -> Result<Vec<f32>, String> {
    let outputs = run_multi(plan, weights, &[input])?;
    match <[Vec<f32>; 1]>::try_from(outputs) {
        Ok([only]) => Ok(only),
        Err(other) => Err(format!("this net has {} outputs, not one", other.len())),
    }
}

/// A host execution of a [`Plan`]: the activation arena, and the weights it reads.
pub struct Reference {
    /// One entry per fp16 element of the arena. Always an exactly-representable fp16
    /// value; see the note on precision in the module docs.
    arena: Vec<f32>,
    /// The weights blob, decoded once. Decoding per multiply-accumulate would be
    /// exact too, and slow enough to make a whole-net run untestable.
    weights: Vec<f32>,
}

impl Reference {
    fn new(plan: &Plan, weights: &[u8], inputs: &[&[f32]]) -> Result<Reference, String> {
        if inputs.len() != plan.inputs.len() {
            return Err(format!(
                "{} input slices for a net with {} inputs",
                inputs.len(),
                plan.inputs.len()
            ));
        }
        if !weights.len().is_multiple_of(2) {
            return Err(format!("{} bytes is not a whole number of fp16", weights.len()));
        }
        let decoded = weights
            .chunks_exact(2)
            .map(|pair| match pair {
                [low, high] => Ok(f16_to_f32(u16::from_le_bytes([*low, *high]))),
                _ => Err("a chunk of two is two bytes".to_string()),
            })
            .collect::<Result<Vec<f32>, String>>()?;

        let mut reference =
            Reference { arena: vec![0.0; plan.arena_elems as usize], weights: decoded };
        for (binding, values) in plan.inputs.iter().zip(inputs) {
            if values.len() != binding.shape.len() as usize {
                return Err(format!(
                    "{} input values for {:?}",
                    values.len(),
                    binding.shape
                ));
            }
            for (i, &value) in values.iter().enumerate() {
                reference.store(binding.at, i as u32, value)?;
            }
        }
        Ok(reference)
    }

    /// Run every op in order.
    fn execute(&mut self, plan: &Plan) -> Result<(), String> {
        for (step, op) in plan.ops.iter().enumerate() {
            let result = match op {
                Op::Copy { src, dst, elems } => self.copy(*src, *dst, *elems),
                Op::Dispatch { kind, push, .. } => match kind {
                    Kind::Conv => self.conv(push),
                    Kind::ConvTranspose => self.conv_transpose(push),
                    Kind::MaxPool => self.max_pool(push),
                    Kind::AvgPool => self.avg_pool(push),
                    Kind::Resize => self.resize(push),
                    Kind::ResizeNearest => self.resize_nearest(push),
                    Kind::GlobalAvgPool => self.global_avg_pool(push),
                    Kind::Add => self.add(push),
                    Kind::MulBroadcast => self.mul_broadcast(push),
                    Kind::Affine => self.affine(push),
                    Kind::LayerNorm => self.layer_norm(push),
                    Kind::AttnScores => self.attn_scores(push),
                    Kind::Softmax => self.softmax(push),
                    Kind::AttnApply => self.attn_apply(push),
                },
            };
            result.map_err(|e| format!("step {step} ({op:?}): {e}"))?;
        }
        Ok(())
    }

    /// `elems` values starting at `at`, as fp32.
    fn read(&self, at: u32, elems: u32) -> Result<Vec<f32>, String> {
        (0..elems).map(|i| self.load(at, i)).collect()
    }

    fn load(&self, at: u32, index: u32) -> Result<f32, String> {
        let position = at.checked_add(index).ok_or("an arena offset overflowed")?;
        self.arena
            .get(position as usize)
            .copied()
            .ok_or_else(|| format!("arena read at {position} of {}", self.arena.len()))
    }

    fn store(&mut self, at: u32, index: u32, value: f32) -> Result<(), String> {
        let position = at.checked_add(index).ok_or("an arena offset overflowed")?;
        let len = self.arena.len();
        let slot = self
            .arena
            .get_mut(position as usize)
            .ok_or_else(|| format!("arena write at {position} of {len}"))?;
        *slot = through_f16(value);
        Ok(())
    }

    fn weight(&self, at: u32, index: u32) -> Result<f32, String> {
        let position = at.checked_add(index).ok_or("a weight offset overflowed")?;
        self.weights
            .get(position as usize)
            .copied()
            .ok_or_else(|| format!("weight read at {position} of {}", self.weights.len()))
    }

    /// The contiguous copy `Concat` lowers to.
    fn copy(&mut self, src: u32, dst: u32, elems: u32) -> Result<(), String> {
        // Read the whole run before writing any of it. Source and destination are
        // disjoint in every plan the builder produces, but reading into a buffer keeps
        // that an assertion of the builder's rather than an assumption here.
        let values = self.read(src, elems)?;
        for (i, value) in values.into_iter().enumerate() {
            self.store(dst, i as u32, value)?;
        }
        Ok(())
    }

    /// Convolution: ONNX semantics, weights `[m, in_c / group, kh, kw]`.
    ///
    /// Output channel `oc` belongs to group `oc / (out_c / group)` and reads only that
    /// group's `in_c / group` input channels — the indexing that a depthwise layer
    /// makes or breaks, since there `group == in_c == out_c` and every output channel
    /// must see exactly one input channel.
    fn conv(&mut self, p: &Push) -> Result<(), String> {
        let (in_per_group, out_per_group) = groups(p)?;
        for oc in 0..p.out_c {
            let first_in = (oc / out_per_group) * in_per_group;
            let kernel_at = p.weight + oc * in_per_group * p.kh * p.kw;
            let slope = self.slope(p, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let mut acc = self.weight(p.bias, oc)?;
                    for ic in 0..in_per_group {
                        let plane = (first_in + ic) * p.in_h * p.in_w;
                        for ky in 0..p.kh {
                            let Some(iy) = tap(oy * p.stride_h + ky * p.dil_h, p.pad_t, p.in_h)
                            else {
                                continue;
                            };
                            let row = plane + iy * p.in_w;
                            let tap_at = kernel_at + (ic * p.kh + ky) * p.kw;
                            for kx in 0..p.kw {
                                let Some(ix) =
                                    tap(ox * p.stride_w + kx * p.dil_w, p.pad_l, p.in_w)
                                else {
                                    continue;
                                };
                                acc += self.load(p.in0, row + ix)?
                                    * self.weight(tap_at, kx)?;
                            }
                        }
                    }
                    self.store(p.out, nchw(p, oc, oy, ox), activate(acc, p.act, slope))?;
                }
            }
        }
        Ok(())
    }

    /// The PReLU slope for output channel `c`, or zero when the activation is not PReLU.
    fn slope(&self, p: &Push, c: u32) -> Result<f32, String> {
        if p.act == act::PRELU {
            self.weight(p.act_weight, c)
        } else {
            Ok(0.0)
        }
    }

    /// Transposed convolution: ONNX semantics, weights `[in_c, out_c / group, kh, kw]`.
    ///
    /// Note the layout: input channels are outermost here and outermost-but-one in
    /// [`Reference::conv`]. That inversion is the one real difference between the two,
    /// and reading it the wrong way round still produces an output of the right shape.
    ///
    /// Written as a gather, like `conv_transpose.comp`: output row `oy` is fed by
    /// input row `(oy + pad_t - ky) / stride_h` whenever that division is exact.
    /// `group` is 1 at the only call site and is not modelled.
    fn conv_transpose(&mut self, p: &Push) -> Result<(), String> {
        if p.group != 1 {
            return Err(format!("a transposed convolution in {} groups", p.group));
        }
        for oc in 0..p.out_c {
            let slope = self.slope(p, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let mut acc = self.weight(p.bias, oc)?;
                    for ic in 0..p.in_c {
                        let plane = ic * p.in_h * p.in_w;
                        let kernel_at = p.weight + (ic * p.out_c + oc) * p.kh * p.kw;
                        for ky in 0..p.kh {
                            let Some(iy) = source(oy + p.pad_t, ky, p.stride_h, p.in_h) else {
                                continue;
                            };
                            let row = plane + iy * p.in_w;
                            let tap_at = kernel_at + ky * p.kw;
                            for kx in 0..p.kw {
                                let Some(ix) = source(ox + p.pad_l, kx, p.stride_w, p.in_w)
                                else {
                                    continue;
                                };
                                acc += self.load(p.in0, row + ix)?
                                    * self.weight(tap_at, kx)?;
                            }
                        }
                    }
                    self.store(p.out, nchw(p, oc, oy, ox), activate(acc, p.act, slope))?;
                }
            }
        }
        Ok(())
    }

    /// Max pooling, floored and unpadded — which is what all 33 uses are.
    fn max_pool(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    // The most negative fp16 rather than -inf, as `maxpool.comp` uses:
                    // every window here is fully inside the input, so it is never the
                    // answer.
                    let mut best = -65504.0f32;
                    for ky in 0..p.kh {
                        let iy = oy * p.stride_h + ky;
                        if iy >= p.in_h {
                            continue;
                        }
                        for kx in 0..p.kw {
                            let ix = ox * p.stride_w + kx;
                            if ix >= p.in_w {
                                continue;
                            }
                            best = best.max(self.load(p.in0, plane + iy * p.in_w + ix)?);
                        }
                    }
                    self.store(p.out, nchw(p, oc, oy, ox), best)?;
                }
            }
        }
        Ok(())
    }

    /// Average pooling over an explicit window, floored and unpadded.
    ///
    /// The divisor is the window size rather than the number of elements actually read,
    /// which is only correct because `Builder::avg_pool` refuses a window that overhangs.
    fn avg_pool(&mut self, p: &Push) -> Result<(), String> {
        let window = p.kh * p.kw;
        if window == 0 {
            return Err("an average pool over an empty window".into());
        }
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let mut total = 0.0;
                    for ky in 0..p.kh {
                        let iy = oy * p.stride_h + ky;
                        if iy >= p.in_h {
                            continue;
                        }
                        for kx in 0..p.kw {
                            let ix = ox * p.stride_w + kx;
                            if ix >= p.in_w {
                                continue;
                            }
                            total += self.load(p.in0, plane + iy * p.in_w + ix)?;
                        }
                    }
                    self.store(p.out, nchw(p, oc, oy, ox), total / window as f32)?;
                }
            }
        }
        Ok(())
    }

    /// Bilinear resize under the `half_pixel` convention, `src = (dst + 0.5) * in / out - 0.5`.
    ///
    /// The same formula [`crate::preprocess`] uses for the input resize. That
    /// agreement is load-bearing: U^2-Netp's decoder upsamples and then adds an
    /// encoder skip five times, so a half-pixel disagreement between the two paths
    /// would misregister every one of those residuals.
    fn resize(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                let (y0, y1, ty) = bracket(oy, p.in_h, p.out_h)?;
                for ox in 0..p.out_w {
                    let (x0, x1, tx) = bracket(ox, p.in_w, p.out_w)?;
                    let row0 = plane + y0 * p.in_w;
                    let row1 = plane + y1 * p.in_w;
                    let top = lerp(self.load(p.in0, row0 + x0)?, self.load(p.in0, row0 + x1)?, tx);
                    let bottom =
                        lerp(self.load(p.in0, row1 + x0)?, self.load(p.in0, row1 + x1)?, tx);
                    self.store(p.out, nchw(p, oc, oy, ox), lerp(top, bottom, ty))?;
                }
            }
        }
        Ok(())
    }

    /// Nearest-neighbour resize, ONNX `asymmetric` coordinates with `floor` rounding:
    /// `src = floor(dst * in / out)`, which in integers is an exact division.
    ///
    /// SCRFD's feature pyramid, and deliberately a different function from
    /// [`Reference::resize`] rather than a mode flag — the two agree nowhere.
    fn resize_nearest(&mut self, p: &Push) -> Result<(), String> {
        if p.out_h == 0 || p.out_w == 0 || p.in_h == 0 || p.in_w == 0 {
            return Err("a resize of a zero extent".into());
        }
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                let iy = (oy * p.in_h / p.out_h).min(p.in_h - 1);
                for ox in 0..p.out_w {
                    let ix = (ox * p.in_w / p.out_w).min(p.in_w - 1);
                    let value = self.load(p.in0, plane + iy * p.in_w + ix)?;
                    self.store(p.out, nchw(p, oc, oy, ox), value)?;
                }
            }
        }
        Ok(())
    }

    /// Mean over H and W, to `C x 1 x 1`. ONNX `ReduceMean` over axes `[2, 3]`.
    fn global_avg_pool(&mut self, p: &Push) -> Result<(), String> {
        let elements = p.in_h * p.in_w;
        if elements == 0 {
            return Err("a global average pool over nothing".into());
        }
        for channel in 0..p.out_c {
            let plane = channel * elements;
            let mut total = 0.0;
            for i in 0..elements {
                total += self.load(p.in0, plane + i)?;
            }
            self.store(p.out, channel, total / elements as f32)?;
        }
        Ok(())
    }

    /// `out = in * scale + shift`, both scalars carried as raw bits in the push block.
    fn affine(&mut self, p: &Push) -> Result<(), String> {
        let scale = f32::from_bits(p.param0_bits);
        let shift = f32::from_bits(p.param1_bits);
        for i in 0..p.count {
            let value = self.load(p.in0, i)?;
            self.store(p.out, i, value * scale + shift)?;
        }
        Ok(())
    }

    /// Layer normalisation over the channel axis, per spatial position.
    ///
    /// Biased variance, dividing by `C` rather than `C - 1`, which is what every
    /// framework does at inference. `count` is the spatial extent rather than the element
    /// count, because one invocation handles a whole column of channels.
    fn layer_norm(&mut self, p: &Push) -> Result<(), String> {
        if p.in_c == 0 {
            return Err("a layer norm over zero channels".into());
        }
        let stride = p.in_h * p.in_w;
        let epsilon = f32::from_bits(p.param1_bits);
        for position in 0..p.count {
            let mut total = 0.0;
            for c in 0..p.in_c {
                total += self.load(p.in0, c * stride + position)?;
            }
            let mean = total / p.in_c as f32;

            let mut variance = 0.0;
            for c in 0..p.in_c {
                let centred = self.load(p.in0, c * stride + position)? - mean;
                variance += centred * centred;
            }
            let inverse = 1.0 / (variance / p.in_c as f32 + epsilon).sqrt();

            for c in 0..p.in_c {
                let at = c * stride + position;
                let centred = self.load(p.in0, at)? - mean;
                let gamma = self.weight(p.weight, c)?;
                let beta = self.weight(p.bias, c)?;
                self.store(p.out, at, centred * inverse * gamma + beta)?;
            }
        }
        Ok(())
    }

    /// Elementwise sum of two equal shapes.
    fn add(&mut self, p: &Push) -> Result<(), String> {
        for i in 0..p.count {
            let sum = self.load(p.in0, i)? + self.load(p.in1, i)?;
            self.store(p.out, i, sum)?;
        }
        Ok(())
    }

    /// `S[h][i][j] = scale * sum_d Q[h][d][i] * K[h][d][j]`.
    ///
    /// Q and K are `[d_model, 1, T]`; the output is `[heads, T, T]`. A head is a run of
    /// `in_c / group` consecutive channels, which is what makes the split free — there is
    /// no transpose anywhere in this, by construction.
    fn attn_scores(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        let stride = p.in_h * p.in_w;
        let scale = f32::from_bits(p.param0_bits);
        for head in 0..p.group {
            let base = head * head_dim * stride;
            for query in 0..p.out_h {
                for key in 0..p.out_w {
                    let mut total = 0.0;
                    for d in 0..head_dim {
                        let channel = base + d * stride;
                        total += self.load(p.in0, channel + query)?
                            * self.load(p.in1, channel + key)?;
                    }
                    self.store(p.out, nchw(p, head, query, key), total * scale)?;
                }
            }
        }
        Ok(())
    }

    /// Softmax over the last axis, `count` contiguous rows of `out_w`.
    ///
    /// The row maximum is subtracted first, as `softmax.comp` does. That is not a
    /// refinement: without it a row containing a large score exponentiates to infinity in
    /// fp32 and every probability in it becomes a NaN.
    fn softmax(&mut self, p: &Push) -> Result<(), String> {
        if p.out_w == 0 {
            return Err("a softmax over an empty axis".into());
        }
        for row in 0..p.count {
            let at = row * p.out_w;
            let mut peak = -65504.0f32;
            for i in 0..p.out_w {
                peak = peak.max(self.load(p.in0, at + i)?);
            }
            let mut total = 0.0;
            for i in 0..p.out_w {
                total += (self.load(p.in0, at + i)? - peak).exp();
            }
            // The peak's own term is `exp(0)`, so this is at least 1.
            let inverse = 1.0 / total;
            for i in 0..p.out_w {
                let value = (self.load(p.in0, at + i)? - peak).exp() * inverse;
                self.store(p.out, at + i, value)?;
            }
        }
        Ok(())
    }

    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`.
    ///
    /// `S` is `[heads, T, T]` at `in0`; `V` and the output are `[d_model, 1, T]`. The
    /// head index picks a plane of `S` and nothing else — channel `c` of `V` is channel
    /// `c` of the output — which is why concatenating the heads afterwards is not an op.
    fn attn_apply(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        let keys = p.out_w;
        for channel in 0..p.out_c {
            let row = (channel / head_dim) * keys * keys;
            for query in 0..keys {
                let mut total = 0.0;
                for key in 0..keys {
                    total += self.load(p.in0, row + query * keys + key)?
                        * self.load(p.in1, channel * keys + key)?;
                }
                self.store(p.out, nchw(p, channel, 0, query), total)?;
            }
        }
        Ok(())
    }

    /// `out[c][y][x] = a[c][y][x] * b[c]`, the excite half of a squeeze-excite block.
    fn mul_broadcast(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let gate = self.load(p.in1, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let index = nchw(p, oc, oy, ox);
                    let value = self.load(p.in0, index)?;
                    self.store(p.out, index, value * gate)?;
                }
            }
        }
        Ok(())
    }
}

/// `channels / group`, the head dimension, refusing the split the builder should have
/// caught.
fn heads(p: &Push, channels: u32) -> Result<u32, String> {
    if p.group == 0 || !channels.is_multiple_of(p.group) {
        return Err(format!("{channels} channels do not split into {} heads", p.group));
    }
    let head_dim = channels / p.group;
    if head_dim == 0 {
        return Err(format!("{} heads for {channels} channels", p.group));
    }
    Ok(head_dim)
}

/// `(in_c / group, out_c / group)`, refusing the divisions the builder should have
/// caught.
fn groups(p: &Push) -> Result<(u32, u32), String> {
    if p.group == 0 {
        return Err("a convolution in zero groups".into());
    }
    if !p.in_c.is_multiple_of(p.group) || !p.out_c.is_multiple_of(p.group) {
        return Err(format!(
            "{} in / {} out channels do not split into {} groups",
            p.in_c, p.out_c, p.group
        ));
    }
    let out_per_group = p.out_c / p.group;
    if out_per_group == 0 {
        return Err(format!("{} groups for {} output channels", p.group, p.out_c));
    }
    Ok((p.in_c / p.group, out_per_group))
}

/// The input coordinate a kernel tap reads, or `None` when it falls in the padding.
fn tap(positioned: u32, pad: u32, extent: u32) -> Option<u32> {
    let coordinate = positioned.checked_sub(pad)?;
    (coordinate < extent).then_some(coordinate)
}

/// The input coordinate feeding a transposed convolution's output, or `None` when this
/// tap does not land on one: `(padded - k)` must be non-negative and a whole number of
/// strides.
fn source(padded: u32, k: u32, stride: u32, extent: u32) -> Option<u32> {
    let numerator = padded.checked_sub(k)?;
    if stride == 0 || !numerator.is_multiple_of(stride) {
        return None;
    }
    let coordinate = numerator / stride;
    (coordinate < extent).then_some(coordinate)
}

/// The two source indices bracketing `dst` and the weight of the second, at
/// `half_pixel`. Coordinates outside the source clamp to the edge.
fn bracket(dst: u32, in_extent: u32, out_extent: u32) -> Result<(u32, u32, f32), String> {
    if in_extent == 0 || out_extent == 0 {
        return Err("a resize of a zero extent".into());
    }
    let source =
        ((dst as f32 + 0.5) * in_extent as f32 / out_extent as f32 - 0.5).max(0.0);
    let base = source.floor();
    let low = (base as u32).min(in_extent - 1);
    Ok((low, (low + 1).min(in_extent - 1), source - base))
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// A flat output index, in the NCHW order every shader's `unpack` assumes.
fn nchw(p: &Push, c: u32, y: u32, x: u32) -> u32 {
    (c * p.out_h + y) * p.out_w + x
}

/// Lay tensors out the way `.vkml` does: 16-byte aligned, fp16, in index order.
fn pack(tensors: &[(Vec<u32>, Vec<f32>)]) -> Result<(Vec<u32>, Vec<u8>), String> {
    let mut offsets = Vec::with_capacity(tensors.len());
    let mut data: Vec<u8> = Vec::new();
    for (dims, values) in tensors {
        while !data.len().is_multiple_of(16) {
            data.push(0);
        }
        let declared: u64 = dims.iter().map(|&d| d as u64).product();
        if declared != values.len() as u64 {
            return Err(format!("{dims:?} declares {declared} values, got {}", values.len()));
        }
        offsets.push((data.len() / 2) as u32);
        for &value in values {
            data.extend_from_slice(&f32_to_f16(value).to_le_bytes());
        }
    }
    Ok((offsets, data))
}

/// A [`WeightSource`] over tensors given explicitly, for the per-op fixtures.
///
/// Unlike [`super::tests::Shapes`], which hands back the tensor index as a stand-in
/// offset, this lays the tensors out for real — 16-byte aligned fp16 in index order,
/// exactly as `vkml_convert.py` writes them — so a plan built against it can actually
/// be run.
pub struct Given {
    offsets: Vec<u32>,
    lengths: Vec<u64>,
    data: Vec<u8>,
}

impl Given {
    /// Lay out `(dims, values)` pairs in `.vkml` index order.
    pub fn new(tensors: &[(Vec<u32>, Vec<f32>)]) -> Result<Given, String> {
        let (offsets, data) = pack(tensors)?;
        let lengths = tensors.iter().map(|(_, v)| v.len() as u64).collect();
        Ok(Given { offsets, lengths, data })
    }

    /// The blob to hand [`run`].
    pub fn data(&self) -> &[u8] {
        &self.data
    }
}

impl WeightSource for Given {
    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        let declared: u64 = dims.iter().map(|&d| d as u64).product();
        let length = *self
            .lengths
            .get(index)
            .ok_or_else(|| format!("tensor {index} of {}: out of range", self.lengths.len()))?;
        if declared != length {
            return Err(format!(
                "tensor {index} holds {length} values, the forward pass wants {dims:?}"
            ));
        }
        self.offsets
            .get(index)
            .copied()
            .ok_or_else(|| format!("tensor {index} has no offset"))
    }

    fn count(&self) -> usize {
        self.lengths.len()
    }
}

/// A [`WeightSource`] that invents weights for whatever shapes it is asked for, so a
/// whole net can be run without its asset.
///
/// Values are deterministic and scaled by `1 / sqrt(fan_in)`, which matters more than
/// it looks: uniform random weights through 119 layers either saturate fp16 or decay to
/// zero, and either way an end-to-end run proves nothing. Kaiming-style scaling keeps
/// activations near unit variance all the way down, so "the output is finite and in
/// range" becomes a real statement about the plan.
///
/// Biases are zero. A bias is one value per output channel and contributes no
/// indexing that the weights do not already cover.
pub struct Invented {
    count: usize,
    state: RefCell<Laid>,
}

/// What [`Invented`] has handed out so far.
struct Laid {
    /// Element offset per tensor index, once asked for.
    offsets: Vec<Option<u32>>,
    data: Vec<u8>,
}

impl Invented {
    /// A source that expects to be asked for exactly `count` tensors.
    pub fn new(count: usize) -> Invented {
        Invented {
            count,
            state: RefCell::new(Laid { offsets: vec![None; count], data: Vec::new() }),
        }
    }

    /// The blob to hand [`run`], after the net has been built against this.
    pub fn into_data(self) -> Vec<u8> {
        self.state.into_inner().data
    }
}

impl WeightSource for Invented {
    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        let mut state = self.state.borrow_mut();
        if let Some(Some(offset)) = state.offsets.get(index).copied() {
            return Ok(offset);
        }
        let elements: u64 = dims.iter().map(|&d| d as u64).product();
        // A rank-1 tensor is a bias; anything else is a kernel `[m, k, kh, kw]` whose
        // fan-in is everything but the first dimension.
        let fan_in: u64 = dims.iter().skip(1).map(|&d| d as u64).product();
        let scale = if dims.len() == 1 { 0.0 } else { 1.0 / (fan_in.max(1) as f32).sqrt() };

        while !state.data.len().is_multiple_of(16) {
            state.data.push(0);
        }
        let offset = (state.data.len() / 2) as u32;
        let mut random = seed(index);
        for _ in 0..elements {
            let value = uniform(&mut random) * scale;
            state.data.extend_from_slice(&f32_to_f16(value).to_le_bytes());
        }
        *state
            .offsets
            .get_mut(index)
            .ok_or_else(|| format!("tensor {index} of {}: out of range", self.count))? =
            Some(offset);
        Ok(offset)
    }

    fn count(&self) -> usize {
        self.count
    }
}

/// A non-zero xorshift seed derived from a tensor index, so each tensor's values are
/// reproducible on their own rather than dependent on the order tensors were asked for.
fn seed(index: usize) -> u32 {
    (index as u32).wrapping_mul(2_654_435_761).wrapping_add(0x9e37_79b9) | 1
}

/// xorshift32, mapped to `-1..1`.
fn uniform(state: &mut u32) -> f32 {
    *state ^= *state << 13;
    *state ^= *state >> 17;
    *state ^= *state << 5;
    // The top 24 bits, so the quotient is exact in fp32 and lands in `0..1`.
    (*state >> 8) as f32 / (1u32 << 24) as f32 * 2.0 - 1.0
}

#[cfg(test)]
mod tests {
    use super::super::{mobilefacenet, ppocr_det, ppocr_rec, scrfd, selfie, u2netp, Act, Builder};
    use super::*;

    /// Build a plan whose only ops come from `record`, run it, and return the output.
    ///
    /// Going through the real [`Builder`] rather than hand-writing a [`Push`] is the
    /// point: these fixtures check the resolved plan, so the shape propagation, the
    /// group split and the arena offsets are all under test too.
    fn one(
        input_shape: Shape,
        input: &[f32],
        tensors: &[(Vec<u32>, Vec<f32>)],
        record: impl FnOnce(&mut Builder, Id) -> Id,
    ) -> Vec<f32> {
        let given = Given::new(tensors).expect("the fixture tensors are consistent");
        let mut builder = Builder::new(&given);
        let first = builder.input(input_shape);
        let last = record(&mut builder, first);
        let plan = builder.finish(&[last]).expect("the fixture plan builds");
        run(&plan, given.data(), input).expect("the fixture plan runs")
    }

    /// [`one`], for the ops whose two operands are different shapes.
    ///
    /// Attention cannot be checked with one tensor used twice: `Q . K^T` is symmetric
    /// when `Q == K`, so a fixture built that way passes with the two operands swapped
    /// and with the query and key axes transposed.
    fn two(
        shapes: (Shape, Shape),
        inputs: (&[f32], &[f32]),
        record: impl FnOnce(&mut Builder, Id, Id) -> Id,
    ) -> Vec<f32> {
        let given = Given::new(&[]).expect("no tensors");
        let mut builder = Builder::new(&given);
        let first = builder.input(shapes.0);
        let second = builder.input(shapes.1);
        let last = record(&mut builder, first, second);
        let plan = builder.finish(&[last]).expect("the fixture plan builds");
        super::super::tests::assert_no_aliasing(&plan);
        let outputs =
            run_multi(&plan, given.data(), &[inputs.0, inputs.1]).expect("the fixture plan runs");
        match <[Vec<f32>; 1]>::try_from(outputs) {
            Ok([only]) => only,
            Err(other) => panic!("{} outputs", other.len()),
        }
    }

    fn close(got: &[f32], want: &[f32]) {
        assert_eq!(got.len(), want.len(), "{got:?} vs {want:?}");
        for (i, (&g, &w)) in got.iter().zip(want).enumerate() {
            // fp16 carries ~3 decimal digits, and these fixtures are small integers
            // and halves, so the tolerance only has to absorb the store.
            let tolerance = w.abs() * 1e-3 + 1e-3;
            assert!((g - w).abs() <= tolerance, "element {i}: {got:?} vs {want:?}");
        }
    }

    #[test]
    fn a_dense_convolution_matches_a_hand_summed_window() {
        // 2x2 of ones over 1..9, bias 0.5. Each output is the sum of its window.
        let got = one(
            Shape::new(1, 3, 3),
            &[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0],
            &[(vec![1, 1, 2, 2], vec![1.0; 4]), (vec![1], vec![0.5])],
            |b, x| b.conv(x, 0, 1, (2, 2), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None),
        );
        close(&got, &[12.5, 16.5, 24.5, 28.5]);
    }

    #[test]
    fn asymmetric_padding_is_applied_only_where_onnx_puts_it() {
        // The selfie net's stem shape: 3x3 stride 2, `pads = [0, 0, 1, 1]`. Nothing
        // above or left, one row and column below and right. Padding symmetrically
        // gives the same 2x2 output and different numbers in every cell, which is
        // exactly the error no mask inspection would reveal.
        let input: Vec<f32> = (1..=16).map(|v| v as f32).collect();
        let got = one(
            Shape::new(1, 4, 4),
            &input,
            &[(vec![1, 1, 3, 3], vec![1.0; 9]), (vec![1], vec![0.0])],
            |b, x| b.conv(x, 0, 1, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::None),
        );
        // Windows anchored at (0,0) and (0,2) / (2,0) and (2,2), clipped at the far edge.
        close(&got, &[54.0, 45.0, 72.0, 54.0]);
    }

    #[test]
    fn a_dilated_convolution_skips_the_taps_it_should() {
        // U^2-Netp's shape: 3x3, dilation 2, pad 2, which holds the extent. At the
        // centre the taps land on rows and columns 0, 2, 4 of a 5x5; at the corner two
        // of the three fall in the padding.
        let input: Vec<f32> = (1..=25).map(|v| v as f32).collect();
        let got = one(
            Shape::new(1, 5, 5),
            &input,
            &[(vec![1, 1, 3, 3], vec![1.0; 9]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 3, 2, Act::None),
        );
        assert_eq!(got.len(), 25);
        // Centre: 1+3+5 + 11+13+15 + 21+23+25.
        close(&got[12..13], &[117.0]);
        // Corner: rows 0 and 2, columns 0 and 2 only.
        close(&got[0..1], &[28.0]);
    }

    #[test]
    fn a_depthwise_convolution_never_mixes_channels() {
        // group == in_c == out_c, so each output channel sees exactly one input
        // channel. A weight table read as if it were dense would fold both together.
        let got = one(
            Shape::new(2, 1, 2),
            &[1.0, 2.0, 10.0, 20.0],
            &[(vec![2, 1, 1, 1], vec![3.0, 5.0]), (vec![2], vec![0.0, 0.0])],
            |b, x| b.conv(x, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::None),
        );
        close(&got, &[3.0, 6.0, 50.0, 100.0]);
    }

    #[test]
    fn a_grouped_convolution_reads_only_its_own_slice() {
        // Four channels in two groups of two, each input channel a distinct decade so
        // any leak across the group boundary is visible in the sum.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 10.0, 100.0, 1000.0],
            &[(vec![4, 2, 1, 1], vec![1.0; 8]), (vec![4], vec![0.0; 4])],
            |b, x| b.conv(x, 0, 4, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::None),
        );
        close(&got, &[11.0, 11.0, 1100.0, 1100.0]);
    }

    #[test]
    fn a_transposed_convolution_reads_input_channels_outermost() {
        // The layout inversion. Weights are `[in_c, out_c, kh, kw]`, so with two input
        // channels the first four values belong to input channel 0. Reading them as
        // `Conv` does — `[out_c, in_c, kh, kw]` — gives 7 here instead of 21, and an
        // output of the right shape either way.
        let got = one(
            Shape::new(2, 1, 1),
            &[1.0, 2.0],
            &[
                (vec![2, 1, 2, 2], vec![1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0]),
                (vec![1], vec![0.0]),
            ],
            |b, x| b.conv_transpose(x, 0, 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::None),
        );
        close(&got, &[21.0, 42.0, 63.0, 84.0]);
    }

    #[test]
    fn max_pooling_takes_the_largest_of_each_window() {
        let input: Vec<f32> = (1..=16).map(|v| v as f32).collect();
        let got = one(Shape::new(1, 4, 4), &input, &[], |b, x| b.max_pool_2x2(x));
        close(&got, &[6.0, 8.0, 14.0, 16.0]);
    }

    #[test]
    fn average_pooling_takes_the_mean_of_each_window() {
        // The same 4x4 as the max-pool fixture above, so the two are directly
        // comparable: 6 against 3.5 for the first window.
        let input: Vec<f32> = (1..=16).map(|v| v as f32).collect();
        let got = one(Shape::new(1, 4, 4), &input, &[], |b, x| {
            b.avg_pool(x, (2, 2), (2, 2))
        });
        close(&got, &[3.5, 5.5, 11.5, 13.5]);
    }

    #[test]
    fn an_asymmetric_pool_window_is_not_transposed() {
        // The recogniser's shape: kernel and stride both (3, 2) on a 3-row map, which
        // collapses the height to one and halves the width. This is the step that turns
        // a feature map into a `[d_model, 1, T]` sequence.
        //
        // Rows are (1,2,3,4), (5,6,7,8), (9,10,11,12), so the left window holds
        // 1,2,5,6,9,10 and the right one 3,4,7,8,11,12. A kernel read as (2, 3) instead
        // would pool 1,2,3,5,6,7 and answer 4 for the first column.
        let input: Vec<f32> = (1..=12).map(|v| v as f32).collect();
        let got = one(Shape::new(1, 3, 4), &input, &[], |b, x| {
            b.avg_pool(x, (3, 2), (3, 2))
        });
        close(&got, &[33.0 / 6.0, 45.0 / 6.0]);
    }

    #[test]
    fn average_pooling_keeps_channels_apart() {
        // Two channels a decade apart, so a pool whose plane stride was wrong folds them
        // together visibly rather than shifting the answer slightly.
        let got = one(
            Shape::new(2, 1, 4),
            &[1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0],
            &[],
            |b, x| b.avg_pool(x, (1, 2), (1, 2)),
        );
        close(&got, &[1.5, 3.5, 15.0, 35.0]);
    }

    #[test]
    fn a_pool_window_that_does_not_tile_its_input_is_refused() {
        // 5 wide with a 2-wide window at stride 2 drops the last column, and the shader
        // divides by the window size regardless — so the choice is between a silently
        // dropped column and a silently rescaled edge. Neither is acceptable, so the
        // build fails instead.
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let x = b.input(Shape::new(1, 2, 5));
        let pooled = b.avg_pool(x, (2, 2), (2, 2));
        let error = b.finish(&[pooled]).expect_err("a 5-wide input");
        assert!(error.contains("does not tile 5 along w"), "{error}");
    }

    #[test]
    fn a_resize_puts_its_samples_at_half_pixel_centres() {
        // Two columns to four. The samples land at source x = -0.25, 0.25, 0.75, 1.25,
        // which clamp at both ends. `align_corners` would give 0, 4/3, 8/3, 4 instead.
        let got = one(Shape::new(1, 1, 2), &[0.0, 4.0], &[], |b, x| b.resize_to(x, 1, 4));
        close(&got, &[0.0, 1.0, 3.0, 4.0]);
    }

    #[test]
    fn a_resize_down_to_one_pixel_averages_all_four_taps() {
        // Half-pixel puts the single sample at the centre of the 2x2, so all four taps
        // weigh equally.
        let got = one(Shape::new(1, 2, 2), &[0.0, 4.0, 4.0, 0.0], &[], |b, x| {
            b.resize_to(x, 1, 1)
        });
        close(&got, &[2.0]);
    }

    #[test]
    fn global_average_pooling_reduces_each_channel_on_its_own() {
        let got = one(
            Shape::new(2, 2, 2),
            &[1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0],
            &[],
            |b, x| b.global_avg_pool(x),
        );
        close(&got, &[2.5, 25.0]);
    }

    #[test]
    fn a_channel_broadcast_multiply_indexes_the_gate_by_channel_alone() {
        let got = one(
            Shape::new(2, 1, 2),
            &[1.0, 2.0, 4.0, 8.0],
            &[(vec![2, 2, 1, 1], vec![1.0, 0.0, 0.0, 1.0]), (vec![2], vec![0.0, 0.0])],
            |b, x| {
                // A `C x 1 x 1` gate, made by pooling and then a 1x1 that passes each
                // channel through unchanged, so the expected values stay hand-computable:
                // the gate is (1.5, 6.0).
                let pooled = b.global_avg_pool(x);
                let gate = b.conv_same(pooled, 0, 2, 1, 1, Act::None);
                b.mul_channel(x, gate)
            },
        );
        close(&got, &[1.5, 3.0, 24.0, 48.0]);
    }

    #[test]
    fn an_elementwise_add_pairs_the_two_operands_positionally() {
        let got = one(Shape::new(1, 1, 3), &[1.0, 2.0, 3.0], &[], |b, x| {
            let doubled = b.add(x, x);
            b.add(doubled, x)
        });
        close(&got, &[3.0, 6.0, 9.0]);
    }

    #[test]
    fn a_concatenation_lands_the_parts_end_to_end_in_channel_order() {
        // Concat is lowered to copies rather than a shader, so this is the check that
        // the destination offsets stack correctly.
        let got = one(Shape::new(1, 1, 2), &[1.0, 2.0], &[], |b, x| {
            let resized = b.resize_to(x, 1, 2);
            b.concat(&[x, resized, x])
        });
        close(&got, &[1.0, 2.0, 1.0, 2.0, 1.0, 2.0]);
    }

    #[test]
    fn the_activation_codes_agree_with_the_ones_the_builder_emits() {
        // `activate` here and in `common.glsl` both switch on a bare `u32`, so a
        // reordering of `Act` would silently repoint every activation in both.
        assert_eq!(Act::None.code(), act::NONE);
        assert_eq!(Act::Relu.code(), act::RELU);
        assert_eq!(Act::HardSwish.code(), act::HARDSWISH);
        assert_eq!(Act::Sigmoid.code(), act::SIGMOID);
        assert_eq!(Act::PRelu(0).code(), act::PRELU);
        assert_eq!(Act::Clip01.code(), act::CLIP01);
        assert_eq!(Act::Swish.code(), act::SWISH);
    }

    #[test]
    fn prelu_scales_only_the_negative_side_and_per_channel() {
        // Two channels with different slopes, so a PReLU that read one slope for the
        // whole tensor — or indexed it by element instead of by channel — is visible.
        // Slopes 0.25 and 4.0; inputs -2 and 3 in each channel.
        let got = one(
            Shape::new(2, 1, 2),
            &[-2.0, 3.0, -2.0, 3.0],
            &[
                (vec![2, 1, 1, 1], vec![1.0, 1.0]),
                (vec![2], vec![0.0, 0.0]),
                (vec![2, 1, 1], vec![0.25, 4.0]),
            ],
            |b, x| {
                b.conv(x, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::PRelu(2))
            },
        );
        close(&got, &[-0.5, 3.0, -8.0, 3.0]);
    }

    #[test]
    fn prelu_at_slope_one_is_the_identity_and_at_zero_is_relu() {
        // The two degenerate slopes, which pin the sign convention: a shader that
        // scaled the positive side instead would pass the identity case and fail this.
        let got = one(
            Shape::new(2, 1, 2),
            &[-4.0, 4.0, -4.0, 4.0],
            &[
                (vec![2, 1, 1, 1], vec![1.0, 1.0]),
                (vec![2], vec![0.0, 0.0]),
                (vec![2, 1, 1], vec![1.0, 0.0]),
            ],
            |b, x| {
                b.conv(x, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::PRelu(2))
            },
        );
        close(&got, &[-4.0, 4.0, 0.0, 4.0]);
    }

    #[test]
    fn a_nearest_resize_repeats_each_source_pixel_rather_than_blending() {
        // Two columns to four, `asymmetric` + `floor`: src = floor(dst * 2 / 4) = dst/2,
        // so 0, 0, 1, 1. The bilinear kernel gives 0, 1, 3, 4 for the same input, which
        // is what makes this the discriminating fixture.
        let got = one(Shape::new(1, 1, 2), &[0.0, 4.0], &[], |b, x| {
            let like = b.resize_to(x, 1, 4);
            b.resize_nearest_like(x, like)
        });
        close(&got, &[0.0, 0.0, 4.0, 4.0]);
    }

    #[test]
    fn a_nearest_resize_upsamples_both_axes_together() {
        // A 2x2 doubled to 4x4. Each source pixel becomes a 2x2 block, so a row/column
        // transposition in the index arithmetic changes the answer.
        let got = one(Shape::new(1, 2, 2), &[1.0, 2.0, 3.0, 4.0], &[], |b, x| {
            let like = b.resize_to(x, 4, 4);
            b.resize_nearest_like(x, like)
        });
        close(
            &got,
            &[
                1.0, 1.0, 2.0, 2.0, //
                1.0, 1.0, 2.0, 2.0, //
                3.0, 3.0, 4.0, 4.0, //
                3.0, 3.0, 4.0, 4.0,
            ],
        );
    }

    #[test]
    fn a_plan_can_declare_more_than_one_input_and_output() {
        // SCRFD needs nine outputs and the VITS duration predictor three inputs, so the
        // plan's bindings are lists. This checks both ends: two inputs land at distinct
        // arena offsets, and two outputs come back in the order `finish` was given.
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let first = b.input(Shape::new(1, 1, 2));
        let second = b.input(Shape::new(1, 1, 2));
        let sum = b.add(first, second);
        let doubled = b.add(sum, sum);
        let plan = b.finish(&[doubled, sum]).expect("builds");

        assert_eq!(plan.inputs.len(), 2);
        assert_ne!(
            plan.inputs.first().map(|b| b.at),
            plan.inputs.get(1).map(|b| b.at),
            "the two inputs share an offset"
        );
        let got = run_multi(&plan, given.data(), &[&[1.0, 2.0], &[10.0, 20.0]])
            .expect("the two-input plan runs");
        assert_eq!(got, vec![vec![22.0, 44.0], vec![11.0, 22.0]]);
    }

    #[test]
    fn a_single_output_helper_refuses_a_multi_output_plan() {
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let first = b.input(Shape::new(1, 1, 1));
        let same = b.add(first, first);
        let plan = b.finish(&[same, first]).expect("builds");
        let error = run(&plan, given.data(), &[1.0]).expect_err("two outputs");
        assert!(error.contains("2 outputs"), "{error}");
    }

    #[test]
    fn an_affine_scales_and_shifts_every_element_by_the_same_scalars() {
        let got = one(Shape::new(1, 1, 4), &[-2.0, 0.0, 1.0, 4.0], &[], |b, x| {
            b.affine(x, 0.5, 3.0)
        });
        close(&got, &[2.0, 3.0, 3.5, 5.0]);
    }

    #[test]
    fn an_affine_carries_its_scalars_through_the_push_block_as_bits() {
        // The two parameters are `f32` bits in a `u32` field so `Push` stays all-`u32`.
        // A value with a non-trivial mantissa catches a reinterpretation that happens to
        // work for small integers.
        let got = one(Shape::new(1, 1, 2), &[1.0, 2.0], &[], |b, x| {
            b.affine(x, 0.3, -0.7)
        });
        close(&got, &[0.3 - 0.7, 0.6 - 0.7]);
    }

    #[test]
    fn clip01_clamps_to_the_unit_interval() {
        // A normalised HardSigmoid: `ppocr_fold.py` folds alpha and beta into the
        // convolution, so what reaches the shader is the bare clamp.
        let got = one(
            Shape::new(1, 1, 5),
            &[-4.0, -0.5, 0.25, 1.0, 9.0],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Clip01),
        );
        close(&got, &[0.0, 0.0, 0.25, 1.0, 1.0]);
    }

    #[test]
    fn a_folded_hard_sigmoid_matches_the_onnx_definition() {
        // The fold's claim: `clamp(alpha * (w*x + b) + beta, 0, 1)` equals
        // `clamp(w'*x + b', 0, 1)` with `w' = alpha*w` and `b' = alpha*b + beta`. Checked
        // here against ONNX's formula computed directly, at both alphas PP-OCRv5 uses.
        for alpha in [0.2f32, 1.0 / 6.0] {
            let (w, bias) = (2.0f32, -0.5f32);
            let inputs = [-3.0f32, -0.4, 0.0, 0.9, 5.0];
            let got = one(
                Shape::new(1, 1, 5),
                &inputs,
                &[
                    (vec![1, 1, 1, 1], vec![alpha * w]),
                    (vec![1], vec![alpha * bias + 0.5]),
                ],
                |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Clip01),
            );
            let want: Vec<f32> = inputs
                .iter()
                .map(|&x| (alpha * (w * x + bias) + 0.5).clamp(0.0, 1.0))
                .collect();
            close(&got, &want);
        }
    }

    #[test]
    fn swish_is_x_times_sigmoid_x_and_not_hard_swish() {
        // The recogniser uses both, so the piecewise approximation must not be
        // substituted here. They differ most around |x| = 1..3.
        let inputs = [-3.0f32, -1.0, 0.0, 1.0, 3.0];
        let got = one(
            Shape::new(1, 1, 5),
            &inputs,
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Swish),
        );
        let want: Vec<f32> = inputs.iter().map(|&x| x / (1.0 + (-x).exp())).collect();
        close(&got, &want);
        // And it really is a different function from HardSwish at x = 1.
        let hard = 1.0 * (1.0 / 6.0 + 0.5);
        assert!((got[3] - hard).abs() > 0.05, "swish {} vs hardswish {hard}", got[3]);
    }

    #[test]
    fn layer_norm_standardises_a_column_of_channels() {
        // Four channels at one position: mean 2.5, biased variance 1.25.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 2.0, 3.0, 4.0],
            &[(vec![4], vec![1.0; 4]), (vec![4], vec![0.0; 4])],
            |b, x| b.layer_norm(x, 0, 1e-5),
        );
        let sd = 1.25f32.sqrt();
        close(&got, &[-1.5 / sd, -0.5 / sd, 0.5 / sd, 1.5 / sd]);
    }

    #[test]
    fn layer_norm_applies_its_affine_per_channel() {
        // A gamma and beta that differ per channel, so a shader broadcasting one value
        // over the column would be visible.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 2.0, 3.0, 4.0],
            &[
                (vec![4], vec![1.0, 2.0, 3.0, 4.0]),
                (vec![4], vec![10.0, 20.0, 30.0, 40.0]),
            ],
            |b, x| b.layer_norm(x, 0, 1e-5),
        );
        let sd = 1.25f32.sqrt();
        let normalised = [-1.5 / sd, -0.5 / sd, 0.5 / sd, 1.5 / sd];
        let want: Vec<f32> = (0..4)
            .map(|i| normalised[i] * (i as f32 + 1.0) + (i as f32 + 1.0) * 10.0)
            .collect();
        close(&got, &want);
    }

    #[test]
    fn layer_norm_treats_each_position_independently() {
        // Two positions with very different scales. Both standardise to the same pair, so
        // a reduction that spanned the whole tensor instead of one column would not.
        //
        // Layout is `[c, 1, T]` channel-major, so this is columns (1, 2) and (3, 10).
        let got = one(
            Shape::new(2, 1, 2),
            &[1.0, 3.0, 2.0, 10.0],
            &[(vec![2], vec![1.0, 1.0]), (vec![2], vec![0.0, 0.0])],
            |b, x| b.layer_norm(x, 0, 1e-5),
        );
        // Two channels always standardise to -1 and +1 regardless of their spread.
        close(&got, &[-1.0, -1.0, 1.0, 1.0]);
    }

    #[test]
    fn layer_norm_epsilon_comes_from_the_push_block() {
        // The recogniser uses 1e-5 for four of its five and 1e-6 for the last, so the
        // value has to travel per op. A constant column makes it the only thing that
        // stops a division by zero.
        let got = one(
            Shape::new(2, 1, 1),
            &[5.0, 5.0],
            &[(vec![2], vec![1.0, 1.0]), (vec![2], vec![0.0, 0.0])],
            |b, x| b.layer_norm(x, 0, 1e-5),
        );
        // Zero variance, so both come out at beta rather than as NaN.
        close(&got, &[0.0, 0.0]);
        assert!(got.iter().all(|v| v.is_finite()), "{got:?}");
    }

    #[test]
    fn attention_scores_contract_over_channels_and_keep_the_key_axis_last() {
        // d_model 2, one head, T 2, so `scale` is 1/sqrt(2). Q and K are `[c, 1, T]`
        // channel-major, so Q's columns are (1,3) and (2,4) and K's are (5,7) and (6,8).
        //
        // Distinct operands on purpose: Q.K^T with Q == K is symmetric, and a symmetric
        // fixture passes with the query and key axes transposed. Here S[0][1] is 30 and
        // S[1][0] is 38.
        let got = two(
            (Shape::new(2, 1, 2), Shape::new(2, 1, 2)),
            (&[1.0, 2.0, 3.0, 4.0], &[5.0, 6.0, 7.0, 8.0]),
            |b, q, k| b.attn_scores(q, k, 1),
        );
        let scale = 1.0 / 2f32.sqrt();
        close(&got, &[26.0 * scale, 30.0 * scale, 38.0 * scale, 44.0 * scale]);
    }

    #[test]
    fn attention_scores_never_mix_two_heads() {
        // d_model 4 in two heads, so head 0 owns channels 0-1 and head 1 channels 2-3.
        // The second head's values are a decade larger, so any leak across the boundary
        // moves head 0's scores by about a hundredfold rather than subtly.
        //
        // Q == K here, which is fine: what is under test is the channel range each head
        // reads, and the axis convention is pinned by the fixture above.
        let sequence = [1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0];
        let got = two(
            (Shape::new(4, 1, 2), Shape::new(4, 1, 2)),
            (&sequence, &sequence),
            |b, q, k| b.attn_scores(q, k, 2),
        );
        // head_dim is 2, so the scale is still 1/sqrt(2).
        let scale = 1.0 / 2f32.sqrt();
        close(
            &got,
            &[
                10.0 * scale, 14.0 * scale, 14.0 * scale, 20.0 * scale, //
                1000.0 * scale, 1400.0 * scale, 1400.0 * scale, 2000.0 * scale,
            ],
        );
    }

    #[test]
    fn attention_scale_is_the_inverse_root_of_the_head_dimension() {
        // Four channels in four heads is head_dim 1, where the scale is exactly 1, so
        // this fixture is the raw product and isolates the scale from the contraction.
        // At one head the same tensors would be divided by 2 instead.
        let got = two(
            (Shape::new(4, 1, 1), Shape::new(4, 1, 1)),
            (&[1.0, 2.0, 3.0, 4.0], &[5.0, 6.0, 7.0, 8.0]),
            |b, q, k| b.attn_scores(q, k, 4),
        );
        close(&got, &[5.0, 12.0, 21.0, 32.0]);
    }

    #[test]
    fn softmax_normalises_each_row_of_the_last_axis_on_its_own() {
        // `[2, 2, 2]` is four rows of two, the shape a score map has. Rows are (1,2),
        // (5,5), (0,100) and (-3,-3): a plain pair, two ties at different offsets, and
        // one row whose spread would overflow.
        let got = one(
            Shape::new(2, 2, 2),
            &[1.0, 2.0, 5.0, 5.0, 0.0, 100.0, -3.0, -3.0],
            &[],
            |b, x| b.softmax(x),
        );
        let pair = (-1.0f32).exp() / (1.0 + (-1.0f32).exp());
        close(
            &got,
            &[
                pair, 1.0 - pair, //
                0.5, 0.5, //
                0.0, 1.0, //
                0.5, 0.5,
            ],
        );
        // Every row is a distribution. Two equal values summing to 1 is what a shader
        // that forgot to divide would also produce for rows 2 and 4, so the sum is
        // checked for all of them.
        for (row, pair) in got.chunks_exact(2).enumerate() {
            let total: f32 = pair.iter().sum();
            assert!((total - 1.0).abs() < 2e-3, "row {row} sums to {total}");
        }
    }

    #[test]
    fn softmax_subtracts_the_row_maximum_rather_than_exponentiating_directly() {
        // exp overflows fp32 a little past 88, so a row containing 100 sums to infinity
        // and every probability in it becomes a NaN. Subtracting the maximum first makes
        // the largest term exp(0), which cannot overflow and also floors the denominator
        // at 1.
        let got = one(Shape::new(1, 1, 3), &[100.0, 99.0, -100.0], &[], |b, x| b.softmax(x));
        assert!(got.iter().all(|v| v.is_finite()), "{got:?}");
        let expected = 1.0 / (1.0 + (-1.0f32).exp());
        close(&got, &[expected, 1.0 - expected, 0.0]);
    }

    #[test]
    fn attention_applies_a_row_of_weights_across_the_keys_not_the_queries() {
        // One head, d_model 2, T 2. Weights are `[heads, T, T]` with the key innermost,
        // so query 0 mixes (0.25, 0.75) and query 1 takes key 0 alone.
        //
        // V's columns are (10, 3) and (20, 7). Reading the weight matrix transposed gives
        // 22.5 for the first output instead of 17.5, which is why the rows differ.
        let got = two(
            (Shape::new(1, 2, 2), Shape::new(2, 1, 2)),
            (&[0.25, 0.75, 1.0, 0.0], &[10.0, 20.0, 3.0, 7.0]),
            |b, probs, v| b.attn_apply(probs, v, 1),
        );
        close(&got, &[17.5, 10.0, 6.0, 3.0]);
    }

    #[test]
    fn attention_uses_the_head_only_to_pick_a_plane_of_weights() {
        // Two heads over d_model 4. Head 0's weights are the identity and head 1's swap
        // the two keys, so the expected output is V with its last two channels reversed
        // along T and the first two untouched.
        //
        // This is the claim that makes the head concatenation free: channel `c` of V is
        // channel `c` of the output, and the head index selects nothing but which plane
        // of the score map to read.
        let got = two(
            (Shape::new(2, 2, 2), Shape::new(4, 1, 2)),
            (
                &[1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0],
                &[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0],
            ),
            |b, probs, v| b.attn_apply(probs, v, 2),
        );
        close(&got, &[1.0, 2.0, 3.0, 4.0, 6.0, 5.0, 8.0, 7.0]);
    }

    #[test]
    fn a_whole_attention_averages_its_values_when_every_score_is_equal() {
        // The three ops composed. Q and K are zero, so every score is zero, so every
        // softmax row is uniform at 1/T, so each output position is the mean of V over
        // the sequence — the same value at all four positions.
        //
        // That pins all three at once in a way none of them can fake: a missing scale
        // still gives zeros, but a softmax that did not normalise scales the mean by T,
        // and a weighted sum that indexed V by query rather than key returns V itself.
        let got = two(
            (Shape::new(2, 1, 4), Shape::new(2, 1, 4)),
            (
                &[0.0; 8],
                &[1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0],
            ),
            |b, zeros, v| {
                let scores = b.attn_scores(zeros, zeros, 1);
                let probs = b.softmax(scores);
                b.attn_apply(probs, v, 1)
            },
        );
        close(&got, &[2.5, 2.5, 2.5, 2.5, 25.0, 25.0, 25.0, 25.0]);
    }

    #[test]
    fn a_head_count_that_does_not_divide_the_channels_fails_the_build() {
        // d_model 120 in 8 heads is the recogniser's geometry; anything that does not
        // divide would silently reinterpret the channel runs as overlapping heads.
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let q = b.input(Shape::new(6, 1, 2));
        let scores = b.attn_scores(q, q, 4);
        let error = b.finish(&[scores]).expect_err("6 channels in 4 heads");
        assert!(error.contains("do not split into 4 heads"), "{error}");
    }

    #[test]
    fn a_sequence_with_a_height_above_one_is_refused() {
        // `[d_model, 1, T]` is what makes the head split a reinterpretation rather than
        // a copy. A taller tensor would be read as if the extra rows were sequence
        // positions, which is wrong and produces an output of a plausible shape.
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let q = b.input(Shape::new(4, 2, 2));
        let scores = b.attn_scores(q, q, 2);
        let error = b.finish(&[scores]).expect_err("a two-row sequence");
        assert!(error.contains("height above"), "{error}");
    }

    #[test]
    fn relu_clamps_at_zero_without_touching_positives() {
        let got = one(
            Shape::new(1, 1, 2),
            &[-3.0, 4.0],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Relu),
        );
        close(&got, &[0.0, 4.0]);
    }

    #[test]
    fn hard_swish_matches_onnx_at_its_default_alpha_and_beta() {
        // `x * clamp(x/6 + 0.5, 0, 1)`: saturated off at -3, linear at 0 and 1,
        // saturated on at 3 and above.
        let got = one(
            Shape::new(1, 1, 5),
            &[-4.0, -3.0, 0.0, 1.0, 3.0],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::HardSwish),
        );
        close(&got, &[0.0, 0.0, 0.0, 1.0 * (1.0 / 6.0 + 0.5), 3.0]);
    }

    #[test]
    fn sigmoid_is_the_logistic_function_and_bounds_the_mask_to_zero_one() {
        let got = one(
            Shape::new(1, 1, 3),
            &[-8.0, 0.0, 8.0],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Sigmoid),
        );
        close(&got, &[1.0 / (1.0 + 8f32.exp()), 0.5, 1.0 / (1.0 + (-8f32).exp())]);
    }

    #[test]
    fn a_stored_value_is_rounded_to_fp16_not_kept_in_fp32() {
        // The arena is fp16 on the device, so the reference must not be more accurate
        // than the thing it is a reference for. 1 + 2^-11 is exactly halfway to the
        // next fp16 and must round to 1.
        let got = one(
            Shape::new(1, 1, 1),
            &[1.0 + 2f32.powi(-11)],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::None),
        );
        assert_eq!(got, vec![1.0]);
    }

    #[test]
    fn a_mismatched_input_length_is_refused_rather_than_padded() {
        let given = Given::new(&[]).expect("no tensors");
        let mut builder = Builder::new(&given);
        let input = builder.input(Shape::new(1, 2, 2));
        let out = builder.resize_to(input, 2, 2);
        let plan = builder.finish(&[out]).expect("builds");
        let error = run(&plan, given.data(), &[1.0, 2.0]).expect_err("too few values");
        assert!(error.contains("2 input values"), "{error}");
    }

    /// A net with every op in it, at a size a debug build can run in milliseconds.
    ///
    /// This is what keeps the interpreter itself honest on every `cargo test`: the two
    /// real nets below are hundreds of times larger and have to be opted into.
    fn miniature(weights: &dyn WeightSource) -> Result<Plan, String> {
        let mut b = Builder::new(weights);
        let first = b.input(Shape::new(3, 8, 8));
        let x = b.conv(first, 0, 8, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::HardSwish);
        let pooled = b.global_avg_pool(x);
        let gate = b.conv_same(pooled, 2, 8, 1, 1, Act::Sigmoid);
        let gated = b.mul_channel(x, gate);
        let deep = b.max_pool_2x2(gated);
        let deep = b.conv_same(deep, 4, 8, 3, 1, Act::Relu);
        let up = b.resize_like(deep, gated);
        let merged = b.add(gated, up);
        let joined = b.concat(&[merged, up]);
        let out = b.conv_transpose(joined, 6, 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::Sigmoid);
        b.finish(&[out])
    }

    #[test]
    fn the_miniature_net_runs_every_op_and_stays_in_range() {
        let source = Invented::new(8);
        let plan = miniature(&source).expect("the miniature net builds");
        let data = source.into_data();
        let input: Vec<f32> = (0..plan.input().expect("one input").shape.len())
            .map(|i| (i as f32 * 0.37).sin())
            .collect();
        let got = run(&plan, &data, &input).expect("the miniature net runs");

        assert_eq!(got.len(), plan.output().expect("one output").shape.len() as usize);
        // A sigmoid output, so every value is a probability. A NaN or an fp16 overflow
        // anywhere upstream lands outside this.
        for (i, &value) in got.iter().enumerate() {
            assert!((0.0..=1.0).contains(&value), "element {i} is {value}");
        }
        // Not a constant: a plan that dropped its spatial ops would still be in range.
        let first = got.first().copied().unwrap_or_default();
        assert!(got.iter().any(|&v| v != first), "the output is uniform");
    }

    #[test]
    fn the_miniature_net_is_deterministic() {
        let source = Invented::new(8);
        let plan = miniature(&source).expect("builds");
        let data = source.into_data();
        let input = vec![0.25; plan.input().expect("one input").shape.len() as usize];
        assert_eq!(
            run(&plan, &data, &input).expect("first run"),
            run(&plan, &data, &input).expect("second run")
        );
    }

    #[test]
    fn invented_weights_are_reproducible_per_tensor() {
        // Each tensor's values come from its own index, so the blob does not depend on
        // the order a forward pass happens to ask for them.
        let first = Invented::new(4);
        let second = Invented::new(4);
        for source in [&first, &second] {
            assert!(source.shaped(0, &[2, 2, 1, 1]).is_ok());
            assert!(source.shaped(1, &[2]).is_ok());
            assert!(source.shaped(2, &[2, 2, 1, 1]).is_ok());
            assert!(source.shaped(3, &[2]).is_ok());
        }
        assert_eq!(first.into_data(), second.into_data());
    }

    #[test]
    fn invented_kernels_are_scaled_by_their_fan_in() {
        // Without this a 119-layer net either saturates fp16 or decays to zero, and an
        // end-to-end run says nothing.
        let source = Invented::new(1);
        assert!(source.shaped(0, &[4, 16, 3, 3]).is_ok());
        let data = source.into_data();
        let bound = 1.0 / (16.0f32 * 3.0 * 3.0).sqrt();
        for pair in data.chunks_exact(2) {
            let value = match pair {
                [low, high] => f16_to_f32(u16::from_le_bytes([*low, *high])),
                _ => unreachable!(),
            };
            assert!(value.abs() <= bound, "{value} exceeds {bound}");
        }
    }

    #[test]
    fn the_ppocr_rec_net_runs_end_to_end_and_decodes() {
        // The whole recognition pass, at width 16 rather than the 320 it runs at on
        // device: `T` is 2 instead of 40, which still exercises all 56 layers, both
        // attentions, all five layer norms, both squeeze-excites and the pool. Invented
        // weights, so no asset is needed.
        //
        // Width 16 rather than 8 on purpose. At `T` 1 a softmax over one key is 1.0
        // whatever the scores were, so the attention would run without its indexing being
        // observable at all. Two costs about a second more in a debug build and makes the
        // query and key axes distinguishable.
        let source = Invented::new(ppocr_rec::TENSORS);
        let plan = ppocr_rec::build(&source, 16).expect("ppocr_rec builds at width 16");
        let data = source.into_data();
        let shape = plan.input().expect("one input").shape;
        let logits = run(&plan, &data, &blob(shape)).expect("ppocr_rec runs");

        let steps = 16 / 8;
        assert_eq!(logits.len(), ppocr_rec::LOGITS as usize * steps);
        // Raw logits, so the only thing to assert about the values themselves is that fp16
        // did not saturate anywhere down 56 layers — an infinity or a NaN here is what a
        // misindexed weight or an aliased arena produces.
        for (i, &value) in logits.iter().enumerate() {
            assert!(value.is_finite(), "logit {i} is {value}");
        }
        let low = logits.iter().fold(f32::MAX, |a, &b| a.min(b));
        let high = logits.iter().fold(f32::MIN, |a, &b| a.max(b));
        println!("ppocr_rec at 48x16: logits in {low:.4}..{high:.4}");

        // And it feeds the decode. The text is meaningless — the weights are invented —
        // but the confidence must be a probability, which is the end-to-end check that the
        // class-major layout the net writes is the one `ctc::decode` reads.
        let mut keys = String::new();
        for index in 0..crate::post::ctc::DICTIONARY_ENTRIES {
            keys.push((b'a' + (index % 26) as u8) as char);
            keys.push('\n');
        }
        let dictionary = crate::post::ctc::Dictionary::parse(&keys).expect("parses");
        let decoded = crate::post::ctc::decode(&logits, steps, &dictionary).expect("decodes");
        println!("ppocr_rec: {:?} at {:.4}", decoded.text, decoded.confidence);
        assert!(
            (0.0..=1.0).contains(&decoded.confidence),
            "confidence {}",
            decoded.confidence
        );
    }

    /// Run a shipped net on an input from disk and write its output back, for
    /// `scripts/ml/onnx_parity.py` to compare against onnxruntime.
    ///
    /// Ignored, and a no-op without `PARITY_DIR`: it exists to be driven by that script,
    /// which needs the export's ONNX and an `onnxruntime` install that CI does not have.
    /// See the script's header for what the comparison is worth and what it has caught.
    #[test]
    #[ignore = "driven by scripts/ml/onnx_parity.py"]
    fn dump_reference_output() {
        let Ok(dir) = std::env::var("PARITY_DIR") else {
            return;
        };
        let dir = std::path::PathBuf::from(dir);
        let graph = std::env::var("PARITY_GRAPH").expect("PARITY_GRAPH");
        let width: u32 = std::env::var("PARITY_WIDTH")
            .expect("PARITY_WIDTH")
            .parse()
            .expect("a width");
        let raw = std::fs::read(dir.join("input.f32")).expect("the input");
        let input: Vec<f32> = raw
            .chunks_exact(4)
            .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
            .collect();

        let (path, id) = match graph.as_str() {
            "ppocr_rec" => ("library/ocr/src/main/assets/ppocr_rec.vkml", crate::weights::graph::PPOCR_REC),
            "ppocr_det" => ("library/ocr/src/main/assets/ppocr_det.vkml", crate::weights::graph::PPOCR_DET),
            other => panic!("no parity probe for {other}"),
        };
        let bytes = asset(path).unwrap_or_else(|| panic!("{path} is not checked out"));
        let weights = crate::weights::Weights::parse(&bytes, id).expect("the asset parses");
        // Detection's own output is a saturated probability map, so the probe is its
        // backbone output — where all fourteen of its surviving affines are.
        let plan = match graph.as_str() {
            "ppocr_rec" => ppocr_rec::build(&weights, width).expect("ppocr_rec builds"),
            _ => ppocr_det::build_with_backbone(&weights, width, width).expect("det builds"),
        };
        let outputs = run_multi(&plan, weights.data(), &[&input]).expect("the net runs");
        let probe = match graph.as_str() {
            "ppocr_rec" => outputs.first(),
            _ => outputs.get(1),
        }
        .expect("a probe output");
        let mut out = Vec::with_capacity(probe.len() * 4);
        for value in probe {
            out.extend_from_slice(&value.to_le_bytes());
        }
        std::fs::write(dir.join("reference.f32"), out).expect("writes");
        println!("{graph} at {width}: wrote {} values", probe.len());
    }

    /// The shipped `.vkml` for `name`, or `None` if it is not checked out.
    fn asset(name: &str) -> Option<Vec<u8>> {
        let mut dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        while !dir.join("settings.gradle.kts").is_file() {
            dir = dir.parent()?.to_path_buf();
        }
        std::fs::read(dir.join(name)).ok()
    }

    /// A gradient: monotonic in both axes, and containing nothing a segmentation net
    /// should find.
    fn ramp(shape: Shape) -> Vec<f32> {
        let mut values = Vec::with_capacity(shape.len() as usize);
        for c in 0..shape.c {
            for y in 0..shape.h {
                for x in 0..shape.w {
                    let across = x as f32 / shape.w as f32;
                    let down = y as f32 / shape.h as f32;
                    values.push((across + down + c as f32 * 0.1) / 2.2);
                }
            }
        }
        values
    }

    /// A bright centred blob on a dark field — the crudest possible stand-in for a
    /// subject, and structurally nothing like [`ramp`].
    fn blob(shape: Shape) -> Vec<f32> {
        let mut values = Vec::with_capacity(shape.len() as usize);
        let (cy, cx) = (shape.h as f32 / 2.0, shape.w as f32 / 2.0);
        let radius = shape.h.min(shape.w) as f32 / 3.0;
        for c in 0..shape.c {
            for y in 0..shape.h {
                for x in 0..shape.w {
                    let dy = y as f32 - cy;
                    let dx = x as f32 - cx;
                    let inside = (dy * dy + dx * dx).sqrt() < radius;
                    values.push(if inside { 0.9 - c as f32 * 0.2 } else { 0.05 });
                }
            }
        }
        values
    }

    /// Run `plan` on two unlike inputs and check that both give a usable mask.
    ///
    /// # What is and is not asserted
    ///
    /// Finiteness and the `0..1` range are the cheap part, and they do catch the
    /// failure these nets are most prone to: fp16 saturating somewhere down a hundred
    /// layers and coming back as an infinity or a NaN.
    ///
    /// Flatness deliberately is **not** asserted. A synthetic image contains no
    /// subject, so a near-uniform mask is the honest answer and demanding variance
    /// would only be demanding that the net hallucinate. What is asserted instead is
    /// that the mask *depends on its input*: a forward pass whose weights were
    /// misindexed into a constant, whose activations had all died, or whose arena
    /// aliased itself would answer the same thing for both of these, and that is a
    /// property a real photograph is not needed to check.
    ///
    /// The distribution is printed rather than asserted, for the same reason
    /// `tests/assets.rs` prints its memory figures: the numbers are what a reviewer
    /// wants to see, and pinning them would pin this runtime's fp16 rounding.
    fn assert_usable_mask(name: &str, plan: &Plan, weights: &[u8]) {
        let shape = plan.input().expect("one input").shape;
        let inputs = [("ramp", ramp(shape)), ("blob", blob(shape))];
        let mut masks = Vec::new();
        for (label, input) in &inputs {
            let mask = run(plan, weights, input).unwrap_or_else(|e| panic!("{name}/{label}: {e}"));
            assert_eq!(mask.len(), plan.output().expect("one output").shape.len() as usize);
            for (i, &value) in mask.iter().enumerate() {
                assert!(
                    value.is_finite() && (0.0..=1.0).contains(&value),
                    "{name}/{label} pixel {i} is {value}",
                );
            }
            let low = mask.iter().fold(f32::MAX, |a, &b| a.min(b));
            let high = mask.iter().fold(f32::MIN, |a, &b| a.max(b));
            let mean = mask.iter().sum::<f32>() / mask.len() as f32;
            println!("{name}/{label}: min {low:.4} mean {mean:.4} max {high:.4}");
            masks.push(mask);
        }

        let (first, second) = match (masks.first(), masks.get(1)) {
            (Some(a), Some(b)) => (a, b),
            _ => panic!("{name}: two runs"),
        };
        // The largest per-pixel change, not the mean one. Both of these nets answer a
        // *question* about the image — "is this pixel a person", "is it the salient
        // object" — so a correct mask is zero across almost all of a synthetic frame
        // and any mean is dominated by that zero. The maximum is what distinguishes a
        // net that responded somewhere from one that cannot respond at all.
        let response = first
            .iter()
            .zip(second)
            .map(|(a, b)| (a - b).abs())
            .fold(0.0f32, f32::max);
        println!("{name}: peak response to the input {response:.4}");
        assert!(response > 0.01, "{name} answers {response} regardless of its input");
    }

    /// `cargo test --release -p modelrunner --lib -- --ignored --nocapture`, and expect
    /// about a minute for the pair.
    ///
    /// Ignored by default because these are the real graphs at their real sizes —
    /// roughly 0.5 and 2.2 GMAC — which a debug build does not get through quickly.
    /// The per-op fixtures above are what run on every commit; this is the check that
    /// the whole shipped forward pass, against the shipped weights, holds together.
    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_selfie_net_produces_a_usable_mask() {
        let Some(bytes) = asset("camera/src/main/assets/selfie_segmentation.vkml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::SELFIE)
            .expect("the shipped selfie asset parses");
        let plan = selfie::build(&weights).expect("selfie builds");
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(1, selfie::SIZE, selfie::SIZE)
        );
        assert_usable_mask("selfie", &plan, weights.data());
    }

    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_u2netp_net_produces_a_usable_mask() {
        let Some(bytes) = asset("photos/src/main/assets/u2netp.vkml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::U2NETP)
            .expect("the shipped u2netp asset parses");
        let plan = u2netp::build(&weights).expect("u2netp builds");
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(1, u2netp::SIZE, u2netp::SIZE)
        );
        assert_usable_mask("u2netp", &plan, weights.data());
    }

    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_scrfd_net_produces_usable_detection_maps() {
        let Some(bytes) = asset("photos/src/main/assets/scrfd_500m.vkml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::SCRFD)
            .expect("the shipped scrfd asset parses");
        // 128x128 rather than the 640 it runs at on device: the plan lowers at any
        // multiple of 32, and a 1/25th-area run exercises every one of the 60 layers,
        // both nearest upsamples and all nine heads for a twenty-fifth of the arithmetic.
        let plan = scrfd::build(&weights, 128, 128).expect("scrfd builds at 128x128");
        let shape = plan.input().expect("one input").shape;
        let got = run_multi(&plan, weights.data(), &[&blob(shape)]).expect("scrfd runs");

        assert_eq!(got.len(), 9);
        for (group, stride) in got.chunks_exact(3).zip(scrfd::STRIDES) {
            let [score, bbox, keypoints] = match group {
                [a, b, c] => [a, b, c],
                other => panic!("stride {stride}: {} maps", other.len()),
            };
            // Scores come through a sigmoid, so they are probabilities. Boxes and
            // keypoints are raw distances in stride units and only have to be finite.
            for (i, &value) in score.iter().enumerate() {
                assert!((0.0..=1.0).contains(&value), "stride {stride} score {i} is {value}");
            }
            for (name, map) in [("box", bbox), ("keypoint", keypoints)] {
                for (i, &value) in map.iter().enumerate() {
                    assert!(value.is_finite(), "stride {stride} {name} {i} is {value}");
                }
            }
            let peak = score.iter().fold(0.0f32, |a, &b| a.max(b));
            let spread = bbox.iter().fold(0.0f32, |a, &b| a.max(b.abs()));
            println!("scrfd/{stride}: peak score {peak:.4} largest box distance {spread:.3}");
            // A box map that is identically zero means the regression branch never ran,
            // which is what reading the wrong tensor index for a head produces.
            assert!(spread > 1e-3, "stride {stride} predicts no box extent at all");
        }
    }

    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_mobilefacenet_net_embeds_two_faces_differently() {
        let Some(bytes) = asset("photos/src/main/assets/w600k_mbf.vkml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::MOBILEFACENET)
            .expect("the shipped mobilefacenet asset parses");
        let plan = mobilefacenet::build(&weights).expect("mobilefacenet builds");
        let shape = plan.input().expect("one input").shape;
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(mobilefacenet::EMBEDDING, 1, 1)
        );

        let first = run(&plan, weights.data(), &ramp(shape)).expect("embeds a ramp");
        let second = run(&plan, weights.data(), &blob(shape)).expect("embeds a blob");
        for (label, embedding) in [("ramp", &first), ("blob", &second)] {
            assert_eq!(embedding.len(), mobilefacenet::EMBEDDING as usize);
            for (i, &value) in embedding.iter().enumerate() {
                assert!(value.is_finite(), "{label} component {i} is {value}");
            }
            let norm = embedding.iter().map(|v| v * v).sum::<f32>().sqrt();
            println!("mobilefacenet/{label}: L2 norm {norm:.4}");
            // Unnormalised on purpose — `FaceRecognizer` L2-normalises in Kotlin — so
            // this only has to be a vector rather than the origin. A collapsed net,
            // which is what a wrong PReLU slope index produces, lands at zero.
            assert!(norm > 1e-2, "{label} embeds to the origin");
        }

        // Cosine similarity, which is exactly what the clustering compares. Two inputs
        // this unlike must not map to the same direction; a net whose final `Gemm` was
        // reshaped wrongly tends to return near-identical vectors for everything.
        let dot: f32 = first.iter().zip(&second).map(|(a, b)| a * b).sum();
        let magnitude = |v: &[f32]| v.iter().map(|x| x * x).sum::<f32>().sqrt();
        let cosine = dot / (magnitude(&first) * magnitude(&second));
        println!("mobilefacenet: cosine between the two {cosine:.4}");
        assert!(cosine < 0.99, "two unlike inputs embed to the same direction");
    }

    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_ppocr_det_net_produces_a_usable_probability_map() {
        let Some(bytes) = asset("library/ocr/src/main/assets/ppocr_det.vkml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::PPOCR_DET)
            .expect("the shipped ppocr_det asset parses");
        // 128x128 rather than 960: the plan lowers at any multiple of 32, and this
        // exercises all 62 convolutions, both squeeze-excite kinds, the one surviving
        // affine, all six nearest upsamples and both transposed convolutions for a
        // fraction of the arithmetic.
        let plan = ppocr_det::build(&weights, 128, 128).expect("builds at 128x128");
        assert_eq!(plan.output().expect("one output").shape, Shape::new(1, 128, 128));
        // The output is a per-pixel text probability at full resolution, so it is a mask
        // in exactly the sense `assert_usable_mask` means — including that a synthetic
        // image contains no text, which makes a near-empty map the honest answer.
        assert_usable_mask("ppocr_det", &plan, weights.data());
    }
}
