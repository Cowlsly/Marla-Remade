//! The `VkPipelineCache` and its blob on disk.
//!
//! Without a cache the driver compiles every shader at pipeline-creation time, and this renderer
//! creates twelve graphics pipelines plus the pick pipeline in one go — at startup, and again on
//! every [`rebuild`](crate::vulkan::renderer) a resize or rotation triggers. That work lands
//! inside a frame on the UI thread, which is where the multi-second stalls came from.
//!
//! The in-memory half of that is what `VkPipelineCache` is for, but on its own it only helps
//! within a session: the first resize after every cold start would still pay the full compile.
//! So the blob is written back to a file and reloaded on the next launch, which is the part that
//! actually removes the stall a user sees.
//!
//! Nothing here is fatal. A missing, unreadable, corrupt or foreign blob starts the cache empty,
//! and a cache the driver refuses to create at all leaves a null handle — which is precisely the
//! behaviour this module replaced, so the map still draws.
//!
//! The header check that decides whether a blob is usable is [`crate::pipeline_cache`], kept
//! outside this Android-only module so the host suite can cover it.

use ash::vk;
use std::path::{Path, PathBuf};

/// The blob, inside the directory Kotlin hands over.
const FILE_NAME: &str = "pipelines.bin";

pub struct ShaderCache {
    /// Passed to every `create_graphics_pipelines` call. Null when the driver would not give us
    /// one, which `vkCreateGraphicsPipelines` accepts as "no cache".
    handle: vk::PipelineCache,
    /// `None` when no directory was supplied, which makes this an in-memory cache.
    path: Option<PathBuf>,
    /// Size of the blob currently on disk, so [`persist`](Self::persist) can tell an unchanged
    /// cache from one the driver has added entries to. Zero when the file was absent or rejected,
    /// so the first write always happens.
    on_disk: usize,
}

impl ShaderCache {
    /// Load the blob from `dir` if there is a usable one there, and create the cache.
    ///
    /// # Safety
    ///
    /// `device` must be live, and `physical_device` the device it was created from.
    pub unsafe fn open(
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
        device: &ash::Device,
        dir: Option<&Path>,
    ) -> ShaderCache {
        let path = dir.map(|dir| dir.join(FILE_NAME));
        let properties = instance.get_physical_device_properties(physical_device);
        let blob = path.as_deref().and_then(|path| {
            let bytes = std::fs::read(path).ok()?;
            crate::pipeline_cache::usable(
                &bytes,
                properties.vendor_id,
                properties.device_id,
                &properties.pipeline_cache_uuid,
            )
            .then_some(bytes)
        });

        let mut info = vk::PipelineCacheCreateInfo::default();
        if let Some(bytes) = &blob {
            info = info.initial_data(bytes);
        }
        let handle = match device.create_pipeline_cache(&info, None) {
            Ok(handle) => handle,
            // The header said this blob belongs to this device and the driver still would not take
            // it. Retry empty rather than give up the cache: an in-memory one is worth having, and
            // the next `persist` replaces the file.
            Err(_) => device
                .create_pipeline_cache(&vk::PipelineCacheCreateInfo::default(), None)
                .unwrap_or_else(|_| vk::PipelineCache::null()),
        };

        let on_disk = if handle == vk::PipelineCache::null() {
            0
        } else {
            blob.as_ref().map_or(0, |bytes| bytes.len())
        };
        ShaderCache { handle, path, on_disk }
    }

    pub fn handle(&self) -> vk::PipelineCache {
        self.handle
    }

    /// Write the cache back, if the driver has put anything new in it.
    ///
    /// The size check is what makes this safe to call from `rebuild`, which runs inside a frame:
    /// once the blob has settled — after the first cold run — every later call reads the data back
    /// and returns without touching the disk.
    ///
    /// Temp-then-rename, as [`crate::tile::cache`] writes its entries, so a launch that dies
    /// part-way through leaves the previous blob intact rather than a truncated one.
    ///
    /// # Safety
    ///
    /// `device` must be the live device this cache was opened on.
    pub unsafe fn persist(&mut self, device: &ash::Device) {
        let Some(path) = self.path.as_deref() else {
            return;
        };
        if self.handle == vk::PipelineCache::null() {
            return;
        }
        let Ok(data) = device.get_pipeline_cache_data(self.handle) else {
            return;
        };
        if data.len() == self.on_disk || data.is_empty() {
            return;
        }
        // The directory is a subdirectory of the one Kotlin hands over and will not exist on a
        // first run. Without this the write fails silently and the cache never persists at all —
        // a failure that looks exactly like a cache that works but never helps.
        if let Some(parent) = path.parent() {
            if std::fs::create_dir_all(parent).is_err() {
                return;
            }
        }
        let temp = path.with_extension("tmp");
        if std::fs::write(&temp, &data).is_ok() && std::fs::rename(&temp, path).is_ok() {
            self.on_disk = data.len();
        } else {
            let _ = std::fs::remove_file(&temp);
        }
    }

    /// # Safety
    ///
    /// The device must be idle, and no pipeline creation in flight.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        if self.handle != vk::PipelineCache::null() {
            device.destroy_pipeline_cache(self.handle, None);
        }
    }
}
