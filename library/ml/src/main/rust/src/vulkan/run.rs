//! Recording a [`Plan`] into one command buffer, and running it.
//!
//! # Recorded once, submitted per inference
//!
//! The whole network — 350 dispatches for U^2-Netp, 150 for the selfie net, with a barrier
//! between each — is recorded into a single primary command buffer at [`Net::new`] and
//! never re-recorded. An inference is then exactly:
//!
//! 1. preprocess the bitmap into the staging buffer on the CPU,
//! 2. one `vkQueueSubmit`,
//! 3. one `vkWaitForFences`,
//! 4. read the mask back out of the staging buffer.
//!
//! Nothing is allocated, no descriptor is written and no command is recorded per frame.
//! That is what makes this viable on `:camera`'s ~15 fps preview path, where the recording
//! cost — hundreds of `vkCmd` calls — would otherwise be paid 15 times a second while the
//! UI is also using the GPU.
//!
//! The input copy is *inside* the recorded buffer, so the host writes only to the staging
//! buffer and the GPU does the transfer into device-local memory itself.
//!
//! # Barriers
//!
//! One full barrier between every op, covering both the compute and transfer stages in
//! each direction. Every layer reads what the one before it wrote, into the same
//! `VkBuffer`, so there is no dependency to skip: the arena is a single buffer and a
//! finer-grained barrier would have to name byte ranges that the ops already overlap by
//! design. About 350 barriers per inference is the cost of that simplicity, and it is the
//! first thing to look at if U^2-Netp is slower than it should be.

use std::sync::Arc;

use ash::vk;

use crate::nets::{Op, Plan};
use crate::preprocess::{self, Normalise};
use crate::weights::Blob;

use super::buffers::Buffer;
use super::context::Context;
use super::pipeline::{Pipelines, MAX_WORKGROUPS_PER_DIM, WORKGROUP};
use super::segment::Segments;

/// Values a recorded command buffer reads from memory rather than from its own recording.
///
/// # Why this exists
///
/// A decode step attends over every position decoded so far, so the loop bound grows by one each
/// token. That bound lived in the [`Plan`] — in a push constant and in the dispatch's group count
/// — and both are *baked into the recording*, so growing it meant re-recording: a
/// `device_wait_idle` under the queue lock, a fresh plan, and every dispatch emitted again, per
/// token. See [`super::reshape::Reshaped`].
///
/// A storage buffer is the one thing a recorded command buffer reads late. Putting the bound here
/// lets the same recording serve every step, and a step becomes a memcpy of a few bytes.
///
/// # Layout
///
/// `repr(C)` and all `u32`, matching the `Params` block in `shaders/common.glsl` field for field.
/// `std430` lays a struct of `uint`s out with no padding, so the two agree without alignment
/// rules having to be restated on either side.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct StepParams {
    /// Cache positions already written, and so the index the current step writes at.
    ///
    /// A cached-attention op attends over `prefix + 1` keys: the prefix plus this step's own.
    pub prefix: u32,
    /// First position a sliding-window attention may attend to.
    ///
    /// Zero means attend from the start, which is every model the runtime has today. Gemma 3n
    /// alternates local and global layers, which is what this is for.
    pub window_start: u32,
}

impl StepParams {
    /// Bytes to allocate for the params buffer.
    ///
    /// Rounded well past `size_of::<StepParams>()` so that adding a field, or appending the
    /// `VkDispatchIndirectCommand`s an indirect dispatch reads, does not change the allocation
    /// and cannot silently overrun a buffer sized to an older layout.
    pub const BYTES: vk::DeviceSize = 256;

    /// The struct as the bytes the buffer holds.
    fn as_bytes(&self) -> &[u8] {
        // SAFETY: `repr(C)` over two `u32`s has no padding and no invalid bit patterns, so every
        // byte of it is initialised and readable as `u8`. The slice borrows `self`.
        unsafe {
            std::slice::from_raw_parts(
                std::ptr::from_ref(self).cast::<u8>(),
                std::mem::size_of::<StepParams>(),
            )
        }
    }
}

/// A compiled, recorded network ready to run.
pub struct Net {
    context: Arc<Context>,
    plan: Plan,
    normalise: Normalise,
    weights: Buffer,
    arena: Buffer,
    staging: Buffer,
    /// Values the shaders read that change per step without a re-record.
    ///
    /// See [`StepParams`] and [`Buffer::step_params`].
    params: Buffer,
    pipelines: Pipelines,
    /// Which descriptor set each op's weights are visible through.
    ///
    /// Almost always one window over the whole file. See [`super::segment`].
    segments: Segments,
    /// The `.maml` tensor table, for [`Segments::for_op`].
    tensors: Vec<crate::weights::Tensor>,
    /// This net's own command pool, not the context's.
    ///
    /// A `VkCommandPool` is externally synchronised across *recording* as well as across
    /// allocation — every `vkBeginCommandBuffer` and `vkCmd*` counts — so a pool shared between
    /// nets would have to be locked for the whole of [`Net::record`]. One pool each removes the
    /// question: `infer` takes `&mut self`, so a net is already exclusive to one thread at a
    /// time, and nothing else can reach this pool.
    command_pool: vk::CommandPool,
    command_buffer: vk::CommandBuffer,
    fence: vk::Fence,
    /// Set when a submission was left pending, or a recording left part-written, after which
    /// this net is unusable.
    ///
    /// A `wait_for_fences` timeout returns an error while the submission is **still in
    /// flight**. Nothing can cancel it, so resetting the fence, resubmitting the command
    /// buffer or rewriting the staging buffer would all be illegal — and since the
    /// five-second timeout exists precisely so a hung GPU does not block forever, this is
    /// an anticipated path rather than a theoretical one. Once poisoned, every later
    /// `infer` fails immediately without touching Vulkan, and only `Drop` — which waits
    /// for the device to go idle first — cleans up. [`Net::rebuild`] sets it for the other
    /// reason: a command buffer it failed to finish recording must never be submitted.
    poisoned: bool,
    /// Scratch for the fp16 input and the fp16 output, so an inference allocates nothing.
    input_scratch: Vec<u16>,
    output_scratch: Vec<u16>,
}

impl Net {
    /// Allocate, upload the weights and record the plan.
    ///
    /// `weights` is read for its data section but not retained: once the blob is in
    /// device-local memory the host copy is dropped, which for U^2-Netp gives back 2.1 MB
    /// of heap.
    ///
    /// A [`Blob`] rather than a [`crate::weights::Weights`] so that a bundled net can stream
    /// straight out of the APK. Nothing here reads the tensor table — the [`Plan`] already carries
    /// every resolved offset — which is what makes the two interchangeable.
    pub fn new(
        context: Arc<Context>,
        plan: Plan,
        weights: &dyn Blob,
        normalise: Normalise,
    ) -> Result<Net, String> {
        let arena_bytes = (plan.arena_elems as vk::DeviceSize) * 2;
        // Vulkan forbids a zero-sized buffer, and the descriptor set needs a real one bound to
        // the weights binding whether or not any shader reads it. A plan can legitimately read
        // no weights at all — a purely elementwise one does — so this floors the allocation
        // rather than refusing the plan.
        let weights_bytes = (weights.data_len() as vk::DeviceSize).max(2);
        let input_elems = binding_elems(&plan.inputs);
        let output_elems = binding_elems(&plan.outputs);
        // One staging buffer for both directions: the inputs and the outputs are never in
        // flight at the same time, because an inference is a single blocking submit. Each
        // side packs its bindings end to end from offset 0, in declaration order.
        let staging_bytes = (input_elems.max(output_elems) as vk::DeviceSize) * 2;

        // Before allocating anything: a file this device's descriptors cannot describe must fail
        // here, with the limit in the message, rather than at the first dispatch that reads past
        // a range. Windowing depends only on the length, so `rebuild` never redoes it.
        let segments = Segments::plan(weights_bytes, &context.limits)?;
        // A few kilobytes, kept because `record` needs each tensor's extent to know which window
        // an op fits in, and `rebuild` installs plans this net was not constructed with.
        let tensors = weights.tensors().to_vec();

        let weights_buffer = Buffer::device_local(&context, weights_bytes)?;
        let arena = Buffer::device_local(&context, arena_bytes)?;
        let staging = Buffer::staging(&context, staging_bytes)?;
        let params = Buffer::step_params(&context, StepParams::BYTES)?;

        let pipelines = Pipelines::new(
            &context,
            arena.buffer,
            arena_bytes,
            weights_buffer.buffer,
            params.buffer,
            segments.all(),
        )?;

        // `RESET_COMMAND_BUFFER`, so a net can re-record without reallocating.
        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(context.queue_family_index)
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        // SAFETY: a plain object creation on a device this net holds an `Arc` to. It is stored in
        // the struct below before anything fallible runs, so `Drop` destroys it on every path.
        let command_pool = unsafe { context.device.create_command_pool(&pool_info, None) }
            .map_err(|e| format!("create_command_pool {e:?}"))?;

        let mut net = Net {
            context,
            plan,
            normalise,
            weights: weights_buffer,
            arena,
            staging,
            params,
            pipelines,
            segments,
            tensors,
            command_pool,
            command_buffer: vk::CommandBuffer::null(),
            fence: vk::Fence::null(),
            poisoned: false,
            input_scratch: vec![0u16; input_elems],
            output_scratch: vec![0u16; output_elems],
        };

        net.upload_weights(weights)?;
        net.command_buffer = net.allocate_command_buffer()?;
        net.fence = net.create_fence()?;
        // Written before the first record so a shader reading it never sees uninitialised
        // memory, even on a plan that never calls `set_params`.
        net.set_params(StepParams::default())?;
        net.record()?;
        Ok(net)
    }

    /// Install the values the next submit reads, without touching the recording.
    ///
    /// The buffer is host-coherent, so the write is visible to a later submit with no flush and
    /// no barrier. It must not be called while a submit is in flight — `infer` blocks on its
    /// fence before returning, so holding `&mut self` is enough to guarantee that.
    pub fn set_params(&mut self, params: StepParams) -> Result<(), String> {
        self.params.write(params.as_bytes())
    }

    /// Re-record this net at `plan`'s shapes, keeping the weights already uploaded.
    ///
    /// Supertonic's nets are shaped by the utterance — the text encoder runs at the character
    /// count, the sampler and the vocoder at the latent length — and a [`Plan`] is one command
    /// buffer recorded at fixed shapes, so a new length needs a new recording. It does not need a
    /// new upload: the weights are the expensive part (198 MB for Supertonic, 605 MB for
    /// SMaLL-100) and they do not depend on the length. So this keeps the weights buffer and
    /// re-uses the arena and the staging buffer too, growing them only when the new shapes need
    /// more than the old ones did — which for a sequence of utterances means the allocation
    /// happens a handful of times rather than once per sentence.
    ///
    /// `plan` must have been built against the same weights as this net's. Nothing here checks
    /// that, because [`crate::nets::Builder::finish`] already refuses a plan that does not read
    /// every tensor in its file, so a plan for a different `.maml` cannot reach here.
    pub fn rebuild(&mut self, plan: Plan) -> Result<(), String> {
        if self.poisoned {
            return Err("this network is unusable after an earlier failure".into());
        }
        let arena_bytes = (plan.arena_elems as vk::DeviceSize) * 2;
        let input_elems = binding_elems(&plan.inputs);
        let output_elems = binding_elems(&plan.outputs);
        let staging_bytes = (input_elems.max(output_elems) as vk::DeviceSize) * 2;

        // Allocate before touching any state. A failure part-way through must leave the net
        // running at its old plan: a descriptor pointing at a new arena while the recorded
        // command buffer still copies the inputs into the old one would be a wrong answer at the
        // right shape, which is far worse than an error.
        let grown_arena = if arena_bytes > self.arena.size {
            Some(Buffer::device_local(&self.context, arena_bytes)?)
        } else {
            None
        };
        let grown_staging = if staging_bytes > self.staging.size {
            Some(Buffer::staging(&self.context, staging_bytes)?)
        } else {
            None
        };

        // SAFETY: a descriptor set may not be rewritten, and a bound buffer may not be freed,
        // while a command buffer using either is pending. `submit` waits on its fence before
        // returning and the check above rejects the one case where it did not, so nothing is in
        // fact pending — but this costs one round trip per length change, not per inference, and
        // it makes that reasoning unnecessary rather than load-bearing.
        unsafe {
            let guard = self.context.lock_queue();
            let idle = self.context.device.device_wait_idle();
            drop(guard);
            idle.map_err(|e| format!("device_wait_idle {e:?}"))?;
        }

        // Past here nothing fails until `record`, so the net cannot be left describing one shape
        // and recording another. Each old `Buffer` frees itself as it is replaced, after the wait
        // above.
        if let Some(arena) = grown_arena {
            self.pipelines.rebind_arena(&self.context.device, arena.buffer, arena.size);
            self.arena = arena;
        }
        if let Some(staging) = grown_staging {
            self.staging = staging;
        }
        self.input_scratch.resize(input_elems, 0);
        self.output_scratch.resize(output_elems, 0);
        self.plan = plan;

        // A `record` that fails leaves the command buffer part-written, and submitting that is
        // not something a later caller may be allowed to do. There is no way back — both of its
        // failure modes are device loss or host OOM — so the net is retired instead.
        if let Err(e) = self.record() {
            self.poisoned = true;
            return Err(e);
        }
        Ok(())
    }

    /// Staging bytes per copy. Large enough that the per-chunk round trip is noise against the
    /// transfer, small enough to be an unremarkable allocation on a low-RAM device.
    ///
    /// `pub(crate)` only so the parity fixture can assert its blob is bigger than one chunk. A
    /// fixture that fits in a single copy exercises none of the `dst_offset` arithmetic.
    pub(crate) const CHUNK_BYTES: u64 = 8 * 1024 * 1024;

    /// Copy the weights blob to device-local memory through the staging buffer, in chunks.
    ///
    /// Its own staging allocation and its own one-shot command buffer, because the permanent
    /// staging buffer is sized for one input and the weights are ten times that. Both are freed
    /// before returning; this happens once per net.
    ///
    /// # Why chunked, and why this small
    ///
    /// A single copy needs a staging buffer the size of the whole blob. For Supertonic's 127 MB
    /// sampler that is 127 MB of `HOST_VISIBLE` memory on top of the 127 MB device-local
    /// destination, at the moment of load — and it defeats the point of streaming the data section
    /// out of the APK, since the peak would be the whole file again just in a different allocation.
    ///
    /// [`Net::CHUNK_BYTES`] instead, one `cmd_copy_buffer` per chunk at the right `dst_offset`.
    /// Each is submitted and waited on before the next is written, because the staging buffer is
    /// reused and overwriting it while a copy is still reading it is a race. That costs one round
    /// trip per chunk — 16 for that sampler — against a transfer that is bandwidth-bound anyway,
    /// and it happens once per net rather than once per inference.
    fn upload_weights(&self, weights: &dyn Blob) -> Result<(), String> {
        let total = weights.data_len();
        if total == 0 {
            return Ok(());
        }
        let chunk = total.min(Self::CHUNK_BYTES);
        let staging = Buffer::staging(&self.context, chunk as vk::DeviceSize)?;
        let mut buffer = vec![0u8; usize::try_from(chunk).map_err(|_| "a chunk overflowed")?];
        let command_buffer = self.allocate_command_buffer()?;
        // SAFETY: the command buffer was just allocated from this device's pool and is
        // freed on every path below; each copy is bounds-checked by the loop, which never asks
        // for more than `chunk` bytes of staging or writes past `total` of the destination.
        unsafe {
            let device = &self.context.device;
            let mut written = 0u64;
            let outcome = loop {
                if written >= total {
                    break Ok(());
                }
                let size = chunk.min(total - written);
                let piece = match buffer.get_mut(..size as usize) {
                    Some(piece) => piece,
                    None => break Err("a chunk is larger than its buffer".to_string()),
                };
                if let Err(e) = weights.read_at(written, piece) {
                    break Err(e);
                }
                if let Err(e) = staging.write(piece) {
                    break Err(e);
                }
                let copy = || -> Result<(), String> {
                    device
                        .begin_command_buffer(
                            command_buffer,
                            &vk::CommandBufferBeginInfo::default()
                                .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
                        )
                        .map_err(|e| format!("begin_command_buffer {e:?}"))?;
                    let region = vk::BufferCopy::default()
                        .dst_offset(written as vk::DeviceSize)
                        .size(size as vk::DeviceSize);
                    device.cmd_copy_buffer(
                        command_buffer,
                        staging.buffer,
                        self.weights.buffer,
                        std::slice::from_ref(&region),
                    );
                    device
                        .end_command_buffer(command_buffer)
                        .map_err(|e| format!("end_command_buffer {e:?}"))?;
                    // Waited on before the next chunk overwrites the staging buffer. The pool was
                    // created with `RESET_COMMAND_BUFFER`, so re-beginning the same buffer is
                    // legal once its submission has completed.
                    self.submit_and_wait(command_buffer)
                };
                if let Err(e) = copy() {
                    break Err(e);
                }
                written += size;
            };
            device.free_command_buffers(self.command_pool, &[command_buffer]);
            // `staging` drops here, after the last fence has been waited on.
            outcome
        }
    }

    fn allocate_command_buffer(&self) -> Result<vk::CommandBuffer, String> {
        let info = vk::CommandBufferAllocateInfo::default()
            .command_pool(self.command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        // SAFETY: an allocation from this net's own pool, which no other thread can reach.
        let buffers = unsafe { self.context.device.allocate_command_buffers(&info) }
            .map_err(|e| format!("allocate_command_buffers {e:?}"))?;
        buffers
            .first()
            .copied()
            .ok_or_else(|| "allocate_command_buffers returned nothing".into())
    }

    fn create_fence(&self) -> Result<vk::Fence, String> {
        // SAFETY: a plain object creation on a device this net holds an `Arc` to.
        unsafe {
            self.context
                .device
                .create_fence(&vk::FenceCreateInfo::default(), None)
                .map_err(|e| format!("create_fence {e:?}"))
        }
    }

    /// Record the whole plan: input copy, every op with a barrier between, output copy.
    fn record(&self) -> Result<(), String> {
        let device = &self.context.device;
        let buffer = self.command_buffer;
        // SAFETY: `buffer` is a primary command buffer from this device's pool, not
        // currently pending, and every handle referenced below outlives it (they are all
        // fields of `self`, dropped after it in `Drop`).
        unsafe {
            device
                .begin_command_buffer(buffer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin_command_buffer {e:?}"))?;

            // The weights were written by `upload_weights`, in a different submission. A
            // fence wait between submissions orders them but is not a memory dependency, so
            // making that TRANSFER_WRITE visible to every SHADER_READ below needs a real
            // barrier. Recorded once at the top rather than folded into `barrier`, because
            // the weights buffer is never written again.
            let weights_visible = vk::BufferMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(self.weights.buffer)
                .offset(0)
                .size(vk::WHOLE_SIZE);
            device.cmd_pipeline_barrier(
                buffer,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[],
                std::slice::from_ref(&weights_visible),
                &[],
            );

            // One copy per input, packed end to end in the staging buffer in declaration
            // order. Both shipping nets have exactly one; SCRFD's nine outputs come back
            // the same way below.
            let mut staged = 0u64;
            for input in &self.plan.inputs {
                let bytes = (input.shape.len() as vk::DeviceSize) * 2;
                let region = vk::BufferCopy::default()
                    .src_offset(staged)
                    .dst_offset((input.at as vk::DeviceSize) * 2)
                    .size(bytes);
                device.cmd_copy_buffer(
                    buffer,
                    self.staging.buffer,
                    self.arena.buffer,
                    std::slice::from_ref(&region),
                );
                staged += bytes;
            }
            self.barrier(buffer);

            for (step, op) in self.plan.ops.iter().enumerate() {
                match *op {
                    Op::Dispatch { kind, push, invocations } => {
                        device.cmd_bind_pipeline(
                            buffer,
                            vk::PipelineBindPoint::COMPUTE,
                            self.pipelines.for_kind(kind),
                        );
                        // The window an op's weights are visible through, and the push rebased
                        // into it. Both are the identity unless the file was larger than one
                        // descriptor's range, so the common case records what it always did.
                        let segment =
                            self.segments.for_op(step, kind, &push, &self.tensors)?.unwrap_or(0);
                        let set = match self.pipelines.descriptor_sets.get(segment) {
                            Some(&set) => set,
                            None => return Err(format!("step {step} wants segment {segment}")),
                        };
                        device.cmd_bind_descriptor_sets(
                            buffer,
                            vk::PipelineBindPoint::COMPUTE,
                            self.pipelines.layout,
                            0,
                            &[set],
                            &[],
                        );
                        let push = self.segments.rebase(segment, kind, &push);
                        device.cmd_push_constants(
                            buffer,
                            self.pipelines.layout,
                            vk::ShaderStageFlags::COMPUTE,
                            0,
                            push_bytes(&push),
                        );
                        // Split across x and y: the widest layer here needs 102,400
                        // workgroups and `maxComputeWorkGroupCount` is only guaranteed to
                        // be 65,535 per dimension. `global_index()` in the shaders
                        // flattens the grid back, and the grid over-covers, which is what
                        // `push.count` is checked against.
                        let groups = invocations.div_ceil(WORKGROUP);
                        device.cmd_dispatch(
                            buffer,
                            groups.min(MAX_WORKGROUPS_PER_DIM),
                            groups.div_ceil(MAX_WORKGROUPS_PER_DIM),
                            1,
                        );
                    }
                    Op::Copy { src, dst, elems } => {
                        // Same buffer for source and destination. The spec allows that as
                        // long as the regions do not overlap, which the arena allocator
                        // guarantees and `nets::u2netp` asserts.
                        let region = vk::BufferCopy::default()
                            .src_offset((src as vk::DeviceSize) * 2)
                            .dst_offset((dst as vk::DeviceSize) * 2)
                            .size((elems as vk::DeviceSize) * 2);
                        device.cmd_copy_buffer(
                            buffer,
                            self.arena.buffer,
                            self.arena.buffer,
                            std::slice::from_ref(&region),
                        );
                    }
                }
                self.barrier(buffer);
            }

            let mut read_back = 0u64;
            for output in &self.plan.outputs {
                let bytes = (output.shape.len() as vk::DeviceSize) * 2;
                let region = vk::BufferCopy::default()
                    .src_offset((output.at as vk::DeviceSize) * 2)
                    .dst_offset(read_back)
                    .size(bytes);
                device.cmd_copy_buffer(
                    buffer,
                    self.arena.buffer,
                    self.staging.buffer,
                    std::slice::from_ref(&region),
                );
                read_back += bytes;
            }

            // Waiting on a fence makes the copy's writes *available*, but the device-to-host
            // domain operation still needs a memory dependency naming HOST_READ — and
            // HOST_COHERENT only removes the need for `vkInvalidateMappedMemoryRanges`, not
            // for this. Without it the readback in `infer` works on unified-memory parts and
            // is undefined on others, which is the worst kind of bug to leave in.
            let host_visible = vk::BufferMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(self.staging.buffer)
                .offset(0)
                .size(vk::WHOLE_SIZE);
            device.cmd_pipeline_barrier(
                buffer,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[],
                std::slice::from_ref(&host_visible),
                &[],
            );

            device
                .end_command_buffer(buffer)
                .map_err(|e| format!("end_command_buffer {e:?}"))
        }
    }

    /// # Safety
    ///
    /// `buffer` must be inside a `begin`/`end` pair.
    unsafe fn barrier(&self, buffer: vk::CommandBuffer) {
        // Whole-buffer rather than per-range: consecutive ops address overlapping parts
        // of one arena by design, so there is nothing to narrow to.
        let barrier = vk::BufferMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::SHADER_WRITE | vk::AccessFlags::TRANSFER_WRITE)
            .dst_access_mask(
                vk::AccessFlags::SHADER_READ
                    | vk::AccessFlags::SHADER_WRITE
                    | vk::AccessFlags::TRANSFER_READ
                    | vk::AccessFlags::TRANSFER_WRITE,
            )
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .buffer(self.arena.buffer)
            .offset(0)
            .size(vk::WHOLE_SIZE);
        self.context.device.cmd_pipeline_barrier(
            buffer,
            vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::COMPUTE_SHADER | vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[],
            std::slice::from_ref(&barrier),
            &[],
        );
    }

    /// # Safety
    ///
    /// `buffer` must be recorded and not already pending.
    unsafe fn submit_and_wait(&self, buffer: vk::CommandBuffer) -> Result<(), String> {
        let device = &self.context.device;
        let fence = device
            .create_fence(&vk::FenceCreateInfo::default(), None)
            .map_err(|e| format!("create_fence {e:?}"))?;
        let buffers = [buffer];
        let submit = vk::SubmitInfo::default().command_buffers(&buffers);
        let guard = self.context.lock_queue();
        let submitted = device
            .queue_submit(self.context.queue, std::slice::from_ref(&submit), fence)
            .map_err(|e| format!("queue_submit {e:?}"));
        drop(guard);

        let result = submitted.and_then(|()| {
            device
                .wait_for_fences(&[fence], true, FENCE_TIMEOUT_NS)
                .map_err(|e| format!("wait_for_fences {e:?}"))
        });
        // On a timeout the submission is still in flight, and the caller is about to drop
        // both the fence and the staging buffer it reads from. Wait the device out first:
        // destroying either while a queue operation references it is undefined, and a
        // one-off weights upload has nowhere to defer the cleanup to.
        if result.is_err() {
            let guard = self.context.lock_queue();
            let _ = device.device_wait_idle();
            drop(guard);
        }
        device.destroy_fence(fence, None);
        result
    }

    /// Preprocess `pixels`, run the network, and return the mask as `0..1` floats.
    ///
    /// `pixels` is `ARGB_8888` as `Bitmap.getPixels` produces it. The returned mask is
    /// `output_shape.h * output_shape.w` long, row-major.
    ///
    /// Single-input, single-output: this is the bitmap-in, mask-out path the two
    /// segmenters and the face embedder use. It refuses a plan shaped otherwise rather
    /// than silently returning only the first binding.
    pub fn infer(
        &mut self,
        pixels: &[i32],
        width: u32,
        height: u32,
    ) -> Result<Vec<f32>, String> {
        let input = self.plan.input()?;
        // Reject a multi-output plan here rather than returning only the first binding:
        // `output_scratch` spans every output, so the concatenation would look like a
        // mask of the wrong size instead of an error.
        let _single_output = self.plan.output()?;
        preprocess::to_planar_f16(
            pixels,
            width,
            height,
            input.shape,
            &self.normalise,
            &mut self.input_scratch,
        )?;
        self.submit()?;
        Ok(self.output_scratch.iter().map(|&h| preprocess::f16_to_f32(h)).collect())
    }

    /// Letterbox `pixels` into the plan's input shape, run, and return **every** output.
    ///
    /// SCRFD's path: nine maps come back, in [`crate::nets::Plan::outputs`] order. `fit`
    /// must have been computed for the shape this net was built at, which
    /// [`preprocess::Letterbox::square`] guarantees for a fixed side.
    pub fn infer_letterboxed(
        &mut self,
        pixels: &[i32],
        width: u32,
        height: u32,
        fit: &preprocess::Letterbox,
    ) -> Result<Vec<Vec<f32>>, String> {
        let input = self.plan.input()?;
        if fit.shape() != input.shape {
            return Err(format!(
                "a letterbox of {:?} for a net built at {:?}",
                fit.shape(),
                input.shape
            ));
        }
        preprocess::to_letterboxed_f16(
            pixels,
            width,
            height,
            fit,
            &self.normalise,
            &mut self.input_scratch,
        )?;
        self.submit()?;
        self.split_outputs()
    }

    /// The plan's input shape, so a caller can size the tensor it hands to
    /// [`Net::infer_raw`] without holding the plan itself.
    pub fn input_shape(&self) -> Result<crate::nets::Shape, String> {
        Ok(self.plan.input()?.shape)
    }

    /// Run the plan over `values` directly, with no preprocessing, and return every output.
    ///
    /// The bitmap paths above exist because most of these networks take an image. Supertonic's
    /// do not: the text encoder takes character ids, the sampler takes a latent and a
    /// conditioning and the vocoder takes the sampler's output, each produced by the previous
    /// stage rather than by a camera. So this is the path for a net whose input is a tensor
    /// someone else computed.
    ///
    /// `values` must be exactly the input shape's element count, in `[c, h, w]` order — the
    /// same order [`crate::nets::Plan`] uses everywhere. It is rounded to fp16 on the way in,
    /// which is the arena's precision, so a caller cannot hand over more accuracy than the
    /// device will keep.
    pub fn infer_raw(&mut self, values: &[f32]) -> Result<Vec<Vec<f32>>, String> {
        let input = self.plan.input()?;
        let wanted = input.shape.len() as usize;
        if values.len() != wanted {
            return Err(format!(
                "{} values for an input of {:?}, which holds {wanted}",
                values.len(),
                input.shape
            ));
        }
        self.input_scratch.clear();
        self.input_scratch.extend(values.iter().map(|&v| preprocess::f32_to_f16(v)));
        self.submit()?;
        self.split_outputs()
    }

    /// [`Net::infer_raw`] for a plan with more than one input, one slice per binding.
    ///
    /// The recorded command buffer packs the inputs end to end from offset 0 in declaration
    /// order, so this is the mirror of `split_outputs`. Supertonic's sampler takes seven — a
    /// latent, two conditionings, the timestep shifts and two rotary angle tables — and getting
    /// them out of order would be a wrong answer at the right shape, so each is checked against
    /// its own binding rather than the total.
    pub fn infer_raw_many(&mut self, inputs: &[&[f32]]) -> Result<Vec<Vec<f32>>, String> {
        if inputs.len() != self.plan.inputs.len() {
            return Err(format!(
                "{} inputs for a plan that declares {}",
                inputs.len(),
                self.plan.inputs.len()
            ));
        }
        self.input_scratch.clear();
        for (binding, values) in self.plan.inputs.iter().zip(inputs) {
            let wanted = binding.shape.len() as usize;
            if values.len() != wanted {
                return Err(format!(
                    "{} values for an input of {:?}, which holds {wanted}",
                    values.len(),
                    binding.shape
                ));
            }
            self.input_scratch.extend(values.iter().map(|&v| preprocess::f32_to_f16(v)));
        }
        self.submit()?;
        self.split_outputs()
    }

    /// Split the concatenated readback into one vector per output binding.
    ///
    /// The recorded command buffer packs the outputs end to end from offset 0 in the
    /// staging buffer, in declaration order, so this is the mirror of `record`.
    fn split_outputs(&self) -> Result<Vec<Vec<f32>>, String> {
        let mut outputs = Vec::with_capacity(self.plan.outputs.len());
        let mut at = 0usize;
        for binding in &self.plan.outputs {
            let len = binding.shape.len() as usize;
            let end = at.checked_add(len).ok_or("an output offset overflowed")?;
            let slice = self
                .output_scratch
                .get(at..end)
                .ok_or_else(|| format!("the readback is shorter than {end} elements"))?;
            outputs.push(slice.iter().map(|&h| preprocess::f16_to_f32(h)).collect());
            at = end;
        }
        Ok(outputs)
    }

    /// Upload `input_scratch`, submit the recorded buffer, and read `output_scratch` back.
    ///
    /// Factored out of the two `infer` variants because it is the whole of the unsafe,
    /// order-sensitive part: everything about poisoning, the fence and the queue lock is
    /// here once rather than twice.
    fn submit(&mut self) -> Result<(), String> {
        if self.poisoned {
            return Err("this network is unusable after an earlier failure".into());
        }
        // SAFETY: the scratch buffer is exactly the fp16 elements the recorded copy
        // regions move, and `u16` has no padding or invalid bit patterns.
        let bytes = unsafe {
            std::slice::from_raw_parts(
                self.input_scratch.as_ptr().cast::<u8>(),
                std::mem::size_of_val(self.input_scratch.as_slice()),
            )
        };
        self.staging.write(bytes)?;

        let device = &self.context.device;
        let buffers = [self.command_buffer];
        let submit = vk::SubmitInfo::default().command_buffers(&buffers);
        // SAFETY: the fence is reset before the submit and waited on after it, so the
        // command buffer is never resubmitted while pending and the staging buffer is
        // never read while the GPU is writing it. `poisoned` is what keeps that true when
        // the wait times out. The queue and the pool are shared process-wide, hence the
        // lock around the submit; the fence wait is deliberately outside it.
        unsafe {
            device.reset_fences(&[self.fence]).map_err(|e| format!("reset_fences {e:?}"))?;
            // Poison first: from here until the wait returns, a submission may be pending,
            // and every path out of that state other than a successful wait is one this
            // net cannot recover from.
            self.poisoned = true;
            let guard = self.context.lock_queue();
            let submitted = device
                .queue_submit(self.context.queue, std::slice::from_ref(&submit), self.fence)
                .map_err(|e| format!("queue_submit {e:?}"));
            drop(guard);
            submitted?;
            device
                .wait_for_fences(&[self.fence], true, FENCE_TIMEOUT_NS)
                .map_err(|e| format!("wait_for_fences {e:?}"))?;
            self.poisoned = false;
        }

        self.staging.read_f16(&mut self.output_scratch)
    }

    /// The mask's dimensions, which is what the Kotlin wrapper reports to its caller.
    pub fn output_size(&self) -> Result<(u32, u32), String> {
        let output = self.plan.output()?;
        Ok((output.shape.w, output.shape.h))
    }

    /// Every output binding, so a multi-output caller can size and shape the maps
    /// [`Net::infer_letterboxed`] returns without rebuilding the plan.
    pub fn output_shapes(&self) -> &[crate::nets::Binding] {
        &self.plan.outputs
    }
}

/// Total elements across `bindings`, which is how much staging space one direction needs.
fn binding_elems(bindings: &[crate::nets::Binding]) -> usize {
    bindings.iter().map(|b| b.shape.len() as usize).sum()
}

/// Long enough that a slow first dispatch on a cold driver is not mistaken for a hang,
/// short enough that a genuinely hung GPU does not block a UI thread forever. `:camera`
/// runs this on a dedicated executor, `:photos` on its own thread, so neither blocks the
/// main thread even at the limit.
const FENCE_TIMEOUT_NS: u64 = 5_000_000_000;

fn push_bytes(push: &crate::nets::Push) -> &[u8] {
    // SAFETY: `Push` is `repr(C)` and entirely `u32`, so it has no padding and no
    // uninitialised bytes; it is read as exactly its own size.
    unsafe {
        std::slice::from_raw_parts(
            (push as *const crate::nets::Push).cast::<u8>(),
            std::mem::size_of::<crate::nets::Push>(),
        )
    }
}

impl Drop for Net {
    fn drop(&mut self) {
        // SAFETY: `vkDeviceWaitIdle` returns only once nothing on the device is pending, so
        // after it everything below is safe to destroy — including the case where `infer`
        // timed out and left a submission in flight, which is why `poisoned` only has to
        // block further submits and not the teardown.
        //
        // The result is ignored because the only ways it fails are device loss and host
        // OOM. After a lost device the spec explicitly permits destroying objects, and a
        // host OOM here means the process is about to die anyway; in both cases leaking
        // every handle instead would be worse.
        //
        // The lock is held across the wait because `vkDeviceWaitIdle` needs every queue on
        // the device to itself. Destroying the pool frees the command buffer with it, and needs
        // no lock because the pool is this net's alone. The three `Buffer` fields free
        // themselves afterwards through their own Drop, which runs after this body.
        unsafe {
            let device = &self.context.device;
            let guard = self.context.lock_queue();
            let _ = device.device_wait_idle();
            drop(guard);
            device.destroy_fence(self.fence, None);
            device.destroy_command_pool(self.command_pool, None);
            self.pipelines.destroy(device);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The GLSL `Params` block is written by hand, so the two layouts agree only by inspection.
    /// This is the part of that agreement a compiler can hold: `std430` packs a struct of `uint`s
    /// end to end, so the Rust side must be `u32`s with no padding and nothing else.
    #[test]
    fn the_step_params_block_matches_the_shader_layout() {
        assert_eq!(std::mem::size_of::<StepParams>(), 2 * 4, "two u32 fields, no padding");
        assert_eq!(std::mem::align_of::<StepParams>(), 4);
        assert_eq!(StepParams::default().as_bytes().len(), std::mem::size_of::<StepParams>());
        assert!(
            (std::mem::size_of::<StepParams>() as vk::DeviceSize) <= StepParams::BYTES,
            "the allocation must cover the struct",
        );
    }

    /// Field order is the whole contract with the shader, and a reorder is invisible to the type
    /// system. Distinct values placed through the struct must land at distinct known offsets.
    #[test]
    fn the_step_params_fields_are_in_the_declared_order() {
        let params = StepParams { prefix: 0x1111_1111, window_start: 0x2222_2222 };
        let bytes = params.as_bytes();
        assert_eq!(bytes.get(0..4), Some(&0x1111_1111u32.to_ne_bytes()[..]), "prefix first");
        assert_eq!(bytes.get(4..8), Some(&0x2222_2222u32.to_ne_bytes()[..]), "window_start second");
    }
}