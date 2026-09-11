//! Validating a persisted `VkPipelineCache` blob before it reaches the driver.
//!
//! The bytes of a pipeline cache are opaque driver output, and they are read back from a file that
//! may have been written by different hardware, a different driver version, or a launch that died
//! part-way through the write. Vulkan defines a fixed 32-byte header precisely so this can be
//! checked, and this module is that check.
//!
//! It lives outside [`crate::vulkan`], which is Android-only, because it is pure byte parsing of
//! untrusted input and therefore exactly the kind of thing the host test suite should cover. The
//! handle management that uses it is in [`crate::vulkan::cache`].

/// `VkPipelineCacheHeaderVersionOne`: length, version, vendor, device, then the 16-byte UUID.
pub const HEADER_BYTES: usize = 32;
/// `VK_PIPELINE_CACHE_HEADER_VERSION_ONE`, the only version defined.
const HEADER_VERSION_ONE: u32 = 1;

/// Whether `bytes` is a pipeline cache the device described by `vendor_id`, `device_id` and
/// `uuid` can actually use.
///
/// The driver is required to detect a foreign blob itself, but "required to detect" is not the
/// same as "safe to hand anything to on every implementation", and the header exists to be
/// checked. Vendor and device catch a blob copied from other hardware; `uuid` is the one that
/// catches a driver update on the *same* hardware, which is the common case and the one that
/// would otherwise feed a stale blob to the shader compiler on the first frame after a system
/// update.
pub fn usable(bytes: &[u8], vendor_id: u32, device_id: u32, uuid: &[u8; 16]) -> bool {
    if bytes.len() < HEADER_BYTES {
        return false;
    }
    let word =
        |at: usize| u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]);
    // A future header version is allowed to be longer than 32 bytes, but it can never be longer
    // than the blob carrying it — that combination means a truncated or corrupt file.
    if word(0) as usize > bytes.len() {
        return false;
    }
    word(4) == HEADER_VERSION_ONE
        && word(8) == vendor_id
        && word(12) == device_id
        && &bytes[16..HEADER_BYTES] == uuid
}

#[cfg(test)]
mod tests {
    use super::*;

    const VENDOR: u32 = 0x5143;
    const DEVICE: u32 = 0x6030_0001;
    const UUID: [u8; 16] = [7; 16];

    /// A header, followed by `payload` bytes standing in for the driver's own data.
    fn blob(length: u32, version: u32, vendor: u32, device: u32, uuid: [u8; 16], payload: usize) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&length.to_le_bytes());
        bytes.extend_from_slice(&version.to_le_bytes());
        bytes.extend_from_slice(&vendor.to_le_bytes());
        bytes.extend_from_slice(&device.to_le_bytes());
        bytes.extend_from_slice(&uuid);
        bytes.resize(HEADER_BYTES + payload, 0xAB);
        bytes
    }

    #[test]
    fn a_blob_written_by_this_device_is_usable() {
        assert!(usable(&blob(32, 1, VENDOR, DEVICE, UUID, 128), VENDOR, DEVICE, &UUID));
    }

    #[test]
    fn a_blob_from_other_hardware_is_rejected() {
        let vendor = blob(32, 1, 0x10DE, DEVICE, UUID, 128);
        assert!(!usable(&vendor, VENDOR, DEVICE, &UUID), "a different vendor must not be trusted");
        let device = blob(32, 1, VENDOR, 0x0000_0002, UUID, 128);
        assert!(!usable(&device, VENDOR, DEVICE, &UUID), "a different device must not be trusted");
    }

    /// The case a vendor/device check alone misses: same phone, driver updated under it.
    #[test]
    fn a_blob_from_an_older_driver_on_the_same_gpu_is_rejected() {
        let stale = blob(32, 1, VENDOR, DEVICE, [3; 16], 128);
        assert!(!usable(&stale, VENDOR, DEVICE, &UUID));
    }

    #[test]
    fn a_truncated_blob_is_rejected() {
        let full = blob(32, 1, VENDOR, DEVICE, UUID, 128);
        let short = &full[..HEADER_BYTES - 1];
        assert!(!usable(short, VENDOR, DEVICE, &UUID), "shorter than a header");
        // A header claiming a length the file does not contain: a part-written blob.
        let overrun = blob(4096, 1, VENDOR, DEVICE, UUID, 128);
        assert!(!usable(&overrun, VENDOR, DEVICE, &UUID), "header longer than the blob");
    }

    #[test]
    fn an_unknown_header_version_is_rejected() {
        let future = blob(32, 2, VENDOR, DEVICE, UUID, 128);
        assert!(!usable(&future, VENDOR, DEVICE, &UUID));
    }

    /// What a failed write leaves behind. Must read as "cold", not panic on the slicing above.
    #[test]
    fn an_empty_blob_is_rejected_without_panicking() {
        assert!(!usable(&[], VENDOR, DEVICE, &UUID));
    }
}
