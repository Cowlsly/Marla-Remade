//! What a plain `vkCmdCopyBuffer` achieves on this device.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example report_copy_ceiling
//! ```
//!
//! # Why this is the denominator
//!
//! Every kernel bandwidth figure in this crate is quoted against a claim that "a plain
//! `vkCmdCopyBuffer` reaches 5.0 GB/s" (`crate::vulkan::imageprobe`, header). That number was
//! taken on a Tensor G4. This measures the same thing on whatever device it is run on, so the
//! claim can be checked rather than inherited.
//!
//! A copy moves bytes twice - one read and one write - so the figure reported here is
//! `2 * size / seconds`, the total traffic the memory system carried. The one-directional
//! read rate a shader can hope for is roughly half of it, which is the number to compare
//! against `report_read_path`'s storage-buffer column.
//!
//! Deliberately uses the transfer path and nothing else: no shader, no descriptors, no
//! pipeline. Whatever this reports is the floor under which no explanation involving shader
//! quality can hide.
use std::sync::Arc;

use ash::vk;
use modelrunner::vulkan::buffers::Buffer;
use modelrunner::vulkan::context::{self, Context};

/// Bytes per buffer. Matches `imageprobe`'s sweep so the two are comparable.
const BYTES: u64 = 256 * 1024 * 1024;

/// Timed passes. The first is discarded: it pays for first-touch and lazy allocation.
const PASSES: usize = 6;

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => {
            println!("no usable Vulkan device: {why}");
            return;
        }
    };
    match measure(&context) {
        Ok(seconds) => {
            let moved = (BYTES * 2) as f64;
            println!("copy      {} MB src -> dst", BYTES / (1024 * 1024));
            println!("best      {:.2} ms", seconds * 1000.0);
            println!();
            println!("traffic   {:.2} GB/s  (read + write, {:.0} MB moved)", moved / seconds / 1e9, moved / 1e6);
            println!("one-way   {:.2} GB/s  (compare against report_read_path's storage column)", BYTES as f64 / seconds / 1e9);
        }
        Err(why) => println!("the copy probe failed: {why}"),
    }
}

/// Seconds for the fastest of [`PASSES`] copies, the first discarded.
fn measure(context: &Arc<Context>) -> Result<f64, String> {
    let device = &context.device;
    let src = Buffer::device_local_usage(
        context,
        BYTES,
        vk::BufferUsageFlags::TRANSFER_SRC | vk::BufferUsageFlags::TRANSFER_DST,
    )?;
    let dst = Buffer::device_local_usage(
        context,
        BYTES,
        vk::BufferUsageFlags::TRANSFER_SRC | vk::BufferUsageFlags::TRANSFER_DST,
    )?;

    // SAFETY: every object created here is used only on this thread and destroyed below,
    // after the fence has been waited on so nothing is still in flight.
    unsafe {
        let command_pool = device
            .create_command_pool(
                &vk::CommandPoolCreateInfo::default()
                    .queue_family_index(context.queue_family_index)
                    .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER),
                None,
            )
            .map_err(|e| format!("command pool: {e:?}"))?;
        let command_buffer = *device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(command_pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(1),
            )
            .map_err(|e| format!("allocate: {e:?}"))?
            .first()
            .ok_or("no command buffer")?;
        let fence = device
            .create_fence(&vk::FenceCreateInfo::default(), None)
            .map_err(|e| format!("fence: {e:?}"))?;

        device
            .begin_command_buffer(command_buffer, &vk::CommandBufferBeginInfo::default())
            .map_err(|e| format!("begin: {e:?}"))?;
        let region = [vk::BufferCopy::default().src_offset(0).dst_offset(0).size(BYTES)];
        device.cmd_copy_buffer(command_buffer, src.buffer, dst.buffer, &region);
        device.end_command_buffer(command_buffer).map_err(|e| format!("end: {e:?}"))?;

        let mut best = f64::MAX;
        for pass in 0..PASSES {
            let started = std::time::Instant::now();
            device.reset_fences(&[fence]).map_err(|e| format!("reset: {e:?}"))?;
            let buffers = [command_buffer];
            let submit = vk::SubmitInfo::default().command_buffers(&buffers);
            let guard = context.lock_queue();
            let sent = device
                .queue_submit(context.queue, std::slice::from_ref(&submit), fence)
                .map_err(|e| format!("submit: {e:?}"));
            drop(guard);
            sent?;
            device
                .wait_for_fences(&[fence], true, 20_000_000_000)
                .map_err(|e| format!("wait: {e:?}"))?;
            if pass > 0 {
                best = best.min(started.elapsed().as_secs_f64());
            }
        }

        device.destroy_fence(fence, None);
        device.destroy_command_pool(command_pool, None);
        Ok(best)
    }
}
