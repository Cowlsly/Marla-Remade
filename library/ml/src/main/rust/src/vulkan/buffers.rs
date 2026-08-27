//! Device memory: the weights buffer, the activation arena, and the staging buffer both
//! the input and the output pass through.
//!
//! # Three buffers, and that is all
//!
//! * **weights** — device-local, written once at construction, never again.
//! * **arena** — device-local, every activation in the network. One buffer for all of
//!   them, sub-allocated by element offset in a push constant, which is why the
//!   descriptor set is written once and never touched again.
//! * **staging** — host-visible, and the only thing mapped. Large enough for the input
//!   *or* the output, whichever needs more.
//!
//! # Why staging at all, when `library/map` does not
//!
//! `library/map/src/main/rust/src/vulkan/buffers.rs` writes vertices straight into
//! `DEVICE_LOCAL | HOST_VISIBLE` memory, which mobile GPUs expose because their memory is
//! unified. That is right for vertices: written once by the CPU, read once by the GPU,
//! streaming.
//!
//! It is wrong here. The arena is read and written by the GPU hundreds of times per
//! inference and touched by the CPU twice, and host-visible memory is not always the
//! fastest memory a driver has — on some Adreno and Mali configurations it is uncached
//! or write-combined for the GPU too. So the buffers the shaders hammer are plain
//! `DEVICE_LOCAL`, and the two CPU crossings go through one small host-visible buffer and
//! a `vkCmdCopyBuffer`.

use std::sync::Arc;

use ash::vk;

use super::context::Context;

/// A buffer and the memory bound to it, freed on drop.
///
/// It holds an `Arc<Context>` rather than taking a `&ash::Device` at destruction time, so
/// that a fallible constructor can bail between two allocations without leaking the first.
/// `library/map`'s equivalent has an explicit `destroy` and no `Drop`, which works there
/// because the renderer allocates buffers in one place; here `Net::new` makes three and
/// then does more fallible work, and every `?` between them would be a leak.
pub struct Buffer {
    context: Arc<Context>,
    /// The buffer handle.
    pub buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    /// Bytes requested, which may be less than the memory actually allocated.
    pub size: vk::DeviceSize,
}

impl Buffer {
    /// A device-local buffer of `size` bytes, usable as a storage buffer and a transfer
    /// endpoint.
    pub fn device_local(context: &Arc<Context>, size: vk::DeviceSize) -> Result<Buffer, String> {
        Buffer::new(
            context,
            size,
            vk::BufferUsageFlags::STORAGE_BUFFER
                | vk::BufferUsageFlags::TRANSFER_DST
                | vk::BufferUsageFlags::TRANSFER_SRC,
            vk::MemoryPropertyFlags::DEVICE_LOCAL,
        )
    }

    /// A host-visible, host-coherent buffer for staging bytes in and out.
    pub fn staging(context: &Arc<Context>, size: vk::DeviceSize) -> Result<Buffer, String> {
        Buffer::new(
            context,
            size,
            vk::BufferUsageFlags::TRANSFER_SRC | vk::BufferUsageFlags::TRANSFER_DST,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
        )
    }

    fn new(
        context: &Arc<Context>,
        size: vk::DeviceSize,
        usage: vk::BufferUsageFlags,
        properties: vk::MemoryPropertyFlags,
    ) -> Result<Buffer, String> {
        if size == 0 {
            return Err("refusing to allocate a zero-length buffer".into());
        }
        let device = &context.device;
        let create_info = vk::BufferCreateInfo::default()
            .size(size)
            .usage(usage)
            .sharing_mode(vk::SharingMode::EXCLUSIVE);
        // SAFETY: `create_info` is fully initialised above and the device outlives this
        // buffer, which the caller guarantees by holding the same `Arc<Context>`.
        unsafe {
            let buffer = device
                .create_buffer(&create_info, None)
                .map_err(|e| format!("create_buffer of {size} bytes: {e:?}"))?;
            let requirements = device.get_buffer_memory_requirements(buffer);
            let memory_type = match context.memory_type(requirements.memory_type_bits, properties) {
                Some(t) => t,
                None => {
                    device.destroy_buffer(buffer, None);
                    return Err(format!(
                        "no memory type with properties {:#x}",
                        properties.as_raw()
                    ));
                }
            };
            let allocate = vk::MemoryAllocateInfo::default()
                .allocation_size(requirements.size)
                .memory_type_index(memory_type);
            let memory = match device.allocate_memory(&allocate, None) {
                Ok(m) => m,
                Err(e) => {
                    device.destroy_buffer(buffer, None);
                    return Err(format!("allocate_memory of {} bytes: {e:?}", requirements.size));
                }
            };
            if let Err(e) = device.bind_buffer_memory(buffer, memory, 0) {
                device.free_memory(memory, None);
                device.destroy_buffer(buffer, None);
                return Err(format!("bind_buffer_memory {e:?}"));
            }
            Ok(Buffer { context: Arc::clone(context), buffer, memory, size })
        }
    }

    /// Copy `bytes` into a host-visible buffer.
    ///
    /// Only valid on a [`Buffer::staging`] buffer; a device-local one has no mappable
    /// memory and the map will fail.
    pub fn write(&self, bytes: &[u8]) -> Result<(), String> {
        if bytes.len() as vk::DeviceSize > self.size {
            return Err(format!("{} bytes into a {}-byte buffer", bytes.len(), self.size));
        }
        if bytes.is_empty() {
            return Ok(());
        }
        // SAFETY: the range is bounds-checked above, the memory is HOST_COHERENT so no
        // flush is needed, and the mapping is released before returning on both paths.
        unsafe {
            let mapped = self
                .context
                .device
                .map_memory(self.memory, 0, bytes.len() as vk::DeviceSize, vk::MemoryMapFlags::empty())
                .map_err(|e| format!("map_memory {e:?}"))?;
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), mapped.cast::<u8>(), bytes.len());
            self.context.device.unmap_memory(self.memory);
        }
        Ok(())
    }

    /// Read `out.len()` fp16 elements back out of a host-visible buffer.
    pub fn read_f16(&self, out: &mut [u16]) -> Result<(), String> {
        let bytes = std::mem::size_of_val(out) as vk::DeviceSize;
        if bytes > self.size {
            return Err(format!("{bytes} bytes out of a {}-byte buffer", self.size));
        }
        if out.is_empty() {
            return Ok(());
        }
        // SAFETY: as `write`. The mapped pointer is read as bytes and only then
        // reassembled, rather than cast to `*const u16`, because the mapping's alignment
        // is the driver's business and a misaligned `u16` read is undefined.
        unsafe {
            let mapped = self
                .context
                .device
                .map_memory(self.memory, 0, bytes, vk::MemoryMapFlags::empty())
                .map_err(|e| format!("map_memory {e:?}"))?;
            let source = std::slice::from_raw_parts(mapped.cast::<u8>(), bytes as usize);
            for (slot, chunk) in out.iter_mut().zip(source.chunks_exact(2)) {
                *slot = u16::from_le_bytes([chunk[0], chunk[1]]);
            }
            self.context.device.unmap_memory(self.memory);
        }
        Ok(())
    }
}

impl Drop for Buffer {
    fn drop(&mut self) {
        // SAFETY: `Net`'s own Drop waits for the device to go idle before its buffer
        // fields are dropped, and the only other buffers are the one-shot weights staging
        // one, which is dropped after its fence has been waited on.
        unsafe {
            self.context.device.destroy_buffer(self.buffer, None);
            self.context.device.free_memory(self.memory, None);
        }
    }
}
