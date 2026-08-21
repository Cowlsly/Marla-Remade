/*
 * Placement for the Qorvo l1_config persistent-configuration blob.
 *
 * The prebuilt FiRa bundle emits .l1_config_persist_storage (1004 bytes of default L1
 * configuration) and .l1_config_persist_storage_sha256 (its checksum) as their own
 * sections, expecting the platform's linker script to place them. Without this they land
 * as orphan sections, which the linker warns about and which leaves
 * l1_config_get_persistent_memory() pointing somewhere unintended.
 *
 * NOTE: this puts the blob in rodata, alongside code. That is correct for *reading* the
 * compiled-in defaults, which is all this firmware needs — per-unit calibration is read
 * from the DW3110's own OTP at init, and qflash_write() is deliberately unimplemented
 * (see qhal_extras_zephyr.c). If persisting a modified L1 config is ever needed, this
 * has to move to a dedicated flash partition first: erasing a page here would erase
 * code with it.
 */
. = ALIGN(4);
KEEP(*(.l1_config_persist_storage))
. = ALIGN(4);
KEEP(*(.l1_config_persist_storage_sha256))
. = ALIGN(4);
