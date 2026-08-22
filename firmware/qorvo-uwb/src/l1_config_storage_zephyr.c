/*
 * RAM-backed storage for the Qorvo l1_config "persistent" configuration.
 *
 * l1_config's storage has to be writable: `internal_l1_config` has no initializer, so the
 * stored hash never matches on a fresh device and l1_config_init() always calls
 * reset_to_default() and then stores the result. As shipped, the storage is two sections
 * the bundle places in the image, and on this target that lands in flash — where a store
 * would mean erasing a page shared with code.
 *
 * Making the linker put those sections in RAM was tried four ways and abandoned: rodata
 * boots but the store fails ENOTSUP; .data faults before logging starts; .noinit pushed a
 * kernel variable (slice_expired) into an MPU stack-guard region so the kernel faulted
 * writing its own state; and a dedicated NOLOAD section hung the image before main().
 * Overriding the accessors doesn't work either, because l1_config calls them from inside
 * the same translation unit, so those calls are bound locally and never reach us.
 *
 * What does work: the accessors return two ordinary global pointers, so repointing them at
 * buffers of our own before l1_config_init() runs puts the storage in RAM with no linker
 * surgery and no symbol games. See ff_qorvo_l1_config_use_ram_storage().
 *
 * Not persisting across reset is fine: what matters is this module's antenna delays and
 * crystal trim, and reset_to_default re-reads those from the DW3110's OTP every boot.
 */
#include "ff_qorvo_l1_config.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/toolchain.h>

#include <stdint.h>

LOG_MODULE_REGISTER(qorvo_l1cfg, CONFIG_QORVO_UWB_LOG_LEVEL);

/*
 * Defined by the bundle's l1_config_custom.c, which initialises them to point at its own
 * in-image sections and returns them from l1_config_get_persistent_memory() and
 * l1_config_get_persistent_memory_hash_addr().
 */
extern const uint32_t *persistent_ram_config;
extern const uint32_t *persistent_ram_config_hash;

/*
 * sizeof(struct l1_config) is 1004 bytes in this delivery (the size of the
 * .l1_config_persist_storage section the archive emits), and the struct is only declared in
 * the SDK's internal headers. Sized past that so a larger struct in a future SDK cannot
 * overrun it, since l1_config copies sizeof(struct l1_config) in and out of here.
 */
#define L1_CONFIG_STORAGE_SIZE 2048
/* store_to_persistent_memory writes a full SHA-256 to the hash address. */
#define L1_CONFIG_HASH_SIZE 32

static uint8_t l1_config_storage[L1_CONFIG_STORAGE_SIZE] __aligned(8);
static uint8_t l1_config_hash_storage[L1_CONFIG_HASH_SIZE] __aligned(8);

void ff_qorvo_l1_config_use_ram_storage(void)
{
	persistent_ram_config = (const uint32_t *)l1_config_storage;
	persistent_ram_config_hash = (const uint32_t *)l1_config_hash_storage;

	LOG_INF("l1_config storage redirected to RAM: config %p (%u bytes), hash %p (%u bytes)",
		(void *)l1_config_storage, (unsigned int)sizeof(l1_config_storage),
		(void *)l1_config_hash_storage, (unsigned int)sizeof(l1_config_hash_storage));
}
