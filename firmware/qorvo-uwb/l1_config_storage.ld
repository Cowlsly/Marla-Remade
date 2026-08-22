/*
 * Placement for the Qorvo l1_config sections.
 *
 * The prebuilt FiRa bundle emits .l1_config_persist_storage and
 * .l1_config_persist_storage_sha256 as their own sections and expects the platform's
 * linker script to place them. Left unplaced they become orphan sections, which the
 * linker warns about.
 *
 * They are placed in rodata and then go unused: src/l1_config_storage_zephyr.c overrides
 * the accessors l1_config reads them through, pointing them at a writable RAM buffer
 * instead. See that file for why RAM placement via the linker was abandoned — every
 * variant of it disturbed a RAM layout the kernel depends on. This snippet exists purely
 * to keep the build free of orphan-section warnings.
 */
. = ALIGN(4);
KEEP(*(.l1_config_persist_storage))
. = ALIGN(4);
KEEP(*(.l1_config_persist_storage_sha256))
. = ALIGN(4);
