//! Device memory and the buffers geometry lives in.
//!
//! # Host-visible vertex buffers, and no staging copies
//!
//! On a desktop GPU you upload through a staging buffer because device memory is across
//! a PCIe bus. Mobile GPUs are **unified memory** — Adreno and Mali both expose memory
//! that is `DEVICE_LOCAL | HOST_VISIBLE` — so the vertex data can be written straight
//! into the buffer the GPU reads, and the staging buffer, its allocation, the copy
//! command and the barrier all disappear.
//!
//! That matters here more than usual: tiles arrive continuously while panning, so an
//! upload happens several times a second, and the plan expects the CPU to be the
//! bottleneck.

use ash::vk;

/// A buffer and the memory behind it.
pub struct Buffer {
    pub buffer: vk::Buffer,
    pub memory: vk::DeviceMemory,
    pub size: vk::DeviceSize,
}

impl Buffer {
    /// Allocate a host-writable buffer and fill it with `contents`.
    ///
    /// # Safety
    ///
    /// `device` must outlive the returned buffer, and the caller must call
    /// [`Buffer::destroy`] before dropping the device.
    pub unsafe fn upload<T: Copy>(
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
        device: &ash::Device,
        usage: vk::BufferUsageFlags,
        contents: &[T],
    ) -> Result<Buffer, String> {
        let size = std::mem::size_of_val(contents) as vk::DeviceSize;
        if size == 0 {
            return Err("refusing to allocate a zero-length buffer".into());
        }

        let create_info = vk::BufferCreateInfo::default()
            .size(size)
            .usage(usage)
            .sharing_mode(vk::SharingMode::EXCLUSIVE);
        let buffer = device
            .create_buffer(&create_info, None)
            .map_err(|e| format!("create_buffer {e:?}"))?;

        let requirements = device.get_buffer_memory_requirements(buffer);
        let properties = instance.get_physical_device_memory_properties(physical_device);
        // Prefer memory that is both device-local and host-visible, which on a unified
        // mobile GPU is the common case and needs no staging copy. Fall back to plain
        // host-visible, which is always present.
        let memory_type = find_memory_type(
            &properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::DEVICE_LOCAL
                | vk::MemoryPropertyFlags::HOST_VISIBLE
                | vk::MemoryPropertyFlags::HOST_COHERENT,
        )
        .or_else(|| {
            find_memory_type(
                &properties,
                requirements.memory_type_bits,
                vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
            )
        });
        let memory_type = match memory_type {
            Some(t) => t,
            None => {
                device.destroy_buffer(buffer, None);
                return Err("no host-visible memory type for a vertex buffer".into());
            }
        };

        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(memory_type);
        let memory = match device.allocate_memory(&allocate, None) {
            Ok(m) => m,
            Err(e) => {
                device.destroy_buffer(buffer, None);
                return Err(format!("allocate_memory {e:?}"));
            }
        };
        if let Err(e) = device.bind_buffer_memory(buffer, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            return Err(format!("bind_buffer_memory {e:?}"));
        }

        // HOST_COHERENT, so no explicit flush is needed.
        match device.map_memory(memory, 0, size, vk::MemoryMapFlags::empty()) {
            Ok(mapped) => {
                std::ptr::copy_nonoverlapping(
                    contents.as_ptr() as *const u8,
                    mapped as *mut u8,
                    size as usize,
                );
                device.unmap_memory(memory);
            }
            Err(e) => {
                device.free_memory(memory, None);
                device.destroy_buffer(buffer, None);
                return Err(format!("map_memory {e:?}"));
            }
        }

        Ok(Buffer { buffer, memory, size })
    }

    /// # Safety
    ///
    /// The device must be idle, or the buffer must not be referenced by any command
    /// buffer still executing.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_buffer(self.buffer, None);
        device.free_memory(self.memory, None);
    }
}

fn find_memory_type(
    properties: &vk::PhysicalDeviceMemoryProperties,
    allowed: u32,
    flags: vk::MemoryPropertyFlags,
) -> Option<u32> {
    (0..properties.memory_type_count).find(|&i| {
        allowed & (1 << i) != 0
            && properties.memory_types[i as usize].property_flags.contains(flags)
    })
}
