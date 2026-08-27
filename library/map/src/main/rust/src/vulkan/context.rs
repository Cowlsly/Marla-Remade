//! Instance, physical device, logical device, queue and the Android surface.
//!
//! Modelled on `games/voxels/src/main/rust/src/vulkan/context.rs`, the in-repo
//! precedent. `ANativeWindow_fromSurface` comes from `libandroid`, so a Java `Surface`
//! becomes a `VkSurfaceKHR` with no native code of our own beyond this declaration.

use ash::vk;
use ash::Entry;
use std::ffi::{c_char, CStr, CString};
use std::os::raw::c_void;

/// Opaque handle to Android's native window.
#[repr(C)]
pub struct ANativeWindow {
    _private: [u8; 0],
}

#[link(name = "android")]
extern "C" {
    pub fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut ANativeWindow;
    pub fn ANativeWindow_acquire(window: *mut ANativeWindow);
    pub fn ANativeWindow_release(window: *mut ANativeWindow);
}

/// Whether to ask for the validation layer.
///
/// This is the single highest-value thing we gain by owning Vulkan, and the thing Dawn
/// was doing for us: without it an incorrect descriptor, a wrong image layout or a
/// missing barrier is undefined behaviour that happens to work on one driver. Debug
/// builds only — the layer is not present on a user's device and costs real time.
const VALIDATION_LAYER: &CStr = c"VK_LAYER_KHRONOS_validation";

pub struct Context {
    pub entry: Entry,
    pub instance: ash::Instance,
    pub surface_loader: ash::khr::surface::Instance,
    pub surface: vk::SurfaceKHR,
    pub physical_device: vk::PhysicalDevice,
    pub device: ash::Device,
    pub queue_family_index: u32,
    pub queue: vk::Queue,
}

impl Context {
    /// # Safety
    ///
    /// `window` must be a live `ANativeWindow` the caller has acquired; it must outlive
    /// this context.
    pub unsafe fn new(window: *mut ANativeWindow) -> Result<Context, String> {
        let entry = Entry::load().map_err(|e| format!("no libvulkan.so: {e:?}"))?;

        let app_name = CString::new("map_renderer").expect("static string");
        let extensions = [ash::khr::surface::NAME.as_ptr(), ash::khr::android_surface::NAME.as_ptr()];
        let app_info = vk::ApplicationInfo::default()
            .application_name(&app_name)
            .engine_name(&app_name)
            // 1.1 is the floor on every device at minSdk 31, and all we need.
            .api_version(vk::make_api_version(0, 1, 1, 0));

        let mut layers: Vec<*const c_char> = Vec::new();
        if cfg!(debug_assertions) && Self::has_layer(&entry, VALIDATION_LAYER) {
            layers.push(VALIDATION_LAYER.as_ptr());
        }
        let create_info = vk::InstanceCreateInfo::default()
            .application_info(&app_info)
            .enabled_extension_names(&extensions)
            .enabled_layer_names(&layers);
        let instance = entry
            .create_instance(&create_info, None)
            .map_err(|e| format!("create_instance {e:?}"))?;

        let surface_loader = ash::khr::surface::Instance::new(&entry, &instance);
        let android_loader = ash::khr::android_surface::Instance::new(&entry, &instance);
        let surface_info = vk::AndroidSurfaceCreateInfoKHR::default().window(window as *mut _);
        let surface = android_loader
            .create_android_surface(&surface_info, None)
            .map_err(|e| format!("create_android_surface {e:?}"))?;

        // A queue family that can both draw and present to this surface. Every Android
        // GPU has one; looking for separate families would only add a transfer path
        // nothing here needs.
        let devices = instance
            .enumerate_physical_devices()
            .map_err(|e| format!("enumerate_physical_devices {e:?}"))?;
        let mut chosen: Option<(vk::PhysicalDevice, u32)> = None;
        for &physical_device in &devices {
            let families = instance.get_physical_device_queue_family_properties(physical_device);
            for (index, family) in families.iter().enumerate() {
                if !family.queue_flags.contains(vk::QueueFlags::GRAPHICS) {
                    continue;
                }
                let presents = surface_loader
                    .get_physical_device_surface_support(physical_device, index as u32, surface)
                    .unwrap_or(false);
                if presents {
                    chosen = Some((physical_device, index as u32));
                    break;
                }
            }
            if chosen.is_some() {
                break;
            }
        }
        let (physical_device, queue_family_index) = match chosen {
            Some(c) => c,
            None => {
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                return Err("no device can both draw and present to this surface".into());
            }
        };

        let priorities = [1.0f32];
        let queue_info = vk::DeviceQueueCreateInfo::default()
            .queue_family_index(queue_family_index)
            .queue_priorities(&priorities);
        let device_extensions = [ash::khr::swapchain::NAME.as_ptr()];
        let device_info = vk::DeviceCreateInfo::default()
            .queue_create_infos(std::slice::from_ref(&queue_info))
            .enabled_extension_names(&device_extensions);
        let device = match instance.create_device(physical_device, &device_info, None) {
            Ok(d) => d,
            Err(e) => {
                surface_loader.destroy_surface(surface, None);
                instance.destroy_instance(None);
                return Err(format!("create_device {e:?}"));
            }
        };
        let queue = device.get_device_queue(queue_family_index, 0);

        Ok(Context {
            entry,
            instance,
            surface_loader,
            surface,
            physical_device,
            device,
            queue_family_index,
            queue,
        })
    }

    unsafe fn has_layer(entry: &Entry, wanted: &CStr) -> bool {
        entry
            .enumerate_instance_layer_properties()
            .map(|layers| {
                layers.iter().any(|l| {
                    CStr::from_ptr(l.layer_name.as_ptr()) == wanted
                })
            })
            .unwrap_or(false)
    }
}

impl Drop for Context {
    fn drop(&mut self) {
        unsafe {
            // Everything the device owns must already be gone; the renderer's own Drop
            // runs before this because it holds the Context.
            let _ = self.device.device_wait_idle();
            self.device.destroy_device(None);
            self.surface_loader.destroy_surface(self.surface, None);
            self.instance.destroy_instance(None);
        }
    }
}
