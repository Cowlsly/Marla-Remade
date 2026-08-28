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
use crate::weights::Weights;

use super::buffers::Buffer;
use super::context::Context;
use super::pipeline::{Pipelines, MAX_WORKGROUPS_PER_DIM, WORKGROUP};

/// A compiled, recorded network ready to run.
pub struct Net {
    context: Arc<Context>,
    plan: Plan,
    normalise: Normalise,
    weights: Buffer,
    arena: Buffer,
    staging: Buffer,
    pipelines: Pipelines,
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
    /// Set when a submission was left pending, after which this net is unusable.
    ///
    /// A `wait_for_fences` timeout returns an error while the submission is **still in
    /// flight**. Nothing can cancel it, so resetting the fence, resubmitting the command
    /// buffer or rewriting the staging buffer would all be illegal — and since the
    /// five-second timeout exists precisely so a hung GPU does not block forever, this is
    /// an anticipated path rather than a theoretical one. Once poisoned, every later
    /// `infer` fails immediately without touching Vulkan, and only `Drop` — which waits
    /// for the device to go idle first — cleans up.
    poisoned: bool,
    /// Scratch for the fp16 input and the fp16 output, so an inference allocates nothing.
    input_scratch: Vec<u16>,
    output_scratch: Vec<u16>,
}

impl Net {
    /// Allocate, upload the weights and record the plan.
    ///
    /// `weights` is consumed for its data but not retained: once the blob is in
    /// device-local memory the host copy is dropped, which for U^2-Netp gives back 2.1 MB
    /// of heap.
    pub fn new(
        context: Arc<Context>,
        plan: Plan,
        weights: &Weights,
        normalise: Normalise,
    ) -> Result<Net, String> {
        let arena_bytes = (plan.arena_elems as vk::DeviceSize) * 2;
        // Vulkan forbids a zero-sized buffer, and the descriptor set needs a real one bound to
        // the weights binding whether or not any shader reads it. A plan can legitimately read
        // no weights at all — a purely elementwise one does — so this floors the allocation
        // rather than refusing the plan.
        let weights_bytes = (weights.data().len() as vk::DeviceSize).max(2);
        let input_elems = binding_elems(&plan.inputs);
        let output_elems = binding_elems(&plan.outputs);
        // One staging buffer for both directions: the inputs and the outputs are never in
        // flight at the same time, because an inference is a single blocking submit. Each
        // side packs its bindings end to end from offset 0, in declaration order.
        let staging_bytes = (input_elems.max(output_elems) as vk::DeviceSize) * 2;

        let weights_buffer = Buffer::device_local(&context, weights_bytes)?;
        let arena = Buffer::device_local(&context, arena_bytes)?;
        let staging = Buffer::staging(&context, staging_bytes)?;

        let pipelines = Pipelines::new(
            &context,
            arena.buffer,
            arena_bytes,
            weights_buffer.buffer,
            weights_bytes,
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
            pipelines,
            command_pool,
            command_buffer: vk::CommandBuffer::null(),
            fence: vk::Fence::null(),
            poisoned: false,
            input_scratch: vec![0u16; input_elems],
            output_scratch: vec![0u16; output_elems],
        };

        net.upload_weights(weights.data())?;
        net.command_buffer = net.allocate_command_buffer()?;
        net.fence = net.create_fence()?;
        net.record()?;
        Ok(net)
    }

    /// Copy the weights blob to device-local memory through the staging buffer.
    ///
    /// Its own one-shot command buffer and its own staging allocation, because the
    /// permanent staging buffer is sized for one input and the weights are ten times
    /// that. Both are freed before returning; this happens once per net.
    fn upload_weights(&self, data: &[u8]) -> Result<(), String> {
        if data.is_empty() {
            return Ok(());
        }
        let staging = Buffer::staging(&self.context, data.len() as vk::DeviceSize)?;
        staging.write(data)?;
        let command_buffer = self.allocate_command_buffer()?;
        // SAFETY: the command buffer was just allocated from this device's pool and is
        // freed on every path below; the copy is bounds-checked by construction, since both
        // buffers are exactly `data.len()` bytes.
        unsafe {
            let device = &self.context.device;
            let record = || -> Result<(), String> {
                device
                    .begin_command_buffer(
                        command_buffer,
                        &vk::CommandBufferBeginInfo::default()
                            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
                    )
                    .map_err(|e| format!("begin_command_buffer {e:?}"))?;
                let region = vk::BufferCopy::default().size(data.len() as vk::DeviceSize);
                device.cmd_copy_buffer(
                    command_buffer,
                    staging.buffer,
                    self.weights.buffer,
                    std::slice::from_ref(&region),
                );
                device
                    .end_command_buffer(command_buffer)
                    .map_err(|e| format!("end_command_buffer {e:?}"))?;
                self.submit_and_wait(command_buffer)
            };
            let outcome = record();
            device.free_command_buffers(self.command_pool, &[command_buffer]);
            // `staging` drops here, after the fence has been waited on.
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

            for op in &self.plan.ops {
                match *op {
                    Op::Dispatch { kind, push, invocations } => {
                        device.cmd_bind_pipeline(
                            buffer,
                            vk::PipelineBindPoint::COMPUTE,
                            self.pipelines.for_kind(kind),
                        );
                        device.cmd_bind_descriptor_sets(
                            buffer,
                            vk::PipelineBindPoint::COMPUTE,
                            self.pipelines.layout,
                            0,
                            &[self.pipelines.descriptor_set],
                            &[],
                        );
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
    /// The bitmap paths above exist because most of these networks take an image. Piper's do
    /// not: the text encoder takes phoneme ids, the flow takes a sampled prior and the
    /// vocoder takes a latent, all produced by the previous stage rather than by a camera. So
    /// this is the path for a net whose input is a tensor someone else computed.
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
            return Err("a previous submission timed out; this network is unusable".into());
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
