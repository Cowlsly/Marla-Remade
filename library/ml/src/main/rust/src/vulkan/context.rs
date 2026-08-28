//! Instance, physical device, logical device and compute queue.
//!
//! Modelled on `library/map/src/main/rust/src/vulkan/context.rs`, with three deliberate
//! differences: a `VK_QUEUE_COMPUTE_BIT` queue instead of graphics-and-present, no
//! surface, swapchain, `VK_KHR_android_surface` or `ANativeWindow` at all, and two
//! device features that have to be asked for explicitly.
//!
//! # fp16 is not free at Vulkan 1.1
//!
//! minSdk 31 guarantees Vulkan 1.1. `VK_KHR_shader_float16_int8` was promoted to core
//! only in **1.2**, so at the 1.1 floor it is an optional device extension whose feature
//! struct must be chained onto `VkDeviceCreateInfo`:
//!
//! * `shaderFloat16` — from `VK_KHR_shader_float16_int8`, for `float16_t` arithmetic and
//!   the fp16-to-fp32 conversions every shader does when it loads a weight.
//! * `storageBuffer16BitAccess` — from `VkPhysicalDevice16BitStorageFeatures`, which
//!   *is* core in 1.1 but still off unless enabled, for a storage buffer of
//!   `float16_t`.
//!
//! Both are queried through `vkGetPhysicalDeviceFeatures2` before being requested, and a
//! device missing either is skipped. If no device has both, [`Context::new`] fails and
//! the feature turns off in Kotlin — the GPU-only decision, with no fp32 fallback path to
//! keep correct.
//!
//! Neither existing renderer in this repo enables any device feature, so there is no
//! in-repo precedent for the `push_next` chaining below.
//!
//! # One device, process-wide
//!
//! `:camera` holds two segmenters at once — the preview analyzer and the still renderer,
//! deliberately separate per `PortraitBokeh.kt` — and creating a `VkDevice` per segmenter
//! would double the driver's allocations for no benefit. So [`shared`] hands out a
//! reference-counted context and only the last user tears it down.

use ash::vk;
use ash::Entry;
use std::ffi::{c_char, CStr, CString};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, Weak};

/// The single highest-value thing owning Vulkan buys us: without it a wrong descriptor
/// or a missing barrier is undefined behaviour that happens to work on one driver.
/// Debug builds only — the layer is not on a user's device and costs real time.
const VALIDATION_LAYER: &CStr = c"VK_LAYER_KHRONOS_validation";

/// A logical device with a compute queue, and the pool commands are recorded from.
pub struct Context {
    /// Kept alive because `ash::Instance`'s function pointers borrow from it.
    pub entry: Entry,
    /// The instance.
    pub instance: ash::Instance,
    /// The physical device chosen in [`Context::new`].
    pub physical_device: vk::PhysicalDevice,
    /// The logical device.
    pub device: ash::Device,
    /// A queue with `COMPUTE` support. Reach it through [`Context::lock_queue`].
    pub queue: vk::Queue,
    /// The family [`Context::queue`] came from.
    pub queue_family_index: u32,
    /// Guards the queue above.
    ///
    /// `VkQueue` is *externally synchronised* in the Vulkan sense: the application must not
    /// call `vkQueueSubmit` on one queue from two threads at once, and `vkDeviceWaitIdle` needs
    /// every queue on the device to itself.
    ///
    /// The queue is shared process-wide, so per-network locking is not enough, and `:camera`
    /// really does break it: `BokehAnalyzer` submits from `bokehExecutor` at ~15 fps while
    /// `StillBokehRenderer` - a deliberately separate segmenter, per `PortraitBokeh.kt` -
    /// submits from `Dispatchers.IO` when the shutter is pressed, and pressing the shutter
    /// does not stop the analysis stream. The locks in `BokehAnalyzer` and
    /// `StillBokehRenderer` each serialise their *own* segmenter and cannot see each
    /// other.
    ///
    /// `VkCommandPool` is externally synchronised too, and is deliberately **not** here: each
    /// [`crate::vulkan::run::Net`] owns its own pool. A pool is synchronised across recording
    /// as well as across allocation - every `vkCmd*` and `vkBeginCommandBuffer` counts - so one
    /// shared pool would have to be locked for the whole of a net's `record`, and a lock held
    /// that long by a net being built would stall a net that is merely submitting.
    queue_lock: Mutex<()>,
}

impl Context {
    /// Bring up Vulkan with a compute queue and fp16 support, or explain why not.
    pub fn new() -> Result<Context, String> {
        // SAFETY: every call below is a Vulkan entry point whose arguments are
        // constructed here, and every early return destroys what it created.
        unsafe { Self::create() }
    }

    unsafe fn create() -> Result<Context, String> {
        let entry = Entry::load().map_err(|e| format!("no Vulkan loader: {e:?}"))?;

        let app_name = CString::new("modelrunner").map_err(|e| format!("{e}"))?;
        let app_info = vk::ApplicationInfo::default()
            .application_name(&app_name)
            .engine_name(&app_name)
            // 1.1 is the floor at minSdk 31. Asking for 1.2 to get fp16 in core would
            // exclude devices that have the extension.
            .api_version(vk::make_api_version(0, 1, 1, 0));

        let mut layers: Vec<*const c_char> = Vec::new();
        if cfg!(debug_assertions) && Self::has_layer(&entry, VALIDATION_LAYER) {
            layers.push(VALIDATION_LAYER.as_ptr());
        }
        // No instance extensions at all: there is no surface to create.
        let create_info = vk::InstanceCreateInfo::default()
            .application_info(&app_info)
            .enabled_layer_names(&layers);
        let instance = entry
            .create_instance(&create_info, None)
            .map_err(|e| format!("create_instance {e:?}"))?;

        let chosen = Self::pick_device(&instance);
        let (physical_device, queue_family_index) = match chosen {
            Some(c) => c,
            None => {
                instance.destroy_instance(None);
                return Err(
                    "no device offers a compute queue with shaderFloat16 and \
                     storageBuffer16BitAccess. VK_KHR_shader_float16_int8 is a Vulkan 1.2 \
                     promotion, so this device is too old for fp16 compute."
                        .into(),
                );
            }
        };

        let priorities = [1.0f32];
        let queue_info = vk::DeviceQueueCreateInfo::default()
            .queue_family_index(queue_family_index)
            .queue_priorities(&priorities);
        let extensions = [ash::khr::shader_float16_int8::NAME.as_ptr()];
        let mut float16 =
            vk::PhysicalDeviceShaderFloat16Int8Features::default().shader_float16(true);
        let mut storage16 =
            vk::PhysicalDevice16BitStorageFeatures::default().storage_buffer16_bit_access(true);
        let device_info = vk::DeviceCreateInfo::default()
            .queue_create_infos(std::slice::from_ref(&queue_info))
            .enabled_extension_names(&extensions)
            .push_next(&mut float16)
            .push_next(&mut storage16);
        let device = match instance.create_device(physical_device, &device_info, None) {
            Ok(d) => d,
            Err(e) => {
                instance.destroy_instance(None);
                return Err(format!("create_device {e:?}"));
            }
        };
        let queue = device.get_device_queue(queue_family_index, 0);
        Ok(Context {
            entry,
            instance,
            physical_device,
            device,
            queue,
            queue_family_index,
            queue_lock: Mutex::new(()),
        })
    }

    /// The first device with a compute queue and both fp16 features.
    ///
    /// No scoring between devices: an Android phone has one GPU, and a machine that
    /// somehow reports two would still only be asked to run a 2 GMAC network.
    unsafe fn pick_device(instance: &ash::Instance) -> Option<(vk::PhysicalDevice, u32)> {
        let devices = instance.enumerate_physical_devices().ok()?;
        for &physical_device in &devices {
            if !Self::supports_float16(instance, physical_device) {
                continue;
            }
            let families = instance.get_physical_device_queue_family_properties(physical_device);
            // Any family with COMPUTE will do. A dedicated compute family (COMPUTE
            // without GRAPHICS) would let this run alongside the UI with less
            // contention, but is rare on mobile and not worth a second code path.
            let family = families
                .iter()
                .position(|f| f.queue_flags.contains(vk::QueueFlags::COMPUTE));
            // `continue`, not `?`: a device without a compute family must not stop the
            // enumeration of the ones after it.
            if let Some(family) = family {
                return Some((physical_device, family as u32));
            }
        }
        None
    }

    /// Whether both features can actually be enabled, asked before asking for them.
    ///
    /// Requesting a feature the device does not have is undefined behaviour rather than
    /// an error the driver returns, so this query is not optional.
    unsafe fn supports_float16(
        instance: &ash::Instance,
        physical_device: vk::PhysicalDevice,
    ) -> bool {
        let extensions = match instance.enumerate_device_extension_properties(physical_device) {
            Ok(e) => e,
            Err(_) => return false,
        };
        let has_extension = extensions.iter().any(|e| {
            CStr::from_ptr(e.extension_name.as_ptr()) == ash::khr::shader_float16_int8::NAME
        });
        if !has_extension {
            return false;
        }

        let mut float16 = vk::PhysicalDeviceShaderFloat16Int8Features::default();
        let mut storage16 = vk::PhysicalDevice16BitStorageFeatures::default();
        let mut features = vk::PhysicalDeviceFeatures2::default()
            .push_next(&mut float16)
            .push_next(&mut storage16);
        instance.get_physical_device_features2(physical_device, &mut features);
        float16.shader_float16 == vk::TRUE && storage16.storage_buffer16_bit_access == vk::TRUE
    }

    unsafe fn has_layer(entry: &Entry, wanted: &CStr) -> bool {
        entry
            .enumerate_instance_layer_properties()
            .map(|layers| {
                layers
                    .iter()
                    .any(|l| CStr::from_ptr(l.layer_name.as_ptr()) == wanted)
            })
            .unwrap_or(false)
    }

    /// Take the lock that makes [`Context::queue`] safe to touch.
    /// See [`Context::queue_lock`].
    ///
    /// Hold it across `vkQueueSubmit` and across `vkDeviceWaitIdle` - but **not** across
    /// `vkWaitForFences`, which needs no external synchronisation and would otherwise let one
    /// network's five-second timeout stall another's.
    ///
    /// A poisoned lock is recovered rather than propagated: it guards no invariant of its
    /// own, only the sequencing of the calls under it, and refusing to segment for the rest
    /// of the process because an unrelated thread panicked would be worse than proceeding.
    pub fn lock_queue(&self) -> MutexGuard<'_, ()> {
        self.queue_lock.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A memory type satisfying `flags` out of those `allowed` by a resource.
    pub fn memory_type(&self, allowed: u32, flags: vk::MemoryPropertyFlags) -> Option<u32> {
        // SAFETY: a plain property query on a device we created.
        let properties =
            unsafe { self.instance.get_physical_device_memory_properties(self.physical_device) };
        (0..properties.memory_type_count).find(|&i| {
            allowed & (1 << i) != 0
                && properties
                    .memory_types
                    .get(i as usize)
                    .is_some_and(|t| t.property_flags.contains(flags))
        })
    }
}

impl Drop for Context {
    fn drop(&mut self) {
        // SAFETY: everything the device owns is dropped before this, because every owner
        // holds an `Arc<Context>`. The lock is taken because `vkDeviceWaitIdle` requires
        // exclusive access to every queue on the device.
        unsafe {
            let guard = self.lock_queue();
            let _ = self.device.device_wait_idle();
            drop(guard);
            self.device.destroy_device(None);
            self.instance.destroy_instance(None);
        }
    }
}

/// The process-wide context, if anything still holds it.
///
/// `Weak`, not `Arc`: when the last segmenter closes, the device and its driver
/// allocations go away rather than staying resident for the life of the app. Reopening
/// pays instance and device creation again, which is a few milliseconds against a user
/// action.
static SHARED: OnceLock<Mutex<Weak<Context>>> = OnceLock::new();

/// The shared context, creating it if no one currently holds one.
///
/// `:camera` builds two segmenters — `BokehAnalyzer` for the preview and
/// `StillBokehRenderer` for the capture — and must not end up with two devices.
pub fn shared() -> Result<Arc<Context>, String> {
    let slot = SHARED.get_or_init(|| Mutex::new(Weak::new()));
    // A poisoned lock means a previous caller panicked mid-creation. Recovering the
    // guard is right here: the invariant is only "this Weak is either live or dead", and
    // both are safe to observe.
    let mut guard = slot.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(existing) = guard.upgrade() {
        return Ok(existing);
    }
    let context = Arc::new(Context::new()?);
    *guard = Arc::downgrade(&context);
    Ok(context)
}
