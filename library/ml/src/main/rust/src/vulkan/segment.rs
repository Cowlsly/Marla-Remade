//! Splitting the weights buffer into descriptor-sized pieces.
//!
//! # The problem
//!
//! Every shader reads the whole weights file through one descriptor whose `range` is the file's
//! length, and `maxStorageBufferRange` is only guaranteed to be **128 MiB**. SMaLL-100's weights
//! are 318 MiB. So on a device reporting the minimum, one descriptor cannot describe the file at
//! all: the binding would be invalid, and validation would say so while a release build read
//! whatever the driver felt like past the range.
//!
//! It is not new with SMaLL-100 either. Supertonic's fp16 sampler shipped at 121.7 MiB, 95% of the
//! guarantee, so this was one model away from breaking already.
//!
//! # One buffer, several descriptors
//!
//! The file stays a **single** `VkBuffer` and a single allocation — nothing about the upload,
//! [`crate::weights::Blob::read_at`] or `Net::CHUNK_BYTES` changes. What is split is the
//! *descriptor*: one set per segment, each pointing at the same buffer with its own
//! `(offset, range)`, and `Net::record` binds the set an op needs before dispatching it.
//!
//! Per-segment `VkBuffer`s would work too, and would sidestep `minStorageBufferOffsetAlignment`
//! entirely, at the price of N allocations, an upload that has to know which buffer each byte
//! belongs to, and N sets of aliasing rules. The alignment is not actually a problem: it is at
//! most 256, a segment base is a multiple of the window stride which is rounded down to it, and
//! the overlap that creates is read-only in every segment.
//!
//! # Overlapping windows, so the split does not depend on the plan
//!
//! The obvious segmentation is greedy over the ops: fill a descriptor until the next op's tensors
//! do not fit, then start another. It gives the fewest segments, and it is wrong here, because
//! [`super::run::Net::rebuild`] installs a **different plan** over the same weights. SMaLL-100's
//! encoder and decoder passes read different subsets of one file, so a plan-derived segmentation
//! would have to be recomputed on every rebuild, and a recomputation that wanted *more* segments
//! than the descriptor pool was allocated for could only fail.
//!
//! So the segments are fixed windows of `range` bytes at a stride of `range / 4`, covering the
//! file. Any byte span shorter than `range - stride`, which is three quarters of the range, is
//! then **entirely inside** at least one window: a span starting at `from` is inside window
//! `from / stride`, which reaches to `(from / stride) * stride + range`. The largest span any op
//! here has is one of SMaLL-100's two logits halves at 65.9 MB, against 100.7 MB of guaranteed
//! headroom.
//!
//! The cost of the overlap is descriptor sets, not memory: ten of them for a 318 MiB file at the
//! guaranteed range, one for the same file on a device reporting 4 GiB. Since it depends only on
//! the file's length, `rebuild` needs to recompute nothing.
//!
//! # What stays invisible
//!
//! [`crate::weights::Tensor::offset`] stays absolute and file-relative, and so does every offset
//! in a [`Plan`]. Nothing in `nets/` knows a segment exists, which is what keeps
//! `nets::reference` — the only oracle for the shaders — and every parity fixture unchanged.
//! Rebasing happens here, at record time, and is a pure function of the segment and the push.
//!
//! [`Plan`]: crate::nets::Plan

use crate::nets::{Kind, Push};
use crate::weights::Tensor;

use super::context::Limits;

/// The `.maml` tensor alignment, from `scripts/ml/maml_convert.py`.
///
/// A stride is rounded down to a multiple of at least this, so that dividing a segment base by 2
/// for the fp16 view and by 4 for the 32-bit word view are both exact.
/// `minStorageBufferOffsetAlignment` may be *finer* than 16 — it is only required to be a power of
/// two — and 2 would not satisfy the word view.
const ALIGNMENT: u64 = 16;

/// A window count past which the device's reported range is not believable.
///
/// At the spec's guaranteed 128 MiB this allows a 32 GiB weights file, which is two orders of
/// magnitude past anything this runtime loads. Hitting it means `max_storage_buffer_range` came
/// back absurdly small, and thousands of descriptor sets would be a worse failure than a message.
const MAX_SEGMENTS: usize = 1024;

/// One descriptor's worth of the weights buffer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Segment {
    /// Byte offset of the descriptor. A multiple of the device's required alignment.
    pub base: u64,
    /// Bytes the descriptor covers, never more than `max_storage_buffer_range`.
    pub len: u64,
}

/// The weights file as overlapping windows, one descriptor set each.
#[derive(Debug, PartialEq, Eq)]
pub struct Segments {
    segments: Vec<Segment>,
    /// Distance between window bases. Zero when there is only one window.
    stride: u64,
    /// The longest span a window is guaranteed to contain, `range - stride`.
    reach: u64,
}

impl Segments {
    /// Window `total` bytes of weights so that every op's tensors land inside one descriptor.
    ///
    /// Depends only on the file's length and the device, never on the plan. See the module docs.
    pub fn plan(total: u64, limits: &Limits) -> Result<Segments, String> {
        if total > limits.max_memory_allocation_size {
            return Err(format!(
                "{total} bytes of weights against a maxMemoryAllocationSize of {}. The spec \
                 guarantees at least 1 GiB, so this device is unusually constrained and the model \
                 is refused rather than loaded wrongly.",
                limits.max_memory_allocation_size
            ));
        }
        let granularity = limits.min_storage_buffer_offset_alignment.max(ALIGNMENT);
        let range = limits.max_storage_buffer_range;
        if range < granularity {
            return Err(format!(
                "maxStorageBufferRange {range} is below minStorageBufferOffsetAlignment \
                 {granularity}"
            ));
        }
        if total <= range {
            // The overwhelmingly common case, and the only one before SMaLL-100: one descriptor
            // over the whole file, `base` zero, so `rebase` is the identity and `record` emits
            // exactly the commands it always did.
            return Ok(Segments { segments: vec![Segment { base: 0, len: total }], stride: 0, reach: range });
        }
        // A quarter of the range, rounded down to something both views can address.
        let stride = ((range / 4) / granularity) * granularity;
        if stride == 0 {
            return Err(format!("maxStorageBufferRange {range} is too small to window"));
        }
        let count = usize::try_from(total.div_ceil(stride)).map_err(|_| "too many segments")?;
        if count > MAX_SEGMENTS {
            return Err(format!(
                "{total} bytes of weights need {count} descriptor windows at a \
                 maxStorageBufferRange of {range}, past the {MAX_SEGMENTS} this allows"
            ));
        }
        let segments = (0..count)
            .map(|index| {
                let base = index as u64 * stride;
                Segment { base, len: range.min(total - base) }
            })
            .collect();
        Ok(Segments { segments, stride, reach: range - stride })
    }

    /// The segments, in the order their descriptor sets are allocated.
    pub fn all(&self) -> &[Segment] {
        &self.segments
    }

    /// The window op `step` should be dispatched through, or `None` if it reads no weights.
    ///
    /// An op that reads none keeps whatever set is already bound, which is always a valid one.
    pub fn for_op(
        &self,
        step: usize,
        kind: Kind,
        push: &Push,
        tensors: &[Tensor],
    ) -> Result<Option<usize>, String> {
        let Some((from, to)) = self.span(step, kind, push, tensors)? else {
            return Ok(None);
        };
        if self.stride == 0 {
            return Ok(Some(0));
        }
        let index = usize::try_from(from / self.stride).map_err(|_| "a segment index overflowed")?;
        let Some(segment) = self.segments.get(index) else {
            return Err(format!("step {step} reads at byte {from}, past the last segment"));
        };
        if to > segment.base + segment.len {
            return Err(format!(
                "step {step} ({kind:?}) reads bytes {from}..{to} of the weights, a span of {} \
                 against the {} one descriptor window is guaranteed to hold. Split the tensor in \
                 the converter.",
                to - from,
                self.reach
            ));
        }
        Ok(Some(index))
    }

    /// `push` with its three weights offsets made relative to segment `index`.
    ///
    /// The units differ per field and per kind — `weight` is a 32-bit word index for the int8
    /// kinds and an fp16 element index otherwise — which is why the base is divided rather than
    /// subtracted from one normalised offset. A base is a multiple of `ALIGNMENT`, so both
    /// divisions are exact.
    ///
    /// The kinds named below must stay in step with the word-unit arm of [`Kind::weight_reads`],
    /// which is the only place that decides what a `weight` offset counts in.
    pub fn rebase(&self, index: usize, kind: Kind, push: &Push) -> Push {
        let base = self.segments.get(index).map_or(0, |segment| segment.base);
        if base == 0 {
            return *push;
        }
        let elems = (base / 2) as u32;
        let words = (base / 4) as u32;
        let mut out = *push;
        for read in kind.weight_reads(push) {
            match read.field {
                "weight"
                    if matches!(
                        kind,
                        Kind::ConvInt8 | Kind::ConvPointInt8 | Kind::ConvVecInt8
                    ) =>
                {
                    out.weight = push.weight.saturating_sub(words);
                }
                "weight" => out.weight = push.weight.saturating_sub(elems),
                "bias" => out.bias = push.bias.saturating_sub(elems),
                "act_weight" => out.act_weight = push.act_weight.saturating_sub(elems),
                _ => {}
            }
        }
        out
    }

    /// The `[from, to)` byte span of the weights one op reads, or `None` if it reads none.
    ///
    /// Each offset is resolved against the tensor table rather than reconstructed from the push's
    /// shape fields, so the extent is exact for every kind without this knowing what any of them
    /// compute. An offset landing in no tensor is a bug in the net module — a push pointing
    /// somewhere the file does not describe — and is reported rather than read on the device.
    fn span(
        &self,
        step: usize,
        kind: Kind,
        push: &Push,
        tensors: &[Tensor],
    ) -> Result<Option<(u64, u64)>, String> {
        let reads = kind.weight_reads(push);
        if reads.is_empty() {
            return Ok(None);
        }
        let mut from = u64::MAX;
        let mut to = 0u64;
        for read in reads {
            let end = tensor_end(read.at, tensors).ok_or_else(|| {
                format!(
                    "step {step} ({kind:?}) reads {} at byte {}, which is inside none of the {} \
                     tensors the file describes",
                    read.field,
                    read.at,
                    tensors.len()
                )
            })?;
            from = from.min(read.at);
            to = to.max(end);
        }
        Ok(Some((from, to)))
    }
}

/// The end of the tensor containing byte `at`.
///
/// A linear scan, run once per op per recording over a table of at most a few hundred entries.
fn tensor_end(at: u64, tensors: &[Tensor]) -> Option<u64> {
    tensors.iter().find_map(|tensor| {
        let start = u64::from(tensor.offset);
        let bytes = u64::from(tensor.len) * if tensor.int8 { 1 } else { 2 };
        (at >= start && at < start + bytes.max(1)).then_some(start + bytes)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The spec's guaranteed floor for the two limits that matter, plus 1 GiB of allocation.
    fn floor() -> Limits {
        Limits {
            max_storage_buffer_range: 128 << 20,
            min_storage_buffer_offset_alignment: 256,
            max_memory_allocation_size: 1 << 30,
        }
    }

    #[test]
    fn a_file_inside_the_range_is_one_window() {
        let segments = Segments::plan(121_700_000, &floor()).unwrap();
        assert_eq!(segments.all(), [Segment { base: 0, len: 121_700_000 }]);
        // Base zero, so `rebase` must be the identity: the common case records what it always did.
        let push = Push { weight: 7, bias: 9, act_weight: 11, ..Push::default() };
        assert_eq!(segments.rebase(0, Kind::Conv, &push), push);
    }

    #[test]
    fn a_larger_file_is_covered_by_overlapping_windows() {
        let limits = floor();
        let total = 333_783_488u64;
        let segments = Segments::plan(total, &limits).unwrap();
        let stride = (128 << 20) / 4;
        assert_eq!(segments.all().len(), total.div_ceil(stride) as usize);
        for window in segments.all() {
            assert_eq!(window.base % 256, 0, "a base must satisfy the device's alignment");
            assert_eq!(window.base % ALIGNMENT, 0, "and be addressable as fp16 and as words");
            assert!(window.len <= limits.max_storage_buffer_range, "{window:?}");
            assert!(window.base + window.len <= total, "{window:?} runs past the file");
        }
        // The whole file is covered: consecutive bases are one stride apart and the last window
        // reaches the end.
        let last = segments.all().last().unwrap();
        assert_eq!(last.base + last.len, total);
    }

    #[test]
    fn every_span_short_enough_lands_inside_one_window() {
        let limits = floor();
        let total = 333_783_488u64;
        let segments = Segments::plan(total, &limits).unwrap();
        // One int8 tensor the size of SMaLL-100's larger logits half, walked across the file. This
        // is the span the three-quarter reach exists for.
        let biggest = 65_593_344u64;
        let mut at = 0u64;
        while at + biggest <= total {
            let index = (at / segments.stride) as usize;
            let window = segments.all()[index];
            assert!(
                at >= window.base && at + biggest <= window.base + window.len,
                "{at}..{} is not inside {window:?}",
                at + biggest
            );
            at += 4_096_301;
        }
    }

    #[test]
    fn a_split_file_rebases_both_views_exactly() {
        let segments = Segments::plan(333_783_488, &floor()).unwrap();
        let window = segments.all()[3];
        assert!(window.base > 0);
        // An fp16 kernel: `weight` and `bias` are element indices.
        let fp16 = Push {
            weight: (window.base / 2) as u32 + 10,
            bias: (window.base / 2) as u32 + 20,
            ..Push::default()
        };
        let out = segments.rebase(3, Kind::Conv, &fp16);
        assert_eq!((out.weight, out.bias), (10, 20));
        // Every int8 kind: `weight` is a word index, `bias` and the scale are element indices.
        // Driven off a list rather than one kind because the divisor used to be picked by a
        // `matches!` naming two of the three, which rebased `ConvVecInt8` — the op a decode step
        // is almost entirely made of — by half as much as it needed.
        for kind in [Kind::ConvInt8, Kind::ConvPointInt8, Kind::ConvVecInt8] {
            let int8 = Push {
                weight: (window.base / 4) as u32 + 5,
                bias: (window.base / 2) as u32 + 6,
                act_weight: (window.base / 2) as u32 + 7,
                ..Push::default()
            };
            let out = segments.rebase(3, kind, &int8);
            assert_eq!((out.weight, out.bias, out.act_weight), (5, 6, 7), "{kind:?}");
        }
    }

    #[test]
    fn every_int8_kind_rebases_its_kernel_in_words() {
        // The regression proper. `weight_reads` resolves a `weight` offset to a byte address, so
        // rebasing it and converting back must land on the byte the op started from — whatever
        // unit the kind counts in. Asserting through bytes means this cannot pass by agreeing
        // with `rebase`'s own arithmetic.
        let segments = Segments::plan(333_783_488, &floor()).unwrap();
        let window = segments.all()[3];
        assert!(window.base > 0, "the file must actually be windowed");
        for kind in [Kind::ConvInt8, Kind::ConvPointInt8, Kind::ConvVecInt8] {
            let push = Push {
                weight: (window.base / 4) as u32 + 5,
                bias: (window.base / 2) as u32 + 6,
                act_weight: (window.base / 2) as u32 + 7,
                ..Push::default()
            };
            let absolute = kind.weight_reads(&push);
            let out = segments.rebase(3, kind, &push);
            let mut checked = 0;
            for read in absolute {
                let rebased = match read.field {
                    "weight" => u64::from(out.weight) * 4,
                    "bias" => u64::from(out.bias) * 2,
                    "act_weight" => u64::from(out.act_weight) * 2,
                    _ => continue,
                };
                checked += 1;
                assert_eq!(
                    rebased + window.base,
                    read.at,
                    "{kind:?} rebased {} off its byte address",
                    read.field
                );
            }
            assert_eq!(checked, 3, "{kind:?} reads a kernel, a bias and a scale");
        }
    }

    #[test]
    fn an_op_reading_no_weights_needs_no_window() {
        let segments = Segments::plan(333_783_488, &floor()).unwrap();
        let push = Push { in0: 4, in1: 8, ..Push::default() };
        assert_eq!(segments.for_op(0, Kind::Add, &push, &[]).unwrap(), None);
        // And rebasing one changes nothing, because it reads nothing.
        assert_eq!(segments.rebase(3, Kind::Add, &push), push);
    }

    #[test]
    fn an_offset_in_no_tensor_is_refused() {
        let segments = Segments::plan(1024, &floor()).unwrap();
        let tensors = [Tensor { rank: 1, dims: [8, 0, 0, 0], offset: 0, len: 8, int8: false }];
        let push = Push { weight: 0, bias: 400, ..Push::default() };
        let error = segments.for_op(2, Kind::Conv, &push, &tensors).unwrap_err();
        assert!(error.contains("step 2"), "{error}");
        assert!(error.contains("bias"), "{error}");
    }

    #[test]
    fn a_span_longer_than_the_reach_is_refused() {
        let limits = Limits { max_storage_buffer_range: 4096, ..floor() };
        let segments = Segments::plan(16_384, &limits).unwrap();
        // The reach is `range - stride` = 3072, but a span starting at a multiple of the stride
        // gets the whole range. So this is placed *off* the stride, at 1536: window 1 covers
        // 1024..5120 and the span runs to 5136.
        let tensors =
            [Tensor { rank: 1, dims: [1800, 0, 0, 0], offset: 1536, len: 1800, int8: false }];
        let push = Push { weight: 768, bias: 768, ..Push::default() };
        let error = segments.for_op(0, Kind::Conv, &push, &tensors).unwrap_err();
        assert!(error.contains("Split the tensor in the converter"), "{error}");
    }

    #[test]
    fn an_int8_kernel_is_never_separated_from_its_scale_and_bias() {
        // A layer laid out the way `maml_convert.build` writes one: an int8 kernel, then its fp16
        // per-output-channel scale, then its fp16 bias, each starting on a 16-byte boundary. This
        // is the group a boundary must never fall inside, and the windows are forced small enough
        // that the layer does not start in the first one.
        let limits = Limits { max_storage_buffer_range: 1 << 20, ..floor() };
        let out = 512u32;
        let inputs = 256u32;
        let kernel_at = 3_145_728u64;
        let kernel_bytes = u64::from(out) * u64::from(inputs);
        let scale_at = kernel_at + kernel_bytes;
        let bias_at = scale_at + u64::from(out) * 2;
        let total = bias_at + u64::from(out) * 2;
        let tensors = [
            Tensor {
                rank: 4,
                dims: [out, inputs, 1, 1],
                offset: kernel_at as u32,
                len: out * inputs,
                int8: true,
            },
            Tensor { rank: 1, dims: [out, 0, 0, 0], offset: scale_at as u32, len: out, int8: false },
            Tensor { rank: 1, dims: [out, 0, 0, 0], offset: bias_at as u32, len: out, int8: false },
        ];
        let segments = Segments::plan(total, &limits).unwrap();
        assert!(segments.all().len() > 1, "the limit must actually force windowing");

        let push = Push {
            weight: (kernel_at / 4) as u32,
            bias: (bias_at / 2) as u32,
            act_weight: (scale_at / 2) as u32,
            ..Push::default()
        };
        let index = segments
            .for_op(0, Kind::ConvPointInt8, &push, &tensors)
            .unwrap()
            .expect("an int8 convolution reads weights");
        let window = segments.all()[index];
        // All three inside the one window, which is the invariant.
        assert!(kernel_at >= window.base, "the kernel starts before its window");
        assert!(total <= window.base + window.len, "the bias ends past its window");

        // And the rebased offsets address the same three tensors from the window's base.
        let out_push = segments.rebase(index, Kind::ConvPointInt8, &push);
        assert_eq!(u64::from(out_push.weight) * 4 + window.base, kernel_at);
        assert_eq!(u64::from(out_push.act_weight) * 2 + window.base, scale_at);
        assert_eq!(u64::from(out_push.bias) * 2 + window.base, bias_at);
    }

    #[test]
    fn a_prelu_slope_is_part_of_the_span_it_is_read_with() {
        // `prelu` in `common.glsl` reads `act_weight` for *any* kind carrying the activation, not
        // only the int8 kinds, so the slope has to be inside the same window as the kernel.
        let tensors = [
            Tensor { rank: 4, dims: [4, 1, 1, 1], offset: 0, len: 4, int8: false },
            Tensor { rank: 1, dims: [4, 0, 0, 0], offset: 16, len: 4, int8: false },
            Tensor { rank: 1, dims: [4, 0, 0, 0], offset: 32, len: 4, int8: false },
        ];
        let segments = Segments::plan(40, &floor()).unwrap();
        let push = Push { weight: 0, bias: 8, act_weight: 16, act: 4, ..Push::default() };
        let reads = Kind::Conv.weight_reads(&push);
        assert_eq!(reads.len(), 3, "kernel, bias and slope: {reads:?}");
        assert_eq!(segments.span(0, Kind::Conv, &push, &tensors).unwrap(), Some((0, 40)));
        // Without PRelu the slope is not read, so it is not part of the span.
        let plain = Push { act: 1, ..push };
        assert_eq!(Kind::Conv.weight_reads(&plain).len(), 2);
    }

    #[test]
    fn a_file_past_the_allocation_cap_is_refused_with_the_reason() {
        let limits = Limits { max_memory_allocation_size: 1 << 20, ..floor() };
        let error = Segments::plan(2 << 20, &limits).unwrap_err();
        assert!(error.contains("maxMemoryAllocationSize"), "{error}");
    }
}
